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
package ai.kompile.knowledgegraph.repository;

import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for GraphEdge entities.
 */
@Repository
public interface GraphEdgeRepository extends JpaRepository<GraphEdge, Long> {

    /**
     * Find edge by its UUID
     */
    Optional<GraphEdge> findByEdgeId(String edgeId);

    /**
     * Find all edges from a source node
     */
    List<GraphEdge> findBySourceNode(GraphNode sourceNode);

    /**
     * Find all edges to a target node
     */
    List<GraphEdge> findByTargetNode(GraphNode targetNode);

    /**
     * Find edges by type
     */
    List<GraphEdge> findByEdgeType(EdgeType edgeType);

    /**
     * Find edges by type (paginated)
     */
    Page<GraphEdge> findByEdgeType(EdgeType edgeType, Pageable pageable);

    /**
     * Find outgoing edges (including reverse bidirectional)
     */
    @Query("SELECT e FROM GraphEdge e WHERE e.sourceNode = :node OR (e.targetNode = :node AND e.bidirectional = true)")
    List<GraphEdge> findOutgoingEdges(@Param("node") GraphNode node);

    /**
     * Find incoming edges (including forward bidirectional)
     */
    @Query("SELECT e FROM GraphEdge e WHERE e.targetNode = :node OR (e.sourceNode = :node AND e.bidirectional = true)")
    List<GraphEdge> findIncomingEdges(@Param("node") GraphNode node);

    /**
     * Find all edges connected to a node (either direction)
     */
    @Query("SELECT e FROM GraphEdge e WHERE e.sourceNode = :node OR e.targetNode = :node")
    List<GraphEdge> findAllEdgesForNode(@Param("node") GraphNode node);

    /**
     * Find edges by node ID (either direction)
     */
    @Query("SELECT e FROM GraphEdge e WHERE e.sourceNode.nodeId = :nodeId OR e.targetNode.nodeId = :nodeId")
    List<GraphEdge> findAllEdgesForNodeId(@Param("nodeId") String nodeId);

    /**
     * Find edges by node and type, ordered by weight
     */
    @Query("SELECT e FROM GraphEdge e WHERE (e.sourceNode = :node OR e.targetNode = :node) " +
           "AND e.edgeType = :type ORDER BY e.weight DESC")
    List<GraphEdge> findEdgesByNodeAndType(
        @Param("node") GraphNode node,
        @Param("type") EdgeType type,
        Pageable pageable
    );

    /**
     * Find edge between two specific nodes
     */
    @Query("SELECT e FROM GraphEdge e WHERE e.sourceNode.nodeId = :nodeId1 AND e.targetNode.nodeId = :nodeId2")
    Optional<GraphEdge> findEdgeBetweenNodes(@Param("nodeId1") String nodeId1, @Param("nodeId2") String nodeId2);

    /**
     * Find edge between two nodes (either direction)
     */
    @Query("SELECT e FROM GraphEdge e WHERE " +
           "(e.sourceNode.nodeId = :nodeId1 AND e.targetNode.nodeId = :nodeId2) OR " +
           "(e.sourceNode.nodeId = :nodeId2 AND e.targetNode.nodeId = :nodeId1)")
    Optional<GraphEdge> findEdgeBetweenNodesBidirectional(
        @Param("nodeId1") String nodeId1,
        @Param("nodeId2") String nodeId2
    );

    /**
     * Find strong edges by type
     */
    @Query("SELECT e FROM GraphEdge e WHERE e.edgeType = :type AND e.weight >= :minWeight " +
           "ORDER BY e.weight DESC")
    List<GraphEdge> findStrongEdgesByType(
        @Param("type") EdgeType type,
        @Param("minWeight") Double minWeight,
        Pageable pageable
    );

    /**
     * Delete stale computed edges
     */
    @Modifying
    @Query("DELETE FROM GraphEdge e WHERE e.edgeType = :type AND e.computedAt < :before")
    int deleteStaleComputedEdges(@Param("type") EdgeType type, @Param("before") LocalDateTime before);

    /**
     * Delete weak edges
     */
    @Modifying
    @Query("DELETE FROM GraphEdge e WHERE e.weight < :minWeight AND e.edgeType != 'HIERARCHICAL' AND e.edgeType != 'USER_DEFINED'")
    int deleteWeakEdges(@Param("minWeight") Double minWeight);

    /**
     * Count edges by type
     */
    long countByEdgeType(EdgeType edgeType);

    /**
     * Search edges by description
     */
    @Query("SELECT e FROM GraphEdge e WHERE LOWER(e.description) LIKE :query " +
           "AND (:edgeType IS NULL OR e.edgeType = :edgeType) " +
           "ORDER BY e.weight DESC")
    List<GraphEdge> searchByDescription(
        @Param("query") String query,
        @Param("edgeType") EdgeType edgeType,
        Pageable pageable
    );

    /**
     * Find all hierarchical edges from a source
     */
    @Query("SELECT e FROM GraphEdge e WHERE e.sourceNode.nodeId = :sourceId AND e.edgeType = 'HIERARCHICAL'")
    List<GraphEdge> findHierarchicalEdgesFromSource(@Param("sourceId") String sourceId);

    /**
     * Delete all edges connected to a node
     */
    @Modifying
    @Query("DELETE FROM GraphEdge e WHERE e.sourceNode = :node OR e.targetNode = :node")
    int deleteAllEdgesForNode(@Param("node") GraphNode node);

    // ═══════════════════════════════════════════════════════════════════════════
    // FACT SHEET SCOPED QUERIES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Find all edges belonging to a fact sheet
     */
    List<GraphEdge> findByFactSheetId(Long factSheetId);

    /**
     * Find edges by fact sheet and type
     */
    List<GraphEdge> findByFactSheetIdAndEdgeType(Long factSheetId, EdgeType edgeType);

    /**
     * Find edges by fact sheet and type (paginated)
     */
    Page<GraphEdge> findByFactSheetIdAndEdgeType(Long factSheetId, EdgeType edgeType, Pageable pageable);

    /**
     * Find all edges for a node within a fact sheet
     */
    @Query("SELECT e FROM GraphEdge e WHERE e.factSheetId = :factSheetId AND " +
           "(e.sourceNode.nodeId = :nodeId OR e.targetNode.nodeId = :nodeId)")
    List<GraphEdge> findAllEdgesForNodeIdInFactSheet(
        @Param("nodeId") String nodeId,
        @Param("factSheetId") Long factSheetId
    );

    /**
     * Find strong edges by type within a fact sheet
     */
    @Query("SELECT e FROM GraphEdge e WHERE e.factSheetId = :factSheetId AND " +
           "e.edgeType = :type AND e.weight >= :minWeight ORDER BY e.weight DESC")
    List<GraphEdge> findStrongEdgesByTypeAndFactSheet(
        @Param("factSheetId") Long factSheetId,
        @Param("type") EdgeType type,
        @Param("minWeight") Double minWeight,
        Pageable pageable
    );

    /**
     * Find edge between two nodes within a fact sheet
     */
    @Query("SELECT e FROM GraphEdge e WHERE e.factSheetId = :factSheetId AND " +
           "e.sourceNode.nodeId = :nodeId1 AND e.targetNode.nodeId = :nodeId2")
    Optional<GraphEdge> findEdgeBetweenNodesInFactSheet(
        @Param("nodeId1") String nodeId1,
        @Param("nodeId2") String nodeId2,
        @Param("factSheetId") Long factSheetId
    );

    /**
     * Count edges by type within a fact sheet
     */
    @Query("SELECT COUNT(e) FROM GraphEdge e WHERE e.factSheetId = :factSheetId AND e.edgeType = :type")
    long countByFactSheetIdAndEdgeType(@Param("factSheetId") Long factSheetId, @Param("type") EdgeType type);

    /**
     * Delete all edges in a fact sheet
     */
    @Modifying
    @Query("DELETE FROM GraphEdge e WHERE e.factSheetId = :factSheetId")
    int deleteByFactSheetId(@Param("factSheetId") Long factSheetId);

    /**
     * Find cross-source edges within a fact sheet (edges between different sources)
     */
    @Query("SELECT e FROM GraphEdge e WHERE e.factSheetId = :factSheetId AND " +
           "e.edgeType = 'CROSS_SOURCE' ORDER BY e.weight DESC")
    List<GraphEdge> findCrossSourceEdgesByFactSheet(
        @Param("factSheetId") Long factSheetId,
        Pageable pageable
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // KG EMBEDDING QUERIES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Find edges with KG relation embeddings in a fact sheet
     */
    @Query("SELECT e FROM GraphEdge e WHERE e.factSheetId = :factSheetId AND e.kgRelationEmbedding IS NOT NULL")
    List<GraphEdge> findByFactSheetIdAndKgRelationEmbeddingNotNull(@Param("factSheetId") Long factSheetId);

    /**
     * Find edges with KG embeddings by version
     */
    @Query("SELECT e FROM GraphEdge e WHERE e.factSheetId = :factSheetId AND e.kgEmbeddingVersion = :version")
    List<GraphEdge> findByFactSheetIdAndKgEmbeddingVersion(
        @Param("factSheetId") Long factSheetId,
        @Param("version") Long version
    );

    /**
     * Get distinct edge types in a fact sheet (for relation embedding)
     */
    @Query("SELECT DISTINCT e.edgeType FROM GraphEdge e WHERE e.factSheetId = :factSheetId")
    List<EdgeType> findDistinctEdgeTypesByFactSheet(@Param("factSheetId") Long factSheetId);

    /**
     * Count edges with KG embeddings in a fact sheet
     */
    @Query("SELECT COUNT(e) FROM GraphEdge e WHERE e.factSheetId = :factSheetId AND e.kgRelationEmbedding IS NOT NULL")
    long countByFactSheetIdAndKgRelationEmbeddingNotNull(@Param("factSheetId") Long factSheetId);

    // ═══════════════════════════════════════════════════════════════════════════
    // GRAPHRAG RETRIEVAL QUERIES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Find edges whose source node UUID is in the given list
     */
    @Query("SELECT e FROM GraphEdge e WHERE e.sourceNode.nodeId IN :nodeIds")
    List<GraphEdge> findBySourceNodeNodeIdIn(@Param("nodeIds") java.util.List<String> nodeIds);

    /**
     * Find edges whose target node UUID is in the given list
     */
    @Query("SELECT e FROM GraphEdge e WHERE e.targetNode.nodeId IN :nodeIds")
    List<GraphEdge> findByTargetNodeNodeIdIn(@Param("nodeIds") java.util.List<String> nodeIds);

    /**
     * Find outgoing edges by source node title within a fact sheet.
     * Used by KGEmbeddingRetriever for graph expansion.
     */
    @Query("SELECT e FROM GraphEdge e WHERE e.factSheetId = :factSheetId AND e.sourceNode.title = :title")
    List<GraphEdge> findBySourceNodeTitleAndFactSheetId(
        @Param("title") String sourceNodeTitle,
        @Param("factSheetId") Long factSheetId
    );

    /**
     * Find incoming edges by target node title within a fact sheet.
     * Used by KGEmbeddingRetriever for graph expansion.
     */
    @Query("SELECT e FROM GraphEdge e WHERE e.factSheetId = :factSheetId AND e.targetNode.title = :title")
    List<GraphEdge> findByTargetNodeTitleAndFactSheetId(
        @Param("title") String targetNodeTitle,
        @Param("factSheetId") Long factSheetId
    );

    /**
     * Find all edges connected to a node by its database ID (either as source or target).
     * Used by KGEmbeddingRetriever for building entity context.
     */
    @Query("SELECT e FROM GraphEdge e WHERE e.sourceNode.id = :nodeId OR e.targetNode.id = :nodeId")
    List<GraphEdge> findBySourceNodeIdOrTargetNodeId(@Param("nodeId") Long nodeId);

    // ═══════════════════════════════════════════════════════════════════════════
    // TEMPORAL QUERIES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Find edges that occurred within a time range
     */
    @Query("SELECT e FROM GraphEdge e WHERE e.occurredAt IS NOT NULL " +
           "AND e.occurredAt >= :from AND e.occurredAt <= :to " +
           "ORDER BY e.occurredAt DESC")
    List<GraphEdge> findByOccurredAtBetween(
        @Param("from") LocalDateTime from,
        @Param("to") LocalDateTime to,
        Pageable pageable
    );

    /**
     * Find edges within a fact sheet that occurred in a time range
     */
    @Query("SELECT e FROM GraphEdge e WHERE e.factSheetId = :factSheetId " +
           "AND e.occurredAt IS NOT NULL " +
           "AND e.occurredAt >= :from AND e.occurredAt <= :to " +
           "ORDER BY e.occurredAt DESC")
    List<GraphEdge> findByFactSheetIdAndOccurredAtBetween(
        @Param("factSheetId") Long factSheetId,
        @Param("from") LocalDateTime from,
        @Param("to") LocalDateTime to,
        Pageable pageable
    );

    /**
     * Find edges for a node that occurred in a time range
     */
    @Query("SELECT e FROM GraphEdge e WHERE " +
           "(e.sourceNode.nodeId = :nodeId OR e.targetNode.nodeId = :nodeId) " +
           "AND e.occurredAt IS NOT NULL " +
           "AND e.occurredAt >= :from AND e.occurredAt <= :to " +
           "ORDER BY e.occurredAt DESC")
    List<GraphEdge> findEdgesForNodeInTimeRange(
        @Param("nodeId") String nodeId,
        @Param("from") LocalDateTime from,
        @Param("to") LocalDateTime to
    );

    /**
     * Find the earliest and latest occurredAt timestamps in the graph
     */
    @Query("SELECT MIN(e.occurredAt), MAX(e.occurredAt) FROM GraphEdge e WHERE e.occurredAt IS NOT NULL")
    List<Object[]> findTemporalBounds();

    /**
     * Find temporal bounds within a fact sheet
     */
    @Query("SELECT MIN(e.occurredAt), MAX(e.occurredAt) FROM GraphEdge e " +
           "WHERE e.factSheetId = :factSheetId AND e.occurredAt IS NOT NULL")
    List<Object[]> findTemporalBoundsByFactSheet(@Param("factSheetId") Long factSheetId);

    /**
     * Count edges with occurredAt set (for temporal coverage stats)
     */
    @Query("SELECT COUNT(e) FROM GraphEdge e WHERE e.occurredAt IS NOT NULL")
    long countWithOccurredAt();

    // ═══════════════════════════════════════════════════════════════════════════
    // MAINTENANCE QUERIES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Find active (non-stale) edges in a fact sheet
     */
    @Query("SELECT e FROM GraphEdge e WHERE e.factSheetId = :factSheetId AND (e.stale IS NULL OR e.stale = false)")
    List<GraphEdge> findActiveEdges(@Param("factSheetId") Long factSheetId);

    /**
     * Count active (non-stale) edges in a fact sheet
     */
    @Query("SELECT COUNT(e) FROM GraphEdge e WHERE e.factSheetId = :factSheetId AND (e.stale IS NULL OR e.stale = false)")
    long countActiveEdges(@Param("factSheetId") Long factSheetId);

    /**
     * Find edges with low confidence in a fact sheet
     */
    @Query("SELECT e FROM GraphEdge e WHERE e.factSheetId = :factSheetId " +
           "AND (e.stale IS NULL OR e.stale = false) " +
           "AND e.confidence IS NOT NULL AND e.confidence < :minConfidence")
    List<GraphEdge> findLowConfidenceEdges(
        @Param("factSheetId") Long factSheetId,
        @Param("minConfidence") double minConfidence
    );

    /**
     * Find edges whose TTL has expired
     */
    @Query("SELECT e FROM GraphEdge e WHERE e.factSheetId = :factSheetId " +
           "AND (e.stale IS NULL OR e.stale = false) " +
           "AND e.validUntil IS NOT NULL AND e.validUntil < :now")
    List<GraphEdge> findExpiredEdges(
        @Param("factSheetId") Long factSheetId,
        @Param("now") LocalDateTime now
    );

    /**
     * Bulk mark edges as stale by their database IDs
     */
    @Modifying
    @Query("UPDATE GraphEdge e SET e.stale = true, e.staleAt = :now WHERE e.id IN :ids")
    void bulkMarkStale(@Param("ids") List<Long> ids, @Param("now") LocalDateTime now);

    /**
     * Hard-delete edges that were marked stale before the grace cutoff
     */
    @Modifying
    @Query("DELETE FROM GraphEdge e WHERE e.factSheetId = :factSheetId " +
           "AND e.stale = true AND e.staleAt IS NOT NULL AND e.staleAt < :graceCutoff")
    int hardDeleteStaleEdges(
        @Param("factSheetId") Long factSheetId,
        @Param("graceCutoff") LocalDateTime graceCutoff
    );
}
