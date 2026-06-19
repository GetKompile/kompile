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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Handles progress/ETA reporting for the parallel ingest pipeline.
 *
 * <p>Encapsulates:</p>
 * <ul>
 *   <li>Sliding-window rate tracking for accurate ETA calculation</li>
 *   <li>Overall progress percentage calculation per pipeline mode</li>
 *   <li>Throttled and worker-aware progress reporting via callback</li>
 * </ul>
 */
public class PipelineProgressReporter {

    private static final Logger logger = LoggerFactory.getLogger(PipelineProgressReporter.class);

    private static final long PROGRESS_REPORT_INTERVAL_MS = 50;

    // Sliding window rate tracking
    private static final int RATE_WINDOW_SIZE = 10;
    private static final long RATE_SAMPLE_INTERVAL_MS = 2000;

    private final long[] rateSampleTimestamps = new long[RATE_WINDOW_SIZE];
    private final int[] rateSampleCounts = new int[RATE_WINDOW_SIZE];
    private volatile int rateSampleIndex = 0;
    private volatile long lastRateSampleTime = 0;

    private final AtomicLong lastProgressReportMs = new AtomicLong(0);

    // Context passed in from the pipeline
    private final IngestPipelineConfig pipelineConfig;
    private final boolean vectorOnlyMode;
    private Consumer<PipelineProgress> progressCallback;

    public PipelineProgressReporter(IngestPipelineConfig pipelineConfig, boolean vectorOnlyMode) {
        this.pipelineConfig = pipelineConfig;
        this.vectorOnlyMode = vectorOnlyMode;
    }

    public void setProgressCallback(Consumer<PipelineProgress> callback) {
        this.progressCallback = callback;
    }

    // ========== ETA / Rate Calculation ==========

    /**
     * Updates the sliding window rate samples and calculates the windowed rate.
     * This provides a more accurate ETA by using recent throughput rather than overall average.
     *
     * @param currentProcessed current number of items processed
     * @return windowed rate in items per second, or -1 if not enough samples yet
     */
    public double updateAndGetWindowedRate(int currentProcessed) {
        long now = System.currentTimeMillis();

        // Only sample at intervals to avoid noise
        if (now - lastRateSampleTime >= RATE_SAMPLE_INTERVAL_MS) {
            // Store current sample
            rateSampleTimestamps[rateSampleIndex] = now;
            rateSampleCounts[rateSampleIndex] = currentProcessed;
            rateSampleIndex = (rateSampleIndex + 1) % RATE_WINDOW_SIZE;
            lastRateSampleTime = now;
        }

        // Find oldest valid sample in the window
        int oldestIndex = -1;
        long oldestTime = Long.MAX_VALUE;
        int validSamples = 0;

        for (int i = 0; i < RATE_WINDOW_SIZE; i++) {
            if (rateSampleTimestamps[i] > 0) {
                validSamples++;
                if (rateSampleTimestamps[i] < oldestTime) {
                    oldestTime = rateSampleTimestamps[i];
                    oldestIndex = i;
                }
            }
        }

        // Need at least 2 samples to calculate rate
        if (validSamples < 2 || oldestIndex < 0) {
            return -1;
        }

        // Calculate rate from oldest to current
        long timeDelta = now - oldestTime;
        int countDelta = currentProcessed - rateSampleCounts[oldestIndex];

        if (timeDelta > 0 && countDelta >= 0) {
            return (countDelta * 1000.0) / timeDelta;
        }

        return -1;
    }

    /**
     * Calculates ETA string using windowed rate with fallback to overall rate.
     *
     * @param remaining        items remaining to process
     * @param currentProcessed current items processed
     * @param overallRate      overall average rate (fallback)
     * @return formatted ETA string, or empty if cannot calculate
     */
    public String calculateEtaString(long remaining, int currentProcessed, double overallRate) {
        // Try windowed rate first (more accurate for ongoing work)
        double rate = updateAndGetWindowedRate(currentProcessed);

        // Fall back to overall rate if windowed rate not available
        if (rate < 0) {
            rate = overallRate;
        }

        if (rate > 0 && remaining > 0) {
            long etaSec = (long) (remaining / rate);
            if (etaSec > 3600) {
                return String.format(" (~%dh %dm)", etaSec / 3600, (etaSec % 3600) / 60);
            } else if (etaSec > 60) {
                return String.format(" (~%dm %ds)", etaSec / 60, etaSec % 60);
            } else {
                return String.format(" (~%ds)", etaSec);
            }
        }
        return "";
    }

    // ========== Progress Reporting ==========

    public void reportProgressThrottled(String phase, int percent, String message,
                                        long startTimeMs,
                                        int documentsProcessed, int chunksCreated,
                                        int chunksEmbedded, int chunksIndexed,
                                        long tokensProcessed,
                                        int chunkingParallelism, int embeddingParallelism,
                                        ConcurrentHashMap<String, PipelineProgress.WorkerStatus> workerStatuses,
                                        int chunkQueueSize,
                                        PipelineProgress.EmbeddingBatchMetrics currentBatchMetrics,
                                        List<PipelineProgress.EmbeddingBatchMetrics> batchHistory,
                                        AdaptivePipelineConfig adaptiveConfig) {
        long now = System.currentTimeMillis();
        long lastReport = lastProgressReportMs.get();
        if (now - lastReport >= PROGRESS_REPORT_INTERVAL_MS) {
            if (lastProgressReportMs.compareAndSet(lastReport, now)) {
                reportProgress(phase, percent, message, startTimeMs,
                        documentsProcessed, chunksCreated, chunksEmbedded, chunksIndexed,
                        tokensProcessed, chunkingParallelism, embeddingParallelism,
                        workerStatuses, chunkQueueSize, currentBatchMetrics, batchHistory, adaptiveConfig);
            }
        }
    }

    public void reportProgress(String phase, int percent, String message,
                               long startTimeMs,
                               int documentsProcessed, int chunksCreated,
                               int chunksEmbedded, int chunksIndexed,
                               long tokensProcessed,
                               int chunkingParallelism, int embeddingParallelism,
                               ConcurrentHashMap<String, PipelineProgress.WorkerStatus> workerStatuses,
                               int chunkQueueSize,
                               PipelineProgress.EmbeddingBatchMetrics currentBatchMetrics,
                               List<PipelineProgress.EmbeddingBatchMetrics> batchHistory,
                               AdaptivePipelineConfig adaptiveConfig) {
        reportProgressWithWorkers(phase, percent, message, startTimeMs,
                documentsProcessed, chunksCreated, chunksEmbedded, chunksIndexed,
                tokensProcessed, chunkingParallelism, embeddingParallelism,
                workerStatuses, chunkQueueSize, currentBatchMetrics, batchHistory, adaptiveConfig);
    }

    public void reportProgressWithWorkers(String phase, int ignoredPercent, String message,
                                          long startTimeMs,
                                          int documentsProcessed, int chunksCreated,
                                          int chunksEmbedded, int chunksIndexed,
                                          long tokensProcessed,
                                          int chunkingParallelism, int embeddingParallelism,
                                          ConcurrentHashMap<String, PipelineProgress.WorkerStatus> workerStatuses,
                                          int chunkQueueSize,
                                          PipelineProgress.EmbeddingBatchMetrics currentBatchMetrics,
                                          List<PipelineProgress.EmbeddingBatchMetrics> batchHistory,
                                          AdaptivePipelineConfig adaptiveConfig) {
        if (progressCallback != null) {
            long elapsed = System.currentTimeMillis() - startTimeMs;

            int effectiveCompleted = vectorOnlyMode ? chunksEmbedded : Math.max(chunksIndexed, chunksEmbedded);
            double throughput = elapsed > 0 ? (effectiveCompleted * 1000.0 / elapsed) : 0;

            // Collect worker statuses
            List<PipelineProgress.WorkerStatus> workers = new ArrayList<>(workerStatuses.values());

            // Build queue status
            PipelineProgress.QueueStatus queueStatus = new PipelineProgress.QueueStatus(
                    chunkQueueSize,
                    pipelineConfig.queueCapacity(),
                    0,  // embeddedQueue removed
                    0);

            // Build progress object first, then calculate overall progress
            PipelineProgress progress = new PipelineProgress(
                    phase,
                    0, // Will be calculated
                    message,
                    documentsProcessed,
                    chunksCreated,
                    chunksEmbedded,
                    chunksIndexed,
                    tokensProcessed,
                    throughput,
                    adaptiveConfig.getMemoryUsagePercent(),
                    chunkingParallelism,
                    embeddingParallelism,
                    workers,
                    queueStatus,
                    currentBatchMetrics,
                    batchHistory);

            // Calculate the actual overall progress
            int calculatedPercent = calculateOverallProgressPercent(
                    phase,
                    progress.documentsProcessed(),
                    progress.chunksCreated(),
                    progress.chunksEmbedded(),
                    progress.chunksIndexed());

            // Create final progress with correct percentage
            PipelineProgress finalProgress = new PipelineProgress(
                    phase,
                    calculatedPercent,
                    message,
                    progress.documentsProcessed(),
                    progress.chunksCreated(),
                    progress.chunksEmbedded(),
                    progress.chunksIndexed(),
                    progress.tokensProcessed(),
                    progress.chunksPerSecond(),
                    progress.memoryUsagePercent(),
                    progress.chunkingWorkers(),
                    progress.embeddingWorkers(),
                    progress.workerStatuses(),
                    progress.queueStatus(),
                    progress.currentEmbeddingBatch(),
                    progress.batchHistory());

            try {
                progressCallback.accept(finalProgress);
            } catch (Exception e) {
                logger.warn("Progress callback failed: {}", e.getMessage());
            }
        }
    }

    // ========== Progress Percentage Calculation ==========

    public int calculateOverallProgressPercent(String phase, int documentsProcessed, int chunksCreated,
                                               int chunksEmbedded, int chunksIndexed) {
        if (phase != null) {
            String p = phase.trim().toLowerCase(Locale.ROOT);
            if (p.equals("completed") || p.equals("complete") || p.equals("done")) {
                return 100;
            }
        }

        if (chunksCreated <= 0) {
            return documentsProcessed > 0 ? Math.min(5, documentsProcessed) : 0;
        }

        // In vector-only mode, Lucene indexing is skipped entirely: embedding is the terminal stage.
        if (vectorOnlyMode) {
            final double READY_WEIGHT = 0.10;
            final double EMBEDDING_WEIGHT = 0.90;
            double embeddingProgress = (double) chunksEmbedded / chunksCreated;
            double overall = (READY_WEIGHT) + (embeddingProgress * EMBEDDING_WEIGHT);
            return clampPercent(overall);
        }

        // Passthrough/keyword-only mode: embedding is performed by indexer (or skipped).
        boolean isPassthroughMode = (chunksEmbedded == 0 && chunksIndexed > 0);
        if (isPassthroughMode || pipelineConfig.skipEmbedding()) {
            final double READY_WEIGHT = 0.10;
            final double INDEXING_WEIGHT = 0.90;
            double indexingProgress = (double) chunksIndexed / chunksCreated;
            double overall = (READY_WEIGHT) + (indexingProgress * INDEXING_WEIGHT);
            return clampPercent(overall);
        }

        // Standard mode: chunking (10%) + embedding (50%) + indexing (40%).
        final double READY_WEIGHT = 0.10;
        final double EMBEDDING_WEIGHT = 0.50;
        final double INDEXING_WEIGHT = 0.40;

        double embeddingProgress = (double) chunksEmbedded / chunksCreated;
        double indexingProgress = (double) chunksIndexed / chunksCreated;
        double overall = (READY_WEIGHT) + (embeddingProgress * EMBEDDING_WEIGHT) + (indexingProgress * INDEXING_WEIGHT);
        return clampPercent(overall);
    }

    public static int clampPercent(double overallProgress01) {
        double clamped = Math.max(0.0, Math.min(1.0, overallProgress01));
        return Math.min(100, Math.max(0, (int) (clamped * 100)));
    }
}
