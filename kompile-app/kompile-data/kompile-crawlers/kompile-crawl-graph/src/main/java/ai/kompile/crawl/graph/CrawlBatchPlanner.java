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

package ai.kompile.crawl.graph;

import ai.kompile.core.graphrag.GraphConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

/**
 * Cost-batch planning utility for the unified crawl pipeline.
 *
 * <p>Extracted from {@link UnifiedCrawlGraphServiceImpl} to reduce class size.
 * Provides cost-balanced batch partitioning for chunking and graph extraction
 * phases, plus the {@link OuterParallelismAdvisor} adaptive concurrency control.</p>
 */
@Component
class CrawlBatchPlanner {

    private static final Logger log = LoggerFactory.getLogger(CrawlBatchPlanner.class);

    // ── Internal records ────────────────────────────────────────────────────

    record CostBatch<T>(int index, List<T> items, long cost) {}

    record CostItem<T>(T item, long cost) {}

    static class MutableCostBatch<T> {
        final List<T> items = new ArrayList<>();
        long cost;

        void add(T item, long itemCost) {
            items.add(item);
            cost += itemCost;
        }
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Partitions {@code items} into cost-balanced batches.
     *
     * @param items              items to partition
     * @param costEstimator      function that returns an estimated cost per item
     * @param maxItemsPerBatch   hard cap on items per batch
     * @param targetCostPerBatch soft cap on total cost per batch (0 = unlimited)
     * @param balanceByCost      when true, use a min-heap to balance cost across batches;
     *                           when false, pack sequentially up to the caps
     * @return ordered list of batches (index starts at 1)
     */
    <T> List<CostBatch<T>> planCostBatches(List<T> items,
                                            Function<T, Long> costEstimator,
                                            int maxItemsPerBatch,
                                            long targetCostPerBatch,
                                            boolean balanceByCost) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }

        int maxItems = Math.max(1, maxItemsPerBatch);
        long targetCost = Math.max(0, targetCostPerBatch);
        List<CostItem<T>> costItems = new ArrayList<>(items.size());
        for (T item : items) {
            long cost = 1L;
            try {
                Long estimated = costEstimator.apply(item);
                if (estimated != null) {
                    cost = Math.max(1L, estimated);
                }
            } catch (Exception ignored) {
                cost = 1L;
            }
            costItems.add(new CostItem<>(item, cost));
        }

        if (!balanceByCost) {
            List<CostBatch<T>> batches = new ArrayList<>();
            List<T> current = new ArrayList<>();
            long currentCost = 0L;
            for (CostItem<T> costItem : costItems) {
                boolean overItemLimit = current.size() >= maxItems;
                boolean overCostLimit = targetCost > 0 && !current.isEmpty()
                        && currentCost + costItem.cost() > targetCost;
                if (overItemLimit || overCostLimit) {
                    batches.add(new CostBatch<>(batches.size() + 1, new ArrayList<>(current), currentCost));
                    current.clear();
                    currentCost = 0L;
                }
                current.add(costItem.item());
                currentCost += costItem.cost();
            }
            if (!current.isEmpty()) {
                batches.add(new CostBatch<>(batches.size() + 1, new ArrayList<>(current), currentCost));
            }
            return batches;
        }

        costItems.sort((a, b) -> Long.compare(b.cost(), a.cost()));

        // Use a min-heap by cost to find the lightest batch in O(log B) instead of O(B)
        PriorityQueue<MutableCostBatch<T>> batchHeap = new PriorityQueue<>(
                Comparator.comparingLong(b -> b.cost));
        for (CostItem<T> costItem : costItems) {
            MutableCostBatch<T> best = null;
            if (!batchHeap.isEmpty()) {
                MutableCostBatch<T> lightest = batchHeap.peek();
                if (lightest.items.size() < maxItems) {
                    long newCost = lightest.cost + costItem.cost();
                    if (targetCost <= 0 || lightest.items.isEmpty() || newCost <= targetCost) {
                        best = batchHeap.poll();
                    }
                }
            }
            if (best == null) {
                best = new MutableCostBatch<>();
            }
            best.add(costItem.item(), costItem.cost());
            batchHeap.offer(best);
        }

        List<CostBatch<T>> batches = new ArrayList<>(batchHeap.size());
        for (MutableCostBatch<T> batch : batchHeap) {
            batches.add(new CostBatch<>(batches.size() + 1, new ArrayList<>(batch.items), batch.cost));
        }
        return batches;
    }

    /**
     * Estimates the processing cost of a text string with optional metadata hints.
     */
    long estimateTextCost(String text, Map<String, Object> metadata) {
        long cost = text != null ? Math.max(1, text.length()) : 1;
        if (metadata == null) {
            return cost;
        }
        if (Boolean.TRUE.equals(metadata.get(GraphConstants.META_VLM_PROCESSED))) {
            cost = Math.round(cost * 1.5);
        }
        Object contentType = metadata.get(GraphConstants.META_CONTENT_TYPE);
        if (contentType instanceof String type) {
            String normalized = type.toLowerCase(Locale.ROOT);
            if (normalized.contains("table") || normalized.contains("vlm")) {
                cost = Math.round(cost * 1.3);
            } else if (normalized.contains("html")) {
                cost = Math.round(cost * 1.15);
            }
        }
        return Math.max(1, cost);
    }

    /**
     * Estimates the processing cost of a Spring AI {@link Document}.
     */
    long estimateDocumentCost(Document document) {
        return estimateTextCost(document != null ? document.getText() : null,
                document != null ? document.getMetadata() : null);
    }

    // ── OuterParallelismAdvisor ─────────────────────────────────────────────

    /**
     * Dynamically adjusts outer graph extraction batch parallelism based on memory pressure.
     * Created per-job and used to gate concurrent batch submissions.
     */
    static class OuterParallelismAdvisor {
        private static final Logger log = LoggerFactory.getLogger(OuterParallelismAdvisor.class);

        private volatile int currentParallelism;
        private final int maxParallelism;
        private final Semaphore concurrencyGate;
        private int consecutiveLowMemoryBatches;
        private long lastRampTime;
        private static final long RAMP_COOLDOWN_MS = 12_000;
        private static final double CRITICAL_THRESHOLD = 0.82;
        private static final double HIGH_THRESHOLD = 0.70;
        private static final double LOW_THRESHOLD = 0.60;
        private static final int RAMP_AFTER_LOW_COUNT = 2;

        OuterParallelismAdvisor(int initialParallelism) {
            this.maxParallelism = Math.max(1, initialParallelism);
            this.currentParallelism = this.maxParallelism;
            this.concurrencyGate = new Semaphore(this.maxParallelism);
            this.lastRampTime = System.currentTimeMillis();
        }

        /**
         * Acquire a permit before submitting a batch. Blocks if the advisor
         * has reduced parallelism and all permits are in use.
         */
        void acquirePermit() throws InterruptedException {
            concurrencyGate.acquire();
        }

        /**
         * Release a permit after a batch completes (call from task finally block).
         */
        void releasePermit() {
            concurrencyGate.release();
        }

        synchronized void afterBatchComplete(long batchMs, double heapPercent) {
            int oldParallelism = currentParallelism;

            if (heapPercent > CRITICAL_THRESHOLD) {
                currentParallelism = 1;
                consecutiveLowMemoryBatches = 0;
                if (oldParallelism != 1) {
                    // Drain excess permits so only 1 batch can run concurrently
                    drainPermits(oldParallelism, 1);
                    log.info("Outer graph parallelism reduced {} -> 1 (reason: heap {}% > critical {}%)",
                            oldParallelism, Math.round(heapPercent * 100), Math.round(CRITICAL_THRESHOLD * 100));
                }
            } else if (heapPercent > HIGH_THRESHOLD) {
                consecutiveLowMemoryBatches = 0;
                // Hold at current level
            } else if (heapPercent < LOW_THRESHOLD) {
                consecutiveLowMemoryBatches++;
                long now = System.currentTimeMillis();
                if (consecutiveLowMemoryBatches >= RAMP_AFTER_LOW_COUNT
                        && now - lastRampTime >= RAMP_COOLDOWN_MS
                        && currentParallelism < maxParallelism) {
                    int newParallelism = Math.min(maxParallelism, currentParallelism + 1);
                    // Release additional permits to allow more concurrency
                    concurrencyGate.release(newParallelism - currentParallelism);
                    currentParallelism = newParallelism;
                    lastRampTime = now;
                    consecutiveLowMemoryBatches = 0;
                    log.info("Outer graph parallelism increased {} -> {} (reason: {} consecutive low-memory batches, heap {}%)",
                            oldParallelism, currentParallelism, RAMP_AFTER_LOW_COUNT, Math.round(heapPercent * 100));
                }
            } else {
                consecutiveLowMemoryBatches = 0;
            }
        }

        private void drainPermits(int from, int to) {
            int toDrain = from - to;
            for (int d = 0; d < toDrain; d++) {
                if (!concurrencyGate.tryAcquire()) break;
            }
        }

        int getCurrentParallelism() { return currentParallelism; }
    }
}
