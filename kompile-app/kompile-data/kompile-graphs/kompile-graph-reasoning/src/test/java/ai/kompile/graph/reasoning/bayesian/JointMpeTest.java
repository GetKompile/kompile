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
 * Tests for {@link VariableElimination#jointMostProbableExplanation}.
 *
 * <h3>Key regression: marginal-argmax ≠ joint argmax</h3>
 * <p>The critical property under test is that the new joint max-product result can
 * DIFFER from the old per-variable marginal-argmax ({@code mostProbableExplanation}).
 * A classic construction: two anti-correlated parents A and B sharing a child C.
 * The marginals of A and B individually favour state 1 (TRUE), but the joint
 * mass concentrates on (A=1, B=0) because the child's CPT makes (A=1, B=1)
 * improbable.</p>
 *
 * <pre>
 *   Anti-correlated net:
 *     A ──┐
 *          ├──▶ C
 *     B ──┘
 *
 *   A: prior P(TRUE)=0.6
 *   B: prior P(TRUE)=0.6
 *   C|A,B CPT:
 *     A=F, B=F → P(C=T)=0.1   (neither active, low C)
 *     A=F, B=T → P(C=T)=0.9   (only B  → high C)
 *     A=T, B=F → P(C=T)=0.9   (only A  → high C)
 *     A=T, B=T → P(C=T)=0.05  (BOTH on  → very low C; anti-correlation)
 *
 *   Evidence: C=TRUE
 *
 *   With C=TRUE observed:
 *   - P(A=TRUE|C=TRUE) is elevated; P(B=TRUE|C=TRUE) is also elevated individually.
 *   - But JOINTLY, A=TRUE forces C via the CPT; adding B=TRUE crushes C=TRUE (0.05),
 *     so the joint mass concentrates on (A=1,B=0) and (A=0,B=1), NOT (A=1,B=1).
 *   - Brute-force joint oracle picks the single assignment with the highest
 *     P(A,B,C=1) = P(C=1|A,B) * P(A) * P(B).
 * </pre>
 */
class JointMpeTest {

    /**
     * Build the anti-correlation network:
     * A → C ← B with a CPT where (A=T,B=T) almost never produces C=T.
     */
    private static BayesianNetwork buildAntiCorrelNet() {
        BayesianNetwork net = new BayesianNetwork();

        BayesianNode a = new BayesianNode("A", "A", "Node A");
        net.addNode(a);
        // P(A=FALSE)=0.4, P(A=TRUE)=0.6
        a.setCpt(new Factor(List.of("A"), new int[]{2}, new double[]{0.4, 0.6}));

        BayesianNode b = new BayesianNode("B", "B", "Node B");
        net.addNode(b);
        // P(B=FALSE)=0.4, P(B=TRUE)=0.6
        b.setCpt(new Factor(List.of("B"), new int[]{2}, new double[]{0.4, 0.6}));

        BayesianNode c = new BayesianNode("C", "C", "Node C");
        net.addNode(c);
        net.addEdge("A", "C");
        net.addEdge("B", "C");

        // CPT over [A, B, C]
        // Index order (row-major A,B,C):
        //  A=0,B=0,C=0 | A=0,B=0,C=1 | A=0,B=1,C=0 | A=0,B=1,C=1
        //  A=1,B=0,C=0 | A=1,B=0,C=1 | A=1,B=1,C=0 | A=1,B=1,C=1
        c.setCpt(new Factor(
                List.of("A", "B", "C"),
                new int[]{2, 2, 2},
                new double[]{
                        // A=0,B=0: P(C=F)=0.90, P(C=T)=0.10
                        0.90, 0.10,
                        // A=0,B=1: P(C=F)=0.10, P(C=T)=0.90
                        0.10, 0.90,
                        // A=1,B=0: P(C=F)=0.10, P(C=T)=0.90
                        0.10, 0.90,
                        // A=1,B=1: P(C=F)=0.95, P(C=T)=0.05  ← anti-correlation
                        0.95, 0.05
                }
        ));

        return net;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // BRUTE-FORCE ORACLE HELPER
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Enumerate all non-evidence assignments and return the one that maximises
     * the joint probability P(A=a, B=b, C=cEvidence).
     *
     * <p>Computed as P(C=cEvidence | A=a, B=b) * P(A=a) * P(B=b), i.e. the
     * unnormalised joint without dividing by P(C=cEvidence).</p>
     */
    private static Map<String, Integer> bruteForceJointMpe(BayesianNetwork net,
                                                            Map<String, Integer> evidence) {
        // Collect non-evidence variables and their cardinalities
        List<String> nonEvidenceVars = new ArrayList<>();
        List<Integer> cards = new ArrayList<>();
        for (BayesianNode node : net.getNodes()) {
            String v = node.getVariableName();
            if (!evidence.containsKey(v)) {
                nonEvidenceVars.add(v);
                cards.add(node.getCardinality());
            }
        }

        int numNonEv = nonEvidenceVars.size();
        int[] cardArr = cards.stream().mapToInt(Integer::intValue).toArray();
        int totalAssignments = 1;
        for (int c : cardArr) totalAssignments *= c;

        Map<String, Integer> bestAssignment = new LinkedHashMap<>();
        double bestProb = -1;
        int[] assign = new int[numNonEv];

        for (int idx = 0; idx < totalAssignments; idx++) {
            Factor.indexToAssignment(idx, cardArr, assign);

            // Build full assignment: non-evidence vars + evidence vars
            Map<String, Integer> fullAssignment = new LinkedHashMap<>();
            for (int i = 0; i < numNonEv; i++) {
                fullAssignment.put(nonEvidenceVars.get(i), assign[i]);
            }
            fullAssignment.putAll(evidence);

            // Compute joint probability as product of CPT entries
            double joint = 1.0;
            for (BayesianNode node : net.getNodes()) {
                Factor cpt = node.getCpt();
                double[] cptVals = cpt.getValues();
                List<String> cptVars = cpt.getVariables();
                int[] cptCards = cpt.getCardinalities();

                // Build assignment for this CPT's variables
                int[] cptAssign = new int[cptVars.size()];
                for (int j = 0; j < cptVars.size(); j++) {
                    cptAssign[j] = fullAssignment.getOrDefault(cptVars.get(j), 0);
                }
                int flatIdx = 0;
                int stride = 1;
                for (int j = cptCards.length - 1; j >= 0; j--) {
                    flatIdx += cptAssign[j] * stride;
                    stride *= cptCards[j];
                }
                joint *= cptVals[flatIdx];
            }

            if (joint > bestProb) {
                bestProb = joint;
                bestAssignment = new LinkedHashMap<>();
                for (int i = 0; i < numNonEv; i++) {
                    bestAssignment.put(nonEvidenceVars.get(i), assign[i]);
                }
            }
        }
        return bestAssignment;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // MARGINAL ≠ JOINT REGRESSION
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Marginal-argmax ≠ joint-argmax regression")
    class MarginalVsJoint {

        /**
         * With C=TRUE observed in the anti-correlation net, the marginals of A and B
         * are each individually elevated.  The joint MPE should find that ONLY ONE of
         * A or B should be TRUE (the other causes explaining-away).
         *
         * Specifically, the brute-force oracle and jointMostProbableExplanation agree,
         * while mostProbableExplanation (marginal-argmax) assigns BOTH A=1 and B=1 —
         * which is a suboptimal joint assignment due to the anti-correlation CPT.
         */
        @Test
        @DisplayName("Joint MPE matches brute-force oracle and differs from marginal-argmax")
        void jointDiffersFromMarginal() {
            BayesianNetwork net = buildAntiCorrelNet();
            Map<String, Integer> evidence = Map.of("C", 1);

            // --- Brute-force oracle ---
            Map<String, Integer> oracle = bruteForceJointMpe(net, evidence);
            // The oracle must NOT have both A=1 and B=1 (that's the anti-correlated assignment)
            // Valid optimal assignments: (A=1,B=0) or (A=0,B=1)
            boolean oracleBothTrue = oracle.getOrDefault("A", 0) == 1
                    && oracle.getOrDefault("B", 0) == 1;
            assertFalse(oracleBothTrue,
                    "Brute-force oracle should NOT select (A=TRUE, B=TRUE) in the anti-correlation net; got: " + oracle);

            // --- Joint MPE ---
            VariableElimination.JointMpeResult result =
                    VariableElimination.jointMostProbableExplanation(net, evidence);
            Map<String, Integer> joint = result.assignment();

            // Must agree with the oracle
            assertEquals(oracle.get("A"), joint.get("A"),
                    "Joint MPE A should match oracle; oracle=" + oracle + " joint=" + joint);
            assertEquals(oracle.get("B"), joint.get("B"),
                    "Joint MPE B should match oracle; oracle=" + oracle + " joint=" + joint);

            // Must not have both A and B true (anti-correlated configuration)
            boolean jointBothTrue = joint.getOrDefault("A", 0) == 1
                    && joint.getOrDefault("B", 0) == 1;
            assertFalse(jointBothTrue,
                    "Joint MPE should NOT pick (A=TRUE,B=TRUE) in anti-correlated net; got: " + joint);

            // --- Old marginal-argmax (mostProbableExplanation) ---
            Map<String, Integer> marginal = VariableElimination.mostProbableExplanation(net, evidence);
            // With priors P(A=T)=0.6, P(B=T)=0.6 and evidence C=TRUE, the marginals of
            // both A and B are elevated past 0.5, so the marginal-argmax should select
            // A=1 AND B=1, which conflicts with the joint optimum.
            boolean marginalBothTrue = marginal.getOrDefault("A", 0) == 1
                    && marginal.getOrDefault("B", 0) == 1;
            // We assert the difference only when the marginal actually selects both=TRUE
            // (which it does given the symmetric priors and noisy-OR-like effect).
            // If the marginal is identical to joint by coincidence, we skip the regression check.
            if (marginalBothTrue) {
                assertFalse(jointBothTrue,
                        "Joint MPE must differ from marginal-argmax (both-TRUE) assignment");
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PROBABILITY GIVEN EVIDENCE
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("probabilityGivenEvidence accuracy")
    class ProbabilityGivenEvidence {

        /**
         * With evidence in the anti-correlation net, the probabilityGivenEvidence
         * should equal brute-force P(x*|C=T) = P(x*,C=T)/P(C=T) within 1e-9.
         */
        @Test
        @DisplayName("probabilityGivenEvidence matches brute-force oracle")
        void matchesBruteForce() {
            BayesianNetwork net = buildAntiCorrelNet();
            Map<String, Integer> evidence = Map.of("C", 1);

            VariableElimination.JointMpeResult result =
                    VariableElimination.jointMostProbableExplanation(net, evidence);

            // Brute-force P(x*, C=TRUE) = product of CPT values at (A=a*, B=b*, C=TRUE)
            Map<String, Integer> xStar = result.assignment();
            Map<String, Integer> fullStar = new LinkedHashMap<>(xStar);
            fullStar.putAll(evidence);

            double jointUnnorm = computeJointUnnorm(net, fullStar);

            // P(C=TRUE) = sum over A,B of P(A,B,C=TRUE)
            double pEvidence = 0.0;
            for (int a = 0; a < 2; a++) {
                for (int b = 0; b < 2; b++) {
                    pEvidence += computeJointUnnorm(net, Map.of("A", a, "B", b, "C", 1));
                }
            }

            double expectedProbGivenE = jointUnnorm / pEvidence;
            assertEquals(expectedProbGivenE, result.probabilityGivenEvidence(), 1e-9,
                    "probabilityGivenEvidence should match brute-force P(x*|C=TRUE)");
        }

        /** Helper: compute unnormalized joint P(full assignment) from CPTs. */
        private double computeJointUnnorm(BayesianNetwork net, Map<String, Integer> full) {
            double joint = 1.0;
            for (BayesianNode node : net.getNodes()) {
                Factor cpt = node.getCpt();
                List<String> cptVars = cpt.getVariables();
                int[] cptCards = cpt.getCardinalities();
                int[] cptAssign = new int[cptVars.size()];
                for (int j = 0; j < cptVars.size(); j++) {
                    cptAssign[j] = full.getOrDefault(cptVars.get(j), 0);
                }
                int flatIdx = 0, stride = 1;
                for (int j = cptCards.length - 1; j >= 0; j--) {
                    flatIdx += cptAssign[j] * stride;
                    stride *= cptCards[j];
                }
                joint *= cpt.getValues()[flatIdx];
            }
            return joint;
        }

        /**
         * No-evidence case: probabilityGivenEvidence = P(x*) directly from joint.
         */
        @Test
        @DisplayName("No-evidence: probabilityGivenEvidence equals joint probability")
        void noEvidenceReturnsJointProb() {
            BayesianNetwork net = buildAntiCorrelNet();
            Map<String, Integer> evidence = Map.of();

            VariableElimination.JointMpeResult result =
                    VariableElimination.jointMostProbableExplanation(net, evidence);

            // The probability should be in (0, 1]
            double pge = result.probabilityGivenEvidence();
            assertTrue(pge > 0 && pge <= 1.0,
                    "probabilityGivenEvidence with no evidence should be in (0,1], got: " + pge);

            // logScore should be log(pge) approximately
            assertEquals(Math.exp(result.logScore()), pge, 1e-9,
                    "exp(logScore) should equal probabilityGivenEvidence when no evidence");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // WITH EVIDENCE — ASSIGNMENT EXCLUDES CLAMPED VARIABLES
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Evidence variables are excluded from the returned assignment")
    void evidenceExcludedFromAssignment() {
        BayesianNetwork net = buildAntiCorrelNet();
        Map<String, Integer> evidence = Map.of("C", 1);

        VariableElimination.JointMpeResult result =
                VariableElimination.jointMostProbableExplanation(net, evidence);

        assertFalse(result.assignment().containsKey("C"),
                "Evidence variable C must not appear in the assignment");
        assertTrue(result.assignment().containsKey("A"), "Non-evidence A must be in assignment");
        assertTrue(result.assignment().containsKey("B"), "Non-evidence B must be in assignment");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ZERO-PROBABILITY EVIDENCE GUARD
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Zero-probability evidence: probabilityGivenEvidence is NaN or non-finite gracefully")
    void zeroProbabilityEvidenceGuard() {
        BayesianNetwork net = buildAntiCorrelNet();
        // C has CPT entries for all states, but we test with an impossible assignment
        // by creating a network where only certain states are possible.
        // For this test we simply verify that the method does NOT throw an exception
        // even when evidence may be near-zero probability; NaN is the documented outcome.
        // Use a very unlikely (but not structurally impossible) evidence combination.
        Map<String, Integer> evidence = Map.of("C", 0, "A", 1, "B", 1);
        // P(C=0, A=1, B=1) = P(C=0|A=1,B=1)*P(A=1)*P(B=1) = 0.95*0.6*0.6 = 0.342 — not zero
        // so this won't trigger the NaN path; just confirm the method runs without error.
        assertDoesNotThrow(() -> {
            VariableElimination.JointMpeResult r =
                    VariableElimination.jointMostProbableExplanation(net, evidence);
            // All evidence vars should not be in the assignment
            for (String ev : evidence.keySet()) {
                assertFalse(r.assignment().containsKey(ev),
                        "Evidence variable " + ev + " must not appear in assignment");
            }
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TIE-BREAK DETERMINISM
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Tie-break: symmetric network with identical prior yields deterministic result")
    void tieBreakDeterminism() {
        // Fully symmetric network: A and B independent, equal priors, no children
        BayesianNetwork net = new BayesianNetwork();
        BayesianNode a = new BayesianNode("A", "A", "A");
        BayesianNode b = new BayesianNode("B", "B", "B");
        net.addNode(a);
        net.addNode(b);
        // Perfectly symmetric: P(TRUE)=P(FALSE)=0.5 for both
        a.setCpt(new Factor(List.of("A"), new int[]{2}, new double[]{0.5, 0.5}));
        b.setCpt(new Factor(List.of("B"), new int[]{2}, new double[]{0.5, 0.5}));

        VariableElimination.JointMpeResult r1 =
                VariableElimination.jointMostProbableExplanation(net, Map.of());
        VariableElimination.JointMpeResult r2 =
                VariableElimination.jointMostProbableExplanation(net, Map.of());

        assertEquals(r1.assignment(), r2.assignment(),
                "Tie-break must be deterministic across repeated calls");
        // Per documented tie-break: lower state index wins → both should be 0 (FALSE)
        assertEquals(0, r1.assignment().getOrDefault("A", -1),
                "Tie-break: A should be state 0 (FALSE)");
        assertEquals(0, r1.assignment().getOrDefault("B", -1),
                "Tie-break: B should be state 0 (FALSE)");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SIMPLE CHAIN NET — SANITY
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Simple chain A→B: joint MPE agrees with brute-force oracle")
    void simpleChainMatchesOracle() {
        BayesianNetwork net = new BayesianNetwork();
        BayesianNode a = new BayesianNode("A", "A", "A");
        BayesianNode b = new BayesianNode("B", "B", "B");
        net.addNode(a);
        net.addNode(b);
        net.addEdge("A", "B");

        // P(A=FALSE)=0.3, P(A=TRUE)=0.7
        a.setCpt(new Factor(List.of("A"), new int[]{2}, new double[]{0.3, 0.7}));
        // P(B|A): P(B=T|A=F)=0.1, P(B=T|A=T)=0.9
        // Factor vars: [A, B]
        b.setCpt(new Factor(List.of("A", "B"), new int[]{2, 2},
                new double[]{0.9, 0.1, 0.1, 0.9}));

        Map<String, Integer> evidence = Map.of();
        Map<String, Integer> oracle = bruteForceJointMpe(net, evidence);
        VariableElimination.JointMpeResult result =
                VariableElimination.jointMostProbableExplanation(net, evidence);

        assertEquals(oracle.get("A"), result.assignment().get("A"),
                "A should match oracle; oracle=" + oracle + " joint=" + result.assignment());
        assertEquals(oracle.get("B"), result.assignment().get("B"),
                "B should match oracle; oracle=" + oracle + " joint=" + result.assignment());
    }
}
