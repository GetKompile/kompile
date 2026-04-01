package ai.kompile.app.subprocess;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SubprocessMemoryWatchdog")
class SubprocessMemoryWatchdogTest {

    @Nested @DisplayName("Construction")
    class Construction {
        @Test void defaultThresholds() {
            try (var wd = new SubprocessMemoryWatchdog(80, 90, 95, 2000)) {
                assertFalse(wd.shouldStop());
                assertFalse(wd.shouldKill());
                assertFalse(wd.isCriticalMemory());
            }
        }

        @Test void fromArgs() {
            SubprocessArgs args = SubprocessArgs.builder()
                    .taskId("t").filePath("/f").callbackBaseUrl("http://x")
                    .memoryThresholdPercent(75).memoryCriticalPercent(85)
                    .memoryKillThresholdPercent(92).memoryCheckIntervalMs(3000).build();
            try (var wd = SubprocessMemoryWatchdog.fromArgs(args)) {
                assertNotNull(wd);
                assertFalse(wd.shouldStop());
            }
        }

        @Test void killThresholdDisabled() {
            try (var wd = new SubprocessMemoryWatchdog(80, 90, 0, 2000)) {
                assertFalse(wd.shouldKill());
            }
        }
    }

    @Nested @DisplayName("Monitoring")
    class Monitoring {
        @Test void startStop() throws InterruptedException {
            try (var wd = new SubprocessMemoryWatchdog(80, 90, 95, 500)) {
                wd.start();
                Thread.sleep(200);
                wd.stop();
            }
        }

        @Test void checkNowCapturesSnapshot() {
            try (var wd = new SubprocessMemoryWatchdog(80, 90, 95, 60000)) {
                wd.checkNow();
                var snap = wd.getLastSnapshot();
                assertNotNull(snap);
                assertTrue(snap.maxMB() > 0);
                assertTrue(snap.usagePercent() >= 0 && snap.usagePercent() <= 100);
            }
        }

        @Test void currentMemoryPercent() {
            try (var wd = new SubprocessMemoryWatchdog(80, 90, 95, 60000)) {
                double pct = wd.getCurrentMemoryPercent();
                assertTrue(pct >= 0 && pct <= 100);
            }
        }

        @Test void snapshotFormatted() {
            try (var wd = new SubprocessMemoryWatchdog(80, 90, 95, 60000)) {
                wd.checkNow();
                assertNotNull(wd.getLastSnapshot().formatted());
                assertFalse(wd.getLastSnapshot().formatted().isEmpty());
            }
        }
    }

    @Nested @DisplayName("Thresholds")
    class Thresholds {
        @Test void initialClean() {
            try (var wd = new SubprocessMemoryWatchdog(80, 90, 95, 60000)) {
                assertFalse(wd.shouldStop());
                assertFalse(wd.shouldKill());
                assertFalse(wd.isCriticalMemory());
            }
        }

        @Test void resetClearsFlags() {
            try (var wd = new SubprocessMemoryWatchdog(80, 90, 95, 60000)) {
                wd.checkNow();
                wd.reset();
                assertFalse(wd.shouldStop());
                assertFalse(wd.shouldKill());
                assertFalse(wd.isCriticalMemory());
            }
        }
    }

    @Nested @DisplayName("Lifecycle")
    class Lifecycle {
        @Test void doubleStartOk() {
            try (var wd = new SubprocessMemoryWatchdog(80, 90, 95, 1000)) {
                wd.start(); wd.start(); wd.stop();
            }
        }

        @Test void stopWithoutStartOk() {
            try (var wd = new SubprocessMemoryWatchdog(80, 90, 95, 1000)) {
                wd.stop();
            }
        }

        @Test void closeIdempotent() {
            var wd = new SubprocessMemoryWatchdog(80, 90, 95, 1000);
            wd.start(); wd.close(); wd.close();
        }

        @Test void periodicMonitoring() throws InterruptedException {
            try (var wd = new SubprocessMemoryWatchdog(80, 90, 95, 100)) {
                wd.start();
                Thread.sleep(350);
                wd.stop();
                assertNotNull(wd.getLastSnapshot());
            }
        }
    }
}
