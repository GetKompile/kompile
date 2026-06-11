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

import ai.kompile.app.services.pipeline.stages.EmbeddingStage;
import ai.kompile.app.services.pipeline.stages.EmbeddingStage.EmbeddedChunk;
import ai.kompile.app.services.pipeline.stages.EmbeddingStage.EmbeddingOutput;
import ai.kompile.app.services.pipeline.stages.IndexingStage;
import ai.kompile.app.services.pipeline.stages.IndexingStage.IndexingOutput;
import ai.kompile.core.indexers.IndexerService;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link IndexingStage} — batch indexing, adaptive sizing,
 * cancellation, configuration, null indexer, and output record fields.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class IndexingStageTest {

    @Mock
    private IndexerService indexerService;

    private IndexingStage stage;

    @BeforeEach
    void setUp() {
        stage = new IndexingStage(indexerService);
    }

    private RetrievedDoc doc(String id, String text) {
        return RetrievedDoc.builder().id(id).text(text).metadata(Map.of()).build();
    }

    private EmbeddedChunk embeddedChunk(String id, String text) {
        return new EmbeddedChunk(doc(id, text), null);
    }

    private EmbeddingOutput embeddingOutput(List<EmbeddedChunk> chunks) {
        return new EmbeddingOutput(
                chunks, 100L, "test-model", "test-chunker", "test-loader", "task-1", Map.of());
    }

    // ─── Name ──────────────────────────────────────────────────────────

    @Test
    void getName() {
        assertEquals("indexing", stage.getName());
    }

    // ─── Basic indexing ────────────────────────────────────────────────

    @Test
    void process_indexesSingleChunk() throws Exception {
        EmbeddedChunk ec = embeddedChunk("c1", "hello");
        IndexingOutput output = stage.process(embeddingOutput(List.of(ec)));

        assertEquals(1, output.chunksIndexed());
        assertTrue(output.indexedDocumentIds().contains("c1"));
        assertEquals(1, output.batchCount());
        verify(indexerService).indexDocuments(anyList());
    }

    @Test
    void process_indexesMultipleChunks() throws Exception {
        List<EmbeddedChunk> chunks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            chunks.add(embeddedChunk("c" + i, "text " + i));
        }

        IndexingOutput output = stage.process(embeddingOutput(chunks));

        assertEquals(10, output.chunksIndexed());
        assertEquals(10, output.indexedDocumentIds().size());
        verify(indexerService, atLeastOnce()).indexDocuments(anyList());
    }

    // ─── Empty input ───────────────────────────────────────────────────

    @Test
    void process_emptyInput() throws Exception {
        IndexingOutput output = stage.process(embeddingOutput(List.of()));

        assertEquals(0, output.chunksIndexed());
        assertEquals(0, output.batchCount());
        assertTrue(output.indexedDocumentIds().isEmpty());
        verify(indexerService, never()).indexDocuments(anyList());
    }

    // ─── Null indexer ──────────────────────────────────────────────────

    @Test
    void process_nullIndexer_throwsRuntime() {
        IndexingStage nullStage = new IndexingStage(null);
        EmbeddedChunk ec = embeddedChunk("c1", "text");

        assertThrows(RuntimeException.class,
                () -> nullStage.process(embeddingOutput(List.of(ec))));
    }

    // ─── Batching ──────────────────────────────────────────────────────

    @Test
    void process_batchesCorrectly() throws Exception {
        // MIN_BATCH_SIZE=25, so batchSize is clamped to 25 minimum
        stage.configure(Map.of("batchSize", 25, "adaptiveBatching", false));

        List<EmbeddedChunk> chunks = new ArrayList<>();
        for (int i = 0; i < 75; i++) {
            chunks.add(embeddedChunk("c" + i, "text " + i));
        }

        IndexingOutput output = stage.process(embeddingOutput(chunks));

        assertEquals(75, output.chunksIndexed());
        // 25 + 25 + 25 = 3 batches
        assertEquals(3, output.batchCount());
        verify(indexerService, times(3)).indexDocuments(anyList());
    }

    // ─── Cancellation ──────────────────────────────────────────────────

    @Test
    void process_cancelledBefore_throwsInterrupted() {
        stage.cancel();
        assertTrue(stage.isCancelled());
        assertThrows(InterruptedException.class,
                () -> stage.process(embeddingOutput(List.of(embeddedChunk("c1", "text")))));
    }

    @Test
    void process_cancelledDuringBatch_throwsInterrupted() throws Exception {
        // MIN_BATCH_SIZE=25. Use 2 batches of 25 so cancellation triggers between batches.
        stage.configure(Map.of("batchSize", 25, "adaptiveBatching", false));
        doAnswer(inv -> {
            stage.cancel();
            return null;
        }).when(indexerService).indexDocuments(anyList());

        List<EmbeddedChunk> chunks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            chunks.add(embeddedChunk("c" + i, "text"));
        }

        assertThrows(InterruptedException.class,
                () -> stage.process(embeddingOutput(chunks)));
    }

    // ─── Reset ─────────────────────────────────────────────────────────

    @Test
    void reset_clearsCancelled() {
        stage.cancel();
        assertTrue(stage.isCancelled());
        stage.reset();
        assertFalse(stage.isCancelled());
    }

    // ─── Configure ─────────────────────────────────────────────────────

    @Test
    void configure_null_noOp() {
        assertDoesNotThrow(() -> stage.configure(null));
    }

    @Test
    void configure_batchSizeClamped() throws Exception {
        // Below minimum (25) — gets clamped to 25
        stage.configure(Map.of("batchSize", 5, "adaptiveBatching", false));
        List<EmbeddedChunk> chunks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            chunks.add(embeddedChunk("c" + i, "text"));
        }

        IndexingOutput output = stage.process(embeddingOutput(chunks));
        // Batch size clamped to MIN_BATCH_SIZE=25, so 2 batches for 50 chunks
        assertEquals(50, output.chunksIndexed());
        assertEquals(2, output.batchCount());
    }

    @Test
    void configure_indexBatchSize_overridesBatchSize() throws Exception {
        stage.configure(Map.of("indexBatchSize", 50, "adaptiveBatching", false));
        List<EmbeddedChunk> chunks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            chunks.add(embeddedChunk("c" + i, "text"));
        }

        IndexingOutput output = stage.process(embeddingOutput(chunks));
        assertEquals(100, output.chunksIndexed());
        assertEquals(2, output.batchCount());
    }

    @Test
    void configure_adaptiveBatching_canBeDisabled() throws Exception {
        stage.configure(Map.of("adaptiveBatching", false, "batchSize", 100));
        List<EmbeddedChunk> chunks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            chunks.add(embeddedChunk("c" + i, "text"));
        }

        IndexingOutput output = stage.process(embeddingOutput(chunks));
        assertEquals(100, output.chunksIndexed());
    }

    // ─── Metadata passthrough ──────────────────────────────────────────

    @Test
    void process_passesModelAndChunkerMetadata() throws Exception {
        EmbeddingOutput input = new EmbeddingOutput(
                List.of(embeddedChunk("c1", "text")),
                100L, "bge-base-en", "recursive", "pdf-loader", "task-42",
                Map.of("key", "val"));

        IndexingOutput output = stage.process(input);

        assertEquals("bge-base-en", output.embeddingModelUsed());
        assertEquals("recursive", output.chunkerUsed());
        assertEquals("pdf-loader", output.loaderUsed());
        assertEquals("task-42", output.taskId());
        assertEquals("val", output.metadata().get("key"));
    }

    // ─── Metrics ───────────────────────────────────────────────────────

    @Test
    void metrics_initiallyClean() {
        assertNotNull(stage.getMetrics());
    }

    // ─── Output record computed fields ─────────────────────────────────

    @Test
    void outputRecord_chunksPerSecond() {
        IndexingOutput out = new IndexingOutput(
                List.of("c1", "c2"), 2, 1, 1000L, "model", "chunker", "loader", "t1", Map.of());
        assertEquals(2.0, out.chunksPerSecond(), 0.01);
    }

    @Test
    void outputRecord_avgBatchSize() {
        IndexingOutput out = new IndexingOutput(
                List.of("c1", "c2", "c3", "c4"), 4, 2, 100L, "m", "c", "l", "t", Map.of());
        assertEquals(2.0, out.avgBatchSize(), 0.01);
    }

    @Test
    void outputRecord_zeroDuration_chunksPerSecondZero() {
        IndexingOutput out = new IndexingOutput(
                List.of(), 0, 0, 0L, "m", "c", "l", "t", Map.of());
        assertEquals(0.0, out.chunksPerSecond());
        assertEquals(0.0, out.avgBatchSize());
    }

    // ─── Indexer error propagates ──────────────────────────────────────

    @Test
    void process_indexerError_propagates() throws Exception {
        doThrow(new RuntimeException("Index full")).when(indexerService).indexDocuments(anyList());

        assertThrows(RuntimeException.class,
                () -> stage.process(embeddingOutput(List.of(embeddedChunk("c1", "text")))));
    }
}
