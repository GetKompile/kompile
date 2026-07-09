/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.calibration;

import ai.kompile.graph.reasoning.fol.grounding.calibration.IsotonicCalibrator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link IsotonicCalibrator} — PAVA isotonic regression.
 */
@DisplayName("IsotonicCalibrator (E14) tests")
class IsotonicCalibratorTest {

    private static final double EPS = 1e-9;

    // ─── Basic fit ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Perfect monotone data: breakpoints match mean outcome per group")
    void perfectMonotoneData() {
        IsotonicCalibrator cal = new IsotonicCalibrator();
        // All outcomes strictly increasing with score — no merging needed
        cal.addSample(0.1, false); // outcome 0.0
        cal.addSample(0.5, false); // outcome 0.0
        cal.addSample(0.8, true);  // outcome 1.0
        cal.addSample(0.9, true);  // outcome 1.0
        cal.fit();

        assertTrue(cal.isFitted());
        // Below left breakpoint: clamp
        assertEquals(0.0, cal.calibrate(0.0), EPS);
        // Above right breakpoint: clamp to 1.0
        assertEquals(1.0, cal.calibrate(1.0), EPS);
        // Internal: monotone non-decreasing
        assertTrue(cal.calibrate(0.5) <= cal.calibrate(0.8),
                "Calibrate must be non-decreasing");
    }

    @Test
    @DisplayName("All positive outcomes: calibrate returns 1.0 for any input")
    void allPositiveOutcomes() {
        IsotonicCalibrator cal = new IsotonicCalibrator();
        cal.addSample(0.2, true);
        cal.addSample(0.6, true);
        cal.addSample(0.9, true);
        cal.fit();

        // Every block has average outcome = 1.0
        assertEquals(1.0, cal.calibrate(0.0), EPS);
        assertEquals(1.0, cal.calibrate(0.5), EPS);
        assertEquals(1.0, cal.calibrate(1.0), EPS);
    }

    @Test
    @DisplayName("All negative outcomes: calibrate returns 0.0 for any input")
    void allNegativeOutcomes() {
        IsotonicCalibrator cal = new IsotonicCalibrator();
        cal.addSample(0.1, false);
        cal.addSample(0.4, false);
        cal.addSample(0.7, false);
        cal.fit();

        assertEquals(0.0, cal.calibrate(0.0), EPS);
        assertEquals(0.0, cal.calibrate(0.5), EPS);
        assertEquals(0.0, cal.calibrate(1.0), EPS);
    }

    // ─── PAVA merging ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Violation triggers merge: high score mapped to low outcome → merge")
    void violationMerge() {
        // Score 0.3 → true, Score 0.7 → false: violates monotonicity → should merge
        IsotonicCalibrator cal = new IsotonicCalibrator();
        cal.addSample(0.3, true);  // avg 1.0
        cal.addSample(0.7, false); // avg 0.0 — this violates monotone ↑ from prev block
        cal.fit();

        // After merge: one block with avg x=(0.3+0.7)/2=0.5, avg y=(1+0)/2=0.5
        // All points clamp to 0.5
        assertEquals(0.5, cal.calibrate(0.0), EPS);
        assertEquals(0.5, cal.calibrate(1.0), EPS);
        // Single breakpoint: bpX.length == 1
        assertEquals(1, cal.breakpointX().length);
        assertEquals(0.5, cal.breakpointX()[0], EPS);
        assertEquals(0.5, cal.breakpointY()[0], EPS);
    }

    @Test
    @DisplayName("Interleaved violations produce correct merged blocks")
    void interleavedViolations() {
        // Scores:  0.1→1, 0.2→0, 0.5→1, 0.8→0
        // Expected PAVA output: one merged block (all violate) with avgY = (1+0+1+0)/4 = 0.5
        // OR two blocks if pairs resolve first — need to check by algorithm:
        // Step 1: add (0.1→1): blocks=[{0.1,1,1}]
        // Step 2: add (0.2→0): blocks=[{0.1,1,1},{0.2,0,1}]; avg(prev)=1>avg(cur)=0 → merge
        //         merged={0.3,1,2} avgY=0.5
        // Step 3: add (0.5→1): blocks=[{0.3,1,2},{0.5,1,1}]; avg(prev)=0.5<=avg(cur)=1 → ok
        // Step 4: add (0.8→0): blocks=[{0.3,1,2},{0.5,1,1},{0.8,0,1}]; avg(last-1)=1>avg(cur)=0 → merge
        //         merged cur+prev: {1.3,1,2} avgY=0.5; check again: {0.3,1,2} avg=0.5 <= 0.5 → ok
        // Final blocks: [{0.3,1,2,avgX=0.15,avgY=0.5},{1.3,1,2,avgX=0.65,avgY=0.5}]
        // Both have avgY=0.5 (monotone non-decreasing satisfied)
        IsotonicCalibrator cal = new IsotonicCalibrator();
        cal.addSample(0.1, true);
        cal.addSample(0.2, false);
        cal.addSample(0.5, true);
        cal.addSample(0.8, false);
        cal.fit();

        // Both breakpoints should have Y=0.5
        double[] bpY = cal.breakpointY();
        for (double y : bpY) {
            assertEquals(0.5, y, EPS, "All blocks should average to 0.5");
        }
        // Monotone non-decreasing check
        double[] bpX = cal.breakpointX();
        for (int i = 1; i < bpX.length; i++) {
            assertTrue(bpX[i] >= bpX[i - 1], "bpX must be non-decreasing");
        }
    }

    // ─── Single sample ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Single sample: calibrate always returns that outcome")
    void singleSample() {
        IsotonicCalibrator cal = new IsotonicCalibrator();
        cal.addSample(0.6, true);
        cal.fit();

        // bpY[0] = 1.0
        assertEquals(1.0, cal.calibrate(0.0), EPS);
        assertEquals(1.0, cal.calibrate(0.6), EPS);
        assertEquals(1.0, cal.calibrate(1.0), EPS);
        assertEquals(1, cal.breakpointX().length);
    }

    // ─── Interpolation ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Linear interpolation between breakpoints")
    void linearInterpolation() {
        // Two separated blocks, no merging needed
        IsotonicCalibrator cal = new IsotonicCalibrator();
        cal.addSample(0.0, false); // bp: x=0.0, y=0.0
        cal.addSample(1.0, true);  // bp: x=1.0, y=1.0
        cal.fit();

        // At midpoint: exactly 0.5
        assertEquals(0.5, cal.calibrate(0.5), 1e-9);
        // At 0.25: should be 0.25
        assertEquals(0.25, cal.calibrate(0.25), 1e-9);
        // At 0.75: should be 0.75
        assertEquals(0.75, cal.calibrate(0.75), 1e-9);
    }

    // ─── No-samples guard ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Fit with no samples throws IllegalStateException")
    void fitWithNoSamples() {
        IsotonicCalibrator cal = new IsotonicCalibrator();
        assertThrows(IllegalStateException.class, cal::fit);
    }

    @Test
    @DisplayName("Calibrate before fit throws IllegalStateException")
    void calibrateBeforeFit() {
        IsotonicCalibrator cal = new IsotonicCalibrator();
        assertThrows(IllegalStateException.class, () -> cal.calibrate(0.5));
    }

    // ─── Serialization round-trip ─────────────────────────────────────────────────

    @Test
    @DisplayName("serialize/deserialize round-trip preserves calibration")
    void serializeRoundTrip() {
        IsotonicCalibrator original = new IsotonicCalibrator();
        original.addSample(0.1, false);
        original.addSample(0.4, false);
        original.addSample(0.6, true);
        original.addSample(0.9, true);
        original.fit();

        String csv = original.serialize();
        assertNotNull(csv);
        assertFalse(csv.isBlank(), "Serialized CSV must not be blank");

        IsotonicCalibrator restored = IsotonicCalibrator.deserialize(csv);
        assertTrue(restored.isFitted());

        // Calibration outputs must be identical
        double[] testPoints = {0.0, 0.1, 0.3, 0.5, 0.7, 0.9, 1.0};
        for (double x : testPoints) {
            assertEquals(original.calibrate(x), restored.calibrate(x), EPS,
                    "Calibration at " + x + " must match after round-trip");
        }
    }

    @Test
    @DisplayName("serialize before fit throws IllegalStateException")
    void serializeBeforeFit() {
        IsotonicCalibrator cal = new IsotonicCalibrator();
        cal.addSample(0.5, true);
        assertThrows(IllegalStateException.class, cal::serialize);
    }

    @Test
    @DisplayName("deserialize null/blank throws IllegalArgumentException")
    void deserializeNullBlank() {
        assertThrows(IllegalArgumentException.class, () -> IsotonicCalibrator.deserialize(null));
        assertThrows(IllegalArgumentException.class, () -> IsotonicCalibrator.deserialize("  "));
    }

    @Test
    @DisplayName("deserialize malformed entry throws IllegalArgumentException")
    void deserializeMalformed() {
        assertThrows(IllegalArgumentException.class, () -> IsotonicCalibrator.deserialize("bad-entry"));
        assertThrows(IllegalArgumentException.class, () -> IsotonicCalibrator.deserialize("0.5:notanumber"));
    }

    // ─── sampleCount accessor ─────────────────────────────────────────────────────

    @Test
    @DisplayName("sampleCount returns number of added samples")
    void sampleCount() {
        IsotonicCalibrator cal = new IsotonicCalibrator();
        assertEquals(0, cal.sampleCount());
        cal.addSample(0.3, true);
        cal.addSample(0.7, false);
        assertEquals(2, cal.sampleCount());
    }
}
