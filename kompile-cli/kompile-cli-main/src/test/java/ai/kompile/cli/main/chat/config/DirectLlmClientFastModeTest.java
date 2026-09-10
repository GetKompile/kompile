package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.auth.oauth.OAuthProviderFlow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DirectLlmClientFastModeTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void openAiAndCodexSendPriorityThenExplicitDefaultWithoutChangingEffort() throws Exception {
        for (String provider : List.of("openai", "openai-codex")) {
            List<JsonNode> requests = new ArrayList<>();
            HttpServer server = server(requests, new ArrayList<>(), provider);
            try {
                ChatConfig config = new ChatConfig(provider, "test-key", "gpt-5.5", baseUrl(server));
                config.setThinking("high");
                try (DirectLlmClient client = new DirectLlmClient(config, mapper)) {
                    client.setOutputConsumer(ignored -> { });
                    config.setFastMode(true);
                    assertFalse(client.streamChat("first", "system", null, null).failed);
                    config.setFastMode(false);
                    assertFalse(client.streamChat("second", "system", null, null).failed);
                    assertEquals(2, requests.size());
                    assertEquals("priority", requests.get(0).path("service_tier").asText());
                    assertEquals("default", requests.get(1).path("service_tier").asText());
                    for (JsonNode request : requests) {
                        String effort = "openai-codex".equals(provider)
                                ? request.path("reasoning").path("effort").asText()
                                : request.path("reasoning_effort").asText();
                        assertEquals("high", effort);
                        assertFalse(request.has("speed"));
                    }
                    assertTrue(client.getHistorySize() >= 4, "toggling must retain conversation history");
                }
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void anthropicMergesFastBetaWithOauthAndCompactionAndRemovesItWhenOff() throws Exception {
        List<JsonNode> requests = new ArrayList<>();
        List<String> betaHeaders = new ArrayList<>();
        HttpServer server = server(requests, betaHeaders, "anthropic");
        try {
            ChatConfig config = new ChatConfig("anthropic", null, "claude-opus-5", baseUrl(server)) {
                @Override
                public OAuthProviderFlow.RequestAuth resolveRequestAuth() {
                    return OAuthProviderFlow.RequestAuth.oauth("test-oauth", null,
                            Map.of("anthropic-beta", "oauth-2025-04-20"));
                }
            };
            config.setFastMode(true);
            try (DirectLlmClient client = new DirectLlmClient(config, mapper)) {
                client.setOutputConsumer(ignored -> { });
                client.setNativeCompactionTriggerTokens(50_000);
                assertFalse(client.streamChat("first", "system", null, null).failed);
                config.setFastMode(false);
                assertFalse(client.streamChat("second", "system", null, null).failed);
                assertEquals("fast", requests.get(0).path("speed").asText());
                assertFalse(requests.get(1).has("speed"));
                assertTrue(betaHeaders.get(0).contains("fast-mode-2026-02-01"));
                assertFalse(betaHeaders.get(1).contains("fast-mode-2026-02-01"));
                for (String header : betaHeaders) {
                    assertTrue(header.contains("oauth-2025-04-20"));
                    assertTrue(header.contains("compact-2026-01-12"));
                }
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unsupportedRoutesAndEffectiveModelOverridesNeverReceivePaidFields() throws Exception {
        for (String provider : List.of("custom", "anthropic", "openai-codex")) {
            List<JsonNode> requests = new ArrayList<>();
            List<String> betaHeaders = new ArrayList<>();
            HttpServer server = server(requests, betaHeaders, provider);
            try {
                String configuredModel = "anthropic".equals(provider) ? "claude-opus-5" : "gpt-5.5";
                ChatConfig config = new ChatConfig(provider, "test-key", configuredModel, baseUrl(server));
                config.setFastMode(true);
                try (DirectLlmClient client = new DirectLlmClient(config, mapper)) {
                    client.setOutputConsumer(ignored -> { });
                    String override = "anthropic".equals(provider) ? "claude-sonnet-4-8"
                            : "openai-codex".equals(provider) ? "gpt-5.3-codex-spark" : configuredModel;
                    assertFalse(client.streamChat("hello", "system", null, null, override).failed);
                    assertFalse(requests.get(0).has("service_tier"));
                    assertFalse(requests.get(0).has("speed"));
                    assertFalse(betaHeaders.get(0).contains("fast-mode"));
                }
            } finally {
                server.stop(0);
            }
        }
    }

    private HttpServer server(List<JsonNode> requests, List<String> betaHeaders, String provider) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(mapper.readTree(exchange.getRequestBody()));
            String beta = exchange.getRequestHeaders().getFirst("anthropic-beta");
            betaHeaders.add(beta == null ? "" : beta);
            String body = switch (provider) {
                case "anthropic" -> "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":1}}}\n\n"
                        + "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}\n\n"
                        + "data: {\"type\":\"message_stop\"}\n\n";
                case "openai-codex" -> "data: {\"type\":\"response.output_text.delta\",\"output_index\":0,\"delta\":\"ok\"}\n\n"
                        + "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n";
                default -> "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":null}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
            };
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
