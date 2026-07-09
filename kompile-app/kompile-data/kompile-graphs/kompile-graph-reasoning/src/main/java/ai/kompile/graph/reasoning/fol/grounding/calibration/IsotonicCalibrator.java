/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.fol.grounding.calibration;

import java.util.ArrayList;
import java.util.List;

/**
 * Pool-Adjacent-Violators Algorithm (PAVA) isotonic regression calibrator.
 *
 * <p>Fits a monotone-non-decreasing mapping from raw score → calibrated probability.
 * After {@link #fit()} the breakpoints define a piecewise-linear function used by
 * {@link #calibrate(double)} for new raw inputs.</p>
 *
 * <h2>Workflow</h2>
 * <ol>
 *   <li>Call {@link #addSample(double, boolean)} one or more times.</li>
 *   <li>Call {@link #fit()} to run PAVA and produce the breakpoints.</li>
 *   <li>Call {@link #calibrate(double)} to map new raw scores.</li>
 * </ol>
 *
 * <h2>Serialization</h2>
 * <p>After fitting, {@link #serialize()} produces a compact CSV string {@code "x1:y1,x2:y2,..."}
 * that can be stored in a config file or JSON field. {@link #deserialize(String)} restores a
 * fitted calibrator directly from that string (skips {@code fit()}).</p>
 */
public class IsotonicCalibrator {

    /** Pre-fit sample accumulator. Each double[2] = {rawScore, outcome 0.0 or 1.0}. */
    private final List<double[]> samples = new ArrayList<>();

    /** Post-fit breakpoint X values (sorted ascending raw scores of block centres). */
    private double[] bpX;

    /** Post-fit breakpoint Y values (calibrated probability for each block centre). */
    private double[] bpY;

    // ─── Sample accumulation ─────────────────────────────────────────────────────

    /**
     * Add one calibration sample.
     *
     * @param score   raw engine score in any numeric range (need not be [0,1])
     * @param outcome true = positive / supported label; false = negative / refuted label
     */
    public void addSample(double score, boolean outcome) {
        samples.add(new double[]{score, outcome ? 1.0 : 0.0});
    }

    // ─── PAVA fit ────────────────────────────────────────────────────────────────

    /**
     * Run the Pool-Adjacent-Violators Algorithm on the accumulated samples and
     * produce the piecewise-linear breakpoints used by {@link #calibrate(double)}.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Sort samples by raw score ascending.</li>
     *   <li>Merge adjacent blocks whose weighted average would violate monotone-non-decreasing
     *       order (merge until the prefix is non-decreasing).</li>
     *   <li>Each PAVA block becomes one breakpoint at its weighted-average X mapped to
     *       its weighted-average Y.</li>
     * </ol>
     *
     * @throws IllegalStateException if no samples have been added
     */
    public void fit() {
        if (samples.isEmpty()) {
            throw new IllegalStateException("Cannot fit IsotonicCalibrator: no samples added");
        }

        // Sort by raw score ascending
        samples.sort((a, b) -> Double.compare(a[0], b[0]));

        // PAVA: accumulate into blocks; each block tracks (sumX, sumY, weight)
        // weight = count of samples in the block
        List<double[]> blocks = new ArrayList<>(); // each double[3] = {sumX, sumY, count}

        for (double[] s : samples) {
            // Start a new block from this sample
            double[] block = {s[0], s[1], 1.0};
            blocks.add(block);
            // Merge blocks from the end while monotone-non-decreasing is violated
            int last = blocks.size() - 1;
            while (last > 0) {
                double[] prev = blocks.get(last - 1);
                double[] cur  = blocks.get(last);
                double prevAvgY = prev[1] / prev[2];
                double curAvgY  = cur[1]  / cur[2];
                if (prevAvgY <= curAvgY) {
                    // Monotone constraint satisfied — stop merging
                    break;
                }
                // Merge cur into prev
                prev[0] += cur[0]; // sumX
                prev[1] += cur[1]; // sumY
                prev[2] += cur[2]; // count
                blocks.remove(last);
                last--;
            }
        }

        // Convert blocks to breakpoints
        bpX = new double[blocks.size()];
        bpY = new double[blocks.size()];
        for (int i = 0; i < blocks.size(); i++) {
            double[] b = blocks.get(i);
            bpX[i] = b[0] / b[2]; // weighted-average X
            bpY[i] = b[1] / b[2]; // weighted-average Y
        }
    }

    // ─── Calibration ─────────────────────────────────────────────────────────────

    /**
     * Map a new raw score to a calibrated probability via piecewise-linear interpolation
     * between the fitted breakpoints.
     *
     * <ul>
     *   <li>Below the leftmost breakpoint → clamp to {@code bpY[0]}.</li>
     *   <li>Above the rightmost breakpoint → clamp to {@code bpY[bpX.length-1]}.</li>
     *   <li>Between adjacent breakpoints → linear interpolation.</li>
     * </ul>
     *
     * @param raw raw score (any value; clamps outside breakpoint range)
     * @return calibrated probability in [0, 1]
     * @throws IllegalStateException if {@link #fit()} or {@link #deserialize(String)} has not been called
     */
    public double calibrate(double raw) {
        if (bpX == null) {
            throw new IllegalStateException("IsotonicCalibrator has not been fitted yet");
        }
        int n = bpX.length;
        if (n == 1) return bpY[0];

        // Left clamp
        if (raw <= bpX[0]) return bpY[0];
        // Right clamp
        if (raw >= bpX[n - 1]) return bpY[n - 1];

        // Binary search for the segment
        int lo = 0, hi = n - 2;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (bpX[mid + 1] <= raw) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        // Linear interpolation between bpX[lo] and bpX[lo+1]
        double x0 = bpX[lo], x1 = bpX[lo + 1];
        double y0 = bpY[lo], y1 = bpY[lo + 1];
        double t = (x1 == x0) ? 0.5 : (raw - x0) / (x1 - x0);
        return y0 + t * (y1 - y0);
    }

    // ─── Serialization ───────────────────────────────────────────────────────────

    /**
     * Serialize the fitted breakpoints to a compact CSV string {@code "x1:y1,x2:y2,..."}
     * suitable for embedding in a JSON field or config file.
     *
     * @return compact CSV breakpoint string
     * @throws IllegalStateException if the calibrator has not been fitted
     */
    public String serialize() {
        if (bpX == null) {
            throw new IllegalStateException("IsotonicCalibrator has not been fitted yet");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bpX.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(bpX[i]).append(':').append(bpY[i]);
        }
        return sb.toString();
    }

    /**
     * Reconstruct a fitted {@link IsotonicCalibrator} directly from a serialized string.
     * Skips the {@link #fit()} step — breakpoints are set directly.
     *
     * @param s a string in the format produced by {@link #serialize()}
     * @return a fitted {@code IsotonicCalibrator}
     * @throws IllegalArgumentException if the string is null, blank, or malformed
     */
    public static IsotonicCalibrator deserialize(String s) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException("IsotonicCalibrator.deserialize: input is null or blank");
        }
        String[] parts = s.split(",");
        double[] x = new double[parts.length];
        double[] y = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            int colon = part.indexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException(
                        "IsotonicCalibrator.deserialize: malformed entry '" + part + "'");
            }
            try {
                x[i] = Double.parseDouble(part.substring(0, colon));
                y[i] = Double.parseDouble(part.substring(colon + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "IsotonicCalibrator.deserialize: bad number in '" + part + "'", e);
            }
        }
        IsotonicCalibrator cal = new IsotonicCalibrator();
        cal.bpX = x;
        cal.bpY = y;
        return cal;
    }

    // ─── Accessors (for testing / CalibrationHarness) ────────────────────────────

    /** True after {@link #fit()} or {@link #deserialize(String)} has been called. */
    public boolean isFitted() {
        return bpX != null;
    }

    /** Number of raw samples accumulated (before fit). */
    public int sampleCount() {
        return samples.size();
    }

    /**
     * X breakpoints (block-centre raw scores) after fitting.
     * Returns a copy so the internal array cannot be mutated by callers.
     *
     * @throws IllegalStateException if not yet fitted
     */
    public double[] breakpointX() {
        if (bpX == null) throw new IllegalStateException("Not fitted");
        return bpX.clone();
    }

    /**
     * Y breakpoints (calibrated probability per block) after fitting.
     * Returns a copy so the internal array cannot be mutated by callers.
     *
     * @throws IllegalStateException if not yet fitted
     */
    public double[] breakpointY() {
        if (bpY == null) throw new IllegalStateException("Not fitted");
        return bpY.clone();
    }
}
