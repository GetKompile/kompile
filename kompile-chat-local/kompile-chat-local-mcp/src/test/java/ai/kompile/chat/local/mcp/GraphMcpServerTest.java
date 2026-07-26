package ai.kompile.chat.local.mcp;

import ai.kompile.chat.local.GraphToolBackend;
import ai.kompile.graph.reasoning.unified.MiniJson;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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
