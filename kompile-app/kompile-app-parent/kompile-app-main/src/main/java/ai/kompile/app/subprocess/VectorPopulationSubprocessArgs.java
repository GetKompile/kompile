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

package ai.kompile.app.subprocess;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Arguments passed to the vector population subprocess via JSON file.
 * Contains all configuration needed to populate vector store from Lucene keyword index.
 *
 * <h3>Pipeline Configuration</h3>
 * <ul>
 *   <li>embeddingBatchSize: Batch size for embedding operations (default 32)</li>
 *   <li>maxBatchSize: Maximum batch size limit (default 128)</li>
 *   <li>queueCapacity: Queue capacity for pipeline stages (default 1000)</li>
 *   <li>indexingWorkers: Number of parallel indexing workers (default 4)</li>
 *   <li>indexingBatchAccumulationSize: Batches to accumulate before bulk index (default 8)</li>
 *   <li>embeddingThreads: Number of embedding threads (default 1 for OpenMP)</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VectorPopulationSubprocessArgs(
        String taskId,
        String keywordIndexPath,              // Source: Lucene keyword index
        String vectorIndexPath,               // Destination: Vector store index
        String checkpointBasePath,            // Base path for checkpoint files (enables resume on failure)
        int embeddingBatchSize,               // Batch size for embedding (default 32)
        int maxBatchSize,                     // Maximum batch size (default 128)
        int queueCapacity,                    // Pipeline queue capacity (default 1000)
        boolean parallelIndexing,             // Enable parallel indexing workers
        int indexingWorkers,                  // Number of indexing workers (default 4)
        int indexingBatchAccumulationSize,    // Batches to accumulate before bulk index (default 8)
        int embeddingThreads,                 // Number of embedding threads (default 1)
        String callbackBaseUrl,               // URL for HTTP callbacks to main app
        String nd4jConfigJson,                // ND4J environment config as JSON
        // Model source configuration (inherited from parent)
        String modelSourceType,               // "staging" or "archive"
        String modelIdentifier,               // Model ID to use
        String stagingUrl,                    // Staging service URL
        String stagingApiKey,                 // Staging service API key
        String archivePath,                   // Path to loaded archive
        // Memory watchdog thresholds
        int memoryThresholdPercent,           // Graceful stop threshold (default 80%)
        int memoryCriticalPercent,            // Critical warning/GC threshold (default 90%)
        int memoryKillThresholdPercent,       // Hard kill threshold (default 95%, 0 to disable)
        long memoryCheckIntervalMs,           // How often to check memory (default 2000ms)
        // GPU memory thresholds
        int gpuMemoryThresholdPercent,        // GPU graceful stop (default 75%)
        int gpuMemoryCriticalPercent,         // GPU critical warning (default 85%)
        int gpuMemoryKillThresholdPercent,    // GPU hard kill (default 92%)
        // Off-heap (JavaCPP native) memory thresholds
        int offHeapThresholdPercent,          // Off-heap graceful stop (default 80%)
        int offHeapCriticalPercent,           // Off-heap critical warning (default 90%)
        int offHeapKillThresholdPercent,      // Off-heap hard kill (default 95%)
        Map<String, String> options           // Additional options
) {
    // Default constants for memory thresholds
    public static final int DEFAULT_MEMORY_THRESHOLD_PERCENT = 80;
    public static final int DEFAULT_MEMORY_CRITICAL_PERCENT = 90;
    public static final int DEFAULT_MEMORY_KILL_THRESHOLD_PERCENT = 95;
    public static final long DEFAULT_MEMORY_CHECK_INTERVAL_MS = 2000;
    // GPU threshold defaults
    public static final int DEFAULT_GPU_MEMORY_THRESHOLD_PERCENT = 75;
    public static final int DEFAULT_GPU_MEMORY_CRITICAL_PERCENT = 85;
    public static final int DEFAULT_GPU_MEMORY_KILL_THRESHOLD_PERCENT = 92;
    // Off-heap threshold defaults
    public static final int DEFAULT_OFF_HEAP_THRESHOLD_PERCENT = 80;
    public static final int DEFAULT_OFF_HEAP_CRITICAL_PERCENT = 90;
    public static final int DEFAULT_OFF_HEAP_KILL_THRESHOLD_PERCENT = 95;
    // Uses SubprocessArgsIo shared mapper

    public VectorPopulationSubprocessArgs {
        // Apply defaults
        if (embeddingBatchSize <= 0) embeddingBatchSize = 32;
        if (maxBatchSize <= 0) maxBatchSize = 128;
        if (queueCapacity <= 0) queueCapacity = 1000;
        if (indexingWorkers <= 0) indexingWorkers = 4;
        if (indexingBatchAccumulationSize <= 0) indexingBatchAccumulationSize = 8;
        // Use 1 embedding worker - parallelism comes from OpenMP/BLAS internally
        if (embeddingThreads <= 0) embeddingThreads = 1;
        // Memory thresholds defaults
        if (memoryThresholdPercent <= 0) memoryThresholdPercent = DEFAULT_MEMORY_THRESHOLD_PERCENT;
        if (memoryCriticalPercent <= 0) memoryCriticalPercent = DEFAULT_MEMORY_CRITICAL_PERCENT;
        if (memoryKillThresholdPercent < 0) memoryKillThresholdPercent = DEFAULT_MEMORY_KILL_THRESHOLD_PERCENT;
        if (memoryCheckIntervalMs <= 0) memoryCheckIntervalMs = DEFAULT_MEMORY_CHECK_INTERVAL_MS;
        // GPU memory thresholds defaults
        if (gpuMemoryThresholdPercent <= 0) gpuMemoryThresholdPercent = DEFAULT_GPU_MEMORY_THRESHOLD_PERCENT;
        if (gpuMemoryCriticalPercent <= 0) gpuMemoryCriticalPercent = DEFAULT_GPU_MEMORY_CRITICAL_PERCENT;
        if (gpuMemoryKillThresholdPercent < 0) gpuMemoryKillThresholdPercent = DEFAULT_GPU_MEMORY_KILL_THRESHOLD_PERCENT;
        // Off-heap memory thresholds defaults
        if (offHeapThresholdPercent <= 0) offHeapThresholdPercent = DEFAULT_OFF_HEAP_THRESHOLD_PERCENT;
        if (offHeapCriticalPercent <= 0) offHeapCriticalPercent = DEFAULT_OFF_HEAP_CRITICAL_PERCENT;
        if (offHeapKillThresholdPercent < 0) offHeapKillThresholdPercent = DEFAULT_OFF_HEAP_KILL_THRESHOLD_PERCENT;
    }

    /**
     * Read args from JSON file.
     */
    public static VectorPopulationSubprocessArgs fromFile(Path path) throws IOException {
        return SubprocessArgsIo.fromFile(path, VectorPopulationSubprocessArgs.class);
    }

    /**
     * Write args to JSON file.
     */
    public void toFile(Path path) throws IOException {
        SubprocessArgsIo.toFile(path, this);
    }

    /**
     * Write args to a temp file and return the path.
     */
    public Path writeToTempFile() throws IOException {
        return SubprocessArgsIo.writeToTempFile(this, "vector-pop-args-");
    }

    /**
     * Builder for creating VectorPopulationSubprocessArgs.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String taskId;
        private String keywordIndexPath;
        private String vectorIndexPath;
        private String checkpointBasePath;  // Base path for checkpoint files (enables resume on failure)
        private int embeddingBatchSize = 32;
        private int maxBatchSize = 128;
        private int queueCapacity = 1000;
        private boolean parallelIndexing = true;
        private int indexingWorkers = 4;
        private int indexingBatchAccumulationSize = 8;
        private int embeddingThreads = 1;  // 1 worker - OpenMP handles internal parallelism
        private String callbackBaseUrl;
        private String nd4jConfigJson;
        // Model source config
        private String modelSourceType;
        private String modelIdentifier;
        private String stagingUrl;
        private String stagingApiKey;
        private String archivePath;
        // Memory watchdog thresholds
        private int memoryThresholdPercent = DEFAULT_MEMORY_THRESHOLD_PERCENT;
        private int memoryCriticalPercent = DEFAULT_MEMORY_CRITICAL_PERCENT;
        private int memoryKillThresholdPercent = DEFAULT_MEMORY_KILL_THRESHOLD_PERCENT;
        private long memoryCheckIntervalMs = DEFAULT_MEMORY_CHECK_INTERVAL_MS;
        // GPU memory thresholds
        private int gpuMemoryThresholdPercent = DEFAULT_GPU_MEMORY_THRESHOLD_PERCENT;
        private int gpuMemoryCriticalPercent = DEFAULT_GPU_MEMORY_CRITICAL_PERCENT;
        private int gpuMemoryKillThresholdPercent = DEFAULT_GPU_MEMORY_KILL_THRESHOLD_PERCENT;
        // Off-heap memory thresholds
        private int offHeapThresholdPercent = DEFAULT_OFF_HEAP_THRESHOLD_PERCENT;
        private int offHeapCriticalPercent = DEFAULT_OFF_HEAP_CRITICAL_PERCENT;
        private int offHeapKillThresholdPercent = DEFAULT_OFF_HEAP_KILL_THRESHOLD_PERCENT;
        private Map<String, String> options = Map.of();

        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder keywordIndexPath(String keywordIndexPath) {
            this.keywordIndexPath = keywordIndexPath;
            return this;
        }

        public Builder vectorIndexPath(String vectorIndexPath) {
            this.vectorIndexPath = vectorIndexPath;
            return this;
        }

        public Builder checkpointBasePath(String checkpointBasePath) {
            this.checkpointBasePath = checkpointBasePath;
            return this;
        }

        public Builder embeddingBatchSize(int embeddingBatchSize) {
            this.embeddingBatchSize = embeddingBatchSize;
            return this;
        }

        public Builder maxBatchSize(int maxBatchSize) {
            this.maxBatchSize = maxBatchSize;
            return this;
        }

        public Builder queueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
            return this;
        }

        public Builder parallelIndexing(boolean parallelIndexing) {
            this.parallelIndexing = parallelIndexing;
            return this;
        }

        public Builder indexingWorkers(int indexingWorkers) {
            this.indexingWorkers = indexingWorkers;
            return this;
        }

        public Builder indexingBatchAccumulationSize(int indexingBatchAccumulationSize) {
            this.indexingBatchAccumulationSize = indexingBatchAccumulationSize;
            return this;
        }

        public Builder embeddingThreads(int embeddingThreads) {
            this.embeddingThreads = embeddingThreads;
            return this;
        }

        public Builder callbackBaseUrl(String callbackBaseUrl) {
            this.callbackBaseUrl = callbackBaseUrl;
            return this;
        }

        public Builder nd4jConfigJson(String nd4jConfigJson) {
            this.nd4jConfigJson = nd4jConfigJson;
            return this;
        }

        public Builder options(Map<String, String> options) {
            this.options = options;
            return this;
        }

        public Builder modelSourceType(String modelSourceType) {
            this.modelSourceType = modelSourceType;
            return this;
        }

        public Builder modelIdentifier(String modelIdentifier) {
            this.modelIdentifier = modelIdentifier;
            return this;
        }

        public Builder stagingUrl(String stagingUrl) {
            this.stagingUrl = stagingUrl;
            return this;
        }

        public Builder stagingApiKey(String stagingApiKey) {
            this.stagingApiKey = stagingApiKey;
            return this;
        }

        public Builder archivePath(String archivePath) {
            this.archivePath = archivePath;
            return this;
        }

        public Builder memoryThresholdPercent(int memoryThresholdPercent) {
            this.memoryThresholdPercent = memoryThresholdPercent;
            return this;
        }

        public Builder memoryCriticalPercent(int memoryCriticalPercent) {
            this.memoryCriticalPercent = memoryCriticalPercent;
            return this;
        }

        public Builder memoryKillThresholdPercent(int memoryKillThresholdPercent) {
            this.memoryKillThresholdPercent = memoryKillThresholdPercent;
            return this;
        }

        public Builder memoryCheckIntervalMs(long memoryCheckIntervalMs) {
            this.memoryCheckIntervalMs = memoryCheckIntervalMs;
            return this;
        }

        public Builder gpuMemoryThresholdPercent(int gpuMemoryThresholdPercent) {
            this.gpuMemoryThresholdPercent = gpuMemoryThresholdPercent;
            return this;
        }

        public Builder gpuMemoryCriticalPercent(int gpuMemoryCriticalPercent) {
            this.gpuMemoryCriticalPercent = gpuMemoryCriticalPercent;
            return this;
        }

        public Builder gpuMemoryKillThresholdPercent(int gpuMemoryKillThresholdPercent) {
            this.gpuMemoryKillThresholdPercent = gpuMemoryKillThresholdPercent;
            return this;
        }

        public Builder offHeapThresholdPercent(int offHeapThresholdPercent) {
            this.offHeapThresholdPercent = offHeapThresholdPercent;
            return this;
        }

        public Builder offHeapCriticalPercent(int offHeapCriticalPercent) {
            this.offHeapCriticalPercent = offHeapCriticalPercent;
            return this;
        }

        public Builder offHeapKillThresholdPercent(int offHeapKillThresholdPercent) {
            this.offHeapKillThresholdPercent = offHeapKillThresholdPercent;
            return this;
        }

        public VectorPopulationSubprocessArgs build() {
            return new VectorPopulationSubprocessArgs(
                    taskId,
                    keywordIndexPath,
                    vectorIndexPath,
                    checkpointBasePath,
                    embeddingBatchSize,
                    maxBatchSize,
                    queueCapacity,
                    parallelIndexing,
                    indexingWorkers,
                    indexingBatchAccumulationSize,
                    embeddingThreads,
                    callbackBaseUrl,
                    nd4jConfigJson,
                    modelSourceType,
                    modelIdentifier,
                    stagingUrl,
                    stagingApiKey,
                    archivePath,
                    memoryThresholdPercent,
                    memoryCriticalPercent,
                    memoryKillThresholdPercent,
                    memoryCheckIntervalMs,
                    gpuMemoryThresholdPercent,
                    gpuMemoryCriticalPercent,
                    gpuMemoryKillThresholdPercent,
                    offHeapThresholdPercent,
                    offHeapCriticalPercent,
                    offHeapKillThresholdPercent,
                    options
            );
        }
    }
}
