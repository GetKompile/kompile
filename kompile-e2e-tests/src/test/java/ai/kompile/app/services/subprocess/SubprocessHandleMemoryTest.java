package ai.kompile.app.services.subprocess;

import ai.kompile.app.subprocess.SubprocessMessage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SubprocessHandle memory tracking from heartbeats,
 * including off-heap, GPU, peak tracking, and status snapshot.
 */
@DisplayName("SubprocessHandle Memory Tracking")
class SubprocessHandleMemoryTest {

    @TempDir Path tempDir;

    private SubprocessHandle createHandle(Process process) {
        return new SubprocessHandle(
                "mem-test",
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

    private SubprocessMessage.Heartbeat createHeartbeat(
            String taskId, long uptimeMs,
            double heapPercent, long heapUsed, long heapMax,
            long offHeapUsed, long offHeapMax, double offHeapPercent,
            long gpuUsed, long gpuMax, double gpuPercent) {
        return new SubprocessMessage.Heartbeat(
                taskId, uptimeMs, heapPercent, heapUsed, heapMax,
                offHeapUsed, offHeapMax, offHeapPercent,
                gpuUsed, gpuMax, gpuPercent);
    }

    @Nested @DisplayName("updateMemoryFromHeartbeat")
    class UpdateMemoryFromHeartbeat {

        @Test @DisplayName("updates all heap fields from heartbeat")
        void updatesHeapFields() throws Exception {
            Process p = startSleepProcess(60);
            try {
                var handle = createHandle(p);
                var hb = createHeartbeat("mem-test", 5000,
                        65.0, 650_000_000L, 1_000_000_000L,
                        0L, 0L, 0.0,
                        0L, 0L, 0.0);

                handle.updateMemoryFromHeartbeat(hb);

                var status = handle.getStatus();
                assertEquals(65.0, status.heapUsagePercent(), 0.01);
                assertEquals(650_000_000L, status.heapUsedBytes());
                assertEquals(1_000_000_000L, status.heapMaxBytes());
            } finally {
                p.destroyForcibly();
            }
        }

        @Test @DisplayName("updates off-heap fields from heartbeat")
        void updatesOffHeapFields() throws Exception {
            Process p = startSleepProcess(60);
            try {
                var handle = createHandle(p);
                var hb = createHeartbeat("mem-test", 5000,
                        50.0, 500_000_000L, 1_000_000_000L,
                        300_000_000L, 1_200_000_000L, 25.0,
                        0L, 0L, 0.0);

                handle.updateMemoryFromHeartbeat(hb);

                var status = handle.getStatus();
                assertEquals(25.0, status.offHeapUsagePercent(), 0.01);
                assertEquals(300_000_000L, status.offHeapUsedBytes());
                assertEquals(1_200_000_000L, status.offHeapMaxBytes());
            } finally {
                p.destroyForcibly();
            }
        }

        @Test @DisplayName("updates GPU fields from heartbeat")
        void updatesGpuFields() throws Exception {
            Process p = startSleepProcess(60);
            try {
                var handle = createHandle(p);
                var hb = createHeartbeat("mem-test", 5000,
                        40.0, 400_000_000L, 1_000_000_000L,
                        0L, 0L, 0.0,
                        6_000_000_000L, 8_000_000_000L, 75.0);

                handle.updateMemoryFromHeartbeat(hb);

                var status = handle.getStatus();
                assertEquals(75.0, status.gpuUsagePercent(), 0.01);
                assertEquals(6_000_000_000L, status.gpuUsedBytes());
                assertEquals(8_000_000_000L, status.gpuMaxBytes());
            } finally {
                p.destroyForcibly();
            }
        }

        @Test @DisplayName("updates lastHeartbeat timestamp")
        void updatesLastHeartbeat() throws Exception {
            Process p = startSleepProcess(60);
            try {
                var handle = createHandle(p);
                var before = handle.getLastHeartbeat();
                Thread.sleep(10);

                var hb = createHeartbeat("mem-test", 5000,
                        50.0, 500L, 1000L, 0L, 0L, 0.0, 0L, 0L, 0.0);
                handle.updateMemoryFromHeartbeat(hb);

                assertTrue(handle.getLastHeartbeat().isAfter(before),
                        "Heartbeat should update lastHeartbeat timestamp");
            } finally {
                p.destroyForcibly();
            }
        }

        @Test @DisplayName("process is not stale after heartbeat")
        void notStaleAfterHeartbeat() throws Exception {
            Process p = startSleepProcess(60);
            try {
                var handle = createHandle(p);
                var hb = createHeartbeat("mem-test", 5000,
                        50.0, 500L, 1000L, 0L, 0L, 0.0, 0L, 0L, 0.0);
                handle.updateMemoryFromHeartbeat(hb);

                assertFalse(handle.isStale(Duration.ofSeconds(1)),
                        "Should not be stale immediately after heartbeat");
            } finally {
                p.destroyForcibly();
            }
        }
    }

    @Nested @DisplayName("Peak memory tracking")
    class PeakMemoryTracking {

        @Test @DisplayName("peak heap tracks highest value")
        void peakHeapTracksHighest() throws Exception {
            Process p = startSleepProcess(60);
            try {
                var handle = createHandle(p);

                // First heartbeat: 50% heap
                handle.updateMemoryFromHeartbeat(createHeartbeat("t", 1000,
                        50.0, 500L, 1000L, 0L, 0L, 0.0, 0L, 0L, 0.0));
                assertEquals(50.0, handle.getStatus().peakHeapUsagePercent(), 0.01);

                // Second heartbeat: 80% heap (new peak)
                handle.updateMemoryFromHeartbeat(createHeartbeat("t", 2000,
                        80.0, 800L, 1000L, 0L, 0L, 0.0, 0L, 0L, 0.0));
                assertEquals(80.0, handle.getStatus().peakHeapUsagePercent(), 0.01);

                // Third heartbeat: 60% heap (below peak)
                handle.updateMemoryFromHeartbeat(createHeartbeat("t", 3000,
                        60.0, 600L, 1000L, 0L, 0L, 0.0, 0L, 0L, 0.0));
                // Peak should remain at 80%
                assertEquals(80.0, handle.getStatus().peakHeapUsagePercent(), 0.01);
                // Current should be 60%
                assertEquals(60.0, handle.getStatus().heapUsagePercent(), 0.01);
            } finally {
                p.destroyForcibly();
            }
        }

        @Test @DisplayName("peak off-heap tracks highest value")
        void peakOffHeapTracksHighest() throws Exception {
            Process p = startSleepProcess(60);
            try {
                var handle = createHandle(p);

                handle.updateMemoryFromHeartbeat(createHeartbeat("t", 1000,
                        30.0, 300L, 1000L, 400L, 1000L, 40.0, 0L, 0L, 0.0));
                handle.updateMemoryFromHeartbeat(createHeartbeat("t", 2000,
                        30.0, 300L, 1000L, 900L, 1000L, 90.0, 0L, 0L, 0.0));
                handle.updateMemoryFromHeartbeat(createHeartbeat("t", 3000,
                        30.0, 300L, 1000L, 500L, 1000L, 50.0, 0L, 0L, 0.0));

                var status = handle.getStatus();
                assertEquals(90.0, status.peakOffHeapUsagePercent(), 0.01);
                assertEquals(50.0, status.offHeapUsagePercent(), 0.01);
            } finally {
                p.destroyForcibly();
            }
        }

        @Test @DisplayName("peak GPU tracks highest value")
        void peakGpuTracksHighest() throws Exception {
            Process p = startSleepProcess(60);
            try {
                var handle = createHandle(p);

                handle.updateMemoryFromHeartbeat(createHeartbeat("t", 1000,
                        30.0, 300L, 1000L, 0L, 0L, 0.0,
                        2_000_000_000L, 8_000_000_000L, 25.0));
                handle.updateMemoryFromHeartbeat(createHeartbeat("t", 2000,
                        30.0, 300L, 1000L, 0L, 0L, 0.0,
                        7_000_000_000L, 8_000_000_000L, 87.5));
                handle.updateMemoryFromHeartbeat(createHeartbeat("t", 3000,
                        30.0, 300L, 1000L, 0L, 0L, 0.0,
                        4_000_000_000L, 8_000_000_000L, 50.0));

                var status = handle.getStatus();
                assertEquals(87.5, status.peakGpuUsagePercent(), 0.01);
                assertEquals(50.0, status.gpuUsagePercent(), 0.01);
            } finally {
                p.destroyForcibly();
            }
        }

        @Test @DisplayName("peaks start at zero before any heartbeat")
        void peaksStartAtZero() throws Exception {
            Process p = startSleepProcess(60);
            try {
                var handle = createHandle(p);
                var status = handle.getStatus();

                assertEquals(0.0, status.peakHeapUsagePercent(), 0.01);
                assertEquals(0.0, status.peakOffHeapUsagePercent(), 0.01);
                assertEquals(0.0, status.peakGpuUsagePercent(), 0.01);
            } finally {
                p.destroyForcibly();
            }
        }

        @Test @DisplayName("all three peaks track independently")
        void allPeaksTrackIndependently() throws Exception {
            Process p = startSleepProcess(60);
            try {
                var handle = createHandle(p);

                // High heap, low others
                handle.updateMemoryFromHeartbeat(createHeartbeat("t", 1000,
                        95.0, 950L, 1000L, 100L, 1000L, 10.0,
                        500L, 8000L, 6.25));
                // Low heap, high off-heap
                handle.updateMemoryFromHeartbeat(createHeartbeat("t", 2000,
                        20.0, 200L, 1000L, 850L, 1000L, 85.0,
                        500L, 8000L, 6.25));
                // Low heap, low off-heap, high GPU
                handle.updateMemoryFromHeartbeat(createHeartbeat("t", 3000,
                        20.0, 200L, 1000L, 100L, 1000L, 10.0,
                        7500L, 8000L, 93.75));

                var status = handle.getStatus();
                assertEquals(95.0, status.peakHeapUsagePercent(), 0.01);
                assertEquals(85.0, status.peakOffHeapUsagePercent(), 0.01);
                assertEquals(93.75, status.peakGpuUsagePercent(), 0.01);
            } finally {
                p.destroyForcibly();
            }
        }
    }

    @Nested @DisplayName("Status snapshot with memory")
    class StatusSnapshotWithMemory {

        @Test @DisplayName("status includes all 12 memory fields")
        void statusIncludesAllMemoryFields() throws Exception {
            Process p = startSleepProcess(60);
            try {
                var handle = createHandle(p);
                handle.updateMemoryFromHeartbeat(createHeartbeat("t", 5000,
                        70.0, 700_000_000L, 1_000_000_000L,
                        300_000_000L, 800_000_000L, 37.5,
                        5_000_000_000L, 8_000_000_000L, 62.5));

                var status = handle.getStatus();

                // Current values
                assertEquals(70.0, status.heapUsagePercent(), 0.01);
                assertEquals(700_000_000L, status.heapUsedBytes());
                assertEquals(1_000_000_000L, status.heapMaxBytes());
                assertEquals(37.5, status.offHeapUsagePercent(), 0.01);
                assertEquals(300_000_000L, status.offHeapUsedBytes());
                assertEquals(800_000_000L, status.offHeapMaxBytes());
                assertEquals(62.5, status.gpuUsagePercent(), 0.01);
                assertEquals(5_000_000_000L, status.gpuUsedBytes());
                assertEquals(8_000_000_000L, status.gpuMaxBytes());

                // Peak values (equal to current since only one heartbeat)
                assertEquals(70.0, status.peakHeapUsagePercent(), 0.01);
                assertEquals(37.5, status.peakOffHeapUsagePercent(), 0.01);
                assertEquals(62.5, status.peakGpuUsagePercent(), 0.01);
            } finally {
                p.destroyForcibly();
            }
        }

        @Test @DisplayName("status preserves non-memory fields alongside memory")
        void statusPreservesOtherFields() throws Exception {
            Process p = startSleepProcess(60);
            try {
                var handle = createHandle(p);
                handle.updateProgress("EMBEDDING", 75, "Batch 15/20");
                handle.updateMemoryFromHeartbeat(createHeartbeat("t", 5000,
                        55.0, 550L, 1000L, 0L, 0L, 0.0, 0L, 0L, 0.0));

                var status = handle.getStatus();

                // Non-memory fields
                assertEquals("mem-test", status.taskId());
                assertEquals("test-file.pdf", status.fileName());
                assertTrue(status.alive());
                assertFalse(status.cancelled());
                assertFalse(status.oomDetected());
                assertEquals("EMBEDDING", status.currentPhase());
                assertEquals(75, status.progressPercent());
                assertEquals("Batch 15/20", status.lastMessage());

                // Memory fields
                assertEquals(55.0, status.heapUsagePercent(), 0.01);
            } finally {
                p.destroyForcibly();
            }
        }
    }

    @Nested @DisplayName("Multiple rapid heartbeats")
    class MultipleRapidHeartbeats {

        @Test @DisplayName("handles 100 rapid heartbeats correctly")
        void handlesRapidHeartbeats() throws Exception {
            Process p = startSleepProcess(60);
            try {
                var handle = createHandle(p);

                for (int i = 0; i < 100; i++) {
                    double heapPct = 20.0 + (i * 0.5);  // 20% -> 69.5%
                    double offHeapPct = 10.0 + (i * 0.3); // 10% -> 39.7%
                    handle.updateMemoryFromHeartbeat(createHeartbeat("t", i * 1000,
                            heapPct, (long) (heapPct * 10), 1000L,
                            (long) (offHeapPct * 10), 1000L, offHeapPct,
                            0L, 0L, 0.0));
                }

                var status = handle.getStatus();
                // Peak should be the last value since they monotonically increase
                assertEquals(69.5, status.peakHeapUsagePercent(), 0.01);
                assertEquals(39.7, status.peakOffHeapUsagePercent(), 0.1);
                // Current should be the last heartbeat values
                assertEquals(69.5, status.heapUsagePercent(), 0.01);
            } finally {
                p.destroyForcibly();
            }
        }
    }

    @Nested @DisplayName("SubprocessResult factories")
    class ResultFactories {

        @Test @DisplayName("success result from completed message")
        void successFromCompleted() {
            var completed = SubprocessMessage.completed("t1", 10, 500, 500, 500,
                    75000L, 150000L, 25000L, "/data/index",
                    java.util.Map.of("LOADING", 5000L));

            var result = SubprocessHandle.SubprocessResult.success("t1", completed);

            assertTrue(result.success());
            assertEquals(0, result.exitCode());
            assertEquals(10, result.documentsLoaded());
            assertEquals(500, result.chunksCreated());
            assertEquals(500, result.chunksEmbedded());
            assertEquals(500, result.documentsIndexed());
            assertEquals(25000L, result.totalDurationMs());
            assertEquals("/data/index", result.indexPath());
            assertNull(result.errorMessage());
            assertFalse(result.cancelled());
            assertFalse(result.oomKilled());
            assertFalse(result.gpuOomKilled());
        }

        @Test @DisplayName("failure result with GPU OOM")
        void failureWithGpuOom() {
            var result = SubprocessHandle.SubprocessResult.failure(
                    "t2", 137, "CUDA out of memory", "EMBEDDING",
                    false, false, true);

            assertFalse(result.success());
            assertEquals(137, result.exitCode());
            assertTrue(result.gpuOomKilled());
            assertFalse(result.oomKilled());
            assertFalse(result.cancelled());
        }

        @Test @DisplayName("failure result with cancellation")
        void failureWithCancellation() {
            var result = SubprocessHandle.SubprocessResult.failure(
                    "t3", 143, "Process cancelled", "INDEXING",
                    true, false);

            assertFalse(result.success());
            assertTrue(result.cancelled());
            assertFalse(result.oomKilled());
            assertFalse(result.gpuOomKilled());
        }
    }

    @Nested @DisplayName("GPU OOM detection")
    class GpuOomDetection {

        @Test @DisplayName("GPU OOM flag set and get")
        void gpuOomFlagSetAndGet() throws Exception {
            Process p = startSleepProcess(60);
            try {
                var handle = createHandle(p);
                assertFalse(handle.isGpuOomDetected());
                handle.setGpuOomDetected(true);
                assertTrue(handle.isGpuOomDetected());
            } finally {
                p.destroyForcibly();
            }
        }

        @Test @DisplayName("GPU OOM and regular OOM are independent")
        void gpuOomAndRegularOomIndependent() throws Exception {
            Process p = startSleepProcess(60);
            try {
                var handle = createHandle(p);
                handle.setOomDetected(true);
                handle.setGpuOomDetected(false);
                assertTrue(handle.isOomDetected());
                assertFalse(handle.isGpuOomDetected());

                handle.setOomDetected(false);
                handle.setGpuOomDetected(true);
                assertFalse(handle.isOomDetected());
                assertTrue(handle.isGpuOomDetected());
            } finally {
                p.destroyForcibly();
            }
        }
    }
}
