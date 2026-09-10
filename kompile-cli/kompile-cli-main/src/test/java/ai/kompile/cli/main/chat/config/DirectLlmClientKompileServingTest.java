package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectLlmClientKompileServingTest {

    private final ObjectMapper mapper = JsonUtils.standardMapper();

    @Test
    void usesCanonicalServingChatEndpointAndParsesToolCalls()
            throws Exception {
        AtomicReference<JsonNode> captured = new AtomicReference<>();
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/llm/chat", exchange -> {
            try {
                captured.set(mapper.readTree(exchange.getRequestBody()));
                byte[] response = """
                        {"rawText":"<tool_call>read</tool_call>",
                         "content":"Checking the file.",
                         "toolCalls":[{"id":"call_1","name":"read",
                           "arguments":{"path":"README.md"}}],
                         "parseErrors":[],"finishReason":"completed","totalTimeMs":3}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add(
                        "Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
        });
        server.start();

        try {
            ChatConfig config = preparedConfig(server);
            DirectLlmClient client = new DirectLlmClient(config, mapper);
            StringBuilder streamed = new StringBuilder();
            client.setOutputConsumer(streamed::append);

            ArrayNode toolDefs = mapper.createArrayNode();
            ObjectNode tool = toolDefs.addObject();
            tool.put("name", "read");
            tool.put("description", "Read a file");
            tool.putObject("inputSchema")
                    .put("type", "object")
                    .putObject("properties")
                    .putObject("path")
                    .put("type", "string");

            DirectLlmClient.StreamResult result =
                    client.streamChat(
                            "Open README", "You are helpful", toolDefs, null);

            assertEquals("Checking the file.", result.text);
            assertEquals("Checking the file.", streamed.toString());
            assertEquals(1, result.toolCalls.size());
            assertEquals("read", result.toolCalls.get(0).name);
            assertEquals("README.md",
                    result.toolCalls.get(0).arguments.path("path").asText());

            JsonNode request = captured.get();
            JsonNode structured = request.path("request");
            assertEquals("system",
                    structured.path("messages").path(0).path("role").asText());
            assertEquals("Open README",
                    structured.path("messages").path(1).path("content").asText());
            assertEquals("read",
                    structured.path("tools").path(0).path("name").asText());
            assertEquals("object", structured.path("tools").path(0)
                    .path("parameters").path("type").asText());
            assertEquals("AUTO", structured.path("toolChoice").asText());
            assertEquals("STANDARD",
                    structured.path("toolDefinitionFormat").asText());
            assertEquals("NATIVE",
                    structured.path("toolCallFormat").asText());
            assertEquals(1024, request.path("maxTokens").asInt());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void surfacesServingHttpErrors() throws Exception {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/llm/chat", exchange -> {
            try {
                byte[] response = "{\"error\":\"synthetic serving failure\"}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(503, response.length);
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
        });
        server.start();

        try {
            DirectLlmClient client =
                    new DirectLlmClient(preparedConfig(server), mapper);
            StringBuilder output = new StringBuilder();
            client.setOutputConsumer(output::append);

            DirectLlmClient.StreamResult result =
                    client.streamChat("hello", null, null, null);

            assertTrue(result.text.contains("Kompile serving HTTP 503"));
            assertTrue(result.text.contains("synthetic serving failure"));
            assertEquals(result.text, output.toString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesToolResultWithoutDuplicatingItInHistory() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<JsonNode> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/llm/chat", exchange -> {
            try {
                requests.add(mapper.readTree(exchange.getRequestBody()));
                if (calls.getAndIncrement() == 0) {
                    byte[] body = "{\"error\":\"retry\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(503, body.length);
                    exchange.getResponseBody().write(body);
                } else {
                    byte[] body = "{\"content\":\"ok\",\"toolCalls\":[],"
                            .concat("\"finishReason\":\"completed\"}")
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
            } finally {
                exchange.close();
            }
        });
        server.start();

        try {
            ProviderConnectivityPolicy policy = new ProviderConnectivityPolicy(
                    Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(1),
                    Duration.ofSeconds(1), 2, Duration.ofMillis(1), Duration.ofMillis(2));
            DirectLlmClient client = new DirectLlmClient(
                    preparedConfig(server), mapper, policy);
            client.setOutputConsumer(ignored -> { });
            List<DirectLlmClient.ToolCallResultInput> toolResults = List.of(
                    new DirectLlmClient.ToolCallResultInput(
                            "call_1", "read", "contents", false));

            DirectLlmClient.StreamResult result =
                    client.streamChat(null, null, null, toolResults);

            assertEquals("ok", result.text);
            assertEquals(2, requests.size());
            assertEquals(1, countRole(requests.get(0), "tool"));
            assertEquals(1, countRole(requests.get(1), "tool"),
                    "the retry must stage the pending result once, not replay + resubmit it");
        } finally {
            server.stop(0);
        }
    }

    private int countRole(JsonNode request, String role) {
        int count = 0;
        for (JsonNode message : request.path("request").path("messages")) {
            if (role.equals(message.path("role").asText())) count++;
        }
        return count;
    }

    private ChatConfig preparedConfig(HttpServer server) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        return new ChatConfig(
                "kompile-local", null, "Qwen2.5-0.5B-Instruct", baseUrl);
    }
}
