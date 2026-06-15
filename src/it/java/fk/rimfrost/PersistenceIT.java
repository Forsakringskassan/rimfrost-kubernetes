package fk.rimfrost;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.fk.rimfrost.regel.rtf.manuell.jaxrsspec.controllers.generatedsource.model.Beslutsutfall;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

public class PersistenceIT extends RimfrostTestSupport
{
   private static final String TEST_HANDLAGGARE_ID = "3f439f0d-a915-42cb-ba8f-6a4170c6011f";

   @BeforeAll
   static void setup() throws Exception
   {
      for (String url : List.of(HANDLAGGNING_BASE_URL, OUL_BASE_URL, RTF_MANUELL_BASE_URL))
      {
         waitForService(url);
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
      var handlaggningId = yrkandeResponse.getHandlaggning().getId();

      // Assign task and patch beslutsutfall to JA
      var uppgifterResponse = sendUppgifterHandlaggare(TEST_HANDLAGGARE_ID, handlaggningId);
      var regelUrl = uppgifterResponse.getOperativUppgift().getUrl();
      var ersattningId = sendRegelGetData(String.valueOf(handlaggningId), regelUrl).getErsattningar().getFirst()
            .getErsattningId();
      assertEquals(204, sendRegelPatchData(String.valueOf(handlaggningId), regelUrl, Beslutsutfall.JA, ersattningId));

      // Restart the rtf-manuell pod and re-establish port-forward
      restartDeployment("rimfrost-k8s-rtf-manuell");
      waitForServiceRestartingPortForward("rimfrost-k8s-rtf-manuell", 8890, RTF_MANUELL_BASE_URL, 180);

      // Assert data survived the restart
      var beslutsutfall = sendRegelGetData(String.valueOf(handlaggningId), regelUrl).getErsattningar().getFirst()
            .getBeslutsutfall();
      assertEquals(Beslutsutfall.JA, beslutsutfall, "beslutsutfall should be persisted after pod restart");
   }

   @Test
   @DisplayName("Uppgift survives OUL pod restart")
   void oul_persisted_data_survives_pod_restart() throws Exception
   {
      var yrkandeFrom = LocalDate.parse("2025-12-24").atStartOfDay().atOffset(OffsetDateTime.now().getOffset());
      var yrkandeTom = LocalDate.parse("2025-12-24").atStartOfDay().atOffset(OffsetDateTime.now().getOffset());

      // Create yrkande and handlaggning
      var yrkandeResponse = sendYrkandeRequest("19900101-9999", "7d4a6c38-348b-4f46-9278-b1bfeabc0353", yrkandeFrom, yrkandeTom);
      var handlaggningId = yrkandeResponse.getHandlaggning().getId();

      // Assign task
      var uppgifterResponse = sendUppgifterHandlaggare(TEST_HANDLAGGARE_ID, handlaggningId);

      // Restart the uppgiftslager pod and re-establish port-forward
      restartDeployment("rimfrost-k8s-uppgiftslager");
      waitForServiceRestartingPortForward("rimfrost-k8s-uppgiftslager", 8889, OUL_BASE_URL, 180);

      // Assert data survived the restart
      var handlaggareUppgifter = sendGetUppgifterHandlaggare(TEST_HANDLAGGARE_ID);
      var assignedUppgift = handlaggareUppgifter.getOperativaUppgifter().stream()
            .filter(u -> u.getUppgiftId().equals(uppgifterResponse.getOperativUppgift().getUppgiftId())
                  && u.getHandlaggningId().equals(uppgifterResponse.getOperativUppgift().getHandlaggningId()))
            .findFirst();
      assertTrue(assignedUppgift.isPresent(), "uppgift should be persisted after pod restart");
   }

   private static void restartDeployment(String deploymentName) throws IOException, InterruptedException
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
   private static void waitForServiceRestartingPortForward(String serviceName, int localPort, String baseUrl,
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
