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

import ai.kompile.knowledgegraph.domain.EntityMention;
import ai.kompile.knowledgegraph.domain.GraphNode;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for EntityMention entities.
 */
@Repository
public interface EntityMentionRepository extends JpaRepository<EntityMention, Long> {

    /**
     * Find all entity mentions in a node
     */
    List<EntityMention> findByNode(GraphNode node);

    /**
     * Find all nodes mentioning an entity
     */
    List<EntityMention> findByEntityName(String entityName);

    /**
     * Find mention by node and entity name
     */
    Optional<EntityMention> findByNodeAndEntityName(GraphNode node, String entityName);

    /**
     * Find entities shared across multiple nodes
     * Returns entity name and count of distinct nodes mentioning it
     */
    @Query("SELECT em.entityName, COUNT(DISTINCT em.node) as docCount " +
           "FROM EntityMention em GROUP BY em.entityName HAVING COUNT(DISTINCT em.node) > 1 " +
           "ORDER BY docCount DESC")
    List<Object[]> findSharedEntities(Pageable pageable);

    /**
     * Find distinct nodes containing a specific entity
     */
    @Query("SELECT DISTINCT em.node FROM EntityMention em WHERE em.entityName = :entityName")
    List<GraphNode> findNodesWithEntity(@Param("entityName") String entityName);

    /**
     * Find entity names in a specific node
     */
    @Query("SELECT em.entityName FROM EntityMention em WHERE em.node = :node")
    List<String> findEntitiesInNode(@Param("node") GraphNode node);

    /**
     * Find entity names by node ID
     */
    @Query("SELECT em.entityName FROM EntityMention em WHERE em.node.nodeId = :nodeId")
    List<String> findEntitiesByNodeId(@Param("nodeId") String nodeId);

    /**
     * Find pairs of nodes with shared entities
     * Returns node1, node2, count of shared entities
     */
    @Query("SELECT em1.node, em2.node, COUNT(em1.entityName) as sharedCount " +
           "FROM EntityMention em1, EntityMention em2 " +
           "WHERE em1.entityName = em2.entityName AND em1.node != em2.node " +
           "AND em1.node.id < em2.node.id " +
           "GROUP BY em1.node, em2.node HAVING COUNT(em1.entityName) >= :minShared")
    List<Object[]> findNodePairsWithSharedEntities(@Param("minShared") int minShared, Pageable pageable);

    /**
     * Find pairs of nodes with shared entities (returns node IDs)
     * Returns node1.id, node2.id, count of shared entities
     */
    @Query("SELECT em1.node.id, em2.node.id, COUNT(em1.entityName) as sharedCount " +
           "FROM EntityMention em1, EntityMention em2 " +
           "WHERE em1.entityName = em2.entityName AND em1.node != em2.node " +
           "AND em1.node.id < em2.node.id " +
           "GROUP BY em1.node.id, em2.node.id HAVING COUNT(em1.entityName) >= :minShared")
    List<Object[]> findNodePairsWithSharedEntities(@Param("minShared") int minShared);

    /**
     * Find entities by type
     */
    @Query("SELECT em FROM EntityMention em WHERE em.entityType = :type")
    List<EntityMention> findByEntityType(@Param("type") String type);

    /**
     * Find top entities overall
     */
    @Query("SELECT em.entityName, SUM(em.mentionCount) as totalMentions " +
           "FROM EntityMention em GROUP BY em.entityName ORDER BY totalMentions DESC")
    List<Object[]> findTopEntities(Pageable pageable);

    /**
     * Delete all mentions for a node
     */
    @Modifying
    @Query("DELETE FROM EntityMention em WHERE em.node = :node")
    int deleteByNode(@Param("node") GraphNode node);

    /**
     * Count distinct entities
     */
    @Query("SELECT COUNT(DISTINCT em.entityName) FROM EntityMention em")
    long countDistinctEntities();

    /**
     * Find mentions with high confidence
     */
    @Query("SELECT em FROM EntityMention em WHERE em.confidence >= :minConfidence ORDER BY em.confidence DESC")
    List<EntityMention> findHighConfidenceMentions(@Param("minConfidence") Double minConfidence, Pageable pageable);

    // ═══════════════════════════════════════════════════════════════════════════
    // FACT SHEET SCOPED QUERIES
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Find all entity mentions in a fact sheet
     */
    List<EntityMention> findByFactSheetId(Long factSheetId);

    /**
     * Find all nodes mentioning an entity within a fact sheet
     */
    @Query("SELECT em FROM EntityMention em WHERE em.entityName = :entityName AND em.factSheetId = :factSheetId")
    List<EntityMention> findByEntityNameAndFactSheet(
        @Param("entityName") String entityName,
        @Param("factSheetId") Long factSheetId
    );

    /**
     * Find entities shared across multiple nodes within a fact sheet
     */
    @Query("SELECT em.entityName, COUNT(DISTINCT em.node) as docCount " +
           "FROM EntityMention em WHERE em.factSheetId = :factSheetId " +
           "GROUP BY em.entityName HAVING COUNT(DISTINCT em.node) > 1 ORDER BY docCount DESC")
    List<Object[]> findSharedEntitiesByFactSheet(@Param("factSheetId") Long factSheetId, Pageable pageable);

    /**
     * Find pairs of nodes with shared entities within a fact sheet
     */
    @Query("SELECT em1.node.id, em2.node.id, COUNT(em1.entityName) as sharedCount " +
           "FROM EntityMention em1, EntityMention em2 " +
           "WHERE em1.entityName = em2.entityName AND em1.node != em2.node " +
           "AND em1.node.id < em2.node.id AND em1.factSheetId = :factSheetId AND em2.factSheetId = :factSheetId " +
           "GROUP BY em1.node.id, em2.node.id HAVING COUNT(em1.entityName) >= :minShared")
    List<Object[]> findNodePairsWithSharedEntitiesByFactSheet(
        @Param("factSheetId") Long factSheetId,
        @Param("minShared") int minShared
    );

    /**
     * Find top entities within a fact sheet
     */
    @Query("SELECT em.entityName, SUM(em.mentionCount) as totalMentions " +
           "FROM EntityMention em WHERE em.factSheetId = :factSheetId " +
           "GROUP BY em.entityName ORDER BY totalMentions DESC")
    List<Object[]> findTopEntitiesByFactSheet(@Param("factSheetId") Long factSheetId, Pageable pageable);

    /**
     * Count distinct entities in a fact sheet
     */
    @Query("SELECT COUNT(DISTINCT em.entityName) FROM EntityMention em WHERE em.factSheetId = :factSheetId")
    long countDistinctEntitiesByFactSheet(@Param("factSheetId") Long factSheetId);

    /**
     * Delete all entity mentions in a fact sheet
     */
    @Modifying
    @Query("DELETE FROM EntityMention em WHERE em.factSheetId = :factSheetId")
    int deleteByFactSheetId(@Param("factSheetId") Long factSheetId);

    /**
     * Find mention by node, entity name, and fact sheet
     */
    @Query("SELECT em FROM EntityMention em WHERE em.node = :node AND em.entityName = :entityName AND em.factSheetId = :factSheetId")
    Optional<EntityMention> findByNodeAndEntityNameAndFactSheet(
        @Param("node") GraphNode node,
        @Param("entityName") String entityName,
        @Param("factSheetId") Long factSheetId
    );
}
