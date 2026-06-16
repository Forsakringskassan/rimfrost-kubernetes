package fk.rimfrost;

import static java.net.HttpURLConnection.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.fk.rimfrost.oul.management.jaxrsspec.controllers.generatedsource.model.OperativUppgift;

public class OulSorteringsordningPersistenceIT extends RimfrostTestSupport
{
   private static final String TEST_HANDLAGGARE_ID = "3f439f0d-a915-42cb-ba8f-6a4170c6011f";
   private static final String TEST_PNR = "19900101-9999";
   private static final String TEST_ERBJUDANDE_ID = "7d4a6c38-348b-4f46-9278-b1bfeabc0353";

   /** Single-entry spec with a ConstraintEq on STATUS, used to verify entries survive round-trips. */
   private static final String SPEC_EQ_STATUS =
         "{\"entries\":[{\"constraints\":[{\"operator\":\"eq\",\"field\":\"status\",\"value\":\"NY\"}],\"sort_by\":{\"field\":\"skapad\",\"direction\":\"asc\"}}]}";

   /** Catch-all spec (no constraints) sorted by SKAPAD ASC. */
   private static final String SPEC_CATCH_ALL_ASC =
         "{\"entries\":[{\"sort_by\":{\"field\":\"skapad\",\"direction\":\"asc\"}}]}";

   @BeforeAll
   static void setup() throws Exception
   {
      for (String url : List.of(HANDLAGGNING_BASE_URL, OUL_BASE_URL))
      {
         waitForService(url);
      }
   }

   /**
    * Verifies that a created sorteringsordning row is durably stored in PostgreSQL and survives a pod
    * restart.
    */
   @Test
   @DisplayName("Sorteringsordning config survives OUL pod restart")
   void sorteringsordning_survives_pod_restart() throws Exception
   {
      var sorteringsordning = sendCreateSorteringsordning(SPEC_EQ_STATUS);
      var id = sorteringsordning.id();
      var expectedEntryCount = sorteringsordning.entryCount();

      restartDeployment(DEPLOYMENT_UPPGIFTSLAGER);
      waitForServiceRestartingPortForward(DEPLOYMENT_UPPGIFTSLAGER, 8889, OUL_BASE_URL, 180);

      var fetched = sendGetSorteringsordning(id);
      assertEquals(id, fetched.id());
      assertEquals(expectedEntryCount, fetched.entryCount(),
            "entry count should be unchanged after restart");
   }

   /**
    * Verifies that the default_sorteringsordning persists a pod restart.
    */
   @Test
   @DisplayName("Default sorteringsordning assignment survives OUL pod restart")
   void default_sorteringsordning_survives_pod_restart() throws Exception
   {
      sendCreateSorteringsordning(SPEC_CATCH_ALL_ASC);
      var sorteringsordning = sendCreateSorteringsordning(SPEC_EQ_STATUS);
      assertEquals(HTTP_NO_CONTENT, sendSetDefaultSorteringsordning(sorteringsordning.id()));

      restartDeployment(DEPLOYMENT_UPPGIFTSLAGER);
      waitForServiceRestartingPortForward(DEPLOYMENT_UPPGIFTSLAGER, 8889, OUL_BASE_URL, 180);

      var defaultSortering = sendGetDefaultSorteringsordning();
      assertEquals(sorteringsordning.id(), defaultSortering.id(),
            "default sorteringsordning should be persisted after restart");
   }

   /**
    * Verifies that the persisted sorteringsordning is used by the DB query engine after restart and
    * that the volatile count cache is correctly rebuilt from the DB on first access.
    */
   @Test
   @DisplayName("Sort order and count are correct after OUL pod restart")
   void sorting_and_count_correct_after_restart() throws Exception
   {
      var yrkandeFrom = LocalDate.parse("2025-12-24").atStartOfDay().atOffset(OffsetDateTime.now().getOffset());
      var yrkandeTom = LocalDate.parse("2025-12-24").atStartOfDay().atOffset(OffsetDateTime.now().getOffset());

      var yrkandeResponse = sendYrkandeRequest(TEST_PNR, TEST_ERBJUDANDE_ID, yrkandeFrom, yrkandeTom);
      sendUppgifterHandlaggare(TEST_HANDLAGGARE_ID, yrkandeResponse.getHandlaggning().getId());

      var sorteringsordning = sendCreateSorteringsordning(SPEC_CATCH_ALL_ASC);
      assertEquals(HTTP_NO_CONTENT, sendSetDefaultSorteringsordning(sorteringsordning.id()));

      var before = sendGetUppgifterPage(10, 0, null);
      var beforeTotal = before.getTotal();
      var beforeIds = before.getItems().stream().map(OperativUppgift::getUppgiftId).toList();

      restartDeployment(DEPLOYMENT_UPPGIFTSLAGER);
      waitForServiceRestartingPortForward(DEPLOYMENT_UPPGIFTSLAGER, 8889, OUL_BASE_URL, 180);

      var after = sendGetUppgifterPage(10, 0, null);
      assertEquals(beforeTotal, after.getTotal(),
            "total should be consistent after restart (count cache rebuilt from DB)");
      assertEquals(beforeIds, after.getItems().stream().map(OperativUppgift::getUppgiftId).toList(),
            "item order should be unchanged after restart");
   }

   /**
    * Verifies that a DELETE /sorteringsordning/{id} removes the DB row durably and that the row does not
    * reappear after restart.
    */
   @Test
   @DisplayName("Deleted sorteringsordning stays deleted after OUL pod restart")
   void deleted_sorteringsordning_stays_deleted_after_restart() throws Exception
   {
      var sorteringsordningStatusEq = sendCreateSorteringsordning(SPEC_EQ_STATUS);
      var sorteringsordningCatchAll = sendCreateSorteringsordning(SPEC_CATCH_ALL_ASC);
      assertEquals(HTTP_NO_CONTENT, sendSetDefaultSorteringsordning(sorteringsordningCatchAll.id()));

      assertEquals(HTTP_NO_CONTENT, sendDeleteSorteringsordning(sorteringsordningStatusEq.id()));
      assertEquals(HTTP_NOT_FOUND, sendGetSorteringsordningRaw(sorteringsordningStatusEq.id()).statusCode(),
            "deleted sorteringsordning should return HTTP_NOT_FOUND before restart");

      restartDeployment(DEPLOYMENT_UPPGIFTSLAGER);
      waitForServiceRestartingPortForward(DEPLOYMENT_UPPGIFTSLAGER, 8889, OUL_BASE_URL, 180);

      assertEquals(HTTP_NOT_FOUND, sendGetSorteringsordningRaw(sorteringsordningStatusEq.id()).statusCode(),
            "deleted sorteringsordning should stay deleted after restart");
      assertEquals(HTTP_OK, sendGetSorteringsordningRaw(sorteringsordningCatchAll.id()).statusCode(),
            "non-deleted sorteringsordning should still exist after restart");
   }

   /**
    * Verifies that limit/offset pagination and total are computed correctly in SQL after restart.
    */
   @Test
   @DisplayName("Paginated offset and total are consistent after OUL pod restart")
   void pagination_consistent_after_restart() throws Exception
   {
      var yrkandeFrom = LocalDate.parse("2025-12-24").atStartOfDay().atOffset(OffsetDateTime.now().getOffset());
      var yrkandeTom = LocalDate.parse("2025-12-24").atStartOfDay().atOffset(OffsetDateTime.now().getOffset());

      for (int i = 0; i < 3; i++)
      {
         var yrkandeResponse = sendYrkandeRequest(TEST_PNR, TEST_ERBJUDANDE_ID, yrkandeFrom, yrkandeTom);
         sendUppgifterHandlaggare(TEST_HANDLAGGARE_ID, yrkandeResponse.getHandlaggning().getId());
      }

      var sortering = sendCreateSorteringsordning(SPEC_CATCH_ALL_ASC);
      assertEquals(HTTP_NO_CONTENT, sendSetDefaultSorteringsordning(sortering.id()));

      var page1Before = sendGetUppgifterPage(2, 0, null);
      var page2Before = sendGetUppgifterPage(2, 2, null);
      var totalBefore = page1Before.getTotal();
      var page1IdsBefore = page1Before.getItems().stream().map(OperativUppgift::getUppgiftId).toList();
      var page2IdsBefore = page2Before.getItems().stream().map(OperativUppgift::getUppgiftId).toList();

      restartDeployment(DEPLOYMENT_UPPGIFTSLAGER);
      waitForServiceRestartingPortForward(DEPLOYMENT_UPPGIFTSLAGER, 8889, OUL_BASE_URL, 180);

      var page1After = sendGetUppgifterPage(2, 0, null);
      var page2After = sendGetUppgifterPage(2, 2, null);
      assertEquals(totalBefore, page1After.getTotal(),
            "total should be unchanged after restart");
      assertEquals(page1IdsBefore, page1After.getItems().stream().map(OperativUppgift::getUppgiftId).toList(),
            "page 1 items should be unchanged after restart");
      assertEquals(page2IdsBefore, page2After.getItems().stream().map(OperativUppgift::getUppgiftId).toList(),
            "page 2 items should be unchanged after restart");
   }
}
