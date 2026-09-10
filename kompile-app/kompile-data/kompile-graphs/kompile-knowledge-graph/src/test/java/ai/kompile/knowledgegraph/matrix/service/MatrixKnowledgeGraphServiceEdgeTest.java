/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.matrix.service;

import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.matrix.model.MatrixGraphNode;
import ai.kompile.knowledgegraph.matrix.store.MatrixGraphStore;
import ai.kompile.knowledgegraph.service.BoundedKnowledgeGraphReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * Verifies the matrix store restores the extended edge fields carried in the typed
 * {@code EdgeMetadata} (metaJson) on import — bidirectional (M-3), factSheetId (M-1),
 * shared-entity payload + similarity (M-4), and explicit label (M-7).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MatrixKnowledgeGraphServiceEdgeTest {

    private static final String DEFAULT_GRAPH_ID = "default-knowledge-graph";

    @Mock
    private MatrixGraphStore graphStore;

    private MatrixKnowledgeGraphService service;

    @BeforeEach
    void setUp() {
        service = new MatrixKnowledgeGraphService(graphStore, new ObjectMapper());
    }

    @Test
    void createEdgeWithMetadata_honorsImportedBidirectional_overEdgeTypeDefault() {
        // A HIERARCHICAL edge would default to bidirectional=false; the imported metaJson says true,
        // and that must win (M-3) — otherwise a bidirectional edge becomes directional after a clone.
        String metaJson = "{\"bidirectional\":true,"
                + "\"sharedEntitiesJson\":\"[\\\"Acme\\\"]\","
                + "\"similarityScore\":0.91,\"label\":\"co-occurs\"}";

        GraphEdge edge = service.createEdgeWithMetadata("a", "b", EdgeType.HIERARCHICAL, 1.0,
                null, "desc", metaJson, null, 42L);

        ArgumentCaptor<Boolean> bidi = ArgumentCaptor.forClass(Boolean.class);
        // factSheetId 42 → segmented graph "factsheet_42"
        verify(graphStore).addEdge(eq("factsheet_42"), eq("a"), eq("b"), eq(1.0), anyString(),
                bidi.capture(), any(), any(), any());
        assertTrue(bidi.getValue(), "[M-3] imported bidirectional=true must override the HIERARCHICAL default");
        assertTrue(edge.getBidirectional());
        assertEquals(42L, edge.getFactSheetId(), "[M-1] factSheetId surfaced on the edge");
        assertEquals("[\"Acme\"]", edge.getSharedEntitiesJson(), "[M-4] shared-entity payload surfaced");
        assertEquals(0.91, edge.getSimilarityScore(), "[M-4] similarity score surfaced");
        assertEquals("co-occurs", edge.getLabel(), "[M-7] explicit label surfaced");
    }

    @Test
    void createEdgeWithMetadata_defaultsBidirectionalFromEdgeType_whenNotImported() {
        GraphEdge edge = service.createEdgeWithMetadata("a", "b", EdgeType.HIERARCHICAL, 1.0,
                null, "desc", null, null, null);

        verify(graphStore).addEdge(eq(DEFAULT_GRAPH_ID), eq("a"), eq("b"), eq(1.0), anyString(),
                eq(false), any(), any(), any());
        assertFalse(edge.getBidirectional(), "HIERARCHICAL with no imported flag stays directional");
    }

    @Test
    void createEdgeWithMetadata_surfacesTypedProvenanceFromMetaJson() {
        GraphEdge edge = service.createEdgeWithMetadata("a", "b", EdgeType.HIERARCHICAL, 1.0,
                null, "desc", "{\"provenanceType\":\"INFERRED\"}", null, null);
        assertEquals(EdgeProvenance.INFERRED, edge.getProvenanceType(),
                "[M-10] typed provenance classification restored from metaJson");
    }

    @Test
    @SuppressWarnings("unchecked")
    void createEdgeWithMetadataPersistsBoundedInferenceProvenanceInTheMatrixMetadataBag() {
        String metaJson = "{\"provenance\":\"inference:run-7\","
                + "\"provenanceType\":\"INFERRED\","
                + "\"metadata\":{\"inferenceRunId\":\"run-7\","
                + "\"inferenceVersion\":\"fol-v2\","
                + "\"supportingRuleIds\":[\"rule:control\"]}}";

        GraphEdge edge = service.createEdgeWithMetadata("a", "b", EdgeType.HIERARCHICAL, 1.0,
                null, "desc", metaJson, null, null);

        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(graphStore).mergeEdgeMetadata(eq(DEFAULT_GRAPH_ID), eq("a"), eq("b"),
                anyString(), metadata.capture());
        assertEquals("INFERRED", metadata.getValue().get("provenanceType"));
        assertEquals("inference:run-7", metadata.getValue().get("provenance"));
        assertEquals("run-7", metadata.getValue().get("inferenceRunId"));
        assertEquals(List.of("rule:control"), metadata.getValue().get("supportingRuleIds"));
        assertEquals(metadata.getValue(), edge.getMetadata());
    }

    @Test
    void createEdgeWithMetadata_usesEdgeProvenanceParam_whenMetaJsonHasNoProvenanceType() {
        GraphEdge edge = service.createEdgeWithMetadata("a", "b", EdgeType.HIERARCHICAL, 1.0,
                null, "desc", null, EdgeProvenance.EXTRACTED, null);
        assertEquals(EdgeProvenance.EXTRACTED, edge.getProvenanceType(),
                "[M-10] typed provenance falls back to the EdgeProvenance param");
    }

    @Test
    void coldEdgeLookupUsesPersistedGraphAndPagedStoreWithoutLoadingMatrix() {
        MatrixGraphNode source = MatrixGraphNode.builder()
                .nodeId("a").nodeType("ENTITY").title("A").build();
        when(graphStore.getLoadedGraphIds()).thenReturn(Set.of());
        when(graphStore.listGraphs()).thenReturn(List.of("factsheet_42"));
        when(graphStore.getNode("factsheet_42", "a")).thenReturn(Optional.of(source));
        when(graphStore.getNode("factsheet_42", "b")).thenReturn(Optional.empty());
        when(graphStore.scanIncidentEdges("factsheet_42", "a",
                MatrixGraphStore.EdgeDirection.BOTH, 100_000)).thenReturn(
                new MatrixGraphStore.IncidentEdges(List.of(new MatrixGraphStore.StoredEdge(
                        "a", "b", "CALLS", 1.0, false, "CALLS",
                        0.9, null, Map.of())), false));

        List<GraphEdge> edges = service.getEdgesForNodeInFactSheet("a", 42L);

        assertEquals(1, edges.size());
        assertEquals("CALLS", edges.get(0).getRelationType());
        verify(graphStore, never()).loadGraph(anyString());
    }

    @Test
    void directedTargetLookupReturnsIncomingEdge() {
        when(graphStore.scanIncidentEdges("factsheet_42", "b",
                MatrixGraphStore.EdgeDirection.BOTH, 100_000)).thenReturn(
                new MatrixGraphStore.IncidentEdges(List.of(new MatrixGraphStore.StoredEdge(
                        "a", "b", "CALLS", 1.0, false, "CALLS",
                        0.9, null, Map.of())), false));

        List<GraphEdge> edges = service.getEdgesForNodeInFactSheet("b", 42L);

        assertEquals(1, edges.size());
        assertEquals("a", edges.get(0).getSourceNodeId());
        assertEquals("b", edges.get(0).getTargetNodeId());
    }

    @Test
    void boundedIncidentLookupHonorsDirectionAndReportsTruncation() {
        when(graphStore.scanIncidentEdges("factsheet_42", "b",
                MatrixGraphStore.EdgeDirection.INCOMING, 1)).thenReturn(
                new MatrixGraphStore.IncidentEdges(List.of(
                        new MatrixGraphStore.StoredEdge("a", "b", "CALLS", 1.0, false,
                                "CALLS", 0.9, null, Map.of())), true));
        when(graphStore.scanIncidentEdges("factsheet_42", "b",
                MatrixGraphStore.EdgeDirection.OUTGOING, 1)).thenReturn(
                new MatrixGraphStore.IncidentEdges(List.of(), false));

        BoundedKnowledgeGraphReader.IncidentEdges incoming = service.getIncidentEdges(
                "b", 42L, BoundedKnowledgeGraphReader.Direction.INCOMING, 1);
        BoundedKnowledgeGraphReader.IncidentEdges outgoing = service.getIncidentEdges(
                "b", 42L, BoundedKnowledgeGraphReader.Direction.OUTGOING, 1);

        assertEquals(1, incoming.edges().size());
        assertTrue(incoming.truncated());
        assertTrue(outgoing.edges().isEmpty());
        assertFalse(outgoing.truncated());
    }

    @Test
    void bidirectionalEdgeIsReadableInEitherDirectionFromEitherEndpoint() {
        MatrixGraphStore.StoredEdge stored = new MatrixGraphStore.StoredEdge(
                        "a", "b", "RELATED_TO", 1.0, true, "RELATED_TO",
                        0.9, null, Map.of());
        when(graphStore.scanIncidentEdges("factsheet_42", "b",
                MatrixGraphStore.EdgeDirection.OUTGOING, 10)).thenReturn(
                new MatrixGraphStore.IncidentEdges(List.of(stored), false));
        when(graphStore.scanIncidentEdges("factsheet_42", "a",
                MatrixGraphStore.EdgeDirection.INCOMING, 10)).thenReturn(
                new MatrixGraphStore.IncidentEdges(List.of(stored), false));

        assertEquals(1, service.getIncidentEdges("b", 42L,
                BoundedKnowledgeGraphReader.Direction.OUTGOING, 10).edges().size());
        assertEquals(1, service.getIncidentEdges("a", 42L,
                BoundedKnowledgeGraphReader.Direction.INCOMING, 10).edges().size());
    }

    @Test
    void scopedPointLookupUsesTheFactSheetGraphEvenWhenAnotherGraphIsHot() {
        MatrixGraphNode scoped = MatrixGraphNode.builder()
                .nodeId("shared").nodeType("ENTITY").title("Scoped").factSheetId(42L).build();
        when(graphStore.getLoadedGraphIds()).thenReturn(Set.of("factsheet_7"));
        when(graphStore.listGraphs()).thenReturn(List.of("factsheet_7", "factsheet_42"));
        when(graphStore.getNode("factsheet_42", "shared")).thenReturn(Optional.of(scoped));

        assertEquals("Scoped", service.getNodeInScope("shared", 42L).orElseThrow().getTitle());

        verify(graphStore).getNode("factsheet_42", "shared");
        verify(graphStore, never()).loadGraph(anyString());
    }

    @Test
    void unscopedLookupMergesHotAndPersistedGraphIds() {
        MatrixGraphNode cold = MatrixGraphNode.builder()
                .nodeId("cold").nodeType("ENTITY").title("Cold").build();
        when(graphStore.getLoadedGraphIds()).thenReturn(Set.of("factsheet_7"));
        when(graphStore.listGraphs()).thenReturn(List.of("factsheet_7", "factsheet_42"));
        when(graphStore.getNode("factsheet_7", "cold")).thenReturn(Optional.empty());
        when(graphStore.getNode("factsheet_42", "cold")).thenReturn(Optional.of(cold));

        assertEquals("Cold", service.getNode("cold").orElseThrow().getTitle());
    }
}
