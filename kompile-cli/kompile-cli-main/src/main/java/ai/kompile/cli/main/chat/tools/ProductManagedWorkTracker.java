/* Copyright 2026 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.cli.main.chat.tools;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Session-local ownership of the supported in-host workers, not OS process containment.
 * Registrations precede submission. Only a worker's physical-stop/publication signal is
 * evidence of cleanup; cancelling a task/future or receiving a tool result is not.
 * Terminal identities remain owned for status/result isolation for this session's lifetime.
 */
public final class ProductManagedWorkTracker {
    public static final String PIPELINE = "pipeline";
    public static final String CRAWL = "crawl";

    private record Key(String kind, String id) { }
    private static final class Work {
        private final Runnable cancel;
        private final CompletableFuture<Void> stopped;
        private CompletableFuture<Void> cancellationReturned = CompletableFuture.completedFuture(null);
        private boolean cancellationStarted;

        private Work(Runnable cancel, CompletableFuture<Void> stopped) {
            this.cancel = cancel;
            this.stopped = stopped;
        }

        private synchronized void requestCancellation() {
            if (cancellationStarted || confirmed(stopped)) return;
            cancellationStarted = true;
            cancellationReturned = new CompletableFuture<>();
            // A cancel hook may be waiting on a publication fence. Never let that consume
            // the caller's entire close deadline; this thread does not execute content.
            try {
                Thread request = new Thread(() -> {
                    try { cancel.run(); }
                    catch (RuntimeException ignored) { /* Still require physical-stop evidence. */ }
                    finally { cancellationReturned.complete(null); }
                }, "product-managed-cancel");
                request.setDaemon(true);
                request.start();
            } catch (RuntimeException rejected) { cancellationReturned.completeExceptionally(rejected); }
        }

        private synchronized CompletableFuture<Void> drained() {
            return CompletableFuture.allOf(stopped, cancellationReturned);
        }

        private synchronized boolean cleanupConfirmed() {
            return confirmed(stopped) && confirmed(cancellationReturned);
        }
    }
    private final Map<Key, Work> owned = new LinkedHashMap<>();
    private boolean admissionStopped;

    public synchronized void register(String kind, String id, Runnable cancel,
                                      CompletableFuture<Void> actuallyStopped) {
        if (admissionStopped) throw new IllegalStateException("Product work admission is closed");
        if (kind == null || kind.isBlank() || id == null || id.isBlank()) {
            throw new IllegalArgumentException("Work kind and identity are required");
        }
        Key key = new Key(kind, id);
        Work work = new Work(Objects.requireNonNull(cancel), Objects.requireNonNull(actuallyStopped));
        if (owned.putIfAbsent(key, work) != null) throw new IllegalArgumentException("Work identity already owned");
    }

    public synchronized boolean owns(String kind, String id) {
        return owned.containsKey(new Key(kind, id));
    }

    public synchronized void stopAdmission() { admissionStopped = true; }

    public synchronized boolean isAdmissionStopped() { return admissionStopped; }

    /** One deadline includes cancellation hooks, real worker exit and terminal publication. */
    public boolean cancelAndAwait(Duration timeout) {
        Objects.requireNonNull(timeout);
        if (timeout.isNegative()) throw new IllegalArgumentException("Negative cleanup timeout");
        long budget;
        try { budget = timeout.toNanos(); }
        catch (ArithmeticException overflow) { budget = Long.MAX_VALUE; }
        long started = System.nanoTime();
        List<Work> work;
        synchronized (this) {
            stopAdmission();
            work = List.copyOf(owned.values());
        }
        work.forEach(Work::requestCancellation);
        for (Work entry : work) {
            if (entry.cleanupConfirmed()) continue;
            long remaining = budget - (System.nanoTime() - started);
            if (remaining <= 0) return cleanupConfirmed();
            try { entry.drained().get(remaining, TimeUnit.NANOSECONDS); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception unproved) { return false; }
        }
        return cleanupConfirmed();
    }

    public synchronized boolean cleanupConfirmed() {
        return admissionStopped && owned.values().stream().allMatch(Work::cleanupConfirmed);
    }

    private static boolean confirmed(CompletableFuture<Void> stopped) {
        return stopped.isDone() && !stopped.isCancelled() && !stopped.isCompletedExceptionally();
    }
}
