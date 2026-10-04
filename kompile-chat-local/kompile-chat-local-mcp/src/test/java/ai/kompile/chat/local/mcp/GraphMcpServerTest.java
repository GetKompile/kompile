package ai.kompile.chat.local.mcp;

import ai.kompile.chat.local.GraphToolBackend;
import ai.kompile.chat.local.GraphToolBridge;
import ai.kompile.graph.reasoning.unified.MiniJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.graph.reasoning.unified.KGraphCompatibilityPolicy;
import java.nio.file.Path;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphMcpServerTest {

    @Test
    void initializePreservesNumericAndStringIds() {
        try (GraphMcpServer server = new GraphMcpServer(new FakeBackend())) {
            Map<String, Object> numeric = response(server,
                    request(7, "initialize", Map.of("protocolVersion", "2024-11-05")));
            assertEquals(7L, numeric.get("id"));
            assertEquals("2024-11-05", result(numeric).get("protocolVersion"));

            Map<String, Object> string = response(server,
                    request("request-2", "initialize", Map.of()));
            assertEquals("request-2", string.get("id"));

            Map<String, Object> capabilities = object(result(string).get("capabilities"));
            assertEquals(Map.of("listChanged", false), object(capabilities.get("tools")));
        }
    }

    @Test
    void notificationsNeverProduceResponses() {
        try (GraphMcpServer server = new GraphMcpServer(new FakeBackend())) {
            Optional<String> initialized = server.handle(
                    MiniJson.write(Map.of("jsonrpc", "2.0", "method", "notifications/initialized")));
            Optional<String> unknown = server.handle(
                    MiniJson.write(Map.of("jsonrpc", "2.0", "method", "notifications/unknown")));
            assertTrue(initialized.isEmpty());
            assertTrue(unknown.isEmpty());
        }
    }

    @Test
    void toolsListTranslatesInternalParameterSchema() {
        try (GraphMcpServer server = initializedServer()) {
            Map<String, Object> response = response(server, request(2, "tools/list", Map.of()));
            List<?> tools = list(result(response).get("tools"));
            assertEquals(2, tools.size());

            Map<String, Object> first = object(tools.get(0));
            assertEquals("graph_reasoning_query", first.get("name"));
            assertTrue(first.containsKey("inputSchema"));
            assertFalse(first.containsKey("parameters"));
        }
    }

    @Test
    void toolCallsDispatchArgumentsAndReturnMcpContent() {
        FakeBackend backend = new FakeBackend();
        try (GraphMcpServer server = initializedServer(backend)) {
            Map<String, Object> params = Map.of(
                    "name", "graph_reasoning_query",
                    "arguments", Map.of("operation", "capabilities"));
            Map<String, Object> response = response(server, request(3, "tools/call", params));
            Map<String, Object> callResult = result(response);

            assertEquals(false, callResult.get("isError"));
            assertEquals("graph_reasoning_query", backend.lastTool);
            assertEquals("capabilities", MiniJson.parseObject(backend.lastArgs).get("operation"));

            Map<String, Object> content = object(list(callResult.get("content")).get(0));
            assertEquals("text", content.get("type"));
            assertEquals(Map.of("status", "OK"), MiniJson.parseObject((String) content.get("text")));
        }
    }

    @Test
    void realBridgeExposesAndExecutesTheLocalReasoningToolCatalog() {
        try (GraphMcpServer server = new GraphMcpServer(GraphToolBridge.empty())) {
            response(server, request(1, "initialize", Map.of()));

            List<?> tools = list(result(response(
                    server, request(2, "tools/list", Map.of()))).get("tools"));
            Set<String> names = tools.stream()
                    .map(GraphMcpServerTest::object)
                    .map(tool -> (String) tool.get("name"))
                    .collect(Collectors.toSet());

            assertTrue(names.containsAll(Set.of(
                    "graph_reasoning_query",
                    "ask_graph_verify",
                    "ask_graph_query",
                    "ask_graph_explain",
                    "graph_reason",
                    "ask_graph_mebn",
                    "graph_bayes",
                    "ask_graph_claim",
                    "ask_graph_synthesize",
                    "graph_centrality",
                    "graph_psl",
                    "graph_embeddings")));

            Map<String, Object> call = result(response(server, request(3, "tools/call", Map.of(
                    "name", "graph_reasoning_query",
                    "arguments", Map.of("operation", "OVERVIEW")))));
            assertEquals(false, call.get("isError"));
            Map<String, Object> graphResult = MiniJson.parseObject((String) object(
                    list(call.get("content")).get(0)).get("text"));
            assertEquals("OK", graphResult.get("status"));
            assertEquals("OVERVIEW", graphResult.get("intent"));

            Map<String, Object> capabilitiesCall = result(response(server, request(
                    4, "tools/call", Map.of(
                            "name", "graph_reasoning_query",
                            "arguments", Map.of()))));
            assertEquals(false, capabilitiesCall.get("isError"));
            Map<String, Object> capabilities = MiniJson.parseObject((String) object(
                    list(capabilitiesCall.get("content")).get(0)).get("text"));
            assertEquals("CAPABILITIES", capabilities.get("intent"));
            List<?> supported = list(capabilities.get("capabilities"));
            assertEquals(21, supported.size());
            Set<String> operations = supported.stream().map(GraphMcpServerTest::object)
                    .map(capability -> (String) capability.get("intent")).collect(Collectors.toSet());
            assertTrue(operations.containsAll(Set.of("MODELS", "CALCULATE", "SCENARIO", "SOLVE_TARGET")));

            Map<String, Object> questionCall = result(response(server, request(
                    5, "tools/call", Map.of(
                            "name", "graph_reasoning_query",
                            "arguments", Map.of("question", "Find Orchid")))));
            assertEquals(false, questionCall.get("isError"));
            Map<String, Object> search = MiniJson.parseObject((String) object(
                    list(questionCall.get("content")).get(0)).get("text"));
            assertEquals("SEARCH", search.get("intent"));
        }
    }

    @Test
    void backendStatusErrorsStayInsideCallToolResult() {
        FakeBackend backend = new FakeBackend();
        backend.result = MiniJson.write(Map.of("status", "INVALID", "message", "bad input"));
        try (GraphMcpServer server = initializedServer(backend)) {
            Map<String, Object> response = response(server, request(4, "tools/call", Map.of(
                    "name", "graph_reasoning_query",
                    "arguments", Map.of())));
            assertEquals(true, result(response).get("isError"));
            assertFalse(response.containsKey("error"));
        }
    }

    @Test
    void protocolFailuresUseJsonRpcErrors() {
        try (GraphMcpServer server = new GraphMcpServer(new FakeBackend())) {
            Map<String, Object> parseError = response(server, "{bad");
            assertEquals(-32700L, object(parseError.get("error")).get("code"));

            Map<String, Object> notInitialized = response(server, request(1, "tools/list", Map.of()));
            assertEquals(-32002L, object(notInitialized.get("error")).get("code"));

            response(server, request(2, "initialize", Map.of()));
            Map<String, Object> unknownTool = response(server, request(3, "tools/call", Map.of(
                    "name", "does_not_exist",
                    "arguments", Map.of())));
            assertEquals(-32602L, object(unknownTool.get("error")).get("code"));

            Map<String, Object> unknownMethod = response(server, request(4, "resources/list", Map.of()));
            assertEquals(-32601L, object(unknownMethod.get("error")).get("code"));
        }
    }

    @Test
    void rejectsMalformedCatalogsBeforeServing() {
        FakeBackend backend = new FakeBackend();
        backend.catalog = "[{\"name\":\"duplicate\",\"parameters\":{}},"
                + "{\"name\":\"duplicate\",\"parameters\":{}}]";
        assertThrows(IllegalArgumentException.class, () -> new GraphMcpServer(backend));
    }

    @Test
    void closeOwnsBackendExactlyOnce() {
        FakeBackend backend = new FakeBackend();
        GraphMcpServer server = new GraphMcpServer(backend);
        server.close();
        server.close();
        assertEquals(1, backend.closeCount);
    }

    @Test
    void exportedInterchangeIsUsableThroughRealMcpWithoutPromotingConfidence(@TempDir Path temp) throws Exception {
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity("alice", "PERSON", "Alice");
        graph.addEntity("acme", "COMPANY", "Acme");
        graph.addRelation("employment", "alice", "acme", "WORKS_AT", 0.7);
        graph.putArtifact("unregistered.bin", new byte[]{1, 2, 3});
        Path exported = temp.resolve("portable.kgraph");
        graph.save(exported, KGraphCompatibilityPolicy.PORTABLE_V2);
        try (GraphMcpServer server = new GraphMcpServer(GraphToolBridge.open(exported))) {
            response(server, request(1, "initialize", Map.of()));
            Map<String, Object> verified = toolText(server, 2, "ask_graph_verify", Map.of("atom", "WORKS_AT(alice, acme)"));
            assertEquals("SUPPORTED", verified.get("verdict"));
            assertEquals(0.7, ((Number) verified.get("confidence")).doubleValue(), 1e-9);
            Map<?, ?> data = object(toolText(server, 3, "graph_reasoning_query", Map.of("operation", "ASSETS")).get("data"));
            Map<?, ?> report = object(data.get("portability"));
            assertEquals("kompile-graph-reasoning-local", report.get("consumer"));
            Map<?, ?> artifact = object(list(report.get("artifacts")).get(0));
            assertEquals("INSPECTION_ONLY", artifact.get("status"));
        }
    }

    @Test
    void portableCustomPslExecutesThroughRealMcp(@TempDir Path temp) throws Exception {
        var p = new ai.kompile.graph.reasoning.psl.PslProgram()
                .observe("Evidence", 0.8, "x").target("Prediction", "x")
                .addRule("3: Evidence(X) -> Prediction(X) ^2");
        UnifiedGraph graph = new UnifiedGraph().putArtifactText(
                ai.kompile.graph.reasoning.psl.PslProgramArtifactCodec.ARTIFACT,
                ai.kompile.graph.reasoning.psl.PslProgramArtifactCodec.encode(p));
        Path exported = temp.resolve("program.kgraph");
        graph.save(exported, KGraphCompatibilityPolicy.PORTABLE_V2);
        try (GraphMcpServer server = new GraphMcpServer(GraphToolBridge.open(exported))) {
            response(server, request(1, "initialize", Map.of()));
            Map<String, Object> inferred = toolText(server, 2, "graph_psl", Map.of());
            assertEquals("OK", inferred.get("status"), inferred.toString());
            assertEquals("ADMM_JAVA", inferred.get("solver"));
            assertEquals(false, inferred.get("binaryCacheUsed"));
            assertTrue(((Number) object(inferred.get("targets")).get("Prediction(x)")).doubleValue() >= 0.799);
        }
    }

    private static Map<String, Object> toolText(GraphMcpServer server, int id, String name, Map<String, Object> arguments) {
        Map<String, Object> called = result(response(server, request(id, "tools/call", Map.of("name", name, "arguments", arguments))));
        assertEquals(false, called.get("isError"), called.toString());
        return MiniJson.parseObject((String) object(list(called.get("content")).get(0)).get("text"));
    }

    private static GraphMcpServer initializedServer() {
        return initializedServer(new FakeBackend());
    }

    private static GraphMcpServer initializedServer(FakeBackend backend) {
        GraphMcpServer server = new GraphMcpServer(backend);
        response(server, request(1, "initialize", Map.of()));
        return server;
    }

    private static String request(Object id, String method, Map<String, Object> params) {
        return MiniJson.write(Map.of(
                "jsonrpc", "2.0",
                "id", id,
                "method", method,
                "params", params));
    }

    private static Map<String, Object> response(GraphMcpServer server, String request) {
        return MiniJson.parseObject(server.handle(request).orElseThrow());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> result(Map<String, Object> response) {
        return (Map<String, Object>) response.get("result");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private static List<?> list(Object value) {
        return (List<?>) value;
    }

    private static final class FakeBackend implements GraphToolBackend {
        private String catalog = """
                [
                  {
                    "name":"graph_reasoning_query",
                    "description":"Query the local graph",
                    "parameters":{"type":"object","properties":{"operation":{"type":"string"}}}
                  },
                  {
                    "name":"graph_reason",
                    "description":"Explain graph evidence",
                    "parameters":{"type":"object","properties":{"target":{"type":"string"}}}
                  }
                ]
                """;
        private String result = MiniJson.write(Map.of("status", "OK"));
        private String lastTool;
        private String lastArgs;
        private int closeCount;

        @Override
        public String catalogJson() {
            return catalog;
        }

        @Override
        public String execute(String toolName, String argsJson) {
            lastTool = toolName;
            lastArgs = argsJson;
            return result;
        }

        @Override
        public void close() {
            closeCount++;
        }
    }
}
