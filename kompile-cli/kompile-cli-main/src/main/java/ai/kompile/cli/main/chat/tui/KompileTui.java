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
import org.jline.reader.LineReader;
import org.jline.reader.Widget;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static ai.kompile.utils.AnsiConstants.*;

/**
 * Unified TUI screen manager for kompile-chat.
 * Owns the full terminal layout: TopBar + scrollable content region + StatusBar.
 *
 * <pre>
 * ┌─────────────────────────────────────────────┐  Row 1   ← TopBar content
 * │ kompile  [claude]  session: cli-a1b2  [plan] │
 * │ ⚠ transient alert                            │  Row 2   ← fixed alert lane
 * ├─────────────────────────────────────────────┤  Row 3   ← TopBar separator
 * │                                              │
 * │  (scrollable content area — JLine readline,  │  Rows 4..N    ← scroll region
 * │   agent output, tool calls, markdown, etc.)  │
 * │                                              │
 * │ Queue · 2 pending · ↑ edits latest            │  Fixed queue pane
 * │ → upcoming [a1b2c3d4] first pending message   │
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
    private static final String REDRAW_WIDGET = "kompile-redraw-frame";
    private static final int MAX_MAIN_TRANSCRIPT_LINES = 2_000;
    private static final long MIN_ASYNC_FRAME_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private static final String SCROLL_TO_BOTTOM_CONTROL = "[↓ Bottom]";
    /** JLine mouse coordinates are zero-based; row 3 and column 2 keep X10 clicks ASCII-safe. */
    private static final int SCROLL_TO_BOTTOM_CONTROL_X = 1;
    private static final int SCROLL_TO_BOTTOM_CONTROL_Y = TopBar.TOP_HEIGHT - 1;

    private final TopBar topBar;
    private final StatusBar statusBar;
    private final TerminalRenderer renderer;
    private final MessageQueue messageQueue;

    /** Shared lock for all ANSI drawing to prevent interleaved output. */
    private final Object drawLock = new Object();
    private final ExecutorService redrawExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "kompile-tui-redraw");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean redrawQueued = new AtomicBoolean(false);
    private final AtomicBoolean redrawDirty = new AtomicBoolean(false);
    private final AtomicLong alertVersion = new AtomicLong();
    private volatile long lastFrameNanos;

    private volatile Terminal terminal;
    private volatile int terminalHeight;
    private volatile int terminalWidth;
    private volatile boolean started = false;
    private volatile LineReader lineReader;
    private volatile Widget clearInputWidget;
    private volatile Widget redisplayWidget;

    /**
     * Retained transcript entries let a running tool replace its own block while
     * output arrives, instead of appending a start row, raw deltas, and a duplicate
     * completion body. Unkeyed entries remain ordinary append-only transcript text.
     */
    private static final class TranscriptEntry {
        private final String key;
        private List<String> lines;

        private TranscriptEntry(String key, List<String> lines) {
            this.key = key;
            this.lines = lines;
        }
    }

    private final Deque<TranscriptEntry> mainTranscriptEntries = new ArrayDeque<>();
    private int mainTranscriptLineCount;
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
        this.messageQueue = messageQueue;
        this.topBar = new TopBar(drawLock);
        // StatusBar with externalScrollManagement=true — we own the scroll region
        this.statusBar = new StatusBar(
                taskManager, processManager, messageQueue, renderer,
                drawLock, true);
        this.statusBar.setRedrawRequester(this::requestRedraw);
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

    public String getCurrentAlert() {
        return topBar.getAlert();
    }

    public boolean isStarted() {
        return started;
    }

    /** Show a transient warning in the permanently reserved top alert row. */
    public void showAlert(String message) {
        if (message == null || message.isBlank()) return;
        long version = alertVersion.incrementAndGet();
        topBar.setAlert(message);
        if (started) requestRedraw();
        CompletableFuture.delayedExecutor(8, TimeUnit.SECONDS).execute(() -> {
            if (alertVersion.compareAndSet(version, version + 1)) {
                topBar.setAlert("");
                if (started) requestRedraw();
            }
        });
    }

    public void clearAlert() {
        alertVersion.incrementAndGet();
        topBar.setAlert("");
        if (started) requestRedraw();
    }

    /**
     * The first row of the scrollable content region.
     */
    public int scrollTop() {
        return TopBar.TOP_HEIGHT + 1;
    }

    /**
     * The last row of the scrollable content region.
     * Accounts for TopBar at top, StatusBar/activity rows at bottom, and the
     * fixed queue pane. Queue rows are reserved even while empty so adding a
     * message cannot move JLine's live input anchor.
     */
    public int scrollBottom() {
        if (terminalHeight <= 0) return scrollTop() + 2;
        return Math.max(scrollTop(),
                terminalHeight - StatusBar.STATUS_HEIGHT
                        - reservedMiddleRows - getQueueRegionRows());
    }

    /** Number of fixed rows allocated to upcoming/queued messages. */
    public int getQueueRegionRows() {
        return queueRowsForTerminal(terminalHeight);
    }

    static int queueRowsForTerminal(int height) {
        if (height <= 0) return 0;
        if (height < 16) return 1;
        return Math.max(2, Math.min(5, height / 12 + 1));
    }

    public int queueTop() {
        return scrollBottom() + 1;
    }

    public int queueBottom() {
        return queueTop() + Math.max(0, getQueueRegionRows() - 1);
    }

    /** Snapshot of queue-pane text before cursor-addressed placement. */
    public List<String> getVisibleQueueLines() {
        synchronized (drawLock) {
            return visibleQueueLines();
        }
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

    /** Whether the fixed separator-row shortcut should currently be shown. */
    public boolean isScrollToBottomControlVisible() {
        int width = terminalWidth > 0 ? terminalWidth : 80;
        return !temporaryWindowActive
                && contentScrollOffset > 0
                && width > SCROLL_TO_BOTTOM_CONTROL_X
                + AnsiConstants.visibleLength(SCROLL_TO_BOTTOM_CONTROL);
    }

    /**
     * Handle a zero-based JLine primary-click coordinate. Only the visible
     * separator-row shortcut is active; every other click remains a no-op.
     */
    public boolean handleScrollToBottomClick(int x, int y) {
        int controlWidth = AnsiConstants.visibleLength(SCROLL_TO_BOTTOM_CONTROL);
        if (!isScrollToBottomControlVisible()
                || y != SCROLL_TO_BOTTOM_CONTROL_Y
                || x < SCROLL_TO_BOTTOM_CONTROL_X
                || x >= SCROLL_TO_BOTTOM_CONTROL_X + controlWidth) {
            return false;
        }
        return scrollToBottom();
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
     * Attach the active JLine reader. Redraw requests that arrive while a prompt
     * is live are dispatched through a JLine widget, so the reader lock serializes
     * them with printAbove/redisplay instead of letting ANSI frames interleave.
     */
    public void attachLineReader(LineReader reader) {
        this.lineReader = reader;
        if (reader == null) return;
        this.clearInputWidget = reader.getWidgets().get(LineReader.CLEAR);
        this.redisplayWidget = reader.getWidgets().get(LineReader.REDISPLAY);
        reader.getWidgets().put(REDRAW_WIDGET, this::redrawReaderFrame);
    }

    /** Detach the reader before terminal shutdown. */
    public void detachLineReader() {
        this.lineReader = null;
        this.clearInputWidget = null;
        this.redisplayWidget = null;
    }

    /**
     * Repaint from inside an active JLine widget. Clearing JLine's cached display
     * before the cursor-addressed frame is essential: a plain REDISPLAY sees an
     * unchanged prompt, emits nothing, and leaves the physical cursor at column 1.
     */
    public boolean redrawForInputWidget() {
        if (!started || lineReader == null) return false;
        // This synchronous key-driven frame already includes every pending state
        // change, so a queued asynchronous frame may safely become a no-op.
        redrawDirty.set(false);
        redrawReaderFrame();
        return true;
    }

    private boolean redrawReaderFrame() {
        // Match LineReader.printAbove's ordering while replacing the whole frame:
        // remove the cached input, draw at absolute rows, then let JLine restore
        // both the prompt and its exact editing cursor.
        Widget clearInput = clearInputWidget;
        if (clearInput != null) {
            clearInput.apply();
        }
        renderFrame();
        Widget redisplay = redisplayWidget;
        boolean applied = redisplay == null || redisplay.apply();
        // A frame/status redraw can occur while JLine has the cursor hidden.
        writeTerminal(ESC + "?25h");
        return applied;
    }

    /** Coalesced redraw entry point used by status/activity listeners. */
    public void requestRedraw() {
        if (!started) return;
        redrawDirty.set(true);
        if (!redrawQueued.compareAndSet(false, true)) return;
        try {
            redrawExecutor.execute(this::runAsyncRedraw);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            redrawQueued.set(false);
        }
    }

    private void runAsyncRedraw() {
        try {
            long remaining = MIN_ASYNC_FRAME_NANOS
                    - (System.nanoTime() - lastFrameNanos);
            if (remaining > 0L) LockSupport.parkNanos(remaining);
            if (!started || Thread.currentThread().isInterrupted()) return;

            // State accumulated before this point belongs to this frame. Updates
            // arriving during render set dirty again and receive one trailing frame.
            if (!redrawDirty.getAndSet(false)) return;
            LineReader active = lineReader;
            try {
                if (active != null && active.isReading()) {
                    active.callWidget(REDRAW_WIDGET);
                } else if (started) {
                    renderFrame();
                }
            } catch (RuntimeException ignored) {
                // Prompt teardown can race a background diagnostic/status frame.
                if (started && lineReader == null) renderFrame();
            }
        } finally {
            redrawQueued.set(false);
            if (started && redrawDirty.get()) requestRedraw();
        }
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
            // The previous JLine prompt belongs to the readLine call that just
            // finished. Clear its row before the picker owns the input anchor.
            replaceScrollRegion(contentViewLines, false, true);
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
                contentViewLines = mainTranscriptSnapshot();
            } else {
                contentViewLines = savedContentViewLines == null ? List.of() : savedContentViewLines;
            }
            contentScrollOffset = savedContentScrollOffset;
            contentViewPinsHeader = savedContentViewPinsHeader;
            savedContentViewKey = null;
            savedContentViewTitle = null;
            savedContentViewLines = null;
            temporaryWindowLines = List.of();
            // The nested picker readLine has finished. Leave a clean cursor row
            // for the outer chat loop to render the normal "kompile> " prompt.
            replaceScrollRegion(contentViewLines, contentViewPinsHeader, true);
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
            writeTerminal(ESC + "2J" + ESC + "H"); // clear + home
            // Starts the status refresh thread. Its initial redraw is routed
            // through the same frame coordinator installed in the constructor.
            statusBar.start(terminal);
        }

        // Handle terminal resize
        terminal.handle(Terminal.Signal.WINCH, signal -> {
            updateTerminalSize();
            topBar.setTerminalWidth(terminalWidth);
            recalcReservedMiddleRows(reservedRowsCalculator);
            statusBar.syncTerminalSize();
            requestRedraw();
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
        statusBar.syncTerminalSize();
        requestRedraw();
        fireResizeListeners();
    }

    /**
     * Stop the TUI: reset scroll regions, clear bars, stop refresh threads.
     */
    public void stop() {
        if (!started) return;
        started = false;

        statusBar.stop();
        detachLineReader();
        redrawExecutor.shutdownNow();

        synchronized (drawLock) {
            // Reset the region and clear through JLine's terminal stream so no
            // buffered stdout frame can arrive after shutdown.
            writeTerminal(ESC + "r" + ESC + "2J" + ESC + "H");
        }
    }

    // ── Drawing ───────────────────────────────────────────────────────────

    /**
     * Redraw both bars without touching the content area.
     * Call after state changes that affect top or bottom bar.
     */
    public void redrawBars() {
        if (!started) return;
        requestRedraw();
    }

    /**
     * Repaint the active content view after an external renderer (such as JLine)
     * has redrawn the terminal. The view state remains authoritative in this
     * object, so redisplay cannot leave a stale or blank activity screen behind.
     */
    public void redrawContentView() {
        if (!started || temporaryWindowActive) return;
        synchronized (drawLock) {
            writeTerminal(scrollRegionSequence()
                    + renderScrollToBottomControl()
                    + renderContentRegion(contentViewLines, contentViewPinsHeader, false));
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
        List<String> lines = splitLines(text);
        synchronized (drawLock) {
            int addedRows = visualRows(lines).size();
            rememberMainLines(lines);
            if (isMainContentView() && contentScrollOffset > 0) {
                contentScrollOffset = clampScrollOffset(
                        contentScrollOffset + addedRows, contentViewLines, false);
            }
        }
        // Managed output is rendered only by a complete cursor-addressed frame.
        // Never echo raw tool text into JLine's scroll region: one long logical
        // line can auto-wrap through the row reserved for the user's input.
        if (started) requestRedraw();
    }

    /**
     * Insert or replace one live tool/process block in the main transcript.
     * The block is retained while an activity view or modal is open and becomes
     * visible again when the user returns to the main chat.
     */
    public boolean upsertMainTranscriptBlock(String key, String text) {
        if (key == null || key.isBlank()) return false;
        List<String> lines = splitLines(text);
        synchronized (drawLock) {
            int previousRows = 0;
            TranscriptEntry matched = null;
            for (TranscriptEntry entry : mainTranscriptEntries) {
                if (key.equals(entry.key)) {
                    matched = entry;
                    previousRows = visualRows(entry.lines).size();
                    mainTranscriptLineCount -= entry.lines.size();
                    break;
                }
            }
            if (matched == null) {
                matched = new TranscriptEntry(key, List.of());
                mainTranscriptEntries.addLast(matched);
            }
            matched.lines = List.copyOf(lines);
            mainTranscriptLineCount += matched.lines.size();
            trimMainTranscript();

            if (isMainContentView()) {
                contentViewLines = mainTranscriptSnapshot();
                int nextRows = visualRows(matched.lines).size();
                if (contentScrollOffset > 0 && nextRows > previousRows) {
                    contentScrollOffset = clampScrollOffset(
                            contentScrollOffset + nextRows - previousRows,
                            contentViewLines, false);
                }
            }
        }
        requestRedraw();
        return started;
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
                        contentScrollOffset + visualRows(lines).size(), contentViewLines, false);
            }
        }
        requestRedraw();
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
            int previousSize = visualBodyRows(contentViewLines, true).size();
            boolean followingTail = contentScrollOffset == 0;
            contentViewTitle = title == null || title.isBlank() ? "Activity" : title;
            contentViewLines = List.copyOf(lines);
            contentViewPinsHeader = true;
            int nextSize = visualBodyRows(lines, true).size();
            if (!followingTail && nextSize > previousSize) {
                contentScrollOffset += nextSize - previousSize;
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
            List<String> lines = mainTranscriptSnapshot();
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
        List<String> retained = List.copyOf(lines);
        mainTranscriptEntries.addLast(new TranscriptEntry(null, retained));
        mainTranscriptLineCount += retained.size();
        trimMainTranscript();
        if (isMainContentView()) {
            contentViewLines = mainTranscriptSnapshot();
        }
    }

    private void trimMainTranscript() {
        while (mainTranscriptLineCount > MAX_MAIN_TRANSCRIPT_LINES
                && !mainTranscriptEntries.isEmpty()) {
            TranscriptEntry first = mainTranscriptEntries.peekFirst();
            int overflow = mainTranscriptLineCount - MAX_MAIN_TRANSCRIPT_LINES;
            if (first.lines.size() <= overflow) {
                mainTranscriptEntries.removeFirst();
                mainTranscriptLineCount -= first.lines.size();
            } else {
                first.lines = List.copyOf(first.lines.subList(overflow, first.lines.size()));
                mainTranscriptLineCount -= overflow;
            }
        }
    }

    private List<String> mainTranscriptSnapshot() {
        List<String> lines = new ArrayList<>(mainTranscriptLineCount);
        for (TranscriptEntry entry : mainTranscriptEntries) {
            lines.addAll(entry.lines);
        }
        return List.copyOf(lines);
    }

    private static List<String> splitLines(String text) {
        if (text == null) {
            return List.of("");
        }
        return List.of(text.split("\\R", -1));
    }

    private void writeScrollLine(String line) {
        writeTerminal(ESC + scrollTop() + ";" + scrollBottom() + "r"
                + ESC + scrollBottom() + ";1H" + ESC + "2K" + line + '\n');
    }

    /** Emit one complete cursor-addressed frame through the process terminal stream. */
    private void renderFrame() {
        if (!started) return;
        synchronized (drawLock) {
            StringBuilder frame = new StringBuilder();
            frame.append(scrollRegionSequence());
            frame.append(topBar.render(terminalWidth));
            frame.append(renderScrollToBottomControl());
            if (!temporaryWindowActive) {
                frame.append(renderContentRegion(contentViewLines, contentViewPinsHeader, false));
            } else {
                frame.append(renderContentRegion(temporaryWindowLines, false, false));
            }
            frame.append(renderQueueRegion());
            frame.append(statusBar.render(terminalHeight, terminalWidth));
            frame.append(ESC).append(scrollBottom()).append(";1H");
            writeTerminal(frame.toString());
            lastFrameNanos = System.nanoTime();
        }
    }

    private String scrollRegionSequence() {
        int top = scrollTop();
        int bottom = scrollBottom();
        if (bottom <= top + 2) return "";
        return ESC + top + ";" + bottom + "r" + ESC + bottom + ";1H";
    }

    private String renderContentRegion(List<String> lines, boolean preserveHeader,
                                       boolean clearInputRow) {
        int top = scrollTop();
        int bottom = scrollBottom();
        int contentBottom = Math.max(top, bottom - 1);
        StringBuilder frame = new StringBuilder();
        frame.append(ESC).append(top).append(';').append(bottom).append('r');
        for (int row = top; row <= contentBottom; row++) {
            frame.append(ESC).append(row).append(";1H").append(ESC).append("2K");
        }
        List<String> visible = visibleContentLines(lines, preserveHeader);
        int row = top;
        for (int i = 0; i < visible.size() && row <= contentBottom; i++, row++) {
            frame.append(ESC).append(row).append(";1H").append(visible.get(i));
        }
        if (clearInputRow) {
            frame.append(ESC).append(bottom).append(";1H").append(ESC).append("2K");
        }
        frame.append(ESC).append(bottom).append(";1H");
        return frame.toString();
    }

    /**
     * Paint or erase the scroll shortcut within the existing top separator.
     * Replacing only these cells keeps the fixed layout and transcript capacity
     * unchanged while allowing partial content repaints to update the control.
     */
    private String renderScrollToBottomControl() {
        int width = terminalWidth > 0 ? terminalWidth : 80;
        int available = Math.max(0, width - SCROLL_TO_BOTTOM_CONTROL_X - 1);
        int cells = Math.min(
                AnsiConstants.visibleLength(SCROLL_TO_BOTTOM_CONTROL), available);
        if (cells <= 0) return "";

        String content = isScrollToBottomControlVisible()
                ? BOLD + CYAN + SCROLL_TO_BOTTOM_CONTROL + RESET
                : DIM + HORIZONTAL_LINE.repeat(cells) + RESET;
        return ESC + TopBar.TOP_HEIGHT + ";"
                + (SCROLL_TO_BOTTOM_CONTROL_X + 1) + "H" + content;
    }

    private String renderQueueRegion() {
        int rows = getQueueRegionRows();
        if (rows <= 0) return "";
        int top = queueTop();
        StringBuilder frame = new StringBuilder();
        for (int row = top; row < top + rows; row++) {
            frame.append(ESC).append(row).append(";1H").append(ESC).append("2K");
        }
        List<String> visible = visibleQueueLines();
        for (int i = 0; i < visible.size() && i < rows; i++) {
            frame.append(ESC).append(top + i).append(";1H").append(visible.get(i));
        }
        return frame.toString();
    }

    List<String> visibleQueueLines() {
        int rows = getQueueRegionRows();
        if (rows <= 0) return List.of();
        List<MessageQueue.QueuedMessage> messages =
                messageQueue == null ? List.of() : messageQueue.getAll();
        int width = terminalWidth > 0 ? terminalWidth : 80;
        if (messages.isEmpty()) {
            return List.of();
        }

        if (rows == 1) {
            MessageQueue.QueuedMessage next = messages.get(0);
            return List.of(renderer.cyan(" Queue " + messages.size() + " · next ["
                    + next.getId() + "] ")
                    + truncateQueueLine(singleLine(next.getContent()), Math.max(8, width - 30)));
        }

        int itemSlots = rows - 1;
        int shown = Math.min(itemSlots, messages.size());
        int hidden = messages.size() - shown;
        List<String> lines = new ArrayList<>(rows);
        String header = " Queue · " + messages.size() + " pending · ↑ edits latest"
                + (hidden > 0 ? " · +" + hidden + " more" : "");
        lines.add(renderer.bold(renderer.cyan(truncateQueueLine(header, width))));
        List<Integer> visibleIndexes = new ArrayList<>(shown);
        for (int i = 0; i < shown; i++) visibleIndexes.add(i);
        int editingIndex = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).getStatus()
                    == MessageQueue.QueuedMessage.QueuedMessageStatus.EDITING) {
                editingIndex = i;
                break;
            }
        }
        // The upcoming item is always pinned. If an edit lease belongs to a
        // message beyond the visible head, pin that item in the final slot too.
        if (editingIndex >= shown && shown > 1) {
            visibleIndexes.set(shown - 1, editingIndex);
        }
        for (int visibleIndex : visibleIndexes) {
            MessageQueue.QueuedMessage message = messages.get(visibleIndex);
            boolean editing = message.getStatus()
                    == MessageQueue.QueuedMessage.QueuedMessageStatus.EDITING;
            String role = visibleIndex == 0
                    ? "→ upcoming" : "  queued " + (visibleIndex + 1);
            String prefix = " " + role + " [" + message.getId() + "] ";
            String content = truncateQueueLine(
                    singleLine(message.getContent()), Math.max(8, width - prefix.length() - 12));
            String suffix = editing ? renderer.yellow("  ✎ editing") : "";
            lines.add((visibleIndex == 0 ? renderer.cyan(prefix) : renderer.dim(prefix))
                    + content + suffix);
        }
        return List.copyOf(lines);
    }

    private static String singleLine(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }

    private static String truncateQueueLine(String value, int maxLength) {
        if (value == null || maxLength <= 0) return "";
        if (value.length() <= maxLength) return value;
        return value.substring(0, Math.max(0, maxLength - 1)) + "…";
    }

    private void replaceScrollRegion(List<String> lines, boolean preserveHeader) {
        replaceScrollRegion(lines, preserveHeader, false);
    }

    /**
     * Repaint the transcript and optionally clear the row owned by JLine's
     * current prompt. Modal windows use the clearing form when transitioning
     * between nested readLine calls; normal asynchronous redraws must leave
     * the live input row to JLine.
     */
    private void replaceScrollRegion(List<String> lines, boolean preserveHeader,
                                     boolean clearInputRow) {
        if (!started) {
            return;
        }
        // Never query JLine's reader lock while holding drawLock. The redraw
        // worker takes the inverse order (reader lock, then drawLock), so checking
        // isReading here would deadlock a producer during an active prompt.
        LineReader reader = lineReader;
        if (reader != null) {
            requestRedraw();
            return;
        }
        writeTerminal(renderScrollToBottomControl()
                + renderContentRegion(lines, preserveHeader, clearInputRow));
    }

    private List<String> visibleContentLines(List<String> lines, boolean preserveHeader) {
        int capacity = transcriptCapacity();
        if (lines == null || lines.isEmpty()) return List.of();
        int offset = clampScrollOffset(contentScrollOffset, lines, preserveHeader);
        if (preserveHeader && capacity > 1) {
            int bodyCapacity = capacity - 1;
            List<String> headerRows = visualRows(List.of(lines.get(0)));
            List<String> bodyRows = visualBodyRows(lines, true);
            int bodySize = bodyRows.size();
            int end = Math.max(0, bodySize - offset);
            int start = Math.max(0, end - bodyCapacity);
            List<String> visible = new ArrayList<>(capacity);
            visible.add(headerRows.isEmpty() ? "" : headerRows.get(0));
            visible.addAll(bodyRows.subList(start, end));
            return visible;
        }
        List<String> rows = visualRows(lines);
        int end = Math.max(0, rows.size() - offset);
        int start = Math.max(0, end - capacity);
        return List.copyOf(rows.subList(start, end));
    }

    private int clampScrollOffset(int requested, List<String> lines, boolean preserveHeader) {
        int capacity = transcriptCapacity();
        int bodyCount = visualBodyRows(lines, preserveHeader).size();
        int bodyCapacity = preserveHeader && capacity > 1 ? capacity - 1 : capacity;
        int maximum = Math.max(0, bodyCount - Math.max(1, bodyCapacity));
        return Math.max(0, Math.min(requested, maximum));
    }

    private List<String> visualBodyRows(List<String> lines, boolean preserveHeader) {
        if (lines == null || lines.isEmpty()) return List.of();
        return visualRows(preserveHeader ? lines.subList(1, lines.size()) : lines);
    }

    /**
     * Convert retained logical lines into bounded terminal rows. JLine performs
     * ANSI-aware column measurement, including wide Unicode, while width-1 keeps
     * the terminal's automatic right-margin wrap from advancing into input.
     */
    private List<String> visualRows(List<String> lines) {
        if (lines == null || lines.isEmpty()) return List.of();
        int width = Math.max(1, (terminalWidth > 0 ? terminalWidth : 80) - 1);
        List<String> rows = new ArrayList<>();
        Terminal active = terminal;
        for (String line : lines) {
            AttributedString attributed = AttributedString.fromAnsi(line == null ? "" : line);
            List<AttributedString> wrapped = attributed.columnSplitLength(width);
            if (wrapped.isEmpty()) {
                rows.add("");
                continue;
            }
            for (AttributedString row : wrapped) {
                rows.add(active == null ? row.toAnsi() : row.toAnsi(active));
            }
        }
        return rows;
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
        if (started) redrawBars();
    }

    public void setMode(String mode) {
        topBar.setMode(mode);
        if (started) redrawBars();
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

    /** Keep TUI cursor controls and JLine input on one ordered terminal stream. */
    private void writeTerminal(String text) {
        Terminal active = terminal;
        if (active != null) {
            active.writer().print(text);
            active.writer().flush();
            return;
        }
        System.out.print(text);
        System.out.flush();
    }

    private void setScrollRegion() {
        int top = scrollTop();
        int bottom = scrollBottom();
        if (bottom <= top + 2) return; // Too small
        writeTerminal(scrollRegionSequence());
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
