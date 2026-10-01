/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.app.subprocess;

import ai.kompile.app.subprocess.SubprocessMemoryWatchdog.GpuProbe;
import ai.kompile.app.subprocess.SubprocessMemoryWatchdog.MemorySnapshot;
import ai.kompile.app.subprocess.SubprocessMemoryWatchdog.Readings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flag and velocity state of {@link SubprocessMemoryWatchdog#evaluate} driven tick by tick with
 * synthetic {@link Readings}. The watchdog is never started, so nothing here touches ND4J,
 * NativeOps, or JavaCPP. Thresholds are 80/90/95 (stop/critical/kill) for heap, GPU, and
 * off-heap alike; ticks are one second apart.
 */
class SubprocessMemoryWatchdogFlagStateTest {

    private static final long MIB = 1024L * 1024L;
    private static final long HEAP_MAX = 1000 * MIB;
    private static final long OFF_HEAP_LIMIT = 1000 * MIB;
    private static final long GPU_TOTAL = 8 * 1024 * MIB;
    private static final double EPS = 1e-9;

    private final List<SubprocessMemoryWatchdog> watchdogs = new ArrayList<>();
    private long now = 1_000L;
    private int gcSuggestions;

    @AfterEach
    void closeWatchdogs() {
        watchdogs.forEach(SubprocessMemoryWatchdog::close);
    }

    @Test
    void gpuVelocityIsMeasuredEachTickAndRapidGrowthSetsThenClears() {
        SubprocessMemoryWatchdog w = watchdog(0);
        tick(w, 10, 0, gpu(0, 10), gpu(1, 5));
        assertEquals(0.0, w.getGpuVelocityPercentPerSecond(), EPS, "the first tick has nothing to diff against");

        tick(w, 10, 0, gpu(0, 30), gpu(1, 5));
        assertEquals(20.0, w.getGpuVelocityPercentPerSecond(), EPS);
        assertTrue(w.isRapidMemoryGrowth());

        tick(w, 10, 0, gpu(0, 30), gpu(1, 5));
        assertEquals(0.0, w.getGpuVelocityPercentPerSecond(), EPS);
        assertFalse(w.isRapidMemoryGrowth());
    }

    @Test
    void gpuVelocityDiffsEachDeviceAgainstItselfNotWorstAgainstWorst() {
        SubprocessMemoryWatchdog w = watchdog(0);
        tick(w, 10, 0, gpu(0, 80), gpu(1, 10));
        tick(w, 10, 0, gpu(0, 5), gpu(1, 75));

        // Worst-vs-worst would diff device 1's 75 against device 0's 80 and read -5%/s.
        assertEquals(65.0, w.getGpuVelocityPercentPerSecond(), EPS);
        assertTrue(w.isRapidMemoryGrowth());
        assertEquals(1, w.getLastSnapshot().gpuDeviceId());
        assertEquals(75.0, w.getLastSnapshot().gpuUsagePercent(), EPS);
    }

    @Test
    void deviceMissingFromATickLosesItsHistory() {
        SubprocessMemoryWatchdog w = watchdog(0);
        tick(w, 10, 0, gpu(0, 10), gpu(1, 10));
        tick(w, 10, 0, gpu(0, 10)); // device 1 gave no reading this tick
        tick(w, 10, 0, gpu(0, 10), gpu(1, 60));

        assertEquals(0.0, w.getGpuVelocityPercentPerSecond(), EPS,
                "device 1 must not diff 60 against its pre-gap 10");
        assertFalse(w.isRapidMemoryGrowth());

        tick(w, 10, 0, gpu(0, 10), gpu(1, 70));
        assertEquals(10.0, w.getGpuVelocityPercentPerSecond(), EPS, "history resumes once the device reports again");
    }

    @Test
    void snapshotCarriesTheTicksVelocities() {
        SubprocessMemoryWatchdog w = watchdog(OFF_HEAP_LIMIT);
        tick(w, 10, 10, gpu(0, 10), gpu(1, 5));
        tick(w, 20, 25, gpu(0, 30), gpu(1, 5));

        MemorySnapshot s = w.getLastSnapshot();
        assertEquals(20.0, s.usagePercent(), EPS);
        assertEquals(10.0, s.heapVelocityPercentPerSecond(), EPS);
        assertEquals(20.0, s.gpuVelocityPercentPerSecond(), EPS);
        assertEquals(0, s.gpuDeviceId());
        assertEquals(30.0, s.gpuUsagePercent(), EPS);
        assertEquals(250, s.javacppMB());
        assertEquals(1000, s.offHeapMaxMB());
        assertEquals(25.0, s.offHeapUsagePercent(), EPS);
        assertEquals(15.0, s.offHeapVelocityPercentPerSecond(), EPS);
        assertEquals(w.getHeapVelocityPercentPerSecond(), s.heapVelocityPercentPerSecond(), EPS);
        assertEquals(w.getGpuVelocityPercentPerSecond(), s.gpuVelocityPercentPerSecond(), EPS);
    }

    @Test
    void heapStopSurvivesAHealthyGpu() {
        SubprocessMemoryWatchdog w = watchdog(0);
        tick(w, 85, 0, gpu(0, 10), gpu(1, 10));
        tick(w, 85, 0, gpu(0, 10), gpu(1, 10));
        assertFalse(w.shouldStop(), "stop is debounced over three ticks");

        tick(w, 85, 0, gpu(0, 10), gpu(1, 10));
        assertTrue(w.shouldStop());

        tick(w, 85, 0, gpu(0, 10), gpu(1, 10));
        assertTrue(w.shouldStop(), "a healthy GPU must not clear a heap stop");
    }

    @Test
    void gpuStopSurvivesAHealthyHeapAndClearsBelowThreshold() {
        SubprocessMemoryWatchdog w = watchdog(0);
        tick(w, 10, 0, gpu(0, 85), gpu(1, 10));
        tick(w, 10, 0, gpu(0, 85), gpu(1, 10));
        assertFalse(w.shouldStop());

        tick(w, 10, 0, gpu(0, 85), gpu(1, 10));
        assertTrue(w.shouldStop());

        tick(w, 10, 0, gpu(0, 85), gpu(1, 10));
        assertTrue(w.shouldStop(), "a healthy heap must not clear a GPU stop");

        tick(w, 10, 0, gpu(0, 79), gpu(1, 10));
        assertFalse(w.shouldStop(), "GPU stop clears as soon as usage is back under the threshold");
    }

    @Test
    void heapStopKeepsItsTenPointHysteresisBand() {
        SubprocessMemoryWatchdog w = watchdog(0);
        tick(w, 85, 0, gpu(0, 10), gpu(1, 10));
        tick(w, 85, 0, gpu(0, 10), gpu(1, 10));
        tick(w, 85, 0, gpu(0, 10), gpu(1, 10));
        assertTrue(w.shouldStop());

        tick(w, 75, 0, gpu(0, 10), gpu(1, 10));
        assertTrue(w.shouldStop(), "75% is still inside the heap's -10 band (clears under 70%)");

        tick(w, 69, 0, gpu(0, 10), gpu(1, 10));
        assertFalse(w.shouldStop());
    }

    @Test
    void aHeldHeapStopGetsARateLimitedGcSoItCanClear() {
        SubprocessMemoryWatchdog w = watchdog(0);
        tick(w, 85, 0, gpu(0, 10), gpu(1, 10));
        assertEquals(1, gcSuggestions, "the first quiet tick over the stop threshold suggests a GC");

        tick(w, 85, 0, gpu(0, 10), gpu(1, 10));
        tick(w, 85, 0, gpu(0, 10), gpu(1, 10));
        assertTrue(w.shouldStop(), "the heap stayed over the threshold after the GC, so the stop sets");
        assertEquals(1, gcSuggestions, "at most one suggestion per interval");

        // Held inside the -10 band on garbage: a gated process allocates nothing, so nothing
        // but the watchdog's GC would ever collect it.
        tick(w, 75, 0, gpu(0, 10), gpu(1, 10));
        assertEquals(1, gcSuggestions);
        now += 30_000;
        tick(w, 75, 0, gpu(0, 10), gpu(1, 10));
        assertEquals(2, gcSuggestions, "a held stop gets another GC once the interval has passed");

        tick(w, 40, 0, gpu(0, 10), gpu(1, 10));
        assertFalse(w.shouldStop(), "the collected reading clears the stop");
    }

    @Test
    void heapGcIsDeferredMidBurstAndSuggestedOncePerTick() {
        SubprocessMemoryWatchdog w = watchdog(0);
        tick(w, 50, 0, gpu(0, 10), gpu(1, 10));
        tick(w, 92, 0, gpu(0, 10), gpu(1, 10)); // +42%/s: a growth burst
        assertEquals(0, gcSuggestions, "no GC mid-burst from either the critical or the stop path");

        tick(w, 92, 0, gpu(0, 10), gpu(1, 10));
        assertEquals(1, gcSuggestions, "the quiet tick gets one GC, not one per path");
    }

    @Test
    void aClockSteppingBackDoesNotHoldOffTheNextHeapGc() {
        SubprocessMemoryWatchdog w = watchdog(0);
        tickAt(w, 100_000, 85, 0, gpu(0, 10), gpu(1, 10));
        assertEquals(1, gcSuggestions);

        tickAt(w, 50_000, 85, 0, gpu(0, 10), gpu(1, 10)); // wall clock stepped back 50s
        assertEquals(2, gcSuggestions, "a negative interval can't be measured, so it doesn't hold the GC off");

        tickAt(w, 51_000, 85, 0, gpu(0, 10), gpu(1, 10));
        assertEquals(2, gcSuggestions, "the rate limit resumes from the stepped-back suggestion");
    }

    @Test
    void criticalIsTrackedPerDimension() {
        SubprocessMemoryWatchdog w = watchdog(OFF_HEAP_LIMIT);
        tick(w, 92, 10, gpu(0, 10), gpu(1, 10));
        assertTrue(w.isCriticalMemory(), "a healthy GPU must not clear a heap critical");

        tick(w, 10, 10, gpu(0, 92), gpu(1, 10));
        assertTrue(w.isCriticalMemory(), "heap recovering must not clear a GPU critical");

        tick(w, 10, 10, gpu(0, 50), gpu(1, 10));
        assertFalse(w.isCriticalMemory());

        tick(w, 10, 92, gpu(0, 50), gpu(1, 10));
        assertTrue(w.isCriticalMemory());

        tick(w, 10, 50, gpu(0, 50), gpu(1, 10));
        assertFalse(w.isCriticalMemory(), "off-heap critical clears once usage recovers");
    }

    @Test
    void offHeapRapidCriticalAndStopEachSetAndClear() {
        SubprocessMemoryWatchdog w = watchdog(OFF_HEAP_LIMIT);
        tick(w, 10, 10, gpu(0, 10), gpu(1, 10));
        tick(w, 10, 30, gpu(0, 10), gpu(1, 10));
        assertTrue(w.isRapidMemoryGrowth(), "off-heap grew 20%/s");

        tick(w, 10, 30, gpu(0, 10), gpu(1, 10));
        assertFalse(w.isRapidMemoryGrowth());

        tick(w, 10, 85, gpu(0, 10), gpu(1, 10));
        tick(w, 10, 85, gpu(0, 10), gpu(1, 10));
        assertFalse(w.shouldStop());

        tick(w, 10, 85, gpu(0, 10), gpu(1, 10));
        assertTrue(w.shouldStop());
        assertFalse(w.isCriticalMemory());

        tick(w, 10, 92, gpu(0, 10), gpu(1, 10));
        assertTrue(w.isCriticalMemory());
        assertTrue(w.shouldStop());

        tick(w, 10, 79, gpu(0, 10), gpu(1, 10));
        assertFalse(w.isCriticalMemory());
        assertFalse(w.shouldStop(), "off-heap stop clears below its threshold");
        assertFalse(w.isRapidMemoryGrowth());
    }

    @Test
    void killIsTerminal() {
        SubprocessMemoryWatchdog w = watchdog(0);
        tick(w, 10, 0, gpu(0, 96), gpu(1, 10));
        assertFalse(w.shouldKill(), "kill is debounced over two ticks");

        tick(w, 10, 0, gpu(0, 96), gpu(1, 10));
        assertTrue(w.shouldKill());
        assertTrue(w.shouldStop());

        for (int i = 0; i < 5; i++) {
            tick(w, 10, 0, gpu(0, 10), gpu(1, 10));
        }
        assertTrue(w.shouldKill(), "kill never clears on recovery");
        assertTrue(w.shouldStop(), "shouldStop stays published while kill holds");
        assertFalse(w.isCriticalMemory(), "critical still follows the live reading");
    }

    @Test
    void tickWithoutGpuProbesKeepsTheGpuStopAndForgetsVelocityHistory() {
        SubprocessMemoryWatchdog w = watchdog(0);
        tick(w, 10, 0, gpu(0, 85), gpu(1, 10));
        tick(w, 10, 0, gpu(0, 85), gpu(1, 10));
        tick(w, 10, 0, gpu(0, 85), gpu(1, 10));
        assertTrue(w.shouldStop());

        tick(w, 10, 0); // no device answered this tick
        assertTrue(w.shouldStop(), "no reading is no evidence of recovery");
        assertEquals(0.0, w.getGpuVelocityPercentPerSecond(), EPS);
        assertEquals(-1, w.getLastSnapshot().gpuDeviceId());

        tick(w, 10, 0, gpu(0, 89), gpu(1, 10));
        assertEquals(0.0, w.getGpuVelocityPercentPerSecond(), EPS,
                "history was forgotten across the gap, so 89 is not diffed against the pre-gap 85");
        assertTrue(w.shouldStop());

        tick(w, 10, 0, gpu(0, 79), gpu(1, 10));
        assertFalse(w.shouldStop());
    }

    @Test
    void resetClearsFlagsVelocitiesAndHistory() {
        SubprocessMemoryWatchdog w = watchdog(OFF_HEAP_LIMIT);
        tick(w, 85, 10, gpu(0, 10), gpu(1, 10));
        tick(w, 85, 30, gpu(0, 30), gpu(1, 10));
        tick(w, 85, 30, gpu(0, 92), gpu(1, 10));
        assertTrue(w.shouldStop());
        assertTrue(w.isCriticalMemory());
        assertTrue(w.isRapidMemoryGrowth());

        w.reset();
        assertFalse(w.shouldStop());
        assertFalse(w.shouldKill());
        assertFalse(w.isCriticalMemory());
        assertFalse(w.isRapidMemoryGrowth());
        assertEquals(0.0, w.getHeapVelocityPercentPerSecond(), EPS);
        assertEquals(0.0, w.getGpuVelocityPercentPerSecond(), EPS);

        tick(w, 85, 60, gpu(0, 60), gpu(1, 10));
        assertEquals(0.0, w.getGpuVelocityPercentPerSecond(), EPS, "no pre-reset baseline to diff against");
        assertEquals(0.0, w.getHeapVelocityPercentPerSecond(), EPS);
        assertEquals(0.0, w.getLastSnapshot().offHeapVelocityPercentPerSecond(), EPS);
        assertFalse(w.isRapidMemoryGrowth());
        assertFalse(w.shouldStop(), "the heap stop debounce restarts after a reset");
        assertEquals(2, gcSuggestions, "reset forgets the heap GC rate limit");
    }

    @Test
    void clockSteppingBackReportsZeroVelocityThenResumes() {
        SubprocessMemoryWatchdog w = watchdog(0);
        tickAt(w, 10_000, 10, 0, gpu(0, 10), gpu(1, 10));
        tickAt(w, 9_000, 10, 0, gpu(0, 30), gpu(1, 10)); // wall clock stepped back one second
        assertEquals(0.0, w.getGpuVelocityPercentPerSecond(), EPS, "no forward time, no velocity");

        tickAt(w, 10_000, 10, 0, gpu(0, 50), gpu(1, 10));
        assertEquals(20.0, w.getGpuVelocityPercentPerSecond(), EPS,
                "velocity resumes against the stepped-back reading instead of freezing until the clock passes 10s");
    }

    /**
     * GPU monitoring on with two devices; never started. Kill must never halt the test JVM, and GC
     * suggestions are counted, never run.
     */
    private SubprocessMemoryWatchdog watchdog(long offHeapLimitBytes) {
        SubprocessMemoryWatchdog w = new SubprocessMemoryWatchdog(
                80, 90, 95, 1000,
                80, 90, 95,
                80, 90, 95,
                true, 2, offHeapLimitBytes) {
            @Override
            void suggestGc() {
                gcSuggestions++;
            }
        };
        w.setForceKillOnThreshold(false);
        watchdogs.add(w);
        return w;
    }

    private static GpuProbe gpu(int deviceId, double usagePercent) {
        long used = (long) (GPU_TOTAL * usagePercent / 100.0);
        return new GpuProbe(deviceId, used, GPU_TOTAL, usagePercent, used);
    }

    /** One tick, one second after the previous one. */
    private void tick(SubprocessMemoryWatchdog w, double heapPercent, double offHeapPercent, GpuProbe... probes) {
        now += 1000;
        tickAt(w, now, heapPercent, offHeapPercent, probes);
    }

    private static void tickAt(SubprocessMemoryWatchdog w, long timestampMs, double heapPercent,
                               double offHeapPercent, GpuProbe... probes) {
        long heapUsed = (long) (HEAP_MAX * heapPercent / 100.0);
        long offHeapUsed = (long) (OFF_HEAP_LIMIT * offHeapPercent / 100.0);
        w.evaluate(new Readings(timestampMs, HEAP_MAX, HEAP_MAX, HEAP_MAX - heapUsed, 0L,
                List.of(probes), offHeapUsed, 0L, 0L));
    }
}
