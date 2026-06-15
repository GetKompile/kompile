/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.matrix.store;

import ai.kompile.core.embeddings.ScoredDocument;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.knowledgegraph.matrix.model.AdjacencyMatrixGraph;
import ai.kompile.knowledgegraph.matrix.model.MatrixGraphNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link VectorStoreMatrixGraphStore}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VectorStoreMatrixGraphStoreTest {

    @Mock
    private VectorStore vectorStore;

    @Mock
    private INDArray queryEmbedding;

    private VectorStoreMatrixGraphStore store;

    @BeforeEach
    void setUp() {
        store = new VectorStoreMatrixGraphStore(vectorStore, new ObjectMapper());
        // Default stub: add() returns int count, flush returns boolean, delete returns boolean
        when(vectorStore.add(any())).thenReturn(1);
        when(vectorStore.flushAndCommit()).thenReturn(true);
        when(vectorStore.delete(any())).thenReturn(true);
    }

    private static MatrixGraphNode node(String id, String type, String title) {
        return MatrixGraphNode.builder()
                .nodeId(id)
                .nodeType(type)
                .title(title)
                .description("desc of " + title)
                .metadata(Map.of())
                .build();
    }

    // ─── createGraph ─────────────────────────────────────────────────────────

    @Test
    void createGraphReturnsFreshGraph() throws IOException {
        AdjacencyMatrixGraph graph = store.createGraph("g1", null);

        assertNotNull(graph);
        assertEquals("g1", graph.getGraphId());
        assertEquals(0, graph.getNodeCount());
        // Close to free native memory
        graph.close();
    }

    @Test
    void createGraphPersistsMetadataToVectorStore() throws IOException {
        AdjacencyMatrixGraph graph = store.createGraph("g-meta", 99L);
        graph.close();

        // One add() call for the metadata doc
        verify(vectorStore, atLeastOnce()).add(any());
    }

    @Test
    void createGraphIsCachedSoLoadGraphReturnsSame() {
        AdjacencyMatrixGraph g1 = store.createGraph("g-cache", null);
        Optional<AdjacencyMatrixGraph> loaded = store.loadGraph("g-cache");

        assertTrue(loaded.isPresent());
        assertSame(g1, loaded.get(), "Loaded graph should be the same cached instance");
        g1.close();
    }

    // ─── loadGraph ───────────────────────────────────────────────────────────

    @Test
    void loadGraphReturnsCachedGraphWithoutCallingVectorStore() throws IOException {
        AdjacencyMatrixGraph g = store.createGraph("g-hit", null);

        // Reset invocations after createGraph
        clearInvocations(vectorStore);

        Optional<AdjacencyMatrixGraph> loaded = store.loadGraph("g-hit");

        assertTrue(loaded.isPresent());
        // Should NOT call listVectorDocuments because graph is in cache
        verify(vectorStore, never()).listVectorDocuments(anyInt(), anyInt());
        g.close();
    }

    @Test
    void loadGraphReturnsEmptyForUnknownGraph() {
        when(vectorStore.listVectorDocuments(anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());

        Optional<AdjacencyMatrixGraph> loaded = store.loadGraph("nonexistent");
        assertTrue(loaded.isEmpty());
    }

    // ─── addNode ─────────────────────────────────────────────────────────────

    @Test
    void addNodeIncreasesNodeCount() {
        store.createGraph("g1", null);
        int idx = store.addNode("g1", node("n1", "PERSON", "Alice"));
        assertEquals(0, idx, "First node should get matrix index 0");

        Optional<AdjacencyMatrixGraph> graphOpt = store.loadGraph("g1");
        assertTrue(graphOpt.isPresent());
        assertEquals(1, graphOpt.get().getNodeCount());
        graphOpt.get().close();
    }

    @Test
    void addNodePersistsToVectorStore() {
        store.createGraph("g1", null);
        clearInvocations(vectorStore);
        when(vectorStore.add(any())).thenReturn(1);

        store.addNode("g1", node("n1", "PERSON", "Alice"));

        verify(vectorStore, atLeastOnce()).add(any());
    }

    @Test
    void addNodeCreatesGraphIfNotCached() {
        // Graph "g-auto" not previously created
        when(vectorStore.listVectorDocuments(anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());

        int idx = store.addNode("g-auto", node("n1", "CONCEPT", "Test"));
        assertTrue(idx >= 0);
    }

    // ─── updateNode ──────────────────────────────────────────────────────────

    @Test
    void updateNodeUpdatesExistingNode() {
        store.createGraph("g1", null);
        store.addNode("g1", node("n1", "PERSON", "Alice"));

        MatrixGraphNode updated = node("n1", "PERSON", "Alice Updated");
        store.updateNode("g1", updated);

        Optional<MatrixGraphNode> nodeOpt = store.getNode("g1", "n1");
        assertTrue(nodeOpt.isPresent());
        assertEquals("Alice Updated", nodeOpt.get().getTitle());
    }

    // ─── removeNode ──────────────────────────────────────────────────────────

    @Test
    void removeNodeDeletesFromVectorStore() {
        store.createGraph("g1", null);
        store.addNode("g1", node("n1", "PERSON", "Alice"));

        when(vectorStore.delete(any())).thenReturn(true);
        boolean removed = store.removeNode("g1", "n1");

        assertTrue(removed);
        verify(vectorStore).delete(argThat(ids -> ids instanceof List && ((List<?>) ids).stream().anyMatch(id -> id.toString().contains("n1"))));
    }

    @Test
    void removeNodeDelegatesToVectorStoreEvenWhenNotCached() {
        // removeNode always delegates to vectorStore.delete() regardless of cache state
        when(vectorStore.delete(any())).thenReturn(false);
        boolean removed = store.removeNode("uncached-graph", "n1");
        // vectorStore.delete() returns false, so removeNode returns false
        assertFalse(removed);
        verify(vectorStore).delete(any());
    }

    // ─── getNode ─────────────────────────────────────────────────────────────

    @Test
    void getNodeReturnsEmptyForUnknownId() {
        store.createGraph("g1", null);
        Optional<MatrixGraphNode> result = store.getNode("g1", "unknown");
        assertTrue(result.isEmpty());
    }

    @Test
    void getNodeReturnsNodeWhenPresent() {
        store.createGraph("g1", null);
        store.addNode("g1", node("n1", "PERSON", "Alice"));

        Optional<MatrixGraphNode> result = store.getNode("g1", "n1");
        assertTrue(result.isPresent());
        assertEquals("Alice", result.get().getTitle());
    }

    // ─── getAllNodes ──────────────────────────────────────────────────────────

    @Test
    void getAllNodesReturnsEmptyForNewGraph() {
        store.createGraph("g1", null);
        List<MatrixGraphNode> nodes = store.getAllNodes("g1");
        assertNotNull(nodes);
        assertTrue(nodes.isEmpty());
    }

    @Test
    void getAllNodesReturnsAllAddedNodes() {
        store.createGraph("g1", null);
        store.addNode("g1", node("n1", "PERSON", "Alice"));
        store.addNode("g1", node("n2", "ORGANIZATION", "Acme"));

        List<MatrixGraphNode> nodes = store.getAllNodes("g1");
        assertEquals(2, nodes.size());
    }

    // ─── addEdge ─────────────────────────────────────────────────────────────

    @Test
    void addEdgeReturnsTrueForValidNodes() {
        store.createGraph("g1", null);
        store.addNode("g1", node("src", "PERSON", "Alice"));
        store.addNode("g1", node("tgt", "ORGANIZATION", "Acme"));

        boolean result = store.addEdge("g1", "src", "tgt", 0.8, "WORKS_AT", false);
        assertTrue(result);
    }

    @Test
    void addEdgeReturnsFalseForMissingNodes() {
        store.createGraph("g1", null);
        boolean result = store.addEdge("g1", "missing-src", "missing-tgt", 0.5, "REL", false);
        assertFalse(result, "Edge should fail if source or target node doesn't exist");
    }

    // ─── removeEdge ──────────────────────────────────────────────────────────

    @Test
    void removeEdgeReturnsFalseForUncachedGraph() {
        boolean result = store.removeEdge("uncached", "src", "tgt", "REL");
        assertFalse(result);
    }

    @Test
    void removeEdgeReturnsTrueForExistingEdge() {
        store.createGraph("g1", null);
        store.addNode("g1", node("src", "PERSON", "Alice"));
        store.addNode("g1", node("tgt", "ORG", "Acme"));
        store.addEdge("g1", "src", "tgt", 0.8, "WORKS_AT", false);

        boolean result = store.removeEdge("g1", "src", "tgt", "WORKS_AT");
        assertTrue(result);
    }

    // ─── hasEdge ─────────────────────────────────────────────────────────────

    @Test
    void hasEdgeReturnsFalseWhenNoEdge() {
        store.createGraph("g1", null);
        store.addNode("g1", node("src", "PERSON", "Alice"));
        store.addNode("g1", node("tgt", "ORG", "Acme"));

        assertFalse(store.hasEdge("g1", "src", "tgt", null));
    }

    @Test
    void hasEdgeReturnsTrueAfterAddEdge() {
        store.createGraph("g1", null);
        store.addNode("g1", node("src", "PERSON", "Alice"));
        store.addNode("g1", node("tgt", "ORG", "Acme"));
        store.addEdge("g1", "src", "tgt", 1.0, "RELATED_TO", false);

        assertTrue(store.hasEdge("g1", "src", "tgt", "RELATED_TO"));
    }

    // ─── getEdges ────────────────────────────────────────────────────────────

    @Test
    void getEdgesReturnsEmptyForNewNode() {
        store.createGraph("g1", null);
        store.addNode("g1", node("n1", "PERSON", "Alice"));

        List<Map.Entry<String, Double>> edges = store.getEdges("g1", "n1", null);
        assertTrue(edges.isEmpty());
    }

    @Test
    void getEdgesReturnsNeighborsAfterAddEdge() {
        store.createGraph("g1", null);
        store.addNode("g1", node("src", "PERSON", "Alice"));
        store.addNode("g1", node("tgt", "ORG", "Acme"));
        store.addEdge("g1", "src", "tgt", 0.7, "WORKS_AT", false);

        List<Map.Entry<String, Double>> edges = store.getEdges("g1", "src", "WORKS_AT");
        assertEquals(1, edges.size());
        assertEquals("tgt", edges.get(0).getKey());
        assertEquals(0.7, edges.get(0).getValue(), 0.001);
    }

    // ─── searchNodes ─────────────────────────────────────────────────────────

    @Test
    void searchNodesDelegatesToVectorStore() {
        store.createGraph("g1", null);
        String graphId = "g1";

        Document doc = new Document("graph:g1:node:n1", "Alice: engineer",
                Map.of("nodeId", "n1", "nodeType", "PERSON", "title", "Alice",
                        "type", "graph_node"));
        when(vectorStore.similaritySearch(eq("Alice"), anyInt()))
                .thenReturn(List.of(doc));

        List<MatrixGraphNode> results = store.searchNodes(graphId, "Alice", 5);

        verify(vectorStore).similaritySearch(eq("Alice"), anyInt());
        assertNotNull(results);
    }

    // ─── findSimilarNodes ────────────────────────────────────────────────────

    @Test
    void findSimilarNodesDelegatesToVectorStoreWithEmbedding() {
        store.createGraph("g1", null);
        when(vectorStore.similaritySearchWithScores(any(INDArray.class), anyInt(), anyDouble()))
                .thenReturn(Collections.emptyList());

        List<Map.Entry<String, Double>> results =
                store.findSimilarNodes("g1", queryEmbedding, 5, 0.5);

        verify(vectorStore).similaritySearchWithScores(eq(queryEmbedding), eq(5), eq(0.5));
        assertNotNull(results);
    }

    @Test
    void findSimilarNodesFiltersResultsByGraphId() {
        store.createGraph("g1", null);
        // A document from a different graph should NOT be included
        Document docOtherGraph = new Document("graph:other-graph:node:n99", "other content",
                Map.of("type", "graph_node"));
        Document docThisGraph = new Document("graph:g1:node:n1", "alice content",
                Map.of("type", "graph_node"));

        when(vectorStore.similaritySearchWithScores(any(INDArray.class), anyInt(), anyDouble()))
                .thenReturn(List.of(
                        new ScoredDocument(docThisGraph, 0.95),
                        new ScoredDocument(docOtherGraph, 0.90)
                ));

        List<Map.Entry<String, Double>> results =
                store.findSimilarNodes("g1", queryEmbedding, 5, 0.0);

        assertEquals(1, results.size(), "Only nodes from graph 'g1' should be returned");
        assertEquals("n1", results.get(0).getKey());
        assertEquals(0.95, results.get(0).getValue(), 0.001);
    }

    // ─── deleteGraph ─────────────────────────────────────────────────────────

    @Test
    void deleteGraphClearsCache() {
        store.createGraph("g-delete", null);
        assertTrue(store.loadGraph("g-delete").isPresent());

        when(vectorStore.listVectorDocuments(anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());
        store.deleteGraph("g-delete");

        // After deletion, the cache should be cleared; loading from VS returns empty
        when(vectorStore.listVectorDocuments(anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());
        Optional<AdjacencyMatrixGraph> afterDelete = store.loadGraph("g-delete");
        assertTrue(afterDelete.isEmpty());
    }

    // ─── listGraphs ──────────────────────────────────────────────────────────

    @Test
    void listGraphsParsesGraphMetaDocs() {
        when(vectorStore.listVectorDocuments(anyInt(), anyInt())).thenReturn(List.of(
                Map.of("id", "graph:my-graph:meta", "type", "graph_metadata"),
                Map.of("id", "graph:other-graph:meta", "type", "graph_metadata"),
                Map.of("id", "graph:other-graph:node:n1", "type", "graph_node")
        ));

        List<String> graphs = store.listGraphs();
        assertEquals(2, graphs.size());
        assertTrue(graphs.contains("my-graph"));
        assertTrue(graphs.contains("other-graph"));
    }

    // ─── getGraphStatistics ──────────────────────────────────────────────────

    @Test
    void getGraphStatisticsReturnsStats() {
        store.createGraph("g1", null);
        store.addNode("g1", node("n1", "PERSON", "Alice"));

        Map<String, Object> stats = store.getGraphStatistics("g1");
        assertNotNull(stats);
        assertTrue(stats.containsKey("nodeCount") || stats.containsKey("graphId"),
                "Statistics map should contain node or graph metadata");
    }

    // ─── Batch operations ────────────────────────────────────────────────────

    @Test
    void addNodesBatchAddsAllNodes() {
        store.createGraph("g1", null);
        List<MatrixGraphNode> nodes = List.of(
                node("n1", "PERSON", "Alice"),
                node("n2", "PERSON", "Bob"),
                node("n3", "ORGANIZATION", "Acme")
        );
        when(vectorStore.add(any())).thenReturn(1);

        int count = store.addNodesBatch("g1", nodes);
        assertEquals(3, count);
    }

    @Test
    void addEdgesBatchAddsValidEdges() {
        store.createGraph("g1", null);
        store.addNode("g1", node("src", "PERSON", "Alice"));
        store.addNode("g1", node("tgt", "ORG", "Acme"));

        List<MatrixGraphStore.EdgeDefinition> edges = List.of(
                new MatrixGraphStore.EdgeDefinition("src", "tgt", 0.9, "WORKS_AT", false)
        );
        int count = store.addEdgesBatch("g1", edges);
        assertEquals(1, count);
    }

    // ─── flush ───────────────────────────────────────────────────────────────

    @Test
    void flushSavesAllCachedGraphsAndCallsVectorStoreCommit() {
        store.createGraph("g1", null);

        // Flush will try to save all cached graphs (calls vectorStore.add)
        // Reset to verify flush calls
        clearInvocations(vectorStore);
        when(vectorStore.add(any())).thenReturn(1);

        store.flush();

        verify(vectorStore, atLeastOnce()).flushAndCommit();
    }
}
