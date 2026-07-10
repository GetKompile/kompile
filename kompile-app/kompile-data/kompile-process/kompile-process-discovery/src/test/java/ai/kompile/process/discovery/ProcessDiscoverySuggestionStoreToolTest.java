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

package ai.kompile.process.discovery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the new {@code process_discovery_list_suggestions} and
 * {@code process_discovery_get_suggestion} @Tool methods added to {@link ProcessDiscoveryTool}.
 *
 * <p>Uses a real {@link ProcessSuggestionStore} backed by a temp directory so the
 * store behavior is exercised without mocking, and a mocked {@link ProcessDiscoveryService}
 * for the pre-existing tool methods (not exercised here but required by the constructor).
 */
@ExtendWith(MockitoExtension.class)
class ProcessDiscoverySuggestionStoreToolTest {

    @Mock
    private ProcessDiscoveryService discoveryService;

    @TempDir
    Path tempDir;

    private ProcessDiscoveryTool tool;
    private ProcessSuggestionStore store;

    @BeforeEach
    void setUp() throws Exception {
        store = new ProcessSuggestionStore(tempDir);
        tool = new ProcessDiscoveryTool(discoveryService);
        // Inject the suggestion store via reflection (it's @Autowired(required=false))
        var field = ProcessDiscoveryTool.class.getDeclaredField("suggestionStore");
        field.setAccessible(true);
        field.set(tool, store);
    }

    // ── process_discovery_list_suggestions ──────────────────────────────────────

    @Test
    void listSuggestions_returnsAllPendingByDefault() {
        store.save(makeSuggestion("s1", "Invoice Flow", 1L, false, null));
        store.save(makeSuggestion("s2", "Budget Process", 1L, false, null));
        store.save(makeSuggestion("s3", "Old Process", 1L, false, "s2")); // superseded

        Map<String, Object> result = tool.listSuggestions(
                new ProcessDiscoveryTool.ListSuggestionsInput(null, false));

        assertEquals("success", result.get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> suggestions = (List<Map<String, Object>>) result.get("suggestions");
        // s3 is superseded → excluded by default
        assertEquals(2, suggestions.size());
    }

    @Test
    void listSuggestions_includeSuperseded() {
        store.save(makeSuggestion("s1", "Invoice Flow", 1L, false, null));
        store.save(makeSuggestion("s3", "Old Process", 1L, false, "s1")); // superseded

        Map<String, Object> result = tool.listSuggestions(
                new ProcessDiscoveryTool.ListSuggestionsInput(null, true));

        assertEquals("success", result.get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> suggestions = (List<Map<String, Object>>) result.get("suggestions");
        assertEquals(2, suggestions.size());
    }

    @Test
    void listSuggestions_scopesByFactSheet() {
        store.save(makeSuggestion("s1", "Fact Sheet 1 Process", 1L, false, null));
        store.save(makeSuggestion("s2", "Fact Sheet 2 Process", 2L, false, null));

        Map<String, Object> result = tool.listSuggestions(
                new ProcessDiscoveryTool.ListSuggestionsInput(1L, false));

        assertEquals("success", result.get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> suggestions = (List<Map<String, Object>>) result.get("suggestions");
        assertEquals(1, suggestions.size());
        assertEquals("s1", suggestions.get(0).get("id"));
        assertEquals("Fact Sheet 1 Process", suggestions.get(0).get("name"));
    }

    @Test
    void listSuggestions_stateFieldReflectsAccepted() {
        store.save(makeSuggestion("s1", "Accepted Process", 1L, true, null));
        store.save(makeSuggestion("s2", "Pending Process", 1L, false, null));

        Map<String, Object> result = tool.listSuggestions(
                new ProcessDiscoveryTool.ListSuggestionsInput(null, false));

        assertEquals("success", result.get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> suggestions = (List<Map<String, Object>>) result.get("suggestions");
        Map<String, Object> accepted = suggestions.stream()
                .filter(s -> "s1".equals(s.get("id"))).findFirst().orElseThrow();
        assertEquals("ACCEPTED", accepted.get("state"));
        Map<String, Object> pending = suggestions.stream()
                .filter(s -> "s2".equals(s.get("id"))).findFirst().orElseThrow();
        assertEquals("PENDING", pending.get("state"));
    }

    @Test
    void listSuggestions_noStoreReturnsError() throws Exception {
        var field = ProcessDiscoveryTool.class.getDeclaredField("suggestionStore");
        field.setAccessible(true);
        field.set(tool, null);

        Map<String, Object> result = tool.listSuggestions(
                new ProcessDiscoveryTool.ListSuggestionsInput(null, false));
        assertEquals("error", result.get("status"));
    }

    // ── process_discovery_get_suggestion ────────────────────────────────────────

    @Test
    void getSuggestion_returnsFullDetail() {
        ProcessSuggestion s = ProcessSuggestion.builder()
                .id("full-1")
                .name("Full Invoice Process")
                .description("Complete invoice approval workflow")
                .factSheetId(5L)
                .discoverySource("MINING")
                .confidence(0.92)
                .processKey("proc-invoice")
                .discoveredAt(Instant.parse("2026-07-09T08:00:00Z"))
                .narrative("Invoices are submitted via the procurement portal...")
                .narrativeSource("TEMPLATE")
                .lineageRef(ProcessSuggestion.ProcessLineage.builder()
                        .derivationMethod("INDUCTIVE_MINER")
                        .softTruthValue(0.91)
                        .basisNodeIds(List.of("n1", "n2"))
                        .build())
                .reasoningTraceId("trace-abc")
                .structuredEvidence(List.of(
                        ProcessSuggestion.StructuredEvidence.builder()
                                .type("DRIFT")
                                .description("Activity ordering changed after 2026-01-01")
                                .score(0.75)
                                .build(),
                        ProcessSuggestion.StructuredEvidence.builder()
                                .type("CONFLICT")
                                .description("Conflicting order: Approve before Review vs Review before Approve")
                                .score(0.4)
                                .build(),
                        ProcessSuggestion.StructuredEvidence.builder()
                                .type("CAUSAL")
                                .description("Submit → Review (χ²=12.3)")
                                .score(0.89)
                                .build()
                ))
                .build();
        store.save(s);

        Map<String, Object> result = tool.getSuggestion(
                new ProcessDiscoveryTool.GetSuggestionInput("full-1"));

        assertEquals("success", result.get("status"));
        assertEquals("full-1", result.get("id"));
        assertEquals("Full Invoice Process", result.get("name"));
        assertEquals("Complete invoice approval workflow", result.get("description"));
        assertEquals(5L, result.get("factSheetId"));
        assertEquals("PENDING", result.get("state"));
        assertEquals(0.92, result.get("confidence"));
        assertEquals("proc-invoice", result.get("processKey"));
        assertEquals("Invoices are submitted via the procurement portal...", result.get("narrative"));
        assertEquals("trace-abc", result.get("reasoningTraceId"));
        assertEquals(3, result.get("totalEvidenceItems"));

        // Drift evidence
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> driftReport = (List<Map<String, Object>>) result.get("driftReport");
        assertNotNull(driftReport, "driftReport should be present");
        assertEquals(1, driftReport.size());
        assertEquals("DRIFT", driftReport.get(0).get("type"));

        // Conflict evidence
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conflictReport = (List<Map<String, Object>>) result.get("conflictReport");
        assertNotNull(conflictReport, "conflictReport should be present");
        assertEquals(1, conflictReport.size());
        assertEquals("CONFLICT", conflictReport.get(0).get("type"));

        // Other evidence (CAUSAL)
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> otherEvidence = (List<Map<String, Object>>) result.get("otherEvidence");
        assertNotNull(otherEvidence, "otherEvidence should be present");
        assertEquals(1, otherEvidence.size());

        // Lineage
        @SuppressWarnings("unchecked")
        Map<String, Object> lineage = (Map<String, Object>) result.get("lineage");
        assertNotNull(lineage, "lineage should be present");
        assertEquals("INDUCTIVE_MINER", lineage.get("derivationMethod"));
        assertEquals(0.91, lineage.get("softTruthValue"));
        assertEquals(2, lineage.get("basisNodeCount"));
    }

    @Test
    void getSuggestion_notFoundReturnsNotFound() {
        Map<String, Object> result = tool.getSuggestion(
                new ProcessDiscoveryTool.GetSuggestionInput("nonexistent-id"));
        assertEquals("not_found", result.get("status"));
    }

    @Test
    void getSuggestion_blankIdReturnsError() {
        Map<String, Object> result = tool.getSuggestion(
                new ProcessDiscoveryTool.GetSuggestionInput(""));
        assertEquals("error", result.get("status"));
    }

    @Test
    void getSuggestion_nullIdReturnsError() {
        Map<String, Object> result = tool.getSuggestion(
                new ProcessDiscoveryTool.GetSuggestionInput(null));
        assertEquals("error", result.get("status"));
    }

    @Test
    void getSuggestion_noStoreReturnsError() throws Exception {
        var field = ProcessDiscoveryTool.class.getDeclaredField("suggestionStore");
        field.setAccessible(true);
        field.set(tool, null);

        Map<String, Object> result = tool.getSuggestion(
                new ProcessDiscoveryTool.GetSuggestionInput("s1"));
        assertEquals("error", result.get("status"));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private ProcessSuggestion makeSuggestion(String id, String name, Long factSheetId,
                                              boolean accepted, String supersededBy) {
        return ProcessSuggestion.builder()
                .id(id)
                .name(name)
                .factSheetId(factSheetId)
                .confidence(0.8)
                .discoverySource("MINING")
                .discoveredAt(Instant.now())
                .accepted(accepted)
                .supersededBySuggestionId(supersededBy)
                .build();
    }
}
