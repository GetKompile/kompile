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
import ai.kompile.process.workflow.ProcessDefinition;
import ai.kompile.process.workflow.ProcessStatus;
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
import static org.mockito.Mockito.when;

/**
 * Tests for cross-document process discovery and process hierarchy support.
 * Covers:
 * 1. Email-with-spreadsheet-attachment → parent/child process
 * 2. Explicit cross-document references (REFERENCES_DOCUMENT edges)
 * 3. Implicit name-based references (email mentions spreadsheet by name)
 * 4. Hierarchical acceptSuggestion producing parent/child ProcessDefinitions
 * 5. Process hierarchy fields on ProcessDefinition
 */
@ExtendWith(MockitoExtension.class)
class CrossDocumentProcessDiscoveryTest {

    @Mock
    private KnowledgeGraphService knowledgeGraphService;

    private ProcessDiscoveryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProcessDiscoveryServiceImpl(knowledgeGraphService);
    }

    // ── Node builders ───────────────────────────────────────────────────────

    private GraphNode emailNode(String id, String subject) {
        return GraphNode.builder()
                .nodeId(id).nodeType(NodeLevel.ENTITY).externalId(id).title(subject)
                .metadataJson("{\"entity_type\":\"EMAIL_MESSAGE\",\"email.subject\":\"" + subject + "\"}")
                .build();
    }

    private GraphNode emailNodeWithPreview(String id, String subject, String preview) {
        return GraphNode.builder()
                .nodeId(id).nodeType(NodeLevel.ENTITY).externalId(id).title(subject)
                .contentPreview(preview)
                .metadataJson("{\"entity_type\":\"EMAIL_MESSAGE\",\"email.subject\":\"" + subject + "\"}")
                .build();
    }

    private GraphNode personNode(String id, String name) {
        return GraphNode.builder()
                .nodeId(id).nodeType(NodeLevel.ENTITY).externalId(id).title(name)
                .metadataJson("{\"entity_type\":\"PERSON\"}")
                .build();
    }

    private GraphNode spreadsheetNode(String id, String title) {
        return GraphNode.builder()
                .nodeId(id).nodeType(NodeLevel.ENTITY).externalId(id).title(title)
                .metadataJson("{\"entity_type\":\"SPREADSHEET\"}")
                .build();
    }

    private GraphNode sheetNode(String id, String title) {
        return GraphNode.builder()
                .nodeId(id).nodeType(NodeLevel.TABLE).externalId(id).title(title)
                .metadataJson("{\"entity_subtype\":\"sheet\"}")
                .build();
    }

    private GraphNode formulaCellNode(String id, String formula) {
        return GraphNode.builder()
                .nodeId(id).nodeType(NodeLevel.ENTITY).externalId(id)
                .title("Formula: " + formula)
                .metadataJson("{\"entity_subtype\":\"formula_cell\",\"cellType\":\"FORMULA\"}")
                .build();
    }

    private GraphNode inputCellNode(String id, String value) {
        return GraphNode.builder()
                .nodeId(id).nodeType(NodeLevel.ENTITY).externalId(id)
                .title("Cell: " + value)
                .metadataJson("{\"entity_subtype\":\"input_cell\"}")
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

    // ── Scenario 1: Email with spreadsheet attachment ───────────────────────

    @Nested
    class EmailWithSpreadsheetAttachment {

        @Test
        void emailWithAttachedSpreadsheet_discoversHierarchicalProcess() {
            GraphNode email = emailNode("email-1", "Please fill out Q1 Budget");
            GraphNode sender = personNode("person-alice", "Alice");
            GraphNode recipient = personNode("person-bob", "Bob");
            GraphNode spreadsheet = spreadsheetNode("ss-budget", "Q1_Budget.xlsx");

            // Email edges: SENT_BY Alice, SENT_TO Bob, HAS_ATTACHMENT spreadsheet
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(new ArrayList<>(List.of(email, sender, recipient, spreadsheet)));
            when(knowledgeGraphService.getEdgesForNode("email-1")).thenReturn(List.of(
                    edge(email, sender, "SENT_BY"),
                    edge(email, recipient, "SENT_TO"),
                    edge(email, spreadsheet, "HAS_ATTACHMENT")
            ));

            // Spreadsheet has formula and input cells
            GraphNode fc = formulaCellNode("fc-1", "SUM(B2:B5)");
            GraphNode ic = inputCellNode("ic-1", "50000");
            when(knowledgeGraphService.getChildren("ss-budget")).thenReturn(List.of(fc, ic));

            List<FlowPattern> patterns = service.analyzeCrossDocumentFlows(null);

            assertThat(patterns).hasSize(1);
            FlowPattern parent = patterns.get(0);
            assertThat(parent.getType()).isEqualTo("EMAIL_SPREADSHEET_PROCESS");
            assertThat(parent.getDescription()).contains("Q1 Budget");
            assertThat(parent.getConfidence()).isGreaterThan(0.7);

            // Should have child patterns
            assertThat(parent.getChildPatterns()).hasSize(1);
            FlowPattern child = parent.getChildPatterns().get(0);
            assertThat(child.getType()).isEqualTo("SPREADSHEET_SUBPROCESS");
            assertThat(child.getParentFlowType()).isEqualTo("EMAIL_SPREADSHEET_PROCESS");
            assertThat(child.getDescription()).contains("Q1_Budget.xlsx");

            // Parent steps should include email receive, review, compute, reply
            assertThat(parent.getSteps()).hasSizeGreaterThanOrEqualTo(3);
            assertThat(parent.getSteps().get(0).getAction()).isEqualTo("SEND");
            assertThat(parent.getSteps().get(0).getActor()).isEqualTo("Alice");

            // Involved IDs should span both email and spreadsheet nodes
            assertThat(parent.getInvolvedNodeIds())
                    .contains("email-1", "ss-budget");
        }

        @Test
        void emailWithMultipleSpreadsheets_createsMultipleChildPatterns() {
            GraphNode email = emailNode("email-2", "Monthly reports attached");
            GraphNode ss1 = spreadsheetNode("ss-rev", "Revenue.xlsx");
            GraphNode ss2 = spreadsheetNode("ss-cost", "Costs.xlsx");

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(new ArrayList<>(List.of(email, ss1, ss2)));
            when(knowledgeGraphService.getEdgesForNode("email-2")).thenReturn(List.of(
                    edge(email, ss1, "HAS_ATTACHMENT"),
                    edge(email, ss2, "HAS_ATTACHMENT")
            ));

            when(knowledgeGraphService.getChildren("ss-rev")).thenReturn(List.of(
                    formulaCellNode("fc-r1", "A1+A2")));
            when(knowledgeGraphService.getChildren("ss-cost")).thenReturn(List.of(
                    formulaCellNode("fc-c1", "B1*1.1")));

            List<FlowPattern> patterns = service.analyzeCrossDocumentFlows(null);

            assertThat(patterns).hasSize(1);
            assertThat(patterns.get(0).getChildPatterns()).hasSize(2);
        }
    }

    // ── Scenario 2: Explicit cross-document references ──────────────────────

    @Nested
    class ExplicitDocumentReferences {

        @Test
        void documentWithReferencesDocumentEdge_discoversHierarchicalProcess() {
            GraphNode procedure = documentNode("doc-procedure", "Budget Process Guide");
            GraphNode spreadsheet = sheetNode("sheet-budget", "Budget Sheet");

            when(knowledgeGraphService.getNode("doc-procedure")).thenReturn(Optional.of(procedure));
            when(knowledgeGraphService.getEdgesForNode("doc-procedure")).thenReturn(List.of(
                    edge(procedure, spreadsheet, "REFERENCES_DOCUMENT")
            ));
            when(knowledgeGraphService.getChildren("sheet-budget")).thenReturn(List.of(
                    formulaCellNode("fc-1", "SUM(A1:A10)"),
                    inputCellNode("ic-1", "100")
            ));

            List<FlowPattern> patterns = service.analyzeCrossDocumentFlows(List.of("doc-procedure"));

            assertThat(patterns).hasSize(1);
            FlowPattern parent = patterns.get(0);
            assertThat(parent.getType()).isEqualTo("CROSS_DOCUMENT_PROCESS");
            assertThat(parent.getChildPatterns()).hasSize(1);
            assertThat(parent.getChildPatterns().get(0).getType()).isEqualTo("SPREADSHEET_SUBPROCESS");
        }

        @Test
        void documentWithDescribesProcedureEdge_discoversHierarchicalProcess() {
            GraphNode emailDoc = documentNode("doc-email", "How to submit expense report");
            GraphNode form = documentNode("doc-form", "Expense Report Form.pdf");

            when(knowledgeGraphService.getNode("doc-email")).thenReturn(Optional.of(emailDoc));
            when(knowledgeGraphService.getEdgesForNode("doc-email")).thenReturn(List.of(
                    edge(emailDoc, form, "DESCRIBES_PROCEDURE")
            ));

            List<FlowPattern> patterns = service.analyzeCrossDocumentFlows(List.of("doc-email"));

            assertThat(patterns).hasSize(1);
            FlowPattern parent = patterns.get(0);
            assertThat(parent.getType()).isEqualTo("CROSS_DOCUMENT_PROCESS");
            assertThat(parent.getChildPatterns()).hasSize(1);
            // Non-spreadsheet document gets a DOCUMENT_SUBPROCESS
            assertThat(parent.getChildPatterns().get(0).getType()).isEqualTo("DOCUMENT_SUBPROCESS");
        }
    }

    // ── Scenario 3: Implicit name-based references ──────────────────────────

    @Nested
    class ImplicitNameReferences {

        @Test
        void emailMentioningSpreadsheetByName_discoversImplicitReference() {
            GraphNode email = emailNodeWithPreview("email-3",
                    "Please update the budget",
                    "Open the Q1_Budget.xlsx and fill in column B with the latest revenue figures");
            GraphNode spreadsheet = sheetNode("sheet-q1", "Q1_Budget.xlsx");

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(new ArrayList<>(List.of(email)));
            // No explicit attachment edges
            when(knowledgeGraphService.getEdgesForNode("email-3")).thenReturn(List.of());

            // Spreadsheet exists in the graph
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.TABLE), anyInt()))
                    .thenReturn(new ArrayList<>(List.of(spreadsheet)));
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                    .thenReturn(new ArrayList<>());
            when(knowledgeGraphService.getChildren("sheet-q1")).thenReturn(List.of(
                    formulaCellNode("fc-1", "SUM(B2:B10)")));

            List<FlowPattern> patterns = service.analyzeCrossDocumentFlows(null);

            // Should find the implicit reference
            boolean foundImplicit = patterns.stream()
                    .anyMatch(p -> p.getType().equals("IMPLICIT_REFERENCE_PROCESS"));
            assertThat(foundImplicit).as("Should discover implicit name-based reference").isTrue();

            FlowPattern implicit = patterns.stream()
                    .filter(p -> p.getType().equals("IMPLICIT_REFERENCE_PROCESS"))
                    .findFirst().orElseThrow();
            assertThat(implicit.getDescription()).contains("Q1_Budget.xlsx");
            assertThat(implicit.getChildPatterns()).hasSize(1);
            assertThat(implicit.getChildPatterns().get(0).getType()).isEqualTo("SPREADSHEET_SUBPROCESS");
        }

        @Test
        void emailAlreadyHasExplicitAttachment_noImplicitDuplicate() {
            GraphNode email = emailNodeWithPreview("email-4",
                    "Budget attached",
                    "See attached Budget.xlsx");
            GraphNode spreadsheet = sheetNode("sheet-budget", "Budget.xlsx");

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(new ArrayList<>(List.of(email)));
            // Explicit HAS_ATTACHMENT edge exists
            when(knowledgeGraphService.getEdgesForNode("email-4")).thenReturn(List.of(
                    edge(email, spreadsheet, "HAS_ATTACHMENT")
            ));

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.TABLE), anyInt()))
                    .thenReturn(new ArrayList<>(List.of(spreadsheet)));
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                    .thenReturn(new ArrayList<>());
            when(knowledgeGraphService.getChildren("sheet-budget")).thenReturn(List.of());

            List<FlowPattern> patterns = service.analyzeCrossDocumentFlows(null);

            // The implicit analysis should NOT duplicate the attachment-based one
            long implicitCount = patterns.stream()
                    .filter(p -> p.getType().equals("IMPLICIT_REFERENCE_PROCESS"))
                    .count();
            assertThat(implicitCount).isZero();
        }
    }

    // ── Scenario 4: Hierarchical accept produces parent/child definitions ───

    @Nested
    class HierarchicalAcceptance {

        @Test
        void acceptHierarchicalSuggestion_createsParentWithChildProcesses() {
            ProcessSuggestion childSuggestion = ProcessSuggestion.builder()
                    .name("Spreadsheet Computation")
                    .description("Execute formulas in Budget.xlsx")
                    .discoverySource("SPREADSHEET_SUBPROCESS")
                    .confidence(0.8)
                    .phases(List.of(
                            ProcessSuggestion.SuggestedPhase.builder()
                                    .name("Compute")
                                    .steps(List.of(
                                            ProcessSuggestion.SuggestedStep.builder()
                                                    .name("Execute formulas")
                                                    .stepType("EXCEL_COMPUTE")
                                                    .graphNodeIds(List.of("sheet-budget"))
                                                    .build()
                                    )).build()
                    ))
                    .sourceGraphNodeIds(List.of("sheet-budget"))
                    .build();

            ProcessSuggestion parentSuggestion = ProcessSuggestion.builder()
                    .name("Email-Driven Budget Review")
                    .description("Email instructs recipient to fill out Budget.xlsx")
                    .discoverySource("EMAIL_SPREADSHEET_PROCESS")
                    .confidence(0.85)
                    .phases(List.of(
                            ProcessSuggestion.SuggestedPhase.builder()
                                    .name("Email Processing")
                                    .steps(List.of(
                                            ProcessSuggestion.SuggestedStep.builder()
                                                    .name("Receive email")
                                                    .stepType("AUTO")
                                                    .build(),
                                            ProcessSuggestion.SuggestedStep.builder()
                                                    .name("Review instructions")
                                                    .stepType("HUMAN")
                                                    .build()
                                    )).build()
                    ))
                    .childSuggestions(List.of(childSuggestion))
                    .sourceGraphNodeIds(List.of("email-1", "sheet-budget"))
                    .build();

            ProcessDefinition parentDef = service.acceptSuggestion(parentSuggestion);

            // Parent should have child process IDs
            assertThat(parentDef.getChildProcessIds()).isNotNull();
            assertThat(parentDef.getChildProcessIds()).hasSize(1);
            assertThat(parentDef.getStatus()).isEqualTo(ProcessStatus.DRAFT);

            // Metadata should track discovery provenance
            assertThat(parentDef.getMetadata().get("discoverySource"))
                    .isEqualTo("EMAIL_SPREADSHEET_PROCESS");
        }

        @Test
        void processDefinition_supportsParentProcessId() {
            ProcessDefinition child = ProcessDefinition.builder()
                    .id("child-1")
                    .name("Sub Process")
                    .parentProcessId("parent-1")
                    .build();

            ProcessDefinition parent = ProcessDefinition.builder()
                    .id("parent-1")
                    .name("Parent Process")
                    .childProcessIds(List.of("child-1"))
                    .build();

            assertThat(child.getParentProcessId()).isEqualTo("parent-1");
            assertThat(parent.getChildProcessIds()).containsExactly("child-1");
        }
    }

    // ── Scenario 5: Full pipeline — discover + accept hierarchical ──────────

    @Nested
    class FullPipelineHierarchical {

        @Test
        void discoverProcesses_includesCrossDocumentPatterns() {
            GraphNode email = emailNode("email-5", "Fill out expenses");
            GraphNode spreadsheet = spreadsheetNode("ss-exp", "Expenses.xlsx");

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(new ArrayList<>(List.of(email, spreadsheet)));
            when(knowledgeGraphService.getEdgesForNode("email-5")).thenReturn(List.of(
                    edge(email, spreadsheet, "HAS_ATTACHMENT")
            ));
            when(knowledgeGraphService.getChildren("ss-exp")).thenReturn(List.of(
                    formulaCellNode("fc-1", "A1+A2")));

            // No email sender/recipient patterns, no sheets, no documents
            when(knowledgeGraphService.searchNodes("sheet", NodeLevel.TABLE, 100))
                    .thenReturn(Collections.emptyList());
            when(knowledgeGraphService.searchNodes("table", NodeLevel.TABLE, 100))
                    .thenReturn(Collections.emptyList());
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                    .thenReturn(new ArrayList<>());
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.TABLE), anyInt()))
                    .thenReturn(new ArrayList<>());

            List<ProcessSuggestion> suggestions = service.discoverProcesses(null, null);

            // Should include the cross-document process
            boolean hasCrossDoc = suggestions.stream()
                    .anyMatch(s -> s.getDiscoverySource().equals("EMAIL_SPREADSHEET_PROCESS"));
            assertThat(hasCrossDoc)
                    .as("discoverProcesses should include cross-document patterns")
                    .isTrue();

            ProcessSuggestion crossDoc = suggestions.stream()
                    .filter(s -> s.getDiscoverySource().equals("EMAIL_SPREADSHEET_PROCESS"))
                    .findFirst().orElseThrow();

            // Should have child suggestions
            assertThat(crossDoc.getChildSuggestions()).isNotNull();
            assertThat(crossDoc.getChildSuggestions()).hasSizeGreaterThanOrEqualTo(1);

            // Accept and verify hierarchy is preserved
            ProcessDefinition def = service.acceptSuggestion(crossDoc);
            assertThat(def.getChildProcessIds()).isNotNull();
            assertThat(def.getChildProcessIds()).isNotEmpty();
        }
    }

    // ── Scenario 6: FlowPattern hierarchy fields ────────────────────────────

    @Nested
    class FlowPatternHierarchy {

        @Test
        void flowPattern_supportsChildPatternsAndParentType() {
            FlowPattern child = FlowPattern.builder()
                    .type("SPREADSHEET_SUBPROCESS")
                    .description("Child process")
                    .parentFlowType("EMAIL_SPREADSHEET_PROCESS")
                    .build();

            FlowPattern parent = FlowPattern.builder()
                    .type("EMAIL_SPREADSHEET_PROCESS")
                    .description("Parent process")
                    .childPatterns(List.of(child))
                    .build();

            assertThat(parent.getChildPatterns()).hasSize(1);
            assertThat(parent.getChildPatterns().get(0).getParentFlowType())
                    .isEqualTo("EMAIL_SPREADSHEET_PROCESS");
        }
    }

    // ── Scenario 7: ProcessSuggestion hierarchy fields ──────────────────────

    @Nested
    class ProcessSuggestionHierarchy {

        @Test
        void processSuggestion_supportsChildSuggestionsAndParentId() {
            ProcessSuggestion child = ProcessSuggestion.builder()
                    .name("Child Process")
                    .parentSuggestionId("parent-suggestion")
                    .build();

            ProcessSuggestion parent = ProcessSuggestion.builder()
                    .name("Parent Process")
                    .childSuggestions(List.of(child))
                    .build();

            assertThat(parent.getChildSuggestions()).hasSize(1);
            assertThat(parent.getChildSuggestions().get(0).getParentSuggestionId())
                    .isEqualTo("parent-suggestion");
        }
    }
}
