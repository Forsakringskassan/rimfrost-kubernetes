package fk.rimfrost;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.fk.rimfrost.jaxrsspec.controllers.generatedsource.model.*;
import se.fk.rimfrost.regel.rtf.manuell.jaxrsspec.controllers.generatedsource.model.Beslutsutfall;
import se.fk.rimfrost.regel.rtf.manuell.jaxrsspec.controllers.generatedsource.model.GetDataResponse;
import se.fk.rimfrost.regel.rtf.manuell.jaxrsspec.controllers.generatedsource.model.PatchErsattningRequest;
import se.fk.rimfrost.regel.rtf.manuell.jaxrsspec.controllers.generatedsource.model.UpdateErsattning;

public class PersistenceIT
{
   private static final String HANDLAGGNING_BASE_URL = System.getenv("HANDLAGGNING_BASE_URL") != null
         ? System.getenv("HANDLAGGNING_BASE_URL")
         : System.getProperty("handlaggningBaseUrl", "http://localhost:8888");
   private static final String OUL_BASE_URL = System.getenv("OUL_BASE_URL") != null
         ? System.getenv("OUL_BASE_URL")
         : System.getProperty("oulBaseUrl", "http://localhost:8889");
   private static final String RTF_MANUELL_BASE_URL = System.getenv("RTF_MANUELL_BASE_URL") != null
         ? System.getenv("RTF_MANUELL_BASE_URL")
         : System.getProperty("regelRtfManuellBaseUrl", "http://localhost:8890");

   private static final String IDTYP_TYP_ID = "c5f2e2b4-9143-4160-8f4b-30c172f0ac05";
   private static final String YRKANDE_ROLL_ID = "80f5f41f-9e55-4fc2-a076-ad5a651e0a9d";
   private static final String YRKANDESTATUS_ID = "e27da561-a8db-4513-8272-ef652b097b16";
   private static final String HANDLAGGARE_ID = "116759e4-18fd-4209-849c-90abbd257d22";
   private static final String TEST_HANDLAGGARE_ID = "3f439f0d-a915-42cb-ba8f-6a4170c6011f";

   private static final String YRKANDE_URL = HANDLAGGNING_BASE_URL + "/yrkande";
   private static final String HANDLAGGNING_URL = HANDLAGGNING_BASE_URL + "/handlaggning";
   private static final String OUL_URL = OUL_BASE_URL + "/uppgifter/handlaggare";

   private static final HttpClient client = HttpClient.newHttpClient();
   private static final ObjectMapper mapper = new ObjectMapper();

   @BeforeAll
   static void setup() throws Exception
   {
      mapper.registerModule(new JavaTimeModule());
      for (String url : List.of(HANDLAGGNING_BASE_URL, OUL_BASE_URL, RTF_MANUELL_BASE_URL))
      {
         waitForService(url);
      }
   }

   static void waitForService(String baseUrl) throws InterruptedException
   {
      waitForService(baseUrl, "/q/health");
   }

   static void waitForService(String baseUrl, String healthPath) throws InterruptedException
   {
      waitForService(baseUrl, healthPath, 60);
   }

   static void waitForService(String baseUrl, String healthPath, int timeoutSeconds) throws InterruptedException
   {
      String healthUrl = baseUrl + healthPath;
      var deadline = java.time.Instant.now().plusSeconds(timeoutSeconds);
      while (true)
      {
         try
         {
            var request = HttpRequest.newBuilder(URI.create(healthUrl)).GET().build();
            client.send(request, HttpResponse.BodyHandlers.discarding());
            System.out.println("Service ready: " + healthUrl);
            return;
         }
         catch (Exception ignored)
         {
         }
         if (java.time.Instant.now().isAfter(deadline))
         {
            fail("Service not ready after " + timeoutSeconds + "s: " + healthUrl);
         }
         Thread.sleep(2000);
      }
   }

   @Test
   @DisplayName("Beslutsutfall survives rtf-manuell pod restart")
   void persisted_data_survives_pod_restart() throws Exception
   {
      var yrkandeFrom = LocalDate.parse("2025-12-24").atStartOfDay().atOffset(OffsetDateTime.now().getOffset());
      var yrkandeTom = LocalDate.parse("2025-12-24").atStartOfDay().atOffset(OffsetDateTime.now().getOffset());

      // Create yrkande and handlaggning
      var yrkandeResponse = sendYrkandeRequest("19900101-9999", "7d4a6c38-348b-4f46-9278-b1bfeabc0353", yrkandeFrom, yrkandeTom);
      var handlaggningResponse = sendHandlaggningRequest(yrkandeResponse.getYrkande().getId());
      var handlaggningId = handlaggningResponse.getHandlaggning().getId();

      // Assign task and patch beslutsutfall to JA
      var uppgifterResponse = sendUppgifterHandlaggare(TEST_HANDLAGGARE_ID);
      assertEquals(handlaggningId, uppgifterResponse.getOperativUppgift().getHandlaggningId());
      var regelUrl = uppgifterResponse.getOperativUppgift().getUrl();

      var getData = sendRegelGetData(String.valueOf(handlaggningId), regelUrl);
      var ersattningId = getData.getErsattningar().getFirst().getErsattningId();
      assertEquals(204, sendRegelPatchData(String.valueOf(handlaggningId), regelUrl, Beslutsutfall.JA, ersattningId));

      // Restart the rtf-manuell pod and re-establish port-forward
      restartDeployment("rimfrost-k8s-rtf-manuell");
      restartPortForward("rimfrost-k8s-rtf-manuell", 8890);
      waitForService(RTF_MANUELL_BASE_URL, "/q/health", 180);

      // Assert data survived the restart
      var afterRestart = sendRegelGetData(String.valueOf(handlaggningId), regelUrl);
      var beslutsutfall = afterRestart.getErsattningar().getFirst().getBeslutsutfall();
      assertEquals(Beslutsutfall.JA, beslutsutfall, "beslutsutfall should be persisted after pod restart");
   }

   private static void restartDeployment(String deploymentName) throws IOException, InterruptedException
   {
      System.out.println("Restarting deployment: " + deploymentName);
      var process = new ProcessBuilder("kubectl", "rollout", "restart", "deployment/" + deploymentName)
            .inheritIO()
            .start();
      int exit = process.waitFor();
      if (exit != 0)
      {
         fail("kubectl rollout restart failed with exit code " + exit);
      }
      // Wait for rollout to complete
      var rolloutWait = new ProcessBuilder("kubectl", "rollout", "status", "deployment/" + deploymentName, "--timeout=120s")
            .inheritIO()
            .start();
      int waitExit = rolloutWait.waitFor();
      if (waitExit != 0)
      {
         fail("kubectl rollout status timed out for " + deploymentName);
      }
   }

   private static void restartPortForward(String serviceName, int localPort) throws IOException, InterruptedException
   {
      System.out.println("Restarting port-forward for " + serviceName + " on port " + localPort);
      new ProcessBuilder("sh", "-c", "pkill -f 'kubectl port-forward.*" + localPort + "' 2>/dev/null; true")
            .start().waitFor();
      Thread.sleep(1000);
      new ProcessBuilder("kubectl", "port-forward", "service/" + serviceName, localPort + ":8080")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
      Thread.sleep(2000);
   }

   private static PostYrkandeResponse sendYrkandeRequest(String pnr, String erbjudandeId,
         OffsetDateTime yrkandeFrom, OffsetDateTime yrkandeTom) throws IOException, InterruptedException
   {
      var idTyp = new Idtyp();
      idTyp.setTypId(IDTYP_TYP_ID);
      idTyp.setVarde(pnr);

      var individYrkandeRoll = new IndividYrkandeRoll();
      individYrkandeRoll.setIndivid(idTyp);
      individYrkandeRoll.setYrkandeRollId(YRKANDE_ROLL_ID);

      var produceratResultat = new ProduceratResultat();
      produceratResultat.setId(UUID.randomUUID());
      produceratResultat.setVersion(1);
      produceratResultat.setFrom(yrkandeFrom);
      produceratResultat.setTom(yrkandeTom);
      produceratResultat.setYrkandestatus(YRKANDESTATUS_ID);
      produceratResultat.setTyp("ERSATTNING");
      produceratResultat.setData(
            "{\"belopp\":40000,\"berakningsgrund\":0,\"ersattningstyp\":{\"id\":\"dee75df2-a6e0-493d-8314-ec4c37b96f9c\",\"namn\":\"HUNDBIDRAG\"},\"omfattningProcent\":100,\"beslutsutfall\":\"FU\"}");

      var yrkandeRequest = new PostYrkandeRequest();
      yrkandeRequest.setErbjudandeId(erbjudandeId);
      yrkandeRequest.setYrkandeFrom(yrkandeFrom);
      yrkandeRequest.setYrkandeTom(yrkandeTom);
      yrkandeRequest.setIndividYrkandeRoller(List.of(individYrkandeRoll));
      yrkandeRequest.setProduceradeResultat(List.of(produceratResultat));

      var request = HttpRequest.newBuilder()
            .uri(URI.create(YRKANDE_URL))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(yrkandeRequest)))
            .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      return mapper.readValue(response.body(), PostYrkandeResponse.class);
   }

   private static PostHandlaggningResponse sendHandlaggningRequest(UUID yrkandeId) throws IOException, InterruptedException
   {
      var handlaggningRequest = new PostHandlaggningRequest();
      handlaggningRequest.setYrkandeId(yrkandeId);
      handlaggningRequest.handlaggningspecifikationId(UUID.randomUUID());

      var request = HttpRequest.newBuilder()
            .uri(URI.create(HANDLAGGNING_URL))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(handlaggningRequest)))
            .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      return mapper.readValue(response.body(), PostHandlaggningResponse.class);
   }

   private static PostUppgifterHandlaggareResponse sendUppgifterHandlaggare(String handlaggareId)
         throws IOException, InterruptedException
   {
      var request = HttpRequest.newBuilder()
            .uri(URI.create(OUL_URL + "/" + HANDLAGGARE_ID + "/" + handlaggareId))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
      PostUppgifterHandlaggareResponse response;
      int attempt = 0;
      do
      {
         System.out.printf("sendUppgifterHandlaggare attempt %d waiting for task%n", attempt);
         var httpResponse = SmokeTestIT.httpSendRetries(client, request, HttpResponse.BodyHandlers.ofString(), 200, 5);
         response = mapper.readValue(httpResponse.body(), PostUppgifterHandlaggareResponse.class);
         attempt++;
         Thread.sleep(1000);
      }
      while (response.getOperativUppgift() == null && attempt < 120);
      if (response.getOperativUppgift() == null)
      {
         fail("Ingen uppgift hittades efter " + attempt + " försök");
      }
      return response;
   }

   private static GetDataResponse sendRegelGetData(String handlaggningId, String regelUrl)
         throws IOException, InterruptedException
   {
      var request = HttpRequest.newBuilder()
            .uri(URI.create(RTF_MANUELL_BASE_URL + regelUrl + "/" + handlaggningId))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      return mapper.readValue(response.body(), GetDataResponse.class);
   }

   private static int sendRegelPatchData(String handlaggningId, String regelUrl, Beslutsutfall beslutsUtfall, UUID ersattningId)
         throws IOException, InterruptedException
   {
      var updateErsattning = new UpdateErsattning();
      updateErsattning.setErsattningId(ersattningId);
      updateErsattning.setBeslutsutfall(beslutsUtfall);
      updateErsattning.setAvslagsanledning("-");

      var patchRequest = new PatchErsattningRequest();
      patchRequest.setErsattningar(List.of(updateErsattning));

      var request = HttpRequest.newBuilder()
            .uri(URI.create(RTF_MANUELL_BASE_URL + regelUrl + "/" + handlaggningId))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .method("PATCH", HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(patchRequest)))
            .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      return response.statusCode();
   }
}
