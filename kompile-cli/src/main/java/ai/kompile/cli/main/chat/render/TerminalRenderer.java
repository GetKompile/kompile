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

    // Tool icons (Unicode)
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
            Map.entry("todowrite", "📝"),
            Map.entry("todoread", "📝")
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
     */
    public String renderToolCallStart(String toolName, String description) {
        String icon = TOOL_ICONS.getOrDefault(toolName, "🔧");
        String desc = description != null && !description.isEmpty() ? " " + dim(description) : "";
        return "  " + icon + " " + bold(cyan(toolName)) + desc;
    }

    /**
     * Render a tool call running with spinner.
     */
    public String renderToolCallRunning(String toolName, int spinnerFrame) {
        String icon = TOOL_ICONS.getOrDefault(toolName, "🔧");
        String spinner = ansiEnabled ? yellow(SPINNER_FRAMES[spinnerFrame % SPINNER_FRAMES.length]) : "...";
        return "  " + spinner + " " + cyan(toolName) + " " + dim("running...");
    }

    /**
     * Render a completed tool call with result summary.
     */
    public String renderToolCallComplete(String toolName, ToolResult result) {
        String icon = TOOL_ICONS.getOrDefault(toolName, "🔧");
        StringBuilder sb = new StringBuilder();

        if (result.isError()) {
            sb.append("  ").append(icon).append(" ").append(bold(red(toolName)));
            sb.append(" ").append(red("✗"));
            String errorPreview = truncatePreview(result.getOutput(), 120);
            sb.append("\n    ").append(red(errorPreview));
        } else {
            sb.append("  ").append(icon).append(" ").append(bold(green(toolName)));
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
        String icon = TOOL_ICONS.getOrDefault(toolName, "🔧");
        return "  " + icon + " " + bold(yellow(toolName)) + " " + yellow("⊘ denied") +
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
        String icon = TOOL_ICONS.getOrDefault(toolName, "🔧");
        String status = isError ? red("✗") : green("✓");
        return "  " + magenta("│") + "  " + icon + " " + cyan(toolName) + " " + status;
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
     */
    public String renderContextGroup(Map<String, Integer> toolCounts) {
        StringBuilder sb = new StringBuilder();
        sb.append("  ").append(dim("Gathered context: "));
        boolean first = true;
        for (Map.Entry<String, Integer> entry : toolCounts.entrySet()) {
            if (!first) sb.append(dim(", "));
            sb.append(cyan(String.valueOf(entry.getValue())))
                    .append(dim(" " + entry.getKey()));
            if (entry.getValue() > 1) sb.append(dim("s"));
            first = false;
        }
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
        if (!ansiEnabled) {
            System.out.print("  " + toolName + "...");
            System.out.flush();
            return new SpinnerHandle(null);
        }

        AtomicBoolean running = new AtomicBoolean(true);
        Thread spinnerThread = new Thread(() -> {
            int frame = 0;
            String icon = TOOL_ICONS.getOrDefault(toolName, "🔧");
            while (running.get()) {
                String spinner = SPINNER_FRAMES[frame % SPINNER_FRAMES.length];
                // Move cursor to start of line, clear line, print spinner
                System.out.print("\r" + ESC + "2K  " + yellow(spinner) + " " + cyan(toolName) + " " + dim("running..."));
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
