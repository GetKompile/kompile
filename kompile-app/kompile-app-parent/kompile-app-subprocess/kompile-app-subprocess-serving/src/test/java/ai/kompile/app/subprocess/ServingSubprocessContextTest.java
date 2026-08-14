package ai.kompile.app.subprocess;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ServingSubprocessContextTest {

    @Test
    void explicitNativeComponentsServeStatusWithoutReflectiveSpringBootstrap() throws Exception {
        ServingSubprocessHttpServer server = null;
        try (ServingSubprocessMain.ServingComponents components =
                     ServingSubprocessMain.createServingComponents(
                             "http://127.0.0.1:19090/",
                             "/tmp/managed-llm-cache")) {
            assertNotNull(components.languageModel());

            server = ServingSubprocessHttpServer.start(
                    "127.0.0.1",
                    0,
                    components.objectMapper(),
                    components.modelController(),
                    components.generateController());
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + server.port() + "/api/llm/status"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            JsonNode status = components.objectMapper().readTree(response.body());
            assertEquals("direct", status.path("mode").asText());
            assertFalse(status.path("loaded").asBoolean());
            assertEquals("http://127.0.0.1:19090", status.path("stagingUrl").asText());
            assertEquals("/tmp/managed-llm-cache", status.path("cacheDir").asText());
        } finally {
            if (server != null) {
                server.close();
            }
        }
    }
}
