package fk.rimfrost;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import se.fk.rimfrost.regel.rtf.manuell.jaxrsspec.controllers.generatedsource.model.GetDataResponse;

/**
 * Integration test for the informationsaccess scenario (FKPOC-1102), see RK-FR-01–03 in docs/krav.md.
 *
 * <p>Handläggare A opens the rtf-manuell regel through rtf-manuell-bff, which is expected to publish an
 * {@code InformationsaccessEvent} on the {@code informationsaccess} topic. The event tests are disabled
 * until the BFF publishes events (FKPOC-1098), the backend returns informationsaccess data (FKPOC-1103)
 * and both are deployed here.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class InformationsaccessIT extends RimfrostTestSupport
{
   private static final String INDIVID_PNR = "19900101-9999";
   private static final String ERBJUDANDE_ID = "7d4a6c38-348b-4f46-9278-b1bfeabc0353";
   private static final String VANTAR_PA_ILOGG = "Väntar på FKPOC-1098, FKPOC-1103 och FK-bygget";

   // regel block in rimfrost-regel-rtf-manuell's config.yaml
   private static final String REGEL_ID = "a11f3429-3a6b-4389-ba1c-c2747b0fb45b";
   private static final String REGEL_VERSION = "1";
   private static final String KALLA = "rimfrost-regel-rtf-manuell-bff";

   private static final Duration EVENT_WINDOW = Duration.ofSeconds(10);
   private static final Duration CLOCK_MARGIN = Duration.ofSeconds(30);

   private static KafkaConsumer<String, String> informationsaccessConsumer;
   private static UUID handlaggningId;
   private static UUID uppgiftId;

   @BeforeAll
   static void setup() throws Exception
   {
      waitForServiceRestartingPortForward(SERVICE_HANDLAGGNING, HANDLAGGNING_BASE_URL, 120);
      waitForServiceRestartingPortForward(SERVICE_OUL, OUL_BASE_URL, 120);
      waitForServiceRestartingPortForward(SERVICE_RTF_MANUELL, RTF_MANUELL_BASE_URL, 120);
      waitForServiceRestartingPortForward(SERVICE_RTF_MANUELL_KOMPLETTERING, RTF_MANUELL_KOMPLETTERING_BASE_URL, 120);
      waitForServiceRestartingPortForward(SERVICE_RTF_MANUELL_BFF, URI.create(RTF_MANUELL_BFF_BASE_URL).getPort(),
            RTF_MANUELL_BFF_SERVICE_PORT, RTF_MANUELL_BFF_BASE_URL, 120);
      resetOulDatabase();
      informationsaccessConsumer = createKafkaConsumer(INFORMATIONSACCESS_TOPIC);
   }

   @AfterAll
   static void teardown()
   {
      informationsaccessConsumer.close();
   }

   /**
    * TC1: Handläggare A gets the rtf-manuell task after the mandatory komplettering round and opens
    * the regel through rtf-manuell-bff with their token.
    */
   @Test
   @Order(1)
   @DisplayName("RK-FR-01.1, RK-FR-01.2: Handläggare hämtar regelns data genom BFF:en")
   void handlaggare_hamtar_regelns_data_genom_bff() throws IOException, InterruptedException
   {
      var yrkandeResponse = sendYrkandeRequest(INDIVID_PNR, ERBJUDANDE_ID, OffsetDateTime.now(), OffsetDateTime.now());
      handlaggningId = yrkandeResponse.getHandlaggning().getId();

      // Complete mandatory komplettering round (rtf-manuell 1.3.1 always triggers it)
      assignUnassignableUppgift(HANDLAGGARE_A_VARDE, handlaggningId);
      var kompletteringData = sendKompletteringGet(handlaggningId);
      assertEquals(204, sendKompletteringPatch(handlaggningId, INDIVID_PNR, kompletteringData.getAvsikt()));
      assertEquals(204, sendKompletteringDone(handlaggningId));

      uppgiftId = sendUppgifterHandlaggare(HANDLAGGARE_A_VARDE, handlaggningId).getOperativUppgift().getUppgiftId();

      var response = sendRtfManuellBffGetTask(handlaggningId, HANDLAGGARE_A_VARDE);
      assertEquals(200, response.statusCode());
      assertEquals(handlaggningId, mapper.readValue(response.body(), GetDataResponse.class).getHandlaggningId());
   }

   /**
    * TC2: A call that returns no personuppgifter must not produce an event. Events for TC1's task are
    * ignored, since they may arrive after the consumer has moved to the end of the topic.
    */
   @Test
   @Order(2)
   @Disabled(VANTAR_PA_ILOGG)
   @DisplayName("RK-FR-02.3: Hämtning av uppgiftsbeskrivning ger inget event")
   void uppgiftsbeskrivning_ger_inget_event() throws IOException, InterruptedException
   {
      skipToEnd(informationsaccessConsumer);
      assertEquals(200, sendRtfManuellBffGetUppgiftsbeskrivning(HANDLAGGARE_A_VARDE).statusCode());

      var events = collectEvents(event -> KALLA.equals(event.path("kalla").asText()) && !isEventForUppgift(event));
      assertTrue(events.isEmpty(), "Inget event förväntades, men fick: " + events);
   }

   /**
    * TC3: One call gives one event for the task, with the expected content.
    */
   @Test
   @Order(3)
   @Disabled(VANTAR_PA_ILOGG)
   @DisplayName("RK-FR-02.1, RK-FR-03: Ett anrop ger ett event med förväntat innehåll")
   void ett_anrop_ger_ett_event_med_forvantat_innehall() throws IOException, InterruptedException
   {
      skipToEnd(informationsaccessConsumer);
      var fore = OffsetDateTime.now();
      var response = sendRtfManuellBffGetTask(handlaggningId, HANDLAGGARE_A_VARDE);
      var efter = OffsetDateTime.now();
      assertEquals(200, response.statusCode());

      var events = collectEvents(InformationsaccessIT::isEventForUppgift);
      assertEquals(1, distinctIds(events), "Förväntade ett event för uppgiften, fick: " + events);
      var event = events.getFirst();
      var tidpunkt = OffsetDateTime.parse(event.path("tidpunkt").asText());

      assertAll(
            () -> assertEquals(HANDLAGGARE_ID, event.path("anvandare").path("typId").asText(), "RK-FR-03.1"),
            () -> assertEquals(HANDLAGGARE_A_VARDE, event.path("anvandare").path("varde").asText(), "RK-FR-03.1"),
            () -> assertEquals(KALLA, event.path("kalla").asText(), "RK-FR-03.2"),
            () -> assertEquals(handlaggningId.toString(), event.path("handlaggningId").asText(), "RK-FR-03.3"),
            () -> assertEquals(uppgiftId.toString(), event.path("uppgiftId").asText(), "RK-FR-03.3"),
            () -> assertEquals(REGEL_ID, event.path("regelId").asText(), "RK-FR-03.4"),
            () -> assertEquals(REGEL_VERSION, event.path("regelVersion").asText(), "RK-FR-03.4"),
            () -> assertFalse(tidpunkt.isBefore(fore.minus(CLOCK_MARGIN)), "RK-FR-03.5"),
            () -> assertFalse(tidpunkt.isAfter(efter.plus(CLOCK_MARGIN)), "RK-FR-03.5"),
            () -> assertEquals(mapper.readTree(response.body()), event.path("visadInformation"), "RK-FR-03.6"));
   }

   /**
    * TC4: Every load is logged separately, so two calls give two events with different ids.
    */
   @Test
   @Order(4)
   @Disabled(VANTAR_PA_ILOGG)
   @DisplayName("RK-FR-02.2: Två anrop ger två event med olika id")
   void tva_anrop_ger_tva_event() throws IOException, InterruptedException
   {
      skipToEnd(informationsaccessConsumer);
      assertEquals(200, sendRtfManuellBffGetTask(handlaggningId, HANDLAGGARE_A_VARDE).statusCode());
      assertEquals(200, sendRtfManuellBffGetTask(handlaggningId, HANDLAGGARE_A_VARDE).statusCode());

      var events = collectEvents(InformationsaccessIT::isEventForUppgift);
      assertEquals(2, distinctIds(events), "Förväntade två event med olika id, fick: " + events);
   }

   private static boolean isEventForUppgift(JsonNode event)
   {
      return uppgiftId.toString().equals(event.path("uppgiftId").asText(null));
   }

   private static long distinctIds(List<JsonNode> events)
   {
      return events.stream().map(event -> event.path("id").asText()).distinct().count();
   }

   /**
    * Moves the consumer to the end of the topic so only events published after this call are read.
    */
   private static void skipToEnd(KafkaConsumer<String, String> consumer)
   {
      for (int attempt = 0; consumer.assignment().isEmpty() && attempt < 15; attempt++)
      {
         consumer.poll(Duration.ofSeconds(2));
      }
      assertFalse(consumer.assignment().isEmpty(), "Ingen partition tilldelad för " + INFORMATIONSACCESS_TOPIC);
      consumer.seekToEnd(consumer.assignment());
      consumer.assignment().forEach(consumer::position);
   }

   /**
    * Collects all events matching {@code filter} that arrive within {@link #EVENT_WINDOW}.
    */
   private static List<JsonNode> collectEvents(Predicate<JsonNode> filter) throws IOException
   {
      var events = new ArrayList<JsonNode>();
      var deadline = Instant.now().plus(EVENT_WINDOW);
      while (Instant.now().isBefore(deadline))
      {
         for (ConsumerRecord<String, String> record : informationsaccessConsumer.poll(Duration.ofSeconds(1)))
         {
            var event = mapper.readTree(record.value());
            if (filter.test(event))
            {
               events.add(event);
            }
         }
      }
      return events;
   }
}
