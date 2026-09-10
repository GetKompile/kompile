package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.auth.oauth.OAuthProviderFlow;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static ai.kompile.cli.main.chat.config.DirectLlmClient.FailureKind.*;

class DirectLlmClientFailureClassificationTest {
    private static final ProviderConnectivityPolicy POLICY = new ProviderConnectivityPolicy(
            Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofMillis(200),
            Duration.ofSeconds(1), 3, Duration.ofMillis(1), Duration.ofMillis(5));

    @Test
    void temporaryCredentialFailureDoesNotAskForLoginOrLeakCause() {
        ChatConfig config = new ChatConfig("openai", null, "test", "http://localhost:1") {
            @Override public OAuthProviderFlow.RequestAuth resolveRequestAuth() {
                throw new AuthenticationException("openai", new HttpTimeoutException("secret-token"));
            }
        };
        try (var client = new DirectLlmClient(config, new ObjectMapper(), POLICY)) {
            client.setOutputConsumer(ignored -> {});
            var result = client.streamChat("hello", "system", null, null);
            assertEquals(TEMPORARY, result.failureKind);
            assertFalse(result.text.contains("auth login"));
            assertFalse(result.text.contains("secret-token"));
        }
    }

    @Test
    void interruptedCredentialAccessIsNotRenderedAsAuthenticationRejectionOrRetried() {
        AtomicInteger attempts = new AtomicInteger();
        ChatConfig config = new ChatConfig("openai-codex", null, "test", "http://localhost:1") {
            @Override public OAuthProviderFlow.RequestAuth resolveRequestAuth() {
                attempts.incrementAndGet();
                throw new AuthenticationException("openai-codex",
                        new java.io.IOException("secret-token", new InterruptedException("secret-token")));
            }
        };
        try (var client = new DirectLlmClient(config, new ObjectMapper(), POLICY)) {
            client.setOutputConsumer(ignored -> {});
            var result = client.streamChat("hello", "system", null, null);
            assertEquals(CREDENTIAL_UNAVAILABLE, result.failureKind);
            assertEquals(1, attempts.get());
            assertTrue(result.failed);
            assertTrue(result.text.contains("interrupted"));
            assertFalse(result.text.contains("auth login"));
            assertFalse(result.text.contains("refresh"));
            assertFalse(result.text.contains("secret-token"));
        }
    }

    @Test
    void refreshTimeoutAfter401RemainsTemporary() throws Exception {
        try (var fixture = new Fixture("openai", 401, "{}", true)) {
            var result = fixture.run();
            assertEquals(TEMPORARY, result.failureKind);
            assertEquals(1, fixture.refreshes.get());
            assertEquals(1, fixture.requests.get());
            assertFalse(result.text.contains("auth login"));
            assertFalse(result.text.contains("secret-token"));
        }
    }

    @Test
    void permissionAndPolicy401sDoNotRefreshAndNeverReflectSecrets() throws Exception {
        for (String code : new String[]{"ip_not_authorized", "permission_error", "content_policy_violation"}) {
            try (var fixture = new Fixture("openai", 401,
                    "{\"error\":{\"code\":\"" + code + "\",\"message\":\"secret-token\"}}", false)) {
                var result = fixture.run();
                assertEquals(code.equals("content_policy_violation") ? REFUSAL : PERMISSION_DENIED, result.failureKind);
                assertEquals(0, fixture.refreshes.get());
                assertEquals(1, fixture.requests.get());
                assertFalse(result.text.contains("secret-token"));
                assertFalse(result.text.contains("auth login"));
            }
        }
    }

    @Test
    void quota429DoesNotEnterConnectivityRetries() throws Exception {
        try (var fixture = new Fixture("openai", 429, "{\"error\":{\"type\":\"insufficient_quota\"}}", false)) {
            assertEquals(QUOTA_EXHAUSTED, fixture.run().failureKind);
            assertEquals(1, fixture.requests.get());
            assertEquals(0, fixture.refreshes.get());
        }
    }

    @Test
    void httpFailuresRetainDiagnosticsAfterRetries() throws Exception {
        for (String provider : new String[]{"openai", "openai-codex", "anthropic"}) {
            try (var fixture = new Fixture(provider, 500,
                    "{\"error\":{\"message\":\"Server failed\",\"code\":\"internal_error\","
                            + "\"type\":\"server_error\",\"param\":\"input\"}}", false,
                    Map.of("X-Request-ID", "req-synthetic", "Set-Cookie", "private-cookie"))) {
                var result = fixture.run();
                assertTrue(result.failed);
                assertEquals(PROVIDER_ERROR, result.failureKind);
                assertEquals(500, result.failureStatusCode);
                assertEquals(result.text, result.failureMessage);
                assertTrue(result.text.contains("API error 500: Server failed"), result.text);
                assertTrue(result.text.contains("code=internal_error"));
                assertTrue(result.text.contains("type=server_error"));
                assertTrue(result.text.contains("param=input"));
                assertTrue(result.text.contains("request_id=req-synthetic"));
                assertFalse(result.text.contains("private-cookie"));
                assertEquals(POLICY.maxAttempts(), fixture.requests.get());
                assertEquals(0, fixture.refreshes.get());
            }
        }
    }

    @Test
    void diagnosticMetadataIsOptionalBoundedAndAllowlisted() {
        var empty = java.net.http.HttpHeaders.of(Map.of(), (name, value) -> true);
        assertEquals("", ProviderResponseFailure.diagnostics("{\"error\":{\"message\":\"ordinary\"}}", empty));
        assertEquals(" (request_id=body-id)", ProviderResponseFailure.diagnostics("{\"request_id\":\"body-id\"}", empty));
        var headers = java.net.http.HttpHeaders.of(Map.of("request-id", java.util.List.of("header-id")), (name, value) -> true);
        assertEquals(" (request_id=header-id)", ProviderResponseFailure.diagnostics("not JSON", headers));
        assertEquals(" (request_id=header-id)", ProviderResponseFailure.diagnostics("{\"request_id\":\"body-id\"}", headers));
        String body = "{\"error\":{\"code\":\"\\u001b[31m\\n" + "x".repeat(500)
                + "\",\"private\":\"secret-token\"}}";
        String diagnostic = ProviderResponseFailure.diagnostics(body, empty);
        assertTrue(diagnostic.length() < 200);
        assertFalse(diagnostic.contains("\u001b"));
        assertFalse(diagnostic.contains("\n"));
        assertFalse(diagnostic.contains("secret-token"));
        String schemaBody = "{\"error\":{\"message\":\"Invalid schema: minItems is not permitted\","
                + "\"code\":\"invalid_json_schema\",\"type\":\"invalid_request_error\","
                + "\"param\":\"text.format.schema\"}}";
        String schemaDiagnostic = ProviderResponseFailure.diagnostics(schemaBody, empty);
        assertTrue(schemaDiagnostic.contains("message=Invalid schema: minItems is not permitted"),
                schemaDiagnostic);
        assertFalse(schemaDiagnostic.contains("secret"));
    }

    @Test
    void anthropicRefusalDiscardsPreviouslyAccumulatedTools() throws Exception {
        String sse = event("{\"type\":\"content_block_start\",\"content_block\":{\"type\":\"tool_use\",\"id\":\"t\",\"name\":\"bash\"}}")
                + event("{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{}\"}}")
                + event("{\"type\":\"content_block_stop\"}")
                + event("{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Partial answer\"}}")
                + event("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"refusal\"},\"usage\":{\"output_tokens\":3}}")
                + event("{\"type\":\"message_stop\"}");
        assertTerminal("anthropic", sse, REFUSAL);
    }

    @Test
    void openAiChatRefusalAndContentFilterAreNotEmptySuccesses() throws Exception {
        assertTerminal("openai", event("{\"choices\":[{\"delta\":{\"refusal\":\"I cannot answer\"},\"finish_reason\":\"stop\"}]}") + event("[DONE]"), REFUSAL);
        assertTerminal("openai", event("{\"choices\":[{\"delta\":{},\"finish_reason\":\"content_filter\"}]}") + event("[DONE]"), REFUSAL);
    }

    @Test
    void responsesRefusalAndIncompleteAreDistinct() throws Exception {
        assertTerminal("openai-codex", event("{\"type\":\"response.refusal.delta\",\"delta\":\"I cannot answer\"}")
                + event("{\"type\":\"response.completed\",\"response\":{}}"), REFUSAL);
        assertTerminal("openai-codex", event("{\"type\":\"response.completed\",\"response\":{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"refusal\",\"refusal\":\"No\"}]}]}}"), REFUSAL);
        assertTerminal("openai-codex", event("{\"type\":\"response.incomplete\",\"response\":{\"incomplete_details\":{\"reason\":\"content_filter\"}}}"), REFUSAL);
        assertTerminal("openai-codex", event("{\"type\":\"response.incomplete\",\"response\":{\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}}"), TRUNCATED);
    }

    @Test
    void ordinaryRefusalWordsDoNotTriggerPolicyClassification() throws Exception {
        try (var fixture = new Fixture("openai", 200,
                event("{\"choices\":[{\"delta\":{\"content\":\"Explain the word refusal\"},\"finish_reason\":\"stop\"}]}") + event("[DONE]"), false)) {
            assertFalse(fixture.run().failed);
        }
    }

    @Test
    void acceptedToolResultsSurviveRefusalForTheNextTurn() throws Exception {
        for (String provider : new String[]{"openai", "anthropic", "openai-codex"}) {
            String refusal = switch (provider) {
                case "anthropic" -> event("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"refusal\"}}")
                        + event("{\"type\":\"message_stop\"}");
                case "openai-codex" -> event("{\"type\":\"response.incomplete\",\"response\":{\"incomplete_details\":{\"reason\":\"content_filter\"}}}");
                default -> event("{\"choices\":[{\"delta\":{},\"finish_reason\":\"content_filter\"}]}") + event("[DONE]");
            };
            try (var fixture = new Fixture(provider, 200, refusal, false)) {
                fixture.client.addToHistory("user", "run the tool");
                fixture.client.addReplayedToolCall("example", "call_1", "{}");
                var result = fixture.client.streamChat(null, "system", null,
                        java.util.List.of(new DirectLlmClient.ToolCallResultInput("call_1", "example", "synthetic-tool-result", false)));
                assertEquals(REFUSAL, result.failureKind);
                fixture.run();
                assertEquals(2, fixture.requestBodies.size());
                assertTrue(fixture.requestBodies.get(1).contains("synthetic-tool-result"), provider);
            }
        }
    }

    @Test
    void cancelled401NeverRefreshes() throws Exception {
        try (var fixture = new Fixture("openai", 401, "{}", false)) {
            // Becomes true once the server has received the request.
            fixture.client.setCancellationCheck(() -> fixture.requests.get() > 0);
            var result = fixture.run();
            assertTrue(result.cancelled);
            assertEquals(0, fixture.refreshes.get());
        }
    }

    @Test
    void diagnosticReaderIsByteAndTimeBounded() {
        String body = "x".repeat(9000);
        assertEquals("", ProviderResponseFailure.authenticationBody(new java.io.ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))));
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        var blocked = new java.io.InputStream() {
            @Override public int read() throws java.io.IOException {
                try { new java.util.concurrent.CountDownLatch(1).await(); }
                catch (InterruptedException e) { throw new java.io.IOException(e); }
                return -1;
            }
            @Override public void close() { closed.set(true); }
        };
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertEquals("", ProviderResponseFailure.authenticationBody(blocked)));
        assertTrue(closed.get());
    }

    private static void assertTerminal(String provider, String sse, DirectLlmClient.FailureKind kind) throws Exception {
        try (var fixture = new Fixture(provider, 200, sse, false)) {
            var result = fixture.run();
            assertTrue(result.failed);
            assertEquals(kind, result.failureKind, result.text);
            assertTrue(result.toolCalls.isEmpty());
            assertFalse(result.text.contains("auth login"));
            assertEquals(1, fixture.requests.get());
            assertEquals(0, fixture.refreshes.get());
        }
    }

    private static String event(String json) { return "data: " + json + "\n\n"; }

    private static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final DirectLlmClient client;
        final AtomicInteger requests = new AtomicInteger();
        final AtomicInteger refreshes = new AtomicInteger();
        final java.util.List<String> requestBodies = new java.util.concurrent.CopyOnWriteArrayList<>();
        Fixture(String provider, int status, String body, boolean refreshFails) throws Exception {
            this(provider, status, body, refreshFails, Map.of());
        }
        Fixture(String provider, int status, String body, boolean refreshFails, Map<String, String> headers) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", status == 200 ? "text/event-stream" : "application/json");
                headers.forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
                exchange.sendResponseHeaders(status, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            server.start();
            ChatConfig config = new ChatConfig(provider, null, "test-model", "http://127.0.0.1:" + server.getAddress().getPort()) {
                @Override public OAuthProviderFlow.RequestAuth resolveRequestAuth() {
                    return OAuthProviderFlow.RequestAuth.oauth("synthetic", null, Map.of());
                }
                @Override public OAuthProviderFlow.RequestAuth refreshRequestAuthAfterUnauthorized(OAuthProviderFlow.RequestAuth rejected) {
                    refreshes.incrementAndGet();
                    if (refreshFails) throw new AuthenticationException(provider, new HttpTimeoutException("secret-token"));
                    return null;
                }
            };
            client = new DirectLlmClient(config, new ObjectMapper(), POLICY);
            client.setOutputConsumer(ignored -> {});
        }
        DirectLlmClient.StreamResult run() { return client.streamChat("hello", "system", null, null); }
        @Override public void close() { client.close(); server.stop(0); }
    }
}
