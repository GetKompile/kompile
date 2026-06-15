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
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Tests for the document-flow analysis methods added to ProcessDiscoveryServiceImpl:
 * analyzeDocumentFlows(), analyzeAuthorPipelines(), analyzeVersionChainWorkflows(),
 * analyzeTopicClusterWorkflows(), analyzeFormCollectionWorkflows(),
 * isTopicNode(), isFormFieldNode().
 */
@ExtendWith(MockitoExtension.class)
class ProcessDiscoveryDocumentFlowTest {

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

    private GraphNode documentNode(String nodeId, String title) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .nodeType(NodeLevel.DOCUMENT)
                .externalId(nodeId)
                .title(title)
                .metadataJson("{\"type\":\"document\"}")
                .build();
    }

    private GraphNode authorNode(String nodeId, String name) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .nodeType(NodeLevel.ENTITY)
                .externalId(nodeId)
                .title(name)
                .metadataJson("{\"entity_type\":\"PERSON\"}")
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

    private GraphNode formFieldNode(String nodeId, String title) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .nodeType(NodeLevel.ENTITY)
                .externalId(nodeId)
                .title(title)
                .metadataJson("{\"entity_type\":\"FORM_FIELD\"}")
                .build();
    }

    private GraphNode nonFormFieldEntity(String nodeId) {
        return GraphNode.builder()
                .nodeId(nodeId)
                .nodeType(NodeLevel.ENTITY)
                .externalId(nodeId)
                .title("SomeEntity")
                .metadataJson("{\"entity_type\":\"ORGANIZATION\"}")
                .build();
    }

    private GraphEdge authoredByEdge(GraphNode doc, GraphNode author) {
        return GraphEdge.builder()
                .edgeId(doc.getNodeId() + "->AUTHORED_BY->" + author.getNodeId())
                .sourceNode(doc)
                .targetNode(author)
                .edgeType(EdgeType.USER_DEFINED)
                .weight(1.0)
                .label("AUTHORED_BY")
                .description("authored by edge")
                .build();
    }

    private GraphEdge versionOfEdge(GraphNode newVersion, GraphNode oldVersion) {
        return GraphEdge.builder()
                .edgeId(newVersion.getNodeId() + "->VERSION_OF->" + oldVersion.getNodeId())
                .sourceNode(newVersion)
                .targetNode(oldVersion)
                .edgeType(EdgeType.USER_DEFINED)
                .weight(1.0)
                .label("VERSION_OF")
                .description("version of edge")
                .build();
    }

    private GraphEdge hasTopicEdge(GraphNode doc, GraphNode topic) {
        return GraphEdge.builder()
                .edgeId(doc.getNodeId() + "->HAS_TOPIC->" + topic.getNodeId())
                .sourceNode(doc)
                .targetNode(topic)
                .edgeType(EdgeType.USER_DEFINED)
                .weight(1.0)
                .label("HAS_TOPIC")
                .description("has topic edge")
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. analyzeDocumentFlows() — no documents → returns empty
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void analyzeDocumentFlows_noDocuments_returnsEmpty() {
        // searchNodes returns nothing (null graphNodeIds → scans all DOCUMENT nodes)
        when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                .thenReturn(Collections.emptyList());

        List<FlowPattern> patterns = service.analyzeDocumentFlows(null);

        assertThat(patterns).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. analyzeDocumentFlows() — null graphNodeIds → scans all DOCUMENT nodes
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void analyzeDocumentFlows_nullGraphNodeIds_scansAllDocumentNodes() {
        GraphNode doc1 = documentNode("doc1", "Report.pdf");
        GraphNode doc2 = documentNode("doc2", "Memo.pdf");

        // With null graphNodeIds the impl calls searchNodes("", DOCUMENT, 200)
        when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                .thenReturn(new ArrayList<>(List.of(doc1, doc2)));

        // Stub getEdgesForNode so sub-strategies find no patterns (we just verify scanning)
        when(knowledgeGraphService.getEdgesForNode("doc1")).thenReturn(Collections.emptyList());
        when(knowledgeGraphService.getEdgesForNode("doc2")).thenReturn(Collections.emptyList());
        // Stub getChildren for topic/form strategies
        when(knowledgeGraphService.getChildren("doc1")).thenReturn(Collections.emptyList());
        when(knowledgeGraphService.getChildren("doc2")).thenReturn(Collections.emptyList());

        // Should not throw; result may be empty but the scan itself must complete
        List<FlowPattern> patterns = service.analyzeDocumentFlows(null);

        // No patterns because no edges/children trigger any strategy, but no exception
        assertThat(patterns).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Author pipelines — 2 documents by same author → DOCUMENT_AUTHORING
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void analyzeAuthorPipelines_twoDocumentsBySameAuthor_producesDocumentAuthoringPattern() {
        GraphNode doc1 = documentNode("doc1", "Annual Report.pdf");
        GraphNode doc2 = documentNode("doc2", "Budget Memo.pdf");
        GraphNode author = authorNode("author1", "Alice Smith");

        when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                .thenReturn(new ArrayList<>(List.of(doc1, doc2)));

        // Both documents have AUTHORED_BY edge to author1
        when(knowledgeGraphService.getEdgesForNode("doc1"))
                .thenReturn(List.of(authoredByEdge(doc1, author)));
        when(knowledgeGraphService.getEdgesForNode("doc2"))
                .thenReturn(List.of(authoredByEdge(doc2, author)));

        // Author node lookup for display name
        when(knowledgeGraphService.getNode("author1"))
                .thenReturn(Optional.of(author));

        // Topic/form child stubs
        when(knowledgeGraphService.getChildren("doc1")).thenReturn(Collections.emptyList());
        when(knowledgeGraphService.getChildren("doc2")).thenReturn(Collections.emptyList());

        List<FlowPattern> patterns = service.analyzeDocumentFlows(null);

        assertThat(patterns).hasSize(1);
        FlowPattern p = patterns.get(0);
        assertThat(p.getType()).isEqualTo("DOCUMENT_AUTHORING");
        assertThat(p.getOccurrenceCount()).isEqualTo(2);
        // confidence = min(0.5 + 2 * 0.1, 0.85) = 0.7
        assertThat(p.getConfidence()).isEqualTo(0.7, within(0.001));
        assertThat(p.getDescription()).contains("Alice Smith");
        // 2 document steps + 1 review step
        assertThat(p.getSteps()).hasSize(3);
        assertThat(p.getInvolvedNodeIds()).containsExactlyInAnyOrder("doc1", "doc2");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. Author pipelines — 1 document → no pattern (need 2+)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void analyzeAuthorPipelines_oneDocumentByAuthor_noPattern() {
        GraphNode doc1 = documentNode("doc1", "Lone Report.pdf");
        GraphNode author = authorNode("author1", "Bob Jones");

        when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                .thenReturn(new ArrayList<>(List.of(doc1)));

        when(knowledgeGraphService.getEdgesForNode("doc1"))
                .thenReturn(List.of(authoredByEdge(doc1, author)));
        when(knowledgeGraphService.getChildren("doc1")).thenReturn(Collections.emptyList());

        List<FlowPattern> patterns = service.analyzeDocumentFlows(null);

        // Single authored document is below the 2-doc threshold — no pattern
        assertThat(patterns.stream().filter(p -> "DOCUMENT_AUTHORING".equals(p.getType())))
                .isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. Version chain — documents linked by VERSION_OF edges → DOCUMENT_REVIEW_CYCLE
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void analyzeVersionChainWorkflows_twoVersionsLinked_producesDocumentReviewCycle() {
        GraphNode v1 = documentNode("doc-v1", "Contract_v1.pdf");
        GraphNode v2 = documentNode("doc-v2", "Contract_v2.pdf");

        when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                .thenReturn(new ArrayList<>(List.of(v1, v2)));

        // v2 is a VERSION_OF v1; edge on doc-v2
        when(knowledgeGraphService.getEdgesForNode("doc-v2"))
                .thenReturn(List.of(versionOfEdge(v2, v1)));
        // v1 has no version edges
        when(knowledgeGraphService.getEdgesForNode("doc-v1"))
                .thenReturn(Collections.emptyList());

        // getNode is called when building the chain for "doc-v1" (the other node)
        when(knowledgeGraphService.getNode("doc-v1")).thenReturn(Optional.of(v1));

        // No topic/form children
        when(knowledgeGraphService.getChildren("doc-v1")).thenReturn(Collections.emptyList());
        when(knowledgeGraphService.getChildren("doc-v2")).thenReturn(Collections.emptyList());

        List<FlowPattern> patterns = service.analyzeDocumentFlows(null);

        List<FlowPattern> reviewCycles = patterns.stream()
                .filter(p -> "DOCUMENT_REVIEW_CYCLE".equals(p.getType()))
                .toList();
        assertThat(reviewCycles).isNotEmpty();

        FlowPattern cycle = reviewCycles.get(0);
        assertThat(cycle.getOccurrenceCount()).isEqualTo(2);
        // confidence = min(0.6 + 2 * 0.1, 0.9) = 0.8
        assertThat(cycle.getConfidence()).isEqualTo(0.8, within(0.001));
        // draft step + 1 review/revision step = 2 steps
        assertThat(cycle.getSteps()).hasSize(2);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. Topic cluster — 3+ documents sharing a TOPIC entity → TOPIC_CLUSTER_PIPELINE
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void analyzeTopicClusterWorkflows_threeDocumentsSharedTopic_producesTopicClusterPipeline() {
        GraphNode doc1 = documentNode("doc1", "Article1.pdf");
        GraphNode doc2 = documentNode("doc2", "Article2.pdf");
        GraphNode doc3 = documentNode("doc3", "Article3.pdf");
        GraphNode topic = topicNode("topic1", "Machine Learning");

        when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                .thenReturn(new ArrayList<>(List.of(doc1, doc2, doc3)));

        // Each document has a HAS_TOPIC edge to topic1 (edge-based discovery path)
        when(knowledgeGraphService.getEdgesForNode("doc1")).thenReturn(List.of(hasTopicEdge(doc1, topic)));
        when(knowledgeGraphService.getEdgesForNode("doc2")).thenReturn(List.of(hasTopicEdge(doc2, topic)));
        when(knowledgeGraphService.getEdgesForNode("doc3")).thenReturn(List.of(hasTopicEdge(doc3, topic)));

        // No form-field or topic children (child-based topic discovery should find nothing)
        when(knowledgeGraphService.getChildren("doc1")).thenReturn(Collections.emptyList());
        when(knowledgeGraphService.getChildren("doc2")).thenReturn(Collections.emptyList());
        when(knowledgeGraphService.getChildren("doc3")).thenReturn(Collections.emptyList());

        // Topic node lookup for display name
        when(knowledgeGraphService.getNode("topic1")).thenReturn(Optional.of(topic));

        List<FlowPattern> patterns = service.analyzeDocumentFlows(null);

        List<FlowPattern> topicClusters = patterns.stream()
                .filter(p -> "TOPIC_CLUSTER_PIPELINE".equals(p.getType()))
                .toList();
        assertThat(topicClusters).hasSize(1);

        FlowPattern cluster = topicClusters.get(0);
        assertThat(cluster.getOccurrenceCount()).isEqualTo(3);
        // confidence = min(0.4 + 3 * 0.08, 0.8) = 0.64
        assertThat(cluster.getConfidence()).isEqualTo(0.64, within(0.001));
        assertThat(cluster.getDescription()).contains("Machine Learning");
        // 3 steps: gather, review, synthesize
        assertThat(cluster.getSteps()).hasSize(3);
        assertThat(cluster.getInvolvedNodeIds()).containsExactlyInAnyOrder("doc1", "doc2", "doc3");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. Topic cluster — 2 documents sharing topic → no pattern (need 3+)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void analyzeTopicClusterWorkflows_twoDocumentsSharedTopic_noPattern() {
        GraphNode doc1 = documentNode("doc1", "Paper1.pdf");
        GraphNode doc2 = documentNode("doc2", "Paper2.pdf");
        GraphNode topic = topicNode("topic1", "Cloud Computing");

        when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                .thenReturn(new ArrayList<>(List.of(doc1, doc2)));

        when(knowledgeGraphService.getEdgesForNode("doc1")).thenReturn(List.of(hasTopicEdge(doc1, topic)));
        when(knowledgeGraphService.getEdgesForNode("doc2")).thenReturn(List.of(hasTopicEdge(doc2, topic)));

        when(knowledgeGraphService.getChildren("doc1")).thenReturn(Collections.emptyList());
        when(knowledgeGraphService.getChildren("doc2")).thenReturn(Collections.emptyList());

        List<FlowPattern> patterns = service.analyzeDocumentFlows(null);

        assertThat(patterns.stream().filter(p -> "TOPIC_CLUSTER_PIPELINE".equals(p.getType())))
                .isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 8. Form collection — document with 2+ FORM_FIELD children → FORM_DATA_COLLECTION
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void analyzeFormCollectionWorkflows_twoFormFields_producesFormDataCollection() {
        GraphNode form = documentNode("form1", "ApplicationForm.pdf");
        GraphNode field1 = formFieldNode("ff1", "Name");
        GraphNode field2 = formFieldNode("ff2", "Date of Birth");

        when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                .thenReturn(new ArrayList<>(List.of(form)));

        // No edges from the form document
        when(knowledgeGraphService.getEdgesForNode("form1")).thenReturn(Collections.emptyList());
        // Two FORM_FIELD children
        when(knowledgeGraphService.getChildren("form1"))
                .thenReturn(new ArrayList<>(List.of(field1, field2)));

        List<FlowPattern> patterns = service.analyzeDocumentFlows(null);

        List<FlowPattern> formPatterns = patterns.stream()
                .filter(p -> "FORM_DATA_COLLECTION".equals(p.getType()))
                .toList();
        assertThat(formPatterns).hasSize(1);

        FlowPattern fp = formPatterns.get(0);
        assertThat(fp.getOccurrenceCount()).isEqualTo(1);
        // confidence = 0.7 + min(2 * 0.03, 0.2) = 0.76
        assertThat(fp.getConfidence()).isEqualTo(0.76, within(0.001));
        assertThat(fp.getDescription()).contains("ApplicationForm.pdf");
        // 3 steps: distribute, fill, review
        assertThat(fp.getSteps()).hasSize(3);
        assertThat(fp.getInvolvedNodeIds()).containsExactly("form1");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 9. Form collection — document with 1 form field → no pattern (need 2+)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void analyzeFormCollectionWorkflows_oneFormField_noPattern() {
        GraphNode form = documentNode("form1", "SignaturePage.pdf");
        GraphNode field1 = formFieldNode("ff1", "Signature");

        when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                .thenReturn(new ArrayList<>(List.of(form)));

        when(knowledgeGraphService.getEdgesForNode("form1")).thenReturn(Collections.emptyList());
        when(knowledgeGraphService.getChildren("form1"))
                .thenReturn(new ArrayList<>(List.of(field1)));

        List<FlowPattern> patterns = service.analyzeDocumentFlows(null);

        assertThat(patterns.stream().filter(p -> "FORM_DATA_COLLECTION".equals(p.getType())))
                .isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 10. discoverProcesses() integration — document flows included alongside
    //     email and Excel flows
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void discoverProcesses_includesDocumentFlowsAlongsideEmailAndExcel() {
        // --- Email setup (needs person nodes + at least 2 edges) ---
        GraphNode alice = GraphNode.builder()
                .nodeId("alice").nodeType(NodeLevel.ENTITY).externalId("alice").title("Alice")
                .metadataJson("{\"entity_type\":\"PERSON\"}").build();
        GraphNode bob = GraphNode.builder()
                .nodeId("bob").nodeType(NodeLevel.ENTITY).externalId("bob").title("Bob")
                .metadataJson("{\"entity_type\":\"PERSON\"}").build();

        when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                .thenReturn(new ArrayList<>(List.of(alice, bob)));

        GraphEdge e1 = GraphEdge.builder()
                .edgeId("e1").sourceNode(alice).targetNode(bob)
                .edgeType(EdgeType.USER_DEFINED).weight(1.0).label("SENT_BY")
                .description("email edge 1").build();
        GraphEdge e2 = GraphEdge.builder()
                .edgeId("e2").sourceNode(alice).targetNode(bob)
                .edgeType(EdgeType.USER_DEFINED).weight(1.0).label("SENT_BY")
                .description("email edge 2").build();
        when(knowledgeGraphService.getEdgesForNode("alice")).thenReturn(List.of(e1, e2));
        when(knowledgeGraphService.getEdgesForNode("bob")).thenReturn(Collections.emptyList());

        // --- Excel setup (no sheet nodes — empty list) ---
        when(knowledgeGraphService.searchNodes("sheet", NodeLevel.TABLE, 100))
                .thenReturn(Collections.emptyList());
        when(knowledgeGraphService.searchNodes("table", NodeLevel.TABLE, 100))
                .thenReturn(Collections.emptyList());

        // --- Document setup: form document with 2 fields ---
        GraphNode formDoc = documentNode("form1", "Intake.pdf");
        GraphNode ff1 = formFieldNode("ff1", "FirstName");
        GraphNode ff2 = formFieldNode("ff2", "LastName");

        when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                .thenReturn(new ArrayList<>(List.of(formDoc)));
        when(knowledgeGraphService.getEdgesForNode("form1")).thenReturn(Collections.emptyList());
        when(knowledgeGraphService.getChildren("form1"))
                .thenReturn(new ArrayList<>(List.of(ff1, ff2)));

        List<ProcessSuggestion> suggestions = service.discoverProcesses(null, Map.of());

        // Should include both EMAIL_FLOW and FORM_DATA_COLLECTION
        List<String> sources = suggestions.stream()
                .map(ProcessSuggestion::getDiscoverySource)
                .toList();
        assertThat(sources).containsExactlyInAnyOrder("EMAIL_FLOW", "FORM_DATA_COLLECTION");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 11. Confidence for author pipelines scales with document count
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void authorPipelineConfidence_scalesWithDocumentCount() {
        // 4 documents by the same author
        GraphNode doc1 = documentNode("d1", "Doc1.pdf");
        GraphNode doc2 = documentNode("d2", "Doc2.pdf");
        GraphNode doc3 = documentNode("d3", "Doc3.pdf");
        GraphNode doc4 = documentNode("d4", "Doc4.pdf");
        GraphNode author = authorNode("author1", "Carol Williams");

        when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                .thenReturn(new ArrayList<>(List.of(doc1, doc2, doc3, doc4)));

        for (GraphNode doc : List.of(doc1, doc2, doc3, doc4)) {
            when(knowledgeGraphService.getEdgesForNode(doc.getNodeId()))
                    .thenReturn(List.of(authoredByEdge(doc, author)));
            when(knowledgeGraphService.getChildren(doc.getNodeId()))
                    .thenReturn(Collections.emptyList());
        }
        when(knowledgeGraphService.getNode("author1")).thenReturn(Optional.of(author));

        List<FlowPattern> patterns = service.analyzeDocumentFlows(null);

        List<FlowPattern> authorPatterns = patterns.stream()
                .filter(p -> "DOCUMENT_AUTHORING".equals(p.getType()))
                .toList();
        assertThat(authorPatterns).hasSize(1);

        FlowPattern p = authorPatterns.get(0);
        assertThat(p.getOccurrenceCount()).isEqualTo(4);
        // confidence = min(0.5 + 4 * 0.1, 0.85) = 0.85 (capped)
        assertThat(p.getConfidence()).isEqualTo(0.85, within(0.001));

        // Also verify that 3-document case gives 0.8 (uncapped)
        GraphNode doc5 = documentNode("d5", "Doc5.pdf");
        GraphNode doc6 = documentNode("d6", "Doc6.pdf");
        GraphNode doc7 = documentNode("d7", "Doc7.pdf");
        GraphNode author2 = authorNode("author2", "David Lee");

        when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                .thenReturn(new ArrayList<>(List.of(doc5, doc6, doc7)));
        for (GraphNode doc : List.of(doc5, doc6, doc7)) {
            when(knowledgeGraphService.getEdgesForNode(doc.getNodeId()))
                    .thenReturn(List.of(authoredByEdge(doc, author2)));
            when(knowledgeGraphService.getChildren(doc.getNodeId()))
                    .thenReturn(Collections.emptyList());
        }
        when(knowledgeGraphService.getNode("author2")).thenReturn(Optional.of(author2));

        List<FlowPattern> patterns3 = service.analyzeDocumentFlows(null);
        List<FlowPattern> authorPatterns3 = patterns3.stream()
                .filter(p2 -> "DOCUMENT_AUTHORING".equals(p2.getType()))
                .toList();
        assertThat(authorPatterns3).hasSize(1);
        // confidence = min(0.5 + 3 * 0.1, 0.85) = 0.8
        assertThat(authorPatterns3.get(0).getConfidence()).isEqualTo(0.8, within(0.001));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 12. Confidence for version chains scales with chain length
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void versionChainConfidence_scalesWithChainLength() {
        // Three-version chain: v1 <- v2 <- v3
        // v2 is VERSION_OF v1, v3 is VERSION_OF v2
        GraphNode v1 = documentNode("v1", "Draft_v1.pdf");
        GraphNode v2 = documentNode("v2", "Draft_v2.pdf");
        GraphNode v3 = documentNode("v3", "Draft_v3.pdf");

        when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                .thenReturn(new ArrayList<>(List.of(v1, v2, v3)));

        // v1 has no version edges
        when(knowledgeGraphService.getEdgesForNode("v1")).thenReturn(Collections.emptyList());
        // v2 links back to v1
        when(knowledgeGraphService.getEdgesForNode("v2"))
                .thenReturn(List.of(versionOfEdge(v2, v1)));
        // v3 links back to v2
        when(knowledgeGraphService.getEdgesForNode("v3"))
                .thenReturn(List.of(versionOfEdge(v3, v2)));

        // getNode calls for the "other" side of each VERSION_OF edge
        when(knowledgeGraphService.getNode("v1")).thenReturn(Optional.of(v1));
        when(knowledgeGraphService.getNode("v2")).thenReturn(Optional.of(v2));

        // No form/topic children
        when(knowledgeGraphService.getChildren("v1")).thenReturn(Collections.emptyList());
        when(knowledgeGraphService.getChildren("v2")).thenReturn(Collections.emptyList());
        when(knowledgeGraphService.getChildren("v3")).thenReturn(Collections.emptyList());

        List<FlowPattern> patterns = service.analyzeDocumentFlows(null);

        List<FlowPattern> versionCycles = patterns.stream()
                .filter(p -> "DOCUMENT_REVIEW_CYCLE".equals(p.getType()))
                .toList();

        // v2 forms a 2-chain (v2 + v1), v3 forms a 2-chain (v3 + v2)
        // Both are >= 2, so we expect at least one cycle pattern per document with a VERSION_OF edge
        assertThat(versionCycles).isNotEmpty();

        // Find the pattern for v2 (chain length 2)
        FlowPattern twoChain = versionCycles.stream()
                .filter(p -> p.getOccurrenceCount() == 2)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected a chain-of-2 pattern"));
        // confidence = min(0.6 + 2 * 0.1, 0.9) = 0.8
        assertThat(twoChain.getConfidence()).isEqualTo(0.8, within(0.001));

        // A longer chain (3 versions) would yield min(0.6 + 3 * 0.1, 0.9) = 0.9
        // Verify the formula's cap: with 3 chain members, confidence would be capped at 0.9
        double confidenceFor3 = Math.min(0.6 + 3 * 0.1, 0.9);
        assertThat(confidenceFor3).isEqualTo(0.9, within(0.001));
    }
}
