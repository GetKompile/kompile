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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A work-stealing task queue for distributing pipeline work items across
 * multiple workers. Each worker owns a double-ended deque (deque):
 * <ul>
 *   <li>Local push/pop operates on the <b>front</b> (LIFO for locality)</li>
 *   <li>Steal operates on the <b>back</b> (FIFO — steals coarse-grained work)</li>
 * </ul>
 *
 * <p>When a worker's deque is empty, it selects a random victim and attempts
 * to steal from that victim's deque back. This naturally balances load across
 * heterogeneous pipeline stages (embedding, graph extraction, chunking, etc.)
 * without centralized coordination.</p>
 *
 * <p>Thread safety: each deque is protected by its own {@link ReentrantLock}.
 * Local operations try-lock first for low contention; steal operations always lock.</p>
 *
 * @param <T> the work item type
 */
public class WorkStealingTaskQueue<T> {

    /**
     * Per-worker deque with its own lock.
     */
    private static class WorkerDeque<T> {
        final Deque<T> deque = new ArrayDeque<>();
        final ReentrantLock lock = new ReentrantLock();
        final AtomicLong pushed = new AtomicLong(0);
        final AtomicLong popped = new AtomicLong(0);
        final AtomicLong stolen = new AtomicLong(0);
    }

    private final WorkerDeque<T>[] deques;
    private final int workerCount;

    // Global stats
    private final AtomicLong totalSteals = new AtomicLong(0);
    private final AtomicLong totalStealFailures = new AtomicLong(0);
    private final AtomicLong totalLocalDispatches = new AtomicLong(0);

    @SuppressWarnings("unchecked")
    public WorkStealingTaskQueue(int workerCount) {
        if (workerCount < 1) {
            throw new IllegalArgumentException("workerCount must be >= 1, got " + workerCount);
        }
        this.workerCount = workerCount;
        this.deques = new WorkerDeque[workerCount];
        for (int i = 0; i < workerCount; i++) {
            this.deques[i] = new WorkerDeque<>();
        }
    }

    /**
     * Push a work item onto the front of a worker's deque (local push).
     *
     * @param workerId the worker index (0-based)
     * @param item     the work item
     */
    public void push(int workerId, T item) {
        WorkerDeque<T> wd = deques[workerId];
        wd.lock.lock();
        try {
            wd.deque.addFirst(item);
            wd.pushed.incrementAndGet();
        } finally {
            wd.lock.unlock();
        }
    }

    /**
     * Push a batch of work items onto a worker's deque, distributing evenly
     * across all workers using round-robin for initial load balance.
     *
     * @param items the work items to distribute
     */
    public void pushDistributed(List<T> items) {
        for (int i = 0; i < items.size(); i++) {
            push(i % workerCount, items.get(i));
        }
    }

    /**
     * Pop a work item from the front of the worker's own deque (local pop).
     * Returns null if the deque is empty.
     *
     * @param workerId the worker index
     * @return the work item, or null if empty
     */
    public T popLocal(int workerId) {
        WorkerDeque<T> wd = deques[workerId];
        wd.lock.lock();
        try {
            T item = wd.deque.pollFirst();
            if (item != null) {
                wd.popped.incrementAndGet();
                totalLocalDispatches.incrementAndGet();
            }
            return item;
        } finally {
            wd.lock.unlock();
        }
    }

    /**
     * Try to get work: first from own deque, then by stealing from a random victim.
     * Implements the core work-stealing protocol.
     *
     * @param workerId the worker requesting work
     * @return a work item, or null if all deques are empty
     */
    public T getWork(int workerId) {
        // Try local first
        T item = popLocal(workerId);
        if (item != null) {
            return item;
        }
        // Local deque empty — steal from a random victim
        return steal(workerId);
    }

    /**
     * Steal a work item from a random victim's deque back.
     * Tries up to {@code workerCount} random victims before giving up.
     *
     * @param thiefId the stealing worker's index
     * @return a stolen work item, or null if no victim has work
     */
    public T steal(int thiefId) {
        if (workerCount <= 1) {
            return null;
        }

        // Try each other worker in random order. We do (workerCount - 1)
        // actual attempts on distinct victims, skipping self.
        int realAttempts = 0;
        int maxRealAttempts = workerCount - 1;
        // Guard against infinite loop from bad RNG: cap total iterations.
        // Need enough iterations so that even with unlucky RNG (hitting self),
        // we still attempt every distinct victim at least once.
        int totalIter = 0;
        int maxTotalIter = workerCount * 10;
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        while (realAttempts < maxRealAttempts && totalIter < maxTotalIter) {
            totalIter++;
            int victimId = rng.nextInt(workerCount);
            if (victimId == thiefId) {
                continue; // skip self, don't count as attempt
            }

            realAttempts++;
            WorkerDeque<T> victim = deques[victimId];
            if (victim.lock.tryLock()) {
                try {
                    T item = victim.deque.pollLast(); // steal from back
                    if (item != null) {
                        victim.stolen.incrementAndGet();
                        totalSteals.incrementAndGet();
                        return item;
                    }
                } finally {
                    victim.lock.unlock();
                }
            }
        }

        totalStealFailures.incrementAndGet();
        return null;
    }

    /**
     * Check if all deques are empty (termination check).
     */
    public boolean isEmpty() {
        for (WorkerDeque<T> wd : deques) {
            wd.lock.lock();
            try {
                if (!wd.deque.isEmpty()) {
                    return false;
                }
            } finally {
                wd.lock.unlock();
            }
        }
        return true;
    }

    /**
     * Total items across all deques.
     */
    public int totalSize() {
        int total = 0;
        for (WorkerDeque<T> wd : deques) {
            wd.lock.lock();
            try {
                total += wd.deque.size();
            } finally {
                wd.lock.unlock();
            }
        }
        return total;
    }

    /**
     * Get the size of a specific worker's deque.
     */
    public int workerQueueSize(int workerId) {
        WorkerDeque<T> wd = deques[workerId];
        wd.lock.lock();
        try {
            return wd.deque.size();
        } finally {
            wd.lock.unlock();
        }
    }

    /**
     * Compute the imbalance ratio: max queue depth / max(1, min queue depth).
     * Returns value * 100 for integer precision (100 = perfectly balanced).
     */
    public long imbalanceRatioX100() {
        int min = Integer.MAX_VALUE;
        int max = 0;
        for (WorkerDeque<T> wd : deques) {
            wd.lock.lock();
            try {
                int sz = wd.deque.size();
                min = Math.min(min, sz);
                max = Math.max(max, sz);
            } finally {
                wd.lock.unlock();
            }
        }
        if (max == 0) return 100L;
        return (max * 100L) / Math.max(1, min);
    }

    // ---- Stats accessors ----

    public long getTotalSteals() {
        return totalSteals.get();
    }

    public long getTotalStealFailures() {
        return totalStealFailures.get();
    }

    public long getTotalLocalDispatches() {
        return totalLocalDispatches.get();
    }

    public int getWorkerCount() {
        return workerCount;
    }

    /**
     * Publish current stats to a {@link UnifiedCrawlJob} for UI visibility.
     */
    public void publishStats(UnifiedCrawlJob job) {
        job.getWorkStealCount().set(totalSteals.get());
        job.getWorkStealFailures().set(totalStealFailures.get());
        job.getLocalDispatchCount().set(totalLocalDispatches.get());
        job.getWorkImbalanceRatioX100().set(imbalanceRatioX100());
    }
}
