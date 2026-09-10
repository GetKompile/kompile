/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat;

import org.jline.reader.LineReader;
import org.jline.reader.Widget;
import org.jline.terminal.Terminal;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Retained UI routing for one standard-chat session, independent of terminal focus.
 * Bind while configuring/using ChatCompleter; capture work explicitly before handing
 * it to an executor or provider. Context is NOT inherited by child/pool threads.
 * Unbound callers keep the existing single-chat route, never the last bound session.
 *
 * <p>This is an opt-in building block, not a multi-REPL input loop. A host must capture
 * every asynchronous entry point and give only its foreground session a terminal.
 * stdout interception and transcript diagnostics are separate (TranscriptLogScope).
 * Isolated output without an installed sink is deliberately not sent to System.out.
 * Close does not cancel work or close the caller-owned terminal; callbacks already
 * in flight may finish against their original sink, never a different session.</p>
 */
public final class ChatUiSession implements AutoCloseable {
    private static final ChatUiSession LEGACY = new ChatUiSession(true);
    private static final ThreadLocal<Binding> BOUND = new ThreadLocal<>();

    private final boolean legacy;
    private volatile boolean closed;
    volatile Terminal terminalRef;
    volatile LineReader lineReaderRef;
    volatile Runnable contentRedraw;
    volatile Consumer<String> contentOutput;
    volatile Consumer<String> alertOutput;
    volatile ChatCompleter.TranscriptBlockOutput transcriptBlockOutput;
    volatile ChatCompleter.CompletionDisplay completionDisplay;
    volatile Supplier<List<String>> queueSupplier;
    volatile boolean temporaryWindowActive;
    volatile String activityLabel;
    volatile boolean activityTerminal;
    long interruptionVersion; // guarded by this session's monitor
    volatile Consumer<String> activityListener;

    public ChatUiSession() {
        this(false);
    }

    private ChatUiSession(boolean legacy) {
        this.legacy = legacy;
    }

    /** Capture this reference at construction/registration, not on a worker thread. */
    public static ChatUiSession current() {
        Binding binding = BOUND.get();
        return binding == null ? LEGACY : binding.session;
    }

    public boolean isClosed() { return closed; }
    /** Isolated owners must not open independent interactive terminals, even after close. */
    public boolean isIsolated() { return !legacy; }
    boolean usesLegacyOutput() { return legacy; }
    public String getActivity() { return closed ? null : activityLabel; }
    public boolean isActivityTerminal() { return !closed && activityTerminal; }

    public Binding bind() {
        if (closed) throw new IllegalStateException("Chat UI session is closed");
        return new Binding(this);
    }

    /** Captures remain tied to this owner after close; UI output is then suppressed. */
    public Runnable capture(Runnable task) {
        Objects.requireNonNull(task, "task");
        return () -> {
            try (Binding ignored = new Binding(this)) {
                task.run();
            }
        };
    }

    public <T> Callable<T> capture(Callable<T> task) {
        Objects.requireNonNull(task, "task");
        return () -> {
            try (Binding ignored = new Binding(this)) {
                return task.call();
            }
        };
    }

    public <T> Consumer<T> captureConsumer(Consumer<T> callback) {
        Objects.requireNonNull(callback, "callback");
        return value -> {
            try (Binding ignored = new Binding(this)) {
                callback.accept(value);
            }
        };
    }

    Widget captureWidget(Widget widget) {
        return () -> {
            if (closed) return false;
            try (Binding ignored = new Binding(this)) {
                return widget.apply();
            }
        };
    }

    /** Final disposal, not focus switching. The legacy compatibility owner is immortal. */
    @Override
    public synchronized void close() {
        if (legacy || closed) return;
        closed = true;
        try (Binding ignored = new Binding(this)) {
            ChatCompleter.clearTerminalRef(lineReaderRef);
        }
    }

    /** Lexical, thread-confined, LIFO binding; closing it never closes the session. */
    public static final class Binding implements AutoCloseable {
        private final ChatUiSession session;
        private final Binding previous = BOUND.get();
        private final Thread thread = Thread.currentThread();
        private boolean closed;

        private Binding(ChatUiSession session) {
            this.session = session;
            BOUND.set(this);
        }

        @Override
        public void close() {
            if (Thread.currentThread() != thread) {
                throw new IllegalStateException("Chat UI binding belongs to another thread");
            }
            if (closed) return;
            if (BOUND.get() != this) {
                throw new IllegalStateException("Chat UI bindings must close in reverse order");
            }
            closed = true;
            if (previous == null) BOUND.remove();
            else BOUND.set(previous);
        }
    }
}
