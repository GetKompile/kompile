/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.logs.LogPaths;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Stderr scope for one canonical Kompile transcript UUID/ID.
 *
 * <p>Every standard chat process installs this scope before model, MCP, or tool
 * initialization. Diagnostics remain visible on the terminal and are also appended to
 * {@code ~/.kompile/logs/transcripts/<transcriptId>/cli.log}. Resuming appends to the
 * same file; {@code /clear} switches the active sink to the new transcript UUID.</p>
 *
 * <p>Scopes nest as a stack. In-process launches — the resume browser re-running
 * {@code ChatCommand} or {@code EmulatedPassthroughCommand} through picocli while the
 * hosting chat REPL still holds its own scope — push a nested scope instead of
 * colliding with the parent. While nested, stderr is captured by the inner transcript;
 * when the inner scope closes, the parent's sink and transcript id are restored. This
 * is purely in-memory state, so a hard kill (Ctrl+C included) leaves nothing behind
 * on disk to clean up.</p>
 *
 * <p>{@link #openIsolated} is opt-in session ownership: it neither pushes the legacy
 * stack nor changes the global transcript property. Only explicitly {@link #bind bound}
 * or {@link #capture(Runnable) captured} work routes to that session. Unbound work keeps
 * legacy stack/terminal behavior. No context is inherited by child threads.</p>
 */
public final class TranscriptLogScope implements AutoCloseable {

    public static final String TRANSCRIPT_ID_PROPERTY = "kompile.transcript.uuid";
    public static final String TRANSCRIPT_ID_ENV = "KOMPILE_TRANSCRIPT_UUID";

    private static final Object LOCK = new Object();
    private static final Deque<State> STACK = new ArrayDeque<>();
    private static final ThreadLocal<Binding> BOUND = new ThreadLocal<>();
    /** The pre-chat terminal stream; no transcript sink tees through another sink. */
    private static PrintStream rootTerminal;
    private static DispatchOutputStream dispatcher;
    private static PrintStream dispatchErr;
    private static int activeStates;
    private static String legacyProperty;

    private final State state;
    private boolean closed;

    private TranscriptLogScope(State state) {
        this.state = state;
    }

    /**
     * Open (or re-enter) the log scope for a transcript. Failure is fatal to session
     * startup. Opening the same transcript as the currently active scope re-enters it
     * (shared reference counting); any other transcript pushes a nested scope.
     */
    public static TranscriptLogScope open(
            String transcriptId, Path workingDirectory, boolean resumed) throws IOException {
        return open(transcriptId, workingDirectory, resumed, false, null);
    }

    /**
     * Open an independently owned scope without binding the caller or changing the
     * process transcript property. Use bind() for lexical work or capture() for pools.
     * Even equal transcript IDs have independent lifetimes (and append to the same file).
     */
    public static TranscriptLogScope openIsolated(
            String transcriptId, Path workingDirectory, boolean resumed) throws IOException {
        return open(transcriptId, workingDirectory, resumed, true, null);
    }

    /**
     * Tee to an explicit diagnostic echo, normally SessionOutputRouter.sessionErr().
     * The echo must not forward to System.err/the transcript dispatcher (that would
     * recurse and log twice). It is caller-owned and remains the route after close.
     */
    public static TranscriptLogScope openIsolated(
            String transcriptId, Path workingDirectory, boolean resumed, PrintStream terminalEcho)
            throws IOException {
        return open(transcriptId, workingDirectory, resumed, true,
                Objects.requireNonNull(terminalEcho, "terminalEcho"));
    }

    private static TranscriptLogScope open(
            String transcriptId, Path workingDirectory, boolean resumed, boolean isolated,
            PrintStream terminalEcho) throws IOException {
        String requiredId = requireTranscriptId(transcriptId);
        synchronized (LOCK) {
            State top = STACK.peek();
            if (!isolated && top != null && top.transcriptId.equals(requiredId)) {
                top.references++;
                return new TranscriptLogScope(top);
            }
            State created = new State(terminalEcho != null ? terminalEcho
                    : activeStates == 0 ? System.err : rootTerminal, isolated, terminalEcho != null);
            // Open the sink before mutating any process-wide state.
            created.switchTo(requiredId, workingDirectory, resumed);
            if (activeStates == 0) {
                installDispatcher();
            } else if (!isolated && System.err != dispatchErr) {
                // A legacy TUI command may temporarily intercept stderr before launching
                // a nested chat. Bypass it for the child, then hand it back on close.
                // Never tee through the interceptor: it may forward to this dispatcher.
                created.displacedErr = System.err;
                System.setErr(dispatchErr);
            }
            activeStates++;
            created.references = 1;
            if (!isolated) {
                if (STACK.isEmpty()) legacyProperty = System.getProperty(TRANSCRIPT_ID_PROPERTY);
                STACK.push(created);
                System.setProperty(TRANSCRIPT_ID_PROPERTY, requiredId);
            }
            return new TranscriptLogScope(created);
        }
    }

    /**
     * Bind this owner on the calling thread until the returned binding closes. Bindings
     * are thread-confined and must close in reverse order. They follow this owner's
     * switchTo (/clear), never another owner; closing the owner makes them echo-only
     * (the terminal by default, or the explicit openIsolated echo).
     */
    public Binding bind() {
        synchronized (LOCK) {
            if (closed) throw new IllegalStateException("Transcript log scope is closed");
            return new Binding(this);
        }
    }

    /**
     * Capture this explicit owner, not the executor thread's context; restore in finally.
     * Captures do not retain the sink. After owner close the task still runs, but its
     * diagnostics go only to its echo and currentTranscriptId() returns null.
     */
    public Runnable capture(Runnable task) {
        Objects.requireNonNull(task, "task");
        return () -> {
            try (Binding ignored = new Binding(this)) {
                task.run();
            }
        };
    }

    /** Callable counterpart of capture(Runnable), preserving checked exceptions. */
    public <T> Callable<T> capture(Callable<T> task) {
        Objects.requireNonNull(task, "task");
        return () -> {
            try (Binding ignored = new Binding(this)) {
                return task.call();
            }
        };
    }

    static boolean isDispatchLockedByCurrentThread() { return Thread.holdsLock(LOCK); }

    /** No legacy stack lookup: absence of an explicit owner must stay dynamic. */
    static TranscriptLogScope currentExplicitScope() {
        Binding binding = BOUND.get();
        return binding == null ? null : binding.owner;
    }

    /** Also permits a closed owner or null (mask a worker's binding, use legacy stack). */
    static Binding bindCaptured(TranscriptLogScope owner) {
        return new Binding(owner);
    }

    /** Physical diagnostic echo, never the dispatching/file-writing stream. */
    static PrintStream terminalEcho() {
        synchronized (LOCK) {
            PrintStream terminal = activeStates == 0 ? System.err : rootTerminal;
            // A temporary command may restore a retired dispatcher after its host
            // closes. Echo must bypass even retired dispatchers, not just the live one.
            while (terminal instanceof DispatchPrintStream stream) terminal = stream.terminal;
            return terminal;
        }
    }

    /** Keep routing late explicit captures until the opt-in host releases its lease. */
    static Runnable retainDispatcher() {
        synchronized (LOCK) {
            if (activeStates == 0) installDispatcher();
            activeStates++;
        }
        return () -> {
            synchronized (LOCK) { releaseDispatcher(true); }
        };
    }

    private static void installDispatcher() {
        rootTerminal = System.err;
        dispatcher = new DispatchOutputStream(rootTerminal);
        dispatchErr = new DispatchPrintStream(dispatcher, rootTerminal);
        System.setErr(dispatchErr);
    }

    private static void releaseDispatcher(boolean preserveInterceptor) {
        if (--activeStates == 0) {
            // Host disposal must not stomp a temporary KompileTui interceptor. Its
            // saved dispatcher stays a terminal-only passthrough afterwards. Ordinary
            // scope disposal retains its pre-router restoration behavior.
            if (!preserveInterceptor || System.err == dispatchErr) System.setErr(rootTerminal);
            rootTerminal = null;
            dispatcher = null;
            dispatchErr = null;
        }
    }

    /** Thread-confined lexical binding; closing it never closes its owning scope. */
    public static final class Binding implements AutoCloseable {
        private final TranscriptLogScope owner;
        private final Binding previous;
        private final Thread thread = Thread.currentThread();
        private boolean closed;

        private Binding(TranscriptLogScope owner) {
            this.owner = owner;
            this.previous = BOUND.get();
            BOUND.set(this);
        }

        @Override
        public void close() {
            if (Thread.currentThread() != thread) {
                throw new IllegalStateException("Transcript binding belongs to another thread");
            }
            if (closed) return;
            if (BOUND.get() != this) {
                throw new IllegalStateException("Transcript bindings must close in reverse order");
            }
            closed = true;
            if (previous == null) BOUND.remove();
            else BOUND.set(previous);
        }
    }

    /** Switch an existing JVM chat loop to the fresh UUID created by {@code /clear}. */
    public void switchTo(String transcriptId, Path workingDirectory, boolean resumed)
            throws IOException {
        String requiredId = requireTranscriptId(transcriptId);
        try {
            synchronized (LOCK) {
                ensureOpen();
                if (!state.isolated && STACK.peek() != state) {
                    throw new IOException("Transcript log scope is no longer active");
                }
                if (!state.transcriptId.equals(requiredId)) {
                    if (state.references != 1) {
                        throw new IOException("Cannot switch a re-entered transcript log scope");
                    }
                    state.switchTo(requiredId, workingDirectory, resumed);
                    if (!state.isolated) System.setProperty(TRANSCRIPT_ID_PROPERTY, requiredId);
                }
            }
        } finally {
            SessionOutputRouter.drainPending();
        }
    }

    /** Current canonical transcript identity, including while MCP configs are being generated. */
    public static String currentTranscriptId() {
        synchronized (LOCK) {
            Binding bound = BOUND.get();
            // A stale owner must not fall through to another session or global property.
            if (bound != null && bound.owner != null) {
                return bound.owner.closed ? null : bound.owner.state.transcriptId;
            }
            State top = STACK.peek();
            if (top != null) return top.transcriptId;
            String configured = System.getProperty(TRANSCRIPT_ID_PROPERTY);
            return configured == null || configured.isBlank() ? null : configured.trim();
        }
    }

    public Path logFile() {
        synchronized (LOCK) {
            return state.logFile;
        }
    }

    @Override
    public void close() {
        try {
            synchronized (LOCK) {
                if (closed) return;
                closed = true;
                if (--state.references > 0) return;
                boolean wasTop = STACK.peek() == state;
                STACK.remove(state);
                state.closeCurrentSink(state.isolated || wasTop ? "end" : "abandoned");
                if (wasTop) {
                    String restored = STACK.isEmpty() ? legacyProperty : STACK.peek().transcriptId;
                    if (restored == null) System.clearProperty(TRANSCRIPT_ID_PROPERTY);
                    else System.setProperty(TRANSCRIPT_ID_PROPERTY, restored);
                    if (STACK.isEmpty()) legacyProperty = null;
                    if (state.displacedErr != null && System.err == dispatchErr) {
                        System.setErr(state.displacedErr);
                    }
                }
                releaseDispatcher(false);
            }
        } finally {
            SessionOutputRouter.drainPending();
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Transcript log scope is closed");
    }

    private static String requireTranscriptId(String value) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IOException("A transcript UUID/ID is required before chat logging starts");
        }
        return value.trim();
    }

    private static final class State {
        private final PrintStream rootErr;
        private final boolean isolated;
        private final boolean explicitEcho;
        private PrintStream scopedErr;
        private PrintStream displacedErr;
        private Path logFile;
        private String transcriptId = "";
        private int references;

        private State(PrintStream rootErr, boolean isolated, boolean explicitEcho) {
            this.rootErr = rootErr;
            this.isolated = isolated;
            this.explicitEcho = explicitEcho;
        }

        private void switchTo(String id, Path workingDirectory, boolean resumed)
                throws IOException {
            Path directory = LogPaths.ensureTranscriptDirectory(id).toPath();
            Path nextLog = directory.resolve("cli.log");
            appendBoundary(nextLog, resumed ? "resume" : "start", id, workingDirectory);
            OutputStream nextFile = Files.newOutputStream(nextLog,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            PrintStream nextErr = new PrintStream(
                    new TeeOutputStream(rootErr, nextFile), true, StandardCharsets.UTF_8);

            // Do not dismantle the current sink until the replacement is fully open.
            // If any operation above fails, the old transcript remains observable.
            Path previousLog = this.logFile;
            String previousId = this.transcriptId;
            PrintStream previousErr = this.scopedErr;
            this.transcriptId = id;
            this.logFile = nextLog;
            this.scopedErr = nextErr;
            closeSink(previousLog, previousId, previousErr, "scope-switch");
        }

        private void closeCurrentSink(String event) {
            Path currentLog = logFile;
            String currentId = transcriptId;
            PrintStream currentErr = scopedErr;
            scopedErr = null;
            closeSink(currentLog, currentId, currentErr, event);
        }

        private static void closeSink(
                Path sinkLog, String sinkId, PrintStream sinkErr, String event) {
            if (sinkLog != null && sinkId != null && !sinkId.isBlank()) {
                try {
                    appendBoundary(sinkLog, event, sinkId, null);
                } catch (IOException ignored) {
                    // The active PrintStream still gets a chance to flush below.
                }
            }
            if (sinkErr != null) {
                sinkErr.flush();
                sinkErr.close();
            }
        }
    }

    private static void appendBoundary(
            Path logFile, String event, String transcriptId, Path workingDirectory)
            throws IOException {
        String cwd = workingDirectory == null ? "" : " cwd="
                + workingDirectory.toAbsolutePath().normalize();
        String line = "[" + Instant.now() + "] transcript=" + transcriptId
                + " event=" + event + " pid=" + ProcessHandle.current().pid() + cwd
                + System.lineSeparator();
        Files.createDirectories(logFile.getParent());
        Files.writeString(logFile, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * One dispatcher for the lifetime of all live scopes. Routing, switching and closing
     * sinks share LOCK. Never acquire the dispatching PrintStream monitor while holding
     * LOCK: println holds that monitor before entering this stream.
     */
    private static final class DispatchPrintStream extends PrintStream {
        private final PrintStream terminal;

        private DispatchPrintStream(DispatchOutputStream output, PrintStream terminal) {
            super(output, true, StandardCharsets.UTF_8);
            this.terminal = terminal;
        }
    }

    private static final class DispatchOutputStream extends OutputStream {
        private final PrintStream terminal;

        private DispatchOutputStream(PrintStream terminal) {
            this.terminal = terminal;
        }

        private PrintStream target() {
            Binding bound = BOUND.get();
            if (bound != null && bound.owner != null && bound.owner.closed
                    && bound.owner.state.explicitEcho) {
                return bound.owner.state.rootErr;
            }
            // A retained dispatcher from an earlier lifetime cannot capture new sessions.
            if (dispatcher != this) return terminal;
            if (bound != null && bound.owner != null) {
                return bound.owner.closed ? terminal : bound.owner.state.scopedErr;
            }
            State top = STACK.peek();
            return top == null ? terminal : top.scopedErr;
        }

        @Override
        public void write(int value) {
            try {
                synchronized (LOCK) { target().write(value); }
            } finally { SessionOutputRouter.drainPending(); }
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            try {
                synchronized (LOCK) { target().write(bytes, offset, length); }
            } finally { SessionOutputRouter.drainPending(); }
        }

        @Override
        public void flush() {
            try {
                synchronized (LOCK) { target().flush(); }
            } finally { SessionOutputRouter.drainPending(); }
        }
    }

    /** Close only the transcript file; the terminal stream belongs to the JVM. */
    private static final class TeeOutputStream extends OutputStream {
        private final OutputStream terminal;
        private final OutputStream file;

        private TeeOutputStream(OutputStream terminal, OutputStream file) {
            this.terminal = terminal;
            this.file = file;
        }

        @Override
        public synchronized void write(int value) throws IOException {
            terminal.write(value);
            file.write(value);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length)
                throws IOException {
            terminal.write(bytes, offset, length);
            file.write(bytes, offset, length);
        }

        @Override
        public synchronized void flush() throws IOException {
            terminal.flush();
            file.flush();
        }

        @Override
        public synchronized void close() throws IOException {
            flush();
            file.close();
        }
    }
}
