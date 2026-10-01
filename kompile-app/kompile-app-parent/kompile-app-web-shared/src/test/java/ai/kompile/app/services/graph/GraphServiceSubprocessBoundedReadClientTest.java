/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.app.services.graph;

import ai.kompile.app.services.subprocess.GraphMatrixSubprocessLauncher;
import ai.kompile.app.subprocess.GraphMatrixSubprocessMain;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.BoundedKnowledgeGraphReader;
import ai.kompile.knowledgegraph.service.BoundedKnowledgeGraphReader.Direction;
import ai.kompile.knowledgegraph.service.BoundedKnowledgeGraphReader.IncidentEdges;
import ai.kompile.knowledgegraph.service.BoundedKnowledgeGraphReader.Neighborhood;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.knowledgegraph.unified.UnifiedGraphBridge;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Round trip through the real client, HTTP and the real subprocess dispatch. The store behind the
 * dispatch answers point and incident reads; the traversal runs next to it, as in the subprocess.
 */
class GraphServiceSubprocessBoundedReadClientTest {

    /** The app's mapper bean: nodes carry the derived {@code hollow} getter, which has no setter. */
    private final ObjectMapper mapper = JsonUtils.newStandardMapper();
    private final List<String> methods = new CopyOnWriteArrayList<>();
    private final KnowledgeGraphService store = mock(KnowledgeGraphService.class,
            withSettings().extraInterfaces(BoundedKnowledgeGraphReader.class));
    private HttpServer server;

    @BeforeEach
    void startSubprocess() throws IOException {
        GraphNode a = node("a");
        GraphNode b = node("b");
        GraphNode c = node("c");
        GraphEdge ab = edge(a, b);
        GraphEdge bc = edge(b, c);
        BoundedKnowledgeGraphReader reads = (BoundedKnowledgeGraphReader) store;
        when(reads.getNodeInScope(anyString(), eq(7L))).thenAnswer(call ->
                Optional.ofNullable(Map.of("a", a, "b", b, "c", c).get(call.<String>getArgument(0))));
        when(reads.getIncidentEdges(anyString(), eq(7L), any(), anyInt())).thenAnswer(call -> {
            String id = call.getArgument(0);
            return new IncidentEdges(Stream.of(ab, bc)
                    .filter(edge -> id.equals(edge.getSourceNodeId()) || id.equals(edge.getTargetNodeId()))
                    .toList(), false);
        });
        when(reads.getNeighborhood(any(), any(), any(), anyInt(), anyInt(), any(), anyInt())).thenCallRealMethod();

        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/invoke", this::invoke);
        server.start();
    }

    @AfterEach
    void stopSubprocess() {
        if (server != null) server.stop(0);
    }

    @Test
    void theWholeNeighborhoodCrossesTheProcessBoundaryInOneCall() {
        Neighborhood hood = client().getNeighborhood(7L, List.of("a"), List.of("a"), 2, 10, Direction.BOTH, 10);

        assertEquals(List.of("getNeighborhood"), methods);
        assertEquals(List.of("a", "b", "c"), hood.nodes().stream().map(GraphNode::getNodeId).toList());
        assertEquals(List.of("a::b", "b::c"), hood.edges().stream().map(GraphEdge::getEdgeId).toList());
        assertFalse(hood.truncated());
    }

    @Test
    void theBridgeSeesEdgesThroughTheClient() {
        UnifiedGraph bounded = new UnifiedGraphBridge(client()).exportNeighborhood(7L, List.of("a"), 1, 10);

        assertEquals(2, bounded.entityCount());
        assertEquals(1, bounded.relationCount(),
                "the app's bounded exports carried no edges while the client lacked bounded reads");
        assertEquals(false, bounded.meta().get("truncated"));
        assertEquals(List.of("getNeighborhood"), methods);
    }

    @Test
    void incidentEdgeAndScopedNodeReadsRoundTrip() {
        GraphServiceSubprocessClients.SubprocessKnowledgeGraphServiceClient client = client();

        IncidentEdges incident = client.getIncidentEdges("b", 7L, Direction.BOTH, 10);

        assertEquals(List.of("a::b", "b::c"), incident.edges().stream().map(GraphEdge::getEdgeId).toList());
        assertFalse(incident.truncated());
        assertEquals("b", client.getNodeInScope("b", 7L).orElseThrow().getNodeId());
        assertTrue(client.getNodeInScope("missing", 7L).isEmpty());
        assertEquals(List.of("getIncidentEdges", "getNodeInScope", "getNodeInScope"), methods);
    }

    private GraphServiceSubprocessClients.SubprocessKnowledgeGraphServiceClient client() {
        GraphMatrixSubprocessLauncher launcher = mock(GraphMatrixSubprocessLauncher.class);
        when(launcher.baseUrl()).thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
        return new GraphServiceSubprocessClients.SubprocessKnowledgeGraphServiceClient(launcher, mapper);
    }

    private void invoke(HttpExchange exchange) throws IOException {
        JsonNode request = mapper.readTree(exchange.getRequestBody());
        String method = request.path("method").asText();
        methods.add(method);
        List<JsonNode> args = new ArrayList<>();
        request.path("args").forEach(args::add);
        String body;
        try {
            body = GraphMatrixSubprocessMain.dispatchKnowledgeGraphService(store, null, method, args, mapper);
        } catch (Exception e) {
            body = mapper.createObjectNode().put("ok", false).put("error", String.valueOf(e.getMessage())).toString();
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static GraphNode node(String id) {
        return GraphNode.builder().nodeId(id).externalId(id).nodeType(NodeLevel.ENTITY)
                .title(id.toUpperCase()).factSheetId(7L).build();
    }

    private static GraphEdge edge(GraphNode source, GraphNode target) {
        return GraphEdge.builder().edgeId(source.getNodeId() + "::" + target.getNodeId())
                .sourceNodeId(source.getNodeId()).targetNodeId(target.getNodeId())
                .edgeType(EdgeType.USER_DEFINED).relationType("CALLS").weight(1.0).factSheetId(7L).build();
    }
}
