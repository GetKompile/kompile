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
import ai.kompile.graph.reasoning.psl.PslRule;

import java.util.List;
import java.util.Map;

/**
 * Shared PSL rule-gradient machinery for the weight learners ({@link StructuredPerceptronLearner} and
 * {@link PseudolikelihoodLearner}).
 *
 * <p>Both learners compute the SAME per-rule descent gradient — the mean over a rule's ground instances
 * of {@code distanceToSatisfaction(predicted) − distanceToSatisfaction(groundTruth)} — and differ only in
 * HOW they obtain the prediction (full MAP inference per epoch vs a single grounding with blended values).
 * Extracting it here, alongside {@link ProjectedGradientOptimizer} (the shared update step), leaves each
 * learner expressing only its distinct prediction strategy.</p>
 */
final class PslRuleGradient {

    private PslRuleGradient() {
    }

    /**
     * Per-rule descent gradient: for each rule index {@code i}, the mean over its ground instances of
     * {@code distanceToSatisfaction(predicted) − distanceToSatisfaction(groundTruth)}. Hard rules — and
     * rules with no ground instances — get gradient {@code 0}. Descending this with a non-negative
     * projection reproduces the classic perceptron ascent {@code w += eta·mean(distGT − distPred)}.
     *
     * @param rules       the program rules (the returned gradient is indexed parallel to this list)
     * @param groundRules the grounded rules to aggregate over
     * @param predicted   atom values under the current model (an inference result or evolving estimate)
     * @param groundTruth the labeled atom values
     * @return the gradient vector, one entry per rule
     */
    static double[] ruleGradient(List<PslRule> rules, List<GroundRule> groundRules,
                                 Map<String, Double> predicted, Map<String, Double> groundTruth) {
        int numRules = rules.size();
        double[] gradient = new double[numRules];
        int[] count = new int[numRules];

        for (GroundRule gr : groundRules) {
            int ruleIdx = findRuleIndex(rules, gr);
            if (ruleIdx < 0 || rules.get(ruleIdx).hard()) {
                continue;
            }
            gradient[ruleIdx] += gr.distanceToSatisfaction(predicted) - gr.distanceToSatisfaction(groundTruth);
            count[ruleIdx]++;
        }

        for (int i = 0; i < numRules; i++) {
            gradient[i] = count[i] > 0 ? gradient[i] / count[i] : 0.0;
            if (rules.get(i).hard()) {
                gradient[i] = 0.0;
            }
        }
        return gradient;
    }

    /**
     * Find the index of the original rule that corresponds to a ground rule, matching by the structural
     * signature (the {@code hard} / {@code squared} flags and the weight).
     *
     * @param rules the program rules
     * @param gr    a grounded rule
     * @return the matching rule index, or {@code -1} if none matches
     */
    static int findRuleIndex(List<PslRule> rules, GroundRule gr) {
        for (int i = 0; i < rules.size(); i++) {
            PslRule r = rules.get(i);
            if (r.hard() == gr.hard() && r.squared() == gr.squared()
                    && Math.abs(r.weight() - gr.weight()) < 1e-10) {
                return i;
            }
        }
        return -1;
    }
}
