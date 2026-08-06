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
        SubprocessMemoryWatchdog.GpuProbe probe =
                SubprocessMemoryWatchdog.processOwnedGpuProbe(
                        0, 24 * GIB, 64L * 1024L * 1024L, GIB, 2 * GIB);

        assertNotNull(probe);
        assertEquals(2 * GIB, probe.usedBytes());
        assertTrue(probe.driverUsedBytes() > 23 * GIB,
                "driver telemetry should still expose external occupancy for diagnostics");
        assertTrue(probe.usagePercent() < 10.0,
                "kill decisions must use process-owned memory, not the saturated device total");
    }

    @Test
    void genuineProcessOwnedSaturationStillCrossesTheKillThreshold() {
        SubprocessMemoryWatchdog.GpuProbe probe =
                SubprocessMemoryWatchdog.processOwnedGpuProbe(
                        0, 24 * GIB, 64L * 1024L * 1024L, 22 * GIB, 23 * GIB);

        assertNotNull(probe);
        assertEquals(23 * GIB, probe.usedBytes());
        assertTrue(probe.usagePercent() > 92.0);
    }

    @Test
    void trackedAllocationsRemainUsableWhenNativePoolTelemetryIsUnavailable() {
        SubprocessMemoryWatchdog.GpuProbe probe =
                SubprocessMemoryWatchdog.processOwnedGpuProbe(
                        1, 8 * GIB, GIB, 3 * GIB, -1);

        assertNotNull(probe);
        assertEquals(3 * GIB, probe.usedBytes());
        assertEquals(37.5, probe.usagePercent(), 0.0001);
    }

    @Test
    void unavailableOwnershipDoesNotFallBackToDriverWideOccupancy() {
        assertNull(SubprocessMemoryWatchdog.processOwnedGpuProbe(
                0, 24 * GIB, 1, 0, -1));
    }
}
