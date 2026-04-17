package ai.kompile.app.subprocess;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AdaptiveRecoverySettings")
class AdaptiveRecoverySettingsTest {

    @Nested @DisplayName("Defaults")
    class Defaults {
        @Test void hasReasonableDefaults() {
            var s = AdaptiveRecoverySettings.defaults();
            assertEquals("4g", s.getHeapSize());
            assertEquals(32, s.getBatchSize());
            assertTrue(s.getNd4jThreads() >= 1);
            assertTrue(s.getOmpThreads() >= 1);
            assertEquals(1, s.getEmbeddingWorkers());
            assertEquals(0, s.getRetryAttempt());
            assertFalse(s.isShouldGiveUp());
        }

        @Test void shouldRetryInitially() {
            assertTrue(AdaptiveRecoverySettings.defaults().shouldRetry());
        }
    }

    @Nested @DisplayName("Heap parsing")
    class HeapParsing {
        @Test void parsesGigabytes() {
            assertEquals(4L * 1024 * 1024 * 1024, AdaptiveRecoverySettings.parseHeapToBytes("4g"));
            assertEquals(8L * 1024 * 1024 * 1024, AdaptiveRecoverySettings.parseHeapToBytes("8G"));
        }

        @Test void parsesMegabytes() {
            assertEquals(512L * 1024 * 1024, AdaptiveRecoverySettings.parseHeapToBytes("512m"));
        }

        @Test void parsesKilobytes() {
            assertEquals(1024L * 1024, AdaptiveRecoverySettings.parseHeapToBytes("1024k"));
        }

        @Test void parsesNoUnit() {
            // Default unit is GB
            assertEquals(4L * 1024 * 1024 * 1024, AdaptiveRecoverySettings.parseHeapToBytes("4"));
        }

        @Test void nullDefaultsTo4g() {
            assertEquals(4L * 1024 * 1024 * 1024, AdaptiveRecoverySettings.parseHeapToBytes(null));
            assertEquals(4L * 1024 * 1024 * 1024, AdaptiveRecoverySettings.parseHeapToBytes(""));
        }
    }

    @Nested @DisplayName("Heap formatting")
    class HeapFormatting {
        @Test void formatsGigabytes() {
            assertEquals("4g", AdaptiveRecoverySettings.formatHeapSize(4L * 1024 * 1024 * 1024));
        }

        @Test void formatsMegabytes() {
            assertEquals("512m", AdaptiveRecoverySettings.formatHeapSize(512L * 1024 * 1024));
        }
    }

    @Nested @DisplayName("Builder setters")
    class BuilderSetters {
        @Test void heapSizeUpdatesBytes() {
            var s = AdaptiveRecoverySettings.defaults().heapSize("8g");
            assertEquals("8g", s.getHeapSize());
            assertEquals(8L * 1024 * 1024 * 1024, s.getHeapBytes());
        }

        @Test void batchSizeClamped() {
            var s = AdaptiveRecoverySettings.defaults().batchSize(1);
            assertEquals(AdaptiveRecoverySettings.MIN_BATCH_SIZE, s.getBatchSize());
            s.batchSize(999);
            assertEquals(AdaptiveRecoverySettings.MAX_BATCH_SIZE, s.getBatchSize());
        }

        @Test void threadsClamped() {
            var s = AdaptiveRecoverySettings.defaults();
            s.nd4jThreads(0);
            assertEquals(1, s.getNd4jThreads());
            s.ompThreads(0);
            assertEquals(1, s.getOmpThreads());
        }

        @Test void embeddingWorkersClamped() {
            var s = AdaptiveRecoverySettings.defaults().embeddingWorkers(0);
            assertEquals(1, s.getEmbeddingWorkers());
        }
    }

    @Nested @DisplayName("Recovery from checkpoint")
    class Recovery {
        @Test void fromCheckpointNoHistory() {
            var cp = IngestCheckpoint.create("j", "t", "/f");
            var s = AdaptiveRecoverySettings.fromCheckpoint(cp, "4g", 32);
            assertEquals("4g", s.getHeapSize());
            assertEquals(32, s.getBatchSize());
            assertTrue(s.shouldRetry());
        }

        @Test void fromCheckpointWithOomReducesBatch() {
            var cp = IngestCheckpoint.create("j", "t", "/f");
            cp.recordOomFailure("4g", 32, 4, 8, "OOM", "E");
            var s = AdaptiveRecoverySettings.fromCheckpoint(cp, "4g", 32);
            // Batch should be reduced from 32
            assertTrue(s.getBatchSize() < 32);
            assertTrue(s.shouldRetry());
        }

        @Test void fromCheckpointMinBatchReducesThreads() {
            var cp = IngestCheckpoint.create("j", "t", "/f");
            // First failure at batch=32
            cp.recordOomFailure("4g", 32, 4, 8, "OOM1", "E");
            // Second failure at minimum batch, should reduce threads
            cp.recordOomFailure("4g", AdaptiveRecoverySettings.MIN_BATCH_SIZE, 4, 8, "OOM2", "E");
            var s = AdaptiveRecoverySettings.fromCheckpoint(cp, "4g", 32);
            assertEquals(AdaptiveRecoverySettings.MIN_BATCH_SIZE, s.getBatchSize());
            // Threads should be reduced
            assertTrue(s.getNd4jThreads() <= 4);
        }
    }

    @Nested @DisplayName("Summary")
    class Summary {
        @Test void containsAllFields() {
            var s = AdaptiveRecoverySettings.defaults();
            String summary = s.toSummary();
            assertTrue(summary.contains("heap="));
            assertTrue(summary.contains("batch="));
            assertTrue(summary.contains("nd4jThreads="));
            assertTrue(summary.contains("ompThreads="));
        }
    }

    @Test @DisplayName("Constants are reasonable")
    void constantsReasonable() {
        assertTrue(AdaptiveRecoverySettings.MIN_BATCH_SIZE >= 1);
        assertTrue(AdaptiveRecoverySettings.MAX_BATCH_SIZE > AdaptiveRecoverySettings.MIN_BATCH_SIZE);
        assertTrue(AdaptiveRecoverySettings.MAX_RETRY_ATTEMPTS >= 1);
        assertTrue(AdaptiveRecoverySettings.MIN_HEAP_BYTES > 0);
        assertTrue(AdaptiveRecoverySettings.MAX_HEAP_BYTES > AdaptiveRecoverySettings.MIN_HEAP_BYTES);
    }
}
