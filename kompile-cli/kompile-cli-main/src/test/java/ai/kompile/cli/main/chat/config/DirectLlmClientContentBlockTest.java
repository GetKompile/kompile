package ai.kompile.cli.main.chat.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the content block builders in DirectLlmClient for multimodal messages.
 * Covers both OpenAI-compatible and Anthropic formats.
 */
class DirectLlmClientContentBlockTest {

    private DirectLlmClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        ChatConfig config = new ChatConfig("openai", "test-key", "gpt-4o", "http://localhost");
        client = new DirectLlmClient(config, mapper);
    }

    // --- OpenAI content array ---

    @Test
    void openAiContentArrayWithImageHasImageUrlBlock() throws Exception {
        var att = new DirectLlmClient.AttachmentInput(
                "photo.png", "image/png", true, "aWdv", null);

        ArrayNode result = invokeBuildOpenAiContentArray("Describe this", List.of(att));

        assertEquals(2, result.size());
        // First: image block
        assertEquals("image_url", result.get(0).get("type").asText());
        String url = result.get(0).get("image_url").get("url").asText();
        assertTrue(url.startsWith("data:image/png;base64,"));
        assertTrue(url.contains("aWdv"));
        // Last: text block
        assertEquals("text", result.get(1).get("type").asText());
        assertEquals("Describe this", result.get(1).get("text").asText());
    }

    @Test
    void openAiContentArrayWithTextFileHasTextBlock() throws Exception {
        var att = new DirectLlmClient.AttachmentInput(
                "readme.md", "text/markdown", false, null, "# Hello");

        ArrayNode result = invokeBuildOpenAiContentArray("Summarize", List.of(att));

        assertEquals(2, result.size());
        assertEquals("text", result.get(0).get("type").asText());
        assertTrue(result.get(0).get("text").asText().contains("readme.md"));
        assertTrue(result.get(0).get("text").asText().contains("# Hello"));
        assertEquals("Summarize", result.get(1).get("text").asText());
    }

    @Test
    void openAiContentArrayWithMixedAttachments() throws Exception {
        var imgAtt = new DirectLlmClient.AttachmentInput(
                "chart.png", "image/png", true, "imgdata", null);
        var textAtt = new DirectLlmClient.AttachmentInput(
                "data.csv", "text/csv", false, null, "a,b\n1,2");

        ArrayNode result = invokeBuildOpenAiContentArray("Analyze", List.of(imgAtt, textAtt));

        assertEquals(3, result.size());
        assertEquals("image_url", result.get(0).get("type").asText());
        assertEquals("text", result.get(1).get("type").asText());
        assertTrue(result.get(1).get("text").asText().contains("data.csv"));
        assertEquals("Analyze", result.get(2).get("text").asText());
    }

    // --- Anthropic content array ---

    @Test
    void anthropicContentArrayWithImageHasBase64Block() throws Exception {
        var att = new DirectLlmClient.AttachmentInput(
                "photo.jpg", "image/jpeg", true, "jpeg64data", null);

        ArrayNode result = invokeBuildAnthropicContentArray("What is this?", List.of(att));

        assertEquals(2, result.size());
        // First: image block (Anthropic format)
        JsonNode imageBlock = result.get(0);
        assertEquals("image", imageBlock.get("type").asText());
        JsonNode source = imageBlock.get("source");
        assertNotNull(source);
        assertEquals("base64", source.get("type").asText());
        assertEquals("image/jpeg", source.get("media_type").asText());
        assertEquals("jpeg64data", source.get("data").asText());
        // Last: text block
        assertEquals("text", result.get(1).get("type").asText());
        assertEquals("What is this?", result.get(1).get("text").asText());
    }

    @Test
    void anthropicContentArrayWithTextFileHasTextBlock() throws Exception {
        var att = new DirectLlmClient.AttachmentInput(
                "code.py", "text/x-python", false, null, "print('hi')");

        ArrayNode result = invokeBuildAnthropicContentArray("Review", List.of(att));

        assertEquals(2, result.size());
        assertEquals("text", result.get(0).get("type").asText());
        assertTrue(result.get(0).get("text").asText().contains("code.py"));
        assertTrue(result.get(0).get("text").asText().contains("print('hi')"));
        assertEquals("Review", result.get(1).get("text").asText());
    }

    @Test
    void anthropicContentArrayWithMixedAttachments() throws Exception {
        var imgAtt = new DirectLlmClient.AttachmentInput(
                "screen.png", "image/png", true, "pngdata", null);
        var textAtt = new DirectLlmClient.AttachmentInput(
                "log.txt", "text/plain", false, null, "ERROR: timeout");

        ArrayNode result = invokeBuildAnthropicContentArray("Debug this", List.of(imgAtt, textAtt));

        assertEquals(3, result.size());
        assertEquals("image", result.get(0).get("type").asText());
        assertEquals("text", result.get(1).get("type").asText());
        assertTrue(result.get(1).get("text").asText().contains("log.txt"));
        assertEquals("Debug this", result.get(2).get("text").asText());
    }

    @Test
    void anthropicContentArrayWithNullAttachments() throws Exception {
        ArrayNode result = invokeBuildAnthropicContentArray("Hello", null);

        // Should just have the text block
        assertEquals(1, result.size());
        assertEquals("text", result.get(0).get("type").asText());
        assertEquals("Hello", result.get(0).get("text").asText());
    }

    @Test
    void openAiApiErrorIsReturnedAndPrinted() throws Exception {
        HttpServer server = startJsonServer("/v1/chat/completions", 500,
                "{\"error\":{\"message\":\"bad key\"}}");
        try {
            ChatConfig config = new ChatConfig("openai", "bad-key", "gpt-4o",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            DirectLlmClient errorClient = new DirectLlmClient(config, mapper);
            StringBuilder output = new StringBuilder();
            errorClient.setOutputConsumer(output::append);

            DirectLlmClient.StreamResult result =
                    errorClient.streamChat("hello", "", null, null);

            assertEquals("[LLM API error 500: bad key]", result.text);
            assertEquals(result.text, output.toString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cachedTokensStillCountTowardContextOccupancy() {
        DirectLlmClient.StreamResult result = new DirectLlmClient.StreamResult();
        result.inputTokens = 1_000;
        result.cacheReadTokens = 70_000;
        result.cacheCreationTokens = 2_000;

        assertEquals(73_000, result.contextInputTokens());
    }

    @Test
    void responsesToolResultWithoutRetainedCallRecoversAsOrdinaryContext() throws Exception {
        AtomicInteger turn = new AtomicInteger();
        AtomicReference<String> recoveredRequest = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/codex/responses", exchange -> {
            int currentTurn = turn.incrementAndGet();
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String body;
            if (currentTurn == 1) {
                body = """
                        data: {"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","id":"fc_1","call_id":"call_FKELUG01bpnSx0E0nIVwRnt1","name":"write","arguments":"{}"}}

                        data: {"type":"response.output_item.done","output_index":0,"item":{"type":"function_call","id":"fc_1","call_id":"call_FKELUG01bpnSx0E0nIVwRnt1","name":"write","arguments":"{}"}}

                        data: {"type":"response.completed","response":{"output":[],"usage":{}}}

                        """;
            } else {
                recoveredRequest.set(request);
                body = """
                        data: {"type":"response.output_text.delta","delta":"recovered"}

                        data: {"type":"response.completed","response":{"output":[],"usage":{}}}

                        """;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(bytes);
            }
        });
        server.start();

        try {
            ChatConfig config = new ChatConfig(
                    "openai-codex", "test-token", "gpt-5.4",
                    "http://127.0.0.1:" + server.getAddress().getPort());
            DirectLlmClient responsesClient = new DirectLlmClient(config, mapper);
            DirectLlmClient.StreamResult first =
                    responsesClient.streamChat("use the write tool", "system", null, null);
            assertEquals(1, first.toolCalls.size());

            // Reproduce full compaction/resume losing the provider-owned call item
            // while the executor still has its completed result to submit.
            responsesClient.replaceHistoryWithSummary("prior work summary");
            DirectLlmClient.ToolCallResultInput result = new DirectLlmClient.ToolCallResultInput(
                    "call_FKELUG01bpnSx0E0nIVwRnt1", "write", "markdown saved", false);
            DirectLlmClient.StreamResult second =
                    responsesClient.streamChat(null, "system", null, List.of(result));

            assertEquals("recovered", second.text);
            JsonNode input = mapper.readTree(recoveredRequest.get()).path("input");
            boolean recovered = false;
            for (JsonNode item : input) {
                assertNotEquals("function_call_output", item.path("type").asText(),
                        "an output without its matching call must never reach OpenAI");
                for (JsonNode block : item.path("content")) {
                    if (block.path("text").asText().contains("Recovered tool result for write")) {
                        recovered = true;
                    }
                }
            }
            assertTrue(recovered, "the completed tool result should remain available as ordinary context");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unmatchedResponsesFunctionCallIsDroppedBeforeNextTurn() throws Exception {
        AtomicInteger turn = new AtomicInteger();
        AtomicReference<String> nextRequest = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/codex/responses", exchange -> {
            int currentTurn = turn.incrementAndGet();
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String body;
            int status = 200;
            if (currentTurn == 1) {
                body = """
                        data: {"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","id":"fc_unanswered","call_id":"call_UNANSWERED","name":"write","arguments":"{}"}}

                        data: {"type":"response.output_item.done","output_index":0,"item":{"type":"function_call","id":"fc_unanswered","call_id":"call_UNANSWERED","name":"write","arguments":"{}"}}

                        data: {"type":"response.completed","response":{"output":[],"usage":{}}}

                        """;
            } else {
                nextRequest.set(request);
                JsonNode input = mapper.readTree(request).path("input");
                boolean hasUnansweredCall = false;
                for (JsonNode item : input) {
                    if ("function_call".equals(item.path("type").asText())
                            && "call_UNANSWERED".equals(item.path("call_id").asText())) {
                        hasUnansweredCall = true;
                        break;
                    }
                }
                if (hasUnansweredCall) {
                    status = 400;
                    body = "{\"error\":{\"message\":\"No tool output found for function call call_UNANSWERED\"}}";
                } else {
                    body = """
                            data: {"type":"response.output_text.delta","delta":"next turn"}

                            data: {"type":"response.completed","response":{"output":[],"usage":{}}}

                            """;
                }
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type",
                    status == 200 ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(bytes);
            }
        });
        server.start();

        try {
            ChatConfig config = new ChatConfig(
                    "openai-codex", "test-token", "gpt-5.4",
                    "http://127.0.0.1:" + server.getAddress().getPort());
            DirectLlmClient responsesClient = new DirectLlmClient(config, mapper);
            DirectLlmClient.StreamResult first =
                    responsesClient.streamChat("use the write tool", "system", null, null);
            assertEquals(1, first.toolCalls.size());

            DirectLlmClient.StreamResult second =
                    responsesClient.streamChat("next queued message", "system", null, null);

            assertEquals("next turn", second.text);
            assertNotNull(nextRequest.get());
            JsonNode input = mapper.readTree(nextRequest.get()).path("input");
            for (JsonNode item : input) {
                assertFalse("function_call".equals(item.path("type").asText())
                                && "call_UNANSWERED".equals(item.path("call_id").asText()),
                        "an unanswered function call must not be sent on the next request");
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void nullExceptionMessageUsesExceptionType() throws Exception {
        Method method = DirectLlmClient.class.getDeclaredMethod(
                "formatExceptionMessage", Exception.class);
        method.setAccessible(true);

        String message = (String) method.invoke(client, new Exception());

        assertEquals("Exception", message);
    }

    // --- helpers ---

    private ArrayNode invokeBuildOpenAiContentArray(String text, List<DirectLlmClient.AttachmentInput> attachments) throws Exception {
        Method method = DirectLlmClient.class.getDeclaredMethod(
                "buildOpenAiContentArray", String.class, List.class);
        method.setAccessible(true);
        return (ArrayNode) method.invoke(client, text, attachments);
    }

    private ArrayNode invokeBuildAnthropicContentArray(String text, List<DirectLlmClient.AttachmentInput> attachments) throws Exception {
        Method method = DirectLlmClient.class.getDeclaredMethod(
                "buildAnthropicContentArray", String.class, List.class);
        method.setAccessible(true);
        return (ArrayNode) method.invoke(client, text, attachments);
    }

    private HttpServer startJsonServer(String path, int status, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(bytes);
            }
        });
        server.start();
        return server;
    }
}
