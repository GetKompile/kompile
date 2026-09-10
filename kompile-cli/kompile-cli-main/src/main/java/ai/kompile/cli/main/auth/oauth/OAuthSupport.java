/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.auth.oauth;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/** Shared protocol utilities for built-in OAuth flows. */
final class OAuthSupport {
    static final ObjectMapper MAPPER = JsonUtils.newStandardMapper();
    static final String DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";

    private static final SecureRandom RANDOM = new SecureRandom();

    private OAuthSupport() {
    }

    static String callbackHost() {
        String configured = System.getenv("KOMPILE_OAUTH_CALLBACK_HOST");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("PI_OAUTH_CALLBACK_HOST");
        }
        return configured == null || configured.isBlank() ? "127.0.0.1" : configured.trim();
    }

    static Pkce generatePkce() throws IOException {
        byte[] verifierBytes = new byte[32];
        RANDOM.nextBytes(verifierBytes);
        String verifier = base64Url(verifierBytes);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return new Pkce(verifier, base64Url(digest));
        } catch (Exception e) {
            throw new IOException("Could not generate PKCE challenge", e);
        }
    }

    static String randomState() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return base64Url(bytes);
    }

    static String randomPathToken() {
        return UUID.randomUUID().toString();
    }

    private static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    static URI uriWithQuery(String base, Map<String, String> values) {
        StringBuilder query = new StringBuilder();
        values.forEach((key, value) -> {
            if (value == null) {
                return;
            }
            if (!query.isEmpty()) {
                query.append('&');
            }
            query.append(urlEncode(key)).append('=').append(urlEncode(value));
        });
        return URI.create(base + (base.contains("?") ? "&" : "?") + query);
    }

    static String form(Map<String, String> values) {
        StringBuilder encoded = new StringBuilder();
        values.forEach((key, value) -> {
            if (value == null) {
                return;
            }
            if (!encoded.isEmpty()) {
                encoded.append('&');
            }
            encoded.append(urlEncode(key)).append('=').append(urlEncode(value));
        });
        return encoded.toString();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static JsonNode json(Response response, String context) throws IOException {
        try {
            JsonNode parsed = MAPPER.readTree(response.body());
            if (parsed == null || !parsed.isObject()) {
                throw new IOException(context + " returned a non-object JSON response");
            }
            return parsed;
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().startsWith(context)) {
                throw e;
            }
            throw new IOException(context + " returned invalid JSON", e);
        }
    }

    static void requireSuccess(Response response, String context) throws IOException {
        if (response.success()) {
            return;
        }
        String detail = errorDetail(response);
        throw new OAuthHttpException(
                response.status(),
                oauthErrorCode(response),
                context + " failed (HTTP " + response.status() + ")"
                        + (detail == null ? "" : ": " + detail));
    }

    static String requiredText(JsonNode body, String field, String context) throws IOException {
        JsonNode value = body.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IOException(context + " is missing '" + field + "'");
        }
        return value.textValue();
    }

    static String optionalText(JsonNode body, String field) {
        JsonNode value = body.get(field);
        return value != null && value.isTextual() && !value.textValue().isBlank()
                ? value.textValue()
                : null;
    }

    static long requiredPositiveLong(JsonNode body, String field, String context) throws IOException {
        JsonNode value = body.get(field);
        if (value == null || !value.canConvertToLong() || value.longValue() <= 0L) {
            throw new IOException(context + " has an invalid '" + field + "'");
        }
        return value.longValue();
    }

    static int optionalPositiveInt(JsonNode body, String field, int fallback) {
        JsonNode value = body.get(field);
        if (value == null) {
            return fallback;
        }
        if (value.isTextual()) {
            try {
                int parsed = Integer.parseInt(value.textValue().trim());
                return parsed > 0 ? parsed : fallback;
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return value.canConvertToInt() && value.intValue() > 0 ? value.intValue() : fallback;
    }

    static long expiryFromNow(long expiresInSeconds, long skewMillis) {
        long lifetimeMillis;
        try {
            lifetimeMillis = Math.multiplyExact(expiresInSeconds, 1000L);
        } catch (ArithmeticException e) {
            lifetimeMillis = Long.MAX_VALUE;
        }
        long adjusted = Math.max(1_000L, lifetimeMillis - Math.max(0L, skewMillis));
        long now = System.currentTimeMillis();
        return adjusted >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + adjusted;
    }

    static AuthorizationInput parseAuthorizationInput(String input) {
        String value = input == null ? "" : input.trim();
        if (value.isEmpty()) {
            return new AuthorizationInput(null, null);
        }
        try {
            URI uri = URI.create(value);
            if (uri.getScheme() != null) {
                Map<String, String> query = parseQuery(uri.getRawQuery());
                return new AuthorizationInput(query.get("code"), query.get("state"));
            }
        } catch (IllegalArgumentException ignored) {
            // Continue with code/state and query-string formats.
        }
        if (value.contains("#")) {
            String[] parts = value.split("#", 2);
            return new AuthorizationInput(parts[0], parts.length > 1 ? parts[1] : null);
        }
        if (value.contains("code=")) {
            Map<String, String> query = parseQuery(value.startsWith("?") ? value.substring(1) : value);
            return new AuthorizationInput(query.get("code"), query.get("state"));
        }
        return new AuthorizationInput(value, null);
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> values = new LinkedHashMap<>();
        if (query == null || query.isBlank()) {
            return values;
        }
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length > 1
                    ? java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
                    : "";
            values.put(key, value);
        }
        return values;
    }

    static URI trustedHttpUri(String raw, boolean httpsOnly, String context) throws IOException {
        try {
            URI uri = URI.create(raw);
            String scheme = uri.getScheme();
            boolean trusted = "https".equalsIgnoreCase(scheme)
                    || (!httpsOnly && "http".equalsIgnoreCase(scheme));
            if (!trusted || uri.getHost() == null) {
                throw new IOException("Untrusted " + context + " URI");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid " + context + " URI", e);
        }
    }

    static String normalizeBaseUrl(String raw, String context) throws IOException {
        URI uri = trustedHttpUri(raw, false, context);
        String normalized = uri.toString();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String oauthErrorCode(Response response) {
        try {
            JsonNode body = MAPPER.readTree(response.body());
            String code = optionalText(body, "error");
            if (code != null) return code;
            JsonNode error = body == null ? null : body.get("error");
            if (error != null && error.isObject()) {
                code = optionalText(error, "code");
                return code != null ? code : optionalText(error, "type");
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String errorDetail(Response response) {
        try {
            JsonNode body = MAPPER.readTree(response.body());
            for (String field : new String[]{"error_description", "message", "error"}) {
                String value = optionalText(body, field);
                if (value != null) {
                    return truncate(value, 512);
                }
            }
            JsonNode error = body == null ? null : body.get("error");
            if (error != null && error.isObject()) {
                String nested = optionalText(error, "message");
                if (nested != null) {
                    return truncate(nested, 512);
                }
            }
        } catch (Exception ignored) {
            // Deliberately do not echo arbitrary response bodies into errors.
        }
        return null;
    }

    private static String truncate(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum) + "...";
    }

    static HttpTransport defaultTransport() {
        return new JavaHttpTransport();
    }

    record Pkce(String verifier, String challenge) {
    }

    record AuthorizationInput(String code, String state) {
    }

    record Request(String method, URI uri, Map<String, String> headers, String body) {
        Request {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    record Response(int status, String body) {
        Response {
            body = body == null ? "" : body;
        }

        boolean success() {
            return status >= 200 && status < 300;
        }
    }

    @FunctionalInterface
    interface HttpTransport {
        Response send(Request request) throws IOException, InterruptedException;
    }

    static final class JavaHttpTransport implements HttpTransport {
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        @Override
        public Response send(Request request) throws IOException, InterruptedException {
            HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                    .timeout(Duration.ofSeconds(30));
            request.headers().forEach(builder::header);
            String method = request.method().toUpperCase(java.util.Locale.ROOT);
            if ("GET".equals(method)) {
                builder.GET();
            } else {
                builder.method(method, HttpRequest.BodyPublishers.ofString(
                        request.body() == null ? "" : request.body(),
                        StandardCharsets.UTF_8));
            }
            java.net.http.HttpResponse<String> response = client.send(
                    builder.build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(response.statusCode(), response.body());
        }
    }

    static final class OAuthHttpException extends IOException {
        private final int status;
        private final String oauthError;

        OAuthHttpException(int status, String oauthError, String message) {
            super(message);
            this.status = status;
            this.oauthError = oauthError;
        }

        int status() {
            return status;
        }

        String oauthError() {
            return oauthError;
        }
    }

    static final class CallbackServer implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor;
        private final URI redirectUri;
        private final CompletableFuture<AuthorizationInput> result;

        private CallbackServer(
                HttpServer server,
                ExecutorService executor,
                URI redirectUri,
                CompletableFuture<AuthorizationInput> result) {
            this.server = server;
            this.executor = executor;
            this.redirectUri = redirectUri;
            this.result = result;
        }

        static CallbackServer start(
                String bindHost,
                String redirectHost,
                int port,
                String path,
                String expectedState) throws IOException {
            Objects.requireNonNull(path, "path");
            if (!path.startsWith("/")) {
                throw new IllegalArgumentException("Callback path must start with '/'");
            }
            HttpServer server = HttpServer.create(new InetSocketAddress(bindHost, port), 0);
            ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "kompile-oauth-callback");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);
            CompletableFuture<AuthorizationInput> result = new CompletableFuture<>();
            server.createContext(path, exchange ->
                    handleCallback(exchange, path, expectedState, result));
            server.start();
            int actualPort = server.getAddress().getPort();
            URI redirectUri = URI.create("http://" + redirectHost + ":" + actualPort + path);
            return new CallbackServer(server, executor, redirectUri, result);
        }

        URI redirectUri() {
            return redirectUri;
        }

        AuthorizationInput await(Duration timeout) throws IOException, InterruptedException {
            try {
                return result.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new IOException("OAuth callback timed out after " + timeout.toMinutes() + " minutes", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException io) {
                    throw io;
                }
                throw new IOException("OAuth callback failed", cause);
            }
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }

        private static void handleCallback(
                HttpExchange exchange,
                String expectedPath,
                String expectedState,
                CompletableFuture<AuthorizationInput> result) throws IOException {
            try {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())
                        || !expectedPath.equals(exchange.getRequestURI().getPath())) {
                    sendHtml(exchange, 404, "OAuth callback route not found.");
                    return;
                }
                Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
                String error = query.get("error");
                if (error != null) {
                    String description = query.getOrDefault("error_description", error);
                    sendHtml(exchange, 400, "Authorization was denied: " + description);
                    result.completeExceptionally(new IOException("OAuth authorization failed: " + description));
                    return;
                }
                String state = query.get("state");
                if (expectedState != null && !expectedState.equals(state)) {
                    sendHtml(exchange, 400, "OAuth state mismatch.");
                    return;
                }
                String code = query.get("code");
                if (code == null || code.isBlank()) {
                    sendHtml(exchange, 400, "OAuth callback did not include an authorization code.");
                    return;
                }
                sendHtml(exchange, 200, "Authentication completed. You may close this window.");
                result.complete(new AuthorizationInput(code, state));
            } finally {
                exchange.close();
            }
        }

        private static void sendHtml(HttpExchange exchange, int status, String message) throws IOException {
            String safe = message
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;");
            byte[] body = ("<!doctype html><html><body><h2>" + safe + "</h2></body></html>")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    static final class DeviceCodePoller {
        private static final long MINIMUM_INTERVAL_MILLIS = 1_000L;
        private static final long DEFAULT_INTERVAL_MILLIS = 5_000L;
        private static final long SLOW_DOWN_INCREMENT_MILLIS = 5_000L;

        private final Sleeper sleeper;
        private final LongSupplier clock;

        DeviceCodePoller() {
            this(Thread::sleep, System::currentTimeMillis);
        }

        DeviceCodePoller(Sleeper sleeper, LongSupplier clock) {
            this.sleeper = sleeper;
            this.clock = clock;
        }

        <T> T poll(
                Integer intervalSeconds,
                int expiresInSeconds,
                boolean waitBeforeFirstPoll,
                PollOperation<T> operation) throws IOException, InterruptedException {
            if (expiresInSeconds <= 0) {
                throw new IOException("OAuth device flow has an invalid expiry");
            }
            long deadline = clock.getAsLong() + expiresInSeconds * 1000L;
            long intervalMillis = intervalSeconds == null
                    ? DEFAULT_INTERVAL_MILLIS
                    : Math.max(MINIMUM_INTERVAL_MILLIS, intervalSeconds.longValue() * 1000L);
            boolean slowedDown = false;

            if (waitBeforeFirstPoll) {
                sleepWithinDeadline(intervalMillis, deadline);
            }

            while (clock.getAsLong() < deadline) {
                PollResult<T> result = operation.poll();
                if (result.status() == PollStatus.COMPLETE) {
                    return result.value();
                }
                if (result.status() == PollStatus.FAILED) {
                    throw new IOException(result.message());
                }
                if (result.status() == PollStatus.SLOW_DOWN) {
                    slowedDown = true;
                    intervalMillis = result.intervalSeconds() != null && result.intervalSeconds() > 0
                            ? Math.max(MINIMUM_INTERVAL_MILLIS, result.intervalSeconds().longValue() * 1000L)
                            : intervalMillis + SLOW_DOWN_INCREMENT_MILLIS;
                }
                sleepWithinDeadline(intervalMillis, deadline);
            }
            throw new IOException(slowedDown
                    ? "OAuth device flow timed out after slow_down responses"
                    : "OAuth device flow timed out");
        }

        private void sleepWithinDeadline(long intervalMillis, long deadline) throws InterruptedException {
            long remaining = deadline - clock.getAsLong();
            if (remaining > 0) {
                sleeper.sleep(Math.min(intervalMillis, remaining));
            }
        }
    }

    enum PollStatus {
        PENDING,
        SLOW_DOWN,
        COMPLETE,
        FAILED
    }

    record PollResult<T>(
            PollStatus status,
            T value,
            String message,
            Integer intervalSeconds) {
        static <T> PollResult<T> pending() {
            return new PollResult<>(PollStatus.PENDING, null, null, null);
        }

        static <T> PollResult<T> slowDown(Integer intervalSeconds) {
            return new PollResult<>(PollStatus.SLOW_DOWN, null, null, intervalSeconds);
        }

        static <T> PollResult<T> complete(T value) {
            return new PollResult<>(PollStatus.COMPLETE, value, null, null);
        }

        static <T> PollResult<T> failed(String message) {
            return new PollResult<>(PollStatus.FAILED, null, message, null);
        }
    }

    @FunctionalInterface
    interface PollOperation<T> {
        PollResult<T> poll() throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
