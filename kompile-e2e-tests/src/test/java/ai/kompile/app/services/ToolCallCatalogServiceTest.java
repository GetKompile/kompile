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

package ai.kompile.app.services;

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
 * Unit tests for {@link ToolCallCatalogService}.
 *
 * The service reads from ~/.kompile/conversations/tool-calls/all-tool-calls.jsonl.
 * Tests cover search/filter/stats logic that works even when the file is absent.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ToolCallCatalogServiceTest {

    private ToolCallCatalogService service;

    @BeforeEach
    void setUp() {
        service = new ToolCallCatalogService();
    }

    // ===== search =====

    @Test
    void search_returnsPagedResult_withExpectedKeys() {
        Map<String, Object> result = service.search(null, null, null, null, null, null, 0, 20);
        assertThat(result).containsKeys("results", "totalCount", "page", "pageSize", "totalPages");
    }

    @Test
    void search_page0_startAtBeginning() {
        Map<String, Object> result = service.search(null, null, null, null, null, null, 0, 10);
        assertThat(result.get("page")).isEqualTo(0);
        assertThat(result.get("pageSize")).isEqualTo(10);
    }

    @Test
    void search_filterByToolName_returnsSubset() {
        Map<String, Object> result = service.search(null, "NonExistentToolXYZ", null, null, null, null, 0, 50);
        @SuppressWarnings("unchecked")
        List<?> results = (List<?>) result.get("results");
        assertThat(results).isEmpty();
        assertThat(result.get("totalCount")).isEqualTo(0);
    }

    @Test
    void search_withSortAndDir_doesNotThrow() {
        assertThatCode(() ->
            service.search(null, null, null, null, null, null, null, "toolName", "asc", 0, 10)
        ).doesNotThrowAnyException();
    }

    @Test
    void search_withProjectFilter_doesNotThrow() {
        assertThatCode(() ->
            service.search(null, null, null, null, null, null, "/some/project", "timestamp", "desc", 0, 10)
        ).doesNotThrowAnyException();
    }

    @Test
    void search_totalPagesCalculatedCorrectly_whenEmpty() {
        Map<String, Object> result = service.search(null, "NoSuchTool", null, null, null, null, 0, 10);
        assertThat(result.get("totalPages")).isEqualTo(0);
    }

    // ===== groupBy =====

    @Test
    void groupBy_containsExpectedKeys() {
        Map<String, Object> result = service.groupBy("category", null, null, null, null, null, null, null, "timestamp", "desc", 10);
        assertThat(result).containsKeys("groups", "groupCounts", "groupField", "totalGroups", "totalCount");
    }

    @Test
    void groupBy_nullGroupField_defaultsToCategory() {
        Map<String, Object> result = service.groupBy(null, null, null, null, null, null, null, null, null, null, 0);
        assertThat(result.get("groupField")).isEqualTo("category");
    }

    @Test
    void groupBy_differentFields_doesNotThrow() {
        for (String field : List.of("project", "agent", "tool", "source", "session")) {
            assertThatCode(() ->
                service.groupBy(field, null, null, null, null, null, null, null, null, null, 5)
            ).doesNotThrowAnyException();
        }
    }

    // ===== getStats =====

    @Test
    void getStats_containsExpectedKeys() {
        Map<String, Object> stats = service.getStats();
        assertThat(stats).containsKeys(
                "totalToolCalls", "byTool", "byCategory", "byAgent", "bySource", "byProject",
                "sessionCount", "totalErrors"
        );
    }

    @Test
    void getStats_totalToolCalls_isNonNegative() {
        Map<String, Object> stats = service.getStats();
        int total = (int) stats.get("totalToolCalls");
        assertThat(total).isGreaterThanOrEqualTo(0);
    }

    // ===== getFilterOptions =====

    @Test
    void getFilterOptions_containsExpectedKeys() {
        Map<String, Object> options = service.getFilterOptions();
        assertThat(options).containsKeys("toolNames", "categories", "agents", "sources", "sessions", "projects");
    }

    // ===== getById =====

    @Test
    void getById_returnsNull_whenNotFound() {
        assertThat(service.getById("nonexistent-id-xyz")).isNull();
    }

    // ===== ToolCallEntry =====

    @Test
    void toolCallEntry_defaultValues() {
        ToolCallCatalogService.ToolCallEntry entry = new ToolCallCatalogService.ToolCallEntry();
        assertThat(entry.id).isNull();
        assertThat(entry.isError).isFalse();
        assertThat(entry.durationMs).isEqualTo(0L);
    }
}
