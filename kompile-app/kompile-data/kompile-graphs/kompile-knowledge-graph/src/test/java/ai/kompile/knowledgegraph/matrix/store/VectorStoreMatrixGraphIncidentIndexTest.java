/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.knowledgegraph.matrix.store;

import ai.kompile.core.embeddings.VectorStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VectorStoreMatrixGraphIncidentIndexTest {

    @Test
    void canonicalIncidentLookupUsesExactEndpointMetadataIndexWithoutFullScan() {
        VectorStore vectorStore = mock(VectorStore.class);
        VectorStoreMatrixGraphStore store =
                new VectorStoreMatrixGraphStore(vectorStore, new ObjectMapper());
        when(vectorStore.getVectorDocumentStrict("graph:g:meta")).thenReturn(Map.of(
                "id", "graph:g:meta",
                "metadata", Map.of("type", "graph_metadata", "graphId", "g",
                        "storageVersion", 3, "nodeCount", 3, "edgeCount", 2)));
        Map<String, Object> first = edge("g", "a", "b", "CALLS");
        Map<String, Object> second = edge("g", "a", "c", "CALLS");
        when(vectorStore.listVectorDocumentsByMetadata(anyMap(), eq(2))).thenAnswer(invocation -> {
            Map<String, String> filters = invocation.getArgument(0);
            return "a".equals(filters.get("sourceNodeId")) ? List.of(first, second) : List.of();
        });

        MatrixGraphStore.IncidentEdges result = store.scanIncidentEdges(
                "g", "a", MatrixGraphStore.EdgeDirection.OUTGOING, 1);

        assertEquals(1, result.edges().size());
        assertEquals("b", result.edges().get(0).targetNodeId());
        assertTrue(result.truncated());
        verify(vectorStore, never()).listVectorDocuments(anyInt(), anyInt());
    }

    @Test
    void interruptedStorageMarkerFailsClosedAcrossReadPaths() {
        VectorStore vectorStore = mock(VectorStore.class);
        VectorStoreMatrixGraphStore store =
                new VectorStoreMatrixGraphStore(vectorStore, new ObjectMapper());
        when(vectorStore.getVectorDocumentStrict("graph:g:meta")).thenReturn(Map.of(
                "id", "graph:g:meta",
                "metadata", Map.of("type", "graph_metadata", "graphId", "g",
                        "storageVersion", -1, "nodeCount", 3, "edgeCount", 2)));

        assertThrows(IllegalStateException.class, () -> store.getNode("g", "a"));
        assertThrows(IllegalStateException.class, () -> store.loadGraph("g"));
        assertThrows(IllegalStateException.class, () -> store.scanIncidentEdges(
                "g", "a", MatrixGraphStore.EdgeDirection.BOTH, 10));
    }

    private static Map<String, Object> edge(
            String graphId, String source, String target, String type) {
        return Map.of(
                "id", "edge-" + source + "-" + target,
                "metadata", Map.of(
                        "type", "graph_edge",
                        "graphId", graphId,
                        "sourceNodeId", source,
                        "targetNodeId", target,
                        "edgeType", type,
                        "weight", 1.0,
                        "bidirectional", false));
    }
}
