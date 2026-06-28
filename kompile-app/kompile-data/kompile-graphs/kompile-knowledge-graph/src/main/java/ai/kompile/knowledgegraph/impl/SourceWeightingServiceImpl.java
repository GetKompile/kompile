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
import ai.kompile.knowledgegraph.domain.SourceWeightView;
import ai.kompile.knowledgegraph.repository.SourceWeightRepository;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.knowledgegraph.service.SourceWeightingService;
import ai.kompile.core.embeddings.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.nd4j.linalg.api.ndarray.INDArray;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Implementation of the SourceWeightingService interface.
 */
@Service
@Slf4j
public class SourceWeightingServiceImpl implements SourceWeightingService {

    private SourceWeightRepository weightRepository;
    private KnowledgeGraphService knowledgeGraphService;

    @Value("${kompile.source-weighting.default-weight:1.0}")
    private double defaultWeight;

    @Value("${kompile.source-weighting.max-weight:3.0}")
    private double maxWeight;

    @Value("${kompile.source-weighting.topic-relevance-factor:0.3}")
    private double topicRelevanceFactor;

    /**
     * Optional embedding model used to score query↔source semantic relevance in
     * {@link #previewWeightedSearch}. Field-injected and {@code required = false} so the
     * service still starts when embeddings are disabled — relevance then falls back to
     * weight-only ranking.
     */
    @Autowired(required = false)
    private EmbeddingModel embeddingModel;

    @Autowired
    public SourceWeightingServiceImpl(SourceWeightRepository weightRepository,
                                       KnowledgeGraphService knowledgeGraphService) {
        this.weightRepository = weightRepository;
        this.knowledgeGraphService = knowledgeGraphService;
    }

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected SourceWeightingServiceImpl() {}


    // ═══════════════════════════════════════════════════════════════════════════
    // WEIGHT MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public SourceWeight setSourceWeight(String sourceNodeId, Double baseWeight, String topic, String userId) {
        GraphNode sourceNode = knowledgeGraphService.getNode(sourceNodeId)
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
    @Transactional(readOnly = true)
    public List<SourceWeightView> listAllSourcesWithWeights() {
        List<GraphNode> sources = knowledgeGraphService.getAllSources();

        // Build a map: sourceNodeId → the best configured global weight (topic=null)
        Map<String, SourceWeight> configuredGlobalWeights = new HashMap<>();
        for (GraphNode source : sources) {
            List<SourceWeight> weights = weightRepository.findEnabledWeightsForSource(source.getNodeId());
            weights.stream()
                    .filter(sw -> sw.getTopic() == null)
                    .findFirst()
                    .ifPresent(sw -> configuredGlobalWeights.put(source.getNodeId(), sw));
        }

        return sources.stream()
                .map(source -> {
                    SourceWeight configured = configuredGlobalWeights.get(source.getNodeId());
                    return configured != null
                            ? SourceWeightView.from(configured)
                            : SourceWeightView.defaultFor(source, defaultWeight);
                })
                .sorted((a, b) -> Double.compare(
                        b.effectiveWeight() != null ? b.effectiveWeight() : defaultWeight,
                        a.effectiveWeight() != null ? a.effectiveWeight() : defaultWeight))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void removeWeight(String sourceNodeId, String topic, String userId) {
        GraphNode sourceNode = knowledgeGraphService.getNode(sourceNodeId)
            .orElseThrow(() -> new IllegalArgumentException("Source node not found: " + sourceNodeId));

        weightRepository.findBySourceNodeAndTopicAndUserId(sourceNode, topic, userId)
            .ifPresent(weightRepository::delete);
    }

    @Override
    @Transactional
    public SourceWeight setWeightEnabled(String sourceNodeId, String topic, String userId, boolean enabled) {
        GraphNode sourceNode = knowledgeGraphService.getNode(sourceNodeId)
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
        GraphNode sourceNode = knowledgeGraphService.getNode(sourceNodeId).orElse(null);
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
        GraphNode sourceNode = knowledgeGraphService.getNode(sourceNodeId)
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
        List<GraphNode> sources = knowledgeGraphService.getAllSources();
        List<String> sourceIds = sources.stream().map(GraphNode::getNodeId).collect(Collectors.toList());

        // Configured (or default) weight per source. Semantics unchanged — weight only.
        Map<String, Double> weights = computeQueryWeights(query, sourceIds);

        // Semantic relevance of the query against each source's text. Empty (so we fall back
        // to weight-only ranking) when the embedding model is unavailable.
        Map<String, Double> relevanceById = computeQueryRelevance(query, sources);
        boolean relevanceApplied = !relevanceById.isEmpty();

        List<Map<String, Object>> sourceWeights = sources.stream()
            .map(source -> {
                double weight = weights.getOrDefault(source.getNodeId(), defaultWeight);
                Double relevance = relevanceById.get(source.getNodeId());
                // Final ranking score: the configured weight scaled by query relevance when
                // available; otherwise the weight alone.
                double score = relevance != null ? weight * relevance : weight;

                Map<String, Object> info = new LinkedHashMap<>();
                info.put("sourceId", source.getNodeId());
                info.put("sourceName", source.getTitle());
                info.put("sourceType", source.getSourceType());
                info.put("weight", weight);
                info.put("relevance", relevance); // null when embeddings unavailable
                info.put("score", score);
                return info;
            })
            .sorted((a, b) -> Double.compare(
                (Double) b.get("score"),
                (Double) a.get("score")))
            .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("maxResults", maxResults);
        result.put("sourceWeights", sourceWeights);
        result.put("note", relevanceApplied
            ? "Ranked by semantic relevance to your query × the configured source weight. "
              + "Real search results would have their scores multiplied by these weights."
            : "Embedding model unavailable — ranked by configured weight only "
              + "(query relevance not applied).");

        return result;
    }

    /**
     * Cosine similarity of the query against each source's text (title + description), computed
     * with ND4J in one vectorized pass: every source is embedded into a single [N, dim] matrix,
     * the rows are L2-normalized, and a single {@code Sᵤ · qᵤ} matmul against the unit query
     * vector yields all N similarities at once. (The embedding model bounds its own forward-pass
     * batching internally, so there is no need to mini-batch here.) Returns an empty map —
     * signalling callers to fall back to weight-only ranking — when embeddings are unavailable
     * or the text cannot be embedded.
     */
    private Map<String, Double> computeQueryRelevance(String query, List<GraphNode> sources) {
        if (embeddingModel == null || query == null || query.isBlank() || sources.isEmpty()) {
            return Map.of();
        }
        INDArray sourceEmb = null, queryEmb = null, qUnitCol = null, rowNorms = null, sims = null;
        try {
            if (!embeddingModel.isInitialized()) {
                return Map.of();
            }
            List<String> texts = sources.stream().map(this::sourceText).collect(Collectors.toList());

            sourceEmb = embeddingModel.embed(texts);   // [N, dim] — one batched forward pass
            queryEmb = embeddingModel.embed(query);    // [dim] (or [1, dim])
            if (sourceEmb == null || sourceEmb.isEmpty() || queryEmb == null || queryEmb.isEmpty()) {
                return Map.of();
            }

            // cosine = (S . q_unit) / ||S||_row : one matmul for all dot products, one norm2
            // reduction for all row norms, one elementwise divide. q_unit is a [dim, 1] column.
            qUnitCol = queryEmb.div(queryEmb.norm2Number().doubleValue() + 1e-12)
                               .reshape(sourceEmb.size(1), 1);                    // [dim, 1]
            sims = sourceEmb.mmul(qUnitCol);                                      // dot products, [N, 1]
            rowNorms = sourceEmb.norm2(1).reshape(sourceEmb.size(0), 1).add(1e-12); // [N, 1]
            sims.divi(rowNorms);                                                  // cosine per source, [N, 1]

            double[] simArr = sims.toDoubleVector();
            Map<String, Double> relevance = new HashMap<>(simArr.length);
            for (int i = 0; i < simArr.length; i++) {
                // Clamp to [0,1]: a negative cosine means "unrelated", treat as 0.
                relevance.put(sources.get(i).getNodeId(), Math.max(0.0, Math.min(1.0, simArr[i])));
            }
            return relevance;
        } catch (Exception e) {
            log.warn("Semantic relevance unavailable for weighted-search preview: {}", e.getMessage());
            return Map.of();
        } finally {
            closeQuietly(sourceEmb, queryEmb, qUnitCol, rowNorms, sims);
        }
    }

    /** Text used for semantic matching of a source: its title plus description. */
    private String sourceText(GraphNode node) {
        String title = node.getTitle() != null ? node.getTitle() : "";
        String desc = node.getDescription() != null ? node.getDescription() : "";
        String text = (title + " " + desc).trim();
        if (!text.isEmpty()) {
            return text;
        }
        return node.getNodeId() != null ? node.getNodeId() : "";
    }

    /** Closes ND4J arrays best-effort, skipping nulls and already-closed buffers. */
    private static void closeQuietly(INDArray... arrays) {
        for (INDArray a : arrays) {
            if (a != null && !a.wasClosed()) {
                try { a.close(); } catch (Exception ignore) { /* best-effort native cleanup */ }
            }
        }
    }
}
