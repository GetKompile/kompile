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

package ai.kompile.app.boundary;

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
 * {@code kompile-app-main} is the admin console. It mounts the shared surface plus the admin
 * surface, and nothing else.
 *
 * <p>This is the test the comment in {@code pom.xml} points at. What it fences is narrow and
 * specific: the pom deliberately omits {@code kompile-app-web-chat}, {@code -web-crawl} and
 * {@code -web-graph}, and since every controller in the repo lives under
 * {@code ai.kompile.app.web.controllers}, that omission — not {@code scanBasePackages} — is the only
 * thing keeping chat and crawl APIs off {@code :8080}. Re-adding one of those modules for a
 * convenience import is a one-line pom edit that silently re-exposes an entire persona's API to the
 * admin port, and it is exactly the mistake this test refuses to let pass review.</p>
 */
@DisplayName("kompile-app-main mounts the admin surface only")
class MainPersonaBoundaryTest {

    private static SortedSet<String> mounted;

    /**
     * Paths the admin console legitimately mounts that sit underneath another persona's base path.
     * Each is a genuinely admin-owned sub-resource, not a leak: the parents ({@code /api/rag},
     * {@code /api/agents}) stay on the chat app.
     */
    private static final Set<String> NESTED_ADMIN_PATHS = Set.of(
            "/api/rag/test",
            "/api/agents/api-config",
            "/api/agents/cli-config");

    /**
     * A sample of admin families spread across the console's subsystems. Not exhaustive — the point
     * is to fail loudly if {@code kompile-app-web-admin} ever drops off the classpath, which would
     * otherwise show up as a merely empty admin UI.
     */
    private static final Set<String> REQUIRED_ADMIN = Set.of(
            "/api/nd4j/environment",
            "/api/eval-debugger",
            "/api/subprocess-events",
            "/api/enforcer",
            "/api/mcp",
            "/api/scheduler",
            "/api/tool-permissions",
            "/api/vlm");

    @BeforeAll
    static void scan() {
        mounted = PersonaApiSurface.mountedBasePaths();
    }

    @Test
    @DisplayName("mounts the admin surface")
    void mountsAdminSurface() {
        SortedSet<String> missing = new TreeSet<>(REQUIRED_ADMIN);
        missing.removeAll(PersonaApiSurface.matching(mounted, REQUIRED_ADMIN));
        assertTrue(missing.isEmpty(),
                "kompile-app-web-admin is not contributing these families to the admin console: " + missing);
    }

    @Test
    @DisplayName("mounts the shared surface every persona needs")
    void mountsSharedSurface() {
        SortedSet<String> missing = new TreeSet<>(PersonaSurfaces.SHARED);
        missing.removeAll(PersonaApiSurface.matching(mounted, PersonaSurfaces.SHARED));
        assertTrue(missing.isEmpty(),
                "kompile-app-web-shared is not contributing these families: " + missing);
    }

    @Test
    @DisplayName("does not mount the chat app's API")
    void doesNotMountChatSurface() {
        SortedSet<String> leaked = PersonaApiSurface.matching(mounted, PersonaSurfaces.CHAT);
        leaked.removeAll(NESTED_ADMIN_PATHS);
        leaked.removeAll(PersonaSurfaces.LIBRARY_OVERLAPS);
        assertTrue(leaked.isEmpty(),
                "The admin console is serving chat APIs. kompile-app-web-chat has re-entered its "
                        + "classpath: " + leaked);
    }

    /**
     * Every {@code /api} family the admin console mounts has to be classified somewhere, because the
     * other two personas fence themselves with {@link PersonaSurfaces#ADMIN} — a path missing from
     * it is a path their forbidden assertions cannot see. {@code /api/rag-pipelines} was exactly
     * that: mounted only on {@code :8080}, but listed nowhere, so pulling
     * {@code kompile-rag-pipeline} onto the chat classpath would have exposed pipeline create and
     * delete on {@code :8081} with every test still green.
     *
     * <p>This is the "extra mappings" half of the boundary contract. The required assertions above
     * are deliberately samples; this one is not, and it is the reason a new admin controller cannot
     * be added without saying which persona owns its path.</p>
     *
     * <p>Membership is <b>exact</b>, not prefix-based, and that is the whole value of the check.
     * Under prefix matching a listed family would silently adopt anything mounted beneath it, so a
     * new admin-only {@code /api/process/secrets} would be waved through by {@code /api/process}
     * already being classified as library-unscoped — the {@code /api/rag-pipelines} hole reproduced
     * one segment deeper. Exact matching costs one entry per endpoint family and buys a decision per
     * endpoint family.</p>
     */
    @Test
    @DisplayName("classifies every API family it mounts")
    void classifiesEverythingItMounts() {
        Set<String> classified = new TreeSet<>();
        classified.addAll(PersonaSurfaces.ADMIN);
        classified.addAll(PersonaSurfaces.SHARED);
        classified.addAll(PersonaSurfaces.LIBRARY_UNSCOPED);
        classified.addAll(PersonaSurfaces.LIBRARY_OVERLAPS);
        classified.addAll(NESTED_ADMIN_PATHS);

        SortedSet<String> unclassified = new TreeSet<>();
        for (String path : mounted) {
            if (path.startsWith("/api/") && !classified.contains(path)) {
                unclassified.add(path);
            }
        }
        assertTrue(unclassified.isEmpty(),
                "The admin console mounts API families that PersonaSurfaces does not classify, so no "
                        + "persona test can fence them. Add each to PersonaSurfaces.ADMIN (reachable on "
                        + ":8080 only), .SHARED (a surface all three personas are meant to have), or "
                        + ".LIBRARY_UNSCOPED (a library module puts it wherever that library is "
                        + "depended on): " + unclassified);
    }

    @Test
    @DisplayName("does not mount the crawl manager's API")
    void doesNotMountCrawlSurface() {
        SortedSet<String> leaked = PersonaApiSurface.matching(mounted, PersonaSurfaces.CRAWL);
        leaked.removeAll(PersonaSurfaces.LIBRARY_OVERLAPS);
        assertTrue(leaked.isEmpty(),
                "The admin console is serving crawl-manager APIs. kompile-app-web-crawl has "
                        + "re-entered its classpath: " + leaked);
    }
}
