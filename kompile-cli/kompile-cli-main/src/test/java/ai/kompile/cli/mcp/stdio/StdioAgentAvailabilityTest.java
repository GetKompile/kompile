package ai.kompile.cli.mcp.stdio;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StdioAgentAvailabilityTest {

    private final ObjectMapper objectMapper = JsonUtils.standardMapper();

    @TempDir
    Path tempDir;

    @Test
    void delegationSchemasExposeCodexClaudeAndOpenCode() {
        StdioTaskTool task = new StdioTaskTool(null, null, objectMapper, null);
        StdioMultiTaskTool multiTask = new StdioMultiTaskTool(null, null, objectMapper, tempDir, null);
        StdioQuorumTaskTool quorumTask = new StdioQuorumTaskTool(null, null, objectMapper, tempDir);
        StdioEnforcerTool enforcer = new StdioEnforcerTool(null, objectMapper, tempDir);

        assertDelegationAgents(task.parameterSchema().path("properties").path("agent").path("enum"));
        JsonNode subtaskProperties = multiTask.parameterSchema().path("properties")
                .path("subtasks").path("items").path("properties");
        assertDelegationAgents(subtaskProperties.path("agent").path("enum"));
        assertDelegationAgents(subtaskProperties.path("agents").path("items").path("enum"));
        assertDelegationAgents(quorumTask.parameterSchema().path("properties")
                .path("agents").path("items").path("enum"));
        assertCodexOnly(enforcer.parameterSchema().path("properties").path("agent").path("enum"));
    }

    @Test
    void taskAcceptsEachSupportedAgentAndCarriesSelectionOverrides() {
        for (String agent : List.of("codex", "claude", "opencode")) {
            CapturingRunner runner = new CapturingRunner(tempDir);
            StdioTaskTool task = new StdioTaskTool(null, runner, objectMapper, null);
            ToolResult result = task.execute(Map.of(
                    "description", "selection test",
                    "prompt", "do work",
                    "agent", agent,
                    "model", "model-for-" + agent,
                    "thinking", "high"));

            assertTrue(!result.isError(), result.getOutput());
            assertEquals(agent, runner.lastAgent.getName());
            assertEquals("model-for-" + agent, runner.lastAgent.getModelOverride());
            assertEquals("high", runner.lastAgent.getThinkingOverride());
        }
    }

    @Test
    void multiTaskCarriesTopLevelAndPerSubtaskSelectionOverrides() {
        CapturingRunner runner = new CapturingRunner(tempDir);
        StdioMultiTaskTool multiTask = new StdioMultiTaskTool(null, runner, objectMapper, tempDir, null);
        Map<String, Object> inherited = subtask("inherits", "codex");
        Map<String, Object> overridden = subtask("overrides", "claude");
        overridden.put("model", "claude-model");
        overridden.put("thinking", "low");

        ToolResult result = multiTask.execute(Map.of(
                "description", "selection overrides",
                "model", "top-model",
                "thinking", "high",
                "subtasks", List.of(inherited, overridden)));

        assertTrue(!result.isError(), result.getOutput());
        AgentConfig inheritedAgent = runner.agentWithDescription("Subtask: inherits");
        assertEquals("top-model", inheritedAgent.getModelOverride());
        assertEquals("high", inheritedAgent.getThinkingOverride());
        AgentConfig overriddenAgent = runner.agentWithDescription("Subtask: overrides");
        assertEquals("claude-model", overriddenAgent.getModelOverride());
        assertEquals("low", overriddenAgent.getThinkingOverride());
    }

    @Test
    void quorumTaskCarriesSharedSelectionOverridesToEveryAgent() {
        CapturingRunner runner = new CapturingRunner(tempDir);
        StdioQuorumTaskTool quorum = new StdioQuorumTaskTool(null, runner, objectMapper, tempDir);

        ToolResult result = quorum.execute(Map.of(
                "description", "shared selection overrides",
                "prompt", "do work",
                "agents", List.of("codex", "claude"),
                "model", "shared-model",
                "thinking", "max"));

        assertTrue(!result.isError(), result.getOutput());
        assertEquals(2, runner.capturedAgents.size());
        for (AgentConfig agent : runner.capturedAgents) {
            assertEquals("shared-model", agent.getModelOverride());
            assertEquals("max", agent.getThinkingOverride());
        }
    }

    @Test
    void delegationToolsRejectQwenEvenWhenSchemaValidationIsBypassed() {
        StdioTaskTool task = new StdioTaskTool(null, null, objectMapper, null);
        ToolResult taskResult = task.execute(Map.of(
                "description", "invalid agent",
                "prompt", "do work",
                "agent", "qwen"));
        assertUnavailable(taskResult);

        StdioMultiTaskTool multiTask = new StdioMultiTaskTool(null, null, objectMapper, tempDir, null);
        Map<String, Object> invalidSubtask = subtask("one", "qwen");
        Map<String, Object> validSubtask = subtask("two", "codex");
        ToolResult multiResult = multiTask.execute(Map.of(
                "description", "invalid agent",
                "subtasks", List.of(invalidSubtask, validSubtask)));
        assertUnavailable(multiResult);

        StdioQuorumTaskTool quorum = new StdioQuorumTaskTool(null, null, objectMapper, tempDir);
        ToolResult quorumResult = quorum.execute(Map.of(
                "description", "invalid agent",
                "prompt", "do work",
                "agents", List.of("codex", "qwen")));
        assertUnavailable(quorumResult);

        StdioEnforcerTool enforcer = new StdioEnforcerTool(null, objectMapper, tempDir);
        ToolResult enforcerResult = enforcer.execute(Map.of(
                "prompt", "do work",
                "rules", "Follow the rules",
                "agent", "qwen"));
        assertUnavailable(enforcerResult);
    }

    @Test
    void multiTaskDispatchPlanExposesAgentsModelsRolesAndInstanceCount() {
        Map<String, Object> first = subtask("implementation", "codex");
        first.put("model", "gpt-5.3-codex");
        first.put("role", "developer");
        first.put("agent_count", 2);

        Map<String, Object> second = subtask("review", "codex");
        String plan = StdioMultiTaskTool.dispatchPlan(Map.of(
                "subtasks", List.of(first, second),
                "role", "reviewer"));

        assertTrue(plan.contains("**implementation**: agent=codex x2, model=gpt-5.3-codex, thinking=configured default, role=developer"), plan);
        assertTrue(plan.contains("**review**: agent=codex, model=configured default, thinking=configured default, role=reviewer"), plan);
        assertTrue(plan.contains("**Total agent instances**: 3"), plan);
    }

    @Test
    void delegationSchemasDocumentModelAndThinkingOverrides() {
        StdioTaskTool task = new StdioTaskTool(null, null, objectMapper, null);
        StdioMultiTaskTool multiTask = new StdioMultiTaskTool(null, null, objectMapper, tempDir, null);
        StdioQuorumTaskTool quorum = new StdioQuorumTaskTool(null, null, objectMapper, tempDir);

        assertSelectionFields(task.parameterSchema().path("properties"));
        JsonNode multiProperties = multiTask.parameterSchema().path("properties");
        assertSelectionFields(multiProperties);
        assertSelectionFields(multiProperties.path("subtasks").path("items").path("properties"));
        assertSelectionFields(quorum.parameterSchema().path("properties"));
    }

    private static final class CapturingRunner extends DirectSubagentRunnerStdio {
        AgentConfig lastAgent;
        final List<AgentConfig> capturedAgents = new CopyOnWriteArrayList<>();

        CapturingRunner(Path workDir) {
            super(workDir);
        }

        @Override
        DirectSubagentRunnerStdio forkForSubagent() {
            return this;
        }

        @Override
        public String runSubagent(AgentConfig agent, String prompt) {
            lastAgent = agent;
            capturedAgents.add(agent);
            return "completed";
        }

        AgentConfig agentWithDescription(String description) {
            return capturedAgents.stream()
                    .filter(agent -> description.equals(agent.getDescription()))
                    .findFirst()
                    .orElseThrow();
        }
    }

    private static Map<String, Object> subtask(String name, String agent) {
        Map<String, Object> subtask = new LinkedHashMap<>();
        subtask.put("name", name);
        subtask.put("prompt", "do work");
        subtask.put("agent", agent);
        return subtask;
    }

    private static void assertCodexOnly(JsonNode enumValues) {
        List<String> values = new ArrayList<>();
        enumValues.forEach(value -> values.add(value.asText()));
        assertEquals(List.of("codex"), values);
    }

    private static void assertDelegationAgents(JsonNode enumValues) {
        List<String> values = new ArrayList<>();
        enumValues.forEach(value -> values.add(value.asText()));
        assertEquals(List.of("codex", "claude", "opencode"), values);
    }

    private static void assertSelectionFields(JsonNode properties) {
        assertEquals("string", properties.path("model").path("type").asText());
        assertEquals("string", properties.path("thinking").path("type").asText());
    }

    private static void assertUnavailable(ToolResult result) {
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("is not available"), result.getOutput());
        assertTrue(result.getOutput().contains("codex"), result.getOutput());
    }
}
