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

import ai.kompile.knowledgegraph.domain.NamedGraph;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service interface for managing hierarchical named knowledge graphs.
 * Enables a "graph of graphs" concept where users can create, nest,
 * browse, and manipulate named knowledge graphs.
 */
public interface NamedGraphService {

    // ═══════════════════════════════════════════════════════════════════════════
    // CRUD OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Create a new named graph.
     *
     * @param name          Required user-defined name
     * @param description   Optional description
     * @param parentGraphId Optional parent graph UUID (null for root)
     * @param factSheetId   Optional fact sheet scope
     * @param ontologyType  Optional classification (e.g., "domain_ontology", "taxonomy")
     * @return The created NamedGraph
     */
    NamedGraph createGraph(String name, String description, String parentGraphId,
                           Long factSheetId, String ontologyType);

    /**
     * Retrieve a named graph by its external UUID.
     *
     * @param graphId External UUID
     * @return Optional containing the graph if found
     */
    Optional<NamedGraph> getGraph(String graphId);

    /**
     * Update a named graph's mutable fields.
     *
     * @param graphId      External UUID of the graph to update
     * @param name         New name (null = no change)
     * @param description  New description (null = no change)
     * @param metadataJson New JSON metadata (null = no change)
     * @return The updated NamedGraph
     */
    NamedGraph updateGraph(String graphId, String name, String description, String metadataJson);

    /**
     * Delete a named graph and all its descendants recursively.
     *
     * @param graphId External UUID of the graph to delete
     */
    void deleteGraph(String graphId);

    // ═══════════════════════════════════════════════════════════════════════════
    // HIERARCHY NAVIGATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get all root-level graphs (those with no parent).
     *
     * @return List of root graphs
     */
    List<NamedGraph> getRootGraphs();

    /**
     * Get all direct children of the specified parent graph.
     *
     * @param parentGraphId External UUID of the parent graph
     * @return List of child graphs
     */
    List<NamedGraph> getChildGraphs(String parentGraphId);

    /**
     * Get the full hierarchy tree rooted at the given graph, up to maxDepth levels.
     * Returns a nested map structure with graph metadata and a "children" key containing
     * nested subtrees.
     *
     * @param graphId  External UUID of the root graph
     * @param maxDepth Maximum depth to traverse (0 = just the root node itself)
     * @return Nested map representing the hierarchy tree
     */
    Map<String, Object> getGraphHierarchy(String graphId, int maxDepth);

    /**
     * Get the ancestor chain from the outermost root down to (but not including)
     * the specified graph, ordered root-first.
     *
     * @param graphId External UUID of the graph
     * @return Ordered list of ancestor graphs, root-first; empty if the graph is already a root
     */
    List<NamedGraph> getAncestors(String graphId);

    /**
     * Move a graph to a new parent, or promote it to a root graph.
     *
     * @param graphId         External UUID of the graph to move
     * @param newParentGraphId External UUID of the new parent (null to make root)
     * @return The updated NamedGraph
     */
    NamedGraph moveGraph(String graphId, String newParentGraphId);

    // ═══════════════════════════════════════════════════════════════════════════
    // SEARCH
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Search named graphs by name (case-insensitive substring match).
     *
     * @param query Search query
     * @return List of matching graphs
     */
    List<NamedGraph> searchGraphs(String query);

    // ═══════════════════════════════════════════════════════════════════════════
    // NODE MEMBERSHIP
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Associate an existing GraphNode with a named graph.
     * Updates the node's namedGraphId field and increments the graph's nodeCount.
     *
     * @param nodeId  External UUID of the GraphNode
     * @param graphId External UUID of the NamedGraph
     */
    void linkNodeToGraph(String nodeId, String graphId);

    /**
     * Remove the association of a GraphNode from a named graph.
     * Clears the node's namedGraphId field and decrements the graph's nodeCount.
     *
     * @param nodeId  External UUID of the GraphNode
     * @param graphId External UUID of the NamedGraph
     */
    void unlinkNodeFromGraph(String nodeId, String graphId);

    // ═══════════════════════════════════════════════════════════════════════════
    // STATISTICS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get statistics for the specified graph including node count, edge count,
     * child graph count, depth in the hierarchy, and descendant count.
     *
     * @param graphId External UUID of the graph
     * @return Map containing statistics entries
     */
    Map<String, Object> getGraphStatistics(String graphId);
}
