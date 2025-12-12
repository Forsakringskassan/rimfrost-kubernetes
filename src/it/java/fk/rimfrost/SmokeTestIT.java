package fk.rimfrost;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Properties;
import java.util.UUID;

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.fk.rimfrost.KundbehovsflodeDoneMessage;
import se.fk.rimfrost.jaxrsspec.controllers.generatedsource.model.*;
import se.fk.rimfrost.regel.rtf.manuell.jaxrsspec.controllers.generatedsource.model.Beslutsutfall;
import se.fk.rimfrost.regel.rtf.manuell.jaxrsspec.controllers.generatedsource.model.GetDataResponse;
import se.fk.rimfrost.regel.rtf.manuell.jaxrsspec.controllers.generatedsource.model.PatchDataRequest;

public class SmokeTestIT {

    // Base URL configurable via environment variable or system property
    private static final String KUNDBEHOVSFLODE_BASE_URL =
            System.getenv("KUNDBEHOVSFLODE_BASE_URL") != null ? System.getenv("KUNDBEHOVSFLODE_BASE_URL")
                    : System.getProperty("kundbehovsflodeBaseUrl", "http://localhost:8888");
    private static final String OUL_BASE_URL =
            System.getenv("OUL_BASE_URL") != null ? System.getenv("OUL_BASE_URL")
                    : System.getProperty("oulBaseUrl", "http://localhost:8889");
    private static final String RTF_MANUELL_BASE_URL =
            System.getenv("RTF_MANUELL_BASE_URL") != null ? System.getenv("RTF_MANUELL_BASE_URL")
                    : System.getProperty("regelBaseUrl", "http://localhost:8890");
    private static final String KUNDBEHOV_URL = KUNDBEHOVSFLODE_BASE_URL + "/kundbehov";
    private static final String KUNDBEHOVSFLODE_URL = KUNDBEHOVSFLODE_BASE_URL + "/kundbehovsflode";
    private static final String OUL_URL = OUL_BASE_URL + "/uppgifter/handlaggare";
    private static final String REGEL_URL = RTF_MANUELL_BASE_URL + "/regel";
    private static final HttpClient client = HttpClient.newHttpClient();
    private static ObjectMapper mapper = new ObjectMapper();
    private static KafkaConsumer kundbehovsflodeDoneConsumer;
    private static final String kundbehovsFlodeDoneTopic = "kundbehovsflode-done";

    @BeforeAll
    static void setup()
    {
        kundbehovsflodeDoneConsumer = createKafkaConsumer(kundbehovsFlodeDoneTopic);
    }
    /**
     * Waits for the given URL to become reachable (HTTP 200).
     */
    @SuppressWarnings("SameParameterValue")
    private static void waitForService(String url, int maxAttempts, int sleepSeconds)
            throws InterruptedException {
        for (int i = 0; i < maxAttempts; i++) {
            try {
                var request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(3))
                        .GET()
                        .build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    System.out.println("Service is up at " + url);
                    return;
                }
            } catch (Exception ignored) {
                System.out.println("Waiting for service at " + url + "...");
            }
            Thread.sleep(sleepSeconds * 1000L);
        }
        fail("Service at " + url + " did not become ready in time");
    }

    static KafkaConsumer<String, String> createKafkaConsumer(String topic)
    {
        String bootstrap = System.getenv().getOrDefault(
                "KAFKA_BOOTSTRAP_SERVERS",
                "localhost:9094"
        );
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-" + System.currentTimeMillis());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(Collections.singletonList(topic));
        return consumer;
    }

    private String readKafkaMessage(KafkaConsumer<String, String> consumer)
    {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(120));
        if (records.isEmpty())
        {
            throw new IllegalStateException("No Kafka message received on topic ");
        }
        // return the first new record
        return records.iterator().next().value();
    }

    private static PostKundbehovResponse sendKundbehovRequest(String personnummer,
                                                              String formanstyp,
                                                              Period period) throws IOException, InterruptedException {
        var kundbehovRequest = new PostKundbehovRequest();

        kundbehovRequest.setPersnr(personnummer);
        kundbehovRequest.setFormanstyp(formanstyp);
        kundbehovRequest.setPeriod(period);
        String jsonBody = mapper.writeValueAsString(kundbehovRequest);
        var request = HttpRequest.newBuilder()
                .uri(URI.create(KUNDBEHOV_URL))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return mapper.readValue(response.body(), PostKundbehovResponse.class);

    }

    private static PostUppgifterHandlaggareResponse sendUppgifterHandlaggare(String handlaggareId) throws IOException, InterruptedException {


        var request = HttpRequest.newBuilder()
                .uri(URI.create(OUL_URL + "/" + handlaggareId))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response;
        PostUppgifterHandlaggareResponse postUppgifterHandlaggareResponse;
        int maxRetries = 120;
        int attempt = 0;
        do {
            System.out.printf("sendUppgifterHandlaggare attempt: %s waiting for task to be assigned%n", attempt);
            response = httpSendRetries(client, request,  HttpResponse.BodyHandlers.ofString(), 200, 180);
            postUppgifterHandlaggareResponse = mapper.readValue(response.body(), PostUppgifterHandlaggareResponse.class);
            attempt++;
            Thread.sleep(1000);
        } while (postUppgifterHandlaggareResponse.getUppgift() == null && attempt < maxRetries);
        if (postUppgifterHandlaggareResponse.getUppgift() == null) {
            throw new RuntimeException("Ingen uppgift hittades");
        }
        return postUppgifterHandlaggareResponse;
    }

    public static <T> HttpResponse<T> httpSendRetries(
            HttpClient client,
            HttpRequest request,
            HttpResponse.BodyHandler<T> bodyHandler,
            int expectedStatus,
            int numberOfRetries) {

        int attempt = 0;
        HttpResponse<T> response = null;

        while (attempt < numberOfRetries) {
            attempt++;
            try {
                response = client.send(request, bodyHandler);
                if (response.statusCode() == expectedStatus) {
                    System.out.printf("httpSendRetries Attempt %s successful waiting for status %s%n", attempt, expectedStatus);
                    return response; // success
                }
                System.out.printf("httpSendRetries Attempt %s failed with status code %s%n", attempt, response.statusCode());
            } catch (IOException | InterruptedException e) {
                System.out.printf("httpSendRetries Attempt %s failed with exception %s%n", attempt, e.getMessage());
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {}
        }

        throw new RuntimeException("httpSendRetries HTTP call failed after " + numberOfRetries + " attempts.");
    }

    private static GetDataResponse sendRegelGetData(String kundbehovsflodeId, String regeltyp) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(REGEL_URL + "/" + regeltyp+  "/" + kundbehovsflodeId))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return mapper.readValue(response.body(), GetDataResponse.class);

    }

    private static int sendRegelPatchData(String kundbehovsflodeId, String regeltyp, Beslutsutfall beslutsUtfall, UUID ersattningId) throws IOException, InterruptedException {
        var patchDataRequest = new PatchDataRequest();
        patchDataRequest.setBeslutsutfall(beslutsUtfall);
        patchDataRequest.setSignera(true);
        patchDataRequest.setErsattningId(ersattningId);
        patchDataRequest.setAvslagsanledning("-");
        String jsonBody = mapper.writeValueAsString(patchDataRequest);
        var request = HttpRequest.newBuilder()
                .uri(URI.create(REGEL_URL + "/" + regeltyp+  "/" + kundbehovsflodeId))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .method("PATCH", HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }

    private static PostKundbehovsflodeResponse sendKundbehovsflodeRequest(UUID kundbehovsId) throws IOException, InterruptedException {
        var kundbehovsflodeRequest = new PostKundbehovsflodeRequest();
        kundbehovsflodeRequest.setKundbehovId(kundbehovsId);
        var jsonBody = mapper.writeValueAsString(kundbehovsflodeRequest);
        var request = HttpRequest.newBuilder()
                .uri(URI.create(KUNDBEHOVSFLODE_URL))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return mapper.readValue(response.body(), PostKundbehovsflodeResponse.class);
    }

    @DisplayName("Smoke test för VAH flöde")
    @ParameterizedTest(name = "POST med personnummer={0}")
    @CsvSource({
            "19990101-1234, VAH, 2025-12-24, 2025-12-26, 3f439f0d-a915-42cb-ba8f-6a4170c6011f"
    })
    void smokeTest_VahRequest(String personnummer, String formanstyp, String startdag, String slutdag, String handlaggareId) throws IOException, InterruptedException {
        // waitForService(BASE_URL, 10, 5);
        mapper.registerModule(new JavaTimeModule());
        var period = new Period();
        period.setStart(LocalDate.parse(startdag).atStartOfDay().atOffset(OffsetDateTime.now().getOffset()));
        period.setSlut(LocalDate.parse(slutdag).atStartOfDay().atOffset(OffsetDateTime.now().getOffset()));
        // send KundbehovRequest
        PostKundbehovResponse kundbehovResponse =
                sendKundbehovRequest(personnummer, formanstyp, period);
        // send KundbehovsflodeRequest
        PostKundbehovsflodeResponse kundbehovsflodeResponse =
                sendKundbehovsflodeRequest(kundbehovResponse.getKundbehov().getId());
        var kundbehovsflodeId = kundbehovsflodeResponse.getKundbehovsflode().getId();
        assertEquals(kundbehovResponse.getKundbehov().getId(),
                kundbehovsflodeResponse.getKundbehovsflode().getKundbehov().getId());
        assertEquals(period.getStart().toInstant(), kundbehovsflodeResponse.getKundbehovsflode().getKundbehov().getPeriod().getStart().toInstant());
        assertEquals(period.getSlut().toInstant(), kundbehovsflodeResponse.getKundbehovsflode().getKundbehov().getPeriod().getSlut().toInstant());
        assertEquals(formanstyp, kundbehovsflodeResponse.getKundbehovsflode().getKundbehov().getFormanstyp());

        // tilldela uppgift
        var uppgifterHandlaggareResponse = sendUppgifterHandlaggare(handlaggareId);
        assertEquals(kundbehovsflodeId, uppgifterHandlaggareResponse.getUppgift().getKundbehovsflodeId());
        var regelTyp = uppgifterHandlaggareResponse.getUppgift().getRegeltyp();
        // hämta url för uppgift
        var regelGetDataResponse = sendRegelGetData(String.valueOf(kundbehovsflodeId), regelTyp);
        var ersattningId = regelGetDataResponse.getErsattning().getFirst().getErsattningId();
        assertEquals(kundbehovsflodeId, regelGetDataResponse.getKundbehovsflodeId());
        // färdigställ uppgift
        var patchResult = sendRegelPatchData(String.valueOf(kundbehovsflodeId), regelTyp, Beslutsutfall.JA, ersattningId);
        assertEquals(204, patchResult);
        // assert kafka done message
        String kundbehovsflodeDoneJson = readKafkaMessage(kundbehovsflodeDoneConsumer);
        var kundbehovsflodeDone = mapper.readValue(kundbehovsflodeDoneJson, KundbehovsflodeDoneMessage.class);
        assertEquals(kundbehovsflodeResponse.getKundbehovsflode().getId().toString(), kundbehovsflodeDone.getKundbehovsflodeId());
    }
}
