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

import ai.kompile.knowledgegraph.matrix.model.AdjacencyMatrixGraph;
import ai.kompile.knowledgegraph.matrix.model.MatrixGraphNode;
import org.nd4j.linalg.api.ndarray.INDArray;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Interface for persistent storage of matrix-based graphs.
 * <p>
 * Implementations can store graphs in vector stores, file systems,
 * or other persistent storage backends.
 * </p>
 */
public interface MatrixGraphStore {

    // ═══════════════════════════════════════════════════════════════════════════
    // GRAPH LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Creates a new graph with the specified ID.
     *
     * @param graphId     Unique identifier for the graph
     * @param factSheetId Optional fact sheet ID for scoping
     * @return The newly created graph
     */
    AdjacencyMatrixGraph createGraph(String graphId, Long factSheetId);

    /**
     * Loads a graph from persistent storage.
     *
     * @param graphId The graph ID
     * @return The loaded graph, or empty if not found
     */
    Optional<AdjacencyMatrixGraph> loadGraph(String graphId);

    /**
     * Saves a graph to persistent storage.
     *
     * @param graph The graph to save
     * @throws IOException if save fails
     */
    void saveGraph(AdjacencyMatrixGraph graph) throws IOException;

    /**
     * Deletes a graph from persistent storage.
     *
     * @param graphId The graph ID to delete
     * @return true if deleted, false if not found
     */
    boolean deleteGraph(String graphId);

    /**
     * Lists all graph IDs in storage.
     *
     * @return List of graph IDs
     */
    List<String> listGraphs();

    /**
     * Cheap, in-memory enumeration of currently-loaded graph IDs (the in-memory graph-cache keys
     * after eager rehydration). Hot-path helpers that fan out across every per-fact-sheet graph
     * (cross-graph node/edge lookups, global aggregation) use this instead of {@link #listGraphs()},
     * which scans the entire vector index page-by-page and is far too expensive to call per node/edge.
     *
     * <p>Defaults to {@link #listGraphs()} for stores without an in-memory cache; the vector-store
     * implementation overrides it to return its cache keys (complete once startup rehydration runs).</p>
     *
     * @return the set of loaded graph IDs (never null)
     */
    default Set<String> getLoadedGraphIds() {
        List<String> ids = listGraphs();
        return ids == null ? Set.of() : new LinkedHashSet<>(ids);
    }

    /**
     * Merge arbitrary metadata into an existing directed edge without modifying its topology.
     * Used by GNN/link-prediction overlays to attach scores post-hoc.
     *
     * @return true if the edge was found and updated, false otherwise
     */
    default boolean mergeEdgeMetadata(String graphId,
                                      String sourceNodeId,
                                      String targetNodeId,
                                      String edgeType,
                                      Map<String, Object> additionalMetadata) {
        return false;
    }

    /**
     * Lists all graph IDs for a specific fact sheet.
     *
     * @param factSheetId The fact sheet ID
     * @return List of graph IDs
     */
    List<String> listGraphsByFactSheet(Long factSheetId);

    // ═══════════════════════════════════════════════════════════════════════════
    // NODE OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Adds a node to a graph and persists the change.
     *
     * @param graphId The graph ID
     * @param node    The node to add
     * @return The matrix index assigned to the node
     */
    int addNode(String graphId, MatrixGraphNode node);

    /**
     * Updates a node in persistent storage.
     *
     * @param graphId The graph ID
     * @param node    The updated node
     */
    void updateNode(String graphId, MatrixGraphNode node);

    /**
     * Updates a node's struct/metadata WITHOUT re-embedding, reusing the node's existing vector.
     * Use when the embedding-relevant text (title/description) is unchanged — e.g. a metadata-only
     * update such as stashing extracted graph JSON on a DOCUMENT node. Avoids a full per-node
     * re-embed (the costly 1-text-at-a-time path). The default delegates to {@link #updateNode}
     * (which re-embeds) for stores that do not separate the two.
     *
     * @param graphId The graph ID
     * @param node    The updated node (title/description unchanged from the stored version)
     */
    default void updateNodeMetadata(String graphId, MatrixGraphNode node) {
        updateNode(graphId, node);
    }

    /**
     * Removes a node from a graph.
     *
     * @param graphId The graph ID
     * @param nodeId  The node ID to remove
     * @return true if removed
     */
    boolean removeNode(String graphId, String nodeId);

    /**
     * Gets a node from a graph.
     *
     * @param graphId The graph ID
     * @param nodeId  The node ID
     * @return The node, or empty if not found
     */
    Optional<MatrixGraphNode> getNode(String graphId, String nodeId);

    /**
     * Gets all nodes from a graph.
     *
     * @param graphId The graph ID
     * @return List of all nodes
     */
    List<MatrixGraphNode> getAllNodes(String graphId);

    /**
     * Searches nodes by text query.
     *
     * @param graphId The graph ID
     * @param query   Text query
     * @param limit   Maximum results
     * @return Matching nodes
     */
    List<MatrixGraphNode> searchNodes(String graphId, String query, int limit);

    // ═══════════════════════════════════════════════════════════════════════════
    // EDGE OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Adds or updates an edge between two nodes.
     *
     * @param graphId       The graph ID
     * @param sourceNodeId  Source node ID
     * @param targetNodeId  Target node ID
     * @param weight        Edge weight
     * @param edgeType      Edge type
     * @param bidirectional Whether the edge is bidirectional
     * @return true if successful
     */
    boolean addEdge(String graphId, String sourceNodeId, String targetNodeId,
                   double weight, String edgeType, boolean bidirectional);

    /**
     * Adds an edge carrying an explicit semantic {@code relationType} (stored as a first-class
     * per-edge field) in addition to the structural {@code edgeType} routing key. The default
     * delegates to {@link #addEdge(String, String, String, double, String, boolean)} (ignoring
     * relationType); the matrix store overrides it to persist the relation.
     *
     * @param relationType semantic relation, or {@code null} for a purely structural edge
     * @return true if successful
     */
    default boolean addEdge(String graphId, String sourceNodeId, String targetNodeId,
                   double weight, String edgeType, boolean bidirectional, String relationType) {
        return addEdge(graphId, sourceNodeId, targetNodeId, weight, edgeType, bidirectional);
    }

    /**
     * [M-7] Adds an edge with full quality/provenance metadata: relationType, confidence, and
     * description are persisted as first-class per-edge fields so they survive vector-store
     * round-trips. The default delegates to the 7-arg overload (ignoring confidence/description);
     * the {@link VectorStoreMatrixGraphStore} overrides it to persist all fields.
     *
     * @param relationType  semantic relation, or {@code null}
     * @param confidence    edge confidence [0,1], or {@code null}
     * @param description   human-readable description, or {@code null}
     * @return true if successful
     */
    default boolean addEdge(String graphId, String sourceNodeId, String targetNodeId,
                            double weight, String edgeType, boolean bidirectional,
                            String relationType, Double confidence, String description) {
        return addEdge(graphId, sourceNodeId, targetNodeId, weight, edgeType, bidirectional, relationType);
    }

    /**
     * Removes an edge between two nodes.
     *
     * @param graphId      The graph ID
     * @param sourceNodeId Source node ID
     * @param targetNodeId Target node ID
     * @param edgeType     Edge type
     * @return true if removed
     */
    boolean removeEdge(String graphId, String sourceNodeId, String targetNodeId, String edgeType);

    /**
     * Gets all edges for a node.
     *
     * @param graphId  The graph ID
     * @param nodeId   The node ID
     * @param edgeType Edge type (null for all)
     * @return List of edge entries (neighbor ID, weight)
     */
    List<Map.Entry<String, Double>> getEdges(String graphId, String nodeId, String edgeType);

    /**
     * Checks if an edge exists.
     *
     * @param graphId      The graph ID
     * @param sourceNodeId Source node ID
     * @param targetNodeId Target node ID
     * @param edgeType     Edge type
     * @return true if edge exists
     */
    boolean hasEdge(String graphId, String sourceNodeId, String targetNodeId, String edgeType);

    // ═══════════════════════════════════════════════════════════════════════════
    // EMBEDDING OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Stores node embeddings for a graph.
     *
     * @param graphId    The graph ID
     * @param nodeIds    Node IDs in order
     * @param embeddings Embeddings matrix [numNodes, embeddingDim]
     */
    void storeNodeEmbeddings(String graphId, List<String> nodeIds, INDArray embeddings);

    /**
     * Gets embeddings for specific nodes.
     *
     * @param graphId The graph ID
     * @param nodeIds List of node IDs
     * @return Embeddings matrix [numNodes, embeddingDim]
     */
    INDArray getNodeEmbeddings(String graphId, List<String> nodeIds);

    /**
     * Finds similar nodes based on embedding similarity.
     *
     * @param graphId        The graph ID
     * @param queryEmbedding Query embedding vector
     * @param k              Number of results
     * @param threshold      Minimum similarity threshold
     * @return List of similar node IDs with scores
     */
    List<Map.Entry<String, Double>> findSimilarNodes(String graphId, INDArray queryEmbedding,
                                                      int k, double threshold);

    // ═══════════════════════════════════════════════════════════════════════════
    // BULK OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Adds multiple nodes in batch.
     *
     * @param graphId The graph ID
     * @param nodes   List of nodes to add
     * @return Number of nodes added
     */
    int addNodesBatch(String graphId, List<MatrixGraphNode> nodes);

    /**
     * Adds multiple edges in batch.
     *
     * @param graphId The graph ID
     * @param edges   List of edge definitions [sourceId, targetId, weight, edgeType]
     * @return Number of edges added
     */
    int addEdgesBatch(String graphId, List<EdgeDefinition> edges);

    /**
     * Flushes any pending changes to persistent storage.
     */
    void flush();

    /**
     * Blocks until all pending async sentence-embedding tasks (dispatched by
     * {@link ai.kompile.core.embeddings.VectorStore#add}) have completed and their
     * documents are committed to the store.
     *
     * <p>Must be called before {@link #updateNodeMetadata} when the caller cannot guarantee
     * that all sentence embeddings are already present in the adjacency-matrix cache; otherwise
     * {@code updateNodeMetadata} will fall back to a re-embed (the slow per-node path).
     * The default is a no-op; {@link VectorStoreMatrixGraphStore} delegates to
     * {@link ai.kompile.core.embeddings.VectorStore#awaitPendingEmbeddings()}.</p>
     */
    default void awaitPendingEmbeddings() {}

    // ═══════════════════════════════════════════════════════════════════════════
    // STATISTICS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Gets statistics for a graph.
     *
     * @param graphId The graph ID
     * @return Statistics map
     */
    Map<String, Object> getGraphStatistics(String graphId);

    /**
     * Edge definition for batch operations.
     */
    record EdgeDefinition(
            String sourceNodeId,
            String targetNodeId,
            double weight,
            String edgeType,
            boolean bidirectional,
            String relationType
    ) {
        /** Backward-compatible constructor for structural edges (no explicit semantic relation). */
        public EdgeDefinition(String sourceNodeId, String targetNodeId, double weight,
                              String edgeType, boolean bidirectional) {
            this(sourceNodeId, targetNodeId, weight, edgeType, bidirectional, null);
        }
    }
}
