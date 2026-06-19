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
import ai.kompile.knowledgegraph.domain.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
        Map<String, Object> tableMeta = metadata != null ? new java.util.LinkedHashMap<>(metadata)
                                                         : new java.util.LinkedHashMap<>();
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
     * Get all nodes across all types, up to the given limit.
     * Default implementation combines results from every NodeLevel.
     */
    default List<GraphNode> getAllNodes(int limit) {
        java.util.List<GraphNode> result = new java.util.ArrayList<>();
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
    default void deleteEdgesBulk(java.util.List<String> edgeIds) {
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
        return createEdge(sourceNodeId, targetNodeId, edgeType, weight,
                description != null ? description : label);
    }

    /**
     * Add a document to the graph, creating or updating the source node and document node.
     * Default implementation uses createOrUpdateSourceNode + createDocumentNode.
     */
    default GraphNode addDocument(String sourceExternalId, String jobId, String sourceType,
                                   String sourcePath, String fileName,
                                   String contentPreview, Map<String, Object> docMeta,
                                   Long factSheetId) {
        Map<String, Object> sourceMeta = docMeta == null ? Map.of() : new java.util.HashMap<>(docMeta);
        GraphNode sourceNode = createOrUpdateSourceNode(sourceExternalId, jobId, sourceType, sourcePath, sourceMeta);
        Map<String, Object> documentMeta = docMeta == null ? Map.of() : new java.util.HashMap<>(docMeta);
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
        java.util.Map<String, String> parentMap = new java.util.LinkedHashMap<>();
        java.util.Queue<String> queue = new java.util.ArrayDeque<>();
        java.util.Set<String> visited = new java.util.HashSet<>();

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
                        java.util.LinkedList<GraphNode> path = new java.util.LinkedList<>();
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
     * Get all nodes belonging to a fact sheet.
     */
    List<GraphNode> getNodesInFactSheet(Long factSheetId);

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
     * Return the node UUIDs (nodeId strings) of ENTITY nodes within the given fact
     * sheet that have degree 0 — i.e. no edges connect them to any other node —
     * and have not already been marked stale.
     *
     * <p>The default implementation iterates all nodes in the fact sheet and
     * checks {@link #getEdgesForNode}; stores that maintain edge indices may
     * override this for efficiency.</p>
     *
     * @param factSheetId the fact sheet to scan
     * @return list of orphan node UUIDs (never {@code null})
     */
    default List<String> findOrphanNodeIds(Long factSheetId) {
        return getNodesInFactSheet(factSheetId).stream()
                .filter(n -> n.getNodeType() != null
                        && "ENTITY".equals(n.getNodeType().name()))
                .filter(n -> !Boolean.TRUE.equals(n.getStale()))
                .filter(n -> getEdgesForNode(n.getNodeId()).isEmpty())
                .map(ai.kompile.knowledgegraph.domain.GraphNode::getNodeId)
                .collect(java.util.stream.Collectors.toList());
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
                .collect(java.util.stream.Collectors.toList());
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
                .collect(java.util.stream.Collectors.toList());
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
                .collect(java.util.stream.Collectors.toList());
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
        List<String> ids = new java.util.ArrayList<>(nodeIds);
        if (dryRun) {
            return GraphPruneResult.ofSoftDelete(ids, true);
        }
        if (softDelete) {
            // Mark stale via metadata — stores with a dedicated stale column should override
            for (String nodeId : ids) {
                try {
                    updateNode(nodeId, null, null,
                            java.util.Map.of("_stale", true,
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
        List<String> ids = new java.util.ArrayList<>(edgeIds);
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
                        ? java.util.Map.of("_raw", node.getMetadataJson())
                        : java.util.Map.of());
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
                .collect(java.util.stream.Collectors.toList());
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
        java.util.stream.Stream<GraphEdge> stream = all.stream();
        if (minWeight != null) {
            stream = stream.filter(e -> e.getWeight() != null && e.getWeight() >= minWeight);
        }
        return stream.limit(limit).collect(java.util.stream.Collectors.toList());
    }

    /**
     * Get edges of a specific type globally, optionally filtered by minimum weight,
     * up to {@code limit} results.
     * Default: delegates to {@link #getEdgesByType} using edges for all nodes.
     */
    default List<GraphEdge> getStrongEdgesByType(EdgeType edgeType, Double minWeight, int limit) {
        List<GraphEdge> all = searchEdges(null, edgeType, limit * 2);
        java.util.stream.Stream<GraphEdge> stream = all.stream();
        if (minWeight != null) {
            stream = stream.filter(e -> e.getWeight() != null && e.getWeight() >= minWeight);
        }
        return stream.limit(limit).collect(java.util.stream.Collectors.toList());
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
}
