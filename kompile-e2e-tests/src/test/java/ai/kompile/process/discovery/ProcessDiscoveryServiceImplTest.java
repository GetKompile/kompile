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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class ProcessDiscoveryServiceImplTest {

    @Mock
    private KnowledgeGraphService kgService;

    private ProcessDiscoveryServiceImpl discoveryService;

    @BeforeEach
    void setUp() {
        discoveryService = new ProcessDiscoveryServiceImpl(kgService);
    }

    // --- Helper methods ---

    private GraphNode personNode(String nodeId, String title) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .nodeType(NodeLevel.ENTITY)
                .externalId("ext-" + nodeId)
                .title(title)
                .metadataJson("{\"entity_type\":\"PERSON\"}")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private GraphNode sheetNode(String nodeId, String title) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .nodeType(NodeLevel.TABLE)
                .externalId("ext-" + nodeId)
                .title(title)
                .metadataJson("{\"entity_subtype\":\"sheet\"}")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private GraphNode formulaCellNode(String nodeId, String title) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .nodeType(NodeLevel.ENTITY)
                .externalId("ext-" + nodeId)
                .title(title)
                .metadataJson("{\"entity_subtype\":\"formula_cell\"}")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private GraphNode inputCellNode(String nodeId, String title) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .nodeType(NodeLevel.ENTITY)
                .externalId("ext-" + nodeId)
                .title(title)
                .metadataJson("{\"entity_subtype\":\"input_cell\"}")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private GraphNode documentNode(String nodeId, String title) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .nodeType(NodeLevel.DOCUMENT)
                .externalId("ext-" + nodeId)
                .title(title)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private GraphNode topicNode(String nodeId, String title) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .nodeType(NodeLevel.ENTITY)
                .externalId("ext-" + nodeId)
                .title(title)
                .metadataJson("{\"entity_type\":\"TOPIC\"}")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private GraphNode formFieldNode(String nodeId, String title) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .nodeType(NodeLevel.ENTITY)
                .externalId("ext-" + nodeId)
                .title(title)
                .metadataJson("{\"entity_type\":\"FORM_FIELD\"}")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private GraphEdge edge(GraphNode source, GraphNode target, String label) {
        return GraphEdge.builder()
                .edgeId("edge-" + source.getNodeId() + "-" + target.getNodeId())
                .sourceNode(source)
                .targetNode(target)
                .edgeType(EdgeType.USER_DEFINED)
                .weight(0.8)
                .label(label)
                .description(label + " relationship")
                .createdAt(LocalDateTime.now())
                .build();
    }

    // ───────────────────────────────────────────────────────────────────────
    // Email Flow Analysis
    // ───────────────────────────────────────────────────────────────────────

    @Nested
    class AnalyzeEmailFlows {

        @Test
        void discoversEmailFlowFromRecurringSenderRecipientPairs() {
            GraphNode alice = personNode("alice", "Alice");
            GraphNode bob = personNode("bob", "Bob");

            when(kgService.getNode("alice")).thenReturn(Optional.of(alice));
            when(kgService.getConnectedNodes("alice", 1)).thenReturn(List.of());

            // 3 emails from alice -> bob (threshold is 2)
            GraphEdge e1 = edge(alice, bob, "SENT_TO");
            GraphEdge e2 = edge(alice, bob, "SENT_TO");
            GraphEdge e3 = edge(alice, bob, "SENT_TO");
            when(kgService.getEdgesForNode("alice")).thenReturn(List.of(e1, e2, e3));

            List<FlowPattern> patterns = discoveryService.analyzeEmailFlows(List.of("alice"));

            assertFalse(patterns.isEmpty());
            FlowPattern p = patterns.get(0);
            assertEquals("EMAIL_FLOW", p.getType());
            assertEquals(3, p.getOccurrenceCount());
            // confidence = min(0.5 + 3*0.1, 0.9) = 0.8
            assertEquals(0.8, p.getConfidence(), 0.001);
            assertFalse(p.getSteps().isEmpty());
            assertTrue(p.getInvolvedNodeIds().contains("alice"));
            assertTrue(p.getInvolvedNodeIds().contains("bob"));
        }

        @Test
        void noPatternForSingleEmailPair() {
            GraphNode alice = personNode("alice", "Alice");

            when(kgService.getNode("alice")).thenReturn(Optional.of(alice));
            when(kgService.getConnectedNodes("alice", 1)).thenReturn(List.of());

            // Only 1 email — below threshold
            GraphNode bob = personNode("bob", "Bob");
            when(kgService.getEdgesForNode("alice")).thenReturn(
                    List.of(edge(alice, bob, "SENT_TO")));

            List<FlowPattern> patterns = discoveryService.analyzeEmailFlows(List.of("alice"));
            assertTrue(patterns.isEmpty());
        }

        @Test
        void confidenceCappedAt09() {
            GraphNode alice = personNode("alice", "Alice");
            GraphNode bob = personNode("bob", "Bob");

            when(kgService.getNode("alice")).thenReturn(Optional.of(alice));
            when(kgService.getConnectedNodes("alice", 1)).thenReturn(List.of());

            // 10 emails: confidence = min(0.5 + 10*0.1, 0.9) = 0.9 (capped)
            List<GraphEdge> edges = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                edges.add(edge(alice, bob, "SENT_BY"));
            }
            when(kgService.getEdgesForNode("alice")).thenReturn(edges);

            List<FlowPattern> patterns = discoveryService.analyzeEmailFlows(List.of("alice"));

            assertFalse(patterns.isEmpty());
            assertEquals(0.9, patterns.get(0).getConfidence(), 0.001);
        }

        @Test
        void discoversConnectedPersonNodes() {
            // Main node is not a person, but connects to persons
            GraphNode emailNode = GraphNode.builder()
                    .nodeId("email1")
                    .nodeType(NodeLevel.DOCUMENT)
                    .externalId("ext-email1")
                    .title("Email")
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            GraphNode alice = personNode("alice", "Alice");
            GraphNode bob = personNode("bob", "Bob");

            when(kgService.getNode("email1")).thenReturn(Optional.of(emailNode));
            when(kgService.getConnectedNodes("email1", 1)).thenReturn(List.of(alice, bob));
            when(kgService.getEdgesForNode("alice")).thenReturn(List.of(
                    edge(alice, bob, "SENT_TO"), edge(alice, bob, "SENT_TO")));
            when(kgService.getEdgesForNode("bob")).thenReturn(List.of());

            List<FlowPattern> patterns = discoveryService.analyzeEmailFlows(List.of("email1"));

            assertFalse(patterns.isEmpty());
        }

        @Test
        void nullGraphNodeIdsFallsBackToSearch() {
            GraphNode alice = personNode("alice", "Alice");
            GraphNode bob = personNode("bob", "Bob");

            when(kgService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(new ArrayList<>(List.of(alice, bob)));
            when(kgService.getEdgesForNode("alice")).thenReturn(List.of(
                    edge(alice, bob, "SENT_TO"), edge(alice, bob, "SENT_TO")));
            when(kgService.getEdgesForNode("bob")).thenReturn(List.of());

            List<FlowPattern> patterns = discoveryService.analyzeEmailFlows(null);

            assertFalse(patterns.isEmpty());
        }

        @Test
        void detectsPersonByEntitySubtype() {
            GraphNode person = GraphNode.builder()
                    .nodeId("p1")
                    .nodeType(NodeLevel.ENTITY)
                    .externalId("ext-p1")
                    .title("Person via subtype")
                    .metadataJson("{\"entity_subtype\":\"person\"}")
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            when(kgService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(new ArrayList<>(List.of(person)));
            when(kgService.getEdgesForNode("p1")).thenReturn(List.of());

            // Should not crash; person recognized by subtype
            List<FlowPattern> patterns = discoveryService.analyzeEmailFlows(null);
            assertNotNull(patterns);
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // Excel Flow Analysis
    // ───────────────────────────────────────────────────────────────────────

    @Nested
    class AnalyzeExcelFlows {

        @Test
        void discoversExcelComputationWorkflow() {
            GraphNode sheet = sheetNode("sheet1", "Revenue Sheet");
            GraphNode formula1 = formulaCellNode("f1", "SUM formula");
            GraphNode formula2 = formulaCellNode("f2", "AVG formula");
            GraphNode input1 = inputCellNode("i1", "Input 1");
            GraphNode input2 = inputCellNode("i2", "Input 2");

            when(kgService.getNode("sheet1")).thenReturn(Optional.of(sheet));
            when(kgService.getChildren("sheet1"))
                    .thenReturn(List.of(formula1, formula2, input1, input2));

            List<FlowPattern> patterns = discoveryService.analyzeExcelFlows(List.of("sheet1"));

            assertEquals(1, patterns.size());
            FlowPattern p = patterns.get(0);
            assertEquals("EXCEL_COMPUTATION", p.getType());
            // confidence = 0.7 + min(2*0.05, 0.2) = 0.8
            assertEquals(0.8, p.getConfidence(), 0.001);

            // Should have 3 steps: input, compute, review
            assertEquals(3, p.getSteps().size());
            assertEquals("INPUT", p.getSteps().get(0).getAction());
            assertEquals("COMPUTE", p.getSteps().get(1).getAction());
            assertEquals("APPROVE", p.getSteps().get(2).getAction());
        }

        @Test
        void noPatternWhenNoFormulaCells() {
            GraphNode sheet = sheetNode("sheet1", "Data Sheet");
            GraphNode input1 = inputCellNode("i1", "Input 1");

            when(kgService.getNode("sheet1")).thenReturn(Optional.of(sheet));
            when(kgService.getChildren("sheet1")).thenReturn(List.of(input1));

            List<FlowPattern> patterns = discoveryService.analyzeExcelFlows(List.of("sheet1"));

            assertTrue(patterns.isEmpty());
        }

        @Test
        void formulaOnlySheetSkipsInputStep() {
            GraphNode sheet = sheetNode("sheet1", "Calc Sheet");
            GraphNode formula = formulaCellNode("f1", "Formula");

            when(kgService.getNode("sheet1")).thenReturn(Optional.of(sheet));
            when(kgService.getChildren("sheet1")).thenReturn(List.of(formula));

            List<FlowPattern> patterns = discoveryService.analyzeExcelFlows(List.of("sheet1"));

            assertEquals(1, patterns.size());
            // Only compute + review steps (no input step since no input cells)
            assertEquals(2, patterns.get(0).getSteps().size());
            assertEquals("COMPUTE", patterns.get(0).getSteps().get(0).getAction());
        }

        @Test
        void excelConfidenceCappedAt09() {
            GraphNode sheet = sheetNode("sheet1", "Complex Sheet");

            // 10 formula cells: 0.7 + min(10*0.05, 0.2) = 0.9
            List<GraphNode> cells = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                cells.add(formulaCellNode("f" + i, "Formula " + i));
            }

            when(kgService.getNode("sheet1")).thenReturn(Optional.of(sheet));
            when(kgService.getChildren("sheet1")).thenReturn(cells);

            List<FlowPattern> patterns = discoveryService.analyzeExcelFlows(List.of("sheet1"));

            assertEquals(0.9, patterns.get(0).getConfidence(), 0.001);
        }

        @Test
        void searchesSheetsWhenNodeIdsNull() {
            GraphNode sheet = sheetNode("s1", "Sheet1");
            GraphNode formula = formulaCellNode("f1", "Formula");

            when(kgService.searchNodes("sheet", NodeLevel.TABLE, 100))
                    .thenReturn(new ArrayList<>(List.of(sheet)));
            when(kgService.getChildren("s1")).thenReturn(List.of(formula));

            List<FlowPattern> patterns = discoveryService.analyzeExcelFlows(null);
            assertFalse(patterns.isEmpty());
        }

        @Test
        void checksChildrenForSheetNodes() {
            // Parent is a workbook, child is a sheet
            GraphNode workbook = GraphNode.builder()
                    .nodeId("wb1")
                    .nodeType(NodeLevel.DOCUMENT)
                    .externalId("ext-wb1")
                    .title("Workbook")
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            GraphNode sheet = sheetNode("s1", "Sheet1");
            GraphNode formula = formulaCellNode("f1", "Formula");

            when(kgService.getNode("wb1")).thenReturn(Optional.of(workbook));
            when(kgService.getChildren("wb1")).thenReturn(List.of(sheet));
            when(kgService.getChildren("s1")).thenReturn(List.of(formula));

            List<FlowPattern> patterns = discoveryService.analyzeExcelFlows(List.of("wb1"));
            assertFalse(patterns.isEmpty());
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // Document Flow Analysis
    // ───────────────────────────────────────────────────────────────────────

    @Nested
    class AnalyzeDocumentFlows {

        @Test
        void discoversAuthorPipeline() {
            GraphNode doc1 = documentNode("d1", "Report Q1");
            GraphNode doc2 = documentNode("d2", "Report Q2");
            GraphNode author = personNode("author1", "Jane Smith");

            when(kgService.getNode("d1")).thenReturn(Optional.of(doc1));
            when(kgService.getNode("d2")).thenReturn(Optional.of(doc2));
            when(kgService.getNode("author1")).thenReturn(Optional.of(author));
            when(kgService.getChildren("d1")).thenReturn(List.of());
            when(kgService.getChildren("d2")).thenReturn(List.of());

            when(kgService.getEdgesForNode("d1")).thenReturn(List.of(
                    edge(doc1, author, "AUTHORED_BY")));
            when(kgService.getEdgesForNode("d2")).thenReturn(List.of(
                    edge(doc2, author, "AUTHORED_BY")));

            when(kgService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                    .thenReturn(new ArrayList<>(List.of(doc1, doc2)));

            List<FlowPattern> patterns = discoveryService.analyzeDocumentFlows(null);

            assertTrue(patterns.stream().anyMatch(p -> "DOCUMENT_AUTHORING".equals(p.getType())));
            FlowPattern authoring = patterns.stream()
                    .filter(p -> "DOCUMENT_AUTHORING".equals(p.getType()))
                    .findFirst().orElseThrow();
            assertTrue(authoring.getDescription().contains("Jane Smith"));
        }

        @Test
        void discoversVersionChainWorkflow() {
            GraphNode v1 = documentNode("v1", "Contract Draft");
            GraphNode v2 = documentNode("v2", "Contract Final");

            when(kgService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                    .thenReturn(new ArrayList<>(List.of(v1, v2)));
            when(kgService.getNode("v1")).thenReturn(Optional.of(v1));
            when(kgService.getNode("v2")).thenReturn(Optional.of(v2));
            when(kgService.getChildren("v1")).thenReturn(List.of());
            when(kgService.getChildren("v2")).thenReturn(List.of());

            when(kgService.getEdgesForNode("v1")).thenReturn(List.of(
                    edge(v1, v2, "VERSION_OF")));
            when(kgService.getEdgesForNode("v2")).thenReturn(List.of(
                    edge(v1, v2, "VERSION_OF")));

            List<FlowPattern> patterns = discoveryService.analyzeDocumentFlows(null);

            assertTrue(patterns.stream().anyMatch(p -> "DOCUMENT_REVIEW_CYCLE".equals(p.getType())));
        }

        @Test
        void discoversTopicClusterPipeline() {
            GraphNode doc1 = documentNode("d1", "ML Paper 1");
            GraphNode doc2 = documentNode("d2", "ML Paper 2");
            GraphNode doc3 = documentNode("d3", "ML Paper 3");
            GraphNode topic = topicNode("t1", "Machine Learning");

            when(kgService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                    .thenReturn(new ArrayList<>(List.of(doc1, doc2, doc3)));
            when(kgService.getNode("t1")).thenReturn(Optional.of(topic));

            // Each document has the topic as a child entity
            when(kgService.getChildren("d1")).thenReturn(List.of(topic));
            when(kgService.getChildren("d2")).thenReturn(List.of(topic));
            when(kgService.getChildren("d3")).thenReturn(List.of(topic));
            when(kgService.getEdgesForNode(anyString())).thenReturn(List.of());

            List<FlowPattern> patterns = discoveryService.analyzeDocumentFlows(null);

            assertTrue(patterns.stream().anyMatch(p -> "TOPIC_CLUSTER_PIPELINE".equals(p.getType())));
            FlowPattern cluster = patterns.stream()
                    .filter(p -> "TOPIC_CLUSTER_PIPELINE".equals(p.getType()))
                    .findFirst().orElseThrow();
            assertTrue(cluster.getDescription().contains("Machine Learning"));
            assertEquals(3, cluster.getOccurrenceCount());
        }

        @Test
        void topicClusterRequires3Documents() {
            GraphNode doc1 = documentNode("d1", "Paper 1");
            GraphNode doc2 = documentNode("d2", "Paper 2");
            GraphNode topic = topicNode("t1", "Topic");

            when(kgService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                    .thenReturn(new ArrayList<>(List.of(doc1, doc2)));
            when(kgService.getChildren("d1")).thenReturn(List.of(topic));
            when(kgService.getChildren("d2")).thenReturn(List.of(topic));
            when(kgService.getEdgesForNode(anyString())).thenReturn(List.of());

            List<FlowPattern> patterns = discoveryService.analyzeDocumentFlows(null);

            assertTrue(patterns.stream().noneMatch(p -> "TOPIC_CLUSTER_PIPELINE".equals(p.getType())));
        }

        @Test
        void discoversFormCollectionWorkflow() {
            GraphNode formDoc = documentNode("form1", "Application Form");
            GraphNode field1 = formFieldNode("ff1", "Name");
            GraphNode field2 = formFieldNode("ff2", "Date of Birth");
            GraphNode field3 = formFieldNode("ff3", "Address");

            when(kgService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                    .thenReturn(new ArrayList<>(List.of(formDoc)));
            when(kgService.getChildren("form1")).thenReturn(List.of(field1, field2, field3));
            when(kgService.getEdgesForNode("form1")).thenReturn(List.of());

            List<FlowPattern> patterns = discoveryService.analyzeDocumentFlows(null);

            assertTrue(patterns.stream().anyMatch(p -> "FORM_DATA_COLLECTION".equals(p.getType())));
            FlowPattern form = patterns.stream()
                    .filter(p -> "FORM_DATA_COLLECTION".equals(p.getType()))
                    .findFirst().orElseThrow();
            assertTrue(form.getDescription().contains("3 fields"));
            assertEquals(3, form.getSteps().size());
            assertEquals("SEND", form.getSteps().get(0).getAction());
            assertEquals("INPUT", form.getSteps().get(1).getAction());
            assertEquals("APPROVE", form.getSteps().get(2).getAction());
        }

        @Test
        void formCollectionRequires2Fields() {
            GraphNode formDoc = documentNode("form1", "Simple Form");
            GraphNode field1 = formFieldNode("ff1", "Name");

            when(kgService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                    .thenReturn(new ArrayList<>(List.of(formDoc)));
            when(kgService.getChildren("form1")).thenReturn(List.of(field1));
            when(kgService.getEdgesForNode("form1")).thenReturn(List.of());

            List<FlowPattern> patterns = discoveryService.analyzeDocumentFlows(null);

            assertTrue(patterns.stream().noneMatch(p -> "FORM_DATA_COLLECTION".equals(p.getType())));
        }

        @Test
        void emptyDocumentsReturnsEmptyPatterns() {
            when(kgService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                    .thenReturn(new ArrayList<>());

            List<FlowPattern> patterns = discoveryService.analyzeDocumentFlows(null);
            assertTrue(patterns.isEmpty());
        }

        @Test
        void documentFlowsWithSpecificNodeIds() {
            GraphNode doc = documentNode("d1", "Test Doc");
            GraphNode field1 = formFieldNode("ff1", "Field 1");
            GraphNode field2 = formFieldNode("ff2", "Field 2");

            when(kgService.getNode("d1")).thenReturn(Optional.of(doc));
            when(kgService.getChildren("d1")).thenReturn(List.of(field1, field2));
            when(kgService.getEdgesForNode("d1")).thenReturn(List.of());

            List<FlowPattern> patterns = discoveryService.analyzeDocumentFlows(List.of("d1"));

            assertTrue(patterns.stream().anyMatch(p -> "FORM_DATA_COLLECTION".equals(p.getType())));
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // discoverProcesses (integration of all analyzers)
    // ───────────────────────────────────────────────────────────────────────

    @Nested
    class DiscoverProcesses {

        @Test
        void combinesAllFlowTypes() {
            // Set up an email flow
            GraphNode alice = personNode("alice", "Alice");
            GraphNode bob = personNode("bob", "Bob");
            when(kgService.getNode("alice")).thenReturn(Optional.of(alice));
            when(kgService.getConnectedNodes("alice", 1)).thenReturn(List.of());
            when(kgService.getEdgesForNode("alice")).thenReturn(List.of(
                    edge(alice, bob, "SENT_TO"), edge(alice, bob, "SENT_TO")));

            // Set up an excel flow
            GraphNode sheet = sheetNode("s1", "Sheet");
            GraphNode formula = formulaCellNode("f1", "Formula");
            when(kgService.getNode("s1")).thenReturn(Optional.of(sheet));
            when(kgService.getChildren("s1")).thenReturn(List.of(formula));

            // Document flows (form)
            GraphNode formDoc = documentNode("form1", "Form");
            GraphNode ff1 = formFieldNode("ff1", "F1");
            GraphNode ff2 = formFieldNode("ff2", "F2");
            when(kgService.getNode("form1")).thenReturn(Optional.of(formDoc));
            when(kgService.getChildren("form1")).thenReturn(List.of(ff1, ff2));
            when(kgService.getEdgesForNode("form1")).thenReturn(List.of());

            List<ProcessSuggestion> suggestions = discoveryService.discoverProcesses(
                    List.of("alice", "s1", "form1"), Map.of());

            assertFalse(suggestions.isEmpty());
        }

        @Test
        void filtersbyMinConfidence() {
            GraphNode alice = personNode("alice", "Alice");
            GraphNode bob = personNode("bob", "Bob");
            when(kgService.getNode("alice")).thenReturn(Optional.of(alice));
            when(kgService.getConnectedNodes("alice", 1)).thenReturn(List.of());
            // 2 edges: confidence = 0.5 + 2*0.1 = 0.7
            when(kgService.getEdgesForNode("alice")).thenReturn(List.of(
                    edge(alice, bob, "SENT_TO"), edge(alice, bob, "SENT_TO")));

            // min confidence 0.8 should filter out 0.7 flow
            List<ProcessSuggestion> suggestions = discoveryService.discoverProcesses(
                    List.of("alice"), Map.of("minConfidence", 0.8));

            assertTrue(suggestions.isEmpty());
        }

        @Test
        void resultsSortedByConfidenceDescending() {
            // Set up flows with different confidences
            GraphNode alice = personNode("alice", "Alice");
            GraphNode bob = personNode("bob", "Bob");
            when(kgService.getNode("alice")).thenReturn(Optional.of(alice));
            when(kgService.getConnectedNodes("alice", 1)).thenReturn(List.of());
            // 2 edges: confidence 0.7
            when(kgService.getEdgesForNode("alice")).thenReturn(List.of(
                    edge(alice, bob, "SENT_TO"), edge(alice, bob, "SENT_TO")));

            GraphNode sheet = sheetNode("s1", "Sheet");
            List<GraphNode> manyCells = new ArrayList<>();
            for (int i = 0; i < 5; i++) manyCells.add(formulaCellNode("f" + i, "F"));
            when(kgService.getNode("s1")).thenReturn(Optional.of(sheet));
            when(kgService.getChildren("s1")).thenReturn(manyCells);

            List<ProcessSuggestion> suggestions = discoveryService.discoverProcesses(
                    List.of("alice", "s1"), Map.of("minConfidence", 0.0));

            if (suggestions.size() >= 2) {
                assertTrue(suggestions.get(0).getConfidence() >= suggestions.get(1).getConfidence());
            }
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // acceptSuggestion
    // ───────────────────────────────────────────────────────────────────────

    @Nested
    class AcceptSuggestion {

        @Test
        void convertsToProcessDefinitionInDraftStatus() {
            ProcessSuggestion suggestion = ProcessSuggestion.builder()
                    .name("Invoice Processing")
                    .description("Automated invoice flow")
                    .discoverySource("EMAIL_FLOW")
                    .confidence(0.8)
                    .phases(List.of(
                            ProcessSuggestion.SuggestedPhase.builder()
                                    .name("Phase 1")
                                    .description("Initial phase")
                                    .steps(List.of(
                                            ProcessSuggestion.SuggestedStep.builder()
                                                    .name("Receive invoice")
                                                    .stepType("AUTO")
                                                    .description("Auto-receive invoice email")
                                                    .graphNodeIds(List.of("node1"))
                                                    .build(),
                                            ProcessSuggestion.SuggestedStep.builder()
                                                    .name("Review invoice")
                                                    .stepType("APPROVE")
                                                    .description("Human approves invoice")
                                                    .suggestedAssignee("finance-team")
                                                    .build()
                                    ))
                                    .build()
                    ))
                    .sourceGraphNodeIds(List.of("node1", "node2"))
                    .evidence(List.of("3 occurrences observed"))
                    .build();

            ProcessDefinition def = discoveryService.acceptSuggestion(suggestion);

            assertEquals("Invoice Processing", def.getName());
            assertEquals(ProcessStatus.DRAFT, def.getStatus());
            assertTrue(def.getId().startsWith("discovered-"));
            assertEquals(1, def.getVersion());
            assertEquals(1, def.getPhases().size());
            assertEquals(2, def.getPhases().get(0).getSteps().size());
            assertEquals("1.1", def.getPhases().get(0).getSteps().get(0).getId());
            assertEquals("1.2", def.getPhases().get(0).getSteps().get(1).getId());

            // Metadata carries discovery provenance
            assertEquals("EMAIL_FLOW", def.getMetadata().get("discoverySource"));
            assertEquals(0.8, def.getMetadata().get("discoveryConfidence"));
        }

        @Test
        void handlesInvalidStepTypeGracefully() {
            ProcessSuggestion suggestion = ProcessSuggestion.builder()
                    .name("Test")
                    .description("test")
                    .discoverySource("TEST")
                    .confidence(0.5)
                    .phases(List.of(
                            ProcessSuggestion.SuggestedPhase.builder()
                                    .name("Phase")
                                    .steps(List.of(
                                            ProcessSuggestion.SuggestedStep.builder()
                                                    .name("Step")
                                                    .stepType("INVALID_TYPE")
                                                    .description("step")
                                                    .build()
                                    ))
                                    .build()
                    ))
                    .build();

            ProcessDefinition def = discoveryService.acceptSuggestion(suggestion);

            // Invalid type falls back to AUTO
            assertEquals("AUTO", def.getPhases().get(0).getSteps().get(0).getStepType().name());
        }

        @Test
        void multiplePhasesSerialized() {
            ProcessSuggestion suggestion = ProcessSuggestion.builder()
                    .name("Multi-phase")
                    .description("test")
                    .discoverySource("TEST")
                    .confidence(0.6)
                    .phases(List.of(
                            ProcessSuggestion.SuggestedPhase.builder()
                                    .name("Phase 1")
                                    .steps(List.of(
                                            ProcessSuggestion.SuggestedStep.builder()
                                                    .name("Step A").stepType("AUTO").description("a").build()
                                    ))
                                    .build(),
                            ProcessSuggestion.SuggestedPhase.builder()
                                    .name("Phase 2")
                                    .steps(List.of(
                                            ProcessSuggestion.SuggestedStep.builder()
                                                    .name("Step B").stepType("HUMAN").description("b").build(),
                                            ProcessSuggestion.SuggestedStep.builder()
                                                    .name("Step C").stepType("APPROVE").description("c").build()
                                    ))
                                    .build()
                    ))
                    .build();

            ProcessDefinition def = discoveryService.acceptSuggestion(suggestion);

            assertEquals(2, def.getPhases().size());
            assertEquals("phase-1", def.getPhases().get(0).getId());
            assertEquals("phase-2", def.getPhases().get(1).getId());
            assertEquals(1, def.getPhases().get(0).getSteps().size());
            assertEquals(2, def.getPhases().get(1).getSteps().size());
            assertEquals("2.1", def.getPhases().get(1).getSteps().get(0).getId());
            assertEquals("2.2", def.getPhases().get(1).getSteps().get(1).getId());
        }
    }

    // ───────────────────────────────────────────────────────────────────────
    // flowToSuggestion (tested indirectly via discoverProcesses output)
    // ───────────────────────────────────────────────────────────────────────

    @Test
    void flowToSuggestionMapsActionTypesCorrectly() {
        // Set up a form collection flow — it has SEND, INPUT, APPROVE steps
        GraphNode formDoc = documentNode("form1", "Application Form");
        GraphNode ff1 = formFieldNode("ff1", "Name");
        GraphNode ff2 = formFieldNode("ff2", "Email");

        when(kgService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                .thenReturn(new ArrayList<>(List.of(formDoc)));
        when(kgService.getChildren("form1")).thenReturn(List.of(ff1, ff2));
        when(kgService.getEdgesForNode("form1")).thenReturn(List.of());

        List<ProcessSuggestion> suggestions = discoveryService.discoverProcesses(null, Map.of());

        assertFalse(suggestions.isEmpty());
        ProcessSuggestion s = suggestions.get(0);
        List<ProcessSuggestion.SuggestedStep> steps = s.getPhases().get(0).getSteps();

        // SEND -> AUTO, INPUT -> HUMAN, APPROVE -> APPROVE
        assertEquals("AUTO", steps.get(0).getStepType());
        assertEquals("HUMAN", steps.get(1).getStepType());
        assertEquals("APPROVE", steps.get(2).getStepType());
    }
}
