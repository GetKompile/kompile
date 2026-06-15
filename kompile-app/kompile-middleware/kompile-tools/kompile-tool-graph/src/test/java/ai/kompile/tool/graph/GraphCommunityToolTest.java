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
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import ai.kompile.graph.algorithms.community.CommunitySummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GraphCommunityToolTest {

    @Mock private GraphAlgorithmService algorithmService;
    @Mock private GraphNodeRepository nodeRepository;

    private GraphCommunityTool tool;

    @BeforeEach
    void setUp() {
        tool = new GraphCommunityTool(algorithmService, nodeRepository);
    }

    @Test
    void detectCommunities_louvainDefault_returnsSortedCommunities() {
        Map<String, Integer> assignments = new LinkedHashMap<>();
        assignments.put("n1", 0);
        assignments.put("n2", 0);
        assignments.put("n3", 0);
        assignments.put("n4", 1);
        assignments.put("n5", 1);
        when(algorithmService.louvainCommunities(isNull(), eq(20)))
                .thenReturn(assignments);

        var result = tool.detectCommunities(
                new GraphCommunityTool.DetectCommunitiesInput(null, null, null));

        assertEquals("louvain", result.get("algorithm"));
        assertEquals(5, result.get("totalNodes"));
        assertEquals(2, result.get("communityCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> communities = (List<Map<String, Object>>) result.get("communities");
        // Community 0 has 3 members, should be first
        assertEquals(0, communities.get(0).get("communityId"));
        assertEquals(3L, communities.get(0).get("memberCount"));
    }

    @Test
    void detectCommunities_wcc_callsCorrectAlgorithm() {
        when(algorithmService.weaklyConnectedComponents(isNull()))
                .thenReturn(Map.of("n1", 0));

        var result = tool.detectCommunities(
                new GraphCommunityTool.DetectCommunitiesInput(null, "wcc", null));

        assertEquals("wcc", result.get("algorithm"));
        verify(algorithmService).weaklyConnectedComponents(isNull());
        verify(algorithmService, never()).louvainCommunities(any(), anyInt());
    }

    @Test
    void detectCommunities_customIterations_passedToLouvain() {
        when(algorithmService.louvainCommunities(isNull(), eq(50)))
                .thenReturn(Map.of("n1", 0));

        tool.detectCommunities(
                new GraphCommunityTool.DetectCommunitiesInput(null, "louvain", 50));

        verify(algorithmService).louvainCommunities(isNull(), eq(50));
    }

    @Test
    void communityMembers_missingId_returnsError() {
        var result = tool.getCommunityMembers(
                new GraphCommunityTool.CommunityMembersInput(null, null, null, null));
        assertEquals("communityId is required", result.get("error"));
    }

    @Test
    void communityMembers_returnsResolvedNodes() {
        Map<String, Integer> assignments = Map.of("n1", 0, "n2", 0, "n3", 1);
        when(algorithmService.louvainCommunities(isNull(), eq(20)))
                .thenReturn(assignments);
        when(nodeRepository.findByNodeIdIn(anyList()))
                .thenReturn(List.of(
                        GraphSearchToolTest.createNode("n1", "Node 1", NodeLevel.ENTITY),
                        GraphSearchToolTest.createNode("n2", "Node 2", NodeLevel.DOCUMENT)
                ));

        var result = tool.getCommunityMembers(
                new GraphCommunityTool.CommunityMembersInput(null, 0, null, null));

        assertEquals(0, result.get("communityId"));
        assertEquals(2L, result.get("totalMembers"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> members = (List<Map<String, Object>>) result.get("members");
        assertEquals(2, members.size());
    }

    @Test
    void summarizeCommunities_returnsSummaryList() {
        List<CommunitySummary> summaries = List.of(
                new CommunitySummary(0, List.of("n1", "n2"), "A cluster about AI", Instant.now()),
                new CommunitySummary(1, List.of("n3"), "A cluster about databases", Instant.now())
        );
        when(algorithmService.summarizeCommunities(isNull(), eq("louvain"), eq(20)))
                .thenReturn(summaries);

        var result = tool.summarizeCommunities(
                new GraphCommunityTool.SummarizeCommunitiesInput(null, null, null));

        assertEquals("louvain", result.get("algorithm"));
        assertEquals(2, result.get("communityCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> summaryList = (List<Map<String, Object>>) result.get("summaries");
        assertEquals("A cluster about AI", summaryList.get(0).get("summary"));
        assertEquals(2, summaryList.get(0).get("memberCount"));
    }

    @Test
    void nodeCommunity_missingNodeId_returnsError() {
        var result = tool.getNodeCommunity(
                new GraphCommunityTool.NodeCommunityInput("", null, null));
        assertEquals("nodeId is required", result.get("error"));
    }

    @Test
    void nodeCommunity_nodeNotInGraph_returnsError() {
        when(algorithmService.louvainCommunities(isNull(), eq(20)))
                .thenReturn(Map.of("other", 0));

        var result = tool.getNodeCommunity(
                new GraphCommunityTool.NodeCommunityInput("missing", null, null));
        assertTrue(result.get("error").toString().contains("missing"));
    }

    @Test
    void nodeCommunity_findsCoMembers() {
        Map<String, Integer> assignments = new LinkedHashMap<>();
        assignments.put("n1", 0);
        assignments.put("n2", 0);
        assignments.put("n3", 1);
        when(algorithmService.louvainCommunities(isNull(), eq(20)))
                .thenReturn(assignments);
        when(nodeRepository.findByNodeIdIn(anyList()))
                .thenReturn(List.of(
                        GraphSearchToolTest.createNode("n2", "Co-member", NodeLevel.ENTITY)
                ));

        var result = tool.getNodeCommunity(
                new GraphCommunityTool.NodeCommunityInput("n1", null, null));

        assertEquals("n1", result.get("nodeId"));
        assertEquals(0, result.get("communityId"));
        assertEquals(2L, result.get("communitySize"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> coMembers = (List<Map<String, Object>>) result.get("coMembers");
        assertEquals(1, coMembers.size());
    }

    @Test
    void similarPairs_returnsPairsWithTitles() {
        List<JaccardNodeSimilarity.SimilarityPair> pairs = List.of(
                new JaccardNodeSimilarity.SimilarityPair("a", "b", 0.8)
        );
        when(algorithmService.jaccardTopK(isNull(), eq(20), eq(0.1)))
                .thenReturn(pairs);
        when(nodeRepository.findByNodeIdIn(anyList()))
                .thenReturn(List.of(
                        GraphSearchToolTest.createNode("a", "Alpha", NodeLevel.ENTITY),
                        GraphSearchToolTest.createNode("b", "Beta", NodeLevel.ENTITY)
                ));

        var result = tool.findSimilarPairs(
                new GraphCommunityTool.SimilarNodePairsInput(null, null, null));

        assertEquals(1, result.get("pairCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pairList = (List<Map<String, Object>>) result.get("pairs");
        assertEquals("Alpha", pairList.get(0).get("titleA"));
        assertEquals("Beta", pairList.get(0).get("titleB"));
        assertEquals(0.8, pairList.get(0).get("jaccardSimilarity"));
    }

    @Test
    void similarPairs_respectsTopKAndThreshold() {
        when(algorithmService.jaccardTopK(isNull(), eq(5), eq(0.3)))
                .thenReturn(List.of());
        when(nodeRepository.findByNodeIdIn(anyList())).thenReturn(List.of());

        var result = tool.findSimilarPairs(
                new GraphCommunityTool.SimilarNodePairsInput(null, 5, 0.3));

        assertEquals(0, result.get("pairCount"));
        assertEquals(0.3, result.get("minThreshold"));
    }
}
