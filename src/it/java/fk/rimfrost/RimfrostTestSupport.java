package fk.rimfrost;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import se.fk.rimfrost.jaxrsspec.controllers.generatedsource.model.*;
import se.fk.rimfrost.oul.management.jaxrsspec.controllers.generatedsource.model.UppgiftPage;
import se.fk.rimfrost.regel.rtf.manuell.jaxrsspec.controllers.generatedsource.model.Beslutsutfall;
import se.fk.rimfrost.regel.rtf.manuell.jaxrsspec.controllers.generatedsource.model.GetDataResponse;
import se.fk.rimfrost.regel.rtf.manuell.jaxrsspec.controllers.generatedsource.model.PatchErsattningRequest;
import se.fk.rimfrost.regel.rtf.manuell.jaxrsspec.controllers.generatedsource.model.UpdateErsattning;
import static java.nio.charset.StandardCharsets.UTF_8;

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

   static final String DEPLOYMENT_UPPGIFTSLAGER = "rimfrost-k8s-uppgiftslager";
   static final String DEPLOYMENT_RTF_MANUELL = "rimfrost-k8s-rtf-manuell";

   static final String YRKANDE_URL = HANDLAGGNING_BASE_URL + "/yrkande";
   static final String HANDLAGGNING_URL = HANDLAGGNING_BASE_URL + "/handlaggning";
   static final String OUL_URL = OUL_BASE_URL + "/uppgifter/handlaggare";
   static final String SORTERINGSORDNING_URL = OUL_BASE_URL + "/sorteringsordning";
   static final String UPPGIFTER_URL = OUL_BASE_URL + "/uppgifter";

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
            var request = HttpRequest.newBuilder(URI.create(healthUrl)).GET().timeout(Duration.ofSeconds(5)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 500)
            {
               System.out.println("Service ready: " + healthUrl);
               return;
            }
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
      yrkandeRequest.setHandlaggningspecifikationId(UUID.randomUUID());
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

   static GetUppgifterHandlaggareResponse sendGetUppgifterHandlaggare(String handlaggareId)
         throws IOException, InterruptedException
   {
      var request = HttpRequest.newBuilder()
            .uri(URI.create(OUL_URL + "/" + HANDLAGGARE_ID + "/" + handlaggareId))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      return mapper.readValue(response.body(), GetUppgifterHandlaggareResponse.class);
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

   /**
    * Minimal view of a sorteringsordning response — avoids deserializing the polymorphic Constraint
    * subtypes, which are not Java subtypes of Constraint in the generated JAX-RS spec jar.
    */
   record SorteringsordningInfo(UUID id, int entryCount) {}

   /**
    * POST /sorteringsordning — creates a new sorteringsordning from raw JSON spec; asserts HTTP 201.
    */
   static SorteringsordningInfo sendCreateSorteringsordning(String specJson)
         throws IOException, InterruptedException
   {
      var request = HttpRequest.newBuilder()
            .uri(URI.create(SORTERINGSORDNING_URL))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(specJson))
            .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(201, response.statusCode(), "POST /sorteringsordning");
      return parseSorteringsordningInfo(response.body());
   }

   /**
    * GET /sorteringsordning/{id} — returns the raw HTTP response; use when asserting non-200 status.
    */
   static HttpResponse<String> sendGetSorteringsordningRaw(UUID id)
         throws IOException, InterruptedException
   {
      var request = HttpRequest.newBuilder()
            .uri(URI.create(SORTERINGSORDNING_URL + "/" + id))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
      return client.send(request, HttpResponse.BodyHandlers.ofString());
   }

   /**
    * GET /sorteringsordning/{id} — fetches a sorteringsordning by id; asserts HTTP 200.
    */
   static SorteringsordningInfo sendGetSorteringsordning(UUID id)
         throws IOException, InterruptedException
   {
      var response = sendGetSorteringsordningRaw(id);
      assertEquals(200, response.statusCode(), "GET /sorteringsordning/" + id);
      return parseSorteringsordningInfo(response.body());
   }

   /**
    * GET /sorteringsordning/default — fetches the current default sorteringsordning; asserts HTTP 200.
    */
   static SorteringsordningInfo sendGetDefaultSorteringsordning()
         throws IOException, InterruptedException
   {
      var request = HttpRequest.newBuilder()
            .uri(URI.create(SORTERINGSORDNING_URL + "/default"))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode(), "GET /sorteringsordning/default");
      return parseSorteringsordningInfo(response.body());
   }

   private static SorteringsordningInfo parseSorteringsordningInfo(String body) throws IOException
   {
      var node = mapper.readTree(body);
      var id = UUID.fromString(node.get("id").asText());
      var entries = node.get("entries");
      var entryCount = entries != null ? entries.size() : 0;
      return new SorteringsordningInfo(id, entryCount);
   }

   /**
    * PUT /sorteringsordning/{id}/default — sets the given sorteringsordning as default.
    *
    * @return the HTTP status code (204 on success, 404 if not found)
    */
   static int sendSetDefaultSorteringsordning(UUID id)
         throws IOException, InterruptedException
   {
      var request = HttpRequest.newBuilder()
            .uri(URI.create(SORTERINGSORDNING_URL + "/" + id + "/default"))
            .timeout(Duration.ofSeconds(10))
            .PUT(HttpRequest.BodyPublishers.noBody())
            .build();
      return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
   }

   /**
    * DELETE /sorteringsordning/{id} — deletes a sorteringsordning.
    *
    * @return the HTTP status code (204 on success, 404 if not found, 409 if it is the default)
    */
   static int sendDeleteSorteringsordning(UUID id)
         throws IOException, InterruptedException
   {
      var request = HttpRequest.newBuilder()
            .uri(URI.create(SORTERINGSORDNING_URL + "/" + id))
            .timeout(Duration.ofSeconds(10))
            .DELETE()
            .build();
      return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
   }

   /**
    * GET /uppgifter?limit=N&offset=M[&sorteringsordningId=X] — fetches a page of uppgifter.
    * Pass {@code null} for {@code sorteringsordningId} to use the current default.
    */
   static UppgiftPage sendGetUppgifterPage(int limit, int offset, UUID sorteringsordningId)
         throws IOException, InterruptedException
   {
      var url = UPPGIFTER_URL + "?limit=" + limit + "&offset=" + offset;
      if (sorteringsordningId != null)
      {
         url += "&sorteringsordningId=" + sorteringsordningId;
      }
      var request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode(), "GET /uppgifter");
      return mapper.readValue(response.body(), UppgiftPage.class);
   }

   /**
    * Restarts a kubernetes deployment and blocks until the rollout completes.
    */
   static void restartDeployment(String deploymentName) throws IOException, InterruptedException
   {
      System.out.println("Restarting deployment: " + deploymentName);
      var process = new ProcessBuilder("kubectl", "rollout", "restart", "deployment/" + deploymentName)
            .redirectInput(ProcessBuilder.Redirect.INHERIT)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectErrorStream(true)
            .start();
      drainSubprocessIO(process.getInputStream());
      if (process.waitFor() != 0)
      {
         fail("kubectl rollout restart failed for " + deploymentName);
      }
      var rolloutWait = new ProcessBuilder("kubectl", "rollout", "status", "deployment/" + deploymentName, "--timeout=120s")
            .redirectInput(ProcessBuilder.Redirect.INHERIT)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectErrorStream(true)
            .start();
      drainSubprocessIO(rolloutWait.getInputStream());
      if (rolloutWait.waitFor() != 0)
      {
         fail("kubectl rollout status timed out for " + deploymentName);
      }
   }

   /**
    * Waits for the service health endpoint to return a non-error response, restarting the port-forward process
    * whenever it dies. This is necessary because {@code kubectl port-forward} exits when the backend pod is
    * unreachable — which happens immediately after a pod restart, before the new pod starts listening. Without
    * active restarts, all health-check attempts would fail with "connection refused" for the full timeout.
    */
   static void waitForServiceRestartingPortForward(String serviceName, int localPort, String baseUrl,
         int timeoutSeconds) throws IOException, InterruptedException
   {
      System.out.println("Waiting for " + baseUrl + " (restarting port-forward as needed)");
      new ProcessBuilder("sh", "-c", "pkill -f 'kubectl port-forward.*" + localPort + "' 2>/dev/null; true")
            .start().waitFor();
      var deadline = java.time.Instant.now().plusSeconds(timeoutSeconds);
      Process pf = startPortForward(serviceName, localPort);
      while (true)
      {
         if (!pf.isAlive())
         {
            pf = startPortForward(serviceName, localPort);
            Thread.sleep(500);
         }
         else
         {
            try
            {
               var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(baseUrl + "/q/health"))
                     .GET()
                     .timeout(java.time.Duration.ofSeconds(5))
                     .build();
               var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding());
               if (response.statusCode() < 500)
               {
                  System.out.println("Service ready: " + baseUrl);
                  return;
               }
            }
            catch (Exception ignored)
            {
            }
         }
         if (java.time.Instant.now().isAfter(deadline))
            fail("Service not ready after " + timeoutSeconds + "s: " + baseUrl + "/q/health");
         Thread.sleep(1000);
      }
   }

   private static Process startPortForward(String serviceName, int localPort) throws IOException
   {
      return new ProcessBuilder("kubectl", "port-forward", "service/" + serviceName, localPort + ":8080")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
   }

   // Based on solution from https://github.com/allegro/embedded-elasticsearch/pull/48
   // for fixing "Corrupted channel by directly writing to native stream in forked JVM 1"
   // warning.
   private static void drainSubprocessIO(InputStream inputStream) throws IOException
   {
      BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, UTF_8));
      String line;
      while ((line = reader.readLine()) != null)
      {
         System.out.println(line);
      }
   }
}
