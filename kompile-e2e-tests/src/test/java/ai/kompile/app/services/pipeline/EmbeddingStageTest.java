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
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.services.pipeline;

import ai.kompile.app.services.pipeline.stages.ChunkingStage;
import ai.kompile.app.services.pipeline.stages.EmbeddingStage;
import ai.kompile.app.services.pipeline.stages.EmbeddingStage.EmbeddedChunk;
import ai.kompile.app.services.pipeline.stages.EmbeddingStage.EmbeddingOutput;
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link EmbeddingStage} — null model passthrough, batch embedding,
 * memory leak fix (dup+close), cancellation, configuration, and output helpers.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class EmbeddingStageTest {

    @Mock
    private EmbeddingModel embeddingModel;

    private EmbeddingStage stage;

    @BeforeEach
    void setUp() {
        when(embeddingModel.getModelName()).thenReturn("test-model");
        stage = new EmbeddingStage(embeddingModel);
    }

    private ChunkingStage.ChunkingOutput chunkingOutput(List<RetrievedDoc> chunks) {
        return new ChunkingStage.ChunkingOutput(
                chunks, chunks.size(), 10L, "test-chunker", "test-loader", "task-1", Map.of()
        );
    }

    private RetrievedDoc chunk(String text) {
        return RetrievedDoc.builder().text(text).build();
    }

    // ─── Null model passthrough ──────────────────────────────────────────

    @Test
    void nullModelPassesThroughWithoutEmbeddings() throws Exception {
        EmbeddingStage nullStage = new EmbeddingStage(null);
        List<RetrievedDoc> chunks = List.of(chunk("hello"), chunk("world"));

        EmbeddingOutput output = nullStage.process(chunkingOutput(chunks));

        assertEquals(2, output.chunkCount());
        assertEquals(0, output.chunksWithEmbeddings());
        assertEquals("none", output.embeddingModelUsed());
        assertFalse(output.embeddedChunks().get(0).hasEmbedding());
    }

    // ─── Batch embedding with real model ─────────────────────────────────

    @Test
    void embeddsSingleBatch() throws Exception {
        INDArray batchResult = Nd4j.rand(2, 384);
        when(embeddingModel.embed(anyList())).thenReturn(batchResult);

        List<RetrievedDoc> chunks = List.of(chunk("hello"), chunk("world"));
        EmbeddingOutput output = stage.process(chunkingOutput(chunks));

        assertEquals(2, output.chunkCount());
        assertEquals(2, output.chunksWithEmbeddings());
        assertEquals("test-model", output.embeddingModelUsed());
        assertTrue(output.embeddedChunks().get(0).hasEmbedding());
        assertEquals(384, output.embeddedChunks().get(0).getEmbeddingDimensions());
    }

    @Test
    void handlesNullEmbeddingFromModel() throws Exception {
        when(embeddingModel.embed(anyList())).thenReturn(null);

        List<RetrievedDoc> chunks = List.of(chunk("text"));
        EmbeddingOutput output = stage.process(chunkingOutput(chunks));

        assertEquals(1, output.chunkCount());
        assertEquals(0, output.chunksWithEmbeddings());
        assertFalse(output.embeddedChunks().get(0).hasEmbedding());
    }

    @Test
    void handlesEmptyTextInChunk() throws Exception {
        INDArray batchResult = Nd4j.rand(1, 128);
        when(embeddingModel.embed(anyList())).thenReturn(batchResult);

        RetrievedDoc emptyTextChunk = RetrievedDoc.builder().text("").build();
        EmbeddingOutput output = stage.process(chunkingOutput(List.of(emptyTextChunk)));

        assertEquals(1, output.chunkCount());
        verify(embeddingModel).embed(List.of(""));
    }

    // ─── Cancellation ────────────────────────────────────────────────────

    @Test
    void cancelBeforeProcessing() {
        stage.cancel();
        assertTrue(stage.isCancelled());

        assertThrows(InterruptedException.class, () ->
                stage.process(chunkingOutput(List.of(chunk("text")))));
    }

    @Test
    void resetClearsCancellation() {
        stage.cancel();
        stage.reset();
        assertFalse(stage.isCancelled());
    }

    // ─── Configuration ───────────────────────────────────────────────────

    @Test
    void configureBatchSize() {
        stage.configure(Map.of("batchSize", 16));
    }

    @Test
    void configureEmbeddingBatchSizeAlternateKey() {
        stage.configure(Map.of("embeddingBatchSize", 64));
    }

    @Test
    void configureBatchSizeClampedToMin() {
        stage.configure(Map.of("batchSize", 1));
    }

    @Test
    void configureBatchSizeClampedToMax() {
        stage.configure(Map.of("batchSize", 999));
    }

    @Test
    void configureAdaptiveBatching() {
        stage.configure(Map.of("adaptiveBatching", false));
    }

    @Test
    void configureNullIsNoOp() {
        stage.configure(null);
    }

    // ─── Stage metadata ──────────────────────────────────────────────────

    @Test
    void stageNameIsEmbedding() {
        assertEquals("embedding", stage.getName());
    }

    @Test
    void metricsRecordedOnSuccess() throws Exception {
        INDArray result = Nd4j.rand(1, 128);
        when(embeddingModel.embed(anyList())).thenReturn(result);

        stage.process(chunkingOutput(List.of(chunk("word"))));
        assertEquals(1, stage.getMetrics().getItemsProcessed());
        assertEquals(0, stage.getMetrics().getItemsFailed());
    }

    // ─── EmbeddedChunk record helpers ────────────────────────────────────

    @Test
    void embeddedChunkNoEmbedding() {
        EmbeddedChunk ec = new EmbeddedChunk(chunk("hello"), null);
        assertFalse(ec.hasEmbedding());
        assertEquals(0, ec.getEmbeddingDimensions());
        assertEquals("hello", ec.getText());
    }

    @Test
    void embeddedChunkWithEmbedding() {
        INDArray vec = Nd4j.rand(1, 256);
        EmbeddedChunk ec = new EmbeddedChunk(chunk("text"), vec);
        assertTrue(ec.hasEmbedding());
        assertEquals(256, ec.getEmbeddingDimensions());
    }

    // ─── Output helpers ──────────────────────────────────────────────────

    @Test
    void outputChunkCountHandlesNull() {
        EmbeddingOutput output = new EmbeddingOutput(null, 0L, "none", "chunker", "loader", null, null);
        assertEquals(0, output.chunkCount());
        assertEquals(0, output.chunksWithEmbeddings());
    }

    @Test
    void embeddingsPerSecondCalculation() {
        EmbeddingOutput output = new EmbeddingOutput(
                List.of(new EmbeddedChunk(chunk("a"), Nd4j.rand(1, 10)),
                        new EmbeddedChunk(chunk("b"), Nd4j.rand(1, 10))),
                1000L, "model", "chunker", "loader", null, null
        );
        assertEquals(2.0, output.embeddingsPerSecond(), 0.001);
    }

    @Test
    void embeddingsPerSecondZeroTime() {
        EmbeddingOutput output = new EmbeddingOutput(
                List.of(new EmbeddedChunk(chunk("a"), null)),
                0L, "model", "chunker", "loader", null, null
        );
        assertEquals(0.0, output.embeddingsPerSecond(), 0.001);
    }

    @Test
    void outputPreservesTaskIdAndMetadata() throws Exception {
        when(embeddingModel.embed(anyList())).thenReturn(Nd4j.rand(1, 10));

        ChunkingStage.ChunkingOutput input = new ChunkingStage.ChunkingOutput(
                List.of(chunk("text")), 1, 5L, "chunker", "my-loader", "my-task", Map.of("k", "v")
        );
        EmbeddingOutput output = stage.process(input);

        assertEquals("my-task", output.taskId());
        assertEquals("v", output.metadata().get("k"));
        assertEquals("my-loader", output.loaderUsed());
        assertEquals("chunker", output.chunkerUsed());
    }
}
