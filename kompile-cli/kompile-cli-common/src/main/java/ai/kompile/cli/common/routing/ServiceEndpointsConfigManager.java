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

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The kompile-managed config behind {@link KompileServiceEndpoints}:
 * {@code service-endpoints.json} under the kompile config directory, holding one base URL per
 * {@link KompileService} plus optional path-prefix route overrides.
 *
 * <pre>{@code
 * {
 *   "adminUrl": "http://localhost:8080",
 *   "chatUrl":  "http://localhost:8081",
 *   "crawlUrl": "http://localhost:8082",
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
    private static final String ROUTES_KEY = "routes";

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
     * @param urls   base URL per service, containing only what the file actually set
     * @param routes path prefix → service, overriding {@link KompileServiceEndpoints}'s built-ins
     */
    public record ServiceEndpointsConfig(Map<KompileService, String> urls,
                                         Map<String, KompileService> routes) {
        public String url(KompileService service) {
            return urls.get(service);
        }
    }

    @Override
    protected ServiceEndpointsConfig defaults() {
        return new ServiceEndpointsConfig(new EnumMap<>(KompileService.class), Map.of());
    }

    @Override
    protected ServiceEndpointsConfig parse(JsonNode root) {
        Map<KompileService, String> urls = new EnumMap<>(KompileService.class);
        Map<String, KompileService> routes = new LinkedHashMap<>();
        if (root == null || !root.isObject()) {
            return new ServiceEndpointsConfig(urls, routes);
        }
        for (KompileService service : KompileService.values()) {
            JsonNode node = root.get(service.configKey());
            if (node != null && node.isTextual() && !node.asText().isBlank()) {
                urls.put(service, normalize(node.asText()));
            }
        }
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
        return new ServiceEndpointsConfig(urls, routes);
    }

    @Override
    protected Map<String, Object> toMap(ServiceEndpointsConfig config) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (KompileService service : KompileService.values()) {
            String configured = config.url(service);
            out.put(service.configKey(),
                    configured != null ? configured : KompileServiceEndpoints.resolve(service).baseUrl());
        }
        Map<String, String> routes = new LinkedHashMap<>();
        KompileServiceEndpoints.defaultRoutes().forEach((prefix, service) -> routes.put(prefix, service.id()));
        config.routes().forEach((prefix, service) -> routes.put(prefix, service.id()));
        out.put(ROUTES_KEY, routes);
        return out;
    }

    @Override
    protected Set<String> ownedKeys() {
        return Set.of(KompileService.ADMIN.configKey(), KompileService.CHAT.configKey(),
                KompileService.CRAWL.configKey(), ROUTES_KEY);
    }

    private static String normalize(String url) {
        return url.trim().replaceAll("/+$", "");
    }
}
