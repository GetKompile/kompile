/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.attribution.algorithm.psl;

import ai.kompile.event.attribution.algorithm.bayesian.NoisyOrCpt;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

/**
 * Builds a {@link PslProgram} from a subgraph of the knowledge graph, the PSL analogue
 * of {@code BayesianNetworkBuilder}.
 *
 * <p>It performs a bounded BFS from the seed nodes and maps the discovered subgraph onto
 * three predicates:</p>
 * <ul>
 *   <li>{@code State(N)} — the inferred soft truth that node {@code N} is active (a target),</li>
 *   <li>{@code Link(X, Y)} — an observed atom whose truth is the directed causal strength of the
 *       edge {@code X→Y} ({@code weight·confidence}, reusing {@link NoisyOrCpt#computeCausalStrength}),</li>
 *   <li>{@code Prior(N)} — an observed structural prior for node {@code N}.</li>
 * </ul>
 *
 * <p>Default weighted rules wire these together for collective inference:</p>
 * <pre>
 *   propagation: State(X) &amp; Link(X, Y) -&gt; State(Y)   // active causes activate their effects
 *   abduction:   State(Y) &amp; Link(X, Y) -&gt; State(X)   // observed effects raise their causes
 *   prior:       Prior(N) &lt;-&gt; State(N)                 // soft-equality anchor to the structural prior
 * </pre>
 *
 * <p>Knowledge-graph node ids (which can embed file paths and other arbitrary characters)
 * are replaced by safe synthetic constants ({@code n0, n1, ...}); use
 * {@link #constantToNodeId()} / {@link #nodeIdToConstant()} to translate.</p>
 */
public class KgPslProgramBuilder {

    private static final Logger log = LoggerFactory.getLogger(KgPslProgramBuilder.class);

    public static final String STATE = "State";
    public static final String LINK = "Link";
    public static final String PRIOR = "Prior";

    private final KnowledgeGraphService graphService;

    private int maxDepth = 3;
    private int maxNodes = 100;
    private double minEdgeWeight = 0.05;
    private double propagationWeight = 2.0;
    private double abductionWeight = 1.0;
    private double priorWeight = 1.0;
    private boolean includeAbduction = true;
    private boolean includeDefaultRules = true;

    private final Map<String, String> constantToNodeId = new LinkedHashMap<>();
    private final Map<String, String> nodeIdToConstant = new LinkedHashMap<>();
    private final Map<String, String> constantToTitle = new LinkedHashMap<>();

    public KgPslProgramBuilder(KnowledgeGraphService graphService) {
        this.graphService = graphService;
    }

    public KgPslProgramBuilder maxDepth(int maxDepth) { this.maxDepth = maxDepth; return this; }
    public KgPslProgramBuilder maxNodes(int maxNodes) { this.maxNodes = maxNodes; return this; }
    public KgPslProgramBuilder minEdgeWeight(double w) { this.minEdgeWeight = w; return this; }
    public KgPslProgramBuilder propagationWeight(double w) { this.propagationWeight = w; return this; }
    public KgPslProgramBuilder abductionWeight(double w) { this.abductionWeight = w; return this; }
    public KgPslProgramBuilder priorWeight(double w) { this.priorWeight = w; return this; }
    public KgPslProgramBuilder includeAbduction(boolean b) { this.includeAbduction = b; return this; }

    /** When false, only the KG atoms are populated and no default rules are added (caller supplies rules). */
    public KgPslProgramBuilder includeDefaultRules(boolean b) { this.includeDefaultRules = b; return this; }

    public Map<String, String> constantToNodeId() { return constantToNodeId; }
    public Map<String, String> nodeIdToConstant() { return nodeIdToConstant; }
    public Map<String, String> constantToTitle() { return constantToTitle; }

    /** Build the PSL program for the subgraph reachable from {@code seedNodeIds}. */
    public PslProgram build(Collection<String> seedNodeIds) {
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        List<DirectedEdge> edges = new ArrayList<>();
        extractSubgraph(seedNodeIds, nodes, edges);

        PslProgram program = new PslProgram();
        if (nodes.isEmpty()) {
            log.warn("No nodes discovered from PSL seeds: {}", seedNodeIds);
            return program;
        }

        // Assign safe constants and per-node atoms.
        int i = 0;
        Map<String, Integer> outDegree = new HashMap<>();
        for (DirectedEdge e : edges) outDegree.merge(e.sourceId, 1, Integer::sum);

        for (GraphNode node : nodes.values()) {
            String constant = "n" + (i++);
            constantToNodeId.put(constant, node.getNodeId());
            nodeIdToConstant.put(node.getNodeId(), constant);
            constantToTitle.put(constant, node.getTitle() != null ? node.getTitle() : node.getNodeId());

            double prior = NoisyOrCpt.estimatePrior(node.getConfidence(),
                    outDegree.getOrDefault(node.getNodeId(), 0));
            program.observe(PRIOR, prior, constant);
            program.target(STATE, constant);
        }

        // Edge atoms (only between discovered nodes).
        for (DirectedEdge e : edges) {
            String cs = nodeIdToConstant.get(e.sourceId);
            String ct = nodeIdToConstant.get(e.targetId);
            if (cs == null || ct == null) continue;
            program.observe(LINK, e.strength, cs, ct);
        }

        if (includeDefaultRules) {
            addDefaultRules(program);
        }

        log.info("Built PSL program: {} nodes, {} directed edges, {} rules",
                nodes.size(), edges.size(), program.rules().size());
        return program;
    }

    private void addDefaultRules(PslProgram program) {
        // Active causes tend to activate their effects.
        program.addRule(PslRule.parse(propagationWeight + ": " + STATE + "(X) & " + LINK
                + "(X, Y) -> " + STATE + "(Y) ^2"));
        // Observed effects raise the plausibility of their causes (abductive reasoning).
        if (includeAbduction) {
            program.addRule(PslRule.parse(abductionWeight + ": " + STATE + "(Y) & " + LINK
                    + "(X, Y) -> " + STATE + "(X) ^2"));
        }
        // Soft-equality anchor of each node's state to its structural prior.
        program.addRule(PslRule.parse(priorWeight + ": " + PRIOR + "(N) -> " + STATE + "(N) ^2"));
        program.addRule(PslRule.parse(priorWeight + ": " + STATE + "(N) -> " + PRIOR + "(N) ^2"));
    }

    // ─── Subgraph extraction (BFS), mirroring BayesianNetworkBuilder ───────────

    private void extractSubgraph(Collection<String> seedNodeIds,
                                 Map<String, GraphNode> discovered, List<DirectedEdge> edges) {
        Set<String> visited = new HashSet<>();
        Set<String> edgeSeen = new HashSet<>();
        Queue<NodeDepth> queue = new ArrayDeque<>();

        for (String seedId : seedNodeIds) {
            Optional<GraphNode> seed = graphService.getNode(seedId);
            if (seed.isPresent()) {
                discovered.put(seedId, seed.get());
                queue.add(new NodeDepth(seedId, 0));
                visited.add(seedId);
            }
        }

        while (!queue.isEmpty() && discovered.size() < maxNodes) {
            NodeDepth current = queue.poll();
            if (current.depth >= maxDepth) continue;

            for (GraphEdge edge : graphService.getEdgesForNode(current.nodeId)) {
                double weight = edge.getWeight() != null ? edge.getWeight() : 0.5;
                if (weight < minEdgeWeight) continue;

                String sourceId = edge.getSourceNode() != null ? edge.getSourceNode().getNodeId() : null;
                String targetId = edge.getTargetNode() != null ? edge.getTargetNode().getNodeId() : null;
                if (sourceId == null || targetId == null) continue;

                String neighborId = sourceId.equals(current.nodeId) ? targetId : sourceId;
                if (!discovered.containsKey(neighborId)) {
                    Optional<GraphNode> neighbor = graphService.getNode(neighborId);
                    if (neighbor.isEmpty()) continue;
                    if (discovered.size() >= maxNodes) continue;
                    discovered.put(neighborId, neighbor.get());
                }
                if (!visited.contains(neighborId) && discovered.size() < maxNodes) {
                    visited.add(neighborId);
                    queue.add(new NodeDepth(neighborId, current.depth + 1));
                }

                double strength = NoisyOrCpt.computeCausalStrength(
                        edge.getWeight(), edge.getConfidence(), 1.0);
                addDirectedEdge(edges, edgeSeen, sourceId, targetId, strength);
                if (Boolean.TRUE.equals(edge.getBidirectional())) {
                    addDirectedEdge(edges, edgeSeen, targetId, sourceId, strength);
                }
            }
        }
    }

    private void addDirectedEdge(List<DirectedEdge> edges, Set<String> seen,
                                 String sourceId, String targetId, double strength) {
        if (strength < minEdgeWeight) return;
        String key = sourceId + "->" + targetId;
        if (!seen.add(key)) return;
        edges.add(new DirectedEdge(sourceId, targetId, strength));
    }

    private static final class DirectedEdge {
        final String sourceId;
        final String targetId;
        final double strength;

        DirectedEdge(String sourceId, String targetId, double strength) {
            this.sourceId = sourceId;
            this.targetId = targetId;
            this.strength = strength;
        }
    }

    private static final class NodeDepth {
        final String nodeId;
        final int depth;

        NodeDepth(String nodeId, int depth) {
            this.nodeId = nodeId;
            this.depth = depth;
        }
    }
}
