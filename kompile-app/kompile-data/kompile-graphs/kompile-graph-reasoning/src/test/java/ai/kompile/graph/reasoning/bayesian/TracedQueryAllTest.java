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

import ai.kompile.graph.reasoning.domain.InferenceStep;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link VariableElimination#queryAllWithTrace(BayesianNetwork, Map)}.
 *
 * <p>The method must:</p>
 * <ul>
 *   <li>Return a {@link VariableElimination.TracedResult} per network variable</li>
 *   <li>Match the posteriors from the single-variable {@code queryWithTrace} path</li>
 *   <li>Include a trace with at least one step per variable</li>
 *   <li>Handle evidence variables with a DETERMINISTIC step</li>
 * </ul>
 */
class TracedQueryAllTest {

    /** Simple 3-node chain: A → B → C */
    private BayesianNetwork chainNet;

    @BeforeEach
    void buildNetwork() {
        chainNet = new BayesianNetwork();

        BayesianNode a = new BayesianNode("A", "A", "Node A");
        chainNet.addNode(a);
        a.setCpt(NoisyOrCpt.buildPrior("A", 0.6));

        BayesianNode b = new BayesianNode("B", "B", "Node B");
        chainNet.addNode(b);
        chainNet.addEdge("A", "B");
        b.setCpt(NoisyOrCpt.buildCpt("B", List.of("A"), new double[]{0.8}, 0.05));

        BayesianNode c = new BayesianNode("C", "C", "Node C");
        chainNet.addNode(c);
        chainNet.addEdge("B", "C");
        c.setCpt(NoisyOrCpt.buildCpt("C", List.of("B"), new double[]{0.75}, 0.05));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // POSTERIOR PARITY WITH SINGLE-VAR QUERIES
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Posterior parity")
    class PosteriorParity {

        /**
         * With no evidence, queryAllWithTrace posteriors must match single-variable
         * queryWithTrace posteriors for every variable in the network.
         */
        @Test
        @DisplayName("No-evidence posteriors match single-var queryWithTrace (all 3 nodes)")
        void noEvidenceMatchesSingleVar() {
            Map<String, Integer> evidence = Map.of();
            Map<String, VariableElimination.TracedResult> allTraced =
                    VariableElimination.queryAllWithTrace(chainNet, evidence);

            assertEquals(3, allTraced.size(), "Should return a trace for each of A, B, C");

            for (String var : List.of("A", "B", "C")) {
                VariableElimination.TracedResult single =
                        VariableElimination.queryWithTrace(chainNet, var, evidence);
                VariableElimination.TracedResult fromAll = allTraced.get(var);

                assertNotNull(fromAll, "queryAllWithTrace must include variable: " + var);

                double[] singleVals = single.getFactor().normalize().getValues();
                double[] allVals    = fromAll.getFactor().normalize().getValues();

                assertEquals(singleVals.length, allVals.length,
                        "Factor cardinality mismatch for " + var);
                for (int i = 0; i < singleVals.length; i++) {
                    assertEquals(singleVals[i], allVals[i], 1e-9,
                            "Posterior mismatch for " + var + " at state " + i);
                }
            }
        }

        /**
         * With evidence A=TRUE, posteriors for B and C from queryAllWithTrace must match
         * the single-variable query with the same evidence.
         */
        @Test
        @DisplayName("With evidence, non-evidence variable posteriors match single-var")
        void withEvidenceMatchesSingleVar() {
            Map<String, Integer> evidence = Map.of("A", 1);
            Map<String, VariableElimination.TracedResult> allTraced =
                    VariableElimination.queryAllWithTrace(chainNet, evidence);

            for (String var : List.of("B", "C")) {
                VariableElimination.TracedResult single =
                        VariableElimination.queryWithTrace(chainNet, var, evidence);
                VariableElimination.TracedResult fromAll = allTraced.get(var);

                assertNotNull(fromAll, "queryAllWithTrace must include " + var);
                double singleP = single.getFactor().normalize().getValues()[1];
                double allP    = fromAll.getFactor().normalize().getValues()[1];
                assertEquals(singleP, allP, 1e-9,
                        "P(" + var + "=TRUE|A=TRUE) mismatch between single and all-trace");
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TRACE STRUCTURE
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Trace structure")
    class TraceStructure {

        /**
         * Every variable's TracedResult must carry a non-empty trace.
         */
        @Test
        @DisplayName("Every variable has a non-empty trace")
        void everyVariableHasTrace() {
            Map<String, VariableElimination.TracedResult> allTraced =
                    VariableElimination.queryAllWithTrace(chainNet, Map.of());

            for (Map.Entry<String, VariableElimination.TracedResult> entry : allTraced.entrySet()) {
                List<InferenceStep> trace = entry.getValue().getTrace();
                assertFalse(trace.isEmpty(),
                        "Trace for variable '" + entry.getKey() + "' must not be empty");
            }
        }

        /**
         * Evidence variables (A=TRUE in this case) must receive a DETERMINISTIC step,
         * not a full VE trace.
         */
        @Test
        @DisplayName("Evidence variable receives DETERMINISTIC step")
        void evidenceVariableGetsDeterministicStep() {
            Map<String, Integer> evidence = Map.of("A", 1);
            Map<String, VariableElimination.TracedResult> allTraced =
                    VariableElimination.queryAllWithTrace(chainNet, evidence);

            VariableElimination.TracedResult aResult = allTraced.get("A");
            assertNotNull(aResult, "Evidence variable A must still appear in results");

            List<InferenceStep> trace = aResult.getTrace();
            assertFalse(trace.isEmpty(), "Evidence variable must have at least one step");

            boolean hasDeterministic = trace.stream()
                    .anyMatch(step -> "DETERMINISTIC".equals(step.getOperation()));
            assertTrue(hasDeterministic,
                    "Evidence variable step must use DETERMINISTIC operation, trace: " + trace);
        }

        /**
         * Evidence variable point-mass factor: P(A=TRUE) = 1.0 when A is observed TRUE.
         */
        @Test
        @DisplayName("Evidence variable factor is a point mass at observed state")
        void evidenceVariableHasPointMassFactor() {
            Map<String, Integer> evidence = Map.of("A", 1);
            Map<String, VariableElimination.TracedResult> allTraced =
                    VariableElimination.queryAllWithTrace(chainNet, evidence);

            double[] aVals = allTraced.get("A").getFactor().getValues();
            assertEquals(2, aVals.length, "Binary variable should have 2 factor values");
            assertEquals(0.0, aVals[0], 1e-12, "P(A=FALSE) must be 0.0 when A=TRUE observed");
            assertEquals(1.0, aVals[1], 1e-12, "P(A=TRUE) must be 1.0 when A=TRUE observed");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // INDEPENDENT (NO-EDGE) NETWORK
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Independent root-only network: posteriors match priors")
    void independentRootsMatchPriors() {
        BayesianNetwork rootNet = new BayesianNetwork();
        BayesianNode x = new BayesianNode("X", "X", "X");
        BayesianNode y = new BayesianNode("Y", "Y", "Y");
        rootNet.addNode(x);
        rootNet.addNode(y);
        x.setCpt(NoisyOrCpt.buildPrior("X", 0.3));
        y.setCpt(NoisyOrCpt.buildPrior("Y", 0.7));

        Map<String, VariableElimination.TracedResult> allTraced =
                VariableElimination.queryAllWithTrace(rootNet, Map.of());

        assertEquals(2, allTraced.size());
        assertEquals(0.3, allTraced.get("X").getFactor().normalize().getValues()[1], 1e-9);
        assertEquals(0.7, allTraced.get("Y").getFactor().normalize().getValues()[1], 1e-9);
    }
}
