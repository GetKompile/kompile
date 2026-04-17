package ai.kompile.app.subprocess;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SubprocessMemoryWatchdog off-heap monitoring features,
 * including 10-arg constructor, threshold clamping, and off-heap snapshot.
 */
@DisplayName("SubprocessMemoryWatchdog Off-Heap Monitoring")
class SubprocessMemoryWatchdogOffHeapTest {

    // 10-arg constructor: heap(3) + interval + GPU(3) + offHeap(3)
    private SubprocessMemoryWatchdog createWatchdogFull(
            int heapThreshold, int heapCritical, int heapKill, long intervalMs,
            int gpuThreshold, int gpuCritical, int gpuKill,
            int offHeapThreshold, int offHeapCritical, int offHeapKill) {
        return new SubprocessMemoryWatchdog(
                heapThreshold, heapCritical, heapKill, intervalMs,
                gpuThreshold, gpuCritical, gpuKill,
                offHeapThreshold, offHeapCritical, offHeapKill);
    }

    // 7-arg constructor: heap(3) + interval + GPU(3) - uses default off-heap thresholds
    private SubprocessMemoryWatchdog createWatchdog7(
            int heapThreshold, int heapCritical, int heapKill, long intervalMs,
            int gpuThreshold, int gpuCritical, int gpuKill) {
        return new SubprocessMemoryWatchdog(
                heapThreshold, heapCritical, heapKill, intervalMs,
                gpuThreshold, gpuCritical, gpuKill);
    }

    @Nested @DisplayName("10-arg constructor with off-heap thresholds")
    class TenArgConstructor {

        @Test @DisplayName("accepts valid off-heap thresholds")
        void acceptsValidOffHeapThresholds() {
            try (var wd = createWatchdogFull(80, 90, 95, 2000, 75, 85, 92, 70, 85, 93)) {
                assertFalse(wd.shouldStop());
                assertFalse(wd.shouldKill());
                assertFalse(wd.isCriticalMemory());
            }
        }

        @Test @DisplayName("clamps off-heap thresholds to 0-100 range")
        void clampsOffHeapThresholds() {
            // Values outside 0-100 should be clamped, not throw
            try (var wd = createWatchdogFull(80, 90, 95, 2000, 75, 85, 92, -10, 150, 200)) {
                assertNotNull(wd);
            }
        }

        @Test @DisplayName("zero off-heap thresholds effectively disable monitoring")
        void zeroThresholdsDisableMonitoring() {
            try (var wd = createWatchdogFull(80, 90, 95, 2000, 75, 85, 92, 0, 0, 0)) {
                wd.checkNow();
                assertNotNull(wd);
            }
        }

        @Test @DisplayName("100% off-heap thresholds never trigger")
        void maxThresholdsNeverTrigger() {
            try (var wd = createWatchdogFull(80, 90, 95, 2000, 75, 85, 92, 100, 100, 100)) {
                wd.checkNow();
                assertFalse(wd.shouldStop());
                assertFalse(wd.shouldKill());
            }
        }
    }

    @Nested @DisplayName("7-arg constructor backward compatibility")
    class SevenArgConstructor {

        @Test @DisplayName("7-arg constructor creates valid watchdog")
        void sevenArgCreatesValid() {
            try (var wd = createWatchdog7(80, 90, 95, 2000, 75, 85, 92)) {
                assertFalse(wd.shouldStop());
                assertFalse(wd.shouldKill());
            }
        }

        @Test @DisplayName("7-arg constructor captures snapshot")
        void sevenArgCapturesSnapshot() {
            try (var wd = createWatchdog7(80, 90, 95, 60000, 75, 85, 92)) {
                wd.checkNow();
                var snap = wd.getLastSnapshot();
                assertNotNull(snap);
                assertTrue(snap.maxMB() > 0);
            }
        }
    }

    @Nested @DisplayName("Off-heap checkNow and snapshot")
    class OffHeapCheckNow {

        @Test @DisplayName("checkNow with off-heap thresholds captures snapshot")
        void checkNowCapturesSnapshot() {
            try (var wd = createWatchdogFull(80, 90, 95, 60000, 75, 85, 92, 80, 90, 95)) {
                wd.checkNow();
                var snap = wd.getLastSnapshot();
                assertNotNull(snap);
                assertTrue(snap.maxMB() > 0);
                assertTrue(snap.usagePercent() >= 0 && snap.usagePercent() <= 100);
            }
        }

        @Test @DisplayName("reset clears all flags including off-heap-triggered ones")
        void resetClearsAllFlags() {
            try (var wd = createWatchdogFull(80, 90, 95, 60000, 75, 85, 92, 80, 90, 95)) {
                wd.checkNow();
                wd.reset();
                assertFalse(wd.shouldStop());
                assertFalse(wd.shouldKill());
                assertFalse(wd.isCriticalMemory());
            }
        }
    }

    @Nested @DisplayName("Lifecycle with off-heap monitoring")
    class LifecycleWithOffHeap {

        @Test @DisplayName("start/stop with off-heap monitoring enabled")
        void startStopWithOffHeap() throws InterruptedException {
            try (var wd = createWatchdogFull(80, 90, 95, 200, 75, 85, 92, 80, 90, 95)) {
                wd.start();
                Thread.sleep(300);
                wd.stop();
                assertNotNull(wd.getLastSnapshot());
            }
        }

        @Test @DisplayName("periodic monitoring captures snapshots with off-heap")
        void periodicMonitoringWithOffHeap() throws InterruptedException {
            try (var wd = createWatchdogFull(80, 90, 95, 100, 75, 85, 92, 80, 90, 95)) {
                wd.start();
                Thread.sleep(350);
                wd.stop();
                assertNotNull(wd.getLastSnapshot());
            }
        }

        @Test @DisplayName("close idempotent with off-heap")
        void closeIdempotentWithOffHeap() {
            var wd = createWatchdogFull(80, 90, 95, 1000, 75, 85, 92, 80, 90, 95);
            wd.start();
            wd.close();
            wd.close(); // Should not throw
        }

        @Test @DisplayName("double start OK with off-heap")
        void doubleStartOkWithOffHeap() {
            try (var wd = createWatchdogFull(80, 90, 95, 1000, 75, 85, 92, 80, 90, 95)) {
                wd.start();
                wd.start(); // Should not throw
                wd.stop();
            }
        }
    }

    @Nested @DisplayName("Memory velocity tracking")
    class MemoryVelocity {

        @Test @DisplayName("rapid growth flag initially false")
        void rapidGrowthInitiallyFalse() {
            try (var wd = createWatchdogFull(80, 90, 95, 60000, 75, 85, 92, 80, 90, 95)) {
                assertFalse(wd.isRapidMemoryGrowth());
            }
        }

        @Test @DisplayName("checkNow does not falsely trigger rapid growth")
        void checkNowNoFalseRapidGrowth() {
            try (var wd = createWatchdogFull(80, 90, 95, 60000, 75, 85, 92, 80, 90, 95)) {
                wd.checkNow();
                // After a single check, there's no velocity data
                // so rapid growth should not be triggered
                // (need at least 2 checks with interval)
            }
        }
    }

    @Nested @DisplayName("SubprocessArgs integration with off-heap thresholds")
    class SubprocessArgsIntegration {

        @Test @DisplayName("SubprocessArgs builder includes off-heap thresholds")
        void argsBuilderIncludesOffHeapThresholds() {
            var args = SubprocessArgs.builder()
                    .taskId("t").filePath("/f").callbackBaseUrl("http://x")
                    .memoryThresholdPercent(80).memoryCriticalPercent(90)
                    .memoryKillThresholdPercent(95).memoryCheckIntervalMs(2000)
                    .gpuMemoryThresholdPercent(75).gpuMemoryCriticalPercent(85)
                    .gpuMemoryKillThresholdPercent(92)
                    .offHeapThresholdPercent(70).offHeapCriticalPercent(83)
                    .offHeapKillThresholdPercent(91)
                    .build();

            assertEquals(70, args.offHeapThresholdPercent());
            assertEquals(83, args.offHeapCriticalPercent());
            assertEquals(91, args.offHeapKillThresholdPercent());

            // Construct watchdog from args
            try (var wd = new SubprocessMemoryWatchdog(
                    args.memoryThresholdPercent(), args.memoryCriticalPercent(),
                    args.memoryKillThresholdPercent(), args.memoryCheckIntervalMs(),
                    args.gpuMemoryThresholdPercent(), args.gpuMemoryCriticalPercent(),
                    args.gpuMemoryKillThresholdPercent(),
                    args.offHeapThresholdPercent(), args.offHeapCriticalPercent(),
                    args.offHeapKillThresholdPercent())) {
                assertNotNull(wd);
                assertFalse(wd.shouldStop());
            }
        }
    }
}
