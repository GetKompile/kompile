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

import java.util.List;

/**
 * Calibrates raw domain scores into [0,1] probability space.
 * Uses per-signal-type Platt scaling: calibrated = sigmoid(w * rawScore + b).
 * Until calibration parameters are available, uses identity defaults (w=1, b=0).
 */
public interface StrengthCalibrator {

    /** Signal types — each gets its own (w, b) pair. */
    enum SignalType {
        PSL_SOFT_TRUTH,
        MEBN_POSTERIOR,
        HEURISTICS_DEPENDENCY,
        INDUCTIVE_MINER_FM,
        ROTATE_DISTANCE,
        CLUSTER_SIZE,
        DECLARE_CONFIDENCE,
        OBSERVED
    }

    /**
     * Calibrate a raw score with verify result veto:
     * REFUTED → 0.0, UNKNOWN → min(raw, unknownCeiling), SUPPORTED → sigmoid(w*raw+b)
     */
    double calibrate(double rawScore, SignalType signalType, VerifyResult verifyResult);

    /**
     * Aggregate: geometric mean of SUPPORTED-element confidences; 0.0 if any REFUTED.
     */
    double calibrateAggregate(List<? extends Object> elements);

    /**
     * Update calibration parameters from a labeled batch.
     * LabeledScore: (rawScore, trueLabel 0.0/1.0)
     */
    void updateFromLabeledBatch(SignalType signalType, List<LabeledScore> batch);

    /** A raw score paired with a ground-truth label. */
    record LabeledScore(double rawScore, double trueLabel) {}
}
