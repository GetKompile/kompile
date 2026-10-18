package ai.kompile.app.web.security;

import ai.kompile.app.services.agent.ProductChatSessionService;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Trusted-local boundary, NOT authentication. Generic APIs are absent in product mode. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public final class ProductChatHttpFilter extends OncePerRequestFilter {
    public static final String VALIDATED_TURN_ATTRIBUTE = "kompile.product-chat.validated-turn";
    private static final String BASE = "/api/agents/chat";
    private static final int BODY_LIMIT = 131_072;
    private static final int DOMAIN_BODY_LIMIT = 150_000;
    private static final String DOMAIN = BASE + "/product/domain/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String POSITIVE_ID = "[1-9][0-9]{0,15}";
    private static final String RUN = "harness-product-[0-9a-f]{32}";
    private static final String APPROVAL = BASE + "/product/content-approvals/[A-Za-z0-9][A-Za-z0-9_-]{0,127}";
    private final ProductChatSessionService products;
    private final Environment environment;
    private final ObjectMapper mapper;

    public ProductChatHttpFilter(ProductChatSessionService products, Environment environment, ObjectMapper mapper) {
        this.products = products; this.environment = environment;
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @PostConstruct
    public void verifyLoopbackBind() {
        if (products.enabled() && !loopback(environment.getProperty("server.address")))
            throw new IllegalStateException("Product chat requires an explicit loopback server.address binding");
    }

    @Override protected boolean shouldNotFilterAsyncDispatch() { return false; }
    @Override protected boolean shouldNotFilterErrorDispatch() { return false; }
    @Override protected void doFilterNestedErrorDispatch(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        doFilterInternal(request, response, chain);
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!products.enabled()) { chain.doFilter(request, response); return; }
        if (!safeAuthority(request) || !boundedHeaders(request)) { reject(response, 403, "Product chat is trusted-loopback only"); return; }
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Content-Security-Policy", "default-src 'self'; connect-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; frame-src 'self'; frame-ancestors 'self'; object-src 'none'; base-uri 'none'; form-action 'none'");
        if (request.getDispatcherType() == DispatcherType.ERROR) {
            // Container error dispatch uses /error (and retains the original
            // method). Do not turn a real 409/410/503 into the closed-route 404,
            // or expose the generic error controller's exception details.
            Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
            int code = status instanceof Integer value && value >= 400 && value <= 599 ? value : 500;
            reject(response, code, "Product request could not be completed");
            return;
        }
        String path = request.getRequestURI();
        if (path.length() > 2048 || path.contains("%") || path.contains("\\") || path.contains(";") || path.contains("..") || path.contains("//")
                || request.getHeader("Upgrade") != null || !request.getContextPath().isEmpty()) {
            reject(response, 404, "Not available in product chat"); return;
        }
        if (!path.startsWith("/api/") && !path.equals("/api")) {
            // The product app's static browser bundle is allowed; non-API transports aren't.
            if (!request.getMethod().equals("GET") || path.startsWith("/ws") || path.startsWith("/sock")) {
                reject(response, 404, "Not available in product chat"); return;
            }
            chain.doFilter(request, response); return;
        }
        if (!approvedRoute(request.getMethod(), path)) { reject(response, 404, "Not available in product chat"); return; }
        if (request.getQueryString() != null && request.getQueryString().length() > 2048) { reject(response, 413, "Query too large"); return; }
        boolean domainRead = approvedDomainRoute("GET", path);
        boolean domainCommand = approvedDomainRoute("POST", path);
        Set<String> queryFields = path.equals(BASE + "/active-run") || path.equals(BASE + "/product/transcript")
                ? Set.of("sessionId", "workingDirectory") : path.startsWith(BASE + "/events/") ? Set.of("after") : Set.of();
        // Domain GET queries are parsed from the raw query by the closed proxy controller.
        if (domainRead && request.getQueryString() != null && request.getQueryString().length() > 128) {
            reject(response, 400, "Invalid domain query"); return;
        }
        if (!domainRead && queryFields.isEmpty() && request.getQueryString() != null && !request.getQueryString().isEmpty()) {
            reject(response, 400, "Unexpected product query parameter"); return;
        }
        // Never ask the servlet to parse a POST form before the byte limit is enforced.
        if (!queryFields.isEmpty()) for (var parameter : request.getParameterMap().entrySet()) {
            if (!queryFields.contains(parameter.getKey()) || parameter.getValue().length != 1
                    || parameter.getValue()[0].length() > 2048) {
                reject(response, 400, "Unexpected product query parameter"); return;
            }
        }
        if (request.getDispatcherType() == DispatcherType.ASYNC) { chain.doFilter(request, response); return; }
        if (Set.of("POST", "DELETE").contains(request.getMethod())) {
            int limit = path.matches(APPROVAL) ? 512 : domainCommand ? DOMAIN_BODY_LIMIT : BODY_LIMIT;
            if (request.getContentLengthLong() > limit) { reject(response, 413, "Product request too large"); return; }
            byte[] body = request.getInputStream().readNBytes(limit + 1);
            if (body.length > limit) { reject(response, 413, "Product request too large"); return; }
            if (domainCommand || path.matches(APPROVAL) || path.equals(BASE + "/stream") || path.matches(BASE + "/control/" + RUN)) {
                if (!jsonRequest(request)) { reject(response, 415, "UTF-8 JSON required"); return; }
                // Forward domain bytes unchanged: only its destination owns command decoding.
                if (domainCommand) { chain.doFilter(new BodyRequest(request, body), response); return; }
                try {
                    JsonNode frame = mapper.readTree(body);
                    if (path.endsWith("/stream")) {
                        products.validateBody(frame);
                        request.setAttribute(VALIDATED_TURN_ATTRIBUTE, Boolean.TRUE);
                    }
                    else if (path.matches(APPROVAL)) ProductChatSessionService.validateApprovalBody(frame);
                    else if (frame == null || !frame.isObject() || frame.size() != 1 || !frame.path("action").asText().equals("cancel"))
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only turn cancellation is allowed");
                } catch (ResponseStatusException refused) { reject(response, refused.getStatusCode().value(), "Product request refused"); return; }
                catch (RuntimeException | IOException invalid) { reject(response, 400, "Invalid product request"); return; }
            } else if (body.length != 0) { reject(response, 400, "Product session controls do not accept a body"); return; }
            chain.doFilter(new BodyRequest(request, body), response); return;
        }
        chain.doFilter(request, response);
    }

    static boolean approvedRoute(String method, String path) {
        if (approvedDomainRoute(method, path)) return true;
        if (method.equals("GET")) return path.equals(BASE + "/product")
                || path.equals(BASE + "/product/connection") || path.equals(BASE + "/product/dashboard")
                || path.equals(BASE + "/product/transcript") || path.equals(BASE + "/product/content-approvals")
                || path.matches(BASE + "/product/assets/[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
                || path.equals(BASE + "/active-run") || path.matches(BASE + "/events/" + RUN)
                || path.equals(BASE + "/health");
        if (method.equals("POST")) return path.equals(BASE + "/product/session") || path.equals(BASE + "/stream")
                || path.matches(BASE + "/(?:cancel|control)/" + RUN) || path.matches(APPROVAL);
        return method.equals("DELETE") && path.equals(BASE + "/product/session");
    }

    private static boolean approvedDomainRoute(String method, String path) {
        if (method.equals("POST")) return path.matches(DOMAIN + "/api/tactical-boards/" + POSITIVE_ID + "/commands");
        return method.equals("GET") && (path.matches(DOMAIN + "/api/tactical-boards/" + POSITIVE_ID)
                || path.matches(DOMAIN + "/api/world-sim/" + POSITIVE_ID + "/(?:story-candidates|decision-workbench)"));
    }

    private static boolean jsonRequest(HttpServletRequest request) {
        if (request.getHeader("Content-Encoding") != null) return false;
        List<String> types = Collections.list(request.getHeaders("Content-Type"));
        if (types.size() != 1) return false;
        try {
            MediaType parsed = MediaType.parseMediaType(types.get(0));
            return parsed.getType().equals("application") && parsed.getSubtype().equals("json")
                    && parsed.getParameters().keySet().stream().allMatch("charset"::equalsIgnoreCase)
                    && (parsed.getCharset() == null || parsed.getCharset().equals(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException invalid) { return false; }
    }

    static boolean loopback(String host) {
        return Set.of("127.0.0.1", "::1", "[::1]", "0:0:0:0:0:0:0:1", "[0:0:0:0:0:0:0:1]").contains(host == null ? "" : host);
    }

    static boolean safeAuthority(HttpServletRequest request) {
        if (!loopback(request.getRemoteAddr()) || !loopback(request.getLocalAddr())) return false;
        for (String header : List.of("Forwarded", "X-Forwarded-Host", "X-Forwarded-For", "X-Forwarded-Proto"))
            if (request.getHeader(header) != null) return false;
        if (Collections.list(request.getHeaders("Host")).size() != 1
                || Collections.list(request.getHeaders("Origin")).size() > 1) return false;
        String host = request.getHeader("Host");
        if (host == null || !host.matches("(?:127\\.0\\.0\\.1|localhost|\\[::1\\]|\\[0:0:0:0:0:0:0:1\\])(?::[0-9]{1,5})?")) return false;
        try {
            URI authority = URI.create(request.getScheme() + "://" + host);
            if (port(authority) != request.getLocalPort()) return false;
            String site = request.getHeader("Sec-Fetch-Site");
            if (site != null && !Set.of("same-origin", "none").contains(site)) return false;
            String origin = request.getHeader("Origin");
            if (origin == null) return true;
            URI source = URI.create(origin);
            return source.getUserInfo() == null && source.getRawQuery() == null && source.getRawFragment() == null
                    && (source.getRawPath() == null || source.getRawPath().isEmpty())
                    && authority.getScheme().equals(source.getScheme())
                    && authority.getHost().equals(source.getHost()) && port(authority) == port(source);
        } catch (IllegalArgumentException invalid) { return false; }
    }
    private static int port(URI uri) { return uri.getPort() >= 0 ? uri.getPort() : uri.getScheme().equals("https") ? 443 : 80; }
    private static boolean boundedHeaders(HttpServletRequest request) {
        int count = 0, bytes = 0;
        for (Enumeration<String> names = request.getHeaderNames(); names.hasMoreElements();) {
            String name = names.nextElement(); if (++count > 64) return false;
            for (Enumeration<String> values = request.getHeaders(name); values.hasMoreElements();) {
                bytes += name.length() + values.nextElement().length(); if (bytes > 16_384) return false;
            }
        }
        return true;
    }
    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status); response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }

    private static final class BodyRequest extends HttpServletRequestWrapper {
        private final byte[] bytes;
        BodyRequest(HttpServletRequest request, byte[] bytes) { super(request); this.bytes = bytes; }
        @Override public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(bytes);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
            };
        }
        @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
        @Override public int getContentLength() { return bytes.length; }
        @Override public long getContentLengthLong() { return bytes.length; }
    }
}
