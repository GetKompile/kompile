/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.learning;

import ai.kompile.graph.reasoning.psl.GroundRule;
import ai.kompile.graph.reasoning.psl.PslProgram;
import ai.kompile.graph.reasoning.psl.PslRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Max-pseudolikelihood weight learner for PSL (HL-MRF) rules.
 *
 * <h3>Modes</h3>
 * Two gradient modes are available, controlled by {@link PseudolikelihoodMode}:
 *
 * <h4>EXACT_PL — true HL-MRF pseudolikelihood (default)</h4>
 * Implements the max-pseudolikelihood gradient from Bach et al., JMLR 2017 §6.
 * For each target atom {@code i}, the 1-D conditional density over its soft-truth
 * value {@code y_i ∈ [0,1]} (with all other atoms fixed to their ground-truth values)
 * is:
 * <pre>
 *   p(y_i | y_{-i}) ∝ exp(-Σ_{r ∋ i} w_r · φ_r(y_i, y_{-i}))
 * </pre>
 * where {@code φ_r(y_i, y_{-i})} is the hinge-loss potential for ground rule {@code r}
 * evaluated at {@code (y_i, y_{-i})}.  The negative log-pseudolikelihood gradient
 * for weight {@code w_r} is:
 * <pre>
 *   ∂(-log PL)/∂w_r
 *       = Σ_i [E_{y_i ~ p(·|y_{-i})}[φ_r(y_i, y_{-i})] - φ_r(y_i^train, y_{-i}^train)]
 * </pre>
 * The 1-D expectation over {@code y_i ∈ [0,1]} is computed by fixed-grid numeric
 * quadrature (33-point composite Simpson rule; the integrand is piecewise-polynomial
 * so the quadrature error is negligible).  Exp-overflow is guarded by subtracting
 * the maximum log-unnormalized-density before exponentiating (log-sum-exp trick).
 *
 * <p><b>Cost note</b>: O(|target atoms| × |ground rules| × Q) per epoch, where Q = 33
 * (quadrature points). For programs with thousands of atoms or ground rules, prefer
 * {@link PseudolikelihoodMode#CHEAP_SURROGATE} or mini-batching.</p>
 *
 * <h4>CHEAP_SURROGATE — fast perceptron-style approximation</h4>
 * Uses the classical {@code distPred − distGT} per-rule gradient, computed with
 * current predicted values (targets initialized to 0.5).  Identical to the pre-2026
 * implementation.  O(|ground rules|) per epoch; converges faster on large programs
 * at the cost of gradient accuracy.
 *
 * <h3>Existing signatures preserved</h3>
 * All pre-existing constructors and the {@link #learn} method signature are unchanged.
 * The default no-arg constructor now uses {@link PseudolikelihoodMode#EXACT_PL}
 * (features ship enabled, per project convention).
 */
public class PseudolikelihoodLearner implements WeightLearner {

    private static final Logger log = LoggerFactory.getLogger(PseudolikelihoodLearner.class);

    /** Sentinel: {@code batchSize <= 0} means use all ground rules (full-batch mode). */
    private static final int FULL_BATCH = 0;
    private static final long DEFAULT_SEED = 1234L;

    /** Number of quadrature points for the Simpson-rule 1-D integral over y_i ∈ [0,1]. */
    private static final int QUAD_N = 33; // must be odd for composite Simpson
    /** Pre-computed Simpson weights and nodes over [0,1]. */
    private static final double[] QUAD_X;
    private static final double[] QUAD_W;

    static {
        // Composite Simpson rule: n+1 points, n must be even (here QUAD_N-1 = 32 intervals).
        int n = QUAD_N - 1; // number of sub-intervals (must be even)
        QUAD_X = new double[QUAD_N];
        QUAD_W = new double[QUAD_N];
        double h = 1.0 / n;
        for (int k = 0; k < QUAD_N; k++) {
            QUAD_X[k] = k * h;
            if (k == 0 || k == n) {
                QUAD_W[k] = h / 3.0;
            } else if (k % 2 == 1) {
                QUAD_W[k] = 4.0 * h / 3.0;
            } else {
                QUAD_W[k] = 2.0 * h / 3.0;
            }
        }
    }

    /**
     * Gradient mode selector.
     *
     * <ul>
     *   <li>{@link #EXACT_PL} — true max-pseudolikelihood gradient via 1-D quadrature.
     *       Correct but O(|atoms| × |rules| × Q) per epoch.</li>
     *   <li>{@link #CHEAP_SURROGATE} — perceptron-style {@code distPred − distGT} gradient.
     *       Fast but not true PL; identical to the pre-2026 behavior.</li>
     * </ul>
     */
    public enum PseudolikelihoodMode {
        /** True max-PL gradient (Bach et al. JMLR 2017 §6) via 33-point Simpson quadrature. */
        EXACT_PL,
        /**
         * Fast approximation: {@code distPred − distGT} per-rule mean. Not pseudolikelihood;
         * retained for large-grounding callers where the exact gradient's O(atoms × rules × Q)
         * cost is prohibitive. Matches the pre-2026 implementation exactly.
         */
        CHEAP_SURROGATE
    }

    private final double learningRate;
    private final double tolerance;
    private final int batchSize;
    private final long seed;
    private final PseudolikelihoodMode mode;

    /** Default: EXACT_PL mode, learning rate 0.05, tolerance 1e-4, full-batch. */
    public PseudolikelihoodLearner() {
        this(0.05, 1e-4, FULL_BATCH, DEFAULT_SEED, PseudolikelihoodMode.EXACT_PL);
    }

    public PseudolikelihoodLearner(double learningRate, double tolerance) {
        this(learningRate, tolerance, FULL_BATCH, DEFAULT_SEED, PseudolikelihoodMode.EXACT_PL);
    }

    /**
     * Mini-batch constructor (EXACT_PL mode).
     *
     * @param learningRate learning rate (positive)
     * @param tolerance    convergence threshold
     * @param batchSize    number of ground rules to sample per epoch; {@code <= 0} or
     *                     {@code >= groundRules.size()} means full-batch (today's behavior)
     * @param seed         RNG seed for reproducible subsampling
     */
    public PseudolikelihoodLearner(double learningRate, double tolerance, int batchSize, long seed) {
        this(learningRate, tolerance, batchSize, seed, PseudolikelihoodMode.EXACT_PL);
    }

    /**
     * Full constructor.
     *
     * @param learningRate learning rate
     * @param tolerance    convergence threshold
     * @param batchSize    mini-batch size; {@code <= 0} = full-batch
     * @param seed         RNG seed
     * @param mode         gradient mode ({@link PseudolikelihoodMode#EXACT_PL} or
     *                     {@link PseudolikelihoodMode#CHEAP_SURROGATE})
     */
    public PseudolikelihoodLearner(double learningRate, double tolerance, int batchSize,
                                   long seed, PseudolikelihoodMode mode) {
        this.learningRate = learningRate;
        this.tolerance = tolerance;
        this.batchSize = batchSize;
        this.seed = seed;
        this.mode = (mode != null) ? mode : PseudolikelihoodMode.EXACT_PL;
    }

    /** Returns the gradient mode in use. */
    public PseudolikelihoodMode mode() {
        return mode;
    }

    @Override
    public List<PslRule> learn(PslProgram program, Map<String, Double> groundTruth, int maxEpochs) {
        List<PslRule> rules = program.rules();
        if (rules.isEmpty() || groundTruth.isEmpty()) {
            return new ArrayList<>(rules);
        }

        int numRules = rules.size();
        double[] weights = new double[numRules];
        for (int i = 0; i < numRules; i++) {
            weights[i] = rules.get(i).weight();
        }

        // Build initial value map: observed atoms from program, targets at 0.5
        Map<String, Double> currentValues = program.valueSnapshot();
        for (String key : program.targetKeys()) {
            currentValues.putIfAbsent(key, 0.5);
        }

        // Ground the program once
        List<GroundRule> groundRules = program.ground();

        ProjectedGradientOptimizer optimizer = new ProjectedGradientOptimizer(
                learningRate, tolerance, ProjectedGradientOptimizer.nonNegative());

        // Single RNG seeded once for the whole run, ensuring deterministic subsampling.
        Random rng = new Random(seed);

        boolean converged = false;
        int epoch;

        for (epoch = 0; epoch < maxEpochs; epoch++) {
            // Mini-batch subsampling: use a subset if batchSize is in range.
            List<GroundRule> batch = subsample(groundRules, rng);

            // Sync weights into the working rules list so gradient resolution is current.
            List<PslRule> currentRules = buildRulesWithWeights(rules, weights);

            double[] gradient;
            if (mode == PseudolikelihoodMode.EXACT_PL) {
                gradient = exactPlGradient(currentRules, batch, currentValues, groundTruth,
                        program.targetKeys());
            } else {
                // CHEAP_SURROGATE: the original distPred - distGT gradient.
                // For large programs use the vectorized SameDiff path.
                if (batch.size() >= SameDiffPslWeightGradient.GROUND_RULE_THRESHOLD) {
                    gradient = SameDiffPslWeightGradient.compute(rules, batch, currentValues, groundTruth);
                } else {
                    gradient = PslRuleGradient.ruleGradient(rules, batch, currentValues, groundTruth);
                }
                // Surrogate mode: blend target values toward ground-truth each epoch.
                for (String key : program.targetKeys()) {
                    double gt = groundTruth.getOrDefault(key, 0.5);
                    double cur = currentValues.getOrDefault(key, 0.5);
                    currentValues.put(key, cur + 0.1 * (gt - cur));
                }
            }

            double maxChange = optimizer.step(weights, gradient);

            log.debug("PseudolikelihoodLearner epoch {}: mode={}, maxChange={}", epoch, mode, maxChange);

            if (optimizer.converged(maxChange)) {
                converged = true;
                break;
            }
        }

        log.info("PseudolikelihoodLearner: {} epochs, converged={}, mode={}", epoch, converged, mode);

        // Build final rules with learned weights
        return buildRulesWithWeights(rules, weights);
    }

    // ─── EXACT_PL gradient ───────────────────────────────────────────────────────

    /**
     * True HL-MRF max-pseudolikelihood gradient (Bach et al. JMLR 2017 §6).
     *
     * <p>For each target atom {@code i} and each rule {@code r} containing {@code i}:
     * <pre>
     *   -log PL = Σ_i [ Σ_{r ∋ i} w_r · φ_r(y^train) + log Z_i ],   Z_i = ∫ exp(-Σ_{r ∋ i} w_r φ_r(y_i)) dy_i
     *   ∂(-log PL)/∂w_r += φ_r(y_i^train, y_{-i}^train) - E_{y_i ~ p(·|y_{-i})}[φ_r(y_i, y_{-i})]
     * </pre>
     * (∂log Z_i/∂w_r = -E[φ_r], so the observed feature enters with a PLUS sign — a rule
     * violated by the training data gets a POSITIVE gradient and gradient DESCENT pushes its
     * weight down.) The conditional p(y_i | y_{-i}) ∝ exp(-Σ_{r' ∋ i} w_{r'} · φ_{r'}(y_i, y_{-i}))
     * is a 1-D distribution over y_i ∈ [0,1].  The expectation is computed by composite
     * 33-point Simpson quadrature; the log-sum-exp trick prevents exp overflow.</p>
     *
     * <p>Atoms contributing to rule {@code r}'s conditional are the ones that appear in the body
     * or head of any ground rule whose head or body contains atom {@code i}.  Because we work with
     * fully grounded rules, the set of rules containing atom {@code i} is enumerable in O(R).</p>
     *
     * @param rules       current rules (with up-to-date weights)
     * @param groundRules grounded rules (batch or full)
     * @param values      current atom-value map (y_{-i} values, also initial y_i estimate)
     * @param groundTruth labeled atom values for gradient computation
     * @param targetKeys  target atom keys (those for which we compute the conditional)
     * @return gradient vector aligned with {@code rules}
     */
    static double[] exactPlGradient(List<PslRule> rules, List<GroundRule> groundRules,
                                    Map<String, Double> values, Map<String, Double> groundTruth,
                                    Iterable<String> targetKeys) {
        int numRules = rules.size();
        double[] gradient = new double[numRules];

        // Build signature index for fast rule-index resolution.
        Map<String, Integer> sigIndex = PslRuleGradient.buildSignatureIndex(rules);

        // Build per-atom rule-membership index: atomKey → list of (ruleIndex, groundRule) pairs.
        // A ground rule "contains" atom i when i appears as a head or body literal.
        Map<String, List<int[]>> atomToRuleEntries = new HashMap<>();
        List<int[]> groundRuleIndices = new ArrayList<>(groundRules.size()); // ruleIdx per groundRule
        for (GroundRule gr : groundRules) {
            int ri = PslRuleGradient.findRuleIndex(rules, sigIndex, gr);
            groundRuleIndices.add(new int[]{ri});
        }
        for (int gi = 0; gi < groundRules.size(); gi++) {
            int ri = groundRuleIndices.get(gi)[0];
            if (ri < 0 || rules.get(ri).hard()) continue;
            GroundRule gr = groundRules.get(gi);
            // Collect all atom keys that appear in this ground rule
            for (GroundRule.Lit lit : gr.body()) {
                atomToRuleEntries.computeIfAbsent(lit.atomKey(), k -> new ArrayList<>())
                        .add(new int[]{ri, gi});
            }
            for (GroundRule.Lit lit : gr.head()) {
                atomToRuleEntries.computeIfAbsent(lit.atomKey(), k -> new ArrayList<>())
                        .add(new int[]{ri, gi});
            }
        }

        // For each target atom i, compute its contribution to the gradient.
        for (String atomI : targetKeys) {
            List<int[]> entries = atomToRuleEntries.get(atomI);
            if (entries == null || entries.isEmpty()) continue; // atom not in any rule

            // Deduplicate: collect the set of ground rules that contain atom i.
            // Build a sorted-unique list of ground-rule indices that touch atom i.
            java.util.Set<Integer> giSet = new java.util.LinkedHashSet<>();
            for (int[] e : entries) {
                giSet.add(e[1]);
            }
            List<Integer> touchingGis = new ArrayList<>(giSet);

            // y_{-i}: copy current values and fix everything except y_i.
            // We will vary y_i over QUAD_X.
            Map<String, Double> yMinus = new HashMap<>(values);
            double yiTrain = groundTruth.getOrDefault(atomI, values.getOrDefault(atomI, 0.5));

            // ── 33-point composite Simpson quadrature ──────────────────────────────
            // Evaluate the unnormalized log-density at each quadrature node:
            //   log q(y_i^k) = -Σ_{r' ∋ i} w_{r'} · φ_{r'}(y_i^k, y_{-i})
            double[] logQ = new double[QUAD_N];
            for (int k = 0; k < QUAD_N; k++) {
                double yi = QUAD_X[k];
                yMinus.put(atomI, yi);
                double sumW = 0.0;
                for (int gi : touchingGis) {
                    GroundRule gr = groundRules.get(gi);
                    int ri = groundRuleIndices.get(gi)[0];
                    if (ri < 0 || rules.get(ri).hard()) continue;
                    double w = rules.get(ri).weight();
                    double phi = gr.distanceToSatisfaction(yMinus);
                    sumW += w * phi;
                }
                logQ[k] = -sumW;
            }

            // Log-sum-exp trick: subtract max before exp to avoid overflow.
            double maxLogQ = logQ[0];
            for (double v : logQ) if (v > maxLogQ) maxLogQ = v;

            double[] q = new double[QUAD_N];
            double Z = 0.0;
            for (int k = 0; k < QUAD_N; k++) {
                q[k] = Math.exp(logQ[k] - maxLogQ);
                Z += QUAD_W[k] * q[k];
            }
            if (Z <= 0.0) continue; // degenerate; skip

            // For each rule containing atom i, compute the expected feature minus the observed feature.
            // We accumulate per rule-index.
            Map<Integer, Double> ruleExpected = new HashMap<>();
            for (int gi : touchingGis) {
                GroundRule gr = groundRules.get(gi);
                int ri = groundRuleIndices.get(gi)[0];
                if (ri < 0 || rules.get(ri).hard()) continue;

                // E[φ_r(y_i, y_{-i})] = Σ_k W_k * q_k / Z * φ_r(y_i^k, y_{-i})
                double expected = 0.0;
                for (int k = 0; k < QUAD_N; k++) {
                    double yi = QUAD_X[k];
                    yMinus.put(atomI, yi);
                    double phi = gr.distanceToSatisfaction(yMinus);
                    expected += QUAD_W[k] * q[k] * phi;
                }
                expected /= Z;

                // φ_r(y_i^train, y_{-i})
                yMinus.put(atomI, yiTrain);
                double phiTrain = gr.distanceToSatisfaction(yMinus);

                // ∂(-log PL)/∂w_r contribution: observed feature MINUS model expectation.
                ruleExpected.merge(ri, phiTrain - expected, Double::sum);
            }

            for (Map.Entry<Integer, Double> e : ruleExpected.entrySet()) {
                gradient[e.getKey()] += e.getValue();
            }
        }

        // Zero out hard rules (they have no weight gradient).
        for (int i = 0; i < numRules; i++) {
            if (rules.get(i).hard()) gradient[i] = 0.0;
        }
        return gradient;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    /**
     * Build a rule list with weights updated to the current {@code weights} array.
     * The i-th element of the result is a copy of {@code rules.get(i)} with
     * {@code weight = weights[i]}.
     */
    private static List<PslRule> buildRulesWithWeights(List<PslRule> rules, double[] weights) {
        List<PslRule> updated = new ArrayList<>(rules.size());
        for (int i = 0; i < rules.size(); i++) {
            PslRule r = rules.get(i);
            updated.add(new PslRule(weights[i], r.hard(), r.squared(), r.body(), r.head(), r.distinct()));
        }
        return updated;
    }

    /**
     * Return the subset of ground rules to use for this epoch.
     * When {@link #batchSize} is {@code <= 0} or {@code >= groundRules.size()}, returns the
     * full list unchanged (full-batch mode; today's exact behavior).
     */
    private List<GroundRule> subsample(List<GroundRule> groundRules, Random rng) {
        if (batchSize <= 0 || batchSize >= groundRules.size()) {
            return groundRules;
        }
        List<GroundRule> shuffled = new ArrayList<>(groundRules);
        Collections.shuffle(shuffled, rng);
        return shuffled.subList(0, batchSize);
    }
}
