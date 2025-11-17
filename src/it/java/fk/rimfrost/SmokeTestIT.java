package fk.rimfrost;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import se.fk.rimfrost.api.vardavhusdjur.jaxrsspec.controllers.generatedsource.model.VahRequest;
import se.fk.rimfrost.api.vardavhusdjur.jaxrsspec.controllers.generatedsource.model.VahResponse;

public class SmokeTestIT {

    // Base URL configurable via environment variable or system property
    private static final String BASE_URL =
            System.getenv("BASE_URL") != null ? System.getenv("BASE_URL")
                    : System.getProperty("baseUrl", "http://localhost:8888/vah");

    private static final HttpClient client = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Waits for the given URL to become reachable (HTTP 200).
     */
    @SuppressWarnings("SameParameterValue")
    private static void waitForService(String url, int maxAttempts, int sleepSeconds)
            throws InterruptedException {
        for (int i = 0; i < maxAttempts; i++) {
            try {
                var request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(3))
                        .GET()
                        .build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    System.out.println("Service is up at " + url);
                    return;
                }
            } catch (Exception ignored) {
                System.out.println("Waiting for service at " + url + "...");
            }
            Thread.sleep(sleepSeconds * 1000L);
        }
        fail("Service at " + url + " did not become ready in time");
    }

    @DisplayName("Smoke test för VAH flöde")
    @ParameterizedTest(name = "POST med personnummer={0}")
    @ValueSource(strings = {"12345"})
    void smokeTest_VahRequest(String personnummer) throws IOException, InterruptedException {
        waitForService(BASE_URL, 10, 5);
        // send VahRequest
        var vahRequest = new VahRequest();
        vahRequest.setPnr(personnummer);
        String jsonBody = mapper.writeValueAsString(vahRequest);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        // verify VahResponse
        System.out.println("Response code: " + response.statusCode());
        System.out.println("Response body: " + response.body());
        assertEquals(201, response.statusCode(), "Unexpected HTTP status code");
        VahResponse vahResponse = mapper.readValue(response.body(), VahResponse.class);
        assertNotNull(vahResponse.getId());
        assertNotNull(vahResponse.getProcessId());
        assertEquals(personnummer, vahResponse.getPnr());
        assertNull(vahResponse.getRtfResult());
        assertNull(vahResponse.getRtfManuellResult());
    }
}
