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
package ai.kompile.knowledgegraph.matrix.service;

import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.query.GraphRagQuery;
import ai.kompile.core.graphrag.query.GraphRagResult;
import ai.kompile.core.graphrag.query.SearchType;
import ai.kompile.knowledgegraph.matrix.model.AdjacencyMatrixGraph;
import ai.kompile.knowledgegraph.matrix.model.MatrixGraphNode;
import ai.kompile.knowledgegraph.matrix.store.MatrixGraphStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MatrixGraphRagService.
 * Uses stubs for EmbeddingModel and LLMChat due to Java 24 module restrictions with Mockito.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MatrixGraphRagService Tests")
public class MatrixGraphRagServiceTest {

    @Mock
    private MatrixGraphStore graphStore;

    private StubEmbeddingModel embeddingModel;
    private StubLLMChat llmChat;
    private MatrixGraphRagService service;

    private static final String DEFAULT_GRAPH_ID = "default-knowledge-graph";

    @BeforeEach
    void setUp() {
        embeddingModel = new StubEmbeddingModel();
        llmChat = new StubLLMChat();
        service = new MatrixGraphRagService(graphStore, embeddingModel, llmChat);
    }

    // ========================================
    // Helper Methods
    // ========================================

    private AdjacencyMatrixGraph createTestGraph() {
        AdjacencyMatrixGraph graph = new AdjacencyMatrixGraph(DEFAULT_GRAPH_ID, 100);

        // Add some test nodes
        MatrixGraphNode node1 = MatrixGraphNode.builder()
                .nodeId("n1")
                .title("TechCorp")
                .nodeType("ORGANIZATION")
                .description("A leading technology company")
                .matrixIndex(0)
                .build();

        MatrixGraphNode node2 = MatrixGraphNode.builder()
                .nodeId("n2")
                .title("Alice Smith")
                .nodeType("PERSON")
                .description("CEO of TechCorp")
                .matrixIndex(1)
                .build();

        MatrixGraphNode node3 = MatrixGraphNode.builder()
                .nodeId("n3")
                .title("Software Products")
                .nodeType("CONCEPT")
                .description("Products developed by TechCorp")
                .matrixIndex(2)
                .build();

        graph.addNode(node1);
        graph.addNode(node2);
        graph.addNode(node3);

        // Add edges
        graph.addEdge("n1", "n2", 1.0, "EMPLOYS", true);
        graph.addEdge("n1", "n3", 0.8, "PRODUCES", true);
        graph.addEdge("n2", "n3", 0.6, "MANAGES", true);

        return graph;
    }

    private List<MatrixGraphNode> createTestNodes() {
        List<MatrixGraphNode> nodes = new ArrayList<>();

        nodes.add(MatrixGraphNode.builder()
                .nodeId("n1")
                .title("TechCorp")
                .nodeType("ORGANIZATION")
                .description("A leading technology company")
                .build());

        nodes.add(MatrixGraphNode.builder()
                .nodeId("n2")
                .title("Alice Smith")
                .nodeType("PERSON")
                .description("CEO of TechCorp")
                .build());

        return nodes;
    }

    // ========================================
    // No Graph Available Tests
    // ========================================

    @Nested
    @DisplayName("No Graph Available Tests")
    class NoGraphAvailableTests {

        @Test
        @DisplayName("Should return no data message when graph not found")
        void shouldReturnNoDataMessageWhenGraphNotFound() {
            // Arrange
            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.empty());

            GraphRagQuery query = GraphRagQuery.builder()
                    .query("What is TechCorp?")
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build();

            // Act
            GraphRagResult result = service.answerQuery(query);

            // Assert
            assertNotNull(result);
            assertTrue(result.getAnswer().contains("don't have any knowledge graph data"));
            assertEquals("", result.getFormattedContext());
        }

        @Test
        @DisplayName("Should return no data message for empty graph")
        void shouldReturnNoDataMessageForEmptyGraph() {
            // Arrange
            AdjacencyMatrixGraph emptyGraph = new AdjacencyMatrixGraph(DEFAULT_GRAPH_ID, 100);
            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.of(emptyGraph));

            GraphRagQuery query = GraphRagQuery.builder()
                    .query("What is TechCorp?")
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build();

            // Act
            GraphRagResult result = service.answerQuery(query);

            // Assert
            assertNotNull(result);
            // Should return empty context when no nodes match
        }
    }

    // ========================================
    // Local Search Tests
    // ========================================

    @Nested
    @DisplayName("Local Search Tests")
    class LocalSearchTests {

        @Test
        @DisplayName("Should perform local search with embedding model")
        void shouldPerformLocalSearchWithEmbeddingModel() {
            // Arrange
            AdjacencyMatrixGraph graph = createTestGraph();
            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.of(graph));

            List<Map.Entry<String, Double>> similarNodes = List.of(
                    Map.entry("n1", 0.95),
                    Map.entry("n2", 0.85)
            );
            when(graphStore.findSimilarNodes(eq(DEFAULT_GRAPH_ID), any(INDArray.class), anyInt(), anyDouble()))
                    .thenReturn(similarNodes);

            llmChat.setFixedResponse("TechCorp is a technology company led by Alice Smith.");

            // Act
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("What is TechCorp?")
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build();

            GraphRagResult result = service.answerQuery(query);

            // Assert
            assertNotNull(result);
            assertNotNull(result.getAnswer());
            verify(graphStore).findSimilarNodes(eq(DEFAULT_GRAPH_ID), any(INDArray.class), eq(5), eq(0.0));
        }

        @Test
        @DisplayName("Should fallback to text search when embedding model is null")
        void shouldFallbackToTextSearchWhenEmbeddingModelNull() {
            // Arrange
            MatrixGraphRagService serviceWithoutEmbedding = new MatrixGraphRagService(graphStore, null, llmChat);

            AdjacencyMatrixGraph graph = createTestGraph();
            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.of(graph));

            List<MatrixGraphNode> searchResults = createTestNodes();
            when(graphStore.searchNodes(eq(DEFAULT_GRAPH_ID), anyString(), anyInt())).thenReturn(searchResults);

            llmChat.setFixedResponse("Based on the knowledge graph...");

            // Act
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("What is TechCorp?")
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build();

            GraphRagResult result = serviceWithoutEmbedding.answerQuery(query);

            // Assert
            assertNotNull(result);
            verify(graphStore).searchNodes(DEFAULT_GRAPH_ID, "What is TechCorp?", 5);
            verify(graphStore, never()).findSimilarNodes(anyString(), any(INDArray.class), anyInt(), anyDouble());
        }

        @Test
        @DisplayName("Should fallback to text search when embedding returns null")
        void shouldFallbackToTextSearchWhenEmbeddingReturnsNull() {
            // Arrange
            embeddingModel.setShouldReturnNull(true);

            AdjacencyMatrixGraph graph = createTestGraph();
            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.of(graph));

            List<MatrixGraphNode> searchResults = createTestNodes();
            when(graphStore.searchNodes(eq(DEFAULT_GRAPH_ID), anyString(), anyInt())).thenReturn(searchResults);

            llmChat.setFixedResponse("Based on the knowledge graph...");

            // Act
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("What is TechCorp?")
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build();

            GraphRagResult result = service.answerQuery(query);

            // Assert
            assertNotNull(result);
            verify(graphStore).searchNodes(DEFAULT_GRAPH_ID, "What is TechCorp?", 5);
        }

        @Test
        @DisplayName("Should fallback to text search when no similar nodes found")
        void shouldFallbackToTextSearchWhenNoSimilarNodesFound() {
            // Arrange
            AdjacencyMatrixGraph graph = createTestGraph();
            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.of(graph));

            when(graphStore.findSimilarNodes(anyString(), any(INDArray.class), anyInt(), anyDouble()))
                    .thenReturn(List.of()); // Empty results

            List<MatrixGraphNode> searchResults = createTestNodes();
            when(graphStore.searchNodes(eq(DEFAULT_GRAPH_ID), anyString(), anyInt())).thenReturn(searchResults);

            llmChat.setFixedResponse("Based on the knowledge graph...");

            // Act
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("What is TechCorp?")
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build();

            GraphRagResult result = service.answerQuery(query);

            // Assert
            assertNotNull(result);
            verify(graphStore).searchNodes(DEFAULT_GRAPH_ID, "What is TechCorp?", 5);
        }
    }

    // ========================================
    // Global Search Tests
    // ========================================

    @Nested
    @DisplayName("Global Search Tests")
    class GlobalSearchTests {

        @Test
        @DisplayName("Should perform global search using PageRank")
        void shouldPerformGlobalSearchUsingPageRank() {
            // Arrange
            AdjacencyMatrixGraph graph = createTestGraph();
            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.of(graph));

            llmChat.setFixedResponse("Overview of the knowledge graph...");

            // Act
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("Give me an overview of the knowledge graph")
                    .searchType(SearchType.GLOBAL)
                    .k(10)
                    .build();

            GraphRagResult result = service.answerQuery(query);

            // Assert
            assertNotNull(result);
            assertNotNull(result.getFormattedContext());
            assertTrue(result.getFormattedContext().contains("Knowledge Graph Overview"));
        }

        @Test
        @DisplayName("Global search should include entity counts")
        void globalSearchShouldIncludeEntityCounts() {
            // Arrange
            AdjacencyMatrixGraph graph = createTestGraph();
            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.of(graph));

            llmChat.setFixedResponse("Overview of the knowledge graph...");

            // Act
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("What's in the graph?")
                    .searchType(SearchType.GLOBAL)
                    .k(10)
                    .build();

            GraphRagResult result = service.answerQuery(query);

            // Assert
            assertNotNull(result);
            String context = result.getFormattedContext();
            assertTrue(context.contains("Total entities"));
            assertTrue(context.contains("Total relationships"));
        }
    }

    // ========================================
    // No LLM Available Tests
    // ========================================

    @Nested
    @DisplayName("No LLM Available Tests")
    class NoLLMAvailableTests {

        @Test
        @DisplayName("Should return context-only when LLM is null")
        void shouldReturnContextOnlyWhenLLMNull() {
            // Arrange
            MatrixGraphRagService serviceWithoutLLM = new MatrixGraphRagService(graphStore, embeddingModel, null);

            AdjacencyMatrixGraph graph = createTestGraph();
            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.of(graph));

            List<Map.Entry<String, Double>> similarNodes = List.of(
                    Map.entry("n1", 0.95)
            );
            when(graphStore.findSimilarNodes(anyString(), any(INDArray.class), anyInt(), anyDouble()))
                    .thenReturn(similarNodes);

            // Act
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("What is TechCorp?")
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build();

            GraphRagResult result = serviceWithoutLLM.answerQuery(query);

            // Assert
            assertNotNull(result);
            assertTrue(result.getAnswer().contains("No LLM configured"));
        }

        @Test
        @DisplayName("Should handle LLM exception gracefully")
        void shouldHandleLLMExceptionGracefully() {
            // Arrange
            llmChat.setShouldThrowException(true);

            AdjacencyMatrixGraph graph = createTestGraph();
            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.of(graph));

            List<Map.Entry<String, Double>> similarNodes = List.of(
                    Map.entry("n1", 0.95)
            );
            when(graphStore.findSimilarNodes(anyString(), any(INDArray.class), anyInt(), anyDouble()))
                    .thenReturn(similarNodes);

            // Act
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("What is TechCorp?")
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build();

            GraphRagResult result = service.answerQuery(query);

            // Assert
            assertNotNull(result);
            assertTrue(result.getAnswer().contains("error"));
        }
    }

    // ========================================
    // Query With Graph Tests
    // ========================================

    @Nested
    @DisplayName("Query With Provided Graph Tests")
    class QueryWithGraphTests {

        @Test
        @DisplayName("Should use provided graph entities for context")
        void shouldUseProvidedGraphEntitiesForContext() {
            // Arrange
            Graph providedGraph = new Graph();

            Entity entity1 = new Entity();
            entity1.setId("e1");
            entity1.setTitle("TechCorp");
            entity1.setType("ORGANIZATION");
            entity1.setDescription("A technology company");

            Entity entity2 = new Entity();
            entity2.setId("e2");
            entity2.setTitle("Alice");
            entity2.setType("PERSON");
            entity2.setDescription("CEO of TechCorp");

            providedGraph.setEntities(List.of(entity1, entity2));

            Relationship rel = new Relationship();
            rel.setSource("e1");
            rel.setTarget("e2");
            rel.setType("EMPLOYS");
            rel.setDescription("Employment relationship");
            providedGraph.setRelationships(List.of(rel));

            llmChat.setFixedResponse("Based on the provided graph, TechCorp employs Alice.");

            // Act
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("Who works at TechCorp?")
                    .graph(providedGraph)
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build();

            GraphRagResult result = service.answerQueryWithGraph(query);

            // Assert
            assertNotNull(result);
            assertNotNull(result.getFormattedContext());
            assertTrue(result.getFormattedContext().contains("TechCorp"));
            assertTrue(result.getFormattedContext().contains("Alice"));
        }

        @Test
        @DisplayName("Should fall back to stored graph when provided graph is null")
        void shouldFallBackToStoredGraphWhenProvidedGraphNull() {
            // Arrange
            AdjacencyMatrixGraph storedGraph = createTestGraph();
            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.of(storedGraph));

            when(graphStore.findSimilarNodes(anyString(), any(INDArray.class), anyInt(), anyDouble()))
                    .thenReturn(List.of(Map.entry("n1", 0.95)));

            llmChat.setFixedResponse("Answer from stored graph");

            // Act
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("What is TechCorp?")
                    .graph(null) // Explicitly null
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build();

            GraphRagResult result = service.answerQueryWithGraph(query);

            // Assert
            assertNotNull(result);
            verify(graphStore).loadGraph(DEFAULT_GRAPH_ID);
        }
    }

    // ========================================
    // K Parameter Tests
    // ========================================

    @Nested
    @DisplayName("K Parameter Tests")
    class KParameterTests {

        @Test
        @DisplayName("Should use default k value when not specified or zero")
        void shouldUseDefaultKValueWhenNotSpecifiedOrZero() {
            // Arrange
            AdjacencyMatrixGraph graph = createTestGraph();
            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.of(graph));

            when(graphStore.findSimilarNodes(anyString(), any(INDArray.class), anyInt(), anyDouble()))
                    .thenReturn(List.of());
            when(graphStore.searchNodes(anyString(), anyString(), anyInt())).thenReturn(List.of());

            // Act
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("What is TechCorp?")
                    .searchType(SearchType.LOCAL)
                    .k(0) // Zero should use default
                    .build();

            service.answerQuery(query);

            // Assert - should use default k=5 for local search
            verify(graphStore).findSimilarNodes(eq(DEFAULT_GRAPH_ID), any(INDArray.class), eq(5), eq(0.0));
        }

        @Test
        @DisplayName("Should use specified k value for search")
        void shouldUseSpecifiedKValueForSearch() {
            // Arrange
            AdjacencyMatrixGraph graph = createTestGraph();
            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.of(graph));

            when(graphStore.findSimilarNodes(anyString(), any(INDArray.class), anyInt(), anyDouble()))
                    .thenReturn(List.of());
            when(graphStore.searchNodes(anyString(), anyString(), anyInt())).thenReturn(List.of());

            // Act
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("What is TechCorp?")
                    .searchType(SearchType.LOCAL)
                    .k(15)
                    .build();

            service.answerQuery(query);

            // Assert - should use specified k=15
            verify(graphStore).findSimilarNodes(eq(DEFAULT_GRAPH_ID), any(INDArray.class), eq(15), eq(0.0));
        }
    }

    // ========================================
    // Context Formatting Tests
    // ========================================

    @Nested
    @DisplayName("Context Formatting Tests")
    class ContextFormattingTests {

        @Test
        @DisplayName("Should format node context with title and description")
        void shouldFormatNodeContextWithTitleAndDescription() {
            // Arrange
            AdjacencyMatrixGraph graph = createTestGraph();
            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.of(graph));

            List<MatrixGraphNode> nodes = createTestNodes();
            when(graphStore.searchNodes(anyString(), anyString(), anyInt())).thenReturn(nodes);

            // Service without embedding to trigger text search
            MatrixGraphRagService serviceWithoutEmbedding = new MatrixGraphRagService(graphStore, null, llmChat);

            llmChat.setFixedResponse("Answer");

            // Act
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("What is TechCorp?")
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build();

            GraphRagResult result = serviceWithoutEmbedding.answerQuery(query);

            // Assert
            assertNotNull(result.getFormattedContext());
            assertTrue(result.getFormattedContext().contains("Entity:"));
            assertTrue(result.getFormattedContext().contains("TechCorp"));
        }

        @Test
        @DisplayName("Should include related entities in context")
        void shouldIncludeRelatedEntitiesInContext() {
            // Arrange
            AdjacencyMatrixGraph graph = createTestGraph();
            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.of(graph));

            List<MatrixGraphNode> nodes = List.of(
                    MatrixGraphNode.builder()
                            .nodeId("n1")
                            .title("TechCorp")
                            .nodeType("ORGANIZATION")
                            .description("A technology company")
                            .matrixIndex(0)
                            .build()
            );
            when(graphStore.searchNodes(anyString(), anyString(), anyInt())).thenReturn(nodes);

            // Service without embedding
            MatrixGraphRagService serviceWithoutEmbedding = new MatrixGraphRagService(graphStore, null, llmChat);

            llmChat.setFixedResponse("Answer");

            // Act
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("What is TechCorp?")
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build();

            GraphRagResult result = serviceWithoutEmbedding.answerQuery(query);

            // Assert
            assertNotNull(result.getFormattedContext());
            // Context should include related entities
            assertTrue(result.getFormattedContext().contains("Relevant Knowledge"));
        }
    }

    // ========================================
    // Edge Case Tests
    // ========================================

    @Nested
    @DisplayName("Edge Case Tests")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle empty query gracefully")
        void shouldHandleEmptyQueryGracefully() {
            // Arrange
            AdjacencyMatrixGraph graph = createTestGraph();
            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.of(graph));
            when(graphStore.searchNodes(anyString(), anyString(), anyInt())).thenReturn(List.of());

            // Service without embedding and LLM
            MatrixGraphRagService serviceWithoutDeps = new MatrixGraphRagService(graphStore, null, null);

            // Act
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("")
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build();

            GraphRagResult result = serviceWithoutDeps.answerQuery(query);

            // Assert
            assertNotNull(result);
        }

        @Test
        @DisplayName("Should handle null search type as LOCAL")
        void shouldHandleNullSearchTypeAsLocal() {
            // Arrange
            AdjacencyMatrixGraph graph = createTestGraph();
            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.of(graph));

            List<MatrixGraphNode> nodes = createTestNodes();
            when(graphStore.searchNodes(anyString(), anyString(), anyInt())).thenReturn(nodes);

            MatrixGraphRagService serviceWithoutEmbedding = new MatrixGraphRagService(graphStore, null, llmChat);

            llmChat.setFixedResponse("Answer");

            // Act
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("What is TechCorp?")
                    .searchType(null) // Null should default to LOCAL
                    .k(5)
                    .build();

            GraphRagResult result = serviceWithoutEmbedding.answerQuery(query);

            // Assert - should perform local search (text search in this case)
            verify(graphStore).searchNodes(anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("Should handle graph with no edges for global search")
        void shouldHandleGraphWithNoEdgesForGlobalSearch() {
            // Arrange
            AdjacencyMatrixGraph graphWithNoEdges = new AdjacencyMatrixGraph(DEFAULT_GRAPH_ID, 100);
            graphWithNoEdges.addNode(MatrixGraphNode.builder()
                    .nodeId("n1")
                    .title("Isolated Node")
                    .nodeType("CONCEPT")
                    .build());

            when(graphStore.loadGraph(DEFAULT_GRAPH_ID)).thenReturn(Optional.of(graphWithNoEdges));

            llmChat.setFixedResponse("Overview of isolated nodes...");

            // Act
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("Overview")
                    .searchType(SearchType.GLOBAL)
                    .k(10)
                    .build();

            GraphRagResult result = service.answerQuery(query);

            // Assert
            assertNotNull(result);
            assertTrue(result.getFormattedContext().contains("Knowledge Graph Overview"));
        }
    }
}
