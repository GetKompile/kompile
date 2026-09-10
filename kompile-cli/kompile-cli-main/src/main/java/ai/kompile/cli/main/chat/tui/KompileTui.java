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
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.WCWidth;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

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
 * │  (managed transcript — agent output, tools,  │  Rows 4..N    ← transcript region
 * │   markdown, activity views, etc.)             │
 * ├─────────────────────────────────────────────┤  ← input separator
 * │ kompile &gt; user draft                         │  ← JLine-only input pane
 * │ Queue · 2 pending · ↑ edits latest            │  Fixed queue pane
 * │ → upcoming [a1b2c3d4] first pending message   │
 * ├─────────────────────────────────────────────┤  Row H-1 ← StatusBar separator
 * │ ⠋ proc-001 (2m) │ ◐ 1 bg │ Q:3 │ coder      │  Row H   ← StatusBar content
 * └─────────────────────────────────────────────┘
 * </pre>
 *
 * The transcript scroll region ends above a dedicated input pane. TopBar,
 * input, queue, activity, and StatusBar rows are never owned by transcript rendering.
 */
public class KompileTui {

    private static final String MAIN_CONTENT_VIEW = "main";
    private static final String REDRAW_WIDGET = "kompile-redraw-frame";
    private static final String REDRAW_WITH_INPUT_WIDGET = "kompile-redraw-frame-with-input";
    private static final Object COMMAND_OUTPUT_MONITOR = new Object();
    private static final InheritableThreadLocal<CommandOutputTransaction> ACTIVE_COMMAND_OUTPUT =
            new InheritableThreadLocal<>();
    private static final AtomicLong COMMAND_OUTPUT_SEQUENCE = new AtomicLong();
    private static final int MAX_MAIN_TRANSCRIPT_LINES = 2_000;
    private static final int MAX_TEMPORARY_COMMAND_OUTPUT_LINES = 20;
    private static final int MAX_COMMAND_PARTIAL_BYTES = 16 * 1024;
    private static final long MIN_ASYNC_FRAME_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private static final long RESIZE_POLL_MILLIS = 100L;
    private static final String SCROLL_TO_BOTTOM_CONTROL = "[↓ Scroll to bottom]";

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
    private final ScheduledExecutorService resizeExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "kompile-tui-resize");
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicBoolean redrawQueued = new AtomicBoolean(false);
    private final AtomicBoolean redrawDirty = new AtomicBoolean(false);
    private final AtomicBoolean inputRedisplayRequested = new AtomicBoolean(false);
    private final AtomicLong alertVersion = new AtomicLong();
    private volatile long lastFrameNanos;

    private volatile Terminal terminal;
    private volatile int terminalHeight;
    private volatile int terminalWidth;
    // started is the retained surface lifetime; terminal attachment is independent.
    private volatile boolean started = false;
    private volatile boolean stopped;
    private volatile long attachmentVersion;
    // Shared only as a callback guard: a late named JLine widget lookup may now
    // resolve to another surface's widget on the same reader.
    private record RedrawAttachment(KompileTui owner, long version) {}
    private static final ThreadLocal<RedrawAttachment> REDRAW_ATTACHMENT = new ThreadLocal<>();
    // A redraw acquires this only AFTER JLine's reader lock. Detach never acquires
    // a reader lock, so it can wait for a frame without reversing JLine lock order.
    private final java.util.concurrent.locks.ReentrantReadWriteLock attachmentLock =
            new java.util.concurrent.locks.ReentrantReadWriteLock();
    private volatile LineReader lineReader;
    private volatile boolean inputPaneLayout;
    private volatile Widget clearInputWidget;
    private volatile Widget redisplayWidget;
    private volatile Object readerBinding;
    private record ReaderRedrawDispatch(LineReader reader, String frame, String input) {}
    private volatile ReaderRedrawDispatch readerRedrawDispatch;
    private Widget ownedRedrawWidget;
    private Widget ownedInputRedrawWidget;
    private Widget previousRedrawWidget;
    private Widget previousInputRedrawWidget;

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

    /** One terminal row plus the retained logical line that produced it. */
    private record VisualRow(AttributedString text, int logicalLine) {}

    /** Absolute visual-row/cell coordinate within the active content view. */
    private record SelectionPoint(int row, int column) {}

    /** Normalized half-open selection range. */
    private record SelectionRange(SelectionPoint start, SelectionPoint end) {}

    /** Visible rows and their indexes in the complete visual transcript. */
    private record VisibleVisualRows(
            List<VisualRow> allRows,
            List<VisualRow> rows,
            List<Integer> absoluteIndexes) {}

    private final Deque<TranscriptEntry> mainTranscriptEntries = new ArrayDeque<>();
    private int mainTranscriptLineCount;
    private volatile String contentViewKey = MAIN_CONTENT_VIEW;
    private volatile String contentViewTitle = "Main chat";
    private volatile List<String> contentViewLines = List.of();
    /** Rows from the transcript tail, or from the top for a temporary picker. */
    private volatile int contentScrollOffset = 0;
    /** Activity views pin their title row while their transcript body scrolls. */
    private volatile boolean contentViewPinsHeader = false;

    /** Optional project dashboard pinned between the top bar and transcript. */
    private volatile DashboardSnapshot dashboard = DashboardSnapshot.hidden();

    /** Browser-like transcript selection retained independently of viewport scroll. */
    private SelectionPoint selectionAnchorCell;
    private SelectionPoint selectionActiveCell;
    private boolean selectionDragging;
    private String selectionViewKey;
    private int selectionPointerX;
    private int selectionPointerY;
    private boolean selectionAutoScrollScheduled;
    private long selectionVersion;

    /** True while a short-lived modal (for example the provider/model picker) owns the content area. */
    private volatile boolean temporaryWindowActive = false;
    private volatile String temporaryWindowTitle = "";
    private volatile List<String> temporaryWindowLines = List.of();
    private final LinkedHashMap<String, String> temporaryCommandOutputBlocks =
            new LinkedHashMap<>();
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

    /** Replace the persistent project dashboard without appending transcript rows. */
    public void setDashboard(String title, List<String> lines) {
        String safeTitle = title == null || title.isBlank() ? "Dashboard" : title;
        List<String> safeLines = lines == null ? List.of() : lines.stream()
                .filter(java.util.Objects::nonNull)
                .limit(8)
                .toList();
        dashboard = new DashboardSnapshot(true, safeTitle, safeLines);
        if (started) requestRedrawWithInput();
    }

    /** Hide the project dashboard and release its pinned terminal rows. */
    public void clearDashboard() {
        dashboard = DashboardSnapshot.hidden();
        if (started) requestRedrawWithInput();
    }

    public DashboardSnapshot getDashboardSnapshot() {
        return dashboard;
    }

    public record DashboardSnapshot(boolean visible, String title, List<String> lines) {
        public DashboardSnapshot {
            title = title == null ? "Dashboard" : title;
            lines = lines == null ? List.of() : List.copyOf(lines);
        }

        private static DashboardSnapshot hidden() {
            return new DashboardSnapshot(false, "Dashboard", List.of());
        }
    }

    public static final long EPHEMERAL_MESSAGE_SECONDS = 10;

    /** Show a transient warning in the permanently reserved top alert row. */
    public void showAlert(String message) {
        if (message == null || message.isBlank()) return;
        showAlert(message, CompletableFuture.delayedExecutor(EPHEMERAL_MESSAGE_SECONDS, TimeUnit.SECONDS));
    }

    void showAlert(String message, java.util.concurrent.Executor expiryExecutor) {
        synchronized (drawLock) {
            long version = alertVersion.incrementAndGet();
            topBar.setAlert(message);
            if (started) requestRedraw();
            expiryExecutor.execute(() -> {
                synchronized (drawLock) {
                    if (alertVersion.compareAndSet(version, version + 1)) {
                        topBar.setAlert("");
                        if (started) requestRedraw();
                    }
                }
            });
        }
    }

    public void clearAlert() {
        synchronized (drawLock) {
            alertVersion.incrementAndGet();
            topBar.setAlert("");
            if (started) requestRedraw();
        }
    }

    /**
     * The first row of the scrollable content region.
     */
    public int scrollTop() {
        return TopBar.TOP_HEIGHT + getDashboardRegionRows() + 1;
    }

    /** Stable responsive row budget; dashboard updates never resize the transcript. */
    public int getDashboardRegionRows() {
        if (!dashboard.visible()) return 0;
        int height = terminalHeight > 0 ? terminalHeight : 24;
        return dashboardRowsForLayout(
                height, reservedMiddleRows, queueRowsForTerminal(height));
    }

    static int dashboardRowsForLayout(int height, int reservedRows, int queueRows) {
        int desired = dashboardRowsForTerminal(height);
        int baseScrollTop = TopBar.TOP_HEIGHT + 1;
        int bottomWithoutDashboard = height - StatusBar.STATUS_HEIGHT
                - Math.max(0, reservedRows) - Math.max(0, queueRows);
        int availableScrollRows = Math.max(0, bottomWithoutDashboard - baseScrollTop + 1);
        int rows = Math.min(desired, Math.max(0, availableScrollRows - 5));
        return rows < 2 ? 0 : rows;
    }

    static int dashboardRowsForTerminal(int height) {
        if (height < 18) return 0;
        return Math.max(5, Math.min(9, height / 3));
    }

    /**
     * The last row of the central transcript/input allocation, immediately
     * before the fixed queue pane. When JLine is attached, {@link #inputTop()}
     * and {@link #transcriptBottom()} partition this allocation into disjoint
     * editor and transcript regions. Queue rows remain reserved while empty so
     * adding a message cannot move the live input pane.
     */
    public int scrollBottom() {
        if (terminalHeight <= 0) return scrollTop() + 2;
        return Math.max(scrollTop(),
                terminalHeight - StatusBar.STATUS_HEIGHT
                        - reservedMiddleRows - getQueueRegionRows());
    }

    /**
     * Stable row budget owned exclusively by an attached JLine editor. The pane
     * grows on larger terminals but always leaves a separator and two transcript rows.
     * Terminal detach retains this budget; surfaces without an editor use one prompt row.
     */
    public int getInputRegionRows() {
        int desired = !inputPaneLayout
                ? 1 : inputRowsForTerminal(terminalHeight > 0 ? terminalHeight : 24);
        int maximum = Math.max(1, scrollBottom() - scrollTop() - 2);
        return Math.min(desired, maximum);
    }

    static int inputRowsForTerminal(int height) {
        if (height < 16) return 2;
        return Math.max(3, Math.min(8, height / 6));
    }

    /** First 1-based row owned by the JLine editor. */
    public int inputTop() {
        return Math.max(scrollTop(), scrollBottom() - getInputRegionRows() + 1);
    }

    /** Separator row between transcript and the JLine-owned pane. */
    public int inputSeparatorRow() {
        return Math.max(scrollTop(), inputTop() - 1);
    }

    /** Last 1-based row owned by transcript content. */
    public int transcriptBottom() {
        if (!inputPaneLayout) {
            return Math.max(scrollTop(), scrollBottom() - 1);
        }
        return Math.max(scrollTop(), inputSeparatorRow() - 1);
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

    /** Whether the floating viewport shortcut should currently be shown. */
    public boolean isScrollToBottomControlVisible() {
        int width = terminalWidth > 0 ? terminalWidth : 80;
        return !temporaryWindowActive
                && contentScrollOffset > 0
                && width > AnsiConstants.visibleLength(SCROLL_TO_BOTTOM_CONTROL) + 2
                && scrollToBottomControlY() >= scrollTop() - 1;
    }

    /** Zero-based floating-control column, centered for the current terminal width. */
    public int scrollToBottomControlX() {
        int width = terminalWidth > 0 ? terminalWidth : 80;
        int controlWidth = AnsiConstants.visibleLength(SCROLL_TO_BOTTOM_CONTROL);
        return Math.max(1, (width - controlWidth) / 2);
    }

    /** Zero-based row at the bottom of the transcript viewport. */
    public int scrollToBottomControlY() {
        return transcriptBottom() - 1;
    }

    /**
     * Handle a zero-based JLine primary-click coordinate. Only the visible
     * floating shortcut is active; every other click remains a no-op.
     */
    public boolean handleScrollToBottomClick(int x, int y) {
        int controlWidth = AnsiConstants.visibleLength(SCROLL_TO_BOTTOM_CONTROL);
        if (!isScrollToBottomControlVisible()
                || y != scrollToBottomControlY()
                || x < scrollToBottomControlX()
                || x >= scrollToBottomControlX() + controlWidth) {
            return false;
        }
        return scrollToBottom();
    }

    /** Begin a primary-button selection on a rendered transcript row. */
    public boolean beginTranscriptSelection(int x, int y) {
        synchronized (drawLock) {
            SelectionPoint point = selectionPointAt(x, y, false);
            if (point == null || temporaryWindowActive) {
                boolean changed = clearTranscriptSelectionLocked();
                if (changed) replaceScrollRegion(contentViewLines, contentViewPinsHeader);
                return changed;
            }
            selectionAnchorCell = point;
            selectionActiveCell = point;
            selectionDragging = true;
            selectionViewKey = contentViewKey;
            selectionPointerX = x;
            selectionPointerY = y;
            selectionAutoScrollScheduled = false;
            selectionVersion++;
            replaceScrollRegion(contentViewLines, contentViewPinsHeader);
            return true;
        }
    }

    /** Extend the active selection, scrolling when the pointer reaches a viewport edge. */
    public boolean dragTranscriptSelection(int x, int y) {
        synchronized (drawLock) {
            if (!selectionDragging || !selectionBelongsToActiveView()) return false;
            selectionPointerX = x;
            selectionPointerY = y;
            boolean changed = autoScrollSelectionAt(y);
            SelectionPoint point = selectionPointAt(x, y, true, true);
            if (point != null && !point.equals(selectionActiveCell)) {
                selectionActiveCell = point;
                changed = true;
            }
            scheduleSelectionAutoScrollLocked();
            if (changed) replaceScrollRegion(contentViewLines, contentViewPinsHeader);
            return changed;
        }
    }

    /** Finish a primary-button selection while retaining its highlight for copying. */
    public boolean finishTranscriptSelection(int x, int y) {
        synchronized (drawLock) {
            if (!selectionDragging || !selectionBelongsToActiveView()) return false;
            selectionPointerX = x;
            selectionPointerY = y;
            SelectionPoint point = selectionPointAt(x, y, true, true);
            if (point != null) selectionActiveCell = point;
            selectionDragging = false;
            if (selectionRangeLocked() == null) clearTranscriptSelectionLocked();
            replaceScrollRegion(contentViewLines, contentViewPinsHeader);
            return true;
        }
    }

    /** Clear any retained transcript selection. */
    public boolean clearTranscriptSelection() {
        synchronized (drawLock) {
            boolean changed = clearTranscriptSelectionLocked();
            if (changed) replaceScrollRegion(contentViewLines, contentViewPinsHeader);
            return changed;
        }
    }

    public boolean hasTranscriptSelection() {
        synchronized (drawLock) {
            return selectionRangeLocked() != null;
        }
    }

    /** Whether a zero-based terminal coordinate addresses a currently rendered transcript row. */
    public boolean isTranscriptCoordinate(int x, int y) {
        synchronized (drawLock) {
            return x >= 0 && selectionPointAt(x, y, false) != null;
        }
    }

    /** Plain selected text with ANSI removed and soft wraps joined back together. */
    public String getSelectedTranscriptText() {
        synchronized (drawLock) {
            List<VisualRow> rows = selectableVisualRows(contentViewLines, contentViewPinsHeader);
            SelectionRange range = selectionRangeLocked(rows);
            if (range == null) return "";
            StringBuilder selected = new StringBuilder();
            for (int rowIndex = range.start().row(); rowIndex <= range.end().row(); rowIndex++) {
                VisualRow row = rows.get(rowIndex);
                int from = rowIndex == range.start().row() ? range.start().column() : 0;
                int to = rowIndex == range.end().row()
                        ? range.end().column() : row.text().columnLength();
                if (to > from) {
                    selected.append(row.text().columnSubSequence(from, to));
                }
                if (rowIndex < range.end().row()
                        && row.logicalLine() != rows.get(rowIndex + 1).logicalLine()) {
                    selected.append('\n');
                }
            }
            return AnsiConstants.stripAnsi(selected.toString());
        }
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
        attachmentLock.writeLock().lock();
        try {
            attachLineReaderLocked(reader);
        } finally {
            attachmentLock.writeLock().unlock();
        }
    }

    private void attachLineReaderLocked(LineReader reader) {
        if (stopped) throw new IllegalStateException("Chat surface is stopped");
        if (lineReader == reader) return;
        detachLineReader();
        this.lineReader = reader;
        inputPaneLayout = reader != null;
        if (reader == null) return;
        Object binding = new Object();
        readerBinding = binding;
        this.clearInputWidget = reader.getWidgets().get(LineReader.CLEAR);
        this.redisplayWidget = reader.getWidgets().get(LineReader.REDISPLAY);
        ownedRedrawWidget = () -> redrawReaderFramePreservingInput(binding);
        ownedInputRedrawWidget = () -> redrawReaderFrameWithInput(binding);
        previousRedrawWidget = reader.getWidgets().put(REDRAW_WIDGET, ownedRedrawWidget);
        previousInputRedrawWidget = reader.getWidgets().put(
                REDRAW_WITH_INPUT_WIDGET, ownedInputRedrawWidget);
        // Async dispatch must never resolve a restored, unrelated widget after detach.
        // Keep the public aliases for existing callers, but give this binding private names.
        String suffix = ":" + java.util.UUID.randomUUID();
        ReaderRedrawDispatch dispatch = new ReaderRedrawDispatch(reader,
                REDRAW_WIDGET + suffix, REDRAW_WITH_INPUT_WIDGET + suffix);
        reader.getWidgets().put(dispatch.frame(), ownedRedrawWidget);
        reader.getWidgets().put(dispatch.input(), ownedInputRedrawWidget);
        readerRedrawDispatch = dispatch;
    }

    /**
     * Clear JLine's accepted echo from the whole input pane, including wrapped
     * and pasted rows. Call on the input thread after readLine returns and before
     * dispatching the line or starting another read. Never queue this cleanup:
     * an asynchronous clear could erase the next draft. JLine owns its buffer
     * and history; only the completed editor's terminal rows are cleared here.
     */
    public void clearSubmittedInput() {
        if (!started || lineReader == null) return;
        renderFrame(false, true);
    }

    /** Detach the reader before terminal shutdown. */
    public void detachLineReader() {
        attachmentLock.writeLock().lock();
        try {
            inputPaneLayout = false;
            detachLineReaderLocked();
        } finally {
            attachmentLock.writeLock().unlock();
        }
    }

    private void detachLineReaderLocked() {
        LineReader previous = lineReader;
        ReaderRedrawDispatch dispatch = readerRedrawDispatch;
        readerRedrawDispatch = null;
        readerBinding = null;
        if (dispatch != null) {
            dispatch.reader().getWidgets().remove(dispatch.frame(), ownedRedrawWidget);
            dispatch.reader().getWidgets().remove(dispatch.input(), ownedInputRedrawWidget);
        }
        this.lineReader = null;
        this.clearInputWidget = null;
        this.redisplayWidget = null;
        if (previous != null) {
            restoreWidget(previous, REDRAW_WIDGET, ownedRedrawWidget, previousRedrawWidget);
            restoreWidget(previous, REDRAW_WITH_INPUT_WIDGET,
                    ownedInputRedrawWidget, previousInputRedrawWidget);
        }
        ownedRedrawWidget = null;
        ownedInputRedrawWidget = null;
        previousRedrawWidget = null;
        previousInputRedrawWidget = null;
    }

    private static void restoreWidget(LineReader reader, String name, Widget owned, Widget previous) {
        if (owned == null) return;
        if (previous == null) reader.getWidgets().remove(name, owned);
        else reader.getWidgets().replace(name, owned, previous);
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
        inputRedisplayRequested.set(false);
        return redrawReaderFrameWithInput(readerBinding);
    }

    /**
     * Run one slash handler while routing its stdout/stderr into the retained
     * transcript. The transaction context is inherited by command-created worker
     * threads, while unrelated existing background producers retain their original
     * streams. This keeps legacy handler output out of JLine's small input pane.
     */
    public boolean runCommandOutput(BooleanSupplier command) {
        java.util.Objects.requireNonNull(command, "command");
        if (!started) return command.getAsBoolean();

        // System.out/System.err are process-wide. Serialize replacements across
        // TUI instances; Java monitors are reentrant for nested slash dispatches.
        synchronized (COMMAND_OUTPUT_MONITOR) {
            return runCommandOutputLocked(command);
        }
    }

    private boolean runCommandOutputLocked(BooleanSupplier command) {
        synchronized (drawLock) {
            temporaryCommandOutputBlocks.clear();
        }

        // readLine has returned, so the accepted command no longer belongs to
        // a live editor. Clear it before any handler output is emitted.
        renderFrame(false, true);

        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        CommandOutputTransaction previousTransaction = ACTIVE_COMMAND_OUTPUT.get();
        CommandOutputTransaction transaction = new CommandOutputTransaction(
                this, COMMAND_OUTPUT_SEQUENCE.incrementAndGet());
        CommandOutputStream output = new CommandOutputStream(transaction, originalOut);
        CommandOutputStream error = new CommandOutputStream(transaction, originalErr);
        PrintStream managedOut = commandPrintStream(output);
        PrintStream managedErr = commandPrintStream(error);
        boolean contextInstalled = false;
        boolean outInstalled = false;
        boolean errInstalled = false;
        Throwable commandFailure = null;
        try {
            ACTIVE_COMMAND_OUTPUT.set(transaction);
            contextInstalled = true;
            System.setOut(managedOut);
            outInstalled = true;
            System.setErr(managedErr);
            errInstalled = true;
            return command.getAsBoolean();
        } catch (RuntimeException | Error failure) {
            commandFailure = failure;
            throw failure;
        } finally {
            Throwable cleanupFailure = null;
            cleanupFailure = runCleanup(cleanupFailure, managedOut::flush);
            cleanupFailure = runCleanup(cleanupFailure, managedErr::flush);
            cleanupFailure = runCleanup(cleanupFailure, transaction::finish);
            if (outInstalled) {
                cleanupFailure = runCleanup(
                        cleanupFailure, () -> System.setOut(originalOut));
            }
            if (errInstalled) {
                cleanupFailure = runCleanup(
                        cleanupFailure, () -> System.setErr(originalErr));
            }
            if (contextInstalled) {
                cleanupFailure = runCleanup(cleanupFailure, () -> {
                    if (previousTransaction == null) {
                        ACTIVE_COMMAND_OUTPUT.remove();
                    } else {
                        ACTIVE_COMMAND_OUTPUT.set(previousTransaction);
                    }
                });
            }
            cleanupFailure = runCleanup(cleanupFailure, () -> {
                synchronized (drawLock) {
                    temporaryCommandOutputBlocks.clear();
                }
                renderFrame(false, true);
            });
            if (cleanupFailure != null) {
                if (commandFailure != null) {
                    commandFailure.addSuppressed(cleanupFailure);
                } else if (cleanupFailure instanceof RuntimeException runtime) {
                    throw runtime;
                } else if (cleanupFailure instanceof Error errorFailure) {
                    throw errorFailure;
                } else {
                    throw new IllegalStateException(
                            "Could not restore command output", cleanupFailure);
                }
            }
        }
    }

    private static Throwable runCleanup(Throwable prior, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException | Error failure) {
            if (prior == null) return failure;
            prior.addSuppressed(failure);
        }
        return prior;
    }

    private PrintStream commandPrintStream(CommandOutputStream output) {
        return new PrintStream(
                output,
                true, StandardCharsets.UTF_8);
    }

    private static final class CommandOutputTransaction {
        private KompileTui tui;
        private final String keyPrefix;
        private long nextLine;
        private CommandOutputStream activeStream;
        private volatile boolean closed;

        private CommandOutputTransaction(KompileTui tui, long sequence) {
            this.tui = tui;
            this.keyPrefix = "command-output:" + sequence + ":";
        }

        private void activate(CommandOutputStream stream) {
            if (activeStream != null && activeStream != stream) {
                activeStream.finishPartialLocked();
            }
            activeStream = stream;
        }

        private String nextKey() {
            return keyPrefix + nextLine++;
        }

        private void finish() {
            synchronized (this) {
                try {
                    if (activeStream != null) activeStream.finishPartialLocked();
                } finally {
                    activeStream = null;
                    closed = true;
                    tui = null;
                }
            }
        }
    }

    private static final class CommandOutputStream extends OutputStream {
        private final CommandOutputTransaction transaction;
        private final PrintStream fallback;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private String currentKey;
        private boolean partialTruncated;
        private boolean afterCarriageReturn;

        private CommandOutputStream(
                CommandOutputTransaction transaction, PrintStream fallback) {
            this.transaction = transaction;
            this.fallback = fallback;
        }

        @Override
        public void write(int value) {
            if (!routesToTransaction()) {
                fallback.write(value);
                return;
            }
            synchronized (transaction) {
                if (transaction.closed) {
                    fallback.write(value);
                    return;
                }
                transaction.activate(this);
                appendByteLocked(value);
                if (value != '\n' && value != '\r') {
                    // Direct byte writers publish on flush/newline. Avoid decoding
                    // the complete growing buffer once per byte.
                    trimPendingLocked();
                }
            }
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            if (!routesToTransaction()) {
                fallback.write(bytes, offset, length);
                return;
            }
            synchronized (transaction) {
                if (transaction.closed) {
                    fallback.write(bytes, offset, length);
                    return;
                }
                transaction.activate(this);
                int end = offset + length;
                for (int index = offset; index < end; index++) {
                    appendByteLocked(bytes[index] & 0xff);
                }
                if (pending.size() > 0) {
                    trimPendingLocked();
                    publishLocked(false);
                }
            }
        }

        @Override
        public void flush() {
            if (routesToTransaction()) {
                synchronized (transaction) {
                    if (!transaction.closed) {
                        transaction.activate(this);
                        if (pending.size() > 0) publishLocked(false);
                    }
                }
            }
            fallback.flush();
        }

        private boolean routesToTransaction() {
            return ACTIVE_COMMAND_OUTPUT.get() == transaction && !transaction.closed;
        }

        private void appendByteLocked(int value) {
            if (value == '\r') {
                if (pending.size() > 0) {
                    trimPendingLocked();
                    publishLocked(false);
                }
                pending.reset();
                partialTruncated = false;
                afterCarriageReturn = true;
                return;
            }
            if (value == '\n') {
                if (pending.size() > 0) {
                    trimPendingLocked();
                    publishLocked(true);
                } else if (currentKey != null) {
                    currentKey = null;
                } else if (!afterCarriageReturn) {
                    ensureCurrentKeyLocked();
                    publishLocked(true);
                }
                afterCarriageReturn = false;
                partialTruncated = false;
                return;
            }

            ensureCurrentKeyLocked();
            pending.write(value);
            afterCarriageReturn = false;
        }

        private void trimPendingLocked() {
            if (pending.size() <= MAX_COMMAND_PARTIAL_BYTES) return;
            byte[] bytes = pending.toByteArray();
            // Retain half the cap so byte-at-a-time writers have substantial
            // headroom before another trim instead of copying on every byte.
            int start = bytes.length - (MAX_COMMAND_PARTIAL_BYTES / 2);
            while (start < bytes.length && (bytes[start] & 0xc0) == 0x80) start++;
            pending.reset();
            pending.write(bytes, start, bytes.length - start);
            partialTruncated = true;
        }

        private void ensureCurrentKeyLocked() {
            if (currentKey == null) currentKey = transaction.nextKey();
        }

        private void finishPartialLocked() {
            if (pending.size() > 0) publishLocked(false);
            pending.reset();
            currentKey = null;
            partialTruncated = false;
            afterCarriageReturn = false;
        }

        private void publishLocked(boolean complete) {
            byte[] bytes = pending.toByteArray();
            int length = bytes.length;
            KompileTui target = transaction.tui;
            if (target == null) return;
            String text = new String(bytes, 0, length, StandardCharsets.UTF_8);
            target.recordCommandOutputBlock(
                    currentKey, partialTruncated ? "…" + text : text);
            if (complete) {
                pending.reset();
                currentKey = null;
                partialTruncated = false;
                afterCarriageReturn = false;
            }
        }
    }

    private void recordCommandOutputBlock(String key, String text) {
        upsertMainTranscriptBlock(key, text);
        synchronized (drawLock) {
            if (temporaryWindowActive) {
                temporaryCommandOutputBlocks.put(key, text);
                while (temporaryCommandOutputBlocks.size()
                        > MAX_TEMPORARY_COMMAND_OUTPUT_LINES) {
                    var iterator = temporaryCommandOutputBlocks.keySet().iterator();
                    iterator.next();
                    iterator.remove();
                }
                contentViewLines = temporaryWindowDisplayLines();
                contentScrollOffset = clampScrollOffset(
                        contentScrollOffset, contentViewLines, contentViewPinsHeader);
            }
        }
        requestRedraw();
    }

    private boolean redrawReaderFrameWithInput(Object binding) {
        attachmentLock.readLock().lock();
        try {
            if (binding == null || binding != readerBinding || !isCurrentAttachment()) return false;
            return redrawAttachedReaderFrameWithInput();
        } finally {
            attachmentLock.readLock().unlock();
        }
    }

    private boolean redrawAttachedReaderFrameWithInput() {
        // Match LineReader.printAbove's ordering while replacing the whole frame:
        // remove the cached input, draw at absolute rows, then let JLine restore
        // both the prompt and its exact editing cursor.
        Widget clearInput = clearInputWidget;
        if (clearInput != null) {
            clearInput.apply();
        }
        // Resize/reflow can move transcript cells into the input pane. JLine's
        // CLEAR only knows its cached draft footprint, not those foreign cells.
        // Erase the entire pane under the reader lock before restoring the draft.
        renderFrame(false, true);
        Widget redisplay = redisplayWidget;
        boolean applied = redisplay == null || redisplay.apply();
        // A frame/status redraw can occur while JLine has the cursor hidden.
        writeTerminal(ESC + "?25h");
        return applied;
    }

    private boolean redrawReaderFramePreservingInput(Object binding) {
        attachmentLock.readLock().lock();
        try {
            if (binding == null || binding != readerBinding || !isCurrentAttachment()) return false;
            // Transcript frames never touch the dedicated input pane.
            renderFrame(true);
            return true;
        } finally {
            attachmentLock.readLock().unlock();
        }
    }

    private boolean isCurrentAttachment() {
        RedrawAttachment expected = REDRAW_ATTACHMENT.get();
        return started && terminal != null
                && (expected == null || (expected.owner() == this
                && expected.version() == attachmentVersion));
    }

    /** Coalesced redraw entry point used by status/activity listeners. */
    public void requestRedraw() {
        if (!started || terminal == null) return;
        redrawDirty.set(true);
        if (!redrawQueued.compareAndSet(false, true)) return;
        try {
            long version = attachmentVersion;
            redrawExecutor.execute(() -> runAsyncRedraw(version));
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            redrawQueued.set(false);
        }
    }

    private void requestRedrawWithInput() {
        inputRedisplayRequested.set(true);
        requestRedraw();
    }

    private void runAsyncRedraw(long version) {
        REDRAW_ATTACHMENT.set(new RedrawAttachment(this, version));
        try {
            long remaining = MIN_ASYNC_FRAME_NANOS
                    - (System.nanoTime() - lastFrameNanos);
            if (remaining > 0L) LockSupport.parkNanos(remaining);
            boolean redisplayInput;
            ReaderRedrawDispatch dispatch;
            attachmentLock.readLock().lock();
            try {
                synchronized (drawLock) {
                    if (!isCurrentAttachment() || Thread.currentThread().isInterrupted()) return;
                    // Consume pending state only for this attachment, never its successor.
                    if (!redrawDirty.getAndSet(false)) return;
                    redisplayInput = inputRedisplayRequested.getAndSet(false);
                    dispatch = readerRedrawDispatch;
                }
            } finally {
                attachmentLock.readLock().unlock();
            }
            try {
                if (dispatch != null) {
                    try {
                        // callWidget acquires JLine's reader lock and checks its reading
                        // state atomically. A separate isReading() check races prompt
                        // startup/teardown and can strand a fresh prompt at column 1.
                        dispatch.reader().callWidget(redisplayInput
                                ? dispatch.input() : dispatch.frame());
                    } catch (IllegalStateException notReading) {
                        // Never clear after releasing JLine's reader lock: the next
                        // readLine may already be painting. Modal/command transitions
                        // perform their destructive clear synchronously between reads.
                        if (started) renderFrame(true);
                    }
                } else if (started) {
                    renderFrame();
                }
            } catch (RuntimeException ignored) {
                // Prompt teardown can race a background diagnostic/status frame.
                if (started && lineReader == null) renderFrame();
            }
        } finally {
            REDRAW_ATTACHMENT.remove();
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
        window.add("╭─ " + (title == null || title.isBlank() ? "Kompile" : title)
                + " · PgUp/PgDn scroll ─╮");
        if (lines != null) {
            for (String line : lines) {
                window.add("│ " + (line == null ? "" : line) + " │");
            }
        }
        // Leave the terminal's final column unused, just like normal visual rows.
        window.add("╰" + "─".repeat(Math.max(1, Math.min(120, terminalWidth - 3))) + "╯");
        synchronized (drawLock) {
            clearTranscriptSelectionLocked();
            if (!temporaryWindowActive) {
                savedContentViewKey = contentViewKey;
                savedContentViewTitle = contentViewTitle;
                savedContentViewLines = contentViewLines;
                savedContentScrollOffset = contentScrollOffset;
                savedContentViewPinsHeader = contentViewPinsHeader;
            }
            // A new page replaces its predecessor, including command output
            // (OAuth instructions, prompts, errors). Ordinary frame redraws keep
            // this page's output; only an explicit page transition discards it.
            temporaryCommandOutputBlocks.clear();
            temporaryWindowActive = true;
            temporaryWindowTitle = title == null ? "" : title;
            temporaryWindowLines = List.copyOf(window);
            contentViewKey = "__temporary__";
            contentViewTitle = temporaryWindowTitle;
            contentViewLines = temporaryWindowDisplayLines();
            // A picker is a document, not a streaming transcript: always begin at
            // its first option and keep that position through background redraws.
            contentScrollOffset = 0;
            contentViewPinsHeader = true;
            // The previous JLine prompt belongs to the readLine call that just
            // finished. Clear its row before the picker owns the input anchor.
            replaceScrollRegion(contentViewLines, contentViewPinsHeader, true);
        }
        if (!started) {
            window.forEach(System.out::println);
        }
    }

    public void updateTemporaryWindow(String title, List<String> lines) {
        showTemporaryWindow(title, lines);
    }

    private List<String> temporaryWindowDisplayLines() {
        if (temporaryCommandOutputBlocks.isEmpty()) return temporaryWindowLines;
        List<String> display = new ArrayList<>(temporaryWindowLines);
        int insertion = Math.max(0, display.size() - 1);
        for (String output : temporaryCommandOutputBlocks.values()) {
            for (String line : splitLines(output)) {
                display.add(insertion++, "│ " + (line == null ? "" : line) + " │");
            }
        }
        return List.copyOf(display);
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
            temporaryCommandOutputBlocks.clear();
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
        attachTerminal(terminal);
    }

    /**
     * Attach a terminal to this retained chat surface. Call on the input owner
     * between readLine calls, after attachLineReader when using JLine. Repeated
     * attachment to the same terminal is a no-op; moving requires detach first.
     * Does not take ownership of closing the supplied terminal.
     */
    public void attachTerminal(Terminal terminal) {
        java.util.Objects.requireNonNull(terminal, "terminal");
        attachmentLock.writeLock().lock();
        try {
            if (stopped) throw new IllegalStateException("Chat surface is stopped");
            if (this.terminal == terminal) return;
            if (this.terminal != null) {
                throw new IllegalStateException("Detach the current terminal first");
            }
            synchronized (drawLock) {
                attachTerminalLocked(terminal);
            }
        } finally {
            attachmentLock.writeLock().unlock();
        }
    }

    private void attachTerminalLocked(Terminal terminal) {
        if (!renderer.isAnsiEnabled()) return;
        boolean firstStart = !started;
        this.terminal = terminal;
        attachmentVersion++;
        updateTerminalSize(terminal.getSize());
        // Recalculate reserved rows now that we have real terminal dimensions
        recalcReservedMiddleRows(reservedRowsCalculator);

        if (terminalHeight < 12) {
            this.terminal = null;
            return; // Too small for top+bottom bars; may retry with a larger terminal.
        }

        started = true;
        topBar.setTerminalWidth(terminalWidth);

        // Establish the complete layout synchronously before readLine can paint
        // its first prompt. Leaving this to the queued status redraw races prompt
        // startup and can place "kompile>" on the cleared screen's home row.
        synchronized (drawLock) {
            writeTerminal(ESC + "2J" + ESC + "H"); // clear + home
            statusBar.start(terminal);
            redrawDirty.set(false); // the frame below includes the initial status state
            renderFrame();
        }

        // Keep JLine's WINCH handler installed and independently observe the
        // terminal geometry. Some native-image and nested-terminal runtimes do
        // not deliver WINCH, but their Terminal size still changes.
        if (firstStart) {
            resizeExecutor.scheduleWithFixedDelay(
                    this::pollForTerminalResize,
                    RESIZE_POLL_MILLIS,
                    RESIZE_POLL_MILLIS,
                    TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Release only the display, retaining transcript, activity view, viewport,
     * queue and background work. No terminal close or session shutdown occurs.
     * The caller must stop readLine before detaching and retain its reader/draft.
     * Static ChatCompleter routing is NOT switched by this surface-level method.
     */
    public void detachTerminal() {
        attachmentLock.writeLock().lock();
        try {
            synchronized (drawLock) {
                // A hidden surface cannot receive mouse release. Preserve the highlight,
                // but invalidate delayed drag callbacks so its viewport stays put.
                selectionDragging = false;
                selectionAutoScrollScheduled = false;
                selectionVersion++;
                if (terminal == null) {
                    detachLineReaderLocked();
                    return;
                }
                writeTerminal(ESC + "r");
                terminal = null;
                attachmentVersion++;
                // Keep the last input-pane geometry for detached viewport updates.
                detachLineReaderLocked();
                statusBar.detachTerminal();
            }
        } finally {
            attachmentLock.writeLock().unlock();
        }
    }

    public boolean isTerminalAttached() {
        return terminal != null;
    }

    /**
     * Refresh every layout layer after the terminal geometry changes.
     */
    public void handleResize() {
        Terminal active;
        long version;
        attachmentLock.readLock().lock();
        try {
            if (!started || terminal == null) return;
            active = terminal;
            version = attachmentVersion;
        } finally {
            attachmentLock.readLock().unlock();
        }
        // Terminal I/O may block. Do not prevent switching while sampling geometry.
        Size size = active.getSize();
        attachmentLock.writeLock().lock();
        try {
            if (!started || terminal != active || attachmentVersion != version) return;
            synchronized (drawLock) {
                if (!updateTerminalSize(size)) return;
                topBar.setTerminalWidth(terminalWidth);
                recalcReservedMiddleRows(reservedRowsCalculator);
                statusBar.syncTerminalSize();
                requestRedrawWithInput();
            }
            fireResizeListeners();
        } finally {
            attachmentLock.writeLock().unlock();
        }
    }

    /**
     * Stop the TUI: reset scroll regions, clear bars, stop refresh threads.
     */
    public void stop() {
        attachmentLock.writeLock().lock();
        try {
            if (stopped) return;
            stopped = true;
            started = false;
            statusBar.stop();
            detachLineReader();
            resizeExecutor.shutdownNow();
            redrawExecutor.shutdownNow();
            synchronized (drawLock) {
                clearTranscriptSelectionLocked();
                // Only an attached surface may clear terminal rows on final close.
                if (terminal != null) {
                    terminal.writer().print(ESC + "r" + ESC + "2J" + ESC + "H");
                    terminal.writer().flush();
                }
                terminal = null;
                attachmentVersion++;
            }
        } finally {
            attachmentLock.writeLock().unlock();
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
        if (!started) return;
        synchronized (drawLock) {
            writeTerminal(scrollRegionSequence()
                    + renderContentRegion(contentViewLines, contentViewPinsHeader, false)
                    + renderScrollToBottomControl());
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
            List<String> previousContentLines = contentViewLines;
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
                if (!selectionStableAcross(
                        previousContentLines, false, contentViewLines, false)) {
                    clearTranscriptSelectionLocked();
                }
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
            if (temporaryWindowActive) return;
            clearTranscriptSelectionLocked();
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
     * Refresh the currently selected activity without snapping a reader back to
     * the tail. Updates for any other key are stale and are ignored; explicit
     * navigation switches views through {@link #showActivityView}.
     */
    public void updateActivityView(String key, String title, String content) {
        if (key == null || key.isBlank()) return;
        List<String> lines = new ArrayList<>();
        lines.add("── " + (title == null || title.isBlank() ? "Activity" : title) + " ──");
        lines.addAll(splitLines(content));
        synchronized (drawLock) {
            if (temporaryWindowActive || !key.equals(contentViewKey)) return;
            if (!selectionStableAcross(contentViewLines, true, lines, true)) {
                clearTranscriptSelectionLocked();
            }
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
        showMainViewIf(() -> true);
    }

    /**
     * Restore Main only if the caller's selection is still current. The guard is
     * evaluated under the draw lock so a delayed refresh cannot overwrite newer
     * explicit navigation in either direction.
     */
    public void showMainViewIf(BooleanSupplier stillSelected) {
        synchronized (drawLock) {
            // Opening a picker and a delayed activity refresh compete for this
            // same lock. An ownership check before acquiring it is not sufficient.
            if (temporaryWindowActive) return;
            if (stillSelected != null && !stillSelected.getAsBoolean()) return;
            clearTranscriptSelectionLocked();
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
                    contentScrollOffset + (temporaryWindowActive ? -deltaLines : deltaLines),
                    contentViewLines, contentViewPinsHeader);
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
            int top = temporaryWindowActive ? 0
                    : clampScrollOffset(Integer.MAX_VALUE, contentViewLines, contentViewPinsHeader);
            if (contentScrollOffset == top) return false;
            contentScrollOffset = top;
            replaceScrollRegion(contentViewLines, contentViewPinsHeader);
            return true;
        }
    }

    public boolean scrollToBottom() {
        synchronized (drawLock) {
            int bottom = temporaryWindowActive
                    ? clampScrollOffset(Integer.MAX_VALUE, contentViewLines, contentViewPinsHeader) : 0;
            if (contentScrollOffset == bottom) return false;
            contentScrollOffset = bottom;
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
        boolean trimmed = false;
        while (mainTranscriptLineCount > MAX_MAIN_TRANSCRIPT_LINES
                && !mainTranscriptEntries.isEmpty()) {
            trimmed = true;
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
        if (trimmed && selectionBelongsToActiveView()) clearTranscriptSelectionLocked();
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
        int bottom = transcriptScrollRegionBottom();
        writeTerminal(ESC + scrollTop() + ";" + bottom + "r"
                + ESC + bottom + ";1H" + ESC + "2K" + line + '\n'
                + (lineReader == null
                        ? ESC + inputAnchorRow() + ";1H"
                        : activeInputRegionSequence()));
    }

    /** Emit one complete cursor-addressed frame through the process terminal stream. */
    private void renderFrame() {
        renderFrame(false, false);
    }

    private void renderFrame(boolean preserveInputCursor) {
        renderFrame(preserveInputCursor, false);
    }

    private void renderFrame(boolean preserveInputCursor, boolean clearInputRows) {
        if (!started) return;
        synchronized (drawLock) {
            if (!isCurrentAttachment()) return;
            StringBuilder frame = new StringBuilder();
            if (preserveInputCursor) {
                frame.append('\033').append('7').append(ESC).append("?25l");
            }
            frame.append(scrollRegionSequence());
            frame.append(topBar.render(terminalWidth));
            frame.append(renderDashboardRegion());
            frame.append(renderContentRegion(
                    contentViewLines, contentViewPinsHeader, clearInputRows));
            frame.append(renderScrollToBottomControl());
            frame.append(renderInputSeparator());
            frame.append(renderQueueRegion());
            frame.append(statusBar.render(terminalHeight, terminalWidth));
            frame.append(ESC).append(inputAnchorRow()).append(";1H");
            if (preserveInputCursor) {
                frame.append('\033').append('8').append(ESC).append("?25h");
            } else if (clearInputRows) {
                frame.append(ESC).append("?25h");
            }
            writeTerminal(frame.toString());
            lastFrameNanos = System.nanoTime();
        }
    }

    private String scrollRegionSequence() {
        if (lineReader != null) return activeInputRegionSequence();
        int top = scrollTop();
        int bottom = scrollBottom();
        if (bottom <= top + 2) return "";
        return ESC + top + ";" + bottom + "r" + ESC + bottom + ";1H";
    }

    /** Keep all JLine line insertion/deletion and overflow inside its own pane. */
    private String activeInputRegionSequence() {
        int top = inputTop();
        int bottom = scrollBottom();
        if (bottom <= top) return ESC + top + ";1H";
        return ESC + top + ";" + bottom + "r" + ESC + top + ";1H";
    }

    private int transcriptScrollRegionBottom() {
        return lineReader == null ? scrollBottom() : transcriptBottom();
    }

    private int inputAnchorRow() {
        return lineReader == null ? scrollBottom() : inputTop();
    }

    private String renderInputSeparator() {
        if (lineReader == null || inputSeparatorRow() >= inputTop()) return "";
        int width = Math.max(1, (terminalWidth > 0 ? terminalWidth : 80) - 1);
        return ESC + inputSeparatorRow() + ";1H" + ESC + "2K"
                + DIM + "─".repeat(width) + RESET;
    }

    /** Paint the dashboard into rows permanently excluded from the transcript. */
    private String renderDashboardRegion() {
        DashboardSnapshot snapshot = dashboard;
        int rows = getDashboardRegionRows();
        if (!snapshot.visible() || rows <= 0) return "";

        int width = Math.max(8, terminalWidth > 0 ? terminalWidth : 80);
        int top = TopBar.TOP_HEIGHT + 1;
        StringBuilder frame = new StringBuilder();
        for (int row = top; row < top + rows; row++) {
            frame.append(ESC).append(row).append(";1H").append(ESC).append("2K");
        }

        String heading = "  " + snapshot.title();
        frame.append(ESC).append(top).append(";1H")
                .append(BOLD).append(CYAN).append(INVERSE)
                .append(truncateDashboardLine(heading, width - 1))
                .append(RESET);
        int contentRows = rows - 1;
        List<String> visibleLines = dashboardContentLines(snapshot.lines(), contentRows);
        for (int index = 0; index < visibleLines.size() && index < contentRows; index++) {
            String line = "  " + visibleLines.get(index);
            frame.append(ESC).append(top + index + 1).append(";1H")
                    .append(truncateDashboardLine(line, width - 1));
        }
        return frame.toString();
    }

    static List<String> dashboardContentLines(List<String> lines, int contentRows) {
        if (lines == null || lines.isEmpty() || contentRows <= 0) return List.of();
        if (lines.size() <= contentRows) return List.copyOf(lines);
        if (contentRows == 1) return List.of(lines.get(lines.size() - 1));

        int leadingRows = Math.max(0, contentRows - 2);
        List<String> visible = new ArrayList<>(contentRows);
        visible.addAll(lines.subList(0, leadingRows));
        visible.add(lines.get(lines.size() - 1));
        int hidden = lines.size() - visible.size();
        visible.add("… " + hidden + " more details");
        return List.copyOf(visible);
    }

    private String truncateDashboardLine(String value, int maxColumns) {
        AttributedString text = AttributedString.fromAnsi(value == null ? "" : value);
        if (text.columnLength() <= maxColumns) return toAnsi(text);
        int bodyColumns = Math.max(0, maxColumns - 1);
        return toAnsi(text.columnSubSequence(0, bodyColumns)) + "…";
    }

    private String renderContentRegion(List<String> lines, boolean preserveHeader,
                                       boolean clearInputRow) {
        int top = scrollTop();
        int regionBottom = transcriptScrollRegionBottom();
        int contentBottom = transcriptBottom();
        StringBuilder frame = new StringBuilder();
        frame.append(ESC).append(top).append(';').append(regionBottom).append('r');
        for (int row = top; row <= contentBottom; row++) {
            frame.append(ESC).append(row).append(";1H").append(ESC).append("2K");
        }
        List<String> visible = visibleContentLines(lines, preserveHeader);
        int row = top;
        for (int i = 0; i < visible.size() && row <= contentBottom; i++, row++) {
            frame.append(ESC).append(row).append(";1H").append(visible.get(i));
        }
        if (clearInputRow) {
            for (int inputRow = inputTop(); inputRow <= scrollBottom(); inputRow++) {
                frame.append(ESC).append(inputRow).append(";1H").append(ESC).append("2K");
            }
        }
        if (lineReader != null) {
            frame.append(activeInputRegionSequence());
        } else {
            frame.append(ESC).append(inputAnchorRow()).append(";1H");
        }
        return frame.toString();
    }

    /** Paint a floating bottom-center shortcut over the scrolled transcript. */
    private String renderScrollToBottomControl() {
        if (!isScrollToBottomControlVisible()) return "";
        return ESC + (scrollToBottomControlY() + 1) + ";"
                + (scrollToBottomControlX() + 1) + "H"
                + BOLD + CYAN + INVERSE + SCROLL_TO_BOTTOM_CONTROL + RESET;
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
            if (clearInputRow) {
                // Modal transitions are invoked between nested readLine calls on
                // the dispatch thread. Complete the destructive clear now so it
                // cannot race the next prompt after JLine releases its lock.
                renderFrame(false, true);
            } else {
                requestRedraw();
            }
            return;
        }
        writeTerminal(renderContentRegion(lines, preserveHeader, clearInputRow)
                + renderScrollToBottomControl());
    }

    private List<String> visibleContentLines(List<String> lines, boolean preserveHeader) {
        VisibleVisualRows visible = visibleVisualRows(lines, preserveHeader);
        if (visible.rows().isEmpty()) return List.of();
        SelectionRange selection = selectionRangeLocked(visible.allRows());
        List<String> rendered = new ArrayList<>(visible.rows().size());
        for (int i = 0; i < visible.rows().size(); i++) {
            rendered.add(renderVisualRow(
                    visible.rows().get(i), visible.absoluteIndexes().get(i), selection));
        }
        return List.copyOf(rendered);
    }

    private VisibleVisualRows visibleVisualRows(List<String> lines, boolean preserveHeader) {
        int capacity = transcriptCapacity();
        List<VisualRow> allRows = selectableVisualRows(lines, preserveHeader);
        if (allRows.isEmpty()) return new VisibleVisualRows(allRows, List.of(), List.of());
        int offset = clampScrollOffset(contentScrollOffset, lines, preserveHeader);
        if (preserveHeader && capacity > 1) {
            int bodyCapacity = capacity - 1;
            int bodySize = allRows.size() - 1;
            int end = temporaryWindowActive ? Math.min(bodySize, offset + bodyCapacity)
                    : Math.max(0, bodySize - offset);
            int start = temporaryWindowActive ? offset : Math.max(0, end - bodyCapacity);
            List<VisualRow> visible = new ArrayList<>(capacity);
            List<Integer> indexes = new ArrayList<>(capacity);
            visible.add(allRows.get(0));
            indexes.add(0);
            for (int index = start; index < end; index++) {
                visible.add(allRows.get(index + 1));
                indexes.add(index + 1);
            }
            return new VisibleVisualRows(allRows, List.copyOf(visible), List.copyOf(indexes));
        }
        int end = temporaryWindowActive ? Math.min(allRows.size(), offset + capacity)
                : Math.max(0, allRows.size() - offset);
        int start = temporaryWindowActive ? offset : Math.max(0, end - capacity);
        List<Integer> indexes = new ArrayList<>(end - start);
        for (int index = start; index < end; index++) indexes.add(index);
        return new VisibleVisualRows(
                allRows, List.copyOf(allRows.subList(start, end)), List.copyOf(indexes));
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
        List<VisualRow> visualRows = visualRowsWithMetadata(lines, 0);
        if (visualRows.isEmpty()) return List.of();
        List<String> rows = new ArrayList<>(visualRows.size());
        for (VisualRow row : visualRows) rows.add(toAnsi(row.text()));
        return rows;
    }

    private List<VisualRow> visualRowsWithMetadata(List<String> lines, int logicalLineOffset) {
        if (lines == null || lines.isEmpty()) return List.of();
        int width = Math.max(1, (terminalWidth > 0 ? terminalWidth : 80) - 1);
        List<VisualRow> rows = new ArrayList<>();
        for (int logicalLine = 0; logicalLine < lines.size(); logicalLine++) {
            String line = lines.get(logicalLine);
            AttributedString attributed = AttributedString.fromAnsi(line == null ? "" : line);
            List<AttributedString> wrapped = attributed.columnSplitLength(width);
            if (wrapped.isEmpty()) {
                rows.add(new VisualRow(AttributedString.EMPTY, logicalLineOffset + logicalLine));
                continue;
            }
            for (AttributedString row : wrapped) {
                rows.add(new VisualRow(row, logicalLineOffset + logicalLine));
            }
        }
        return List.copyOf(rows);
    }

    private List<VisualRow> selectableVisualRows(List<String> lines, boolean preserveHeader) {
        if (lines == null || lines.isEmpty()) return List.of();
        if (preserveHeader && transcriptCapacity() > 1) {
            List<VisualRow> headerRows = visualRowsWithMetadata(List.of(lines.get(0)), 0);
            List<VisualRow> bodyRows = visualRowsWithMetadata(lines.subList(1, lines.size()), 1);
            List<VisualRow> rows = new ArrayList<>(bodyRows.size() + 1);
            rows.add(headerRows.isEmpty()
                    ? new VisualRow(AttributedString.EMPTY, 0) : headerRows.get(0));
            rows.addAll(bodyRows);
            return List.copyOf(rows);
        }
        return visualRowsWithMetadata(lines, 0);
    }

    private String renderVisualRow(VisualRow row, int rowIndex, SelectionRange selection) {
        AttributedString text = row.text();
        if (selection != null
                && rowIndex >= selection.start().row()
                && rowIndex <= selection.end().row()) {
            int start = rowIndex == selection.start().row() ? selection.start().column() : 0;
            int end = rowIndex == selection.end().row()
                    ? selection.end().column() : text.columnLength();
            text = inverseColumns(text, start, end);
        }
        return toAnsi(text);
    }

    private AttributedString inverseColumns(AttributedString text, int start, int end) {
        int length = text.columnLength();
        int from = Math.max(0, Math.min(start, length));
        int to = Math.max(from, Math.min(end, length));
        if (from == to) return text;
        AttributedStringBuilder highlighted = new AttributedStringBuilder(text.length());
        highlighted.append(text.columnSubSequence(0, from));
        AttributedString selected = text.columnSubSequence(from, to);
        for (int i = 0; i < selected.length(); i++) {
            highlighted.append(selected.subSequence(i, i + 1), selected.styleAt(i).inverse());
        }
        highlighted.append(text.columnSubSequence(to, length));
        return highlighted.toAttributedString();
    }

    private String toAnsi(AttributedString text) {
        Terminal active = terminal;
        return active == null ? text.toAnsi() : text.toAnsi(active);
    }

    private SelectionPoint selectionPointAt(int x, int y, boolean clampToTranscript) {
        return selectionPointAt(x, y, clampToTranscript, false);
    }

    private SelectionPoint selectionPointAt(
            int x, int y, boolean clampToTranscript, boolean preferBodyAtPinnedTop) {
        VisibleVisualRows visible = visibleVisualRows(contentViewLines, contentViewPinsHeader);
        if (visible.rows().isEmpty()) return null;
        int top = scrollTop() - 1;
        int bottom = Math.min(transcriptBottomY(), top + visible.rows().size() - 1);
        if (!clampToTranscript && (y < top || y > bottom)) return null;
        int rowOnScreen = Math.max(top, Math.min(y, bottom)) - top;
        if (preferBodyAtPinnedTop && contentViewPinsHeader
                && y <= top && visible.rows().size() > 1) {
            rowOnScreen = 1;
        }
        VisualRow row = visible.rows().get(rowOnScreen);
        int column = Math.max(0, Math.min(x, row.text().columnLength()));
        return new SelectionPoint(visible.absoluteIndexes().get(rowOnScreen), column);
    }

    private boolean autoScrollSelectionAt(int y) {
        int maximum = clampScrollOffset(
                Integer.MAX_VALUE, contentViewLines, contentViewPinsHeader);
        int next = contentScrollOffset;
        if (y <= scrollTop() - 1 && contentScrollOffset < maximum) {
            next++;
        } else if (y >= transcriptBottomY() && contentScrollOffset > 0) {
            next--;
        }
        if (next == contentScrollOffset) return false;
        contentScrollOffset = next;
        return true;
    }

    private void scheduleSelectionAutoScrollLocked() {
        if (selectionAutoScrollScheduled || !selectionDragging || !selectionAtScrollableEdge()) return;
        selectionAutoScrollScheduled = true;
        long version = selectionVersion;
        CompletableFuture.delayedExecutor(75, TimeUnit.MILLISECONDS).execute(() -> {
            synchronized (drawLock) {
                if (version != selectionVersion) return;
                selectionAutoScrollScheduled = false;
                if (!selectionDragging
                        || !selectionBelongsToActiveView()) {
                    return;
                }
                boolean changed = autoScrollSelectionAt(selectionPointerY);
                SelectionPoint point = selectionPointAt(
                        selectionPointerX, selectionPointerY, true, true);
                if (point != null && !point.equals(selectionActiveCell)) {
                    selectionActiveCell = point;
                    changed = true;
                }
                if (changed) replaceScrollRegion(contentViewLines, contentViewPinsHeader);
                if (changed) scheduleSelectionAutoScrollLocked();
            }
        });
    }

    private boolean selectionAtScrollableEdge() {
        int maximum = clampScrollOffset(
                Integer.MAX_VALUE, contentViewLines, contentViewPinsHeader);
        return (selectionPointerY <= scrollTop() - 1 && contentScrollOffset < maximum)
                || (selectionPointerY >= transcriptBottomY() && contentScrollOffset > 0);
    }

    private int transcriptBottomY() {
        return transcriptBottom() - 1;
    }

    private boolean selectionBelongsToActiveView() {
        return selectionViewKey != null && selectionViewKey.equals(contentViewKey);
    }

    private SelectionRange selectionRangeLocked() {
        return selectionRangeLocked(selectableVisualRows(contentViewLines, contentViewPinsHeader));
    }

    private SelectionRange selectionRangeLocked(List<VisualRow> rows) {
        if (!selectionBelongsToActiveView()
                || selectionAnchorCell == null || selectionActiveCell == null
                || rows.isEmpty() || selectionAnchorCell.equals(selectionActiveCell)) {
            return null;
        }
        boolean forward = comparePoints(selectionAnchorCell, selectionActiveCell) < 0;
        SelectionPoint firstCell = forward ? selectionAnchorCell : selectionActiveCell;
        SelectionPoint lastCell = forward ? selectionActiveCell : selectionAnchorCell;
        if (firstCell.row() < 0 || lastCell.row() >= rows.size()) return null;
        AttributedString firstRow = rows.get(firstCell.row()).text();
        AttributedString lastRow = rows.get(lastCell.row()).text();
        int startColumn = glyphStartColumn(firstRow, firstCell.column());
        int endColumn = glyphEndColumn(lastRow, lastCell.column());
        SelectionPoint start = new SelectionPoint(firstCell.row(), startColumn);
        SelectionPoint end = new SelectionPoint(lastCell.row(), endColumn);
        return comparePoints(start, end) < 0 ? new SelectionRange(start, end) : null;
    }

    private static int comparePoints(SelectionPoint left, SelectionPoint right) {
        int row = Integer.compare(left.row(), right.row());
        return row != 0 ? row : Integer.compare(left.column(), right.column());
    }

    private static int glyphStartColumn(AttributedString text, int requestedColumn) {
        return glyphBoundary(text, requestedColumn, false);
    }

    private static int glyphEndColumn(AttributedString text, int requestedColumn) {
        return glyphBoundary(text, requestedColumn, true);
    }

    private static int glyphBoundary(
            AttributedString text, int requestedColumn, boolean afterGlyph) {
        int target = Math.max(0, Math.min(requestedColumn, text.columnLength()));
        int column = 0;
        for (int index = 0; index < text.length();) {
            int codePoint = text.codePointAt(index);
            int width = text.isHidden(index) ? 0 : Math.max(0, WCWidth.wcwidth(codePoint));
            if (width > 0 && target < column + width) {
                return afterGlyph ? column + width : column;
            }
            column += width;
            index += Character.charCount(codePoint);
        }
        return column;
    }

    private boolean selectionStableAcross(
            List<String> previousLines,
            boolean previousPreservesHeader,
            List<String> nextLines,
            boolean nextPreservesHeader) {
        if (!selectionBelongsToActiveView()
                || selectionAnchorCell == null || selectionActiveCell == null) {
            return true;
        }
        List<VisualRow> previous = selectableVisualRows(previousLines, previousPreservesHeader);
        List<VisualRow> next = selectableVisualRows(nextLines, nextPreservesHeader);
        int lastSelectedRow = Math.max(selectionAnchorCell.row(), selectionActiveCell.row());
        if (lastSelectedRow >= previous.size() || lastSelectedRow >= next.size()) return false;
        for (int row = 0; row <= lastSelectedRow; row++) {
            VisualRow before = previous.get(row);
            VisualRow after = next.get(row);
            if (before.logicalLine() != after.logicalLine()
                    || !before.text().equals(after.text())) {
                return false;
            }
        }
        return true;
    }

    private boolean clearTranscriptSelectionLocked() {
        boolean changed = selectionAnchorCell != null || selectionActiveCell != null
                || selectionDragging || selectionViewKey != null;
        selectionAnchorCell = null;
        selectionActiveCell = null;
        selectionDragging = false;
        selectionViewKey = null;
        selectionAutoScrollScheduled = false;
        selectionVersion++;
        return changed;
    }

    private int transcriptCapacity() {
        return Math.max(1, transcriptBottom() - scrollTop() + 1);
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
        synchronized (drawLock) {
            if (!isCurrentAttachment()) return;
            terminal.writer().print(text);
            terminal.writer().flush();
        }
    }

    private void setScrollRegion() {
        int top = scrollTop();
        int bottom = scrollBottom();
        if (bottom <= top + 2) return; // Too small
        writeTerminal(scrollRegionSequence());
    }

    private void pollForTerminalResize() {
        if (!started) return;
        try {
            handleResize();
        } catch (RuntimeException ignored) {
            // Terminal teardown can race the final scheduled size check.
        }
    }

    // Caller holds the attachment write lock and drawLock.
    private boolean updateTerminalSize(Size size) {
        int previousHeight = terminalHeight;
        int previousWidth = terminalWidth;
        terminalHeight = size.getRows();
        terminalWidth = size.getColumns();
        if (terminalHeight <= 0) terminalHeight = 24;
        if (terminalWidth <= 0) terminalWidth = 80;
        if (previousWidth > 0 && previousWidth != terminalWidth) {
            synchronized (drawLock) {
                clearTranscriptSelectionLocked();
            }
        }
        return previousHeight != terminalHeight || previousWidth != terminalWidth;
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
