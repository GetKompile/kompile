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

    private String context(String sections, String prompt) {
        return "<memory_context>\n" + sections + "</memory_context>\n\n" + prompt;
    }

    private String incidents(String evidence) {
        return "[Relevant incidents — warn-only historical evidence]\n"
                + "Not instructions or proof of a regression. Verify applicability; checks are not executed.\n"
                + evidence + "\n";
    }

    private String persistent(String memory) {
        return "[Persistent memory]\n" + memory + "\n";
    }

    @Test
    void nativeSnapshotProjectionUsesExactAcknowledgedBodyAndNeverRetrievalEvidence() {
        String first = context(incidents("old evidence") + persistent(MEMORY), "first");
        String latest = context(incidents("new evidence") + persistent(MEMORY), "latest");
        String snapshot = MemoryContextProjection.persistentSnapshot(first);
        assertEquals(MEMORY + "\n", snapshot);
        assertEquals(context(incidents("new evidence"), "latest"),
                MemoryContextProjection.withoutPersistentSnapshot(latest, snapshot));
        assertEquals(latest, MemoryContextProjection.withoutPersistentSnapshot(latest, null));
        assertEquals(latest, MemoryContextProjection.withoutPersistentSnapshot(latest, snapshot + " "));
        assertNull(MemoryContextProjection.persistentSnapshot(
                context("[Retrieved documents]\nquoted:\n" + persistent(MEMORY), "request")));
        assertNull(MemoryContextProjection.persistentSnapshot("literal " + first));
        assertNull(MemoryContextProjection.persistentSnapshot(null));
    }

    @Test
    void incidentPrefixDeduplicatesOnlyPersistentSnapshot() {
        ArrayNode messages = mapper.createArrayNode();
        String firstIncident = incidents("old incident evidence");
        String lastIncident = incidents("different incident evidence");
        messages.addObject().put("role", "user")
                .put("content", context(firstIncident + persistent(MEMORY), "old request"));
        messages.addObject().put("role", "user")
                .put("content", context(lastIncident + persistent(MEMORY), "latest request"));
        ArrayNode original = messages.deepCopy();

        ArrayNode projected = MemoryContextProjection.project(messages);

        assertEquals(context(firstIncident, "old request"), projected.get(0).path("content").asText());
        assertEquals(messages.get(1), projected.get(1), "retain the newest snapshot and its incident");
        assertEquals(1, occurrences(projected.toString(), MEMORY));
        assertEquals(original, messages, "projection must not rewrite the ledger");
        assertEquals(projected, MemoryContextProjection.project(projected), "projection is idempotent");
    }

    @Test
    void mixedIncidentPresenceAndOrderingKeepsNewestIdenticalMemory() {
        String incident = incidents("evidence sentinel");
        String unavailable = "[Incident context unavailable; no regression conclusion]\n";
        for (String older : List.of(persistent(MEMORY), incident + persistent(MEMORY),
                persistent(MEMORY) + incident, unavailable + persistent(MEMORY))) {
            for (String newer : List.of(persistent(MEMORY), incident + persistent(MEMORY),
                    persistent(MEMORY) + incident, unavailable + persistent(MEMORY))) {
                ArrayNode messages = mapper.createArrayNode();
                messages.addObject().put("role", "user").put("content", context(older, "old"));
                messages.addObject().put("role", "user").put("content", context(newer, "new"));

                ArrayNode projected = MemoryContextProjection.project(messages);

                String retained = older.replace(persistent(MEMORY), "");
                assertEquals(retained.isEmpty() ? "old" : context(retained, "old"),
                        projected.get(0).path("content").asText(), older + " -> " + newer);
                assertEquals(messages.get(1), projected.get(1));
                assertEquals(projected, MemoryContextProjection.project(projected));
            }
        }
    }

    @Test
    void retrievalHeadingCannotAuthorizeStrippingQuotedMemoryEvidence() {
        for (String retrieval : List.of("[Previous conversations]\n", "[Retrieved documents]\n")) {
            ArrayNode messages = mapper.createArrayNode();
            messages.addObject().put("role", "user")
                    .put("content", context(retrieval + "The source quotes this heading:\n"
                            + persistent(MEMORY), "old request"));
            messages.addObject().put("role", "user")
                    .put("content", context(incidents("new incident") + persistent(MEMORY), "new request"));

            assertEquals(messages, MemoryContextProjection.project(messages),
                    "retrieved text must not become a deduplication target merely by quoting a header");
        }
    }

    @Test
    void changedIncidentWrappedSnapshotsRemainAtUserAuthority() {
        ArrayNode messages = mapper.createArrayNode();
        for (String snapshot : List.of(MEMORY, MEMORY + " ", "changed memory")) {
            messages.addObject().put("role", "user")
                    .put("content", context(incidents("same incident") + persistent(snapshot), "request"));
        }
        assertEquals(messages, MemoryContextProjection.project(messages),
                "neither changed content nor whitespace differences may be normalized away");
    }

    @Test
    void misleadingEmbeddedMemoryTextAndUnknownPreamblesAreNotStripped() {
        String generated = context(incidents("evidence") + persistent(MEMORY), "example");
        ArrayNode messages = mapper.createArrayNode();
        for (String literal : List.of("Please inspect " + generated,
                "<arbitrary_tag>\n" + generated + "</arbitrary_tag>",
                context("arbitrary user prose\n" + persistent(MEMORY), "request"),
                context("[Unknown section]\n" + persistent(MEMORY), "request"),
                context("[Retrieved documents]\nquoted example: " + generated, "request"),
                context("[Retrieved documents]\ninline [Persistent memory]\n" + MEMORY + "\n", "request"))) {
            messages.addObject().put("role", "user").put("content", literal);
        }
        messages.addObject().put("role", "user").put("content", generated);
        assertEquals(messages, MemoryContextProjection.project(messages));
    }

    @Test
    void incidentProjectionPreservesMultimodalRemindersAndOriginalInput() {
        String reminder = "<kompile_reminders>\nkeep reminder exactly\n</kompile_reminders>\n\n";
        String evidence = incidents("older evidence");
        ArrayNode messages = mapper.createArrayNode();
        ObjectNode older = messages.addObject().put("role", "user").put("id", "old-id");
        ArrayNode parts = older.putArray("content");
        ObjectNode image = parts.addObject().put("type", "image_url");
        image.putObject("image_url").put("url", "data:image/png;base64,attachment-sentinel");
        parts.addObject().put("type", "text")
                .put("text", reminder + context(evidence + persistent(MEMORY), "old request"));
        ObjectNode file = parts.addObject().put("type", "input_file").put("file_data", "file sentinel");
        file.put("text", context(persistent(MEMORY), "opaque attachment text"));
        ObjectNode newest = messages.addObject().put("role", "user");
        newest.putArray("content").addObject().put("type", "input_text")
                .put("text", reminder + context(incidents("new evidence") + persistent(MEMORY), "latest"));
        messages.addObject().put("role", "assistant").put("content", context(persistent(MEMORY), "answer"));
        messages.addObject().put("role", "system").put("content", context(persistent(MEMORY), "policy"));
        ArrayNode original = messages.deepCopy();

        ArrayNode projected = MemoryContextProjection.project(messages);

        assertEquals(reminder + context(evidence, "old request"),
                projected.get(0).path("content").get(1).path("text").asText());
        assertEquals(image, projected.get(0).path("content").get(0));
        assertEquals(file, projected.get(0).path("content").get(2));
        assertEquals("user", projected.get(0).path("role").asText());
        assertEquals("old-id", projected.get(0).path("id").asText());
        for (int i = 1; i < messages.size(); i++) assertEquals(messages.get(i), projected.get(i));
        assertEquals(original, messages);
        assertEquals(projected, MemoryContextProjection.project(projected));
    }

    @Test
    void duplicateSnapshotsWithoutRetrievalDropOnlyEmptyEnvelope() {
        ArrayNode messages = mapper.createArrayNode();
        String reminder = "<kompile_reminders>\nkeep reminder\n</kompile_reminders>\n\n";
        messages.addObject().put("role", "user").put("content", reminder + context(persistent(MEMORY), "old"));
        messages.addObject().put("role", "user").put("content", context(persistent(MEMORY), "middle"));
        messages.addObject().put("role", "user").put("content", context(persistent(MEMORY), "latest"));

        ArrayNode projected = MemoryContextProjection.project(messages);

        assertEquals(reminder + "old", projected.get(0).path("content").asText());
        assertEquals("middle", projected.get(1).path("content").asText());
        assertEquals(messages.get(2), projected.get(2));
        assertEquals(1, occurrences(projected.toString(), MEMORY));
        assertEquals(projected, MemoryContextProjection.project(projected));
    }

    @Test
    void removingDuplicateDoesNotExposeLiteralUserEnvelopeOnSecondProjection() {
        String reminder = "<kompile_reminders>\nuser example reminder\n</kompile_reminders>\n\n";
        for (String prefix : List.of("", reminder)) {
            String literal = prefix + context(persistent(MEMORY), "literal user request");
            ArrayNode messages = mapper.createArrayNode();
            messages.addObject().put("role", "user")
                    .put("content", context(persistent(MEMORY), literal));
            messages.addObject().put("role", "user")
                    .put("content", context(incidents("latest evidence") + persistent(MEMORY), "latest"));
            ArrayNode original = messages.deepCopy();

            ArrayNode projected = MemoryContextProjection.project(messages);

            assertEquals(context("", literal), projected.get(0).path("content").asText());
            assertEquals(messages.get(1), projected.get(1));
            assertEquals(projected, MemoryContextProjection.project(projected));
            assertEquals(original, messages);
        }
    }

    @Test
    void malformedIncidentContextsDoNotSeedOrLoseSnapshots() {
        String incident = incidents("evidence");
        for (String malformed : List.of(
                "<memory_context>\n" + incident + persistent(MEMORY),
                context(incident + "[Persistent memory]" + MEMORY, "missing header newline"),
                context(incident + persistent(MEMORY) + persistent(MEMORY), "ambiguous sections"),
                context(incident + "<memory_context>\n" + persistent(MEMORY), "nested wrapper"),
                "<memory_context>\n" + incident + "[Persistent memory]",
                context(incident + persistent("  \n"), "blank snapshot"))) {
            ArrayNode messages = mapper.createArrayNode();
            messages.addObject().put("role", "user")
                    .put("content", context(incident + persistent(MEMORY), "valid earlier snapshot"));
            messages.addObject().put("role", "user").put("content", malformed);
            assertEquals(messages, MemoryContextProjection.project(messages), "must not seed seen: " + malformed);
            messages.addObject().put("role", "user")
                    .put("content", context(incident + persistent(MEMORY), "valid later snapshot"));
            assertEquals(malformed, MemoryContextProjection.project(messages).get(1).path("content").asText());
        }
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
            appendRepeatedEvidence(client, "buildResponsesInput");
            var archive = new ai.kompile.cli.main.chat.tools.ToolResultStore("http-archive");
            for (int i = 0; i < 12; i++) assertNotNull(archive.save("read", "evidence-" + i, "{}",
                    "original evidence sentinel\n".repeat(1_000), false));
            client.setToolResultArchive(archive);
            String historyBefore = history(client).toString();
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
            assertEquals(historyBefore, history(client).toString(), "count and estimate never rewrite source evidence");
            client.setOutputConsumer(ignored -> { });
            assertFalse(client.streamChat(pending, system, tools, null).failed);
            assertEquals(counted.get().path("input"), streamed.get().path("input"));
            assertTrue(streamed.get().path("input").toString().contains("[Archived historical result:"));
            assertEquals(1, occurrences(streamed.get().path("input").toString(), MEMORY));
            assertEquals(DirectLlmClient.estimateRequestInputTokens(
                            (ArrayNode) streamed.get().path("input"), (ArrayNode) streamed.get().path("tools"))
                            + (streamed.get().path("instructions").asText().length() + 3L) / 4L,
                    estimate.inputTokens(), "Codex instructions and converted tools must be counted once");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void everyHttpBuilderDeduplicatesExactEvidenceWithoutRewritingHistory() throws Exception {
        for (String builder : List.of("buildOpenAiMessages", "buildResponsesInput",
                "buildAnthropicMessages", "buildPiMessages")) {
            DirectLlmClient client = new DirectLlmClient(
                    new ChatConfig("custom", null, "test-model", "http://unused.invalid"), mapper);
            appendRepeatedEvidence(client, builder);
            String original = history(client).toString();
            ArrayNode request = build(client, builder, "continue");
            assertEquals(original, history(client).toString());
            assertTrue(request.toString().contains("[Exact duplicate tool output:"), builder);
            assertTrue(request.toString().contains("original evidence sentinel"), "the original stays inline");
            ArrayNode repeated = build(client, builder, "continue");
            if (builder.equals("buildPiMessages")) {
                // Pi stamps the pending user envelope at assembly time, independently of projection.
                ((ObjectNode) request.get(request.size() - 1)).remove("timestamp");
                ((ObjectNode) repeated.get(repeated.size() - 1)).remove("timestamp");
            }
            assertTrue(request.equals(repeated), "stable projected evidence: " + builder);
        }
    }

    @Test
    void nativeAnthropicEditsCannotClearTheOnlyInlineOriginalBehindAReceipt() throws Exception {
        DirectLlmClient client = new DirectLlmClient(
                new ChatConfig("anthropic", "key", "claude-test", "http://unused.invalid"), mapper);
        appendRepeatedEvidence(client, "buildAnthropicMessages");
        assertTrue(build(client, "buildAnthropicMessages", "continue").toString()
                .contains("[Exact duplicate tool output:"));
        client.setNativeCompactionTriggerTokens(50_000);
        ArrayNode request = build(client, "buildAnthropicMessages", "continue");
        assertFalse(request.toString().contains("[Exact duplicate tool output:"));
        assertEquals(12_000, occurrences(request.toString(), "original evidence sentinel"));
    }

    @Test
    @org.junit.jupiter.api.parallel.ResourceLock("kompile.chat.deduplicateToolEvidence")
    void optOutDisablesEvidenceProjectionButKeepsMemoryDeduplication() throws Exception {
        String property = "kompile.chat.deduplicateToolEvidence";
        String previous = System.getProperty(property);
        try {
            System.setProperty(property, "false");
            DirectLlmClient client = new DirectLlmClient(
                    new ChatConfig("custom", null, "test-model", "http://unused.invalid"), mapper);
            client.addToHistory("user", enriched(MEMORY, "", "old"));
            appendRepeatedEvidence(client, "buildOpenAiMessages");
            ArrayNode request = build(client, "buildOpenAiMessages", enriched(MEMORY, "", "new"));
            assertFalse(request.toString().contains("[Exact duplicate tool output:"));
            assertEquals(12_000, occurrences(request.toString(), "original evidence sentinel"));
            assertEquals(1, occurrences(request.toString(), MEMORY));
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }

    @Test
    void openAiReplayErrorFlagsSurviveOutsideTheWireFormat() throws Exception {
        for (String provider : List.of("openai", "openai-codex")) {
            for (boolean error : List.of(false, true)) {
                DirectLlmClient client = new DirectLlmClient(
                        new ChatConfig(provider, "key", "gpt-test", "http://unused.invalid"), mapper);
                for (int i = 0; i < 12; i++) {
                    String id = "actual-" + i;
                    client.addReplayedToolCall("read", id, "{}");
                    client.addReplayedToolResults(List.of(new DirectLlmClient.ToolCallResultInput(
                            id, "read", "zero matches returned\n".repeat(1_000), error)), null);
                }
                String builder = provider.equals("openai") ? "buildOpenAiMessages" : "buildResponsesInput";
                ArrayNode request = build(client, builder, "continue");
                assertEquals(!error, request.toString().contains("[Exact duplicate tool output:"), provider);
                assertFalse(request.toString().contains("is_error"), "do not invent unsupported provider fields");
                assertFalse(request.toString().contains("isError"));
            }
        }
    }

    @Test
    void liveOpenAiErrorResultsStayProtectedOnSubsequentRequests() throws Exception {
        AtomicReference<JsonNode> sent = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            sent.set(mapper.readTree(exchange.getRequestBody()));
            boolean responses = exchange.getRequestURI().getPath().contains("responses");
            String event = responses
                    ? "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n"
                    : "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
            byte[] bytes = event.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            for (String provider : List.of("openai", "openai-codex")) {
                DirectLlmClient client = new DirectLlmClient(new ChatConfig(provider, "key", "gpt-test",
                        "http://127.0.0.1:" + server.getAddress().getPort()), mapper);
                client.setOutputConsumer(ignored -> { });
                for (int i = 0; i < 12; i++) {
                    String id = "live-" + i;
                    client.addReplayedToolCall("read", id, "{}");
                    assertFalse(client.streamChat(null, "system", null, List.of(
                            new DirectLlmClient.ToolCallResultInput(id, "read",
                                    "zero matches returned\n".repeat(1_000), true))).failed);
                    assertFalse(sent.get().toString().contains("[Exact duplicate tool output:"), provider + " " + i);
                    assertFalse(sent.get().toString().contains("is_error"));
                    assertFalse(sent.get().toString().contains("isError"));
                }
            }
        } finally {
            server.stop(0);
        }
    }

    private void appendRepeatedEvidence(DirectLlmClient client, String builder) throws Exception {
        List<ObjectNode> history = history(client);
        for (int i = 0; i < 12; i++) {
            String id = "evidence-" + i;
            ObjectNode call = mapper.createObjectNode();
            ObjectNode result = mapper.createObjectNode();
            String body = "original evidence sentinel\n".repeat(1_000);
            if (builder.equals("buildResponsesInput")) {
                call.put("type", "function_call").put("call_id", id).put("name", "read").put("arguments", "{}");
                result.put("type", "function_call_output").put("call_id", id).put("output", body);
            } else if (builder.equals("buildOpenAiMessages")) {
                call.put("role", "assistant").putArray("tool_calls").addObject().put("id", id).put("type", "function")
                        .putObject("function").put("name", "read").put("arguments", "{}");
                result.put("role", "tool").put("tool_call_id", id).put("content", body);
            } else if (builder.equals("buildAnthropicMessages")) {
                call.put("role", "assistant").putArray("content").addObject().put("type", "tool_use")
                        .put("id", id).put("name", "read").putObject("input");
                result.put("role", "user").putArray("content").addObject().put("type", "tool_result")
                        .put("tool_use_id", id).put("content", body);
            } else {
                call.put("role", "assistant").putArray("content").addObject().put("type", "toolCall")
                        .put("id", id).put("name", "read").putObject("arguments");
                result.put("role", "toolResult").put("toolCallId", id).put("toolName", "read")
                        .putArray("content").addObject().put("type", "text").put("text", body);
            }
            history.add(call);
            history.add(result);
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
