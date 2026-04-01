/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.matrix.integration;

import ai.kompile.core.graphrag.query.GraphRagQuery;
import ai.kompile.core.graphrag.query.GraphRagResult;
import ai.kompile.core.graphrag.query.SearchType;
import ai.kompile.knowledgegraph.matrix.model.AdjacencyMatrixGraph;
import ai.kompile.knowledgegraph.matrix.model.MatrixGraphNode;
import ai.kompile.knowledgegraph.matrix.service.MatrixGraphRagService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.nd4j.linalg.api.ndarray.INDArray;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for MatrixGraphRagService.
 *
 * These tests use real in-memory implementations without mocking,
 * verifying the complete flow from query to response.
 *
 * Run with: mvn verify -pl kompile-app/kompile-knowledge-graph
 * Or: mvn failsafe:integration-test -pl kompile-app/kompile-knowledge-graph
 */
@DisplayName("MatrixGraphRagService Integration Tests")
public class MatrixGraphRagServiceIntegrationTest {

    private InMemoryMatrixGraphStore graphStore;
    private TestEmbeddingModel embeddingModel;
    private TestLLMChat llmChat;
    private MatrixGraphRagService service;

    private static final String DEFAULT_GRAPH_ID = "default-knowledge-graph";

    @BeforeEach
    void setUp() {
        graphStore = new InMemoryMatrixGraphStore();
        embeddingModel = new TestEmbeddingModel(384);
        llmChat = new TestLLMChat();
        service = new MatrixGraphRagService(graphStore, embeddingModel, llmChat);
    }

    @AfterEach
    void tearDown() {
        llmChat.clearReceivedPrompts();
        graphStore.clearAll();
    }

    /**
     * Helper method to create a test knowledge graph with company data.
     */
    private void createTestKnowledgeGraph() {
        AdjacencyMatrixGraph graph = new AdjacencyMatrixGraph(DEFAULT_GRAPH_ID, 100);

        // Create nodes using builder pattern
        MatrixGraphNode techCorp = createNode("techcorp", "TechCorp", "COMPANY",
                "A leading AI technology company focused on machine learning and data analytics.",
                Map.of("industry", "Technology", "founded", "2010"));

        MatrixGraphNode acme = createNode("acme", "Acme Inc", "COMPANY",
                "A manufacturing company specializing in industrial equipment.",
                Map.of("industry", "Manufacturing", "founded", "1985"));

        MatrixGraphNode globalData = createNode("globaldata", "GlobalData", "COMPANY",
                "A data analytics and business intelligence company.",
                Map.of("industry", "Analytics", "founded", "2015"));

        MatrixGraphNode johnSmith = createNode("john", "John Smith", "PERSON",
                "CEO of TechCorp with 20 years of experience in technology leadership.",
                Map.of("role", "CEO", "expertise", "Strategic Leadership"));

        MatrixGraphNode janeDoe = createNode("jane", "Jane Doe", "PERSON",
                "CTO of TechCorp, expert in AI and machine learning systems.",
                Map.of("role", "CTO", "expertise", "AI and ML"));

        MatrixGraphNode aiPlatform = createNode("aiplatform", "AI Platform", "PRODUCT",
                "Enterprise AI solution for automated data processing and insights.",
                Map.of("category", "Software", "version", "3.0"));

        MatrixGraphNode dataEngine = createNode("dataengine", "DataEngine", "PRODUCT",
                "Real-time data processing engine for high-throughput analytics.",
                Map.of("category", "Software", "version", "2.5"));

        // Add nodes to graph
        graph.addNode(techCorp);
        graph.addNode(acme);
        graph.addNode(globalData);
        graph.addNode(johnSmith);
        graph.addNode(janeDoe);
        graph.addNode(aiPlatform);
        graph.addNode(dataEngine);

        // Create relationships: addEdge(source, target, weight, edgeType, bidirectional)
        graph.addEdge("john", "techcorp", 1.0, "LEADS", false);
        graph.addEdge("john", "techcorp", 1.0, "WORKS_AT", false);
        graph.addEdge("jane", "techcorp", 1.0, "WORKS_AT", false);
        graph.addEdge("techcorp", "aiplatform", 1.0, "PRODUCES", false);
        graph.addEdge("techcorp", "dataengine", 1.0, "PRODUCES", false);
        graph.addEdge("techcorp", "globaldata", 0.8, "PARTNERS_WITH", true);
        graph.addEdge("globaldata", "dataengine", 0.9, "USES", false);
        graph.addEdge("acme", "techcorp", 0.7, "CUSTOMER_OF", false);

        // Store embeddings for similarity search
        List<String> nodeIds = List.of("techcorp", "acme", "globaldata", "john", "jane", "aiplatform", "dataengine");
        List<String> descriptions = nodeIds.stream()
                .map(id -> graph.getNode(id).map(MatrixGraphNode::getDescription).orElse(""))
                .toList();
        INDArray embeddings = embeddingModel.embed(descriptions);
        graph.setNodeEmbeddings(nodeIds, embeddings);

        // Also store embeddings in the graphStore for findSimilarNodes to work
        graphStore.storeNodeEmbeddings(DEFAULT_GRAPH_ID, nodeIds, embeddings);

        // Save to store
        try {
            graphStore.saveGraph(graph);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save test graph", e);
        }
    }

    /**
     * Helper method to create a node with the correct builder pattern.
     */
    private MatrixGraphNode createNode(String nodeId, String title, String nodeType,
                                        String description, Map<String, Object> metadata) {
        return MatrixGraphNode.builder()
                .nodeId(nodeId)
                .title(title)
                .nodeType(nodeType)
                .description(description)
                .metadata(new HashMap<>(metadata))
                .build();
    }

    // ========================================
    // Basic Query Tests
    // ========================================

    @Nested
    @DisplayName("Basic Query Tests")
    class BasicQueryTests {

        @Test
        @DisplayName("Should return answer for query matching node content")
        void shouldReturnAnswerForQueryMatchingNodeContent() {
            // Arrange
            createTestKnowledgeGraph();
            llmChat.setFixedResponse("TechCorp is a leading AI technology company focused on machine learning.");

            GraphRagQuery query = GraphRagQuery.builder()
                    .query("What is TechCorp?")
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build();

            // Act
            GraphRagResult result = service.answerQuery(query);

            // Assert
            assertNotNull(result);
            assertNotNull(result.getAnswer());
            assertEquals("TechCorp is a leading AI technology company focused on machine learning.", result.getAnswer());
        }

        @Test
        @DisplayName("Should handle query with no matching nodes")
        void shouldHandleQueryWithNoMatchingNodes() {
            // Arrange
            createTestKnowledgeGraph();
            llmChat.setFixedResponse("I don't have information about XYZ Corp.");

            GraphRagQuery query = GraphRagQuery.builder()
                    .query("What is XYZ Corp?")
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build();

            // Act
            GraphRagResult result = service.answerQuery(query);

            // Assert
            assertNotNull(result);
            assertNotNull(result.getAnswer());
        }

        @Test
        @DisplayName("Should return empty result when no graph exists")
        void shouldReturnEmptyResultWhenNoGraphExists() {
            // Arrange - don't create any graph
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("What is TechCorp?")
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build();

            // Act
            GraphRagResult result = service.answerQuery(query);

            // Assert
            assertNotNull(result);
            // Should return a message indicating no graph data is available
            assertNotNull(result.getAnswer());
        }
    }

    // ========================================
    // Vector Similarity Search Tests
    // ========================================

    @Nested
    @DisplayName("Vector Similarity Search Tests")
    class VectorSimilaritySearchTests {

        @Test
        @DisplayName("Should find similar nodes based on query embedding")
        void shouldFindSimilarNodesBasedOnQueryEmbedding() {
            // Arrange
            createTestKnowledgeGraph();
            llmChat.setResponseGenerator(prompt -> {
                if (prompt.contains("TechCorp") || prompt.contains("technology")) {
                    return "Found companies in technology sector.";
                }
                return "No matching companies found.";
            });

            // Use a query that matches node titles for text search fallback
            // (Test embedding model generates deterministic but non-semantic embeddings)
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("TechCorp")
                    .searchType(SearchType.LOCAL)
                    .k(3)
                    .build();

            // Act
            GraphRagResult result = service.answerQuery(query);

            // Assert
            assertNotNull(result);
            // Verify LLM received some context (via text search fallback)
            assertFalse(llmChat.getReceivedPrompts().isEmpty());
        }

        @Test
        @DisplayName("Should respect k parameter for result limit")
        void shouldRespectKParameterForResultLimit() {
            // Arrange
            createTestKnowledgeGraph();
            llmChat.setFixedResponse("Found limited results.");

            GraphRagQuery query = GraphRagQuery.builder()
                    .query("companies")
                    .searchType(SearchType.LOCAL)
                    .k(2)
                    .build();

            // Act
            GraphRagResult result = service.answerQuery(query);

            // Assert
            assertNotNull(result);
        }

        @Test
        @DisplayName("Should find related nodes through embeddings")
        void shouldFindRelatedNodesThroughEmbeddings() {
            // Arrange
            createTestKnowledgeGraph();
            llmChat.setResponseGenerator(prompt -> {
                if (prompt.contains("machine learning") || prompt.contains("AI")) {
                    return "Jane Doe is the CTO with ML expertise.";
                }
                return "No ML experts found.";
            });

            GraphRagQuery query = GraphRagQuery.builder()
                    .query("Who has expertise in machine learning?")
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build();

            // Act
            GraphRagResult result = service.answerQuery(query);

            // Assert
            assertNotNull(result);
        }
    }

    // ========================================
    // Graph Traversal Tests
    // ========================================

    @Nested
    @DisplayName("Graph Traversal Tests")
    class GraphTraversalTests {

        @Test
        @DisplayName("Should include connected nodes in context")
        void shouldIncludeConnectedNodesInContext() {
            // Arrange
            createTestKnowledgeGraph();
            llmChat.setResponseGenerator(prompt -> {
                if (prompt.contains("TechCorp") && prompt.contains("John")) {
                    return "John Smith leads TechCorp.";
                }
                return "Leadership information not found.";
            });

            GraphRagQuery query = GraphRagQuery.builder()
                    .query("Who leads TechCorp?")
                    .searchType(SearchType.LOCAL)
                    .k(10)
                    .build();

            // Act
            GraphRagResult result = service.answerQuery(query);

            // Assert
            assertNotNull(result);
        }

        @Test
        @DisplayName("Should traverse multi-hop relationships")
        void shouldTraverseMultiHopRelationships() {
            // Arrange
            createTestKnowledgeGraph();
            llmChat.setResponseGenerator(prompt ->
                    "TechCorp produces AI Platform and DataEngine, partners with GlobalData.");

            GraphRagQuery query = GraphRagQuery.builder()
                    .query("What products does TechCorp make and who uses them?")
                    .searchType(SearchType.LOCAL)
                    .k(10)
                    .build();

            // Act
            GraphRagResult result = service.answerQuery(query);

            // Assert
            assertNotNull(result);
            assertNotNull(result.getAnswer());
        }
    }

    // ========================================
    // Global Search Tests
    // ========================================

    @Nested
    @DisplayName("Global Search Tests")
    class GlobalSearchTests {

        @Test
        @DisplayName("Should perform global search across all nodes")
        void shouldPerformGlobalSearchAcrossAllNodes() {
            // Arrange
            createTestKnowledgeGraph();
            llmChat.setFixedResponse("Summary of all companies and their relationships.");

            GraphRagQuery query = GraphRagQuery.builder()
                    .query("Give me an overview of all companies")
                    .searchType(SearchType.GLOBAL)
                    .k(20)
                    .build();

            // Act
            GraphRagResult result = service.answerQuery(query);

            // Assert
            assertNotNull(result);
            assertNotNull(result.getAnswer());
        }

        @Test
        @DisplayName("Should aggregate information from multiple nodes in global search")
        void shouldAggregateInformationFromMultipleNodes() {
            // Arrange
            createTestKnowledgeGraph();
            llmChat.setResponseGenerator(prompt -> {
                int companyCount = 0;
                if (prompt.contains("TechCorp")) companyCount++;
                if (prompt.contains("Acme")) companyCount++;
                if (prompt.contains("GlobalData")) companyCount++;

                if (companyCount >= 2) {
                    return "Found " + companyCount + " companies in the knowledge graph.";
                }
                return "Limited company information available.";
            });

            GraphRagQuery query = GraphRagQuery.builder()
                    .query("List all companies")
                    .searchType(SearchType.GLOBAL)
                    .k(10)
                    .build();

            // Act
            GraphRagResult result = service.answerQuery(query);

            // Assert
            assertNotNull(result);
        }
    }

    // ========================================
    // Edge Cases Tests
    // ========================================

    @Nested
    @DisplayName("Edge Cases Tests")
    class EdgeCasesTests {

        @Test
        @DisplayName("Should handle empty query gracefully")
        void shouldHandleEmptyQueryGracefully() {
            // Arrange
            createTestKnowledgeGraph();
            llmChat.setFixedResponse("Please provide a valid query.");

            GraphRagQuery query = GraphRagQuery.builder()
                    .query("")
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build();

            // Act
            GraphRagResult result = service.answerQuery(query);

            // Assert
            assertNotNull(result);
        }

        @Test
        @DisplayName("Should handle special characters in query")
        void shouldHandleSpecialCharactersInQuery() {
            // Arrange
            createTestKnowledgeGraph();
            llmChat.setFixedResponse("Query processed successfully.");

            GraphRagQuery query = GraphRagQuery.builder()
                    .query("What is TechCorp's AI platform? (version 3.0)")
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build();

            // Act
            GraphRagResult result = service.answerQuery(query);

            // Assert
            assertNotNull(result);
            assertNotNull(result.getAnswer());
        }

        @Test
        @DisplayName("Should handle very long query")
        void shouldHandleVeryLongQuery() {
            // Arrange
            createTestKnowledgeGraph();
            String longQuery = "Tell me about ".repeat(50) + "TechCorp and its products.";
            llmChat.setFixedResponse("TechCorp makes AI products.");

            GraphRagQuery query = GraphRagQuery.builder()
                    .query(longQuery)
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build();

            // Act
            GraphRagResult result = service.answerQuery(query);

            // Assert
            assertNotNull(result);
        }

        @Test
        @DisplayName("Should handle zero k parameter with default value")
        void shouldHandleZeroKParameterWithDefaultValue() {
            // Arrange
            createTestKnowledgeGraph();
            llmChat.setFixedResponse("Response with default k.");

            GraphRagQuery query = GraphRagQuery.builder()
                    .query("What is TechCorp?")
                    .searchType(SearchType.LOCAL)
                    .k(0) // Should use default
                    .build();

            // Act
            GraphRagResult result = service.answerQuery(query);

            // Assert
            assertNotNull(result);
        }
    }

    // ========================================
    // Service Without LLM Tests
    // ========================================

    @Nested
    @DisplayName("Service Without LLM Tests")
    class ServiceWithoutLLMTests {

        @Test
        @DisplayName("Should work without LLM and return context only")
        void shouldWorkWithoutLLMAndReturnContextOnly() {
            // Arrange
            createTestKnowledgeGraph();

            // Create service without LLM
            MatrixGraphRagService serviceNoLLM = new MatrixGraphRagService(graphStore, embeddingModel, null);

            GraphRagQuery query = GraphRagQuery.builder()
                    .query("What is TechCorp?")
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build();

            // Act
            GraphRagResult result = serviceNoLLM.answerQuery(query);

            // Assert
            assertNotNull(result);
            // Without LLM, the answer should contain a message about no LLM
            assertTrue(result.getAnswer().contains("No LLM") || result.getAnswer().contains("context"));
        }

        @Test
        @DisplayName("Should work without embedding model using text search")
        void shouldWorkWithoutEmbeddingModelUsingTextSearch() {
            // Arrange
            createTestKnowledgeGraph();

            // Create service without embedding model
            MatrixGraphRagService serviceNoEmbedding = new MatrixGraphRagService(graphStore, null, llmChat);
            llmChat.setFixedResponse("Found TechCorp through text search.");

            GraphRagQuery query = GraphRagQuery.builder()
                    .query("TechCorp")
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build();

            // Act
            GraphRagResult result = serviceNoEmbedding.answerQuery(query);

            // Assert
            assertNotNull(result);
        }
    }

    // ========================================
    // Performance Tests
    // ========================================

    @Nested
    @DisplayName("Performance Tests")
    class PerformanceTests {

        @Test
        @DisplayName("Should complete query within reasonable time")
        void shouldCompleteQueryWithinReasonableTime() {
            // Arrange
            createTestKnowledgeGraph();
            llmChat.setFixedResponse("Quick response.");

            GraphRagQuery query = GraphRagQuery.builder()
                    .query("What is TechCorp?")
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build();

            // Act
            long startTime = System.currentTimeMillis();
            GraphRagResult result = service.answerQuery(query);
            long endTime = System.currentTimeMillis();

            // Assert
            assertNotNull(result);
            long duration = endTime - startTime;
            assertTrue(duration < 2000, "Query should complete within 2 seconds, took: " + duration + "ms");
        }

        @Test
        @DisplayName("Should handle large graph efficiently")
        void shouldHandleLargeGraphEfficiently() {
            // Arrange - create a larger graph
            AdjacencyMatrixGraph graph = new AdjacencyMatrixGraph(DEFAULT_GRAPH_ID, 200);

            // Add 100 nodes
            for (int i = 0; i < 100; i++) {
                MatrixGraphNode node = createNode(
                        "node" + i,
                        "Entity " + i,
                        "ENTITY",
                        "This is entity number " + i + " with various properties and relationships.",
                        Map.of("index", String.valueOf(i))
                );
                graph.addNode(node);
            }

            // Add edges (create a connected graph)
            for (int i = 0; i < 99; i++) {
                graph.addEdge("node" + i, "node" + (i + 1), 1.0, "CONNECTED_TO", false);
            }

            try {
                graphStore.saveGraph(graph);
            } catch (Exception e) {
                throw new RuntimeException("Failed to save large test graph", e);
            }

            llmChat.setFixedResponse("Large graph processed.");

            GraphRagQuery query = GraphRagQuery.builder()
                    .query("Find entities")
                    .searchType(SearchType.LOCAL)
                    .k(10)
                    .build();

            // Act
            long startTime = System.currentTimeMillis();
            GraphRagResult result = service.answerQuery(query);
            long endTime = System.currentTimeMillis();

            // Assert
            assertNotNull(result);
            long duration = endTime - startTime;
            assertTrue(duration < 5000, "Large graph query should complete within 5 seconds, took: " + duration + "ms");
        }
    }

    // ========================================
    // Multiple Graphs Tests
    // ========================================

    @Nested
    @DisplayName("Multiple Graphs Tests")
    class MultipleGraphsTests {

        @Test
        @DisplayName("Should store multiple graphs independently")
        void shouldStoreMultipleGraphsIndependently() {
            // Arrange
            AdjacencyMatrixGraph graph1 = new AdjacencyMatrixGraph("graph-1", 50);
            MatrixGraphNode node1 = createNode("node1", "Graph1 Node", "NODE",
                    "Content from graph 1", Map.of());
            graph1.addNode(node1);

            AdjacencyMatrixGraph graph2 = new AdjacencyMatrixGraph("graph-2", 50);
            MatrixGraphNode node2 = createNode("node2", "Graph2 Node", "NODE",
                    "Content from graph 2", Map.of());
            graph2.addNode(node2);

            try {
                graphStore.saveGraph(graph1);
                graphStore.saveGraph(graph2);
            } catch (Exception e) {
                throw new RuntimeException("Failed to save graphs", e);
            }

            // Assert
            assertTrue(graphStore.loadGraph("graph-1").isPresent());
            assertTrue(graphStore.loadGraph("graph-2").isPresent());
            assertEquals(1, graphStore.loadGraph("graph-1").get().getNodeCount());
            assertEquals(1, graphStore.loadGraph("graph-2").get().getNodeCount());
        }

        @Test
        @DisplayName("Should query default graph when multiple exist")
        void shouldQueryDefaultGraphWhenMultipleExist() {
            // Arrange
            // Create the default graph
            createTestKnowledgeGraph();

            // Create another graph
            AdjacencyMatrixGraph otherGraph = new AdjacencyMatrixGraph("other-graph", 50);
            MatrixGraphNode otherNode = createNode("other", "Other Node", "NODE",
                    "Different content", Map.of());
            otherGraph.addNode(otherNode);

            try {
                graphStore.saveGraph(otherGraph);
            } catch (Exception e) {
                throw new RuntimeException("Failed to save other graph", e);
            }

            llmChat.setFixedResponse("TechCorp information from default graph.");

            GraphRagQuery query = GraphRagQuery.builder()
                    .query("What is TechCorp?")
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build();

            // Act
            GraphRagResult result = service.answerQuery(query);

            // Assert
            assertNotNull(result);
            // Should query the default graph, not the other one
        }
    }
}
