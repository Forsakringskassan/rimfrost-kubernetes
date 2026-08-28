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
 * Integration tests for the komplettering flow in rimfrost-regel-rtf-manuell.
 *
 * <p>Komplettering is initiated when {@code RtfService.checkKomplettering()} finds no individYrkandeRoll
 * with {@code typId="personnummer"} (the semantic string). A newly submitted yrkande carries the
 * reference-data UUID as typId, which never matches that string, so komplettering fires on every
 * new yrkande. After the handläggare supplies the personnummer via PATCH, {@code registerSvar()}
 * writes {@code typId="personnummer"}, and the re-run of {@code checkKomplettering()} returns empty.
 */
public class KompletteringIT extends RimfrostTestSupport
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
      waitForServiceRestartingPortForward(SERVICE_BEKRAFTABESLUT, BEKRAFTABESLUT_BASE_URL, 120);
      waitForServiceRestartingPortForward(SERVICE_TEAM, TEAM_BASE_URL, 120);
      resetOulDatabase();
      handlaggningDoneConsumer = createKafkaConsumer(HANDLAGGNING_DONE_TOPIC);
   }

   @AfterAll
   static void teardown()
   {
      handlaggningDoneConsumer.close();
   }

   /**
    * TC1: No krav.md found in this repo — no requirement ID available.
    * Verifies that every new yrkande triggers komplettering (typId UUID never matches "personnummer"),
    * the handläggare can supply the missing personnummer, and the flow completes with utfall JA.
    */
   @Test
   @DisplayName("Komplettering: personnummer saknas, kompletteras av handläggare, utfall JA")
   void komplettering_personnummer_saknas_utfall_ja() throws IOException, InterruptedException
   {
      var yrkandeFrom = LocalDate.of(2025, 12, 24).atStartOfDay().atOffset(OffsetDateTime.now().getOffset());
      var yrkandeTom = LocalDate.of(2025, 12, 24).atStartOfDay().atOffset(OffsetDateTime.now().getOffset());

      // Nytt yrkande bär typId=UUID (referensdata) som aldrig matchar strängen "personnummer" →
      // checkKomplettering() returnerar non-empty → komplettering initieras alltid.
      var yrkandeResponse = sendYrkandeRequest(INDIVID_PNR, ERBJUDANDE_ID, yrkandeFrom, yrkandeTom);
      var handlaggningId = yrkandeResponse.getHandlaggning().getId();
      assertNotNull(handlaggningId);

      // Komplettering OUL-uppgift skapas; processen parkeras vid eventBasedGateway
      var kompletteringTask = sendUppgifterHandlaggare(HANDLAGGARE_ID_VARDE, handlaggningId);
      var kompletteringUrl = kompletteringTask.getOperativUppgift().getUrl();
      assertTrue(kompletteringUrl.contains("/komplettering"),
            "Förväntad kompletteringsuppgift men fick URL: " + kompletteringUrl);

      // Handläggare läser kompletteringsdata — personnummer saknas (readKompletteringData filtrerar
      // på typId="personnummer" men inkommande typId är UUID), avsikt är satt från yrkandet
      var kompletteringData = sendKompletteringGet(handlaggningId);
      assertNull(kompletteringData.getPersonnummer(), "Personnummer ska vara null i kompletteringsdata innan komplettering");
      assertNotNull(kompletteringData.getAvsikt(), "Avsikt ska vara satt (skickat i yrkandet)");

      // Handläggare registrerar svar — personnummer sätts, avsikt ekas tillbaka
      assertEquals(204, sendKompletteringPatch(handlaggningId, INDIVID_PNR, kompletteringData.getAvsikt()));

      // Handläggare markerar komplettering som klar — stänger komplettering-OUL-uppgift och
      // anropar handleRegelRequest() direkt (synkront); checkKomplettering() returnerar nu tom
      // lista (avsikt och personnummer finns) → huvud-OUL-uppgift skapas inom samma anrop.
      assertEquals(204, sendKompletteringDone(handlaggningId));

      var regelTask = sendUppgifterHandlaggare(HANDLAGGARE_ID_VARDE, handlaggningId);
      var regelUrl = regelTask.getOperativUppgift().getUrl();
      assertFalse(regelUrl.contains("/komplettering"),
            "Förväntad regeluppgift (ej komplettering) men fick URL: " + regelUrl);

      var regelGetDataResponse = sendRegelGetData(String.valueOf(handlaggningId), regelUrl);
      assertEquals(handlaggningId, regelGetDataResponse.getHandlaggningId());
      var ersattningId = regelGetDataResponse.getErsattningar().getFirst().getErsattningId();

      assertEquals(204, sendRegelPatchData(String.valueOf(handlaggningId), regelUrl, Beslutsutfall.JA, ersattningId));
      assertEquals(204, sendRegelDone(RTF_MANUELL_BASE_URL, String.valueOf(handlaggningId), regelUrl));

      // Bekräfta beslut
      var bekraftaTask = sendUppgifterHandlaggare(HANDLAGGARE_ID_VARDE, handlaggningId);
      var bekraftaUrl = bekraftaTask.getOperativUppgift().getUrl();
      assertEquals(204, sendRegelDone(BEKRAFTABESLUT_BASE_URL, String.valueOf(handlaggningId), bekraftaUrl));

      // Handläggning klar — Kafka-meddelande ska ha publicerats
      awaitKafkaMessage(handlaggningDoneConsumer, handlaggningId.toString());
   }
}
