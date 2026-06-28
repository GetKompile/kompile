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
package ai.kompile.knowledgegraph.reasoning;

import ai.kompile.graph.reasoning.bayesian.NoisyOrCpt;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.embedding.util.INDArrayConverter;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

/**
 * Projects the knowledge graph onto the store-agnostic {@link ReasoningGraph} consumed by
 * {@code kompile-graph-reasoning}. This is the bridge that makes the knowledge-graph store a
 * <em>client</em> of the generic reasoning library: callers materialize a bounded subgraph here,
 * then hand it to {@code GraphPslProgramBuilder}, {@code GraphBayesianNetworkBuilder}, or any other
 * engine without those engines ever depending on the KG store.
 *
 * <p>Because it reads exclusively through {@link KnowledgeGraphService}, it works identically over
 * the JPA-backed store and the vector/matrix-backed store — the service abstracts which one is live.</p>
 *
 * <p>The BFS, edge-strength (noisy-OR causal strength) and bidirectional handling intentionally
 * mirror the former {@code KgPslProgramBuilder}/{@code BayesianNetworkBuilder} extraction so that
 * inference results are unchanged after the refactor.</p>
 */
public final class KnowledgeGraphReasoningAdapter {

    private final KnowledgeGraphService graphService;

    private int maxDepth = 3;
    private int maxNodes = 100;
    private double minEdgeWeight = 0.05;

    public KnowledgeGraphReasoningAdapter(KnowledgeGraphService graphService) {
        this.graphService = graphService;
    }

    public KnowledgeGraphReasoningAdapter maxDepth(int maxDepth) { this.maxDepth = maxDepth; return this; }
    public KnowledgeGraphReasoningAdapter maxNodes(int maxNodes) { this.maxNodes = maxNodes; return this; }
    public KnowledgeGraphReasoningAdapter minEdgeWeight(double minEdgeWeight) { this.minEdgeWeight = minEdgeWeight; return this; }

    /** Materialize the subgraph reachable from {@code seedNodeIds} as a {@link ReasoningGraph}. */
    public ReasoningGraph subgraph(Collection<String> seedNodeIds) {
        if (seedNodeIds == null) seedNodeIds = java.util.List.of();
        Map<String, GraphNode> discovered = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();
        Set<String> edgeSeen = new HashSet<>();
        MutableReasoningGraph graph = new MutableReasoningGraph();
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
            if (current.depth >= maxDepth) {
                continue;
            }
            for (GraphEdge edge : graphService.getEdgesForNode(current.nodeId)) {
                double weight = edge.getWeight() != null ? edge.getWeight() : 0.5;
                if (weight < minEdgeWeight) {
                    continue;
                }
                String sourceId = edge.getSourceNode() != null ? edge.getSourceNode().getNodeId() : null;
                String targetId = edge.getTargetNode() != null ? edge.getTargetNode().getNodeId() : null;
                if (sourceId == null || targetId == null) {
                    continue;
                }
                String neighborId = sourceId.equals(current.nodeId) ? targetId : sourceId;
                if (!discovered.containsKey(neighborId)) {
                    Optional<GraphNode> neighbor = graphService.getNode(neighborId);
                    if (neighbor.isEmpty() || discovered.size() >= maxNodes) {
                        continue;
                    }
                    discovered.put(neighborId, neighbor.get());
                }
                if (!visited.contains(neighborId) && discovered.size() < maxNodes) {
                    visited.add(neighborId);
                    queue.add(new NodeDepth(neighborId, current.depth + 1));
                }
                double strength = NoisyOrCpt.computeCausalStrength(edge.getWeight(), edge.getConfidence(), 1.0);
                addRelation(graph, edgeSeen, sourceId, targetId, strength, edge);
                if (Boolean.TRUE.equals(edge.getBidirectional())) {
                    addRelation(graph, edgeSeen, targetId, sourceId, strength, edge);
                }
            }
        }

        for (GraphNode node : discovered.values()) {
            graph.addEntity(toEntity(node));
        }
        return graph;
    }

    private void addRelation(MutableReasoningGraph graph, Set<String> seen,
                             String sourceId, String targetId, double strength, GraphEdge edge) {
        if (strength < minEdgeWeight) {
            return;
        }
        String key = sourceId + "->" + targetId;
        if (!seen.add(key)) {
            return;
        }
        double confidence = edge.getConfidence() != null ? edge.getConfidence() : 1.0;
        String type = edge.getEdgeType() != null ? edge.getEdgeType().name() : "";
        graph.addRelation(GraphRelation.builder(key, sourceId, targetId)
                .type(type)
                .weight(strength)
                .confidence(confidence)
                .directed(true)
                .embedding(INDArrayConverter.toDoubleArray(edge.getKgRelationEmbedding()))
                .timestamp(toInstant(edge.getOccurredAt()))
                .build());
    }

    private GraphEntity toEntity(GraphNode node) {
        double weight = node.getConfidence() != null ? node.getConfidence() : 1.0;
        String label = node.getTitle() != null ? node.getTitle() : node.getNodeId();
        String type = node.getNodeType() != null ? node.getNodeType().name() : "";
        return GraphEntity.builder(node.getNodeId())
                .type(type)
                .label(label)
                .weight(weight)
                .confidence(weight)
                .embedding(INDArrayConverter.toDoubleArray(node.getKgEmbedding()))
                .timestamp(toInstant(node.getOccurredAt()))
                .build();
    }

    private static Instant toInstant(LocalDateTime ldt) {
        return ldt == null ? null : ldt.toInstant(ZoneOffset.UTC);
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
