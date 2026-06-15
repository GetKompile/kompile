/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.attribution.algorithm;

import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Backward influence propagation from a target event through the knowledge graph.
 *
 * <p>Adapted from fault-score propagation (KGroot pattern): assign an initial fault
 * score of 1.0 to the target event, then propagate inversely along incoming edges,
 * attenuating by edge weight at each hop. Nodes accumulating high incoming fault
 * scores are ranked as potential root causes.</p>
 *
 * <p>This is analogous to a reverse PageRank focused on a single seed node, and
 * provides a complementary signal to the path-based {@link CausalTraversal}.</p>
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>Initialize score(target) = 1.0, all others = 0.0</li>
 *   <li>For each iteration (up to maxIterations):
 *     <ul>
 *       <li>For each node with score &gt; epsilon, propagate fraction of score
 *           backward along each incoming edge, weighted by edge weight * damping</li>
 *       <li>Accumulate propagated scores at ancestor nodes</li>
 *     </ul>
 *   </li>
 *   <li>Normalize scores and return top-K influential nodes</li>
 * </ol>
 */
public class InfluencePropagation {

    private static final Logger log = LoggerFactory.getLogger(InfluencePropagation.class);

    private InfluencePropagation() {}

    /**
     * Compute influence scores for all nodes reachable backward from a target.
     *
     * @param graphService   the knowledge graph
     * @param targetNodeId   the event to explain
     * @param maxIterations  propagation iterations (higher = deeper reach)
     * @param damping        damping factor per hop (0.0-1.0; typical: 0.85)
     * @param epsilon        minimum score to continue propagating from a node
     * @return map of nodeId → influence score, sorted by score descending
     */
    public static Map<String, Double> computeInfluenceScores(KnowledgeGraphService graphService,
                                                              String targetNodeId,
                                                              int maxIterations,
                                                              double damping,
                                                              double epsilon) {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put(targetNodeId, 1.0);

        // Track which nodes we've discovered edges for (cache)
        Map<String, List<IncomingEdge>> edgeCache = new HashMap<>();

        for (int iter = 0; iter < maxIterations; iter++) {
            Map<String, Double> newScores = new LinkedHashMap<>(scores);
            boolean changed = false;

            // Snapshot current nodes to propagate from
            List<Map.Entry<String, Double>> active = scores.entrySet().stream()
                    .filter(e -> e.getValue() > epsilon)
                    .toList();

            for (Map.Entry<String, Double> entry : active) {
                String nodeId = entry.getKey();
                double nodeScore = entry.getValue();

                List<IncomingEdge> incoming = edgeCache.computeIfAbsent(nodeId,
                        id -> findIncomingEdges(graphService, id));

                if (incoming.isEmpty()) continue;

                // Distribute score backward across incoming edges
                double sharePerEdge = nodeScore * damping / incoming.size();

                for (IncomingEdge ie : incoming) {
                    double propagated = sharePerEdge * ie.weight;
                    double oldScore = newScores.getOrDefault(ie.sourceNodeId, 0.0);
                    double updated = oldScore + propagated;
                    newScores.put(ie.sourceNodeId, updated);
                    if (Math.abs(updated - oldScore) > epsilon) {
                        changed = true;
                    }
                }
            }

            scores = newScores;
            if (!changed) {
                log.debug("Influence propagation converged at iteration {}", iter);
                break;
            }
        }

        // Remove the target itself and sort by score descending
        scores.remove(targetNodeId);
        return sortByValueDescending(scores);
    }

    /**
     * Run counterfactual analysis: remove a node and recompute influence scores
     * to see how the attribution changes.
     *
     * @return influence scores without the removed node
     */
    public static Map<String, Double> counterfactualScores(KnowledgeGraphService graphService,
                                                            String targetNodeId,
                                                            String removedNodeId,
                                                            int maxIterations,
                                                            double damping,
                                                            double epsilon) {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put(targetNodeId, 1.0);
        Map<String, List<IncomingEdge>> edgeCache = new HashMap<>();

        for (int iter = 0; iter < maxIterations; iter++) {
            Map<String, Double> newScores = new LinkedHashMap<>(scores);
            boolean changed = false;

            List<Map.Entry<String, Double>> active = scores.entrySet().stream()
                    .filter(e -> e.getValue() > epsilon)
                    .filter(e -> !e.getKey().equals(removedNodeId))
                    .toList();

            for (Map.Entry<String, Double> entry : active) {
                String nodeId = entry.getKey();
                double nodeScore = entry.getValue();

                List<IncomingEdge> incoming = edgeCache.computeIfAbsent(nodeId,
                        id -> findIncomingEdges(graphService, id));

                // Filter out edges from the removed node
                List<IncomingEdge> filtered = incoming.stream()
                        .filter(ie -> !ie.sourceNodeId.equals(removedNodeId))
                        .toList();

                if (filtered.isEmpty()) continue;

                double sharePerEdge = nodeScore * damping / filtered.size();
                for (IncomingEdge ie : filtered) {
                    double propagated = sharePerEdge * ie.weight;
                    double oldScore = newScores.getOrDefault(ie.sourceNodeId, 0.0);
                    double updated = oldScore + propagated;
                    newScores.put(ie.sourceNodeId, updated);
                    if (Math.abs(updated - oldScore) > epsilon) changed = true;
                }
            }

            scores = newScores;
            if (!changed) break;
        }

        scores.remove(targetNodeId);
        scores.remove(removedNodeId);
        return sortByValueDescending(scores);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private static List<IncomingEdge> findIncomingEdges(KnowledgeGraphService graphService, String nodeId) {
        List<GraphEdge> allEdges = graphService.getEdgesForNode(nodeId);
        List<IncomingEdge> incoming = new ArrayList<>();
        for (GraphEdge edge : allEdges) {
            if (edge.getTargetNode() != null && edge.getTargetNode().getNodeId().equals(nodeId)
                    && edge.getSourceNode() != null) {
                double w = edge.getWeight() != null ? edge.getWeight() : 0.5;
                incoming.add(new IncomingEdge(edge.getSourceNode().getNodeId(), w));
            }
            if (Boolean.TRUE.equals(edge.getBidirectional())
                    && edge.getSourceNode() != null && edge.getSourceNode().getNodeId().equals(nodeId)
                    && edge.getTargetNode() != null) {
                double w = edge.getWeight() != null ? edge.getWeight() : 0.5;
                incoming.add(new IncomingEdge(edge.getTargetNode().getNodeId(), w));
            }
        }
        return incoming;
    }

    private static Map<String, Double> sortByValueDescending(Map<String, Double> map) {
        LinkedHashMap<String, Double> sorted = new LinkedHashMap<>();
        map.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    private record IncomingEdge(String sourceNodeId, double weight) {}
}
