/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.knowledgegraph.unified;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.query.GraphQueryEngine;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphReasoningQueryServiceInMemoryTest {

    @Test
    void executesTheProductionQueryContractAgainstIncrementalGraphState() {
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity(GraphEntity.builder("orchid")
                .type("PROJECT")
                .label("Orchid")
                .confidence(0.91)
                .build());

        GraphReasoningQueryService service = new GraphReasoningQueryService(null);
        GraphQueryEngine.Result result = service.execute(
                graph,
                new GraphReasoningQueryService.QueryRequest(
                        null, "SEARCH", null, null, null, null,
                        null, 5, null, null, "Orchid", null));

        assertEquals(GraphQueryEngine.Status.OK, result.status());
        assertTrue(result.entities().stream().anyMatch(entity -> "orchid".equals(entity.id())));
    }

    @Test
    void questionOnlyRequestUsesTheSameSearchDefaultAsLocalMcp() {
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity(GraphEntity.builder("orchid")
                .type("PROJECT")
                .label("Orchid")
                .confidence(0.91)
                .build());

        GraphReasoningQueryService service = new GraphReasoningQueryService(null);
        GraphQueryEngine.Result result = service.execute(
                graph,
                new GraphReasoningQueryService.QueryRequest(
                        null, null, null, null, null, null,
                        null, 5, null, null, null, "Find Orchid"));

        assertEquals(GraphQueryEngine.Status.OK, result.status());
        assertEquals(GraphQueryEngine.Intent.SEARCH, result.intent());
        assertTrue(result.entities().stream().anyMatch(entity -> "orchid".equals(entity.id())));
    }

    @Test
    void queryTextWinsWhenBothTextAliasesArePresent() {
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity(GraphEntity.builder("orchid").type("PROJECT").label("Orchid").build());
        graph.addEntity(GraphEntity.builder("violet").type("PROJECT").label("Violet").build());
        GraphReasoningQueryService service = new GraphReasoningQueryService(null);

        GraphQueryEngine.Result result = service.execute(
                graph,
                new GraphReasoningQueryService.QueryRequest(
                        null, null, null, null, null, null,
                        null, 5, null, null, "Orchid", "Violet"));

        assertTrue(result.entities().stream().anyMatch(entity -> "orchid".equals(entity.id())));
        assertTrue(result.entities().stream().noneMatch(entity -> "violet".equals(entity.id())));
    }

    @Test
    void emptyRequestUsesCapabilitiesDefaultLikeLocalMcp() {
        GraphReasoningQueryService service = new GraphReasoningQueryService(null);

        GraphQueryEngine.Result result = service.execute(
                null,
                new GraphReasoningQueryService.QueryRequest(
                        null, null, null, null, null, null,
                        null, null, null, null, null, null));

        assertEquals(GraphQueryEngine.Status.OK, result.status());
        assertEquals(GraphQueryEngine.Intent.CAPABILITIES, result.intent());
        assertFalse(result.capabilities().isEmpty());
    }

    @Test
    void freshGraphStillExposesGraphReasoningCapabilities() {
        GraphReasoningQueryService service = new GraphReasoningQueryService(null);

        GraphQueryEngine.Result result = service.execute(
                null,
                new GraphReasoningQueryService.QueryRequest(
                        null, "CAPABILITIES", null, null, null, null,
                        null, null, null, null, null, null));

        assertEquals(GraphQueryEngine.Status.OK, result.status());
        assertTrue(result.capabilities().stream()
                .anyMatch(capability -> "VERIFY".equals(capability.intent())));
        assertTrue(result.capabilities().stream()
                .anyMatch(capability -> "SIMILAR".equals(capability.intent())));
        assertTrue(result.capabilities().stream()
                .anyMatch(capability -> "RANK".equals(capability.intent())));
        assertFalse(result.capabilities().stream()
                .anyMatch(capability -> "CALCULATE".equals(capability.intent())));
        assertEquals(GraphReasoningQueryService.queryRequestOperations(),
                result.capabilities().stream()
                        .map(GraphQueryEngine.Capability::intent)
                        .toList());
        assertTrue(GraphReasoningQueryService.queryRequestOperationGuide()
                .contains("VERIFY(entityId,targetId,relationTypes[0])"));
    }

    @Test
    void incompleteCommandsFailFromTheSharedCapabilityContract() {
        GraphReasoningQueryService service = new GraphReasoningQueryService(null);

        GraphQueryEngine.Result missingSearchText = service.execute(
                new UnifiedGraph(),
                new GraphReasoningQueryService.QueryRequest(
                        null, "SEARCH", null, null, null, null,
                        null, null, null, null, null, null));
        assertEquals(GraphQueryEngine.Status.INVALID, missingSearchText.status());
        assertTrue(missingSearchText.summary().contains("SEARCH requires queryText"));

        GraphQueryEngine.Result malformedClaim = service.execute(
                new UnifiedGraph(),
                new GraphReasoningQueryService.QueryRequest(
                        null, "VERIFY", "a", "b", null,
                        List.of("OWNS", "MANAGES"),
                        null, null, null, null, null, null));
        assertEquals(GraphQueryEngine.Status.INVALID, malformedClaim.status());
        assertTrue(malformedClaim.summary().contains("exactly one relationTypes value"));

        GraphQueryEngine.Result unsupportedQuantitative = service.execute(
                new UnifiedGraph(),
                new GraphReasoningQueryService.QueryRequest(
                        null, "CALCULATE", null, null, null, null,
                        null, null, null, null, null, null));
        assertEquals(GraphQueryEngine.Status.INVALID, unsupportedQuantitative.status());
        assertTrue(unsupportedQuantitative.summary().contains(
                "not supported by this graph query request contract"));
    }

    @Test
    void exactClaimUsesBoundedNeighborhoodInsteadOfFullExport() {
        UnifiedGraph bounded = new UnifiedGraph()
                .addEntity(GraphEntity.builder("a").type("PERSON").label("A").build())
                .addEntity(GraphEntity.builder("b").type("PERSON").label("B").build())
                .addRelation(GraphRelation.builder("a-b", "a", "b").type("KNOWS").build());
        UnifiedGraphBridge bridge = mock(UnifiedGraphBridge.class);
        when(bridge.exportNeighborhood(eq(7L), eq(List.of("a", "b")), eq(List.of("a")),
                anyInt(), anyInt(), eq(GraphQueryEngine.Direction.OUTGOING), anyInt()))
                .thenReturn(bounded);
        GraphReasoningQueryService service = new GraphReasoningQueryService(bridge);

        GraphQueryEngine.Result result = service.execute(new GraphReasoningQueryService.QueryRequest(
                7L, "VERIFY", "a", "b", null, List.of("KNOWS"),
                null, null, null, null, null, null));

        assertEquals(GraphQueryEngine.Status.SUPPORTED, result.status());
        verify(bridge, never()).export(7L);
    }

    @Test
    void truncatedBoundedNeighborhoodReturnsPartialInsteadOfConclusiveAbsence() {
        UnifiedGraph bounded = new UnifiedGraph()
                .addEntity(GraphEntity.builder("a").type("PERSON").label("A").build())
                .meta("truncated", true)
                .meta("materializedNodes", 1)
                .meta("materializedEdges", 0)
                .meta("maxNodes", 1)
                .meta("maxEdges", 1);
        UnifiedGraphBridge bridge = mock(UnifiedGraphBridge.class);
        when(bridge.exportNeighborhood(eq(7L), eq(List.of("a")), eq(List.of("a")),
                anyInt(), anyInt(), eq(GraphQueryEngine.Direction.BOTH), anyInt()))
                .thenReturn(bounded);
        GraphReasoningQueryService service = new GraphReasoningQueryService(bridge);

        GraphQueryEngine.Result result = service.execute(new GraphReasoningQueryService.QueryRequest(
                7L, "NEIGHBORS", "a", null, "BOTH", null,
                null, 20, null, null, null, null));

        assertEquals(GraphQueryEngine.Status.PARTIAL, result.status());
        assertEquals(true, result.data().get("boundedGraphTruncated"));
        verify(bridge, never()).export(7L);
    }

    @Test
    void unresolvedEntityScopedIdNeverFallsBackToFullExport() {
        UnifiedGraphBridge bridge = mock(UnifiedGraphBridge.class);
        when(bridge.exportNeighborhood(eq(7L), eq(List.of("missing")), eq(List.of("missing")),
                anyInt(), anyInt(), eq(GraphQueryEngine.Direction.BOTH), anyInt()))
                .thenReturn(new UnifiedGraph().meta("truncated", false));
        GraphReasoningQueryService service = new GraphReasoningQueryService(bridge);

        GraphQueryEngine.Result result = service.execute(new GraphReasoningQueryService.QueryRequest(
                7L, "DESCRIBE", "missing", null, null, null,
                null, null, null, null, null, null));

        assertEquals(GraphQueryEngine.Status.NOT_FOUND, result.status());
        verify(bridge, never()).export(7L);
    }
}
