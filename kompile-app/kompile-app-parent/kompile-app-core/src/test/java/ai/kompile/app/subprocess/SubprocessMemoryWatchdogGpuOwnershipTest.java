/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.app.subprocess;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubprocessMemoryWatchdogGpuOwnershipTest {

    private static final long GIB = 1024L * 1024L * 1024L;
    private static final long MIB = 1024L * 1024L;

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
        // accounting, but escalation to driver-wide used (total - free) only fires when this
        // process is plausibly the DOMINANT TENANT (mempool used * 2 >= driver-wide used) - see
        // the co-tenant tests below for the case where it must NOT fire. Driver-wide used here
        // is ~23.94GB, so a 14GB pool clears the half-of-that bar and escalation still applies.
        long expectedUsed = (24 * GIB) - (64L * 1024L * 1024L);
        SubprocessMemoryWatchdog.GpuProbe probe =
                SubprocessMemoryWatchdog.processOwnedGpuProbe(
                        0, 24 * GIB, 64L * 1024L * 1024L, GIB, 14 * GIB);

        assertNotNull(probe);
        assertEquals(expectedUsed, probe.usedBytes(),
                "near-total physical exhaustion must escalate used to the driver-wide value "
                        + "when this process is the dominant tenant");
        assertTrue(probe.usagePercent() > 99.0,
                "escalated driver-wide used must read as effectively full");
    }

    @Test
    void coTenantFillingTheCardDoesNotKillAnInnocentProcess() {
        // A co-tenant has filled the card to <1% free (45MB of 24084MB free), but this
        // process's own mempool holds only 64MB - nowhere near "dominant tenant" (half of the
        // ~24039MB driver-wide used). The card-exhaustion override must not escalate: this
        // process keeps its own honest 64MB reading, exactly the 2026-09-23 regression this
        // guards against (see the processOwnedGpuProbe javadoc).
        SubprocessMemoryWatchdog.GpuProbe probe =
                SubprocessMemoryWatchdog.processOwnedGpuProbe(
                        0, 24084 * MIB, 45 * MIB, 0, 64 * MIB);

        assertNotNull(probe);
        assertEquals(64 * MIB, probe.usedBytes(),
                "a co-tenant filling the card must not be charged to an innocent process");
        assertEquals(24039 * MIB, probe.driverUsedBytes(),
                "driver telemetry should still expose the card-wide occupancy for diagnostics");
        assertTrue(probe.usagePercent() < 1.0,
                "this process's own usage percent must reflect its own 64MB, not the full card");
    }

    @Test
    void fullSiblingGpuWeNeverUsedIsNotEscalatedByCoTenants() {
        // Device 1 is a full sibling GPU (other processes have filled it to <1% free, a REAL
        // driver reading, not a failed query) that this process never allocated on: our own
        // mempool genuinely reads 0. Dominant-tenant requires mempoolUsed*2 >= driverUsed; 0 can
        // never clear that bar for a positive driver-wide used, so this must stay at 0 rather
        // than being escalated to the sibling's occupancy.
        SubprocessMemoryWatchdog.GpuProbe probe =
                SubprocessMemoryWatchdog.processOwnedGpuProbe(
                        1, 8 * GIB, 60 * MIB, 0, 0);

        assertNotNull(probe);
        assertEquals(0, probe.usedBytes());
        assertEquals(0.0, probe.usagePercent(), 0.0001);
    }

    @Test
    void dominantTenantBoundaryAtExactlyHalfEscalates() {
        // total=8GiB, free=64MB -> driver-wide used = 8128MB. A pool of 4064MB is EXACTLY half
        // of that - the ">=" boundary in the dominant-tenant check - so escalation still fires.
        SubprocessMemoryWatchdog.GpuProbe probe =
                SubprocessMemoryWatchdog.processOwnedGpuProbe(
                        0, 8 * GIB, 64 * MIB, 0, 4064 * MIB);

        assertNotNull(probe);
        assertEquals(8128 * MIB, probe.usedBytes(),
                "pool at exactly half of driver-wide used must still count as dominant tenant");
    }

    @Test
    void dominantTenantBoundaryJustBelowHalfDoesNotEscalate() {
        // Same card, pool one MB short of half (4063MB of the 8128MB driver-wide used): not
        // dominant, so the probe keeps its own honest (lower) mempool reading, unescalated.
        SubprocessMemoryWatchdog.GpuProbe probe =
                SubprocessMemoryWatchdog.processOwnedGpuProbe(
                        0, 8 * GIB, 64 * MIB, 0, 4063 * MIB);

        assertNotNull(probe);
        assertEquals(4063 * MIB, probe.usedBytes(),
                "pool just below half of driver-wide used must not escalate");
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
                List.of(idleDevice, saturatedDevice));

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

    @Test
    void failedFreeMemoryQueryAfterACudaFaultIsNotAFullCard() {
        // After a CUDA 700 every CUDA call fails: the free-memory query returns 0, the total
        // falls back to the device properties (24084MB on the 4090), and the mempool query
        // reads 0 too. Taking that 0 as "0 bytes free" reported 100% (24084MB/24084MB) and
        // halted the process as a GPU memory kill.
        SubprocessMemoryWatchdog.GpuProbe probe =
                SubprocessMemoryWatchdog.processOwnedGpuProbe(
                        0, 24084 * MIB, 0, 20 * GIB, 0);

        assertNotNull(probe);
        assertEquals(0, probe.usedBytes());
        assertEquals(-1, probe.driverUsedBytes(), "a failed query is not a driver reading");
        assertTrue(probe.usagePercent() < 92.0,
                "a failed free-memory query must not cross the kill threshold");
    }

    @Test
    void failedFreeMemoryQueryKeepsTheMempoolReading() {
        // Same failed query while the mempool still reports 18GB: the probe keeps the
        // mempool value and skips the card-exhaustion override.
        SubprocessMemoryWatchdog.GpuProbe probe =
                SubprocessMemoryWatchdog.processOwnedGpuProbe(
                        0, 24084 * MIB, 0, 20 * GIB, 18 * GIB);

        assertNotNull(probe);
        assertEquals(18 * GIB, probe.usedBytes());
        assertTrue(probe.usagePercent() < 92.0);
    }

    @Test
    void unusedSiblingGpuThatCannotCreateAContextDoesNotKillThisProcess() {
        // The watchdog probes every visible GPU. A full sibling this process never used cannot
        // create a context, so its free-memory query fails (0) while our pool there reads 0.
        SubprocessMemoryWatchdog.GpuProbe probe =
                SubprocessMemoryWatchdog.processOwnedGpuProbe(
                        1, 8 * GIB, 0, 0, 0);

        assertNotNull(probe);
        assertEquals(0, probe.usedBytes());
        assertEquals(0.0, probe.usagePercent(), 0.0001);
    }

    @Test
    void failedFreeMemoryQueryWithoutMempoolTelemetryGivesNoProbe() {
        // No physical signal is left, and logical tracked bytes must not stand in for one.
        assertNull(SubprocessMemoryWatchdog.processOwnedGpuProbe(
                0, 24084 * MIB, 0, 20 * GIB, -1));
    }
}
