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

import ai.kompile.event.attribution.domain.*;
import ai.kompile.event.attribution.service.EventAttributionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link EventAttributionController}.
 *
 * <p>Tests are pure JUnit 5 + Mockito — no Spring context is loaded.
 * The controller is instantiated directly via its constructor with a mocked
 * {@link EventAttributionService}.</p>
 */
@ExtendWith(MockitoExtension.class)
class EventAttributionControllerTest {

    @Mock
    private EventAttributionService attributionService;

    private EventAttributionController controller;

    // ── Shared test fixtures ────────────────────────────────────────────────

    private AttributionResult sampleAttributionResult;
    private PredictionResult samplePredictionResult;

    @BeforeEach
    void setUp() {
        controller = new EventAttributionController(attributionService);

        // Build realistic AttributionResult with 2 chains, each with 2 hops and evidence,
        // plus 1 counterfactual.

        AttributionEvidence evidenceA = AttributionEvidence.builder()
                .evidenceType(EvidenceType.DIRECT_EXTRACTION)
                .strength(0.92)
                .edgeId("edge-001")
                .sourceNodeId("node-root")
                .summary("Server overload explicitly stated as cause of latency spike")
                .sourceSnippet("The server was overloaded, which caused the latency spike observed.")
                .sourceReference("incident-report-2025-001")
                .collectedAt(Instant.parse("2025-06-01T10:00:00Z"))
                .metadata(Map.of("extractor", "NLP-v2"))
                .build();

        AttributionEvidence evidenceB = AttributionEvidence.builder()
                .evidenceType(EvidenceType.TEMPORAL_PROXIMITY)
                .strength(0.75)
                .edgeId("edge-002")
                .sourceNodeId("node-mid")
                .summary("Latency spike preceded outage by 2 minutes")
                .sourceSnippet("Metrics show latency crossed threshold 2 min before user-facing errors began.")
                .sourceReference("metrics-dashboard-2025-06-01")
                .collectedAt(Instant.parse("2025-06-01T10:01:00Z"))
                .metadata(Map.of("timeDeltaSeconds", 120))
                .build();

        AttributionEvidence evidenceC = AttributionEvidence.builder()
                .evidenceType(EvidenceType.INFLUENCE_PROPAGATION)
                .strength(0.68)
                .edgeId("edge-003")
                .sourceNodeId("node-root")
                .summary("High influence score accumulated at root via backward propagation")
                .sourceReference("propagation-run-001")
                .collectedAt(Instant.parse("2025-06-01T10:02:00Z"))
                .metadata(Map.of("propagationIterations", 5))
                .build();

        AttributionEvidence evidenceD = AttributionEvidence.builder()
                .evidenceType(EvidenceType.LLM_INFERENCE)
                .strength(0.85)
                .edgeId("edge-004")
                .sourceNodeId("node-alt-root")
                .summary("LLM inferred database bottleneck as alternative root cause")
                .sourceReference("llm-inference-run-002")
                .collectedAt(Instant.parse("2025-06-01T10:03:00Z"))
                .metadata(Map.of("model", "claude-3-5-sonnet"))
                .build();

        // Chain 1: root -> mid -> target (CAUSES chain)
        CausalHop hop1 = CausalHop.builder()
                .causeNodeId("node-root")
                .causeTitle("Server Overloaded")
                .effectNodeId("node-mid")
                .effectTitle("Response Latency Spike")
                .causalType(CausalEdgeType.CAUSES)
                .strength(0.88)
                .causeTimestamp(Instant.parse("2025-06-01T09:55:00Z"))
                .effectTimestamp(Instant.parse("2025-06-01T09:57:00Z"))
                .evidence(List.of(evidenceA))
                .llmExplanation("The server overload directly caused response latency to spike.")
                .build();

        CausalHop hop2 = CausalHop.builder()
                .causeNodeId("node-mid")
                .causeTitle("Response Latency Spike")
                .effectNodeId("node-target")
                .effectTitle("User-Facing Outage")
                .causalType(CausalEdgeType.TRIGGERS)
                .strength(0.80)
                .causeTimestamp(Instant.parse("2025-06-01T09:57:00Z"))
                .effectTimestamp(Instant.parse("2025-06-01T09:59:00Z"))
                .evidence(List.of(evidenceB))
                .llmExplanation("The latency spike triggered connection timeouts causing the user-facing outage.")
                .build();

        AttributionChain chain1 = AttributionChain.builder()
                .chainId("chain-001")
                .targetEventNodeId("node-target")
                .targetEventTitle("User-Facing Outage")
                .rootCauseNodeId("node-root")
                .rootCauseTitle("Server Overloaded")
                .hops(List.of(hop1, hop2))
                .overallConfidence(0.82)
                .confidenceBand(AttributionConfidence.HIGH)
                .narrative("The server overloaded, causing a latency spike that ultimately triggered a user-facing outage.")
                .computedAt(Instant.parse("2025-06-01T10:00:00Z"))
                .build();

        // Chain 2: alt-root -> target (alternative CONTRIBUTES_TO path)
        CausalHop hop3 = CausalHop.builder()
                .causeNodeId("node-alt-root")
                .causeTitle("Database Connection Pool Exhausted")
                .effectNodeId("node-mid")
                .effectTitle("Response Latency Spike")
                .causalType(CausalEdgeType.CONTRIBUTES_TO)
                .strength(0.65)
                .causeTimestamp(Instant.parse("2025-06-01T09:54:00Z"))
                .effectTimestamp(Instant.parse("2025-06-01T09:57:00Z"))
                .evidence(List.of(evidenceC))
                .llmExplanation("Connection pool exhaustion contributed to the latency spike by starving worker threads.")
                .build();

        CausalHop hop4 = CausalHop.builder()
                .causeNodeId("node-mid")
                .causeTitle("Response Latency Spike")
                .effectNodeId("node-target")
                .effectTitle("User-Facing Outage")
                .causalType(CausalEdgeType.TRIGGERS)
                .strength(0.80)
                .causeTimestamp(Instant.parse("2025-06-01T09:57:00Z"))
                .effectTimestamp(Instant.parse("2025-06-01T09:59:00Z"))
                .evidence(List.of(evidenceD))
                .llmExplanation("LLM confirmed latency spike as the direct trigger of the outage.")
                .build();

        AttributionChain chain2 = AttributionChain.builder()
                .chainId("chain-002")
                .targetEventNodeId("node-target")
                .targetEventTitle("User-Facing Outage")
                .rootCauseNodeId("node-alt-root")
                .rootCauseTitle("Database Connection Pool Exhausted")
                .hops(List.of(hop3, hop4))
                .overallConfidence(0.55)
                .confidenceBand(AttributionConfidence.MODERATE)
                .narrative("Database connection pool exhaustion contributed to the latency spike that led to the outage.")
                .computedAt(Instant.parse("2025-06-01T10:00:00Z"))
                .build();

        CounterfactualResult counterfactual = CounterfactualResult.builder()
                .removedNodeId("node-root")
                .removedNodeTitle("Server Overloaded")
                .targetStillReachable(false)
                .survivingChainCount(0)
                .confidenceDelta(-0.82)
                .explanation("Without the server overload, the primary causal chain is severed — the outage would not have occurred.")
                .build();

        sampleAttributionResult = AttributionResult.builder()
                .query(AttributionQuery.builder().targetNodeId("node-target").build())
                .targetNodeId("node-target")
                .targetTitle("User-Facing Outage")
                .chains(List.of(chain1, chain2))
                .synthesizedExplanation("The user-facing outage was primarily caused by server overload, with a secondary contributing factor of database connection pool exhaustion.")
                .influenceScores(Map.of("node-root", 0.82, "node-alt-root", 0.55, "node-mid", 0.70))
                .counterfactuals(List.of(counterfactual))
                .deadEnds(List.of("node-unrelated"))
                .computedAt(Instant.parse("2025-06-01T10:00:05Z"))
                .computationTimeMs(320)
                .nodesVisited(12)
                .edgesExamined(18)
                .llmUsed(true)
                .build();

        // Build PredictionResult with 3 predictions
        AttributionEvidence predEvidence1 = AttributionEvidence.builder()
                .evidenceType(EvidenceType.GRAPH_STRUCTURAL)
                .strength(0.90)
                .sourceNodeId("node-root")
                .summary("Strong structural edge from root to downstream-1")
                .build();

        AttributionEvidence predEvidence2 = AttributionEvidence.builder()
                .evidenceType(EvidenceType.EMBEDDING_SIMILARITY)
                .strength(0.72)
                .sourceNodeId("node-root")
                .summary("Semantic similarity between root and downstream-2 events")
                .build();

        PredictedEvent prediction1 = PredictedEvent.builder()
                .nodeId("node-downstream-1")
                .title("Service Degradation Alert Fired")
                .probability(0.91)
                .hopsFromSource(1)
                .pathFromSource(List.of("node-root", "node-downstream-1"))
                .pathEdgeTypes(List.of(CausalEdgeType.TRIGGERS))
                .explanation("With high probability, a service degradation alert will fire next.")
                .evidence(List.of(predEvidence1))
                .build();

        PredictedEvent prediction2 = PredictedEvent.builder()
                .nodeId("node-downstream-2")
                .title("Customer Support Escalation")
                .probability(0.73)
                .hopsFromSource(2)
                .pathFromSource(List.of("node-root", "node-downstream-1", "node-downstream-2"))
                .pathEdgeTypes(List.of(CausalEdgeType.TRIGGERS, CausalEdgeType.CAUSES))
                .explanation("If the alert fires and is unresolved, a customer support escalation is likely.")
                .evidence(List.of(predEvidence2))
                .build();

        PredictedEvent prediction3 = PredictedEvent.builder()
                .nodeId("node-downstream-3")
                .title("Automated Rollback Initiated")
                .probability(0.58)
                .hopsFromSource(1)
                .pathFromSource(List.of("node-root", "node-downstream-3"))
                .pathEdgeTypes(List.of(CausalEdgeType.ENABLES))
                .explanation("The system's SRE runbooks enable an automated rollback from this state.")
                .evidence(List.of())
                .build();

        samplePredictionResult = PredictionResult.builder()
                .query(PredictionQuery.builder().sourceNodeId("node-root").build())
                .sourceNodeId("node-root")
                .sourceTitle("Server Overloaded")
                .predictions(List.of(prediction1, prediction2, prediction3))
                .synthesizedForecast("Starting from server overload, the most likely cascade is: alert fires → customer escalation, with a parallel chance of automated rollback.")
                .computedAt(Instant.parse("2025-06-01T10:00:10Z"))
                .computationTimeMs(180)
                .nodesVisited(8)
                .llmUsed(true)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // POST /api/attribution/explain
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void explain_post_returnsAttributionResult() {
        when(attributionService.explain(any(AttributionQuery.class))).thenReturn(sampleAttributionResult);

        EventAttributionController.AttributionQueryRequest request =
                new EventAttributionController.AttributionQueryRequest(
                        "node-target",
                        "Why did the outage happen?",
                        42L,
                        7,
                        3,
                        0.2,
                        true,
                        true,
                        Set.of(CausalEdgeType.CAUSES, CausalEdgeType.TRIGGERS),
                        Set.of(EvidenceType.DIRECT_EXTRACTION)
                );

        ResponseEntity<AttributionResult> response = controller.explain(request);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("node-target", response.getBody().getTargetNodeId());
        assertEquals("User-Facing Outage", response.getBody().getTargetTitle());
    }

    @Test
    void explain_post_returnsChains() {
        when(attributionService.explain(any(AttributionQuery.class))).thenReturn(sampleAttributionResult);

        EventAttributionController.AttributionQueryRequest request =
                new EventAttributionController.AttributionQueryRequest(
                        "node-target", null, null, null, null, null, null, null, null, null
                );

        ResponseEntity<AttributionResult> response = controller.explain(request);

        assertNotNull(response.getBody());
        List<AttributionChain> chains = response.getBody().getChains();
        assertEquals(2, chains.size());

        // Chain 1 assertions
        AttributionChain c1 = chains.get(0);
        assertEquals("chain-001", c1.getChainId());
        assertEquals("node-root", c1.getRootCauseNodeId());
        assertEquals("Server Overloaded", c1.getRootCauseTitle());
        assertEquals(2, c1.getDepth());
        assertEquals(AttributionConfidence.HIGH, c1.getConfidenceBand());
        assertEquals(0.82, c1.getOverallConfidence(), 1e-9);

        // Chain 1 hops + evidence
        CausalHop firstHop = c1.getHops().get(0);
        assertEquals(CausalEdgeType.CAUSES, firstHop.getCausalType());
        assertFalse(firstHop.getEvidence().isEmpty());
        assertEquals(EvidenceType.DIRECT_EXTRACTION, firstHop.getEvidence().get(0).getEvidenceType());
        assertEquals(0.92, firstHop.getEvidence().get(0).getStrength(), 1e-9);

        // Chain 2 assertions
        AttributionChain c2 = chains.get(1);
        assertEquals("chain-002", c2.getChainId());
        assertEquals(AttributionConfidence.MODERATE, c2.getConfidenceBand());
        assertEquals(2, c2.getDepth());
    }

    @Test
    void explain_post_mapsQueryParameters() {
        when(attributionService.explain(any(AttributionQuery.class))).thenReturn(sampleAttributionResult);

        Set<CausalEdgeType> allowedTypes = Set.of(CausalEdgeType.CAUSES, CausalEdgeType.ENABLES);
        Set<EvidenceType> requiredEvidence = Set.of(EvidenceType.LLM_INFERENCE, EvidenceType.GRAPH_STRUCTURAL);

        EventAttributionController.AttributionQueryRequest request =
                new EventAttributionController.AttributionQueryRequest(
                        "node-target",
                        "What caused the failure?",
                        99L,
                        8,
                        4,
                        0.25,
                        false,
                        true,
                        allowedTypes,
                        requiredEvidence
                );

        ArgumentCaptor<AttributionQuery> captor = ArgumentCaptor.forClass(AttributionQuery.class);
        controller.explain(request);
        verify(attributionService).explain(captor.capture());

        AttributionQuery captured = captor.getValue();
        assertEquals("node-target", captured.getTargetNodeId());
        assertEquals("What caused the failure?", captured.getNaturalLanguageQuery());
        assertEquals(99L, captured.getFactSheetId());
        assertEquals(8, captured.getMaxDepth());
        assertEquals(4, captured.getMaxChains());
        assertEquals(0.25, captured.getMinConfidence(), 1e-9);
        assertFalse(captured.isUseLlm());
        assertTrue(captured.isIncludeCounterfactuals());
        assertEquals(allowedTypes, captured.getAllowedCausalTypes());
        assertEquals(requiredEvidence, captured.getRequiredEvidenceTypes());
    }

    @Test
    void explain_post_defaultsWhenNullableParamsOmitted() {
        when(attributionService.explain(any(AttributionQuery.class))).thenReturn(sampleAttributionResult);

        // All nullable fields are null — controller should apply defaults
        EventAttributionController.AttributionQueryRequest request =
                new EventAttributionController.AttributionQueryRequest(
                        "node-target",
                        null,
                        null,
                        null,   // maxDepth → default 5
                        null,   // maxChains → default 5
                        null,   // minConfidence → default 0.1
                        null,   // useLlm → default true
                        null,   // includeCounterfactuals → default false
                        null,
                        null
                );

        ArgumentCaptor<AttributionQuery> captor = ArgumentCaptor.forClass(AttributionQuery.class);
        controller.explain(request);
        verify(attributionService).explain(captor.capture());

        AttributionQuery captured = captor.getValue();
        assertEquals(5, captured.getMaxDepth());
        assertEquals(5, captured.getMaxChains());
        assertEquals(0.1, captured.getMinConfidence(), 1e-9);
        assertTrue(captured.isUseLlm());
        assertFalse(captured.isIncludeCounterfactuals());
    }

    @Test
    void explain_post_counterfactualsIncluded() {
        when(attributionService.explain(any(AttributionQuery.class))).thenReturn(sampleAttributionResult);

        EventAttributionController.AttributionQueryRequest request =
                new EventAttributionController.AttributionQueryRequest(
                        "node-target", null, null, null, null, null, null, null, null, null
                );

        ResponseEntity<AttributionResult> response = controller.explain(request);

        assertNotNull(response.getBody());
        List<CounterfactualResult> counterfactuals = response.getBody().getCounterfactuals();
        assertFalse(counterfactuals.isEmpty());
        CounterfactualResult cf = counterfactuals.get(0);
        assertEquals("node-root", cf.getRemovedNodeId());
        assertEquals("Server Overloaded", cf.getRemovedNodeTitle());
        assertFalse(cf.isTargetStillReachable());
        assertEquals(0, cf.getSurvivingChainCount());
        assertTrue(cf.isNecessaryCause());
        assertEquals(-0.82, cf.getConfidenceDelta(), 1e-9);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GET /api/attribution/explain/{nodeId}
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void explainQuick_get_returnsAttributionResult() {
        when(attributionService.explain(any(AttributionQuery.class))).thenReturn(sampleAttributionResult);

        ResponseEntity<AttributionResult> response = controller.explainQuick(
                "node-target", "Why did the outage happen?", 42L, 7, 3, true, true
        );

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("node-target", response.getBody().getTargetNodeId());
        assertEquals(2, response.getBody().getChains().size());
    }

    @Test
    void explainQuick_get_mapsQueryParameters() {
        when(attributionService.explain(any(AttributionQuery.class))).thenReturn(sampleAttributionResult);

        ArgumentCaptor<AttributionQuery> captor = ArgumentCaptor.forClass(AttributionQuery.class);
        controller.explainQuick("node-target", "Why?", 7L, 4, 2, false, true);
        verify(attributionService).explain(captor.capture());

        AttributionQuery captured = captor.getValue();
        assertEquals("node-target", captured.getTargetNodeId());
        assertEquals("Why?", captured.getNaturalLanguageQuery());
        assertEquals(7L, captured.getFactSheetId());
        assertEquals(4, captured.getMaxDepth());
        assertEquals(2, captured.getMaxChains());
        assertFalse(captured.isUseLlm());
        assertTrue(captured.isIncludeCounterfactuals());
    }

    @Test
    void explainQuick_get_defaultValues() {
        when(attributionService.explain(any(AttributionQuery.class))).thenReturn(sampleAttributionResult);

        // Call with the Spring-default-equivalent values: maxDepth=5, maxChains=5, useLlm=true, includeCounterfactuals=false
        ArgumentCaptor<AttributionQuery> captor = ArgumentCaptor.forClass(AttributionQuery.class);
        controller.explainQuick("node-target", null, null, 5, 5, true, false);
        verify(attributionService).explain(captor.capture());

        AttributionQuery captured = captor.getValue();
        assertEquals("node-target", captured.getTargetNodeId());
        assertNull(captured.getNaturalLanguageQuery());
        assertNull(captured.getFactSheetId());
        assertEquals(5, captured.getMaxDepth());
        assertEquals(5, captured.getMaxChains());
        assertTrue(captured.isUseLlm());
        assertFalse(captured.isIncludeCounterfactuals());
        // minConfidence not set by quick endpoint — Lombok @Builder.Default gives 0.1
        assertEquals(0.1, captured.getMinConfidence(), 1e-9);
    }

    @Test
    void explainQuick_get_chainsHaveEvidenceEntries() {
        when(attributionService.explain(any(AttributionQuery.class))).thenReturn(sampleAttributionResult);

        ResponseEntity<AttributionResult> response =
                controller.explainQuick("node-target", null, null, 5, 5, true, false);

        assertNotNull(response.getBody());
        List<AttributionChain> chains = response.getBody().getChains();
        assertFalse(chains.isEmpty());
        // Every chain must have at least one hop with evidence
        for (AttributionChain chain : chains) {
            assertFalse(chain.getHops().isEmpty());
            for (CausalHop hop : chain.getHops()) {
                assertFalse(hop.getEvidence().isEmpty(),
                        "Hop " + hop.getCauseNodeId() + " -> " + hop.getEffectNodeId() + " should have evidence");
            }
        }
    }

    @Test
    void explainQuick_get_evidenceTypesAreDerived() {
        when(attributionService.explain(any(AttributionQuery.class))).thenReturn(sampleAttributionResult);

        ResponseEntity<AttributionResult> response =
                controller.explainQuick("node-target", null, null, 5, 5, true, false);

        assertNotNull(response.getBody());
        AttributionChain chain1 = response.getBody().getChains().get(0);
        List<EvidenceType> types = chain1.getEvidenceTypes();
        assertFalse(types.isEmpty());
        // Chain 1 has DIRECT_EXTRACTION (hop1) and TEMPORAL_PROXIMITY (hop2)
        assertTrue(types.contains(EvidenceType.DIRECT_EXTRACTION));
        assertTrue(types.contains(EvidenceType.TEMPORAL_PROXIMITY));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // POST /api/attribution/predict
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void predict_post_returnsPredictionResult() {
        when(attributionService.predict(any(PredictionQuery.class))).thenReturn(samplePredictionResult);

        EventAttributionController.PredictionQueryRequest request =
                new EventAttributionController.PredictionQueryRequest(
                        "node-root",
                        "What happens after server overload?",
                        42L,
                        4,
                        15,
                        0.3,
                        true
                );

        ResponseEntity<PredictionResult> response = controller.predict(request);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("node-root", response.getBody().getSourceNodeId());
        assertEquals("Server Overloaded", response.getBody().getSourceTitle());
    }

    @Test
    void predict_post_returnsPredictions() {
        when(attributionService.predict(any(PredictionQuery.class))).thenReturn(samplePredictionResult);

        EventAttributionController.PredictionQueryRequest request =
                new EventAttributionController.PredictionQueryRequest(
                        "node-root", null, null, null, null, null, null
                );

        ResponseEntity<PredictionResult> response = controller.predict(request);

        assertNotNull(response.getBody());
        List<PredictedEvent> predictions = response.getBody().getPredictions();
        assertEquals(3, predictions.size());

        PredictedEvent p1 = predictions.get(0);
        assertEquals("node-downstream-1", p1.getNodeId());
        assertEquals("Service Degradation Alert Fired", p1.getTitle());
        assertEquals(0.91, p1.getProbability(), 1e-9);
        assertEquals(1, p1.getHopsFromSource());
        assertFalse(p1.getEvidence().isEmpty());
        assertEquals(EvidenceType.GRAPH_STRUCTURAL, p1.getEvidence().get(0).getEvidenceType());

        PredictedEvent p2 = predictions.get(1);
        assertEquals("node-downstream-2", p2.getNodeId());
        assertEquals(0.73, p2.getProbability(), 1e-9);
        assertEquals(2, p2.getHopsFromSource());
        assertEquals(2, p2.getPathEdgeTypes().size());

        PredictedEvent p3 = predictions.get(2);
        assertEquals("node-downstream-3", p3.getNodeId());
        assertEquals(0.58, p3.getProbability(), 1e-9);
        assertEquals(CausalEdgeType.ENABLES, p3.getPathEdgeTypes().get(0));
    }

    @Test
    void predict_post_mapsQueryParameters() {
        when(attributionService.predict(any(PredictionQuery.class))).thenReturn(samplePredictionResult);

        EventAttributionController.PredictionQueryRequest request =
                new EventAttributionController.PredictionQueryRequest(
                        "node-root",
                        "What is the likely cascade?",
                        77L,
                        6,
                        20,
                        0.35,
                        false
                );

        ArgumentCaptor<PredictionQuery> captor = ArgumentCaptor.forClass(PredictionQuery.class);
        controller.predict(request);
        verify(attributionService).predict(captor.capture());

        PredictionQuery captured = captor.getValue();
        assertEquals("node-root", captured.getSourceNodeId());
        assertEquals("What is the likely cascade?", captured.getNaturalLanguageContext());
        assertEquals(77L, captured.getFactSheetId());
        assertEquals(6, captured.getMaxDepth());
        assertEquals(20, captured.getMaxPredictions());
        assertEquals(0.35, captured.getMinProbability(), 1e-9);
        assertFalse(captured.isUseLlm());
    }

    @Test
    void predict_post_defaultsWhenNullableParamsOmitted() {
        when(attributionService.predict(any(PredictionQuery.class))).thenReturn(samplePredictionResult);

        // All nullable fields null — controller applies defaults
        EventAttributionController.PredictionQueryRequest request =
                new EventAttributionController.PredictionQueryRequest(
                        "node-root",
                        null,
                        null,
                        null,   // maxDepth → default 3
                        null,   // maxPredictions → default 10
                        null,   // minProbability → default 0.1
                        null    // useLlm → default true
                );

        ArgumentCaptor<PredictionQuery> captor = ArgumentCaptor.forClass(PredictionQuery.class);
        controller.predict(request);
        verify(attributionService).predict(captor.capture());

        PredictionQuery captured = captor.getValue();
        assertEquals(3, captured.getMaxDepth());
        assertEquals(10, captured.getMaxPredictions());
        assertEquals(0.1, captured.getMinProbability(), 1e-9);
        assertTrue(captured.isUseLlm());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GET /api/attribution/predict/{nodeId}
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void predictQuick_get_returnsPredictionResult() {
        when(attributionService.predict(any(PredictionQuery.class))).thenReturn(samplePredictionResult);

        ResponseEntity<PredictionResult> response = controller.predictQuick(
                "node-root", "What happens next?", 42L, 4, 15, true
        );

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("node-root", response.getBody().getSourceNodeId());
        assertEquals(3, response.getBody().getPredictions().size());
    }

    @Test
    void predictQuick_get_mapsQueryParameters() {
        when(attributionService.predict(any(PredictionQuery.class))).thenReturn(samplePredictionResult);

        ArgumentCaptor<PredictionQuery> captor = ArgumentCaptor.forClass(PredictionQuery.class);
        controller.predictQuick("node-root", "cascade?", 5L, 4, 12, false);
        verify(attributionService).predict(captor.capture());

        PredictionQuery captured = captor.getValue();
        assertEquals("node-root", captured.getSourceNodeId());
        assertEquals("cascade?", captured.getNaturalLanguageContext());
        assertEquals(5L, captured.getFactSheetId());
        assertEquals(4, captured.getMaxDepth());
        assertEquals(12, captured.getMaxPredictions());
        assertFalse(captured.isUseLlm());
    }

    @Test
    void predictQuick_get_defaultValues() {
        when(attributionService.predict(any(PredictionQuery.class))).thenReturn(samplePredictionResult);

        // Call with Spring @RequestParam defaults: maxDepth=3, maxPredictions=10, useLlm=true
        ArgumentCaptor<PredictionQuery> captor = ArgumentCaptor.forClass(PredictionQuery.class);
        controller.predictQuick("node-root", null, null, 3, 10, true);
        verify(attributionService).predict(captor.capture());

        PredictionQuery captured = captor.getValue();
        assertEquals("node-root", captured.getSourceNodeId());
        assertNull(captured.getNaturalLanguageContext());
        assertNull(captured.getFactSheetId());
        assertEquals(3, captured.getMaxDepth());
        assertEquals(10, captured.getMaxPredictions());
        assertTrue(captured.isUseLlm());
        // minProbability not set by quick endpoint — Lombok @Builder.Default gives 0.1
        assertEquals(0.1, captured.getMinProbability(), 1e-9);
    }

    @Test
    void predictQuick_get_predictionsHavePathAndEdgeTypes() {
        when(attributionService.predict(any(PredictionQuery.class))).thenReturn(samplePredictionResult);

        ResponseEntity<PredictionResult> response =
                controller.predictQuick("node-root", null, null, 3, 10, true);

        assertNotNull(response.getBody());
        List<PredictedEvent> predictions = response.getBody().getPredictions();

        // All predictions must have a path from source
        for (PredictedEvent pe : predictions) {
            assertFalse(pe.getPathFromSource().isEmpty(),
                    "Prediction " + pe.getNodeId() + " should have a path from source");
            assertEquals(pe.getHopsFromSource(), pe.getPathEdgeTypes().size(),
                    "Path edge types count should match hops from source for " + pe.getNodeId());
        }

        // Verify the multi-hop prediction has correct path
        PredictedEvent p2 = predictions.get(1);
        assertEquals(List.of("node-root", "node-downstream-1", "node-downstream-2"), p2.getPathFromSource());
        assertEquals(List.of(CausalEdgeType.TRIGGERS, CausalEdgeType.CAUSES), p2.getPathEdgeTypes());
    }

    @Test
    void predictQuick_get_synthesizedForecastPresent() {
        when(attributionService.predict(any(PredictionQuery.class))).thenReturn(samplePredictionResult);

        ResponseEntity<PredictionResult> response =
                controller.predictQuick("node-root", null, null, 3, 10, true);

        assertNotNull(response.getBody());
        assertNotNull(response.getBody().getSynthesizedForecast());
        assertFalse(response.getBody().getSynthesizedForecast().isBlank());
        assertTrue(response.getBody().isLlmUsed());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Cross-cutting: service interaction
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void explain_post_invokesServiceExactlyOnce() {
        when(attributionService.explain(any(AttributionQuery.class))).thenReturn(sampleAttributionResult);

        controller.explain(new EventAttributionController.AttributionQueryRequest(
                "node-target", null, null, null, null, null, null, null, null, null
        ));

        verify(attributionService, times(1)).explain(any(AttributionQuery.class));
        verifyNoMoreInteractions(attributionService);
    }

    @Test
    void predict_post_invokesServiceExactlyOnce() {
        when(attributionService.predict(any(PredictionQuery.class))).thenReturn(samplePredictionResult);

        controller.predict(new EventAttributionController.PredictionQueryRequest(
                "node-root", null, null, null, null, null, null
        ));

        verify(attributionService, times(1)).predict(any(PredictionQuery.class));
        verifyNoMoreInteractions(attributionService);
    }

    @Test
    void explainQuick_get_invokesServiceExactlyOnce() {
        when(attributionService.explain(any(AttributionQuery.class))).thenReturn(sampleAttributionResult);

        controller.explainQuick("node-target", null, null, 5, 5, true, false);

        verify(attributionService, times(1)).explain(any(AttributionQuery.class));
        verifyNoMoreInteractions(attributionService);
    }

    @Test
    void predictQuick_get_invokesServiceExactlyOnce() {
        when(attributionService.predict(any(PredictionQuery.class))).thenReturn(samplePredictionResult);

        controller.predictQuick("node-root", null, null, 3, 10, true);

        verify(attributionService, times(1)).predict(any(PredictionQuery.class));
        verifyNoMoreInteractions(attributionService);
    }
}
