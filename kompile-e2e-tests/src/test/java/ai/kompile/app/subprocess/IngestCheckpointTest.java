package ai.kompile.app.subprocess;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("IngestCheckpoint")
class IngestCheckpointTest {

    @TempDir Path tempDir;

    @Nested @DisplayName("Creation")
    class Creation {
        @Test void create() {
            var cp = IngestCheckpoint.create("j1", "t1", "/f.pdf");
            assertEquals("j1", cp.getJobId());
            assertEquals("t1", cp.getTaskId());
            assertEquals("/f.pdf", cp.getFilePath());
            assertNotNull(cp.getCreatedAt());
            assertEquals(0, cp.getEmbeddedCount());
            assertEquals(0, cp.getIndexedCount());
            assertEquals(0, cp.getOomFailureCount());
            assertFalse(cp.needsResume());
        }

        @Test void loadOrCreateNew() {
            var cp = IngestCheckpoint.loadOrCreate(tempDir.resolve("new.json"), "j2", "t2", "/f");
            assertEquals("j2", cp.getJobId());
        }

        @Test void loadOrCreateExisting() throws IOException {
            Path file = tempDir.resolve("existing.json");
            var orig = IngestCheckpoint.create("j3", "t3", "/f");
            orig.markChunksEmbedded(List.of(0, 1, 2));
            orig.setTotalChunks(10);
            orig.save(file);

            var loaded = IngestCheckpoint.loadOrCreate(file, "j3", "t3", "/f");
            assertEquals(3, loaded.getEmbeddedCount());
            assertEquals(10, loaded.getTotalChunks());
        }
    }

    @Nested @DisplayName("Save/Load")
    class SaveLoad {
        @Test void savesFile() throws IOException {
            Path file = tempDir.resolve("save.json");
            IngestCheckpoint.create("j", "t", "/f").save(file);
            assertTrue(Files.exists(file));
            assertTrue(Files.size(file) > 0);
        }

        @Test void roundTrip() throws IOException {
            Path file = tempDir.resolve("rt.json");
            var orig = IngestCheckpoint.create("j", "t", "/f");
            orig.setCurrentPhase("EMBEDDING");
            orig.setTotalChunks(500);
            orig.markChunksEmbedded(List.of(0, 1, 2, 3, 4));
            orig.markChunksIndexed(List.of(0, 1, 2));
            orig.setLastCompletedBatch(2);
            orig.setTotalBatches(16);
            orig.setHeapSize("8g");
            orig.setBatchSize(32);
            orig.setNd4jThreads(4);
            orig.setOmpThreads(8);
            orig.setEmbeddingWorkers(2);
            orig.save(file);

            var loaded = IngestCheckpoint.loadOrCreate(file, "j", "t", "/f");
            assertEquals("EMBEDDING", loaded.getCurrentPhase());
            assertEquals(500, loaded.getTotalChunks());
            assertEquals(5, loaded.getEmbeddedCount());
            assertEquals(3, loaded.getIndexedCount());
            assertEquals(2, loaded.getLastCompletedBatch());
            assertEquals("8g", loaded.getHeapSize());
            assertEquals(32, loaded.getBatchSize());
        }

        @Test void atomicOverwrite() throws IOException {
            Path file = tempDir.resolve("atomic.json");
            var cp = IngestCheckpoint.create("j", "t", "/f");
            cp.setTotalChunks(100);
            for (int i = 0; i < 10; i++) {
                cp.markChunksEmbedded(List.of(i));
                cp.save(file);
            }
            var loaded = IngestCheckpoint.loadOrCreate(file, "j", "t", "/f");
            assertEquals(10, loaded.getEmbeddedCount());
        }
    }

    @Nested @DisplayName("Chunk tracking")
    class ChunkTracking {
        @Test void markEmbedded() {
            var cp = IngestCheckpoint.create("j", "t", "/f");
            cp.setTotalChunks(100);
            cp.markChunksEmbedded(List.of(0, 1, 2, 3, 4));
            assertEquals(5, cp.getEmbeddedCount());
            assertTrue(cp.isChunkEmbedded(0));
            assertTrue(cp.isChunkEmbedded(4));
            assertFalse(cp.isChunkEmbedded(5));
        }

        @Test void markIndexed() {
            var cp = IngestCheckpoint.create("j", "t", "/f");
            cp.setTotalChunks(100);
            cp.markChunksIndexed(List.of(0, 1, 2));
            assertEquals(3, cp.getIndexedCount());
            assertTrue(cp.isChunkIndexed(2));
            assertFalse(cp.isChunkIndexed(3));
        }

        @Test void duplicateIdempotent() {
            var cp = IngestCheckpoint.create("j", "t", "/f");
            cp.setTotalChunks(50);
            cp.markChunksEmbedded(List.of(0, 1, 2));
            cp.markChunksEmbedded(List.of(1, 2, 3));
            assertEquals(4, cp.getEmbeddedCount());
        }

        @Test void batchCompletion() {
            var cp = IngestCheckpoint.create("j", "t", "/f");
            cp.setTotalBatches(10);
            cp.markBatchCompleted(0);
            assertEquals(0, cp.getLastCompletedBatch());
            cp.markBatchCompleted(5);
            assertEquals(5, cp.getLastCompletedBatch());
        }

        @Test void nextChunkToEmbed() {
            var cp = IngestCheckpoint.create("j", "t", "/f");
            cp.setTotalChunks(10);
            assertEquals(0, cp.getNextChunkToEmbed());
            cp.markChunksEmbedded(List.of(0, 1, 2));
            assertEquals(3, cp.getNextChunkToEmbed());
            cp.markChunksEmbedded(List.of(3, 4, 5, 6, 7, 8, 9));
            assertEquals(10, cp.getNextChunkToEmbed());
        }
    }

    @Nested @DisplayName("Resume")
    class Resume {
        @Test void freshNotNeedResume() {
            assertFalse(IngestCheckpoint.create("j", "t", "/f").needsResume());
        }

        @Test void partialNeedsResume() {
            var cp = IngestCheckpoint.create("j", "t", "/f");
            cp.setTotalChunks(100);
            cp.markChunksEmbedded(List.of(0, 1, 2));
            assertTrue(cp.needsResume());
        }

        @Test void markCompleted() {
            var cp = IngestCheckpoint.create("j", "t", "/f");
            cp.markCompleted(25000);
            assertEquals(25000, cp.getTotalProcessingTimeMs());
        }
    }

    @Nested @DisplayName("OOM tracking")
    class OomTracking {
        @Test void recordIncrementsCount() {
            var cp = IngestCheckpoint.create("j", "t", "/f");
            cp.recordOomFailure("4g", 32, 4, 8, "OOM", "EMBEDDING");
            assertEquals(1, cp.getOomFailureCount());
            assertEquals("OOM", cp.getLastErrorMessage());
            assertEquals("EMBEDDING", cp.getLastErrorPhase());
            cp.recordOomFailure("6g", 16, 2, 4, "OOM2", "EMBEDDING");
            assertEquals(2, cp.getOomFailureCount());
        }

        @Test void failedSettingsHistory() {
            var cp = IngestCheckpoint.create("j", "t", "/f");
            cp.recordOomFailure("4g", 32, 4, 8, "OOM1", "E");
            cp.recordOomFailure("6g", 16, 2, 4, "OOM2", "E");
            cp.recordOomFailure("8g", 8, 1, 2, "OOM3", "I");
            assertEquals(3, cp.getFailedSettingsHistory().size());
        }

        @Test void persistsAcrossSaveLoad() throws IOException {
            Path file = tempDir.resolve("oom.json");
            var cp = IngestCheckpoint.create("j", "t", "/f");
            cp.recordOomFailure("4g", 32, 4, 8, "OOM", "E");
            cp.save(file);

            var loaded = IngestCheckpoint.loadOrCreate(file, "j", "t", "/f");
            assertEquals(1, loaded.getOomFailureCount());
            assertEquals("OOM", loaded.getLastErrorMessage());
        }
    }

    @Test
    @DisplayName("Checkpoint OOM restart simulation")
    void oomRestartSimulation() throws IOException {
        Path file = tempDir.resolve("restart.json");

        // Run 1: partial progress then OOM
        var cp1 = IngestCheckpoint.create("j", "t1", "/f");
        cp1.setTotalChunks(100);
        for (int i = 0; i < 40; i++) cp1.markChunksEmbedded(List.of(i));
        cp1.recordOomFailure("4g", 32, 4, 8, "OOM", "EMBEDDING");
        cp1.save(file);

        // Run 2: resume
        var cp2 = IngestCheckpoint.loadOrCreate(file, "j", "t2", "/f");
        assertTrue(cp2.needsResume());
        assertEquals(40, cp2.getNextChunkToEmbed());
        for (int i = 40; i < 100; i++) cp2.markChunksEmbedded(List.of(i));
        var all = new java.util.ArrayList<Integer>();
        for (int i = 0; i < 100; i++) all.add(i);
        cp2.markChunksIndexed(all);
        cp2.markCompleted(30000);
        cp2.save(file);

        // Verify final
        var fin = IngestCheckpoint.loadOrCreate(file, "j", "t3", "/f");
        assertEquals(100, fin.getEmbeddedCount());
        assertEquals(100, fin.getIndexedCount());
        assertEquals(1, fin.getOomFailureCount());
    }
}
