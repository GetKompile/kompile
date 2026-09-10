package ai.kompile.cli.main.coordination;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ActivityWaitRegistryTest {
    @Test
    void expiryNotifiesWithoutClaimingReadiness() throws Exception {
        var time = new java.util.concurrent.atomic.AtomicReference<>(java.time.Instant.now());
        try (var registry = new ActivityWaitRegistry(time::get)) {
            AtomicInteger notifications = new AtomicInteger();
            registry.setWakeHandler("owner", message -> {
                assertTrue(message.contains("EXPIRED"));
                notifications.incrementAndGet();
            });
            var watch = registry.watch("owner", "key", "BUILD", "build", () -> false);
            time.set(time.get().plus(java.time.Duration.ofHours(24)));
            registry.checkNow();
            registry.checkNow();
            assertEquals(1, notifications.get());
            assertEquals(ActivityWaitRegistry.State.EXPIRED,
                    registry.await("owner", watch.waitId(), 1, () -> false).state());
        }
    }

    @Test
    void daemonChecksReadinessWhileAgentIsIdle() throws Exception {
        try (var registry = new ActivityWaitRegistry()) {
            CountDownLatch notification = new CountDownLatch(1);
            registry.setWakeHandler("owner", ignored -> notification.countDown());
            registry.watch("owner", "key", "BUILD", "build", () -> true);
            assertTrue(notification.await(8, TimeUnit.SECONDS), "host daemon must work without tool polling");
        }
    }

    @Test
    void heldToolResponseUnblocksWithoutModelPolling() throws Exception {
        try (var registry = new ActivityWaitRegistry()) {
            AtomicBoolean ready = new AtomicBoolean();
            var watch = registry.watch("owner", "key", "BUILD", "mvn test", ready::get);
            var worker = Executors.newSingleThreadExecutor();
            CountDownLatch started = new CountDownLatch(1);
            try {
                var result = worker.submit(() -> {
                    started.countDown();
                    return registry.await("owner", watch.waitId(), 5_000, () -> false);
                });
                assertTrue(started.await(1, TimeUnit.SECONDS));
                registry.checkNow();
                assertFalse(result.isDone());
                ready.set(true);
                registry.checkNow();
                assertEquals(ActivityWaitRegistry.State.READY, result.get(1, TimeUnit.SECONDS).state());
            } finally {
                worker.shutdownNow();
            }
        }
    }

    @Test
    void cancellingUnusedWatchImmediatelyFreesBoundedCapacity() {
        try (var registry = new ActivityWaitRegistry()) {
            String first = null;
            for (int i = 0; i < 128; i++) {
                var watch = registry.watch("owner", "key-" + i, "BUILD", "build", () -> false);
                if (first == null) first = watch.waitId();
            }
            assertThrows(IllegalStateException.class,
                    () -> registry.watch("owner", "overflow", "BUILD", "build", () -> false));
            registry.cancel("owner", first);
            assertNotNull(registry.watch("owner", "replacement", "BUILD", "build", () -> false));
            assertEquals(128, registry.list("owner").size());
        }
    }

    @Test
    void timeoutPreservesWatchAndCancelStopsNotification() throws Exception {
        try (var registry = new ActivityWaitRegistry()) {
            AtomicInteger notifications = new AtomicInteger();
            AtomicBoolean ready = new AtomicBoolean();
            registry.setWakeHandler("owner", ignored -> notifications.incrementAndGet());
            var watch = registry.watch("owner", "key", "TEST", "tests", ready::get);
            assertEquals(ActivityWaitRegistry.State.WAITING,
                    registry.await("owner", watch.waitId(), 5, () -> false).state());
            assertEquals(watch.waitId(), registry.watch("owner", "key", "TEST", "tests", ready::get).waitId());
            registry.cancel("owner", watch.waitId());
            ready.set(true);
            registry.checkNow();
            assertEquals(0, notifications.get());
            assertEquals(ActivityWaitRegistry.State.CANCELLED, registry.get("owner", watch.waitId()).state());
        }
    }

    @Test
    void abortedWaitAndShutdownCancelPendingWork() throws Exception {
        var registry = new ActivityWaitRegistry();
        try {
            var watch = registry.watch("owner", "key", "BUILD", "build", () -> false);
            assertEquals(ActivityWaitRegistry.State.CANCELLED,
                    registry.await("owner", watch.waitId(), 1_000, () -> true).state());
            var pending = registry.watch("owner", "second", "BUILD", "build", () -> false);
            registry.close();
            assertEquals(ActivityWaitRegistry.State.CANCELLED, registry.get("owner", pending.waitId()).state());
            assertThrows(IllegalStateException.class,
                    () -> registry.watch("owner", "third", "BUILD", "build", () -> true));
        } finally {
            registry.close();
        }
    }

    @Test
    void interruptCancelsWatch() throws Exception {
        try (var registry = new ActivityWaitRegistry()) {
            var watch = registry.watch("owner", "key", "BUILD", "build", () -> false);
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedException.class,
                        () -> registry.await("owner", watch.waitId(), 5_000, () -> false));
            } finally {
                Thread.interrupted();
            }
            assertEquals(ActivityWaitRegistry.State.CANCELLED, registry.get("owner", watch.waitId()).state());
        }
    }

    @Test
    void sessionsCannotObserveOrCancelEachOthersWatches() {
        try (var registry = new ActivityWaitRegistry()) {
            var watch = registry.watch("owner", "key", "BUILD", "build", () -> false);
            registry.setWakeHandler("other", ignored -> fail("wrong session"));
            assertFalse(watch.wakeSupported());
            assertTrue(registry.list("other").isEmpty());
            assertThrows(IllegalArgumentException.class, () -> registry.get("other", watch.waitId()));
            assertThrows(IllegalArgumentException.class, () -> registry.cancel("other", watch.waitId()));
        }
    }

    @Test
    void failingProbeIsClosedAndFailedDeliveryIsRetriedOnceReady() {
        try (var registry = new ActivityWaitRegistry()) {
            AtomicBoolean broken = new AtomicBoolean(true);
            AtomicInteger attempts = new AtomicInteger();
            registry.setWakeHandler("owner", ignored -> {
                if (attempts.incrementAndGet() == 1) throw new IllegalStateException("transport unavailable");
            });
            var watch = registry.watch("owner", "key", "BUILD", "build", () -> {
                if (broken.get()) throw new IllegalStateException("cannot inspect lane");
                return true;
            });
            registry.checkNow();
            assertEquals(ActivityWaitRegistry.State.WAITING, registry.get("owner", watch.waitId()).state());
            assertTrue(registry.get("owner", watch.waitId()).reason().contains("unavailable"));
            assertEquals(0, attempts.get());
            broken.set(false);
            registry.checkNow();
            registry.checkNow();
            registry.checkNow();
            assertEquals(2, attempts.get());
        }
    }

    @Test
    void cancelDuringSlowProbeCannotRaceIntoWakeup() throws Exception {
        try (var registry = new ActivityWaitRegistry()) {
            CountDownLatch probing = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger wakes = new AtomicInteger();
            registry.setWakeHandler("owner", ignored -> wakes.incrementAndGet());
            var watch = registry.watch("owner", "key", "BUILD", "build", () -> {
                probing.countDown();
                try { release.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return true;
            });
            Thread checker = new Thread(registry::checkNow);
            checker.start();
            try {
                assertTrue(probing.await(1, TimeUnit.SECONDS));
                registry.cancel("owner", watch.waitId());
            } finally {
                release.countDown();
                checker.join(2_000);
            }
            assertFalse(checker.isAlive());
            assertEquals(0, wakes.get());
        }
    }
}
