/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.attribution.algorithm.bayesian;

import ai.kompile.event.attribution.algorithm.CausalTraversal;
import ai.kompile.event.attribution.domain.CausalEdgeType;
import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Constructs a {@link BayesianNetwork} from a subgraph of the knowledge graph.
 *
 * <p>The builder walks the KG starting from a set of seed nodes, discovers
 * connected nodes within a configurable depth, and converts the subgraph
 * into a DAG suitable for Bayesian inference:</p>
 *
 * <ol>
 *   <li><b>Subgraph extraction</b>: BFS from seed nodes up to maxDepth, collecting
 *       all reachable nodes and edges</li>
 *   <li><b>DAG construction</b>: directed edges become parent→child relationships;
 *       bidirectional and correlation edges are resolved by edge weight (higher-weight
 *       side becomes the parent). Cycles are broken by removing the weakest edge.</li>
 *   <li><b>CPT construction</b>: edge weights and confidence scores are converted
 *       to causal strengths via the noisy-OR model. Root nodes get priors estimated
 *       from their graph properties.</li>
 * </ol>
 */
public class BayesianNetworkBuilder {

    private static final Logger log = LoggerFactory.getLogger(BayesianNetworkBuilder.class);

    private final KnowledgeGraphService graphService;

    private int maxDepth = 3;
    private int maxNodes = 100;
    private double leakProbability = NoisyOrCpt.DEFAULT_LEAK;
    private double minEdgeWeight = 0.05;

    public BayesianNetworkBuilder(KnowledgeGraphService graphService) {
        this.graphService = graphService;
    }

    public BayesianNetworkBuilder maxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
        return this;
    }

    public BayesianNetworkBuilder maxNodes(int maxNodes) {
        this.maxNodes = maxNodes;
        return this;
    }

    public BayesianNetworkBuilder leakProbability(double leakProbability) {
        this.leakProbability = leakProbability;
        return this;
    }

    public BayesianNetworkBuilder minEdgeWeight(double minEdgeWeight) {
        this.minEdgeWeight = minEdgeWeight;
        return this;
    }

    /**
     * Build a Bayesian network from the KG subgraph reachable from the given seed nodes.
     *
     * @param seedNodeIds KG node IDs to start from
     * @return the constructed Bayesian network with CPTs
     */
    public BayesianNetwork build(Collection<String> seedNodeIds) {
        log.info("Building Bayesian network from {} seed nodes, maxDepth={}, maxNodes={}",
                seedNodeIds.size(), maxDepth, maxNodes);

        // 1. Extract subgraph via BFS
        Map<String, GraphNode> discoveredNodes = new LinkedHashMap<>();
        List<DirectedEdge> directedEdges = new ArrayList<>();

        extractSubgraph(seedNodeIds, discoveredNodes, directedEdges);

        if (discoveredNodes.isEmpty()) {
            log.warn("No nodes discovered from seeds: {}", seedNodeIds);
            return new BayesianNetwork();
        }

        log.info("Extracted subgraph: {} nodes, {} directed edges",
                discoveredNodes.size(), directedEdges.size());

        // 2. Create BN nodes
        BayesianNetwork network = new BayesianNetwork();
        for (GraphNode gn : discoveredNodes.values()) {
            String varName = toVariableName(gn.getNodeId());
            BayesianNode bn = new BayesianNode(varName, gn.getNodeId(), gn.getTitle());
            network.addNode(bn);
        }

        // 3. Add edges (with cycle breaking)
        addEdgesWithCycleBreaking(network, directedEdges);

        // 4. Build CPTs
        buildCpts(network, directedEdges);

        log.info("Bayesian network built: {}", network.getStatistics());
        return network;
    }

    /**
     * Build a Bayesian network centered on a single target node.
     * Uses backward traversal to find causal ancestors.
     */
    public BayesianNetwork buildFromTarget(String targetNodeId) {
        return build(List.of(targetNodeId));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SUBGRAPH EXTRACTION
    // ═══════════════════════════════════════════════════════════════════════════

    private void extractSubgraph(Collection<String> seedNodeIds,
                                  Map<String, GraphNode> discoveredNodes,
                                  List<DirectedEdge> directedEdges) {
        Set<String> visited = new HashSet<>();
        Set<String> edgeSeen = new HashSet<>();
        Queue<NodeDepth> queue = new ArrayDeque<>();

        for (String seedId : seedNodeIds) {
            Optional<GraphNode> seedOpt = graphService.getNode(seedId);
            if (seedOpt.isPresent()) {
                discoveredNodes.put(seedId, seedOpt.get());
                queue.add(new NodeDepth(seedId, 0));
                visited.add(seedId);
            }
        }

        while (!queue.isEmpty() && discoveredNodes.size() < maxNodes) {
            NodeDepth current = queue.poll();
            if (current.depth >= maxDepth) continue;

            List<GraphEdge> edges = graphService.getEdgesForNode(current.nodeId);
            for (GraphEdge edge : edges) {
                double weight = edge.getWeight() != null ? edge.getWeight() : 0.5;
                if (weight < minEdgeWeight) continue;

                String sourceId = edge.getSourceNode() != null ? edge.getSourceNode().getNodeId() : null;
                String targetId = edge.getTargetNode() != null ? edge.getTargetNode().getNodeId() : null;
                if (sourceId == null || targetId == null) continue;

                // Discover neighbor nodes
                String neighborId = sourceId.equals(current.nodeId) ? targetId : sourceId;

                if (!discoveredNodes.containsKey(neighborId)) {
                    Optional<GraphNode> neighborOpt = graphService.getNode(neighborId);
                    if (neighborOpt.isEmpty()) continue;
                    discoveredNodes.put(neighborId, neighborOpt.get());
                }

                if (!visited.contains(neighborId) && discoveredNodes.size() < maxNodes) {
                    visited.add(neighborId);
                    queue.add(new NodeDepth(neighborId, current.depth + 1));
                }

                // Record directed edge (deduplicate)
                String edgeKey = sourceId + "->" + targetId;
                if (edgeSeen.contains(edgeKey)) continue;
                edgeSeen.add(edgeKey);

                CausalEdgeType causalType = CausalTraversal.classifyEdge(edge);
                EdgeProvenance edgeProvenance = edge.getProvenance() != null
                        ? EdgeProvenance.valueOf(edge.getProvenance()) : null;
                double causalStrength = NoisyOrCpt.computeCausalStrength(
                        edge.getWeight(), edge.getConfidence(),
                        getTypeMultiplier(causalType)) * getProvenanceMultiplier(edgeProvenance);

                directedEdges.add(new DirectedEdge(
                        sourceId, targetId, causalStrength, causalType, edge.getEdgeId(),
                        edgeProvenance));

                // For bidirectional edges, also add the reverse
                if (Boolean.TRUE.equals(edge.getBidirectional())) {
                    String reverseKey = targetId + "->" + sourceId;
                    if (!edgeSeen.contains(reverseKey)) {
                        edgeSeen.add(reverseKey);
                        directedEdges.add(new DirectedEdge(
                                targetId, sourceId, causalStrength * 0.5, // Weaker reverse
                                causalType, edge.getEdgeId(), edgeProvenance));
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DAG CONSTRUCTION
    // ═══════════════════════════════════════════════════════════════════════════

    private void addEdgesWithCycleBreaking(BayesianNetwork network, List<DirectedEdge> edges) {
        // Sort edges by strength descending — add strongest first, skip if cycle
        edges.sort(Comparator.comparingDouble(DirectedEdge::strength).reversed());

        int added = 0;
        int skipped = 0;

        for (DirectedEdge edge : edges) {
            String parentVar = toVariableName(edge.sourceNodeId);
            String childVar = toVariableName(edge.targetNodeId);

            if (network.getNode(parentVar) == null || network.getNode(childVar) == null) continue;
            if (parentVar.equals(childVar)) continue;

            try {
                network.addEdge(parentVar, childVar);
                added++;
            } catch (IllegalArgumentException e) {
                // Cycle detected — skip this edge
                skipped++;
                log.debug("Skipped edge {} → {} (cycle)", parentVar, childVar);
            }
        }

        log.debug("Added {} edges, skipped {} (cycle breaking)", added, skipped);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CPT CONSTRUCTION
    // ═══════════════════════════════════════════════════════════════════════════

    private void buildCpts(BayesianNetwork network, List<DirectedEdge> edges) {
        // Index edges by target for quick lookup
        Map<String, List<DirectedEdge>> edgesByTarget = new HashMap<>();
        for (DirectedEdge e : edges) {
            edgesByTarget.computeIfAbsent(e.targetNodeId, k -> new ArrayList<>()).add(e);
        }

        for (BayesianNode node : network.getNodes()) {
            if (node.isRoot()) {
                // Root node: prior probability
                GraphNode gn = graphService.getNode(node.getKgNodeId()).orElse(null);
                int outDegree = gn != null ? graphService.getEdgesForNode(gn.getNodeId()).size() : 0;
                Double confidence = gn != null ? gn.getConfidence() : null;
                double prior = NoisyOrCpt.estimatePrior(confidence, outDegree);
                node.setCpt(NoisyOrCpt.buildPrior(node.getVariableName(), prior));
            } else {
                // Non-root: noisy-OR CPT from parent edges
                List<String> parentVars = new ArrayList<>();
                double[] strengths = new double[node.getParents().size()];

                for (int i = 0; i < node.getParents().size(); i++) {
                    BayesianNode parent = node.getParents().get(i);
                    parentVars.add(parent.getVariableName());

                    // Find the edge strength from parent to this node
                    double strength = 0.5; // Default
                    List<DirectedEdge> candidateEdges = edgesByTarget.getOrDefault(
                            node.getKgNodeId(), List.of());
                    for (DirectedEdge de : candidateEdges) {
                        if (de.sourceNodeId.equals(parent.getKgNodeId())) {
                            strength = de.strength;
                            break;
                        }
                    }
                    strengths[i] = strength;
                }

                node.setCpt(NoisyOrCpt.buildCpt(
                        node.getVariableName(), parentVars, strengths, leakProbability));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Convert a KG node ID to a BN variable name.
     * Uses the node ID directly, prefixed with "v_" to ensure valid identifier.
     */
    static String toVariableName(String kgNodeId) {
        return "v_" + kgNodeId.replace("-", "_");
    }

    /**
     * Get the causal type multiplier matching CausalTraversal.computeHopStrength().
     */
    static double getTypeMultiplier(CausalEdgeType type) {
        return switch (type) {
            case CAUSES -> 1.0;
            case TRIGGERS -> 0.95;
            case ENABLES -> 0.85;
            case DERIVED_FROM -> 0.8;
            case CONTRIBUTES_TO -> 0.7;
            case INFLUENCES -> 0.6;
            case PREVENTS -> 0.5;
            case CORRELATES_WITH -> 0.3;
        };
    }

    /**
     * Discount factor based on how the edge was obtained.
     * EXTRACTED edges are directly stated in source text — full weight.
     * INFERRED edges are derived by algorithms — reduced weight.
     * AMBIGUOUS edges have uncertain provenance — heavily discounted.
     */
    static double getProvenanceMultiplier(EdgeProvenance provenance) {
        if (provenance == null) return 0.8; // Unknown provenance, moderate discount
        return switch (provenance) {
            case EXTRACTED -> 1.0;
            case INFERRED -> 0.7;
            case AMBIGUOUS -> 0.4;
        };
    }

    private record NodeDepth(String nodeId, int depth) {}

    /**
     * A directed edge extracted from the KG, annotated with causal strength.
     */
    record DirectedEdge(String sourceNodeId, String targetNodeId,
                        double strength, CausalEdgeType causalType, String edgeId,
                        EdgeProvenance provenance) {}
}
