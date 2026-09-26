package ai.kompile.cli.main.chat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stand-in for {@code opencode serve} whose turn runs until the session is
 * aborted, like a turn blocked in a long tool call.
 */
final class HangingOpenCodeServer implements AutoCloseable {

    final CountDownLatch turnStarted = new CountDownLatch(1);
    final CountDownLatch aborted = new CountDownLatch(1);
    final AtomicInteger abortCalls = new AtomicInteger();

    private final String sessionId;
    private final ExecutorService handlers = Executors.newCachedThreadPool();
    private final HttpServer server;

    HangingOpenCodeServer(String sessionId) throws IOException {
        this.sessionId = sessionId;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // The turn and event handlers block, so each exchange needs its own thread.
        server.setExecutor(handlers);
        server.createContext("/event", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            awaitAbort();
            exchange.close();
        });
        server.createContext("/session/" + sessionId + "/message", exchange -> {
            exchange.getRequestBody().readAllBytes();
            turnStarted.countDown();
            awaitAbort();
            respond(exchange, "{\"info\":{\"role\":\"assistant\","
                    + "\"error\":{\"name\":\"MessageAbortedError\"}},\"parts\":[]}");
        });
        server.createContext("/session/" + sessionId + "/abort", exchange -> {
            abortCalls.incrementAndGet();
            aborted.countDown();
            respond(exchange, "true");
        });
        server.start();
    }

    /** A transport attached to this server's session. */
    OpenCodeServeClient client(ObjectMapper objectMapper) {
        return new OpenCodeServeClient(objectMapper, Path.of("."), HttpClient.newHttpClient(),
                "http://127.0.0.1:" + server.getAddress().getPort(), sessionId);
    }

    private void awaitAbort() {
        try {
            aborted.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
        handlers.shutdownNow();
    }
}
