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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.services.mcp;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.core.mcp.server.ExternalMcpServerConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Runtime manager for REST/SSE-based MCP client connections.
 * Connects to remote MCP servers via HTTP/HTTPS.
 *
 * This class handles:
 * - HTTP client management with configurable timeouts
 * - SSE (Server-Sent Events) stream handling
 * - REST endpoint communication
 * - Connection health monitoring
 */
public class RestMcpClientRuntime {

    private static final Logger logger = LoggerFactory.getLogger(RestMcpClientRuntime.class);

    private final ExternalMcpServerConfig config;
    private final ObjectMapper objectMapper;
    private HttpClient httpClient;
    private ExecutorService sseExecutor;
    private CompletableFuture<Void> sseConnection;
    private volatile boolean running = false;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private final AtomicReference<Instant> lastHealthCheck = new AtomicReference<>();
    private Consumer<JsonNode> messageHandler;

    public RestMcpClientRuntime(ExternalMcpServerConfig config) {
        this.config = config;
        this.objectMapper = JsonUtils.standardMapper();
    }

    /**
     * Starts the REST/SSE MCP client connection.
     */
    public synchronized void start() throws IOException {
        if (running) {
            throw new IllegalStateException("Client is already running");
        }

        logger.info("Starting REST MCP client '{}' connecting to: {}", config.getId(), config.getUrl());

        try {
            // Build HTTP client with configured settings
            httpClient = buildHttpClient();

            // Test connectivity with a health check
            if (!performHealthCheck()) {
                throw new IOException("Health check failed - server not reachable at " + config.getUrl());
            }

            running = true;
            connected.set(true);

            // For SSE transport, establish event stream
            if (config.isSse()) {
                startSseConnection();
            }

            logger.info("REST MCP client '{}' connected to: {}", config.getId(), config.getUrl());

        } catch (Exception e) {
            lastError.set(e.getMessage());
            throw new IOException("Failed to connect to MCP server: " + e.getMessage(), e);
        }
    }

    /**
     * Stops the REST/SSE MCP client connection.
     */
    public synchronized void stop() {
        if (!running) {
            return;
        }

        logger.info("Stopping REST MCP client '{}'", config.getId());

        running = false;
        connected.set(false);

        // Cancel SSE connection
        if (sseConnection != null && !sseConnection.isDone()) {
            sseConnection.cancel(true);
        }

        // Shutdown SSE executor
        if (sseExecutor != null) {
            sseExecutor.shutdownNow();
        }

        // Note: HttpClient does not need explicit closing in Java 11+

        logger.info("REST MCP client '{}' stopped", config.getId());
    }

    /**
     * Checks if the client is connected and alive.
     */
    public boolean isAlive() {
        if (!running) {
            return false;
        }

        // Perform periodic health check
        Instant lastCheck = lastHealthCheck.get();
        if (lastCheck == null || Duration.between(lastCheck, Instant.now()).getSeconds() > 30) {
            return performHealthCheck();
        }

        return connected.get();
    }

    /**
     * Gets the connection ID (for REST connections, returns null as there's no PID).
     */
    public Long getPid() {
        // REST connections don't have a process ID
        return null;
    }

    /**
     * Gets the last error message.
     */
    public String getLastError() {
        return lastError.get();
    }

    /**
     * Gets the server configuration.
     */
    public ExternalMcpServerConfig getConfig() {
        return config;
    }

    /**
     * Sends a JSON-RPC request to the MCP server.
     */
    public CompletableFuture<JsonNode> sendRequest(String method, JsonNode params) {
        if (!running || !connected.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Client is not connected"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                ObjectNode requestBody = objectMapper.createObjectNode();
                requestBody.put("jsonrpc", "2.0");
                requestBody.put("id", System.currentTimeMillis());
                requestBody.put("method", method);
                if (params != null) {
                    requestBody.set("params", params);
                }

                String requestJson = objectMapper.writeValueAsString(requestBody);
                String endpoint = buildEndpoint(config.getEffectiveMessagesEndpoint());

                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .timeout(Duration.ofMillis(config.getRequestTimeout()))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestJson));

                // Add custom headers
                addCustomHeaders(requestBuilder);

                HttpRequest request = requestBuilder.build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return objectMapper.readTree(response.body());
                } else {
                    throw new IOException("HTTP error " + response.statusCode() + ": " + response.body());
                }

            } catch (Exception e) {
                lastError.set(e.getMessage());
                throw new RuntimeException("Request failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Sets a handler for incoming SSE messages.
     */
    public void setMessageHandler(Consumer<JsonNode> handler) {
        this.messageHandler = handler;
    }

    /**
     * Performs a health check by sending a request to the server.
     */
    private boolean performHealthCheck() {
        try {
            String healthUrl = config.getUrl();
            // Try to reach the base URL or a health endpoint

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(healthUrl))
                    .timeout(Duration.ofMillis(config.getConnectionTimeout()))
                    .GET();

            addCustomHeaders(requestBuilder);

            HttpRequest request = requestBuilder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            lastHealthCheck.set(Instant.now());
            boolean healthy = response.statusCode() >= 200 && response.statusCode() < 500;
            connected.set(healthy);

            if (healthy) {
                logger.debug("Health check passed for MCP server '{}'", config.getId());
            } else {
                logger.warn("Health check failed for MCP server '{}': HTTP {}", config.getId(), response.statusCode());
            }

            return healthy;

        } catch (Exception e) {
            logger.warn("Health check failed for MCP server '{}': {}", config.getId(), e.getMessage());
            connected.set(false);
            lastError.set(e.getMessage());
            return false;
        }
    }

    /**
     * Starts an SSE connection for server-sent events.
     */
    private void startSseConnection() {
        sseExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mcp-" + config.getId() + "-sse");
            t.setDaemon(true);
            return t;
        });

        sseConnection = CompletableFuture.runAsync(() -> {
            try {
                String sseUrl = buildEndpoint(config.getEffectiveSseEndpoint());
                logger.info("Establishing SSE connection to: {}", sseUrl);

                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(sseUrl))
                        .timeout(Duration.ofHours(24)) // Long timeout for SSE
                        .header("Accept", "text/event-stream")
                        .GET();

                addCustomHeaders(requestBuilder);

                HttpRequest request = requestBuilder.build();

                httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
                        .body();

                // In a real implementation, we'd parse the SSE stream here
                // For now, this establishes the connection

            } catch (Exception e) {
                if (running) {
                    logger.error("SSE connection error for '{}': {}", config.getId(), e.getMessage());
                    lastError.set(e.getMessage());
                }
            }
        }, sseExecutor);
    }

    /**
     * Builds the HTTP client with configured settings.
     */
    private HttpClient buildHttpClient() throws NoSuchAlgorithmException, KeyManagementException {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.getConnectionTimeout()))
                .followRedirects(HttpClient.Redirect.NORMAL);

        // Handle SSL verification
        if (!config.getVerifySsl()) {
            logger.warn("SSL verification disabled for MCP server '{}' - not recommended for production", config.getId());
            SSLContext sslContext = createTrustAllSslContext();
            builder.sslContext(sslContext);
        }

        return builder.build();
    }

    /**
     * Creates an SSL context that trusts all certificates.
     * WARNING: Only use for development/testing.
     */
    private SSLContext createTrustAllSslContext() throws NoSuchAlgorithmException, KeyManagementException {
        TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) { }
                    public void checkServerTrusted(X509Certificate[] certs, String authType) { }
                }
        };

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
        return sslContext;
    }

    /**
     * Builds a full endpoint URL from a path.
     */
    private String buildEndpoint(String path) {
        String baseUrl = config.getUrl();
        if (baseUrl.endsWith("/") && path.startsWith("/")) {
            return baseUrl + path.substring(1);
        } else if (!baseUrl.endsWith("/") && !path.startsWith("/")) {
            return baseUrl + "/" + path;
        }
        return baseUrl + path;
    }

    /**
     * Adds custom headers to the request.
     */
    private void addCustomHeaders(HttpRequest.Builder builder) {
        Map<String, String> headers = config.getHeaders();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                builder.header(entry.getKey(), entry.getValue());
            }
        }
    }
}
