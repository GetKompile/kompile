package ai.kompile.cli.main.chat.hooks;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** File-backed, transport-independent tool-hook configuration. Never executes commands. */
public final class HookManager {
    public static final List<String> EVENTS = List.of("PreToolUse", "PostToolUse", "PostToolUseFailure");
    private static final ConcurrentHashMap<Path, Object> LOCKS = new ConcurrentHashMap<>();
    private final Path projectFile;
    private final Path globalFile;

    public record Command(String command, int timeout) { }
    public record Hook(String scope, String event, String id, String matcher, boolean enabled,
                       List<Command> commands) {
        public boolean matches(String name) {
            return enabled && (matcher.isEmpty() || matcher.equals("*") || Pattern.compile(matcher).matcher(name).find());
        }
    }

    public HookManager(Path workingDirectory) {
        this(workingDirectory, Path.of(System.getProperty("user.home")));
    }

    public HookManager(Path workingDirectory, Path userHome) {
        projectFile = workingDirectory.toAbsolutePath().normalize().resolve(".kompile/hooks.json");
        globalFile = userHome.toAbsolutePath().normalize().resolve(".kompile/hooks.json");
    }

    public Path path(String scope) {
        return switch (scope) {
            case "project" -> projectFile;
            case "global" -> globalFile;
            default -> throw new IllegalArgumentException("scope must be project or global");
        };
    }

    /** A consistent snapshot for one invocation, global hooks first. */
    public List<Hook> load() throws IOException {
        var hooks = new ArrayList<>(parse(read(globalFile), "global"));
        if (!projectFile.equals(globalFile)) hooks.addAll(parse(read(projectFile), "project"));
        return List.copyOf(hooks);
    }

    public JsonNode manage(JsonNode request) throws IOException {
        String requestedAction = text(request, "action", "list");
        String action = requestedAction.equals("edit") ? "update" : requestedAction;
        String scope = text(request, "scope", "project");
        Path file = path(scope);
        if (action.equals("list")) {
            ObjectNode result = JsonUtils.standardMapper().createObjectNode();
            result.put("projectPath", projectFile.toString()).put("globalPath", globalFile.toString());
            ArrayNode entries = result.putArray("hooks");
            List<Hook> hooks = request.has("scope") ? parse(read(file), scope) : load();
            hooks.forEach(hook -> entries.add(describe(hook)));
            return result;
        }
        if (action.equals("get")) {
            String id = requiredText(request, "id");
            return describe(parse(read(file), scope).stream().filter(h -> h.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown hook in " + scope + ": " + id)));
        }
        if (!Set.of("add", "update", "delete", "enable", "disable").contains(action)) {
            throw new IllegalArgumentException("Unknown hooks action: " + action);
        }
        // Coordinate both threads and separate MCP hosts. Always re-read under the lock.
        synchronized (LOCKS.computeIfAbsent(file, ignored -> new Object())) {
            Files.createDirectories(file.getParent());
            try (var channel = FileChannel.open(file.resolveSibling("hooks.json.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = channel.lock()) {
                ObjectNode root = read(file);
                List<Hook> existing = parse(root, scope);
                ObjectNode events = root.has("hooks") ? (ObjectNode) root.get("hooks") : root.putObject("hooks");
                String id = action.equals("add") ? text(request, "id", UUID.randomUUID().toString()) : requiredText(request, "id");
                if (id.isBlank()) throw new IllegalArgumentException("id must not be blank");
                Hook found = existing.stream().filter(h -> h.id().equals(id)).findFirst().orElse(null);
                if (action.equals("add") && found != null) throw new IllegalArgumentException("Hook id already exists: " + id);
                if (!action.equals("add") && found == null) throw new IllegalArgumentException("Unknown hook in " + scope + ": " + id);
                ObjectNode entry;
                String event;
                if (found != null) {
                    ArrayNode group = (ArrayNode) events.get(found.event());
                    int index = indexOf(group, found.event(), id);
                    entry = ((ObjectNode) group.get(index)).deepCopy();
                    group.remove(index);
                    event = found.event();
                } else {
                    entry = JsonUtils.standardMapper().createObjectNode();
                    entry.put("matcher", "*").put("enabled", true);
                    event = requiredText(request, "event");
                }
                entry.put("id", id);
                if (request.has("event")) event = requiredText(request, "event");
                if (!EVENTS.contains(event)) throw new IllegalArgumentException("Unsupported hook event: " + event);
                if (request.has("matcher")) entry.put("matcher", text(request, "matcher", "*"));
                if (request.has("enabled")) entry.put("enabled", bool(request, "enabled", true));
                if (action.equals("enable") || action.equals("disable")) entry.put("enabled", action.equals("enable"));
                if (request.has("command") || action.equals("add")) {
                    String command = requiredText(request, "command");
                    int timeout = timeout(request, "timeout", found == null ? 60 : found.commands().get(0).timeout());
                    entry.putArray("hooks").addObject().put("type", "command").put("command", command).put("timeout", timeout);
                } else if (request.has("timeout")) {
                    int timeout = timeout(request, "timeout", 60);
                    for (JsonNode command : entry.withArray("hooks")) ((ObjectNode) command).put("timeout", timeout);
                }
                if (!action.equals("delete")) {
                    ArrayNode group = events.has(event) ? (ArrayNode) events.get(event) : events.putArray(event);
                    // Updates preserve relative order within their event.
                    if (found != null && found.event().equals(event)) {
                        ArrayNode original = (ArrayNode) read(file).path("hooks").path(event);
                        group.insert(indexOf(original, event, id), entry);
                    } else group.add(entry);
                }
                parse(root, scope); // Validate the complete proposed file before replacing it.
                save(file, root);
                ObjectNode result = JsonUtils.standardMapper().createObjectNode();
                result.put("action", action).put("id", id).put("scope", scope).put("path", file.toString());
                if (!action.equals("delete")) result.set("hook", describe(parse(root, scope).stream()
                        .filter(h -> h.id().equals(id)).findFirst().orElseThrow()));
                return result;
            }
        }
    }

    private static int indexOf(ArrayNode entries, String event, String id) {
        for (int i = 0; i < entries.size(); i++) if (id(entries.get(i), event).equals(id)) return i;
        throw new IllegalArgumentException("Unknown hook: " + id);
    }

    private static String id(JsonNode entry, String event) {
        if (entry.has("id")) return requiredText(entry, "id");
        // Hand-written Claude-shaped entries need no Kompile-specific id field.
        return UUID.nameUUIDFromBytes((event + ":" + entry).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static List<Hook> parse(ObjectNode root, String scope) {
        List<Hook> result = new ArrayList<>();
        if (!root.has("hooks")) return result;
        JsonNode events = root.get("hooks");
        if (!events.isObject()) throw new IllegalArgumentException("hooks must be an event object");
        Set<String> ids = new HashSet<>();
        var fields = events.fieldNames();
        while (fields.hasNext()) {
            String event = fields.next();
            if (!EVENTS.contains(event)) throw new IllegalArgumentException("Unsupported hook event: " + event);
            if (!events.get(event).isArray()) throw new IllegalArgumentException(event + " must be an array");
        }
        for (String event : EVENTS) for (JsonNode entry : events.path(event)) {
            if (!entry.isObject()) throw new IllegalArgumentException("Hook matcher must be an object");
            String id = id(entry, event);
            if (!ids.add(id)) throw new IllegalArgumentException("Duplicate hook id in " + scope + ": " + id);
            String matcher = text(entry, "matcher", "*");
            if (!matcher.isEmpty() && !matcher.equals("*")) Pattern.compile(matcher);
            boolean enabled = bool(entry, "enabled", true);
            JsonNode commands = entry.path("hooks");
            if (!commands.isArray() || commands.isEmpty()) throw new IllegalArgumentException("hooks handlers must be a nonempty array");
            List<Command> handlers = new ArrayList<>();
            for (JsonNode command : commands) {
                if (!command.isObject() || !text(command, "type", "command").equals("command")) {
                    throw new IllegalArgumentException("Only synchronous command hooks are supported");
                }
                if (command.has("async") && bool(command, "async", false)) throw new IllegalArgumentException("Async hooks are not supported");
                handlers.add(new Command(requiredText(command, "command"), timeout(command, "timeout", 60)));
            }
            result.add(new Hook(scope, event, id, matcher, enabled, List.copyOf(handlers)));
        }
        return List.copyOf(result);
    }

    private static ObjectNode describe(Hook hook) {
        ObjectNode result = JsonUtils.standardMapper().createObjectNode();
        result.put("id", hook.id()).put("scope", hook.scope()).put("event", hook.event())
                .put("matcher", hook.matcher()).put("enabled", hook.enabled());
        ArrayNode commands = result.putArray("hooks");
        hook.commands().forEach(command -> commands.addObject().put("type", "command")
                .put("command", command.command()).put("timeout", command.timeout()));
        return result;
    }

    private static ObjectNode read(Path file) throws IOException {
        if (!Files.exists(file)) return JsonUtils.standardMapper().createObjectNode();
        if (Files.size(file) > 1_048_576) throw new IOException("Hook configuration exceeds 1 MiB: " + file);
        JsonNode root = JsonUtils.standardMapper().reader()
                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(Files.readString(file));
        if (root == null || !root.isObject()) throw new IOException("Hook configuration must be an object: " + file);
        return (ObjectNode) root;
    }

    private static void save(Path file, ObjectNode root) throws IOException {
        Path temp = Files.createTempFile(file.getParent(), ".hooks-", ".json");
        try {
            Files.writeString(temp, root.toPrettyString() + "\n");
            try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    private static String text(JsonNode node, String field, String fallback) {
        if (!node.has(field)) return fallback;
        if (!node.get(field).isTextual()) throw new IllegalArgumentException(field + " must be a string");
        return node.get(field).textValue();
    }
    private static String requiredText(JsonNode node, String field) {
        String value = text(node, field, "");
        if (value.isBlank()) throw new IllegalArgumentException(field + " is required and must not be blank");
        return value;
    }
    private static boolean bool(JsonNode node, String field, boolean fallback) {
        if (!node.has(field)) return fallback;
        if (!node.get(field).isBoolean()) throw new IllegalArgumentException(field + " must be boolean");
        return node.get(field).booleanValue();
    }
    private static int timeout(JsonNode node, String field, int fallback) {
        if (!node.has(field)) return fallback;
        JsonNode value = node.get(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1 || value.intValue() > 600) {
            throw new IllegalArgumentException("timeout must be an integer between 1 and 600 seconds");
        }
        return value.intValue();
    }
}
