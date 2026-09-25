package ai.kompile.cli.main.chat.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("SYSTEM_PROPERTIES")
class DirectLlmClientAstraResponsesTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void astraUsesResponsesOnlyOnTheOpenAiRouteIncludingOverridesAndSnapshots() throws Exception {
        ChatConfig config = new ChatConfig("openai", "test-key", "gpt-6-astra", "http://127.0.0.1:1/v1");
        try (DirectLlmClient client = new DirectLlmClient(config, mapper)) {
            assertEquals(DirectLlmClient.WireProtocol.OPENAI_RESPONSES, client.resolveRoute(null).protocol());
            assertFalse(client.resolveRoute(null).codexBackend());
            assertEquals(DirectLlmClient.WireProtocol.OPENAI_RESPONSES,
                    client.resolveRoute("gpt-6-astra-2026-09-01").protocol());
            assertEquals(DirectLlmClient.WireProtocol.OPENAI_CHAT, client.resolveRoute("gpt-4o").protocol());
            assertEquals(DirectLlmClient.WireProtocol.OPENAI_CHAT,
                    client.resolveRoute("gpt-6-astral-custom").protocol());
            config.setModel("gpt-4o");
            assertEquals(DirectLlmClient.WireProtocol.OPENAI_RESPONSES,
                    client.resolveRoute("gpt-6-astra").protocol());
            config.setProvider("openrouter");
            assertEquals(DirectLlmClient.WireProtocol.OPENAI_CHAT,
                    client.resolveRoute("gpt-6-astra").protocol());
        }
    }

    @Test
    void apiKeyReasoningAndToolsUseResponsesWithLinkedStatelessContinuation() throws Exception {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        List<String> authorizations = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            requests.add(mapper.readTree(exchange.getRequestBody()));
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            String stream = requests.size() == 2 ? """
                    data: {"type":"response.output_item.done","output_index":0,"item":{"type":"reasoning","id":"rs_1","summary":[],"encrypted_content":"opaque-reasoning"}}

                    data: {"type":"response.output_item.done","output_index":1,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"read","arguments":"{\\\"path\\\":\\\"README.md\\\"}"}}

                    data: {"type":"response.completed","response":{"output":[]}}

                    """ : """
                    data: {"type":"response.output_text.delta","delta":"done"}

                    data: {"type":"response.completed","response":{"output":[]}}

                    """;
            byte[] bytes = stream.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        try {
            ChatConfig config = config(server);
            config.setThinking("high");
            ArrayNode tools = (ArrayNode) mapper.readTree("""
                    [{"name":"read","description":"Read a file","inputSchema":{
                      "type":"object","properties":{"path":{"type":"string"},"limit":{"type":"integer"}},
                      "required":["path"]}}]
                    """);
            JsonNode originalTools = tools.deepCopy();
            try (DirectLlmClient client = new DirectLlmClient(config, mapper)) {
                client.setOutputConsumer(ignored -> {});
                // Tool-free turns must not switch back to Chat Completions.
                assertEquals("done", client.streamChat("hello", "system", null, null).text);
                var first = client.streamChat("read it", "system", tools, null);
                assertEquals(1, first.toolCalls.size(), first.text);
                assertEquals("call_1", first.toolCalls.get(0).id);
                assertEquals("README.md", first.toolCalls.get(0).arguments.path("path").asText());
                var second = client.streamChat(null, "system", tools, List.of(
                        new DirectLlmClient.ToolCallResultInput("call_1", "read", "contents", false)));
                assertEquals("done", second.text);
            }
            assertEquals(3, requests.size());
            for (JsonNode request : requests) {
                assertEquals("gpt-6-astra", request.path("model").asText());
                assertEquals("high", request.path("reasoning").path("effort").asText());
                assertFalse(request.has("reasoning_effort"));
                assertFalse(request.has("messages"));
                assertFalse(request.path("store").asBoolean(true));
                assertTrue(request.path("stream").asBoolean());
                assertEquals("reasoning.encrypted_content", request.path("include").path(0).asText());
                assertEquals("developer", request.path("input").path(0).path("role").asText());
            }
            assertEquals(List.of("Bearer test-key", "Bearer test-key", "Bearer test-key"), authorizations);
            JsonNode tool = requests.get(1).path("tools").path(0);
            assertEquals("function", tool.path("type").asText());
            assertEquals("read", tool.path("name").asText());
            assertFalse(tool.path("strict").asBoolean(true));
            assertEquals(originalTools.path(0).path("inputSchema"), tool.path("parameters"));
            assertEquals(originalTools, tools);
            JsonNode input = requests.get(2).path("input");
            assertEquals("opaque-reasoning", item(input, "reasoning").path("encrypted_content").asText());
            assertEquals("call_1", item(input, "function_call").path("call_id").asText());
            assertEquals("call_1", item(input, "function_call_output").path("call_id").asText());
            assertEquals("contents", item(input, "function_call_output").path("output").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resumedAstraToolHistoryUsesResponsesItems() throws Exception {
        ChatConfig config = new ChatConfig("openai", "test-key", "gpt-6-astra", "http://127.0.0.1:1/v1");
        try (DirectLlmClient client = new DirectLlmClient(config, mapper)) {
            client.addReplayedToolCall("read", "call_resume", "{\"path\":\"README.md\"}");
            client.addReplayedToolResult("read", "call_resume", "contents");
            var method = DirectLlmClient.class.getDeclaredMethod("buildResponsesInput",
                    String.class, String.class, List.class, boolean.class, List.class);
            method.setAccessible(true);
            JsonNode input = (JsonNode) method.invoke(client, "continue", "system", List.of(), false, List.of());
            assertEquals("call_resume", item(input, "function_call").path("call_id").asText());
            assertEquals("call_resume", item(input, "function_call_output").path("call_id").asText());
            assertEquals("contents", item(input, "function_call_output").path("output").asText());
            for (JsonNode node : input) {
                assertFalse(node.has("tool_calls"));
                assertNotEquals("tool", node.path("role").asText());
            }
        }
    }

    private static ChatConfig config(HttpServer server) {
        return new ChatConfig("openai", "test-key", "gpt-6-astra",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    }

    private static JsonNode item(JsonNode input, String type) {
        for (JsonNode node : input) {
            if (type.equals(node.path("type").asText())) return node;
        }
        fail("Missing " + type + " item in " + input);
        return null;
    }
}
