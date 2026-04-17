package ai.kompile.cli.mcp.stdio;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.roles.RoleManager;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class StdioTaskTool {

    private final AgentRegistry agentRegistry;
    private final DirectSubagentRunnerStdio subagentRunner;
    private final ObjectMapper objectMapper;
    private final RoleManager roleManager;

    public StdioTaskTool(AgentRegistry agentRegistry,
                         DirectSubagentRunnerStdio subagentRunner,
                         ObjectMapper objectMapper,
                         RoleManager roleManager) {
        this.agentRegistry = agentRegistry;
        this.subagentRunner = subagentRunner;
        this.objectMapper = objectMapper;
        this.roleManager = roleManager;
    }

    public String id() { return "task"; }

    public String description() {
        return "Spawn a subagent to handle a delegated task. " +
            "The subagent runs as an external agent process (qwen, claude, codex, etc.) " +
            "with its own context window, then returns a summary.\n\n" +
            "Available agents: qwen (default), claude, codex, gemini, opencode.\n" +
            "Returns a concise summary. Full output is written to a file under .kompile/task-results/ " +
            "which can be read with the `read` tool if more detail is needed.\n" +
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
        String requestedAgent = (String) arguments.getOrDefault("agent", "qwen");
        String roleName = (String) arguments.get("role"); // optional

        if (prompt == null || prompt.isEmpty()) {
            return ToolResult.error("prompt is required");
        }

        // Build ordered list: requested agent first, then role-based fallbacks
        List<String> roleFallbacks = roleManager.getAgentFallbackOrder(roleName);
        List<String> agentsToTry = new ArrayList<>();
        agentsToTry.add(requestedAgent);
        for (String fallback : roleFallbacks) {
            if (!fallback.equals(requestedAgent)) {
                agentsToTry.add(fallback);
            }
        }

        List<String> rateLimitedAgents = new ArrayList<>();

        for (String agentName : agentsToTry) {
            AgentConfig agentConfig = AgentConfig.builder(agentName)
                .displayName(agentName.substring(0, 1).toUpperCase() + agentName.substring(1))
                .description("External " + agentName + " agent")
                .systemPrompt(prompt).maxSteps(50).isSubagent(true).canSpawnSubagents(false)
                .roleName(roleName)
                .build();

            if (rateLimitedAgents.isEmpty()) {
                System.err.println("\u001B[32m  ⟳ Spawning " + agentName + " subagent: " + desc + "\u001B[0m");
            } else {
                System.err.println("\u001B[33m  ⟳ Falling back to " + agentName + " (rate limited: "
                    + String.join(", ", rateLimitedAgents) + ")\u001B[0m");
            }

            try {
                String result = subagentRunner.runSubagent(agentConfig, prompt);
                if (result.contains("not found in PATH")) {
                    // Agent not installed — skip to next
                    System.err.println("\u001B[2m  " + agentName + " not found, trying next agent...\u001B[0m");
                    continue;
                }
                return ToolResult.success("task:" + agentName, result,
                    Map.of("agent", agentName, "description", desc, "mode", "external-process",
                           "fallbacksUsed", String.valueOf(rateLimitedAgents.size())));
            } catch (RateLimitException e) {
                rateLimitedAgents.add(agentName);
                System.err.println("\u001B[33m  ⚠ " + agentName + " rate limited, trying fallback...\u001B[0m");
                // Continue to next agent
            } catch (Exception e) {
                return ToolResult.error("Subagent execution failed: " + e.getMessage());
            }
        }

        // All agents exhausted
        return ToolResult.error("All agents were rate limited or unavailable: "
            + String.join(", ", rateLimitedAgents)
            + ". Please try again later.");
    }
}
