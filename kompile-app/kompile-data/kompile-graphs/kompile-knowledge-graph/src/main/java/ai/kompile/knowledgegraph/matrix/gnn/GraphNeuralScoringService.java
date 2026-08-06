/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.matrix.gnn;

import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Trains and applies a bounded message-passing link predictor over one fact-sheet graph.
 *
 * <p>Persisted KGE vectors are used when available; deterministic hashed graph features provide
 * coverage for sparse graphs. The model itself is genuinely trained from retained edges and
 * deterministic absent-pair negatives. It uses only on-heap Java arrays, is closed after every
 * invocation, and therefore cannot retain a tensor backend, native workspace, thread, or GPU.
 */
@Service
public class GraphNeuralScoringService {

    public static final String SCORE_KEY = "gnn.score";
    public static final String MODEL_KEY = "gnn.model";
    public static final String MODEL_NAME = "trainable-message-passing-link-v1";
    public static final String RUNTIME_NAME = "cpu-heap";

    private static final int FEATURE_DIMENSION = 64;
    private static final int MAX_METADATA_ENTRIES = 32;
    private static final int MAX_TEXT_LENGTH = 2_000;
    private static final double EPSILON = 1.0e-12;

    private final KnowledgeGraphService knowledgeGraphService;

    public GraphNeuralScoringService(KnowledgeGraphService knowledgeGraphService) {
        this.knowledgeGraphService = knowledgeGraphService;
    }

    /**
     * Score the active edges in one fact-sheet graph and merge the scores into edge metadata.
     *
     * @param factSheetId fact sheet whose graph should be scored
     * @param maxNodes safety bound; non-positive means unbounded
     * @param maxEdges safety bound; non-positive means unbounded
     * @param batchSize maximum metadata updates per store call
     * @param selfWeight initial scale for each node's own features
     * @param neighborWeight initial scale for the neighbor mean
     */
    public ScoringResult scoreFactSheetEdges(
            long factSheetId,
            int maxNodes,
            int maxEdges,
            int batchSize,
            double selfWeight,
            double neighborWeight) {
        return scoreFactSheetEdges(
                factSheetId,
                maxNodes,
                maxEdges,
                batchSize,
                TrainingConfig.defaults(selfWeight, neighborWeight));
    }

    /** Train a fresh bounded model, persist its scores, then deterministically dispose it. */
    public ScoringResult scoreFactSheetEdges(
            long factSheetId,
            int maxNodes,
            int maxEdges,
            int batchSize,
            TrainingConfig trainingConfig) {

        String graphId = GraphToSameDiffDataset.FACTSHEET_GRAPH_PREFIX + factSheetId;
        List<GraphNode> nodes = activeNodes(knowledgeGraphService.getNodesInFactSheet(factSheetId));
        List<GraphEdge> edges = activeEdges(knowledgeGraphService.getEdgesInFactSheet(factSheetId));

        if (nodes.isEmpty()) {
            return skipped(graphId, 0, edges.size(), "graph has no active nodes");
        }
        if (edges.isEmpty()) {
            return skipped(graphId, nodes.size(), 0, "graph has no active edges");
        }
        if (maxNodes > 0 && nodes.size() > maxNodes) {
            return skipped(graphId, nodes.size(), edges.size(),
                    "node safety bound exceeded: " + nodes.size() + " > " + maxNodes);
        }
        if (maxEdges > 0 && edges.size() > maxEdges) {
            return skipped(graphId, nodes.size(), edges.size(),
                    "edge safety bound exceeded: " + edges.size() + " > " + maxEdges);
        }

        Map<String, GraphNode> nodesById = new LinkedHashMap<>();
        for (GraphNode node : nodes) {
            if (node != null && node.getNodeId() != null && !node.getNodeId().isBlank()) {
                nodesById.putIfAbsent(node.getNodeId(), node);
            }
        }
        if (nodesById.isEmpty()) {
            return skipped(graphId, nodes.size(), edges.size(), "active nodes have no identifiers");
        }

        Map<String, double[]> baseFeatures = new LinkedHashMap<>();
        for (Map.Entry<String, GraphNode> entry : nodesById.entrySet()) {
            baseFeatures.put(entry.getKey(), features(entry.getValue()));
        }

        TrainingConfig safeConfig = trainingConfig == null
                ? TrainingConfig.defaults(0.7, 0.3)
                : trainingConfig;
        try (TrainableMessagePassingLinkModel.TrainingAttempt attempt =
                     TrainableMessagePassingLinkModel.train(baseFeatures, edges, safeConfig)) {
            if (!attempt.trained()) {
                return skipped(graphId, nodes.size(), edges.size(), attempt.reason());
            }

            TrainableMessagePassingLinkModel model = attempt.model();
            TrainableMessagePassingLinkModel.Diagnostics diagnostics = attempt.diagnostics();
            String scoredAt = Instant.now().toString();
            List<KnowledgeGraphService.EdgeMetadataUpdate> updates = new ArrayList<>(edges.size());
            for (GraphEdge edge : edges) {
                String edgeId = edge.getEdgeId();
                if (edgeId == null || edgeId.isBlank()
                        || !baseFeatures.containsKey(edge.getSourceNodeId())
                        || !baseFeatures.containsKey(edge.getTargetNodeId())) {
                    continue;
                }

                double score = clampUnit(model.score(edge));
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put(SCORE_KEY, score);
                metadata.put(MODEL_KEY, MODEL_NAME);
                metadata.put("gnn.runtime", RUNTIME_NAME);
                metadata.put("gnn.featureSource", "persisted-embedding-or-graph-content");
                metadata.put("gnn.selfWeightInitial", safeConfig.selfWeight());
                metadata.put("gnn.neighborWeightInitial", safeConfig.neighborWeight());
                metadata.put("gnn.trainingExamples", diagnostics.trainingExamples());
                metadata.put("gnn.validationExamples", diagnostics.validationExamples());
                putFinite(metadata, "gnn.initialLoss", diagnostics.initialLoss());
                putFinite(metadata, "gnn.finalLoss", diagnostics.finalLoss());
                putFinite(metadata, "gnn.validationPositiveMean", diagnostics.validationPositiveMean());
                putFinite(metadata, "gnn.validationNegativeMean", diagnostics.validationNegativeMean());
                metadata.put("gnn.modelFingerprint", diagnostics.fingerprint());
                metadata.put("gnn.scoredAt", scoredAt);
                metadata.put("neural.score", score);
                metadata.put("neural.model", MODEL_NAME);
                updates.add(new KnowledgeGraphService.EdgeMetadataUpdate(edgeId, Map.copyOf(metadata)));
            }

            if (updates.isEmpty()) {
                return skipped(graphId, nodes.size(), edges.size(),
                        "no edges had resolvable endpoints and identifiers");
            }

            int updated = 0;
            int safeBatchSize = Math.max(1, batchSize);
            for (int start = 0; start < updates.size(); start += safeBatchSize) {
                int end = Math.min(start + safeBatchSize, updates.size());
                updated += knowledgeGraphService.updateEdgeMetadataBatch(
                        List.copyOf(updates.subList(start, end)));
            }

            String reason = updated == updates.size()
                    ? "ok"
                    : "metadata persisted for " + updated + " of " + updates.size() + " scored edges";
            return new ScoringResult(
                    graphId,
                    nodes.size(),
                    edges.size(),
                    updated,
                    false,
                    reason,
                    MODEL_NAME,
                    diagnostics.trainingExamples(),
                    diagnostics.validationExamples(),
                    diagnostics.initialLoss(),
                    diagnostics.finalLoss(),
                    diagnostics.validationPositiveMean(),
                    diagnostics.validationNegativeMean(),
                    diagnostics.fingerprint());
        }
    }

    private static List<GraphNode> activeNodes(List<GraphNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        return nodes.stream()
                .filter(node -> node != null && !Boolean.TRUE.equals(node.getStale()))
                .toList();
    }

    private static List<GraphEdge> activeEdges(List<GraphEdge> edges) {
        if (edges == null || edges.isEmpty()) {
            return List.of();
        }
        return edges.stream()
                .filter(edge -> edge != null && !Boolean.TRUE.equals(edge.getStale()))
                .toList();
    }

    private static double[] features(GraphNode node) {
        double[] result = new double[FEATURE_DIMENSION];
        boolean hasEmbedding = foldEmbedding(node, result);

        hashText(result, "type", node.getNodeType() == null ? null : node.getNodeType().name(), 1.0);
        hashText(result, "title", node.getTitle(), 1.0);
        hashText(result, "description", node.getDescription(), 0.6);
        hashText(result, "content", node.getContentPreview(), 0.4);
        hashMetadata(result, node.getMetadata());

        if (!hasEmbedding && magnitudeSquared(result) <= EPSILON) {
            hashFeature(result, "node", 1.0);
        }
        normalizeInPlace(result);
        return result;
    }

    private static boolean foldEmbedding(GraphNode node, double[] target) {
        if (node.getKgEmbedding() == null || node.getKgEmbedding().isEmpty()) {
            return false;
        }
        try {
            double[] embedding = node.getKgEmbedding().toDoubleVector();
            boolean added = false;
            for (int i = 0; i < embedding.length; i++) {
                double value = embedding[i];
                if (!Double.isFinite(value)) {
                    continue;
                }
                int bucket = i % target.length;
                int band = i / target.length;
                target[bucket] += ((band & 1) == 0 ? value : -value);
                added |= Math.abs(value) > EPSILON;
            }
            return added;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static void hashMetadata(double[] target, Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        metadata.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .limit(MAX_METADATA_ENTRIES)
                .forEach(entry -> {
                    hashFeature(target, "meta-key:" + entry.getKey().toLowerCase(Locale.ROOT), 0.35);
                    hashText(target, "meta-value:" + entry.getKey(), String.valueOf(entry.getValue()), 0.2);
                });
    }

    private static void hashText(
            double[] target, String namespace, String text, double weight) {
        if (text == null || text.isBlank()) {
            return;
        }
        String bounded = text.length() <= MAX_TEXT_LENGTH
                ? text
                : text.substring(0, MAX_TEXT_LENGTH);
        String[] tokens = bounded.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_]+");
        int limit = Math.min(tokens.length, 128);
        for (int i = 0; i < limit; i++) {
            if (!tokens[i].isBlank()) {
                hashFeature(target, namespace + ":" + tokens[i], weight);
            }
        }
    }

    private static void hashFeature(double[] target, String feature, double weight) {
        int hash = feature.hashCode();
        int index = Math.floorMod(hash, target.length);
        double sign = ((Integer.rotateLeft(hash, 13) & 1) == 0) ? 1.0 : -1.0;
        target[index] += sign * weight;
    }

    private static double nonNegativeFinite(double value, double fallback) {
        return Double.isFinite(value) && value >= 0.0 ? value : fallback;
    }

    private static void putFinite(Map<String, Object> metadata, String key, double value) {
        if (Double.isFinite(value)) {
            metadata.put(key, value);
        }
    }

    private static void normalizeInPlace(double[] vector) {
        double normSquared = magnitudeSquared(vector);
        if (normSquared <= EPSILON) {
            return;
        }
        double inverseNorm = 1.0 / Math.sqrt(normSquared);
        for (int i = 0; i < vector.length; i++) {
            vector[i] *= inverseNorm;
        }
    }

    private static double magnitudeSquared(double[] vector) {
        double result = 0.0;
        for (double value : vector) {
            result += value * value;
        }
        return result;
    }

    private static double clampUnit(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static ScoringResult skipped(
            String graphId, int nodeCount, int edgesSeen, String reason) {
        return new ScoringResult(graphId, nodeCount, edgesSeen, 0, true, reason);
    }

    /** Bounded, deterministic training controls for the per-crawl model. */
    public record TrainingConfig(
            double selfWeight,
            double neighborWeight,
            int epochs,
            double learningRate,
            int negativeSamplesPerPositive,
            int maxPositiveEdges,
            long seed,
            double l2) {

        public TrainingConfig {
            selfWeight = nonNegativeFinite(selfWeight, 0.7);
            neighborWeight = nonNegativeFinite(neighborWeight, 0.3);
            if (selfWeight + neighborWeight <= EPSILON) {
                selfWeight = 1.0;
            }
            epochs = Math.max(1, epochs);
            learningRate = Double.isFinite(learningRate) && learningRate > 0.0
                    ? learningRate : 0.03;
            negativeSamplesPerPositive = Math.max(1, negativeSamplesPerPositive);
            maxPositiveEdges = Math.max(1, maxPositiveEdges);
            l2 = Double.isFinite(l2) && l2 >= 0.0 ? l2 : 1.0e-4;
        }

        public static TrainingConfig defaults(double selfWeight, double neighborWeight) {
            return new TrainingConfig(
                    selfWeight, neighborWeight, 30, 0.03, 1, 20_000, 1_729L, 1.0e-4);
        }
    }

    public record ScoringResult(
            String graphId,
            int nodeCount,
            int edgesSeen,
            int edgesScored,
            boolean skipped,
            String reason,
            String modelName,
            int trainingExamples,
            int validationExamples,
            double initialLoss,
            double finalLoss,
            double validationPositiveMean,
            double validationNegativeMean,
            String modelFingerprint) {

        public ScoringResult(
                String graphId,
                int nodeCount,
                int edgesSeen,
                int edgesScored,
                boolean skipped,
                String reason) {
            this(graphId, nodeCount, edgesSeen, edgesScored, skipped, reason,
                    null, 0, 0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, null);
        }
    }
}
