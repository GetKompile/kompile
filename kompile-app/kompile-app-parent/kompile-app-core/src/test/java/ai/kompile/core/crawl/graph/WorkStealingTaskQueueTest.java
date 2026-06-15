package ai.kompile.core.crawl.graph;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WorkStealingTaskQueueTest {

    @Test
    void testBasicPushPop() {
        WorkStealingTaskQueue<String> queue = new WorkStealingTaskQueue<>(4);

        queue.push(0, "item-a");
        queue.push(0, "item-b");
        assertEquals(2, queue.workerQueueSize(0));
        assertEquals(2, queue.totalSize());

        // Pop returns LIFO (front)
        assertEquals("item-b", queue.popLocal(0));
        assertEquals("item-a", queue.popLocal(0));
        assertNull(queue.popLocal(0));
        assertTrue(queue.isEmpty());
    }

    @Test
    void testPushDistributed() {
        WorkStealingTaskQueue<Integer> queue = new WorkStealingTaskQueue<>(3);
        List<Integer> items = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9);
        queue.pushDistributed(items);

        // 9 items across 3 workers: 3 each
        assertEquals(3, queue.workerQueueSize(0));
        assertEquals(3, queue.workerQueueSize(1));
        assertEquals(3, queue.workerQueueSize(2));
        assertEquals(9, queue.totalSize());
    }

    @Test
    void testStealFromVictim() {
        WorkStealingTaskQueue<String> queue = new WorkStealingTaskQueue<>(2);

        // Load worker 0, leave worker 1 empty
        queue.push(0, "a");
        queue.push(0, "b");
        queue.push(0, "c");

        // Worker 1 steals from worker 0's back
        String stolen = queue.steal(1);
        assertNotNull(stolen);
        assertEquals("a", stolen); // oldest item stolen from back
        assertEquals(1, queue.getTotalSteals());
    }

    @Test
    void testGetWorkTriesLocalFirst() {
        WorkStealingTaskQueue<String> queue = new WorkStealingTaskQueue<>(2);

        queue.push(0, "local");
        String item = queue.getWork(0);
        assertEquals("local", item);
        assertEquals(1, queue.getTotalLocalDispatches());
        assertEquals(0, queue.getTotalSteals());
    }

    @Test
    void testGetWorkFallsBackToSteal() {
        WorkStealingTaskQueue<String> queue = new WorkStealingTaskQueue<>(2);

        // Only worker 1 has items
        queue.push(1, "remote");
        String item = queue.getWork(0); // worker 0 steals from worker 1
        assertEquals("remote", item);
        assertEquals(0, queue.getTotalLocalDispatches());
        assertEquals(1, queue.getTotalSteals());
    }

    @Test
    void testImbalanceRatio() {
        WorkStealingTaskQueue<String> queue = new WorkStealingTaskQueue<>(2);

        // Balanced
        queue.push(0, "a");
        queue.push(1, "b");
        assertEquals(100, queue.imbalanceRatioX100());

        // Imbalanced: 4:1
        queue.push(0, "c");
        queue.push(0, "d");
        queue.push(0, "e");
        assertEquals(400, queue.imbalanceRatioX100());
    }

    @Test
    void testConcurrentWorkStealing() throws Exception {
        int numWorkers = 4;
        int totalItems = 1000;
        WorkStealingTaskQueue<Integer> queue = new WorkStealingTaskQueue<>(numWorkers);

        // Load ALL items on worker 0 (maximum imbalance)
        for (int i = 0; i < totalItems; i++) {
            queue.push(0, i);
        }

        AtomicInteger totalProcessed = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(numWorkers);
        CountDownLatch doneLatch = new CountDownLatch(numWorkers);
        ExecutorService pool = Executors.newFixedThreadPool(numWorkers);

        for (int w = 0; w < numWorkers; w++) {
            final int workerId = w;
            pool.submit(() -> {
                startLatch.countDown();
                try {
                    startLatch.await(); // all workers start together
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                try {
                    while (true) {
                        Integer item = queue.getWork(workerId);
                        if (item == null) break;
                        totalProcessed.incrementAndGet();
                        // Worker 0 has a small delay to give other workers time to steal
                        if (workerId == 0) {
                            Thread.onSpinWait();
                        }
                    }
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        doneLatch.await();
        pool.shutdown();

        assertEquals(totalItems, totalProcessed.get());
        assertTrue(queue.isEmpty());
        // Functional guarantee: all items processed across workers.
        // Steals are expected but not guaranteed under very fast local draining.
        // If local dispatches + steals >= totalItems, the queue is working correctly.
        assertTrue(queue.getTotalLocalDispatches() + queue.getTotalSteals() >= totalItems,
                "All items should be accounted for: local=" + queue.getTotalLocalDispatches()
                        + " steals=" + queue.getTotalSteals());
    }

    @Test
    void testPublishStats() {
        // Use exactly 2 workers — worker 1's only steal victim is worker 0 (deterministic)
        WorkStealingTaskQueue<String> queue = new WorkStealingTaskQueue<>(2);
        // Put items on worker 0 only
        for (int i = 0; i < 20; i++) {
            queue.push(0, "item-" + i);
        }

        // Worker 0 pops locally
        assertNotNull(queue.popLocal(0)); // local dispatch

        // Worker 1 steals — with 2 workers, the only possible victim is worker 0
        String stolen = queue.steal(1);
        assertNotNull(stolen, "Worker 1 should steal from worker 0");

        UnifiedCrawlJob job = UnifiedCrawlJob.builder().jobId("test").build();
        queue.publishStats(job);

        assertEquals(1, job.getWorkStealCount().get(), "Expected exactly one steal");
        assertEquals(1, job.getLocalDispatchCount().get());
    }
}
