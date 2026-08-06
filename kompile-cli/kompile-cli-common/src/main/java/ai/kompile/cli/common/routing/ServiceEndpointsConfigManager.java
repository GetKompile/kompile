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

import ai.kompile.cli.common.config.ManagedJsonConfigManager;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The kompile-managed config behind {@link KompileServiceEndpoints}:
 * {@code service-endpoints.json} under the kompile config directory, holding one base URL per
 * {@link KompileService}, the model-staging and serving support processes, plus optional
 * path-prefix route overrides.
 *
 * <pre>{@code
 * {
 *   "adminUrl": "http://localhost:8080",
 *   "chatUrl":  "http://localhost:8081",
 *   "crawlUrl": "http://localhost:8082",
 *   "stagingUrl": "http://localhost:8090",
 *   "servingUrl": "http://127.0.0.1:8091",
 *   "routes":   { "/api/graph/aggregate": "crawl" }
 * }
 * }</pre>
 *
 * <p>An all-in-one deployment points all three URLs at the same host:port; a split deployment
 * points them at the three persona processes. {@code routes} re-assigns a path prefix to another
 * service, which is how a re-homed contract is handled without a CLI release.</p>
 *
 * <p>Absent keys stay absent — {@link #current()} does <b>not</b> substitute defaults, because
 * "unset" is what lets {@link KompileServiceEndpoints} fall through to instance discovery before
 * giving up on the built-in port. {@link #currentAsMap()} is the display/REST view and does show
 * effective values.</p>
 */
public class ServiceEndpointsConfigManager extends ManagedJsonConfigManager<ServiceEndpointsConfigManager.ServiceEndpointsConfig> {

    public static final String FILENAME = "service-endpoints.json";
    public static final String STAGING_URL_KEY = "stagingUrl";
    public static final String SERVING_URL_KEY = "servingUrl";
    public static final String ROUTES_KEY = "routes";
    public static final String DEFAULT_STAGING_URL = "http://localhost:8090";
    public static final String DEFAULT_SERVING_URL = "http://127.0.0.1:8091";

    private static final ServiceEndpointsConfigManager INSTANCE = new ServiceEndpointsConfigManager();

    public ServiceEndpointsConfigManager() {
        super(FILENAME);
    }

    /** Test seam: read/write an explicit file instead of the real {@code ~/.kompile} path. */
    public ServiceEndpointsConfigManager(Path configPath) {
        super(configPath);
    }

    public static ServiceEndpointsConfigManager shared() {
        return INSTANCE;
    }

    /**
     * Manager for the topology owned by one project. Project-launched components receive that
     * project root as {@code kompile.data.dir}, so their shared UI and runtime services read this
     * exact file rather than the global {@code ~/.kompile} topology.
     */
    public static ServiceEndpointsConfigManager forProjectDirectory(Path projectDirectory) {
        if (projectDirectory == null) {
            throw new IllegalArgumentException("projectDirectory is required");
        }
        return new ServiceEndpointsConfigManager(projectDirectory.toAbsolutePath().normalize()
                .resolve("config")
                .resolve(FILENAME));
    }

    /**
     * @param urls   base URL per service, containing only what the file actually set
     * @param stagingUrl model-staging base URL, or {@code null} when the file did not set it
     * @param servingUrl internal serving-child base URL, or {@code null} when not configured
     * @param routes path prefix → service, overriding {@link KompileServiceEndpoints}'s built-ins
     */
    public record ServiceEndpointsConfig(Map<KompileService, String> urls,
                                         String stagingUrl,
                                         String servingUrl,
                                         Map<String, KompileService> routes) {
        /** Compatibility constructor for callers that only configure routed web personas. */
        public ServiceEndpointsConfig(Map<KompileService, String> urls,
                                      Map<String, KompileService> routes) {
            this(urls, null, null, routes);
        }

        public String url(KompileService service) {
            return urls.get(service);
        }

        public String effectiveUrl(KompileService service) {
            String configured = url(service);
            return configured != null ? configured : service.defaultUrl();
        }

        public int port(KompileService service) {
            return portOf(effectiveUrl(service), service.defaultPort());
        }

        public String effectiveStagingUrl() {
            return stagingUrl != null ? stagingUrl : DEFAULT_STAGING_URL;
        }

        public String effectiveServingUrl() {
            return servingUrl != null ? servingUrl : DEFAULT_SERVING_URL;
        }

        public int stagingPort() {
            return portOf(effectiveStagingUrl(), 8090);
        }

        public int servingPort() {
            return portOf(effectiveServingUrl(), 8091);
        }
    }

    @Override
    protected ServiceEndpointsConfig defaults() {
        return new ServiceEndpointsConfig(new EnumMap<>(KompileService.class), null, null, Map.of());
    }

    @Override
    protected ServiceEndpointsConfig parse(JsonNode root) {
        Map<KompileService, String> urls = new EnumMap<>(KompileService.class);
        Map<String, KompileService> routes = new LinkedHashMap<>();
        if (root == null || !root.isObject()) {
            return new ServiceEndpointsConfig(urls, null, null, routes);
        }
        for (KompileService service : KompileService.values()) {
            JsonNode node = root.get(service.configKey());
            if (node != null && node.isTextual() && !node.asText().isBlank()) {
                urls.put(service, normalize(node.asText()));
            }
        }
        String stagingUrl = textualUrl(root, STAGING_URL_KEY);
        String servingUrl = textualUrl(root, SERVING_URL_KEY);
        JsonNode routeNode = root.get(ROUTES_KEY);
        if (routeNode != null && routeNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = routeNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                KompileService target = KompileService.fromId(entry.getValue().asText());
                if (target != null && !entry.getKey().isBlank()) {
                    routes.put(entry.getKey().trim(), target);
                }
            }
        }
        return new ServiceEndpointsConfig(urls, stagingUrl, servingUrl, routes);
    }

    @Override
    protected Map<String, Object> toMap(ServiceEndpointsConfig config) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (KompileService service : KompileService.values()) {
            String configured = config.url(service);
            out.put(service.configKey(),
                    configured != null ? configured : service.defaultUrl());
        }
        out.put(STAGING_URL_KEY, config.effectiveStagingUrl());
        out.put(SERVING_URL_KEY, config.effectiveServingUrl());
        Map<String, String> routes = new LinkedHashMap<>();
        KompileServiceEndpoints.defaultRoutes().forEach((prefix, service) -> routes.put(prefix, service.id()));
        config.routes().forEach((prefix, service) -> routes.put(prefix, service.id()));
        out.put(ROUTES_KEY, routes);
        return out;
    }

    @Override
    protected Set<String> ownedKeys() {
        return Set.of(KompileService.ADMIN.configKey(), KompileService.CHAT.configKey(),
                KompileService.CRAWL.configKey(), STAGING_URL_KEY, SERVING_URL_KEY, ROUTES_KEY);
    }

    private static String textualUrl(JsonNode root, String key) {
        JsonNode node = root.get(key);
        return node != null && node.isTextual() && !node.asText().isBlank()
                ? normalize(node.asText()) : null;
    }

    /** Validate and normalize a base URL accepted by the REST/UI configuration surfaces. */
    public static String requireHttpBaseUrl(String key, Object rawValue) {
        if (!(rawValue instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException(key + " must be a non-blank URL");
        }
        String normalized = normalize(value);
        final URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(key + " is not a valid URL", e);
        }
        String scheme = uri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || uri.getHost() == null || uri.getHost().isBlank()
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    key + " must be an HTTP(S) base URL without credentials, query, or fragment");
        }
        if (uri.getPort() == 0 || uri.getPort() > 65_535) {
            throw new IllegalArgumentException(key + " port must be between 1 and 65535");
        }
        return normalized;
    }

    /** Validate the internal serving-child endpoint shared by the REST UI and CLI surfaces. */
    public static String requireLoopbackServingBaseUrl(Object rawValue) {
        String url = requireHttpBaseUrl(SERVING_URL_KEY, rawValue);
        String host = URI.create(url).getHost();
        boolean loopback = "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host);
        if (!loopback) {
            throw new IllegalArgumentException(
                    SERVING_URL_KEY
                            + " must use localhost or a loopback address; the serving child is internal");
        }
        return url;
    }

    private static int portOf(String url, int fallback) {
        try {
            URI uri = URI.create(url);
            if (uri.getPort() > 0) {
                return uri.getPort();
            }
            if ("https".equalsIgnoreCase(uri.getScheme())) {
                return 443;
            }
            if ("http".equalsIgnoreCase(uri.getScheme())) {
                return 80;
            }
        } catch (Exception ignored) {
            // A hand-edited malformed file must not prevent project startup.
        }
        return fallback;
    }

    private static String normalize(String url) {
        return url.trim().replaceAll("/+$", "");
    }
}
