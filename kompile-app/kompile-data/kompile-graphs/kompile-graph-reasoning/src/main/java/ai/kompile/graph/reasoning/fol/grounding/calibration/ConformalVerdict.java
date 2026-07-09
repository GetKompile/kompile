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
import java.util.Arrays;
import java.util.List;

/**
 * Conformal prediction wrapper for three-class reasoning verdicts:
 * class 0 = SUPPORTED, class 1 = REFUTED, class 2 = UNKNOWN.
 *
 * <p>Provides distribution-free coverage guarantees: the prediction set
 * contains the true class with probability ≥ 1 − α, regardless of the
 * score distribution, given exchangeable calibration data.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 *   // Build and fit a calibrator
 *   ConformalVerdict.Calibrator builder = new ConformalVerdict.Calibrator();
 *   builder.addCalibrationExample(new double[]{0.85, 0.10, 0.05}, 0); // SUPPORTED true
 *   builder.addCalibrationExample(new double[]{0.10, 0.80, 0.10}, 1); // REFUTED true
 *   // ... more examples ...
 *   ConformalVerdict verdict = builder.fit(0.05); // 95% coverage target
 *
 *   // Predict
 *   ConformalDecision d = verdict.predictSet(new double[]{0.70, 0.20, 0.10});
 *   // → ConformalDecision.SUPPORTED when only class 0 qualifies
 * </pre>
 *
 * <h2>Mondrian variant</h2>
 * <p>When {@link Calibrator#minPerClass} examples per class are available,
 * per-class quantiles are computed (class-conditional coverage); otherwise
 * the marginal quantile is used. The {@link #isMondrianActive()} flag
 * reports which regime is active.</p>
 */
public final class ConformalVerdict {

    /**
     * Decisions produced by {@link #predictSet(double[])}.
     *
     * <p>Mapping from prediction set to decision:
     * <ul>
     *   <li>{0} → SUPPORTED</li>
     *   <li>{1} → REFUTED</li>
     *   <li>{2} → UNKNOWN</li>
     *   <li>{0,1} → CONTRADICTORY</li>
     *   <li>{0,2} → WEAK_SUPPORTED</li>
     *   <li>{1,2} → WEAK_REFUTED</li>
     *   <li>{0,1,2} or {} → ABSTAIN</li>
     * </ul>
     */
    public enum ConformalDecision {
        /** Only class 0 (SUPPORTED) qualifies. */
        SUPPORTED,
        /** Only class 1 (REFUTED) qualifies. */
        REFUTED,
        /** Only class 2 (UNKNOWN) qualifies. */
        UNKNOWN,
        /** Classes 0 and 1 both qualify — contradictory evidence. */
        CONTRADICTORY,
        /** Classes 0 and 2 qualify — weakly supported but uncertain. */
        WEAK_SUPPORTED,
        /** Classes 1 and 2 qualify — weakly refuted but uncertain. */
        WEAK_REFUTED,
        /** All three classes or none qualify — abstain from verdict. */
        ABSTAIN
    }

    // ─── Fields ──────────────────────────────────────────────────────────────────

    /**
     * Marginal nonconformity quantile: the threshold used when Mondrian is not active.
     * A sample's nonconformity score = 1 − classProbs[trueClass].
     */
    private final double qHat;

    /**
     * Per-class quantiles (Mondrian variant).
     * Index = class index (0 = SUPPORTED, 1 = REFUTED, 2 = UNKNOWN).
     * May be null if Mondrian is not active.
     */
    private final double[] qHatPerClass;

    /** True when per-class Mondrian quantiles are used instead of the marginal. */
    private final boolean mondrianActive;

    /**
     * True when the calibration set was too small to compute a meaningful quantile
     * (trivial mode: qHat = 1.0 so all classes always qualify → ABSTAIN everywhere).
     */
    private final boolean trivialMode;

    // ─── Private constructor (use Calibrator to build) ────────────────────────────

    private ConformalVerdict(double qHat, double[] qHatPerClass,
                              boolean mondrianActive, boolean trivialMode) {
        this.qHat           = qHat;
        this.qHatPerClass   = qHatPerClass;
        this.mondrianActive = mondrianActive;
        this.trivialMode    = trivialMode;
    }

    // ─── Builder / Calibrator ─────────────────────────────────────────────────────

    /**
     * Builder that accumulates calibration examples and computes the conformal
     * quantile(s) via {@link #fit(double)}.
     */
    public static final class Calibrator {

        /**
         * Minimum number of examples per class required to activate the Mondrian variant.
         * Defaults to 30.
         */
        private int minPerClass = 30;

        /**
         * Raw calibration examples: each double[2] = {nonconformity, trueClassIndex}.
         * nonconformity = 1 − classProbs[trueClassIndex].
         */
        private final List<double[]> examples = new ArrayList<>();

        /**
         * Set the minimum per-class example count threshold for the Mondrian variant.
         *
         * @param minPerClass minimum required examples per class (default 30)
         * @return this builder
         */
        public Calibrator minPerClass(int minPerClass) {
            if (minPerClass < 1) throw new IllegalArgumentException("minPerClass must be >= 1");
            this.minPerClass = minPerClass;
            return this;
        }

        /**
         * Add one calibration example.
         *
         * <p>Stores the nonconformity score = 1 − classProbs[trueClassIndex].
         *
         * @param classProbs    predicted class probabilities; must have length ≥ (trueClassIndex+1)
         *                      and each value in [0, 1]
         * @param trueClassIndex the ground-truth class index (0=SUPPORTED, 1=REFUTED, 2=UNKNOWN)
         * @throws IllegalArgumentException if trueClassIndex is out of bounds
         */
        public void addCalibrationExample(double[] classProbs, int trueClassIndex) {
            if (trueClassIndex < 0 || trueClassIndex >= classProbs.length) {
                throw new IllegalArgumentException(
                        "trueClassIndex " + trueClassIndex + " out of bounds for classProbs.length="
                        + classProbs.length);
            }
            double nonconformity = 1.0 - classProbs[trueClassIndex];
            examples.add(new double[]{nonconformity, trueClassIndex});
        }

        /**
         * Fit the conformal predictor at significance level {@code alpha}.
         *
         * <p>Computes the finite-sample corrected quantile:
         * <pre>
         *   q̂ = sorted_nonconformity_scores[ ceil((n+1)*(1−alpha)) / n ]
         * </pre>
         * clamped to [0, 1].  If {@code n} is too small to cover the required
         * quantile (i.e. {@code ceil((n+1)*(1-alpha)) > n}), trivial mode is
         * activated and q̂ = 1.0 so every prediction set = {0,1,2} → ABSTAIN.</p>
         *
         * <p>If at least {@link #minPerClass} examples per class are available,
         * the Mondrian variant is activated: per-class quantiles are computed
         * using only examples from that class, providing class-conditional coverage.</p>
         *
         * @param alpha significance level in (0, 1); e.g. 0.05 for 95% coverage
         * @return a fitted {@link ConformalVerdict}
         * @throws IllegalArgumentException if alpha is not in (0, 1)
         */
        public ConformalVerdict fit(double alpha) {
            if (alpha <= 0.0 || alpha >= 1.0) {
                throw new IllegalArgumentException("alpha must be in (0,1), got: " + alpha);
            }

            int n = examples.size();

            // Marginal quantile
            double qHat;
            boolean trivialMode;
            if (n == 0) {
                qHat = 1.0;
                trivialMode = true;
            } else {
                int needed = (int) Math.ceil((n + 1) * (1.0 - alpha));
                if (needed > n) {
                    qHat = 1.0;
                    trivialMode = true;
                } else {
                    double[] sorted = examples.stream()
                            .mapToDouble(e -> e[0])
                            .sorted()
                            .toArray();
                    qHat = sorted[needed - 1]; // 1-indexed rank → 0-indexed array
                    trivialMode = false;
                }
            }

            // Mondrian: attempt per-class quantiles
            final int numClasses = 3;
            double[] qHatPerClass = new double[numClasses];
            boolean mondrianActive = false;

            // Group examples by class
            List<List<Double>> perClass = new ArrayList<>();
            for (int c = 0; c < numClasses; c++) perClass.add(new ArrayList<>());
            for (double[] ex : examples) {
                int cls = (int) ex[1];
                if (cls >= 0 && cls < numClasses) {
                    perClass.get(cls).add(ex[0]);
                }
            }

            // Check if all classes meet the minPerClass threshold
            boolean allSufficient = true;
            for (int c = 0; c < numClasses; c++) {
                if (perClass.get(c).size() < minPerClass) {
                    allSufficient = false;
                    break;
                }
            }

            if (allSufficient) {
                mondrianActive = true;
                for (int c = 0; c < numClasses; c++) {
                    List<Double> classScores = perClass.get(c);
                    int nc = classScores.size();
                    int needed = (int) Math.ceil((nc + 1) * (1.0 - alpha));
                    if (needed > nc) {
                        qHatPerClass[c] = 1.0;
                    } else {
                        double[] sorted = classScores.stream()
                                .mapToDouble(Double::doubleValue)
                                .sorted()
                                .toArray();
                        qHatPerClass[c] = sorted[needed - 1];
                    }
                }
            } else {
                // Fill with the marginal qHat as fallback
                Arrays.fill(qHatPerClass, qHat);
            }

            return new ConformalVerdict(qHat, qHatPerClass, mondrianActive, trivialMode);
        }
    }

    // ─── Prediction ──────────────────────────────────────────────────────────────

    /**
     * Compute the prediction set from class probabilities and map it to a
     * {@link ConformalDecision}.
     *
     * <p>Class {@code i} is included in the prediction set when:
     * <pre>
     *   1 − classProbs[i] ≤ q̂_i
     * </pre>
     * which is equivalent to {@code classProbs[i] ≥ 1 − q̂_i}.</p>
     *
     * <p>If the Mondrian variant is active, each class uses its own per-class
     * quantile; otherwise all classes share the marginal quantile.</p>
     *
     * @param classProbs predicted probabilities for [SUPPORTED, REFUTED, UNKNOWN];
     *                   must have length ≥ 3
     * @return the conformal decision
     * @throws IllegalArgumentException if classProbs has length &lt; 3
     */
    public ConformalDecision predictSet(double[] classProbs) {
        if (classProbs == null || classProbs.length < 3) {
            throw new IllegalArgumentException(
                    "classProbs must have at least 3 elements (SUPPORTED, REFUTED, UNKNOWN)");
        }

        // Determine which classes qualify
        boolean[] qualifies = new boolean[3];
        for (int c = 0; c < 3; c++) {
            double threshold = mondrianActive ? qHatPerClass[c] : qHat;
            // Class c qualifies iff 1 - classProbs[c] <= qHat_c
            qualifies[c] = (1.0 - classProbs[c]) <= threshold;
        }

        // Map prediction set to decision
        boolean s = qualifies[0]; // SUPPORTED
        boolean r = qualifies[1]; // REFUTED
        boolean u = qualifies[2]; // UNKNOWN

        if (s && !r && !u)  return ConformalDecision.SUPPORTED;
        if (!s && r && !u)  return ConformalDecision.REFUTED;
        if (!s && !r && u)  return ConformalDecision.UNKNOWN;
        if (s && r && !u)   return ConformalDecision.CONTRADICTORY;
        if (s && !r && u)   return ConformalDecision.WEAK_SUPPORTED;
        if (!s && r && u)   return ConformalDecision.WEAK_REFUTED;
        // {0,1,2} or {} — abstain
        return ConformalDecision.ABSTAIN;
    }

    // ─── Accessors ────────────────────────────────────────────────────────────────

    /** The marginal nonconformity quantile q̂ used for all classes when Mondrian is inactive. */
    public double qHat() {
        return qHat;
    }

    /**
     * Per-class quantiles (Mondrian variant).
     * Returns a copy; index 0=SUPPORTED, 1=REFUTED, 2=UNKNOWN.
     */
    public double[] qHatPerClass() {
        return qHatPerClass.clone();
    }

    /** True when per-class Mondrian quantiles are active. */
    public boolean isMondrianActive() {
        return mondrianActive;
    }

    /**
     * True when the calibration set was too small to compute a meaningful quantile.
     * In trivial mode every prediction set is {0,1,2} → {@link ConformalDecision#ABSTAIN}.
     */
    public boolean isTrivialMode() {
        return trivialMode;
    }
}
