/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.algorithms.adjacency;

import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;

import java.util.List;

/**
 * Materializes an {@link AdjacencyView} from the JPA-backed
 * {@link KnowledgeGraphService}. Walks the graph from source nodes to enumerate
 * connected entities and edges; for very large graphs callers should pre-filter
 * by {@code factSheetId} or use Cypher passthrough instead.
 */
public final class AdjacencyViewBuilder {

    private AdjacencyViewBuilder() {}

    /**
     * Builds an adjacency view for all nodes reachable from the given source set.
     * If {@code sourceNodeIds} is null or empty, all source nodes are used.
     */
    public static AdjacencyView fromGraph(KnowledgeGraphService kgs,
                                           List<String> sourceNodeIds,
                                           int maxDepth) {
        AdjacencyView.Builder b = AdjacencyView.builder();
        List<GraphNode> sources;
        if (sourceNodeIds == null || sourceNodeIds.isEmpty()) {
            sources = kgs.getAllSources();
        } else {
            sources = sourceNodeIds.stream()
                    .map(kgs::getNode)
                    .filter(java.util.Optional::isPresent)
                    .map(java.util.Optional::get)
                    .toList();
        }

        for (GraphNode src : sources) {
            visit(kgs, b, src, 0, maxDepth);
        }
        return b.build();
    }

    /**
     * Builds an adjacency view restricted to nodes belonging to a given fact sheet.
     * Walks edges to nodes within the same fact sheet only.
     */
    public static AdjacencyView fromFactSheet(KnowledgeGraphService kgs, Long factSheetId) {
        AdjacencyView.Builder b = AdjacencyView.builder();
        if (factSheetId == null) return fromGraph(kgs, null, Integer.MAX_VALUE);
        for (GraphNode src : kgs.getAllSources()) {
            if (!factSheetId.equals(src.getFactSheetId())) continue;
            visit(kgs, b, src, 0, Integer.MAX_VALUE);
        }
        return b.build();
    }

    /**
     * Builds an adjacency view for all nodes of a given type.
     */
    public static AdjacencyView fromNodes(KnowledgeGraphService kgs,
                                           NodeLevel type,
                                           int limit) {
        AdjacencyView.Builder b = AdjacencyView.builder();
        List<GraphNode> nodes = kgs.searchNodes("", type, limit);
        for (GraphNode n : nodes) {
            b.addNode(n.getNodeId());
            for (GraphEdge edge : kgs.getEdgesForNode(n.getNodeId())) {
                String src = edge.getSourceNode().getNodeId();
                String tgt = edge.getTargetNode().getNodeId();
                double w = edge.getWeight() != null ? edge.getWeight() : 1.0;
                b.addEdge(src, tgt, w);
                if (Boolean.TRUE.equals(edge.getBidirectional())) {
                    b.addEdge(tgt, src, w);
                }
            }
        }
        return b.build();
    }

    private static void visit(KnowledgeGraphService kgs,
                              AdjacencyView.Builder b,
                              GraphNode node,
                              int depth,
                              int maxDepth) {
        if (depth > maxDepth) return;
        b.addNode(node.getNodeId());
        for (GraphEdge edge : kgs.getEdgesForNode(node.getNodeId())) {
            String src = edge.getSourceNode().getNodeId();
            String tgt = edge.getTargetNode().getNodeId();
            double w = edge.getWeight() != null ? edge.getWeight() : 1.0;
            b.addEdge(src, tgt, w);
            if (Boolean.TRUE.equals(edge.getBidirectional())) {
                b.addEdge(tgt, src, w);
            }
        }
        if (depth < maxDepth) {
            for (GraphNode child : kgs.getChildren(node.getNodeId())) {
                visit(kgs, b, child, depth + 1, maxDepth);
            }
        }
    }
}
