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

package ai.kompile.crawl.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests for the job-id alias-resolution seam added to fix
 * "no SSE events in UI" (the scheduler-assigned alias "crawl-55ef8175"
 * was never mapped to the internal UUID that progress events carry).
 *
 * <p>Exercises {@link UnifiedCrawlGraphServiceImpl#registerJobIdAlias} and
 * {@link UnifiedCrawlGraphServiceImpl#resolveJobId} directly —
 * no Spring context, no mocks, no disk.  Both methods touch only the
 * {@code jobIdAliases} {@link java.util.concurrent.ConcurrentHashMap}
 * which is initialised inline, so a plain {@code new} construction is safe.
 *
 * <p>Framework: JUnit 5 (same as every other test in this module).</p>
 */
@DisplayName("Job-ID alias resolution (SSE fix)")
class JobIdAliasResolutionTest {

    // Construct directly — the @PostConstruct executor init is never called without Spring,
    // and registerJobIdAlias/resolveJobId operate only on the inline-init ConcurrentHashMap.
    private UnifiedCrawlGraphServiceImpl svc() {
        return new UnifiedCrawlGraphServiceImpl();
    }

    // ── registerJobIdAlias + resolveJobId happy path ──────────────────────────

    @Test
    @DisplayName("Registered alias resolves to the internal UUID")
    void registeredAlias_resolvesToInternalUuid() {
        UnifiedCrawlGraphServiceImpl service = svc();
        service.registerJobIdAlias("crawl-abc", "uuid-1234-5678");
        assertEquals("uuid-1234-5678", service.resolveJobId("crawl-abc"),
                "A registered alias must map to the provided internal UUID");
    }

    @Test
    @DisplayName("Internal UUID (not an alias) passes through unchanged")
    void internalId_passesThroughUnchanged() {
        UnifiedCrawlGraphServiceImpl service = svc();
        // No alias registered for "uuid-1234-5678"; ConcurrentHashMap.getOrDefault returns the key.
        assertEquals("uuid-1234-5678", service.resolveJobId("uuid-1234-5678"),
                "An unregistered ID must be returned as-is (identity pass-through)");
    }

    @Test
    @DisplayName("Unknown alias returns input unchanged — never null for non-null input")
    void unknownAlias_returnsInputUnchanged() {
        UnifiedCrawlGraphServiceImpl service = svc();
        assertEquals("some-unknown-id", service.resolveJobId("some-unknown-id"),
                "An unknown key must be returned unchanged, never null");
    }

    // ── null / empty input contract ──────────────────────────────────────────

    @Test
    @DisplayName("resolveJobId(null) returns null — explicit null check in impl")
    void nullInput_returnsNull() {
        UnifiedCrawlGraphServiceImpl service = svc();
        // impl: if (aliasOrInternalId == null) return null;
        assertNull(service.resolveJobId(null),
                "null input must produce null, not NPE");
    }

    // ── registerJobIdAlias null-safety ────────────────────────────────────────

    @Test
    @DisplayName("registerJobIdAlias with null alias is a no-op — subsequent resolveJobId passes through")
    void registerWithNullAlias_isNoOpAndPassesThrough() {
        UnifiedCrawlGraphServiceImpl service = svc();
        service.registerJobIdAlias(null, "uuid-9999");
        // null alias → nothing stored; "uuid-9999" has no alias entry → passes through
        assertEquals("uuid-9999", service.resolveJobId("uuid-9999"));
    }

    @Test
    @DisplayName("registerJobIdAlias with null internalJobId is a no-op — alias not stored")
    void registerWithNullInternalId_isNoOp_aliasResolvesAsPassThrough() {
        UnifiedCrawlGraphServiceImpl service = svc();
        service.registerJobIdAlias("crawl-xyz", null);
        // null internalJobId → nothing stored; the alias key passes through as itself
        assertEquals("crawl-xyz", service.resolveJobId("crawl-xyz"),
                "Alias with null target must not be stored — key returns as pass-through");
    }

    // ── alias overwrite (ConcurrentHashMap.put replaces) ────────────────────

    @Test
    @DisplayName("Re-registering the same alias overwrites the previous target UUID")
    void registerSameAlias_twice_lastWriteWins() {
        UnifiedCrawlGraphServiceImpl service = svc();
        service.registerJobIdAlias("crawl-abc", "uuid-first");
        service.registerJobIdAlias("crawl-abc", "uuid-second");
        assertEquals("uuid-second", service.resolveJobId("crawl-abc"),
                "ConcurrentHashMap.put overwrites — last registration must win");
    }

    // ── multiple independent aliases ─────────────────────────────────────────

    @Test
    @DisplayName("Multiple distinct aliases resolve to their respective UUIDs independently")
    void multipleAliases_resolveIndependently() {
        UnifiedCrawlGraphServiceImpl service = svc();
        service.registerJobIdAlias("crawl-A", "uuid-A");
        service.registerJobIdAlias("crawl-B", "uuid-B");

        assertEquals("uuid-A", service.resolveJobId("crawl-A"));
        assertEquals("uuid-B", service.resolveJobId("crawl-B"));
        // An unregistered alias still passes through untouched
        assertEquals("crawl-C", service.resolveJobId("crawl-C"),
                "An alias not in the map must still return itself, not null or another alias's value");
    }
}
