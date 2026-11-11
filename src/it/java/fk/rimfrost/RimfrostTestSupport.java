package fk.rimfrost;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import se.fk.rimfrost.oul.handlaggning.jaxrsspec.controllers.generatedsource.model.*;
import se.fk.rimfrost.oul.management.jaxrsspec.controllers.generatedsource.model.UppgiftPage;
import se.fk.rimfrost.workflow.jaxrsspec.controllers.generatedsource.model.Idtyp;
import se.fk.rimfrost.workflow.jaxrsspec.controllers.generatedsource.model.PostHandlaggningProcessRequest;
import se.fk.rimfrost.workflow.jaxrsspec.controllers.generatedsource.model.PostRollIYrkande;
import se.fk.rimfrost.workflow.jaxrsspec.controllers.generatedsource.model.PostSakfragaStallningstagande;
import se.fk.rimfrost.workflow.jaxrsspec.controllers.generatedsource.model.PostYrkandeRequest;
import se.fk.rimfrost.workflow.jaxrsspec.controllers.generatedsource.model.PostYrkandeResponse;
import se.fk.rimfrost.regel.rtf.manuell.jaxrsspec.controllers.generatedsource.model.Beslutsutfall;
import se.fk.rimfrost.regel.rtf.manuell.jaxrsspec.controllers.generatedsource.model.GetDataResponse;
import se.fk.rimfrost.regel.rtf.manuell.jaxrsspec.controllers.generatedsource.model.PatchErsattningRequest;
import se.fk.rimfrost.regel.rtf.manuell.jaxrsspec.controllers.generatedsource.model.UpdateErsattning;
import se.fk.rimfrost.regel.rtf.manuell.komplettering.jaxrsspec.controllers.generatedsource.model.RtfKompletteringData;
import static java.nio.charset.StandardCharsets.UTF_8;

abstract class RimfrostTestSupport
{
   static final String HANDLAGGNING_BASE_URL = System.getenv("HANDLAGGNING_BASE_URL") != null
         ? System.getenv("HANDLAGGNING_BASE_URL")
         : System.getProperty("handlaggningBaseUrl", "http://localhost:8888");
   static final String OUL_BASE_URL = System.getenv("OUL_BASE_URL") != null
         ? System.getenv("OUL_BASE_URL")
         : System.getProperty("oulBaseUrl", "http://localhost:8889");
   static final String RTF_MANUELL_BASE_URL = System.getenv("RTF_MANUELL_BASE_URL") != null
         ? System.getenv("RTF_MANUELL_BASE_URL")
         : System.getProperty("regelRtfManuellBaseUrl", "http://localhost:8890");
   static final String BEKRAFTABESLUT_BASE_URL = System.getenv("BEKRAFTABESLUT_BASE_URL") != null
         ? System.getenv("BEKRAFTABESLUT_BASE_URL")
         : System.getProperty("regelBekraftabeslutBaseUrl", "http://localhost:8891");
   static final String TEAM_BASE_URL = System.getenv("TEAM_BASE_URL") != null
         ? System.getenv("TEAM_BASE_URL")
         : System.getProperty("teamBaseUrl", "http://localhost:8893");
   static final String RTF_MANUELL_KOMPLETTERING_BASE_URL = System.getenv("RTF_MANUELL_KOMPLETTERING_BASE_URL") != null
         ? System.getenv("RTF_MANUELL_KOMPLETTERING_BASE_URL")
         : System.getProperty("regelRtfManuellKompletteringBaseUrl", "http://localhost:8899");
   static final String RTF_MANUELL_BFF_BASE_URL = System.getenv("RTF_MANUELL_BFF_BASE_URL") != null
         ? System.getenv("RTF_MANUELL_BFF_BASE_URL")
         : System.getProperty("regelRtfManuellBffBaseUrl", "http://localhost:9002");

   static final String IDTYP_TYP_ID = "c5f2e2b4-9143-4160-8f4b-30c172f0ac05";
   static final String YRKANDESTATUS_ID = "e27da561-a8db-4513-8272-ef652b097b16";

   /** Personnummer per handläggning, used to look up unassignable tasks in OUL. */
   private static final Map<UUID, String> INDIVID_BY_HANDLAGGNING = new ConcurrentHashMap<>();

   /** Handläggare (varde) currently holding the OUL task per handläggning. */
   private static final Map<UUID, String> ASSIGNED_HANDLAGGARE = new ConcurrentHashMap<>();

   /**
    * Regel endpoints resolve the caller through the identity service and require the caller to be
    * assigned the OUL task, so send the token of the handläggare that currently holds it.
    */
   private static HttpRequest.Builder asAssignedHandlaggare(HttpRequest.Builder builder, String handlaggningId)
   {
      var handlaggare = ASSIGNED_HANDLAGGARE.get(UUID.fromString(handlaggningId));
      return handlaggare == null
            ? builder
            : builder.header("Authorization", "Bearer " + HANDLAGGARE_ID + ":" + handlaggare);
   }

   static final String YRKANDE_ROLL_ID = "80f5f41f-9e55-4fc2-a076-ad5a651e0a9d";
   static final String HANDLAGGARE_ID = "116759e4-18fd-4209-849c-90abbd257d22";
   static final String HANDLAGGARE_A_VARDE = "111111111";
   static final String HANDLAGGARE_C_VARDE = "333333333";

   static final String HANDLAGGNING_DONE_TOPIC = "handlaggning-done";
   static final String INFORMATIONSACCESS_TOPIC = "informationsaccess";

   static final String DEPLOYMENT_UPPGIFTSLAGER = "rimfrost-k8s-uppgiftslager";
   static final String DEPLOYMENT_RTF_MANUELL = "rimfrost-k8s-rtf-manuell";

   static final String SERVICE_HANDLAGGNING = "rimfrost-k8s-workflow";
   static final String SERVICE_OUL = "rimfrost-k8s-uppgiftslager";
   static final String SERVICE_RTF_MANUELL = "rimfrost-k8s-rtf-manuell";
   static final String SERVICE_RTF_MANUELL_KOMPLETTERING = "rimfrost-k8s-rtf-manuell-komplettering";
   static final String SERVICE_BEKRAFTABESLUT = "rimfrost-k8s-bekraftabeslut";
   static final String SERVICE_TEAM = "rimfrost-k8s-team";
   static final String SERVICE_RTF_MANUELL_BFF = "rimfrost-k8s-rtf-manuell-bff";
   static final int RTF_MANUELL_BFF_SERVICE_PORT = 9002;

   static final String YRKANDE_URL = HANDLAGGNING_BASE_URL + "/yrkande";
   static final String HANDLAGGNING_URL = HANDLAGGNING_BASE_URL + "/handlaggning";
   static final String OUL_URL = OUL_BASE_URL + "/uppgifter/handlaggare";
   static final String OUL_TEAM_URL = OUL_BASE_URL + "/uppgifter/team";
   static final String SORTERINGSORDNING_URL = OUL_BASE_URL + "/sorteringsordning";
   static final String UPPGIFTER_URL = OUL_BASE_URL + "/uppgifter";

   static final HttpClient client = HttpClient.newHttpClient();
   static final ObjectMapper mapper = new ObjectMapper();

   static
   {
      mapper.registerModule(new JavaTimeModule());
   }

   static void waitForService(String baseUrl) throws InterruptedException
   {
      waitForService(baseUrl, "/q/health", 60);
   }

   static void waitForService(String baseUrl, String healthPath, int timeoutSeconds) throws InterruptedException
   {
      String healthUrl = baseUrl + healthPath;
      var deadline = java.time.Instant.now().plusSeconds(timeoutSeconds);
      while (true)
      {
         try
         {
            var request = HttpRequest.newBuilder(URI.create(healthUrl)).GET().timeout(Duration.ofSeconds(5)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 500)
            {
               System.out.println("Service ready: " + healthUrl);
               return;
            }
         }
         catch (Exception ignored)
         {
         }
         if (java.time.Instant.now().isAfter(deadline))
         {
            fail("Service not ready after " + timeoutSeconds + "s: " + healthUrl);
         }
         Thread.sleep(2000);
      }
   }

   static <T> HttpResponse<T> httpSendRetries(
         HttpClient client,
         HttpRequest request,
         HttpResponse.BodyHandler<T> bodyHandler,
         int expectedStatus,
         int numberOfRetries)
   {
      int attempt = 0;
      HttpResponse<T> response = null;
      while (attempt < numberOfRetries)
      {
         attempt++;
         try
         {
            response = client.send(request, bodyHandler);
            if (response.statusCode() == expectedStatus)
            {
               System.out.printf("httpSendRetries Attempt %s successful waiting for status %s%n", attempt, expectedStatus);
               return response;
            }
            System.out.printf("httpSendRetries Attempt %s failed with status code %s%n", attempt, response.statusCode());
         }
         catch (IOException | InterruptedException e)
         {
            System.out.printf("httpSendRetries Attempt %s failed with exception %s%n", attempt, e.getMessage());
         }
         try
         {
            Thread.sleep(1000);
         }
         catch (InterruptedException ignored)
         {
         }
      }
      throw new RuntimeException("httpSendRetries HTTP call failed after " + numberOfRetries + " attempts.");
   }

   /**
    * POST /handlaggning/{handlaggningId}/process — restarts the process for an existing handlaggning.
    * {@code replyTo} is required by the endpoint and replaces the stored reply topic for this handlaggning.
    *
    * @return the raw HTTP response so callers can assert on both success (200) and error status codes
    */
   static HttpResponse<String> sendRestartProcess(UUID handlaggningId, String replyTo)
         throws IOException, InterruptedException
   {
      var processRequest = new PostHandlaggningProcessRequest();
      processRequest.setReplyTo(replyTo);
      var request = HttpRequest.newBuilder()
            .uri(URI.create(HANDLAGGNING_URL + "/" + handlaggningId + "/process"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(processRequest)))
            .build();
      return client.send(request, HttpResponse.BodyHandlers.ofString());
   }

   /**
    * Creates a Kafka consumer subscribed to {@code topic}, reading from the earliest offset.
    * The consumer uses a unique group ID so it never shares offset state with other consumers.
    */
   static KafkaConsumer<String, String> createKafkaConsumer(String topic)
   {
      String bootstrap = System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9094");
      Properties props = new Properties();
      props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
      props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-" + System.currentTimeMillis());
      props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
      props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
      props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
      KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
      consumer.subscribe(Collections.singletonList(topic));
      return consumer;
   }

   /**
    * Polls {@code consumer} until a message whose {@code handlaggningId} field matches is found,
    * then returns that message value. Fails the test after 15 polling attempts (~30 s).
    */
   static String awaitKafkaMessage(KafkaConsumer<String, String> consumer, String handlaggningId)
   {
      int maxAttempts = 15;
      for (int attempt = 0; attempt < maxAttempts; attempt++)
      {
         System.out.printf("Polling kafka topic waiting for handlaggningId: %s%n", handlaggningId);
         ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(2));
         for (ConsumerRecord<String, String> record : records)
         {
            String value = record.value();
            System.out.printf("-- Found kafka message: %s%n", value);
            if (kafkaMessageHasHandlaggningId(value, handlaggningId))
            {
               return value;
            }
         }
      }
      return fail("No Kafka message with handlaggningId " + handlaggningId + " received after " + maxAttempts + " attempts");
   }

   /**
    * POST {baseUrl}{regelUrl}/{handlaggningId}/done — marks a regel step as complete.
    *
    * @return the HTTP status code (204 on success)
    */
   static int sendRegelDone(String baseUrl, String handlaggningId, String regelUrl)
         throws IOException, InterruptedException
   {
      var request = asAssignedHandlaggare(HttpRequest.newBuilder(), handlaggningId)
            .uri(URI.create(baseUrl + regelUrl + "/" + handlaggningId + "/done"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
      return client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
   }

   private static boolean kafkaMessageHasHandlaggningId(String json, String handlaggningId)
   {
      try
      {
         JsonNode root = mapper.readTree(json);
         return handlaggningId.equals(root.path("handlaggningId").asText(null));
      }
      catch (Exception e)
      {
         return false;
      }
   }

   static PostYrkandeResponse sendYrkandeRequest(String pnr, String ingangtypId,
         OffsetDateTime yrkandeFrom, OffsetDateTime yrkandeTom) throws IOException, InterruptedException
   {
      var idTyp = new Idtyp();
      idTyp.setTypId(IDTYP_TYP_ID);
      idTyp.setVarde(pnr);

      var rollIYrkande = new PostRollIYrkande();
      rollIYrkande.setIndivid(idTyp);
      rollIYrkande.setYrkandeRollId(YRKANDE_ROLL_ID);

      var sakfraga = new PostSakfragaStallningstagande();
      sakfraga.setObjektTypId("ERSATTNING");
      // data is a serialized se.fk.rimfrost.ersattningdata.ErsattningData
      var ersattningData = mapper.createObjectNode();
      ersattningData.put("id", UUID.randomUUID().toString());
      ersattningData.put("version", 1);
      ersattningData.putObject("ersattningstyp")
            .put("id", "dee75df2-a6e0-493d-8314-ec4c37b96f9c")
            .put("namn", "HUNDBIDRAG");
      ersattningData.put("omfattningProcent", 100);
      ersattningData.put("belopp", 40000);
      ersattningData.put("berakningsgrund", 0);
      ersattningData.put("beslutsutfall", "FU");
      ersattningData.put("from", yrkandeFrom.toLocalDate().toString());
      ersattningData.put("tom", yrkandeTom.toLocalDate().toString());
      ersattningData.put("yrkandeStatus", YRKANDESTATUS_ID);
      sakfraga.setData(mapper.writeValueAsString(ersattningData));

      var yrkandeRequest = new PostYrkandeRequest();
      yrkandeRequest.setIngangtypId(ingangtypId);
      yrkandeRequest.setYrkandeFrom(yrkandeFrom);
      yrkandeRequest.setYrkandeTom(yrkandeTom);
      yrkandeRequest.setHandlaggningspecifikationId(UUID.randomUUID());
      yrkandeRequest.setRollerIYrkande(List.of(rollIYrkande));
      yrkandeRequest.setSakfragorStallningstaganden(List.of(sakfraga));
      yrkandeRequest.setAvsiktsId(UUID.randomUUID().toString());
      yrkandeRequest.setReplyTo("handlaggning-done");

      var request = HttpRequest.newBuilder()
            .uri(URI.create(YRKANDE_URL))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(yrkandeRequest)))
            .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      var yrkandeResponse = mapper.readValue(response.body(), PostYrkandeResponse.class);
      INDIVID_BY_HANDLAGGNING.put(yrkandeResponse.getHandlaggning().getId(), pnr);
      return yrkandeResponse;
   }

   /**
    * Assigns the unassignable uppgift for the given handläggning to the handläggare.
    * <p>
    * Komplettering tasks are created with assignable=false, so OUL never hands them out via
    * POST /uppgifter/handlaggare. Polls POST /uppgifter/individ/unassignable for the individ on the
    * handläggning until the task appears, then claims it with POST /uppgifter/{uppgiftId}/handlaggare.
    */
   static PostUppgifterHandlaggareResponse assignUnassignableUppgift(String handlaggareId, UUID handlaggningId)
         throws IOException, InterruptedException
   {
      var pnr = INDIVID_BY_HANDLAGGNING.get(handlaggningId);
      assertNotNull(pnr, "Inget personnummer registrerat för handlaggningId " + handlaggningId
            + " (skapades den via sendYrkandeRequest?)");

      var searchRequest = new PostUppgifterIndividUnassignableRequest();
      searchRequest.setIdTyp(IDTYP_TYP_ID);
      searchRequest.setIdVarde(pnr);
      searchRequest.setStatuses(List.of(PostUppgifterIndividUnassignableRequest.StatusesEnum.NY));
      var search = HttpRequest.newBuilder()
            .uri(URI.create(UPPGIFTER_URL + "/individ/unassignable"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + HANDLAGGARE_ID + ":" + handlaggareId)
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(searchRequest)))
            .build();

      UUID uppgiftId = null;
      int attempt = 0;
      while (uppgiftId == null && attempt < 120)
      {
         System.out.printf("assignUnassignableUppgift attempt %d waiting for task with handlaggningId %s%n", attempt,
               handlaggningId);
         var searchResponse = httpSendRetries(client, search, HttpResponse.BodyHandlers.ofString(), 200, 5);
         uppgiftId = mapper.readValue(searchResponse.body(), PostUppgifterIndividUnassignableResponse.class)
               .getOperativaUppgifter().stream()
               .filter(u -> handlaggningId.equals(u.getHandlaggningId()))
               .map(u -> u.getUppgiftId())
               .findFirst()
               .orElse(null);
         attempt++;
         if (uppgiftId == null)
         {
            Thread.sleep(1000);
         }
      }
      if (uppgiftId == null)
      {
         fail("Ingen otilldelningsbar uppgift med handlaggningId " + handlaggningId + " hittades efter " + attempt
               + " försök");
      }

      var assign = HttpRequest.newBuilder()
            .uri(URI.create(UPPGIFTER_URL + "/" + uppgiftId + "/handlaggare"))
            .header("Authorization", "Bearer " + HANDLAGGARE_ID + ":" + handlaggareId)
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
      var assignResponse = httpSendRetries(client, assign, HttpResponse.BodyHandlers.ofString(), 200, 5);
      var response = new PostUppgifterHandlaggareResponse();
      response.setOperativUppgift(
            mapper.readValue(assignResponse.body(), PostUppgiftHandlaggareResponse.class).getOperativUppgift());
      ASSIGNED_HANDLAGGARE.put(handlaggningId, handlaggareId);
      return response;
   }

   static PostUppgifterHandlaggareResponse sendUppgifterHandlaggare(String handlaggareId, UUID expectedHandlaggningId)
         throws IOException, InterruptedException
   {
      var request = HttpRequest.newBuilder()
            .uri(URI.create(OUL_URL))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + HANDLAGGARE_ID + ":" + handlaggareId)
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
      PostUppgifterHandlaggareResponse response;
      int attempt = 0;
      do
      {
         System.out.printf("sendUppgifterHandlaggare attempt %d waiting for task with handlaggningId %s%n", attempt,
               expectedHandlaggningId);
         var httpResponse = httpSendRetries(client, request, HttpResponse.BodyHandlers.ofString(), 200, 5);
         response = mapper.readValue(httpResponse.body(), PostUppgifterHandlaggareResponse.class);
         attempt++;
         Thread.sleep(1000);
      }
      while ((response.getOperativUppgift() == null
            || !expectedHandlaggningId.equals(response.getOperativUppgift().getHandlaggningId()))
            && attempt < 120);
      if (response.getOperativUppgift() == null
            || !expectedHandlaggningId.equals(response.getOperativUppgift().getHandlaggningId()))
      {
         fail("Ingen uppgift med handlaggningId " + expectedHandlaggningId + " hittades efter " + attempt + " försök");
      }
      ASSIGNED_HANDLAGGARE.put(expectedHandlaggningId, handlaggareId);
      return response;
   }

   static GetUppgifterHandlaggareResponse sendGetUppgifterHandlaggare(String handlaggareId)
         throws IOException, InterruptedException
   {
      var request = HttpRequest.newBuilder()
            .uri(URI.create(OUL_URL))
            .header("Authorization", "Bearer " + HANDLAGGARE_ID + ":" + handlaggareId)
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      return mapper.readValue(response.body(), GetUppgifterHandlaggareResponse.class);
   }

   /**
    * Calls {@code GET /uppgifter/team} as the given handläggare and returns the deserialized response.
    *
    * @param handlaggareVarde the varde of the calling handläggare
    * @param responseType the expected response class
    * @return deserialized response body
    */
   static <T> T sendGetUppgifterTeam(String handlaggareVarde, Class<T> responseType)
         throws IOException, InterruptedException
   {
      var request = HttpRequest.newBuilder()
            .uri(URI.create(OUL_TEAM_URL))
            .header("Authorization", "Bearer " + HANDLAGGARE_ID + ":" + handlaggareVarde)
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      return mapper.readValue(response.body(), responseType);
   }

   /**
    * Calls {@code POST /uppgifter/{uppgiftId}/handlaggare} as the given handläggare, taking
    * ownership of the specified task.
    *
    * @param uppgiftId the ID of the task to take over
    * @param handlaggareVarde the varde of the handläggare taking over
    * @return HTTP status code
    */
   static int sendTakeoverUppgift(UUID uppgiftId, String handlaggareVarde)
         throws IOException, InterruptedException
   {
      var request = HttpRequest.newBuilder()
            .uri(URI.create(UPPGIFTER_URL + "/" + uppgiftId + "/handlaggare"))
            .header("Authorization", "Bearer " + HANDLAGGARE_ID + ":" + handlaggareVarde)
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      var uppgift = mapper.readValue(response.body(), PostUppgiftHandlaggareResponse.class).getOperativUppgift();
      ASSIGNED_HANDLAGGARE.put(uppgift.getHandlaggningId(), handlaggareVarde);
      return response.statusCode();
   }

   static GetDataResponse sendRegelGetData(String handlaggningId, String regelUrl)
         throws IOException, InterruptedException
   {
      var request = asAssignedHandlaggare(HttpRequest.newBuilder(), handlaggningId)
            .uri(URI.create(RTF_MANUELL_BASE_URL + regelUrl + "/" + handlaggningId))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
      // Wait for the regel to receive OUL's async assignment notification (see sendKompletteringGet).
      var response = httpSendRetries(client, request, HttpResponse.BodyHandlers.ofString(), 200, 10);
      // framework-regel-manuell 1.6.0 wraps the payload: {"data": {...}, "informationsaccess": {...}}
      return mapper.treeToValue(mapper.readTree(response.body()).path("data"), GetDataResponse.class);
   }

   static int sendRegelPatchData(String handlaggningId, String regelUrl, Beslutsutfall beslutsUtfall, UUID ersattningId)
         throws IOException, InterruptedException
   {
      var updateErsattning = new UpdateErsattning();
      updateErsattning.setErsattningId(ersattningId);
      updateErsattning.setBeslutsutfall(beslutsUtfall);
      updateErsattning.setAvslagsanledning("-");

      var patchRequest = new PatchErsattningRequest();
      patchRequest.setErsattningar(List.of(updateErsattning));

      var request = asAssignedHandlaggare(HttpRequest.newBuilder(), handlaggningId)
            .uri(URI.create(RTF_MANUELL_BASE_URL + regelUrl + "/" + handlaggningId))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .method("PATCH", HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(patchRequest)))
            .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      return response.statusCode();
   }

   /**
    * GET /api/task/{handlaggningId} on rtf-manuell-bff as the given handläggare.
    *
    * @return the raw HTTP response so callers can compare the body with the informationsaccess event
    */
   static HttpResponse<String> sendRtfManuellBffGetTask(UUID handlaggningId, String handlaggareVarde)
         throws IOException, InterruptedException
   {
      var request = HttpRequest.newBuilder()
            .uri(URI.create(RTF_MANUELL_BFF_BASE_URL + "/api/task/" + handlaggningId))
            .header("Authorization", "Bearer " + HANDLAGGARE_ID + ":" + handlaggareVarde)
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
      return client.send(request, HttpResponse.BodyHandlers.ofString());
   }

   /**
    * GET /api/uppgiftsbeskrivning/rtf-manuell on rtf-manuell-bff as the given handläggare.
    */
   static HttpResponse<String> sendRtfManuellBffGetUppgiftsbeskrivning(String handlaggareVarde)
         throws IOException, InterruptedException
   {
      var request = HttpRequest.newBuilder()
            .uri(URI.create(RTF_MANUELL_BFF_BASE_URL + "/api/uppgiftsbeskrivning/rtf-manuell"))
            .header("Authorization", "Bearer " + HANDLAGGARE_ID + ":" + handlaggareVarde)
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
      return client.send(request, HttpResponse.BodyHandlers.ofString());
   }

   /**
    * GET /regel/rtf-manuell-komplettering/{handlaggningId} — reads current komplettering data.
    *
    * @return the deserialized komplettering data (personnummer and avsikt)
    */
   static RtfKompletteringData sendKompletteringGet(UUID handlaggningId)
         throws IOException, InterruptedException
   {
      var request = asAssignedHandlaggare(HttpRequest.newBuilder(), handlaggningId.toString())
            .uri(URI.create(RTF_MANUELL_KOMPLETTERING_BASE_URL + "/regel/rtf-manuell-komplettering/" + handlaggningId))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
      // The regel learns about the assignment from OUL's async status notification, so allow a
      // few seconds for it to arrive after the task was claimed.
      var response = httpSendRetries(client, request, HttpResponse.BodyHandlers.ofString(), 200, 10);
      // framework-regel-komplettering 0.4.0 wraps the payload: {"data": {...}, "informationsaccess": {...}}
      return mapper.treeToValue(mapper.readTree(response.body()).path("data"), RtfKompletteringData.class);
   }

   /**
    * PATCH /regel/rtf-manuell-komplettering/{handlaggningId} — registers the handläggare's svar.
    *
    * @return the HTTP status code (204 on success)
    */
   static int sendKompletteringPatch(UUID handlaggningId, String personnummer, String avsikt)
         throws IOException, InterruptedException
   {
      var kompletteringData = new RtfKompletteringData();
      kompletteringData.setPersonnummer(personnummer);
      kompletteringData.setAvsikt(avsikt);

      var request = asAssignedHandlaggare(HttpRequest.newBuilder(), handlaggningId.toString())
            .uri(URI.create(RTF_MANUELL_KOMPLETTERING_BASE_URL + "/regel/rtf-manuell-komplettering/" + handlaggningId))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .method("PATCH", HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(kompletteringData)))
            .build();
      return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
   }

   /**
    * POST /regel/rtf-manuell-komplettering/{handlaggningId}/done — closes the komplettering OUL
    * task and continues regel processing synchronously; the main OUL task is created within this
    * HTTP call before 204 is returned.
    *
    * @return the HTTP status code (204 on success, 207 if OUL close failed, 409 if timed out,
    *         422 if yrkande still incomplete)
    */
   static int sendKompletteringDone(UUID handlaggningId) throws IOException, InterruptedException
   {
      var request = asAssignedHandlaggare(HttpRequest.newBuilder(), handlaggningId.toString())
            .uri(URI.create(RTF_MANUELL_KOMPLETTERING_BASE_URL + "/regel/rtf-manuell-komplettering/" + handlaggningId + "/done"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
      return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
   }

   /**
    * Minimal view of a sorteringsordning response — avoids deserializing the polymorphic Constraint
    * subtypes, which are not Java subtypes of Constraint in the generated JAX-RS spec jar.
    */
   record SorteringsordningInfo(UUID id, int entryCount)
   {
   }

   /**
    * POST /sorteringsordning — creates a new sorteringsordning from raw JSON spec; asserts HTTP 201.
    */
   static SorteringsordningInfo sendCreateSorteringsordning(String specJson)
         throws IOException, InterruptedException
   {
      var request = HttpRequest.newBuilder()
            .uri(URI.create(SORTERINGSORDNING_URL))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(specJson))
            .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(201, response.statusCode(), "POST /sorteringsordning");
      return parseSorteringsordningInfo(response.body());
   }

   /**
    * GET /sorteringsordning/{id} — returns the raw HTTP response; use when asserting non-200 status.
    */
   static HttpResponse<String> sendGetSorteringsordningRaw(UUID id)
         throws IOException, InterruptedException
   {
      var request = HttpRequest.newBuilder()
            .uri(URI.create(SORTERINGSORDNING_URL + "/" + id))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
      return client.send(request, HttpResponse.BodyHandlers.ofString());
   }

   /**
    * GET /sorteringsordning/{id} — fetches a sorteringsordning by id; asserts HTTP 200.
    */
   static SorteringsordningInfo sendGetSorteringsordning(UUID id)
         throws IOException, InterruptedException
   {
      var response = sendGetSorteringsordningRaw(id);
      assertEquals(200, response.statusCode(), "GET /sorteringsordning/" + id);
      return parseSorteringsordningInfo(response.body());
   }

   /**
    * GET /sorteringsordning/aktiv — fetches the current aktiv sorteringsordning; asserts HTTP 200.
    */
   static SorteringsordningInfo sendGetAktivSorteringsordning()
         throws IOException, InterruptedException
   {
      var request = HttpRequest.newBuilder()
            .uri(URI.create(SORTERINGSORDNING_URL + "/aktiv"))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode(), "GET /sorteringsordning/aktiv");
      return parseSorteringsordningInfo(response.body());
   }

   private static SorteringsordningInfo parseSorteringsordningInfo(String body) throws IOException
   {
      var node = mapper.readTree(body);
      var id = UUID.fromString(node.get("id").asText());
      var entries = node.get("entries");
      var entryCount = entries != null ? entries.size() : 0;
      return new SorteringsordningInfo(id, entryCount);
   }

   /**
    * PUT /sorteringsordning/{id}/aktiv — sets the given sorteringsordning as aktiv.
    *
    * @return the HTTP status code (204 on success, 404 if not found)
    */
   static int sendSetAktivSorteringsordning(UUID id)
         throws IOException, InterruptedException
   {
      var request = HttpRequest.newBuilder()
            .uri(URI.create(SORTERINGSORDNING_URL + "/" + id + "/aktiv"))
            .timeout(Duration.ofSeconds(10))
            .PUT(HttpRequest.BodyPublishers.noBody())
            .build();
      return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
   }

   /**
    * DELETE /sorteringsordning/{id} — deletes a sorteringsordning.
    *
    * @return the HTTP status code (204 on success, 404 if not found, 409 if it is the aktiv one)
    */
   static int sendDeleteSorteringsordning(UUID id)
         throws IOException, InterruptedException
   {
      var request = HttpRequest.newBuilder()
            .uri(URI.create(SORTERINGSORDNING_URL + "/" + id))
            .timeout(Duration.ofSeconds(10))
            .DELETE()
            .build();
      return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
   }

   /**
    * GET /uppgifter?limit=N&offset=M[&sorteringsordningId=X] — fetches a page of uppgifter.
    * Pass {@code null} for {@code sorteringsordningId} to use the current aktiv sorteringsordning.
    */
   static UppgiftPage sendGetUppgifterPage(int limit, int offset, UUID sorteringsordningId)
         throws IOException, InterruptedException
   {
      var url = UPPGIFTER_URL + "?limit=" + limit + "&offset=" + offset;
      if (sorteringsordningId != null)
      {
         url += "&sorteringsordningId=" + sorteringsordningId;
      }
      var request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode(), "GET /uppgifter");
      return mapper.readValue(response.body(), UppgiftPage.class);
   }

   /**
    * Truncates the OUL uppgift table (and its dependent tables via CASCADE) so each test class
    * starts from a known-empty state. Sorteringsordning tables are left untouched.
    */
   static void resetOulDatabase() throws IOException, InterruptedException
   {
      System.out.println("Resetting OUL database...");
      var process = new ProcessBuilder(
            "kubectl", "exec", "rimfrost-k8s-postgresql-0", "--",
            "/bin/sh", "-c",
            "PGPASSWORD=rimfrost-test psql -h 127.0.0.1 -U rimfrost-test -d rimfrost-test -c 'TRUNCATE TABLE operativt_uppgiftslager.uppgift CASCADE'")
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectErrorStream(true)
            .start();
      drainSubprocessIO(process.getInputStream());
      if (!process.waitFor(30, TimeUnit.SECONDS))
      {
         process.destroyForcibly();
         fail("Timed out waiting for OUL database reset");
      }
      if (process.exitValue() != 0)
      {
         fail("Failed to truncate OUL uppgift table");
      }
   }

   /**
    * Restarts a kubernetes deployment and blocks until the rollout completes.
    */
   static void restartDeployment(String deploymentName) throws IOException, InterruptedException
   {
      System.out.println("Restarting deployment: " + deploymentName);
      var process = new ProcessBuilder("kubectl", "rollout", "restart", "deployment/" + deploymentName)
            .redirectInput(ProcessBuilder.Redirect.INHERIT)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectErrorStream(true)
            .start();
      drainSubprocessIO(process.getInputStream());
      if (process.waitFor() != 0)
      {
         fail("kubectl rollout restart failed for " + deploymentName);
      }
      var rolloutWait = new ProcessBuilder("kubectl", "rollout", "status", "deployment/" + deploymentName, "--timeout=120s")
            .redirectInput(ProcessBuilder.Redirect.INHERIT)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectErrorStream(true)
            .start();
      drainSubprocessIO(rolloutWait.getInputStream());
      if (rolloutWait.waitFor() != 0)
      {
         fail("kubectl rollout status timed out for " + deploymentName);
      }
   }

   /**
    * Waits for the service health endpoint to return a non-error response, restarting the port-forward process
    * whenever it dies. Extracts the local port from {@code baseUrl}.
    *
    * @see #waitForServiceRestartingPortForward(String, int, String, int)
    */
   static void waitForServiceRestartingPortForward(String serviceName, String baseUrl, int timeoutSeconds)
         throws IOException, InterruptedException
   {
      waitForServiceRestartingPortForward(serviceName, URI.create(baseUrl).getPort(), baseUrl, timeoutSeconds);
   }

   /**
    * Waits for the service health endpoint to return a non-error response, restarting the port-forward process
    * whenever it dies. Forwards to service port 8080.
    *
    * @see #waitForServiceRestartingPortForward(String, int, int, String, int)
    */
   static void waitForServiceRestartingPortForward(String serviceName, int localPort, String baseUrl,
         int timeoutSeconds) throws IOException, InterruptedException
   {
      waitForServiceRestartingPortForward(serviceName, localPort, 8080, baseUrl, timeoutSeconds);
   }

   /**
    * Waits for the service health endpoint to return a non-error response, restarting the port-forward process
    * whenever it dies. This is necessary because {@code kubectl port-forward} exits when the backend pod is
    * unreachable — which happens immediately after a pod restart, before the new pod starts listening. Without
    * active restarts, all health-check attempts would fail with "connection refused" for the full timeout.
    * {@code remotePort} is the service port: 8080 for backends, the BFFs have their own.
    */
   static void waitForServiceRestartingPortForward(String serviceName, int localPort, int remotePort,
         String baseUrl, int timeoutSeconds) throws IOException, InterruptedException
   {
      System.out.println("Waiting for " + baseUrl + " (restarting port-forward as needed)");
      new ProcessBuilder("sh", "-c", "pkill -f 'kubectl port-forward.*" + localPort + "' 2>/dev/null; true")
            .start().waitFor();
      var deadline = java.time.Instant.now().plusSeconds(timeoutSeconds);
      Process pf = startPortForward(serviceName, localPort, remotePort);
      while (true)
      {
         if (!pf.isAlive())
         {
            pf = startPortForward(serviceName, localPort, remotePort);
            Thread.sleep(500);
         }
         else
         {
            try
            {
               var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(baseUrl + "/q/health"))
                     .GET()
                     .timeout(java.time.Duration.ofSeconds(5))
                     .build();
               var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding());
               if (response.statusCode() < 500)
               {
                  System.out.println("Service ready: " + baseUrl);
                  return;
               }
            }
            catch (Exception ignored)
            {
            }
         }
         if (java.time.Instant.now().isAfter(deadline))
            fail("Service not ready after " + timeoutSeconds + "s: " + baseUrl + "/q/health");
         Thread.sleep(1000);
      }
   }

   private static Process startPortForward(String serviceName, int localPort, int remotePort) throws IOException
   {
      return new ProcessBuilder("kubectl", "port-forward", "service/" + serviceName, localPort + ":" + remotePort)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
   }

   // Based on solution from https://github.com/allegro/embedded-elasticsearch/pull/48
   // for fixing "Corrupted channel by directly writing to native stream in forked JVM 1"
   // warning.
   private static void drainSubprocessIO(InputStream inputStream) throws IOException
   {
      BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, UTF_8));
      String line;
      while ((line = reader.readLine()) != null)
      {
         System.out.println(line);
      }
   }
}
