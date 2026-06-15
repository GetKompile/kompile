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

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Interface for a single stage in the document ingest pipeline.
 * Each stage processes input of type I and produces output of type O.
 *
 * <p>Stages are designed to be:</p>
 * <ul>
 *   <li>Thread-safe: Multiple threads can call process() concurrently</li>
 *   <li>Configurable: Options can be set before processing begins</li>
 *   <li>Observable: Metrics are collected for monitoring</li>
 *   <li>Cancellable: Processing can be interrupted gracefully</li>
 * </ul>
 *
 * @param <I> Input type for this stage
 * @param <O> Output type for this stage
 */
public interface PipelineStage<I, O> {

    /**
     * Returns the name of this stage (e.g., "extraction", "tokenization", "chunking").
     */
    String getName();

    /**
     * Processes a single input item and returns the output.
     * This method must be thread-safe for parallel execution.
     *
     * @param input The input to process
     * @return The processed output
     * @throws Exception if processing fails
     */
    O process(I input) throws Exception;

    /**
     * Configures this stage with the given options.
     * Called before processing begins.
     *
     * @param options Configuration options for this stage
     */
    void configure(Map<String, Object> options);

    /**
     * Returns the current metrics for this stage.
     */
    StageMetrics getMetrics();

    /**
     * Signals that processing should be cancelled.
     * Implementations should check for cancellation periodically.
     */
    default void cancel() {
        // Default no-op, subclasses can override
    }

    /**
     * Returns true if this stage has been cancelled.
     */
    default boolean isCancelled() {
        return false;
    }

    /**
     * Resets this stage for a new processing run.
     * Clears metrics and any cached state.
     */
    default void reset() {
        getMetrics().reset();
    }

    /**
     * Metrics collected for a pipeline stage.
     */
    class StageMetrics {
        private final AtomicLong itemsProcessed = new AtomicLong(0);
        private final AtomicLong itemsFailed = new AtomicLong(0);
        private final AtomicLong totalProcessingTimeNanos = new AtomicLong(0);
        private final AtomicLong minProcessingTimeNanos = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong maxProcessingTimeNanos = new AtomicLong(0);
        private final AtomicLong startTimeMs = new AtomicLong(0);
        private final AtomicLong bytesProcessed = new AtomicLong(0);
        private final AtomicLong outputItemsProduced = new AtomicLong(0);

        public void recordSuccess(long processingTimeNanos) {
            itemsProcessed.incrementAndGet();
            totalProcessingTimeNanos.addAndGet(processingTimeNanos);
            updateMinMax(processingTimeNanos);
        }

        public void recordSuccess(long processingTimeNanos, long bytes, int outputItems) {
            recordSuccess(processingTimeNanos);
            bytesProcessed.addAndGet(bytes);
            outputItemsProduced.addAndGet(outputItems);
        }

        public void recordFailure() {
            itemsFailed.incrementAndGet();
        }

        private void updateMinMax(long nanos) {
            long currentMin = minProcessingTimeNanos.get();
            while (nanos < currentMin) {
                if (minProcessingTimeNanos.compareAndSet(currentMin, nanos)) {
                    break;
                }
                currentMin = minProcessingTimeNanos.get();
            }

            long currentMax = maxProcessingTimeNanos.get();
            while (nanos > currentMax) {
                if (maxProcessingTimeNanos.compareAndSet(currentMax, nanos)) {
                    break;
                }
                currentMax = maxProcessingTimeNanos.get();
            }
        }

        public void markStart() {
            startTimeMs.set(System.currentTimeMillis());
        }

        public void reset() {
            itemsProcessed.set(0);
            itemsFailed.set(0);
            totalProcessingTimeNanos.set(0);
            minProcessingTimeNanos.set(Long.MAX_VALUE);
            maxProcessingTimeNanos.set(0);
            startTimeMs.set(0);
            bytesProcessed.set(0);
            outputItemsProduced.set(0);
        }

        public long getItemsProcessed() {
            return itemsProcessed.get();
        }

        public long getItemsFailed() {
            return itemsFailed.get();
        }

        public long getTotalProcessingTimeNanos() {
            return totalProcessingTimeNanos.get();
        }

        public long getTotalProcessingTimeMs() {
            return totalProcessingTimeNanos.get() / 1_000_000;
        }

        public double getAverageProcessingTimeMs() {
            long items = itemsProcessed.get();
            if (items == 0) return 0;
            return (totalProcessingTimeNanos.get() / 1_000_000.0) / items;
        }

        public long getMinProcessingTimeMs() {
            long min = minProcessingTimeNanos.get();
            return min == Long.MAX_VALUE ? 0 : min / 1_000_000;
        }

        public long getMaxProcessingTimeMs() {
            return maxProcessingTimeNanos.get() / 1_000_000;
        }

        public long getElapsedTimeMs() {
            long start = startTimeMs.get();
            if (start == 0) return 0;
            return System.currentTimeMillis() - start;
        }

        public double getThroughput() {
            long elapsed = getElapsedTimeMs();
            if (elapsed == 0) return 0;
            return (itemsProcessed.get() * 1000.0) / elapsed;
        }

        public long getBytesProcessed() {
            return bytesProcessed.get();
        }

        public double getByteThroughputMBps() {
            long elapsed = getElapsedTimeMs();
            if (elapsed == 0) return 0;
            return (bytesProcessed.get() / (1024.0 * 1024.0)) / (elapsed / 1000.0);
        }

        public long getOutputItemsProduced() {
            return outputItemsProduced.get();
        }

        public double getExpansionRatio() {
            long items = itemsProcessed.get();
            if (items == 0) return 0;
            return (double) outputItemsProduced.get() / items;
        }

        @Override
        public String toString() {
            return String.format(
                    "StageMetrics{processed=%d, failed=%d, avgMs=%.2f, throughput=%.1f/sec, " +
                            "bytes=%d, output=%d, expansion=%.2fx}",
                    itemsProcessed.get(), itemsFailed.get(),
                    getAverageProcessingTimeMs(), getThroughput(),
                    bytesProcessed.get(), outputItemsProduced.get(),
                    getExpansionRatio()
            );
        }
    }
}
