package ai.kompile.cli.mcp.stdio;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.Map;

public class StdioTaskTool {

    private final AgentRegistry agentRegistry;
    private final DirectSubagentRunnerStdio subagentRunner;
    private final ObjectMapper objectMapper;

    public StdioTaskTool(AgentRegistry agentRegistry,
                         DirectSubagentRunnerStdio subagentRunner,
                         ObjectMapper objectMapper) {
        this.agentRegistry = agentRegistry;
        this.subagentRunner = subagentRunner;
        this.objectMapper = objectMapper;
    }

    public String id() { return "task"; }

    public String description() {
        return "Spawn a subagent to handle a delegated task. " +
            "The subagent runs as an external agent process (qwen, claude, codex, etc.) " +
            "with its own context window, then returns its final response.\n\n" +
            "Available agents: qwen (default), claude, codex, gemini, opencode.\n" +
            "The subagent runs once and returns — it cannot send follow-up messages.";
    }

    public JsonNode parameterSchema() {
        var schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        var desc = props.putObject("description");
        desc.put("type", "string");
        desc.put("description", "A short (3-5 word) description of the task");
        var prompt = props.putObject("prompt");
        prompt.put("type", "string");
        prompt.put("description", "Detailed task description for the subagent. Include all necessary context.");
        var agent = props.putObject("agent");
        agent.put("type", "string");
        agent.put("description", "Which agent to spawn. Options: qwen (default), claude, codex, gemini, opencode.");
        ArrayNode enumValues = agent.putArray("enum");
        enumValues.add("qwen"); enumValues.add("claude"); enumValues.add("codex");
        enumValues.add("gemini"); enumValues.add("opencode");
        var role = props.putObject("role");
        role.put("type", "string");
        role.put("description", "Optional role to assign to the subagent (e.g., 'developer', 'architect', 'reviewer')");
        schema.putArray("required").add("description").add("prompt");
        return schema;
    }

    public ToolResult execute(Map<String, Object> arguments) {
        String desc = (String) arguments.getOrDefault("description", "");
        String prompt = (String) arguments.getOrDefault("prompt", "");
        String agentName = (String) arguments.getOrDefault("agent", "qwen");
        String roleName = (String) arguments.get("role"); // optional

        if (prompt == null || prompt.isEmpty()) {
            return ToolResult.error("prompt is required");
        }

        AgentConfig agentConfig = AgentConfig.builder(agentName)
            .displayName(agentName.substring(0, 1).toUpperCase() + agentName.substring(1))
            .description("External " + agentName + " agent")
            .systemPrompt(prompt).maxSteps(50).isSubagent(true).canSpawnSubagents(false)
            .roleName(roleName)
            .build();

        System.err.println("\u001B[32m  ⟳ Spawning " + agentName + " subagent: " + desc + "\u001B[0m");

        try {
            String result = subagentRunner.runSubagent(agentConfig, prompt);
            if (result.contains("not found in PATH")) return ToolResult.error(result);
            return ToolResult.success("task:" + agentName, result,
                Map.of("agent", agentName, "description", desc, "mode", "external-process"));
        } catch (Exception e) {
            return ToolResult.error("Subagent execution failed: " + e.getMessage());
        }
    }
}
