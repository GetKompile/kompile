/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.attribution.controller;

import ai.kompile.event.attribution.algorithm.bayesian.BayesianNetwork;
import ai.kompile.event.attribution.algorithm.bayesian.BayesianNode;
import ai.kompile.event.attribution.algorithm.bayesian.NoisyOrCpt;
import ai.kompile.event.attribution.domain.BayesianInferenceResult;
import ai.kompile.event.attribution.domain.InferenceStep;
import ai.kompile.event.attribution.domain.MpeResult;
import ai.kompile.event.attribution.domain.SensitivityResult;
import ai.kompile.event.attribution.service.BayesianNetworkService;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link BayesianNetworkController}.
 *
 * <p>The controller is constructed directly (no Spring context) and
 * the {@link BayesianNetworkService} is replaced with a Mockito mock.
 * Where tests need to exercise the controller's own logic (e.g. the
 * byType filter), a real {@link BayesianNetworkService} backed by a
 * spy network is used instead.</p>
 *
 * <p>The inner record types (WhatIfRequest, SensitivityRequest, etc.)
 * are package-private so they are accessed reflectively from the test
 * package via the controller's declared constructors.</p>
 */
@ExtendWith(MockitoExtension.class)
class BayesianNetworkControllerTest {

    @Mock
    private BayesianNetworkService bayesianService;

    @Mock
    private KnowledgeGraphService knowledgeGraphService;

    private BayesianNetworkController controller;

    // ─── shared result builders ──────────────────────────────────────────────

    /** Builds a minimal BayesianInferenceResult with supplied posteriors and priors. */
    private BayesianInferenceResult makeResult(Map<String, Double> posteriors,
                                               Map<String, Double> priors,
                                               Map<String, String> varToNodeId,
                                               Map<String, String> varToTitle) {
        List<InferenceStep> trace = List.of(
                InferenceStep.builder()
                        .eliminatedVariable("A").eliminatedTitle("Node A")
                        .factorsInvolved(1).factorVariables(List.of("A"))
                        .operation("REDUCE").priorValue(0.5).posteriorValue(0.8)
                        .contributionWeight(0.3).build(),
                InferenceStep.builder()
                        .eliminatedVariable("RESULT").eliminatedTitle("Result")
                        .factorsInvolved(1).factorVariables(List.of())
                        .operation("NORMALIZE").priorValue(0.5).posteriorValue(0.8)
                        .contributionWeight(1.0).build()
        );

        return BayesianInferenceResult.builder()
                .posteriors(new LinkedHashMap<>(posteriors))
                .priors(new LinkedHashMap<>(priors))
                .variableToNodeId(new LinkedHashMap<>(varToNodeId))
                .variableToTitle(new LinkedHashMap<>(varToTitle))
                .evidence(new LinkedHashMap<>())
                .inferenceTrace(new ArrayList<>(trace))
                .networkStats(new LinkedHashMap<>(Map.of("nodeCount", 3)))
                .computedAt(Instant.now())
                .computationTimeMs(5L)
                .build();
    }

    /** Builds a minimal MpeResult with the given assignments, posteriors, and priors. */
    private MpeResult makeMpeResult(Map<String, Integer> assignments,
                                    Map<String, Double> posteriors,
                                    Map<String, Double> priors,
                                    Map<String, String> varToNodeId,
                                    Map<String, String> varToTitle) {
        List<InferenceStep> trace = List.of(
                InferenceStep.builder()
                        .eliminatedVariable("B").eliminatedTitle("Node B")
                        .factorsInvolved(2).factorVariables(List.of("A", "B"))
                        .operation("MARGINALIZE").priorValue(0.4).posteriorValue(0.75)
                        .contributionWeight(0.35).build(),
                InferenceStep.builder()
                        .eliminatedVariable("RESULT").eliminatedTitle("Result")
                        .factorsInvolved(1).factorVariables(List.of())
                        .operation("NORMALIZE").priorValue(0.5).posteriorValue(0.75)
                        .contributionWeight(1.0).build()
        );

        return MpeResult.builder()
                .assignments(new LinkedHashMap<>(assignments))
                .posteriors(new LinkedHashMap<>(posteriors))
                .priors(new LinkedHashMap<>(priors))
                .evidence(new LinkedHashMap<>())
                .inferenceTrace(new ArrayList<>(trace))
                .variableToNodeId(new LinkedHashMap<>(varToNodeId))
                .variableToTitle(new LinkedHashMap<>(varToTitle))
                .networkStats(new LinkedHashMap<>(Map.of("nodeCount", 3)))
                .computedAt(Instant.now())
                .computationTimeMs(8L)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inner record instantiation helpers
    //
    // The controller's request DTOs are package-private records.
    // We build them here using reflection so this test class (in the same
    // package) can construct them without coupling to a JSON/serialisation
    // stack.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Constructs a WhatIfRequest record (package-private inner record of the controller).
     */
    @SuppressWarnings("unchecked")
    private Object makeWhatIfRequest(List<String> seedNodeIds,
                                     Map<String, Integer> hypotheticalEvidence,
                                     Integer maxDepth,
                                     Integer maxNodes) throws Exception {
        Class<?> clazz = Class.forName(
                "ai.kompile.event.attribution.controller.BayesianNetworkController$WhatIfRequest");
        Constructor<?> ctor = clazz.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        return ctor.newInstance(seedNodeIds, hypotheticalEvidence, maxDepth, maxNodes);
    }

    /**
     * Constructs a SensitivityRequest record.
     */
    private Object makeSensitivityRequest(List<String> seedNodeIds,
                                          String queryNodeId,
                                          Map<String, Integer> evidence,
                                          Double epsilon,
                                          Integer maxDepth,
                                          Integer maxNodes) throws Exception {
        Class<?> clazz = Class.forName(
                "ai.kompile.event.attribution.controller.BayesianNetworkController$SensitivityRequest");
        Constructor<?> ctor = clazz.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        return ctor.newInstance(seedNodeIds, queryNodeId, evidence, epsilon, maxDepth, maxNodes);
    }

    /**
     * Constructs a BayesianQueryRequest record (used for MPE endpoint).
     */
    private Object makeBayesianQueryRequest(List<String> seedNodeIds,
                                             String queryNodeId,
                                             Map<String, Integer> evidence,
                                             Integer maxDepth,
                                             Integer maxNodes) throws Exception {
        Class<?> clazz = Class.forName(
                "ai.kompile.event.attribution.controller.BayesianNetworkController$BayesianQueryRequest");
        Constructor<?> ctor = clazz.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        return ctor.newInstance(seedNodeIds, queryNodeId, evidence, maxDepth, maxNodes);
    }

    /**
     * Constructs a MebnTypeQueryRequest record (used for byType endpoint).
     */
    private Object makeMebnTypeQueryRequest(List<String> seedNodeIds,
                                             String entityType,
                                             Map<String, Integer> evidence,
                                             Integer maxDepth,
                                             Integer maxNodes) throws Exception {
        Class<?> clazz = Class.forName(
                "ai.kompile.event.attribution.controller.BayesianNetworkController$MebnTypeQueryRequest");
        Constructor<?> ctor = clazz.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        return ctor.newInstance(seedNodeIds, entityType, evidence, maxDepth, maxNodes);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper: call controller endpoints reflectively
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private ResponseEntity<BayesianInferenceResult> callWhatIf(Object request) throws Exception {
        return (ResponseEntity<BayesianInferenceResult>)
                BayesianNetworkController.class
                        .getDeclaredMethod("whatIfQuery",
                                Class.forName("ai.kompile.event.attribution.controller.BayesianNetworkController$WhatIfRequest"))
                        .invoke(controller, request);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<SensitivityResult> callSensitivity(Object request) throws Exception {
        return (ResponseEntity<SensitivityResult>)
                BayesianNetworkController.class
                        .getDeclaredMethod("sensitivityAnalysis",
                                Class.forName("ai.kompile.event.attribution.controller.BayesianNetworkController$SensitivityRequest"))
                        .invoke(controller, request);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<SensitivityResult> callQuickSensitivity(String nodeId,
                                                                      int maxDepth,
                                                                      int maxNodes) throws Exception {
        return (ResponseEntity<SensitivityResult>)
                BayesianNetworkController.class
                        .getDeclaredMethod("quickSensitivity", String.class, int.class, int.class)
                        .invoke(controller, nodeId, maxDepth, maxNodes);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<MpeResult> callMpe(Object request) throws Exception {
        return (ResponseEntity<MpeResult>)
                BayesianNetworkController.class
                        .getDeclaredMethod("mostProbableExplanation",
                                Class.forName("ai.kompile.event.attribution.controller.BayesianNetworkController$BayesianQueryRequest"))
                        .invoke(controller, request);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<BayesianInferenceResult> callByType(Object request) throws Exception {
        return (ResponseEntity<BayesianInferenceResult>)
                BayesianNetworkController.class
                        .getDeclaredMethod("queryMebnByType",
                                Class.forName("ai.kompile.event.attribution.controller.BayesianNetworkController$MebnTypeQueryRequest"))
                        .invoke(controller, request);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Setup
    // ─────────────────────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        controller = new BayesianNetworkController(bayesianService);
    }

    // =========================================================================
    // POST /api/attribution/bayesian/whatif — what-if query
    // =========================================================================

    /**
     * Basic what-if call: service is called with the hypothetical evidence
     * map and the controller returns 200 OK with the result body.
     */
    @Test
    void whatIf_returnsOkWithResult() throws Exception {
        Map<String, Integer> hypothetical = Map.of("kg-a", 1, "kg-b", 0);
        BayesianInferenceResult expected = makeResult(
                Map.of("C", 0.72), Map.of("C", 0.5),
                Map.of("C", "kg-c"), Map.of("C", "Node C"));

        when(bayesianService.whatIfQuery(anyCollection(), anyMap(), anyInt(), anyInt()))
                .thenReturn(expected);

        Object req = makeWhatIfRequest(List.of("kg-a"), hypothetical, 3, 100);
        ResponseEntity<BayesianInferenceResult> response = callWhatIf(req);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
    }

    /**
     * The hypothetical evidence map passed to the controller is forwarded
     * unchanged to the service (pass-through verification).
     */
    @Test
    void whatIf_passesHypotheticalEvidenceToService() throws Exception {
        Map<String, Integer> hypothetical = Map.of("kg-risk", 1, "kg-safe", 0);
        BayesianInferenceResult stub = makeResult(
                Map.of("X", 0.9), Map.of("X", 0.4),
                Map.of("X", "kg-x"), Map.of("X", "Node X"));

        when(bayesianService.whatIfQuery(anyCollection(), anyMap(), anyInt(), anyInt()))
                .thenReturn(stub);

        Object req = makeWhatIfRequest(List.of("kg-root"), hypothetical, 4, 50);
        callWhatIf(req);

        verify(bayesianService).whatIfQuery(
                eq(List.of("kg-root")),
                eq(hypothetical),
                eq(4),
                eq(50));
    }

    /**
     * Null hypotheticalEvidence in the request should default to an empty map
     * (controller must not NPE and must pass Map.of() to the service).
     */
    @Test
    void whatIf_nullHypotheticalEvidence_defaultsToEmptyMap() throws Exception {
        BayesianInferenceResult stub = makeResult(
                Map.of("A", 0.5), Map.of("A", 0.5),
                Map.of("A", "kg-a"), Map.of("A", "Node A"));

        when(bayesianService.whatIfQuery(anyCollection(), anyMap(), anyInt(), anyInt()))
                .thenReturn(stub);

        Object req = makeWhatIfRequest(List.of("kg-a"), null, null, null);
        ResponseEntity<BayesianInferenceResult> response = callWhatIf(req);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());

        // Service must be called with empty evidence (not null)
        verify(bayesianService).whatIfQuery(
                anyCollection(),
                eq(Map.of()),
                eq(3),    // default maxDepth
                eq(100)); // default maxNodes
    }

    /**
     * The response body posteriors should be exactly what the service returned.
     */
    @Test
    void whatIf_responseBodyContainsPosteriors() throws Exception {
        Map<String, Double> posteriors = Map.of("A", 0.85, "B", 0.62, "C", 0.31);
        BayesianInferenceResult stub = makeResult(
                posteriors, Map.of("A", 0.5, "B", 0.4, "C", 0.3),
                Map.of("A", "kg-a", "B", "kg-b", "C", "kg-c"),
                Map.of("A", "Node A", "B", "Node B", "C", "Node C"));

        when(bayesianService.whatIfQuery(anyCollection(), anyMap(), anyInt(), anyInt()))
                .thenReturn(stub);

        Object req = makeWhatIfRequest(List.of("kg-a"), Map.of("kg-a", 1), 3, 100);
        ResponseEntity<BayesianInferenceResult> response = callWhatIf(req);

        assertNotNull(response.getBody());
        assertEquals(0.85, response.getBody().getPosteriors().get("A"), 1e-9);
        assertEquals(0.62, response.getBody().getPosteriors().get("B"), 1e-9);
        assertEquals(0.31, response.getBody().getPosteriors().get("C"), 1e-9);
    }

    /**
     * What-if response must include priors from the service result.
     */
    @Test
    void whatIf_responseBodyContainsPriors() throws Exception {
        Map<String, Double> priors = Map.of("A", 0.5, "B", 0.4);
        BayesianInferenceResult stub = makeResult(
                Map.of("A", 0.9), priors,
                Map.of("A", "kg-a"), Map.of("A", "Node A"));

        when(bayesianService.whatIfQuery(anyCollection(), anyMap(), anyInt(), anyInt()))
                .thenReturn(stub);

        Object req = makeWhatIfRequest(List.of("kg-a"), Map.of("kg-a", 1), 3, 100);
        ResponseEntity<BayesianInferenceResult> response = callWhatIf(req);

        assertNotNull(response.getBody());
        assertNotNull(response.getBody().getPriors(), "priors must not be null");
        assertEquals(0.5, response.getBody().getPriors().get("A"), 1e-9);
        assertEquals(0.4, response.getBody().getPriors().get("B"), 1e-9);
    }

    // =========================================================================
    // POST /api/attribution/bayesian/sensitivity — sensitivity analysis
    // =========================================================================

    /**
     * Basic sensitivity call returns 200 with the sensitivity map.
     */
    @Test
    void sensitivity_returnsOkWithSensitivityResult() throws Exception {
        SensitivityResult sensResult = SensitivityResult.builder()
                .sensitivities(Map.of("kg-a", 0.15, "kg-b", 0.08))
                .priors(Map.of("kg-a", 0.5, "kg-b", 0.3, "kg-c", 0.4))
                .baselinePosterior(0.42)
                .queryPrior(0.4)
                .queryNodeId("kg-c")
                .computationTimeMs(10)
                .build();

        when(bayesianService.sensitivityAnalysis(anyCollection(), anyString(),
                anyMap(), anyDouble(), anyInt(), anyInt()))
                .thenReturn(sensResult);

        Object req = makeSensitivityRequest(
                List.of("kg-c"), "kg-c", Map.of(), 0.01, 3, 100);
        ResponseEntity<SensitivityResult> response = callSensitivity(req);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(0.15, response.getBody().getSensitivities().get("kg-a"), 1e-9);
        assertEquals(0.08, response.getBody().getSensitivities().get("kg-b"), 1e-9);
        assertNotNull(response.getBody().getPriors());
        assertEquals(0.5, response.getBody().getPriors().get("kg-a"), 1e-9);
        assertEquals(0.42, response.getBody().getBaselinePosterior(), 1e-9);
        assertEquals(0.4, response.getBody().getQueryPrior(), 1e-9);
        assertEquals("kg-c", response.getBody().getQueryNodeId());
    }

    /**
     * The controller passes the epsilon value from the request to the service.
     * When not provided, the default is 0.01.
     */
    @Test
    void sensitivity_passesEpsilonToService() throws Exception {
        when(bayesianService.sensitivityAnalysis(anyCollection(), anyString(),
                anyMap(), anyDouble(), anyInt(), anyInt()))
                .thenReturn(SensitivityResult.builder().queryNodeId("kg-x").build());

        Object req = makeSensitivityRequest(
                List.of("kg-x"), "kg-x", Map.of(), 0.05, 2, 50);
        callSensitivity(req);

        verify(bayesianService).sensitivityAnalysis(
                eq(List.of("kg-x")),
                eq("kg-x"),
                eq(Map.of()),
                eq(0.05),
                eq(2),
                eq(50));
    }

    /**
     * Null epsilon in the request should default to 0.01.
     */
    @Test
    void sensitivity_nullEpsilon_defaultsToPointZeroOne() throws Exception {
        when(bayesianService.sensitivityAnalysis(anyCollection(), anyString(),
                anyMap(), anyDouble(), anyInt(), anyInt()))
                .thenReturn(SensitivityResult.builder().queryNodeId("kg-x").build());

        Object req = makeSensitivityRequest(
                List.of("kg-x"), "kg-x", Map.of(), null, null, null);
        callSensitivity(req);

        verify(bayesianService).sensitivityAnalysis(
                anyCollection(),
                anyString(),
                eq(Map.of()),
                eq(0.01),   // default epsilon
                eq(3),      // default maxDepth
                eq(100));   // default maxNodes
    }

    /**
     * Null evidence in the request should default to empty map.
     */
    @Test
    void sensitivity_nullEvidence_defaultsToEmptyMap() throws Exception {
        when(bayesianService.sensitivityAnalysis(anyCollection(), anyString(),
                anyMap(), anyDouble(), anyInt(), anyInt()))
                .thenReturn(SensitivityResult.builder().queryNodeId("kg-y").build());

        Object req = makeSensitivityRequest(
                List.of("kg-y"), "kg-y", null, 0.01, 3, 100);
        callSensitivity(req);

        verify(bayesianService).sensitivityAnalysis(
                anyCollection(),
                anyString(),
                eq(Map.of()),
                anyDouble(),
                anyInt(),
                anyInt());
    }

    // =========================================================================
    // GET /api/attribution/bayesian/sensitivity/{nodeId} — quick sensitivity
    // =========================================================================

    /**
     * Quick sensitivity invokes sensitivityAnalysis with the nodeId as both
     * seed and queryNodeId, with empty evidence and default epsilon 0.01.
     */
    @Test
    void quickSensitivity_returnsOkWithSensitivityResult() throws Exception {
        SensitivityResult sensResult = SensitivityResult.builder()
                .sensitivities(Map.of("kg-parent", 0.22))
                .priors(Map.of("kg-parent", 0.5, "kg-target", 0.4))
                .baselinePosterior(0.4)
                .queryPrior(0.4)
                .queryNodeId("kg-target")
                .computationTimeMs(5)
                .build();

        when(bayesianService.sensitivityAnalysis(anyCollection(), anyString(),
                anyMap(), anyDouble(), anyInt(), anyInt()))
                .thenReturn(sensResult);

        ResponseEntity<SensitivityResult> response =
                callQuickSensitivity("kg-target", 3, 50);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(0.22, response.getBody().getSensitivities().get("kg-parent"), 1e-9);
        assertNotNull(response.getBody().getPriors());
        assertEquals(0.5, response.getBody().getPriors().get("kg-parent"), 1e-9);
        assertEquals(0.4, response.getBody().getBaselinePosterior(), 1e-9);
    }

    /**
     * Quick sensitivity uses the nodeId as both seed AND query node.
     */
    @Test
    void quickSensitivity_usesNodeIdAsBothSeedAndQueryNode() throws Exception {
        when(bayesianService.sensitivityAnalysis(anyCollection(), anyString(),
                anyMap(), anyDouble(), anyInt(), anyInt()))
                .thenReturn(SensitivityResult.builder().queryNodeId("kg-node42").build());

        callQuickSensitivity("kg-node42", 4, 60);

        verify(bayesianService).sensitivityAnalysis(
                eq(List.of("kg-node42")),
                eq("kg-node42"),
                eq(Map.of()),
                eq(0.01),
                eq(4),
                eq(60));
    }

    /**
     * Quick sensitivity passes the path variable maxNodes and maxDepth correctly.
     */
    @Test
    void quickSensitivity_passesMaxDepthAndMaxNodesToService() throws Exception {
        when(bayesianService.sensitivityAnalysis(anyCollection(), anyString(),
                anyMap(), anyDouble(), anyInt(), anyInt()))
                .thenReturn(SensitivityResult.builder()
                        .sensitivities(Map.of("nodeX", 0.3))
                        .queryNodeId("kg-n")
                        .build());

        ResponseEntity<SensitivityResult> response =
                callQuickSensitivity("kg-n", 5, 75);

        assertEquals(200, response.getStatusCode().value());
        verify(bayesianService).sensitivityAnalysis(
                anyCollection(), anyString(), anyMap(), anyDouble(), eq(5), eq(75));
    }

    // =========================================================================
    // POST /api/attribution/bayesian/mpe — most probable explanation
    // =========================================================================

    /**
     * MPE endpoint returns 200 with an MpeResult body.
     */
    @Test
    void mpe_returnsOkWithMpeResult() throws Exception {
        MpeResult expected = makeMpeResult(
                Map.of("kg-a", 1, "kg-b", 0, "kg-c", 1),
                Map.of("A", 0.82, "B", 0.35, "C", 0.77),
                Map.of("A", 0.5, "B", 0.4, "C", 0.5),
                Map.of("A", "kg-a", "B", "kg-b", "C", "kg-c"),
                Map.of("A", "Node A", "B", "Node B", "C", "Node C"));

        when(bayesianService.mostProbableExplanation(anyCollection(), anyMap(),
                anyInt(), anyInt()))
                .thenReturn(expected);

        Object req = makeBayesianQueryRequest(
                List.of("kg-a"), null, Map.of("kg-a", 1), 3, 100);
        ResponseEntity<MpeResult> response = callMpe(req);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
    }

    /**
     * MpeResult must contain assignments with the expected KG node IDs.
     */
    @Test
    void mpe_resultContainsAssignments() throws Exception {
        Map<String, Integer> assignments = Map.of("kg-a", 1, "kg-b", 0, "kg-c", 1);
        MpeResult expected = makeMpeResult(
                assignments,
                Map.of("A", 0.9), Map.of("A", 0.5),
                Map.of("A", "kg-a"), Map.of("A", "Node A"));

        when(bayesianService.mostProbableExplanation(anyCollection(), anyMap(),
                anyInt(), anyInt()))
                .thenReturn(expected);

        Object req = makeBayesianQueryRequest(List.of("kg-a"), null, Map.of(), 3, 100);
        ResponseEntity<MpeResult> response = callMpe(req);

        MpeResult body = response.getBody();
        assertNotNull(body);
        assertNotNull(body.getAssignments(), "assignments must not be null");
        assertFalse(body.getAssignments().isEmpty(), "assignments must not be empty");
        assertEquals(1, body.getAssignments().get("kg-a"));
        assertEquals(0, body.getAssignments().get("kg-b"));
        assertEquals(1, body.getAssignments().get("kg-c"));
    }

    /**
     * MpeResult must contain posteriors.
     */
    @Test
    void mpe_resultContainsPosteriors() throws Exception {
        Map<String, Double> posteriors = Map.of("A", 0.82, "B", 0.35, "C", 0.77);
        MpeResult expected = makeMpeResult(
                Map.of("kg-a", 1), posteriors, Map.of("A", 0.5),
                Map.of("A", "kg-a"), Map.of("A", "Node A"));

        when(bayesianService.mostProbableExplanation(anyCollection(), anyMap(),
                anyInt(), anyInt()))
                .thenReturn(expected);

        Object req = makeBayesianQueryRequest(List.of("kg-a"), null, Map.of(), 3, 100);
        ResponseEntity<MpeResult> response = callMpe(req);

        MpeResult body = response.getBody();
        assertNotNull(body);
        assertNotNull(body.getPosteriors(), "posteriors must not be null");
        assertEquals(0.82, body.getPosteriors().get("A"), 1e-9);
        assertEquals(0.35, body.getPosteriors().get("B"), 1e-9);
        assertEquals(0.77, body.getPosteriors().get("C"), 1e-9);
    }

    /**
     * MpeResult must contain priors for comparison with posteriors.
     */
    @Test
    void mpe_resultContainsPriors() throws Exception {
        Map<String, Double> priors = Map.of("A", 0.5, "B", 0.4, "C", 0.5);
        MpeResult expected = makeMpeResult(
                Map.of("kg-a", 1),
                Map.of("A", 0.82, "B", 0.35, "C", 0.77),
                priors,
                Map.of("A", "kg-a"), Map.of("A", "Node A"));

        when(bayesianService.mostProbableExplanation(anyCollection(), anyMap(),
                anyInt(), anyInt()))
                .thenReturn(expected);

        Object req = makeBayesianQueryRequest(List.of("kg-a"), null, Map.of(), 3, 100);
        ResponseEntity<MpeResult> response = callMpe(req);

        MpeResult body = response.getBody();
        assertNotNull(body);
        assertNotNull(body.getPriors(), "priors must not be null");
        assertFalse(body.getPriors().isEmpty(), "priors must not be empty");
        assertEquals(0.5, body.getPriors().get("A"), 1e-9);
        assertEquals(0.4, body.getPriors().get("B"), 1e-9);
    }

    /**
     * MpeResult must contain an inferenceTrace.
     */
    @Test
    void mpe_resultContainsInferenceTrace() throws Exception {
        MpeResult expected = makeMpeResult(
                Map.of("kg-a", 1),
                Map.of("A", 0.82), Map.of("A", 0.5),
                Map.of("A", "kg-a"), Map.of("A", "Node A"));

        when(bayesianService.mostProbableExplanation(anyCollection(), anyMap(),
                anyInt(), anyInt()))
                .thenReturn(expected);

        Object req = makeBayesianQueryRequest(List.of("kg-a"), null, Map.of(), 3, 100);
        ResponseEntity<MpeResult> response = callMpe(req);

        MpeResult body = response.getBody();
        assertNotNull(body);
        assertNotNull(body.getInferenceTrace(), "inferenceTrace must not be null");
        assertFalse(body.getInferenceTrace().isEmpty(), "inferenceTrace must not be empty");
    }

    /**
     * MpeResult must contain variableToNodeId.
     */
    @Test
    void mpe_resultContainsVariableToNodeId() throws Exception {
        Map<String, String> varToNodeId = Map.of("A", "kg-a", "B", "kg-b", "C", "kg-c");
        MpeResult expected = makeMpeResult(
                Map.of("kg-a", 1),
                Map.of("A", 0.9), Map.of("A", 0.5),
                varToNodeId, Map.of("A", "Node A"));

        when(bayesianService.mostProbableExplanation(anyCollection(), anyMap(),
                anyInt(), anyInt()))
                .thenReturn(expected);

        Object req = makeBayesianQueryRequest(List.of("kg-a"), null, Map.of(), 3, 100);
        ResponseEntity<MpeResult> response = callMpe(req);

        MpeResult body = response.getBody();
        assertNotNull(body);
        assertNotNull(body.getVariableToNodeId(), "variableToNodeId must not be null");
        assertEquals("kg-a", body.getVariableToNodeId().get("A"));
        assertEquals("kg-b", body.getVariableToNodeId().get("B"));
        assertEquals("kg-c", body.getVariableToNodeId().get("C"));
    }

    /**
     * MpeResult must contain variableToTitle.
     */
    @Test
    void mpe_resultContainsVariableToTitle() throws Exception {
        Map<String, String> varToTitle = Map.of("A", "Node A", "B", "Node B", "C", "Node C");
        MpeResult expected = makeMpeResult(
                Map.of("kg-a", 1),
                Map.of("A", 0.9), Map.of("A", 0.5),
                Map.of("A", "kg-a"), varToTitle);

        when(bayesianService.mostProbableExplanation(anyCollection(), anyMap(),
                anyInt(), anyInt()))
                .thenReturn(expected);

        Object req = makeBayesianQueryRequest(List.of("kg-a"), null, Map.of(), 3, 100);
        ResponseEntity<MpeResult> response = callMpe(req);

        MpeResult body = response.getBody();
        assertNotNull(body);
        assertNotNull(body.getVariableToTitle(), "variableToTitle must not be null");
        assertEquals("Node A", body.getVariableToTitle().get("A"));
        assertEquals("Node B", body.getVariableToTitle().get("B"));
        assertEquals("Node C", body.getVariableToTitle().get("C"));
    }

    /**
     * MpeResult must have a non-null computedAt timestamp.
     */
    @Test
    void mpe_resultContainsComputedAt() throws Exception {
        Instant now = Instant.now();
        MpeResult expected = MpeResult.builder()
                .assignments(new LinkedHashMap<>(Map.of("kg-a", 1)))
                .posteriors(new LinkedHashMap<>(Map.of("A", 0.9)))
                .priors(new LinkedHashMap<>(Map.of("A", 0.5)))
                .evidence(new LinkedHashMap<>())
                .inferenceTrace(new ArrayList<>())
                .variableToNodeId(new LinkedHashMap<>(Map.of("A", "kg-a")))
                .variableToTitle(new LinkedHashMap<>(Map.of("A", "Node A")))
                .networkStats(new LinkedHashMap<>())
                .computedAt(now)
                .computationTimeMs(12L)
                .build();

        when(bayesianService.mostProbableExplanation(anyCollection(), anyMap(),
                anyInt(), anyInt()))
                .thenReturn(expected);

        Object req = makeBayesianQueryRequest(List.of("kg-a"), null, Map.of(), 3, 100);
        ResponseEntity<MpeResult> response = callMpe(req);

        MpeResult body = response.getBody();
        assertNotNull(body);
        assertNotNull(body.getComputedAt(), "computedAt must not be null");
        assertEquals(now, body.getComputedAt());
    }

    /**
     * MpeResult must have a non-negative computationTimeMs.
     */
    @Test
    void mpe_resultContainsComputationTimeMs() throws Exception {
        MpeResult expected = MpeResult.builder()
                .assignments(new LinkedHashMap<>(Map.of("kg-a", 1)))
                .posteriors(new LinkedHashMap<>(Map.of("A", 0.9)))
                .priors(new LinkedHashMap<>(Map.of("A", 0.5)))
                .evidence(new LinkedHashMap<>())
                .inferenceTrace(new ArrayList<>())
                .variableToNodeId(new LinkedHashMap<>())
                .variableToTitle(new LinkedHashMap<>())
                .networkStats(new LinkedHashMap<>())
                .computedAt(Instant.now())
                .computationTimeMs(42L)
                .build();

        when(bayesianService.mostProbableExplanation(anyCollection(), anyMap(),
                anyInt(), anyInt()))
                .thenReturn(expected);

        Object req = makeBayesianQueryRequest(List.of("kg-a"), null, Map.of(), 3, 100);
        ResponseEntity<MpeResult> response = callMpe(req);

        MpeResult body = response.getBody();
        assertNotNull(body);
        assertEquals(42L, body.getComputationTimeMs());
        assertTrue(body.getComputationTimeMs() >= 0, "computationTimeMs must be non-negative");
    }

    /**
     * MPE with null evidence in request must default to Map.of() rather than NPE.
     */
    @Test
    void mpe_nullEvidence_defaultsToEmptyMap() throws Exception {
        MpeResult stub = makeMpeResult(
                Map.of("kg-a", 1),
                Map.of("A", 0.9), Map.of("A", 0.5),
                Map.of("A", "kg-a"), Map.of("A", "Node A"));

        when(bayesianService.mostProbableExplanation(anyCollection(), anyMap(),
                anyInt(), anyInt()))
                .thenReturn(stub);

        Object req = makeBayesianQueryRequest(List.of("kg-a"), null, null, null, null);
        ResponseEntity<MpeResult> response = callMpe(req);

        assertEquals(200, response.getStatusCode().value());
        verify(bayesianService).mostProbableExplanation(
                anyCollection(), eq(Map.of()), eq(3), eq(100));
    }

    // =========================================================================
    // POST /api/attribution/bayesian/mebn/query/byType — MEBN query by entity type
    // =========================================================================

    /**
     * When entityType matches variable names, only matching posteriors are returned.
     * This is the primary test for the byType filter logic.
     */
    @Test
    void byType_filtersPosteriorsByEntityType() throws Exception {
        // Full result has 4 variables: 2 ENTITY-type, 2 PROCESS-type
        Map<String, Double> allPosteriors = new LinkedHashMap<>();
        allPosteriors.put("isRisky_ENTITY_node1", 0.80);
        allPosteriors.put("isRisky_ENTITY_node2", 0.65);
        allPosteriors.put("isActive_PROCESS_node3", 0.40);
        allPosteriors.put("isActive_PROCESS_node4", 0.20);

        Map<String, Double> allPriors = new LinkedHashMap<>();
        allPriors.put("isRisky_ENTITY_node1", 0.50);
        allPriors.put("isRisky_ENTITY_node2", 0.50);
        allPriors.put("isActive_PROCESS_node3", 0.50);
        allPriors.put("isActive_PROCESS_node4", 0.50);

        Map<String, String> varToNodeId = new LinkedHashMap<>();
        varToNodeId.put("isRisky_ENTITY_node1", "node1");
        varToNodeId.put("isRisky_ENTITY_node2", "node2");
        varToNodeId.put("isActive_PROCESS_node3", "node3");
        varToNodeId.put("isActive_PROCESS_node4", "node4");

        Map<String, String> varToTitle = new LinkedHashMap<>();
        varToTitle.put("isRisky_ENTITY_node1", "Node 1");
        varToTitle.put("isRisky_ENTITY_node2", "Node 2");
        varToTitle.put("isActive_PROCESS_node3", "Node 3");
        varToTitle.put("isActive_PROCESS_node4", "Node 4");

        BayesianInferenceResult fullResult = makeResult(
                allPosteriors, allPriors, varToNodeId, varToTitle);

        when(bayesianService.queryMebnFromKg(anyCollection(), anyMap(), anyInt(), anyInt()))
                .thenReturn(fullResult);

        // Query for ENTITY type — should keep only the 2 ENTITY variables
        Object req = makeMebnTypeQueryRequest(List.of("seed"), "ENTITY", Map.of(), 3, 100);
        ResponseEntity<BayesianInferenceResult> response = callByType(req);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());

        Map<String, Double> filteredPosteriors = response.getBody().getPosteriors();
        assertNotNull(filteredPosteriors);
        assertEquals(2, filteredPosteriors.size(),
                "Only ENTITY variables should remain in posteriors");
        assertTrue(filteredPosteriors.containsKey("isRisky_ENTITY_node1"));
        assertTrue(filteredPosteriors.containsKey("isRisky_ENTITY_node2"));
        assertFalse(filteredPosteriors.containsKey("isActive_PROCESS_node3"),
                "PROCESS variables must be excluded from posteriors");
        assertFalse(filteredPosteriors.containsKey("isActive_PROCESS_node4"),
                "PROCESS variables must be excluded from posteriors");
    }

    /**
     * Critical regression test: byType must also filter PRIORS to match the
     * same variable set as the filtered posteriors.
     *
     * This was a bug where posteriors were filtered but priors still contained
     * all variables, making prior/posterior comparison meaningless.
     */
    @Test
    void byType_filtersPriorsByEntityType_regressionTest() throws Exception {
        Map<String, Double> allPosteriors = new LinkedHashMap<>();
        allPosteriors.put("isRisky_ENTITY_alpha", 0.75);
        allPosteriors.put("isRisky_ENTITY_beta", 0.60);
        allPosteriors.put("isActive_PROCESS_gamma", 0.45);

        Map<String, Double> allPriors = new LinkedHashMap<>();
        allPriors.put("isRisky_ENTITY_alpha", 0.50);
        allPriors.put("isRisky_ENTITY_beta", 0.50);
        allPriors.put("isActive_PROCESS_gamma", 0.50);

        Map<String, String> varToNodeId = new LinkedHashMap<>();
        varToNodeId.put("isRisky_ENTITY_alpha", "alpha");
        varToNodeId.put("isRisky_ENTITY_beta", "beta");
        varToNodeId.put("isActive_PROCESS_gamma", "gamma");

        Map<String, String> varToTitle = new LinkedHashMap<>();
        varToTitle.put("isRisky_ENTITY_alpha", "Alpha");
        varToTitle.put("isRisky_ENTITY_beta", "Beta");
        varToTitle.put("isActive_PROCESS_gamma", "Gamma");

        BayesianInferenceResult fullResult = makeResult(
                allPosteriors, allPriors, varToNodeId, varToTitle);

        when(bayesianService.queryMebnFromKg(anyCollection(), anyMap(), anyInt(), anyInt()))
                .thenReturn(fullResult);

        Object req = makeMebnTypeQueryRequest(List.of("seed"), "ENTITY", Map.of(), 3, 100);
        ResponseEntity<BayesianInferenceResult> response = callByType(req);

        BayesianInferenceResult body = response.getBody();
        assertNotNull(body);

        // ─── Posteriors check ────────────────────────────────────────────────
        Map<String, Double> filteredPosteriors = body.getPosteriors();
        assertEquals(2, filteredPosteriors.size(), "Posteriors: only ENTITY variables");
        assertTrue(filteredPosteriors.containsKey("isRisky_ENTITY_alpha"));
        assertTrue(filteredPosteriors.containsKey("isRisky_ENTITY_beta"));
        assertFalse(filteredPosteriors.containsKey("isActive_PROCESS_gamma"));

        // ─── Priors check — the regression being tested ──────────────────────
        Map<String, Double> filteredPriors = body.getPriors();
        assertNotNull(filteredPriors, "priors must not be null after filtering");
        assertEquals(2, filteredPriors.size(),
                "Priors must be filtered to the SAME variable set as posteriors (regression fix)");
        assertTrue(filteredPriors.containsKey("isRisky_ENTITY_alpha"),
                "filtered priors must include isRisky_ENTITY_alpha");
        assertTrue(filteredPriors.containsKey("isRisky_ENTITY_beta"),
                "filtered priors must include isRisky_ENTITY_beta");
        assertFalse(filteredPriors.containsKey("isActive_PROCESS_gamma"),
                "filtered priors must NOT include PROCESS variables (regression)");

        // ─── Prior values should be preserved ────────────────────────────────
        assertEquals(0.50, filteredPriors.get("isRisky_ENTITY_alpha"), 1e-9);
        assertEquals(0.50, filteredPriors.get("isRisky_ENTITY_beta"), 1e-9);
    }

    /**
     * byType with null/blank entityType should return all variables unfiltered.
     */
    @Test
    void byType_nullEntityType_returnsAllVariables() throws Exception {
        Map<String, Double> allPosteriors = Map.of("varA", 0.7, "varB", 0.3);
        Map<String, Double> allPriors = Map.of("varA", 0.5, "varB", 0.5);

        BayesianInferenceResult fullResult = makeResult(
                allPosteriors, allPriors,
                Map.of("varA", "kg-a", "varB", "kg-b"),
                Map.of("varA", "Var A", "varB", "Var B"));

        when(bayesianService.queryMebnFromKg(anyCollection(), anyMap(), anyInt(), anyInt()))
                .thenReturn(fullResult);

        Object req = makeMebnTypeQueryRequest(List.of("seed"), null, Map.of(), 3, 100);
        ResponseEntity<BayesianInferenceResult> response = callByType(req);

        BayesianInferenceResult body = response.getBody();
        assertNotNull(body);
        // No filter applied → all 2 variables remain
        assertEquals(2, body.getPosteriors().size(), "All variables must remain when entityType is null");
    }

    /**
     * byType with a blank entityType string also returns all variables unfiltered.
     */
    @Test
    void byType_blankEntityType_returnsAllVariables() throws Exception {
        Map<String, Double> allPosteriors = Map.of("varX", 0.8, "varY", 0.2);
        BayesianInferenceResult fullResult = makeResult(
                allPosteriors, Map.of("varX", 0.5, "varY", 0.5),
                Map.of("varX", "kg-x", "varY", "kg-y"),
                Map.of("varX", "Var X", "varY", "Var Y"));

        when(bayesianService.queryMebnFromKg(anyCollection(), anyMap(), anyInt(), anyInt()))
                .thenReturn(fullResult);

        Object req = makeMebnTypeQueryRequest(List.of("seed"), "   ", Map.of(), 3, 100);
        ResponseEntity<BayesianInferenceResult> response = callByType(req);

        BayesianInferenceResult body = response.getBody();
        assertNotNull(body);
        assertEquals(2, body.getPosteriors().size(), "All variables must remain when entityType is blank");
    }

    /**
     * byType filter is case-insensitive (ENTITY matches entity, Entity, etc.).
     */
    @Test
    void byType_filterIsCaseInsensitive() throws Exception {
        Map<String, Double> allPosteriors = new LinkedHashMap<>();
        allPosteriors.put("isRisky_ENTITY_n1", 0.8);
        allPosteriors.put("isRisky_process_n2", 0.4);

        BayesianInferenceResult fullResult = makeResult(
                allPosteriors,
                new LinkedHashMap<>(Map.of("isRisky_ENTITY_n1", 0.5, "isRisky_process_n2", 0.5)),
                new LinkedHashMap<>(Map.of("isRisky_ENTITY_n1", "n1", "isRisky_process_n2", "n2")),
                new LinkedHashMap<>(Map.of("isRisky_ENTITY_n1", "N1", "isRisky_process_n2", "N2")));

        when(bayesianService.queryMebnFromKg(anyCollection(), anyMap(), anyInt(), anyInt()))
                .thenReturn(fullResult);

        // lowercase "entity" should still match "ENTITY"
        Object req = makeMebnTypeQueryRequest(List.of("seed"), "entity", Map.of(), 3, 100);
        ResponseEntity<BayesianInferenceResult> response = callByType(req);

        BayesianInferenceResult body = response.getBody();
        assertNotNull(body);
        assertEquals(1, body.getPosteriors().size(),
                "Case-insensitive match: 'entity' should match 'ENTITY'");
        assertTrue(body.getPosteriors().containsKey("isRisky_ENTITY_n1"));
    }

    /**
     * byType filtered variableToNodeId only contains keys for matching variables.
     */
    @Test
    void byType_filtersVariableToNodeId() throws Exception {
        Map<String, Double> allPosteriors = new LinkedHashMap<>();
        allPosteriors.put("isRisky_ENTITY_n1", 0.8);
        allPosteriors.put("isActive_PROCESS_n2", 0.4);

        BayesianInferenceResult fullResult = makeResult(
                allPosteriors,
                new LinkedHashMap<>(Map.of("isRisky_ENTITY_n1", 0.5, "isActive_PROCESS_n2", 0.5)),
                new LinkedHashMap<>(Map.of("isRisky_ENTITY_n1", "n1", "isActive_PROCESS_n2", "n2")),
                new LinkedHashMap<>(Map.of("isRisky_ENTITY_n1", "Node 1", "isActive_PROCESS_n2", "Node 2")));

        when(bayesianService.queryMebnFromKg(anyCollection(), anyMap(), anyInt(), anyInt()))
                .thenReturn(fullResult);

        Object req = makeMebnTypeQueryRequest(List.of("seed"), "ENTITY", Map.of(), 3, 100);
        ResponseEntity<BayesianInferenceResult> response = callByType(req);

        BayesianInferenceResult body = response.getBody();
        assertNotNull(body);
        Map<String, String> filteredNodeIds = body.getVariableToNodeId();
        assertNotNull(filteredNodeIds);
        assertEquals(1, filteredNodeIds.size(), "variableToNodeId must be filtered");
        assertEquals("n1", filteredNodeIds.get("isRisky_ENTITY_n1"));
        assertFalse(filteredNodeIds.containsKey("isActive_PROCESS_n2"),
                "PROCESS variables must not appear in filtered variableToNodeId");
    }

    /**
     * byType filtered variableToTitle only contains keys for matching variables.
     */
    @Test
    void byType_filtersVariableToTitle() throws Exception {
        Map<String, Double> allPosteriors = new LinkedHashMap<>();
        allPosteriors.put("isRisky_ENTITY_n1", 0.9);
        allPosteriors.put("isActive_PROCESS_n2", 0.1);

        BayesianInferenceResult fullResult = makeResult(
                allPosteriors,
                new LinkedHashMap<>(Map.of("isRisky_ENTITY_n1", 0.5, "isActive_PROCESS_n2", 0.5)),
                new LinkedHashMap<>(Map.of("isRisky_ENTITY_n1", "n1", "isActive_PROCESS_n2", "n2")),
                new LinkedHashMap<>(Map.of("isRisky_ENTITY_n1", "Title Entity", "isActive_PROCESS_n2", "Title Process")));

        when(bayesianService.queryMebnFromKg(anyCollection(), anyMap(), anyInt(), anyInt()))
                .thenReturn(fullResult);

        Object req = makeMebnTypeQueryRequest(List.of("seed"), "ENTITY", Map.of(), 3, 100);
        ResponseEntity<BayesianInferenceResult> response = callByType(req);

        BayesianInferenceResult body = response.getBody();
        assertNotNull(body);
        Map<String, String> filteredTitles = body.getVariableToTitle();
        assertNotNull(filteredTitles);
        assertEquals(1, filteredTitles.size(), "variableToTitle must be filtered");
        assertEquals("Title Entity", filteredTitles.get("isRisky_ENTITY_n1"));
        assertFalse(filteredTitles.containsKey("isActive_PROCESS_n2"),
                "PROCESS variables must not appear in filtered variableToTitle");
    }

    /**
     * byType where no variables match the entity type returns empty posteriors and priors.
     */
    @Test
    void byType_noMatchingVariables_returnsEmptyMaps() throws Exception {
        Map<String, Double> allPosteriors = new LinkedHashMap<>();
        allPosteriors.put("isActive_PROCESS_n1", 0.7);

        BayesianInferenceResult fullResult = makeResult(
                allPosteriors,
                new LinkedHashMap<>(Map.of("isActive_PROCESS_n1", 0.5)),
                new LinkedHashMap<>(Map.of("isActive_PROCESS_n1", "n1")),
                new LinkedHashMap<>(Map.of("isActive_PROCESS_n1", "Node 1")));

        when(bayesianService.queryMebnFromKg(anyCollection(), anyMap(), anyInt(), anyInt()))
                .thenReturn(fullResult);

        // Ask for ENTITY — none present
        Object req = makeMebnTypeQueryRequest(List.of("seed"), "ENTITY", Map.of(), 3, 100);
        ResponseEntity<BayesianInferenceResult> response = callByType(req);

        BayesianInferenceResult body = response.getBody();
        assertNotNull(body);
        assertTrue(body.getPosteriors().isEmpty(), "No ENTITY variables → empty posteriors");
        // Priors must also be empty (matching the empty posterior variable set)
        Map<String, Double> filteredPriors = body.getPriors();
        assertNotNull(filteredPriors);
        assertTrue(filteredPriors.isEmpty(),
                "No ENTITY variables → priors must also be empty (regression check)");
    }

    /**
     * byType passes evidence through to the underlying MEBN service call.
     */
    @Test
    void byType_passesEvidenceToService() throws Exception {
        BayesianInferenceResult fullResult = makeResult(
                new LinkedHashMap<>(), new LinkedHashMap<>(),
                new LinkedHashMap<>(), new LinkedHashMap<>());

        when(bayesianService.queryMebnFromKg(anyCollection(), anyMap(), anyInt(), anyInt()))
                .thenReturn(fullResult);

        Map<String, Integer> evidence = Map.of("var_EVIDENCE", 1);
        Object req = makeMebnTypeQueryRequest(List.of("seed"), "ENTITY", evidence, 5, 200);
        callByType(req);

        verify(bayesianService).queryMebnFromKg(
                eq(List.of("seed")),
                eq(evidence),
                eq(5),
                eq(200));
    }

    // =========================================================================
    // GET /api/attribution/bayesian/mebn/structure/{nodeId} — MEBN structure
    // =========================================================================

    /**
     * The /mebn/structure endpoint should call queryMebnFromKg and return
     * results that include variableToMebnMeta when available.
     */
    @Test
    void mebnStructure_callsServiceAndReturnsResult() {
        Map<String, Double> posteriors = new LinkedHashMap<>(Map.of("isRisky_ENTITY_n1", 0.8));
        Map<String, Double> priors = new LinkedHashMap<>(Map.of("isRisky_ENTITY_n1", 0.5));
        Map<String, String> varToNodeId = new LinkedHashMap<>(Map.of("isRisky_ENTITY_n1", "n1"));
        Map<String, String> varToTitle = new LinkedHashMap<>(Map.of("isRisky_ENTITY_n1", "Node 1"));

        BayesianInferenceResult result = makeResult(posteriors, priors, varToNodeId, varToTitle);

        // Simulate MEBN meta
        Map<String, Map<String, String>> mebnMeta = new LinkedHashMap<>();
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("mfragName", "isRisky_MFrag");
        entry.put("nodeRole", "RESIDENT");
        entry.put("rvName", "isRisky");
        entry.put("entityType", "ENTITY");
        entry.put("entityId", "n1");
        mebnMeta.put("isRisky_ENTITY_n1", entry);
        result.setVariableToMebnMeta(mebnMeta);

        when(bayesianService.queryMebnFromKg(eq(List.of("test-node")), eq(Map.of()), eq(3), eq(100)))
                .thenReturn(result);

        ResponseEntity<BayesianInferenceResult> response = controller.mebnStructure("test-node", 3, 100);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(0.8, response.getBody().getPosteriors().get("isRisky_ENTITY_n1"), 0.001);
        assertNotNull(response.getBody().getVariableToMebnMeta());
        assertEquals(1, response.getBody().getVariableToMebnMeta().size());
        Map<String, String> meta = response.getBody().getVariableToMebnMeta().get("isRisky_ENTITY_n1");
        assertNotNull(meta);
        assertEquals("isRisky_MFrag", meta.get("mfragName"));
        assertEquals("RESIDENT", meta.get("nodeRole"));
        assertEquals("ENTITY", meta.get("entityType"));
        assertEquals("n1", meta.get("entityId"));

        verify(bayesianService).queryMebnFromKg(List.of("test-node"), Map.of(), 3, 100);
    }

    /**
     * The /mebn/structure endpoint should pass custom maxDepth and maxNodes.
     */
    @Test
    void mebnStructure_usesCustomDepthAndNodes() {
        BayesianInferenceResult result = makeResult(
                new LinkedHashMap<>(), new LinkedHashMap<>(),
                new LinkedHashMap<>(), new LinkedHashMap<>());

        when(bayesianService.queryMebnFromKg(anyCollection(), anyMap(), anyInt(), anyInt()))
                .thenReturn(result);

        controller.mebnStructure("node-abc", 5, 200);

        verify(bayesianService).queryMebnFromKg(List.of("node-abc"), Map.of(), 5, 200);
    }
}
