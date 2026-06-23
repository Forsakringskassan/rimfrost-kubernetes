package fk.rimfrost;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.fk.rimfrost.regel.rtf.manuell.jaxrsspec.controllers.generatedsource.model.Beslutsutfall;
import se.fk.rimfrost.workflow.jaxrsspec.controllers.generatedsource.model.PostHandlaggningProcessResponse;

/**
 * Integration tests for the process restart endpoint:
 * {@code POST /handlaggning/{handlaggningId}/process}.
 */
public class ProcessRestartIT extends RimfrostTestSupport
{
   private static final String HANDLAGGNING_DONE_ALT_TOPIC = "handlaggning-done-alt";
   private static final String INDIVID_PNR = "19900101-9999";
   private static final String ERBJUDANDE_ID = "7d4a6c38-348b-4f46-9278-b1bfeabc0353";
   private static final String HANDLAGGARE_ID_PARAM = "3f439f0d-a915-42cb-ba8f-6a4170c6011f";

   @BeforeAll
   static void setup() throws Exception
   {
      for (String url : List.of(HANDLAGGNING_BASE_URL, OUL_BASE_URL, RTF_MANUELL_BASE_URL, BEKRAFTABESLUT_BASE_URL))
      {
         waitForService(url);
      }
   }

   /**
    * TC1: Restart returns HTTP 200 with the correct handlaggning in the response body.
    * No prior flow completion required — the endpoint only needs the handlaggning to exist.
    */
   @Test
   @DisplayName("FKPOC-870-TC1: Restart process returns 200 with handlaggning")
   void restart_process_returns_200_with_handlaggning() throws IOException, InterruptedException
   {
      var yrkandeResponse = sendYrkandeRequest(INDIVID_PNR, ERBJUDANDE_ID, OffsetDateTime.now(), OffsetDateTime.now());
      var handlaggningId = yrkandeResponse.getHandlaggning().getId();

      var restartResponse = sendRestartProcess(handlaggningId, null);

      assertEquals(200, restartResponse.statusCode());
      var responseBody = mapper.readValue(restartResponse.body(), PostHandlaggningProcessResponse.class);
      assertEquals(handlaggningId, responseBody.getHandlaggning().getId());
   }

   /**
    * TC2: Restart creates a new OUL task for the handlaggning.
    * The initial flow is driven to completion and the handlaggning-done Kafka message is
    * awaited before restarting, ensuring workflow has fully settled before the restart call.
    */
   @Test
   @DisplayName("FKPOC-870-TC2: Restart process enqueues a new OUL task")
   void restart_process_creates_new_oul_task() throws IOException, InterruptedException
   {
      var yrkandeResponse = sendYrkandeRequest(INDIVID_PNR, ERBJUDANDE_ID, OffsetDateTime.now(), OffsetDateTime.now());
      var handlaggningId = yrkandeResponse.getHandlaggning().getId();

      try (var doneConsumer = createKafkaConsumer(HANDLAGGNING_DONE_TOPIC))
      {
         driveFlowToCompletion(handlaggningId);
         awaitKafkaMessage(doneConsumer, handlaggningId.toString());
      }

      sendRestartProcess(handlaggningId, null);

      sendUppgifterHandlaggare(HANDLAGGARE_ID_PARAM, handlaggningId);
   }

   /**
    * TC3: The restarted flow produces a handlaggning-done message on the stored reply topic.
    * The initial flow is driven to completion first to isolate the restarted flow's done message.
    */
   @Test
   @DisplayName("FKPOC-870-TC3: Restarted flow produces handlaggning-done message")
   void restart_process_produces_handlaggning_done() throws IOException, InterruptedException
   {
      var yrkandeResponse = sendYrkandeRequest(INDIVID_PNR, ERBJUDANDE_ID, OffsetDateTime.now(), OffsetDateTime.now());
      var handlaggningId = yrkandeResponse.getHandlaggning().getId();

      try (var doneConsumer = createKafkaConsumer(HANDLAGGNING_DONE_TOPIC))
      {
         driveFlowToCompletion(handlaggningId);
         awaitKafkaMessage(doneConsumer, handlaggningId.toString());
      }

      try (var doneConsumer = createKafkaConsumer(HANDLAGGNING_DONE_TOPIC))
      {
         sendRestartProcess(handlaggningId, null);
         driveFlowToCompletion(handlaggningId);
         awaitKafkaMessage(doneConsumer, handlaggningId.toString());
      }
   }

   /**
    * TC4: Restart with a new {@code replyTo} sends the done message to the updated topic.
    */
   @Test
   @DisplayName("FKPOC-870-TC4: Restart process with new replyTo sends done message to the updated topic")
   void restart_process_with_reply_to_sends_done_to_new_topic() throws IOException, InterruptedException
   {
      var yrkandeResponse = sendYrkandeRequest(INDIVID_PNR, ERBJUDANDE_ID, OffsetDateTime.now(), OffsetDateTime.now());
      var handlaggningId = yrkandeResponse.getHandlaggning().getId();

      try (var doneConsumer = createKafkaConsumer(HANDLAGGNING_DONE_TOPIC))
      {
         driveFlowToCompletion(handlaggningId);
         awaitKafkaMessage(doneConsumer, handlaggningId.toString());
      }

      try (var altConsumer = createKafkaConsumer(HANDLAGGNING_DONE_ALT_TOPIC))
      {
         sendRestartProcess(handlaggningId, HANDLAGGNING_DONE_ALT_TOPIC);
         driveFlowToCompletion(handlaggningId);
         awaitKafkaMessage(altConsumer, handlaggningId.toString());
      }
   }

   /**
    * TC5: Restart with an unknown handlaggningId returns HTTP 404.
    */
   @Test
   @DisplayName("FKPOC-870-TC5: Restart process with unknown handlaggningId returns 404")
   void restart_process_with_unknown_id_returns_404() throws IOException, InterruptedException
   {
      var response = sendRestartProcess(UUID.randomUUID(), null);
      assertEquals(404, response.statusCode());
   }

   /**
    * Drives a VAH handlaggning through rtf-manuell and bekraftabeslut to completion.
    */
   private void driveFlowToCompletion(UUID handlaggningId) throws IOException, InterruptedException
   {
      var uppgifterResponse = sendUppgifterHandlaggare(HANDLAGGARE_ID_PARAM, handlaggningId);
      var regelUrl = uppgifterResponse.getOperativUppgift().getUrl();
      var regelData = sendRegelGetData(String.valueOf(handlaggningId), regelUrl);
      var ersattningId = regelData.getErsattningar().getFirst().getErsattningId();
      assertEquals(204, sendRegelPatchData(String.valueOf(handlaggningId), regelUrl, Beslutsutfall.JA, ersattningId));
      assertEquals(204, sendRegelDone(RTF_MANUELL_BASE_URL, String.valueOf(handlaggningId), regelUrl));

      uppgifterResponse = sendUppgifterHandlaggare(HANDLAGGARE_ID_PARAM, handlaggningId);
      regelUrl = uppgifterResponse.getOperativUppgift().getUrl();
      assertEquals(204, sendRegelDone(BEKRAFTABESLUT_BASE_URL, String.valueOf(handlaggningId), regelUrl));
   }
}
