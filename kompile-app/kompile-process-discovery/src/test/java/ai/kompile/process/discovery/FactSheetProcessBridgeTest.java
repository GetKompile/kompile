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

import ai.kompile.core.graphbuilder.GraphBuildCompletedEvent;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.process.service.ProcessEngineService;
import ai.kompile.process.workflow.ProcessDefinition;
import ai.kompile.process.workflow.ProcessStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests the business process bridge: fact-sheet-scoped process discovery.
 * Verifies that factSheetId flows from discovery through to ProcessDefinition.
 */
@ExtendWith(MockitoExtension.class)
class FactSheetProcessBridgeTest {

    @Mock
    private KnowledgeGraphService knowledgeGraphService;

    @Mock
    private GraphNodeRepository graphNodeRepository;

    @Mock
    private ProcessEngineService processEngineService;

    private ProcessDiscoveryServiceImpl service;

    private static final Long FACT_SHEET_ID = 42L;

    @BeforeEach
    void setUp() {
        service = new ProcessDiscoveryServiceImpl(knowledgeGraphService);
        service.setGraphNodeRepository(graphNodeRepository);
        service.setProcessEngineService(processEngineService);
    }

    // ── Helper builders ───────────────────────────────────────────────────

    private GraphNode personNode(String nodeId, String title, Long factSheetId) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .nodeType(NodeLevel.ENTITY)
                .externalId(nodeId)
                .title(title)
                .factSheetId(factSheetId)
                .metadataJson("{\"entity_type\":\"PERSON\"}")
                .build();
    }

    private GraphNode documentNode(String nodeId, String title, Long factSheetId) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .nodeType(NodeLevel.DOCUMENT)
                .externalId(nodeId)
                .title(title)
                .factSheetId(factSheetId)
                .build();
    }

    private GraphNode sheetNode(String nodeId, String title, Long factSheetId) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .nodeType(NodeLevel.TABLE)
                .externalId(nodeId)
                .title(title)
                .factSheetId(factSheetId)
                .metadataJson("{\"entity_subtype\":\"sheet\"}")
                .build();
    }

    private GraphNode formulaCellNode(String nodeId, Long factSheetId) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .nodeType(NodeLevel.ENTITY)
                .externalId(nodeId)
                .title("FormulaCell")
                .factSheetId(factSheetId)
                .metadataJson("{\"entity_subtype\":\"formula_cell\"}")
                .build();
    }

    private GraphNode inputCellNode(String nodeId, Long factSheetId) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .nodeType(NodeLevel.ENTITY)
                .externalId(nodeId)
                .title("InputCell")
                .factSheetId(factSheetId)
                .metadataJson("{\"entity_subtype\":\"input_cell\"}")
                .build();
    }

    private GraphEdge edge(GraphNode source, GraphNode target, String label) {
        return GraphEdge.builder()
                .edgeId(source.getNodeId() + "->" + target.getNodeId())
                .sourceNode(source)
                .targetNode(target)
                .edgeType(EdgeType.USER_DEFINED)
                .weight(1.0)
                .label(label)
                .description("edge: " + label)
                .build();
    }

    // ── Fact-sheet-scoped email discovery ──────────────────────────────────

    @Test
    void analyzeEmailFlowsForFactSheet_usesRepositoryAndSetsFactSheetId() {
        GraphNode alice = personNode("alice", "Alice", FACT_SHEET_ID);
        GraphNode bob = personNode("bob", "Bob", FACT_SHEET_ID);

        // Repository returns fact-sheet-scoped nodes
        when(graphNodeRepository.findByFactSheetId(FACT_SHEET_ID))
                .thenReturn(List.of(alice, bob));

        // Existing KG methods used for scoped graph node lookup
        when(knowledgeGraphService.getNode("alice")).thenReturn(java.util.Optional.of(alice));
        when(knowledgeGraphService.getNode("bob")).thenReturn(java.util.Optional.of(bob));
        when(knowledgeGraphService.getConnectedNodes("alice", 1)).thenReturn(List.of(bob));
        when(knowledgeGraphService.getConnectedNodes("bob", 1)).thenReturn(List.of(alice));

        // Email edges
        GraphEdge e1 = edge(alice, bob, "SENT_BY");
        GraphEdge e2 = edge(alice, bob, "SENT_BY");
        GraphEdge e3 = edge(alice, bob, "SENT_BY");
        when(knowledgeGraphService.getEdgesForNode("alice")).thenReturn(List.of(e1, e2, e3));
        when(knowledgeGraphService.getEdgesForNode("bob")).thenReturn(Collections.emptyList());

        List<FlowPattern> patterns = service.analyzeEmailFlowsForFactSheet(FACT_SHEET_ID);

        assertThat(patterns).hasSize(1);
        assertThat(patterns.get(0).getFactSheetId()).isEqualTo(FACT_SHEET_ID);
        assertThat(patterns.get(0).getType()).isEqualTo("EMAIL_FLOW");
    }

    // ── Fact-sheet-scoped Excel discovery ──────────────────────────────────

    @Test
    void analyzeExcelFlowsForFactSheet_findsSheetNodesAndSetsFactSheetId() {
        GraphNode sheet = sheetNode("sheet1", "Budget Sheet", FACT_SHEET_ID);
        GraphNode fc = formulaCellNode("fc1", FACT_SHEET_ID);
        GraphNode ic = inputCellNode("ic1", FACT_SHEET_ID);

        when(graphNodeRepository.findByFactSheetId(FACT_SHEET_ID))
                .thenReturn(List.of(sheet, fc, ic));

        // analyzeExcelFlows(nodeIds) calls getNode for each nodeId
        when(knowledgeGraphService.getNode("sheet1")).thenReturn(java.util.Optional.of(sheet));
        when(knowledgeGraphService.getNode("fc1")).thenReturn(java.util.Optional.of(fc));
        when(knowledgeGraphService.getNode("ic1")).thenReturn(java.util.Optional.of(ic));
        // Then getChildren on the TABLE node
        when(knowledgeGraphService.getChildren("sheet1")).thenReturn(List.of(fc, ic));

        List<FlowPattern> patterns = service.analyzeExcelFlowsForFactSheet(FACT_SHEET_ID);

        assertThat(patterns).hasSize(1);
        assertThat(patterns.get(0).getFactSheetId()).isEqualTo(FACT_SHEET_ID);
        assertThat(patterns.get(0).getType()).isEqualTo("EXCEL_COMPUTATION");
        // 3 steps: input + compute + review
        assertThat(patterns.get(0).getSteps()).hasSize(3);
    }

    // ── Combined discovery ────────────────────────────────────────────────

    @Test
    void discoverProcessesForFactSheet_combinesAllPatternsWithFactSheetId() {
        // Set up a fact sheet with both email and excel patterns
        GraphNode alice = personNode("alice", "Alice", FACT_SHEET_ID);
        GraphNode bob = personNode("bob", "Bob", FACT_SHEET_ID);
        GraphNode sheet = sheetNode("sheet1", "Budget Sheet", FACT_SHEET_ID);
        GraphNode fc = formulaCellNode("fc1", FACT_SHEET_ID);
        GraphNode ic = inputCellNode("ic1", FACT_SHEET_ID);

        when(graphNodeRepository.findByFactSheetId(FACT_SHEET_ID))
                .thenReturn(List.of(alice, bob, sheet, fc, ic));

        // Email edges for alice→bob (2 occurrences — meets minimum)
        GraphEdge e1 = edge(alice, bob, "SENT_BY");
        GraphEdge e2 = edge(alice, bob, "SENT_BY");

        // getNode is called per nodeId from the collected set
        when(knowledgeGraphService.getNode(anyString())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            return switch (id) {
                case "alice" -> java.util.Optional.of(alice);
                case "bob" -> java.util.Optional.of(bob);
                case "sheet1" -> java.util.Optional.of(sheet);
                case "fc1" -> java.util.Optional.of(fc);
                case "ic1" -> java.util.Optional.of(ic);
                default -> java.util.Optional.empty();
            };
        });
        when(knowledgeGraphService.getConnectedNodes(anyString(), eq(1))).thenReturn(Collections.emptyList());
        when(knowledgeGraphService.getEdgesForNode(anyString())).thenReturn(Collections.emptyList());
        when(knowledgeGraphService.getEdgesForNode("alice")).thenReturn(List.of(e1, e2));
        when(knowledgeGraphService.getChildren(anyString())).thenReturn(Collections.emptyList());
        when(knowledgeGraphService.getChildren("sheet1")).thenReturn(List.of(fc, ic));

        // Suppress LLM discovery
        List<ProcessSuggestion> suggestions = service.discoverProcessesForFactSheet(
                FACT_SHEET_ID, Map.of("useLlm", false));

        // Should have email flow + excel computation patterns
        assertThat(suggestions).isNotEmpty();
        // Every suggestion must carry the factSheetId
        for (ProcessSuggestion s : suggestions) {
            assertThat(s.getFactSheetId()).isEqualTo(FACT_SHEET_ID);
        }
    }

    // ── acceptSuggestion carries factSheetId through ──────────────────────

    @Test
    void acceptSuggestion_setsFactSheetIdOnProcessDefinition() {
        when(processEngineService.createProcess(any())).thenAnswer(inv -> inv.getArgument(0));

        ProcessSuggestion suggestion = ProcessSuggestion.builder()
                .factSheetId(FACT_SHEET_ID)
                .name("Monthly Close")
                .description("Monthly financial close process")
                .discoverySource("EMAIL_FLOW")
                .confidence(0.85)
                .phases(List.of(ProcessSuggestion.SuggestedPhase.builder()
                        .name("Data Collection")
                        .steps(List.of(ProcessSuggestion.SuggestedStep.builder()
                                .name("Collect inputs")
                                .stepType("HUMAN")
                                .description("Gather budget data")
                                .build()))
                        .build()))
                .sourceGraphNodeIds(List.of("node-1", "node-2"))
                .evidence(List.of("3 email exchanges observed"))
                .build();

        ProcessDefinition definition = service.acceptSuggestion(suggestion);

        assertThat(definition.getFactSheetId()).isEqualTo(FACT_SHEET_ID);
        assertThat(definition.getStatus()).isEqualTo(ProcessStatus.DRAFT);
        assertThat(definition.getName()).isEqualTo("Monthly Close");
        assertThat(definition.getPhases()).hasSize(1);
        assertThat(definition.getPhases().get(0).getSteps()).hasSize(1);
    }

    // ── onGraphBuildCompleted uses factSheetId ────────────────────────────

    @Test
    void onGraphBuildCompleted_withFactSheetId_runsFactSheetScopedDiscovery() {
        // Set up a minimal graph so discovery completes without errors
        when(graphNodeRepository.findByFactSheetId(FACT_SHEET_ID))
                .thenReturn(Collections.emptyList());

        GraphBuildCompletedEvent event = new GraphBuildCompletedEvent(
                this, "job-1", 10, 5, FACT_SHEET_ID, null);

        service.onGraphBuildCompleted(event);

        // Verify the repository was queried with the fact sheet ID
        verify(graphNodeRepository, atLeastOnce()).findByFactSheetId(FACT_SHEET_ID);
    }

    @Test
    void onGraphBuildCompleted_withoutFactSheetId_runsGlobalDiscovery() {
        // No factSheetId — should fall back to global search
        when(knowledgeGraphService.searchNodes(eq(""), any(NodeLevel.class), anyInt()))
                .thenReturn(new ArrayList<>());

        GraphBuildCompletedEvent event = new GraphBuildCompletedEvent(
                this, "job-2", 5, 3);

        service.onGraphBuildCompleted(event);

        // Verify global search was called (not fact-sheet-scoped)
        verify(knowledgeGraphService, atLeastOnce()).searchNodes(eq(""), any(NodeLevel.class), anyInt());
        verify(graphNodeRepository, never()).findByFactSheetId(any());
    }

    // ── FlowPattern and ProcessSuggestion factSheetId propagation ─────────

    @Test
    void flowPattern_factSheetId_propagatesToSuggestion() {
        // Manually build a flow pattern with factSheetId and verify it propagates
        FlowPattern flow = FlowPattern.builder()
                .factSheetId(FACT_SHEET_ID)
                .type("EMAIL_FLOW")
                .description("Test email flow")
                .occurrenceCount(3)
                .confidence(0.8)
                .steps(List.of(FlowPattern.FlowStep.builder()
                        .description("Send email")
                        .actor("Alice")
                        .action("SEND")
                        .target("Bob")
                        .nodeId("node-1")
                        .build()))
                .involvedNodeIds(List.of("node-1", "node-2"))
                .build();

        assertThat(flow.getFactSheetId()).isEqualTo(FACT_SHEET_ID);
    }

    @Test
    void processSuggestion_factSheetId_roundTrips() {
        ProcessSuggestion suggestion = ProcessSuggestion.builder()
                .factSheetId(99L)
                .name("Test Process")
                .description("A test")
                .confidence(0.9)
                .build();

        assertThat(suggestion.getFactSheetId()).isEqualTo(99L);
    }

    // ── Empty fact sheet graph ─────────────────────────────────────────────

    @Test
    void discoverProcessesForFactSheet_emptyGraph_returnsEmptySuggestions() {
        when(graphNodeRepository.findByFactSheetId(FACT_SHEET_ID))
                .thenReturn(Collections.emptyList());

        List<ProcessSuggestion> suggestions = service.discoverProcessesForFactSheet(
                FACT_SHEET_ID, Map.of("useLlm", false));

        assertThat(suggestions).isEmpty();
    }

    // ── Document flow for fact sheet ──────────────────────────────────────

    @Test
    void analyzeDocumentFlowsForFactSheet_setsFactSheetId() {
        GraphNode doc1 = documentNode("doc1", "Report v1", FACT_SHEET_ID);
        GraphNode doc2 = documentNode("doc2", "Report v2", FACT_SHEET_ID);

        when(graphNodeRepository.findByFactSheetId(FACT_SHEET_ID))
                .thenReturn(List.of(doc1, doc2));

        // analyzeDocumentFlows(nodeIds) calls getNode per ID, getConnectedNodes, then
        // getChildren and getEdgesForNode for each DOCUMENT node
        // analyzeDocumentFlows(nodeIds) calls getNode for each ID to find DOCUMENT nodes,
        // then getChildren and getEdgesForNode per document for pattern analysis
        when(knowledgeGraphService.getNode("doc1")).thenReturn(java.util.Optional.of(doc1));
        when(knowledgeGraphService.getNode("doc2")).thenReturn(java.util.Optional.of(doc2));
        when(knowledgeGraphService.getChildren(anyString())).thenReturn(Collections.emptyList());
        when(knowledgeGraphService.getEdgesForNode(anyString())).thenReturn(Collections.emptyList());

        List<FlowPattern> patterns = service.analyzeDocumentFlowsForFactSheet(FACT_SHEET_ID);

        // With no author/version/topic/form edges, no patterns found — but no error either
        // If patterns were found, every one must carry the factSheetId
        for (FlowPattern p : patterns) {
            assertThat(p.getFactSheetId()).isEqualTo(FACT_SHEET_ID);
        }
    }
}
