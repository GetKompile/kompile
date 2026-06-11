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

package ai.kompile.knowledgegraph.reporting;

import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.reporting.Reporter;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.io.OutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link GraphReportController} — verifies report type dispatch,
 * graph model building, factSheetId filtering, and edge deduplication.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class GraphReportControllerTest {

    @Mock
    private Reporter reporter;

    @Mock
    private KnowledgeGraphService graphService;

    private GraphReportController controller;

    @BeforeEach
    void setUp() {
        controller = new GraphReportController(reporter, graphService);
        // By default, return empty for all node levels
        when(graphService.searchNodes(anyString(), any(NodeLevel.class), anyInt()))
                .thenReturn(List.of());
    }

    // ─── Report type dispatch ────────────────────────────────────────────

    @Test
    void summaryReportByDefault() {
        doAnswer(inv -> {
            OutputStream os = inv.getArgument(1);
            os.write("# Graph Summary".getBytes());
            return null;
        }).when(reporter).generateGraphSummaryReport(any(Graph.class), any(OutputStream.class));

        ResponseEntity<String> response = controller.generateReport("summary", null);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().contains("Graph Summary"));
        verify(reporter).generateGraphSummaryReport(any(Graph.class), any(OutputStream.class));
        verify(reporter, never()).generateCommunityReports(any(), any());
        verify(reporter, never()).generateEntityRelationshipReport(any(), any());
    }

    @Test
    void communitiesReportType() {
        doAnswer(inv -> {
            OutputStream os = inv.getArgument(1);
            os.write("Communities".getBytes());
            return null;
        }).when(reporter).generateCommunityReports(any(Graph.class), any(OutputStream.class));

        ResponseEntity<String> response = controller.generateReport("communities", null);

        assertEquals(200, response.getStatusCode().value());
        verify(reporter).generateCommunityReports(any(Graph.class), any(OutputStream.class));
    }

    @Test
    void entitiesReportType() {
        doAnswer(inv -> {
            OutputStream os = inv.getArgument(1);
            os.write("Entities".getBytes());
            return null;
        }).when(reporter).generateEntityRelationshipReport(any(Graph.class), any(OutputStream.class));

        ResponseEntity<String> response = controller.generateReport("entities", null);

        assertEquals(200, response.getStatusCode().value());
        verify(reporter).generateEntityRelationshipReport(any(Graph.class), any(OutputStream.class));
    }

    @Test
    void unknownTypeFallsToSummary() {
        doAnswer(inv -> {
            OutputStream os = inv.getArgument(1);
            os.write("Summary fallback".getBytes());
            return null;
        }).when(reporter).generateGraphSummaryReport(any(Graph.class), any(OutputStream.class));

        ResponseEntity<String> response = controller.generateReport("unknown_type", null);

        assertEquals(200, response.getStatusCode().value());
        verify(reporter).generateGraphSummaryReport(any(Graph.class), any(OutputStream.class));
    }

    // ─── Graph model building ────────────────────────────────────────────

    @Test
    void buildsGraphFromAllNodeLevels() {
        GraphNode entityNode = GraphNode.builder()
                .nodeId("n1").title("Person A").nodeType(NodeLevel.ENTITY)
                .build();
        // Return node only for ENTITY level
        when(graphService.searchNodes("", NodeLevel.ENTITY, Integer.MAX_VALUE))
                .thenReturn(List.of(entityNode));
        when(graphService.getEdgesForNode("n1")).thenReturn(List.of());

        doAnswer(inv -> {
            Graph graph = inv.getArgument(0);
            OutputStream os = inv.getArgument(1);
            os.write(("nodes=" + graph.getEntities().size()).getBytes());
            return null;
        }).when(reporter).generateGraphSummaryReport(any(Graph.class), any(OutputStream.class));

        ResponseEntity<String> response = controller.generateReport("summary", null);

        assertTrue(response.getBody().contains("nodes=1"));
    }

    @Test
    void buildsGraphWithEdges() {
        GraphNode n1 = GraphNode.builder().nodeId("n1").title("A").nodeType(NodeLevel.ENTITY).build();
        GraphNode n2 = GraphNode.builder().nodeId("n2").title("B").nodeType(NodeLevel.ENTITY).build();
        GraphEdge edge = GraphEdge.builder()
                .edgeId("e1").sourceNode(n1).targetNode(n2)
                .edgeType(EdgeType.SHARED_ENTITY).weight(0.9).build();

        when(graphService.searchNodes("", NodeLevel.ENTITY, Integer.MAX_VALUE))
                .thenReturn(List.of(n1, n2));
        when(graphService.getEdgesForNode("n1")).thenReturn(List.of(edge));
        when(graphService.getEdgesForNode("n2")).thenReturn(List.of(edge)); // same edge from both sides

        doAnswer(inv -> {
            Graph graph = inv.getArgument(0);
            OutputStream os = inv.getArgument(1);
            // Edge should be deduplicated — appears only once despite returned from both nodes
            os.write(("edges=" + graph.getRelationships().size()).getBytes());
            return null;
        }).when(reporter).generateGraphSummaryReport(any(Graph.class), any(OutputStream.class));

        ResponseEntity<String> response = controller.generateReport("summary", null);

        assertTrue(response.getBody().contains("edges=1"));
    }

    // ─── FactSheetId filtering ───────────────────────────────────────────

    @Test
    void filtersNodesByFactSheetId() {
        GraphNode match = GraphNode.builder()
                .nodeId("n1").title("Match").nodeType(NodeLevel.ENTITY).factSheetId(42L).build();
        GraphNode noMatch = GraphNode.builder()
                .nodeId("n2").title("NoMatch").nodeType(NodeLevel.ENTITY).factSheetId(99L).build();

        when(graphService.searchNodes("", NodeLevel.ENTITY, Integer.MAX_VALUE))
                .thenReturn(List.of(match, noMatch));
        when(graphService.getEdgesForNode("n1")).thenReturn(List.of());

        doAnswer(inv -> {
            Graph graph = inv.getArgument(0);
            OutputStream os = inv.getArgument(1);
            os.write(("nodes=" + graph.getEntities().size()).getBytes());
            return null;
        }).when(reporter).generateGraphSummaryReport(any(Graph.class), any(OutputStream.class));

        ResponseEntity<String> response = controller.generateReport("summary", 42L);

        assertTrue(response.getBody().contains("nodes=1"));
    }

    // ─── Empty graph ─────────────────────────────────────────────────────

    @Test
    void handlesEmptyGraph() {
        doAnswer(inv -> {
            OutputStream os = inv.getArgument(1);
            os.write("Empty graph".getBytes());
            return null;
        }).when(reporter).generateGraphSummaryReport(any(Graph.class), any(OutputStream.class));

        ResponseEntity<String> response = controller.generateReport("summary", null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("Empty graph", response.getBody());
    }
}
