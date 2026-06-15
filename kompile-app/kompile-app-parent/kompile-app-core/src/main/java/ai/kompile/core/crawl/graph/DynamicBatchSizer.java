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

package ai.kompile.core.crawl.graph;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Adaptive batch sizer that dynamically adjusts batch sizes based on:
 * <ol>
 *   <li><b>Memory pressure</b>: JVM heap and native/GPU memory usage</li>
 *   <li><b>Throughput tracking</b>: EMA of batch processing latency</li>
 *   <li><b>Error rate</b>: OOM or timeout signals trigger immediate shrink</li>
 * </ol>
 *
 * <p>Inspired by TCP congestion control (AIMD — additive increase, multiplicative decrease):
 * batch size grows linearly when conditions are good and shrinks multiplicatively
 * under memory pressure or errors.</p>
 *
 * <p>Each pipeline stage (EMBEDDING, GRAPH_EXTRACTION, VECTOR_INDEXING, CHUNKING)
 * should have its own {@code DynamicBatchSizer} instance with stage-specific bounds.</p>
 *
 * <p>Usage pattern:</p>
 * <pre>{@code
 * DynamicBatchSizer sizer = DynamicBatchSizer.builder()
 *     .minBatchSize(1).maxBatchSize(128).initialBatchSize(32)
 *     .memoryPressureThreshold(0.80).memoryCriticalThreshold(0.90)
 *     .build();
 *
 * int batchSize = sizer.currentBatchSize();
 * // ... process batch ...
 * sizer.recordBatchResult(batchSize, elapsedMs, success, memoryUsagePercent);
 * }</pre>
 */
public class DynamicBatchSizer {

    public enum AdjustDirection { UP, DOWN, HOLD }

    /** Stage identifier for logging/UI */
    private final String stageId;

    // ---- Bounds ----
    private final int minBatchSize;
    private final int maxBatchSize;

    // ---- Memory thresholds ----
    private final double memoryPressureThreshold;  // start shrinking
    private final double memoryCriticalThreshold;   // shrink aggressively

    // ---- EMA parameters ----
    private final double emaAlpha;  // smoothing factor for latency EMA

    // ---- Mutable state (thread-safe) ----
    private final AtomicInteger currentSize;
    private final AtomicLong emaLatencyMsX100;       // EMA * 100 for precision
    private final AtomicLong peakThroughputX100;     // items/sec * 100
    private final AtomicInteger adjustmentCount;
    private final AtomicReference<AdjustDirection> lastDirection;
    private final AtomicReference<String> lastReason;

    // AIMD parameters
    private final int additiveIncrease;
    private final double multiplicativeDecrease;

    // Consecutive success/failure tracking for hysteresis
    private final AtomicInteger consecutiveSuccesses;
    private final AtomicInteger consecutiveFailures;
    private final int successThresholdForIncrease;

    private DynamicBatchSizer(Builder builder) {
        this.stageId = builder.stageId;
        this.minBatchSize = builder.minBatchSize;
        this.maxBatchSize = builder.maxBatchSize;
        this.memoryPressureThreshold = builder.memoryPressureThreshold;
        this.memoryCriticalThreshold = builder.memoryCriticalThreshold;
        this.emaAlpha = builder.emaAlpha;
        this.additiveIncrease = builder.additiveIncrease;
        this.multiplicativeDecrease = builder.multiplicativeDecrease;
        this.successThresholdForIncrease = builder.successThresholdForIncrease;
        this.currentSize = new AtomicInteger(builder.initialBatchSize);
        this.emaLatencyMsX100 = new AtomicLong(0);
        this.peakThroughputX100 = new AtomicLong(0);
        this.adjustmentCount = new AtomicInteger(0);
        this.lastDirection = new AtomicReference<>(AdjustDirection.HOLD);
        this.lastReason = new AtomicReference<>(null);
        this.consecutiveSuccesses = new AtomicInteger(0);
        this.consecutiveFailures = new AtomicInteger(0);
    }

    /**
     * Get the current recommended batch size.
     */
    public int currentBatchSize() {
        return currentSize.get();
    }

    /**
     * Record the result of processing a batch, adjusting the batch size accordingly.
     *
     * @param batchSize      the actual batch size processed
     * @param elapsedMs      wall-clock time for the batch
     * @param success        whether the batch completed without OOM/timeout
     * @param memoryPercent  current memory usage as a fraction (0.0 - 1.0)
     */
    public void recordBatchResult(int batchSize, long elapsedMs, boolean success,
                                  double memoryPercent) {
        if (!success) {
            // Multiplicative decrease on failure
            consecutiveFailures.incrementAndGet();
            consecutiveSuccesses.set(0);
            int newSize = Math.max(minBatchSize,
                    (int)(currentSize.get() * multiplicativeDecrease));
            if (newSize != currentSize.get()) {
                currentSize.set(newSize);
                adjustmentCount.incrementAndGet();
                lastDirection.set(AdjustDirection.DOWN);
                lastReason.set("batch_failure");
            }
            return;
        }

        consecutiveFailures.set(0);
        consecutiveSuccesses.incrementAndGet();

        // Update EMA latency
        if (batchSize > 0 && elapsedMs > 0) {
            long perItemMs = (elapsedMs * 100L) / batchSize;
            long prevEma = emaLatencyMsX100.get();
            long newEma;
            if (prevEma == 0) {
                newEma = perItemMs;
            } else {
                newEma = (long)(emaAlpha * perItemMs + (1.0 - emaAlpha) * prevEma);
            }
            emaLatencyMsX100.set(newEma);

            // Track throughput (items/sec * 100)
            long throughput = (batchSize * 100_000L) / Math.max(1, elapsedMs);
            long prevPeak = peakThroughputX100.get();
            if (throughput > prevPeak) {
                peakThroughputX100.compareAndSet(prevPeak, throughput);
            }
        }

        // Decide adjustment direction
        if (memoryPercent >= memoryCriticalThreshold) {
            // Critical: aggressive shrink
            int newSize = Math.max(minBatchSize,
                    (int)(currentSize.get() * multiplicativeDecrease));
            if (newSize != currentSize.get()) {
                currentSize.set(newSize);
                adjustmentCount.incrementAndGet();
                lastDirection.set(AdjustDirection.DOWN);
                lastReason.set("memory_critical");
            }
        } else if (memoryPercent >= memoryPressureThreshold) {
            // Pressure: mild shrink
            int newSize = Math.max(minBatchSize, currentSize.get() - additiveIncrease);
            if (newSize != currentSize.get()) {
                currentSize.set(newSize);
                adjustmentCount.incrementAndGet();
                lastDirection.set(AdjustDirection.DOWN);
                lastReason.set("memory_pressure");
            }
        } else if (consecutiveSuccesses.get() >= successThresholdForIncrease) {
            // Stable: additive increase
            int newSize = Math.min(maxBatchSize, currentSize.get() + additiveIncrease);
            if (newSize != currentSize.get()) {
                currentSize.set(newSize);
                adjustmentCount.incrementAndGet();
                lastDirection.set(AdjustDirection.UP);
                lastReason.set("stable_throughput");
                consecutiveSuccesses.set(0);  // reset after growth
            } else {
                lastDirection.set(AdjustDirection.HOLD);
                lastReason.set("at_max");
            }
        } else {
            lastDirection.set(AdjustDirection.HOLD);
        }
    }

    /**
     * Force an immediate shrink (e.g., on OOM signal from outside the batch loop).
     */
    public void emergencyShrink(String reason) {
        int newSize = Math.max(minBatchSize,
                (int)(currentSize.get() * multiplicativeDecrease * multiplicativeDecrease));
        currentSize.set(newSize);
        adjustmentCount.incrementAndGet();
        lastDirection.set(AdjustDirection.DOWN);
        lastReason.set(reason);
        consecutiveSuccesses.set(0);
        consecutiveFailures.incrementAndGet();
    }

    /**
     * Publish current stats to a {@link UnifiedCrawlJob} for UI visibility.
     */
    public void publishStats(UnifiedCrawlJob job) {
        job.getAdaptiveBatchSize().set(currentSize.get());
        job.getBatchSizeAdjustments().set(adjustmentCount.get());
        job.getLastBatchAdjustDirection().set(
                lastDirection.get() != null ? lastDirection.get().name() : "HOLD");
        job.getLastBatchAdjustReason().set(lastReason.get());
        job.getBatchEmaLatencyMsX100().set(emaLatencyMsX100.get());
        job.getPeakThroughputX100().set(peakThroughputX100.get());
    }

    // ---- Accessors ----

    public String getStageId() { return stageId; }
    public int getMinBatchSize() { return minBatchSize; }
    public int getMaxBatchSize() { return maxBatchSize; }
    public long getEmaLatencyMsX100() { return emaLatencyMsX100.get(); }
    public long getPeakThroughputX100() { return peakThroughputX100.get(); }
    public int getAdjustmentCount() { return adjustmentCount.get(); }
    public AdjustDirection getLastDirection() { return lastDirection.get(); }
    public String getLastReason() { return lastReason.get(); }

    // ---- Builder ----

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String stageId = "default";
        private int minBatchSize = 1;
        private int maxBatchSize = 128;
        private int initialBatchSize = 16;
        private double memoryPressureThreshold = 0.80;
        private double memoryCriticalThreshold = 0.90;
        private double emaAlpha = 0.3;
        private int additiveIncrease = 2;
        private double multiplicativeDecrease = 0.5;
        private int successThresholdForIncrease = 3;

        public Builder stageId(String stageId) {
            this.stageId = stageId;
            return this;
        }

        public Builder minBatchSize(int min) {
            this.minBatchSize = min;
            return this;
        }

        public Builder maxBatchSize(int max) {
            this.maxBatchSize = max;
            return this;
        }

        public Builder initialBatchSize(int initial) {
            this.initialBatchSize = initial;
            return this;
        }

        public Builder memoryPressureThreshold(double threshold) {
            this.memoryPressureThreshold = threshold;
            return this;
        }

        public Builder memoryCriticalThreshold(double threshold) {
            this.memoryCriticalThreshold = threshold;
            return this;
        }

        public Builder emaAlpha(double alpha) {
            this.emaAlpha = alpha;
            return this;
        }

        public Builder additiveIncrease(int increment) {
            this.additiveIncrease = increment;
            return this;
        }

        public Builder multiplicativeDecrease(double factor) {
            this.multiplicativeDecrease = factor;
            return this;
        }

        public Builder successThresholdForIncrease(int threshold) {
            this.successThresholdForIncrease = threshold;
            return this;
        }

        public DynamicBatchSizer build() {
            if (minBatchSize < 1) throw new IllegalArgumentException("minBatchSize must be >= 1");
            if (maxBatchSize < minBatchSize) throw new IllegalArgumentException("maxBatchSize must be >= minBatchSize");
            if (initialBatchSize < minBatchSize || initialBatchSize > maxBatchSize) {
                initialBatchSize = Math.max(minBatchSize, Math.min(maxBatchSize, initialBatchSize));
            }
            return new DynamicBatchSizer(this);
        }
    }

    /**
     * Predefined stage profiles with empirically-tuned defaults.
     */
    public static DynamicBatchSizer forEmbedding(int maxBatch) {
        return builder()
                .stageId("EMBEDDING")
                .minBatchSize(1)
                .maxBatchSize(maxBatch)
                .initialBatchSize(Math.max(1, maxBatch / 4))
                .memoryPressureThreshold(0.78)
                .memoryCriticalThreshold(0.88)
                .additiveIncrease(4)
                .multiplicativeDecrease(0.5)
                .successThresholdForIncrease(2)
                .build();
    }

    public static DynamicBatchSizer forGraphExtraction(int maxBatch) {
        return builder()
                .stageId("GRAPH_EXTRACTION")
                .minBatchSize(1)
                .maxBatchSize(maxBatch)
                .initialBatchSize(Math.max(1, maxBatch / 2))
                .memoryPressureThreshold(0.82)
                .memoryCriticalThreshold(0.92)
                .additiveIncrease(1)
                .multiplicativeDecrease(0.6)
                .successThresholdForIncrease(3)
                .build();
    }

    public static DynamicBatchSizer forVectorIndexing(int maxBatch) {
        return builder()
                .stageId("VECTOR_INDEXING")
                .minBatchSize(1)
                .maxBatchSize(maxBatch)
                .initialBatchSize(Math.max(1, maxBatch / 2))
                .memoryPressureThreshold(0.80)
                .memoryCriticalThreshold(0.90)
                .additiveIncrease(4)
                .multiplicativeDecrease(0.5)
                .successThresholdForIncrease(2)
                .build();
    }

    public static DynamicBatchSizer forChunking(int maxBatch) {
        return builder()
                .stageId("CHUNKING")
                .minBatchSize(1)
                .maxBatchSize(maxBatch)
                .initialBatchSize(maxBatch)
                .memoryPressureThreshold(0.85)
                .memoryCriticalThreshold(0.95)
                .additiveIncrease(8)
                .multiplicativeDecrease(0.7)
                .successThresholdForIncrease(1)
                .build();
    }
}
