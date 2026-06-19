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

import ai.kompile.graph.algorithms.JaccardNodeSimilarity;
import ai.kompile.graph.algorithms.service.GraphAlgorithmService;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.graph.algorithms.community.CommunitySummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP tools for community detection and exploration.
 * Exposes Louvain and WCC community detection, per-community member listing,
 * LLM-generated community summaries, and a node's local community.
 */
@Component
@ConditionalOnBean(GraphAlgorithmService.class)
public class GraphCommunityTool {

    private static final Logger log = LoggerFactory.getLogger(GraphCommunityTool.class);

    private final GraphAlgorithmService algorithmService;
    private final KnowledgeGraphService graphService;

    // ═══════════════════════════════════════════════════════════════════════════
    // INPUT RECORDS
    // ═══════════════════════════════════════════════════════════════════════════

    public record DetectCommunitiesInput(
            Long factSheetId,
            String algorithm,
            Integer maxIterations
    ) {}

    public record CommunityMembersInput(
            Long factSheetId,
            Integer communityId,
            String algorithm,
            Integer maxResults
    ) {}

    public record SummarizeCommunitiesInput(
            Long factSheetId,
            String algorithm,
            Integer maxNodesPerPrompt
    ) {}

    public record NodeCommunityInput(
            String nodeId,
            Long factSheetId,
            String algorithm
    ) {}

    public record SimilarNodePairsInput(
            Long factSheetId,
            Integer topK,
            Double minThreshold
    ) {}

    @Autowired
    public GraphCommunityTool(GraphAlgorithmService algorithmService,
                              KnowledgeGraphService graphService) {
        this.algorithmService = algorithmService;
        this.graphService = graphService;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TOOLS
    // ═══════════════════════════════════════════════════════════════════════════

    @Tool(name = "graph_detect_communities",
          description = "Run community detection on the knowledge graph. "
                  + "Algorithms: 'louvain' (default, modularity-based) or 'wcc' (weakly connected components). "
                  + "Returns a map of community IDs to their member count. "
                  + "Scope to a factSheetId for project-level community detection.")
    public Map<String, Object> detectCommunities(DetectCommunitiesInput input) {
        log.info("Community detection: algorithm={} factSheet={}", input.algorithm(), input.factSheetId());

        try {
            String algo = input.algorithm() != null ? input.algorithm().toLowerCase() : "louvain";
            Map<String, Integer> assignments;

            if ("wcc".equals(algo)) {
                assignments = algorithmService.weaklyConnectedComponents(input.factSheetId());
            } else {
                int maxIter = input.maxIterations() != null && input.maxIterations() > 0
                        ? input.maxIterations() : 20;
                assignments = algorithmService.louvainCommunities(input.factSheetId(), maxIter);
            }

            // Aggregate: community ID -> count
            Map<Integer, Long> communitySizes = assignments.values().stream()
                    .collect(Collectors.groupingBy(c -> c, Collectors.counting()));

            // Sort by size descending
            List<Map<String, Object>> communities = communitySizes.entrySet().stream()
                    .sorted(Map.Entry.<Integer, Long>comparingByValue().reversed())
                    .limit(100)
                    .map(e -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("communityId", e.getKey());
                        m.put("memberCount", e.getValue());
                        return m;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("algorithm", algo);
            result.put("totalNodes", assignments.size());
            result.put("communityCount", communitySizes.size());
            result.put("communities", communities);
            return result;

        } catch (Exception e) {
            log.error("Community detection failed: {}", e.getMessage(), e);
            return Map.of("error", "Community detection failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_community_members",
          description = "List the members (nodes) of a specific community. "
                  + "First run graph_detect_communities to get community IDs, "
                  + "then use a communityId here to see the actual nodes in that community. "
                  + "Returns node IDs, titles, and types for each community member.")
    public Map<String, Object> getCommunityMembers(CommunityMembersInput input) {
        if (input.communityId() == null) {
            return Map.of("error", "communityId is required", "members", List.of());
        }

        int limit = GraphSearchTool.clampLimit(input.maxResults(), 50);

        try {
            String algo = input.algorithm() != null ? input.algorithm().toLowerCase() : "louvain";
            Map<String, Integer> assignments;

            if ("wcc".equals(algo)) {
                assignments = algorithmService.weaklyConnectedComponents(input.factSheetId());
            } else {
                assignments = algorithmService.louvainCommunities(input.factSheetId(), 20);
            }

            List<String> memberNodeIds = assignments.entrySet().stream()
                    .filter(e -> e.getValue().equals(input.communityId()))
                    .map(Map.Entry::getKey)
                    .limit(limit)
                    .collect(Collectors.toList());

            // Resolve node details
            List<GraphNode> nodes = graphService.getNodesByIds(memberNodeIds);
            List<Map<String, Object>> members = nodes.stream()
                    .map(n -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("nodeId", n.getNodeId());
                        m.put("title", n.getTitle() != null ? n.getTitle() : "Untitled");
                        m.put("type", n.getNodeType().name());
                        m.put("connections", n.getEdgeCount());
                        return m;
                    })
                    .collect(Collectors.toList());

            long totalMembers = assignments.values().stream()
                    .filter(v -> v.equals(input.communityId()))
                    .count();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("communityId", input.communityId());
            result.put("totalMembers", totalMembers);
            result.put("returnedMembers", members.size());
            result.put("members", members);
            return result;

        } catch (Exception e) {
            log.error("Community members failed: {}", e.getMessage(), e);
            return Map.of("error", "Failed: " + e.getMessage(), "members", List.of());
        }
    }

    @Tool(name = "graph_summarize_communities",
          description = "Generate LLM-powered summaries for each detected community. "
                  + "Each summary describes the theme and key entities within a community. "
                  + "Uses 'louvain' or 'wcc' algorithm. Requires an active LLM for summarization.")
    public Map<String, Object> summarizeCommunities(SummarizeCommunitiesInput input) {
        log.info("Summarize communities: factSheet={} algorithm={}",
                input.factSheetId(), input.algorithm());

        try {
            String algo = input.algorithm() != null ? input.algorithm() : "louvain";
            int maxNodes = input.maxNodesPerPrompt() != null && input.maxNodesPerPrompt() > 0
                    ? input.maxNodesPerPrompt() : 20;

            List<CommunitySummary> summaries = algorithmService.summarizeCommunities(
                    input.factSheetId(), algo, maxNodes);

            List<Map<String, Object>> summaryList = summaries.stream()
                    .map(s -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("communityId", s.communityId());
                        m.put("memberCount", s.nodeIds() != null ? s.nodeIds().size() : 0);
                        m.put("summary", s.summary());
                        m.put("nodeIds", s.nodeIds());
                        return m;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("algorithm", algo);
            result.put("communityCount", summaryList.size());
            result.put("summaries", summaryList);
            return result;

        } catch (Exception e) {
            log.error("Summarize communities failed: {}", e.getMessage(), e);
            return Map.of("error", "Summarization failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_node_community",
          description = "Find which community a specific node belongs to and list its co-members. "
                  + "Runs community detection and returns the community ID plus other nodes "
                  + "in the same community. Useful for finding a node's neighborhood cluster.")
    public Map<String, Object> getNodeCommunity(NodeCommunityInput input) {
        if (input.nodeId() == null || input.nodeId().isBlank()) {
            return Map.of("error", "nodeId is required");
        }

        try {
            String algo = input.algorithm() != null ? input.algorithm().toLowerCase() : "louvain";
            Map<String, Integer> assignments;

            if ("wcc".equals(algo)) {
                assignments = algorithmService.weaklyConnectedComponents(input.factSheetId());
            } else {
                assignments = algorithmService.louvainCommunities(input.factSheetId(), 20);
            }

            Integer communityId = assignments.get(input.nodeId());
            if (communityId == null) {
                return Map.of("error", "Node not found in graph: " + input.nodeId());
            }

            // Find co-members
            List<String> coMembers = assignments.entrySet().stream()
                    .filter(e -> e.getValue().equals(communityId))
                    .filter(e -> !e.getKey().equals(input.nodeId()))
                    .map(Map.Entry::getKey)
                    .limit(30)
                    .collect(Collectors.toList());

            List<GraphNode> nodes = graphService.getNodesByIds(coMembers);
            List<Map<String, Object>> memberList = nodes.stream()
                    .map(n -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("nodeId", n.getNodeId());
                        m.put("title", n.getTitle() != null ? n.getTitle() : "Untitled");
                        m.put("type", n.getNodeType().name());
                        return m;
                    })
                    .collect(Collectors.toList());

            long totalInCommunity = assignments.values().stream()
                    .filter(v -> v.equals(communityId))
                    .count();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("nodeId", input.nodeId());
            result.put("communityId", communityId);
            result.put("algorithm", algo);
            result.put("communitySize", totalInCommunity);
            result.put("coMembers", memberList);
            return result;

        } catch (Exception e) {
            log.error("Node community failed: {}", e.getMessage(), e);
            return Map.of("error", "Failed: " + e.getMessage());
        }
    }

    @Tool(name = "graph_similar_pairs",
          description = "Find the most structurally similar node pairs in the graph using Jaccard similarity. "
                  + "Returns pairs of nodes that share many of the same neighbors, "
                  + "indicating they play similar roles in the graph. "
                  + "Set minThreshold (0.0 to 1.0) to control minimum similarity.")
    public Map<String, Object> findSimilarPairs(SimilarNodePairsInput input) {
        log.info("Similar pairs: topK={} threshold={}", input.topK(), input.minThreshold());

        try {
            int topK = input.topK() != null && input.topK() > 0 ? Math.min(input.topK(), 50) : 20;
            double threshold = input.minThreshold() != null ? input.minThreshold() : 0.1;

            List<JaccardNodeSimilarity.SimilarityPair> pairs =
                    algorithmService.jaccardTopK(input.factSheetId(), topK, threshold);

            // Resolve node titles
            Set<String> allNodeIds = new HashSet<>();
            pairs.forEach(p -> {
                allNodeIds.add(p.nodeA());
                allNodeIds.add(p.nodeB());
            });
            Map<String, String> idToTitle = graphService.getNodesByIds(new ArrayList<>(allNodeIds))
                    .stream()
                    .collect(Collectors.toMap(
                            GraphNode::getNodeId,
                            n -> n.getTitle() != null ? n.getTitle() : "Untitled",
                            (a, b) -> a
                    ));

            List<Map<String, Object>> pairList = pairs.stream()
                    .map(p -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("nodeA", p.nodeA());
                        m.put("titleA", idToTitle.getOrDefault(p.nodeA(), "Unknown"));
                        m.put("nodeB", p.nodeB());
                        m.put("titleB", idToTitle.getOrDefault(p.nodeB(), "Unknown"));
                        m.put("jaccardSimilarity", p.score());
                        return m;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("pairCount", pairList.size());
            result.put("minThreshold", threshold);
            result.put("pairs", pairList);
            return result;

        } catch (Exception e) {
            log.error("Similar pairs failed: {}", e.getMessage(), e);
            return Map.of("error", "Failed: " + e.getMessage());
        }
    }
}
