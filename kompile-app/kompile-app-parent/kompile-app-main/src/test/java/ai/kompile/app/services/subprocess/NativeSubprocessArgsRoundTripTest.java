/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.services.subprocess;

import ai.kompile.app.learning.subprocess.LearningSubprocessArgs;
import ai.kompile.app.learning.subprocess.ReasoningLearningSubprocessArgs;
import ai.kompile.app.subprocess.SubprocessArgs;
import ai.kompile.app.subprocess.VectorPopulationSubprocessArgs;
import ai.kompile.app.subprocess.model.ModelInitSubprocessArgs;
import ai.kompile.pipeline.serving.subprocess.PipelineServingSubprocessArgs;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Focused production JSON paths for every file-deserialized record reachable through
 * native self-exec, excluding ServingSubprocessArgs which has its own focused test.
 * This class is also the input to the class-filtered native-image-agent trace.
 */
class NativeSubprocessArgsRoundTripTest {

    private static final ObjectMapper LEARNING_MAPPER = new ObjectMapper();

    @Test
    void ingestArgsRoundTrip() throws Exception {
        SubprocessArgs expected = SubprocessArgs.builder()
                .taskId("trace-ingest")
                .filePath("/tmp/document.pdf")
                .loaderName("pdf")
                .chunkerName("sentence")
                .chunkSize(768)
                .chunkOverlap(96)
                .embeddingBatchSize(24)
                .indexPath("/tmp/index")
                .vectorStorePath("/tmp/vector")
                .keywordIndexPath("/tmp/keyword")
                .callbackBaseUrl("http://127.0.0.1:18080")
                .nd4jConfigJson("{\"maxThreads\":4}")
                .checkpointPath("/tmp/checkpoint")
                .resume(true)
                .modelSourceType("archive")
                .modelIdentifier("trace-embedding")
                .stagingUrl("http://127.0.0.1:18090")
                .stagingApiKey("trace-key")
                .archivePath("/tmp/model.zip")
                .memoryThresholdPercent(71)
                .memoryCriticalPercent(81)
                .memoryKillThresholdPercent(91)
                .memoryCheckIntervalMs(1234)
                .gpuMemoryThresholdPercent(72)
                .gpuMemoryCriticalPercent(82)
                .gpuMemoryKillThresholdPercent(92)
                .gpuSoftLimitPercent(62)
                .offHeapThresholdPercent(73)
                .offHeapCriticalPercent(83)
                .offHeapKillThresholdPercent(93)
                .options(Map.of("trace", true, "batch", 3))
                .build();
        Path file = expected.writeToTempFile();
        assertFileRoundTrip(expected, file, SubprocessArgs::readFromFile);
    }

    @Test
    void vectorPopulationArgsRoundTrip() throws Exception {
        VectorPopulationSubprocessArgs expected = VectorPopulationSubprocessArgs.builder()
                .taskId("trace-vector")
                .keywordIndexPath("/tmp/keyword")
                .vectorIndexPath("/tmp/vector")
                .checkpointBasePath("/tmp/vector-checkpoint")
                .embeddingBatchSize(17)
                .maxBatchSize(33)
                .queueCapacity(65)
                .parallelIndexing(false)
                .indexingWorkers(2)
                .indexingBatchAccumulationSize(5)
                .embeddingThreads(3)
                .callbackBaseUrl("http://127.0.0.1:18080")
                .nd4jConfigJson("{\"maxThreads\":3}")
                .modelSourceType("staging")
                .modelIdentifier("trace-vector-model")
                .stagingUrl("http://127.0.0.1:18090")
                .stagingApiKey("trace-key")
                .archivePath("/tmp/vector-model.zip")
                .memoryThresholdPercent(71)
                .memoryCriticalPercent(81)
                .memoryKillThresholdPercent(91)
                .memoryCheckIntervalMs(2345)
                .gpuMemoryThresholdPercent(72)
                .gpuMemoryCriticalPercent(82)
                .gpuMemoryKillThresholdPercent(92)
                .offHeapThresholdPercent(73)
                .offHeapCriticalPercent(83)
                .offHeapKillThresholdPercent(93)
                .options(Map.of("trace", "true"))
                .build();
        Path file = expected.writeToTempFile();
        assertFileRoundTrip(expected, file, VectorPopulationSubprocessArgs::fromFile);
    }

    @Test
    void modelInitArgsRoundTrip() throws Exception {
        ModelInitSubprocessArgs expected = ModelInitSubprocessArgs.builder()
                .taskId("trace-model-init")
                .modelIdentifier("trace-embedding-model")
                .modelSourceType("archive")
                .stagingUrl("http://127.0.0.1:18090")
                .stagingApiKey("trace-key")
                .archivePath("/tmp/model.zip")
                .optimalBatchSize(11)
                .maxBatchSize(22)
                .nd4jConfigJson("{\"maxThreads\":2}")
                .callbackBaseUrl("http://127.0.0.1:18080")
                .memoryThresholdPercent(71)
                .memoryCriticalPercent(81)
                .memoryKillThresholdPercent(91)
                .memoryCheckIntervalMs(3456)
                .gpuMemoryThresholdPercent(72)
                .gpuMemoryCriticalPercent(82)
                .gpuMemoryKillThresholdPercent(92)
                .offHeapThresholdPercent(73)
                .offHeapCriticalPercent(83)
                .offHeapKillThresholdPercent(93)
                .skipValidation(true)
                .validationTestText("focused native metadata trace")
                .options(Map.of("normalize", true, "dimension", 768))
                .build();
        Path file = expected.writeToTempFile();
        assertFileRoundTrip(expected, file, ModelInitSubprocessArgs::readFromFile);
    }

    @Test
    void learningArgsUseTheProductionMapConversionPath() throws Exception {
        LearningSubprocessArgs expected = new LearningSubprocessArgs(
                42L,
                "KGE_TRANSE",
                64,
                3,
                0.01,
                1.0,
                4,
                true,
                8,
                "/tmp/triples.json",
                "/tmp/embeddings.json",
                "/tmp/warm-start.json");
        Path file = expected.writeToTempFile();
        try {
            Map<String, Object> raw = LEARNING_MAPPER.readValue(
                    file.toFile(), new TypeReference<Map<String, Object>>() {});
            assertEquals(expected, LEARNING_MAPPER.convertValue(raw, LearningSubprocessArgs.class));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void reasoningLearningArgsUseTheProductionMapConversionPath() throws Exception {
        ReasoningLearningSubprocessArgs expected = new ReasoningLearningSubprocessArgs(
                43L,
                "PSL",
                "/tmp/reasoning-input.json",
                "/tmp/reasoning-output.json",
                5,
                0.02);
        Path file = expected.writeToTempFile();
        try {
            Map<String, Object> raw = LEARNING_MAPPER.readValue(
                    file.toFile(), new TypeReference<Map<String, Object>>() {});
            assertEquals(expected,
                    LEARNING_MAPPER.convertValue(raw, ReasoningLearningSubprocessArgs.class));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void pipelineServingArgsRoundTrip() throws Exception {
        PipelineServingSubprocessArgs expected = new PipelineServingSubprocessArgs(
                "{\"id\":\"trace-pipeline\"}");
        Path file = expected.writeToTempFile();
        assertFileRoundTrip(expected, file, PipelineServingSubprocessArgs::fromFile);
    }

    private static <T> void assertFileRoundTrip(
            T expected,
            Path file,
            IoReader<T> reader) throws Exception {
        try {
            assertEquals(expected, reader.read(file));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @FunctionalInterface
    private interface IoReader<T> {
        T read(Path path) throws IOException;
    }
}
