/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.tool.graph;

import ai.kompile.graph.algorithms.DegreeCentrality;
import ai.kompile.graph.algorithms.ShortestPathAlgorithm;
import ai.kompile.graph.algorithms.service.GraphAlgorithmService;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.knowledgegraph.unified.UnifiedGraphBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP tools for graph algorithms: centrality measures, shortest path, and similarity.
 * Wraps the {@link GraphAlgorithmService} for LLM agent access.
 */
@Component
@ConditionalOnBean(GraphAlgorithmService.class)
public class GraphAlgorithmsTool {

    private static final Logger log = LoggerFactory.getLogger(GraphAlgorithmsTool.class);

    private final GraphAlgorithmService algorithmService;
    private final KnowledgeGraphService graphService;
    private final UnifiedGraphBridge unifiedGraphBridge;

    // ═══════════════════════════════════════════════════════════════════════════
    // INPUT RECORDS
    // ═══════════════════════════════════════════════════════════════════════════

    public record PageRankInput(
            Long factSheetId,
            Double damping,
            Integer maxIterations,
            Integer topK
    ) {}

    public record DegreeCentralityInput(
            Long factSheetId,
            String type,
            Integer topK
    ) {}

    public record BetweennessCentralityInput(
            Long factSheetId,
            Integer sampleSize,
            Integer topK
    ) {}

    public record ShortestPathInput(
            String fromNodeId,
            String toNodeId,
            Long factSheetId,
            Boolean weighted
    ) {}

    public record NodeSimilarityInput(
            String nodeIdA,
            String nodeIdB,
            Long factSheetId
    ) {}

    public GraphAlgorithmsTool(GraphAlgorithmService algorithmService,
                               KnowledgeGraphService graphService) {
        this(algorithmService, graphService, null);
    }

    @Autowired
    public GraphAlgorithmsTool(GraphAlgorithmService algorithmService,
                               KnowledgeGraphService graphService,
                               @org.springframework.lang.Nullable UnifiedGraphBridge unifiedGraphBridge) {
        this.algorithmService = algorithmService;
        this.graphService = graphService;
        this.unifiedGraphBridge = unifiedGraphBridge;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TOOLS
    // ═══════════════════════════════════════════════════════════════════════════

    @Tool(name = "graph_pagerank",
          description = "Compute PageRank for the knowledge graph, identifying the most important/influential nodes. "
                  + "Higher scores indicate more central nodes in the link structure. "
                  + "damping defaults to 0.85, maxIterations to 100. "
                  + "Returns the top-K ranked nodes with their scores.")
    public Map<String, Object> pageRank(PageRankInput input) {
        log.info("PageRank: factSheet={}", input.factSheetId());

        try {
            double damping = input.damping() != null ? input.damping() : 0.85;
            int maxIter = input.maxIterations() != null && input.maxIterations() > 0
                    ? input.maxIterations() : 100;
            int topK = input.topK() != null && input.topK() > 0 ? Math.min(input.topK(), 100) : 20;

            UnifiedGraph unified = unifiedGraph(input.factSheetId());
            Map<String, Double> scores = unified != null
                    ? algorithmService.pageRankGraph(unified, damping, maxIter, 1e-6)
                    : algorithmService.pageRank(input.factSheetId(), damping, maxIter, 1e-6);

            return buildRankedResult("pagerank", scores, topK, unified);

        } catch (Exception e) {
            log.error("PageRank failed: {}", e.getMessage(), e);
            return Map.of("error", "PageRank failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_degree_centrality",
          description = "Compute degree centrality for graph nodes. "
                  + "Type: 'in' (incoming edges), 'out' (outgoing edges), or 'total' (default, both). "
                  + "Higher scores mean more connections. "
                  + "Returns top-K nodes ranked by degree.")
    public Map<String, Object> degreeCentrality(DegreeCentralityInput input) {
        log.info("Degree centrality: factSheet={} type={}", input.factSheetId(), input.type());

        try {
            DegreeCentrality.Type degreeType;
            if ("in".equalsIgnoreCase(input.type())) {
                degreeType = DegreeCentrality.Type.IN;
            } else if ("out".equalsIgnoreCase(input.type())) {
                degreeType = DegreeCentrality.Type.OUT;
            } else {
                degreeType = DegreeCentrality.Type.TOTAL;
            }

            int topK = input.topK() != null && input.topK() > 0 ? Math.min(input.topK(), 100) : 20;
            UnifiedGraph unified = unifiedGraph(input.factSheetId());
            Map<String, Double> scores = unified != null
                    ? algorithmService.degreeCentralityGraph(unified, degreeType)
                    : algorithmService.degreeCentrality(input.factSheetId(), degreeType);

            return buildRankedResult("degree_centrality_" + degreeType.name().toLowerCase(),
                    scores, topK, unified);

        } catch (Exception e) {
            log.error("Degree centrality failed: {}", e.getMessage(), e);
            return Map.of("error", "Degree centrality failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_betweenness_centrality",
          description = "Compute betweenness centrality, identifying bridge nodes that connect different parts "
                  + "of the graph. High betweenness means the node sits on many shortest paths between other nodes. "
                  + "sampleSize controls accuracy vs speed (higher = more accurate but slower).")
    public Map<String, Object> betweennessCentrality(BetweennessCentralityInput input) {
        log.info("Betweenness centrality: factSheet={}", input.factSheetId());

        try {
            int sampleSize = input.sampleSize() != null && input.sampleSize() > 0
                    ? input.sampleSize() : 100;
            int topK = input.topK() != null && input.topK() > 0 ? Math.min(input.topK(), 100) : 20;

            UnifiedGraph unified = unifiedGraph(input.factSheetId());
            Map<String, Double> scores = unified != null
                    ? algorithmService.betweennessCentralityGraph(unified, sampleSize, 42L)
                    : algorithmService.betweennessCentrality(input.factSheetId(), sampleSize, 42L);

            return buildRankedResult("betweenness_centrality", scores, topK, unified);

        } catch (Exception e) {
            log.error("Betweenness centrality failed: {}", e.getMessage(), e);
            return Map.of("error", "Betweenness centrality failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_shortest_path",
          description = "Find the shortest path between two nodes in the knowledge graph. "
                  + "Set weighted=true to use Dijkstra (considers edge weights), "
                  + "or false (default) for BFS (unweighted, fewest hops). "
                  + "Returns the ordered path with node details and total distance.")
    public Map<String, Object> shortestPath(ShortestPathInput input) {
        if (input.fromNodeId() == null || input.toNodeId() == null) {
            return Map.of("error", "Both fromNodeId and toNodeId are required");
        }

        log.info("Shortest path: {} -> {} weighted={}", input.fromNodeId(), input.toNodeId(), input.weighted());

        try {
            boolean weighted = input.weighted() != null && input.weighted();
            UnifiedGraph unified = unifiedGraph(input.factSheetId());
            ShortestPathAlgorithm.PathResult pathResult = unified != null
                    ? algorithmService.shortestPathGraph(unified, input.fromNodeId(), input.toNodeId(), weighted)
                    : algorithmService.shortestPath(input.factSheetId(), input.fromNodeId(), input.toNodeId(), weighted);

            if (pathResult.path().isEmpty()) {
                return Map.of(
                        "found", false,
                        "fromNodeId", input.fromNodeId(),
                        "toNodeId", input.toNodeId(),
                        "message", "No path exists between these nodes"
                );
            }

            Map<String, GraphNode> liveNodes = unified == null
                    ? graphService.getNodesByIds(pathResult.path()).stream()
                            .collect(Collectors.toMap(GraphNode::getNodeId, n -> n, (a, b) -> a))
                    : Map.of();
            List<Map<String, Object>> pathNodes = pathResult.path().stream()
                    .map(id -> nodeDetails(id, unified, liveNodes))
                    .collect(Collectors.toList());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("found", true);
            result.put("algorithm", weighted ? "dijkstra" : "bfs");
            result.put("distance", pathResult.length());
            result.put("hopCount", pathResult.path().size() - 1);
            result.put("path", pathNodes);
            return result;

        } catch (Exception e) {
            log.error("Shortest path failed: {}", e.getMessage(), e);
            return Map.of("error", "Shortest path failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_node_similarity",
          description = "Compute Jaccard similarity between two specific nodes. "
                  + "Jaccard similarity measures the overlap between their neighbor sets. "
                  + "Returns a value between 0.0 (no shared neighbors) and 1.0 (identical neighborhoods).")
    public Map<String, Object> nodeSimilarity(NodeSimilarityInput input) {
        if (input.nodeIdA() == null || input.nodeIdB() == null) {
            return Map.of("error", "Both nodeIdA and nodeIdB are required");
        }

        try {
            UnifiedGraph unified = unifiedGraph(input.factSheetId());
            double similarity = unified != null
                    ? algorithmService.jaccardSimilarityGraph(unified, input.nodeIdA(), input.nodeIdB())
                    : algorithmService.jaccardSimilarity(input.factSheetId(), input.nodeIdA(), input.nodeIdB());

            Map<String, GraphNode> liveNodes = unified == null
                    ? graphService.getNodesByIds(List.of(input.nodeIdA(), input.nodeIdB())).stream()
                            .collect(Collectors.toMap(GraphNode::getNodeId, n -> n, (a, b) -> a))
                    : Map.of();
            Map<String, Object> nodeA = nodeDetails(input.nodeIdA(), unified, liveNodes);
            Map<String, Object> nodeB = nodeDetails(input.nodeIdB(), unified, liveNodes);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("nodeIdA", input.nodeIdA());
            result.put("titleA", nodeA.get("title"));
            result.put("nodeIdB", input.nodeIdB());
            result.put("titleB", nodeB.get("title"));
            result.put("jaccardSimilarity", similarity);
            result.put("interpretation", similarity > 0.5 ? "highly similar neighborhoods"
                    : similarity > 0.2 ? "moderate overlap" : "low similarity");
            return result;

        } catch (Exception e) {
            log.error("Node similarity failed: {}", e.getMessage(), e);
            return Map.of("error", "Failed: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private UnifiedGraph unifiedGraph(Long factSheetId) {
        if (unifiedGraphBridge == null || factSheetId == null) {
            return null;
        }
        try {
            return unifiedGraphBridge.export(factSheetId);
        } catch (RuntimeException ex) {
            log.warn("Falling back to live graph algorithms; unified export failed for factSheet={}", factSheetId, ex);
            return null;
        }
    }

    private Map<String, Object> buildRankedResult(String algorithm, Map<String, Double> scores,
                                                  int topK, UnifiedGraph unified) {
        List<Map.Entry<String, Double>> sorted = scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .collect(Collectors.toList());

        List<String> topIds = sorted.stream().map(Map.Entry::getKey).collect(Collectors.toList());
        Map<String, GraphNode> liveNodes = unified == null
                ? graphService.getNodesByIds(topIds).stream()
                        .collect(Collectors.toMap(GraphNode::getNodeId, n -> n, (a, b) -> a))
                : Map.of();

        List<Map<String, Object>> ranked = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            Map.Entry<String, Double> e = sorted.get(i);
            Map<String, Object> m = nodeDetails(e.getKey(), unified, liveNodes);
            m.put("rank", i + 1);
            m.put("score", e.getValue());
            ranked.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("algorithm", algorithm);
        result.put("source", unified != null ? "unified_graph" : "knowledge_graph_service");
        result.put("totalNodes", scores.size());
        result.put("topK", ranked.size());
        result.put("rankings", ranked);
        return result;
    }

    private Map<String, Object> nodeDetails(String nodeId, UnifiedGraph unified, Map<String, GraphNode> liveNodes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodeId", nodeId);
        if (unified != null) {
            Optional<GraphEntity> entity = unified.entity(nodeId);
            if (entity.isPresent()) {
                GraphEntity e = entity.get();
                m.put("title", e.label() == null || e.label().isBlank() ? "Unknown" : e.label());
                m.put("type", e.type() == null || e.type().isBlank() ? "UNKNOWN" : e.type());
                return m;
            }
        }
        GraphNode n = liveNodes.get(nodeId);
        m.put("title", n != null && n.getTitle() != null ? n.getTitle() : "Unknown");
        m.put("type", n != null ? n.getNodeType().name() : "UNKNOWN");
        return m;
    }
}
