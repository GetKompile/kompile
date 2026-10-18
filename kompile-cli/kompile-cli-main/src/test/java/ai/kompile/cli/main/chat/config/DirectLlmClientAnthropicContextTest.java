package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.agent.AgenticChatLoop;
import ai.kompile.cli.main.chat.context.ConversationLedger;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.CompactionService;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@TemporaryUserHome
class DirectLlmClientAnthropicContextTest {
    private static final ObjectMapper MAPPER = ai.kompile.cli.common.util.JsonUtils.standardMapper();
    private static final String BLOCK = "{\"type\":\"compaction\",\"content\":\"Keep active constraints and pending work.\",\"signature\":\"signed-exact-value\"}";
    @TempDir Path directory;

    @Test
    void signedSummaryIsTransactionalAndReplayedUnchangedWithRequiredHeader() throws Exception {
        try (Fixture f = new Fixture(); DirectLlmClient client = f.client()) {
            client.addToHistory("user", "old user request");
            client.addToHistory("assistant", "old answer");
            String original = wire(client);
            var result = client.tryNativeCompact(null, "exact system", tools(), "focus on the failing build");
            assertTrue(result.applied(), result.diagnostic());
            assertEquals(original, wire(client), "native summary cannot publish before ledger commit");
            JsonNode sent = f.requests.get(0);
            assertEquals("exact system", sent.path("system").asText());
            assertEquals("read", sent.path("tools").get(0).path("name").asText());
            assertTrue(sent.path("compaction").path("instructions").asText().contains("focus on the failing build"));
            assertFalse(sent.has("context_management"));
            assertFalse(sent.has("tool_choice"));
            assertEquals(3, result.usage().compactionInputTokens);
            assertEquals(2, result.usage().compactionOutputTokens);
            assertEquals(MAPPER.readTree(BLOCK), result.nativePayload().path("content").get(0));

            client.replaceHistoryWithNativeCheckpoint(result.nativePayload());
            client.setNativeCompactionTriggerTokens(60_000);
            assertTrue(client.countInputTokens("continue", "exact system", tools(), null, null).exact());
            assertFalse(client.streamChat("continue", "exact system", tools(), null).failed);
            for (int i = 1; i < f.requests.size(); i++) {
                JsonNode request = f.requests.get(i);
                assertEquals(MAPPER.readTree(BLOCK), request.path("messages").get(0).path("content").get(0));
                assertFalse(request.path("messages").toString().contains("old user request"));
                assertTrue(f.betas.get(i).contains("compact-2026-09-04"));
                assertFalse(request.path("context_management").toString().contains("compact_20260112"));
            }
            assertEquals(f.requests.get(1).path("context_management"), f.requests.get(2).path("context_management"),
                    "count and generation must use the same context policy");
            assertEquals(1, f.probes.get(), "capability evidence is cached within endpoint/model/credentials");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"max_tokens", "missing-signature", "oversized", "multiple-blocks"})
    void incompleteOrInvalidSummaryNeverChangesHistory(String failure) throws Exception {
        try (Fixture f = new Fixture(); DirectLlmClient client = f.client()) {
            f.failure = failure;
            client.addToHistory("user", "retain original constraints");
            String original = wire(client);
            var result = client.tryNativeCompact(null, "system", tools(), null);
            assertFalse(result.applied());
            assertEquals(original, wire(client));
            assertNotNull(result.usage(), "even rejected HTTP 200 summaries retain billed usage");
        }
    }

    @Test
    void cancellationAfterSummaryAndUnansweredToolCallsAreNotPublished() throws Exception {
        try (Fixture f = new Fixture(); DirectLlmClient client = f.client()) {
            AtomicBoolean cancelled = new AtomicBoolean();
            client.setCancelSignal(cancelled);
            client.addToHistory("user", "original request");
            f.cancelOnSummary = cancelled;
            String original = wire(client);
            assertFalse(client.tryNativeCompact(null, "system", tools(), null).applied());
            assertEquals(original, wire(client));
            cancelled.set(false);
            client.addReplayedToolCall("read", "pending-call", "{}");
            int before = f.requests.size();
            assertFalse(client.tryNativeCompact(null, "system", tools(), null).applied());
            assertEquals(before, f.requests.size(), "do not submit an incomplete tool exchange to the summarizer");
        }
    }

    @Test
    void clearingPreservesRawBodiesAndFallsBackOnceWhenEndpointRejectsIt() throws Exception {
        try (Fixture f = new Fixture(); DirectLlmClient client = f.client()) {
            client.addToHistory("user", "request");
            client.addReplayedToolCall("read", "call-id", "{\"path\":\"file.txt\"}");
            client.addReplayedToolResult("read", "call-id", "raw-body".repeat(1_000));
            String original = wire(client);
            client.setNativeCompactionTriggerTokens(60_000);
            assertTrue(client.countInputTokens("next", "system", tools(), null, null).exact());
            assertEquals(original, wire(client), "context editing is server-side, not ledger/body deletion");
            JsonNode edit = f.requests.get(0).path("context_management").path("edits").get(0);
            assertEquals("clear_tool_uses_20250919", edit.path("type").asText());
            assertEquals(8, edit.path("keep").path("value").asInt());
            assertTrue(edit.path("clear_at_least").path("value").asInt() > 0);
            assertTrue(f.requests.get(0).path("messages").toString().contains("call-id"));
            f.rejectClearing = true;
            assertFalse(client.streamChat("next", "system", tools(), null).failed);
            assertEquals(3, f.requests.size());
            assertTrue(f.requests.get(1).has("context_management"));
            assertFalse(f.requests.get(2).has("context_management"));
            assertTrue(wire(client).contains("raw-body"));
        }
    }

    @Test
    void unknownCapabilityDoesNotInventBetaSupportOrAffectClaudeCode() throws Exception {
        try (Fixture f = new Fixture(); DirectLlmClient client = f.client()) {
            f.metadataStatus = 404;
            client.addToHistory("user", "request");
            assertFalse(client.supportsAnthropicOnDemandCompaction(null));
            assertFalse(client.tryNativeCompact(null, "system", tools(), null).supported());
            assertEquals(0, f.requests.size());
            ChatConfig nativeConfig = new ChatConfig("anthropic", null, "claude-test", f.url());
            nativeConfig.setAuthenticationMethod("native");
            try (DirectLlmClient nativeClient = new DirectLlmClient(nativeConfig, MAPPER)) {
                assertFalse(nativeClient.supportsAnthropicOnDemandCompaction(null));
                assertFalse(nativeClient.tryNativeCompact(null, "system", tools(), null).supported());
            }
            assertEquals(1, f.probes.get());
        }
    }

    @Test
    void validationErrorsDoNotPoisonCapabilityEvidence() throws Exception {
        try (Fixture f = new Fixture(); DirectLlmClient client = f.client()) {
            client.addToHistory("user", "constraints");
            f.failure = "validation-error";
            assertFalse(client.tryNativeCompact(null, "system", tools(), null).applied());
            assertTrue(client.supportsAnthropicOnDemandCompaction(null));
            f.failure = null;
            assertTrue(client.tryNativeCompact(null, "system", tools(), null).applied());
            assertEquals(1, f.probes.get());
        }
    }

    @Test
    void unsupportedOnDemandAfterSignedCheckpointCanFallBackPortably() throws Exception {
        try (Fixture f = new Fixture(); DirectLlmClient client = f.client()) {
            client.getChatConfig().setContextWindowTokens(128_000);
            AgenticChatLoop loop = loop(client);
            String session = "downgrade-signed-" + UUID.randomUUID();
            loop.configureConversationSession(session);
            ConversationLedger ledger = ledger(loop);
            ledger.append(CompactionService.ConversationEntry.user("u".repeat(8_000)));
            ledger.append(CompactionService.ConversationEntry.assistant("a".repeat(8_000)));
            loop.rebuildDirectHistoryForProviderSwitch();
            assertTrue(loop.forceCompact(null).isSuccess());
            ledger.append(CompactionService.ConversationEntry.user("u".repeat(80_000)));
            ledger.append(CompactionService.ConversationEntry.assistant("a".repeat(80_000)));
            loop.rebuildDirectHistoryForProviderSwitch();
            f.failure = "unsupported";
            assertFalse(client.tryNativeCompact(null, "system", tools(), null).applied());
            assertFalse(client.supportsAnthropicOnDemandCompaction(null));
            assertFalse(client.anthropicThresholdCompactionAvailable(null), "signed blocks cannot use legacy threshold compaction");
            f.failure = null;
            f.inputTokens = 120_000;
            assertEquals("answer", loop.chat("resume", session, "coder", "default", false));
            assertNull(ledger.snapshot().checkpoint().nativePayload(), "fallback must publish portable context");
            assertFalse(wire(client).contains("signed-exact-value"));
            assertEquals(6, ledger.snapshot().allEvents().size());
        }
    }

    @Test
    void ledgerRestartRestoresSignedPayloadAndProviderSwitchUsesPortableSummary() throws Exception {
        try (Fixture f = new Fixture(); DirectLlmClient client = f.client()) {
            String session = "signed-context-" + UUID.randomUUID();
            AgenticChatLoop loop = loop(client);
            loop.configureConversationSession(session);
            Field field = AgenticChatLoop.class.getDeclaredField("conversationLedger");
            field.setAccessible(true);
            ConversationLedger ledger = (ConversationLedger) field.get(loop);
            ledger.append(CompactionService.ConversationEntry.user("Original constraints " + "u".repeat(8_000)));
            ledger.append(CompactionService.ConversationEntry.assistant("Earlier work " + "a".repeat(8_000)));
            loop.rebuildDirectHistoryForProviderSwitch();
            assertTrue(loop.forceCompact("keep unresolved work").isSuccess());
            assertEquals(2, ledger.snapshot().allEvents().size());
            assertNotNull(ledger.snapshot().checkpoint().nativePayload());
            try (DirectLlmClient restarted = f.client()) {
                AgenticChatLoop restored = loop(restarted);
                restored.configureConversationSession(session);
                restored.rebuildDirectHistoryForProviderSwitch();
                assertTrue(wire(restarted).contains("signed-exact-value"));
                assertFalse(restarted.streamChat("continue", "system", tools(), null).failed);
                assertTrue(f.betas.get(f.betas.size() - 1).contains("compact-2026-09-04"));
            }
            try (DirectLlmClient other = new DirectLlmClient(new ChatConfig("custom", null, "other", f.url()), MAPPER)) {
                AgenticChatLoop restored = loop(other);
                restored.configureConversationSession(session);
                restored.rebuildDirectHistoryForProviderSwitch();
                assertFalse(wire(other).contains("signed-exact-value"));
                assertTrue(wire(other).contains("Keep active constraints and pending work."));
            }
            ChatConfig claudeCode = new ChatConfig("anthropic", null, "claude-test", f.url());
            claudeCode.setAuthenticationMethod("native");
            try (DirectLlmClient nativeClient = new DirectLlmClient(claudeCode, MAPPER)) {
                AgenticChatLoop restored = loop(nativeClient);
                restored.configureConversationSession(session);
                restored.rebuildDirectHistoryForProviderSwitch();
                assertFalse(wire(nativeClient).contains("signed-exact-value"));
                assertTrue(wire(nativeClient).contains("Keep active constraints and pending work."),
                        "same-vendor/model API-to-Claude-Code switch must retain portable context");
            }
        }
    }

    @Test
    void automaticCompactionUsesSignedCheckpointAndKeepsPendingRequestExactlyOnce() throws Exception {
        try (Fixture f = new Fixture(); DirectLlmClient client = f.client()) {
            client.getChatConfig().setContextWindowTokens(128_000);
            f.inputTokens = 120_000;
            String session = "auto-signed-" + UUID.randomUUID();
            AgenticChatLoop loop = loop(client);
            loop.configureConversationSession(session);
            ConversationLedger ledger = ledger(loop);
            for (int i = 0; i < 3; i++) {
                ledger.append(CompactionService.ConversationEntry.user("old-" + i + "u".repeat(80_000)));
                ledger.append(CompactionService.ConversationEntry.assistant("a".repeat(80_000)));
            }
            loop.rebuildDirectHistoryForProviderSwitch();
            assertEquals("answer", loop.chat("pending-user-message", session, "coder", "default", false));
            assertNotNull(ledger.snapshot().checkpoint().nativePayload());
            assertEquals(6, ledger.snapshot().checkpoint().coveredThroughSequence());
            assertEquals(8, ledger.snapshot().allEvents().size());
            JsonNode summaryRequest = f.requests.stream().filter(r -> r.has("compaction")).findFirst().orElseThrow();
            assertFalse(summaryRequest.path("messages").toString().contains("pending-user-message"));
            JsonNode next = f.requests.get(f.requests.size() - 1);
            assertEquals(MAPPER.readTree(BLOCK), next.path("messages").get(0).path("content").get(0));
            assertEquals(1, next.path("messages").findValuesAsText("text").stream()
                    .filter(t -> t.contains("pending-user-message")).count());
        }
    }

    @Test
    void staleLedgerVersionRejectsSignedSummaryWithoutLosingNewEvents() throws Exception {
        try (Fixture f = new Fixture(); DirectLlmClient client = f.client()) {
            AgenticChatLoop loop = loop(client);
            loop.configureConversationSession("stale-signed-" + UUID.randomUUID());
            ConversationLedger ledger = ledger(loop);
            ledger.append(CompactionService.ConversationEntry.user("old request " + "u".repeat(8_000)));
            ledger.append(CompactionService.ConversationEntry.assistant("old answer " + "a".repeat(8_000)));
            loop.rebuildDirectHistoryForProviderSwitch();
            f.onSummary = () -> ledger.append(CompactionService.ConversationEntry.user("late user correction"));
            assertFalse(loop.forceCompact(null).isSuccess());
            assertNull(ledger.snapshot().checkpoint());
            assertEquals(3, ledger.snapshot().allEvents().size());
            assertTrue(wire(client).contains("late user correction"));
            assertFalse(wire(client).contains("signed-exact-value"));
        }
    }

    private static ConversationLedger ledger(AgenticChatLoop loop) throws Exception {
        Field field = AgenticChatLoop.class.getDeclaredField("conversationLedger");
        field.setAccessible(true);
        return (ConversationLedger) field.get(loop);
    }

    private AgenticChatLoop loop(DirectLlmClient client) {
        return new AgenticChatLoop(null, MAPPER, new ToolRegistry(MAPPER), new PermissionService(),
                new AgentRegistry(), directory, client, null);
    }

    private static String wire(DirectLlmClient client) throws Exception {
        Field field = DirectLlmClient.class.getDeclaredField("conversationHistory");
        field.setAccessible(true);
        return field.get(client).toString();
    }

    private static ArrayNode tools() {
        ArrayNode tools = MAPPER.createArrayNode();
        tools.addObject().put("name", "read").put("description", "read a file")
                .putObject("inputSchema").put("type", "object");
        return tools;
    }

    private static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger probes = new AtomicInteger();
        final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        final List<String> betas = new CopyOnWriteArrayList<>();
        volatile int metadataStatus = 200;
        volatile long inputTokens = 1000;
        volatile Runnable onSummary;
        volatile String failure;
        volatile boolean rejectClearing;
        volatile AtomicBoolean cancelOnSummary;

        Fixture() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        DirectLlmClient client() {
            DirectLlmClient client = new DirectLlmClient(new ChatConfig("anthropic", "test-key", "claude-test", url()), MAPPER);
            client.setOutputConsumer(ignored -> { });
            return client;
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/v1/models/")) {
                probes.incrementAndGet();
                send(exchange, metadataStatus, "application/json", "{\"capabilities\":{\"compaction\":{\"supported\":true,\"summarize\":{\"supported\":true}},"
                        + "\"context_management\":{\"clear_tool_uses_20250919\":{\"supported\":true}}}}");
                return;
            }
            JsonNode request = MAPPER.readTree(exchange.getRequestBody());
            requests.add(request);
            betas.add(exchange.getRequestHeaders().getFirst("anthropic-beta") == null ? ""
                    : exchange.getRequestHeaders().getFirst("anthropic-beta"));
            if (path.endsWith("count_tokens")) {
                send(exchange, 200, "application/json", "{\"input_tokens\":" + inputTokens + "}");
            } else if (request.has("compaction")) {
                if ("validation-error".equals(failure) || "unsupported".equals(failure)) {
                    send(exchange, 400, "application/json", "{\"error\":{\"message\":\""
                            + ("validation-error".equals(failure) ? "compaction.instructions is invalid" : "compaction is not supported")
                            + "\"}}");
                    return;
                }
                String block = BLOCK;
                if ("missing-signature".equals(failure)) block = "{\"type\":\"compaction\",\"content\":\"unsigned\"}";
                if ("oversized".equals(failure)) block = "{\"type\":\"compaction\",\"content\":\"" + "x".repeat(12_001) + "\",\"signature\":\"sig\"}";
                if ("multiple-blocks".equals(failure)) block += "," + BLOCK;
                if (cancelOnSummary != null) cancelOnSummary.set(true);
                if (onSummary != null) onSummary.run();
                send(exchange, 200, "application/json", "{\"content\":[" + block + "],\"stop_reason\":\""
                        + ("max_tokens".equals(failure) ? "max_tokens" : "compaction")
                        + "\",\"usage\":{\"iterations\":[{\"type\":\"compaction\",\"input_tokens\":3,\"output_tokens\":2}]}}");
            } else if (rejectClearing && request.path("context_management").toString().contains("clear_tool_uses_20250919")) {
                send(exchange, 400, "application/json", "{\"error\":{\"message\":\"unsupported clear_tool_uses_20250919\"}}");
            } else {
                send(exchange, 200, "text/event-stream", "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"answer\"}}\n\n"
                        + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\n"
                        + "data: {\"type\":\"message_stop\"}\n\n");
            }
        }
        static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", type);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
        @Override public void close() { server.stop(0); }
    }
}
