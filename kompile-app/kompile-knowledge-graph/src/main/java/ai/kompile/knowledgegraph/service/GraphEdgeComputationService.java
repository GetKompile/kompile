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

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Service interface for computing graph edges automatically.
 * Handles embedding similarity edges and shared entity edges.
 */
public interface GraphEdgeComputationService {

    // ═══════════════════════════════════════════════════════════════════════════
    // EMBEDDING SIMILARITY EDGES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Compute embedding similarity edges for all nodes
     *
     * @param minSimilarity Minimum similarity threshold (0.0 to 1.0)
     * @param maxEdgesPerNode Maximum edges to create per node
     */
    void computeEmbeddingSimilarityEdges(double minSimilarity, int maxEdgesPerNode);

    /**
     * Compute embedding similarity edges for a specific node
     *
     * @param nodeId Node UUID
     * @param minSimilarity Minimum similarity threshold
     */
    void computeEmbeddingSimilarityEdgesForNode(String nodeId, double minSimilarity);

    /**
     * Update similarity edges incrementally (for newly added nodes)
     *
     * @param nodeIds List of new node UUIDs
     * @param minSimilarity Minimum similarity threshold
     */
    void updateSimilarityEdgesIncremental(java.util.List<String> nodeIds, double minSimilarity);

    // ═══════════════════════════════════════════════════════════════════════════
    // SHARED ENTITY EDGES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Compute shared entity edges based on entity mentions
     *
     * @param minSharedEntities Minimum number of shared entities to create an edge
     */
    void computeSharedEntityEdges(int minSharedEntities);

    /**
     * Extract entities from a node's content
     *
     * @param nodeId Node UUID
     */
    void extractEntitiesForNode(String nodeId);

    /**
     * Extract entities for all nodes
     */
    void extractEntitiesForAllNodes();

    // ═══════════════════════════════════════════════════════════════════════════
    // BULK OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Recompute all automatic edges
     */
    void recomputeAllEdges();

    /**
     * Prune weak and stale edges
     *
     * @param minWeight Minimum weight to keep
     * @param olderThan Remove edges computed before this time
     * @return Number of edges pruned
     */
    int pruneWeakEdges(double minWeight, LocalDateTime olderThan);

    /**
     * Delete all computed edges (similarity and entity edges)
     * @return Number of edges deleted
     */
    int deleteAllComputedEdges();

    // ═══════════════════════════════════════════════════════════════════════════
    // STATUS & MONITORING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get computation status
     */
    Map<String, Object> getComputationStatus();

    /**
     * Check if computation is currently running
     */
    boolean isComputationRunning();

    /**
     * Cancel ongoing computation
     */
    void cancelComputation();
}
