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
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Embedding stage: Generates vector embeddings for document chunks.
 *
 * <p>This stage provides:</p>
 * <ul>
 *   <li>Batched embedding generation for efficiency</li>
 *   <li>Adaptive batch sizing based on memory</li>
 *   <li>Interrupt handling for cancellation</li>
 *   <li>Caching support (optional)</li>
 * </ul>
 *
 * <p>Input: {@link ChunkingStage.ChunkingOutput} containing document chunks</p>
 * <p>Output: {@link EmbeddingOutput} containing embedded chunks</p>
 */
public class EmbeddingStage implements PipelineStage<ChunkingStage.ChunkingOutput, EmbeddingStage.EmbeddingOutput> {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddingStage.class);

    // Default batch sizes
    private static final int DEFAULT_BATCH_SIZE = 32;
    private static final int MIN_BATCH_SIZE = 8;
    private static final int MAX_BATCH_SIZE = 128;

    private final EmbeddingModel embeddingModel;
    private final StageMetrics metrics = new StageMetrics();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    // Configuration
    private int batchSize = DEFAULT_BATCH_SIZE;
    private boolean adaptiveBatching = true;

    public EmbeddingStage(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public String getName() {
        return "embedding";
    }

    @Override
    public EmbeddingOutput process(ChunkingStage.ChunkingOutput input) throws Exception {
        if (cancelled.get()) {
            throw new InterruptedException("Embedding stage cancelled");
        }

        long startNanos = System.nanoTime();
        List<EmbeddedChunk> embeddedChunks = new ArrayList<>();
        int totalChunks = input.chunkCount();
        long totalBytes = 0;

        try {
            if (embeddingModel == null) {
                // No embedding model - pass through chunks without embeddings
                logger.warn("No embedding model configured, chunks will not have embeddings");
                for (RetrievedDoc chunk : input.chunks()) {
                    embeddedChunks.add(new EmbeddedChunk(chunk, null));
                }
                long elapsedNanos = System.nanoTime() - startNanos;
                metrics.recordSuccess(elapsedNanos, 0, totalChunks);
                return createOutput(embeddedChunks, input, elapsedNanos);
            }

            // Calculate effective batch size based on memory
            int effectiveBatchSize = calculateBatchSize(totalChunks);
            logger.debug("Embedding {} chunks with batch size {}", totalChunks, effectiveBatchSize);

            // Process in batches
            List<RetrievedDoc> chunks = input.chunks();
            int batchCount = 0;

            for (int i = 0; i < totalChunks; i += effectiveBatchSize) {
                if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Embedding cancelled during batch processing");
                }

                int batchEnd = Math.min(i + effectiveBatchSize, totalChunks);
                List<RetrievedDoc> batch = chunks.subList(i, batchEnd);

                // Collect texts for batch embedding
                List<String> texts = new ArrayList<>(batch.size());
                for (RetrievedDoc chunk : batch) {
                    String text = chunk.getText();
                    texts.add(text != null ? text : "");
                    totalBytes += (text != null ? text.length() : 0) * 2;
                }

                // Generate embeddings for batch
                long batchStartNanos = System.nanoTime();
                INDArray batchEmbeddings = embeddingModel.embed(texts);
                long batchTimeMs = (System.nanoTime() - batchStartNanos) / 1_000_000;

                // Associate embeddings with chunks
                // MEMORY LEAK FIX: getRow() returns a VIEW that shares the same buffer as batchEmbeddings.
                // We must dup() each row to create an independent copy, then close the batch immediately.
                // Without this fix, batchEmbeddings is never closed and accumulates in native memory.
                try {
                    for (int j = 0; j < batch.size(); j++) {
                        INDArray embedding = null;
                        if (batchEmbeddings != null && batchEmbeddings.rank() >= 2) {
                            // dup() creates an independent copy with its own native memory buffer
                            embedding = batchEmbeddings.getRow(j).dup();
                        }
                        embeddedChunks.add(new EmbeddedChunk(batch.get(j), embedding));
                    }
                } finally {
                    // CRITICAL: Close the batch embeddings to release native memory
                    if (batchEmbeddings != null) {
                        try {
                            batchEmbeddings.close();
                        } catch (Exception e) {
                            logger.trace("Error closing batch embeddings: {}", e.getMessage());
                        }
                    }
                }

                batchCount++;
                logger.debug("Batch {}: embedded {}-{}/{} chunks in {}ms",
                        batchCount, i, batchEnd, totalChunks, batchTimeMs);

                // Adaptive batch sizing: check memory and adjust
                if (adaptiveBatching) {
                    effectiveBatchSize = adaptBatchSize(effectiveBatchSize, batchTimeMs);
                }
            }

            long elapsedNanos = System.nanoTime() - startNanos;
            metrics.recordSuccess(elapsedNanos, totalBytes, totalChunks);

            double throughput = (elapsedNanos / 1_000_000) > 0 ?
                    (totalChunks * 1000.0) / (elapsedNanos / 1_000_000) : 0;
            logger.debug("Embedded {} chunks in {} batches, {}ms ({} chunks/sec)",
                    totalChunks, batchCount, elapsedNanos / 1_000_000, throughput);

            return createOutput(embeddedChunks, input, elapsedNanos);

        } catch (Exception e) {
            metrics.recordFailure();
            throw e;
        }
    }

    private EmbeddingOutput createOutput(List<EmbeddedChunk> embeddedChunks,
                                          ChunkingStage.ChunkingOutput input,
                                          long elapsedNanos) {
        return new EmbeddingOutput(
                embeddedChunks,
                elapsedNanos / 1_000_000,
                embeddingModel != null ? embeddingModel.getModelName() : "none",
                input.chunkerUsed(),
                input.loaderUsed(),
                input.taskId(),
                input.metadata()
        );
    }

    private int calculateBatchSize(int totalChunks) {
        if (!adaptiveBatching) {
            return batchSize;
        }

        // Start with configured batch size
        int size = batchSize;

        // For small chunk counts, use smaller batches for better progress granularity
        if (totalChunks < 50) {
            size = Math.min(size, Math.max(MIN_BATCH_SIZE, totalChunks / 4));
        }

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
        // If batch takes too long (>5 seconds), reduce size
        if (batchTimeMs > 5000) {
            int newSize = Math.max(MIN_BATCH_SIZE, currentSize / 2);
            if (newSize < currentSize) {
                logger.debug("Batch took {}ms, reducing size {} -> {}", batchTimeMs, currentSize, newSize);
                return newSize;
            }
        }

        // If batch is very fast and we have memory headroom, increase slightly
        if (batchTimeMs < 500 && currentSize < MAX_BATCH_SIZE) {
            Runtime runtime = Runtime.getRuntime();
            long maxMemory = runtime.maxMemory();
            long usedMemory = runtime.totalMemory() - runtime.freeMemory();
            double memoryUsage = (usedMemory * 100.0) / maxMemory;

            if (memoryUsage < 60) {
                int newSize = Math.min(MAX_BATCH_SIZE, (int) (currentSize * 1.25));
                if (newSize > currentSize) {
                    logger.debug("Fast batch {}ms, increasing size {} -> {}", batchTimeMs, currentSize, newSize);
                    return newSize;
                }
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
        if (options.containsKey("embeddingBatchSize")) {
            this.batchSize = ((Number) options.get("embeddingBatchSize")).intValue();
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
     * A chunk with its embedding vector.
     */
    public record EmbeddedChunk(
            RetrievedDoc chunk,
            INDArray embedding
    ) {
        public String getId() {
            return chunk.getId();
        }

        public String getText() {
            return chunk.getText();
        }

        public Map<String, Object> getMetadata() {
            return chunk.getMetadata();
        }

        public boolean hasEmbedding() {
            return embedding != null;
        }

        public int getEmbeddingDimensions() {
            return embedding != null ? (int) embedding.length() : 0;
        }
    }

    /**
     * Output from the embedding stage.
     */
    public record EmbeddingOutput(
            List<EmbeddedChunk> embeddedChunks,
            long embeddingTimeMs,
            String embeddingModelUsed,
            String chunkerUsed,
            String loaderUsed,
            String taskId,
            Map<String, Object> metadata
    ) {
        public int chunkCount() {
            return embeddedChunks != null ? embeddedChunks.size() : 0;
        }

        public int chunksWithEmbeddings() {
            if (embeddedChunks == null) return 0;
            return (int) embeddedChunks.stream().filter(EmbeddedChunk::hasEmbedding).count();
        }

        public double embeddingsPerSecond() {
            return embeddingTimeMs > 0 ? (chunkCount() * 1000.0) / embeddingTimeMs : 0;
        }
    }
}
