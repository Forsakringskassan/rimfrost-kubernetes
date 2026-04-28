package fk.rimfrost;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import se.fk.rimfrost.jaxrsspec.controllers.generatedsource.model.*;
import se.fk.rimfrost.regel.rtf.manuell.jaxrsspec.controllers.generatedsource.model.Beslutsutfall;
import se.fk.rimfrost.regel.rtf.manuell.jaxrsspec.controllers.generatedsource.model.GetDataResponse;
import se.fk.rimfrost.regel.rtf.manuell.jaxrsspec.controllers.generatedsource.model.PatchErsattningRequest;
import se.fk.rimfrost.regel.rtf.manuell.jaxrsspec.controllers.generatedsource.model.UpdateErsattning;

public class SmokeTestIT {

    // Base URL configurable via environment variable or system property
    private static final String HANDLAGGNING_BASE_URL =
            System.getenv("HANDLAGGNING_BASE_URL") != null ? System.getenv("HANDLAGGNING_BASE_URL")
                    : System.getProperty("handlaggningBaseUrl", "http://localhost:8888");
    private static final String OUL_BASE_URL =
            System.getenv("OUL_BASE_URL") != null ? System.getenv("OUL_BASE_URL")
                    : System.getProperty("oulBaseUrl", "http://localhost:8889");
    private static final String RTF_MANUELL_BASE_URL =
            System.getenv("RTF_MANUELL_BASE_URL") != null ? System.getenv("RTF_MANUELL_BASE_URL")
                    : System.getProperty("regelRtfManuellBaseUrl", "http://localhost:8890");
    private static final String BEKRAFTABESLUT_BASE_URL =
            System.getenv("BEKRAFTABESLUT_BASE_URL") != null ? System.getenv("BEKRAFTABESLUT_BASE_URL")
                    : System.getProperty("regelBekraftabeslutBaseUrl", "http://localhost:8891");

    private static final String YRKANDE_URL = HANDLAGGNING_BASE_URL + "/yrkande";
    private static final String HANDLAGGNING_URL = HANDLAGGNING_BASE_URL + "/handlaggning";
    private static final String OUL_URL = OUL_BASE_URL + "/uppgifter/handlaggare";
    private static final HttpClient client = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();
    private static KafkaConsumer<String, String> handlaggningDoneConsumer;
    private static final String handlaggningDoneTopic = "handlaggning-done";

    @BeforeAll
    static void setup()
    {
        mapper.registerModule(new JavaTimeModule());
        handlaggningDoneConsumer = createKafkaConsumer(handlaggningDoneTopic);
    }

    @AfterAll
    static void tearDown()
    {
        if (handlaggningDoneConsumer != null) {
            handlaggningDoneConsumer.close();
        }
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

    public boolean hasHandlaggningId(String json, String handlaggningId) {
        try {
            JsonNode root = mapper.readTree(json);
            return handlaggningId.equals(
                    root.path("handlaggningId").asText(null)
            );
        } catch (Exception e) {
            return false; // or rethrow, depending on your use case
        }
    }

    private String getKafkaMessage(KafkaConsumer<String, String> consumer, String handlaggningId) {
        // How many poll attempts before giving up
        int maxAttempts = 5;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            System.out.printf("Polling kafka topic waiting for handlaggningId: %s%n", handlaggningId);
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(2));
            for (ConsumerRecord<String, String> record : records) {
                String value = record.value();
                System.out.printf("-- Found kafka message with handlaggningId: %s%n", value);
                if (hasHandlaggningId(value, handlaggningId)) {
                    return value;
                }
            }
        }
        // Nothing matched within the allowed attempts
        return null;
    }


    private static PostYrkandeResponse sendYrkandeRequest(String pnr,
                                                          String erbjudandeId,
                                                          OffsetDateTime yrkandeFrom,
                                                          OffsetDateTime yrkandeTom) throws IOException, InterruptedException {
        var idTyp = new Idtyp();
        idTyp.setTypId("c5f2e2b4-9143-4160-8f4b-30c172f0ac05");
        idTyp.setVarde(pnr);

        var individYrkandeRoll = new IndividYrkandeRoll();
        individYrkandeRoll.setIndivid(idTyp);
        individYrkandeRoll.setYrkandeRollId("80f5f41f-9e55-4fc2-a076-ad5a651e0a9d");

        var produceratResultat = new ProduceratResultat();
        produceratResultat.setId(UUID.randomUUID());
        produceratResultat.setVersion(1);
        produceratResultat.setFrom(yrkandeFrom);
        produceratResultat.setTom(yrkandeTom);
        produceratResultat.setYrkandestatus("e27da561-a8db-4513-8272-ef652b097b16");
        produceratResultat.setTyp("ERSATTNING");
        produceratResultat.setData("{\"belopp\":40000,\"berakningsgrund\":0,\"ersattningstyp\":{\"id\":\"dee75df2-a6e0-493d-8314-ec4c37b96f9c\",\"namn\":\"HUNDBIDRAG\"},\"omfattningProcent\":100,\"beslutsutfall\":\"FU\"}");

        var yrkandeRequest = new PostYrkandeRequest();

        yrkandeRequest.setErbjudandeId(erbjudandeId);
        yrkandeRequest.setYrkandeFrom(yrkandeFrom);
        yrkandeRequest.setYrkandeTom(yrkandeTom);
        yrkandeRequest.setIndividYrkandeRoller(List.of(individYrkandeRoll));
        yrkandeRequest.setProduceradeResultat(List.of(produceratResultat));
        String jsonBody = mapper.writeValueAsString(yrkandeRequest);
        var request = HttpRequest.newBuilder()
                .uri(URI.create(YRKANDE_URL))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return mapper.readValue(response.body(), PostYrkandeResponse.class);

    }

    private static PostUppgifterHandlaggareResponse sendUppgifterHandlaggare(String handlaggareId) throws IOException, InterruptedException {


        var request = HttpRequest.newBuilder()
                .uri(URI.create(OUL_URL + "/116759e4-18fd-4209-849c-90abbd257d22" + "/" + handlaggareId))
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
        } while (postUppgifterHandlaggareResponse.getOperativUppgift() == null && attempt < maxRetries);
        if (postUppgifterHandlaggareResponse.getOperativUppgift() == null) {
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

    private static GetDataResponse sendRegelGetData(String handlaggningId, String regelUrl) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(RTF_MANUELL_BASE_URL + regelUrl + "/" + handlaggningId))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return mapper.readValue(response.body(), GetDataResponse.class);

    }

    private static int sendRegelPatchData(String handlaggningId, String regelUrl, Beslutsutfall beslutsUtfall, UUID ersattningId) throws IOException, InterruptedException
    {
        var updateErsattning = new UpdateErsattning();
        updateErsattning.setErsattningId(ersattningId);
        updateErsattning.setBeslutsutfall(beslutsUtfall);
        updateErsattning.setAvslagsanledning("-");

        var patchErsattningRequest = new PatchErsattningRequest();
        patchErsattningRequest.setErsattningar(List.of(updateErsattning));

        String jsonBody = mapper.writeValueAsString(patchErsattningRequest);
        var request = HttpRequest.newBuilder()
                .uri(URI.create(RTF_MANUELL_BASE_URL + regelUrl +  "/" + handlaggningId))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .method("PATCH", HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }

       private static int sendDoneOperation(String baseUrl, String handlaggningId, String regelUrl) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + regelUrl +  "/" + handlaggningId + "/done"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }

    private static PostHandlaggningResponse sendHandlaggningRequest(UUID yrkandeId) throws IOException, InterruptedException {
        var handlaggningRequest = new PostHandlaggningRequest();
        handlaggningRequest.setYrkandeId(yrkandeId);
        handlaggningRequest.handlaggningspecifikationId(UUID.randomUUID());

        var jsonBody = mapper.writeValueAsString(handlaggningRequest);
        var request = HttpRequest.newBuilder()
                .uri(URI.create(HANDLAGGNING_URL))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return mapper.readValue(response.body(), PostHandlaggningResponse.class);
    }

    @DisplayName("Smoke test för VAH flöde")
    @ParameterizedTest(name = "POST med personnummer={0}")
    @CsvSource({
            "19900101-9999, 7d4a6c38-348b-4f46-9278-b1bfeabc0353, 2025-12-24, 2025-12-24, 3f439f0d-a915-42cb-ba8f-6a4170c6011f"
    })
    void smokeTest_VahRequest(String individPnr, String erbjudandeId, String startdag, String slutdag, String handlaggareId) throws IOException, InterruptedException {
        var yrkandeFrom = LocalDate.parse(startdag).atStartOfDay().atOffset(OffsetDateTime.now().getOffset());
        var yrkandeTom = LocalDate.parse(slutdag).atStartOfDay().atOffset(OffsetDateTime.now().getOffset());

        // service-handlaggning

        // send YrkandeRequest
        PostYrkandeResponse yrkandeResponse =
                sendYrkandeRequest(individPnr, erbjudandeId, yrkandeFrom, yrkandeTom);
        // send HandlaggningRequest
        PostHandlaggningResponse handlaggningResponse =
                sendHandlaggningRequest(yrkandeResponse.getYrkande().getId());
        var handlaggningId = handlaggningResponse.getHandlaggning().getId();
        assertEquals(yrkandeResponse.getYrkande().getId(),
                handlaggningResponse.getHandlaggning().getYrkande().getId());
        assertEquals(yrkandeFrom.toInstant(), handlaggningResponse.getHandlaggning().getYrkande().getYrkandeFrom().toInstant());
        assertEquals(yrkandeTom.toInstant(), handlaggningResponse.getHandlaggning().getYrkande().getYrkandeTom().toInstant());
        assertEquals(erbjudandeId, handlaggningResponse.getHandlaggning().getYrkande().getErbjudandeId());

        // rtf-manuell

        // tilldela uppgift
        var uppgifterHandlaggareResponse = sendUppgifterHandlaggare(handlaggareId);
        assertEquals(handlaggningId, uppgifterHandlaggareResponse.getOperativUppgift().getHandlaggningId());
        var regelUrl = uppgifterHandlaggareResponse.getOperativUppgift().getUrl();
        // hämta url för uppgift
        var regelGetDataResponse = sendRegelGetData(String.valueOf(handlaggningId), regelUrl);
        var ersattningId = regelGetDataResponse.getErsattningar().getFirst().getErsattningId();
        assertEquals(handlaggningId, regelGetDataResponse.getHandlaggningId());
        // färdigställ uppgift
        var patchResult = sendRegelPatchData(String.valueOf(handlaggningId), regelUrl, Beslutsutfall.JA, ersattningId);
        assertEquals(204, patchResult);
        // marker uppgift som klar
        var doneOperationResult = sendDoneOperation(RTF_MANUELL_BASE_URL, String.valueOf(handlaggningId), regelUrl);
        assertEquals(204, doneOperationResult);

        // bekraftabeslut

        // tilldela uppgift
        uppgifterHandlaggareResponse = sendUppgifterHandlaggare(handlaggareId);
        assertEquals(handlaggningId, uppgifterHandlaggareResponse.getOperativUppgift().getHandlaggningId());
        regelUrl = uppgifterHandlaggareResponse.getOperativUppgift().getUrl();
        // markera uppgift som klar
        doneOperationResult = sendDoneOperation(BEKRAFTABESLUT_BASE_URL, String.valueOf(handlaggningId), regelUrl);
        assertEquals(204, doneOperationResult);

        // vah

        // assert kafka done message
        String handlaggningDoneJson = getKafkaMessage(handlaggningDoneConsumer, handlaggningResponse.getHandlaggning().getId().toString());
        assertNotNull(handlaggningDoneJson);
    }
 
}
