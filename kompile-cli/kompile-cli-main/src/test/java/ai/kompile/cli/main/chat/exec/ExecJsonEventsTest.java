package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.main.chat.ChatHistory;
import ai.kompile.cli.common.auth.ManagedCredential;
import ai.kompile.cli.main.auth.CredentialStore;
import ai.kompile.cli.main.chat.config.ChatConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ExecJsonEventsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void session_hasTypeAndFields() throws Exception {
        JsonNode n = mapper.readTree(ExecJsonEvents.session(mapper, "exec-123", "claude-x", "/work"));
        assertEquals("session", n.get("type").asText());
        assertEquals("exec-123", n.get("session_id").asText());
        assertEquals("claude-x", n.get("model").asText());
        assertEquals("/work", n.get("cwd").asText());
    }

    @Test
    void session_omitsNullModelAndCwd() throws Exception {
        JsonNode n = mapper.readTree(ExecJsonEvents.session(mapper, "s", null, null));
        assertEquals("s", n.get("session_id").asText());
        assertFalse(n.has("model"));
        assertFalse(n.has("cwd"));
    }

    @Test
    void text_escapesSpecialCharsAndStaysSingleLine() throws Exception {
        String chunk = "line1\nline2 \"quoted\" \tend";
        String line = ExecJsonEvents.text(mapper, chunk);
        assertEquals(-1, line.indexOf('\n'), "JSONL line must not contain a raw newline");
        JsonNode n = mapper.readTree(line);
        assertEquals("text", n.get("type").asText());
        assertEquals(chunk, n.get("text").asText());
    }

    @Test
    void tool_carriesNameOkAndMs() throws Exception {
        JsonNode n = mapper.readTree(ExecJsonEvents.tool(mapper, "bash", true, 42));
        assertEquals("tool", n.get("type").asText());
        assertEquals("bash", n.get("name").asText());
        assertTrue(n.get("ok").asBoolean());
        assertEquals(42, n.get("ms").asLong());
    }

    @Test
    void tool_okFalseIsPreserved() throws Exception {
        // The !isError mapping happens at the call site (JsonEmittingMetrics);
        // the builder records the boolean it is given verbatim.
        JsonNode n = mapper.readTree(ExecJsonEvents.tool(mapper, "edit", false, 5));
        assertFalse(n.get("ok").asBoolean());
        assertEquals(5, n.get("ms").asLong());
    }

    @Test
    void result_carriesTextSessionToolsExit() throws Exception {
        JsonNode n = mapper.readTree(ExecJsonEvents.result(mapper, "done", "exec-9", 3, 0));
        assertEquals("result", n.get("type").asText());
        assertEquals("done", n.get("text").asText());
        assertEquals("exec-9", n.get("session_id").asText());
        assertEquals(3, n.get("tools").asInt());
        assertEquals(0, n.get("exit").asInt());
    }

    @Test
    void error_carriesMessage() throws Exception {
        JsonNode n = mapper.readTree(ExecJsonEvents.error(mapper, "boom"));
        assertEquals("error", n.get("type").asText());
        assertEquals("boom", n.get("message").asText());
    }

    @Test
    void nullsDegradeGracefully() throws Exception {
        assertEquals("", mapper.readTree(ExecJsonEvents.text(mapper, null)).get("text").asText());
        assertEquals("", mapper.readTree(ExecJsonEvents.result(mapper, null, "s", 0, 0)).get("text").asText());
        assertEquals("", mapper.readTree(ExecJsonEvents.error(mapper, null)).get("message").asText());
    }

    @Test
    void richEventsPreserveSequenceAndLifecycle() throws Exception {
        JsonNode started = mapper.readTree(ExecJsonEvents.event(mapper,
                HeadlessRunEvent.started("s", "model", "/work", Map.of(
                        "provider", "anthropic",
                        "thinking", "high",
                        "agent", "coder",
                        "role", "architect",
                        "rag", "true",
                        "memory", "false")).withSequence(7)));
        assertEquals(7, started.get("seq").asLong());
        assertEquals("session", started.get("type").asText());
        assertEquals("anthropic", started.get("provider").asText());
        assertEquals("high", started.get("thinking").asText());
        assertEquals("coder", started.get("agent").asText());
        assertEquals("architect", started.get("role").asText());
        assertTrue(started.get("rag").asBoolean());
        assertFalse(started.get("memory").asBoolean());

        JsonNode backend = mapper.readTree(ExecJsonEvents.event(mapper,
                HeadlessRunEvent.backendStarted("s", "codex-cli", "process-1")
                        .withSequence(8)));
        assertEquals("backend", backend.get("type").asText());
        assertEquals("codex-cli", backend.get("agent").asText());
        assertEquals("process-1", backend.get("process_id").asText());

        JsonNode sources = mapper.readTree(ExecJsonEvents.event(mapper,
                HeadlessRunEvent.sources("s", "[{\"title\":\"doc\"}]")
                        .withSequence(9)));
        assertEquals("doc", sources.path("sources").path(0).path("title").asText());

        JsonNode stats = mapper.readTree(ExecJsonEvents.event(mapper,
                HeadlessRunEvent.stats("s", "{\"durationMs\":5}")
                        .withSequence(10)));
        assertEquals(5, stats.path("stats").path("durationMs").asLong());

        JsonNode delta = mapper.readTree(ExecJsonEvents.event(mapper,
                HeadlessRunEvent.assistantDelta("s", "hello").withSequence(11)));
        assertEquals(11, delta.get("seq").asLong());
        assertEquals("text", delta.get("type").asText());
        assertEquals("hello", delta.get("text").asText());

        JsonNode toolStart = mapper.readTree(ExecJsonEvents.event(mapper,
                HeadlessRunEvent.toolStarted("s", "call-1", "bash", "pwd").withSequence(12)));
        assertEquals(12, toolStart.get("seq").asLong());
        assertEquals("tool_start", toolStart.get("type").asText());
        assertEquals("call-1", toolStart.get("call_id").asText());

        JsonNode toolDone = mapper.readTree(ExecJsonEvents.event(mapper,
                HeadlessRunEvent.toolCompleted("s", "call-1", "bash", "", true, 12).withSequence(13)));
        assertEquals(13, toolDone.get("seq").asLong());
        assertEquals("tool", toolDone.get("type").asText());
        assertEquals(12, toolDone.get("ms").asLong());

        JsonNode usage = mapper.readTree(ExecJsonEvents.event(mapper,
                HeadlessRunEvent.tokenUsage("s", 120, 30, 40, 5).withSequence(14)));
        assertEquals("usage", usage.get("type").asText());
        assertEquals(120, usage.get("input_tokens").asLong());
        assertEquals(30, usage.get("output_tokens").asLong());
        assertEquals(40, usage.get("cache_read_tokens").asLong());
        assertEquals(5, usage.get("cache_creation_tokens").asLong());

        JsonNode result = mapper.readTree(ExecJsonEvents.event(mapper,
                HeadlessRunEvent.completed("s", "done", 0, 1).withSequence(15)));
        assertEquals(15, result.get("seq").asLong());
        assertEquals("result", result.get("type").asText());
        assertEquals("done", result.get("text").asText());
        assertEquals(1, result.get("tools").asInt());
    }

    @Test
    @ResourceLock("user.home")
    void headlessServerStreamsWithDefaultAgentAndRestoredHistory(@TempDir Path tempDir)
            throws Exception {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.resolve("home").toString());
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        HttpServer server = startSseServer(requestBody, """
                event: start
                data: {"agent":"codex-cli","processId":"process-1"}

                event: sources
                data: [{"title":"doc"}]

                event: chunk
                data: "hello"

                event: stats
                data: {"durationMs":5}

                event: complete
                data: {}

                """);
        try {
            String sessionId = "json-server-" + UUID.randomUUID();
            ChatHistory previous = new ChatHistory(sessionId);
            previous.open("(local)", "coder", false, tempDir);
            previous.logUserMessage("earlier question");
            previous.logAgentResponse("coder", "earlier answer", 1);
            previous.close();

            List<HeadlessRunEvent> events = new ArrayList<>();
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            ChatConfig config = new ChatConfig("kompile", null, null, baseUrl);
            HeadlessAgentRunner.Options options = configuredServerOptions(
                    "next question", sessionId, true, tempDir, config, baseUrl, events);

            HeadlessAgentRunner.Result result = new HeadlessAgentRunner().run(options);

            assertEquals(0, result.exitCode());
            assertEquals("hello", result.text());
            assertEquals("claude-cli", requestBody.get().path("agentName").asText());
            assertTrue(requestBody.get().path("enableRag").asBoolean());
            assertEquals(tempDir.toAbsolutePath().toString(),
                    requestBody.get().path("workingDirectory").asText());
            JsonNode history = requestBody.get().path("chatHistory");
            assertEquals(2, history.size());
            assertEquals("earlier question", history.get(0).path("content").asText());
            assertEquals("earlier answer", history.get(1).path("content").asText());
            assertEquals(List.of(
                            HeadlessRunEvent.Type.RUN_STARTED,
                            HeadlessRunEvent.Type.BACKEND_STARTED,
                            HeadlessRunEvent.Type.SOURCES,
                            HeadlessRunEvent.Type.ASSISTANT_DELTA,
                            HeadlessRunEvent.Type.STATS,
                            HeadlessRunEvent.Type.RUN_COMPLETED),
                    events.stream().map(HeadlessRunEvent::type).toList());
            assertEquals("claude-cli", events.get(0).metadata().get("agent"));
            assertEquals("codex-cli", events.get(1).toolName());
        } finally {
            server.stop(0);
            System.setProperty("user.home", previousHome);
        }
    }

    @Test
    @ResourceLock("user.home")
    void headlessServerErrorIsTerminalFailure(@TempDir Path tempDir) throws Exception {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.resolve("home").toString());
        HttpServer server = startSseServer(new AtomicReference<>(), """
                event: error
                data: {"message":"agent unavailable"}

                """);
        try {
            List<HeadlessRunEvent> events = new ArrayList<>();
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            ChatConfig config = new ChatConfig("kompile", null, null, baseUrl);

            HeadlessAgentRunner.Result result = new HeadlessAgentRunner().run(
                    configuredServerOptions("hello", "json-error-" + UUID.randomUUID(),
                            false, tempDir, config, baseUrl, events));

            assertEquals(1, result.exitCode());
            assertEquals(HeadlessRunEvent.Type.RUN_FAILED,
                    events.get(events.size() - 1).type());
            assertTrue(events.stream().noneMatch(
                    event -> event.type() == HeadlessRunEvent.Type.RUN_COMPLETED));
        } finally {
            server.stop(0);
            System.setProperty("user.home", previousHome);
        }
    }

    @Test
    @ResourceLock("user.home")
    void headlessStandardChatPreservesOpenAiSubscriptionAuth(@TempDir Path tempDir)
            throws Exception {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.resolve("home").toString());
        AtomicReference<Map<String, List<String>>> headers = new AtomicReference<>();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/codex/responses", exchange -> {
            try {
                headers.set(exchange.getRequestHeaders());
                requestBody.set(mapper.readTree(exchange.getRequestBody()));
                byte[] response = ("data: {\"type\":\"response.output_text.delta\","
                        + "\"delta\":\"subscription answer\"}\n\n"
                        + "data: {\"type\":\"response.completed\",\"response\":{"
                        + "\"status\":\"completed\",\"output\":[],\"usage\":{"
                        + "\"input_tokens\":10,\"output_tokens\":2}}}\n\n")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            CredentialStore.create().put("openai-codex", ManagedCredential.oauth(
                    "codex-access", "codex-refresh",
                    System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1),
                    Map.of("accountId", "account-123")));
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            ChatConfig config = new ChatConfig(
                    "openai-codex", null, "gpt-5.6-terra", baseUrl);
            List<HeadlessRunEvent> events = new ArrayList<>();
            HeadlessAgentRunner.Options options = new HeadlessAgentRunner.Options(
                    "hello", "json-subscription-" + UUID.randomUUID(), false,
                    null, null, HeadlessAgentRunner.OutputMode.JSON,
                    tempDir, 0, null, null, null, events::add,
                    config, null, false, false, null, false);

            HeadlessAgentRunner.Result result = new HeadlessAgentRunner().run(options);

            assertEquals(0, result.exitCode());
            assertEquals("subscription answer", result.text());
            assertEquals("Bearer codex-access", firstHeader(headers.get(), "Authorization"));
            assertEquals("account-123", firstHeader(headers.get(), "chatgpt-account-id"));
            assertEquals("gpt-5.6-terra", requestBody.get().path("model").asText());
            assertEquals("openai-codex", events.get(0).metadata().get("provider"));
            assertEquals("oauth", events.get(0).metadata().get("auth"));
            assertEquals(HeadlessRunEvent.Type.RUN_COMPLETED,
                    events.get(events.size() - 1).type());
        } finally {
            server.stop(0);
            System.setProperty("user.home", previousHome);
        }
    }

    @Test
    @ResourceLock("user.home")
    void headlessDirectProviderErrorIsTerminalFailure(@TempDir Path tempDir)
            throws Exception {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.resolve("home").toString());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] response = "{\"error\":{\"message\":\"bad credential\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            ChatConfig config = new ChatConfig("custom", null, "model", baseUrl);
            List<HeadlessRunEvent> events = new ArrayList<>();
            HeadlessAgentRunner.Options options = new HeadlessAgentRunner.Options(
                    "hello", "json-direct-error-" + UUID.randomUUID(), false,
                    null, null, HeadlessAgentRunner.OutputMode.JSON,
                    tempDir, 0, null, null, null, events::add,
                    config, null, false, false, null, false);

            HeadlessAgentRunner.Result result = new HeadlessAgentRunner().run(options);

            assertEquals(1, result.exitCode());
            assertEquals(HeadlessRunEvent.Type.RUN_FAILED,
                    events.get(events.size() - 1).type());
            assertTrue(events.stream().noneMatch(
                    event -> event.type() == HeadlessRunEvent.Type.RUN_COMPLETED));
        } finally {
            server.stop(0);
            System.setProperty("user.home", previousHome);
        }
    }

    @Test
    @ResourceLock("user.home")
    void requiredMcpStartupFailureBecomesATerminalRunFailure(@TempDir Path tempDir)
            throws Exception {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.resolve("home").toString());
        Files.writeString(tempDir.resolve(".mcp.json"), """
                {"mcpServers":{"required":{"command":"/missing/required-mcp","required":true}}}
                """);
        String sessionId = "required-mcp-" + UUID.randomUUID();
        List<HeadlessRunEvent> events = new ArrayList<>();
        ChatConfig config = new ChatConfig(
                "custom", null, "model", "http://127.0.0.1:1");
        HeadlessAgentRunner.Options options = new HeadlessAgentRunner.Options(
                "hello", sessionId, false, null, null,
                HeadlessAgentRunner.OutputMode.JSON, tempDir, 0, null,
                null, null, events::add, config, null,
                false, false, null, false);
        try {
            HeadlessAgentRunner.Result result = new HeadlessAgentRunner().run(options);

            assertEquals(1, result.exitCode());
            assertEquals(HeadlessRunEvent.Type.RUN_FAILED,
                    events.get(events.size() - 1).type());
            assertTrue(ChatHistory.exists(sessionId));
        } finally {
            System.setProperty("user.home", previousHome);
        }
    }

    @Test
    @ResourceLock("user.home")
    void builtInJsonSinkKeepsEveryStdoutLineMachineReadable(@TempDir Path tempDir)
            throws Exception {
        String previousHome = System.getProperty("user.home");
        PrintStream previousOut = System.out;
        PrintStream previousErr = System.err;
        System.setProperty("user.home", tempDir.resolve("home").toString());
        HttpServer server = startSseServer(new AtomicReference<>(), """
                event: chunk
                data: "machine readable"

                event: complete
                data: {}

                """);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            ChatConfig config = new ChatConfig("kompile", null, null, baseUrl);
            HeadlessAgentRunner.Options options = new HeadlessAgentRunner.Options(
                    "hello", "json-stdout-" + UUID.randomUUID(), false, null, null,
                    HeadlessAgentRunner.OutputMode.JSON, tempDir, 0, null,
                    null, null, null, config, baseUrl,
                    false, false, null, false);

            assertEquals(0, new HeadlessAgentRunner().run(options).exitCode());

            List<JsonNode> lines = stdout.toString(StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.isBlank())
                    .map(line -> assertDoesNotThrow(() -> mapper.readTree(line), line))
                    .toList();
            assertEquals(List.of("session", "text", "result"),
                    lines.stream().map(line -> line.path("type").asText()).toList());
            assertTrue(lines.stream().allMatch(line -> line.path("seq").asLong() > 0));
        } finally {
            server.stop(0);
            System.setOut(previousOut);
            System.setErr(previousErr);
            System.setProperty("user.home", previousHome);
        }
    }

    @Test
    @ResourceLock("user.home")
    void timeoutClosesEventStreamBeforeLateServerOutput(@TempDir Path tempDir)
            throws Exception {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.resolve("home").toString());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/agents/chat/stream", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try {
                exchange.getResponseBody().write(
                        "event: chunk\ndata: \"before timeout\"\n\n"
                                .getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                TimeUnit.MILLISECONDS.sleep(300);
                exchange.getResponseBody().write(
                        "event: chunk\ndata: \"late output\"\n\n"
                                .getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
            } catch (Exception ignored) {
                // The client is expected to close the stream on timeout.
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            List<HeadlessRunEvent> events = new ArrayList<>();
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            ChatConfig config = new ChatConfig("kompile", null, null, baseUrl);
            HeadlessAgentRunner.Options options = new HeadlessAgentRunner.Options(
                    "hello", "json-timeout-" + UUID.randomUUID(), false, null, null,
                    HeadlessAgentRunner.OutputMode.JSON, tempDir, 50, null,
                    null, null, events::add, config, baseUrl,
                    false, false, null, false);

            HeadlessAgentRunner.Result result = new HeadlessAgentRunner().run(options);
            int countAtReturn = events.size();
            TimeUnit.MILLISECONDS.sleep(400);

            assertEquals(124, result.exitCode());
            assertEquals(countAtReturn, events.size());
            assertEquals(HeadlessRunEvent.Type.RUN_COMPLETED,
                    events.get(events.size() - 1).type());
            assertEquals(124, events.get(events.size() - 1).exitCode());
        } finally {
            server.stop(0);
            System.setProperty("user.home", previousHome);
        }
    }

    private HeadlessAgentRunner.Options configuredServerOptions(
            String prompt, String sessionId, boolean resume, Path workDir,
            ChatConfig config, String baseUrl, List<HeadlessRunEvent> events) {
        return new HeadlessAgentRunner.Options(
                prompt, sessionId, resume, null, null,
                HeadlessAgentRunner.OutputMode.JSON, workDir, 0, null,
                null, null, events::add, config, baseUrl,
                true, false, null, false);
    }

    private HttpServer startSseServer(
            AtomicReference<JsonNode> requestBody, String responseBody) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/agents/chat/stream", exchange -> {
            try {
                requestBody.set(mapper.readTree(exchange.getRequestBody()));
                byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    private static String firstHeader(Map<String, List<String>> headers, String name) {
        if (headers == null) return null;
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(entry -> entry.getValue().isEmpty() ? null : entry.getValue().get(0))
                .findFirst().orElse(null);
    }
}
