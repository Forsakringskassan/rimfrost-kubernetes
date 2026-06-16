package fk.rimfrost;

import static java.net.HttpURLConnection.*;
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
      var handlaggningId = yrkandeResponse.getHandlaggning().getId();

      // Assign task and patch beslutsutfall to JA
      var uppgifterResponse = sendUppgifterHandlaggare(TEST_HANDLAGGARE_ID, handlaggningId);
      var regelUrl = uppgifterResponse.getOperativUppgift().getUrl();
      var ersattningId = sendRegelGetData(String.valueOf(handlaggningId), regelUrl).getErsattningar().getFirst()
            .getErsattningId();
      assertEquals(HTTP_NO_CONTENT, sendRegelPatchData(String.valueOf(handlaggningId), regelUrl, Beslutsutfall.JA, ersattningId));

      // Restart the rtf-manuell pod and re-establish port-forward
      restartDeployment(DEPLOYMENT_RTF_MANUELL);
      waitForServiceRestartingPortForward(DEPLOYMENT_RTF_MANUELL, 8890, RTF_MANUELL_BASE_URL, 180);

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
      restartDeployment(DEPLOYMENT_UPPGIFTSLAGER);
      waitForServiceRestartingPortForward(DEPLOYMENT_UPPGIFTSLAGER, 8889, OUL_BASE_URL, 180);

      // Assert data survived the restart
      var handlaggareUppgifter = sendGetUppgifterHandlaggare(TEST_HANDLAGGARE_ID);
      var assignedUppgift = handlaggareUppgifter.getOperativaUppgifter().stream()
            .filter(u -> u.getUppgiftId().equals(uppgifterResponse.getOperativUppgift().getUppgiftId())
                  && u.getHandlaggningId().equals(uppgifterResponse.getOperativUppgift().getHandlaggningId()))
            .findFirst();
      assertTrue(assignedUppgift.isPresent(), "uppgift should be persisted after pod restart");
   }

}
