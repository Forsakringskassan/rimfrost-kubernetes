package fk.rimfrost;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
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
import se.fk.rimfrost.regel.rtf.manuell.jaxrsspec.controllers.generatedsource.model.Beslutsutfall;

public class SmokeTestIT extends RimfrostTestSupport
{
   private static final String handlaggningDoneTopic = "handlaggning-done";
   private static KafkaConsumer<String, String> handlaggningDoneConsumer;

   @BeforeAll
   static void setup() throws Exception
   {
      for (String url : List.of(HANDLAGGNING_BASE_URL, OUL_BASE_URL, RTF_MANUELL_BASE_URL, BEKRAFTABESLUT_BASE_URL))
      {
         waitForService(url);
      }
      handlaggningDoneConsumer = createKafkaConsumer(handlaggningDoneTopic);
   }

   @AfterAll
   static void teardown()
   {
      handlaggningDoneConsumer.close();
   }

   static KafkaConsumer<String, String> createKafkaConsumer(String topic)
   {
      String bootstrap = System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9094");
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

   private static boolean hasHandlaggningId(String json, String handlaggningId)
   {
      try
      {
         JsonNode root = mapper.readTree(json);
         return handlaggningId.equals(root.path("handlaggningId").asText(null));
      }
      catch (Exception e)
      {
         return false;
      }
   }

   private String getKafkaMessage(KafkaConsumer<String, String> consumer, String handlaggningId)
   {
      int maxAttempts = 15;
      for (int attempt = 0; attempt < maxAttempts; attempt++)
      {
         System.out.printf("Polling kafka topic waiting for handlaggningId: %s%n", handlaggningId);
         ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(2));
         for (ConsumerRecord<String, String> record : records)
         {
            String value = record.value();
            System.out.printf("-- Found kafka message with handlaggningId: %s%n", value);
            if (hasHandlaggningId(value, handlaggningId))
            {
               return value;
            }
         }
      }
      return fail("No Kafka message with handlaggningId " + handlaggningId + " received after " + maxAttempts + " attempts");
   }

   private static int sendDoneOperation(String baseUrl, String handlaggningId, String regelUrl)
         throws IOException, InterruptedException
   {
      var request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + regelUrl + "/" + handlaggningId + "/done"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      return response.statusCode();
   }

   @DisplayName("Smoke test för VAH flöde")
   @ParameterizedTest(name = "POST med personnummer={0}")
   @CsvSource(
   {
         "19900101-9999, 7d4a6c38-348b-4f46-9278-b1bfeabc0353, 2025-12-24, 2025-12-24, 3f439f0d-a915-42cb-ba8f-6a4170c6011f"
   })
   void smoke_test_vah_request(String individPnr, String erbjudandeId, String startdag, String slutdag, String handlaggareId)
         throws IOException, InterruptedException
   {
      var yrkandeFrom = LocalDate.parse(startdag).atStartOfDay().atOffset(OffsetDateTime.now().getOffset());
      var yrkandeTom = LocalDate.parse(slutdag).atStartOfDay().atOffset(OffsetDateTime.now().getOffset());

      // service-handlaggning
      var yrkandeResponse = sendYrkandeRequest(individPnr, erbjudandeId, yrkandeFrom, yrkandeTom);
      var handlaggningId = yrkandeResponse.getHandlaggning().getId();
      assertEquals(yrkandeResponse.getHandlaggning().getYrkande().getId(),
            yrkandeResponse.getHandlaggning().getYrkande().getId());
      assertEquals(yrkandeFrom.toInstant(), yrkandeResponse.getHandlaggning().getYrkande().getYrkandeFrom().toInstant());
      assertEquals(yrkandeTom.toInstant(), yrkandeResponse.getHandlaggning().getYrkande().getYrkandeTom().toInstant());
      assertEquals(erbjudandeId, yrkandeResponse.getHandlaggning().getYrkande().getErbjudandeId());

      // rtf-manuell
      var uppgifterHandlaggareResponse = sendUppgifterHandlaggare(handlaggareId, handlaggningId);
      var regelUrl = uppgifterHandlaggareResponse.getOperativUppgift().getUrl();
      var regelGetDataResponse = sendRegelGetData(String.valueOf(handlaggningId), regelUrl);
      var ersattningId = regelGetDataResponse.getErsattningar().getFirst().getErsattningId();
      assertEquals(handlaggningId, regelGetDataResponse.getHandlaggningId());
      assertEquals(204, sendRegelPatchData(String.valueOf(handlaggningId), regelUrl, Beslutsutfall.JA, ersattningId));
      assertEquals(204, sendDoneOperation(RTF_MANUELL_BASE_URL, String.valueOf(handlaggningId), regelUrl));

      // bekraftabeslut
      uppgifterHandlaggareResponse = sendUppgifterHandlaggare(handlaggareId, handlaggningId);
      regelUrl = uppgifterHandlaggareResponse.getOperativUppgift().getUrl();
      assertEquals(204, sendDoneOperation(BEKRAFTABESLUT_BASE_URL, String.valueOf(handlaggningId), regelUrl));

      // vah — assert kafka done message
      getKafkaMessage(handlaggningDoneConsumer, handlaggningId.toString());
   }
}
