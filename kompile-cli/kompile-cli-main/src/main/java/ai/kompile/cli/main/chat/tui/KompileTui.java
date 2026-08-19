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
import ai.kompile.cli.main.chat.MessageQueue;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.utils.AnsiConstants;
import org.jline.terminal.Terminal;

import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static ai.kompile.utils.AnsiConstants.*;

/**
 * Unified TUI screen manager for kompile-chat.
 * Owns the full terminal layout: TopBar + scrollable content region + StatusBar.
 *
 * <pre>
 * ┌─────────────────────────────────────────────┐  Row 1   ← TopBar content
 * │ kompile  [claude]  session: cli-a1b2  [plan] │
 * ├─────────────────────────────────────────────┤  Row 2   ← TopBar separator
 * │                                              │
 * │  (scrollable content area — JLine readline,  │  Rows 3..H-2  ← scroll region
 * │   agent output, tool calls, markdown, etc.)  │
 * │                                              │
 * ├─────────────────────────────────────────────┤  Row H-1 ← StatusBar separator
 * │ ⠋ proc-001 (2m) │ ◐ 1 bg │ Q:3 │ coder      │  Row H   ← StatusBar content
 * └─────────────────────────────────────────────┘
 * </pre>
 *
 * The scroll region is set to {@code [TopBar.TOP_HEIGHT + 1, height - StatusBar.STATUS_HEIGHT]}.
 * TopBar and StatusBar draw outside the scroll region using save/restore cursor.
 */
public class KompileTui {

    private static final String MAIN_CONTENT_VIEW = "main";
    private static final int MAX_MAIN_TRANSCRIPT_LINES = 2_000;

    private final TopBar topBar;
    private final StatusBar statusBar;
    private final TerminalRenderer renderer;

    /** Shared lock for all ANSI drawing to prevent interleaved output. */
    private final Object drawLock = new Object();

    private volatile Terminal terminal;
    private volatile int terminalHeight;
    private volatile int terminalWidth;
    private volatile boolean started = false;

    /**
     * Retained main-chat lines let the transcript area switch to a managed
     * process view and back without relying on terminal scrollback scraping.
     */
    private final Deque<String> mainTranscriptLines = new ArrayDeque<>();
    private volatile String contentViewKey = MAIN_CONTENT_VIEW;
    private volatile String contentViewTitle = "Main chat";
    private volatile List<String> contentViewLines = List.of();
    /** Lines above the bottom of the retained transcript currently being viewed. */
    private volatile int contentScrollOffset = 0;
    /** Activity views pin their title row while their transcript body scrolls. */
    private volatile boolean contentViewPinsHeader = false;

    /** True while a short-lived modal (for example the provider/model picker) owns the content area. */
    private volatile boolean temporaryWindowActive = false;
    private volatile String temporaryWindowTitle = "";
    private volatile List<String> temporaryWindowLines = List.of();
    private String savedContentViewKey;
    private String savedContentViewTitle;
    private List<String> savedContentViewLines;
    private int savedContentScrollOffset;
    private boolean savedContentViewPinsHeader;

    /**
     * Extra rows reserved between the scroll region and the StatusBar.
     * Used by EmulatedPassthroughCommand for its input box, queue preview, etc.
     * ChatRepl leaves this at 0 (readline lives inside the scroll region).
     */
    private volatile int reservedMiddleRows = 0;

    /** Optional callback for recalculating reserved rows on resize. */
    private volatile ReservedRowsCalculator reservedRowsCalculator;
    private final List<Runnable> resizeListeners = new CopyOnWriteArrayList<>();

    // ── Construction ──────────────────────────────────────────────────────

    /**
     * Create a KompileTui with the given collaborators.
     * The StatusBar and TopBar are created internally with the shared drawLock.
     */
    public KompileTui(BackgroundTaskManager taskManager,
                      BackgroundProcessManager processManager,
                      MessageQueue messageQueue,
                      TerminalRenderer renderer) {
        this.renderer = renderer;
        this.topBar = new TopBar(drawLock);
        // StatusBar with externalScrollManagement=true — we own the scroll region
        this.statusBar = new StatusBar(
                taskManager, processManager, messageQueue, renderer,
                drawLock, true);
    }

    // ── Accessors ─────────────────────────────────────────────────────────

    public TopBar getTopBar() {
        return topBar;
    }

    public StatusBar getStatusBar() {
        return statusBar;
    }

    public Object getDrawLock() {
        return drawLock;
    }

    /**
     * The first row of the scrollable content region.
     */
    public int scrollTop() {
        return TopBar.TOP_HEIGHT + 1;
    }

    /**
     * The last row of the scrollable content region.
     * Accounts for TopBar at top, StatusBar at bottom, and any reserved middle rows.
     */
    public int scrollBottom() {
        return Math.max(scrollTop() + 2, terminalHeight - StatusBar.STATUS_HEIGHT - reservedMiddleRows);
    }

    /**
     * Set extra rows reserved between the scroll region bottom and the StatusBar.
     * Call BEFORE {@link #start(Terminal)} or call {@link #reestablishScrollRegion()}
     * afterwards to re-apply.
     */
    public void setReservedMiddleRows(int rows) {
        this.reservedMiddleRows = Math.max(0, rows);
    }

    public int getReservedMiddleRows() {
        return reservedMiddleRows;
    }

    public void addResizeListener(Runnable listener) {
        if (listener != null) {
            resizeListeners.add(listener);
        }
    }

    /**
     * Install a calculator that recomputes reserved middle rows on every resize.
     * Also immediately computes and applies the value.
     */
    public void setReservedRowsCalculator(ReservedRowsCalculator calculator) {
        this.reservedRowsCalculator = calculator;
        recalcReservedMiddleRows(calculator);
    }

    /**
     * Recalculate reserved middle rows based on terminal height.
     */
    public void recalcReservedMiddleRows(ReservedRowsCalculator calculator) {
        if (calculator != null) {
            this.reservedMiddleRows = Math.max(0, calculator.calculate(terminalHeight, terminalWidth));
        }
    }

    /**
     * Callback interface for dynamically computing reserved middle rows
     * based on current terminal dimensions.
     */
    @FunctionalInterface
    public interface ReservedRowsCalculator {
        int calculate(int terminalHeight, int terminalWidth);
    }

    public int getTerminalWidth() {
        return terminalWidth;
    }

    public int getTerminalHeight() {
        return terminalHeight;
    }

    public boolean isMainContentView() {
        return MAIN_CONTENT_VIEW.equals(contentViewKey);
    }

    public String getContentViewKey() {
        return contentViewKey;
    }

    public String getContentViewTitle() {
        return contentViewTitle;
    }

    /** Snapshot used by focused renderer regressions and non-ANSI callers. */
    public List<String> getContentViewLines() {
        synchronized (drawLock) {
            return List.copyOf(contentViewLines);
        }
    }

    public int getContentScrollOffset() {
        return contentScrollOffset;
    }

    /** Snapshot of the actual transcript rows selected by the current viewport. */
    public List<String> getVisibleContentLines() {
        synchronized (drawLock) {
            return visibleContentLines(contentViewLines, contentViewPinsHeader);
        }
    }

    public boolean isTemporaryWindowActive() {
        return temporaryWindowActive;
    }

    /**
     * Replace the content region with a modal window while retaining the active
     * transcript/view underneath it. Background output continues to update the
     * retained transcript, but cannot overwrite this window until it closes.
     */
    public void showTemporaryWindow(String title, List<String> lines) {
        List<String> window = new ArrayList<>();
        window.add("╭─ " + (title == null || title.isBlank() ? "Kompile" : title) + " ─╮");
        if (lines != null) {
            for (String line : lines) {
                window.add("│ " + (line == null ? "" : line) + " │");
            }
        }
        window.add("╰" + "─".repeat(Math.max(1, Math.min(120, terminalWidth - 2))) + "╯");
        synchronized (drawLock) {
            if (!temporaryWindowActive) {
                savedContentViewKey = contentViewKey;
                savedContentViewTitle = contentViewTitle;
                savedContentViewLines = contentViewLines;
                savedContentScrollOffset = contentScrollOffset;
                savedContentViewPinsHeader = contentViewPinsHeader;
            }
            temporaryWindowActive = true;
            temporaryWindowTitle = title == null ? "" : title;
            temporaryWindowLines = List.copyOf(window);
            contentViewKey = "__temporary__";
            contentViewTitle = temporaryWindowTitle;
            contentViewLines = temporaryWindowLines;
            contentScrollOffset = 0;
            contentViewPinsHeader = false;
            replaceScrollRegion(contentViewLines, false);
        }
        if (!started) {
            window.forEach(System.out::println);
        }
    }

    public void updateTemporaryWindow(String title, List<String> lines) {
        showTemporaryWindow(title, lines);
    }

    /** Close the modal and restore the exact view that was underneath it. */
    public void closeTemporaryWindow() {
        synchronized (drawLock) {
            if (!temporaryWindowActive) return;
            temporaryWindowActive = false;
            contentViewKey = savedContentViewKey == null ? MAIN_CONTENT_VIEW : savedContentViewKey;
            contentViewTitle = savedContentViewTitle == null ? "Main chat" : savedContentViewTitle;
            if (MAIN_CONTENT_VIEW.equals(contentViewKey)) {
                contentViewLines = List.copyOf(mainTranscriptLines);
            } else {
                contentViewLines = savedContentViewLines == null ? List.of() : savedContentViewLines;
            }
            contentScrollOffset = savedContentScrollOffset;
            contentViewPinsHeader = savedContentViewPinsHeader;
            savedContentViewKey = null;
            savedContentViewTitle = null;
            savedContentViewLines = null;
            temporaryWindowLines = List.of();
            replaceScrollRegion(contentViewLines, contentViewPinsHeader);
        }
    }

    /** Retain a JLine-owned user input row without printing it a second time. */
    public void rememberMainTranscriptLine(String text) {
        synchronized (drawLock) {
            rememberMainLines(splitLines(text));
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    /**
     * Start the TUI: set scroll regions, draw top/bottom bars, start refresh threads.
     */
    public void start(Terminal terminal) {
        if (!renderer.isAnsiEnabled()) return;

        this.terminal = terminal;
        updateTerminalSize();
        // Recalculate reserved rows now that we have real terminal dimensions
        recalcReservedMiddleRows(reservedRowsCalculator);

        if (terminalHeight < 12) return; // Too small for top+bottom bars

        started = true;
        topBar.setTerminalWidth(terminalWidth);

        // Clear screen and draw initial layout
        synchronized (drawLock) {
            PrintStream out = System.out;
            out.print(ESC + "2J" + ESC + "H"); // clear + home
            out.flush();

            // Draw top bar
            topBar.redraw();

            // Set scroll region
            setScrollRegion();

            // Draw status bar (starts its refresh thread)
            statusBar.start(terminal);
        }

        // Handle terminal resize
        terminal.handle(Terminal.Signal.WINCH, signal -> {
            updateTerminalSize();
            topBar.setTerminalWidth(terminalWidth);
            recalcReservedMiddleRows(reservedRowsCalculator);
            synchronized (drawLock) {
                setScrollRegion();
                topBar.redraw();
                statusBar.requestRedraw();
            }
            fireResizeListeners();
        });
    }

    /**
     * Handle a terminal resize event. Call from an external WINCH handler
     * when EmulatedPassthroughCommand overrides the default handler.
     */
    public void handleResize() {
        if (!started) return;
        updateTerminalSize();
        topBar.setTerminalWidth(terminalWidth);
        recalcReservedMiddleRows(reservedRowsCalculator);
        synchronized (drawLock) {
            setScrollRegion();
            topBar.redraw();
            statusBar.requestRedraw();
        }
        fireResizeListeners();
    }

    /**
     * Stop the TUI: reset scroll regions, clear bars, stop refresh threads.
     */
    public void stop() {
        if (!started) return;
        started = false;

        statusBar.stop();

        synchronized (drawLock) {
            // Reset scroll region to full terminal
            System.out.print(ESC + "r");
            // Clear screen
            System.out.print(ESC + "2J" + ESC + "H");
            System.out.flush();
        }
    }

    // ── Drawing ───────────────────────────────────────────────────────────

    /**
     * Redraw both bars without touching the content area.
     * Call after state changes that affect top or bottom bar.
     */
    public void redrawBars() {
        if (!started) return;
        synchronized (drawLock) {
            topBar.redraw();
            statusBar.requestRedraw();
        }
    }

    /**
     * Repaint the active content view after an external renderer (such as JLine)
     * has redrawn the terminal. The view state remains authoritative in this
     * object, so redisplay cannot leave a stale or blank activity screen behind.
     */
    public void redrawContentView() {
        if (!started || temporaryWindowActive) return;
        synchronized (drawLock) {
            setScrollRegion();
            replaceScrollRegion(contentViewLines, contentViewPinsHeader);
        }
    }

    /**
     * Print text into the scroll region.
     * Moves cursor to the last scroll row, prints the text, then scrolls up.
     * Thread-safe via drawLock.
     */
    /**
     * Record streamed transcript lines without writing to the terminal.
     *
     * JLine owns the live input cursor while a prompt is active. Background
     * output uses {@code LineReader.printAbove} for the actual write; this
     * state-only hook keeps the TUI's authoritative transcript in sync without
     * moving the cursor from a non-JLine thread.
     */
    public void recordInScrollRegion(String text) {
        synchronized (drawLock) {
            rememberMainLines(splitLines(text));
        }
    }

    public void printInScrollRegion(String text) {
        List<String> lines = splitLines(text);
        synchronized (drawLock) {
            rememberMainLines(lines);
        }
        if (!isMainContentView()) {
            // Parent chat output continues to be retained while a process view is
            // open, but must not bleed over the selected process transcript.
            return;
        }
        if (!started) {
            lines.forEach(System.out::println);
            return;
        }
        synchronized (drawLock) {
            if (contentScrollOffset > 0) {
                // Keep an explicitly scrolled viewport stable while new agent output
                // arrives. The user returns to the live tail with PageDown.
                contentScrollOffset = clampScrollOffset(
                        contentScrollOffset + lines.size(), contentViewLines, false);
                replaceScrollRegion(contentViewLines, false);
                return;
            }
            for (String line : lines) {
                writeScrollLine(line);
            }
        }
    }

    /**
     * Replace the transcript area with one process/subagent/task view. The
     * replacement is cursor-addressed rather than newline-driven, so live input
     * and the activity tree below it do not drift down the terminal.
     */
    public void showActivityView(String key, String title, String content) {
        if (temporaryWindowActive) return;
        List<String> lines = new ArrayList<>();
        lines.add("── " + (title == null || title.isBlank() ? "Activity" : title) + " ──");
        lines.addAll(splitLines(content));
        boolean plainOutput = !started;
        synchronized (drawLock) {
            contentViewKey = key == null || key.isBlank() ? "activity" : key;
            contentViewTitle = title == null || title.isBlank() ? "Activity" : title;
            contentViewLines = List.copyOf(lines);
            contentScrollOffset = 0;
            contentViewPinsHeader = true;
            replaceScrollRegion(lines, true);
        }
        if (plainOutput) {
            lines.forEach(System.out::println);
        }
    }

    /**
     * Refresh a selected activity without snapping a reader back to the tail.
     * This is used for live subagent chunks and process output updates.
     */
    public void updateActivityView(String key, String title, String content) {
        if (temporaryWindowActive) return;
        if (key == null || key.isBlank()) return;
        if (!key.equals(contentViewKey)) {
            // A selection/process transition can arrive between refresh callbacks.
            // Treat the new key as an authoritative view switch instead of silently
            // updating an off-screen transcript.
            showActivityView(key, title, content);
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add("── " + (title == null || title.isBlank() ? "Activity" : title) + " ──");
        lines.addAll(splitLines(content));
        synchronized (drawLock) {
            int previousSize = contentViewLines.size();
            boolean followingTail = contentScrollOffset == 0;
            contentViewTitle = title == null || title.isBlank() ? "Activity" : title;
            contentViewLines = List.copyOf(lines);
            contentViewPinsHeader = true;
            if (!followingTail && lines.size() > previousSize) {
                contentScrollOffset += lines.size() - previousSize;
            }
            contentScrollOffset = clampScrollOffset(
                    followingTail ? 0 : contentScrollOffset, contentViewLines, true);
            replaceScrollRegion(contentViewLines, true);
        }
    }

    /** Restore the retained parent-chat transcript in-place. */
    public void showMainView() {
        if (temporaryWindowActive) return;
        synchronized (drawLock) {
            contentViewKey = MAIN_CONTENT_VIEW;
            contentViewTitle = "Main chat";
            List<String> lines = new ArrayList<>(mainTranscriptLines);
            contentViewLines = List.copyOf(lines);
            contentScrollOffset = 0;
            contentViewPinsHeader = false;
            replaceScrollRegion(lines, false);
        }
    }

    /** Scroll upward for positive deltas and downward for negative deltas. */
    public boolean scrollContent(int deltaLines) {
        if (deltaLines == 0) return false;
        synchronized (drawLock) {
            int next = clampScrollOffset(
                    contentScrollOffset + deltaLines, contentViewLines, contentViewPinsHeader);
            if (next == contentScrollOffset) return false;
            contentScrollOffset = next;
            replaceScrollRegion(contentViewLines, contentViewPinsHeader);
            return true;
        }
    }

    public boolean pageContent(int direction) {
        int page = Math.max(1, transcriptCapacity() - 2);
        return scrollContent(direction > 0 ? page : -page);
    }

    public boolean scrollToTop() {
        synchronized (drawLock) {
            int top = clampScrollOffset(Integer.MAX_VALUE, contentViewLines, contentViewPinsHeader);
            if (contentScrollOffset == top) return false;
            contentScrollOffset = top;
            replaceScrollRegion(contentViewLines, contentViewPinsHeader);
            return true;
        }
    }

    public boolean scrollToBottom() {
        synchronized (drawLock) {
            if (contentScrollOffset == 0) return false;
            contentScrollOffset = 0;
            replaceScrollRegion(contentViewLines, contentViewPinsHeader);
            return true;
        }
    }

    private void rememberMainLines(List<String> lines) {
        for (String line : lines) {
            mainTranscriptLines.addLast(line);
            while (mainTranscriptLines.size() > MAX_MAIN_TRANSCRIPT_LINES) {
                mainTranscriptLines.removeFirst();
            }
        }
        if (isMainContentView()) {
            contentViewLines = List.copyOf(mainTranscriptLines);
        }
    }

    private static List<String> splitLines(String text) {
        if (text == null) {
            return List.of("");
        }
        return List.of(text.split("\\R", -1));
    }

    private void writeScrollLine(String line) {
        PrintStream out = System.out;
        out.printf("%s%d;%dr", ESC, scrollTop(), scrollBottom());
        out.printf("%s%d;1H%s2K%s\n", ESC, scrollBottom(), ESC, line);
        out.flush();
    }

    private void replaceScrollRegion(List<String> lines, boolean preserveHeader) {
        if (!started) {
            return;
        }
        PrintStream out = System.out;
        int top = scrollTop();
        int bottom = scrollBottom();
        // The bottom scroll row belongs to JLine's live prompt. Process output
        // occupies only the transcript rows above it, then REDISPLAY restores the
        // input without either surface overwriting the other.
        int contentBottom = Math.max(top, bottom - 1);
        out.printf("%s%d;%dr", ESC, top, bottom);
        for (int row = top; row <= contentBottom; row++) {
            out.printf("%s%d;1H%s2K", ESC, row, ESC);
        }
        List<String> visible = visibleContentLines(lines, preserveHeader);
        int row = top;
        for (int i = 0; i < visible.size() && row <= contentBottom; i++, row++) {
            out.printf("%s%d;1H%s", ESC, row, visible.get(i));
        }
        out.printf("%s%d;1H", ESC, bottom);
        out.flush();
    }

    private List<String> visibleContentLines(List<String> lines, boolean preserveHeader) {
        int capacity = transcriptCapacity();
        if (lines == null || lines.isEmpty()) return List.of();
        int offset = clampScrollOffset(contentScrollOffset, lines, preserveHeader);
        if (preserveHeader && capacity > 1) {
            int bodyCapacity = capacity - 1;
            int bodySize = Math.max(0, lines.size() - 1);
            int end = Math.max(0, bodySize - offset);
            int start = Math.max(0, end - bodyCapacity);
            List<String> visible = new ArrayList<>(capacity);
            visible.add(lines.get(0));
            visible.addAll(lines.subList(1 + start, 1 + end));
            return visible;
        }
        int end = Math.max(0, lines.size() - offset);
        int start = Math.max(0, end - capacity);
        return List.copyOf(lines.subList(start, end));
    }

    private int clampScrollOffset(int requested, List<String> lines, boolean preserveHeader) {
        int capacity = transcriptCapacity();
        int lineCount = lines == null ? 0 : lines.size();
        int bodyCount = preserveHeader && lineCount > 0 ? lineCount - 1 : lineCount;
        int bodyCapacity = preserveHeader && capacity > 1 ? capacity - 1 : capacity;
        int maximum = Math.max(0, bodyCount - Math.max(1, bodyCapacity));
        return Math.max(0, Math.min(requested, maximum));
    }

    private int transcriptCapacity() {
        return Math.max(1, Math.max(scrollTop(), scrollBottom() - 1) - scrollTop() + 1);
    }

    /**
     * Re-establish the scroll region. Call before JLine readLine() since
     * JLine may reset scroll regions.
     */
    public void reestablishScrollRegion() {
        if (!started) return;
        synchronized (drawLock) {
            setScrollRegion();
        }
    }

    // ── Convenience state setters (delegate to bars) ──────────────────────

    public void setAgentName(String name) {
        topBar.setAgentName(name);
        statusBar.setActiveAgent(name);
        if (started) redrawBars();
    }

    public void setSessionId(String sessionId) {
        topBar.setSessionId(sessionId);
        if (started) topBar.redraw();
    }

    public void setMode(String mode) {
        topBar.setMode(mode);
        if (started) topBar.redraw();
    }

    public void setPlanningMode(boolean planning) {
        topBar.setPlanningMode(planning);
        statusBar.setPlanningMode(planning);
        if (started) redrawBars();
    }

    public void setEnforcerActive(boolean active) {
        topBar.setEnforcerActive(active);
        statusBar.setEnforcerActive(active);
        if (started) redrawBars();
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private void setScrollRegion() {
        int top = scrollTop();
        int bottom = scrollBottom();
        if (bottom <= top + 2) return; // Too small
        System.out.printf("%s%d;%dr", ESC, top, bottom);
        // Position cursor inside the scroll region
        System.out.printf("%s%d;1H", ESC, bottom);
        System.out.flush();
    }

    private void updateTerminalSize() {
        if (terminal != null) {
            terminalHeight = terminal.getHeight();
            terminalWidth = terminal.getWidth();
        }
        if (terminalHeight <= 0) terminalHeight = 24;
        if (terminalWidth <= 0) terminalWidth = 80;
    }

    private void fireResizeListeners() {
        for (Runnable listener : resizeListeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // A display listener must never break terminal resize handling.
            }
        }
    }
}
