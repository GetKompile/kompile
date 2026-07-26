/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.crawlmanager.boundary;

import ai.kompile.app.web.boundary.PersonaApiSurface;
import ai.kompile.app.web.boundary.PersonaSurfaces;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code kompile-app-crawl-manager} serves the crawl persona: running crawls, fact sheets,
 * documents and the index browser, note sync, and graph viewing.
 *
 * <p>This app has the widest end-user surface of the three, which makes it the likeliest place for
 * an admin dependency to be added "just for one endpoint" — model warm-up, subprocess events and
 * GPU lifecycle all look adjacent to running a crawl. They are not: they are how the operator
 * diagnoses the machine, and they belong to the admin console. The crawl UI links across to
 * {@code :8080} for them instead of mounting them here.</p>
 */
@DisplayName("kompile-app-crawl-manager mounts the crawl surface only")
class CrawlManagerPersonaBoundaryTest {

    private static SortedSet<String> mounted;

    /** A sample spanning the crawl app's own web module, wide enough to catch its removal. */
    private static final Set<String> REQUIRED_CRAWL = Set.of(
            "/api/unified-crawl",
            "/api/crawlers",
            "/api/crawl-events",
            "/api/ingest/events",
            "/api/indexing/history",
            "/api/chunk-manager",
            "/api/cross-index",
            "/api/graph-extraction",
            "/api/schedules");

    /** Graph viewing is an end-user surface, so {@code kompile-app-web-graph} is on the classpath. */
    private static final Set<String> REQUIRED_GRAPH = Set.of("/api/graph");

    @BeforeAll
    static void scan() {
        mounted = PersonaApiSurface.mountedBasePaths();
    }

    @Test
    @DisplayName("mounts the crawl surface")
    void mountsCrawlSurface() {
        SortedSet<String> missing = new TreeSet<>(REQUIRED_CRAWL);
        missing.removeAll(PersonaApiSurface.matching(mounted, REQUIRED_CRAWL));
        assertTrue(missing.isEmpty(),
                "kompile-app-web-crawl is not contributing these families: " + missing);
    }

    @Test
    @DisplayName("mounts graph viewing")
    void mountsGraphSurface() {
        SortedSet<String> missing = new TreeSet<>(REQUIRED_GRAPH);
        missing.removeAll(PersonaApiSurface.matching(mounted, REQUIRED_GRAPH));
        assertTrue(missing.isEmpty(),
                "kompile-app-web-graph is not contributing these families: " + missing);
    }

    @Test
    @DisplayName("mounts the shared surface, including fact sheets and the index browser")
    void mountsSharedSurface() {
        SortedSet<String> missing = new TreeSet<>(PersonaSurfaces.SHARED);
        missing.removeAll(PersonaApiSurface.matching(mounted, PersonaSurfaces.SHARED));
        assertTrue(missing.isEmpty(),
                "kompile-app-web-shared is not contributing these families: " + missing);
    }

    @Test
    @DisplayName("does not mount the admin console's API")
    void doesNotMountAdminSurface() {
        SortedSet<String> leaked = PersonaApiSurface.matching(mounted, PersonaSurfaces.ADMIN);
        leaked.removeAll(PersonaSurfaces.LIBRARY_OVERLAPS);
        assertTrue(leaked.isEmpty(),
                "The crawl manager is serving admin APIs on :8082 with no authentication in front "
                        + "of them. kompile-app-web-admin has entered its classpath: " + leaked);
    }

    @Test
    @DisplayName("does not mount the chat app's API")
    void doesNotMountChatSurface() {
        SortedSet<String> leaked = PersonaApiSurface.matching(mounted, PersonaSurfaces.CHAT);
        leaked.removeAll(PersonaSurfaces.LIBRARY_OVERLAPS);
        assertTrue(leaked.isEmpty(),
                "The crawl manager is serving chat APIs. kompile-app-web-chat has entered its "
                        + "classpath: " + leaked);
    }
}
