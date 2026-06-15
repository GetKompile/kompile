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

package ai.kompile.knowledgegraph.io;

import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.io.model.ExportResult;
import ai.kompile.knowledgegraph.io.model.ImportResult;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link GraphIOService} — JSON import/export, node upsert logic,
 * edge resolution, format dispatch, and error handling.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class GraphIOServiceTest {

    @Mock
    private KnowledgeGraphService graphService;

    private GraphIOService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new GraphIOService(graphService, mapper);
    }

    private GraphNode graphNode(String nodeId, String externalId, NodeLevel level) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .externalId(externalId)
                .nodeType(level)
                .title(externalId)
                .build();
    }

    // ─── Import — unknown format ───────────────────────────────────────

    @Test
    void importGraph_unknownFormat_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.importGraph("xml", new byte[0], null));
    }

    // ─── Import — JSON with new nodes ──────────────────────────────────

    @Test
    void importGraph_json_createsNewNodes() throws Exception {
        String json = """
            {
              "nodes": [
                {"externalId": "e1", "title": "Apple", "nodeType": "ENTITY"}
              ],
              "edges": []
            }
            """;

        when(graphService.getNodeByExternalId("e1", NodeLevel.ENTITY))
                .thenReturn(Optional.empty());
        when(graphService.createNode(eq(NodeLevel.ENTITY), eq("e1"), eq("Apple"), isNull(), isNull()))
                .thenReturn(graphNode("n1", "e1", NodeLevel.ENTITY));

        ImportResult result = service.importGraph("json", json.getBytes(StandardCharsets.UTF_8), null);

        assertEquals(1, result.nodesCreated());
        assertEquals(0, result.nodesUpdated());
        assertEquals(0, result.errors());
    }

    // ─── Import — JSON updates existing node ───────────────────────────

    @Test
    void importGraph_json_updatesExistingNode() throws Exception {
        String json = """
            {
              "nodes": [
                {"externalId": "e1", "title": "Apple Updated", "nodeType": "ENTITY"}
              ],
              "edges": []
            }
            """;

        GraphNode existing = graphNode("n1", "e1", NodeLevel.ENTITY);
        when(graphService.getNodeByExternalId("e1", NodeLevel.ENTITY))
                .thenReturn(Optional.of(existing));

        ImportResult result = service.importGraph("json", json.getBytes(StandardCharsets.UTF_8), null);

        assertEquals(0, result.nodesCreated());
        assertEquals(1, result.nodesUpdated());
        verify(graphService).updateNode(eq("n1"), eq("Apple Updated"), isNull(), isNull());
    }

    // ─── Import — JSON with edges ──────────────────────────────────────

    @Test
    void importGraph_json_createsEdges() throws Exception {
        String json = """
            {
              "nodes": [
                {"externalId": "e1", "title": "A", "nodeType": "ENTITY"},
                {"externalId": "e2", "title": "B", "nodeType": "ENTITY"}
              ],
              "edges": [
                {"fromExternalId": "e1", "toExternalId": "e2", "edgeType": "SHARED_ENTITY", "weight": 0.8}
              ]
            }
            """;

        when(graphService.getNodeByExternalId("e1", NodeLevel.ENTITY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(graphNode("n1", "e1", NodeLevel.ENTITY)));
        when(graphService.getNodeByExternalId("e2", NodeLevel.ENTITY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(graphNode("n2", "e2", NodeLevel.ENTITY)));
        when(graphService.createNode(any(), eq("e1"), any(), any(), any()))
                .thenReturn(graphNode("n1", "e1", NodeLevel.ENTITY));
        when(graphService.createNode(any(), eq("e2"), any(), any(), any()))
                .thenReturn(graphNode("n2", "e2", NodeLevel.ENTITY));

        ImportResult result = service.importGraph("json", json.getBytes(StandardCharsets.UTF_8), null);

        assertEquals(2, result.nodesCreated());
        assertEquals(1, result.edgesCreated());
        verify(graphService).createEdge("n1", "n2", EdgeType.SHARED_ENTITY, 0.8, null);
    }

    // ─── Import — edge with missing endpoint ───────────────────────────

    @Test
    void importGraph_edgeMissingEndpoint_countedAsError() throws Exception {
        String json = """
            {
              "nodes": [],
              "edges": [
                {"fromExternalId": "missing1", "toExternalId": "missing2", "edgeType": "RELATED"}
              ]
            }
            """;

        // No nodes found for any level
        when(graphService.getNodeByExternalId(anyString(), any()))
                .thenReturn(Optional.empty());

        ImportResult result = service.importGraph("json", json.getBytes(StandardCharsets.UTF_8), null);

        assertEquals(1, result.errors());
        assertEquals(0, result.edgesCreated());
    }

    // ─── Import — null nodeType defaults to ENTITY ─────────────────────

    @Test
    void importGraph_nullNodeType_defaultsToEntity() throws Exception {
        String json = """
            {
              "nodes": [
                {"externalId": "e1", "title": "Untyped"}
              ],
              "edges": []
            }
            """;

        when(graphService.getNodeByExternalId("e1", NodeLevel.ENTITY))
                .thenReturn(Optional.empty());
        when(graphService.createNode(eq(NodeLevel.ENTITY), eq("e1"), eq("Untyped"), isNull(), isNull()))
                .thenReturn(graphNode("n1", "e1", NodeLevel.ENTITY));

        ImportResult result = service.importGraph("json", json.getBytes(StandardCharsets.UTF_8), null);

        assertEquals(1, result.nodesCreated());
        verify(graphService).createNode(eq(NodeLevel.ENTITY), any(), any(), any(), any());
    }

    // ─── Import — null edge weight defaults to 1.0 ─────────────────────

    @Test
    void importGraph_nullEdgeWeight_defaultsToOne() throws Exception {
        String json = """
            {
              "nodes": [
                {"externalId": "e1", "title": "A", "nodeType": "ENTITY"},
                {"externalId": "e2", "title": "B", "nodeType": "ENTITY"}
              ],
              "edges": [
                {"fromExternalId": "e1", "toExternalId": "e2", "edgeType": "SHARED_ENTITY"}
              ]
            }
            """;

        GraphNode n1 = graphNode("n1", "e1", NodeLevel.ENTITY);
        GraphNode n2 = graphNode("n2", "e2", NodeLevel.ENTITY);
        when(graphService.getNodeByExternalId("e1", NodeLevel.ENTITY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(n1));
        when(graphService.getNodeByExternalId("e2", NodeLevel.ENTITY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(n2));
        when(graphService.createNode(any(), eq("e1"), any(), any(), any())).thenReturn(n1);
        when(graphService.createNode(any(), eq("e2"), any(), any(), any())).thenReturn(n2);

        service.importGraph("json", json.getBytes(StandardCharsets.UTF_8), null);

        verify(graphService).createEdge("n1", "n2", EdgeType.SHARED_ENTITY, 1.0, null);
    }

    // ─── Import — unknown edge type defaults to USER_DEFINED ───────────

    @Test
    void importGraph_unknownEdgeType_defaultsToUserDefined() throws Exception {
        String json = """
            {
              "nodes": [
                {"externalId": "e1", "title": "A", "nodeType": "ENTITY"},
                {"externalId": "e2", "title": "B", "nodeType": "ENTITY"}
              ],
              "edges": [
                {"fromExternalId": "e1", "toExternalId": "e2", "edgeType": "CUSTOM_NONSENSE"}
              ]
            }
            """;

        GraphNode n1 = graphNode("n1", "e1", NodeLevel.ENTITY);
        GraphNode n2 = graphNode("n2", "e2", NodeLevel.ENTITY);
        when(graphService.getNodeByExternalId("e1", NodeLevel.ENTITY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(n1));
        when(graphService.getNodeByExternalId("e2", NodeLevel.ENTITY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(n2));
        when(graphService.createNode(any(), eq("e1"), any(), any(), any())).thenReturn(n1);
        when(graphService.createNode(any(), eq("e2"), any(), any(), any())).thenReturn(n2);

        service.importGraph("json", json.getBytes(StandardCharsets.UTF_8), null);

        verify(graphService).createEdge("n1", "n2", EdgeType.USER_DEFINED, 1.0, null);
    }

    // ─── Export — unknown format ───────────────────────────────────────

    @Test
    void exportGraph_unknownFormat_throws() {
        when(graphService.searchNodes(anyString(), any(), anyInt())).thenReturn(List.of());
        assertThrows(IllegalArgumentException.class,
                () -> service.exportGraph("xml", null));
    }

    // ─── Export — JSON empty graph ─────────────────────────────────────

    @Test
    void exportGraph_json_emptyGraph() throws Exception {
        when(graphService.searchNodes(anyString(), any(), anyInt())).thenReturn(List.of());

        ExportResult result = service.exportGraph("json", null);

        assertEquals("json", result.format());
        assertEquals(0, result.nodesExported());
        assertEquals(0, result.edgesExported());
        assertEquals("application/json", result.contentType());
        assertEquals("graph.json", result.suggestedFilename());
        assertNotNull(result.data());
    }

    // ─── Export — JSON with nodes and edges ────────────────────────────

    @Test
    void exportGraph_json_withNodesAndEdges() throws Exception {
        GraphNode n1 = graphNode("n1", "apple", NodeLevel.ENTITY);
        n1.setTitle("Apple");
        GraphNode n2 = graphNode("n2", "google", NodeLevel.ENTITY);
        n2.setTitle("Google");

        GraphEdge edge = new GraphEdge();
        edge.setEdgeId("edge1");
        edge.setSourceNode(n1);
        edge.setTargetNode(n2);
        edge.setEdgeType(EdgeType.SHARED_ENTITY);
        edge.setWeight(0.9);

        when(graphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                .thenReturn(List.of(n1, n2));
        when(graphService.searchNodes(eq(""), argThat(l -> l != NodeLevel.ENTITY), anyInt()))
                .thenReturn(List.of());
        when(graphService.getEdgesForNode("n1")).thenReturn(List.of(edge));
        when(graphService.getEdgesForNode("n2")).thenReturn(List.of(edge));

        ExportResult result = service.exportGraph("json", null);

        assertEquals(2, result.nodesExported());
        assertEquals(1, result.edgesExported()); // deduplicated by edgeId
        assertTrue(result.data().length > 0);
    }

    // ─── Export — CSV format ───────────────────────────────────────────

    @Test
    void exportGraph_csv_returnsZip() throws Exception {
        when(graphService.searchNodes(anyString(), any(), anyInt())).thenReturn(List.of());

        ExportResult result = service.exportGraph("csv", null);

        assertEquals("csv", result.format());
        assertEquals("application/zip", result.contentType());
        assertEquals("graph-csv.zip", result.suggestedFilename());
    }

    // ─── Export — GraphML format ───────────────────────────────────────

    @Test
    void exportGraph_graphml_returnsXml() throws Exception {
        when(graphService.searchNodes(anyString(), any(), anyInt())).thenReturn(List.of());

        ExportResult result = service.exportGraph("graphml", null);

        assertEquals("graphml", result.format());
        assertEquals("application/xml", result.contentType());
        assertEquals("graph.graphml", result.suggestedFilename());
    }

    // ─── Export — Cypher format ────────────────────────────────────────

    @Test
    void exportGraph_cypher_returnsPlaintext() throws Exception {
        when(graphService.searchNodes(anyString(), any(), anyInt())).thenReturn(List.of());

        ExportResult result = service.exportGraph("cypher", null);

        assertEquals("cypher", result.format());
        assertEquals("text/plain", result.contentType());
        assertEquals("graph.cypher", result.suggestedFilename());
    }

    // ─── Export — factSheetId filtering ────────────────────────────────

    @Test
    void exportGraph_factSheetFilter_excludesNonMatching() throws Exception {
        GraphNode match = graphNode("n1", "apple", NodeLevel.ENTITY);
        match.setFactSheetId(42L);

        GraphNode noMatch = graphNode("n2", "google", NodeLevel.ENTITY);
        noMatch.setFactSheetId(99L);

        when(graphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                .thenReturn(List.of(match, noMatch));
        when(graphService.searchNodes(eq(""), argThat(l -> l != NodeLevel.ENTITY), anyInt()))
                .thenReturn(List.of());
        when(graphService.getEdgesForNode("n1")).thenReturn(List.of());

        ExportResult result = service.exportGraph("json", 42L);

        assertEquals(1, result.nodesExported());
    }

    // ─── Import — node error counted and continued ─────────────────────

    @Test
    void importGraph_nodeError_continuesProcessing() throws Exception {
        String json = """
            {
              "nodes": [
                {"externalId": "bad", "title": "Bad", "nodeType": "ENTITY"},
                {"externalId": "good", "title": "Good", "nodeType": "ENTITY"}
              ],
              "edges": []
            }
            """;

        when(graphService.getNodeByExternalId("bad", NodeLevel.ENTITY))
                .thenReturn(Optional.empty());
        when(graphService.createNode(any(), eq("bad"), any(), any(), any()))
                .thenThrow(new RuntimeException("db error"));
        when(graphService.getNodeByExternalId("good", NodeLevel.ENTITY))
                .thenReturn(Optional.empty());
        when(graphService.createNode(any(), eq("good"), any(), any(), any()))
                .thenReturn(graphNode("n2", "good", NodeLevel.ENTITY));

        ImportResult result = service.importGraph("json", json.getBytes(StandardCharsets.UTF_8), null);

        assertEquals(1, result.nodesCreated());
        assertEquals(1, result.errors());
    }
}
