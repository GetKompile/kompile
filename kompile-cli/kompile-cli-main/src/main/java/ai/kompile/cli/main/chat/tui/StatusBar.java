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
import ai.kompile.cli.main.chat.ChatCompleter;
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

    /** Optional owner for serialized redraws (installed by KompileTui). */
    private volatile Runnable redrawRequester;

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
        private final StringBuilder transcript = new StringBuilder();
        private volatile Instant completedAt;
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
        public Instant getStartedAt() { return startedAt; }
        public Instant getCompletedAt() { return completedAt; }
        public String getStatus() { return status; }
        public boolean isActive() { return completedAt == null; }

        /** Full bounded activity transcript shown only when this subagent is opened. */
        public synchronized String getTranscript() {
            return transcript.toString().stripTrailing();
        }

        private synchronized void appendActivity(String summary, String detail) {
            if (summary != null && !summary.isBlank()) {
                this.status = summary;
            }
            if (detail == null || detail.isBlank()) return;
            if (transcript.length() > 0) transcript.append('\n');
            transcript.append(detail);
            if (transcript.length() > MAX_SUBAGENT_TRANSCRIPT_CHARS) {
                int trimAt = transcript.length() - MAX_SUBAGENT_TRANSCRIPT_CHARS;
                int newline = transcript.indexOf("\n", trimAt);
                transcript.delete(0, newline >= 0 ? newline + 1 : trimAt);
                transcript.insert(0, "[older activity omitted]\n");
            }
        }

        /** Append streamed model output without inserting a newline per token/chunk. */
        private synchronized void appendOutput(String chunk) {
            if (chunk == null || chunk.isEmpty()) return;
            transcript.append(chunk);
            trimTranscript();
        }

        private synchronized void trimTranscript() {
            if (transcript.length() <= MAX_SUBAGENT_TRANSCRIPT_CHARS) return;
            int trimAt = transcript.length() - MAX_SUBAGENT_TRANSCRIPT_CHARS;
            int newline = transcript.indexOf("\n", trimAt);
            transcript.delete(0, newline >= 0 ? newline + 1 : trimAt);
            transcript.insert(0, "[older activity omitted]\n");
        }

        /** Update the live status (e.g. "thinking", "Read file.java", "writing"). */
        public void setStatus(String status) {
            this.status = status;
        }

        private void complete() {
            this.completedAt = Instant.now();
            if (status == null || status.isBlank()
                    || "starting".equalsIgnoreCase(status)
                    || "thinking".equalsIgnoreCase(status)) {
                this.status = "completed";
            }
        }

        private void reactivate() {
            this.completedAt = null;
            this.status = "starting follow-up";
        }

        public String getElapsed() {
            Duration d = Duration.between(startedAt,
                    completedAt != null ? completedAt : Instant.now());
            long secs = d.getSeconds();
            if (secs < 60) return secs + "s";
            return (secs / 60) + "m " + (secs % 60) + "s";
        }
    }

    private final CopyOnWriteArrayList<SubagentEntry> activeSubagents = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<SubagentEntry> recentSubagents = new CopyOnWriteArrayList<>();
    private static final int MAX_RECENT_SUBAGENTS = 8;
    private static final int MAX_SUBAGENT_TRANSCRIPT_CHARS = 250_000;

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

    /** Route redraw requests through the owning TUI frame coordinator. */
    public void setRedrawRequester(Runnable redrawRequester) {
        this.redrawRequester = redrawRequester;
    }

    /** Refresh dimensions when an externally-managed TUI receives WINCH. */
    void syncTerminalSize() {
        updateTerminalSize();
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
            boolean wasActive = hasActiveItems();
            while (running.get()) {
                try {
                    Thread.sleep(wasActive ? 200 : 1000);
                    boolean active = hasActiveItems();
                    if (active) {
                        spinnerFrame++;
                    }
                    // An idle status bar is event-driven. Rewriting it forever can
                    // disturb JLine and, on wrap-prone terminals, scroll blank rows.
                    if (visible && (active || active != wasActive)) {
                        requestRedraw();
                    }
                    wasActive = active;
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "status-bar-refresh");
        refreshThread.setDaemon(true);
        refreshThread.start();

        // Initial draw
        requestRedraw();
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
            requestRedraw();
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
        for (SubagentEntry existing : recentSubagents) {
            if (existing.getId().equals(id) && recentSubagents.remove(existing)) {
                existing.reactivate();
                activeSubagents.add(existing);
                requestRedraw();
                return existing;
            }
        }
        SubagentEntry entry = new SubagentEntry(id, type, description);
        activeSubagents.add(entry);
        requestRedraw();
        return entry;
    }

    /**
     * Unregister a subagent (it has completed or failed).
     */
    public void unregisterSubagent(String id) {
        for (SubagentEntry entry : activeSubagents) {
            if (entry.getId().equals(id) && activeSubagents.remove(entry)) {
                retainCompletedSubagent(entry);
                requestRedraw();
                return;
            }
        }
    }

    /**
     * Unregister a subagent by entry reference.
     */
    public void unregisterSubagent(SubagentEntry entry) {
        if (entry != null && activeSubagents.remove(entry)) {
            retainCompletedSubagent(entry);
            requestRedraw();
        }
    }

    private void retainCompletedSubagent(SubagentEntry entry) {
        entry.complete();
        recentSubagents.removeIf(existing -> existing.getId().equals(entry.getId()));
        recentSubagents.add(entry);
        while (recentSubagents.size() > MAX_RECENT_SUBAGENTS) {
            recentSubagents.remove(0);
        }
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

    /** Append detailed activity while keeping only its concise summary in the main panel. */
    public void appendSubagentActivity(String id, String summary, String detail) {
        for (SubagentEntry e : activeSubagents) {
            if (e.getId().equals(id)) {
                e.appendActivity(summary, detail);
                requestRedraw();
                return;
            }
        }
        for (SubagentEntry e : recentSubagents) {
            if (e.getId().equals(id)) {
                e.appendActivity(summary, detail);
                requestRedraw();
                return;
            }
        }
    }

    /** Append raw streaming output to a subagent's retained transcript. */
    public void appendSubagentOutput(String id, String chunk) {
        for (SubagentEntry e : activeSubagents) {
            if (e.getId().equals(id)) {
                e.appendOutput(chunk);
                return;
            }
        }
        for (SubagentEntry e : recentSubagents) {
            if (e.getId().equals(id)) {
                e.appendOutput(chunk);
                return;
            }
        }
    }

    public List<SubagentEntry> getActiveSubagents() {
        return new ArrayList<>(activeSubagents);
    }

    public List<SubagentEntry> getRecentSubagents() {
        return new ArrayList<>(recentSubagents);
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
        Runnable requester = redrawRequester;
        if (requester != null) {
            requester.run();
        } else if (canDraw()) {
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
        if (!canDraw()) return;

        synchronized (drawLock) {
            PrintStream out = System.out;
            out.print(SAVE_CURSOR);
            out.print(render(terminalHeight, terminalWidth));
            out.print(RESTORE_CURSOR);
            out.flush();
        }
    }

    /**
     * Render the status/menu rows without cursor save/restore or flushing.
     * This is intentionally bounded by terminal rows and visible columns.
     */
    public String render(int height, int width) {
        if (!canDraw(height, width)) return "";
        List<MenuItem> items = this.menuItems;
        String msg = this.menuMessage;
        int availableMenuRows = Math.max(0, height - STATUS_HEIGHT);
        int requestedMenuRows = items.size() + (msg.isEmpty() ? 0 : 1);
        int menuRows = Math.min(requestedMenuRows, availableMenuRows);
        int totalRows = STATUS_HEIGHT + menuRows;
        int sepRow = Math.max(1, height - totalRows + 1);
        int contentRow = sepRow + 1;
        StringBuilder frame = new StringBuilder();

        frame.append(ESC).append(sepRow).append(";1H").append(ESC).append("2K")
                .append(DIM).append(HORIZONTAL_LINE.repeat(Math.min(safeDrawWidth(width), 200)))
                .append(RESET);
        frame.append(ESC).append(contentRow).append(";1H").append(ESC).append("2K")
                .append(buildStatusContent(width));

        int menuRow = contentRow + 1;
        int drawWidth = safeDrawWidth(width);
        for (MenuItem item : items) {
            if (menuRow > height) break;
            frame.append(ESC).append(menuRow).append(";1H").append(ESC).append("2K");
            String line = "  " + item.label();
            if (item.status() != null && !item.status().isEmpty()) {
                line += DIM + " " + item.status() + RESET;
            }
            String truncated = AnsiConstants.stripAnsi(line);
            if (truncated.length() > drawWidth) {
                truncated = truncated.substring(0, Math.max(0, drawWidth));
            }
            frame.append(item.selected() ? INVERSE : "").append(truncated)
                    .append(item.selected() ? RESET : "");
            menuRow++;
        }
        if (!msg.isEmpty() && menuRow <= height) {
            frame.append(ESC).append(menuRow).append(";1H").append(ESC).append("2K");
            String truncated = msg.length() > drawWidth
                    ? msg.substring(0, Math.max(0, drawWidth)) : msg;
            frame.append(DIM).append(truncated).append(RESET);
        }
        return frame.toString();
    }

    private boolean canDraw() {
        return canDraw(terminalHeight, terminalWidth);
    }

    private boolean canDraw(int height, int width) {
        return enabled && visible && renderer.isAnsiEnabled()
                && height >= STATUS_HEIGHT && width > 0;
    }

    /**
     * Build the status bar content string.
     * Layout: [active items...] │ [queue] │ [mode] │ [agent]
     */
    String buildStatusContent() {
        return buildStatusContent(terminalWidth > 0 ? terminalWidth : 80);
    }

    String buildStatusContent(int width) {
        List<String> segments = new ArrayList<>();

        // --- Foreground model activity ---
        String activity = ChatCompleter.getActivity();
        if (activity != null && !activity.isBlank()) {
            if (ChatCompleter.isActivityTerminal()) {
                segments.add(YELLOW + "■" + RESET + " " + YELLOW + activity + RESET);
            } else {
                String spinner = YELLOW + SPINNER_FRAMES[spinnerFrame % SPINNER_FRAMES.length] + RESET;
                String backgroundHint = taskManager.isCurrentTaskBackgroundable()
                        ? DIM + " (use Ctrl+B to background this)" + RESET
                        : "";
                segments.add(spinner + " " + YELLOW + activity + RESET + backgroundHint);
            }
        }

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
        List<SubagentEntry> visibleSubagents = activeSubagents.stream()
                .filter(sa -> !isIdleStatus(sa.getStatus()))
                .toList();
        if (!visibleSubagents.isEmpty()) {
            String spinner = MAGENTA + SPINNER_FRAMES[spinnerFrame % SPINNER_FRAMES.length] + RESET;
            if (visibleSubagents.size() == 1) {
                SubagentEntry sa = visibleSubagents.get(0);
                String desc = sa.getType();
                if (sa.getDescription() != null && !sa.getDescription().isEmpty()) {
                    desc = sa.getType() + " — "
                            + StringUtils.truncateEllipsis(sa.getDescription(), 20);
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
                String names = visibleSubagents.stream()
                        .limit(3)
                        .map(sa -> sa.getType() == null || sa.getType().isBlank()
                                ? "agent" : sa.getType())
                        .collect(java.util.stream.Collectors.joining(", "));
                if (visibleSubagents.size() > 3) names += ", …";
                segments.add(spinner + " ▸ "
                        + MAGENTA + names + RESET);
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

        // Never write the last terminal column: filling it sets the terminal's
        // auto-wrap flag, so the next animated redraw can become a line feed.
        int drawWidth = safeDrawWidth(width);
        if (drawWidth == 1) {
            return INVERSE + " " + RESET;
        }
        int innerWidth = Math.max(0, drawWidth - 2);
        int visibleLen = AnsiConstants.stripAnsi(joined).length();
        if (visibleLen > innerWidth) {
            String plain = AnsiConstants.stripAnsi(joined);
            joined = plain.substring(0, innerWidth);
            visibleLen = joined.length();
        }
        int padding = Math.max(0, drawWidth - visibleLen - 2);

        return INVERSE + " " + joined + " ".repeat(padding + 1) + RESET;
    }

    static int safeDrawWidth(int terminalWidth) {
        return Math.max(1, terminalWidth - 1);
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
                appendProcessOutputTail(body, p, "    ");
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
                appendProcessOutputTail(body, p, "    ");
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

        if (!recentSubagents.isEmpty()) {
            if (!running.isEmpty() || !done.isEmpty() || !activeSubagents.isEmpty()) body.append("\n");
            body.append(BOLD + MAGENTA + "Recent Subagents" + RESET).append("\n");
            for (SubagentEntry sa : recentSubagents) {
                body.append("  ✓ ")
                        .append("[").append(DIM + sa.getId() + RESET).append("]")
                        .append(" ").append(sa.getType());
                if (sa.getDescription() != null && !sa.getDescription().isEmpty()) {
                    body.append(DIM + " — " + sa.getDescription() + RESET);
                }
                if (sa.getStatus() != null && !sa.getStatus().isBlank()) {
                    body.append(DIM + " · " + sa.getStatus() + RESET);
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

        if (running.isEmpty() && done.isEmpty() && activeSubagents.isEmpty()
                && recentSubagents.isEmpty() && bgTasks.isEmpty()) {
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
        return (ChatCompleter.getActivity() != null && !ChatCompleter.isActivityTerminal())
                || !processManager.listRunning().isEmpty()
                || !taskManager.getActiveTasks().isEmpty()
                || activeSubagents.stream().anyMatch(sa -> !isIdleStatus(sa.getStatus()));
    }

    private static boolean isIdleStatus(String status) {
        return status != null && status.trim().equalsIgnoreCase("idle");
    }

    private static void appendProcessOutputTail(StringBuilder body, ProcessEntry p, String indent) {
        List<String> tail = processOutputTail(p, 3);
        if (tail.isEmpty()) return;
        body.append(DIM).append(indent).append("recent output:").append(RESET).append("\n");
        for (String line : tail) {
            body.append(DIM)
                    .append(indent).append("  ")
                    .append(StringUtils.truncateEllipsis(AnsiConstants.stripAnsi(line), 96))
                    .append(RESET).append("\n");
        }
    }

    private static List<String> processOutputTail(ProcessEntry p, int maxLines) {
        if (p == null || p.getOutputFile() == null || maxLines <= 0) return List.of();
        try {
            return BackgroundProcessManager.tailOutputFile(p.getOutputFile(), maxLines).lines();
        } catch (Exception ignored) {
            return List.of();
        }
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
}
