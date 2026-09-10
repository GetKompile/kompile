package ai.kompile.cli.mcp.stdio;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.agent.AgentLaunchDefaults;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.roles.RoleConfig;
import ai.kompile.cli.main.chat.roles.RoleManager;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.tools.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class StdioTaskTool {

    static final String DEFAULT_AGENT = "codex";
    static final List<String> SUPPORTED_AGENTS = AgentLaunchDefaults.SUPPORTED_AGENTS;

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
        return "Delegate one task; use multi_task for 2+ independent subtasks in one parallel batch. " +
            "Do not call task repeatedly and wait for each result when the work can run concurrently. " +
            "Use sequential task calls only when later work needs an earlier result, or shared files/resources require serialization.\n\n" +
            "The subagent runs through the same managed terminal launcher used by interactive passthrough " +
            "with its own context window, then returns a summary.\n\n" +
            "Available agents: codex (default), claude, opencode. Roles customize the prompt and " +
            "model defaults without disabling tools, edits, execution, or delegation.\n" +
            "Returns a concise summary. Full output is written to a file under .kompile/task-results/ " +
            "which can be read with the `read` tool if more detail is needed.\n" +
            "The subagent runs once and returns — it cannot send follow-up messages.";
    }

    /** Keep parallel-tool discovery visible in compact MCP tools/list responses. */
    public String compactHint() {
        return "Delegate one task. For 2+ independent subtasks use multi_task in one parallel batch, not serial task calls. Sequence only for dependencies or shared files/resources.";
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
        agent.put("default", DEFAULT_AGENT);
        agent.put("description", "Which agent to spawn. Available: codex (default), claude, opencode.");
        ArrayNode enumValues = agent.putArray("enum");
        SUPPORTED_AGENTS.forEach(enumValues::add);
        var model = props.putObject("model");
        model.put("type", "string");
        model.put("description", "Optional model override. Precedence: explicit value, selected role default, project default, user default, provider native default.");
        var thinking = props.putObject("thinking");
        thinking.put("type", "string");
        thinking.put("description", "Optional thinking/effort override. When omitted, exact-model/default thinking from the selected role is tried before project/user defaults. Native mapping: Codex reasoning effort, Claude effort, OpenCode variant.");
        var role = props.putObject("role");
        role.put("type", "string");
        role.put("description", "Optional role to assign to the subagent. When omitted, the agent's persisted role assignment is used. Roles may define prompt, model, and thinking defaults; all tools remain enabled.");
        schema.putArray("required").add("description").add("prompt");
        return schema;
    }

    public ToolResult execute(Map<String, Object> arguments) {
        return execute(arguments, null);
    }

    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        if (context != null && context.isAborted()) return ToolResult.error("Task cancelled");
        String desc = (String) arguments.getOrDefault("description", "");
        String prompt = (String) arguments.getOrDefault("prompt", "");
        String requestedAgent = String.valueOf(arguments.getOrDefault("agent", DEFAULT_AGENT))
                .toLowerCase(Locale.ROOT);
        String model = (String) arguments.get("model");
        String thinking = (String) arguments.get("thinking");
        String roleName = (String) arguments.get("role"); // optional

        if (prompt == null || prompt.isEmpty()) {
            return ToolResult.error("prompt is required");
        }
        if (!isSupportedAgent(requestedAgent)) {
            return ToolResult.error("Agent '" + requestedAgent
                + "' is not available. Available agents: " + String.join(", ", SUPPORTED_AGENTS) + ".");
        }
        if (roleName != null && !roleName.isBlank() && roleManager != null
                && roleManager.getRole(roleName) == null) {
            return ToolResult.error("Unknown role '" + roleName + "'; refusing to launch subagent.");
        }

        String displayName = requestedAgent.substring(0, 1).toUpperCase(Locale.ROOT) + requestedAgent.substring(1);
        AgentConfig agentConfig = AgentConfig.builder(requestedAgent)
            .displayName(displayName)
            .description("External " + displayName + " agent")
            .systemPrompt(prompt).isSubagent(true).canSpawnSubagents(true)
            .roleName(roleName)
            .modelOverride(model)
            .thinkingOverride(thinking)
            .build();

        System.err.println("\u001B[32m  ⟳ Spawning " + displayName + " subagent: " + desc + "\u001B[0m");

        try {
            // The shared runner is a factory, never the cancellation target of a request.
            String result = runWithCancellation(subagentRunner.forkForSubagent(), agentConfig, prompt, context);
            if (isAgentMissing(result)) {
                return ToolResult.error(displayName + " is not available on PATH.");
            }
            String effectiveRoleName = roleName;
            RoleConfig effectiveRole = roleName != null && !roleName.isBlank() && roleManager != null
                    ? roleManager.getRole(roleName) : null;
            if ((effectiveRoleName == null || effectiveRoleName.isBlank()) && roleManager != null) {
                effectiveRoleName = roleManager.getAgentRole(requestedAgent);
                effectiveRole = effectiveRoleName == null ? null : roleManager.getRole(effectiveRoleName);
            }
            AgentLaunchDefaults.Selection selection = AgentLaunchDefaults.resolve(
                    requestedAgent, java.nio.file.Path.of("."), model, thinking,
                    effectiveRole != null ? effectiveRole.getAgentDefaultsFor(requestedAgent) : null);
            String effectiveModel = selection.model();
            String effectiveThinking = selection.thinking();
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("agent", requestedAgent);
            metadata.put("description", desc);
            metadata.put("mode", "managed-terminal");
            metadata.put("role", effectiveRoleName == null ? "" : effectiveRoleName);
            metadata.put("model", effectiveModel == null ? "" : effectiveModel);
            metadata.put("thinking", effectiveThinking == null ? "" : effectiveThinking);
            metadata.put("policy", "FULL_ACCESS");
            metadata.put("fallbacksUsed", "0");
            return ToolResult.success("task:" + requestedAgent, result, metadata);
        } catch (RateLimitException e) {
            System.err.println("\u001B[33m  \u26a0 " + displayName + " rate limited; provider fallback is disabled.\u001B[0m");
            return ToolResult.error(displayName + " is rate limited. No provider fallback was attempted.");
        } catch (Exception e) {
            return ToolResult.error("Subagent execution failed: " + e.getMessage());
        }
    }

    static String runWithCancellation(DirectSubagentRunnerStdio runner, AgentConfig agent,
                                      String prompt, ToolContext context) throws Exception {
        if (context == null) return runner.runSubagent(agent, prompt);
        try (CancellationWatch watch = new CancellationWatch(context, runner)) {
            if (context.isAborted()) throw new InterruptedException("Task cancelled");
            String result = runner.runSubagent(agent, prompt);
            if (context.isAborted()) throw new InterruptedException("Task cancelled");
            return result;
        }
    }

    /** One execution owns one fork and one watcher; no shared runner or global cancel state. */
    static final class CancellationWatch implements AutoCloseable {
        private final ToolContext context;
        private final DirectSubagentRunnerStdio runner;
        final Thread thread;
        private boolean closed;

        CancellationWatch(ToolContext context, DirectSubagentRunnerStdio runner) {
            this.context = context;
            this.runner = runner;
            thread = new Thread(() -> {
                try {
                    while (checkCancellation()) Thread.sleep(50);
                } catch (InterruptedException ignored) {
                    // close() retires the watcher, not the request's worker thread.
                }
            }, "mcp-task-cancellation");
            thread.setDaemon(true);
            thread.start();
        }

        private synchronized boolean checkCancellation() {
            if (closed) return false;
            // Retry while aborted: the runner resets its local flag during startup,
            // and may publish its managed runner only after the first cancellation.
            if (context.isAborted()) runner.cancel();
            return true;
        }

        @Override
        public synchronized void close() {
            closed = true;
            thread.interrupt();
        }
    }

    static boolean isSupportedAgent(String agentName) {
        return agentName != null && SUPPORTED_AGENTS.contains(agentName.toLowerCase(Locale.ROOT));
    }

    static boolean isAgentMissing(String result) {
        return result != null && (result.contains("not found in PATH") || result.contains("not found on PATH"));
    }
}
