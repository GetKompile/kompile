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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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

    @Test
    void addEdgeWithRelationTypeStoresExplicitFieldOnGraph() {
        store.createGraph("g-rel", null);
        store.addNode("g-rel", node("src", "PERSON", "Alice"));
        store.addNode("g-rel", node("tgt", "ORG", "Acme"));

        // 7-arg overload: structural routing key + explicit semantic relation as a first-class field.
        store.addEdge("g-rel", "src", "tgt", 0.9, "USER_DEFINED", false, "WORKS_AT");

        AdjacencyMatrixGraph graph = store.loadGraph("g-rel").orElseThrow();
        assertEquals("WORKS_AT", graph.getEdgeRelationType("USER_DEFINED", "src", "tgt"));
        graph.close();
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

    // ─── M-7: edge metadata (confidence, bidirectional, description) round-trips ─

    @Test
    void addEdgeWithMetadata_storesConfidenceAndDescription() {
        // [M-7] The 9-arg addEdge overload must store confidence and description in edgeMetaData.
        store.createGraph("g-meta7", null);
        store.addNode("g-meta7", node("src", "PERSON", "Alice"));
        store.addNode("g-meta7", node("tgt", "ORG",    "Acme"));

        boolean ok = store.addEdge("g-meta7", "src", "tgt", 0.8, "USER_DEFINED",
                false, null, 0.72, "Alice works at Acme");

        assertTrue(ok);

        AdjacencyMatrixGraph graph = store.loadGraph("g-meta7").orElseThrow();
        AdjacencyMatrixGraph.EdgeMeta meta = graph.getEdgeMeta("USER_DEFINED", "src", "tgt");
        assertNotNull(meta, "EdgeMeta should be stored");
        assertNotNull(meta.confidence());
        assertEquals(0.72, meta.confidence(), 0.001);
        assertEquals("Alice works at Acme", meta.description());
        assertFalse(Boolean.TRUE.equals(meta.bidirectional()));
        graph.close();
    }

    @Test
    void addEdgeWithMetadata_bidirectionalFlagIsStored() {
        // [M-7] Bidirectional=true must be stored in EdgeMeta.
        store.createGraph("g-bidir", null);
        store.addNode("g-bidir", node("a", "PERSON", "Alice"));
        store.addNode("g-bidir", node("b", "PERSON", "Bob"));

        store.addEdge("g-bidir", "a", "b", 1.0, "RELATED_TO",
                true, null, 0.9, null);

        AdjacencyMatrixGraph graph = store.loadGraph("g-bidir").orElseThrow();
        AdjacencyMatrixGraph.EdgeMeta meta = graph.getEdgeMeta("RELATED_TO", "a", "b");
        assertNotNull(meta);
        assertTrue(Boolean.TRUE.equals(meta.bidirectional()));
        graph.close();
    }

    @Test
    void saveGraph_persistsBoundedEdgeDocumentsWithMetadata() throws Exception {
        store.createGraph("g-serial", null);
        store.addNode("g-serial", node("src", "PERSON", "Alice"));
        store.addNode("g-serial", node("tgt", "ORG", "Acme"));
        store.addEdge("g-serial", "src", "tgt", 0.6, "USER_DEFINED",
                false, null, 0.88, "employment relationship");

        clearInvocations(vectorStore);
        store.flush();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, atLeastOnce()).add(captor.capture());

        List<Document> edgeDocs = captor.getAllValues().stream()
                .flatMap(List::stream)
                .filter(doc -> "graph_edge".equals(doc.getMetadata().get("type")))
                .toList();
        assertEquals(1, edgeDocs.size(), "one relationship must produce one Lucene document");
        Document edge = edgeDocs.get(0);
        assertEquals("src", edge.getMetadata().get("sourceNodeId"));
        assertEquals("tgt", edge.getMetadata().get("targetNodeId"));
        assertEquals(0.88, edge.getMetadata().get("confidence"));
        assertEquals("employment relationship", edge.getMetadata().get("description"));
        assertTrue(edge.getText().length() < 256, "edge content must remain bounded");
        assertTrue(captor.getAllValues().stream().flatMap(List::stream)
                .noneMatch(doc -> "adjacency_matrix".equals(doc.getMetadata().get("type"))));
    }

    // ─── Restart round-trip ───────────────────────────────────────────────────

    /**
     * Acceptance test for the production data-loss bug: graph must survive a JVM restart.
     *
     * <p>Simulates the full persistence round-trip:
     * <ol>
     *   <li>Store a graph with nodes and edges (writes Documents to the mock vector store).</li>
     *   <li>Capture every {@link Document} that was passed to {@code vectorStore.add()}.</li>
     *   <li>Convert the captured Spring AI Documents into the format that
     *       {@link VectorStore#listVectorDocuments} returns after a real Lucene round-trip:
     *       a map with top-level {@code "id"} and {@code "content"} keys, plus a nested
     *       {@code "metadata"} map containing all the application-level fields.  This is
     *       exactly the structure that {@link AnseriniVectorStoreImpl#listVectorDocuments}
     *       produces.</li>
     *   <li>Clear the in-memory {@code graphCache} via reflection to simulate a fresh JVM.</li>
     *   <li>Configure the mock to return the captured documents from {@code listVectorDocuments}.</li>
     *   <li>Load the graph and assert that nodes and edges are non-empty and correct.</li>
     * </ol>
     */
    @Test
    void restartRoundTrip_graphSurvivesJvmRestart() throws Exception {
        ObjectMapper om = new ObjectMapper();

        // ── Phase 1: build and persist a graph ───────────────────────────────
        when(vectorStore.add(any())).thenReturn(1);
        when(vectorStore.flushAndCommit()).thenReturn(true);

        store.createGraph("restart-graph", 42L);
        store.addNode("restart-graph", node("alice", "PERSON", "Alice"));
        store.addNode("restart-graph", node("acme",  "ORGANIZATION", "Acme Corp"));
        store.addEdge("restart-graph", "alice", "acme", 0.9, "WORKS_AT", false, "works_at");

        // Flush so that saveAdjacencyMatrices() fires and the edge serialization
        // document is captured (saveGraph calls saveAdjacencyMatrices).
        store.saveGraph(store.loadGraph("restart-graph").orElseThrow());

        // ── Phase 2: capture every Document that was added to the vector store ─
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, atLeastOnce()).add(captor.capture());

        // Collect all unique documents (de-duplicated by id, last-write wins).
        Map<String, Document> byId = new HashMap<>();
        for (List<Document> batch : captor.getAllValues()) {
            for (Document d : batch) {
                if (d.getId() != null) {
                    byId.put(d.getId(), d);
                }
            }
        }
        assertFalse(byId.isEmpty(), "No documents were captured — the store never called vectorStore.add()");

        // ── Phase 3: convert to listVectorDocuments format ────────────────────
        // AnseriniVectorStoreImpl.listVectorDocuments returns:
        //   { "id": "<doc-id>", "content": "<doc-text>", "metadata": { ...all metadata fields... } }
        // This is the format our fixed loadGraphFromVectorStore now handles via flattenDoc().
        List<Map<String, Object>> vsListDocs = new ArrayList<>();
        for (Document d : byId.values()) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", d.getId());
            // The adjacency-matrix document text is the JSON edge list.
            String text = d.getText();
            if (text != null && !text.isBlank()) {
                row.put("content", text);
            }
            // Wrap the Spring AI Document's metadata inside a nested "metadata" map,
            // exactly as AnseriniVectorStoreImpl does.
            if (d.getMetadata() != null && !d.getMetadata().isEmpty()) {
                row.put("metadata", new HashMap<>(d.getMetadata()));
            }
            vsListDocs.add(row);
        }

        // ── Phase 4: simulate JVM restart by clearing the in-memory cache ──────
        Field cacheField = VectorStoreMatrixGraphStore.class.getDeclaredField("graphCache");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, AdjacencyMatrixGraph> cache =
                (ConcurrentHashMap<String, AdjacencyMatrixGraph>) cacheField.get(store);
        cache.clear(); // ← this is what a JVM restart does

        // ── Phase 5: configure mock to serve the captured docs ────────────────
        when(vectorStore.listVectorDocuments(anyInt(), anyInt())).thenReturn(vsListDocs);

        // ── Phase 6: reload and assert ────────────────────────────────────────
        Optional<AdjacencyMatrixGraph> reloaded = store.loadGraph("restart-graph");

        assertTrue(reloaded.isPresent(),
                "Graph must be present after simulated restart — loadGraphFromVectorStore must reconstruct it");

        AdjacencyMatrixGraph g = reloaded.get();
        try {
            assertEquals(2, g.getNodeCount(),
                    "Both nodes (alice, acme) must survive the restart round-trip");

            assertTrue(g.getNode("alice").isPresent(), "Node 'alice' must be present after restart");
            assertTrue(g.getNode("acme").isPresent(),  "Node 'acme' must be present after restart");

            assertEquals("Alice",     g.getNode("alice").get().getTitle());
            assertEquals("Acme Corp", g.getNode("acme").get().getTitle());

            assertTrue(g.hasEdge("alice", "acme", "WORKS_AT"),
                    "Edge alice→acme:WORKS_AT must survive the restart round-trip");
        } finally {
            g.close();
        }
    }

    /**
     * Sibling acceptance test: node embedding matrix must survive a JVM restart.
     *
     * <p>Builds a 2-node graph, stores a 3-dim embedding matrix (one row per node),
     * flushes (which triggers {@code saveNodeEmbeddings}), then simulates restart by
     * clearing the cache and reloading from the captured vector-store documents.
     * Asserts that {@code getNodeEmbeddings()} is non-null, has the correct shape,
     * and that each node's row contains exactly the values that were stored.</p>
     */
    @org.junit.jupiter.api.Disabled("Aggregate embedding JSON was removed; vectors live on node documents")
    @Test
    void restartRoundTrip_embeddingMatrixSurvivesJvmRestart() throws Exception {
        final int DIM = 3;

        // ── Phase 1: build graph and store embeddings ─────────────────────────
        when(vectorStore.add(any())).thenReturn(1);
        when(vectorStore.addWithEmbeddings(any(), any())).thenReturn(1);
        when(vectorStore.flushAndCommit()).thenReturn(true);

        store.createGraph("embd-graph", 7L);
        store.addNode("embd-graph", node("alice", "PERSON", "Alice"));
        store.addNode("embd-graph", node("acme",  "ORGANIZATION", "Acme Corp"));

        AdjacencyMatrixGraph liveGraph = store.loadGraph("embd-graph").orElseThrow();

        // Build a 2×3 embedding matrix — row order matches the node list we pass in.
        org.nd4j.linalg.api.ndarray.INDArray embd =
                org.nd4j.linalg.factory.Nd4j.create(new float[][]{
                        {0.1f, 0.2f, 0.3f},   // alice
                        {0.4f, 0.5f, 0.6f}    // acme
                });
        liveGraph.setNodeEmbeddings(List.of("alice", "acme"), embd);
        // setNodeEmbeddings may allocate a larger matrix than 2 rows (uses max(capacity, nodeCount));
        // capture alice/acme matrix indices now so we can verify after reload.
        int aliceIdxBefore = liveGraph.getNode("alice").orElseThrow().getMatrixIndex();
        int acmeIdxBefore  = liveGraph.getNode("acme").orElseThrow().getMatrixIndex();

        // Flush to trigger saveGraph → saveNodeEmbeddings.
        store.saveGraph(liveGraph);

        // ── Phase 2: capture Documents ────────────────────────────────────────
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, atLeastOnce()).add(captor.capture());

        Map<String, Document> byId = new HashMap<>();
        for (List<Document> batch : captor.getAllValues()) {
            for (Document d : batch) {
                if (d.getId() != null) {
                    byId.put(d.getId(), d);
                }
            }
        }
        // The embeddings doc must have been written.
        String embdDocId = "graph:embd-graph:embd";
        assertTrue(byId.containsKey(embdDocId),
                "saveGraph must write the '" + embdDocId + "' document to the vector store");

        // ── Phase 3: convert to listVectorDocuments format ────────────────────
        List<Map<String, Object>> vsListDocs = new ArrayList<>();
        for (Document d : byId.values()) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", d.getId());
            String text = d.getText();
            if (text != null && !text.isBlank()) {
                row.put("content", text);
            }
            if (d.getMetadata() != null && !d.getMetadata().isEmpty()) {
                row.put("metadata", new HashMap<>(d.getMetadata()));
            }
            vsListDocs.add(row);
        }

        // ── Phase 4: simulate restart ─────────────────────────────────────────
        Field cacheField = VectorStoreMatrixGraphStore.class.getDeclaredField("graphCache");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, AdjacencyMatrixGraph> cache =
                (ConcurrentHashMap<String, AdjacencyMatrixGraph>) cacheField.get(store);
        cache.clear();

        // ── Phase 5: mock listVectorDocuments ─────────────────────────────────
        when(vectorStore.listVectorDocuments(anyInt(), anyInt())).thenReturn(vsListDocs);

        // ── Phase 6: reload and assert embeddings ─────────────────────────────
        Optional<AdjacencyMatrixGraph> reloadedOpt = store.loadGraph("embd-graph");
        assertTrue(reloadedOpt.isPresent(), "Graph must survive restart");

        AdjacencyMatrixGraph g = reloadedOpt.get();
        try {
            assertNotNull(g.getNodeEmbeddings(),
                    "getNodeEmbeddings() must be non-null after restart round-trip");
            assertEquals(DIM, g.getEmbeddingDimension(),
                    "Embedding dimension must match what was persisted");

            // Verify alice's embedding row.
            org.nd4j.linalg.api.ndarray.INDArray aliceRow = g.getNodeEmbedding("alice");
            assertNotNull(aliceRow, "alice's embedding row must be non-null");
            assertEquals(DIM, aliceRow.length(), "alice's row must have " + DIM + " dims");
            assertEquals(0.1f, aliceRow.getFloat(0), 1e-5f, "alice dim-0");
            assertEquals(0.2f, aliceRow.getFloat(1), 1e-5f, "alice dim-1");
            assertEquals(0.3f, aliceRow.getFloat(2), 1e-5f, "alice dim-2");

            // Verify acme's embedding row.
            org.nd4j.linalg.api.ndarray.INDArray acmeRow = g.getNodeEmbedding("acme");
            assertNotNull(acmeRow, "acme's embedding row must be non-null");
            assertEquals(DIM, acmeRow.length(), "acme's row must have " + DIM + " dims");
            assertEquals(0.4f, acmeRow.getFloat(0), 1e-5f, "acme dim-0");
            assertEquals(0.5f, acmeRow.getFloat(1), 1e-5f, "acme dim-1");
            assertEquals(0.6f, acmeRow.getFloat(2), 1e-5f, "acme dim-2");
        } finally {
            g.close();
        }
    }

    // ─── restart round-trip (Bug 1 + Bug 2 regression tests) ─────────────────

    /**
     * Acceptance test for Bug 2 (eager rehydration — "0/8 graphs loaded"):
     *
     * <p>The Anserini VectorStore nests all application fields inside a {@code "metadata"}
     * sub-map.  Without {@code flattenDoc}, {@code doc.get("type")} always returns {@code null},
     * {@code metaDoc} is never assigned, and every {@code loadGraphFromVectorStore} call returns
     * {@code Optional.empty()}.  This test confirms the fix by nesting metadata exactly as
     * Anserini does and asserting the graph is fully reconstructed.</p>
     */
    @Test
    void restartRoundTrip_flattenDocFixEnablesRehydration() throws Exception {
        // Phase 1: build and persist a graph
        when(vectorStore.add(any())).thenReturn(1);
        when(vectorStore.flushAndCommit()).thenReturn(true);

        store.createGraph("rr-graph", 42L);
        store.addNode("rr-graph", node("alice", "PERSON", "Alice"));
        store.addNode("rr-graph", node("acme",  "ORGANIZATION", "Acme Corp"));
        store.addEdge("rr-graph", "alice", "acme", 0.9, "WORKS_AT", false);

        store.saveGraph(store.loadGraph("rr-graph").orElseThrow());

        // Phase 2: capture every Document added to the vector store
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captor2 = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, atLeastOnce()).add(captor2.capture());

        Map<String, Document> byId = new HashMap<>();
        for (List<Document> batch : captor2.getAllValues()) {
            for (Document d : batch) {
                if (d.getId() != null) byId.put(d.getId(), d);
            }
        }
        assertFalse(byId.isEmpty(), "No documents were captured from vectorStore.add()");

        // Phase 3: convert to Anserini nested format {"id":…,"content":…,"metadata":{…all fields…}}
        List<Map<String, Object>> vsListDocs = new ArrayList<>();
        for (Document d : byId.values()) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", d.getId());
            String text = d.getText();
            if (text != null && !text.isBlank()) row.put("content", text);
            if (d.getMetadata() != null && !d.getMetadata().isEmpty()) {
                row.put("metadata", new HashMap<>(d.getMetadata()));
            }
            vsListDocs.add(row);
        }

        // Phase 4: simulate JVM restart
        clearCache();
        when(vectorStore.listVectorDocuments(anyInt(), anyInt())).thenReturn(vsListDocs);
        when(vectorStore.getIndexPath()).thenReturn("N/A");

        // Phase 5: reload and assert
        Optional<AdjacencyMatrixGraph> reloaded = store.loadGraph("rr-graph");
        assertTrue(reloaded.isPresent(),
                "Graph must be present after restart — flattenDoc fix enables metaDoc discovery");
        AdjacencyMatrixGraph g = reloaded.get();
        try {
            assertEquals(2, g.getAllNodes().size(), "Both nodes must survive restart");
            assertTrue(g.getNode("alice").isPresent(), "Node 'alice' must be present");
            assertTrue(g.getNode("acme").isPresent(),  "Node 'acme' must be present");
            assertTrue(g.hasEdge("alice", "acme", "WORKS_AT"),
                    "Edge alice→acme:WORKS_AT must survive restart");
        } finally {
            g.close();
        }
    }

    /**
     * Acceptance test for Bug 1 (index-space mismatch — "Could not resolve edge source=N target=M"):
     *
     * <p>After removing 50 of 250 nodes, the remaining 200 nodes have {@code matrixIndex} values
     * spread across 0–249 with gaps.  The adjacency-matrix JSON encodes edges by these original
     * stored indices.  The previous code used the RUNTIME {@code indexToNodeId} map (new sequential
     * indices 0..199 assigned on reload by {@code addNode}) — edges referencing original indices
     * like 37 or 199 failed to resolve.  The fix builds a {@code storedIndexToNodeId} map from the
     * persisted {@code matrixIndex} field in each node document BEFORE {@code addNode} overwrites
     * it, bridging the two index spaces.</p>
     */
    @Test
    void restartRoundTrip_nonContiguousIndices_exactNodeAndEdgeCountPreserved() throws Exception {
        final int TOTAL_ADDED    = 250;
        final int REMOVED_COUNT  = 50;
        final int EXPECTED_NODES = TOTAL_ADDED - REMOVED_COUNT; // 200

        when(vectorStore.add(any())).thenReturn(1);
        when(vectorStore.addWithEmbeddings(any(), any())).thenReturn(1);
        when(vectorStore.flushAndCommit()).thenReturn(true);
        when(vectorStore.delete(any())).thenReturn(true);

        // Phase 1: add 250 nodes
        store.createGraph("gap-graph", 1L);
        for (int i = 0; i < TOTAL_ADDED; i++) {
            store.addNode("gap-graph", node("node-" + i, "CONCEPT", "Node " + i));
        }
        assertEquals(TOTAL_ADDED, store.getAllNodes("gap-graph").size());

        // Phase 2: remove every 5th node to create index gaps (node-0, node-5, ..., node-245)
        Set<String> removedIds = new HashSet<>();
        for (int i = 0; i < TOTAL_ADDED; i += 5) removedIds.add("node-" + i);
        assertEquals(REMOVED_COUNT, removedIds.size());
        for (String id : removedIds) store.removeNode("gap-graph", id);
        assertEquals(EXPECTED_NODES, store.getAllNodes("gap-graph").size());

        // Phase 3: add dense edges across the gaps
        List<String> survivors = store.getAllNodes("gap-graph").stream()
                .map(MatrixGraphNode::getNodeId)
                .collect(Collectors.toList());
        int edgesAdded = 0;
        for (int i = 0; i < survivors.size(); i++) {
            for (int j = 1; j <= 3 && i + j < survivors.size(); j++) {
                boolean ok = store.addEdge("gap-graph", survivors.get(i), survivors.get(i + j),
                        0.5 + 0.001 * j, "DEPENDS_ON", false);
                if (ok) edgesAdded++;
            }
        }
        assertTrue(edgesAdded > 0, "Must have added at least some edges");

        // Phase 4: save
        AdjacencyMatrixGraph liveGraph = store.loadGraph("gap-graph").orElseThrow();
        long expectedEdgeCount = liveGraph.getEdgeCount();
        assertEquals(edgesAdded, expectedEdgeCount, "Edge count in live graph must match added edges");

        clearInvocations(vectorStore);
        when(vectorStore.add(any())).thenReturn(1);
        when(vectorStore.addWithEmbeddings(any(), any())).thenReturn(1);
        when(vectorStore.flushAndCommit()).thenReturn(true);
        store.saveGraph(liveGraph);

        // Phase 5: capture saved Documents
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captor3 = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, atLeastOnce()).add(captor3.capture());

        Map<String, Document> byId3 = new HashMap<>();
        for (List<Document> batch : captor3.getAllValues()) {
            for (Document d : batch) {
                if (d.getId() != null) byId3.put(d.getId(), d);
            }
        }

        long nodeDocs = byId3.keySet().stream().filter(id -> id.contains(":node:")).count();
        assertEquals(EXPECTED_NODES, nodeDocs,
                "saveNodes must persist exactly " + EXPECTED_NODES + " live nodes (not nextIndex=" + TOTAL_ADDED + ")");

        // Phase 6: wrap in Anserini nested format
        List<Map<String, Object>> vsListDocs3 = new ArrayList<>();
        for (Document d : byId3.values()) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", d.getId());
            String text = d.getText();
            if (text != null && !text.isBlank()) row.put("content", text);
            if (d.getMetadata() != null && !d.getMetadata().isEmpty()) {
                row.put("metadata", new HashMap<>(d.getMetadata()));
            }
            vsListDocs3.add(row);
        }

        // Phase 7: simulate JVM restart
        clearCache();
        when(vectorStore.listVectorDocuments(anyInt(), anyInt())).thenReturn(vsListDocs3);
        when(vectorStore.getIndexPath()).thenReturn("N/A");

        // Phase 8: reload and assert lossless recovery
        Optional<AdjacencyMatrixGraph> reloadedOpt = store.loadGraph("gap-graph");
        assertTrue(reloadedOpt.isPresent(), "Graph must be loadable after restart");

        AdjacencyMatrixGraph reloaded = reloadedOpt.get();
        try {
            assertEquals(EXPECTED_NODES, reloaded.getAllNodes().size(),
                    "Node count must be EXACTLY " + EXPECTED_NODES + " after restart");
            assertEquals(expectedEdgeCount, reloaded.getEdgeCount(),
                    "Edge count must be EXACTLY " + expectedEdgeCount + " after restart — ZERO edges dropped");
            for (String id : survivors) {
                assertTrue(reloaded.getNode(id).isPresent(), "Survivor node '" + id + "' must be present");
            }
            for (String id : removedIds) {
                assertTrue(reloaded.getNode(id).isEmpty(), "Removed node '" + id + "' must NOT be present");
            }
            for (int i = 0; i < Math.min(survivors.size() - 1, 10); i++) {
                assertTrue(reloaded.hasEdge(survivors.get(i), survivors.get(i + 1), "DEPENDS_ON"),
                        "Edge " + survivors.get(i) + " → " + survivors.get(i + 1) + " must survive restart");
            }
        } finally {
            reloaded.close();
        }
    }

    /** Clears the in-memory graphCache via reflection to simulate a JVM restart. */
    private void clearCache() throws Exception {
        Field cacheField = VectorStoreMatrixGraphStore.class.getDeclaredField("graphCache");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, AdjacencyMatrixGraph> cache =
                (ConcurrentHashMap<String, AdjacencyMatrixGraph>) cacheField.get(store);
        cache.clear();
    }

    // ─── Bounded-memory streaming: cross-graph isolation ─────────────────────

    /**
     * Verifies that loading graph "A" from a mixed index (containing docs for
     * graph "A" AND graph "B") never materialises graph "B"'s node docs in the
     * matched set, even when the two graphs are interleaved across multiple pages.
     *
     * <p>Mechanism: we create a counting {@code VectorStore} stub whose
     * {@code listVectorDocuments} returns a mix of A-docs and B-docs interleaved
     * (simulating a real shared index).  The stub tracks the maximum number of
     * raw docs that were returned in a single page window and asserts it never
     * exceeds the configured page size (2 000 default, set to 5 here for the
     * test).  We then assert that {@code loadGraph("graphA")} returns ONLY A's
     * nodes, never B's.</p>
     *
     * <p>This regression test guards against a future re-introduction of
     * accumulate-all patterns: if the impl reverts to calling
     * {@code listAllVectorDocuments()} the max-retained-count assertion will
     * fail because the stub sees a single call that materialises all 300 docs.</p>
     */
    @Test
    void loadGraph_doesNotMaterializeOtherGraphsDocs() throws Exception {
        // Build 150 "graphA" docs + 150 "graphB" docs interleaved in a flat list.
        // graphA has: 1 meta + 100 nodes + 1 adj + 1 embd  (103 docs)
        // graphB has: 1 meta + 100 nodes + 1 adj + 1 embd  (103 docs)
        // Plus a pile of unrelated docs (noise).
        ObjectMapper om = new ObjectMapper();

        // Helper: build a meta doc in Anserini nested format
        java.util.function.Function<String, Map<String, Object>> metaDoc = gId -> {
            Map<String, Object> meta = new HashMap<>();
            meta.put("type", "graph_metadata");
            meta.put("graphId", gId);
            meta.put("factSheetId", 1L);
            meta.put("nodeCount", 100);
            meta.put("capacity", 1024);
            meta.put("embeddingDim", 0);
            meta.put("edgeTypes", List.of("RELATED"));
            Map<String, Object> row = new HashMap<>();
            row.put("id", "graph:" + gId + ":meta");
            row.put("content", "{}");
            row.put("metadata", new HashMap<>(meta));
            return row;
        };

        // Helper: build a node doc in nested format
        java.util.function.BiFunction<String, Integer, Map<String, Object>> nodeDoc = (gId, idx) -> {
            Map<String, Object> meta = new HashMap<>();
            meta.put("type", "graph_node");
            meta.put("nodeId", gId + "-node-" + idx);
            meta.put("matrixIndex", idx);
            meta.put("nodeType", "CONCEPT");
            meta.put("title", gId + " Node " + idx);
            meta.put("description", "desc");
            Map<String, Object> row = new HashMap<>();
            row.put("id", "graph:" + gId + ":node:" + gId + "-node-" + idx);
            row.put("content", gId + " Node " + idx + ": desc");
            row.put("metadata", new HashMap<>(meta));
            return row;
        };

        // Helper: build an adj doc in nested format
        java.util.function.Function<String, Map<String, Object>> adjDoc = gId -> {
            Map<String, Object> meta = new HashMap<>();
            meta.put("type", "adjacency_matrix");
            meta.put("graphId", gId);
            meta.put("edgeType", "RELATED");
            meta.put("edgeCount", 0);
            Map<String, Object> row = new HashMap<>();
            row.put("id", "graph:" + gId + ":adj:RELATED");
            row.put("content", "[]");
            row.put("metadata", new HashMap<>(meta));
            return row;
        };

        // Assemble: interleave A-docs and B-docs so any "grab all" impl sees them mixed.
        List<Map<String, Object>> allDocs = new ArrayList<>();
        allDocs.add(metaDoc.apply("graphA"));
        allDocs.add(metaDoc.apply("graphB"));
        for (int i = 0; i < 100; i++) {
            allDocs.add(nodeDoc.apply("graphA", i));
            allDocs.add(nodeDoc.apply("graphB", i));
        }
        allDocs.add(adjDoc.apply("graphA"));
        allDocs.add(adjDoc.apply("graphB"));
        // 202 total docs, interleaved

        // ── Counting stub VectorStore ────────────────────────────────────────
        // Counts how many times each page's docs were handed to the caller.
        // We track the maximum returned-per-call to assert paging is real.
        int[] totalListCalls = {0};
        int[] maxDocsReturnedPerCall = {0};

        // Use a small page size (5) to force multiple pages and ensure the
        // streaming impl actually pages rather than grabbing everything at once.
        final int TEST_PAGE_SIZE = 5;
        VectorStore countingStore = new VectorStore() {
            @Override
            public List<Map<String, Object>> listVectorDocuments(int offset, int limit) {
                totalListCalls[0]++;
                int end = Math.min(offset + limit, allDocs.size());
                if (offset >= allDocs.size()) return Collections.emptyList();
                List<Map<String, Object>> slice = new ArrayList<>(allDocs.subList(offset, end));
                maxDocsReturnedPerCall[0] = Math.max(maxDocsReturnedPerCall[0], slice.size());
                return slice;
            }
            @Override public int add(List<Document> docs) { return docs.size(); }
            @Override public int add(List<Document> docs, List<List<Float>> emb) { return docs.size(); }
            @Override public List<Document> similaritySearch(String q, int k) { return List.of(); }
            @Override public List<Document> similaritySearch(String q, int k, double t) { return List.of(); }
            @Override public List<Document> similaritySearch(List<Float> q, int k, double t) { return List.of(); }
            @Override public boolean delete(List<String> ids) { return true; }
            @Override public boolean flushAndCommit() { return true; }
        };

        // Build a store with the counting stub and inject the small page size.
        VectorStoreMatrixGraphStore countingStoreImpl =
                new VectorStoreMatrixGraphStore(countingStore, om);
        // Inject vectorScanPageSize = TEST_PAGE_SIZE via reflection
        Field pageSizeField = VectorStoreMatrixGraphStore.class.getDeclaredField("vectorScanPageSize");
        pageSizeField.setAccessible(true);
        pageSizeField.set(countingStoreImpl, TEST_PAGE_SIZE);

        // ── Load graphA and assert only A's nodes come back ──────────────────
        Optional<AdjacencyMatrixGraph> graphAOpt = countingStoreImpl.loadGraph("graphA");

        assertTrue(graphAOpt.isPresent(), "graphA must be loadable from the mixed index");
        AdjacencyMatrixGraph graphA = graphAOpt.get();
        try {
            assertEquals(100, graphA.getNodeCount(),
                    "graphA must contain exactly 100 nodes — not B's nodes");
            // No graphB node should ever appear in graphA
            for (int i = 0; i < 100; i++) {
                assertTrue(graphA.getNode("graphA-node-" + i).isPresent(),
                        "graphA node " + i + " must be present");
                assertTrue(graphA.getNode("graphB-node-" + i).isEmpty(),
                        "graphB node " + i + " must NOT appear in graphA");
            }
        } finally {
            graphA.close();
        }

        // ── Assert paging was real — each call returned at most TEST_PAGE_SIZE docs ──
        assertTrue(totalListCalls[0] > 1,
                "loadGraph must make multiple listVectorDocuments calls (paging), got " + totalListCalls[0]);
        assertEquals(TEST_PAGE_SIZE, maxDocsReturnedPerCall[0],
                "Each page must be bounded by vectorScanPageSize=" + TEST_PAGE_SIZE
                        + " — got max " + maxDocsReturnedPerCall[0]);

        // ── listGraphs also streams — check it finds both graphs ─────────────
        List<String> listed = countingStoreImpl.listGraphs();
        assertTrue(listed.contains("graphA"), "listGraphs must find graphA");
        assertTrue(listed.contains("graphB"), "listGraphs must find graphB");
    }
}
