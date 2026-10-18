package ai.kompile.cli.main.chat.tui;

import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.AbstractTerminal;
import org.jline.utils.Display;
import org.jline.utils.AttributedString;
import org.jline.utils.InfoCmp.Capability;

import java.util.function.IntSupplier;
import java.util.function.Consumer;

/** JLine editor whose display viewport matches the TUI's restricted input pane. */
public final class InputPaneLineReader extends LineReaderImpl {
    private volatile IntSupplier inputRows;
    private volatile IntSupplier inputColumns;
    private volatile Runnable beforeViewportChange;
    private volatile Runnable afterRedisplay;
    private volatile Consumer<String> transcriptOutput;
    private int displayRows;
    private int displayColumns;
    private Display paneDisplay;

    /** Display-only capability facade: the physical right margin is not the pane's edge. */
    private static final class PaneDisplayTerminal extends AbstractTerminal {
        private final Terminal delegate;
        PaneDisplayTerminal(Terminal delegate) throws java.io.IOException {
            super(delegate.getName(), delegate.getType(), delegate.encoding(), SignalHandler.SIG_DFL);
            this.delegate = delegate;
        }
        @Override public boolean getBooleanCapability(Capability capability) {
            return capability != Capability.auto_right_margin && (delegate == null
                    ? super.getBooleanCapability(capability) : delegate.getBooleanCapability(capability));
        }
        @Override public Integer getNumericCapability(Capability capability) {
            return delegate == null ? super.getNumericCapability(capability) : delegate.getNumericCapability(capability);
        }
        @Override public String getStringCapability(Capability capability) {
            return delegate == null ? super.getStringCapability(capability) : delegate.getStringCapability(capability);
        }
        @Override public org.jline.utils.NonBlockingReader reader() { return delegate.reader(); }
        @Override public java.io.PrintWriter writer() { return delegate.writer(); }
        @Override public java.io.InputStream input() { return delegate.input(); }
        @Override public java.io.OutputStream output() { return delegate.output(); }
        @Override public org.jline.terminal.Attributes getAttributes() { return delegate.getAttributes(); }
        @Override public void setAttributes(org.jline.terminal.Attributes attributes) { delegate.setAttributes(attributes); }
        @Override public org.jline.terminal.Size getSize() { return delegate.getSize(); }
        @Override public void setSize(org.jline.terminal.Sized size) { delegate.setSize(size); }
        @Override public org.jline.terminal.spi.TerminalProvider getProvider() { return null; }
        @Override public org.jline.terminal.spi.SystemStream getSystemStream() { return null; }
    }

    public InputPaneLineReader(Terminal terminal) {
        super(terminal, terminal.getName(), null);
    }

    /** Attach/detach the pane without changing the physical terminal's dimensions. */
    void setInputRows(IntSupplier rows) {
        inputRows = rows;
    }

    void setInputColumns(IntSupplier columns) {
        inputColumns = columns;
    }

    /** Erase and anchor the new pane before replacing JLine's display cache. */
    void setBeforeViewportChange(Runnable callback) {
        beforeViewportChange = callback;
        displayRows = 0;
        displayColumns = 0;
    }

    void setAfterRedisplay(Runnable callback) {
        afterRedisplay = callback;
    }

    void setTranscriptOutput(Consumer<String> output) {
        transcriptOutput = output;
    }

    @Override
    public void printAbove(String text) {
        Consumer<String> output = transcriptOutput;
        if (output == null) {
            super.printAbove(text);
        } else {
            // JLine's scroll region IS the editor. Even a diagnostic/fallback
            // producer must go through the retained transcript, never print here.
            output.accept(text == null ? "" : text);
        }
    }

    @Override
    public void printAbove(AttributedString text) {
        Consumer<String> output = transcriptOutput;
        if (output == null) super.printAbove(text);
        else output.accept(text == null ? "" : text.toAnsi(terminal));
    }

    @Override
    protected void redisplay(boolean flush) {
        lock.lock();
        try {
            if (display != null) {
                // Also pick up width changes detected by the TUI's resize polling,
                // which need not be accompanied by a JLine WINCH signal.
                size.copy(terminal.getBufferSize());
                IntSupplier rows = inputRows;
                if (rows != null) {
                    // readLine startup and WINCH copy the full terminal size into JLine.
                    // Reapply the viewport before every display diff, including normal
                    // paste/typing redisplays that never invoke a REDISPLAY widget.
                    size.setRows(Math.max(1, rows.getAsInt()));
                }
                IntSupplier columns = inputColumns;
                int physicalColumns = size.getColumns();
                if (columns != null) size.setColumns(Math.max(1, columns.getAsInt()));
                Runnable prepareViewport = beforeViewportChange;
                boolean viewportChanged = prepareViewport != null
                        && (size.getRows() != displayRows || size.getColumns() != displayColumns);
                if (viewportChanged) {
                    // /ui updates the suppliers before its async frame runs. A key
                    // can redisplay first, so never apply the new width at the old
                    // physical cursor or diff against the previous pane's cells.
                    prepareViewport.run();
                }
                boolean narrow = size.getColumns() < physicalColumns;
                if (viewportChanged || (narrow ? display != paneDisplay : display == paneDisplay)) {
                    boolean delayLineWrap = display.delayLineWrap();
                    try {
                        display = new Display(narrow ? new PaneDisplayTerminal(terminal) : terminal, false);
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                    display.setDelayLineWrap(delayLineWrap);
                    paneDisplay = narrow ? display : null;
                }
                display.resize(size.getRows(), size.getColumns());
                displayRows = size.getRows();
                displayColumns = size.getColumns();
            }
            super.redisplay(flush);
            // JLine erases/inserts complete physical rows even with a narrower
            // viewport. Restore the adjacent pane after every edit, not just a
            // background frame, while still holding the reader lock.
            Runnable callback = afterRedisplay;
            if (callback != null) callback.run();
        } finally {
            lock.unlock();
        }
    }
}
