package ai.kompile.app.web.controllers;

import ai.kompile.app.services.agent.ProductChatSessionService;
import ai.kompile.app.services.agent.KompileCliSessionWorker;
import ai.kompile.cli.common.ProductChatPolicy;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Closed Elthoria SDK relay, not a general URL proxy or a second domain-state authority. */
@RestController
@RequestMapping(ProductDomainProxyController.BASE + "/{runtimeId}")
public final class ProductDomainProxyController {
    static final String BASE = "/api/agents/chat/product/domain";
    static final String RUNTIME_HEADER = "X-Elthoria-Runtime-Id";
    static final int BODY_LIMIT = 150_000;
    // Raw transport headroom for both SDKs' 2 Mi normalized JSON-character budget,
    // including UTF-8 and legal Unicode-escape expansion. Not an authorization boundary.
    static final int RESPONSE_LIMIT = 16 * 1024 * 1024;
    private static final int HEADER_LIMIT = 16_384;
    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final long MAX_SAFE_ID = 9_007_199_254_740_991L;
    private static final Pattern RUNTIME = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern BOARD = Pattern.compile("/api/tactical-boards/([0-9]+)(/commands)?");
    private static final Pattern WORLD = Pattern.compile("/api/world-sim/([0-9]+)/(story-candidates|decision-workbench)");
    // Literal origins only. The stricter textual grammar also rejects DNS, credentials, escapes,
    // alternative IPv4 spellings, IPv6 zones, path (including '/'), query and fragments.
    private static final Pattern ORIGIN = Pattern.compile("http://(127\\.0\\.0\\.1|\\[::1\\]):([1-9][0-9]{0,4})");
    private static final ProxySelector DIRECT = new ProxySelector() {
        @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
        @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) { }
    };
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER)
            .proxy(DIRECT).version(HttpClient.Version.HTTP_1_1).build();

    private final ProductChatSessionService products;
    private final ObjectMapper json = new ObjectMapper();

    public ProductDomainProxyController(ProductChatSessionService products) { this.products = products; }

    // Explicitly include OPTIONS: an unspecified Spring method condition synthesizes a 200
    // OPTIONS handler. Every wrong standard method must instead return 404. /domain/** stays shut.
    @RequestMapping(value = {"/api/tactical-boards/{savedGridId}", "/api/tactical-boards/{savedGridId}/commands",
            "/api/world-sim/{simulationId}/story-candidates", "/api/world-sim/{simulationId}/decision-workbench"},
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH,
                    RequestMethod.DELETE, RequestMethod.HEAD, RequestMethod.OPTIONS, RequestMethod.TRACE})
    public ResponseEntity<byte[]> proxy(@PathVariable("runtimeId") String runtimeId, HttpServletRequest request) {
        try {
            Target route = target(runtimeId, request);
            boundedRequestHeaders(request);
            byte[] body = requestBody(request, route.command());
            ProductChatPolicy policy = products.policy();
            if (!"elthoria-play".equals(policy.id()) || !"mcp__elthoria__get_play_connection"
                    .equals(policy.readCapabilities().get("connection"))) {
                throw failure(404, "domain_unavailable");
            }
            // The host pin cannot extend the CLI's independent profile idle timer. Refuse
            // profiles too short for this bounded relay rather than silently lose its MCP owner.
            if (policy.idleTimeoutSeconds() <= TIMEOUT.toSeconds() + 2) throw failure(503, "domain_idle_budget_too_short");
            return products.withDomainConnection((connection, operation) -> {
                URI origin = origin(connection, runtimeId);
                String capturedRuntime = connection.path("runtimeId").textValue();
                return relay(URI.create(origin.toASCIIString() + route.pathAndQuery()), capturedRuntime, body, route.command(), operation);
            });
        } catch (ProxyFailure refused) {
            return error(refused.status, refused.code);
        } catch (ResponseStatusException refused) {
            int status = refused.getStatusCode().value();
            return error(status, status == 409 ? "runtime_busy" : "domain_unavailable");
        } catch (IOException invalid) {
            return error(400, "invalid_request");
        }
    }

    private static Target target(String runtime, HttpServletRequest request) {
        if (runtime == null || runtime.length() != 36 || !RUNTIME.matcher(runtime).matches())
            throw failure(404, "not_found");
        String raw = request.getRequestURI();
        String context = request.getContextPath();
        String prefix = context + BASE + "/" + runtime;
        if (raw == null || raw.length() > 512 || !raw.startsWith(prefix + "/")) throw failure(404, "not_found");
        String path = raw.substring(prefix.length());
        Matcher board = BOARD.matcher(path);
        Matcher world = WORLD.matcher(path);
        boolean command;
        boolean story = false;
        String closedPath;
        if (board.matches()) {
            command = board.group(2) != null;
            if (!(command ? "POST" : "GET").equals(request.getMethod())) throw failure(404, "not_found");
            String id = positive(board.group(1), MAX_SAFE_ID);
            closedPath = "/api/tactical-boards/" + id + (command ? "/commands" : "");
        } else if (world.matches()) {
            if (!"GET".equals(request.getMethod())) throw failure(404, "not_found");
            String id = positive(world.group(1), MAX_SAFE_ID);
            command = false;
            story = "story-candidates".equals(world.group(2));
            closedPath = "/api/world-sim/" + id + (story ? "/story-candidates" : "/decision-workbench");
        } else {
            throw failure(404, "not_found");
        }
        String query = request.getQueryString();
        if (command) {
            if (query != null && !query.isEmpty()) throw failure(400, "invalid_query");
            return new Target(closedPath, true);
        }
        if (query == null || query.isEmpty() || query.length() > 128) throw failure(400, "invalid_query");
        Map<String, String> parameters = new HashMap<>();
        for (String part : query.split("&", -1)) {
            int equal = part.indexOf('=');
            if (equal < 1 || equal != part.lastIndexOf('=')) throw failure(400, "invalid_query");
            String name = part.substring(0, equal);
            if (!(name.equals("campaignId") || story && name.equals("limit"))
                    || parameters.putIfAbsent(name, part.substring(equal + 1)) != null) throw failure(400, "invalid_query");
        }
        String campaign = positive(parameters.get("campaignId"), MAX_SAFE_ID);
        String suffix = "?campaignId=" + campaign;
        if (parameters.containsKey("limit")) suffix += "&limit=" + positive(parameters.get("limit"), 24);
        return new Target(closedPath + suffix, false);
    }

    private static String positive(String value, long maximum) {
        if (value == null || value.length() > 16 || !value.matches("[1-9][0-9]{0,15}"))
            throw failure(400, "invalid_id");
        try {
            if (Long.parseLong(value) > maximum) throw failure(400, "invalid_id");
        } catch (NumberFormatException invalid) { throw failure(400, "invalid_id"); }
        return value;
    }

    static URI origin(JsonNode connection, String requestedRuntime) {
        if (connection == null || !connection.isObject()
                || !text(connection, "schemaVersion", "elthoria.play-connection.v1")
                || !text(connection, "status", "READY")
                || !connection.path("localOnly").isBoolean() || !connection.path("localOnly").booleanValue()
                || !text(connection, "tacticalAudience", "GM")
                || !connection.path("tacticalPlayerSafe").isBoolean() || connection.path("tacticalPlayerSafe").booleanValue()
                || !connection.path("uiStarted").isBoolean() || connection.path("uiStarted").booleanValue()
                || !text(connection, "authentication", "NOT_PROVIDED")
                || !(text(connection, "transport", "stdio") && text(connection, "lifecycle", "HARNESS_CHILD")
                    || text(connection, "transport", "sse") && text(connection, "lifecycle", "SHARED_SERVER")))
            throw failure(503, "connection_unavailable");
        String runtime = connection.path("runtimeId").isTextual() ? connection.path("runtimeId").textValue() : "";
        if (runtime.length() != 36 || !RUNTIME.matcher(runtime).matches()) throw failure(503, "connection_unavailable");
        if (!runtime.equals(requestedRuntime)) throw failure(409, "runtime_changed");
        String url = connection.path("apiBaseUrl").isTextual() ? connection.path("apiBaseUrl").textValue() : "";
        if (url.length() > 64 || !ORIGIN.matcher(url).matches()) throw failure(503, "connection_unavailable");
        URI origin = URI.create(url);
        if (origin.getPort() < 1 || origin.getPort() > 65535) throw failure(503, "connection_unavailable");
        return origin;
    }

    private static boolean text(JsonNode node, String field, String expected) {
        return node.path(field).isTextual() && expected.equals(node.path(field).textValue());
    }

    private static void boundedRequestHeaders(HttpServletRequest request) {
        int count = 0, size = 0;
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) return;
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            Enumeration<String> values = request.getHeaders(name);
            while (values.hasMoreElements()) {
                String value = values.nextElement();
                if (++count > 64 || name.length() > 256 || value.length() > 8192
                        || (size += name.length() + value.length()) > HEADER_LIMIT) throw failure(431, "headers_too_large");
            }
        }
    }

    private static byte[] requestBody(HttpServletRequest request, boolean command) throws IOException {
        if (request.getHeader("Content-Encoding") != null) throw failure(415, "unsupported_content_type");
        if (!command) {
            if (request.getContentLengthLong() > 0 || request.getInputStream().read() != -1)
                throw failure(400, "unexpected_body");
            return new byte[0];
        }
        Enumeration<String> types = request.getHeaders("Content-Type");
        if (types == null || !types.hasMoreElements()) throw failure(415, "unsupported_content_type");
        String type = types.nextElement();
        if (types.hasMoreElements() || !jsonType(type)) throw failure(415, "unsupported_content_type");
        if (request.getContentLengthLong() > BODY_LIMIT) throw failure(413, "body_too_large");
        byte[] body = request.getInputStream().readNBytes(BODY_LIMIT + 1);
        if (body.length > BODY_LIMIT) throw failure(413, "body_too_large");
        // Domain decoder retains sole ownership of JSON fields, GM ownership and revision checks.
        return body;
    }

    private static boolean jsonType(String type) {
        try {
            MediaType parsed = MediaType.parseMediaType(type);
            return parsed.getType().equals("application") && parsed.getSubtype().equals("json")
                    && parsed.getParameters().keySet().stream().allMatch("charset"::equalsIgnoreCase)
                    && (parsed.getCharset() == null || parsed.getCharset().equals(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IllegalArgumentException invalid) { return false; }
    }

    private ResponseEntity<byte[]> relay(URI target, String runtime, byte[] body, boolean command,
                                         KompileCliSessionWorker.ExternalOperation operation) {
        HttpRequest.Builder outgoing = HttpRequest.newBuilder(target).timeout(TIMEOUT)
                .header("Accept", "application/json").header(RUNTIME_HEADER, runtime);
        if (command) outgoing.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body));
        else outgoing.GET();
        BoundedBody subscriber = new BoundedBody();
        CompletableFuture<HttpResponse<byte[]>> exchange = null;
        boolean dispatched = false, completed = false;
        try {
            // Once handed to the client, network errors/cancel do NOT establish non-delivery.
            dispatched = true;
            exchange = CLIENT.sendAsync(outgoing.build(), info -> {
                boundedResponseHeaders(info.headers());
                // Never follow/forward Location, cookies, auth challenges or any other origin headers.
                if (info.statusCode() >= 300 && info.statusCode() < 400) throw failure(502, "upstream_redirect");
                List<String> types = info.headers().allValues("Content-Type");
                if (types.size() != 1 || !jsonType(types.get(0)) || !info.headers().allValues("Content-Encoding").isEmpty())
                    throw failure(502, "invalid_upstream_response");
                return subscriber;
            });
            HttpResponse<byte[]> reply = exchange.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            byte[] bytes = reply.body();
            if (bytes.length == 0 || json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes) == null)
                throw failure(502, "invalid_upstream_response");
            ResponseEntity<byte[]> result = response(reply.statusCode(), bytes);
            completed = true; // A valid completed business HTTP error is a result, not uncertainty.
            return result;
        } catch (TimeoutException timeout) {
            throw failure(504, "upstream_timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw failure(503, "relay_interrupted");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
                if (cause instanceof ProxyFailure refused) throw refused;
                if (cause instanceof java.net.http.HttpTimeoutException) throw failure(504, "upstream_timeout");
            }
            throw failure(502, "upstream_unavailable");
        } catch (IOException invalid) {
            throw failure(502, "invalid_upstream_response");
        } catch (java.util.concurrent.CancellationException cancelled) {
            throw failure(503, "relay_cancelled");
        } catch (RuntimeException failed) {
            if (failed instanceof ProxyFailure refused) throw refused;
            throw failure(502, "upstream_unavailable");
        } finally {
            // Sticky BEFORE releasing domain/model admission. No automatic close, retry or rollback.
            if (command && dispatched && !completed) operation.quarantine();
            subscriber.cancel();
            if (exchange != null && !exchange.isDone()) exchange.cancel(true);
        }
    }

    private static void boundedResponseHeaders(HttpHeaders headers) {
        int count = 0, size = 0;
        for (var entry : headers.map().entrySet()) {
            for (String value : entry.getValue()) {
                if (++count > 64 || entry.getKey().length() > 256 || value.length() > 8192
                        || (size += entry.getKey().length() + value.length()) > HEADER_LIMIT)
                    throw failure(502, "upstream_headers_too_large");
            }
        }
        List<String> lengths = headers.allValues("Content-Length");
        if (lengths.size() > 1) throw failure(502, "invalid_upstream_response");
        if (!lengths.isEmpty()) {
            try {
                long sizeHint = Long.parseLong(lengths.get(0));
                if (sizeHint < 0 || sizeHint > RESPONSE_LIMIT) throw failure(502, "upstream_body_too_large");
            } catch (NumberFormatException invalid) { throw failure(502, "invalid_upstream_response"); }
        }
    }

    /** Backpressure + a byte budget; never BodyHandlers.ofByteArray (which buffers without a limit). */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(8192);
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private boolean cancelled;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public synchronized void onSubscribe(Flow.Subscription value) {
            if (cancelled || subscription != null) { value.cancel(); return; }
            subscription = value; value.request(1);
        }
        @Override public synchronized void onNext(List<ByteBuffer> buffers) {
            if (cancelled) return;
            for (ByteBuffer buffer : buffers) {
                int length = buffer.remaining();
                if (length > RESPONSE_LIMIT - bytes.size()) {
                    result.completeExceptionally(failure(502, "upstream_body_too_large")); cancel(); return;
                }
                byte[] chunk = new byte[length]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public synchronized void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public synchronized void onComplete() { result.complete(bytes.toByteArray()); }
        synchronized void cancel() {
            cancelled = true;
            if (subscription != null) subscription.cancel();
            result.completeExceptionally(failure(502, "relay_cancelled"));
        }
    }

    private ResponseEntity<byte[]> error(int status, String code) {
        try { return response(status, json.writeValueAsBytes(Map.of("code", code, "message", "Product domain request unavailable"))); }
        catch (IOException impossible) { throw new IllegalStateException("Unable to encode fixed error"); }
    }

    private static ResponseEntity<byte[]> response(int status, byte[] bytes) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store").header("X-Content-Type-Options", "nosniff").body(bytes);
    }
    private record Target(String pathAndQuery, boolean command) { }
    private static ProxyFailure failure(int status, String code) { return new ProxyFailure(status, code); }
    private static final class ProxyFailure extends RuntimeException {
        final int status;
        final String code;
        ProxyFailure(int status, String code) { super(code); this.status = status; this.code = code; }
    }
}
