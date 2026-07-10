/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.tool.graph;

import ai.kompile.graph.reasoning.query.GraphQueryEngine;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.unified.GraphReasoningQueryService;
import ai.kompile.knowledgegraph.unified.UnifiedGraphBridge;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link GraphReasoningQueryTool}.
 *
 * <p>The tool is now a thin delegate to {@link GraphReasoningQueryService}. Tests verify that
 * (a) delegation is correct and (b) the service behaviour is observable through the tool,
 * matching the original test contract so callers can rely on stable tool semantics.</p>
 */
class GraphReasoningQueryToolTest {

    // ── Construction helpers ──────────────────────────────────────────────────

    /**
     * Build a tool that uses a real {@link GraphReasoningQueryService} backed by a mocked bridge.
     */
    private static GraphReasoningQueryTool toolWithBridge(UnifiedGraphBridge bridge) {
        return new GraphReasoningQueryTool(new GraphReasoningQueryService(bridge));
    }

    // ── Tool delegates to service ─────────────────────────────────────────────

    @Test
    void delegatesToServiceNotDirectlyToBridge() {
        GraphReasoningQueryService service = mock(GraphReasoningQueryService.class);
        when(service.execute(any())).thenReturn(
                GraphReasoningQueryService.invalid("stub"));

        GraphReasoningQueryTool tool = new GraphReasoningQueryTool(service);
        tool.query(new GraphReasoningQueryTool.QueryInput(
                null, "overview", null, null, null, null, null, null, null, null, null));

        verify(service, times(1)).execute(any());
    }

    @Test
    void nullInputReturnsInvalidWithoutCallingService() {
        GraphReasoningQueryService service = mock(GraphReasoningQueryService.class);
        GraphReasoningQueryTool tool = new GraphReasoningQueryTool(service);

        GraphQueryEngine.Result result = tool.query(null);

        assertEquals(GraphQueryEngine.Status.INVALID, result.status());
        verifyNoInteractions(service);
    }

    // ── Original contract (now exercised through delegation) ──────────────────

    @Test
    void capabilitiesIsSelfDescribingAndDoesNotLoadGraph() {
        UnifiedGraphBridge bridge = mock(UnifiedGraphBridge.class);
        GraphReasoningQueryTool tool = toolWithBridge(bridge);

        GraphQueryEngine.Result result = tool.query(new GraphReasoningQueryTool.QueryInput(
                null, "capabilities", null, null, null, null, null, null, null, null, null));

        assertEquals(GraphQueryEngine.Status.OK, result.status());
        assertEquals(21, result.capabilities().size());
        assertNotNull(result.trace());
        verifyNoInteractions(bridge);
    }

    @Test
    void executesReadablePathQueryOverLiveUnifiedGraph() throws Exception {
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity("person", "PERSON", "Jordan Lee");
        graph.addEntity("email", "EMAIL", "Close package email");
        graph.addEntity("file", "DOCUMENT", "regional-close.xlsx");
        graph.addRelation("r1", "person", "email", "SENT", 0.95);
        graph.addRelation("r2", "email", "file", "HAS_ATTACHMENT", 0.9);

        UnifiedGraphBridge bridge = mock(UnifiedGraphBridge.class);
        when(bridge.export(42L)).thenReturn(graph);
        GraphReasoningQueryTool tool = toolWithBridge(bridge);

        GraphQueryEngine.Result result = tool.query(new GraphReasoningQueryTool.QueryInput(
                42L, "path", "Jordan Lee", "regional-close.xlsx", "forward", List.of(),
                4, null, null, null, null));

        assertEquals(GraphQueryEngine.Status.OK, result.status());
        assertEquals(2, result.resolutions().size());
        assertNotNull(result.trace());
        assertEquals(List.of("Jordan Lee", "Close package email", "regional-close.xlsx"),
                result.path().stream().map(step -> step.entity().label()).toList());
        assertEquals(List.of("SENT", "HAS_ATTACHMENT"),
                result.relations().stream().map(GraphQueryEngine.RelationView::type).toList());
        String json = new ObjectMapper().writeValueAsString(result);
        assertTrue(json.contains("\"resolutions\""));
        assertTrue(json.contains("\"trace\":{"));
        assertTrue(json.contains("\"conclusion\""));
        assertTrue(json.contains("\"premises\""));
    }

    @Test
    void verifiesTypedClaimsThroughTheSingleTool() {
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity("person", "PERSON", "Jordan Lee");
        graph.addEntity("email", "EMAIL", "Close package email");
        graph.addRelation("r1", "person", "email", "SENT", 0.95);

        UnifiedGraphBridge bridge = mock(UnifiedGraphBridge.class);
        when(bridge.export(42L)).thenReturn(graph);
        GraphReasoningQueryTool tool = toolWithBridge(bridge);

        GraphQueryEngine.Result result = tool.query(new GraphReasoningQueryTool.QueryInput(
                42L, "verify", "Jordan Lee", "Close package email", null, List.of("sent"),
                null, null, null, null, null));

        assertEquals(GraphQueryEngine.Status.SUPPORTED, result.status());
        assertEquals("r1", result.relations().get(0).id());
        assertEquals(2, result.resolutions().size());
        assertNotNull(result.trace());
    }

    @Test
    void rejectsInvalidOptionsBeforeLoadingTheGraph() {
        UnifiedGraphBridge bridge = mock(UnifiedGraphBridge.class);
        GraphReasoningQueryTool tool = toolWithBridge(bridge);

        GraphQueryEngine.Result result = tool.query(new GraphReasoningQueryTool.QueryInput(
                42L, "path", "a", "b", "sideways", null,
                null, null, null, "unknown", null));

        assertEquals(GraphQueryEngine.Status.INVALID, result.status());
        assertTrue(result.summary().contains("direction"));
        assertNotNull(result.trace());
        verifyNoInteractions(bridge);
    }

    @Test
    void rejectsUnknownOperationsWithRecoveryGuidance() {
        GraphReasoningQueryTool tool = toolWithBridge(mock(UnifiedGraphBridge.class));

        GraphQueryEngine.Result result = tool.query(new GraphReasoningQueryTool.QueryInput(
                null, "guess", null, null, null, null, null, null, null, null, null));

        assertEquals(GraphQueryEngine.Status.INVALID, result.status());
        assertFalse(result.guidance().isEmpty());
        assertNotNull(result.trace());
    }
}
