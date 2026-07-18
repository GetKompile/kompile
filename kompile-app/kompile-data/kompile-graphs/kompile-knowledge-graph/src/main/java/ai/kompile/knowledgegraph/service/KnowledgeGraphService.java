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
package ai.kompile.knowledgegraph.service;

import ai.kompile.core.graphrag.maintenance.model.GraphPruneResult;
import ai.kompile.core.kgembedding.KGEmbeddingAlgorithm;
import ai.kompile.knowledgegraph.domain.*;
import org.nd4j.linalg.api.ndarray.INDArray;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Service interface for knowledge graph operations.
 */
public interface KnowledgeGraphService {

    // ═══════════════════════════════════════════════════════════════════════════
    // NODE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Create or update a source node
     *
     * @param externalId External identifier (path, URL, etc.)
     * @param title Display title
     * @param sourceType Type of source (FILE, URL, SLACK, etc.)
     * @param pathOrUrl Path or URL of the source
     * @param metadata Additional metadata
     * @return Created or updated source node
     */
    GraphNode createOrUpdateSourceNode(String externalId, String title, String sourceType,
                                        String pathOrUrl, Map<String, Object> metadata);

    /**
     * Create a document node under a source
     *
     * @param sourceNode Parent source node
     * @param docId External document ID
     * @param title Document title
     * @param metadata Document metadata
     * @return Created document node
     */
    GraphNode createDocumentNode(GraphNode sourceNode, String docId, String title,
                                  Map<String, Object> metadata);

    /**
     * Create a snippet/chunk node under a document
     *
     * @param documentNode Parent document node
     * @param snippetId External snippet ID
     * @param content Snippet content
     * @param chunkIndex Chunk index in the document
     * @return Created snippet node
     */
    GraphNode createSnippetNode(GraphNode documentNode, String snippetId, String content,
                                 int chunkIndex);

    /**
     * Create a snippet/chunk node under a document with metadata.
     * Metadata enriches the snippet title and stores additional context
     * (e.g., sheet name, content type, table headers for spreadsheet chunks).
     */
    default GraphNode createSnippetNode(GraphNode documentNode, String snippetId, String content,
                                         int chunkIndex, Map<String, Object> metadata) {
        return createSnippetNode(documentNode, snippetId, content, chunkIndex);
    }

    /**
     * Specification for one snippet node to create via {@link #createSnippetNodesBatch}.
     *
     * <p>Carries the parent-document identity ({@code parentExternalId} + {@code parentFactSheetId})
     * rather than the full {@link GraphNode} object so the batch payload stays minimal over RPC.</p>
     */
    record SnippetSpec(String parentExternalId, Long parentFactSheetId,
                       String snippetId, String content, int chunkIndex) {}

    /**
     * Create many SNIPPET nodes in one call, returning them in the same order as {@code specs}.
     *
     * <p>The default implementation loops the existing {@link #createSnippetNode} overload,
     * reconstructing a minimal parent {@link GraphNode} from the spec fields — so all existing
     * implementors stay correct without change. Stores that can batch (the matrix/vector store)
     * override this for a single batched write per fact-sheet group.</p>
     */
    default List<GraphNode> createSnippetNodesBatch(List<SnippetSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return List.of();
        }
        List<GraphNode> created = new ArrayList<>(specs.size());
        for (SnippetSpec s : specs) {
            GraphNode parentNode = GraphNode.builder()
                    .nodeId("doc_" + s.parentExternalId())
                    .externalId(s.parentExternalId())
                    .factSheetId(s.parentFactSheetId())
                    .build();
            created.add(createSnippetNode(parentNode, s.snippetId(), s.content(), s.chunkIndex()));
        }
        return created;
    }

    /**
     * Create a table/sheet node under a parent document node.
     *
     * @param parentNodeId Parent document node UUID
     * @param externalId   Stable external ID for the table
     * @param tableTitle   Display title (e.g. sheet name)
     * @param rowCount     Number of rows
     * @param columnCount  Number of columns
     * @param headers      Column header names
     * @param contentPreview Short preview of the table content
     * @param metadata     Additional metadata
     * @return Created table node, or {@code null} if parent not found
     */
    default GraphNode createTableNode(String parentNodeId, String externalId, String tableTitle,
                                       int rowCount, int columnCount, List<String> headers,
                                       String contentPreview, Map<String, Object> metadata) {
        Optional<GraphNode> parentOpt = getNode(parentNodeId);
        if (parentOpt.isEmpty()) {
            return null;
        }
        GraphNode parent = parentOpt.get();

        // Enrich metadata with table-specific fields
        Map<String, Object> tableMeta = metadata != null ? new LinkedHashMap<>(metadata)
                                                         : new LinkedHashMap<>();
        tableMeta.put("rowCount", rowCount);
        tableMeta.put("columnCount", columnCount);
        if (headers != null && !headers.isEmpty()) {
            tableMeta.put("headers", String.join(",", headers));
        }

        String description = contentPreview;
        if (description != null && description.length() > 500) {
            description = description.substring(0, 500) + "...";
        }

        // Propagate factSheetId from parent so TABLE nodes are scoped in the
        // vector store and appear in per-fact-sheet counts (graph-stats tableCount).
        Long factSheetId = parent.getFactSheetId();
        GraphNode tableNode = createNode(NodeLevel.TABLE, externalId, tableTitle,
                description, tableMeta, factSheetId);

        // Create hierarchical CONTAINS edge from parent → table
        try {
            if (!edgeExists(parent.getNodeId(), tableNode.getNodeId())) {
                createEdge(parent.getNodeId(), tableNode.getNodeId(),
                        EdgeType.HIERARCHICAL, 1.0, "Contains table: " + tableTitle);
            }
        } catch (Exception ignored) {
            // Edge creation is best-effort
        }

        return tableNode;
    }

    /**
     * Create a custom/entity node
     *
     * @param nodeType Type of node
     * @param externalId External ID
     * @param title Title
     * @param description Description
     * @param metadata Metadata
     * @return Created node
     */
    GraphNode createNode(NodeLevel nodeType, String externalId, String title,
                          String description, Map<String, Object> metadata);

    /**
     * Create a custom/entity node scoped to a fact sheet.
     * Default implementation delegates to the non-scoped overload (ignoring factSheetId).
     */
    default GraphNode createNode(NodeLevel nodeType, String externalId, String title,
                                  String description, Map<String, Object> metadata,
                                  Long factSheetId) {
        return createNode(nodeType, externalId, title, description, metadata);
    }

    /**
     * Specification for one node to create via {@link #createNodesBatch}.
     */
    record NodeSpec(NodeLevel nodeType, String externalId, String title,
                    String description, Map<String, Object> metadata) {}

    /**
     * Create many nodes in one call, scoped to a fact sheet, returning the created nodes
     * in the same order as {@code specs}.
     *
     * <p>The default implementation loops over {@link #createNode}, so every existing
     * implementor keeps working unchanged. Stores that can write in bulk (the matrix/vector
     * store) override this to collapse N per-node persistence calls into a single batched
     * write — the per-node path otherwise dominates structural-graph construction (one Lucene
     * add per spreadsheet cell/formula node, ~thousands per crawl).</p>
     */
    default List<GraphNode> createNodesBatch(List<NodeSpec> specs, Long factSheetId) {
        if (specs == null || specs.isEmpty()) {
            return List.of();
        }
        List<GraphNode> created = new ArrayList<>(specs.size());
        for (NodeSpec s : specs) {
            created.add(createNode(s.nodeType(), s.externalId(), s.title(),
                    s.description(), s.metadata(), factSheetId));
        }
        return created;
    }

    /**
     * Get a node by its UUID
     */
    Optional<GraphNode> getNode(String nodeId);

    /**
     * Get a node by external ID and type
     */
    Optional<GraphNode> getNodeByExternalId(String externalId, NodeLevel nodeType);

    /**
     * Get a node by external ID and type, scoped to a fact sheet.
     * Default implementation delegates to the non-scoped overload.
     */
    default Optional<GraphNode> getNodeByExternalId(String externalId, NodeLevel nodeType, Long factSheetId) {
        return getNodeByExternalId(externalId, nodeType);
    }

    /**
     * One external-id lookup row for {@link #getNodesByExternalIds(List)}. A {@code null}
     * factSheetId means the unscoped lookup.
     */
    record ExternalNodeLookup(String externalId, NodeLevel nodeType, Long factSheetId) {
    }

    /**
     * Batch-resolve nodes by external id. Rows that resolve to nothing are omitted (the result
     * order follows the input order of the rows that DID resolve). Implementations backed by a
     * remote store should override this with a single round-trip.
     */
    default List<GraphNode> getNodesByExternalIds(List<ExternalNodeLookup> lookups) {
        if (lookups == null || lookups.isEmpty()) {
            return List.of();
        }
        List<GraphNode> result = new ArrayList<>(lookups.size());
        for (ExternalNodeLookup lookup : lookups) {
            if (lookup == null || lookup.externalId() == null) {
                continue;
            }
            (lookup.factSheetId() != null
                    ? getNodeByExternalId(lookup.externalId(), lookup.nodeType(), lookup.factSheetId())
                    : getNodeByExternalId(lookup.externalId(), lookup.nodeType()))
                    .ifPresent(result::add);
        }
        return result;
    }

    /**
     * Get children of a node
     */
    List<GraphNode> getChildren(String parentNodeId);

    /**
     * Update a node's metadata
     */
    GraphNode updateNode(String nodeId, String title, String description, Map<String, Object> metadata);

    /**
     * Delete a node and all its descendants
     */
    void deleteNode(String nodeId);

    /**
     * Get all source nodes
     */
    List<GraphNode> getAllSources();

    /**
     * Enumerate the distinct fact-sheet IDs that have graph data, derived from the
     * {@linkplain #getAllSources() source roots}. Store-agnostic: works on whichever backend is
     * active (JPA, matrix/vector, or the change-tracking decorator) because it goes through
     * {@code getAllSources()}. Fact sheets with no SOURCE node (e.g. only manually-added entities)
     * are not enumerated — acceptable for scheduled maintenance, which acts on crawled structure.
     */
    default Set<Long> findFactSheetIds() {
        return getAllSources().stream()
                .map(GraphNode::getFactSheetId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * Ordered fact-sheet enumeration for callers that mine or hydrate every available graph.
     */
    default List<Long> getFactSheetIdsWithGraphs() {
        return findFactSheetIds().stream().sorted().toList();
    }

    /**
     * Get all nodes across all types, up to the given limit.
     * Default implementation combines results from every NodeLevel.
     */
    default List<GraphNode> getAllNodes(int limit) {
        List<GraphNode> result = new ArrayList<>();
        for (NodeLevel level : NodeLevel.values()) {
            List<GraphNode> byType = getNodesByType(level, limit);
            result.addAll(byType);
            if (result.size() >= limit) break;
        }
        if (result.size() > limit) {
            return result.subList(0, limit);
        }
        return result;
    }

    /**
     * Search nodes by text
     */
    List<GraphNode> searchNodes(String query, NodeLevel type, int limit);

    // ═══════════════════════════════════════════════════════════════════════════
    // EDGE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Create an edge between two nodes
     *
     * @param sourceNodeId Source node UUID
     * @param targetNodeId Target node UUID
     * @param edgeType Type of edge
     * @param weight Edge weight (0.0 to 1.0)
     * @param description Human-readable description
     * @return Created edge
     */
    GraphEdge createEdge(String sourceNodeId, String targetNodeId, EdgeType edgeType,
                          Double weight, String description);

    /**
     * Create an edge carrying a semantic {@code relationType} (e.g. "WORKS_AT", "FEEDS_INTO") in
     * addition to the structural {@link EdgeType}. The default ignores {@code relationType} (delegating
     * to {@link #createEdge(String, String, EdgeType, Double, String)}); implementations that support
     * it override this — the matrix store persists it as the adjacency key, JPA as a column — so it
     * round-trips on read and is available for ontology relationship-conformance.
     */
    default GraphEdge createEdge(String sourceNodeId, String targetNodeId, EdgeType edgeType,
                                 String relationType, Double weight, String description) {
        return createEdge(sourceNodeId, targetNodeId, edgeType, weight, description);
    }

    /**
     * Get an edge by its UUID
     */
    Optional<GraphEdge> getEdge(String edgeId);

    /**
     * Get all edges for a node
     */
    List<GraphEdge> getEdgesForNode(String nodeId);

    /**
     * Get edges of a specific type for a node
     */
    List<GraphEdge> getEdgesByType(String nodeId, EdgeType edgeType);

    /**
     * Update an edge
     */
    GraphEdge updateEdge(String edgeId, Double weight, String description);

    /**
     * Delete an edge
     */
    void deleteEdge(String edgeId);

    /**
     * Check if an edge exists between two nodes
     */
    boolean edgeExists(String sourceNodeId, String targetNodeId);

    /**
     * Check if a specific typed edge (with optional label and fact sheet scope) exists
     */
    default boolean edgeExists(String sourceNodeId, String targetNodeId,
                                EdgeType edgeType, String label, Long factSheetId) {
        return edgeExists(sourceNodeId, targetNodeId);
    }

    /**
     * Delete multiple edges in a single batch operation
     */
    default void deleteEdgesBulk(List<String> edgeIds) {
        if (edgeIds != null) {
            edgeIds.forEach(this::deleteEdge);
        }
    }

    /**
     * Create an edge with full metadata including provenance
     */
    default GraphEdge createEdgeWithMetadata(String sourceNodeId, String targetNodeId,
                                              EdgeType edgeType, Double weight,
                                              String label, String description,
                                              String metaJson, EdgeProvenance provenance,
                                              Long factSheetId) {
        // Route the semantic relation label through createEdge's relationType so it is persisted
        // (matrix: as the adjacency key; JPA: as the relation_type column) instead of dropped.
        return createEdge(sourceNodeId, targetNodeId, edgeType, label, weight,
                description != null ? description : label);
    }

    /**
     * Specification for one edge to create via {@link #createEdgesBatch}.
     *
     * <p>The batch method performs server-side idempotency: if an edge already exists between
     * {@code sourceNodeId} and {@code targetNodeId} (regardless of edge type), the tuple is
     * skipped rather than creating a duplicate.  Duplicate checking is intentionally coarse
     * (same source+target pair) to match the behaviour of the normalizer redirect step.</p>
     */
    record EdgeSpec(
            String sourceNodeId,
            String targetNodeId,
            EdgeType edgeType,
            Double weight,
            String description,
            String label,
            String metaJson,
            EdgeProvenance provenance,
            Long factSheetId) {

        /** Backward-compatible minimal edge specification. */
        public EdgeSpec(String sourceNodeId, String targetNodeId, EdgeType edgeType,
                        Double weight, String description) {
            this(sourceNodeId, targetNodeId, edgeType, weight, description,
                    null, null, null, null);
        }
    }

    /**
     * Create many edges in one call, skipping pairs that already have an edge between them.
     *
     * <p>The default implementation loops existing {@link #edgeExists(String, String)} +
     * {@link #createEdge(String, String, EdgeType, Double, String)} so every existing implementor
     * is correct without change. Stores that can batch (the matrix/vector store) override this to
     * collapse N per-edge round-trips into a single batched write — the per-edge path otherwise
     * dominates post-extraction normalisation (one RPC per duplicate edge redirect).</p>
     *
     * @param specs list of edges to create
     * @return number of edges actually created (existing edges are not double-counted)
     */
    default int createEdgesBatch(List<EdgeSpec> specs) {
        if (specs == null || specs.isEmpty()) return 0;
        int created = 0;
        for (EdgeSpec s : specs) {
            try {
                if (!edgeExists(s.sourceNodeId(), s.targetNodeId())) {
                    createEdgeWithMetadata(
                            s.sourceNodeId(), s.targetNodeId(), s.edgeType(), s.weight(),
                            s.label(), s.description(), s.metaJson(), s.provenance(),
                            s.factSheetId());
                    created++;
                }
            } catch (Exception e) { /* best-effort — skip failures */ }
        }
        return created;
    }

    /**
     * Add a document to the graph, creating or updating the source node and document node.
     * Default implementation uses createOrUpdateSourceNode + createDocumentNode.
     */
    default GraphNode addDocument(String sourceExternalId, String jobId, String sourceType,
                                   String sourcePath, String fileName,
                                   String contentPreview, Map<String, Object> docMeta,
                                   Long factSheetId) {
        Map<String, Object> sourceMeta = docMeta == null ? Map.of() : new HashMap<>(docMeta);
        GraphNode sourceNode = createOrUpdateSourceNode(sourceExternalId, jobId, sourceType, sourcePath, sourceMeta);
        Map<String, Object> documentMeta = docMeta == null ? Map.of() : new HashMap<>(docMeta);
        if (contentPreview != null) documentMeta.put("contentPreview", contentPreview);
        return createDocumentNode(sourceNode, sourcePath, fileName != null ? fileName : sourcePath, documentMeta);
    }

    /**
     * Search edges by description or connected node titles
     *
     * @param query Search query
     * @param edgeType Optional edge type filter
     * @param limit Maximum results
     * @return List of matching edges
     */
    List<GraphEdge> searchEdges(String query, EdgeType edgeType, int limit);

    // ═══════════════════════════════════════════════════════════════════════════
    // GRAPH TRAVERSAL
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get nodes connected to a node within a certain depth
     *
     * @param nodeId Starting node UUID
     * @param depth Maximum traversal depth
     * @return List of connected nodes
     */
    List<GraphNode> getConnectedNodes(String nodeId, int depth);

    /**
     * Find related nodes based on graph structure
     *
     * @param nodeId Starting node UUID
     * @param maxResults Maximum results to return
     * @return List of related nodes
     */
    List<GraphNode> findRelatedNodes(String nodeId, int maxResults);

    /**
     * Find the shortest path between two nodes using BFS.
     *
     * @param fromNodeId Source node UUID
     * @param toNodeId   Target node UUID
     * @param maxDepth   Maximum BFS depth to prevent runaway traversal
     * @return Ordered list of nodes from source to target, or empty if no path found
     */
    default List<GraphNode> findShortestPath(String fromNodeId, String toNodeId, int maxDepth) {
        if (fromNodeId.equals(toNodeId)) {
            return getNode(fromNodeId).map(List::of).orElse(List.of());
        }

        // BFS with parent tracking
        Map<String, String> parentMap = new LinkedHashMap<>();
        Queue<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();

        queue.add(fromNodeId);
        visited.add(fromNodeId);
        parentMap.put(fromNodeId, null);
        int depth = 0;

        while (!queue.isEmpty() && depth < maxDepth) {
            int levelSize = queue.size();
            for (int i = 0; i < levelSize; i++) {
                String current = queue.poll();
                for (GraphEdge edge : getEdgesForNode(current)) {
                    String neighbor = edge.getSourceNode() != null
                            && edge.getSourceNode().getNodeId().equals(current)
                            ? (edge.getTargetNode() != null ? edge.getTargetNode().getNodeId() : null)
                            : (edge.getSourceNode() != null ? edge.getSourceNode().getNodeId() : null);
                    if (neighbor == null || visited.contains(neighbor)) continue;
                    visited.add(neighbor);
                    parentMap.put(neighbor, current);
                    if (neighbor.equals(toNodeId)) {
                        // Reconstruct path
                        LinkedList<GraphNode> path = new LinkedList<>();
                        String step = toNodeId;
                        while (step != null) {
                            getNode(step).ifPresent(path::addFirst);
                            step = parentMap.get(step);
                        }
                        return path;
                    }
                    queue.add(neighbor);
                }
            }
            depth++;
        }
        return List.of();
    }

    /**
     * Compute relevance scores for nodes relative to a query node
     *
     * @param queryNodeId Query node UUID
     * @param candidateNodeIds List of candidate node UUIDs
     * @return Map of node ID to relevance score
     */
    Map<String, Double> computeNodeRelevance(String queryNodeId, List<String> candidateNodeIds);

    /**
     * Get all nodes of a specific type across the entire graph (limited).
     */
    List<GraphNode> getNodesByType(NodeLevel type, int limit);

    /**
     * Get all nodes of a specific type across the entire graph (no limit).
     */
    List<GraphNode> getNodesByType(NodeLevel type);

    /**
     * Get nodes by their internal UUIDs.
     */
    List<GraphNode> getNodesByIds(List<String> nodeIds);

    // ═══════════════════════════════════════════════════════════════════════════
    // FACT-SHEET-SCOPED NODE QUERIES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get nodes of a specific type scoped to a fact sheet.
     */
    List<GraphNode> getNodesByTypeInFactSheet(Long factSheetId, NodeLevel type);

    /**
     * Count ENTITY nodes scoped to a fact sheet without materialising the full list.
     * The default implementation loads and counts; matrix stores override with a streaming count.
     */
    default long countEntityNodesInFactSheet(Long factSheetId) {
        return getNodesByTypeInFactSheet(factSheetId, NodeLevel.ENTITY).size();
    }

    /**
     * Return a bounded page of ENTITY nodes for a fact sheet.
     * Used by {@code GraphCompactionService} to load entity nodes in bounded chunks
     * so the full list is never materialised all at once during ENTITY_RESOLUTION.
     *
     * <p>The default implementation loads the full list and subLists; matrix stores
     * override with an efficient streaming skip+limit to avoid full materialisation.</p>
     *
     * @param factSheetId fact sheet to scope to (must not be null)
     * @param offset      zero-based start index
     * @param pageSize    maximum number of nodes to return
     */
    default List<GraphNode> getEntityNodesInFactSheetPage(Long factSheetId, int offset, int pageSize) {
        List<GraphNode> all = getNodesByTypeInFactSheet(factSheetId, NodeLevel.ENTITY);
        int start = Math.min(offset, all.size());
        int end   = Math.min(offset + pageSize, all.size());
        return new ArrayList<>(all.subList(start, end));
    }

    /**
     * Get all nodes belonging to a fact sheet.
     */
    List<GraphNode> getNodesInFactSheet(Long factSheetId);

    /** A bounded page whose cursor is opaque to callers except for pass-through. */
    record GraphPage<T>(List<T> items, int nextCursor, boolean hasMore) {
        public GraphPage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    default GraphPage<GraphNode> getNodesInFactSheetPage(Long factSheetId, int cursor, int pageSize) {
        List<GraphNode> all = getNodesInFactSheet(factSheetId);
        int start = Math.min(cursor, all.size());
        int end = Math.min(start + pageSize, all.size());
        return new GraphPage<>(all.subList(start, end), end, end < all.size());
    }

    /**
     * Get all source nodes belonging to a fact sheet.
     */
    List<GraphNode> getSourcesInFactSheet(Long factSheetId);

    /**
     * Find a node by external ID and type within a specific fact sheet.
     */
    Optional<GraphNode> getNodeByExternalIdInFactSheet(String externalId, NodeLevel type, Long factSheetId);

    /**
     * Search nodes by text within a specific fact sheet.
     */
    List<GraphNode> searchNodesInFactSheet(Long factSheetId, String query, int limit);

    // ═══════════════════════════════════════════════════════════════════════════
    // FACT-SHEET-SCOPED EDGE QUERIES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get all edges for a node within a specific fact sheet.
     */
    List<GraphEdge> getEdgesForNodeInFactSheet(String nodeId, Long factSheetId);

    /**
     * Check if an edge exists between two nodes within a specific fact sheet.
     */
    boolean edgeExistsInFactSheet(String sourceNodeId, String targetNodeId, Long factSheetId);

    /**
     * Get all edges belonging to a fact sheet.
     */
    List<GraphEdge> getEdgesInFactSheet(Long factSheetId);

    default GraphPage<GraphEdge> getEdgesInFactSheetPage(Long factSheetId, int cursor, int pageSize) {
        List<GraphEdge> all = getEdgesInFactSheet(factSheetId);
        int start = Math.min(cursor, all.size());
        int end = Math.min(start + pageSize, all.size());
        return new GraphPage<>(all.subList(start, end), end, end < all.size());
    }

    /**
     * Get edges of a specific type within a fact sheet.
     */
    List<GraphEdge> getEdgesByTypeInFactSheet(Long factSheetId, EdgeType edgeType);

    /**
     * Find a single edge between two nodes (source → target direction).
     * Returns null if no such edge exists.
     */
    GraphEdge findEdgeBetweenNodes(String sourceNodeId, String targetNodeId);

    // ═══════════════════════════════════════════════════════════════════════════
    // ENTITY MENTION OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get all entity mentions for a node.
     */
    List<EntityMention> getEntityMentionsForNode(GraphNode node);

    /**
     * Get all entity mentions for a node by node ID.
     */
    List<EntityMention> getEntityMentionsForNode(String nodeId);

    /**
     * Find a specific entity mention in a node.
     */
    Optional<EntityMention> findEntityMention(GraphNode node, String entityName);

    /**
     * Find a specific entity mention in a node within a fact sheet.
     */
    Optional<EntityMention> findEntityMentionInFactSheet(GraphNode node, String entityName, Long factSheetId);

    /**
     * Save (create or update) an entity mention.
     */
    EntityMention saveEntityMention(EntityMention mention);

    /**
     * Find pairs of nodes that share at least {@code minShared} entities.
     * Each returned element is a 3-element Object array: [node1, node2, sharedCount].
     */
    List<Object[]> findNodePairsWithSharedEntities(int minShared);

    /**
     * Find pairs of nodes that share at least {@code minShared} entities within a fact sheet.
     * Each returned element is a 3-element Object array: [node1Id (Long), node2Id (Long), sharedCount].
     */
    List<Object[]> findNodePairsWithSharedEntitiesInFactSheet(Long factSheetId, int minShared);

    /**
     * Get entity names mentioned in a node.
     */
    List<String> getEntityNamesForNode(String nodeId);

    /**
     * Get all nodes that mention a specific entity.
     */
    List<GraphNode> getNodesWithEntity(String entityName);

    // ═══════════════════════════════════════════════════════════════════════════
    // NODE EMBEDDINGS (store-agnostic portability)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Export the embeddings the live store holds for a fact sheet's nodes, keyed by nodeId.
     *
     * <p>Store-agnostic seam so {@code GraphEmbeddingSidecar} can serialize whatever the
     * @Primary store actually holds, rather than only the JPA {@code kgEmbedding} column:
     * the matrix/vector store returns its per-node vectors (the text embeddings used for
     * similarity search); a JPA-backed store may return its KGE columns. Only nodes that
     * have an embedding are included. Returned arrays are caller-owned and should be closed
     * after use. Default: empty (store has none / not supported).</p>
     *
     * @param factSheetId fact-sheet scope; {@code null} means all nodes
     * @return map of nodeId → embedding (never {@code null})
     */
    default Map<String, INDArray> exportNodeEmbeddings(Long factSheetId) {
        return Map.of();
    }

    /**
     * Bulk-apply node embeddings (keyed by nodeId) to the live store — used on project
     * rehydrate to reattach a cloned graph's embeddings instead of recomputing them. The
     * matrix/vector store additionally re-indexes them so similarity search is warm after a
     * clone. Embeddings for unknown nodeIds are skipped. Default: no-op (returns 0).
     *
     * @param embeddingsByNodeId map of nodeId → embedding to apply
     * @return number of node embeddings actually applied
     */
    default int applyNodeEmbeddings(Map<String, INDArray> embeddingsByNodeId) {
        return 0;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GENERAL NODE BATCH UPDATE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * One node update. Metadata is merged with the existing metadata rather than replacing it.
     */
    record NodeUpdate(String nodeId, String title, String description,
                      Map<String, Object> additionalMetadata) {
        public NodeUpdate {
            additionalMetadata = additionalMetadata == null
                    ? null
                    : Map.copyOf(additionalMetadata);
        }

        /** Compatibility name used by graph post-processing code and JSON-facing callers. */
        public Map<String, Object> metadata() {
            return additionalMetadata;
        }
    }

    /**
     * Apply node updates in a store-neutral batch contract.
     * Stores with native batching may override this method.
     */
    default int updateNodesBatch(List<NodeUpdate> updates) {
        if (updates == null || updates.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (NodeUpdate update : updates) {
            if (update == null || update.nodeId() == null) {
                continue;
            }
            try {
                Map<String, Object> merged = null;
                if (update.additionalMetadata() != null) {
                    Optional<GraphNode> existing = getNode(update.nodeId());
                    if (existing.isEmpty()) {
                        continue;
                    }
                    merged = new HashMap<>(existing.get().getMetadata());
                    merged.putAll(update.additionalMetadata());
                }
                updateNode(update.nodeId(), update.title(), update.description(), merged);
                count++;
            } catch (Exception ignored) {
                // Best-effort default; transactional stores can provide an atomic override.
            }
        }
        return count;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // KGE METADATA BATCH UPDATE (write KGE keys without triggering sentence re-embed)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * A (nodeId, additionalMetadata) pair for {@link #updateNodeKgeMetadataBatch}.
     *
     * <p>The {@code additionalMetadata} map contains only the keys to MERGE into the node's
     * existing metadata (e.g. {@code kgeEmbedding}, {@code kgeAlgorithm}, {@code kgeVersion}).
     * Existing metadata keys NOT present in {@code additionalMetadata} are preserved.</p>
     */
    record NodeMetadataUpdate(String nodeId, Map<String, Object> additionalMetadata) {}

    /**
     * Blocks until all pending async embedding tasks (dispatched by {@link VectorStore#add})
     * have completed and their documents are committed to the store.
     *
     * <p>Call this before {@link #updateNodeKgeMetadataBatch} to ensure sentence embeddings
     * dispatched during graph construction are visible in the adjacency-matrix cache, so the
     * subsequent metadata-only updates can reuse the cached vectors without re-embedding.
     * The default is a no-op; the matrix-store implementation delegates to
     * {@link ai.kompile.core.embeddings.VectorStore#awaitPendingEmbeddings()}.</p>
     */
    default void awaitPendingEmbeddings() {}

    /**
     * Write KGE structural-embedding metadata (kgeEmbedding, kgeAlgorithm, kgeVersion) for
     * many nodes in a single call WITHOUT triggering sentence re-embeds.
     *
     * <p>Each update MERGES its {@code additionalMetadata} keys into the node's existing
     * metadata — existing keys not present in the update are preserved.  This is the correct
     * write path for {@link ai.kompile.knowledgegraph.embedding.adapter.MatrixKgEmbeddingGraphAdapter#storeEmbeddings}
     * so that storing 5 903 KGE vectors does not enqueue 5 903 sentence re-embeds in the
     * graph subprocess's async-embed pool (the root cause of the post-KGE OOM).</p>
     *
     * <p>The default implementation loops {@link #getNode} + metadata-merge + {@link #updateNode}
     * (with null title/description to avoid re-embedding) so every existing implementor stays
     * correct.  The matrix/vector store overrides this to do all merges in one batched server-side
     * call — a single subprocess RPC instead of 5 903.</p>
     *
     * @param updates list of (nodeId, additionalMetadata) pairs
     * @return number of nodes actually updated (nodes not found are skipped)
     */
    default int updateNodeKgeMetadataBatch(List<NodeMetadataUpdate> updates) {
        if (updates == null || updates.isEmpty()) return 0;
        int count = 0;
        for (NodeMetadataUpdate u : updates) {
            try {
                Optional<GraphNode> nodeOpt = getNode(u.nodeId());
                if (nodeOpt.isEmpty()) continue;
                GraphNode node = nodeOpt.get();
                // Merge: preserve existing metadata, overwrite only the new KGE keys
                Map<String, Object> merged = node.getMetadata() != null
                        ? new HashMap<>(node.getMetadata()) : new HashMap<>();
                merged.putAll(u.additionalMetadata());
                // Null title+description → MatrixKnowledgeGraphService takes the
                // no-re-embed (updateNodeMetadata) code path
                updateNode(u.nodeId(), null, null, merged);
                count++;
            } catch (Exception e) { /* best-effort — skip failures */ }
        }
        return count;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // KG EMBEDDING STORAGE (TransE/RotatE — structural KGE on nodes and edges)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Store a structural KGE (TransE/RotatE) embedding for a node.
     * The embedding is keyed by {@code nodeId} (UUID string) and associated metadata
     * (algorithm, training version, timestamp) are stored alongside.
     *
     * <p>Default: no-op (store does not support KGE persistence).</p>
     */
    default void storeNodeKgEmbedding(String nodeId, INDArray embedding,
                                       ai.kompile.core.kgembedding.KGEmbeddingAlgorithm algorithm,
                                       Long version, java.time.Instant updatedAt) {}

    /**
     * Retrieve the structural KGE embedding for a node, or {@code null} if none is stored.
     * Default: {@code null}.
     */
    default INDArray getNodeKgEmbedding(String nodeId) { return null; }

    /**
     * Return nodeIds (UUIDs) of all nodes in a fact sheet that have a stored KGE embedding.
     * Default: empty list.
     */
    default List<GraphNode> findNodesWithKgEmbedding(Long factSheetId) {
        return List.of();
    }

    /**
     * Store a structural KGE relation embedding for an edge type.
     * Relation embeddings are type-shared (one per EdgeType name) and stored per fact sheet.
     * Default: no-op.
     */
    default void storeEdgeTypeKgEmbedding(String edgeTypeName, INDArray embedding,
                                           ai.kompile.core.kgembedding.KGEmbeddingAlgorithm algorithm,
                                           Long version, Long factSheetId) {}

    /**
     * Return all stored edge-type KGE embeddings for a fact sheet as a map
     * from EdgeType name → embedding. Default: empty map.
     */
    default Map<String, INDArray> getEdgeTypeKgEmbeddings(Long factSheetId) {
        return Map.of();
    }

    /**
     * Return the algorithm used for the stored node KGE embeddings in a fact sheet,
     * or {@code null} if none. Default: {@code null}.
     */
    default ai.kompile.core.kgembedding.KGEmbeddingAlgorithm getStoredKgAlgorithm(Long factSheetId) {
        return null;
    }

    /**
     * Clear all KGE embeddings (node and edge) for a fact sheet. Default: no-op.
     */
    default void clearKgEmbeddings(Long factSheetId) {}

    // ═══════════════════════════════════════════════════════════════════════════
    // COUNT / STATISTICS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Count nodes of a specific type across the entire graph.
     */
    long countNodesByType(NodeLevel type);

    /**
     * Count nodes of a specific type scoped to a fact sheet.
     */
    long countNodesByTypeInFactSheet(Long factSheetId, NodeLevel type);

    // ═══════════════════════════════════════════════════════════════════════════
    // MAINTENANCE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Flush any buffered/pending nodes to the underlying store.
     * Implementations that write eagerly may treat this as a no-op.
     */
    void flushPendingNodes();

    /**
     * Delete all graph data (nodes, edges, mentions) associated with a fact sheet.
     */
    void deleteByFactSheetId(Long factSheetId);

    // ═══════════════════════════════════════════════════════════════════════════
    // PRUNING / MAINTENANCE QUERIES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Node levels treated as orphan-eligible when {@link #findOrphanNodeIds(Long)} is called
     * without an explicit set. Kept to the ENTITY layer so the existing auto-prune policy
     * (OrphanPruner) is unchanged; broader maintenance/health scans pass an explicit level set.
     */
    Set<NodeLevel> DEFAULT_ORPHAN_LEVELS = Set.of(NodeLevel.ENTITY);

    /**
     * Return the nodeId strings of degree-0 ENTITY nodes (no edges connect them) within the
     * given fact sheet that are not already stale. Delegates to
     * {@link #findOrphanNodeIds(Long, java.util.Set)} with {@link #DEFAULT_ORPHAN_LEVELS}.
     *
     * @param factSheetId the fact sheet to scan
     * @return list of orphan node UUIDs (never {@code null})
     */
    default List<String> findOrphanNodeIds(Long factSheetId) {
        return findOrphanNodeIds(factSheetId, DEFAULT_ORPHAN_LEVELS);
    }

    /**
     * Return the nodeId strings of degree-0 nodes (no edges connect them) within the given
     * fact sheet whose {@link NodeLevel} is in {@code levels} and that are not already stale.
     *
     * <p>The default implementation iterates all nodes in the fact sheet and checks
     * {@link #getEdgesForNode}; stores that maintain edge indices override this for efficiency.
     * Every backend (JPA <em>and</em> the matrix/vector store) MUST honor the level set.</p>
     *
     * @param factSheetId the fact sheet to scan
     * @param levels      node levels to consider; {@code null}/empty falls back to {@link #DEFAULT_ORPHAN_LEVELS}
     * @return list of orphan node UUIDs (never {@code null})
     */
    default List<String> findOrphanNodeIds(Long factSheetId, Set<NodeLevel> levels) {
        Set<NodeLevel> wanted =
                (levels == null || levels.isEmpty()) ? DEFAULT_ORPHAN_LEVELS : levels;
        return getNodesInFactSheet(factSheetId).stream()
                .filter(n -> n.getNodeType() != null && wanted.contains(n.getNodeType()))
                .filter(n -> !Boolean.TRUE.equals(n.getStale()))
                .filter(n -> getEdgesForNode(n.getNodeId()).isEmpty())
                .map(ai.kompile.knowledgegraph.domain.GraphNode::getNodeId)
                .collect(Collectors.toList());
    }

    /**
     * Return the node UUIDs of non-stale nodes in the fact sheet whose
     * {@code confidence} field is non-null and strictly less than
     * {@code minConfidence}.
     *
     * @param factSheetId   the fact sheet to scan
     * @param minConfidence exclusive lower-bound; nodes below this are returned
     * @return list of matching node UUIDs (never {@code null})
     */
    default List<String> findLowConfidenceNodeIds(Long factSheetId, double minConfidence) {
        return getNodesInFactSheet(factSheetId).stream()
                .filter(n -> !Boolean.TRUE.equals(n.getStale()))
                .filter(n -> n.getConfidence() != null && n.getConfidence() < minConfidence)
                .map(ai.kompile.knowledgegraph.domain.GraphNode::getNodeId)
                .collect(Collectors.toList());
    }

    /**
     * Return the edge UUIDs of non-stale edges in the fact sheet whose
     * {@code confidence} field is non-null and strictly less than
     * {@code minConfidence}.
     *
     * @param factSheetId   the fact sheet to scan
     * @param minConfidence exclusive lower-bound
     * @return list of matching edge UUIDs (never {@code null})
     */
    default List<String> findLowConfidenceEdgeIds(Long factSheetId, double minConfidence) {
        return getEdgesInFactSheet(factSheetId).stream()
                .filter(e -> !Boolean.TRUE.equals(e.getStale()))
                .filter(e -> e.getConfidence() != null && e.getConfidence() < minConfidence)
                .map(ai.kompile.knowledgegraph.domain.GraphEdge::getEdgeId)
                .collect(Collectors.toList());
    }

    /**
     * Count non-stale nodes within a fact sheet (across all node types).
     *
     * @param factSheetId the fact sheet to count
     * @return count of active (non-stale) nodes
     */
    default long countActiveNodes(Long factSheetId) {
        return getNodesInFactSheet(factSheetId).stream()
                .filter(n -> !Boolean.TRUE.equals(n.getStale()))
                .count();
    }

    /**
     * Return the edge UUIDs of active edges in a fact sheet.
     *
     * @param factSheetId the fact sheet to scan
     * @return list of active edge UUIDs (never {@code null})
     */
    default List<String> findActiveEdgeIds(Long factSheetId) {
        return getEdgesInFactSheet(factSheetId).stream()
                .filter(e -> !Boolean.TRUE.equals(e.getStale()))
                .map(ai.kompile.knowledgegraph.domain.GraphEdge::getEdgeId)
                .collect(Collectors.toList());
    }

    /**
     * Soft-delete (mark stale) or immediately remove a set of nodes identified
     * by their nodeId strings.
     *
     * <p>When {@code softDelete} is {@code true} nodes are marked stale with a
     * stale-at timestamp so a subsequent grace-period sweep can hard-delete them.
     * When {@code softDelete} is {@code false} the nodes are permanently removed
     * immediately (hard-delete, grace period is ignored).  When {@code dryRun}
     * is {@code true} no mutations are performed and the result carries the IDs
     * that <em>would</em> have been acted on.</p>
     *
     * <p>The default implementation calls {@link #deleteNode} for each nodeId
     * (hard-delete path) or {@link #updateNode} with a stale marker via
     * metadata (soft-delete path).  Stores that support bulk operations should
     * override this method.</p>
     *
     * @param nodeIds    UUIDs of nodes to prune
     * @param softDelete {@code true} to mark stale; {@code false} to hard-delete
     * @param grace      grace period used only when {@code softDelete} is
     *                   {@code true} and a store persists it; may be {@code null}
     * @param dryRun     {@code true} to simulate without mutating
     * @return a {@link GraphPruneResult} describing what was (or would be) done
     */
    default GraphPruneResult pruneNodes(Collection<String> nodeIds,
                                        boolean softDelete,
                                        Duration grace,
                                        boolean dryRun) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return GraphPruneResult.empty(dryRun);
        }
        List<String> ids = new ArrayList<>(nodeIds);
        if (dryRun) {
            return GraphPruneResult.ofSoftDelete(ids, true);
        }
        if (softDelete) {
            // Mark stale via metadata — stores with a dedicated stale column should override
            for (String nodeId : ids) {
                try {
                    updateNode(nodeId, null, null,
                            Map.of("_stale", true,
                                    "_staleAt", java.time.LocalDateTime.now().toString()));
                } catch (Exception ignored) { /* best-effort */ }
            }
            return GraphPruneResult.ofSoftDelete(ids, false);
        } else {
            int deleted = 0;
            for (String nodeId : ids) {
                try {
                    deleteNode(nodeId);
                    deleted++;
                } catch (Exception ignored) { /* best-effort */ }
            }
            return new GraphPruneResult(ids, deleted, deleted, false);
        }
    }

    /**
     * Soft-delete (mark stale) or immediately remove a set of edges identified
     * by their edgeId strings.
     *
     * <p>Semantics mirror {@link #pruneNodes}.  The default implementation
     * calls {@link #deleteEdge} per edge for hard-delete, and
     * {@link #updateEdge} with a stale weight sentinel for soft-delete.
     * Stores with bulk operations should override.</p>
     *
     * @param edgeIds    UUIDs of edges to prune
     * @param softDelete {@code true} to mark stale; {@code false} to hard-delete
     * @param dryRun     {@code true} to simulate without mutating
     * @return a {@link GraphPruneResult} describing what was (or would be) done
     */
    default GraphPruneResult pruneEdges(Collection<String> edgeIds,
                                        boolean softDelete,
                                        boolean dryRun) {
        if (edgeIds == null || edgeIds.isEmpty()) {
            return GraphPruneResult.empty(dryRun);
        }
        List<String> ids = new ArrayList<>(edgeIds);
        if (dryRun) {
            return GraphPruneResult.ofSoftDelete(ids, true);
        }
        if (softDelete) {
            for (String edgeId : ids) {
                try {
                    // Sentinel: set weight to -1.0 to signal staleness for non-JPA stores
                    updateEdge(edgeId, -1.0, "_stale");
                } catch (Exception ignored) { /* best-effort */ }
            }
            return GraphPruneResult.ofSoftDelete(ids, false);
        } else {
            int deleted = 0;
            for (String edgeId : ids) {
                try {
                    deleteEdge(edgeId);
                    deleted++;
                } catch (Exception ignored) { /* best-effort */ }
            }
            return new GraphPruneResult(ids, deleted, deleted, false);
        }
    }

    /**
     * Hard-delete nodes that were previously soft-deleted (marked stale) more
     * than {@code grace} ago.
     *
     * <p>The default implementation is a no-op returning zero, because the
     * base interface has no way to query stale-at timestamps generically.
     * The JPA backend overrides this to delegate to
     * {@code nodeRepository.hardDeleteStaleNodes}.  The matrix backend
     * purges any node whose metadata contains {@code _stale=true}.</p>
     *
     * @param factSheetId the fact sheet scope
     * @param grace       how long nodes must have been stale before deletion
     * @return a {@link GraphPruneResult} with the count of records permanently removed
     */
    default GraphPruneResult hardDeleteStaleNodes(Long factSheetId, Duration grace) {
        return GraphPruneResult.empty(false);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATISTICS & VISUALIZATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get graph statistics
     */
    Map<String, Object> getGraphStatistics();

    /**
     * Get graph data in a format suitable for D3.js visualization
     *
     * @param rootNodeId Optional root node (null for entire graph)
     * @param depth Maximum depth from root
     * @param maxNodes Maximum nodes to include
     * @return Map with "nodes" and "edges" lists
     */
    Map<String, Object> getVisualizationData(String rootNodeId, int depth, int maxNodes);

    // ═══════════════════════════════════════════════════════════════════════════
    // ADDITIONAL WRITE / LOOKUP HELPERS (default; delegating to existing primitives)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Persist metadata changes to an already-loaded node object.
     * The default implementation delegates to {@link #updateNode} using the
     * values already present on the supplied node.
     */
    default GraphNode saveNode(GraphNode node) {
        return updateNode(node.getNodeId(), node.getTitle(),
                node.getDescription(),
                node.getMetadataJson() != null
                        ? Map.of("_raw", node.getMetadataJson())
                        : Map.of());
    }

    /**
     * Persist metadata/weight changes to an already-loaded edge object.
     * The default implementation delegates to {@link #updateEdge}.
     */
    default GraphEdge saveEdge(GraphEdge edge) {
        return updateEdge(edge.getEdgeId(), edge.getWeight(), edge.getDescription());
    }

    /**
     * Find an edge between two nodes searching both directions.
     * Default: checks source→target, then target→source via {@link #findEdgeBetweenNodes}.
     */
    default Optional<GraphEdge> findEdgeBetweenNodesBidirectional(String nodeId1, String nodeId2) {
        GraphEdge fwd = findEdgeBetweenNodes(nodeId1, nodeId2);
        if (fwd != null) return Optional.of(fwd);
        GraphEdge rev = findEdgeBetweenNodes(nodeId2, nodeId1);
        return Optional.ofNullable(rev);
    }

    /**
     * Search nodes scoped to a fact sheet, optionally filtered by type, up to {@code limit} results.
     * Default: delegates to {@link #searchNodesInFactSheet} (ignoring type post-filter is caller's responsibility).
     */
    default List<GraphNode> searchNodesInFactSheetByType(Long factSheetId, String query,
                                                          NodeLevel type, int limit) {
        List<GraphNode> raw = searchNodesInFactSheet(factSheetId, query, limit);
        if (type == null) return raw;
        return raw.stream()
                .filter(n -> n.getNodeType() == type)
                .collect(Collectors.toList());
    }

    /**
     * Search nodes globally (no fact-sheet scope), optionally filtered by type.
     * Default: delegates to {@link #searchNodes}.
     */
    default List<GraphNode> searchNodesGlobal(String query, NodeLevel type, int limit) {
        return searchNodes(query, type, limit);
    }

    /**
     * Get edges of a specific type within a fact sheet, optionally filtered by minimum weight,
     * up to {@code limit} results.
     * Default: delegates to {@link #getEdgesByTypeInFactSheet} then filters by weight.
     */
    default List<GraphEdge> getStrongEdgesByTypeInFactSheet(Long factSheetId, EdgeType edgeType,
                                                             Double minWeight, int limit) {
        List<GraphEdge> all = getEdgesByTypeInFactSheet(factSheetId, edgeType);
        Stream<GraphEdge> stream = all.stream();
        if (minWeight != null) {
            stream = stream.filter(e -> e.getWeight() != null && e.getWeight() >= minWeight);
        }
        return stream.limit(limit).collect(Collectors.toList());
    }

    /**
     * Get edges of a specific type globally, optionally filtered by minimum weight,
     * up to {@code limit} results.
     * Default: delegates to {@link #getEdgesByType} using edges for all nodes.
     */
    default List<GraphEdge> getStrongEdgesByType(EdgeType edgeType, Double minWeight, int limit) {
        List<GraphEdge> all = searchEdges(null, edgeType, limit * 2);
        Stream<GraphEdge> stream = all.stream();
        if (minWeight != null) {
            stream = stream.filter(e -> e.getWeight() != null && e.getWeight() >= minWeight);
        }
        return stream.limit(limit).collect(Collectors.toList());
    }

    /**
     * Get a node by its nodeId (UUID string).
     * Alias for {@link #getNode} — satisfies callers that use {@code findByNodeId} naming.
     */
    default Optional<GraphNode> findNodeById(String nodeId) {
        return getNode(nodeId);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TEMPORAL QUERIES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get visualization data filtered to a time range.
     * Only edges with occurredAt within [from, to] are included,
     * plus nodes connected by those edges.
     *
     * @param from Start of time range (inclusive)
     * @param to End of time range (inclusive)
     * @param maxNodes Maximum nodes to include
     * @return Map with "nodes", "edges", and "temporalBounds"
     */
    default Map<String, Object> getVisualizationDataInTimeRange(LocalDateTime from, LocalDateTime to, int maxNodes) {
        return getVisualizationData(null, 2, maxNodes);
    }

    /**
     * Search edges by time range
     *
     * @param from Start of time range (inclusive)
     * @param to End of time range (inclusive)
     * @param limit Maximum results
     * @return List of edges with occurredAt in the range
     */
    default List<GraphEdge> searchEdgesByTimeRange(LocalDateTime from, LocalDateTime to, int limit) {
        return List.of();
    }

    /**
     * Get the earliest and latest occurredAt timestamps in the graph.
     *
     * @return Map with "earliest" and "latest" LocalDateTime values, or empty if no temporal data
     */
    default Map<String, Object> getTemporalBounds() {
        return Map.of();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LEVEL-OF-DETAIL (LOD) ENDPOINTS — bounded graph views for large graphs
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Level-of-detail: return a bounded visualization of the top-K nodes by centrality, plus the
     * induced subgraph edges between them. Ideal as the initial "seed" view for the Sigma.js
     * visualizer on large graphs. The default falls back to a maxNodes-capped standard
     * visualization; matrix stores override with a real centrality-ranked selection.
     *
     * @param factSheetId optional fact-sheet scope (null = all graphs)
     * @param k           maximum number of nodes to return
     * @param metric      "pagerank" (default), "degree", or "betweenness"
     * @return viz-shape map: nodes / edges / links / statistics
     *         (statistics.totalAvailableNodes = full node count so the UI can show "K of N")
     */
    default Map<String, Object> getTopKVisualizationData(Long factSheetId, int k, String metric) {
        return getVisualizationData(null, 2, k);
    }

    /**
     * Level-of-detail: 1-hop neighborhood expand for a single node. Returns the seed node plus its
     * immediate neighbors (capped at maxNeighbors, sorted by edge weight desc) and all connecting
     * edges, in the standard viz shape. The default returns an empty result; matrix stores override
     * with a direct adjacency-list expansion (no full-graph scan).
     *
     * @param nodeId       the seed node id
     * @param maxNeighbors cap on returned neighbors (sorted by edge weight desc)
     * @param edgeTypes    optional edge-type filter (null/empty = all types)
     * @return viz-shape map: nodes / edges / links / statistics
     */
    default Map<String, Object> expandNeighborhoodVisualization(String nodeId, int maxNeighbors,
                                                                  List<String> edgeTypes) {
        return Map.of("nodes", List.of(), "edges", List.of(), "links", List.of(),
                "statistics", Map.of("totalAvailableNodes", 0));
    }

    /**
     * Batch-update arbitrary metadata on existing edges without touching their topology.
     * Used by GNN/link-prediction overlays to attach scores post-hoc.
     *
     * @param updates list of edge-id + metadata pairs
     * @return count of edges successfully updated
     */
    default int updateEdgeMetadataBatch(List<EdgeMetadataUpdate> updates) {
        return 0;
    }

    /**
     * Payload for {@link #updateEdgeMetadataBatch}: identifies an edge and carries the
     * key/value metadata to merge into it. The {@code edgeId} format is
     * {@code sourceNodeId::targetNodeId::edgeType}, which matches the compound id produced
     * by the matrix store.
     */
    record EdgeMetadataUpdate(String edgeId, Map<String, Object> additionalMetadata) {}
}
