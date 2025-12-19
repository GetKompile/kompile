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

import ai.kompile.knowledgegraph.domain.SourceWeight;

import java.util.List;
import java.util.Map;

/**
 * Service interface for source weighting operations.
 * Source weights affect how documents from different sources are ranked in search results.
 */
public interface SourceWeightingService {

    // ═══════════════════════════════════════════════════════════════════════════
    // WEIGHT MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Set or update a source weight
     *
     * @param sourceNodeId Source node UUID
     * @param baseWeight Base weight value (0.0 to 2.0)
     * @param topic Optional topic/domain for this weight
     * @param userId Optional user ID (null for global weight)
     * @return Created or updated weight
     */
    SourceWeight setSourceWeight(String sourceNodeId, Double baseWeight, String topic, String userId);

    /**
     * Get the effective weight for a source and topic
     *
     * @param sourceNodeId Source node UUID
     * @param topic Topic/domain (null for global)
     * @return The most specific applicable weight, or default
     */
    SourceWeight getSourceWeight(String sourceNodeId, String topic);

    /**
     * Get all weights configured for a source
     */
    List<SourceWeight> getAllWeightsForSource(String sourceNodeId);

    /**
     * Remove a weight configuration
     */
    void removeWeight(String sourceNodeId, String topic, String userId);

    /**
     * Enable or disable a weight
     */
    SourceWeight setWeightEnabled(String sourceNodeId, String topic, String userId, boolean enabled);

    // ═══════════════════════════════════════════════════════════════════════════
    // QUERY-TIME WEIGHTING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Compute effective weights for sources given a query
     * Combines user-defined weights, topic relevance, and semantic similarity
     *
     * @param query User query string
     * @param sourceNodeIds List of source node UUIDs to compute weights for
     * @return Map of source node ID to effective weight
     */
    Map<String, Double> computeQueryWeights(String query, List<String> sourceNodeIds);

    /**
     * Get the default weight for a source (when no specific weight is configured)
     */
    double getDefaultWeight();

    // ═══════════════════════════════════════════════════════════════════════════
    // WEIGHT COMPUTATION HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Compute topic relevance score for a source
     *
     * @param sourceNodeId Source node UUID
     * @param topic Topic to compute relevance for
     * @return Relevance score (0.0 to 1.0)
     */
    Double computeTopicRelevance(String sourceNodeId, String topic);

    /**
     * Update quality score based on user feedback
     *
     * @param sourceNodeId Source node UUID
     * @param wasHelpful Whether the retrieval was helpful
     */
    void updateQualityScore(String sourceNodeId, boolean wasHelpful);

    /**
     * Recompute all effective weights
     */
    void recomputeAllWeights();

    // ═══════════════════════════════════════════════════════════════════════════
    // TOPIC MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get all configured topics
     */
    List<String> getTopics();

    /**
     * Assign a topic to a source
     */
    void assignTopic(String sourceNodeId, String topic);

    /**
     * Get sources for a topic
     */
    List<String> getSourcesForTopic(String topic);

    // ═══════════════════════════════════════════════════════════════════════════
    // PREVIEW & TESTING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Preview how weights would affect search results
     *
     * @param query Query string
     * @param maxResults Maximum results
     * @return Preview results with original and weighted scores
     */
    Map<String, Object> previewWeightedSearch(String query, int maxResults);
}
