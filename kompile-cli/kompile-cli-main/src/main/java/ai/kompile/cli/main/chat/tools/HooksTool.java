package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.hooks.HookManager;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

/** Shared CLI/MCP management surface; this tool is deliberately exempt from hooks. */
public final class HooksTool implements CliTool {
    private static final Set<String> ACTIONS = Set.of("list", "get", "add", "edit", "update", "delete", "enable", "disable");
    private static final Set<String> FIELDS = Set.of("action", "scope", "id", "event", "matcher", "command", "timeout", "enabled", "confirmed");
    @Override public String id() { return "hooks"; }
    @Override public String permissionKey() { return "read"; }
    @Override public String compactHint() {
        return "Manage command hooks: list|get|add|edit|update|delete|enable|disable; scope project|global. Mutations require explicit user authorization and confirmed=true.";
    }
    @Override public String description() {
        return "Manage Claude-style tool hooks in .kompile/hooks.json (project) or ~/.kompile/hooks.json (global). "
                + "Actions: list, get, add, edit (alias update), delete, enable, disable. Uses stable IDs. "
                + "Events: PreToolUse, PostToolUse, PostToolUseFailure; matcher is a case-sensitive regex against Kompile tool IDs (e.g. ^(write|edit)$), * matches all. "
                + "Command receives JSON on stdin; timeout is seconds, 1..600 (default 60). "
                + "Updates edit supplied fields only. command replaces the handler list with one command; timeout alone changes all handlers. "
                + "Mutations configure future shell execution: require confirmed=true only after explicit user authorization. "
                + "Delegated children cannot mutate hooks. This tool does not run hooks and is exempt from hook execution for recovery. "
                + "Hooks cannot override host permissions or rewrite tool input after supervision.";
    }
    @Override public JsonNode parameterSchema() {
        var schema = JsonUtils.standardMapper().createObjectNode();
        schema.put("type", "object").put("additionalProperties", false);
        var properties = schema.putObject("properties");
        var actions = properties.putObject("action").put("type", "string").putArray("enum");
        ACTIONS.stream().sorted().forEach(actions::add);
        properties.putObject("scope").put("type", "string").putArray("enum").add("project").add("global");
        properties.putObject("id").put("type", "string").put("description", "Stable hook ID; required except list/add");
        properties.putObject("event").put("type", "string").putArray("enum")
                .add("PreToolUse").add("PostToolUse").add("PostToolUseFailure");
        properties.putObject("matcher").put("type", "string").put("description", "Tool-name regex; default *");
        properties.putObject("command").put("type", "string").put("description", "Shell command; required on add");
        properties.putObject("timeout").put("type", "integer").put("minimum", 1).put("maximum", 600);
        properties.putObject("enabled").put("type", "boolean");
        properties.putObject("confirmed").put("type", "boolean").put("description", "Required true for mutations after explicit user authorization");
        schema.putArray("required").add("action");
        return schema;
    }
    @Override public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        if (params == null || !params.isObject()) return ToolResult.error("Expected an object");
        var fields = params.fieldNames();
        while (fields.hasNext()) {
            String name = fields.next();
            if (!FIELDS.contains(name)) return ToolResult.error("Unknown hooks field: " + name);
        }
        if (!params.path("action").isTextual() || !ACTIONS.contains(params.path("action").textValue())) {
            return ToolResult.error("action must be list, get, add, edit/update, delete, enable or disable");
        }
        boolean mutation = !Set.of("list", "get").contains(params.path("action").textValue());
        if (mutation) {
            if (context.isSupervisedChild()) {
                return ToolResult.error("Delegated children cannot change hooks; ask the parent/operator");
            }
            if (!params.path("confirmed").isBoolean() || !params.path("confirmed").booleanValue()) {
                return ToolResult.error("Hook changes authorize future shell execution; explicit user authorization and confirmed=true are required");
            }
        }
        context.checkPermission(mutation ? "hooks" : "read", mutation ? "Configure automatic shell hooks: " + params : "Read tool hook configuration");
        try {
            JsonNode result = new HookManager(context.getWorkingDirectory()).manage(params);
            return ToolResult.success("Hooks: " + params.path("action").textValue(), result.toPrettyString());
        } catch (java.io.IOException | IllegalArgumentException invalid) {
            return ToolResult.error("Hook configuration: " + invalid.getMessage());
        }
    }
}
