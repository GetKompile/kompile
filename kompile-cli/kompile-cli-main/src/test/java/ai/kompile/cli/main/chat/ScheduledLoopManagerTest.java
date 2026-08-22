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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void intervalFiresLocallyAndCallbackFailureDoesNotKillSchedule() throws Exception {
        CountDownLatch twoFires = new CountDownLatch(2);
        AtomicInteger attempts = new AtomicInteger();
        ScheduledLoopManager manager = new ScheduledLoopManager(prompt -> {
            int attempt = attempts.incrementAndGet();
            twoFires.countDown();
            if (attempt == 1) throw new IllegalStateException("synthetic dispatch failure");
        }, tempDir.resolve("firing.json"), 1);
        try {
            assertNotNull(manager.create("1s", "check status"));
            assertTrue(twoFires.await(3, TimeUnit.SECONDS),
                    "a failed callback must not terminate the recurring schedule");
            assertTrue(attempts.get() >= 2);
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
    void projectStateFilesAreStableAndProjectScoped() {
        Path first = ScheduledLoopManager.stateFileForProject(tempDir.resolve("project-a"));
        Path same = ScheduledLoopManager.stateFileForProject(tempDir.resolve("project-a/../project-a"));
        Path second = ScheduledLoopManager.stateFileForProject(tempDir.resolve("project-b"));

        assertEquals(first, same);
        assertNotEquals(first, second);
        assertTrue(first.toString().contains("scheduled-loops"));
        assertFalse(first.equals(second));
    }
}
