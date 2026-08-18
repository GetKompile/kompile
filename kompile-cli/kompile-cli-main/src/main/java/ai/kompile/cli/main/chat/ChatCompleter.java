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
        COMMANDS.put("/clear", "Clear conversation");
        COMMANDS.put("/compact", "Summarize conversation to free context");
        COMMANDS.put("/auto-compact", "Configure automatic model-aware compaction");
        COMMANDS.put("/memory", "Show memory entries");
        COMMANDS.put("/recall", "Recall from memory");
        COMMANDS.put("/transcript", "Show transcript");
        COMMANDS.put("/conversations", "List conversations");
        COMMANDS.put("/sessions", "List sessions");

        // RAG & planning
        COMMANDS.put("/rag", "Toggle or configure RAG");
        COMMANDS.put("/plan", "Toggle plan mode");
        COMMANDS.put("/todos", "Show todo list");

        // Config & permissions
        COMMANDS.put("/config", "Show or edit configuration");
        COMMANDS.put("/permissions", "Manage permissions");
        COMMANDS.put("/model", "Switch or show model");
        COMMANDS.put("/mode", "Switch interaction mode");

        // Queue & jobs
        COMMANDS.put("/queue", "Show message queue");
        COMMANDS.put("/queues", "List all queues");
        COMMANDS.put("/queue-send", "Send message to queue");
        COMMANDS.put("/queue-send-all", "Send to all queues");
        COMMANDS.put("/queue-remove", "Remove from queue");
        COMMANDS.put("/queue-clear", "Clear queue");
        COMMANDS.put("/queue-status", "Show queue status");
        COMMANDS.put("/jobs", "List background jobs");
        COMMANDS.put("/jobs-remove", "Remove a background job");
        COMMANDS.put("/jobs-clear", "Clear all jobs");
        COMMANDS.put("/activity", "Manage processes, subagents, and logs");
        COMMANDS.put("/processes", "Show processes & subagents");
        COMMANDS.put("/process-kill", "Kill a running process");
        COMMANDS.put("/process-output", "View process output");
        COMMANDS.put("/process-status", "Show process or watcher status");
        COMMANDS.put("/statusbar", "Toggle status bar");
        COMMANDS.put("/auto-dequeue", "Toggle auto-dequeue");
        COMMANDS.put("/stats", "Show session statistics");

        // Roles & skills
        COMMANDS.put("/skills", "List available skills");
        COMMANDS.put("/roles", "Manage roles");
        COMMANDS.put("/role", "Show or assign role");
        COMMANDS.put("/enforce", "Toggle or configure enforcer");
        COMMANDS.put("/enforcer", "Toggle or configure enforcer");
        COMMANDS.put("/rules", "Show active enforcer rules");
        COMMANDS.put("/archive", "List archived enforced turns");
        COMMANDS.put("/rollback", "Roll back archived turns");
        COMMANDS.put("/diff", "Show an archived turn diff");
        COMMANDS.put("/purge", "Purge session diff archive");

        // Passthrough & forwarding
        COMMANDS.put("/passthrough", "Toggle passthrough mode");
        COMMANDS.put("/keys", "Forward keys directly to the agent");
        COMMANDS.put("/render", "Show or set managed render mode");
        COMMANDS.put("/forward", "Forward command to agent");
        COMMANDS.put("/resume", "Resume a session");
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
        List<String[]> enforcerArgs = List.of(
                new String[]{"status", "Show enforcer status"},
                new String[]{"on", "Enable enforcer"},
                new String[]{"off", "Disable enforcer"},
                new String[]{"pause", "Pause live enforcement"},
                new String[]{"resume", "Resume live enforcement"},
                new String[]{"judgements", "Show recorded judgements"},
                new String[]{"show", "Show enforcer config"},
                new String[]{"rules", "Show active enforcer rules"},
                new String[]{"init", "Configure enforcer"},
                new String[]{"delete", "Remove enforcer config"},
                new String[]{"reload", "Reload enforcer config"},
                new String[]{"run", "Show enforcer launch command"}
        );
        SUB_ARGS.put("/enforce", enforcerArgs);
        SUB_ARGS.put("/enforcer", enforcerArgs);
        SUB_ARGS.put("/rag", List.of(
                new String[]{"on", "Enable RAG"},
                new String[]{"off", "Disable RAG"}
        ));
        SUB_ARGS.put("/plan", List.of(
                new String[]{"on", "Enable plan mode"},
                new String[]{"off", "Disable plan mode"}
        ));
        SUB_ARGS.put("/auto-compact", List.of(
                new String[]{"status", "Show active model limits and trigger"},
                new String[]{"on", "Enable automatic compaction"},
                new String[]{"off", "Disable automatic compaction"},
                new String[]{"threshold", "Set trigger percentage"},
                new String[]{"reserve", "Set reserved input headroom"},
                new String[]{"context", "Override context window"},
                new String[]{"output", "Override max output tokens"}
        ));
        SUB_ARGS.put("/mode", List.of(
                new String[]{"standard", "Standard chat mode"},
                new String[]{"passthrough", "Agent passthrough mode"},
                new String[]{"plan", "Planning mode"}
        ));
        SUB_ARGS.put("/render", List.of(
                new String[]{"mirror", "Render the agent terminal directly"},
                new String[]{"decoded", "Render the decoded transcript"},
                new String[]{"decode", "Alias for decoded render mode"}
        ));
        List<String[]> activityArgs = List.of(
                new String[]{"enter", "Inspect activity or subagent"},
                new String[]{"inspect", "Inspect activity or subagent"},
                new String[]{"status", "Inspect activity status"},
                new String[]{"logs", "Show activity logs"},
                new String[]{"kill", "Kill or cancel activity"},
                new String[]{"remove", "Remove completed activity"},
                new String[]{"clear", "Clear completed activities"},
                new String[]{"close", "Close activity menu"}
        );
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

    /** Max candidates to show below the prompt. */
    private static final int MAX_DISPLAY_CANDIDATES = 15;

    /** Cached reflective handle to LineReaderImpl.post (protected field). */
    private static volatile Field postField;

    /**
     * Marks a SELF_INSERT/BACKWARD_DELETE dispatch so completion updates never
     * call JLine REDISPLAY recursively from inside the active widget.
     */
    private static final ThreadLocal<Boolean> IN_INPUT_WIDGET =
            ThreadLocal.withInitial(() -> false);

    /** Terminal reference for measuring width (set via {@link #setTerminalRef}). */
    private static volatile Terminal terminalRef;

    /** Active standard-chat reader used for thread-safe asynchronous output. */
    private static volatile LineReader lineReaderRef;

    /** Repaints the TUI content after JLine redraws the prompt/post area. */
    private static volatile Runnable contentRedraw;

    /**
     * Authoritative output sink for the active TUI transcript. Keeping this separate
     * from JLine's printAbove prevents streamed tool/model output from disappearing
     * on the next prompt redisplay.
     */
    private static volatile Consumer<String> contentOutput;

    /** Retained compatibility hook; queue state is rendered by the status bar, not JLine post rows. */
    private static volatile Supplier<List<String>> queueSupplier;

    /** Current standard-chat model activity rendered by the persistent status bar. */
    private static volatile String activityLabel;
    /** Terminal activity must remain visible without being rendered as a live spinner. */
    private static volatile boolean activityTerminal;

    /** Activity shown after Escape interrupts a turn until the next edit begins. */
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
        lineReaderRef = reader;
        terminalRef = terminal;
    }

    /**
     * Register the active TUI repaint callback. JLine's REDISPLAY redraws the
     * prompt and may clear cursor-addressed transcript rows, so the TUI must
     * repaint its authoritative view immediately afterwards.
     */
    public static void setContentRedraw(Runnable redraw) {
        contentRedraw = redraw;
    }

    /** Register the active TUI transcript sink for asynchronous output. */
    public static void setContentOutput(Consumer<String> output) {
        contentOutput = output;
    }

    private static void redrawContentView() {
        Runnable redraw = contentRedraw;
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
        return lineReaderRef != null;
    }

    public static void clearTerminalRef(LineReader reader) {
        if (lineReaderRef == reader) {
            lineReaderRef = null;
            terminalRef = null;
            contentRedraw = null;
            contentOutput = null;
            cachedImpl = null;
            activityLabel = null;
            activityTerminal = false;
        }
    }

    public static void setActivity(String activity) {
        activityLabel = activity == null || activity.isBlank() ? null : activity;
        activityTerminal = false;
    }

    public static String getActivity() {
        return activityLabel;
    }

    /** Mark the foreground turn as interrupted; the next input edit clears it. */
    public static void markInterrupted() {
        activityLabel = INTERRUPTED_ACTIVITY;
        activityTerminal = true;
    }

    public static boolean isActivityTerminal() {
        return activityTerminal;
    }

    /** Clear only the transient interruption marker, preserving normal activity state. */
    public static boolean clearInterruptedOnInput() {
        if (INTERRUPTED_ACTIVITY.equals(activityLabel)) {
            activityLabel = null;
            activityTerminal = false;
            return true;
        }
        return false;
    }

    /**
     * Print complete lines above the active input buffer. JLine clears and restores
     * the prompt around the output, so background model/tool events cannot erase
     * text the user is typing. Falls back to stdout outside an interactive REPL.
     */
    public static void printAbove(String text) {
        String line = text == null ? "" : text;
        Consumer<String> output = contentOutput;
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
                LineReader reader = lineReaderRef;
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
        LineReader reader = lineReaderRef;
        if (reader instanceof LineReaderImpl impl && impl.isReading()) {
            try {
                reader.printAbove(line);
                return;
            } catch (RuntimeException ignored) {
                // Terminal may be shutting down; preserve the output via stdout.
            }
        }
        System.out.println(line);
    }

    /**
     * Sets a supplier that provides queued message strings.
     * When non-empty, queued messages are rendered below the bottom border.
     */
    public static void setQueueSupplier(Supplier<List<String>> supplier) {
        queueSupplier = supplier;
    }

    /**
     * Returns the current terminal width, defaulting to 80 if unknown.
     */
    private static int getTermWidth() {
        Terminal t = terminalRef;
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
     * A bottom border is always rendered below the input line via the JLine
     * {@code post} field. When slash-command candidates match, they appear
     * below the bottom border.
     */
    public static void enableAutoTrigger(LineReader reader) {
        if (!(reader instanceof LineReaderImpl impl)) return;

        // Cache the reflective handle once
        if (postField == null) {
            try {
                postField = LineReaderImpl.class.getDeclaredField("post");
                postField.setAccessible(true);
            } catch (Exception e) {
                return; // can't set post — fall back to plain Tab completion
            }
        }

        cachedImpl = impl;

        reader.unsetOpt(LineReader.Option.INSERT_TAB);
        reader.setOpt(LineReader.Option.DISABLE_EVENT_EXPANSION);

        Widget origSelfInsert = impl.getWidgets().get(LineReader.SELF_INSERT);
        Widget origBackDelete = impl.getWidgets().get(LineReader.BACKWARD_DELETE_CHAR);

        impl.getWidgets().put(LineReader.SELF_INSERT, () -> {
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
        });

        impl.getWidgets().put(LineReader.BACKWARD_DELETE_CHAR, () -> {
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
        });

        // Hook up/down history navigation so border updates after recall
        Widget origUp = impl.getWidgets().get(LineReader.UP_LINE_OR_HISTORY);
        if (origUp != null) {
            impl.getWidgets().put(LineReader.UP_LINE_OR_HISTORY, () -> {
                boolean result = origUp.apply();
                updatePostDisplay(impl);
                return result;
            });
        }

        Widget origDown = impl.getWidgets().get(LineReader.DOWN_LINE_OR_HISTORY);
        if (origDown != null) {
            impl.getWidgets().put(LineReader.DOWN_LINE_OR_HISTORY, () -> {
                boolean result = origDown.apply();
                updatePostDisplay(impl);
                return result;
            });
        }

        // Show the bottom border immediately when the prompt first appears
        setBottomBorderOnly(impl);
    }

    /** Cached impl reference for post-restore scheduling. */
    private static volatile LineReaderImpl cachedImpl;

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

    private static void redisplayWithContent(LineReaderImpl impl) {
        // JLine itself redraws after a SELF_INSERT widget returns. Calling
        // REDISPLAY here would re-enter the widget while its buffer/cursor state
        // is still being updated, which can make printable keys disappear. Keep
        // the explicit repaint for out-of-band completion/activity changes only.
        if (IN_INPUT_WIDGET.get()) {
            return;
        }
        redrawContentView();
        impl.callWidget(LineReader.REDISPLAY);
    }

    /**
     * Sets JLine's {@code post} field to show a bottom border below the
     * input line, followed by any matching candidates when typing slash
     * commands. The border + candidates appear below the editing buffer
     * via JLine's post-display mechanism, which coordinates properly with
     * cursor position and screen redraws.
     * <p>
     * IMPORTANT: All strings containing ANSI escape codes must be wrapped
     * via {@link AttributedString#fromAnsi} — the plain constructor treats
     * escape bytes as literal characters, inflating the measured width and
     * causing cursor mis-positioning.
     */
    @SuppressWarnings("unchecked")
    private static void updatePostDisplay(LineReaderImpl impl) {
        try {
            if (postField == null) return;
            String buf = impl.getBuffer().toString();

            String bottomBorder = buildBottomBorder();

            // No slash prefix: the status bar owns persistent state, so clear post rows.
            if (buf.isEmpty() || !buf.startsWith("/")) {
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
                // No matches: remove transient completion rows.
                setBottomBorderOnly(impl);
                redisplayWithContent(impl);
                return;
            }

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
