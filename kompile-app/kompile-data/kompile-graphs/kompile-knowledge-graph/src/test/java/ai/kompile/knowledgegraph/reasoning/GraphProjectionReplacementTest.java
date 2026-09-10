/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.knowledgegraph.reasoning;

import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.FactStore;
import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GraphProjectionReplacementTest {

    @Test
    void replacementRetractsOnlyPriorGraphProjectionFacts() {
        long factSheetId = 42L;
        KnowledgeGraphService graph = mock(KnowledgeGraphService.class);
        KbGroundingService grounding = new KbGroundingService();
        FactStore facts = grounding.getState(factSheetId).factStore();
        facts.assertFact(Fact.soft("entity(old)", 0.7, "agent-user"));
        facts.assertFact(Fact.observed("entity(old)", "graph-projection"));
        GraphNode replacement = GraphNode.builder()
                .nodeId("new-node").externalId("new").nodeType(NodeLevel.ENTITY)
                .title("New").factSheetId(factSheetId).build();
        when(graph.getNodesInFactSheet(factSheetId)).thenReturn(List.of(replacement));
        when(graph.getEdgesInFactSheet(factSheetId)).thenReturn(List.of());

        new GraphToFactStoreProjector(graph, grounding).project(factSheetId);

        assertTrue(facts.factFor("entity(old)")
                .filter(fact -> "agent-user".equals(fact.sourceId()) && fact.value() == 0.7)
                .isPresent());
        assertTrue(facts.factFor("entity(new)").isPresent());
    }

    @Test
    void failedGraphScanLeavesPreviousProjectionIntact() {
        long factSheetId = 42L;
        KnowledgeGraphService graph = mock(KnowledgeGraphService.class);
        KbGroundingService grounding = new KbGroundingService();
        FactStore facts = grounding.getState(factSheetId).factStore();
        facts.assertFact(Fact.observed("entity(old)", "graph-projection"));
        when(graph.getNodesInFactSheet(factSheetId)).thenThrow(new IllegalStateException("store down"));

        assertThrows(IllegalStateException.class,
                () -> new GraphToFactStoreProjector(graph, grounding).project(factSheetId));

        assertTrue(facts.factFor("entity(old)").isPresent());
    }

    @Test
    void removedGraphOnlyFactPurgesItsStaleInferredEntry() {
        long factSheetId = 42L;
        KnowledgeGraphService graph = mock(KnowledgeGraphService.class);
        KbGroundingService grounding = new KbGroundingService();
        var state = grounding.getState(factSheetId);
        state.factStore().assertFact(Fact.observed("entity(old)", "graph-projection"));
        state.inferredFactStore().store(new InferredFact(
                "entity(old)", 1.0, 1.0, List.of(), List.of(), "run-old", 1L, Instant.now()));
        when(graph.getNodesInFactSheet(factSheetId)).thenReturn(List.of());
        when(graph.getEdgesInFactSheet(factSheetId)).thenReturn(List.of());

        new GraphToFactStoreProjector(graph, grounding).project(factSheetId);

        assertTrue(state.inferredFactStore().latest("entity(old)").isEmpty());
    }
}
