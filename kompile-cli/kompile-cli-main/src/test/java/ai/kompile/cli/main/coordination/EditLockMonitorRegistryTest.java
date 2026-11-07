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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ai.kompile.cli.main.coordination;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class EditLockMonitorRegistryTest {

    /** Controllable active-lock ledger: path -> lockId. */
    private final Map<String, String> ledger = new LinkedHashMap<>();
    private final AtomicReference<Instant> now =
            new AtomicReference<>(Instant.now());

    private EditLockMonitorRegistry newRegistry() {
        return new EditLockMonitorRegistry(() -> Map.copyOf(ledger), now::get);
    }

    @Test
    void releasedLockWakesTheRegisteredSession() {
        try (EditLockMonitorRegistry registry = newRegistry()) {
            AtomicInteger woken = new AtomicInteger();
            AtomicReference<String> lastMessage = new AtomicReference<>();
            registry.setWakeHandler("s1", message -> {
                assertTrue(message.contains("RELEASED"));
                assertTrue(message.contains("/tmp/a.java"));
                woken.incrementAndGet();
                lastMessage.set(message);
            });
            ledger.put("/tmp/a.java", "lock-1");
            var monitor = registry.watch("s1", null, "/tmp/a.java", "retry my edit");
            assertEquals(EditLockMonitorRegistry.State.WAITING, monitor.state());

            registry.checkNow();
            assertEquals(0, woken.get(), "still held: no wake");

            ledger.remove("/tmp/a.java");
            registry.checkNow();
            assertEquals(1, woken.get(), "release must wake the registered session");
            assertEquals(EditLockMonitorRegistry.State.RELEASED,
                    registry.get("s1", monitor.monitorId()).state());
            assertTrue(lastMessage.get().contains("retry my edit"));
            assertTrue(monitor.wakeSupported());
        }
    }

    @Test
    void lockIdTargetFiresOnlyWhenThatExactLockIsGone() {
        try (EditLockMonitorRegistry registry = newRegistry()) {
            AtomicInteger woken = new AtomicInteger();
            registry.setWakeHandler("s1", ignored -> woken.incrementAndGet());
            ledger.put("/tmp/a.java", "lock-1");
            var watched = registry.watch("s1", "lock-1", "/tmp/a.java", "");
            assertEquals(EditLockMonitorRegistry.State.WAITING, watched.state());

            // Another agent takes the file with a new lock; the WATCHED lock (lock-1) has
            // left the ledger, so the lock-id monitor fires even though the file is busy.
            ledger.put("/tmp/a.java", "lock-2");
            registry.checkNow();
            assertEquals(1, woken.get());

            // A monitor on the NEW lock keeps waiting while it holds the file.
            var current = registry.watch("s1", "lock-2", "/tmp/a.java", "");
            assertEquals(EditLockMonitorRegistry.State.WAITING, current.state());
            registry.checkNow();
            assertEquals(1, woken.get(), "a held lock never wakes its monitor");
            ledger.remove("/tmp/a.java");
            registry.checkNow();
            assertEquals(2, woken.get());
        }
    }

    @Test
    void pathTargetWaitsUntilNoLockHoldsTheFile() {
        try (EditLockMonitorRegistry registry = newRegistry()) {
            AtomicInteger woken = new AtomicInteger();
            registry.setWakeHandler("s1", ignored -> woken.incrementAndGet());
            ledger.put("/tmp/a.java", "lock-1");
            registry.watch("s1", null, "/tmp/a.java", "");

            ledger.put("/tmp/a.java", "lock-2");
            registry.checkNow();
            assertEquals(0, woken.get(),
                    "re-locked by another agent: the file is still busy, no wake");

            ledger.remove("/tmp/a.java");
            registry.checkNow();
            assertEquals(1, woken.get(), "the file is free now");
        }
    }

    @Test
    void alreadyFreeTargetReportsReleasedWithoutInstallingAMonitor() {
        try (EditLockMonitorRegistry registry = newRegistry()) {
            AtomicInteger woken = new AtomicInteger();
            registry.setWakeHandler("s1", ignored -> woken.incrementAndGet());
            var monitor = registry.watch("s1", null, "/tmp/free.java", "");
            assertEquals(EditLockMonitorRegistry.State.RELEASED, monitor.state());
            registry.checkNow();
            assertEquals(0, woken.get(), "no daemon wake for a target already met at registration");
            assertTrue(registry.list("s1").isEmpty(),
                    "an already-met target must not linger as a monitor");
        }
    }

    @Test
    void unreadableLedgerFailsClosedAndKeepsWaiting() {
        AtomicReference<Boolean> readable = new AtomicReference<>(Boolean.TRUE);
        try (EditLockMonitorRegistry registry = new EditLockMonitorRegistry(
                () -> Boolean.TRUE.equals(readable.get()) ? Map.copyOf(ledger) : null, now::get)) {
            AtomicInteger woken = new AtomicInteger();
            registry.setWakeHandler("s1", ignored -> woken.incrementAndGet());
            ledger.put("/tmp/a.java", "lock-1");
            var monitor = registry.watch("s1", null, "/tmp/a.java", "");
            assertEquals(EditLockMonitorRegistry.State.WAITING, monitor.state());

            // Ledger goes unreadable: not a release, the monitor keeps waiting.
            readable.set(Boolean.FALSE);
            registry.checkNow();
            assertEquals(0, woken.get(), "unreadable ledger is not a release");
            assertEquals(EditLockMonitorRegistry.State.WAITING,
                    registry.get("s1", monitor.monitorId()).state());

            // And registration against an unreadable ledger refuses to wait blindly.
            var rejected = registry.watch("s1", null, "/tmp/b.java", "");
            assertEquals(EditLockMonitorRegistry.State.CANCELLED, rejected.state());

            // Readable again, lock gone: the original monitor still fires.
            readable.set(Boolean.TRUE);
            ledger.remove("/tmp/a.java");
            registry.checkNow();
            assertEquals(1, woken.get());
            assertEquals(EditLockMonitorRegistry.State.RELEASED,
                    registry.get("s1", monitor.monitorId()).state());
        }
    }

    @Test
    void throwingLedgerFailsClosedAndKeepsWaiting() {
        try (EditLockMonitorRegistry registry =
                     new EditLockMonitorRegistry(() -> { throw new IllegalStateException("busy"); },
                             now::get)) {
            // The probe swallows the failure: registration fails closed, nothing throws,
            // and no monitor is installed to wait on a ledger that cannot be read.
            var rejected = registry.watch("s1", null, "/tmp/a.java", "");
            assertEquals(EditLockMonitorRegistry.State.CANCELLED, rejected.state());
            assertDoesNotThrow(registry::checkNow);
        }
    }

    @Test
    void monitorExpiresAfter24HoursOfHeldLock() {
        try (EditLockMonitorRegistry registry = newRegistry()) {
            AtomicInteger woken = new AtomicInteger();
            registry.setWakeHandler("s1", message -> {
                assertTrue(message.contains("EXPIRED"));
                woken.incrementAndGet();
            });
            ledger.put("/tmp/a.java", "lock-1");
            var monitor = registry.watch("s1", null, "/tmp/a.java", "");
            now.set(now.get().plus(Duration.ofHours(24)));
            registry.checkNow();
            registry.checkNow();
            assertEquals(1, woken.get());
            assertEquals(EditLockMonitorRegistry.State.EXPIRED,
                    registry.get("s1", monitor.monitorId()).state());
        }
    }

    @Test
    void wakeHandlerThrowingIsRetriedOnNextTick() {
        try (EditLockMonitorRegistry registry = newRegistry()) {
            AtomicInteger failures = new AtomicInteger();
            registry.setWakeHandler("s1", ignored -> {
                if (failures.incrementAndGet() == 1) throw new IllegalStateException("chat closed");
            });
            ledger.put("/tmp/a.java", "lock-1");
            var monitor = registry.watch("s1", null, "/tmp/a.java", "");
            ledger.remove("/tmp/a.java");
            registry.checkNow();
            // State is already RELEASED (one-shot), but the failed delivery means the
            // wake-up has not been consumed yet.
            assertEquals(EditLockMonitorRegistry.State.RELEASED,
                    registry.get("s1", monitor.monitorId()).state());
            assertEquals(1, failures.get(), "still exactly one delivery attempt");

            registry.checkNow();
            assertEquals(EditLockMonitorRegistry.State.RELEASED,
                    registry.get("s1", monitor.monitorId()).state(),
                    "second tick retries delivery and marks delivered");
            assertEquals(2, failures.get(), "delivery retried once");
        }
    }

    @Test
    void duplicateWaitingWatchForSameTargetIsIdempotent() {
        try (EditLockMonitorRegistry registry = newRegistry()) {
            ledger.put("/tmp/a.java", "lock-1");
            var first = registry.watch("s1", null, "/tmp/a.java", "");
            var second = registry.watch("s1", null, "/tmp/a.java", "");
            assertEquals(first.monitorId(), second.monitorId());
        }
    }

    @Test
    void cancelFreesCapacityAndIsSessionScoped() {
        try (EditLockMonitorRegistry registry = newRegistry()) {
            ledger.put("/tmp/a.java", "lock-1");
            var mine = registry.watch("s1", null, "/tmp/a.java", "");
            assertThrows(IllegalArgumentException.class,
                    () -> registry.cancel("s2", mine.monitorId()),
                    "another session must not cancel my monitor");
            registry.cancel("s1", mine.monitorId());
            assertEquals(EditLockMonitorRegistry.State.CANCELLED,
                    registry.get("s1", mine.monitorId()).state());
        }
    }

    @Test
    void capacityIsBoundedAndReclaimedFromDeliveredMonitors() {
        try (EditLockMonitorRegistry registry = newRegistry()) {
            AtomicInteger woken = new AtomicInteger();
            registry.setWakeHandler("s1", ignored -> woken.incrementAndGet());
            String first = null;
            for (int i = 0; i < 128; i++) {
                String path = "/tmp/locked-" + i + ".java";
                ledger.put(path, "lock-" + i);
                var monitor = registry.watch("s1", null, path, "");
                if (first == null) first = monitor.monitorId();
            }
            assertThrows(IllegalStateException.class,
                    () -> {
                        // A genuinely LOCKED 129th target, so registration reaches the cap
                        // check instead of short-circuiting to RELEASED (free target).
                        ledger.put("/tmp/one-too-many.java", "lock-new");
                        registry.watch("s1", null, "/tmp/one-too-many.java", "");
                    });
            // Free every watched file, deliver the wake-ups, age past the reclaim window.
            ledger.clear();
            registry.checkNow();
            assertEquals(128, woken.get());
            now.set(now.get().plus(Duration.ofHours(1)));
            // Registration must reclaim the aged delivered monitors and install the new one.
            ledger.put("/tmp/one-too-many.java", "lock-new");
            var replacement = registry.watch("s1", null, "/tmp/one-too-many.java", "");
            assertEquals(EditLockMonitorRegistry.State.WAITING, replacement.state(),
                    "delivered monitors age out of the cap");
        }
    }

    @Test
    void heldAwaitReturnsAsSoonAsTheLockIsReleased() throws Exception {
        try (EditLockMonitorRegistry registry = newRegistry()) {
            ledger.put("/tmp/a.java", "lock-1");
            var monitor = registry.watch("s1", null, "/tmp/a.java", "");
            var worker = new Thread(() -> {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                }
                ledger.remove("/tmp/a.java");
                registry.checkNow();
            });
            worker.start();
            var result = registry.await("s1", monitor.monitorId(), 5_000, () -> false);
            worker.join(5_000);
            assertEquals(EditLockMonitorRegistry.State.RELEASED, result.state());
        }
    }

    @Test
    void closeCancelsWaitingMonitorsAndRejectsNewWatches() {
        EditLockMonitorRegistry registry = newRegistry();
        ledger.put("/tmp/a.java", "lock-1");
        var monitor = registry.watch("s1", null, "/tmp/a.java", "");
        registry.close();
        assertEquals(EditLockMonitorRegistry.State.CANCELLED,
                registry.get("s1", monitor.monitorId()).state());
        assertThrows(IllegalStateException.class,
                () -> registry.watch("s1", null, "/tmp/a.java", ""));
    }
}
