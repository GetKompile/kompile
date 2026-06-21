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

package ai.kompile.app.services.diffindex;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@code since}/{@code until} time filtering added to
 * {@link DiffIndexService#search}. The service's constructor does not touch disk
 * (loading happens in a {@code @PostConstruct}), so entries are injected directly
 * into the in-memory map via reflection.
 */
class DiffIndexServiceTimeFilterTest {

    private DiffIndexService service;
    private Map<String, DiffIndexEntry> entries;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new DiffIndexService();
        entries = (Map<String, DiffIndexEntry>) ReflectionTestUtils.getField(service, "entries");
        entries.clear();
        put("e1", "claude-code", "/proj/A/src/Foo.java", "2026-01-01T00:00:00Z");
        put("e2", "codex", "/proj/A/src/Foo.java", "2026-02-01T00:00:00Z");
        put("e3", "claude-code", "/proj/A/src/Bar.java", "2026-03-01T00:00:00Z");
    }

    @Test
    void noBoundsReturnsAllNewestFirst() {
        List<DiffIndexEntry> r = service.search(null, null, null, null, null, null, null, 50);
        assertThat(r).extracting(DiffIndexEntry::getId).containsExactly("e3", "e2", "e1");
    }

    @Test
    void sinceIsInclusiveLowerBound() {
        List<DiffIndexEntry> r = service.search(null, null, null, null, null, "2026-02-01T00:00:00Z", null, 50);
        assertThat(r).extracting(DiffIndexEntry::getId).containsExactlyInAnyOrder("e2", "e3");
    }

    @Test
    void untilIsInclusiveUpperBound() {
        List<DiffIndexEntry> r = service.search(null, null, null, null, null, null, "2026-02-01T00:00:00Z", 50);
        assertThat(r).extracting(DiffIndexEntry::getId).containsExactlyInAnyOrder("e1", "e2");
    }

    @Test
    void sinceAndUntilNarrowToWindow() {
        List<DiffIndexEntry> r = service.search(
                null, null, null, null, null, "2026-01-15T00:00:00Z", "2026-02-15T00:00:00Z", 50);
        assertThat(r).extracting(DiffIndexEntry::getId).containsExactly("e2");
    }

    @Test
    void acceptsDatetimeLocalBoundWithoutZone() {
        // The HTML datetime-local control sends "2026-02-01T00:00" (no seconds/zone).
        List<DiffIndexEntry> r = service.search(null, null, null, null, null, "2026-02-01T00:00", null, 50);
        assertThat(r).extracting(DiffIndexEntry::getId).containsExactlyInAnyOrder("e2", "e3");
    }

    @Test
    void combinesFileAndTimeFilters() {
        List<DiffIndexEntry> r = service.search(
                null, null, "Foo.java", null, null, "2026-01-15T00:00:00Z", null, 50);
        // Foo.java matches e1 & e2; the since bound drops e1.
        assertThat(r).extracting(DiffIndexEntry::getId).containsExactly("e2");
    }

    @Test
    void unparseableTimestampsArePreservedNotDropped() {
        put("e4", "aider", "/proj/A/src/Baz.java", "not-a-real-timestamp");
        List<DiffIndexEntry> r = service.search(null, null, null, null, null, "2026-02-01T00:00:00Z", null, 50);
        assertThat(r).extracting(DiffIndexEntry::getId).contains("e4");
    }

    @Test
    void filtersFilePathByGlobForFilesOfConcern() {
        put("env", "claude-code", "/proj/A/.env", "2026-01-05T00:00:00Z");
        put("pem", "claude-code", "/proj/A/secrets/key.pem", "2026-01-06T00:00:00Z");

        // ** spans directories; concrete tail is anchored so a.environment is NOT matched.
        assertThat(service.search(null, null, "**/*.env", null, null, null, null, 50))
                .extracting(DiffIndexEntry::getId).containsExactly("env");
        assertThat(service.search(null, null, "*.pem", null, null, null, null, 50))
                .extracting(DiffIndexEntry::getId).containsExactly("pem");
        assertThat(service.search(null, null, "secrets/**", null, null, null, null, 50))
                .extracting(DiffIndexEntry::getId).containsExactly("pem");
        // A plain (non-glob) filter remains a substring match.
        assertThat(service.search(null, null, "Foo.java", null, null, null, null, 50))
                .extracting(DiffIndexEntry::getId).containsExactlyInAnyOrder("e1", "e2");
    }

    private void put(String id, String agent, String filePath, String timestamp) {
        entries.put(id, DiffIndexEntry.builder()
                .id(id)
                .agent(agent)
                .source("transcript")
                .filePath(filePath)
                .toolName("Edit")
                .diffType("edit")
                .unifiedDiff("--- a/" + filePath + "\n+++ b/" + filePath + "\n+x")
                .timestamp(timestamp)
                .linesAdded(1)
                .linesRemoved(0)
                .build());
    }
}
