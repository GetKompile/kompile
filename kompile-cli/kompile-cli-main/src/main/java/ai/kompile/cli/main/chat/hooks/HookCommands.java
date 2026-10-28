package ai.kompile.cli.main.chat.hooks;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.tools.HooksTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** The human slash-command syntax; configuration is shared with the MCP hooks tool. */
public final class HookCommands {
    private HookCommands() { }
    public static final String HELP = """
            /hooks                         List effective hooks and configuration paths
            /hooks [project|global] list
            /hooks [project|global] get <id>
            /hooks [project|global] add {"event":"PreToolUse","matcher":"^(write|edit)$","command":"/path/check.sh","timeout":30}
            /hooks [project|global] edit <id> {"command":"/path/new-check.sh"}
            /hooks [project|global] enable|disable|delete <id>
            /hooks help                    Show this help
            Commands run automatically with your permissions. Only configure trusted commands.
            Changes are reloaded on the next tool call; editing never runs the command.
            """;

    public static ToolResult execute(String rest, ToolContext context) {
        if (context.getToolRegistry() != null && context.getToolRegistry().isProductMode()) {
            return ToolResult.error("Shell hooks are unavailable in restricted product sessions");
        }
        if (rest.strip().equals("help")) return ToolResult.success(HELP);
        try {
            ObjectNode request = parse(rest);
            ToolContext management = context;
            if (!java.util.Set.of("list", "get").contains(request.path("action").asText())) {
                // This is an explicit human command, not agent-supplied confirmation.
                // Do not block the JLine owner waiting on a second asynchronous prompt.
                if (context.getPermissionService().getEffectiveLevel(context.getAgent(), "hooks")
                        == ai.kompile.cli.main.chat.permission.PermissionService.PermissionLevel.DENY) {
                    return ToolResult.error("Permission denied: hooks");
                }
                management = context.forkForToolExecution();
                management.setAutoApproveAll(true); // this one management call only; workflow/child ceilings still apply
            }
            ToolResult result = new HooksTool().execute(request, management);
            if (rest.isBlank() && !result.isError()) return ToolResult.success(result.getOutput() + "\n" + HELP);
            return result;
        } catch (Exception invalid) {
            return ToolResult.error(invalid.getMessage() + "\n" + HELP);
        }
    }

    public static ObjectNode parse(String rest) throws java.io.IOException {
        String[] parts = rest.strip().split("\\s+", 2);
        ObjectNode request = JsonUtils.standardMapper().createObjectNode();
        if (parts[0].equals("global") || parts[0].equals("project")) {
            request.put("scope", parts[0]);
            parts = (parts.length > 1 ? parts[1] : "list").split("\\s+", 2);
        }
        String action = parts[0].isEmpty() ? "list" : parts[0];
        String args = parts.length > 1 ? parts[1].strip() : "";
        if (action.equals("edit")) action = "update";
        if (action.equals("add") || action.equals("update")) {
            if (action.equals("update")) {
                String[] update = args.split("\\s+", 2);
                if (update.length < 2) throw new IllegalArgumentException("edit requires an id and a JSON object");
                request.put("id", update[0]);
                args = update[1];
            }
            JsonNode fields = JsonUtils.standardMapper().reader()
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(args);
            if (fields == null || !fields.isObject()) throw new IllegalArgumentException(action + " requires a JSON object");
            // Do not allow the JSON body to redirect action, scope, identity or authorization.
            var names = fields.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!java.util.Set.of("event", "matcher", "command", "timeout", "enabled").contains(name)) {
                    throw new IllegalArgumentException("Unknown hook edit field: " + name);
                }
                request.set(name, fields.get(name));
            }
        } else if (java.util.Set.of("get", "delete", "enable", "disable").contains(action)) {
            if (args.isBlank() || args.matches(".*\\s+.*")) throw new IllegalArgumentException(action + " requires one hook id");
            request.put("id", args);
        } else if (!args.isEmpty()) throw new IllegalArgumentException("Unexpected arguments: " + args);
        request.put("action", action).put("confirmed", true);
        return request;
    }
}
