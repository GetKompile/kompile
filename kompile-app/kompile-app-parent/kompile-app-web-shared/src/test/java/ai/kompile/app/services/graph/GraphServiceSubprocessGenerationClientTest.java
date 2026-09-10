/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.app.services.graph;

import ai.kompile.app.services.subprocess.GraphMatrixSubprocessLauncher;
import ai.kompile.core.crawl.graph.DistributedGraphRuntimeContext;
import ai.kompile.knowledgegraph.generation.GraphGeneration;
import ai.kompile.knowledgegraph.generation.GraphGenerationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GraphServiceSubprocessGenerationClientTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private volatile String lastAuthorization;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void capabilityNegotiationAndGenerationEnvelopeAreExplicitAndNonLeaking() throws Exception {
        startServer(2, true);
        GraphServiceSubprocessClients.SubprocessKnowledgeGraphServiceClient client = client();

        assertTrue(client.supportsGraphGenerations());
        GraphGeneration.Ref generation = client.beginFactSheetGeneration(7L, "g1", "job-7");
        assertEquals("job-7", requests.get(0).path("args").get(2).asText());
        assertFalse(requests.get(0).has("generation"));

        try (var ignored = GraphGenerationContext.open(generation, "job-7")) {
            client.getNode("n1");
        }
        client.getNode("n2");

        JsonNode scoped = requests.get(1);
        assertEquals(2, scoped.path("protocolVersion").asInt());
        assertEquals(generation.physicalGraphId(),
                scoped.path("generation").path("physicalGraphId").asText());
        assertEquals("job-7", scoped.path("generationOwnerJobId").asText());
        assertFalse(requests.get(2).has("generation"),
                "the reused client must not retain a prior request's generation target");
    }

    @Test
    void oldServerFailsClosedBeforeLifecycleMutation() throws Exception {
        startServer(1, false);
        GraphServiceSubprocessClients.SubprocessKnowledgeGraphServiceClient client = client();

        assertFalse(client.supportsGraphGenerations());
        assertThrows(UnsupportedOperationException.class,
                () -> client.beginFactSheetGeneration(7L, "g1", "job-7"));
        assertTrue(requests.isEmpty());
    }

    @Test
    void distributedContextRoutesServiceRpcThroughGatewayWithLeaseHeaders() throws Exception {
        startServer(2, true);
        GraphServiceSubprocessClients.SubprocessKnowledgeGraphServiceClient client = client();
        DistributedGraphRuntimeContext route = new DistributedGraphRuntimeContext(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "secret", "lease", "s1", "p1", 2);

        try (var ignored = GraphGenerationContext.openRemote(null, "distributed:s1", route)) {
            client.getNode("n1");
        }

        assertEquals("Bearer secret", lastAuthorization);
        assertEquals("getNode", requests.get(0).path("method").asText());
    }

    private GraphServiceSubprocessClients.SubprocessKnowledgeGraphServiceClient client() {
        GraphMatrixSubprocessLauncher launcher = mock(GraphMatrixSubprocessLauncher.class);
        when(launcher.baseUrl()).thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
        return new GraphServiceSubprocessClients.SubprocessKnowledgeGraphServiceClient(launcher, mapper);
    }

    private void startServer(int protocolVersion, boolean generations) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/capabilities", exchange -> {
            ObjectNode body = mapper.createObjectNode();
            body.put("protocolVersion", protocolVersion);
            body.put("generationLifecycle", generations);
            body.put("generationRouting", generations);
            body.put("generationAuthority", generations);
            send(exchange, body);
        });
        server.createContext("/invoke", this::handleInvoke);
        server.createContext("/api/internal/distributed-graph/invoke", this::handleInvoke);
        server.start();
    }

    private void handleInvoke(HttpExchange exchange) throws IOException {
            lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            requests.add(request);
            String method = request.path("method").asText();
            ObjectNode response = mapper.createObjectNode();
            response.put("ok", true);
            response.put("protocolVersion", 2);
            if ("beginFactSheetGeneration".equals(method)) {
                response.set("result", mapper.valueToTree(ref()));
            } else if ("activateFactSheetGeneration".equals(method)) {
                response.set("result", mapper.valueToTree(new GraphGeneration.Activation(
                        "factsheet_7", "factsheet_7~gen~g1", "factsheet_7", 1L, Instant.now())));
            } else {
                response.putNull("result");
            }
            send(exchange, response);
    }

    private GraphGeneration.Ref ref() {
        return new GraphGeneration.Ref(
                7L, "factsheet_7", "factsheet_7~gen~g1", "g1", "factsheet_7", 0L);
    }

    private void send(HttpExchange exchange, JsonNode body) throws IOException {
        byte[] bytes = mapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
