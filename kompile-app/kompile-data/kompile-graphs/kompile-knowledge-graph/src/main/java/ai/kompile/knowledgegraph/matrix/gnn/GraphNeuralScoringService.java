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
 * Computes a bounded, one-hop neural-style edge overlay from graph-resident information.
 *
 * <p>The service deliberately depends on {@link KnowledgeGraphService}, not a graph-store or
 * tensor backend. Persisted KGE vectors are used when available; deterministic hashed features
 * from node type, text, and metadata provide coverage for sparse graphs. A distribution remains
 * responsible for supplying any backend needed to create the persisted vectors.
 */
@Service
public class GraphNeuralScoringService {

    public static final String SCORE_KEY = "gnn.score";
    public static final String MODEL_KEY = "gnn.model";
    public static final String MODEL_NAME = "mean-aggregate-v1";

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
     * @param selfWeight contribution of each node's own features
     * @param neighborWeight contribution of its weighted neighbor mean
     */
    public ScoringResult scoreFactSheetEdges(
            long factSheetId,
            int maxNodes,
            int maxEdges,
            int batchSize,
            double selfWeight,
            double neighborWeight) {

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

        Map<String, double[]> neighborSums = new LinkedHashMap<>();
        Map<String, Double> neighborMass = new LinkedHashMap<>();
        for (GraphEdge edge : edges) {
            String sourceId = edge.getSourceNodeId();
            String targetId = edge.getTargetNodeId();
            double[] source = baseFeatures.get(sourceId);
            double[] target = baseFeatures.get(targetId);
            if (source == null || target == null) {
                continue;
            }

            double weight = contextWeight(edge.getWeight());
            accumulateNeighbor(neighborSums, neighborMass, sourceId, target,
                    relationToken(edge, "out"), weight);
            accumulateNeighbor(neighborSums, neighborMass, targetId, source,
                    relationToken(edge, "in"), weight);
        }

        double safeSelfWeight = nonNegativeFinite(selfWeight, 1.0);
        double safeNeighborWeight = nonNegativeFinite(neighborWeight, 1.0);
        if (safeSelfWeight + safeNeighborWeight <= EPSILON) {
            safeSelfWeight = 1.0;
        }

        Map<String, double[]> contextualFeatures = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> entry : baseFeatures.entrySet()) {
            double[] contextual = scaledCopy(entry.getValue(), safeSelfWeight);
            double mass = neighborMass.getOrDefault(entry.getKey(), 0.0);
            double[] neighbors = neighborSums.get(entry.getKey());
            if (neighbors != null && mass > EPSILON) {
                addScaled(contextual, neighbors, safeNeighborWeight / mass);
            }
            normalizeInPlace(contextual);
            contextualFeatures.put(entry.getKey(), contextual);
        }

        String scoredAt = Instant.now().toString();
        List<KnowledgeGraphService.EdgeMetadataUpdate> updates = new ArrayList<>(edges.size());
        for (GraphEdge edge : edges) {
            String edgeId = edge.getEdgeId();
            double[] source = contextualFeatures.get(edge.getSourceNodeId());
            double[] target = contextualFeatures.get(edge.getTargetNodeId());
            if (edgeId == null || edgeId.isBlank() || source == null || target == null) {
                continue;
            }

            double score = clampUnit((cosine(source, target) + 1.0) / 2.0);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put(SCORE_KEY, score);
            metadata.put(MODEL_KEY, MODEL_NAME);
            metadata.put("gnn.featureSource", "persisted-embedding-or-graph-content");
            metadata.put("gnn.selfWeight", safeSelfWeight);
            metadata.put("gnn.neighborWeight", safeNeighborWeight);
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
        return new ScoringResult(graphId, nodes.size(), edges.size(), updated, false, reason);
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

    private static void accumulateNeighbor(
            Map<String, double[]> sums,
            Map<String, Double> mass,
            String nodeId,
            double[] neighbor,
            String relationToken,
            double weight) {
        double[] sum = sums.computeIfAbsent(nodeId, ignored -> new double[FEATURE_DIMENSION]);
        addScaled(sum, neighbor, weight);
        hashFeature(sum, relationToken, 0.25 * weight);
        mass.merge(nodeId, weight, Double::sum);
    }

    private static String relationToken(GraphEdge edge, String direction) {
        String relation = edge.getRelationType();
        if (relation == null || relation.isBlank()) {
            relation = edge.getEdgeType() == null ? "unknown" : edge.getEdgeType().name();
        }
        return "relation:" + direction + ":" + relation.toLowerCase(Locale.ROOT);
    }

    private static double contextWeight(Double weight) {
        if (weight == null || !Double.isFinite(weight)) {
            return 1.0;
        }
        return Math.max(0.05, Math.min(1.0, Math.abs(weight)));
    }

    private static double nonNegativeFinite(double value, double fallback) {
        return Double.isFinite(value) && value >= 0.0 ? value : fallback;
    }

    private static double[] scaledCopy(double[] source, double scale) {
        double[] result = new double[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = source[i] * scale;
        }
        return result;
    }

    private static void addScaled(double[] target, double[] source, double scale) {
        for (int i = 0; i < target.length; i++) {
            target[i] += source[i] * scale;
        }
    }

    private static double cosine(double[] left, double[] right) {
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm <= EPSILON || rightNorm <= EPSILON) {
            return 0.0;
        }
        return dot / Math.sqrt(leftNorm * rightNorm);
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

    public record ScoringResult(
            String graphId,
            int nodeCount,
            int edgesSeen,
            int edgesScored,
            boolean skipped,
            String reason) {
    }
}
