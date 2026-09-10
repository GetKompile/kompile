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
import java.time.Duration;
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
                respond(exchange, 400, "{\"error\":{\"message\":\"test\"}}");
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
                JsonNode request = new ObjectMapper().readTree(requestBody.get());
                assertEquals("ephemeral",
                        request.path("cache_control").path("type").asText());
                assertFalse(request.path("cache_control").has("ttl"));
                assertTrue(requestBody.get().contains(
                        "You are Claude Code, Anthropic's official CLI for Claude."));
            } finally {
                server.stop(0);
            }
        });
    }

    @Test
    void anthropicNativeCompactionIsRequestedParsedAndReplayed() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> requests = new ArrayList<>();
        AtomicReference<Map<String, java.util.List<String>>> headers = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server("/v1/messages", exchange -> {
            headers.set(exchange.getRequestHeaders());
            requests.add(mapper.readTree(exchange.getRequestBody()));
            if (calls.getAndIncrement() == 0) {
                respondSse(exchange,
                        "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":12}}}\n\n"
                                + "data: {\"type\":\"content_block_start\",\"content_block\":{\"type\":\"compaction\",\"content\":null}}\n\n"
                                + "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"compaction_delta\",\"content\":\"native summary\"}}\n\n"
                                + "data: {\"type\":\"content_block_stop\"}\n\n"
                                + "data: {\"type\":\"content_block_start\",\"content_block\":{\"type\":\"text\"}}\n\n"
                                + "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"answer\"}}\n\n"
                                + "data: {\"type\":\"content_block_stop\"}\n\n"
                                + "data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":2,\"iterations\":[{\"type\":\"compaction\",\"input_tokens\":50000,\"output_tokens\":500}]}}\n\n"
                                + "data: {\"type\":\"message_stop\"}\n\n");
            } else {
                respondSse(exchange,
                        "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":5}}}\n\n"
                                + "data: {\"type\":\"content_block_start\",\"content_block\":{\"type\":\"text\"}}\n\n"
                                + "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"next\"}}\n\n"
                                + "data: {\"type\":\"content_block_stop\"}\n\n"
                                + "data: {\"type\":\"message_stop\"}\n\n");
            }
        });
        try {
            ChatConfig config = new ChatConfig(
                    "anthropic", "test-key", "claude-test", baseUrl(server));
            DirectLlmClient client = new DirectLlmClient(config, mapper);
            client.setOutputConsumer(ignored -> { });
            client.setNativeCompactionTriggerTokens(50_000);

            DirectLlmClient.StreamResult first =
                    client.streamChat("hello", "system", null, null);
            assertEquals("native summary", first.nativeCompactionSummary);
            assertEquals(50_000, first.compactionInputTokens);
            assertEquals(500, first.compactionOutputTokens);
            assertTrue(requests.get(0).has("context_management"));
            assertTrue(first(headers.get(), "anthropic-beta").contains("compact-2026-01-12"));

            client.streamChat("continue", "system", null, null);
            assertTrue(requests.get(1).path("messages").toString()
                    .contains("\"type\":\"compaction\""));
            assertTrue(requests.get(1).path("messages").toString()
                    .contains("native summary"));
            assertFalse(requests.get(1).path("messages").toString().contains("hello"),
                    "messages covered by the native block must be removed locally");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anthropicUnsupportedNativeCompactionRetriesWithoutBetaPayload() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> requests = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server("/v1/messages", exchange -> {
            requests.add(mapper.readTree(exchange.getRequestBody()));
            if (calls.getAndIncrement() == 0) {
                respond(exchange, 400, "{\"error\":{\"message\":\"unsupported beta\"}}");
            } else {
                respondSse(exchange,
                        "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":3}}}\n\n"
                                + "data: {\"type\":\"content_block_start\",\"content_block\":{\"type\":\"text\"}}\n\n"
                                + "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}\n\n"
                                + "data: {\"type\":\"content_block_stop\"}\n\n"
                                + "data: {\"type\":\"message_stop\"}\n\n");
            }
        });
        try {
            DirectLlmClient client = new DirectLlmClient(
                    new ChatConfig("anthropic", "key", "claude-unsupported", baseUrl(server)), mapper);
            client.setOutputConsumer(ignored -> { });
            client.setNativeCompactionTriggerTokens(50_000);

            DirectLlmClient.StreamResult result =
                    client.streamChat("hello", "system", null, null);

            assertEquals("ok", result.text);
            assertEquals(2, requests.size());
            assertTrue(requests.get(0).has("context_management"));
            assertFalse(requests.get(1).has("context_management"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anthropicContextOverflowDoesNotDisableNativeCompactionAndRetryUnchanged()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger calls = new AtomicInteger();
        List<JsonNode> requests = new ArrayList<>();
        HttpServer server = server("/v1/messages", exchange -> {
            int call = calls.getAndIncrement();
            requests.add(mapper.readTree(exchange.getRequestBody()));
            if (call == 0) {
                respond(exchange, 400,
                        "{\"type\":\"error\",\"error\":{"
                                + "\"type\":\"invalid_request_error\","
                                + "\"message\":\"prompt is too long: 210000 tokens > 200000 maximum\"}}");
            } else {
                respondSse(exchange,
                        "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":3}}}\n\n"
                                + "data: {\"type\":\"content_block_start\",\"content_block\":{\"type\":\"text\"}}\n\n"
                                + "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}\n\n"
                                + "data: {\"type\":\"content_block_stop\"}\n\n"
                                + "data: {\"type\":\"message_stop\"}\n\n");
            }
        });
        try {
            DirectLlmClient client = new DirectLlmClient(
                    new ChatConfig("anthropic", "key", "claude-overflow", baseUrl(server)),
                    mapper);
            client.setOutputConsumer(ignored -> { });
            client.setNativeCompactionTriggerTokens(50_000);

            DirectLlmClient.StreamResult result =
                    client.streamChat("hello", "system", null, null);

            assertEquals(1, calls.get(),
                    "a context rejection must not resend the same payload without compaction");
            assertTrue(result.isContextOverflow());
            assertTrue(result.canRetryAfterContextOverflow());

            DirectLlmClient.StreamResult next =
                    client.streamChat("shorter follow-up", "system", null, null);
            assertEquals("ok", next.text);
            assertEquals(2, calls.get());
            assertTrue(requests.get(0).has("context_management"));
            assertTrue(requests.get(1).has("context_management"),
                    "a context rejection must not disable native compaction support");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void xaiOauthUsesBearerForOpenAiCompatibleRequest() throws Exception {
        withTemporaryHome(() -> {
            AtomicReference<Map<String, java.util.List<String>>> headers = new AtomicReference<>();
            HttpServer server = server("/chat/completions", exchange -> {
                headers.set(exchange.getRequestHeaders());
                exchange.getRequestBody().readAllBytes();
                // This test inspects request headers; avoid a 401 because production
                // correctly treats it as a signal to refresh the stored OAuth token.
                respond(exchange, 400, "{\"error\":{\"message\":\"test\"}}");
            });
            try {
                CredentialStore.create().putOAuth(
                        "xai",
                        "xai-access",
                        "xai-refresh",
                        System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1));
                ChatConfig config = new ChatConfig("xai", null, "grok-4", baseUrl(server));
                DirectLlmClient client = new DirectLlmClient(config, new ObjectMapper());
                client.setPromptCacheSessionId("xai-session-123");

                client.streamChat("hello", "system", null, null);

                assertEquals("Bearer xai-access", first(headers.get(), "Authorization"));
                assertEquals("xai-session-123", first(headers.get(), "x-grok-conv-id"));
            } finally {
                server.stop(0);
            }
        });
    }

    @Test
    void openAiLongCachePolicySendsAffinityAndExtendedRetention() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        HttpServer server = server("/chat/completions", exchange -> {
            requestBody.set(mapper.readTree(exchange.getRequestBody()));
            respondSse(exchange,
                    "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},"
                            + "\"finish_reason\":\"stop\"}],\"usage\":{"
                            + "\"prompt_tokens\":20,\"completion_tokens\":1,"
                            + "\"prompt_tokens_details\":{\"cached_tokens\":10}}}\n\n"
                            + "data: [DONE]\n\n");
        });
        try {
            ChatConfig config = new ChatConfig(
                    "openai", "key", "gpt-5.4", baseUrl(server));
            config.setPromptCacheRetention("long");
            DirectLlmClient client = new DirectLlmClient(config, mapper, tempDir);
            client.setOutputConsumer(ignored -> { });
            client.setPromptCacheSessionId("openai-session-123");

            DirectLlmClient.StreamResult result =
                    client.streamChat("hello", "system", null, null);

            assertEquals("ok", result.text);
            assertEquals(10, result.inputTokens);
            assertEquals(10, result.cacheReadTokens);
            assertEquals("openai-session-123",
                    requestBody.get().path("prompt_cache_key").asText());
            assertEquals("24h",
                    requestBody.get().path("prompt_cache_retention").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void openAiShortCachePolicySelectsInMemoryRetentionOnEarlierModels() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        HttpServer server = server("/chat/completions", exchange -> {
            requestBody.set(mapper.readTree(exchange.getRequestBody()));
            respondSse(exchange, "data: [DONE]\n\n");
        });
        try {
            ChatConfig config = new ChatConfig(
                    "openai", "key", "gpt-5.4", baseUrl(server));
            DirectLlmClient client = new DirectLlmClient(config, mapper, tempDir);
            client.setOutputConsumer(ignored -> { });

            client.streamChat("hello", "system", null, null);

            assertEquals("in_memory",
                    requestBody.get().path("prompt_cache_retention").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void openAiShortCachePolicyUsesTheOnlyRetentionSupportedByGpt55() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        HttpServer server = server("/chat/completions", exchange -> {
            requestBody.set(mapper.readTree(exchange.getRequestBody()));
            respondSse(exchange, "data: [DONE]\n\n");
        });
        try {
            ChatConfig config = new ChatConfig(
                    "openai", "key", "gpt-5.5", baseUrl(server));
            DirectLlmClient client = new DirectLlmClient(config, mapper, tempDir);
            client.setOutputConsumer(ignored -> { });

            client.streamChat("hello", "system", null, null);

            assertEquals("24h",
                    requestBody.get().path("prompt_cache_retention").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void openAiLongCachePolicyFallsBackToInMemoryWhenModelLacks24h() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        HttpServer server = server("/chat/completions", exchange -> {
            requestBody.set(mapper.readTree(exchange.getRequestBody()));
            respondSse(exchange, "data: [DONE]\n\n");
        });
        try {
            ChatConfig config = new ChatConfig(
                    "openai", "key", "gpt-4o", baseUrl(server));
            config.setPromptCacheRetention("long");
            DirectLlmClient client = new DirectLlmClient(config, mapper, tempDir);
            client.setOutputConsumer(ignored -> { });

            client.streamChat("hello", "system", null, null);

            assertEquals("in_memory",
                    requestBody.get().path("prompt_cache_retention").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void openAiShortCachePolicySelectsImplicitModeOnGpt56AndLater() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        HttpServer server = server("/chat/completions", exchange -> {
            requestBody.set(mapper.readTree(exchange.getRequestBody()));
            respondSse(exchange, "data: [DONE]\n\n");
        });
        try {
            ChatConfig config = new ChatConfig(
                    "openai", "key", "gpt-5.6-terra", baseUrl(server));
            DirectLlmClient client = new DirectLlmClient(config, mapper, tempDir);
            client.setOutputConsumer(ignored -> { });

            client.streamChat("hello", "system", null, null);

            JsonNode options = requestBody.get().path("prompt_cache_options");
            assertEquals("implicit", options.path("mode").asText());
            assertFalse(options.has("ttl"), "short policy uses OpenAI's default 30-minute TTL");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void openAiLongCachePolicySelectsImplicitModeAndSupportedGpt56Ttl() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        HttpServer server = server("/chat/completions", exchange -> {
            requestBody.set(mapper.readTree(exchange.getRequestBody()));
            respondSse(exchange, "data: [DONE]\n\n");
        });
        try {
            ChatConfig config = new ChatConfig(
                    "openai", "key", "gpt-5.6-terra", baseUrl(server));
            config.setPromptCacheRetention("long");
            DirectLlmClient client = new DirectLlmClient(config, mapper, tempDir);
            client.setOutputConsumer(ignored -> { });

            client.streamChat("hello", "system", null, null);

            JsonNode options = requestBody.get().path("prompt_cache_options");
            assertEquals("implicit", options.path("mode").asText());
            assertEquals("30m", options.path("ttl").asText());
            assertFalse(requestBody.get().has("prompt_cache_retention"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void openAiDisabledCachePolicySuppressesAffinityAndImplicitCaching() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        HttpServer server = server("/chat/completions", exchange -> {
            requestBody.set(mapper.readTree(exchange.getRequestBody()));
            respondSse(exchange, "data: [DONE]\n\n");
        });
        try {
            ChatConfig config = new ChatConfig(
                    "openai", "key", "gpt-5.6-terra", baseUrl(server));
            config.setPromptCacheRetention("none");
            DirectLlmClient client = new DirectLlmClient(config, mapper, tempDir);
            client.setOutputConsumer(ignored -> { });
            client.setPromptCacheSessionId("must-not-be-sent");

            client.streamChat("hello", "system", null, null);

            assertFalse(requestBody.get().has("prompt_cache_key"));
            assertEquals("explicit",
                    requestBody.get().path("prompt_cache_options").path("mode").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void oneShotUsesDistinctBoundedPromptCacheAffinity() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        HttpServer server = server("/chat/completions", exchange -> {
            requestBody.set(mapper.readTree(exchange.getRequestBody()));
            respondSse(exchange,
                    "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},"
                            + "\"finish_reason\":\"stop\"}]}\n\n"
                            + "data: [DONE]\n\n");
        });
        try {
            ChatConfig config = new ChatConfig(
                    "openai", "key", "gpt-5.4", baseUrl(server));
            DirectLlmClient client = new DirectLlmClient(config, mapper, tempDir);
            client.setOutputConsumer(ignored -> { });
            String liveAffinity = "x".repeat(64);
            client.setPromptCacheSessionId(liveAffinity);

            DirectLlmClient.StreamResult result =
                    client.streamOneShot("summarize", "utility system", null);

            assertEquals("ok", result.text);
            String utilityAffinity = requestBody.get().path("prompt_cache_key").asText();
            assertTrue(utilityAffinity.startsWith("utility:"));
            assertTrue(utilityAffinity.length() <= 64);
            assertNotEquals(liveAffinity, utilityAffinity);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void openRouterUsesSessionAffinityAndOnlyMarksAnthropicRoutesExplicitly()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> requests = new ArrayList<>();
        List<Map<String, java.util.List<String>>> headers = new ArrayList<>();
        HttpServer server = server("/chat/completions", exchange -> {
            headers.add(exchange.getRequestHeaders());
            requests.add(mapper.readTree(exchange.getRequestBody()));
            respondSse(exchange, "data: [DONE]\n\n");
        });
        try {
            ChatConfig config = new ChatConfig(
                    "openrouter", "key", "anthropic/claude-sonnet-4", baseUrl(server));
            config.setPromptCacheRetention("long");
            DirectLlmClient client = new DirectLlmClient(config, mapper, tempDir);
            client.setOutputConsumer(ignored -> { });
            client.setPromptCacheSessionId("router-session-123");

            client.streamChat("one", "system", null, null);
            config.setModel("openai/gpt-5.4");
            client.streamChat("two", "system", null, null);

            assertEquals("router-session-123", first(headers.get(0), "x-session-id"));
            assertEquals("ephemeral",
                    requests.get(0).path("cache_control").path("type").asText());
            assertEquals("1h",
                    requests.get(0).path("cache_control").path("ttl").asText());
            assertFalse(requests.get(1).has("cache_control"),
                    "OpenRouter automatic-cache routes must not receive Anthropic controls");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unauthenticatedOpenAiCompatibleEndpointReceivesNoAuthorizationHeader()
            throws Exception {
        AtomicReference<Map<String, java.util.List<String>>> headers = new AtomicReference<>();
        HttpServer server = server("/chat/completions", exchange -> {
            headers.set(exchange.getRequestHeaders());
            exchange.getRequestBody().readAllBytes();
            respondSse(exchange,
                    "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n"
                            + "data: [DONE]\n\n");
        });
        try {
            ChatConfig config = new ChatConfig(
                    "custom", null, "custom-model", baseUrl(server) + "/");
            DirectLlmClient client = new DirectLlmClient(config, new ObjectMapper(), tempDir);
            client.setOutputConsumer(ignored -> { });

            DirectLlmClient.StreamResult result =
                    client.streamChat("hello", "system", null, null);

            assertEquals("ok", result.text);
            assertNull(first(headers.get(), "Authorization"));
            assertNull(first(headers.get(), "x-api-key"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void githubCopilotOauthAddsRequiredClientHeaders() throws Exception {
        withTemporaryHome(() -> {
            AtomicReference<Map<String, java.util.List<String>>> headers = new AtomicReference<>();
            HttpServer server = server("/chat/completions", exchange -> {
                headers.set(exchange.getRequestHeaders());
                exchange.getRequestBody().readAllBytes();
                // Header-only fixture: a 401 would intentionally enter OAuth refresh.
                respond(exchange, 400, "{\"error\":{\"message\":\"test\"}}");
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
                // A value saved by older pickers must migrate even though max is now the ceiling.
                config.setThinking("ultra");
                DirectLlmClient client = new DirectLlmClient(config, mapper);
                client.setOutputConsumer(ignored -> {});
                client.setPromptCacheSessionId("codex-session-123");

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
                assertEquals("max", firstRequest.path("reasoning").path("effort").asText());
                assertFalse(firstRequest.path("store").asBoolean(true));
                assertEquals("codex-session-123",
                        firstRequest.path("prompt_cache_key").asText());
                assertFalse(firstRequest.has("prompt_cache_retention"));
                assertFalse(firstRequest.has("prompt_cache_options"),
                        "the ChatGPT/Codex subscription endpoint owns cache retention");
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
    void openAiResponsesCompactionItemIsRequestedCapturedAndReplayed() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> requests = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server("/codex/responses", exchange -> {
            requests.add(mapper.readTree(exchange.getRequestBody()));
            if (calls.getAndIncrement() == 0) {
                respondSse(exchange,
                        "data: {\"type\":\"response.output_item.done\",\"output_index\":0,"
                                + "\"item\":{\"type\":\"compaction\",\"id\":\"cmp_1\","
                                + "\"encrypted_content\":\"opaque-context\"}}\n\n"
                                + "data: {\"type\":\"response.output_text.delta\",\"output_index\":1,"
                                + "\"delta\":\"answer\"}\n\n"
                                + "data: {\"type\":\"response.completed\",\"response\":{"
                                + "\"status\":\"completed\",\"output\":[{\"type\":\"compaction\","
                                + "\"id\":\"cmp_1\",\"encrypted_content\":\"opaque-context\"}],"
                                + "\"usage\":{\"input_tokens\":20,\"output_tokens\":2}}}\n\n");
            } else {
                respondSse(exchange,
                        "data: {\"type\":\"response.output_text.delta\",\"output_index\":0,"
                                + "\"delta\":\"next\"}\n\n"
                                + "data: {\"type\":\"response.completed\",\"response\":{"
                                + "\"status\":\"completed\",\"usage\":{\"input_tokens\":4,"
                                + "\"output_tokens\":1}}}\n\n");
            }
        });
        try {
            ChatConfig config = new ChatConfig(
                    "openai-codex", "key", "gpt-test", baseUrl(server));
            DirectLlmClient client = new DirectLlmClient(config, mapper);
            client.setOutputConsumer(ignored -> { });
            client.setNativeCompactionTriggerTokens(10_000);

            DirectLlmClient.StreamResult first =
                    client.streamChat("hello", "system", null, null);
            assertEquals("compaction", first.nativeCompactionPayload.path("type").asText());
            assertEquals("opaque-context",
                    first.nativeCompactionPayload.path("encrypted_content").asText());
            assertEquals(10_000, requests.get(0).path("context_management")
                    .path(0).path("compact_threshold").asInt());

            client.streamChat("continue", "system", null, null);
            assertTrue(requests.get(1).path("input").toString().contains("opaque-context"));
            assertFalse(requests.get(1).path("input").toString().contains("hello"),
                    "Responses input before the compaction item must not be replayed");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void responsesContextOverflowIsNotRetriedAsUnsupportedNativeCompaction()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> requests = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server("/codex/responses", exchange -> {
            requests.add(mapper.readTree(exchange.getRequestBody()));
            if (calls.getAndIncrement() == 0) {
                respond(exchange, 400,
                        "{\"error\":{\"code\":\"context_length_exceeded\","
                                + "\"message\":\"Maximum context length exceeded\"}}");
            } else {
                respondSse(exchange,
                        "data: {\"type\":\"response.output_text.delta\","
                                + "\"output_index\":0,\"delta\":\"ok\"}\n\n"
                                + "data: {\"type\":\"response.completed\",\"response\":{"
                                + "\"status\":\"completed\",\"usage\":{"
                                + "\"input_tokens\":4,\"output_tokens\":1}}}\n\n");
            }
        });
        try {
            DirectLlmClient client = new DirectLlmClient(
                    new ChatConfig("openai-codex", "key", "gpt-test", baseUrl(server)),
                    mapper);
            client.setOutputConsumer(ignored -> { });
            client.setNativeCompactionTriggerTokens(10_000);

            DirectLlmClient.StreamResult overflow =
                    client.streamChat("hello", "system", null, null);
            assertEquals(1, calls.get());
            assertTrue(overflow.isContextOverflow());

            DirectLlmClient.StreamResult next =
                    client.streamChat("shorter", "system", null, null);
            assertEquals("ok", next.text);
            assertEquals(2, calls.get());
            assertTrue(requests.get(0).has("context_management"));
            assertTrue(requests.get(1).has("context_management"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void explicitResponsesCompactionReturnsCanonicalPayloadWithoutEarlyMutation() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<JsonNode> request = new AtomicReference<>();
        HttpServer server = server("/codex/responses/compact", exchange -> {
            request.set(mapper.readTree(exchange.getRequestBody()));
            respond(exchange, 200,
                    "{\"output\":[{\"type\":\"compaction\",\"id\":\"cmp_manual\","
                            + "\"encrypted_content\":\"manual-opaque\"}]}");
        });
        try {
            DirectLlmClient client = new DirectLlmClient(
                    new ChatConfig("openai-codex", "key", "gpt-test", baseUrl(server)), mapper);
            client.addToHistory("user", "long prior context");

            DirectLlmClient.NativeCompactionResult result = client.tryNativeCompact(null);

            assertTrue(result.applied());
            assertEquals("manual-opaque",
                    result.nativePayload().path(0).path("encrypted_content").asText());
            assertTrue(request.get().path("input").toString().contains("long prior context"));
            assertEquals(1, client.getHistorySize(),
                    "wire history changes only after the ledger checkpoint commits");
        } finally {
            server.stop(0);
        }
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
            client.setPromptCacheSessionId("radius-session-123");

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
            assertEquals("short", firstRequest.path("options")
                    .path("cacheRetention").asText());
            assertEquals("radius-session-123", firstRequest.path("options")
                    .path("sessionId").asText());
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
    void radiusRetriesPendingToolResultWithoutDuplicatingIt() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> requests = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/config", exchange -> {
            try {
                respond(exchange, 200,
                        "{\"baseUrl\":\"" + baseUrl(server) + "/pi\","
                                + "\"models\":[{\"id\":\"radius-model\"}]}");
            } finally {
                exchange.close();
            }
        });
        server.createContext("/pi/messages", exchange -> {
            try {
                requests.add(mapper.readTree(exchange.getRequestBody()));
                if (calls.getAndIncrement() == 0) {
                    respond(exchange, 503, "{\"error\":{\"message\":\"retry\"}}");
                } else {
                    respondSse(exchange,
                            "data: {\"type\":\"text_delta\",\"contentIndex\":0,"
                                    + "\"delta\":\"ok\"}\n\n"
                                    + "data: {\"type\":\"done\",\"reason\":\"stop\","
                                    + "\"usage\":{}}\n\n");
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
                    new ChatConfig("radius", "key", "radius-model", baseUrl(server)),
                    mapper, policy);
            client.setOutputConsumer(ignored -> { });
            List<DirectLlmClient.ToolCallResultInput> toolResults = List.of(
                    new DirectLlmClient.ToolCallResultInput(
                            "call_radius", "read", "contents", false));

            DirectLlmClient.StreamResult result =
                    client.streamChat(null, "system", null, toolResults);

            assertEquals("ok", result.text);
            assertEquals(2, requests.size());
            for (JsonNode request : requests) {
                long resultCount = java.util.stream.StreamSupport.stream(
                                request.path("context").path("messages").spliterator(), false)
                        .filter(message -> "toolResult".equals(message.path("role").asText()))
                        .count();
                assertEquals(1, resultCount,
                        "each retry request must contain the pending result exactly once");
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void githubCopilotRoutesModelsToTheirPiApiFamilies() throws Exception {
        withTemporaryHome(() -> {
            AtomicReference<Map<String, java.util.List<String>>> responsesHeaders = new AtomicReference<>();
            AtomicReference<JsonNode> responsesBody = new AtomicReference<>();
            AtomicReference<Map<String, java.util.List<String>>> anthropicHeaders = new AtomicReference<>();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/responses", exchange -> {
                try {
                    responsesHeaders.set(exchange.getRequestHeaders());
                    responsesBody.set(mapper().readTree(exchange.getRequestBody()));
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
                    respond(exchange, 400, "{\"error\":{\"message\":\"test\"}}");
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
                responsesConfig.setPromptCacheRetention("long");
                DirectLlmClient responsesClient = new DirectLlmClient(responsesConfig, mapper());
                responsesClient.setOutputConsumer(ignored -> {});
                responsesClient.streamChat("hello", "system", null, null);

                ChatConfig anthropicConfig = new ChatConfig(
                        "github-copilot", null, "claude-sonnet-4.6", baseUrl(server));
                DirectLlmClient anthropicClient = new DirectLlmClient(anthropicConfig, mapper());
                anthropicClient.streamChat("hello", "system", null, null);

                assertEquals("user", first(responsesHeaders.get(), "X-Initiator"));
                assertEquals("conversation-edits", first(responsesHeaders.get(), "Openai-Intent"));
                assertFalse(responsesBody.get().has("prompt_cache_key"));
                assertFalse(responsesBody.get().has("prompt_cache_retention"));
                assertFalse(responsesBody.get().has("prompt_cache_options"),
                        "delegated providers must own their cache wire controls");
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
            respond(exchange, 400, "{\"error\":{\"message\":\"test\"}}");
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
    void oneShotJsonAddsResponsesStructuredOutputWithoutReasoning() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = server("/codex/responses", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 400, "{\"error\":{\"message\":\"test\"}}");
        });
        try {
            ChatConfig config = new ChatConfig(
                    "openai-codex", "test-token", "gpt-5.6-sol", baseUrl(server));
            DirectLlmClient client = new DirectLlmClient(config, mapper);
            JsonNode schema = mapper.createObjectNode()
                    .put("type", "object")
                    .put("additionalProperties", false)
                    .set("properties", mapper.createObjectNode()
                            .set("allowed", mapper.createObjectNode().put("type", "boolean")));

            client.streamOneShotJson(
                    "review", "return JSON", null, "judge verdict", schema, true);

            JsonNode request = mapper.readTree(requestBody.get());
            JsonNode format = request.path("text").path("format");
            assertEquals("low", request.path("text").path("verbosity").asText());
            assertEquals("json_schema", format.path("type").asText());
            assertEquals("judge_verdict", format.path("name").asText());
            assertTrue(format.path("strict").asBoolean());
            assertEquals("object", format.path("schema").path("type").asText());
            assertFalse(request.has("reasoning"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void codexStrictSchemaWirePayloadKeepsTypesAndRemovesUnsupportedBounds() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = server("/codex/responses", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            // The original provider response had no body; do not invent an unsupported-keyword
            // diagnostic when this wire test only inspects the request payload.
            respond(exchange, 400, "");
        });
        try {
            ChatConfig config = new ChatConfig(
                    "openai-codex", "test-token", "gpt-5.6-sol", baseUrl(server));
            config.setThinking("xhigh");
            DirectLlmClient client = new DirectLlmClient(config, mapper);
            JsonNode schema = mapper.readTree("{"
                    + "\"type\":\"object\","
                    + "\"properties\":{\"nodeTypes\":{"
                    + "\"type\":\"array\",\"items\":{"
                    + "\"type\":\"object\",\"properties\":{"
                    + "\"label\":{\"type\":\"string\",\"pattern\":\"^[A-Z]+$\",\"maxLength\":48},"
                    + "\"parentType\":{\"type\":\"string\",\"enum\":[\"PERSON\"]}},"
                    + "\"required\":[\"label\",\"parentType\"],\"additionalProperties\":false},"
                    + "\"uniqueItems\":true,\"maxItems\":32},"
                    + "\"pattern\":{\"type\":\"string\"},"
                    + "\"minimum\":{\"type\":\"integer\",\"minimum\":0},"
                    + "\"default\":{\"type\":\"object\",\"default\":{"
                    + "\"pattern\":\"payload\",\"minimum\":7}}},"
                    + "\"$defs\":{\"minimum\":{\"type\":\"object\",\"properties\":{"
                    + "\"default\":{\"type\":\"object\",\"enum\":[{"
                    + "\"pattern\":\"enum-payload\",\"minimum\":3}]}}},"
                    + "\"required\":[\"default\"],\"additionalProperties\":false},"
                    + "\"required\":[\"nodeTypes\",\"pattern\",\"minimum\",\"default\"],"
                    + "\"additionalProperties\":false}");
            JsonNode originalSchema = schema.deepCopy();

            DirectLlmClient.StreamResult result = client.streamOneShotJson(
                    "review", "return JSON", null, "schema-prepass", schema, true);

            assertTrue(result.failed);
            assertEquals(400, result.failureStatusCode);
            assertFalse(result.text.contains("invalid_json_schema"), result.text);
            assertEquals(originalSchema, schema,
                    "transport normalization must not mutate the local schema");
            JsonNode request = mapper.readTree(requestBody.get());
            assertEquals("gpt-5.6-sol", request.path("model").asText());
            assertEquals("xhigh", request.path("reasoning").path("effort").asText());
            JsonNode wire = request.path("text").path("format").path("schema");
            assertEquals(List.of("nodeTypes", "pattern", "minimum", "default"), mapper.convertValue(
                    wire.path("required"), List.class));
            assertFalse(wire.path("additionalProperties").asBoolean());
            JsonNode item = wire.path("properties").path("nodeTypes").path("items");
            assertEquals("object", item.path("type").asText());
            assertEquals(List.of("label", "parentType"), mapper.convertValue(
                    item.path("required"), List.class));
            assertEquals(List.of("PERSON"), mapper.convertValue(
                    item.path("properties").path("parentType").path("enum"), List.class));
            assertFalse(item.path("properties").path("label").has("pattern"));
            assertFalse(item.path("properties").path("label").has("maxLength"));
            assertFalse(wire.path("properties").path("nodeTypes").has("uniqueItems"));
            assertFalse(wire.path("properties").path("nodeTypes").has("maxItems"));
            assertTrue(wire.path("properties").has("pattern"));
            assertTrue(wire.path("properties").has("minimum"));
            assertTrue(wire.path("properties").has("default"));
            assertFalse(wire.path("properties").path("minimum").has("minimum"));
            assertEquals("payload", wire.path("properties").path("default")
                    .path("default").path("pattern").asText());
            assertTrue(wire.path("$defs").has("minimum"));
            assertTrue(wire.path("$defs").path("minimum").path("properties").has("default"));
            assertEquals("enum-payload", wire.path("$defs").path("minimum")
                    .path("properties").path("default").path("enum").path(0)
                    .path("pattern").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void oneShotJsonAddsChatCompletionsStructuredOutput() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = server("/chat/completions", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 400, "{\"error\":{\"message\":\"test\"}}");
        });
        try {
            ChatConfig config = new ChatConfig(
                    "openai", "test-key", "gpt-4o", baseUrl(server));
            DirectLlmClient client = new DirectLlmClient(config, mapper);
            JsonNode schema = mapper.createObjectNode()
                    .put("type", "object")
                    .put("additionalProperties", false)
                    .set("properties", mapper.createObjectNode());

            client.streamOneShotJson(
                    "review", "return JSON", null, "judge", schema, false);

            JsonNode responseFormat = mapper.readTree(requestBody.get()).path("response_format");
            JsonNode jsonSchema = responseFormat.path("json_schema");
            assertEquals("json_schema", responseFormat.path("type").asText());
            assertEquals("judge", jsonSchema.path("name").asText());
            assertFalse(jsonSchema.path("strict").asBoolean(true));
            assertEquals("object", jsonSchema.path("schema").path("type").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void genericOpenAiStrictSchemaRetainsValidationKeywords() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = server("/chat/completions", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 400, "");
        });
        try {
            ChatConfig config = new ChatConfig("openai", "test-key", "gpt-4o", baseUrl(server));
            DirectLlmClient client = new DirectLlmClient(config, mapper);
            JsonNode schema = mapper.readTree("{\"type\":\"object\",\"properties\":{"
                    + "\"pattern\":{\"type\":\"string\",\"pattern\":\"^x\"},"
                    + "\"minimum\":{\"type\":\"number\",\"minimum\":0},"
                    + "\"default\":{\"type\":\"object\",\"default\":{\"pattern\":\"keep\"}}"
                    + "},\"required\":[\"pattern\",\"minimum\",\"default\"],"
                    + "\"additionalProperties\":false}");

            client.streamOneShotJson("review", "return JSON", null, "judge", schema, true);

            JsonNode wire = mapper.readTree(requestBody.get()).path("response_format")
                    .path("json_schema").path("schema");
            assertTrue(wire.path("properties").path("pattern").has("pattern"));
            assertEquals(0, wire.path("properties").path("minimum").path("minimum").asInt());
            assertEquals("keep", wire.path("properties").path("default")
                    .path("default").path("pattern").asText());
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
        assertEquals(0, ChatConfig.getDefaultModels("openai-codex").length);
        assertEquals(0, ChatConfig.getDefaultModels("radius").length);
    }

    @Test
    void expiredNonRefreshableAuthFailsBeforeHttpAndReloginRepairsSameClient() throws Exception {
        withTemporaryHome(() -> {
            AtomicInteger calls = new AtomicInteger();
            HttpServer server = server("/v1/messages", exchange -> {
                exchange.getRequestBody().readAllBytes();
                calls.incrementAndGet();
                respondSse(exchange, """
                        data: {"type":"message_start","message":{"usage":{"input_tokens":1}}}

                        data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"recovered"}}

                        data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}

                        data: {"type":"message_stop"}

                        """);
            });
            try {
                CredentialStore store = CredentialStore.create();
                Map<String, String> identity = Map.of("accountId", "test-account");
                store.put("anthropic", "personal", ManagedCredential.oauth("expired-secret", "", 1L, identity), true);
                ChatConfig config = new ChatConfig("anthropic", null, "claude-sonnet-4-6", baseUrl(server));
                config.setAuthenticationMethod("oauth");
                try (DirectLlmClient client = new DirectLlmClient(config, new ObjectMapper())) {
                    client.setOutputConsumer(ignored -> { });
                    DirectLlmClient.StreamResult rejected = client.streamChat("hello", "system", null, null);
                    assertTrue(rejected.failed);
                    assertEquals(DirectLlmClient.FailureKind.AUTHENTICATION, rejected.failureKind);
                    assertTrue(rejected.failureMessage.contains("kompile auth login anthropic"));
                    assertFalse(rejected.text.contains("expired-secret"));
                    assertEquals(0, calls.get());
                    store.put("anthropic", "another-name", ManagedCredential.oauth(
                            "new-secret", "new-refresh", System.currentTimeMillis() + 3_600_000L, identity), true);
                    DirectLlmClient.StreamResult recovered = client.streamChat("hello", "system", null, null);
                    assertFalse(recovered.failed, recovered.text);
                    assertEquals("recovered", recovered.text);
                    assertEquals(1, calls.get());
                    assertEquals(1, store.list("anthropic").size());
                    assertEquals("personal", store.activeCredentialName("anthropic"));
                }
            } finally {
                server.stop(0);
            }
        });
    }

    @Test
    void anthropicAndRadius401sRefreshOnceAndPinTheReturnedCredential() throws Exception {
        // Exercise both Radius entry points and Claude, including a failed replay.
        for (String route : List.of("anthropic", "radius-config", "radius-messages")) {
            for (boolean rejectReplay : List.of(false, true)) {
                boolean radius = route.startsWith("radius");
                String provider = radius ? "radius" : "anthropic";
                AtomicInteger refreshes = new AtomicInteger();
                AtomicInteger rejectedCalls = new AtomicInteger();
                List<String> tokens = new ArrayList<>();
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                Handler endpoint = exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    String token = exchange.getRequestHeaders().getFirst("Authorization");
                    tokens.add(token);
                    boolean configRequest = exchange.getRequestURI().getPath().endsWith("config");
                    boolean rejectHere = configRequest == "radius-config".equals(route);
                    if (rejectHere && ("Bearer old".equals(token) || rejectReplay)) {
                        rejectedCalls.incrementAndGet();
                        respond(exchange, 401, "{\"error\":{\"message\":\"secret-reflected-token\"}}");
                    } else if (configRequest) {
                        respond(exchange, 200, "{\"baseUrl\":\"" + baseUrl(server)
                                + "/pi\",\"models\":[{\"id\":\"test-model\"}]}");
                    } else if (radius) {
                        respondSse(exchange, "data: {\"type\":\"text_delta\",\"contentIndex\":0,\"delta\":\"ok\"}\n\n"
                                + "data: {\"type\":\"done\",\"reason\":\"stop\"}\n\n");
                    } else {
                        respondSse(exchange, "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}\n\n"
                                + "data: {\"type\":\"message_stop\"}\n\n");
                    }
                };
                for (String path : List.of("/v1/messages", "/v1/config", "/pi/messages")) {
                    server.createContext(path, exchange -> {
                        try { endpoint.handle(exchange); } finally { exchange.close(); }
                    });
                }
                server.start();
                ChatConfig config = new ChatConfig(provider, null, "test-model", baseUrl(server)) {
                    @Override
                    public ai.kompile.cli.main.auth.oauth.OAuthProviderFlow.RequestAuth resolveRequestAuth() {
                        String token = refreshes.get() == 0 ? "old" : "wrong-account";
                        return ai.kompile.cli.main.auth.oauth.OAuthProviderFlow.RequestAuth.oauth(
                                token, null, Map.of("Authorization", "Bearer " + token));
                    }

                    @Override
                    public ai.kompile.cli.main.auth.oauth.OAuthProviderFlow.RequestAuth refreshRequestAuthAfterUnauthorized(
                            ai.kompile.cli.main.auth.oauth.OAuthProviderFlow.RequestAuth rejected) {
                        assertEquals("old", rejected.token());
                        refreshes.incrementAndGet();
                        // Another account becomes active immediately after refresh. The
                        // retry must use this return value, NOT resolveRequestAuth again.
                        return ai.kompile.cli.main.auth.oauth.OAuthProviderFlow.RequestAuth.oauth(
                                "new", null, Map.of("Authorization", "Bearer new"));
                    }
                };
                try (DirectLlmClient client = new DirectLlmClient(config, new ObjectMapper())) {
                    client.setOutputConsumer(ignored -> { });
                    var result = client.streamChat("hello", "system", null, null);
                    assertEquals(1, refreshes.get(), route + ": " + result.text);
                    assertEquals(rejectReplay ? 2 : 1, rejectedCalls.get(), route);
                    assertEquals(rejectReplay, result.failed, result.text);
                    assertFalse(tokens.contains("Bearer wrong-account"), route);
                    assertFalse(result.text.contains("secret-reflected-token"));
                    if (rejectReplay) assertEquals(DirectLlmClient.FailureKind.AUTHENTICATION, result.failureKind);
                    else assertEquals("ok", result.text);
                } finally {
                    server.stop(0);
                }
            }
        }
    }

    @Test
    void missingExplicitOauthDoesNotSendAnonymousRequests() throws Exception {
        withTemporaryHome(() -> {
            ChatConfig config = new ChatConfig("openai-codex", null, "gpt-5.5", "http://127.0.0.1:1");
            config.setAuthenticationMethod("oauth");
            assertThrows(ChatConfig.AuthenticationException.class, config::resolveRequestAuth);
            try (DirectLlmClient client = new DirectLlmClient(config, new ObjectMapper())) {
                client.setOutputConsumer(ignored -> { });
                var result = client.streamChat("hello", "system", null, null);
                assertTrue(result.failed);
                assertEquals(DirectLlmClient.FailureKind.AUTHENTICATION, result.failureKind);
                assertEquals(0, result.failureStatusCode);
            }
        });
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
