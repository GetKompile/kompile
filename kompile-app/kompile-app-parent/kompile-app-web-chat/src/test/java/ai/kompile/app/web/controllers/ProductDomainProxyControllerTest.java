package ai.kompile.app.web.controllers;

import ai.kompile.app.services.agent.ProductChatSessionService;
import ai.kompile.app.services.agent.KompileCliSessionWorker;
import org.junit.jupiter.api.BeforeEach;
import ai.kompile.cli.common.ProductChatPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Source-only tests: pure MVC/mocks plus disposable loopback HttpServer; no product app/provider. */
class ProductDomainProxyControllerTest {
    private static final String RUNTIME = "643b9814-ea85-401c-9a33-160f84955aa2";
    private static final String PREFIX = ProductDomainProxyController.BASE + "/" + RUNTIME;
    private final ObjectMapper json = new ObjectMapper();
    private final ProductChatSessionService products = mock(ProductChatSessionService.class);
    private final ProductChatPolicy policy = mock(ProductChatPolicy.class);
    private final ProductDomainProxyController controller = new ProductDomainProxyController(products);
    private final List<HttpServer> servers = new ArrayList<>();

    private final KompileCliSessionWorker.ExternalOperation operation = mock(KompileCliSessionWorker.ExternalOperation.class);

    @BeforeEach @SuppressWarnings("unchecked") void domainAdmission() {
        when(products.withDomainConnection(any())).thenAnswer(invocation -> {
            synchronized (products) {
                ProductChatSessionService.DomainRelay<Object> relay = invocation.getArgument(0);
                return relay.relay(products.readCapability("connection"), operation);
            }
        });
    }

    @AfterEach void stopServers() { servers.forEach(server -> server.stop(0)); }

    @Test void fourExactRoutesRelayTheirClosedTargetsAndNeverBrowserCredentialsOrRuntimeOverrides() throws Exception {
        List<String> targets = new ArrayList<>();
        List<byte[]> bodies = new ArrayList<>();
        HttpServer server = server(exchange -> {
            assertEquals(RUNTIME, exchange.getRequestHeaders().getFirst(ProductDomainProxyController.RUNTIME_HEADER));
            assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
            assertNull(exchange.getRequestHeaders().getFirst("Cookie"));
            assertNull(exchange.getRequestHeaders().getFirst("X-Forwarded-Host"));
            targets.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            bodies.add(exchange.getRequestBody().readAllBytes());
            exchange.getResponseHeaders().add("Set-Cookie", "not-for-browser=value");
            exchange.getResponseHeaders().add("Location", "http://must-not-forward.invalid/");
            reply(exchange, 200, "{\"revision\":7,\"gmOnly\":true}");
        });
        bind(server);
        for (String path : new String[]{"/api/tactical-boards/12", "/api/world-sim/5/story-candidates",
                "/api/world-sim/5/decision-workbench"}) {
            MockHttpServletRequest incoming = incoming("GET", path, "campaignId=7");
            incoming.addHeader("Authorization", "Bearer browser-value");
            incoming.addHeader("Cookie", "browser=value");
            incoming.addHeader("X-Forwarded-Host", "untrusted.invalid");
            incoming.addHeader(ProductDomainProxyController.RUNTIME_HEADER, UUID.randomUUID().toString());
            ResponseEntity<byte[]> response = controller.proxy(RUNTIME, incoming);
            assertEquals(200, response.getStatusCode().value());
            assertEquals("{\"revision\":7,\"gmOnly\":true}", utf8(response.getBody()));
            assertNull(response.getHeaders().getFirst("Set-Cookie"));
            assertNull(response.getHeaders().getFirst("Location"));
            assertEquals("no-store", response.getHeaders().getFirst("Cache-Control"));
        }
        byte[] command = "{ \"campaignId\":7, \"expectedRevision\":6, \"command\":\"move\" }".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest post = incoming("POST", "/api/tactical-boards/12/commands", null);
        post.setContentType("application/json; charset=UTF-8"); post.setContent(command);
        assertEquals(200, controller.proxy(RUNTIME, post).getStatusCode().value());
        assertEquals(List.of("GET /api/tactical-boards/12?campaignId=7", "GET /api/world-sim/5/story-candidates?campaignId=7",
                "GET /api/world-sim/5/decision-workbench?campaignId=7", "POST /api/tactical-boards/12/commands"), targets);
        assertArrayEquals(command, bodies.get(3)); // No JSON normalization or second revision/ownership authority.
        verify(products, times(4)).readCapability("connection");
    }

    @Test void serverPolicyAndCapturedConnectionAreMandatoryAndBusy409IsPreservedWithoutDiagnostics() throws Exception {
        approvedPolicy();
        when(products.readCapability("connection")).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "internal detail"));
        ResponseEntity<byte[]> busy = controller.proxy(RUNTIME, incoming("GET", "/api/tactical-boards/1", "campaignId=1"));
        assertEquals(409, busy.getStatusCode().value());
        assertEquals("runtime_busy", code(busy)); assertFalse(utf8(busy.getBody()).contains("internal detail"));
        when(policy.id()).thenReturn("other-product");
        assertEquals(404, controller.proxy(RUNTIME, incoming("GET", "/api/tactical-boards/1", "campaignId=1")).getStatusCode().value());
        when(policy.id()).thenReturn("elthoria-play");
        when(policy.readCapabilities()).thenReturn(Map.of("connection", "mcp__other__get_play_connection"));
        assertEquals(404, controller.proxy(RUNTIME, incoming("GET", "/api/tactical-boards/1", "campaignId=1")).getStatusCode().value());
    }

    @Test void onlyLiteralOriginOnlyLoopbackAndExactReadyGmContractAreAccepted() throws Exception {
        approvedPolicy();
        for (String url : new String[]{"http://localhost:80", "http://evil.invalid:80", "http://127.0.0.1.evil.invalid:80",
                "http://127.0.0.2:80", "http://2130706433:80", "http://0177.0.0.1:80", "http://0.0.0.0:80",
                "http://127.0.0.1:0", "http://127.0.0.1:65536", "http://127.0.0.1", "https://127.0.0.1:80",
                "http://user@127.0.0.1:80", "http://user:pass@127.0.0.1:80", "http://127.0.0.1:80/",
                "http://127.0.0.1:80/api", "http://127.0.0.1:80?x=1", "http://127.0.0.1:80#fragment",
                "http://[::ffff:127.0.0.1]:80", "http://[::1%25lo]:80", "http://[::]:80", "http://[::1]:80/"}) {
            when(products.readCapability("connection")).thenReturn(connection(url));
            ResponseEntity<byte[]> response = controller.proxy(RUNTIME, incoming("GET", "/api/tactical-boards/1", "campaignId=1"));
            assertEquals(503, response.getStatusCode().value(), url);
            assertEquals("connection_unavailable", code(response));
            assertFalse(utf8(response.getBody()).contains(url));
        }
        assertEquals(URI.create("http://127.0.0.1:65535"), ProductDomainProxyController.origin(connection("http://127.0.0.1:65535"), RUNTIME));
        assertEquals(URI.create("http://[::1]:1"), ProductDomainProxyController.origin(connection("http://[::1]:1"), RUNTIME));
        for (String field : new String[]{"schemaVersion", "status", "localOnly", "tacticalAudience", "tacticalPlayerSafe",
                "authentication", "transport", "lifecycle", "uiStarted", "runtimeId"}) {
            ObjectNode invalid = connection("http://127.0.0.1:1"); invalid.remove(field);
            assertThrows(RuntimeException.class, () -> ProductDomainProxyController.origin(invalid, RUNTIME), field);
        }
        for (ObjectNode invalid : List.of(connection("http://127.0.0.1:1").put("localOnly", "true"),
                connection("http://127.0.0.1:1").put("tacticalPlayerSafe", true),
                connection("http://127.0.0.1:1").put("tacticalAudience", "PLAYER"),
                connection("http://127.0.0.1:1").put("status", "NOT_READY"))) {
            assertThrows(RuntimeException.class, () -> ProductDomainProxyController.origin(invalid, RUNTIME));
        }
        when(products.readCapability("connection")).thenReturn(connection("http://127.0.0.1:1").put("runtimeId", UUID.randomUUID().toString()));
        ResponseEntity<byte[]> stale = controller.proxy(RUNTIME, incoming("POST", "/api/tactical-boards/1/commands", null, "{}"));
        assertEquals(409, stale.getStatusCode().value()); assertEquals("runtime_changed", code(stale));
    }

    @Test void exactQueryGrammarRejectsDuplicatesExtrasEscapesAndUnsafeNumbersBeforeCapture() {
        for (String query : new String[]{null, "", "campaignId=0", "campaignId=-1", "campaignId=1.0", "campaignId=01",
                "campaignId=9007199254740992", "campaignId=1&campaignId=1", "campaignId=1&campaignId=2",
                "campaignId=1&url=http://127.0.0.1", "campaignId=1&limit=2", "campaignId=%31", "campaignId=+1",
                "campaignId=1&", "campaignId=1=2", "campaign%49d=1"}) {
            assertEquals(400, controller.proxy(RUNTIME, incoming("GET", "/api/tactical-boards/1", query)).getStatusCode().value(), query);
        }
        for (String query : new String[]{"campaignId=1&limit=0", "campaignId=1&limit=25", "campaignId=1&limit=1&limit=2",
                "limit=1", "campaignId=1&extra=x"}) {
            assertEquals(400, controller.proxy(RUNTIME, incoming("GET", "/api/world-sim/1/story-candidates", query)).getStatusCode().value());
        }
        for (String id : new String[]{"0", "01", "9007199254740992", "99999999999999999999"}) {
            assertEquals(400, controller.proxy(RUNTIME, incoming("GET", "/api/tactical-boards/" + id, "campaignId=1")).getStatusCode().value());
        }
        assertEquals(400, controller.proxy(RUNTIME, incoming("POST", "/api/tactical-boards/1/commands", "campaignId=1", "{}")).getStatusCode().value());
        verifyNoInteractions(products);
    }

    @Test void safeIntegerBoundaryAndOptionalStoryLimitAreRelayedInCanonicalQueryOrder() throws Exception {
        AtomicReference<String> actual = new AtomicReference<>();
        HttpServer server = server(exchange -> { actual.set(exchange.getRequestURI().toString()); reply(exchange, 200, "{}"); });
        bind(server);
        assertEquals(200, controller.proxy(RUNTIME, incoming("GET", "/api/world-sim/9007199254740991/story-candidates",
                "limit=24&campaignId=9007199254740991")).getStatusCode().value());
        assertEquals("/api/world-sim/9007199254740991/story-candidates?campaignId=9007199254740991&limit=24", actual.get());
    }

    @Test void wrongMethodsAndAllOtherPathsAre404IncludingHeadOptionsAndEncodedTraversal() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller, new ProductChatController(products)).build();
        for (HttpMethod method : new HttpMethod[]{HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.POST}) {
            mvc.perform(request(method, PREFIX + "/api/tactical-boards/1")).andExpect(status().isNotFound());
        }
        for (HttpMethod method : new HttpMethod[]{HttpMethod.GET, HttpMethod.PUT, HttpMethod.HEAD, HttpMethod.OPTIONS}) {
            mvc.perform(request(method, PREFIX + "/api/tactical-boards/1/commands")).andExpect(status().isNotFound());
        }
        for (String path : new String[]{"/api/world-sim/1/advance", "/api/world-sim/1", "/api/tactical-boards/1/commands/extra",
                "/api/tactical-boards/1/", "/api/tactical-boards/../1", "/api/tactical-boards/%31", "/api/config", "/anything"}) {
            assertEquals(404, controller.proxy(RUNTIME, incoming("GET", path, "campaignId=1")).getStatusCode().value(), path);
        }
        mvc.perform(request(HttpMethod.GET, PREFIX + "/api/config")).andExpect(status().isNotFound());
        verifyNoInteractions(products);
    }

    @Test void requestBodyAndHeaderLimitsAreCheckedBeforeCapabilityRead() {
        MockHttpServletRequest over = incoming("POST", "/api/tactical-boards/1/commands", null);
        over.setContentType("application/json"); over.setContent(new byte[150_001]);
        assertEquals(413, controller.proxy(RUNTIME, over).getStatusCode().value());
        MockHttpServletRequest chunked = new MockHttpServletRequest("POST", PREFIX + "/api/tactical-boards/1/commands") {
            @Override public long getContentLengthLong() { return -1; }
        };
        chunked.setContentType("application/json"); chunked.setContent(new byte[150_001]);
        assertEquals(413, controller.proxy(RUNTIME, chunked).getStatusCode().value());
        MockHttpServletRequest wrongType = incoming("POST", "/api/tactical-boards/1/commands", null, "{}");
        wrongType.setContentType("text/plain");
        assertEquals(415, controller.proxy(RUNTIME, wrongType).getStatusCode().value());
        MockHttpServletRequest encoded = incoming("POST", "/api/tactical-boards/1/commands", null, "{}");
        encoded.addHeader("Content-Encoding", "gzip");
        assertEquals(415, controller.proxy(RUNTIME, encoded).getStatusCode().value());
        MockHttpServletRequest getBody = incoming("GET", "/api/tactical-boards/1", "campaignId=1", "{}");
        assertEquals(400, controller.proxy(RUNTIME, getBody).getStatusCode().value());
        MockHttpServletRequest headers = incoming("GET", "/api/tactical-boards/1", "campaignId=1");
        headers.addHeader("X-Oversized", "x".repeat(8193));
        assertEquals(431, controller.proxy(RUNTIME, headers).getStatusCode().value());
        verifyNoInteractions(products);
    }

    @Test void exact150000ByteCommandIsNotParsedRewrittenOrTruncated() throws Exception {
        AtomicReference<byte[]> received = new AtomicReference<>();
        HttpServer server = server(exchange -> { received.set(exchange.getRequestBody().readAllBytes()); reply(exchange, 400, "{\"code\":\"invalid_command\"}"); });
        bind(server);
        byte[] bytes = new byte[150_000]; Arrays.fill(bytes, (byte) ' '); bytes[0] = '{'; bytes[1] = '}';
        MockHttpServletRequest post = incoming("POST", "/api/tactical-boards/1/commands", null);
        post.setContentType("application/json"); post.setContent(bytes);
        ResponseEntity<byte[]> response = controller.proxy(RUNTIME, post);
        assertEquals(400, response.getStatusCode().value()); assertArrayEquals(bytes, received.get());
        assertEquals("invalid_command", code(response));
    }

    @Test void originTypedConflictSurvivesPortReuseButNoCommandRunsOnWrongRuntime() throws Exception {
        AtomicInteger commandInvocations = new AtomicInteger();
        String replacementRuntime = UUID.randomUUID().toString();
        HttpServer replacement = server(exchange -> {
            if (!replacementRuntime.equals(exchange.getRequestHeaders().getFirst(ProductDomainProxyController.RUNTIME_HEADER))) {
                reply(exchange, 409, "{\"code\":\"runtime_changed\",\"message\":\"The attached runtime has changed\"}");
                return;
            }
            commandInvocations.incrementAndGet(); reply(exchange, 200, "{}");
        });
        bind(replacement);
        ResponseEntity<byte[]> response = controller.proxy(RUNTIME, incoming("POST", "/api/tactical-boards/1/commands", null, "{}"));
        assertEquals(409, response.getStatusCode().value()); assertEquals("runtime_changed", code(response));
        assertEquals(0, commandInvocations.get()); // Actual destination filter is covered in RuntimeBindingFilterTest.
    }

    @Test void redirectsAreNeverFollowedAndLocationAndCookiesNeverReachTheBrowser() throws Exception {
        AtomicInteger redirected = new AtomicInteger();
        HttpServer forbidden = server(exchange -> { redirected.incrementAndGet(); reply(exchange, 200, "{}"); });
        HttpServer redirect = server(exchange -> {
            exchange.getResponseHeaders().add("Location", origin(forbidden) + "/api/tactical-boards/999?campaignId=9");
            exchange.getResponseHeaders().add("Set-Cookie", "not-for-browser=value");
            reply(exchange, 302, "{}");
        });
        bind(redirect);
        ResponseEntity<byte[]> response = controller.proxy(RUNTIME, incoming("GET", "/api/tactical-boards/1", "campaignId=1"));
        assertEquals(502, response.getStatusCode().value()); assertEquals("upstream_redirect", code(response));
        assertEquals(0, redirected.get()); assertNull(response.getHeaders().getFirst("Location"));
        assertNull(response.getHeaders().getFirst("Set-Cookie"));
    }

    @Test void unicodeEscapingHasRawHeadroomWithinTheSdkSemanticBudget() throws Exception {
        // Transport-only fixture: semantic DTO validation remains the SDK reader's job.
        String body = "{\"summary\":\"" + "\\u0061".repeat(200_000) + "\"}";
        assertTrue(body.length() > 1_048_576);
        HttpServer server = server(exchange -> reply(exchange, 200, body));
        bind(server);
        ResponseEntity<byte[]> result = controller.proxy(RUNTIME,
                incoming("GET", "/api/world-sim/1/decision-workbench", "campaignId=1"));
        assertEquals(200, result.getStatusCode().value());
        assertEquals(body, utf8(result.getBody()));
    }

    @Test void oversizedFixedAndChunkedResponsesHeadersAndNonJsonAreRejectedWithBoundedErrors() throws Exception {
        for (boolean chunked : new boolean[]{false, true}) {
            HttpServer server = server(exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                byte[] bytes = new byte[ProductDomainProxyController.RESPONSE_LIMIT + 1]; Arrays.fill(bytes, (byte) ' ');
                exchange.sendResponseHeaders(200, chunked ? 0 : bytes.length);
                try { exchange.getResponseBody().write(bytes); } catch (IOException cancelledByBudget) { }
            });
            bind(server);
            ResponseEntity<byte[]> response = controller.proxy(RUNTIME, incoming("GET", "/api/tactical-boards/1", "campaignId=1"));
            assertEquals(502, response.getStatusCode().value()); assertTrue(response.getBody().length < 512);
        }
        HttpServer headers = server(exchange -> {
            exchange.getResponseHeaders().add("X-Too-Large", "x".repeat(8193)); reply(exchange, 200, "{}");
        });
        bind(headers);
        assertEquals(502, controller.proxy(RUNTIME, incoming("GET", "/api/tactical-boards/1", "campaignId=1")).getStatusCode().value());
        HttpServer badJson = server(exchange -> reply(exchange, 200, "{broken internal diagnostic")); bind(badJson);
        ResponseEntity<byte[]> invalid = controller.proxy(RUNTIME, incoming("GET", "/api/tactical-boards/1", "campaignId=1"));
        assertEquals(502, invalid.getStatusCode().value()); assertFalse(utf8(invalid.getBody()).contains("internal diagnostic"));
        HttpServer html = server(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/html"); exchange.sendResponseHeaders(200, 1); exchange.getResponseBody().write('x');
        }); bind(html);
        assertEquals(502, controller.proxy(RUNTIME, incoming("GET", "/api/tactical-boards/1", "campaignId=1")).getStatusCode().value());
    }

    @Test void completedBusinessHttpErrorsAreResultsButInvalidPostResponsesQuarantine() throws Exception {
        HttpServer business = server(exchange -> reply(exchange, 409, "{\"code\":\"revision_conflict\"}"));
        bind(business);
        assertEquals(409, controller.proxy(RUNTIME, incoming("POST", "/api/tactical-boards/1/commands", null, "{}")).getStatusCode().value());
        verify(operation, never()).quarantine();
        for (String kind : List.of("json", "type", "headers", "redirect", "oversize", "network")) {
            clearInvocations(operation);
            HttpServer invalid = server(exchange -> {
                switch (kind) {
                    case "json" -> reply(exchange, 200, "{broken");
                    case "type" -> {
                        exchange.getResponseHeaders().add("Content-Type", "text/html"); exchange.sendResponseHeaders(200, 1); exchange.getResponseBody().write('x');
                    }
                    case "headers" -> { exchange.getResponseHeaders().add("X-Large", "x".repeat(8193)); reply(exchange, 200, "{}"); }
                    case "redirect" -> reply(exchange, 302, "{}");
                    case "oversize" -> {
                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, ProductDomainProxyController.RESPONSE_LIMIT + 1L);
                    }
                    case "network" -> exchange.close(); // Accepted request, no complete response.
                    default -> throw new AssertionError(kind);
                }
            });
            bind(invalid);
            assertEquals(502, controller.proxy(RUNTIME, incoming("POST", "/api/tactical-boards/1/commands", null, "{}")).getStatusCode().value(), kind);
            verify(operation).quarantine();
        }
    }

    @Test void interruptedDispatchedPostQuarantinesAndTooShortCliIdleBudgetNeverDispatches() throws Exception {
        CountDownLatch accepted = new CountDownLatch(1), release = new CountDownLatch(1);
        HttpServer server = server(exchange -> {
            accepted.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            reply(exchange, 200, "{}");
        });
        bind(server);
        AtomicReference<ResponseEntity<byte[]>> result = new AtomicReference<>();
        Thread caller = new Thread(() -> result.set(controller.proxy(RUNTIME, incoming("POST", "/api/tactical-boards/1/commands", null, "{}"))));
        try {
            caller.start(); assertTrue(accepted.await(2, TimeUnit.SECONDS)); caller.interrupt(); caller.join(2000);
            assertFalse(caller.isAlive()); assertEquals(503, result.get().getStatusCode().value()); verify(operation).quarantine();
            clearInvocations(products, operation);
            when(policy.idleTimeoutSeconds()).thenReturn(1);
            assertEquals(503, controller.proxy(RUNTIME, incoming("POST", "/api/tactical-boards/1/commands", null, "{}")).getStatusCode().value());
            verify(products, never()).withDomainConnection(any()); verify(operation, never()).quarantine();
        } finally { release.countDown(); caller.interrupt(); caller.join(2000); }
    }

    @Test void slowChunkedBodyHasAnAbsoluteDeadlineNotJustATimeoutForHeaders() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        HttpServer slow = server(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json"); exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
            try { release.await(15, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        bind(slow);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(12), () -> {
                ResponseEntity<byte[]> response = controller.proxy(RUNTIME, incoming("GET", "/api/tactical-boards/1", "campaignId=1"));
                assertEquals(504, response.getStatusCode().value()); assertEquals("upstream_timeout", code(response));
            });
        } finally { release.countDown(); }
    }

    @Test void serviceMonitorIsHeldAcrossCaptureAndEntireRelayToSerializeExplicitClose() throws Exception {
        CountDownLatch arrived = new CountDownLatch(1), release = new CountDownLatch(1), closed = new CountDownLatch(1);
        HttpServer server = server(exchange -> {
            arrived.countDown();
            try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            reply(exchange, 200, "{}");
        });
        bind(server);
        when(products.readCapability("connection")).thenAnswer(invocation -> {
            assertTrue(Thread.holdsLock(products)); return connection(origin(server));
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var relay = executor.submit(() -> controller.proxy(RUNTIME, incoming("GET", "/api/tactical-boards/1", "campaignId=1")));
            assertTrue(arrived.await(2, TimeUnit.SECONDS));
            var close = executor.submit(() -> { synchronized (products) { closed.countDown(); } });
            assertFalse(closed.await(100, TimeUnit.MILLISECONDS), "Explicit synchronized close must wait for relay");
            release.countDown();
            assertEquals(200, relay.get(3, TimeUnit.SECONDS).getStatusCode().value()); close.get(3, TimeUnit.SECONDS);
            assertEquals(0, closed.getCount());
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    private void approvedPolicy() {
        when(products.policy()).thenReturn(policy); when(policy.id()).thenReturn("elthoria-play");
        when(policy.readCapabilities()).thenReturn(Map.of("connection", "mcp__elthoria__get_play_connection"));
        when(policy.idleTimeoutSeconds()).thenReturn(60);
    }
    private void bind(HttpServer server) {
        approvedPolicy(); when(products.readCapability("connection")).thenReturn(connection(origin(server)));
    }
    private ObjectNode connection(String origin) {
        return json.createObjectNode().put("schemaVersion", "elthoria.play-connection.v1").put("status", "READY")
                .put("apiBaseUrl", origin).put("runtimeId", RUNTIME).put("transport", "stdio").put("lifecycle", "HARNESS_CHILD")
                .put("localOnly", true).put("authentication", "NOT_PROVIDED").put("tacticalAudience", "GM")
                .put("tacticalPlayerSafe", false).put("uiStarted", false).put("note", "not a credential");
    }
    private MockHttpServletRequest incoming(String method, String path, String query) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, PREFIX + path); request.setQueryString(query); return request;
    }
    private MockHttpServletRequest incoming(String method, String path, String query, String body) {
        MockHttpServletRequest request = incoming(method, path, query); request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8)); return request;
    }
    private String code(ResponseEntity<byte[]> response) throws IOException { return json.readTree(response.getBody()).path("code").asText(); }
    private static String utf8(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
    private static String origin(HttpServer server) { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    private HttpServer server(Handler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { try { handler.handle(exchange); } finally { exchange.close(); } });
        server.start(); servers.add(server); return server;
    }
    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
    }
    @FunctionalInterface private interface Handler { void handle(HttpExchange exchange) throws IOException; }
}
