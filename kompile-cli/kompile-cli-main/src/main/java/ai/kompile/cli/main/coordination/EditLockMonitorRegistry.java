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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ai.kompile.cli.main.coordination;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Session-owned one-shot edit-lock monitors. An agent whose work is blocked on a file held
 * by another agent registers a monitor for that file path (or the exact lock id listed by
 * {@code query_edits}); the host daemon probes the live lock ledger and wakes the agent when
 * the target lock is gone — released, lease-expired and evicted, or dead-holder evicted.
 *
 * <p>Same discipline as {@link ActivityWaitRegistry}: only the host polls, no model turns
 * or commands run while waiting, readiness is advisory (a freed file is not a reservation —
 * another waiter may take it first), and external MCP clients use the same registry through
 * a held tool call instead of relying on unsupported unsolicited model wakeups. Probes run
 * against the coordinator's active-lock read and FAIL CLOSED: an unreadable ledger keeps the
 * monitor WAITING rather than reporting a release that may not have happened. Monitors
 * survive idle turns, not host shutdown, and end after 24 hours.
 */
public final class EditLockMonitorRegistry implements AutoCloseable {
    public enum State { WAITING, RELEASED, CANCELLED, EXPIRED }

    public record Snapshot(String monitorId, String sessionId, String lockId, String filePath,
                           String message, Instant createdAt, State state, String reason,
                           boolean wakeSupported) {
        public String notification() {
            return "[System edit lock release]\n"
                    + "Edit-lock monitor " + monitorId + " is " + state + ".\n"
                    + "Watched: " + (filePath == null ? "lock " + lockId : filePath)
                    + (lockId != null && filePath != null ? " (lock " + lockId + ")" : "") + "\n"
                    + reason + "\n"
                    + (message == null || message.isBlank() ? ""
                    : "Wake-up instructions: " + message + "\n")
                    + "Continue the parent task and re-run query_edits before editing: capacity "
                    + "is not reserved and another waiter may have taken the file already.";
        }
    }

    private static final class Monitor {
        final String id = UUID.randomUUID().toString();
        final String session, lockId, filePath, message;
        final Instant created;
        State state = State.WAITING;
        String reason = "Waiting for the lock to be released, expire, or be evicted";
        boolean delivered;

        Monitor(String session, String lockId, String filePath, String message, Instant created) {
            this.session = session;
            this.lockId = lockId;
            this.filePath = filePath;
            this.message = message;
            this.created = created;
        }
    }

    private final Map<String, Monitor> monitors = new LinkedHashMap<>();
    private final Map<String, Consumer<String>> wakeHandlers = new LinkedHashMap<>();
    private final ScheduledExecutorService executor;
    private final Supplier<Instant> clock;
    private final Supplier<Map<String, String>> activeLocks;
    private final AtomicBoolean checking = new AtomicBoolean();
    private boolean closed;
    private boolean started;

    public EditLockMonitorRegistry(Supplier<Map<String, String>> activeLocks) {
        this(activeLocks, Instant::now);
    }

    EditLockMonitorRegistry(Supplier<Map<String, String>> activeLocks, Supplier<Instant> clock) {
        this.activeLocks = activeLocks;
        this.clock = clock;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "coord-edit-lock-monitors");
            thread.setDaemon(true);
            return thread;
        });
    }

    public synchronized void setWakeHandler(String session, Consumer<String> handler) {
        if (handler == null) wakeHandlers.remove(session);
        else if (!closed) wakeHandlers.put(session, handler);
    }

    /**
     * Register a one-shot monitor on a live lock. {@code lockId} wins when both are given;
     * a path-only monitor follows whichever lock currently holds the file.
     *
     * @return the registered monitor; also RELEASED immediately when the target is already
     *         gone, and CANCELLED when the coordinator is unreadable at registration (the
     *         caller must re-check rather than wait on a ledger it cannot see)
     */
    public synchronized Snapshot watch(String session, String lockId, String filePath,
                                       String message) {
        if (closed) throw new IllegalStateException("Edit-lock monitor registry has shut down");
        String dedupeKey = lockId != null ? lockId : filePath;
        for (Monitor monitor : monitors.values()) {
            if (monitor.session.equals(session) && monitor.state == State.WAITING
                    && dedupeKey.equals(monitor.lockId != null ? monitor.lockId : monitor.filePath)) {
                return snapshot(monitor);
            }
        }
        Map<String, String> live = probe();
        if (live == null) {
            Monitor cancelled = new Monitor(session, lockId, filePath, message, clock.get());
            cancelled.state = State.CANCELLED;
            cancelled.reason = "Coordinator ledger is unreadable; no monitor was installed. "
                    + "Re-check with query_edits and register again.";
            return snapshot(cancelled);
        }
        boolean present = live.entrySet().stream()
                .anyMatch(active -> matchesTarget(active, lockId, filePath));
        if (!present) {
            Monitor released = new Monitor(session, lockId, filePath, message, clock.get());
            released.state = State.RELEASED;
            released.reason = "The watched lock is not in the active ledger";
            released.delivered = true;
            return snapshot(released);
        }
        pruneFinished();
        if (monitors.size() >= 128) {
            throw new IllegalStateException(
                    "Too many edit-lock monitors; cancel unused ones with unmonitor_edit");
        }
        Monitor monitor = new Monitor(session, lockId, filePath, message, clock.get());
        monitors.put(monitor.id, monitor);
        if (!started) {
            started = true;
            executor.scheduleWithFixedDelay(this::checkNow, 5, 5, TimeUnit.SECONDS);
        }
        return snapshot(monitor);
    }

    public synchronized List<Snapshot> list(String session) {
        return monitors.values().stream()
                .filter(m -> m.session.equals(session)).map(this::snapshot).toList();
    }

    public synchronized Snapshot get(String session, String id) {
        return snapshot(owned(session, id));
    }

    public synchronized Snapshot cancel(String session, String id) {
        Monitor monitor = owned(session, id);
        if (monitor.state == State.WAITING) {
            monitor.state = State.CANCELLED;
            monitor.reason = "Monitor cancelled; no wake-up will be delivered";
            monitor.delivered = true;
        }
        return snapshot(monitor);
    }

    /**
     * Bounded held wait for external MCP agents; cancellation also removes the wake-up.
     * Mirrors {@link ActivityWaitRegistry#await}.
     */
    public synchronized Snapshot await(String session, String id, long timeoutMs,
                                       BooleanSupplier aborted) throws InterruptedException {
        Monitor monitor = owned(session, id);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        try {
            while (true) {
                if (aborted.getAsBoolean() || closed) return cancel(session, id);
                if (monitor.state != State.WAITING) {
                    monitor.delivered = true;
                    return snapshot(monitor);
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return snapshot(monitor);
                wait(Math.max(1, Math.min(250, TimeUnit.NANOSECONDS.toMillis(remaining))));
            }
        } catch (InterruptedException interrupted) {
            cancel(session, id);
            throw interrupted;
        }
    }

    /** Also exposed for deterministic tests. Probes run outside the registry monitor. */
    public void checkNow() {
        if (!checking.compareAndSet(false, true)) return;
        try {
            checkPending();
        } finally {
            checking.set(false);
        }
    }

    private void checkPending() {
        List<Monitor> pending;
        synchronized (this) {
            if (closed) return;
            pending = List.copyOf(monitors.values());
        }
        Map<String, String> live = probe();
        for (Monitor monitor : pending) {
            synchronized (this) {
                // Only CANCELLED and delivered monitors are final: a RELEASED/EXPIRED monitor
                // whose wake-up delivery failed retries on the next tick, like resource waits.
                if (closed || monitor.state == State.CANCELLED || monitor.delivered) continue;
                State next = null;
                String reason = null;
                if (monitor.state == State.WAITING) {
                    if (Duration.between(monitor.created, clock.get()).toHours() >= 24) {
                        next = State.EXPIRED;
                        reason = "Monitor expired after 24 hours; the lock was still held";
                    } else if (live != null) {
                        // live == null fails closed: an unreadable ledger is not a release.
                        boolean gone = monitor.lockId != null
                                ? !live.containsValue(monitor.lockId)
                                : !live.containsKey(monitor.filePath);
                        if (gone) {
                            next = State.RELEASED;
                            reason = "The watched lock left the active ledger "
                                    + "(released, lease-expired, or its holder died)";
                        }
                    }
                } else {
                    next = monitor.state;
                    reason = monitor.reason;
                }
                if (next == null) continue;
                monitor.state = next;
                monitor.reason = reason;
                fire(monitor);
            }
        }
    }

    private void fire(Monitor monitor) {
        notifyAll();
        Consumer<String> handler = wakeHandlers.get(monitor.session);
        if (handler != null) {
            try {
                handler.accept(snapshot(monitor).notification());
                monitor.delivered = true;
            } catch (RuntimeException unavailable) {
                // A transient delivery failure is retried on the next host tick.
            }
        }
    }

    /**
     * Target match against one active-ledger entry ({@code path -> lockId}). A lock-id
     * monitor fires when that exact lock is gone, even if another lock now holds the file;
     * a path monitor fires only when no active lock holds the file.
     */
    private boolean matchesTarget(Map.Entry<String, String> active, String lockId, String filePath) {
        if (lockId != null) return lockId.equals(active.getValue());
        return filePath.equals(active.getKey());
    }

    /** One probe of the coordinator's active lock ledger; null means unreadable. */
    private Map<String, String> probe() {
        try {
            Map<String, String> live = activeLocks.get();
            return live == null ? null : Map.copyOf(live);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /** Drop finished monitors after delivery, and reclaim capacity from delivered ones. */
    private void pruneFinished() {
        monitors.values().removeIf(m -> m.state != State.WAITING && m.delivered
                && Duration.between(m.created, clock.get()).toHours() >= 1);
        if (monitors.size() >= 128) {
            monitors.values().stream().filter(m -> m.state != State.WAITING && m.delivered)
                    .findFirst().ifPresent(m -> monitors.remove(m.id));
        }
    }

    private Monitor owned(String session, String id) {
        Monitor monitor = monitors.get(id);
        if (monitor == null || !monitor.session.equals(session)) {
            throw new IllegalArgumentException("Edit-lock monitor not found in this session: " + id);
        }
        return monitor;
    }

    private Snapshot snapshot(Monitor monitor) {
        return new Snapshot(monitor.id, monitor.session, monitor.lockId, monitor.filePath,
                monitor.message, monitor.created, monitor.state, monitor.reason,
                wakeHandlers.containsKey(monitor.session));
    }

    @Override public synchronized void close() {
        closed = true;
        executor.shutdownNow();
        for (Monitor monitor : monitors.values()) {
            if (monitor.state == State.WAITING || !monitor.delivered) {
                monitor.state = State.CANCELLED;
                monitor.reason = "Host shut down; the monitor was cancelled";
                monitor.delivered = true;
            }
        }
        wakeHandlers.clear();
        notifyAll();
    }
}
