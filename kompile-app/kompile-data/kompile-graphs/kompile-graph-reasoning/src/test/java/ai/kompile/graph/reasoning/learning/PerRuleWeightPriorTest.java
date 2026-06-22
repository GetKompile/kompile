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

import ai.kompile.graph.reasoning.psl.PslProgram;
import ai.kompile.graph.reasoning.psl.PslRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for per-rule prior mean MAP regularization in {@link StructuredPerceptronLearner}.
 *
 * <p>The central invariant: a rule whose prior mean is HIGH should end up with a higher
 * learned weight than an otherwise identical rule whose prior mean is LOW — the per-rule
 * MAP gradient {@code lambda * (w[r] - priorMean[r])} pulls each rule weight toward its
 * OWN mean. This is the fix for "established rules still need more weight": the old scalar
 * path shrank ALL rules toward the same small mean, de-weighting established rules.</p>
 *
 * <p>Backward-compatibility invariant: the scalar (6-arg) constructor must produce exactly
 * the same result as a per-rule array where every entry is the scalar mean — verified by
 * {@link #scalarAndUniformArrayProduceSameResult()}.</p>
 */
class PerRuleWeightPriorTest {

    /**
     * Two-rule program: rule 0 is "established" (high-value atom), rule 1 is "speculative".
     * Ground truth has State(alice)=1.0 (established) and Noise(carol)=0.2 (speculative).
     */
    private PslProgram twoRuleProgram() {
        PslProgram p = new PslProgram();
        // Rule 0: propagate State (established — value=1.0)
        p.addRule("1.0: State(?X) -> derived_State(?X) ^2");
        // Rule 1: propagate Noise (speculative — value=0.2)
        p.addRule("0.2: Noise(?X) -> derived_Noise(?X) ^2");
        p.observe("State", 1.0, "alice");
        p.observe("Noise", 0.2, "carol");
        p.target("derived_State", "alice");
        p.target("derived_Noise", "carol");
        return p;
    }

    private Map<String, Double> groundTruth() {
        return Map.of(
                "State(alice)", 1.0,
                "Noise(carol)", 0.2,
                "derived_State(alice)", 1.0,
                "derived_Noise(carol)", 0.2
        );
    }

    // ── Test 1: per-rule prior means regularize each weight toward its own target ──

    @Test
    @DisplayName("Per-rule MAP: rule 0 (high prior mean) settles higher than rule 1 (low prior mean)")
    void perRulePriorMean_pullsEachRuleTowardItsOwnTarget() {
        double highMean = 0.9;   // established rule prior
        double lowMean  = 0.1;   // speculative rule prior
        double[] perRuleMeans = {highMean, lowMean};

        StructuredPerceptronLearner learner = new StructuredPerceptronLearner(
                0.05, 1e-4, 0, 42L, 5.0, 0.1, perRuleMeans);
        List<PslRule> learned = learner.learn(twoRuleProgram(), groundTruth(), 200);

        assertEquals(2, learned.size(), "Must have 2 rules");
        double w0 = learned.get(0).weight();
        double w1 = learned.get(1).weight();

        // Rule 0 has a high prior mean → should end up higher than rule 1
        assertTrue(w0 > w1,
                "Rule with high prior mean (" + highMean + ") must settle higher than rule with low prior mean ("
                        + lowMean + "); got w0=" + w0 + ", w1=" + w1);

        // Both weights must stay non-negative (the non-negativity projection in the optimizer)
        assertTrue(w0 >= 0.0, "w0 must be non-negative; got " + w0);
        assertTrue(w1 >= 0.0, "w1 must be non-negative; got " + w1);
    }

    // ── Test 2: uniform array produces same result as scalar constructor ─────────

    @Test
    @DisplayName("Backward compat: per-rule array with uniform value equals scalar constructor")
    void scalarAndUniformArrayProduceSameResult() {
        double scalarMean = 0.3;
        double lambda = 2.0;
        int seed = 7;

        StructuredPerceptronLearner scalar = new StructuredPerceptronLearner(
                0.1, 1e-4, 0, seed, lambda, scalarMean);

        double[] uniformArray = {scalarMean, scalarMean};
        StructuredPerceptronLearner perRule = new StructuredPerceptronLearner(
                0.1, 1e-4, 0, seed, lambda, scalarMean, uniformArray);

        List<PslRule> scalarResult = scalar.learn(twoRuleProgram(), groundTruth(), 80);
        List<PslRule> perRuleResult = perRule.learn(twoRuleProgram(), groundTruth(), 80);

        assertEquals(scalarResult.size(), perRuleResult.size());
        for (int i = 0; i < scalarResult.size(); i++) {
            assertEquals(scalarResult.get(i).weight(), perRuleResult.get(i).weight(), 1e-12,
                    "Uniform per-rule array must match scalar path exactly for rule " + i);
        }
    }

    // ── Test 3: null array falls back to scalar path ──────────────────────────────

    @Test
    @DisplayName("Per-rule null array falls back to scalar path (no NPE, exact match)")
    void nullArrayFallsBackToScalarPath() {
        double scalar = 0.2;
        double lambda = 1.5;
        int seed = 99;

        StructuredPerceptronLearner scalarLearner = new StructuredPerceptronLearner(
                0.1, 1e-4, 0, seed, lambda, scalar);
        StructuredPerceptronLearner nullArrayLearner = new StructuredPerceptronLearner(
                0.1, 1e-4, 0, seed, lambda, scalar, null);

        List<PslRule> a = scalarLearner.learn(twoRuleProgram(), groundTruth(), 80);
        List<PslRule> b = nullArrayLearner.learn(twoRuleProgram(), groundTruth(), 80);

        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).weight(), b.get(i).weight(), 1e-12,
                    "Null per-rule array must reproduce scalar path exactly for rule " + i);
        }
    }

    // ── Test 4: short array falls back for tail rules ─────────────────────────────

    @Test
    @DisplayName("Short per-rule array: tail rules beyond array boundary fall back to scalar mean")
    void shortArray_tailRulesFallBackToScalarMean() {
        double establishedMean = 0.9;
        double scalarFallback  = 0.15;
        double lambda = 4.0;

        // Array only covers rule 0 (established); rule 1 falls back to scalarFallback
        double[] shortArray = {establishedMean};  // length 1, program has 2 rules

        StructuredPerceptronLearner withShortArray = new StructuredPerceptronLearner(
                0.05, 1e-4, 0, 13L, lambda, scalarFallback, shortArray);
        StructuredPerceptronLearner scalarFallbackLearner = new StructuredPerceptronLearner(
                0.05, 1e-4, 0, 13L, lambda, scalarFallback);

        List<PslRule> shortResult  = withShortArray.learn(twoRuleProgram(), groundTruth(), 150);
        List<PslRule> scalarResult = scalarFallbackLearner.learn(twoRuleProgram(), groundTruth(), 150);

        assertEquals(2, shortResult.size());

        // Rule 0: short array provides established mean → higher than scalar fallback learner's rule 0
        // Rule 1: falls back to scalarFallback → must match scalar-fallback learner's rule 1
        // Rule 1 uses the scalar fallback as its prior mean in both learners, so it is regularized
        // toward the same target. The two learned weights are not bit-identical: rule 0's different
        // prior (0.9 vs 0.15) shifts the joint MAP inference that feeds rule 1's data gradient — the
        // structured-perceptron update couples rules through the shared solve.
        assertEquals(scalarResult.get(1).weight(), shortResult.get(1).weight(), 0.02,
                "Rule 1 (beyond short array) should track the scalar-fallback prior (small MAP coupling allowed)");

        // Rule 0 should be pulled higher than rule 1 (prior 0.9 vs fallback 0.15)
        assertTrue(shortResult.get(0).weight() >= shortResult.get(1).weight(),
                "Rule 0 (high prior 0.9) must not be below rule 1 (scalar fallback 0.15); got w0="
                        + shortResult.get(0).weight() + ", w1=" + shortResult.get(1).weight());
    }

    // ── Test 5: existing WeightPriorRegularizationTest cases still pass ────────────
    // The 6-arg constructor must be entirely unchanged in behavior.

    @Test
    @DisplayName("6-arg scalar constructor: lambda=0 reproduces unregularized MLE (WeightPriorRegularizationTest parity)")
    void sixArgConstructor_lambdaZero_reproducesUnregularizedMle() {
        StructuredPerceptronLearner baseline =
                new StructuredPerceptronLearner(0.1, 1e-4, 0, 7L);          // 4-arg → lambda 0
        StructuredPerceptronLearner lambdaZero =
                new StructuredPerceptronLearner(0.1, 1e-4, 0, 7L, 0.0, 0.0); // explicit lambda 0
        List<PslRule> a = baseline.learn(twoRuleProgram(), groundTruth(), 50);
        List<PslRule> b = lambdaZero.learn(twoRuleProgram(), groundTruth(), 50);
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).weight(), b.get(i).weight(), 1e-12,
                    "lambda=0 must reproduce pure MLE exactly for rule " + i);
        }
    }

    // ── Test 6: strong per-rule prior pulls each weight near its own mean ─────────

    @Test
    @DisplayName("Strong per-rule prior: each weight settles near its own prior mean")
    void strongPerRulePrior_pullsEachWeightNearItsOwnMean() {
        double mean0 = 0.8;
        double mean1 = 0.2;
        double[] perRuleMeans = {mean0, mean1};

        // Very strong lambda — prior dominates data gradient
        StructuredPerceptronLearner learner = new StructuredPerceptronLearner(
                0.05, 1e-4, 0, 17L, 10.0, 0.5, perRuleMeans);
        List<PslRule> learned = learner.learn(twoRuleProgram(), groundTruth(), 300);

        assertEquals(2, learned.size());
        // With dominant prior, each rule must settle within a loose band around its mean
        assertEquals(mean0, learned.get(0).weight(), 0.3,
                "Rule 0 must settle near its prior mean " + mean0 + "; got " + learned.get(0).weight());
        assertEquals(mean1, learned.get(1).weight(), 0.3,
                "Rule 1 must settle near its prior mean " + mean1 + "; got " + learned.get(1).weight());
    }
}
