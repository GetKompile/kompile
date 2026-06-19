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
     * Extra rows reserved between the scroll region and the StatusBar.
     * Used by EmulatedPassthroughCommand for its input box, queue preview, etc.
     * ChatRepl leaves this at 0 (readline lives inside the scroll region).
     */
    private volatile int reservedMiddleRows = 0;

    /** Optional callback for recalculating reserved rows on resize. */
    private volatile ReservedRowsCalculator reservedRowsCalculator;

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
     * Print text into the scroll region.
     * Moves cursor to the last scroll row, prints the text, then scrolls up.
     * Thread-safe via drawLock.
     */
    public void printInScrollRegion(String text) {
        if (!started) {
            System.out.println(text);
            return;
        }
        synchronized (drawLock) {
            PrintStream out = System.out;
            // Re-establish scroll region (JLine may have reset it)
            out.printf("%s%d;%dr", ESC, scrollTop(), scrollBottom());
            // Move to last row of scroll region, print, newline triggers scroll
            out.printf("%s%d;1H%s2K%s\n", ESC, scrollBottom(), ESC, text);
            out.flush();
        }
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
}
