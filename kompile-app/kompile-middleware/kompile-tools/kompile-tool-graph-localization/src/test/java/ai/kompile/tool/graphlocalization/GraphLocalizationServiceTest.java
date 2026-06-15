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

import ai.kompile.graph.algorithms.DegreeCentrality;
import ai.kompile.graph.algorithms.adjacency.AdjacencyView;
import ai.kompile.graph.algorithms.service.GraphAlgorithmService;
import ai.kompile.knowledgegraph.domain.*;
import ai.kompile.knowledgegraph.repository.*;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class GraphLocalizationServiceTest {

    @Mock private KnowledgeGraphService graphService;
    @Mock private GraphAlgorithmService algorithmService;
    @Mock private GraphNodeRepository nodeRepository;
    @Mock private GraphEdgeRepository edgeRepository;
    @Mock private EntityMentionRepository entityMentionRepository;

    private GraphLocalizationService service;

    @BeforeEach
    void setUp() {
        service = new GraphLocalizationService(graphService, algorithmService);
        // Inject optional repo dependencies via reflection (field-injected in production)
        setField(service, "nodeRepository", nodeRepository);
        setField(service, "edgeRepository", edgeRepository);
        setField(service, "entityMentionRepository", entityMentionRepository);
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private GraphNode mockNode(String nodeId, String title, NodeLevel type) {
        GraphNode node = mock(GraphNode.class);
        when(node.getNodeId()).thenReturn(nodeId);
        when(node.getTitle()).thenReturn(title);
        when(node.getNodeType()).thenReturn(type);
        when(node.getEdgeCount()).thenReturn(0);
        return node;
    }

    private GraphNode mockNodeWithDesc(String nodeId, String title, NodeLevel type, String desc) {
        GraphNode node = mockNode(nodeId, title, type);
        when(node.getDescription()).thenReturn(desc);
        return node;
    }

    private GraphEdge mockEdge(String edgeId, GraphNode source, GraphNode target,
                                EdgeType type, double weight) {
        GraphEdge edge = mock(GraphEdge.class);
        doReturn(edgeId).when(edge).getEdgeId();
        doReturn(source).when(edge).getSourceNode();
        doReturn(target).when(edge).getTargetNode();
        doReturn(type).when(edge).getEdgeType();
        doReturn(weight).when(edge).getWeight();
        return edge;
    }

    private void stubNodeLookup(GraphNode node) {
        when(graphService.getNode(node.getNodeId())).thenReturn(Optional.of(node));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EXPLORE NEIGHBORHOOD
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void exploreNeighborhood_nodeNotFound_returnsError() {
        when(graphService.getNode("missing")).thenReturn(Optional.empty());
        when(graphService.getNodeByExternalId(eq("missing"), any()))
                .thenReturn(Optional.empty());

        Map<String, Object> result = service.exploreNeighborhood(
                "missing", 2, 50, null, null, null, null);

        assertTrue(((String) result.get("error")).contains("Node not found"));
    }

    @Test
    void exploreNeighborhood_seedOnly_noEdges() {
        GraphNode seed = mockNode("seed", "Seed Node", NodeLevel.DOCUMENT);
        stubNodeLookup(seed);
        when(graphService.getEdgesForNode("seed")).thenReturn(List.of());

        Map<String, Object> result = service.exploreNeighborhood(
                "seed", 2, 50, null, null, null, null);

        assertEquals("seed", result.get("seedNode"));
        assertEquals("Seed Node", result.get("seedTitle"));
        assertEquals(1, result.get("nodeCount")); // just the seed
        assertEquals(0, result.get("edgeCount"));
    }

    @Test
    void exploreNeighborhood_onHopTraversal() {
        GraphNode seed = mockNode("seed", "Seed", NodeLevel.DOCUMENT);
        GraphNode neighbor = mockNode("n1", "Neighbor", NodeLevel.ENTITY);
        stubNodeLookup(seed);
        stubNodeLookup(neighbor);

        GraphEdge edge = mockEdge("e1", seed, neighbor, EdgeType.SHARED_ENTITY, 0.8);
        when(graphService.getEdgesForNode("seed")).thenReturn(List.of(edge));
        when(graphService.getEdgesForNode("n1")).thenReturn(List.of());

        Map<String, Object> result = service.exploreNeighborhood(
                "seed", 2, 50, null, null, null, null);

        assertEquals(2, result.get("nodeCount"));
        assertEquals(1, result.get("edgeCount"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) result.get("nodes");
        assertEquals("seed", nodes.get(0).get("nodeId"));
        assertEquals(0, nodes.get(0).get("depth"));
        assertEquals("n1", nodes.get(1).get("nodeId"));
        assertEquals(1, nodes.get(1).get("depth"));
    }

    @Test
    void exploreNeighborhood_edgeTypeFilter() {
        GraphNode seed = mockNode("seed", "Seed", NodeLevel.DOCUMENT);
        GraphNode n1 = mockNode("n1", "N1", NodeLevel.ENTITY);
        GraphNode n2 = mockNode("n2", "N2", NodeLevel.ENTITY);
        stubNodeLookup(seed);
        stubNodeLookup(n1);
        stubNodeLookup(n2);

        GraphEdge sharedEdge = mockEdge("e1", seed, n1, EdgeType.SHARED_ENTITY, 0.9);
        GraphEdge similarEdge = mockEdge("e2", seed, n2, EdgeType.EMBEDDING_SIMILARITY, 0.5);
        when(graphService.getEdgesForNode("seed"))
                .thenReturn(List.of(sharedEdge, similarEdge));

        // Only traverse SHARED_ENTITY edges
        Map<String, Object> result = service.exploreNeighborhood(
                "seed", 1, 50, null, List.of("SHARED_ENTITY"), null, null);

        assertEquals(2, result.get("nodeCount")); // seed + n1
        assertEquals(1, result.get("edgeCount")); // only sharedEdge
    }

    @Test
    void exploreNeighborhood_minWeightFilter() {
        GraphNode seed = mockNode("seed", "Seed", NodeLevel.DOCUMENT);
        GraphNode n1 = mockNode("n1", "Strong", NodeLevel.ENTITY);
        GraphNode n2 = mockNode("n2", "Weak", NodeLevel.ENTITY);
        stubNodeLookup(seed);
        stubNodeLookup(n1);
        stubNodeLookup(n2);

        GraphEdge strongEdge = mockEdge("e1", seed, n1, EdgeType.SHARED_ENTITY, 0.9);
        GraphEdge weakEdge = mockEdge("e2", seed, n2, EdgeType.SHARED_ENTITY, 0.1);
        when(graphService.getEdgesForNode("seed"))
                .thenReturn(List.of(strongEdge, weakEdge));

        Map<String, Object> result = service.exploreNeighborhood(
                "seed", 1, 50, null, null, 0.5, null);

        assertEquals(2, result.get("nodeCount")); // seed + n1 only
    }

    @Test
    void exploreNeighborhood_maxNodesCapped() {
        GraphNode seed = mockNode("seed", "Seed", NodeLevel.DOCUMENT);
        stubNodeLookup(seed);

        List<GraphEdge> manyEdges = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            GraphNode n = mockNode("n" + i, "N" + i, NodeLevel.ENTITY);
            stubNodeLookup(n);
            manyEdges.add(mockEdge("e" + i, seed, n, EdgeType.SHARED_ENTITY, 0.5));
        }
        when(graphService.getEdgesForNode("seed")).thenReturn(manyEdges);

        // maxNodes = 5 means at most 5 nodes total (including seed)
        Map<String, Object> result = service.exploreNeighborhood(
                "seed", 1, 5, null, null, null, null);

        int nodeCount = (int) result.get("nodeCount");
        assertTrue(nodeCount <= 5);
    }

    @Test
    void exploreNeighborhood_depthCappedAt4() {
        GraphNode seed = mockNode("seed", "Seed", NodeLevel.DOCUMENT);
        stubNodeLookup(seed);
        when(graphService.getEdgesForNode("seed")).thenReturn(List.of());

        Map<String, Object> result = service.exploreNeighborhood(
                "seed", 100, 50, null, null, null, null);

        assertEquals(4, result.get("maxDepth"));
    }

    @Test
    void exploreNeighborhood_factSheetScoped() {
        GraphNode seed = mockNode("seed", "Seed", NodeLevel.DOCUMENT);
        stubNodeLookup(seed);
        when(edgeRepository.findAllEdgesForNodeIdInFactSheet("seed", 42L))
                .thenReturn(List.of());

        service.exploreNeighborhood("seed", 2, 50, null, null, null, 42L);

        verify(edgeRepository).findAllEdgesForNodeIdInFactSheet("seed", 42L);
        verify(graphService, never()).getEdgesForNode("seed");
    }

    @Test
    void exploreNeighborhood_entityTypeFilter() {
        GraphNode seed = mockNode("seed", "Seed", NodeLevel.DOCUMENT);
        GraphNode person = mockNode("p1", "Alice", NodeLevel.ENTITY);
        GraphNode org = mockNode("o1", "Acme", NodeLevel.ENTITY);
        stubNodeLookup(seed);
        stubNodeLookup(person);
        stubNodeLookup(org);

        GraphEdge e1 = mockEdge("e1", seed, person, EdgeType.SHARED_ENTITY, 0.8);
        GraphEdge e2 = mockEdge("e2", seed, org, EdgeType.SHARED_ENTITY, 0.8);
        when(graphService.getEdgesForNode("seed")).thenReturn(List.of(e1, e2));

        EntityMention personMention = mock(EntityMention.class);
        when(personMention.getEntityType()).thenReturn("PERSON");
        when(entityMentionRepository.findByNode(person)).thenReturn(List.of(personMention));

        EntityMention orgMention = mock(EntityMention.class);
        when(orgMention.getEntityType()).thenReturn("ORGANIZATION");
        when(entityMentionRepository.findByNode(org)).thenReturn(List.of(orgMention));

        // Only include entities of type PERSON
        Map<String, Object> result = service.exploreNeighborhood(
                "seed", 1, 50, List.of("PERSON"), null, null, null);

        assertEquals(2, result.get("nodeCount")); // seed + person only
    }

    @Test
    void exploreNeighborhood_bidirectionalEdges() {
        GraphNode seed = mockNode("seed", "Seed", NodeLevel.DOCUMENT);
        GraphNode n1 = mockNode("n1", "N1", NodeLevel.DOCUMENT);
        stubNodeLookup(seed);
        stubNodeLookup(n1);

        // Edge where seed is the TARGET, not source
        GraphEdge edge = mockEdge("e1", n1, seed, EdgeType.SHARED_ENTITY, 0.7);
        when(graphService.getEdgesForNode("seed")).thenReturn(List.of(edge));
        when(graphService.getEdgesForNode("n1")).thenReturn(List.of());

        Map<String, Object> result = service.exploreNeighborhood(
                "seed", 2, 50, null, null, null, null);

        assertEquals(2, result.get("nodeCount"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STRUCTURAL SEARCH
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void structuralSearch_minDegreeFilter() {
        GraphNode n1 = mockNode("n1", "Hub", NodeLevel.ENTITY);
        GraphNode n2 = mockNode("n2", "Leaf", NodeLevel.ENTITY);
        stubNodeLookup(n1);
        stubNodeLookup(n2);

        when(graphService.getNodesByType(isNull(), eq(5000)))
                .thenReturn(List.of(n1, n2));

        // n1 has 3 edges, n2 has 1
        GraphEdge e1 = mockEdge("e1", n1, n2, EdgeType.SHARED_ENTITY, 0.5);
        GraphEdge e2 = mockEdge("e2", n1, n2, EdgeType.HIERARCHICAL, 0.5);
        GraphEdge e3 = mockEdge("e3", n1, n2, EdgeType.CITATION, 0.5);
        GraphEdge e4 = mockEdge("e4", n2, n1, EdgeType.SHARED_ENTITY, 0.5);
        when(graphService.getEdgesForNode("n1")).thenReturn(List.of(e1, e2, e3));
        when(graphService.getEdgesForNode("n2")).thenReturn(List.of(e4));

        Map<String, Object> result = service.structuralSearch(
                3, null, null, null, null, null, null, 20, null);

        assertEquals(1, result.get("matchCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matches = (List<Map<String, Object>>) result.get("matches");
        assertEquals("n1", matches.get(0).get("nodeId"));
        assertEquals(3, matches.get(0).get("degree"));
    }

    @Test
    void structuralSearch_maxDegreeFilter() {
        GraphNode n1 = mockNode("n1", "Hub", NodeLevel.ENTITY);
        GraphNode n2 = mockNode("n2", "Other", NodeLevel.ENTITY);
        stubNodeLookup(n1);
        stubNodeLookup(n2);

        when(graphService.getNodesByType(isNull(), eq(5000))).thenReturn(List.of(n1));
        GraphEdge me1 = mockEdge("e1", n1, n2, EdgeType.SHARED_ENTITY, 0.5);
        GraphEdge me2 = mockEdge("e2", n1, n2, EdgeType.HIERARCHICAL, 0.5);
        GraphEdge me3 = mockEdge("e3", n1, n2, EdgeType.CITATION, 0.5);
        when(graphService.getEdgesForNode("n1")).thenReturn(List.of(me1, me2, me3));

        // maxDegree=2 should exclude n1 (degree=3)
        Map<String, Object> result = service.structuralSearch(
                null, 2, null, null, null, null, null, 20, null);

        assertEquals(0, result.get("matchCount"));
    }

    @Test
    void structuralSearch_requiredEdgeTypes() {
        GraphNode n1 = mockNode("n1", "N1", NodeLevel.DOCUMENT);
        GraphNode n2 = mockNode("n2x", "N2x", NodeLevel.ENTITY);
        stubNodeLookup(n1);
        stubNodeLookup(n2);

        GraphEdge re1 = mockEdge("e1", n1, n2, EdgeType.SHARED_ENTITY, 0.5);
        GraphEdge re2 = mockEdge("e2", n1, n2, EdgeType.CITATION, 0.5);
        when(graphService.getNodesByType(isNull(), eq(5000))).thenReturn(List.of(n1));
        when(graphService.getEdgesForNode("n1")).thenReturn(List.of(re1, re2));

        // Require both SHARED_ENTITY and CITATION
        Map<String, Object> result = service.structuralSearch(
                null, null, List.of("SHARED_ENTITY", "CITATION"), null,
                null, null, null, 20, null);

        assertEquals(1, result.get("matchCount"));

        // Require HIERARCHICAL which is not present
        result = service.structuralSearch(
                null, null, List.of("SHARED_ENTITY", "HIERARCHICAL"), null,
                null, null, null, 20, null);

        assertEquals(0, result.get("matchCount"));
    }

    @Test
    void structuralSearch_nodeTypeFilter() {
        GraphNode doc = mockNode("d1", "Doc", NodeLevel.DOCUMENT);
        GraphNode entity = mockNode("e1", "Entity", NodeLevel.ENTITY);
        stubNodeLookup(doc);
        stubNodeLookup(entity);

        when(graphService.getNodesByType(eq(NodeLevel.DOCUMENT), eq(5000)))
                .thenReturn(List.of(doc));

        when(graphService.getEdgesForNode("d1")).thenReturn(List.of());

        Map<String, Object> result = service.structuralSearch(
                null, null, null, null, "DOCUMENT", null, null, 20, null);

        assertEquals(1, result.get("matchCount"));
    }

    @Test
    void structuralSearch_scopedToNeighborhood() {
        when(algorithmService.bfsTraversal(isNull(), eq("center"), eq(2)))
                .thenReturn(Map.of(0, List.of("center"), 1, List.of("n1", "n2")));

        GraphNode center = mockNode("center", "Center", NodeLevel.DOCUMENT);
        GraphNode n1 = mockNode("n1", "N1", NodeLevel.ENTITY);
        GraphNode n2 = mockNode("n2", "N2", NodeLevel.ENTITY);
        stubNodeLookup(center);
        stubNodeLookup(n1);
        stubNodeLookup(n2);

        when(graphService.getEdgesForNode(anyString())).thenReturn(List.of());

        Map<String, Object> result = service.structuralSearch(
                null, null, null, null, null,
                "center", 2, 20, null);

        // All 3 candidates from BFS should be checked
        assertEquals(3, result.get("matchCount"));
    }

    @Test
    void structuralSearch_edgeTypeCountsInResult() {
        GraphNode n1 = mockNode("n1", "N1", NodeLevel.ENTITY);
        GraphNode n2 = mockNode("n2y", "N2y", NodeLevel.ENTITY);
        stubNodeLookup(n1);
        stubNodeLookup(n2);

        GraphEdge ce1 = mockEdge("e1", n1, n2, EdgeType.SHARED_ENTITY, 0.5);
        GraphEdge ce2 = mockEdge("e2", n1, n2, EdgeType.SHARED_ENTITY, 0.6);
        GraphEdge ce3 = mockEdge("e3", n1, n2, EdgeType.CITATION, 0.5);
        when(graphService.getNodesByType(isNull(), eq(5000))).thenReturn(List.of(n1));
        when(graphService.getEdgesForNode("n1")).thenReturn(List.of(ce1, ce2, ce3));

        Map<String, Object> result = service.structuralSearch(
                null, null, null, null, null, null, null, 20, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matches = (List<Map<String, Object>>) result.get("matches");
        @SuppressWarnings("unchecked")
        Map<String, Long> edgeCounts = (Map<String, Long>) matches.get(0).get("edgeTypeCounts");
        assertEquals(2L, edgeCounts.get("SHARED_ENTITY"));
        assertEquals(1L, edgeCounts.get("CITATION"));
    }

    @Test
    void structuralSearch_requiredEntityTypes() {
        GraphNode doc = mockNode("d1", "Doc", NodeLevel.DOCUMENT);
        GraphNode person = mockNode("p1", "Alice", NodeLevel.ENTITY);
        stubNodeLookup(doc);
        stubNodeLookup(person);

        when(graphService.getNodesByType(isNull(), eq(5000))).thenReturn(List.of(doc));

        GraphEdge e1 = mockEdge("e1", doc, person, EdgeType.SHARED_ENTITY, 0.8);
        when(graphService.getEdgesForNode("d1")).thenReturn(List.of(e1));

        EntityMention mention = mock(EntityMention.class);
        when(mention.getEntityType()).thenReturn("PERSON");
        when(entityMentionRepository.findByNode(person)).thenReturn(List.of(mention));

        // Require PERSON — matches
        Map<String, Object> result = service.structuralSearch(
                null, null, null, List.of("PERSON"), null, null, null, 20, null);
        assertEquals(1, result.get("matchCount"));

        // Require LOCATION — no match
        result = service.structuralSearch(
                null, null, null, List.of("LOCATION"), null, null, null, 20, null);
        assertEquals(0, result.get("matchCount"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NEIGHBORHOOD PROFILE
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void profileNeighborhood_nodeNotFound_returnsError() {
        when(graphService.getNode("missing")).thenReturn(Optional.empty());
        when(graphService.getNodeByExternalId(eq("missing"), any()))
                .thenReturn(Optional.empty());

        Map<String, Object> result = service.profileNeighborhood("missing", 2, null);

        assertTrue(((String) result.get("error")).contains("Node not found"));
    }

    @Test
    void profileNeighborhood_isolatedNode() {
        GraphNode seed = mockNode("seed", "Isolated", NodeLevel.DOCUMENT);
        stubNodeLookup(seed);

        when(algorithmService.bfsTraversal(isNull(), eq("seed"), eq(2)))
                .thenReturn(Map.of(0, List.of("seed")));
        when(graphService.getEdgesForNode("seed")).thenReturn(List.of());
        when(algorithmService.pageRank(isNull(), eq(0.85), eq(50), eq(1e-6)))
                .thenReturn(Map.of("seed", 1.0));
        when(algorithmService.degreeCentrality(isNull(), eq(DegreeCentrality.Type.TOTAL)))
                .thenReturn(Map.of("seed", 0.0));
        when(algorithmService.view(isNull())).thenReturn(AdjacencyView.builder().addNode("seed").build());

        Map<String, Object> result = service.profileNeighborhood("seed", 2, null);

        assertEquals("seed", result.get("seedNode"));
        assertEquals(1, result.get("totalNodes"));
        assertEquals(0, result.get("totalEdges"));
        assertEquals(0.0, result.get("density"));
    }

    @Test
    void profileNeighborhood_returnsTypeDistributions() {
        GraphNode seed = mockNode("seed", "Seed", NodeLevel.DOCUMENT);
        GraphNode entity = mockNode("e1", "Alice", NodeLevel.ENTITY);
        stubNodeLookup(seed);
        stubNodeLookup(entity);

        when(algorithmService.bfsTraversal(isNull(), eq("seed"), eq(2)))
                .thenReturn(Map.of(0, List.of("seed"), 1, List.of("e1")));

        when(graphService.getEdgesForNode("seed")).thenReturn(List.of());
        when(graphService.getEdgesForNode("e1")).thenReturn(List.of());

        EntityMention mention = mock(EntityMention.class);
        when(mention.getEntityType()).thenReturn("PERSON");
        when(entityMentionRepository.findByNode(entity)).thenReturn(List.of(mention));

        when(algorithmService.pageRank(any(), anyDouble(), anyInt(), anyDouble()))
                .thenReturn(Map.of("seed", 0.6, "e1", 0.4));
        when(algorithmService.degreeCentrality(any(), any()))
                .thenReturn(Map.of("seed", 1.0, "e1", 1.0));
        when(algorithmService.view(any())).thenReturn(
                AdjacencyView.builder().addNode("seed").addNode("e1").build());

        Map<String, Object> result = service.profileNeighborhood("seed", 2, null);

        @SuppressWarnings("unchecked")
        Map<String, Integer> nodeTypeDist = (Map<String, Integer>) result.get("nodeTypeDistribution");
        assertEquals(1, nodeTypeDist.get("DOCUMENT"));
        assertEquals(1, nodeTypeDist.get("ENTITY"));

        @SuppressWarnings("unchecked")
        Map<String, Integer> entityTypeDist = (Map<String, Integer>) result.get("entityTypeDistribution");
        assertEquals(1, entityTypeDist.get("PERSON"));
    }

    @Test
    void profileNeighborhood_depthCappedAt3() {
        GraphNode seed = mockNode("seed", "Seed", NodeLevel.DOCUMENT);
        stubNodeLookup(seed);

        when(algorithmService.bfsTraversal(isNull(), eq("seed"), eq(3)))
                .thenReturn(Map.of(0, List.of("seed")));
        when(graphService.getEdgesForNode("seed")).thenReturn(List.of());
        when(algorithmService.pageRank(any(), anyDouble(), anyInt(), anyDouble()))
                .thenReturn(Map.of());
        when(algorithmService.degreeCentrality(any(), any()))
                .thenReturn(Map.of());
        when(algorithmService.view(any()))
                .thenReturn(AdjacencyView.builder().addNode("seed").build());

        Map<String, Object> result = service.profileNeighborhood("seed", 10, null);

        assertEquals(3, result.get("depth"));
    }

    @Test
    void profileNeighborhood_topNodesByPageRank() {
        GraphNode seed = mockNode("seed", "Seed", NodeLevel.DOCUMENT);
        GraphNode hub = mockNode("hub", "Hub", NodeLevel.ENTITY);
        stubNodeLookup(seed);
        stubNodeLookup(hub);

        when(algorithmService.bfsTraversal(any(), eq("seed"), eq(2)))
                .thenReturn(Map.of(0, List.of("seed"), 1, List.of("hub")));
        when(graphService.getEdgesForNode(anyString())).thenReturn(List.of());
        when(entityMentionRepository.findByNode(any())).thenReturn(List.of());

        when(algorithmService.pageRank(any(), anyDouble(), anyInt(), anyDouble()))
                .thenReturn(Map.of("seed", 0.3, "hub", 0.7));
        when(algorithmService.degreeCentrality(any(), any()))
                .thenReturn(Map.of("seed", 1.0, "hub", 5.0));
        when(algorithmService.view(any()))
                .thenReturn(AdjacencyView.builder().addNode("seed").addNode("hub").build());

        Map<String, Object> result = service.profileNeighborhood("seed", 2, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> topNodes =
                (List<Map<String, Object>>) result.get("topNodesByPageRank");
        assertFalse(topNodes.isEmpty());
        // Hub should be first (higher PageRank)
        assertEquals("hub", topNodes.get(0).get("nodeId"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FIND HUBS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void findHubs_degreeMetric() {
        GraphNode n1 = mockNode("n1", "Hub1", NodeLevel.ENTITY);
        GraphNode n2 = mockNode("n2", "Hub2", NodeLevel.ENTITY);
        stubNodeLookup(n1);
        stubNodeLookup(n2);

        when(algorithmService.degreeCentrality(isNull(), eq(DegreeCentrality.Type.TOTAL)))
                .thenReturn(Map.of("n1", 10.0, "n2", 5.0));

        Map<String, Object> result = service.findHubs(
                "degree", null, null, null, 10, null);

        assertEquals("degree", result.get("metric"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hubs = (List<Map<String, Object>>) result.get("hubs");
        assertEquals(2, hubs.size());
        assertEquals("n1", hubs.get(0).get("nodeId")); // higher degree first
    }

    @Test
    void findHubs_pageRankMetric() {
        when(algorithmService.pageRank(isNull(), eq(0.85), eq(50), eq(1e-6)))
                .thenReturn(Map.of("a", 0.3, "b", 0.7));

        GraphNode a = mockNode("a", "A", NodeLevel.DOCUMENT);
        GraphNode b = mockNode("b", "B", NodeLevel.DOCUMENT);
        stubNodeLookup(a);
        stubNodeLookup(b);

        Map<String, Object> result = service.findHubs(
                "pagerank", null, null, null, 10, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hubs = (List<Map<String, Object>>) result.get("hubs");
        assertEquals("b", hubs.get(0).get("nodeId")); // higher PageRank
    }

    @Test
    void findHubs_betweennessMetric() {
        when(algorithmService.betweennessCentrality(isNull(), eq(500), eq(42L)))
                .thenReturn(Map.of("bridge", 0.9));

        GraphNode bridge = mockNode("bridge", "Bridge", NodeLevel.ENTITY);
        stubNodeLookup(bridge);

        Map<String, Object> result = service.findHubs(
                "betweenness", null, null, null, 10, null);

        assertEquals("betweenness", result.get("metric"));
        assertEquals(1, result.get("hubCount"));
    }

    @Test
    void findHubs_nodeTypeFilter() {
        when(algorithmService.degreeCentrality(any(), any()))
                .thenReturn(Map.of("doc1", 5.0, "ent1", 10.0));

        GraphNode doc = mockNode("doc1", "Doc", NodeLevel.DOCUMENT);
        GraphNode ent = mockNode("ent1", "Entity", NodeLevel.ENTITY);
        stubNodeLookup(doc);
        stubNodeLookup(ent);

        Map<String, Object> result = service.findHubs(
                "degree", "DOCUMENT", null, null, 10, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hubs = (List<Map<String, Object>>) result.get("hubs");
        assertEquals(1, hubs.size());
        assertEquals("doc1", hubs.get(0).get("nodeId"));
    }

    @Test
    void findHubs_scopedToNeighborhood() {
        when(algorithmService.bfsTraversal(isNull(), eq("center"), eq(2)))
                .thenReturn(Map.of(0, List.of("center"), 1, List.of("n1")));
        when(algorithmService.degreeCentrality(any(), any()))
                .thenReturn(Map.of("center", 5.0, "n1", 3.0, "faraway", 100.0));

        GraphNode center = mockNode("center", "Center", NodeLevel.ENTITY);
        GraphNode n1 = mockNode("n1", "N1", NodeLevel.ENTITY);
        stubNodeLookup(center);
        stubNodeLookup(n1);

        Map<String, Object> result = service.findHubs(
                "degree", null, "center", 2, 10, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hubs = (List<Map<String, Object>>) result.get("hubs");
        // "faraway" should be excluded
        assertTrue(hubs.stream().noneMatch(h -> "faraway".equals(h.get("nodeId"))));
    }

    @Test
    void findHubs_topKCapped() {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (int i = 0; i < 100; i++) {
            String id = "n" + i;
            scores.put(id, (double) (100 - i));
            GraphNode n = mockNode(id, "Node" + i, NodeLevel.ENTITY);
            stubNodeLookup(n);
        }
        when(algorithmService.degreeCentrality(any(), any())).thenReturn(scores);

        Map<String, Object> result = service.findHubs(
                "degree", null, null, null, 5, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hubs = (List<Map<String, Object>>) result.get("hubs");
        assertEquals(5, hubs.size());
    }

    @Test
    void findHubs_defaultMetricIsDegree() {
        when(algorithmService.degreeCentrality(any(), any()))
                .thenReturn(Map.of("n1", 1.0));
        GraphNode n1 = mockNode("n1", "N1", NodeLevel.ENTITY);
        stubNodeLookup(n1);

        Map<String, Object> result = service.findHubs(
                null, null, null, null, 10, null);

        assertEquals("degree", result.get("metric"));
        verify(algorithmService).degreeCentrality(any(), any());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONSTRAINED PATH SEARCH
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void constrainedPath_sourceNotFound() {
        when(graphService.getNode("missing")).thenReturn(Optional.empty());
        when(graphService.getNodeByExternalId(eq("missing"), any()))
                .thenReturn(Optional.empty());
        GraphNode target = mockNode("target", "Target", NodeLevel.DOCUMENT);
        stubNodeLookup(target);

        Map<String, Object> result = service.constrainedPathSearch(
                "missing", "target", null, null, 4, false, null);

        assertTrue(((String) result.get("error")).contains("Source node not found"));
    }

    @Test
    void constrainedPath_targetNotFound() {
        GraphNode source = mockNode("source", "Source", NodeLevel.DOCUMENT);
        stubNodeLookup(source);
        when(graphService.getNode("missing")).thenReturn(Optional.empty());
        when(graphService.getNodeByExternalId(eq("missing"), any()))
                .thenReturn(Optional.empty());

        Map<String, Object> result = service.constrainedPathSearch(
                "source", "missing", null, null, 4, false, null);

        assertTrue(((String) result.get("error")).contains("Target node not found"));
    }

    @Test
    void constrainedPath_directConnection() {
        GraphNode source = mockNode("s", "Source", NodeLevel.DOCUMENT);
        GraphNode target = mockNode("t", "Target", NodeLevel.DOCUMENT);
        stubNodeLookup(source);
        stubNodeLookup(target);

        GraphEdge edge = mockEdge("e1", source, target, EdgeType.SHARED_ENTITY, 0.9);
        when(graphService.getEdgesForNode("s")).thenReturn(List.of(edge));

        Map<String, Object> result = service.constrainedPathSearch(
                "s", "t", null, null, 4, false, null);

        assertTrue((boolean) result.get("found"));
        assertEquals(1, result.get("pathLength"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> path = (List<Map<String, Object>>) result.get("path");
        assertEquals(2, path.size());
        assertEquals("s", path.get(0).get("nodeId"));
        assertEquals("t", path.get(1).get("nodeId"));
    }

    @Test
    void constrainedPath_twoHopPath() {
        GraphNode a = mockNode("a", "A", NodeLevel.DOCUMENT);
        GraphNode b = mockNode("b", "B", NodeLevel.ENTITY);
        GraphNode c = mockNode("c", "C", NodeLevel.DOCUMENT);
        stubNodeLookup(a);
        stubNodeLookup(b);
        stubNodeLookup(c);

        GraphEdge e1 = mockEdge("e1", a, b, EdgeType.SHARED_ENTITY, 0.8);
        GraphEdge e2 = mockEdge("e2", b, c, EdgeType.CITATION, 0.7);
        when(graphService.getEdgesForNode("a")).thenReturn(List.of(e1));
        when(graphService.getEdgesForNode("b")).thenReturn(List.of(e1, e2));

        Map<String, Object> result = service.constrainedPathSearch(
                "a", "c", null, null, 4, false, null);

        assertTrue((boolean) result.get("found"));
        assertEquals(2, result.get("pathLength"));
    }

    @Test
    void constrainedPath_noPathFound() {
        GraphNode a = mockNode("a", "A", NodeLevel.DOCUMENT);
        GraphNode z = mockNode("z", "Z", NodeLevel.DOCUMENT);
        stubNodeLookup(a);
        stubNodeLookup(z);

        when(graphService.getEdgesForNode("a")).thenReturn(List.of());

        Map<String, Object> result = service.constrainedPathSearch(
                "a", "z", null, null, 4, false, null);

        assertFalse((boolean) result.get("found"));
    }

    @Test
    void constrainedPath_edgeTypeConstraint() {
        GraphNode a = mockNode("a", "A", NodeLevel.DOCUMENT);
        GraphNode b = mockNode("b", "B", NodeLevel.ENTITY);
        GraphNode c = mockNode("c", "C", NodeLevel.DOCUMENT);
        stubNodeLookup(a);
        stubNodeLookup(b);
        stubNodeLookup(c);

        // a->b via CITATION, b->c via SHARED_ENTITY
        GraphEdge e1 = mockEdge("e1", a, b, EdgeType.CITATION, 0.8);
        GraphEdge e2 = mockEdge("e2", b, c, EdgeType.SHARED_ENTITY, 0.7);
        when(graphService.getEdgesForNode("a")).thenReturn(List.of(e1));
        when(graphService.getEdgesForNode("b")).thenReturn(List.of(e1, e2));

        // Only allow CITATION — can reach b but not c
        Map<String, Object> result = service.constrainedPathSearch(
                "a", "c", List.of("CITATION"), null, 4, false, null);

        assertFalse((boolean) result.get("found"));
    }

    @Test
    void constrainedPath_requiredIntermediateTypes() {
        GraphNode a = mockNode("a", "A", NodeLevel.DOCUMENT);
        GraphNode b = mockNode("b", "B", NodeLevel.SNIPPET); // not ENTITY
        GraphNode c = mockNode("c", "C", NodeLevel.DOCUMENT);
        stubNodeLookup(a);
        stubNodeLookup(b);
        stubNodeLookup(c);

        GraphEdge e1 = mockEdge("e1", a, b, EdgeType.HIERARCHICAL, 0.8);
        GraphEdge e2 = mockEdge("e2", b, c, EdgeType.HIERARCHICAL, 0.7);
        when(graphService.getEdgesForNode("a")).thenReturn(List.of(e1));
        when(graphService.getEdgesForNode("b")).thenReturn(List.of(e1, e2));

        // Require ENTITY intermediate — but B is SNIPPET
        Map<String, Object> result = service.constrainedPathSearch(
                "a", "c", null, List.of("ENTITY"), 4, false, null);

        assertFalse((boolean) result.get("found"));
    }

    @Test
    void constrainedPath_maxPathLengthCapped() {
        GraphNode a = mockNode("a", "A", NodeLevel.DOCUMENT);
        GraphNode z = mockNode("z", "Z", NodeLevel.DOCUMENT);
        stubNodeLookup(a);
        stubNodeLookup(z);
        when(graphService.getEdgesForNode("a")).thenReturn(List.of());

        Map<String, Object> result = service.constrainedPathSearch(
                "a", "z", null, null, 100, false, null);

        // Path capped at 6, message should reflect that
        assertTrue(((String) result.get("message")).contains("6 hops"));
    }

    @Test
    void constrainedPath_edgeLabelsReturned() {
        GraphNode a = mockNode("a", "A", NodeLevel.DOCUMENT);
        GraphNode b = mockNode("b", "B", NodeLevel.DOCUMENT);
        stubNodeLookup(a);
        stubNodeLookup(b);

        GraphEdge edge = mockEdge("e1", a, b, EdgeType.CITATION, 0.75);
        when(graphService.getEdgesForNode("a")).thenReturn(List.of(edge));

        Map<String, Object> result = service.constrainedPathSearch(
                "a", "b", null, null, 4, false, null);

        @SuppressWarnings("unchecked")
        List<String> edgeLabels = (List<String>) result.get("edgeLabels");
        assertEquals(1, edgeLabels.size());
        assertTrue(edgeLabels.get(0).contains("CITATION"));
        assertTrue(edgeLabels.get(0).contains("0.75"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // COMPARE NEIGHBORHOODS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void compareNeighborhoods_nodeANotFound() {
        when(graphService.getNode("missing")).thenReturn(Optional.empty());
        when(graphService.getNodeByExternalId(eq("missing"), any()))
                .thenReturn(Optional.empty());
        GraphNode b = mockNode("b", "B", NodeLevel.DOCUMENT);
        stubNodeLookup(b);

        Map<String, Object> result = service.compareNeighborhoods(
                "missing", "b", 2, null);

        assertTrue(((String) result.get("error")).contains("Node A not found"));
    }

    @Test
    void compareNeighborhoods_nodeBNotFound() {
        GraphNode a = mockNode("a", "A", NodeLevel.DOCUMENT);
        stubNodeLookup(a);
        when(graphService.getNode("missing")).thenReturn(Optional.empty());
        when(graphService.getNodeByExternalId(eq("missing"), any()))
                .thenReturn(Optional.empty());

        Map<String, Object> result = service.compareNeighborhoods(
                "a", "missing", 2, null);

        assertTrue(((String) result.get("error")).contains("Node B not found"));
    }

    @Test
    void compareNeighborhoods_overlappingNeighborhoods() {
        GraphNode a = mockNode("a", "A", NodeLevel.DOCUMENT);
        GraphNode b = mockNode("b", "B", NodeLevel.DOCUMENT);
        GraphNode shared = mockNode("shared", "Shared", NodeLevel.ENTITY);
        GraphNode uniqueA = mockNode("ua", "UniqueA", NodeLevel.ENTITY);
        GraphNode uniqueB = mockNode("ub", "UniqueB", NodeLevel.ENTITY);
        stubNodeLookup(a);
        stubNodeLookup(b);
        stubNodeLookup(shared);
        stubNodeLookup(uniqueA);
        stubNodeLookup(uniqueB);

        when(algorithmService.bfsTraversal(isNull(), eq("a"), eq(2)))
                .thenReturn(Map.of(0, List.of("a"), 1, List.of("shared", "ua")));
        when(algorithmService.bfsTraversal(isNull(), eq("b"), eq(2)))
                .thenReturn(Map.of(0, List.of("b"), 1, List.of("shared", "ub")));

        when(algorithmService.jaccardSimilarity(isNull(), eq("a"), eq("b")))
                .thenReturn(0.333);

        when(entityMentionRepository.findByNode(any())).thenReturn(List.of());

        Map<String, Object> result = service.compareNeighborhoods("a", "b", 2, null);

        assertEquals(1, result.get("sharedNodeCount")); // "shared"
        assertEquals(2, result.get("uniqueToACount")); // "a" + "ua"
        assertEquals(2, result.get("uniqueToBCount")); // "b" + "ub"
    }

    @Test
    void compareNeighborhoods_jaccardSimilarity() {
        GraphNode a = mockNode("a", "A", NodeLevel.DOCUMENT);
        GraphNode b = mockNode("b", "B", NodeLevel.DOCUMENT);
        stubNodeLookup(a);
        stubNodeLookup(b);

        when(algorithmService.bfsTraversal(any(), eq("a"), eq(2)))
                .thenReturn(Map.of(0, List.of("a")));
        when(algorithmService.bfsTraversal(any(), eq("b"), eq(2)))
                .thenReturn(Map.of(0, List.of("b")));

        when(algorithmService.jaccardSimilarity(any(), eq("a"), eq("b")))
                .thenReturn(0.0);

        when(entityMentionRepository.findByNode(any())).thenReturn(List.of());

        Map<String, Object> result = service.compareNeighborhoods("a", "b", 2, null);

        assertEquals(0.0, result.get("jaccardSimilarity"));
    }

    @Test
    void compareNeighborhoods_sharedEntityNames() {
        GraphNode a = mockNode("a", "A", NodeLevel.DOCUMENT);
        GraphNode b = mockNode("b", "B", NodeLevel.DOCUMENT);
        GraphNode entity = mockNode("e1", "Alice", NodeLevel.ENTITY);
        stubNodeLookup(a);
        stubNodeLookup(b);
        stubNodeLookup(entity);

        when(algorithmService.bfsTraversal(any(), eq("a"), eq(2)))
                .thenReturn(Map.of(0, List.of("a"), 1, List.of("e1")));
        when(algorithmService.bfsTraversal(any(), eq("b"), eq(2)))
                .thenReturn(Map.of(0, List.of("b"), 1, List.of("e1")));

        when(algorithmService.jaccardSimilarity(any(), any(), any()))
                .thenReturn(1.0);

        EntityMention mention = mock(EntityMention.class);
        when(mention.getEntityType()).thenReturn("PERSON");
        when(mention.getEntityName()).thenReturn("alice");
        when(entityMentionRepository.findByNode(entity)).thenReturn(List.of(mention));

        Map<String, Object> result = service.compareNeighborhoods("a", "b", 2, null);

        @SuppressWarnings("unchecked")
        List<String> sharedNames = (List<String>) result.get("sharedEntityNames");
        assertTrue(sharedNames.contains("alice"));

        @SuppressWarnings("unchecked")
        Set<String> sharedTypes = (Set<String>) result.get("sharedEntityTypes");
        assertTrue(sharedTypes.contains("PERSON"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FIND COMMUNITY
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void findCommunity_nodeNotFound_returnsError() {
        when(graphService.getNode("missing")).thenReturn(Optional.empty());
        when(graphService.getNodeByExternalId(eq("missing"), any()))
                .thenReturn(Optional.empty());

        Map<String, Object> result = service.findCommunity("missing", "louvain", 30, null);

        assertTrue(((String) result.get("error")).contains("Node not found"));
    }

    @Test
    void findCommunity_louvainAlgorithm() {
        GraphNode seed = mockNode("seed", "Seed", NodeLevel.DOCUMENT);
        GraphNode member = mockNode("m1", "Member", NodeLevel.ENTITY);
        stubNodeLookup(seed);
        stubNodeLookup(member);

        when(algorithmService.louvainCommunities(isNull(), eq(20)))
                .thenReturn(Map.of("seed", 0, "m1", 0, "other", 1));

        when(algorithmService.pageRank(any(), anyDouble(), anyInt(), anyDouble()))
                .thenReturn(Map.of("seed", 0.4, "m1", 0.6));

        when(entityMentionRepository.findByNode(any())).thenReturn(List.of());

        Map<String, Object> result = service.findCommunity("seed", "louvain", 30, null);

        assertEquals("louvain", result.get("algorithm"));
        assertEquals(0, result.get("communityId"));
        assertEquals(2, result.get("communitySize")); // seed + m1
        assertEquals(2L, result.get("totalCommunities")); // 0 and 1
    }

    @Test
    void findCommunity_wccAlgorithm() {
        GraphNode seed = mockNode("seed", "Seed", NodeLevel.DOCUMENT);
        stubNodeLookup(seed);

        when(algorithmService.weaklyConnectedComponents(isNull()))
                .thenReturn(Map.of("seed", 0));

        when(algorithmService.pageRank(any(), anyDouble(), anyInt(), anyDouble()))
                .thenReturn(Map.of("seed", 1.0));

        when(entityMentionRepository.findByNode(any())).thenReturn(List.of());

        Map<String, Object> result = service.findCommunity("seed", "wcc", 30, null);

        assertEquals("wcc", result.get("algorithm"));
        verify(algorithmService).weaklyConnectedComponents(isNull());
        verify(algorithmService, never()).louvainCommunities(any(), anyInt());
    }

    @Test
    void findCommunity_nodeNotInAssignments() {
        GraphNode seed = mockNode("seed", "Seed", NodeLevel.DOCUMENT);
        stubNodeLookup(seed);

        // Louvain returns empty — seed not present
        when(algorithmService.louvainCommunities(any(), anyInt()))
                .thenReturn(Map.of());

        Map<String, Object> result = service.findCommunity("seed", "louvain", 30, null);

        assertTrue(((String) result.get("error")).contains("not found in community"));
    }

    @Test
    void findCommunity_membersRankedByPageRank() {
        GraphNode seed = mockNode("seed", "Seed", NodeLevel.DOCUMENT);
        GraphNode m1 = mockNode("m1", "M1", NodeLevel.ENTITY);
        GraphNode m2 = mockNode("m2", "M2", NodeLevel.ENTITY);
        stubNodeLookup(seed);
        stubNodeLookup(m1);
        stubNodeLookup(m2);

        when(algorithmService.louvainCommunities(any(), anyInt()))
                .thenReturn(Map.of("seed", 0, "m1", 0, "m2", 0));

        // m2 has highest PageRank
        when(algorithmService.pageRank(any(), anyDouble(), anyInt(), anyDouble()))
                .thenReturn(Map.of("seed", 0.1, "m1", 0.3, "m2", 0.6));

        when(entityMentionRepository.findByNode(any())).thenReturn(List.of());

        Map<String, Object> result = service.findCommunity("seed", null, 30, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> members =
                (List<Map<String, Object>>) result.get("members");
        assertEquals("m2", members.get(0).get("nodeId")); // highest PR
    }

    @Test
    void findCommunity_maxMembersCapped() {
        GraphNode seed = mockNode("seed", "Seed", NodeLevel.DOCUMENT);
        stubNodeLookup(seed);

        Map<String, Integer> assignments = new LinkedHashMap<>();
        assignments.put("seed", 0);
        for (int i = 0; i < 50; i++) {
            String id = "m" + i;
            assignments.put(id, 0);
            GraphNode n = mockNode(id, "N" + i, NodeLevel.ENTITY);
            stubNodeLookup(n);
        }

        when(algorithmService.louvainCommunities(any(), anyInt())).thenReturn(assignments);
        when(algorithmService.pageRank(any(), anyDouble(), anyInt(), anyDouble()))
                .thenReturn(Map.of());
        when(entityMentionRepository.findByNode(any())).thenReturn(List.of());

        Map<String, Object> result = service.findCommunity("seed", null, 5, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> members =
                (List<Map<String, Object>>) result.get("members");
        assertEquals(5, members.size());
    }

    @Test
    void findCommunity_entityTypeSummary() {
        GraphNode seed = mockNode("seed", "Seed", NodeLevel.ENTITY);
        stubNodeLookup(seed);

        when(algorithmService.louvainCommunities(any(), anyInt()))
                .thenReturn(Map.of("seed", 0));
        when(algorithmService.pageRank(any(), anyDouble(), anyInt(), anyDouble()))
                .thenReturn(Map.of("seed", 1.0));

        EntityMention mention = mock(EntityMention.class);
        when(mention.getEntityType()).thenReturn("PERSON");
        when(entityMentionRepository.findByNode(seed)).thenReturn(List.of(mention));

        Map<String, Object> result = service.findCommunity("seed", null, 30, null);

        @SuppressWarnings("unchecked")
        Map<String, Integer> summary =
                (Map<String, Integer>) result.get("entityTypeSummary");
        assertEquals(1, summary.get("PERSON"));
    }

    @Test
    void findCommunity_defaultAlgorithmIsLouvain() {
        GraphNode seed = mockNode("seed", "Seed", NodeLevel.DOCUMENT);
        stubNodeLookup(seed);

        when(algorithmService.louvainCommunities(any(), anyInt()))
                .thenReturn(Map.of("seed", 0));
        when(algorithmService.pageRank(any(), anyDouble(), anyInt(), anyDouble()))
                .thenReturn(Map.of());
        when(entityMentionRepository.findByNode(any())).thenReturn(List.of());

        Map<String, Object> result = service.findCommunity("seed", null, 30, null);

        assertEquals("louvain", result.get("algorithm"));
    }
}
