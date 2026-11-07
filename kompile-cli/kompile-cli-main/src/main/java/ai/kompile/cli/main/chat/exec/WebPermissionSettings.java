package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.agent.SubagentDelegationGuard;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.permission.PermissionService.PermissionLevel;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.workflow.WorkflowSessionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

import static ai.kompile.cli.main.chat.exec.WebCommandResolver.Status.*;

/** Per-web-chat overrides. Runtime decisions always remain in the existing PermissionService. */
public final class WebPermissionSettings {
    private WebPermissionSettings() { }

    static Path path(String session) {
        if (session == null || session.length() > 200 || !session.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Session required");
        return KompileHome.homeDirectory().toPath().resolve("conversations").resolve(session + ".web-permissions.json");
    }

    private static void safe(Path file) throws IOException {
        Path home = KompileHome.homeDirectory().toPath().toAbsolutePath().normalize();
        if (Files.exists(file.getParent()) && !file.getParent().toRealPath().startsWith(home.toRealPath())) throw new IOException("Unsafe permission store");
        if (Files.isSymbolicLink(file) || Files.isSymbolicLink(file.resolveSibling(file.getFileName() + ".lock"))) throw new IOException("Unsafe permission store");
    }

    private static Map<String, PermissionLevel> read(String session, Path directory) throws IOException {
        Path file = path(session); safe(file);
        if (!Files.exists(file)) return Map.of();
        if (Files.size(file) > 65_536) throw new IOException("Permission store too large");
        JsonNode root = JsonUtils.standardMapper().reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(Files.readAllBytes(file));
        if (root == null || !root.isObject() || !root.path("version").isIntegralNumber() || root.path("version").asInt() != 1
                || !directory.toRealPath().toString().equals(root.path("workspace").asText())) throw new IOException("Permission store scope mismatch");
        return levels(root.path("overrides"));
    }

    private static Map<String, PermissionLevel> levels(JsonNode node) {
        if (!node.isObject()) throw new IllegalArgumentException("Expected overrides");
        Map<String, PermissionLevel> result = new TreeMap<>();
        Set<String> known = new PermissionService().defaultLevels().keySet();
        node.fields().forEachRemaining(entry -> {
            if (!known.contains(entry.getKey()) || !entry.getValue().isTextual()) throw new IllegalArgumentException("Unknown permission");
            result.put(entry.getKey(), PermissionLevel.valueOf(entry.getValue().asText().toUpperCase(Locale.ROOT)));
        });
        return result;
    }

    /** Fail closed on a malformed/foreign store, rather than silently running with default access. */
    public static void apply(String session, Path directory, PermissionService service) {
        try {
            Map<String, PermissionLevel> saved = read(session, directory);
            // Choosing a chat policy (even Restore defaults) must not be silently defeated
            // by a launcher-wide skip-prompts flag. Chats without a saved policy stay unchanged.
            if (Files.exists(path(session))) service.setAutoApproveAll(false);
            saved.forEach(service::setUserOverride);
        }
        catch (IOException | RuntimeException error) { throw new IllegalStateException("Web permission settings could not be loaded safely"); }
    }

    static boolean canManage(ToolContext context, String key, boolean managementAllowed) throws IOException {
        if (!managementAllowed || context == null || context.getAgent() == null || context.isSupervisedChild()
                || SubagentDelegationGuard.isSubagent() || SubagentDelegationGuard.blocks(key)
                || (context.getToolRegistry() != null && context.getToolRegistry().isProductMode())
                || WorkflowSessionContext.enforcementFor(context) != null
                || (context.getSessionId() != null && WorkflowSessionContext.recorded(context.getSessionId(), context.getWorkingDirectory()) != null)) return false;
        Set<String> enabled = context.getAgent().getEnabledTools();
        return enabled != null && (enabled.contains("*") || enabled.contains(key.equals("permissions") ? "write" : key))
                && context.getPermissionService().getEffectiveLevel(context.getAgent(), key) != PermissionLevel.DENY;
    }

    public static ObjectNode snapshot(ToolContext context, boolean managementAllowed) throws Exception {
        if (context == null || context.getAgent() == null) throw new IllegalArgumentException("Principal required");
        // Like the terminal permission manager, viewing your own policy is a control-plane
        // action: a user must still be able to restore a self-imposed read denial.
        Map<String, PermissionLevel> saved = read(context.getSessionId(), context.getWorkingDirectory());
        PermissionService preview = new PermissionService(); saved.forEach(preview::setUserOverride);
        ObjectNode data = JsonUtils.standardMapper().createObjectNode().put("menu", "permissions").put("scope", "session")
                .put("canManage", canManage(context, "permissions", managementAllowed));
        var rows = data.putArray("rows");
        for (var entry : new TreeMap<>(preview.defaultLevels()).entrySet()) {
            String key = entry.getKey();
            PermissionLevel role = context.getAgent().getPermissionOverrides() == null ? null : context.getAgent().getPermissionOverrides().get(key);
            ObjectNode row = rows.addObject().put("key", key).put("defaultLevel", entry.getValue().name().toLowerCase(Locale.ROOT))
                    .put("effectiveLevel", preview.getEffectiveLevel(context.getAgent(), key).name().toLowerCase(Locale.ROOT));
            row.put("overrideLevel", saved.containsKey(key) ? saved.get(key).name().toLowerCase(Locale.ROOT) : null);
            row.put("roleLevel", role == null ? null : role.name().toLowerCase(Locale.ROOT));
        }
        data.put("notes", "Applies to following web runs in this chat. Saving a policy, including Restore defaults, disables launcher-wide skip-prompts for this chat. Current role rules take precedence. ASK requires an available approval channel; it never means automatic permission.");
        return data;
    }

    public static WebCommandResolver.Resolution resolve(String arguments, ToolContext context, boolean managementAllowed) {
        String rest = arguments == null ? "" : arguments.strip();
        try {
            if (rest.isEmpty() || rest.equals("list") || rest.equals("status")) return new WebCommandResolver.Resolution(INTERACTION_REQUIRED, "/permissions", "Web chat permission settings loaded.", null, snapshot(context, managementAllowed));
            if (!rest.startsWith("save ") || !canManage(context, "permissions", managementAllowed)) throw new IllegalArgumentException("Management unavailable");
            JsonNode request = JsonUtils.standardMapper().reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(rest.substring(5));
            if (request == null || !request.isObject() || request.size() != 2 || !request.has("overrides")
                    || !request.path("confirmed").isBoolean() || !request.path("confirmed").booleanValue()) throw new IllegalArgumentException("Confirmation required");
            Map<String, PermissionLevel> replacement = levels(request.get("overrides"));
            save(context.getSessionId(), context.getWorkingDirectory(), replacement);
            ObjectNode data;
            try { data = snapshot(context, managementAllowed); }
            catch (Exception unreadable) { data = JsonUtils.standardMapper().createObjectNode().put("menu", "permissions")
                    .put("canManage", false).put("error", "Saved, but the permission preview could not be reloaded."); }
            return new WebCommandResolver.Resolution(COMPLETED, "/permissions", "Permission overrides saved for following web turns; role rules remain authoritative.", null, data);
        } catch (Exception rejected) {
            return new WebCommandResolver.Resolution(INVALID, "/permissions", "Permission settings were refused; nothing was saved. Use list or save with explicit confirmation and valid known permission levels.", null);
        }
    }

    private static synchronized void save(String session, Path directory, Map<String, PermissionLevel> replacement) throws IOException {
        Path file = path(session); safe(file);
        // Validate an existing scope before replacement; never overwrite another workspace's chat.
        read(session, directory);
        Files.createDirectories(file.getParent()); safe(file);
        Path lock = file.resolveSibling(file.getFileName() + ".lock");
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             var ignored = channel.lock()) {
            safe(file); read(session, directory);
            ObjectNode root = JsonUtils.standardMapper().createObjectNode().put("version", 1).put("workspace", directory.toRealPath().toString());
            ObjectNode overrides = root.putObject("overrides");
            replacement.forEach((key, value) -> overrides.put(key, value.name().toLowerCase(Locale.ROOT)));
            Path temporary = Files.createTempFile(file.getParent(), ".web-permissions-", ".json");
            try {
                JsonUtils.standardMapper().writeValue(temporary.toFile(), root);
                try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temporary); }
        }
    }
}
