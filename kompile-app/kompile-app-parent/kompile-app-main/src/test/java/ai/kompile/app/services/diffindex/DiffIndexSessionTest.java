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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the per-session aggregation ({@link DiffIndexService#listSessions}) and
 * per-session lookup ({@link DiffIndexService#sessionEntries}) that back the
 * "Sessions" diff-browsing view. Entries are injected directly into the in-memory
 * map (the constructor does not load from disk).
 */
class DiffIndexSessionTest {

    private DiffIndexService service;
    private Map<String, DiffIndexEntry> entries;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new DiffIndexService();
        entries = (Map<String, DiffIndexEntry>) ReflectionTestUtils.getField(service, "entries");
        entries.clear();

        // Session A: claude-code edits Foo.java twice + Bar.java, then aider touches Foo.java.
        put("a1", "sess-A", "claude-code", "/proj/A/Foo.java", "2026-01-01T00:00:00Z", 3, 1);
        put("a2", "sess-A", "claude-code", "/proj/A/Foo.java", "2026-01-02T00:00:00Z", 1, 0);
        put("a3", "sess-A", "claude-code", "/proj/A/Bar.java", "2026-01-03T00:00:00Z", 5, 2);
        put("a4", "sess-A", "aider", "/proj/A/Foo.java", "2026-01-04T00:00:00Z", 1, 1);
        // Session B: a single codex edit, more recent than session A.
        put("b1", "sess-B", "codex", "/proj/B/Baz.java", "2026-02-01T00:00:00Z", 2, 0);
    }

    @Test
    void listSessionsAggregatesPerSessionNewestActiveFirst() {
        List<Map<String, Object>> sessions = service.listSessions();
        assertThat(sessions).hasSize(2);
        // Sorted by last activity, newest first → sess-B (Feb) before sess-A (Jan).
        assertThat(sessions.get(0).get("sessionId")).isEqualTo("sess-B");

        Map<String, Object> a = byId(sessions, "sess-A");
        assertThat(a.get("entryCount")).isEqualTo(4);
        assertThat(a.get("fileCount")).isEqualTo(2L);          // Foo.java + Bar.java
        assertThat(a.get("totalLinesAdded")).isEqualTo(10L);    // 3+1+5+1
        assertThat(a.get("totalLinesRemoved")).isEqualTo(4L);   // 1+0+2+1
        assertThat(a.get("firstTimestamp")).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(a.get("lastTimestamp")).isEqualTo("2026-01-04T00:00:00Z");
        // Primary agent = whoever made the most recent edit.
        assertThat(a.get("agent")).isEqualTo("aider");

        @SuppressWarnings("unchecked")
        Set<String> agents = (Set<String>) a.get("agents");
        assertThat(agents).containsExactlyInAnyOrder("claude-code", "aider");
    }

    @Test
    void sessionEntriesReturnsOnlyThatSessionNewestFirst() {
        List<DiffIndexEntry> a = service.sessionEntries("sess-A");
        assertThat(a).extracting(DiffIndexEntry::getId).containsExactly("a4", "a3", "a2", "a1");

        List<DiffIndexEntry> b = service.sessionEntries("sess-B");
        assertThat(b).extracting(DiffIndexEntry::getId).containsExactly("b1");
    }

    @Test
    void sessionEntriesForUnknownOrBlankIsEmpty() {
        assertThat(service.sessionEntries("does-not-exist")).isEmpty();
        assertThat(service.sessionEntries("")).isEmpty();
        assertThat(service.sessionEntries(null)).isEmpty();
    }

    private static Map<String, Object> byId(List<Map<String, Object>> sessions, String id) {
        return sessions.stream()
                .filter(s -> id.equals(s.get("sessionId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No session: " + id));
    }

    private void put(String id, String sessionId, String agent, String filePath,
                     String timestamp, long added, long removed) {
        entries.put(id, DiffIndexEntry.builder()
                .id(id)
                .sessionId(sessionId)
                .sessionFingerprint(sessionId + "-fp")
                .agent(agent)
                .source("transcript")
                .projectDirectory(filePath.substring(0, filePath.lastIndexOf('/')))
                .filePath(filePath)
                .toolName("Edit")
                .diffType("edit")
                .unifiedDiff("--- a/" + filePath + "\n+++ b/" + filePath + "\n+x")
                .timestamp(timestamp)
                .linesAdded(added)
                .linesRemoved(removed)
                .build());
    }
}
