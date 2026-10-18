package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.ChatCompleter;
import ai.kompile.cli.main.chat.config.ProviderConnectivityPolicy;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A "connection closed before a terminal event" drop mid-answer must never kill the
 * chat session: the server turn reconnects with backoff (bounded by the Kompile
 * provider policy) and keeps whatever the stream already produced.
 */
@TemporaryUserHome
class ServerStreamMidDropRecoveryTest {

    private static final ProviderConnectivityPolicy FAST_POLICY =
            new ProviderConnectivityPolicy(
                    Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofMillis(100),
                    Duration.ofSeconds(1), 4, Duration.ofMillis(5), Duration.ofMillis(20));

    @TempDir
    java.nio.file.Path directory;

    @Test
    void midStreamCloseWithNothingStreamedResendsTheTurnAndRecovers() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServerFixture fixture = new HttpServerFixture(exchange -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                respondThenClose(exchange, ""); // connection dies before any event
            } else {
                respondSse(exchange, "event: complete\n\n");
            }
        });
        AgenticChatLoop loop = loop(fixture.server);
        try (fixture) {
            var method = AgenticChatLoop.class.getDeclaredMethod("streamServerTurn",
                    String.class, String.class, String.class, boolean.class,
                    String.class, com.fasterxml.jackson.databind.node.ArrayNode.class,
                    List.class);
            method.setAccessible(true);
            AgenticChatLoop.StreamResult result = (AgenticChatLoop.StreamResult)
                    method.invoke(loop, "hello", "session", "claude", false, "", null, null);

            assertFalse(result.failed);
            assertEquals(2, calls.get(), "a pre-stream drop must consume one retry");
        }
    }

    @Test
    void midStreamCloseWithPartialTextContinuesAndKeepsThePartialAnswer() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServerFixture fixture = new HttpServerFixture(exchange -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                respondThenClose(exchange, "event: chunk\ndata: \"partial answer\"\n\n");
            } else {
                respondSse(exchange,
                        "event: chunk\ndata: \" rest\"\n\nevent: complete\n\n");
            }
        });
        AgenticChatLoop loop = loop(fixture.server);
        try (fixture) {
            var method = AgenticChatLoop.class.getDeclaredMethod("streamServerTurn",
                    String.class, String.class, String.class, boolean.class,
                    String.class, com.fasterxml.jackson.databind.node.ArrayNode.class,
                    List.class);
            method.setAccessible(true);
            AgenticChatLoop.StreamResult result = (AgenticChatLoop.StreamResult)
                    method.invoke(loop, "hello", "session", "claude", false, "", null, null);

            assertFalse(result.failed);
            assertEquals("partial answer rest", result.text,
                    "the partial text and the continuation must both survive");
            assertEquals(2, calls.get());
        }
    }

    @Test
    void aDropAfterToolCallsSurfacesTheToolCallsInsteadOfRetryingText() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServerFixture fixture = new HttpServerFixture(exchange -> {
            calls.incrementAndGet();
            respondThenClose(exchange,
                    "event: tool_call\ndata: {\"id\":\"call-1\",\"name\":\"read\","
                            + "\"arguments\":{}}\n\n");
        });
        AgenticChatLoop loop = loop(fixture.server);
        try (fixture) {
            var method = AgenticChatLoop.class.getDeclaredMethod("streamServerTurn",
                    String.class, String.class, String.class, boolean.class,
                    String.class, com.fasterxml.jackson.databind.node.ArrayNode.class,
                    List.class);
            method.setAccessible(true);
            AgenticChatLoop.StreamResult result = (AgenticChatLoop.StreamResult)
                    method.invoke(loop, "hello", "session", "claude", false, "", null, null);

            assertEquals(1, calls.get(),
                    "a dropped turn that already produced tool calls must not be replayed");
            assertEquals(1, result.toolCalls.size());
            assertEquals("read", result.toolCalls.get(0).name);
        }
    }

    @Test
    void budgetExhaustionKeepsThePartialAnswerInsteadOfKillingTheSession() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServerFixture fixture = new HttpServerFixture(exchange -> {
            calls.incrementAndGet();
            respondThenClose(exchange, "event: chunk\ndata: \"kept\"\n\n");
        });
        AgenticChatLoop loop = loop(fixture.server);
        try (fixture) {
            var method = AgenticChatLoop.class.getDeclaredMethod("streamServerTurn",
                    String.class, String.class, String.class, boolean.class,
                    String.class, com.fasterxml.jackson.databind.node.ArrayNode.class,
                    List.class);
            method.setAccessible(true);
            AgenticChatLoop.StreamResult result = (AgenticChatLoop.StreamResult)
                    method.invoke(loop, "hello", "session", "claude", false, "", null, null);

            assertFalse(result.failed,
                    "partial output must survive even when every retry also dropped");
            assertTrue(result.text.startsWith("kept"));
            assertEquals(FAST_POLICY.maxAttempts(), calls.get());
        }
    }

    private AgenticChatLoop loop(HttpServer server) {
        ObjectMapper mapper = JsonUtils.standardMapper();
        ChatCompleter.setContentOutput(ignored -> { });
        return new AgenticChatLoop("http://127.0.0.1:" + server.getAddress().getPort(),
                mapper, new ToolRegistry(mapper), new PermissionService(),
                new AgentRegistry(), directory, null, null);
    }

    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws Exception;
    }

    private static final class HttpServerFixture implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor;

        HttpServerFixture(ExchangeHandler handler) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor = Executors.newCachedThreadPool();
            server.setExecutor(executor);
            server.createContext("/api/agents/chat/stream", exchange -> {
                try {
                    exchange.getRequestBody().readAllBytes();
                    handler.handle(exchange);
                } catch (Exception failure) {
                    exchange.close();
                }
            });
            server.start();
        }

        HttpServer server() {
            return server;
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    /** Streams the given SSE body, then closes the connection without a terminal event. */
    private static void respondThenClose(HttpExchange exchange, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
                out.flush();
            }
        } else {
            exchange.close();
        }
    }

    private static void respondSse(HttpExchange exchange, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
            out.flush();
        }
    }
}
