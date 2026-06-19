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
import ai.kompile.utils.AnsiConstants;
import ai.kompile.utils.FormatUtils;
import ai.kompile.utils.StringUtils;
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

import static ai.kompile.utils.AnsiConstants.*;

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

    /** Number of terminal rows reserved for the status bar (separator + content). */
    public static final int STATUS_HEIGHT = 2;

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
    private volatile Thread refreshThread;
    private int spinnerFrame = 0;
    private volatile boolean enforcerActive = false;

    /** Lock object for synchronized drawing to prevent interleaved ANSI output. */
    private final Object drawLock;

    /**
     * When true, StatusBar does NOT manage scroll regions itself —
     * KompileTui owns scroll region lifecycle.
     */
    private final boolean externalScrollManagement;

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
        private volatile String status;

        public SubagentEntry(String id, String type, String description) {
            this.id = id;
            this.type = type;
            this.description = description;
            this.startedAt = Instant.now();
        }

        public String getId() { return id; }
        public String getType() { return type; }
        public String getDescription() { return description; }
        public String getStatus() { return status; }

        /** Update the live status (e.g. "thinking", "Read file.java", "writing"). */
        public void setStatus(String status) {
            this.status = status;
        }

        public String getElapsed() {
            Duration d = Duration.between(startedAt, Instant.now());
            long secs = d.getSeconds();
            if (secs < 60) return secs + "s";
            return (secs / 60) + "m " + (secs % 60) + "s";
        }
    }

    private final CopyOnWriteArrayList<SubagentEntry> activeSubagents = new CopyOnWriteArrayList<>();

    // ========================================================================
    // Menu items — navigable activity items shown below the status line.
    // Arrow down from input enters menu navigation; up returns to input.
    // ========================================================================

    /** A menu item displayed below the status bar, selectable via arrow keys. */
    public record MenuItem(String id, String label, String status, boolean selected) {}

    /** Currently displayed menu items (set externally, rendered on each redraw). */
    private volatile List<MenuItem> menuItems = List.of();
    /** Message/hint shown below menu items. */
    private volatile String menuMessage = "";

    // ========================================================================
    // Constructor
    // ========================================================================

    /**
     * Create a StatusBar that manages its own scroll regions (legacy mode).
     */
    public StatusBar(BackgroundTaskManager taskManager,
                     BackgroundProcessManager processManager,
                     MessageQueue messageQueue,
                     TerminalRenderer renderer) {
        this(taskManager, processManager, messageQueue, renderer, new Object(), false);
    }

    /**
     * Create a StatusBar with a shared draw lock and optional external scroll management.
     * When {@code externalScrollManagement} is true, the StatusBar only draws its content
     * and never calls setScrollRegion/resetScrollRegion — the caller (KompileTui) owns that.
     */
    public StatusBar(BackgroundTaskManager taskManager,
                     BackgroundProcessManager processManager,
                     MessageQueue messageQueue,
                     TerminalRenderer renderer,
                     Object drawLock,
                     boolean externalScrollManagement) {
        this.taskManager = taskManager;
        this.processManager = processManager;
        this.messageQueue = messageQueue;
        this.renderer = renderer;
        this.drawLock = drawLock;
        this.externalScrollManagement = externalScrollManagement;
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
        // (only when we own the scroll region ourselves)
        if (!externalScrollManagement) {
            setScrollRegion();

            // Listen for terminal resize
            terminal.handle(Terminal.Signal.WINCH, signal -> {
                updateTerminalSize();
                if (terminalHeight < 10) {
                    resetScrollRegion();
                    visible = false;
                } else {
                    visible = true;
                    setScrollRegion();
                    redraw();
                }
            });
        }

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
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        if (enabled && renderer.isAnsiEnabled()) {
            synchronized (drawLock) {
                if (!externalScrollManagement) {
                    resetScrollRegion();
                }
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
            if (!externalScrollManagement) {
                setScrollRegion();
            }
            redraw();
        } else if (!enabled && wasEnabled) {
            synchronized (drawLock) {
                if (!externalScrollManagement) {
                    resetScrollRegion();
                }
                clearStatusArea();
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnforcerActive(boolean enforcerActive) {
        this.enforcerActive = enforcerActive;
        requestRedraw();
    }

    public Object getDrawLock() {
        return drawLock;
    }

    public int getTerminalHeight() {
        return terminalHeight;
    }

    public int getTerminalWidth() {
        return terminalWidth;
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

    /**
     * Update a subagent's live status and trigger a redraw.
     * No-op if the status hasn't actually changed.
     */
    public void updateSubagentStatus(String id, String status) {
        for (SubagentEntry e : activeSubagents) {
            if (e.getId().equals(id)) {
                String current = e.getStatus();
                if (status == null ? current == null : status.equals(current)) return;
                e.setStatus(status);
                requestRedraw();
                return;
            }
        }
    }

    public List<SubagentEntry> getActiveSubagents() {
        return new ArrayList<>(activeSubagents);
    }

    // ========================================================================
    // Menu item management
    // ========================================================================

    /**
     * Set the menu items to display below the status bar.
     * Items with {@code selected=true} are highlighted with inverse video.
     */
    public void setMenuItems(List<MenuItem> items, String message) {
        this.menuItems = items == null ? List.of() : List.copyOf(items);
        this.menuMessage = message == null ? "" : message;
        requestRedraw();
    }

    /** Clear all menu items. */
    public void clearMenu() {
        this.menuItems = List.of();
        this.menuMessage = "";
        requestRedraw();
    }

    /**
     * How many extra rows the menu needs below the standard STATUS_HEIGHT.
     * KompileTui uses this to dynamically adjust reserved rows.
     */
    public int getMenuRowCount() {
        int items = menuItems.size();
        if (items == 0 && menuMessage.isEmpty()) return 0;
        return items + (menuMessage.isEmpty() ? 0 : 1);
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
            List<MenuItem> items = this.menuItems;
            String msg = this.menuMessage;
            int menuRows = items.size() + (msg.isEmpty() ? 0 : 1);
            int totalRows = STATUS_HEIGHT + menuRows;
            int sepRow = terminalHeight - totalRows + 1;
            int contentRow = sepRow + 1;

            // Save cursor position
            out.print(SAVE_CURSOR);

            // Draw separator line
            out.print(ESC + sepRow + ";1H");
            out.print(ESC + "2K");
            out.print(DIM + HORIZONTAL_LINE.repeat(Math.min(terminalWidth, 200)) + RESET);

            // Draw status content line
            out.print(ESC + contentRow + ";1H");
            out.print(ESC + "2K");
            out.print(buildStatusContent());

            // Draw menu items below status line
            int menuRow = contentRow + 1;
            for (MenuItem item : items) {
                if (menuRow > terminalHeight) break;
                out.print(ESC + menuRow + ";1H");
                out.print(ESC + "2K");
                String line = "  " + item.label();
                if (item.status() != null && !item.status().isEmpty()) {
                    line += DIM + " " + item.status() + RESET;
                }
                String truncated = line.length() > terminalWidth
                        ? line.substring(0, Math.max(0, terminalWidth - 1)) : line;
                out.print(item.selected() ? INVERSE + truncated + RESET : truncated);
                menuRow++;
            }

            // Draw menu message/hint
            if (!msg.isEmpty() && menuRow <= terminalHeight) {
                out.print(ESC + menuRow + ";1H");
                out.print(ESC + "2K");
                String truncMsg = msg.length() > terminalWidth
                        ? msg.substring(0, Math.max(0, terminalWidth - 1)) : msg;
                out.print(DIM + truncMsg + RESET);
            }

            // Restore cursor position
            out.print(RESTORE_CURSOR);
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
            String spinner = MAGENTA + SPINNER_FRAMES[spinnerFrame % SPINNER_FRAMES.length] + RESET;
            if (watchers.size() == 1) {
                ProcessEntry p = watchers.get(0);
                String desc = StringUtils.truncateEllipsis(p.getDescription(), 24);
                segments.add(spinner + " " + MAGENTA + p.getKind().label() + RESET
                        + " " + desc + DIM + " (" + FormatUtils.formatDuration(p.getDuration()) + ")" + RESET);
            } else {
                segments.add(spinner + " " + MAGENTA + watchers.size() + " watchers" + RESET);
            }
        }

        // --- Running command processes ---
        if (!commands.isEmpty()) {
            String spinner = YELLOW + SPINNER_FRAMES[spinnerFrame % SPINNER_FRAMES.length] + RESET;
            if (commands.size() == 1) {
                ProcessEntry p = commands.get(0);
                String desc = StringUtils.truncateEllipsis(p.getDescription(), 25);
                String elapsed = FormatUtils.formatDuration(p.getDuration());
                segments.add(spinner + " " + CYAN + p.getId() + RESET
                        + " " + desc + DIM + " (" + elapsed + ")" + RESET);
            } else {
                segments.add(spinner + " " + CYAN + commands.size() + " processes" + RESET);
            }
        }

        // --- Backgrounded LLM tasks ---
        List<BackgroundTask> activeTasks = taskManager.getActiveTasks();
        long bgCount = activeTasks.stream()
                .filter(t -> t.getStatus() == BackgroundTask.BackgroundTaskStatus.BACKGROUNDED)
                .count();
        if (bgCount > 0) {
            segments.add(YELLOW + "\u25D0" + RESET + " " // ◐
                    + YELLOW + bgCount + " bg task" + (bgCount > 1 ? "s" : "") + RESET);
        }

        // --- Active subagents ---
        if (!activeSubagents.isEmpty()) {
            String spinner = MAGENTA + SPINNER_FRAMES[spinnerFrame % SPINNER_FRAMES.length] + RESET;
            if (activeSubagents.size() == 1) {
                SubagentEntry sa = activeSubagents.get(0);
                String desc = sa.getType();
                if (sa.getDescription() != null && !sa.getDescription().isEmpty()) {
                    desc = StringUtils.truncateEllipsis(sa.getDescription(), 20);
                }
                String statusText = sa.getStatus();
                if (statusText != null && !statusText.isEmpty()) {
                    segments.add(spinner + " ▸ " + MAGENTA + desc + RESET
                            + " " + DIM + statusText + RESET
                            + DIM + " (" + sa.getElapsed() + ")" + RESET);
                } else {
                    segments.add(spinner + " ▸ " + MAGENTA + desc + RESET
                            + DIM + " (" + sa.getElapsed() + ")" + RESET);
                }
            } else {
                segments.add(spinner + " ▸ "
                        + MAGENTA + activeSubagents.size() + " subagents" + RESET);
            }
        }

        // --- Queue ---
        int queueSize = messageQueue != null ? messageQueue.size() : 0;
        if (queueSize > 0) {
            segments.add(GREEN + "Q:" + queueSize + RESET);
        }

        // --- Queue chain progress ---
        if (taskManager.isInQueueChain()) {
            int current = taskManager.getQueueChainCurrent();
            int total = taskManager.getQueueChainTotal();
            segments.add(DIM + "(" + current + "/" + total + ")" + RESET);
        }

        // --- Enforcer active ---
        if (enforcerActive) {
            segments.add(MAGENTA + "[enforcer]" + RESET);
        }

        // --- Planning mode ---
        if (planningMode) {
            segments.add(CYAN + "[plan]" + RESET);
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
        String sep = " " + DIM + VERTICAL_SEP + RESET + " ";
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
            body.append(BOLD + MAGENTA + "Active Watchers" + RESET).append("\n");
            for (ProcessEntry p : watchers) {
                body.append("  ")
                        .append(MAGENTA + "\u25CF" + RESET)
                        .append(" [").append(MAGENTA + p.getId() + RESET).append("]")
                        .append(" ").append(p.getKind().label())
                        .append(" - ").append(p.getDescription())
                        .append(DIM + " (" + FormatUtils.formatDuration(p.getDuration()) + ")" + RESET)
                        .append("\n");
                body.append(DIM + "    ").append(formatProcessDetails(p)).append(RESET)
                        .append("\n");
            }
        }
        if (!commandProcesses.isEmpty()) {
            if (!watchers.isEmpty()) body.append("\n");
            body.append(BOLD + CYAN + "Running" + RESET).append("\n");
            for (ProcessEntry p : commandProcesses) {
                body.append("  ")
                        .append(YELLOW + "\u25CF" + RESET) // ●
                        .append(" [").append(CYAN + p.getId() + RESET).append("]")
                        .append(" ").append(p.getDescription())
                        .append(DIM + " (" + FormatUtils.formatDuration(p.getDuration()) + ")" + RESET)
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
            body.append(BOLD + CYAN + "Recent" + RESET).append("\n");
            int start = Math.max(0, done.size() - 8);
            for (int i = start; i < done.size(); i++) {
                ProcessEntry p = done.get(i);
                String icon;
                switch (p.getState()) {
                    case COMPLETED: icon = GREEN + "\u2713" + RESET; break; // ✓
                    case FAILED: icon = RED + "\u2717" + RESET; break;     // ✗
                    case KILLED: icon = YELLOW + "\u2298" + RESET; break;  // ⊘
                    default: icon = "?";
                }
                body.append("  ").append(icon)
                        .append(" [").append(DIM + p.getId() + RESET).append("]")
                        .append(" ").append(p.getDescription())
                        .append(DIM + " (" + FormatUtils.formatDuration(p.getDuration()) + ")" + RESET);
                if (p.getExitCode() != null && p.getExitCode() != 0) {
                    body.append(RED + " exit=" + p.getExitCode() + RESET);
                }
                body.append("\n");
            }
        }

        // Active subagents
        if (!activeSubagents.isEmpty()) {
            if (!running.isEmpty() || !done.isEmpty()) body.append("\n");
            body.append(BOLD + MAGENTA + "Subagents" + RESET).append("\n");
            for (SubagentEntry sa : activeSubagents) {
                body.append("  ▸ ")
                        .append("[").append(MAGENTA + sa.getId() + RESET).append("]")
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
            body.append(BOLD + YELLOW + "LLM Tasks" + RESET).append("\n");
            for (BackgroundTask t : bgTasks) {
                body.append("  ").append(t.getStatusIcon())
                        .append(" [").append(YELLOW + t.getId() + RESET).append("]")
                        .append(" ").append(StringUtils.truncateEllipsis(t.getDescription(), 50))
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
        out.print(SAVE_CURSOR);
        for (int row = sepRow; row <= terminalHeight; row++) {
            out.print(ESC + row + ";1H");
            out.print(ESC + "2K");
        }
        out.print(RESTORE_CURSOR);
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

    private static String stripAnsi(String text) {
        return AnsiConstants.stripAnsi(text);
    }
}
