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
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.knowledgegraph.service.SourceLinkingService.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link SourceLinkingServiceImpl} — shared concept linking, embedding similarity,
 * combined linking, manual links, removal, connectivity analysis, term-based linking,
 * and isolated/most-connected source discovery.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class SourceLinkingServiceImplTest {

    @Mock private GraphNodeRepository nodeRepository;
    @Mock private GraphEdgeRepository edgeRepository;
    @Mock private EntityMentionRepository entityMentionRepository;
    @Mock private KnowledgeGraphService knowledgeGraphService;

    private SourceLinkingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SourceLinkingServiceImpl(nodeRepository, edgeRepository,
                entityMentionRepository, knowledgeGraphService);
    }

    private GraphNode stubSource(String nodeId, String title) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .title(title)
                .nodeType(NodeLevel.SOURCE)
                .build();
    }

    private GraphNode stubDocNode(String nodeId) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .nodeType(NodeLevel.DOCUMENT)
                .build();
    }

    private GraphEdge stubEdge(GraphNode source, GraphNode target, EdgeType type, double weight) {
        return GraphEdge.builder()
                .edgeId(UUID.randomUUID().toString())
                .sourceNode(source)
                .targetNode(target)
                .edgeType(type)
                .weight(weight)
                .description("test edge")
                .build();
    }

    private EntityMention stubMention(GraphNode node, String entityName, Long factSheetId) {
        return EntityMention.builder()
                .node(node)
                .entityName(entityName)
                .factSheetId(factSheetId)
                .build();
    }

    private LinkingConfig defaultConfig() {
        return LinkingConfig.defaults();
    }

    // ─── linkSourcesBySharedConcepts ──────────────────────────────────

    @Test
    void linkSourcesBySharedConcepts_lessThan2Sources_returnsEarlyWithMessage() {
        when(nodeRepository.findSourcesByFactSheet(1L)).thenReturn(List.of(stubSource("s1", "Source1")));

        LinkingResult result = service.linkSourcesBySharedConcepts(1L, defaultConfig());

        assertEquals(1, result.sourcesAnalyzed());
        assertEquals(0, result.linksCreated());
        assertTrue(result.statistics().containsKey("message"));
    }

    @Test
    void linkSourcesBySharedConcepts_noSharedConcepts_noLinksCreated() {
        GraphNode s1 = stubSource("s1", "Source1");
        GraphNode s2 = stubSource("s2", "Source2");
        when(nodeRepository.findSourcesByFactSheet(1L)).thenReturn(List.of(s1, s2));

        // No documents under either source
        when(nodeRepository.findBySourceIdAndType("s1", NodeLevel.DOCUMENT)).thenReturn(List.of());
        when(nodeRepository.findBySourceIdAndType("s2", NodeLevel.DOCUMENT)).thenReturn(List.of());

        LinkingResult result = service.linkSourcesBySharedConcepts(1L, defaultConfig());

        assertEquals(2, result.sourcesAnalyzed());
        assertEquals(0, result.linksCreated());
    }

    @Test
    void linkSourcesBySharedConcepts_sufficientOverlap_createsLink() {
        GraphNode s1 = stubSource("s1", "Source1");
        GraphNode s2 = stubSource("s2", "Source2");
        when(nodeRepository.findSourcesByFactSheet(1L)).thenReturn(List.of(s1, s2));

        // Source1 has documents with concepts A, B, C, D
        GraphNode doc1 = stubDocNode("d1");
        when(nodeRepository.findBySourceIdAndType("s1", NodeLevel.DOCUMENT)).thenReturn(List.of(doc1));
        EntityMention m1 = stubMention(doc1, "conceptA", 1L);
        EntityMention m2 = stubMention(doc1, "conceptB", 1L);
        EntityMention m3 = stubMention(doc1, "conceptC", 1L);
        EntityMention m4 = stubMention(doc1, "conceptD", 1L);
        when(entityMentionRepository.findByNode(doc1)).thenReturn(List.of(m1, m2, m3, m4));

        // Source2 has documents with concepts A, B, C, E
        GraphNode doc2 = stubDocNode("d2");
        when(nodeRepository.findBySourceIdAndType("s2", NodeLevel.DOCUMENT)).thenReturn(List.of(doc2));
        EntityMention m5 = stubMention(doc2, "conceptA", 1L);
        EntityMention m6 = stubMention(doc2, "conceptB", 1L);
        EntityMention m7 = stubMention(doc2, "conceptC", 1L);
        EntityMention m8 = stubMention(doc2, "conceptE", 1L);
        when(entityMentionRepository.findByNode(doc2)).thenReturn(List.of(m5, m6, m7, m8));

        // No existing edge
        when(edgeRepository.findEdgeBetweenNodesInFactSheet("s1", "s2", 1L)).thenReturn(Optional.empty());
        when(edgeRepository.findEdgeBetweenNodesInFactSheet("s2", "s1", 1L)).thenReturn(Optional.empty());

        // Create edge returns mock
        GraphEdge createdEdge = mock(GraphEdge.class);
        when(knowledgeGraphService.createEdge(eq("s1"), eq("s2"), any(), anyDouble(), anyString()))
                .thenReturn(createdEdge);

        LinkingResult result = service.linkSourcesBySharedConcepts(1L, defaultConfig());

        assertEquals(2, result.sourcesAnalyzed());
        assertEquals(1, result.linksCreated());
        assertEquals(1, result.links().size());
        verify(edgeRepository).save(createdEdge);
    }

    @Test
    void linkSourcesBySharedConcepts_existingEdge_skipsCreation() {
        GraphNode s1 = stubSource("s1", "Source1");
        GraphNode s2 = stubSource("s2", "Source2");
        when(nodeRepository.findSourcesByFactSheet(1L)).thenReturn(List.of(s1, s2));

        GraphNode doc1 = stubDocNode("d1");
        when(nodeRepository.findBySourceIdAndType("s1", NodeLevel.DOCUMENT)).thenReturn(List.of(doc1));
        when(entityMentionRepository.findByNode(doc1)).thenReturn(List.of(
                stubMention(doc1, "a", 1L), stubMention(doc1, "b", 1L), stubMention(doc1, "c", 1L)));

        GraphNode doc2 = stubDocNode("d2");
        when(nodeRepository.findBySourceIdAndType("s2", NodeLevel.DOCUMENT)).thenReturn(List.of(doc2));
        when(entityMentionRepository.findByNode(doc2)).thenReturn(List.of(
                stubMention(doc2, "a", 1L), stubMention(doc2, "b", 1L), stubMention(doc2, "c", 1L)));

        // Edge already exists
        when(edgeRepository.findEdgeBetweenNodesInFactSheet("s1", "s2", 1L))
                .thenReturn(Optional.of(mock(GraphEdge.class)));

        LinkingResult result = service.linkSourcesBySharedConcepts(1L, defaultConfig());

        assertEquals(0, result.linksCreated());
        verify(knowledgeGraphService, never()).createEdge(any(), any(), any(), anyDouble(), any());
    }

    @Test
    void linkSourcesBySharedConcepts_crossSourceEdgeType_whenConfigured() {
        GraphNode s1 = stubSource("s1", "Source1");
        GraphNode s2 = stubSource("s2", "Source2");
        when(nodeRepository.findSourcesByFactSheet(1L)).thenReturn(List.of(s1, s2));

        GraphNode doc1 = stubDocNode("d1");
        when(nodeRepository.findBySourceIdAndType("s1", NodeLevel.DOCUMENT)).thenReturn(List.of(doc1));
        when(entityMentionRepository.findByNode(doc1)).thenReturn(List.of(
                stubMention(doc1, "x", 1L), stubMention(doc1, "y", 1L), stubMention(doc1, "z", 1L)));

        GraphNode doc2 = stubDocNode("d2");
        when(nodeRepository.findBySourceIdAndType("s2", NodeLevel.DOCUMENT)).thenReturn(List.of(doc2));
        when(entityMentionRepository.findByNode(doc2)).thenReturn(List.of(
                stubMention(doc2, "x", 1L), stubMention(doc2, "y", 1L), stubMention(doc2, "z", 1L)));

        when(edgeRepository.findEdgeBetweenNodesInFactSheet(any(), any(), eq(1L))).thenReturn(Optional.empty());
        GraphEdge edge = mock(GraphEdge.class);
        when(knowledgeGraphService.createEdge(any(), any(), eq(EdgeType.CROSS_SOURCE), anyDouble(), any()))
                .thenReturn(edge);

        // createCrossSourceEdges = true in defaults
        service.linkSourcesBySharedConcepts(1L, defaultConfig());

        verify(knowledgeGraphService).createEdge(any(), any(), eq(EdgeType.CROSS_SOURCE), anyDouble(), any());
    }

    // ─── linkSourcesByEmbeddingSimilarity ─────────────────────────────

    @Test
    void linkSourcesByEmbeddingSimilarity_returnsStubResult() {
        LinkingResult result = service.linkSourcesByEmbeddingSimilarity(1L, defaultConfig());

        assertEquals(0, result.sourcesAnalyzed());
        assertEquals(0, result.linksCreated());
        assertTrue(result.statistics().containsKey("message"));
    }

    // ─── linkAllSources ───────────────────────────────────────────────

    @Test
    void linkAllSources_combinesConceptAndSimilarityResults() {
        when(nodeRepository.findSourcesByFactSheet(1L)).thenReturn(List.of(stubSource("s1", "S1")));

        LinkingResult result = service.linkAllSources(1L, defaultConfig());

        // linkSourcesBySharedConcepts returns early (<2 sources)
        // linkSourcesByEmbeddingSimilarity returns stub (useEmbeddingSimilarity=true in defaults)
        assertEquals(1, result.sourcesAnalyzed());
        assertEquals(0, result.linksCreated());
    }

    @Test
    void linkAllSources_skipsEmbedding_whenDisabled() {
        LinkingConfig noEmbedding = new LinkingConfig(3, 0.7, 0.2, true, false, true, true);
        when(nodeRepository.findSourcesByFactSheet(1L)).thenReturn(List.of(stubSource("s1", "S1")));

        LinkingResult result = service.linkAllSources(1L, noEmbedding);

        assertEquals(1, result.sourcesAnalyzed());
        assertFalse(result.statistics().containsKey("similarityStats"));
    }

    // ─── getSourceLinks ───────────────────────────────────────────────

    @Test
    void getSourceLinks_returnsConvertedLinks() {
        GraphNode s1 = stubSource("s1", "Source1");
        GraphNode s2 = stubSource("s2", "Source2");
        GraphEdge edge = stubEdge(s1, s2, EdgeType.CROSS_SOURCE, 0.8);

        doReturn(List.of(edge)).when(edgeRepository).findCrossSourceEdgesByFactSheet(eq(1L), any(PageRequest.class));

        List<SourceLink> links = service.getSourceLinks(1L);

        assertEquals(1, links.size());
        assertEquals("s1", links.get(0).sourceId1());
        assertEquals("s2", links.get(0).sourceId2());
        assertEquals("CROSS_SOURCE", links.get(0).linkType());
    }

    // ─── getLinksForSource ────────────────────────────────────────────

    @Test
    void getLinksForSource_filtersByEdgeTypeAndNodeType() {
        GraphNode s1 = stubSource("s1", "Source1");
        GraphNode s2 = stubSource("s2", "Source2");
        GraphNode docNode = stubDocNode("d1");

        GraphEdge sourceEdge = stubEdge(s1, s2, EdgeType.CROSS_SOURCE, 0.8);
        GraphEdge nonSourceEdge = stubEdge(s1, docNode, EdgeType.HIERARCHICAL, 1.0);

        when(edgeRepository.findAllEdgesForNodeIdInFactSheet("s1", 1L))
                .thenReturn(List.of(sourceEdge, nonSourceEdge));

        List<SourceLink> links = service.getLinksForSource(1L, "s1");

        assertEquals(1, links.size());
        assertEquals("CROSS_SOURCE", links.get(0).linkType());
    }

    @Test
    void getLinksForSource_sharedEntityType_included() {
        GraphNode s1 = stubSource("s1", "Source1");
        GraphNode s2 = stubSource("s2", "Source2");

        GraphEdge edge = stubEdge(s1, s2, EdgeType.SHARED_ENTITY, 0.6);
        when(edgeRepository.findAllEdgesForNodeIdInFactSheet("s1", 1L)).thenReturn(List.of(edge));

        List<SourceLink> links = service.getLinksForSource(1L, "s1");

        assertEquals(1, links.size());
        assertEquals("SHARED_ENTITY", links.get(0).linkType());
    }

    // ─── createManualLink ─────────────────────────────────────────────

    @Test
    void createManualLink_validSources_createsEdge() {
        GraphNode s1 = stubSource("s1", "Source1");
        GraphNode s2 = stubSource("s2", "Source2");
        when(nodeRepository.findByNodeId("s1")).thenReturn(Optional.of(s1));
        when(nodeRepository.findByNodeId("s2")).thenReturn(Optional.of(s2));

        GraphEdge edge = mock(GraphEdge.class);
        when(knowledgeGraphService.createEdge("s1", "s2", EdgeType.USER_DEFINED, 0.9, "Manual link"))
                .thenReturn(edge);

        SourceLink link = service.createManualLink(1L, "s1", "s2", null, 0.9);

        assertEquals("s1", link.sourceId1());
        assertEquals("s2", link.sourceId2());
        assertEquals("USER_DEFINED", link.linkType());
        assertEquals(0.9, link.strength());
        verify(edge).setFactSheetId(1L);
        verify(edge).setBidirectional(true);
        verify(edgeRepository).save(edge);
    }

    @Test
    void createManualLink_nodeNotFound_throws() {
        when(nodeRepository.findByNodeId("missing")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.createManualLink(1L, "missing", "s2", "desc", 0.5));
    }

    @Test
    void createManualLink_nonSourceNode_throws() {
        GraphNode docNode = stubDocNode("d1");
        GraphNode s2 = stubSource("s2", "Source2");
        when(nodeRepository.findByNodeId("d1")).thenReturn(Optional.of(docNode));
        when(nodeRepository.findByNodeId("s2")).thenReturn(Optional.of(s2));

        assertThrows(IllegalArgumentException.class,
                () -> service.createManualLink(1L, "d1", "s2", "desc", 0.5));
    }

    @Test
    void createManualLink_withDescription_usesIt() {
        GraphNode s1 = stubSource("s1", "Source1");
        GraphNode s2 = stubSource("s2", "Source2");
        when(nodeRepository.findByNodeId("s1")).thenReturn(Optional.of(s1));
        when(nodeRepository.findByNodeId("s2")).thenReturn(Optional.of(s2));

        GraphEdge edge = mock(GraphEdge.class);
        when(knowledgeGraphService.createEdge("s1", "s2", EdgeType.USER_DEFINED, 0.7, "Custom desc"))
                .thenReturn(edge);

        SourceLink link = service.createManualLink(1L, "s1", "s2", "Custom desc", 0.7);

        assertEquals("Custom desc", link.description());
    }

    // ─── removeLink ───────────────────────────────────────────────────

    @Test
    void removeLink_forwardDirection_removesAndReturnsTrue() {
        GraphEdge edge = mock(GraphEdge.class);
        when(edgeRepository.findEdgeBetweenNodesInFactSheet("s1", "s2", 1L))
                .thenReturn(Optional.of(edge));

        assertTrue(service.removeLink(1L, "s1", "s2"));
        verify(edgeRepository).delete(edge);
    }

    @Test
    void removeLink_reverseDirection_removesAndReturnsTrue() {
        when(edgeRepository.findEdgeBetweenNodesInFactSheet("s1", "s2", 1L)).thenReturn(Optional.empty());
        GraphEdge edge = mock(GraphEdge.class);
        when(edgeRepository.findEdgeBetweenNodesInFactSheet("s2", "s1", 1L)).thenReturn(Optional.of(edge));

        assertTrue(service.removeLink(1L, "s1", "s2"));
        verify(edgeRepository).delete(edge);
    }

    @Test
    void removeLink_noEdge_returnsFalse() {
        when(edgeRepository.findEdgeBetweenNodesInFactSheet(any(), any(), eq(1L)))
                .thenReturn(Optional.empty());

        assertFalse(service.removeLink(1L, "s1", "s2"));
        verify(edgeRepository, never()).delete(any(GraphEdge.class));
    }

    // ─── getConnectivitySummary ───────────────────────────────────────

    @Test
    void getConnectivitySummary_noSources_returnsZeros() {
        when(nodeRepository.findSourcesByFactSheet(1L)).thenReturn(List.of());
        when(edgeRepository.findByFactSheetId(1L)).thenReturn(List.of());

        Map<String, Object> summary = service.getConnectivitySummary(1L);

        assertEquals(0, summary.get("totalSources"));
        assertEquals(0L, summary.get("totalSourceLinks"));
        assertEquals(0, summary.get("isolatedSources"));
    }

    @Test
    void getConnectivitySummary_withEdges_calculatesCorrectly() {
        GraphNode s1 = stubSource("s1", "Source1");
        GraphNode s2 = stubSource("s2", "Source2");
        GraphNode s3 = stubSource("s3", "Source3");
        when(nodeRepository.findSourcesByFactSheet(1L)).thenReturn(List.of(s1, s2, s3));

        GraphEdge edge = stubEdge(s1, s2, EdgeType.CROSS_SOURCE, 0.8);
        when(edgeRepository.findByFactSheetId(1L)).thenReturn(List.of(edge));

        Map<String, Object> summary = service.getConnectivitySummary(1L);

        assertEquals(3, summary.get("totalSources"));
        assertEquals(1L, summary.get("totalSourceLinks"));
        assertEquals(1, summary.get("isolatedSources")); // s3 is isolated
    }

    // ─── findIsolatedSources ──────────────────────────────────────────

    @Test
    void findIsolatedSources_noEdges_allIsolated() {
        GraphNode s1 = stubSource("s1", "S1");
        GraphNode s2 = stubSource("s2", "S2");
        when(nodeRepository.findSourcesByFactSheet(1L)).thenReturn(List.of(s1, s2));
        when(edgeRepository.findByFactSheetId(1L)).thenReturn(List.of());

        List<String> isolated = service.findIsolatedSources(1L);

        assertEquals(2, isolated.size());
        assertTrue(isolated.contains("s1"));
        assertTrue(isolated.contains("s2"));
    }

    @Test
    void findIsolatedSources_connectedSources_excluded() {
        GraphNode s1 = stubSource("s1", "S1");
        GraphNode s2 = stubSource("s2", "S2");
        GraphNode s3 = stubSource("s3", "S3");
        when(nodeRepository.findSourcesByFactSheet(1L)).thenReturn(List.of(s1, s2, s3));

        GraphEdge edge = stubEdge(s1, s2, EdgeType.CROSS_SOURCE, 0.8);
        when(edgeRepository.findByFactSheetId(1L)).thenReturn(List.of(edge));

        List<String> isolated = service.findIsolatedSources(1L);

        assertEquals(1, isolated.size());
        assertEquals("s3", isolated.get(0));
    }

    // ─── findMostConnectedSources ─────────────────────────────────────

    @Test
    void findMostConnectedSources_sortedByConnectionCount() {
        GraphNode s1 = stubSource("s1", "S1");
        GraphNode s2 = stubSource("s2", "S2");
        GraphNode s3 = stubSource("s3", "S3");
        when(nodeRepository.findSourcesByFactSheet(1L)).thenReturn(List.of(s1, s2, s3));

        // s2 has 2 connections (as source and target)
        GraphEdge e1 = stubEdge(s1, s2, EdgeType.CROSS_SOURCE, 0.8);
        GraphEdge e2 = stubEdge(s2, s3, EdgeType.CROSS_SOURCE, 0.7);
        when(edgeRepository.findByFactSheetId(1L)).thenReturn(List.of(e1, e2));

        List<Map<String, Object>> result = service.findMostConnectedSources(1L, 2);

        assertFalse(result.isEmpty());
        // s2 should be first (connected to both s1 and s3)
        assertEquals("s2", result.get(0).get("sourceId"));
        assertEquals(2, result.get(0).get("connectionCount"));
    }

    @Test
    void findMostConnectedSources_respectsLimit() {
        GraphNode s1 = stubSource("s1", "S1");
        GraphNode s2 = stubSource("s2", "S2");
        GraphNode s3 = stubSource("s3", "S3");
        when(nodeRepository.findSourcesByFactSheet(1L)).thenReturn(List.of(s1, s2, s3));
        when(edgeRepository.findByFactSheetId(1L)).thenReturn(List.of());

        List<Map<String, Object>> result = service.findMostConnectedSources(1L, 1);

        assertEquals(1, result.size());
    }

    // ─── linkNodesByTerm ──────────────────────────────────────────────

    @Test
    void linkNodesByTerm_nullTerm_returnsMessage() {
        TermLinkingResult result = service.linkNodesByTerm(null, 1L, null, null);

        assertEquals(0, result.nodesFound());
        assertEquals(0, result.linksCreated());
        assertEquals("Term cannot be empty", result.message());
    }

    @Test
    void linkNodesByTerm_blankTerm_returnsMessage() {
        TermLinkingResult result = service.linkNodesByTerm("   ", 1L, null, null);

        assertEquals(0, result.nodesFound());
        assertEquals("Term cannot be empty", result.message());
    }

    @Test
    void linkNodesByTerm_lessThan2Nodes_returnsInsufficientMessage() {
        GraphNode n1 = stubDocNode("n1");
        EntityMention m1 = stubMention(n1, "kubernetes", 1L);
        when(entityMentionRepository.findByEntityNameAndFactSheet("kubernetes", 1L))
                .thenReturn(List.of(m1));

        TermLinkingResult result = service.linkNodesByTerm("Kubernetes", 1L, null, null);

        assertEquals(1, result.nodesFound());
        assertEquals(0, result.linksCreated());
        assertTrue(result.message().contains("Need at least 2 nodes"));
    }

    @Test
    void linkNodesByTerm_multipleNodes_createsPairwiseEdges() {
        GraphNode n1 = stubDocNode("n1");
        GraphNode n2 = stubDocNode("n2");
        GraphNode n3 = stubDocNode("n3");

        EntityMention m1 = stubMention(n1, "kubernetes", 1L);
        EntityMention m2 = stubMention(n2, "kubernetes", 1L);
        EntityMention m3 = stubMention(n3, "kubernetes", 1L);
        when(entityMentionRepository.findByEntityNameAndFactSheet("kubernetes", 1L))
                .thenReturn(List.of(m1, m2, m3));

        when(edgeRepository.findEdgeBetweenNodesBidirectional(any(), any())).thenReturn(Optional.empty());

        GraphEdge edge = mock(GraphEdge.class);
        when(knowledgeGraphService.createEdge(any(), any(), any(), anyDouble(), any())).thenReturn(edge);

        TermLinkingResult result = service.linkNodesByTerm("Kubernetes", 1L, null, null);

        assertEquals(3, result.nodesFound());
        assertEquals(3, result.linksCreated()); // C(3,2) = 3 pairs
    }

    @Test
    void linkNodesByTerm_defaultsEdgeTypeAndWeight() {
        GraphNode n1 = stubDocNode("n1");
        GraphNode n2 = stubDocNode("n2");
        when(entityMentionRepository.findByEntityNameAndFactSheet("test", 1L))
                .thenReturn(List.of(stubMention(n1, "test", 1L), stubMention(n2, "test", 1L)));

        when(edgeRepository.findEdgeBetweenNodesBidirectional(any(), any())).thenReturn(Optional.empty());
        GraphEdge edge = mock(GraphEdge.class);
        when(knowledgeGraphService.createEdge(any(), any(), eq(EdgeType.SHARED_ENTITY), eq(0.7), any()))
                .thenReturn(edge);

        service.linkNodesByTerm("Test!", 1L, null, null);

        verify(knowledgeGraphService).createEdge(any(), any(), eq(EdgeType.SHARED_ENTITY), eq(0.7), any());
    }

    @Test
    void linkNodesByTerm_existingEdge_skips() {
        GraphNode n1 = stubDocNode("n1");
        GraphNode n2 = stubDocNode("n2");
        when(entityMentionRepository.findByEntityNameAndFactSheet("test", 1L))
                .thenReturn(List.of(stubMention(n1, "test", 1L), stubMention(n2, "test", 1L)));

        when(edgeRepository.findEdgeBetweenNodesBidirectional("n1", "n2"))
                .thenReturn(Optional.of(mock(GraphEdge.class)));

        TermLinkingResult result = service.linkNodesByTerm("Test", 1L, null, null);

        assertEquals(2, result.nodesFound());
        assertEquals(0, result.linksCreated());
    }

    // ─── linkNodesByTerms ─────────────────────────────────────────────

    @Test
    void linkNodesByTerms_nullList_returnsEmpty() {
        List<TermLinkingResult> results = service.linkNodesByTerms(null, 1L, null, null);
        assertTrue(results.isEmpty());
    }

    @Test
    void linkNodesByTerms_emptyList_returnsEmpty() {
        List<TermLinkingResult> results = service.linkNodesByTerms(List.of(), 1L, null, null);
        assertTrue(results.isEmpty());
    }

    @Test
    void linkNodesByTerms_processeEachTerm() {
        // Each term finds < 2 nodes, so no links created but result per term
        when(entityMentionRepository.findByEntityNameAndFactSheet(any(), eq(1L))).thenReturn(List.of());

        List<TermLinkingResult> results = service.linkNodesByTerms(
                List.of("alpha", "beta"), 1L, null, null);

        assertEquals(2, results.size());
    }

    // ─── createTermBasedRelation ──────────────────────────────────────

    @Test
    void createTermBasedRelation_createsEdgeAndMentions() {
        GraphNode s = stubSource("s1", "Source1");
        GraphNode t = stubSource("t1", "Target1");
        when(nodeRepository.findByNodeId("s1")).thenReturn(Optional.of(s));
        when(nodeRepository.findByNodeId("t1")).thenReturn(Optional.of(t));

        GraphEdge edge = mock(GraphEdge.class);
        when(knowledgeGraphService.createEdge("s1", "t1", EdgeType.USER_DEFINED, 0.8, "Custom desc"))
                .thenReturn(edge);

        when(entityMentionRepository.findByNodeAndEntityName(any(), any())).thenReturn(Optional.empty());

        SourceLink link = service.createTermBasedRelation("s1", "t1", "Test-Term", "Custom desc", 0.8, true);

        assertEquals("USER_DEFINED", link.linkType());
        assertEquals(0.8, link.strength());
        verify(edge).setBidirectional(true);
        verify(edge).setLabel("Test-Term");
        // Entity mentions created for both nodes
        verify(entityMentionRepository, times(2)).save(any(EntityMention.class));
    }

    @Test
    void createTermBasedRelation_nodeNotFound_throws() {
        when(nodeRepository.findByNodeId("missing")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.createTermBasedRelation("missing", "t1", "term", null, 0.7, true));
    }

    @Test
    void createTermBasedRelation_nullDescription_usesDefault() {
        GraphNode s = stubSource("s1", "S");
        GraphNode t = stubSource("t1", "T");
        when(nodeRepository.findByNodeId("s1")).thenReturn(Optional.of(s));
        when(nodeRepository.findByNodeId("t1")).thenReturn(Optional.of(t));

        GraphEdge edge = mock(GraphEdge.class);
        when(knowledgeGraphService.createEdge(eq("s1"), eq("t1"), eq(EdgeType.USER_DEFINED), eq(0.7),
                contains("Related by"))).thenReturn(edge);
        when(entityMentionRepository.findByNodeAndEntityName(any(), any())).thenReturn(Optional.empty());

        SourceLink link = service.createTermBasedRelation("s1", "t1", "concept", null, 0.7, false);

        assertTrue(link.description().contains("Related by"));
    }

    // ─── findNodesWithTerm ────────────────────────────────────────────

    @Test
    void findNodesWithTerm_nullTerm_returnsEmpty() {
        List<String> result = service.findNodesWithTerm(null, 1L, 10);
        assertTrue(result.isEmpty());
    }

    @Test
    void findNodesWithTerm_blankTerm_returnsEmpty() {
        List<String> result = service.findNodesWithTerm("  ", 1L, 10);
        assertTrue(result.isEmpty());
    }

    @Test
    void findNodesWithTerm_withFactSheetId_filtersCorrectly() {
        GraphNode n1 = stubDocNode("n1");
        EntityMention m = stubMention(n1, "kubernetes", 1L);
        when(entityMentionRepository.findByEntityNameAndFactSheet("kubernetes", 1L))
                .thenReturn(List.of(m));

        List<String> result = service.findNodesWithTerm("Kubernetes", 1L, 10);

        assertEquals(1, result.size());
        assertEquals("n1", result.get(0));
    }

    @Test
    void findNodesWithTerm_nullFactSheetId_searchesGlobally() {
        GraphNode n1 = stubDocNode("n1");
        when(entityMentionRepository.findNodesWithEntity("test")).thenReturn(List.of(n1));

        List<String> result = service.findNodesWithTerm("test", null, 10);

        assertEquals(1, result.size());
    }

    // ─── getAllTerms ──────────────────────────────────────────────────

    @Test
    void getAllTerms_withFactSheetId_returnsTerms() {
        Object[] row = new Object[]{"kubernetes", 5L};
        List<Object[]> rows = new ArrayList<>();
        rows.add(row);
        when(entityMentionRepository.findTopEntitiesByFactSheet(eq(1L), any(PageRequest.class)))
                .thenReturn(rows);

        List<Map<String, Object>> result = service.getAllTerms(1L, 10);

        assertEquals(1, result.size());
        assertEquals("kubernetes", result.get(0).get("term"));
        assertEquals(5L, result.get(0).get("count"));
    }

    @Test
    void getAllTerms_nullFactSheetId_returnsGlobalTerms() {
        Object[] row = new Object[]{"docker", 3L};
        List<Object[]> rows = new ArrayList<>();
        rows.add(row);
        when(entityMentionRepository.findTopEntities(any(PageRequest.class)))
                .thenReturn(rows);

        List<Map<String, Object>> result = service.getAllTerms(null, 10);

        assertEquals(1, result.size());
        assertEquals("docker", result.get(0).get("term"));
    }

    // ─── getSharedTerms ──────────────────────────────────────────────

    @Test
    void getSharedTerms_returnsIntersection() {
        when(entityMentionRepository.findEntitiesByNodeId("n1"))
                .thenReturn(List.of("alpha", "beta", "gamma"));
        when(entityMentionRepository.findEntitiesByNodeId("n2"))
                .thenReturn(List.of("beta", "gamma", "delta"));

        List<String> shared = service.getSharedTerms("n1", "n2", 1L);

        assertEquals(2, shared.size());
        assertTrue(shared.contains("beta"));
        assertTrue(shared.contains("gamma"));
    }

    @Test
    void getSharedTerms_noOverlap_returnsEmpty() {
        when(entityMentionRepository.findEntitiesByNodeId("n1")).thenReturn(List.of("alpha"));
        when(entityMentionRepository.findEntitiesByNodeId("n2")).thenReturn(List.of("beta"));

        List<String> shared = service.getSharedTerms("n1", "n2", 1L);

        assertTrue(shared.isEmpty());
    }
}
