package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.auth.CredentialStore;
import ai.kompile.cli.common.auth.ManagedCredential;
import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.tools.ReadTool;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class DirectLlmClientOAuthTest {
    @TempDir
    Path tempDir;

    @Test
    void anthropicOauthUsesBearerAndClaudeIdentityHeaders() throws Exception {
        withTemporaryHome(() -> {
            AtomicReference<Map<String, java.util.List<String>>> headers = new AtomicReference<>();
            AtomicReference<String> requestBody = new AtomicReference<>();
            HttpServer server = server("/v1/messages", exchange -> {
                headers.set(exchange.getRequestHeaders());
                requestBody.set(new String(
                        exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8));
                respond(exchange, 401, "{\"error\":{\"message\":\"test\"}}");
            });
            try {
                CredentialStore.create().putOAuth(
                        "anthropic",
                        "sk-ant-oat-managed",
                        "refresh",
                        System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1));
                ChatConfig config = new ChatConfig(
                        "anthropic",
                        null,
                        "claude-sonnet-4-20250514",
                        baseUrl(server));
                DirectLlmClient client = new DirectLlmClient(config, new ObjectMapper());

                client.streamChat("hello", "Kompile system prompt", null, null);

                assertEquals("Bearer sk-ant-oat-managed", first(headers.get(), "Authorization"));
                assertNull(first(headers.get(), "x-api-key"));
                assertTrue(first(headers.get(), "anthropic-beta").contains("oauth-2025-04-20"));
                assertTrue(requestBody.get().contains(
                        "You are Claude Code, Anthropic's official CLI for Claude."));
            } finally {
                server.stop(0);
            }
        });
    }

    @Test
    void xaiOauthUsesBearerForOpenAiCompatibleRequest() throws Exception {
        withTemporaryHome(() -> {
            AtomicReference<Map<String, java.util.List<String>>> headers = new AtomicReference<>();
            HttpServer server = server("/chat/completions", exchange -> {
                headers.set(exchange.getRequestHeaders());
                exchange.getRequestBody().readAllBytes();
                respond(exchange, 401, "{\"error\":{\"message\":\"test\"}}");
            });
            try {
                CredentialStore.create().putOAuth(
                        "xai",
                        "xai-access",
                        "xai-refresh",
                        System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1));
                ChatConfig config = new ChatConfig("xai", null, "grok-4", baseUrl(server));
                DirectLlmClient client = new DirectLlmClient(config, new ObjectMapper());

                client.streamChat("hello", "system", null, null);

                assertEquals("Bearer xai-access", first(headers.get(), "Authorization"));
            } finally {
                server.stop(0);
            }
        });
    }

    @Test
    void githubCopilotOauthAddsRequiredClientHeaders() throws Exception {
        withTemporaryHome(() -> {
            AtomicReference<Map<String, java.util.List<String>>> headers = new AtomicReference<>();
            HttpServer server = server("/chat/completions", exchange -> {
                headers.set(exchange.getRequestHeaders());
                exchange.getRequestBody().readAllBytes();
                respond(exchange, 401, "{\"error\":{\"message\":\"test\"}}");
            });
            try {
                CredentialStore.create().put(
                        "github-copilot",
                        ManagedCredential.oauth(
                                "tid=x;proxy-ep=proxy.individual.githubcopilot.com;exp=y",
                                "github-token",
                                System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1)));
                ChatConfig config = new ChatConfig(
                        "github-copilot",
                        null,
                        "gpt-4.1",
                        baseUrl(server));
                DirectLlmClient client = new DirectLlmClient(config, new ObjectMapper());

                client.streamChat("hello", "system", null, null);

                assertEquals(
                        "Bearer tid=x;proxy-ep=proxy.individual.githubcopilot.com;exp=y",
                        first(headers.get(), "Authorization"));
                assertEquals("vscode-chat", first(headers.get(), "Copilot-Integration-Id"));
                assertEquals("vscode/1.107.0", first(headers.get(), "Editor-Version"));
            } finally {
                server.stop(0);
            }
        });
    }

    @Test
    void openAiCodexUsesResponsesProtocolAndReplaysToolCalls() throws Exception {
        withTemporaryHome(() -> {
            ObjectMapper mapper = new ObjectMapper();
            List<JsonNode> requests = new ArrayList<>();
            AtomicReference<Map<String, java.util.List<String>>> headers = new AtomicReference<>();
            AtomicInteger calls = new AtomicInteger();
            HttpServer server = server("/codex/responses", exchange -> {
                headers.set(exchange.getRequestHeaders());
                requests.add(mapper.readTree(exchange.getRequestBody()));
                int call = calls.getAndIncrement();
                if (call == 0) {
                    respondSse(exchange,
                            "data: {\"type\":\"response.output_item.added\",\"output_index\":0,"
                                    + "\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\",\"summary\":[]}}\n\n"
                                    + "data: {\"type\":\"response.output_item.done\",\"output_index\":0,"
                                    + "\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\",\"summary\":[]}}\n\n"
                                    + "data: {\"type\":\"response.output_item.added\",\"output_index\":1,"
                                    + "\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\","
                                    + "\"call_id\":\"call_abc\",\"name\":\"read\",\"arguments\":\"\"}}\n\n"
                                    + "data: {\"type\":\"response.function_call_arguments.delta\","
                                    + "\"output_index\":1,\"delta\":\"{\\\"path\\\":\\\"README.md\\\"}\"}\n\n"
                                    + "data: {\"type\":\"response.output_item.done\",\"output_index\":1,"
                                    + "\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\","
                                    + "\"call_id\":\"call_abc\",\"name\":\"read\","
                                    + "\"arguments\":\"{\\\"path\\\":\\\"README.md\\\"}\"}}\n\n"
                                    + "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\","
                                    + "\"output\":[{\"type\":\"reasoning\",\"id\":\"rs_1\",\"summary\":[],"
                                    + "\"encrypted_content\":\"opaque-reasoning\"}],"
                                    + "\"usage\":{\"input_tokens\":12,\"output_tokens\":4,"
                                    + "\"input_tokens_details\":{\"cached_tokens\":2}}}}\n\n");
                } else {
                    respondSse(exchange,
                            "data: {\"type\":\"response.output_text.delta\",\"output_index\":0,"
                                    + "\"delta\":\"done\"}\n\n"
                                    + "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\","
                                    + "\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}}\n\n");
                }
            });
            try {
                CredentialStore.create().put(
                        "openai-codex",
                        ManagedCredential.oauth(
                                "codex-access",
                                "codex-refresh",
                                System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1),
                                Map.of("accountId", "account-123")));
                ChatConfig config = new ChatConfig(
                        "openai-codex", null, "gpt-5.6-terra", baseUrl(server));
                config.setThinking("high");
                DirectLlmClient client = new DirectLlmClient(config, mapper);
                client.setOutputConsumer(ignored -> {});

                DirectLlmClient.StreamResult first =
                        client.streamChat("read it", "system", toolDefinitions(mapper), null);
                assertEquals(1, first.toolCalls.size());
                assertEquals("call_abc", first.toolCalls.get(0).id);
                assertEquals("read", first.toolCalls.get(0).name);
                assertEquals("README.md", first.toolCalls.get(0).arguments.path("path").asText());
                assertEquals(10, first.inputTokens);
                assertEquals(2, first.cacheReadTokens);

                DirectLlmClient.StreamResult second = client.streamChat(
                        null,
                        "system",
                        toolDefinitions(mapper),
                        List.of(new DirectLlmClient.ToolCallResultInput(
                                "call_abc", "read", "contents", false)));
                assertEquals("done", second.text);
                assertEquals(2, requests.size());

                JsonNode firstRequest = requests.get(0);
                assertEquals("gpt-5.6-terra", firstRequest.path("model").asText());
                assertEquals("high", firstRequest.path("reasoning").path("effort").asText());
                assertFalse(firstRequest.path("store").asBoolean(true));
                assertEquals("system", firstRequest.path("instructions").asText());
                assertEquals(1, firstRequest.path("tools").size());
                assertEquals("function", firstRequest.path("tools").path(0).path("type").asText());
                assertEquals("read", firstRequest.path("tools").path(0).path("name").asText());
                assertEquals("object", firstRequest.path("tools").path(0)
                        .path("parameters").path("type").asText());
                assertFalse(firstRequest.path("tools").path(0)
                        .path("parameters").path("properties").isEmpty());
                assertTrue(firstRequest.path("tools").path(0).has("strict"));
                assertTrue(firstRequest.path("tools").path(0).path("strict").isNull());

                String replay = requests.get(1).path("input").toString();
                assertTrue(replay.contains("\"reasoning\""));
                assertTrue(replay.contains("\"encrypted_content\":\"opaque-reasoning\""));
                assertTrue(replay.contains("\"function_call\""));
                assertTrue(replay.contains("\"function_call_output\""));
                assertTrue(replay.contains("\"call_abc\""));
                assertEquals("Bearer codex-access", first(headers.get(), "Authorization"));
                assertEquals("account-123", first(headers.get(), "chatgpt-account-id"));
                assertEquals("responses=experimental", first(headers.get(), "OpenAI-Beta"));
            } finally {
                server.stop(0);
            }
        });
    }

    @Test
    void radiusLoadsCatalogThenStreamsPiMessages() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> requestBodies = new ArrayList<>();
        AtomicInteger messageCalls = new AtomicInteger();
        AtomicReference<Map<String, java.util.List<String>>> configHeaders = new AtomicReference<>();
        AtomicReference<Map<String, java.util.List<String>>> messageHeaders = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/config", exchange -> {
            try {
                configHeaders.set(exchange.getRequestHeaders());
                respond(exchange, 200,
                        "{\"baseUrl\":\"" + baseUrl(server) + "/pi\","
                                + "\"models\":[{\"id\":\"radius-model\",\"name\":\"Radius Model\","
                                + "\"reasoning\":true,\"input\":[\"text\"],\"cost\":{},"
                                + "\"contextWindow\":100000,\"maxTokens\":8192}]}");
            } finally {
                exchange.close();
            }
        });
        server.createContext("/pi/messages", exchange -> {
            try {
                messageHeaders.set(exchange.getRequestHeaders());
                requestBodies.add(mapper.readTree(exchange.getRequestBody()));
                if (messageCalls.getAndIncrement() == 0) {
                    respondSse(exchange,
                            "data: {\"type\":\"thinking_start\",\"contentIndex\":0}\n\n"
                                    + "data: {\"type\":\"thinking_delta\",\"contentIndex\":0,\"delta\":\"plan\"}\n\n"
                                    + "data: {\"type\":\"thinking_end\",\"contentIndex\":0,"
                                    + "\"content\":\"plan\",\"contentSignature\":\"thinking-signature\"}\n\n"
                                    + "data: {\"type\":\"text_start\",\"contentIndex\":1}\n\n"
                                    + "data: {\"type\":\"text_delta\",\"contentIndex\":1,\"delta\":\"hello\"}\n\n"
                                    + "data: {\"type\":\"text_end\",\"contentIndex\":1,"
                                    + "\"content\":\"hello\",\"contentSignature\":\"text-signature\"}\n\n"
                                    + "data: {\"type\":\"toolcall_start\",\"contentIndex\":2,"
                                    + "\"id\":\"call_radius\",\"toolName\":\"read\"}\n\n"
                                    + "data: {\"type\":\"toolcall_delta\",\"contentIndex\":2,"
                                    + "\"delta\":\"{\\\"path\\\":\\\"pom.xml\\\"}\"}\n\n"
                                    + "data: {\"type\":\"toolcall_end\",\"contentIndex\":2,"
                                    + "\"toolCall\":{\"type\":\"toolCall\",\"id\":\"call_radius\","
                                    + "\"name\":\"read\",\"arguments\":{\"path\":\"pom.xml\"}}}\n\n"
                                    + "data: {\"type\":\"done\",\"reason\":\"toolUse\","
                                    + "\"usage\":{\"input\":7,\"output\":3,\"cacheRead\":2,"
                                    + "\"cacheWrite\":1,\"totalTokens\":13,\"cost\":{}}}\n\n");
                } else {
                    respondSse(exchange,
                            "data: {\"type\":\"text_delta\",\"contentIndex\":0,\"delta\":\"finished\"}\n\n"
                                    + "data: {\"type\":\"done\",\"reason\":\"stop\","
                                    + "\"usage\":{\"input\":2,\"output\":1,\"cacheRead\":0,"
                                    + "\"cacheWrite\":0,\"totalTokens\":3,\"cost\":{}}}\n\n");
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            ChatConfig config = new ChatConfig(
                    "radius", "radius-key", "radius-model", baseUrl(server));
            DirectLlmClient client = new DirectLlmClient(config, mapper);
            client.setOutputConsumer(ignored -> {});

            DirectLlmClient.StreamResult result =
                    client.streamChat("hello", "system", toolDefinitions(mapper), null);
            DirectLlmClient.StreamResult continuation = client.streamChat(
                    null,
                    "system",
                    toolDefinitions(mapper),
                    List.of(new DirectLlmClient.ToolCallResultInput(
                            "call_radius", "read", "contents", false)));

            assertEquals("hello", result.text);
            assertEquals("finished", continuation.text);
            assertEquals(1, result.toolCalls.size());
            assertEquals("call_radius", result.toolCalls.get(0).id);
            assertEquals("pom.xml", result.toolCalls.get(0).arguments.path("path").asText());
            assertEquals(7, result.inputTokens);
            assertEquals(3, result.outputTokens);
            assertEquals(2, result.cacheReadTokens);
            assertEquals(1, result.cacheCreationTokens);
            assertEquals("Bearer radius-key", first(configHeaders.get(), "Authorization"));
            assertEquals("Bearer radius-key", first(messageHeaders.get(), "Authorization"));
            JsonNode firstRequest = requestBodies.get(0);
            assertEquals("radius-model", firstRequest.path("model").asText());
            assertEquals("system", firstRequest.path("context").path("systemPrompt").asText());
            assertEquals("read",
                    firstRequest.path("context").path("tools").path(0).path("name").asText());
            assertEquals("hello",
                    firstRequest.path("context").path("messages").path(0).path("content").asText());
            assertTrue(firstRequest.path("context").path("messages").path(0).path("timestamp").isNumber());
            String replay = requestBodies.get(1).path("context").path("messages").toString();
            assertTrue(replay.contains("thinking-signature"));
            assertTrue(replay.contains("text-signature"));
            assertTrue(replay.contains("\"role\":\"toolResult\""));
            assertTrue(replay.contains("call_radius"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void githubCopilotRoutesModelsToTheirPiApiFamilies() throws Exception {
        withTemporaryHome(() -> {
            AtomicReference<Map<String, java.util.List<String>>> responsesHeaders = new AtomicReference<>();
            AtomicReference<Map<String, java.util.List<String>>> anthropicHeaders = new AtomicReference<>();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/responses", exchange -> {
                try {
                    responsesHeaders.set(exchange.getRequestHeaders());
                    exchange.getRequestBody().readAllBytes();
                    respondSse(exchange,
                            "data: {\"type\":\"response.completed\","
                                    + "\"response\":{\"status\":\"completed\",\"usage\":{}}}\n\n");
                } finally {
                    exchange.close();
                }
            });
            server.createContext("/v1/messages", exchange -> {
                try {
                    anthropicHeaders.set(exchange.getRequestHeaders());
                    exchange.getRequestBody().readAllBytes();
                    respond(exchange, 401, "{\"error\":{\"message\":\"test\"}}");
                } finally {
                    exchange.close();
                }
            });
            server.start();
            try {
                CredentialStore.create().put(
                        "github-copilot",
                        ManagedCredential.oauth(
                                "tid=x;proxy-ep=proxy.individual.githubcopilot.com;exp=y",
                                "github-token",
                                System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1)));

                ChatConfig responsesConfig = new ChatConfig(
                        "github-copilot", null, "gpt-5.6-terra", baseUrl(server));
                DirectLlmClient responsesClient = new DirectLlmClient(responsesConfig, mapper());
                responsesClient.setOutputConsumer(ignored -> {});
                responsesClient.streamChat("hello", "system", null, null);

                ChatConfig anthropicConfig = new ChatConfig(
                        "github-copilot", null, "claude-sonnet-4.6", baseUrl(server));
                DirectLlmClient anthropicClient = new DirectLlmClient(anthropicConfig, mapper());
                anthropicClient.streamChat("hello", "system", null, null);

                assertEquals("user", first(responsesHeaders.get(), "X-Initiator"));
                assertEquals("conversation-edits", first(responsesHeaders.get(), "Openai-Intent"));
                assertNotNull(anthropicHeaders.get());
                assertEquals(
                        "Bearer tid=x;proxy-ep=proxy.individual.githubcopilot.com;exp=y",
                        first(anthropicHeaders.get(), "Authorization"));
                assertEquals("conversation-edits", first(anthropicHeaders.get(), "Openai-Intent"));
            } finally {
                server.stop(0);
            }
        });
    }

    @Test
    void githubCopilotApiKeyUsesBearerAndCopilotHeadersForAnthropicModels() throws Exception {
        AtomicReference<Map<String, java.util.List<String>>> headers = new AtomicReference<>();
        HttpServer server = server("/v1/messages", exchange -> {
            headers.set(exchange.getRequestHeaders());
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 401, "{\"error\":{\"message\":\"test\"}}");
        });
        try {
            ChatConfig config = new ChatConfig(
                    "github-copilot",
                    "copilot-session-token",
                    "claude-sonnet-4.6",
                    baseUrl(server));
            DirectLlmClient client = new DirectLlmClient(config, new ObjectMapper());
            client.streamChat("hello", "system", null, null);

            assertEquals("Bearer copilot-session-token", first(headers.get(), "Authorization"));
            assertNull(first(headers.get(), "x-api-key"));
            assertEquals("GitHubCopilotChat/0.35.0", first(headers.get(), "User-Agent"));
            assertEquals("vscode-chat", first(headers.get(), "Copilot-Integration-Id"));
            assertEquals("conversation-edits", first(headers.get(), "Openai-Intent"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void providerPickerIncludesSubscriptionProtocols() {
        assertEquals("https://chatgpt.com/backend-api",
                ChatConfig.getDefaultBaseUrl("openai-codex"));
        assertEquals("https://radius.pi.dev", ChatConfig.getDefaultBaseUrl("radius"));
        assertTrue(ChatConfig.PROVIDERS.containsKey("openai-codex"));
        assertTrue(ChatConfig.PROVIDERS.containsKey("radius"));
        assertTrue(List.of(ChatConfig.getDefaultModels("openai-codex"))
                .contains("gpt-5.6-terra"));
        assertEquals(0, ChatConfig.getDefaultModels("radius").length);
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper();
    }

    private static com.fasterxml.jackson.databind.node.ArrayNode toolDefinitions(
            ObjectMapper mapper) {
        ToolRegistry registry = new ToolRegistry(mapper);
        registry.register(new ReadTool());
        AgentConfig agent = AgentConfig.builder("test")
                .enabledTools(Set.of("*"))
                .build();
        return registry.buildDirectToolDefinitions(agent);
    }

    private HttpServer server(String path, Handler handler) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    private String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void respondSse(HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static String first(
            Map<String, java.util.List<String>> headers,
            String name) {
        if (headers == null) {
            return null;
        }
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(entry -> entry.getValue().isEmpty() ? null : entry.getValue().get(0))
                .findFirst()
                .orElse(null);
    }

    private void withTemporaryHome(ThrowingRunnable body) throws Exception {
        String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            body.run();
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws java.io.IOException;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
