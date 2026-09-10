/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.project;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused tests for the parent-side local crawl subprocess watchdog: config surface,
 * limit resolution, tracking lifecycle, and real-process kill.
 */
class LocalSubprocessWatchdogTest {

    private final LocalSubprocessWatchdog watchdog = LocalSubprocessWatchdog.get();

    @AfterEach
    void resetSystemProperties() {
        System.clearProperty(LocalSubprocessWatchdog.ENABLED_PROPERTY);
        System.clearProperty(LocalSubprocessWatchdog.INTERVAL_PROPERTY);
        System.clearProperty(LocalSubprocessWatchdog.MAX_RSS_MB_PROPERTY);
        System.clearProperty(LocalSubprocessWatchdog.MAX_RSS_FRACTION_PROPERTY);
        System.clearProperty(LocalSubprocessWatchdog.BREACH_COUNT_PROPERTY);
        System.clearProperty(LocalSubprocessWatchdog.GRACE_SECONDS_PROPERTY);
        System.clearProperty(LocalSubprocessWatchdog.ADMISSION_MODE_PROPERTY);
        System.clearProperty(LocalSubprocessWatchdog.ADMISSION_TIMEOUT_MS_PROPERTY);
        System.clearProperty(LocalSubprocessWatchdog.ADMISSION_POLL_INTERVAL_MS_PROPERTY);
        System.clearProperty(LocalSubprocessWatchdog.ADMISSION_MIN_AVAILABLE_RAM_MB_PROPERTY);
        System.clearProperty(LocalSubprocessWatchdog.ADMISSION_MAX_RAM_USED_FRACTION_PROPERTY);
        System.clearProperty(LocalSubprocessWatchdog.ADMISSION_MIN_AVAILABLE_GPU_MB_PROPERTY);
        System.clearProperty(LocalSubprocessWatchdog.ADMISSION_MAX_GPU_USED_FRACTION_PROPERTY);
    }

    @Test
    void enforcementIsOffByDefaultButTrackingWorks() {
        assertEquals(0L, watchdog.maxRssMb());
        assertEquals(0.0, watchdog.maxRssFraction());
        assertEquals(0L, watchdog.effectiveLimitMb(), "no limits set → enforcement disabled");
        assertTrue(watchdog.enabled(), "enabled by default; zero limits are what gate enforcement");
        assertEquals("wait", watchdog.admissionMode());
        assertEquals(0.75, watchdog.admissionMaxRamUsedFraction());
        assertEquals(0.75, watchdog.admissionMaxGpuUsedFraction());
    }

    @Test
    void effectiveLimitIsTheLowerOfAbsoluteAndFraction() throws Exception {
        System.setProperty(LocalSubprocessWatchdog.MAX_RSS_MB_PROPERTY, "1024");
        assertEquals(1024L, watchdog.effectiveLimitMb());

        long totalRamMb = watchdog.systemTotalRamMb();
        if (totalRamMb > 0) {
            System.setProperty(LocalSubprocessWatchdog.MAX_RSS_FRACTION_PROPERTY, "0.5");
            long expected = Math.min(1024L, totalRamMb / 2);
            assertEquals(expected, watchdog.effectiveLimitMb());
        }

        System.setProperty(LocalSubprocessWatchdog.MAX_RSS_MB_PROPERTY, "0");
        if (totalRamMb > 0) {
            assertEquals(totalRamMb / 2, watchdog.effectiveLimitMb());
        }
    }

    @Test
    void configUpdateAppliesImmediatelyAndClearsOnNull() {
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("maxRssMb", 512);
        updates.put("intervalMs", 5000);
        updates.put("breachCount", 3);
        updates.put("admissionMode", "fail-fast");
        updates.put("admissionTimeoutMs", 1234);
        updates.put("admissionMinAvailableRamMb", 8192);
        updates.put("admissionMaxGpuUsedFraction", 0.75);
        watchdog.updateConfig(updates);
        assertEquals(512L, watchdog.maxRssMb());
        assertEquals(5000L, watchdog.intervalMs());
        assertEquals(3, watchdog.breachCount());
        assertEquals("fail", watchdog.admissionMode());
        assertEquals(1234L, watchdog.admissionTimeoutMs());
        assertEquals(8192L, watchdog.admissionMinAvailableRamMb());
        assertEquals(0.75, watchdog.admissionMaxGpuUsedFraction());

        updates.clear();
        updates.put("maxRssMb", null);
        updates.put("breachCount", null);
        updates.put("admissionMode", null);
        watchdog.updateConfig(updates);
        assertEquals(0L, watchdog.maxRssMb(), "null clears the property");
        assertEquals(2, watchdog.breachCount(), "cleared property returns to default");
        assertEquals("wait", watchdog.admissionMode());
    }

    @Test
    void configUpdateRejectsGarbage() {
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("maxRssMb", "not-a-number");
        try {
            watchdog.updateConfig(updates);
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // nothing persisted for the bad key
            assertEquals(0L, watchdog.maxRssMb());
        }

        updates.clear();
        updates.put("admissionMaxRamUsedFraction", 1.5);
        try {
            watchdog.updateConfig(updates);
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertEquals(0.75, watchdog.admissionMaxRamUsedFraction());
        }
    }

    @Test
    void gpuAdmissionUsesAnyDeviceWithHeadroomInsteadOfHardcodingOneCard() throws Exception {
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_MAX_RAM_USED_FRACTION_PROPERTY, "0");
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_MAX_GPU_USED_FRACTION_PROPERTY, "0.9");
        LocalSubprocessWatchdog.HardwareCapacitySnapshot capacity = snapshot(0.25,
                new LocalSubprocessWatchdog.GpuCapacity(0, 23_000, 24_000),
                new LocalSubprocessWatchdog.GpuCapacity(1, 1_000, 8_000));

        LocalSubprocessWatchdog.CapacityAdmission admission = watchdog.awaitCrawlCapacity(
                () -> capacity,
                millis -> { throw new AssertionError("healthy capacity must not sleep"); },
                reason -> { throw new AssertionError("healthy capacity must not wait: " + reason); });

        assertTrue(admission.admitted());
        assertFalse(admission.waited());
        assertEquals("ADMITTED", admission.outcome());
        assertEquals(0.125, admission.capacity().bestGpuUsedFraction());
    }

    @Test
    void gpuAdmissionAppliesTheAbsoluteFloorAcrossAllDevices() throws Exception {
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_MODE_PROPERTY, "fail");
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_MAX_RAM_USED_FRACTION_PROPERTY, "0");
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_MAX_GPU_USED_FRACTION_PROPERTY, "0");
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_MIN_AVAILABLE_GPU_MB_PROPERTY, "10000");

        LocalSubprocessWatchdog.CapacityAdmission rejected = watchdog.awaitCrawlCapacity(
                () -> snapshot(0.25,
                        new LocalSubprocessWatchdog.GpuCapacity(0, 15_000, 24_000),
                        new LocalSubprocessWatchdog.GpuCapacity(1, 0, 8_000)),
                millis -> { throw new AssertionError("fail mode must not sleep"); },
                ignored -> { });
        assertFalse(rejected.admitted());
        assertTrue(rejected.reason().contains("no GPU has 10000 MB free"), rejected.reason());

        LocalSubprocessWatchdog.CapacityAdmission admitted = watchdog.awaitCrawlCapacity(
                () -> snapshot(0.25,
                        new LocalSubprocessWatchdog.GpuCapacity(0, 12_000, 24_000),
                        new LocalSubprocessWatchdog.GpuCapacity(1, 0, 8_000)),
                millis -> { throw new AssertionError("available GPU capacity must not sleep"); },
                ignored -> { });
        assertTrue(admitted.admitted());
    }

    @Test
    void explicitGpuFloorFailsClosedWhenGpuCapacityIsUnknown() throws Exception {
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_MODE_PROPERTY, "fail");
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_MAX_RAM_USED_FRACTION_PROPERTY, "0");
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_MAX_GPU_USED_FRACTION_PROPERTY, "0");
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_MIN_AVAILABLE_GPU_MB_PROPERTY, "8192");

        LocalSubprocessWatchdog.CapacityAdmission admission = watchdog.awaitCrawlCapacity(
                () -> new LocalSubprocessWatchdog.HardwareCapacitySnapshot(
                        100_000, 80_000, 0.2, List.of(),
                        "nvidia-smi-unavailable", Instant.now()),
                millis -> { throw new AssertionError("fail mode must not sleep"); },
                ignored -> { });

        assertFalse(admission.admitted());
        assertTrue(admission.reason().contains("cannot verify the configured 8192 MB"),
                admission.reason());
    }

    @Test
    void failModeRejectsImmediatelyWhenRamIsOverCapacity() throws Exception {
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_MODE_PROPERTY, "fail");
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_MAX_RAM_USED_FRACTION_PROPERTY, "0.8");
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_MAX_GPU_USED_FRACTION_PROPERTY, "0");

        LocalSubprocessWatchdog.CapacityAdmission admission = watchdog.awaitCrawlCapacity(
                () -> snapshot(0.9),
                millis -> { throw new AssertionError("fail mode must not sleep"); },
                reason -> { throw new AssertionError("fail mode must not report a wait"); });

        assertFalse(admission.admitted());
        assertFalse(admission.waited());
        assertEquals("REJECTED", admission.outcome());
        assertTrue(admission.reason().contains("system RAM is 90.0% used"), admission.reason());
        assertEquals(1, admission.samples());
    }

    @Test
    void waitModeAdmitsAfterCapacityReturns() throws Exception {
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_MODE_PROPERTY, "wait");
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_TIMEOUT_MS_PROPERTY, "1000");
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_POLL_INTERVAL_MS_PROPERTY, "1");
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_MAX_RAM_USED_FRACTION_PROPERTY, "0.8");
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_MAX_GPU_USED_FRACTION_PROPERTY, "0");
        AtomicInteger samples = new AtomicInteger();
        AtomicInteger sleeps = new AtomicInteger();
        List<String> waits = new ArrayList<>();

        LocalSubprocessWatchdog.CapacityAdmission admission = watchdog.awaitCrawlCapacity(
                () -> samples.getAndIncrement() == 0 ? snapshot(0.9) : snapshot(0.2),
                millis -> sleeps.incrementAndGet(),
                waits::add);

        assertTrue(admission.admitted());
        assertTrue(admission.waited());
        assertEquals("ADMITTED", admission.outcome());
        assertEquals(2, admission.samples());
        assertEquals(1, sleeps.get());
        assertEquals(1, waits.size());
    }

    @Test
    void disablingAdmissionReleasesAnExistingWaiter() throws Exception {
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_MODE_PROPERTY, "wait");
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_TIMEOUT_MS_PROPERTY, "1000");
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_POLL_INTERVAL_MS_PROPERTY, "1");
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_MAX_RAM_USED_FRACTION_PROPERTY, "0.8");
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_MAX_GPU_USED_FRACTION_PROPERTY, "0");

        LocalSubprocessWatchdog.CapacityAdmission admission = watchdog.awaitCrawlCapacity(
                () -> snapshot(0.9),
                millis -> System.setProperty(
                        LocalSubprocessWatchdog.ADMISSION_MODE_PROPERTY, "off"),
                ignored -> { });

        assertTrue(admission.admitted());
        assertTrue(admission.waited());
        assertEquals("DISABLED", admission.outcome());
        assertEquals(1, admission.samples(), "disabling the gate should not perform another probe");
    }

    @Test
    void waitModeTimesOutWhileCapacityRemainsHigh() throws Exception {
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_MODE_PROPERTY, "wait");
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_TIMEOUT_MS_PROPERTY, "5");
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_POLL_INTERVAL_MS_PROPERTY, "1");
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_MAX_RAM_USED_FRACTION_PROPERTY, "0.8");
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_MAX_GPU_USED_FRACTION_PROPERTY, "0");

        LocalSubprocessWatchdog.CapacityAdmission admission = watchdog.awaitCrawlCapacity(
                () -> snapshot(0.9), Thread::sleep, ignored -> { });

        assertFalse(admission.admitted());
        assertTrue(admission.waited());
        assertEquals("TIMED_OUT", admission.outcome());
        assertTrue(admission.waitedMs() >= 5L, "timeout returned too early: " + admission.waitedMs());
    }

    @Test
    void parsesNvidiaMemoryWithoutAssumingDeviceOrder() {
        List<LocalSubprocessWatchdog.GpuCapacity> gpus =
                LocalSubprocessWatchdog.parseNvidiaGpuMemory("1, 7000, 8192\n0, 512, 24576\ninvalid\n");

        assertEquals(2, gpus.size());
        assertEquals(1, gpus.get(0).index());
        assertEquals(1192L, gpus.get(0).availableMb());
        assertEquals(0, gpus.get(1).index());
        assertEquals(24064L, gpus.get(1).availableMb());
    }

    @Test
    void tracksAndDeregistersRealProcesses() throws Exception {
        Process sleepy = new ProcessBuilder("sleep", "60").start();
        try {
            String id = watchdog.register("test-serving-x", sleepy, "model-serving", "test child");
            assertTrue(watchdog.find(id).isPresent());
            assertEquals(1, watchdog.trackedCount());

            LocalSubprocessWatchdog.TrackedSubprocess tracked = watchdog.find(id).orElseThrow();
            assertEquals(sleepy.pid(), tracked.pid());
            assertTrue(tracked.alive());
            // A freshly started sleep may not be in /proc status yet on the first read,
            // but if the RSS is reported it must be plausible (>0).
            long rss = tracked.rssMb();
            if (rss > 0) {
                assertTrue(rss < 4096, "sleep RSS should be small, was " + rss);
            }
        } finally {
            sleepy.destroy();
            sleepy.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        watchdog.deregister("test-serving-x");
        assertEquals(0, watchdog.trackedCount());
        assertFalse(watchdog.find("test-serving-x").isPresent());
    }

    @Test
    void killRemovesATrackedProcess() throws Exception {
        Process sleepy = new ProcessBuilder("sleep", "60").start();
        try {
            String id = watchdog.register("test-kill-x", sleepy, "model-serving", "kill test");
            assertTrue(watchdog.kill(id, "test kill"));
            // Allow the destroy to complete.
            sleepy.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            assertFalse(sleepy.isAlive(), "process should be dead after kill");
            assertFalse(watchdog.find(id).isPresent(), "killed entries are deregistered");
            assertFalse(watchdog.kill("test-kill-x", "repeat"), "second kill returns false");
        } finally {
            if (sleepy.isAlive()) {
                sleepy.destroyForcibly();
            }
        }
    }

    @Test
    void graceWindowProtectsFreshChildren() throws Exception {
        System.setProperty(LocalSubprocessWatchdog.GRACE_SECONDS_PROPERTY, "3600");
        System.setProperty(LocalSubprocessWatchdog.MAX_RSS_MB_PROPERTY, "1");
        System.setProperty(LocalSubprocessWatchdog.BREACH_COUNT_PROPERTY, "1");
        Process sleepy = new ProcessBuilder("sleep", "60").start();
        try {
            watchdog.register("test-grace-x", sleepy, "model-serving", "grace test");
            // Wait until the child reports an RSS so the breach path would have fired.
            for (int i = 0; i < 50 && LocalSubprocessWatchdog.readRssMb(sleepy.pid()) <= 0; i++) {
                Thread.sleep(100);
            }
            int killed = watchdog.checkAll();
            assertEquals(0, killed, "child inside the grace window must not be killed");
            assertTrue(sleepy.isAlive());
        } finally {
            sleepy.destroy();
            sleepy.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            watchdog.deregister("test-grace-x");
        }
    }

    @Test
    void statusMapCarriesTrackingAndConfig() {
        Map<String, Object> status = watchdog.statusMap();
        assertEquals("project-local", status.get("backend"));
        assertTrue(status.containsKey("config"));
        assertTrue(status.containsKey("trackedProcesses"));
        assertTrue(status.containsKey("aliveCount"));
        assertTrue(status.containsKey("capacityAdmission"));
        assertTrue(((Map<?, ?>) status.get("config")).containsKey("effectiveLimitMb"));
        assertTrue(((Map<?, ?>) status.get("config")).containsKey("admissionMode"));
    }

    @Test
    void registrationRejectsBadInput() {
        try {
            watchdog.register(null, 1L, "t", "d");
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            watchdog.register("bad-pid", -5L, "t", "d");
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        assertNotEquals(0, watchdog.trackedCount() < 0, "sanity");
    }

    private static LocalSubprocessWatchdog.HardwareCapacitySnapshot snapshot(
            double ramUsedFraction, LocalSubprocessWatchdog.GpuCapacity... gpus) {
        long totalRamMb = 100_000L;
        long availableRamMb = Math.round(totalRamMb * (1.0 - ramUsedFraction));
        return new LocalSubprocessWatchdog.HardwareCapacitySnapshot(
                totalRamMb, availableRamMb, ramUsedFraction,
                List.of(gpus), "test", Instant.now());
    }
}
