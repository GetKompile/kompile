package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Wire fixtures: counts are provider snapshots; listeners receive settled deltas. */
@TemporaryUserHome
class DirectLlmClientUsageTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ProviderConnectivityPolicy FAST_POLICY = new ProviderConnectivityPolicy(
            Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofMillis(100),
            Duration.ofSeconds(1), 3, Duration.ofMillis(5), Duration.ofMillis(20));

    @Test
    void compatibleVendorsUseInclusivePromptAndOutputTotalsExactlyOnce() throws Exception {
        // Gemini and Ollama/custom use /chat/completions here, NOT their native
        // usageMetadata or prompt_eval_count/eval_count dialects.
        for (String provider : List.of("openai", "gemini", "deepseek", "zai", "xai", "openrouter",
                "groq", "custom", "ollama", "github-copilot")) {
            try (Fixture server = fixture("/chat/completions", exchange -> sse(exchange, """
                    data: {"choices":[{"delta":{"reasoning_content":"thinking"}}],"usage":null}

                    data: {"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":6,"prompt_tokens_details":{"cached_tokens":60,"cache_write_tokens":10}}}

                    data: {"choices":[{"delta":{"content":"answer"},"finish_reason":"stop"}],"usage":null}

                    data: {"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":20,"prompt_tokens_details":{"cached_tokens":60,"cache_write_tokens":10},"completion_tokens_details":{"reasoning_tokens":12}}}

                    data: {"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":20,"prompt_tokens_details":{"cached_tokens":60,"cache_write_tokens":10}}}

                    data: {"choices":[],"usage":null}

                    data: [DONE]

                    """)); DirectLlmClient client = client(provider, "test-model", server)) {
                UsageListener listener = listen(client);
                var result = client.streamChat("hi", "system", null, null);
                assertFalse(result.failed, provider + ": " + result.failureMessage);
                assertUsage(result, 30, 20, 60, 10);
                assertEquals(100, result.contextInputTokens(), provider);
                assertEquals(List.of(new Usage(30, 20, 60, 10)), listener.events, provider);
            }
        }
    }

    @Test
    void deepSeekMissIsOrdinaryInputAndNullDetailFallsBackToHitCount() throws Exception {
        var result = new DirectLlmClient.StreamResult();
        DirectLlmClient.readOpenAiCompatibleUsage(MAPPER.readTree("""
                {"prompt_tokens":100,"prompt_cache_hit_tokens":70,"prompt_cache_miss_tokens":30,
                 "prompt_tokens_details":{"cached_tokens":null},"completion_tokens":20,
                 "completion_tokens_details":{"reasoning_tokens":12}}
                """), result);
        assertUsage(result, 30, 20, 70, 0);
        DirectLlmClient.readOpenAiCompatibleUsage(MAPPER.readTree("null"), result);
        DirectLlmClient.readOpenAiCompatibleUsage(MAPPER.readTree("{}"), result);
        DirectLlmClient.readOpenAiCompatibleUsage(MAPPER.readTree("{\"completion_tokens\":25}"), result);
        assertUsage(result, 30, 25, 70, 0);
    }

    @Test
    void xaiAddsReasoningOnlyWhenItsWireTotalShowsItIsSeparate() throws Exception {
        // xAI legacy documentation: input=716, completion=126, reasoning=167,
        // total=1009. Other providers (and inclusive xAI frames) include reasoning.
        for (String provider : List.of("xai", "openai")) {
            for (int completion : List.of(126, 293)) {
                String body = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":716,\"completion_tokens\":" + completion
                        + ",\"total_tokens\":1009,\"completion_tokens_details\":{\"reasoning_tokens\":167}}}\n\n";
                try (Fixture server = fixture("/chat/completions", exchange -> sse(exchange,
                        body + body + "data: [DONE]\n\n"));
                     DirectLlmClient client = client(provider, "test-model", server)) {
                    UsageListener listener = listen(client);
                    var result = client.streamChat("hi", "system", null, null);
                    long output = "xai".equals(provider) ? 293 : completion;
                    assertFalse(result.failed, result.failureMessage);
                    assertUsage(result, 716, output, 0, 0);
                    assertEquals(List.of(new Usage(716, output, 0, 0)), listener.events);
                }
            }
        }
    }

    @Test
    void groqReadsLegacyNestedUsageWithoutAddingTheTopLevelAlias() throws Exception {
        for (String topLevel : List.of("null", "{\"prompt_tokens\":100,\"completion_tokens\":20}")) {
            String body = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":" + topLevel + ",\"x_groq\":{\"usage\":{\"prompt_tokens\":100,"
                    + "\"completion_tokens\":20}}}\n\ndata: [DONE]\n\n";
            try (Fixture server = fixture("/chat/completions", exchange -> sse(exchange, body));
                 DirectLlmClient client = client("groq", "test-model", server)) {
                UsageListener listener = listen(client);
                var result = client.streamChat("hi", "system", null, null);
                assertFalse(result.failed);
                assertUsage(result, 100, 20, 0, 0);
                assertEquals(List.of(new Usage(100, 20, 0, 0)), listener.events);
            }
        }
    }

    @Test
    void lengthAndRefusalStillReadTrailingUsageChunk() throws Exception {
        for (String reason : List.of("length", "content_filter")) {
            String body = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"" + reason
                    + "\"}]}\n\ndata: {\"choices\":[],\"usage\":{\"prompt_tokens\":100,"
                    + "\"completion_tokens\":20,\"prompt_tokens_details\":{\"cached_tokens\":60}}}\n\n"
                    + "data: [DONE]\n\n";
            try (Fixture server = fixture("/chat/completions", exchange -> sse(exchange, body));
                 DirectLlmClient client = client("openai", "test-model", server)) {
                UsageListener listener = listen(client);
                var result = client.streamChat("hi", "system", null, null);
                assertTrue(result.failed);
                assertUsage(result, 40, 20, 60, 0);
                assertEquals(List.of(new Usage(40, 20, 60, 0)), listener.events);
            }
        }
    }

    @Test
    void responsesRoutesIncludeCacheWritesAndReasoningOnAllTerminalOutcomes() throws Exception {
        for (String provider : List.of("openai", "openai-codex", "github-copilot")) {
            String model = "openai".equals(provider) ? "gpt-6-astra" : "gpt-5";
            String path = "openai-codex".equals(provider) ? "/codex/responses" : "/responses";
            for (String status : List.of("completed", "incomplete", "failed")) {
                String terminal = "data: {\"type\":\"response." + status + "\",\"response\":{"
                        + "\"output\":[],\"error\":{\"message\":\"generation failed\"},"
                        + "\"incomplete_details\":{\"reason\":\"max_output_tokens\"},"
                        + "\"usage\":{\"input_tokens\":100,\"output_tokens\":20,"
                        + "\"input_tokens_details\":{\"cached_tokens\":60,\"cache_write_tokens\":10},"
                        + "\"output_tokens_details\":{\"reasoning_tokens\":12}}}}\n\n";
                // A repeated terminal event must never add the same snapshot again.
                try (Fixture server = fixture(path, exchange -> sse(exchange, terminal + terminal));
                     DirectLlmClient client = client(provider, model, server)) {
                    UsageListener listener = listen(client);
                    var result = client.streamChat("hi", "system", null, null);
                    assertEquals(!"completed".equals(status), result.failed, provider + status);
                    assertUsage(result, 30, 20, 60, 10);
                    assertEquals(List.of(new Usage(30, 20, 60, 10)), listener.events);
                }
            }
        }
    }

    @Test
    void anthropicDeltasReplaceAllCountersAndCompactionSnapshots() throws Exception {
        String usage = """
                {"input_tokens":40,"output_tokens":20,"cache_read_input_tokens":70,
                 "cache_creation_input_tokens":10,"output_tokens_details":{"reasoning_tokens":12},
                 "iterations":[
                   {"type":"compaction","input_tokens":1000,"output_tokens":15,
                    "cache_read_input_tokens":500,"cache_creation_input_tokens":25},
                   {"type":"message","input_tokens":30,"output_tokens":8,
                    "cache_read_input_tokens":40,"cache_creation_input_tokens":10},
                   {"type":"message","input_tokens":10,"output_tokens":12,
                    "cache_read_input_tokens":30,"cache_creation_input_tokens":0}]}
                """.replace("\n", "");
        String delta = "data: {\"type\":\"message_delta\",\"delta\":{},\"usage\":" + usage + "}\n\n";
        String body = "data: {\"type\":\"message_start\",\"message\":{\"usage\":{"
                + "\"input_tokens\":2,\"output_tokens\":1,\"cache_read_input_tokens\":5}}}\n\n"
                + delta + delta
                + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
                + "\"usage\":{\"output_tokens\":20}}\n\ndata: {\"type\":\"message_stop\"}\n\n";
        for (String provider : List.of("anthropic", "github-copilot")) {
            try (Fixture server = fixture("/v1/messages", exchange -> sse(exchange, body));
                 DirectLlmClient client = client(provider, "claude-sonnet-4-6", server)) {
                UsageListener listener = listen(client);
                var result = client.streamChat("hi", "system", null, null);
                assertFalse(result.failed, result.failureMessage);
                assertUsage(result, 40, 20, 70, 10);
                assertEquals(1000, result.compactionInputTokens);
                assertEquals(15, result.compactionOutputTokens);
                assertEquals(500, result.compactionCacheReadTokens);
                assertEquals(25, result.compactionCacheCreationTokens);
                assertEquals(40, result.contextInputTokens(), "last message, not accumulated billing");
                assertEquals(List.of(new Usage(1040, 35, 570, 35)), listener.events);
            }
        }
    }

    @Test
    void replaySafeRetryKeepsBilledStartUsageButUsesLatestRequestContext() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (Fixture server = fixture("/v1/messages", exchange -> {
            if (requests.incrementAndGet() == 1) {
                // Input was billed, but no model text/tools arrived: retry is safe.
                sse(exchange, "data: {\"type\":\"message_start\",\"message\":{\"usage\":{"
                        + "\"input_tokens\":10,\"output_tokens\":1,\"cache_read_input_tokens\":30}}}\n\n");
            } else {
                sse(exchange, """
                        data: {"type":"message_start","message":{"usage":{"input_tokens":5,"output_tokens":1,"cache_read_input_tokens":35}}}

                        data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":4}}

                        data: {"type":"message_stop"}

                        """);
            }
        }); DirectLlmClient client = client("anthropic", "claude-sonnet-4-6", server)) {
            UsageListener listener = listen(client);
            var result = client.streamChat("hi", "system", null, null);
            assertFalse(result.failed, result.failureMessage);
            assertEquals(2, requests.get());
            assertUsage(result, 15, 5, 65, 0);
            assertEquals(40, result.contextInputTokens());
            assertEquals(List.of(new Usage(10, 1, 30, 0), new Usage(5, 4, 35, 0)), listener.events);
        }
    }

    @Test
    void continuationUsageMatchesSingleSettledCallbackAndLatestContext() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (Fixture server = fixture("/chat/completions", exchange -> {
            if (requests.incrementAndGet() == 1) {
                sse(exchange, """
                        data: {"choices":[{"delta":{"content":"partial"}}],"usage":{"prompt_tokens":100,"completion_tokens":5,"prompt_tokens_details":{"cached_tokens":60}}}

                        """);
            } else {
                sse(exchange, """
                        data: {"choices":[{"delta":{"content":" continued"},"finish_reason":"stop"}],"usage":{"prompt_tokens":130,"completion_tokens":3,"prompt_tokens_details":{"cached_tokens":100}}}

                        data: [DONE]

                        """);
            }
        }); DirectLlmClient client = client("zai", "glm-5.3", server)) {
            UsageListener listener = listen(client);
            var result = client.streamChat("hi", "system", null, null);
            assertFalse(result.failed, result.failureMessage);
            assertEquals(2, requests.get());
            assertUsage(result, 70, 8, 160, 0);
            assertEquals(130, result.contextInputTokens());
            assertEquals(List.of(new Usage(70, 8, 160, 0)), listener.events);
        }
    }

    @Test
    void failedContinuationRetainsItsReceivedUsageAndUnknownContextDoesNotReusePriorInput() throws Exception {
        for (boolean reportUsage : List.of(true, false)) {
            AtomicInteger requests = new AtomicInteger();
            try (Fixture server = fixture("/chat/completions", exchange -> {
                if (requests.incrementAndGet() == 1) {
                    sse(exchange, "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}],"
                            + "\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":5}}\n\n");
                } else {
                    // The continuation ends without a terminal event or visible text.
                    sse(exchange, reportUsage
                            ? "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":130,\"completion_tokens\":2}}\n\n"
                            : "data: {\"choices\":[],\"usage\":null}\n\n");
                }
            }); DirectLlmClient client = client("zai", "glm-5.3", server)) {
                UsageListener listener = listen(client);
                var result = client.streamChat("hi", "system", null, null);
                assertTrue(result.failed);
                assertEquals(2, requests.get());
                assertUsage(result, reportUsage ? 230 : 100, reportUsage ? 7 : 5, 0, 0);
                assertEquals(reportUsage ? 130 : 0, result.contextInputTokens());
                assertEquals(List.of(new Usage(reportUsage ? 230 : 100, reportUsage ? 7 : 5, 0, 0)),
                        listener.events);
            }
        }
    }

    @Test
    void oneShotAndStructuredOneShotForwardUsageWithoutTouchingHistory() throws Exception {
        try (Fixture server = fixture("/chat/completions", exchange -> sse(exchange, """
                data: {"choices":[{"delta":{"content":"{}"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":2}}

                data: [DONE]

                """)); DirectLlmClient client = client("openai", "gpt-4o", server)) {
            UsageListener listener = listen(client);
            assertUsage(client.streamOneShot("hi", "system", null), 10, 2, 0, 0);
            assertUsage(client.streamOneShotJson("hi", "system", null, "test",
                    MAPPER.readTree("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"), true),
                    10, 2, 0, 0);
            assertEquals(List.of(new Usage(10, 2, 0, 0), new Usage(10, 2, 0, 0)), listener.events);
            assertEquals(0, client.getHistorySize());
        }
    }

    @Test
    void piUsageIsAlreadyDisjointAndLocalServingDoesNotInventCounts() throws Exception {
        try (Fixture server = fixture("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/v1/config")) {
                json(exchange, "{\"baseUrl\":\"http://127.0.0.1:" + exchange.getLocalAddress().getPort()
                        + "/pi\",\"models\":[{\"id\":\"test-model\",\"input\":[\"text\"]}]}");
            } else if (path.equals("/pi/messages")) {
                sse(exchange, "data: {\"type\":\"done\",\"reason\":\"stop\",\"usage\":{\"input\":7,"
                        + "\"output\":3,\"cacheRead\":20,\"cacheWrite\":10,\"totalTokens\":40}}\n\n");
            } else if (path.equals("/api/llm/chat")) {
                json(exchange, "{\"content\":\"local answer\",\"rawText\":\"local answer\","
                        + "\"toolCalls\":[],\"finishReason\":\"completed\"}");
            } else {
                throw new IOException("Unexpected path: " + path);
            }
        })) {
            try (DirectLlmClient client = client("radius", "test-model", server)) {
                UsageListener listener = listen(client);
                var result = client.streamChat("hi", "system", null, null);
                assertFalse(result.failed, result.failureMessage);
                assertUsage(result, 7, 3, 20, 10);
                assertEquals(37, result.contextInputTokens());
                assertEquals(List.of(new Usage(7, 3, 20, 10)), listener.events);
            }
            try (DirectLlmClient client = client("kompile-local", "test-model", server)) {
                UsageListener listener = listen(client);
                var result = client.streamChat("hi", "system", null, null);
                assertFalse(result.failed, result.failureMessage);
                assertEquals("local answer", result.text);
                assertUsage(result, 0, 0, 0, 0);
                assertTrue(listener.events.isEmpty(), "unknown usage must remain unknown");
            }
        }
    }

    private static DirectLlmClient client(String provider, String model, Fixture server) {
        var client = new DirectLlmClient(new ChatConfig(provider, "test-key", model,
                "http://127.0.0.1:" + server.server.getAddress().getPort()), MAPPER, FAST_POLICY);
        client.setOutputConsumer(ignored -> { });
        client.setThinkingConsumer(ignored -> { });
        client.setConnectivityEventConsumer(ignored -> { });
        return client;
    }

    private static UsageListener listen(DirectLlmClient client) {
        var listener = new UsageListener();
        client.setProviderActivityListener(listener);
        return listener;
    }

    private static void assertUsage(DirectLlmClient.StreamResult result,
                                    long input, long output, long read, long write) {
        assertEquals(new Usage(input, output, read, write),
                new Usage(result.inputTokens, result.outputTokens, result.cacheReadTokens, result.cacheCreationTokens));
    }

    private static Fixture fixture(String path, HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return new Fixture(server);
    }

    private static void sse(HttpExchange exchange, String body) throws IOException {
        respond(exchange, body, "text/event-stream");
    }

    private static void json(HttpExchange exchange, String body) throws IOException {
        respond(exchange, body, "application/json");
    }

    private static void respond(HttpExchange exchange, String body, String type) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private record Fixture(HttpServer server) implements AutoCloseable {
        @Override public void close() { server.stop(0); }
    }

    private record Usage(long input, long output, long cacheRead, long cacheWrite) { }

    private static final class UsageListener implements DirectLlmClient.ProviderActivityListener {
        final List<Usage> events = new ArrayList<>();
        @Override public void onToolStart(String id, String name, String input) { }
        @Override public void onToolComplete(String id, String name, String output, int exitCode, boolean error) { }
        @Override public void onTokenUsage(long input, long output, long read, long write) {
            events.add(new Usage(input, output, read, write));
        }
    }
}
