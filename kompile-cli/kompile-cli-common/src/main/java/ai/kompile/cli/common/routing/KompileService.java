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

/**
 * The three kompile web processes a CLI command can talk to, since
 * {@code kompile-app-main} stopped serving end-user APIs.
 *
 * <p>Each app mounts {@code kompile-app-web-shared} — projects, fact sheets, documents, sources,
 * setup status, the SPA forward — so any of them can serve a shared contract. What differs is the
 * persona surface: chat APIs exist only on {@link #CHAT}, crawl/ingest/indexing APIs only on
 * {@link #CRAWL}, and everything admin (nd4j, staging, MCP admin, enforcer, model registry, graph
 * maintenance) only on {@link #ADMIN}. See {@code docs/architecture/app-persona-boundary.md}.</p>
 *
 * <p>The {@code defaultPort} here is the last resort, not the policy: every service is
 * overridable through {@link #systemProperty()}, {@link #environmentVariable()} and the managed
 * {@code service-endpoints.json} — see {@link KompileServiceEndpoints}.</p>
 */
public enum KompileService {

    /** {@code kompile-app-main} — the admin console. Also mounts the shared surface. */
    ADMIN("admin", "kompile-app-main", 8080, "kompile.app.url", "KOMPILE_APP_URL"),

    /** {@code kompile-app-chat} — chat, agents, RAG, skills, prompts, tool-call catalog. */
    CHAT("chat", "kompile-app-chat", 8081, "kompile.chat.url", "KOMPILE_CHAT_URL"),

    /** {@code kompile-app-crawl-manager} — crawls, ingest, indexing, schedules, extraction. */
    CRAWL("crawl", "kompile-app-crawl-manager", 8082, "kompile.crawl.url", "KOMPILE_CRAWL_URL");

    private final String id;
    private final String componentId;
    private final int defaultPort;
    private final String systemProperty;
    private final String environmentVariable;

    KompileService(String id, String componentId, int defaultPort,
                   String systemProperty, String environmentVariable) {
        this.id = id;
        this.componentId = componentId;
        this.defaultPort = defaultPort;
        this.systemProperty = systemProperty;
        this.environmentVariable = environmentVariable;
    }

    /** Short name used in {@code service-endpoints.json} route values: {@code admin|chat|crawl}. */
    public String id() {
        return id;
    }

    /** Component id under which {@code kompile manage start} registers a live instance. */
    public String componentId() {
        return componentId;
    }

    public int defaultPort() {
        return defaultPort;
    }

    /** Config key holding this service's base URL, e.g. {@code chatUrl}. */
    public String configKey() {
        return id + "Url";
    }

    public String systemProperty() {
        return systemProperty;
    }

    public String environmentVariable() {
        return environmentVariable;
    }

    public String defaultUrl() {
        return "http://localhost:" + defaultPort;
    }

    /** Parse a route value / config id; {@code null} when it names no known service. */
    public static KompileService fromId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String needle = value.trim().toLowerCase();
        for (KompileService service : values()) {
            if (service.id.equals(needle) || service.componentId.equals(needle)) {
                return service;
            }
        }
        return null;
    }
}
