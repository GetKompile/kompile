/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.knowledgegraph.tool;

import ai.kompile.knowledgegraph.domain.*;
import ai.kompile.knowledgegraph.repository.*;
import ai.kompile.knowledgegraph.service.*;
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
 * Tests for {@link KnowledgeGraphToolImpl} — search, related docs, source context,
 * connected nodes, node search, entity extraction, topic search, overview, and path finding.
 *
 * Note: KnowledgeGraphToolImpl constructor takes 5 args (no GraphEdgeRepository).
 * Tests for lookupCell, listTableCells, createNode, updateNode, createEdge, and deleteNode
 * are not included — those methods do not exist on KnowledgeGraphToolImpl.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class KnowledgeGraphToolImplTest {

    @Mock private KnowledgeGraphService graphService;
    @Mock private SourceWeightingService weightingService;
    @Mock private EntityMentionRepository entityMentionRepository;
    @Mock private GraphNodeRepository nodeRepository;

    private KnowledgeGraphToolImpl tool;

    @BeforeEach
    void setUp() {
        // KnowledgeGraphToolImpl takes 5 args: graphService, weightingService,
        // entityMentionRepository, nodeRepository, McpOptimizationConfigProvider
        tool = new KnowledgeGraphToolImpl(
                graphService, weightingService,
                entityMentionRepository, nodeRepository,
                null  // no MCP optimization
        );
    }

    private GraphNode mockNode(String nodeId, String title, String description,
                                NodeLevel type, String externalId) {
        GraphNode node = mock(GraphNode.class);
        when(node.getNodeId()).thenReturn(nodeId);
        when(node.getTitle()).thenReturn(title);
        when(node.getDescription()).thenReturn(description);
        when(node.getNodeType()).thenReturn(type);
        when(node.getExternalId()).thenReturn(externalId);
        when(node.getEdgeCount()).thenReturn(0);
        return node;
    }

    // ─── searchByEntity ───────────────────────────────────────────────

    @Test
    void searchByEntity_nullName_returnsError() {
        var result = tool.searchByEntity(
                new KnowledgeGraphToolImpl.SearchByEntityInput(null, 10));
        assertEquals("Entity name cannot be empty", result.get("error"));
    }

    @Test
    void searchByEntity_blankName_returnsError() {
        var result = tool.searchByEntity(
                new KnowledgeGraphToolImpl.SearchByEntityInput("  ", 10));
        assertEquals("Entity name cannot be empty", result.get("error"));
    }

    @Test
    void searchByEntity_found_returnsResults() {
        GraphNode node = mockNode("n1", "Alice", "A person", NodeLevel.ENTITY, "ext-1");
        when(entityMentionRepository.findNodesWithEntity("alice")).thenReturn(List.of(node));

        var result = tool.searchByEntity(
                new KnowledgeGraphToolImpl.SearchByEntityInput("Alice", 10));

        assertEquals("Alice", result.get("entity"));
        assertEquals(1, result.get("resultCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
        assertEquals("ext-1", results.get(0).get("documentId"));
    }

    @Test
    void searchByEntity_maxResultsCapped() {
        List<GraphNode> many = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            many.add(mockNode("n" + i, "Node " + i, null, NodeLevel.DOCUMENT, "ext" + i));
        }
        when(entityMentionRepository.findNodesWithEntity("test")).thenReturn(many);

        var result = tool.searchByEntity(
                new KnowledgeGraphToolImpl.SearchByEntityInput("test", 100));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
        assertTrue(results.size() <= 20);
    }

    @Test
    void searchByEntity_defaultMaxResults() {
        when(entityMentionRepository.findNodesWithEntity("x")).thenReturn(List.of());
        var result = tool.searchByEntity(
                new KnowledgeGraphToolImpl.SearchByEntityInput("x", null));
        assertEquals(0, result.get("resultCount"));
    }

    @Test
    void searchByEntity_exception_returnsError() {
        when(entityMentionRepository.findNodesWithEntity(any()))
                .thenThrow(new RuntimeException("DB down"));

        var result = tool.searchByEntity(
                new KnowledgeGraphToolImpl.SearchByEntityInput("test", 10));
        assertTrue(((String) result.get("error")).contains("Search failed"));
    }

    // ─── getRelatedDocuments ──────────────────────────────────────────

    @Test
    void getRelatedDocuments_nullId_returnsError() {
        var result = tool.getRelatedDocuments(
                new KnowledgeGraphToolImpl.GetRelatedDocumentsInput(null, 10, "any"));
        assertEquals("Document ID cannot be empty", result.get("error"));
    }

    @Test
    void getRelatedDocuments_anyType_usesGraphService() {
        GraphNode related = mockNode("r1", "Related Doc", null, NodeLevel.DOCUMENT, "ext-r1");
        when(graphService.findRelatedNodes("doc-1", 10)).thenReturn(List.of(related));

        var result = tool.getRelatedDocuments(
                new KnowledgeGraphToolImpl.GetRelatedDocumentsInput("doc-1", 10, "any"));

        assertEquals(1, result.get("resultCount"));
        assertEquals("doc-1", result.get("sourceDocument"));
    }

    // ─── getSourceContext ─────────────────────────────────────────────

    @Test
    void getSourceContext_nullId_returnsError() {
        var result = tool.getSourceContext(
                new KnowledgeGraphToolImpl.GetSourceContextInput(null, false));
        assertEquals("Source ID cannot be empty", result.get("error"));
    }

    @Test
    void getSourceContext_notFound_returnsError() {
        when(graphService.getNode("s1")).thenReturn(Optional.empty());
        var result = tool.getSourceContext(
                new KnowledgeGraphToolImpl.GetSourceContextInput("s1", false));
        assertTrue(((String) result.get("error")).contains("Source not found"));
    }

    @Test
    void getSourceContext_found_returnsInfo() {
        GraphNode source = mockNode("s1", "My Source", "A source", NodeLevel.SOURCE, null);
        when(source.getSourceType()).thenReturn("file");
        when(source.getChildCount()).thenReturn(5);
        when(graphService.getNode("s1")).thenReturn(Optional.of(source));

        SourceWeight weight = mock(SourceWeight.class);
        when(weight.getEffectiveWeight()).thenReturn(1.0);
        when(weightingService.getSourceWeight("s1", null)).thenReturn(weight);

        var result = tool.getSourceContext(
                new KnowledgeGraphToolImpl.GetSourceContextInput("s1", false));

        assertEquals("s1", result.get("sourceId"));
        assertEquals("My Source", result.get("title"));
        assertEquals(5, result.get("documentCount"));
    }

    @Test
    void getSourceContext_includeChildren_listsDocs() {
        GraphNode source = mockNode("s1", "Source", null, NodeLevel.SOURCE, null);
        when(source.getSourceType()).thenReturn("file");
        when(source.getChildCount()).thenReturn(1);
        when(graphService.getNode("s1")).thenReturn(Optional.of(source));

        SourceWeight weight = mock(SourceWeight.class);
        when(weight.getEffectiveWeight()).thenReturn(1.0);
        when(weightingService.getSourceWeight("s1", null)).thenReturn(weight);

        GraphNode child = mockNode("d1", "Doc 1", null, NodeLevel.DOCUMENT, null);
        when(graphService.getChildren("s1")).thenReturn(List.of(child));

        var result = tool.getSourceContext(
                new KnowledgeGraphToolImpl.GetSourceContextInput("s1", true));

        assertNotNull(result.get("documents"));
    }

    // ─── findConnectedNodes ───────────────────────────────────────────

    @Test
    void findConnectedNodes_nullId_returnsError() {
        var result = tool.findConnectedNodes(
                new KnowledgeGraphToolImpl.FindConnectedNodesInput(null, 2));
        assertEquals("Node ID cannot be empty", result.get("error"));
    }

    @Test
    void findConnectedNodes_found_returnsNodeList() {
        GraphNode connected = mockNode("c1", "Connected", null, NodeLevel.ENTITY, "ext-c1");
        when(graphService.getConnectedNodes("start", 2)).thenReturn(List.of(connected));

        var result = tool.findConnectedNodes(
                new KnowledgeGraphToolImpl.FindConnectedNodesInput("start", 2));

        assertEquals("start", result.get("sourceNode"));
        assertEquals(2, result.get("depth"));
        assertEquals(1, result.get("nodeCount"));
    }

    @Test
    void findConnectedNodes_depthCappedAt3() {
        when(graphService.getConnectedNodes("n1", 3)).thenReturn(List.of());

        var result = tool.findConnectedNodes(
                new KnowledgeGraphToolImpl.FindConnectedNodesInput("n1", 10));

        assertEquals(3, result.get("depth"));
        verify(graphService).getConnectedNodes("n1", 3);
    }

    // ─── searchNodes ──────────────────────────────────────────────────

    @Test
    void searchNodes_nullQuery_returnsError() {
        var result = tool.searchNodes(
                new KnowledgeGraphToolImpl.SearchNodesInput(null, null, 10));
        assertEquals("Search query cannot be empty", result.get("error"));
    }

    @Test
    void searchNodes_withType_filtersResults() {
        GraphNode node = mockNode("n1", "Result", "Desc", NodeLevel.DOCUMENT, "ext-1");
        when(graphService.searchNodes("test", NodeLevel.DOCUMENT, 10))
                .thenReturn(List.of(node));

        var result = tool.searchNodes(
                new KnowledgeGraphToolImpl.SearchNodesInput("test", "DOCUMENT", 10));

        assertEquals(1, result.get("resultCount"));
        assertEquals("DOCUMENT", result.get("nodeType"));
    }

    @Test
    void searchNodes_invalidType_searchesAll() {
        when(graphService.searchNodes(eq("test"), isNull(), eq(10)))
                .thenReturn(List.of());

        var result = tool.searchNodes(
                new KnowledgeGraphToolImpl.SearchNodesInput("test", "INVALID_TYPE", 10));

        assertEquals(0, result.get("resultCount"));
    }

    // ─── getEntitiesInDocument ─────────────────────────────────────────

    @Test
    void getEntitiesInDocument_nullId_returnsError() {
        var result = tool.getEntitiesInDocument(
                new KnowledgeGraphToolImpl.GetEntitiesInDocumentInput(null));
        assertEquals("Document ID cannot be empty", result.get("error"));
    }

    @Test
    void getEntitiesInDocument_notFound_returnsError() {
        when(graphService.getNode("d1")).thenReturn(Optional.empty());
        when(nodeRepository.findByExternalIdAndNodeType("d1", NodeLevel.DOCUMENT))
                .thenReturn(Optional.empty());

        var result = tool.getEntitiesInDocument(
                new KnowledgeGraphToolImpl.GetEntitiesInDocumentInput("d1"));

        assertTrue(((String) result.get("error")).contains("Document not found"));
    }

    @Test
    void getEntitiesInDocument_found_returnsMentions() {
        GraphNode doc = mockNode("d1", "Doc", null, NodeLevel.DOCUMENT, "ext-d1");
        when(graphService.getNode("d1")).thenReturn(Optional.of(doc));

        EntityMention mention = mock(EntityMention.class);
        when(mention.getEntityName()).thenReturn("Alice");
        when(mention.getEntityType()).thenReturn("PERSON");
        when(mention.getMentionCount()).thenReturn(3);
        when(mention.getConfidence()).thenReturn(0.95);
        when(entityMentionRepository.findByNode(doc)).thenReturn(List.of(mention));

        var result = tool.getEntitiesInDocument(
                new KnowledgeGraphToolImpl.GetEntitiesInDocumentInput("d1"));

        assertEquals(1, result.get("entityCount"));
    }

    // ─── findDocumentsByTopic ─────────────────────────────────────────

    @Test
    void findDocumentsByTopic_nullTopic_returnsError() {
        var result = tool.findDocumentsByTopic(
                new KnowledgeGraphToolImpl.FindDocumentsByTopicInput(null, 10));
        assertEquals("Topic cannot be empty", result.get("error"));
    }

    @Test
    void findDocumentsByTopic_noSources_returnsEmpty() {
        when(weightingService.getSourcesForTopic("finance")).thenReturn(List.of());
        when(weightingService.getTopics()).thenReturn(List.of("tech", "science"));

        var result = tool.findDocumentsByTopic(
                new KnowledgeGraphToolImpl.FindDocumentsByTopicInput("finance", 10));

        assertTrue(((String) result.get("message")).contains("No sources found"));
    }

    @Test
    void findDocumentsByTopic_found_returnsDocuments() {
        when(weightingService.getSourcesForTopic("tech")).thenReturn(List.of("src1"));

        GraphNode doc = mockNode("d1", "Tech Doc", null, NodeLevel.DOCUMENT, null);
        when(graphService.getChildren("src1")).thenReturn(List.of(doc));

        var result = tool.findDocumentsByTopic(
                new KnowledgeGraphToolImpl.FindDocumentsByTopicInput("tech", 10));

        assertEquals(1, result.get("resultCount"));
    }

    // ─── getGraphOverview ─────────────────────────────────────────────

    @Test
    void getGraphOverview_returnsStats() {
        when(graphService.getGraphStatistics()).thenReturn(Map.of("nodes", 100, "edges", 50));
        when(graphService.getAllSources()).thenReturn(List.of());
        when(weightingService.getTopics()).thenReturn(List.of("topic1"));

        var result = tool.getGraphOverview();

        assertNotNull(result.get("statistics"));
        assertEquals(1, result.get("topicCount"));
        assertEquals(0, result.get("sourceCount"));
    }

    @Test
    void getGraphOverview_exception_returnsError() {
        when(graphService.getGraphStatistics()).thenThrow(new RuntimeException("fail"));

        var result = tool.getGraphOverview();
        assertTrue(((String) result.get("error")).contains("Failed to get overview"));
    }

    // graphRagQuery tests removed: KnowledgeGraphToolImpl does not have graphRagQuery method,
    // GraphRagQueryInput inner class, nor does GraphRagResult have EntityResult,
    // RelationshipResult, or SourceChunkReference inner classes.

}
