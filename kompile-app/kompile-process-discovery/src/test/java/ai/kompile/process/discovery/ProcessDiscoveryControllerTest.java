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
}
