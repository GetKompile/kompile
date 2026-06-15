/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.services.pipeline.stages;

import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EmbeddingStageTest {

    private EmbeddingModel mockModel;
    private EmbeddingStage stage;

    @BeforeEach
    void setUp() {
        mockModel = mock(EmbeddingModel.class);
        when(mockModel.getModelName()).thenReturn("test-embedding-model");
        stage = new EmbeddingStage(mockModel);
    }

    // --- getName ---

    @Test
    void getNameReturnsEmbedding() {
        assertEquals("embedding", stage.getName());
    }

    // --- process: successful embedding ---

    @Test
    void processEmbedsChunks() throws Exception {
        assumeNd4jAvailable();
        INDArray batchEmbeddings = Nd4j.rand(2, 128);
        when(mockModel.embed(any(List.class))).thenReturn(batchEmbeddings);

        ChunkingStage.ChunkingOutput input = chunkingOutput(List.of(
                new RetrievedDoc("c1", "chunk one", Map.of()),
                new RetrievedDoc("c2", "chunk two", Map.of())
        ));

        EmbeddingStage.EmbeddingOutput output = stage.process(input);

        assertEquals(2, output.chunkCount());
        assertEquals(2, output.chunksWithEmbeddings());
        assertEquals("test-embedding-model", output.embeddingModelUsed());
        assertTrue(output.embeddingTimeMs() >= 0);
    }

    @Test
    void processEmbeddedChunkHasCorrectDimensions() throws Exception {
        assumeNd4jAvailable();
        INDArray batchEmbeddings = Nd4j.rand(1, 64);
        when(mockModel.embed(any(List.class))).thenReturn(batchEmbeddings);

        ChunkingStage.ChunkingOutput input = chunkingOutput(List.of(
                new RetrievedDoc("c1", "text", Map.of())
        ));

        EmbeddingStage.EmbeddingOutput output = stage.process(input);

        EmbeddingStage.EmbeddedChunk embeddedChunk = output.embeddedChunks().get(0);
        assertTrue(embeddedChunk.hasEmbedding());
        assertEquals(64, embeddedChunk.getEmbeddingDimensions());
        assertEquals("c1", embeddedChunk.getId());
        assertEquals("text", embeddedChunk.getText());
    }

    // --- process: null model ---

    @Test
    void processWithNullModelPassesThroughWithoutEmbeddings() throws Exception {
        EmbeddingStage noModelStage = new EmbeddingStage(null);
        ChunkingStage.ChunkingOutput input = chunkingOutput(List.of(
                new RetrievedDoc("c1", "text", Map.of())
        ));

        EmbeddingStage.EmbeddingOutput output = noModelStage.process(input);

        assertEquals(1, output.chunkCount());
        assertEquals(0, output.chunksWithEmbeddings());
        assertEquals("none", output.embeddingModelUsed());
    }

    // --- process: cancellation ---

    @Test
    void processThrowsWhenCancelled() {
        stage.cancel();
        assertThrows(InterruptedException.class, () ->
                stage.process(chunkingOutput(List.of(
                        new RetrievedDoc("c1", "text", Map.of())))));
    }

    // --- configure ---

    @Test
    void configureHandlesNullOptions() {
        stage.configure(null);
        // No exception
    }

    @Test
    void configureSetsBatchSize() {
        stage.configure(Map.of("batchSize", 64));
        // No direct getter but verify no exception
    }

    @Test
    void configureSetsEmbeddingBatchSize() {
        stage.configure(Map.of("embeddingBatchSize", 16));
        // No direct getter but verify no exception
    }

    @Test
    void configureSetsAdaptiveBatching() {
        stage.configure(Map.of("adaptiveBatching", false));
        // No direct getter but verify no exception
    }

    // --- cancel / reset ---

    @Test
    void cancelAndResetCycle() {
        assertFalse(stage.isCancelled());
        stage.cancel();
        assertTrue(stage.isCancelled());
        stage.reset();
        assertFalse(stage.isCancelled());
    }

    // --- EmbeddedChunk ---

    @Test
    void embeddedChunkWithoutEmbedding() {
        RetrievedDoc chunk = new RetrievedDoc("id", "text", Map.of("key", "val"));
        EmbeddingStage.EmbeddedChunk ec = new EmbeddingStage.EmbeddedChunk(chunk, null);

        assertFalse(ec.hasEmbedding());
        assertEquals(0, ec.getEmbeddingDimensions());
        assertEquals("id", ec.getId());
        assertEquals("text", ec.getText());
        assertEquals("val", ec.getMetadata().get("key"));
    }

    // --- EmbeddingOutput ---

    @Test
    void embeddingOutputChunkCountHandlesNull() {
        EmbeddingStage.EmbeddingOutput output = new EmbeddingStage.EmbeddingOutput(
                null, 0, "model", "chunker", "loader", "task", Map.of());
        assertEquals(0, output.chunkCount());
        assertEquals(0, output.chunksWithEmbeddings());
    }

    @Test
    void embeddingOutputEmbeddingsPerSecond() {
        EmbeddingStage.EmbeddingOutput output = new EmbeddingStage.EmbeddingOutput(
                List.of(
                        new EmbeddingStage.EmbeddedChunk(new RetrievedDoc("c1", "a", Map.of()), null),
                        new EmbeddingStage.EmbeddedChunk(new RetrievedDoc("c2", "b", Map.of()), null)
                ), 1000, "model", "chunker", "loader", "task", Map.of());
        assertEquals(2.0, output.embeddingsPerSecond(), 0.01);
    }

    @Test
    void embeddingOutputEmbeddingsPerSecondHandlesZeroTime() {
        EmbeddingStage.EmbeddingOutput output = new EmbeddingStage.EmbeddingOutput(
                List.of(), 0, "model", "chunker", "loader", "task", Map.of());
        assertEquals(0.0, output.embeddingsPerSecond());
    }

    // --- metrics ---

    @Test
    void metricsRecordedOnSuccess() throws Exception {
        assumeNd4jAvailable();
        INDArray batchEmbeddings = Nd4j.rand(1, 32);
        when(mockModel.embed(any(List.class))).thenReturn(batchEmbeddings);

        stage.process(chunkingOutput(List.of(
                new RetrievedDoc("c1", "text", Map.of()))));
        assertNotNull(stage.getMetrics());
    }

    // --- helpers ---

    private static void assumeNd4jAvailable() {
        try {
            Class.forName("org.nd4j.linalg.factory.Nd4j");
            Nd4j.scalar(1.0);
        } catch (Throwable t) {
            Assumptions.assumeTrue(false, "ND4J native backend not available");
        }
    }

    private ChunkingStage.ChunkingOutput chunkingOutput(List<RetrievedDoc> chunks) {
        return new ChunkingStage.ChunkingOutput(
                chunks, chunks.size(), 0, "test-chunker", "test-loader", "task-1", Map.of()
        );
    }
}
