package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.hooks.HookCommands;
import ai.kompile.cli.main.chat.hooks.HookManager;
import ai.kompile.cli.main.chat.tools.HooksTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static ai.kompile.cli.main.chat.exec.WebCommandResolver.Status.*;

/** Explicit human hook management; saving configuration never executes a hook command. */
public final class WebHooksSettings {
    private WebHooksSettings() { }

    private static void safe(HookManager manager, String scope, Path directory) throws IOException {
        Path file = manager.path(scope);
        Path root = "project".equals(scope) ? directory.toRealPath()
                : Path.of(System.getProperty("user.home")).toRealPath();
        if (Files.isSymbolicLink(file) || (Files.exists(file.getParent()) && !file.getParent().toRealPath().startsWith(root)))
            throw new IOException("Unsafe hook store");
    }

    public static ObjectNode snapshot(ToolContext context, boolean managementAllowed) throws Exception {
        if (context == null || context.getAgent() == null) throw new IllegalArgumentException("Principal required");
        context.checkPermission("read", "Read tool hook configuration");
        HookManager manager = new HookManager(context.getWorkingDirectory());
        safe(manager, "project", context.getWorkingDirectory()); safe(manager, "global", context.getWorkingDirectory());
        JsonNode listed = manager.manage(JsonUtils.standardMapper().createObjectNode().put("action", "list"));
        ObjectNode data = JsonUtils.standardMapper().createObjectNode().put("menu", "hooks")
                .put("canManage", WebPermissionSettings.canManage(context, "hooks", managementAllowed));
        data.set("hooks", listed.path("hooks"));
        data.set("events", JsonUtils.standardMapper().valueToTree(HookManager.EVENTS));
        data.put("notes", "Hooks run shell commands automatically on future tool calls. Only save trusted commands. Editing this configuration does not run them. Global scope affects other projects.");
        return data;
    }

    public static WebCommandResolver.Resolution resolve(String arguments, ToolContext context, boolean managementAllowed) {
        String rest = arguments == null ? "" : arguments.strip();
        try {
            if (rest.isEmpty() || rest.equals("list") || rest.equals("status")) return new WebCommandResolver.Resolution(INTERACTION_REQUIRED,
                    "/hooks", "Hook configuration loaded; no commands were executed.", null, snapshot(context, managementAllowed));
            if (rest.equals("help")) return new WebCommandResolver.Resolution(COMPLETED, "/hooks", HookCommands.HELP
                    + "\nWeb changes use /hooks apply <JSON> with confirmed:true.", null);
            if (!rest.startsWith("apply ") || !WebPermissionSettings.canManage(context, "hooks", managementAllowed)) throw new IllegalArgumentException("Management unavailable");
            JsonNode request = JsonUtils.standardMapper().reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(rest.substring(6));
            if (request == null || !request.isObject() || !request.path("action").isTextual()
                    || !Set.of("add", "edit", "update", "delete", "enable", "disable").contains(request.path("action").asText())
                    || !request.path("confirmed").isBoolean() || !request.path("confirmed").booleanValue()) throw new IllegalArgumentException("Explicit confirmation required");
            String scope = request.path("scope").asText("project");
            HookManager manager = new HookManager(context.getWorkingDirectory()); safe(manager, scope, context.getWorkingDirectory());
            ToolContext management = context.forkForToolExecution();
            management.setAutoApproveAll(true); // only this explicitly confirmed human call, after role DENY checks
            var result = new HooksTool().execute(request, management);
            if (result.isError()) throw new IllegalArgumentException("Rejected hook settings");
            // The write has succeeded: never call a post-write read failure "nothing saved".
            ObjectNode data;
            try { data = snapshot(context, managementAllowed); }
            catch (Exception unreadable) { data = JsonUtils.standardMapper().createObjectNode().put("menu", "hooks")
                    .put("canManage", false).put("error", "Saved, but the hook list could not be reloaded."); }
            return new WebCommandResolver.Resolution(COMPLETED, "/hooks", "Hook configuration saved; no hook command was executed.", null, data);
        } catch (Exception refused) {
            return new WebCommandResolver.Resolution(INVALID, "/hooks", "Hook settings were refused. No change was made. Check permissions, scope, fields and explicit confirmation; no commands were executed.", null);
        }
    }
}
