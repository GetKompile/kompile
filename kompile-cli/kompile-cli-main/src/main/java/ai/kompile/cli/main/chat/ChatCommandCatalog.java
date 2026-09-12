package ai.kompile.cli.main.chat;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** CLI-owned catalog of the actual ChatCommandRouter switch, not completion suggestions. */
public final class ChatCommandCatalog {
    private ChatCommandCatalog() { }

    public enum WebSupport { SUPPORTED, TERMINAL_REQUIRED, LIVE_SESSION_REQUIRED, NOT_YET_SUPPORTED }
    public record Entry(String name, WebSupport webSupport) { }

    private static final Set<String> NAMES = Set.of(
            "quit", "exit", "resources", "help", "auth", "setup", "provider", "tools", "subagents",
            "local-tools", "tool", "local-tool", "status", "dashboard", "title", "history", "clear",
            "restart", "reset", "reset-all", "compact", "auto-compact", "rag", "agents", "local-agents",
            "agent", "local-agent", "config", "sessions", "ask", "agent-chat", "crawl", "conversations",
            "transcript", "copy", "memory", "recall", "reminder", "reminder-global", "permissions", "todos",
            "plan", "queue", "queues", "queue-send", "queue-send-all", "queue-remove", "queue-edit",
            "queue-move", "queue-clear", "queue-status", "loop", "loop-global", "jobs", "jobs-remove",
            "jobs-clear", "activity", "processes", "process-kill", "process-output", "process-status",
            "statusbar", "auto-dequeue", "stats", "passthrough", "resume", "resume-all", "mode", "menu",
            "skills", "roles", "role", "model", "fast", "enforce", "enforcer", "judge", "judge-global",
            "direction", "forward", "image", "file", "attach", "attachments");
    private static final Set<String> TERMINAL = Set.of("quit", "exit", "auth", "setup", "menu", "copy", "statusbar");
    private static final Set<String> LIVE = Arrays.stream((
            "clear restart reset reset-all compact auto-compact rag agent local-agent role fast title "
            + "memory permissions plan queue queues queue-send queue-send-all queue-remove queue-edit queue-move "
            + "queue-clear queue-status loop loop-global jobs jobs-remove jobs-clear auto-dequeue passthrough "
            + "resume resume-all mode enforce enforcer judge judge-global direction forward image file attach attachments")
            .split(" ")).collect(Collectors.toUnmodifiableSet());

    public static Set<String> names() { return NAMES; }
    public static boolean isBuiltin(String name) {
        return name != null && NAMES.contains(name.toLowerCase(Locale.ROOT));
    }
    public static WebSupport webSupport(String name) {
        if ("help".equals(name) || "skills".equals(name) || "model".equals(name)) return WebSupport.SUPPORTED;
        if (TERMINAL.contains(name)) return WebSupport.TERMINAL_REQUIRED;
        if (LIVE.contains(name)) return WebSupport.LIVE_SESSION_REQUIRED;
        return WebSupport.NOT_YET_SUPPORTED;
    }
    public static List<Entry> entries() {
        return NAMES.stream().sorted().map(n -> new Entry("/" + n, webSupport(n))).toList();
    }
    public static String webHelp() {
        return "Web slash commands (CLI-owned; foundation, not terminal parity):\n"
                + entries().stream().map(e -> e.name() + " — " + e.webSupport())
                .collect(Collectors.joining("\n"))
                + "\n/model is fully supported over web input: bare /model lists locally known "
                + "models (no live provider call), /model <id> validates and persists the selection "
                + "for the session; later MODEL_INPUT turns use it."
                + "\nUse /skills to list reusable prompts. Unsupported commands never go to a model.";
    }
}
