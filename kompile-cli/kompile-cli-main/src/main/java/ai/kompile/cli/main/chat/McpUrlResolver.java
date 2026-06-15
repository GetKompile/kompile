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

package ai.kompile.cli.main.chat;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Shared utility for resolving the MCP server URL of a running kompile-app instance.
 * <p>
 * Supports three resolution strategies:
 * <ol>
 *   <li>Explicit URL provided by the user</li>
 *   <li>Explicit port provided by the user</li>
 *   <li>Auto-detection by probing common ports (8080, 8443, 9090, 3000)</li>
 * </ol>
 * <p>
 * Thread-safe: each caller should maintain their own instance for caching,
 * or use the static resolution methods directly.
 */
public class McpUrlResolver {

    /** Shared HttpClient for probing (not closed individually, reused across probes). */
    private static final HttpClient PROBE_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(500))
            .build();

    /** ANSI reset code for terminal output. */
    private static final String RESET = "\033[0m";
    /** ANSI dim code for terminal output. */
    private static final String DIM = "\033[2m";

    /** Common ports to probe for auto-detection. */
    private static final int[] PROBE_PORTS = {8080, 8443, 9090, 3000};

    /** In-memory cache for resolved URL. */
    private String resolvedMcpUrl;
    private boolean mcpUrlResolved;

    /**
     * Create a new resolver with no cached state.
     */
    public McpUrlResolver() {
        this.resolvedMcpUrl = null;
        this.mcpUrlResolved = false;
    }

    /**
     * Resolve the MCP URL, using the cache if available.
     *
     * @param kompileUrl explicit URL provided by the user (may be null or empty)
     * @param mcpPort    explicit port provided by the user (0 means not set)
     * @return the resolved MCP URL, or null if not found
     */
    public String resolveMcpUrl(String kompileUrl, int mcpPort) {
        if (mcpUrlResolved) return resolvedMcpUrl;
        mcpUrlResolved = true;
        resolvedMcpUrl = doResolveMcpUrl(kompileUrl, mcpPort);
        return resolvedMcpUrl;
    }

    /**
     * Reset the cache so the next {@link #resolveMcpUrl} call will re-resolve.
     */
    public void resetCache() {
        mcpUrlResolved = false;
        resolvedMcpUrl = null;
    }

    /**
     * Core resolution logic.
     */
    private String doResolveMcpUrl(String kompileUrl, int mcpPort) {
        if (kompileUrl != null && !kompileUrl.isEmpty()) {
            String url = kompileUrl.endsWith("/") ? kompileUrl.substring(0, kompileUrl.length() - 1) : kompileUrl;
            if (!url.contains("/mcp")) url = url + "/mcp/sse";
            return url;
        }

        if (mcpPort > 0) return "http://localhost:" + mcpPort + "/mcp/sse";

        for (int port : PROBE_PORTS) {
            if (probeKompileApp(port)) {
                System.out.println(DIM + "Auto-detected kompile-app on port " + port + RESET);
                return "http://localhost:" + port + "/mcp/sse";
            }
        }
        return null;
    }

    /**
     * Probe a single port to check if kompile-app is running there.
     */
    private static boolean probeKompileApp(int port) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://localhost:" + port + "/mcp/status"))
                    .timeout(Duration.ofMillis(1000))
                    .GET()
                    .build();
            HttpResponse<String> response = PROBE_HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Static convenience method for one-off resolution without caching.
     *
     * @param kompileUrl explicit URL (may be null or empty)
     * @param mcpPort    explicit port (0 means not set)
     * @return the resolved MCP URL, or null if not found
     */
    public static String resolveOnce(String kompileUrl, int mcpPort) {
        return new McpUrlResolver().doResolveMcpUrl(kompileUrl, mcpPort);
    }
}
