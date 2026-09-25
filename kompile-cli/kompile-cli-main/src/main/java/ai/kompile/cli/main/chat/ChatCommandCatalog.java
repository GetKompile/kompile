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
            "transcript", "copy", "memory", "recall", "reminder", "reminder-global", "continue",
            "permissions", "todos",
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
        if ("help".equals(name) || "skills".equals(name) || "model".equals(name)
                || "role".equals(name) || "fast".equals(name)
                || "reminder".equals(name) || "reminder-global".equals(name)
                || "continue".equals(name) || "judge".equals(name) || "judge-global".equals(name)
                || "loop".equals(name) || "loop-global".equals(name)
                || "queue".equals(name) || "queues".equals(name)
                || "queue-remove".equals(name) || "queue-edit".equals(name)
                || "queue-move".equals(name) || "queue-clear".equals(name)
                || "queue-status".equals(name) || "clear".equals(name)) return WebSupport.SUPPORTED;
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
                + "\n/role and /fast are fully supported over web input: the bare form returns the "
                + "current selection (with an optional menu), the explicit form validates and "
                + "persists it durably per session id + working directory; /role '' clears the "
                + "selection and /fast on|off persists the toggle."
                + "\n/reminder, /reminder-global, /loop, and /loop-global are fully supported over "
                + "web input (list/add/clear/pause/resume/remove persist through the same managers "
                + "as the interactive CLI); loop run-now requires a live chat process and reports "
                + "that honestly instead of pretending to fire."
                + "\n/continue is fully supported over web input: the auto-reply configuration is "
                + "project-global file-backed state, so on/off/keywords/reply edits persist for the "
                + "interactive CLI too; the firing itself stays a live-session behavior."
                + "\n/judge and /judge-global support the durable controls over web input: the "
                + "global master switch (harness config) and per-session guidance (\"/judge "
                + "feedback <text>\") persist in the same files the live CLI reads; live-session "
                + "controls (override, approvals, restart, agent, judgements) report honestly."
                + "\nUse /skills to list reusable prompts. Unsupported commands never go to a model.";
    }
}
