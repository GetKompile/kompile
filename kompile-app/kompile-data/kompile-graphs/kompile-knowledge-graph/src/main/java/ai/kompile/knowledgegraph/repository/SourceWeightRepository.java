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
import ai.kompile.knowledgegraph.domain.SourceWeight;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for SourceWeight entities.
 */
@Repository
public interface SourceWeightRepository extends JpaRepository<SourceWeight, Long> {

    /**
     * Find all weights for a source node
     */
    List<SourceWeight> findBySourceNode(GraphNode sourceNode);

    /**
     * Find weight by source, topic, and user
     */
    Optional<SourceWeight> findBySourceNodeAndTopicAndUserId(
        GraphNode sourceNode,
        String topic,
        String userId
    );

    /**
     * Find weights for a source and topic (includes global weights)
     * Orders by specificity: exact topic match first, then global
     */
    @Query("SELECT sw FROM SourceWeight sw WHERE sw.sourceNode.nodeId = :sourceId " +
           "AND sw.enabled = true " +
           "AND (sw.topic = :topic OR sw.topic IS NULL) " +
           "ORDER BY CASE WHEN sw.topic = :topic THEN 0 ELSE 1 END, sw.effectiveWeight DESC")
    List<SourceWeight> findWeightsForSourceAndTopic(
        @Param("sourceId") String sourceId,
        @Param("topic") String topic
    );

    /**
     * Find all weights for a topic
     */
    @Query("SELECT sw FROM SourceWeight sw WHERE sw.topic = :topic AND sw.enabled = true ORDER BY sw.effectiveWeight DESC")
    List<SourceWeight> findByTopic(@Param("topic") String topic);

    /**
     * Find all weights by user
     */
    @Query("SELECT sw FROM SourceWeight sw WHERE sw.userId = :userId")
    List<SourceWeight> findByUserId(@Param("userId") String userId);

    /**
     * Find all global weights (no specific topic)
     */
    @Query("SELECT sw FROM SourceWeight sw WHERE sw.topic IS NULL AND sw.enabled = true")
    List<SourceWeight> findGlobalWeights();

    /**
     * Find all weights for a source node ID
     */
    @Query("SELECT sw FROM SourceWeight sw WHERE sw.sourceNode.nodeId = :sourceId")
    List<SourceWeight> findBySourceNodeId(@Param("sourceId") String sourceId);

    /**
     * Get sources ranked by average effective weight
     */
    @Query("SELECT sw.sourceNode, AVG(sw.effectiveWeight) as avgWeight " +
           "FROM SourceWeight sw WHERE sw.enabled = true " +
           "GROUP BY sw.sourceNode ORDER BY avgWeight DESC")
    List<Object[]> findSourcesRankedByAverageWeight(Pageable pageable);

    /**
     * Find distinct topics
     */
    @Query("SELECT DISTINCT sw.topic FROM SourceWeight sw WHERE sw.topic IS NOT NULL ORDER BY sw.topic")
    List<String> findDistinctTopics();

    /**
     * Delete weights by source node
     */
    void deleteBySourceNode(GraphNode sourceNode);

    /**
     * Find enabled weights for a source
     */
    @Query("SELECT sw FROM SourceWeight sw WHERE sw.sourceNode.nodeId = :sourceId AND sw.enabled = true")
    List<SourceWeight> findEnabledWeightsForSource(@Param("sourceId") String sourceId);
}
