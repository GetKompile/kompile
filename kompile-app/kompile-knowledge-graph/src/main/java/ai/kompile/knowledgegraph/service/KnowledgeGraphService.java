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

import ai.kompile.knowledgegraph.domain.*;

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

        GraphNode tableNode = createNode(NodeLevel.TABLE, externalId, tableTitle,
                description, tableMeta);

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
}
