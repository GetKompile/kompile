/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.knowledgegraph.controller;

import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.GraphBuildingService;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.knowledgegraph.service.SourceLinkingService;
import ai.kompile.knowledgegraph.service.SourceWeightingService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests the provenance-based delete endpoint (purge facts from a crawl run / source document).
 */
class KnowledgeGraphControllerProvenanceTest {

    private final KnowledgeGraphService graphService = mock(KnowledgeGraphService.class);
    private final KnowledgeGraphController controller = new KnowledgeGraphController(
            graphService, mock(SourceWeightingService.class),
            mock(GraphBuildingService.class), mock(SourceLinkingService.class));

    private GraphNode node(String id, String metadataJson) {
        return GraphNode.builder()
                .nodeId(id).externalId(id).nodeType(NodeLevel.ENTITY).title(id)
                .metadataJson(metadataJson).build();
    }

    @Test
    void deleteByProvenance_deletesOnlyMatchingCrawlRunNodes() {
        GraphNode match = node("n1", "{\"_crawlRunId\":\"job-1\"}");
        GraphNode other = node("n2", "{\"_crawlRunId\":\"job-2\"}");
        when(graphService.getNodesInFactSheet(42L)).thenReturn(List.of(match, other));

        ResponseEntity<Map<String, Object>> resp = controller.deleteByProvenance("job-1", null, 42L, false);

        assertEquals(1, resp.getBody().get("matched"));
        assertEquals(1, resp.getBody().get("deleted"));
        verify(graphService).deleteNode("n1");
        verify(graphService, never()).deleteNode("n2");
    }

    @Test
    void deleteByProvenance_dryRun_previewsWithoutDeleting() {
        GraphNode match = node("n1", "{\"_sourceDocumentId\":\"doc.pdf\"}");
        when(graphService.getNodesInFactSheet(42L)).thenReturn(List.of(match));

        ResponseEntity<Map<String, Object>> resp = controller.deleteByProvenance(null, "doc.pdf", 42L, true);

        assertEquals(1, resp.getBody().get("matched"));
        assertEquals(0, resp.getBody().get("deleted"));
        assertEquals(true, resp.getBody().get("dryRun"));
        verify(graphService, never()).deleteNode(anyString());
    }

    @Test
    void deleteByProvenance_noCriteria_isBadRequest() {
        ResponseEntity<Map<String, Object>> resp = controller.deleteByProvenance(null, null, null, false);
        assertEquals(400, resp.getStatusCode().value());
        verifyNoInteractions(graphService);
    }
}
