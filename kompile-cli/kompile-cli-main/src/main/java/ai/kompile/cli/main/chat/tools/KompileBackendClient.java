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

package ai.kompile.cli.main.chat.tools;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared HTTP client for communicating with a running kompile-app instance.
 * Used by all tools that need the kompile-app HTTP backend (RAG, GraphRAG,
 * CodeSearch, CodeGraph).
 *
 * <h3>Features</h3>
 * <ul>
 *   <li><b>Auto-detection</b>: probes ports 8080/8443/9090/3000 for a live kompile-app</li>
 *   <li><b>Reconnection</b>: on ConnectException, re-probes and retries on the new URL</li>
 *   <li><b>Configurable timeouts</b>: connect timeout on the shared HttpClient,
 *       per-request timeout on each call</li>
 *   <li><b>Probe cooldown</b>: avoids hammering ports — re-probes at most every 30s</li>
 *   <li><b>Health recheck</b>: re-verifies a healthy URL every 5 minutes to detect port changes</li>
 * </ul>
 *
 * <h3>MCP spec note</h3>
 * The MCP protocol defines transport-level reconnection (SSE {@code retry} field,
 * ping/pong keepalives) but NOT application-level tool reconnection. This client
 * provides tool-level resilience above the MCP layer — when the kompile-app HTTP
 * backend goes down and comes back (possibly on a different port), tools
 * automatically reconnect without requiring an MCP server restart.
 */
public class KompileBackendClient {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** Minimum interval between port probes (milliseconds). */
    private static final long PROBE_COOLDOWN_MS = 30_000;

    /** Re-verify a healthy URL after this interval (milliseconds). */
    private static final long HEALTH_RECHECK_MS = 300_000;

    /** Lazy holder for safe static initialization in GraalVM native image. */
    private static final class Holder {
        static final KompileBackendClient INSTANCE = new KompileBackendClient();
    }

    private final HttpClient httpClient;
    private final AtomicReference<String> cachedBaseUrl = new AtomicReference<>();
    private final AtomicLong lastProbeTimeMs = new AtomicLong(0);
    private final AtomicLong lastSuccessTimeMs = new AtomicLong(0);

    private KompileBackendClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public static KompileBackendClient getInstance() {
        return Holder.INSTANCE;
    }

    /**
     * Seed the client with an explicit base URL (e.g. from {@code --url} flag
     * or auto-detection at startup). This bypasses probing until the URL fails.
     */
    public void setBaseUrl(String url) {
        if (url != null && !url.isBlank()) {
            cachedBaseUrl.set(url.replaceAll("/+$", ""));
            lastSuccessTimeMs.set(System.currentTimeMillis());
        }
    }

    /**
     * Get the current base URL, auto-detecting if needed.
     *
     * @return the base URL (e.g. {@code http://localhost:8080}), or {@code null}
     *         if no kompile-app instance is reachable
     */
    public String getBaseUrl() {
        String current = cachedBaseUrl.get();

        // If we have a URL and it was recently successful, return it directly
        if (current != null) {
            long sinceSuccess = System.currentTimeMillis() - lastSuccessTimeMs.get();
            if (sinceSuccess < HEALTH_RECHECK_MS) {
                return current;
            }
        }

        // Probe (or re-probe) for a live instance
        return probeAndUpdate();
    }

    /**
     * Check if the backend is reachable (probes if needed).
     */
    public boolean isAvailable() {
        return getBaseUrl() != null;
    }

    /**
     * Send a GET request to the kompile-app backend.
     *
     * @param path    the API path (e.g. {@code /api/search/cross-index})
     * @param timeout per-request timeout
     * @return the HTTP response
     * @throws ConnectException  if the backend is unreachable after reconnection attempt
     * @throws HttpTimeoutException if the request times out
     */
    public HttpResponse<String> get(String path, Duration timeout) throws Exception {
        return sendWithReconnect("GET", path, null, timeout);
    }

    /**
     * Send a POST request to the kompile-app backend.
     *
     * @param path    the API path
     * @param jsonBody the JSON request body
     * @param timeout  per-request timeout
     * @return the HTTP response
     * @throws ConnectException  if the backend is unreachable after reconnection attempt
     * @throws HttpTimeoutException if the request times out
     */
    public HttpResponse<String> post(String path, String jsonBody, Duration timeout) throws Exception {
        return sendWithReconnect("POST", path, jsonBody, timeout);
    }

    /**
     * Send a DELETE request to the kompile-app backend.
     *
     * @param path    the API path
     * @param timeout per-request timeout
     * @return the HTTP response
     * @throws ConnectException  if the backend is unreachable after reconnection attempt
     * @throws HttpTimeoutException if the request times out
     */
    public HttpResponse<String> delete(String path, Duration timeout) throws Exception {
        return sendWithReconnect("DELETE", path, null, timeout);
    }

    /**
     * Core send logic with automatic reconnection on ConnectException.
     * <ol>
     *   <li>Resolve the base URL (from cache or by probing)</li>
     *   <li>Send the request</li>
     *   <li>On ConnectException: re-probe and retry once on the new URL</li>
     *   <li>On success: update the last-success timestamp</li>
     * </ol>
     */
    private HttpResponse<String> sendWithReconnect(String method, String path,
                                                    String body, Duration timeout) throws Exception {
        String url = getBaseUrl();
        if (url == null) {
            throw new ConnectException(
                    "kompile-app is not running. Start it with: kompile-app or kompile run");
        }

        try {
            HttpResponse<String> response = doSend(method, url + path, body, timeout);
            lastSuccessTimeMs.set(System.currentTimeMillis());
            return response;
        } catch (ConnectException e) {
            // Backend went down — invalidate and re-probe
            cachedBaseUrl.compareAndSet(url, null);
            lastProbeTimeMs.set(0); // force re-probe past cooldown

            String reconnected = probeAndUpdate();
            if (reconnected != null) {
                // Found the backend (possibly on a different port) — retry once
                System.err.println("[kompile] Reconnected to backend at " + reconnected);
                HttpResponse<String> response = doSend(method, reconnected + path, body, timeout);
                lastSuccessTimeMs.set(System.currentTimeMillis());
                return response;
            }

            throw new ConnectException(
                    "kompile-app is not reachable (was at " + url + "). " +
                    "Is it running? Start with: kompile-app or kompile run");
        }
    }

    private HttpResponse<String> doSend(String method, String fullUrl,
                                         String body, Duration timeout) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");

        switch (method) {
            case "POST" -> builder.POST(body != null
                    ? HttpRequest.BodyPublishers.ofString(body)
                    : HttpRequest.BodyPublishers.noBody());
            case "DELETE" -> builder.DELETE();
            default -> builder.GET();
        }

        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Probe for a live kompile-app and update the cached URL.
     * Respects a cooldown interval to avoid hammering ports.
     *
     * @return the discovered base URL, or {@code null} if nothing found
     */
    private String probeAndUpdate() {
        long now = System.currentTimeMillis();
        long lastProbe = lastProbeTimeMs.get();

        // Within cooldown — return whatever we have cached
        if (now - lastProbe < PROBE_COOLDOWN_MS) {
            return cachedBaseUrl.get();
        }

        // CAS to prevent concurrent probing
        if (!lastProbeTimeMs.compareAndSet(lastProbe, now)) {
            return cachedBaseUrl.get();
        }

        String sseUrl = ai.kompile.cli.main.chat.McpUrlResolver.resolveOnce(null, 0);
        if (sseUrl != null) {
            // McpUrlResolver returns e.g. http://localhost:8080/mcp/sse — strip the path
            String baseUrl = sseUrl.replaceAll("/mcp/sse$", "");
            String prev = cachedBaseUrl.getAndSet(baseUrl);
            if (prev == null || !prev.equals(baseUrl)) {
                System.err.println("[kompile] Backend discovered at " + baseUrl);
            }
            return baseUrl;
        }

        cachedBaseUrl.set(null);
        return null;
    }

    /**
     * Force the next access to re-probe (e.g. after a known state change
     * like starting or stopping kompile-app).
     */
    public void invalidate() {
        lastProbeTimeMs.set(0);
        lastSuccessTimeMs.set(0);
    }

    /**
     * Get the shared HttpClient (for tools that need direct access,
     * e.g. URL-encoded GET requests with custom query strings).
     */
    public HttpClient httpClient() {
        return httpClient;
    }
}
