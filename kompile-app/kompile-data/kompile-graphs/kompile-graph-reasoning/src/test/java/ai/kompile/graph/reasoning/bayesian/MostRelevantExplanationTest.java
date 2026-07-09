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

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MostRelevantExplanation} — greedy Most Relevant Explanation via GBF.
 *
 * <h3>Test network: two-cause noisy-OR (explaining-away)</h3>
 * <pre>
 *   causeA ──┐
 *             ├──▶ effect
 *   causeB ──┘
 *
 *   causeA: prior P(TRUE) = 0.1 (strong explanation when active)
 *   causeB: prior P(TRUE) = 0.1 (weak explanation)
 *   effect: noisy-OR — causeA strength = 0.98, causeB strength = 0.30, leak = 0.01
 * </pre>
 *
 * <p>When effect=TRUE is observed:
 * <ul>
 *   <li>causeA=TRUE is the dominant explanation (high causal strength)</li>
 *   <li>Once causeA=TRUE is committed, adding causeB=TRUE does NOT improve the
 *       explanation — the CBF for that addition will be ≤ 1 (explaining-away)</li>
 * </ul>
 */
class MostRelevantExplanationTest {

    /** Shared two-cause noisy-OR network. */
    private BayesianNetwork net;

    /**
     * Compute GBF directly from VE queries for a single-variable explanation.
     * Used to hand-verify the computeGbf() implementation.
     */
    private double directGbf(BayesianNetwork network, Map<String, Integer> evidence,
                               String var, int state) {
        // P(var=state | evidence) via VE
        Factor postFactor = VariableElimination.query(network, var, evidence);
        postFactor = postFactor.normalize();
        double pXGivenE = postFactor.getValues()[state];

        // P(var=state) prior via VE with empty evidence
        Factor priorFactor = VariableElimination.query(network, var, Map.of());
        priorFactor = priorFactor.normalize();
        double pX = priorFactor.getValues()[state];

        double eps = MostRelevantExplanation.EPS;
        double clampedPXGivenE = Math.max(eps, Math.min(1.0 - eps, pXGivenE));
        double clampedPX       = Math.max(eps, Math.min(1.0 - eps, pX));

        double num = clampedPXGivenE * (1.0 - clampedPX);
        double den = clampedPX       * (1.0 - clampedPXGivenE);
        return den > 0 ? num / den : Double.MAX_VALUE / 2.0;
    }

    @BeforeEach
    void buildNetwork() {
        net = new BayesianNetwork();

        // causeA: rare but strong
        BayesianNode causeA = new BayesianNode("causeA", "causeA", "Cause A");
        net.addNode(causeA);
        causeA.setCpt(NoisyOrCpt.buildPrior("causeA", 0.1));

        // causeB: rare and weak
        BayesianNode causeB = new BayesianNode("causeB", "causeB", "Cause B");
        net.addNode(causeB);
        causeB.setCpt(NoisyOrCpt.buildPrior("causeB", 0.1));

        // effect: noisy-OR(causeA:0.98, causeB:0.30, leak:0.01)
        BayesianNode effect = new BayesianNode("effect", "effect", "Effect");
        net.addNode(effect);
        net.addEdge("causeA", "effect");
        net.addEdge("causeB", "effect");
        effect.setCpt(NoisyOrCpt.buildCpt("effect",
                List.of("causeA", "causeB"),
                new double[]{0.98, 0.30},
                0.01));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PARSIMONY: causeA alone explains effect=TRUE
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Parsimony — greedy MRE selects only causeA=TRUE")
    class Parsimony {

        /**
         * With effect=TRUE observed:
         * - The greedy search should select causeA=TRUE as the first (and only) step.
         * - causeB=TRUE should NOT be added (it is explained away by causeA=TRUE).
         * - The result's assignment should contain only causeA=TRUE.
         */
        @Test
        @DisplayName("MRE returns {causeA=TRUE} only — causeB is explaining-away'd")
        void onlyCauseASelected() {
            Map<String, Integer> evidence = Map.of("effect", 1);
            MostRelevantExplanation.MreResult result = MostRelevantExplanation.explain(net, evidence);

            assertEquals(1, result.assignment().size(),
                    "MRE should select exactly one variable; got: " + result.assignment());
            assertTrue(result.assignment().containsKey("causeA"),
                    "MRE should select causeA; got: " + result.assignment());
            assertEquals(1, result.assignment().get("causeA"),
                    "causeA should be selected as TRUE (state=1); got: " + result.assignment());
        }

        /**
         * GBF of the selected explanation must be > 1 (evidence is explained by causeA=TRUE
         * better than by the prior alone).
         */
        @Test
        @DisplayName("GBF of {causeA=TRUE} is greater than 1")
        void gbfGreaterThanOne() {
            Map<String, Integer> evidence = Map.of("effect", 1);
            MostRelevantExplanation.MreResult result = MostRelevantExplanation.explain(net, evidence);

            assertTrue(result.gbf() > 1.0,
                    "GBF of MRE result should be > 1 (positive explanation); got: " + result.gbf());
        }

        /**
         * Hand-verify the GBF of {causeA=TRUE} against a direct computation via
         * VE marginal queries.
         *
         * <p>For a single-variable explanation {causeA=state} with observed evidence {effect=1}:
         * <pre>
         *   GBF({causeA=1}|effect=1)
         *     = [P(causeA=1|effect=1)·(1-P(causeA=1))] / [P(causeA=1)·(1-P(causeA=1|effect=1))]
         * </pre>
         * </p>
         */
        @Test
        @DisplayName("GBF of {causeA=TRUE} matches direct VE computation")
        void gbfMatchesDirectComputation() {
            Map<String, Integer> evidence = Map.of("effect", 1);

            double directGbfCauseATrue = directGbf(net, evidence, "causeA", 1);

            Map<String, Integer> explanation = Map.of("causeA", 1);
            double computedGbf = MostRelevantExplanation.computeGbf(net, evidence, explanation);

            assertEquals(directGbfCauseATrue, computedGbf, 1e-9,
                    "computeGbf({causeA=TRUE}|effect=TRUE) should match direct VE computation");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // EXPLAINING-AWAY: adding causeB=TRUE worsens or doesn't improve the GBF
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Explaining-away — causeB=TRUE not added after causeA=TRUE")
    class ExplainingAway {

        /**
         * With {causeA=TRUE} already committed, the CBF of adding {causeB=TRUE}
         * must be ≤ 1 + DEFAULT_MIN_GAIN, meaning it is not added by the greedy search.
         *
         * <p>Computed directly as GBF({causeA=1,causeB=1}|e) / GBF({causeA=1}|e).
         */
        @Test
        @DisplayName("CBF of adding causeB=TRUE after causeA=TRUE is ≤ 1+minGain")
        void causeBADdsNegativeCBF() {
            Map<String, Integer> evidence = Map.of("effect", 1);

            Map<String, Integer> withCauseA     = Map.of("causeA", 1);
            Map<String, Integer> withCauseAAndB = new LinkedHashMap<>();
            withCauseAAndB.put("causeA", 1);
            withCauseAAndB.put("causeB", 1);

            double gbfA    = MostRelevantExplanation.computeGbf(net, evidence, withCauseA);
            double gbfAB   = MostRelevantExplanation.computeGbf(net, evidence, withCauseAAndB);
            double cbfForB = gbfAB / Math.max(gbfA, MostRelevantExplanation.EPS);

            assertTrue(cbfForB <= 1.0 + MostRelevantExplanation.DEFAULT_MIN_GAIN,
                    "After committing causeA=TRUE, CBF of adding causeB=TRUE should be ≤ 1+minGain; got CBF=" + cbfForB);
        }

        /**
         * The full MRE result must not contain causeB at all.
         */
        @Test
        @DisplayName("causeB is absent from the MRE assignment")
        void causeBAbsent() {
            Map<String, Integer> evidence = Map.of("effect", 1);
            MostRelevantExplanation.MreResult result = MostRelevantExplanation.explain(net, evidence);

            assertFalse(result.assignment().containsKey("causeB"),
                    "causeB must not appear in the MRE assignment; got: " + result.assignment());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CANDIDATE RESTRICTION AND K CAP
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Candidate restriction and k cap")
    class CandidateRestriction {

        /**
         * When causeA is excluded from the candidate set, the MRE should select
         * causeB=TRUE instead (next-best explanation).
         */
        @Test
        @DisplayName("Restricting candidates to {causeB} selects causeB=TRUE")
        void restrictedCandidateSet() {
            Map<String, Integer> evidence = Map.of("effect", 1);
            MostRelevantExplanation.MreResult result = MostRelevantExplanation.explain(
                    net, evidence,
                    Set.of("causeB"),    // only causeB is a candidate
                    MostRelevantExplanation.DEFAULT_MAX_K,
                    MostRelevantExplanation.DEFAULT_MIN_GAIN);

            assertFalse(result.assignment().containsKey("causeA"),
                    "causeA must not appear when excluded from candidates");
            // causeB should be selected if its GBF > 1+minGain
            if (!result.assignment().isEmpty()) {
                assertTrue(result.assignment().containsKey("causeB"),
                        "The only candidate (causeB) should be selected if it improves GBF");
            }
        }

        /**
         * k=1 cap: the greedy search must not return more than 1 variable.
         */
        @Test
        @DisplayName("k=1 cap limits result to at most 1 variable")
        void kCapLimitsResult() {
            Map<String, Integer> evidence = Map.of("effect", 1);
            MostRelevantExplanation.MreResult result = MostRelevantExplanation.explain(
                    net, evidence,
                    null,   // all candidates
                    1,      // k cap = 1
                    MostRelevantExplanation.DEFAULT_MIN_GAIN);

            assertTrue(result.assignment().size() <= 1,
                    "k=1 should limit assignment to at most 1 variable; got: " + result.assignment());
        }

        /**
         * k=0 cap: result must be empty.
         */
        @Test
        @DisplayName("k=0 cap returns empty assignment")
        void kZeroReturnsEmpty() {
            Map<String, Integer> evidence = Map.of("effect", 1);
            MostRelevantExplanation.MreResult result = MostRelevantExplanation.explain(
                    net, evidence, null, 0, MostRelevantExplanation.DEFAULT_MIN_GAIN);

            assertTrue(result.assignment().isEmpty(),
                    "k=0 should return empty assignment; got: " + result.assignment());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // NARRATIVE LINES
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Narrative lines populated")
    class NarrativeLines {

        /**
         * Each greedy step must produce exactly one narrative line.
         */
        @Test
        @DisplayName("narrative has same length as stepGbfs and assignment")
        void narrativeLengthMatchesSteps() {
            Map<String, Integer> evidence = Map.of("effect", 1);
            MostRelevantExplanation.MreResult result = MostRelevantExplanation.explain(net, evidence);

            assertEquals(result.assignment().size(), result.narrative().size(),
                    "narrative must have one entry per greedy step");
            assertEquals(result.assignment().size(), result.stepGbfs().size(),
                    "stepGbfs must have one entry per greedy step");
        }

        /**
         * Each narrative line should mention the variable name and the GBF transition.
         */
        @Test
        @DisplayName("narrative line mentions the added variable")
        void narrativeMentionsVariable() {
            Map<String, Integer> evidence = Map.of("effect", 1);
            MostRelevantExplanation.MreResult result = MostRelevantExplanation.explain(net, evidence);

            for (int i = 0; i < result.narrative().size(); i++) {
                String line = result.narrative().get(i);
                // Must contain "added" and "GBF"
                assertTrue(line.contains("added") && line.contains("GBF"),
                        "Narrative line " + i + " should contain 'added' and 'GBF'; got: " + line);
            }
        }

        /**
         * stepGbfs must be monotonically increasing (each step improves the GBF).
         */
        @Test
        @DisplayName("stepGbfs are monotonically non-decreasing")
        void stepGbfsNonDecreasing() {
            Map<String, Integer> evidence = Map.of("effect", 1);
            // Use a looser minGain to allow more steps (if any)
            MostRelevantExplanation.MreResult result = MostRelevantExplanation.explain(
                    net, evidence, null, 4, 0.0);

            List<Double> gbfs = result.stepGbfs();
            for (int i = 1; i < gbfs.size(); i++) {
                assertTrue(gbfs.get(i) >= gbfs.get(i - 1) - 1e-12,
                        "stepGbfs must be non-decreasing; step " + (i - 1) + "=" + gbfs.get(i - 1) +
                        " > step " + i + "=" + gbfs.get(i));
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // EMPTY EVIDENCE
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Empty evidence: MRE may return empty or low-GBF result")
    void emptyEvidenceDoesNotCrash() {
        assertDoesNotThrow(() -> {
            MostRelevantExplanation.MreResult result = MostRelevantExplanation.explain(net, Map.of());
            // GBF with no evidence is 1.0 baseline; any improvement must be > 1+minGain
            // May or may not select variables depending on priors
            assertNotNull(result.assignment());
            assertNotNull(result.narrative());
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    // GBF OF EMPTY EXPLANATION IS 1.0
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("computeGbf of empty explanation returns 1.0")
    void emptyExplanationGbfIsOne() {
        double gbf = MostRelevantExplanation.computeGbf(net, Map.of("effect", 1), Map.of());
        assertEquals(1.0, gbf, 1e-12,
                "GBF of empty explanation should be exactly 1.0");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // MRE RESULT MAINTAINS INSERTION ORDER
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("MreResult.assignment() preserves insertion (selection) order")
    void insertionOrderPreserved() {
        // Use a larger network where multiple steps might be added
        BayesianNetwork multiNet = new BayesianNetwork();
        BayesianNode x = new BayesianNode("X", "X", "X");
        BayesianNode y = new BayesianNode("Y", "Y", "Y");
        BayesianNode obs = new BayesianNode("obs", "obs", "Obs");
        multiNet.addNode(x);
        multiNet.addNode(y);
        multiNet.addNode(obs);
        multiNet.addEdge("X", "obs");
        multiNet.addEdge("Y", "obs");
        x.setCpt(NoisyOrCpt.buildPrior("X", 0.2));
        y.setCpt(NoisyOrCpt.buildPrior("Y", 0.05));
        obs.setCpt(NoisyOrCpt.buildCpt("obs", List.of("X", "Y"), new double[]{0.95, 0.5}, 0.01));

        MostRelevantExplanation.MreResult result = MostRelevantExplanation.explain(
                multiNet, Map.of("obs", 1), null, 4, 0.0);

        // The LinkedHashMap should preserve insertion order; verify by iterating
        List<String> orderedKeys = new ArrayList<>(result.assignment().keySet());
        // The order must be consistent with the narrative order
        for (int i = 0; i < result.narrative().size(); i++) {
            if (i < orderedKeys.size()) {
                assertTrue(result.narrative().get(i).contains(orderedKeys.get(i)),
                        "Narrative line " + i + " should mention key " + orderedKeys.get(i));
            }
        }
    }
}
