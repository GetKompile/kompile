/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.tool.graphlocalization;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GraphLocalizationToolImplTest {

    @Mock private GraphLocalizationService localizationService;

    private GraphLocalizationToolImpl tool;

    @BeforeEach
    void setUp() {
        tool = new GraphLocalizationToolImpl(localizationService, null, null);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EXPLORE NEIGHBORHOOD
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void exploreNeighborhood_nullSeedNodeId_returnsError() {
        var input = new GraphLocalizationToolImpl.NeighborhoodExploreInput(
                null, 2, 50, null, null, null, null);
        Map<String, Object> result = tool.exploreNeighborhood(input);
        assertEquals("seedNodeId is required", result.get("error"));
        verifyNoInteractions(localizationService);
    }

    @Test
    void exploreNeighborhood_blankSeedNodeId_returnsError() {
        var input = new GraphLocalizationToolImpl.NeighborhoodExploreInput(
                "   ", 2, 50, null, null, null, null);
        Map<String, Object> result = tool.exploreNeighborhood(input);
        assertEquals("seedNodeId is required", result.get("error"));
        verifyNoInteractions(localizationService);
    }

    @Test
    void exploreNeighborhood_delegatesToService() {
        Map<String, Object> expected = Map.of("nodeCount", 5, "edgeCount", 3);
        when(localizationService.exploreNeighborhood(
                eq("node1"), eq(3), eq(100),
                eq(List.of("PERSON")), eq(List.of("SHARED_ENTITY")),
                eq(0.5), eq(42L)))
                .thenReturn(expected);

        var input = new GraphLocalizationToolImpl.NeighborhoodExploreInput(
                "node1", 3, 100, List.of("PERSON"), List.of("SHARED_ENTITY"), 0.5, 42L);
        Map<String, Object> result = tool.exploreNeighborhood(input);

        assertEquals(5, result.get("nodeCount"));
        assertEquals(3, result.get("edgeCount"));
    }

    @Test
    void exploreNeighborhood_defaultsApplied() {
        when(localizationService.exploreNeighborhood(
                eq("node1"), eq(2), eq(50), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(Map.of());

        var input = new GraphLocalizationToolImpl.NeighborhoodExploreInput(
                "node1", null, null, null, null, null, null);
        tool.exploreNeighborhood(input);

        verify(localizationService).exploreNeighborhood("node1", 2, 50, null, null, null, null);
    }

    @Test
    void exploreNeighborhood_serviceException_returnsError() {
        when(localizationService.exploreNeighborhood(anyString(), anyInt(), anyInt(),
                any(), any(), any(), any()))
                .thenThrow(new RuntimeException("DB down"));

        var input = new GraphLocalizationToolImpl.NeighborhoodExploreInput(
                "node1", 2, 50, null, null, null, null);
        Map<String, Object> result = tool.exploreNeighborhood(input);

        assertTrue(((String) result.get("error")).contains("DB down"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STRUCTURAL SEARCH
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void structuralSearch_delegatesToService() {
        Map<String, Object> expected = Map.of("matchCount", 3);
        when(localizationService.structuralSearch(
                eq(5), eq(10), eq(List.of("CITATION")), eq(List.of("PERSON")),
                eq("DOCUMENT"), eq("center"), eq(3), eq(20), eq(1L)))
                .thenReturn(expected);

        var input = new GraphLocalizationToolImpl.StructuralSearchInput(
                5, 10, List.of("CITATION"), List.of("PERSON"),
                "DOCUMENT", "center", 3, 20, 1L);
        Map<String, Object> result = tool.structuralSearch(input);

        assertEquals(3, result.get("matchCount"));
    }

    @Test
    void structuralSearch_defaultMaxResults() {
        when(localizationService.structuralSearch(
                isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(), eq(20), isNull()))
                .thenReturn(Map.of());

        var input = new GraphLocalizationToolImpl.StructuralSearchInput(
                null, null, null, null, null, null, null, null, null);
        tool.structuralSearch(input);

        verify(localizationService).structuralSearch(
                null, null, null, null, null, null, null, 20, null);
    }

    @Test
    void structuralSearch_serviceException_returnsError() {
        when(localizationService.structuralSearch(any(), any(), any(), any(),
                any(), any(), any(), anyInt(), any()))
                .thenThrow(new RuntimeException("Query timeout"));

        var input = new GraphLocalizationToolImpl.StructuralSearchInput(
                null, null, null, null, null, null, null, null, null);
        Map<String, Object> result = tool.structuralSearch(input);

        assertTrue(((String) result.get("error")).contains("Query timeout"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NEIGHBORHOOD PROFILE
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void profileNeighborhood_nullSeedNodeId_returnsError() {
        var input = new GraphLocalizationToolImpl.NeighborhoodProfileInput(null, 2, null);
        Map<String, Object> result = tool.profileNeighborhood(input);
        assertEquals("seedNodeId is required", result.get("error"));
        verifyNoInteractions(localizationService);
    }

    @Test
    void profileNeighborhood_blankSeedNodeId_returnsError() {
        var input = new GraphLocalizationToolImpl.NeighborhoodProfileInput("", 2, null);
        Map<String, Object> result = tool.profileNeighborhood(input);
        assertEquals("seedNodeId is required", result.get("error"));
        verifyNoInteractions(localizationService);
    }

    @Test
    void profileNeighborhood_delegatesToService() {
        Map<String, Object> expected = Map.of("totalNodes", 10, "density", 0.5);
        when(localizationService.profileNeighborhood("node1", 3, 42L))
                .thenReturn(expected);

        var input = new GraphLocalizationToolImpl.NeighborhoodProfileInput("node1", 3, 42L);
        Map<String, Object> result = tool.profileNeighborhood(input);

        assertEquals(10, result.get("totalNodes"));
        assertEquals(0.5, result.get("density"));
    }

    @Test
    void profileNeighborhood_defaultDepth() {
        when(localizationService.profileNeighborhood("node1", 2, null))
                .thenReturn(Map.of());

        var input = new GraphLocalizationToolImpl.NeighborhoodProfileInput("node1", null, null);
        tool.profileNeighborhood(input);

        verify(localizationService).profileNeighborhood("node1", 2, null);
    }

    @Test
    void profileNeighborhood_serviceException_returnsError() {
        when(localizationService.profileNeighborhood(anyString(), anyInt(), any()))
                .thenThrow(new RuntimeException("OOM"));

        var input = new GraphLocalizationToolImpl.NeighborhoodProfileInput("node1", 2, null);
        Map<String, Object> result = tool.profileNeighborhood(input);

        assertTrue(((String) result.get("error")).contains("OOM"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FIND HUBS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void findHubs_delegatesToService() {
        Map<String, Object> expected = Map.of("metric", "pagerank", "hubCount", 5);
        when(localizationService.findHubs("pagerank", "ENTITY", "scope1", 3, 5, 1L))
                .thenReturn(expected);

        var input = new GraphLocalizationToolImpl.FindHubsInput(
                "pagerank", "ENTITY", "scope1", 3, 5, 1L);
        Map<String, Object> result = tool.findHubs(input);

        assertEquals("pagerank", result.get("metric"));
        assertEquals(5, result.get("hubCount"));
    }

    @Test
    void findHubs_defaultTopK() {
        when(localizationService.findHubs(isNull(), isNull(), isNull(), isNull(), eq(10), isNull()))
                .thenReturn(Map.of());

        var input = new GraphLocalizationToolImpl.FindHubsInput(
                null, null, null, null, null, null);
        tool.findHubs(input);

        verify(localizationService).findHubs(null, null, null, null, 10, null);
    }

    @Test
    void findHubs_serviceException_returnsError() {
        when(localizationService.findHubs(any(), any(), any(), any(), anyInt(), any()))
                .thenThrow(new RuntimeException("Graph too large"));

        var input = new GraphLocalizationToolImpl.FindHubsInput(
                "degree", null, null, null, 10, null);
        Map<String, Object> result = tool.findHubs(input);

        assertTrue(((String) result.get("error")).contains("Graph too large"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONSTRAINED PATH
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void constrainedPath_nullFromNodeId_returnsError() {
        var input = new GraphLocalizationToolImpl.ConstrainedPathInput(
                null, "target", null, null, 4, false, null);
        Map<String, Object> result = tool.constrainedPath(input);
        assertEquals("fromNodeId is required", result.get("error"));
        verifyNoInteractions(localizationService);
    }

    @Test
    void constrainedPath_blankFromNodeId_returnsError() {
        var input = new GraphLocalizationToolImpl.ConstrainedPathInput(
                " ", "target", null, null, 4, false, null);
        Map<String, Object> result = tool.constrainedPath(input);
        assertEquals("fromNodeId is required", result.get("error"));
        verifyNoInteractions(localizationService);
    }

    @Test
    void constrainedPath_nullToNodeId_returnsError() {
        var input = new GraphLocalizationToolImpl.ConstrainedPathInput(
                "source", null, null, null, 4, false, null);
        Map<String, Object> result = tool.constrainedPath(input);
        assertEquals("toNodeId is required", result.get("error"));
        verifyNoInteractions(localizationService);
    }

    @Test
    void constrainedPath_blankToNodeId_returnsError() {
        var input = new GraphLocalizationToolImpl.ConstrainedPathInput(
                "source", "", null, null, 4, false, null);
        Map<String, Object> result = tool.constrainedPath(input);
        assertEquals("toNodeId is required", result.get("error"));
        verifyNoInteractions(localizationService);
    }

    @Test
    void constrainedPath_delegatesToService() {
        Map<String, Object> expected = Map.of("found", true, "pathLength", 2);
        when(localizationService.constrainedPathSearch(
                eq("a"), eq("b"), eq(List.of("CITATION")),
                eq(List.of("ENTITY")), eq(3), eq(true), eq(1L)))
                .thenReturn(expected);

        var input = new GraphLocalizationToolImpl.ConstrainedPathInput(
                "a", "b", List.of("CITATION"), List.of("ENTITY"), 3, true, 1L);
        Map<String, Object> result = tool.constrainedPath(input);

        assertEquals(true, result.get("found"));
        assertEquals(2, result.get("pathLength"));
    }

    @Test
    void constrainedPath_defaultsApplied() {
        when(localizationService.constrainedPathSearch(
                eq("a"), eq("b"), isNull(), isNull(), eq(4), eq(false), isNull()))
                .thenReturn(Map.of());

        var input = new GraphLocalizationToolImpl.ConstrainedPathInput(
                "a", "b", null, null, null, null, null);
        tool.constrainedPath(input);

        verify(localizationService).constrainedPathSearch("a", "b", null, null, 4, false, null);
    }

    @Test
    void constrainedPath_serviceException_returnsError() {
        when(localizationService.constrainedPathSearch(
                anyString(), anyString(), any(), any(), anyInt(), anyBoolean(), any()))
                .thenThrow(new RuntimeException("Cycle detected"));

        var input = new GraphLocalizationToolImpl.ConstrainedPathInput(
                "a", "b", null, null, 4, false, null);
        Map<String, Object> result = tool.constrainedPath(input);

        assertTrue(((String) result.get("error")).contains("Cycle detected"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // COMPARE NEIGHBORHOODS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void compareNeighborhoods_nullNodeAId_returnsError() {
        var input = new GraphLocalizationToolImpl.CompareNeighborhoodsInput(
                null, "b", 2, null);
        Map<String, Object> result = tool.compareNeighborhoods(input);
        assertEquals("nodeAId is required", result.get("error"));
        verifyNoInteractions(localizationService);
    }

    @Test
    void compareNeighborhoods_blankNodeAId_returnsError() {
        var input = new GraphLocalizationToolImpl.CompareNeighborhoodsInput(
                "", "b", 2, null);
        Map<String, Object> result = tool.compareNeighborhoods(input);
        assertEquals("nodeAId is required", result.get("error"));
        verifyNoInteractions(localizationService);
    }

    @Test
    void compareNeighborhoods_nullNodeBId_returnsError() {
        var input = new GraphLocalizationToolImpl.CompareNeighborhoodsInput(
                "a", null, 2, null);
        Map<String, Object> result = tool.compareNeighborhoods(input);
        assertEquals("nodeBId is required", result.get("error"));
        verifyNoInteractions(localizationService);
    }

    @Test
    void compareNeighborhoods_blankNodeBId_returnsError() {
        var input = new GraphLocalizationToolImpl.CompareNeighborhoodsInput(
                "a", " ", 2, null);
        Map<String, Object> result = tool.compareNeighborhoods(input);
        assertEquals("nodeBId is required", result.get("error"));
        verifyNoInteractions(localizationService);
    }

    @Test
    void compareNeighborhoods_delegatesToService() {
        Map<String, Object> expected = Map.of("jaccardSimilarity", 0.75, "sharedNodeCount", 5);
        when(localizationService.compareNeighborhoods("a", "b", 3, 1L))
                .thenReturn(expected);

        var input = new GraphLocalizationToolImpl.CompareNeighborhoodsInput("a", "b", 3, 1L);
        Map<String, Object> result = tool.compareNeighborhoods(input);

        assertEquals(0.75, result.get("jaccardSimilarity"));
        assertEquals(5, result.get("sharedNodeCount"));
    }

    @Test
    void compareNeighborhoods_defaultDepth() {
        when(localizationService.compareNeighborhoods("a", "b", 2, null))
                .thenReturn(Map.of());

        var input = new GraphLocalizationToolImpl.CompareNeighborhoodsInput("a", "b", null, null);
        tool.compareNeighborhoods(input);

        verify(localizationService).compareNeighborhoods("a", "b", 2, null);
    }

    @Test
    void compareNeighborhoods_serviceException_returnsError() {
        when(localizationService.compareNeighborhoods(anyString(), anyString(), anyInt(), any()))
                .thenThrow(new RuntimeException("Nodes are identical"));

        var input = new GraphLocalizationToolImpl.CompareNeighborhoodsInput("a", "b", 2, null);
        Map<String, Object> result = tool.compareNeighborhoods(input);

        assertTrue(((String) result.get("error")).contains("Nodes are identical"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FIND COMMUNITY
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void findCommunity_nullSeedNodeId_returnsError() {
        var input = new GraphLocalizationToolImpl.FindCommunityInput(null, "louvain", 30, null);
        Map<String, Object> result = tool.findCommunity(input);
        assertEquals("seedNodeId is required", result.get("error"));
        verifyNoInteractions(localizationService);
    }

    @Test
    void findCommunity_blankSeedNodeId_returnsError() {
        var input = new GraphLocalizationToolImpl.FindCommunityInput("  ", "louvain", 30, null);
        Map<String, Object> result = tool.findCommunity(input);
        assertEquals("seedNodeId is required", result.get("error"));
        verifyNoInteractions(localizationService);
    }

    @Test
    void findCommunity_delegatesToService() {
        Map<String, Object> expected = Map.of("algorithm", "wcc", "communitySize", 12);
        when(localizationService.findCommunity("node1", "wcc", 50, 1L))
                .thenReturn(expected);

        var input = new GraphLocalizationToolImpl.FindCommunityInput("node1", "wcc", 50, 1L);
        Map<String, Object> result = tool.findCommunity(input);

        assertEquals("wcc", result.get("algorithm"));
        assertEquals(12, result.get("communitySize"));
    }

    @Test
    void findCommunity_defaultMaxMembers() {
        when(localizationService.findCommunity("node1", null, 30, null))
                .thenReturn(Map.of());

        var input = new GraphLocalizationToolImpl.FindCommunityInput("node1", null, null, null);
        tool.findCommunity(input);

        verify(localizationService).findCommunity("node1", null, 30, null);
    }

    @Test
    void findCommunity_serviceException_returnsError() {
        when(localizationService.findCommunity(anyString(), any(), anyInt(), any()))
                .thenThrow(new RuntimeException("Algorithm diverged"));

        var input = new GraphLocalizationToolImpl.FindCommunityInput("node1", "louvain", 30, null);
        Map<String, Object> result = tool.findCommunity(input);

        assertTrue(((String) result.get("error")).contains("Algorithm diverged"));
    }
}
