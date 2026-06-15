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

package ai.kompile.app.services.pipeline.stages;

import ai.kompile.app.services.pipeline.PipelineStage;
import ai.kompile.core.indexers.IndexerService;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Indexing stage: Stores embedded chunks in the vector store.
 *
 * <p>This stage provides:</p>
 * <ul>
 *   <li>Batched Lucene writes for efficiency</li>
 *   <li>Adaptive batch sizing based on memory</li>
 *   <li>RAM buffer optimization</li>
 *   <li>Commit frequency control</li>
 * </ul>
 *
 * <p><b>Note:</b> Lucene IndexWriter is not thread-safe, so this stage
 * processes batches sequentially. However, batching reduces commit overhead.</p>
 *
 * <p>Input: {@link EmbeddingStage.EmbeddingOutput} containing embedded chunks</p>
 * <p>Output: {@link IndexingOutput} containing indexed document IDs</p>
 */
public class IndexingStage implements PipelineStage<EmbeddingStage.EmbeddingOutput, IndexingStage.IndexingOutput> {

    private static final Logger logger = LoggerFactory.getLogger(IndexingStage.class);

    // Batch size limits
    private static final int DEFAULT_BATCH_SIZE = 100;
    private static final int MIN_BATCH_SIZE = 25;
    private static final int MAX_BATCH_SIZE = 500;

    private final IndexerService indexerService;
    private final StageMetrics metrics = new StageMetrics();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    // Configuration
    private int batchSize = DEFAULT_BATCH_SIZE;
    private boolean adaptiveBatching = true;

    public IndexingStage(IndexerService indexerService) {
        this.indexerService = indexerService;
    }

    @Override
    public String getName() {
        return "indexing";
    }

    @Override
    public IndexingOutput process(EmbeddingStage.EmbeddingOutput input) throws Exception {
        if (cancelled.get()) {
            throw new InterruptedException("Indexing stage cancelled");
        }

        long startNanos = System.nanoTime();
        List<String> indexedIds = new ArrayList<>();
        int totalChunks = input.chunkCount();
        long totalBytes = 0;

        try {
            if (indexerService == null) {
                throw new RuntimeException("No indexer service configured");
            }

            // Calculate effective batch size
            int effectiveBatchSize = calculateBatchSize(totalChunks);
            logger.debug("Indexing {} chunks with batch size {}", totalChunks, effectiveBatchSize);

            // Convert embedded chunks to RetrievedDocs for indexing
            List<EmbeddingStage.EmbeddedChunk> embeddedChunks = input.embeddedChunks();
            List<RetrievedDoc> currentBatch = new ArrayList<>(effectiveBatchSize);
            int batchCount = 0;
            int chunksIndexed = 0;

            for (int i = 0; i < totalChunks; i++) {
                if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Indexing cancelled during batch processing");
                }

                EmbeddingStage.EmbeddedChunk embeddedChunk = embeddedChunks.get(i);
                RetrievedDoc chunk = embeddedChunk.chunk();

                // Add embedding to metadata if present
                if (embeddedChunk.hasEmbedding()) {
                    Map<String, Object> metadata = new HashMap<>(
                            chunk.getMetadata() != null ? chunk.getMetadata() : Map.of()
                    );
                    metadata.put("_embedding", embeddedChunk.embedding());
                    chunk = RetrievedDoc.builder()
                            .id(chunk.getId())
                            .text(chunk.getText())
                            .metadata(metadata)
                            .build();
                }

                currentBatch.add(chunk);
                String text = chunk.getText();
                if (text != null) {
                    totalBytes += text.length() * 2;
                }

                boolean isLastChunk = (i == totalChunks - 1);

                // Index when batch is full or this is the last chunk
                if (currentBatch.size() >= effectiveBatchSize || isLastChunk) {
                    long batchStartNanos = System.nanoTime();

                    // Index the batch
                    indexerService.indexDocuments(currentBatch);

                    // Track indexed IDs
                    for (RetrievedDoc doc : currentBatch) {
                        if (doc.getId() != null) {
                            indexedIds.add(doc.getId());
                        }
                    }

                    chunksIndexed += currentBatch.size();
                    batchCount++;

                    long batchTimeMs = (System.nanoTime() - batchStartNanos) / 1_000_000;
                    double batchThroughput = batchTimeMs > 0 ?
                            (currentBatch.size() * 1000.0) / batchTimeMs : 0;

                    logger.debug("Batch {}: indexed {} chunks in {}ms ({}/sec) [{}/{}]",
                            batchCount, currentBatch.size(), batchTimeMs,
                            String.format("%.0f", batchThroughput), chunksIndexed, totalChunks);

                    // MEMORY LEAK FIX: Close embedding INDArrays after they've been indexed
                    // The embeddings are stored in metadata and passed to the indexer.
                    // After indexing, they're no longer needed and must be explicitly closed
                    // to release native memory.
                    for (RetrievedDoc doc : currentBatch) {
                        if (doc.getMetadata() != null) {
                            Object embedding = doc.getMetadata().get("_embedding");
                            if (embedding instanceof org.nd4j.linalg.api.ndarray.INDArray) {
                                try {
                                    ((org.nd4j.linalg.api.ndarray.INDArray) embedding).close();
                                } catch (Exception e) {
                                    logger.trace("Error closing embedding array: {}", e.getMessage());
                                }
                            }
                        }
                    }

                    currentBatch.clear();

                    // Adaptive batch sizing
                    if (adaptiveBatching) {
                        effectiveBatchSize = adaptBatchSize(effectiveBatchSize, batchTimeMs);
                    }
                }
            }

            long elapsedNanos = System.nanoTime() - startNanos;
            metrics.recordSuccess(elapsedNanos, totalBytes, chunksIndexed);

            double throughput = (elapsedNanos / 1_000_000) > 0 ?
                    (chunksIndexed * 1000.0) / (elapsedNanos / 1_000_000) : 0;
            logger.debug("Indexed {} chunks in {} batches, {}ms ({} chunks/sec)",
                    chunksIndexed, batchCount, elapsedNanos / 1_000_000, throughput);

            return new IndexingOutput(
                    indexedIds,
                    chunksIndexed,
                    batchCount,
                    elapsedNanos / 1_000_000,
                    input.embeddingModelUsed(),
                    input.chunkerUsed(),
                    input.loaderUsed(),
                    input.taskId(),
                    input.metadata()
            );

        } catch (Exception e) {
            metrics.recordFailure();
            throw e;
        }
    }

    private int calculateBatchSize(int totalChunks) {
        if (!adaptiveBatching) {
            return batchSize;
        }

        int size = batchSize;

        // For small chunk counts, use smaller batches
        if (totalChunks < 100) {
            size = Math.min(size, Math.max(MIN_BATCH_SIZE, totalChunks / 4));
        }

        // Don't have too many batches (max ~50)
        int maxBatches = 50;
        int minBatchForChunks = (int) Math.ceil((double) totalChunks / maxBatches);
        size = Math.max(size, minBatchForChunks);

        // Check memory pressure
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        double memoryUsage = (usedMemory * 100.0) / maxMemory;

        if (memoryUsage > 85) {
            size = Math.max(MIN_BATCH_SIZE, size / 2);
            logger.debug("Memory pressure ({}%), reducing batch size to {}", memoryUsage, size);
        } else if (memoryUsage > 70) {
            size = Math.max(MIN_BATCH_SIZE, (int) (size * 0.75));
        }

        return Math.max(MIN_BATCH_SIZE, Math.min(size, MAX_BATCH_SIZE));
    }

    private int adaptBatchSize(int currentSize, long batchTimeMs) {
        // If batch takes too long (>10 seconds), reduce size
        if (batchTimeMs > 10000) {
            int newSize = Math.max(MIN_BATCH_SIZE, currentSize / 2);
            if (newSize < currentSize) {
                logger.debug("Batch took {}ms, reducing size {} -> {}", batchTimeMs, currentSize, newSize);
                return newSize;
            }
        }

        // Check memory for potential increase
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        double memoryUsage = (usedMemory * 100.0) / maxMemory;

        if (memoryUsage > 80) {
            int newSize = Math.max(MIN_BATCH_SIZE, currentSize / 2);
            if (newSize < currentSize) {
                logger.debug("Memory pressure {}%, reducing batch size {} -> {}",
                        memoryUsage, currentSize, newSize);
                return newSize;
            }
        }

        return currentSize;
    }

    @Override
    public void configure(Map<String, Object> options) {
        if (options == null) return;

        if (options.containsKey("batchSize")) {
            this.batchSize = ((Number) options.get("batchSize")).intValue();
            this.batchSize = Math.max(MIN_BATCH_SIZE, Math.min(this.batchSize, MAX_BATCH_SIZE));
        }
        if (options.containsKey("indexBatchSize")) {
            this.batchSize = ((Number) options.get("indexBatchSize")).intValue();
            this.batchSize = Math.max(MIN_BATCH_SIZE, Math.min(this.batchSize, MAX_BATCH_SIZE));
        }
        if (options.containsKey("adaptiveBatching")) {
            this.adaptiveBatching = (Boolean) options.get("adaptiveBatching");
        }
    }

    @Override
    public StageMetrics getMetrics() {
        return metrics;
    }

    @Override
    public void cancel() {
        cancelled.set(true);
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public void reset() {
        cancelled.set(false);
        metrics.reset();
    }

    /**
     * Output from the indexing stage.
     */
    public record IndexingOutput(
            List<String> indexedDocumentIds,
            int chunksIndexed,
            int batchCount,
            long indexingTimeMs,
            String embeddingModelUsed,
            String chunkerUsed,
            String loaderUsed,
            String taskId,
            Map<String, Object> metadata
    ) {
        public double chunksPerSecond() {
            return indexingTimeMs > 0 ? (chunksIndexed * 1000.0) / indexingTimeMs : 0;
        }

        public double avgBatchSize() {
            return batchCount > 0 ? (double) chunksIndexed / batchCount : 0;
        }
    }
}
