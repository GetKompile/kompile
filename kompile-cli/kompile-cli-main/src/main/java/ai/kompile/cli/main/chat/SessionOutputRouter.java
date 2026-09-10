/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Locale;

/**
 * Opt-in, single-host stdout routing. Install before opening isolated log scopes;
 * pass sessionErr() as their terminal echo. System.err remains the transcript
 * dispatcher (leased for the host lifetime), never this echo stream. Terminal.writer
 * is untouched: the host must retain its raw renderer and detach hidden readers.
 *
 * <p>Routing uses explicit ChatUiSession bindings, not focus. Missing/closed sinks
 * are suppressed. Unbound legacy bytes go directly to the saved physical streams.
 * Flush emits a partial line but retains incomplete UTF-8 sequences. Subsequent
 * fragments are separate printAbove calls, not terminal-screen interpretation.
 * stdout and diagnostic fragments never share a buffer, nor do different owners.
 * Callbacks already queued at close may finish against their original owner only.
 * Retained router streams suppress isolated writes after close; global streams are
 * restored, so the host must stop producers before ending its routing lifetime.</p>
 */
public final class SessionOutputRouter implements AutoCloseable {
    private static volatile SessionOutputRouter installed;
    private final Object lock = new Object();
    private final PrintStream originalOut;
    private final Runnable releaseDiagnostics;
    private final RoutedStream output;
    private final RoutedStream diagnostic;
    private final PrintStream routedOut;
    private final PrintStream routedErr;
    private final ArrayDeque<Runnable> deliveries = new ArrayDeque<>();
    private final ThreadLocal<Boolean> inSink = new ThreadLocal<>();
    private boolean draining;
    private boolean closed;

    private SessionOutputRouter() {
        originalOut = System.out;
        output = new RoutedStream(originalOut);
        diagnostic = new RoutedStream(TranscriptLogScope.terminalEcho(), true);
        routedOut = new RoutingPrintStream(output);
        routedErr = new RoutingPrintStream(diagnostic);
        releaseDiagnostics = TranscriptLogScope.retainDispatcher();
        try {
            System.setOut(routedOut);
        } catch (RuntimeException | Error failure) {
            releaseDiagnostics.run();
            throw failure;
        }
    }

    /** Exclusive installation; a second host must not steal or close the first host. */
    public static synchronized SessionOutputRouter install() {
        if (installed != null) throw new IllegalStateException("Session output router already installed");
        installed = new SessionOutputRouter();
        return installed;
    }

    /** Diagnostic echo only: writing here directly does NOT append to a log file. */
    public PrintStream sessionErr() { return routedErr; }

    @Override
    public void close() {
        synchronized (SessionOutputRouter.class) {
            synchronized (lock) {
                if (closed) return;
                output.finish();
                diagnostic.finish();
                closed = true;
            }
            // A temporary TUI interceptor may own stdout. Never overwrite it; a
            // saved router it later restores is a safe, retired legacy passthrough.
            if (System.out == routedOut) System.setOut(originalOut);
        }
        // Do not hold the installation monitor while acquiring the log dispatch
        // lock: a diagnostic sink may concurrently dispose this same host.
        releaseDiagnostics.run();
        synchronized (SessionOutputRouter.class) {
            if (installed == this) installed = null;
        }
        drain();
        // Never close saved process streams or caller-owned UI sinks.
    }

    /**
     * One nonblocking drainer across both streams. No callback holds our lock.
     * A competing writer only queues; it never waits for a retained sink while
     * holding TranscriptLogScope's dispatch lock. Sink re-entry cannot recurse.
     */
    static void drainPending() {
        SessionOutputRouter router = installed;
        if (router != null) router.drain();
    }

    private void drain() {
        // Transcript writes/close queue their echoes while holding the file lock,
        // then drain after releasing it. Retained UI callbacks must not hold it.
        if (TranscriptLogScope.isDispatchLockedByCurrentThread()) return;
        synchronized (lock) {
            if (draining) return;
            draining = true;
        }
        try {
            while (true) {
                Runnable delivery;
                synchronized (lock) {
                    delivery = deliveries.poll();
                    if (delivery == null) {
                        draining = false;
                        return;
                    }
                }
                inSink.set(true);
                try { delivery.run(); }
                finally { inSink.remove(); }
            }
        } catch (RuntimeException | Error failure) {
            synchronized (lock) { draining = false; }
            throw failure;
        }
    }

    /**
     * Non-owning UTF-8 facade. PrintStream normally holds its own monitor through
     * sink callbacks, inverting the transcript dispatch lock during concurrent
     * close/flush. Every public writing entry point delegates without that monitor;
     * RoutedStream provides ordering. Closing this borrowed stream only flushes it.
     */
    private static final class RoutingPrintStream extends PrintStream {
        private final RoutedStream route;

        private RoutingPrintStream(RoutedStream route) {
            super(OutputStream.nullOutputStream(), false, StandardCharsets.UTF_8);
            this.route = route;
        }

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
            return append((value == null ? "null" : value).subSequence(start, end));
        }
        @Override public PrintStream append(char value) { print(value); return this; }
        @Override public PrintStream format(String format, Object... args) {
            print(String.format(format, args)); return this;
        }
        @Override public PrintStream format(Locale locale, String format, Object... args) {
            print(String.format(locale, format, args)); return this;
        }
        @Override public PrintStream printf(String format, Object... args) { return format(format, args); }
        @Override public PrintStream printf(Locale locale, String format, Object... args) {
            return format(locale, format, args);
        }
    }

    private final class RoutedStream extends OutputStream {
        private final PrintStream legacy;
        private final Map<ChatUiSession, Pending> pending = new IdentityHashMap<>();

        private final boolean alerts;

        private RoutedStream(PrintStream legacy) { this(legacy, false); }
        private RoutedStream(PrintStream legacy, boolean alerts) {
            this.legacy = legacy;
            this.alerts = alerts;
        }

        @Override
        public void write(int value) {
            write(new byte[]{(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (Boolean.TRUE.equals(inSink.get())) return; // no recursive sink echo
            ChatUiSession owner = ChatUiSession.current();
            if (owner.usesLegacyOutput()) {
                legacy.write(bytes, offset, length);
                return;
            }
            synchronized (lock) {
                pending.keySet().removeIf(ChatUiSession::isClosed);
                if (closed || owner.isClosed() || owner.contentOutput == null || length == 0) return;
                Pending buffer = pending.computeIfAbsent(owner, ignored -> new Pending(alerts));
                buffer.context = ChatSessionContext.current();
                for (int i = offset; i < offset + length; i++) {
                    if (bytes[i] == '\n') buffer.emit(true);
                    else buffer.bytes.write(bytes[i]);
                }
            }
            drain();
        }

        @Override
        public void flush() {
            if (Boolean.TRUE.equals(inSink.get())) return;
            ChatUiSession owner = ChatUiSession.current();
            if (owner.usesLegacyOutput()) {
                legacy.flush();
                return;
            }
            synchronized (lock) {
                Pending buffer = pending.get(owner);
                if (closed || owner.isClosed() || owner.contentOutput == null) pending.remove(owner);
                else if (buffer != null) buffer.emit(false);
            }
            drain();
        }

        // Called with lock, without acquiring either PrintStream's monitor.
        private void finish() {
            pending.forEach((owner, buffer) -> {
                if (!owner.isClosed() && owner.contentOutput != null) buffer.emit(false, true);
            });
            pending.clear();
        }
    }

    private final class Pending {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private ChatSessionContext context;
        private boolean fragmented;
        private final boolean alerts;

        private Pending(boolean alerts) { this.alerts = alerts; }

        private void emit(boolean newline) { emit(newline, newline); }

        private void emit(boolean newline, boolean endOfInput) {
            byte[] raw = bytes.toByteArray();
            ByteBuffer input = ByteBuffer.wrap(raw);
            CharBuffer chars = CharBuffer.allocate(raw.length);
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            decoder.decode(input, chars, endOfInput);
            bytes.reset();
            bytes.write(raw, input.position(), input.remaining());
            chars.flip();
            String text = chars.toString();
            if (newline && text.endsWith("\r")) text = text.substring(0, text.length() - 1);
            if (!text.isEmpty() || (newline && !fragmented)) {
                String line = text;
                deliveries.add(context.wrap(() -> {
                    ChatUiSession owner = ChatUiSession.current();
                    if (!owner.isClosed() && owner.contentOutput != null) {
                        if (alerts && ChatCompleter.showAlert(line)) return;
                        ChatCompleter.printAbove(line);
                    }
                }));
            }
            fragmented = !newline && (fragmented || !text.isEmpty());
        }
    }
}
