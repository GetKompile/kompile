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
package ai.kompile.knowledgegraph.reasoning.controller;

import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.reasoning.controller.GraphFocalViewController.SubgraphRequest;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link GraphFocalViewController}.
 *
 * Directly instantiates the controller with a mocked {@link KnowledgeGraphService}
 * — no Spring context required.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GraphFocalViewControllerTest {

    @Mock
    private KnowledgeGraphService graphService;

    private GraphFocalViewController controller;

    private static final Long FS_ID = 42L;

    // ── Shared node/edge fixtures ─────────────────────────────────────────────

    /** Entity node tagged as conformant. */
    private GraphNode conformantNode;

    /** Entity node tagged as non-conformant with a violation message. */
    private GraphNode violatingNode;

    /** Entity node with NO conformance metadata (untagged). */
    private GraphNode untaggedNode;

    /** Non-entity node — must not appear in conformance response. */
    private GraphNode documentNode;

    @Test
    void mapsCanonicalAndCachedClientBasePaths() {
        RequestMapping mapping = GraphFocalViewController.class.getAnnotation(RequestMapping.class);

        assertNotNull(mapping);
        assertArrayEquals(new String[]{
                "/api/graph/{factSheetId}",
                "/api/api/graph/{factSheetId}",
                "/{factSheetId}"
        }, mapping.value());
    }

    @BeforeEach
    void setUp() {
        controller = new GraphFocalViewController(graphService);

        conformantNode = GraphNode.builder()
                .nodeId("node-conformant")
                .nodeType(NodeLevel.ENTITY)
                .title("ConformantEntity")
                .metadataJson("{\"ontology.conformant\": true}")
                .build();

        violatingNode = GraphNode.builder()
                .nodeId("node-violating")
                .nodeType(NodeLevel.ENTITY)
                .title("ViolatingEntity")
                .metadataJson("{\"ontology.conformant\": false, \"ontology.violation\": \"Missing required property 'type'\"}")
                .build();

        untaggedNode = GraphNode.builder()
                .nodeId("node-untagged")
                .nodeType(NodeLevel.ENTITY)
                .title("UntaggedEntity")
                .build();

        documentNode = GraphNode.builder()
                .nodeId("node-doc")
                .nodeType(NodeLevel.DOCUMENT)
                .title("SomeDocument")
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // P2 – CONFORMANCE OVERLAY TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void conformanceOverlay_returnsOnlyEntityNodes() {
        when(graphService.getNodesByTypeInFactSheet(FS_ID, NodeLevel.ENTITY))
                .thenReturn(List.of(conformantNode, violatingNode, untaggedNode));

        ResponseEntity<?> response = controller.getConformanceOverlay(FS_ID);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
        assertNotNull(body);
        // documentNode should NOT appear — we only call getNodesByTypeInFactSheet(ENTITY)
        assertEquals(3, body.size());
    }

    @Test
    void conformanceOverlay_conformantNode_hasConformantTrue() {
        when(graphService.getNodesByTypeInFactSheet(FS_ID, NodeLevel.ENTITY))
                .thenReturn(List.of(conformantNode));

        ResponseEntity<?> response = controller.getConformanceOverlay(FS_ID);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
        assertNotNull(body);
        Map<String, Object> entry = body.get(0);
        assertEquals("node-conformant", entry.get("nodeId"));
        assertEquals(Boolean.TRUE, entry.get("conformant"));
        assertNull(entry.get("violation"));
    }

    @Test
    void conformanceOverlay_violatingNode_hasConformantFalseAndViolationMessage() {
        when(graphService.getNodesByTypeInFactSheet(FS_ID, NodeLevel.ENTITY))
                .thenReturn(List.of(violatingNode));

        ResponseEntity<?> response = controller.getConformanceOverlay(FS_ID);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
        assertNotNull(body);
        Map<String, Object> entry = body.get(0);
        assertEquals(Boolean.FALSE, entry.get("conformant"));
        assertEquals("Missing required property 'type'", entry.get("violation"));
    }

    @Test
    void conformanceOverlay_untaggedNode_hasNullConformant() {
        when(graphService.getNodesByTypeInFactSheet(FS_ID, NodeLevel.ENTITY))
                .thenReturn(List.of(untaggedNode));

        ResponseEntity<?> response = controller.getConformanceOverlay(FS_ID);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
        assertNotNull(body);
        Map<String, Object> entry = body.get(0);
        assertNull(entry.get("conformant"),
                "Untagged node should report conformant=null (grey in the visualizer)");
        assertNull(entry.get("violation"));
    }

    @Test
    void conformanceOverlay_serviceThrows_returns503() {
        when(graphService.getNodesByTypeInFactSheet(anyLong(), any()))
                .thenThrow(new RuntimeException("DB unavailable"));

        ResponseEntity<?> response = controller.getConformanceOverlay(FS_ID);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertTrue(body.containsKey("error"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // REASONING LAYERS TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void reasoningLayers_emptyFactSheet_returnsValidEmptyResponse() {
        when(graphService.getNodesInFactSheet(FS_ID)).thenReturn(List.of());
        when(graphService.getEdgesInFactSheet(FS_ID)).thenReturn(List.of());

        ResponseEntity<?> response = controller.getReasoningLayers(FS_ID);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals(FS_ID, body.get("factSheetId"));
        assertEquals(List.of(), body.get("nodes"));
        assertEquals(List.of(), body.get("edges"));

        @SuppressWarnings("unchecked")
        Map<String, Object> statistics = (Map<String, Object>) body.get("statistics");
        assertNotNull(statistics);
        assertEquals(0, statistics.get("nodeCount"));
        assertEquals(0, statistics.get("edgeCount"));
        assertEquals(0, statistics.get("ontologyCount"));
        assertEquals(0, statistics.get("pslCount"));
        assertEquals(0, statistics.get("mebnCount"));
        assertEquals(0, statistics.get("provenanceCount"));
        assertEquals(0, statistics.get("opinionCount"));
        assertEquals(0, statistics.get("neuralScoreCount"));
    }

    @Test
    void reasoningLayers_serializesNodeAndEdgeMetadata() {
        GraphEdge edge = GraphEdge.builder()
                .edgeId("reasoning-edge")
                .sourceNode(conformantNode)
                .targetNode(untaggedNode)
                .edgeType(EdgeType.SHARED_ENTITY)
                .relationType("MENTIONS")
                .weight(0.8)
                .metadataJson("{\"psl\":{\"ruleId\":\"edge-rule\",\"truthValue\":0.77}}")
                .build();
        when(graphService.getNodesInFactSheet(FS_ID)).thenReturn(List.of(conformantNode));
        when(graphService.getEdgesInFactSheet(FS_ID)).thenReturn(List.of(edge));

        ResponseEntity<?> response = controller.getReasoningLayers(FS_ID);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) body.get("nodes");
        @SuppressWarnings("unchecked")
        Map<String, Object> ontology = (Map<String, Object>) nodes.get(0).get("ontology");
        assertEquals(Boolean.TRUE, ontology.get("conformant"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) body.get("edges");
        assertEquals("node-conformant", edges.get(0).get("sourceNodeId"));
        assertEquals("node-untagged", edges.get(0).get("targetNodeId"));
        assertTrue(edges.get(0).get("psl") instanceof Map<?, ?>);
    }

    @Test
    void reasoningLayers_serviceThrows_returns503() {
        when(graphService.getNodesInFactSheet(FS_ID))
                .thenThrow(new RuntimeException("DB unavailable"));

        ResponseEntity<?> response = controller.getReasoningLayers(FS_ID);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertTrue(body.containsKey("error"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // D2 – SUBGRAPH / FOCAL-VIEW TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    /** Build a minimal seed node + connected neighbor + one edge between them. */
    private void setupSubgraphFixture(GraphNode seed, GraphNode neighbor, GraphEdge edge) {
        when(graphService.getNodesInFactSheet(FS_ID))
                .thenReturn(List.of(seed, neighbor));
        when(graphService.getEdgesForNodeInFactSheet(seed.getNodeId(), FS_ID))
                .thenReturn(List.of(edge));
        when(graphService.getEdgesForNodeInFactSheet(neighbor.getNodeId(), FS_ID))
                .thenReturn(List.of(edge));
    }

    @Test
    void buildSubgraph_seedNotInFactSheet_returnsEmptySubgraph() {
        // No nodes in factSheet at all
        when(graphService.getNodesInFactSheet(FS_ID)).thenReturn(List.of());

        SubgraphRequest req = new SubgraphRequest(List.of("unknown-node"), 2, null, 0.0);
        ResponseEntity<?> response = controller.buildSubgraph(FS_ID, req);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        @SuppressWarnings("unchecked")
        List<?> nodes = (List<?>) body.get("nodes");
        assertEquals(0, nodes.size());
    }

    @Test
    void buildSubgraph_emptySeedNodeIds_returns400() {
        SubgraphRequest req = new SubgraphRequest(List.of(), 2, null, 0.0);
        ResponseEntity<?> response = controller.buildSubgraph(FS_ID, req);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void buildSubgraph_nullSeedNodeIds_returns400() {
        SubgraphRequest req = new SubgraphRequest(null, 2, null, 0.0);
        ResponseEntity<?> response = controller.buildSubgraph(FS_ID, req);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void buildSubgraph_radius1_returnsOnlyDirectNeighbors() {
        GraphNode seed = GraphNode.builder()
                .nodeId("seed-1").nodeType(NodeLevel.ENTITY).title("Seed").build();
        GraphNode neighbor = GraphNode.builder()
                .nodeId("neighbor-1").nodeType(NodeLevel.ENTITY).title("Neighbor").build();
        GraphNode farAway = GraphNode.builder()
                .nodeId("far-1").nodeType(NodeLevel.ENTITY).title("FarAway").build();

        GraphEdge seedEdge = GraphEdge.builder()
                .edgeId("e1")
                .sourceNode(seed)
                .targetNode(neighbor)
                .edgeType(EdgeType.SHARED_ENTITY)
                .weight(1.0)
                .build();

        // buildSubgraph now loads nodes lazily via getNode(id), not via getNodesInFactSheet.
        when(graphService.getNode("seed-1")).thenReturn(Optional.of(seed));
        when(graphService.getNode("neighbor-1")).thenReturn(Optional.of(neighbor));
        when(graphService.getEdgesForNodeInFactSheet("seed-1", FS_ID))
                .thenReturn(List.of(seedEdge));
        when(graphService.getEdgesForNodeInFactSheet("neighbor-1", FS_ID))
                .thenReturn(List.of()); // neighbor has no further edges at radius 1

        SubgraphRequest req = new SubgraphRequest(List.of("seed-1"), 1, null, 0.0);
        ResponseEntity<?> response = controller.buildSubgraph(FS_ID, req);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) body.get("nodes");
        // seed + neighbor (radius=1), farAway NOT included
        assertEquals(2, nodes.size());
        assertTrue(nodes.stream().anyMatch(n -> "seed-1".equals(n.get("id"))));
        assertTrue(nodes.stream().anyMatch(n -> "neighbor-1".equals(n.get("id"))));
        assertFalse(nodes.stream().anyMatch(n -> "far-1".equals(n.get("id"))));
    }

    @Test
    void buildSubgraph_confidenceFloor_excludesLowWeightEdges() {
        GraphNode seed = GraphNode.builder()
                .nodeId("seed-2").nodeType(NodeLevel.ENTITY).title("Seed2").build();
        GraphNode neighbor = GraphNode.builder()
                .nodeId("neighbor-2").nodeType(NodeLevel.ENTITY).title("LowConfNeighbor").build();

        GraphEdge lowConfEdge = GraphEdge.builder()
                .edgeId("e-low")
                .sourceNode(seed)
                .targetNode(neighbor)
                .edgeType(EdgeType.SHARED_ENTITY)
                .weight(0.2)  // below floor
                .build();

        // buildSubgraph now loads seeds lazily via getNode(id), not via getNodesInFactSheet.
        when(graphService.getNode("seed-2")).thenReturn(Optional.of(seed));
        when(graphService.getEdgesForNodeInFactSheet("seed-2", FS_ID))
                .thenReturn(List.of(lowConfEdge));

        SubgraphRequest req = new SubgraphRequest(List.of("seed-2"), 2, null, 0.5); // floor=0.5
        ResponseEntity<?> response = controller.buildSubgraph(FS_ID, req);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        @SuppressWarnings("unchecked")
        List<?> nodes = (List<?>) body.get("nodes");
        // Only the seed — neighbor filtered by confidence floor
        assertEquals(1, nodes.size());
    }

    @Test
    void buildSubgraph_edgeTypeFilter_excludesNonMatchingEdges() {
        GraphNode seed = GraphNode.builder()
                .nodeId("seed-3").nodeType(NodeLevel.ENTITY).title("Seed3").build();
        GraphNode neighbor = GraphNode.builder()
                .nodeId("neighbor-3").nodeType(NodeLevel.ENTITY).title("ETNeighbor").build();

        GraphEdge hierEdge = GraphEdge.builder()
                .edgeId("e-hier")
                .sourceNode(seed)
                .targetNode(neighbor)
                .edgeType(EdgeType.HIERARCHICAL)   // NOT in filter
                .weight(1.0)
                .build();

        when(graphService.getNodesInFactSheet(FS_ID))
                .thenReturn(List.of(seed, neighbor));
        when(graphService.getEdgesForNodeInFactSheet("seed-3", FS_ID))
                .thenReturn(List.of(hierEdge));

        // filter only SHARED_ENTITY — HIERARCHICAL should be excluded
        SubgraphRequest req = new SubgraphRequest(List.of("seed-3"), 2,
                List.of("SHARED_ENTITY"), 0.0);
        ResponseEntity<?> response = controller.buildSubgraph(FS_ID, req);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        @SuppressWarnings("unchecked")
        List<?> links = (List<?>) body.get("links");
        assertEquals(0, links.size(), "HIERARCHICAL edge should be filtered out");
    }

    @Test
    void buildSubgraph_responseIncludesBothLinksAndEdgesSynonym() {
        GraphNode seed = GraphNode.builder()
                .nodeId("seed-4").nodeType(NodeLevel.ENTITY).title("Seed4").build();

        when(graphService.getNodesInFactSheet(FS_ID)).thenReturn(List.of(seed));
        when(graphService.getEdgesForNodeInFactSheet("seed-4", FS_ID)).thenReturn(List.of());

        SubgraphRequest req = new SubgraphRequest(List.of("seed-4"), 1, null, 0.0);
        ResponseEntity<?> response = controller.buildSubgraph(FS_ID, req);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertTrue(body.containsKey("links"),  "Response must include 'links' key");
        assertTrue(body.containsKey("edges"),  "Response must include 'edges' synonym");
        assertTrue(body.containsKey("statistics"), "Response must include 'statistics'");
    }

    @Test
    void buildSubgraph_radiusClamped_above5BecomesMax5() {
        // Providing radius=999 should be clamped to 5 without error
        GraphNode seed = GraphNode.builder()
                .nodeId("seed-5").nodeType(NodeLevel.ENTITY).title("Seed5").build();

        when(graphService.getNodesInFactSheet(FS_ID)).thenReturn(List.of(seed));
        when(graphService.getEdgesForNodeInFactSheet("seed-5", FS_ID)).thenReturn(List.of());

        SubgraphRequest req = new SubgraphRequest(List.of("seed-5"), 999, null, 0.0);
        ResponseEntity<?> response = controller.buildSubgraph(FS_ID, req);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> stats = (Map<String, Object>) body.get("statistics");
        assertEquals(5, stats.get("radius"), "Radius should be clamped to 5");
    }

    @Test
    void buildSubgraph_serviceThrows_returns503() {
        // buildSubgraph now calls getNode(seedId) first; throw there to exercise the 503 path.
        when(graphService.getNode(anyString()))
                .thenThrow(new RuntimeException("DB unavailable"));

        SubgraphRequest req = new SubgraphRequest(List.of("seed-6"), 2, null, 0.0);
        ResponseEntity<?> response = controller.buildSubgraph(FS_ID, req);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertTrue(body.containsKey("error"));
    }
}
