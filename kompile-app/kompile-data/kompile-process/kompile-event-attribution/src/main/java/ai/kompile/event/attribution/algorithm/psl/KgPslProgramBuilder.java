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

import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.psl.GraphPslProgramBuilder;
import ai.kompile.graph.reasoning.psl.PslProgram;
import ai.kompile.knowledgegraph.reasoning.KnowledgeGraphReasoningAdapter;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Knowledge-graph-specific façade that builds a {@link PslProgram} for the subgraph reachable from
 * a set of seed nodes.
 *
 * <p>Since the reasoning engines were extracted into {@code kompile-graph-reasoning}, this is now a
 * thin adapter: it projects the KG subgraph onto the generic {@link ReasoningGraph}
 * (via {@link KnowledgeGraphReasoningAdapter}) and hands it to the store-agnostic
 * {@link GraphPslProgramBuilder}. The PSL encoding therefore lives once in the library and is shared
 * with every other consumer (process mining, etc.); this class only carries the KG-specific
 * traversal config and the node-id ⇄ constant translation that callers rely on.</p>
 */
public class KgPslProgramBuilder {

    public static final String STATE = GraphPslProgramBuilder.STATE;
    public static final String LINK = GraphPslProgramBuilder.LINK;
    public static final String PRIOR = GraphPslProgramBuilder.PRIOR;

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
        ReasoningGraph graph = new KnowledgeGraphReasoningAdapter(graphService)
                .maxDepth(maxDepth)
                .maxNodes(maxNodes)
                .minEdgeWeight(minEdgeWeight)
                .subgraph(seedNodeIds);

        GraphPslProgramBuilder builder = new GraphPslProgramBuilder()
                .minEdgeWeight(minEdgeWeight)
                .propagationWeight(propagationWeight)
                .abductionWeight(abductionWeight)
                .priorWeight(priorWeight)
                .includeAbduction(includeAbduction)
                .includeDefaultRules(includeDefaultRules);

        PslProgram program = builder.build(graph);

        // Expose the library's constant ⇄ entity-id translation under this façade's historical names.
        constantToNodeId.clear();
        constantToNodeId.putAll(builder.constantToEntityId());
        nodeIdToConstant.clear();
        nodeIdToConstant.putAll(builder.entityIdToConstant());
        constantToTitle.clear();
        constantToTitle.putAll(builder.constantToLabel());

        return program;
    }
}
