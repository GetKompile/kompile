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

    /**
     * Aggregate calibrated confidence across a list of {@link GroundedElement}s.
     *
     * <p>For each element the raw score is obtained from
     * {@link GroundedElement#calibratedConfidence()} (already calibrated) and the
     * corresponding verify-result is used to route the value:
     * <ul>
     *   <li>Any REFUTED element → immediately returns {@code 0.0} (hard veto).</li>
     *   <li>Non-{@link GroundedElement} objects are skipped (type-safe guard).</li>
     *   <li>The aggregate is the <em>minimum</em> of all calibrated confidences —
     *       a conservative lower bound that prevents a high-confidence element
     *       from masking a weak one in a conjunction.</li>
     * </ul>
     *
     * <p>Returns {@code 1.0} for a null or empty list (vacuous truth).
     *
     * @param elements list of elements; non-{@link GroundedElement} entries are ignored
     * @return conservative aggregate confidence in [0, 1]
     */
    @Override
    public double calibrateAggregate(List<? extends Object> elements) {
        if (elements == null || elements.isEmpty()) return 1.0;
        double minConf = 1.0;
        boolean sawAny = false;
        for (Object obj : elements) {
            if (!(obj instanceof GroundedElement<?>)) continue;
            GroundedElement<?> ge = (GroundedElement<?>) obj;
            // Hard veto: any REFUTED element collapses the aggregate to 0
            if (ge.verifyResult().status() == VerifyResult.Status.REFUTED) return 0.0;
            double conf = ge.calibratedConfidence();
            if (conf < minConf) minConf = conf;
            sawAny = true;
        }
        // If no GroundedElement was present in the list, return 1.0 (vacuous)
        return sawAny ? minConf : 1.0;
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

    /**
     * Get the slope parameter {@code w} for the given signal type.
     * Defaults to {@code 1.0} (identity) if never fitted.
     */
    public double getW(SignalType signalType) {
        return params.getOrDefault(signalType, new double[]{1.0, 0.0})[0];
    }

    /**
     * Get the intercept parameter {@code b} for the given signal type.
     * Defaults to {@code 0.0} (identity) if never fitted.
     */
    public double getB(SignalType signalType) {
        return params.getOrDefault(signalType, new double[]{1.0, 0.0})[1];
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
