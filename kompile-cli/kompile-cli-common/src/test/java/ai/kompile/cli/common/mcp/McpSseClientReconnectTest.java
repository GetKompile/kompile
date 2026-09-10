package ai.kompile.cli.common.mcp;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpSseClientReconnectTest {

    @Test
    void reconnectsDroppedSseAndRestoresInitializedProtocol() throws Exception {
        try (ReconnectableMcpServer server = new ReconnectableMcpServer();
             McpSseClient client = new McpSseClient(
                     server.baseUrl(), Duration.ofSeconds(1), Duration.ofSeconds(1),
                     Duration.ofSeconds(2), 3, Duration.ofMillis(10))) {
            client.connect();
            client.initialize();
            client.notifyInitialized();
            assertTrue(client.isConnected());

            server.dropFirstConnection();
            await(() -> !client.isConnected(), Duration.ofSeconds(2));
            assertFalse(client.isConnected());

            List<McpSseClient.ToolInfo> tools = client.listTools();

            assertEquals(1, tools.size());
            assertEquals("read", tools.get(0).getName());
            assertEquals(2, server.sseConnections.get());
            assertEquals(2, server.methods.stream().filter("initialize"::equals).count(),
                    "a new SSE session must repeat the MCP initialize handshake");
            assertEquals(2, server.methods.stream()
                    .filter("notifications/initialized"::equals).count());
            assertEquals(1, server.methods.stream().filter("tools/list"::equals).count());
        }
    }

    private static void await(Check check, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!check.get() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(check.get(), "condition was not met within " + timeout);
    }

    @FunctionalInterface
    private interface Check {
        boolean get() throws Exception;
    }

    private static final class ReconnectableMcpServer implements AutoCloseable {
        private final ObjectMapper mapper = JsonUtils.standardMapper();
        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final CountDownLatch dropFirst = new CountDownLatch(1);
        private final CountDownLatch shutdown = new CountDownLatch(1);
        private final AtomicReference<OutputStream> currentSse = new AtomicReference<>();
        private final AtomicInteger sseConnections = new AtomicInteger();
        private final List<String> methods = new CopyOnWriteArrayList<>();

        private ReconnectableMcpServer() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/sse", this::handleSse);
            server.createContext("/message", this::handleMessage);
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void dropFirstConnection() {
            dropFirst.countDown();
        }

        private void handleSse(HttpExchange exchange) {
            int connection = sseConnections.incrementAndGet();
            OutputStream stream = null;
            try {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                stream = exchange.getResponseBody();
                currentSse.set(stream);
                write(stream, "event: endpoint\ndata: /message\n\n");
                if (connection == 1) {
                    dropFirst.await(5, TimeUnit.SECONDS);
                } else {
                    shutdown.await(5, TimeUnit.SECONDS);
                }
            } catch (Exception ignored) {
                // Client-side close is expected during reconnect and shutdown.
            } finally {
                if (stream != null) {
                    try {
                        stream.close();
                    } catch (Exception ignored) {
                    }
                    currentSse.compareAndSet(stream, null);
                }
                exchange.close();
            }
        }

        private void handleMessage(HttpExchange exchange) {
            try {
                JsonNode request = mapper.readTree(exchange.getRequestBody());
                String method = request.path("method").asText();
                methods.add(method);
                if (request.has("id")) {
                    int id = request.path("id").asInt();
                    String result = "tools/list".equals(method)
                            ? "{\"tools\":[{\"name\":\"read\",\"description\":\"Read\",\"inputSchema\":{}}]}"
                            : "{\"protocolVersion\":\"2024-11-05\"}";
                    writeCurrent("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":"
                            + id + ",\"result\":" + result + "}\n\n");
                }
                byte[] accepted = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, accepted.length);
                exchange.getResponseBody().write(accepted);
            } catch (Exception failure) {
                try {
                    exchange.sendResponseHeaders(500, -1);
                } catch (Exception ignored) {
                }
            } finally {
                exchange.close();
            }
        }

        private void writeCurrent(String event) throws Exception {
            OutputStream stream = currentSse.get();
            if (stream == null) throw new IllegalStateException("No active SSE stream");
            write(stream, event);
        }

        private static void write(OutputStream stream, String event) throws Exception {
            synchronized (stream) {
                stream.write(event.getBytes(StandardCharsets.UTF_8));
                stream.flush();
            }
        }

        @Override
        public void close() {
            shutdown.countDown();
            dropFirst.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
