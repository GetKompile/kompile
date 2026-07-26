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

import ai.kompile.app.services.grounding.KbFactSubscriptionService.KbEvent;
import ai.kompile.app.services.grounding.KbFactSubscriptionService.KbSubscription;
import ai.kompile.app.services.grounding.KbFactSubscriptionService.PollResult;
import ai.kompile.app.services.grounding.KbFactSubscriptionService.SequencedEvent;
import ai.kompile.knowledgegraph.grounding.AgentFactAssertedEvent;
import ai.kompile.knowledgegraph.grounding.AgentFactRetractedEvent;
import ai.kompile.graphchangetracking.event.GraphBatchMutationEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link KbFactSubscriptionService}.
 *
 * <p>No Spring context — exercises the service directly.</p>
 */
@DisplayName("KbFactSubscriptionService")
class KbFactSubscriptionServiceTest {

    private KbFactSubscriptionService service;

    @BeforeEach
    void setUp() {
        service = new KbFactSubscriptionService();
    }

    // ── Subscription lifecycle ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Subscription lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("createSubscription returns non-null id with expiresAt")
        void createReturnsDescriptor() {
            KbSubscription sub = service.createSubscription(42L, List.of("worksFor"));
            assertNotNull(sub.subscriptionId());
            assertFalse(sub.subscriptionId().isBlank());
            assertNotNull(sub.expiresAt());
            assertEquals(42L, sub.factSheetId());
            assertEquals(List.of("worksFor"), sub.predicates());
        }

        @Test
        @DisplayName("get returns present for live subscription")
        void getReturnsLiveSubscription() {
            KbSubscription sub = service.createSubscription(1L, List.of());
            assertTrue(service.get(sub.subscriptionId()).isPresent());
        }

        @Test
        @DisplayName("get returns empty for unknown id")
        void getReturnsEmptyForUnknown() {
            assertTrue(service.get("no-such-id").isEmpty());
        }

        @Test
        @DisplayName("cancel removes subscription; get returns empty after cancel")
        void cancelRemovesSubscription() {
            KbSubscription sub = service.createSubscription(1L, List.of());
            service.cancel(sub.subscriptionId());
            assertTrue(service.get(sub.subscriptionId()).isEmpty());
        }
    }

    // ── Event routing ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Event routing via @EventListener")
    class EventRouting {

        @Test
        @DisplayName("AgentFactAssertedEvent routes to matching subscription")
        void assertedEventReachesSubscription() {
            KbSubscription sub = service.createSubscription(10L, List.of("trusts"));
            AgentFactAssertedEvent event = new AgentFactAssertedEvent(
                    this, 10L, "trusts(Alice, Bob)", 0.9, "sess-1");
            service.onFactAsserted(event);

            PollResult result = service.poll(sub.subscriptionId(), -1, 0);
            assertEquals(1, result.events().size());
            SequencedEvent se = result.events().get(0);
            assertEquals("asserted", se.event().type());
            assertEquals("trusts(Alice, Bob)", se.event().atomKey());
            assertEquals(0.9, se.event().value(), 1e-9);
            assertEquals("sess-1", se.event().source());
        }

        @Test
        @DisplayName("AgentFactRetractedEvent routes to matching subscription")
        void retractedEventReachesSubscription() {
            KbSubscription sub = service.createSubscription(10L, List.of("trusts"));
            AgentFactRetractedEvent event = new AgentFactRetractedEvent(
                    this, 10L, "trusts(Alice, Bob)", 2, 0);
            service.onFactRetracted(event);

            PollResult result = service.poll(sub.subscriptionId(), -1, 0);
            assertEquals(1, result.events().size());
            assertEquals("retracted", result.events().get(0).event().type());
        }

        @Test
        @DisplayName("Predicate filtering: non-matching predicate is not delivered")
        void predicateFilterExcludes() {
            KbSubscription sub = service.createSubscription(10L, List.of("trusts"));
            AgentFactAssertedEvent event = new AgentFactAssertedEvent(
                    this, 10L, "worksFor(Alice, Acme)", 1.0, null);
            service.onFactAsserted(event);

            PollResult result = service.poll(sub.subscriptionId(), -1, 0);
            assertTrue(result.events().isEmpty(), "worksFor event must not reach trusts-only subscription");
        }

        @Test
        @DisplayName("Empty predicate list matches all predicates on the fact sheet")
        void emptyPredicateMatchesAll() {
            KbSubscription sub = service.createSubscription(10L, List.of()); // no filter
            AgentFactAssertedEvent e1 = new AgentFactAssertedEvent(this, 10L, "trusts(A,B)", 1.0, null);
            AgentFactAssertedEvent e2 = new AgentFactAssertedEvent(this, 10L, "worksFor(A,C)", 1.0, null);
            service.onFactAsserted(e1);
            service.onFactAsserted(e2);

            PollResult result = service.poll(sub.subscriptionId(), -1, 0);
            assertEquals(2, result.events().size());
        }

        @Test
        @DisplayName("Fact-sheet isolation: event for sheet 99 not delivered to sheet 10 subscription")
        void factSheetIsolation() {
            KbSubscription sub = service.createSubscription(10L, List.of());
            AgentFactAssertedEvent event = new AgentFactAssertedEvent(
                    this, 99L, "trusts(X,Y)", 1.0, null);
            service.onFactAsserted(event);

            PollResult result = service.poll(sub.subscriptionId(), -1, 0);
            assertTrue(result.events().isEmpty());
        }

        @Test
        @DisplayName("GraphBatchMutationEvent delivers 'changed' event to matching subscription")
        void batchMutationEventDelivered() {
            KbSubscription sub = service.createSubscription(5L, List.of());
            GraphBatchMutationEvent batch = new GraphBatchMutationEvent(
                    this, "NODES_CREATED", 5L, 3, "cs-001", "crawl");
            service.onBatchMutation(batch);

            PollResult result = service.poll(sub.subscriptionId(), -1, 0);
            assertEquals(1, result.events().size());
            assertEquals("changed", result.events().get(0).event().type());
        }

        @Test
        @DisplayName("GraphBatchMutationEvent with null factSheetId is ignored")
        void batchMutationNullSheetIgnored() {
            KbSubscription sub = service.createSubscription(5L, List.of());
            GraphBatchMutationEvent batch = new GraphBatchMutationEvent(
                    this, "NODES_CREATED", null, 3, "cs-001", "crawl");
            service.onBatchMutation(batch);

            PollResult result = service.poll(sub.subscriptionId(), -1, 0);
            assertTrue(result.events().isEmpty());
        }
    }

    // ── Cursor semantics ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Cursor semantics")
    class CursorSemantics {

        @Test
        @DisplayName("Cursor -1 returns all buffered events on first poll")
        void cursorMinusOneReturnAll() {
            KbSubscription sub = service.createSubscription(1L, List.of());
            enqueueAsserted(sub, "a(x)", 1);
            enqueueAsserted(sub, "b(x)", 2);

            PollResult result = service.poll(sub.subscriptionId(), -1, 0);
            assertEquals(2, result.events().size());
            assertEquals(0L, result.events().get(0).seq());
            assertEquals(1L, result.events().get(1).seq());
        }

        @Test
        @DisplayName("Poll with cursor=0 returns only events with seq > 0")
        void cursorSkipsProcessedEvents() {
            KbSubscription sub = service.createSubscription(1L, List.of());
            enqueueAsserted(sub, "a(x)", 1);
            enqueueAsserted(sub, "b(x)", 2);
            enqueueAsserted(sub, "c(x)", 3);

            // Drain first two
            PollResult first = service.poll(sub.subscriptionId(), -1, 0);
            assertEquals(3, first.events().size());
            long cursor = first.nextCursor(); // should be 2

            // Enqueue one more
            enqueueAsserted(sub, "d(x)", 4);

            PollResult second = service.poll(sub.subscriptionId(), cursor, 0);
            assertEquals(1, second.events().size());
            assertEquals("d(x)", second.events().get(0).event().atomKey());
        }

        @Test
        @DisplayName("nextCursor is the seq of the last returned event")
        void nextCursorIsLastSeq() {
            KbSubscription sub = service.createSubscription(1L, List.of());
            enqueueAsserted(sub, "a(x)", 1);
            enqueueAsserted(sub, "b(x)", 2);

            PollResult result = service.poll(sub.subscriptionId(), -1, 0);
            assertEquals(1L, result.nextCursor()); // last seq is 1
        }

        @Test
        @DisplayName("Empty poll (no events, no wait) returns nextCursor = afterCursor")
        void emptyPollReturnsSameCursor() {
            KbSubscription sub = service.createSubscription(1L, List.of());
            PollResult result = service.poll(sub.subscriptionId(), 5L, 0);
            assertTrue(result.events().isEmpty());
            assertEquals(5L, result.nextCursor());
        }

        @Test
        @DisplayName("Gap-free drain: consecutive polls with returned cursors produce all events")
        void gapFreeDrain() {
            KbSubscription sub = service.createSubscription(1L, List.of());
            for (int i = 0; i < 5; i++) {
                enqueueAsserted(sub, "fact" + i + "(x)", i);
            }

            long cursor = -1;
            int totalDrained = 0;
            for (int call = 0; call < 5; call++) {
                PollResult r = service.poll(sub.subscriptionId(), cursor, 0);
                if (r.events().isEmpty()) break;
                totalDrained += r.events().size();
                cursor = r.nextCursor();
                // Enqueue next after first drain to simulate interleaved arrivals
                if (call == 0) {
                    for (int i = 5; i < 10; i++) {
                        enqueueAsserted(sub, "fact" + i + "(x)", i);
                    }
                }
            }
            // At minimum all 10 were enqueued; drain all
            PollResult rest = service.poll(sub.subscriptionId(), cursor, 0);
            totalDrained += rest.events().size();
            assertTrue(totalDrained >= 10, "Must drain all 10 events, got " + totalDrained);
        }
    }

    // ── Ring buffer overflow ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Ring buffer overflow")
    class RingBufferOverflow {

        @Test
        @DisplayName("Overflow flag set when consumer cursor is behind buffer start")
        void overflowFlagSetOnDrop() {
            KbSubscription sub = service.createSubscription(1L, List.of());

            // Enqueue more than ring buffer capacity (500 + 1 = 501 events)
            int overCount = KbFactSubscriptionService.RING_BUFFER_CAPACITY + 1;
            for (int i = 0; i < overCount; i++) {
                sub.enqueue(new KbEvent("asserted", 1L, "fact" + i + "(x)", 1.0, null));
            }

            // Poll from seq=-1 (before the start of what's now in the buffer)
            PollResult result = service.poll(sub.subscriptionId(), -1, 0);
            assertTrue(result.overflow(), "overflow must be true when earliest seq was dropped");
            // Should get exactly RING_BUFFER_CAPACITY events (the ones still in the buffer)
            assertEquals(KbFactSubscriptionService.RING_BUFFER_CAPACITY, result.events().size());
        }
    }

    // ── Long-poll parking ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Long-poll: park and wake")
    class LongPoll {

        @Test
        @DisplayName("poll returns immediately when events are already available")
        void immediateReturnWhenEventsAvailable() {
            KbSubscription sub = service.createSubscription(1L, List.of());
            enqueueAsserted(sub, "ready(x)", 1);

            long start = System.currentTimeMillis();
            PollResult result = service.poll(sub.subscriptionId(), -1, 10_000);
            long elapsed = System.currentTimeMillis() - start;

            assertFalse(result.events().isEmpty());
            assertTrue(elapsed < 2_000, "Must return immediately when events exist, took " + elapsed + "ms");
        }

        @Test
        @DisplayName("poll returns empty after timeout when no events arrive")
        void timeoutWhenNoEvents() {
            KbSubscription sub = service.createSubscription(1L, List.of());

            long start = System.currentTimeMillis();
            PollResult result = service.poll(sub.subscriptionId(), -1, 50); // 50ms timeout
            long elapsed = System.currentTimeMillis() - start;

            assertTrue(result.events().isEmpty());
            // Should have waited ~50ms but not significantly more
            assertTrue(elapsed >= 40, "Should wait at least 40ms, but took " + elapsed + "ms");
        }

        @Test
        @DisplayName("poll wakes up when event arrives during wait")
        void wakesUpOnNewEvent() throws InterruptedException {
            KbSubscription sub = service.createSubscription(1L, List.of());

            // Start poll in a background thread with a 5s wait
            long[] elapsed = {-1};
            List<SequencedEvent>[] got = new List[]{null};
            Thread pollThread = new Thread(() -> {
                long start = System.currentTimeMillis();
                PollResult r = service.poll(sub.subscriptionId(), -1, 5_000);
                elapsed[0] = System.currentTimeMillis() - start;
                got[0] = r.events();
            });
            pollThread.start();

            // Inject an event after 100ms
            Thread.sleep(100);
            enqueueAsserted(sub, "wakeup(x)", 1);

            pollThread.join(3_000);
            assertFalse(pollThread.isAlive(), "poll thread should have exited");
            assertNotNull(got[0]);
            assertFalse(got[0].isEmpty(), "poll should have returned the injected event");
            assertTrue(elapsed[0] < 2_000, "poll should have returned well before 5s timeout");
        }
    }

    // ── TTL eviction ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("TTL eviction")
    class TtlEviction {

        @Test
        @DisplayName("Non-expired subscription is not evicted")
        void nonExpiredIsNotEvicted() {
            KbSubscription sub = service.createSubscription(1L, List.of());
            service.evictIdleSubscriptions();
            assertTrue(service.get(sub.subscriptionId()).isPresent());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /** Directly enqueue a fake assert event into a subscription (bypass event routing for unit tests). */
    private void enqueueAsserted(KbSubscription sub, String atomKey, double value) {
        sub.enqueue(new KbEvent("asserted", sub.factSheetId(), atomKey, value, null));
    }
}
