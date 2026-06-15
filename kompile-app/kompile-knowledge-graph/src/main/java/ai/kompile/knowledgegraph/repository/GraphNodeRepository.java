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

import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
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
 * Repository for GraphNode entities.
 */
@Repository
public interface GraphNodeRepository extends JpaRepository<GraphNode, Long> {

    /**
     * Find node by its external UUID
     */
    Optional<GraphNode> findByNodeId(String nodeId);

    /**
     * Find node by external ID and type
     */
    Optional<GraphNode> findByExternalIdAndNodeType(String externalId, NodeLevel nodeType);

    /**
     * Find all nodes of a specific type
     */
    List<GraphNode> findByNodeType(NodeLevel nodeType);

    /**
     * Find all nodes of a specific type (paginated)
     */
    Page<GraphNode> findByNodeType(NodeLevel nodeType, Pageable pageable);

    /**
     * Find children of a parent node
     */
    List<GraphNode> findByParent(GraphNode parent);

    /**
     * Find all nodes belonging to a source
     */
    List<GraphNode> findBySourceNode(GraphNode sourceNode);

    /**
     * Find children by parent nodeId
     */
    @Query("SELECT n FROM GraphNode n WHERE n.parent.nodeId = :parentId ORDER BY n.title")
    List<GraphNode> findChildrenByParentId(@Param("parentId") String parentId);

    /**
     * Find nodes by source nodeId and type
     */
    @Query("SELECT n FROM GraphNode n WHERE n.sourceNode.nodeId = :sourceId AND n.nodeType = :type")
    List<GraphNode> findBySourceIdAndType(@Param("sourceId") String sourceId, @Param("type") NodeLevel type);

    /**
     * Find top sources by child count
     */
    @Query("SELECT n FROM GraphNode n WHERE n.nodeType = :type ORDER BY n.childCount DESC")
    List<GraphNode> findTopByChildCount(@Param("type") NodeLevel type, Pageable pageable);

    /**
     * Count nodes by type
     */
    long countByNodeType(NodeLevel type);

    /**
     * Search nodes by title or description
     */
    @Query("SELECT n FROM GraphNode n WHERE LOWER(n.title) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "OR LOWER(n.description) LIKE LOWER(CONCAT('%', :query, '%'))")
    Page<GraphNode> searchByTitleOrDescription(@Param("query") String query, Pageable pageable);

    /**
     * Search nodes by title or description filtered by type
     */
    @Query("SELECT n FROM GraphNode n WHERE n.nodeType = :type AND " +
           "(LOWER(n.title) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "OR LOWER(n.description) LIKE LOWER(CONCAT('%', :query, '%')))")
    Page<GraphNode> searchByTitleOrDescriptionAndType(
        @Param("query") String query,
        @Param("type") NodeLevel type,
        Pageable pageable
    );

    /**
     * Delete by external ID
     */
    void deleteByExternalId(String externalId);

    /**
     * Check existence by external ID and type
     */
    boolean existsByExternalIdAndNodeType(String externalId, NodeLevel nodeType);

    /**
     * Find source nodes with documents
     */
    @Query("SELECT DISTINCT n.sourceNode FROM GraphNode n WHERE n.sourceNode IS NOT NULL AND n.nodeType = 'DOCUMENT'")
    List<GraphNode> findSourcesWithDocuments();

    /**
     * Find all source nodes
     */
    @Query("SELECT n FROM GraphNode n WHERE n.nodeType = 'SOURCE' ORDER BY n.title")
    List<GraphNode> findAllSources();

    /**
     * Find orphan nodes (no parent, not a source)
     */
    @Query("SELECT n FROM GraphNode n WHERE n.parent IS NULL AND n.nodeType != 'SOURCE'")
    List<GraphNode> findOrphanNodes();

    // ═══════════════════════════════════════════════════════════════════════════
    // FACT SHEET SCOPED QUERIES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Find all nodes belonging to a fact sheet
     */
    List<GraphNode> findByFactSheetId(Long factSheetId);

    /**
     * Find nodes by fact sheet and type
     */
    List<GraphNode> findByFactSheetIdAndNodeType(Long factSheetId, NodeLevel nodeType);

    /**
     * Find nodes by fact sheet and type (paginated)
     */
    Page<GraphNode> findByFactSheetIdAndNodeType(Long factSheetId, NodeLevel nodeType, Pageable pageable);

    /**
     * Find all source nodes for a fact sheet
     */
    @Query("SELECT n FROM GraphNode n WHERE n.factSheetId = :factSheetId AND n.nodeType = 'SOURCE' ORDER BY n.title")
    List<GraphNode> findSourcesByFactSheet(@Param("factSheetId") Long factSheetId);

    /**
     * Find children by parent nodeId within a fact sheet
     */
    @Query("SELECT n FROM GraphNode n WHERE n.parent.nodeId = :parentId AND n.factSheetId = :factSheetId ORDER BY n.title")
    List<GraphNode> findChildrenByParentIdAndFactSheet(
        @Param("parentId") String parentId,
        @Param("factSheetId") Long factSheetId
    );

    /**
     * Search nodes by title or description within a fact sheet
     */
    @Query("SELECT n FROM GraphNode n WHERE n.factSheetId = :factSheetId AND " +
           "(LOWER(n.title) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "OR LOWER(n.description) LIKE LOWER(CONCAT('%', :query, '%')))")
    Page<GraphNode> searchByFactSheetAndQuery(
        @Param("factSheetId") Long factSheetId,
        @Param("query") String query,
        Pageable pageable
    );

    /**
     * Find entity nodes within a fact sheet
     */
    @Query("SELECT n FROM GraphNode n WHERE n.factSheetId = :factSheetId AND n.nodeType = 'ENTITY' ORDER BY n.title")
    List<GraphNode> findEntitiesByFactSheet(@Param("factSheetId") Long factSheetId);

    /**
     * Count nodes by type within a fact sheet
     */
    @Query("SELECT COUNT(n) FROM GraphNode n WHERE n.factSheetId = :factSheetId AND n.nodeType = :type")
    long countByFactSheetIdAndNodeType(@Param("factSheetId") Long factSheetId, @Param("type") NodeLevel type);

    /**
     * Find node by external ID, type, and fact sheet
     */
    Optional<GraphNode> findByExternalIdAndNodeTypeAndFactSheetId(String externalId, NodeLevel nodeType, Long factSheetId);

    /**
     * Delete all nodes in a fact sheet
     */
    @Modifying
    @Query("DELETE FROM GraphNode n WHERE n.factSheetId = :factSheetId")
    int deleteByFactSheetId(@Param("factSheetId") Long factSheetId);

    // ═══════════════════════════════════════════════════════════════════════════
    // KG EMBEDDING QUERIES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Find nodes by a set of external IDs and node type, scoped to a fact sheet
     */
    @Query("SELECT n FROM GraphNode n WHERE n.externalId IN :externalIds AND n.nodeType = :nodeType AND n.factSheetId = :factSheetId")
    List<GraphNode> findByExternalIdInAndNodeTypeAndFactSheetId(
        @Param("externalIds") java.util.Set<String> externalIds,
        @Param("nodeType") NodeLevel nodeType,
        @Param("factSheetId") Long factSheetId
    );

    /**
     * Find nodes by a set of external IDs and node type (no fact sheet scope)
     */
    @Query("SELECT n FROM GraphNode n WHERE n.externalId IN :externalIds AND n.nodeType = :nodeType")
    List<GraphNode> findByExternalIdInAndNodeType(
        @Param("externalIds") java.util.Set<String> externalIds,
        @Param("nodeType") NodeLevel nodeType
    );

    /**
     * Find nodes by a list of node UUIDs
     */
    @Query("SELECT n FROM GraphNode n WHERE n.nodeId IN :nodeIds")
    List<GraphNode> findByNodeIdIn(@Param("nodeIds") java.util.List<String> nodeIds);

    /**
     * Find nodes with KG embeddings in a fact sheet
     */
    @Query("SELECT n FROM GraphNode n WHERE n.factSheetId = :factSheetId AND n.kgEmbedding IS NOT NULL")
    List<GraphNode> findByFactSheetIdAndKgEmbeddingNotNull(@Param("factSheetId") Long factSheetId);

    /**
     * Find nodes with KG embeddings by version
     */
    @Query("SELECT n FROM GraphNode n WHERE n.factSheetId = :factSheetId AND n.kgEmbeddingVersion = :version")
    List<GraphNode> findByFactSheetIdAndKgEmbeddingVersion(
        @Param("factSheetId") Long factSheetId,
        @Param("version") Long version
    );

    /**
     * Count nodes with KG embeddings in a fact sheet
     */
    @Query("SELECT COUNT(n) FROM GraphNode n WHERE n.factSheetId = :factSheetId AND n.kgEmbedding IS NOT NULL")
    long countByFactSheetIdAndKgEmbeddingNotNull(@Param("factSheetId") Long factSheetId);

    /**
     * Find entity nodes that don't have KG embeddings yet
     */
    @Query("SELECT n FROM GraphNode n WHERE n.factSheetId = :factSheetId AND n.nodeType = 'ENTITY' AND n.kgEmbedding IS NULL")
    List<GraphNode> findEntitiesWithoutKgEmbedding(@Param("factSheetId") Long factSheetId);

    // ═══════════════════════════════════════════════════════════════════════════
    // MAINTENANCE QUERIES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Find active (non-stale) entity nodes in a fact sheet
     */
    @Query("SELECT n FROM GraphNode n WHERE n.factSheetId = :factSheetId AND (n.stale IS NULL OR n.stale = false)")
    List<GraphNode> findActiveEntities(@Param("factSheetId") Long factSheetId);

    /**
     * Count active (non-stale) nodes in a fact sheet
     */
    @Query("SELECT COUNT(n) FROM GraphNode n WHERE n.factSheetId = :factSheetId AND (n.stale IS NULL OR n.stale = false)")
    long countActiveNodes(@Param("factSheetId") Long factSheetId);

    /**
     * Find orphan entity nodes (no edges connecting them) in a fact sheet
     */
    @Query("SELECT n FROM GraphNode n WHERE n.factSheetId = :factSheetId " +
           "AND n.nodeType = 'ENTITY' AND (n.stale IS NULL OR n.stale = false) " +
           "AND NOT EXISTS (SELECT e FROM GraphEdge e WHERE e.sourceNode = n OR e.targetNode = n)")
    List<GraphNode> findGraphOrphanEntities(@Param("factSheetId") Long factSheetId);

    /**
     * Find entities with low confidence in a fact sheet
     */
    @Query("SELECT n FROM GraphNode n WHERE n.factSheetId = :factSheetId " +
           "AND (n.stale IS NULL OR n.stale = false) " +
           "AND n.confidence IS NOT NULL AND n.confidence < :minConfidence")
    List<GraphNode> findLowConfidenceEntities(
        @Param("factSheetId") Long factSheetId,
        @Param("minConfidence") double minConfidence
    );

    /**
     * Find nodes whose TTL has expired
     */
    @Query("SELECT n FROM GraphNode n WHERE n.factSheetId = :factSheetId " +
           "AND (n.stale IS NULL OR n.stale = false) " +
           "AND n.validUntil IS NOT NULL AND n.validUntil < :now")
    List<GraphNode> findExpiredNodes(
        @Param("factSheetId") Long factSheetId,
        @Param("now") LocalDateTime now
    );

    /**
     * Bulk mark nodes as stale by their database IDs
     */
    @Modifying
    @Query("UPDATE GraphNode n SET n.stale = true, n.staleAt = :now WHERE n.id IN :ids")
    void bulkMarkStale(@Param("ids") List<Long> ids, @Param("now") LocalDateTime now);

    /**
     * Hard-delete nodes that were marked stale before the grace cutoff
     */
    @Modifying
    @Query("DELETE FROM GraphNode n WHERE n.factSheetId = :factSheetId " +
           "AND n.stale = true AND n.staleAt IS NOT NULL AND n.staleAt < :graceCutoff")
    int hardDeleteStaleNodes(
        @Param("factSheetId") Long factSheetId,
        @Param("graceCutoff") LocalDateTime graceCutoff
    );
}
