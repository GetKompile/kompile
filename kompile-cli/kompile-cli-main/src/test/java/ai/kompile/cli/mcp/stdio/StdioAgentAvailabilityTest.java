package ai.kompile.cli.mcp.stdio;

import ai.kompile.cli.common.util.JsonUtils;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StdioAgentAvailabilityTest {

    private final ObjectMapper objectMapper = JsonUtils.standardMapper();

    @TempDir
    Path tempDir;

    @Test
    void delegationSchemasExposeOnlyCodex() {
        StdioTaskTool task = new StdioTaskTool(null, null, objectMapper, null);
        StdioMultiTaskTool multiTask = new StdioMultiTaskTool(null, null, objectMapper, tempDir, null);
        StdioQuorumTaskTool quorumTask = new StdioQuorumTaskTool(null, null, objectMapper, tempDir);
        StdioEnforcerTool enforcer = new StdioEnforcerTool(null, objectMapper, tempDir);

        assertCodexOnly(task.parameterSchema().path("properties").path("agent").path("enum"));
        JsonNode subtaskProperties = multiTask.parameterSchema().path("properties")
                .path("subtasks").path("items").path("properties");
        assertCodexOnly(subtaskProperties.path("agent").path("enum"));
        assertCodexOnly(subtaskProperties.path("agents").path("items").path("enum"));
        assertCodexOnly(quorumTask.parameterSchema().path("properties")
                .path("agents").path("items").path("enum"));
        assertCodexOnly(enforcer.parameterSchema().path("properties").path("agent").path("enum"));
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

        assertTrue(plan.contains("**implementation**: agent=codex x2, model=gpt-5.3-codex, role=developer"), plan);
        assertTrue(plan.contains("**review**: agent=codex, model=configured default, role=reviewer"), plan);
        assertTrue(plan.contains("**Total agent instances**: 3"), plan);
    }

    @Test
    void multiTaskSchemaDocumentsModelOverrides() {
        StdioMultiTaskTool multiTask = new StdioMultiTaskTool(null, null, objectMapper, tempDir, null);
        JsonNode properties = multiTask.parameterSchema().path("properties");
        assertEquals("string", properties.path("model").path("type").asText());
        assertEquals("string", properties.path("subtasks").path("items").path("properties")
                .path("model").path("type").asText());
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

    private static void assertUnavailable(ToolResult result) {
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("is not available"), result.getOutput());
        assertTrue(result.getOutput().contains("codex"), result.getOutput());
    }
}
