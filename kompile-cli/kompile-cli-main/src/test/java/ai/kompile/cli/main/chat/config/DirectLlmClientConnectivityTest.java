package ai.kompile.cli.main.chat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectLlmClientConnectivityTest {

    private static final ProviderConnectivityPolicy FAST_POLICY =
            new ProviderConnectivityPolicy(
                    Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofMillis(100),
                    Duration.ofSeconds(1), 3, Duration.ofMillis(5), Duration.ofMillis(20));

    @Test
    void authenticationAndConnectionDiagnosticsUseHeaderNotModelOutput() throws Exception {
        AtomicInteger status = new AtomicInteger(401);
        TestServer fixture = startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, status.get(), "{\"error\":{\"message\":\"Unavailable\"}}");
        });
        try (fixture; DirectLlmClient client = client(fixture);
             var ui = new ai.kompile.cli.main.chat.ChatUiSession();
             var binding = ui.bind()) {
            List<String> alerts = new ArrayList<>();
            StringBuilder output = new StringBuilder();
            ai.kompile.cli.main.chat.ChatCompleter.setAlertOutput(alerts::add);
            client.setOutputConsumer(output::append);
            var unauthorized = client.streamChat("hello", "system", null, null);
            assertTrue(unauthorized.failed);
            assertTrue(alerts.stream().anyMatch(text -> text.contains("authentication")));
            assertEquals("", output.toString());
            alerts.clear();
            status.set(503);
            var unavailable = client.streamChat("hello again", "system", null, null);
            assertTrue(unavailable.failed);
            assertTrue(alerts.stream().anyMatch(text -> text.contains("connection lost")));
            assertTrue(alerts.size() > 1, "retry and terminal failure must both use the header");
            assertEquals("", output.toString());
        }
    }

    @Test
    void nonRetryableProviderExceptionIsMarkedFailed() {
        ChatConfig config = new ChatConfig(
                "custom", null, "test-model", "http://[");
        DirectLlmClient client = new DirectLlmClient(
                config, new ObjectMapper(), FAST_POLICY);
        client.setOutputConsumer(ignored -> { });

        DirectLlmClient.StreamResult result =
                client.streamChat("hello", "system", null, null);

        assertTrue(result.failed);
        assertTrue(result.text.startsWith("[Error:"));
    }

    @Test
    void apiKeyUnauthorizedWithClosedBodyEndsOnlyThatTurnAndLeavesClientReusable()
            throws Exception {
        AtomicInteger calls = new AtomicInteger();
        TestServer fixture = startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (calls.incrementAndGet() == 1) {
                // The status is authoritative even if the provider/proxy closes its
                // diagnostic body early — this formerly surfaced only "Error: closed".
                exchange.sendResponseHeaders(401, 64);
                exchange.close();
            } else {
                respondSse(exchange, completion("next-turn-recovered"));
            }
        });
        try (fixture; DirectLlmClient client = client(fixture)) {
            StringBuilder output = new StringBuilder();
            client.setOutputConsumer(output::append);

            DirectLlmClient.StreamResult rejected =
                    client.streamChat("first", "system", null, null);
            DirectLlmClient.StreamResult next =
                    client.streamChat("second", "system", null, null);

            assertTrue(rejected.failed);
            assertEquals(DirectLlmClient.FailureKind.AUTHENTICATION, rejected.failureKind);
            assertEquals(401, rejected.failureStatusCode);
            assertTrue(rejected.failureMessage.contains("Unauthorized"));
            assertTrue(rejected.failureMessage.contains("kompile auth login openai"));
            assertEquals("next-turn-recovered", next.text);
            assertFalse(next.failed);
            assertEquals(2, calls.get(),
                    "an API-key 401 must not be replayed, but a later user turn must still run");
        }
    }

    @Test
    void refreshesRejectedResponsesOauthOnceAndReplaysWithoutLeakingThe401()
            throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger refreshes = new AtomicInteger();
        AtomicReference<ai.kompile.cli.main.auth.oauth.OAuthProviderFlow.RequestAuth> auth =
                new AtomicReference<>(ai.kompile.cli.main.auth.oauth.OAuthProviderFlow.RequestAuth.oauth(
                        "old-token", null, java.util.Map.of()));
        TestServer fixture = startServer("/codex/responses", exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            exchange.getRequestBody().readAllBytes();
            calls.incrementAndGet();
            if ("Bearer old-token".equals(authorization)) {
                respond(exchange, 401,
                        "{\"error\":{\"message\":\"Unauthorized\"}}");
            } else {
                respondSse(exchange, responsesCompletion("after-oauth-refresh"));
            }
        });
        ChatConfig config = new ChatConfig(
                "openai-codex", null, "test-model", fixture.baseUrl()) {
            @Override
            public ai.kompile.cli.main.auth.oauth.OAuthProviderFlow.RequestAuth resolveRequestAuth() {
                return auth.get();
            }

            @Override
            public ai.kompile.cli.main.auth.oauth.OAuthProviderFlow.RequestAuth
                    refreshRequestAuthAfterUnauthorized(
                    ai.kompile.cli.main.auth.oauth.OAuthProviderFlow.RequestAuth rejectedAuth) {
                refreshes.incrementAndGet();
                var refreshed = ai.kompile.cli.main.auth.oauth.OAuthProviderFlow.RequestAuth.oauth(
                        "new-token", null, java.util.Map.of());
                auth.set(refreshed);
                return refreshed;
            }
        };
        try (fixture; DirectLlmClient client = new DirectLlmClient(
                config, new ObjectMapper(), FAST_POLICY)) {
            StringBuilder output = new StringBuilder();
            client.setOutputConsumer(output::append);

            DirectLlmClient.StreamResult result =
                    client.streamChat("hello", "system", null, null);

            assertEquals("after-oauth-refresh", result.text);
            assertFalse(result.failed);
            assertEquals(2, calls.get());
            assertEquals(1, refreshes.get());
            assertEquals("after-oauth-refresh", output.toString(),
                    "a recovered OAuth 401 must not appear as assistant output");
        }
    }

    @Test
    void classifiesHttpContextOverflowWithoutRenderingBeforeRecovery() throws Exception {
        TestServer fixture = startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 400,
                    "{\"error\":{\"type\":\"invalid_request_error\","
                            + "\"code\":\"context_length_exceeded\","
                            + "\"message\":\"Maximum context length exceeded\"}}");
        });
        try (fixture; DirectLlmClient client = client(fixture)) {
            StringBuilder output = new StringBuilder();
            client.setOutputConsumer(output::append);

            DirectLlmClient.StreamResult result =
                    client.streamChat("hello", "system", null, null);

            assertTrue(result.isContextOverflow());
            assertTrue(result.canRetryAfterContextOverflow());
            assertEquals(400, result.failureStatusCode);
            assertTrue(output.isEmpty(),
                    "a replay-safe overflow must stay hidden until recovery decides");
        }
    }

    @Test
    void classifiesOpenAiSseErrorEnvelopeAsContextOverflow() throws Exception {
        TestServer fixture = startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            respondSse(exchange,
                    "data: {\"error\":{\"code\":\"context_length_exceeded\","
                            + "\"message\":\"Context window exceeded\"}}\n\n");
        });
        try (fixture; DirectLlmClient client = client(fixture)) {
            StringBuilder output = new StringBuilder();
            client.setOutputConsumer(output::append);

            DirectLlmClient.StreamResult result =
                    client.streamChat("hello", "system", null, null);

            assertTrue(result.isContextOverflow());
            assertTrue(result.canRetryAfterContextOverflow());
            assertTrue(output.isEmpty());
        }
    }

    @Test
    void contextOverflowAfterVisibleOutputIsNeverReplaySafe() throws Exception {
        TestServer fixture = startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            respondSse(exchange,
                    "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"
                            + "data: {\"error\":{\"code\":\"context_length_exceeded\","
                            + "\"message\":\"Context window exceeded\"}}\n\n");
        });
        try (fixture; DirectLlmClient client = client(fixture)) {
            StringBuilder output = new StringBuilder();
            client.setOutputConsumer(output::append);

            DirectLlmClient.StreamResult result =
                    client.streamChat("hello", "system", null, null);

            assertTrue(result.isContextOverflow());
            assertFalse(result.isReplaySafe());
            assertFalse(result.canRetryAfterContextOverflow());
            assertTrue(result.responseStarted);
            assertTrue(output.toString().contains("partial"));
            assertTrue(output.toString().contains("Context window exceeded"));
        }
    }

    @Test
    void tokenRateLimitIsNotMisclassifiedAsContextOverflow() {
        assertFalse(DirectLlmClient.isContextOverflowFailure(
                429, "Too many input tokens per minute for this organization"));
        assertTrue(DirectLlmClient.isContextOverflowFailure(
                400, "Range of input length should be [1, 32768]"));
    }

    @Test
    void promptExceedsMaxLengthIsClassifiedAsContextOverflow() {
        assertTrue(DirectLlmClient.isContextOverflowFailure(
                400, "Prompt exceeds max length of 131072 tokens"));
    }

    @Test
    void providerToolActivityMakesOverflowNonReplayable() {
        DirectLlmClient.StreamResult result = new DirectLlmClient.StreamResult();
        result.failed = true;
        result.failureKind = DirectLlmClient.FailureKind.CONTEXT_OVERFLOW;
        result.providerSideEffectsObserved = true;

        assertFalse(result.isReplaySafe());
        assertFalse(result.canRetryAfterContextOverflow());
    }

    @Test
    void retriesTransientStatusesWithoutCommittingFailedAttempts() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        TestServer fixture = startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (calls.incrementAndGet() < 3) {
                respond(exchange, 503, "{\"error\":{\"message\":\"temporarily unavailable\"}}");
            } else {
                respondSse(exchange, completion("recovered"));
            }
        });
        try (fixture; DirectLlmClient client = client(fixture)) {
            List<DirectLlmClient.ConnectivityEvent> events = new ArrayList<>();
            StringBuilder output = new StringBuilder();
            client.setConnectivityEventConsumer(events::add);
            client.setOutputConsumer(output::append);

            DirectLlmClient.StreamResult result =
                    client.streamChat("hello", "system", null, null);

            assertEquals(3, calls.get());
            assertEquals("recovered", result.text);
            assertEquals("recovered", output.toString());
            assertFalse(result.failed);
            assertEquals(2, events.size());
            assertEquals(2, events.get(0).attempt());
            assertEquals(3, events.get(1).attempt());
        }
    }

    @Test
    void retriesCleanPrematureEofBeforeAnyOutput() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        TestServer fixture = startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (calls.incrementAndGet() == 1) {
                respondSse(exchange,
                        "data: {\"choices\":[{\"delta\":{}}]}\n\n");
            } else {
                respondSse(exchange, completion("after-eof-retry"));
            }
        });
        try (fixture; DirectLlmClient client = client(fixture)) {
            client.setOutputConsumer(ignored -> { });

            DirectLlmClient.StreamResult result =
                    client.streamChat("hello", "system", null, null);

            assertEquals(2, calls.get());
            assertEquals("after-eof-retry", result.text);
            assertFalse(result.failed);
        }
    }

    @Test
    void reconnectsWhenHeadersArriveButTheFirstStreamNeverProducesData() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        TestServer fixture = startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (calls.incrementAndGet() == 1) {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().flush();
                // Hold well past the 100 ms idle timeout so the client's watchdog
                // reliably fires first. A 300 ms hold flaked under full-suite load:
                // the watchdog thread could be starved past the close, turning the
                // abort into a clean EOF with no reconnect.
                sleep(5_000);
                exchange.close();
            } else {
                respondSse(exchange, completion("after-idle-retry"));
            }
        });
        try (fixture; DirectLlmClient client = client(fixture)) {
            List<DirectLlmClient.ConnectivityEvent> events = new ArrayList<>();
            client.setConnectivityEventConsumer(events::add);
            client.setOutputConsumer(ignored -> { });

            DirectLlmClient.StreamResult result =
                    client.streamChat("hello", "system", null, null);

            assertEquals(2, calls.get());
            assertEquals("after-idle-retry", result.text);
            assertEquals(1, events.size());
            assertTrue(events.get(0).reason().contains("idle"));
        }
    }

    @Test
    void doesNotReplayAfterVisibleAssistantOutput() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        TestServer fixture = startServer(exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            // Hold past the idle timeout (see reconnects... test) so the client
            // always observes a stall rather than racing the server's close.
            sleep(5_000);
            exchange.close();
        });
        try (fixture; DirectLlmClient client = client(fixture)) {
            StringBuilder output = new StringBuilder();
            client.setOutputConsumer(output::append);

            DirectLlmClient.StreamResult result =
                    client.streamChat("hello", "system", null, null);

            assertEquals(1, calls.get());
            assertTrue(result.failed);
            assertTrue(result.text.startsWith("partial"));
            assertTrue(result.text.contains("not replayed"));
            assertTrue(output.toString().contains("partial"));
        }
    }

    @Test
    void retriesAnthropicOverloadedStatus529UntilRecovery() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        TestServer fixture = startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (calls.incrementAndGet() < 3) {
                respond(exchange, 529, "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\","
                        + "\"message\":\"Overloaded\"}}");
            } else {
                respondSse(exchange, completion("after-overload-retry"));
            }
        });
        try (fixture; DirectLlmClient client = client(fixture)) {
            List<DirectLlmClient.ConnectivityEvent> events = new ArrayList<>();
            StringBuilder output = new StringBuilder();
            client.setConnectivityEventConsumer(events::add);
            client.setOutputConsumer(output::append);

            DirectLlmClient.StreamResult result =
                    client.streamChat("hello", "system", null, null);

            assertEquals(3, calls.get());
            assertEquals("after-overload-retry", result.text);
            assertFalse(result.failed);
            assertEquals(2, events.size());
            assertTrue(events.get(0).reason().contains("529"));
        }
    }

    @Test
    void retriesInBandOverloadedErrorEnvelopeWithoutRenderingTheFailedAttempt() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        TestServer fixture = startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (calls.incrementAndGet() == 1) {
                // A 200-status stream that dies with an overload envelope: the same
                // wire shape Anthropic (and OpenAI-compatible proxies) use mid-stream.
                respondSse(exchange,
                        "data: {\"error\":{\"type\":\"overloaded_error\","
                                + "\"message\":\"Overloaded\"}}\n\n");
            } else {
                respondSse(exchange, completion("after-envelope-retry"));
            }
        });
        try (fixture; DirectLlmClient client = client(fixture)) {
            StringBuilder output = new StringBuilder();
            client.setOutputConsumer(output::append);

            DirectLlmClient.StreamResult result =
                    client.streamChat("hello", "system", null, null);

            assertEquals(2, calls.get());
            assertEquals("after-envelope-retry", result.text);
            assertFalse(result.failed);
            assertEquals("after-envelope-retry", output.toString(),
                    "a replay-safe overloaded turn must not leak the failure into rendered output");
        }
    }

    @Test
    void retriesInBandUnknownServerErrorEnvelopeUntilRecovery() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        TestServer fixture = startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (calls.incrementAndGet() == 1) {
                // The exact shape this fix targets: a 200 SSE stream that dies with
                // OpenAI's opaque server_error envelope ("Unknown error" with no
                // usable human signal).
                respondSse(exchange,
                        "data: {\"error\":{\"message\":\"Unknown error\","
                                + "\"type\":\"server_error\",\"param\":null,\"code\":null}}\n\n");
            } else {
                respondSse(exchange, completion("after-unknown-error-retry"));
            }
        });
        try (fixture; DirectLlmClient client = client(fixture)) {
            List<DirectLlmClient.ConnectivityEvent> events = new ArrayList<>();
            StringBuilder output = new StringBuilder();
            client.setConnectivityEventConsumer(events::add);
            client.setOutputConsumer(output::append);

            DirectLlmClient.StreamResult result =
                    client.streamChat("hello", "system", null, null);

            assertEquals(2, calls.get(),
                    "the unknown server_error envelope must be counted as a retryable attempt");
            assertEquals("after-unknown-error-retry", result.text);
            assertFalse(result.failed);
            assertEquals(1, events.size(),
                    "the failed attempt should emit one reconnect event");
            assertTrue(events.get(0).reason().contains("Unknown error"));
            assertEquals("after-unknown-error-retry", output.toString(),
                    "the failed attempt must not leak into rendered output");
        }
    }

    @Test
    void inBandInvalidRequestEnvelopeStaysTerminalWithoutRetries() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        TestServer fixture = startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            calls.incrementAndGet();
            // An invalid-request envelope is a request-shape problem: retrying
            // identical bytes cannot fix it, so the first failure must be final.
            respondSse(exchange,
                    "data: {\"error\":{\"message\":\"Unrecognized request argument\","
                            + "\"type\":\"invalid_request_error\",\"param\":\"tools\"}}\n\n");
        });
        try (fixture; DirectLlmClient client = client(fixture)) {
            List<DirectLlmClient.ConnectivityEvent> events = new ArrayList<>();
            client.setConnectivityEventConsumer(events::add);
            client.setOutputConsumer(ignored -> { });

            DirectLlmClient.StreamResult result =
                    client.streamChat("hello", "system", null, null);

            assertEquals(1, calls.get(),
                    "invalid-request envelopes must not consume the retry budget");
            assertTrue(result.failed);
            assertTrue(events.isEmpty());
        }
    }

    private static DirectLlmClient client(TestServer server) {
        ChatConfig config = new ChatConfig(
                "openai", "test-key", "test-model", server.baseUrl());
        return new DirectLlmClient(config, new ObjectMapper(), FAST_POLICY);
    }

    private static String completion(String text) {
        return "data: {\"choices\":[{\"delta\":{\"content\":\"" + text
                + "\"},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n";
    }

    private static String responsesCompletion(String text) {
        return "data: {\"type\":\"response.output_text.delta\",\"output_index\":0,"
                + "\"delta\":\"" + text + "\"}\n\n"
                + "data: {\"type\":\"response.completed\",\"response\":{"
                + "\"status\":\"completed\",\"usage\":{\"input_tokens\":1,"
                + "\"output_tokens\":1}}}\n\n";
    }

    private static TestServer startServer(ExchangeHandler handler) throws Exception {
        return startServer("/chat/completions", handler);
    }

    private static TestServer startServer(String path, ExchangeHandler handler) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext(path, exchange -> {
            try {
                handler.handle(exchange);
            } catch (Exception failure) {
                exchange.close();
            }
        });
        server.start();
        return new TestServer(server, executor);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void respondSse(HttpExchange exchange, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws Exception;
    }

    private record TestServer(HttpServer server, ExecutorService executor) implements AutoCloseable {
        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
