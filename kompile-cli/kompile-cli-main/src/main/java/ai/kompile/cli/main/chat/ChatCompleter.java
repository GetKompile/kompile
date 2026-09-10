/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.mcp.McpSseClient;
import ai.kompile.utils.FormatUtils;
import org.jline.reader.Candidate;
import org.jline.reader.Buffer;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.jline.reader.Widget;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * JLine3 tab completer for the chat REPL.
 * Completes slash commands (with descriptions), tool names, skill names,
 * agent names, role names, sub-arguments, and file paths.
 */
public class ChatCompleter implements Completer {

    // ── All slash commands with descriptions ────────────────────────────────

    private static final Map<String, String> COMMANDS = new LinkedHashMap<>();
    static {
        // Chat & agents
        COMMANDS.put("/help", "Show help message");
        COMMANDS.put("/setup", "Run setup wizard");
        COMMANDS.put("/auth", "Select session credentials or global per-vendor authentication");
        COMMANDS.put("/status", "Connection and session info");
        COMMANDS.put("/agent", "Switch or show current agent");
        COMMANDS.put("/agents", "List available agents");
        COMMANDS.put("/local-agent", "Switch local agent");
        COMMANDS.put("/local-agents", "List local agents");
        COMMANDS.put("/agent-chat", "Direct agent chat");
        COMMANDS.put("/subagents", "List available subagents");
        COMMANDS.put("/ask", "Ask a question");

        // Tools
        COMMANDS.put("/tool", "Invoke a tool by name");
        COMMANDS.put("/tools", "List available tools");
        COMMANDS.put("/local-tool", "Invoke a local tool by name");
        COMMANDS.put("/local-tools", "List local tools");

        // Context & memory
        COMMANDS.put("/history", "Show conversation history");
        COMMANDS.put("/clear", "Start a new conversation in this process");
        COMMANDS.put("/reset", "Restart this session in a new process");
        COMMANDS.put("/reset-all", "Restart every active CLI chat session");
        COMMANDS.put("/restart", "Alias for /reset");
        COMMANDS.put("/compact", "Summarize conversation to free context");
        COMMANDS.put("/auto-compact", "Configure automatic model-aware compaction");
        COMMANDS.put("/memory", "Show memory entries");
        COMMANDS.put("/recall", "Recall from memory");
        COMMANDS.put("/transcript", "Show transcript");
        COMMANDS.put("/copy", "Copy the latest assistant response");
        COMMANDS.put("/conversations", "List conversations");
        COMMANDS.put("/sessions", "List sessions");
        COMMANDS.put("/title", "Show or change the session title");
        COMMANDS.put("/dashboard", "Refresh, show, hide, or inspect the project dashboard");
        COMMANDS.put("/reminder", "List or add session reminders");
        COMMANDS.put("/reminder-global", "List or add project-global reminders");

        // RAG & planning
        COMMANDS.put("/rag", "Toggle or configure RAG");
        COMMANDS.put("/plan", "Toggle plan mode");
        COMMANDS.put("/todos", "Show todo list");

        // Config & permissions
        COMMANDS.put("/config", "Show or edit configuration");
        COMMANDS.put("/permissions", "Manage permissions");
        COMMANDS.put("/model", "Switch or show model");
        COMMANDS.put("/fast", "Toggle premium fast mode (supported models only)");
        COMMANDS.put("/mode", "Switch interaction mode");

        // Queue & jobs
        COMMANDS.put("/queue", "Show message queue");
        COMMANDS.put("/queues", "List all queues");
        COMMANDS.put("/queue-send", "Send message to queue");
        COMMANDS.put("/queue-send-all", "Send to all queues");
        COMMANDS.put("/queue-remove", "Remove from queue");
        COMMANDS.put("/queue-edit", "Edit a queued message");
        COMMANDS.put("/queue-move", "Reorder a queued message");
        COMMANDS.put("/queue-clear", "Clear queue");
        COMMANDS.put("/queue-status", "Show queue status");
        COMMANDS.put("/jobs", "List background jobs");
        COMMANDS.put("/jobs-remove", "Remove a background job");
        COMMANDS.put("/jobs-clear", "Clear all jobs");
        COMMANDS.put("/activity", "Show local work or the live project-agent dashboard");
        COMMANDS.put("/resources", "Configure tool-argument resource rules or preview a call");
        COMMANDS.put("/processes", "Show processes & subagents");
        COMMANDS.put("/process-monitors", "List or cancel process monitors");
        COMMANDS.put("/process-kill", "Kill a running process");
        COMMANDS.put("/process-output", "View process output");
        COMMANDS.put("/process-status", "Show process or watcher status");
        COMMANDS.put("/statusbar", "Toggle status bar");
        COMMANDS.put("/auto-dequeue", "Toggle auto-dequeue");
        COMMANDS.put("/loop", "Schedule recurring tasks for this session");
        COMMANDS.put("/loop-global", "Schedule recurring tasks for this project");
        COMMANDS.put("/stats", "Show session statistics");

        // Roles & skills
        COMMANDS.put("/skills", "List available skills");
        COMMANDS.put("/roles", "Manage roles");
        COMMANDS.put("/role", "Show or assign role");
        COMMANDS.put("/judge", "Judge control, policy, direction, and global switch");
        COMMANDS.put("/judge-global", "Persistent judge master switch");
        COMMANDS.put("/rules", "Show active judge policy rules");
        COMMANDS.put("/archive", "List archived enforced turns");
        COMMANDS.put("/rollback", "Roll back archived turns");
        COMMANDS.put("/diff", "Show an archived turn diff");
        COMMANDS.put("/purge", "Purge session diff archive");

        // Passthrough & forwarding
        COMMANDS.put("/passthrough", "Toggle passthrough mode");
        COMMANDS.put("/keys", "Forward keys directly to the agent");
        COMMANDS.put("/render", "Show or set managed render mode");
        COMMANDS.put("/forward", "Forward command to agent");
        COMMANDS.put("/resume", "Browse and resume one session");
        COMMANDS.put("/resume-all", "Restore recent exited or crashed sessions");
        COMMANDS.put("/menu", "Show menu");

        // Files & attachments
        COMMANDS.put("/image", "Attach an image file");
        COMMANDS.put("/file", "Attach a file");
        COMMANDS.put("/attach", "Attach a file");
        COMMANDS.put("/attachments", "List attachments");

        // Exit
        COMMANDS.put("/quit", "Exit the chat");
        COMMANDS.put("/exit", "Exit the chat");
    }

    // ── Sub-argument definitions ────────────────────────────────────────────

    private static final Map<String, List<String[]>> SUB_ARGS = new LinkedHashMap<>();
    static {
        List<String[]> judgeArgs = List.of(
                new String[]{"status", "Show unified judge status"},
                new String[]{"on", "Enable the judge for this session"},
                new String[]{"off", "Disable the judge for this session"},
                new String[]{"pause", "Legacy alias for off"},
                new String[]{"resume", "Legacy alias for on"},
                new String[]{"global", "Persistent master switch: on, off, or status"},
                new String[]{"workflow", "Configure required skills and deterministic workflow gates"},
                new String[]{"judgements", "Show recorded judgements"},
                new String[]{"config", "Show project judge policy config"},
                new String[]{"show", "Legacy alias for status"},
                new String[]{"rules", "Show active judge policy rules"},
                new String[]{"init", "Configure project judge policy"},
                new String[]{"delete", "Remove project judge policy config"},
                new String[]{"reload", "Reload all project judge components"},
                new String[]{"run", "Show standalone compatibility command"},
                new String[]{"direction", "Configure goal-drift direction checks"},
                new String[]{"chat", "Talk with the judge"},
                new String[]{"feedback", "Durable guidance for every verdict"},
                new String[]{"override", "Arm one-shot report-only turn"},
                new String[]{"restart", "Restart the judge backend"},
                new String[]{"agent", "Switch the judge agent"}
        );
        // Legacy aliases remain parseable/completable when typed explicitly, but only
        // /judge is advertised as a top-level concept.
        SUB_ARGS.put("/enforce", judgeArgs);
        SUB_ARGS.put("/enforcer", judgeArgs);
        SUB_ARGS.put("/judge", judgeArgs);
        SUB_ARGS.put("/judge-global", List.of(
                new String[]{"status", "Show the persistent master switch"},
                new String[]{"on", "Enable the judge globally"},
                new String[]{"off", "Disable the judge globally"}
        ));
        SUB_ARGS.put("/resume-all", List.of(
                new String[]{"--dry-run", "Preview commands without launching"},
                new String[]{"--list", "List the resumable batch"},
                new String[]{"--recent", "Override the recent-session limit"},
                new String[]{"--all", "Restore every resumable tracked chat"},
                new String[]{"--agent", "Filter by recorded agent"},
                new String[]{"--project", "Filter by project directory"},
                new String[]{"--status", "Show registry and terminal status"},
                new String[]{"--terminal", "Override the terminal emulator"},
                new String[]{"--set-recent", "Persist the default recent limit"},
                new String[]{"--set-terminal", "Persist the terminal emulator"},
                new String[]{"--set-terminal-args", "Persist terminal arguments"},
                new String[]{"--prune", "Remove old tracked sessions"}
        ));
        SUB_ARGS.put("/rag", List.of(
                new String[]{"on", "Enable RAG"},
                new String[]{"off", "Disable RAG"}
        ));
        SUB_ARGS.put("/plan", List.of(
                new String[]{"on", "Enable plan mode"},
                new String[]{"off", "Disable plan mode"}
        ));
        SUB_ARGS.put("/dashboard", List.of(
                new String[]{"refresh", "Fetch the configured dashboard now"},
                new String[]{"show", "Show the retained project dashboard"},
                new String[]{"hide", "Hide the project dashboard"},
                new String[]{"status", "Show dashboard configuration and snapshot status"}
        ));
        List<String[]> loopArgs = List.of(
                new String[]{"add", "Add a recurring prompt"},
                new String[]{"list", "List scheduled loops"},
                new String[]{"clear", "Clear all loops in this scope"},
                new String[]{"pause", "Pause a loop by id"},
                new String[]{"resume", "Resume a loop by id"},
                new String[]{"run", "Run a loop immediately"},
                new String[]{"remove", "Remove a loop by id"}
        );
        SUB_ARGS.put("/loop", loopArgs);
        SUB_ARGS.put("/loop-global", loopArgs);
        SUB_ARGS.put("/auto-compact", List.of(
                new String[]{"status", "Show active model limits and trigger"},
                new String[]{"on", "Enable automatic compaction"},
                new String[]{"off", "Disable automatic compaction"},
                new String[]{"threshold", "Set trigger percentage"},
                new String[]{"reserve", "Set reserved input headroom"},
                new String[]{"context", "Override context window"},
                new String[]{"output", "Override max output tokens"}
        ));
        SUB_ARGS.put("/fast", List.of(
                new String[]{"on", "Request fast mode (higher cost)"},
                new String[]{"off", "Use standard speed"},
                new String[]{"status", "Show fast-mode preference"}
        ));
        SUB_ARGS.put("/mode", List.of(
                new String[]{"standard", "Standard chat mode"},
                new String[]{"passthrough", "Agent passthrough mode"},
                new String[]{"plan", "Planning mode"}
        ));
        List<String[]> reminderArgs = List.of(
                new String[]{"list", "List configured reminders"},
                new String[]{"add", "Add a reminder"},
                new String[]{"clear", "Clear configured reminders"}
        );
        SUB_ARGS.put("/reminder", reminderArgs);
        SUB_ARGS.put("/reminder-global", reminderArgs);
        SUB_ARGS.put("/render", List.of(
                new String[]{"mirror", "Render the agent terminal directly"},
                new String[]{"decoded", "Render the decoded transcript"},
                new String[]{"decode", "Alias for decoded render mode"}
        ));
        List<String[]> activityArgs = List.of(
                new String[]{"agents", "Open the live project-agent dashboard"},
                new String[]{"agent", "Filter the project dashboard by agent or session"},
                new String[]{"refresh", "Refresh the project-agent dashboard"},
                new String[]{"session", "Open a read-only conversation activity summary"},
                new String[]{"project", "Browse retained activity for this project"},
                new String[]{"global", "Browse all locally known retained activity"},
                new String[]{"history", "Alias for global retained activity"},
                new String[]{"outcomes", "Browse recorded outcomes and evidence"},
                new String[]{"transcript", "Inspect a permitted transcript without resuming"},
                new String[]{"detail", "Open retained events and source evidence"},
                new String[]{"search", "Filter retained activity metadata"},
                new String[]{"next", "Read the next bounded activity page"},
                new String[]{"previous", "Read the previous bounded activity page"},
                new String[]{"confirm", "Record deliberate user outcome confirmation"},
                new String[]{"annotate", "Record a non-confirming outcome annotation"},
                new String[]{"local", "Show local processes and subagents"},
                new String[]{"enter", "Inspect activity or subagent"},
                new String[]{"inspect", "Inspect activity or subagent"},
                new String[]{"status", "Inspect activity status"},
                new String[]{"logs", "Show activity logs"},
                new String[]{"kill", "Kill or cancel activity"},
                new String[]{"remove", "Remove completed activity"},
                new String[]{"clear", "Clear completed activities"},
                new String[]{"close", "Close activity menu"}
        );
        SUB_ARGS.put("/resources", List.of(
                new String[]{"setup", "Interactive resource configuration wizard"},
                new String[]{"add", "Interactive add-rule wizard"},
                new String[]{"show", "Show effective resource policy and sources"},
                new String[]{"sources", "Show configuration precedence and origins"},
                new String[]{"rules", "List ordered resource rules"},
                new String[]{"check", "Preview a shell command without executing it"},
                new String[]{"rule", "Add: id low|high executable [argument prefix...]"},
                new String[]{"remove", "Remove a named rule"},
                new String[]{"default", "Set unmatched launches: low or high"},
                new String[]{"unknown-shell", "Set unparsed shell syntax: low or high"},
                new String[]{"inherit", "Clear an override: default, unknown-shell or rules"},
                new String[]{"global", "Configure user defaults rather than this project"},
                new String[]{"json", "Export advanced policy JSON"},
                new String[]{"help", "Show resource configuration help"}
        ));
        SUB_ARGS.put("/activity", activityArgs);
        SUB_ARGS.put("/processes", activityArgs);
        SUB_ARGS.put("/jobs", activityArgs);
    }

    private static final Set<String> TOOL_COMMANDS = Set.of("/tool", "/local-tool");
    private static final Set<String> AGENT_COMMANDS = Set.of("/agent", "/local-agent");
    private static final Set<String> FILE_COMMANDS = Set.of("/image", "/file", "/attach");

    // ── Suppliers ───────────────────────────────────────────────────────────

    private final Supplier<List<McpSseClient.ToolInfo>> toolsSupplier;
    private final Supplier<Set<String>> skillNamesSupplier;
    private final Supplier<Set<String>> agentNamesSupplier;
    private final Supplier<Set<String>> roleNamesSupplier;

    /**
     * Backward-compatible constructor (tools only, no skill/agent/role completion).
     */
    public ChatCompleter(Supplier<List<McpSseClient.ToolInfo>> toolsSupplier) {
        this(toolsSupplier, Set::of);
    }

    /**
     * Backward-compatible constructor (tools + skills, no agent/role completion).
     */
    public ChatCompleter(Supplier<List<McpSseClient.ToolInfo>> toolsSupplier,
                         Supplier<Set<String>> skillNamesSupplier) {
        this(toolsSupplier, skillNamesSupplier, Set::of, Set::of);
    }

    /**
     * Full constructor with all completion sources.
     */
    public ChatCompleter(Supplier<List<McpSseClient.ToolInfo>> toolsSupplier,
                         Supplier<Set<String>> skillNamesSupplier,
                         Supplier<Set<String>> agentNamesSupplier,
                         Supplier<Set<String>> roleNamesSupplier) {
        this.toolsSupplier = toolsSupplier;
        this.skillNamesSupplier = skillNamesSupplier;
        this.agentNamesSupplier = agentNamesSupplier;
        this.roleNamesSupplier = roleNamesSupplier;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String buffer = line.line();
        int cursor = line.cursor();
        String upToCursor = buffer.substring(0, cursor);

        if (!upToCursor.startsWith("/")) {
            return;
        }

        // If there's a space, we're completing sub-arguments
        int spaceIdx = upToCursor.indexOf(' ');
        if (spaceIdx > 0) {
            String cmd = upToCursor.substring(0, spaceIdx).toLowerCase();
            String argPart = upToCursor.substring(spaceIdx + 1);
            completeSubArgs(cmd, argPart, candidates);
            return;
        }

        // Completing the command name itself
        completeCommandName(upToCursor, candidates);
    }

    // ── Command name completion ─────────────────────────────────────────────

    private void completeCommandName(String upToCursor, List<Candidate> candidates) {
        String prefix = upToCursor.toLowerCase();

        // Built-in commands with descriptions
        for (Map.Entry<String, String> entry : COMMANDS.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                candidates.add(new Candidate(
                        entry.getKey(), entry.getKey(), null,
                        entry.getValue(), null, null, true));
            }
        }

        // Skill names (grouped under "skill")
        Set<String> skillNames = skillNamesSupplier.get();
        if (skillNames != null) {
            for (String skill : skillNames) {
                String skillCmd = "/" + skill;
                if (skillCmd.startsWith(prefix)) {
                    candidates.add(new Candidate(
                            skillCmd, skillCmd, "skill", null, null, null, true));
                }
            }
        }
    }

    // ── Sub-argument completion ──────────────────────────────────────────────

    private void completeSubArgs(String cmd, String argPart, List<Candidate> candidates) {
        String argPrefix = argPart.trim().toLowerCase();

        if ("/auth".equals(cmd)) {
            for (String option : List.of("list", "session", "global", "default session", "default global")) {
                if (option.startsWith(argPrefix)) candidates.add(new Candidate(option));
            }
            return;
        }

        // Tool name completion: /tool <name>, /local-tool <name>
        if (TOOL_COMMANDS.contains(cmd)) {
            completeToolNames(argPrefix, candidates);
            return;
        }

        // Agent name completion: /agent <name>, /local-agent <name>
        if (AGENT_COMMANDS.contains(cmd)) {
            completeAgentNames(argPrefix, candidates);
            return;
        }

        // Role name completion: /role <name>
        if ("/role".equals(cmd)) {
            completeRoleNames(argPrefix, candidates);
            return;
        }

        // File path completion: /image <path>, /file <path>, /attach <path>
        if (FILE_COMMANDS.contains(cmd)) {
            completeFilePaths(argPart, candidates);
            return;
        }

        // Static sub-argument completion (enforce, rag, plan, mode)
        List<String[]> subArgs = SUB_ARGS.get(cmd);
        if (subArgs != null) {
            for (String[] sub : subArgs) {
                if (sub[0].startsWith(argPrefix)) {
                    candidates.add(new Candidate(
                            sub[0], sub[0], null, sub[1], null, null, true));
                }
            }
        }
    }

    // ── Tool names ──────────────────────────────────────────────────────────

    private void completeToolNames(String prefix, List<Candidate> candidates) {
        List<McpSseClient.ToolInfo> tools = toolsSupplier.get();
        if (tools == null) return;
        for (McpSseClient.ToolInfo tool : tools) {
            if (tool.getName().toLowerCase().startsWith(prefix)) {
                candidates.add(new Candidate(
                        tool.getName(), tool.getName(), null,
                        tool.getDescription(), null, null, true));
            }
        }
    }

    // ── Agent names ─────────────────────────────────────────────────────────

    private void completeAgentNames(String prefix, List<Candidate> candidates) {
        Set<String> agents = agentNamesSupplier.get();
        if (agents == null) return;
        for (String agent : agents) {
            if (agent.toLowerCase().startsWith(prefix)) {
                candidates.add(new Candidate(
                        agent, agent, "agent", null, null, null, true));
            }
        }
    }

    // ── Role names ──────────────────────────────────────────────────────────

    private void completeRoleNames(String prefix, List<Candidate> candidates) {
        Set<String> roles = roleNamesSupplier.get();
        if (roles == null) return;
        for (String role : roles) {
            if (role.toLowerCase().startsWith(prefix)) {
                candidates.add(new Candidate(
                        role, role, "role", null, null, null, true));
            }
        }
    }

    // ── File paths ──────────────────────────────────────────────────────────

    private void completeFilePaths(String pathArg, List<Candidate> candidates) {
        try {
            Path base;
            String filePrefix;

            if (pathArg.isEmpty()) {
                base = Paths.get(System.getProperty("user.dir"));
                filePrefix = "";
            } else {
                Path path = Paths.get(pathArg);
                if (pathArg.endsWith(File.separator) || pathArg.endsWith("/")) {
                    base = path;
                    filePrefix = "";
                } else {
                    base = path.getParent();
                    filePrefix = path.getFileName().toString().toLowerCase();
                    if (base == null) {
                        base = Paths.get(System.getProperty("user.dir"));
                    }
                }
            }

            if (!Files.isDirectory(base)) {
                return;
            }

            try (Stream<Path> entries = Files.list(base)) {
                entries.forEach(entry -> {
                    String name = entry.getFileName().toString();

                    // Skip hidden files
                    if (name.startsWith(".")) {
                        return;
                    }

                    if (!name.toLowerCase().startsWith(filePrefix)) {
                        return;
                    }

                    boolean isDir = Files.isDirectory(entry);
                    String display = isDir ? name + "/" : name;
                    String value = entry.toString();

                    // Show file size in description
                    String descr = null;
                    if (!isDir) {
                        try {
                            descr = FormatUtils.formatBytes(Files.size(entry));
                        } catch (IOException ignored) {
                        }
                    }

                    // Directories: complete=false so tab continues into subdir
                    candidates.add(new Candidate(
                            value, display, null, descr, null, null, !isDir));
                });
            }
        } catch (IOException | java.nio.file.InvalidPathException ignored) {
        }
    }

    // ── Terminal builder ──────────────────────────────────────────────────────

    /**
     * Builds a system terminal that works in both JVM and GraalVM native image modes.
     * <p>
     * JLine's default {@code TerminalBuilder.builder().system(true).build()} falls back
     * to {@code DumbTerminal} in native images because JNI/JNA/FFM providers fail.
     * This method explicitly tries the "exec" provider first (which uses {@code stty}
     * and {@code tput} via {@code ProcessBuilder}, always available in native images),
     * then falls back to the default builder if that fails.
     */
    public static Terminal buildSystemTerminal() throws IOException {
        // Try exec provider first — works in native images on POSIX systems
        try {
            Terminal term = TerminalBuilder.builder()
                    .system(true)
                    .provider("exec")
                    .build();
            if (!(term instanceof org.jline.terminal.impl.DumbTerminal)) {
                return term;
            }
            // Got a DumbTerminal even with exec — close and try default
            term.close();
        } catch (Exception ignored) {
            // exec provider failed, try default
        }

        // Default fallback
        return TerminalBuilder.builder()
                .system(true)
                .build();
    }

    // ── Auto-trigger completion on slash ─────────────────────────────────────

    /** Max candidates to show in the non-TUI JLine fallback. */
    private static final int MAX_DISPLAY_CANDIDATES = 15;

    /** Cached reflective handle to LineReaderImpl.post (protected field). */
    private static volatile Field postField;

    /**
     * Marks a SELF_INSERT/BACKWARD_DELETE dispatch so completion updates never
     * call JLine REDISPLAY recursively from inside the active widget.
     */
    private static final ThreadLocal<Boolean> IN_INPUT_WIDGET =
            ThreadLocal.withInitial(() -> false);

    // Mutable routing belongs to ChatUiSession. Unbound callers retain the legacy
    // single-chat route; opt-in hosts bind/capture an explicit owner.

    /** Managed TUI hook for one replaceable in-flight transcript block. */
    @FunctionalInterface
    public interface TranscriptBlockOutput {
        boolean upsert(String key, String content);
    }


    /** One slash-completion row rendered by the managed standard-chat panel. */
    public record CompletionItem(String value, String description) {}

    /**
     * Managed completion sink. Returning {@code true} means the host owns the
     * candidate rows and JLine must keep its scrolling {@code post} area empty.
     */
    @FunctionalInterface
    public interface CompletionDisplay {
        boolean update(List<CompletionItem> items);
    }


    /** Large pasted blocks stay compact in the editor and expand on submit. */
    static final int LARGE_PASTE_THRESHOLD = 1_000;
    private record LargePaste(String token, String content) {}
    private static final Map<LineReader, List<LargePaste>> LARGE_PASTES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<LineReader> PASTE_SUPPORT_READERS =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));


    /** Activity shown after Escape until the next edit or the notice timeout. */
    private static final String INTERRUPTED_ACTIVITY = "Interrupted by user";

    /** DIM ANSI escape. */
    private static final String DIM = "\033[2m";
    /** ANSI reset. */
    private static final String ANSI_RESET = "\033[0m";
    private static final String ANSI_YELLOW = "\033[33m";
    private static final String ANSI_CYAN = "\033[36m";

    /**
     * Stores the terminal reference so the post display can draw borders
     * sized to the actual terminal width.
     */
    public static void setTerminalRef(LineReader reader, Terminal terminal) {
        synchronized (ChatUiSession.current()) {
            if (ChatUiSession.current().isClosed()) return;
            ChatUiSession.current().lineReaderRef = reader;
            ChatUiSession.current().terminalRef = terminal;
        }
    }

    /**
     * Register the active TUI repaint callback. JLine's REDISPLAY redraws the
     * prompt and may clear cursor-addressed transcript rows, so the TUI must
     * repaint its authoritative view immediately afterwards.
     */
    public static void setContentRedraw(Runnable redraw) {
        synchronized (ChatUiSession.current()) {
            if (ChatUiSession.current().isClosed()) return;
            ChatUiSession.current().contentRedraw = redraw;
        }
    }

    /** Register the active TUI transcript sink for asynchronous output. */
    public static void setContentOutput(Consumer<String> output) {
        synchronized (ChatUiSession.current()) {
            if (ChatUiSession.current().isClosed()) return;
            ChatUiSession.current().contentOutput = output;
        }
    }

    /** Register the active TUI sink for replaceable tool/process blocks. */
    public static void setTranscriptBlockOutput(TranscriptBlockOutput output) {
        synchronized (ChatUiSession.current()) {
            if (ChatUiSession.current().isClosed()) return;
            ChatUiSession.current().transcriptBlockOutput = output;
        }
    }

    /** Register the managed standard-chat slash-completion panel. */
    public static void setCompletionDisplay(CompletionDisplay display) {
        synchronized (ChatUiSession.current()) {
            if (ChatUiSession.current().isClosed()) return;
            ChatUiSession.current().completionDisplay = display;
        }
    }

    /**
     * Insert or replace one in-flight transcript block. Returns false outside the
     * managed TUI so callers can retain append-only stdout/headless behavior.
     */
    public static boolean upsertTranscriptBlock(String key, String content) {
        if (ChatUiSession.current().isClosed()) return false;
        TranscriptBlockOutput output = ChatUiSession.current().transcriptBlockOutput;
        if (output == null || key == null || key.isBlank()) return false;
        try {
            return output.upsert(key, content == null ? "" : content);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Session-owned warning lane; retained while a session is detached. */
    public static void setAlertOutput(Consumer<String> output) {
        synchronized (ChatUiSession.current()) {
            if (!ChatUiSession.current().isClosed()) ChatUiSession.current().alertOutput = output;
        }
    }

    /** Returns false only when no managed header is available. Never writes into input rows. */
    public static boolean showAlert(String text) {
        ChatUiSession owner = ChatUiSession.current();
        if (owner.isClosed() || text == null || text.isBlank()) return true;
        Consumer<String> output = owner.alertOutput;
        if (output == null) return false;
        output.accept(text);
        return true;
    }

    /** Transient UI feedback, not transcript content. Plain terminals retain a readable fallback. */
    public static void showNotice(String text) {
        if (!showAlert(text)) printAbove(text);
    }

    /** Register a listener for activity changes (for example, a terminal tab title). */
    public static void setActivityListener(Consumer<String> listener) {
        synchronized (ChatUiSession.current()) {
            if (ChatUiSession.current().isClosed()) return;
            ChatUiSession.current().activityListener = listener;
        }
    }

    private static void redrawContentView() {
        if (ChatUiSession.current().isClosed()) return;
        Runnable redraw = ChatUiSession.current().contentRedraw;
        if (redraw == null) return;
        try {
            redraw.run();
        } catch (RuntimeException ignored) {
            // The terminal may be shutting down; never break line editing.
        }
    }

    /**
     * Whether a standard-chat line editor is available for managed asynchronous output.
     */
    public static boolean hasLineReader() {
        return !ChatUiSession.current().isClosed() && ChatUiSession.current().lineReaderRef != null;
    }

    /** Release terminal focus without discarding transcript sinks, activity or drafts. */
    public static void detachTerminalRef(LineReader reader) {
        synchronized (ChatUiSession.current()) {
            if (ChatUiSession.current().lineReaderRef == reader) {
                ChatUiSession.current().lineReaderRef = null;
                ChatUiSession.current().terminalRef = null;
            }
        }
    }

    public static void clearTerminalRef(LineReader reader) {
        synchronized (ChatUiSession.current()) {
            if (ChatUiSession.current().lineReaderRef == reader) {
                ChatUiSession.current().lineReaderRef = null;
                ChatUiSession.current().terminalRef = null;
                ChatUiSession.current().contentRedraw = null;
                ChatUiSession.current().contentOutput = null;
                ChatUiSession.current().alertOutput = null;
                ChatUiSession.current().transcriptBlockOutput = null;
                ChatUiSession.current().completionDisplay = null;
                ChatUiSession.current().queueSupplier = null;
                ChatUiSession.current().activityLabel = null;
                ChatUiSession.current().activityTerminal = false;
                ChatUiSession.current().activityListener = null;
                ChatUiSession.current().temporaryWindowActive = false;
                LARGE_PASTES.remove(reader);
                PASTE_SUPPORT_READERS.remove(reader);
            }
        }
    }

    public static void setTemporaryWindowActive(boolean active) {
        synchronized (ChatUiSession.current()) {
            if (ChatUiSession.current().isClosed()) return;
            ChatUiSession.current().temporaryWindowActive = active;
        }
    }

    public static boolean isTemporaryWindowActive() {
        return ChatUiSession.current().temporaryWindowActive;
    }

    public static void setActivity(String activity) {
        synchronized (ChatUiSession.current()) {
            if (ChatUiSession.current().isClosed()) return;
            ChatUiSession.current().activityLabel = activity == null || activity.isBlank() ? null : activity;
            ChatUiSession.current().activityTerminal = false;
        }
        notifyActivityListener(ChatUiSession.current().activityLabel);
    }

    private static void notifyActivityListener(String activity) {
        Consumer<String> listener = ChatUiSession.current().activityListener;
        if (listener == null) return;
        try {
            listener.accept(activity);
        } catch (RuntimeException ignored) {
            // Terminal teardown must not interrupt the active chat turn.
        }
    }

    public static String getActivity() {
        return ChatUiSession.current().getActivity();
    }

    /** Mark interruption until the next input edit or ten seconds of inactivity. */
    public static void markInterrupted() {
        markInterrupted(java.util.concurrent.CompletableFuture.delayedExecutor(
                ai.kompile.cli.main.chat.tui.KompileTui.EPHEMERAL_MESSAGE_SECONDS,
                java.util.concurrent.TimeUnit.SECONDS));
    }

    static void markInterrupted(java.util.concurrent.Executor expiryExecutor) {
        ChatUiSession owner = ChatUiSession.current();
        long version;
        synchronized (owner) {
            if (owner.isClosed()) return;
            version = ++owner.interruptionVersion;
            owner.activityLabel = INTERRUPTED_ACTIVITY;
            owner.activityTerminal = true;
        }
        notifyActivityListener(owner.activityLabel);
        expiryExecutor.execute(owner.capture((Runnable) () -> {
            synchronized (owner) {
                if (owner.isClosed() || owner.interruptionVersion != version || !owner.activityTerminal) return;
                owner.activityLabel = null;
                owner.activityTerminal = false;
            }
            notifyActivityListener(owner.activityLabel);
            redrawContentView();
        }));
    }

    public static boolean isActivityTerminal() {
        return ChatUiSession.current().isActivityTerminal();
    }

    /** Clear only the transient interruption marker, preserving normal activity state. */
    public static boolean clearInterruptedOnInput() {
        synchronized (ChatUiSession.current()) {
            if (ChatUiSession.current().isClosed()
                    || !INTERRUPTED_ACTIVITY.equals(ChatUiSession.current().activityLabel)) return false;
            ChatUiSession.current().activityLabel = null;
            ChatUiSession.current().activityTerminal = false;
        }
        notifyActivityListener(null);
        return true;
    }

    /**
     * Print complete lines above the active input buffer. JLine clears and restores
     * the prompt around the output, so background model/tool events cannot erase
     * text the user is typing. Falls back to stdout outside an interactive REPL.
     */
    public static void printAbove(String text) {
        if (ChatUiSession.current().isClosed()) return;
        String line = text == null ? "" : text;
        Consumer<String> output = ChatUiSession.current().contentOutput;
        if (output != null) {
            try {
                // Update the authoritative transcript first. The actual terminal write
                // must go through LineReader.printAbove: unlike callWidget(REDISPLAY),
                // JLine serializes printAbove with its active read loop and restores the
                // prompt/cursor without mutating input state from this background thread.
                output.accept(line);
            } catch (RuntimeException ignored) {
                output = null;
            }
            if (output != null) {
                // The modal owns the terminal surface. Keep recording output above,
                // but defer display until the picker restores the main view.
                if (ChatUiSession.current().temporaryWindowActive) {
                    return;
                }
                // A managed TUI sink schedules one complete frame through the
                // LineReader widget. Do not also call printAbove with raw tool
                // text: terminal auto-wrap can cross the reserved input row.
                if (ChatUiSession.current().contentRedraw != null) {
                    return;
                }
                LineReader reader = ChatUiSession.current().lineReaderRef;
                if (reader instanceof LineReaderImpl impl && impl.isReading()) {
                    try {
                        reader.printAbove(line);
                        return;
                    } catch (RuntimeException ignored) {
                        // JLine may be shutting down; redraw the retained view below.
                    }
                }
                redrawContentView();
                return;
            }
        }
        LineReader reader = ChatUiSession.current().lineReaderRef;
        if (reader instanceof LineReaderImpl impl && impl.isReading()) {
            try {
                reader.printAbove(line);
                return;
            } catch (RuntimeException ignored) {
                // Terminal may be shutting down; preserve the output via stdout.
            }
        }
        if (ChatUiSession.current().usesLegacyOutput()) System.out.println(line);
    }

    /**
     * Sets a supplier that provides queued message strings.
     * When non-empty, queued messages are rendered below the bottom border.
     */
    public static void setQueueSupplier(Supplier<List<String>> supplier) {
        synchronized (ChatUiSession.current()) {
            if (ChatUiSession.current().isClosed()) return;
            ChatUiSession.current().queueSupplier = supplier;
        }
    }

    /**
     * Returns the current terminal width, defaulting to 80 if unknown.
     */
    private static int getTermWidth() {
        Terminal t = ChatUiSession.current().terminalRef;
        if (t != null) {
            int w = t.getWidth();
            if (w > 0) return w;
        }
        return 80;
    }

    /**
     * Installs auto-trigger behavior on a LineReader: typing {@code /} at the
     * start of a line automatically pops up the completion list without requiring Tab.
     * <p>
     * In managed standard chat, candidates use its fixed lower activity rows so
     * they cannot scroll the transcript. Other readers retain the JLine
     * {@code post} fallback.
     */
    public static void enableAutoTrigger(LineReader reader) {
        if (ChatUiSession.current().isClosed()) return;
        if (!(reader instanceof LineReaderImpl impl)) return;

        installPasteSupport(impl);

        // Cache the reflective handle once
        if (postField == null) {
            try {
                postField = LineReaderImpl.class.getDeclaredField("post");
                postField.setAccessible(true);
            } catch (Exception e) {
                // The managed completion panel does not depend on this private
                // JLine field. Non-managed readers still retain plain Tab completion.
                postField = null;
            }
        }

        reader.unsetOpt(LineReader.Option.INSERT_TAB);
        reader.setOpt(LineReader.Option.DISABLE_EVENT_EXPANSION);

        Widget origSelfInsert = impl.getWidgets().get(LineReader.SELF_INSERT);
        Widget origBackDelete = impl.getWidgets().get(LineReader.BACKWARD_DELETE_CHAR);

        impl.getWidgets().put(LineReader.SELF_INSERT, ChatUiSession.current().captureWidget(() -> {
            boolean previous = IN_INPUT_WIDGET.get();
            IN_INPUT_WIDGET.set(true);
            try {
                clearInterruptedOnInput();
                origSelfInsert.apply();
                updatePostDisplay(impl);
                return true;
            } finally {
                IN_INPUT_WIDGET.set(previous);
            }
        }));

        impl.getWidgets().put(LineReader.BACKWARD_DELETE_CHAR, ChatUiSession.current().captureWidget(() -> {
            boolean previous = IN_INPUT_WIDGET.get();
            IN_INPUT_WIDGET.set(true);
            try {
                clearInterruptedOnInput();
                origBackDelete.apply();
                updatePostDisplay(impl);
                return true;
            } finally {
                IN_INPUT_WIDGET.set(previous);
            }
        }));

        Widget origCompleteWord = impl.getWidgets().get(LineReader.COMPLETE_WORD);
        if (origCompleteWord != null) {
            impl.getWidgets().put(LineReader.COMPLETE_WORD, ChatUiSession.current().captureWidget(() -> {
                boolean previous = IN_INPUT_WIDGET.get();
                IN_INPUT_WIDGET.set(true);
                try {
                    boolean result = origCompleteWord.apply();
                    updatePostDisplay(impl);
                    return result;
                } finally {
                    IN_INPUT_WIDGET.set(previous);
                }
            }));
        }

        // Hook up/down history navigation so border updates after recall
        Widget origUp = impl.getWidgets().get(LineReader.UP_LINE_OR_HISTORY);
        if (origUp != null) {
            impl.getWidgets().put(LineReader.UP_LINE_OR_HISTORY, ChatUiSession.current().captureWidget(() -> {
                boolean result = origUp.apply();
                updatePostDisplay(impl);
                return result;
            }));
        }

        Widget origDown = impl.getWidgets().get(LineReader.DOWN_LINE_OR_HISTORY);
        if (origDown != null) {
            impl.getWidgets().put(LineReader.DOWN_LINE_OR_HISTORY, ChatUiSession.current().captureWidget(() -> {
                boolean result = origDown.apply();
                updatePostDisplay(impl);
                return result;
            }));
        }

        // Show the bottom border immediately when the prompt first appears
        setBottomBorderOnly(impl);
    }

    /**
     * Insert clipboard text through JLine. Large blocks are represented by a
     * compact token while editing and restored byte-for-byte before ACCEPT_LINE.
     */
    static boolean insertPastedText(LineReader reader, String text) {
        if (!(reader instanceof LineReaderImpl impl) || text == null || text.isEmpty()) {
            return false;
        }
        boolean previous = IN_INPUT_WIDGET.get();
        IN_INPUT_WIDGET.set(true);
        try {
            clearInterruptedOnInput();
            if (text.codePointCount(0, text.length()) >= LARGE_PASTE_THRESHOLD) {
                writeCompactPaste(impl, text);
            } else {
                impl.getBuffer().write(text);
            }
            updatePostDisplay(impl);
            return true;
        } finally {
            IN_INPUT_WIDGET.set(previous);
        }
    }

    private static void installPasteSupport(LineReaderImpl impl) {
        if (!PASTE_SUPPORT_READERS.add(impl)) return;
        impl.setOpt(LineReader.Option.BRACKETED_PASTE);

        Widget beginPaste = impl.getWidgets().get(LineReader.BEGIN_PASTE);
        if (beginPaste == null) beginPaste = impl.getBuiltinWidgets().get(LineReader.BEGIN_PASTE);
        if (beginPaste != null) {
            Widget originalBeginPaste = beginPaste;
            impl.getWidgets().put(LineReader.BEGIN_PASTE, ChatUiSession.current().captureWidget(() -> {
                boolean previous = IN_INPUT_WIDGET.get();
                IN_INPUT_WIDGET.set(true);
                try {
                    int beforeLength = impl.getBuffer().length();
                    boolean result = originalBeginPaste.apply();
                    compactInsertedPaste(impl, beforeLength);
                    clearInterruptedOnInput();
                    updatePostDisplay(impl);
                    return result;
                } finally {
                    IN_INPUT_WIDGET.set(previous);
                }
            }));
        }

        Widget acceptLine = impl.getWidgets().get(LineReader.ACCEPT_LINE);
        if (acceptLine == null) acceptLine = impl.getBuiltinWidgets().get(LineReader.ACCEPT_LINE);
        if (acceptLine != null) {
            Widget originalAcceptLine = acceptLine;
            impl.getWidgets().put(LineReader.ACCEPT_LINE, ChatUiSession.current().captureWidget(() -> {
                clearManagedCompletion();
                expandLargePastes(impl);
                try {
                    return originalAcceptLine.apply();
                } finally {
                    LARGE_PASTES.remove(impl);
                }
            }));
        }
    }

    private static void compactInsertedPaste(LineReaderImpl impl, int beforeLength) {
        Buffer buffer = impl.getBuffer();
        int added = buffer.length() - beforeLength;
        if (added < LARGE_PASTE_THRESHOLD) return;
        int end = buffer.cursor();
        int start = end - added;
        if (start < 0 || end > buffer.length()) return;
        String pasted = buffer.substring(start, end);
        buffer.cursor(end);
        if (buffer.backspace(added) != added) return;
        writeCompactPaste(impl, pasted);
    }

    private static void writeCompactPaste(LineReaderImpl impl, String text) {
        int characterCount = text.codePointCount(0, text.length());
        String token = String.format(Locale.ROOT,
                "[Pasted %,d characters]", characterCount);
        LARGE_PASTES.computeIfAbsent(impl, ignored -> new ArrayList<>())
                .add(new LargePaste(token, text));
        impl.getBuffer().write(token);
    }

    private static void expandLargePastes(LineReaderImpl impl) {
        List<LargePaste> pastes = LARGE_PASTES.get(impl);
        if (pastes == null || pastes.isEmpty()) return;
        StringBuilder expanded = new StringBuilder(impl.getBuffer().toString());
        int searchFrom = 0;
        for (LargePaste paste : List.copyOf(pastes)) {
            int index = expanded.indexOf(paste.token(), searchFrom);
            if (index < 0) continue; // token was intentionally edited or deleted
            expanded.replace(index, index + paste.token().length(), paste.content());
            searchFrom = index + paste.content().length();
        }
        impl.getBuffer().clear();
        impl.getBuffer().write(expanded);
    }

    /**
     * Compatibility hook retained for callers. Persistent post restoration was
     * removed because its delayed redisplay raced asynchronous transcript output
     * and produced blank-line/feed corruption while the user was idle.
     */
    public static void schedulePostRestore() {
        // Slash candidates are installed synchronously by updatePostDisplay().
    }

    /**
     * Builds the bottom border string with ANSI dim styling.
     */
    private static String buildBottomBorder() {
        int borderWidth = Math.max(20, Math.min(getTermWidth(), 200));
        return DIM + "\u2500".repeat(borderWidth) + ANSI_RESET;
    }

    /**
     * Queue previews no longer occupy JLine's post area. The live status bar owns
     * queue counts and enqueue/dequeue acknowledgements are transcript events.
     */
    static String buildPostWithQueue() {
        return "";
    }

    /** Clear transient slash-completion rows without reserving permanent post rows. */
    private static void setBottomBorderOnly(LineReaderImpl impl) {
        try {
            if (postField == null) return;
            postField.set(impl, (Supplier<AttributedString>) () -> new AttributedString(""));
        } catch (Exception ignored) {
        }
    }

    static void refreshPostDisplay(LineReader reader) {
        if (reader instanceof LineReaderImpl impl) {
            updatePostDisplay(impl);
        }
    }

    private static boolean updateManagedCompletion(
            LineReaderImpl impl, List<Candidate> candidates) {
        if (ChatUiSession.current().isClosed()) return false;
        CompletionDisplay display = ChatUiSession.current().completionDisplay;
        if (display == null) return false;

        List<CompletionItem> items = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            String value = candidate.displ() != null ? candidate.displ() : candidate.value();
            items.add(new CompletionItem(value, candidate.descr()));
        }

        try {
            // The prompt is anchored to the final row of a restricted scroll region.
            // Any JLine post rows would scroll that transcript, so a managed panel
            // must clear the post before scheduling its own cursor-safe redraw.
            setBottomBorderOnly(impl);
            if (!display.update(List.copyOf(items))) return false;
            impl.unsetOpt(LineReader.Option.AUTO_LIST);
            impl.unsetOpt(LineReader.Option.LIST_AMBIGUOUS);
            impl.unsetOpt(LineReader.Option.AUTO_MENU);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static void clearManagedCompletion() {
        if (ChatUiSession.current().isClosed()) return;
        CompletionDisplay display = ChatUiSession.current().completionDisplay;
        if (display == null) return;
        try {
            display.update(List.of());
        } catch (RuntimeException ignored) {
            // Completion cleanup must never block ACCEPT_LINE.
        }
    }

    private static void redisplayWithContent(LineReaderImpl impl) {
        // JLine itself redraws after a SELF_INSERT widget returns. Calling
        // REDISPLAY here would re-enter the widget while its buffer/cursor state
        // is still being updated, which can make printable keys disappear. Keep
        // the explicit repaint for out-of-band completion/activity changes only.
        if (IN_INPUT_WIDGET.get()) {
            return;
        }
        // Cursor-addressed content rendering moves the physical cursor outside
        // JLine's cached display model. Clear first so REDISPLAY must repaint the
        // prompt and restore the actual editing cursor instead of becoming a no-op.
        impl.callWidget(LineReader.CLEAR);
        redrawContentView();
        impl.callWidget(LineReader.REDISPLAY);
    }

    /**
     * Updates slash completion after an input edit. Managed standard chat sends
     * candidates to its fixed activity panel; other readers use JLine's
     * {@code post} field as a compatibility fallback.
     * <p>
     * IMPORTANT: All strings containing ANSI escape codes must be wrapped
     * via {@link AttributedString#fromAnsi} — the plain constructor treats
     * escape bytes as literal characters, inflating the measured width and
     * causing cursor mis-positioning.
     */
    @SuppressWarnings("unchecked")
    private static void updatePostDisplay(LineReaderImpl impl) {
        if (ChatUiSession.current().isClosed()) return;
        try {
            if (postField == null && ChatUiSession.current().completionDisplay == null) return;
            String buf = impl.getBuffer().toString();

            // No slash prefix: restore the activity panel and clear transient post rows.
            if (buf.isEmpty() || !buf.startsWith("/")) {
                if (updateManagedCompletion(impl, List.of())) return;
                setBottomBorderOnly(impl);
                redisplayWithContent(impl);
                return;
            }

            // Gather candidates
            List<Candidate> candidates = new ArrayList<>();
            Completer completer = impl.getCompleter();
            if (completer == null) return;
            completer.complete(impl, impl.getParser().parse(buf, buf.length()), candidates);

            if (candidates.isEmpty()) {
                // No matches: remove transient completion rows and restore activity.
                if (updateManagedCompletion(impl, List.of())) return;
                setBottomBorderOnly(impl);
                redisplayWithContent(impl);
                return;
            }

            if (updateManagedCompletion(impl, candidates)) return;

            String bottomBorder = buildBottomBorder();
            int total = candidates.size();
            int showing = Math.min(total, MAX_DISPLAY_CANDIDATES);

            // Find longest name for column alignment
            int maxCmd = 0;
            for (int i = 0; i < showing; i++) {
                Candidate c = candidates.get(i);
                int len = (c.displ() != null ? c.displ() : c.value()).length();
                if (len > maxCmd) maxCmd = len;
            }

            // Build display: bottom border first, then candidates below
            StringBuilder sb = new StringBuilder();
            sb.append(bottomBorder);

            for (int i = 0; i < showing; i++) {
                Candidate c = candidates.get(i);
                String name = c.displ() != null ? c.displ() : c.value();
                String desc = c.descr();
                sb.append('\n').append("  ").append(name);
                if (desc != null && !desc.isEmpty()) {
                    int pad = maxCmd - name.length() + 2;
                    for (int p = 0; p < pad; p++) sb.append(' ');
                    sb.append("\u2014 ").append(desc);
                }
            }
            if (total > showing) {
                sb.append('\n').append("  ... and ").append(total - showing).append(" more");
            }

            // fromAnsi() so JLine correctly measures visible width
            AttributedString postContent = AttributedString.fromAnsi(sb.toString());
            postField.set(impl, (Supplier<AttributedString>) () -> postContent);
            redisplayWithContent(impl);
        } catch (Exception ignored) {
            // Never let completion display break typing
        }
    }
}
