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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.services.diffindex;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiffIndexServiceSortTest {

    private DiffIndexService service;
    private Map<String, DiffIndexEntry> entries;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws ReflectiveOperationException {
        service = new DiffIndexService();
        Field entriesField = DiffIndexService.class.getDeclaredField("entries");
        entriesField.setAccessible(true);
        entries = (Map<String, DiffIndexEntry>) entriesField.get(service);
        entries.clear();

        put("e1", "codex", "codex", "/projects/B", "src/Zeta.java",
                "2026-01-01T00:00:00Z", 1, 9);
        put("e2", "claude-code", "claude", "/projects/A", "src/Alpha.java",
                "2026-02-01T00:00:00Z", 5, 1);
        put("e3", "aider", "aider", "/projects/C", "src/Middle.java",
                "2026-03-01T00:00:00Z", 3, 4);
    }

    @Test
    void legacyOverloadRemainsNewestFirst() {
        assertIds(service.search(null, null, null, null, null, null, null, 50),
                "e3", "e2", "e1");
    }

    @Test
    void sortsTimestampInEitherDirection() {
        assertIds(search("timestamp", "asc"), "e1", "e2", "e3");
        assertIds(search("timestamp", "desc"), "e3", "e2", "e1");
    }

    @Test
    void sortsTextFieldsCaseInsensitively() {
        assertIds(search("file_path", "asc"), "e2", "e3", "e1");
        assertIds(search("project", "desc"), "e3", "e1", "e2");
        assertIds(search("agent", "asc"), "e3", "e2", "e1");
        assertIds(search("source", "desc"), "e1", "e2", "e3");
    }

    @Test
    void sortsLineCountsAndTotalChanges() {
        assertIds(search("lines_added", "desc"), "e2", "e3", "e1");
        assertIds(search("lines_removed", "asc"), "e2", "e3", "e1");
        assertIds(search("total_changes", "asc"), "e2", "e3", "e1");
    }

    @Test
    void supportsDocumentedAliasesAndTolerantBackendDefaults() {
        assertIds(search("path", "asc"), "e2", "e3", "e1");
        assertIds(search("changes", "desc"), "e1", "e3", "e2");
        assertIds(search("not-a-field", "not-a-direction"), "e3", "e2", "e1");
    }

    @Test
    void missingValuesRemainLastInBothDirections() {
        put("missing", null, null, null, null, null, 100, 100);

        List<DiffIndexEntry> ascending = search("timestamp", "asc");
        List<DiffIndexEntry> descending = search("timestamp", "desc");

        assertEquals("missing", ascending.get(ascending.size() - 1).getId());
        assertEquals("missing", descending.get(descending.size() - 1).getId());
    }

    private List<DiffIndexEntry> search(String sortBy, String sortDir) {
        return service.search(null, null, null, null, null, null, null,
                50, sortBy, sortDir);
    }

    private static void assertIds(List<DiffIndexEntry> entries, String... ids) {
        assertEquals(List.of(ids), entries.stream().map(DiffIndexEntry::getId).toList());
    }

    private void put(String id, String agent, String source, String project,
                     String filePath, String timestamp, long added, long removed) {
        entries.put(id, DiffIndexEntry.builder()
                .id(id)
                .agent(agent)
                .source(source)
                .projectDirectory(project)
                .filePath(filePath)
                .toolName("edit")
                .diffType("edit")
                .timestamp(timestamp)
                .linesAdded(added)
                .linesRemoved(removed)
                .build());
    }
}
