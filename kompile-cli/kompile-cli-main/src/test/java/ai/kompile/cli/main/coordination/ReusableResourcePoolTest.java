package ai.kompile.cli.main.coordination;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ReusableResourcePoolTest {
    private final List<ReusableResourcePool<String, TestResource>> pools = new ArrayList<>();

    @AfterEach
    void closePools() {
        pools.forEach(ReusableResourcePool::close);
    }

    @Test
    void sequentialLeasesReuseOneWarmResource() throws Exception {
        AtomicInteger creates = new AtomicInteger();
        ReusableResourcePool<String, TestResource> pool = pool(60_000, 1);

        TestResource first;
        try (var lease = pool.acquire("model", () -> resource(creates), 1_000)) {
            first = lease.resource();
        }
        try (var lease = pool.acquire("model", () -> resource(creates), 1_000)) {
            assertSame(first, lease.resource());
        }

        assertEquals(1, creates.get());
        assertFalse(first.closed);
    }

    @Test
    void concurrentAcquisitionCollapsesToOneCreation() throws Exception {
        AtomicInteger creates = new AtomicInteger();
        ReusableResourcePool<String, TestResource> pool = pool(60_000, 1);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<CompletableFuture<ReusableResourcePool.Lease<TestResource>>> futures =
                    new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await();
                        return pool.acquire("model", () -> {
                            Thread.sleep(50);
                            return resource(creates);
                        }, 2_000);
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                }, executor));
            }
            start.countDown();
            List<ReusableResourcePool.Lease<TestResource>> leases = futures.stream()
                    .map(CompletableFuture::join)
                    .toList();

            assertEquals(1, creates.get());
            TestResource shared = leases.get(0).resource();
            assertTrue(leases.stream().allMatch(lease -> lease.resource() == shared));
            leases.forEach(ReusableResourcePool.Lease::close);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void boundedPoolQueuesInsteadOfOverAllocatingAndEvictsIdleModel() throws Exception {
        AtomicInteger creates = new AtomicInteger();
        ReusableResourcePool<String, TestResource> pool = pool(60_000, 1);
        ReusableResourcePool.Lease<TestResource> first =
                pool.acquire("model-a", () -> resource(creates), 1_000);
        TestResource firstResource = first.resource();

        CompletableFuture<ReusableResourcePool.Lease<TestResource>> waiting =
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return pool.acquire("model-b", () -> resource(creates), 2_000);
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                });
        Thread.sleep(100);
        assertEquals(1, creates.get(), "a leased GPU model must hold the only capacity slot");

        first.close();
        try (var second = waiting.get(2, TimeUnit.SECONDS)) {
            assertEquals(2, creates.get());
            assertTrue(firstResource.closed, "an incompatible idle model must be evicted");
            assertEquals(1, pool.pooledCount());
            assertEquals(2, second.resource().id);
        }
    }

    @Test
    void distinctKeysRemainIsolatedWhenCapacityAllowsThem() throws Exception {
        AtomicInteger creates = new AtomicInteger();
        ReusableResourcePool<String, TestResource> pool = pool(60_000, 2);
        try (var first = pool.acquire("a", () -> resource(creates), 1_000);
             var second = pool.acquire("b", () -> resource(creates), 1_000)) {
            assertEquals(2, creates.get());
            assertFalse(first.resource() == second.resource());
        }
    }

    @Test
    void failedCreationDoesNotPoisonTheKey() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        ReusableResourcePool<String, TestResource> pool = pool(60_000, 1);
        try {
            pool.acquire("model", () -> {
                attempts.incrementAndGet();
                throw new IllegalStateException("boom");
            }, 1_000);
            fail("expected the first creation to fail");
        } catch (IllegalStateException expected) {
            assertEquals("boom", expected.getMessage());
        }

        try (var lease = pool.acquire("model", () -> resource(attempts), 1_000)) {
            assertTrue(lease.isHealthy());
        }
        assertEquals(2, attempts.get());
    }

    @Test
    void idleResourceIsClosedAndRemoved() throws Exception {
        AtomicInteger creates = new AtomicInteger();
        ReusableResourcePool<String, TestResource> pool = pool(25, 1);
        TestResource resource;
        try (var lease = pool.acquire("model", () -> resource(creates), 1_000)) {
            resource = lease.resource();
        }

        awaitTrue(() -> resource.closed && pool.pooledCount() == 0, 2_000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"model", "different-model"})
    void idleTeardownRetainsCapacityUntilTheOldResourceHasStopped(String nextKey)
            throws Exception {
        AtomicInteger creates = new AtomicInteger();
        CountDownLatch closing = new CountDownLatch(1);
        CountDownLatch allowClose = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(1);
        CountDownLatch replacementStarted = new CountDownLatch(1);
        ReusableResourcePool<String, TestResource> pool = new ReusableResourcePool<>(
                "slow-close-pool", () -> 25L, () -> 1, resource -> resource.alive,
                resource -> {
                    closing.countDown();
                    if (!allowClose.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("teardown was not released");
                    }
                    resource.close();
                });
        pools.add(pool);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            TestResource old;
            try (var lease = pool.acquire("model", () -> resource(creates), 1_000)) {
                old = lease.resource();
            }
            assertTrue(closing.await(2, TimeUnit.SECONDS));
            var waiting = executor.submit(() -> {
                attempted.countDown();
                return pool.acquire(nextKey, () -> {
                    replacementStarted.countDown();
                    return resource(creates);
                }, 2_000);
            });
            assertTrue(attempted.await(1, TimeUnit.SECONDS));
            assertFalse(replacementStarted.await(100, TimeUnit.MILLISECONDS),
                    "a closing model must still occupy its resident capacity slot");
            assertEquals(1, pool.pooledCount());
            assertEquals(1, creates.get());
            allowClose.countDown();
            try (var replacement = waiting.get(2, TimeUnit.SECONDS)) {
                assertTrue(old.closed);
                assertEquals(2, replacement.resource().id);
            }
        } finally {
            allowClose.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cleanupWaitsForAnIdleTeardownAlreadyInProgress(boolean closePool) throws Exception {
        AtomicInteger closes = new AtomicInteger();
        CountDownLatch closing = new CountDownLatch(1);
        CountDownLatch allowClose = new CountDownLatch(1);
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        CountDownLatch cleanupFinished = new CountDownLatch(1);
        ReusableResourcePool<String, TestResource> pool = new ReusableResourcePool<>(
                "shutdown-pool", () -> 25L, () -> 1, resource -> resource.alive,
                resource -> {
                    closes.incrementAndGet();
                    closing.countDown();
                    if (!allowClose.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("teardown was not released");
                    }
                    resource.close();
                });
        pools.add(pool);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        TestResource resource = new TestResource(1);
        try {
            try (var lease = pool.acquire("model", () -> resource, 1_000)) {
                assertSame(resource, lease.resource());
            }
            assertTrue(closing.await(2, TimeUnit.SECONDS));
            var cleanup = executor.submit(() -> {
                cleanupStarted.countDown();
                if (closePool) {
                    pool.close();
                } else {
                    pool.clear();
                }
                cleanupFinished.countDown();
            });
            assertTrue(cleanupStarted.await(1, TimeUnit.SECONDS));
            assertFalse(cleanupFinished.await(100, TimeUnit.MILLISECONDS),
                    "shutdown must wait for the daemon reaper to finish stopping its child");
            assertFalse(resource.closed);
            allowClose.countDown();
            cleanup.get(2, TimeUnit.SECONDS);
            assertTrue(resource.closed);
            assertEquals(1, closes.get(), "a resource must not be closed twice");
            assertEquals(0, pool.pooledCount());
        } finally {
            allowClose.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void idleDeadlineRestartsAfterAResourceIsReused() throws Exception {
        AtomicInteger creates = new AtomicInteger();
        ReusableResourcePool<String, TestResource> pool = pool(500, 1);
        TestResource resource;
        try (var first = pool.acquire("model", () -> resource(creates), 1_000)) {
            resource = first.resource();
        }

        Thread.sleep(300);
        try (var second = pool.acquire("model", () -> resource(creates), 1_000)) {
            assertSame(resource, second.resource());
            Thread.sleep(300);
            assertFalse(resource.closed, "an active reuse must invalidate the previous deadline");
        }

        Thread.sleep(300);
        assertFalse(resource.closed, "the final release must receive a complete idle window");
        awaitTrue(() -> resource.closed && pool.pooledCount() == 0, 2_000);
        assertEquals(1, creates.get());
    }

    @Test
    void cancelledIdleDeadlinesAreRemovedFromTheSchedulerQueue() throws Exception {
        AtomicInteger creates = new AtomicInteger();
        ReusableResourcePool<String, TestResource> pool = pool(60_000, 1);

        for (int i = 0; i < 20; i++) {
            try (var lease = pool.acquire("model", () -> resource(creates), 1_000)) {
                assertEquals(0, pool.queuedReapTasksForTests());
                lease.resource();
            }
            assertEquals(1, pool.queuedReapTasksForTests());
        }

        assertEquals(1, creates.get());
    }

    @Test
    void zeroIdleWindowClosesImmediately() throws Exception {
        AtomicInteger creates = new AtomicInteger();
        ReusableResourcePool<String, TestResource> pool = pool(0, 1);
        TestResource resource;
        try (var lease = pool.acquire("model", () -> resource(creates), 1_000)) {
            resource = lease.resource();
        }

        awaitTrue(() -> resource.closed, 2_000);
    }

    private ReusableResourcePool<String, TestResource> pool(long idleMillis, int maximum) {
        ReusableResourcePool<String, TestResource> pool = new ReusableResourcePool<>(
                "test-resource-pool",
                () -> idleMillis,
                () -> maximum,
                resource -> resource.alive && !resource.closed,
                TestResource::close);
        pools.add(pool);
        return pool;
    }

    private static TestResource resource(AtomicInteger creates) {
        return new TestResource(creates.incrementAndGet());
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        fail("condition was not satisfied before timeout");
    }

    private static final class TestResource {
        private final int id;
        private volatile boolean alive = true;
        private volatile boolean closed;

        private TestResource(int id) {
            this.id = id;
        }

        private void close() {
            alive = false;
            closed = true;
        }
    }
}
