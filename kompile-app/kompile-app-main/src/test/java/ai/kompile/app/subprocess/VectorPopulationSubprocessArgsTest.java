package ai.kompile.app.subprocess;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("VectorPopulationSubprocessArgs")
class VectorPopulationSubprocessArgsTest {

    @TempDir Path tempDir;

    @Nested @DisplayName("Builder")
    class BuilderTests {
        @Test void defaults() {
            var args = VectorPopulationSubprocessArgs.builder()
                    .taskId("t1").keywordIndexPath("/kw").vectorIndexPath("/vec")
                    .callbackBaseUrl("http://x").build();

            assertEquals("t1", args.taskId());
            assertEquals(32, args.embeddingBatchSize());
            assertEquals(128, args.maxBatchSize());
            assertEquals(1000, args.queueCapacity());
            assertEquals(4, args.indexingWorkers());
            assertEquals(8, args.indexingBatchAccumulationSize());
            assertEquals(1, args.embeddingThreads());
            assertEquals(VectorPopulationSubprocessArgs.DEFAULT_MEMORY_THRESHOLD_PERCENT, args.memoryThresholdPercent());
        }

        @Test void allFields() {
            var args = VectorPopulationSubprocessArgs.builder()
                    .taskId("t").keywordIndexPath("/kw").vectorIndexPath("/vec")
                    .checkpointBasePath("/cp").embeddingBatchSize(64).maxBatchSize(256)
                    .queueCapacity(2000).parallelIndexing(true).indexingWorkers(8)
                    .indexingBatchAccumulationSize(16).embeddingThreads(2)
                    .callbackBaseUrl("http://x").nd4jConfigJson("{}")
                    .modelSourceType("staging").modelIdentifier("bge")
                    .stagingUrl("http://s").stagingApiKey("k").archivePath("/a")
                    .memoryThresholdPercent(75).memoryCriticalPercent(85)
                    .memoryKillThresholdPercent(92).memoryCheckIntervalMs(5000)
                    .options(Map.of("k", "v")).build();

            assertEquals(64, args.embeddingBatchSize());
            assertEquals(256, args.maxBatchSize());
            assertEquals(8, args.indexingWorkers());
            assertEquals("staging", args.modelSourceType());
            assertEquals(75, args.memoryThresholdPercent());
            assertEquals("v", args.options().get("k"));
        }

        @Test void zeroDefaultsApplied() {
            var args = VectorPopulationSubprocessArgs.builder()
                    .taskId("t").keywordIndexPath("/kw").vectorIndexPath("/vec")
                    .callbackBaseUrl("http://x")
                    .embeddingBatchSize(0).maxBatchSize(0).queueCapacity(0)
                    .indexingWorkers(0).indexingBatchAccumulationSize(0).embeddingThreads(0)
                    .build();

            assertEquals(32, args.embeddingBatchSize());
            assertEquals(128, args.maxBatchSize());
            assertEquals(1000, args.queueCapacity());
            assertEquals(4, args.indexingWorkers());
            assertEquals(8, args.indexingBatchAccumulationSize());
            assertEquals(1, args.embeddingThreads());
        }
    }

    @Nested @DisplayName("Serialization")
    class Serialization {
        @Test void fileRoundTrip() throws IOException {
            var original = VectorPopulationSubprocessArgs.builder()
                    .taskId("rt1").keywordIndexPath("/kw").vectorIndexPath("/vec")
                    .callbackBaseUrl("http://x").embeddingBatchSize(64).build();

            Path file = tempDir.resolve("args.json");
            original.toFile(file);
            assertTrue(Files.exists(file));
            assertTrue(Files.size(file) > 0);

            var restored = VectorPopulationSubprocessArgs.fromFile(file);
            assertEquals(original.taskId(), restored.taskId());
            assertEquals(original.keywordIndexPath(), restored.keywordIndexPath());
            assertEquals(original.embeddingBatchSize(), restored.embeddingBatchSize());
        }
    }
}
