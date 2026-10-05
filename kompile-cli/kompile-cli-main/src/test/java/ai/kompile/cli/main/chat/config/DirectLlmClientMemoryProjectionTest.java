package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@TemporaryUserHome
class DirectLlmClientMemoryProjectionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String MEMORY = "stable memory sentinel";

    private String enriched(String memory, String retrieval, String prompt) {
        return "<memory_context>\n[Persistent memory]\n" + memory
                + retrieval + "</memory_context>\n\n" + prompt;
    }

    @Test
    void everyHttpShapeDeduplicatesWithoutMutatingHistoryOrRetrieval() throws Exception {
        for (String builder : List.of("buildOpenAiMessages", "buildResponsesInput",
                "buildAnthropicMessages", "buildPiMessages")) {
            DirectLlmClient client = new DirectLlmClient(
                    new ChatConfig("custom", null, "test-model", "http://unused.invalid"), mapper);
            ObjectNode older = mapper.createObjectNode().put("role", "user").put("id", "old-id");
            String oldText = enriched(MEMORY, "\n[Retrieved documents]\nold evidence\n", "old request");
            if (builder.equals("buildOpenAiMessages")) older.put("content", oldText);
            else {
                ArrayNode parts = older.putArray("content");
                parts.addObject().put("type", "image").put("data", "image-sentinel");
                parts.addObject().put("type", builder.equals("buildResponsesInput") ? "input_text" : "text")
                        .put("text", oldText);
            }
            List<ObjectNode> history = history(client);
            history.add(older);
            ObjectNode assistant = mapper.createObjectNode().put("role", "assistant")
                    .put("content", "answer");
            history.add(assistant);
            String original = history.toString();
            String pending = enriched(MEMORY, "\n[Previous conversations]\nnew evidence\n", "new request");

            ArrayNode wire = build(client, builder, pending);

            assertEquals(1, occurrences(wire.toString(), MEMORY), builder);
            assertTrue(wire.toString().contains("old evidence"), builder);
            assertTrue(wire.toString().contains("new evidence"), builder);
            assertTrue(wire.toString().contains("old request"), builder);
            assertTrue(wire.toString().contains("new request"), builder);
            assertTrue(wire.toString().contains("old-id"), builder);
            if (!builder.equals("buildOpenAiMessages")) {
                assertTrue(wire.toString().contains("image-sentinel"), builder);
            }
            assertEquals(original, history.toString(), "request projection must not mutate retry/replay history");
            ArrayNode retry = build(client, builder, pending);
            // Pi stamps each newly constructed pending envelope with wall-clock time.
            ((ObjectNode) wire.get(wire.size() - 1)).remove("timestamp");
            ((ObjectNode) retry.get(retry.size() - 1)).remove("timestamp");
            assertEquals(wire, retry, "projection must be repeatable for retries");
        }
    }

    @Test
    void toolContinuationKeepsNewestMemoryAndLeavesToolEnvelopesUntouched() {
        ArrayNode messages = mapper.createArrayNode();
        messages.addObject().put("role", "user").put("content", enriched(MEMORY, "", "old"));
        messages.addObject().put("role", "user").put("content",
                "<kompile_reminders>\nkeep reminder\n</kompile_reminders>\n\n"
                        + enriched(MEMORY, "", "latest"));
        ObjectNode result = messages.addObject().put("role", "user");
        result.putArray("content").addObject().put("type", "tool_result")
                .put("tool_use_id", "call-id").put("content", enriched(MEMORY, "", "tool text"));
        ObjectNode opaque = messages.addObject().put("type", "compaction").put("encrypted_content", "opaque");

        ArrayNode wire = MemoryContextProjection.project(messages);

        assertEquals("old", wire.get(0).path("content").asText());
        assertEquals(messages.get(1), wire.get(1));
        assertEquals(result, wire.get(2));
        assertEquals(opaque, wire.get(3));
        assertTrue(wire.get(1).path("content").asText().contains("keep reminder"));
    }

    @Test
    void changedSnapshotsEmbeddedLiteralTagsAndMalformedWrappersRemainUntouched() {
        ArrayNode messages = mapper.createArrayNode();
        messages.addObject().put("role", "user").put("content", enriched("old memory", "", "old"));
        messages.addObject().put("role", "user").put("content", enriched("new memory", "", "new"));
        messages.addObject().put("role", "user").put("content", "Please inspect " + enriched("new memory", "", "example"));
        messages.addObject().put("role", "user").put("content", "<memory_context>\n[Persistent memory]\nnew memory");
        assertEquals(messages, MemoryContextProjection.project(messages));
    }

    @Test
    void localChatEstimateUsesProjectedToolsResultsAndAttachmentsWithoutMutatingHistory() throws Exception {
        DirectLlmClient client = new DirectLlmClient(
                new ChatConfig("openai", "key", "gpt-test", "http://unused.invalid"), mapper);
        String memory = "m".repeat(20_000);
        for (int i = 0; i < 3; i++) client.addToHistory("user", enriched(memory, "", "old " + i));
        String original = history(client).toString();
        ArrayNode tools = mapper.createArrayNode();
        tools.addObject().put("name", "read").put("description", "d".repeat(4_000))
                .putObject("inputSchema").put("type", "object");
        DirectLlmClient.ToolCallResultInput result = new DirectLlmClient.ToolCallResultInput(
                "call-1", "read", "tool output sentinel", false);
        List<DirectLlmClient.ToolCallResultInput> results = List.of(result);
        List<DirectLlmClient.AttachmentInput> media = List.of(
                new DirectLlmClient.AttachmentInput("document.txt", "text/plain", false, null, "f".repeat(4_000)),
                new DirectLlmClient.AttachmentInput("image.png", "image/png", true, "a".repeat(40_000), null));
        String pending = enriched(memory, "", "continue");

        DirectLlmClient.TokenCountResult estimate = client.estimateInputTokens(
                pending, "system", tools, results, null, media);
        Method messages = DirectLlmClient.class.getDeclaredMethod("buildOpenAiMessages",
                String.class, String.class, List.class, List.class);
        messages.setAccessible(true);
        ArrayNode input = (ArrayNode) messages.invoke(client, pending, "system", results, media);
        Method convert = DirectLlmClient.class.getDeclaredMethod("convertToolDefsToOpenAi", ArrayNode.class);
        convert.setAccessible(true);
        ArrayNode wireTools = (ArrayNode) convert.invoke(client, tools);

        assertTrue(estimate.supported());
        assertFalse(estimate.exact(), "a local chars/4 estimate is never provider-exact");
        assertEquals(DirectLlmClient.estimateRequestInputTokens(input, wireTools), estimate.inputTokens());
        assertEquals(1, occurrences(input.toString(), memory));
        assertTrue(input.toString().contains(result.output));
        assertTrue(estimate.inputTokens() < original.length() / 4L,
                "neither repeated memory nor image base64 should inflate the estimate");
        assertTrue(estimate.inputTokens() > client.estimateInputTokens(
                pending, "system", null, null, null, List.of()).inputTokens() + 2_000,
                "pending tools, file text and image overhead must be included");
        assertEquals(original, history(client).toString());
        assertEquals(estimate, client.estimateInputTokens(pending, "system", tools, results, null, media));
    }

    @Test
    void responsesLocalEstimateRestoresSanitizedHistoryAndDeclinesOpaqueContext() throws Exception {
        DirectLlmClient client = new DirectLlmClient(
                new ChatConfig("openai-codex", "key", "gpt-test", "http://unused.invalid"), mapper);
        List<ObjectNode> history = history(client);
        ObjectNode orphan = mapper.createObjectNode().put("type", "function_call_output")
                .put("call_id", "orphan").put("output", "o".repeat(40_000));
        history.add(orphan);
        client.addToHistory("user", "earlier request");
        String original = history.toString();

        DirectLlmClient.TokenCountResult estimate = client.estimateInputTokens(
                "continue", null, null, null, null, List.of());

        assertTrue(estimate.supported());
        assertFalse(estimate.exact());
        assertTrue(estimate.inputTokens() < 1_000, "orphan outputs are not on the wire");
        assertEquals(original, history.toString(), "the local estimate must undo sanitization");
        assertEquals(estimate, client.estimateInputTokens("continue", null, null, null, null, List.of()));
        history.add(mapper.createObjectNode().put("type", "compaction").put("encrypted_content", "ciphertext"));
        String opaqueHistory = history.toString();
        assertFalse(client.estimateInputTokens("continue", "system", null, null, null, List.of()).supported());
        assertEquals(opaqueHistory, history.toString(), "even an unsupported estimate must undo sanitization");
    }

    @Test
    void localEstimateRetainsDifferentMemorySnapshotsAndDeclinesNonOpenAiSessions() {
        DirectLlmClient client = new DirectLlmClient(
                new ChatConfig("openai", "key", "gpt-test", "http://unused.invalid"), mapper);
        client.addToHistory("user", enriched("a".repeat(20_000), "", "old"));
        assertTrue(client.estimateInputTokens(enriched("b".repeat(20_000), "", "new"),
                "system", null, null, null, List.of()).inputTokens() >= 10_000);
        DirectLlmClient anthropic = new DirectLlmClient(
                new ChatConfig("anthropic", "key", "test-model", "http://unused.invalid"), mapper);
        assertFalse(anthropic.estimateInputTokens("new", "system", null, null, null, List.of()).supported());
    }

    @Test
    void responsesCountAndStreamSendIdenticalProjectedInputs() throws Exception {
        AtomicReference<JsonNode> counted = new AtomicReference<>();
        AtomicReference<JsonNode> streamed = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/codex/responses/input_tokens", exchange -> {
            counted.set(mapper.readTree(exchange.getRequestBody()));
            byte[] body = "{\"input_tokens\":42}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/codex/responses", exchange -> {
            streamed.set(mapper.readTree(exchange.getRequestBody()));
            byte[] body = ("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\","
                    + "\"usage\":{\"input_tokens\":42,\"output_tokens\":0}}}\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            DirectLlmClient client = new DirectLlmClient(new ChatConfig("openai-codex", "key", "gpt-test",
                    "http://127.0.0.1:" + server.getAddress().getPort()), mapper);
            client.addToHistory("user", enriched(MEMORY, "", "old"));
            String pending = enriched(MEMORY, "", "new");
            ArrayNode tools = mapper.createArrayNode();
            tools.addObject().put("name", "read").put("description", "read a file")
                    .putObject("inputSchema").put("type", "object");
            String system = "instructions ".repeat(100);
            DirectLlmClient.TokenCountResult estimate = client.estimateInputTokens(
                    pending, system, tools, null, null, List.of());
            assertTrue(estimate.supported());
            assertFalse(estimate.exact());
            assertNull(counted.get(), "the local estimate must not call the provider");
            assertNull(streamed.get());
            assertTrue(client.countInputTokens(pending, system, tools, null, null).exact());
            client.setOutputConsumer(ignored -> { });
            assertFalse(client.streamChat(pending, system, tools, null).failed);
            assertEquals(counted.get().path("input"), streamed.get().path("input"));
            assertEquals(1, occurrences(streamed.get().path("input").toString(), MEMORY));
            assertEquals(DirectLlmClient.estimateRequestInputTokens(
                            (ArrayNode) streamed.get().path("input"), (ArrayNode) streamed.get().path("tools"))
                            + (streamed.get().path("instructions").asText().length() + 3L) / 4L,
                    estimate.inputTokens(), "Codex instructions and converted tools must be counted once");
        } finally {
            server.stop(0);
        }
    }

    @SuppressWarnings("unchecked")
    private List<ObjectNode> history(DirectLlmClient client) throws Exception {
        Field field = DirectLlmClient.class.getDeclaredField("conversationHistory");
        field.setAccessible(true);
        return (List<ObjectNode>) field.get(client);
    }

    private ArrayNode build(DirectLlmClient client, String builder, String pending) throws Exception {
        Method method;
        Object[] args;
        if (builder.equals("buildResponsesInput")) {
            method = DirectLlmClient.class.getDeclaredMethod(builder, String.class, String.class,
                    List.class, boolean.class, List.class);
            args = new Object[]{pending, "system", List.of(), false, List.of()};
        } else if (builder.equals("buildOpenAiMessages")) {
            method = DirectLlmClient.class.getDeclaredMethod(builder, String.class, String.class, List.class, List.class);
            args = new Object[]{pending, "system", List.of(), List.of()};
        } else {
            method = DirectLlmClient.class.getDeclaredMethod(builder, String.class, List.class, List.class);
            args = new Object[]{pending, List.of(), List.of()};
        }
        method.setAccessible(true);
        return (ArrayNode) method.invoke(client, args);
    }

    private int occurrences(String text, String needle) {
        return (text.length() - text.replace(needle, "").length()) / needle.length();
    }
}
