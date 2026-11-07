package ai.kompile.cli.remote.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class OpenAiChatExchangeTest {
    @Test
    void synchronousSendPreservesPostHeadersBodyAndNonSuccessResponse() throws Exception {
        record Received(String method, String contentType, String auth, String custom, String body) { }
        AtomicReference<Received> received = new AtomicReference<>();
        String requestBody = "{\"model\":\"alias\",\"messages\":[],\"stream\":true}";
        try (LocalServer server = new LocalServer(exchange -> {
            try (exchange) {
                received.set(new Received(exchange.getRequestMethod(),
                        exchange.getRequestHeaders().getFirst("Content-Type"),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("X-Wire"),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                byte[] error = "{\"error\":\"limited\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("X-Reply", "preserved");
                exchange.sendResponseHeaders(429, error.length);
                exchange.getResponseBody().write(error);
            }
        })) {
            try (OpenAiChatExchange response = OpenAiChatExchange.send(server.client(), server.request(requestBody))) {
                assertEquals(429, response.response().statusCode());
                assertEquals("preserved", response.response().headers().firstValue("X-Reply").orElseThrow());
                assertEquals("{\"error\":\"limited\"}",
                        new String(response.response().body().readAllBytes(), StandardCharsets.UTF_8));
                assertEquals(new Received("POST", "application/json", "Bearer example", "kept", requestBody), received.get());
                response.close(); // Repeated close, including try-with-resources, is safe.
            }
        }
    }

    @Test
    void cancellationBeforeHeadersUnblocksSendAndDisconnectsSocket() throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        CountDownLatch releaseHeaders = new CountDownLatch(1);
        CountDownLatch disconnected = new CountDownLatch(1);
        try (LocalServer server = new LocalServer(exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                requestReceived.countDown();
                awaitGate(releaseHeaders);
                writeUntilDisconnected(exchange, disconnected);
            }
        }); OpenAiChatExchange.Cancellation cancellation = new OpenAiChatExchange.Cancellation()) {
            Future<OpenAiChatExchange> sending = server.workers.submit(
                    () -> OpenAiChatExchange.send(server.client(), server.request("{}"), cancellation));
            await(requestReceived);
            assertFalse(sending.isDone(), "Server deliberately has not sent headers");
            cancellation.cancel();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> sending.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, failure.getCause());
            assertTrue(cancellation.isCancelled());
            releaseHeaders.countDown();
            await(disconnected); // Server observes the aborted connection, not merely a local flag.
        } finally {
            releaseHeaders.countDown();
        }
    }

    @Test
    void cancellationAfterBodyReturnUnblocksDecoderAndDisconnectsSocket() throws Exception {
        CountDownLatch firstText = new CountDownLatch(1);
        CountDownLatch releaseWrites = new CountDownLatch(1);
        CountDownLatch disconnected = new CountDownLatch(1);
        try (LocalServer server = new LocalServer(exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"ready\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                awaitGate(releaseWrites); // No EOF or next event until the client cancels.
                writeBodyUntilDisconnected(exchange, disconnected);
            }
        }); OpenAiChatExchange.Cancellation cancellation = new OpenAiChatExchange.Cancellation();
             OpenAiChatExchange response = OpenAiChatExchange.send(server.client(), server.request("{}"), cancellation)) {
            assertEquals(200, response.response().statusCode());
            var state = new OpenAiChatStreamDecoder.State();
            Future<?> decoding = server.workers.submit(() -> {
                try {
                    OpenAiChatStreamDecoder.decode(response.response().body(), new ObjectMapper(),
                            cancellation::isCancelled, new OpenAiChatStreamDecoder.Listener() {
                                @Override public void onText(String text) { firstText.countDown(); }
                            }, state);
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });
            await(firstText);
            assertFalse(decoding.isDone(), "Open body has neither EOF nor a terminal event");
            cancellation.cancel();
            decoding.get(5, TimeUnit.SECONDS); // No polling thread or runtime scheduler unblocks readLine.
            assertTrue(state.cancelled);
            assertEquals("ready", state.text);
            assertFalse(state.terminalEventSeen);
            assertFalse(state.silentStreamDrop);
            response.close();
            cancellation.cancel();
            releaseWrites.countDown();
            await(disconnected);
        } finally {
            releaseWrites.countDown();
        }
    }

    @Test
    void preCancelledHandleDoesNotDispatch() throws Exception {
        ControlledClient client = new ControlledClient();
        try (OpenAiChatExchange.Cancellation cancellation = new OpenAiChatExchange.Cancellation()) {
            cancellation.cancel();
            assertThrows(IOException.class, () -> OpenAiChatExchange.send(client, sampleRequest(), cancellation));
            assertEquals(1, client.entered.getCount());
        }
    }

    @Test
    void cancellationRacingFutureBindClosesLateBodyExactlyOnce() throws Exception {
        ControlledClient client = new ControlledClient();
        AtomicInteger bodyCloses = new AtomicInteger();
        InputStream body = countingBody(bodyCloses);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (OpenAiChatExchange.Cancellation cancellation = new OpenAiChatExchange.Cancellation()) {
            Future<OpenAiChatExchange> sending = worker.submit(
                    () -> OpenAiChatExchange.send(client, sampleRequest(), cancellation));
            await(client.entered); // sendAsync called, but the future has not yet been bound.
            cancellation.cancel();
            client.pending.complete(response(body)); // Headers/body arrive before sendAsync returns.
            client.release.countDown();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> sending.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, failure.getCause());
            cancellation.close();
            assertEquals(1, bodyCloses.get());
        } finally {
            client.release.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void exchangeAndCancellationCloseShareBodyOwnership() throws Exception {
        ControlledClient client = new ControlledClient();
        AtomicInteger bodyCloses = new AtomicInteger();
        client.pending.complete(response(countingBody(bodyCloses)));
        client.release.countDown();
        try (OpenAiChatExchange.Cancellation cancellation = new OpenAiChatExchange.Cancellation();
             OpenAiChatExchange exchange = OpenAiChatExchange.send(client, sampleRequest(), cancellation)) {
            exchange.close();
            exchange.close();
            assertFalse(cancellation.isCancelled());
            cancellation.cancel();
            assertEquals(1, bodyCloses.get());
        }
        assertEquals(1, bodyCloses.get());
    }

    private static void writeUntilDisconnected(HttpExchange exchange, CountDownLatch disconnected) {
        try {
            exchange.sendResponseHeaders(200, 0);
            writeBodyUntilDisconnected(exchange, disconnected);
        } catch (IOException expectedDisconnect) {
            disconnected.countDown();
        }
    }

    private static void writeBodyUntilDisconnected(HttpExchange exchange, CountDownLatch disconnected) {
        byte[] block = new byte[64 * 1024];
        try {
            // Bounded bytes, no sleeps. More than socket buffering so a closed peer must be observed.
            for (int i = 0; i < 512; i++) {
                exchange.getResponseBody().write(block);
                exchange.getResponseBody().flush();
            }
        } catch (IOException expectedDisconnect) {
            disconnected.countDown();
        }
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out awaiting transport event");
    }

    private static void awaitGate(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IOException("Server gate timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
        }
    }

    private static HttpRequest sampleRequest() {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1/unused")).POST(HttpRequest.BodyPublishers.noBody()).build();
    }

    private static InputStream countingBody(AtomicInteger closes) {
        return new ByteArrayInputStream(new byte[0]) {
            @Override public void close() throws IOException { closes.incrementAndGet(); super.close(); }
        };
    }

    private static HttpResponse<InputStream> response(InputStream body) {
        return new HttpResponse<>() {
            @Override public int statusCode() { return 200; }
            @Override public HttpRequest request() { return sampleRequest(); }
            @Override public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
            @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (name, value) -> true); }
            @Override public InputStream body() { return body; }
            @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
            @Override public URI uri() { return sampleRequest().uri(); }
            @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }

    /** Real JDK HTTP fixtures above; this controllable client only forces the narrow late-bind race. */
    private static final class ControlledClient extends HttpClient {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CompletableFuture<HttpResponse<InputStream>> pending = new CompletableFuture<>();

        @Override @SuppressWarnings("unchecked")
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            entered.countDown();
            try {
                awaitGate(release);
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
            return (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) pending;
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> pushHandler) {
            return sendAsync(request, handler);
        }
        @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { throw new UnsupportedOperationException(); }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
    }

    private static final class LocalServer implements AutoCloseable {
        private final ExecutorService workers = Executors.newCachedThreadPool();
        private final ExecutorService handlers = Executors.newCachedThreadPool();
        private final HttpServer server;
        private final HttpClient client;

        private LocalServer(HttpHandler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(handlers);
            server.createContext("/chat", handler);
            server.start();
            client = HttpClient.newBuilder().executor(workers).version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(3)).build();
        }

        private HttpClient client() { return client; }
        private HttpRequest request(String body) {
            return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat"))
                    .header("Content-Type", "application/json").header("Authorization", "Bearer example")
                    .header("X-Wire", "kept").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        }
        @Override public void close() {
            server.stop(0);
            handlers.shutdownNow();
            workers.shutdownNow();
        }
    }
}
