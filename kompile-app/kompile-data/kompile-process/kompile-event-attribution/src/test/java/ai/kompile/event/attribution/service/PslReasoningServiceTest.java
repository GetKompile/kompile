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

import ai.kompile.event.attribution.domain.PslInferenceResult;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link PslReasoningService}, exercising the KG → PSL program → HL-MRF
 * inference path over a mocked knowledge graph (a simple a→b→c causal chain).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PslReasoningServiceTest {

    @Mock
    private KnowledgeGraphService graphService;

    private PslReasoningService service;

    @BeforeEach
    void setUp() {
        service = new PslReasoningService(graphService);
    }

    private GraphNode node(String id, String title) {
        return GraphNode.builder().nodeId(id).title(title).confidence(0.5).build();
    }

    private GraphEdge edge(String id, GraphNode source, GraphNode target, double weight) {
        return GraphEdge.builder().edgeId(id).sourceNode(source).targetNode(target)
                .weight(weight).confidence(0.9).build();
    }

    /** Wire a→b→c with strong directed edges. */
    private void wireChain() {
        GraphNode a = node("a", "Alpha");
        GraphNode b = node("b", "Beta");
        GraphNode c = node("c", "Gamma");
        GraphEdge ab = edge("ab", a, b, 0.9);
        GraphEdge bc = edge("bc", b, c, 0.9);

        when(graphService.getNode("a")).thenReturn(Optional.of(a));
        when(graphService.getNode("b")).thenReturn(Optional.of(b));
        when(graphService.getNode("c")).thenReturn(Optional.of(c));
        when(graphService.getEdgesForNode("a")).thenReturn(List.of(ab));
        when(graphService.getEdgesForNode("b")).thenReturn(List.of(ab, bc));
        when(graphService.getEdgesForNode("c")).thenReturn(List.of(bc));
    }

    @Test
    void infer_withEvidence_propagatesAndPopulatesResult() {
        wireChain();

        PslInferenceResult result = service.infer(List.of("a"), Map.of("a", 1.0), 3, 100);

        // Evidence is reported as observed, fixed at its value.
        assertEquals(1.0, result.getObservedTruth().get("State(a)"), 1e-9);

        // b and c are inferred soft-truths in [0, 1].
        Double sb = result.getInferredTruth().get("State(b)");
        Double sc = result.getInferredTruth().get("State(c)");
        assertNotNull(sb);
        assertNotNull(sc);
        assertTrue(sb >= 0.0 && sb <= 1.0, "State(b) in range: " + sb);

        // Propagation from the active cause raises State(b) above its structural prior.
        Double priorB = result.getPriors().get("State(b)");
        assertNotNull(priorB);
        assertTrue(sb > priorB + 0.02, "propagation should raise State(b) (" + sb + ") above prior (" + priorB + ")");

        // Mappings and stats.
        assertEquals("a", result.getAtomToNodeId().get("State(a)"));
        assertEquals("Beta", result.getAtomToTitle().get("State(b)"));
        assertTrue((Boolean) result.getStats().get("converged"));
        assertTrue(((Number) result.getStats().get("groundRules")).intValue() > 0);
    }

    @Test
    void infer_emptyGraph_returnsEmptyResultWithoutError() {
        when(graphService.getNode(anyString())).thenReturn(Optional.empty());

        PslInferenceResult result = service.infer(List.of("missing"), Map.of(), 3, 100);

        assertTrue(result.getInferredTruth().isEmpty());
        assertTrue(result.getObservedTruth().isEmpty());
    }

    @Test
    void inferWithRules_customRuleDrivesInference() {
        wireChain();

        PslInferenceResult result = service.inferWithRules(
                List.of("a"),
                List.of("5: State(X) & Link(X, Y) -> State(Y) ^2"),
                Map.of("a", 1.0), 3, 100);

        Double sb = result.getInferredTruth().get("State(b)");
        assertNotNull(sb);
        assertTrue(sb > 0.5, "custom propagation rule should activate State(b): " + sb);
        assertTrue(result.getRules().stream().anyMatch(r -> r.contains("State(X)")));
    }

    @Test
    void inferWithRules_invalidRuleThrows() {
        wireChain();
        assertThrows(IllegalArgumentException.class, () ->
                service.inferWithRules(List.of("a"), List.of("Bad("), Map.of(), 3, 100));
    }

    @Test
    void programStatistics_reportsCounts() {
        wireChain();

        Map<String, Object> stats = service.programStatistics(List.of("a"), 3, 100);

        assertEquals(3, ((Number) stats.get("nodes")).intValue());
        assertEquals(4, ((Number) stats.get("rules")).intValue()); // propagation + abduction + 2 prior
        assertTrue(((Number) stats.get("groundRules")).intValue() > 0);
    }
}
