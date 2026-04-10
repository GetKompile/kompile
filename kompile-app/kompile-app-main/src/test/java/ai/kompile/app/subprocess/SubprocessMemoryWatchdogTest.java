package ai.kompile.app.subprocess;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SubprocessMemoryWatchdog")
class SubprocessMemoryWatchdogTest {

    // Helper to create watchdog with standard args: heap thresholds + interval + GPU thresholds
    // Constructor: (memoryThresholdPercent, memoryCriticalPercent, memoryKillThresholdPercent,
    //               checkIntervalMs, gpuMemoryThresholdPercent, gpuMemoryCriticalPercent, gpuMemoryKillThresholdPercent)
    private SubprocessMemoryWatchdog createWatchdog(int threshold, int critical, int kill, long intervalMs) {
        return new SubprocessMemoryWatchdog(threshold, critical, kill, intervalMs, 75, 85, 92);
    }

    @Nested @DisplayName("Construction")
    class Construction {
        @Test void defaultThresholds() {
            try (var wd = createWatchdog(80, 90, 95, 2000)) {
                assertFalse(wd.shouldStop());
                assertFalse(wd.shouldKill());
                assertFalse(wd.isCriticalMemory());
            }
        }

        @Test void fromArgsBuilder() {
            SubprocessArgs args = SubprocessArgs.builder()
                    .taskId("t").filePath("/f").callbackBaseUrl("http://x")
                    .memoryThresholdPercent(75).memoryCriticalPercent(85)
                    .memoryKillThresholdPercent(92).memoryCheckIntervalMs(3000)
                    .gpuMemoryThresholdPercent(70).gpuMemoryCriticalPercent(80)
                    .gpuMemoryKillThresholdPercent(90).build();
            // Construct from args fields directly
            try (var wd = new SubprocessMemoryWatchdog(
                    args.memoryThresholdPercent(), args.memoryCriticalPercent(),
                    args.memoryKillThresholdPercent(), args.memoryCheckIntervalMs(),
                    args.gpuMemoryThresholdPercent(), args.gpuMemoryCriticalPercent(),
                    args.gpuMemoryKillThresholdPercent())) {
                assertNotNull(wd);
                assertFalse(wd.shouldStop());
            }
        }

        @Test void killThresholdDisabled() {
            try (var wd = createWatchdog(80, 90, 0, 2000)) {
                assertFalse(wd.shouldKill());
            }
        }
    }

    @Nested @DisplayName("Monitoring")
    class Monitoring {
        @Test void startStop() throws InterruptedException {
            try (var wd = createWatchdog(80, 90, 95, 500)) {
                wd.start();
                Thread.sleep(200);
                wd.stop();
            }
        }

        @Test void checkNowCapturesSnapshot() {
            try (var wd = createWatchdog(80, 90, 95, 60000)) {
                wd.checkNow();
                var snap = wd.getLastSnapshot();
                assertNotNull(snap);
                assertTrue(snap.maxMB() > 0);
                assertTrue(snap.usagePercent() >= 0 && snap.usagePercent() <= 100);
            }
        }

        @Test void currentMemoryPercent() {
            try (var wd = createWatchdog(80, 90, 95, 60000)) {
                double pct = wd.getCurrentMemoryPercent();
                assertTrue(pct >= 0 && pct <= 100);
            }
        }

        @Test void snapshotFormatted() {
            try (var wd = createWatchdog(80, 90, 95, 60000)) {
                wd.checkNow();
                assertNotNull(wd.getLastSnapshot().formatted());
                assertFalse(wd.getLastSnapshot().formatted().isEmpty());
            }
        }
    }

    @Nested @DisplayName("Thresholds")
    class Thresholds {
        @Test void initialClean() {
            try (var wd = createWatchdog(80, 90, 95, 60000)) {
                assertFalse(wd.shouldStop());
                assertFalse(wd.shouldKill());
                assertFalse(wd.isCriticalMemory());
            }
        }

        @Test void resetClearsFlags() {
            try (var wd = createWatchdog(80, 90, 95, 60000)) {
                wd.checkNow();
                wd.reset();
                assertFalse(wd.shouldStop());
                assertFalse(wd.shouldKill());
                assertFalse(wd.isCriticalMemory());
            }
        }
    }

    @Nested @DisplayName("ForceKill")
    class ForceKill {
        @Test void forceKillEnabledByDefault() {
            try (var wd = createWatchdog(80, 90, 95, 60000)) {
                assertTrue(wd.isForceKillOnThreshold(),
                        "forceKillOnThreshold should be true by default");
            }
        }

        @Test void forceKillCanBeDisabled() {
            try (var wd = createWatchdog(80, 90, 95, 60000)) {
                wd.setForceKillOnThreshold(false);
                assertFalse(wd.isForceKillOnThreshold(),
                        "forceKillOnThreshold should be false after disabling");
            }
        }

        @Test void disabledForceKillStillSetsFlags() {
            // With forceKill disabled, kill threshold should only set flags (not halt JVM)
            // We can't easily test Runtime.halt() since it terminates the JVM,
            // but we can verify that with forceKill disabled, the flags still work
            try (var wd = createWatchdog(80, 90, 95, 60000)) {
                wd.setForceKillOnThreshold(false);
                // checkNow won't trigger kill unless memory is actually at 95%+
                // but we verify the flag state is consistent
                wd.checkNow();
                // At normal memory, kill should not be set
                assertFalse(wd.shouldKill());
                assertFalse(wd.isForceKillOnThreshold());
            }
        }

        @Test void forceKillTogglePreservedAcrossResets() {
            try (var wd = createWatchdog(80, 90, 95, 60000)) {
                wd.setForceKillOnThreshold(false);
                wd.reset();
                assertFalse(wd.isForceKillOnThreshold(),
                        "forceKillOnThreshold should survive reset()");
            }
        }
    }

    @Nested @DisplayName("Lifecycle")
    class Lifecycle {
        @Test void doubleStartOk() {
            try (var wd = createWatchdog(80, 90, 95, 1000)) {
                wd.start(); wd.start(); wd.stop();
            }
        }

        @Test void stopWithoutStartOk() {
            try (var wd = createWatchdog(80, 90, 95, 1000)) {
                wd.stop();
            }
        }

        @Test void closeIdempotent() {
            var wd = createWatchdog(80, 90, 95, 1000);
            wd.start(); wd.close(); wd.close();
        }

        @Test void periodicMonitoring() throws InterruptedException {
            try (var wd = createWatchdog(80, 90, 95, 100)) {
                wd.start();
                Thread.sleep(350);
                wd.stop();
                assertNotNull(wd.getLastSnapshot());
            }
        }
    }
}
