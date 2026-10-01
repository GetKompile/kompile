/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.app.subprocess;

import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.BoundedKnowledgeGraphReader;
import ai.kompile.knowledgegraph.service.BoundedKnowledgeGraphReader.Direction;
import ai.kompile.knowledgegraph.service.BoundedKnowledgeGraphReader.IncidentEdges;
import ai.kompile.knowledgegraph.service.BoundedKnowledgeGraphReader.Neighborhood;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class GraphMatrixSubprocessBoundedReadDispatchTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final KnowledgeGraphService service = mock(KnowledgeGraphService.class,
            withSettings().extraInterfaces(BoundedKnowledgeGraphReader.class));
    private final BoundedKnowledgeGraphReader reader = (BoundedKnowledgeGraphReader) service;

    @AfterEach
    void restoreResponseCap() {
        GraphMatrixSubprocessMain.responseByteCap = GraphMatrixSubprocessMain.DEFAULT_MAX_RESPONSE_BYTES;
    }

    @Test
    void neighborhoodRunsInOneCallWithBudgetsLoweredToTheWireCeilings() throws Exception {
        when(reader.getNeighborhood(any(), any(), any(), anyInt(), anyInt(), any(), anyInt()))
                .thenReturn(new Neighborhood(List.of(node("a"), node("b")), List.of(edge("b", "a")), false));

        JsonNode result = dispatch("getNeighborhood", 7L, List.of("a"), List.of("a"), 2, 100_000, "INCOMING", 500_000);

        verify(reader).getNeighborhood(7L, List.of("a"), List.of("a"), 2,
                GraphMatrixSubprocessMain.BOUNDED_READ_WIRE_MAX_NODES, Direction.INCOMING,
                GraphMatrixSubprocessMain.BOUNDED_READ_WIRE_MAX_EDGES);
        assertEquals(List.of("a", "b"), values(result.path("nodes"), "nodeId"));
        assertEquals(List.of("b::a"), values(result.path("edges"), "edgeId"));
        assertFalse(result.path("truncated").asBoolean(true));
    }

    @Test
    void aMissingDirectionTraversesBothWays() throws Exception {
        when(reader.getNeighborhood(any(), any(), any(), anyInt(), anyInt(), any(), anyInt()))
                .thenReturn(new Neighborhood(List.of(), List.of(), false));

        dispatch("getNeighborhood", null, List.of("a"), List.of("a"), 1, 10, null, 10);

        verify(reader).getNeighborhood(null, List.of("a"), List.of("a"), 1, 10, Direction.BOTH, 10);
    }

    @Test
    void anOversizedNeighborhoodIsHalvedAndMarkedTruncatedInsteadOfRefused() throws Exception {
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            nodes.add(GraphNode.builder().nodeId("n" + i).nodeType(NodeLevel.ENTITY).factSheetId(7L)
                    .description("x".repeat(2_000)).build());
            if (i > 0) edges.add(edge("n" + (i - 1), "n" + i));
        }
        when(reader.getNeighborhood(any(), any(), any(), anyInt(), anyInt(), any(), anyInt()))
                .thenReturn(new Neighborhood(nodes, edges, false));
        GraphMatrixSubprocessMain.responseByteCap = 16_000;

        String reply = GraphMatrixSubprocessMain.dispatchKnowledgeGraphService(
                service, null, "getNeighborhood",
                args(7L, List.of("n0"), List.of("n0"), 8, 100, "BOTH", 100), mapper);
        JsonNode result = mapper.readTree(reply).path("result");

        assertTrue(reply.length() <= 16_000);
        List<String> kept = values(result.path("nodes"), "nodeId");
        assertFalse(kept.isEmpty());
        assertTrue(kept.size() < nodes.size());
        assertEquals(nodes.stream().map(GraphNode::getNodeId).toList().subList(0, kept.size()), kept,
                "the reply keeps a prefix of the discovery order, so the seed survives");
        Set<String> keptIds = new HashSet<>(kept);
        for (JsonNode edge : result.path("edges")) {
            assertTrue(keptIds.contains(edge.path("sourceNodeId").asText()));
            assertTrue(keptIds.contains(edge.path("targetNodeId").asText()));
        }
        assertTrue(result.path("truncated").asBoolean(false));
    }

    @Test
    void incidentEdgesAndScopedNodeReadsAnswerWithWhatTheClientDecodes() throws Exception {
        when(reader.getIncidentEdges("a", 7L, Direction.OUTGOING, GraphMatrixSubprocessMain.BOUNDED_READ_WIRE_MAX_EDGES))
                .thenReturn(new IncidentEdges(List.of(edge("a", "b")), true));
        when(reader.getNodeInScope("a", 7L)).thenReturn(Optional.of(node("a")));

        JsonNode incident = dispatch("getIncidentEdges", "a", 7L, "OUTGOING", 1_000_000);

        assertEquals(List.of("a::b"), values(incident.path("edges"), "edgeId"));
        assertTrue(incident.path("truncated").asBoolean(false));
        assertEquals("a", dispatch("getNodeInScope", "a", 7L).path("nodeId").asText());
    }

    @Test
    void aServiceWithoutBoundedReadsIsRefused() {
        KnowledgeGraphService plain = mock(KnowledgeGraphService.class);

        assertThrows(IllegalArgumentException.class, () -> GraphMatrixSubprocessMain.dispatchKnowledgeGraphService(
                plain, null, "getNeighborhood", args(7L, List.of("a"), List.of("a"), 1, 10, null, 10), mapper));
    }

    private JsonNode dispatch(String method, Object... values) throws Exception {
        JsonNode reply = mapper.readTree(GraphMatrixSubprocessMain.dispatchKnowledgeGraphService(
                service, null, method, args(values), mapper));
        assertTrue(reply.path("ok").asBoolean(false));
        return reply.path("result");
    }

    private List<JsonNode> args(Object... values) {
        List<JsonNode> encoded = new ArrayList<>();
        for (Object value : values) encoded.add(value == null ? mapper.nullNode() : mapper.valueToTree(value));
        return encoded;
    }

    private static List<String> values(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : array) values.add(item.path(field).asText());
        return values;
    }

    private static GraphNode node(String id) {
        return GraphNode.builder().nodeId(id).nodeType(NodeLevel.ENTITY).factSheetId(7L).build();
    }

    private static GraphEdge edge(String source, String target) {
        return GraphEdge.builder().edgeId(source + "::" + target)
                .sourceNodeId(source).targetNodeId(target).factSheetId(7L).build();
    }
}
