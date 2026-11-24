package fk.rimfrost;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.fk.rimfrost.framework.regel.kommuniceringsutfall.jaxrsspec.controllers.generatedsource.model.Kommuniceringsutfall;
import se.fk.rimfrost.regel.rtf.manuell.jaxrsspec.controllers.generatedsource.model.Beslutsutfall;

/**
 * Integration tests for the kommunicering step in the VAH process.
 *
 * <p>rimfrost-regel-kommunicering maps the kommuniceringsutfall to a regel utfall:
 * {@code KUND_ACCEPTERAT} and {@code NEKAT_BESTRIDANDE} give JA, and VAH ends the handläggning.
 * {@code ACCEPTERAT_BESTRIDANDE} gives NEJ, and VAH starts over from rtf_maskinell.
 * The {@code KUND_ACCEPTERAT} path is covered by {@link SmokeTestIT}.
 */
public class KommuniceringIT extends RimfrostTestSupport
{
   private static final String ERBJUDANDE_ID = "7d4a6c38-348b-4f46-9278-b1bfeabc0353";
   private static final String HANDLAGGARE_ID_VARDE = "3f439f0d-a915-42cb-ba8f-6a4170c6011f";
   private static final String INDIVID_PNR = "19900101-9999";

   private static KafkaConsumer<String, String> handlaggningDoneConsumer;

   @BeforeAll
   static void setup() throws Exception
   {
      waitForServiceRestartingPortForward(SERVICE_WORKFLOW, HANDLAGGNING_BASE_URL, 120);
      waitForServiceRestartingPortForward(SERVICE_OUL, OUL_BASE_URL, 120);
      waitForServiceRestartingPortForward(SERVICE_RTF_MANUELL, RTF_MANUELL_BASE_URL, 120);
      waitForServiceRestartingPortForward(SERVICE_RTF_MANUELL_KOMPLETTERING, RTF_MANUELL_KOMPLETTERING_BASE_URL, 120);
      waitForServiceRestartingPortForward(SERVICE_BEKRAFTABESLUT, BEKRAFTABESLUT_BASE_URL, 120);
      waitForServiceRestartingPortForward(SERVICE_TEAM, TEAM_BASE_URL, 120);
      waitForServiceRestartingPortForward(SERVICE_KOMMUNICERING, KOMMUNICERING_BASE_URL, 120);
      resetOulDatabase();
      handlaggningDoneConsumer = createKafkaConsumer(HANDLAGGNING_DONE_TOPIC);
   }

   @AfterAll
   static void teardown()
   {
      handlaggningDoneConsumer.close();
   }

   @Test
   @DisplayName("RK-FR-05.1: Nekat bestridande ger utfall JA och handläggningen avslutas")
   void nekat_bestridande_avslutar_handlaggningen() throws IOException, InterruptedException
   {
      var handlaggningId = startHandlaggning();
      completeMandatoryKomplettering(HANDLAGGARE_ID_VARDE, handlaggningId, INDIVID_PNR);
      completeRtfManuell(handlaggningId);
      completeBekraftabeslut(handlaggningId);
      completeKommunicering(handlaggningId, Kommuniceringsutfall.NEKAT_BESTRIDANDE);

      awaitKafkaMessage(handlaggningDoneConsumer, handlaggningId.toString());
   }

   @Test
   @DisplayName("RK-FR-05.2: Accepterat bestridande ger utfall NEJ och processen börjar om")
   void accepterat_bestridande_startar_om_processen() throws IOException, InterruptedException
   {
      var handlaggningId = startHandlaggning();
      completeMandatoryKomplettering(HANDLAGGARE_ID_VARDE, handlaggningId, INDIVID_PNR);

      // First round: ACCEPTERAT_BESTRIDANDE gives NEJ, so VAH loops back to rtf_maskinell
      completeRtfManuell(handlaggningId);
      completeBekraftabeslut(handlaggningId);
      completeKommunicering(handlaggningId, Kommuniceringsutfall.ACCEPTERAT_BESTRIDANDE);

      // Second round: personnummer and folkbokföring are already set, so rtf-manuell comes without komplettering
      completeRtfManuell(handlaggningId);
      completeBekraftabeslut(handlaggningId);
      completeKommunicering(handlaggningId, Kommuniceringsutfall.KUND_ACCEPTERAT);

      awaitKafkaMessage(handlaggningDoneConsumer, handlaggningId.toString());
   }

   private static UUID startHandlaggning() throws IOException, InterruptedException
   {
      var yrkandeFrom = LocalDate.of(2025, 12, 24).atStartOfDay().atOffset(OffsetDateTime.now().getOffset());
      var yrkandeTom = LocalDate.of(2025, 12, 24).atStartOfDay().atOffset(OffsetDateTime.now().getOffset());
      return sendYrkandeRequest(INDIVID_PNR, ERBJUDANDE_ID, yrkandeFrom, yrkandeTom).getHandlaggning().getId();
   }

   private static void completeRtfManuell(UUID handlaggningId) throws IOException, InterruptedException
   {
      var regelUrl = sendUppgifterHandlaggare(HANDLAGGARE_ID_VARDE, handlaggningId).getOperativUppgift().getUrl();
      assertTrue(regelUrl.contains("/rtf-manuell") && !regelUrl.contains("/rtf-manuell-komplettering"),
            "Förväntad rtf-manuell-uppgift men fick URL: " + regelUrl);
      var ersattningId = sendRegelGetData(String.valueOf(handlaggningId), regelUrl)
            .getErsattningar().getFirst().getErsattningId();
      assertEquals(204, sendRegelPatchData(String.valueOf(handlaggningId), regelUrl, Beslutsutfall.JA, ersattningId));
      assertEquals(204, sendRegelDone(RTF_MANUELL_BASE_URL, String.valueOf(handlaggningId), regelUrl));
   }

   private static void completeBekraftabeslut(UUID handlaggningId) throws IOException, InterruptedException
   {
      var regelUrl = sendUppgifterHandlaggare(HANDLAGGARE_ID_VARDE, handlaggningId).getOperativUppgift().getUrl();
      assertTrue(regelUrl.contains("/bekraftabeslut"), "Förväntad bekraftabeslut-uppgift men fick URL: " + regelUrl);
      assertEquals(204, sendRegelDone(BEKRAFTABESLUT_BASE_URL, String.valueOf(handlaggningId), regelUrl));
   }

   private static void completeKommunicering(UUID handlaggningId, Kommuniceringsutfall utfall)
         throws IOException, InterruptedException
   {
      var regelUrl = assignUnassignableUppgift(HANDLAGGARE_ID_VARDE, handlaggningId).getOperativUppgift().getUrl();
      assertTrue(regelUrl.contains("/kommunicering"), "Förväntad kommuniceringsuppgift men fick URL: " + regelUrl);
      assertEquals(204, sendKommuniceringsutfall(handlaggningId, utfall));
      assertEquals(204, sendRegelDone(KOMMUNICERING_BASE_URL, String.valueOf(handlaggningId), regelUrl));
   }
}
