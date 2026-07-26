/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.app.services.grounding;

import ai.kompile.graphchangetracking.event.GraphBatchMutationEvent;
import ai.kompile.graphchangetracking.event.NodeMutationEvent;
import ai.kompile.graphchangetracking.event.EdgeMutationEvent;
import ai.kompile.knowledgegraph.grounding.AgentFactAssertedEvent;
import ai.kompile.knowledgegraph.grounding.AgentFactRetractedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Server-side subscription registry for KB fact-change events.
 *
 * <h3>Design</h3>
 * <ul>
 *   <li>Callers create a subscription scoped to a {@code factSheetId} + predicate filter via
 *       {@link #createSubscription}. Each subscription has its own bounded ring buffer
 *       ({@value #RING_BUFFER_CAPACITY} slots) and a monotonically increasing sequence counter.</li>
 *   <li>{@link EventListener} methods receive {@link AgentFactAssertedEvent},
 *       {@link AgentFactRetractedEvent}, {@link NodeMutationEvent}, {@link EdgeMutationEvent},
 *       and {@link GraphBatchMutationEvent} and fan them out to matching subscriptions.</li>
 *   <li>SSE emitters (browser/UI) read directly from the subscription ring buffer as events arrive.
 *       MCP tools use cursor-based long-poll via {@link #poll} which parks a calling thread until
 *       new events appear or the timeout elapses — no busy-spin.</li>
 *   <li>Subscriptions are evicted after {@value #IDLE_TTL_MS} ms of no consumption (lazy sweep
 *       on a scheduled task), and the total count is capped at {@value #MAX_SUBSCRIPTIONS}
 *       (oldest-idle first when the cap is hit).</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * Each {@link KbSubscription} owns a {@link ReentrantLock} + {@link Condition} used for long-poll
 * parking. The global subscription map uses {@link ConcurrentHashMap} for concurrent reads.
 */
@Slf4j
@Service
public class KbFactSubscriptionService {

    /** Max events kept per subscription (ring buffer — oldest dropped on overflow). */
    public static final int RING_BUFFER_CAPACITY = 500;

    /** Subscription expires after this many ms of no consumption (30 min). */
    public static final long IDLE_TTL_MS = 30 * 60 * 1_000L;

    /** Hard cap on active subscriptions; oldest-idle evicted when exceeded. */
    public static final int MAX_SUBSCRIPTIONS = 200;

    /** Max long-poll wait time enforced server-side (25 s). */
    public static final long MAX_WAIT_MS = 25_000L;

    // ── Subscription store ────────────────────────────────────────────────────────

    private final ConcurrentHashMap<String, KbSubscription> subscriptions = new ConcurrentHashMap<>();

    // ── Public API ────────────────────────────────────────────────────────────────

    /**
     * Create a new subscription and return its descriptor.
     *
     * @param factSheetId the fact-sheet scope
     * @param predicates  predicate names to match (empty = match all predicates on this fact sheet)
     * @return the newly created subscription
     */
    public KbSubscription createSubscription(long factSheetId, List<String> predicates) {
        enforceCapacity();
        String id = UUID.randomUUID().toString();
        KbSubscription sub = new KbSubscription(id, factSheetId, List.copyOf(predicates != null ? predicates : List.of()));
        subscriptions.put(id, sub);
        log.debug("[kb-sub] created subscription {} factSheet={} predicates={}", id, factSheetId, predicates);
        return sub;
    }

    /**
     * Resolve a subscription by id. Returns empty if the id is unknown or the subscription has expired.
     */
    public Optional<KbSubscription> get(String subscriptionId) {
        KbSubscription sub = subscriptions.get(subscriptionId);
        if (sub == null || sub.isExpired()) {
            if (sub != null) subscriptions.remove(subscriptionId, sub);
            return Optional.empty();
        }
        return Optional.of(sub);
    }

    /**
     * Cancel and remove a subscription.
     */
    public void cancel(String subscriptionId) {
        KbSubscription removed = subscriptions.remove(subscriptionId);
        if (removed != null) {
            removed.signalAll(); // unblock any parked poll thread
            log.debug("[kb-sub] cancelled subscription {}", subscriptionId);
        }
    }

    /**
     * Cursor-based long-poll: return events with {@code seq > afterCursor} immediately if any
     * exist, else park the calling thread for up to {@code waitMs} (capped at
     * {@value #MAX_WAIT_MS} ms). Returns immediately when new events arrive or the timeout elapses.
     *
     * @param subscriptionId the subscription id
     * @param afterCursor    return only events with seq strictly greater than this value; use -1
     *                       to read from the beginning (all buffered events)
     * @param waitMs         maximum park time in ms
     * @return poll result (events list, nextCursor, overflow flag); empty events list on timeout
     * @throws IllegalArgumentException if the subscriptionId is unknown or expired
     */
    public PollResult poll(String subscriptionId, long afterCursor, long waitMs) {
        KbSubscription sub = subscriptions.get(subscriptionId);
        if (sub == null || sub.isExpired()) {
            if (sub != null) subscriptions.remove(subscriptionId, sub);
            throw new IllegalArgumentException("Unknown or expired subscription: " + subscriptionId);
        }

        long effectiveWaitMs = Math.min(waitMs, MAX_WAIT_MS);
        return sub.poll(afterCursor, effectiveWaitMs);
    }

    // ── Event listeners ───────────────────────────────────────────────────────────

    @EventListener
    public void onFactAsserted(AgentFactAssertedEvent event) {
        KbEvent e = new KbEvent(
                "asserted",
                event.getFactSheetId(),
                event.getAtomKey(),
                event.getValue(),
                event.getSessionId()
        );
        fanOut(event.getFactSheetId(), event.getAtomKey(), e);
    }

    @EventListener
    public void onFactRetracted(AgentFactRetractedEvent event) {
        KbEvent e = new KbEvent(
                "retracted",
                event.getFactSheetId(),
                event.getAtomKey(),
                null,
                null
        );
        fanOut(event.getFactSheetId(), event.getAtomKey(), e);
    }

    @EventListener
    public void onNodeMutation(NodeMutationEvent event) {
        if (event.getFactSheetId() == null) return;
        KbEvent e = new KbEvent(
                "graph_mutated",
                event.getFactSheetId(),
                null,
                null,
                event.getTriggerSource()
        );
        fanOutSheet(event.getFactSheetId(), e);
    }

    @EventListener
    public void onEdgeMutation(EdgeMutationEvent event) {
        if (event.getFactSheetId() == null) return;
        KbEvent e = new KbEvent(
                "graph_mutated",
                event.getFactSheetId(),
                null,
                null,
                event.getTriggerSource()
        );
        fanOutSheet(event.getFactSheetId(), e);
    }

    @EventListener
    public void onBatchMutation(GraphBatchMutationEvent event) {
        if (event.getFactSheetId() == null) return;
        KbEvent e = new KbEvent(
                "changed",
                event.getFactSheetId(),
                null,
                null,
                event.getTriggerSource()
        );
        fanOutSheet(event.getFactSheetId(), e);
    }

    // ── Eviction ──────────────────────────────────────────────────────────────────

    @Scheduled(fixedDelay = 120_000)
    public void evictIdleSubscriptions() {
        int before = subscriptions.size();
        subscriptions.entrySet().removeIf(e -> e.getValue().isExpired());
        int removed = before - subscriptions.size();
        if (removed > 0) {
            log.debug("[kb-sub] evicted {} idle subscriptions (active={})", removed, subscriptions.size());
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────────

    /**
     * Fan out to subscriptions for this fact sheet where the event has a specific atomKey.
     * Predicate filtering applies: a subscription only receives the event if it has no
     * predicate filter OR the atomKey starts with one of the subscribed predicates followed by '('.
     */
    private void fanOut(long factSheetId, String atomKey, KbEvent event) {
        for (KbSubscription sub : subscriptions.values()) {
            if (sub.factSheetId() != factSheetId) continue;
            if (sub.matchesPredicate(atomKey)) {
                sub.enqueue(event);
            }
        }
    }

    /**
     * Fan out a sheet-level event (no specific atomKey) to all subscriptions for this fact sheet.
     */
    private void fanOutSheet(long factSheetId, KbEvent event) {
        for (KbSubscription sub : subscriptions.values()) {
            if (sub.factSheetId() == factSheetId) {
                sub.enqueue(event);
            }
        }
    }

    /** Evict the oldest-idle subscription when the cap is reached. */
    private void enforceCapacity() {
        if (subscriptions.size() < MAX_SUBSCRIPTIONS) return;
        subscriptions.values().stream()
                .min(java.util.Comparator.comparingLong(KbSubscription::lastConsumedMs))
                .ifPresent(oldest -> {
                    subscriptions.remove(oldest.subscriptionId());
                    oldest.signalAll();
                    log.debug("[kb-sub] cap eviction: removed subscription {}", oldest.subscriptionId());
                });
    }

    // ── Nested types ──────────────────────────────────────────────────────────────

    /**
     * A single KB change event destined for subscriber ring buffers.
     *
     * @param type        "asserted" | "retracted" | "graph_mutated" | "changed"
     * @param factSheetId the affected fact sheet
     * @param atomKey     the specific atom key (null for sheet-level events)
     * @param value       truth value (null for retract/graph events)
     * @param source      provenance/session id (may be null)
     */
    public record KbEvent(
            String type,
            long factSheetId,
            String atomKey,
            Double value,
            String source
    ) {}

    /** Result returned from a long-poll operation. */
    public record PollResult(
            List<SequencedEvent> events,
            long nextCursor,
            boolean overflow
    ) {}

    /** An event with its monotonic sequence number assigned by the ring buffer. */
    public record SequencedEvent(
            long seq,
            Instant ts,
            KbEvent event
    ) {}

    /**
     * An active subscription: ring buffer + predicate filter + long-poll lock.
     */
    public static class KbSubscription {

        private final String id;
        private final long factSheetId;
        private final List<String> predicates;
        private final Instant createdAt;
        private final Instant expiresAt;

        /** Ring buffer of sequenced events. */
        private final SequencedEvent[] ring;
        /** Monotonically increasing; next event gets this seq then we increment. */
        private long nextSeq = 0;
        /** How many total events have been enqueued (ring wraps at capacity). */
        private long totalEnqueued = 0;
        /** Tracks the minimum seq currently in the buffer (for overflow detection). */
        private long minSeqInBuffer = 0;

        private final ReentrantLock lock = new ReentrantLock();
        private final Condition hasNewEvents = lock.newCondition();

        private volatile long lastConsumedMs = System.currentTimeMillis();

        KbSubscription(String id, long factSheetId, List<String> predicates) {
            this.id = id;
            this.factSheetId = factSheetId;
            this.predicates = predicates;
            this.createdAt = Instant.now();
            this.expiresAt = createdAt.plusMillis(IDLE_TTL_MS);
            this.ring = new SequencedEvent[RING_BUFFER_CAPACITY];
        }

        public String subscriptionId() { return id; }
        public long factSheetId() { return factSheetId; }
        public List<String> predicates() { return predicates; }
        public Instant createdAt() { return createdAt; }
        public Instant expiresAt() { return expiresAt; }
        public long lastConsumedMs() { return lastConsumedMs; }

        public boolean isExpired() {
            return System.currentTimeMillis() - lastConsumedMs > IDLE_TTL_MS;
        }

        /**
         * Returns true if this subscription is interested in events for {@code atomKey}.
         * An empty predicate list means "all predicates on this fact sheet".
         */
        public boolean matchesPredicate(String atomKey) {
            if (atomKey == null) return true;
            if (predicates.isEmpty()) return true;
            for (String pred : predicates) {
                if (atomKey.startsWith(pred + "(")) return true;
            }
            return false;
        }

        /** Enqueue an event. Overwrites the oldest entry if the buffer is full. */
        void enqueue(KbEvent event) {
            lock.lock();
            try {
                long seq = nextSeq++;
                int slot = (int) (totalEnqueued % RING_BUFFER_CAPACITY);
                ring[slot] = new SequencedEvent(seq, Instant.now(), event);
                totalEnqueued++;
                // When the buffer wraps, the minimum seq in the buffer advances
                if (totalEnqueued > RING_BUFFER_CAPACITY) {
                    minSeqInBuffer = nextSeq - RING_BUFFER_CAPACITY;
                }
                hasNewEvents.signalAll();
            } finally {
                lock.unlock();
            }
        }

        /**
         * Drain events with seq > afterCursor. Park up to {@code waitMs} if none are ready.
         */
        public PollResult poll(long afterCursor, long waitMs) {
            lastConsumedMs = System.currentTimeMillis();
            lock.lock();
            try {
                // Park if no events available yet
                if (nextSeq <= afterCursor + 1 && waitMs > 0) {
                    long deadlineNs = System.nanoTime() + waitMs * 1_000_000L;
                    while (nextSeq <= afterCursor + 1) {
                        long remainingNs = deadlineNs - System.nanoTime();
                        if (remainingNs <= 0) break;
                        try {
                            hasNewEvents.awaitNanos(remainingNs);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }

                // Collect events with seq > afterCursor from the ring buffer
                List<SequencedEvent> results = new ArrayList<>();
                boolean overflow = false;

                if (nextSeq == 0) {
                    // No events ever — preserve the caller's cursor position
                    return new PollResult(List.of(), afterCursor, false);
                }

                // Determine range of seqs available in the buffer
                long bufStart = minSeqInBuffer;
                long bufEnd = nextSeq - 1; // inclusive

                // If afterCursor is behind the start of our buffer, some events were dropped
                long readFrom = afterCursor + 1;
                if (readFrom < bufStart) {
                    overflow = true;
                    readFrom = bufStart;
                }

                for (long seq = readFrom; seq <= bufEnd; seq++) {
                    int slot = (int) (seq % RING_BUFFER_CAPACITY);
                    SequencedEvent se = ring[slot];
                    if (se != null && se.seq() == seq) {
                        results.add(se);
                    }
                }

                long newCursor = results.isEmpty() ? afterCursor : results.get(results.size() - 1).seq();
                lastConsumedMs = System.currentTimeMillis();
                return new PollResult(List.copyOf(results), newCursor, overflow);
            } finally {
                lock.unlock();
            }
        }

        /** Unblock any threads parked in poll (called on cancel/eviction). */
        void signalAll() {
            lock.lock();
            try {
                hasNewEvents.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }
}
