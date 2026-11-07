package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.auth.oauth.OAuthProviderFlow;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP fixtures for the host policy around the shared chat-completions wire. */
@TemporaryUserHome
class DirectLlmClientSharedWireTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ProviderConnectivityPolicy POLICY = new ProviderConnectivityPolicy(
            Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofMillis(200),
            Duration.ofSeconds(1), 1, Duration.ofMillis(1), Duration.ofMillis(5));
    private static final String DONE = "data: [DONE]\n\n";
    private static final String TOOL_STREAM = """
            data: {"choices":[{"delta":{"content":"partial ","reasoning_content":"consider","tool_calls":[{"index":0,"id":"call_wire","function":{"name":"lookup","arguments":"{\\\"q\\\":"}}]}}]}

            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\\"wire\\\"}"}}]},"finish_reason":"tool_calls"}]}

            data: {"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":7,"prompt_tokens_details":{"cached_tokens":20,"cache_write_tokens":10}}}

            """;

    @Test
    void sendsHostHeadersAndRequestOrderWhileStreamingToolsAndSettlingUsage() throws Exception {
        try (Fixture fixture = new Fixture(Reply.ok(TOOL_STREAM + DONE));
             DirectLlmClient client = fixture.client()) {
            Activity activity = new Activity();
            client.setOutputConsumer(text -> activity.events.add("text:" + text));
            client.setThinkingConsumer(text -> activity.events.add("thinking:" + text));
            client.setProviderActivityListener(activity);

            var result = client.streamChat("hello", "system", tools(), null);

            assertFalse(result.failed, result.failureMessage);
            assertEquals("partial ", result.text);
            assertTrue(result.isTerminalEventSeen());
            assertEquals(1, result.toolCalls.size());
            assertEquals("call_wire", result.toolCalls.get(0).id);
            assertEquals("lookup", result.toolCalls.get(0).name);
            assertEquals(MAPPER.readTree("{\"q\":\"wire\"}"), result.toolCalls.get(0).arguments);
            assertEquals(List.of("text:partial ", "thinking:consider", "tool:{\"q\":", "tool:\"wire\"}",
                    "usage:70/7/20/10"), activity.events);
            assertEquals(100, result.contextInputTokens());

            assertEquals(1, fixture.requests.size());
            Request request = fixture.requests.get(0);
            assertEquals("POST", request.method());
            assertEquals("Bearer fixture-key", request.authorization());
            assertEquals("application/json", request.contentType());
            assertEquals("host-auth-header", request.marker());
            List<String> fields = new ArrayList<>();
            request.body().fieldNames().forEachRemaining(fields::add);
            assertEquals(List.of("model", "messages", "stream"), fields.subList(0, 3));
            assertTrue(fields.indexOf("stream_options") < fields.indexOf("tools"));
            assertEquals("fixture-model", request.body().path("model").asText());
            assertEquals("system", request.body().path("messages").path(0).path("content").asText());
            assertEquals("hello", request.body().path("messages").path(1).path("content").asText());
            assertTrue(request.body().path("stream").asBoolean());
            assertTrue(request.body().path("stream_options").path("include_usage").asBoolean());
            assertEquals("lookup", request.body().path("tools").path(0).path("function").path("name").asText());
        }
    }

    @Test
    void refusalIsRenderedAtFinishBeforeLaterTextWithoutLosingTrailingUsage() throws Exception {
        String body = """
                data: {"choices":[{"delta":{"refusal":"No","tool_calls":[{"index":0,"function":{"name":"lookup","arguments":"{}"}}]},"finish_reason":"content_filter"}]}

                data: {"choices":[{"delta":{"content":"later"}}]}

                data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":2}}

                """;
        for (boolean lateRefusal : List.of(false, true)) {
            // Even a delta-only refusal after a prior finish must be diagnosed
            // at that frame's finish point, not after later streamed content.
            String wire = lateRefusal
                    ? "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                            + body.replace("\"finish_reason\":\"content_filter\"", "\"finish_reason\":null")
                    : body;
            try (Fixture fixture = new Fixture(Reply.ok(wire + DONE));
                 DirectLlmClient client = fixture.client()) {
                List<String> output = new ArrayList<>();
                Activity activity = new Activity();
                client.setOutputConsumer(output::add);
                client.setProviderActivityListener(activity);
                var result = client.streamChat("hello", "system", tools(), null);

                assertEquals(FailureKind.REFUSAL, result.failureKind);
                assertTrue(result.isRefusalDetected());
                assertTrue(result.isTerminalEventSeen());
                assertTrue(result.toolCalls.isEmpty());
                assertEquals(3, output.size());
                assertEquals("No", output.get(0));
                assertTrue(output.get(1).contains(result.failureMessage));
                assertEquals("later", output.get(2));
                assertEquals(String.join("", output), result.text);
                assertEquals(List.of("tool:{}", "usage:10/2/0/0"), activity.events);
                assertEquals(1, fixture.requests.size());
            }
        }
    }

    @Test
    void lengthStillDrainsUsageAndSuppressesTools() throws Exception {
        String body = """
                data: {"choices":[{"delta":{"content":"partial","tool_calls":[{"index":0,"function":{"name":"lookup","arguments":"{}"}}]},"finish_reason":"length"}]}

                data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":2}}

                """;
        try (Fixture fixture = new Fixture(Reply.ok(body + DONE));
             DirectLlmClient client = fixture.client()) {
            Activity activity = new Activity();
            client.setProviderActivityListener(activity);
            var result = client.streamChat("hello", "system", tools(), null);
            assertEquals(FailureKind.TRUNCATED, result.failureKind);
            assertTrue(result.isTruncatedDetected());
            assertTrue(result.text.startsWith("partial"));
            assertTrue(result.toolCalls.isEmpty());
            assertEquals(List.of("tool:{}", "usage:10/2/0/0"), activity.events);
            assertEquals(1, fixture.requests.size());
        }
    }

    @Test
    void doneFinalizesToolsWithoutFinishAndSkipsMalformedChunks() throws Exception {
        String body = """
                event: ignored
                data: not-json

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"lookup","arguments":"not-json"}}]}}]}

                """;
        try (Fixture fixture = new Fixture(Reply.ok(body + DONE));
             DirectLlmClient client = fixture.client()) {
            var result = client.streamChat("hello", "system", tools(), null);
            assertFalse(result.failed, result.failureMessage);
            assertTrue(result.isTerminalEventSeen());
            assertEquals(1, result.toolCalls.size());
            assertEquals("call_0", result.toolCalls.get(0).id);
            assertEquals(MAPPER.createObjectNode(), result.toolCalls.get(0).arguments);
        }
    }

    @Test
    void textEofResumesOnceAndCommitsCombinedHistoryButNotSummedContext() throws Exception {
        String first = "data: {\"choices\":[{\"delta\":{\"content\":\"part\"}}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2}}\n\n";
        String next = "data: {\"choices\":[{\"delta\":{\"content\":\"continued\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":3}}\n\n" + DONE;
        try (Fixture fixture = new Fixture(Reply.ok(first), Reply.ok(next));
             DirectLlmClient client = fixture.client()) {
            var result = client.streamChat("hello", "system", null, null);
            assertFalse(result.failed, result.failureMessage);
            assertEquals("partcontinued", result.text);
            assertEquals(30, result.inputTokens);
            assertEquals(5, result.outputTokens);
            assertEquals(20, result.contextInputTokens());
            assertEquals(2, fixture.requests.size());
            JsonNode resumed = fixture.requests.get(1).body().path("messages");
            assertTrue(resumed.path(resumed.size() - 1).path("content").asText()
                    .startsWith("Your previous response was cut off"));
            assertFalse(fixture.requests.get(0).body().has("tools"));

            assertFalse(client.streamChat("next", "system", null, null).failed);
            JsonNode history = fixture.requests.get(2).body().path("messages");
            assertEquals("hello", history.path(1).path("content").asText());
            assertEquals("assistant", history.path(2).path("role").asText());
            assertEquals("partcontinued", history.path(2).path("content").asText());
            assertEquals("next", history.path(3).path("content").asText());
        }
    }

    @Test
    void failedContinuationRetainsStartedEvidenceAcrossHttpClassification() throws Exception {
        String partial = "data: {\"choices\":[{\"delta\":{\"content\":\"visible\"}}]}\n\n";
        try (Fixture fixture = new Fixture(Reply.ok(partial),
                new Reply(503, "{\"error\":{\"message\":\"unavailable\"}}", false));
             DirectLlmClient client = fixture.client()) {
            var result = client.streamChat("hello", "system", null, null);
            assertTrue(result.failed);
            assertTrue(result.text.startsWith("visible"));
            assertTrue(result.responseStarted);
            assertFalse(result.isReplaySafe(), "Failure diagnostics must not reset actual provider-output evidence");
            assertEquals(2, fixture.requests.size());
        }
    }

    @Test
    void emptyEofIsFailureAndReadFailureKeepsAlreadyStreamedTextUsageAndTools() throws Exception {
        try (Fixture fixture = new Fixture(Reply.ok("")); DirectLlmClient client = fixture.client()) {
            assertTrue(client.streamChat("hello", "system", null, null).failed);
            assertEquals(1, fixture.requests.size());
        }
        // Deliberately shorter than Content-Length: a real HTTP read error after
        // complete chunks must not discard the host's live callback mutations.
        try (Fixture fixture = new Fixture(new Reply(200, TOOL_STREAM, true));
             DirectLlmClient client = fixture.client()) {
            var result = client.streamChat("hello", "system", tools(), null);
            assertTrue(result.failed);
            assertTrue(result.text.startsWith("partial "));
            assertEquals(70, result.inputTokens);
            assertEquals(7, result.outputTokens);
            assertEquals(1, result.toolCalls.size());
            assertEquals("wire", result.toolCalls.get(0).arguments.path("q").asText());
        }
    }

    @Test
    void hostStillClassifiesHttpAndSseFailures() throws Exception {
        try (Fixture fixture = new Fixture(new Reply(403, "{\"error\":{\"message\":\"denied\"}}", false));
             DirectLlmClient client = fixture.client()) {
            var result = client.streamChat("hello", "system", null, null);
            assertEquals(FailureKind.PERMISSION_DENIED, result.failureKind);
            assertEquals(403, result.failureStatusCode);
            assertEquals(1, fixture.requests.size());
        }
        String error = "data: {\"error\":{\"type\":\"insufficient_quota\",\"message\":\"quota\"}}\n\n";
        for (String prefix : List.of("", TOOL_STREAM.replace("\"content\":\"partial \",", ""))) {
            try (Fixture fixture = new Fixture(Reply.ok(prefix + error)); DirectLlmClient client = fixture.client()) {
                var result = client.streamChat("hello", "system", tools(), null);
                assertEquals(FailureKind.QUOTA_EXHAUSTED, result.failureKind);
                assertTrue(result.toolCalls.isEmpty());
                assertEquals(!prefix.isEmpty(), result.responseStarted);
                assertEquals(prefix.isEmpty(), result.isReplaySafe());
                assertEquals(1, fixture.requests.size());
            }
        }
    }

    private static ArrayNode tools() throws IOException {
        return (ArrayNode) MAPPER.readTree("""
                [{"name":"lookup","description":"find a value","inputSchema":{"type":"object","properties":{"q":{"type":"string"}}}}]
                """);
    }

    private record Request(String method, String authorization, String contentType, String marker, JsonNode body) { }
    private record Reply(int status, String body, boolean shortBody) {
        static Reply ok(String body) { return new Reply(200, body, false); }
    }

    private static final class Fixture implements AutoCloseable {
        private final HttpServer server;
        final List<Request> requests = new CopyOnWriteArrayList<>();

        Fixture(Reply... replies) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                try {
                    requests.add(new Request(exchange.getRequestMethod(),
                            exchange.getRequestHeaders().getFirst("Authorization"),
                            exchange.getRequestHeaders().getFirst("Content-Type"),
                            exchange.getRequestHeaders().getFirst("X-Fixture-Auth"),
                            MAPPER.readTree(exchange.getRequestBody())));
                    Reply reply = replies[Math.min(requests.size() - 1, replies.length - 1)];
                    byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type",
                            reply.status() == 200 ? "text/event-stream" : "application/json");
                    exchange.sendResponseHeaders(reply.status(), bytes.length + (reply.shortBody() ? 128 : 0));
                    exchange.getResponseBody().write(bytes);
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        DirectLlmClient client() {
            ChatConfig config = new ChatConfig("custom", "fixture-key", "fixture-model",
                    "http://127.0.0.1:" + server.getAddress().getPort()) {
                @Override public OAuthProviderFlow.RequestAuth resolveRequestAuth() {
                    return new OAuthProviderFlow.RequestAuth("fixture-key", null,
                            Map.of("X-Fixture-Auth", "host-auth-header"), false);
                }
            };
            DirectLlmClient client = new DirectLlmClient(config, MAPPER, POLICY);
            client.setOutputConsumer(ignored -> { });
            client.setThinkingConsumer(ignored -> { });
            client.setConnectivityEventConsumer(ignored -> { });
            return client;
        }

        @Override public void close() { server.stop(0); }
    }

    private static final class Activity implements ProviderActivityListener {
        final List<String> events = new ArrayList<>();
        @Override public void onToolStart(String id, String name, String input) { }
        @Override public void onToolInputDelta(String delta) { events.add("tool:" + delta); }
        @Override public void onToolComplete(String id, String name, String output, int exitCode, boolean error) { }
        @Override public void onTokenUsage(long input, long output, long read, long write) {
            events.add("usage:" + input + "/" + output + "/" + read + "/" + write);
        }
    }
}
