/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.embedding.kge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP2c KgeCalibration: σ((γ−d)/T) calibration of RotatE distances, and temperature-scaling fit that
 * recovers a separating boundary from labeled link examples.
 */
class KgeCalibrationTest {

    private static final double EPS = 1e-9;

    @Test
    void crossoverAtGammaMonotoneDecreasing() {
        KgeCalibration c = new KgeCalibration(2.0, 1.0);
        assertEquals(0.5, c.calibrate(2.0), EPS);         // d = γ → 0.5
        assertTrue(c.calibrate(0.0) > 0.5);               // closer than γ → plausible
        assertTrue(c.calibrate(5.0) < 0.5);               // farther than γ → implausible
        assertTrue(c.calibrate(1.0) > c.calibrate(3.0));  // strictly decreasing in distance
    }

    @Test
    void nanDistanceIsZero() {
        assertEquals(0.0, new KgeCalibration(1.0, 1.0).calibrate(Double.NaN), EPS);
    }

    @Test
    void temperatureMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new KgeCalibration(1.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new KgeCalibration(1.0, -1.0));
        assertThrows(IllegalArgumentException.class, () -> new KgeCalibration(Double.NaN, 1.0));
    }

    @Test
    void fitRecoversSeparatingBoundary() {
        // positives (true edges) cluster at small distance, negatives (corrupted) at large distance.
        double[] distances = {0.5, 0.8, 1.0, 3.0, 3.5, 4.0};
        boolean[] positive = {true, true, true, false, false, false};

        KgeCalibration c = KgeCalibration.fit(distances, positive);

        // γ lands between the clusters → positives calibrate high, negatives low.
        assertTrue(c.gamma() > 1.0 && c.gamma() < 3.0, "gamma should separate the clusters: " + c.gamma());
        assertTrue(c.calibrate(0.5) > 0.5, "positive should calibrate > 0.5");
        assertTrue(c.calibrate(1.0) > 0.5, "positive should calibrate > 0.5");
        assertTrue(c.calibrate(3.0) < 0.5, "negative should calibrate < 0.5");
        assertTrue(c.calibrate(4.0) < 0.5, "negative should calibrate < 0.5");
        assertTrue(c.calibrate(0.5) > c.calibrate(4.0));
    }

    @Test
    void fitFallsBackToDefaultsOnDegenerateData() {
        // only one class → cannot fit a boundary
        KgeCalibration allPos = KgeCalibration.fit(new double[]{1.0, 2.0}, new boolean[]{true, true});
        assertEquals(1.0, allPos.gamma(), EPS);
        assertEquals(1.0, allPos.temperature(), EPS);

        // null / mismatched → defaults, never throws
        assertEquals(1.0, KgeCalibration.fit(null, null).gamma(), EPS);
        assertEquals(1.0, KgeCalibration.fit(new double[]{1.0}, new boolean[]{true, false}).gamma(), EPS);
    }
}
