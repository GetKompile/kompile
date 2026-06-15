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
package ai.kompile.enrichment.impl.clean;

import ai.kompile.enrichment.config.EnrichmentConfig;
import ai.kompile.enrichment.impl.EnrichmentAuditService;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.repository.GraphEdgeRepository;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GraphPruningServiceTest {

    @Mock
    private GraphNodeRepository nodeRepository;

    @Mock
    private GraphEdgeRepository edgeRepository;

    @Mock
    private KnowledgeGraphService knowledgeGraphService;

    @Mock
    private EnrichmentAuditService auditService;

    private GraphPruningService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new GraphPruningService(
                nodeRepository,
                edgeRepository,
                knowledgeGraphService,
                auditService,
                objectMapper
        );
    }

    // ─── pruneOrphanEntities ───────────────────────────────────────────────────

    @Test
    void pruneOrphanEntitiesLowConfidenceNoEdges() {
        Long factSheetId = 1L;
        GraphNode entity = GraphNode.builder()
                .id(1L).nodeId(UUID.randomUUID().toString())
                .nodeType(NodeLevel.ENTITY)
                .title("weak entity").description("some desc")
                .confidence(0.1).edgeCount(0)
                .factSheetId(factSheetId).externalId("ext-1").build();

        when(nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.ENTITY))
                .thenReturn(List.of(entity));
        // No weak edges to prune
        when(edgeRepository.findByFactSheetIdAndEdgeType(eq(factSheetId), any(EdgeType.class)))
                .thenReturn(List.of());

        EnrichmentConfig config = EnrichmentConfig.builder()
                .pruneConfidenceThreshold(0.3)
                .pruneEdgeWeightThreshold(0.25)
                .build();

        int pruned = service.pruneGraph(factSheetId, "job-1", config);

        assertTrue(pruned >= 1, "Low-confidence orphan entity should be pruned, got: " + pruned);
        verify(knowledgeGraphService).deleteNode(entity.getNodeId());
    }

    @Test
    void pruneOrphanEntitiesHighConfidence() {
        Long factSheetId = 2L;
        GraphNode entity = GraphNode.builder()
                .id(1L).nodeId(UUID.randomUUID().toString())
                .nodeType(NodeLevel.ENTITY)
                .title("strong entity").description("well described")
                .confidence(0.8).edgeCount(0)
                .factSheetId(factSheetId).externalId("ext-2").build();

        when(nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.ENTITY))
                .thenReturn(List.of(entity));
        when(edgeRepository.findByFactSheetIdAndEdgeType(eq(factSheetId), any(EdgeType.class)))
                .thenReturn(List.of());

        EnrichmentConfig config = EnrichmentConfig.builder()
                .pruneConfidenceThreshold(0.3)
                .pruneEdgeWeightThreshold(0.25)
                .build();

        int pruned = service.pruneGraph(factSheetId, "job-2", config);

        assertEquals(0, pruned, "High-confidence entity should NOT be pruned");
        verify(knowledgeGraphService, never()).deleteNode(any());
    }

    @Test
    void pruneOrphanEntitiesHasEdges() {
        Long factSheetId = 3L;
        GraphNode entity = GraphNode.builder()
                .id(1L).nodeId(UUID.randomUUID().toString())
                .nodeType(NodeLevel.ENTITY)
                .title("connected entity").description("has edges")
                .confidence(0.1).edgeCount(3)
                .factSheetId(factSheetId).externalId("ext-3").build();

        when(nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.ENTITY))
                .thenReturn(List.of(entity));
        when(edgeRepository.findByFactSheetIdAndEdgeType(eq(factSheetId), any(EdgeType.class)))
                .thenReturn(List.of());

        EnrichmentConfig config = EnrichmentConfig.builder()
                .pruneConfidenceThreshold(0.3)
                .pruneEdgeWeightThreshold(0.25)
                .build();

        int pruned = service.pruneGraph(factSheetId, "job-3", config);

        assertEquals(0, pruned, "Entity with edges should NOT be pruned even if confidence is low");
        verify(knowledgeGraphService, never()).deleteNode(any());
    }

    @Test
    void pruneBlankOrphanEntities() {
        Long factSheetId = 4L;
        GraphNode entity = GraphNode.builder()
                .id(1L).nodeId(UUID.randomUUID().toString())
                .nodeType(NodeLevel.ENTITY)
                .title(null).description(null)
                .confidence(0.9).edgeCount(0)
                .factSheetId(factSheetId).externalId("ext-4").build();

        when(nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.ENTITY))
                .thenReturn(List.of(entity));
        when(edgeRepository.findByFactSheetIdAndEdgeType(eq(factSheetId), any(EdgeType.class)))
                .thenReturn(List.of());

        EnrichmentConfig config = EnrichmentConfig.builder()
                .pruneConfidenceThreshold(0.3)
                .pruneEdgeWeightThreshold(0.25)
                .build();

        int pruned = service.pruneGraph(factSheetId, "job-4", config);

        assertTrue(pruned >= 1, "Blank orphan entity (null title, null desc, no edges) should be pruned");
        verify(knowledgeGraphService).deleteNode(entity.getNodeId());
    }

    // ─── pruneWeakEdges ────────────────────────────────────────────────────────

    @Test
    void pruneWeakEdgesNoEdges() {
        Long factSheetId = 5L;
        when(nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.ENTITY))
                .thenReturn(List.of());
        when(edgeRepository.findByFactSheetIdAndEdgeType(eq(factSheetId), any(EdgeType.class)))
                .thenReturn(List.of());

        EnrichmentConfig config = EnrichmentConfig.builder()
                .pruneConfidenceThreshold(0.3)
                .pruneEdgeWeightThreshold(0.25)
                .build();

        int pruned = service.pruneGraph(factSheetId, "job-5", config);

        assertEquals(0, pruned, "No edges to prune should return 0");
    }

    @Test
    void pruneWeakEdgesScopedByFactSheet() {
        Long factSheetId = 6L;
        GraphNode nodeA = GraphNode.builder().id(10L).nodeId("a").nodeType(NodeLevel.ENTITY)
                .title("A").factSheetId(factSheetId).externalId("ext-a").edgeCount(1).confidence(1.0).build();
        GraphNode nodeB = GraphNode.builder().id(20L).nodeId("b").nodeType(NodeLevel.ENTITY)
                .title("B").factSheetId(factSheetId).externalId("ext-b").edgeCount(1).confidence(1.0).build();

        GraphEdge weakEdge = GraphEdge.builder()
                .id(100L).edgeId(UUID.randomUUID().toString())
                .sourceNode(nodeA).targetNode(nodeB)
                .edgeType(EdgeType.EMBEDDING_SIMILARITY)
                .weight(0.1)  // below threshold of 0.25
                .factSheetId(factSheetId)
                .build();
        GraphEdge strongEdge = GraphEdge.builder()
                .id(101L).edgeId(UUID.randomUUID().toString())
                .sourceNode(nodeA).targetNode(nodeB)
                .edgeType(EdgeType.EMBEDDING_SIMILARITY)
                .weight(0.9)  // above threshold
                .factSheetId(factSheetId)
                .build();

        when(nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.ENTITY))
                .thenReturn(List.of(nodeA, nodeB));
        when(edgeRepository.findByFactSheetIdAndEdgeType(factSheetId, EdgeType.EMBEDDING_SIMILARITY))
                .thenReturn(List.of(weakEdge, strongEdge));
        when(edgeRepository.findByFactSheetIdAndEdgeType(factSheetId, EdgeType.SHARED_ENTITY))
                .thenReturn(List.of());

        EnrichmentConfig config = EnrichmentConfig.builder()
                .pruneConfidenceThreshold(0.3)
                .pruneEdgeWeightThreshold(0.25)
                .build();

        int pruned = service.pruneGraph(factSheetId, "job-6", config);

        assertEquals(1, pruned, "Only the weak edge below threshold should be pruned");
        verify(edgeRepository).delete(weakEdge);
        verify(edgeRepository, never()).delete(strongEdge);
    }
}
