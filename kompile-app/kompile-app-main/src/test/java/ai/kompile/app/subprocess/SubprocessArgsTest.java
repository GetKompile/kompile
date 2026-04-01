package ai.kompile.app.subprocess;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SubprocessArgs")
class SubprocessArgsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    @TempDir Path tempDir;

    @Nested
    @DisplayName("Builder")
    class Builder {

        @Test
        void requiredFieldsOnly() {
            SubprocessArgs args = SubprocessArgs.builder()
                    .taskId("t1").filePath("/f.pdf").callbackBaseUrl("http://localhost:8080").build();

            assertEquals("t1", args.taskId());
            assertEquals("/f.pdf", args.filePath());
            assertEquals("http://localhost:8080", args.callbackBaseUrl());
        }

        @Test
        void defaults() {
            SubprocessArgs args = SubprocessArgs.builder()
                    .taskId("t").filePath("/f").callbackBaseUrl("http://x").build();

            assertEquals(SubprocessArgs.DEFAULT_CHUNK_SIZE, args.chunkSize());
            assertEquals(SubprocessArgs.DEFAULT_CHUNK_OVERLAP, args.chunkOverlap());
            assertEquals(SubprocessArgs.DEFAULT_EMBEDDING_BATCH_SIZE, args.embeddingBatchSize());
            assertEquals(SubprocessArgs.DEFAULT_MEMORY_THRESHOLD_PERCENT, args.memoryThresholdPercent());
            assertEquals(SubprocessArgs.DEFAULT_MEMORY_CRITICAL_PERCENT, args.memoryCriticalPercent());
            assertEquals(SubprocessArgs.DEFAULT_MEMORY_KILL_THRESHOLD_PERCENT, args.memoryKillThresholdPercent());
            assertEquals(SubprocessArgs.DEFAULT_MEMORY_CHECK_INTERVAL_MS, args.memoryCheckIntervalMs());
            assertFalse(args.resume());
        }

        @Test
        void allFields() {
            SubprocessArgs args = SubprocessArgs.builder()
                    .taskId("t").filePath("/f").callbackBaseUrl("http://x")
                    .loaderName("pdf-extended").chunkerName("recursive")
                    .chunkSize(1000).chunkOverlap(100).embeddingBatchSize(64)
                    .indexPath("/idx").vectorStorePath("/vec").keywordIndexPath("/kw")
                    .nd4jConfigJson("{\"threads\":4}").checkpointPath("/cp")
                    .resume(true).modelSourceType("staging").modelIdentifier("bge-base")
                    .stagingUrl("http://s").stagingApiKey("k").archivePath("/a")
                    .memoryThresholdPercent(75).memoryCriticalPercent(85)
                    .memoryKillThresholdPercent(92).memoryCheckIntervalMs(5000)
                    .option("k1", "v1")
                    .build();

            assertEquals("pdf-extended", args.loaderName());
            assertEquals(1000, args.chunkSize());
            assertEquals(64, args.embeddingBatchSize());
            assertTrue(args.resume());
            assertEquals("staging", args.modelSourceType());
            assertEquals(75, args.memoryThresholdPercent());
            assertEquals("v1", args.getOption("k1", ""));
        }

        @Test
        void validatesRequiredFields() {
            assertThrows(Exception.class, () ->
                    SubprocessArgs.builder().filePath("/f").callbackBaseUrl("http://x").build());
            assertThrows(Exception.class, () ->
                    SubprocessArgs.builder().taskId("t").callbackBaseUrl("http://x").build());
            assertThrows(Exception.class, () ->
                    SubprocessArgs.builder().taskId("t").filePath("/f").build());
        }

        @Test
        void clampsMemoryThresholds() {
            SubprocessArgs args = SubprocessArgs.builder()
                    .taskId("t").filePath("/f").callbackBaseUrl("http://x")
                    .memoryThresholdPercent(150).memoryCriticalPercent(-10)
                    .memoryKillThresholdPercent(200).build();

            assertTrue(args.memoryThresholdPercent() <= 100);
            assertTrue(args.memoryCriticalPercent() >= 0);
            assertTrue(args.memoryKillThresholdPercent() <= 100);
        }

        @Test
        void enforcesMinCheckInterval() {
            SubprocessArgs args = SubprocessArgs.builder()
                    .taskId("t").filePath("/f").callbackBaseUrl("http://x")
                    .memoryCheckIntervalMs(100).build();

            assertTrue(args.memoryCheckIntervalMs() >= 500);
        }

        @Test
        void optionsMap() {
            SubprocessArgs args = SubprocessArgs.builder()
                    .taskId("t").filePath("/f").callbackBaseUrl("http://x")
                    .options(Map.of("a", 1, "b", "two")).build();

            assertEquals(1, args.getOption("a", 0));
            assertEquals("two", args.getOption("b", ""));
            assertEquals("fallback", args.getOption("missing", "fallback"));
        }
    }

    @Nested
    @DisplayName("Serialization")
    class Serialization {

        @Test
        void jsonRoundTrip() throws IOException {
            SubprocessArgs original = SubprocessArgs.builder()
                    .taskId("rt1").filePath("/rt.pdf").callbackBaseUrl("http://x")
                    .loaderName("tika").chunkSize(750).embeddingBatchSize(16)
                    .memoryThresholdPercent(70).option("custom", "val").build();

            SubprocessArgs restored = SubprocessArgs.fromJson(original.toJson());

            assertEquals(original.taskId(), restored.taskId());
            assertEquals(original.filePath(), restored.filePath());
            assertEquals(original.loaderName(), restored.loaderName());
            assertEquals(original.chunkSize(), restored.chunkSize());
            assertEquals(original.memoryThresholdPercent(), restored.memoryThresholdPercent());
            assertEquals("val", restored.getOption("custom", ""));
        }

        @Test
        void fileRoundTrip() throws IOException {
            SubprocessArgs original = SubprocessArgs.builder()
                    .taskId("fr1").filePath("/fr.pdf").callbackBaseUrl("http://x")
                    .chunkSize(300).build();

            Path file = original.writeToTempFile();
            assertTrue(Files.exists(file));
            assertTrue(Files.size(file) > 0);

            SubprocessArgs restored = SubprocessArgs.readFromFile(file);
            assertEquals(original.taskId(), restored.taskId());
            assertEquals(original.chunkSize(), restored.chunkSize());
            Files.deleteIfExists(file);
        }

        @Test
        void staticFactory() {
            SubprocessArgs args = SubprocessArgs.create("t", "/f", "pdf", "rec",
                    "/idx", "http://x", "{}", "/cp", false);

            assertEquals("t", args.taskId());
            assertEquals("pdf", args.loaderName());
            assertEquals("rec", args.chunkerName());
            assertFalse(args.resume());
        }

        @Test
        void jsonIsValid() throws IOException {
            SubprocessArgs args = SubprocessArgs.builder()
                    .taskId("jv1").filePath("/f").callbackBaseUrl("http://x").build();

            var node = MAPPER.readTree(args.toJson());
            assertEquals("jv1", node.get("taskId").asText());
        }

        @Test
        void largeOptionsMap() throws IOException {
            var builder = SubprocessArgs.builder()
                    .taskId("lg1").filePath("/f").callbackBaseUrl("http://x");
            for (int i = 0; i < 100; i++) builder.option("k" + i, "v" + i);
            SubprocessArgs original = builder.build();

            SubprocessArgs restored = SubprocessArgs.fromJson(original.toJson());
            for (int i = 0; i < 100; i++)
                assertEquals("v" + i, restored.getOption("k" + i, ""));
        }
    }
}
