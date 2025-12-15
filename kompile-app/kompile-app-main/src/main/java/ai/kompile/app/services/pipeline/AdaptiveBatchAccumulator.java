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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Adaptive batch accumulator that intelligently collects items from a queue
 * and forms optimal batches for embedding processing.
 *
 * <h2>Optimization Strategy</h2>
 * <p>
 * Instead of processing items as they arrive (which leads to many small batches),
 * this accumulator waits to collect larger batches while balancing:
 * </p>
 * <ul>
 *   <li><b>Throughput</b>: Larger batches = fewer embedding calls = better GPU utilization</li>
 *   <li><b>Latency</b>: Don't wait too long - process if queue is empty or timeout exceeded</li>
 *   <li><b>Memory</b>: Respect memory pressure - reduce batch size when heap is constrained</li>
 *   <li><b>Backpressure</b>: If downstream is slow, accumulate more items</li>
 * </ul>
 *
 * <h2>Adaptive Sizing</h2>
 * <pre>
 * Queue State          | Memory Pressure | Batch Size
 * --------------------|-----------------|------------
 * Many items waiting   | Low             | Maximum (targetBatchSize)
 * Many items waiting   | High            | Reduced (targetBatchSize * 0.5)
 * Few items waiting    | Low             | What's available (min: minBatchSize)
 * Few items waiting    | High            | Process immediately
 * Producer complete    | Any             | Process remaining
 * </pre>
 *
 * @param <T> The type of items to accumulate
 */
public class AdaptiveBatchAccumulator<T> {

    private static final Logger logger = LoggerFactory.getLogger(AdaptiveBatchAccumulator.class);

    // Configuration
    private final int minBatchSize;
    private final int targetBatchSize;
    private final int maxBatchSize;
    private final long maxWaitMs;
    private final long minWaitMs;

    // Memory thresholds
    private final double memoryPressureThreshold;
    private final double memoryCriticalThreshold;

    // Statistics
    private final AtomicLong totalBatches = new AtomicLong(0);
    private final AtomicLong totalItems = new AtomicLong(0);
    private final AtomicLong totalWaitTimeMs = new AtomicLong(0);

    /**
     * Creates an adaptive batch accumulator with default settings.
     *
     * @param targetBatchSize The ideal batch size under normal conditions
     */
    public AdaptiveBatchAccumulator(int targetBatchSize) {
        this(
                Math.max(8, targetBatchSize / 4),  // minBatchSize: 25% of target
                targetBatchSize,
                targetBatchSize * 2,               // maxBatchSize: 2x target
                500,                               // maxWaitMs: 500ms
                50                                 // minWaitMs: 50ms
        );
    }

    /**
     * Creates an adaptive batch accumulator with custom settings.
     *
     * @param minBatchSize   Minimum batch size (process immediately if this many available)
     * @param targetBatchSize Target batch size under normal conditions
     * @param maxBatchSize   Maximum batch size (never exceed this)
     * @param maxWaitMs      Maximum time to wait for more items
     * @param minWaitMs      Minimum time to wait before checking queue again
     */
    public AdaptiveBatchAccumulator(int minBatchSize, int targetBatchSize, int maxBatchSize,
                                     long maxWaitMs, long minWaitMs) {
        this.minBatchSize = Math.max(1, minBatchSize);
        this.targetBatchSize = Math.max(this.minBatchSize, targetBatchSize);
        this.maxBatchSize = Math.max(this.targetBatchSize, maxBatchSize);
        this.maxWaitMs = Math.max(100, maxWaitMs);
        this.minWaitMs = Math.max(10, Math.min(minWaitMs, this.maxWaitMs / 2));
        this.memoryPressureThreshold = 0.70;
        this.memoryCriticalThreshold = 0.85;

        logger.debug("AdaptiveBatchAccumulator created: min={}, target={}, max={}, maxWait={}ms",
                this.minBatchSize, this.targetBatchSize, this.maxBatchSize, this.maxWaitMs);
    }

    /**
     * Accumulates items from a queue into an optimal batch.
     *
     * <p>This method blocks until either:</p>
     * <ul>
     *   <li>Target batch size is reached</li>
     *   <li>Maximum wait time exceeded</li>
     *   <li>Producer signals completion and queue is empty</li>
     *   <li>Memory pressure forces early processing</li>
     * </ul>
     *
     * @param queue          The queue to drain items from
     * @param isComplete     Supplier that returns true when producer is done
     * @param pollTimeoutMs  Timeout for individual poll operations
     * @return A batch of items, or empty list if interrupted
     */
    public List<T> accumulateBatch(BlockingQueue<? extends List<T>> queue,
                                    Supplier<Boolean> isComplete,
                                    long pollTimeoutMs) {
        List<T> batch = new ArrayList<>(targetBatchSize);
        long startTime = System.currentTimeMillis();
        long waitedMs = 0;

        // Calculate effective batch size based on memory pressure
        int effectiveBatchSize = calculateEffectiveBatchSize();

        while (!Thread.currentThread().isInterrupted()) {
            try {
                // Calculate remaining wait time
                long remainingWait = maxWaitMs - waitedMs;

                // CRITICAL FIX: When timeout expires, process whatever we have (even < minBatchSize)
                // This prevents blocking indefinitely in streaming mode
                if (remainingWait <= 0) {
                    if (!batch.isEmpty()) {
                        // Timeout reached with some items - process them now
                        logger.debug("Batch timeout with {} items (< min {}), processing anyway",
                                batch.size(), minBatchSize);
                        break;
                    } else if (isComplete.get()) {
                        // Timeout with no items and producer complete - exit
                        break;
                    }
                    // No items yet but producer still running - allow brief additional wait
                }

                // Poll with adaptive timeout
                long pollTimeout = Math.min(pollTimeoutMs, Math.min(remainingWait > 0 ? remainingWait : minWaitMs, minWaitMs));
                List<T> items = (List<T>) queue.poll(pollTimeout, TimeUnit.MILLISECONDS);

                if (items != null && !items.isEmpty()) {
                    batch.addAll(items);

                    // Check if we've reached target
                    if (batch.size() >= effectiveBatchSize) {
                        break;
                    }

                    // Check if we've exceeded max
                    if (batch.size() >= maxBatchSize) {
                        break;
                    }
                }

                waitedMs = System.currentTimeMillis() - startTime;

                // Check for completion condition
                if (isComplete.get() && queue.isEmpty()) {
                    break;
                }

                // Re-evaluate batch size if memory pressure changed
                int newEffectiveSize = calculateEffectiveBatchSize();
                if (newEffectiveSize < effectiveBatchSize && batch.size() >= newEffectiveSize) {
                    logger.debug("Memory pressure increased - processing batch early ({} items)", batch.size());
                    break;
                }
                effectiveBatchSize = newEffectiveSize;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // Update statistics
        if (!batch.isEmpty()) {
            totalBatches.incrementAndGet();
            totalItems.addAndGet(batch.size());
            totalWaitTimeMs.addAndGet(System.currentTimeMillis() - startTime);
        }

        return batch;
    }

    /**
     * Accumulates items from a flat queue (individual items, not lists).
     *
     * @param queue          The queue to drain items from
     * @param isComplete     Supplier that returns true when producer is done
     * @param pollTimeoutMs  Timeout for individual poll operations
     * @return A batch of items, or empty list if interrupted
     */
    public List<T> accumulateFlatBatch(BlockingQueue<T> queue,
                                        Supplier<Boolean> isComplete,
                                        long pollTimeoutMs) {
        List<T> batch = new ArrayList<>(targetBatchSize);
        long startTime = System.currentTimeMillis();
        long waitedMs = 0;

        int effectiveBatchSize = calculateEffectiveBatchSize();

        while (!Thread.currentThread().isInterrupted()) {
            try {
                long remainingWait = maxWaitMs - waitedMs;

                // CRITICAL FIX: When timeout expires, process whatever we have (even < minBatchSize)
                // This prevents the accumulator from blocking indefinitely in streaming mode
                // where chunks arrive slowly and chunkingComplete stays false during loading.
                if (remainingWait <= 0) {
                    if (!batch.isEmpty()) {
                        // Timeout reached with some items - process them now
                        logger.debug("Batch timeout with {} items (< min {}), processing anyway",
                                batch.size(), minBatchSize);
                        break;
                    } else if (isComplete.get()) {
                        // Timeout with no items and producer complete - exit
                        break;
                    }
                    // No items yet but producer still running - reset and wait more
                    // This only happens if queue is truly empty, allow brief additional wait
                }

                // Drain as many items as available without blocking
                int drained = queue.drainTo(batch, effectiveBatchSize - batch.size());

                if (drained == 0) {
                    // Queue empty - poll with timeout
                    long pollTimeout = Math.min(pollTimeoutMs, Math.max(minWaitMs, remainingWait > 0 ? remainingWait : minWaitMs));
                    T item = queue.poll(pollTimeout, TimeUnit.MILLISECONDS);
                    if (item != null) {
                        batch.add(item);
                    }
                }

                // Check termination conditions
                if (batch.size() >= effectiveBatchSize || batch.size() >= maxBatchSize) {
                    break;
                }

                waitedMs = System.currentTimeMillis() - startTime;

                if (isComplete.get() && queue.isEmpty()) {
                    break;
                }

                // Re-evaluate batch size
                int newEffectiveSize = calculateEffectiveBatchSize();
                if (newEffectiveSize < effectiveBatchSize && batch.size() >= newEffectiveSize) {
                    break;
                }
                effectiveBatchSize = newEffectiveSize;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (!batch.isEmpty()) {
            totalBatches.incrementAndGet();
            totalItems.addAndGet(batch.size());
            totalWaitTimeMs.addAndGet(System.currentTimeMillis() - startTime);
        }

        return batch;
    }

    /**
     * Calculates the effective batch size based on current memory pressure.
     */
    private int calculateEffectiveBatchSize() {
        double memoryUsage = getMemoryUsagePercent();

        if (memoryUsage >= memoryCriticalThreshold) {
            // Critical memory - use minimum batch size
            return minBatchSize;
        } else if (memoryUsage >= memoryPressureThreshold) {
            // Under pressure - reduce batch size proportionally
            double reduction = (memoryUsage - memoryPressureThreshold) /
                    (memoryCriticalThreshold - memoryPressureThreshold);
            int reduced = (int) (targetBatchSize * (1 - reduction * 0.5));
            return Math.max(minBatchSize, reduced);
        } else {
            // Normal - use target
            return targetBatchSize;
        }
    }

    private double getMemoryUsagePercent() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        return (usedMemory * 100.0) / maxMemory;
    }

    /**
     * Gets accumulator statistics.
     */
    public AccumulatorStats getStats() {
        long batches = totalBatches.get();
        long items = totalItems.get();
        long waitTime = totalWaitTimeMs.get();

        double avgBatchSize = batches > 0 ? (double) items / batches : 0;
        double avgWaitMs = batches > 0 ? (double) waitTime / batches : 0;

        return new AccumulatorStats(batches, items, avgBatchSize, avgWaitMs);
    }

    /**
     * Resets accumulator statistics.
     */
    public void resetStats() {
        totalBatches.set(0);
        totalItems.set(0);
        totalWaitTimeMs.set(0);
    }

    /**
     * Statistics record for the accumulator.
     */
    public record AccumulatorStats(
            long totalBatches,
            long totalItems,
            double avgBatchSize,
            double avgWaitMs
    ) {
        @Override
        public String toString() {
            return String.format("AccumulatorStats[batches=%d, items=%d, avgSize=%.1f, avgWait=%.1fms]",
                    totalBatches, totalItems, avgBatchSize, avgWaitMs);
        }
    }

    // Getters for configuration
    public int getMinBatchSize() { return minBatchSize; }
    public int getTargetBatchSize() { return targetBatchSize; }
    public int getMaxBatchSize() { return maxBatchSize; }
    public long getMaxWaitMs() { return maxWaitMs; }
}
