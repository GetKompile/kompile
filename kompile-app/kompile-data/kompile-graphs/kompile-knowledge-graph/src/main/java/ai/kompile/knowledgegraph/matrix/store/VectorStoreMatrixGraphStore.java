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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * VectorStore-backed implementation of MatrixGraphStore.
 * <p>
 * This implementation stores graph data in a vector store, enabling:
 * <ul>
 *   <li>Node embeddings stored as vector store documents</li>
 *   <li>Graph structure (adjacency matrices) stored as serialized metadata</li>
 *   <li>Similarity search for finding related nodes</li>
 *   <li>Persistence and reconstruction of full graph state</li>
 * </ul>
 * </p>
 * <p>
 * Document ID format:
 * <ul>
 *   <li>Graph metadata: {@code graph:{graphId}:meta}</li>
 *   <li>Node: {@code graph:{graphId}:node:{nodeId}}</li>
 *   <li>Adjacency matrix: {@code graph:{graphId}:adj:{edgeType}}</li>
 * </ul>
 * </p>
 */
@Service
@Slf4j
public class VectorStoreMatrixGraphStore implements MatrixGraphStore {

    @Autowired
    private VectorStore vectorStore;
    @Autowired
    private ObjectMapper objectMapper;

    public VectorStoreMatrixGraphStore() {}

    /** Test constructor. */
    public VectorStoreMatrixGraphStore(VectorStore vectorStore, ObjectMapper objectMapper) {
        this.vectorStore = vectorStore;
        this.objectMapper = objectMapper;
    }

    /**
     * In-memory cache of loaded graphs.
     */
    private final Map<String, AdjacencyMatrixGraph> graphCache = new ConcurrentHashMap<>();

    /**
     * Prefix for graph-related documents in the vector store.
     */
    private static final String GRAPH_PREFIX = "graph:";
    private static final String META_SUFFIX = ":meta";
    private static final String NODE_PREFIX = ":node:";
    private static final String ADJ_PREFIX = ":adj:";
    private static final String EMBD_SUFFIX = ":embd";

    /**
     * Number of vector-store documents to fetch per page when scanning the index.
     *
     * <p>Keeping this modest (default 2 000) means each page is bounded in heap
     * regardless of total index size.  Only documents matching the target graphId
     * are retained after each page — all others are discarded before the next
     * fetch so heap holds at most {@code vectorScanPageSize} docs at once.</p>
     *
     * <p>Override with {@code -Dkompile.graph.vector-scan-page-size=N} or in
     * {@code application.properties}.</p>
     */
    @Value("${kompile.graph.vector-scan-page-size:2000}")
    private int vectorScanPageSize = 2000;

    /**
     * Whether to eagerly rehydrate all persisted graphs into the in-memory cache on startup.
     *
     * <p>When {@code true} (the default), {@link #rehydrateGraphsOnStartup()} runs in a
     * background thread immediately after the app context is ready so graph data is available
     * in-memory as soon as the scan completes — without blocking Tomcat startup.  The
     * lazy-load-on-first-query path already works, so disabling this only affects warm-cache
     * availability; the first query will trigger an on-demand load instead.</p>
     *
     * <p>Set {@code kompile.graph.eager-rehydration-enabled=false} to skip pre-loading
     * entirely and rely purely on lazy loading.</p>
     */
    @Value("${kompile.graph.eager-rehydration-enabled:true}")
    private boolean eagerRehydrationEnabled = true;

    // ═══════════════════════════════════════════════════════════════════════════
    // STARTUP REHYDRATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * On application ready, eagerly reload every graph whose metadata document
     * exists in the vector store so queries return data immediately after restart
     * without a re-crawl.
     *
     * <p>This resolves the production data-loss symptom: crawl builds ~6 000 nodes /
     * ~70 000 edges → JVM restarts → visualizer and derivation both see zero.  The
     * write side already persists nodes + adjacency matrices to the Lucene index via
     * {@link #saveGraph}; the missing piece was reading them back on startup.</p>
     *
     * <p><b>Async:</b> this method runs in a Spring {@code @Async} thread so it never
     * blocks the Tomcat-startup main thread.  The lazy-load-on-first-query path remains
     * fully functional, so in the window between app-ready and rehydration completion the
     * first graph query simply triggers an on-demand load.  Disable pre-loading entirely
     * with {@code kompile.graph.eager-rehydration-enabled=false}.</p>
     */
    /**
     * Called by {@link GraphRehydrationListener} on {@code ApplicationReadyEvent}.
     * Not annotated with {@code @EventListener} here because this bean is proxied
     * through its {@link MatrixGraphStore} interface, and JDK proxies cannot dispatch
     * {@code @EventListener} methods that are absent from the interface.
     */
    public void rehydrateGraphsOnStartup() {
        if (!eagerRehydrationEnabled) {
            log.info("Graph eager rehydration disabled (kompile.graph.eager-rehydration-enabled=false) — graphs will load lazily on first query.");
            return;
        }
        log.info("Graph rehydration starting in background — scanning vector store for persisted graphs...");
        try {
            List<String> graphIds = listGraphs();
            if (graphIds.isEmpty()) {
                log.info("Graph rehydration: no persisted graphs found in vector store.");
                return;
            }
            int loaded = 0;
            for (String graphId : graphIds) {
                if (graphCache.containsKey(graphId)) {
                    continue; // already warm (loaded by lazy path before this scan finished)
                }
                try {
                    Optional<AdjacencyMatrixGraph> graph = loadGraphFromVectorStore(graphId);
                    if (graph.isPresent()) {
                        loaded++;
                        log.info("Rehydrated graph '{}': {} nodes", graphId,
                                graph.get().getNodeCount());
                    } else {
                        log.warn("Graph '{}' listed in vector store but could not be reconstructed", graphId);
                    }
                } catch (Exception e) {
                    log.error("Failed to rehydrate graph '{}'", graphId, e);
                }
            }
            log.info("Graph rehydration complete: {}/{} graphs loaded into cache.", loaded, graphIds.size());
        } catch (Exception e) {
            log.error("Graph rehydration failed — graphs will be loaded lazily on first access", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRAPH LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public AdjacencyMatrixGraph createGraph(String graphId, Long factSheetId) {
        // Get-or-create: with stable per-fact-sheet graph ids (graphIdForFactSheet) a second
        // createGraph for the same id must NOT wipe the existing graph — that would lose nodes/edges
        // already persisted for that fact sheet. Return the existing graph (back-filling factSheetId
        // if it was created without one) instead of replacing it with an empty one.
        Optional<AdjacencyMatrixGraph> existing = loadGraph(graphId);
        if (existing.isPresent()) {
            AdjacencyMatrixGraph g = existing.get();
            if (factSheetId != null && g.getFactSheetId() == null) {
                g.setFactSheetId(factSheetId);
            }
            return g;
        }

        AdjacencyMatrixGraph graph = new AdjacencyMatrixGraph(graphId, 1024);
        graph.setFactSheetId(factSheetId);
        graphCache.put(graphId, graph);

        // Persist initial graph metadata
        try {
            saveGraphMetadata(graph);
        } catch (IOException e) {
            log.error("Failed to save initial graph metadata for {}", graphId, e);
        }

        return graph;
    }

    @Override
    public Optional<AdjacencyMatrixGraph> loadGraph(String graphId) {
        // Check cache first
        AdjacencyMatrixGraph cached = graphCache.get(graphId);
        if (cached != null) {
            return Optional.of(cached);
        }

        // Load from vector store
        try {
            return loadGraphFromVectorStore(graphId);
        } catch (Exception e) {
            log.error("Failed to load graph {}", graphId, e);
            return Optional.empty();
        }
    }

    @Override
    public void saveGraph(AdjacencyMatrixGraph graph) throws IOException {
        String graphId = graph.getGraphId();
        log.info("Saving graph {} with {} nodes", graphId, graph.getNodeCount());

        // Save metadata
        saveGraphMetadata(graph);

        // Save nodes with embeddings
        saveNodes(graph);

        // Save adjacency matrices
        saveAdjacencyMatrices(graph);

        // Save node embedding matrix (if present)
        saveNodeEmbeddings(graph);

        // Update cache
        graphCache.put(graphId, graph);

        // Commit to vector store
        vectorStore.flushAndCommit();
    }

    @Override
    public boolean deleteGraph(String graphId) {
        try {
            // Remove from cache
            AdjacencyMatrixGraph graph = graphCache.remove(graphId);
            if (graph != null) {
                graph.close();
            }

            // Stream-scan the index page-by-page and collect only the IDs belonging
            // to this graph.  We never accumulate docs from other graphs in heap —
            // each page is discarded after filtering.
            String targetPrefix = GRAPH_PREFIX + graphId;
            List<String> idsToDelete = collectMatchingIds(targetPrefix);

            // Always add the meta doc so the graph header is removed even if the
            // scanner missed it (e.g. offset race on a concurrent write).
            String metaId = GRAPH_PREFIX + graphId + META_SUFFIX;
            if (!idsToDelete.contains(metaId)) {
                idsToDelete.add(metaId);
            }

            if (!idsToDelete.isEmpty()) {
                vectorStore.delete(idsToDelete);
                vectorStore.flushAndCommit();
            }

            return true;
        } catch (Exception e) {
            log.error("Failed to delete graph {}", graphId, e);
            return false;
        }
    }

    @Override
    public Set<String> getLoadedGraphIds() {
        // In-memory cache keys — complete after rehydrateGraphsOnStartup() and updated by
        // getOrCreateGraph/createGraph. O(1) snapshot; avoids the full-index scan of listGraphs().
        return new LinkedHashSet<>(graphCache.keySet());
    }

    @Override
    public List<String> listGraphs() {
        List<String> graphIds = new ArrayList<>();
        try {
            // Stream page-by-page; only retain `:meta` docs — discard everything else
            // per page so heap holds at most vectorScanPageSize docs at a time.
            int offset = 0;
            List<Map<String, Object>> page;
            do {
                page = vectorStore.listVectorDocuments(offset, vectorScanPageSize);
                for (Map<String, Object> rawDoc : page) {
                    String docId = (String) rawDoc.get("id");
                    if (docId == null || !docId.startsWith(GRAPH_PREFIX) || !docId.endsWith(META_SUFFIX)) {
                        continue;
                    }
                    // Confirm the document is actually a graph_metadata record (the type field
                    // may be nested under "metadata" in the Anserini VectorStore response).
                    Map<String, Object> doc = flattenDoc(rawDoc);
                    if ("graph_metadata".equals(doc.get("type"))) {
                        String graphId = docId.substring(GRAPH_PREFIX.length(),
                                docId.length() - META_SUFFIX.length());
                        graphIds.add(graphId);
                    }
                    // Non-matching docs are not retained — GC'd at end of page loop
                }
                offset += page.size();
            } while (page.size() == vectorScanPageSize);
        } catch (Exception e) {
            log.error("Failed to list graphs", e);
        }
        return graphIds;
    }

    @Override
    public List<String> listGraphsByFactSheet(Long factSheetId) {
        return listGraphs().stream()
                .filter(graphId -> {
                    Optional<AdjacencyMatrixGraph> graph = loadGraph(graphId);
                    return graph.isPresent() &&
                            Objects.equals(graph.get().getFactSheetId(), factSheetId);
                })
                .collect(Collectors.toList());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NODE OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public int addNode(String graphId, MatrixGraphNode node) {
        AdjacencyMatrixGraph graph = getOrCreateGraph(graphId);
        int index = graph.addNode(node);

        // Persist node
        try {
            saveNode(graphId, node);
        } catch (Exception e) {
            log.error("Failed to persist node {} in graph {}", node.getNodeId(), graphId, e);
        }

        return index;
    }

    @Override
    public void updateNode(String graphId, MatrixGraphNode node) {
        AdjacencyMatrixGraph graph = graphCache.get(graphId);
        if (graph != null) {
            graph.addNode(node); // addNode handles updates
        }

        try {
            saveNode(graphId, node);
        } catch (Exception e) {
            log.error("Failed to update node {} in graph {}", node.getNodeId(), graphId, e);
        }
    }

    @Override
    public void updateNodeMetadata(String graphId, MatrixGraphNode node) {
        AdjacencyMatrixGraph graph = graphCache.get(graphId);
        INDArray existing = null;
        if (graph != null) {
            // Capture the existing vector (a copy — getRow returns a view) BEFORE re-adding,
            // then update the node struct/metadata in place.
            INDArray current = graph.getNodeEmbedding(node.getNodeId());
            if (current != null && !current.isEmpty()) {
                existing = current.dup();
            }
            graph.addNode(node);
        }

        try {
            Document doc = createNodeDocument(graphId, node);
            if (existing != null) {
                // Metadata-only update: the embedding text is unchanged, so reuse the existing
                // vector instead of paying for a full re-embed (the slow 1-text-at-a-time path).
                INDArray row = existing.rank() == 1
                        ? existing.reshape(1, existing.length())
                        : existing;
                vectorStore.addWithEmbeddings(List.of(doc), row);
            } else {
                // No cached sentence embedding available in the adjacency matrix (common for nodes
                // whose embeddings are in Lucene but were not loaded into the in-memory matrix via
                // storeNodeEmbeddings). SKIP the vectorStore.add call here to prevent enqueueing
                // a sentence re-embed in the async pool — which is the root cause of the post-KGE
                // OOM (5 903 concurrent re-embeds after training). The in-memory adjacency matrix
                // has already been updated (graph.addNode above). The metadata change will be
                // durably persisted to Lucene on the next flush()/saveGraph() call, which the
                // caller (MatrixKgEmbeddingGraphAdapter.storeEmbeddings) triggers via
                // knowledgeGraphService.flushPendingNodes() after the batch write.
                if (graph != null) {
                    log.debug("updateNodeMetadata: no cached embedding for node {} in graph {} — "
                            + "in-memory updated, Lucene will be persisted on next flush",
                            node.getNodeId(), graphId);
                } else {
                    // Graph not in cache at all — fall back to re-embed so the node reaches Lucene.
                    log.debug("updateNodeMetadata: graph {} not in cache for node {} — "
                            + "falling back to full re-embed", graphId, node.getNodeId());
                    vectorStore.add(List.of(doc));
                }
            }
        } catch (Exception e) {
            log.error("Failed metadata-only update for node {} in graph {}", node.getNodeId(), graphId, e);
        }
    }

    @Override
    public boolean removeNode(String graphId, String nodeId) {
        AdjacencyMatrixGraph graph = graphCache.get(graphId);
        if (graph != null) {
            graph.removeNode(nodeId);
        }

        String docId = GRAPH_PREFIX + graphId + NODE_PREFIX + nodeId;
        return vectorStore.delete(List.of(docId));
    }

    @Override
    public Optional<MatrixGraphNode> getNode(String graphId, String nodeId) {
        AdjacencyMatrixGraph graph = graphCache.get(graphId);
        if (graph != null) {
            return graph.getNode(nodeId);
        }

        // Load from vector store
        return loadGraph(graphId).flatMap(g -> g.getNode(nodeId));
    }

    @Override
    public List<MatrixGraphNode> getAllNodes(String graphId) {
        AdjacencyMatrixGraph graph = graphCache.get(graphId);
        if (graph != null) {
            return graph.getAllNodes();
        }

        return loadGraph(graphId)
                .map(AdjacencyMatrixGraph::getAllNodes)
                .orElse(Collections.emptyList());
    }

    @Override
    public List<MatrixGraphNode> searchNodes(String graphId, String query, int limit) {
        // Use vector store similarity search
        List<Document> results = vectorStore.similaritySearch(query, limit);

        String nodePrefix = GRAPH_PREFIX + graphId + NODE_PREFIX;
        return results.stream()
                .filter(doc -> doc.getId() != null && doc.getId().startsWith(nodePrefix))
                .map(doc -> deserializeNode(doc))
                .filter(Objects::nonNull)
                .limit(limit)
                .collect(Collectors.toList());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EDGE OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public boolean addEdge(String graphId, String sourceNodeId, String targetNodeId,
                          double weight, String edgeType, boolean bidirectional) {
        AdjacencyMatrixGraph graph = getOrCreateGraph(graphId);
        return graph.addEdge(sourceNodeId, targetNodeId, weight, edgeType, bidirectional);
    }

    @Override
    public boolean addEdge(String graphId, String sourceNodeId, String targetNodeId,
                          double weight, String edgeType, boolean bidirectional, String relationType) {
        AdjacencyMatrixGraph graph = getOrCreateGraph(graphId);
        return graph.addEdge(sourceNodeId, targetNodeId, weight, edgeType, bidirectional, relationType);
    }

    /** [M-7] Full-metadata override — persists confidence and description alongside weight/relationType. */
    @Override
    public boolean addEdge(String graphId, String sourceNodeId, String targetNodeId,
                           double weight, String edgeType, boolean bidirectional,
                           String relationType, Double confidence, String description) {
        AdjacencyMatrixGraph graph = getOrCreateGraph(graphId);
        return graph.addEdge(sourceNodeId, targetNodeId, weight, edgeType, bidirectional,
                relationType, confidence, description);
    }

    @Override
    public boolean mergeEdgeMetadata(String graphId,
                                     String sourceNodeId,
                                     String targetNodeId,
                                     String edgeType,
                                     Map<String, Object> additionalMetadata) {
        AdjacencyMatrixGraph graph = getOrCreateGraph(graphId);
        return graph.mergeEdgeMetadata(edgeType, sourceNodeId, targetNodeId, additionalMetadata);
    }

    @Override
    public boolean removeEdge(String graphId, String sourceNodeId, String targetNodeId, String edgeType) {
        AdjacencyMatrixGraph graph = graphCache.get(graphId);
        if (graph != null) {
            return graph.removeEdge(sourceNodeId, targetNodeId, edgeType);
        }
        return false;
    }

    @Override
    public List<Map.Entry<String, Double>> getEdges(String graphId, String nodeId, String edgeType) {
        AdjacencyMatrixGraph graph = graphCache.get(graphId);
        if (graph != null) {
            return graph.getNeighbors(nodeId, edgeType);
        }

        return loadGraph(graphId)
                .map(g -> g.getNeighbors(nodeId, edgeType))
                .orElse(Collections.emptyList());
    }

    @Override
    public boolean hasEdge(String graphId, String sourceNodeId, String targetNodeId, String edgeType) {
        AdjacencyMatrixGraph graph = graphCache.get(graphId);
        if (graph != null) {
            return graph.hasEdge(sourceNodeId, targetNodeId, edgeType);
        }

        return loadGraph(graphId)
                .map(g -> g.hasEdge(sourceNodeId, targetNodeId, edgeType))
                .orElse(false);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EMBEDDING OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public void storeNodeEmbeddings(String graphId, List<String> nodeIds, INDArray embeddings) {
        AdjacencyMatrixGraph graph = getOrCreateGraph(graphId);
        graph.setNodeEmbeddings(nodeIds, embeddings);

        // Store embeddings in vector store
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < nodeIds.size(); i++) {
            String nodeId = nodeIds.get(i);
            Optional<MatrixGraphNode> nodeOpt = graph.getNode(nodeId);
            if (nodeOpt.isPresent()) {
                MatrixGraphNode node = nodeOpt.get();
                Document doc = createNodeDocument(graphId, node);
                documents.add(doc);
            }
        }

        if (!documents.isEmpty()) {
            vectorStore.addWithEmbeddings(documents, embeddings);
            vectorStore.flushAndCommit();
        }
    }

    @Override
    public INDArray getNodeEmbeddings(String graphId, List<String> nodeIds) {
        AdjacencyMatrixGraph graph = graphCache.get(graphId);
        if (graph != null && graph.getNodeEmbeddings() != null) {
            int dim = graph.getEmbeddingDimension();
            INDArray result = Nd4j.zeros(DataType.FLOAT, nodeIds.size(), dim);

            for (int i = 0; i < nodeIds.size(); i++) {
                INDArray embedding = graph.getNodeEmbedding(nodeIds.get(i));
                if (embedding != null) {
                    result.putRow(i, embedding);
                }
            }
            return result;
        }
        return null;
    }

    @Override
    public List<Map.Entry<String, Double>> findSimilarNodes(String graphId, INDArray queryEmbedding,
                                                             int k, double threshold) {
        List<ScoredDocument> results = vectorStore.similaritySearchWithScores(queryEmbedding, k, threshold);

        String nodePrefix = GRAPH_PREFIX + graphId + NODE_PREFIX;
        return results.stream()
                .filter(sd -> sd.document().getId() != null &&
                        sd.document().getId().startsWith(nodePrefix))
                .map(sd -> {
                    String nodeId = sd.document().getId().substring(nodePrefix.length());
                    return new AbstractMap.SimpleEntry<>(nodeId, sd.score());
                })
                .collect(Collectors.toList());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BULK OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public int addNodesBatch(String graphId, List<MatrixGraphNode> nodes) {
        AdjacencyMatrixGraph graph = getOrCreateGraph(graphId);
        int count = 0;

        List<Document> documents = new ArrayList<>();
        for (MatrixGraphNode node : nodes) {
            graph.addNode(node);
            documents.add(createNodeDocument(graphId, node));
            count++;
        }

        if (!documents.isEmpty()) {
            vectorStore.add(documents);
        }

        return count;
    }

    @Override
    public int addEdgesBatch(String graphId, List<EdgeDefinition> edges) {
        AdjacencyMatrixGraph graph = getOrCreateGraph(graphId);
        int count = 0;

        for (EdgeDefinition edge : edges) {
            if (graph.addEdge(edge.sourceNodeId(), edge.targetNodeId(),
                    edge.weight(), edge.edgeType(), edge.bidirectional(), edge.relationType())) {
                count++;
            }
        }

        return count;
    }

    @Override
    public void flush() {
        // Save all cached graphs
        for (AdjacencyMatrixGraph graph : graphCache.values()) {
            try {
                saveGraph(graph);
            } catch (IOException e) {
                log.error("Failed to flush graph {}", graph.getGraphId(), e);
            }
        }
        vectorStore.flushAndCommit();
    }

    @Override
    public void awaitPendingEmbeddings() {
        vectorStore.awaitPendingEmbeddings();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATISTICS
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public Map<String, Object> getGraphStatistics(String graphId) {
        AdjacencyMatrixGraph graph = graphCache.get(graphId);
        if (graph != null) {
            return graph.getStatistics();
        }

        return loadGraph(graphId)
                .map(AdjacencyMatrixGraph::getStatistics)
                .orElse(Collections.emptyMap());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private AdjacencyMatrixGraph getOrCreateGraph(String graphId) {
        return graphCache.computeIfAbsent(graphId, id -> {
            Optional<AdjacencyMatrixGraph> loaded = loadGraph(id);
            if (loaded.isPresent()) {
                return loaded.get();
            }
            // Brand-new graph (e.g. the first addNode for a per-fact-sheet graph). Persist its
            // :meta doc immediately so listGraphs() can discover it — and rehydrateGraphsOnStartup()
            // can reload it — even if a flush()/saveGraph() never runs. Without this, node docs are
            // written under graph:{id}:node:* but the graph has no :meta record, so after a restart it
            // is invisible to listGraphs() and its nodes (tables/entities) silently disappear.
            AdjacencyMatrixGraph g = new AdjacencyMatrixGraph(id, 1024);
            try {
                saveGraphMetadata(g);
            } catch (IOException e) {
                log.error("Failed to persist initial metadata for graph {}", id, e);
            }
            return g;
        });
    }

    private void saveGraphMetadata(AdjacencyMatrixGraph graph) throws IOException {
        String docId = GRAPH_PREFIX + graph.getGraphId() + META_SUFFIX;

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("graphId", graph.getGraphId());
        metadata.put("factSheetId", graph.getFactSheetId());
        metadata.put("nodeCount", graph.getNodeCount());
        metadata.put("edgeTypes", new ArrayList<>(graph.getEdgeTypes()));
        metadata.put("capacity", graph.getCurrentCapacity());
        metadata.put("embeddingDimension", graph.getEmbeddingDimension());
        metadata.put("type", "graph_metadata");
        metadata.values().removeIf(Objects::isNull);

        Document doc = new Document(docId, objectMapper.writeValueAsString(metadata), metadata);
        vectorStore.add(List.of(doc));
    }

    /**
     * Saves all live nodes with a correctly-aligned embedding matrix.
     *
     * <p><b>Save-side fix:</b> {@link AdjacencyMatrixGraph#getNodeEmbeddings()} may return a
     * matrix whose row count equals {@code nextIndex} (total ever-assigned indices), not
     * {@code nodeById.size()} (live node count after removals). Passing the oversized matrix to
     * {@code addWithEmbeddings} causes a rows≠docs mismatch in the Anserini impl (which then
     * falls back to re-embedding — slow and lossy).
     *
     * <p>We build a compact {@code [liveNodes × embDim]} matrix by reading each live node's
     * persisted {@code matrixIndex} as the row offset into the full embedding array and writing
     * it at position {@code i} (matching {@code documents.get(i)}). Row-to-document
     * correspondence is therefore exact and the rows==docs check passes cleanly.</p>
     */
    private void saveNodes(AdjacencyMatrixGraph graph) {
        INDArray fullEmbeddings = graph.getNodeEmbeddings();
        int embDim = graph.getEmbeddingDimension();
        List<MatrixGraphNode> liveNodes = graph.getAllNodes();

        List<Document> documents = new ArrayList<>(liveNodes.size());
        INDArray alignedEmbeddings = null;

        if (fullEmbeddings != null && embDim > 0 && !liveNodes.isEmpty()) {
            alignedEmbeddings = Nd4j.zeros(DataType.FLOAT, liveNodes.size(), embDim);
        }

        for (int i = 0; i < liveNodes.size(); i++) {
            MatrixGraphNode node = liveNodes.get(i);
            documents.add(createNodeDocument(graph.getGraphId(), node));
            if (alignedEmbeddings != null) {
                int idx = node.getMatrixIndex();
                if (idx >= 0 && idx < fullEmbeddings.rows()) {
                    alignedEmbeddings.putRow(i, fullEmbeddings.getRow(idx));
                }
                // idx out of range → row stays zero (no embedding for this node yet)
            }
        }

        if (!documents.isEmpty()) {
            if (alignedEmbeddings != null) {
                vectorStore.addWithEmbeddings(documents, alignedEmbeddings);
                alignedEmbeddings.close();
            } else {
                vectorStore.add(documents);
            }
        }
    }

    private void saveNode(String graphId, MatrixGraphNode node) {
        Document doc = createNodeDocument(graphId, node);
        vectorStore.add(List.of(doc));
    }

    private Document createNodeDocument(String graphId, MatrixGraphNode node) {
        String docId = GRAPH_PREFIX + graphId + NODE_PREFIX + node.getNodeId();

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("nodeId", node.getNodeId());
        metadata.put("matrixIndex", node.getMatrixIndex());
        metadata.put("nodeType", node.getNodeType());
        metadata.put("title", node.getTitle());
        metadata.put("description", node.getDescription());
        metadata.put("factSheetId", node.getFactSheetId());
        metadata.put("createdAt", node.getCreatedAt());
        metadata.put("updatedAt", node.getUpdatedAt());
        metadata.put("type", "graph_node");
        metadata.values().removeIf(Objects::isNull);

        if (node.getMetadata() != null) {
            metadata.put("nodeMetadata", node.getMetadata());
        }

        String content = String.format("%s: %s",
                node.getTitle() != null ? node.getTitle() : node.getNodeId(),
                node.getDescription() != null ? node.getDescription() : "");

        return new Document(docId, content, metadata);
    }

    private void saveAdjacencyMatrices(AdjacencyMatrixGraph graph) throws IOException {
        // Serialize each edge type directly from sparse storage — no INDArray needed.
        for (String edgeType : graph.getEdgeTypes()) {
            saveAdjacencyMatrix(graph.getGraphId(), edgeType,
                    graph.getSparseEdges(edgeType));
        }
    }

    private void saveAdjacencyMatrix(String graphId, String edgeType,
                                     AdjacencyMatrixGraph.SparseEdgeData sparseData)
            throws IOException {
        String docId = GRAPH_PREFIX + graphId + ADJ_PREFIX + edgeType;

        // Build the same JSON format as before (list of {source, target, weight} objects),
        // now extended with confidence, bidirectional, and description (M-7) so they survive
        // vector-store round-trips on the @Primary live path. restoreAdjacencyMatrix() reads
        // these fields via the same map-key names — old files without these fields are safe
        // (missing keys are treated as null/absent, preserving backward compatibility).
        List<Map<String, Object>> edges = new ArrayList<>(sparseData.size());
        boolean hasRelationTypes = !sparseData.relationTypes.isEmpty();
        boolean hasEdgeMetas = !sparseData.edgeMetas.isEmpty();  // [M-7]
        for (int i = 0; i < sparseData.size(); i++) {
            int[] pair = sparseData.indices.get(i);
            float weight = sparseData.weights.get(i);
            Map<String, Object> edge = new HashMap<>();
            edge.put("source", pair[0]);
            edge.put("target", pair[1]);
            edge.put("weight", weight);
            // Persist the explicit semantic relation as a first-class field alongside the weight.
            String relationType = hasRelationTypes ? sparseData.relationTypes.get(i) : null;
            if (relationType != null) {
                edge.put("relationType", relationType);
            }
            // [M-7] Persist confidence, bidirectional, description when present.
            if (hasEdgeMetas) {
                AdjacencyMatrixGraph.EdgeMeta meta = sparseData.edgeMetas.get(i);
                if (meta != null) {
                    if (meta.confidence() != null)   edge.put("confidence",   meta.confidence());
                    if (Boolean.TRUE.equals(meta.bidirectional())) edge.put("bidirectional", true);
                    if (meta.description() != null && !meta.description().isBlank())
                        edge.put("description", meta.description());
                    if (meta.metadata() != null && !meta.metadata().isEmpty())
                        edge.put("metadata", meta.metadata());
                }
            }
            edges.add(edge);
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("graphId", graphId);
        metadata.put("edgeType", edgeType);
        metadata.put("edgeCount", edges.size());
        metadata.put("type", "adjacency_matrix");

        String content = objectMapper.writeValueAsString(edges);
        Document doc = new Document(docId, content, metadata);
        vectorStore.add(List.of(doc));
    }

    /**
     * Persists the node embedding matrix from {@code graph.getNodeEmbeddings()} to the vector
     * store as a single document with id {@code graph:{graphId}:embd}.
     *
     * <p>Format: a JSON array where each entry is
     * {@code {"storedIndex": N, "values": [f0, f1, …, fd-1]}} using the node's current
     * {@code matrixIndex} as the {@code storedIndex}.  On reload,
     * {@link #restoreNodeEmbeddings} uses the same {@code storedIndexToNodeId} map that
     * edge-restore uses, so a reordered reload still aligns every vector to the right node.
     * If no embeddings are present the document is not written (cold case is handled gracefully
     * in the load path).</p>
     */
    private void saveNodeEmbeddings(AdjacencyMatrixGraph graph) throws IOException {
        INDArray embd = graph.getNodeEmbeddings();
        if (embd == null) {
            return; // nothing to persist
        }

        String graphId = graph.getGraphId();
        int dim = (int) embd.columns();

        // Build a list of { storedIndex, values } entries — one per node that has a non-zero row.
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MatrixGraphNode node : graph.getAllNodes()) {
            int idx = node.getMatrixIndex();
            if (idx < 0 || idx >= embd.rows()) {
                continue;
            }
            INDArray row = embd.getRow(idx);
            List<Float> values = new ArrayList<>(dim);
            for (int c = 0; c < dim; c++) {
                values.add(row.getFloat(c));
            }
            Map<String, Object> entry = new HashMap<>();
            entry.put("storedIndex", idx);
            entry.put("values", values);
            rows.add(entry);
        }

        if (rows.isEmpty()) {
            return; // all-zero or no nodes → skip
        }

        String docId = GRAPH_PREFIX + graphId + EMBD_SUFFIX;
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("graphId", graphId);
        metadata.put("embeddingDim", dim);
        metadata.put("type", "node_embeddings");

        String content = objectMapper.writeValueAsString(rows);
        Document doc = new Document(docId, content, metadata);
        vectorStore.add(List.of(doc));
        log.debug("Persisted node embeddings for graph '{}': {} rows × {} dim", graphId, rows.size(), dim);
    }

    /**
     * Streams through the entire vector-store index page-by-page and returns the
     * IDs of every document whose {@code id} starts with {@code idPrefix}.
     *
     * <p>Unlike the old {@code listAllVectorDocuments()} — which accumulated every
     * document in heap — this method retains only the matching IDs (strings, not
     * the full map payload).  Heap usage per call is therefore bounded by
     * {@code vectorScanPageSize} raw docs (transient) plus the matched ID strings
     * (persistent), never the full index.  The VectorStore API does not expose a
     * native prefix/term query, so a full scan is unavoidable; the key improvement
     * is that non-matching docs are discarded inside the loop rather than
     * accumulated into a giant list.</p>
     *
     * @param idPrefix the document-ID prefix to match (e.g. {@code "graph:myId"})
     * @return mutable list of matching document IDs
     */
    private List<String> collectMatchingIds(String idPrefix) {
        List<String> matched = new ArrayList<>();
        int offset = 0;
        List<Map<String, Object>> page;
        do {
            page = vectorStore.listVectorDocuments(offset, vectorScanPageSize);
            for (Map<String, Object> doc : page) {
                String docId = (String) doc.get("id");
                if (docId != null && docId.startsWith(idPrefix)) {
                    matched.add(docId);
                }
                // Non-matching docs are not retained — released at end of page
            }
            offset += page.size();
        } while (page.size() == vectorScanPageSize);
        return matched;
    }

    /**
     * Flatten the map returned by {@link VectorStore#listVectorDocuments} so that
     * per-document metadata fields are accessible at the top level.
     *
     * <p>{@code listVectorDocuments} returns a map whose top-level keys are
     * {@code "id"}, {@code "content"} (or {@code "preview"}), and
     * {@code "metadata"} — a nested {@code Map<String,Object>} parsed from the
     * Lucene {@code "metadata"} stored-field JSON.  All the graph-specific fields
     * (e.g. {@code "type"}, {@code "nodeId"}, {@code "edgeType"}, …) live inside
     * that nested map.  The deserialization helpers expect them at the top level,
     * so we merge the nested map up before passing the doc along.</p>
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> flattenDoc(Map<String, Object> doc) {
        Object nested = doc.get("metadata");
        if (!(nested instanceof Map)) {
            return doc; // nothing to flatten — return as-is
        }
        Map<String, Object> flat = new HashMap<>(doc);
        flat.putAll((Map<String, Object>) nested);
        return flat;
    }

    private Optional<AdjacencyMatrixGraph> loadGraphFromVectorStore(String graphId) {
        // Page through the full index and keep ONLY docs whose id starts with the
        // target graph prefix.  Non-matching docs are discarded after each page so
        // heap holds at most vectorScanPageSize raw docs at a time — never the whole
        // index at once (which OOM'd at 6 000-node / 70 000-edge scale).
        String graphPrefix = GRAPH_PREFIX + graphId;

        // Find metadata document
        Map<String, Object> metaDoc = null;
        List<Map<String, Object>> nodeDocs = new ArrayList<>();
        List<Map<String, Object>> adjDocs = new ArrayList<>();
        Map<String, Object> embdDoc = null; // single node-embeddings document per graph

        int offset = 0;
        List<Map<String, Object>> page;
        do {
            page = vectorStore.listVectorDocuments(offset, vectorScanPageSize);
            for (Map<String, Object> rawDoc : page) {
                String docId = (String) rawDoc.get("id");
                if (docId == null || !docId.startsWith(graphPrefix)) {
                    // Not this graph's doc — drop it immediately, do not accumulate
                    continue;
                }

                // Flatten nested "metadata" map to the top level so that "type",
                // "nodeId", "edgeType", etc. are directly accessible.
                Map<String, Object> doc = flattenDoc(rawDoc);

                String type = (String) doc.get("type");
                if ("graph_metadata".equals(type)) {
                    metaDoc = doc;
                } else if ("graph_node".equals(type)) {
                    nodeDocs.add(doc);
                } else if ("adjacency_matrix".equals(type)) {
                    adjDocs.add(doc);
                } else if ("node_embeddings".equals(type)) {
                    embdDoc = doc;
                }
                // Unknown types are silently discarded
            }
            offset += page.size();
        } while (page.size() == vectorScanPageSize);

        if (metaDoc == null) {
            log.debug("No graph_metadata document found for graphId='{}' in vector store", graphId);
            return Optional.empty();
        }

        // Reconstruct graph
        Object capObj = metaDoc.get("capacity");
        int capacity = (capObj instanceof Number) ? ((Number) capObj).intValue() : 1024;
        AdjacencyMatrixGraph graph = new AdjacencyMatrixGraph(graphId, capacity);

        Object factSheetIdObj = metaDoc.get("factSheetId");
        if (factSheetIdObj instanceof Number) {
            graph.setFactSheetId(((Number) factSheetIdObj).longValue());
        }

        // Add nodes and build a persisted-index → nodeId lookup.
        // AdjacencyMatrixGraph.addNode() assigns NEW runtime indices via nextIndex;
        // it does NOT honour the stored matrixIndex.  Edges in the adjacency-matrix
        // documents are keyed by the STORED indices, so we need this side-map to
        // translate them back to nodeIds before calling graph.addEdge(nodeId, …).
        Map<Integer, String> storedIndexToNodeId = new HashMap<>();
        for (Map<String, Object> nodeDoc : nodeDocs) {
            MatrixGraphNode node = deserializeNodeFromMetadata(nodeDoc);
            if (node != null) {
                // Capture the persisted index BEFORE addNode() overwrites it.
                int persistedIndex = node.getMatrixIndex();
                graph.addNode(node);
                if (persistedIndex >= 0) {
                    storedIndexToNodeId.put(persistedIndex, node.getNodeId());
                }
            }
        }

        // Restore adjacency matrices using the persisted-index → nodeId map.
        int totalEdgesFailed = 0;
        for (Map<String, Object> adjDoc : adjDocs) {
            totalEdgesFailed += restoreAdjacencyMatrix(graph, adjDoc, storedIndexToNodeId);
        }
        if (totalEdgesFailed > 0) {
            log.warn("Graph '{}': {} edges could not be resolved and were dropped "
                    + "(nodes may have been removed since the last save)", graphId, totalEdgesFailed);
        }

        // Restore node embedding matrix (if one was persisted).
        if (embdDoc != null) {
            restoreNodeEmbeddings(graph, embdDoc, storedIndexToNodeId);
        }

        log.info("Loaded graph '{}' from vector store: {} nodes, {} edge-type buckets, embeddings={}",
                graphId, graph.getNodeCount(), adjDocs.size(), embdDoc != null);
        graphCache.put(graphId, graph);
        return Optional.of(graph);
    }

    private MatrixGraphNode deserializeNode(Document doc) {
        if (doc == null || doc.getMetadata() == null) {
            return null;
        }
        return deserializeNodeFromMetadata(doc.getMetadata());
    }

    @SuppressWarnings("unchecked")
    private MatrixGraphNode deserializeNodeFromMetadata(Map<String, Object> metadata) {
        try {
            String nodeId = (String) metadata.get("nodeId");
            if (nodeId == null || nodeId.isBlank()) {
                return null; // skip non-node docs that slipped through
            }
            MatrixGraphNode.MatrixGraphNodeBuilder builder = MatrixGraphNode.builder()
                    .nodeId(nodeId)
                    .nodeType((String) metadata.get("nodeType"))
                    .title((String) metadata.get("title"))
                    .description((String) metadata.get("description"));

            Object matrixIndex = metadata.get("matrixIndex");
            if (matrixIndex instanceof Number) {
                builder.matrixIndex(((Number) matrixIndex).intValue());
            }

            Object factSheetId = metadata.get("factSheetId");
            if (factSheetId instanceof Number) {
                builder.factSheetId(((Number) factSheetId).longValue());
            }

            Object createdAt = metadata.get("createdAt");
            if (createdAt instanceof Number) {
                builder.createdAt(((Number) createdAt).longValue());
            }

            Object updatedAt = metadata.get("updatedAt");
            if (updatedAt instanceof Number) {
                builder.updatedAt(((Number) updatedAt).longValue());
            }

            Object nodeMetadata = metadata.get("nodeMetadata");
            if (nodeMetadata instanceof Map) {
                builder.metadata((Map<String, Object>) nodeMetadata);
            }

            return builder.build();
        } catch (Exception e) {
            log.error("Failed to deserialize node", e);
            return null;
        }
    }

    /**
     * Restores an adjacency matrix from a persisted document, using the provided
     * {@code storedIndexToNodeId} map to translate stored integer indices back to
     * node IDs.
     *
     * <p><b>Why a separate index map?</b>
     * {@link AdjacencyMatrixGraph#addNode} always assigns <em>new</em> runtime indices
     * via an internal {@code AtomicInteger}, ignoring the {@code matrixIndex} field stored
     * in the node document.  The adjacency-matrix JSON encodes edges by the <em>stored</em>
     * (persisted) indices, not the new runtime ones.  Passing in the persisted mapping
     * (built in {@link #loadGraphFromVectorStore} before any node is added) ensures we
     * look up the right nodeIds regardless of the order in which nodes are re-added.</p>
     *
     * @return the count of edges that could NOT be resolved (for aggregate logging)
     */
    @SuppressWarnings("unchecked")
    private int restoreAdjacencyMatrix(AdjacencyMatrixGraph graph, Map<String, Object> adjDoc,
                                       Map<Integer, String> storedIndexToNodeId) {
        int failCount = 0;
        try {
            String edgeType = (String) adjDoc.get("edgeType");
            Object content = adjDoc.get("content");

            if (content instanceof String contentStr) {
                List<Map<String, Object>> edges = objectMapper.readValue(
                        contentStr,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class)
                );

                for (Map<String, Object> edge : edges) {
                    if (!(edge.get("source") instanceof Number srcNum)
                            || !(edge.get("target") instanceof Number tgtNum)
                            || !(edge.get("weight") instanceof Number wgtNum)) {
                        log.warn("Skipping edge with missing source/target/weight fields in edgeType={}", edgeType);
                        failCount++;
                        continue;
                    }
                    int source = srcNum.intValue();
                    int target = tgtNum.intValue();
                    double weight = wgtNum.doubleValue();
                    Object rtObj = edge.get("relationType");
                    String relationType = rtObj instanceof String s ? s : null;

                    // [M-7] Read confidence, bidirectional, description from the stored JSON.
                    Double confidence = edge.get("confidence") instanceof Number cn ? cn.doubleValue() : null;
                    boolean bidirectional = Boolean.TRUE.equals(edge.get("bidirectional"));
                    Object descObj = edge.get("description");
                    String description = descObj instanceof String ds ? ds : null;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> metadata = edge.get("metadata") instanceof Map<?, ?> map
                            ? (Map<String, Object>) map
                            : null;

                    // Resolve nodeIds from the PERSISTED index map, not the runtime one.
                    // (The runtime map uses new indices assigned by addNode; the stored
                    // indices come from the original crawl and must be resolved separately.)
                    String sourceId = storedIndexToNodeId.get(source);
                    String targetId = storedIndexToNodeId.get(target);

                    if (sourceId != null && targetId != null) {
                        graph.addEdge(sourceId, targetId, weight, edgeType, bidirectional, relationType,
                                confidence, description);  // [M-7]
                        if (metadata != null && !metadata.isEmpty()) {
                            graph.mergeEdgeMetadata(edgeType, sourceId, targetId, metadata);
                        }
                    } else {
                        // Demoted to DEBUG: the caller aggregates the count and emits a single WARN.
                        log.debug("Could not resolve edge source={} target={} for edgeType={}; "
                                + "storedIndexToNodeId has {} entries — node may have been removed",
                                source, target, edgeType, storedIndexToNodeId.size());
                        failCount++;
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to restore adjacency matrix", e);
        }
        return failCount;
    }

    /**
     * Reads the persisted node-embedding document and populates {@code graph.nodeEmbeddings}
     * so that {@link AdjacencyMatrixGraph#getNodeEmbeddings()} returns the original matrix.
     *
     * <p>Row alignment: each stored entry carries a {@code storedIndex} (the original
     * {@code matrixIndex} at save time).  We look it up in {@code storedIndexToNodeId} to get
     * the node ID, then look up the node's <em>current</em> runtime {@code matrixIndex} in the
     * freshly-reconstructed graph.  This mirrors exactly the approach used for edge restore,
     * so a reordered reload still aligns each vector to its correct node.</p>
     *
     * <p>Cold case: if {@code embdDoc} is null or the content is empty/unparseable, this method
     * is a no-op (leaves {@code nodeEmbeddings} null).  Dim/shape mismatches are logged and
     * skipped per-row rather than aborting the whole restore.</p>
     */
    @SuppressWarnings("unchecked")
    private void restoreNodeEmbeddings(AdjacencyMatrixGraph graph,
                                       Map<String, Object> embdDoc,
                                       Map<Integer, String> storedIndexToNodeId) {
        try {
            Object dimObj = embdDoc.get("embeddingDim");
            int dim = (dimObj instanceof Number n) ? n.intValue() : 0;
            if (dim <= 0) {
                log.warn("restoreNodeEmbeddings: embeddingDim missing or 0 — skipping");
                return;
            }

            Object content = embdDoc.get("content");
            if (!(content instanceof String json) || json.isBlank()) {
                log.warn("restoreNodeEmbeddings: no content in embeddings doc — skipping");
                return;
            }

            List<Map<String, Object>> rows = objectMapper.readValue(
                    json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class)
            );

            if (rows == null || rows.isEmpty()) {
                return;
            }

            int nodeCount = graph.getNodeCount();
            INDArray matrix = Nd4j.zeros(DataType.FLOAT, nodeCount, dim);

            int restored = 0;
            for (Map<String, Object> entry : rows) {
                Object siObj = entry.get("storedIndex");
                if (!(siObj instanceof Number siNum)) {
                    continue;
                }
                int storedIdx = siNum.intValue();
                String nodeId = storedIndexToNodeId.get(storedIdx);
                if (nodeId == null) {
                    log.debug("restoreNodeEmbeddings: no nodeId for storedIndex={} — skipping row", storedIdx);
                    continue;
                }

                // Resolve the CURRENT runtime index for this node.
                int currentIdx = graph.getNode(nodeId)
                        .map(MatrixGraphNode::getMatrixIndex)
                        .orElse(-1);
                if (currentIdx < 0 || currentIdx >= nodeCount) {
                    log.debug("restoreNodeEmbeddings: nodeId={} currentIdx={} out of range — skipping", nodeId, currentIdx);
                    continue;
                }

                Object valuesObj = entry.get("values");
                if (!(valuesObj instanceof List<?> valuesList)) {
                    continue;
                }
                if (valuesList.size() != dim) {
                    log.warn("restoreNodeEmbeddings: nodeId={} has {} values, expected {} — skipping row",
                            nodeId, valuesList.size(), dim);
                    continue;
                }

                for (int c = 0; c < dim; c++) {
                    Object v = valuesList.get(c);
                    float f = (v instanceof Number vn) ? vn.floatValue() : 0f;
                    matrix.putScalar(currentIdx, c, f);
                }
                restored++;
            }

            // Values are already written into `matrix` at the correct current indices.
            // Inject directly via the Lombok-generated field setter (avoids the per-nodeId copy
            // loop in the public setNodeEmbeddings(List,INDArray) overload, which would also
            // re-allocate the backing array and overwrite our carefully aligned rows).
            graph.setNodeEmbeddings(matrix);
            graph.setEmbeddingDimension(dim);
            log.debug("Restored node embeddings for graph '{}': {}/{} rows × {} dim",
                    graph.getGraphId(), restored, rows.size(), dim);
        } catch (Exception e) {
            log.error("Failed to restore node embeddings for graph '{}'", graph.getGraphId(), e);
        }
    }
}
