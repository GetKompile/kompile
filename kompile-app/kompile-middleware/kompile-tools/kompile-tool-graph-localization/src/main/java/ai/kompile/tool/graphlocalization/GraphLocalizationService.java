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

import ai.kompile.graph.algorithms.DegreeCentrality;
import ai.kompile.graph.algorithms.adjacency.AdjacencyView;
import ai.kompile.graph.algorithms.service.GraphAlgorithmService;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.domain.*;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.knowledgegraph.unified.UnifiedGraphBridge;
import ai.kompile.utils.StringUtils;
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
    private final UnifiedGraphBridge unifiedGraphBridge;

    public GraphLocalizationService(KnowledgeGraphService graphService,
                                    GraphAlgorithmService algorithmService) {
        this(graphService, algorithmService, null);
    }

    @Autowired
    public GraphLocalizationService(KnowledgeGraphService graphService,
                                    GraphAlgorithmService algorithmService,
                                    @org.springframework.lang.Nullable UnifiedGraphBridge unifiedGraphBridge) {
        this.graphService = graphService;
        this.algorithmService = algorithmService;
        this.unifiedGraphBridge = unifiedGraphBridge;
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
        UnifiedGraph unified = unifiedGraph(factSheetId);
        Optional<GraphNode> seedOpt = resolveNode(seedNodeId);
        if (seedOpt.isEmpty() && (unified == null || unified.entity(seedNodeId).isEmpty())) {
            return Map.of("error", "Node not found: " + seedNodeId);
        }

        int depth = Math.min(maxDepth, 4);
        int limit = Math.min(maxNodes, 200);

        Set<String> entityTypes = entityTypeFilter != null
                ? entityTypeFilter.stream().map(String::toUpperCase).collect(Collectors.toSet())
                : null;
        Set<EdgeType> edgeTypes = parseEdgeTypes(edgeTypeFilter);
        double minWeight = minEdgeWeight != null ? minEdgeWeight : 0.0;

        if (unified != null) {
            return exploreUnifiedNeighborhood(seedNodeId, seedOpt.orElse(null), depth, limit,
                    entityTypes, edgeTypes, minWeight, unified);
        }

        GraphNode seed = seedOpt.get();

        // BFS traversal with filters
        Map<String, GraphNode> visited = new LinkedHashMap<>();
        Map<String, Integer> nodeDepths = new LinkedHashMap<>();
        List<Map<String, Object>> edgeResults = new ArrayList<>();
        Queue<String> frontier = new LinkedList<>();
        // Store-loaded edges embed hollow id-only endpoint nodes — resolve real ones once each.
        Map<String, GraphNode> resolvedNeighbors = new HashMap<>();

        visited.put(seed.getNodeId(), seed);
        nodeDepths.put(seed.getNodeId(), 0);
        frontier.add(seed.getNodeId());

        while (!frontier.isEmpty() && visited.size() < limit) {
            String currentId = frontier.poll();
            int currentDepth = nodeDepths.get(currentId);
            if (currentDepth >= depth) continue;

            List<GraphEdge> edges = (factSheetId != null)
                    ? graphService.getEdgesForNodeInFactSheet(currentId, factSheetId)
                    : graphService.getEdgesForNode(currentId);

            for (GraphEdge edge : edges) {
                // Edge type filter
                if (edgeTypes != null && !edgeTypes.contains(edge.getEdgeType())) continue;
                // Weight filter
                if (edge.getWeight() != null && edge.getWeight() < minWeight) continue;

                String neighborId = edge.getSourceNode().getNodeId().equals(currentId)
                        ? edge.getTargetNode().getNodeId()
                        : edge.getSourceNode().getNodeId();

                GraphNode neighbor = resolveEndpoint(neighborId,
                        edge.getSourceNode().getNodeId().equals(currentId)
                                ? edge.getTargetNode()
                                : edge.getSourceNode(),
                        resolvedNeighbors);

                // Entity type filter
                if (entityTypes != null && neighbor.getNodeType() == NodeLevel.ENTITY) {
                    List<EntityMention> mentions = graphService.getEntityMentionsForNode(neighbor);
                    boolean matchesType = mentions.stream()
                            .anyMatch(m -> m.getEntityType() != null
                                    && entityTypes.contains(m.getEntityType().toUpperCase()));
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

        UnifiedGraph unified = unifiedGraph(factSheetId);

        // Determine candidate node set
        Set<String> candidateIds;
        Map<String, GraphNode> candidateNodes = new LinkedHashMap<>();
        if (withinNeighborhoodOf != null && !withinNeighborhoodOf.isBlank()) {
            int hops = neighborhoodHops != null ? Math.min(neighborhoodHops, 4) : 2;
            Map<Integer, List<String>> bfsLayers = bfsTraversal(factSheetId,
                    withinNeighborhoodOf, hops, unified);
            candidateIds = bfsLayers.values().stream()
                    .flatMap(Collection::stream)
                    .collect(Collectors.toSet());
            for (GraphNode node : graphService.getNodesByIds(new ArrayList<>(candidateIds))) {
                if (node != null && node.getNodeId() != null) {
                    candidateNodes.putIfAbsent(node.getNodeId(), node);
                }
            }
        } else {
            // Use all nodes (paginated to avoid loading entire graph)
            List<GraphNode> allNodes = nodeType != null
                    ? graphService.getNodesByType(nodeType, 5000)
                    : graphService.getNodesByType(null, 5000);
            for (GraphNode node : allNodes) {
                if (node != null && node.getNodeId() != null) {
                    candidateNodes.putIfAbsent(node.getNodeId(), node);
                }
            }
            candidateIds = candidateNodes.keySet();
        }

        // Evaluate structural predicates on each candidate
        List<Map<String, Object>> matches = new ArrayList<>();
        // Neighbors repeat across candidates; resolve each hollow edge endpoint at most once.
        Map<String, GraphNode> resolvedNeighbors = new HashMap<>();
        for (String nodeId : candidateIds) {
            if (matches.size() >= limit) break;

            GraphNode node = candidateNodes.get(nodeId);
            if (node == null) {
                Optional<GraphNode> nodeOpt = resolveNode(nodeId);
                if (nodeOpt.isEmpty()) continue;
                node = nodeOpt.get();
            }

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
            if (reqEntityTypes != null) {
                Set<String> connectedEntityTypes = new HashSet<>();
                for (GraphEdge edge : edges) {
                    GraphNode embedded = edge.getSourceNode().getNodeId().equals(nodeId)
                            ? edge.getTargetNode() : edge.getSourceNode();
                    GraphNode neighbor = resolveEndpoint(embedded.getNodeId(), embedded, resolvedNeighbors);
                    if (neighbor.getNodeType() == NodeLevel.ENTITY) {
                        List<EntityMention> mentions = graphService.getEntityMentionsForNode(neighbor);
                        mentions.stream()
                                .filter(m -> m.getEntityType() != null)
                                .forEach(m -> connectedEntityTypes.add(m.getEntityType().toUpperCase()));
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
        result.put("source", sourceLabel(unified));
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
        UnifiedGraph unified = unifiedGraph(factSheetId);
        Optional<GraphNode> seedOpt = resolveNode(seedNodeId);
        if (seedOpt.isEmpty() && (unified == null || unified.entity(seedNodeId).isEmpty())) {
            return Map.of("error", "Node not found: " + seedNodeId);
        }

        String seedGraphId = seedOpt.map(GraphNode::getNodeId).orElse(seedNodeId);
        String seedTitle = seedOpt.map(GraphNode::getTitle)
                .orElseGet(() -> nodeTitle(seedNodeId, null, unified));
        int maxDepth = Math.min(depth, 3);

        // Get neighborhood via BFS
        Map<Integer, List<String>> bfsLayers = bfsTraversal(factSheetId,
                seedGraphId, maxDepth, unified);
        Set<String> neighborhoodIds = bfsLayers.values().stream()
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());

        if (neighborhoodIds.isEmpty()) {
            return Map.of("source", sourceLabel(unified),
                    "seedNode", seedGraphId, "seedTitle", seedTitle,
                    "message", "No connected nodes found");
        }

        // Node and edge distributions within neighborhood
        Map<String, Integer> nodeTypeDistribution = new LinkedHashMap<>();
        Map<String, Integer> entityTypeDistribution = new LinkedHashMap<>();
        Map<String, Integer> edgeTypeDistribution = new LinkedHashMap<>();
        int totalEdges = unified != null
                ? populateUnifiedProfileDistributions(neighborhoodIds, unified,
                        nodeTypeDistribution, entityTypeDistribution, edgeTypeDistribution)
                : populateLiveProfileDistributions(neighborhoodIds,
                        nodeTypeDistribution, entityTypeDistribution, edgeTypeDistribution);

        // Centrality scores for neighborhood nodes
        Map<String, Double> pageRank = pageRank(factSheetId, unified);
        Map<String, Double> degreeCentrality = degreeCentrality(factSheetId, unified);

        // Top nodes by centrality within neighborhood
        List<Map<String, Object>> topByPageRank = neighborhoodIds.stream()
                .filter(pageRank::containsKey)
                .sorted((a, b) -> Double.compare(pageRank.getOrDefault(b, 0.0),
                        pageRank.getOrDefault(a, 0.0)))
                .limit(10)
                .map(id -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("nodeId", id);
                    putNodeDetails(m, id, unified, 150);
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
        List<String> bridgeNodes = findBridgeNodes(neighborhoodIds, factSheetId, unified);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", sourceLabel(unified));
        result.put("seedNode", seedGraphId);
        result.put("seedTitle", seedTitle);
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
        UnifiedGraph unified = unifiedGraph(factSheetId);

        // Compute the requested centrality metric
        Map<String, Double> scores = centralityScores(metric, factSheetId, unified);

        // If scoped, restrict to neighborhood
        Set<String> scope = null;
        if (scopeNodeId != null && !scopeNodeId.isBlank()) {
            int hops = scopeHops != null ? Math.min(scopeHops, 4) : 2;
            Map<Integer, List<String>> bfsLayers = bfsTraversal(factSheetId,
                    scopeNodeId, hops, unified);
            scope = bfsLayers.values().stream()
                    .flatMap(Collection::stream)
                    .collect(Collectors.toSet());
        }

        Set<String> finalScope = scope;
        List<Map<String, Object>> hubs = scores.entrySet().stream()
                .filter(e -> finalScope == null || finalScope.contains(e.getKey()))
                .filter(e -> matchesNodeType(e.getKey(), nodeType, unified))
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(limit)
                .map(e -> {
                    Map<String, Object> hub = new LinkedHashMap<>();
                    hub.put("nodeId", e.getKey());
                    putNodeDetails(hub, e.getKey(), unified, 150);
                    hub.put("score", Math.round(e.getValue() * 10000.0) / 10000.0);
                    return hub;
                })
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", sourceLabel(unified));
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
        UnifiedGraph unified = unifiedGraph(factSheetId);
        Optional<GraphNode> fromOpt = resolveNode(fromNodeId);
        Optional<GraphNode> toOpt = resolveNode(toNodeId);
        if (fromOpt.isEmpty() && (unified == null || unified.entity(fromNodeId).isEmpty())) {
            return Map.of("error", "Source node not found: " + fromNodeId);
        }
        if (toOpt.isEmpty() && (unified == null || unified.entity(toNodeId).isEmpty())) {
            return Map.of("error", "Target node not found: " + toNodeId);
        }

        int maxLen = Math.min(maxPathLength, 6);
        Set<EdgeType> allowedTypes = parseEdgeTypes(allowedEdgeTypes);
        Set<String> requiredTypes = requiredIntermediateTypes != null
                ? requiredIntermediateTypes.stream().map(String::toUpperCase).collect(Collectors.toSet())
                : null;

        if (unified != null) {
            return constrainedUnifiedPathSearch(fromNodeId, toNodeId, fromOpt.orElse(null), toOpt.orElse(null),
                    allowedTypes, requiredTypes, maxLen, unified);
        }

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

            List<GraphEdge> edges = (factSheetId != null)
                    ? graphService.getEdgesForNodeInFactSheet(current, factSheetId)
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
        UnifiedGraph unified = unifiedGraph(factSheetId);

        // Get neighborhoods via BFS
        Map<Integer, List<String>> aLayers = bfsTraversal(factSheetId, nodeAId, maxDepth, unified);
        Map<Integer, List<String>> bLayers = bfsTraversal(factSheetId, nodeBId, maxDepth, unified);

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
        double jaccard = jaccardSimilarity(factSheetId, nodeAId, nodeBId, unified);

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
        List<Map<String, Object>> sharedNodeDetails = shared.stream()
                .limit(20)
                .map(id -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("nodeId", id);
                    putNodeDetails(m, id, unified, 150);
                    return m;
                })
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", sourceLabel(unified));
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
        UnifiedGraph unified = unifiedGraph(factSheetId);

        Map<String, Integer> assignments = communityAssignments(factSheetId, algo, unified);

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
        Map<String, Double> pageRank = pageRank(factSheetId, unified);

        List<Map<String, Object>> members = communityMembers.stream()
                .sorted((a, b) -> Double.compare(
                        pageRank.getOrDefault(b, 0.0),
                        pageRank.getOrDefault(a, 0.0)))
                .limit(limit)
                .map(id -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("nodeId", id);
                    putNodeDetails(m, id, unified, 120);
                    m.put("pageRank", Math.round(pageRank.getOrDefault(id, 0.0) * 10000.0) / 10000.0);
                    return m;
                })
                .collect(Collectors.toList());

        // Entity type summary for community
        Map<String, Integer> entityTypeSummary = new LinkedHashMap<>();
        for (String id : communityMembers) {
            resolveNode(id).ifPresent(n -> {
                if (n.getNodeType() == NodeLevel.ENTITY) {
                    graphService.getEntityMentionsForNode(n)
                            .stream()
                            .filter(em -> em.getEntityType() != null)
                            .forEach(em -> entityTypeSummary.merge(
                                    em.getEntityType().toUpperCase(), 1, Integer::sum));
                }
            });
        }

        // Total communities count
        long totalCommunities = assignments.values().stream().distinct().count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", sourceLabel(unified));
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

    private UnifiedGraph unifiedGraph(Long factSheetId) {
        if (unifiedGraphBridge == null || factSheetId == null) {
            return null;
        }
        try {
            return unifiedGraphBridge.export(factSheetId);
        } catch (RuntimeException ex) {
            log.warn("Falling back to live graph-localization algorithms; unified export failed for factSheet={}",
                    factSheetId, ex);
            return null;
        }
    }

    private String sourceLabel(UnifiedGraph unified) {
        return unified != null ? "unified_graph" : "knowledge_graph_service";
    }

    private Map<Integer, List<String>> bfsTraversal(Long factSheetId, String startNodeId, int maxDepth,
                                                    UnifiedGraph unified) {
        return unified != null
                ? algorithmService.bfsTraversalGraph(unified, startNodeId, maxDepth)
                : algorithmService.bfsTraversal(factSheetId, startNodeId, maxDepth);
    }

    private Map<String, Double> centralityScores(String metric, Long factSheetId, UnifiedGraph unified) {
        switch (metric != null ? metric.toLowerCase() : "degree") {
            case "pagerank":
                return pageRank(factSheetId, unified);
            case "betweenness":
                return betweennessCentrality(factSheetId, unified);
            default:
                return degreeCentrality(factSheetId, unified);
        }
    }

    private Map<String, Double> pageRank(Long factSheetId, UnifiedGraph unified) {
        return unified != null
                ? algorithmService.pageRankGraph(unified, 0.85, 50, 1e-6)
                : algorithmService.pageRank(factSheetId, 0.85, 50, 1e-6);
    }

    private Map<String, Double> degreeCentrality(Long factSheetId, UnifiedGraph unified) {
        return unified != null
                ? algorithmService.degreeCentralityGraph(unified, DegreeCentrality.Type.TOTAL)
                : algorithmService.degreeCentrality(factSheetId, DegreeCentrality.Type.TOTAL);
    }

    private Map<String, Double> betweennessCentrality(Long factSheetId, UnifiedGraph unified) {
        return unified != null
                ? algorithmService.betweennessCentralityGraph(unified, 500, 42L)
                : algorithmService.betweennessCentrality(factSheetId, 500, 42L);
    }

    private double jaccardSimilarity(Long factSheetId, String nodeAId, String nodeBId, UnifiedGraph unified) {
        return unified != null
                ? algorithmService.jaccardSimilarityGraph(unified, nodeAId, nodeBId)
                : algorithmService.jaccardSimilarity(factSheetId, nodeAId, nodeBId);
    }

    private Map<String, Integer> communityAssignments(Long factSheetId, String algorithm, UnifiedGraph unified) {
        if ("wcc".equals(algorithm)) {
            return unified != null
                    ? algorithmService.weaklyConnectedComponentsGraph(unified)
                    : algorithmService.weaklyConnectedComponents(factSheetId);
        }
        return unified != null
                ? algorithmService.louvainCommunitiesGraph(unified, 20)
                : algorithmService.louvainCommunities(factSheetId, 20);
    }

    private boolean matchesNodeType(String nodeId, NodeLevel nodeType, UnifiedGraph unified) {
        if (nodeType == null) return true;
        Optional<GraphNode> live = resolveNode(nodeId);
        if (live.isPresent()) {
            return live.get().getNodeType() == nodeType;
        }
        if (unified == null) return false;
        return unified.entity(nodeId)
                .map(entity -> entity.hasTypeMembership(nodeType.name()) || nodeType.name().equalsIgnoreCase(entity.type()))
                .orElse(false);
    }

    private void putNodeDetails(Map<String, Object> target, String nodeId, UnifiedGraph unified, int descriptionLimit) {
        Optional<GraphNode> live = resolveNode(nodeId);
        if (live.isPresent()) {
            GraphNode node = live.get();
            target.put("title", node.getTitle());
            target.put("nodeType", node.getNodeType().name());
            if (node.getDescription() != null) {
                target.put("description", StringUtils.truncate(node.getDescription(), descriptionLimit));
            }
            return;
        }
        if (unified == null) {
            return;
        }
        unified.entity(nodeId).ifPresent(entity -> {
            String label = entity.label();
            target.put("title", label == null || label.isBlank() ? entity.id() : label);
            if (entity.type() != null && !entity.type().isBlank()) {
                target.put("nodeType", entity.type());
            }
            Object description = entity.attributes().get("description");
            if (description != null && !description.toString().isBlank()) {
                target.put("description", StringUtils.truncate(description.toString(), descriptionLimit));
            }
        });
    }

    private int populateLiveProfileDistributions(Set<String> neighborhoodIds,
                                                 Map<String, Integer> nodeTypeDistribution,
                                                 Map<String, Integer> entityTypeDistribution,
                                                 Map<String, Integer> edgeTypeDistribution) {
        Set<String> countedEdges = new HashSet<>();
        for (String nodeId : neighborhoodIds) {
            Optional<GraphNode> nOpt = resolveNode(nodeId);
            if (nOpt.isPresent()) {
                GraphNode n = nOpt.get();
                nodeTypeDistribution.merge(n.getNodeType().name(), 1, Integer::sum);
                if (n.getNodeType() == NodeLevel.ENTITY) {
                    List<EntityMention> mentions = graphService.getEntityMentionsForNode(n);
                    for (EntityMention m : mentions) {
                        if (m.getEntityType() != null) {
                            entityTypeDistribution.merge(m.getEntityType().toUpperCase(), 1, Integer::sum);
                        }
                    }
                }
            }

            for (GraphEdge edge : graphService.getEdgesForNode(nodeId)) {
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
        return edgeTypeDistribution.values().stream().mapToInt(Integer::intValue).sum();
    }

    private int populateUnifiedProfileDistributions(Set<String> neighborhoodIds,
                                                    UnifiedGraph unified,
                                                    Map<String, Integer> nodeTypeDistribution,
                                                    Map<String, Integer> entityTypeDistribution,
                                                    Map<String, Integer> edgeTypeDistribution) {
        for (String nodeId : neighborhoodIds) {
            Optional<GraphNode> live = resolveNode(nodeId);
            if (live.isPresent()) {
                GraphNode node = live.get();
                nodeTypeDistribution.merge(node.getNodeType().name(), 1, Integer::sum);
                if (node.getNodeType() == NodeLevel.ENTITY) {
                    graphService.getEntityMentionsForNode(node).stream()
                            .filter(m -> m.getEntityType() != null)
                            .forEach(m -> entityTypeDistribution.merge(m.getEntityType().toUpperCase(), 1, Integer::sum));
                }
                continue;
            }

            unified.entity(nodeId).ifPresent(entity -> {
                String type = entity.type() == null || entity.type().isBlank() ? "UNKNOWN" : entity.type().toUpperCase();
                nodeTypeDistribution.merge(type, 1, Integer::sum);
                NodeLevel level = parseNodeLevel(entity.type());
                if (level == null || level == NodeLevel.ENTITY) {
                    entity.typeMemberships().stream()
                            .filter(t -> t != null && !t.isBlank())
                            .map(String::toUpperCase)
                            .filter(t -> parseNodeLevel(t) == null || "ENTITY".equals(t))
                            .forEach(t -> entityTypeDistribution.merge(t, 1, Integer::sum));
                }
            });
        }

        Set<String> countedRelations = new HashSet<>();
        for (String nodeId : neighborhoodIds) {
            for (GraphRelation relation : incidentRelations(unified, nodeId)) {
                String otherNode = otherNodeId(relation, nodeId);
                if (otherNode != null && neighborhoodIds.contains(otherNode) && countedRelations.add(relation.id())) {
                    String type = relation.type() == null || relation.type().isBlank() ? "UNKNOWN" : relation.type();
                    edgeTypeDistribution.merge(type, 1, Integer::sum);
                }
            }
        }
        return countedRelations.size();
    }

    private Map<String, Object> exploreUnifiedNeighborhood(String seedNodeId,
                                                            GraphNode liveSeed,
                                                            int depth,
                                                            int limit,
                                                            Set<String> entityTypes,
                                                            Set<EdgeType> edgeTypes,
                                                            double minWeight,
                                                            UnifiedGraph unified) {
        Map<String, Integer> nodeDepths = new LinkedHashMap<>();
        List<Map<String, Object>> edgeResults = new ArrayList<>();
        Set<String> countedRelations = new HashSet<>();
        Queue<String> frontier = new LinkedList<>();

        nodeDepths.put(seedNodeId, 0);
        frontier.add(seedNodeId);

        while (!frontier.isEmpty() && nodeDepths.size() < limit) {
            String currentId = frontier.poll();
            int currentDepth = nodeDepths.get(currentId);
            if (currentDepth >= depth) continue;

            for (GraphRelation relation : incidentRelations(unified, currentId)) {
                if (!matchesRelationType(relation, edgeTypes)) continue;
                if (relation.weight() < minWeight) continue;

                String neighborId = otherNodeId(relation, currentId);
                if (neighborId == null || !passesUnifiedEntityTypeFilter(neighborId, entityTypes, unified)) {
                    continue;
                }

                if (countedRelations.add(relation.id())) {
                    edgeResults.add(formatUnifiedRelation(relation));
                }

                if (!nodeDepths.containsKey(neighborId) && nodeDepths.size() < limit) {
                    nodeDepths.put(neighborId, currentDepth + 1);
                    frontier.add(neighborId);
                }
            }
        }

        List<Map<String, Object>> nodeResults = nodeDepths.entrySet().stream()
                .map(e -> formatUnifiedNode(e.getKey(), e.getKey().equals(seedNodeId) ? liveSeed : null,
                        e.getValue(), unified))
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", sourceLabel(unified));
        result.put("seedNode", seedNodeId);
        result.put("seedTitle", nodeTitle(seedNodeId, liveSeed, unified));
        result.put("maxDepth", depth);
        result.put("nodeCount", nodeResults.size());
        result.put("edgeCount", edgeResults.size());
        result.put("nodes", nodeResults);
        result.put("edges", edgeResults);
        return result;
    }

    private Map<String, Object> constrainedUnifiedPathSearch(String fromNodeId,
                                                             String toNodeId,
                                                             GraphNode liveFrom,
                                                             GraphNode liveTo,
                                                             Set<EdgeType> allowedTypes,
                                                             Set<String> requiredTypes,
                                                             int maxLen,
                                                             UnifiedGraph unified) {
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

            for (GraphRelation relation : incidentRelations(unified, current)) {
                if (!matchesRelationType(relation, allowedTypes)) continue;

                String neighborId = otherNodeId(relation, current);
                if (neighborId == null || depthMap.containsKey(neighborId)) continue;

                parentMap.put(neighborId, current);
                parentEdgeMap.put(neighborId, relation.type() + "(" + String.format("%.2f", relation.weight()) + ")");
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
                    "source", sourceLabel(unified),
                    "from", fromNodeId,
                    "to", toNodeId,
                    "found", false,
                    "message", "No path found within " + maxLen + " hops with the given constraints"
            );
        }

        List<String> pathIds = new ArrayList<>();
        List<Map<String, Object>> pathNodes = new ArrayList<>();
        List<String> pathEdgeLabels = new ArrayList<>();
        String cursor = toNodeId;
        while (cursor != null) {
            pathIds.add(0, cursor);
            GraphNode live = cursor.equals(fromNodeId) ? liveFrom : cursor.equals(toNodeId) ? liveTo : null;
            pathNodes.add(0, formatUnifiedNode(cursor, live, null, unified));
            if (parentEdgeMap.containsKey(cursor)) {
                pathEdgeLabels.add(0, parentEdgeMap.get(cursor));
            }
            cursor = parentMap.get(cursor);
        }

        if (requiredTypes != null && !requiredTypes.isEmpty()) {
            Set<String> intermediateTypes = new HashSet<>();
            for (int i = 1; i < pathIds.size() - 1; i++) {
                collectTypeNames(pathIds.get(i), unified, intermediateTypes);
            }
            if (!intermediateTypes.containsAll(requiredTypes)) {
                return Map.of(
                        "source", sourceLabel(unified),
                        "from", fromNodeId,
                        "to", toNodeId,
                        "found", false,
                        "message", "Path found but missing required intermediate types: " + requiredTypes
                );
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", sourceLabel(unified));
        result.put("from", fromNodeId);
        result.put("to", toNodeId);
        result.put("found", true);
        result.put("pathLength", pathNodes.size() - 1);
        result.put("path", pathNodes);
        result.put("edgeLabels", pathEdgeLabels);
        return result;
    }

    private List<GraphRelation> incidentRelations(UnifiedGraph unified, String nodeId) {
        Map<String, GraphRelation> relations = new LinkedHashMap<>();
        for (GraphRelation relation : unified.outgoing(nodeId)) {
            relations.put(relation.id(), relation);
        }
        for (GraphRelation relation : unified.incoming(nodeId)) {
            relations.put(relation.id(), relation);
        }
        return new ArrayList<>(relations.values());
    }

    private String otherNodeId(GraphRelation relation, String nodeId) {
        if (relation.sourceId().equals(nodeId)) return relation.targetId();
        if (relation.targetId().equals(nodeId)) return relation.sourceId();
        return null;
    }

    private boolean matchesRelationType(GraphRelation relation, Set<EdgeType> edgeTypes) {
        if (edgeTypes == null) return true;
        for (EdgeType edgeType : edgeTypes) {
            if (edgeType.name().equalsIgnoreCase(relation.type())) {
                return true;
            }
        }
        return false;
    }

    private boolean passesUnifiedEntityTypeFilter(String nodeId, Set<String> entityTypes, UnifiedGraph unified) {
        if (entityTypes == null || entityTypes.isEmpty()) return true;
        return unified.entity(nodeId)
                .map(entity -> {
                    Set<String> typeNames = new HashSet<>();
                    collectTypeNames(nodeId, unified, typeNames);
                    if (typeNames.stream().anyMatch(entityTypes::contains)) {
                        return true;
                    }
                    NodeLevel level = parseNodeLevel(entity.type());
                    return level != null && level != NodeLevel.ENTITY;
                })
                .orElse(false);
    }

    private Map<String, Object> formatUnifiedNode(String nodeId, GraphNode liveNode, Integer depth, UnifiedGraph unified) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("nodeId", nodeId);
        if (liveNode != null) {
            node.put("title", liveNode.getTitle() != null ? liveNode.getTitle() : "Untitled");
            node.put("nodeType", liveNode.getNodeType().name());
            if (depth != null) node.put("depth", depth);
            if (liveNode.getDescription() != null) {
                node.put("description", StringUtils.truncate(liveNode.getDescription(), 150));
            }
            if (liveNode.getEdgeCount() > 0) {
                node.put("edgeCount", liveNode.getEdgeCount());
            }
            return node;
        }
        putNodeDetails(node, nodeId, unified, 150);
        if (depth != null) node.put("depth", depth);
        if (!node.containsKey("title")) node.put("title", "Untitled");
        if (!node.containsKey("nodeType")) node.put("nodeType", "UNKNOWN");
        node.put("edgeCount", incidentRelations(unified, nodeId).size());
        return node;
    }

    private Map<String, Object> formatUnifiedRelation(GraphRelation relation) {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("edgeId", relation.id());
        edge.put("edgeType", relation.type());
        edge.put("from", relation.sourceId());
        edge.put("to", relation.targetId());
        edge.put("weight", relation.weight());
        edge.put("confidence", relation.confidence());
        Object label = relation.attributes().get("label");
        if (label != null) edge.put("label", label.toString());
        Object description = relation.attributes().get("description");
        if (description != null) edge.put("description", StringUtils.truncate(description.toString(), 100));
        return edge;
    }

    private String nodeTitle(String nodeId, GraphNode liveNode, UnifiedGraph unified) {
        if (liveNode != null && liveNode.getTitle() != null) {
            return liveNode.getTitle();
        }
        return unified.entity(nodeId)
                .map(entity -> entity.label() == null || entity.label().isBlank() ? entity.id() : entity.label())
                .orElse("Untitled");
    }

    private void collectTypeNames(String nodeId, UnifiedGraph unified, Set<String> sink) {
        Optional<GraphNode> live = resolveNode(nodeId);
        if (live.isPresent() && live.get().getNodeType() != null) {
            sink.add(live.get().getNodeType().name());
        }
        unified.entity(nodeId).ifPresent(entity -> {
            if (entity.type() != null && !entity.type().isBlank()) {
                sink.add(entity.type().toUpperCase());
            }
            entity.typeMemberships().stream()
                    .filter(type -> type != null && !type.isBlank())
                    .map(String::toUpperCase)
                    .forEach(sink::add);
        });
    }

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

    /**
     * Resolve an edge endpoint to the REAL store node when the embedded one is hollow.
     * {@code GraphEdge.getSourceNode()/getTargetNode()} synthesize an id-only node when the store
     * didn't embed one (matrix edges never do) — its null nodeType/title silently disabled the
     * entity-type filters and produced untitled results. Cached so each endpoint is looked up once.
     */
    private GraphNode resolveEndpoint(String nodeId, GraphNode embedded, Map<String, GraphNode> cache) {
        if (embedded != null && !embedded.isHollow()) {
            return embedded;
        }
        if (nodeId == null) {
            return embedded;
        }
        return cache.computeIfAbsent(nodeId, id -> graphService.getNode(id).orElse(embedded));
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
        for (String id : nodeIds) {
            resolveNode(id).ifPresent(n -> {
                if (n.getNodeType() == NodeLevel.ENTITY) {
                    graphService.getEntityMentionsForNode(n)
                            .stream()
                            .filter(m -> m.getEntityType() != null)
                            .forEach(m -> typeToNames.computeIfAbsent(m.getEntityType().toUpperCase(),
                                    k -> new HashSet<>()).add(m.getEntityName()));
                }
            });
        }
        return typeToNames;
    }

    private List<String> findBridgeNodes(Set<String> neighborhoodIds, Long factSheetId, UnifiedGraph unified) {
        if (neighborhoodIds.size() < 3) return List.of();

        AdjacencyView view = unified != null ? algorithmService.viewGraph(unified) : algorithmService.view(factSheetId);
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
