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
import ai.kompile.graph.reasoning.psl.HlMrfMapInference;
import ai.kompile.graph.reasoning.psl.PslProgram;
import ai.kompile.graph.reasoning.psl.PslRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Structured perceptron weight learner for PSL rules.
 *
 * <p>Per epoch:
 * <ol>
 *   <li>Run MAP inference with current weights to get predicted values.</li>
 *   <li>For each ground rule, compute the difference between distance-to-satisfaction
 *       under ground truth and under the prediction.</li>
 *   <li>Update weight: {@code w_r += eta * (distGT - distPred)}</li>
 *   <li>Project weights to be non-negative: {@code w_r = max(0, w_r)}</li>
 * </ol>
 * Convergence: max weight change across all rules {@code < tolerance}.</p>
 */
public class StructuredPerceptronLearner implements WeightLearner {

    private static final Logger log = LoggerFactory.getLogger(StructuredPerceptronLearner.class);

    /** Sentinel: {@code batchSize <= 0} means use all ground rules (full-batch mode). */
    private static final int FULL_BATCH = 0;
    private static final long DEFAULT_SEED = 1234L;

    private final double learningRate;
    private final double tolerance;
    private final int batchSize;
    private final long seed;
    private final double weightPriorStrength;
    /** Scalar fallback prior mean — used when {@link #perRuleWeightPriorMean} is null. */
    private final double weightPriorMean;
    /**
     * Per-rule prior means array (length == number of rules). When non-null, the MAP
     * gradient for rule {@code r} uses {@code perRuleWeightPriorMean[r]} in place of the
     * scalar {@link #weightPriorMean}. If the array is shorter than the rule count the
     * tail rules fall back to the scalar mean (safe for forward-compatible programs that
     * grow more rules than the array was built for).
     *
     * <p>null = scalar path (exact prior behavior — all existing tests unchanged).</p>
     */
    private final double[] perRuleWeightPriorMean;

    public StructuredPerceptronLearner() {
        this(0.1, 1e-4, FULL_BATCH, DEFAULT_SEED);
    }

    public StructuredPerceptronLearner(double learningRate, double tolerance) {
        this(learningRate, tolerance, FULL_BATCH, DEFAULT_SEED);
    }

    /**
     * Mini-batch constructor.
     *
     * @param learningRate learning rate (positive)
     * @param tolerance    convergence threshold
     * @param batchSize    number of ground rules to sample per epoch; {@code <= 0} or
     *                     {@code >= groundRules.size()} means full-batch (today's behavior)
     * @param seed         RNG seed for reproducible subsampling
     */
    public StructuredPerceptronLearner(double learningRate, double tolerance, int batchSize, long seed) {
        this(learningRate, tolerance, batchSize, seed, 0.0, 0.0);
    }

    /**
     * Full constructor with MAP (L2 / Gaussian-prior) weight regularization.
     *
     * <p>MAP = MLE + log-prior. A Gaussian prior {@code N(weightPriorMean, 1/weightPriorStrength)}
     * on each rule weight contributes the penalty gradient
     * {@code weightPriorStrength * (w - weightPriorMean)} to the structured-perceptron descent
     * gradient, shrinking weights toward the prior mean unless the data pulls them away.
     * {@code weightPriorStrength = 0} disables regularization (pure MLE — the exact prior
     * behaviour). A small prior mean is the principled form of "start near zero; let the data
     * justify larger weights" — weights no longer drift unboundedly under noisy labels.</p>
     *
     * @param weightPriorStrength lambda {@code >= 0}; 0 disables regularization (pure MLE)
     * @param weightPriorMean     the prior mean each rule weight is shrunk toward (all rules
     *                            share the same mean; use the per-rule overload for band-aware priors)
     */
    public StructuredPerceptronLearner(double learningRate, double tolerance, int batchSize, long seed,
                                       double weightPriorStrength, double weightPriorMean) {
        this.learningRate = learningRate;
        this.tolerance = tolerance;
        this.batchSize = batchSize;
        this.seed = seed;
        this.weightPriorStrength = weightPriorStrength;
        this.weightPriorMean = weightPriorMean;
        this.perRuleWeightPriorMean = null;  // scalar path
    }

    /**
     * Per-rule prior mean constructor for band-aware MAP regularization.
     *
     * <p>This overload replaces the global {@code weightPriorMean} with a per-rule array so
     * that each rule is regularized toward its own prior mean rather than a single shared mean.
     * The motivating use-case is band-aware priors: rules whose supporting atoms are in the
     * ESTABLISHED or HIGH band (structural / deductive basis) are given a <em>higher</em> prior
     * mean and therefore a higher warm-start weight, while speculative rules keep a low mean and
     * converge more slowly upward. This prevents the global-mean path from de-weighting
     * established rules even when there is strong empirical support for them.</p>
     *
     * <p>Contract:
     * <ul>
     *   <li>When {@code perRuleMeans} is non-null and {@code perRuleMeans.length > ruleIndex},
     *       the MAP gradient for rule {@code ruleIndex} is
     *       {@code lambda * (w[ruleIndex] - perRuleMeans[ruleIndex])}.</li>
     *   <li>When {@code perRuleMeans} is shorter than the program's rule list, rules past the
     *       array boundary fall back to the scalar {@code weightPriorMean} (forward-compatible
     *       when the program grows more rules than the array was built for).</li>
     *   <li>Passing {@code null} for {@code perRuleMeans} reproduces the scalar path exactly
     *       (equivalent to the 6-arg constructor with the same scalar mean).</li>
     * </ul>
     *
     * <p>Backward compatibility: all existing 6-arg-and-below constructors route through the
     * scalar path ({@code perRuleWeightPriorMean = null}); their behavior is unchanged.</p>
     *
     * @param learningRate        gradient step size
     * @param tolerance           convergence threshold on max per-epoch weight change
     * @param batchSize           mini-batch size; 0 = full-batch
     * @param seed                RNG seed for reproducible subsampling
     * @param weightPriorStrength lambda {@code >= 0}; 0 = pure MLE (per-rule array ignored)
     * @param weightPriorMean     scalar fallback mean for rules past the array boundary
     * @param perRuleMeans        per-rule prior means (may be null → scalar path)
     */
    public StructuredPerceptronLearner(double learningRate, double tolerance, int batchSize, long seed,
                                       double weightPriorStrength, double weightPriorMean,
                                       double[] perRuleMeans) {
        this.learningRate = learningRate;
        this.tolerance = tolerance;
        this.batchSize = batchSize;
        this.seed = seed;
        this.weightPriorStrength = weightPriorStrength;
        this.weightPriorMean = weightPriorMean;
        this.perRuleWeightPriorMean = (perRuleMeans != null) ? Arrays.copyOf(perRuleMeans, perRuleMeans.length) : null;
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

        ProjectedGradientOptimizer optimizer = new ProjectedGradientOptimizer(
                learningRate, tolerance, ProjectedGradientOptimizer.nonNegative());

        // Single RNG seeded once for the whole run, ensuring deterministic subsampling.
        Random rng = new Random(seed);

        boolean converged = false;
        int epoch;

        for (epoch = 0; epoch < maxEpochs; epoch++) {
            // Build program with current weights and run MAP inference
            List<PslRule> currentRules = buildRulesWithWeights(rules, weights);
            PslProgram currentProgram = rebuildProgram(program, currentRules);

            HlMrfMapInference.Result result;
            try {
                result = HlMrfMapInference.solve(currentProgram);
            } catch (Exception e) {
                log.warn("StructuredPerceptronLearner: inference failed at epoch {}: {}", epoch, e.getMessage());
                break;
            }

            Map<String, Double> predicted = result.values();
            List<GroundRule> groundRules = result.groundRules();

            // Mini-batch subsampling: use a subset if batchSize is in range.
            List<GroundRule> batch = subsample(groundRules, rng);

            // Per-rule descent gradient = mean(distPred - distGT); shared with PseudolikelihoodLearner.
            double[] gradient = PslRuleGradient.ruleGradient(rules, batch, predicted, groundTruth);

            // MAP regularization (Gaussian prior on weights): MAP = MLE + log-prior. The penalty
            // (weightPriorStrength/2)*(w[r] - priorMean[r])^2 contributes
            // weightPriorStrength*(w[r] - priorMean[r]) to the descent gradient, so optimizer.step
            // shrinks each weight toward ITS prior mean unless the data gradient pulls it away.
            // weightPriorStrength=0 is a no-op (pure structured-perceptron MLE — exact prior behaviour).
            //
            // Per-rule path: when perRuleWeightPriorMean is non-null, each rule uses its own mean
            // (band-aware: established rules → higher mean → maintained weight; speculative → low mean).
            // Rules past the array boundary fall back to the scalar weightPriorMean.
            // Scalar path: perRuleWeightPriorMean==null → all rules share weightPriorMean (old behavior).
            if (weightPriorStrength > 0.0) {
                for (int i = 0; i < gradient.length; i++) {
                    double mean = effectivePriorMean(i);
                    gradient[i] += weightPriorStrength * (weights[i] - mean);
                }
            }

            double maxChange = optimizer.step(weights, gradient);
            log.debug("StructuredPerceptronLearner epoch {}: maxChange={}", epoch, maxChange);

            if (optimizer.converged(maxChange)) {
                converged = true;
                break;
            }
        }

        log.info("StructuredPerceptronLearner: {} epochs, converged={}", epoch, converged);
        return buildRulesWithWeights(rules, weights);
    }

    /**
     * Return the effective prior mean for rule {@code ruleIndex}.
     *
     * <p>When a per-rule array is present and covers this index, returns the per-rule value.
     * Otherwise falls back to the scalar {@link #weightPriorMean}.</p>
     */
    private double effectivePriorMean(int ruleIndex) {
        if (perRuleWeightPriorMean != null && ruleIndex < perRuleWeightPriorMean.length) {
            return perRuleWeightPriorMean[ruleIndex];
        }
        return weightPriorMean;
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

    private List<PslRule> buildRulesWithWeights(List<PslRule> rules, double[] weights) {
        List<PslRule> updated = new ArrayList<>(rules.size());
        for (int i = 0; i < rules.size(); i++) {
            PslRule r = rules.get(i);
            updated.add(new PslRule(weights[i], r.hard(), r.squared(), r.body(), r.head(), r.distinct()));
        }
        return updated;
    }

    /**
     * Rebuild a PslProgram with updated rules, preserving all atom declarations.
     */
    PslProgram rebuildProgram(PslProgram original, List<PslRule> updatedRules) {
        PslProgram fresh = new PslProgram();
        for (PslRule rule : updatedRules) {
            fresh.addRule(rule);
        }
        // Re-declare all atoms from the original program
        for (String key : original.atomKeys()) {
            String predicate = extractPredicate(key);
            String[] args = extractArgs(key);
            if (original.isObserved(key)) {
                fresh.observe(predicate, original.value(key), args);
            } else {
                fresh.target(predicate, args);
            }
        }
        return fresh;
    }

    private String extractPredicate(String atomKey) {
        int lp = atomKey.indexOf('(');
        return lp < 0 ? atomKey : atomKey.substring(0, lp).trim();
    }

    private String[] extractArgs(String atomKey) {
        int lp = atomKey.indexOf('(');
        if (lp < 0) return new String[0];
        int rp = atomKey.lastIndexOf(')');
        if (rp <= lp) return new String[0];
        String inside = atomKey.substring(lp + 1, rp).trim();
        if (inside.isEmpty()) return new String[0];
        String[] parts = inside.split(",");
        for (int i = 0; i < parts.length; i++) parts[i] = parts[i].trim();
        return parts;
    }
}
