package ai.kompile.cli.main.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduledLoopManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesCompoundIntervalsAndRejectsTooShortProductionIntervals() {
        assertEquals(9_000_000L, ScheduledLoopManager.parseIntervalMs("2h30m"));

        ScheduledLoopManager manager = new ScheduledLoopManager(ignored -> { });
        try {
            assertNull(manager.create("4s", "too frequent"));
            assertNotNull(manager.create("5s", "valid prompt"));
            assertEquals(1, manager.clear());
            assertEquals(0, manager.clear(), "clear must be idempotent");
            assertTrue(manager.list().isEmpty());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void intervalFiresLocallyAndCallbackFailureDoesNotKillSchedule() throws Exception {
        CountDownLatch twoFires = new CountDownLatch(2);
        AtomicInteger attempts = new AtomicInteger();
        List<RuntimeException> failures = new CopyOnWriteArrayList<>();
        RuntimeException failure = new IllegalStateException("target session closed");
        ScheduledLoopManager manager = new ScheduledLoopManager(prompt -> {
            int attempt = attempts.incrementAndGet();
            twoFires.countDown();
            if (attempt == 1) throw failure;
        }, tempDir.resolve("firing.json"), 1, (loop, error) -> {
            failures.add(error);
            throw new IllegalStateException("reporter unavailable");
        });
        try {
            assertNotNull(manager.create("1s", "check status"));
            assertTrue(twoFires.await(3, TimeUnit.SECONDS),
                    "a failed callback must not terminate the recurring schedule");
            assertTrue(attempts.get() >= 2);
            assertEquals(List.of(failure), failures,
                    "scheduled failure must be reported even when the reporter itself throws");
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void manualDispatchFailureReturnsFalseWithoutOptionalReporter() {
        AtomicInteger attempts = new AtomicInteger();
        ScheduledLoopManager manager = new ScheduledLoopManager(prompt -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("target session closed");
        });
        try {
            ScheduledLoopManager.ScheduledLoop loop = manager.create("1h", "fixed target");
            assertNotNull(loop);
            assertFalse(manager.runNow(loop.getId()));
            assertEquals(1, attempts.get(), "failed dispatch must not be retried or retargeted");
            assertEquals(1, loop.getFireCount());
            assertNotNull(loop.getLastFiredAt());
            assertEquals(ScheduledLoopManager.ScheduledLoop.LoopStatus.ACTIVE, loop.getStatus());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void manualDispatchFailureReportsOriginalErrorAndPersistsAttempt() {
        Path stateFile = tempDir.resolve("failed-attempt.json");
        RuntimeException failure = new IllegalStateException("target session closed");
        List<ScheduledLoopManager.ScheduledLoop> failedLoops = new CopyOnWriteArrayList<>();
        List<RuntimeException> failures = new CopyOnWriteArrayList<>();
        List<Integer> reportedCounts = new CopyOnWriteArrayList<>();
        ScheduledLoopManager manager = new ScheduledLoopManager(prompt -> {
            throw failure;
        }, stateFile, (loop, error) -> {
            failedLoops.add(loop);
            failures.add(error);
            reportedCounts.add(loop.getFireCount());
        });
        String id;
        Instant attemptedAt;
        try {
            ScheduledLoopManager.ScheduledLoop loop = manager.create("1h", "fixed target");
            assertNotNull(loop);
            id = loop.getId();
            assertTrue(manager.pause(id));
            assertFalse(manager.runNow(id));
            assertEquals(1, failedLoops.size());
            assertSame(loop, failedLoops.get(0));
            assertSame(failure, failures.get(0));
            assertEquals(List.of(1), reportedCounts, "accounting must precede failure reporting");
            attemptedAt = loop.getLastFiredAt();
            assertNotNull(attemptedAt);
        } finally {
            manager.shutdown();
        }

        ScheduledLoopManager restored = new ScheduledLoopManager(ignored -> { }, stateFile);
        try {
            ScheduledLoopManager.ScheduledLoop loop = restored.get(id);
            assertNotNull(loop);
            assertEquals(1, loop.getFireCount(), "failed attempts must not be rolled back");
            assertEquals(attemptedAt, loop.getLastFiredAt());
            assertEquals(ScheduledLoopManager.ScheduledLoop.LoopStatus.PAUSED, loop.getStatus());
        } finally {
            restored.shutdown();
        }
    }

    @Test
    void reporterFailureDoesNotMaskManualResultAndSuccessDoesNotReportFailure() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger reports = new AtomicInteger();
        ScheduledLoopManager manager = new ScheduledLoopManager(prompt -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("target session closed");
            }
        }, null, (loop, error) -> {
            reports.incrementAndGet();
            throw new IllegalStateException("reporter unavailable");
        });
        try {
            ScheduledLoopManager.ScheduledLoop loop = manager.create("1h", "fixed target");
            assertNotNull(loop);
            assertFalse(manager.runNow(loop.getId()));
            assertTrue(manager.runNow(loop.getId()));
            assertEquals(2, attempts.get());
            assertEquals(2, loop.getFireCount());
            assertEquals(1, reports.get(), "successful dispatch must not report a failure");
            assertTrue(manager.remove(loop.getId()));
            assertFalse(manager.runNow(loop.getId()));
            assertEquals(2, attempts.get());
            assertEquals(1, reports.get(), "an absent loop is not a dispatch failure");
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void clearCancelsPendingScheduledCallbacks() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        ScheduledLoopManager manager = new ScheduledLoopManager(
                ignored -> attempts.incrementAndGet(), null, 1);
        try {
            assertNotNull(manager.create("1s", "must not fire"));
            assertEquals(1, manager.clear());
            Thread.sleep(1_200);
            assertEquals(0, attempts.get());
            assertEquals(0, manager.activeCount());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void queuedInvocationCannotFireAfterClear() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        ScheduledLoopManager manager = new ScheduledLoopManager(
                ignored -> attempts.incrementAndGet(), null, 1);
        try {
            ScheduledLoopManager.ScheduledLoop loop = manager.create("1h", "stale callback");
            assertNotNull(loop);
            assertEquals(1, manager.clear());

            Method fire = ScheduledLoopManager.class.getDeclaredMethod(
                    "fire", ScheduledLoopManager.ScheduledLoop.class, boolean.class);
            fire.setAccessible(true);
            assertFalse((boolean) fire.invoke(manager, loop, false));
            assertEquals(0, attempts.get());
            assertEquals(0, loop.getFireCount());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void schedulesPersistAndRestorePausedState() throws Exception {
        Path stateFile = tempDir.resolve("persisted-loops.json");
        List<String> fired = new CopyOnWriteArrayList<>();
        String id;

        ScheduledLoopManager first = new ScheduledLoopManager(fired::add, stateFile, 1);
        try {
            ScheduledLoopManager.ScheduledLoop loop = first.create("1h", "review local changes");
            assertNotNull(loop);
            id = loop.getId();
            assertTrue(first.pause(id.substring(0, 4)));
            assertTrue(first.runNow(id));
            assertEquals(List.of("review local changes"), fired);
        } finally {
            first.shutdown();
        }

        assertTrue(Files.isRegularFile(stateFile));
        ScheduledLoopManager restored = new ScheduledLoopManager(fired::add, stateFile, 1);
        try {
            ScheduledLoopManager.ScheduledLoop loop = restored.get(id);
            assertNotNull(loop);
            assertEquals(ScheduledLoopManager.ScheduledLoop.LoopStatus.PAUSED, loop.getStatus());
            assertEquals(1, loop.getFireCount());
            assertTrue(restored.resume(id.substring(0, 4)));
            assertTrue(restored.remove(id.substring(0, 4)));
        } finally {
            restored.shutdown();
        }

        ScheduledLoopManager empty = new ScheduledLoopManager(fired::add, stateFile, 1);
        try {
            assertTrue(empty.list().isEmpty());
        } finally {
            empty.shutdown();
        }
    }

    @Test
    void sharedStateMergesManagersAndDoesNotResurrectRemovedLoops() {
        Path stateFile = tempDir.resolve("shared-project-loops.json");
        List<String> fired = new CopyOnWriteArrayList<>();
        ScheduledLoopManager first = new ScheduledLoopManager(fired::add, stateFile, 1);
        ScheduledLoopManager second = null;
        ScheduledLoopManager restored = null;
        try {
            ScheduledLoopManager.ScheduledLoop original =
                    first.create("1h", "original project loop");
            assertNotNull(original);

            // The second process starts with only the original entry in memory.
            second = new ScheduledLoopManager(fired::add, stateFile, 1);
            ScheduledLoopManager.ScheduledLoop lateFirst =
                    first.create("2h", "late first-process loop");
            ScheduledLoopManager.ScheduledLoop secondOwned =
                    second.create("3h", "second-process loop");
            assertNotNull(lateFirst);
            assertNotNull(secondOwned);

            assertTrue(first.runNow(original.getId()));
            assertTrue(second.pause(original.getId()));
            assertEquals(1, second.get(original.getId()).getFireCount(),
                    "a stale status update must retain another process's fire count");
            assertTrue(second.resume(original.getId()));
            assertTrue(first.runNow(lateFirst.getId()));
            assertTrue(second.pause(secondOwned.getId()));
            assertTrue(first.remove(original.getId()));
            assertFalse(second.pause(original.getId()),
                    "a stale process must not recreate a removed loop while pausing it");
            assertFalse(second.runNow(original.getId()),
                    "a stale process must not resurrect a loop removed elsewhere");

            restored = new ScheduledLoopManager(fired::add, stateFile, 1);
            assertNull(restored.get(original.getId()));
            assertEquals(2, restored.list().size());
            assertEquals(1, restored.get(lateFirst.getId()).getFireCount());
            assertEquals(ScheduledLoopManager.ScheduledLoop.LoopStatus.PAUSED,
                    restored.get(secondOwned.getId()).getStatus());
            assertEquals(List.of("original project loop", "late first-process loop"), fired);
        } finally {
            first.shutdown();
            if (second != null) second.shutdown();
            if (restored != null) restored.shutdown();
        }
    }

    @Test
    void clearRemovesAllPersistedLoopsAcrossManagers() {
        Path stateFile = tempDir.resolve("clear-shared-loops.json");
        ScheduledLoopManager first = new ScheduledLoopManager(
                ignored -> { }, stateFile, 1);
        ScheduledLoopManager second = null;
        ScheduledLoopManager restored = null;
        try {
            ScheduledLoopManager.ScheduledLoop original =
                    first.create("1h", "original loop");
            assertNotNull(original);

            second = new ScheduledLoopManager(ignored -> { }, stateFile, 1);
            ScheduledLoopManager.ScheduledLoop lateFirst =
                    first.create("2h", "late first-process loop");
            ScheduledLoopManager.ScheduledLoop secondOwned =
                    second.create("3h", "second-process loop");
            assertNotNull(lateFirst);
            assertNotNull(secondOwned);

            assertEquals(3, second.clear(),
                    "clear must include persisted loops not loaded by this manager");
            assertEquals(0, second.clear(), "persisted clear must be idempotent");
            assertTrue(second.list().isEmpty());
            assertFalse(first.runNow(original.getId()));
            assertFalse(first.runNow(lateFirst.getId()));
            assertTrue(first.list().isEmpty(),
                    "a stale manager must discard loops cleared by another process");

            restored = new ScheduledLoopManager(ignored -> { }, stateFile, 1);
            assertTrue(restored.list().isEmpty());
        } finally {
            first.shutdown();
            if (second != null) second.shutdown();
            if (restored != null) restored.shutdown();
        }
    }

    @Test
    void stateFilesAreStableAndKeepSessionAndProjectScopesSeparate() {
        Path firstProject = ScheduledLoopManager.stateFileForProject(tempDir.resolve("project-a"));
        Path sameProject = ScheduledLoopManager.stateFileForProject(
                tempDir.resolve("project-a/../project-a"));
        Path secondProject = ScheduledLoopManager.stateFileForProject(tempDir.resolve("project-b"));
        Path firstSession = ScheduledLoopManager.stateFileForSession("session-a");
        Path sameSession = ScheduledLoopManager.stateFileForSession("session-a");
        Path secondSession = ScheduledLoopManager.stateFileForSession("session-b");

        assertEquals(firstProject, sameProject);
        assertNotEquals(firstProject, secondProject);
        assertTrue(firstProject.toString().contains("scheduled-loops"));
        assertEquals(firstSession, sameSession);
        assertNotEquals(firstSession, secondSession);
        assertTrue(firstSession.toString().contains("conversations"));
        assertTrue(firstSession.toString().endsWith("session-a.loops.json"));
        assertFalse(firstProject.equals(firstSession));
    }
}
