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
package ai.kompile.knowledgegraph.impl;

import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.SourceWeight;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import ai.kompile.knowledgegraph.repository.SourceWeightRepository;
import ai.kompile.knowledgegraph.service.SourceWeightingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Implementation of the SourceWeightingService interface.
 */
@Service
@Slf4j
public class SourceWeightingServiceImpl implements SourceWeightingService {

    private SourceWeightRepository weightRepository;
    private GraphNodeRepository nodeRepository;

    @Value("${kompile.source-weighting.default-weight:1.0}")
    private double defaultWeight;

    @Value("${kompile.source-weighting.max-weight:3.0}")
    private double maxWeight;

    @Value("${kompile.source-weighting.topic-relevance-factor:0.3}")
    private double topicRelevanceFactor;

    @Autowired
    public SourceWeightingServiceImpl(SourceWeightRepository weightRepository,
                                       GraphNodeRepository nodeRepository) {
        this.weightRepository = weightRepository;
        this.nodeRepository = nodeRepository;
    }

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected SourceWeightingServiceImpl() {}


    // ═══════════════════════════════════════════════════════════════════════════
    // WEIGHT MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public SourceWeight setSourceWeight(String sourceNodeId, Double baseWeight, String topic, String userId) {
        GraphNode sourceNode = nodeRepository.findByNodeId(sourceNodeId)
            .orElseThrow(() -> new IllegalArgumentException("Source node not found: " + sourceNodeId));

        // Look for existing weight
        Optional<SourceWeight> existing = weightRepository.findBySourceNodeAndTopicAndUserId(
            sourceNode, topic, userId);

        if (existing.isPresent()) {
            SourceWeight weight = existing.get();
            weight.setBaseWeight(baseWeight);
            weight.computeEffectiveWeight();
            return weightRepository.save(weight);
        }

        // Create new weight
        SourceWeight weight = SourceWeight.builder()
            .sourceNode(sourceNode)
            .baseWeight(baseWeight)
            .topic(topic)
            .userId(userId)
            .enabled(true)
            .build();

        return weightRepository.save(weight);
    }

    @Override
    @Transactional(readOnly = true)
    public SourceWeight getSourceWeight(String sourceNodeId, String topic) {
        List<SourceWeight> weights = weightRepository.findWeightsForSourceAndTopic(sourceNodeId, topic);

        if (weights.isEmpty()) {
            // Return a default weight
            return SourceWeight.builder()
                .baseWeight(defaultWeight)
                .effectiveWeight(defaultWeight)
                .build();
        }

        // Return the most specific weight (topic-specific if available, else global)
        return weights.get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SourceWeight> getAllWeightsForSource(String sourceNodeId) {
        return weightRepository.findBySourceNodeId(sourceNodeId);
    }

    @Override
    @Transactional
    public void removeWeight(String sourceNodeId, String topic, String userId) {
        GraphNode sourceNode = nodeRepository.findByNodeId(sourceNodeId)
            .orElseThrow(() -> new IllegalArgumentException("Source node not found: " + sourceNodeId));

        weightRepository.findBySourceNodeAndTopicAndUserId(sourceNode, topic, userId)
            .ifPresent(weightRepository::delete);
    }

    @Override
    @Transactional
    public SourceWeight setWeightEnabled(String sourceNodeId, String topic, String userId, boolean enabled) {
        GraphNode sourceNode = nodeRepository.findByNodeId(sourceNodeId)
            .orElseThrow(() -> new IllegalArgumentException("Source node not found: " + sourceNodeId));

        SourceWeight weight = weightRepository.findBySourceNodeAndTopicAndUserId(sourceNode, topic, userId)
            .orElseThrow(() -> new IllegalArgumentException("Weight not found"));

        weight.setEnabled(enabled);
        return weightRepository.save(weight);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // QUERY-TIME WEIGHTING
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Map<String, Double> computeQueryWeights(String query, List<String> sourceNodeIds) {
        Map<String, Double> weights = new HashMap<>();

        for (String sourceId : sourceNodeIds) {
            double weight = defaultWeight;

            // Get user-defined weights
            List<SourceWeight> sourceWeights = weightRepository.findEnabledWeightsForSource(sourceId);
            if (!sourceWeights.isEmpty()) {
                // Use the global weight (topic = null) if available
                weight = sourceWeights.stream()
                    .filter(sw -> sw.getTopic() == null)
                    .findFirst()
                    .map(SourceWeight::getEffectiveWeight)
                    .orElse(defaultWeight);
            }

            // TODO: Compute semantic similarity between query and source description
            // This would require access to the embedding model
            // For now, we use the configured weight directly

            weights.put(sourceId, Math.min(weight, maxWeight));
        }

        return weights;
    }

    @Override
    public double getDefaultWeight() {
        return defaultWeight;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // WEIGHT COMPUTATION HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Double computeTopicRelevance(String sourceNodeId, String topic) {
        // Get source node
        GraphNode sourceNode = nodeRepository.findByNodeId(sourceNodeId).orElse(null);
        if (sourceNode == null || topic == null) return 0.5;

        // Simple keyword matching for now
        // In production, this would use embeddings
        String sourceText = (sourceNode.getTitle() + " " +
            (sourceNode.getDescription() != null ? sourceNode.getDescription() : "")).toLowerCase();
        String topicLower = topic.toLowerCase();

        if (sourceText.contains(topicLower)) {
            return 0.8;
        }

        return 0.5;
    }

    @Override
    @Transactional
    public void updateQualityScore(String sourceNodeId, boolean wasHelpful) {
        List<SourceWeight> weights = weightRepository.findBySourceNodeId(sourceNodeId);

        for (SourceWeight weight : weights) {
            weight.updateQualityFromFeedback(wasHelpful);
            weightRepository.save(weight);
        }
    }

    @Override
    @Transactional
    public void recomputeAllWeights() {
        List<SourceWeight> allWeights = weightRepository.findAll();
        for (SourceWeight weight : allWeights) {
            weight.computeEffectiveWeight();
            weightRepository.save(weight);
        }
        log.info("Recomputed {} source weights", allWeights.size());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TOPIC MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public List<String> getTopics() {
        return weightRepository.findDistinctTopics();
    }

    @Override
    @Transactional
    public void assignTopic(String sourceNodeId, String topic) {
        // Create or update a weight entry with the topic
        GraphNode sourceNode = nodeRepository.findByNodeId(sourceNodeId)
            .orElseThrow(() -> new IllegalArgumentException("Source node not found: " + sourceNodeId));

        Optional<SourceWeight> existing = weightRepository.findBySourceNodeAndTopicAndUserId(
            sourceNode, topic, null);

        if (existing.isEmpty()) {
            SourceWeight weight = SourceWeight.builder()
                .sourceNode(sourceNode)
                .baseWeight(defaultWeight)
                .topic(topic)
                .topicRelevanceScore(computeTopicRelevance(sourceNodeId, topic))
                .enabled(true)
                .build();
            weightRepository.save(weight);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> getSourcesForTopic(String topic) {
        return weightRepository.findByTopic(topic).stream()
            .map(sw -> sw.getSourceNode().getNodeId())
            .collect(Collectors.toList());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PREVIEW & TESTING
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> previewWeightedSearch(String query, int maxResults) {
        // This would normally integrate with the vector store
        // For now, return a placeholder showing how weights would be applied

        List<GraphNode> sources = nodeRepository.findAllSources();
        List<String> sourceIds = sources.stream().map(GraphNode::getNodeId).collect(Collectors.toList());

        Map<String, Double> weights = computeQueryWeights(query, sourceIds);

        List<Map<String, Object>> sourceWeights = sources.stream()
            .map(source -> {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("sourceId", source.getNodeId());
                info.put("sourceName", source.getTitle());
                info.put("sourceType", source.getSourceType());
                info.put("weight", weights.getOrDefault(source.getNodeId(), defaultWeight));
                return info;
            })
            .sorted((a, b) -> Double.compare(
                (Double) b.get("weight"),
                (Double) a.get("weight")))
            .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("maxResults", maxResults);
        result.put("sourceWeights", sourceWeights);
        result.put("note", "Actual search results would have their scores multiplied by these weights");

        return result;
    }
}
