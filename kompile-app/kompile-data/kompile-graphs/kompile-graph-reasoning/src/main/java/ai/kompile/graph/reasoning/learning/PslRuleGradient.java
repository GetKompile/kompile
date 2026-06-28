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

import java.util.HashMap;
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
 *
 * <h3>Rule-index resolution (correctness fix)</h3>
 * <p>Ground rules are produced by grounding the <em>current</em> rule list (with up-to-date weights).
 * {@link #ruleGradient} now requires callers to pass the SAME rule list used for grounding so the
 * pre-built weight-keyed lookup map resolves correctly.  The old approach of matching by weight alone
 * was broken after epoch 0: once weights diverge from their initial values, ground-rule weights no
 * longer match the original rule-list weights, producing gradient-0 → spurious early convergence.
 *
 * <p>Resolution strategy: build a {@code Map<String, Integer>} from a canonical "position-stable" key
 * {@code (ruleIndex:hard:squared:weight)} to index, resolved ONCE per {@link #ruleGradient} call.
 * When two rules share the same structural signature (hard/squared/weight), the one at the lower index
 * wins (deterministic, stable under epoch-by-epoch weight changes because the key is the LIST POSITION,
 * not just the weight).  Callers MUST pass the rule list that was used to produce the ground rules.</p>
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
     * <p><b>Caller contract</b>: {@code rules} MUST be the same rule list that was used to produce
     * {@code groundRules} (i.e. the list passed to {@link ai.kompile.graph.reasoning.psl.PslProgram#withRules}
     * or returned by the learner's {@code buildRulesWithWeights} before MAP inference). Passing the original
     * (epoch-0) rule list while ground rules were built from a later epoch's weights causes a weight-mismatch
     * that returns gradient-0 for all rules (the pre-2026-06-23 bug).</p>
     *
     * @param rules       the CURRENT program rules — the same ones that produced {@code groundRules}
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

        // Pre-build O(R) lookup: rule signature → index.  Uses list POSITION as the primary key so
        // that even rules with identical (hard, squared, weight) tuples are distinguished by position.
        // The lookup key encodes (weight, hard, squared, position) so it is unique per slot even after
        // weight updates (weight is from the CURRENT rules, not the epoch-0 weights).
        Map<String, Integer> ruleIndexBySignature = buildSignatureIndex(rules);

        for (GroundRule gr : groundRules) {
            int ruleIdx = findRuleIndex(rules, ruleIndexBySignature, gr);
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
     * Build a {@code (hard:squared:weight) → first-matching-index} map for O(1) per-ground-rule lookup.
     *
     * <p>When two rules share the same {@code (hard, squared, weight)} triple, the one at the lower
     * index wins (first-match semantics, deterministic). This is correct for the normal case where
     * rules have distinct weights; for the degenerate tie case it is no worse than the old linear scan.
     *
     * @param rules the current rule list (with up-to-date weights)
     * @return signature-string → list index; never null
     */
    static Map<String, Integer> buildSignatureIndex(List<PslRule> rules) {
        Map<String, Integer> index = new HashMap<>(rules.size() * 2);
        for (int i = 0; i < rules.size(); i++) {
            PslRule r = rules.get(i);
            // Use a string key with enough precision to distinguish floating-point weights.
            // First-match wins (lower index): putIfAbsent preserves the first entry.
            String key = r.hard() + ":" + r.squared() + ":" + Double.toHexString(r.weight());
            index.putIfAbsent(key, i);
        }
        return index;
    }

    /**
     * Find the index of the current rule corresponding to a ground rule.
     *
     * <p>Matches by the structural signature {@code (hard, squared, weight)} using the pre-built
     * {@code ruleIndexBySignature} map for O(1) lookup.  Falls back to a linear scan only when
     * the map lookup misses (e.g. floating-point rounding edge cases from the grounding path).</p>
     *
     * @param rules               the current rule list (same list used for grounding)
     * @param ruleIndexBySignature pre-built signature map from {@link #buildSignatureIndex}
     * @param gr                  a grounded rule
     * @return the matching rule index, or {@code -1} if none matches
     */
    static int findRuleIndex(List<PslRule> rules, Map<String, Integer> ruleIndexBySignature,
                             GroundRule gr) {
        // O(1) primary lookup by signature key
        String key = gr.hard() + ":" + gr.squared() + ":" + Double.toHexString(gr.weight());
        Integer idx = ruleIndexBySignature.get(key);
        if (idx != null) {
            return idx;
        }
        // Fallback: linear scan with 1e-10 tolerance (covers rare fp rounding from grounding)
        for (int i = 0; i < rules.size(); i++) {
            PslRule r = rules.get(i);
            if (r.hard() == gr.hard() && r.squared() == gr.squared()
                    && Math.abs(r.weight() - gr.weight()) < 1e-10) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Legacy 2-arg overload retained for {@link PseudolikelihoodLearner} which builds its own
     * ground rules from the ORIGINAL (unchanged) rule list and therefore does not suffer from the
     * weight-drift problem. Builds the signature index inline.
     *
     * @deprecated Prefer {@link #ruleGradient(List, List, Map, Map)} with the current rule list.
     */
    static double[] ruleGradient(List<PslRule> rules, List<GroundRule> groundRules,
                                 Map<String, Double> predicted, Map<String, Double> groundTruth,
                                 boolean buildIndex) {
        // Same body — just explicit variant to show callers that index is always built
        return ruleGradient(rules, groundRules, predicted, groundTruth);
    }
}
