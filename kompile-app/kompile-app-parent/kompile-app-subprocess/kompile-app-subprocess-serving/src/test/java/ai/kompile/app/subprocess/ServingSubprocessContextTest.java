package ai.kompile.app.subprocess;

import ai.kompile.app.llm.pipeline.LlmGenerateController;
import ai.kompile.app.llm.pipeline.LlmModelController;
import ai.kompile.app.llm.pipeline.SameDiffLanguageModelImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

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
    void plainContextServesTheRealStatusControllerWithoutASecondBootApplication() throws Exception {
        String directProperty = "kompile.llm.direct-serving.enabled";
        String stagingProperty = "kompile.staging.url";
        String cacheProperty = "kompile.llm.cache.dir";
        String previousDirect = System.getProperty(directProperty);
        String previousStaging = System.getProperty(stagingProperty);
        String previousCache = System.getProperty(cacheProperty);
        System.setProperty(directProperty, "true");
        System.setProperty(stagingProperty, "http://127.0.0.1:19090/");
        System.setProperty(cacheProperty, "/tmp/managed-llm-cache");

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        ServingSubprocessHttpServer server = null;
        try {
            context.register(SubprocessServingConfiguration.class);
            context.refresh();

            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
            LlmModelController modelController = context.getBean(LlmModelController.class);
            LlmGenerateController generateController = context.getBean(LlmGenerateController.class);
            assertNotNull(context.getBean(SameDiffLanguageModelImpl.class));

            server = ServingSubprocessHttpServer.start(
                    "127.0.0.1", 0, objectMapper, modelController, generateController);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + server.port() + "/api/llm/status"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            JsonNode status = objectMapper.readTree(response.body());
            assertEquals("direct", status.path("mode").asText());
            assertFalse(status.path("loaded").asBoolean());
            assertEquals("http://127.0.0.1:19090", status.path("stagingUrl").asText());
            assertEquals("/tmp/managed-llm-cache", status.path("cacheDir").asText());
        } finally {
            if (server != null) {
                server.close();
            }
            context.close();
            restoreProperty(directProperty, previousDirect);
            restoreProperty(stagingProperty, previousStaging);
            restoreProperty(cacheProperty, previousCache);
        }
    }

    private static void restoreProperty(String name, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previousValue);
        }
    }
}
