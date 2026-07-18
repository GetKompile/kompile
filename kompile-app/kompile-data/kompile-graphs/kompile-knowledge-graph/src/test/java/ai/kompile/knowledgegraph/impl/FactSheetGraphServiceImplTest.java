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

    @Mock private EntityMentionRepository entityMentionRepository;
    @Mock private KnowledgeGraphService knowledgeGraphService;
    @Mock private ConceptExtractor conceptExtractor;
    @Mock private SourceLinkingService sourceLinkingService;

    private FactSheetGraphServiceImpl service;

    @BeforeEach
    void setUp() {
        // 3-arg constructor: (KnowledgeGraphService, ConceptExtractor, SourceLinkingService)
        service = new FactSheetGraphServiceImpl(knowledgeGraphService, conceptExtractor, sourceLinkingService);
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
        // impl calls knowledgeGraphService.getNodesByTypeInFactSheet for SOURCE nodes
        when(knowledgeGraphService.getNodesByTypeInFactSheet(1L, NodeLevel.SOURCE)).thenReturn(List.of());
        when(sourceLinkingService.linkAllSources(eq(1L), any())).thenReturn(
                new LinkingResult(0, 0, 0, 0, List.of(), Map.of()));
        // impl calls knowledgeGraphService.countActiveNodes after build
        when(knowledgeGraphService.countActiveNodes(1L)).thenReturn(0L);
        // getGraphStatistics dependencies
        for (NodeLevel level : NodeLevel.values()) {
            when(knowledgeGraphService.countNodesByTypeInFactSheet(1L, level)).thenReturn(0L);
        }
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of());
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

        // impl calls knowledgeGraphService.getNodesByTypeInFactSheet for SOURCE nodes
        when(knowledgeGraphService.getNodesByTypeInFactSheet(1L, NodeLevel.SOURCE)).thenReturn(List.of(source));
        // impl calls knowledgeGraphService.getChildren for documents under this source
        when(knowledgeGraphService.getChildren("src1")).thenReturn(List.of(doc));

        // processIndexedDocument: look up source node, doc node, create them via seam
        when(knowledgeGraphService.getNodeByExternalId("src1", NodeLevel.SOURCE, 1L))
                .thenReturn(Optional.of(source));
        when(knowledgeGraphService.getNodeByExternalId("doc1", NodeLevel.DOCUMENT, 1L))
                .thenReturn(Optional.of(doc));

        // Concept extraction returns empty
        when(conceptExtractor.extractConcepts(any(), any())).thenReturn(
                new ExtractionResult(List.of(), List.of(), Map.of()));

        // rebuildConceptEdges delegates to knowledgeGraphService
        when(knowledgeGraphService.findNodePairsWithSharedEntitiesInFactSheet(eq(1L), anyInt()))
                .thenReturn(List.of());
        // linkAllSources
        when(sourceLinkingService.linkAllSources(eq(1L), any())).thenReturn(
                new LinkingResult(0, 0, 0, 0, List.of(), Map.of()));
        // countActiveNodes after build
        when(knowledgeGraphService.countActiveNodes(1L)).thenReturn(2L);

        // getGraphStatistics
        for (NodeLevel level : NodeLevel.values()) {
            when(knowledgeGraphService.countNodesByTypeInFactSheet(1L, level)).thenReturn(0L);
        }
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of());
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
        when(knowledgeGraphService.getNodesByTypeInFactSheet(1L, NodeLevel.SOURCE)).thenReturn(List.of());
        when(sourceLinkingService.linkAllSources(eq(1L), any())).thenReturn(
                new LinkingResult(0, 0, 0, 0, List.of(), Map.of()));
        when(knowledgeGraphService.countActiveNodes(1L)).thenReturn(0L);
        for (NodeLevel level : NodeLevel.values()) {
            when(knowledgeGraphService.countNodesByTypeInFactSheet(1L, level)).thenReturn(0L);
        }
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of());
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

        GraphEdge edge = stubEdge("e1", src, doc, EdgeType.HIERARCHICAL, 1.0);

        // Vector store is the source of truth: getVisualizationData now reads via knowledgeGraphService.
        when(knowledgeGraphService.getNodesInFactSheetPage(1L, 0, 1_000))
                .thenReturn(new KnowledgeGraphService.GraphPage<>(List.of(src, doc), 2, false));
        when(knowledgeGraphService.getEdgesInFactSheetPage(1L, 0, 1_000))
                .thenReturn(new KnowledgeGraphService.GraphPage<>(List.of(edge), 1, false));

        GraphVisualizationData viz = service.getVisualizationData(1L, 50, 50);

        assertEquals(2, viz.nodes().size());
        assertEquals(1, viz.edges().size());
        assertEquals(1L, viz.metadata().get("factSheetId"));
    }

    @Test
    void getVisualizationData_unlimitedNodes_returnsAll() {
        GraphNode n1 = stubNode("n1", "N1", NodeLevel.SOURCE);
        when(knowledgeGraphService.getNodesInFactSheetPage(1L, 0, 1_000))
                .thenReturn(new KnowledgeGraphService.GraphPage<>(List.of(n1), 1, false));
        when(knowledgeGraphService.getEdgesInFactSheetPage(1L, 0, 1_000))
                .thenReturn(new KnowledgeGraphService.GraphPage<>(List.of(), 0, false));

        GraphVisualizationData viz = service.getVisualizationData(1L, 0, 0);

        assertEquals(1, viz.nodes().size());
    }

    @Test
    void getVisualizationData_filtersEdgesWithMissingEndpoints() {
        GraphNode n1 = stubNode("n1", "N1", NodeLevel.SOURCE);
        GraphNode n2 = stubNode("n2", "N2", NodeLevel.DOCUMENT);

        // impl reads via knowledgeGraphService — with maxNodes=1 only n1 (SOURCE priority) is in the set
        when(knowledgeGraphService.getNodesInFactSheetPage(1L, 0, 1_000))
                .thenReturn(new KnowledgeGraphService.GraphPage<>(List.of(n1, n2), 2, false));

        GraphEdge edge = stubEdge("e1", n1, n2, EdgeType.HIERARCHICAL, 1.0);
        when(knowledgeGraphService.getEdgesInFactSheetPage(1L, 0, 1_000))
                .thenReturn(new KnowledgeGraphService.GraphPage<>(List.of(edge), 1, false));

        // maxNodes=1 → only n1 (SOURCE has highest priority) is retained;
        // edge references n2 which is dropped, so 0 edges after filtering
        GraphVisualizationData viz = service.getVisualizationData(1L, 1, 50);

        // Edge should be filtered because n2 is not in the limited node set
        assertEquals(0, viz.edges().size());
    }

    // ─── getGraphStatistics ───────────────────────────────────────────

    @Test
    void getGraphStatistics_returnsNodeAndEdgeCounts() {
        // Vector store is the SINGLE SOURCE OF TRUTH: all counts come from knowledgeGraphService
        when(knowledgeGraphService.countNodesByTypeInFactSheet(1L, NodeLevel.SOURCE)).thenReturn(3L);
        when(knowledgeGraphService.countNodesByTypeInFactSheet(1L, NodeLevel.DOCUMENT)).thenReturn(10L);
        when(knowledgeGraphService.countNodesByTypeInFactSheet(1L, NodeLevel.ENTITY)).thenReturn(42L);
        for (NodeLevel lvl : NodeLevel.values()) {
            if (lvl != NodeLevel.SOURCE && lvl != NodeLevel.DOCUMENT && lvl != NodeLevel.ENTITY) {
                when(knowledgeGraphService.countNodesByTypeInFactSheet(1L, lvl)).thenReturn(0L);
            }
        }
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("edges_hierarchical", 10L));
        // getNodesByTypeInFactSheet for topConcepts
        when(knowledgeGraphService.getNodesByTypeInFactSheet(1L, NodeLevel.ENTITY)).thenReturn(List.of());
        when(sourceLinkingService.getConnectivitySummary(1L)).thenReturn(Map.of("totalSources", 3));

        Map<String, Object> stats = service.getGraphStatistics(1L);

        @SuppressWarnings("unchecked")
        Map<String, Long> nodesByType = (Map<String, Long>) stats.get("nodesByType");
        assertEquals(3L, nodesByType.get("SOURCE"));
        assertEquals(10L, nodesByType.get("DOCUMENT"));
        assertFalse(nodesByType.containsKey("SNIPPET")); // count was 0

        // distinctConcepts = ENTITY count from knowledgeGraphService
        assertEquals(42L, stats.get("distinctConcepts"));
    }

    // ─── clearGraph ───────────────────────────────────────────────────

    @Test
    void clearGraph_deletesAllComponents() {
        // Vector store is the SINGLE SOURCE OF TRUTH: clearGraph counts nodes via the matrix service
        // and deletes via deleteByFactSheetId. No entityMentionRepository calls.
        when(knowledgeGraphService.countNodesByTypeInFactSheet(1L, NodeLevel.SOURCE)).thenReturn(20L);
        for (NodeLevel level : NodeLevel.values()) {
            if (level != NodeLevel.SOURCE) {
                when(knowledgeGraphService.countNodesByTypeInFactSheet(1L, level)).thenReturn(0L);
            }
        }

        int deleted = service.clearGraph(1L);

        assertEquals(20, deleted); // 20 vector-store nodes counted before deletion
        verify(knowledgeGraphService).deleteByFactSheetId(1L);
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
        // Source node lookup via knowledgeGraphService seam
        GraphNode sourceNode = stubNode("src1", "Source", NodeLevel.SOURCE);
        when(knowledgeGraphService.getNodeByExternalId("src1", NodeLevel.SOURCE, 1L))
                .thenReturn(Optional.of(sourceNode));

        // Document node lookup via knowledgeGraphService seam
        GraphNode docNode = stubNode("doc1-saved", "Test Doc", NodeLevel.DOCUMENT);
        when(knowledgeGraphService.getNodeByExternalId("doc1", NodeLevel.DOCUMENT, 1L))
                .thenReturn(Optional.of(docNode));

        // Concept extraction
        ExtractedConcept concept = new ExtractedConcept("Kubernetes", "kubernetes",
                "KEYWORD", 0.9, 3, "...context...");
        ExtractionResult extractionResult = new ExtractionResult(
                List.of(concept), List.of(), Map.of());
        when(conceptExtractor.extractConcepts(any(), any())).thenReturn(extractionResult);

        // Entity node lookup via knowledgeGraphService seam
        GraphNode entityNode = stubNode("entity-k8s", "Kubernetes", NodeLevel.ENTITY);
        when(knowledgeGraphService.getNodeByExternalId("kubernetes", NodeLevel.ENTITY, 1L))
                .thenReturn(Optional.of(entityNode));

        // Edge checking via knowledgeGraphService seam
        when(knowledgeGraphService.edgeExists("doc1-saved", "entity-k8s")).thenReturn(false);

        int count = service.processIndexedDocument(1L, "doc1", "Kubernetes orchestrates containers",
                Map.of("title", "Test Doc"), "src1", syncBuildConfig());

        // minConceptConfidence is 50, concept confidence is 0.9 (90%), should be counted
        assertEquals(1, count);
        verify(knowledgeGraphService).createEdge(eq("doc1-saved"), eq("entity-k8s"),
                eq(EdgeType.SHARED_ENTITY), anyDouble(), anyString());
    }

    @Test
    void processIndexedDocument_noSource_skipsHierarchicalEdge() {
        // No source (sourceId=null) — doc created via knowledgeGraphService
        GraphNode docNode = stubNode("doc1-saved", "Doc", NodeLevel.DOCUMENT);
        when(knowledgeGraphService.getNodeByExternalId("doc1", NodeLevel.DOCUMENT, 1L))
                .thenReturn(Optional.empty());
        when(knowledgeGraphService.createDocumentNode(isNull(), eq("doc1"), anyString(), any()))
                .thenReturn(docNode);

        when(conceptExtractor.extractConcepts(any(), any())).thenReturn(
                new ExtractionResult(List.of(), List.of(), Map.of()));

        service.processIndexedDocument(1L, "doc1", "content", Map.of(), null, syncBuildConfig());

        // No hierarchical edge since source is null — impl checks config.includeHierarchicalEdges()
        // AND sourceNode != null, so with null source no hierarchical edge is created
        verify(knowledgeGraphService, never()).createEdge(any(), any(), eq(EdgeType.HIERARCHICAL),
                anyDouble(), anyString());
    }

    // ─── rebuildConceptEdges ──────────────────────────────────────────

    @Test
    void rebuildConceptEdges_noPairs_returnsZero() {
        // impl delegates to knowledgeGraphService.findNodePairsWithSharedEntitiesInFactSheet
        when(knowledgeGraphService.findNodePairsWithSharedEntitiesInFactSheet(1L, 2))
                .thenReturn(List.of());

        assertEquals(0, service.rebuildConceptEdges(1L, 2));
    }

    @Test
    void rebuildConceptEdges_withPairs_createsEdges() {
        // impl expects String nodeId pairs from the matrix store
        Object[] pair = new Object[]{"n1", "n2", 5L};
        List<Object[]> pairs = new ArrayList<>();
        pairs.add(pair);
        when(knowledgeGraphService.findNodePairsWithSharedEntitiesInFactSheet(1L, 2))
                .thenReturn(pairs);
        when(knowledgeGraphService.edgeExists("n1", "n2")).thenReturn(false);

        int created = service.rebuildConceptEdges(1L, 2);

        assertEquals(1, created);
        verify(knowledgeGraphService).createEdge(eq("n1"), eq("n2"),
                eq(EdgeType.SHARED_ENTITY), anyDouble(), anyString());
    }

    @Test
    void rebuildConceptEdges_existingEdge_skips() {
        Object[] pair = new Object[]{"n1", "n2", 5L};
        List<Object[]> pairs = new ArrayList<>();
        pairs.add(pair);
        when(knowledgeGraphService.findNodePairsWithSharedEntitiesInFactSheet(1L, 2))
                .thenReturn(pairs);
        when(knowledgeGraphService.edgeExists("n1", "n2")).thenReturn(true);

        assertEquals(0, service.rebuildConceptEdges(1L, 2));
    }

    // ─── getTopConcepts ───────────────────────────────────────────────

    @Test
    void getTopConcepts_returnsFormattedList() {
        // impl reads ENTITY nodes from knowledgeGraphService.getNodesByTypeInFactSheet
        GraphNode entityNode = stubNode("e1", "kubernetes", NodeLevel.ENTITY);
        when(knowledgeGraphService.getNodesByTypeInFactSheet(1L, NodeLevel.ENTITY))
                .thenReturn(List.of(entityNode));

        List<Map<String, Object>> result = service.getTopConcepts(1L, 10);

        assertEquals(1, result.size());
        assertEquals("kubernetes", result.get(0).get("name"));
        assertEquals(1L, result.get(0).get("totalMentions"));
    }

    @Test
    void getTopConcepts_empty_returnsEmptyList() {
        when(knowledgeGraphService.getNodesByTypeInFactSheet(1L, NodeLevel.ENTITY)).thenReturn(List.of());

        List<Map<String, Object>> result = service.getTopConcepts(1L, 10);

        assertTrue(result.isEmpty());
    }

    // ─── searchNodes ──────────────────────────────────────────────────

    @Test
    void searchNodes_returnsD3FormatResults() {
        GraphNode n1 = stubNode("n1", "Kubernetes", NodeLevel.ENTITY);
        // Vector store is the source of truth: searchNodes delegates to the matrix service.
        when(knowledgeGraphService.searchNodesInFactSheet(1L, "kube", 10)).thenReturn(List.of(n1));

        List<Map<String, Object>> results = service.searchNodes(1L, "kube", 10);

        assertEquals(1, results.size());
        assertEquals("n1", results.get(0).get("id"));
        assertEquals("Kubernetes", results.get(0).get("label"));
    }

    // ─── getRelatedDocuments ──────────────────────────────────────────

    @Test
    void getRelatedDocuments_nodeNotFound_returnsEmpty() {
        when(knowledgeGraphService.getNode("missing")).thenReturn(Optional.empty());

        List<Map<String, Object>> result = service.getRelatedDocuments(1L, "missing", 1, 10);

        assertTrue(result.isEmpty());
    }

    @Test
    void getRelatedDocuments_noConcepts_returnsEmpty() {
        GraphNode doc = stubNode("d1", "Doc", NodeLevel.DOCUMENT);
        when(knowledgeGraphService.getNode("d1")).thenReturn(Optional.of(doc));
        // impl calls knowledgeGraphService.getEntityNamesForNode(documentNodeId)
        when(knowledgeGraphService.getEntityNamesForNode("d1")).thenReturn(List.of());

        List<Map<String, Object>> result = service.getRelatedDocuments(1L, "d1", 1, 10);

        assertTrue(result.isEmpty());
    }

    @Test
    void getRelatedDocuments_withSharedConcepts_returnsRelated() {
        GraphNode doc1 = stubNode("d1", "Doc1", NodeLevel.DOCUMENT);
        GraphNode doc2 = stubNode("d2", "Doc2", NodeLevel.DOCUMENT);
        when(knowledgeGraphService.getNode("d1")).thenReturn(Optional.of(doc1));
        when(knowledgeGraphService.getNode("d2")).thenReturn(Optional.of(doc2));

        // impl calls knowledgeGraphService.getEntityNamesForNode(documentNodeId)
        when(knowledgeGraphService.getEntityNamesForNode("d1")).thenReturn(List.of("kubernetes", "docker"));

        // impl calls knowledgeGraphService.getNodesWithEntity(concept) for each concept
        when(knowledgeGraphService.getNodesWithEntity("kubernetes")).thenReturn(List.of(doc2));
        when(knowledgeGraphService.getNodesWithEntity("docker")).thenReturn(List.of());

        List<Map<String, Object>> result = service.getRelatedDocuments(1L, "d1", 1, 10);

        assertEquals(1, result.size());
        assertEquals("d2", result.get(0).get("nodeId"));
    }
}
