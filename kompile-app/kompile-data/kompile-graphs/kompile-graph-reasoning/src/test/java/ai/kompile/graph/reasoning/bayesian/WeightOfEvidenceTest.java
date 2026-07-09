/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.bayesian;

import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link WeightOfEvidence} — Good's weight of evidence decomposition.
 *
 * <h3>Test network: noisy-OR with one cause and two effects</h3>
 * <pre>
 *   cause → effect1
 *   cause → effect2
 * </pre>
 * Three binary variables, all FALSE/TRUE.
 * cause:   prior P(TRUE) = 0.5
 * effect1: noisy-OR P(TRUE | cause=TRUE) ≈ 0.85, leak ≈ 0.05
 * effect2: noisy-OR P(TRUE | cause=TRUE) ≈ 0.85, leak ≈ 0.05
 *
 * When BOTH effects are observed TRUE the cause is strongly supported.
 * When one effect is TRUE and the other FALSE, that FALSE effect refutes the cause.
 *
 * @see <a href="https://doi.org/10.1093/biomet/72.2.359">Good 1985, Weight of Evidence</a>
 */
class WeightOfEvidenceTest {

    /** Shared 3-node noisy-OR network: cause → effect1, cause → effect2. */
    private BayesianNetwork net;

    @BeforeEach
    void buildNetwork() {
        net = new BayesianNetwork();

        // cause node: P(cause=TRUE) = 0.5
        BayesianNode cause = new BayesianNode("cause", "cause", "Cause");
        net.addNode(cause);
        cause.setCpt(NoisyOrCpt.buildPrior("cause", 0.5));

        // effect1 node: noisy-OR parent = cause, strength = 0.85
        BayesianNode effect1 = new BayesianNode("effect1", "effect1", "Effect 1");
        net.addNode(effect1);
        net.addEdge("cause", "effect1");
        effect1.setCpt(NoisyOrCpt.buildCpt("effect1", List.of("cause"), new double[]{0.85}, 0.05));

        // effect2 node: noisy-OR parent = cause, strength = 0.85
        BayesianNode effect2 = new BayesianNode("effect2", "effect2", "Effect 2");
        net.addNode(effect2);
        net.addEdge("cause", "effect2");
        effect2.setCpt(NoisyOrCpt.buildCpt("effect2", List.of("cause"), new double[]{0.85}, 0.05));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // BASIC WoE SIGNS
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("WoE sign semantics")
    class WoeSigns {

        /**
         * Both effects observed TRUE → both findings should have positive W (support cause).
         * The positive weights confirm that P(eᵢ|cause=TRUE) > P(eᵢ|cause=FALSE).
         */
        @Test
        @DisplayName("Both effects TRUE → both weights positive (support cause)")
        void bothEffectsTrueGivesPositiveWeights() {
            Map<String, Integer> evidence = Map.of("effect1", 1, "effect2", 1);

            List<WeightOfEvidence.FindingWeight> weights =
                    WeightOfEvidence.decompose(net, "cause", 1, evidence);

            assertEquals(2, weights.size(), "Should have one weight per evidence finding");

            for (WeightOfEvidence.FindingWeight fw : weights) {
                assertTrue(fw.weightNats() > 0,
                        "Finding " + fw.findingVar() + " (TRUE) should support cause, got W=" + fw.weightNats());
            }
        }

        /**
         * effect1=TRUE, effect2=FALSE.
         * effect1 (TRUE) supports cause → W > 0.
         * effect2 (FALSE) refutes cause → W < 0.
         */
        @Test
        @DisplayName("One TRUE, one FALSE — TRUE supports, FALSE refutes")
        void onePositiveOneFalseGivesMixedSigns() {
            Map<String, Integer> evidence = Map.of("effect1", 1, "effect2", 0);

            List<WeightOfEvidence.FindingWeight> weights =
                    WeightOfEvidence.decompose(net, "cause", 1, evidence);

            assertEquals(2, weights.size());

            WeightOfEvidence.FindingWeight wEffect1 = weights.stream()
                    .filter(fw -> fw.findingVar().equals("effect1")).findFirst().orElseThrow();
            WeightOfEvidence.FindingWeight wEffect2 = weights.stream()
                    .filter(fw -> fw.findingVar().equals("effect2")).findFirst().orElseThrow();

            assertTrue(wEffect1.weightNats() > 0,
                    "effect1=TRUE should have W > 0, got: " + wEffect1.weightNats());
            assertTrue(wEffect2.weightNats() < 0,
                    "effect2=FALSE should have W < 0 (refutes cause), got: " + wEffect2.weightNats());
        }

        /**
         * Sort order: findings are returned sorted by |W| descending.
         * With both effects TRUE, the magnitudes should be equal (symmetric network),
         * so order is non-decreasing (pass if |W₁| ≥ |W₂|).
         */
        @Test
        @DisplayName("Findings sorted by |W| descending")
        void sortOrderByAbsoluteWeightDescending() {
            Map<String, Integer> evidence = Map.of("effect1", 1, "effect2", 1);

            List<WeightOfEvidence.FindingWeight> weights =
                    WeightOfEvidence.decompose(net, "cause", 1, evidence);

            for (int i = 1; i < weights.size(); i++) {
                double prev = Math.abs(weights.get(i - 1).weightNats());
                double curr = Math.abs(weights.get(i).weightNats());
                assertTrue(prev >= curr - 1e-12,
                        "Weights not sorted descending by |W|: " + prev + " < " + curr);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // POSTERIOR CONSISTENCY
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Posterior consistency")
    class PosteriorConsistency {

        /**
         * posteriorWith in each FindingWeight must match a direct VE query with full evidence.
         * This checks that WoE doesn't accidentally use a different evidence set for the
         * "full" posterior than the caller intended.
         */
        @Test
        @DisplayName("posteriorWith matches direct VE query with full evidence")
        void posteriorWithMatchesDirectQuery() {
            Map<String, Integer> evidence = Map.of("effect1", 1, "effect2", 1);

            // Direct query for ground truth
            Factor directFactor = VariableElimination.query(net, "cause", evidence);
            double directPosterior = directFactor.normalize().getValues()[1]; // P(cause=TRUE)

            List<WeightOfEvidence.FindingWeight> weights =
                    WeightOfEvidence.decompose(net, "cause", 1, evidence);

            for (WeightOfEvidence.FindingWeight fw : weights) {
                assertEquals(directPosterior, fw.posteriorWith(), 1e-9,
                        "posteriorWith for " + fw.findingVar() + " must equal direct VE posterior");
            }
        }

        /**
         * posteriorWithout should differ from posteriorWith when the finding is informative
         * (i.e., the posterior shifts when we remove one of the findings).
         */
        @Test
        @DisplayName("Removing an informative finding shifts the posterior")
        void posteriorShiftWhenFindingRemoved() {
            Map<String, Integer> evidence = Map.of("effect1", 1, "effect2", 1);
            List<WeightOfEvidence.FindingWeight> weights =
                    WeightOfEvidence.decompose(net, "cause", 1, evidence);

            for (WeightOfEvidence.FindingWeight fw : weights) {
                assertNotEquals(fw.posteriorWithout(), fw.posteriorWith(), 1e-6,
                        "Informative finding " + fw.findingVar() +
                        " should shift posterior when removed");
            }
        }

        /**
         * When both effects are FALSE, the cause should have posterior < prior (0.5).
         * All weights should be negative.
         */
        @Test
        @DisplayName("Both effects FALSE → posterior below prior, all weights negative")
        void bothEffectsFalseReducesPosterior() {
            Map<String, Integer> evidence = Map.of("effect1", 0, "effect2", 0);

            Factor fullFactor = VariableElimination.query(net, "cause", evidence);
            double posterior = fullFactor.normalize().getValues()[1];
            assertTrue(posterior < 0.5, "Both FALSE should push posterior below prior 0.5, got: " + posterior);

            List<WeightOfEvidence.FindingWeight> weights =
                    WeightOfEvidence.decompose(net, "cause", 1, evidence);

            for (WeightOfEvidence.FindingWeight fw : weights) {
                assertTrue(fw.weightNats() < 0,
                        "Both-FALSE finding " + fw.findingVar() + " should have W < 0, got: " + fw.weightNats());
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // VALIDATION & GUARD CASES
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Validation guards")
    class ValidationGuards {

        /**
         * Non-binary hypothesis variable → clear IllegalArgumentException.
         */
        @Test
        @DisplayName("Non-binary hypothesis throws IllegalArgumentException")
        void nonBinaryHypothesisThrows() {
            // Add a ternary node
            BayesianNode ternary = new BayesianNode("ternary", "ternary", "Ternary",
                    List.of("LOW", "MEDIUM", "HIGH"));
            net.addNode(ternary);
            // Give it a trivial CPT so VE doesn't crash before the guard fires
            ternary.setCpt(new Factor(List.of("ternary"), new int[]{3}, new double[]{0.33, 0.34, 0.33}));

            assertThrows(IllegalArgumentException.class,
                    () -> WeightOfEvidence.decompose(net, "ternary", 0, Map.of()),
                    "Non-binary hypothesis should throw IllegalArgumentException");
        }

        /**
         * Unknown hypothesis variable → clear IllegalArgumentException.
         */
        @Test
        @DisplayName("Unknown hypothesis variable throws IllegalArgumentException")
        void unknownHypothesisThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> WeightOfEvidence.decompose(net, "nonexistent", 1, Map.of()),
                    "Unknown hypothesis should throw IllegalArgumentException");
        }

        /**
         * Hypothesis variable already in evidence → clear IllegalArgumentException.
         */
        @Test
        @DisplayName("Hypothesis in evidence throws IllegalArgumentException")
        void hypothesisInEvidenceThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> WeightOfEvidence.decompose(net, "cause", 1, Map.of("cause", 1, "effect1", 1)),
                    "Observed hypothesis should throw IllegalArgumentException");
        }

        /**
         * Empty evidence → empty weights list (no findings to decompose).
         */
        @Test
        @DisplayName("Empty evidence returns empty weight list")
        void emptyEvidenceReturnsEmptyList() {
            List<WeightOfEvidence.FindingWeight> weights =
                    WeightOfEvidence.decompose(net, "cause", 1, Map.of());
            assertTrue(weights.isEmpty(), "No evidence → no finding weights");
        }
    }
}
