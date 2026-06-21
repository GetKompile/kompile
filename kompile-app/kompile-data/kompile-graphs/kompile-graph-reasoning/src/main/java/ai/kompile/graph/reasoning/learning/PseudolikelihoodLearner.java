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
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Pseudolikelihood weight learner for PSL rules.
 *
 * <p>Cheaper than the structured perceptron: instead of running full MAP inference
 * per epoch, this directly computes the pseudolikelihood gradient using the ground-truth
 * assignment and a single grounding pass.</p>
 *
 * <p>For each ground rule:
 * {@code d(logPL)/d(w_r) = distGT_r - distPred_r}
 * where distPred uses the current atom values (initialized to 0.5 for targets).
 * Update: {@code w_r += eta * (distGT_r - distPred_r)}, projected to {@code w_r >= 0}.</p>
 */
public class PseudolikelihoodLearner implements WeightLearner {

    private static final Logger log = LoggerFactory.getLogger(PseudolikelihoodLearner.class);

    /** Sentinel: {@code batchSize <= 0} means use all ground rules (full-batch mode). */
    private static final int FULL_BATCH = 0;
    private static final long DEFAULT_SEED = 1234L;

    private final double learningRate;
    private final double tolerance;
    private final int batchSize;
    private final long seed;

    public PseudolikelihoodLearner() {
        this(0.05, 1e-4, FULL_BATCH, DEFAULT_SEED);
    }

    public PseudolikelihoodLearner(double learningRate, double tolerance) {
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
    public PseudolikelihoodLearner(double learningRate, double tolerance, int batchSize, long seed) {
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

            // Per-rule descent gradient = mean(distPred - distGT); shared with StructuredPerceptronLearner.
            double[] gradient = PslRuleGradient.ruleGradient(rules, batch, currentValues, groundTruth);
            double maxChange = optimizer.step(weights, gradient);

            // Update currentValues for target atoms based on gradient direction
            // (simplified: blend toward groundTruth for observed atoms)
            for (String key : program.targetKeys()) {
                double gt = groundTruth.getOrDefault(key, 0.5);
                double cur = currentValues.getOrDefault(key, 0.5);
                currentValues.put(key, cur + 0.1 * (gt - cur));
            }

            log.debug("PseudolikelihoodLearner epoch {}: maxChange={}", epoch, maxChange);

            if (optimizer.converged(maxChange)) {
                converged = true;
                break;
            }
        }

        log.info("PseudolikelihoodLearner: {} epochs, converged={}", epoch, converged);

        // Build final rules with learned weights
        List<PslRule> updated = new ArrayList<>(numRules);
        for (int i = 0; i < numRules; i++) {
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
