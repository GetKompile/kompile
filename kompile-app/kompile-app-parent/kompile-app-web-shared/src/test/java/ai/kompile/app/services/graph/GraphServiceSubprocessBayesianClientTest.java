/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.app.services.graph;

import ai.kompile.app.services.subprocess.GraphMatrixSubprocessLauncher;
import ai.kompile.app.subprocess.GraphMatrixSubprocessMain;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.event.attribution.service.BayesianNetworkService;
import ai.kompile.graph.reasoning.domain.BayesianInferenceResult;
import ai.kompile.graph.reasoning.domain.MTheoryStructure;
import ai.kompile.graph.reasoning.mebn.EntityType;
import ai.kompile.graph.reasoning.mebn.MFrag;
import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.mebn.RandomVariable;
import ai.kompile.graph.reasoning.mebn.RelationalMTheoryBuilder;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The Bayesian client against the real subprocess dispatch over loopback HTTP. A failed call is
 * answered the way the subprocess answers it, so both halves of the error mapping run.
 */
class GraphServiceSubprocessBayesianClientTest {

    private final ObjectMapper mapper = JsonUtils.newStandardMapper();
    private final BayesianNetworkService bayesian = mock(BayesianNetworkService.class);
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    /** When set, the subprocess answers with this body instead of dispatching. */
    private volatile String cannedReply;
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
    void theFactSheetScopedQueryRunsInTheSubprocessInOneCall() {
        when(bayesian.queryMebnFromKg(anyCollection(), anyMap(), anyInt(), anyInt(), any(), any()))
                .thenReturn(BayesianInferenceResult.builder()
                        .posteriors(Map.of("isRelevant(alice)", 0.8)).build());

        BayesianInferenceResult result = client().queryMebnFromKg(
                List.of("seed"), Map.of("isRelevant(bob)", 1), 2, 50, null, 42L);

        verify(bayesian).queryMebnFromKg(List.of("seed"), Map.of("isRelevant(bob)", 1), 2, 50, null, 42L);
        assertEquals(0.8, result.getPosteriors().get("isRelevant(alice)"), 1e-9);
        assertEquals(1, requests.size(),
                "the inherited body grounded in the app with one RPC per graph node");
    }

    @Test
    void aRejectedArgumentArrivesAsAnIllegalArgumentWithTheServiceMessage() {
        when(bayesian.queryMebnFromKg(anyCollection(), anyMap(), anyInt(), anyInt(), any(), any()))
                .thenThrow(new IllegalArgumentException("Unknown MEBN evidence variable(s): isRelevant(carol)."));

        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class, () -> client()
                .queryMebnFromKg(List.of("seed"), Map.of("isRelevant(carol)", 1), 2, 50, null, 42L));

        assertEquals("Unknown MEBN evidence variable(s): isRelevant(carol).", rejected.getMessage(),
                "controllers answer 400 with this message, as they do in-process");
    }

    @Test
    void aMethodTheSubprocessLacksNamesTheBuildMismatch() {
        GraphServiceSubprocessClients.SubprocessRpcBase rpc = new GraphServiceSubprocessClients.SubprocessRpcBase(
                BayesianNetworkService.class.getName(), launcher(), mapper) {};

        UnsupportedOperationException missing = assertThrows(UnsupportedOperationException.class,
                () -> rpc.rpcRaw("noSuchMethod", new Object[0]));

        assertTrue(missing.getMessage().contains("BayesianNetworkService.noSuchMethod"), missing.getMessage());
        assertTrue(missing.getMessage().contains("older build"), missing.getMessage());
    }

    @Test
    void anOlderSubprocessWithoutTheCodeIsStillRecognised() {
        cannedReply = mapper.createObjectNode().put("ok", false).put("protocolVersion", 2)
                .put("code", "INTERNAL_ERROR")
                .put("error", "[graph-matrix] BayesianNetworkService: unknown method: queryMebnFromKg").toString();

        assertThrows(UnsupportedOperationException.class,
                () -> client().queryMebnFromKg(List.of("seed"), Map.of(), 2, 50, null, 42L));
    }

    @Test
    void otherFailuresStayServerErrors() {
        when(bayesian.queryMebnFromKg(anyCollection(), anyMap(), anyInt(), anyInt(), any(), any()))
                .thenThrow(new IllegalStateException("boom"));

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> client().queryMebnFromKg(List.of("seed"), Map.of(), 2, 50, null, 42L));

        assertFalse(failure instanceof IllegalArgumentException);
        assertFalse(failure instanceof UnsupportedOperationException);
        assertTrue(failure.getMessage().contains("[INTERNAL_ERROR]: boom"), failure.getMessage());
    }

    @Test
    void aWhatIfQueryRunsInTheSubprocessInOneCall() {
        when(bayesian.whatIfQuery(anyCollection(), anyMap(), anyInt(), anyInt()))
                .thenReturn(BayesianInferenceResult.builder().posteriors(Map.of("b", 0.9)).build());

        BayesianInferenceResult result = client().whatIfQuery(List.of("seed"), Map.of("a", 1), 2, 50);

        verify(bayesian).whatIfQuery(List.of("seed"), Map.of("a", 1), 2, 50);
        assertEquals(0.9, result.getPosteriors().get("b"), 1e-9);
        assertEquals(1, requests.size(), "the inherited body built the network with one RPC per graph node");
    }

    @Test
    void networkStatisticsAreOneCall() {
        when(bayesian.getNetworkStatistics(anyCollection(), anyInt(), anyInt())).thenReturn(Map.of("nodes", 3));

        Map<String, Object> stats = client().getNetworkStatistics(List.of("seed"), 2, 50);

        assertEquals(3, ((Number) stats.get("nodes")).intValue());
        assertEquals(1, requests.size(), "the inherited body built the network with one RPC per graph node");
    }

    @Test
    void theStructureOfAGraphBuiltTheoryComesBackInOneCall() {
        MTheory theory = graphBuiltTheory();
        MFrag frag = theory.getMFrag("EntityRelevance");
        frag.addResidentNode(RandomVariable.unary(
                "isActive", theory.getEntityType("AllNodes"), RandomVariable.NodeRole.RESIDENT));
        frag.addParentEdge("isRelevant", "isActive", 0.7);
        when(bayesian.describeMebnTheory(anyCollection(), anyInt(), anyInt()))
                .thenReturn(MTheoryStructure.of(theory));

        MTheoryStructure structure = client().describeMebnTheory(List.of("n1"), 2, 50);

        MTheoryStructure.Fragment fragment = structure.fragments().get(0);
        assertEquals("EntityRelevance", fragment.name());
        assertEquals(List.of("isRelevant", "isActive"),
                fragment.residentNodes().stream().map(MTheoryStructure.Variable::name).toList());
        assertEquals(new MTheoryStructure.Edge("isRelevant", "isActive", 0.7), fragment.edges().get(0));
        assertEquals(1, requests.size());
    }

    @Test
    void aGraphBuiltTheoryIsNotSentAcrossTheBoundary() {
        UnsupportedOperationException refused = assertThrows(UnsupportedOperationException.class,
                () -> client().buildMebnTheory(List.of("n1"), 2, 50));

        assertTrue(refused.getMessage().contains("describeMebnTheory"), refused.getMessage());
        assertTrue(requests.isEmpty());
    }

    @Test
    void aCanonicalTheoryIsGroundedInTheSubprocessInOneCall() {
        MTheory theory = RelationalMTheoryBuilder.build("portable", List.of(
                new RelationalMTheoryBuilder.RelationDescriptor(
                        "supports", "PERSON", "DOCUMENT", 0.77, List.of("alice"), List.of("doc-1"))));
        when(bayesian.queryWithMTheory(any(), anyMap(), any(), any()))
                .thenReturn(BayesianInferenceResult.builder().posteriors(Map.of("isRelevant(alice)", 0.6)).build());

        // The two-argument overload reaches the fact-sheet overload, so every overload is one call.
        BayesianInferenceResult result = client().queryWithMTheory(theory, Map.of("isRelevant(doc-1)", 1));

        ArgumentCaptor<MTheory> sent = ArgumentCaptor.forClass(MTheory.class);
        verify(bayesian).queryWithMTheory(sent.capture(), eq(Map.of("isRelevant(doc-1)", 1)), isNull(), isNull());
        assertEquals(0.77, sent.getValue().getMFrag("supports").getEdgeStrength("isRelevant", "supports"), 1e-12);
        assertEquals(Set.of("alice"), sent.getValue().getEntityType("PERSON").getEntityIds());
        assertEquals(0.6, result.getPosteriors().get("isRelevant(alice)"), 1e-9);
        assertEquals(1, requests.size());
    }

    @Test
    void aTheoryWithJavaDistributionsCannotFallBackToAppGrounding() {
        KnowledgeGraphService graph = mock(KnowledgeGraphService.class);
        var client = new GraphServiceSubprocessClients.SubprocessBayesianNetworkServiceClient(
                launcher(), mapper, graph);
        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
                () -> client.queryWithMTheory(graphBuiltTheory(), Map.of(), null, null));

        assertTrue(error.getMessage().contains("queryMebnFromKg"));
        assertTrue(requests.isEmpty(), "its local distributions cannot be serialized");
        verifyNoInteractions(graph, bayesian);
    }

    /** A theory as KgMTheoryBuilder makes one: its fragment computes its CPT in Java. */
    private static MTheory graphBuiltTheory() {
        MTheory theory = new MTheory("kg");
        EntityType all = new EntityType("AllNodes");
        all.addEntity("n1");
        theory.addEntityType(all);
        MFrag frag = new MFrag("EntityRelevance");
        frag.addResidentNode(RandomVariable.unary("isRelevant", all, RandomVariable.NodeRole.RESIDENT));
        frag.setLocalDistribution((rv, strengths) -> new double[]{0.5, 0.5});
        theory.addMFrag(frag);
        return theory;
    }

    private GraphServiceSubprocessClients.SubprocessBayesianNetworkServiceClient client() {
        return new GraphServiceSubprocessClients.SubprocessBayesianNetworkServiceClient(
                launcher(), mapper, mock(KnowledgeGraphService.class));
    }

    private GraphMatrixSubprocessLauncher launcher() {
        GraphMatrixSubprocessLauncher launcher = mock(GraphMatrixSubprocessLauncher.class);
        when(launcher.baseUrl()).thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
        return launcher;
    }

    private void invoke(HttpExchange exchange) throws IOException {
        JsonNode request = mapper.readTree(exchange.getRequestBody());
        requests.add(request);
        List<JsonNode> args = new ArrayList<>();
        request.path("args").forEach(args::add);
        String body = cannedReply;
        if (body == null) {
            try {
                body = GraphMatrixSubprocessMain.dispatchBayesianNetworkService(
                        bayesian, request.path("method").asText(), args, mapper);
            } catch (Exception e) {
                // As the subprocess's invoke handler answers a failed call.
                body = mapper.createObjectNode().put("ok", false).put("protocolVersion", 2)
                        .put("code", GraphMatrixSubprocessMain.rpcErrorCode(e))
                        .put("error", String.valueOf(e.getMessage())).toString();
            }
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
