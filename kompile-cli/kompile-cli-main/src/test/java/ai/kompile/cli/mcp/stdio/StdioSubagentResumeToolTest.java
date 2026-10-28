package ai.kompile.cli.mcp.stdio;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.agent.SubagentResumeMetadata;
import ai.kompile.cli.main.chat.agent.SubagentResumeService;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class StdioSubagentResumeToolTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ID = "subagent-original-codex";
    @TempDir Path directory;

    private SubagentResumeService seed() throws Exception {
        var service = new SubagentResumeService(directory, MAPPER);
        service.writeMetadata(SubagentResumeMetadata.create(ID, "saved", "codex", "old-parent", null, "old-model")
                .withRoute("old-provider", "api-key"));
        Files.writeString(directory.resolve(ID + ".ledger.jsonl"), "{\"v\":1}\n"
                + "{\"type\":\"USER\",\"role\":\"user\",\"content\":\"original task\"}\n"
                + "{\"type\":\"ASSISTANT\",\"role\":\"assistant\",\"content\":\"original answer\"}\n");
        return service;
    }

    private ToolContext context() {
        var permissions = new PermissionService();
        permissions.allowAll();
        return new ToolContext("adopting-parent", AgentConfig.builder("caller").build(), permissions, directory, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"codex", "claude", "opencode", "gemini", "qwen", "pi"})
    void everyCliCanContinueAnotherAgentsHistory(String agent) throws Exception {
        var service = seed();
        AtomicReference<Map<String, Object>> invocation = new AtomicReference<>();
        var task = new StdioTaskTool(null, null, MAPPER, null) {
            @Override public ToolResult execute(Map<String, Object> args, ToolContext parent) {
                invocation.set(new LinkedHashMap<>(args));
                assertTrue(service.isLocked(ID), "the entire external run must own the durable lease");
                return ToolResult.success("target result", "continued by " + args.get("agent"));
            }
        };
        var tool = new StdioSubagentResumeTool(task, MAPPER, service);
        var params = MAPPER.createObjectNode().put("action", "resume").put("session_id", "saved")
                .put("prompt", "next task").put("agent", agent).put("background", false);
        var result = tool.execute(params, context());
        assertFalse(result.isError(), result.getOutput());
        assertEquals(agent, invocation.get().get("agent"));
        assertFalse(invocation.get().containsKey("model"), "do not transfer the old agent's model");
        assertFalse(invocation.get().containsKey("provider"));
        assertFalse(invocation.get().containsKey("authentication_method"));
        String prompt = (String) invocation.get().get("prompt");
        assertTrue(prompt.contains("original task"));
        assertTrue(prompt.contains("original answer"));
        assertTrue(prompt.contains("next task"));
        assertEquals(ID, result.getMetadata().get("sessionId"));
        var reopened = new SubagentResumeService(directory, MAPPER);
        assertEquals(4, reopened.ledger(ID).size());
        assertEquals("adopting-parent", reopened.metadataFor(ID).parentSession());
        assertFalse(reopened.isLocked(ID));
        // A second fresh adapter, using yet another CLI, sees the first continuation too.
        var second = new StdioSubagentResumeTool(task, MAPPER, reopened);
        assertFalse(second.execute(params.put("agent", "claude").put("prompt", "third task"), context()).isError());
        assertTrue(invocation.get().get("prompt").toString().contains("continued by " + agent));
        assertEquals(6, reopened.ledger(ID).size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"kompile", "general"})
    void nativeTargetUsesTypedResumeWithTheSameIdAndExplicitRoute(String agent) throws Exception {
        var service = seed();
        var task = new StdioTaskTool(null, null, MAPPER, null) {
            @Override public ToolResult execute(Map<String, Object> args, ToolContext parent) { fail("must not create a fresh task"); return null; }
            @Override ToolResult resumeNative(String id, Map<String, Object> args, ToolContext parent) {
                assertEquals(ID, id);
                assertEquals(agent, args.get("agent"));
                assertEquals("new-provider", args.get("provider"));
                assertEquals("new-model", args.get("model"));
                assertEquals("native", args.get("authentication_method"));
                assertEquals("next task", args.get("prompt"));
                return ToolResult.success("typed continuation");
            }
        };
        var tool = new StdioSubagentResumeTool(task, MAPPER, service);
        var result = tool.execute(MAPPER.createObjectNode().put("action", "resume").put("session_id", ID)
                .put("prompt", "next task").put("agent", agent).put("provider", "new-provider")
                .put("model", "new-model").put("authentication_method", "native").put("background", false), context());
        assertFalse(result.isError(), result.getOutput());
        assertEquals(ID, result.getMetadata().get("sessionId"));
        assertFalse(tool.parameterSchema().path("properties").has("agent_type"));
    }

    @Test void nativeRuntimeInvokesResumeRatherThanStartingANewChild() throws Exception {
        var config = new ai.kompile.cli.main.chat.config.ChatConfig();
        config.setProvider("openai"); config.setModel("target-model"); config.setApiKey("test-key");
        config.setBaseUrl("http://127.0.0.1:1");
        AtomicReference<String> resumed = new AtomicReference<>();
        var nativeRunner = new KompileNativeTaskRunner(directory, null, null, null,
                new ai.kompile.cli.main.chat.render.TerminalRenderer(false)) {
            @Override ai.kompile.cli.main.chat.config.ChatConfig loadConfiguration() { return config; }
            @Override AutoCloseable configureSupervision(ToolContext context, String prompt) { return () -> {}; }
            @Override ai.kompile.cli.main.chat.tools.ToolRegistry createRegistry(
                    ai.kompile.cli.main.chat.config.ChatConfig selected, ai.kompile.cli.main.chat.agent.AgentRegistry agents,
                    PermissionService permissions) {
                var registry = new ai.kompile.cli.main.chat.tools.ToolRegistry(MAPPER);
                registry.setSubagentRunner(new ai.kompile.cli.main.chat.agent.SubagentRunner() {
                    public String runSubagent(AgentConfig agent, String prompt, ToolContext parent) { throw new AssertionError("fresh task"); }
                    public String resumeSubagent(AgentConfig agent, String id, String prompt, ToolContext parent) {
                        resumed.set(id);
                        assertEquals("adopting-parent", parent.getSessionId());
                        assertEquals("target-model", agent.getModelOverride());
                        assertEquals("openai", agent.getProviderOverride());
                        assertFalse(agent.canSpawnSubagents());
                        return "typed resumed";
                    }
                });
                return registry;
            }
        };
        String result = nativeRunner.resume(AgentConfig.builder("kompile").build(), ID, "followup",
                new java.util.concurrent.atomic.AtomicBoolean(), context());
        assertEquals(ID, resumed.get());
        assertTrue(result.contains("typed resumed"));
    }

    @Test void portableContinuationPersistsFullAnswerNotTheTaskSummary() throws Exception {
        var service = seed();
        String childId = "subagent-claude-new";
        Files.writeString(directory.resolve(childId + ".ledger.jsonl"), "{\"v\":1}\n"
                + "{\"type\":\"ASSISTANT\",\"role\":\"assistant\",\"content\":\"full untruncated answer\"}\n");
        var task = new StdioTaskTool(null, null, MAPPER, null) {
            @Override public ToolResult execute(Map<String, Object> args, ToolContext parent) {
                return ToolResult.success("summary", "short summary", Map.of("sessionId", childId));
            }
        };
        var tool = new StdioSubagentResumeTool(task, MAPPER, service);
        var result = tool.execute(MAPPER.createObjectNode().put("action", "resume").put("session_id", ID)
                .put("prompt", "next task").put("agent", "claude").put("background", false), context());
        assertFalse(result.isError(), result.getOutput());
        assertEquals("short summary", result.getOutput());
        assertEquals("full untruncated answer", service.ledger(ID).get(3).content);
    }

    @Test void failedCliDoesNotCommitAnExchangeOrHoldItsLease() throws Exception {
        var service = seed();
        var task = new StdioTaskTool(null, null, MAPPER, null) {
            @Override public ToolResult execute(Map<String, Object> args, ToolContext parent) { return ToolResult.error("target login failed"); }
        };
        var tool = new StdioSubagentResumeTool(task, MAPPER, service);
        var result = tool.execute(MAPPER.createObjectNode().put("action", "resume").put("session_id", ID)
                .put("prompt", "retry").put("agent", "gemini").put("background", false), context());
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("target login failed"));
        assertEquals(2, service.ledger(ID).size());
        assertFalse(service.isLocked(ID));
    }
}
