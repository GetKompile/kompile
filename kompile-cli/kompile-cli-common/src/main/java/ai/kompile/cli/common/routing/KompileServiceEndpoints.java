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

package ai.kompile.cli.common.routing;

import ai.kompile.cli.common.registry.InstanceInfo;
import ai.kompile.cli.common.registry.InstanceRegistry;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Maps an API family to the kompile process that serves it, now that one server on {@code :8080}
 * is no longer the whole backend.
 *
 * <p>{@code kompile-app-main} is the admin console; {@code kompile-app-chat} owns the chat surface
 * and {@code kompile-app-crawl-manager} owns crawl/ingest/indexing. Every app mounts the shared
 * surface (projects, fact sheets, documents, sources, {@code /api/setup/status}), so an unmatched
 * path resolves to {@link KompileService#ADMIN} — correct for a full install and harmless for a
 * shared contract.</p>
 *
 * <p>Per service the ladder is: caller-supplied URL &gt; {@code -Dkompile.<svc>.url} &gt;
 * {@code KOMPILE_<SVC>_URL} &gt; {@code service-endpoints.json} &gt; a running instance in the
 * {@link InstanceRegistry} &gt; the built-in port. An explicit URL always wins outright, which is
 * what keeps {@code --url}/{@code --port} working against an all-in-one deployment: the caller has
 * named the server, so no routing is applied.</p>
 *
 * <p>This deliberately does not probe. {@code InstanceDiscovery} guesses by hammering ports, and a
 * guess between two personas that both answer {@code /actuator/health} is worse than a stated
 * default — the persona is a property of the contract, not of what happens to be listening.</p>
 *
 * @see ServiceEndpointsConfigManager
 * @see <a href="file:../../../../../../../../../docs/architecture/app-persona-boundary.md">app-persona-boundary.md</a>
 */
public final class KompileServiceEndpoints {

    /**
     * Path prefix → owning service, from {@code docs/architecture/app-persona-boundary.md}.
     * Longest matching prefix wins, so the handful of admin contracts that live under a chat
     * prefix ({@code /api/agents/api-config}, {@code /api/rag/test}) stay on the admin console.
     * Only paths that are NOT on every app need an entry; the shared surface falls through.
     */
    private static final Map<String, KompileService> DEFAULT_ROUTES = buildDefaultRoutes();

    private KompileServiceEndpoints() {
    }

    private static Map<String, KompileService> buildDefaultRoutes() {
        Map<String, KompileService> routes = new LinkedHashMap<>();

        // --- chat (kompile-app-web-chat) ---
        routes.put("/api/agents", KompileService.CHAT);
        routes.put("/api/chat", KompileService.CHAT);
        routes.put("/api/chat-sessions", KompileService.CHAT);
        routes.put("/api/rag", KompileService.CHAT);
        routes.put("/api/graph-rag", KompileService.CHAT);
        routes.put("/api/system-prompts", KompileService.CHAT);
        routes.put("/api/skills", KompileService.CHAT);
        routes.put("/api/session-metrics", KompileService.CHAT);
        routes.put("/api/tool-calls", KompileService.CHAT);
        routes.put("/api/kb-grounding", KompileService.CHAT);
        routes.put("/api/explain", KompileService.CHAT);
        routes.put("/api/grounding", KompileService.CHAT);
        routes.put("/api/prompts", KompileService.CHAT);

        // Admin contracts nested under a chat prefix — longer, so they win.
        routes.put("/api/agents/api-config", KompileService.ADMIN);
        routes.put("/api/agents/cli-config", KompileService.ADMIN);
        routes.put("/api/rag/test", KompileService.ADMIN);

        // --- crawl (kompile-app-web-crawl) ---
        routes.put("/api/unified-crawl", KompileService.CRAWL);
        routes.put("/api/crawlers", KompileService.CRAWL);
        routes.put("/api/crawl-events", KompileService.CRAWL);
        routes.put("/api/distributed-crawl", KompileService.CRAWL);
        routes.put("/api/cluster", KompileService.CRAWL);
        routes.put("/api/graph-extraction", KompileService.CRAWL);
        routes.put("/api/graph/extraction-models", KompileService.CRAWL);
        routes.put("/api/graph/hydration", KompileService.CRAWL);
        routes.put("/api/ingest", KompileService.CRAWL);
        routes.put("/api/internal/ingest", KompileService.CRAWL);
        routes.put("/api/indexer", KompileService.CRAWL);
        routes.put("/api/indexing", KompileService.CRAWL);
        routes.put("/api/schedules", KompileService.CRAWL);
        routes.put("/api/confluence", KompileService.CRAWL);
        routes.put("/api/email/extract-values", KompileService.CRAWL);
        routes.put("/api/chunk-manager", KompileService.CRAWL);
        routes.put("/api/cross-index", KompileService.CRAWL);
        routes.put("/api/vector-population", KompileService.CRAWL);
        routes.put("/api/enrichment", KompileService.CRAWL);

        // --- graph exploration (kompile-app-web-graph) ---
        // Mounted by chat and crawl-manager but NOT by the admin console, so these two cannot be
        // left to fall through. Chat is the primary consumer: both callers are chat tools.
        routes.put("/api/graph/aggregate", KompileService.CHAT);
        routes.put("/api/graph/forecast", KompileService.CHAT);

        return Map.copyOf(routes);
    }

    /** The built-in route table, for display and for merging user overrides on top. */
    public static Map<String, KompileService> defaultRoutes() {
        return DEFAULT_ROUTES;
    }

    /** The service owning {@code apiPath}; {@link KompileService#ADMIN} when nothing claims it. */
    public static KompileService serviceForPath(String apiPath) {
        String path = normalizePath(apiPath);
        if (path == null) {
            return KompileService.ADMIN;
        }
        KompileService best = KompileService.ADMIN;
        int bestLength = -1;
        for (Map.Entry<String, KompileService> route : effectiveRoutes().entrySet()) {
            String prefix = route.getKey();
            if (matches(path, prefix) && prefix.length() > bestLength) {
                best = route.getValue();
                bestLength = prefix.length();
            }
        }
        return best;
    }

    /** Resolve a service's base URL with no caller override. */
    public static Resolution resolve(KompileService service) {
        return resolve(service, null);
    }

    /** Resolve a service's base URL; {@code explicitUrl} (a {@code --url} flag) wins outright. */
    public static Resolution resolve(KompileService service, String explicitUrl) {
        String url = normalizeUrl(explicitUrl);
        if (url != null) {
            return new Resolution(service, url, Source.EXPLICIT);
        }
        url = normalizeUrl(System.getProperty(service.systemProperty()));
        if (url != null) {
            return new Resolution(service, url, Source.SYSTEM_PROPERTY);
        }
        url = normalizeUrl(System.getenv(service.environmentVariable()));
        if (url != null) {
            return new Resolution(service, url, Source.ENVIRONMENT);
        }
        url = normalizeUrl(configuredUrl(service));
        if (url != null) {
            return new Resolution(service, url, Source.MANAGED_CONFIG);
        }
        url = normalizeUrl(registeredInstanceUrl(service));
        if (url != null) {
            return new Resolution(service, url, Source.MANAGED_INSTANCE);
        }
        return new Resolution(service, service.defaultUrl(), Source.DEFAULT);
    }

    /**
     * Resolve the base URL to use for {@code apiPath}. An explicit URL short-circuits routing
     * entirely — the caller named a server, so honour it even for a foreign contract.
     */
    public static Resolution resolveForPath(String apiPath, String explicitUrl) {
        String url = normalizeUrl(explicitUrl);
        if (url != null) {
            return new Resolution(serviceForPath(apiPath), url, Source.EXPLICIT);
        }
        return resolve(serviceForPath(apiPath), null);
    }

    /** Convenience: the base URL for {@code apiPath}, honouring {@code explicitUrl}. */
    public static String baseUrlForPath(String apiPath, String explicitUrl) {
        return resolveForPath(apiPath, explicitUrl).baseUrl();
    }

    /**
     * Resolved base URL of every kompile service, admin first, deduplicated.
     *
     * <p>For the handful of contracts that genuinely are "whichever process is up wins" — MCP
     * discovery, the shared {@code /api/projects} surface every persona mounts — walking this list
     * replaces the old hardcoded port sweeps ({@code {8080, 8443, 9090, 3000}}), which could not see
     * :8082 and would happily mistake an unrelated dev server on :3000 for a kompile backend. Order
     * is the enum's, so an all-in-one install still lands on the admin console first.</p>
     */
    public static List<String> allBaseUrls() {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        for (KompileService service : KompileService.values()) {
            urls.add(resolve(service).baseUrl());
        }
        return List.copyOf(urls);
    }

    /**
     * Convenience: a full URL for {@code apiPath}, honouring {@code explicitUrl}.
     *
     * <p>The query string is stripped for <i>routing</i> but kept in the returned URL. Most CLI
     * call sites pass their parameters inline ({@code /api/knowledge-graph/nodes?limit=20}), so
     * dropping the query here would silently swap every one of them for the server's default.</p>
     */
    public static String urlForPath(String apiPath, String explicitUrl) {
        String path = requestPath(apiPath);
        String base = baseUrlForPath(apiPath, explicitUrl);
        return path == null ? base : base + path;
    }

    private static Map<String, KompileService> effectiveRoutes() {
        Map<String, KompileService> overrides;
        try {
            overrides = ServiceEndpointsConfigManager.shared().current().routes();
        } catch (Exception ignored) {
            return DEFAULT_ROUTES;
        }
        if (overrides == null || overrides.isEmpty()) {
            return DEFAULT_ROUTES;
        }
        Map<String, KompileService> merged = new LinkedHashMap<>(DEFAULT_ROUTES);
        merged.putAll(overrides);
        return merged;
    }

    private static String configuredUrl(KompileService service) {
        try {
            return ServiceEndpointsConfigManager.shared().current().url(service);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String registeredInstanceUrl(KompileService service) {
        try {
            InstanceInfo instance = InstanceRegistry.findByType(service.componentId());
            return instance != null ? instance.getUrl() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** A prefix matches on a path-segment boundary: {@code /api/chat} does not claim {@code /api/chat-sessions}. */
    private static boolean matches(String path, String prefix) {
        if (!path.startsWith(prefix)) {
            return false;
        }
        return path.length() == prefix.length() || path.charAt(prefix.length()) == '/';
    }

    /**
     * The server-relative request target for {@code apiPath}: an absolute URL is reduced to its
     * path, a relative one gains a leading slash, and the query string is preserved.
     */
    private static String requestPath(String apiPath) {
        if (apiPath == null || apiPath.isBlank()) {
            return null;
        }
        String path = apiPath.trim();
        int scheme = path.indexOf("://");
        if (scheme >= 0) {
            int slash = path.indexOf('/', scheme + 3);
            path = slash < 0 ? "/" : path.substring(slash);
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return path;
    }

    /** {@link #requestPath} without the query string — what the route table is matched against. */
    private static String normalizePath(String apiPath) {
        String path = requestPath(apiPath);
        if (path == null) {
            return null;
        }
        int query = path.indexOf('?');
        return query >= 0 ? path.substring(0, query) : path;
    }

    private static String normalizeUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        return url.trim().replaceAll("/+$", "");
    }

    /** Where a resolved base URL came from — surfaced by {@code kompile doctor} and error messages. */
    public enum Source {
        EXPLICIT,
        SYSTEM_PROPERTY,
        ENVIRONMENT,
        MANAGED_CONFIG,
        MANAGED_INSTANCE,
        DEFAULT
    }

    public record Resolution(KompileService service, String baseUrl, Source source) {

        /**
         * Port of {@link #baseUrl()}, for the local-process checks that speak in ports
         * ({@code ServiceManager.checkHealth(int)}). Falls back to the service default when the
         * URL carries no explicit port.
         */
        public int port() {
            try {
                URI uri = URI.create(baseUrl);
                if (uri.getPort() > 0) {
                    return uri.getPort();
                }
                if ("https".equalsIgnoreCase(uri.getScheme())) {
                    return 443;
                }
                if ("http".equalsIgnoreCase(uri.getScheme())) {
                    return 80;
                }
            } catch (RuntimeException ignored) {
                // Fall through to the built-in port.
            }
            return service.defaultPort();
        }
    }
}
