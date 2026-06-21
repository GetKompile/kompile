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

import ai.kompile.knowledgegraph.domain.EdgeProvenance;
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
        // [H-2] GraphIOService now calls createEdgeWithMetadata so confidence/provenance are wired through.
        verify(graphService).createEdgeWithMetadata(
                eq("n1"), eq("n2"), eq(EdgeType.SHARED_ENTITY),
                eq(0.8), isNull(), isNull(), isNull(), isNull(), isNull());
    }

    // ─── Import — JSON edge with extended fields (M-1/M-3/M-4/M-7) ──────

    @Test
    void importGraph_json_edgeExtendedFields_carriedThroughMetaJsonAndFactSheetId() throws Exception {
        String json = """
            {
              "nodes": [
                {"externalId": "e1", "title": "A", "nodeType": "ENTITY"},
                {"externalId": "e2", "title": "B", "nodeType": "ENTITY"}
              ],
              "edges": [
                {"fromExternalId": "e1", "toExternalId": "e2", "edgeType": "SHARED_ENTITY",
                 "weight": 0.8, "factSheetId": 42, "bidirectional": true,
                 "sharedEntitiesJson": "[\\"Acme\\"]", "similarityScore": 0.91, "label": "co-occurs"}
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

        service.importGraph("json", json.getBytes(StandardCharsets.UTF_8), null);

        org.mockito.ArgumentCaptor<String> metaJson = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<Long> fsId = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(graphService).createEdgeWithMetadata(
                eq("n1"), eq("n2"), eq(EdgeType.SHARED_ENTITY), eq(0.8),
                any(), any(), metaJson.capture(), isNull(), fsId.capture());

        assertEquals(42L, fsId.getValue(), "[M-1] per-edge factSheetId must be passed through on import");
        String mj = metaJson.getValue();
        assertNotNull(mj, "extended edge fields must be carried in the metadata JSON");
        assertTrue(mj.contains("bidirectional"), "[M-3] bidirectional carried");
        assertTrue(mj.contains("sharedEntitiesJson"), "[M-4] shared-entity payload carried");
        assertTrue(mj.contains("similarityScore"), "[M-4] similarity score carried");
        assertTrue(mj.contains("co-occurs"), "[M-7] explicit label carried");
    }

    // ─── [L-8] Schema-version envelope ─────────────────────────────────

    @Test
    void portableGraph_schemaVersion_stampedOnSerializeAndNullForPreVersioningFiles() throws Exception {
        // Freshly-collected graphs are stamped with the current version.
        var stamped = new ai.kompile.knowledgegraph.io.format.PortableGraph(
                java.util.List.of(), java.util.List.of());
        String json = mapper.writeValueAsString(stamped);
        assertTrue(json.contains("\"schemaVersion\":\"1\""), "[L-8] export must stamp the schema version");

        // A pre-versioning file (no schemaVersion key) deserializes with null — treated as compatible.
        var old = mapper.readValue("{\"nodes\":[],\"edges\":[]}",
                ai.kompile.knowledgegraph.io.format.PortableGraph.class);
        assertNull(old.schemaVersion());
    }

    // ─── [L-7] Streaming JSON export ───────────────────────────────────

    @Test
    void exportGraphStreaming_json_streamsAllNodesAndEdges() throws Exception {
        GraphNode n1 = graphNode("u1", "e1", NodeLevel.ENTITY);
        GraphNode n2 = graphNode("u2", "e2", NodeLevel.ENTITY);
        when(graphService.getNodesByType(any())).thenReturn(List.of());
        when(graphService.getNodesByType(NodeLevel.ENTITY)).thenReturn(List.of(n1, n2));
        GraphEdge edge = GraphEdge.builder()
                .edgeId("ed1").edgeType(EdgeType.SHARED_ENTITY).weight(0.5)
                .sourceNode(n1).targetNode(n2).build();
        when(graphService.getEdgesForNode("u1")).thenReturn(List.of(edge));
        when(graphService.getEdgesForNode("u2")).thenReturn(List.of());

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        service.exportGraphStreaming("json", null, out);

        com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(out.toByteArray());
        assertEquals("1", root.get("schemaVersion").asText(), "[L-8] schemaVersion stamped in the streamed output");
        assertEquals(2, root.get("nodes").size(), "[L-7] all nodes streamed");
        assertEquals(1, root.get("edges").size(), "[L-7] all edges streamed (deduped)");
        assertEquals("e1", root.get("nodes").get(0).get("externalId").asText());
        assertEquals("e1", root.get("edges").get(0).get("fromExternalId").asText());
        assertEquals("e2", root.get("edges").get(0).get("toExternalId").asText());
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

        // [H-2] createEdgeWithMetadata carries weight=1.0 default; null confidence/provenance.
        verify(graphService).createEdgeWithMetadata(
                eq("n1"), eq("n2"), eq(EdgeType.SHARED_ENTITY),
                eq(1.0), isNull(), isNull(), isNull(), isNull(), isNull());
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

        // [H-2] createEdgeWithMetadata with USER_DEFINED, weight=1.0 default.
        verify(graphService).createEdgeWithMetadata(
                eq("n1"), eq("n2"), eq(EdgeType.USER_DEFINED),
                eq(1.0), isNull(), isNull(), isNull(), isNull(), isNull());
    }

    // ─── Import — semantic relationType is preserved (round-trip) ───────

    @Test
    void importGraph_edgeRelationType_isPassedToCreateEdge() throws Exception {
        String json = """
            {
              "nodes": [
                {"externalId": "e1", "title": "Alice", "nodeType": "ENTITY"},
                {"externalId": "e2", "title": "Acme", "nodeType": "ENTITY"}
              ],
              "edges": [
                {"fromExternalId": "e1", "toExternalId": "e2", "edgeType": "USER_DEFINED",
                 "relationType": "WORKS_AT", "weight": 0.9}
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

        // [H-2] createEdgeWithMetadata carries relationType="WORKS_AT" in the label parameter.
        verify(graphService).createEdgeWithMetadata(
                eq("n1"), eq("n2"), eq(EdgeType.USER_DEFINED),
                eq(0.9), eq("WORKS_AT"), isNull(), isNull(), isNull(), isNull());
    }

    // ─── Export — unknown format ───────────────────────────────────────

    @Test
    void exportGraph_unknownFormat_throws() {
        when(graphService.getNodesByType(any())).thenReturn(List.of());
        assertThrows(IllegalArgumentException.class,
                () -> service.exportGraph("xml", null));
    }

    // ─── Export — JSON empty graph ─────────────────────────────────────

    @Test
    void exportGraph_json_emptyGraph() throws Exception {
        when(graphService.getNodesByType(any())).thenReturn(List.of());

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

        when(graphService.getNodesByType(eq(NodeLevel.ENTITY)))
                .thenReturn(List.of(n1, n2));
        when(graphService.getNodesByType(argThat(l -> l != NodeLevel.ENTITY)))
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
        when(graphService.getNodesByType(any())).thenReturn(List.of());

        ExportResult result = service.exportGraph("csv", null);

        assertEquals("csv", result.format());
        assertEquals("application/zip", result.contentType());
        assertEquals("graph-csv.zip", result.suggestedFilename());
    }

    // ─── Export — GraphML format ───────────────────────────────────────

    @Test
    void exportGraph_graphml_returnsXml() throws Exception {
        when(graphService.getNodesByType(any())).thenReturn(List.of());

        ExportResult result = service.exportGraph("graphml", null);

        assertEquals("graphml", result.format());
        assertEquals("application/xml", result.contentType());
        assertEquals("graph.graphml", result.suggestedFilename());
    }

    // ─── Export — Cypher format ────────────────────────────────────────

    @Test
    void exportGraph_cypher_returnsPlaintext() throws Exception {
        when(graphService.getNodesByType(any())).thenReturn(List.of());

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

        when(graphService.getNodesByType(eq(NodeLevel.ENTITY)))
                .thenReturn(List.of(match, noMatch));
        when(graphService.getNodesByType(argThat(l -> l != NodeLevel.ENTITY)))
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

    // ─── H-1: confidence/namedGraphId/occurredAt preserved on new node import ──

    @Test
    void importGraph_nodeQualityFields_preservedOnCreate() throws Exception {
        String json = """
            {
              "nodes": [
                {
                  "externalId": "e1",
                  "title": "Apple",
                  "nodeType": "ENTITY",
                  "confidence": 0.85,
                  "namedGraphId": "graph-A",
                  "occurredAt": "2025-06-21T10:00:00"
                }
              ],
              "edges": []
            }
            """;

        when(graphService.getNodeByExternalId("e1", NodeLevel.ENTITY))
                .thenReturn(Optional.empty());
        when(graphService.createNode(eq(NodeLevel.ENTITY), eq("e1"), eq("Apple"), isNull(), any()))
                .thenReturn(graphNode("n1", "e1", NodeLevel.ENTITY));

        service.importGraph("json", json.getBytes(StandardCharsets.UTF_8), null);

        // [H-1] confidence, namedGraphId, occurredAt must be merged into metadata
        // and passed to createNode so the matrix/vector store persists them.
        verify(graphService).createNode(
                eq(NodeLevel.ENTITY), eq("e1"), eq("Apple"), isNull(),
                argThat(meta -> meta != null
                        && Double.valueOf(0.85).equals(meta.get("confidence"))
                        && "graph-A".equals(meta.get("namedGraphId"))
                        && "2025-06-21T10:00:00".equals(meta.get("occurredAt"))));
    }

    // ─── H-1: quality fields merged alongside existing metadata on new node ──

    @Test
    void importGraph_nodeQualityFields_mergedWithExistingMetadata() throws Exception {
        String json = """
            {
              "nodes": [
                {
                  "externalId": "e1",
                  "title": "Apple",
                  "nodeType": "ENTITY",
                  "metadata": {"source": "wiki"},
                  "confidence": 0.9,
                  "namedGraphId": "ng-1"
                }
              ],
              "edges": []
            }
            """;

        when(graphService.getNodeByExternalId("e1", NodeLevel.ENTITY))
                .thenReturn(Optional.empty());
        when(graphService.createNode(eq(NodeLevel.ENTITY), eq("e1"), any(), any(), any()))
                .thenReturn(graphNode("n1", "e1", NodeLevel.ENTITY));

        service.importGraph("json", json.getBytes(StandardCharsets.UTF_8), null);

        // Both the original metadata key ("source") and quality fields must be present.
        verify(graphService).createNode(
                eq(NodeLevel.ENTITY), eq("e1"), any(), any(),
                argThat(meta -> meta != null
                        && "wiki".equals(meta.get("source"))
                        && Double.valueOf(0.9).equals(meta.get("confidence"))
                        && "ng-1".equals(meta.get("namedGraphId"))));
    }

    // ─── H-1: quality fields also merged on update (existing node path) ──

    @Test
    void importGraph_nodeQualityFields_preservedOnUpdate() throws Exception {
        String json = """
            {
              "nodes": [
                {
                  "externalId": "e1",
                  "title": "Apple Updated",
                  "nodeType": "ENTITY",
                  "confidence": 0.75,
                  "namedGraphId": "graph-B"
                }
              ],
              "edges": []
            }
            """;

        GraphNode existing = graphNode("n1", "e1", NodeLevel.ENTITY);
        when(graphService.getNodeByExternalId("e1", NodeLevel.ENTITY))
                .thenReturn(Optional.of(existing));
        when(graphService.updateNode(any(), any(), any(), any())).thenReturn(existing);

        service.importGraph("json", json.getBytes(StandardCharsets.UTF_8), null);

        // [H-1] updateNode must receive metadata containing the quality fields.
        verify(graphService).updateNode(
                eq("n1"), eq("Apple Updated"), isNull(),
                argThat(meta -> meta != null
                        && Double.valueOf(0.75).equals(meta.get("confidence"))
                        && "graph-B".equals(meta.get("namedGraphId"))));
    }

    // ─── H-2: edge confidence/provenance preserved on import ───────────────

    @Test
    void importGraph_edgeQualityFields_preservedOnCreate() throws Exception {
        String json = """
            {
              "nodes": [
                {"externalId": "e1", "title": "A", "nodeType": "ENTITY"},
                {"externalId": "e2", "title": "B", "nodeType": "ENTITY"}
              ],
              "edges": [
                {
                  "fromExternalId": "e1",
                  "toExternalId": "e2",
                  "edgeType": "USER_DEFINED",
                  "weight": 0.7,
                  "confidence": 0.65,
                  "provenance": "EXTRACTED"
                }
              ]
            }
            """;

        GraphNode n1 = graphNode("n1", "e1", NodeLevel.ENTITY);
        GraphNode n2 = graphNode("n2", "e2", NodeLevel.ENTITY);
        when(graphService.getNodeByExternalId("e1", NodeLevel.ENTITY))
                .thenReturn(Optional.empty()).thenReturn(Optional.of(n1));
        when(graphService.getNodeByExternalId("e2", NodeLevel.ENTITY))
                .thenReturn(Optional.empty()).thenReturn(Optional.of(n2));
        when(graphService.createNode(any(), eq("e1"), any(), any(), any())).thenReturn(n1);
        when(graphService.createNode(any(), eq("e2"), any(), any(), any())).thenReturn(n2);

        service.importGraph("json", json.getBytes(StandardCharsets.UTF_8), null);

        // [H-2] createEdgeWithMetadata must be called; metaJson must carry confidence+provenance.
        verify(graphService).createEdgeWithMetadata(
                eq("n1"), eq("n2"), eq(EdgeType.USER_DEFINED),
                eq(0.7), isNull(), isNull(),
                argThat(meta -> meta != null
                        && meta.contains("\"confidence\"")
                        && meta.contains("0.65")
                        && meta.contains("\"provenance\"")
                        && meta.contains("EXTRACTED")),
                isNull(), isNull());
    }

    // ─── H-3: stale nodes excluded from export ──────────────────────────────

    @Test
    void exportGraph_excludesStaleNodes_jpaStaleFlag() throws Exception {
        GraphNode active = graphNode("n1", "apple", NodeLevel.ENTITY);

        GraphNode stale = graphNode("n2", "stale-entity", NodeLevel.ENTITY);
        stale.setStale(true);

        when(graphService.getNodesByType(eq(NodeLevel.ENTITY)))
                .thenReturn(List.of(active, stale));
        when(graphService.getNodesByType(argThat(l -> l != NodeLevel.ENTITY)))
                .thenReturn(List.of());
        when(graphService.getEdgesForNode("n1")).thenReturn(List.of());

        ExportResult result = service.exportGraph("json", null);

        // [H-3] Stale node should be excluded — only 1 node in the export.
        assertEquals(1, result.nodesExported(), "Stale node must not be exported");
    }

    @Test
    void exportGraph_excludesStaleNodes_metadataFlag() throws Exception {
        // Matrix/vector-store path: staleness in metadata as "_stale=true"
        GraphNode active = graphNode("n1", "apple", NodeLevel.ENTITY);

        GraphNode matrixStale = graphNode("n2", "matrix-stale", NodeLevel.ENTITY);
        matrixStale.setMetadataJson("{\"_stale\": true}");

        when(graphService.getNodesByType(eq(NodeLevel.ENTITY)))
                .thenReturn(List.of(active, matrixStale));
        when(graphService.getNodesByType(argThat(l -> l != NodeLevel.ENTITY)))
                .thenReturn(List.of());
        when(graphService.getEdgesForNode("n1")).thenReturn(List.of());

        ExportResult result = service.exportGraph("json", null);

        // [H-3] Matrix-path stale node (metadata "_stale"=true) must not be exported.
        assertEquals(1, result.nodesExported(), "Matrix-path stale node must not be exported");
    }

    // ─── Per-named-graph export filter ─────────────────────────────────────

    @Test
    void exportGraph_namedGraphIdFilter_excludesNonMatchingNodes() throws Exception {
        // JPA path: namedGraphId set on the node directly.
        GraphNode matchNode = graphNode("n1", "entity-in-ng", NodeLevel.ENTITY);
        matchNode.setNamedGraphId("ng-target");

        GraphNode noMatchNode = graphNode("n2", "entity-in-other-ng", NodeLevel.ENTITY);
        noMatchNode.setNamedGraphId("ng-other");

        GraphNode unscoped = graphNode("n3", "entity-global", NodeLevel.ENTITY);
        // namedGraphId == null → also excluded

        when(graphService.getNodesByType(eq(NodeLevel.ENTITY)))
                .thenReturn(List.of(matchNode, noMatchNode, unscoped));
        when(graphService.getNodesByType(argThat(l -> l != NodeLevel.ENTITY)))
                .thenReturn(List.of());
        when(graphService.getEdgesForNode("n1")).thenReturn(List.of());

        ExportResult result = service.exportGraph("json", null, "ng-target");

        assertEquals(1, result.nodesExported(),
                "Only the node with namedGraphId='ng-target' must be exported");
    }

    @Test
    void exportGraph_namedGraphIdFilter_matchesMetadataPath() throws Exception {
        // Matrix/vector-store path: namedGraphId surfaced via metadataJson key.
        GraphNode matchNode = graphNode("n1", "matrix-ng-entity", NodeLevel.ENTITY);
        matchNode.setMetadataJson("{\"namedGraphId\": \"ng-matrix\"}");

        GraphNode noMatchNode = graphNode("n2", "other-entity", NodeLevel.ENTITY);
        noMatchNode.setMetadataJson("{\"namedGraphId\": \"ng-other\"}");

        when(graphService.getNodesByType(eq(NodeLevel.ENTITY)))
                .thenReturn(List.of(matchNode, noMatchNode));
        when(graphService.getNodesByType(argThat(l -> l != NodeLevel.ENTITY)))
                .thenReturn(List.of());
        when(graphService.getEdgesForNode("n1")).thenReturn(List.of());

        ExportResult result = service.exportGraph("json", null, "ng-matrix");

        assertEquals(1, result.nodesExported(),
                "Node with namedGraphId in metadata must be matched by the filter");
    }

    @Test
    void exportGraph_namedGraphIdNull_fallsBackToFactSheetExport() throws Exception {
        // When namedGraphId is null the three-arg overload must delegate to the two-arg one.
        GraphNode n = graphNode("n1", "apple", NodeLevel.ENTITY);
        n.setFactSheetId(7L);

        when(graphService.getNodesByType(eq(NodeLevel.ENTITY))).thenReturn(List.of(n));
        when(graphService.getNodesByType(argThat(l -> l != NodeLevel.ENTITY))).thenReturn(List.of());
        when(graphService.getEdgesForNode("n1")).thenReturn(List.of());

        ExportResult result = service.exportGraph("json", 7L, null);

        // Should behave exactly like exportGraph("json", 7L) — i.e. include the node.
        assertEquals(1, result.nodesExported(),
                "null namedGraphId must fall back to the two-arg (factSheetId-only) overload");
    }

    // ─── RDF import round-trip via GraphIOService ───────────────────────────

    @Test
    void importGraph_ntriples_recognisedFormat() {
        // N-Triples with no nodes — just verify the format is accepted (no exception)
        assertDoesNotThrow(() ->
                service.importGraph("ntriples", "<https://kompile.ai/kg/node/x> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <https://kompile.ai/kg/class/ENTITY> .\n".getBytes(java.nio.charset.StandardCharsets.UTF_8), null),
                "ntriples format must be accepted by importGraph");
    }

    @Test
    void importGraph_nt_aliasRecognised() {
        assertDoesNotThrow(() ->
                service.importGraph("nt", "".getBytes(java.nio.charset.StandardCharsets.UTF_8), null),
                "nt alias must be accepted by importGraph");
    }

    @Test
    void importGraph_turtle_recognisedFormat() {
        assertDoesNotThrow(() ->
                service.importGraph("turtle", "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n".getBytes(java.nio.charset.StandardCharsets.UTF_8), null),
                "turtle format must be accepted by importGraph");
    }

    @Test
    void importGraph_ttl_aliasRecognised() {
        assertDoesNotThrow(() ->
                service.importGraph("ttl", "".getBytes(java.nio.charset.StandardCharsets.UTF_8), null),
                "ttl alias must be accepted by importGraph");
    }
}
