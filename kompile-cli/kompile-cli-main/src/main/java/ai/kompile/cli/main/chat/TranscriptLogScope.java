/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.logs.LogPaths;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.function.Consumer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
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
 * uses a process-owned file lock to refuse a second harness on the same transcript.
 * The OS releases ownership on exit or a hard kill; the empty lock file is retained
 * so clients never race by deleting/replacing its inode.</p>
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
    // All accesses occur under LOCK. Nested/isolated diagnostic scopes within one
    // harness retain the same process lease, rather than competing with themselves.
    private static final Map<Path, WriterLease> WRITERS = new HashMap<>();
    private static final ThreadLocal<Binding> BOUND = new ThreadLocal<>();
    /** The pre-chat terminal stream; no transcript sink tees through another sink. */
    private static PrintStream rootTerminal;
    private static DispatchOutputStream dispatcher;
    private static PrintStream dispatchErr;
    private static int activeStates;
    private static String legacyProperty;
    private static final Deque<Runnable> NOTICE_DELIVERIES = new ArrayDeque<>();
    private static final ThreadLocal<Boolean> DELIVERING_NOTICE = new ThreadLocal<>();
    private static boolean drainingNotices; // guarded by LOCK

    /**
     * Redirect only the terminal echo of this chat's stderr to a transient notice.
     * Full bytes still reach cli.log. No prefix classification and no process-wide
     * stream replacement: captured stderr handles, nested chats and /clear keep
     * their existing transcript ownership. A multi-session host can register its
     * unbound echo while retaining the dispatcher. Without a registration,
     * headless stderr retains its ordinary terminal behavior.
     */
    public static Runnable installTerminalNoticeSink(Consumer<String> sink) {
        if (sink == null) return () -> { };
        synchronized (LOCK) {
            Binding bound = BOUND.get();
            State owner = bound != null && bound.owner != null
                    ? (bound.owner.closed ? null : bound.owner.state) : STACK.peek();
            // A multi-session host retains the dispatcher before opening its
            // isolated scopes. Its registration owns only unbound diagnostics;
            // bound (including closed) owners must never borrow this host route.
            NoticeEcho echo = owner != null ? owner.noticeEcho
                    : bound == null && dispatcher != null ? dispatcher.noticeEcho : null;
            if (echo == null) return () -> { };
            NoticeRegistration registration = new NoticeRegistration(sink);
            echo.registrations.addLast(registration);
            return () -> {
                synchronized (LOCK) {
                    registration.active = false;
                    echo.registrations.remove(registration);
                    echo.discardPartial(registration);
                }
            };
        }
    }

    // Drain outside the file/dispatch lock. A notice callback may redraw, log,
    // close its scope, or wait for a different thread to write a diagnostic.
    private static void drainDiagnostics() {
        SessionOutputRouter.drainPending();
        if (Thread.holdsLock(LOCK) || Boolean.TRUE.equals(DELIVERING_NOTICE.get())) return;
        synchronized (LOCK) {
            if (drainingNotices) return;
            drainingNotices = true;
        }
        try {
            while (true) {
                Runnable delivery;
                synchronized (LOCK) {
                    delivery = NOTICE_DELIVERIES.pollFirst();
                    if (delivery == null) {
                        drainingNotices = false;
                        return;
                    }
                }
                DELIVERING_NOTICE.set(true);
                try { delivery.run(); }
                catch (RuntimeException ignored) {
                    // The diagnostic is already on disk. A closing UI must not
                    // leak it into the input area or break its emitting worker.
                } finally { DELIVERING_NOTICE.remove(); }
            }
        } catch (Error failure) {
            synchronized (LOCK) { drainingNotices = false; }
            throw failure;
        }
    }

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
            drainDiagnostics();
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
            drainDiagnostics();
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
        private final NoticeEcho noticeEcho;
        private PrintStream scopedErr;
        private PrintStream displacedErr;
        private Path logFile;
        private String transcriptId = "";
        private int references;
        private WriterLease writerLease;

        private State(PrintStream rootErr, boolean isolated, boolean explicitEcho) {
            this.rootErr = rootErr;
            this.noticeEcho = new NoticeEcho(rootErr);
            this.isolated = isolated;
            this.explicitEcho = explicitEcho;
        }

        private void switchTo(String id, Path workingDirectory, boolean resumed)
                throws IOException {
            Path directory = LogPaths.ensureTranscriptDirectory(id).toPath().toRealPath();
            WriterLease nextLease = WriterLease.acquire(directory, id);
            Path nextLog = directory.resolve("cli.log");
            PrintStream nextErr;
            try {
                appendBoundary(nextLog, resumed ? "resume" : "start", id, workingDirectory);
                OutputStream nextFile = Files.newOutputStream(nextLog,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                nextErr = new PrintStream(
                        new TeeOutputStream(noticeEcho, nextFile), true, StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException failure) {
                nextLease.release();
                throw failure;
            }

            // Do not dismantle the current sink until the replacement is fully open.
            // If any operation above fails, the old transcript remains observable.
            Path previousLog = this.logFile;
            String previousId = this.transcriptId;
            PrintStream previousErr = this.scopedErr;
            WriterLease previousLease = this.writerLease;
            this.writerLease = nextLease;
            this.transcriptId = id;
            this.logFile = nextLog;
            this.scopedErr = nextErr;
            closeSink(previousLog, previousId, previousErr, "scope-switch");
            if (previousLease != null) previousLease.release();
        }

        private void closeCurrentSink(String event) {
            Path currentLog = logFile;
            String currentId = transcriptId;
            PrintStream currentErr = scopedErr;
            scopedErr = null;
            closeSink(currentLog, currentId, currentErr, event);
            noticeEcho.registrations.forEach(registration -> registration.active = false);
            noticeEcho.registrations.clear();
            noticeEcho.pending.reset();
            if (writerLease != null) {
                writerLease.release();
                writerLease = null;
            }
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

    private static final class WriterLease {
        private final Path directory;
        private final FileChannel channel;
        private final FileLock fileLock;
        private int references = 1;

        private WriterLease(Path directory, FileChannel channel, FileLock fileLock) {
            this.directory = directory;
            this.channel = channel;
            this.fileLock = fileLock;
        }

        private static WriterLease acquire(Path directory, String id) throws IOException {
            WriterLease existing = WRITERS.get(directory);
            if (existing != null) {
                existing.references++;
                return existing;
            }
            FileChannel channel = FileChannel.open(directory.resolve("writer.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                FileLock lock;
                try {
                    lock = channel.tryLock();
                } catch (OverlappingFileLockException busy) {
                    lock = null;
                }
                if (lock == null) {
                    throw new IOException("Chat session " + id
                            + " is already running in another harness. Close it before resuming.");
                }
                WriterLease lease = new WriterLease(directory, channel, lock);
                WRITERS.put(directory, lease);
                return lease;
            } catch (IOException | RuntimeException failure) {
                channel.close();
                throw failure;
            }
        }

        private void release() {
            if (--references > 0) return;
            WRITERS.remove(directory);
            try { fileLock.release(); } catch (IOException ignored) { }
            try { channel.close(); } catch (IOException ignored) { }
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
        private final DispatchOutputStream route;

        private DispatchPrintStream(DispatchOutputStream output, PrintStream terminal) {
            super(OutputStream.nullOutputStream(), false, StandardCharsets.UTF_8);
            this.route = output;
            this.terminal = terminal;
        }

        // PrintStream's monitor must not span UI callbacks. The dispatch LOCK
        // already orders writes; a sink may wait for a different stderr writer.
        @Override public void write(int value) { route.write(value); }
        @Override public void write(byte[] bytes, int offset, int length) { route.write(bytes, offset, length); }
        @Override public void write(byte[] bytes) { write(bytes, 0, bytes.length); }
        @Override public void writeBytes(byte[] bytes) { write(bytes); }
        @Override public void flush() { route.flush(); }
        @Override public void close() { flush(); }
        @Override public void print(String value) { write(String.valueOf(value).getBytes(StandardCharsets.UTF_8)); }
        @Override public void print(boolean value) { print(String.valueOf(value)); }
        @Override public void print(char value) { print(String.valueOf(value)); }
        @Override public void print(int value) { print(String.valueOf(value)); }
        @Override public void print(long value) { print(String.valueOf(value)); }
        @Override public void print(float value) { print(String.valueOf(value)); }
        @Override public void print(double value) { print(String.valueOf(value)); }
        @Override public void print(char[] value) { print(new String(value)); }
        @Override public void print(Object value) { print(String.valueOf(value)); }
        @Override public void println() { print(System.lineSeparator()); }
        @Override public void println(String value) { print(String.valueOf(value) + System.lineSeparator()); }
        @Override public void println(boolean value) { println(String.valueOf(value)); }
        @Override public void println(char value) { println(String.valueOf(value)); }
        @Override public void println(int value) { println(String.valueOf(value)); }
        @Override public void println(long value) { println(String.valueOf(value)); }
        @Override public void println(float value) { println(String.valueOf(value)); }
        @Override public void println(double value) { println(String.valueOf(value)); }
        @Override public void println(char[] value) { println(new String(value)); }
        @Override public void println(Object value) { println(String.valueOf(value)); }
        @Override public PrintStream append(CharSequence value) { print(String.valueOf(value)); return this; }
        @Override public PrintStream append(CharSequence value, int start, int end) {
            print((value == null ? "null" : value).subSequence(start, end).toString()); return this;
        }
        @Override public PrintStream append(char value) { print(value); return this; }
        @Override public PrintStream format(String format, Object... args) {
            print(String.format(format, args)); return this;
        }
        @Override public PrintStream format(java.util.Locale locale, String format, Object... args) {
            print(String.format(locale, format, args)); return this;
        }
        @Override public PrintStream printf(String format, Object... args) { return format(format, args); }
        @Override public PrintStream printf(java.util.Locale locale, String format, Object... args) {
            return format(locale, format, args);
        }
    }

    private static final class DispatchOutputStream extends OutputStream {
        private final PrintStream terminal;
        private final NoticeEcho noticeEcho;
        private final PrintStream unboundEcho;

        private DispatchOutputStream(PrintStream terminal) {
            this.terminal = terminal;
            this.noticeEcho = new NoticeEcho(terminal);
            this.unboundEcho = new PrintStream(noticeEcho, true, StandardCharsets.UTF_8);
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
            return top == null ? unboundEcho : top.scopedErr;
        }

        @Override
        public void write(int value) {
            try {
                synchronized (LOCK) { target().write(value); }
            } finally { drainDiagnostics(); }
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            try {
                synchronized (LOCK) { target().write(bytes, offset, length); }
            } finally { drainDiagnostics(); }
        }

        @Override
        public void flush() {
            try {
                synchronized (LOCK) { target().flush(); }
            } finally { drainDiagnostics(); }
        }
    }

    private static final class NoticeRegistration {
        private final Consumer<String> sink;
        private volatile boolean active = true;
        private NoticeRegistration(Consumer<String> sink) { this.sink = sink; }
    }

    /** Buffered terminal copy only; all access is under the transcript LOCK. */
    private static final class NoticeEcho extends OutputStream {
        private static final int MAX_FRAGMENT_BYTES = 8192;
        private final PrintStream fallback;
        private final Deque<NoticeRegistration> registrations = new ArrayDeque<>();
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private NoticeRegistration partialOwner;

        private NoticeEcho(PrintStream fallback) { this.fallback = fallback; }

        @Override public void write(int value) {
            write(new byte[]{(byte) value}, 0, 1);
        }

        @Override public void write(byte[] bytes, int offset, int length) {
            NoticeRegistration target = registrations.peekLast();
            if (target == null) {
                fallback.write(bytes, offset, length);
                return;
            }
            // Logging from inside a UI sink still reaches the file, not this
            // echo: otherwise a failing/redrawing sink could notify itself forever.
            if (Boolean.TRUE.equals(DELIVERING_NOTICE.get())) return;
            if (partialOwner != target) {
                pending.reset();
                partialOwner = target;
            }
            for (int i = offset; i < offset + length; i++) {
                if (bytes[i] == '\n') emit(true);
                else {
                    pending.write(bytes[i]);
                    if (pending.size() >= MAX_FRAGMENT_BYTES) emit(false);
                }
            }
        }

        @Override public void flush() {
            if (registrations.isEmpty()) fallback.flush();
            else emit(false);
        }

        private void discardPartial(NoticeRegistration registration) {
            if (partialOwner == registration) {
                pending.reset();
                partialOwner = null;
            }
        }

        private void emit(boolean endOfLine) {
            if (pending.size() == 0 || partialOwner == null) return;
            byte[] raw = pending.toByteArray();
            ByteBuffer input = ByteBuffer.wrap(raw);
            CharBuffer chars = CharBuffer.allocate(raw.length);
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            decoder.decode(input, chars, endOfLine);
            pending.reset();
            pending.write(raw, input.position(), input.remaining());
            chars.flip();
            String text = chars.toString().strip();
            if (text.isEmpty()) return;
            NoticeRegistration destination = partialOwner;
            // The header only retains the newest notice. Bound the transient
            // delivery queue under a diagnostic storm; the file loses no bytes.
            if (NOTICE_DELIVERIES.size() >= 128) NOTICE_DELIVERIES.removeFirst();
            NOTICE_DELIVERIES.addLast(() -> {
                if (destination.active) destination.sink.accept(text);
            });
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
