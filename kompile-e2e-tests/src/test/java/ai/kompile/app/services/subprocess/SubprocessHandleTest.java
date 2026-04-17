package ai.kompile.app.services.subprocess;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SubprocessHandle")
class SubprocessHandleTest {

    @TempDir Path tempDir;

    private SubprocessHandle createHandle(Process process) {
        return new SubprocessHandle(
                "test-task",
                "test-file.pdf",
                process,
                null, null,
                new CompletableFuture<>(),
                tempDir.resolve("args.json")
        );
    }

    private Process startSleepProcess(int seconds) throws Exception {
        return new ProcessBuilder("sleep", String.valueOf(seconds)).start();
    }

    @Nested @DisplayName("Construction")
    class Construction {
        @Test void initialState() throws Exception {
            Process p = startSleepProcess(60);
            try {
                var handle = createHandle(p);
                assertEquals("test-task", handle.getTaskId());
                assertEquals("test-file.pdf", handle.getFileName());
                assertTrue(handle.getPid() > 0);
                assertTrue(handle.isAlive());
                assertFalse(handle.isCancelled());
                assertFalse(handle.isOomDetected());
                assertEquals("STARTING", handle.getCurrentPhase());
                assertEquals(0, handle.getProgressPercent());
                assertNotNull(handle.getStartTime());
                assertNotNull(handle.getLastHeartbeat());
            } finally {
                p.destroyForcibly();
            }
        }
    }

    @Nested @DisplayName("Progress tracking")
    class ProgressTracking {
        @Test void updateProgress() throws Exception {
            Process p = startSleepProcess(60);
            try {
                var handle = createHandle(p);
                handle.updateProgress("EMBEDDING", 50, "Batch 5/10");
                assertEquals("EMBEDDING", handle.getCurrentPhase());
                assertEquals(50, handle.getProgressPercent());
                assertEquals("Batch 5/10", handle.getLastMessage());
            } finally {
                p.destroyForcibly();
            }
        }

        @Test void setPhase() throws Exception {
            Process p = startSleepProcess(60);
            try {
                var handle = createHandle(p);
                handle.setCurrentPhase("INDEXING");
                assertEquals("INDEXING", handle.getCurrentPhase());
            } finally {
                p.destroyForcibly();
            }
        }
    }

    @Nested @DisplayName("Heartbeat")
    class Heartbeat {
        @Test void updateHeartbeat() throws Exception {
            Process p = startSleepProcess(60);
            try {
                var handle = createHandle(p);
                var before = handle.getLastHeartbeat();
                Thread.sleep(10);
                handle.updateHeartbeat();
                assertTrue(handle.getLastHeartbeat().isAfter(before));
            } finally {
                p.destroyForcibly();
            }
        }

        @Test void staleDetection() throws Exception {
            Process p = startSleepProcess(60);
            try {
                var handle = createHandle(p);
                assertFalse(handle.isStale(Duration.ofMinutes(5)));
                // Can't reliably test isStale=true without long waits
            } finally {
                p.destroyForcibly();
            }
        }
    }

    @Nested @DisplayName("OOM detection")
    class OomDetection {
        @Test void setAndGet() throws Exception {
            Process p = startSleepProcess(60);
            try {
                var handle = createHandle(p);
                assertFalse(handle.isOomDetected());
                handle.setOomDetected(true);
                assertTrue(handle.isOomDetected());
            } finally {
                p.destroyForcibly();
            }
        }
    }

    @Nested @DisplayName("Cancel")
    class Cancel {
        @Test void cancelTerminatesProcess() throws Exception {
            Process p = startSleepProcess(60);
            var handle = createHandle(p);
            assertTrue(handle.isAlive());
            handle.cancel();
            assertTrue(handle.isCancelled());
            // Process should terminate shortly after cancel
            int exit = handle.waitFor(Duration.ofSeconds(15));
            assertFalse(handle.isAlive());
        }

        @Test void doubleCancelIdempotent() throws Exception {
            Process p = startSleepProcess(60);
            var handle = createHandle(p);
            handle.cancel();
            handle.cancel(); // should not throw
            assertTrue(handle.isCancelled());
            p.waitFor();
        }

        @Test void cancelAlreadyTerminated() throws Exception {
            Process p = startSleepProcess(0);
            p.waitFor();
            var handle = createHandle(p);
            handle.cancel(); // should not throw
            assertTrue(handle.isCancelled());
        }
    }

    @Nested @DisplayName("WaitFor")
    class WaitFor {
        @Test void completedProcess() throws Exception {
            Process p = new ProcessBuilder("true").start();
            p.waitFor();
            var handle = createHandle(p);
            assertEquals(0, handle.waitFor(Duration.ofSeconds(5)));
        }
    }

    @Nested @DisplayName("Status snapshot")
    class StatusSnapshot {
        @Test void capturesCurrentState() throws Exception {
            Process p = startSleepProcess(60);
            try {
                var handle = createHandle(p);
                handle.updateProgress("CHUNKING", 30, "processing");
                var status = handle.getStatus();

                assertEquals("test-task", status.taskId());
                assertEquals("test-file.pdf", status.fileName());
                assertTrue(status.alive());
                assertFalse(status.cancelled());
                assertFalse(status.oomDetected());
                assertEquals("CHUNKING", status.currentPhase());
                assertEquals(30, status.progressPercent());
                assertEquals("processing", status.lastMessage());
                assertNotNull(status.startTime());
                assertNotNull(status.elapsedTime());
            } finally {
                p.destroyForcibly();
            }
        }
    }

    @Nested @DisplayName("SubprocessResult")
    class ResultTests {
        @Test void failureFactory() {
            var result = SubprocessHandle.SubprocessResult.failure(
                    "t1", 137, "OOM killed", "EMBEDDING", false, true);
            assertFalse(result.success());
            assertEquals(137, result.exitCode());
            assertEquals("OOM killed", result.errorMessage());
            assertTrue(result.oomKilled());
        }
    }

    @Nested @DisplayName("Elapsed time")
    class ElapsedTime {
        @Test void elapsedGrows() throws Exception {
            Process p = startSleepProcess(60);
            try {
                var handle = createHandle(p);
                Thread.sleep(50);
                assertTrue(handle.getElapsedTime().toMillis() >= 40);
            } finally {
                p.destroyForcibly();
            }
        }
    }
}
