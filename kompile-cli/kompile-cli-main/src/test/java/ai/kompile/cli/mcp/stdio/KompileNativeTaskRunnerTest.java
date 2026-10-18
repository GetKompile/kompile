package ai.kompile.cli.mcp.stdio;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.agent.DirectSubagentRunner;
import ai.kompile.cli.main.chat.agent.SubprocessAgentRunner;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.enforcer.EnforcerConfig;
import ai.kompile.cli.main.chat.harness.HarnessConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ReadTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolExecutionException;
import ai.kompile.cli.main.chat.workflow.WorkflowSessionContext;
import ai.kompile.cli.main.chat.workflow.WorkflowTeam;
import ai.kompile.cli.main.chat.workflow.WorkflowTeamSnapshot;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class KompileNativeTaskRunnerTest {
    @TempDir Path workDir;
    final ObjectMapper mapper = JsonUtils.standardMapper();

    @Test
    void nativeOverridesUseConfiguredProviderWithoutMutatingStoredDefaults() {
        ChatConfig configured = new ChatConfig("openai-codex", "managed-credential", "default-model", null);
        configured.setThinking("low");
        ChatConfig selected = KompileNativeTaskRunner.configuration(configured,
                "openai-codex/gpt-6.1-sol", "xhigh");
        assertEquals("openai-codex", selected.getProvider());
        assertEquals("gpt-6.1-sol", selected.getModel());
        assertEquals("xhigh", selected.getThinking());
        assertEquals("default-model", configured.getModel());
        assertEquals("low", configured.getThinking());
        assertTrue(KompileNativeTaskRunner.supports("general"));
        assertTrue(KompileNativeTaskRunner.supports("kompile"));
        assertFalse(KompileNativeTaskRunner.supports("codex"));
        assertThrows(IllegalStateException.class, () -> KompileNativeTaskRunner.configuration(null, null, null));
    }

    @Test
    void cancellationBeforeLaunchDoesNotBuildAnyHarness() {
        KompileNativeTaskRunner runner = new KompileNativeTaskRunner(workDir, null, null, null,
                new TerminalRenderer(false)) {
            @Override ChatConfig loadConfiguration() { throw new AssertionError("must not load configuration"); }
        };
        assertThrows(InterruptedException.class, () -> runner.run(
                AgentConfig.builder("kompile").build(), "no work", new AtomicBoolean(true)));
    }

    @Test
    @Timeout(30)
    void mcpTaskAndParallelBatchReachNativeProviderWithNoVendorCli() throws Exception {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        List<String> authorization = new CopyOnWriteArrayList<>();
        CountDownLatch batchArrived = new CountDownLatch(2);
        AtomicBoolean batch = new AtomicBoolean();
        var serverThreads = Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(serverThreads);
        server.createContext("/chat/completions", exchange -> {
            try {
                requests.add(mapper.readTree(exchange.getRequestBody()));
                authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
                if (batch.get()) {
                    batchArrived.countDown();
                    if (!batchArrived.await(10, TimeUnit.SECONDS)) {
                        exchange.sendResponseHeaders(500, -1);
                        return;
                    }
                }
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write((
                        "data: {\"choices\":[{\"delta\":{\"content\":\"native done\"},\"finish_reason\":null}]}\n\n"
                                + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            ChatConfig config = new ChatConfig("custom", "project-auth", "configured-model",
                    "http://127.0.0.1:" + server.getAddress().getPort());
            config.setThinking("low");
            DirectSubagentRunnerStdio runner = routingRunner(config);
            StdioTaskTool task = new StdioTaskTool(new AgentRegistry(), runner, mapper, null, workDir);
            ToolResult one = task.execute(Map.of("description", "native task", "prompt", "answer once",
                    "agent", "general", "model", "native-model", "thinking", "xhigh"));
            assertFalse(one.isError(), one.getOutput());
            assertTrue(one.getOutput().contains("mode=kompile-native"), one.getOutput());
            assertTrue(one.getOutput().contains("native done"), one.getOutput());
            assertEquals("kompile-native", one.getMetadata().get("mode"));
            batch.set(true);
            StdioMultiTaskTool multi = new StdioMultiTaskTool(new AgentRegistry(), runner, mapper, workDir, null);
            ToolResult many = multi.execute(Map.of("description", "parallel native tasks", "thinking", "xhigh",
                    "model", "native-model", "subtasks", List.of(
                            Map.of("name", "first", "prompt", "answer first", "agent", "kompile"),
                            Map.of("name", "second", "prompt", "answer second", "agent", "general"))));
            assertFalse(many.isError(), many.getOutput());
            assertEquals(2, many.getMetadata().get("succeeded"));
            assertEquals(0, batchArrived.getCount(), "both native tasks must start before either returns");
            assertEquals(3, requests.size());
            for (JsonNode request : requests) {
                assertEquals("native-model", request.path("model").asText());
                assertEquals("xhigh", request.path("reasoning_effort").asText());
            }
            assertEquals(List.of("Bearer project-auth", "Bearer project-auth", "Bearer project-auth"), authorization);
            assertEquals("configured-model", config.getModel());
            assertEquals("low", config.getThinking());
        } finally {
            server.stop(0);
            serverThreads.shutdownNow();
        }
    }

    @Test
    void nativeFailureIsReportedWithoutAnyCliFallback() {
        DirectSubagentRunnerStdio runner = routingRunner(null);
        ToolResult one = new StdioTaskTool(new AgentRegistry(), runner, mapper, null, workDir)
                .execute(Map.of("description", "missing configuration", "prompt", "do work", "agent", "kompile"));
        assertTrue(one.isError());
        assertTrue(one.getOutput().contains("No Kompile chat configuration"), one.getOutput());
        ToolResult batch = new StdioMultiTaskTool(new AgentRegistry(), runner, mapper, workDir, null)
                .execute(Map.of("description", "missing batch configuration", "subtasks", List.of(
                        Map.of("name", "a", "prompt", "first", "agent", "general"),
                        Map.of("name", "b", "prompt", "second", "agent", "kompile"))));
        assertTrue(batch.isError(), batch.getOutput());
        assertEquals(0, batch.getMetadata().get("succeeded"));
        assertEquals(2, batch.getMetadata().get("failed"));
    }

    @Test
    @Timeout(30)
    void productionNativeRegistryExecutesToolsWithoutHeadlessStdoutRedirection() throws Exception {
        Files.writeString(workDir.resolve("fixture.txt"), "native tool evidence");
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requests.add(mapper.readTree(exchange.getRequestBody()));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            String response = requests.size() == 1
                    ? "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"read-fixture\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{\\\"file_path\\\":\\\"fixture.txt\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n"
                    : "data: {\"choices\":[{\"delta\":{\"content\":\"verified native tools\"},\"finish_reason\":\"stop\"}]}\n\n";
            exchange.getResponseBody().write((response + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        try {
            ChatConfig config = new ChatConfig("custom", "project-auth", "test-model",
                    "http://127.0.0.1:" + server.getAddress().getPort());
            KompileNativeTaskRunner runner = new KompileNativeTaskRunner(workDir, null, null, null,
                    new TerminalRenderer(false)) {
                @Override ChatConfig loadConfiguration() { return config; }
                @Override ToolRegistry createRegistry(ChatConfig child, AgentRegistry agents, PermissionService permissions) {
                    ToolRegistry tools = super.createRegistry(child, agents, permissions);
                    tools.register(new ReadTool() {
                        @Override public ToolResult execute(JsonNode arguments, ToolContext context) throws ToolExecutionException {
                            assertNotNull(context.getOutputConsumer(), "Native tools need a task-local sink");
                            context.emitOutput("fixture tool progress");
                            return super.execute(arguments, context);
                        }
                    });
                    return tools;
                }
            };
            var stdout = System.out;
            ByteArrayOutputStream wire = new ByteArrayOutputStream();
            try (PrintStream rpcOut = new PrintStream(wire, true, StandardCharsets.UTF_8)) {
                System.setOut(rpcOut);
                String result = runner.run(AgentConfig.builder("kompile").build(), "Read fixture.txt and report",
                        new AtomicBoolean());
                assertSame(rpcOut, System.out, "MCP stdout must not be globally swapped by the native harness");
                assertTrue(result.contains("verified native tools"), result);
                // MCP bootstrap already redirects third-party cold-start logging to stderr.
                // This fixture runs without that bootstrap: test the task-owned progress boundary.
                assertFalse(wire.toString(StandardCharsets.UTF_8).contains("fixture tool progress"),
                        "Tool progress must not enter RPC stdout");
            } finally {
                System.setOut(stdout);
            }
            assertEquals(2, requests.size());
            assertTrue(requests.get(1).path("messages").toString().contains("native tool evidence"),
                    requests.get(1).toString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @Timeout(15)
    void terminalProviderErrorsFailBothTaskAndBatch() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] error = "{\"error\":{\"message\":\"fixture provider rejected model\",\"type\":\"invalid_request_error\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, error.length);
            exchange.getResponseBody().write(error);
            exchange.close();
        });
        server.start();
        try {
            ChatConfig config = new ChatConfig("custom", "project-auth", "rejected-model",
                    "http://127.0.0.1:" + server.getAddress().getPort());
            DirectSubagentRunnerStdio runner = routingRunner(config);
            ToolResult one = new StdioTaskTool(new AgentRegistry(), runner, mapper, null, workDir)
                    .execute(Map.of("description", "rejected provider", "prompt", "answer", "agent", "kompile"));
            assertTrue(one.isError(), one.getOutput());
            assertTrue(one.getOutput().contains("provider request failed"), one.getOutput());
            ToolResult batch = new StdioMultiTaskTool(new AgentRegistry(), runner, mapper, workDir, null)
                    .execute(Map.of("description", "rejected batch", "subtasks", List.of(
                            Map.of("name", "a", "prompt", "first", "agent", "general"),
                            Map.of("name", "b", "prompt", "second", "agent", "kompile"))));
            assertTrue(batch.isError(), batch.getOutput());
            assertEquals(0, batch.getMetadata().get("succeeded"));
            assertEquals(2, batch.getMetadata().get("failed"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @Timeout(15)
    void enabledKeywordEnforcementHasUsableNativeReviewer() throws Exception {
        EnforcerConfig policy = new EnforcerConfig();
        policy.setKeywordMode(true);
        policy.setBannedKeywords(List.of("forbidden-fixture-content"));
        policy.setMaxCorrections(0);
        policy.save(workDir);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicBoolean forbidden = new AtomicBoolean();
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            String text = forbidden.get() ? "forbidden-fixture-content" : "native compliant";
            exchange.getResponseBody().write(("data: {\"choices\":[{\"delta\":{\"content\":\"" + text
                    + "\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        try {
            ChatConfig config = new ChatConfig("custom", "project-auth", "model",
                    "http://127.0.0.1:" + server.getAddress().getPort());
            StdioTaskTool task = new StdioTaskTool(new AgentRegistry(), routingRunner(config), mapper, null, workDir);
            ToolResult compliant = task.execute(Map.of("description", "policy accepts", "prompt", "answer", "agent", "kompile"));
            assertFalse(compliant.isError(), compliant.getOutput());
            assertTrue(compliant.getOutput().contains("native compliant"), compliant.getOutput());
            forbidden.set(true);
            ToolResult violation = task.execute(Map.of("description", "policy rejects", "prompt", "answer", "agent", "general"));
            assertTrue(violation.isError(), violation.getOutput());
            assertFalse(violation.getOutput().contains("reviewer is unavailable"), violation.getOutput());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @Timeout(15)
    void nativeWorkflowParticipantCannotWriteWithoutCapability() throws Exception {
        WorkflowTeam team = new WorkflowTeam("native-read-only", 1, "lead", Map.of(
                "lead", new WorkflowTeam.Participant("lead", "coder", "cli", List.of("read", "delegate"), List.of("reader")),
                "reader", new WorkflowTeam.Participant("reader", "coder", "cli", List.of("read"), List.of())),
                Map.of("review", "reader"), new WorkflowTeam.Limits(2), new WorkflowTeam.Gates(null, null));
        WorkflowSessionContext.activate(new WorkflowTeamSnapshot(team, Map.of("lead", "coder", "reader", "coder"), null));
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requests.add(mapper.readTree(exchange.getRequestBody()));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            String response = requests.size() == 1
                    ? "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"denied-write\",\"type\":\"function\",\"function\":{\"name\":\"write\",\"arguments\":\"{\\\"file_path\\\":\\\"not-allowed.txt\\\",\\\"content\\\":\\\"should not exist\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n"
                    : "data: {\"choices\":[{\"delta\":{\"content\":\"write was blocked\"},\"finish_reason\":\"stop\"}]}\n\n";
            exchange.getResponseBody().write((response + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        try {
            ChatConfig config = new ChatConfig("custom", "project-auth", "test-model",
                    "http://127.0.0.1:" + server.getAddress().getPort());
            KompileNativeTaskRunner runner = new KompileNativeTaskRunner(workDir, null, null, null,
                    new TerminalRenderer(false)) {
                @Override ChatConfig loadConfiguration() { return config; }
            };
            String result = runner.run(AgentConfig.builder("kompile").workflowParticipant("reader").build(),
                    "Read-only inspection", new AtomicBoolean());
            assertTrue(result.contains("write was blocked"), result);
            assertFalse(Files.exists(workDir.resolve("not-allowed.txt")));
            assertEquals(2, requests.size());
            assertTrue(requests.get(1).path("messages").toString().contains("reader"), requests.get(1).toString());
        } finally {
            server.stop(0);
            WorkflowSessionContext.activate(null);
        }
    }

    private DirectSubagentRunnerStdio routingRunner(ChatConfig config) {
        return new DirectSubagentRunnerStdio(workDir) {
            @Override public DirectSubagentRunnerStdio forkForSubagent() { return routingRunner(config); }
            @Override SubprocessAgentRunner createManagedRunner(String name, boolean inject) {
                throw new AssertionError("Native task must never launch external CLI " + name);
            }
            @Override KompileNativeTaskRunner createNativeRunner() {
                return new KompileNativeTaskRunner(workDir, null, null, null, new TerminalRenderer(false)) {
                    @Override ChatConfig loadConfiguration() { return config; }
                    @Override HarnessConfig loadHarnessConfig() {
                        HarnessConfig harness = new HarnessConfig();
                        harness.setJudgeGlobalEnabled(true);
                        return harness;
                    }
                    @Override ToolRegistry createRegistry(ChatConfig child, AgentRegistry agents,
                                                          PermissionService permissions) {
                        // Real native provider loop; omit unrelated tools' heavyweight initialization in this fixture.
                        ToolRegistry tools = new ToolRegistry(mapper);
                        tools.setSubagentRunner(new DirectSubagentRunner(child, mapper, tools, permissions,
                                new TerminalRenderer(false)));
                        return tools;
                    }
                };
            }
        };
    }
}
