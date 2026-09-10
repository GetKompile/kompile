/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.knowledgegraph.matrix.service;

import ai.kompile.knowledgegraph.generation.GraphGenerationContext;
import ai.kompile.knowledgegraph.matrix.model.MatrixGraphNode;
import ai.kompile.knowledgegraph.matrix.store.InMemoryMatrixGraphStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatrixKnowledgeGraphGenerationTest {

    @Test
    void serviceLifecycleSwitchesFactSheetReadsAtActivation() {
        InMemoryMatrixGraphStore store = new InMemoryMatrixGraphStore();
        MatrixKnowledgeGraphService service = new MatrixKnowledgeGraphService(store, new ObjectMapper());
        String logical = MatrixKnowledgeGraphService.graphIdForFactSheet(42L);
        store.createGraph(logical, 42L);
        store.addNode(logical, node("old", 42L));

        var generation = service.beginFactSheetGeneration(42L, "replacement");
        try (var ignored = GraphGenerationContext.open(generation)) {
            store.addNode(MatrixKnowledgeGraphService.graphIdForFactSheet(42L), node("new", 42L));
            assertTrue(service.getNode("new").isPresent());
            assertFalse(service.getNode("old").isPresent());
            assertTrue(service.getAllNodes(10).stream().anyMatch(node -> "new".equals(node.getNodeId())));
            assertFalse(service.getAllNodes(10).stream().anyMatch(node -> "old".equals(node.getNodeId())));
            assertThrows(IllegalArgumentException.class,
                    () -> service.updateNode("old", "changed", null, null));
        }
        assertTrue(service.getNodesInFactSheet(42L).stream()
                .anyMatch(node -> "old".equals(node.getNodeId())));

        service.activateFactSheetGeneration(generation);

        assertTrue(service.getNodesInFactSheet(42L).stream()
                .anyMatch(node -> "new".equals(node.getNodeId())));
    }

    @Test
    void serviceRejectsGenerationReferenceForDifferentLogicalFactSheet() {
        InMemoryMatrixGraphStore store = new InMemoryMatrixGraphStore();
        MatrixKnowledgeGraphService service = new MatrixKnowledgeGraphService(store, new ObjectMapper());
        var generation = store.beginGeneration(42L, "other_graph", "replacement");

        assertThrows(IllegalArgumentException.class,
                () -> service.validateFactSheetGeneration(generation));
    }

    private static MatrixGraphNode node(String id, Long factSheetId) {
        return MatrixGraphNode.builder().nodeId(id).nodeType("ENTITY")
                .title(id).factSheetId(factSheetId).build();
    }
}
