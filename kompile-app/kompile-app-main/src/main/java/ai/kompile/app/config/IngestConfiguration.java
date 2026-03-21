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

package ai.kompile.app.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Configuration for document ingest processing settings.
 * These settings control concurrency, batch sizes, and memory thresholds.
 */
@Configuration
@ConfigurationProperties(prefix = "kompile.ingest")
public class IngestConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(IngestConfiguration.class);

    /**
     * Maximum number of concurrent ingest jobs allowed.
     * When this limit is reached, new uploads will be queued.
     */
    private int maxConcurrentJobs = 4;

    /**
     * Number of chunks to process in each batch during embedding/indexing.
     * Larger batches are more efficient but use more memory.
     * PERFORMANCE: Increased from 50 to 100 for better throughput.
     */
    private int indexBatchSize = 100;

    /**
     * Minimum batch size regardless of document size.
     */
    private int minBatchSize = 25;

    /**
     * Maximum batch size to prevent memory issues.
     * PERFORMANCE: Increased from 200 to 500 for systems with adequate memory.
     */
    private int maxBatchSize = 500;

    /**
     * Target batch size for embedding operations.
     * This is the ideal batch size under normal memory conditions.
     * ADAPTIVE: The actual batch size may be reduced under memory pressure.
     */
    private int embeddingTargetBatchSize = 64;

    /**
     * Maximum time to wait (ms) for batch accumulation.
     * If this time is exceeded, process whatever chunks are available.
     */
    private long embeddingMaxWaitMs = 500;

    /**
     * Minimum time to wait (ms) before checking queue again.
     * Prevents busy-waiting while allowing responsive batch collection.
     */
    private long embeddingMinWaitMs = 25;

    /**
     * Memory usage threshold (percentage) above which new jobs will be queued.
     * Set to 0 to disable memory-based throttling.
     */
    private int memoryThresholdPercent = 80;

    /**
     * Memory usage threshold (percentage) at which to pause processing.
     * Jobs will resume when memory drops below this level.
     */
    private int memoryCriticalPercent = 90;

    /**
     * Memory usage threshold (percentage) at which to forcibly kill running jobs.
     * When memory exceeds this threshold and remains high, jobs will be terminated
     * and an audit log event will be fired. Set to 0 to disable automatic killing.
     * Default: 95% (higher than critical to allow graceful shutdown first)
     */
    private int memoryKillThresholdPercent = 95;

    /**
     * Enable automatic batch size adjustment based on available memory.
     */
    private boolean adaptiveBatchSize = true;

    /**
     * Queue capacity for pending ingest jobs.
     */
    private int queueCapacity = 100;

    /**
     * Thread pool core size for async processing.
     */
    private int corePoolSize = 4;

    /**
     * Thread pool maximum size for async processing.
     * OPTIMIZED: Increased from 8 to 12 for better parallelism on modern CPUs.
     */
    private int maxPoolSize = 12;

    /**
     * Default chunk size in characters.
     *
     * <p>Chunk size affects both retrieval granularity and embedding efficiency:</p>
     * <ul>
     *   <li>1000 chars ≈ 200-250 tokens → allows 20-30 items per forward pass</li>
     *   <li>1500 chars ≈ 300-375 tokens → allows 8-16 items per forward pass</li>
     *   <li>2000 chars ≈ 400-500 tokens → allows 4-8 items per forward pass</li>
     * </ul>
     *
     * <p>Smaller chunks enable larger batch sizes with less padding waste,
     * improving embedding throughput. Default: 1000 chars for good balance
     * between retrieval granularity and batch efficiency.</p>
     */
    private int defaultChunkSize = 1000;

    /**
     * Default overlap between chunks in characters.
     * 10% of chunk size is recommended for context preservation.
     */
    private int defaultChunkOverlap = 100;

    /**
     * Default chunking strategy.
     * Options: table-aware (recommended), recursive-character, sentence, markdown, token
     * table-aware preserves table boundaries while chunking text normally.
     */
    private String defaultChunker = "table-aware";

    // ========== Large Document Processing Settings ==========

    /**
     * File size threshold (bytes) above which documents are processed using streaming.
     * Default: 10MB
     */
    private long largeDocumentSizeThreshold = 10 * 1024 * 1024; // 10MB

    /**
     * Page count threshold above which documents are processed using streaming.
     * Default: 50 pages
     */
    private int largeDocumentPageThreshold = 50;

    /**
     * Enable resume support for large document processing.
     * When enabled, processing state is persisted and can be resumed after failures.
     */
    private boolean enableResumeSupport = true;

    /**
     * Directory for storing processing state files for resume support.
     */
    private String stateDirectory = System.getProperty("user.home") + "/.kompile/state";

    // ========== Pipeline Threading Settings ==========

    /**
     * Number of threads for chunking operations.
     * Chunking is CPU-bound and can be parallelized across cores.
     * Default: half of available cores, capped at 16.
     */
    private int chunkingThreads = Math.min(Runtime.getRuntime().availableProcessors() / 2, 16);

    /**
     * Number of embedding workers.
     * Use 1 worker - parallelism comes from OpenMP/BLAS internally.
     * Multiple workers cause thread contention with OpenMP.
     */
    private int embeddingThreads = 1;

    /**
     * Number of threads for indexing operations.
     * Indexing involves I/O and can benefit from moderate parallelism.
     * Default: 4
     */
    private int indexingThreads = 4;

    /**
     * Queue capacity for chunks waiting to be embedded.
     * Larger queues allow more buffering but use more memory.
     * Default: 1000 (each chunk ~2KB = ~2MB total)
     */
    private int pipelineQueueCapacity = 1000;

    /**
     * Number of batches to accumulate before bulk indexing.
     * Higher values improve indexing efficiency but increase latency.
     * Default: 8
     */
    private int indexingBatchAccumulationSize = 8;

    /**
     * Maximum batch wait time in milliseconds.
     * Time to wait for batch accumulation before processing partial batch.
     * Default: 500ms
     */
    private long maxBatchWaitMs = 500;

    /**
     * Minimum batch wait time in milliseconds.
     * Minimum delay between queue checks to prevent busy-waiting.
     * Default: 25ms
     */
    private long minBatchWaitMs = 25;

    /**
     * Enable parallel indexing of keyword and vector stores.
     * When true, keyword and vector indexes are updated concurrently.
     * Default: true
     */
    private boolean parallelIndexingEnabled = true;

    // Track active jobs
    private final AtomicInteger activeJobCount = new AtomicInteger(0);

    // Reference to the task executor for dynamic updates
    private ThreadPoolTaskExecutor taskExecutor;

    public int getMaxConcurrentJobs() {
        return maxConcurrentJobs;
    }

    public void setMaxConcurrentJobs(int maxConcurrentJobs) {
        this.maxConcurrentJobs = Math.max(1, Math.min(maxConcurrentJobs, 32));
        logger.info("Max concurrent jobs set to: {}", this.maxConcurrentJobs);
    }

    public int getIndexBatchSize() {
        return indexBatchSize;
    }

    public void setIndexBatchSize(int indexBatchSize) {
        this.indexBatchSize = Math.max(minBatchSize, Math.min(indexBatchSize, maxBatchSize));
        logger.info("Index batch size set to: {}", this.indexBatchSize);
    }

    public int getMinBatchSize() {
        return minBatchSize;
    }

    public void setMinBatchSize(int minBatchSize) {
        this.minBatchSize = Math.max(1, minBatchSize);
    }

    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    public void setMaxBatchSize(int maxBatchSize) {
        this.maxBatchSize = Math.max(this.minBatchSize, maxBatchSize);
    }

    public int getEmbeddingTargetBatchSize() {
        return embeddingTargetBatchSize;
    }

    public void setEmbeddingTargetBatchSize(int embeddingTargetBatchSize) {
        this.embeddingTargetBatchSize = Math.max(8, Math.min(embeddingTargetBatchSize, maxBatchSize));
        logger.info("Embedding target batch size set to: {}", this.embeddingTargetBatchSize);
    }

    public long getEmbeddingMaxWaitMs() {
        return embeddingMaxWaitMs;
    }

    public void setEmbeddingMaxWaitMs(long embeddingMaxWaitMs) {
        this.embeddingMaxWaitMs = Math.max(100, Math.min(embeddingMaxWaitMs, 5000));
        logger.info("Embedding max wait set to: {}ms", this.embeddingMaxWaitMs);
    }

    public long getEmbeddingMinWaitMs() {
        return embeddingMinWaitMs;
    }

    public void setEmbeddingMinWaitMs(long embeddingMinWaitMs) {
        this.embeddingMinWaitMs = Math.max(10, Math.min(embeddingMinWaitMs, this.embeddingMaxWaitMs / 2));
    }

    public int getMemoryThresholdPercent() {
        return memoryThresholdPercent;
    }

    public void setMemoryThresholdPercent(int memoryThresholdPercent) {
        this.memoryThresholdPercent = Math.max(0, Math.min(memoryThresholdPercent, 100));
        logger.info("Memory threshold set to: {}%", this.memoryThresholdPercent);
    }

    public int getMemoryCriticalPercent() {
        return memoryCriticalPercent;
    }

    public void setMemoryCriticalPercent(int memoryCriticalPercent) {
        this.memoryCriticalPercent = Math.max(this.memoryThresholdPercent, Math.min(memoryCriticalPercent, 100));
    }

    public int getMemoryKillThresholdPercent() {
        return memoryKillThresholdPercent;
    }

    public void setMemoryKillThresholdPercent(int memoryKillThresholdPercent) {
        // Kill threshold must be >= critical threshold (kill is more severe than critical)
        this.memoryKillThresholdPercent = Math.max(0, Math.min(memoryKillThresholdPercent, 100));
        if (this.memoryKillThresholdPercent > 0 && this.memoryKillThresholdPercent < this.memoryCriticalPercent) {
            logger.warn("Memory kill threshold {}% is lower than critical threshold {}%. " +
                    "Adjusting kill threshold to critical level.", this.memoryKillThresholdPercent, this.memoryCriticalPercent);
            this.memoryKillThresholdPercent = this.memoryCriticalPercent;
        }
        logger.info("Memory kill threshold set to: {}%", this.memoryKillThresholdPercent);
    }

    public boolean isAdaptiveBatchSize() {
        return adaptiveBatchSize;
    }

    public void setAdaptiveBatchSize(boolean adaptiveBatchSize) {
        this.adaptiveBatchSize = adaptiveBatchSize;
        logger.info("Adaptive batch size: {}", adaptiveBatchSize);
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = Math.max(1, queueCapacity);
    }

    public int getCorePoolSize() {
        return corePoolSize;
    }

    public void setCorePoolSize(int corePoolSize) {
        this.corePoolSize = Math.max(1, corePoolSize);
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public void setMaxPoolSize(int maxPoolSize) {
        this.maxPoolSize = Math.max(this.corePoolSize, maxPoolSize);
    }

    public int getDefaultChunkSize() {
        return defaultChunkSize;
    }

    public void setDefaultChunkSize(int defaultChunkSize) {
        // Minimum 100 chars, maximum 10000 chars
        this.defaultChunkSize = Math.max(100, Math.min(defaultChunkSize, 10000));
        logger.info("Default chunk size set to: {}", this.defaultChunkSize);
    }

    public int getDefaultChunkOverlap() {
        return defaultChunkOverlap;
    }

    public void setDefaultChunkOverlap(int defaultChunkOverlap) {
        // Overlap should be 0-50% of chunk size
        int maxOverlap = this.defaultChunkSize / 2;
        this.defaultChunkOverlap = Math.max(0, Math.min(defaultChunkOverlap, maxOverlap));
        logger.info("Default chunk overlap set to: {}", this.defaultChunkOverlap);
    }

    public String getDefaultChunker() {
        return defaultChunker;
    }

    public void setDefaultChunker(String defaultChunker) {
        this.defaultChunker = defaultChunker != null ? defaultChunker : "recursive-character";
        logger.info("Default chunker set to: {}", this.defaultChunker);
    }

    // ========== Large Document Getters/Setters ==========

    public long getLargeDocumentSizeThreshold() {
        return largeDocumentSizeThreshold;
    }

    public void setLargeDocumentSizeThreshold(long largeDocumentSizeThreshold) {
        // Minimum 1MB, maximum 1GB
        this.largeDocumentSizeThreshold = Math.max(1024 * 1024, Math.min(largeDocumentSizeThreshold, 1024L * 1024 * 1024));
        logger.info("Large document size threshold set to: {} bytes", this.largeDocumentSizeThreshold);
    }

    public int getLargeDocumentPageThreshold() {
        return largeDocumentPageThreshold;
    }

    public void setLargeDocumentPageThreshold(int largeDocumentPageThreshold) {
        // Minimum 10 pages, maximum 1000 pages
        this.largeDocumentPageThreshold = Math.max(10, Math.min(largeDocumentPageThreshold, 1000));
        logger.info("Large document page threshold set to: {} pages", this.largeDocumentPageThreshold);
    }

    public boolean isEnableResumeSupport() {
        return enableResumeSupport;
    }

    public void setEnableResumeSupport(boolean enableResumeSupport) {
        this.enableResumeSupport = enableResumeSupport;
        logger.info("Resume support enabled: {}", enableResumeSupport);
    }

    public String getStateDirectory() {
        return stateDirectory;
    }

    public void setStateDirectory(String stateDirectory) {
        this.stateDirectory = stateDirectory != null ? stateDirectory : System.getProperty("user.home") + "/.kompile/state";
        logger.info("State directory set to: {}", this.stateDirectory);
    }

    // ========== Pipeline Threading Getters/Setters ==========

    public int getChunkingThreads() {
        return chunkingThreads;
    }

    public void setChunkingThreads(int chunkingThreads) {
        this.chunkingThreads = Math.max(1, Math.min(chunkingThreads, 64));
        logger.info("Chunking threads set to: {}", this.chunkingThreads);
    }

    public int getEmbeddingThreads() {
        return embeddingThreads;
    }

    public void setEmbeddingThreads(int embeddingThreads) {
        this.embeddingThreads = Math.max(1, Math.min(embeddingThreads, 16));
        logger.info("Embedding threads set to: {}", this.embeddingThreads);
    }

    public int getIndexingThreads() {
        return indexingThreads;
    }

    public void setIndexingThreads(int indexingThreads) {
        this.indexingThreads = Math.max(1, Math.min(indexingThreads, 32));
        logger.info("Indexing threads set to: {}", this.indexingThreads);
    }

    public int getPipelineQueueCapacity() {
        return pipelineQueueCapacity;
    }

    public void setPipelineQueueCapacity(int pipelineQueueCapacity) {
        this.pipelineQueueCapacity = Math.max(100, Math.min(pipelineQueueCapacity, 10000));
        logger.info("Pipeline queue capacity set to: {}", this.pipelineQueueCapacity);
    }

    public int getIndexingBatchAccumulationSize() {
        return indexingBatchAccumulationSize;
    }

    public void setIndexingBatchAccumulationSize(int indexingBatchAccumulationSize) {
        this.indexingBatchAccumulationSize = Math.max(1, Math.min(indexingBatchAccumulationSize, 64));
        logger.info("Indexing batch accumulation size set to: {}", this.indexingBatchAccumulationSize);
    }

    public long getMaxBatchWaitMs() {
        return maxBatchWaitMs;
    }

    public void setMaxBatchWaitMs(long maxBatchWaitMs) {
        this.maxBatchWaitMs = Math.max(100, Math.min(maxBatchWaitMs, 5000));
        logger.info("Max batch wait set to: {}ms", this.maxBatchWaitMs);
    }

    public long getMinBatchWaitMs() {
        return minBatchWaitMs;
    }

    public void setMinBatchWaitMs(long minBatchWaitMs) {
        this.minBatchWaitMs = Math.max(10, Math.min(minBatchWaitMs, this.maxBatchWaitMs / 2));
        logger.info("Min batch wait set to: {}ms", this.minBatchWaitMs);
    }

    public boolean isParallelIndexingEnabled() {
        return parallelIndexingEnabled;
    }

    public void setParallelIndexingEnabled(boolean parallelIndexingEnabled) {
        this.parallelIndexingEnabled = parallelIndexingEnabled;
        logger.info("Parallel indexing enabled: {}", parallelIndexingEnabled);
    }

    /**
     * Gets the chunking options map based on current configuration.
     */
    public java.util.Map<String, Object> getChunkingOptions() {
        java.util.Map<String, Object> options = new java.util.HashMap<>();
        options.put("chunkSize", defaultChunkSize);
        options.put("overlap", defaultChunkOverlap);
        options.put("preserveParagraphs", true);
        return options;
    }

    /**
     * Registers a job start. Returns false if max concurrent jobs reached.
     */
    public boolean tryStartJob() {
        int currentCount = activeJobCount.get();
        if (currentCount >= maxConcurrentJobs) {
            return false;
        }
        return activeJobCount.compareAndSet(currentCount, currentCount + 1) || tryStartJob();
    }

    /**
     * Registers a job completion.
     */
    public void completeJob() {
        activeJobCount.decrementAndGet();
    }

    /**
     * Gets current active job count.
     */
    public int getActiveJobCount() {
        return activeJobCount.get();
    }

    /**
     * Checks if system can accept new jobs based on memory and job count.
     */
    public boolean canAcceptNewJob() {
        if (activeJobCount.get() >= maxConcurrentJobs) {
            return false;
        }

        if (memoryThresholdPercent > 0) {
            double memoryUsage = getMemoryUsagePercent();
            if (memoryUsage >= memoryThresholdPercent) {
                logger.warn("Memory threshold exceeded: {}% >= {}%", String.format("%.1f", memoryUsage), memoryThresholdPercent);
                return false;
            }
        }

        return true;
    }

    /**
     * Calculates current memory usage as a percentage.
     */
    public double getMemoryUsagePercent() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        return (usedMemory * 100.0) / maxMemory;
    }

    /**
     * Gets memory info for monitoring.
     */
    public MemoryInfo getMemoryInfo() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        double usagePercent = (usedMemory * 100.0) / maxMemory;

        return new MemoryInfo(
                maxMemory,
                totalMemory,
                freeMemory,
                usedMemory,
                usagePercent,
                usagePercent >= memoryThresholdPercent,
                usagePercent >= memoryCriticalPercent,
                memoryKillThresholdPercent > 0 && usagePercent >= memoryKillThresholdPercent
        );
    }

    /**
     * Calculates optimal batch size based on current memory availability.
     */
    public int calculateOptimalBatchSize(int totalChunks) {
        if (!adaptiveBatchSize) {
            return indexBatchSize;
        }

        MemoryInfo memInfo = getMemoryInfo();
        int baseBatchSize = indexBatchSize;

        // Reduce batch size as memory usage increases
        if (memInfo.usagePercent() > 70) {
            baseBatchSize = (int) (indexBatchSize * 0.75);
        }
        if (memInfo.usagePercent() > 80) {
            baseBatchSize = (int) (indexBatchSize * 0.5);
        }
        if (memInfo.usagePercent() > 90) {
            baseBatchSize = minBatchSize;
        }

        // Also consider total chunks - don't have too many batches
        int maxBatches = 50;
        int minBatchForChunks = (int) Math.ceil((double) totalChunks / maxBatches);

        int optimalSize = Math.max(minBatchSize, Math.max(baseBatchSize, minBatchForChunks));
        return Math.min(optimalSize, maxBatchSize);
    }

    public void setTaskExecutor(ThreadPoolTaskExecutor taskExecutor) {
        this.taskExecutor = taskExecutor;
    }

    /**
     * Memory information record for monitoring.
     */
    public record MemoryInfo(
            long maxBytes,
            long totalBytes,
            long freeBytes,
            long usedBytes,
            double usagePercent,
            boolean thresholdExceeded,
            boolean criticalExceeded,
            boolean killThresholdExceeded
    ) {
        public long maxMB() {
            return maxBytes / (1024 * 1024);
        }

        public long usedMB() {
            return usedBytes / (1024 * 1024);
        }

        public long freeMB() {
            return (maxBytes - usedBytes) / (1024 * 1024);
        }
    }
}
