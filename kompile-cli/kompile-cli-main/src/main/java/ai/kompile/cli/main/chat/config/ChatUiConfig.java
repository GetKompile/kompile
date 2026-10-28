package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Presentation preferences only: never provider authentication or model context. */
public final class ChatUiConfig {
    private static final ObjectMapper JSON = JsonUtils.standardMapper();
    private static final String FILE = "chat-ui-config.json";
    private static final Set<String> GROUPS = Set.of("tools", "processes", "subagents", "todos", "sources", "custom");
    private final ObjectNode overrides;
    private final ObjectNode changes;
    private ObjectNode resolved;
    private long revision;
    private Path persistencePath;
    public long revision() { return revision; }

    private ChatUiConfig(ObjectNode overrides, ObjectNode changes) {
        this.overrides = overrides;
        this.changes = changes;
    }

    public record Policy(String inline, String sidebar) { }
    public static ChatUiConfig classic() { return new ChatUiConfig(JSON.createObjectNode(), JSON.createObjectNode()); }
    public ChatUiConfig copy() {
        ChatUiConfig copy = new ChatUiConfig(overrides.deepCopy(), changes.deepCopy());
        copy.persistencePath = persistencePath;
        return copy;
    }

    /** Keep explicit scoped preferences scoped; otherwise remember the user's choice globally. */
    public Path persistencePath() { return persistencePath == null ? globalPath() : persistencePath; }
    public void saveDefault() throws IOException { saveTo(persistencePath()); }
    public static Path globalPath() { return KompileHome.homeDirectory().toPath().resolve(FILE); }
    public static Path projectPath(Path project) { return project.toAbsolutePath().normalize().resolve(".kompile").resolve(FILE); }
    public static Path sessionPath(String session) {
        if (session == null || !session.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid session id");
        return KompileHome.homeDirectory().toPath().resolve("conversations").resolve(session + "." + FILE);
    }

    public static ChatUiConfig loadEffective(Path project, String session) {
        List<Path> paths = new ArrayList<>();
        paths.add(globalPath());
        if (project != null) paths.add(projectPath(project));
        if (session != null) paths.add(sessionPath(session));
        return load(paths);
    }

    /** Also useful for isolated configuration tests without changing the user's home. */
    public static ChatUiConfig load(List<Path> layers) {
        ObjectNode root = JSON.createObjectNode();
        Path persistencePath = layers.isEmpty() ? null : layers.get(0);
        for (Path path : layers) {
            if (!Files.exists(path)) continue;
            try {
                ObjectNode candidate = root.deepCopy();
                merge(candidate, readValidated(path));
                ChatUiConfig check = new ChatUiConfig(candidate, JSON.createObjectNode());
                check.validateBindings();
                root = candidate;
                persistencePath = path;
            }
            catch (IOException | IllegalArgumentException e) {
                System.err.println("Warning: ignoring invalid UI preferences " + path + ": " + e.getMessage());
            }
        }
        ChatUiConfig config = new ChatUiConfig(root, JSON.createObjectNode());
        config.persistencePath = persistencePath;
        return config;
    }

    private static ObjectNode readValidated(Path path) throws IOException {
        JsonNode value = JSON.readTree(path.toFile());
        if (!(value instanceof ObjectNode object)) throw new IllegalArgumentException("Expected a JSON object");
        validate(object, "");
        return object;
    }

    private ObjectNode effective() {
        if (resolved == null) {
            resolved = defaults(getPreset());
            merge(resolved, overrides);
            merge(resolved, changes);
        }
        return resolved;
    }

    private static ObjectNode defaults(String preset) {
        ObjectNode root = JSON.createObjectNode();
        root.put("preset", preset);
        root.putObject("images").put("protocol", "auto");
        ObjectNode layout = root.putObject("layout");
        layout.put("mode", "classic".equals(preset) ? "inline" : "auto");
        layout.put("sidebarWidthPercent", 35);
        layout.put("minChatColumns", 60);
        layout.put("narrowFallback", "drawer");
        ObjectNode sidebar = root.putObject("sidebar");
        sidebar.putArray("sections").add("tools").add("processes").add("subagents").add("todos").add("sources").add("custom");
        sidebar.put("wrap", true);
        sidebar.put("visible", true);
        sidebar.put("follow", "when-at-bottom");
        sidebar.put("maxItems", 256);
        sidebar.put("maxChars", 2 * 1024 * 1024);
        ObjectNode bindings = root.putObject("bindings");
        bindings.put("toggle", "ctrl-x s");
        bindings.put("focus", "ctrl-x r");
        ObjectNode content = root.putObject("content");
        for (String kind : List.of("assistant", "thinking")) putPolicy(content, kind, "full", "off");
        for (String kind : List.of("tool", "process", "subagent", "todos", "sources", "custom")) {
            putPolicy(content, kind, "focused".equals(preset) ? "none" : "full", "classic".equals(preset) ? "off" : "collapsed");
        }
        putPolicy(content, "reminders", "summary", "collapsed");
        putPolicy(content, "notification", "summary", "collapsed");
        putPolicy(content, "error", "classic".equals(preset) ? "full" : "summary", "classic".equals(preset) ? "off" : "expanded");
        return root;
    }

    private static void putPolicy(ObjectNode root, String kind, String inline, String sidebar) {
        root.putObject(kind).put("inline", inline).put("sidebar", sidebar);
    }

    public String getPreset() { return changes.path("preset").asText(overrides.path("preset").asText("classic")); }
    public String getImageProtocol() { return effective().path("images").path("protocol").asText(); }
    public String getLayoutMode() { return effective().path("layout").path("mode").asText(); }
    public int getSidebarWidthPercent() { return effective().path("layout").path("sidebarWidthPercent").asInt(); }
    public int getMinChatColumns() { return effective().path("layout").path("minChatColumns").asInt(); }
    public boolean isSidebarWrap() { return effective().path("sidebar").path("wrap").asBoolean(); }
    public boolean isSidebarVisible() { return effective().path("sidebar").path("visible").asBoolean(); }
    public String getSidebarFollow() { return effective().path("sidebar").path("follow").asText(); }
    public int getSidebarMaxItems() { return effective().path("sidebar").path("maxItems").asInt(); }
    public int getSidebarMaxChars() { return effective().path("sidebar").path("maxChars").asInt(); }
    public String getBinding(String action) { return effective().path("bindings").path(action).asText(); }
    public List<String> getSidebarSections() {
        List<String> groups = new ArrayList<>();
        effective().path("sidebar").path("sections").forEach(node -> groups.add(node.asText()));
        return List.copyOf(groups);
    }
    public List<String> getConfiguredTools() {
        List<String> names = new ArrayList<>();
        effective().path("toolOverrides").fieldNames().forEachRemaining(names::add);
        names.sort(String::compareTo);
        return List.copyOf(names);
    }

    public boolean hasContentOverrides() {
        return overrides.has("content") || changes.has("content") || overrides.has("toolOverrides") || changes.has("toolOverrides");
    }

    public Policy policy(String kind, String toolName) {
        ObjectNode root = effective();
        JsonNode rule = root.path("content").path(kind == null ? "" : kind);
        String inline = rule.path("inline").asText("full");
        String sidebar = rule.path("sidebar").asText("off");
        if (toolName != null && !toolName.isBlank()) {
            String clean = toolName.replaceFirst("^mcp__[^_]+__", "").toLowerCase(Locale.ROOT);
            JsonNode tool = root.path("toolOverrides").path(clean);
            inline = tool.path("inline").asText(inline);
            sidebar = tool.path("sidebar").asText(sidebar);
        }
        return new Policy(inline, sidebar);
    }

    /** Sections inherit the parent rule unless explicitly configured. */
    public Policy sectionPolicy(String kind, String toolName, Policy parent) {
        ObjectNode root = effective();
        JsonNode rule = root.path("content").path(kind);
        return new Policy(rule.path("inline").asText(parent.inline()), rule.path("sidebar").asText(parent.sidebar()));
    }

    public void applyPreset(String preset) {
        set("preset", preset);
        set("layout.mode", "classic".equals(preset) ? "inline" : "auto");
    }

    public void set(String path, String value) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("Missing setting path");
        JsonNode node;
        if (path.equals("sidebar.sections")) {
            var array = JSON.createArrayNode();
            if (!value.isBlank()) for (String group : value.split(",")) array.add(group.trim());
            node = array;
        } else if (path.equals("sidebar.wrap") || path.equals("sidebar.visible")) {
            if (!Set.of("true", "false").contains(value)) throw new IllegalArgumentException(path + " must be true or false");
            node = JSON.getNodeFactory().booleanNode(Boolean.parseBoolean(value));
        } else if (path.equals("layout.sidebarWidthPercent") || path.equals("layout.minChatColumns")
                || path.equals("sidebar.maxItems") || path.equals("sidebar.maxChars")) {
            try { node = JSON.getNodeFactory().numberNode(Integer.parseInt(value)); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Expected integer for " + path); }
        } else node = JSON.getNodeFactory().textNode(value);
        validate(node, path);
        if (path.startsWith("bindings.")) {
            String other = path.equals("bindings.toggle") ? "focus" : "toggle";
            if (value.equals(getBinding(other))) throw new IllegalArgumentException("UI toggle and focus need different bindings");
        }
        String[] parts = path.split("\\.");
        ObjectNode target = changes;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonNode child = target.path(parts[i]);
            target = child instanceof ObjectNode object ? object : target.putObject(parts[i]);
        }
        target.set(parts[parts.length - 1], node);
        resolved = null;
        revision++;
    }

    public void save(String scope, Path project, String session) throws IOException {
        Path path = switch (scope) {
            case "global" -> globalPath();
            case "project" -> { if (project == null) throw new IllegalArgumentException("No project directory"); yield projectPath(project); }
            case "session" -> sessionPath(session);
            default -> throw new IllegalArgumentException("Scope must be session, project or global");
        };
        saveTo(path);
    }

    public void saveTo(Path path) throws IOException {
        ObjectNode stored = Files.exists(path) ? readValidated(path) : JSON.createObjectNode();
        merge(stored, changes);
        Path absolute = path.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Path temporary = Files.createTempFile(absolute.getParent(), ".chat-ui-", ".tmp");
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), stored);
            try { Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
        persistencePath = absolute;
    }

    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        lines.add("Preset: " + getPreset() + " · layout: " + getLayoutMode());
        lines.add("Sidebar: " + getSidebarWidthPercent() + "% · minimum chat " + getMinChatColumns() + " columns · wrap " + isSidebarWrap());
        lines.add("Sections: " + String.join(", ", getSidebarSections()) + " · follow " + getSidebarFollow());
        lines.add("Retention: " + getSidebarMaxItems() + " completed cards · " + getSidebarMaxChars() + " presentation characters");
        for (String kind : List.of("assistant", "thinking", "tool", "error", "process", "subagent", "todos", "sources", "custom", "reminders", "notification")) {
            Policy p = policy(kind, null);
            lines.add(kind + ": inline=" + p.inline() + ", sidebar=" + p.sidebar());
        }
        lines.add("Keys: toggle " + getBinding("toggle") + " · focus " + getBinding("focus"));
        lines.add("Sidebar visible: " + isSidebarVisible() + " · preferences: " + persistencePath());
        lines.add("Image previews: " + getImageProtocol() + " (automatic capability detection; Kitty graphics)");
        return List.copyOf(lines);
    }

    private void validateBindings() {
        if (getBinding("toggle").equals(getBinding("focus"))) throw new IllegalArgumentException("UI toggle and focus need different bindings");
    }

    private static void merge(ObjectNode target, ObjectNode source) {
        source.fields().forEachRemaining(entry -> {
            JsonNode current = target.get(entry.getKey());
            if (current instanceof ObjectNode object && entry.getValue() instanceof ObjectNode child) merge(object, child);
            else target.set(entry.getKey(), entry.getValue().deepCopy());
        });
    }

    private static void validate(JsonNode value, String path) {
        if (value.isObject()) {
            boolean allowed = path.isEmpty() || Set.of("layout", "sidebar", "content", "toolOverrides", "bindings", "images").contains(path)
                    || path.matches("(?:content|toolOverrides)\\.[A-Za-z0-9_-]+");
            if (!allowed) throw new IllegalArgumentException("Unknown UI setting " + path);
            value.fields().forEachRemaining(entry -> validate(entry.getValue(), path.isEmpty() ? entry.getKey() : path + "." + entry.getKey()));
            return;
        }
        if (path.equals("sidebar.sections")) {
            if (!value.isArray()) throw new IllegalArgumentException("sections must be an array");
            List<String> seen = new ArrayList<>();
            for (JsonNode node : value) {
                if (!node.isTextual() || !GROUPS.contains(node.asText()) || seen.contains(node.asText())) throw new IllegalArgumentException("Invalid or duplicate sidebar section " + node);
                seen.add(node.asText());
            }
            return;
        }
        if (path.equals("sidebar.wrap") || path.equals("sidebar.visible")) { if (value.isBoolean()) return; }
        else if (path.equals("layout.sidebarWidthPercent")) { if (value.isIntegralNumber() && value.asInt() >= 20 && value.asInt() <= 65) return; }
        else if (path.equals("layout.minChatColumns")) { if (value.isIntegralNumber() && value.asInt() >= 20 && value.asInt() <= 200) return; }
        else if (path.equals("sidebar.maxItems")) { if (value.isIntegralNumber() && value.canConvertToInt() && value.asInt() >= 1 && value.asInt() <= 4096) return; }
        else if (path.equals("sidebar.maxChars")) { if (value.isIntegralNumber() && value.canConvertToInt() && value.asInt() >= 1024 && value.asInt() <= 64 * 1024 * 1024) return; }
        else if (path.matches("bindings\\.(toggle|focus)")) { if (value.isTextual() && value.asText().matches("ctrl-x [a-z]") && !Set.of("ctrl-x p", "ctrl-x t", "ctrl-x a").contains(value.asText())) return; }
        else {
            Set<String> choices = path.equals("preset") ? Set.of("classic", "focused", "verbose")
                    : path.equals("layout.mode") ? Set.of("inline", "split", "auto")
                    : path.equals("images.protocol") ? Set.of("auto", "kitty", "off")
                    : path.equals("layout.narrowFallback") ? Set.of("drawer")
                    : path.equals("sidebar.follow") ? Set.of("when-at-bottom", "manual")
                    : path.matches("(?:content|toolOverrides)\\.[A-Za-z0-9_-]+\\.inline") ? Set.of("none", "summary", "full")
                    : path.matches("(?:content|toolOverrides)\\.[A-Za-z0-9_-]+\\.sidebar") ? Set.of("off", "collapsed", "expanded") : Set.of();
            if (value.isTextual() && choices.contains(value.asText())) return;
        }
        throw new IllegalArgumentException("Invalid UI setting " + path + " = " + value);
    }
}
