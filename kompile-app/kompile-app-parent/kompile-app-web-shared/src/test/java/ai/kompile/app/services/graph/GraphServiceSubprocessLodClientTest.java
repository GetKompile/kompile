/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.app.services.graph;

import ai.kompile.app.services.subprocess.GraphMatrixSubprocessLauncher;
import ai.kompile.app.subprocess.GraphMatrixSubprocessMain;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Round trip through the real client, HTTP and the real subprocess dispatch for the graph page's
 * level-of-detail reads. Before the client forwarded them, every app answered the one-hop expand
 * with the interface's empty default and the top-K overview ignored its fact sheet.
 */
class GraphServiceSubprocessLodClientTest {

    private final ObjectMapper mapper = JsonUtils.newStandardMapper();
    private final List<String> methods = new CopyOnWriteArrayList<>();
    private final KnowledgeGraphService store = mock(KnowledgeGraphService.class);
    private HttpServer server;

    @BeforeEach
    void startSubprocess() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/invoke", this::invoke);
        server.start();
    }

    @AfterEach
    void stopSubprocess() {
        if (server != null) server.stop(0);
    }

    @Test
    void theExpandCrossesWithItsFactSheetAndEdgeTypes() {
        Map<String, Object> stored = Map.of(
                "nodes", List.of(Map.of("id", "a"), Map.of("id", "b")),
                "edges", List.of(Map.of("id", "a::b::MENTIONS", "source", "a", "target", "b")),
                "statistics", Map.of("totalNeighbors", 1));
        when(store.expandNeighborhoodVisualization("a", 25, List.of("MENTIONS"), 7L)).thenReturn(stored);

        Map<String, Object> result = client().expandNeighborhoodVisualization("a", 25, List.of("MENTIONS"), 7L);

        assertEquals(stored, result);
        assertEquals(List.of("expandNeighborhoodVisualization"), methods);
    }

    @Test
    void anUnfilteredUnscopedExpandArrivesAsNullsNotAnEmptyFilter() {
        Map<String, Object> stored = Map.of("nodes", List.of(Map.of("id", "a")));
        when(store.expandNeighborhoodVisualization("a", 25, null, null)).thenReturn(stored);

        assertEquals(stored, client().expandNeighborhoodVisualization("a", 25, null));
        verify(store).expandNeighborhoodVisualization("a", 25, null, null);
        assertEquals(List.of("expandNeighborhoodVisualization"), methods);
    }

    @Test
    void theTopKOverviewIsScopedToItsFactSheet() {
        Map<String, Object> stored = Map.of("nodes", List.of(Map.of("id", "hub")),
                "statistics", Map.of("k", 50, "metric", "degree"));
        when(store.getTopKVisualizationData(7L, 50, "degree")).thenReturn(stored);

        assertEquals(stored, client().getTopKVisualizationData(7L, 50, "degree"));
        assertEquals(List.of("getTopKVisualizationData"), methods);
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
}
