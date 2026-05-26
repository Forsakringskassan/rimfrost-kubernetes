package fk.rimfrost;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.fk.rimfrost.regel.rtf.manuell.jaxrsspec.controllers.generatedsource.model.Beslutsutfall;

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
      var handlaggningResponse = sendHandlaggningRequest(yrkandeResponse.getYrkande().getId());
      var handlaggningId = handlaggningResponse.getHandlaggning().getId();

      // Assign task and patch beslutsutfall to JA
      var uppgifterResponse = sendUppgifterHandlaggare(TEST_HANDLAGGARE_ID, handlaggningId);
      var regelUrl = uppgifterResponse.getOperativUppgift().getUrl();
      var ersattningId = sendRegelGetData(String.valueOf(handlaggningId), regelUrl).getErsattningar().getFirst()
            .getErsattningId();
      assertEquals(204, sendRegelPatchData(String.valueOf(handlaggningId), regelUrl, Beslutsutfall.JA, ersattningId));

      // Restart the rtf-manuell pod and re-establish port-forward
      restartDeployment("rimfrost-k8s-rtf-manuell");
      restartPortForward("rimfrost-k8s-rtf-manuell", 8890);
      waitForService(RTF_MANUELL_BASE_URL, "/q/health", 180);

      // Assert data survived the restart
      var beslutsutfall = sendRegelGetData(String.valueOf(handlaggningId), regelUrl).getErsattningar().getFirst()
            .getBeslutsutfall();
      assertEquals(Beslutsutfall.JA, beslutsutfall, "beslutsutfall should be persisted after pod restart");
   }

   private static void restartDeployment(String deploymentName) throws IOException, InterruptedException
   {
      System.out.println("Restarting deployment: " + deploymentName);
      var process = new ProcessBuilder("kubectl", "rollout", "restart", "deployment/" + deploymentName)
            .inheritIO()
            .start();
      if (process.waitFor() != 0)
      {
         fail("kubectl rollout restart failed for " + deploymentName);
      }
      var rolloutWait = new ProcessBuilder("kubectl", "rollout", "status", "deployment/" + deploymentName, "--timeout=120s")
            .inheritIO()
            .start();
      if (rolloutWait.waitFor() != 0)
      {
         fail("kubectl rollout status timed out for " + deploymentName);
      }
   }

   private static void restartPortForward(String serviceName, int localPort) throws IOException, InterruptedException
   {
      System.out.println("Restarting port-forward for " + serviceName + " on port " + localPort);
      new ProcessBuilder("sh", "-c", "pkill -f 'kubectl port-forward.*" + localPort + "' 2>/dev/null; true")
            .start().waitFor();
      new ProcessBuilder("kubectl", "port-forward", "service/" + serviceName, localPort + ":8080")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
   }
}
