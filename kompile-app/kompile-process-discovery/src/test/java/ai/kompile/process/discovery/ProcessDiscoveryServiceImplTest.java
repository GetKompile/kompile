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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessDiscoveryServiceImplTest {

    @Mock
    private KnowledgeGraphService knowledgeGraphService;

    private ProcessDiscoveryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProcessDiscoveryServiceImpl(knowledgeGraphService);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper builders
    // ─────────────────────────────────────────────────────────────────────────

    private GraphNode personNode(String nodeId, String title) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .nodeType(NodeLevel.ENTITY)
                .externalId(nodeId)
                .title(title)
                .metadataJson("{\"entity_type\":\"PERSON\"}")
                .build();
    }

    private GraphNode personNodeBySubtype(String nodeId, String title) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .nodeType(NodeLevel.ENTITY)
                .externalId(nodeId)
                .title(title)
                .metadataJson("{\"entity_subtype\":\"person\"}")
                .build();
    }

    private GraphNode nonPersonEntityNode(String nodeId) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .nodeType(NodeLevel.ENTITY)
                .externalId(nodeId)
                .title("Some org")
                .metadataJson("{\"entity_type\":\"ORGANIZATION\"}")
                .build();
    }

    private GraphNode sheetNode(String nodeId, String title) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .nodeType(NodeLevel.TABLE)
                .externalId(nodeId)
                .title(title)
                .metadataJson("{\"entity_subtype\":\"sheet\"}")
                .build();
    }

    private GraphNode formulaCellNode(String nodeId) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .nodeType(NodeLevel.ENTITY)
                .externalId(nodeId)
                .title("FormulaCell")
                .metadataJson("{\"entity_subtype\":\"formula_cell\"}")
                .build();
    }

    private GraphNode inputCellNode(String nodeId) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .nodeType(NodeLevel.ENTITY)
                .externalId(nodeId)
                .title("InputCell")
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
                .description("email edge")
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Email flow tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void analyzeEmailFlows_findsPersonNodes_byEntityType() {
        GraphNode alice = personNode("alice", "Alice");
        GraphNode bob = personNode("bob", "Bob");

        // KG search returns both person nodes (must be mutable — impl calls removeIf)
        when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                .thenReturn(new ArrayList<>(List.of(alice, bob)));

        // Alice has 3 SENT_BY edges to Bob — should produce a pattern
        GraphEdge e1 = edge(alice, bob, "SENT_BY");
        GraphEdge e2 = edge(alice, bob, "SENT_BY");
        GraphEdge e3 = edge(alice, bob, "SENT_BY");

        when(knowledgeGraphService.getEdgesForNode("alice")).thenReturn(List.of(e1, e2, e3));
        when(knowledgeGraphService.getEdgesForNode("bob")).thenReturn(Collections.emptyList());

        List<FlowPattern> patterns = service.analyzeEmailFlows(null);

        assertThat(patterns).hasSize(1);
        FlowPattern p = patterns.get(0);
        assertThat(p.getType()).isEqualTo("EMAIL_FLOW");
        assertThat(p.getOccurrenceCount()).isEqualTo(3);
        // confidence = min(0.5 + 3 * 0.1, 0.9) = 0.8
        assertThat(p.getConfidence()).isEqualTo(0.8, org.assertj.core.api.Assertions.within(0.001));
    }

    @Test
    void analyzeEmailFlows_emptyWhenNoPersonNodes() {
        // KG returns only a non-person entity (mutable — impl calls removeIf)
        when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                .thenReturn(new ArrayList<>(List.of(nonPersonEntityNode("org1"))));

        List<FlowPattern> patterns = service.analyzeEmailFlows(null);

        assertThat(patterns).isEmpty();
    }

    @Test
    void analyzeEmailFlows_requiresMinTwoOccurrences() {
        GraphNode alice = personNode("alice", "Alice");
        GraphNode bob = personNode("bob", "Bob");

        when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                .thenReturn(new ArrayList<>(List.of(alice, bob)));

        // Only 1 edge — below the minimum threshold of 2
        GraphEdge e1 = edge(alice, bob, "SENT_BY");
        when(knowledgeGraphService.getEdgesForNode("alice")).thenReturn(List.of(e1));
        when(knowledgeGraphService.getEdgesForNode("bob")).thenReturn(Collections.emptyList());

        List<FlowPattern> patterns = service.analyzeEmailFlows(null);

        assertThat(patterns).isEmpty();
    }

    @Test
    void analyzeEmailFlows_detectsCcBccRepliedForwardedEdges() {
        GraphNode alice = personNode("alice", "Alice");
        GraphNode bob = personNode("bob", "Bob");

        when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                .thenReturn(new ArrayList<>(List.of(alice, bob)));

        // Mix of CC_TO, REPLIED_TO, and FORWARDED_TO edges — all should count
        GraphEdge e1 = edge(alice, bob, "CC_TO");
        GraphEdge e2 = edge(alice, bob, "REPLIED_TO");
        GraphEdge e3 = edge(alice, bob, "FORWARDED_TO");

        when(knowledgeGraphService.getEdgesForNode("alice")).thenReturn(List.of(e1, e2, e3));
        when(knowledgeGraphService.getEdgesForNode("bob")).thenReturn(Collections.emptyList());

        List<FlowPattern> patterns = service.analyzeEmailFlows(null);

        assertThat(patterns).hasSize(1);
        FlowPattern p = patterns.get(0);
        assertThat(p.getType()).isEqualTo("EMAIL_FLOW");
        assertThat(p.getOccurrenceCount()).isEqualTo(3);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Excel flow tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void analyzeExcelFlows_findsSheetNodes() {
        GraphNode sheet = sheetNode("sheet1", "Budget Sheet");

        when(knowledgeGraphService.searchNodes("sheet", NodeLevel.TABLE, 100))
                .thenReturn(new ArrayList<>(List.of(sheet)));
        when(knowledgeGraphService.searchNodes("table", NodeLevel.TABLE, 100))
                .thenReturn(Collections.emptyList());

        GraphNode formula1 = formulaCellNode("fc1");
        GraphNode formula2 = formulaCellNode("fc2");
        GraphNode input1 = inputCellNode("ic1");

        // 2 formula cells + 1 input cell as children (mutable — impl calls stream filter)
        when(knowledgeGraphService.getChildren("sheet1"))
                .thenReturn(new ArrayList<>(List.of(formula1, formula2, input1)));

        List<FlowPattern> patterns = service.analyzeExcelFlows(null);

        assertThat(patterns).hasSize(1);
        FlowPattern p = patterns.get(0);
        assertThat(p.getType()).isEqualTo("EXCEL_COMPUTATION");
        // steps: input + compute + review = 3
        assertThat(p.getSteps()).hasSize(3);
    }

    @Test
    void analyzeExcelFlows_emptyWhenNoFormulas() {
        GraphNode sheet = sheetNode("sheet2", "Plain Sheet");

        when(knowledgeGraphService.searchNodes("sheet", NodeLevel.TABLE, 100))
                .thenReturn(new ArrayList<>(List.of(sheet)));
        when(knowledgeGraphService.searchNodes("table", NodeLevel.TABLE, 100))
                .thenReturn(Collections.emptyList());

        // Only input cells, no formula cells
        GraphNode input1 = inputCellNode("ic1");
        when(knowledgeGraphService.getChildren("sheet2"))
                .thenReturn(new ArrayList<>(List.of(input1)));

        List<FlowPattern> patterns = service.analyzeExcelFlows(null);

        assertThat(patterns).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // discoverProcesses tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void discoverProcesses_combinesEmailAndExcel() {
        // Email setup — 2 person nodes, 2 edges between them
        GraphNode alice = personNode("alice", "Alice");
        GraphNode bob = personNode("bob", "Bob");
        when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                .thenReturn(new ArrayList<>(List.of(alice, bob)));
        GraphEdge e1 = edge(alice, bob, "SENT_TO");
        GraphEdge e2 = edge(alice, bob, "SENT_TO");
        when(knowledgeGraphService.getEdgesForNode("alice")).thenReturn(List.of(e1, e2));
        when(knowledgeGraphService.getEdgesForNode("bob")).thenReturn(Collections.emptyList());

        // Excel setup — 1 sheet with formula cells
        GraphNode sheet = sheetNode("sheet1", "Budget");
        when(knowledgeGraphService.searchNodes("sheet", NodeLevel.TABLE, 100))
                .thenReturn(new ArrayList<>(List.of(sheet)));
        when(knowledgeGraphService.searchNodes("table", NodeLevel.TABLE, 100))
                .thenReturn(Collections.emptyList());
        when(knowledgeGraphService.getChildren("sheet1"))
                .thenReturn(new ArrayList<>(List.of(formulaCellNode("fc1"))));

        List<ProcessSuggestion> suggestions = service.discoverProcesses(null, Map.of());

        assertThat(suggestions).hasSize(2);
        // Sorted by confidence descending — Excel formula = 0.75 > email 2 edges = 0.7
        List<String> sources = suggestions.stream()
                .map(ProcessSuggestion::getDiscoverySource)
                .toList();
        assertThat(sources).containsExactlyInAnyOrder("EMAIL_FLOW", "EXCEL_COMPUTATION");
    }

    @Test
    void discoverProcesses_respectsMinConfidence() {
        // 2 edges → confidence = 0.7; set minConfidence = 0.8 → filtered out
        GraphNode alice = personNode("alice", "Alice");
        GraphNode bob = personNode("bob", "Bob");
        when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                .thenReturn(new ArrayList<>(List.of(alice, bob)));
        GraphEdge e1 = edge(alice, bob, "SENT_BY");
        GraphEdge e2 = edge(alice, bob, "SENT_BY");
        when(knowledgeGraphService.getEdgesForNode("alice")).thenReturn(List.of(e1, e2));
        when(knowledgeGraphService.getEdgesForNode("bob")).thenReturn(Collections.emptyList());

        when(knowledgeGraphService.searchNodes("sheet", NodeLevel.TABLE, 100))
                .thenReturn(Collections.emptyList());
        when(knowledgeGraphService.searchNodes("table", NodeLevel.TABLE, 100))
                .thenReturn(Collections.emptyList());

        List<ProcessSuggestion> suggestions = service.discoverProcesses(null, Map.of("minConfidence", 0.8));

        assertThat(suggestions).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // acceptSuggestion test
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void acceptSuggestion_convertsToProcessDefinition() {
        ProcessSuggestion.SuggestedStep step1 = ProcessSuggestion.SuggestedStep.builder()
                .name("Receive email")
                .stepType("AUTO")
                .description("Receive incoming email")
                .graphNodeIds(List.of("node-1"))
                .build();

        ProcessSuggestion.SuggestedStep step2 = ProcessSuggestion.SuggestedStep.builder()
                .name("Human review")
                .stepType("HUMAN")
                .description("Review email contents")
                .graphNodeIds(List.of("node-2"))
                .build();

        ProcessSuggestion.SuggestedStep step3 = ProcessSuggestion.SuggestedStep.builder()
                .name("Approve")
                .stepType("APPROVE")
                .description("Approve the process")
                .build();

        ProcessSuggestion.SuggestedPhase phase = ProcessSuggestion.SuggestedPhase.builder()
                .name("Email Processing")
                .description("Process incoming emails")
                .steps(List.of(step1, step2, step3))
                .build();

        ProcessSuggestion suggestion = ProcessSuggestion.builder()
                .name("Email Review Process")
                .description("Recurring email review workflow")
                .discoverySource("EMAIL_FLOW")
                .confidence(0.8)
                .phases(List.of(phase))
                .sourceGraphNodeIds(List.of("node-1", "node-2"))
                .evidence(List.of("3 occurrences observed"))
                .build();

        ProcessDefinition result = service.acceptSuggestion(suggestion);

        // Status must be DRAFT
        assertThat(result.getStatus()).isEqualTo(ProcessStatus.DRAFT);

        // One phase
        assertThat(result.getPhases()).hasSize(1);
        ProcessPhase resultPhase = result.getPhases().get(0);
        assertThat(resultPhase.getName()).isEqualTo("Email Processing");
        assertThat(resultPhase.getId()).isEqualTo("phase-1");

        // Three steps
        assertThat(resultPhase.getSteps()).hasSize(3);

        ProcessStep s1 = resultPhase.getSteps().get(0);
        assertThat(s1.getId()).isEqualTo("1.1");
        assertThat(s1.getName()).isEqualTo("Receive email");
        assertThat(s1.getStepType()).isEqualTo(StepType.AUTO);

        ProcessStep s2 = resultPhase.getSteps().get(1);
        assertThat(s2.getId()).isEqualTo("1.2");
        assertThat(s2.getStepType()).isEqualTo(StepType.HUMAN);

        ProcessStep s3 = resultPhase.getSteps().get(2);
        assertThat(s3.getId()).isEqualTo("1.3");
        assertThat(s3.getStepType()).isEqualTo(StepType.APPROVE);

        // Metadata must contain discoverySource
        assertThat(result.getMetadata()).containsKey("discoverySource");
        assertThat(result.getMetadata().get("discoverySource")).isEqualTo("EMAIL_FLOW");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // isPersonNode format test
    // ─────────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────────
    // analyzeDocumentFlows tests
    // ─────────────────────────────────────────────────────────────────────────

    private GraphNode documentNode(String nodeId, String title) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .nodeType(NodeLevel.DOCUMENT)
                .externalId(nodeId)
                .title(title)
                .metadataJson("{}")
                .build();
    }

    private GraphNode topicNode(String nodeId, String title) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .nodeType(NodeLevel.ENTITY)
                .externalId(nodeId)
                .title(title)
                .metadataJson("{\"entity_type\":\"TOPIC\"}")
                .build();
    }

    private GraphNode formFieldNode(String nodeId) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .nodeType(NodeLevel.ENTITY)
                .externalId(nodeId)
                .title("Field " + nodeId)
                .metadataJson("{\"entity_type\":\"FORM_FIELD\"}")
                .build();
    }

    @Test
    void analyzeDocumentFlows_emptyWhenNoDocuments() {
        when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                .thenReturn(new ArrayList<>());

        List<FlowPattern> patterns = service.analyzeDocumentFlows(null);
        assertThat(patterns).isEmpty();
    }

    @Test
    void analyzeDocumentFlows_detectsAuthorPipeline() {
        GraphNode doc1 = documentNode("doc1", "Report Q1");
        GraphNode doc2 = documentNode("doc2", "Report Q2");
        GraphNode author = personNode("author1", "Alice Smith");

        when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                .thenReturn(new ArrayList<>(List.of(doc1, doc2)));

        // Both documents have AUTHORED_BY edge to the same author
        when(knowledgeGraphService.getEdgesForNode("doc1"))
                .thenReturn(List.of(edge(doc1, author, "AUTHORED_BY")));
        when(knowledgeGraphService.getEdgesForNode("doc2"))
                .thenReturn(List.of(edge(doc2, author, "AUTHORED_BY")));

        when(knowledgeGraphService.getNode("author1")).thenReturn(Optional.of(author));
        // No children for the documents (no topics, no form fields)
        when(knowledgeGraphService.getChildren("doc1")).thenReturn(Collections.emptyList());
        when(knowledgeGraphService.getChildren("doc2")).thenReturn(Collections.emptyList());

        List<FlowPattern> patterns = service.analyzeDocumentFlows(null);

        assertThat(patterns).isNotEmpty();
        FlowPattern authorPattern = patterns.stream()
                .filter(p -> "DOCUMENT_AUTHORING".equals(p.getType()))
                .findFirst().orElse(null);
        assertThat(authorPattern).isNotNull();
        assertThat(authorPattern.getDescription()).contains("Alice Smith");
        assertThat(authorPattern.getOccurrenceCount()).isEqualTo(2);
    }

    @Test
    void analyzeDocumentFlows_detectsVersionChain() {
        GraphNode doc1 = documentNode("docA", "Contract v1");
        GraphNode doc2 = documentNode("docB", "Contract v2");

        when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                .thenReturn(new ArrayList<>(List.of(doc1, doc2)));

        // VERSION_OF edge between the two
        when(knowledgeGraphService.getEdgesForNode("docA"))
                .thenReturn(List.of(edge(doc1, doc2, "VERSION_OF")));
        when(knowledgeGraphService.getEdgesForNode("docB"))
                .thenReturn(List.of(edge(doc1, doc2, "VERSION_OF")));

        when(knowledgeGraphService.getNode("docB")).thenReturn(Optional.of(doc2));
        when(knowledgeGraphService.getNode("docA")).thenReturn(Optional.of(doc1));
        when(knowledgeGraphService.getChildren("docA")).thenReturn(Collections.emptyList());
        when(knowledgeGraphService.getChildren("docB")).thenReturn(Collections.emptyList());

        List<FlowPattern> patterns = service.analyzeDocumentFlows(null);

        FlowPattern versionPattern = patterns.stream()
                .filter(p -> "DOCUMENT_REVIEW_CYCLE".equals(p.getType()))
                .findFirst().orElse(null);
        assertThat(versionPattern).isNotNull();
        assertThat(versionPattern.getDescription()).contains("Contract v1");
        assertThat(versionPattern.getSteps().size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void analyzeDocumentFlows_detectsTopicCluster() {
        GraphNode doc1 = documentNode("td1", "Analysis A");
        GraphNode doc2 = documentNode("td2", "Analysis B");
        GraphNode doc3 = documentNode("td3", "Analysis C");
        GraphNode topic = topicNode("topic1", "Market Analysis");

        when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                .thenReturn(new ArrayList<>(List.of(doc1, doc2, doc3)));

        // All 3 docs share a HAS_TOPIC edge to the same topic
        when(knowledgeGraphService.getEdgesForNode("td1"))
                .thenReturn(List.of(edge(doc1, topic, "HAS_TOPIC")));
        when(knowledgeGraphService.getEdgesForNode("td2"))
                .thenReturn(List.of(edge(doc2, topic, "HAS_TOPIC")));
        when(knowledgeGraphService.getEdgesForNode("td3"))
                .thenReturn(List.of(edge(doc3, topic, "HAS_TOPIC")));

        when(knowledgeGraphService.getNode("topic1")).thenReturn(Optional.of(topic));
        when(knowledgeGraphService.getChildren("td1")).thenReturn(Collections.emptyList());
        when(knowledgeGraphService.getChildren("td2")).thenReturn(Collections.emptyList());
        when(knowledgeGraphService.getChildren("td3")).thenReturn(Collections.emptyList());

        List<FlowPattern> patterns = service.analyzeDocumentFlows(null);

        FlowPattern topicPattern = patterns.stream()
                .filter(p -> "TOPIC_CLUSTER_PIPELINE".equals(p.getType()))
                .findFirst().orElse(null);
        assertThat(topicPattern).isNotNull();
        assertThat(topicPattern.getDescription()).contains("Market Analysis");
        assertThat(topicPattern.getOccurrenceCount()).isEqualTo(3);
    }

    @Test
    void analyzeDocumentFlows_detectsFormCollection() {
        GraphNode formDoc = documentNode("form1", "Expense Form");
        GraphNode field1 = formFieldNode("ff1");
        GraphNode field2 = formFieldNode("ff2");
        GraphNode field3 = formFieldNode("ff3");

        when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                .thenReturn(new ArrayList<>(List.of(formDoc)));

        when(knowledgeGraphService.getEdgesForNode("form1")).thenReturn(Collections.emptyList());
        when(knowledgeGraphService.getChildren("form1"))
                .thenReturn(List.of(field1, field2, field3));

        List<FlowPattern> patterns = service.analyzeDocumentFlows(null);

        FlowPattern formPattern = patterns.stream()
                .filter(p -> "FORM_DATA_COLLECTION".equals(p.getType()))
                .findFirst().orElse(null);
        assertThat(formPattern).isNotNull();
        assertThat(formPattern.getDescription()).contains("Expense Form");
        assertThat(formPattern.getSteps()).hasSize(3); // distribute + fill + review
    }

    @Test
    void analyzeDocumentFlows_filteredByGraphNodeIds() {
        GraphNode doc1 = documentNode("d1", "Report");

        when(knowledgeGraphService.getNode("d1")).thenReturn(Optional.of(doc1));
        when(knowledgeGraphService.getChildren("d1")).thenReturn(Collections.emptyList());
        when(knowledgeGraphService.getEdgesForNode("d1")).thenReturn(Collections.emptyList());

        List<FlowPattern> patterns = service.analyzeDocumentFlows(List.of("d1"));

        // Should succeed without calling the global searchNodes
        verify(knowledgeGraphService, never()).searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt());
        assertThat(patterns).isEmpty(); // no patterns from a single doc
    }

    // ─────────────────────────────────────────────────────────────────────────
    // acceptSuggestion edge cases
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void acceptSuggestion_fallbackToAutoForUnknownStepType() {
        ProcessSuggestion.SuggestedStep step = ProcessSuggestion.SuggestedStep.builder()
                .name("Mystery step")
                .stepType("UNKNOWN_TYPE")
                .description("A step with an unrecognized type")
                .build();

        ProcessSuggestion.SuggestedPhase phase = ProcessSuggestion.SuggestedPhase.builder()
                .name("Phase 1")
                .steps(List.of(step))
                .build();

        ProcessSuggestion suggestion = ProcessSuggestion.builder()
                .name("Test Process")
                .confidence(0.5)
                .phases(List.of(phase))
                .discoverySource("TEST")
                .build();

        ProcessDefinition result = service.acceptSuggestion(suggestion);

        assertThat(result.getPhases().get(0).getSteps().get(0).getStepType())
                .isEqualTo(StepType.AUTO);
    }

    @Test
    void acceptSuggestion_persistsViaProcessEngineService() {
        ProcessEngineService mockEngine = mock(ProcessEngineService.class);
        service.setProcessEngineService(mockEngine);

        ProcessSuggestion.SuggestedStep step = ProcessSuggestion.SuggestedStep.builder()
                .name("Auto step")
                .stepType("AUTO")
                .description("Automated step")
                .build();

        ProcessSuggestion suggestion = ProcessSuggestion.builder()
                .name("Persisted Process")
                .confidence(0.8)
                .phases(List.of(ProcessSuggestion.SuggestedPhase.builder()
                        .name("Phase 1").steps(List.of(step)).build()))
                .discoverySource("EMAIL_FLOW")
                .build();

        // Mock createProcess to return the same definition with a modified ID
        when(mockEngine.createProcess(any(ProcessDefinition.class)))
                .thenAnswer(inv -> {
                    ProcessDefinition def = inv.getArgument(0);
                    return ProcessDefinition.builder()
                            .id("persisted-id")
                            .name(def.getName())
                            .version(def.getVersion())
                            .status(def.getStatus())
                            .phases(def.getPhases())
                            .metadata(def.getMetadata())
                            .build();
                });

        ProcessDefinition result = service.acceptSuggestion(suggestion);

        verify(mockEngine).createProcess(any(ProcessDefinition.class));
        assertThat(result.getId()).isEqualTo("persisted-id");
    }

    @Test
    void acceptSuggestion_handlesCreateProcessFailure() {
        ProcessEngineService mockEngine = mock(ProcessEngineService.class);
        service.setProcessEngineService(mockEngine);

        when(mockEngine.createProcess(any(ProcessDefinition.class)))
                .thenThrow(new RuntimeException("DB error"));

        ProcessSuggestion suggestion = ProcessSuggestion.builder()
                .name("Failing Process")
                .confidence(0.6)
                .phases(List.of(ProcessSuggestion.SuggestedPhase.builder()
                        .name("Phase 1")
                        .steps(List.of(ProcessSuggestion.SuggestedStep.builder()
                                .name("Step").stepType("AUTO").build()))
                        .build()))
                .discoverySource("TEST")
                .build();

        // Should not throw — exception is caught and logged
        ProcessDefinition result = service.acceptSuggestion(suggestion);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("Failing Process");
        assertThat(result.getId()).startsWith("discovered-");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // analyzeEmailFlows edge cases
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void analyzeEmailFlows_withGraphNodeIds_findsConnectedPersonNodes() {
        GraphNode alice = personNode("alice", "Alice");
        GraphNode org = nonPersonEntityNode("org1");

        // Requesting analysis for org1 — org1 is not a person, but alice is connected
        when(knowledgeGraphService.getNode("org1")).thenReturn(Optional.of(org));
        when(knowledgeGraphService.getConnectedNodes("org1", 1)).thenReturn(List.of(alice));

        // Alice has edges
        GraphEdge e1 = edge(alice, org, "SENT_BY");
        GraphEdge e2 = edge(alice, org, "SENT_BY");
        when(knowledgeGraphService.getEdgesForNode("alice")).thenReturn(List.of(e1, e2));

        List<FlowPattern> patterns = service.analyzeEmailFlows(List.of("org1"));

        assertThat(patterns).hasSize(1);
    }

    @Test
    void analyzeEmailFlows_recognizesSentToEdges() {
        GraphNode alice = personNode("alice", "Alice");
        GraphNode bob = personNode("bob", "Bob");

        when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                .thenReturn(new ArrayList<>(List.of(alice, bob)));

        // Use SENT_TO instead of SENT_BY
        GraphEdge e1 = edge(alice, bob, "SENT_TO");
        GraphEdge e2 = edge(alice, bob, "SENT_TO");
        when(knowledgeGraphService.getEdgesForNode("alice")).thenReturn(List.of(e1, e2));
        when(knowledgeGraphService.getEdgesForNode("bob")).thenReturn(Collections.emptyList());

        List<FlowPattern> patterns = service.analyzeEmailFlows(null);

        assertThat(patterns).hasSize(1);
        assertThat(patterns.get(0).getType()).isEqualTo("EMAIL_FLOW");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // analyzeExcelFlows edge cases
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void analyzeExcelFlows_withGraphNodeIds_checksChildren() {
        GraphNode parent = GraphNode.builder()
                .nodeId("parent")
                .nodeType(NodeLevel.DOCUMENT)
                .externalId("parent")
                .title("Workbook")
                .build();
        GraphNode sheet = sheetNode("child-sheet", "Data Sheet");

        when(knowledgeGraphService.getNode("parent")).thenReturn(Optional.of(parent));
        when(knowledgeGraphService.getChildren("parent")).thenReturn(List.of(sheet));
        when(knowledgeGraphService.getChildren("child-sheet"))
                .thenReturn(List.of(formulaCellNode("fc1"), inputCellNode("ic1")));

        List<FlowPattern> patterns = service.analyzeExcelFlows(List.of("parent"));

        assertThat(patterns).hasSize(1);
        assertThat(patterns.get(0).getType()).isEqualTo("EXCEL_COMPUTATION");
    }

    @Test
    void analyzeExcelFlows_formulaOnlySheet_hasNoInputStep() {
        GraphNode sheet = sheetNode("sheet-no-input", "Formula Only Sheet");

        when(knowledgeGraphService.searchNodes("sheet", NodeLevel.TABLE, 100))
                .thenReturn(new ArrayList<>(List.of(sheet)));
        when(knowledgeGraphService.searchNodes("table", NodeLevel.TABLE, 100))
                .thenReturn(Collections.emptyList());
        when(knowledgeGraphService.getChildren("sheet-no-input"))
                .thenReturn(new ArrayList<>(List.of(formulaCellNode("fc1"))));

        List<FlowPattern> patterns = service.analyzeExcelFlows(null);

        assertThat(patterns).hasSize(1);
        // With 0 input cells, there should be only 2 steps: compute + review (no input step)
        assertThat(patterns.get(0).getSteps()).hasSize(2);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // isPersonNode format test
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void isPersonNode_matchesBothEntityTypeFormats() {
        // entity_type:PERSON (uppercase — written by ingest)
        GraphNode upperCase = personNode("n1", "Alice");
        // entity_subtype:person (lowercase — older convention)
        GraphNode subtype = personNodeBySubtype("n2", "Bob");
        // Neither — should NOT be found
        GraphNode org = nonPersonEntityNode("n3");

        when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                .thenReturn(new ArrayList<>(List.of(upperCase, subtype, org)));

        // Give them no edges so no patterns are built — we just want to verify
        // both person nodes pass the filter (edge calls for person nodes only).
        when(knowledgeGraphService.getEdgesForNode("n1")).thenReturn(Collections.emptyList());
        when(knowledgeGraphService.getEdgesForNode("n2")).thenReturn(Collections.emptyList());

        // We call analyzeEmailFlows; both person nodes must be in the personNodes list,
        // which means getEdgesForNode is called for them (and NOT for the org node).
        List<FlowPattern> patterns = service.analyzeEmailFlows(null);

        // No patterns because no edges, but no exception means both person nodes were
        // accepted (org node was rejected, so getEdgesForNode is not called for "n3").
        assertThat(patterns).isEmpty();
    }
}
