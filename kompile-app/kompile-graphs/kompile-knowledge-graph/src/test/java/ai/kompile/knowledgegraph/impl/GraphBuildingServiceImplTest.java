/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.knowledgegraph.impl;

import ai.kompile.knowledgegraph.domain.*;
import ai.kompile.knowledgegraph.repository.*;
import ai.kompile.knowledgegraph.service.*;
import ai.kompile.knowledgegraph.service.EntityExtractionService.EntityType;
import ai.kompile.knowledgegraph.service.EntityExtractionService.ExtractedEntity;
import ai.kompile.knowledgegraph.service.GraphBuildingService.BuildConfig;
import ai.kompile.knowledgegraph.service.GraphBuildingService.BuildStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link GraphBuildingServiceImpl} — entity extraction from documents,
 * shared entity edges, build lifecycle (status, cancel), and statistics.
 *
 * Note: GraphBuildingServiceImpl constructor takes 5 args (no ApplicationEventPublisher).
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class GraphBuildingServiceImplTest {

    @Mock private GraphNodeRepository nodeRepository;
    @Mock private GraphEdgeRepository edgeRepository;
    @Mock private EntityMentionRepository entityMentionRepository;
    @Mock private EntityExtractionService entityExtractionService;
    @Mock private KnowledgeGraphService knowledgeGraphService;

    private GraphBuildingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GraphBuildingServiceImpl(
                nodeRepository, edgeRepository, entityMentionRepository,
                entityExtractionService, knowledgeGraphService);
    }

    private BuildConfig syncConfig() {
        return new BuildConfig(0.5, 1, true, true, 0.7,
                List.of("PERSON", "ORGANIZATION"), 100, false);
    }

    private GraphNode docNode(String nodeId, String externalId) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .nodeType(NodeLevel.DOCUMENT)
                .externalId(externalId)
                .title(externalId)
                .build();
    }

    private GraphNode entityNode(String nodeId, String name) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .nodeType(NodeLevel.ENTITY)
                .externalId(name.toLowerCase())
                .title(name)
                .build();
    }

    // ─── processDocument ───────────────────────────────────────────────

    @Test
    void processDocument_nullContent_returnsZero() {
        assertEquals(0, service.processDocument("doc1", null, Map.of(), "src1", syncConfig()));
    }

    @Test
    void processDocument_blankContent_returnsZero() {
        assertEquals(0, service.processDocument("doc1", "   ", Map.of(), "src1", syncConfig()));
    }

    @Test
    void processDocument_extractsEntitiesAndCreatesEdges() {
        GraphNode doc = docNode("n1", "doc1");
        when(nodeRepository.findByExternalIdAndNodeType("doc1", NodeLevel.DOCUMENT))
                .thenReturn(Optional.of(doc));

        ExtractedEntity person = new ExtractedEntity("John Smith", "PERSON", 0, 10, 0.9, Map.of());
        when(entityExtractionService.extractEntities(anyString(), anyList()))
                .thenReturn(List.of(person));
        when(entityExtractionService.normalizeEntityName("John Smith")).thenReturn("john smith");

        GraphNode entityNode = entityNode("e1", "John Smith");
        when(nodeRepository.findByExternalIdAndNodeType("john smith", NodeLevel.ENTITY))
                .thenReturn(Optional.of(entityNode));

        when(entityMentionRepository.findByNodeAndEntityName(doc, "john smith"))
                .thenReturn(Optional.empty());

        EntityMention mention = EntityMention.builder()
                .node(doc).entityName("john smith").entityType("PERSON")
                .mentionCount(0).confidence(0.9).build();
        when(entityMentionRepository.save(any())).thenReturn(mention);

        when(edgeRepository.findEdgeBetweenNodes("n1", "e1")).thenReturn(Optional.empty());

        int count = service.processDocument("doc1", "John Smith is here", Map.of(), "src1", syncConfig());

        assertEquals(1, count);
        verify(knowledgeGraphService).createEdge(eq("n1"), eq("e1"), eq(EdgeType.SHARED_ENTITY),
                eq(0.9), anyString());
    }

    @Test
    void processDocument_filtersLowConfidence() {
        GraphNode doc = docNode("n1", "doc1");
        when(nodeRepository.findByExternalIdAndNodeType("doc1", NodeLevel.DOCUMENT))
                .thenReturn(Optional.of(doc));

        // Entity below minEntityConfidence (0.5)
        ExtractedEntity lowConf = new ExtractedEntity("Maybe", "PERSON", 0, 5, 0.2, Map.of());
        when(entityExtractionService.extractEntities(anyString(), anyList()))
                .thenReturn(List.of(lowConf));

        int count = service.processDocument("doc1", "Maybe something", Map.of(), "src1", syncConfig());

        assertEquals(0, count);
        verify(knowledgeGraphService, never()).createEdge(any(), any(), any(), anyDouble(), anyString());
    }

    @Test
    void processDocument_createsDocNodeIfMissing() {
        when(nodeRepository.findByExternalIdAndNodeType("doc1", NodeLevel.DOCUMENT))
                .thenReturn(Optional.empty());
        GraphNode newDoc = docNode("n1", "doc1");
        when(knowledgeGraphService.createDocumentNode(isNull(), eq("doc1"), eq("doc1"), any()))
                .thenReturn(newDoc);
        when(entityExtractionService.extractEntities(anyString(), anyList()))
                .thenReturn(List.of());

        service.processDocument("doc1", "content", Map.of(), null, syncConfig());

        verify(knowledgeGraphService).createDocumentNode(isNull(), eq("doc1"), eq("doc1"), any());
    }

    @Test
    void processDocument_skipsExistingEdge() {
        GraphNode doc = docNode("n1", "doc1");
        when(nodeRepository.findByExternalIdAndNodeType("doc1", NodeLevel.DOCUMENT))
                .thenReturn(Optional.of(doc));

        ExtractedEntity person = new ExtractedEntity("John", "PERSON", 0, 4, 0.9, Map.of());
        when(entityExtractionService.extractEntities(anyString(), anyList()))
                .thenReturn(List.of(person));
        when(entityExtractionService.normalizeEntityName("John")).thenReturn("john");

        GraphNode entityNode = entityNode("e1", "John");
        when(nodeRepository.findByExternalIdAndNodeType("john", NodeLevel.ENTITY))
                .thenReturn(Optional.of(entityNode));
        when(entityMentionRepository.findByNodeAndEntityName(doc, "john"))
                .thenReturn(Optional.of(EntityMention.builder()
                        .node(doc).entityName("john").entityType("PERSON")
                        .mentionCount(1).confidence(0.8).build()));

        // Edge already exists
        when(edgeRepository.findEdgeBetweenNodes("n1", "e1"))
                .thenReturn(Optional.of(new GraphEdge()));

        service.processDocument("doc1", "John is here", Map.of(), "src1", syncConfig());

        verify(knowledgeGraphService, never()).createEdge(any(), any(), any(), anyDouble(), anyString());
    }

    // ─── createSharedEntityEdges ───────────────────────────────────────

    @Test
    void createSharedEntityEdges_createsEdgesForSharedPairs() {
        GraphNode n1 = docNode("n1", "doc1");
        GraphNode n2 = docNode("n2", "doc2");
        List<Object[]> pairs = new ArrayList<>();
        pairs.add(new Object[]{1L, 2L, 5L});
        when(entityMentionRepository.findNodePairsWithSharedEntities(2))
                .thenReturn(pairs);
        when(nodeRepository.findById(1L)).thenReturn(Optional.of(n1));
        when(nodeRepository.findById(2L)).thenReturn(Optional.of(n2));
        when(edgeRepository.findEdgeBetweenNodes("n1", "n2")).thenReturn(Optional.empty());

        int created = service.createSharedEntityEdges(2);

        assertEquals(1, created);
        verify(knowledgeGraphService).createEdge(eq("n1"), eq("n2"), eq(EdgeType.SHARED_ENTITY),
                eq(0.5), anyString());
    }

    @Test
    void createSharedEntityEdges_skipsExistingEdge() {
        GraphNode n1 = docNode("n1", "doc1");
        GraphNode n2 = docNode("n2", "doc2");
        List<Object[]> pairs = new ArrayList<>();
        pairs.add(new Object[]{1L, 2L, 3L});
        when(entityMentionRepository.findNodePairsWithSharedEntities(1))
                .thenReturn(pairs);
        when(nodeRepository.findById(1L)).thenReturn(Optional.of(n1));
        when(nodeRepository.findById(2L)).thenReturn(Optional.of(n2));
        when(edgeRepository.findEdgeBetweenNodes("n1", "n2"))
                .thenReturn(Optional.of(new GraphEdge()));

        int created = service.createSharedEntityEdges(1);

        assertEquals(0, created);
    }

    @Test
    void createSharedEntityEdges_capsWeightAtOne() {
        GraphNode n1 = docNode("n1", "doc1");
        GraphNode n2 = docNode("n2", "doc2");
        List<Object[]> pairs = new ArrayList<>();
        pairs.add(new Object[]{1L, 2L, 20L}); // 20 shared → weight min(1.0, 20/10) = 1.0
        when(entityMentionRepository.findNodePairsWithSharedEntities(1))
                .thenReturn(pairs);
        when(nodeRepository.findById(1L)).thenReturn(Optional.of(n1));
        when(nodeRepository.findById(2L)).thenReturn(Optional.of(n2));
        when(edgeRepository.findEdgeBetweenNodes("n1", "n2")).thenReturn(Optional.empty());

        service.createSharedEntityEdges(1);

        verify(knowledgeGraphService).createEdge(eq("n1"), eq("n2"), eq(EdgeType.SHARED_ENTITY),
                eq(1.0), anyString());
    }

    @Test
    void createSharedEntityEdges_skipsNullNodes() {
        List<Object[]> pairs = new ArrayList<>();
        pairs.add(new Object[]{1L, 2L, 3L});
        when(entityMentionRepository.findNodePairsWithSharedEntities(1))
                .thenReturn(pairs);
        when(nodeRepository.findById(1L)).thenReturn(Optional.empty());
        when(nodeRepository.findById(2L)).thenReturn(Optional.empty());

        int created = service.createSharedEntityEdges(1);

        assertEquals(0, created);
    }

    // ─── Build lifecycle ───────────────────────────────────────────────

    @Test
    void buildGraphFromAllSources_sync_returnsBuildStatus() {
        when(nodeRepository.findAllSources()).thenReturn(List.of());
        when(entityMentionRepository.findNodePairsWithSharedEntities(anyInt())).thenReturn(List.of());

        BuildStatus status = service.buildGraphFromAllSources(syncConfig());

        assertNotNull(status.jobId());
        assertEquals("COMPLETED", status.status());
    }

    @Test
    void buildGraphFromSources_sync_returnsBuildStatus() {
        when(nodeRepository.findByNodeId("src1")).thenReturn(Optional.empty());
        when(entityMentionRepository.findNodePairsWithSharedEntities(anyInt())).thenReturn(List.of());

        BuildStatus status = service.buildGraphFromSources(List.of("src1"), syncConfig());

        assertNotNull(status.jobId());
        assertEquals("COMPLETED", status.status());
    }

    @Test
    void getBuildStatus_returnsNullForUnknownJob() {
        assertNull(service.getBuildStatus("nonexistent"));
    }

    @Test
    void getBuildStatus_returnsStatusAfterBuild() {
        when(nodeRepository.findAllSources()).thenReturn(List.of());
        when(entityMentionRepository.findNodePairsWithSharedEntities(anyInt())).thenReturn(List.of());

        BuildStatus result = service.buildGraphFromAllSources(syncConfig());
        BuildStatus retrieved = service.getBuildStatus(result.jobId());

        assertNotNull(retrieved);
        assertEquals("COMPLETED", retrieved.status());
    }

    // ─── Cancel ────────────────────────────────────────────────────────

    @Test
    void cancelBuild_nonexistentJob_returnsFalse() {
        assertFalse(service.cancelBuild("nonexistent"));
    }

    // ─── Running jobs ──────────────────────────────────────────────────

    @Test
    void getRunningJobs_initiallyEmpty() {
        assertTrue(service.getRunningJobs().isEmpty());
    }

    // ─── Clear graph ───────────────────────────────────────────────────

    @Test
    void clearGraph_deletesAll() {
        service.clearGraph();

        verify(entityMentionRepository).deleteAll();
        verify(edgeRepository).deleteAll();
        verify(nodeRepository).deleteAll();
    }

    // ─── Build statistics ──────────────────────────────────────────────

    @Test
    void getBuildStatistics_returnsCounts() {
        when(nodeRepository.count()).thenReturn(10L);
        when(edgeRepository.count()).thenReturn(5L);
        when(entityMentionRepository.count()).thenReturn(20L);
        when(nodeRepository.countByNodeType(any())).thenReturn(0L);
        when(edgeRepository.countByEdgeType(any())).thenReturn(0L);

        Map<String, Object> stats = service.getBuildStatistics();

        assertEquals(10L, stats.get("totalNodes"));
        assertEquals(5L, stats.get("totalEdges"));
        assertEquals(20L, stats.get("totalEntityMentions"));
        assertEquals(0, stats.get("runningJobs"));
    }

    @Test
    void getBuildStatistics_includesNonZeroNodeTypes() {
        when(nodeRepository.count()).thenReturn(3L);
        when(edgeRepository.count()).thenReturn(1L);
        when(entityMentionRepository.count()).thenReturn(2L);
        when(nodeRepository.countByNodeType(NodeLevel.ENTITY)).thenReturn(2L);
        when(nodeRepository.countByNodeType(argThat(l -> l != NodeLevel.ENTITY))).thenReturn(0L);
        when(edgeRepository.countByEdgeType(EdgeType.SHARED_ENTITY)).thenReturn(1L);
        when(edgeRepository.countByEdgeType(argThat(t -> t != EdgeType.SHARED_ENTITY))).thenReturn(0L);

        Map<String, Object> stats = service.getBuildStatistics();

        @SuppressWarnings("unchecked")
        Map<String, Long> nodesByType = (Map<String, Long>) stats.get("nodesByType");
        assertEquals(2L, nodesByType.get("ENTITY"));

        @SuppressWarnings("unchecked")
        Map<String, Long> edgesByType = (Map<String, Long>) stats.get("edgesByType");
        assertEquals(1L, edgesByType.get("SHARED_ENTITY"));
    }

    // ─── findOrCreateEntityNode ────────────────────────────────────────

    @Test
    void findOrCreateEntityNode_findsExisting() {
        when(entityExtractionService.normalizeEntityName("Apple")).thenReturn("apple");
        GraphNode existing = entityNode("e1", "Apple");
        when(nodeRepository.findByExternalIdAndNodeType("apple", NodeLevel.ENTITY))
                .thenReturn(Optional.of(existing));

        GraphNode result = service.findOrCreateEntityNode("Apple", "ORG");

        assertSame(existing, result);
        verify(nodeRepository, never()).save(any());
    }

    @Test
    void findOrCreateEntityNode_createsNew() {
        when(entityExtractionService.normalizeEntityName("NewEntity")).thenReturn("newentity");
        when(nodeRepository.findByExternalIdAndNodeType("newentity", NodeLevel.ENTITY))
                .thenReturn(Optional.empty());
        when(nodeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        GraphNode result = service.findOrCreateEntityNode("NewEntity", "CONCEPT");

        assertNotNull(result);
        assertEquals(NodeLevel.ENTITY, result.getNodeType());
        assertEquals("newentity", result.getExternalId());
        assertEquals("NewEntity", result.getTitle());
        verify(nodeRepository).save(any());
    }
}
