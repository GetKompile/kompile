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

package ai.kompile.cli.main.chat.render;

import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.tools.TodoWriteTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ANSI terminal renderer for tool calls, subagents, todos, and agent output.
 * Provides colored, structured output comparable to OpenCode's TUI rendering.
 *
 * Detects terminal capability and falls back to plain text when ANSI is not supported.
 */
public class TerminalRenderer {

    // ANSI escape codes
    private static final String ESC = "\033[";
    private static final String RESET = ESC + "0m";
    private static final String BOLD = ESC + "1m";
    private static final String DIM = ESC + "2m";
    private static final String ITALIC = ESC + "3m";
    private static final String UNDERLINE = ESC + "4m";

    // Colors
    private static final String FG_RED = ESC + "31m";
    private static final String FG_GREEN = ESC + "32m";
    private static final String FG_YELLOW = ESC + "33m";
    private static final String FG_BLUE = ESC + "34m";
    private static final String FG_MAGENTA = ESC + "35m";
    private static final String FG_CYAN = ESC + "36m";
    private static final String FG_WHITE = ESC + "37m";
    private static final String FG_GRAY = ESC + "90m";

    // Braille spinner frames (like OpenCode's)
    private static final String[] SPINNER_FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧"};
    private static final int MAX_CONTEXT_TOOL_BUCKETS = 4;

    // Tool icons (Unicode) — keys are lowercase short names (no MCP prefix)
    private static final Map<String, String> TOOL_ICONS = Map.ofEntries(
            Map.entry("read", "📖"),
            Map.entry("write", "✏️"),
            Map.entry("edit", "✏️"),
            Map.entry("patch", "🩹"),
            Map.entry("bash", "⚡"),
            Map.entry("grep", "🔍"),
            Map.entry("glob", "📂"),
            Map.entry("list", "📋"),
            Map.entry("webfetch", "🌐"),
            Map.entry("websearch", "🔎"),
            Map.entry("task", "🤖"),
            Map.entry("multi_task", "🤖"),
            Map.entry("quorum_task", "🤖"),
            Map.entry("todowrite", "📝"),
            Map.entry("todoread", "📝"),
            Map.entry("exit_plan_mode", "✅"),
            Map.entry("code_search", "🔍"),
            Map.entry("code_graph", "🔗"),
            Map.entry("rag_search", "📚"),
            Map.entry("graph_search", "🔗"),
            Map.entry("memory", "🧠"),
            Map.entry("semantic_memory", "🧠"),
            Map.entry("explore", "🗺️"),
            Map.entry("process", "⚙️"),
            Map.entry("browser", "🌐"),
            Map.entry("edit_coordinator", "🔒"),
            Map.entry("toolsearch", "🔍"),
            Map.entry("agent", "🤖"),
            Map.entry("exec", "⚡")
    );

    /** Shared Jackson mapper for JSON input parsing. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Primary parameter keys per tool — these are extracted from JSON input
     * and shown as the human-readable description instead of raw JSON.
     * Order matters: first key found wins for single-line display.
     */
    private static final Map<String, String[]> PRIMARY_PARAMS = Map.ofEntries(
            Map.entry("read", new String[]{"file_path"}),
            Map.entry("write", new String[]{"file_path"}),
            Map.entry("edit", new String[]{"file_path"}),
            Map.entry("patch", new String[]{"file_path"}),
            Map.entry("glob", new String[]{"pattern"}),
            Map.entry("grep", new String[]{"pattern", "path"}),
            Map.entry("bash", new String[]{"command"}),
            Map.entry("list", new String[]{"path"}),
            Map.entry("webfetch", new String[]{"url"}),
            Map.entry("websearch", new String[]{"query"}),
            Map.entry("task", new String[]{"description"}),
            Map.entry("multi_task", new String[]{"description"}),
            Map.entry("quorum_task", new String[]{"description"}),
            Map.entry("toolsearch", new String[]{"query"}),
            Map.entry("code_search", new String[]{"query"}),
            Map.entry("code_graph", new String[]{"query"}),
            Map.entry("rag_search", new String[]{"query"}),
            Map.entry("graph_search", new String[]{"query"}),
            Map.entry("memory", new String[]{"query", "key"}),
            Map.entry("explore", new String[]{"query"}),
            Map.entry("agent", new String[]{"description"}),
            Map.entry("browser", new String[]{"url"})
    );

    private final boolean ansiEnabled;

    public TerminalRenderer() {
        this.ansiEnabled = detectAnsiSupport();
    }

    public TerminalRenderer(boolean ansiEnabled) {
        this.ansiEnabled = ansiEnabled;
    }

    // ========================================================================
    // Tool call rendering
    // ========================================================================

    /**
     * Render a tool call start (pending/running state).
     * Automatically prettifies MCP-prefixed tool names and parses JSON input
     * into human-readable descriptions.
     * <p>
     * Example: {@code mcp__kompile__read} with input
     * {@code {"file_path":"src/app/foo.ts"}} renders as:
     * <pre>  📖 Read src/app/foo.ts</pre>
     */
    public String renderToolCallStart(String toolName, String description) {
        String cleanName = stripMcpPrefix(toolName);
        String displayName = prettifyToolName(toolName);
        String icon = TOOL_ICONS.getOrDefault(cleanName, "🔧");

        // Try to prettify JSON input into a readable summary
        String prettyDesc = prettifyToolInput(cleanName, description, 80);
        String desc = !prettyDesc.isEmpty() ? " " + dim(prettyDesc) : "";
        return "  " + icon + " " + bold(cyan(displayName)) + desc;
    }

    /**
     * Render a tool call running with spinner.
     */
    public String renderToolCallRunning(String toolName, int spinnerFrame) {
        String cleanName = stripMcpPrefix(toolName);
        String displayName = prettifyToolName(toolName);
        String spinner = ansiEnabled ? yellow(SPINNER_FRAMES[spinnerFrame % SPINNER_FRAMES.length]) : "...";
        return "  " + spinner + " " + cyan(displayName) + " " + dim("running...");
    }

    /**
     * Render a completed tool call with result summary.
     */
    public String renderToolCallComplete(String toolName, ToolResult result) {
        String cleanName = stripMcpPrefix(toolName);
        String displayName = prettifyToolName(toolName);
        String icon = TOOL_ICONS.getOrDefault(cleanName, "🔧");
        StringBuilder sb = new StringBuilder();

        if (result.isError()) {
            sb.append("  ").append(icon).append(" ").append(bold(red(displayName)));
            sb.append(" ").append(red("✗"));
            String errorPreview = truncatePreview(result.getOutput(), 120);
            sb.append("\n    ").append(red(errorPreview));
        } else {
            sb.append("  ").append(icon).append(" ").append(bold(green(displayName)));
            sb.append(" ").append(green("✓"));

            // Show title if present
            if (result.getTitle() != null && !result.getTitle().isEmpty() && !"error".equals(result.getTitle())) {
                sb.append(" ").append(dim(result.getTitle()));
            }

            // Show metadata summary
            Map<String, Object> meta = result.getMetadata();
            if (!meta.isEmpty()) {
                sb.append(" ").append(dim(renderMetadata(meta)));
            }

            // Show output preview for certain tools
            String output = result.getOutput();
            if (output != null && !output.isEmpty()) {
                String preview = truncatePreview(output, 200);
                if (shouldShowPreview(toolName, meta)) {
                    sb.append("\n").append(dim(indentLines(preview, "    ")));
                }
            }
        }

        return sb.toString();
    }

    /**
     * Render a tool call that was denied (permission).
     */
    public String renderToolCallDenied(String toolName, String reason) {
        String cleanName = stripMcpPrefix(toolName);
        String displayName = prettifyToolName(toolName);
        String icon = TOOL_ICONS.getOrDefault(cleanName, "🔧");
        return "  " + icon + " " + bold(yellow(displayName)) + " " + yellow("⊘ denied") +
                (reason != null ? " " + dim(reason) : "");
    }

    // ========================================================================
    // Subagent rendering
    // ========================================================================

    /**
     * Render subagent start.
     */
    public String renderSubagentStart(String agentType, String description) {
        return "\n  " + magenta("┌─") + " " + bold(magenta("Subagent: " + agentType)) +
                (description != null ? " " + dim("— " + description) : "") +
                "\n  " + magenta("│");
    }

    /**
     * Render a subagent tool call (indented under the subagent block).
     */
    public String renderSubagentToolCall(String toolName, boolean isError) {
        String cleanName = stripMcpPrefix(toolName);
        String displayName = prettifyToolName(toolName);
        String icon = TOOL_ICONS.getOrDefault(cleanName, "🔧");
        String status = isError ? red("✗") : green("✓");
        return "  " + magenta("│") + "  " + icon + " " + cyan(displayName) + " " + status;
    }

    /**
     * Render subagent completion.
     */
    public String renderSubagentComplete(String agentType, long durationMs) {
        String timing = durationMs > 0 ? " " + dim("(" + durationMs + "ms)") : "";
        return "  " + magenta("│") + "\n  " + magenta("└─") + " " +
                bold(green("Subagent complete")) + timing;
    }

    /**
     * Render subagent error.
     */
    public String renderSubagentError(String agentType, String error) {
        return "  " + magenta("│") + "\n  " + magenta("└─") + " " +
                bold(red("Subagent failed: ")) + red(truncatePreview(error, 120));
    }

    // ========================================================================
    // Todo list rendering
    // ========================================================================

    /**
     * Render the full todo list with progress indicator.
     */
    public String renderTodoList(List<TodoWriteTool.TodoItem> todos) {
        if (todos.isEmpty()) {
            return dim("  No tasks");
        }

        int total = todos.size();
        int completed = 0, inProgress = 0, pending = 0, cancelled = 0;
        for (TodoWriteTool.TodoItem item : todos) {
            switch (item.status) {
                case "completed": completed++; break;
                case "in_progress": inProgress++; break;
                case "cancelled": cancelled++; break;
                default: pending++; break;
            }
        }

        StringBuilder sb = new StringBuilder();

        // Progress header
        sb.append("  ").append(bold("Tasks"));
        sb.append(" ").append(dim("["));
        sb.append(green(String.valueOf(completed)));
        sb.append(dim("/"));
        sb.append(String.valueOf(total));
        sb.append(dim("]"));

        // Progress bar
        int barWidth = 20;
        int filled = total > 0 ? (completed * barWidth) / total : 0;
        sb.append(" ");
        sb.append(green("█".repeat(filled)));
        sb.append(dim("░".repeat(barWidth - filled)));
        sb.append("\n");

        // Individual items
        for (TodoWriteTool.TodoItem item : todos) {
            sb.append(renderTodoItem(item)).append("\n");
        }

        return sb.toString().stripTrailing();
    }

    /**
     * Render a single todo item with status icon and color.
     */
    public String renderTodoItem(TodoWriteTool.TodoItem item) {
        String icon;
        String color;
        boolean strikethrough = false;

        switch (item.status) {
            case "completed":
                icon = green("✓");
                color = "dim";
                strikethrough = true;
                break;
            case "in_progress":
                icon = yellow("●");
                color = "yellow";
                break;
            case "cancelled":
                icon = red("✗");
                color = "dim";
                strikethrough = true;
                break;
            default: // pending
                icon = dim("○");
                color = "normal";
                break;
        }

        String subject = item.subject;
        if (strikethrough && ansiEnabled) {
            subject = ESC + "9m" + subject + RESET; // strikethrough
        }

        switch (color) {
            case "dim": subject = dim(subject); break;
            case "yellow": subject = yellow(subject); break;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("  ").append(icon).append(" ").append(dim("#" + item.id)).append(" ").append(subject);

        // Show priority if not medium (default)
        if (item.priority != null && !"medium".equals(item.priority)) {
            String priorityLabel = "high".equals(item.priority) ? red("!high") : dim("low");
            sb.append(" ").append(priorityLabel);
        }

        if (item.description != null && !item.description.isEmpty()) {
            sb.append("\n    ").append(dim(truncatePreview(item.description, 80)));
        }

        return sb.toString();
    }

    /**
     * Render a todo update event (when a task status changes).
     */
    public String renderTodoUpdate(String taskId, String subject, String oldStatus, String newStatus) {
        String icon = switch (newStatus) {
            case "completed" -> green("✓");
            case "in_progress" -> yellow("●");
            case "cancelled" -> red("✗");
            default -> dim("○");
        };
        return "  " + icon + " " + dim("#" + taskId) + " " + subject +
                " " + dim(oldStatus + " → " + newStatus);
    }

    // ========================================================================
    // Agent loop rendering
    // ========================================================================

    /**
     * Render the start of an agent turn (step counter).
     */
    public String renderAgentTurnStart(int step, int maxSteps) {
        return dim("─── step " + step + "/" + maxSteps + " ───");
    }

    /**
     * Render context grouping for read-only tools.
     * Tool names in the map are prettified for display.
     */
    public String renderContextGroup(Map<String, Integer> toolCounts) {
        if (toolCounts == null || toolCounts.isEmpty()) {
            return "";
        }

        Map<String, Integer> aggregated = new LinkedHashMap<>();
        int totalCalls = 0;
        for (Map.Entry<String, Integer> entry : toolCounts.entrySet()) {
            int count = entry.getValue() != null ? entry.getValue() : 0;
            if (count <= 0) {
                continue;
            }
            String cleanName = stripMcpPrefix(entry.getKey());
            if (cleanName.isEmpty()) {
                cleanName = "unknown";
            }
            aggregated.merge(cleanName, count, Integer::sum);
            totalCalls += count;
        }

        if (aggregated.isEmpty()) {
            return "";
        }

        List<Map.Entry<String, Integer>> entries = new ArrayList<>(aggregated.entrySet());
        entries.sort(Comparator
                .<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                .reversed()
                .thenComparing(Map.Entry::getKey));

        StringBuilder sb = new StringBuilder();
        sb.append("  ").append(dim("Gathered context: "));
        sb.append(cyan(String.valueOf(totalCalls))).append(dim(totalCalls == 1 ? " call" : " calls"));
        sb.append(dim(" ("));
        boolean first = true;
        int shownCalls = 0;
        int shownBuckets = Math.min(entries.size(), MAX_CONTEXT_TOOL_BUCKETS);
        for (int i = 0; i < shownBuckets; i++) {
            Map.Entry<String, Integer> entry = entries.get(i);
            if (!first) sb.append(dim(", "));
            String displayName = prettifyToolName(entry.getKey());
            sb.append(cyan(String.valueOf(entry.getValue())))
                    .append(dim(" " + displayName));
            if (entry.getValue() > 1) sb.append(dim("s"));
            first = false;
            shownCalls += entry.getValue();
        }

        int remainingCalls = totalCalls - shownCalls;
        int remainingBuckets = entries.size() - shownBuckets;
        if (remainingCalls > 0) {
            if (!first) sb.append(dim(", "));
            sb.append(dim("+")).append(cyan(String.valueOf(remainingCalls))).append(dim(" more"));
            if (remainingBuckets > 0) {
                sb.append(dim(" across ")).append(cyan(String.valueOf(remainingBuckets)))
                        .append(dim(remainingBuckets == 1 ? " tool" : " tools"));
            }
        }
        sb.append(dim(")"));
        return sb.toString();
    }

    /**
     * Render compaction notice.
     */
    public String renderCompactionNotice(int tokensBefore, int tokensAfter) {
        return "\n" + dim("  ─── context compacted: " + tokensBefore + " → " + tokensAfter + " tokens ───") + "\n";
    }

    /**
     * Render a max-steps warning.
     */
    public String renderMaxStepsWarning(int maxSteps) {
        return "\n" + yellow("⚠ Agent reached maximum steps (" + maxSteps + ")");
    }

    // ========================================================================
    // Spinner for long-running tools
    // ========================================================================

    /**
     * Start a spinner thread that updates in-place. Returns a handle to stop it.
     */
    public SpinnerHandle startSpinner(String toolName) {
        String displayName = prettifyToolName(toolName);
        if (!ansiEnabled) {
            System.out.print("  " + displayName + "...");
            System.out.flush();
            return new SpinnerHandle(null);
        }

        AtomicBoolean running = new AtomicBoolean(true);
        Thread spinnerThread = new Thread(() -> {
            int frame = 0;
            while (running.get()) {
                String spinner = SPINNER_FRAMES[frame % SPINNER_FRAMES.length];
                // Move cursor to start of line, clear line, print spinner
                System.out.print("\r" + ESC + "2K  " + yellow(spinner) + " " + cyan(displayName) + " " + dim("running..."));
                System.out.flush();
                frame++;
                try {
                    Thread.sleep(80);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "spinner-" + toolName);
        spinnerThread.setDaemon(true);
        spinnerThread.start();

        return new SpinnerHandle(running);
    }

    /**
     * Handle to stop a running spinner.
     */
    public static class SpinnerHandle {
        private final AtomicBoolean running;
        /** Volatile phase override — spinner thread reads this each frame. */
        private volatile String phaseOverride;

        SpinnerHandle(AtomicBoolean running) {
            this.running = running;
        }

        public void stop() {
            if (running != null) {
                running.set(false);
                // Clear the spinner line
                System.out.print("\r" + ESC + "2K");
                System.out.flush();
            }
        }

        /**
         * Change the spinner phase text (e.g. from "Generating" to "Thinking").
         * The spinner thread picks this up on the next frame.
         */
        public void setPhase(String phase) {
            this.phaseOverride = phase;
        }
    }

    /**
     * Returns a no-op SpinnerHandle that does nothing on stop/setPhase.
     * Used when the spinner would conflict with an active readline.
     */
    public SpinnerHandle noOpSpinner() {
        return new SpinnerHandle(null);
    }

    // ========================================================================
    // Generating spinner (animated waiting indicator during LLM response)
    // ========================================================================

    /**
     * Start an animated "Generating..." spinner with terminal title update.
     * Shows a braille spinner animation on the current line and sets the
     * terminal tab/title to indicate processing is in progress.
     *
     * @param chainInfo optional queue chain info (e.g., " [2/5]")
     * @return a SpinnerHandle to stop the spinner when the response arrives
     */
    public SpinnerHandle startStaticSpinner(String chainInfo) {
        if (chainInfo == null) chainInfo = "";
        final String chain = chainInfo;
        setTerminalTitle("⏳ Generating..." + (chain.isEmpty() ? "" : " " + chain));

        if (ansiEnabled) {
            System.out.print("\r" + ESC + "2K"
                    + "  " + DIM + "Generating..." + RESET + chain
                    + DIM + "  (Esc to cancel, Ctrl+B to background)" + RESET);
            System.out.flush();
        } else {
            System.out.println("  Generating..." + chain + "  (Esc to cancel, Ctrl+B to background)");
            System.out.flush();
        }

        return new SpinnerHandle(null) {
            @Override
            public void stop() {
                if (ansiEnabled) {
                    System.out.print("\r" + ESC + "2K");
                    System.out.flush();
                }
                resetTerminalTitle();
            }

            @Override
            public void setPhase(String phase) {
                super.setPhase(phase);
                if (ansiEnabled && phase != null) {
                    System.out.print("\r" + ESC + "2K"
                            + "  " + DIM + phase + "..." + RESET + chain
                            + DIM + "  (Esc to cancel, Ctrl+B to background)" + RESET);
                    System.out.flush();
                }
            }
        };
    }

    /**
     * Start an animated "Generating..." spinner with terminal title update.
     * Shows a braille spinner animation on the current line and sets the
     * terminal tab/title to indicate processing is in progress.
     *
     * @param chainInfo optional queue chain info (e.g., " [2/5]")
     * @return a SpinnerHandle to stop the spinner when the response arrives
     */
    public SpinnerHandle startGeneratingSpinner(String chainInfo) {
        if (chainInfo == null) chainInfo = "";
        final String chain = chainInfo;

        // Set terminal title to show generating state
        setTerminalTitle("⏳ Generating..." + (chain.isEmpty() ? "" : " " + chain));

        if (!ansiEnabled) {
            System.out.println("  Generating..." + chain + "  (Esc to cancel, Ctrl+B to background)");
            System.out.flush();
            return new SpinnerHandle(null) {
                @Override
                public void stop() {
                    resetTerminalTitle();
                }
            };
        }

        AtomicBoolean running = new AtomicBoolean(true);
        SpinnerHandle handle = new SpinnerHandle(running) {
            @Override
            public void stop() {
                super.stop();
                resetTerminalTitle();
            }
        };
        Thread spinnerThread = new Thread(() -> {
            int frame = 0;
            String[][] phasesets = {
                {"Generating", "Generating.", "Generating..", "Generating..."},
                {"Thinking", "Thinking.", "Thinking..", "Thinking..."},
            };
            String[] currentPhases = phasesets[0];
            while (running.get()) {
                // Check for phase override from the handle
                String override = handle.phaseOverride;
                if (override != null) {
                    if ("Thinking".equals(override)) {
                        currentPhases = phasesets[1];
                    } else {
                        currentPhases = phasesets[0];
                    }
                }
                String spinner = SPINNER_FRAMES[frame % SPINNER_FRAMES.length];
                String phase = currentPhases[(frame / 3) % currentPhases.length];
                System.out.print("\r" + ESC + "2K"
                        + "  " + yellow(spinner) + " " + DIM + phase + RESET
                        + chain
                        + DIM + "  (Esc to cancel, Ctrl+B to background)" + RESET);
                System.out.flush();
                frame++;
                try {
                    Thread.sleep(80);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "generating-spinner");
        spinnerThread.setDaemon(true);
        spinnerThread.start();

        return handle;
    }

    // ========================================================================
    // Terminal title management
    // ========================================================================

    /**
     * Set the terminal tab/window title using OSC escape sequence.
     * Works on most modern terminals (xterm, iTerm2, GNOME Terminal, Windows Terminal, etc.)
     */
    public void setTerminalTitle(String title) {
        if (!ansiEnabled) return;
        // OSC 0 ; title BEL
        System.out.print("\033]0;" + title + "\007");
        System.out.flush();
    }

    /**
     * Reset the terminal title to the default kompile chat title.
     */
    public void resetTerminalTitle() {
        setTerminalTitle("kompile chat");
    }

    // ========================================================================
    // Tool name & input prettification
    // ========================================================================

    /**
     * Strip MCP server prefixes from a tool name, returning the lowercase short name.
     * E.g., {@code "mcp__kompile__read"} → {@code "read"}, {@code "Read"} → {@code "read"}.
     */
    public static String stripMcpPrefix(String rawName) {
        if (rawName == null || rawName.isEmpty()) return "";
        String name = rawName;
        // mcp__<server>__<tool> → <tool>
        if (name.startsWith("mcp__")) {
            int lastSep = name.lastIndexOf("__");
            if (lastSep > 4) { // "mcp__" is 5 chars
                name = name.substring(lastSep + 2);
            }
        }
        return name.toLowerCase();
    }

    /**
     * Clean up a raw tool name for display. Strips MCP prefixes, capitalizes,
     * and converts underscores to title case.
     * <p>
     * Examples:
     * <ul>
     *   <li>{@code "mcp__kompile__read"} → {@code "Read"}</li>
     *   <li>{@code "mcp__kompile__code_search"} → {@code "Code Search"}</li>
     *   <li>{@code "ToolSearch"} → {@code "ToolSearch"} (unchanged)</li>
     *   <li>{@code "exec"} → {@code "Exec"}</li>
     * </ul>
     */
    public static String prettifyToolName(String rawName) {
        if (rawName == null || rawName.isEmpty()) return "unknown";

        String name = rawName;
        // Strip MCP prefix
        if (name.startsWith("mcp__")) {
            int lastSep = name.lastIndexOf("__");
            if (lastSep > 4) {
                name = name.substring(lastSep + 2);
            }
        }

        // If already CamelCase (contains uppercase after position 0), keep as-is
        if (name.length() > 1) {
            boolean hasMidUppercase = false;
            for (int i = 1; i < name.length(); i++) {
                if (Character.isUpperCase(name.charAt(i))) {
                    hasMidUppercase = true;
                    break;
                }
            }
            if (hasMidUppercase) {
                // Capitalize first letter and return (e.g., "toolSearch" → "ToolSearch")
                return Character.toUpperCase(name.charAt(0)) + name.substring(1);
            }
        }

        // Convert underscore_case to Title Case
        if (name.contains("_")) {
            String[] parts = name.split("_");
            StringBuilder sb = new StringBuilder();
            for (String part : parts) {
                if (part.isEmpty()) continue;
                if (sb.length() > 0) sb.append(" ");
                sb.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) sb.append(part.substring(1));
            }
            return sb.toString();
        }

        // Simple lowercase name → capitalize
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /**
     * Parse tool input (often raw JSON) into a human-readable one-line summary.
     * <p>
     * For known tools, extracts the primary parameter value. For example,
     * a {@code read} tool with input {@code {"file_path":"src/app/foo.ts","limit":100}}
     * returns {@code "src/app/foo.ts"}.
     * <p>
     * For unknown tools or non-JSON input, returns a truncated plain-text version.
     *
     * @param toolName  lowercase short tool name (no MCP prefix)
     * @param rawInput  the raw input string (JSON or plain text)
     * @param maxLen    maximum length of the returned string
     */
    public static String prettifyToolInput(String toolName, String rawInput, int maxLen) {
        if (rawInput == null || rawInput.isBlank()) return "";

        String trimmed = rawInput.trim();

        // If not JSON, return truncated plain text
        if (!trimmed.startsWith("{")) {
            return truncatePreview(trimmed, maxLen);
        }

        try {
            JsonNode node = JSON.readTree(trimmed);
            if (!node.isObject()) {
                return truncatePreview(trimmed, maxLen);
            }

            // Look for primary params by tool name
            String[] primaryKeys = PRIMARY_PARAMS.get(toolName);
            if (primaryKeys != null) {
                StringBuilder sb = new StringBuilder();
                for (String key : primaryKeys) {
                    if (node.has(key) && !node.get(key).isNull()) {
                        JsonNode val = node.get(key);
                        String valStr = val.isTextual() ? val.asText() : val.toString();
                        if (!valStr.isEmpty()) {
                            if (sb.length() > 0) sb.append(" in ");
                            sb.append(valStr);
                        }
                    }
                }
                if (sb.length() > 0) {
                    return truncatePreview(sb.toString(), maxLen);
                }
            }

            // Fallback: show fields as "key=value" pairs, skipping noise
            StringBuilder sb = new StringBuilder();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (sb.length() > 0) sb.append(", ");
                String val = entry.getValue().isTextual()
                        ? entry.getValue().asText()
                        : entry.getValue().toString();
                sb.append(entry.getKey()).append("=").append(val);
                if (sb.length() > maxLen) break;
            }
            return truncatePreview(sb.toString(), maxLen);

        } catch (Exception e) {
            // JSON parse failed — return truncated raw text
            return truncatePreview(trimmed, maxLen);
        }
    }

    // ========================================================================
    // Utility methods
    // ========================================================================

    private boolean shouldShowPreview(String toolName, Map<String, Object> meta) {
        // Show preview for bash (command output), errors, and short results
        return "bash".equals(toolName) ||
                "grep".equals(toolName) ||
                "glob".equals(toolName);
    }

    private String renderMetadata(Map<String, Object> meta) {
        StringBuilder sb = new StringBuilder("(");
        boolean first = true;
        for (Map.Entry<String, Object> entry : meta.entrySet()) {
            String key = entry.getKey();
            // Skip verbose metadata
            if ("path".equals(key) || "created".equals(key) || "matchType".equals(key)) continue;
            if (!first) sb.append(", ");
            sb.append(key).append("=").append(entry.getValue());
            first = false;
        }
        sb.append(")");
        return first ? "" : sb.toString();
    }

    public static String truncatePreview(String text, int maxLen) {
        if (text == null) return "";
        // Replace newlines with spaces for inline preview
        String oneLine = text.replace('\n', ' ').replace('\r', ' ');
        if (oneLine.length() <= maxLen) return oneLine;
        return oneLine.substring(0, maxLen - 3) + "...";
    }

    private String indentLines(String text, String indent) {
        if (text == null) return "";
        String[] lines = text.split("\n");
        StringBuilder sb = new StringBuilder();
        int maxLines = 5; // Show at most 5 lines in preview
        for (int i = 0; i < Math.min(lines.length, maxLines); i++) {
            if (i > 0) sb.append("\n");
            sb.append(indent).append(lines[i]);
        }
        if (lines.length > maxLines) {
            sb.append("\n").append(indent).append("... (").append(lines.length - maxLines).append(" more lines)");
        }
        return sb.toString();
    }

    // ========================================================================
    // ANSI formatting helpers
    // ========================================================================

    public String bold(String text) {
        return ansiEnabled ? BOLD + text + RESET : text;
    }

    public String dim(String text) {
        return ansiEnabled ? DIM + text + RESET : text;
    }

    public String red(String text) {
        return ansiEnabled ? FG_RED + text + RESET : text;
    }

    public String green(String text) {
        return ansiEnabled ? FG_GREEN + text + RESET : text;
    }

    public String yellow(String text) {
        return ansiEnabled ? FG_YELLOW + text + RESET : text;
    }

    /**
     * Render a warning message in yellow.
     */
    public String warn(String text) {
        return ansiEnabled ? FG_YELLOW + BOLD + text + RESET : text;
    }

    public String blue(String text) {
        return ansiEnabled ? FG_BLUE + text + RESET : text;
    }

    public String magenta(String text) {
        return ansiEnabled ? FG_MAGENTA + text + RESET : text;
    }

    public String cyan(String text) {
        return ansiEnabled ? FG_CYAN + text + RESET : text;
    }

    public boolean isAnsiEnabled() {
        return ansiEnabled;
    }

    private static boolean detectAnsiSupport() {
        // Check if stdout is a terminal
        if (System.console() == null) {
            return false;
        }
        // Check common environment variables
        String term = System.getenv("TERM");
        if (term != null && !"dumb".equals(term)) {
            return true;
        }
        String colorTerm = System.getenv("COLORTERM");
        if (colorTerm != null) {
            return true;
        }
        // Check if forced
        String forceColor = System.getenv("FORCE_COLOR");
        if (forceColor != null && !"0".equals(forceColor)) {
            return true;
        }
        String noColor = System.getenv("NO_COLOR");
        if (noColor != null) {
            return false;
        }
        // Default: assume ANSI support on non-Windows
        return !System.getProperty("os.name", "").toLowerCase().startsWith("win");
    }
}
