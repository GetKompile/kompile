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
 * Session-owned, one-shot resource watches. Only the host polls; no model turns or commands
 * run while waiting. Readiness is advisory, never a reservation or permission to bypass admission.
 * Watches survive idle turns, not host shutdown. External MCP clients use await() to keep a
 * normal tool response pending instead of relying on unsupported unsolicited model wakeups.
 */
public final class ActivityWaitRegistry implements AutoCloseable {
    public enum State { WAITING, READY, CANCELLED, EXPIRED }

    public record Snapshot(String waitId, String sessionId, String kind, String description,
                           Instant createdAt, State state, String reason, boolean wakeSupported) {
        public String notification() {
            return "[System resource availability]\nResource watch " + waitId + " is " + state
                    + ".\nRequested: " + kind + " — " + description + "\n" + reason
                    + (state == State.READY
                    ? "\nContinue the parent task and retry the blocked tool through normal admission. "
                      + "Capacity is not reserved; no command was launched."
                    : state == State.WAITING ? "\nNo work has started; keep this watch."
                    : "\nInspect the blocker and re-register a watch if the task is still needed.");
        }
    }

    private static final class Wait {
        final String id = UUID.randomUUID().toString();
        final String session, key, kind, description;
        final Instant created;
        final BooleanSupplier ready;
        State state = State.WAITING;
        String reason = "Waiting for peer work and configured RAM/GPU capacity";
        boolean delivered;

        Wait(String session, String key, String kind, String description, BooleanSupplier ready, Instant created) {
            this.created = created;
            this.session = session;
            this.key = key;
            this.kind = kind;
            this.description = description;
            this.ready = ready;
        }
    }

    private final Map<String, Wait> waits = new LinkedHashMap<>();
    private final Map<String, Consumer<String>> wakeHandlers = new LinkedHashMap<>();
    private final ScheduledExecutorService executor;
    private final Supplier<Instant> clock;
    private final AtomicBoolean checking = new AtomicBoolean();
    private boolean closed;
    private boolean started;

    public ActivityWaitRegistry() {
        this(Instant::now);
    }

    ActivityWaitRegistry(Supplier<Instant> clock) {
        this.clock = clock;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "coord-resource-waits");
            thread.setDaemon(true);
            return thread;
        });
    }

    public synchronized void setWakeHandler(String session, Consumer<String> handler) {
        if (handler == null) wakeHandlers.remove(session);
        else if (!closed) wakeHandlers.put(session, handler);
    }

    public synchronized Snapshot watch(String session, String key, String kind,
                                       String description, BooleanSupplier ready) {
        if (closed) throw new IllegalStateException("Resource watcher has shut down");
        for (Wait wait : waits.values()) {
            if (wait.session.equals(session) && wait.key.equals(key) && wait.state == State.WAITING) {
                return snapshot(wait);
            }
        }
        waits.values().removeIf(w -> w.state != State.WAITING && w.delivered
                && Duration.between(w.created, clock.get()).toHours() >= 1);
        if (waits.size() >= 128) {
            waits.values().stream().filter(w -> w.state != State.WAITING && w.delivered)
                    .findFirst().ifPresent(w -> waits.remove(w.id));
        }
        if (waits.size() >= 128) throw new IllegalStateException("Too many resource watches; cancel unused watches");
        Wait wait = new Wait(session, key, kind, description, ready, clock.get());
        waits.put(wait.id, wait);
        if (!started) {
            started = true;
            executor.scheduleWithFixedDelay(this::checkNow, 5, 5, TimeUnit.SECONDS);
        }
        return snapshot(wait);
    }

    public synchronized List<Snapshot> list(String session) {
        return waits.values().stream().filter(w -> w.session.equals(session)).map(this::snapshot).toList();
    }

    public synchronized Snapshot get(String session, String id) {
        return snapshot(owned(session, id));
    }

    public synchronized Snapshot cancel(String session, String id) {
        return cancel(owned(session, id));
    }

    private Snapshot cancel(Wait wait) {
        wait.state = State.CANCELLED;
        wait.reason = "Resource watch cancelled; no work started";
        wait.delivered = true;
        notifyAll();
        return snapshot(wait);
    }

    /** Clear only the matching request after it has actually passed admission. */
    public synchronized void cancelMatching(String session, String key) {
        for (Wait wait : waits.values()) {
            if (wait.session.equals(session) && wait.key.equals(key)) cancel(session, wait.id);
        }
    }

    /** A bounded held tool call; cancellation also removes the future wake-up. */
    public synchronized Snapshot await(String session, String id, long timeoutMs,
                                       BooleanSupplier aborted) throws InterruptedException {
        Wait wait = owned(session, id);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        try {
            while (true) {
                if (aborted.getAsBoolean() || closed) return cancel(wait);
                if (wait.state != State.WAITING) {
                    wait.delivered = true;
                    return snapshot(wait);
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return snapshot(wait);
                wait(Math.max(1, Math.min(250, TimeUnit.NANOSECONDS.toMillis(remaining))));
            }
        } catch (InterruptedException interrupted) {
            cancel(wait);
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
        List<Wait> pending;
        synchronized (this) {
            if (closed) return;
            pending = List.copyOf(waits.values());
        }
        for (Wait wait : pending) {
            State next;
            String reason;
            synchronized (this) {
                if (closed || wait.state == State.CANCELLED || wait.delivered) continue;
                next = wait.state;
                reason = wait.reason;
            }
            try {
                if (next == State.WAITING) {
                    if (Duration.between(wait.created, clock.get()).toHours() >= 24) {
                        next = State.EXPIRED;
                        reason = "Watch expired after 24 hours; resources were not admitted";
                    } else if (wait.ready.getAsBoolean()) {
                        next = State.READY;
                        reason = "The shared activity lane, peer processes and configured capacity checks are clear";
                    }
                }
            } catch (RuntimeException failure) {
                // Fail closed and keep watching; one broken probe must not stop the scheduler.
                reason = "Resource check unavailable: " + failure.getMessage();
            }
            synchronized (this) {
                if (closed || wait.state == State.CANCELLED || wait.delivered) continue;
                wait.state = next;
                wait.reason = reason;
                if (next != State.WAITING) {
                    notifyAll();
                    Consumer<String> handler = wakeHandlers.get(wait.session);
                    if (handler != null) {
                        try {
                            handler.accept(snapshot(wait).notification());
                            wait.delivered = true;
                        } catch (RuntimeException unavailable) {
                            // A transient delivery failure is retried on the next host tick.
                        }
                    }
                }
            }
        }
    }

    private Wait owned(String session, String id) {
        Wait wait = waits.get(id);
        if (wait == null || !wait.session.equals(session)) {
            throw new IllegalArgumentException("Resource watch not found in this tool session: " + id);
        }
        return wait;
    }

    private Snapshot snapshot(Wait wait) {
        return new Snapshot(wait.id, wait.session, wait.kind, wait.description, wait.created,
                wait.state, wait.reason, wakeHandlers.containsKey(wait.session));
    }

    @Override public synchronized void close() {
        closed = true;
        executor.shutdownNow();
        for (Wait wait : waits.values()) {
            if (wait.state == State.WAITING || !wait.delivered) cancel(wait.session, wait.id);
        }
        wakeHandlers.clear();
        notifyAll();
    }
}
