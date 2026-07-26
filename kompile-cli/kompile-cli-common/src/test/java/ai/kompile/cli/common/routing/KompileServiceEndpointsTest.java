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

import ai.kompile.cli.common.routing.KompileServiceEndpoints.Resolution;
import ai.kompile.cli.common.routing.KompileServiceEndpoints.Source;
import ai.kompile.cli.common.routing.ServiceEndpointsConfigManager.ServiceEndpointsConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the API-path → persona routing table and the per-service URL resolution ladder.
 *
 * <p>Only the rungs a test can control deterministically are asserted end-to-end: an explicit URL
 * and the system property, both of which outrank the managed config and the instance registry. The
 * lower rungs read real machine state — {@code ~/.kompile/service-endpoints.json} and whatever
 * happens to be registered — so asserting {@code MANAGED_INSTANCE} or {@code DEFAULT} here would
 * make the suite depend on whether a kompile process is running on the box. The managed-config rung
 * is covered instead through the config manager's own file seam.</p>
 */
class KompileServiceEndpointsTest {

    private final Set<String> touchedProperties = new HashSet<>();

    @AfterEach
    void clearOverrides() {
        touchedProperties.forEach(System::clearProperty);
        touchedProperties.clear();
    }

    private void pin(KompileService service, String url) {
        touchedProperties.add(service.systemProperty());
        System.setProperty(service.systemProperty(), url);
    }

    // ── Route table ─────────────────────────────────────────────────────────

    @Test
    void serviceForPath_routesEachPersonaSurface() {
        assertEquals(KompileService.CHAT, KompileServiceEndpoints.serviceForPath("/api/agents/chat/health"));
        assertEquals(KompileService.CHAT, KompileServiceEndpoints.serviceForPath("/api/chat-sessions/42"));
        assertEquals(KompileService.CRAWL, KompileServiceEndpoints.serviceForPath("/api/unified-crawl/jobs"));
        assertEquals(KompileService.CRAWL, KompileServiceEndpoints.serviceForPath("/api/indexing/status"));
        assertEquals(KompileService.ADMIN, KompileServiceEndpoints.serviceForPath("/api/nd4j/environment"));
    }

    @Test
    void serviceForPath_unclaimedPathsFallThroughToAdmin() {
        // The shared surface is mounted by all three apps, so it needs no route entry — but the
        // fallback has to be a real server, and the admin console is the one always installed.
        assertEquals(KompileService.ADMIN, KompileServiceEndpoints.serviceForPath("/api/projects/current"));
        assertEquals(KompileService.ADMIN, KompileServiceEndpoints.serviceForPath("/api/setup/status"));
        assertEquals(KompileService.ADMIN, KompileServiceEndpoints.serviceForPath("/api/fact-sheets"));
        assertEquals(KompileService.ADMIN, KompileServiceEndpoints.serviceForPath("/nope"));
        assertEquals(KompileService.ADMIN, KompileServiceEndpoints.serviceForPath(null));
        assertEquals(KompileService.ADMIN, KompileServiceEndpoints.serviceForPath("   "));
    }

    @Test
    void serviceForPath_longestPrefixWins() {
        // /api/agents and /api/rag belong to chat, but these two nested contracts are admin —
        // if the table were first-match rather than longest-match they would route to :8081 and
        // 404 there.
        assertEquals(KompileService.CHAT, KompileServiceEndpoints.serviceForPath("/api/agents/models"));
        assertEquals(KompileService.ADMIN, KompileServiceEndpoints.serviceForPath("/api/agents/api-config"));
        assertEquals(KompileService.ADMIN, KompileServiceEndpoints.serviceForPath("/api/agents/cli-config"));
        assertEquals(KompileService.CHAT, KompileServiceEndpoints.serviceForPath("/api/rag/query"));
        assertEquals(KompileService.ADMIN, KompileServiceEndpoints.serviceForPath("/api/rag/test"));

        // /api/graph splits three ways: extraction and hydration are crawl-side, the read-only
        // aggregate/forecast pair is on chat, and everything else stays admin.
        assertEquals(KompileService.CRAWL, KompileServiceEndpoints.serviceForPath("/api/graph/hydration/run"));
        assertEquals(KompileService.CRAWL, KompileServiceEndpoints.serviceForPath("/api/graph/extraction-models"));
        assertEquals(KompileService.CHAT, KompileServiceEndpoints.serviceForPath("/api/graph/aggregate"));
        assertEquals(KompileService.ADMIN, KompileServiceEndpoints.serviceForPath("/api/graph/rules"));
    }

    @Test
    void serviceForPath_matchesOnlyOnSegmentBoundaries() {
        // A prefix must not swallow a longer sibling word: /api/cluster owns the crawl cluster
        // API, but a hypothetical /api/clustering is a different contract entirely.
        assertEquals(KompileService.CRAWL, KompileServiceEndpoints.serviceForPath("/api/cluster"));
        assertEquals(KompileService.CRAWL, KompileServiceEndpoints.serviceForPath("/api/cluster/nodes"));
        assertEquals(KompileService.ADMIN, KompileServiceEndpoints.serviceForPath("/api/clustering"));
        assertEquals(KompileService.ADMIN, KompileServiceEndpoints.serviceForPath("/api/ingestion-report"));
    }

    @Test
    void serviceForPath_acceptsAbsoluteUrlsAndQueryStrings() {
        assertEquals(KompileService.CRAWL, KompileServiceEndpoints.serviceForPath(
                "http://localhost:9999/api/unified-crawl/jobs?active=true"));
        assertEquals(KompileService.CHAT, KompileServiceEndpoints.serviceForPath(
                "https://example.test/api/agents/chat"));
        assertEquals(KompileService.CRAWL, KompileServiceEndpoints.serviceForPath("api/unified-crawl"));
        assertEquals(KompileService.ADMIN, KompileServiceEndpoints.serviceForPath("http://localhost:8080"));
    }

    // ── Resolution ladder ───────────────────────────────────────────────────

    @Test
    void resolve_explicitUrlWinsOverEverythingAndIsNormalized() {
        pin(KompileService.CHAT, "http://property:1111");

        Resolution resolution = KompileServiceEndpoints.resolve(KompileService.CHAT, "http://explicit:2222//");

        assertEquals(Source.EXPLICIT, resolution.source());
        assertEquals("http://explicit:2222", resolution.baseUrl());
        assertEquals(2222, resolution.port());
    }

    @Test
    void resolve_systemPropertyOutranksConfigAndDefault() {
        pin(KompileService.CRAWL, "  http://pinned:9100/ ");

        Resolution resolution = KompileServiceEndpoints.resolve(KompileService.CRAWL);

        assertEquals(Source.SYSTEM_PROPERTY, resolution.source());
        assertEquals("http://pinned:9100", resolution.baseUrl());
        assertEquals(KompileService.CRAWL, resolution.service());
    }

    @Test
    void resolve_blankOverridesAreIgnored() {
        pin(KompileService.CHAT, "   ");

        // A blank -D is an unset knob, not a request to talk to the empty string.
        assertFalse(KompileServiceEndpoints.resolve(KompileService.CHAT).baseUrl().isBlank());
        assertEquals(Source.EXPLICIT, KompileServiceEndpoints.resolve(KompileService.CHAT, " x ").source());
        assertEquals("x", KompileServiceEndpoints.resolve(KompileService.CHAT, " x ").baseUrl());
    }

    @Test
    void resolveForPath_routesToTheOwningServiceButHonoursAnExplicitUrl() {
        pin(KompileService.CRAWL, "http://crawlbox:8082");
        pin(KompileService.CHAT, "http://chatbox:8081");

        assertEquals("http://crawlbox:8082",
                KompileServiceEndpoints.baseUrlForPath("/api/unified-crawl/jobs", null));
        assertEquals("http://chatbox:8081",
                KompileServiceEndpoints.baseUrlForPath("/api/agents/chat/health", null));

        // An explicit --url short-circuits routing: the caller named a server, so a crawl call
        // still goes there even though the path belongs to another persona. This is what keeps
        // --url working against an all-in-one deployment.
        Resolution pinned = KompileServiceEndpoints.resolveForPath("/api/unified-crawl/jobs", "http://one-box:8080");
        assertEquals(Source.EXPLICIT, pinned.source());
        assertEquals("http://one-box:8080", pinned.baseUrl());
        assertEquals(KompileService.CRAWL, pinned.service());
    }

    @Test
    void urlForPath_joinsBaseAndPath() {
        pin(KompileService.CRAWL, "http://crawlbox:8082/");

        assertEquals("http://crawlbox:8082/api/unified-crawl/jobs",
                KompileServiceEndpoints.urlForPath("/api/unified-crawl/jobs", null));
        assertEquals("http://crawlbox:8082/api/unified-crawl/jobs",
                KompileServiceEndpoints.urlForPath("api/unified-crawl/jobs", null));
        // The query string is stripped for routing but must survive into the request URL: most
        // CLI call sites pass their parameters inline, so dropping it here would silently swap
        // every ?limit=/?hours= for the server's default.
        assertEquals("http://crawlbox:8082/api/unified-crawl/jobs?active=true",
                KompileServiceEndpoints.urlForPath("/api/unified-crawl/jobs?active=true", null));
        assertEquals("http://crawlbox:8082/api/indexing/history/recent?hours=24",
                KompileServiceEndpoints.urlForPath(
                        "http://ignored:1/api/indexing/history/recent?hours=24", null));
    }

    @Test
    void allBaseUrls_dedupesAnAllInOneDeployment() {
        pin(KompileService.ADMIN, "http://one-box:8080");
        pin(KompileService.CHAT, "http://one-box:8080");
        pin(KompileService.CRAWL, "http://one-box:8080");

        assertEquals(List.of("http://one-box:8080"), KompileServiceEndpoints.allBaseUrls());

        pin(KompileService.CRAWL, "http://other-box:8082");
        List<String> split = KompileServiceEndpoints.allBaseUrls();
        assertEquals(2, split.size());
        // Admin first, so an all-in-one install still lands on the console before the personas.
        assertEquals("http://one-box:8080", split.get(0));
    }

    @Test
    void resolutionPort_fallsBackByThenSchemeThenServiceDefault() {
        assertEquals(8081, new Resolution(KompileService.CHAT, "http://h:8081", Source.DEFAULT).port());
        assertEquals(443, new Resolution(KompileService.CHAT, "https://h", Source.DEFAULT).port());
        assertEquals(80, new Resolution(KompileService.CHAT, "http://h", Source.DEFAULT).port());
        // Unparseable or scheme-less values fall back to the built-in port rather than throwing —
        // the callers that need a port are local health checks, which must not be able to crash
        // a launch on a malformed override.
        assertEquals(8082, new Resolution(KompileService.CRAWL, "not a url", Source.DEFAULT).port());
        assertEquals(8080, new Resolution(KompileService.ADMIN, "", Source.DEFAULT).port());
    }

    // ── Service identity ────────────────────────────────────────────────────

    @Test
    void services_haveDistinctPortsAndStableIdentifiers() {
        assertEquals(3, KompileService.values().length);
        assertEquals(Set.of(8080, 8081, 8082), Set.of(
                KompileService.ADMIN.defaultPort(),
                KompileService.CHAT.defaultPort(),
                KompileService.CRAWL.defaultPort()));

        assertEquals("adminUrl", KompileService.ADMIN.configKey());
        assertEquals("chatUrl", KompileService.CHAT.configKey());
        assertEquals("crawlUrl", KompileService.CRAWL.configKey());

        assertEquals("http://localhost:8081", KompileService.CHAT.defaultUrl());
        assertEquals("kompile-app-crawl-manager", KompileService.CRAWL.componentId());
    }

    @Test
    void fromId_acceptsShortIdsAndComponentIds() {
        assertEquals(KompileService.CHAT, KompileService.fromId("chat"));
        assertEquals(KompileService.CHAT, KompileService.fromId("  CHAT "));
        assertEquals(KompileService.CRAWL, KompileService.fromId("kompile-app-crawl-manager"));
        assertEquals(KompileService.ADMIN, KompileService.fromId("kompile-app-main"));
        assertNull(KompileService.fromId("staging"));
        assertNull(KompileService.fromId(null));
        assertNull(KompileService.fromId(""));
    }

    // ── Managed config (the MANAGED_CONFIG rung's file format) ──────────────

    @Test
    void configManager_readsPerServiceUrlsAndRouteOverrides(@TempDir Path tmpDir) throws Exception {
        Path file = tmpDir.resolve("service-endpoints.json");
        Files.writeString(file, """
                {
                  "adminUrl": "http://admin-box:8080/",
                  "chatUrl": "http://chat-box:8081",
                  "routes": { "/api/graph/aggregate": "crawl", "/api/bogus": "nosuchservice" }
                }
                """);

        ServiceEndpointsConfig config = new ServiceEndpointsConfigManager(file).current();

        assertEquals("http://admin-box:8080", config.url(KompileService.ADMIN));
        assertEquals("http://chat-box:8081", config.url(KompileService.CHAT));
        // Absent stays absent: null is what lets the resolver fall through to the instance
        // registry instead of pinning crawl to a value nobody configured.
        assertNull(config.url(KompileService.CRAWL));

        assertEquals(KompileService.CRAWL, config.routes().get("/api/graph/aggregate"));
        assertFalse(config.routes().containsKey("/api/bogus"), "unknown service ids must be dropped");
    }

    @Test
    void configManager_rendersEveryServiceAndTheFullRouteTable(@TempDir Path tmpDir) {
        Map<String, Object> view = new ServiceEndpointsConfigManager(tmpDir.resolve("absent.json"))
                .currentAsMap();

        // The display/REST view fills in effective values even when the file is missing, so the
        // settings panel always shows all three services rather than three blanks.
        for (KompileService service : KompileService.values()) {
            Object url = view.get(service.configKey());
            assertNotNull(url, service.configKey() + " must be shown");
            assertTrue(String.valueOf(url).startsWith("http"), service.configKey() + " = " + url);
        }

        @SuppressWarnings("unchecked")
        Map<String, String> routes = (Map<String, String>) view.get("routes");
        assertNotNull(routes);
        assertEquals("crawl", routes.get("/api/unified-crawl"));
        assertEquals("chat", routes.get("/api/agents"));
        assertEquals("admin", routes.get("/api/agents/api-config"));
    }
}
