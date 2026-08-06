/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.knowledgegraph.unified;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.query.GraphQueryEngine;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
