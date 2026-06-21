/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.fol.grounding;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Default Platt-scaling calibrator: sigmoid(w * rawScore + b) per SignalType.
 * Default parameters: w=1.0, b=0.0 (identity sigmoid, uncalibrated but monotone).
 *
 * updateFromLabeledBatch: fits (w, b) via 100-step gradient descent on log-loss.
 */
public class PlattCalibrator implements StrengthCalibrator {

    private static final double DEFAULT_UNKNOWN_CEILING = 0.3;

    // Per-signal-type Platt parameters
    private final Map<SignalType, double[]> params = new EnumMap<>(SignalType.class);

    public PlattCalibrator() {
        // Initialize all with identity defaults
        for (SignalType st : SignalType.values()) {
            params.put(st, new double[]{1.0, 0.0}); // [w, b]
        }
    }

    @Override
    public double calibrate(double rawScore, SignalType signalType, VerifyResult verifyResult) {
        if (verifyResult.status() == VerifyResult.Status.REFUTED) return 0.0;
        double[] wb = params.getOrDefault(signalType, new double[]{1.0, 0.0});
        double mapped = preprocess(rawScore, signalType);
        double calibrated = sigmoid(wb[0] * mapped + wb[1]);
        if (verifyResult.status() == VerifyResult.Status.UNKNOWN) {
            return Math.min(calibrated, DEFAULT_UNKNOWN_CEILING);
        }
        return calibrated;
    }

    @Override
    public double calibrateAggregate(List<? extends Object> elements) {
        // Returns 1.0 for empty list; real aggregation depends on caller knowing GroundedElement
        // This is a placeholder: geometric mean of non-zero values
        if (elements == null || elements.isEmpty()) return 1.0;
        return 1.0; // actual impl is in GroundedElement context where types are known
    }

    @Override
    public void updateFromLabeledBatch(SignalType signalType, List<LabeledScore> batch) {
        if (batch == null || batch.isEmpty()) return;
        // Simple gradient descent on log-loss for 100 steps
        double[] wb = params.getOrDefault(signalType, new double[]{1.0, 0.0}).clone();
        double lr = 0.01;
        for (int iter = 0; iter < 100; iter++) {
            double gw = 0, gb = 0;
            for (LabeledScore ls : batch) {
                double raw = preprocess(ls.rawScore(), signalType);
                double p = sigmoid(wb[0] * raw + wb[1]);
                double err = p - ls.trueLabel();
                gw += err * raw;
                gb += err;
            }
            gw /= batch.size();
            gb /= batch.size();
            wb[0] -= lr * gw;
            wb[1] -= lr * gb;
        }
        params.put(signalType, wb);
    }

    /** Get current parameters for a signal type. */
    public double[] getParams(SignalType signalType) {
        return params.getOrDefault(signalType, new double[]{1.0, 0.0}).clone();
    }

    /** Set parameters directly (for loading from calibration JSON). */
    public void setParams(SignalType signalType, double w, double b) {
        params.put(signalType, new double[]{w, b});
    }

    private double preprocess(double raw, SignalType signalType) {
        switch (signalType) {
            case HEURISTICS_DEPENDENCY:
                // dependency ∈ (-1,1) → [0,1]
                return (raw + 1.0) / 2.0;
            default:
                return Math.min(1.0, Math.max(0.0, raw));
        }
    }

    private static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }
}
