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

import ai.kompile.cli.common.http.KompileHttpClient;
import ai.kompile.cli.common.routing.KompileService;
import ai.kompile.cli.common.routing.KompileServiceEndpoints;

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
 *   <li><b>Routing</b>: the API path picks the service — {@code /api/rag} is chat,
 *       {@code /api/unified-crawl} is the crawl manager, the rest is admin
 *       ({@link KompileServiceEndpoints})</li>
 *   <li><b>Pinning</b>: {@link #setBaseUrl(String)} (e.g. from {@code --url}) bypasses routing</li>
 *   <li><b>Reconnection</b>: on ConnectException, re-resolves and retries if the service moved</li>
 *   <li><b>Configurable timeouts</b>: connect timeout on the shared HttpClient,
 *       per-request timeout on each call</li>
 *   <li><b>Probe cooldown</b>: availability checks are cached for 30s</li>
 *   <li><b>Health recheck</b>: re-verifies a reachable URL every 5 minutes</li>
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
    /** Explicit pin (e.g. {@code --url}); bypasses routing entirely while set. */
    private final AtomicReference<String> pinnedBaseUrl = new AtomicReference<>();
    /** Last base URL confirmed reachable — availability checks and diagnostics only. */
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
     * Pin the client to one base URL (e.g. from a {@code --url} flag). The caller has named
     * a server, so routing is skipped entirely — this is what keeps the tools working against
     * an all-in-one deployment.
     */
    public void setBaseUrl(String url) {
        if (url != null && !url.isBlank()) {
            String normalized = url.replaceAll("/+$", "");
            pinnedBaseUrl.set(normalized);
            cachedBaseUrl.set(normalized);
            lastSuccessTimeMs.set(System.currentTimeMillis());
        }
    }

    /**
     * Base URL for {@code path}: the pinned URL if one is set, otherwise the service that owns
     * the path. Never null — an unreachable service surfaces as a {@link ConnectException} on
     * send, naming the component to start.
     */
    public String baseUrlFor(String path) {
        String pinned = pinnedBaseUrl.get();
        return pinned != null ? pinned : KompileServiceEndpoints.baseUrlForPath(path, null);
    }

    /**
     * A base URL known to be reachable, for diagnostics and coarse availability checks.
     *
     * @return the pinned URL, the last reachable one, or {@code null} when no kompile service
     *         answers
     */
    public String getBaseUrl() {
        String pinned = pinnedBaseUrl.get();
        if (pinned != null) {
            return pinned;
        }
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
     * Check if any kompile service is reachable. Prefer {@link #isAvailable(String)} when the
     * caller knows which API it is about to hit.
     */
    public boolean isAvailable() {
        return getBaseUrl() != null;
    }

    /** Check whether the service that owns {@code path} is reachable. */
    public boolean isAvailable(String path) {
        String url = baseUrlFor(path);
        return new KompileHttpClient(url).isHealthy(url);
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
        String url = baseUrlFor(path);

        try {
            HttpResponse<String> response = doSend(method, url + path, body, timeout);
            lastSuccessTimeMs.set(System.currentTimeMillis());
            cachedBaseUrl.set(url);
            return response;
        } catch (ConnectException e) {
            // The owning service is down. Re-resolve once: a restart can move it to a new port,
            // which the instance registry picks up.
            cachedBaseUrl.compareAndSet(url, null);
            lastProbeTimeMs.set(0);

            String reresolved = baseUrlFor(path);
            if (!reresolved.equals(url)) {
                System.err.println("[kompile] Reconnected to backend at " + reresolved);
                HttpResponse<String> response = doSend(method, reresolved + path, body, timeout);
                lastSuccessTimeMs.set(System.currentTimeMillis());
                cachedBaseUrl.set(reresolved);
                return response;
            }

            // No cross-service fallback: only one persona serves this path, so retrying
            // another one would trade a connect error for a 404.
            if (pinnedBaseUrl.get() != null) {
                throw new ConnectException("kompile backend is not reachable at " + url
                        + ". Is it running?");
            }
            KompileService service = KompileServiceEndpoints.serviceForPath(path);
            throw new ConnectException(service.componentId() + " is not reachable at " + url
                    + " (it serves " + path + "). Start it with: kompile manage start "
                    + service.componentId());
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
     * Find a reachable kompile service and cache it. Walks the resolved endpoints rather than
     * scanning ports — the endpoints are declared, so the only open question is liveness.
     * Respects a cooldown interval.
     *
     * @return a reachable base URL, or {@code null} if no service answers
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

        KompileHttpClient probe = KompileHttpClient.routed();
        for (KompileService service : KompileService.values()) {
            String candidate = KompileServiceEndpoints.resolve(service).baseUrl();
            if (probe.isHealthy(candidate)) {
                String prev = cachedBaseUrl.getAndSet(candidate);
                if (prev == null || !prev.equals(candidate)) {
                    System.err.println("[kompile] Backend discovered at " + candidate);
                }
                return candidate;
            }
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
