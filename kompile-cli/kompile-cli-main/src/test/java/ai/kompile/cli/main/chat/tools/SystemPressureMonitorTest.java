package ai.kompile.cli.main.chat.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SystemPressureMonitorTest {
    static class Fixture implements AutoCloseable {
        final AtomicLong time = new AtomicLong(1000);
        final AtomicReference<Map<String, SystemPressureMonitor.Reading>> readings = new AtomicReference<>(Map.of());
        final AtomicReference<SystemPressureConfig.Settings> settings = new AtomicReference<>(config(3, 10));
        final List<String> kills = new ArrayList<>();
        final AtomicReference<List<String>> targets = new AtomicReference<>(List.of("newest", "older"));
        final SystemPressureMonitor monitor = new SystemPressureMonitor(settings::get, readings::get, targets::get,
                (id, reason) -> { kills.add(id + ":" + reason); return true; }, time::get);
        void sample(double percent) {
            readings.set(Map.of("ram", new SystemPressureMonitor.Reading("ram", percent)));
            monitor.check();
            time.addAndGet(2000);
        }
        @Override public void close() { monitor.close(); }
    }

    static SystemPressureConfig.Settings config(int count, double horizon) {
        return new SystemPressureConfig.Settings(true, 2000, count, 30000, horizon, 1, 0.5, Map.of("ram", 90.0));
    }

    @Test void sustainedThresholdKillsOnlyOneNewestCommand() {
        try (Fixture f = new Fixture()) {
            f.sample(92); f.sample(92);
            assertTrue(f.kills.isEmpty());
            f.sample(92);
            assertEquals(1, f.kills.size());
            assertTrue(f.kills.get(0).startsWith("newest:System resource pressure: ram"));
            assertTrue(f.kills.get(0).contains("threshold"));
            assertEquals(1L, f.monitor.status().get("kills"));
        }
    }

    @Test void allConfiguredResourceKindsCanTrigger() {
        for (String resource : SystemPressureConfig.METRICS) {
            try (Fixture f = new Fixture()) {
                f.settings.set(new SystemPressureConfig.Settings(true, 2000, 1, 30000, 0, 1, 0.5,
                        Map.of(resource, 75.0)));
                f.readings.set(Map.of(resource, new SystemPressureMonitor.Reading(resource, 75)));
                f.monitor.check();
                assertEquals(1, f.kills.size(), resource);
            }
        }
    }

    @Test void momentumCanKillBeforeAbsoluteLimit() {
        try (Fixture f = new Fixture()) {
            f.settings.set(config(2, 10));
            f.sample(60); f.sample(66); f.sample(72);
            assertTrue(f.kills.isEmpty());
            f.sample(78);
            assertEquals(1, f.kills.size());
            assertTrue(f.kills.get(0).contains("3.00 pp/s"));
            assertTrue(f.kills.get(0).contains("momentum"));
            assertTrue(f.kills.get(0).contains("78.00%"));
        }
    }

    @Test void fallingUsageDoesNotRetainMomentumKill() {
        try (Fixture f = new Fixture()) {
            f.settings.set(config(2, 10));
            f.sample(60); f.sample(66); f.sample(72); f.sample(70); f.sample(69);
            assertTrue(f.kills.isEmpty());
        }
    }

    @Test void isolatedSpikeAndMissingReadingsResetBreaches() {
        try (Fixture f = new Fixture()) {
            f.sample(95); f.sample(40); f.sample(95);
            f.readings.set(Map.of()); f.monitor.check(); f.time.addAndGet(2000);
            f.sample(95); f.sample(95);
            assertTrue(f.kills.isEmpty());
            f.sample(95);
            assertEquals(1, f.kills.size());
        }
    }

    @Test void cooldownPreventsKillCascade() {
        try (Fixture f = new Fixture()) {
            f.settings.set(config(1, 0));
            f.sample(95); f.sample(95); f.sample(95);
            assertEquals(1, f.kills.size());
            f.time.addAndGet(30000); f.sample(95);
            assertEquals(2, f.kills.size());
        }
    }

    @Test void killCallbackFailureStillEnforcesCooldownAndReportsUncertainOutcome() {
        AtomicLong time = new AtomicLong(1000);
        List<String> attempts = new ArrayList<>();
        try (var monitor = new SystemPressureMonitor(() -> config(1, 0),
                () -> Map.of("ram", new SystemPressureMonitor.Reading("ram", 95)),
                () -> List.of("newest", "older"), (id, reason) -> {
                    attempts.add(id);
                    throw new IllegalStateException("cleanup failed after signalling process");
                }, time::get)) {
            monitor.check();
            assertEquals("kill error (outcome unknown)", monitor.status().get("state"));
            assertEquals(0L, monitor.status().get("kills"));
            time.addAndGet(2000); monitor.check();
            assertEquals(List.of("newest"), attempts);
            time.addAndGet(30000); monitor.check();
            assertEquals(List.of("newest", "newest"), attempts);
        }
    }

    @Test void statusUsesPlainConfigurationValuesWithoutRecordReflection() {
        try (Fixture f = new Fixture()) {
            f.sample(60);
            assertInstanceOf(Map.class, f.monitor.status().get("config"));
            var json = ai.kompile.cli.common.util.JsonUtils.standardMapper().valueToTree(f.monitor.status());
            assertTrue(json.path("config").path("enabled").asBoolean());
            assertEquals(90, json.path("config").path("thresholds").path("ram").asDouble());
        }
    }

    @Test void disableReconfigureCloseAndNoTargetsCannotKill() {
        try (Fixture f = new Fixture()) {
            f.sample(95); f.sample(95);
            f.settings.set(new SystemPressureConfig.Settings(false, 2000, 3, 30000, 10, 1, 0.5, Map.of("ram", 90.0)));
            f.sample(95);
            assertEquals("disabled", f.monitor.status().get("state"));
            f.settings.set(config(3, 10)); f.sample(95);
            f.targets.set(List.of()); f.sample(95);
            f.targets.set(List.of("newest")); f.sample(95);
            f.monitor.close(); f.sample(95); f.sample(95);
            assertTrue(f.kills.isEmpty());
        }
    }

    @Test void nonfiniteReadingsAndLongSamplingGapsDoNotCarryHistory() {
        try (Fixture f = new Fixture()) {
            f.sample(95); f.sample(95); f.sample(Double.NaN); f.sample(95); f.sample(95);
            f.time.addAndGet(20000); f.sample(95);
            assertTrue(f.kills.isEmpty());
        }
    }

    @Test void repeatedChecksAndStatusDoNotManufactureBreaches() {
        try (Fixture f = new Fixture()) {
            f.readings.set(Map.of("ram", new SystemPressureMonitor.Reading("ram", 95)));
            for (int i = 0; i < 20; i++) { f.monitor.check(); f.monitor.status(); }
            assertTrue(f.kills.isEmpty());
        }
    }

    @Test void linuxMemoryRatiosUseOneHostSnapshotAndRejectMissingOrInvalidValues() {
        var readings = SystemPressureMonitor.parseHostMemory(
                "MemTotal: 1000 kB\nMemAvailable: 200 kB\nSwapTotal: 500 kB\nSwapFree: 250 kB\n");
        assertEquals(80, readings.get("ram").percent());
        assertEquals(50, readings.get("swap").percent());
        for (String invalid : List.of("MemAvailable: 200 kB\n", "MemTotal: 1000 kB\n",
                "MemTotal: 1000 kB\nMemAvailable: 2000 kB\n", "MemTotal: 1000 kB\nMemAvailable: -1 kB\n",
                "MemTotal: invalid kB\nMemAvailable: 200 kB\n", "SwapTotal: 0 kB\nSwapFree: 0 kB\n")) {
            assertTrue(SystemPressureMonitor.parseHostMemory(invalid).isEmpty(), invalid);
        }
    }

    @Test void momentumIsSmoothedAndUsesActualElapsedTime() {
        try (Fixture f = new Fixture()) {
            f.settings.set(new SystemPressureConfig.Settings(true, 2000, 3, 30000, 0, 0.5, 0.5, Map.of("ram", 90.0)));
            f.sample(40); // time 1000
            f.time.addAndGet(2000);
            f.sample(48); // time 5000: slope 2 pp/s, EMA 1
            f.sample(52); // time 7000: slope 2 pp/s, EMA 1.5
            Map<?, ?> metrics = (Map<?, ?>) f.monitor.status().get("metrics");
            Map<?, ?> ram = (Map<?, ?>) metrics.get("ram");
            assertEquals(1.5, (double) ram.get("percentPerSecond"), 0.0001);
            assertTrue(f.kills.isEmpty());
        }
    }

    @Test void gpuTrendKeysUseStableDeviceIdentityAndKeepUnavailableValuesUnknown() {
        var parsed = SystemPressureMonitor.parseGpu("GPU-A, 90, 100, 80\nGPU-B, N/A, N/A, 99\nGPU-C, 300, 200, NaN\n");
        assertEquals(3, parsed.size());
        assertEquals(90, parsed.get("gpuMemory:GPU-A").percent());
        assertEquals(99, parsed.get("gpuUtilization:GPU-B").percent());
        assertFalse(parsed.containsKey("gpuMemory:GPU-B"));
        assertFalse(parsed.containsKey("gpuMemory:GPU-C"));
    }

    @Test void disabledResourcesAndDifferentGpuDevicesDoNotShareBreachHistory() {
        try (Fixture f = new Fixture()) {
            f.settings.set(new SystemPressureConfig.Settings(true, 2000, 2, 30000, 0, 1, 0.5,
                    Map.of("gpuMemory", 90.0)));
            for (String device : List.of("GPU-A", "GPU-B", "GPU-A", "GPU-B")) {
                f.readings.set(Map.of("cpu", new SystemPressureMonitor.Reading("cpu", 100),
                        "gpuMemory:" + device, new SystemPressureMonitor.Reading("gpuMemory", 95)));
                f.monitor.check(); f.time.addAndGet(2000);
                assertTrue(f.kills.isEmpty());
            }
            f.monitor.check();
            assertEquals(1, f.kills.size()); // second consecutive sample for GPU-B only
            assertTrue(f.kills.get(0).contains("gpuMemory:GPU-B"));
            assertFalse(f.kills.get(0).contains("cpu"));
        }
    }

    @Test void configurationErrorsClearPriorBreachesBeforeRecovery() {
        AtomicLong time = new AtomicLong(1000);
        AtomicReference<Boolean> fail = new AtomicReference<>(false);
        List<String> killed = new ArrayList<>();
        try (var monitor = new SystemPressureMonitor(() -> {
            if (fail.get()) throw new IllegalArgumentException("invalid configuration");
            return config(2, 0);
        }, () -> Map.of("ram", new SystemPressureMonitor.Reading("ram", 95)),
                () -> List.of("owned"), (id, reason) -> { killed.add(id); return true; }, time::get)) {
            monitor.check(); time.addAndGet(2000);
            fail.set(true); monitor.check();
            assertEquals("monitor error (no kill)", monitor.status().get("state"));
            fail.set(false); time.addAndGet(2000); monitor.check();
            assertTrue(killed.isEmpty());
            time.addAndGet(2000); monitor.check();
            assertEquals(List.of("owned"), killed);
        }
    }

    @Test void telemetryFailuresFailOpenAndClearTrends() {
        AtomicLong time = new AtomicLong(1000);
        AtomicReference<Boolean> fail = new AtomicReference<>(false);
        List<String> killed = new ArrayList<>();
        try (var monitor = new SystemPressureMonitor(() -> config(2, 0), () -> {
            if (fail.get()) throw new IllegalStateException("probe failed");
            return Map.of("ram", new SystemPressureMonitor.Reading("ram", 95));
        }, () -> List.of("owned"), (id, reason) -> { killed.add(id); return true; }, time::get)) {
            monitor.check(); time.addAndGet(2000); fail.set(true); monitor.check();
            assertEquals("monitor error (no kill)", monitor.status().get("state"));
            time.addAndGet(2000); fail.set(false); monitor.check();
            assertTrue(killed.isEmpty());
        }
    }

    @Test @DisabledOnOs(OS.WINDOWS)
    void managerRejectsSharedAndVirtualTargetsAndPublishesPressureReason(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".kompile"));
        Files.writeString(dir.resolve(".kompile/resource-monitor.json"), "{\"enabled\":false}");
        try (var manager = new BackgroundProcessManager("pressure-test", dir)) {
            var virtual = manager.registerVirtual(BackgroundProcessManager.ProcessKind.COMMAND, "virtual", "virtual", Map.of());
            var shared = manager.registerVirtual(BackgroundProcessManager.ProcessKind.SHARED, "shared", "shared", Map.of());
            assertTrue(manager.pressureTargets().isEmpty());
            assertFalse(manager.killForPressure(virtual.getId(), "test"));
            assertFalse(manager.killForPressure(shared.getId(), "test"));
            var owned = manager.launch("exec sleep 30", "owned test process", dir);
            assertEquals(List.of(owned.getId()), manager.pressureTargets());
            try (var monitor = new SystemPressureMonitor(() -> config(1, 0),
                    () -> Map.of("ram", new SystemPressureMonitor.Reading("ram", 95)),
                    manager::pressureTargets, manager::killForPressure, () -> 1000L)) {
                monitor.check();
                assertEquals(1L, monitor.status().get("kills"));
            }
            assertEquals(BackgroundProcessManager.ProcessState.KILLED, owned.getState());
            assertTrue(owned.getMetadata().get("resourceMonitorReason").startsWith("System resource pressure: ram"));
            assertTrue(manager.pressureTargets().isEmpty());
            var tool = new ProcessManagementTool(manager);
            assertFalse(tool.execute(ai.kompile.cli.common.util.JsonUtils.standardMapper().createObjectNode()
                    .put("action", "resource_status"), null).isError());
        }
    }
}
