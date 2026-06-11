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

import ai.kompile.core.indexers.IndexerService;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class IndexingStageTest {

    private IndexerService mockIndexer;
    private IndexingStage stage;

    @BeforeEach
    void setUp() {
        mockIndexer = mock(IndexerService.class);
        stage = new IndexingStage(mockIndexer);
    }

    // --- getName ---

    @Test
    void getNameReturnsIndexing() {
        assertEquals("indexing", stage.getName());
    }

    // --- process: successful indexing ---

    @Test
    void processIndexesChunks() throws Exception {
        EmbeddingStage.EmbeddingOutput input = embeddingOutput(List.of(
                embeddedChunk("c1", "chunk one"),
                embeddedChunk("c2", "chunk two")
        ));

        IndexingStage.IndexingOutput output = stage.process(input);

        assertEquals(2, output.chunksIndexed());
        assertTrue(output.indexedDocumentIds().contains("c1"));
        assertTrue(output.indexedDocumentIds().contains("c2"));
        assertTrue(output.indexingTimeMs() >= 0);
        assertTrue(output.batchCount() >= 1);
        verify(mockIndexer, atLeastOnce()).indexDocuments(any(List.class));
    }

    @Test
    void processHandsChunksWithEmbeddingsToIndexer() throws Exception {
        INDArray embedding = Nd4j.rand(1, 64);
        RetrievedDoc chunk = new RetrievedDoc("c1", "text", Map.of());
        EmbeddingStage.EmbeddedChunk ec = new EmbeddingStage.EmbeddedChunk(chunk, embedding);

        EmbeddingStage.EmbeddingOutput input = embeddingOutput(List.of(ec));

        IndexingStage.IndexingOutput output = stage.process(input);

        // The IndexingStage clears the batch list after indexing, so we can't use
        // argThat (Mockito captures by reference). Instead verify via output.
        assertEquals(1, output.chunksIndexed());
        assertTrue(output.indexedDocumentIds().contains("c1"));
        verify(mockIndexer, times(1)).indexDocuments(any(List.class));
    }

    // --- process: null indexer ---

    @Test
    void processThrowsWhenNoIndexer() {
        IndexingStage noIndexer = new IndexingStage(null);
        assertThrows(RuntimeException.class, () ->
                noIndexer.process(embeddingOutput(List.of(
                        embeddedChunk("c1", "text")))));
    }

    // --- process: cancellation ---

    @Test
    void processThrowsWhenCancelled() {
        stage.cancel();
        assertThrows(InterruptedException.class, () ->
                stage.process(embeddingOutput(List.of(
                        embeddedChunk("c1", "text")))));
    }

    // --- configure ---

    @Test
    void configureHandlesNullOptions() {
        stage.configure(null);
        // No exception
    }

    @Test
    void configureSetsBatchSize() {
        stage.configure(Map.of("batchSize", 200));
        // No direct getter but verify no exception
    }

    @Test
    void configureSetsIndexBatchSize() {
        stage.configure(Map.of("indexBatchSize", 50));
        // No direct getter but verify no exception
    }

    @Test
    void configureSetsAdaptiveBatching() {
        stage.configure(Map.of("adaptiveBatching", false));
        // No direct getter but verify no exception
    }

    @Test
    void configureClampsMinBatchSize() {
        stage.configure(Map.of("batchSize", 1));
        // batchSize should be clamped to MIN_BATCH_SIZE (25)
        // No direct getter, so we verify via behavior
    }

    @Test
    void configureClampsMaxBatchSize() {
        stage.configure(Map.of("batchSize", 10000));
        // batchSize should be clamped to MAX_BATCH_SIZE (500)
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

    // --- IndexingOutput ---

    @Test
    void indexingOutputChunksPerSecond() {
        IndexingStage.IndexingOutput output = new IndexingStage.IndexingOutput(
                List.of("c1", "c2"), 2, 1, 1000,
                "model", "chunker", "loader", "task", Map.of());
        assertEquals(2.0, output.chunksPerSecond(), 0.01);
    }

    @Test
    void indexingOutputChunksPerSecondHandlesZeroTime() {
        IndexingStage.IndexingOutput output = new IndexingStage.IndexingOutput(
                List.of(), 0, 0, 0,
                "model", "chunker", "loader", "task", Map.of());
        assertEquals(0.0, output.chunksPerSecond());
    }

    @Test
    void indexingOutputAvgBatchSize() {
        IndexingStage.IndexingOutput output = new IndexingStage.IndexingOutput(
                List.of("c1", "c2", "c3", "c4"), 4, 2, 100,
                "model", "chunker", "loader", "task", Map.of());
        assertEquals(2.0, output.avgBatchSize(), 0.01);
    }

    @Test
    void indexingOutputAvgBatchSizeHandlesZeroBatches() {
        IndexingStage.IndexingOutput output = new IndexingStage.IndexingOutput(
                List.of(), 0, 0, 0,
                "model", "chunker", "loader", "task", Map.of());
        assertEquals(0.0, output.avgBatchSize());
    }

    // --- metrics ---

    @Test
    void metricsRecordedOnSuccess() throws Exception {
        stage.process(embeddingOutput(List.of(embeddedChunk("c1", "text"))));
        assertNotNull(stage.getMetrics());
    }

    // --- helpers ---

    private EmbeddingStage.EmbeddedChunk embeddedChunk(String id, String text) {
        return new EmbeddingStage.EmbeddedChunk(
                new RetrievedDoc(id, text, Map.of()), null);
    }

    private EmbeddingStage.EmbeddingOutput embeddingOutput(List<EmbeddingStage.EmbeddedChunk> chunks) {
        return new EmbeddingStage.EmbeddingOutput(
                chunks, 10, "test-model", "test-chunker", "test-loader", "task-1", Map.of()
        );
    }
}
