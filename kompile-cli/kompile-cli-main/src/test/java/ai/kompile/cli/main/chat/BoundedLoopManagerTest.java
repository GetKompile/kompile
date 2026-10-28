package ai.kompile.cli.main.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedLoopManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void bareNumberIsACountBoundAndDurationsAreTimeBounds() {
        assertTrue(BoundedLoopManager.isCountBound("10"));
        assertTrue(BoundedLoopManager.isCountBound("3"));
        assertFalse(BoundedLoopManager.isCountBound("1h"));
        assertFalse(BoundedLoopManager.isCountBound("2h30m"));
        assertFalse(BoundedLoopManager.isCountBound("abc"));
        assertFalse(BoundedLoopManager.isCountBound(null));
        // Interval parsing is shared with ScheduledLoopManager.
        assertEquals(9_000_000L, ScheduledLoopManager.parseIntervalMs("2h30m"));
    }

    @Test
    void countBoundStopsAfterExactFiresAndReportsCompletionOnce() throws Exception {
        CountDownLatch threeFires = new CountDownLatch(3);
        AtomicInteger fires = new AtomicInteger();
        AtomicReference<BoundedLoopManager.BoundedLoop> completed = new AtomicReference<>();
        BoundedLoopManager manager = new BoundedLoopManager(prompt -> {
            fires.incrementAndGet();
            threeFires.countDown();
        }, completed::set, null, 1);
        try {
            BoundedLoopManager.BoundedLoop loop = manager.create("1s", "3", "bounded prompt");
            assertNotNull(loop);
            assertEquals(BoundedLoopManager.BoundedLoop.BoundType.COUNT, loop.getBoundType());
            assertEquals(3, loop.getBoundCount());
            assertEquals("×3", loop.getFormattedBound());

            assertTrue(threeFires.await(5, TimeUnit.SECONDS), "loop must fire three times");
            awaitCompletion(loop);
            // Fourth fire must never happen: confirm no growth after completion.
            long afterThird = fires.get();
            Thread.sleep(1500);
            assertEquals(afterThird, fires.get(), "a completed count loop must not fire again");
            assertEquals(BoundedLoopManager.BoundedLoop.LoopStatus.COMPLETED, loop.getStatus());
            assertNotNull(completed.get(), "completion callback must fire exactly once");
            assertEquals(loop.getId(), completed.get().getId());
            assertTrue(loop.getCompletionReason().contains("3 fires"),
                    "reason should explain the count bound: " + loop.getCompletionReason());
            assertNotNull(loop.getCompletedAt());
            assertEquals(0, loop.getRemainingFires());
        } finally {
            manager.shutdown();
        }
    }

    /**
     * The fire latch counts down inside the dispatch callback, before the manager's
     * post-callback bound check runs; completion is observed with a bounded wait.
     */
    private static void awaitCompletion(BoundedLoopManager.BoundedLoop loop)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (loop.getStatus() != BoundedLoopManager.BoundedLoop.LoopStatus.COMPLETED
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    @Test
    void durationBoundEndsTheLoopAtItsDeadlineEvenBetweenTicks() throws Exception {
        CountDownLatch fired = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<BoundedLoopManager.BoundedLoop> finished = new AtomicReference<>();
        BoundedLoopManager manager = new BoundedLoopManager(prompt ->
                fired.countDown(), loop -> {
            finished.set(loop);
            completed.countDown();
        }, null, 1);
        try {
            BoundedLoopManager.BoundedLoop loop = manager.create("1s", "2s", "watch the clock");
            assertNotNull(loop);
            assertEquals(BoundedLoopManager.BoundedLoop.BoundType.DURATION, loop.getBoundType());
            assertNotNull(loop.getDeadline());

            assertTrue(fired.await(3, TimeUnit.SECONDS), "loop must fire before its deadline");
            assertTrue(completed.await(2, TimeUnit.SECONDS),
                    "deadline guard must complete the loop even between ticks");
            assertEquals(loop.getId(), finished.get().getId());
            assertEquals(BoundedLoopManager.BoundedLoop.LoopStatus.COMPLETED, loop.getStatus());
            assertTrue(loop.getCompletionReason().contains("duration"),
                    "reason should explain the duration bound: " + loop.getCompletionReason());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void completionCallbackFailureMustNotBreakOtherLoopsOrCompletion() throws Exception {
        CountDownLatch threeFires = new CountDownLatch(3);
        AtomicInteger fires = new AtomicInteger();
        BoundedLoopManager manager = new BoundedLoopManager(prompt -> {
            fires.incrementAndGet();
            threeFires.countDown();
        }, loop -> {
            throw new IllegalStateException("reporter unavailable");
        }, null, 1);
        try {
            BoundedLoopManager.BoundedLoop loop = manager.create("1s", "3", "resilient prompt");
            assertNotNull(loop);
            assertTrue(threeFires.await(5, TimeUnit.SECONDS));
            awaitCompletion(loop);
            assertEquals(BoundedLoopManager.BoundedLoop.LoopStatus.COMPLETED, loop.getStatus());
            assertNotNull(loop.getCompletionReason());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void invalidIntervalsBoundsAndPromptsAreRejected() {
        BoundedLoopManager manager = new BoundedLoopManager(ignored -> { }, loop -> { });
        try {
            assertNull(manager.create("4s", "10", "too frequent"));   // below 5s minimum
            assertNull(manager.create("5m", "0", "zero count bound"));
            assertNull(manager.create("5m", "abc", "unrecognized bound"));
            assertNull(manager.create("5m", null, "missing bound"));
            assertNull(manager.create("5m", "10", "  "));             // blank prompt
            assertNull(manager.create("abc", "10", "unrecognized interval"));
            assertTrue(manager.list().isEmpty(), "invalid creates must leave no state");
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void pauseResumeAndRemoveFollowScheduledLoopSemantics() {
        BoundedLoopManager manager = new BoundedLoopManager(ignored -> { }, loop -> { });
        try {
            BoundedLoopManager.BoundedLoop loop = manager.create("1h", "10", "lifecycle");
            assertNotNull(loop);
            String prefix = loop.getId().substring(0, 4);

            assertTrue(manager.pause(prefix));
            assertEquals(BoundedLoopManager.BoundedLoop.LoopStatus.PAUSED, loop.getStatus());
            assertFalse(manager.pause(prefix), "pausing a paused loop fails");

            assertTrue(manager.resume(prefix));
            assertEquals(BoundedLoopManager.BoundedLoop.LoopStatus.ACTIVE, loop.getStatus());
            assertFalse(manager.resume(prefix), "resuming an active loop fails");

            assertTrue(manager.runNow(prefix));
            assertEquals(1, loop.getFireCount(), "runNow counts as an attempt");

            assertTrue(manager.remove(prefix));
            assertNull(manager.get(prefix));
            assertTrue(manager.list().isEmpty());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void resumedDurationLoopPastItsDeadlineCompletesImmediately() {
        BoundedLoopManager manager = new BoundedLoopManager(ignored -> { }, loop -> { });
        try {
            BoundedLoopManager.BoundedLoop loop = manager.create("1h", "1s", "expires while paused");
            assertNotNull(loop);
            String prefix = loop.getId().substring(0, 4);
            assertTrue(manager.pause(prefix));
            try {
                Thread.sleep(1100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            assertTrue(manager.resume(prefix));
            assertEquals(BoundedLoopManager.BoundedLoop.LoopStatus.COMPLETED, loop.getStatus());
            assertTrue(loop.getCompletionReason().contains("while paused"),
                    "reason should mention the pause: " + loop.getCompletionReason());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void persistenceSurvivesRestartAndExpiredDurationsDoNotReload() throws Exception {
        Path stateFile = tempDir.resolve("bounded.json");
        String countId;
        BoundedLoopManager first = new BoundedLoopManager(
                ignored -> { }, loop -> { }, stateFile);
        try {
            BoundedLoopManager.BoundedLoop count = first.create("1h", "5", "count persists");
            assertNotNull(count);
            countId = count.getId();
            BoundedLoopManager.BoundedLoop duration = first.create("1h", "2s", "expires");
            assertNotNull(duration);
            try {
                Thread.sleep(2100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            assertEquals(BoundedLoopManager.BoundedLoop.LoopStatus.COMPLETED, duration.getStatus());
        } finally {
            first.shutdown();
        }

        assertTrue(Files.isRegularFile(stateFile));
        BoundedLoopManager second = new BoundedLoopManager(
                ignored -> { }, loop -> { }, stateFile);
        try {
            List<BoundedLoopManager.BoundedLoop> loops = second.list();
            assertEquals(1, loops.size(),
                    "expired duration loops must not reload; count loops must");
            assertEquals(countId, loops.get(0).getId());
            assertEquals(5, loops.get(0).getBoundCount());
            assertEquals(BoundedLoopManager.BoundedLoop.LoopStatus.ACTIVE, loops.get(0).getStatus());
        } finally {
            second.shutdown();
        }
    }

    @Test
    void clearRemovesEverythingAndIsIdempotent() {
        BoundedLoopManager manager = new BoundedLoopManager(ignored -> { }, loop -> { });
        try {
            assertNotNull(manager.create("1h", "10", "first"));
            assertNotNull(manager.create("30m", "1h", "second"));
            assertEquals(2, manager.clear());
            assertEquals(0, manager.clear(), "clear must be idempotent");
            assertTrue(manager.list().isEmpty());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void dispatchFailureStillCountsTowardTheCountBoundAndReportsOnce() {
        List<BoundedLoopManager.BoundedLoop> failed = new CopyOnWriteArrayList<>();
        BoundedLoopManager manager = new BoundedLoopManager(prompt -> {
            throw new IllegalStateException("target session closed");
        }, loop -> { }, null, 1, (loop, error) -> failed.add(loop));
        try {
            BoundedLoopManager.BoundedLoop loop = manager.create("1h", "1", "fixed target");
            assertNotNull(loop);
            assertFalse(manager.runNow(loop.getId().substring(0, 4)),
                    "a failed dispatch returns false");
            // The single attempt exhausted the count bound even though dispatch failed.
            assertEquals(BoundedLoopManager.BoundedLoop.LoopStatus.COMPLETED, loop.getStatus());
            assertEquals(1, loop.getFireCount());
            assertEquals(1, failed.size());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void formattingShowsBoundProgressAndCompletion() {
        BoundedLoopManager manager = new BoundedLoopManager(ignored -> { }, loop -> { });
        try {
            BoundedLoopManager.BoundedLoop count = manager.create("5m", "10", "count format");
            assertNotNull(count);
            String countLine = BoundedLoopManager.formatBoundedLoop(count);
            assertTrue(countLine.contains("×10"), countLine);
            assertTrue(countLine.contains("fired 0/10"), countLine);
            assertEquals("10 of 10 remaining", count.getFormattedRemaining());

            BoundedLoopManager.BoundedLoop duration = manager.create("30m", "1h", "duration format");
            assertNotNull(duration);
            String durationLine = BoundedLoopManager.formatBoundedLoop(duration);
            assertTrue(durationLine.contains("for 1h"), durationLine);
            assertTrue(duration.getFormattedRemaining().endsWith("remaining"));

            assertTrue(manager.pause(duration.getId()));
            assertTrue(BoundedLoopManager.formatBoundedLoop(duration).contains("⏸"));
            assertTrue(manager.runNow(count.getId()));
            assertTrue(BoundedLoopManager.formatBoundedLoop(count).contains("fired 1/10"));
            assertEquals("9 of 10 remaining", count.getFormattedRemaining());
        } finally {
            manager.shutdown();
        }
    }
}
