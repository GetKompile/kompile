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

/**
 * Configuration record for pipeline settings.
 * All values are configurable - use this to tune performance for your hardware.
 *
 * <p>Worker types:</p>
 * <ul>
 *   <li><b>indexingThreads</b> - Write chunks to Lucene (keyword search)</li>
 *   <li><b>embeddingThreads</b> - Compute embeddings AND write to vector store (semantic search)</li>
 * </ul>
 */
public record IngestPipelineConfig(
        int minBatchSize,
        int defaultBatchSize,
        int maxBatchSize,
        int queueCapacity,
        long queuePollTimeoutMs,
        long maxBatchWaitMs,
        long minBatchWaitMs,
        int chunkingThreads,
        int embeddingThreads,            // Embedding workers: compute embeddings + write to vector store
        int indexingThreads,             // Indexing workers: write to Lucene (keyword index)
        int indexingBatchAccumulationSize,
        boolean skipEmbedding,           // Skip embedding computation entirely (keyword-only mode)
        int embeddingTimeoutSeconds      // Timeout for each embedding batch (0 = no timeout)
) {

    // ========== DEFAULT VALUES ==========
    private static final int DEFAULT_MIN_BATCH_SIZE = 1;
    private static final int DEFAULT_BATCH_SIZE = 1;
    private static final int DEFAULT_MAX_BATCH_SIZE = 128;
    private static final int DEFAULT_QUEUE_CAPACITY = 1000;
    private static final long DEFAULT_QUEUE_POLL_TIMEOUT_MS = 50;
    private static final long DEFAULT_MAX_BATCH_WAIT_MS = 500;
    private static final long DEFAULT_MIN_BATCH_WAIT_MS = 25;
    private static final int DEFAULT_CHUNKING_THREADS = Math.min(Runtime.getRuntime().availableProcessors() / 2, 16);
    private static final int DEFAULT_EMBEDDING_THREADS = 1;
    private static final int DEFAULT_INDEXING_THREADS = 4;
    private static final int DEFAULT_INDEXING_BATCH_ACCUMULATION = 8;
    private static final int DEFAULT_EMBEDDING_TIMEOUT_SECONDS = 300;

    /**
     * Creates a default configuration.
     * Both indexing (Lucene) and embedding (vector store) run in parallel.
     */
    public static IngestPipelineConfig defaults() {
        return new IngestPipelineConfig(
                DEFAULT_MIN_BATCH_SIZE,
                DEFAULT_BATCH_SIZE,
                DEFAULT_MAX_BATCH_SIZE,
                DEFAULT_QUEUE_CAPACITY,
                DEFAULT_QUEUE_POLL_TIMEOUT_MS,
                DEFAULT_MAX_BATCH_WAIT_MS,
                DEFAULT_MIN_BATCH_WAIT_MS,
                DEFAULT_CHUNKING_THREADS,
                DEFAULT_EMBEDDING_THREADS,
                DEFAULT_INDEXING_THREADS,
                DEFAULT_INDEXING_BATCH_ACCUMULATION,
                false,  // skipEmbedding
                DEFAULT_EMBEDDING_TIMEOUT_SECONDS
        );
    }

    /**
     * Creates a high-throughput configuration for systems with more memory.
     * Chunks are processed individually; NDArray batching is internal to the model.
     */
    public static IngestPipelineConfig highThroughput() {
        int cores = Runtime.getRuntime().availableProcessors();
        return new IngestPipelineConfig(
                1,      // Single chunk processing
                1,      // Single chunk processing
                256,    // For indexing batches only
                2000,   // Larger queue
                50,
                500,
                25,
                Math.min(cores, 16),
                1,      // 1 embedding thread - OpenMP handles internal parallelism
                Math.min(cores / 2, 8),  // Indexing threads for Lucene
                16,     // Indexing batch accumulation
                false,  // skipEmbedding
                DEFAULT_EMBEDDING_TIMEOUT_SECONDS
        );
    }

    /**
     * Creates a low-memory configuration for constrained systems.
     * Chunks are processed individually to minimize memory usage.
     */
    public static IngestPipelineConfig lowMemory() {
        return new IngestPipelineConfig(
                1,      // Single chunk processing
                1,      // Single chunk processing
                32,     // Lower max for indexing
                250,    // Smaller queue
                50,
                500,
                25,
                2,      // Fewer threads
                1,      // Single embedding thread - OpenMP handles parallelism
                2,      // 2 indexing threads
                4,      // Indexing batch accumulation
                false,  // skipEmbedding
                DEFAULT_EMBEDDING_TIMEOUT_SECONDS
        );
    }

    /**
     * Creates a keyword-only configuration for maximum ingestion speed.
     * Embedding and vector store population is skipped entirely.
     * Call indexFromLucene() later to populate the vector store.
     */
    public static IngestPipelineConfig keywordOnly() {
        int cores = Runtime.getRuntime().availableProcessors();
        return new IngestPipelineConfig(
                1,      // Single chunk processing
                1,      // Single chunk processing
                256,    // For indexing batches only
                5000,   // Large queue for buffering
                50,
                500,
                25,
                Math.min(cores, 16),  // Max chunking parallelism
                0,      // No embedding threads - keyword only
                Math.min(cores / 2, 8),  // Indexing threads for Lucene
                32,     // Large indexing batch accumulation
                true,   // skipEmbedding
                0       // No timeout needed for keyword-only mode
        );
    }

    /**
     * Builder for custom configuration.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int minBatchSize = DEFAULT_MIN_BATCH_SIZE;
        private int defaultBatchSize = DEFAULT_BATCH_SIZE;
        private int maxBatchSize = DEFAULT_MAX_BATCH_SIZE;
        private int queueCapacity = DEFAULT_QUEUE_CAPACITY;
        private long queuePollTimeoutMs = DEFAULT_QUEUE_POLL_TIMEOUT_MS;
        private long maxBatchWaitMs = DEFAULT_MAX_BATCH_WAIT_MS;
        private long minBatchWaitMs = DEFAULT_MIN_BATCH_WAIT_MS;
        private int chunkingThreads = DEFAULT_CHUNKING_THREADS;
        private int embeddingThreads = DEFAULT_EMBEDDING_THREADS;
        private int indexingThreads = DEFAULT_INDEXING_THREADS;
        private int indexingBatchAccumulationSize = DEFAULT_INDEXING_BATCH_ACCUMULATION;
        private boolean skipEmbedding = false;
        private int embeddingTimeoutSeconds = DEFAULT_EMBEDDING_TIMEOUT_SECONDS;

        public Builder minBatchSize(int v) { this.minBatchSize = v; return this; }
        public Builder defaultBatchSize(int v) { this.defaultBatchSize = v; return this; }
        public Builder maxBatchSize(int v) { this.maxBatchSize = v; return this; }
        public Builder queueCapacity(int v) { this.queueCapacity = v; return this; }
        public Builder queuePollTimeoutMs(long v) { this.queuePollTimeoutMs = v; return this; }
        public Builder maxBatchWaitMs(long v) { this.maxBatchWaitMs = v; return this; }
        public Builder minBatchWaitMs(long v) { this.minBatchWaitMs = v; return this; }
        public Builder chunkingThreads(int v) { this.chunkingThreads = v; return this; }
        public Builder embeddingThreads(int v) { this.embeddingThreads = v; return this; }
        public Builder indexingThreads(int v) { this.indexingThreads = v; return this; }
        public Builder indexingBatchAccumulationSize(int v) { this.indexingBatchAccumulationSize = v; return this; }
        public Builder skipEmbedding(boolean v) { this.skipEmbedding = v; return this; }
        public Builder embeddingTimeoutSeconds(int v) { this.embeddingTimeoutSeconds = v; return this; }

        public IngestPipelineConfig build() {
            return new IngestPipelineConfig(
                    minBatchSize, defaultBatchSize, maxBatchSize, queueCapacity,
                    queuePollTimeoutMs, maxBatchWaitMs, minBatchWaitMs,
                    chunkingThreads, embeddingThreads, indexingThreads,
                    indexingBatchAccumulationSize, skipEmbedding, embeddingTimeoutSeconds
            );
        }
    }
}
