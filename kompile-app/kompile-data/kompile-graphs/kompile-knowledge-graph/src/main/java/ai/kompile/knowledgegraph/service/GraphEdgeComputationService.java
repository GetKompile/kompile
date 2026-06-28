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
import java.util.List;
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
     * Compute embedding similarity edges scoped to a fact sheet
     *
     * @param factSheetId Fact sheet scope (null for global)
     * @param minSimilarity Minimum similarity threshold (0.0 to 1.0)
     * @param maxEdgesPerNode Maximum edges to create per node
     */
    void computeEmbeddingSimilarityEdges(Long factSheetId, double minSimilarity, int maxEdgesPerNode);

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
    void updateSimilarityEdgesIncremental(List<String> nodeIds, double minSimilarity);

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
     * Compute shared entity edges scoped to a fact sheet
     *
     * @param factSheetId Fact sheet scope (null for global)
     * @param minSharedEntities Minimum number of shared entities to create an edge
     */
    void computeSharedEntityEdges(Long factSheetId, int minSharedEntities);

    /**
     * Embedding-free cross-document entity resolution: links ENTITY nodes across different
     * source documents when they share the same normalized name (lowercase + trim + collapse
     * whitespace) AND the same entityType metadata.  Creates a {@code SHARED_ENTITY} edge
     * between each unique pair, giving isolated per-document entity islands a cross-doc
     * connection that keeps them from being swept as small components.
     *
     * <p>This pass runs even when the embedding model is offline, so it always fires during
     * the EDGE_COMPUTATION crawl step regardless of GPU/embedding availability.</p>
     *
     * @param factSheetId fact sheet to scope the resolution (null = global)
     */
    default void computeNameBasedCrossDocEdges(Long factSheetId) {
        // Default no-op; overridden by GraphEdgeComputationServiceImpl.
    }

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

    /**
     * Collapse the existing cross-doc name-resolution SHARED_ENTITY edges (the legacy O(k²) clique
     * bloat) and recompute them as a STAR (the fixed topology). Deletes ONLY edges whose description
     * marks them as name-based cross-doc resolution; entity-mention shared edges and embedding
     * similarity edges are untouched. Numbers-first: {@code dryRun=true} only counts what would change.
     *
     * @param factSheetId fact sheet to scope (null = global)
     * @param dryRun      when true, report counts without deleting/recomputing
     * @return summary map (existingCrossDocEdges, deleted, crossDocEdgesAfter, dryRun)
     */
    default Map<String, Object> rebuildCrossDocEdges(Long factSheetId, boolean dryRun) {
        return Map.of("error", "not implemented");
    }

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
