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

import ai.kompile.app.services.pipeline.IngestPipelineConfig;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the split-queue architecture in {@link ParallelIngestPipeline}.
 * These tests verify the queue behavior directly without requiring ND4J native libraries,
 * since the actual pipeline constructor depends on ND4J initialization.
 *
 * <p>The split-queue architecture ensures that indexing and embedding workers
 * consume from independent queues, preventing embedding starvation when
 * indexing workers are faster.</p>
 */
class ParallelIngestPipelineTest {

    /**
     * Verifies that with split queues, offering a chunk to both queues independently
     * means a fast consumer on one queue does not starve the other.
     */
    @Test
    void splitQueuesPreventStarvation() throws InterruptedException {
        BlockingQueue<RetrievedDoc> indexingQueue = new LinkedBlockingQueue<>(100);
        BlockingQueue<RetrievedDoc> embeddingQueue = new LinkedBlockingQueue<>(100);

        // Simulate chunker producing 10 chunks to BOTH queues
        for (int i = 0; i < 10; i++) {
            RetrievedDoc chunk = new RetrievedDoc("chunk-" + i, "content " + i, new HashMap<>());
            assertTrue(indexingQueue.offer(chunk, 1, TimeUnit.SECONDS));
            assertTrue(embeddingQueue.offer(chunk, 1, TimeUnit.SECONDS));
        }

        // Simulate fast indexing worker draining its queue completely
        while (!indexingQueue.isEmpty()) {
            indexingQueue.poll();
        }

        // Embedding queue should still have all 10 chunks
        assertEquals(10, embeddingQueue.size(),
                "Embedding queue must retain all chunks even after indexing queue is fully drained");

        // Simulate slow embedding worker processing one at a time
        int embeddedCount = 0;
        while (!embeddingQueue.isEmpty()) {
            RetrievedDoc chunk = embeddingQueue.poll(100, TimeUnit.MILLISECONDS);
            assertNotNull(chunk);
            embeddedCount++;
        }
        assertEquals(10, embeddedCount, "Embedding worker should process all 10 chunks");
    }

    /**
     * With the old single-queue design, fast consumers would drain the shared queue,
     * starving slow consumers. This test demonstrates the failure mode that split
     * queues fix.
     */
    @Test
    void singleQueueCausesStarvation() throws InterruptedException {
        // Old design: single shared queue
        BlockingQueue<RetrievedDoc> sharedQueue = new LinkedBlockingQueue<>(100);

        // Producer adds 10 chunks
        for (int i = 0; i < 10; i++) {
            sharedQueue.offer(new RetrievedDoc("chunk-" + i, "content " + i, new HashMap<>()));
        }

        // Fast indexing consumer drains the queue
        int indexedCount = 0;
        while (!sharedQueue.isEmpty()) {
            sharedQueue.poll();
            indexedCount++;
        }
        assertEquals(10, indexedCount);

        // Slow embedding consumer finds nothing left — THIS IS THE BUG
        assertEquals(0, sharedQueue.size(),
                "In single-queue design, embedding worker finds empty queue (starvation)");
    }

    /**
     * Verifies that independent offering to both queues handles backpressure
     * correctly — if one queue is full, the other can still accept.
     */
    @Test
    void splitQueuesHandleBackpressureIndependently() throws InterruptedException {
        // Small capacity to trigger backpressure
        BlockingQueue<RetrievedDoc> indexingQueue = new LinkedBlockingQueue<>(3);
        BlockingQueue<RetrievedDoc> embeddingQueue = new LinkedBlockingQueue<>(3);

        // Fill indexing queue to capacity
        for (int i = 0; i < 3; i++) {
            RetrievedDoc chunk = new RetrievedDoc("c" + i, "t" + i, new HashMap<>());
            assertTrue(indexingQueue.offer(chunk));
            assertTrue(embeddingQueue.offer(chunk));
        }

        // Indexing queue is full
        RetrievedDoc overflow = new RetrievedDoc("overflow", "text", new HashMap<>());
        assertFalse(indexingQueue.offer(overflow), "Indexing queue should be full");
        assertFalse(embeddingQueue.offer(overflow), "Embedding queue should be full");

        // Drain only the indexing queue
        indexingQueue.clear();

        // Now indexing queue can accept, but embedding queue is still full
        assertTrue(indexingQueue.offer(overflow), "Indexing queue should accept after drain");
        assertFalse(embeddingQueue.offer(overflow), "Embedding queue should still be full");
    }

    /**
     * Verifies that queue size reporting works with split queues.
     * The combined size should represent the total outstanding work.
     */
    @Test
    void combinedQueueSizeReportsCorrectly() {
        BlockingQueue<RetrievedDoc> indexingQueue = new LinkedBlockingQueue<>(100);
        BlockingQueue<RetrievedDoc> embeddingQueue = new LinkedBlockingQueue<>(100);

        // Add different amounts to each queue
        for (int i = 0; i < 5; i++) {
            indexingQueue.offer(new RetrievedDoc("c" + i, "t", new HashMap<>()));
        }
        for (int i = 0; i < 8; i++) {
            embeddingQueue.offer(new RetrievedDoc("c" + i, "t", new HashMap<>()));
        }

        int combinedSize = indexingQueue.size() + embeddingQueue.size();
        assertEquals(13, combinedSize);

        // Drain indexing
        indexingQueue.clear();
        assertEquals(8, indexingQueue.size() + embeddingQueue.size());
    }

    /**
     * Verifies IngestPipelineConfig defaults.
     */
    @Test
    void pipelineConfigDefaults() {
        IngestPipelineConfig config = IngestPipelineConfig.defaults();

        assertTrue(config.queueCapacity() > 0, "Queue capacity should be positive");
        assertTrue(config.chunkingThreads() >= 1, "Must have at least 1 chunking thread");
        assertTrue(config.embeddingThreads() >= 1, "Must have at least 1 embedding thread");
        assertTrue(config.indexingThreads() >= 1, "Must have at least 1 indexing thread");
        assertFalse(config.skipEmbedding(), "skipEmbedding should default to false");
    }

    /**
     * Verifies IngestPipelineConfig builder with skipEmbedding.
     */
    @Test
    void pipelineConfigSkipEmbedding() {
        IngestPipelineConfig config = IngestPipelineConfig.builder()
                .skipEmbedding(true)
                .build();

        assertTrue(config.skipEmbedding());
    }

    /**
     * Verifies PipelineResult calculations.
     */
    @Test
    void pipelineResultCalculations() {
        PipelineResult result = new PipelineResult(5, 20, 20, 1000, 5000, java.util.List.of("d1", "d2"));

        assertEquals(5, result.documentsProcessed());
        assertEquals(20, result.chunksCreated());
        assertEquals(20, result.chunksIndexed());
        assertEquals(4.0, result.getChunksPerSecond(), 0.001);
        assertEquals(java.util.List.of("d1", "d2"), result.processedDocumentIds());
    }

    @Test
    void pipelineResultZeroTimeGivesZeroThroughput() {
        PipelineResult result = new PipelineResult(0, 0, 0, 0, 0, java.util.List.of());
        assertEquals(0.0, result.getChunksPerSecond());
    }
}
