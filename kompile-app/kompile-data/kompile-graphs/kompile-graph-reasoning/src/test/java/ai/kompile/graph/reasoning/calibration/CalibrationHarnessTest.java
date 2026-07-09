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

import ai.kompile.graph.reasoning.fol.grounding.StrengthCalibrator.SignalType;
import ai.kompile.graph.reasoning.fol.grounding.calibration.CalibrationHarness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CalibrationHarness} — multi-signal calibration facade.
 */
@DisplayName("CalibrationHarness (E14) tests")
class CalibrationHarnessTest {

    private static final double EPS = 1e-9;

    // ─── Sample accumulation ──────────────────────────────────────────────────────

    @Test
    @DisplayName("addSample accumulates per signal type")
    void addSampleAccumulates() {
        CalibrationHarness h = new CalibrationHarness();
        h.addSample(SignalType.PSL_SOFT_TRUTH, 0.7, true);
        h.addSample(SignalType.PSL_SOFT_TRUTH, 0.3, false);
        h.addSample(SignalType.MEBN_POSTERIOR, 0.8, true);

        assertEquals(2, h.sampleCount(SignalType.PSL_SOFT_TRUTH));
        assertEquals(1, h.sampleCount(SignalType.MEBN_POSTERIOR));
        assertEquals(0, h.sampleCount(SignalType.ROTATE_DISTANCE));
    }

    // ─── Platt fitting ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("fitPlatt: marks type as fitted, calibrate works")
    void fitPlattBasic() {
        CalibrationHarness h = new CalibrationHarness();
        for (int i = 0; i < 20; i++) {
            h.addSample(SignalType.PSL_SOFT_TRUTH, i / 20.0, i >= 10);
        }
        h.fitPlatt(SignalType.PSL_SOFT_TRUTH);

        assertTrue(h.isPlattFitted(SignalType.PSL_SOFT_TRUTH));
        assertFalse(h.isIsotonicFitted(SignalType.PSL_SOFT_TRUTH));

        // After Platt fit, calibrate should return a value in (0,1)
        double cal = h.calibrate(SignalType.PSL_SOFT_TRUTH, 0.8);
        assertTrue(cal > 0.0 && cal < 1.0, "Platt-calibrated value must be in (0,1)");
    }

    @Test
    @DisplayName("fitPlatt with no samples throws IllegalStateException")
    void fitPlattNoSamples() {
        CalibrationHarness h = new CalibrationHarness();
        assertThrows(IllegalStateException.class, () -> h.fitPlatt(SignalType.MEBN_POSTERIOR));
    }

    // ─── Isotonic fitting ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("fitIsotonic: marks type as fitted, calibrate uses isotonic over platt")
    void fitIsotonicBasic() {
        CalibrationHarness h = new CalibrationHarness();
        h.addSample(SignalType.PSL_SOFT_TRUTH, 0.1, false);
        h.addSample(SignalType.PSL_SOFT_TRUTH, 0.9, true);
        h.fitIsotonic(SignalType.PSL_SOFT_TRUTH);

        assertTrue(h.isIsotonicFitted(SignalType.PSL_SOFT_TRUTH));

        // Isotonic calibrate should be bounded in [0,1]
        double cal = h.calibrate(SignalType.PSL_SOFT_TRUTH, 0.5);
        assertTrue(cal >= 0.0 && cal <= 1.0);
    }

    @Test
    @DisplayName("fitIsotonic with no samples throws IllegalStateException")
    void fitIsotonicNoSamples() {
        CalibrationHarness h = new CalibrationHarness();
        assertThrows(IllegalStateException.class, () -> h.fitIsotonic(SignalType.ROTATE_DISTANCE));
    }

    @Test
    @DisplayName("Isotonic preferred over Platt when both fitted")
    void isotonicPreferredOverPlatt() {
        CalibrationHarness h = new CalibrationHarness();
        // Add a perfectly discriminative sample set
        h.addSample(SignalType.PSL_SOFT_TRUTH, 0.0, false);
        h.addSample(SignalType.PSL_SOFT_TRUTH, 1.0, true);
        h.fitPlatt(SignalType.PSL_SOFT_TRUTH);
        h.fitIsotonic(SignalType.PSL_SOFT_TRUTH);

        // Isotonic with 2 samples: left=0.0→0.0, right=1.0→1.0
        // At raw=0.0: isotonic returns 0.0; Platt would return sigmoid(w*0+b) which may differ
        double isoAtZero = h.calibrate(SignalType.PSL_SOFT_TRUTH, 0.0);
        assertEquals(0.0, isoAtZero, EPS, "Isotonic clamp at left breakpoint = 0.0");
    }

    // ─── Identity fallback ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Calibrate with no fitted calibrator: identity (clamp to [0,1])")
    void identityFallback() {
        CalibrationHarness h = new CalibrationHarness();
        assertEquals(0.7, h.calibrate(SignalType.OBSERVED, 0.7), EPS);
        assertEquals(0.0, h.calibrate(SignalType.OBSERVED, -0.5), EPS, "Clamp below 0");
        assertEquals(1.0, h.calibrate(SignalType.OBSERVED, 1.5), EPS, "Clamp above 1");
    }

    // ─── ECE ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ECE: perfectly calibrated model → ECE ≈ 0")
    void ecePerfectlyCalibratedModel() {
        CalibrationHarness h = new CalibrationHarness();
        // raw score == actual frequency in each bin → ECE should be ~0
        // Use identity calibrator (no fitting): raw=0.1→false, raw=0.9→true
        h.addSample(SignalType.OBSERVED, 0.1, false);
        h.addSample(SignalType.OBSERVED, 0.9, true);

        double ece = h.ece(SignalType.OBSERVED, 10);
        // Two perfectly placed samples: each in their own bin, conf≈outcome → ECE small
        assertTrue(ece < 0.2, "ECE for well-separated samples should be < 0.2, got: " + ece);
    }

    @Test
    @DisplayName("ECE: no samples returns 0.0")
    void eceNoSamples() {
        CalibrationHarness h = new CalibrationHarness();
        assertEquals(0.0, h.ece(SignalType.PSL_SOFT_TRUTH, 10), EPS);
    }

    @Test
    @DisplayName("ECE: bins < 1 throws IllegalArgumentException")
    void eceBinsLessThanOne() {
        CalibrationHarness h = new CalibrationHarness();
        assertThrows(IllegalArgumentException.class, () -> h.ece(SignalType.PSL_SOFT_TRUTH, 0));
    }

    // ─── Reliability table ────────────────────────────────────────────────────────

    @Test
    @DisplayName("reliabilityTable: non-empty bins returned with correct structure")
    void reliabilityTableStructure() {
        CalibrationHarness h = new CalibrationHarness();
        h.addSample(SignalType.PSL_SOFT_TRUTH, 0.2, false);
        h.addSample(SignalType.PSL_SOFT_TRUTH, 0.8, true);

        List<double[]> table = h.reliabilityTable(SignalType.PSL_SOFT_TRUTH, 10);
        assertNotNull(table);
        // Only non-empty bins
        assertFalse(table.isEmpty(), "Table should have at least one entry");
        for (double[] row : table) {
            assertEquals(3, row.length, "Each row must have [meanConf, accuracy, count]");
            assertTrue(row[0] >= 0.0 && row[0] <= 1.0, "meanConf must be in [0,1]");
            assertTrue(row[1] >= 0.0 && row[1] <= 1.0, "accuracy must be in [0,1]");
            assertTrue(row[2] >= 1.0, "count must be at least 1");
        }
    }

    @Test
    @DisplayName("reliabilityTable: bins < 1 throws IllegalArgumentException")
    void reliabilityTableBinsLessThanOne() {
        CalibrationHarness h = new CalibrationHarness();
        assertThrows(IllegalArgumentException.class,
                () -> h.reliabilityTable(SignalType.PSL_SOFT_TRUTH, 0));
    }

    @Test
    @DisplayName("reliabilityTable: empty if no samples")
    void reliabilityTableNoSamples() {
        CalibrationHarness h = new CalibrationHarness();
        assertTrue(h.reliabilityTable(SignalType.MEBN_POSTERIOR, 10).isEmpty());
    }

    // ─── JSON round-trip ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("toJson / fromJson round-trip preserves isotonic calibration")
    void jsonRoundTripIsotonic() {
        CalibrationHarness original = new CalibrationHarness();
        original.addSample(SignalType.PSL_SOFT_TRUTH, 0.1, false);
        original.addSample(SignalType.PSL_SOFT_TRUTH, 0.5, true);
        original.addSample(SignalType.PSL_SOFT_TRUTH, 0.9, true);
        original.fitIsotonic(SignalType.PSL_SOFT_TRUTH);

        String json = original.toJson();
        assertNotNull(json);
        assertFalse(json.isBlank());

        CalibrationHarness restored = CalibrationHarness.fromJson(json);
        assertTrue(restored.isIsotonicFitted(SignalType.PSL_SOFT_TRUTH),
                "Restored harness should have isotonic fitted for PSL_SOFT_TRUTH");

        // Calibration should match
        double[] testPoints = {0.0, 0.3, 0.7, 1.0};
        for (double x : testPoints) {
            double orig = original.calibrate(SignalType.PSL_SOFT_TRUTH, x);
            double rest = restored.calibrate(SignalType.PSL_SOFT_TRUTH, x);
            assertEquals(orig, rest, EPS,
                    "Calibration at " + x + " must match after JSON round-trip");
        }
    }

    @Test
    @DisplayName("toJson / fromJson round-trip preserves Platt calibration")
    void jsonRoundTripPlatt() {
        CalibrationHarness original = new CalibrationHarness();
        for (int i = 0; i < 10; i++) {
            original.addSample(SignalType.MEBN_POSTERIOR, i / 10.0, i >= 5);
        }
        original.fitPlatt(SignalType.MEBN_POSTERIOR);

        String json = original.toJson();
        CalibrationHarness restored = CalibrationHarness.fromJson(json);

        assertTrue(restored.isPlattFitted(SignalType.MEBN_POSTERIOR));
        // Calibrated values should match
        double origVal = original.calibrate(SignalType.MEBN_POSTERIOR, 0.6);
        double restVal = restored.calibrate(SignalType.MEBN_POSTERIOR, 0.6);
        assertEquals(origVal, restVal, EPS, "Platt calibration must survive JSON round-trip");
    }

    @Test
    @DisplayName("fromJson with null/blank throws IllegalArgumentException")
    void fromJsonNullBlank() {
        assertThrows(IllegalArgumentException.class, () -> CalibrationHarness.fromJson(null));
        assertThrows(IllegalArgumentException.class, () -> CalibrationHarness.fromJson("  "));
    }

    @Test
    @DisplayName("fromJson with empty harness JSON produces empty harness")
    void fromJsonEmpty() {
        CalibrationHarness empty = new CalibrationHarness();
        String json = empty.toJson();
        CalibrationHarness restored = CalibrationHarness.fromJson(json);
        // Nothing fitted
        for (SignalType st : SignalType.values()) {
            assertFalse(restored.isIsotonicFitted(st));
            assertFalse(restored.isPlattFitted(st));
        }
    }
}
