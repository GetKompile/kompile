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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for MAP (L2 / Gaussian-prior) weight regularization in {@link StructuredPerceptronLearner}.
 *
 * <p>MAP = MLE + log-prior. {@code weightPriorStrength=0} is pure MLE (the prior, un-regularized
 * behaviour); a positive prior shrinks rule weights toward {@code weightPriorMean} — the rigorous
 * form of "start near zero; let the data justify larger weights".</p>
 */
class WeightPriorRegularizationTest {

    private PslProgram program() {
        PslProgram p = new PslProgram();
        p.addRule("1.0: State(X) & Link(X, Y) -> State(Y) ^2");
        p.addRule("0.5: ~State(N) ^2");
        p.observe("State", 1.0, "alice");
        p.observe("Link", 1.0, "alice", "bob");
        p.target("State", "bob");
        p.target("State", "carol");
        return p;
    }

    private Map<String, Double> groundTruth() {
        return Map.of("State(alice)", 1.0, "Link(alice, bob)", 1.0,
                "State(bob)", 1.0, "State(carol)", 0.0);
    }

    private double sumWeights(List<PslRule> rules) {
        double s = 0.0;
        for (PslRule r : rules) {
            s += r.weight();
        }
        return s;
    }

    @Test
    void lambdaZeroReproducesUnregularizedMleExactly() {
        StructuredPerceptronLearner baseline =
                new StructuredPerceptronLearner(0.1, 1e-4, 0, 7L);          // 4-arg -> lambda 0
        StructuredPerceptronLearner lambdaZero =
                new StructuredPerceptronLearner(0.1, 1e-4, 0, 7L, 0.0, 0.0); // explicit lambda 0
        List<PslRule> a = baseline.learn(program(), groundTruth(), 50);
        List<PslRule> b = lambdaZero.learn(program(), groundTruth(), 50);
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).weight(), b.get(i).weight(), 1e-12,
                    "lambda=0 must reproduce pure MLE exactly for rule " + i);
        }
    }

    @Test
    void zeroMeanPriorShrinksWeightsTowardZero() {
        StructuredPerceptronLearner baseline =
                new StructuredPerceptronLearner(0.1, 1e-4, 0, 7L);
        StructuredPerceptronLearner regularized =
                new StructuredPerceptronLearner(0.1, 1e-4, 0, 7L, 3.0, 0.0);
        List<PslRule> base = baseline.learn(program(), groundTruth(), 50);
        List<PslRule> reg = regularized.learn(program(), groundTruth(), 50);

        // A zero-mean prior pulls every weight down: total shrinks and none grows beyond baseline.
        assertTrue(sumWeights(reg) < sumWeights(base),
                "regularized total " + sumWeights(reg) + " should be < baseline " + sumWeights(base));
        for (int i = 0; i < base.size(); i++) {
            assertTrue(reg.get(i).weight() <= base.get(i).weight() + 1e-9,
                    "regularized weight must not exceed baseline for rule " + i);
            assertTrue(reg.get(i).weight() >= 0.0, "weights remain non-negative");
        }
    }

    @Test
    void strongPriorPullsWeightsTowardPriorMean() {
        double priorMean = 0.3;
        StructuredPerceptronLearner learner =
                new StructuredPerceptronLearner(0.1, 1e-4, 0, 7L, 8.0, priorMean);
        List<PslRule> learned = learner.learn(program(), groundTruth(), 100);

        // With a dominant prior (eta*lambda < 2, so stable), every weight settles near the prior
        // mean regardless of the data — the whole point of "start near the prior, not at a hardcode".
        for (PslRule r : learned) {
            assertEquals(priorMean, r.weight(), 0.25,
                    "strong prior should pull weight near priorMean; got " + r.weight());
        }
    }
}
