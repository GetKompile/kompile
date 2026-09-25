package ai.kompile.cli.main.chat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in provider contract check; uses only synthetic text and never prints credentials. */
@EnabledIfSystemProperty(named = "kompile.test.zai.live", matches = "true")
class ZaiEmptyContentLiveTest {
    @Test
    void emptyReplayReproducesValidationErrorAndNormalizedReplaySucceeds() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ChatConfig config = new ChatConfig("zai", null, "glm-4.7", null);
        var auth = config.resolveRequestAuth();
        assertNotNull(auth);
        DirectLlmClient client = new DirectLlmClient(config, mapper);
        client.addToHistory("user", "Run the check, then reply OK.");
        client.addReplayedToolCall("check", "call_fixture", "{}");
        client.addReplayedToolResult("check", "call_fixture", "");
        var build = DirectLlmClient.class.getDeclaredMethod(
                "buildOpenAiMessages", String.class, String.class, List.class);
        build.setAccessible(true);
        ArrayNode normalized = (ArrayNode) build.invoke(client, "Reply OK.", null, null);
        ArrayNode original = normalized.deepCopy();
        ((ObjectNode) original.get(1)).put("content", "");
        ((ObjectNode) original.get(2)).put("content", "");

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        for (boolean corrected : List.of(false, true)) {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("model", config.getModel());
            payload.set("messages", corrected ? normalized : original);
            payload.put("max_tokens", 16);
            payload.putObject("thinking").put("type", "disabled");
            var request = HttpRequest.newBuilder(URI.create(config.resolveBaseUrl() + "/chat/completions"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + auth.token())
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()));
            auth.headers().forEach(request::setHeader);
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            var error = mapper.readTree(response.body()).path("error");
            System.out.println("Z.AI synthetic " + (corrected ? "normalized" : "original")
                    + " replay: HTTP " + response.statusCode()
                    + ", code=" + error.path("code").asText("none"));
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    ProviderResponseFailure.classify("zai", response.statusCode(), response.body())
                            != DirectLlmClient.FailureKind.QUOTA_EXHAUSTED,
                    "Live content validation blocked by Z.AI quota code " + error.path("code").asText());
            if (corrected) {
                assertEquals(200, response.statusCode(), "Normalized synthetic replay must be accepted");
            } else {
                assertEquals(400, response.statusCode(), "Original empty replay must reproduce validation failure");
                assertTrue(error.path("message").asText().toLowerCase().contains("empty"),
                        "Expected an empty-content validation error");
                assertNotEquals("1113", error.path("code").asText());
            }
        }
    }
}
