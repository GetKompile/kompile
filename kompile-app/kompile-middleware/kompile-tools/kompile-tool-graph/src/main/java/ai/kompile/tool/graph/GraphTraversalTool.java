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
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.GraphProvenanceKeys;
import ai.kompile.knowledgegraph.domain.NodeLevel;
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
 * MCP tools for graph traversal: BFS exploration, ego networks,
 * n-hop neighborhoods, and node edge listing.
 */
@Component
@ConditionalOnBean(KnowledgeGraphService.class)
public class GraphTraversalTool {

    private static final Logger log = LoggerFactory.getLogger(GraphTraversalTool.class);

    private final KnowledgeGraphService graphService;
    private final GraphAlgorithmService algorithmService;
    private final GraphRagService graphRagService;
    private final UnifiedGraphBridge unifiedGraphBridge;

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
            Integer maxNodes,
            Long factSheetId
    ) {
        public EgoNetworkInput(String nodeId, Integer radius, Integer maxNodes) {
            this(nodeId, radius, maxNodes, null);
        }
    }

    public record NodeEdgesInput(
            String nodeId,
            String direction,
            String edgeType,
            Integer maxResults,
            Long factSheetId
    ) {
        public NodeEdgesInput(String nodeId, String direction, String edgeType, Integer maxResults) {
            this(nodeId, direction, edgeType, maxResults, null);
        }
    }

    public record NeighborhoodInput(
            String nodeId,
            Integer hops,
            String nodeType,
            Integer maxResults,
            Long factSheetId
    ) {
        public NeighborhoodInput(String nodeId, Integer hops, String nodeType, Integer maxResults) {
            this(nodeId, hops, nodeType, maxResults, null);
        }
    }

    public record GraphVisualizationInput(
            String rootNodeId,
            Integer depth,
            Integer maxNodes,
            Long factSheetId
    ) {
        public GraphVisualizationInput(String rootNodeId, Integer depth, Integer maxNodes) {
            this(rootNodeId, depth, maxNodes, null);
        }
    }

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
            Integer maxDepth,
            Long factSheetId
    ) {
        public ShortestPathInput(String fromNodeId, String toNodeId, Integer maxDepth) {
            this(fromNodeId, toNodeId, maxDepth, null);
        }
    }

    public GraphTraversalTool(KnowledgeGraphService graphService,
                              GraphAlgorithmService algorithmService,
                              GraphRagService graphRagService) {
        this(graphService, algorithmService, graphRagService, null);
    }

    @Autowired
    public GraphTraversalTool(KnowledgeGraphService graphService,
                              @Autowired(required = false) GraphAlgorithmService algorithmService,
                              @Autowired(required = false) GraphRagService graphRagService,
                              @org.springframework.lang.Nullable UnifiedGraphBridge unifiedGraphBridge) {
        this.graphService = graphService;
        this.algorithmService = algorithmService;
        this.graphRagService = graphRagService;
        this.unifiedGraphBridge = unifiedGraphBridge;
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

            UnifiedGraph unified = unifiedGraph(input.factSheetId());
            Map<Integer, List<String>> levels = unified != null
                    ? algorithmService.bfsTraversalGraph(unified, input.startNodeId(), maxDepth)
                    : algorithmService.bfsTraversal(input.factSheetId(), input.startNodeId(), maxDepth);

            Set<String> allNodeIds = new HashSet<>();
            levels.values().forEach(allNodeIds::addAll);
            Map<String, GraphNode> nodeMap = unified == null
                    ? graphService.getNodesByIds(new ArrayList<>(allNodeIds)).stream()
                            .collect(Collectors.toMap(GraphNode::getNodeId, n -> n, (a, b) -> a))
                    : Map.of();

            Map<String, Object> levelDetails = new LinkedHashMap<>();
            int totalNodes = 0;
            for (Map.Entry<Integer, List<String>> entry : levels.entrySet()) {
                List<Map<String, Object>> levelNodes = entry.getValue().stream()
                        .map(id -> nodeDetails(id, unified, nodeMap))
                        .collect(Collectors.toList());
                levelDetails.put("level_" + entry.getKey(), levelNodes);
                totalNodes += levelNodes.size();
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("startNodeId", input.startNodeId());
            result.put("source", unified != null ? "unified_graph" : "knowledge_graph_service");
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
            UnifiedGraph unified = unifiedGraph(input.factSheetId());
            if (unified != null) {
                return unifiedEgoNetwork(input.nodeId(), radius, maxNodes, unified);
            }

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
            UnifiedGraph unified = unifiedGraph(input.factSheetId());
            if (unified != null) {
                return unifiedNodeEdges(input.nodeId(), input.direction(), input.edgeType(), limit, unified);
            }

            List<GraphEdge> edges;
            if (edgeType != null) {
                edges = graphService.getEdgesByType(input.nodeId(), edgeType);
            } else {
                edges = graphService.getEdgesForNode(input.nodeId());
            }

            String direction = input.direction() != null ? input.direction().toLowerCase() : "all";

            List<GraphEdge> selectedEdges = edges.stream()
                    .filter(e -> {
                        if ("all".equals(direction)) return true;
                        String srcId = e.getSourceNodeId();
                        if ("out".equals(direction)) return input.nodeId().equals(srcId);
                        return !input.nodeId().equals(srcId); // "in"
                    })
                    .limit(limit)
                    .collect(Collectors.toList());

            Set<String> connectedNodeIds = new LinkedHashSet<>();
            for (GraphEdge e : selectedEdges) {
                if (input.nodeId().equals(e.getSourceNodeId()) && e.getTargetNodeId() != null) {
                    connectedNodeIds.add(e.getTargetNodeId());
                } else if (e.getSourceNodeId() != null) {
                    connectedNodeIds.add(e.getSourceNodeId());
                }
            }
            Map<String, GraphNode> connectedNodes = graphService.getNodesByIds(new ArrayList<>(connectedNodeIds)).stream()
                    .filter(Objects::nonNull)
                    .filter(n -> n.getNodeId() != null)
                    .collect(Collectors.toMap(GraphNode::getNodeId, n -> n, (first, ignored) -> first));

            List<Map<String, Object>> edgeList = selectedEdges.stream()
                    .map(e -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("edgeId", e.getEdgeId());
                        m.put("edgeType", e.getEdgeType().name());
                        m.put("weight", e.getWeight());
                        m.put("description", GraphSearchTool.truncate(e.getDescription(), 150));

                        // Show the "other" node. Titles resolve through the store when the edge
                        // didn't embed a real node (store-loaded edges carry ids only and
                        // getTargetNode() synthesizes a hollow, title-less node).
                        if (input.nodeId().equals(e.getSourceNodeId()) && e.getTargetNodeId() != null) {
                            m.put("direction", "outgoing");
                            m.put("connectedNodeId", e.getTargetNodeId());
                            m.put("connectedTitle", connectedTitle(e.getTargetNodeId(), e.getTargetNode(), connectedNodes));
                        } else if (e.getSourceNodeId() != null) {
                            m.put("direction", "incoming");
                            m.put("connectedNodeId", e.getSourceNodeId());
                            m.put("connectedTitle", connectedTitle(e.getSourceNodeId(), e.getSourceNode(), connectedNodes));
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
            UnifiedGraph unified = unifiedGraph(input.factSheetId());
            if (unified != null) {
                return unifiedNeighborhood(input.nodeId(), hops, nodeType, limit, unified);
            }

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
            UnifiedGraph unified = unifiedGraph(input.factSheetId());
            if (unified != null) {
                return unifiedVisualizationData(input.rootNodeId(), depth, maxNodes, unified);
            }
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
                            if (e.getConfidence() != null) {
                                m.put("confidence", e.getConfidence());
                            }
                            if (e.getTextUnits() != null && !e.getTextUnits().isEmpty()) {
                                m.put("textUnits", e.getTextUnits());
                            }
                            Map<String, Object> entityMeta = e.getMetadata();
                            if (entityMeta != null && !entityMeta.isEmpty()) {
                                Map<String, Object> prov = new LinkedHashMap<>();
                                for (String key : GraphProvenanceKeys.ALL) {
                                    Object val = entityMeta.get(key);
                                    if (val != null) {
                                        prov.put(key.substring(1), val);
                                    }
                                }
                                if (!prov.isEmpty()) {
                                    m.put("provenance", prov);
                                }
                            }
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

    @Tool(name = "graph_connection_path",
          description = "Find the shortest unweighted path between two nodes in the knowledge graph. "
                  + "Returns the ordered list of nodes along the path and their details. "
                  + "maxDepth controls maximum path length (default 5, max 10). "
                  + "Useful for understanding how two entities are connected. "
                  + "For weighted/Dijkstra paths use graph_shortest_path.")
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
            UnifiedGraph unified = unifiedGraph(input.factSheetId());
            if (unified != null) {
                return unifiedShortestPath(input.fromNodeId(), input.toNodeId(), maxDepth, unified);
            }

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

            List<Map<String, Object>> pathNodes = new ArrayList<>();
            for (int idx = 0; idx < path.size(); idx++) {
                GraphNode n = path.get(idx);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("nodeId", n.getNodeId());
                m.put("title", n.getTitle() != null ? n.getTitle() : "Untitled");
                m.put("type", n.getNodeType().name());
                if (idx > 0) {
                    GraphNode prev = path.get(idx - 1);
                    try {
                        GraphEdge pathEdge = graphService.findEdgeBetweenNodes(
                                prev.getNodeId(), n.getNodeId());
                        if (pathEdge == null) {
                            Optional<GraphEdge> opt = graphService.findEdgeBetweenNodesBidirectional(
                                    prev.getNodeId(), n.getNodeId());
                            pathEdge = opt.orElse(null);
                        }
                        if (pathEdge != null) {
                            Map<String, Object> em = new LinkedHashMap<>();
                            em.put("edgeType", pathEdge.getEdgeType().name());
                            em.put("weight", pathEdge.getWeight());
                            em.put("description", GraphSearchTool.truncate(pathEdge.getDescription(), 150));
                            m.put("incomingEdge", em);
                        }
                    } catch (Exception ignored) {
                        // Edge lookup is best-effort; path node still included without edge detail
                    }
                }
                pathNodes.add(m);
            }

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

    private Map<String, Object> unifiedEgoNetwork(String nodeId, int radius, int maxNodes, UnifiedGraph unified) {
        Set<String> nodeIds = reachableNodeIds(unified, nodeId, radius, maxNodes, true);
        List<Map<String, Object>> nodes = nodeIds.stream()
                .map(unified::entity)
                .flatMap(Optional::stream)
                .map(entity -> unifiedNodeSummary(entity, unified))
                .collect(Collectors.toList());
        List<Map<String, Object>> edges = relationsWithin(unified, nodeIds).stream()
                .map(this::unifiedEdgeSummary)
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("centerNodeId", nodeId);
        result.put("source", "unified_graph");
        result.put("radius", radius);
        result.put("nodeCount", nodes.size());
        result.put("edgeCount", edges.size());
        result.put("nodes", nodes);
        result.put("edges", edges);
        return result;
    }

    private Map<String, Object> unifiedNodeEdges(String nodeId,
                                                String directionInput,
                                                String edgeTypeInput,
                                                int limit,
                                                UnifiedGraph unified) {
        String direction = directionInput != null ? directionInput.toLowerCase(Locale.ROOT) : "all";
        var edgeType = GraphSearchTool.parseEdgeType(edgeTypeInput);
        List<Map<String, Object>> edgeList = incidentRelations(unified, nodeId).stream()
                .filter(r -> edgeType == null || edgeType.name().equalsIgnoreCase(r.type()))
                .filter(r -> {
                    if ("all".equals(direction)) return true;
                    if ("out".equals(direction)) return nodeId.equals(r.sourceId());
                    return nodeId.equals(r.targetId());
                })
                .limit(limit)
                .map(r -> unifiedIncidentEdge(nodeId, r, unified))
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodeId", nodeId);
        result.put("source", "unified_graph");
        result.put("direction", direction);
        result.put("edgeCount", edgeList.size());
        result.put("edges", edgeList);
        return result;
    }

    private Map<String, Object> unifiedNeighborhood(String nodeId,
                                                    int hops,
                                                    NodeLevel nodeType,
                                                    int limit,
                                                    UnifiedGraph unified) {
        Set<String> nodeIds = reachableNodeIds(unified, nodeId, hops, limit + 1, true);
        List<Map<String, Object>> neighbors = nodeIds.stream()
                .filter(id -> !nodeId.equals(id))
                .map(unified::entity)
                .flatMap(Optional::stream)
                .filter(entity -> matchesNodeType(entity, nodeType))
                .limit(limit)
                .map(entity -> unifiedNodeSummary(entity, unified))
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodeId", nodeId);
        result.put("source", "unified_graph");
        result.put("hops", hops);
        if (nodeType != null) result.put("filteredType", nodeType.name());
        result.put("neighborCount", neighbors.size());
        result.put("neighbors", neighbors);
        return result;
    }

    private Map<String, Object> unifiedVisualizationData(String rootNodeId,
                                                         int depth,
                                                         int maxNodes,
                                                         UnifiedGraph unified) {
        Set<String> nodeIds;
        if (rootNodeId != null && !rootNodeId.isBlank()) {
            nodeIds = reachableNodeIds(unified, rootNodeId, depth, maxNodes, true);
        } else {
            nodeIds = unified.entities().stream()
                    .limit(maxNodes)
                    .map(GraphEntity::id)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        List<Map<String, Object>> nodes = nodeIds.stream()
                .map(unified::entity)
                .flatMap(Optional::stream)
                .map(entity -> {
                    Map<String, Object> m = unifiedNodeSummary(entity, unified);
                    m.put("id", entity.id());
                    m.put("label", entity.label() == null || entity.label().isBlank() ? "Untitled" : entity.label());
                    return m;
                })
                .collect(Collectors.toList());
        List<Map<String, Object>> edges = relationsWithin(unified, nodeIds).stream()
                .map(this::unifiedEdgeSummary)
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", "unified_graph");
        result.put("rootNodeId", rootNodeId);
        result.put("depth", depth);
        result.put("nodeCount", nodes.size());
        result.put("edgeCount", edges.size());
        result.put("nodes", nodes);
        result.put("edges", edges);
        return result;
    }

    private Map<String, Object> unifiedShortestPath(String fromNodeId,
                                                    String toNodeId,
                                                    int maxDepth,
                                                    UnifiedGraph unified) {
        List<String> path = shortestPathIds(unified, fromNodeId, toNodeId, maxDepth);
        if (path.isEmpty()) {
            return Map.of(
                    "fromNodeId", fromNodeId,
                    "toNodeId", toNodeId,
                    "source", "unified_graph",
                    "found", false,
                    "message", "No path found within " + maxDepth + " hops"
            );
        }

        List<Map<String, Object>> pathNodes = new ArrayList<>();
        for (int idx = 0; idx < path.size(); idx++) {
            String id = path.get(idx);
            Map<String, Object> m = nodeDetails(id, unified, Map.of());
            if (idx > 0) {
                relationBetween(unified, path.get(idx - 1), id)
                        .map(this::unifiedIncomingEdge)
                        .ifPresent(edge -> m.put("incomingEdge", edge));
            }
            pathNodes.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fromNodeId", fromNodeId);
        result.put("toNodeId", toNodeId);
        result.put("source", "unified_graph");
        result.put("found", true);
        result.put("pathLength", path.size() - 1);
        result.put("path", pathNodes);
        return result;
    }

    private LinkedHashSet<String> reachableNodeIds(UnifiedGraph unified,
                                                   String startNodeId,
                                                   int maxDepth,
                                                   int maxNodes,
                                                   boolean includeStart) {
        LinkedHashSet<String> visited = new LinkedHashSet<>();
        if (startNodeId == null || startNodeId.isBlank() || maxNodes <= 0) {
            return visited;
        }
        if (includeStart && unified.entity(startNodeId).isPresent()) {
            visited.add(startNodeId);
        }
        Set<String> frontier = new LinkedHashSet<>();
        frontier.add(startNodeId);
        for (int depth = 0; depth < maxDepth && !frontier.isEmpty() && visited.size() < maxNodes; depth++) {
            Set<String> next = new LinkedHashSet<>();
            for (String id : frontier) {
                for (GraphRelation relation : incidentRelations(unified, id)) {
                    String other = id.equals(relation.sourceId()) ? relation.targetId() : relation.sourceId();
                    if (other == null || visited.contains(other) || unified.entity(other).isEmpty()) {
                        continue;
                    }
                    visited.add(other);
                    next.add(other);
                    if (visited.size() >= maxNodes) {
                        break;
                    }
                }
                if (visited.size() >= maxNodes) {
                    break;
                }
            }
            frontier = next;
        }
        return visited;
    }

    private List<String> shortestPathIds(UnifiedGraph unified, String fromNodeId, String toNodeId, int maxDepth) {
        if (unified.entity(fromNodeId).isEmpty() || unified.entity(toNodeId).isEmpty()) {
            return List.of();
        }
        Deque<List<String>> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(List.of(fromNodeId));
        visited.add(fromNodeId);
        while (!queue.isEmpty()) {
            List<String> path = queue.removeFirst();
            String current = path.get(path.size() - 1);
            if (current.equals(toNodeId)) {
                return path;
            }
            if (path.size() - 1 >= maxDepth) {
                continue;
            }
            for (GraphRelation relation : incidentRelations(unified, current)) {
                String next = current.equals(relation.sourceId()) ? relation.targetId() : relation.sourceId();
                if (next == null || !visited.add(next) || unified.entity(next).isEmpty()) {
                    continue;
                }
                List<String> nextPath = new ArrayList<>(path);
                nextPath.add(next);
                queue.addLast(nextPath);
            }
        }
        return List.of();
    }

    private List<GraphRelation> incidentRelations(UnifiedGraph unified, String nodeId) {
        return unified.relations().stream()
                .filter(r -> nodeId.equals(r.sourceId()) || nodeId.equals(r.targetId()))
                .collect(Collectors.toList());
    }

    private List<GraphRelation> relationsWithin(UnifiedGraph unified, Set<String> nodeIds) {
        Map<String, GraphRelation> unique = new LinkedHashMap<>();
        for (GraphRelation relation : unified.relations()) {
            if (nodeIds.contains(relation.sourceId()) && nodeIds.contains(relation.targetId())) {
                unique.putIfAbsent(relationKey(relation), relation);
            }
        }
        return new ArrayList<>(unique.values());
    }

    private Optional<GraphRelation> relationBetween(UnifiedGraph unified, String a, String b) {
        return unified.relations().stream()
                .filter(r -> (a.equals(r.sourceId()) && b.equals(r.targetId()))
                        || (a.equals(r.targetId()) && b.equals(r.sourceId())))
                .findFirst();
    }

    private Map<String, Object> unifiedNodeSummary(GraphEntity entity, UnifiedGraph unified) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodeId", entity.id());
        m.put("title", entity.label() == null || entity.label().isBlank() ? "Untitled" : entity.label());
        m.put("type", entity.type() == null || entity.type().isBlank() ? "UNKNOWN" : entity.type());
        m.put("connections", incidentRelations(unified, entity.id()).size());
        return m;
    }

    private Map<String, Object> unifiedIncidentEdge(String nodeId, GraphRelation relation, UnifiedGraph unified) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("edgeId", relationKey(relation));
        m.put("edgeType", relation.type() == null || relation.type().isBlank() ? "UNKNOWN" : relation.type());
        m.put("weight", relation.weight());
        m.put("description", GraphSearchTool.truncate(relation.stringAttribute("description"), 150));
        if (nodeId.equals(relation.sourceId())) {
            m.put("direction", "outgoing");
            m.put("connectedNodeId", relation.targetId());
            m.put("connectedTitle", unified.entity(relation.targetId()).map(GraphEntity::label).orElse(null));
        } else {
            m.put("direction", "incoming");
            m.put("connectedNodeId", relation.sourceId());
            m.put("connectedTitle", unified.entity(relation.sourceId()).map(GraphEntity::label).orElse(null));
        }
        return m;
    }

    private Map<String, Object> unifiedEdgeSummary(GraphRelation relation) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("edgeId", relationKey(relation));
        m.put("source", relation.sourceId());
        m.put("target", relation.targetId());
        m.put("type", relation.type() == null || relation.type().isBlank() ? "UNKNOWN" : relation.type());
        m.put("weight", relation.weight());
        return m;
    }

    private Map<String, Object> unifiedIncomingEdge(GraphRelation relation) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("edgeType", relation.type() == null || relation.type().isBlank() ? "UNKNOWN" : relation.type());
        m.put("weight", relation.weight());
        m.put("description", GraphSearchTool.truncate(relation.stringAttribute("description"), 150));
        return m;
    }

    private boolean matchesNodeType(GraphEntity entity, NodeLevel nodeType) {
        if (nodeType == null) {
            return true;
        }
        String expected = nodeType.name();
        return expected.equalsIgnoreCase(entity.type())
                || entity.typeMemberships().stream().anyMatch(expected::equalsIgnoreCase);
    }

    private String relationKey(GraphRelation relation) {
        if (relation.id() != null && !relation.id().isBlank()) {
            return relation.id();
        }
        return relation.sourceId() + "->" + relation.targetId() + ":" + relation.type();
    }

    private UnifiedGraph unifiedGraph(Long factSheetId) {
        if (unifiedGraphBridge == null || factSheetId == null) {
            return null;
        }
        try {
            return unifiedGraphBridge.export(factSheetId);
        } catch (RuntimeException ex) {
            log.warn("Falling back to live traversal; unified export failed for factSheet={}", factSheetId, ex);
            return null;
        }
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
        m.put("type", n != null && n.getNodeType() != null ? n.getNodeType().name() : "UNKNOWN");
        return m;
    }

    /**
     * Title of an edge endpoint: the embedded node's when real, else the preloaded store node.
     */
    private String connectedTitle(String nodeId, GraphNode embedded, Map<String, GraphNode> preloadedNodes) {
        if (embedded != null && !embedded.isHollow()
                && embedded.getTitle() != null && !embedded.getTitle().isBlank()) {
            return embedded.getTitle();
        }
        GraphNode node = preloadedNodes.get(nodeId);
        return node != null ? node.getTitle() : null;
    }
}
