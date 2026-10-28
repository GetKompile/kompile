package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.CompactionService;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end resumable-subagent lifecycle against a fake OpenAI-compatible
 * provider: a resumable delegation persists its ledger, a second runner
 * (fresh JVM-equivalent state) resumes the same session id with the prior
 * history replayed into the wire, and the ledger accumulates rather than
 * resetting. Non-resumable delegations write nothing — the opt-in contract.
 */
@TemporaryUserHome
class DirectSubagentRunnerResumeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FIRST_TASK = "First resumable task";
    private static final String SECOND_TASK = "Second follow-up task";

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

    private static ChatConfig config(int port) {
        ChatConfig config = new ChatConfig();
        config.setProvider("openai");
        config.setBaseUrl("http://127.0.0.1:" + port);
        config.setModel("test-model");
        config.setContextWindowTokens(100_000);
        config.setMaxOutputTokens(256);
        config.setAutoCompactEnabled(false);
        config.setPromptCacheRetention("none");
        return config;
    }

    private static DirectSubagentRunner runner(ChatConfig config, ToolRegistry tools,
                                               PermissionService permissions) {
        return new DirectSubagentRunner(config, MAPPER, tools, permissions,
                new TerminalRenderer(false));
    }

    private static Path conversationsDir() {
        return ai.kompile.cli.common.KompileHome.homeDirectory().toPath().resolve("conversations");
    }

    @Test
    @Timeout(60)
    void resumableRunPersistsAndResumesAcrossRunnerInstances() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        AtomicReference<String> childId = new AtomicReference<>();
        var allRequestsLocked = new java.util.concurrent.atomic.AtomicBoolean(true);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(body);
            if (!new SubagentResumeService(conversationsDir(), MAPPER).isLocked(childId.get())) {
                allRequestsLocked.set(false);
            }
            // SECOND_TASK is checked first: a correctly replayed request contains
            // BOTH tasks (the resumed history plus the new prompt).
            respondSse(exchange, textChunk(body.contains(SECOND_TASK)
                    ? "second-done"
                    : "first-done"));
        });
        server.start();
        try {
            PermissionService permissions = new PermissionService();
            permissions.allowAll();
            ToolRegistry tools = new ToolRegistry(MAPPER);

            SubagentRunOptions options = SubagentRunOptions.resumable("e2e-resume");

            // Run 1: the resumable delegation on runner A.
            DirectSubagentRunner runnerA = runner(config(server.getAddress().getPort()), tools, permissions);
            runnerA.setLifecycleListener(new SubagentRunner.LifecycleListener() {
                @Override public void onSubagentStart(String id, String type, String description) {
                    childId.compareAndSet(null, id);
                }
                @Override public void onSubagentEnd(String id) { }
            });
            ToolContext parent = new ToolContext("resume-parent",
                    AgentConfig.builder("parent").build(), permissions, Path.of("."), tools);
            String first = runnerA.runSubagent(
                    AgentConfig.builder("explore").isSubagent(true)
                            .systemPrompt("Child system prompt").build(),
                    FIRST_TASK, parent, options);
            assertEquals("first-done", first);
            String sessionId = childId.get();
            assertNotNull(sessionId);
            assertTrue(sessionId.startsWith("subagent-"),
                    "resumable ids carry the subagent- identity prefix: " + sessionId);

            // Sidecar + ledger exist and are discoverable by the registry.
            SubagentResumeService service = new SubagentResumeService(conversationsDir(), MAPPER);
            List<SubagentResumeService.Summary> all = service.list();
            assertEquals(1, all.size());
            assertEquals("e2e-resume", all.get(0).name());
            assertTrue(service.resolve("e2e-resume").ok());
            List<CompactionService.ConversationEntry> persisted = service.ledger(sessionId);
            assertEquals(2, persisted.size(), "user + assistant committed to the ledger");

            // A retained child can run again without losing its follow-up from disk.
            var followUpDone = new java.util.concurrent.CountDownLatch(1);
            runnerA.setLifecycleListener(new SubagentRunner.LifecycleListener() {
                @Override public void onSubagentStart(String id, String type, String description) { }
                @Override public void onSubagentEnd(String id) { followUpDone.countDown(); }
            });
            assertTrue(runnerA.sendMessage(sessionId, "retained follow-up"));
            assertTrue(followUpDone.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(4, service.ledger(sessionId).size(), "retained follow-up is committed to disk");

            // Run 2: a fresh runner (no shared in-memory state) resumes by id.
            DirectSubagentRunner runnerB = runner(config(server.getAddress().getPort()), tools, permissions);
            int requestCountBefore = requests.size();
            String second = runnerB.resumeSubagent(
                    AgentConfig.builder("explore").isSubagent(true)
                            .systemPrompt("Child system prompt").build(),
                    sessionId, SECOND_TASK, parent);
            assertEquals("second-done", second);
            for (String request : requests) {
                String systemPrompt = MAPPER.readTree(request).path("messages").get(0).path("content").asText();
                assertTrue(systemPrompt.contains(ai.kompile.cli.main.chat.config.KompileToolGuidance.PROMPT),
                        "fresh, retained and resumed children must receive tool/workflow guidance");
                assertTrue(systemPrompt.contains("Child system prompt"));
            }

            // The resumed request must carry the replayed prior exchange.
            String resumedRequest = requests.get(requests.size() - 1);
            assertTrue(resumedRequest.contains(FIRST_TASK),
                    "the replayed history must contain the first task");
            assertTrue(resumedRequest.contains(SECOND_TASK),
                    "the resumed request carries the new prompt");

            // The ledger accumulated, not reset.
            List<CompactionService.ConversationEntry> afterResume = service.ledger(sessionId);
            assertTrue(resumedRequest.contains("retained follow-up"));
            assertEquals(6, afterResume.size(),
                    "initial, retained follow-up and resumed exchanges all live in the ledger");

            // The sidecar records the adopting parent.
            SubagentResumeMetadata metadata = service.metadataFor(sessionId);
            assertEquals("resume-parent", metadata.parentSession());
            assertTrue(allRequestsLocked.get(), "initial, retained and resumed requests all hold writer ownership");
            runnerA.close();
            runnerB.close();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void startupObserverFailureReleasesTheResumableWriterLock() throws Exception {
        PermissionService permissions = new PermissionService();
        permissions.allowAll();
        ToolRegistry tools = new ToolRegistry(MAPPER);
        DirectSubagentRunner runner = runner(config(1), tools, permissions);
        AtomicReference<String> childId = new AtomicReference<>();
        runner.setLifecycleListener(new SubagentRunner.LifecycleListener() {
            @Override public void onSubagentStart(String id, String type, String description) {
                childId.set(id);
                throw new IllegalStateException("observer failed before provider call");
            }
            @Override public void onSubagentEnd(String id) { throw new IllegalStateException("end observer failed"); }
        });
        try {
            ToolContext parent = new ToolContext("observer-parent", AgentConfig.builder("parent").build(),
                    permissions, Path.of("."), tools);
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> runner.runSubagent(AgentConfig.builder("explore").isSubagent(true).build(),
                            "task", parent, SubagentRunOptions.resumable("observer-failure")));
            SubagentResumeService service = new SubagentResumeService(conversationsDir(), MAPPER);
            org.junit.jupiter.api.Assertions.assertFalse(service.isLocked(childId.get()));
            org.junit.jupiter.api.Assertions.assertFalse(runner.hasPendingWork(childId.get()));
            org.junit.jupiter.api.Assertions.assertNull(service.prune(childId.get()));
        } finally {
            runner.close();
        }
    }

    @Test
    @Timeout(30)
    void failedLedgerNeverReplacesAcceptedRetainedHistory() throws Exception {
        var requests = new CopyOnWriteArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respondSse(exchange, textChunk("accepted-reply"));
        });
        server.start();
        ObjectMapper failingLedgerMapper = new ObjectMapper() {
            @Override public String writeValueAsString(Object value) throws com.fasterxml.jackson.core.JsonProcessingException {
                if (value instanceof com.fasterxml.jackson.databind.JsonNode node
                        && "ASSISTANT".equals(node.path("type").asText())) {
                    throw new com.fasterxml.jackson.core.JsonProcessingException("injected ledger write failure") { };
                }
                return super.writeValueAsString(value);
            }
        };
        PermissionService permissions = new PermissionService();
        permissions.allowAll();
        ToolRegistry tools = new ToolRegistry(MAPPER);
        DirectSubagentRunner runner = new DirectSubagentRunner(config(server.getAddress().getPort()),
                failingLedgerMapper, tools, permissions, new TerminalRenderer(false));
        AtomicReference<String> childId = new AtomicReference<>();
        runner.setLifecycleListener(new SubagentRunner.LifecycleListener() {
            @Override public void onSubagentStart(String id, String type, String description) { childId.set(id); }
            @Override public void onSubagentEnd(String id) { }
        });
        try {
            ToolContext parent = new ToolContext("failed-ledger-parent", AgentConfig.builder("parent").build(),
                    permissions, Path.of("."), tools);
            assertEquals("accepted-reply", runner.runSubagent(AgentConfig.builder("explore").isSubagent(true).build(),
                    "initial task", parent, SubagentRunOptions.resumable("failed-ledger")));
            assertEquals(1, new SubagentResumeService(conversationsDir(), MAPPER).ledger(childId.get()).size());
            var finished = new java.util.concurrent.CountDownLatch(1);
            runner.setLifecycleListener(new SubagentRunner.LifecycleListener() {
                @Override public void onSubagentStart(String id, String type, String description) { }
                @Override public void onSubagentEnd(String id) { finished.countDown(); }
            });
            assertTrue(runner.sendMessage(childId.get(), "follow up after disk failure"));
            assertTrue(finished.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(requests.get(requests.size() - 1).contains("accepted-reply"),
                    "valid retained memory must survive a partial on-disk ledger");
        } finally {
            runner.close();
            server.stop(0);
            if (childId.get() != null) new SubagentResumeService(conversationsDir(), MAPPER).prune(childId.get());
        }
    }

    @Test
    @Timeout(60)
    void nonResumableRunsWriteNothing() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange ->
                respondSse(exchange, textChunk("plain-done")));
        server.start();
        try {
            PermissionService permissions = new PermissionService();
            permissions.allowAll();
            ToolRegistry tools = new ToolRegistry(MAPPER);
            SubagentResumeService service = new SubagentResumeService(conversationsDir(), MAPPER);
            int sidecarsBefore = service.list().size();
            DirectSubagentRunner runner = runner(config(server.getAddress().getPort()), tools, permissions);
            ToolContext parent = new ToolContext("plain-parent",
                    AgentConfig.builder("parent").build(), permissions, Path.of("."), tools);
            String result = runner.runSubagent(
                    AgentConfig.builder("explore").isSubagent(true).build(),
                    "a plain task", parent);
            assertEquals("plain-done", result);

            assertEquals(sidecarsBefore, service.list().size(),
                    "default (non-resumable) delegations must not persist anything");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @Timeout(60)
    void resumeRejectsNonSubagentAndUnknownSessions() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange ->
                respondSse(exchange, textChunk("unused")));
        server.start();
        try {
            PermissionService permissions = new PermissionService();
            permissions.allowAll();
            ToolRegistry tools = new ToolRegistry(MAPPER);
            DirectSubagentRunner runner = runner(config(server.getAddress().getPort()), tools, permissions);
            ToolContext parent = new ToolContext("guard-parent",
                    AgentConfig.builder("parent").build(), permissions, Path.of("."), tools);

            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> runner.resumeSubagent(AgentConfig.builder("explore").isSubagent(true).build(),
                            "main-chat-session", "hi", parent),
                    "non-subagent ids are refused at the identity boundary");
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> runner.resumeSubagent(AgentConfig.builder("explore").isSubagent(true).build(),
                            "subagent-ghost-00000000", "hi", parent),
                    "unknown subagent ids are refused");
        } finally {
            server.stop(0);
        }
    }
}
