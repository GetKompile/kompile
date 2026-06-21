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
        this.learningRate = learningRate;
        this.tolerance = tolerance;
        this.batchSize = batchSize;
        this.seed = seed;
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
