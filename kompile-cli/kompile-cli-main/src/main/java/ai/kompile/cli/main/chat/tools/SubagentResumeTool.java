package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.agent.SubagentResumeMetadata;
import ai.kompile.cli.main.chat.agent.SubagentResumeService;
import ai.kompile.cli.main.chat.agent.SubagentRunOptions;
import ai.kompile.cli.main.chat.agent.SubagentRunner;
import ai.kompile.cli.main.chat.roles.RoleManager;
import ai.kompile.cli.main.chat.workflow.WorkflowSessionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/** Provider-neutral discovery and continuation of durable child conversations. */
public final class SubagentResumeTool implements CliTool {
    @FunctionalInterface
    public interface ResumeExecutor {
        ToolResult resume(SubagentResumeMetadata metadata, JsonNode params, ToolContext context) throws Exception;
    }

    private final ObjectMapper mapper;
    private final SubagentResumeService service;
    private final ResumeExecutor executor;

    public SubagentResumeTool(ObjectMapper mapper, SubagentResumeService service, ResumeExecutor executor) {
        this.mapper = mapper;
        this.service = service;
        this.executor = executor;
    }

    /** Reuse task's current role, model and permission policy, never a saved permission grant. */
    public static ResumeExecutor chatExecutor(AgentRegistry agents, SubagentRunner runner, RoleManager roles) {
        return (metadata, params, context) -> {
            ObjectNode request = params.deepCopy();
            request.remove("name");
            request.remove("resumable");
            request.put("background", false); // The resume invocation itself owns detachment.
            request.put("description", "Resume " + metadata.sessionId());
            if (!request.hasNonNull("agent_type") && !request.hasNonNull("role")) {
                AgentConfig original = agents.get(metadata.agentType());
                request.put("agent_type", original != null && original.isSubagent() ? original.getName() : "general");
            }
            return new TaskTool(agents, runner, roles) {
                @Override
                protected String runChild(AgentConfig agent, String prompt, ToolContext parent,
                                          SubagentRunOptions options) throws Exception {
                    return runner.resumeSubagent(agent, metadata.sessionId(), prompt, parent);
                }
            }.execute(request, context);
        };
    }

    /** Vendor CLIs cannot enforce a native caller's filtered tool set. Fail closed. */
    public static void checkCliPermissionCeiling(ToolContext context) throws ToolExecutionException {
        AgentConfig caller = context.getAgent();
        if (caller != null && caller.getEnabledTools() != null && !caller.getEnabledTools().contains("*")) {
            throw new ToolExecutionException("CLI resume cannot preserve a restricted caller's tool set; use native agent_type/role instead", true);
        }
        for (String permission : new String[]{"bash", "edit", "write", "external_directory"}) {
            context.checkPermission(permission, "Resume through a full-access CLI child");
        }
    }

    @Override public String id() { return "subagent_resume"; }
    @Override public String permissionKey() { return "task"; }
    @Override public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.DELEGATION; }
    @Override public boolean isBackgroundable() { return true; }
    @Override public String compactHint() {
        return "Durable subagents: action=list|show|resume; session_id accepts id/name. Resume with prompt and ANY agent/provider/model, not just the original. background=false waits.";
    }
    @Override public String description() {
        return "List, inspect, or resume a durable subagent by session_id or saved name. "
                + "The conversation stays a child of the current caller and keeps its durable id. "
                + "Any configured agent may continue it, including a different vendor CLI via agent, "
                + "or a different native provider/model. No original vendor session or credentials are required. "
                + "Discovers named/opt-in native runs and completed durable MCP tasks. "
                + "action defaults to list; resume requires session_id and prompt. "
                + "Resume backgrounds by default where supported; background=false waits. "
                + "Concurrent writers and recursive subagent delegation are refused.";
    }

    @Override public JsonNode parameterSchema() {
        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("action").put("type", "string").put("default", "list")
                .putArray("enum").add("list").add("show").add("resume");
        props.putObject("session_id").put("type", "string").put("description", "Durable subagent id, exact name, or unique name prefix.");
        props.putObject("prompt").put("type", "string").put("description", "Follow-up task for resume.");
        props.putObject("agent").put("type", "string").put("description", "Target CLI: kompile/general, codex, claude, opencode, gemini, qwen, pi. May differ from the original agent.");
        props.putObject("agent_type").put("type", "string").put("description", "Native chat subagent configuration, instead of a CLI agent or role.");
        for (String field : new String[]{"role", "purpose", "provider", "authentication_method", "model", "thinking"}) {
            props.putObject(field).put("type", "string").put("description", "Request-scoped " + field + "; omit to use the target's configuration.");
        }
        props.putObject("background").put("type", "boolean").put("default", true);
        props.putObject("limit").put("type", "integer").put("minimum", 1).put("maximum", 200).put("default", 20)
                .put("description", "Maximum listing or history entries to return (newest history entries for show).");
        return schema;
    }

    @Override public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        for (String key : new String[]{"action", "session_id", "prompt", "agent", "agent_type", "role", "purpose", "provider", "authentication_method", "model", "thinking"}) {
            if (params.hasNonNull(key) && !params.get(key).isTextual()) return ToolResult.error(key + " must be a string");
        }
        if (params.hasNonNull("background") && !params.get("background").isBoolean()) return ToolResult.error("background must be a boolean");
        if (params.hasNonNull("agent") && params.hasNonNull("agent_type")) return ToolResult.error("Specify agent or agent_type, not both");
        if (params.hasNonNull("limit") && !params.get("limit").isIntegralNumber()) return ToolResult.error("limit must be an integer");
        String action = params.path("action").asText("list");
        if (!java.util.Set.of("list", "show", "resume").contains(action)) return ToolResult.error("Unknown action: " + action);
        int limit = params.path("limit").asInt(20);
        if (limit < 1 || limit > 200) return ToolResult.error("limit must be between 1 and 200");
        context.checkPermission("list".equals(action) || "show".equals(action) ? "read" : permissionKey(), "Subagent " + action);
        if ("list".equals(action)) {
            var runs = service.list();
            return ToolResult.success("subagents", mapper.valueToTree(runs.stream().limit(limit).toList()).toPrettyString(),
                    Map.of("total", runs.size()));
        }
        var resolution = service.resolve(params.path("session_id").asText("").trim());
        if (!resolution.ok()) return ToolResult.error(resolution.error());
        var metadata = resolution.metadata();
        if (!metadata.sessionId().matches("subagent-[A-Za-z0-9_-]+")) return ToolResult.error("Invalid subagent session id");
        if ("show".equals(action)) {
            var entries = service.ledger(metadata.sessionId());
            ObjectNode output = mapper.valueToTree(metadata);
            output.put("historyEntries", entries.size());
            output.put("locked", service.isLocked(metadata.sessionId()));
            var history = output.putArray("history");
            for (var entry : entries.subList(Math.max(0, entries.size() - limit), entries.size())) {
                String text = entry.content == null ? "" : entry.content;
                history.addObject().put("type", entry.type.name()).put("content", text.length() > 2000 ? text.substring(0, 2000) + "…" : text);
            }
            return ToolResult.success("subagent:" + metadata.sessionId(), output.toPrettyString());
        }
        String prompt = params.path("prompt").asText("");
        if (prompt.isBlank()) return ToolResult.error("prompt is required for resume");
        if (WorkflowSessionContext.enforcementFor(context) != null) {
            return ToolResult.error("Durable subagent resume is unavailable inside a turn-scoped workflow team; delegate a new task through the team.");
        }
        if (service.isLocked(metadata.sessionId())) return ToolResult.error("Subagent " + metadata.sessionId() + " is live or being resumed elsewhere");
        if (executor == null) return ToolResult.error("Subagent continuation is unavailable in this runtime");
        boolean requestedBackground = params.path("background").asBoolean(true);
        boolean backgrounded = requestedBackground && context.requestSelfBackground();
        try {
            ToolResult result = executor.resume(metadata, params, context);
            if (result.isError()) return result;
            Map<String, Object> details = new LinkedHashMap<>(result.getMetadata());
            details.put("sessionId", metadata.sessionId());
            details.put("resumable", true);
            details.put("backgroundRequested", requestedBackground);
            details.put("backgrounded", backgrounded);
            return ToolResult.success("subagent:" + metadata.sessionId(), result.getOutput(), details);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            return ToolResult.error("Subagent resume failed: " + failure.getMessage());
        }
    }
}
