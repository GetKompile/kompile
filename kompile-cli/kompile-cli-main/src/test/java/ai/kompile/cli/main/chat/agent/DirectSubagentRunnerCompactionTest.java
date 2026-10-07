package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compaction for direct task subagents, mirroring main chat's model-aware
 * two-tier pipeline at child scale:
 *
 * <ul>
 *   <li><b>Overflow recovery</b> (auto-compact off): the provider rejects the
 *       third exchange's request as too long. The runner summarizes the older
 *       exchange, replays a compacted history, and retries the same message
 *       exactly once — without re-running any tool.</li>
 *   <li><b>Preventive compaction</b> (auto-compact on): before the fourth
 *       exchange's request crosses the trigger, the runner summarizes the
 *       oldest exchange at a complete-exchange boundary; the retained child
 *       stays reusable and recent exchanges stay verbatim.</li>
 * </ul>
 *
 * The fixtures use a tiny synthetic window (2,048; trigger 1,024; the
 * recent-exchange preserve span is 682 estimated tokens). Marker pads are
 * sized so the boundary planner keeps the newest exchange verbatim and only
 * the older exchange is summarized away.
 */
@TemporaryUserHome
class DirectSubagentRunnerCompactionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SUMMARY_PHRASE =
            "Summarize the following conversation transcript";
    private static final String SUMMARY_MARKER = "[Conversation summary]";
    private static final String SECOND_TASK_PAD = "b".repeat(1_000);
    private static final String OVERFLOW_PROMPT = "Third run that overflows the window";

    private static final String TOOL_CALL_CHUNK =
            "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\","
                    + "\"type\":\"function\",\"function\":{\"name\":\"compaction_probe\","
                    + "\"arguments\":\"{\\\"n\\\":1}\"}}]},\"finish_reason\":\"tool_calls\"}]}";

    /**
     * Returns a distinct padded marker per call ("marker-1", "marker-2", …) so
     * each exchange's tool result is identifiable in the wire requests.
     */
    private static final class ProbeTool implements CliTool {
        final AtomicInteger calls = new AtomicInteger();
        private final String padding;

        ProbeTool(int padLength) {
            this("p".repeat(padLength));
        }

        ProbeTool(String padding) {
            this.padding = padding;
        }

        String pad() {
            return padding;
        }

        @Override public String id() { return "compaction_probe"; }
        @Override public String description() { return "Probe tool"; }
        @Override public String permissionKey() { return "read"; }

        @Override
        public JsonNode parameterSchema() {
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("type", "object");
            schema.putObject("properties");
            return schema;
        }

        @Override
        public ToolResult execute(JsonNode params, ToolContext context) {
            return ToolResult.success("marker-" + calls.incrementAndGet() + " " + pad());
        }
    }

    private static String sse(String chunk) {
        return "data: " + chunk + "\n\ndata: [DONE]\n\n";
    }

    private static String textChunk(String content) {
        return "{\"choices\":[{\"delta\":{\"content\":\"" + content + "\"},"
                + "\"finish_reason\":\"stop\"}]}";
    }

    private static void respondSse(HttpExchange exchange, String chunk) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().write(sse(chunk).getBytes(StandardCharsets.UTF_8));
        exchange.close();
    }

    private static void respondOverflow(HttpExchange exchange) throws IOException {
        byte[] body = ("{\"error\":{\"message\":\"This model's maximum context length is 2048 "
                + "tokens, however you requested... context_length_exceeded\"}}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(400, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static boolean isSummaryRequest(String requestBody) {
        return requestBody.contains(SUMMARY_PHRASE);
    }

    /** Role of the last message in a wire request. */
    private static String lastRole(String requestBody) {
        try {
            JsonNode messages = MAPPER.readTree(requestBody).path("messages");
            if (!messages.isArray() || messages.isEmpty()) return "other";
            return messages.get(messages.size() - 1).path("role").asText("other");
        } catch (Exception e) {
            return "other";
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    @Test
    @Timeout(60)
    void overflowRecoveryCompactsOldExchangesAndRetriesOnce() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        AtomicInteger requestCount = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(requestBody);
            requestCount.incrementAndGet();
            if (isSummaryRequest(requestBody)) {
                respondSse(exchange, textChunk("summary-of-the-oldest-run"));
                return;
            }
            if (requestBody.contains(SUMMARY_MARKER)) {
                // The retried request rides the compacted history.
                respondSse(exchange, textChunk("completed-after-compaction"));
                return;
            }
            if ("tool".equals(lastRole(requestBody))) {
                respondSse(exchange, textChunk("completed-run"));
                return;
            }
            if (requestBody.contains(OVERFLOW_PROMPT)) {
                // Only the third exchange's request is rejected.
                respondOverflow(exchange);
                return;
            }
            respondSse(exchange, TOOL_CALL_CHUNK);
        });
        server.start();
        try {
            PermissionService permissions = new PermissionService();
            permissions.allowAll();
            ToolRegistry tools = new ToolRegistry(MAPPER);
            ProbeTool probe = new ProbeTool(2_000);
            tools.register(probe);
            DirectSubagentRunner runner = runner(
                    config(2_048, server.getAddress().getPort(), false), tools, permissions);
            ToolContext parent = new ToolContext("compaction-parent",
                    AgentConfig.builder("parent").build(), permissions,
                    Path.of("."), tools);
            AtomicReference<String> childId = new AtomicReference<>();
            runner.setLifecycleListener(new SubagentRunner.LifecycleListener() {
                @Override public void onSubagentStart(String id, String type, String description) {
                    childId.compareAndSet(null, id);
                }
                @Override public void onSubagentEnd(String id) { }
            });

            String first = runner.runSubagent(
                    AgentConfig.builder("explore").isSubagent(true)
                            .systemPrompt("Child system prompt").build(),
                    "First task with a large tool result", parent);
            assertTrue(first.contains("completed-run"), () -> "first run ended: " + first);
            assertEquals(1, probe.calls.get());
            assertNotNull(childId.get());
            assertTrue(runner.canSendMessage(childId.get()));

            // A second large exchange on the same retained child: this is the
            // exchange Tier 2 must preserve verbatim when the third one overflows.
            AtomicReference<String> secondResult = new AtomicReference<>();
            CountDownLatch secondDone = new CountDownLatch(1);
            runner.setAsyncCompletionListener((id, completionResult) -> {
                secondResult.set(completionResult);
                secondDone.countDown();
            });
            assertTrue(runner.sendMessage(childId.get(),
                    "Second task: " + SECOND_TASK_PAD));
            assertTrue(secondDone.await(20, TimeUnit.SECONDS),
                    () -> "second run: " + secondResult.get());
            assertEquals(2, probe.calls.get());

            List<String> completions = new CopyOnWriteArrayList<>();
            CountDownLatch done = new CountDownLatch(1);
            runner.setAsyncCompletionListener((id, completionResult) -> {
                completions.add(completionResult);
                done.countDown();
            });
            assertTrue(runner.sendMessage(childId.get(), OVERFLOW_PROMPT));
            assertTrue(done.await(20, TimeUnit.SECONDS), () -> "completions: " + completions);
            assertEquals(List.of("completed-after-compaction"), completions,
                    "the retried request must succeed");
            assertEquals(2, probe.calls.get(), "the retry must not re-run tools");

            String rejected = null;
            String retried = null;
            for (String request : requests) {
                if (request.contains(OVERFLOW_PROMPT)) {
                    if (request.contains(SUMMARY_MARKER)) retried = request;
                    else rejected = request;
                }
            }
            assertNotNull(rejected, "the provider must have rejected the third run once");
            assertNotNull(retried, "the compacted retry must have been sent");
            assertTrue(rejected.contains("marker-1"),
                    "fixture precondition: the rejected request carried the old exchange");
            assertTrue(retried.contains("summary-of-the-oldest-run"),
                    "the retried request must carry the model summary");
            assertEquals(1, countOccurrences(retried, OVERFLOW_PROMPT),
                    "the pending message rides the retry exactly once");
            assertTrue(retried.contains(SECOND_TASK_PAD),
                    "the recent exchange stays verbatim in the compacted history");
            assertFalse(retried.contains("marker-1"),
                    "the older exchange must be summarized away");
            assertTrue(MAPPER.readTree(retried).has("max_tokens"),
                    "the retried request stays sized against the child window");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @Timeout(60)
    void preventiveCompactionKeepsTheRetainedSessionReusable() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        AtomicInteger requestCount = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(requestBody);
            requestCount.incrementAndGet();
            if (isSummaryRequest(requestBody)) {
                respondSse(exchange, textChunk("summary-of-the-oldest-run"));
                return;
            }
            if ("tool".equals(lastRole(requestBody))) {
                respondSse(exchange, textChunk("completed-run"));
                return;
            }
            respondSse(exchange, TOOL_CALL_CHUNK);
        });
        server.start();
        try {
            PermissionService permissions = new PermissionService();
            permissions.allowAll();
            ToolRegistry tools = new ToolRegistry(MAPPER);
            ProbeTool probe = new ProbeTool(500);
            tools.register(probe);
            DirectSubagentRunner runner = runner(
                    config(2_048, server.getAddress().getPort(), true), tools, permissions);
            ToolContext parent = new ToolContext("compaction-parent",
                    AgentConfig.builder("parent").build(), permissions,
                    Path.of("."), tools);
            AtomicReference<String> childId = new AtomicReference<>();
            runner.setLifecycleListener(new SubagentRunner.LifecycleListener() {
                @Override public void onSubagentStart(String id, String type, String description) {
                    childId.compareAndSet(null, id);
                }
                @Override public void onSubagentEnd(String id) { }
            });

            String first = runner.runSubagent(
                    AgentConfig.builder("explore").isSubagent(true)
                            .systemPrompt("Child system prompt").build(),
                    "First task: " + SECOND_TASK_PAD, parent);
            assertTrue(first.contains("completed-run"), () -> "first run ended: " + first);
            assertEquals(1, probe.calls.get());
            assertNotNull(childId.get());
            assertTrue(runner.canSendMessage(childId.get()));

            List<String> completions = new CopyOnWriteArrayList<>();
            CountDownLatch done = new CountDownLatch(1);
            runner.setAsyncCompletionListener((id, completionResult) -> {
                completions.add(completionResult);
                done.countDown();
            });

            assertTrue(runner.sendMessage(childId.get(), "Second task: " + SECOND_TASK_PAD));
            assertTrue(runner.sendMessage(childId.get(), "Third task: " + SECOND_TASK_PAD));
            assertTrue(runner.sendMessage(childId.get(), "Fourth task: " + SECOND_TASK_PAD));
            assertTrue(done.await(30, TimeUnit.SECONDS), () -> "completions: " + completions);
            assertEquals(4, probe.calls.get(), "every exchange runs its tool exactly once");
            // Queued follow-ups run inline in one worker thread, so the async
            // completion listener observes only the final response of the batch.
            assertEquals(1, completions.size(), () -> "completions: " + completions);
            assertEquals("completed-run", completions.get(0),
                    "the final queued follow-up must complete the batch");

            // By the fourth exchange the three older ones exceed the trigger, so
            // the runner must have summarized the oldest exchange before sending.
            String compacted = null;
            for (String request : requests) {
                if (request.contains(SUMMARY_MARKER)
                        && request.contains("Fourth task: " + SECOND_TASK_PAD)) {
                    compacted = request;
                }
            }
            assertNotNull(compacted,
                    "the fourth exchange's request must ride a compacted history");
            assertTrue(compacted.contains("summary-of-the-oldest-run"),
                    "the compacted history must carry the model summary");
            assertTrue(compacted.contains("Fourth task: " + SECOND_TASK_PAD),
                    "the pending message stays verbatim");
            // The preserve span (~682 tokens) covers only the newest exchange, so
            // every older exchange is summarized away.
            assertFalse(compacted.contains("marker-1"),
                    "the oldest exchange must be summarized away");
            assertFalse(compacted.contains("marker-2"),
                    "older exchanges are summarized, not kept verbatim");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @Timeout(30)
    void oversizedUnicodeToolResultIsBoundedBeforeItReachesTheChildModel(@TempDir Path workspace)
            throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(body);
            respondSse(exchange, "tool".equals(lastRole(body))
                    ? textChunk("completed-run") : TOOL_CALL_CHUNK);
        });
        server.start();
        try {
            PermissionService permissions = new PermissionService();
            permissions.allowAll();
            ToolRegistry tools = new ToolRegistry(MAPPER);
            ProbeTool probe = new ProbeTool("😀".repeat(6_000) + "unique-tail");
            tools.register(probe);
            DirectSubagentRunner runner = runner(
                    config(250_000, server.getAddress().getPort(), false), tools, permissions);
            ToolContext parent = new ToolContext("budget-parent",
                    AgentConfig.builder("parent").build(), permissions, workspace, tools);
            String answer = runner.runSubagent(AgentConfig.builder("explore").isSubagent(true)
                    .systemPrompt("Child system prompt").build(), "Read the probe", parent);
            assertTrue(answer.contains("completed-run"), answer);
            assertEquals(1, probe.calls.get());
            String toolRequest = requests.stream().filter(r -> "tool".equals(lastRole(r)))
                    .findFirst().orElseThrow();
            JsonNode messages = MAPPER.readTree(toolRequest).path("messages");
            String content = messages.get(messages.size() - 1).path("content").asText();
            assertTrue(content.getBytes(StandardCharsets.UTF_8).length < 5_000, content);
            assertTrue(content.startsWith("marker-1 😀"));
            assertFalse(content.contains("unique-tail"));
            assertFalse(content.contains("�"), "Unicode must not be split");
            String pathLine = content.lines().filter(l -> l.startsWith("Full output saved to: "))
                    .findFirst().orElseThrow();
            Path saved = Path.of(pathLine.substring("Full output saved to: ".length()));
            assertEquals("marker-1 " + probe.pad(), java.nio.file.Files.readString(saved));
        } finally {
            server.stop(0);
        }
    }

    private static ChatConfig config(int window, int port, boolean autoCompact) {
        ChatConfig config = new ChatConfig("custom", "test", "child-model",
                "http://127.0.0.1:" + port);
        config.setContextWindowTokens(window);
        config.setMaxOutputTokens(256);
        config.setAutoCompactEnabled(autoCompact);
        config.setAutoCompactThreshold(0.5);
        config.setPromptCacheRetention("none");
        return config;
    }

    private static DirectSubagentRunner runner(ChatConfig config, ToolRegistry tools,
                                               PermissionService permissions) {
        return new DirectSubagentRunner(config, MAPPER, tools, permissions,
                new TerminalRenderer(false));
    }
}
