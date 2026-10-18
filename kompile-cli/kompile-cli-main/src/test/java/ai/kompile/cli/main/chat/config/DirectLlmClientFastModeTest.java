package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.auth.oauth.OAuthProviderFlow;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
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

@TemporaryUserHome
class DirectLlmClientFastModeTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void openAiCodexAndXaiSendPriorityThenExplicitDefaultWithoutChangingEffort() throws Exception {
        for (String provider : List.of("openai", "openai-codex", "xai")) {
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
    void geminiAndGroqUseTheirOwnPriorityAndStandardTierValues() throws Exception {
        for (String provider : List.of("gemini", "groq")) {
            List<JsonNode> requests = new ArrayList<>();
            HttpServer server = server(requests, new ArrayList<>(), provider);
            try {
                ChatConfig config = new ChatConfig(provider, "test-key", "day-one-unlisted-model", baseUrl(server));
                try (DirectLlmClient client = new DirectLlmClient(config, mapper)) {
                    client.setOutputConsumer(ignored -> { });
                    config.setFastMode(true);
                    assertFalse(client.streamChat("first", "system", null, null).failed);
                    config.setFastMode(false);
                    assertFalse(client.streamChat("second", "system", null, null).failed);
                    assertEquals("gemini".equals(provider) ? "priority" : "performance",
                            requests.get(0).path("service_tier").asText());
                    assertEquals("gemini".equals(provider) ? "standard" : "on_demand",
                            requests.get(1).path("service_tier").asText());
                    assertFalse(requests.get(0).has("speed"));
                }
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void openRouterRequestsPriorityWithoutChangingModelOrRoutingPreferences() throws Exception {
        List<JsonNode> requests = new ArrayList<>();
        HttpServer server = server(requests, new ArrayList<>(), "openrouter");
        try {
            ChatConfig config = new ChatConfig("openrouter", "test-key", "vendor/day-one-model", baseUrl(server));
            try (DirectLlmClient client = new DirectLlmClient(config, mapper)) {
                client.setOutputConsumer(ignored -> { });
                config.setFastMode(true);
                assertFalse(client.streamChat("first", "system", null, null).failed);
                config.setFastMode(false);
                assertFalse(client.streamChat("second", "system", null, null).failed);
                assertEquals("priority", requests.get(0).path("service_tier").asText());
                assertEquals("default", requests.get(1).path("service_tier").asText());
                for (JsonNode request : requests) {
                    assertEquals("vendor/day-one-model", request.path("model").asText());
                    assertFalse(request.has("provider"));
                    assertFalse(request.has("speed"));
                }
            }
        } finally {
            server.stop(0);
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
    void effectiveModelOverridesRetainFastModeWithoutClientModelRestrictions() throws Exception {
        for (String provider : List.of("anthropic", "openai", "openai-codex")) {
            List<JsonNode> requests = new ArrayList<>();
            List<String> betaHeaders = new ArrayList<>();
            HttpServer server = server(requests, betaHeaders, provider);
            try {
                ChatConfig config = new ChatConfig(provider, "test-key", "configured-model", baseUrl(server));
                config.setFastMode(true);
                try (DirectLlmClient client = new DirectLlmClient(config, mapper)) {
                    client.setOutputConsumer(ignored -> { });
                    String override = "anthropic".equals(provider) ? "claude-sonnet-new-unlisted"
                            : "gpt-new-unlisted";
                    assertFalse(client.streamChat("hello", "system", null, null, override).failed);
                    JsonNode request = requests.get(0);
                    assertEquals(override, request.path("model").asText());
                    if ("anthropic".equals(provider)) {
                        assertEquals("fast", request.path("speed").asText());
                        assertTrue(betaHeaders.get(0).contains("fast-mode-2026-02-01"));
                    } else {
                        assertEquals("priority", request.path("service_tier").asText());
                    }
                }
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void unsupportedRoutesNeverReceivePaidFields() throws Exception {
        for (String provider : List.of("custom", "deepseek", "zai")) {
            List<JsonNode> requests = new ArrayList<>();
            List<String> betaHeaders = new ArrayList<>();
            HttpServer server = server(requests, betaHeaders, provider);
            try {
                ChatConfig config = new ChatConfig(provider, "test-key", "gpt-5.5", baseUrl(server));
                config.setFastMode(true);
                try (DirectLlmClient client = new DirectLlmClient(config, mapper)) {
                    client.setOutputConsumer(ignored -> { });
                    assertFalse(client.streamChat("hello", "system", null, null).failed);
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
            // Compaction may probe the Models API before the generation request.
            // This fixture exercises the legacy compaction fallback, not discovery.
            if ("GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
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
