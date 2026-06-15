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
