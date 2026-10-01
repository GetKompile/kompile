package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A replayed user turn (resume, provider switch, compaction rebuild) keeps its image in the
 * shape the route's history keeps after a live turn, so the next request resends it.
 */
@TemporaryUserHome
class DirectLlmClientReplayedAttachmentTest {

    private static final String DATA = "cGFnZQ==";
    private static final DirectLlmClient.AttachmentInput IMAGE =
            new DirectLlmClient.AttachmentInput("report.png", "image/png", true, DATA, null);

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;

    @BeforeEach
    void bind() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void openAiChatResendsAnImageUrlPart() {
        capture("/v1/chat/completions", "text/event-stream",
                "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: [DONE]\n\n");

        JsonNode request = replayAndAsk(new ChatConfig("openai", "test-key", "gpt-4o", baseUrl() + "/v1"));

        JsonNode image = onlyPart(request.path("messages"), "image_url");
        assertEquals("data:image/png;base64," + DATA, image.path("image_url").path("url").asText());
    }

    @Test
    void openAiResponsesResendsAnInputImage() {
        capture("/codex/responses", "text/event-stream",
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"ok\"}\n\n"
                        + "data: {\"type\":\"response.completed\",\"response\":{\"output\":[],\"usage\":{}}}\n\n");

        JsonNode request = replayAndAsk(new ChatConfig("openai-codex", "test-key", "gpt-5.4", baseUrl()));

        JsonNode image = onlyPart(request.path("input"), "input_image");
        assertEquals("data:image/png;base64," + DATA, image.path("image_url").asText());
    }

    @Test
    void anthropicResendsABase64ImageBlock() {
        capture("/v1/messages", "text/event-stream",
                "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":1}}}\n\n"
                        + "data: {\"type\":\"content_block_start\",\"content_block\":{\"type\":\"text\"}}\n\n"
                        + "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}\n\n"
                        + "data: {\"type\":\"content_block_stop\"}\n\n"
                        + "data: {\"type\":\"message_stop\"}\n\n");

        JsonNode request = replayAndAsk(new ChatConfig("anthropic", "test-key", "claude-sonnet", baseUrl()));

        JsonNode image = onlyPart(request.path("messages"), "image");
        assertEquals("base64", image.path("source").path("type").asText());
        assertEquals("image/png", image.path("source").path("media_type").asText());
        assertEquals(DATA, image.path("source").path("data").asText());
    }

    @Test
    void radiusResendsPiImageContent() {
        server.createContext("/v1/config", exchange -> respond(exchange, "application/json",
                "{\"baseUrl\":\"" + baseUrl() + "/pi\",\"models\":["
                        + "{\"id\":\"radius-vision\",\"input\":[\"text\",\"image\"]}]}"));
        capture("/pi/messages", "text/event-stream",
                "data: {\"type\":\"text_delta\",\"contentIndex\":0,\"delta\":\"ok\"}\n\n"
                        + "data: {\"type\":\"done\",\"reason\":\"stop\",\"usage\":{\"input\":2,\"output\":1,"
                        + "\"cacheRead\":0,\"cacheWrite\":0,\"totalTokens\":3,\"cost\":{}}}\n\n");

        JsonNode request = replayAndAsk(new ChatConfig("radius", "radius-key", "radius-vision", baseUrl()));

        JsonNode image = onlyPart(request.path("context").path("messages"), "image");
        assertEquals(DATA, image.path("data").asText());
        assertEquals("image/png", image.path("mimeType").asText());
    }

    @Test
    void localServingResendsTheImageWithoutItsPath() {
        capture("/api/llm/chat", "application/json",
                "{\"content\":\"ok\",\"rawText\":\"ok\",\"finishReason\":\"stop\",\"toolCalls\":[]}");

        JsonNode messages = replayAndAsk(new ChatConfig("kompile-local", null, "local", baseUrl()))
                .path("request").path("messages");

        JsonNode replayed = messages.path(1);
        assertEquals("what is in it?", replayed.path("content").asText(), messages.toString());
        assertEquals(1, replayed.path("images").size(), replayed.toString());
        JsonNode image = replayed.path("images").path(0);
        assertEquals("image/png", image.path("mimeType").asText());
        assertEquals(DATA, image.path("base64Data").asText());
        assertTrue(image.path("path").isMissingNode(), "the wire carries no local path: " + image);
        assertEquals("and now?", messages.path(3).path("content").asText(), messages.toString());
        assertTrue(messages.path(3).path("images").isMissingNode(), messages.toString());
    }

    /** Replay an answered turn that attached the image, ask a follow-up; the request it sent. */
    private JsonNode replayAndAsk(ChatConfig config) {
        server.start();
        DirectLlmClient client = new DirectLlmClient(config, mapper);
        client.setOutputConsumer(ignored -> { });
        client.addReplayedUserTurn("what is in it?", List.of(IMAGE), null);
        client.addToHistory("assistant", "A report.");

        DirectLlmClient.StreamResult result = client.streamChat("and now?", "system", null, null);

        assertFalse(result.failed, result.text);
        assertEquals(1, requests.size(), requests.toString());
        return requests.get(0);
    }

    /** The one content part of this type in the request: the follow-up attaches nothing. */
    private static JsonNode onlyPart(JsonNode messages, String type) {
        List<JsonNode> parts = new ArrayList<>();
        for (JsonNode message : messages) {
            for (JsonNode part : message.path("content")) {
                if (type.equals(part.path("type").asText())) parts.add(part);
            }
        }
        assertEquals(1, parts.size(), messages.toString());
        return parts.get(0);
    }

    private void capture(String path, String contentType, String body) {
        server.createContext(path, exchange -> {
            try {
                requests.add(mapper.readTree(exchange.getRequestBody()));
            } finally {
                respond(exchange, contentType, body);
            }
        });
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, String contentType, String body) throws IOException {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        } finally {
            exchange.close();
        }
    }
}
