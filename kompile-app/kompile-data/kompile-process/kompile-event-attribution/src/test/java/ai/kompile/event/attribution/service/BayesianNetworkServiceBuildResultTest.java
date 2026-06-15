/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.attribution.service;

import ai.kompile.event.attribution.algorithm.bayesian.BayesianNetwork;
import ai.kompile.event.attribution.algorithm.bayesian.BayesianNode;
import ai.kompile.event.attribution.algorithm.bayesian.NoisyOrCpt;
import ai.kompile.event.attribution.algorithm.bayesian.VariableElimination;
import ai.kompile.event.attribution.domain.BayesianInferenceResult;
import ai.kompile.event.attribution.domain.InferenceStep;
import ai.kompile.event.attribution.domain.MpeResult;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.mockito.Mockito;

/**
 * Tests for BayesianNetworkService.buildResult() and related inference result construction.
 *
 * Since buildResult() is private, we exercise it through the public
 * queryAllPosteriors() path by stubbing buildNetwork() indirectly via the
 * KnowledgeGraphService mock, or by calling buildResult via reflection.
 * Where reflection is used, the test documents the exact field being verified.
 */
@ExtendWith(MockitoExtension.class)
class BayesianNetworkServiceBuildResultTest {

    @Mock
    private KnowledgeGraphService knowledgeGraphService;

    private BayesianNetworkService service;

    @BeforeEach
    void setUp() {
        service = new BayesianNetworkService(knowledgeGraphService);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper: build a 3-node A→B→C network and call buildResult via reflection
    // ─────────────────────────────────────────────────────────────────────────

    private BayesianNetwork buildThreeNodeNetwork() {
        BayesianNetwork network = new BayesianNetwork();

        BayesianNode a = new BayesianNode("A", "kg-a", "Node A");
        BayesianNode b = new BayesianNode("B", "kg-b", "Node B");
        BayesianNode c = new BayesianNode("C", "kg-c", "Node C");

        network.addNode(a);
        network.addNode(b);
        network.addNode(c);
        network.addEdge("A", "B");
        network.addEdge("B", "C");

        a.setCpt(NoisyOrCpt.buildPrior("A", 0.5));
        b.setCpt(NoisyOrCpt.buildCpt("B", List.of("A"), new double[]{0.8}, 0.1));
        c.setCpt(NoisyOrCpt.buildCpt("C", List.of("B"), new double[]{0.7}, 0.05));

        return network;
    }

    /**
     * Invoke the private buildResult method via reflection so we can test
     * its population logic in isolation without requiring the KG to return nodes.
     */
    private BayesianInferenceResult invokeBuildResult(BayesianNetwork network,
                                                        Map<String, Double> posteriors,
                                                        Map<String, Integer> bnEvidence,
                                                        long startTime) throws Exception {
        Method m = BayesianNetworkService.class.getDeclaredMethod(
                "buildResult", BayesianNetwork.class, Map.class, Map.class, long.class);
        m.setAccessible(true);
        return (BayesianInferenceResult) m.invoke(service, network, posteriors, bnEvidence, startTime);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. buildResult populates posteriors map
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void buildResult_populatesPosteriors() throws Exception {
        BayesianNetwork network = buildThreeNodeNetwork();
        Map<String, Double> posteriors = Map.of("C", 0.72);

        BayesianInferenceResult result = invokeBuildResult(
                network, posteriors, Map.of(), System.currentTimeMillis());

        assertNotNull(result.getPosteriors());
        assertFalse(result.getPosteriors().isEmpty(),
                "buildResult must populate posteriors map");
        assertEquals(0.72, result.getPosteriors().get("C"), 1e-9);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. buildResult computes priors (no-evidence baseline)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void buildResult_computesPriors() throws Exception {
        BayesianNetwork network = buildThreeNodeNetwork();
        Map<String, Double> posteriors = Map.of("A", 1.0, "B", 0.8, "C", 0.6);

        BayesianInferenceResult result = invokeBuildResult(
                network, posteriors, Map.of("A", 1), System.currentTimeMillis());

        assertNotNull(result.getPriors());
        assertFalse(result.getPriors().isEmpty(),
                "buildResult must compute priors (baseline, no evidence)");
        // Priors should include all network variables
        assertTrue(result.getPriors().containsKey("A"),
                "Priors must include A");
        assertTrue(result.getPriors().containsKey("B"),
                "Priors must include B");
        assertTrue(result.getPriors().containsKey("C"),
                "Priors must include C");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. priors should be different from posteriors when evidence is provided
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void buildResult_priorsAndPosteriorsCanDiffer() throws Exception {
        BayesianNetwork network = buildThreeNodeNetwork();
        // Run VE to compute actual posteriors for A=1
        Map<String, Double> actualPosteriors = VariableElimination.queryAll(network, Map.of("A", 1));

        BayesianInferenceResult result = invokeBuildResult(
                network, actualPosteriors, Map.of("A", 1), System.currentTimeMillis());

        double priorA = result.getPriors().getOrDefault("A", -1.0);
        double posteriorA = result.getPosteriors().getOrDefault("A", -1.0);

        // Prior for A = 0.5, posterior given A=TRUE = 1.0
        assertNotEquals(priorA, posteriorA, 1e-6,
                "Priors and posteriors should differ when evidence is set");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. buildResult populates variableToNodeId map
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void buildResult_populatesVariableToNodeIdMap() throws Exception {
        BayesianNetwork network = buildThreeNodeNetwork();
        Map<String, Double> posteriors = Map.of("C", 0.6);

        BayesianInferenceResult result = invokeBuildResult(
                network, posteriors, Map.of(), System.currentTimeMillis());

        assertNotNull(result.getVariableToNodeId());
        assertFalse(result.getVariableToNodeId().isEmpty(),
                "variableToNodeId must be populated");
        // A maps to kg-a
        assertEquals("kg-a", result.getVariableToNodeId().get("A"));
        assertEquals("kg-b", result.getVariableToNodeId().get("B"));
        assertEquals("kg-c", result.getVariableToNodeId().get("C"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. buildResult populates variableToTitle map
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void buildResult_populatesVariableToTitleMap() throws Exception {
        BayesianNetwork network = buildThreeNodeNetwork();
        Map<String, Double> posteriors = Map.of("C", 0.6);

        BayesianInferenceResult result = invokeBuildResult(
                network, posteriors, Map.of(), System.currentTimeMillis());

        assertNotNull(result.getVariableToTitle());
        assertFalse(result.getVariableToTitle().isEmpty(),
                "variableToTitle must be populated");
        assertEquals("Node A", result.getVariableToTitle().get("A"));
        assertEquals("Node B", result.getVariableToTitle().get("B"));
        assertEquals("Node C", result.getVariableToTitle().get("C"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. buildResult populates inferenceTrace
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void buildResult_populatesInferenceTrace() throws Exception {
        BayesianNetwork network = buildThreeNodeNetwork();
        Map<String, Double> posteriors = Map.of("A", 1.0, "B", 0.8);

        BayesianInferenceResult result = invokeBuildResult(
                network, posteriors, Map.of("A", 1), System.currentTimeMillis());

        assertNotNull(result.getInferenceTrace());
        assertFalse(result.getInferenceTrace().isEmpty(),
                "inferenceTrace must be non-empty");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. inferenceTrace contains InferenceStep objects with valid operations
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void buildResult_inferenceTraceContainsValidOperations() throws Exception {
        BayesianNetwork network = buildThreeNodeNetwork();
        Map<String, Double> posteriors = Map.of("C", 0.7);

        BayesianInferenceResult result = invokeBuildResult(
                network, posteriors, Map.of("A", 1), System.currentTimeMillis());

        Set<String> validOps = Set.of("REDUCE", "MARGINALIZE", "NORMALIZE", "MULTIPLY");
        for (InferenceStep step : result.getInferenceTrace()) {
            assertNotNull(step.getOperation());
            assertTrue(validOps.contains(step.getOperation()),
                    "Operation '" + step.getOperation() + "' is not a recognized VE operation");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 8. computedAt is set and recent
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void buildResult_computedAtIsSet() throws Exception {
        BayesianNetwork network = buildThreeNodeNetwork();
        Map<String, Double> posteriors = Map.of("C", 0.5);
        long before = System.currentTimeMillis();

        BayesianInferenceResult result = invokeBuildResult(
                network, posteriors, Map.of(), before);

        assertNotNull(result.getComputedAt());
        // computedAt must be no earlier than before
        assertTrue(result.getComputedAt().toEpochMilli() >= before,
                "computedAt must be after the start time");
        // and no later than now + 5 seconds (generous buffer)
        assertTrue(result.getComputedAt().isBefore(Instant.now().plusSeconds(5)));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 9. computationTimeMs is non-negative
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void buildResult_computationTimeMsIsNonNegative() throws Exception {
        BayesianNetwork network = buildThreeNodeNetwork();
        Map<String, Double> posteriors = Map.of("C", 0.5);
        long startTime = System.currentTimeMillis();

        BayesianInferenceResult result = invokeBuildResult(
                network, posteriors, Map.of(), startTime);

        assertTrue(result.getComputationTimeMs() >= 0,
                "computationTimeMs must be >= 0");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 10. networkStats includes nodeCount
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void buildResult_networkStatsIncludesNodeCount() throws Exception {
        BayesianNetwork network = buildThreeNodeNetwork();
        Map<String, Double> posteriors = Map.of("C", 0.5);

        BayesianInferenceResult result = invokeBuildResult(
                network, posteriors, Map.of(), System.currentTimeMillis());

        assertNotNull(result.getNetworkStats());
        assertTrue(result.getNetworkStats().containsKey("nodeCount"),
                "networkStats must include nodeCount");
        assertEquals(3, result.getNetworkStats().get("nodeCount"),
                "nodeCount should be 3 for the 3-node network");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 11. evidence is stored in the result
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void buildResult_evidenceIsStoredInResult() throws Exception {
        BayesianNetwork network = buildThreeNodeNetwork();
        Map<String, Double> posteriors = Map.of("C", 0.7);
        Map<String, Integer> evidence = Map.of("A", 1);

        BayesianInferenceResult result = invokeBuildResult(
                network, posteriors, evidence, System.currentTimeMillis());

        assertNotNull(result.getEvidence());
        assertEquals(1, result.getEvidence().get("A"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 12. all prior values are valid probabilities in [0, 1]
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void buildResult_allPriorValuesAreValidProbabilities() throws Exception {
        BayesianNetwork network = buildThreeNodeNetwork();
        Map<String, Double> posteriors = VariableElimination.queryAll(network, Map.of("A", 1));

        BayesianInferenceResult result = invokeBuildResult(
                network, posteriors, Map.of("A", 1), System.currentTimeMillis());

        for (Map.Entry<String, Double> entry : result.getPriors().entrySet()) {
            double p = entry.getValue();
            assertTrue(p >= 0.0 && p <= 1.0,
                    "Prior for " + entry.getKey() + " = " + p + " is out of [0,1] range");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 13. four-node network: buildResult maps all four variables
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void buildResult_fourNodeNetwork_allVariablesMapped() throws Exception {
        BayesianNetwork network = new BayesianNetwork();
        BayesianNode a = new BayesianNode("A", "id-a", "Title A");
        BayesianNode b = new BayesianNode("B", "id-b", "Title B");
        BayesianNode c = new BayesianNode("C", "id-c", "Title C");
        BayesianNode d = new BayesianNode("D", "id-d", "Title D");

        network.addNode(a); network.addNode(b); network.addNode(c); network.addNode(d);
        network.addEdge("A", "B"); network.addEdge("A", "C"); network.addEdge("B", "D"); network.addEdge("C", "D");

        a.setCpt(NoisyOrCpt.buildPrior("A", 0.4));
        b.setCpt(NoisyOrCpt.buildCpt("B", List.of("A"), new double[]{0.7}, 0.1));
        c.setCpt(NoisyOrCpt.buildCpt("C", List.of("A"), new double[]{0.5}, 0.05));
        d.setCpt(NoisyOrCpt.buildCpt("D", List.of("B", "C"), new double[]{0.8, 0.6}, 0.02));

        Map<String, Double> posteriors = VariableElimination.queryAll(network, Map.of());

        BayesianInferenceResult result = invokeBuildResult(
                network, posteriors, Map.of(), System.currentTimeMillis());

        assertEquals(4, result.getVariableToNodeId().size());
        assertEquals(4, result.getVariableToTitle().size());
        assertEquals("id-a", result.getVariableToNodeId().get("A"));
        assertEquals("Title D", result.getVariableToTitle().get("D"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 14. inferenceTrace's last step is NORMALIZE
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void buildResult_inferenceTraceEndsWithNormalize() throws Exception {
        BayesianNetwork network = buildThreeNodeNetwork();
        Map<String, Double> posteriors = Map.of("C", 0.6);

        BayesianInferenceResult result = invokeBuildResult(
                network, posteriors, Map.of("A", 1), System.currentTimeMillis());

        List<InferenceStep> trace = result.getInferenceTrace();
        assertFalse(trace.isEmpty());
        assertEquals("NORMALIZE", trace.get(trace.size() - 1).getOperation(),
                "Last inference step must be NORMALIZE");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 15. queryAllPosteriors delegates to buildResult and returns valid result
    //     (integration check — uses mock KG that returns empty, so network is empty)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void queryAllPosteriors_withEmptyKg_returnsEmptyResult() {
        // When KG returns no nodes for the seed IDs, the network is empty → VE cannot run
        // but queryAllPosteriors should still return a non-null result.
        // Mockito's default behaviour for Optional-returning methods is Optional.empty(),
        // so no explicit when() stubs are needed here.

        BayesianInferenceResult result = service.queryAllPosteriors(
                List.of("nonexistent-node"), Map.of(), 2, 10);

        // Should not throw; result must be non-null
        assertNotNull(result);
        // With empty network, posteriors will be empty
        assertNotNull(result.getPosteriors());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MPE helper: create a spy service that returns our pre-built 3-node network
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build a spy on the service whose buildNetwork() returns the given network
     * instead of delegating to the KG. This lets us test mostProbableExplanation()
     * with a real populated network without requiring real KG data.
     */
    private BayesianNetworkService buildSpyWithNetwork(BayesianNetwork network) {
        BayesianNetworkService spy = Mockito.spy(service);
        doReturn(network).when(spy).buildNetwork(anyCollection(), anyInt(), anyInt());
        return spy;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 16. mostProbableExplanation returns non-null MpeResult
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void mostProbableExplanation_returnsNonNullResult() {
        BayesianNetwork network = buildThreeNodeNetwork();
        BayesianNetworkService spy = buildSpyWithNetwork(network);

        MpeResult result = spy.mostProbableExplanation(
                List.of("kg-a"), Map.of(), 2, 10);

        assertNotNull(result, "mostProbableExplanation must return a non-null MpeResult");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 17. MPE assignments map is non-empty
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void mostProbableExplanation_assignmentsMapIsNonEmpty() {
        BayesianNetwork network = buildThreeNodeNetwork();
        BayesianNetworkService spy = buildSpyWithNetwork(network);

        MpeResult result = spy.mostProbableExplanation(
                List.of("kg-a"), Map.of(), 2, 10);

        assertNotNull(result.getAssignments());
        assertFalse(result.getAssignments().isEmpty(),
                "assignments map must be non-empty for a 3-node network");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 18. MPE assignments values are valid binary states (0 or 1)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void mostProbableExplanation_assignmentValuesAreValidBinaryStates() {
        BayesianNetwork network = buildThreeNodeNetwork();
        BayesianNetworkService spy = buildSpyWithNetwork(network);

        MpeResult result = spy.mostProbableExplanation(
                List.of("kg-a"), Map.of(), 2, 10);

        for (Map.Entry<String, Integer> entry : result.getAssignments().entrySet()) {
            assertTrue(entry.getValue() == 0 || entry.getValue() == 1,
                    "Assignment for " + entry.getKey() + " must be 0 or 1, got: " + entry.getValue());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 19. MPE posteriors map is non-empty
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void mostProbableExplanation_posteriorsMapIsNonEmpty() {
        BayesianNetwork network = buildThreeNodeNetwork();
        BayesianNetworkService spy = buildSpyWithNetwork(network);

        MpeResult result = spy.mostProbableExplanation(
                List.of("kg-a"), Map.of(), 2, 10);

        assertNotNull(result.getPosteriors());
        assertFalse(result.getPosteriors().isEmpty(),
                "posteriors map must be non-empty");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 20. MPE priors map is non-empty
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void mostProbableExplanation_priorsMapIsNonEmpty() {
        BayesianNetwork network = buildThreeNodeNetwork();
        BayesianNetworkService spy = buildSpyWithNetwork(network);

        MpeResult result = spy.mostProbableExplanation(
                List.of("kg-a"), Map.of(), 2, 10);

        assertNotNull(result.getPriors());
        assertFalse(result.getPriors().isEmpty(),
                "priors map must be non-empty");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 21. MPE inferenceTrace is non-empty
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void mostProbableExplanation_inferenceTraceIsNonEmpty() {
        BayesianNetwork network = buildThreeNodeNetwork();
        BayesianNetworkService spy = buildSpyWithNetwork(network);

        MpeResult result = spy.mostProbableExplanation(
                List.of("kg-a"), Map.of(), 2, 10);

        assertNotNull(result.getInferenceTrace());
        assertFalse(result.getInferenceTrace().isEmpty(),
                "inferenceTrace must be non-empty for a 3-node network");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 22. MPE variableToNodeId is populated with correct mappings
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void mostProbableExplanation_variableToNodeIdIsPopulated() {
        BayesianNetwork network = buildThreeNodeNetwork();
        BayesianNetworkService spy = buildSpyWithNetwork(network);

        MpeResult result = spy.mostProbableExplanation(
                List.of("kg-a"), Map.of(), 2, 10);

        assertNotNull(result.getVariableToNodeId());
        assertFalse(result.getVariableToNodeId().isEmpty(),
                "variableToNodeId must be populated");
        assertEquals("kg-a", result.getVariableToNodeId().get("A"));
        assertEquals("kg-b", result.getVariableToNodeId().get("B"));
        assertEquals("kg-c", result.getVariableToNodeId().get("C"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 23. MPE variableToTitle is populated with correct titles
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void mostProbableExplanation_variableToTitleIsPopulated() {
        BayesianNetwork network = buildThreeNodeNetwork();
        BayesianNetworkService spy = buildSpyWithNetwork(network);

        MpeResult result = spy.mostProbableExplanation(
                List.of("kg-a"), Map.of(), 2, 10);

        assertNotNull(result.getVariableToTitle());
        assertFalse(result.getVariableToTitle().isEmpty(),
                "variableToTitle must be populated");
        assertEquals("Node A", result.getVariableToTitle().get("A"));
        assertEquals("Node B", result.getVariableToTitle().get("B"));
        assertEquals("Node C", result.getVariableToTitle().get("C"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 24. MPE computedAt is set
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void mostProbableExplanation_computedAtIsSet() {
        BayesianNetwork network = buildThreeNodeNetwork();
        BayesianNetworkService spy = buildSpyWithNetwork(network);
        long before = System.currentTimeMillis();

        MpeResult result = spy.mostProbableExplanation(
                List.of("kg-a"), Map.of(), 2, 10);

        assertNotNull(result.getComputedAt(),
                "computedAt must not be null");
        assertTrue(result.getComputedAt().toEpochMilli() >= before,
                "computedAt must be >= start time");
        assertTrue(result.getComputedAt().isBefore(Instant.now().plusSeconds(5)),
                "computedAt must be in the near past/present");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 25. MPE computationTimeMs is non-negative
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void mostProbableExplanation_computationTimeMsIsNonNegative() {
        BayesianNetwork network = buildThreeNodeNetwork();
        BayesianNetworkService spy = buildSpyWithNetwork(network);

        MpeResult result = spy.mostProbableExplanation(
                List.of("kg-a"), Map.of(), 2, 10);

        assertTrue(result.getComputationTimeMs() >= 0,
                "computationTimeMs must be >= 0");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 26. MPE with evidence: observed node maps to KG node ID in assignments
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void mostProbableExplanation_withEvidence_evidenceNodeAppearsInAssignments() {
        BayesianNetwork network = buildThreeNodeNetwork();
        BayesianNetworkService spy = buildSpyWithNetwork(network);

        // Evidence: kg-a is observed TRUE; the service translates kg-a → "A"
        // and puts A=1 in bnEvidence, then assignments contains kg-a=1
        MpeResult result = spy.mostProbableExplanation(
                List.of("kg-a"), Map.of("kg-a", 1), 2, 10);

        assertNotNull(result.getAssignments());
        // The evidence variable is included in the MPE assignments
        assertTrue(result.getAssignments().containsKey("kg-a"),
                "evidence variable kg-a must appear in assignments map");
        assertEquals(1, result.getAssignments().get("kg-a"),
                "observed evidence kg-a=1 must appear in assignments");
    }
}
