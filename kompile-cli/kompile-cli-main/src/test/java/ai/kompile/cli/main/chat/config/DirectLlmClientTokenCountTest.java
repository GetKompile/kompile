package ai.kompile.cli.main.chat.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectLlmClientTokenCountTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void anthropicCountUsesCompleteRequestWithoutMutatingHistory() throws Exception {
        AtomicReference<JsonNode> body = new AtomicReference<>();
        HttpServer server = server("/v1/messages/count_tokens", exchange -> {
            body.set(mapper.readTree(exchange.getRequestBody()));
            respond(exchange, "{\"input_tokens\":4321}");
        });
        try {
            DirectLlmClient client = client(
                    "anthropic", "claude-test", baseUrl(server), "key");
            client.addToHistory("user", "prior turn");

            DirectLlmClient.TokenCountResult count = client.countInputTokens(
                    "pending", "system", tools(), null, null);

            assertTrue(count.exact());
            assertEquals(4321, count.inputTokens());
            assertEquals(1, client.getHistorySize());
            assertEquals("claude-test", body.get().path("model").asText());
            assertEquals(1, body.get().path("tools").size());
            assertTrue(body.get().path("messages").toString().contains("prior turn"));
            assertTrue(body.get().path("messages").toString().contains("pending"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void codexResponsesCountUsesResponsesInputAndDoesNotMutateHistory() throws Exception {
        AtomicReference<JsonNode> body = new AtomicReference<>();
        HttpServer server = server("/codex/responses/input_tokens", exchange -> {
            body.set(mapper.readTree(exchange.getRequestBody()));
            respond(exchange, "{\"object\":\"response.input_tokens\",\"input_tokens\":9876}");
        });
        try {
            DirectLlmClient client = client(
                    "openai-codex", "gpt-test", baseUrl(server), "key");
            client.addToHistory("assistant", "prior answer");

            DirectLlmClient.TokenCountResult count = client.countInputTokens(
                    "pending", "instructions", tools(), null, null);

            assertTrue(count.exact());
            assertEquals(9876, count.inputTokens());
            assertEquals(1, client.getHistorySize());
            assertEquals("instructions", body.get().path("instructions").asText());
            assertTrue(body.get().path("input").toString().contains("prior answer"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void geminiCountTranslatesSystemHistoryAndTools() throws Exception {
        AtomicReference<JsonNode> body = new AtomicReference<>();
        HttpServer server = server("/v1beta/models/gemini-test:countTokens", exchange -> {
            body.set(mapper.readTree(exchange.getRequestBody()));
            respond(exchange, "{\"totalTokens\":2468}");
        });
        try {
            DirectLlmClient client = client(
                    "gemini", "gemini-test", baseUrl(server) + "/v1beta/openai", "key");
            client.addToHistory("assistant", "prior model output");

            DirectLlmClient.TokenCountResult count = client.countInputTokens(
                    "pending", "system instruction", tools(), null, null);

            assertTrue(count.exact());
            assertEquals(2468, count.inputTokens());
            assertEquals("system instruction", body.get()
                    .path("systemInstruction").path("parts").path(0).path("text").asText());
            assertEquals("model", body.get().path("contents").path(0).path("role").asText());
            assertEquals("read", body.get().path("tools").path(0)
                    .path("functionDeclarations").path(0).path("name").asText());
            assertEquals(1, client.getHistorySize());
        } finally {
            server.stop(0);
        }
    }

    private DirectLlmClient client(String provider, String model, String baseUrl, String apiKey) {
        return new DirectLlmClient(
                new ChatConfig(provider, apiKey, model, baseUrl), mapper);
    }

    private ArrayNode tools() {
        ArrayNode tools = mapper.createArrayNode();
        tools.addObject()
                .put("name", "read")
                .put("description", "Read a file")
                .set("inputSchema", mapper.createObjectNode().put("type", "object"));
        return tools;
    }

    private HttpServer server(String path, com.sun.net.httpserver.HttpHandler handler)
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, handler);
        server.start();
        return server;
    }

    private static String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String json)
            throws java.io.IOException {
        byte[] response = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
