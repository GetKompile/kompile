/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.claims;

import ai.kompile.graph.reasoning.embedding.kge.KgeTripleScorer;
import ai.kompile.graph.reasoning.fol.grounding.PlattCalibrator;
import ai.kompile.graph.reasoning.fol.grounding.StrengthCalibrator;
import ai.kompile.graph.reasoning.fol.grounding.VerifyResult;

import java.util.List;
import java.util.Objects;

/**
 * KGE-based plausibility signal for claim evidence.
 *
 * <p>Wraps a {@link KgeTripleScorer} (the SPI) and a {@link PlattCalibrator} to convert the raw
 * embedding score (e.g. RotatE distance, TransE score) into a calibrated probability estimate.
 * The calibration step is essential because raw KGE scores are not probabilities — their scale and
 * range depend on the embedding dimensionality and training loss function.
 *
 * <p><b>Important:</b> A KGE score is a <em>signal</em>, never a verdict by itself. It should
 * enter fusion with a lower weight ({@link FusionWeights#kgeWeight()}) relative to direct
 * observation or logical derivation. Calibration is required for honest signal fusion
 * (Tabacof &amp; Costabello 2020, "Probabilistic Calibration of Knowledge Graph Embedding Models").
 *
 * <p>If the {@link KgeTripleScorer} is {@code null} (signal absent — no KGE model loaded), the
 * method returns {@code Double.NaN}, which {@link DossierBuilder} interprets as "omit this item".
 */
public final class KgePlausibilitySignal {

    /**
     * The {@link StrengthCalibrator.SignalType} used when submitting the raw KGE score to the
     * Platt calibrator. {@code ROTATE_DISTANCE} is the closest existing type for embedding distance
     * models (RotatE, TransE); all common KGE models use a distance or dot-product score that
     * benefits from sigmoid/Platt calibration.
     */
    public static final StrengthCalibrator.SignalType KGE_SIGNAL_TYPE =
            StrengthCalibrator.SignalType.ROTATE_DISTANCE;

    private final KgeTripleScorer scorer;
    private final PlattCalibrator calibrator;

    /**
     * Create a signal with explicit scorer and calibrator.
     *
     * @param scorer     the KGE scorer, or {@code null} to indicate signal absent
     * @param calibrator the Platt calibrator for converting raw scores to probabilities;
     *                   must not be {@code null} when scorer is non-null
     */
    public KgePlausibilitySignal(KgeTripleScorer scorer, PlattCalibrator calibrator) {
        this.scorer = scorer;
        this.calibrator = (scorer != null)
                ? Objects.requireNonNull(calibrator, "calibrator must not be null when scorer is present")
                : calibrator; // null is allowed when scorer is null
    }

    /**
     * Create a signal with a scorer and a default identity calibrator (for testing or when
     * calibration data is unavailable — returns the raw score unchanged).
     *
     * @param scorer the KGE scorer, or {@code null} to indicate signal absent
     */
    public KgePlausibilitySignal(KgeTripleScorer scorer) {
        this(scorer, scorer != null ? new PlattCalibrator() : null);
    }

    /**
     * Compute the calibrated probability that the triple (head, relation, tail) holds.
     *
     * @param head     head entity id
     * @param relation relation type (predicate)
     * @param tail     tail entity id
     * @return calibrated probability in {@code [0, 1]}, or {@code Double.NaN} if no scorer is available
     */
    public double calibratedProbability(String head, String relation, String tail) {
        if (scorer == null) {
            return Double.NaN;
        }
        double raw = scorer.scoreTriple(head, relation, tail);
        if (Double.isNaN(raw)) {
            return Double.NaN;
        }
        // calibrate returns a probability via Platt scaling (sigmoid with per-type weights).
        // PlattCalibrator caps UNKNOWN results at 0.3; since a KGE score is not a KB verdict
        // we pass SUPPORTED as the neutral context so the raw->sigmoid mapping is uncapped.
        // Clamp raw to [0,1] before wrapping in VerifyResult (its compact constructor validates).
        double clampedRaw = Math.max(0.0, Math.min(1.0, raw));
        double calibrated = calibrator.calibrate(raw, KGE_SIGNAL_TYPE,
                VerifyResult.supported(clampedRaw, List.of()));
        // Clamp to [0,1] in case calibrator produces slight overshoot
        return Math.max(0.0, Math.min(1.0, calibrated));
    }

    /** Whether a scorer is configured (false = signal is absent, score will be NaN). */
    public boolean hasScorer() {
        return scorer != null;
    }

    /** The underlying scorer (may be null). */
    public KgeTripleScorer scorer() { return scorer; }

    /** The calibrator in use (may be null when scorer is null). */
    public PlattCalibrator calibrator() { return calibrator; }
}
