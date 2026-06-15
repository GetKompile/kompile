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

import ai.kompile.core.graphrag.GraphRagService;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.query.GraphRagQuery;
import ai.kompile.core.graphrag.query.GraphRagResult;
import ai.kompile.core.graphrag.query.SearchType;
import ai.kompile.graph.algorithms.service.GraphAlgorithmService;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP tools for graph traversal: BFS exploration, ego networks,
 * n-hop neighborhoods, and node edge listing.
 */
@Component
@ConditionalOnBean(KnowledgeGraphService.class)
public class GraphTraversalTool {

    private static final Logger log = LoggerFactory.getLogger(GraphTraversalTool.class);

    private final KnowledgeGraphService graphService;
    private final GraphAlgorithmService algorithmService;
    private final GraphNodeRepository nodeRepository;
    private final GraphRagService graphRagService;

    // ═══════════════════════════════════════════════════════════════════════════
    // INPUT RECORDS
    // ═══════════════════════════════════════════════════════════════════════════

    public record BfsTraversalInput(
            String startNodeId,
            Long factSheetId,
            Integer maxDepth
    ) {}

    public record EgoNetworkInput(
            String nodeId,
            Integer radius,
            Integer maxNodes
    ) {}

    public record NodeEdgesInput(
            String nodeId,
            String direction,
            String edgeType,
            Integer maxResults
    ) {}

    public record NeighborhoodInput(
            String nodeId,
            Integer hops,
            String nodeType,
            Integer maxResults
    ) {}

    public record GraphVisualizationInput(
            String rootNodeId,
            Integer depth,
            Integer maxNodes
    ) {}

    public record HybridGraphSearchInput(
            String query,
            String searchType,
            Integer maxResults,
            Integer hopDepth,
            Double vectorWeight,
            Integer maxTraversalNodes,
            Long factSheetId,
            Boolean includeCommunities
    ) {}

    public record ShortestPathInput(
            String fromNodeId,
            String toNodeId,
            Integer maxDepth
    ) {}

    @Autowired
    public GraphTraversalTool(KnowledgeGraphService graphService,
                              @Autowired(required = false) GraphAlgorithmService algorithmService,
                              GraphNodeRepository nodeRepository,
                              @Autowired(required = false) GraphRagService graphRagService) {
        this.graphService = graphService;
        this.algorithmService = algorithmService;
        this.nodeRepository = nodeRepository;
        this.graphRagService = graphRagService;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TOOLS
    // ═══════════════════════════════════════════════════════════════════════════

    @Tool(name = "graph_bfs_traverse",
          description = "Perform breadth-first traversal from a starting node, returning nodes organized by depth level. "
                  + "Level 0 is the start node, level 1 is its direct neighbors, etc. "
                  + "maxDepth controls how far to traverse (default 3, max 5). "
                  + "Useful for understanding the structure radiating from a node.")
    public Map<String, Object> bfsTraversal(BfsTraversalInput input) {
        if (input.startNodeId() == null || input.startNodeId().isBlank()) {
            return Map.of("error", "startNodeId is required");
        }

        int maxDepth = input.maxDepth() != null && input.maxDepth() > 0
                ? Math.min(input.maxDepth(), 5) : 3;

        try {
            if (algorithmService == null) {
                return Map.of("error", "Graph algorithm service not available");
            }

            Map<Integer, List<String>> levels = algorithmService.bfsTraversal(
                    input.factSheetId(), input.startNodeId(), maxDepth);

            // Resolve all node titles
            Set<String> allNodeIds = new HashSet<>();
            levels.values().forEach(allNodeIds::addAll);
            Map<String, GraphNode> nodeMap = nodeRepository.findByNodeIdIn(new ArrayList<>(allNodeIds))
                    .stream()
                    .collect(Collectors.toMap(GraphNode::getNodeId, n -> n, (a, b) -> a));

            Map<String, Object> levelDetails = new LinkedHashMap<>();
            int totalNodes = 0;
            for (Map.Entry<Integer, List<String>> entry : levels.entrySet()) {
                List<Map<String, Object>> levelNodes = entry.getValue().stream()
                        .map(id -> {
                            GraphNode n = nodeMap.get(id);
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("nodeId", id);
                            m.put("title", n != null && n.getTitle() != null ? n.getTitle() : "Unknown");
                            m.put("type", n != null ? n.getNodeType().name() : "UNKNOWN");
                            return m;
                        })
                        .collect(Collectors.toList());
                levelDetails.put("level_" + entry.getKey(), levelNodes);
                totalNodes += levelNodes.size();
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("startNodeId", input.startNodeId());
            result.put("maxDepth", maxDepth);
            result.put("levelsReached", levels.size());
            result.put("totalNodes", totalNodes);
            result.put("levels", levelDetails);
            return result;

        } catch (Exception e) {
            log.error("BFS traversal failed: {}", e.getMessage(), e);
            return Map.of("error", "BFS traversal failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_ego_network",
          description = "Get the ego network around a node: the node itself plus all nodes within a given radius. "
                  + "An ego network is a subgraph centered on one node showing its local neighborhood. "
                  + "Returns nodes and the edges between them, suitable for visualization. "
                  + "radius defaults to 1 (direct neighbors), max 3.")
    public Map<String, Object> egoNetwork(EgoNetworkInput input) {
        if (input.nodeId() == null || input.nodeId().isBlank()) {
            return Map.of("error", "nodeId is required");
        }

        int radius = input.radius() != null && input.radius() > 0 ? Math.min(input.radius(), 3) : 1;
        int maxNodes = input.maxNodes() != null && input.maxNodes() > 0
                ? Math.min(input.maxNodes(), 100) : 50;

        try {
            List<GraphNode> connected = graphService.getConnectedNodes(input.nodeId(), radius);
            if (connected.size() > maxNodes) {
                connected = connected.subList(0, maxNodes);
            }

            Set<String> nodeIds = connected.stream()
                    .map(GraphNode::getNodeId)
                    .collect(Collectors.toSet());
            nodeIds.add(input.nodeId());

            List<Map<String, Object>> nodes = connected.stream()
                    .map(n -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("nodeId", n.getNodeId());
                        m.put("title", n.getTitle() != null ? n.getTitle() : "Untitled");
                        m.put("type", n.getNodeType().name());
                        m.put("connections", n.getEdgeCount());
                        return m;
                    })
                    .collect(Collectors.toList());

            // Collect edges within the ego network
            List<Map<String, Object>> edges = new ArrayList<>();
            for (String nid : nodeIds) {
                List<GraphEdge> nodeEdges = graphService.getEdgesForNode(nid);
                for (GraphEdge e : nodeEdges) {
                    String srcId = e.getSourceNode() != null ? e.getSourceNode().getNodeId() : null;
                    String tgtId = e.getTargetNode() != null ? e.getTargetNode().getNodeId() : null;
                    if (srcId != null && tgtId != null && nodeIds.contains(srcId) && nodeIds.contains(tgtId)) {
                        Map<String, Object> em = new LinkedHashMap<>();
                        em.put("edgeId", e.getEdgeId());
                        em.put("source", srcId);
                        em.put("target", tgtId);
                        em.put("type", e.getEdgeType().name());
                        em.put("weight", e.getWeight());
                        edges.add(em);
                    }
                }
            }

            // Deduplicate edges by ID
            Map<String, Map<String, Object>> uniqueEdges = new LinkedHashMap<>();
            edges.forEach(e -> uniqueEdges.putIfAbsent((String) e.get("edgeId"), e));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("centerNodeId", input.nodeId());
            result.put("radius", radius);
            result.put("nodeCount", nodes.size());
            result.put("edgeCount", uniqueEdges.size());
            result.put("nodes", nodes);
            result.put("edges", new ArrayList<>(uniqueEdges.values()));
            return result;

        } catch (Exception e) {
            log.error("Ego network failed: {}", e.getMessage(), e);
            return Map.of("error", "Ego network failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_node_edges",
          description = "List all edges connected to a specific node. "
                  + "Filter by direction ('in', 'out', or 'all' (default)) "
                  + "and optionally by edgeType. "
                  + "Returns edges with connected node details, type, and weight.")
    public Map<String, Object> nodeEdges(NodeEdgesInput input) {
        if (input.nodeId() == null || input.nodeId().isBlank()) {
            return Map.of("error", "nodeId is required", "edges", List.of());
        }

        int limit = GraphSearchTool.clampLimit(input.maxResults(), 50);
        var edgeType = GraphSearchTool.parseEdgeType(input.edgeType());

        try {
            List<GraphEdge> edges;
            if (edgeType != null) {
                edges = graphService.getEdgesByType(input.nodeId(), edgeType);
            } else {
                edges = graphService.getEdgesForNode(input.nodeId());
            }

            String direction = input.direction() != null ? input.direction().toLowerCase() : "all";

            List<Map<String, Object>> edgeList = edges.stream()
                    .filter(e -> {
                        if ("all".equals(direction)) return true;
                        String srcId = e.getSourceNode() != null ? e.getSourceNode().getNodeId() : null;
                        if ("out".equals(direction)) return input.nodeId().equals(srcId);
                        return !input.nodeId().equals(srcId); // "in"
                    })
                    .limit(limit)
                    .map(e -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("edgeId", e.getEdgeId());
                        m.put("edgeType", e.getEdgeType().name());
                        m.put("weight", e.getWeight());
                        m.put("description", GraphSearchTool.truncate(e.getDescription(), 150));

                        // Show the "other" node
                        String srcId = e.getSourceNode() != null ? e.getSourceNode().getNodeId() : null;
                        if (input.nodeId().equals(srcId) && e.getTargetNode() != null) {
                            m.put("direction", "outgoing");
                            m.put("connectedNodeId", e.getTargetNode().getNodeId());
                            m.put("connectedTitle", e.getTargetNode().getTitle());
                        } else if (e.getSourceNode() != null) {
                            m.put("direction", "incoming");
                            m.put("connectedNodeId", e.getSourceNode().getNodeId());
                            m.put("connectedTitle", e.getSourceNode().getTitle());
                        }
                        return m;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("nodeId", input.nodeId());
            result.put("direction", direction);
            result.put("edgeCount", edgeList.size());
            result.put("edges", edgeList);
            return result;

        } catch (Exception e) {
            log.error("Node edges failed: {}", e.getMessage(), e);
            return Map.of("error", "Failed: " + e.getMessage(), "edges", List.of());
        }
    }

    @Tool(name = "graph_neighborhood",
          description = "Get all unique nodes within N hops of a given node. "
                  + "Unlike BFS traversal, this returns a flat list of neighbor nodes "
                  + "optionally filtered by nodeType. "
                  + "Useful for finding all entities, documents, or snippets near a node.")
    public Map<String, Object> neighborhood(NeighborhoodInput input) {
        if (input.nodeId() == null || input.nodeId().isBlank()) {
            return Map.of("error", "nodeId is required", "neighbors", List.of());
        }

        int hops = input.hops() != null && input.hops() > 0 ? Math.min(input.hops(), 4) : 2;
        int limit = GraphSearchTool.clampLimit(input.maxResults(), 50);
        var nodeType = GraphSearchTool.parseNodeLevel(input.nodeType());

        try {
            List<GraphNode> connected = graphService.getConnectedNodes(input.nodeId(), hops);

            List<Map<String, Object>> neighbors = connected.stream()
                    .filter(n -> !n.getNodeId().equals(input.nodeId()))
                    .filter(n -> nodeType == null || n.getNodeType() == nodeType)
                    .limit(limit)
                    .map(n -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("nodeId", n.getNodeId());
                        m.put("title", n.getTitle() != null ? n.getTitle() : "Untitled");
                        m.put("type", n.getNodeType().name());
                        m.put("connections", n.getEdgeCount());
                        return m;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("nodeId", input.nodeId());
            result.put("hops", hops);
            if (nodeType != null) result.put("filteredType", nodeType.name());
            result.put("neighborCount", neighbors.size());
            result.put("neighbors", neighbors);
            return result;

        } catch (Exception e) {
            log.error("Neighborhood failed: {}", e.getMessage(), e);
            return Map.of("error", "Failed: " + e.getMessage(), "neighbors", List.of());
        }
    }

    @Tool(name = "graph_visualization_data",
          description = "Get graph data in a format ready for visualization (D3.js compatible). "
                  + "Returns nodes and edges as separate lists with properties. "
                  + "Optionally provide a rootNodeId to get a subgraph centered on that node, "
                  + "or omit it for the full graph overview.")
    public Map<String, Object> getVisualizationData(GraphVisualizationInput input) {
        int depth = input.depth() != null && input.depth() > 0 ? Math.min(input.depth(), 5) : 3;
        int maxNodes = input.maxNodes() != null && input.maxNodes() > 0
                ? Math.min(input.maxNodes(), 200) : 100;

        try {
            return graphService.getVisualizationData(input.rootNodeId(), depth, maxNodes);
        } catch (Exception e) {
            log.error("Visualization data failed: {}", e.getMessage(), e);
            return Map.of("error", "Failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_hybrid_search",
          description = "Perform a hybrid graph RAG search that combines vector similarity with graph traversal. "
                  + "First finds seed entities matching the query via vector/keyword search, then hops outward "
                  + "along relationships to collect richer context. Results from both channels are merged "
                  + "and re-ranked. searchType can be 'LOCAL' (entity-focused), 'GLOBAL' (community/PageRank), "
                  + "or 'HYBRID' (vector + traversal, default). vectorWeight (0.0-1.0) controls the blend "
                  + "between vector scores and graph proximity scores. hopDepth controls traversal depth (default 2).")
    public Map<String, Object> hybridGraphSearch(HybridGraphSearchInput input) {
        if (input.query() == null || input.query().isBlank()) {
            return Map.of("error", "query is required");
        }

        if (graphRagService == null) {
            return Map.of("error", "Graph RAG service not available");
        }

        try {
            SearchType type = SearchType.HYBRID;
            if (input.searchType() != null && !input.searchType().isBlank()) {
                try {
                    type = SearchType.valueOf(input.searchType().toUpperCase());
                } catch (IllegalArgumentException ignored) {
                    // fall through to HYBRID default
                }
            }

            GraphRagQuery query = GraphRagQuery.builder()
                    .query(input.query())
                    .searchType(type)
                    .k(input.maxResults() != null && input.maxResults() > 0
                            ? Math.min(input.maxResults(), 50) : 10)
                    .hopDepth(input.hopDepth() != null && input.hopDepth() > 0
                            ? Math.min(input.hopDepth(), 5) : 2)
                    .vectorWeight(input.vectorWeight() != null
                            ? Math.max(0.0, Math.min(1.0, input.vectorWeight())) : 0.5)
                    .maxTraversalNodes(input.maxTraversalNodes() != null && input.maxTraversalNodes() > 0
                            ? Math.min(input.maxTraversalNodes(), 200) : 50)
                    .factSheetId(input.factSheetId())
                    .includeCommunities(input.includeCommunities() != null
                            ? input.includeCommunities() : true)
                    .build();

            GraphRagResult ragResult = graphRagService.answerQuery(query);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("searchType", ragResult.getSearchType() != null
                    ? ragResult.getSearchType().name() : type.name());
            result.put("answer", ragResult.getAnswer());

            // Entities
            if (ragResult.getEntities() != null && !ragResult.getEntities().isEmpty()) {
                List<Map<String, Object>> entities = ragResult.getEntities().stream()
                        .limit(30)
                        .map(e -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("id", e.getId());
                            m.put("title", e.getTitle());
                            m.put("type", e.getType());
                            m.put("description", GraphSearchTool.truncate(e.getDescription(), 200));
                            return m;
                        })
                        .collect(Collectors.toList());
                result.put("entityCount", ragResult.getEntities().size());
                result.put("entities", entities);
            }

            // Relationships
            if (ragResult.getRelationships() != null && !ragResult.getRelationships().isEmpty()) {
                List<Map<String, Object>> rels = ragResult.getRelationships().stream()
                        .limit(30)
                        .map(r -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("source", r.getSource());
                            m.put("target", r.getTarget());
                            m.put("type", r.getType());
                            m.put("weight", r.getWeight());
                            m.put("description", GraphSearchTool.truncate(r.getDescription(), 150));
                            return m;
                        })
                        .collect(Collectors.toList());
                result.put("relationshipCount", ragResult.getRelationships().size());
                result.put("relationships", rels);
            }

            // Traversal metadata
            result.put("hopsPerformed", ragResult.getHopsPerformed());
            result.put("nodesVisited", ragResult.getNodesVisited());
            if (ragResult.getTraversalPaths() != null) {
                result.put("traversalPaths", ragResult.getTraversalPaths());
            }
            if (ragResult.getScoreBreakdown() != null) {
                result.put("scoreBreakdown", ragResult.getScoreBreakdown());
            }

            return result;

        } catch (Exception e) {
            log.error("Hybrid graph search failed: {}", e.getMessage(), e);
            return Map.of("error", "Hybrid graph search failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_shortest_path",
          description = "Find the shortest path between two nodes in the knowledge graph. "
                  + "Returns the ordered list of nodes along the path and their details. "
                  + "maxDepth controls maximum path length (default 5, max 10). "
                  + "Useful for understanding how two entities are connected.")
    public Map<String, Object> shortestPath(ShortestPathInput input) {
        if (input.fromNodeId() == null || input.fromNodeId().isBlank()) {
            return Map.of("error", "fromNodeId is required");
        }
        if (input.toNodeId() == null || input.toNodeId().isBlank()) {
            return Map.of("error", "toNodeId is required");
        }

        int maxDepth = input.maxDepth() != null && input.maxDepth() > 0
                ? Math.min(input.maxDepth(), 10) : 5;

        try {
            List<GraphNode> path = graphService.findShortestPath(
                    input.fromNodeId(), input.toNodeId(), maxDepth);

            if (path.isEmpty()) {
                return Map.of(
                        "fromNodeId", input.fromNodeId(),
                        "toNodeId", input.toNodeId(),
                        "found", false,
                        "message", "No path found within " + maxDepth + " hops"
                );
            }

            List<Map<String, Object>> pathNodes = path.stream()
                    .map(n -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("nodeId", n.getNodeId());
                        m.put("title", n.getTitle() != null ? n.getTitle() : "Untitled");
                        m.put("type", n.getNodeType().name());
                        return m;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("fromNodeId", input.fromNodeId());
            result.put("toNodeId", input.toNodeId());
            result.put("found", true);
            result.put("pathLength", path.size() - 1);
            result.put("path", pathNodes);
            return result;

        } catch (Exception e) {
            log.error("Shortest path failed: {}", e.getMessage(), e);
            return Map.of("error", "Shortest path failed: " + e.getMessage());
        }
    }
}
