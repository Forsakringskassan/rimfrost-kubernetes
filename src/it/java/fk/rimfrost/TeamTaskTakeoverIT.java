package fk.rimfrost;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import se.fk.rimfrost.oul.handlaggning.jaxrsspec.controllers.generatedsource.model.GetUppgifterHandlaggareResponse;
import se.fk.rimfrost.regel.rtf.manuell.jaxrsspec.controllers.generatedsource.model.Beslutsutfall;

/**
 * Integration test covering the team task takeover scenario (FKPOC-921).
 *
 * <p>Two handläggare that share Team Göteborg according to the team service stub:
 * <ul>
 *   <li>Handläggare A (varde {@code 111111111}) — initiates the yrkande and receives the task</li>
 *   <li>Handläggare C (varde {@code 333333333}) — sees the task via the team endpoint, takes it over, and completes the flow</li>
 * </ul>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TeamTaskTakeoverIT extends RimfrostTestSupport
{
   private static final String INDIVID_PNR = "19900101-9999";
   private static final String ERBJUDANDE_ID = "7d4a6c38-348b-4f46-9278-b1bfeabc0353";

   private static KafkaConsumer<String, String> handlaggningDoneConsumer;
   private static UUID handlaggningId;
   private static UUID uppgiftId;
   private static String regelUrl;

   /**
    * Waits for all required services and resets shared state before the test suite runs.
    */
   @BeforeAll
   static void setup() throws Exception
   {
      waitForServiceRestartingPortForward(SERVICE_HANDLAGGNING, HANDLAGGNING_BASE_URL, 120);
      waitForServiceRestartingPortForward(SERVICE_OUL, OUL_BASE_URL, 120);
      waitForServiceRestartingPortForward(SERVICE_RTF_MANUELL, RTF_MANUELL_BASE_URL, 120);
      waitForServiceRestartingPortForward(SERVICE_BEKRAFTABESLUT, BEKRAFTABESLUT_BASE_URL, 120);
      waitForServiceRestartingPortForward(SERVICE_TEAM, TEAM_BASE_URL, 120);
      resetOulDatabase();
      handlaggningDoneConsumer = createKafkaConsumer(HANDLAGGNING_DONE_TOPIC);
   }

   /**
    * Closes the Kafka consumer after all tests have run.
    */
   @AfterAll
   static void teardown()
   {
      handlaggningDoneConsumer.close();
   }

   /**
    * TC1: Handläggare A initiates a VAH yrkande and receives the OUL task.
    * Handläggare C then calls GET /uppgifter/team and verifies the task is visible.
    */
   @Test
   @Order(1)
   @DisplayName("Handläggare C ser uppgift tilldelad handläggare A (samma team)")
   void teammate_can_see_assigned_task() throws IOException, InterruptedException
   {
      var yrkandeResponse = sendYrkandeRequest(INDIVID_PNR, ERBJUDANDE_ID, OffsetDateTime.now(), OffsetDateTime.now());
      handlaggningId = yrkandeResponse.getHandlaggning().getId();

      // Complete mandatory komplettering round (rtf-manuell 1.3.1 always triggers it)
      sendUppgifterHandlaggare(HANDLAGGARE_A_VARDE, handlaggningId);
      var kompletteringData = sendKompletteringGet(handlaggningId);
      assertEquals(204, sendKompletteringPatch(handlaggningId, INDIVID_PNR, kompletteringData.getAvsikt()));
      assertEquals(204, sendKompletteringDone(handlaggningId));

      // Get the regel task — this is the task that will be taken over by handläggare C
      var assignedResponse = sendUppgifterHandlaggare(HANDLAGGARE_A_VARDE, handlaggningId);
      uppgiftId = assignedResponse.getOperativUppgift().getUppgiftId();
      regelUrl = assignedResponse.getOperativUppgift().getUrl();

      var teamResponse = sendGetUppgifterTeam(HANDLAGGARE_C_VARDE, GetUppgifterHandlaggareResponse.class);
      assertNotNull(teamResponse.getOperativaUppgifter());
      assertTrue(
            teamResponse.getOperativaUppgifter().stream().anyMatch(u -> handlaggningId.equals(u.getHandlaggningId())),
            "Uppgift för handlaggningId " + handlaggningId + " ska synas för handläggare C via team-endpoint");
   }

   /**
    * TC2: Handläggare C takes over the task, verifies C now owns it, and verifies A no longer
    * sees it in their personal queue.
    */
   @Test
   @Order(2)
   @DisplayName("Handläggare C tar över uppgift från handläggare A")
   void teammate_can_take_over_task() throws IOException, InterruptedException
   {
      assertEquals(200, sendTakeoverUppgift(uppgiftId, HANDLAGGARE_C_VARDE));

      var cResponse = sendGetUppgifterHandlaggare(HANDLAGGARE_C_VARDE);
      assertTrue(
            cResponse.getOperativaUppgifter().stream().anyMatch(u -> handlaggningId.equals(u.getHandlaggningId())),
            "Uppgiften ska nu vara tilldelad handläggare C");

      var aResponse = sendGetUppgifterHandlaggare(HANDLAGGARE_A_VARDE);
      assertFalse(
            aResponse.getOperativaUppgifter().stream().anyMatch(u -> handlaggningId.equals(u.getHandlaggningId())),
            "Uppgiften ska inte längre synas i handläggare A:s kö");
   }

   /**
    * TC3: Handläggare C completes the full workflow — rtf-manuell then bekräftabeslut — and the
    * handlaggning-done Kafka message is received.
    */
   @Test
   @Order(3)
   @DisplayName("Handläggare C slutför flöde efter att ha tagit över uppgiften")
   void teammate_completes_flow_after_takeover() throws IOException, InterruptedException
   {
      var regelGetDataResponse = sendRegelGetData(String.valueOf(handlaggningId), regelUrl);
      var ersattningId = regelGetDataResponse.getErsattningar().getFirst().getErsattningId();
      assertEquals(handlaggningId, regelGetDataResponse.getHandlaggningId());
      assertEquals(204, sendRegelPatchData(String.valueOf(handlaggningId), regelUrl, Beslutsutfall.JA, ersattningId));
      assertEquals(204, sendRegelDone(RTF_MANUELL_BASE_URL, String.valueOf(handlaggningId), regelUrl));

      var bekraftaResponse = sendUppgifterHandlaggare(HANDLAGGARE_C_VARDE, handlaggningId);
      var bekraftaRegelUrl = bekraftaResponse.getOperativUppgift().getUrl();
      assertEquals(204, sendRegelDone(BEKRAFTABESLUT_BASE_URL, String.valueOf(handlaggningId), bekraftaRegelUrl));

      awaitKafkaMessage(handlaggningDoneConsumer, handlaggningId.toString());
   }
}
