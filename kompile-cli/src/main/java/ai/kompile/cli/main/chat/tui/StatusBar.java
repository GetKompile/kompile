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

package ai.kompile.cli.main.chat.tui;

import ai.kompile.cli.main.chat.BackgroundTaskManager;
import ai.kompile.cli.main.chat.BackgroundTaskManager.BackgroundTask;
import ai.kompile.cli.main.chat.MessageQueue;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager.ProcessEntry;
import org.jline.terminal.Terminal;

import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Persistent status bar pinned to the bottom of the terminal.
 * Uses ANSI scroll regions to reserve space below the main output area,
 * showing live process management: running processes, backgrounded LLM tasks,
 * active subagents, queue status, and mode indicators.
 *
 * Modeled after Claude Code's below-bar status line.
 *
 * The bar occupies 2 terminal rows:
 * <pre>
 *   Row height-1:  ─────────────────────────────  (dim separator)
 *   Row height:    ⠋ proc-001 (2m) │ ◐ 1 bg │ Q:3 │ coder
 * </pre>
 *
 * The scroll region is set to {@code [1, height-2]} so normal output
 * (including JLine's readline) scrolls above the bar.
 */
public class StatusBar {

    private static final String ESC = "\033[";
    private static final String RESET = ESC + "0m";
    private static final String BOLD = ESC + "1m";
    private static final String DIM = ESC + "2m";
    private static final String INVERSE = ESC + "7m";
    private static final String FG_GREEN = ESC + "32m";
    private static final String FG_YELLOW = ESC + "33m";
    private static final String FG_CYAN = ESC + "36m";
    private static final String FG_MAGENTA = ESC + "35m";
    private static final String FG_RED = ESC + "31m";
    private static final String FG_GRAY = ESC + "90m";

    private static final String[] SPINNER_FRAMES = {"\u28CB", "\u28D9", "\u28F9", "\u28F8", "\u28FC", "\u28F4", "\u28E6", "\u28E7"};

    /** Number of terminal rows reserved for the status bar (separator + content). */
    private static final int STATUS_HEIGHT = 2;

    private final BackgroundTaskManager taskManager;
    private final BackgroundProcessManager processManager;
    private final MessageQueue messageQueue;
    private final TerminalRenderer renderer;

    private volatile Terminal terminal;
    private volatile int terminalHeight;
    private volatile int terminalWidth;
    private volatile boolean enabled = true;
    private volatile boolean visible = true;
    private volatile boolean planningMode = false;
    private volatile String activeAgent = null;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread refreshThread;
    private int spinnerFrame = 0;

    /** Lock object for synchronized drawing to prevent interleaved ANSI output. */
    private final Object drawLock = new Object();

    // ========================================================================
    // Subagent tracking
    // ========================================================================

    /**
     * Represents an active subagent being tracked in the status bar.
     */
    public static class SubagentEntry {
        private final String id;
        private final String type;
        private final String description;
        private final Instant startedAt;

        public SubagentEntry(String id, String type, String description) {
            this.id = id;
            this.type = type;
            this.description = description;
            this.startedAt = Instant.now();
        }

        public String getId() { return id; }
        public String getType() { return type; }
        public String getDescription() { return description; }

        public String getElapsed() {
            Duration d = Duration.between(startedAt, Instant.now());
            long secs = d.getSeconds();
            if (secs < 60) return secs + "s";
            return (secs / 60) + "m " + (secs % 60) + "s";
        }
    }

    private final CopyOnWriteArrayList<SubagentEntry> activeSubagents = new CopyOnWriteArrayList<>();

    // ========================================================================
    // Constructor
    // ========================================================================

    public StatusBar(BackgroundTaskManager taskManager,
                     BackgroundProcessManager processManager,
                     MessageQueue messageQueue,
                     TerminalRenderer renderer) {
        this.taskManager = taskManager;
        this.processManager = processManager;
        this.messageQueue = messageQueue;
        this.renderer = renderer;
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    /**
     * Start the status bar, setting scroll regions and starting the refresh thread.
     */
    public void start(Terminal terminal) {
        if (!renderer.isAnsiEnabled()) {
            enabled = false;
            return;
        }

        this.terminal = terminal;
        updateTerminalSize();

        // Don't enable if terminal is too small
        if (terminalHeight < 10) {
            enabled = false;
            return;
        }

        // Set scroll region to exclude bottom STATUS_HEIGHT rows
        setScrollRegion();

        // Listen for terminal resize
        terminal.handle(Terminal.Signal.WINCH, signal -> {
            updateTerminalSize();
            if (terminalHeight < 10) {
                // Terminal too small — disable temporarily
                resetScrollRegion();
                visible = false;
            } else {
                visible = true;
                setScrollRegion();
                redraw();
            }
        });

        // Start refresh thread for spinner animation and periodic updates
        running.set(true);
        refreshThread = new Thread(() -> {
            while (running.get()) {
                try {
                    // 5fps when active items exist, 1fps otherwise
                    int sleepMs = hasActiveItems() ? 200 : 1000;
                    Thread.sleep(sleepMs);
                    spinnerFrame++;
                    if (visible) {
                        redraw();
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "status-bar-refresh");
        refreshThread.setDaemon(true);
        refreshThread.start();

        // Initial draw
        redraw();
    }

    /**
     * Stop the status bar, reset scroll regions, and clean up.
     */
    public void stop() {
        running.set(false);
        if (refreshThread != null) {
            refreshThread.interrupt();
            try {
                refreshThread.join(500);
            } catch (InterruptedException ignored) {}
        }
        if (enabled && renderer.isAnsiEnabled()) {
            synchronized (drawLock) {
                resetScrollRegion();
                clearStatusArea();
            }
        }
    }

    // ========================================================================
    // Public state setters (called by ChatRepl)
    // ========================================================================

    public void setPlanningMode(boolean planningMode) {
        this.planningMode = planningMode;
        requestRedraw();
    }

    public void setActiveAgent(String agentName) {
        this.activeAgent = agentName;
        requestRedraw();
    }

    public void setEnabled(boolean enabled) {
        boolean wasEnabled = this.enabled;
        this.enabled = enabled;
        if (enabled && !wasEnabled && terminal != null) {
            setScrollRegion();
            redraw();
        } else if (!enabled && wasEnabled) {
            synchronized (drawLock) {
                resetScrollRegion();
                clearStatusArea();
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ========================================================================
    // Subagent tracking
    // ========================================================================

    /**
     * Register a new active subagent. The status bar will show it until unregistered.
     */
    public SubagentEntry registerSubagent(String id, String type, String description) {
        SubagentEntry entry = new SubagentEntry(id, type, description);
        activeSubagents.add(entry);
        requestRedraw();
        return entry;
    }

    /**
     * Unregister a subagent (it has completed or failed).
     */
    public void unregisterSubagent(String id) {
        activeSubagents.removeIf(e -> e.getId().equals(id));
        requestRedraw();
    }

    /**
     * Unregister a subagent by entry reference.
     */
    public void unregisterSubagent(SubagentEntry entry) {
        activeSubagents.remove(entry);
        requestRedraw();
    }

    public List<SubagentEntry> getActiveSubagents() {
        return new ArrayList<>(activeSubagents);
    }

    // ========================================================================
    // Notification trigger
    // ========================================================================

    /**
     * Request an immediate redraw (called after state changes).
     */
    public void requestRedraw() {
        if (enabled && visible && renderer.isAnsiEnabled()) {
            redraw();
        }
    }

    // ========================================================================
    // Rendering
    // ========================================================================

    /**
     * Redraw the status bar at the bottom of the terminal.
     * Thread-safe: uses drawLock to prevent interleaved ANSI sequences.
     */
    public void redraw() {
        if (!enabled || !visible || !renderer.isAnsiEnabled()) return;

        synchronized (drawLock) {
            PrintStream out = System.out;
            int sepRow = terminalHeight - STATUS_HEIGHT + 1;
            int contentRow = terminalHeight;

            // Save cursor position
            out.print("\0337"); // DEC save (more reliable than CSI s in scroll regions)

            // Draw separator line
            out.print(ESC + sepRow + ";1H"); // move to separator row
            out.print(ESC + "2K");           // clear line
            out.print(DIM + "\u2500".repeat(Math.min(terminalWidth, 200)) + RESET);

            // Draw content line
            out.print(ESC + contentRow + ";1H"); // move to content row
            out.print(ESC + "2K");               // clear line

            String content = buildStatusContent();
            out.print(content);

            // Restore cursor position
            out.print("\0338"); // DEC restore
            out.flush();
        }
    }

    /**
     * Build the status bar content string.
     * Layout: [active items...] │ [queue] │ [mode] │ [agent]
     */
    private String buildStatusContent() {
        List<String> segments = new ArrayList<>();

        // --- Judge/enforcer watchers ---
        List<ProcessEntry> runningProcs = processManager.listRunning();
        List<ProcessEntry> watchers = new ArrayList<>();
        List<ProcessEntry> commands = new ArrayList<>();
        for (ProcessEntry p : runningProcs) {
            if (p.getKind() == BackgroundProcessManager.ProcessKind.JUDGE
                    || p.getKind() == BackgroundProcessManager.ProcessKind.ENFORCER) {
                watchers.add(p);
            } else {
                commands.add(p);
            }
        }
        if (!watchers.isEmpty()) {
            String spinner = FG_MAGENTA + SPINNER_FRAMES[spinnerFrame % SPINNER_FRAMES.length] + RESET;
            if (watchers.size() == 1) {
                ProcessEntry p = watchers.get(0);
                String desc = truncate(p.getDescription(), 24);
                segments.add(spinner + " " + FG_MAGENTA + p.getKind().label() + RESET
                        + " " + desc + DIM + " (" + formatDuration(p.getDuration()) + ")" + RESET);
            } else {
                segments.add(spinner + " " + FG_MAGENTA + watchers.size() + " watchers" + RESET);
            }
        }

        // --- Running command processes ---
        if (!commands.isEmpty()) {
            String spinner = FG_YELLOW + SPINNER_FRAMES[spinnerFrame % SPINNER_FRAMES.length] + RESET;
            if (commands.size() == 1) {
                ProcessEntry p = commands.get(0);
                String desc = truncate(p.getDescription(), 25);
                String elapsed = formatDuration(p.getDuration());
                segments.add(spinner + " " + FG_CYAN + p.getId() + RESET
                        + " " + desc + DIM + " (" + elapsed + ")" + RESET);
            } else {
                segments.add(spinner + " " + FG_CYAN + commands.size() + " processes" + RESET);
            }
        }

        // --- Backgrounded LLM tasks ---
        List<BackgroundTask> activeTasks = taskManager.getActiveTasks();
        long bgCount = activeTasks.stream()
                .filter(t -> t.getStatus() == BackgroundTask.BackgroundTaskStatus.BACKGROUNDED)
                .count();
        if (bgCount > 0) {
            segments.add(FG_YELLOW + "\u25D0" + RESET + " " // ◐
                    + FG_YELLOW + bgCount + " bg task" + (bgCount > 1 ? "s" : "") + RESET);
        }

        // --- Active subagents ---
        if (!activeSubagents.isEmpty()) {
            String spinner = FG_MAGENTA + SPINNER_FRAMES[spinnerFrame % SPINNER_FRAMES.length] + RESET;
            if (activeSubagents.size() == 1) {
                SubagentEntry sa = activeSubagents.get(0);
                String desc = sa.getType();
                if (sa.getDescription() != null && !sa.getDescription().isEmpty()) {
                    desc = truncate(sa.getDescription(), 20);
                }
                segments.add(spinner + " \uD83E\uDD16 " + FG_MAGENTA + desc + RESET
                        + DIM + " (" + sa.getElapsed() + ")" + RESET);
            } else {
                segments.add(spinner + " \uD83E\uDD16 "
                        + FG_MAGENTA + activeSubagents.size() + " subagents" + RESET);
            }
        }

        // --- Queue ---
        int queueSize = messageQueue != null ? messageQueue.size() : 0;
        if (queueSize > 0) {
            segments.add(FG_GREEN + "Q:" + queueSize + RESET);
        }

        // --- Queue chain progress ---
        if (taskManager.isInQueueChain()) {
            int current = taskManager.getQueueChainCurrent();
            int total = taskManager.getQueueChainTotal();
            segments.add(DIM + "(" + current + "/" + total + ")" + RESET);
        }

        // --- Planning mode ---
        if (planningMode) {
            segments.add(FG_CYAN + "[plan]" + RESET);
        }

        // --- Active agent ---
        if (activeAgent != null && !activeAgent.isEmpty()) {
            segments.add(DIM + activeAgent + RESET);
        }

        // --- Idle indicator when nothing is active ---
        if (segments.isEmpty()) {
            segments.add(DIM + "kompile" + RESET);
            if (activeAgent != null && !activeAgent.isEmpty()) {
                segments.add(DIM + activeAgent + RESET);
            }
        }

        // Join with separator
        String sep = " " + DIM + "\u2502" + RESET + " "; // │
        String joined = String.join(sep, segments);

        // Pad to terminal width with inverse-video background
        int visibleLen = stripAnsi(joined).length();
        int padding = Math.max(0, terminalWidth - visibleLen - 2); // -2 for leading space

        return INVERSE + " " + joined + " ".repeat(padding + 1) + RESET;
    }

    // ========================================================================
    // Detailed process view (for /processes command)
    // ========================================================================

    /**
     * Render a detailed process management panel.
     * Used by the /processes slash command.
     */
    public String renderProcessPanel() {
        StringBuilder body = new StringBuilder();

        // Running processes
        List<ProcessEntry> running = processManager.listRunning();
        List<ProcessEntry> watchers = new ArrayList<>();
        List<ProcessEntry> commandProcesses = new ArrayList<>();
        for (ProcessEntry p : running) {
            if (p.getKind() == BackgroundProcessManager.ProcessKind.JUDGE
                    || p.getKind() == BackgroundProcessManager.ProcessKind.ENFORCER) {
                watchers.add(p);
            } else {
                commandProcesses.add(p);
            }
        }
        if (!watchers.isEmpty()) {
            body.append(BOLD + FG_MAGENTA + "Active Watchers" + RESET).append("\n");
            for (ProcessEntry p : watchers) {
                body.append("  ")
                        .append(FG_MAGENTA + "\u25CF" + RESET)
                        .append(" [").append(FG_MAGENTA + p.getId() + RESET).append("]")
                        .append(" ").append(p.getKind().label())
                        .append(" - ").append(p.getDescription())
                        .append(DIM + " (" + formatDuration(p.getDuration()) + ")" + RESET)
                        .append("\n");
                body.append(DIM + "    ").append(formatProcessDetails(p)).append(RESET)
                        .append("\n");
            }
        }
        if (!commandProcesses.isEmpty()) {
            if (!watchers.isEmpty()) body.append("\n");
            body.append(BOLD + FG_CYAN + "Running" + RESET).append("\n");
            for (ProcessEntry p : commandProcesses) {
                body.append("  ")
                        .append(FG_YELLOW + "\u25CF" + RESET) // ●
                        .append(" [").append(FG_CYAN + p.getId() + RESET).append("]")
                        .append(" ").append(p.getDescription())
                        .append(DIM + " (" + formatDuration(p.getDuration()) + ")" + RESET)
                        .append("\n");
                body.append(DIM + "    ").append(formatProcessDetails(p)).append(RESET)
                        .append("\n");
            }
        }

        // Completed/failed/killed processes
        List<ProcessEntry> all = processManager.listAll();
        List<ProcessEntry> done = new ArrayList<>();
        for (ProcessEntry p : all) {
            if (!p.isRunning()) done.add(p);
        }
        if (!done.isEmpty()) {
            if (!running.isEmpty()) body.append("\n");
            body.append(BOLD + FG_CYAN + "Recent" + RESET).append("\n");
            int start = Math.max(0, done.size() - 8);
            for (int i = start; i < done.size(); i++) {
                ProcessEntry p = done.get(i);
                String icon;
                switch (p.getState()) {
                    case COMPLETED: icon = FG_GREEN + "\u2713" + RESET; break; // ✓
                    case FAILED: icon = FG_RED + "\u2717" + RESET; break;     // ✗
                    case KILLED: icon = FG_YELLOW + "\u2298" + RESET; break;  // ⊘
                    default: icon = "?";
                }
                body.append("  ").append(icon)
                        .append(" [").append(DIM + p.getId() + RESET).append("]")
                        .append(" ").append(p.getDescription())
                        .append(DIM + " (" + formatDuration(p.getDuration()) + ")" + RESET);
                if (p.getExitCode() != null && p.getExitCode() != 0) {
                    body.append(FG_RED + " exit=" + p.getExitCode() + RESET);
                }
                body.append("\n");
            }
        }

        // Active subagents
        if (!activeSubagents.isEmpty()) {
            if (!running.isEmpty() || !done.isEmpty()) body.append("\n");
            body.append(BOLD + FG_MAGENTA + "Subagents" + RESET).append("\n");
            for (SubagentEntry sa : activeSubagents) {
                body.append("  \uD83E\uDD16 ") // 🤖
                        .append("[").append(FG_MAGENTA + sa.getId() + RESET).append("]")
                        .append(" ").append(sa.getType());
                if (sa.getDescription() != null && !sa.getDescription().isEmpty()) {
                    body.append(DIM + " \u2014 " + sa.getDescription() + RESET);
                }
                body.append(DIM + " (" + sa.getElapsed() + ")" + RESET)
                        .append("\n");
            }
        }

        // Backgrounded LLM tasks
        List<BackgroundTask> bgTasks = taskManager.getActiveTasks();
        if (!bgTasks.isEmpty()) {
            body.append("\n");
            body.append(BOLD + FG_YELLOW + "LLM Tasks" + RESET).append("\n");
            for (BackgroundTask t : bgTasks) {
                body.append("  ").append(t.getStatusIcon())
                        .append(" [").append(FG_YELLOW + t.getId() + RESET).append("]")
                        .append(" ").append(truncate(t.getDescription(), 50))
                        .append(DIM + " (" + t.getElapsedTime() + ")" + RESET)
                        .append("\n");
            }
        }

        if (running.isEmpty() && done.isEmpty() && activeSubagents.isEmpty() && bgTasks.isEmpty()) {
            body.append(DIM + "  No active processes or subagents" + RESET).append("\n");
        }

        return body.toString();
    }

    // ========================================================================
    // Scroll region management
    // ========================================================================

    private void setScrollRegion() {
        synchronized (drawLock) {
            int scrollBottom = terminalHeight - STATUS_HEIGHT;
            if (scrollBottom < 5) return; // Terminal too small
            // Set scroll region: rows 1 through scrollBottom
            System.out.print(ESC + "1;" + scrollBottom + "r");
            // Move cursor into the scroll region (don't leave it in the status area)
            System.out.print(ESC + scrollBottom + ";1H");
            System.out.flush();
        }
    }

    private void resetScrollRegion() {
        // Reset scroll region to full terminal
        System.out.print(ESC + "r");
        System.out.flush();
    }

    private void clearStatusArea() {
        int sepRow = terminalHeight - STATUS_HEIGHT + 1;
        PrintStream out = System.out;
        out.print("\0337"); // save cursor
        for (int row = sepRow; row <= terminalHeight; row++) {
            out.print(ESC + row + ";1H");
            out.print(ESC + "2K");
        }
        out.print("\0338"); // restore cursor
        out.flush();
    }

    private void updateTerminalSize() {
        if (terminal != null) {
            terminalHeight = terminal.getHeight();
            terminalWidth = terminal.getWidth();
        }
        if (terminalHeight <= 0) terminalHeight = 24;
        if (terminalWidth <= 0) terminalWidth = 80;
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private boolean hasActiveItems() {
        return !processManager.listRunning().isEmpty()
                || !taskManager.getActiveTasks().isEmpty()
                || !activeSubagents.isEmpty();
    }

    private static String formatProcessDetails(ProcessEntry p) {
        List<String> parts = new ArrayList<>();
        if (p.getPid() > 0) {
            parts.add("PID: " + p.getPid());
        }
        if (p.getOutputFile() != null) {
            parts.add("Output: " + p.getOutputFile());
        }
        p.getMetadata().forEach((key, value) -> {
            if (value != null && !value.isBlank()) {
                parts.add(key + ": " + value);
            }
        });
        return String.join("  ", parts);
    }

    private static String formatDuration(Duration d) {
        long secs = d.getSeconds();
        if (secs < 60) return secs + "s";
        if (secs < 3600) return (secs / 60) + "m " + (secs % 60) + "s";
        return (secs / 3600) + "h " + ((secs % 3600) / 60) + "m";
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 1) + "\u2026"; // …
    }

    /**
     * Strip ANSI escape sequences from a string to get the visible length.
     */
    private static String stripAnsi(String text) {
        if (text == null) return "";
        return text.replaceAll("\033\\[[^m]*m", "")
                   .replaceAll("\033[78]", ""); // DEC save/restore
    }
}
