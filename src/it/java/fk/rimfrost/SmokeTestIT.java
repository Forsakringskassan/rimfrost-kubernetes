package fk.rimfrost;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import se.fk.rimfrost.regel.rtf.manuell.jaxrsspec.controllers.generatedsource.model.Beslutsutfall;

public class SmokeTestIT extends RimfrostTestSupport
{
   private static KafkaConsumer<String, String> handlaggningDoneConsumer;

   @BeforeAll
   static void setup() throws Exception
   {
      for (String url : List.of(HANDLAGGNING_BASE_URL, OUL_BASE_URL, RTF_MANUELL_BASE_URL, BEKRAFTABESLUT_BASE_URL))
      {
         waitForService(url);
      }
      handlaggningDoneConsumer = createKafkaConsumer(HANDLAGGNING_DONE_TOPIC);
   }

   @AfterAll
   static void teardown()
   {
      handlaggningDoneConsumer.close();
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
      awaitKafkaMessage(handlaggningDoneConsumer, handlaggningId.toString());
   }
}
