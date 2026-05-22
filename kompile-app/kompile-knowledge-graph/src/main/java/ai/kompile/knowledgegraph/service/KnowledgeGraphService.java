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
     * Get a node by its UUID
     */
    Optional<GraphNode> getNode(String nodeId);

    /**
     * Get a node by external ID and type
     */
    Optional<GraphNode> getNodeByExternalId(String externalId, NodeLevel nodeType);

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
