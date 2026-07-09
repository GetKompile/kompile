/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.argument;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Learns per-evidence-kind multipliers {@code w_kind ∈ [0.1, 3.0]} that scale
 * {@link EvidenceItem} confidences before adjudication via {@link QeSemantics} (or an
 * alternative strategy), so the claim strength better matches labeled verdicts.
 *
 * <h3>Setup</h3>
 * <p>Input is a list of {@link LabeledClaim}s. Each holds a claim label, a prior,
 * a list of {@link EvidenceItem}s, and a numeric target:
 * <ul>
 *   <li>SUPPORTED → 1.0</li>
 *   <li>REFUTED   → 0.0</li>
 *   <li>UNKNOWN   → 0.5</li>
 * </ul>
 *
 * <h3>Loss</h3>
 * <p>Mean squared error between the claim strength under the chosen semantics and the target,
 * averaged over the training split.</p>
 *
 * <h3>Parameters and features</h3>
 * <p>For each evidence kind {@code k} appearing in the training corpus a scalar
 * {@code w_k ∈ [0.1, 3.0]} is maintained.  Before building the QBAF for a claim, each
 * evidence item's confidence is scaled:
 * <pre>
 *   confidence'_i = clamp(confidence_i · w_{kind_i}, 0, 1)
 * </pre>
 * The claim's prior (base score) is never scaled.
 *
 * <h3>Optimizer</h3>
 * <p>Finite-difference gradient descent with central differences ({@code h=1e-4}) on the
 * weight vector. After every gradient step all weights are projected (clamped) back to
 * [0.1, 3.0]. The update is:
 * <pre>
 *   ∂L/∂w_k ≈ (L(w + h·e_k) − L(w − h·e_k)) / (2h)
 *   w_k ← clamp(w_k − lr · ∂L/∂w_k, 0.1, 3.0)
 * </pre>
 * Deterministic: all operations are purely arithmetic; no randomness is introduced. If
 * {@link LearnerConfig#seed()} is set, it is currently unused (documented here for future
 * extensions such as example-shuffle). Two identical runs always produce identical
 * {@link LearnResult}s.
 *
 * <h3>Holdout gate (mirrors beatsFold)</h3>
 * <p>Claims are split by whole claim: every k-th claim (0-indexed, stride =
 * {@code round(1 / holdoutFraction)}) goes to the holdout set; the rest form training.
 * After training, the holdout MSE under learned weights is compared against holdout MSE
 * under all-1.0 defaults. {@link LearnResult#beatsDefault()} is {@code true} iff learned
 * ≤ default. {@link LearnResult#effectiveWeights()} returns the learned map when
 * {@code beatsDefault}, else all-1.0 defaults — never silent about either outcome.
 *
 * @see QeSemantics
 * @see AdjudicatorConfig
 */
public final class QbafWeightLearner {

    // ── Weight bounds ─────────────────────────────────────────────────────────────

    /** Minimum allowed per-kind weight. */
    public static final double W_MIN = 0.1;
    /** Maximum allowed per-kind weight. */
    public static final double W_MAX = 3.0;

    // ── FD gradient step ─────────────────────────────────────────────────────────

    private static final double FD_H = 1e-4;

    // ── Nested types ─────────────────────────────────────────────────────────────

    /**
     * A single training/evaluation example: a claim with known evidence and a target strength.
     *
     * @param claimLabel human-readable label for the claim (used as the QBAF claim label)
     * @param prior      prior strength in [0, 1]; the claim's base score — never scaled
     * @param items      evidence items (may be empty; kinds determine the learned weight keys)
     * @param target     target claim strength in [0, 1]; use 1.0/0.0/0.5 for
     *                   SUPPORTED/REFUTED/UNKNOWN, or any intermediate value
     */
    public record LabeledClaim(
            String claimLabel,
            double prior,
            List<EvidenceItem> items,
            double target
    ) {
        public LabeledClaim {
            Objects.requireNonNull(claimLabel, "claimLabel");
            Objects.requireNonNull(items, "items");
            items = List.copyOf(items);
            if (prior < 0.0 || prior > 1.0)
                throw new IllegalArgumentException("prior must be in [0,1], got " + prior);
            if (target < 0.0 || target > 1.0)
                throw new IllegalArgumentException("target must be in [0,1], got " + target);
        }

        /** Convenience: construct from a status enum instead of a raw double. */
        public static LabeledClaim of(String claimLabel, double prior, List<EvidenceItem> items,
                                      AdjudicatedVerdict.Status status) {
            double t = switch (status) {
                case SUPPORTED -> 1.0;
                case REFUTED   -> 0.0;
                case UNKNOWN   -> 0.5;
            };
            return new LabeledClaim(claimLabel, prior, items, t);
        }
    }

    /**
     * Configuration knobs for the learner.
     *
     * @param learningRate    step size for gradient descent; positive real (default 0.05)
     * @param epochs          number of full-corpus gradient passes (default 200)
     * @param holdoutFraction fraction of claims to hold out (default 0.25, clamped to (0,1))
     * @param seed            reserved for future use (shuffling etc.); algorithm is currently
     *                        fully deterministic regardless of this value
     */
    public record LearnerConfig(
            double learningRate,
            int    epochs,
            double holdoutFraction,
            long   seed
    ) {
        public LearnerConfig {
            if (learningRate <= 0.0) throw new IllegalArgumentException("learningRate must be > 0");
            if (epochs < 1)          throw new IllegalArgumentException("epochs must be >= 1");
            if (holdoutFraction <= 0.0 || holdoutFraction >= 1.0)
                throw new IllegalArgumentException("holdoutFraction must be in (0,1)");
        }

        /** Default config: lr=0.05, 200 epochs, 25% holdout. */
        public static LearnerConfig defaults() {
            return new LearnerConfig(0.05, 200, 0.25, 0L);
        }
    }

    /**
     * Learning outcome.
     *
     * @param weights               the learned per-kind weight map (all kinds seen in training)
     * @param trainLossCurveFirst   MSE on the training set at the <em>start</em> of epoch 1
     * @param trainLossCurveLast    MSE on the training set after the final epoch
     * @param holdoutLossLearned    MSE on the holdout set under learned weights
     * @param holdoutLossDefault    MSE on the holdout set under all-1.0 default weights
     * @param beatsDefault          {@code true} iff holdoutLossLearned ≤ holdoutLossDefault
     */
    public record LearnResult(
            Map<String, Double> weights,
            double trainLossCurveFirst,
            double trainLossCurveLast,
            double holdoutLossLearned,
            double holdoutLossDefault,
            boolean beatsDefault
    ) {
        public LearnResult {
            weights = Collections.unmodifiableMap(new LinkedHashMap<>(weights));
        }

        /**
         * Returns learned weights when they beat the default, otherwise a map of all
         * observed kinds with weight 1.0.  Never silently swaps without reporting.
         */
        public Map<String, Double> effectiveWeights() {
            if (beatsDefault) return weights;
            Map<String, Double> defaults = new LinkedHashMap<>();
            for (String k : weights.keySet()) defaults.put(k, 1.0);
            return Collections.unmodifiableMap(defaults);
        }
    }

    // ── Semantics strategy interface ──────────────────────────────────────────────

    /**
     * Pluggable scoring strategy: evaluates a {@link Qbaf} and returns the claim strength.
     * Built-in strategies: {@link #QE} and {@link #DF_QUAD}.
     */
    @FunctionalInterface
    public interface SemanticsStrategy {
        /** @return claim strength in [0, 1] for the given QBAF. */
        double claimStrength(Qbaf qbaf);

        /** QE semantics (default). */
        SemanticsStrategy QE    = qbaf -> QeSemantics.evaluate(qbaf).claimStrength(qbaf);
        /** DF-QuAD semantics. */
        SemanticsStrategy DF_QUAD = qbaf -> DfQuadSemantics.evaluate(qbaf).claimStrength(qbaf);
    }

    // ── Fields ────────────────────────────────────────────────────────────────────

    private final LearnerConfig     config;
    private final SemanticsStrategy strategy;

    // ── Constructors ─────────────────────────────────────────────────────────────

    /** Create a learner with default config and QE semantics. */
    public QbafWeightLearner() {
        this(LearnerConfig.defaults(), SemanticsStrategy.QE);
    }

    /** Create a learner with given config and QE semantics. */
    public QbafWeightLearner(LearnerConfig config) {
        this(config, SemanticsStrategy.QE);
    }

    /** Create a learner with explicit config and semantics strategy. */
    public QbafWeightLearner(LearnerConfig config, SemanticsStrategy strategy) {
        this.config   = Objects.requireNonNull(config,   "config");
        this.strategy = Objects.requireNonNull(strategy, "strategy");
    }

    // ── Main learn entry-point ────────────────────────────────────────────────────

    /**
     * Learn per-kind weights from labeled claims.
     *
     * <p>The list is split: every {@code stride}-th claim (by insertion order) is held out,
     * where {@code stride = max(2, round(1 / holdoutFraction))}. The training loop runs
     * {@code epochs} passes of finite-difference gradient descent, projecting weights to
     * [0.1, 3.0] after each step. Returns a {@link LearnResult} with both splits' losses
     * always populated.</p>
     *
     * @param claims non-empty list of labeled claims
     * @return the learning outcome
     * @throws IllegalArgumentException if claims is empty
     */
    public LearnResult learn(List<LabeledClaim> claims) {
        Objects.requireNonNull(claims, "claims");
        if (claims.isEmpty()) throw new IllegalArgumentException("claims must not be empty");

        // Collect all kinds present in the data
        Set<String> kindSet = new LinkedHashSet<>();
        for (LabeledClaim lc : claims) {
            for (EvidenceItem item : lc.items()) {
                kindSet.add(item.kind());
            }
        }
        List<String> kinds = new ArrayList<>(kindSet);

        // Split into train / holdout
        int stride = Math.max(2, (int) Math.round(1.0 / config.holdoutFraction()));
        List<LabeledClaim> train   = new ArrayList<>();
        List<LabeledClaim> holdout = new ArrayList<>();
        for (int i = 0; i < claims.size(); i++) {
            if (i % stride == 0) holdout.add(claims.get(i));
            else                 train  .add(claims.get(i));
        }
        // Edge case: if split produced empty train or holdout, fallback to all-train/half-each
        if (train.isEmpty())   train   = new ArrayList<>(claims);
        if (holdout.isEmpty()) holdout = new ArrayList<>(claims);

        // Initialise weights at 1.0
        double[] w = new double[kinds.size()];
        for (int i = 0; i < w.length; i++) w[i] = 1.0;

        // Record first-epoch loss before any update
        double trainFirst = mse(train, kinds, w);
        double trainLast  = trainFirst;

        // Gradient descent
        for (int epoch = 0; epoch < config.epochs(); epoch++) {
            // Compute FD gradient
            double[] grad = new double[w.length];
            for (int k = 0; k < w.length; k++) {
                double orig = w[k];

                w[k] = clampW(orig + FD_H);
                double lossPlus = mse(train, kinds, w);

                w[k] = clampW(orig - FD_H);
                double lossMinus = mse(train, kinds, w);

                w[k] = orig;
                grad[k] = (lossPlus - lossMinus) / (2.0 * FD_H);
            }

            // Gradient step + projection
            for (int k = 0; k < w.length; k++) {
                w[k] = clampW(w[k] - config.learningRate() * grad[k]);
            }

            trainLast = mse(train, kinds, w);
        }

        // Holdout evaluation
        double holdoutLearned = mse(holdout, kinds, w);

        // Default weights (all 1.0)
        double[] wDefault = new double[kinds.size()];
        for (int i = 0; i < wDefault.length; i++) wDefault[i] = 1.0;
        double holdoutDefault = mse(holdout, kinds, wDefault);

        boolean beatsDefault = holdoutLearned <= holdoutDefault;

        // Build result map
        Map<String, Double> weightMap = new LinkedHashMap<>();
        for (int i = 0; i < kinds.size(); i++) weightMap.put(kinds.get(i), w[i]);

        return new LearnResult(weightMap, trainFirst, trainLast,
                holdoutLearned, holdoutDefault, beatsDefault);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────────

    /**
     * MSE of the claim-strength predictions against targets, given the weight vector {@code w}
     * aligned to {@code kinds}.
     */
    private double mse(List<LabeledClaim> claims, List<String> kinds, double[] w) {
        if (claims.isEmpty()) return 0.0;
        double sum = 0.0;
        for (LabeledClaim lc : claims) {
            double sigma = predict(lc, kinds, w);
            double err   = sigma - lc.target();
            sum += err * err;
        }
        return sum / claims.size();
    }

    /**
     * Predict claim strength under the given weights.
     *
     * <p>Scales each evidence item's confidence by its kind's weight (clamped to [0,1]) and
     * builds a fresh QBAF with the claim's original prior; evaluates under the semantics strategy.
     */
    private double predict(LabeledClaim lc, List<String> kinds, double[] w) {
        // Build weight lookup: kind → w_kind
        Map<String, Double> wMap = new LinkedHashMap<>();
        for (int i = 0; i < kinds.size(); i++) wMap.put(kinds.get(i), w[i]);

        Qbaf.Builder builder = Qbaf.builder();
        builder.argument(Argument.claim("claim", lc.claimLabel(), lc.prior()));

        List<EvidenceItem> items = lc.items();
        for (int i = 0; i < items.size(); i++) {
            EvidenceItem item  = items.get(i);
            double       wKind = wMap.getOrDefault(item.kind(), 1.0);
            double       scaledConf = Math.max(0.0, Math.min(1.0, item.confidence() * wKind));

            String  id   = "e" + i;
            ArgKind kind = item.pro() ? ArgKind.PRO : ArgKind.CON;
            builder.argument(new Argument(id, item.label(), scaledConf, kind));
            if (item.pro()) builder.support(id, "claim");
            else            builder.attack (id, "claim");
        }

        Qbaf qbaf = builder.build();
        return strategy.claimStrength(qbaf);
    }

    private static double clampW(double w) {
        return Math.max(W_MIN, Math.min(W_MAX, w));
    }
}
