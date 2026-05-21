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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import se.fk.rimfrost.jaxrsspec.controllers.generatedsource.model.*;
import se.fk.rimfrost.regel.rtf.manuell.jaxrsspec.controllers.generatedsource.model.Beslutsutfall;
import se.fk.rimfrost.regel.rtf.manuell.jaxrsspec.controllers.generatedsource.model.GetDataResponse;
import se.fk.rimfrost.regel.rtf.manuell.jaxrsspec.controllers.generatedsource.model.PatchErsattningRequest;
import se.fk.rimfrost.regel.rtf.manuell.jaxrsspec.controllers.generatedsource.model.UpdateErsattning;

abstract class RimfrostTestSupport
{
   static final String HANDLAGGNING_BASE_URL = System.getenv("HANDLAGGNING_BASE_URL") != null
         ? System.getenv("HANDLAGGNING_BASE_URL")
         : System.getProperty("handlaggningBaseUrl", "http://localhost:8888");
   static final String OUL_BASE_URL = System.getenv("OUL_BASE_URL") != null
         ? System.getenv("OUL_BASE_URL")
         : System.getProperty("oulBaseUrl", "http://localhost:8889");
   static final String RTF_MANUELL_BASE_URL = System.getenv("RTF_MANUELL_BASE_URL") != null
         ? System.getenv("RTF_MANUELL_BASE_URL")
         : System.getProperty("regelRtfManuellBaseUrl", "http://localhost:8890");
   static final String BEKRAFTABESLUT_BASE_URL = System.getenv("BEKRAFTABESLUT_BASE_URL") != null
         ? System.getenv("BEKRAFTABESLUT_BASE_URL")
         : System.getProperty("regelBekraftabeslutBaseUrl", "http://localhost:8891");

   static final String IDTYP_TYP_ID = "c5f2e2b4-9143-4160-8f4b-30c172f0ac05";
   static final String YRKANDE_ROLL_ID = "80f5f41f-9e55-4fc2-a076-ad5a651e0a9d";
   static final String YRKANDESTATUS_ID = "e27da561-a8db-4513-8272-ef652b097b16";
   static final String HANDLAGGARE_ID = "116759e4-18fd-4209-849c-90abbd257d22";

   static final String YRKANDE_URL = HANDLAGGNING_BASE_URL + "/yrkande";
   static final String HANDLAGGNING_URL = HANDLAGGNING_BASE_URL + "/handlaggning";
   static final String OUL_URL = OUL_BASE_URL + "/uppgifter/handlaggare";

   static final HttpClient client = HttpClient.newHttpClient();
   static final ObjectMapper mapper = new ObjectMapper();

   static
   {
      mapper.registerModule(new JavaTimeModule());
   }

   static void waitForService(String baseUrl) throws InterruptedException
   {
      waitForService(baseUrl, "/q/health", 60);
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

   static <T> HttpResponse<T> httpSendRetries(
         HttpClient client,
         HttpRequest request,
         HttpResponse.BodyHandler<T> bodyHandler,
         int expectedStatus,
         int numberOfRetries)
   {
      int attempt = 0;
      HttpResponse<T> response = null;
      while (attempt < numberOfRetries)
      {
         attempt++;
         try
         {
            response = client.send(request, bodyHandler);
            if (response.statusCode() == expectedStatus)
            {
               System.out.printf("httpSendRetries Attempt %s successful waiting for status %s%n", attempt, expectedStatus);
               return response;
            }
            System.out.printf("httpSendRetries Attempt %s failed with status code %s%n", attempt, response.statusCode());
         }
         catch (IOException | InterruptedException e)
         {
            System.out.printf("httpSendRetries Attempt %s failed with exception %s%n", attempt, e.getMessage());
         }
         try
         {
            Thread.sleep(1000);
         }
         catch (InterruptedException ignored)
         {
         }
      }
      throw new RuntimeException("httpSendRetries HTTP call failed after " + numberOfRetries + " attempts.");
   }

   static PostYrkandeResponse sendYrkandeRequest(String pnr, String erbjudandeId,
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

   static PostHandlaggningResponse sendHandlaggningRequest(UUID yrkandeId) throws IOException, InterruptedException
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

   static PostUppgifterHandlaggareResponse sendUppgifterHandlaggare(String handlaggareId, UUID expectedHandlaggningId)
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
         System.out.printf("sendUppgifterHandlaggare attempt %d waiting for task with handlaggningId %s%n", attempt,
               expectedHandlaggningId);
         var httpResponse = httpSendRetries(client, request, HttpResponse.BodyHandlers.ofString(), 200, 5);
         response = mapper.readValue(httpResponse.body(), PostUppgifterHandlaggareResponse.class);
         attempt++;
         Thread.sleep(1000);
      }
      while ((response.getOperativUppgift() == null
            || !expectedHandlaggningId.equals(response.getOperativUppgift().getHandlaggningId()))
            && attempt < 120);
      if (response.getOperativUppgift() == null
            || !expectedHandlaggningId.equals(response.getOperativUppgift().getHandlaggningId()))
      {
         fail("Ingen uppgift med handlaggningId " + expectedHandlaggningId + " hittades efter " + attempt + " försök");
      }
      return response;
   }

   static GetDataResponse sendRegelGetData(String handlaggningId, String regelUrl)
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

   static int sendRegelPatchData(String handlaggningId, String regelUrl, Beslutsutfall beslutsUtfall, UUID ersattningId)
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
