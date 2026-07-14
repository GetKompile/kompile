package ai.kompile.cli.mcp.stdio;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.roles.RoleManager;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.Map;

public class StdioTaskTool {

    private static final String CODEX_AGENT = "codex";

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

    /**
     * Constructor with optional coordination state manager for multi-agent
     * edit tracking and conflict detection.
     */
    public StdioTaskTool(AgentRegistry agentRegistry,
                         DirectSubagentRunnerStdio subagentRunner,
                         ObjectMapper objectMapper,
                         RoleManager roleManager,
                         Object coordinationStateManager) {
        this(agentRegistry, subagentRunner, objectMapper, roleManager);
        // coordinationStateManager stored for future use
    }

    public String id() { return "task"; }

    public String description() {
        return "Spawn a subagent to handle a delegated task. " +
            "The subagent runs through the same managed terminal launcher used by interactive passthrough " +
            "with its own context window, then returns a summary.\n\n" +
            "Available agent: codex (default).\n" +
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
        agent.put("description", "Which agent to spawn. Codex is the only available agent and the default.");
        ArrayNode enumValues = agent.putArray("enum");
        enumValues.add(CODEX_AGENT);
        var role = props.putObject("role");
        role.put("type", "string");
        role.put("description", "Optional role to assign to the subagent (e.g., 'developer', 'architect', 'reviewer')");
        schema.putArray("required").add("description").add("prompt");
        return schema;
    }

    public ToolResult execute(Map<String, Object> arguments) {
        String desc = (String) arguments.getOrDefault("description", "");
        String prompt = (String) arguments.getOrDefault("prompt", "");
        String requestedAgent = (String) arguments.getOrDefault("agent", "codex");
        String roleName = (String) arguments.get("role"); // optional

        if (prompt == null || prompt.isEmpty()) {
            return ToolResult.error("prompt is required");
        }
        if (!CODEX_AGENT.equals(requestedAgent)) {
            return ToolResult.error("Agent '" + requestedAgent
                + "' is not available. Available agent: codex.");
        }

        AgentConfig agentConfig = AgentConfig.builder(CODEX_AGENT)
            .displayName("Codex")
            .description("External Codex agent")
            .systemPrompt(prompt).maxSteps(50).isSubagent(true).canSpawnSubagents(false)
            .roleName(roleName)
            .build();

        System.err.println("\u001B[32m  ⟳ Spawning Codex subagent: " + desc + "\u001B[0m");

        try {
            String result = subagentRunner.runSubagent(agentConfig, prompt);
            if (isAgentMissing(result)) {
                return ToolResult.error("Codex is not available on PATH.");
            }
            return ToolResult.success("task:" + CODEX_AGENT, result,
                Map.of("agent", CODEX_AGENT, "description", desc, "mode", "managed-terminal",
                       "fallbacksUsed", "0"));
        } catch (RateLimitException e) {
            System.err.println("\u001B[33m  \u26a0 Codex rate limited; provider fallback is disabled.\u001B[0m");
            return ToolResult.error("Codex is rate limited. No provider fallback was attempted.");
        } catch (Exception e) {
            return ToolResult.error("Subagent execution failed: " + e.getMessage());
        }
    }

    static boolean isAgentMissing(String result) {
        return result != null && (result.contains("not found in PATH") || result.contains("not found on PATH"));
    }
}
