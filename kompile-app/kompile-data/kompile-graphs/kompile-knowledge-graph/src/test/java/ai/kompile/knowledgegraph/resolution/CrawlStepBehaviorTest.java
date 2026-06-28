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
package ai.kompile.knowledgegraph.resolution;

import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.matrix.model.AdjacencyMatrixGraph;
import ai.kompile.knowledgegraph.matrix.model.MatrixGraphNode;
import ai.kompile.knowledgegraph.matrix.service.MatrixKnowledgeGraphService;
import ai.kompile.knowledgegraph.matrix.store.MatrixGraphStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.nd4j.linalg.api.ndarray.INDArray;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain unit tests for two crawl-step behaviors in the knowledge-graph module:
 *
 * <ol>
 *   <li><b>ENTITY_RESOLUTION chunked entity loading</b> — exercises
 *       {@link MatrixKnowledgeGraphService#countEntityNodesInFactSheet},
 *       {@link MatrixKnowledgeGraphService#getEntityNodesInFactSheetPage}, and the
 *       {@link GraphCompactionService#compact(Long, GraphCompactionService.CompactionConfig)} chunked
 *       path ({@code buildEntityBlocksChunked}) via the public API.</li>
 *   <li><b>Per-fact-sheet graph segmentation</b> — exercises
 *       {@code MatrixKnowledgeGraphService.graphIdForFactSheet} routing, verifying that nodes land in
 *       distinct per-fact-sheet graphs and that scoped reads return only that fact sheet's data.</li>
 * </ol>
 *
 * Uses an in-process {@link InMemoryMatrixGraphStore} backed by {@link AdjacencyMatrixGraph} so no
 * Spring context, vector store, ND4J algorithm calls, or subprocess are needed.
 */
class CrawlStepBehaviorTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // MINIMAL IN-MEMORY MATRIX GRAPH STORE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Purely in-memory implementation of {@link MatrixGraphStore} backed by a
     * {@link ConcurrentHashMap} of {@link AdjacencyMatrixGraph} objects.
     *
     * <p>Only the methods exercised by the tested paths are implemented — everything else
     * is a safe no-op or returns an empty/null value.</p>
     */
    static class InMemoryMatrixGraphStore implements MatrixGraphStore {

        private final Map<String, AdjacencyMatrixGraph> graphs = new ConcurrentHashMap<>();

        private AdjacencyMatrixGraph getOrCreate(String graphId) {
            return graphs.computeIfAbsent(graphId,
                    id -> new AdjacencyMatrixGraph(id, 1024));
        }

        @Override
        public AdjacencyMatrixGraph createGraph(String graphId, Long factSheetId) {
            AdjacencyMatrixGraph g = getOrCreate(graphId);
            g.setFactSheetId(factSheetId);
            return g;
        }

        @Override
        public Optional<AdjacencyMatrixGraph> loadGraph(String graphId) {
            return Optional.ofNullable(graphs.get(graphId));
        }

        @Override
        public void saveGraph(AdjacencyMatrixGraph graph) throws IOException {
            // no-op: already in memory
        }

        @Override
        public boolean deleteGraph(String graphId) {
            return graphs.remove(graphId) != null;
        }

        @Override
        public List<String> listGraphs() {
            return new ArrayList<>(graphs.keySet());
        }

        @Override
        public Set<String> getLoadedGraphIds() {
            return new LinkedHashSet<>(graphs.keySet());
        }

        @Override
        public List<String> listGraphsByFactSheet(Long factSheetId) {
            return graphs.entrySet().stream()
                    .filter(e -> factSheetId != null && factSheetId.equals(e.getValue().getFactSheetId()))
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
        }

        @Override
        public int addNode(String graphId, MatrixGraphNode node) {
            return getOrCreate(graphId).addNode(node);
        }

        @Override
        public void updateNode(String graphId, MatrixGraphNode node) {
            getOrCreate(graphId).addNode(node);
        }

        @Override
        public boolean removeNode(String graphId, String nodeId) {
            AdjacencyMatrixGraph g = graphs.get(graphId);
            return g != null && g.removeNode(nodeId);
        }

        @Override
        public Optional<MatrixGraphNode> getNode(String graphId, String nodeId) {
            AdjacencyMatrixGraph g = graphs.get(graphId);
            return g != null ? g.getNode(nodeId) : Optional.empty();
        }

        @Override
        public List<MatrixGraphNode> getAllNodes(String graphId) {
            AdjacencyMatrixGraph g = graphs.get(graphId);
            return g != null ? g.getAllNodes() : Collections.emptyList();
        }

        @Override
        public List<MatrixGraphNode> searchNodes(String graphId, String query, int limit) {
            AdjacencyMatrixGraph g = graphs.get(graphId);
            if (g == null) return Collections.emptyList();
            String lq = query != null ? query.toLowerCase(Locale.ROOT) : "";
            return g.getAllNodes().stream()
                    .filter(n -> lq.isBlank()
                            || (n.getTitle() != null && n.getTitle().toLowerCase(Locale.ROOT).contains(lq)))
                    .limit(limit)
                    .collect(Collectors.toList());
        }

        @Override
        public boolean addEdge(String graphId, String sourceNodeId, String targetNodeId,
                               double weight, String edgeType, boolean bidirectional) {
            AdjacencyMatrixGraph g = getOrCreate(graphId);
            return g.addEdge(sourceNodeId, targetNodeId, weight, edgeType, bidirectional);
        }

        @Override
        public boolean removeEdge(String graphId, String sourceNodeId, String targetNodeId,
                                  String edgeType) {
            AdjacencyMatrixGraph g = graphs.get(graphId);
            return g != null && g.removeEdge(sourceNodeId, targetNodeId, edgeType);
        }

        @Override
        public List<Map.Entry<String, Double>> getEdges(String graphId, String nodeId,
                                                         String edgeType) {
            AdjacencyMatrixGraph g = graphs.get(graphId);
            return g != null ? g.getNeighbors(nodeId, edgeType) : Collections.emptyList();
        }

        @Override
        public boolean hasEdge(String graphId, String sourceNodeId, String targetNodeId,
                               String edgeType) {
            AdjacencyMatrixGraph g = graphs.get(graphId);
            return g != null && g.getEdgeWeight(sourceNodeId, targetNodeId, edgeType) > 0;
        }

        @Override
        public void storeNodeEmbeddings(String graphId, List<String> nodeIds, INDArray embeddings) {
            // no-op for unit tests
        }

        @Override
        public INDArray getNodeEmbeddings(String graphId, List<String> nodeIds) {
            return null;
        }

        @Override
        public List<Map.Entry<String, Double>> findSimilarNodes(String graphId, INDArray queryEmbedding,
                                                                  int k, double threshold) {
            return Collections.emptyList();
        }

        @Override
        public int addNodesBatch(String graphId, List<MatrixGraphNode> nodes) {
            int count = 0;
            for (MatrixGraphNode n : nodes) {
                addNode(graphId, n);
                count++;
            }
            return count;
        }

        @Override
        public int addEdgesBatch(String graphId, List<EdgeDefinition> edges) {
            int count = 0;
            for (EdgeDefinition e : edges) {
                if (addEdge(graphId, e.sourceNodeId(), e.targetNodeId(), e.weight(),
                        e.edgeType(), e.bidirectional())) {
                    count++;
                }
            }
            return count;
        }

        @Override
        public void flush() {
            // no-op
        }

        @Override
        public Map<String, Object> getGraphStatistics(String graphId) {
            AdjacencyMatrixGraph g = graphs.get(graphId);
            if (g == null) return Map.of("nodeCount", 0L);
            return Map.of("nodeCount", (long) g.getNodeCount());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST FIXTURES
    // ═══════════════════════════════════════════════════════════════════════════

    private InMemoryMatrixGraphStore store;
    private MatrixKnowledgeGraphService service;
    private GraphCompactionService compactionService;

    @BeforeEach
    void setUp() {
        store = new InMemoryMatrixGraphStore();
        service = new MatrixKnowledgeGraphService(store, new ObjectMapper());
        compactionService = new GraphCompactionService(service);
        // Small chunk size so chunked path exercises pagination even for small graphs
        System.setProperty("kompile.compaction.entityLoadChunkSize", "3");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("kompile.compaction.entityLoadChunkSize");
    }

    /**
     * Create an ENTITY node with a non-generic type so it survives generic-artifact filtering
     * in the compaction service.
     */
    private GraphNode createEntityNode(Long factSheetId, String externalId, String title) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("entity_type", "ORGANIZATION");
        return service.createNode(NodeLevel.ENTITY, externalId, title, null, meta, factSheetId);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST 1 — ENTITY_RESOLUTION CHUNKED ENTITY LOADING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 1a — {@code countEntityNodesInFactSheet} returns the exact count of ENTITY nodes
     * stored under the given fact sheet, even for a large graph (2500 nodes).
     */
    @Test
    void countEntityNodesInFactSheet_returnsExactCount() {
        Long fsId = 42L;
        int expectedCount = 2500;

        for (int i = 0; i < expectedCount; i++) {
            createEntityNode(fsId, "ent-" + i, "Entity " + i);
        }

        long count = service.countEntityNodesInFactSheet(fsId);
        assertEquals(expectedCount, count,
                "countEntityNodesInFactSheet must return the exact number of ENTITY nodes");
    }

    /**
     * 1b — {@code getEntityNodesInFactSheetPage} with pageSize=1000 returns three non-overlapping
     * pages that together cover all 2500 entities exactly once (pages of 1000, 1000, 500).
     */
    @Test
    void getEntityNodesInFactSheetPage_returnsNonOverlappingPagesThatCoverAll() {
        Long fsId = 43L;
        int totalCount = 2500;
        int pageSize = 1000;

        for (int i = 0; i < totalCount; i++) {
            createEntityNode(fsId, "pent-" + i, "PageEntity " + i);
        }

        // Collect all pages
        Set<String> allSeenNodeIds = new LinkedHashSet<>();
        List<Integer> pageSizes = new ArrayList<>();
        int offset = 0;
        while (true) {
            List<GraphNode> page = service.getEntityNodesInFactSheetPage(fsId, offset, pageSize);
            if (page.isEmpty()) break;
            pageSizes.add(page.size());
            for (GraphNode n : page) {
                assertTrue(allSeenNodeIds.add(n.getNodeId()),
                        "Duplicate node across pages: " + n.getNodeId());
            }
            offset += page.size();
            if (page.size() < pageSize) break;
        }

        // Should be exactly 3 pages: 1000, 1000, 500
        assertEquals(3, pageSizes.size(), "Expected 3 pages");
        assertEquals(1000, pageSizes.get(0), "First page should have 1000 nodes");
        assertEquals(1000, pageSizes.get(1), "Second page should have 1000 nodes");
        assertEquals(500, pageSizes.get(2), "Third page should have 500 nodes");

        // All 2500 entities must be covered
        assertEquals(totalCount, allSeenNodeIds.size(),
                "All entities must be reachable across the pages");
    }

    /**
     * 1c — the chunked compaction path ({@code buildEntityBlocksChunked}) finds the same merge
     * candidates as a direct inspection of the entity set with a known-correct small example.
     *
     * <p>Setup: 6 ORGANIZATION entities under factSheetId=44 where two entity pairs have
     * effectively identical normalized titles (exact duplicates). The chunked path (chunkSize=3
     * set in setUp) must find both pairs as merge candidates, matching the expected result.</p>
     */
    @Test
    void chunkedCompactionPath_findsSameMatchCandidatesAsExpected() {
        Long fsId = 44L;

        // Two pairs of identical names → must produce 2 merge candidates
        // Two singletons with distinct names → no candidate
        createEntityNode(fsId, "org-a1", "Acme Corporation");
        createEntityNode(fsId, "org-a2", "Acme Corporation");   // exact dup of org-a1
        createEntityNode(fsId, "org-b1", "Beta Industries");
        createEntityNode(fsId, "org-b2", "Beta Industries");   // exact dup of org-b1
        createEntityNode(fsId, "org-c1", "Gamma LLC");           // singleton
        createEntityNode(fsId, "org-d1", "Delta Solutions");     // singleton

        // Use previewCandidates so we don't mutate the graph (no delete)
        GraphCompactionService.CompactionConfig config = GraphCompactionService.CompactionConfig.withoutEmbeddings(0.85);
        List<GraphCompactionService.MatchCandidate> candidates =
                compactionService.previewCandidates(fsId, config);

        // Both identical pairs must surface as candidates
        long acmeCandidates = candidates.stream()
                .filter(c -> "Acme Corporation".equalsIgnoreCase(c.titleA())
                        || "Acme Corporation".equalsIgnoreCase(c.titleB()))
                .count();
        long betaCandidates = candidates.stream()
                .filter(c -> "Beta Industries".equalsIgnoreCase(c.titleA())
                        || "Beta Industries".equalsIgnoreCase(c.titleB()))
                .count();

        assertTrue(acmeCandidates >= 1,
                "Chunked path must detect the 'Acme Corporation' duplicate pair; candidates=" + candidates);
        assertTrue(betaCandidates >= 1,
                "Chunked path must detect the 'Beta Industries' duplicate pair; candidates=" + candidates);

        // Singletons must NOT produce candidates against each other (different names, score < threshold)
        long gammaDeltaCandidates = candidates.stream()
                .filter(c -> (c.titleA().contains("Gamma") && c.titleB().contains("Delta"))
                        || (c.titleA().contains("Delta") && c.titleB().contains("Gamma")))
                .count();
        assertEquals(0, gammaDeltaCandidates,
                "Distinct singleton entities (Gamma/Delta) must NOT be merge candidates");
    }

    /**
     * 1d — calling {@code compact(factSheetId, config)} on a fact sheet with two exact-duplicate
     * entity pairs produces a result showing exactly 2 merged entities (one from each pair),
     * confirming the chunked path performs a correct end-to-end compaction.
     */
    @Test
    void compact_withChunkedPath_mergesExactDuplicates() {
        Long fsId = 45L;

        createEntityNode(fsId, "cx-a1", "Contoso Corp");
        createEntityNode(fsId, "cx-a2", "Contoso Corp");   // duplicate
        createEntityNode(fsId, "cx-b1", "Fabrikam Inc");
        createEntityNode(fsId, "cx-b2", "Fabrikam Inc");   // duplicate
        createEntityNode(fsId, "cx-c1", "Northwind Traders"); // singleton (no dup)

        GraphCompactionService.CompactionConfig config =
                new GraphCompactionService.CompactionConfig(0.85, true, false, 0.0);
        GraphCompactionService.CompactionResult result = compactionService.compact(fsId, config);

        // 2 merge components (one per duplicate pair)
        assertEquals(2, result.componentsFound(),
                "Should produce exactly 2 merge components for the 2 duplicate pairs");
        // 2 entities removed (one non-canonical from each pair)
        assertEquals(2, result.entitiesMerged(),
                "Should remove exactly 2 duplicate entities");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST 2 — PER-FACT-SHEET GRAPH SEGMENTATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 2a — nodes created with different factSheetIds land in distinct per-fact-sheet graphs
     * (named "factsheet_&lt;id&gt;") and the store holds separate loaded graph IDs for each.
     * A null factSheetId falls back to a legacy default graph that is NOT a per-fact-sheet graph.
     */
    @Test
    void createNode_routesToDistinctPerFactSheetGraphs() {
        Long fs1 = 1L;
        Long fs2 = 2L;

        service.createNode(NodeLevel.ENTITY, "ent-fs1-probe", "ProbeFact FS1", null, null, fs1);
        service.createNode(NodeLevel.ENTITY, "ent-fs2-probe", "ProbeFact FS2", null, null, fs2);
        service.createNode(NodeLevel.ENTITY, "ent-null-probe", "ProbeFact null", null, null, null);

        Set<String> loadedIds = store.getLoadedGraphIds();

        // Per-fact-sheet graph IDs must follow "factsheet_<id>" pattern
        assertTrue(loadedIds.stream().anyMatch(id -> id.equals("factsheet_1")),
                "Store must have a graph named 'factsheet_1' after creating a fs1 node; loaded=" + loadedIds);
        assertTrue(loadedIds.stream().anyMatch(id -> id.equals("factsheet_2")),
                "Store must have a graph named 'factsheet_2' after creating a fs2 node; loaded=" + loadedIds);

        // The null-factSheetId graph must NOT be named like a per-fact-sheet graph
        boolean nullInFactsheetGraph = store.getAllNodes("factsheet_1").stream()
                .anyMatch(n -> "entity_ent-null-probe".equals(n.getNodeId()));
        boolean nullInFactsheet2Graph = store.getAllNodes("factsheet_2").stream()
                .anyMatch(n -> "entity_ent-null-probe".equals(n.getNodeId()));
        assertFalse(nullInFactsheetGraph || nullInFactsheet2Graph,
                "A null-factSheetId node must NOT be stored in any per-fact-sheet graph");
    }

    /**
     * 2b — nodes created under factSheetId=1 land in the "factsheet_1" graph and are NOT visible
     * in "factsheet_2", and vice versa.
     */
    @Test
    void createNode_storesInCorrectPerFactSheetGraph() {
        Long fs1 = 1L;
        Long fs2 = 2L;

        service.createNode(NodeLevel.ENTITY, "ent-fs1-a", "Entity A FS1", null, null, fs1);
        service.createNode(NodeLevel.ENTITY, "ent-fs1-b", "Entity B FS1", null, null, fs1);
        service.createNode(NodeLevel.ENTITY, "ent-fs2-a", "Entity A FS2", null, null, fs2);

        // Derive graph IDs from what the store actually created
        List<MatrixGraphNode> rawNodes1 = store.getAllNodes("factsheet_1");
        List<MatrixGraphNode> rawNodes2 = store.getAllNodes("factsheet_2");

        Set<String> ids1 = rawNodes1.stream().map(MatrixGraphNode::getNodeId).collect(Collectors.toSet());
        Set<String> ids2 = rawNodes2.stream().map(MatrixGraphNode::getNodeId).collect(Collectors.toSet());

        assertTrue(ids1.contains("entity_ent-fs1-a"), "FS1 graph must contain entity_ent-fs1-a");
        assertTrue(ids1.contains("entity_ent-fs1-b"), "FS1 graph must contain entity_ent-fs1-b");
        assertFalse(ids1.contains("entity_ent-fs2-a"), "FS1 graph must NOT contain FS2 entity");

        assertTrue(ids2.contains("entity_ent-fs2-a"), "FS2 graph must contain entity_ent-fs2-a");
        assertFalse(ids2.contains("entity_ent-fs1-a"), "FS2 graph must NOT contain FS1 entity");
    }

    /**
     * 2c — {@code getNodesByTypeInFactSheet} returns ONLY the nodes belonging to the queried
     * fact sheet. Cross-fact-sheet contamination must be zero.
     */
    @Test
    void getNodesByTypeInFactSheet_returnsOnlyThatFactSheetsNodes() {
        Long fs1 = 10L;
        Long fs2 = 20L;

        // 5 entities in FS1
        for (int i = 0; i < 5; i++) {
            service.createNode(NodeLevel.ENTITY, "seg-fs1-" + i, "SegEntity FS1 " + i, null, null, fs1);
        }
        // 3 entities in FS2
        for (int i = 0; i < 3; i++) {
            service.createNode(NodeLevel.ENTITY, "seg-fs2-" + i, "SegEntity FS2 " + i, null, null, fs2);
        }

        List<GraphNode> fs1Nodes = service.getNodesByTypeInFactSheet(fs1, NodeLevel.ENTITY);
        List<GraphNode> fs2Nodes = service.getNodesByTypeInFactSheet(fs2, NodeLevel.ENTITY);

        assertEquals(5, fs1Nodes.size(),
                "getNodesByTypeInFactSheet(10, ENTITY) must return exactly 5 nodes");
        assertEquals(3, fs2Nodes.size(),
                "getNodesByTypeInFactSheet(20, ENTITY) must return exactly 3 nodes");

        // No FS1 node must appear in FS2's result and vice versa
        Set<String> fs1Ids = fs1Nodes.stream().map(GraphNode::getNodeId).collect(Collectors.toSet());
        Set<String> fs2Ids = fs2Nodes.stream().map(GraphNode::getNodeId).collect(Collectors.toSet());
        assertTrue(Collections.disjoint(fs1Ids, fs2Ids),
                "FS1 and FS2 node sets must be disjoint — no cross-contamination");
    }

    /**
     * 2d — {@code countEntityNodesInFactSheet} counts only ENTITY nodes in the given fact sheet;
     * ENTITY nodes in other fact sheets, and non-ENTITY nodes in the same fact sheet, are excluded.
     */
    @Test
    void countEntityNodesInFactSheet_excludesOtherFactSheetsAndOtherNodeTypes() {
        Long fs = 30L;
        Long otherFs = 31L;

        // 4 ENTITY nodes in the target fact sheet
        for (int i = 0; i < 4; i++) {
            service.createNode(NodeLevel.ENTITY, "cnt-ent-" + i, "CountEntity " + i, null, null, fs);
        }
        // 2 DOCUMENT nodes in the same fact sheet — must NOT be counted
        for (int i = 0; i < 2; i++) {
            service.createNode(NodeLevel.DOCUMENT, "cnt-doc-" + i, "CountDoc " + i, null, null, fs);
        }
        // 3 ENTITY nodes in a different fact sheet — must NOT be counted
        for (int i = 0; i < 3; i++) {
            service.createNode(NodeLevel.ENTITY, "cnt-other-" + i, "OtherEntity " + i, null, null, otherFs);
        }

        long count = service.countEntityNodesInFactSheet(fs);
        assertEquals(4, count,
                "countEntityNodesInFactSheet must count only ENTITY nodes in the queried fact sheet");
    }

    /**
     * 2e — a node created with {@code factSheetId=null} goes to the default graph and does NOT
     * appear in any per-fact-sheet query.
     */
    @Test
    void nullFactSheetId_usesDefaultGraph_notVisibleInFactSheetQueries() {
        Long fs = 50L;

        // One entity in the fact sheet, one with null factSheetId
        service.createNode(NodeLevel.ENTITY, "scoped-ent", "ScopedEntity", null, null, fs);
        service.createNode(NodeLevel.ENTITY, "global-ent", "GlobalEntity", null, null, null);

        // Fact-sheet-scoped query must return only the scoped entity
        List<GraphNode> scoped = service.getNodesByTypeInFactSheet(fs, NodeLevel.ENTITY);
        assertEquals(1, scoped.size(),
                "Only the fact-sheet-scoped entity must be returned");
        assertEquals("entity_scoped-ent", scoped.get(0).getNodeId());

        // The null-factSheetId node must land in some default (non-per-fact-sheet) graph.
        // Search all loaded graph IDs that do NOT match "factsheet_50"
        Set<String> allGraphIds = store.getLoadedGraphIds();
        boolean globalInDefault = allGraphIds.stream()
                .filter(id -> !id.equals("factsheet_50"))
                .flatMap(id -> store.getAllNodes(id).stream())
                .anyMatch(n -> "entity_global-ent".equals(n.getNodeId()));
        assertTrue(globalInDefault,
                "A null-factSheetId entity must be stored in the default (non-per-fact-sheet) graph");
    }
}
