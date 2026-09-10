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

package ai.kompile.app.chat.boundary;

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
 * {@code kompile-app-chat} serves the chat persona: conversation, project browsing, and read-only
 * fact-sheet and graph views.
 *
 * <p>There is no Spring Security in {@code kompile-app-parent}, so anything this process mounts is
 * reachable by anyone who can reach {@code :8081}. That makes the classpath the whole of the access
 * control story, and it is why the forbidden assertions below matter more than the required ones: a
 * missing chat API is a visibly broken app, while a stray admin API is an invisible hole.</p>
 */
@DisplayName("kompile-app-chat mounts the chat surface only")
class ChatPersonaBoundaryTest {

    private static SortedSet<String> mounted;

    /** A sample spanning the chat app's own web module, wide enough to catch its removal. */
    private static final Set<String> REQUIRED_CHAT = Set.of(
            "/api/agents/chat",
            "/api/chat",
            "/api/rag",
            "/api/graph-rag",
            "/api/skills",
            "/api/system-prompts",
            "/api/tool-calls");

    /**
     * Graph viewing is an end-user surface, so {@code kompile-app-web-graph} is on this app's
     * classpath. Asserted separately from the chat families because it arrives from a different
     * module and would go missing for a different reason.
     */
    private static final Set<String> REQUIRED_GRAPH = Set.of("/api/graph");

    @BeforeAll
    static void scan() {
        mounted = PersonaApiSurface.mountedBasePaths();
    }

    @Test
    @DisplayName("mounts the chat surface")
    void mountsChatSurface() {
        SortedSet<String> missing = new TreeSet<>(REQUIRED_CHAT);
        missing.removeAll(PersonaApiSurface.matching(mounted, REQUIRED_CHAT));
        assertTrue(missing.isEmpty(),
                "kompile-app-web-chat is not contributing these families: " + missing);
    }

    @Test
    @DisplayName("mounts read-only graph viewing")
    void mountsGraphSurface() {
        SortedSet<String> missing = new TreeSet<>(REQUIRED_GRAPH);
        missing.removeAll(PersonaApiSurface.matching(mounted, REQUIRED_GRAPH));
        assertTrue(missing.isEmpty(),
                "kompile-app-web-graph is not contributing these families: " + missing);
    }

    @Test
    @DisplayName("mounts the shared surface, including project browsing")
    void mountsSharedSurface() {
        SortedSet<String> missing = new TreeSet<>(PersonaSurfaces.SHARED);
        missing.removeAll(PersonaApiSurface.matching(mounted, PersonaSurfaces.SHARED));
        assertTrue(missing.isEmpty(),
                "kompile-app-web-shared is not contributing these families: " + missing);
    }

    @Test
    @DisplayName("classifies every API family it mounts")
    void classifiesEverythingItMounts() {
        Set<String> classified = new TreeSet<>();
        classified.addAll(PersonaSurfaces.CHAT);
        classified.addAll(PersonaSurfaces.GRAPH);
        classified.addAll(PersonaSurfaces.SHARED);
        classified.addAll(PersonaSurfaces.LIBRARY_UNSCOPED);
        classified.addAll(PersonaSurfaces.LIBRARY_OVERLAPS);

        SortedSet<String> unclassified = new TreeSet<>();
        for (String path : mounted) {
            if (path.startsWith("/api/") && !classified.contains(path)) {
                unclassified.add(path);
            }
        }
        assertTrue(unclassified.isEmpty(),
                "The chat app mounts API families that PersonaSurfaces does not classify: "
                        + unclassified);
    }

    @Test
    @DisplayName("does not mount the admin console's API")
    void doesNotMountAdminSurface() {
        SortedSet<String> leaked = PersonaApiSurface.matching(mounted, PersonaSurfaces.ADMIN);
        leaked.removeAll(PersonaSurfaces.LIBRARY_OVERLAPS);
        assertTrue(leaked.isEmpty(),
                "The chat app is serving admin APIs on :8081 with no authentication in front of "
                        + "them. kompile-app-web-admin has entered its classpath: " + leaked);
    }

    @Test
    @DisplayName("does not mount the crawl manager's API")
    void doesNotMountCrawlSurface() {
        SortedSet<String> leaked = PersonaApiSurface.matching(mounted, PersonaSurfaces.CRAWL);
        leaked.removeAll(PersonaSurfaces.LIBRARY_OVERLAPS);
        assertTrue(leaked.isEmpty(),
                "The chat app is serving crawl-manager APIs. kompile-app-web-crawl has entered "
                        + "its classpath: " + leaked);
    }
}
