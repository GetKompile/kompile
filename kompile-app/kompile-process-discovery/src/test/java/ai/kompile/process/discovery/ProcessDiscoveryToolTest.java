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
import ai.kompile.process.workflow.ProcessStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ProcessDiscoveryTool} — verifies all 5 LLM-accessible tool methods
 * delegate correctly to {@link ProcessDiscoveryService} and handle errors gracefully.
 */
@ExtendWith(MockitoExtension.class)
class ProcessDiscoveryToolTest {

    @Mock
    private ProcessDiscoveryService discoveryService;

    private ProcessDiscoveryTool tool;

    @BeforeEach
    void setUp() {
        tool = new ProcessDiscoveryTool(discoveryService);
    }

    // ─── discoverProcesses ───────────────────────────────────────────────

    @Test
    void discoverProcesses_returnsSuggestions() {
        ProcessSuggestion.SuggestedStep step = ProcessSuggestion.SuggestedStep.builder()
                .name("Send report")
                .stepType("HUMAN")
                .description("Analyst sends monthly report to manager")
                .suggestedAssignee("analyst@example.com")
                .build();

        ProcessSuggestion.SuggestedPhase phase = ProcessSuggestion.SuggestedPhase.builder()
                .name("Report Distribution")
                .description("Distribute monthly report to stakeholders")
                .steps(List.of(step))
                .build();

        ProcessSuggestion suggestion = ProcessSuggestion.builder()
                .name("Monthly Report Flow")
                .description("Recurring monthly report distribution pattern")
                .discoverySource("EMAIL_FLOW")
                .confidence(0.85)
                .phases(List.of(phase))
                .sourceGraphNodeIds(List.of("node-1", "node-2"))
                .evidence(List.of("3 occurrences in last 6 months"))
                .build();

        when(discoveryService.discoverProcesses(anyList(), anyMap())).thenReturn(List.of(suggestion));

        Map<String, Object> result = tool.discoverProcesses(
                new ProcessDiscoveryTool.DiscoverProcessesInput(List.of("node-1", "node-2"), 0.5));

        assertEquals("success", result.get("status"));
        assertEquals(1, result.get("count"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> suggestions = (List<Map<String, Object>>) result.get("suggestions");
        assertNotNull(suggestions);
        assertEquals(1, suggestions.size());
        Map<String, Object> item = suggestions.get(0);
        assertEquals("Monthly Report Flow", item.get("name"));
        assertEquals("EMAIL_FLOW", item.get("discoverySource"));
        assertEquals(0.85, item.get("confidence"));
        assertEquals(1, item.get("phaseCount"));
        assertEquals(1, item.get("totalStepCount"));
    }

    @Test
    void discoverProcesses_handlesError() {
        when(discoveryService.discoverProcesses(any(), any()))
                .thenThrow(new RuntimeException("KG query failed"));

        Map<String, Object> result = tool.discoverProcesses(
                new ProcessDiscoveryTool.DiscoverProcessesInput(null, null));

        assertEquals("error", result.get("status"));
        assertEquals("KG query failed", result.get("error"));
    }

    // ─── analyzeEmailFlows ───────────────────────────────────────────────

    @Test
    void analyzeEmailFlows_returnsPatterns() {
        FlowPattern.FlowStep step = FlowPattern.FlowStep.builder()
                .description("Alice sends budget report to Bob")
                .actor("alice@example.com")
                .action("SEND")
                .target("bob@example.com")
                .nodeId("email-node-1")
                .build();

        FlowPattern pattern = FlowPattern.builder()
                .type("EMAIL_FLOW")
                .description("Monthly budget report exchange")
                .occurrenceCount(4)
                .confidence(0.9)
                .steps(List.of(step))
                .involvedNodeIds(List.of("email-node-1", "email-node-2"))
                .build();

        when(discoveryService.analyzeEmailFlows(anyList())).thenReturn(List.of(pattern));

        Map<String, Object> result = tool.analyzeEmailFlows(
                new ProcessDiscoveryTool.AnalyzeEmailFlowsInput(List.of("node-1")));

        assertEquals("success", result.get("status"));
        assertEquals(1, result.get("count"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> patterns = (List<Map<String, Object>>) result.get("patterns");
        assertNotNull(patterns);
        assertEquals(1, patterns.size());
        Map<String, Object> item = patterns.get(0);
        assertEquals("EMAIL_FLOW", item.get("type"));
        assertEquals(4, item.get("occurrenceCount"));
        assertEquals(0.9, item.get("confidence"));
    }

    @Test
    void analyzeEmailFlows_handlesError() {
        when(discoveryService.analyzeEmailFlows(any()))
                .thenThrow(new RuntimeException("Email index unavailable"));

        Map<String, Object> result = tool.analyzeEmailFlows(
                new ProcessDiscoveryTool.AnalyzeEmailFlowsInput(null));

        assertEquals("error", result.get("status"));
        assertEquals("Email index unavailable", result.get("error"));
    }

    // ─── analyzeExcelFlows ───────────────────────────────────────────────

    @Test
    void analyzeExcelFlows_returnsPatterns() {
        FlowPattern.FlowStep inputStep = FlowPattern.FlowStep.builder()
                .description("Analyst enters actuals into input sheet")
                .actor("analyst")
                .action("COMPUTE")
                .target("Sheet1!B2")
                .build();

        FlowPattern.FlowStep approveStep = FlowPattern.FlowStep.builder()
                .description("Manager approves computed totals")
                .actor("manager")
                .action("APPROVE")
                .target("Sheet1!D10")
                .build();

        FlowPattern pattern = FlowPattern.builder()
                .type("EXCEL_COMPUTATION")
                .description("Budget variance computation pattern")
                .occurrenceCount(2)
                .confidence(0.75)
                .steps(List.of(inputStep, approveStep))
                .involvedNodeIds(List.of("excel-node-1"))
                .build();

        when(discoveryService.analyzeExcelFlows(anyList())).thenReturn(List.of(pattern));

        Map<String, Object> result = tool.analyzeExcelFlows(
                new ProcessDiscoveryTool.AnalyzeExcelFlowsInput(List.of("excel-node-1")));

        assertEquals("success", result.get("status"));
        assertEquals(1, result.get("count"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> patterns = (List<Map<String, Object>>) result.get("patterns");
        assertNotNull(patterns);
        assertEquals(1, patterns.size());
        Map<String, Object> item = patterns.get(0);
        assertEquals("EXCEL_COMPUTATION", item.get("type"));
        assertEquals(2, item.get("occurrenceCount"));
        assertEquals(0.75, item.get("confidence"));
    }

    @Test
    void analyzeExcelFlows_handlesError() {
        when(discoveryService.analyzeExcelFlows(any()))
                .thenThrow(new RuntimeException("Spreadsheet parser error"));

        Map<String, Object> result = tool.analyzeExcelFlows(
                new ProcessDiscoveryTool.AnalyzeExcelFlowsInput(null));

        assertEquals("error", result.get("status"));
        assertEquals("Spreadsheet parser error", result.get("error"));
    }

    // ─── analyzeDocumentFlows ────────────────────────────────────────────

    @Test
    void analyzeDocumentFlows_returnsPatterns() {
        FlowPattern.FlowStep step = FlowPattern.FlowStep.builder()
                .description("Author drafts quarterly review document")
                .actor("author@example.com")
                .action("TRANSFORM")
                .target("quarterly-review-v1.docx")
                .nodeId("doc-node-1")
                .build();

        FlowPattern pattern = FlowPattern.builder()
                .type("DOCUMENT_PIPELINE")
                .description("Quarterly review authoring and approval cycle")
                .occurrenceCount(3)
                .confidence(0.8)
                .steps(List.of(step))
                .involvedNodeIds(List.of("doc-node-1", "doc-node-2"))
                .build();

        when(discoveryService.analyzeDocumentFlows(anyList())).thenReturn(List.of(pattern));

        Map<String, Object> result = tool.analyzeDocumentFlows(
                new ProcessDiscoveryTool.AnalyzeDocumentFlowsInput(List.of("doc-node-1")));

        assertEquals("success", result.get("status"));
        assertEquals(1, result.get("count"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> patterns = (List<Map<String, Object>>) result.get("patterns");
        assertNotNull(patterns);
        assertEquals(1, patterns.size());
        Map<String, Object> item = patterns.get(0);
        assertEquals("DOCUMENT_PIPELINE", item.get("type"));
        assertEquals(3, item.get("occurrenceCount"));
        assertEquals(0.8, item.get("confidence"));
    }

    @Test
    void analyzeDocumentFlows_handlesError() {
        when(discoveryService.analyzeDocumentFlows(any()))
                .thenThrow(new RuntimeException("Document index error"));

        Map<String, Object> result = tool.analyzeDocumentFlows(
                new ProcessDiscoveryTool.AnalyzeDocumentFlowsInput(null));

        assertEquals("error", result.get("status"));
        assertEquals("Document index error", result.get("error"));
    }

    // ─── acceptSuggestion ────────────────────────────────────────────────

    @Test
    void acceptSuggestion_returnsProcessDefinition() {
        ProcessDefinition definition = ProcessDefinition.builder()
                .id("def-99")
                .name("Monthly Report Flow")
                .version(1)
                .status(ProcessStatus.DRAFT)
                .phases(List.of())
                .metadata(Map.of("discoverySource", "EMAIL_FLOW"))
                .build();

        when(discoveryService.acceptSuggestion(any(ProcessSuggestion.class))).thenReturn(definition);

        Map<String, Object> result = tool.acceptSuggestion(
                new ProcessDiscoveryTool.AcceptSuggestionInput(
                        "Monthly Report Flow",
                        "Recurring monthly report distribution pattern",
                        "EMAIL_FLOW",
                        0.85,
                        List.of("node-1", "node-2"),
                        List.of("3 occurrences in last 6 months")));

        assertEquals("success", result.get("status"));
        assertEquals("def-99", result.get("definitionId"));
        assertEquals("Monthly Report Flow", result.get("name"));
        assertEquals(1, result.get("version"));
        assertEquals("DRAFT", result.get("processStatus"));
        assertEquals(0, result.get("phaseCount"));
    }

    @Test
    void acceptSuggestion_buildsCorrectSuggestionFromInput() {
        ProcessDefinition definition = ProcessDefinition.builder()
                .id("def-100")
                .name("Invoice Approval")
                .version(1)
                .status(ProcessStatus.DRAFT)
                .build();

        when(discoveryService.acceptSuggestion(any(ProcessSuggestion.class))).thenReturn(definition);

        // Null lists should default to empty in the tool
        Map<String, Object> result = tool.acceptSuggestion(
                new ProcessDiscoveryTool.AcceptSuggestionInput(
                        "Invoice Approval",
                        "Discovered invoice approval cycle",
                        "DOCUMENT_PIPELINE",
                        0.7,
                        null,
                        null));

        assertEquals("success", result.get("status"));
        assertEquals("def-100", result.get("definitionId"));
        verify(discoveryService).acceptSuggestion(argThat(s ->
                "Invoice Approval".equals(s.getName())
                        && "DOCUMENT_PIPELINE".equals(s.getDiscoverySource())
                        && s.getSourceGraphNodeIds().isEmpty()
                        && s.getEvidence().isEmpty()));
    }

    @Test
    void acceptSuggestion_handlesError() {
        when(discoveryService.acceptSuggestion(any()))
                .thenThrow(new RuntimeException("Process engine unavailable"));

        Map<String, Object> result = tool.acceptSuggestion(
                new ProcessDiscoveryTool.AcceptSuggestionInput(
                        "Failing Flow", "desc", "EMAIL_FLOW", 0.5, List.of(), List.of()));

        assertEquals("error", result.get("status"));
        assertEquals("Process engine unavailable", result.get("error"));
    }
}
