/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/** Minimal MCP Streamable HTTP client for custom chat tool servers. */
final class McpStreamableHttpClient implements AutoCloseable {
    private static final String REQUESTED_PROTOCOL_VERSION = "2025-06-18";
    static final int MAX_RESPONSE_BYTES = Integer.getInteger(
            "kompile.mcp.http.maxResponseBytes", 8 * 1024 * 1024);
    private static final Set<String> MANAGED_HEADERS = Set.of(
            "accept", "content-type", "content-length", "host",
            "mcp-session-id", "mcp-protocol-version");

    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final URI endpoint;
    private final Map<String, String> headers;
    private final Duration timeout;
    private final AtomicLong nextId = new AtomicLong(1L);
    private final ExecutorService bodyReaders = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "mcp-http-body-reader");
        thread.setDaemon(true);
        return thread;
    });

    private volatile String sessionId;
    private volatile String protocolVersion;

    McpStreamableHttpClient(ObjectMapper mapper, String url,
                            Map<String, String> headers, long timeoutSeconds) throws IOException {
        this.mapper = mapper;
        try {
            this.endpoint = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid MCP HTTP URL: " + url, e);
        }
        String scheme = endpoint.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IOException("MCP HTTP URL must use http or https: " + url);
        }
        this.timeout = Duration.ofSeconds(Math.max(1L, timeoutSeconds));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(30L, Math.max(1L, timeoutSeconds))))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    void initialize() throws IOException, InterruptedException {
        ObjectNode params = mapper.createObjectNode()
                .put("protocolVersion", REQUESTED_PROTOCOL_VERSION);
        params.putObject("capabilities");
        params.putObject("clientInfo")
                .put("name", "kompile-cli")
                .put("version", "0.1.0");
        JsonNode result = request("initialize", params);
        protocolVersion = result.path("protocolVersion")
                .asText(REQUESTED_PROTOCOL_VERSION);
        notify("notifications/initialized", mapper.createObjectNode());
    }

    List<McpBundleToolLoader.RemoteTool> listTools() throws IOException, InterruptedException {
        List<McpBundleToolLoader.RemoteTool> tools = new ArrayList<>();
        Set<String> seenCursors = new HashSet<>();
        String cursor = null;
        for (int page = 0; page < 100; page++) {
            ObjectNode params = mapper.createObjectNode();
            if (cursor != null) params.put("cursor", cursor);
            JsonNode result = request("tools/list", params);
            for (JsonNode tool : result.path("tools")) {
                tools.add(new McpBundleToolLoader.RemoteTool(
                        tool.path("name").asText(),
                        tool.path("description").asText(""),
                        tool.path("inputSchema").isObject()
                                ? tool.path("inputSchema") : mapper.createObjectNode()));
            }
            String nextCursor = result.path("nextCursor").asText("").trim();
            if (nextCursor.isBlank()) return tools;
            if (!seenCursors.add(nextCursor)) {
                throw new IOException("MCP tools/list repeated cursor: " + nextCursor);
            }
            cursor = nextCursor;
        }
        throw new IOException("MCP tools/list exceeded 100 pages");
    }

    JsonNode callTool(String name, JsonNode arguments) throws IOException, InterruptedException {
        ObjectNode params = mapper.createObjectNode().put("name", name);
        params.set("arguments", arguments == null ? mapper.createObjectNode() : arguments);
        return request("tools/call", params);
    }

    /** Combined prompts + resources catalog; empty object when neither is supported. */
    JsonNode promptResourceCatalog() {
        ObjectNode catalog = mapper.createObjectNode();
        try {
            JsonNode prompts = request("prompts/list", mapper.createObjectNode(), false);
            if (prompts.path("prompts").isArray() && !prompts.path("prompts").isEmpty()) {
                catalog.set("prompts", prompts.path("prompts"));
            }
        } catch (IOException | InterruptedException ignored) {
            // Prompts capability not declared or call failed; continue.
        }
        try {
            JsonNode resources = request("resources/list", mapper.createObjectNode(), false);
            if (resources.path("resources").isArray() && !resources.path("resources").isEmpty()) {
                catalog.set("resources", resources.path("resources"));
            }
        } catch (IOException | InterruptedException ignored) {
            // Resources capability not declared or call failed.
        }
        return catalog;
    }

    private synchronized JsonNode request(String method, JsonNode params)
            throws IOException, InterruptedException {
        return request(method, params, true);
    }

    private JsonNode request(String method, JsonNode params, boolean allowSessionRecovery)
            throws IOException, InterruptedException {
        long id = nextId.getAndIncrement();
        ObjectNode envelope = mapper.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", method);
        envelope.set("params", params == null ? mapper.createObjectNode() : params);
        Response response;
        try {
            response = send(envelope);
        } catch (SessionExpiredException expired) {
            if (!allowSessionRecovery || protocolVersion == null) throw expired;
            sessionId = null;
            protocolVersion = null;
            initialize();
            return request(method, params, false);
        }
        JsonNode message = decodeResponse(response, id);
        JsonNode error = message.get("error");
        if (error != null && !error.isNull()) {
            throw new IOException("MCP " + method + " failed: "
                    + error.path("message").asText(error.toString()));
        }
        JsonNode result = message.get("result");
        if (result == null) {
            throw new IOException("MCP " + method + " response did not contain result");
        }
        return result;
    }

    private void notify(String method, JsonNode params)
            throws IOException, InterruptedException {
        ObjectNode envelope = mapper.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("method", method);
        envelope.set("params", params == null ? mapper.createObjectNode() : params);
        Response response = send(envelope);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("MCP notification failed: HTTP " + response.statusCode()
                    + bodySuffix(response.body()));
        }
    }

    private Response send(JsonNode envelope)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(envelope), StandardCharsets.UTF_8));
        addHeaders(builder);
        HttpResponse<InputStream> rawResponse = httpClient.send(
                builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        String expectedId = envelope.has("id") ? envelope.path("id").asText() : null;
        String body = readResponseBody(rawResponse, expectedId);
        Response response = new Response(rawResponse.statusCode(), rawResponse.headers(),
                body);
        response.headers().firstValue("Mcp-Session-Id")
                .filter(value -> !value.isBlank())
                .ifPresent(value -> sessionId = value);
        if (response.statusCode() == 404 && sessionId != null) {
            throw new SessionExpiredException("MCP HTTP session expired");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("MCP HTTP request failed: HTTP " + response.statusCode()
                    + bodySuffix(response.body()));
        }
        return response;
    }

    private String readResponseBody(HttpResponse<InputStream> response, String expectedId)
            throws IOException, InterruptedException {
        String contentType = response.headers().firstValue("Content-Type")
                .orElse("").toLowerCase(Locale.ROOT);
        InputStream input = response.body();
        Future<String> reader = bodyReaders.submit(() -> {
            try (InputStream body = input) {
                if (expectedId != null && contentType.contains("text/event-stream")) {
                    return readSseUntilResponse(body, expectedId, MAX_RESPONSE_BYTES);
                }
                return new String(readBounded(body, MAX_RESPONSE_BYTES), StandardCharsets.UTF_8);
            }
        });
        try {
            return reader.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            try { input.close(); } catch (IOException ignored) { }
            reader.cancel(true);
            throw new IOException("Timed out after " + timeout.toSeconds()
                    + "s reading MCP HTTP response", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("Could not read MCP HTTP response", cause);
        }
    }

    static byte[] readBounded(InputStream input, int maxBytes) throws IOException {
        byte[] body = input.readNBytes(maxBytes + 1);
        if (body.length > maxBytes) {
            throw new IOException("MCP HTTP response exceeds " + maxBytes + " bytes");
        }
        return body;
    }

    String readSseUntilResponse(InputStream input, String expectedId, int maxBytes)
            throws IOException {
        int bytesRead = 0;
        ByteArrayOutputStream lineBytes = new ByteArrayOutputStream();
        StringBuilder data = new StringBuilder();
        while (true) {
            int next = input.read();
            if (next < 0) {
                JsonNode candidate = parseSseLine(lineBytes, data);
                if (candidate != null && expectedId.equals(candidate.path("id").asText())) {
                    return mapper.writeValueAsString(candidate);
                }
                throw new IOException("MCP HTTP event stream closed before response id="
                        + expectedId);
            }
            if (++bytesRead > maxBytes) {
                throw new IOException("MCP HTTP response exceeds " + maxBytes + " bytes");
            }
            if (next == '\n') {
                JsonNode candidate = parseSseLine(lineBytes, data);
                lineBytes.reset();
                if (candidate != null && expectedId.equals(candidate.path("id").asText())) {
                    return mapper.writeValueAsString(candidate);
                }
            } else {
                lineBytes.write(next);
            }
        }
    }

    private JsonNode parseSseLine(ByteArrayOutputStream lineBytes, StringBuilder data)
            throws IOException {
        String line = lineBytes.toString(StandardCharsets.UTF_8);
        if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
        if (line.startsWith("data:")) {
            if (!data.isEmpty()) data.append('\n');
            data.append(line.substring(5).trim());
            return null;
        }
        if (!line.isEmpty() || data.isEmpty()) return null;
        JsonNode candidate = parseEventData(data.toString());
        data.setLength(0);
        return candidate;
    }

    private void addHeaders(HttpRequest.Builder builder) {
        headers.forEach((name, value) -> {
            if (name != null && value != null
                    && !MANAGED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                builder.header(name, value);
            }
        });
        if (sessionId != null && !sessionId.isBlank()) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        if (protocolVersion != null && !protocolVersion.isBlank()) {
            builder.header("MCP-Protocol-Version", protocolVersion);
        }
    }

    private JsonNode decodeResponse(Response response, long id) throws IOException {
        String body = response.body() == null ? "" : response.body().trim();
        if (body.isEmpty()) {
            throw new IOException("MCP HTTP response was empty");
        }
        String contentType = response.headers().firstValue("Content-Type")
                .orElse("").toLowerCase(Locale.ROOT);
        if (body.startsWith("{")) {
            try {
                return mapper.readTree(body);
            } catch (Exception e) {
                throw new IOException("Invalid MCP HTTP JSON response", e);
            }
        }
        if (contentType.contains("text/event-stream") || body.startsWith("event:")
                || body.startsWith("data:")) {
            JsonNode event = decodeSse(body, id);
            if (event != null) return event;
            throw new IOException("MCP HTTP event stream did not contain response id=" + id);
        }
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new IOException("Invalid MCP HTTP JSON response", e);
        }
    }

    private JsonNode decodeSse(String body, long id) throws IOException {
        String expectedId = Long.toString(id);
        StringBuilder data = new StringBuilder();
        for (String line : body.split("\\R", -1)) {
            if (line.startsWith("data:")) {
                if (!data.isEmpty()) data.append('\n');
                data.append(line.substring(5).trim());
            } else if (line.isEmpty() && !data.isEmpty()) {
                JsonNode candidate = parseEventData(data.toString());
                if (candidate != null && expectedId.equals(candidate.path("id").asText())) {
                    return candidate;
                }
                data.setLength(0);
            }
        }
        if (!data.isEmpty()) {
            JsonNode candidate = parseEventData(data.toString());
            if (candidate != null && expectedId.equals(candidate.path("id").asText())) {
                return candidate;
            }
        }
        return null;
    }

    private JsonNode parseEventData(String data) throws IOException {
        try {
            return mapper.readTree(data);
        } catch (Exception e) {
            throw new IOException("Invalid MCP HTTP SSE data", e);
        }
    }

    private static String bodySuffix(String body) {
        if (body == null || body.isBlank()) return "";
        String compact = body.replaceAll("\\s+", " ").trim();
        return ": " + compact.substring(0, Math.min(500, compact.length()));
    }

    private record Response(int statusCode, HttpHeaders headers, String body) { }

    private static final class SessionExpiredException extends IOException {
        private SessionExpiredException(String message) { super(message); }
    }

    @Override
    public void close() {
        String activeSession = sessionId;
        try {
            if (activeSession != null && !activeSession.isBlank()) {
                HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                        .timeout(Duration.ofSeconds(Math.min(10L, timeout.toSeconds())))
                        .header("Accept", "application/json, text/event-stream")
                        .DELETE();
                addHeaders(builder);
                httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            }
        } catch (Exception ignored) {
            // Session termination is best-effort; the server may return 405.
        } finally {
            sessionId = null;
            bodyReaders.shutdownNow();
        }
    }
}
