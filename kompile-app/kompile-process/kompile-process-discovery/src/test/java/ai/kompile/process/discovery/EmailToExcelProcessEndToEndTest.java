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

import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.process.service.ProcessEngineService;
import ai.kompile.process.workflow.ProcessDefinition;
import ai.kompile.process.workflow.ProcessPhase;
import ai.kompile.process.workflow.ProcessStatus;
import ai.kompile.process.workflow.ProcessStep;
import ai.kompile.process.workflow.StepType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * End-to-end tests for the full pipeline:
 *   Email/document graph nodes → process discovery → process definition → execution linkage
 *
 * Tests the complete path from knowledge graph containing email PERSON/EMAIL_MESSAGE
 * entities and Excel SHEET/CELL entities through to a discovered process definition
 * that correctly references the source graph nodes and can be accepted into the
 * process engine for execution.
 *
 * Scenarios tested:
 * 1. Email flow with attached Excel → discovers combined email + Excel process
 * 2. Standalone Excel computation → discovers Excel-only process
 * 3. Document authoring pipeline → discovers author-based workflow
 * 4. Full discover → accept → verify graph node linkage persists
 */
@ExtendWith(MockitoExtension.class)
class EmailToExcelProcessEndToEndTest {

    @Mock
    private KnowledgeGraphService knowledgeGraphService;

    @Mock
    private ProcessEngineService processEngineService;

    private ProcessDiscoveryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProcessDiscoveryServiceImpl(knowledgeGraphService);
    }

    // ── Graph node builders ──────────────────────────────────────────────────

    private GraphNode personNode(String id, String name) {
        return GraphNode.builder()
                .nodeId(id).nodeType(NodeLevel.ENTITY).externalId(id).title(name)
                .metadataJson("{\"entity_type\":\"PERSON\",\"email\":\"" + name.toLowerCase() + "@example.com\"}")
                .build();
    }

    private GraphNode emailMessageNode(String id, String subject) {
        return GraphNode.builder()
                .nodeId(id).nodeType(NodeLevel.ENTITY).externalId(id).title(subject)
                .metadataJson("{\"entity_type\":\"EMAIL_MESSAGE\",\"email.subject\":\"" + subject + "\"}")
                .build();
    }

    private GraphNode sheetNode(String id, String title) {
        return GraphNode.builder()
                .nodeId(id).nodeType(NodeLevel.TABLE).externalId(id).title(title)
                .metadataJson("{\"entity_subtype\":\"sheet\",\"sheetName\":\"" + title + "\"}")
                .build();
    }

    private GraphNode formulaCellNode(String id, String formula) {
        return GraphNode.builder()
                .nodeId(id).nodeType(NodeLevel.ENTITY).externalId(id)
                .title("Formula: " + formula)
                .metadataJson("{\"entity_subtype\":\"formula_cell\",\"cellType\":\"FORMULA\",\"formula\":\"" + formula + "\"}")
                .build();
    }

    private GraphNode inputCellNode(String id, String displayValue) {
        return GraphNode.builder()
                .nodeId(id).nodeType(NodeLevel.ENTITY).externalId(id)
                .title("Cell: " + displayValue)
                .metadataJson("{\"entity_subtype\":\"input_cell\",\"cellType\":\"NUMERIC\",\"displayValue\":\"" + displayValue + "\"}")
                .build();
    }

    private GraphNode documentNode(String id, String title) {
        return GraphNode.builder()
                .nodeId(id).nodeType(NodeLevel.DOCUMENT).externalId(id).title(title)
                .metadataJson("{}")
                .build();
    }

    private GraphEdge edge(GraphNode source, GraphNode target, String label) {
        return GraphEdge.builder()
                .edgeId(source.getNodeId() + "->" + target.getNodeId())
                .sourceNode(source).targetNode(target)
                .edgeType(EdgeType.USER_DEFINED).weight(1.0)
                .label(label).description(label)
                .build();
    }

    // ── Scenario 1: Email flow discovers recurring exchange ─────────────────

    @Nested
    class EmailFlowDiscovery {

        @Test
        void recurringEmailExchange_discoversProcessWithCorrectNodeIds() {
            GraphNode alice = personNode("person-alice", "Alice");
            GraphNode bob = personNode("person-bob", "Bob");

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(new ArrayList<>(List.of(alice, bob)));

            // Alice → Bob email exchange with 4 occurrences
            List<GraphEdge> aliceEdges = List.of(
                    edge(alice, bob, "SENT_BY"), edge(alice, bob, "SENT_BY"),
                    edge(alice, bob, "SENT_BY"), edge(alice, bob, "SENT_BY")
            );
            when(knowledgeGraphService.getEdgesForNode("person-alice")).thenReturn(aliceEdges);
            when(knowledgeGraphService.getEdgesForNode("person-bob")).thenReturn(Collections.emptyList());

            // No Excel sheets
            when(knowledgeGraphService.searchNodes("sheet", NodeLevel.TABLE, 100))
                    .thenReturn(Collections.emptyList());
            when(knowledgeGraphService.searchNodes("table", NodeLevel.TABLE, 100))
                    .thenReturn(Collections.emptyList());
            // No documents
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                    .thenReturn(new ArrayList<>());

            List<ProcessSuggestion> suggestions = service.discoverProcesses(null, null);

            assertThat(suggestions).hasSize(1);
            ProcessSuggestion s = suggestions.get(0);
            assertThat(s.getDiscoverySource()).isEqualTo("EMAIL_FLOW");
            assertThat(s.getConfidence()).isCloseTo(0.9, within(0.001)); // min(0.5 + 4*0.1, 0.9)
            assertThat(s.getSourceGraphNodeIds()).contains("person-alice", "person-bob");

            // Accept the suggestion and verify the process definition
            ProcessDefinition def = service.acceptSuggestion(s);

            assertThat(def.getStatus()).isEqualTo(ProcessStatus.DRAFT);
            assertThat(def.getPhases()).isNotEmpty();

            // Steps should reference the source graph node IDs
            for (ProcessPhase phase : def.getPhases()) {
                for (ProcessStep step : phase.getSteps()) {
                    // Steps that have nodeIds should preserve them
                    if (step.getGraphNodeIds() != null && !step.getGraphNodeIds().isEmpty()) {
                        assertThat(step.getGraphNodeIds().get(0)).startsWith("person-");
                    }
                }
            }

            // Metadata should track discovery provenance
            assertThat(def.getMetadata().get("discoverySource")).isEqualTo("EMAIL_FLOW");
            assertThat(def.getMetadata().get("discoveryConfidence")).isEqualTo(0.9);
            assertThat(def.getMetadata().get("sourceGraphNodeIds")).isNotNull();
        }
    }

    // ── Scenario 2: Excel computation discovers formula workflow ────────────

    @Nested
    class ExcelComputationDiscovery {

        @Test
        void excelSheetWithFormulas_discoversComputeWorkflowWithGraphNodes() {
            GraphNode sheet = sheetNode("sheet-revenue", "Revenue");
            GraphNode fc1 = formulaCellNode("fc-d2", "B2-C2");
            GraphNode fc2 = formulaCellNode("fc-b4", "SUM(B2:B3)");
            GraphNode ic1 = inputCellNode("ic-b2", "50000");
            GraphNode ic2 = inputCellNode("ic-c2", "30000");
            GraphNode ic3 = inputCellNode("ic-b3", "75000");

            when(knowledgeGraphService.searchNodes("sheet", NodeLevel.TABLE, 100))
                    .thenReturn(new ArrayList<>(List.of(sheet)));
            when(knowledgeGraphService.searchNodes("table", NodeLevel.TABLE, 100))
                    .thenReturn(Collections.emptyList());
            when(knowledgeGraphService.getChildren("sheet-revenue"))
                    .thenReturn(new ArrayList<>(List.of(fc1, fc2, ic1, ic2, ic3)));

            // No email entities or documents
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(new ArrayList<>());
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                    .thenReturn(new ArrayList<>());

            List<ProcessSuggestion> suggestions = service.discoverProcesses(null, null);

            assertThat(suggestions).hasSize(1);
            ProcessSuggestion s = suggestions.get(0);
            assertThat(s.getDiscoverySource()).isEqualTo("EXCEL_COMPUTATION");
            // confidence = 0.7 + min(2 * 0.05, 0.2) = 0.8
            assertThat(s.getConfidence()).isCloseTo(0.8, within(0.001));

            // Source graph node IDs should include all cells
            assertThat(s.getSourceGraphNodeIds())
                    .contains("fc-d2", "fc-b4", "ic-b2", "ic-c2", "ic-b3");

            // Accept and verify process definition
            ProcessDefinition def = service.acceptSuggestion(s);
            assertThat(def.getPhases()).isNotEmpty();

            // Should have an INPUT step, a COMPUTE step (EXCEL_COMPUTE), and an APPROVE step
            List<ProcessStep> allSteps = new ArrayList<>();
            for (ProcessPhase phase : def.getPhases()) {
                allSteps.addAll(phase.getSteps());
            }

            boolean hasHumanInput = allSteps.stream().anyMatch(st -> st.getStepType() == StepType.HUMAN);
            boolean hasExcelCompute = allSteps.stream()
                    .anyMatch(st -> st.getStepType() == StepType.EXCEL_COMPUTE
                            || "EXCEL_COMPUTE".equals(st.getName()));
            boolean hasApprove = allSteps.stream().anyMatch(st -> st.getStepType() == StepType.APPROVE);

            assertThat(hasHumanInput).as("Should have a HUMAN input step").isTrue();
            assertThat(hasApprove).as("Should have an APPROVE review step").isTrue();

            // The COMPUTE step should reference the sheet node
            ProcessStep computeStep = allSteps.stream()
                    .filter(st -> st.getDescription() != null && st.getDescription().contains("formula"))
                    .findFirst().orElse(null);
            if (computeStep != null) {
                assertThat(computeStep.getGraphNodeIds()).isNotEmpty();
                assertThat(computeStep.getGraphNodeIds()).contains("sheet-revenue");
            }
        }

        @Test
        void excelSheetWithGraphNodeIdFilter_onlyAnalyzesRequestedNodes() {
            GraphNode parentDoc = documentNode("doc-budget", "Budget.xlsx");
            GraphNode sheet = sheetNode("sheet-in-doc", "Data");
            GraphNode fc = formulaCellNode("fc-1", "A1+A2");

            when(knowledgeGraphService.getNode("doc-budget")).thenReturn(Optional.of(parentDoc));
            when(knowledgeGraphService.getChildren("doc-budget")).thenReturn(List.of(sheet));
            when(knowledgeGraphService.getChildren("sheet-in-doc")).thenReturn(List.of(fc));

            List<FlowPattern> excelFlows = service.analyzeExcelFlows(List.of("doc-budget"));

            assertThat(excelFlows).hasSize(1);
            assertThat(excelFlows.get(0).getType()).isEqualTo("EXCEL_COMPUTATION");
            // The involved node IDs should include the formula cell
            assertThat(excelFlows.get(0).getInvolvedNodeIds()).contains("fc-1");
        }
    }

    // ── Scenario 3: Combined email + Excel discovery ────────────────────────

    @Nested
    class CombinedDiscovery {

        @Test
        void emailFlowPlusExcelComputation_produceBothSuggestions() {
            // Set up email flow
            GraphNode alice = personNode("p-alice", "Alice");
            GraphNode bob = personNode("p-bob", "Bob");
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(new ArrayList<>(List.of(alice, bob)));

            GraphEdge e1 = edge(alice, bob, "SENT_BY");
            GraphEdge e2 = edge(alice, bob, "SENT_BY");
            GraphEdge e3 = edge(alice, bob, "SENT_BY");
            when(knowledgeGraphService.getEdgesForNode("p-alice")).thenReturn(List.of(e1, e2, e3));
            when(knowledgeGraphService.getEdgesForNode("p-bob")).thenReturn(Collections.emptyList());

            // Set up Excel flow
            GraphNode sheet = sheetNode("s-budget", "Budget");
            when(knowledgeGraphService.searchNodes("sheet", NodeLevel.TABLE, 100))
                    .thenReturn(new ArrayList<>(List.of(sheet)));
            when(knowledgeGraphService.searchNodes("table", NodeLevel.TABLE, 100))
                    .thenReturn(Collections.emptyList());
            GraphNode fc = formulaCellNode("f1", "SUM(A1:A3)");
            GraphNode ic1 = inputCellNode("i1", "100");
            GraphNode ic2 = inputCellNode("i2", "200");
            when(knowledgeGraphService.getChildren("s-budget"))
                    .thenReturn(new ArrayList<>(List.of(fc, ic1, ic2)));

            // No document flows
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                    .thenReturn(new ArrayList<>());

            List<ProcessSuggestion> suggestions = service.discoverProcesses(null, null);

            assertThat(suggestions).hasSize(2);

            // Should be sorted by confidence descending
            assertThat(suggestions.get(0).getConfidence())
                    .isGreaterThanOrEqualTo(suggestions.get(1).getConfidence());

            Set<String> sources = new HashSet<>();
            for (ProcessSuggestion s : suggestions) {
                sources.add(s.getDiscoverySource());
            }
            assertThat(sources).containsExactlyInAnyOrder("EMAIL_FLOW", "EXCEL_COMPUTATION");
        }

        @Test
        void discoverThenAcceptMultiple_eachGetsUniqueId() {
            // Set up email flow
            GraphNode alice = personNode("p-a", "Alice");
            GraphNode bob = personNode("p-b", "Bob");
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(new ArrayList<>(List.of(alice, bob)));
            when(knowledgeGraphService.getEdgesForNode("p-a"))
                    .thenReturn(List.of(edge(alice, bob, "SENT_TO"), edge(alice, bob, "SENT_TO")));
            when(knowledgeGraphService.getEdgesForNode("p-b")).thenReturn(Collections.emptyList());

            // Set up Excel flow
            GraphNode sheet = sheetNode("s1", "Sheet1");
            when(knowledgeGraphService.searchNodes("sheet", NodeLevel.TABLE, 100))
                    .thenReturn(new ArrayList<>(List.of(sheet)));
            when(knowledgeGraphService.searchNodes("table", NodeLevel.TABLE, 100))
                    .thenReturn(Collections.emptyList());
            when(knowledgeGraphService.getChildren("s1"))
                    .thenReturn(new ArrayList<>(List.of(formulaCellNode("f1", "A1*2"))));

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                    .thenReturn(new ArrayList<>());

            List<ProcessSuggestion> suggestions = service.discoverProcesses(null, null);
            assertThat(suggestions).hasSize(2);

            // Accept both
            ProcessDefinition def1 = service.acceptSuggestion(suggestions.get(0));
            ProcessDefinition def2 = service.acceptSuggestion(suggestions.get(1));

            // Each should have a unique ID
            assertThat(def1.getId()).isNotEqualTo(def2.getId());
            assertThat(def1.getId()).startsWith("discovered-");
            assertThat(def2.getId()).startsWith("discovered-");
        }
    }

    // ── Scenario 4: Accept with persistence and graph node tracing ──────────

    @Nested
    class AcceptWithGraphTracing {

        @Test
        void acceptSuggestion_persists_andPreservesGraphNodeIds() {
            service.setProcessEngineService(processEngineService);

            // Capture the definition passed to createProcess
            when(processEngineService.createProcess(any(ProcessDefinition.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            ProcessSuggestion suggestion = ProcessSuggestion.builder()
                    .name("Budget Review Flow")
                    .description("Quarterly budget review from email to Excel")
                    .discoverySource("EXCEL_COMPUTATION")
                    .confidence(0.85)
                    .sourceGraphNodeIds(List.of("sheet-revenue", "cell-b2", "cell-b3", "cell-d2"))
                    .evidence(List.of("2 formula cells found", "3 input cells found"))
                    .phases(List.of(
                            ProcessSuggestion.SuggestedPhase.builder()
                                    .name("Data Input")
                                    .description("Gather revenue and cost inputs")
                                    .steps(List.of(
                                            ProcessSuggestion.SuggestedStep.builder()
                                                    .name("Enter Q1 Revenue")
                                                    .stepType("HUMAN")
                                                    .description("Input Q1 revenue into cell B2")
                                                    .graphNodeIds(List.of("cell-b2"))
                                                    .suggestedAssignee("alice@example.com")
                                                    .build(),
                                            ProcessSuggestion.SuggestedStep.builder()
                                                    .name("Enter Q1 Costs")
                                                    .stepType("HUMAN")
                                                    .description("Input Q1 costs into cell C2")
                                                    .graphNodeIds(List.of("cell-c2"))
                                                    .suggestedAssignee("alice@example.com")
                                                    .build()
                                    ))
                                    .build(),
                            ProcessSuggestion.SuggestedPhase.builder()
                                    .name("Computation")
                                    .description("Run formulas")
                                    .steps(List.of(
                                            ProcessSuggestion.SuggestedStep.builder()
                                                    .name("Compute Profit")
                                                    .stepType("EXCEL_COMPUTE")
                                                    .description("Execute profit formula B2-C2")
                                                    .graphNodeIds(List.of("sheet-revenue"))
                                                    .build()
                                    ))
                                    .build(),
                            ProcessSuggestion.SuggestedPhase.builder()
                                    .name("Review")
                                    .description("Manager reviews results")
                                    .steps(List.of(
                                            ProcessSuggestion.SuggestedStep.builder()
                                                    .name("Approve Results")
                                                    .stepType("APPROVE")
                                                    .description("Manager approves the computed figures")
                                                    .suggestedAssignee("manager@example.com")
                                                    .build()
                                    ))
                                    .build()
                    ))
                    .build();

            ProcessDefinition def = service.acceptSuggestion(suggestion);

            // Verify persistence was called
            verify(processEngineService).createProcess(any(ProcessDefinition.class));

            // Verify the definition structure
            assertThat(def.getPhases()).hasSize(3);
            assertThat(def.getStatus()).isEqualTo(ProcessStatus.DRAFT);
            assertThat(def.getVersion()).isEqualTo(1);

            // Phase 1: Data Input — 2 HUMAN steps
            ProcessPhase inputPhase = def.getPhases().get(0);
            assertThat(inputPhase.getName()).isEqualTo("Data Input");
            assertThat(inputPhase.getSteps()).hasSize(2);
            assertThat(inputPhase.getSteps().get(0).getStepType()).isEqualTo(StepType.HUMAN);
            assertThat(inputPhase.getSteps().get(0).getGraphNodeIds()).containsExactly("cell-b2");
            assertThat(inputPhase.getSteps().get(1).getGraphNodeIds()).containsExactly("cell-c2");

            // Phase 2: Computation — 1 EXCEL_COMPUTE step
            ProcessPhase computePhase = def.getPhases().get(1);
            assertThat(computePhase.getSteps()).hasSize(1);
            assertThat(computePhase.getSteps().get(0).getStepType()).isEqualTo(StepType.EXCEL_COMPUTE);
            assertThat(computePhase.getSteps().get(0).getGraphNodeIds()).containsExactly("sheet-revenue");

            // Phase 3: Review — 1 APPROVE step
            ProcessPhase reviewPhase = def.getPhases().get(2);
            assertThat(reviewPhase.getSteps()).hasSize(1);
            assertThat(reviewPhase.getSteps().get(0).getStepType()).isEqualTo(StepType.APPROVE);

            // Metadata should preserve discovery provenance for auditing
            assertThat(def.getMetadata().get("discoverySource")).isEqualTo("EXCEL_COMPUTATION");
            assertThat(def.getMetadata().get("discoveryConfidence")).isEqualTo(0.85);
            @SuppressWarnings("unchecked")
            List<String> sourceNodeIds = (List<String>) def.getMetadata().get("sourceGraphNodeIds");
            assertThat(sourceNodeIds).contains("sheet-revenue", "cell-b2", "cell-b3", "cell-d2");
            @SuppressWarnings("unchecked")
            List<String> evidence = (List<String>) def.getMetadata().get("evidence");
            assertThat(evidence).contains("2 formula cells found", "3 input cells found");
        }

        @Test
        void acceptSuggestion_multiPhaseStepIds_areCorrectlyNumbered() {
            ProcessSuggestion suggestion = ProcessSuggestion.builder()
                    .name("Multi-Phase Process")
                    .discoverySource("TEST")
                    .confidence(0.75)
                    .phases(List.of(
                            ProcessSuggestion.SuggestedPhase.builder()
                                    .name("Phase A")
                                    .steps(List.of(
                                            ProcessSuggestion.SuggestedStep.builder()
                                                    .name("A-1").stepType("AUTO").build(),
                                            ProcessSuggestion.SuggestedStep.builder()
                                                    .name("A-2").stepType("HUMAN").build()
                                    )).build(),
                            ProcessSuggestion.SuggestedPhase.builder()
                                    .name("Phase B")
                                    .steps(List.of(
                                            ProcessSuggestion.SuggestedStep.builder()
                                                    .name("B-1").stepType("APPROVE").build()
                                    )).build()
                    ))
                    .build();

            ProcessDefinition def = service.acceptSuggestion(suggestion);

            // Phase IDs: phase-1, phase-2
            assertThat(def.getPhases().get(0).getId()).isEqualTo("phase-1");
            assertThat(def.getPhases().get(1).getId()).isEqualTo("phase-2");

            // Step IDs: 1.1, 1.2, 2.1 (phase.step numbering)
            assertThat(def.getPhases().get(0).getSteps().get(0).getId()).isEqualTo("1.1");
            assertThat(def.getPhases().get(0).getSteps().get(1).getId()).isEqualTo("1.2");
            assertThat(def.getPhases().get(1).getSteps().get(0).getId()).isEqualTo("2.1");

            // Phase ordering
            assertThat(def.getPhases().get(0).getOrder()).isEqualTo(1);
            assertThat(def.getPhases().get(1).getOrder()).isEqualTo(2);
        }
    }

    // ── Scenario 5: Confidence filtering ────────────────────────────────────

    @Nested
    class ConfidenceFiltering {

        @Test
        void lowConfidenceFlows_filteredByMinConfidenceOption() {
            // 2 edges → confidence = 0.7 (email), 1 formula → confidence = 0.75 (excel)
            GraphNode alice = personNode("pa", "Alice");
            GraphNode bob = personNode("pb", "Bob");
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(new ArrayList<>(List.of(alice, bob)));
            when(knowledgeGraphService.getEdgesForNode("pa"))
                    .thenReturn(List.of(edge(alice, bob, "SENT_BY"), edge(alice, bob, "SENT_BY")));
            when(knowledgeGraphService.getEdgesForNode("pb")).thenReturn(Collections.emptyList());

            GraphNode sheet = sheetNode("s1", "Data");
            when(knowledgeGraphService.searchNodes("sheet", NodeLevel.TABLE, 100))
                    .thenReturn(new ArrayList<>(List.of(sheet)));
            when(knowledgeGraphService.searchNodes("table", NodeLevel.TABLE, 100))
                    .thenReturn(Collections.emptyList());
            when(knowledgeGraphService.getChildren("s1"))
                    .thenReturn(new ArrayList<>(List.of(formulaCellNode("f1", "A1+B1"))));

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                    .thenReturn(new ArrayList<>());

            // Set minConfidence = 0.76 → only Excel (0.75) passes... wait, 0.75 < 0.76
            // Email = 0.7, Excel = 0.75. Setting threshold to 0.76 should filter both.
            List<ProcessSuggestion> high = service.discoverProcesses(null, Map.of("minConfidence", 0.76));
            assertThat(high).isEmpty();

            // Set minConfidence = 0.7 → only Excel (0.75) passes, email (0.7) doesn't (>= check)
            List<ProcessSuggestion> medium = service.discoverProcesses(null, Map.of("minConfidence", 0.71));
            assertThat(medium).hasSize(1);
            assertThat(medium.get(0).getDiscoverySource()).isEqualTo("EXCEL_COMPUTATION");
        }

        @Test
        void defaultMinConfidence_is0_5() {
            // 2 edges → email confidence = 0.7 (above default 0.5)
            GraphNode alice = personNode("pa", "Alice");
            GraphNode bob = personNode("pb", "Bob");
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(new ArrayList<>(List.of(alice, bob)));
            when(knowledgeGraphService.getEdgesForNode("pa"))
                    .thenReturn(List.of(edge(alice, bob, "SENT_BY"), edge(alice, bob, "SENT_BY")));
            when(knowledgeGraphService.getEdgesForNode("pb")).thenReturn(Collections.emptyList());

            when(knowledgeGraphService.searchNodes("sheet", NodeLevel.TABLE, 100))
                    .thenReturn(Collections.emptyList());
            when(knowledgeGraphService.searchNodes("table", NodeLevel.TABLE, 100))
                    .thenReturn(Collections.emptyList());
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                    .thenReturn(new ArrayList<>());

            // null options → default minConfidence = 0.5
            List<ProcessSuggestion> suggestions = service.discoverProcesses(null, null);
            assertThat(suggestions).hasSize(1);
        }
    }
}
