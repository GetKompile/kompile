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
package ai.kompile.tool.graphlocalization;

import ai.kompile.graph.algorithms.adjacency.AdjacencyView;
import ai.kompile.utils.StringUtils;
import ai.kompile.graph.algorithms.service.GraphAlgorithmService;
import ai.kompile.knowledgegraph.domain.*;
import ai.kompile.knowledgegraph.repository.*;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service backing the graph localization MCP tool.
 * Provides structural graph queries: neighborhood extraction with filters,
 * hub detection, neighborhood profiling, path-constrained search, and
 * neighborhood comparison.
 */
@Service
public class GraphLocalizationService {

    private static final Logger log = LoggerFactory.getLogger(GraphLocalizationService.class);

    private final KnowledgeGraphService graphService;
    private final GraphAlgorithmService algorithmService;

    @Autowired(required = false)
    private GraphNodeRepository nodeRepository;

    @Autowired(required = false)
    private GraphEdgeRepository edgeRepository;

    @Autowired(required = false)
    private EntityMentionRepository entityMentionRepository;

    @Autowired
    public GraphLocalizationService(KnowledgeGraphService graphService,
                                    GraphAlgorithmService algorithmService) {
        this.graphService = graphService;
        this.algorithmService = algorithmService;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NEIGHBORHOOD EXPLORATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Explore the N-hop neighborhood of a seed node with structured filters.
     * Returns a subgraph (nodes + edges) matching the constraints.
     */
    public Map<String, Object> exploreNeighborhood(String seedNodeId,
                                                    int maxDepth,
                                                    int maxNodes,
                                                    List<String> entityTypeFilter,
                                                    List<String> edgeTypeFilter,
                                                    Double minEdgeWeight,
                                                    Long factSheetId) {
        Optional<GraphNode> seedOpt = resolveNode(seedNodeId);
        if (seedOpt.isEmpty()) {
            return Map.of("error", "Node not found: " + seedNodeId);
        }

        GraphNode seed = seedOpt.get();
        int depth = Math.min(maxDepth, 4);
        int limit = Math.min(maxNodes, 200);

        Set<String> entityTypes = entityTypeFilter != null
                ? entityTypeFilter.stream().map(String::toUpperCase).collect(Collectors.toSet())
                : null;
        Set<EdgeType> edgeTypes = parseEdgeTypes(edgeTypeFilter);
        double minWeight = minEdgeWeight != null ? minEdgeWeight : 0.0;

        // BFS traversal with filters
        Map<String, GraphNode> visited = new LinkedHashMap<>();
        Map<String, Integer> nodeDepths = new LinkedHashMap<>();
        List<Map<String, Object>> edgeResults = new ArrayList<>();
        Queue<String> frontier = new LinkedList<>();

        visited.put(seed.getNodeId(), seed);
        nodeDepths.put(seed.getNodeId(), 0);
        frontier.add(seed.getNodeId());

        while (!frontier.isEmpty() && visited.size() < limit) {
            String currentId = frontier.poll();
            int currentDepth = nodeDepths.get(currentId);
            if (currentDepth >= depth) continue;

            List<GraphEdge> edges = (factSheetId != null && edgeRepository != null)
                    ? edgeRepository.findAllEdgesForNodeIdInFactSheet(currentId, factSheetId)
                    : graphService.getEdgesForNode(currentId);

            for (GraphEdge edge : edges) {
                // Edge type filter
                if (edgeTypes != null && !edgeTypes.contains(edge.getEdgeType())) continue;
                // Weight filter
                if (edge.getWeight() != null && edge.getWeight() < minWeight) continue;

                String neighborId = edge.getSourceNode().getNodeId().equals(currentId)
                        ? edge.getTargetNode().getNodeId()
                        : edge.getSourceNode().getNodeId();

                GraphNode neighbor = edge.getSourceNode().getNodeId().equals(currentId)
                        ? edge.getTargetNode()
                        : edge.getSourceNode();

                // Entity type filter (skipped when entityMentionRepository is unavailable)
                if (entityTypes != null && neighbor.getNodeType() == NodeLevel.ENTITY
                        && entityMentionRepository != null) {
                    List<EntityMention> mentions = entityMentionRepository.findByNode(neighbor);
                    boolean matchesType = mentions.stream()
                            .anyMatch(m -> entityTypes.contains(m.getEntityType().toUpperCase()));
                    if (!matchesType) continue;
                }

                edgeResults.add(formatEdge(edge, currentId));

                if (!visited.containsKey(neighborId) && visited.size() < limit) {
                    visited.put(neighborId, neighbor);
                    nodeDepths.put(neighborId, currentDepth + 1);
                    frontier.add(neighborId);
                }
            }
        }

        List<Map<String, Object>> nodeResults = visited.entrySet().stream()
                .map(e -> formatNode(e.getValue(), nodeDepths.get(e.getKey())))
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("seedNode", seed.getNodeId());
        result.put("seedTitle", seed.getTitle());
        result.put("maxDepth", depth);
        result.put("nodeCount", nodeResults.size());
        result.put("edgeCount", edgeResults.size());
        result.put("nodes", nodeResults);
        result.put("edges", edgeResults);
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STRUCTURAL SEARCH
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Find nodes matching structural predicates: degree thresholds,
     * required edge types, entity type constraints.
     */
    public Map<String, Object> structuralSearch(Integer minDegree,
                                                 Integer maxDegree,
                                                 List<String> requiredEdgeTypes,
                                                 List<String> requiredEntityTypes,
                                                 String nodeTypeFilter,
                                                 String withinNeighborhoodOf,
                                                 Integer neighborhoodHops,
                                                 int maxResults,
                                                 Long factSheetId) {
        int limit = Math.min(maxResults, 100);
        Set<EdgeType> reqEdgeTypes = parseEdgeTypes(requiredEdgeTypes);
        Set<String> reqEntityTypes = requiredEntityTypes != null
                ? requiredEntityTypes.stream().map(String::toUpperCase).collect(Collectors.toSet())
                : null;
        NodeLevel nodeType = parseNodeLevel(nodeTypeFilter);

        // Determine candidate node set
        Set<String> candidateIds;
        if (withinNeighborhoodOf != null && !withinNeighborhoodOf.isBlank()) {
            int hops = neighborhoodHops != null ? Math.min(neighborhoodHops, 4) : 2;
            Map<Integer, List<String>> bfsLayers = algorithmService.bfsTraversal(factSheetId,
                    withinNeighborhoodOf, hops);
            candidateIds = bfsLayers.values().stream()
                    .flatMap(Collection::stream)
                    .collect(Collectors.toSet());
        } else {
            // Use all nodes (paginated to avoid loading entire graph)
            List<GraphNode> allNodes = nodeType != null
                    ? graphService.getNodesByType(nodeType, 5000)
                    : graphService.getNodesByType(null, 5000);
            candidateIds = allNodes.stream()
                    .map(GraphNode::getNodeId)
                    .collect(Collectors.toSet());
        }

        // Evaluate structural predicates on each candidate
        List<Map<String, Object>> matches = new ArrayList<>();
        for (String nodeId : candidateIds) {
            if (matches.size() >= limit) break;

            Optional<GraphNode> nodeOpt = resolveNode(nodeId);
            if (nodeOpt.isEmpty()) continue;
            GraphNode node = nodeOpt.get();

            // Node type filter
            if (nodeType != null && node.getNodeType() != nodeType) continue;

            List<GraphEdge> edges = graphService.getEdgesForNode(nodeId);
            int degree = edges.size();

            // Degree filters
            if (minDegree != null && degree < minDegree) continue;
            if (maxDegree != null && degree > maxDegree) continue;

            // Required edge types: node must have at least one edge of each required type
            if (reqEdgeTypes != null) {
                Set<EdgeType> presentTypes = edges.stream()
                        .map(GraphEdge::getEdgeType)
                        .collect(Collectors.toSet());
                if (!presentTypes.containsAll(reqEdgeTypes)) continue;
            }

            // Required entity types: node must be connected to entities of these types
            // (skipped when entityMentionRepository is unavailable)
            if (reqEntityTypes != null && entityMentionRepository != null) {
                Set<String> connectedEntityTypes = new HashSet<>();
                for (GraphEdge edge : edges) {
                    GraphNode neighbor = edge.getSourceNode().getNodeId().equals(nodeId)
                            ? edge.getTargetNode() : edge.getSourceNode();
                    if (neighbor.getNodeType() == NodeLevel.ENTITY) {
                        List<EntityMention> mentions = entityMentionRepository.findByNode(neighbor);
                        mentions.forEach(m -> connectedEntityTypes.add(m.getEntityType().toUpperCase()));
                    }
                }
                if (!connectedEntityTypes.containsAll(reqEntityTypes)) continue;
            }

            // Build match result with structural metadata
            Map<String, Object> match = formatNode(node, null);
            match.put("degree", degree);

            // Edge type breakdown
            Map<String, Long> edgeTypeCounts = edges.stream()
                    .collect(Collectors.groupingBy(e -> e.getEdgeType().name(), Collectors.counting()));
            match.put("edgeTypeCounts", edgeTypeCounts);

            matches.add(match);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("matchCount", matches.size());
        result.put("filters", buildFilterSummary(minDegree, maxDegree, requiredEdgeTypes,
                requiredEntityTypes, nodeTypeFilter, withinNeighborhoodOf));
        result.put("matches", matches);
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NEIGHBORHOOD PROFILE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Generate a quantitative profile of a node's neighborhood:
     * type distributions, density, centrality rankings, bridge nodes.
     */
    public Map<String, Object> profileNeighborhood(String seedNodeId,
                                                    int depth,
                                                    Long factSheetId) {
        Optional<GraphNode> seedOpt = resolveNode(seedNodeId);
        if (seedOpt.isEmpty()) {
            return Map.of("error", "Node not found: " + seedNodeId);
        }

        GraphNode seed = seedOpt.get();
        int maxDepth = Math.min(depth, 3);

        // Get neighborhood via BFS
        Map<Integer, List<String>> bfsLayers = algorithmService.bfsTraversal(factSheetId,
                seed.getNodeId(), maxDepth);
        Set<String> neighborhoodIds = bfsLayers.values().stream()
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());

        if (neighborhoodIds.isEmpty()) {
            return Map.of("seedNode", seed.getNodeId(), "seedTitle", seed.getTitle(),
                    "message", "No connected nodes found");
        }

        // Node type distribution
        Map<String, Integer> nodeTypeDistribution = new LinkedHashMap<>();
        Map<String, Integer> entityTypeDistribution = new LinkedHashMap<>();
        int totalEdges = 0;

        for (String nodeId : neighborhoodIds) {
            Optional<GraphNode> nOpt = resolveNode(nodeId);
            if (nOpt.isEmpty()) continue;
            GraphNode n = nOpt.get();

            nodeTypeDistribution.merge(n.getNodeType().name(), 1, Integer::sum);

            if (n.getNodeType() == NodeLevel.ENTITY && entityMentionRepository != null) {
                List<EntityMention> mentions = entityMentionRepository.findByNode(n);
                for (EntityMention m : mentions) {
                    entityTypeDistribution.merge(m.getEntityType().toUpperCase(), 1, Integer::sum);
                }
            }

            totalEdges += graphService.getEdgesForNode(nodeId).size();
        }
        // Each edge counted from both ends
        totalEdges = totalEdges / 2;

        // Edge type distribution within neighborhood
        Map<String, Integer> edgeTypeDistribution = new LinkedHashMap<>();
        Set<String> countedEdges = new HashSet<>();
        for (String nodeId : neighborhoodIds) {
            List<GraphEdge> edges = graphService.getEdgesForNode(nodeId);
            for (GraphEdge edge : edges) {
                if (countedEdges.add(edge.getEdgeId())) {
                    String otherNode = edge.getSourceNode().getNodeId().equals(nodeId)
                            ? edge.getTargetNode().getNodeId()
                            : edge.getSourceNode().getNodeId();
                    if (neighborhoodIds.contains(otherNode)) {
                        edgeTypeDistribution.merge(edge.getEdgeType().name(), 1, Integer::sum);
                    }
                }
            }
        }

        // Centrality scores for neighborhood nodes
        Map<String, Double> pageRank = algorithmService.pageRank(factSheetId, 0.85, 50, 1e-6);
        Map<String, Double> degreeCentrality = algorithmService.degreeCentrality(factSheetId,
                ai.kompile.graph.algorithms.DegreeCentrality.Type.TOTAL);

        // Top nodes by centrality within neighborhood
        List<Map<String, Object>> topByPageRank = neighborhoodIds.stream()
                .filter(pageRank::containsKey)
                .sorted((a, b) -> Double.compare(pageRank.getOrDefault(b, 0.0),
                        pageRank.getOrDefault(a, 0.0)))
                .limit(10)
                .map(id -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("nodeId", id);
                    resolveNode(id).ifPresent(n -> m.put("title", n.getTitle()));
                    m.put("pageRank", Math.round(pageRank.getOrDefault(id, 0.0) * 10000.0) / 10000.0);
                    m.put("degree", degreeCentrality.getOrDefault(id, 0.0).intValue());
                    return m;
                })
                .collect(Collectors.toList());

        // Depth layer sizes
        Map<String, Integer> layerSizes = new LinkedHashMap<>();
        bfsLayers.forEach((d, ids) -> layerSizes.put("depth_" + d, ids.size()));

        // Density: actual edges / possible edges
        int n = neighborhoodIds.size();
        double density = n > 1
                ? (2.0 * totalEdges) / (n * (n - 1))
                : 0.0;

        // Bridge nodes: nodes whose removal would disconnect components
        List<String> bridgeNodes = findBridgeNodes(neighborhoodIds, factSheetId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("seedNode", seed.getNodeId());
        result.put("seedTitle", seed.getTitle());
        result.put("depth", maxDepth);
        result.put("totalNodes", n);
        result.put("totalEdges", totalEdges);
        result.put("density", Math.round(density * 10000.0) / 10000.0);
        result.put("layerSizes", layerSizes);
        result.put("nodeTypeDistribution", nodeTypeDistribution);
        result.put("entityTypeDistribution", entityTypeDistribution);
        result.put("edgeTypeDistribution", edgeTypeDistribution);
        result.put("topNodesByPageRank", topByPageRank);
        result.put("bridgeNodeCount", bridgeNodes.size());
        result.put("bridgeNodes", bridgeNodes.stream().limit(10).collect(Collectors.toList()));
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HUB DETECTION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Find hub nodes: highest degree, PageRank, or betweenness centrality.
     */
    public Map<String, Object> findHubs(String metric,
                                         String nodeTypeFilter,
                                         String scopeNodeId,
                                         Integer scopeHops,
                                         int topK,
                                         Long factSheetId) {
        int limit = Math.min(topK, 50);
        NodeLevel nodeType = parseNodeLevel(nodeTypeFilter);

        // Compute the requested centrality metric
        Map<String, Double> scores;
        switch (metric != null ? metric.toLowerCase() : "degree") {
            case "pagerank":
                scores = algorithmService.pageRank(factSheetId, 0.85, 50, 1e-6);
                break;
            case "betweenness":
                scores = algorithmService.betweennessCentrality(factSheetId, 500, 42L);
                break;
            default: // "degree"
                scores = algorithmService.degreeCentrality(factSheetId,
                        ai.kompile.graph.algorithms.DegreeCentrality.Type.TOTAL);
                break;
        }

        // If scoped, restrict to neighborhood
        Set<String> scope = null;
        if (scopeNodeId != null && !scopeNodeId.isBlank()) {
            int hops = scopeHops != null ? Math.min(scopeHops, 4) : 2;
            Map<Integer, List<String>> bfsLayers = algorithmService.bfsTraversal(factSheetId,
                    scopeNodeId, hops);
            scope = bfsLayers.values().stream()
                    .flatMap(Collection::stream)
                    .collect(Collectors.toSet());
        }

        Set<String> finalScope = scope;
        List<Map<String, Object>> hubs = scores.entrySet().stream()
                .filter(e -> finalScope == null || finalScope.contains(e.getKey()))
                .filter(e -> {
                    if (nodeType == null) return true;
                    Optional<GraphNode> nOpt = resolveNode(e.getKey());
                    return nOpt.isPresent() && nOpt.get().getNodeType() == nodeType;
                })
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(limit)
                .map(e -> {
                    Map<String, Object> hub = new LinkedHashMap<>();
                    hub.put("nodeId", e.getKey());
                    resolveNode(e.getKey()).ifPresent(n -> {
                        hub.put("title", n.getTitle());
                        hub.put("nodeType", n.getNodeType().name());
                        hub.put("description", StringUtils.truncate(n.getDescription(), 150));
                    });
                    hub.put("score", Math.round(e.getValue() * 10000.0) / 10000.0);
                    return hub;
                })
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("metric", metric != null ? metric : "degree");
        result.put("hubCount", hubs.size());
        if (scopeNodeId != null) result.put("scopeNode", scopeNodeId);
        result.put("hubs", hubs);
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PATH-CONSTRAINED SEARCH
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Find paths between two nodes with constraints on intermediate types
     * and edge types.
     */
    public Map<String, Object> constrainedPathSearch(String fromNodeId,
                                                      String toNodeId,
                                                      List<String> allowedEdgeTypes,
                                                      List<String> requiredIntermediateTypes,
                                                      int maxPathLength,
                                                      boolean weighted,
                                                      Long factSheetId) {
        Optional<GraphNode> fromOpt = resolveNode(fromNodeId);
        Optional<GraphNode> toOpt = resolveNode(toNodeId);
        if (fromOpt.isEmpty()) return Map.of("error", "Source node not found: " + fromNodeId);
        if (toOpt.isEmpty()) return Map.of("error", "Target node not found: " + toNodeId);

        int maxLen = Math.min(maxPathLength, 6);
        Set<EdgeType> allowedTypes = parseEdgeTypes(allowedEdgeTypes);
        Set<String> requiredTypes = requiredIntermediateTypes != null
                ? requiredIntermediateTypes.stream().map(String::toUpperCase).collect(Collectors.toSet())
                : null;

        // BFS path search with edge type filtering
        Map<String, String> parentMap = new LinkedHashMap<>();
        Map<String, String> parentEdgeMap = new LinkedHashMap<>();
        Map<String, Integer> depthMap = new LinkedHashMap<>();
        Queue<String> queue = new LinkedList<>();

        queue.add(fromNodeId);
        depthMap.put(fromNodeId, 0);
        boolean found = false;

        while (!queue.isEmpty() && !found) {
            String current = queue.poll();
            int currentDepth = depthMap.get(current);
            if (currentDepth >= maxLen) continue;

            List<GraphEdge> edges = (factSheetId != null && edgeRepository != null)
                    ? edgeRepository.findAllEdgesForNodeIdInFactSheet(current, factSheetId)
                    : graphService.getEdgesForNode(current);

            for (GraphEdge edge : edges) {
                if (allowedTypes != null && !allowedTypes.contains(edge.getEdgeType())) continue;

                String neighborId = edge.getSourceNode().getNodeId().equals(current)
                        ? edge.getTargetNode().getNodeId()
                        : edge.getSourceNode().getNodeId();

                if (depthMap.containsKey(neighborId)) continue;

                parentMap.put(neighborId, current);
                parentEdgeMap.put(neighborId, edge.getEdgeType().name() + "(" +
                        (edge.getWeight() != null ? String.format("%.2f", edge.getWeight()) : "1.0") + ")");
                depthMap.put(neighborId, currentDepth + 1);

                if (neighborId.equals(toNodeId)) {
                    found = true;
                    break;
                }
                queue.add(neighborId);
            }
        }

        if (!found) {
            return Map.of(
                    "from", fromNodeId,
                    "to", toNodeId,
                    "found", false,
                    "message", "No path found within " + maxLen + " hops with the given constraints"
            );
        }

        // Reconstruct path
        List<Map<String, Object>> pathNodes = new ArrayList<>();
        List<String> pathEdgeLabels = new ArrayList<>();
        String cursor = toNodeId;
        while (cursor != null) {
            Optional<GraphNode> nOpt = resolveNode(cursor);
            Map<String, Object> pathNode = new LinkedHashMap<>();
            pathNode.put("nodeId", cursor);
            nOpt.ifPresent(n -> {
                pathNode.put("title", n.getTitle());
                pathNode.put("nodeType", n.getNodeType().name());
            });
            pathNodes.add(0, pathNode);
            if (parentEdgeMap.containsKey(cursor)) {
                pathEdgeLabels.add(0, parentEdgeMap.get(cursor));
            }
            cursor = parentMap.get(cursor);
        }

        // Verify required intermediate types
        if (requiredTypes != null && !requiredTypes.isEmpty()) {
            Set<String> intermediateTypes = new HashSet<>();
            for (int i = 1; i < pathNodes.size() - 1; i++) {
                Object nt = pathNodes.get(i).get("nodeType");
                if (nt != null) intermediateTypes.add(nt.toString());
            }
            if (!intermediateTypes.containsAll(requiredTypes)) {
                return Map.of(
                        "from", fromNodeId,
                        "to", toNodeId,
                        "found", false,
                        "message", "Path found but missing required intermediate types: " + requiredTypes
                );
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", fromNodeId);
        result.put("to", toNodeId);
        result.put("found", true);
        result.put("pathLength", pathNodes.size() - 1);
        result.put("path", pathNodes);
        result.put("edgeLabels", pathEdgeLabels);
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NEIGHBORHOOD COMPARISON
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Compare the neighborhoods of two nodes: shared entities, structural
     * differences, Jaccard similarity.
     */
    public Map<String, Object> compareNeighborhoods(String nodeAId,
                                                     String nodeBId,
                                                     int depth,
                                                     Long factSheetId) {
        Optional<GraphNode> aOpt = resolveNode(nodeAId);
        Optional<GraphNode> bOpt = resolveNode(nodeBId);
        if (aOpt.isEmpty()) return Map.of("error", "Node A not found: " + nodeAId);
        if (bOpt.isEmpty()) return Map.of("error", "Node B not found: " + nodeBId);

        int maxDepth = Math.min(depth, 3);

        // Get neighborhoods via BFS
        Map<Integer, List<String>> aLayers = algorithmService.bfsTraversal(factSheetId, nodeAId, maxDepth);
        Map<Integer, List<String>> bLayers = algorithmService.bfsTraversal(factSheetId, nodeBId, maxDepth);

        Set<String> aNeighborhood = aLayers.values().stream()
                .flatMap(Collection::stream).collect(Collectors.toSet());
        Set<String> bNeighborhood = bLayers.values().stream()
                .flatMap(Collection::stream).collect(Collectors.toSet());

        // Shared and unique nodes
        Set<String> shared = new HashSet<>(aNeighborhood);
        shared.retainAll(bNeighborhood);
        Set<String> uniqueToA = new HashSet<>(aNeighborhood);
        uniqueToA.removeAll(bNeighborhood);
        Set<String> uniqueToB = new HashSet<>(bNeighborhood);
        uniqueToB.removeAll(aNeighborhood);

        // Jaccard similarity
        double jaccard = algorithmService.jaccardSimilarity(factSheetId, nodeAId, nodeBId);

        // Entity type overlap
        Map<String, Set<String>> aEntityTypes = collectEntityTypes(aNeighborhood);
        Map<String, Set<String>> bEntityTypes = collectEntityTypes(bNeighborhood);
        Set<String> sharedEntityTypes = new HashSet<>(aEntityTypes.keySet());
        sharedEntityTypes.retainAll(bEntityTypes.keySet());

        // Shared entity names
        Set<String> aEntityNames = aEntityTypes.values().stream()
                .flatMap(Set::stream).collect(Collectors.toSet());
        Set<String> bEntityNames = bEntityTypes.values().stream()
                .flatMap(Set::stream).collect(Collectors.toSet());
        Set<String> sharedEntityNames = new HashSet<>(aEntityNames);
        sharedEntityNames.retainAll(bEntityNames);

        // Format shared nodes with titles
        List<Map<String, String>> sharedNodeDetails = shared.stream()
                .limit(20)
                .map(id -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("nodeId", id);
                    resolveNode(id).ifPresent(n -> m.put("title", n.getTitle() != null ? n.getTitle() : "Untitled"));
                    return m;
                })
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodeA", Map.of("nodeId", nodeAId, "title", aOpt.get().getTitle(),
                "neighborhoodSize", aNeighborhood.size()));
        result.put("nodeB", Map.of("nodeId", nodeBId, "title", bOpt.get().getTitle(),
                "neighborhoodSize", bNeighborhood.size()));
        result.put("depth", maxDepth);
        result.put("jaccardSimilarity", Math.round(jaccard * 10000.0) / 10000.0);
        result.put("sharedNodeCount", shared.size());
        result.put("uniqueToACount", uniqueToA.size());
        result.put("uniqueToBCount", uniqueToB.size());
        result.put("sharedNodes", sharedNodeDetails);
        result.put("sharedEntityTypes", sharedEntityTypes);
        result.put("sharedEntityNames", sharedEntityNames.stream().limit(20).collect(Collectors.toList()));
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // COMMUNITY SEARCH
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Find the community a node belongs to and return its members with metadata.
     */
    public Map<String, Object> findCommunity(String seedNodeId,
                                              String algorithm,
                                              int maxMembers,
                                              Long factSheetId) {
        Optional<GraphNode> seedOpt = resolveNode(seedNodeId);
        if (seedOpt.isEmpty()) {
            return Map.of("error", "Node not found: " + seedNodeId);
        }

        int limit = Math.min(maxMembers, 100);
        String algo = algorithm != null ? algorithm.toLowerCase() : "louvain";

        Map<String, Integer> assignments;
        if ("wcc".equals(algo)) {
            assignments = algorithmService.weaklyConnectedComponents(factSheetId);
        } else {
            assignments = algorithmService.louvainCommunities(factSheetId, 20);
        }

        Integer seedCommunity = assignments.get(seedNodeId);
        if (seedCommunity == null) {
            return Map.of("error", "Node not found in community assignments",
                    "seedNode", seedNodeId);
        }

        // All nodes in the same community
        List<String> communityMembers = assignments.entrySet().stream()
                .filter(e -> e.getValue().equals(seedCommunity))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        // Get PageRank for ranking within community
        Map<String, Double> pageRank = algorithmService.pageRank(factSheetId, 0.85, 50, 1e-6);

        List<Map<String, Object>> members = communityMembers.stream()
                .sorted((a, b) -> Double.compare(
                        pageRank.getOrDefault(b, 0.0),
                        pageRank.getOrDefault(a, 0.0)))
                .limit(limit)
                .map(id -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("nodeId", id);
                    resolveNode(id).ifPresent(n -> {
                        m.put("title", n.getTitle());
                        m.put("nodeType", n.getNodeType().name());
                        m.put("description", StringUtils.truncate(n.getDescription(), 120));
                    });
                    m.put("pageRank", Math.round(pageRank.getOrDefault(id, 0.0) * 10000.0) / 10000.0);
                    return m;
                })
                .collect(Collectors.toList());

        // Entity type summary for community (skipped when entityMentionRepository is unavailable)
        Map<String, Integer> entityTypeSummary = new LinkedHashMap<>();
        if (entityMentionRepository != null) {
            for (String id : communityMembers) {
                resolveNode(id).ifPresent(n -> {
                    if (n.getNodeType() == NodeLevel.ENTITY) {
                        entityMentionRepository.findByNode(n)
                                .forEach(em -> entityTypeSummary.merge(
                                        em.getEntityType().toUpperCase(), 1, Integer::sum));
                    }
                });
            }
        }

        // Total communities count
        long totalCommunities = assignments.values().stream().distinct().count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("seedNode", seedNodeId);
        result.put("algorithm", algo);
        result.put("communityId", seedCommunity);
        result.put("communitySize", communityMembers.size());
        result.put("totalCommunities", totalCommunities);
        result.put("entityTypeSummary", entityTypeSummary);
        result.put("members", members);
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private Optional<GraphNode> resolveNode(String nodeId) {
        Optional<GraphNode> opt = graphService.getNode(nodeId);
        if (opt.isEmpty()) {
            // Try external ID against common node types
            for (NodeLevel level : NodeLevel.values()) {
                opt = graphService.getNodeByExternalId(nodeId, level);
                if (opt.isPresent()) break;
            }
        }
        return opt;
    }

    private Map<String, Object> formatNode(GraphNode node, Integer depth) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodeId", node.getNodeId());
        m.put("title", node.getTitle() != null ? node.getTitle() : "Untitled");
        m.put("nodeType", node.getNodeType().name());
        if (depth != null) m.put("depth", depth);
        if (node.getDescription() != null) {
            m.put("description", StringUtils.truncate(node.getDescription(), 150));
        }
        if (node.getEdgeCount() > 0) {
            m.put("edgeCount", node.getEdgeCount());
        }
        return m;
    }

    private Map<String, Object> formatEdge(GraphEdge edge, String fromNodeId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("edgeId", edge.getEdgeId());
        m.put("edgeType", edge.getEdgeType().name());
        m.put("from", edge.getSourceNode().getNodeId());
        m.put("to", edge.getTargetNode().getNodeId());
        if (edge.getWeight() != null) m.put("weight", edge.getWeight());
        if (edge.getLabel() != null) m.put("label", edge.getLabel());
        if (edge.getDescription() != null) m.put("description", StringUtils.truncate(edge.getDescription(), 100));
        if (edge.getConfidence() != null) m.put("confidence", edge.getConfidence());
        return m;
    }

    private Set<EdgeType> parseEdgeTypes(List<String> types) {
        if (types == null || types.isEmpty()) return null;
        Set<EdgeType> result = new HashSet<>();
        for (String t : types) {
            try {
                result.add(EdgeType.valueOf(t.toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                log.warn("Unknown edge type: {}", t);
            }
        }
        return result.isEmpty() ? null : result;
    }

    private NodeLevel parseNodeLevel(String type) {
        if (type == null || type.isBlank()) return null;
        try {
            return NodeLevel.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Map<String, Set<String>> collectEntityTypes(Set<String> nodeIds) {
        Map<String, Set<String>> typeToNames = new LinkedHashMap<>();
        if (entityMentionRepository == null) {
            return typeToNames;
        }
        for (String id : nodeIds) {
            resolveNode(id).ifPresent(n -> {
                if (n.getNodeType() == NodeLevel.ENTITY) {
                    entityMentionRepository.findByNode(n).forEach(m ->
                            typeToNames.computeIfAbsent(m.getEntityType().toUpperCase(),
                                    k -> new HashSet<>()).add(m.getEntityName()));
                }
            });
        }
        return typeToNames;
    }

    private List<String> findBridgeNodes(Set<String> neighborhoodIds, Long factSheetId) {
        if (neighborhoodIds.size() < 3) return List.of();

        AdjacencyView view = algorithmService.view(factSheetId);
        List<String> bridges = new ArrayList<>();

        for (String candidate : neighborhoodIds) {
            if (!neighborhoodIds.contains(candidate)) continue;
            // Check if removing this node increases the number of components
            Set<String> remaining = new HashSet<>(neighborhoodIds);
            remaining.remove(candidate);

            // Quick connectivity check on remaining
            if (remaining.isEmpty()) continue;
            Set<String> reachable = new HashSet<>();
            Queue<String> q = new LinkedList<>();
            String start = remaining.iterator().next();
            q.add(start);
            reachable.add(start);
            while (!q.isEmpty()) {
                String cur = q.poll();
                for (String neighbor : view.neighbors(cur)) {
                    if (remaining.contains(neighbor) && reachable.add(neighbor)) {
                        q.add(neighbor);
                    }
                }
            }
            if (reachable.size() < remaining.size()) {
                bridges.add(candidate);
            }
        }
        return bridges;
    }

    private Map<String, Object> buildFilterSummary(Integer minDegree, Integer maxDegree,
                                                    List<String> edgeTypes, List<String> entityTypes,
                                                    String nodeType, String scopeNode) {
        Map<String, Object> filters = new LinkedHashMap<>();
        if (minDegree != null) filters.put("minDegree", minDegree);
        if (maxDegree != null) filters.put("maxDegree", maxDegree);
        if (edgeTypes != null) filters.put("requiredEdgeTypes", edgeTypes);
        if (entityTypes != null) filters.put("requiredEntityTypes", entityTypes);
        if (nodeType != null) filters.put("nodeType", nodeType);
        if (scopeNode != null) filters.put("withinNeighborhoodOf", scopeNode);
        return filters;
    }

}
