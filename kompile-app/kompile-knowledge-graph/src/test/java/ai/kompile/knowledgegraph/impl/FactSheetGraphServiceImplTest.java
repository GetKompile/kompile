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
import ai.kompile.knowledgegraph.service.ConceptExtractor.*;
import ai.kompile.knowledgegraph.service.FactSheetGraphService.*;
import ai.kompile.knowledgegraph.service.SourceLinkingService.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link FactSheetGraphServiceImpl} — graph building (sync/async), status tracking,
 * cancellation, visualization data, statistics, clearing, running jobs, processIndexedDocument,
 * rebuildConceptEdges, topConcepts, searchNodes, and relatedDocuments.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class FactSheetGraphServiceImplTest {

    @Mock private GraphNodeRepository nodeRepository;
    @Mock private GraphEdgeRepository edgeRepository;
    @Mock private EntityMentionRepository entityMentionRepository;
    @Mock private KnowledgeGraphService knowledgeGraphService;
    @Mock private ConceptExtractor conceptExtractor;
    @Mock private SourceLinkingService sourceLinkingService;

    private FactSheetGraphServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FactSheetGraphServiceImpl(nodeRepository, edgeRepository,
                entityMentionRepository, knowledgeGraphService, conceptExtractor, sourceLinkingService);
    }

    private GraphNode stubNode(String nodeId, String title, NodeLevel type) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .title(title)
                .nodeType(type)
                .externalId(nodeId)
                .build();
    }

    private GraphEdge stubEdge(String edgeId, GraphNode source, GraphNode target, EdgeType type, double weight) {
        return GraphEdge.builder()
                .edgeId(edgeId)
                .sourceNode(source)
                .targetNode(target)
                .edgeType(type)
                .weight(weight)
                .bidirectional(false)
                .build();
    }

    private GraphBuildConfig syncBuildConfig() {
        ExtractionConfig extractionConfig = ExtractionConfig.defaults();
        LinkingConfig linkingConfig = LinkingConfig.defaults();
        return new GraphBuildConfig(
                extractionConfig,
                linkingConfig,
                50,      // minConceptConfidence
                2,       // minSharedConceptsForEdge
                true,    // includeHierarchicalEdges
                true,    // computeConceptEdges
                true,    // computeSourceLinks
                false,   // asyncProcessing
                0        // maxDocumentsToProcess (unlimited)
        );
    }

    // ─── buildGraphFromIndex ──────────────────────────────────────────

    @Test
    void buildGraphFromIndex_sync_returnsCompletedStatus() {
        when(nodeRepository.findSourcesByFactSheet(1L)).thenReturn(List.of());
        when(nodeRepository.findByFactSheetId(1L)).thenReturn(List.of());
        when(sourceLinkingService.linkAllSources(eq(1L), any())).thenReturn(
                new LinkingResult(0, 0, 0, 0, List.of(), Map.of()));
        // getGraphStatistics dependencies
        for (NodeLevel level : NodeLevel.values()) {
            when(nodeRepository.countByFactSheetIdAndNodeType(1L, level)).thenReturn(0L);
        }
        for (EdgeType type : EdgeType.values()) {
            when(edgeRepository.countByFactSheetIdAndEdgeType(1L, type)).thenReturn(0L);
        }
        when(entityMentionRepository.countDistinctEntitiesByFactSheet(1L)).thenReturn(0L);
        when(entityMentionRepository.findTopEntitiesByFactSheet(eq(1L), any())).thenReturn(List.of());
        when(sourceLinkingService.getConnectivitySummary(1L)).thenReturn(Map.of());

        GraphBuildStatus status = service.buildGraphFromIndex(1L, syncBuildConfig());

        assertNotNull(status);
        assertEquals("COMPLETED", status.status());
        assertEquals(1L, status.factSheetId());
    }

    @Test
    void buildGraphFromIndex_withDocuments_processesContent() {
        GraphNode source = stubNode("src1", "Source", NodeLevel.SOURCE);
        GraphNode doc = GraphNode.builder()
                .nodeId("doc1").title("Document").nodeType(NodeLevel.DOCUMENT)
                .externalId("doc1").description("Test content for extraction")
                .contentPreview("Preview").build();

        when(nodeRepository.findSourcesByFactSheet(1L)).thenReturn(List.of(source));
        when(nodeRepository.findBySourceIdAndType("src1", NodeLevel.DOCUMENT)).thenReturn(List.of(doc));
        when(nodeRepository.findByFactSheetId(1L)).thenReturn(List.of(source, doc));

        // Concept extraction returns empty
        when(conceptExtractor.extractConcepts(any(), any())).thenReturn(
                new ExtractionResult(List.of(), List.of(), Map.of()));

        // processIndexedDocument needs these
        when(nodeRepository.findByExternalIdAndNodeTypeAndFactSheetId(any(), any(), eq(1L)))
                .thenReturn(Optional.empty());
        GraphNode savedNode = stubNode("saved", "Saved", NodeLevel.DOCUMENT);
        when(nodeRepository.save(any())).thenReturn(savedNode);

        // rebuildConceptEdges
        when(entityMentionRepository.findNodePairsWithSharedEntitiesByFactSheet(eq(1L), anyInt()))
                .thenReturn(List.of());
        // linkAllSources
        when(sourceLinkingService.linkAllSources(eq(1L), any())).thenReturn(
                new LinkingResult(0, 0, 0, 0, List.of(), Map.of()));

        // getGraphStatistics
        for (NodeLevel level : NodeLevel.values()) {
            when(nodeRepository.countByFactSheetIdAndNodeType(1L, level)).thenReturn(0L);
        }
        for (EdgeType type : EdgeType.values()) {
            when(edgeRepository.countByFactSheetIdAndEdgeType(1L, type)).thenReturn(0L);
        }
        when(entityMentionRepository.countDistinctEntitiesByFactSheet(1L)).thenReturn(0L);
        when(entityMentionRepository.findTopEntitiesByFactSheet(eq(1L), any())).thenReturn(List.of());
        when(sourceLinkingService.getConnectivitySummary(1L)).thenReturn(Map.of());

        GraphBuildStatus status = service.buildGraphFromIndex(1L, syncBuildConfig());

        assertEquals("COMPLETED", status.status());
    }

    // ─── getBuildStatus ───────────────────────────────────────────────

    @Test
    void getBuildStatus_unknownJobId_returnsNull() {
        assertNull(service.getBuildStatus("unknown-job-id"));
    }

    @Test
    void getBuildStatus_afterBuild_returnsStatus() {
        when(nodeRepository.findSourcesByFactSheet(1L)).thenReturn(List.of());
        when(nodeRepository.findByFactSheetId(1L)).thenReturn(List.of());
        when(sourceLinkingService.linkAllSources(eq(1L), any())).thenReturn(
                new LinkingResult(0, 0, 0, 0, List.of(), Map.of()));
        for (NodeLevel level : NodeLevel.values()) {
            when(nodeRepository.countByFactSheetIdAndNodeType(1L, level)).thenReturn(0L);
        }
        for (EdgeType type : EdgeType.values()) {
            when(edgeRepository.countByFactSheetIdAndEdgeType(1L, type)).thenReturn(0L);
        }
        when(entityMentionRepository.countDistinctEntitiesByFactSheet(1L)).thenReturn(0L);
        when(entityMentionRepository.findTopEntitiesByFactSheet(eq(1L), any())).thenReturn(List.of());
        when(sourceLinkingService.getConnectivitySummary(1L)).thenReturn(Map.of());

        GraphBuildStatus status = service.buildGraphFromIndex(1L, syncBuildConfig());

        GraphBuildStatus retrieved = service.getBuildStatus(status.jobId());
        assertNotNull(retrieved);
        assertEquals(status.jobId(), retrieved.jobId());
    }

    // ─── cancelBuild ──────────────────────────────────────────────────

    @Test
    void cancelBuild_noRunningJob_returnsFalse() {
        assertFalse(service.cancelBuild("nonexistent-job"));
    }

    // ─── getVisualizationData ─────────────────────────────────────────

    @Test
    void getVisualizationData_withNodes_returnsD3Format() {
        GraphNode src = stubNode("s1", "Source1", NodeLevel.SOURCE);
        GraphNode doc = stubNode("d1", "Doc1", NodeLevel.DOCUMENT);

        Page<GraphNode> sourcePage = new PageImpl<>(List.of(src));
        Page<GraphNode> docPage = new PageImpl<>(List.of(doc));
        Page<GraphNode> entityPage = new PageImpl<>(List.of());

        when(nodeRepository.findByFactSheetIdAndNodeType(eq(1L), eq(NodeLevel.SOURCE), any(PageRequest.class)))
                .thenReturn(sourcePage);
        when(nodeRepository.findByFactSheetIdAndNodeType(eq(1L), eq(NodeLevel.DOCUMENT), any(PageRequest.class)))
                .thenReturn(docPage);
        when(nodeRepository.findByFactSheetIdAndNodeType(eq(1L), eq(NodeLevel.ENTITY), any(PageRequest.class)))
                .thenReturn(entityPage);

        GraphEdge edge = stubEdge("e1", src, doc, EdgeType.HIERARCHICAL, 1.0);
        when(edgeRepository.findByFactSheetId(1L)).thenReturn(List.of(edge));

        GraphVisualizationData viz = service.getVisualizationData(1L, 50, 50);

        assertEquals(2, viz.nodes().size());
        assertEquals(1, viz.edges().size());
        assertEquals(1L, viz.metadata().get("factSheetId"));
    }

    @Test
    void getVisualizationData_unlimitedNodes_returnsAll() {
        GraphNode n1 = stubNode("n1", "N1", NodeLevel.SOURCE);
        when(nodeRepository.findByFactSheetId(1L)).thenReturn(List.of(n1));
        when(edgeRepository.findByFactSheetId(1L)).thenReturn(List.of());

        GraphVisualizationData viz = service.getVisualizationData(1L, 0, 0);

        assertEquals(1, viz.nodes().size());
    }

    @Test
    void getVisualizationData_filtersEdgesWithMissingEndpoints() {
        GraphNode n1 = stubNode("n1", "N1", NodeLevel.SOURCE);
        GraphNode n2 = stubNode("n2", "N2", NodeLevel.DOCUMENT);
        // n2 not in the returned node set (maxNodes=1 only gets SOURCE)
        Page<GraphNode> sourcePage = new PageImpl<>(List.of(n1));

        when(nodeRepository.findByFactSheetIdAndNodeType(eq(1L), eq(NodeLevel.SOURCE), any(PageRequest.class)))
                .thenReturn(sourcePage);
        when(nodeRepository.findByFactSheetIdAndNodeType(eq(1L), eq(NodeLevel.DOCUMENT), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(nodeRepository.findByFactSheetIdAndNodeType(eq(1L), eq(NodeLevel.ENTITY), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of()));

        GraphEdge edge = stubEdge("e1", n1, n2, EdgeType.HIERARCHICAL, 1.0);
        when(edgeRepository.findByFactSheetId(1L)).thenReturn(List.of(edge));

        GraphVisualizationData viz = service.getVisualizationData(1L, 1, 50);

        // Edge should be filtered because n2 is not in the node set
        assertEquals(0, viz.edges().size());
    }

    // ─── getGraphStatistics ───────────────────────────────────────────

    @Test
    void getGraphStatistics_returnsNodeAndEdgeCounts() {
        when(nodeRepository.countByFactSheetIdAndNodeType(1L, NodeLevel.SOURCE)).thenReturn(3L);
        when(nodeRepository.countByFactSheetIdAndNodeType(1L, NodeLevel.DOCUMENT)).thenReturn(10L);
        // All other NodeLevel types return 0
        for (NodeLevel level : NodeLevel.values()) {
            if (level != NodeLevel.SOURCE && level != NodeLevel.DOCUMENT) {
                when(nodeRepository.countByFactSheetIdAndNodeType(1L, level)).thenReturn(0L);
            }
        }

        when(edgeRepository.countByFactSheetIdAndEdgeType(1L, EdgeType.HIERARCHICAL)).thenReturn(10L);
        for (EdgeType type : EdgeType.values()) {
            if (type != EdgeType.HIERARCHICAL) {
                when(edgeRepository.countByFactSheetIdAndEdgeType(1L, type)).thenReturn(0L);
            }
        }

        when(entityMentionRepository.countDistinctEntitiesByFactSheet(1L)).thenReturn(42L);
        List<Object[]> topConceptRows = new ArrayList<>();
        topConceptRows.add(new Object[]{"kubernetes", 15L});
        when(entityMentionRepository.findTopEntitiesByFactSheet(eq(1L), any()))
                .thenReturn(topConceptRows);
        when(sourceLinkingService.getConnectivitySummary(1L)).thenReturn(Map.of("totalSources", 3));

        Map<String, Object> stats = service.getGraphStatistics(1L);

        @SuppressWarnings("unchecked")
        Map<String, Long> nodesByType = (Map<String, Long>) stats.get("nodesByType");
        assertEquals(3L, nodesByType.get("SOURCE"));
        assertEquals(10L, nodesByType.get("DOCUMENT"));
        assertFalse(nodesByType.containsKey("ENTITY")); // count was 0

        assertEquals(42L, stats.get("distinctConcepts"));
    }

    // ─── clearGraph ───────────────────────────────────────────────────

    @Test
    void clearGraph_deletesAllComponents() {
        when(entityMentionRepository.deleteByFactSheetId(1L)).thenReturn(5);
        when(edgeRepository.deleteByFactSheetId(1L)).thenReturn(10);
        when(nodeRepository.deleteByFactSheetId(1L)).thenReturn(20);

        int deleted = service.clearGraph(1L);

        assertEquals(35, deleted);
        verify(entityMentionRepository).deleteByFactSheetId(1L);
        verify(edgeRepository).deleteByFactSheetId(1L);
        verify(nodeRepository).deleteByFactSheetId(1L);
    }

    // ─── getRunningJobs ───────────────────────────────────────────────

    @Test
    void getRunningJobs_noJobs_returnsEmpty() {
        List<GraphBuildStatus> jobs = service.getRunningJobs();
        assertTrue(jobs.isEmpty());
    }

    // ─── processIndexedDocument ────────────────────────────────────────

    @Test
    void processIndexedDocument_nullContent_returnsZero() {
        assertEquals(0, service.processIndexedDocument(1L, "doc1", null, Map.of(), "src1", syncBuildConfig()));
    }

    @Test
    void processIndexedDocument_blankContent_returnsZero() {
        assertEquals(0, service.processIndexedDocument(1L, "doc1", "  ", Map.of(), "src1", syncBuildConfig()));
    }

    @Test
    void processIndexedDocument_withConcepts_createsEntitiesAndEdges() {
        // Source node lookup
        GraphNode sourceNode = stubNode("src1", "Source", NodeLevel.SOURCE);
        when(nodeRepository.findByExternalIdAndNodeTypeAndFactSheetId("src1", NodeLevel.SOURCE, 1L))
                .thenReturn(Optional.of(sourceNode));

        // Document node creation
        when(nodeRepository.findByExternalIdAndNodeTypeAndFactSheetId("doc1", NodeLevel.DOCUMENT, 1L))
                .thenReturn(Optional.empty());
        GraphNode docNode = stubNode("doc1-saved", "Test Doc", NodeLevel.DOCUMENT);
        when(nodeRepository.save(any(GraphNode.class))).thenReturn(docNode);

        // Concept extraction
        ExtractedConcept concept = new ExtractedConcept("Kubernetes", "kubernetes",
                "KEYWORD", 0.9, 3, "...context...");
        ExtractionResult extractionResult = new ExtractionResult(
                List.of(concept), List.of(), Map.of());
        when(conceptExtractor.extractConcepts(any(), any())).thenReturn(extractionResult);

        // Entity node creation
        GraphNode entityNode = stubNode("entity-k8s", "Kubernetes", NodeLevel.ENTITY);
        when(nodeRepository.findByExternalIdAndNodeTypeAndFactSheetId("kubernetes", NodeLevel.ENTITY, 1L))
                .thenReturn(Optional.of(entityNode));

        // Entity mention
        when(entityMentionRepository.findByNodeAndEntityNameAndFactSheet(docNode, "kubernetes", 1L))
                .thenReturn(Optional.empty());

        // Edge checking
        when(edgeRepository.findEdgeBetweenNodesInFactSheet(any(), any(), eq(1L))).thenReturn(Optional.empty());

        int count = service.processIndexedDocument(1L, "doc1", "Kubernetes orchestrates containers",
                Map.of("title", "Test Doc"), "src1", syncBuildConfig());

        // minConceptConfidence is 50, concept confidence is 0.9 (90%), should be counted
        assertEquals(1, count);
        verify(entityMentionRepository).save(any(EntityMention.class));
    }

    @Test
    void processIndexedDocument_noSource_skipsHierarchicalEdge() {
        // No source
        when(nodeRepository.findByExternalIdAndNodeTypeAndFactSheetId("doc1", NodeLevel.DOCUMENT, 1L))
                .thenReturn(Optional.empty());
        GraphNode docNode = stubNode("doc1-saved", "Doc", NodeLevel.DOCUMENT);
        when(nodeRepository.save(any(GraphNode.class))).thenReturn(docNode);

        when(conceptExtractor.extractConcepts(any(), any())).thenReturn(
                new ExtractionResult(List.of(), List.of(), Map.of()));

        service.processIndexedDocument(1L, "doc1", "content", Map.of(), null, syncBuildConfig());

        // No hierarchical edge since source is null
        verify(edgeRepository, never()).save(any(GraphEdge.class));
    }

    // ─── rebuildConceptEdges ──────────────────────────────────────────

    @Test
    void rebuildConceptEdges_noPairs_returnsZero() {
        when(entityMentionRepository.findNodePairsWithSharedEntitiesByFactSheet(1L, 2))
                .thenReturn(List.of());

        assertEquals(0, service.rebuildConceptEdges(1L, 2));
    }

    @Test
    void rebuildConceptEdges_withPairs_createsEdges() {
        GraphNode n1 = stubNode("n1", "N1", NodeLevel.DOCUMENT);
        GraphNode n2 = stubNode("n2", "N2", NodeLevel.DOCUMENT);
        when(nodeRepository.findById(1L)).thenReturn(Optional.of(n1));
        when(nodeRepository.findById(2L)).thenReturn(Optional.of(n2));

        Object[] pair = new Object[]{1L, 2L, 5L};
        List<Object[]> pairs = new ArrayList<>();
        pairs.add(pair);
        when(entityMentionRepository.findNodePairsWithSharedEntitiesByFactSheet(1L, 2))
                .thenReturn(pairs);
        when(edgeRepository.findEdgeBetweenNodesInFactSheet("n1", "n2", 1L)).thenReturn(Optional.empty());

        int created = service.rebuildConceptEdges(1L, 2);

        assertEquals(1, created);
        verify(edgeRepository).save(any(GraphEdge.class));
    }

    @Test
    void rebuildConceptEdges_existingEdge_skips() {
        GraphNode n1 = stubNode("n1", "N1", NodeLevel.DOCUMENT);
        GraphNode n2 = stubNode("n2", "N2", NodeLevel.DOCUMENT);
        when(nodeRepository.findById(1L)).thenReturn(Optional.of(n1));
        when(nodeRepository.findById(2L)).thenReturn(Optional.of(n2));

        Object[] pair = new Object[]{1L, 2L, 5L};
        List<Object[]> pairs = new ArrayList<>();
        pairs.add(pair);
        when(entityMentionRepository.findNodePairsWithSharedEntitiesByFactSheet(1L, 2))
                .thenReturn(pairs);
        when(edgeRepository.findEdgeBetweenNodesInFactSheet("n1", "n2", 1L))
                .thenReturn(Optional.of(mock(GraphEdge.class)));

        assertEquals(0, service.rebuildConceptEdges(1L, 2));
    }

    // ─── getTopConcepts ───────────────────────────────────────────────

    @Test
    void getTopConcepts_returnsFormattedList() {
        Object[] row = new Object[]{"kubernetes", 15L};
        List<Object[]> rows = new ArrayList<>();
        rows.add(row);
        when(entityMentionRepository.findTopEntitiesByFactSheet(eq(1L), any()))
                .thenReturn(rows);

        List<Map<String, Object>> result = service.getTopConcepts(1L, 10);

        assertEquals(1, result.size());
        assertEquals("kubernetes", result.get(0).get("name"));
        assertEquals(15L, result.get(0).get("totalMentions"));
    }

    @Test
    void getTopConcepts_empty_returnsEmptyList() {
        when(entityMentionRepository.findTopEntitiesByFactSheet(eq(1L), any())).thenReturn(List.of());

        List<Map<String, Object>> result = service.getTopConcepts(1L, 10);

        assertTrue(result.isEmpty());
    }

    // ─── searchNodes ──────────────────────────────────────────────────

    @Test
    void searchNodes_returnsD3FormatResults() {
        GraphNode n1 = stubNode("n1", "Kubernetes", NodeLevel.ENTITY);
        Page<GraphNode> page = new PageImpl<>(List.of(n1));
        when(nodeRepository.searchByFactSheetAndQuery(eq(1L), eq("kube"), any())).thenReturn(page);

        List<Map<String, Object>> results = service.searchNodes(1L, "kube", 10);

        assertEquals(1, results.size());
        assertEquals("n1", results.get(0).get("id"));
        assertEquals("Kubernetes", results.get(0).get("label"));
    }

    // ─── getRelatedDocuments ──────────────────────────────────────────

    @Test
    void getRelatedDocuments_nodeNotFound_returnsEmpty() {
        when(nodeRepository.findByNodeId("missing")).thenReturn(Optional.empty());

        List<Map<String, Object>> result = service.getRelatedDocuments(1L, "missing", 1, 10);

        assertTrue(result.isEmpty());
    }

    @Test
    void getRelatedDocuments_noConcepts_returnsEmpty() {
        GraphNode doc = stubNode("d1", "Doc", NodeLevel.DOCUMENT);
        when(nodeRepository.findByNodeId("d1")).thenReturn(Optional.of(doc));
        when(entityMentionRepository.findEntitiesByNodeId("d1")).thenReturn(List.of());

        List<Map<String, Object>> result = service.getRelatedDocuments(1L, "d1", 1, 10);

        assertTrue(result.isEmpty());
    }

    @Test
    void getRelatedDocuments_withSharedConcepts_returnsRelated() {
        GraphNode doc1 = stubNode("d1", "Doc1", NodeLevel.DOCUMENT);
        GraphNode doc2 = stubNode("d2", "Doc2", NodeLevel.DOCUMENT);
        when(nodeRepository.findByNodeId("d1")).thenReturn(Optional.of(doc1));
        when(nodeRepository.findByNodeId("d2")).thenReturn(Optional.of(doc2));

        when(entityMentionRepository.findEntitiesByNodeId("d1")).thenReturn(List.of("kubernetes", "docker"));

        // kubernetes is shared with d2
        EntityMention mention = mock(EntityMention.class);
        when(mention.getNode()).thenReturn(doc2);
        when(entityMentionRepository.findByEntityNameAndFactSheet("kubernetes", 1L)).thenReturn(List.of(mention));
        when(entityMentionRepository.findByEntityNameAndFactSheet("docker", 1L)).thenReturn(List.of());

        List<Map<String, Object>> result = service.getRelatedDocuments(1L, "d1", 1, 10);

        assertEquals(1, result.size());
        assertEquals("d2", result.get(0).get("nodeId"));
    }
}
