/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.coordination;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * A keyed, reference-counted pool for expensive reusable resources.
 *
 * <p>The pool collapses concurrent creation for the same key, retains an idle resource for a
 * configurable keep-warm interval, replaces unhealthy resources, and can bound the total number
 * of resident resources. When a bounded pool is full, an idle resource is evicted immediately;
 * if every resource is leased, acquisition waits for capacity instead of over-allocating.</p>
 *
 * <p>The resource key must describe every compatibility-affecting input. The factory is supplied
 * per acquisition so callers can retain checked startup errors and request-local timeout values
 * without putting those operational values into the compatibility key.</p>
 */
public final class ReusableResourcePool<K, R> implements AutoCloseable {

    @FunctionalInterface
    public interface ResourceFactory<R> {
        R create() throws Exception;
    }

    @FunctionalInterface
    public interface HealthCheck<R> {
        boolean isHealthy(R resource);
    }

    @FunctionalInterface
    public interface ResourceCloser<R> {
        void close(R resource) throws Exception;
    }

    /** A lease releases its reference on close; it never destroys the resource directly. */
    public static final class Lease<R> implements AutoCloseable {
        private final R resource;
        private final BooleanSupplier healthy;
        private final Runnable markUsed;
        private final Runnable release;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private Lease(R resource, BooleanSupplier healthy, Runnable markUsed, Runnable release) {
            this.resource = resource;
            this.healthy = healthy;
            this.markUsed = markUsed;
            this.release = release;
        }

        public R resource() {
            if (closed.get()) {
                throw new IllegalStateException("Resource lease is closed");
            }
            markUsed.run();
            return resource;
        }

        public boolean isHealthy() {
            return !closed.get() && healthy.getAsBoolean();
        }

        public boolean isClosed() {
            return closed.get();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                release.run();
            }
        }
    }

    private final class Entry {
        private final K key;
        private R resource;
        private int leases;
        private boolean starting;
        private boolean closing;
        private boolean removed;
        private long lastUsedNanos;
        private long reapGeneration;
        private ScheduledFuture<?> pendingReap;

        private Entry(K key) {
            this.key = key;
        }
    }

    private final Object lock = new Object();
    private final Map<K, Entry> entries = new HashMap<>();
    private final String name;
    private final LongSupplier idleMillis;
    private final IntSupplier maxResources;
    private final HealthCheck<R> healthCheck;
    private final ResourceCloser<R> closer;
    private final ScheduledThreadPoolExecutor reaper;
    private int activeIdleTeardowns;
    private boolean closed;

    public ReusableResourcePool(
            String name,
            LongSupplier idleMillis,
            IntSupplier maxResources,
            HealthCheck<R> healthCheck,
            ResourceCloser<R> closer) {
        this.name = Objects.requireNonNull(name, "name");
        this.idleMillis = Objects.requireNonNull(idleMillis, "idleMillis");
        this.maxResources = Objects.requireNonNull(maxResources, "maxResources");
        this.healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
        this.closer = Objects.requireNonNull(closer, "closer");
        this.reaper = new ScheduledThreadPoolExecutor(1, r -> {
            Thread thread = new Thread(r, name + "-reaper");
            thread.setDaemon(true);
            return thread;
        });
        this.reaper.setRemoveOnCancelPolicy(true);
        this.reaper.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    /**
     * Acquire a compatible resource.
     *
     * @param waitMillis maximum time to wait for an in-flight creation or capacity; zero waits
     *                   indefinitely
     */
    public Lease<R> acquire(
            K key,
            ResourceFactory<R> factory,
            long waitMillis) throws Exception {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(factory, "factory");
        long deadline = waitMillis > 0
                ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis)
                : 0L;

        while (true) {
            Entry entry;
            R staleResource = null;
            synchronized (lock) {
                requireOpen();
                entry = entries.get(key);
                if (entry != null) {
                    if (entry.starting || entry.closing) {
                        awaitChange(deadline, waitMillis);
                        continue;
                    }
                    cancelReap(entry);
                    if (entry.resource != null && healthy(entry.resource)) {
                        entry.leases++;
                        markUsedLocked(entry);
                        return lease(entry, entry.resource);
                    }
                    staleResource = entry.resource;
                    entry.resource = null;
                    entry.starting = true;
                } else {
                    if (!hasCapacity()) {
                        Entry idle = oldestIdleEntry();
                        if (idle == null) {
                            awaitChange(deadline, waitMillis);
                            continue;
                        }
                        entries.remove(idle.key, idle);
                        idle.removed = true;
                        cancelReap(idle);
                        staleResource = idle.resource;
                        idle.resource = null;
                    }
                    entry = new Entry(key);
                    entry.starting = true;
                    entries.put(key, entry);
                }
            }

            closeQuietly(staleResource);
            return finishCreation(entry, factory);
        }
    }

    private Lease<R> finishCreation(Entry entry, ResourceFactory<R> factory) throws Exception {
        final R created;
        try {
            created = Objects.requireNonNull(factory.create(), "Resource factory returned null");
        } catch (Exception | Error failure) {
            synchronized (lock) {
                entry.starting = false;
                entry.removed = true;
                entries.remove(entry.key, entry);
                lock.notifyAll();
            }
            throw failure;
        }

        synchronized (lock) {
            if (closed || entry.removed || entries.get(entry.key) != entry) {
                closeQuietly(created);
                throw new IllegalStateException(name + " closed while a resource was starting");
            }
            entry.resource = created;
            entry.starting = false;
            entry.leases++;
            markUsedLocked(entry);
            lock.notifyAll();
            return lease(entry, created);
        }
    }

    private Lease<R> lease(Entry entry, R resource) {
        return new Lease<>(
                resource,
                () -> healthy(resource),
                () -> markUsed(entry),
                () -> release(entry));
    }

    private void markUsed(Entry entry) {
        synchronized (lock) {
            if (entry.removed || entries.get(entry.key) != entry) {
                return;
            }
            markUsedLocked(entry);
            if (entry.leases == 0 && entry.pendingReap != null) {
                scheduleReap(entry);
            }
        }
    }

    private void markUsedLocked(Entry entry) {
        entry.lastUsedNanos = System.nanoTime();
    }

    private void release(Entry entry) {
        synchronized (lock) {
            if (entry.leases > 0) {
                entry.leases--;
            }
            if (entry.leases == 0 && !entry.removed && !entry.starting
                    && entry.resource != null) {
                markUsedLocked(entry);
                scheduleReap(entry);
            }
            lock.notifyAll();
        }
    }

    private void scheduleReap(Entry entry) {
        scheduleReap(entry, TimeUnit.MILLISECONDS.toNanos(
                Math.max(0L, idleMillis.getAsLong())));
    }

    private void scheduleReap(Entry entry, long delayNanos) {
        cancelReap(entry);
        long generation = entry.reapGeneration;
        entry.pendingReap = reaper.schedule(
                () -> reap(entry, generation), Math.max(0L, delayNanos), TimeUnit.NANOSECONDS);
    }

    private void reap(Entry entry, long generation) {
        R resource;
        synchronized (lock) {
            if (generation != entry.reapGeneration || entry.removed
                    || entry.starting || entry.leases > 0
                    || entries.get(entry.key) != entry) {
                return;
            }
            long requiredIdleNanos = TimeUnit.MILLISECONDS.toNanos(
                    Math.max(0L, idleMillis.getAsLong()));
            long idleNanos = Math.max(0L, System.nanoTime() - entry.lastUsedNanos);
            if (idleNanos < requiredIdleNanos) {
                scheduleReap(entry, requiredIdleNanos - idleNanos);
                return;
            }
            // Retain the capacity slot until teardown finishes. In particular, do not load a
            // replacement GPU model while the previous process is still releasing its memory.
            entry.closing = true;
            activeIdleTeardowns++;
            entry.pendingReap = null;
            resource = entry.resource;
            entry.resource = null;
        }
        try {
            closeQuietly(resource);
        } finally {
            synchronized (lock) {
                entries.remove(entry.key, entry);
                entry.removed = true;
                activeIdleTeardowns--;
                lock.notifyAll();
            }
        }
    }

    private Entry oldestIdleEntry() {
        Entry oldest = null;
        for (Entry entry : entries.values()) {
            if (entry.removed || entry.starting || entry.closing || entry.leases > 0) {
                continue;
            }
            if (oldest == null || entry.lastUsedNanos < oldest.lastUsedNanos) {
                oldest = entry;
            }
        }
        return oldest;
    }

    private boolean hasCapacity() {
        int maximum = maxResources.getAsInt();
        return maximum <= 0 || entries.size() < maximum;
    }

    private boolean healthy(R resource) {
        try {
            return healthCheck.isHealthy(resource);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void awaitChange(long deadline, long waitMillis)
            throws InterruptedException, TimeoutException {
        if (waitMillis <= 0) {
            lock.wait();
            return;
        }
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new TimeoutException("Timed out waiting for capacity in " + name);
        }
        long millis = TimeUnit.NANOSECONDS.toMillis(remaining);
        int nanos = (int) (remaining - TimeUnit.MILLISECONDS.toNanos(millis));
        lock.wait(millis, nanos);
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException(name + " is closed");
        }
    }

    private void cancelReap(Entry entry) {
        entry.reapGeneration++;
        if (entry.pendingReap != null) {
            entry.pendingReap.cancel(false);
            entry.pendingReap = null;
        }
    }

    /** Destroys all currently pooled resources while keeping this pool reusable. */
    public void clear() {
        List<R> resources = detachAll(false);
        resources.forEach(this::closeQuietly);
        awaitIdleTeardowns();
    }

    public int pooledCount() {
        synchronized (lock) {
            return entries.size();
        }
    }

    public int leaseCount(K key) {
        synchronized (lock) {
            Entry entry = entries.get(key);
            return entry == null ? 0 : entry.leases;
        }
    }

    int queuedReapTasksForTests() {
        return reaper.getQueue().size();
    }

    @Override
    public void close() {
        List<R> resources = detachAll(true);
        resources.forEach(this::closeQuietly);
        awaitIdleTeardowns();
        reaper.shutdownNow();
    }

    private void awaitIdleTeardowns() {
        // A daemon reaper may already own a detached child. Shutdown must not return (or
        // interrupt the reaper) before its graceful/forced process termination completes.
        boolean interrupted = false;
        synchronized (lock) {
            while (activeIdleTeardowns > 0) {
                try {
                    lock.wait();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private List<R> detachAll(boolean closePool) {
        List<R> resources = new ArrayList<>();
        synchronized (lock) {
            if (closePool) {
                closed = true;
            }
            for (Entry entry : entries.values()) {
                cancelReap(entry);
                entry.removed = true;
                if (entry.resource != null) {
                    resources.add(entry.resource);
                    entry.resource = null;
                }
            }
            entries.clear();
            lock.notifyAll();
        }
        return resources;
    }

    private void closeQuietly(R resource) {
        if (resource == null) {
            return;
        }
        try {
            closer.close(resource);
        } catch (Exception failure) {
            System.err.println("[" + name + "] Resource cleanup failed: " + failure.getMessage());
        }
    }
}
