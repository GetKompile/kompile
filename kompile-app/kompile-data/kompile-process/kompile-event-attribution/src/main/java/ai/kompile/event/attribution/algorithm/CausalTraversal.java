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

import ai.kompile.event.attribution.domain.*;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;

/**
 * Backward causal traversal from a target event through the knowledge graph.
 *
 * <p>Starting from the target node, this algorithm traverses edges backward
 * (following incoming edges) to discover ancestor nodes that may have caused
 * or influenced the target event. It builds {@link AttributionChain}s by:</p>
 *
 * <ol>
 *   <li>BFS backward from the target, following edges in reverse direction</li>
 *   <li>At each hop, classifying the edge into a {@link CausalEdgeType} based on
 *       the existing {@link EdgeType} and metadata</li>
 *   <li>Scoring each hop's causal strength from edge weight, temporal proximity,
 *       and evidence type</li>
 *   <li>Pruning branches below the confidence threshold</li>
 *   <li>Collecting all paths that reach leaf nodes (no further ancestors) as chains</li>
 * </ol>
 */
public class CausalTraversal {

    private static final Logger log = LoggerFactory.getLogger(CausalTraversal.class);

    private CausalTraversal() {}

    /**
     * Discover causal chains backward from a target node.
     *
     * @param graphService  the knowledge graph service
     * @param targetNodeId  the node to explain
     * @param maxDepth      maximum backward traversal depth
     * @param maxChains     maximum number of chains to return
     * @param minConfidence minimum per-chain confidence
     * @return traversal result with chains and statistics
     */
    public static TraversalResult traverseBackward(KnowledgeGraphService graphService,
                                                    String targetNodeId,
                                                    int maxDepth,
                                                    int maxChains,
                                                    double minConfidence) {
        return traverseBackward(graphService, targetNodeId, maxDepth, maxChains,
                minConfidence, null, null);
    }

    /**
     * Discover causal chains backward from a target node, optionally filtered
     * by a temporal window.
     *
     * @param graphService   the knowledge graph service
     * @param targetNodeId   the node to explain
     * @param maxDepth       maximum backward traversal depth
     * @param maxChains      maximum number of chains to return
     * @param minConfidence  minimum per-chain confidence
     * @param temporalStart  if non-null, exclude nodes with timestamps before this
     * @param temporalEnd    if non-null, exclude nodes with timestamps after this
     * @return traversal result with chains and statistics
     */
    public static TraversalResult traverseBackward(KnowledgeGraphService graphService,
                                                    String targetNodeId,
                                                    int maxDepth,
                                                    int maxChains,
                                                    double minConfidence,
                                                    Instant temporalStart,
                                                    Instant temporalEnd) {
        Optional<GraphNode> targetOpt = graphService.getNode(targetNodeId);
        if (targetOpt.isEmpty()) {
            return TraversalResult.empty(targetNodeId);
        }
        GraphNode targetNode = targetOpt.get();

        List<AttributionChain> chains = new ArrayList<>();
        int[] stats = {0, 0}; // [nodesVisited, edgesExamined]
        int[] totalNodesExpanded = {0};
        final int MAX_NODES_VISITED = 1000;

        // DFS with per-path visited sets to allow the same node to appear in
        // independent causal chains (A→X→target and B→X→target must both be emitted).
        Set<String> initialPath = new HashSet<>();
        initialPath.add(targetNodeId);
        Deque<TraversalFrame> stack = new ArrayDeque<>();
        stack.push(new TraversalFrame(targetNodeId, targetNode.getTitle(), new ArrayList<>(), 1.0, initialPath));

        while (!stack.isEmpty() && chains.size() < maxChains && totalNodesExpanded[0] < MAX_NODES_VISITED) {
            TraversalFrame frame = stack.pop();
            stats[0]++;
            totalNodesExpanded[0]++;

            List<GraphEdge> incomingEdges = getIncomingEdges(graphService, frame.nodeId);
            stats[1] += incomingEdges.size();

            boolean isLeaf = true;

            for (GraphEdge edge : incomingEdges) {
                String ancestorId = getAncestorNodeId(edge, frame.nodeId);
                if (ancestorId == null || frame.pathNodes.contains(ancestorId)) continue;

                Optional<GraphNode> ancestorOpt = graphService.getNode(ancestorId);
                if (ancestorOpt.isEmpty()) continue;
                GraphNode ancestor = ancestorOpt.get();

                // Apply temporal bounds filter
                if (!isWithinTemporalBounds(ancestor, temporalStart, temporalEnd)) continue;

                CausalEdgeType causalType = classifyEdge(edge);
                double hopStrength = computeHopStrength(edge, causalType);
                double chainConfidence = frame.accumulatedConfidence * hopStrength;

                if (chainConfidence < minConfidence) continue;
                isLeaf = false;

                CausalHop hop = CausalHop.builder()
                        .causeNodeId(ancestorId)
                        .causeTitle(ancestor.getTitle())
                        .effectNodeId(frame.nodeId)
                        .effectTitle(frame.nodeTitle)
                        .causalType(causalType)
                        .strength(hopStrength)
                        .evidence(List.of(buildStructuralEvidence(edge, causalType)))
                        .build();

                List<CausalHop> newPath = new ArrayList<>(frame.path);
                newPath.add(hop);

                if (newPath.size() >= maxDepth) {
                    // Max depth reached — emit chain
                    chains.add(buildChain(targetNodeId, targetNode.getTitle(),
                            ancestorId, ancestor.getTitle(), newPath, chainConfidence));
                } else {
                    Set<String> newPathNodes = new HashSet<>(frame.pathNodes);
                    newPathNodes.add(ancestorId);
                    stack.push(new TraversalFrame(ancestorId, ancestor.getTitle(),
                            newPath, chainConfidence, newPathNodes));
                }
            }

            // Leaf node with non-empty path — this is a root cause
            if (isLeaf && !frame.path.isEmpty()) {
                String rootId = frame.path.get(frame.path.size() - 1).getCauseNodeId();
                String rootTitle = frame.path.get(frame.path.size() - 1).getCauseTitle();
                chains.add(buildChain(targetNodeId, targetNode.getTitle(),
                        rootId, rootTitle, frame.path, frame.accumulatedConfidence));
            }
        }

        // Sort by confidence descending
        chains.sort(Comparator.comparingDouble(AttributionChain::getOverallConfidence).reversed());
        if (chains.size() > maxChains) {
            chains = chains.subList(0, maxChains);
        }

        return new TraversalResult(chains, stats[0], stats[1]);
    }

    /**
     * Forward traversal for prediction: discover events likely to follow from a source node.
     */
    public static TraversalResult traverseForward(KnowledgeGraphService graphService,
                                                   String sourceNodeId,
                                                   int maxDepth,
                                                   int maxPaths,
                                                   double minConfidence) {
        return traverseForward(graphService, sourceNodeId, maxDepth, maxPaths,
                minConfidence, null, null);
    }

    /**
     * Forward traversal with optional temporal bounds.
     */
    public static TraversalResult traverseForward(KnowledgeGraphService graphService,
                                                   String sourceNodeId,
                                                   int maxDepth,
                                                   int maxPaths,
                                                   double minConfidence,
                                                   Instant temporalStart,
                                                   Instant temporalEnd) {
        Optional<GraphNode> sourceOpt = graphService.getNode(sourceNodeId);
        if (sourceOpt.isEmpty()) {
            return TraversalResult.empty(sourceNodeId);
        }
        GraphNode sourceNode = sourceOpt.get();

        List<AttributionChain> chains = new ArrayList<>();
        int[] stats = {0, 0};
        int[] totalNodesExpanded = {0};
        final int MAX_NODES_VISITED = 1000;

        Set<String> initialPath = new HashSet<>();
        initialPath.add(sourceNodeId);
        Deque<TraversalFrame> stack = new ArrayDeque<>();
        stack.push(new TraversalFrame(sourceNodeId, sourceNode.getTitle(), new ArrayList<>(), 1.0, initialPath));

        while (!stack.isEmpty() && chains.size() < maxPaths && totalNodesExpanded[0] < MAX_NODES_VISITED) {
            TraversalFrame frame = stack.pop();
            stats[0]++;
            totalNodesExpanded[0]++;

            List<GraphEdge> outgoingEdges = getOutgoingEdges(graphService, frame.nodeId);
            stats[1] += outgoingEdges.size();

            boolean isLeaf = true;

            for (GraphEdge edge : outgoingEdges) {
                String descendantId = getDescendantNodeId(edge, frame.nodeId);
                if (descendantId == null || frame.pathNodes.contains(descendantId)) continue;

                Optional<GraphNode> descendantOpt = graphService.getNode(descendantId);
                if (descendantOpt.isEmpty()) continue;
                GraphNode descendant = descendantOpt.get();

                // Apply temporal bounds filter
                if (!isWithinTemporalBounds(descendant, temporalStart, temporalEnd)) continue;

                CausalEdgeType causalType = classifyEdge(edge);
                double hopStrength = computeHopStrength(edge, causalType);
                double chainConfidence = frame.accumulatedConfidence * hopStrength;

                if (chainConfidence < minConfidence) continue;
                isLeaf = false;

                CausalHop hop = CausalHop.builder()
                        .causeNodeId(frame.nodeId)
                        .causeTitle(frame.nodeTitle)
                        .effectNodeId(descendantId)
                        .effectTitle(descendant.getTitle())
                        .causalType(causalType)
                        .strength(hopStrength)
                        .evidence(List.of(buildStructuralEvidence(edge, causalType)))
                        .build();

                List<CausalHop> newPath = new ArrayList<>(frame.path);
                newPath.add(hop);

                if (newPath.size() >= maxDepth) {
                    chains.add(buildForwardChain(descendantId, descendant.getTitle(),
                            sourceNodeId, sourceNode.getTitle(), newPath, chainConfidence));
                } else {
                    Set<String> newPathNodes = new HashSet<>(frame.pathNodes);
                    newPathNodes.add(descendantId);
                    stack.push(new TraversalFrame(descendantId, descendant.getTitle(),
                            newPath, chainConfidence, newPathNodes));
                }
            }

            if (isLeaf && !frame.path.isEmpty()) {
                String leafId = frame.path.get(frame.path.size() - 1).getEffectNodeId();
                String leafTitle = frame.path.get(frame.path.size() - 1).getEffectTitle();
                chains.add(buildForwardChain(leafId, leafTitle,
                        sourceNodeId, sourceNode.getTitle(), frame.path, frame.accumulatedConfidence));
            }
        }

        chains.sort(Comparator.comparingDouble(AttributionChain::getOverallConfidence).reversed());
        if (chains.size() > maxPaths) {
            chains = chains.subList(0, maxPaths);
        }

        return new TraversalResult(chains, stats[0], stats[1]);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EDGE CLASSIFICATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Classify a knowledge graph edge into a causal edge type based on its EdgeType,
     * label, and metadata.
     */
    public static CausalEdgeType classifyEdge(GraphEdge edge) {
        EdgeType type = edge.getEdgeType();
        String label = edge.getLabel() != null ? edge.getLabel().toLowerCase() : "";
        String desc = edge.getDescription() != null ? edge.getDescription().toLowerCase() : "";
        String combined = label + " " + desc;

        // Check explicit causal keywords in label/description
        if (combined.contains("causes") || combined.contains("caused by")) return CausalEdgeType.CAUSES;
        if (combined.contains("triggers") || combined.contains("triggered")) return CausalEdgeType.TRIGGERS;
        if (combined.contains("enables") || combined.contains("enabled")) return CausalEdgeType.ENABLES;
        if (combined.contains("prevents") || combined.contains("blocked")) return CausalEdgeType.PREVENTS;
        if (combined.contains("derived") || combined.contains("generated from")) return CausalEdgeType.DERIVED_FROM;
        if (combined.contains("contributes") || combined.contains("factor")) return CausalEdgeType.CONTRIBUTES_TO;
        if (combined.contains("influences") || combined.contains("affects")) return CausalEdgeType.INFLUENCES;

        // Fall back to EdgeType mapping
        return switch (type) {
            case HIERARCHICAL -> CausalEdgeType.DERIVED_FROM;
            case TEMPORAL -> CausalEdgeType.TRIGGERS;
            case CITATION -> CausalEdgeType.DERIVED_FROM;
            case SHARED_ENTITY -> CausalEdgeType.CORRELATES_WITH;
            case EMBEDDING_SIMILARITY -> CausalEdgeType.CORRELATES_WITH;
            case CROSS_SOURCE -> CausalEdgeType.CONTRIBUTES_TO;
            case CONTAINS -> CausalEdgeType.DERIVED_FROM;
            case AUTHORED_BY -> CausalEdgeType.CONTRIBUTES_TO;
            case ADDRESSED_TO -> CausalEdgeType.INFLUENCES;
            case EXTRACTED_FROM -> CausalEdgeType.DERIVED_FROM;
            case USER_DEFINED -> CausalEdgeType.INFLUENCES;
        };
    }

    /**
     * Compute the causal strength of a single hop based on edge weight,
     * causal type, and confidence.
     */
    public static double computeHopStrength(GraphEdge edge, CausalEdgeType causalType) {
        double baseWeight = edge.getWeight() != null ? edge.getWeight() : 0.5;
        double confidence = edge.getConfidence() != null ? edge.getConfidence() : 0.5;

        // Causal type multiplier — direct causation is stronger than correlation
        double typeMultiplier = switch (causalType) {
            case CAUSES -> 1.0;
            case TRIGGERS -> 0.95;
            case ENABLES -> 0.85;
            case DERIVED_FROM -> 0.8;
            case CONTRIBUTES_TO -> 0.7;
            case INFLUENCES -> 0.6;
            case PREVENTS -> 0.5; // Inversion — preventing X is weaker evidence for causing not-X
            case CORRELATES_WITH -> 0.3;
        };

        return Math.min(1.0, baseWeight * confidence * typeMultiplier);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private static List<GraphEdge> getIncomingEdges(KnowledgeGraphService graphService, String nodeId) {
        List<GraphEdge> allEdges = graphService.getEdgesForNode(nodeId);
        List<GraphEdge> incoming = new ArrayList<>();
        for (GraphEdge edge : allEdges) {
            // Incoming = this node is the target
            if (edge.getTargetNode() != null && edge.getTargetNode().getNodeId().equals(nodeId)) {
                incoming.add(edge);
            }
            // Bidirectional edges count both ways
            if (Boolean.TRUE.equals(edge.getBidirectional()) &&
                    edge.getSourceNode() != null && edge.getSourceNode().getNodeId().equals(nodeId)) {
                incoming.add(edge);
            }
        }
        return incoming;
    }

    private static List<GraphEdge> getOutgoingEdges(KnowledgeGraphService graphService, String nodeId) {
        List<GraphEdge> allEdges = graphService.getEdgesForNode(nodeId);
        List<GraphEdge> outgoing = new ArrayList<>();
        for (GraphEdge edge : allEdges) {
            if (edge.getSourceNode() != null && edge.getSourceNode().getNodeId().equals(nodeId)) {
                outgoing.add(edge);
            }
            if (Boolean.TRUE.equals(edge.getBidirectional()) &&
                    edge.getTargetNode() != null && edge.getTargetNode().getNodeId().equals(nodeId)) {
                outgoing.add(edge);
            }
        }
        return outgoing;
    }

    private static String getAncestorNodeId(GraphEdge edge, String currentNodeId) {
        if (edge.getTargetNode() != null && edge.getTargetNode().getNodeId().equals(currentNodeId)) {
            return edge.getSourceNode() != null ? edge.getSourceNode().getNodeId() : null;
        }
        if (Boolean.TRUE.equals(edge.getBidirectional()) &&
                edge.getSourceNode() != null && edge.getSourceNode().getNodeId().equals(currentNodeId)) {
            return edge.getTargetNode() != null ? edge.getTargetNode().getNodeId() : null;
        }
        return null;
    }

    private static String getDescendantNodeId(GraphEdge edge, String currentNodeId) {
        if (edge.getSourceNode() != null && edge.getSourceNode().getNodeId().equals(currentNodeId)) {
            return edge.getTargetNode() != null ? edge.getTargetNode().getNodeId() : null;
        }
        if (Boolean.TRUE.equals(edge.getBidirectional()) &&
                edge.getTargetNode() != null && edge.getTargetNode().getNodeId().equals(currentNodeId)) {
            return edge.getSourceNode() != null ? edge.getSourceNode().getNodeId() : null;
        }
        return null;
    }

    private static AttributionEvidence buildStructuralEvidence(GraphEdge edge, CausalEdgeType causalType) {
        return AttributionEvidence.builder()
                .evidenceType(EvidenceType.GRAPH_STRUCTURAL)
                .strength(edge.getWeight() != null ? edge.getWeight() : 0.5)
                .edgeId(edge.getEdgeId())
                .summary("Graph edge of type " + edge.getEdgeType() +
                        " classified as " + causalType +
                        (edge.getDescription() != null ? ": " + edge.getDescription() : ""))
                .collectedAt(Instant.now())
                .build();
    }

    private static AttributionChain buildChain(String targetId, String targetTitle,
                                                String rootId, String rootTitle,
                                                List<CausalHop> hops, double confidence) {
        // Reverse hops so they go from root cause → target
        List<CausalHop> orderedHops = new ArrayList<>(hops);
        Collections.reverse(orderedHops);

        return AttributionChain.builder()
                .chainId(UUID.randomUUID().toString())
                .targetEventNodeId(targetId)
                .targetEventTitle(targetTitle)
                .rootCauseNodeId(rootId)
                .rootCauseTitle(rootTitle)
                .hops(orderedHops)
                .overallConfidence(confidence)
                .confidenceBand(AttributionConfidence.fromScore(confidence))
                .computedAt(Instant.now())
                .build();
    }

    private static AttributionChain buildForwardChain(String targetId, String targetTitle,
                                                       String rootId, String rootTitle,
                                                       List<CausalHop> hops, double confidence) {
        // Forward traversal builds hops source→descendant (already root→target order),
        // so no reversal is needed.
        return AttributionChain.builder()
                .chainId(UUID.randomUUID().toString())
                .targetEventNodeId(targetId)
                .targetEventTitle(targetTitle)
                .rootCauseNodeId(rootId)
                .rootCauseTitle(rootTitle)
                .hops(new ArrayList<>(hops))
                .overallConfidence(confidence)
                .confidenceBand(AttributionConfidence.fromScore(confidence))
                .computedAt(Instant.now())
                .build();
    }

    /**
     * Checks whether a node's timestamp falls within the given temporal bounds.
     * Nodes without timestamps are always included (no data to filter on).
     */
    private static boolean isWithinTemporalBounds(GraphNode node,
                                                    Instant temporalStart,
                                                    Instant temporalEnd) {
        if (temporalStart == null && temporalEnd == null) return true;
        Instant nodeTime = TemporalChainExtractor.extractTimestamp(node);
        if (nodeTime == null) return true; // No timestamp — cannot filter
        if (temporalStart != null && nodeTime.isBefore(temporalStart)) return false;
        if (temporalEnd != null && nodeTime.isAfter(temporalEnd)) return false;
        return true;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INNER TYPES
    // ═══════════════════════════════════════════════════════════════════════════

    private record TraversalFrame(String nodeId, String nodeTitle,
                                   List<CausalHop> path, double accumulatedConfidence,
                                   Set<String> pathNodes) {}

    /**
     * Result of a causal traversal, containing chains and traversal statistics.
     */
    public record TraversalResult(List<AttributionChain> chains, int nodesVisited, int edgesExamined) {
        public static TraversalResult empty(String nodeId) {
            return new TraversalResult(List.of(), 0, 0);
        }
    }
}
