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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP SSE client that connects to a kompile-app MCP server.
 * Uses java.net.http.HttpClient for HTTP and manual SSE parsing.
 */
public class McpSseClient implements AutoCloseable {

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AtomicInteger requestIdCounter = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();

    private volatile String messageEndpointUrl;
    private volatile boolean connected;
    private volatile Thread sseReaderThread;

    public McpSseClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = JsonUtils.standardMapper();
    }

    /**
     * Connects to the MCP SSE endpoint and starts the background reader.
     * Waits for the 'endpoint' event that provides the message URL.
     */
    public void connect() throws IOException, InterruptedException {
        CompletableFuture<String> endpointFuture = new CompletableFuture<>();

        HttpRequest sseRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/sse"))
                .header("Accept", "text/event-stream")
                .GET()
                .build();

        // Send the SSE request asynchronously and start reading events
        httpClient.sendAsync(sseRequest, HttpResponse.BodyHandlers.ofInputStream())
                .thenAccept(response -> {
                    if (response.statusCode() != 200) {
                        endpointFuture.completeExceptionally(
                                new IOException("SSE connection failed: HTTP " + response.statusCode()));
                        return;
                    }

                    sseReaderThread = new Thread(() -> {
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(response.body()))) {
                            String eventType = null;
                            StringBuilder dataBuffer = new StringBuilder();

                            String line;
                            while ((line = reader.readLine()) != null && !Thread.currentThread().isInterrupted()) {
                                if (line.startsWith("event:")) {
                                    eventType = line.substring(6).trim();
                                } else if (line.startsWith("data:")) {
                                    dataBuffer.append(line.substring(5).trim());
                                } else if (line.isEmpty() && eventType != null) {
                                    // End of event
                                    String data = dataBuffer.toString();
                                    handleSseEvent(eventType, data, endpointFuture);
                                    eventType = null;
                                    dataBuffer.setLength(0);
                                }
                            }
                        } catch (IOException e) {
                            if (!Thread.currentThread().isInterrupted()) {
                                endpointFuture.completeExceptionally(e);
                            }
                        }
                    }, "mcp-sse-reader");
                    sseReaderThread.setDaemon(true);
                    sseReaderThread.start();
                })
                .exceptionally(ex -> {
                    endpointFuture.completeExceptionally(ex);
                    return null;
                });

        // Wait for the endpoint event (up to 10 seconds)
        try {
            this.messageEndpointUrl = endpointFuture.get(10, TimeUnit.SECONDS);
            this.connected = true;
        } catch (Exception e) {
            throw new IOException("Failed to establish MCP SSE connection: " + e.getMessage(), e);
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
        ObjectNode params = objectMapper.createObjectNode();
        ObjectNode clientInfo = objectMapper.createObjectNode();
        clientInfo.put("name", "kompile-cli");
        clientInfo.put("version", "0.1.0");
        params.set("clientInfo", clientInfo);
        params.put("protocolVersion", "2024-11-05");

        ObjectNode capabilities = objectMapper.createObjectNode();
        params.set("capabilities", capabilities);

        return sendRequest("initialize", params);
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
        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", name);
        if (arguments != null) {
            params.set("arguments", arguments);
        } else {
            params.set("arguments", objectMapper.createObjectNode());
        }

        JsonNode response = sendRequest("tools/call", params);
        return extractTextContent(response);
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
        if (!connected || messageEndpointUrl == null) {
            throw new IOException("Not connected to MCP server");
        }

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
                .uri(URI.create(messageEndpointUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(120))
                .build();

        HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (httpResponse.statusCode() >= 400) {
            pendingRequests.remove(id);
            throw new IOException("MCP request failed: HTTP " + httpResponse.statusCode()
                    + " " + httpResponse.body());
        }

        // Wait for the response via SSE (up to 120 seconds for tool calls)
        try {
            return future.get(120, TimeUnit.SECONDS);
        } catch (Exception e) {
            pendingRequests.remove(id);
            throw new IOException("MCP request timed out or failed: " + e.getMessage(), e);
        }
    }

    public boolean isConnected() {
        return connected;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    @Override
    public void close() {
        connected = false;
        if (sseReaderThread != null) {
            sseReaderThread.interrupt();
        }
        // Complete all pending futures
        pendingRequests.forEach((id, future) -> future.cancel(true));
        pendingRequests.clear();
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
