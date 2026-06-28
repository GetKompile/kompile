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
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.knowledgegraph.service.SourceLinkingService.*;
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
 * Tests for {@link SourceLinkingServiceImpl} — shared concept linking, embedding similarity,
 * combined linking, manual links, removal, connectivity analysis, term-based linking,
 * and isolated/most-connected source discovery.
 *
 * Note: SourceLinkingServiceImpl constructor is 1-arg (KnowledgeGraphService only).
 * All entity-mention lookups go through KnowledgeGraphService seam methods:
 *   getEntityMentionsForNode, getNodesWithEntity, getEntityNamesForNode,
 *   findEntityMention, saveEntityMention.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class SourceLinkingServiceImplTest {

    @Mock private KnowledgeGraphService knowledgeGraphService;

    private SourceLinkingServiceImpl service;

    @BeforeEach
    void setUp() {
        // Constructor is 1-arg — only KnowledgeGraphService
        service = new SourceLinkingServiceImpl(knowledgeGraphService);
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

    private GraphNode stubDocNode(String nodeId, Long factSheetId) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .nodeType(NodeLevel.DOCUMENT)
                .factSheetId(factSheetId)
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
        // impl calls knowledgeGraphService.getSourcesInFactSheet(factSheetId)
        when(knowledgeGraphService.getSourcesInFactSheet(1L))
                .thenReturn(List.of(stubSource("s1", "Source1")));

        LinkingResult result = service.linkSourcesBySharedConcepts(1L, defaultConfig());

        assertEquals(1, result.sourcesAnalyzed());
        assertEquals(0, result.linksCreated());
        assertTrue(result.statistics().containsKey("message"));
    }

    @Test
    void linkSourcesBySharedConcepts_noSharedConcepts_noLinksCreated() {
        GraphNode s1 = stubSource("s1", "Source1");
        GraphNode s2 = stubSource("s2", "Source2");
        when(knowledgeGraphService.getSourcesInFactSheet(1L)).thenReturn(List.of(s1, s2));

        // impl calls knowledgeGraphService.getChildren(sourceNodeId) for each source
        // then knowledgeGraphService.getEntityMentionsForNode(doc) for each child document
        when(knowledgeGraphService.getChildren("s1")).thenReturn(List.of());
        when(knowledgeGraphService.getChildren("s2")).thenReturn(List.of());

        LinkingResult result = service.linkSourcesBySharedConcepts(1L, defaultConfig());

        assertEquals(2, result.sourcesAnalyzed());
        assertEquals(0, result.linksCreated());
    }

    @Test
    void linkSourcesBySharedConcepts_sufficientOverlap_createsLink() {
        GraphNode s1 = stubSource("s1", "Source1");
        GraphNode s2 = stubSource("s2", "Source2");
        when(knowledgeGraphService.getSourcesInFactSheet(1L)).thenReturn(List.of(s1, s2));

        // Source1 has one document with concepts A, B, C, D
        GraphNode doc1 = stubDocNode("d1");
        when(knowledgeGraphService.getChildren("s1")).thenReturn(List.of(doc1));
        // impl calls knowledgeGraphService.getEntityMentionsForNode(doc) — NOT entityMentionRepository.findByNode
        EntityMention m1 = stubMention(doc1, "conceptA", 1L);
        EntityMention m2 = stubMention(doc1, "conceptB", 1L);
        EntityMention m3 = stubMention(doc1, "conceptC", 1L);
        EntityMention m4 = stubMention(doc1, "conceptD", 1L);
        when(knowledgeGraphService.getEntityMentionsForNode(doc1)).thenReturn(List.of(m1, m2, m3, m4));

        // Source2 has one document with concepts A, B, C, E
        GraphNode doc2 = stubDocNode("d2");
        when(knowledgeGraphService.getChildren("s2")).thenReturn(List.of(doc2));
        EntityMention m5 = stubMention(doc2, "conceptA", 1L);
        EntityMention m6 = stubMention(doc2, "conceptB", 1L);
        EntityMention m7 = stubMention(doc2, "conceptC", 1L);
        EntityMention m8 = stubMention(doc2, "conceptE", 1L);
        when(knowledgeGraphService.getEntityMentionsForNode(doc2)).thenReturn(List.of(m5, m6, m7, m8));

        // impl checks knowledgeGraphService.edgeExistsInFactSheet (both directions)
        when(knowledgeGraphService.edgeExistsInFactSheet("s1", "s2", 1L)).thenReturn(false);
        when(knowledgeGraphService.edgeExistsInFactSheet("s2", "s1", 1L)).thenReturn(false);

        // impl calls knowledgeGraphService.createEdgeWithMetadata(...)
        GraphEdge createdEdge = mock(GraphEdge.class);
        when(knowledgeGraphService.createEdgeWithMetadata(
                eq("s1"), eq("s2"), any(EdgeType.class), anyDouble(),
                isNull(), anyString(), anyString(), isNull(), eq(1L)))
                .thenReturn(createdEdge);

        LinkingResult result = service.linkSourcesBySharedConcepts(1L, defaultConfig());

        assertEquals(2, result.sourcesAnalyzed());
        assertEquals(1, result.linksCreated());
        assertEquals(1, result.links().size());
        verify(knowledgeGraphService).createEdgeWithMetadata(
                eq("s1"), eq("s2"), any(EdgeType.class), anyDouble(),
                isNull(), anyString(), anyString(), isNull(), eq(1L));
    }

    @Test
    void linkSourcesBySharedConcepts_existingEdge_skipsCreation() {
        GraphNode s1 = stubSource("s1", "Source1");
        GraphNode s2 = stubSource("s2", "Source2");
        when(knowledgeGraphService.getSourcesInFactSheet(1L)).thenReturn(List.of(s1, s2));

        GraphNode doc1 = stubDocNode("d1");
        when(knowledgeGraphService.getChildren("s1")).thenReturn(List.of(doc1));
        when(knowledgeGraphService.getEntityMentionsForNode(doc1)).thenReturn(List.of(
                stubMention(doc1, "a", 1L), stubMention(doc1, "b", 1L), stubMention(doc1, "c", 1L)));

        GraphNode doc2 = stubDocNode("d2");
        when(knowledgeGraphService.getChildren("s2")).thenReturn(List.of(doc2));
        when(knowledgeGraphService.getEntityMentionsForNode(doc2)).thenReturn(List.of(
                stubMention(doc2, "a", 1L), stubMention(doc2, "b", 1L), stubMention(doc2, "c", 1L)));

        // Edge already exists in forward direction
        when(knowledgeGraphService.edgeExistsInFactSheet("s1", "s2", 1L)).thenReturn(true);

        LinkingResult result = service.linkSourcesBySharedConcepts(1L, defaultConfig());

        assertEquals(0, result.linksCreated());
        verify(knowledgeGraphService, never()).createEdgeWithMetadata(
                any(), any(), any(), anyDouble(), any(), any(), any(), any(), any());
    }

    @Test
    void linkSourcesBySharedConcepts_crossSourceEdgeType_whenConfigured() {
        GraphNode s1 = stubSource("s1", "Source1");
        GraphNode s2 = stubSource("s2", "Source2");
        when(knowledgeGraphService.getSourcesInFactSheet(1L)).thenReturn(List.of(s1, s2));

        GraphNode doc1 = stubDocNode("d1");
        when(knowledgeGraphService.getChildren("s1")).thenReturn(List.of(doc1));
        when(knowledgeGraphService.getEntityMentionsForNode(doc1)).thenReturn(List.of(
                stubMention(doc1, "x", 1L), stubMention(doc1, "y", 1L), stubMention(doc1, "z", 1L)));

        GraphNode doc2 = stubDocNode("d2");
        when(knowledgeGraphService.getChildren("s2")).thenReturn(List.of(doc2));
        when(knowledgeGraphService.getEntityMentionsForNode(doc2)).thenReturn(List.of(
                stubMention(doc2, "x", 1L), stubMention(doc2, "y", 1L), stubMention(doc2, "z", 1L)));

        when(knowledgeGraphService.edgeExistsInFactSheet(any(), any(), eq(1L))).thenReturn(false);
        GraphEdge edge = mock(GraphEdge.class);
        when(knowledgeGraphService.createEdgeWithMetadata(
                any(), any(), eq(EdgeType.CROSS_SOURCE), anyDouble(),
                isNull(), anyString(), anyString(), isNull(), eq(1L)))
                .thenReturn(edge);

        // defaultConfig() has createCrossSourceEdges=true
        service.linkSourcesBySharedConcepts(1L, defaultConfig());

        verify(knowledgeGraphService).createEdgeWithMetadata(
                any(), any(), eq(EdgeType.CROSS_SOURCE), anyDouble(),
                isNull(), anyString(), anyString(), isNull(), eq(1L));
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
        // impl calls linkSourcesBySharedConcepts → knowledgeGraphService.getSourcesInFactSheet
        when(knowledgeGraphService.getSourcesInFactSheet(1L))
                .thenReturn(List.of(stubSource("s1", "S1")));

        LinkingResult result = service.linkAllSources(1L, defaultConfig());

        // linkSourcesBySharedConcepts returns early (<2 sources)
        // linkSourcesByEmbeddingSimilarity returns stub (useEmbeddingSimilarity=true in defaults)
        assertEquals(1, result.sourcesAnalyzed());
        assertEquals(0, result.linksCreated());
    }

    @Test
    void linkAllSources_skipsEmbedding_whenDisabled() {
        LinkingConfig noEmbedding = new LinkingConfig(3, 0.7, 0.2, true, false, true, true);
        when(knowledgeGraphService.getSourcesInFactSheet(1L))
                .thenReturn(List.of(stubSource("s1", "S1")));

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

        // impl calls knowledgeGraphService.getEdgesByTypeInFactSheet(factSheetId, CROSS_SOURCE)
        when(knowledgeGraphService.getEdgesByTypeInFactSheet(1L, EdgeType.CROSS_SOURCE))
                .thenReturn(List.of(edge));

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

        // impl calls knowledgeGraphService.getEdgesForNodeInFactSheet(sourceNodeId, factSheetId)
        when(knowledgeGraphService.getEdgesForNodeInFactSheet("s1", 1L))
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
        when(knowledgeGraphService.getEdgesForNodeInFactSheet("s1", 1L))
                .thenReturn(List.of(edge));

        List<SourceLink> links = service.getLinksForSource(1L, "s1");

        assertEquals(1, links.size());
        assertEquals("SHARED_ENTITY", links.get(0).linkType());
    }

    // ─── createManualLink ─────────────────────────────────────────────

    @Test
    void createManualLink_validSources_createsEdge() {
        GraphNode s1 = stubSource("s1", "Source1");
        GraphNode s2 = stubSource("s2", "Source2");
        // impl calls knowledgeGraphService.getNode(nodeId)
        when(knowledgeGraphService.getNode("s1")).thenReturn(Optional.of(s1));
        when(knowledgeGraphService.getNode("s2")).thenReturn(Optional.of(s2));

        GraphEdge createdEdge = mock(GraphEdge.class);
        when(knowledgeGraphService.createEdgeWithMetadata(
                eq("s1"), eq("s2"), eq(EdgeType.USER_DEFINED), eq(0.9),
                isNull(), eq("Manual link"), isNull(), isNull(), eq(1L)))
                .thenReturn(createdEdge);

        SourceLink link = service.createManualLink(1L, "s1", "s2", null, 0.9);

        assertEquals("s1", link.sourceId1());
        assertEquals("s2", link.sourceId2());
        assertEquals("USER_DEFINED", link.linkType());
        assertEquals(0.9, link.strength());
        verify(knowledgeGraphService).createEdgeWithMetadata(
                eq("s1"), eq("s2"), eq(EdgeType.USER_DEFINED), eq(0.9),
                isNull(), eq("Manual link"), isNull(), isNull(), eq(1L));
    }

    @Test
    void createManualLink_nodeNotFound_throws() {
        when(knowledgeGraphService.getNode("missing")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.createManualLink(1L, "missing", "s2", "desc", 0.5));
    }

    @Test
    void createManualLink_nonSourceNode_throws() {
        GraphNode docNode = stubDocNode("d1");
        GraphNode s2 = stubSource("s2", "Source2");
        when(knowledgeGraphService.getNode("d1")).thenReturn(Optional.of(docNode));
        when(knowledgeGraphService.getNode("s2")).thenReturn(Optional.of(s2));

        assertThrows(IllegalArgumentException.class,
                () -> service.createManualLink(1L, "d1", "s2", "desc", 0.5));
    }

    @Test
    void createManualLink_withDescription_usesIt() {
        GraphNode s1 = stubSource("s1", "Source1");
        GraphNode s2 = stubSource("s2", "Source2");
        when(knowledgeGraphService.getNode("s1")).thenReturn(Optional.of(s1));
        when(knowledgeGraphService.getNode("s2")).thenReturn(Optional.of(s2));

        GraphEdge createdEdge = mock(GraphEdge.class);
        when(knowledgeGraphService.createEdgeWithMetadata(
                eq("s1"), eq("s2"), eq(EdgeType.USER_DEFINED), eq(0.7),
                isNull(), eq("Custom desc"), isNull(), isNull(), eq(1L)))
                .thenReturn(createdEdge);

        SourceLink link = service.createManualLink(1L, "s1", "s2", "Custom desc", 0.7);

        assertEquals("Custom desc", link.description());
    }

    // ─── removeLink ───────────────────────────────────────────────────

    @Test
    void removeLink_forwardDirection_removesAndReturnsTrue() {
        GraphEdge edge = mock(GraphEdge.class);
        String edgeId = "edge-id-fwd";
        when(edge.getEdgeId()).thenReturn(edgeId);

        when(knowledgeGraphService.findEdgeBetweenNodes("s1", "s2")).thenReturn(edge);

        assertTrue(service.removeLink(1L, "s1", "s2"));
        verify(knowledgeGraphService).deleteEdge(edgeId);
    }

    @Test
    void removeLink_reverseDirection_removesAndReturnsTrue() {
        when(knowledgeGraphService.findEdgeBetweenNodes("s1", "s2")).thenReturn(null);

        GraphEdge edge = mock(GraphEdge.class);
        String edgeId = "edge-id-rev";
        when(edge.getEdgeId()).thenReturn(edgeId);
        when(knowledgeGraphService.findEdgeBetweenNodes("s2", "s1")).thenReturn(edge);

        assertTrue(service.removeLink(1L, "s1", "s2"));
        verify(knowledgeGraphService).deleteEdge(edgeId);
    }

    @Test
    void removeLink_noEdge_returnsFalse() {
        when(knowledgeGraphService.findEdgeBetweenNodes(any(), any())).thenReturn(null);

        assertFalse(service.removeLink(1L, "s1", "s2"));
        verify(knowledgeGraphService, never()).deleteEdge(any());
    }

    // ─── getConnectivitySummary ───────────────────────────────────────

    @Test
    void getConnectivitySummary_noSources_returnsZeros() {
        when(knowledgeGraphService.getSourcesInFactSheet(1L)).thenReturn(List.of());
        when(knowledgeGraphService.getEdgesInFactSheet(1L)).thenReturn(List.of());

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
        when(knowledgeGraphService.getSourcesInFactSheet(1L)).thenReturn(List.of(s1, s2, s3));

        GraphEdge edge = stubEdge(s1, s2, EdgeType.CROSS_SOURCE, 0.8);
        when(knowledgeGraphService.getEdgesInFactSheet(1L)).thenReturn(List.of(edge));

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
        when(knowledgeGraphService.getSourcesInFactSheet(1L)).thenReturn(List.of(s1, s2));
        when(knowledgeGraphService.getEdgesInFactSheet(1L)).thenReturn(List.of());

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
        when(knowledgeGraphService.getSourcesInFactSheet(1L)).thenReturn(List.of(s1, s2, s3));

        GraphEdge edge = stubEdge(s1, s2, EdgeType.CROSS_SOURCE, 0.8);
        when(knowledgeGraphService.getEdgesInFactSheet(1L)).thenReturn(List.of(edge));

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
        when(knowledgeGraphService.getSourcesInFactSheet(1L)).thenReturn(List.of(s1, s2, s3));

        GraphEdge e1 = stubEdge(s1, s2, EdgeType.CROSS_SOURCE, 0.8);
        GraphEdge e2 = stubEdge(s2, s3, EdgeType.CROSS_SOURCE, 0.7);
        when(knowledgeGraphService.getEdgesInFactSheet(1L)).thenReturn(List.of(e1, e2));

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
        when(knowledgeGraphService.getSourcesInFactSheet(1L)).thenReturn(List.of(s1, s2, s3));
        when(knowledgeGraphService.getEdgesInFactSheet(1L)).thenReturn(List.of());

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
        // impl calls knowledgeGraphService.getNodesWithEntity(normalizedTerm) filtered by factSheetId
        // NOT entityMentionRepository.findByEntityNameAndFactSheet
        GraphNode n1 = stubDocNode("n1", 1L);
        when(knowledgeGraphService.getNodesWithEntity("kubernetes"))
                .thenReturn(List.of(n1));

        TermLinkingResult result = service.linkNodesByTerm("Kubernetes", 1L, null, null);

        assertEquals(1, result.nodesFound());
        assertEquals(0, result.linksCreated());
        assertTrue(result.message().contains("Need at least 2 nodes"));
    }

    @Test
    void linkNodesByTerm_multipleNodes_createsPairwiseEdges() {
        GraphNode n1 = stubDocNode("n1", 1L);
        GraphNode n2 = stubDocNode("n2", 1L);
        GraphNode n3 = stubDocNode("n3", 1L);

        // impl calls knowledgeGraphService.getNodesWithEntity(normalizedTerm) filtered by factSheetId
        when(knowledgeGraphService.getNodesWithEntity("kubernetes"))
                .thenReturn(List.of(n1, n2, n3));

        // impl uses findEdgeBetweenNodesBidirectional (Optional) — not edgeRepository
        when(knowledgeGraphService.findEdgeBetweenNodesBidirectional(any(), any()))
                .thenReturn(Optional.empty());

        GraphEdge edge = mock(GraphEdge.class);
        when(knowledgeGraphService.createEdgeWithMetadata(
                any(), any(), any(EdgeType.class), anyDouble(),
                isNull(), anyString(), anyString(), isNull(), eq(1L)))
                .thenReturn(edge);

        TermLinkingResult result = service.linkNodesByTerm("Kubernetes", 1L, null, null);

        assertEquals(3, result.nodesFound());
        assertEquals(3, result.linksCreated()); // C(3,2) = 3 pairs
    }

    @Test
    void linkNodesByTerm_defaultsEdgeTypeAndWeight() {
        GraphNode n1 = stubDocNode("n1", 1L);
        GraphNode n2 = stubDocNode("n2", 1L);
        when(knowledgeGraphService.getNodesWithEntity("test"))
                .thenReturn(List.of(n1, n2));

        when(knowledgeGraphService.findEdgeBetweenNodesBidirectional(any(), any()))
                .thenReturn(Optional.empty());

        GraphEdge edge = mock(GraphEdge.class);
        when(knowledgeGraphService.createEdgeWithMetadata(
                any(), any(), eq(EdgeType.SHARED_ENTITY), eq(0.7),
                isNull(), anyString(), anyString(), isNull(), eq(1L)))
                .thenReturn(edge);

        service.linkNodesByTerm("Test!", 1L, null, null);

        verify(knowledgeGraphService).createEdgeWithMetadata(
                any(), any(), eq(EdgeType.SHARED_ENTITY), eq(0.7),
                isNull(), anyString(), anyString(), isNull(), eq(1L));
    }

    @Test
    void linkNodesByTerm_existingEdge_skips() {
        GraphNode n1 = stubDocNode("n1", 1L);
        GraphNode n2 = stubDocNode("n2", 1L);
        when(knowledgeGraphService.getNodesWithEntity("test"))
                .thenReturn(List.of(n1, n2));

        // An existing edge (either direction) must short-circuit and skip creation.
        when(knowledgeGraphService.findEdgeBetweenNodesBidirectional(any(), any()))
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
        when(knowledgeGraphService.getNodesWithEntity(any())).thenReturn(List.of());

        List<TermLinkingResult> results = service.linkNodesByTerms(
                List.of("alpha", "beta"), 1L, null, null);

        assertEquals(2, results.size());
    }

    // ─── createTermBasedRelation ──────────────────────────────────────

    @Test
    void createTermBasedRelation_createsEdgeAndMentions() {
        GraphNode s = stubSource("s1", "Source1");
        GraphNode t = stubSource("t1", "Target1");
        when(knowledgeGraphService.getNode("s1")).thenReturn(Optional.of(s));
        when(knowledgeGraphService.getNode("t1")).thenReturn(Optional.of(t));

        // impl calls knowledgeGraphService.createEdgeWithMetadata
        GraphEdge edge = mock(GraphEdge.class);
        when(knowledgeGraphService.createEdgeWithMetadata(
                eq("s1"), eq("t1"), eq(EdgeType.USER_DEFINED), eq(0.8),
                eq("Test-Term"), eq("Custom desc"), anyString(), isNull(), isNull()))
                .thenReturn(edge);

        // impl calls knowledgeGraphService.findEntityMention for both nodes (NOT entityMentionRepository)
        when(knowledgeGraphService.findEntityMention(any(), any())).thenReturn(Optional.empty());

        SourceLink link = service.createTermBasedRelation("s1", "t1", "Test-Term", "Custom desc", 0.8, true);

        assertEquals("USER_DEFINED", link.linkType());
        assertEquals(0.8, link.strength());
        // Entity mentions saved via knowledgeGraphService.saveEntityMention for both nodes
        verify(knowledgeGraphService, times(2)).saveEntityMention(any(EntityMention.class));
        verify(knowledgeGraphService).createEdgeWithMetadata(
                eq("s1"), eq("t1"), eq(EdgeType.USER_DEFINED), eq(0.8),
                eq("Test-Term"), eq("Custom desc"), anyString(), isNull(), isNull());
    }

    @Test
    void createTermBasedRelation_nodeNotFound_throws() {
        when(knowledgeGraphService.getNode("missing")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.createTermBasedRelation("missing", "t1", "term", null, 0.7, true));
    }

    @Test
    void createTermBasedRelation_nullDescription_usesDefault() {
        GraphNode s = stubSource("s1", "S");
        GraphNode t = stubSource("t1", "T");
        when(knowledgeGraphService.getNode("s1")).thenReturn(Optional.of(s));
        when(knowledgeGraphService.getNode("t1")).thenReturn(Optional.of(t));

        GraphEdge edge = mock(GraphEdge.class);
        // When description is null, impl substitutes "Related by: <term>"
        when(knowledgeGraphService.createEdgeWithMetadata(
                eq("s1"), eq("t1"), eq(EdgeType.USER_DEFINED), eq(0.7),
                eq("concept"), contains("Related by"), anyString(), isNull(), isNull()))
                .thenReturn(edge);
        when(knowledgeGraphService.findEntityMention(any(), any())).thenReturn(Optional.empty());

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
        // impl calls knowledgeGraphService.getNodesWithEntity(normalizedTerm) filtered by factSheetId
        GraphNode n1 = stubDocNode("n1", 1L);
        when(knowledgeGraphService.getNodesWithEntity("kubernetes"))
                .thenReturn(List.of(n1));

        List<String> result = service.findNodesWithTerm("Kubernetes", 1L, 10);

        assertEquals(1, result.size());
        assertEquals("n1", result.get(0));
    }

    @Test
    void findNodesWithTerm_nullFactSheetId_searchesGlobally() {
        // impl calls knowledgeGraphService.getNodesWithEntity globally when factSheetId=null
        GraphNode n1 = stubDocNode("n1", 1L);
        when(knowledgeGraphService.getNodesWithEntity("test")).thenReturn(List.of(n1));

        List<String> result = service.findNodesWithTerm("test", null, 10);

        assertEquals(1, result.size());
    }

    // ─── getAllTerms ──────────────────────────────────────────────────

    @Test
    void getAllTerms_withFactSheetId_returnsTerms() {
        // impl calls knowledgeGraphService.getNodesByTypeInFactSheet(factSheetId, ENTITY)
        // and counts occurrences of each normalized title — NOT entityMentionRepository
        GraphNode e1 = GraphNode.builder().nodeId("e1").nodeType(NodeLevel.ENTITY)
                .title("kubernetes").factSheetId(1L).build();
        GraphNode e2 = GraphNode.builder().nodeId("e2").nodeType(NodeLevel.ENTITY)
                .title("kubernetes").factSheetId(1L).build();
        GraphNode e3 = GraphNode.builder().nodeId("e3").nodeType(NodeLevel.ENTITY)
                .title("kubernetes").factSheetId(1L).build();
        GraphNode e4 = GraphNode.builder().nodeId("e4").nodeType(NodeLevel.ENTITY)
                .title("kubernetes").factSheetId(1L).build();
        GraphNode e5 = GraphNode.builder().nodeId("e5").nodeType(NodeLevel.ENTITY)
                .title("kubernetes").factSheetId(1L).build();
        when(knowledgeGraphService.getNodesByTypeInFactSheet(1L, NodeLevel.ENTITY))
                .thenReturn(List.of(e1, e2, e3, e4, e5));

        List<Map<String, Object>> result = service.getAllTerms(1L, 10);

        assertEquals(1, result.size());
        assertEquals("kubernetes", result.get(0).get("term"));
        assertEquals(5L, result.get(0).get("count"));
    }

    @Test
    void getAllTerms_nullFactSheetId_returnsGlobalTerms() {
        // impl calls knowledgeGraphService.getNodesByType(ENTITY) globally
        GraphNode e1 = GraphNode.builder().nodeId("e1").nodeType(NodeLevel.ENTITY)
                .title("docker").build();
        GraphNode e2 = GraphNode.builder().nodeId("e2").nodeType(NodeLevel.ENTITY)
                .title("docker").build();
        GraphNode e3 = GraphNode.builder().nodeId("e3").nodeType(NodeLevel.ENTITY)
                .title("docker").build();
        when(knowledgeGraphService.getNodesByType(NodeLevel.ENTITY))
                .thenReturn(List.of(e1, e2, e3));

        List<Map<String, Object>> result = service.getAllTerms(null, 10);

        assertEquals(1, result.size());
        assertEquals("docker", result.get(0).get("term"));
        assertEquals(3L, result.get(0).get("count"));
    }

    // ─── getSharedTerms ──────────────────────────────────────────────

    @Test
    void getSharedTerms_returnsIntersection() {
        // impl calls knowledgeGraphService.getEntityNamesForNode(nodeId) — NOT entityMentionRepository
        when(knowledgeGraphService.getEntityNamesForNode("n1"))
                .thenReturn(List.of("alpha", "beta", "gamma"));
        when(knowledgeGraphService.getEntityNamesForNode("n2"))
                .thenReturn(List.of("beta", "gamma", "delta"));

        List<String> shared = service.getSharedTerms("n1", "n2", 1L);

        assertEquals(2, shared.size());
        assertTrue(shared.contains("beta"));
        assertTrue(shared.contains("gamma"));
    }

    @Test
    void getSharedTerms_noOverlap_returnsEmpty() {
        when(knowledgeGraphService.getEntityNamesForNode("n1")).thenReturn(List.of("alpha"));
        when(knowledgeGraphService.getEntityNamesForNode("n2")).thenReturn(List.of("beta"));

        List<String> shared = service.getSharedTerms("n1", "n2", 1L);

        assertTrue(shared.isEmpty());
    }
}
