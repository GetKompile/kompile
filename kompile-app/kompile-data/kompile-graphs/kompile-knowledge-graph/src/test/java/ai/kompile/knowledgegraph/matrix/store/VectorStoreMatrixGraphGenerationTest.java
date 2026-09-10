/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.knowledgegraph.matrix.store;

import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.knowledgegraph.matrix.model.MatrixGraphNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VectorStoreMatrixGraphGenerationTest {

    @Test
    void pointerDocumentAtomicallySwitchesLogicalGraphAndSupportsRollback() {
        VectorStore vectors = mock(VectorStore.class);
        when(vectors.addStoredOnlyDocuments(anyList())).thenAnswer(invocation ->
                ((List<?>) invocation.getArgument(0)).size());
        when(vectors.listVectorDocuments(anyInt(), anyInt())).thenReturn(List.of());
        when(vectors.delete(anyList())).thenReturn(true);
        when(vectors.flushAndCommit()).thenReturn(true);
        VectorStoreMatrixGraphStore store = new VectorStoreMatrixGraphStore(vectors, new ObjectMapper());
        String logical = "factsheet_42";
        store.createGraph(logical, 42L);
        store.addNode(logical, node("old"));

        var generation = store.beginGeneration(42L, logical, "g1");
        store.addNode(generation.physicalGraphId(), node("new"));
        assertTrue(store.getNode(logical, "old").isPresent());
        assertTrue(store.getNode(logical, "new").isEmpty());

        var activated = store.activateGeneration(generation);
        assertTrue(store.getNode(logical, "new").isPresent());
        assertEquals(List.of(logical), store.listGraphs());

        store.rollbackGeneration(42L, logical, activated.revision());
        assertTrue(store.getNode(logical, "old").isPresent());

        var second = store.beginGeneration(42L, logical, "g2");
        store.addNode(second.physicalGraphId(), node("newer"));
        store.activateGeneration(second);
        assertTrue(store.getNode(logical, "newer").isPresent());
        assertTrue(store.getNode(logical, "old").isEmpty());
    }

    @Test
    void restartListingDiscoversPointerAndHidesPhysicalGenerations() {
        VectorStore vectors = mock(VectorStore.class);
        when(vectors.listVectorDocuments(0, 2_000)).thenReturn(List.of(
                Map.of("id", "graph:pointer:factsheet_42", "metadata", Map.of(
                        "type", "graph_generation_pointer",
                        "activePhysicalGraphId", "factsheet_42~gen~g1",
                        "previousPhysicalGraphId", "factsheet_42",
                        "revision", 1L)),
                Map.of("id", "graph:factsheet_42~gen~g1:meta", "metadata", Map.of(
                        "type", "graph_metadata")),
                Map.of("id", "graph:factsheet_42:meta", "metadata", Map.of(
                        "type", "graph_metadata"))));
        VectorStoreMatrixGraphStore restarted =
                new VectorStoreMatrixGraphStore(vectors, new ObjectMapper());

        assertEquals(List.of("factsheet_42"), restarted.listGraphs());
    }

    @Test
    void restartDeletionRemovesEveryPhysicalGenerationButNotPrefixCollisions() {
        VectorStore vectors = mock(VectorStore.class);
        when(vectors.getVectorDocumentStrict("graph:pointer:factsheet_4")).thenReturn(Map.of(
                "metadata", Map.of(
                        "activePhysicalGraphId", "factsheet_4~gen~g1",
                        "previousPhysicalGraphId", "factsheet_4",
                        "revision", 1L)));
        when(vectors.listVectorDocuments(0, 2_000)).thenReturn(List.of(
                Map.of("id", "graph:factsheet_4:meta"),
                Map.of("id", "graph:factsheet_4~gen~g1:meta"),
                Map.of("id", "graph:factsheet_4~gen~orphan:node:n1"),
                Map.of("id", "graph:factsheet_42:meta")));
        when(vectors.delete(anyList())).thenReturn(true);
        when(vectors.flushAndCommit()).thenReturn(true);
        VectorStoreMatrixGraphStore restarted =
                new VectorStoreMatrixGraphStore(vectors, new ObjectMapper());

        assertTrue(restarted.deleteGraph("factsheet_4"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> deleted = ArgumentCaptor.forClass(List.class);
        verify(vectors).delete(deleted.capture());
        assertTrue(deleted.getValue().contains("graph:factsheet_4:meta"));
        assertTrue(deleted.getValue().contains("graph:factsheet_4~gen~g1:meta"));
        assertTrue(deleted.getValue().contains("graph:factsheet_4~gen~orphan:node:n1"));
        assertTrue(deleted.getValue().contains("graph:pointer:factsheet_4"));
        assertFalse(deleted.getValue().contains("graph:factsheet_42:meta"));
    }

    @Test
    void strictGenerationFlushRejectsShortEdgePersistence() {
        VectorStore vectors = mock(VectorStore.class);
        AtomicBoolean failEdges = new AtomicBoolean();
        when(vectors.addStoredOnlyDocuments(anyList())).thenAnswer(invocation -> {
            List<?> documents = invocation.getArgument(0);
            boolean edgeBatch = documents.stream()
                    .filter(Document.class::isInstance)
                    .map(Document.class::cast)
                    .anyMatch(document -> "graph_edge".equals(document.getMetadata().get("type")));
            return failEdges.get() && edgeBatch ? Math.max(0, documents.size() - 1) : documents.size();
        });
        when(vectors.flushAndCommit()).thenReturn(true);
        when(vectors.listVectorDocuments(anyInt(), anyInt())).thenReturn(List.of());
        when(vectors.delete(anyList())).thenReturn(true);
        VectorStoreMatrixGraphStore store = new VectorStoreMatrixGraphStore(vectors, new ObjectMapper());
        var generation = store.beginGeneration(42L, "factsheet_42", "g1");
        store.addNode(generation.physicalGraphId(), node("a"));
        store.addNode(generation.physicalGraphId(), node("b"));
        store.addEdge(generation.physicalGraphId(), "a", "b", 1.0, "RELATED", false);
        failEdges.set(true);

        assertThrows(IllegalStateException.class, () -> store.flushGeneration(generation));
    }

    private static MatrixGraphNode node(String id) {
        return MatrixGraphNode.builder().nodeId(id).nodeType("ENTITY").title(id).build();
    }
}
