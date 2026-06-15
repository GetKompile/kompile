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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GraphValidationServiceTest {

    @Mock
    private GraphNodeRepository nodeRepository;

    @Mock
    private GraphEdgeRepository edgeRepository;

    @Mock
    private KnowledgeGraphService knowledgeGraphService;

    @Mock
    private EnrichmentAuditService auditService;

    private GraphValidationService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new GraphValidationService(
                nodeRepository,
                edgeRepository,
                knowledgeGraphService,
                auditService,
                objectMapper
        );
    }

    private static final EnrichmentConfig DEFAULT_CONFIG = EnrichmentConfig.builder().build();

    private GraphNode node(Long id, String nodeId, Long factSheetId) {
        return GraphNode.builder()
                .id(id)
                .nodeId(nodeId)
                .nodeType(NodeLevel.ENTITY)
                .title(nodeId)
                .externalId("ext-" + nodeId)
                .factSheetId(factSheetId)
                .build();
    }

    private GraphEdge edge(Long id, String edgeId, GraphNode source, GraphNode target,
                           EdgeType edgeType, String label, String description,
                           Double weight, boolean bidirectional, Long factSheetId) {
        return GraphEdge.builder()
                .id(id)
                .edgeId(edgeId)
                .sourceNode(source)
                .targetNode(target)
                .edgeType(edgeType)
                .label(label)
                .description(description)
                .weight(weight)
                .confidence(weight)
                .bidirectional(bidirectional)
                .factSheetId(factSheetId)
                .build();
    }

    private void stubGraph(Long factSheetId, List<GraphNode> nodes, List<GraphEdge> edges) {
        when(nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.ENTITY))
                .thenReturn(nodes);
        when(nodeRepository.findByFactSheetId(factSheetId)).thenReturn(nodes);
        for (GraphNode node : nodes) {
            when(edgeRepository.findBySourceNodeIdOrTargetNodeId(node.getId())).thenReturn(edges);
        }
        when(edgeRepository.findByFactSheetId(factSheetId)).thenReturn(edges);
    }

    // ─── fixBlankTitles ────────────────────────────────────────────────────────

    @Test
    void fixBlankTitlesFromDescription() {
        Long factSheetId = 1L;
        String description = "A detailed description of this entity in the knowledge graph";
        GraphNode entity = GraphNode.builder()
                .id(1L).nodeId(UUID.randomUUID().toString())
                .nodeType(NodeLevel.ENTITY)
                .title(null)
                .description(description)
                .confidence(0.7).edgeCount(0)
                .factSheetId(factSheetId).externalId("ext-1").build();

        when(nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.ENTITY))
                .thenReturn(List.of(entity));
        when(nodeRepository.findByFactSheetId(factSheetId)).thenReturn(List.of(entity));
        when(edgeRepository.findBySourceNodeIdOrTargetNodeId(entity.getId())).thenReturn(List.of());

        int fixed = service.validate(factSheetId, "job-1", DEFAULT_CONFIG);

        assertTrue(fixed >= 1, "Entity with null title and a description should be fixed");
        // The saved entity should have a non-null title derived from description
        ArgumentCaptor<GraphNode> savedCaptor = ArgumentCaptor.forClass(GraphNode.class);
        verify(nodeRepository).save(savedCaptor.capture());
        assertNotNull(savedCaptor.getValue().getTitle());
        assertTrue(savedCaptor.getValue().getTitle().startsWith(description.substring(0, 10)),
                "Title should start with description content");
    }

    @Test
    void fixBlankTitlesFromAlias() throws Exception {
        Long factSheetId = 2L;
        String metadataJson = objectMapper.writeValueAsString(
                java.util.Map.of("aliases", java.util.List.of("MyAlias", "AnotherAlias")));

        GraphNode entity = GraphNode.builder()
                .id(1L).nodeId(UUID.randomUUID().toString())
                .nodeType(NodeLevel.ENTITY)
                .title(null)
                .description(null)
                .metadataJson(metadataJson)
                .confidence(0.7).edgeCount(0)
                .factSheetId(factSheetId).externalId("ext-2").build();

        when(nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.ENTITY))
                .thenReturn(List.of(entity));
        when(nodeRepository.findByFactSheetId(factSheetId)).thenReturn(List.of(entity));
        when(edgeRepository.findBySourceNodeIdOrTargetNodeId(entity.getId())).thenReturn(List.of());

        int fixed = service.validate(factSheetId, "job-2", DEFAULT_CONFIG);

        assertTrue(fixed >= 1, "Entity with null title and metadataJson aliases should be fixed");
        ArgumentCaptor<GraphNode> savedCaptor = ArgumentCaptor.forClass(GraphNode.class);
        verify(nodeRepository).save(savedCaptor.capture());
        assertEquals("MyAlias", savedCaptor.getValue().getTitle(),
                "Title should be set to the first alias");
    }

    @Test
    void fixBlankTitlesSkipsNonBlank() {
        Long factSheetId = 3L;
        GraphNode entity = GraphNode.builder()
                .id(1L).nodeId(UUID.randomUUID().toString())
                .nodeType(NodeLevel.ENTITY)
                .title("Existing Title")
                .description("has a description too")
                .confidence(0.7).edgeCount(0)
                .factSheetId(factSheetId).externalId("ext-3").build();

        when(nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.ENTITY))
                .thenReturn(List.of(entity));
        when(nodeRepository.findByFactSheetId(factSheetId)).thenReturn(List.of(entity));
        when(edgeRepository.findBySourceNodeIdOrTargetNodeId(entity.getId())).thenReturn(List.of());

        int fixed = service.validate(factSheetId, "job-3", DEFAULT_CONFIG);

        assertEquals(0, fixed, "Entity with an existing title should not be modified");
        verify(nodeRepository, never()).save(any());
    }

    // ─── removeDanglingEdges ───────────────────────────────────────────────────

    @Test
    void removeDanglingEdges() {
        Long factSheetId = 4L;
        String validNodeId = UUID.randomUUID().toString();
        String outsideNodeId = UUID.randomUUID().toString(); // not in this fact sheet

        GraphNode validNode = GraphNode.builder()
                .id(1L).nodeId(validNodeId)
                .nodeType(NodeLevel.ENTITY).title("valid")
                .externalId("ext-valid").factSheetId(factSheetId).build();

        // Source is outside this fact sheet — dangling edge
        GraphNode outsideNode = GraphNode.builder()
                .id(99L).nodeId(outsideNodeId)
                .nodeType(NodeLevel.ENTITY).title("outside")
                .externalId("ext-outside").factSheetId(999L).build();

        String danglingEdgeId = UUID.randomUUID().toString();
        GraphEdge danglingEdge = GraphEdge.builder()
                .id(100L).edgeId(danglingEdgeId)
                .sourceNode(outsideNode).targetNode(validNode)
                .edgeType(EdgeType.CONTAINS).weight(0.9)
                .factSheetId(factSheetId).build();

        when(nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.ENTITY))
                .thenReturn(List.of(validNode));
        when(nodeRepository.findByFactSheetId(factSheetId)).thenReturn(List.of(validNode));
        when(edgeRepository.findBySourceNodeIdOrTargetNodeId(validNode.getId()))
                .thenReturn(List.of(danglingEdge));

        int fixed = service.validate(factSheetId, "job-4", DEFAULT_CONFIG);

        assertTrue(fixed >= 1, "Dangling edge pointing to node outside the factSheet should be removed");
        ArgumentCaptor<List<String>> edgeIdsCaptor = ArgumentCaptor.forClass(List.class);
        verify(knowledgeGraphService).deleteEdgesBulk(edgeIdsCaptor.capture());
        assertTrue(edgeIdsCaptor.getValue().contains(danglingEdgeId));
    }

    @Test
    void noDanglingEdgesNoDeletes() {
        Long factSheetId = 5L;
        String nodeIdA = UUID.randomUUID().toString();
        String nodeIdB = UUID.randomUUID().toString();

        GraphNode nodeA = GraphNode.builder()
                .id(1L).nodeId(nodeIdA)
                .nodeType(NodeLevel.ENTITY).title("entity A")
                .externalId("ext-a").factSheetId(factSheetId).build();

        GraphNode nodeB = GraphNode.builder()
                .id(2L).nodeId(nodeIdB)
                .nodeType(NodeLevel.ENTITY).title("entity B")
                .externalId("ext-b").factSheetId(factSheetId).build();

        // Edge between two valid nodes in the same factSheet — not dangling
        GraphEdge validEdge = GraphEdge.builder()
                .id(100L).edgeId(UUID.randomUUID().toString())
                .sourceNode(nodeA).targetNode(nodeB)
                .edgeType(EdgeType.SHARED_ENTITY).weight(0.8)
                .factSheetId(factSheetId).build();

        when(nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.ENTITY))
                .thenReturn(List.of(nodeA, nodeB));
        when(nodeRepository.findByFactSheetId(factSheetId)).thenReturn(List.of(nodeA, nodeB));
        when(edgeRepository.findBySourceNodeIdOrTargetNodeId(nodeA.getId()))
                .thenReturn(List.of(validEdge));
        when(edgeRepository.findBySourceNodeIdOrTargetNodeId(nodeB.getId()))
                .thenReturn(List.of(validEdge));

        int fixed = service.validate(factSheetId, "job-5", DEFAULT_CONFIG);

        assertEquals(0, fixed, "All edges point to valid nodes — no dangling edges should be removed");
        verify(knowledgeGraphService, never()).deleteEdgesBulk(any());
    }

    // ─── removeDuplicateEdges ──────────────────────────────────────────────────

    @Test
    void removeDuplicateSemanticRelationEdges() {
        Long factSheetId = 6L;
        GraphNode nodeA = node(1L, "node-a", factSheetId);
        GraphNode nodeB = node(2L, "node-b", factSheetId);

        GraphEdge weakerDuplicate = edge(10L, "edge-weak", nodeA, nodeB,
                EdgeType.USER_DEFINED, "VERSION_OF", "Older duplicate",
                0.6, true, factSheetId);
        GraphEdge strongerKeeper = edge(11L, "edge-strong", nodeA, nodeB,
                EdgeType.USER_DEFINED, "VERSION_OF", "Newer duplicate",
                0.9, true, factSheetId);

        stubGraph(factSheetId, List.of(nodeA, nodeB), List.of(weakerDuplicate, strongerKeeper));

        int fixed = service.validate(factSheetId, "job-6", DEFAULT_CONFIG);

        assertEquals(1, fixed, "One duplicate semantic relation should be removed");
        ArgumentCaptor<List<String>> edgeIdsCaptor = ArgumentCaptor.forClass(List.class);
        verify(knowledgeGraphService).deleteEdgesBulk(edgeIdsCaptor.capture());
        assertEquals(List.of("edge-weak"), edgeIdsCaptor.getValue());
        verify(auditService).logAction(eq(factSheetId), eq("job-6"), eq("CLEAN"),
                eq("REMOVE_DUPLICATE_EDGE"), isNull(), eq("GRAPH_EDGE"),
                isNull(), isNull(), contains("Removed 1 duplicate"));
    }

    @Test
    void recalculatesEdgeCountsAfterDuplicateRelationRemoval() {
        Long factSheetId = 9L;
        GraphNode nodeA = node(1L, "node-a", factSheetId);
        nodeA.setEdgeCount(2);
        GraphNode nodeB = node(2L, "node-b", factSheetId);
        nodeB.setEdgeCount(2);

        GraphEdge duplicate = edge(10L, "edge-duplicate", nodeA, nodeB,
                EdgeType.USER_DEFINED, "VERSION_OF", "Duplicate",
                0.6, true, factSheetId);
        GraphEdge keeper = edge(11L, "edge-keeper", nodeA, nodeB,
                EdgeType.USER_DEFINED, "VERSION_OF", "Keeper",
                0.9, true, factSheetId);

        when(nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.ENTITY))
                .thenReturn(List.of(nodeA, nodeB));
        when(nodeRepository.findByFactSheetId(factSheetId)).thenReturn(List.of(nodeA, nodeB));
        when(edgeRepository.findBySourceNodeIdOrTargetNodeId(anyLong()))
                .thenReturn(List.of(duplicate, keeper));
        when(edgeRepository.findByFactSheetId(factSheetId))
                .thenReturn(List.of(duplicate, keeper))
                .thenReturn(List.of(keeper));

        int fixed = service.validate(factSheetId, "job-9", DEFAULT_CONFIG);

        assertEquals(1, fixed);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GraphNode>> nodesCaptor = ArgumentCaptor.forClass(List.class);
        verify(nodeRepository).saveAll(nodesCaptor.capture());
        assertEquals(1, nodeA.getEdgeCount());
        assertEquals(1, nodeB.getEdgeCount());
        assertTrue(nodesCaptor.getValue().containsAll(List.of(nodeA, nodeB)));
    }

    @Test
    void distinctSemanticRelationLabelsArePreserved() {
        Long factSheetId = 7L;
        GraphNode nodeA = node(1L, "node-a", factSheetId);
        GraphNode nodeB = node(2L, "node-b", factSheetId);

        GraphEdge referencesData = edge(10L, "edge-references", nodeA, nodeB,
                EdgeType.USER_DEFINED, "REFERENCES_DATA", "References spreadsheet",
                0.7, true, factSheetId);
        GraphEdge hyperlinkTo = edge(11L, "edge-hyperlink", nodeA, nodeB,
                EdgeType.USER_DEFINED, "HYPERLINK_TO", "Links to spreadsheet",
                0.8, true, factSheetId);

        stubGraph(factSheetId, List.of(nodeA, nodeB), List.of(referencesData, hyperlinkTo));

        int fixed = service.validate(factSheetId, "job-7", DEFAULT_CONFIG);

        assertEquals(0, fixed, "Different semantic relation labels on the same pair must be preserved");
        verify(knowledgeGraphService, never()).deleteEdgesBulk(any());
    }

    @Test
    void reverseBidirectionalSemanticDuplicatesAreRemoved() {
        Long factSheetId = 8L;
        GraphNode nodeA = node(1L, "node-a", factSheetId);
        GraphNode nodeB = node(2L, "node-b", factSheetId);

        GraphEdge forward = edge(10L, "edge-forward", nodeA, nodeB,
                EdgeType.USER_DEFINED, "SHARED_AUTHOR", "Both authored by alice",
                0.8, true, factSheetId);
        GraphEdge reverse = edge(11L, "edge-reverse", nodeB, nodeA,
                EdgeType.USER_DEFINED, "SHARED_AUTHOR", "Both authored by alice",
                0.8, true, factSheetId);

        stubGraph(factSheetId, List.of(nodeA, nodeB), List.of(forward, reverse));

        int fixed = service.validate(factSheetId, "job-8", DEFAULT_CONFIG);

        assertEquals(1, fixed, "Reverse bidirectional duplicate should be removed");
        ArgumentCaptor<List<String>> edgeIdsCaptor = ArgumentCaptor.forClass(List.class);
        verify(knowledgeGraphService).deleteEdgesBulk(edgeIdsCaptor.capture());
        assertEquals(List.of("edge-forward"), edgeIdsCaptor.getValue());
    }
}
