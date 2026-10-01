/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.knowledgegraph.unified;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.quantitative.ScenarioResult;
import ai.kompile.graph.reasoning.query.GraphQueryEngine;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
        assertTrue(result.capabilities().stream()
                .anyMatch(capability -> "CALCULATE".equals(capability.intent())));
        assertEquals(GraphReasoningQueryService.queryRequestOperations(),
                result.capabilities().stream()
                        .map(GraphQueryEngine.Capability::intent)
                        .toList());
        assertTrue(GraphReasoningQueryService.queryRequestOperationGuide()
                .contains("VERIFY(entityId,targetId,relationTypes[0])"));
        assertTrue(GraphReasoningQueryService.queryRequestOperationGuide()
                .contains("SCENARIO(quantitative.target,quantitative.interventions)"));
        assertFalse(GraphReasoningQueryService.nonQuantitativeOperations().contains("CALCULATE"));
        assertFalse(GraphReasoningQueryService.nonQuantitativeOperationGuide().contains("quantitative"));
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

        GraphQueryEngine.Result missingTarget = service.execute(
                new UnifiedGraph(),
                new GraphReasoningQueryService.QueryRequest(
                        null, "CALCULATE", null, null, null, null,
                        null, null, null, null, null, null));
        assertEquals(GraphQueryEngine.Status.INVALID, missingTarget.status());
        assertTrue(missingTarget.summary().startsWith(
                "CALCULATE requires quantitative.target. Example: quantitative={"), missingTarget.summary());

        GraphQueryEngine.Result misplacedSpec = service.execute(
                new UnifiedGraph(),
                new GraphReasoningQueryService.QueryRequest(
                        null, "SEARCH", null, null, null, null,
                        null, null, null, null, "Orchid", null, Map.of("target", "Sales")));
        assertEquals(GraphQueryEngine.Status.INVALID, misplacedSpec.status());
        assertTrue(misplacedSpec.summary().contains("quantitative only applies to"), misplacedSpec.summary());
    }

    @Test
    void quantitativeOperationsRunThroughTheSharedRequest() {
        GraphReasoningQueryService service = new GraphReasoningQueryService(null);

        GraphQueryEngine.Result calculated = service.execute(formulaGraph(), quantitative(
                "CALCULATE", Map.of("target", Map.of("entityId", "a3"))));
        assertEquals(GraphQueryEngine.Status.OK, calculated.status(), calculated.summary());
        assertEquals("Calculated a3 = 30.0.", calculated.summary());

        // Transports that cannot nest objects send the same spec as a JSON string.
        GraphQueryEngine.Result scenario = service.execute(formulaGraph(), quantitative("scenario",
                "{\"target\":{\"entityId\":\"a3\"},\"interventions\":[{\"target\":{\"entityId\":\"a1\"},"
                        + "\"operation\":\"add\",\"value\":5}]}"));
        assertEquals(GraphQueryEngine.Status.OK, scenario.status(), scenario.summary());
        ScenarioResult values = assertInstanceOf(ScenarioResult.class, scenario.data().get("scenario"));
        assertEquals(30.0, values.baselineValue(), 1.0e-9);
        assertEquals(35.0, values.scenarioValue(), 1.0e-9);

        GraphQueryEngine.Result unnamedOperation = service.execute(formulaGraph(), quantitative("SCENARIO",
                Map.of("target", Map.of("entityId", "a3"),
                        "interventions", List.of(Map.of("target", Map.of("entityId", "a1"), "value", 5)))));
        assertEquals(GraphQueryEngine.Status.INVALID, unnamedOperation.status());
        assertTrue(unnamedOperation.summary().contains("interventions[0].operation is required"),
                unnamedOperation.summary());

        // Without a supplied graph the whole fact sheet is exported, as for other unscoped operations.
        UnifiedGraphBridge bridge = mock(UnifiedGraphBridge.class);
        when(bridge.export(7L)).thenReturn(formulaGraph());
        GraphQueryEngine.Result persisted = new GraphReasoningQueryService(bridge).execute(
                new GraphReasoningQueryService.QueryRequest(
                        7L, "CALCULATE", null, null, null, null,
                        null, null, null, null, null, null, Map.of("target", Map.of("entityId", "a3"))));
        assertEquals("Calculated a3 = 30.0.", persisted.summary());
    }

    @Test
    void exactClaimUsesBoundedNeighborhoodInsteadOfFullExport() {
        UnifiedGraph bounded = new UnifiedGraph()
                .addEntity(GraphEntity.builder("a").type("PERSON").label("A").build())
                .addEntity(GraphEntity.builder("b").type("PERSON").label("B").build())
                .addRelation(GraphRelation.builder("a-b", "a", "b").type("KNOWS").build());
        UnifiedGraphBridge bridge = mock(UnifiedGraphBridge.class);
        when(bridge.resolveSeedIds(7L, "a")).thenReturn(List.of("a"));
        when(bridge.resolveSeedIds(7L, "b")).thenReturn(List.of("b"));
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
        when(bridge.resolveSeedIds(7L, "a")).thenReturn(List.of("a"));
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
        when(bridge.resolveSeedIds(7L, "missing")).thenReturn(List.of());
        when(bridge.exportNeighborhood(eq(7L), eq(List.of()), eq(List.of()),
                anyInt(), anyInt(), eq(GraphQueryEngine.Direction.BOTH), anyInt()))
                .thenReturn(new UnifiedGraph().meta("truncated", false));
        GraphReasoningQueryService service = new GraphReasoningQueryService(bridge);

        GraphQueryEngine.Result result = service.execute(new GraphReasoningQueryService.QueryRequest(
                7L, "DESCRIBE", "missing", null, null, null,
                null, null, null, null, null, null));

        assertEquals(GraphQueryEngine.Status.NOT_FOUND, result.status());
        verify(bridge, never()).export(7L);
    }

    @Test
    void nameOnPersistedPathResolvesThroughBoundedLookupWithoutFullExport() {
        UnifiedGraph bounded = new UnifiedGraph()
                .addEntity(GraphEntity.builder("org-5").type("ORGANIZATION").label("Jordan Lee Consulting").build())
                .addEntity(GraphEntity.builder("person-17").type("PERSON").label("Jordan Lee").build())
                .addEntity(GraphEntity.builder("email-9").type("EMAIL").label("Close package email").build())
                .addRelation(GraphRelation.builder("r1", "person-17", "email-9").type("SENT").build())
                .meta("truncated", false);
        UnifiedGraphBridge bridge = mock(UnifiedGraphBridge.class);
        // Search order is not the answer: the engine ranks every materialized candidate.
        when(bridge.resolveSeedIds(7L, "Jordan Lee")).thenReturn(List.of("org-5", "person-17"));
        when(bridge.resolveSeedIds(7L, "Close package email")).thenReturn(List.of("email-9"));
        when(bridge.exportNeighborhood(eq(7L), eq(List.of("org-5", "person-17", "email-9")),
                eq(List.of("org-5", "person-17")), anyInt(), anyInt(),
                eq(GraphQueryEngine.Direction.OUTGOING), anyInt()))
                .thenReturn(bounded);
        GraphReasoningQueryService service = new GraphReasoningQueryService(bridge);

        GraphQueryEngine.Result result = service.execute(new GraphReasoningQueryService.QueryRequest(
                7L, "VERIFY", "Jordan Lee", "Close package email", null, List.of("SENT"),
                null, null, null, null, null, null));

        assertEquals(GraphQueryEngine.Status.SUPPORTED, result.status(), result.summary());
        assertEquals("r1", result.relations().get(0).id());
        assertEquals(2, result.resolutions().size());
        GraphQueryEngine.ResolutionView source = result.resolutions().get(0);
        assertEquals("person-17", source.resolvedId());
        assertEquals(List.of("person-17", "org-5"), source.candidates().stream()
                .map(GraphQueryEngine.EntityView::id)
                .toList());
        assertEquals("email-9", result.resolutions().get(1).resolvedId());
        assertTrue(result.trace().steps().stream().anyMatch(step ->
                "automatic_entity_resolution".equals(step.operation())
                        && "person-17".equals(step.meta().get("resolvedId"))
                        && "person-17,org-5".equals(step.meta().get("candidateIds"))));
        verify(bridge).resolveSeedIds(7L, "Jordan Lee");
        verify(bridge).resolveSeedIds(7L, "Close package email");
        verify(bridge, never()).export(7L);
    }

    private static GraphReasoningQueryService.QueryRequest quantitative(String operation, Object spec) {
        return new GraphReasoningQueryService.QueryRequest(
                null, operation, null, null, null, null,
                null, null, null, null, null, null, spec);
    }

    private static UnifiedGraph formulaGraph() {
        return new UnifiedGraph()
                .addEntity(cell("a1", "Sheet1!A1", "Input A", 10.0))
                .addEntity(cell("a2", "Sheet1!A2", "Input B", 20.0))
                .addEntity(GraphEntity.builder("a3")
                        .type("FORMULA_CELL")
                        .label("Total")
                        .attribute("cell_reference", "Sheet1!A3")
                        .attribute("formula", "SUM(Sheet1!A1:Sheet1!A2)")
                        .attribute("displayValue", "30")
                        .attribute("validated", true)
                        .build())
                .addRelation(GraphRelation.builder("d1", "a3", "a1").type("DEPENDS_ON").build())
                .addRelation(GraphRelation.builder("d2", "a3", "a2").type("DEPENDS_ON").build());
    }

    private static GraphEntity cell(String id, String reference, String label, double value) {
        return GraphEntity.builder(id)
                .type("CELL").label(label)
                .attribute("cell_reference", reference)
                .attribute("value", value)
                .build();
    }
}
