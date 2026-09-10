/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Closes a connected stream when no bytes arrive within a provider's idle limit.
 *
 * <p>{@link java.net.http.HttpRequest.Builder#timeout(Duration)} stops protecting a
 * response once an {@code InputStream} body handler has delivered the headers. Closing
 * the underlying body from a watchdog is what unblocks a thread stuck in
 * {@link java.io.BufferedReader#readLine()}.</p>
 */
public final class IdleTimeoutInputStream extends FilterInputStream {

    private static final ScheduledExecutorService WATCHDOG =
            Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory());

    private final Duration idleTimeout;
    private final BooleanSupplier cancellationCheck;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean timedOut = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Thread> activeReader = new AtomicReference<>();
    private final Object timerLock = new Object();
    private ScheduledFuture<?> timeoutTask;
    private ScheduledFuture<?> cancellationTask;

    public IdleTimeoutInputStream(InputStream delegate, Duration idleTimeout) {
        this(delegate, idleTimeout, null);
    }

    public IdleTimeoutInputStream(InputStream delegate, Duration idleTimeout, BooleanSupplier cancellationCheck) {
        super(Objects.requireNonNull(delegate, "delegate"));
        this.idleTimeout = Objects.requireNonNull(idleTimeout, "idleTimeout");
        this.cancellationCheck = cancellationCheck;
        if (idleTimeout.isZero() || idleTimeout.isNegative()) {
            throw new IllegalArgumentException("idleTimeout must be positive");
        }
        armWatchdog();
        if (cancellationCheck != null) {
            synchronized (timerLock) {
                if (!closed.get()) cancellationTask = WATCHDOG.scheduleWithFixedDelay(this::checkCancellation,
                        50, 50, TimeUnit.MILLISECONDS);
            }
        }
    }

    @Override
    public int read() throws IOException {
        Thread reader = Thread.currentThread();
        activeReader.set(reader);
        try {
            return afterRead(super.read());
        } catch (IOException failure) {
            throw translateTimeout(failure);
        } finally {
            activeReader.compareAndSet(reader, null);
        }
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        Thread reader = Thread.currentThread();
        activeReader.set(reader);
        try {
            return afterRead(super.read(buffer, offset, length));
        } catch (IOException failure) {
            throw translateTimeout(failure);
        } finally {
            activeReader.compareAndSet(reader, null);
        }
    }

    @Override
    public long skip(long count) throws IOException {
        try {
            long skipped = super.skip(count);
            if (skipped > 0) armWatchdog();
            return skipped;
        } catch (IOException failure) {
            throw translateTimeout(failure);
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        cancelWatchdog();
        super.close();
    }

    public boolean timedOut() {
        return timedOut.get();
    }

    private int afterRead(int value) throws IOException {
        if (cancelled.get()) throw cancellationFailure(null);
        if (value < 0) {
            cancelWatchdog();
            if (timedOut.get()) {
                throw new StreamIdleTimeoutException(
                        "Provider stream was idle for " + describe(idleTimeout), null);
            }
        } else {
            armWatchdog();
        }
        return value;
    }

    private void armWatchdog() {
        synchronized (timerLock) {
            if (closed.get()) return;
            if (timeoutTask != null) timeoutTask.cancel(false);
            timeoutTask = WATCHDOG.schedule(this::expire,
                    idleTimeout.toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    private void cancelWatchdog() {
        synchronized (timerLock) {
            if (cancellationTask != null) {
                cancellationTask.cancel(false);
                cancellationTask = null;
            }
            if (timeoutTask != null) {
                timeoutTask.cancel(false);
                timeoutTask = null;
            }
        }
    }

    private void expire() {
        if (closed.get() || !timedOut.compareAndSet(false, true)) return;
        abortRead();
    }

    private void checkCancellation() {
        if (closed.get()) return;
        boolean requested;
        try {
            requested = cancellationCheck.getAsBoolean();
        } catch (RuntimeException ignored) {
            return; // A broken observer must not disable the idle watchdog.
        }
        if (requested && cancelled.compareAndSet(false, true)) abortRead();
    }

    private void abortRead() {
        Thread reader = activeReader.get();
        try {
            close();
        } catch (IOException ignored) {
            // The blocked reader observes the cancellation/timeout exception below.
        }
        if (reader != null && activeReader.get() == reader) reader.interrupt();
    }

    private IOException translateTimeout(IOException failure) {
        if (cancelled.get()) return cancellationFailure(failure);
        if (!timedOut.get()) return failure;
        // The watchdog owns this interrupt; expose a typed IOException instead of
        // leaking an interrupted flag into the next chat/tool operation.
        Thread.interrupted();
        return new StreamIdleTimeoutException(
                "Provider stream was idle for " + describe(idleTimeout), failure);
    }

    private static InterruptedIOException cancellationFailure(IOException cause) {
        InterruptedIOException failure = new InterruptedIOException("Provider stream cancelled");
        if (cause != null) failure.initCause(cause);
        return failure;
    }

    private static String describe(Duration duration) {
        if (duration.toMillis() < 1_000) return duration.toMillis() + " ms";
        if (duration.toSeconds() < 60) return duration.toSeconds() + " s";
        return duration.toMinutes() + " min";
    }

    /** Distinguishes a connected-but-silent stream from a user cancellation. */
    public static final class StreamIdleTimeoutException extends IOException {
        public StreamIdleTimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "kompile-chat-stream-watchdog");
            thread.setDaemon(true);
            return thread;
        }
    }
}
