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

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Calculates adaptive settings for subprocess retry after OOM.
 *
 * Strategy:
 * 1. First attempt uses configured defaults
 * 2. On OOM, reduce batch size by 50% (down to min of 4)
 * 3. If batch already at minimum, reduce threads by 50%
 * 4. If threads at minimum (1), try increasing heap by 50%
 * 5. After max retries (5), give up with detailed error
 *
 * The goal is to maintain performance while finding stable settings.
 * Each adjustment is logged with rationale.
 */
@Getter
public class AdaptiveRecoverySettings {

    private static final Logger logger = LoggerFactory.getLogger(AdaptiveRecoverySettings.class);

    // === Configuration Bounds ===
    public static final int MIN_BATCH_SIZE = 4;
    public static final int MAX_BATCH_SIZE = 256;
    public static final int MIN_THREADS = 1;
    public static final int MIN_EMBEDDING_WORKERS = 1;
    public static final long MIN_HEAP_BYTES = 1L * 1024 * 1024 * 1024;  // 1GB minimum
    public static final long MAX_HEAP_BYTES = 32L * 1024 * 1024 * 1024; // 32GB maximum
    public static final int MAX_RETRY_ATTEMPTS = 5;

    private static final Pattern HEAP_PATTERN = Pattern.compile("^(\\d+)([gGmMkK]?)$");

    // === Current Settings ===
    private String heapSize;
    private int batchSize;
    private int nd4jThreads;
    private int ompThreads;
    private int embeddingWorkers;
    private int retryAttempt;

    // === Computed ===
    private long heapBytes;
    @Getter(value = lombok.AccessLevel.NONE)
    private boolean shouldGiveUp;
    private String giveUpReason;

    public AdaptiveRecoverySettings() {
        this.retryAttempt = 0;
        this.shouldGiveUp = false;
    }

    /**
     * Initialize with default settings.
     */
    public static AdaptiveRecoverySettings defaults() {
        AdaptiveRecoverySettings settings = new AdaptiveRecoverySettings();
        settings.heapSize = "4g";
        settings.heapBytes = 4L * 1024 * 1024 * 1024;
        settings.batchSize = 32;
        settings.nd4jThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        settings.ompThreads = settings.nd4jThreads;
        settings.embeddingWorkers = 1;
        return settings;
    }

    /**
     * Initialize from checkpoint's failed settings history.
     */
    public static AdaptiveRecoverySettings fromCheckpoint(IngestCheckpoint checkpoint,
                                                           String defaultHeapSize,
                                                           int defaultBatchSize) {
        AdaptiveRecoverySettings settings = new AdaptiveRecoverySettings();
        settings.retryAttempt = checkpoint.getOomFailureCount();

        List<IngestCheckpoint.FailedSettings> history = checkpoint.getFailedSettingsHistory();
        if (history == null || history.isEmpty()) {
            // No history - use defaults
            settings.heapSize = defaultHeapSize != null ? defaultHeapSize : "4g";
            settings.batchSize = defaultBatchSize > 0 ? defaultBatchSize : 32;
            settings.nd4jThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
            settings.ompThreads = settings.nd4jThreads;
            settings.embeddingWorkers = 1;
        } else {
            // Get the last failed settings and compute adjustments
            IngestCheckpoint.FailedSettings lastFailed = history.get(history.size() - 1);
            settings.calculateNextSettings(lastFailed, defaultHeapSize);
        }

        settings.heapBytes = parseHeapToBytes(settings.heapSize);
        return settings;
    }

    /**
     * Calculate next settings based on what failed.
     */
    private void calculateNextSettings(IngestCheckpoint.FailedSettings failed, String maxHeapSize) {
        int failedBatch = failed.batchSize > 0 ? failed.batchSize : 32;
        int failedNd4jThreads = failed.nd4jThreads > 0 ? failed.nd4jThreads : Runtime.getRuntime().availableProcessors() / 2;
        int failedOmpThreads = failed.ompThreads > 0 ? failed.ompThreads : failedNd4jThreads;
        String failedHeap = failed.heapSize != null ? failed.heapSize : "4g";
        long failedHeapBytes = parseHeapToBytes(failedHeap);
        long maxHeapBytes = maxHeapSize != null ? parseHeapToBytes(maxHeapSize) : MAX_HEAP_BYTES;

        logger.info("Calculating recovery settings after OOM. Failed with: heap={}, batch={}, nd4jThreads={}, ompThreads={}",
                failedHeap, failedBatch, failedNd4jThreads, failedOmpThreads);

        // Strategy 1: Reduce batch size (most effective, least performance impact per item)
        if (failedBatch > MIN_BATCH_SIZE) {
            int newBatch = Math.max(MIN_BATCH_SIZE, failedBatch / 2);
            logger.info("Recovery strategy: Reducing batch size {} -> {}", failedBatch, newBatch);

            this.batchSize = newBatch;
            this.nd4jThreads = failedNd4jThreads;
            this.ompThreads = failedOmpThreads;
            this.heapSize = failedHeap;
            this.heapBytes = failedHeapBytes;
            this.embeddingWorkers = 1;
            return;
        }

        // Strategy 2: Reduce threads (helps with memory fragmentation and contention)
        if (failedNd4jThreads > MIN_THREADS || failedOmpThreads > MIN_THREADS) {
            int newNd4jThreads = Math.max(MIN_THREADS, failedNd4jThreads / 2);
            int newOmpThreads = Math.max(MIN_THREADS, failedOmpThreads / 2);
            logger.info("Recovery strategy: Reducing threads nd4j={}->{}, omp={}->{}",
                    failedNd4jThreads, newNd4jThreads, failedOmpThreads, newOmpThreads);

            this.batchSize = MIN_BATCH_SIZE;
            this.nd4jThreads = newNd4jThreads;
            this.ompThreads = newOmpThreads;
            this.heapSize = failedHeap;
            this.heapBytes = failedHeapBytes;
            this.embeddingWorkers = 1;
            return;
        }

        // Strategy 3: Increase heap (if possible)
        if (failedHeapBytes < maxHeapBytes) {
            long newHeapBytes = Math.min(maxHeapBytes, (long)(failedHeapBytes * 1.5));
            String newHeap = formatHeapSize(newHeapBytes);
            logger.info("Recovery strategy: Increasing heap {} -> {} (max allowed: {})",
                    failedHeap, newHeap, formatHeapSize(maxHeapBytes));

            this.batchSize = MIN_BATCH_SIZE;
            this.nd4jThreads = MIN_THREADS;
            this.ompThreads = MIN_THREADS;
            this.heapSize = newHeap;
            this.heapBytes = newHeapBytes;
            this.embeddingWorkers = 1;
            return;
        }

        // Strategy 4: Give up - we've exhausted all options
        logger.error("RECOVERY EXHAUSTED: All strategies failed. batch={}, threads={}, heap={}. Cannot continue.",
                MIN_BATCH_SIZE, MIN_THREADS, formatHeapSize(maxHeapBytes));

        this.shouldGiveUp = true;
        this.giveUpReason = String.format(
                "Adaptive recovery exhausted after %d attempts. Tried: batch=%d (min), threads=%d (min), heap=%s (max). " +
                        "The document may be too large or complex for available memory. " +
                        "Consider: (1) splitting the document, (2) increasing system RAM, (3) using a smaller embedding model.",
                retryAttempt, MIN_BATCH_SIZE, MIN_THREADS, formatHeapSize(maxHeapBytes));

        // Set to minimums anyway for logging
        this.batchSize = MIN_BATCH_SIZE;
        this.nd4jThreads = MIN_THREADS;
        this.ompThreads = MIN_THREADS;
        this.heapSize = formatHeapSize(maxHeapBytes);
        this.heapBytes = maxHeapBytes;
        this.embeddingWorkers = 1;
    }

    /**
     * Record a successful run and potentially increase settings for next time.
     * This allows gradual recovery of performance after stability is achieved.
     */
    public AdaptiveRecoverySettings optimizeAfterSuccess(int chunksProcessed, long durationMs) {
        // If we had previous failures but succeeded, we could cautiously increase settings
        // For now, just keep the working settings
        logger.info("Completed {} chunks in {}ms with settings: batch={}, threads={}",
                chunksProcessed, durationMs, batchSize, nd4jThreads);
        return this;
    }

    /**
     * Check if we've exceeded max retries.
     */
    public boolean shouldRetry() {
        return !shouldGiveUp && retryAttempt < MAX_RETRY_ATTEMPTS;
    }

    /**
     * Parse heap size string to bytes.
     */
    public static long parseHeapToBytes(String heapSize) {
        if (heapSize == null || heapSize.isEmpty()) {
            return 4L * 1024 * 1024 * 1024; // Default 4GB
        }

        String normalized = heapSize.trim().toLowerCase();
        Matcher matcher = HEAP_PATTERN.matcher(normalized);
        if (!matcher.matches()) {
            logger.warn("Could not parse heap size '{}', defaulting to 4GB", heapSize);
            return 4L * 1024 * 1024 * 1024;
        }

        long value = Long.parseLong(matcher.group(1));
        String unit = matcher.group(2);

        return switch (unit) {
            case "k" -> value * 1024;
            case "m" -> value * 1024 * 1024;
            case "g", "" -> value * 1024 * 1024 * 1024; // Default to GB if no unit
            default -> value * 1024 * 1024 * 1024;
        };
    }

    /**
     * Format bytes to heap size string.
     */
    public static String formatHeapSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return (bytes / (1024L * 1024 * 1024)) + "g";
        } else if (bytes >= 1024L * 1024) {
            return (bytes / (1024L * 1024)) + "m";
        } else {
            return bytes + "";
        }
    }

    /**
     * Get summary of current settings for logging.
     */
    public String toSummary() {
        return String.format("heap=%s, batch=%d, nd4jThreads=%d, ompThreads=%d, workers=%d, attempt=%d/%d",
                heapSize, batchSize, nd4jThreads, ompThreads, embeddingWorkers, retryAttempt, MAX_RETRY_ATTEMPTS);
    }

    // Manual getter for shouldGiveUp
    public boolean isShouldGiveUp() { return shouldGiveUp; }

    // === Setters for builder pattern (fluent-style, non-trivial) ===

    public AdaptiveRecoverySettings heapSize(String heapSize) {
        this.heapSize = heapSize;
        this.heapBytes = parseHeapToBytes(heapSize);
        return this;
    }

    public AdaptiveRecoverySettings batchSize(int batchSize) {
        this.batchSize = Math.max(MIN_BATCH_SIZE, Math.min(MAX_BATCH_SIZE, batchSize));
        return this;
    }

    public AdaptiveRecoverySettings nd4jThreads(int threads) {
        this.nd4jThreads = Math.max(MIN_THREADS, threads);
        return this;
    }

    public AdaptiveRecoverySettings ompThreads(int threads) {
        this.ompThreads = Math.max(MIN_THREADS, threads);
        return this;
    }

    public AdaptiveRecoverySettings embeddingWorkers(int workers) {
        this.embeddingWorkers = Math.max(MIN_EMBEDDING_WORKERS, workers);
        return this;
    }

    public AdaptiveRecoverySettings retryAttempt(int attempt) {
        this.retryAttempt = attempt;
        return this;
    }
}
