/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.attribution.algorithm.bayesian;

import ai.kompile.event.attribution.domain.InferenceStep;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that VariableElimination.queryWithTrace() produces correct InferenceStep records
 * covering the full elimination trace from REDUCE through MARGINALIZE to NORMALIZE.
 */
class VariableEliminationTraceTest {

    // ─────────────────────────────────────────────────────────────────────────
    // Helper: build A → B → C chain network
    // ─────────────────────────────────────────────────────────────────────────

    private BayesianNetwork buildChainNetwork() {
        BayesianNetwork network = new BayesianNetwork();

        BayesianNode a = new BayesianNode("A", "kg-a", "Node A");
        BayesianNode b = new BayesianNode("B", "kg-b", "Node B");
        BayesianNode c = new BayesianNode("C", "kg-c", "Node C");

        network.addNode(a);
        network.addNode(b);
        network.addNode(c);
        network.addEdge("A", "B");
        network.addEdge("B", "C");

        // P(A): prior 0.4 FALSE, 0.6 TRUE
        a.setCpt(NoisyOrCpt.buildPrior("A", 0.6));
        // P(B|A): noisy-OR, strength 0.8, leak 0.1
        b.setCpt(NoisyOrCpt.buildCpt("B", List.of("A"), new double[]{0.8}, 0.1));
        // P(C|B): noisy-OR, strength 0.7, leak 0.05
        c.setCpt(NoisyOrCpt.buildCpt("C", List.of("B"), new double[]{0.7}, 0.05));

        return network;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. queryWithTrace returns a non-empty trace
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void queryWithTrace_traceIsNonEmpty() {
        BayesianNetwork network = buildChainNetwork();
        VariableElimination.TracedResult result =
                VariableElimination.queryWithTrace(network, "C", Map.of("A", 1));

        assertNotNull(result);
        List<InferenceStep> trace = result.getTrace();
        assertNotNull(trace);
        assertFalse(trace.isEmpty(),
                "Inference trace must contain at least one step");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. trace contains a REDUCE step for the evidence variable A
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void queryWithTrace_traceContainsReduceStepForEvidenceVariable() {
        BayesianNetwork network = buildChainNetwork();
        VariableElimination.TracedResult result =
                VariableElimination.queryWithTrace(network, "C", Map.of("A", 1));

        List<InferenceStep> trace = result.getTrace();
        boolean hasReduceForA = trace.stream()
                .anyMatch(s -> "REDUCE".equals(s.getOperation())
                        && "A".equals(s.getEliminatedVariable()));

        assertTrue(hasReduceForA,
                "Trace must contain a REDUCE step for evidence variable A");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. trace contains MARGINALIZE step(s) for hidden variable B
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void queryWithTrace_traceContainsMarginalizeStepForHiddenVariable() {
        BayesianNetwork network = buildChainNetwork();
        // A=1 is evidence, C is query; B is the only hidden variable
        VariableElimination.TracedResult result =
                VariableElimination.queryWithTrace(network, "C", Map.of("A", 1));

        List<InferenceStep> trace = result.getTrace();
        boolean hasMarginalizeForB = trace.stream()
                .anyMatch(s -> "MARGINALIZE".equals(s.getOperation())
                        && "B".equals(s.getEliminatedVariable()));

        assertTrue(hasMarginalizeForB,
                "Trace must contain a MARGINALIZE step for hidden variable B");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. trace contains a final NORMALIZE step
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void queryWithTrace_traceContainsFinalNormalizeStep() {
        BayesianNetwork network = buildChainNetwork();
        VariableElimination.TracedResult result =
                VariableElimination.queryWithTrace(network, "C", Map.of("A", 1));

        List<InferenceStep> trace = result.getTrace();
        boolean hasNormalize = trace.stream()
                .anyMatch(s -> "NORMALIZE".equals(s.getOperation()));

        assertTrue(hasNormalize, "Trace must end with a NORMALIZE step");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. NORMALIZE step is the last step in the trace
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void queryWithTrace_normalizeStepIsLast() {
        BayesianNetwork network = buildChainNetwork();
        VariableElimination.TracedResult result =
                VariableElimination.queryWithTrace(network, "C", Map.of("A", 1));

        List<InferenceStep> trace = result.getTrace();
        InferenceStep last = trace.get(trace.size() - 1);
        assertEquals("NORMALIZE", last.getOperation(),
                "The final trace step must be NORMALIZE");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. posterior factor is properly normalized (values sum to ~1.0)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void queryWithTrace_posteriorFactorIsProperlynormalized() {
        BayesianNetwork network = buildChainNetwork();
        VariableElimination.TracedResult result =
                VariableElimination.queryWithTrace(network, "C", Map.of("A", 1));

        Factor posterior = result.getFactor();
        assertNotNull(posterior);

        double[] vals = posterior.getValues();
        double sum = 0;
        for (double v : vals) sum += v;

        assertEquals(1.0, sum, 1e-9,
                "Posterior factor values must sum to 1.0 (normalized)");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. all trace steps have non-null eliminatedVariable and operation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void queryWithTrace_allStepsHaveNonNullFields() {
        BayesianNetwork network = buildChainNetwork();
        VariableElimination.TracedResult result =
                VariableElimination.queryWithTrace(network, "C", Map.of("A", 1));

        for (InferenceStep step : result.getTrace()) {
            assertNotNull(step.getEliminatedVariable(),
                    "Every trace step must have a non-null eliminatedVariable");
            assertNotNull(step.getOperation(),
                    "Every trace step must have a non-null operation");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 8. posterior value for C is in valid probability range
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void queryWithTrace_posteriorValueIsValidProbability() {
        BayesianNetwork network = buildChainNetwork();
        VariableElimination.TracedResult result =
                VariableElimination.queryWithTrace(network, "C", Map.of("A", 1));

        Factor posterior = result.getFactor();
        double pCTrue = posterior.getValues().length > 1
                ? posterior.getValue(1) : posterior.getValue(0);

        assertTrue(pCTrue >= 0.0 && pCTrue <= 1.0,
                "P(C=TRUE | A=TRUE) must be in [0,1], was " + pCTrue);
        // A=TRUE causes B to be likely TRUE (0.82), which causes C to be likely TRUE
        assertTrue(pCTrue > 0.3, "P(C=TRUE | A=TRUE) should be meaningfully above the leak");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 9. trace steps respect REDUCE → MARGINALIZE → NORMALIZE ordering
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void queryWithTrace_stepOrderingReduceThenMarginalizesThenNormalize() {
        BayesianNetwork network = buildChainNetwork();
        VariableElimination.TracedResult result =
                VariableElimination.queryWithTrace(network, "C", Map.of("A", 1));

        List<InferenceStep> trace = result.getTrace();

        // Find first REDUCE, first MARGINALIZE, and NORMALIZE indices
        int firstReduceIdx = -1;
        int firstMargIdx = -1;
        int normalizeIdx = -1;

        for (int i = 0; i < trace.size(); i++) {
            String op = trace.get(i).getOperation();
            if ("REDUCE".equals(op) && firstReduceIdx < 0) firstReduceIdx = i;
            if ("MARGINALIZE".equals(op) && firstMargIdx < 0) firstMargIdx = i;
            if ("NORMALIZE".equals(op)) normalizeIdx = i;
        }

        assertTrue(firstReduceIdx >= 0, "Must have at least one REDUCE step");
        // NORMALIZE must come last
        assertTrue(normalizeIdx == trace.size() - 1,
                "NORMALIZE must be the final step, index=" + normalizeIdx
                        + " but trace size=" + trace.size());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 10. query() without trace returns same posterior as queryWithTrace()
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void query_andQueryWithTrace_returnSamePosterior() {
        BayesianNetwork network = buildChainNetwork();
        Map<String, Integer> evidence = Map.of("A", 1);

        Factor directPosterior = VariableElimination.query(network, "C", evidence);
        Factor tracedPosterior = VariableElimination.queryWithTrace(network, "C", evidence).getFactor();

        double[] direct = directPosterior.getValues();
        double[] traced = tracedPosterior.getValues();

        assertEquals(direct.length, traced.length,
                "Both queries must return factors of same size");
        for (int i = 0; i < direct.length; i++) {
            assertEquals(direct[i], traced[i], 1e-9,
                    "Posterior values must match between query() and queryWithTrace()");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 11. no evidence: trace has no REDUCE steps, only MARGINALIZE + NORMALIZE
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void queryWithTrace_noEvidence_noReduceSteps() {
        BayesianNetwork network = buildChainNetwork();
        // Query P(C) with no evidence
        VariableElimination.TracedResult result =
                VariableElimination.queryWithTrace(network, "C", Map.of());

        List<InferenceStep> trace = result.getTrace();
        boolean hasReduce = trace.stream().anyMatch(s -> "REDUCE".equals(s.getOperation()));
        assertFalse(hasReduce,
                "With no evidence there should be no REDUCE steps in the trace");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 12. REDUCE step posteriorValue reflects the evidence state
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void queryWithTrace_reduceStepPosteriorValueReflectsEvidenceState() {
        BayesianNetwork network = buildChainNetwork();

        // A=TRUE (state index 1)
        VariableElimination.TracedResult result =
                VariableElimination.queryWithTrace(network, "C", Map.of("A", 1));

        InferenceStep reduceStep = result.getTrace().stream()
                .filter(s -> "REDUCE".equals(s.getOperation()) && "A".equals(s.getEliminatedVariable()))
                .findFirst()
                .orElse(null);

        assertNotNull(reduceStep, "Should find a REDUCE step for A");
        // posteriorValue for evidence=1 should be 1.0
        assertEquals(1.0, reduceStep.getPosteriorValue(), 1e-9,
                "REDUCE step posteriorValue for A=TRUE should be 1.0");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 13. REDUCE step for FALSE evidence has posteriorValue 0.0
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void queryWithTrace_reduceStepPosteriorValue_falseEvidence() {
        BayesianNetwork network = buildChainNetwork();

        // A=FALSE (state index 0)
        VariableElimination.TracedResult result =
                VariableElimination.queryWithTrace(network, "C", Map.of("A", 0));

        InferenceStep reduceStep = result.getTrace().stream()
                .filter(s -> "REDUCE".equals(s.getOperation()) && "A".equals(s.getEliminatedVariable()))
                .findFirst()
                .orElse(null);

        assertNotNull(reduceStep, "Should find a REDUCE step for A=FALSE");
        assertEquals(0.0, reduceStep.getPosteriorValue(), 1e-9,
                "REDUCE step posteriorValue for A=FALSE should be 0.0");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 14. MARGINALIZE steps have positive factorsInvolved count
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void queryWithTrace_marginalizeStepsHavePositiveFactorsInvolved() {
        BayesianNetwork network = buildChainNetwork();
        VariableElimination.TracedResult result =
                VariableElimination.queryWithTrace(network, "C", Map.of("A", 1));

        result.getTrace().stream()
                .filter(s -> "MARGINALIZE".equals(s.getOperation()))
                .forEach(s -> assertTrue(s.getFactorsInvolved() >= 1,
                        "MARGINALIZE step must involve at least 1 factor, got "
                                + s.getFactorsInvolved()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 15. posterior query variable is in the result factor variables
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void queryWithTrace_posteriorFactorContainsQueryVariable() {
        BayesianNetwork network = buildChainNetwork();
        VariableElimination.TracedResult result =
                VariableElimination.queryWithTrace(network, "C", Map.of("A", 1));

        Factor posterior = result.getFactor();
        assertTrue(posterior.getVariables().contains("C"),
                "Posterior factor must contain the query variable C");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 16. single-node network (no parents, no children): trace has only NORMALIZE
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void queryWithTrace_singleNodeNetwork_traceHasOnlyNormalize() {
        BayesianNetwork network = new BayesianNetwork();
        BayesianNode x = new BayesianNode("X", "kg-x", "Node X");
        x.setCpt(NoisyOrCpt.buildPrior("X", 0.7));
        network.addNode(x);

        VariableElimination.TracedResult result =
                VariableElimination.queryWithTrace(network, "X", Map.of());

        List<InferenceStep> trace = result.getTrace();
        assertFalse(trace.isEmpty());
        // For a single node with no evidence, only NORMALIZE should appear
        boolean hasNormalize = trace.stream().anyMatch(s -> "NORMALIZE".equals(s.getOperation()));
        assertTrue(hasNormalize, "Single-node network trace must contain NORMALIZE");
        // Posterior should match the prior
        Factor posterior = result.getFactor();
        assertEquals(0.7, posterior.getValue(1), 1e-9,
                "Posterior for single-node network should match prior 0.7");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 17. unknown query variable throws IllegalArgumentException
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void queryWithTrace_unknownQueryVariable_throwsIllegalArgumentException() {
        BayesianNetwork network = buildChainNetwork();

        assertThrows(IllegalArgumentException.class,
                () -> VariableElimination.queryWithTrace(network, "NONEXISTENT", Map.of()),
                "Should throw for unknown query variable");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 18. NORMALIZE step's eliminatedTitle matches query node title
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void queryWithTrace_normalizeStepContainsQueryNodeTitle() {
        BayesianNetwork network = buildChainNetwork();
        VariableElimination.TracedResult result =
                VariableElimination.queryWithTrace(network, "C", Map.of("A", 1));

        InferenceStep normalizeStep = result.getTrace().stream()
                .filter(s -> "NORMALIZE".equals(s.getOperation()))
                .findFirst()
                .orElse(null);

        assertNotNull(normalizeStep, "Should have a NORMALIZE step");
        assertEquals("Node C", normalizeStep.getEliminatedTitle(),
                "NORMALIZE step should reference the query node's title");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 19. four-node diamond network A→B, A→C, B→D, C→D: trace covers B and C
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void queryWithTrace_diamondNetwork_traceCoversBothHiddenVariables() {
        BayesianNetwork network = new BayesianNetwork();
        BayesianNode a = new BayesianNode("A", "kg-a", "Node A");
        BayesianNode b = new BayesianNode("B", "kg-b", "Node B");
        BayesianNode c = new BayesianNode("C", "kg-c", "Node C");
        BayesianNode d = new BayesianNode("D", "kg-d", "Node D");

        network.addNode(a);
        network.addNode(b);
        network.addNode(c);
        network.addNode(d);
        network.addEdge("A", "B");
        network.addEdge("A", "C");
        network.addEdge("B", "D");
        network.addEdge("C", "D");

        a.setCpt(NoisyOrCpt.buildPrior("A", 0.5));
        b.setCpt(NoisyOrCpt.buildCpt("B", List.of("A"), new double[]{0.7}, 0.1));
        c.setCpt(NoisyOrCpt.buildCpt("C", List.of("A"), new double[]{0.6}, 0.05));
        d.setCpt(NoisyOrCpt.buildCpt("D", List.of("B", "C"), new double[]{0.8, 0.6}, 0.02));

        // Query P(D | A=1): B and C are hidden variables
        VariableElimination.TracedResult result =
                VariableElimination.queryWithTrace(network, "D", Map.of("A", 1));

        List<InferenceStep> trace = result.getTrace();
        assertFalse(trace.isEmpty());

        // Should have REDUCE for A, MARGINALIZE steps, NORMALIZE
        boolean hasReduceA = trace.stream()
                .anyMatch(s -> "REDUCE".equals(s.getOperation()) && "A".equals(s.getEliminatedVariable()));
        boolean hasMarginalize = trace.stream().anyMatch(s -> "MARGINALIZE".equals(s.getOperation()));
        boolean hasNormalize = trace.stream().anyMatch(s -> "NORMALIZE".equals(s.getOperation()));

        assertTrue(hasReduceA, "Must have REDUCE step for A");
        assertTrue(hasMarginalize, "Must have MARGINALIZE step(s) for hidden B/C");
        assertTrue(hasNormalize, "Must end with NORMALIZE");

        // Posterior for D should be valid
        Factor posterior = result.getFactor();
        double[] vals = posterior.getValues();
        double sum = 0;
        for (double v : vals) sum += v;
        assertEquals(1.0, sum, 1e-9, "Posterior must be normalized");
    }
}
