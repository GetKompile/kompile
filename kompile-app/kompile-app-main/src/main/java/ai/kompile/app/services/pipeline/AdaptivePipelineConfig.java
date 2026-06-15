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

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adaptive configuration for parallel ingest pipeline.
 * Calculates optimal parallelism and batch sizes based on available system resources.
 */
@Getter
public class AdaptivePipelineConfig {

    private static final Logger logger = LoggerFactory.getLogger(AdaptivePipelineConfig.class);

    // Estimated memory per chunk (bytes) - conservative estimate including embeddings
    private static final long ESTIMATED_BYTES_PER_CHUNK = 50_000; // ~50KB per chunk with embeddings

    // Minimum and maximum parallelism bounds
    private static final int MIN_PARALLELISM = 2;
    private static final int MAX_PARALLELISM = 32;

    // Batch size bounds
    private static final int MIN_BATCH_SIZE = 10;
    private static final int MAX_BATCH_SIZE = 500;

    // Target memory usage percentage (leave headroom for GC)
    private static final double TARGET_MEMORY_USAGE = 0.70;

    // Minimum free memory to maintain (MB)
    private static final long MIN_FREE_MEMORY_MB = 512;

    private final int availableCores;
    private final long maxHeapBytes;
    private final long currentUsedBytes;
    private final long availableBytes;

    // Calculated values
    private final int optimalParallelism;
    private final int optimalBatchSize;
    private final int maxConcurrentBatches;
    private final long estimatedChunksInMemory;

    public AdaptivePipelineConfig() {
        Runtime runtime = Runtime.getRuntime();
        this.availableCores = runtime.availableProcessors();
        this.maxHeapBytes = runtime.maxMemory();
        this.currentUsedBytes = runtime.totalMemory() - runtime.freeMemory();
        this.availableBytes = (long) (maxHeapBytes * TARGET_MEMORY_USAGE) - currentUsedBytes;

        // Calculate optimal settings
        this.estimatedChunksInMemory = Math.max(100, availableBytes / ESTIMATED_BYTES_PER_CHUNK);
        this.optimalParallelism = calculateOptimalParallelism();
        this.optimalBatchSize = calculateOptimalBatchSize();
        this.maxConcurrentBatches = calculateMaxConcurrentBatches();

        logger.info("AdaptivePipelineConfig initialized: cores={}, maxHeap={}MB, available={}MB, " +
                        "parallelism={}, batchSize={}, maxConcurrentBatches={}, estimatedChunksInMemory={}",
                availableCores, maxHeapBytes / (1024 * 1024), availableBytes / (1024 * 1024),
                optimalParallelism, optimalBatchSize, maxConcurrentBatches, estimatedChunksInMemory);
    }

    /**
     * Creates a config for a specific workload size.
     */
    public static AdaptivePipelineConfig forWorkload(int totalDocuments, int estimatedChunksPerDoc) {
        return new AdaptivePipelineConfig();
    }

    private int calculateOptimalParallelism() {
        // Base parallelism on CPU cores, but cap based on available memory
        int cpuBasedParallelism = Math.max(MIN_PARALLELISM, availableCores - 1);

        // Memory-based cap: each parallel worker needs memory for its batch
        long memoryPerWorker = ESTIMATED_BYTES_PER_CHUNK * MIN_BATCH_SIZE;
        int memoryBasedParallelism = (int) Math.max(MIN_PARALLELISM, availableBytes / memoryPerWorker / 2);

        int optimal = Math.min(cpuBasedParallelism, memoryBasedParallelism);
        return Math.max(MIN_PARALLELISM, Math.min(optimal, MAX_PARALLELISM));
    }

    private int calculateOptimalBatchSize() {
        // Calculate batch size based on available memory and parallelism
        long memoryPerWorker = availableBytes / optimalParallelism;
        int memoryBasedBatchSize = (int) (memoryPerWorker / ESTIMATED_BYTES_PER_CHUNK);

        // Scale batch size with available cores for better throughput
        int cpuBasedBatchSize = 50 + (availableCores * 10);

        int optimal = Math.min(memoryBasedBatchSize, cpuBasedBatchSize);
        return Math.max(MIN_BATCH_SIZE, Math.min(optimal, MAX_BATCH_SIZE));
    }

    private int calculateMaxConcurrentBatches() {
        // How many batches can we have in flight at once
        long batchMemory = (long) optimalBatchSize * ESTIMATED_BYTES_PER_CHUNK;
        int memoryBasedBatches = (int) (availableBytes / batchMemory);
        return Math.max(2, Math.min(memoryBasedBatches, optimalParallelism * 2));
    }

    /**
     * Recalculates configuration based on current memory state.
     * Call this periodically during long-running operations.
     */
    public AdaptivePipelineConfig refresh() {
        return new AdaptivePipelineConfig();
    }

    /**
     * Checks if we should reduce parallelism due to memory pressure.
     */
    public boolean shouldReduceParallelism() {
        Runtime runtime = Runtime.getRuntime();
        long currentFree = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
        return currentFree < MIN_FREE_MEMORY_MB * 1024 * 1024;
    }

    /**
     * Gets the recommended parallelism for the current memory state.
     */
    public int getRecommendedParallelism() {
        if (shouldReduceParallelism()) {
            return Math.max(MIN_PARALLELISM, optimalParallelism / 2);
        }
        return optimalParallelism;
    }

    /**
     * Gets the recommended batch size for the current memory state.
     */
    public int getRecommendedBatchSize() {
        if (shouldReduceParallelism()) {
            return Math.max(MIN_BATCH_SIZE, optimalBatchSize / 2);
        }
        return optimalBatchSize;
    }

    /**
     * Calculates how many documents can be processed in parallel.
     */
    public int getParallelDocumentLimit() {
        // Limit based on memory - each document in flight consumes memory
        return Math.max(1, (int) (estimatedChunksInMemory / 100)); // Assume ~100 chunks per doc
    }

    public double getMemoryUsagePercent() {
        return (currentUsedBytes * 100.0) / maxHeapBytes;
    }

    public long getAvailableMB() {
        return availableBytes / (1024 * 1024);
    }

    @Override
    public String toString() {
        return String.format("AdaptivePipelineConfig{cores=%d, heap=%dMB, available=%dMB, " +
                        "parallelism=%d, batchSize=%d, maxBatches=%d}",
                availableCores, maxHeapBytes / (1024 * 1024), availableBytes / (1024 * 1024),
                optimalParallelism, optimalBatchSize, maxConcurrentBatches);
    }
}
