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
 * Configuration settings for the staged ingest pipeline.
 *
 * <p>All settings can be auto-detected based on system resources using {@link #adaptive()},
 * or customized for specific use cases.</p>
 *
 * @param preferredLoader Preferred loader name (null for auto-detect)
 * @param autoDetectLoader Whether to auto-detect loader based on file type
 * @param tokenizerModel Tokenizer model name (e.g., "bert-base-uncased")
 * @param maxTokenLength Maximum tokens per document
 * @param enablePreTokenization Whether to pre-tokenize for token-aware chunking
 * @param chunkerType Chunker type (e.g., "recursive-character", "sentence")
 * @param chunkSize Target chunk size in characters
 * @param chunkOverlap Overlap between chunks in characters
 * @param preserveParagraphs Whether to preserve paragraph boundaries
 * @param embeddingBatchSize Batch size for embedding generation
 * @param indexBatchSize Batch size for Lucene indexing
 * @param extractionThreads Thread count for extraction stage
 * @param tokenizationThreads Thread count for tokenization stage
 * @param chunkingThreads Thread count for chunking stage
 * @param embeddingThreads Thread count for embedding stage
 * @param queueCapacity Capacity of inter-stage queues
 * @param enableBackpressure Whether to enable backpressure (bounded queues)
 */
public record PipelineSettings(
        // Extraction settings
        String preferredLoader,
        boolean autoDetectLoader,

        // Tokenization settings
        String tokenizerModel,
        int maxTokenLength,
        boolean enablePreTokenization,

        // Chunking settings
        String chunkerType,
        int chunkSize,
        int chunkOverlap,
        boolean preserveParagraphs,

        // Embedding settings
        int embeddingBatchSize,

        // Indexing settings
        int indexBatchSize,

        // Parallelism settings
        int extractionThreads,
        int tokenizationThreads,
        int chunkingThreads,
        int embeddingThreads,

        // Queue settings
        int queueCapacity,
        boolean enableBackpressure
) {
    // Default values
    private static final int DEFAULT_CHUNK_SIZE = 1000;
    private static final int DEFAULT_CHUNK_OVERLAP = 200;
    private static final int DEFAULT_MAX_TOKEN_LENGTH = 512;
    private static final int DEFAULT_EMBEDDING_BATCH_SIZE = 32;
    private static final int DEFAULT_INDEX_BATCH_SIZE = 100;
    private static final int DEFAULT_QUEUE_CAPACITY = 100;

    /**
     * Creates settings with values adapted to the current system resources.
     * This is the recommended way to create settings for most use cases.
     */
    public static PipelineSettings adaptive() {
        int cores = Runtime.getRuntime().availableProcessors();
        long memoryMB = Runtime.getRuntime().maxMemory() / (1024 * 1024);

        // Calculate thread counts based on cores and memory
        int extractionThreads = Math.max(1, Math.min(2, cores / 8));
        int tokenizationThreads = Math.max(2, Math.min(4, cores / 4));
        int chunkingThreads = Math.max(4, Math.min(cores - 2, 16));
        int embeddingThreads = Math.max(1, Math.min(4, cores / 4));

        // Reduce thread counts if memory-constrained (<8GB)
        if (memoryMB < 8192) {
            tokenizationThreads = Math.max(1, tokenizationThreads / 2);
            chunkingThreads = Math.max(2, chunkingThreads / 2);
            embeddingThreads = Math.max(1, embeddingThreads / 2);
        }

        // Calculate batch sizes based on memory
        int embeddingBatchSize = DEFAULT_EMBEDDING_BATCH_SIZE;
        int indexBatchSize = DEFAULT_INDEX_BATCH_SIZE;
        if (memoryMB < 4096) {
            embeddingBatchSize = 16;
            indexBatchSize = 50;
        } else if (memoryMB >= 16384) {
            embeddingBatchSize = 64;
            indexBatchSize = 200;
        }

        // Queue capacity based on thread counts
        int queueCapacity = Math.max(DEFAULT_QUEUE_CAPACITY, chunkingThreads * 10);

        return new PipelineSettings(
                null,                        // preferredLoader - auto-detect
                true,                        // autoDetectLoader
                "default",                   // tokenizerModel
                DEFAULT_MAX_TOKEN_LENGTH,    // maxTokenLength
                true,                        // enablePreTokenization
                null,                        // chunkerType - auto-select
                DEFAULT_CHUNK_SIZE,          // chunkSize
                DEFAULT_CHUNK_OVERLAP,       // chunkOverlap
                true,                        // preserveParagraphs
                embeddingBatchSize,          // embeddingBatchSize
                indexBatchSize,              // indexBatchSize
                extractionThreads,           // extractionThreads
                tokenizationThreads,         // tokenizationThreads
                chunkingThreads,             // chunkingThreads
                embeddingThreads,            // embeddingThreads
                queueCapacity,               // queueCapacity
                true                         // enableBackpressure
        );
    }

    /**
     * Creates settings optimized for minimal memory usage.
     */
    public static PipelineSettings memoryOptimized() {
        return new PipelineSettings(
                null, true,                  // loader
                "default", 256, false,       // tokenization - disabled
                null, 500, 100, true,        // chunking - smaller chunks
                16, 25,                      // smaller batches
                1, 1, 2, 1,                  // minimal threads
                50, true                     // smaller queue
        );
    }

    /**
     * Creates settings optimized for maximum throughput.
     */
    public static PipelineSettings highThroughput() {
        int cores = Runtime.getRuntime().availableProcessors();
        return new PipelineSettings(
                null, true,                  // loader
                "default", 512, true,        // tokenization
                null, 1500, 300, false,      // larger chunks
                64, 200,                     // larger batches
                2, 4, Math.min(cores, 16), 4, // more threads
                200, true                    // larger queue
        );
    }

    /**
     * Creates settings with all defaults.
     */
    public static PipelineSettings defaults() {
        return new PipelineSettings(
                null, true,                               // loader
                "default", DEFAULT_MAX_TOKEN_LENGTH, true, // tokenization
                null, DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP, true, // chunking
                DEFAULT_EMBEDDING_BATCH_SIZE, DEFAULT_INDEX_BATCH_SIZE, // batching
                1, 2, 4, 2,                               // threads
                DEFAULT_QUEUE_CAPACITY, true              // queue
        );
    }

    /**
     * Creates a builder for custom settings.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for creating custom PipelineSettings.
     */
    public static class Builder {
        private String preferredLoader = null;
        private boolean autoDetectLoader = true;
        private String tokenizerModel = "default";
        private int maxTokenLength = DEFAULT_MAX_TOKEN_LENGTH;
        private boolean enablePreTokenization = true;
        private String chunkerType = null;
        private int chunkSize = DEFAULT_CHUNK_SIZE;
        private int chunkOverlap = DEFAULT_CHUNK_OVERLAP;
        private boolean preserveParagraphs = true;
        private int embeddingBatchSize = DEFAULT_EMBEDDING_BATCH_SIZE;
        private int indexBatchSize = DEFAULT_INDEX_BATCH_SIZE;
        private int extractionThreads = 1;
        private int tokenizationThreads = 2;
        private int chunkingThreads = 4;
        private int embeddingThreads = 2;
        private int queueCapacity = DEFAULT_QUEUE_CAPACITY;
        private boolean enableBackpressure = true;

        public Builder preferredLoader(String loader) {
            this.preferredLoader = loader;
            return this;
        }

        public Builder autoDetectLoader(boolean autoDetect) {
            this.autoDetectLoader = autoDetect;
            return this;
        }

        public Builder tokenizerModel(String model) {
            this.tokenizerModel = model;
            return this;
        }

        public Builder maxTokenLength(int maxLength) {
            this.maxTokenLength = maxLength;
            return this;
        }

        public Builder enablePreTokenization(boolean enable) {
            this.enablePreTokenization = enable;
            return this;
        }

        public Builder chunkerType(String type) {
            this.chunkerType = type;
            return this;
        }

        public Builder chunkSize(int size) {
            this.chunkSize = size;
            return this;
        }

        public Builder chunkOverlap(int overlap) {
            this.chunkOverlap = overlap;
            return this;
        }

        public Builder preserveParagraphs(boolean preserve) {
            this.preserveParagraphs = preserve;
            return this;
        }

        public Builder embeddingBatchSize(int size) {
            this.embeddingBatchSize = size;
            return this;
        }

        public Builder indexBatchSize(int size) {
            this.indexBatchSize = size;
            return this;
        }

        public Builder extractionThreads(int threads) {
            this.extractionThreads = Math.max(1, threads);
            return this;
        }

        public Builder tokenizationThreads(int threads) {
            this.tokenizationThreads = Math.max(1, threads);
            return this;
        }

        public Builder chunkingThreads(int threads) {
            this.chunkingThreads = Math.max(1, threads);
            return this;
        }

        public Builder embeddingThreads(int threads) {
            this.embeddingThreads = Math.max(1, threads);
            return this;
        }

        public Builder queueCapacity(int capacity) {
            this.queueCapacity = Math.max(10, capacity);
            return this;
        }

        public Builder enableBackpressure(boolean enable) {
            this.enableBackpressure = enable;
            return this;
        }

        public PipelineSettings build() {
            return new PipelineSettings(
                    preferredLoader, autoDetectLoader,
                    tokenizerModel, maxTokenLength, enablePreTokenization,
                    chunkerType, chunkSize, chunkOverlap, preserveParagraphs,
                    embeddingBatchSize, indexBatchSize,
                    extractionThreads, tokenizationThreads, chunkingThreads, embeddingThreads,
                    queueCapacity, enableBackpressure
            );
        }
    }

    @Override
    public String toString() {
        return String.format(
                "PipelineSettings{loader=%s, tokenize=%s, chunk=%dx%d, batch=%d/%d, threads=%d/%d/%d/%d, queue=%d}",
                preferredLoader != null ? preferredLoader : "auto",
                enablePreTokenization ? "on" : "off",
                chunkSize, chunkOverlap,
                embeddingBatchSize, indexBatchSize,
                extractionThreads, tokenizationThreads, chunkingThreads, embeddingThreads,
                queueCapacity
        );
    }
}
