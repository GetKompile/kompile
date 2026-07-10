/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.tool.graph;

import ai.kompile.knowledgegraph.domain.*;
import ai.kompile.knowledgegraph.grounding.GroundingResetPort;
import ai.kompile.knowledgegraph.reasoning.GraphToFactStoreProjector;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.graph.algorithms.service.GraphAlgorithmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GraphMutationToolTest {

    @Mock private KnowledgeGraphService graphService;
    @Mock private GraphAlgorithmService algorithmService;
    @Mock private GraphToFactStoreProjector factStoreProjector;
    @Mock private GroundingResetPort groundingResetPort;

    private GraphMutationTool tool;

    @BeforeEach
    void setUp() {
        tool = new GraphMutationTool(graphService, algorithmService, factStoreProjector, groundingResetPort);
    }

    @Test
    void createNode_missingTitle_returnsError() {
        var result = tool.createNode(new GraphMutationTool.CreateNodeInput("", "ENTITY", null, null, null));
        assertEquals("title is required", result.get("error"));
    }

    @Test
    void createNode_validInput_createsAndInvalidatesCache() {
        GraphNode node = GraphSearchToolTest.createNode("new-id", "New Entity", NodeLevel.ENTITY);
        when(graphService.createNode(eq(NodeLevel.ENTITY), isNull(), eq("New Entity"),
                eq("A description"), eq(Map.of()), isNull()))
                .thenReturn(node);

        var result = tool.createNode(new GraphMutationTool.CreateNodeInput(
                "New Entity", "ENTITY", "A description", null, null));

        assertEquals("new-id", result.get("nodeId"));
        assertEquals("New Entity", result.get("title"));
        verify(algorithmService).invalidateCache((Long) null);
    }

    @Test
    void createNode_preservesSeedMetadata() {
        Map<String, Object> metadata = Map.of("traceId", "attr-1", "confidence", 0.82);
        GraphNode node = GraphSearchToolTest.createNode("new-id", "New Entity", NodeLevel.ENTITY);
        when(graphService.createNode(eq(NodeLevel.ENTITY), isNull(), eq("New Entity"),
                eq("A description"), eq(metadata), eq(7L)))
                .thenReturn(node);

        var result = tool.createNode(new GraphMutationTool.CreateNodeInput(
                "New Entity", "ENTITY", "A description", null, 7L, metadata));

        assertEquals("new-id", result.get("nodeId"));
        assertEquals(7L, result.get("factSheetId"));
        verify(algorithmService).invalidateCache(7L);
    }

    @Test
    void bulkCreateNodes_usesBatchAndScopedInvalidation() {
        GraphNode node1 = GraphSearchToolTest.createNode("n1", "Revenue", NodeLevel.ENTITY);
        GraphNode node2 = GraphSearchToolTest.createNode("n2", "Cost", NodeLevel.ENTITY);
        when(graphService.createNodesBatch(anyList(), eq(7L))).thenReturn(List.of(node1, node2));

        var result = tool.bulkCreateNodes(new GraphMutationTool.BulkCreateNodesInput(List.of(
                new GraphMutationTool.BulkCreateNodesInput.NodeSpec(
                        "Revenue", "ENTITY", "Revenue metric", "rev", Map.of("traceId", "attr-1")),
                new GraphMutationTool.BulkCreateNodesInput.NodeSpec(
                        "Cost", "ENTITY", "Cost metric", "cost", Map.of("traceId", "attr-2"))
        ), 7L));

        assertEquals(2, result.get("created"));
        assertEquals(2, ((List<?>) result.get("nodes")).size());
        verify(graphService).createNodesBatch(argThat(specs -> specs.size() == 2), eq(7L));
        verify(graphService, never()).createNode(any(), any(), any(), any(), any(), any());
        verify(algorithmService).invalidateCache(7L);
    }

    @Test
    void createNode_defaultsToCustomType() {
        GraphNode node = GraphSearchToolTest.createNode("id", "Node", NodeLevel.CUSTOM);
        when(graphService.createNode(eq(NodeLevel.CUSTOM), any(), any(), any(), any(), any()))
                .thenReturn(node);

        var result = tool.createNode(new GraphMutationTool.CreateNodeInput(
                "Node", "invalid_type", null, null, null));

        assertNull(result.get("error"));
    }

    @Test
    void updateNode_missingId_returnsError() {
        var result = tool.updateNode(new GraphMutationTool.UpdateNodeInput("", "t", "d"));
        assertEquals("nodeId is required", result.get("error"));
    }

    @Test
    void deleteNode_notFound_returnsError() {
        when(graphService.getNode("missing")).thenReturn(Optional.empty());
        var result = tool.deleteNode(new GraphMutationTool.DeleteNodeInput("missing"));
        assertEquals("Node not found: missing", result.get("error"));
    }

    @Test
    void deleteNode_found_deletesAndInvalidates() {
        GraphNode node = GraphSearchToolTest.createNode("n1", "Delete Me", NodeLevel.ENTITY);
        when(graphService.getNode("n1")).thenReturn(Optional.of(node));

        var result = tool.deleteNode(new GraphMutationTool.DeleteNodeInput("n1"));

        assertEquals(true, result.get("deleted"));
        assertEquals("n1", result.get("nodeId"));
        verify(graphService).deleteNode("n1");
        verify(algorithmService).invalidateCache((Long) null);
    }

    @Test
    void createEdge_missingNodes_returnsError() {
        var result = tool.createEdge(new GraphMutationTool.CreateEdgeInput(null, "b", null, null, null));
        assertEquals("Both sourceNodeId and targetNodeId are required", result.get("error"));
    }

    @Test
    void createEdge_defaultsToUserDefined() {
        GraphEdge edge = new GraphEdge();
        edge.setEdgeId("e1");
        edge.setEdgeType(EdgeType.USER_DEFINED);
        edge.setWeight(1.0);
        when(graphService.createEdge("a", "b", EdgeType.USER_DEFINED, 1.0, "test"))
                .thenReturn(edge);

        var result = tool.createEdge(new GraphMutationTool.CreateEdgeInput(
                "a", "b", null, null, "test"));

        assertEquals("e1", result.get("edgeId"));
        verify(algorithmService).invalidateCache();
    }

    @Test
    void createEdge_withFactSheetAndRelation_usesMetadataAndScopedInvalidation() {
        GraphEdge edge = new GraphEdge();
        edge.setEdgeId("e2");
        edge.setEdgeType(EdgeType.USER_DEFINED);
        edge.setWeight(0.8);
        edge.setDescription("drives lower margin");
        when(graphService.createEdgeWithMetadata("a", "b", EdgeType.USER_DEFINED, 0.8,
                "CAUSES", "drives lower margin", null, null, 7L))
                .thenReturn(edge);

        var result = tool.createEdge(new GraphMutationTool.CreateEdgeInput(
                "a", "b", "USER_DEFINED", 0.8, "drives lower margin", "CAUSES", 7L));

        assertEquals("e2", result.get("edgeId"));
        assertEquals("CAUSES", result.get("relationType"));
        assertEquals(7L, result.get("factSheetId"));
        verify(algorithmService).invalidateCache(7L);
        verify(algorithmService, never()).invalidateCache();
    }

    @Test
    void bulkCreateEdges_usesBatchWithRelationAndFactSheet() {
        when(graphService.createEdgesBatch(anyList())).thenReturn(2);

        var result = tool.bulkCreateEdges(new GraphMutationTool.BulkCreateEdgesInput(List.of(
                new GraphMutationTool.BulkCreateEdgesInput.EdgeSpec(
                        "a", "b", "USER_DEFINED", 0.9, "supports", "SUPPORTS", null),
                new GraphMutationTool.BulkCreateEdgesInput.EdgeSpec(
                        "b", "c", "CITATION", 1.0, "cites", "CITES", null)
        ), 7L));

        assertEquals(2, result.get("created"));
        ArgumentCaptor<List<KnowledgeGraphService.EdgeSpec>> captor = ArgumentCaptor.forClass(List.class);
        verify(graphService).createEdgesBatch(captor.capture());
        assertEquals(2, captor.getValue().size());
        assertEquals("SUPPORTS", captor.getValue().get(0).label());
        assertEquals(7L, captor.getValue().get(0).factSheetId());
        assertEquals(EdgeType.CITATION, captor.getValue().get(1).edgeType());
        verify(graphService, never()).createEdge(any(), any(), any(), any(), any());
        verify(algorithmService).invalidateCache(7L);
    }

    @Test
    void seedGraph_createsNodesAndEdgesUsingClientIds() {
        GraphNode revenue = GraphSearchToolTest.createNode("n-revenue", "Revenue", NodeLevel.ENTITY);
        GraphNode cost = GraphSearchToolTest.createNode("n-cost", "Cost", NodeLevel.ENTITY);
        when(graphService.createNodesBatch(anyList(), eq(7L))).thenReturn(List.of(revenue, cost));
        when(graphService.createEdgesBatch(anyList())).thenReturn(1);
        when(factStoreProjector.project(7L)).thenReturn(12);

        var result = tool.seedGraph(new GraphMutationTool.SeedGraphInput(
                List.of(
                        new GraphMutationTool.SeedGraphInput.SeedNodeSpec(
                                "revenue", null, "Revenue", "ENTITY", "Revenue metric",
                                "rev", Map.of("traceId", "attr-1"), null),
                        new GraphMutationTool.SeedGraphInput.SeedNodeSpec(
                                "cost", null, "Cost", "ENTITY", "Cost metric",
                                "cost", Map.of("traceId", "attr-2"), null)
                ),
                List.of(new GraphMutationTool.SeedGraphInput.SeedEdgeSpec(
                        null, null, "cost", "revenue", "USER_DEFINED", 0.7,
                        "cost pressure lowered revenue", "CAUSES", null)),
                7L));

        assertEquals(2, result.get("createdNodes"));
        assertEquals(1, result.get("createdEdges"));
        Map<?, ?> projectedAtoms = (Map<?, ?>) result.get("projectedAtomsByFactSheet");
        assertEquals(12, projectedAtoms.get(7L));
        Map<?, ?> clientNodeIds = (Map<?, ?>) result.get("clientNodeIds");
        assertEquals("n-revenue", clientNodeIds.get("revenue"));
        assertEquals("n-cost", clientNodeIds.get("cost"));

        ArgumentCaptor<List<KnowledgeGraphService.NodeSpec>> nodeCaptor = ArgumentCaptor.forClass(List.class);
        verify(graphService).createNodesBatch(nodeCaptor.capture(), eq(7L));
        assertEquals(Map.of("traceId", "attr-1"), nodeCaptor.getValue().get(0).metadata());

        ArgumentCaptor<List<KnowledgeGraphService.EdgeSpec>> edgeCaptor = ArgumentCaptor.forClass(List.class);
        verify(graphService).createEdgesBatch(edgeCaptor.capture());
        assertEquals("n-cost", edgeCaptor.getValue().get(0).sourceNodeId());
        assertEquals("n-revenue", edgeCaptor.getValue().get(0).targetNodeId());
        assertEquals("CAUSES", edgeCaptor.getValue().get(0).label());
        assertEquals(7L, edgeCaptor.getValue().get(0).factSheetId());
        verify(algorithmService).invalidateCache(7L);
    }

    @Test
    void seedGraph_unresolvedClientReferenceSkipsEdgeBatch() {
        var result = tool.seedGraph(new GraphMutationTool.SeedGraphInput(
                List.of(),
                List.of(new GraphMutationTool.SeedGraphInput.SeedEdgeSpec(
                        null, "target", "missing", null, "USER_DEFINED", 1.0,
                        "missing source", "SUPPORTS", 7L)),
                7L));

        assertEquals(0, result.get("validEdges"));
        assertTrue(((List<?>) result.get("errors")).get(0).toString().contains("source and target"));
        verify(graphService, never()).createEdgesBatch(anyList());
        verifyNoInteractions(algorithmService);
        verifyNoInteractions(factStoreProjector);
    }

    @Test
    void seedGraph_rollbackOnFailureDeletesCreatedEdgesAndNodes() {
        GraphNode revenue = GraphSearchToolTest.createNode("n-revenue", "Revenue", NodeLevel.ENTITY);
        GraphNode cost = GraphSearchToolTest.createNode("n-cost", "Cost", NodeLevel.ENTITY);
        when(graphService.createNodesBatch(anyList(), eq(7L))).thenReturn(List.of(revenue, cost));
        GraphEdge edge = new GraphEdge();
        edge.setEdgeId("e-cause");
        edge.setEdgeType(EdgeType.USER_DEFINED);
        edge.setWeight(0.7);
        edge.setDescription("cost pressure lowered revenue");
        when(graphService.createEdgeWithMetadata("n-cost", "n-revenue", EdgeType.USER_DEFINED, 0.7,
                "CAUSES", "cost pressure lowered revenue", null, null, 7L))
                .thenReturn(edge);
        when(graphService.createEdgeWithMetadata("n-revenue", "external-target", EdgeType.USER_DEFINED, 1.0,
                "SUPPORTS", "second edge fails", null, null, 7L))
                .thenThrow(new RuntimeException("edge write failed"));

        var result = tool.seedGraph(new GraphMutationTool.SeedGraphInput(
                List.of(
                        new GraphMutationTool.SeedGraphInput.SeedNodeSpec(
                                "revenue", null, "Revenue", "ENTITY", "Revenue metric",
                                "rev", Map.of("traceId", "attr-1"), null),
                        new GraphMutationTool.SeedGraphInput.SeedNodeSpec(
                                "cost", null, "Cost", "ENTITY", "Cost metric",
                                "cost", Map.of("traceId", "attr-2"), null)
                ),
                List.of(
                        new GraphMutationTool.SeedGraphInput.SeedEdgeSpec(
                                null, null, "cost", "revenue", "USER_DEFINED", 0.7,
                                "cost pressure lowered revenue", "CAUSES", null),
                        new GraphMutationTool.SeedGraphInput.SeedEdgeSpec(
                                null, "external-target", "revenue", null, "USER_DEFINED", 1.0,
                                "second edge fails", "SUPPORTS", null)
                ),
                7L,
                true));

        assertEquals(true, result.get("rollbackOnFailure"));
        assertEquals(true, result.get("rolledBack"));
        assertTrue(((List<?>) result.get("errors")).get(0).toString().contains("edge write failed"));
        Map<?, ?> rollback = (Map<?, ?>) result.get("rollback");
        assertEquals(1, rollback.get("edgesRolledBack"));
        assertEquals(2, rollback.get("nodesRolledBack"));
        verify(graphService, never()).createEdgesBatch(anyList());
        verify(graphService).deleteEdge("e-cause");
        verify(graphService).deleteNode("n-cost");
        verify(graphService).deleteNode("n-revenue");
        verify(algorithmService).invalidateCache(7L);
        verify(factStoreProjector, never()).project(anyLong());
    }

    @Test
    void seedTrace_convertsAttributionsEvidenceAndGapsToGraphSeed() {
        GraphNode root = GraphSearchToolTest.createNode("n-root", "Revenue dropped", NodeLevel.CUSTOM);
        GraphNode support = GraphSearchToolTest.createNode("n-support", "Costs rose", NodeLevel.CUSTOM);
        GraphNode gap = GraphSearchToolTest.createNode("n-gap", "Can this be corroborated?", NodeLevel.CUSTOM);
        when(graphService.createNodesBatch(anyList(), eq(7L))).thenReturn(List.of(root, support, gap));
        when(graphService.createEdgesBatch(anyList())).thenReturn(3);
        when(factStoreProjector.project(7L)).thenReturn(9);

        Map<String, Object> rootStep = new LinkedHashMap<>();
        rootStep.put("stepId", "trace.root");
        rootStep.put("kind", "CONCLUSION");
        rootStep.put("conclusion", "Revenue dropped");
        rootStep.put("confidence", 0.8);
        rootStep.put("source", "fusion");

        Map<String, Object> supportStep = new LinkedHashMap<>();
        supportStep.put("stepId", "trace.step.1");
        supportStep.put("kind", "FACT");
        supportStep.put("conclusion", "Costs rose");
        supportStep.put("confidence", 0.9);
        supportStep.put("evidenceRefs", List.of(Map.of(
                "refType", "GRAPH_NODE",
                "nodeId", "evidence-node",
                "raw", "Cost evidence",
                "factSheetId", 7L)));

        Map<String, Object> traceGap = new LinkedHashMap<>();
        traceGap.put("gapType", "SINGLE_SUPPORT");
        traceGap.put("severity", "LOW");
        traceGap.put("question", "Can this be corroborated?");
        traceGap.put("reason", "Only one supporting trace step is exposed.");
        traceGap.put("relatedStepIds", List.of("trace.step.1"));

        var result = tool.seedTrace(new GraphMutationTool.SeedTraceInput(
                List.of(rootStep, supportStep), List.of(traceGap), 7L));

        assertEquals(2, result.get("traceAttributions"));
        assertEquals(1, result.get("traceGaps"));
        assertEquals(3, result.get("traceSeedNodes"));
        assertEquals(3, result.get("traceSeedEdges"));
        assertEquals(9, ((Map<?, ?>) result.get("projectedAtomsByFactSheet")).get(7L));

        ArgumentCaptor<List<KnowledgeGraphService.NodeSpec>> nodeCaptor = ArgumentCaptor.forClass(List.class);
        verify(graphService).createNodesBatch(nodeCaptor.capture(), eq(7L));
        assertEquals(3, nodeCaptor.getValue().size());
        assertEquals("TRACE_ATTRIBUTION", nodeCaptor.getValue().get(0).metadata().get("seedType"));
        assertEquals("TRACE_GAP", nodeCaptor.getValue().get(2).metadata().get("seedType"));

        ArgumentCaptor<List<KnowledgeGraphService.EdgeSpec>> edgeCaptor = ArgumentCaptor.forClass(List.class);
        verify(graphService).createEdgesBatch(edgeCaptor.capture());
        assertEquals(3, edgeCaptor.getValue().size());
        assertEquals("SUPPORTS", edgeCaptor.getValue().get(0).label());
        assertEquals("EVIDENCE_FOR", edgeCaptor.getValue().get(1).label());
        assertEquals("evidence-node", edgeCaptor.getValue().get(1).sourceNodeId());
        assertEquals("NEEDS_CLARIFICATION", edgeCaptor.getValue().get(2).label());
        verify(algorithmService).invalidateCache(7L);
    }

    // ─── graph_reproject ─────────────────────────────────────────────────────────

    @Test
    void reproject_nullFactSheetId_returnsError() {
        var result = tool.reprojectGraph(new GraphMutationTool.ReprojectInput(null, "project"));
        assertEquals("factSheetId is required", result.get("error"));
    }

    @Test
    void reproject_nullInput_returnsError() {
        var result = tool.reprojectGraph(null);
        assertEquals("factSheetId is required", result.get("error"));
    }

    @Test
    void reproject_modeProject_projectsAndDoesNotScheduleCascade() {
        when(factStoreProjector.project(7L)).thenReturn(42);

        var result = tool.reprojectGraph(new GraphMutationTool.ReprojectInput(7L, "project"));

        assertEquals(7L, result.get("factSheetId"));
        assertEquals("project", result.get("mode"));
        assertEquals(42, result.get("atomsProjected"));
        assertEquals(true, result.get("projected"));
        assertEquals(false, result.get("cascadeScheduled"));
        verify(factStoreProjector).project(7L);
        verifyNoInteractions(groundingResetPort);
    }

    @Test
    void reproject_modeFull_projectsAndSchedulesCascade() {
        when(factStoreProjector.project(7L)).thenReturn(55);

        var result = tool.reprojectGraph(new GraphMutationTool.ReprojectInput(7L, "full"));

        assertEquals("full", result.get("mode"));
        assertEquals(55, result.get("atomsProjected"));
        assertEquals(true, result.get("projected"));
        assertEquals(true, result.get("cascadeScheduled"));
        verify(factStoreProjector).project(7L);
        verify(groundingResetPort).schedule(eq(7L), anyString(), anyString());
    }

    @Test
    void reproject_defaultMode_isProject() {
        when(factStoreProjector.project(3L)).thenReturn(10);

        var result = tool.reprojectGraph(new GraphMutationTool.ReprojectInput(3L));

        assertEquals("project", result.get("mode"));
        assertEquals(false, result.get("cascadeScheduled"));
        verifyNoInteractions(groundingResetPort);
    }

    @Test
    void reproject_projectorUnavailable_returnsGracefulMessage() {
        // Tool without factStoreProjector
        GraphMutationTool toolNoProjector = new GraphMutationTool(
                graphService, algorithmService, null, groundingResetPort);

        var result = toolNoProjector.reprojectGraph(new GraphMutationTool.ReprojectInput(7L, "full"));

        assertEquals(false, result.get("projected"));
        assertTrue(result.containsKey("projectionUnavailable"),
                "Should report projectionUnavailable but got: " + result);
        // Cascade can still be scheduled in full mode even if projection is unavailable
        // (best-effort: the full re-ground will re-project internally)
        assertEquals(true, result.get("cascadeScheduled"));
    }

    @Test
    void reproject_cascadeUnavailable_reportsGracefullyAndStillProjects() {
        // Tool without groundingResetPort
        GraphMutationTool toolNoCascade = new GraphMutationTool(
                graphService, algorithmService, factStoreProjector, null);
        when(factStoreProjector.project(7L)).thenReturn(30);

        var result = toolNoCascade.reprojectGraph(new GraphMutationTool.ReprojectInput(7L, "full"));

        assertEquals(true, result.get("projected"));
        assertEquals(30, result.get("atomsProjected"));
        assertEquals(false, result.get("cascadeScheduled"));
        assertTrue(result.containsKey("cascadeUnavailable"),
                "Should report cascadeUnavailable but got: " + result);
    }

    @Test
    void reproject_projectionThrows_returnsErrorFieldAndDoesNotPropagateException() {
        when(factStoreProjector.project(7L)).thenThrow(new RuntimeException("store offline"));

        assertDoesNotThrow(() -> {
            var result = tool.reprojectGraph(new GraphMutationTool.ReprojectInput(7L, "project"));
            assertEquals(false, result.get("projected"));
            assertTrue(result.get("projectionError").toString().contains("store offline"));
        });
    }

    @Test
    void updateEdge_missingId_returnsError() {
        var result = tool.updateEdge(new GraphMutationTool.UpdateEdgeInput("", null, null));
        assertEquals("edgeId is required", result.get("error"));
    }

    @Test
    void deleteEdge_notFound_returnsError() {
        when(graphService.getEdge("missing")).thenReturn(Optional.empty());
        var result = tool.deleteEdge(new GraphMutationTool.DeleteEdgeInput("missing"));
        assertEquals("Edge not found: missing", result.get("error"));
    }

    @Test
    void bulkCreateEdges_emptyList_returnsError() {
        var result = tool.bulkCreateEdges(new GraphMutationTool.BulkCreateEdgesInput(List.of()));
        assertEquals("At least one edge specification is required", result.get("error"));
    }

    @Test
    void mergeNodes_missingCanonical_returnsError() {
        var result = tool.mergeNodes(new GraphMutationTool.MergeNodesInput("", List.of("n2")));
        assertEquals("canonicalNodeId is required", result.get("error"));
    }

    @Test
    void mergeNodes_canonicalNotFound_returnsError() {
        when(graphService.getNode("missing")).thenReturn(Optional.empty());
        var result = tool.mergeNodes(new GraphMutationTool.MergeNodesInput("missing", List.of("n2")));
        assertEquals("Canonical node not found: missing", result.get("error"));
    }
}
