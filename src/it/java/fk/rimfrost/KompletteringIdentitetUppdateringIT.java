package fk.rimfrost;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.fk.rimfrost.regel.rtf.manuell.jaxrsspec.controllers.generatedsource.model.Beslutsutfall;

/**
 * Integration tests for the komplettering flow driven by an external identity update.
 *
 * <p>The scenario: a yrkande arrives with {@code typId="personnummer"} and a known PNR, but the
 * handläggning is missing folkbokföring data in its {@code sakfragorStallningstaganden}. The
 * komplettering service therefore determines that komplettering is required, creates an OUL task,
 * and registers a prenumeration with {@code rimfrost-service-identitet-prenumeration} for the
 * individ's personnummer identity.
 *
 * <p>An external system then writes the folkbokföring data directly to the handläggning (via
 * {@code PUT /handlaggning/{id}}) and fires an {@code IdentitetUppdateradEvent} via
 * {@link #sendTriggerIdentitetDataChanged}. The komplettering service receives the event,
 * re-reads the handläggning (now containing the folkbokföring sakfraga), determines that
 * komplettering is no longer required, ends the OUL task, and sends a
 * {@code RegelResponseMessage} back to the process.
 *
 * <p><b>Assumption:</b> {@code rimfrost-regel-rtf-manuell-komplettering} must be extended so that
 * {@code isKompletteringRequired()} also checks for the presence of a
 * {@code sakfragorStallningstaganden} entry with {@code objektTypId = "folkbokforing"}. This test
 * will fail until that extension and the identitet-prenumeration subscription support are in place.
 */
public class KompletteringIdentitetUppdateringIT extends RimfrostTestSupport
{
   private static final String ERBJUDANDE_ID = "7d4a6c38-348b-4f46-9278-b1bfeabc0353";
   private static final String HANDLAGGARE_ID_VARDE = "3f439f0d-a915-42cb-ba8f-6a4170c6011f";
   private static final String INDIVID_PNR = "19900101-9999";

   private static KafkaConsumer<String, String> handlaggningDoneConsumer;

   @BeforeAll
   static void setup() throws Exception
   {
      waitForServiceRestartingPortForward(SERVICE_HANDLAGGNING, HANDLAGGNING_BASE_URL, 120);
      waitForServiceRestartingPortForward(SERVICE_OUL, OUL_BASE_URL, 120);
      waitForServiceRestartingPortForward(SERVICE_RTF_MANUELL, RTF_MANUELL_BASE_URL, 120);
      waitForServiceRestartingPortForward(SERVICE_RTF_MANUELL_KOMPLETTERING, RTF_MANUELL_KOMPLETTERING_BASE_URL, 120);
      waitForServiceRestartingPortForward(SERVICE_BEKRAFTABESLUT, BEKRAFTABESLUT_BASE_URL, 120);
      waitForServiceRestartingPortForward(SERVICE_TEAM, TEAM_BASE_URL, 120);
      waitForServiceRestartingPortForward(SERVICE_IDENTITET_PRENUMERATION, IDENTITET_PRENUMERATION_BASE_URL, 120);
      waitForServiceRestartingPortForward(SERVICE_HANDLAGGNING_SVC, HANDLAGGNING_SVC_BASE_URL, 120);
      resetOulDatabase();
      handlaggningDoneConsumer = createKafkaConsumer(HANDLAGGNING_DONE_TOPIC);
   }

   @AfterAll
   static void teardown()
   {
      handlaggningDoneConsumer.close();
   }

   /**
    * TC1: folkbokföring saknas initialt, extern källa skriver det och triggar IdentitetUppdateradEvent,
    * komplettering auto-resolvas, flödet fortsätter till utfall JA.
    *
    * <p>Sequence:
    * <ol>
    *   <li>POST /yrkande med {@code typId="personnummer"} — personnummer är känt, folkbokföring
    *       saknas. Kompletteringstjänsten bedömer att komplettering krävs (ingen folkbokföring-
    *       sakfraga) och registrerar en prenumeration för personnumret.</li>
    *   <li>OUL-uppgift av typ komplettering skapas och processen parkeras.</li>
    *   <li>PUT /handlaggning/{id} — simulerar att extern källa skriver folkbokföringsdata till
    *       handläggningens {@code sakfragorStallningstaganden}.</li>
    *   <li>POST /test/identitet-data-changed — extern källa triggar {@code IdentitetUppdateradEvent}
    *       för personnumret.</li>
    *   <li>Kompletteringstjänsten tar emot eventet, läser om handläggningen (nu med folkbokföring),
    *       bedömer att komplettering inte längre krävs, stänger OUL-uppgiften och skickar
    *       {@code RegelResponseMessage}.</li>
    * </ol>
    */
   @Test
   @DisplayName("RTFMK-FR-09: folkbokföring saknas initialt, extern källa uppdaterar via IdentitetUppdateradEvent, komplettering auto-resolvas, utfall JA")
   void folkbokforing_saknas_extern_kalla_triggar_identitet_event_komplettering_auto_resolvas_utfall_ja()
         throws IOException, InterruptedException
   {
      var yrkandeFrom = LocalDate.of(2025, 12, 24).atStartOfDay().atOffset(OffsetDateTime.now().getOffset());
      var yrkandeTom = LocalDate.of(2025, 12, 24).atStartOfDay().atOffset(OffsetDateTime.now().getOffset());

      // Yrkande med personnummer känt — folkbokföring saknas i handläggningen.
      // isKompletteringRequired() = true → prenumeration registreras för personnumret.
      var yrkandeResponse = sendYrkandeRequest(IDTYP_PERSONNUMMER, INDIVID_PNR, ERBJUDANDE_ID, yrkandeFrom, yrkandeTom);
      var handlaggningId = yrkandeResponse.getHandlaggning().getId();
      assertNotNull(handlaggningId);

      // Komplettering OUL-uppgift skapas; processen parkeras.
      // Kompletteringsuppgifter skapas med assignable=false och hittas därför inte via POST /uppgifter/handlaggare.
      var kompletteringTask = assignUnassignableUppgift(HANDLAGGARE_ID_VARDE, handlaggningId, IDTYP_PERSONNUMMER);
      var kompletteringUrl = kompletteringTask.getOperativUppgift().getUrl();
      assertTrue(kompletteringUrl.contains("/rtf-manuell-komplettering"),
            "Förväntad kompletteringsuppgift men fick URL: " + kompletteringUrl);

      // Verifiera att folkbokföring saknas i handläggningen innan extern uppdatering.
      var initialHandlaggning = sendGetHandlaggning(handlaggningId);
      var hasFolkbokforing = initialHandlaggning.getHandlaggning().getYrkande()
            .getSakfragorStallningstaganden().stream()
            .anyMatch(s -> FOLKBOKFORING_OBJEKT_TYP_ID.equals(s.getObjektTypId()));
      assertFalse(hasFolkbokforing, "Folkbokföring sakfraga ska inte finnas innan extern uppdatering");

      // Extern källa skriver folkbokföringsdata till handläggningen.
      assertEquals(200, sendPutHandlaggningWithFolkbokforing(handlaggningId, initialHandlaggning));

      // Extern källa triggar IdentitetUppdateradEvent — kompletteringstjänsten tar emot eventet,
      // läser om handläggningen (nu med folkbokföring-sakfraga), isKompletteringRequired() = false,
      // avslutar OUL-uppgiften och skickar RegelResponseMessage.
      assertEquals(204, sendTriggerIdentitetDataChanged(IDTYP_PERSONNUMMER, INDIVID_PNR));

      // Processen fortsätter → nästa steg är rtf-manuell OUL-uppgift (ej komplettering).
      var regelTask = sendUppgifterHandlaggare(HANDLAGGARE_ID_VARDE, handlaggningId);
      var regelUrl = regelTask.getOperativUppgift().getUrl();
      assertFalse(regelUrl.contains("/rtf-manuell-komplettering"),
            "Förväntad regeluppgift (ej komplettering) men fick URL: " + regelUrl);

      var regelGetDataResponse = sendRegelGetData(String.valueOf(handlaggningId), regelUrl);
      assertEquals(handlaggningId, regelGetDataResponse.getHandlaggningId());
      var ersattningId = regelGetDataResponse.getErsattningar().getFirst().getErsattningId();

      assertEquals(204, sendRegelPatchData(String.valueOf(handlaggningId), regelUrl, Beslutsutfall.JA, ersattningId));
      assertEquals(204, sendRegelDone(RTF_MANUELL_BASE_URL, String.valueOf(handlaggningId), regelUrl));

      // Bekräfta beslut.
      var bekraftaTask = sendUppgifterHandlaggare(HANDLAGGARE_ID_VARDE, handlaggningId);
      var bekraftaUrl = bekraftaTask.getOperativUppgift().getUrl();
      assertEquals(204, sendRegelDone(BEKRAFTABESLUT_BASE_URL, String.valueOf(handlaggningId), bekraftaUrl));

      // Handläggning klar — Kafka-meddelande ska ha publicerats.
      awaitKafkaMessage(handlaggningDoneConsumer, handlaggningId.toString());
   }
}
