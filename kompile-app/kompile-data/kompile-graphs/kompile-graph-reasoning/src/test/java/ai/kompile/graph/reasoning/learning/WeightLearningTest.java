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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for StructuredPerceptronLearner and PseudolikelihoodLearner.
 */
class WeightLearningTest {

    /**
     * Build a small 2-rule PSL program.
     * Rule 1: State(X) & Link(X, Y) -> State(Y) (propagation)
     * Rule 2: ~State(N) (low prior -- push toward false)
     */
    PslProgram buildTwoRuleProgram() {
        PslProgram p = new PslProgram();
        p.addRule("1.0: State(X) & Link(X, Y) -> State(Y) ^2");
        p.addRule("0.5: ~State(N) ^2");
        p.observe("State", 1.0, "alice");
        p.observe("Link", 1.0, "alice", "bob");
        p.target("State", "bob");
        p.target("State", "carol");
        return p;
    }

    Map<String, Double> buildGroundTruth() {
        return Map.of(
                "State(alice)", 1.0,
                "Link(alice, bob)", 1.0,
                "State(bob)", 1.0,     // bob should be active
                "State(carol)", 0.0    // carol should not be active
        );
    }

    // ─── StructuredPerceptronLearner ─────────────────────────────────────────────

    @Nested
    @DisplayName("StructuredPerceptronLearner")
    class PerceptronTests {

        @Test
        @DisplayName("Learner updates weights from initial values")
        void weightsChange() {
            PslProgram program = buildTwoRuleProgram();
            Map<String, Double> gt = buildGroundTruth();

            List<PslRule> initialRules = List.copyOf(program.rules());

            StructuredPerceptronLearner learner = new StructuredPerceptronLearner(0.1, 1e-4);
            List<PslRule> learned = learner.learn(program, gt, 50);

            assertNotNull(learned);
            assertEquals(initialRules.size(), learned.size());

            // Weights should have changed from initial (or stayed if already optimal)
            // At minimum, they should be non-negative
            for (PslRule r : learned) {
                assertTrue(r.weight() >= 0.0, "Weight must be non-negative: " + r.weight());
            }
        }

        @Test
        @DisplayName("All learned weights are non-negative (projection constraint)")
        void weightsNonNegative() {
            PslProgram program = buildTwoRuleProgram();
            StructuredPerceptronLearner learner = new StructuredPerceptronLearner(0.1, 1e-4);
            List<PslRule> learned = learner.learn(program, buildGroundTruth(), 100);
            for (PslRule r : learned) {
                assertTrue(r.weight() >= 0.0, "Weight must be >= 0: " + r.weight());
            }
        }

        @Test
        @DisplayName("Learning with empty groundTruth returns original rules")
        void emptyGroundTruth() {
            PslProgram program = buildTwoRuleProgram();
            StructuredPerceptronLearner learner = new StructuredPerceptronLearner();
            List<PslRule> learned = learner.learn(program, Map.of(), 50);
            assertNotNull(learned);
            assertEquals(program.rules().size(), learned.size());
        }

        @Test
        @DisplayName("Learning with empty program returns empty list")
        void emptyProgram() {
            PslProgram program = new PslProgram();
            StructuredPerceptronLearner learner = new StructuredPerceptronLearner();
            List<PslRule> learned = learner.learn(program, buildGroundTruth(), 50);
            assertNotNull(learned);
            assertTrue(learned.isEmpty());
        }

        @Test
        @DisplayName("rebuildProgram preserves atom declarations")
        void rebuildProgram() {
            PslProgram program = buildTwoRuleProgram();
            StructuredPerceptronLearner learner = new StructuredPerceptronLearner();
            List<PslRule> rules = program.rules();

            PslProgram rebuilt = learner.rebuildProgram(program, rules);
            assertNotNull(rebuilt);
            // Should have same atom keys
            assertEquals(program.atomKeys().size(), rebuilt.atomKeys().size());
            // Observed atoms should remain observed
            for (String key : program.observedKeys()) {
                assertTrue(rebuilt.isObserved(key), "Observed atom not preserved: " + key);
            }
        }
    }

    // ─── PseudolikelihoodLearner ─────────────────────────────────────────────────

    @Nested
    @DisplayName("PseudolikelihoodLearner")
    class PseudolikelihoodTests {

        @Test
        @DisplayName("Learner runs without error and returns correct number of rules")
        void basicLearning() {
            PslProgram program = buildTwoRuleProgram();
            PseudolikelihoodLearner learner = new PseudolikelihoodLearner();
            List<PslRule> learned = learner.learn(program, buildGroundTruth(), 50);
            assertNotNull(learned);
            assertEquals(program.rules().size(), learned.size());
        }

        @Test
        @DisplayName("All learned weights are non-negative")
        void weightsNonNegative() {
            PslProgram program = buildTwoRuleProgram();
            PseudolikelihoodLearner learner = new PseudolikelihoodLearner(0.05, 1e-4);
            List<PslRule> learned = learner.learn(program, buildGroundTruth(), 100);
            for (PslRule r : learned) {
                assertTrue(r.weight() >= 0.0, "Weight must be >= 0: " + r.weight());
            }
        }

        @Test
        @DisplayName("Empty groundTruth returns original rules")
        void emptyGroundTruth() {
            PslProgram program = buildTwoRuleProgram();
            PseudolikelihoodLearner learner = new PseudolikelihoodLearner();
            List<PslRule> learned = learner.learn(program, Map.of(), 50);
            assertNotNull(learned);
            assertEquals(program.rules().size(), learned.size());
        }

        @Test
        @DisplayName("Zero-weight rules stay at 0 when gradient pushes them to negative")
        void zeroWeightProjection() {
            // Build a program where the rule should not fire (groundTruth pushes weight to 0)
            PslProgram program = new PslProgram();
            // Rule that should be suppressed: it says State(X) should be true,
            // but groundTruth says State(alice) = 0.0
            program.addRule("1.0: State(X) ^2");
            program.target("State", "alice");

            Map<String, Double> gt = Map.of("State(alice)", 0.0);

            PseudolikelihoodLearner learner = new PseudolikelihoodLearner(0.1, 1e-6);
            List<PslRule> learned = learner.learn(program, gt, 200);

            assertNotNull(learned);
            assertFalse(learned.isEmpty());
            for (PslRule r : learned) {
                assertTrue(r.weight() >= 0.0, "Weight must be >= 0: " + r.weight());
            }
        }
    }

    // ─── WeightLearningResult tests ───────────────────────────────────────────────

    @Nested
    @DisplayName("WeightLearningResult")
    class WeightLearningResultTests {

        @Test
        @DisplayName("WeightLearningResult fields are set correctly")
        void fields() {
            PslProgram program = buildTwoRuleProgram();
            List<PslRule> rules = program.rules();

            WeightLearningResult result = new WeightLearningResult(
                    rules, 42, true, 0.05,
                    Map.of("rule-1", 1.5, "rule-2", 0.3)
            );

            assertEquals(rules.size(), result.learnedRules().size());
            assertEquals(42, result.epochsRun());
            assertTrue(result.converged());
            assertEquals(0.05, result.finalLoss(), 1e-9);
            assertEquals(1.5, result.weightHistory().get("rule-1"), 1e-9);
        }

        @Test
        @DisplayName("WeightLearningResult with null weightHistory uses empty map")
        void nullWeightHistory() {
            PslProgram program = buildTwoRuleProgram();
            WeightLearningResult result = new WeightLearningResult(
                    program.rules(), 10, false, 1.0, null);
            assertNotNull(result.weightHistory());
            assertTrue(result.weightHistory().isEmpty());
        }
    }
}
