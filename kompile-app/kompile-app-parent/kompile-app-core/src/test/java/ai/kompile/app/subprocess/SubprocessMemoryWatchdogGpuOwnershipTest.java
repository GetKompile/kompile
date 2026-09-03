/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.app.subprocess;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubprocessMemoryWatchdogGpuOwnershipTest {

    private static final long GIB = 1024L * 1024L * 1024L;

    @Test
    void driverWideSaturationFromAnotherProcessDoesNotKillThisSubprocess() {
        // 512MB free = ~2.1% (above the 1% exhaustion override): another process has the
        // card nearly full, but OUR mempool holds only 2GB — no kill signal from that.
        SubprocessMemoryWatchdog.GpuProbe probe =
                SubprocessMemoryWatchdog.processOwnedGpuProbe(
                        0, 24 * GIB, 512L * 1024L * 1024L, GIB, 2 * GIB);

        assertNotNull(probe);
        assertEquals(2 * GIB, probe.usedBytes());
        assertTrue(probe.driverUsedBytes() > 23 * GIB,
                "driver telemetry should still expose external occupancy for diagnostics");
        assertTrue(probe.usagePercent() < 10.0,
                "kill decisions must use process-owned memory, not the saturated device total");
    }

    @Test
    void aliasedLogicalTrackedBytesNeverInflateTheKillSignal() {
        // The regression this guards against: logical DataBuffer tracking double-counts
        // aliased pool pages and exceeded physical capacity (tracked 25GB on a 24GB card)
        // while the real mempool occupancy was ~18GB — the watchdog killed a healthy plan.
        SubprocessMemoryWatchdog.GpuProbe probe =
                SubprocessMemoryWatchdog.processOwnedGpuProbe(
                        0, 24 * GIB, 1500L * 1024L * 1024L, 25 * GIB, 18 * GIB);

        assertNotNull(probe);
        assertEquals(18 * GIB, probe.usedBytes(),
                "kill signal must use physical mempool occupancy, never logical tracked bytes");
        assertTrue(probe.usagePercent() < 95.0,
                "aliased over-count must not fabricate a kill-threshold crossing");
    }

    @Test
    void physicalCardExhaustionEscalatesEvenWhenMempoolAccountingLags() {
        // Driver reports <1% free (64MB of 24GB): allocations are failing regardless of
        // accounting, so the probe escalates to driver-wide used (total - free).
        long expectedUsed = (24 * GIB) - (64L * 1024L * 1024L);
        SubprocessMemoryWatchdog.GpuProbe probe =
                SubprocessMemoryWatchdog.processOwnedGpuProbe(
                        0, 24 * GIB, 64L * 1024L * 1024L, GIB, 2 * GIB);

        assertNotNull(probe);
        assertEquals(expectedUsed, probe.usedBytes(),
                "near-total physical exhaustion must escalate used to the driver-wide value");
        assertTrue(probe.usagePercent() > 99.0,
                "escalated driver-wide used must read as effectively full");
    }

    @Test
    void genuineProcessOwnedSaturationStillCrossesTheKillThreshold() {
        // 512MB free (~2.1%, above override): mempool honestly reports 23GB → >92%.
        SubprocessMemoryWatchdog.GpuProbe probe =
                SubprocessMemoryWatchdog.processOwnedGpuProbe(
                        0, 24 * GIB, 512L * 1024L * 1024L, 22 * GIB, 23 * GIB);

        assertNotNull(probe);
        assertEquals(23 * GIB, probe.usedBytes());
        assertTrue(probe.usagePercent() > 92.0);
    }

    @Test
    void mempoolTelemetryUnavailableFallsBackToDriverWideUsed() {
        SubprocessMemoryWatchdog.GpuProbe probe =
                SubprocessMemoryWatchdog.processOwnedGpuProbe(
                        1, 8 * GIB, GIB, 3 * GIB, -1);

        assertNotNull(probe);
        // nativePoolUsed=-1 → conservative driver-wide fallback (8GB - 1GB = 7GB),
        // NOT the logical tracked bytes.
        assertEquals(7 * GIB, probe.usedBytes());
        assertEquals(87.5, probe.usagePercent(), 0.0001);
    }

    @Test
    void multiDeviceThresholdUsesWorstDeviceInsteadOfAggregateUtilization() {
        SubprocessMemoryWatchdog.GpuProbe idleDevice =
                SubprocessMemoryWatchdog.processOwnedGpuProbe(
                        0, 24 * GIB, 23 * GIB, GIB, GIB);
        SubprocessMemoryWatchdog.GpuProbe saturatedDevice =
                SubprocessMemoryWatchdog.processOwnedGpuProbe(
                        1, 24 * GIB, 4 * GIB, 19 * GIB, 20 * GIB);

        SubprocessMemoryWatchdog.GpuProbe highest =
                SubprocessMemoryWatchdog.highestUsageGpuProbe(
                java.util.List.of(idleDevice, saturatedDevice));

        assertNotNull(highest);
        assertEquals(1, highest.deviceId());
        assertTrue(highest.usagePercent() > 80.0,
                "an idle sibling GPU must not dilute the limiting device below its threshold");
        double aggregateUsage =
                ((idleDevice.usedBytes() + saturatedDevice.usedBytes()) * 100.0)
                        / (idleDevice.totalBytes() + saturatedDevice.totalBytes());
        assertTrue(aggregateUsage < 50.0,
                "the regression setup must demonstrate why aggregate utilization is unsafe");
    }

    @Test
    void unavailableOwnershipDoesNotFallBackToDriverWideOccupancy() {
        assertNull(SubprocessMemoryWatchdog.processOwnedGpuProbe(
                0, 24 * GIB, 1, 0, -1));
    }
}
