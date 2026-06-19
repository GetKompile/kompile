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

package ai.kompile.app.services.difftracker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link DiffTrackerService}.
 *
 * Diffs are persisted to ~/.kompile/agent-state/diffs/<sessionId>/; tests
 * use unique session IDs based on nanotime to avoid conflicts.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DiffTrackerServiceTest {

    private DiffTrackerService service;
    private String sessionId;

    @BeforeEach
    void setUp() throws Exception {
        service = new DiffTrackerService();
        service.init();
        sessionId = "test-session-" + System.nanoTime();
    }

    // ===== record() =====

    @Test
    void record_returnsNonNullDiff() {
        DiffRecord rec = service.record(
                sessionId, "/src/Foo.java", "old", "new", null, "agent-1", "task-1", "desc");
        assertThat(rec).isNotNull();
        assertThat(rec.getId()).isGreaterThan(0);
    }

    @Test
    void record_setsFilePath() {
        DiffRecord rec = service.record(
                sessionId, "/src/Bar.java", "before", "after", null, null, null, null);
        assertThat(rec.getFilePath()).isEqualTo("/src/Bar.java");
    }

    @Test
    void record_setsSessionId() {
        DiffRecord rec = service.record(
                sessionId, "/foo", "a", "b", null, null, null, null);
        assertThat(rec.getSessionId()).isEqualTo(sessionId);
    }

    @Test
    void record_computesDiff_whenUnifiedDiffNull() {
        DiffRecord rec = service.record(
                sessionId, "/file.txt", "line1\nline2", "line1\nline3", null, null, null, null);
        assertThat(rec.getUnifiedDiff()).isNotNull().isNotBlank();
    }

    @Test
    void record_usesProvidedUnifiedDiff() {
        DiffRecord rec = service.record(
                sessionId, "/file.txt", "a", "b", "+newline\n-oldline", null, null, null);
        assertThat(rec.getUnifiedDiff()).isEqualTo("+newline\n-oldline");
    }

    @Test
    void record_countsLinesAdded() {
        DiffRecord rec = service.record(
                sessionId, "/f.txt", "old", "new\nextra", null, null, null, null);
        assertThat(rec.getLinesAdded()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void record_countsLinesRemoved() {
        DiffRecord rec = service.record(
                sessionId, "/f.txt", "old\nextra", "new", null, null, null, null);
        assertThat(rec.getLinesRemoved()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void record_setsTimestamp() {
        DiffRecord rec = service.record(
                sessionId, "/f.txt", "a", "b", null, null, null, null);
        assertThat(rec.getTimestamp()).isNotNull().isNotBlank();
    }

    @Test
    void record_withNullSession_usesGlobalSession() {
        DiffRecord rec = service.record(
                null, "/f.txt", "a", "b", null, "agent", null, null);
        assertThat(rec.getSessionId()).isEqualTo("_global");
    }

    // ===== get() =====

    @Test
    void get_returnsExistingRecord() {
        DiffRecord created = service.record(
                sessionId, "/foo.java", "x", "y", null, null, null, null);
        DiffRecord found = service.get(created.getId());
        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(created.getId());
    }

    @Test
    void get_returnsNull_forNonExistentId() {
        assertThat(service.get(Long.MAX_VALUE)).isNull();
    }

    // ===== listForSession() =====

    @Test
    void listForSession_returnsRecordsForSession() {
        service.record(sessionId, "/a.java", "x", "y", null, null, null, null);
        service.record(sessionId, "/b.java", "x", "y", null, null, null, null);

        List<DiffRecord> records = service.listForSession(sessionId, null, null, null, null);
        assertThat(records).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void listForSession_returnsEmpty_forUnknownSession() {
        List<DiffRecord> records =
                service.listForSession("nonexistent-session-xyz", null, null, null, null);
        assertThat(records).isEmpty();
    }

    @Test
    void listForSession_filtersByFilePath() {
        service.record(sessionId, "/match.java", "x", "y", null, null, null, null);
        service.record(sessionId, "/other.java", "x", "y", null, null, null, null);

        List<DiffRecord> records =
                service.listForSession(sessionId, "match", null, null, null);
        assertThat(records).allMatch(r -> r.getFilePath().contains("match"));
    }

    @Test
    void listForSession_respectsLimit() {
        for (int i = 0; i < 10; i++) {
            service.record(sessionId, "/f" + i + ".java", "x", "y", null, null, null, null);
        }
        List<DiffRecord> records =
                service.listForSession(sessionId, null, null, null, 3);
        assertThat(records).hasSizeLessThanOrEqualTo(3);
    }

    // ===== listAll() =====

    @Test
    void listAll_includesRecordsFromAllSessions() {
        String session2 = "s2-" + System.nanoTime();
        service.record(sessionId, "/a.java", "x", "y", null, null, null, null);
        service.record(session2, "/b.java", "x", "y", null, null, null, null);

        List<DiffRecord> all = service.listAll(null, null, null, 100);
        assertThat(all).hasSizeGreaterThanOrEqualTo(2);
    }

    // ===== listByFile() =====

    @Test
    void listByFile_returnsMatchingRecords() {
        service.record(sessionId, "/specific-file.java", "a", "b", null, null, null, null);
        List<DiffRecord> found =
                service.listByFile("/specific-file.java", sessionId);
        assertThat(found).isNotEmpty();
        assertThat(found).allMatch(r -> "/specific-file.java".equals(r.getFilePath()));
    }

    // ===== summary() =====

    @Test
    void summary_containsExpectedKeys() {
        service.record(sessionId, "/f.java", "a", "b", null, "agent", null, null);
        Map<String, Object> summary = service.summary(sessionId);

        assertThat(summary).containsKeys(
                "totalDiffs", "sessionCount", "fileCount", "byFile",
                "byAgent", "bySession", "totalLinesAdded", "totalLinesRemoved"
        );
    }

    @Test
    void summary_totalDiffs_isPositive() {
        service.record(sessionId, "/f.java", "x", "y", null, null, null, null);
        Map<String, Object> summary = service.summary(sessionId);
        assertThat((int) summary.get("totalDiffs")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void summary_nullSession_returnsAllDiffs() {
        Map<String, Object> summary = service.summary(null);
        assertThat(summary).containsKey("totalDiffs");
    }

    // ===== delete() =====

    @Test
    void delete_removesRecord() {
        DiffRecord rec = service.record(
                sessionId, "/del.java", "a", "b", null, null, null, null);
        boolean deleted = service.delete(rec.getId());
        assertThat(deleted).isTrue();
        assertThat(service.get(rec.getId())).isNull();
    }

    @Test
    void delete_returnsFalse_forNonExistent() {
        assertThat(service.delete(Long.MAX_VALUE)).isFalse();
    }

    // ===== clearSession() =====

    @Test
    void clearSession_removesAllSessionRecords() {
        service.record(sessionId, "/a.java", "x", "y", null, null, null, null);
        service.record(sessionId, "/b.java", "x", "y", null, null, null, null);

        int cleared = service.clearSession(sessionId);
        assertThat(cleared).isGreaterThanOrEqualTo(2);

        List<DiffRecord> remaining =
                service.listForSession(sessionId, null, null, null, null);
        assertThat(remaining).isEmpty();
    }

    @Test
    void clearSession_returnsZero_forUnknownSession() {
        assertThat(service.clearSession("no-such-session-" + System.nanoTime())).isEqualTo(0);
    }

    // ===== listSessions() =====

    @Test
    void listSessions_includesKnownSession() {
        service.record(sessionId, "/f.java", "a", "b", null, null, null, null);
        assertThat(service.listSessions()).contains(sessionId);
    }
}
