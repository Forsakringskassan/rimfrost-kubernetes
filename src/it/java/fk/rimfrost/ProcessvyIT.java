package fk.rimfrost;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import se.fk.rimfrost.oul.handlaggning.jaxrsspec.controllers.generatedsource.model.OperativUppgift;

/**
 * Integration test for the processvy (FKPOC-1177), see RK-FR-04 in docs/krav.md.
 *
 * <p>A VAH handläggning creates OUL tasks whose {@code aktivitet_id} and {@code processvy_url} come from the
 * process. The portal loads the processvy with that aktivitetId: process-vah-fe calls process-vah-bff, which calls
 * process-vah's {@code GET /processvy/steg} inside the cluster.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ProcessvyIT extends RimfrostTestSupport
{
   private static final String INDIVID_PNR = "19900101-9999";
   private static final String ERBJUDANDE_ID_VAH = "7d4a6c38-348b-4f46-9278-b1bfeabc0353";
   private static final String HANDLAGGARE_ID_PROCESSVY = "3f439f0d-a915-42cb-ba8f-6a4170c6011f";

   // processvy.url in rimfrost-process-vah's application.properties
   private static final String PROCESSVY_URL_VAH = "/process/vah";

   // process-vah isn't forwarded by port-forward.sh, so the test forwards it on a port of its own.
   private static final String SERVICE_PROCESS_VAH = "rimfrost-k8s-vah";
   private static final String PROCESS_VAH_BASE_URL = System.getenv("PROCESS_VAH_BASE_URL") != null
         ? System.getenv("PROCESS_VAH_BASE_URL")
         : System.getProperty("processVahBaseUrl", "http://localhost:8903");

   private static final String SERVICE_PROCESS_VAH_BFF = "rimfrost-k8s-process-vah-bff";
   private static final int PROCESS_VAH_BFF_SERVICE_PORT = 9005;
   private static final String PROCESS_VAH_BFF_BASE_URL = System.getenv("PROCESS_VAH_BFF_BASE_URL") != null
         ? System.getenv("PROCESS_VAH_BFF_BASE_URL")
         : System.getProperty("processVahBffBaseUrl", "http://localhost:9005");

   private static final String SERVICE_PROCESS_VAB_BFF = "rimfrost-k8s-process-vab-bff";
   private static final int PROCESS_VAB_BFF_SERVICE_PORT = 9006;
   private static final String PROCESS_VAB_BFF_BASE_URL = System.getenv("PROCESS_VAB_BFF_BASE_URL") != null
         ? System.getenv("PROCESS_VAB_BFF_BASE_URL")
         : System.getProperty("processVabBffBaseUrl", "http://localhost:9006");

   private static OperativUppgift uppgift;

   @BeforeAll
   static void setup() throws Exception
   {
      waitForServiceRestartingPortForward(SERVICE_HANDLAGGNING, HANDLAGGNING_BASE_URL, 120);
      waitForServiceRestartingPortForward(SERVICE_OUL, OUL_BASE_URL, 120);
      waitForServiceRestartingPortForward(SERVICE_RTF_MANUELL_KOMPLETTERING, RTF_MANUELL_KOMPLETTERING_BASE_URL, 120);
      waitForServiceRestartingPortForward(SERVICE_PROCESS_VAH, PROCESS_VAH_BASE_URL, 120);
      waitForServiceRestartingPortForward(SERVICE_PROCESS_VAH_BFF, URI.create(PROCESS_VAH_BFF_BASE_URL).getPort(),
            PROCESS_VAH_BFF_SERVICE_PORT, PROCESS_VAH_BFF_BASE_URL, 120);
      waitForServiceRestartingPortForward(SERVICE_PROCESS_VAB_BFF, URI.create(PROCESS_VAB_BFF_BASE_URL).getPort(),
            PROCESS_VAB_BFF_SERVICE_PORT, PROCESS_VAB_BFF_BASE_URL, 120);
      resetOulDatabase();
   }

   /**
    * TC1: The rtf-manuell task that VAH creates after the mandatory komplettering round carries the process's
    * aktivitetId and processvy link.
    */
   @Test
   @Order(1)
   @DisplayName("RK-FR-04.1: En uppgift som VAH skapar har aktivitet_id och processvy_url i OUL")
   void uppgift_fran_vah_har_aktivitet_och_processvy() throws IOException, InterruptedException
   {
      var yrkandeResponse = sendYrkandeRequest(INDIVID_PNR, ERBJUDANDE_ID_VAH, OffsetDateTime.now(),
            OffsetDateTime.now());
      UUID handlaggningId = yrkandeResponse.getHandlaggning().getId();

      assignUnassignableUppgift(HANDLAGGARE_ID_PROCESSVY, handlaggningId);
      var kompletteringData = sendKompletteringGet(handlaggningId);
      assertEquals(204, sendKompletteringPatch(handlaggningId, INDIVID_PNR, kompletteringData.getAvsikt()));
      assertEquals(204, sendKompletteringDone(handlaggningId));

      uppgift = sendUppgifterHandlaggare(HANDLAGGARE_ID_PROCESSVY, handlaggningId).getOperativUppgift();
      assertTrue(uppgift.getUrl().contains("/rtf-manuell"),
            "Expected the rtf-manuell task but got URL: " + uppgift.getUrl());
      assertFalse(uppgift.getUrl().contains("/rtf-manuell-komplettering"),
            "Expected the rtf-manuell task but got the komplettering task: " + uppgift.getUrl());
      assertNotNull(uppgift.getAktivitetId(), "aktivitet_id saknas på uppgiften " + uppgift.getUppgiftId());
      assertEquals(PROCESSVY_URL_VAH, uppgift.getProcessvyUrl());
   }

   /**
    * TC2: process-vah answers GET /processvy/steg for the task's aktivitetId, with the task's step in progress.
    */
   @Test
   @Order(2)
   @DisplayName("RK-FR-04.2: process-vah svarar på GET /processvy/steg för uppgiftens aktivitet")
   void process_vah_svarar_med_steg() throws IOException, InterruptedException
   {
      Assumptions.assumeTrue(uppgift != null, "TC1 skapade ingen uppgift");
      var response = sendGetSteg(PROCESS_VAH_BASE_URL + "/processvy/steg", uppgift.getAktivitetId());
      assertEquals(200, response.statusCode(), response.body());
      assertStegMedPagaendeSteg(mapper.readTree(response.body()));
   }

   /**
    * TC3: process-vah-bff reaches process-vah inside the cluster and passes the steps on.
    */
   @Test
   @Order(3)
   @DisplayName("RK-FR-04.3: process-vah-bff når process-vah i klustret")
   void process_vah_bff_nar_process_vah() throws IOException, InterruptedException
   {
      Assumptions.assumeTrue(uppgift != null, "TC1 skapade ingen uppgift");
      var response = sendGetSteg(PROCESS_VAH_BFF_BASE_URL + "/api/steg", uppgift.getAktivitetId());
      assertEquals(200, response.statusCode(), response.body());
      assertStegMedPagaendeSteg(mapper.readTree(response.body()));
   }

   /**
    * TC4: process-vab-bff reaches process-vab inside the cluster. No VAB task is created, so an unknown aktivitetId
    * is used: process-vab answers 404, which the BFF passes on. If the BFF couldn't reach process-vab, it would
    * answer 5xx instead.
    */
   @Test
   @Order(4)
   @DisplayName("RK-FR-04.4: process-vab-bff når process-vab i klustret")
   void process_vab_bff_nar_process_vab() throws IOException, InterruptedException
   {
      var response = sendGetSteg(PROCESS_VAB_BFF_BASE_URL + "/api/steg", UUID.randomUUID());
      assertTrue(response.statusCode() >= 400 && response.statusCode() < 500,
            "Expected a 4xx from process-vab, got " + response.statusCode() + ": " + response.body());
   }

   private static HttpResponse<String> sendGetSteg(String url, UUID aktivitetId)
         throws IOException, InterruptedException
   {
      var request = HttpRequest.newBuilder()
            .uri(URI.create(url + "?aktivitetId=" + aktivitetId))
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
      return client.send(request, HttpResponse.BodyHandlers.ofString());
   }

   private static void assertStegMedPagaendeSteg(JsonNode body)
   {
      assertEquals("vah", body.path("processId").asText(), body.toString());
      var steg = body.path("steg");
      assertTrue(steg.isArray() && !steg.isEmpty(), "Processvyn har inga steg: " + body);
      var pagaende = 0;
      for (var s : steg)
      {
         if ("PAGAR".equals(s.path("status").asText()))
         {
            pagaende++;
         }
      }
      assertEquals(1, pagaende, "Exakt ett steg ska pågå: " + body);
   }
}
