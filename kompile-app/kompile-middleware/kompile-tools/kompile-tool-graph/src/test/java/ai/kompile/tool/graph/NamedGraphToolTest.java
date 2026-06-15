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

import ai.kompile.knowledgegraph.domain.NamedGraph;
import ai.kompile.knowledgegraph.service.NamedGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NamedGraphToolTest {

    @Mock private NamedGraphService namedGraphService;

    private NamedGraphTool tool;

    @BeforeEach
    void setUp() {
        tool = new NamedGraphTool(namedGraphService);
    }

    @Test
    void createNamedGraph_missingName_returnsError() {
        var result = tool.createNamedGraph(
                new NamedGraphTool.CreateNamedGraphInput("", null, null, null, null));
        assertEquals("name is required", result.get("error"));
    }

    @Test
    void createNamedGraph_validInput_returnsGraph() {
        NamedGraph graph = NamedGraph.builder()
                .name("Test Graph")
                .description("A test")
                .ontologyType("domain")
                .build();
        when(namedGraphService.createGraph("Test Graph", "A test", null, null, "domain"))
                .thenReturn(graph);

        var result = tool.createNamedGraph(
                new NamedGraphTool.CreateNamedGraphInput("Test Graph", "A test", null, null, "domain"));

        assertEquals("Test Graph", result.get("name"));
        assertEquals("domain", result.get("ontologyType"));
    }

    @Test
    void updateNamedGraph_missingId_returnsError() {
        var result = tool.updateNamedGraph(
                new NamedGraphTool.UpdateNamedGraphInput("", null, null, null));
        assertEquals("graphId is required", result.get("error"));
    }

    @Test
    void deleteNamedGraph_missingId_returnsError() {
        var result = tool.deleteNamedGraph(new NamedGraphTool.DeleteNamedGraphInput(""));
        assertEquals("graphId is required", result.get("error"));
    }

    @Test
    void deleteNamedGraph_valid_callsService() {
        var result = tool.deleteNamedGraph(new NamedGraphTool.DeleteNamedGraphInput("g1"));
        assertEquals(true, result.get("deleted"));
        verify(namedGraphService).deleteGraph("g1");
    }

    @Test
    void getNamedGraph_notFound_returnsError() {
        when(namedGraphService.getGraph("missing")).thenReturn(Optional.empty());
        var result = tool.getNamedGraph(new NamedGraphTool.GetNamedGraphInput("missing"));
        assertEquals("Named graph not found: missing", result.get("error"));
    }

    @Test
    void listNamedGraphs_rootLevel_returnsRoots() {
        when(namedGraphService.getRootGraphs()).thenReturn(List.of());
        var result = tool.listNamedGraphs(new NamedGraphTool.ListNamedGraphsInput(null));
        assertEquals("root", result.get("level"));
        assertEquals(0, result.get("count"));
    }

    @Test
    void listNamedGraphs_withParent_returnsChildren() {
        when(namedGraphService.getChildGraphs("parent")).thenReturn(List.of());
        var result = tool.listNamedGraphs(new NamedGraphTool.ListNamedGraphsInput("parent"));
        assertEquals("children", result.get("level"));
    }

    @Test
    void graphHierarchy_missingId_returnsError() {
        var result = tool.getGraphHierarchy(new NamedGraphTool.GraphHierarchyInput("", null));
        assertEquals("graphId is required", result.get("error"));
    }

    @Test
    void linkNode_missingInputs_returnsError() {
        var result = tool.linkNode(new NamedGraphTool.LinkNodeInput(null, "g1"));
        assertEquals("Both nodeId and graphId are required", result.get("error"));
    }

    @Test
    void linkNode_valid_callsService() {
        var result = tool.linkNode(new NamedGraphTool.LinkNodeInput("n1", "g1"));
        assertEquals(true, result.get("linked"));
        verify(namedGraphService).linkNodeToGraph("n1", "g1");
    }

    @Test
    void unlinkNode_valid_callsService() {
        var result = tool.unlinkNode(new NamedGraphTool.UnlinkNodeInput("n1", "g1"));
        assertEquals(true, result.get("unlinked"));
        verify(namedGraphService).unlinkNodeFromGraph("n1", "g1");
    }

    @Test
    void moveGraph_missingId_returnsError() {
        var result = tool.moveGraph(new NamedGraphTool.MoveGraphInput("", null));
        assertEquals("graphId is required", result.get("error"));
    }

    @Test
    void graphStats_missingId_returnsError() {
        var result = tool.getGraphStats(new NamedGraphTool.GraphStatsInput(""));
        assertEquals("graphId is required", result.get("error"));
    }
}
