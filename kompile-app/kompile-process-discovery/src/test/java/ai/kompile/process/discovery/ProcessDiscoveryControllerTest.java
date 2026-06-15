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

import ai.kompile.process.workflow.ProcessDefinition;
import ai.kompile.process.workflow.ProcessPhase;
import ai.kompile.process.workflow.ProcessStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ProcessDiscoveryController} REST endpoints.
 */
@ExtendWith(MockitoExtension.class)
class ProcessDiscoveryControllerTest {

    @Mock
    private ProcessDiscoveryService discoveryService;

    private ProcessDiscoveryController controller;

    @BeforeEach
    void setUp() {
        controller = new ProcessDiscoveryController(discoveryService);
    }

    // ─── /suggest ────────────────────────────────────────────────────────

    @Test
    void suggestProcesses_returnsSuggestionsWithCount() {
        ProcessSuggestion suggestion = ProcessSuggestion.builder()
                .name("Recurring email flow")
                .confidence(0.8)
                .discoverySource("EMAIL_FLOW")
                .phases(List.of())
                .build();
        when(discoveryService.discoverProcesses(anyList(), anyMap()))
                .thenReturn(List.of(suggestion));

        Map<String, Object> request = new HashMap<>();
        request.put("graphNodeIds", List.of("node-1", "node-2"));
        request.put("options", Map.of("minConfidence", 0.5));

        ResponseEntity<Map<String, Object>> response = controller.suggestProcesses(request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().get("count"));
        @SuppressWarnings("unchecked")
        List<ProcessSuggestion> suggestions = (List<ProcessSuggestion>) response.getBody().get("suggestions");
        assertEquals("Recurring email flow", suggestions.get(0).getName());
    }

    @Test
    void suggestProcesses_emptyListWhenNoMatches() {
        when(discoveryService.discoverProcesses(any(), anyMap()))
                .thenReturn(List.of());

        Map<String, Object> request = new HashMap<>();
        ResponseEntity<Map<String, Object>> response = controller.suggestProcesses(request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(0, response.getBody().get("count"));
    }

    @Test
    void suggestProcesses_handlesNullGraphNodeIds() {
        when(discoveryService.discoverProcesses(isNull(), anyMap()))
                .thenReturn(List.of());

        Map<String, Object> request = new HashMap<>();
        // graphNodeIds is absent — should pass null to service
        ResponseEntity<Map<String, Object>> response = controller.suggestProcesses(request);

        assertEquals(200, response.getStatusCode().value());
        verify(discoveryService).discoverProcesses(isNull(), anyMap());
    }

    // ─── /email-flows ────────────────────────────────────────────────────

    @Test
    void analyzeEmailFlows_returnsPatterns() {
        FlowPattern pattern = FlowPattern.builder()
                .type("EMAIL_FLOW")
                .description("Alice → Bob recurring")
                .occurrenceCount(5)
                .confidence(0.8)
                .steps(List.of())
                .involvedNodeIds(List.of("person-1", "person-2"))
                .build();
        when(discoveryService.analyzeEmailFlows(anyList()))
                .thenReturn(List.of(pattern));

        Map<String, Object> request = Map.of("graphNodeIds", List.of("n1"));
        ResponseEntity<Map<String, Object>> response = controller.analyzeEmailFlows(request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().get("count"));
    }

    @Test
    void analyzeEmailFlows_handlesNullBody() {
        when(discoveryService.analyzeEmailFlows(isNull()))
                .thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = controller.analyzeEmailFlows(null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(0, response.getBody().get("count"));
    }

    // ─── /excel-flows ────────────────────────────────────────────────────

    @Test
    void analyzeExcelFlows_returnsPatterns() {
        FlowPattern pattern = FlowPattern.builder()
                .type("EXCEL_COMPUTATION")
                .description("Budget worksheet: 3 formulas")
                .occurrenceCount(1)
                .confidence(0.75)
                .steps(List.of())
                .involvedNodeIds(List.of("cell-1"))
                .build();
        when(discoveryService.analyzeExcelFlows(any()))
                .thenReturn(List.of(pattern));

        ResponseEntity<Map<String, Object>> response = controller.analyzeExcelFlows(
                Map.of("graphNodeIds", List.of("sheet-node")));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().get("count"));
    }

    @Test
    void analyzeExcelFlows_handlesNullBody() {
        when(discoveryService.analyzeExcelFlows(isNull()))
                .thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = controller.analyzeExcelFlows(null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(0, response.getBody().get("count"));
    }

    // ─── /document-flows ──────────────────────────────────────────────────

    @Test
    void analyzeDocumentFlows_returnsPatterns() {
        FlowPattern pattern = FlowPattern.builder()
                .type("DOCUMENT_AUTHORING")
                .description("Author pipeline: alice@test.com — 4 documents")
                .occurrenceCount(4)
                .confidence(0.72)
                .steps(List.of())
                .involvedNodeIds(List.of("doc-1", "doc-2"))
                .build();
        when(discoveryService.analyzeDocumentFlows(anyList()))
                .thenReturn(List.of(pattern));

        Map<String, Object> request = Map.of("graphNodeIds", List.of("n1", "n2"));
        ResponseEntity<Map<String, Object>> response = controller.analyzeDocumentFlows(request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().get("count"));
        @SuppressWarnings("unchecked")
        List<FlowPattern> patterns = (List<FlowPattern>) response.getBody().get("patterns");
        assertEquals("DOCUMENT_AUTHORING", patterns.get(0).getType());
    }

    @Test
    void analyzeDocumentFlows_handlesNullBody() {
        when(discoveryService.analyzeDocumentFlows(isNull()))
                .thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = controller.analyzeDocumentFlows(null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(0, response.getBody().get("count"));
    }

    @Test
    void analyzeDocumentFlows_handlesEmptyResult() {
        when(discoveryService.analyzeDocumentFlows(any()))
                .thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = controller.analyzeDocumentFlows(
                Map.of("graphNodeIds", List.of("some-node")));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(0, response.getBody().get("count"));
        @SuppressWarnings("unchecked")
        List<FlowPattern> patterns = (List<FlowPattern>) response.getBody().get("patterns");
        assertTrue(patterns.isEmpty());
    }

    // ─── /accept ─────────────────────────────────────────────────────────

    @Test
    void acceptSuggestion_returnsProcessDefinition() {
        ProcessSuggestion suggestion = ProcessSuggestion.builder()
                .name("Suggested: email flow")
                .confidence(0.85)
                .discoverySource("EMAIL_FLOW")
                .phases(List.of())
                .build();

        ProcessDefinition definition = ProcessDefinition.builder()
                .id("discovered-abc123")
                .name("Suggested: email flow")
                .version(1)
                .status(ProcessStatus.DRAFT)
                .phases(List.of(ProcessPhase.builder()
                        .id("phase-1").name("email flow").order(1).steps(List.of())
                        .build()))
                .build();

        when(discoveryService.acceptSuggestion(any(ProcessSuggestion.class)))
                .thenReturn(definition);

        ResponseEntity<ProcessDefinition> response = controller.acceptSuggestion(suggestion);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("discovered-abc123", response.getBody().getId());
        assertEquals(ProcessStatus.DRAFT, response.getBody().getStatus());
        verify(discoveryService).acceptSuggestion(suggestion);
    }

    // ─── Suggestion Store Endpoints ──────────────────────────────────────────

    /**
     * Build a controller with a real ProcessSuggestionStore injected via the setter.
     */
    private ProcessDiscoveryController controllerWithStore(ProcessSuggestionStore store) {
        ProcessDiscoveryController c = new ProcessDiscoveryController(discoveryService);
        c.setSuggestionStore(store);
        return c;
    }

    private ProcessSuggestion buildTestSuggestion(String id, String name) {
        return ProcessSuggestion.builder()
                .id(id)
                .name(name)
                .confidence(0.9)
                .discoverySource("EMAIL_FLOW")
                .phases(List.of())
                .bayesianPosteriors(new LinkedHashMap<>(Map.of("isRelevant(node_1)", 0.87)))
                .structuredEvidence(List.of(
                        ProcessSuggestion.StructuredEvidence.builder()
                                .type("BAYESIAN")
                                .description("High posterior probability")
                                .score(0.87)
                                .build()))
                .build();
    }

    @Test
    void listSuggestions_withNullStore_returnsEmptyResult() {
        // controller has no store injected (null)
        ProcessDiscoveryController c = new ProcessDiscoveryController(discoveryService);

        ResponseEntity<Map<String, Object>> response = c.listSuggestions(null, false);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(0, response.getBody().get("count"));
        @SuppressWarnings("unchecked")
        List<?> suggestions = (List<?>) response.getBody().get("suggestions");
        assertTrue(suggestions.isEmpty());
    }

    @Test
    void listSuggestions_returnsAllStoredSuggestions() {
        ProcessSuggestionStore store = new ProcessSuggestionStore();
        store.save(buildTestSuggestion("s-list-1", "Flow A"));
        store.save(buildTestSuggestion("s-list-2", "Flow B"));

        ProcessDiscoveryController c = controllerWithStore(store);
        ResponseEntity<Map<String, Object>> response = c.listSuggestions(null, false);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(2, response.getBody().get("count"));
        @SuppressWarnings("unchecked")
        List<?> suggestions = (List<?>) response.getBody().get("suggestions");
        assertEquals(2, suggestions.size());

        store.delete("s-list-1");
        store.delete("s-list-2");
    }

    @Test
    void listSuggestions_byFactSheet_returnsOnlyMatchingSuggestions() {
        ProcessSuggestionStore store = new ProcessSuggestionStore();
        ProcessSuggestion s1 = buildTestSuggestion("s-fs-42", "Flow FS42");
        s1.setFactSheetId(42L);
        ProcessSuggestion s2 = buildTestSuggestion("s-fs-99", "Flow FS99");
        s2.setFactSheetId(99L);
        store.save(s1);
        store.save(s2);

        ProcessDiscoveryController c = controllerWithStore(store);
        ResponseEntity<Map<String, Object>> response = c.listSuggestions(42L, false);

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        List<ProcessSuggestion> suggestions = (List<ProcessSuggestion>) response.getBody().get("suggestions");
        assertEquals(1, suggestions.size());
        assertEquals("s-fs-42", suggestions.get(0).getId());

        store.delete("s-fs-42");
        store.delete("s-fs-99");
    }

    @Test
    void listSuggestions_pendingOnly_excludesAccepted() {
        ProcessSuggestionStore store = new ProcessSuggestionStore();
        store.save(buildTestSuggestion("s-pending-1", "Pending Flow"));
        store.save(buildTestSuggestion("s-accepted-1", "Accepted Flow"));
        store.markAccepted("s-accepted-1", "proc-def-999");

        ProcessDiscoveryController c = controllerWithStore(store);
        ResponseEntity<Map<String, Object>> response = c.listSuggestions(null, true);

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        List<ProcessSuggestion> suggestions = (List<ProcessSuggestion>) response.getBody().get("suggestions");
        assertTrue(suggestions.stream().anyMatch(s -> "s-pending-1".equals(s.getId())),
                "Pending suggestion must be in pendingOnly results");
        assertTrue(suggestions.stream().noneMatch(s -> "s-accepted-1".equals(s.getId())),
                "Accepted suggestion must not be in pendingOnly results");

        store.delete("s-pending-1");
        store.delete("s-accepted-1");
    }

    @Test
    void getSuggestion_withNullStore_returns404() {
        ProcessDiscoveryController c = new ProcessDiscoveryController(discoveryService);

        ResponseEntity<ProcessSuggestion> response = c.getSuggestion("any-id");

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void getSuggestion_returnsCorrectSuggestion() {
        ProcessSuggestionStore store = new ProcessSuggestionStore();
        store.save(buildTestSuggestion("s-get-me", "Get Test Flow"));

        ProcessDiscoveryController c = controllerWithStore(store);
        ResponseEntity<ProcessSuggestion> response = c.getSuggestion("s-get-me");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("s-get-me", response.getBody().getId());
        assertEquals("Get Test Flow", response.getBody().getName());

        store.delete("s-get-me");
    }

    @Test
    void getSuggestion_notFound_returns404() {
        ProcessSuggestionStore store = new ProcessSuggestionStore();
        ProcessDiscoveryController c = controllerWithStore(store);

        ResponseEntity<ProcessSuggestion> response = c.getSuggestion("nonexistent-id");

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void getSuggestion_includesBayesianPosteriorsAndStructuredEvidence() {
        ProcessSuggestionStore store = new ProcessSuggestionStore();
        store.save(buildTestSuggestion("s-bayes-check", "Bayesian Flow"));

        ProcessDiscoveryController c = controllerWithStore(store);
        ResponseEntity<ProcessSuggestion> response = c.getSuggestion("s-bayes-check");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody().getBayesianPosteriors());
        assertFalse(response.getBody().getBayesianPosteriors().isEmpty(),
                "Bayesian posteriors must be returned");
        assertNotNull(response.getBody().getStructuredEvidence());
        assertFalse(response.getBody().getStructuredEvidence().isEmpty(),
                "Structured evidence must be returned");

        store.delete("s-bayes-check");
    }

    @Test
    void acceptStoredSuggestion_withNullStore_returns404() {
        ProcessDiscoveryController c = new ProcessDiscoveryController(discoveryService);

        ResponseEntity<ProcessDefinition> response = c.acceptStoredSuggestion("any-id");

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void acceptStoredSuggestion_notFound_returns404() {
        ProcessSuggestionStore store = new ProcessSuggestionStore();
        ProcessDiscoveryController c = controllerWithStore(store);

        ResponseEntity<ProcessDefinition> response = c.acceptStoredSuggestion("nonexistent-id");

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void acceptStoredSuggestion_convertsToProcessDefinitionAndMarksAccepted() {
        ProcessSuggestionStore store = new ProcessSuggestionStore();
        store.save(buildTestSuggestion("s-accept-stored-1", "Stored Flow"));

        ProcessDefinition definition = ProcessDefinition.builder()
                .id("pd-from-stored-1")
                .name("Stored Flow")
                .version(1)
                .status(ProcessStatus.DRAFT)
                .phases(List.of())
                .build();

        when(discoveryService.acceptSuggestion(any(ProcessSuggestion.class)))
                .thenReturn(definition);

        ProcessDiscoveryController c = controllerWithStore(store);
        ResponseEntity<ProcessDefinition> response = c.acceptStoredSuggestion("s-accept-stored-1");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("pd-from-stored-1", response.getBody().getId());
        assertEquals(ProcessStatus.DRAFT, response.getBody().getStatus());

        // Verify the suggestion is now marked accepted in the store
        ProcessSuggestion updated = store.get("s-accept-stored-1").orElse(null);
        assertNotNull(updated, "Suggestion must still exist in store after accept");
        assertTrue(Boolean.TRUE.equals(updated.getAccepted()),
                "Suggestion must be marked as accepted");
        assertEquals("pd-from-stored-1", updated.getAcceptedProcessDefinitionId());

        store.delete("s-accept-stored-1");
    }

    @Test
    void deleteSuggestion_withNullStore_returns404() {
        ProcessDiscoveryController c = new ProcessDiscoveryController(discoveryService);

        ResponseEntity<Void> response = c.deleteSuggestion("any-id");

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void deleteSuggestion_removesFromStore() {
        ProcessSuggestionStore store = new ProcessSuggestionStore();
        store.save(buildTestSuggestion("s-delete-me-1", "Delete Me Flow"));

        assertTrue(store.get("s-delete-me-1").isPresent(), "Suggestion must exist before delete");

        ProcessDiscoveryController c = controllerWithStore(store);
        ResponseEntity<Void> response = c.deleteSuggestion("s-delete-me-1");

        assertEquals(204, response.getStatusCode().value());
        assertFalse(store.get("s-delete-me-1").isPresent(),
                "Suggestion must be removed from store after delete");
    }

    @Test
    void deleteSuggestion_nonExistentId_returnsNoContent() {
        // Per the controller: delete() on the store is always called; store silently ignores
        // unknown IDs. The controller always returns 204 after calling store.delete().
        ProcessSuggestionStore store = new ProcessSuggestionStore();
        ProcessDiscoveryController c = controllerWithStore(store);

        ResponseEntity<Void> response = c.deleteSuggestion("nonexistent-id");

        assertEquals(204, response.getStatusCode().value());
    }
}
