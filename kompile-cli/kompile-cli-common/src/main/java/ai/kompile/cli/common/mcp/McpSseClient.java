/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.common.mcp;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP SSE client that connects to a kompile-app MCP server.
 * Uses java.net.http.HttpClient for HTTP and manual SSE parsing.
 */
public class McpSseClient implements AutoCloseable {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_HANDSHAKE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_RESPONSE_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration DEFAULT_RECONNECT_BACKOFF = Duration.ofMillis(250);
    private static final int DEFAULT_CONNECT_ATTEMPTS = 4;

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration handshakeTimeout;
    private final Duration responseTimeout;
    private final Duration reconnectBackoff;
    private final int connectAttempts;
    private final Object connectionLock = new Object();
    private final AtomicInteger requestIdCounter = new AtomicInteger(1);
    private final AtomicInteger connectionGeneration = new AtomicInteger();
    private final ConcurrentHashMap<Integer, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();

    private volatile String messageEndpointUrl;
    private volatile boolean connected;
    private volatile boolean protocolInitialized;
    private volatile boolean closed;
    private volatile Thread sseReaderThread;
    private volatile InputStream activeSseBody;

    public McpSseClient(String baseUrl) {
        this(baseUrl, DEFAULT_CONNECT_TIMEOUT, DEFAULT_HANDSHAKE_TIMEOUT,
                DEFAULT_RESPONSE_TIMEOUT, DEFAULT_CONNECT_ATTEMPTS,
                DEFAULT_RECONNECT_BACKOFF);
    }

    McpSseClient(String baseUrl, Duration connectTimeout, Duration handshakeTimeout,
                 Duration responseTimeout, int connectAttempts, Duration reconnectBackoff) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        this.objectMapper = JsonUtils.standardMapper();
        this.handshakeTimeout = handshakeTimeout;
        this.responseTimeout = responseTimeout;
        this.connectAttempts = Math.max(1, connectAttempts);
        this.reconnectBackoff = reconnectBackoff;
    }

    /**
     * Connects to the MCP SSE endpoint and starts the background reader.
     * Waits for the 'endpoint' event that provides the message URL.
     */
    public void connect() throws IOException, InterruptedException {
        synchronized (connectionLock) {
            if (closed) throw new IOException("MCP client is closed");
            if (isConnectionLive()) return;

            IOException lastFailure = null;
            for (int attempt = 1; attempt <= connectAttempts; attempt++) {
                try {
                    establishConnection();
                    if (protocolInitialized) restoreProtocolHandshake();
                    return;
                } catch (IOException failure) {
                    lastFailure = failure;
                    invalidateConnection(failure, connectionGeneration.get());
                    if (attempt < connectAttempts) {
                        sleepBeforeReconnect(attempt);
                    }
                }
            }
            throw new IOException("Failed to establish MCP SSE connection after "
                    + connectAttempts + " attempts: "
                    + (lastFailure == null ? "unknown error" : lastFailure.getMessage()),
                    lastFailure);
        }
    }

    private void establishConnection() throws IOException, InterruptedException {
        closeActiveSseBody();
        CompletableFuture<String> endpointFuture = new CompletableFuture<>();
        HttpRequest sseRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/sse"))
                .header("Accept", "text/event-stream")
                .timeout(handshakeTimeout)
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(
                sseRequest, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("SSE connection failed: HTTP " + response.statusCode());
        }

        int generation = connectionGeneration.incrementAndGet();
        activeSseBody = response.body();
        Thread readerThread = new Thread(
                () -> readSse(response.body(), endpointFuture, generation),
                "mcp-sse-reader-" + generation);
        readerThread.setDaemon(true);
        sseReaderThread = readerThread;
        readerThread.start();

        try {
            String endpoint = endpointFuture.get(
                    handshakeTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (connectionGeneration.get() != generation || !readerThread.isAlive()) {
                throw new IOException("MCP SSE connection closed during handshake");
            }
            messageEndpointUrl = endpoint;
            connected = true;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw new IOException("MCP SSE handshake failed: "
                    + (cause == null ? e.getMessage() : cause.getMessage()), cause);
        } catch (TimeoutException e) {
            throw new IOException("Timed out waiting for MCP SSE endpoint", e);
        }
    }

    private void readSse(InputStream body, CompletableFuture<String> endpointFuture,
                         int generation) {
        IOException failure = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body))) {
            String eventType = null;
            StringBuilder dataBuffer = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null && !Thread.currentThread().isInterrupted()) {
                if (line.startsWith("event:")) {
                    eventType = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    dataBuffer.append(line.substring(5).trim());
                } else if (line.isEmpty() && eventType != null) {
                    handleSseEvent(eventType, dataBuffer.toString(), endpointFuture);
                    eventType = null;
                    dataBuffer.setLength(0);
                }
            }
            if (!closed && !Thread.currentThread().isInterrupted()) {
                failure = new IOException("MCP SSE connection closed by server");
            }
        } catch (IOException e) {
            if (!closed && !Thread.currentThread().isInterrupted()) failure = e;
        } finally {
            IOException terminal = failure == null
                    ? new IOException("MCP SSE connection closed") : failure;
            endpointFuture.completeExceptionally(terminal);
            invalidateConnection(terminal, generation);
        }
    }

    private void handleSseEvent(String eventType, String data, CompletableFuture<String> endpointFuture) {
        if ("endpoint".equals(eventType)) {
            // The data is the message endpoint URL (may be relative or absolute)
            String endpointUrl;
            if (data.startsWith("http://") || data.startsWith("https://")) {
                endpointUrl = data;
            } else {
                endpointUrl = baseUrl + (data.startsWith("/") ? data : "/" + data);
            }
            endpointFuture.complete(endpointUrl);
        } else if ("message".equals(eventType)) {
            try {
                JsonNode json = objectMapper.readTree(data);
                if (json.has("id")) {
                    int id = json.get("id").asInt();
                    CompletableFuture<JsonNode> future = pendingRequests.remove(id);
                    if (future != null) {
                        future.complete(json);
                    }
                }
            } catch (Exception e) {
                // Ignore malformed messages
            }
        }
    }

    /**
     * Sends the MCP initialize handshake.
     */
    public JsonNode initialize() throws IOException, InterruptedException {
        return sendRequest("initialize", initializationParams());
    }

    private ObjectNode initializationParams() {
        ObjectNode params = objectMapper.createObjectNode();
        ObjectNode clientInfo = objectMapper.createObjectNode();
        clientInfo.put("name", "kompile-cli");
        clientInfo.put("version", "0.1.0");
        params.set("clientInfo", clientInfo);
        params.put("protocolVersion", "2024-11-05");

        ObjectNode capabilities = objectMapper.createObjectNode();
        params.set("capabilities", capabilities);
        return params;
    }

    /**
     * Completes the MCP handshake by notifying the server that initialization
     * is complete. MCP servers may hold tools/call requests until this
     * notification arrives.
     */
    public void notifyInitialized() throws IOException, InterruptedException {
        sendNotification("notifications/initialized", null);
        protocolInitialized = true;
    }

    /**
     * Sends a JSON-RPC notification that does not expect an SSE response.
     */
    private void sendNotification(String method, JsonNode params) throws IOException, InterruptedException {
        ensureConnected();
        sendNotificationConnected(method, params);
    }

    private void sendNotificationConnected(String method, JsonNode params)
            throws IOException, InterruptedException {
        String endpoint = messageEndpointUrl;
        if (!isConnectionLive() || endpoint == null) throw new IOException("Not connected to MCP server");

        ObjectNode notification = objectMapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        if (params != null) {
            notification.set("params", params);
        }

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(notification)))
                .timeout(handshakeTimeout)
                .build();
        HttpResponse<String> httpResponse;
        try {
            httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (IOException failure) {
            invalidateConnection(failure, connectionGeneration.get());
            throw failure;
        }
        if (httpResponse.statusCode() >= 400) {
            throw new IOException("MCP notification failed: HTTP " + httpResponse.statusCode()
                    + " " + httpResponse.body());
        }
    }

    /**
     * Lists all available MCP tools.
     */
    public List<ToolInfo> listTools() throws IOException, InterruptedException {
        JsonNode result = sendRequest("tools/list", null);
        List<ToolInfo> tools = new ArrayList<>();

        JsonNode toolsNode = result.path("result").path("tools");
        if (toolsNode.isArray()) {
            for (JsonNode tool : toolsNode) {
                String name = tool.path("name").asText();
                String description = tool.path("description").asText("");
                JsonNode inputSchema = tool.path("inputSchema");
                tools.add(new ToolInfo(name, description, inputSchema));
            }
        }
        return tools;
    }

    /**
     * Calls an MCP tool by name with the given arguments.
     * Returns the text content from the tool result.
     */
    public String callTool(String name, JsonNode arguments) throws IOException, InterruptedException {
        return callToolResult(name, arguments).content();
    }

    /** Calls an MCP tool while preserving the protocol-level isError flag. */
    public ToolCallResult callToolResult(String name, JsonNode arguments)
            throws IOException, InterruptedException {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", name);
        if (arguments != null) {
            params.set("arguments", arguments);
        } else {
            params.set("arguments", objectMapper.createObjectNode());
        }

        JsonNode response = sendRequest("tools/call", params);
        boolean error = response.has("error")
                || response.path("result").path("isError").asBoolean(false);
        return new ToolCallResult(extractTextContent(response), error);
    }

    /**
     * Calls an MCP tool with arguments provided as a JSON string.
     */
    public String callTool(String name, String argumentsJson) throws IOException, InterruptedException {
        JsonNode args = argumentsJson != null && !argumentsJson.isBlank()
                ? objectMapper.readTree(argumentsJson)
                : objectMapper.createObjectNode();
        return callTool(name, args);
    }

    private String extractTextContent(JsonNode response) {
        JsonNode result = response.path("result");
        JsonNode content = result.path("content");
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : content) {
                if ("text".equals(item.path("type").asText())) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(item.path("text").asText());
                }
            }
            return sb.toString();
        }
        // Fallback: return the whole result as string
        if (!result.isMissingNode()) {
            return result.toString();
        }
        // Check for error
        JsonNode error = response.path("error");
        if (!error.isMissingNode()) {
            return "Error: " + error.path("message").asText(error.toString());
        }
        return response.toString();
    }

    private JsonNode sendRequest(String method, JsonNode params) throws IOException, InterruptedException {
        ensureConnected();
        return sendRequestConnected(method, params);
    }

    private JsonNode sendRequestConnected(String method, JsonNode params)
            throws IOException, InterruptedException {
        String endpoint = messageEndpointUrl;
        int generation = connectionGeneration.get();
        if (!isConnectionLive() || endpoint == null) throw new IOException("Not connected to MCP server");

        int id = requestIdCounter.getAndIncrement();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("method", method);
        request.put("id", id);
        if (params != null) {
            request.set("params", params);
        }

        String body = objectMapper.writeValueAsString(request);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(responseTimeout)
                .build();

        HttpResponse<String> httpResponse;
        try {
            httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (IOException failure) {
            pendingRequests.remove(id);
            invalidateConnection(failure, generation);
            throw failure;
        }
        if (httpResponse.statusCode() >= 400) {
            pendingRequests.remove(id);
            if (httpResponse.statusCode() == 404 || httpResponse.statusCode() == 408
                    || httpResponse.statusCode() >= 500) {
                invalidateConnection(new IOException(
                        "MCP endpoint returned HTTP " + httpResponse.statusCode()), generation);
            }
            throw new IOException("MCP request failed: HTTP " + httpResponse.statusCode()
                    + " " + httpResponse.body());
        }

        try {
            return future.get(responseTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            pendingRequests.remove(id);
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            pendingRequests.remove(id);
            Throwable cause = e.getCause();
            throw new IOException("MCP request failed: "
                    + (cause == null ? e.getMessage() : cause.getMessage()), cause);
        } catch (TimeoutException e) {
            pendingRequests.remove(id);
            IOException failure = new IOException("MCP request timed out after "
                    + responseTimeout.toSeconds() + " seconds", e);
            invalidateConnection(failure, generation);
            throw failure;
        }
    }

    private void ensureConnected() throws IOException, InterruptedException {
        if (!isConnectionLive()) connect();
    }

    private void restoreProtocolHandshake() throws IOException, InterruptedException {
        sendRequestConnected("initialize", initializationParams());
        sendNotificationConnected("notifications/initialized", null);
    }

    private boolean isConnectionLive() {
        Thread reader = sseReaderThread;
        return connected && messageEndpointUrl != null && reader != null && reader.isAlive();
    }

    private void invalidateConnection(IOException failure, int generation) {
        InputStream body;
        Thread reader;
        synchronized (connectionLock) {
            if (generation != connectionGeneration.get()) return;
            connected = false;
            messageEndpointUrl = null;
            body = activeSseBody;
            activeSseBody = null;
            reader = sseReaderThread;
            sseReaderThread = null;
            pendingRequests.forEach((id, future) -> future.completeExceptionally(failure));
            pendingRequests.clear();
        }
        if (body != null) {
            try {
                body.close();
            } catch (IOException ignored) {
                // The connection is already unusable.
            }
        }
        if (reader != null && reader != Thread.currentThread()) reader.interrupt();
    }

    private void closeActiveSseBody() {
        InputStream body = activeSseBody;
        activeSseBody = null;
        if (body != null) {
            try {
                body.close();
            } catch (IOException ignored) {
                // Reconnect replaces this body immediately.
            }
        }
        Thread reader = sseReaderThread;
        sseReaderThread = null;
        if (reader != null && reader != Thread.currentThread()) reader.interrupt();
        connected = false;
        messageEndpointUrl = null;
    }

    private void sleepBeforeReconnect(int failedAttempt) throws InterruptedException {
        int exponent = Math.max(0, Math.min(10, failedAttempt - 1));
        long baseMillis = Math.max(1L, reconnectBackoff.toMillis());
        long delayMillis = Math.min(2_000L, baseMillis * (1L << exponent));
        Thread.sleep(delayMillis);
    }

    public boolean isConnected() {
        return isConnectionLive();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    public record ToolCallResult(String content, boolean error) {
    }

    @Override
    public void close() {
        synchronized (connectionLock) {
            if (closed) return;
            closed = true;
            connectionGeneration.incrementAndGet();
            closeActiveSseBody();
            pendingRequests.forEach((id, future) -> future.completeExceptionally(
                    new IOException("MCP client closed")));
            pendingRequests.clear();
        }
    }

    /**
     * Describes an MCP tool.
     */
    public static class ToolInfo {
        private final String name;
        private final String description;
        private final JsonNode inputSchema;

        public ToolInfo(String name, String description, JsonNode inputSchema) {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
        }

        public String getName() { return name; }
        public String getDescription() { return description; }
        public JsonNode getInputSchema() { return inputSchema; }

        @Override
        public String toString() {
            return name + " - " + description;
        }
    }
}
