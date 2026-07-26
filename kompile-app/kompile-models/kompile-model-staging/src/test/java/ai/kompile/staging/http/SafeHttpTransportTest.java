package ai.kompile.staging.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeHttpTransportTest {

    private HttpServer server;
    private InetAddress loopback;

    @BeforeEach
    void startServer() throws Exception {
        loopback = InetAddress.getByName("127.0.0.1");
        server = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void connectionManagerUsesTheValidatedResolverAnswer() throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        server.createContext("/model", exchange -> respond(exchange, 200, "model"));
        DnsResolver delegate = resolver(host -> {
            resolutions.incrementAndGet();
            return new InetAddress[] {loopback};
        });

        try (SafeHttpTransport transport = transport(delegate);
             SafeHttpTransport.Response response = transport.execute(
                     uri("model.test", "/model"),
                     "GET",
                     Map.of(),
                     2_000,
                     2_000,
                     0)) {
            assertEquals(200, response.statusCode());
            assertEquals(
                    "model",
                    new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
        }

        assertTrue(resolutions.get() >= 1);
    }

    @Test
    void redirectTargetIsResolvedAndBlockedBeforePrivateConnection()
            throws Exception {
        AtomicInteger privateResolutions = new AtomicInteger();
        server.createContext("/redirect-private", exchange -> {
            exchange.getResponseHeaders().add(
                    "Location", uri("metadata.test", "/metadata").toString());
            respond(exchange, 302, "");
        });
        DnsResolver delegate = resolver(host -> {
            if ("metadata.test".equals(host)) {
                privateResolutions.incrementAndGet();
                return new InetAddress[] {
                        InetAddress.getByName("169.254.169.254")
                };
            }
            return new InetAddress[] {loopback};
        });

        try (SafeHttpTransport transport = transport(delegate)) {
            assertThrows(
                    UnknownHostException.class,
                    () -> transport.execute(
                            uri("model.test", "/redirect-private"),
                            "GET",
                            Map.of(),
                            2_000,
                            2_000,
                            3));
        }

        assertEquals(1, privateResolutions.get());
    }

    @Test
    void stripsAuthorizationAcrossOriginsButRetainsItOnSameOrigin()
            throws Exception {
        AtomicReference<String> crossOriginAuthorization = new AtomicReference<>();
        AtomicReference<String> sameOriginAuthorization = new AtomicReference<>();
        server.createContext("/cross-start", exchange -> {
            exchange.getResponseHeaders().add(
                    "Location", uri("cdn.test", "/cross-final").toString());
            respond(exchange, 302, "");
        });
        server.createContext("/cross-final", exchange -> {
            crossOriginAuthorization.set(
                    exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "cross");
        });
        server.createContext("/same-start", exchange -> {
            exchange.getResponseHeaders().add("Location", "/same-final");
            respond(exchange, 302, "");
        });
        server.createContext("/same-final", exchange -> {
            sameOriginAuthorization.set(
                    exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "same");
        });

        try (SafeHttpTransport transport = transport(loopbackResolver())) {
            try (SafeHttpTransport.Response ignored = transport.execute(
                    uri("model.test", "/cross-start"),
                    "GET",
                    Map.of("Authorization", "Bearer secret"),
                    2_000,
                    2_000,
                    3)) {
                // Response closure is the assertion-relevant behavior here.
            }
            try (SafeHttpTransport.Response ignored = transport.execute(
                    uri("model.test", "/same-start"),
                    "GET",
                    Map.of("Authorization", "Bearer secret"),
                    2_000,
                    2_000,
                    3)) {
                // Response closure is the assertion-relevant behavior here.
            }
        }

        assertNull(crossOriginAuthorization.get());
        assertEquals("Bearer secret", sameOriginAuthorization.get());
    }

    @Test
    void preservesSignedRedirectQueryWithoutExposingItInDiagnostics()
            throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        server.createContext("/signed-start", exchange -> {
            exchange.getResponseHeaders().add(
                    "Location", "/signed-final?signature=top-secret");
            respond(exchange, 302, "");
        });
        server.createContext("/signed-final", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, "signed");
        });

        try (SafeHttpTransport transport = transport(loopbackResolver());
             SafeHttpTransport.Response response = transport.execute(
                     uri("model.test", "/signed-start"),
                     "GET",
                     Map.of(),
                     2_000,
                     2_000,
                     3)) {
            assertEquals(200, response.statusCode());
            assertFalse(SafeHttpTransport.safeUriForDiagnostics(response.uri())
                    .contains("top-secret"));
        }

        assertEquals("signature=top-secret", query.get());
    }

    @Test
    void rejectsRedirectLoopsAndRedirectsBeyondTheConfiguredLimit()
            throws Exception {
        server.createContext("/loop-a", exchange -> {
            exchange.getResponseHeaders().add("Location", "/loop-b");
            respond(exchange, 302, "");
        });
        server.createContext("/loop-b", exchange -> {
            exchange.getResponseHeaders().add("Location", "/loop-a");
            respond(exchange, 302, "");
        });

        try (SafeHttpTransport transport = transport(loopbackResolver())) {
            IOException loop = assertThrows(
                    IOException.class,
                    () -> transport.execute(
                            uri("model.test", "/loop-a"),
                            "GET",
                            Map.of(),
                            2_000,
                            2_000,
                            5));
            assertTrue(loop.getMessage().contains("loop"));

            IOException limit = assertThrows(
                    IOException.class,
                    () -> transport.execute(
                            uri("model.test", "/loop-a"),
                            "GET",
                            Map.of(),
                            2_000,
                            2_000,
                            0));
            assertTrue(limit.getMessage().contains("limit"));
        }
    }

    @Test
    void rejectsCredentialsFragmentsAndNonHttpSchemes() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SafeHttpTransport.validateRemoteUri(
                        URI.create("https://user:pass@example.com/model")));
        assertThrows(
                IllegalArgumentException.class,
                () -> SafeHttpTransport.validateRemoteUri(
                        URI.create("https://example.com/model#fragment")));
        assertThrows(
                IllegalArgumentException.class,
                () -> SafeHttpTransport.validateRemoteUri(
                        URI.create("file:///tmp/model")));
    }

    private SafeHttpTransport transport(DnsResolver delegate) {
        return new SafeHttpTransport(
                new PublicAddressDnsResolver(delegate, true));
    }

    private DnsResolver loopbackResolver() {
        return resolver(host -> new InetAddress[] {loopback});
    }

    private URI uri(String host, String path) {
        return URI.create("http://" + host + ":" + server.getAddress().getPort() + path);
    }

    private static DnsResolver resolver(Resolution resolution) {
        return new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                return resolution.resolve(host);
            }

            @Override
            public String resolveCanonicalHostname(String host)
                    throws UnknownHostException {
                resolution.resolve(host);
                return host;
            }
        };
    }

    private static void respond(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface Resolution {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }
}
