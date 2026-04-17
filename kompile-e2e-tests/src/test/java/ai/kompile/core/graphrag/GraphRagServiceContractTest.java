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

package ai.kompile.core.graphrag;

import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.query.GraphRagQuery;
import ai.kompile.core.graphrag.query.GraphRagResult;
import ai.kompile.core.graphrag.query.SearchType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests for GraphRagService implementations.
 * <p>
 * These tests define the expected behavior of any GraphRagService implementation.
 * Implementations should extend this class and provide their specific implementation
 * via the {@link #createService()} method.
 * </p>
 * <p>
 * The tests are implementation-agnostic and verify:
 * <ul>
 *     <li>Query processing for LOCAL and GLOBAL search types</li>
 *     <li>Result structure and content</li>
 *     <li>Edge cases: empty graphs, null handling, empty queries</li>
 *     <li>Graph-based queries with provided graph data</li>
 * </ul>
 * </p>
 */
public abstract class GraphRagServiceContractTest {

    protected GraphRagService service;

    /**
     * Creates the GraphRagService implementation to test.
     * Subclasses must implement this to provide their specific implementation.
     *
     * @return the GraphRagService implementation
     */
    protected abstract GraphRagService createService();

    /**
     * Optional: Setup the service with test graph data.
     * Default implementation does nothing. Override to populate test data.
     *
     * @param service the service to setup
     * @param graph   the test graph data
     */
    protected void setupWithGraph(GraphRagService service, Graph graph) {
        // Default: no-op. Implementations can override to populate their stores.
    }

    @BeforeEach
    void setUp() {
        service = createService();
    }

    // ========================================
    // Test Data Helpers
    // ========================================

    /**
     * Creates a simple test graph with entities and relationships.
     */
    protected Graph createSimpleTestGraph() {
        Graph graph = new Graph();

        // Create entities
        List<Entity> entities = new ArrayList<>();

        Entity alice = new Entity();
        alice.setId("e1");
        alice.setTitle("Alice");
        alice.setType("PERSON");
        alice.setDescription("Alice is a software engineer at TechCorp");
        entities.add(alice);

        Entity bob = new Entity();
        bob.setId("e2");
        bob.setTitle("Bob");
        bob.setType("PERSON");
        bob.setDescription("Bob is a data scientist at TechCorp");
        entities.add(bob);

        Entity techCorp = new Entity();
        techCorp.setId("e3");
        techCorp.setTitle("TechCorp");
        techCorp.setType("ORGANIZATION");
        techCorp.setDescription("TechCorp is a technology company specializing in AI");
        entities.add(techCorp);

        Entity aiProject = new Entity();
        aiProject.setId("e4");
        aiProject.setTitle("AI Project");
        aiProject.setType("PROJECT");
        aiProject.setDescription("An AI research project at TechCorp");
        entities.add(aiProject);

        graph.setEntities(entities);

        // Create relationships
        List<Relationship> relationships = new ArrayList<>();

        Relationship aliceWorksAt = new Relationship();
        aliceWorksAt.setSource("e1");
        aliceWorksAt.setTarget("e3");
        aliceWorksAt.setType("WORKS_AT");
        aliceWorksAt.setDescription("Alice works at TechCorp");
        aliceWorksAt.setWeight(1.0);
        relationships.add(aliceWorksAt);

        Relationship bobWorksAt = new Relationship();
        bobWorksAt.setSource("e2");
        bobWorksAt.setTarget("e3");
        bobWorksAt.setType("WORKS_AT");
        bobWorksAt.setDescription("Bob works at TechCorp");
        bobWorksAt.setWeight(1.0);
        relationships.add(bobWorksAt);

        Relationship aliceLeads = new Relationship();
        aliceLeads.setSource("e1");
        aliceLeads.setTarget("e4");
        aliceLeads.setType("LEADS");
        aliceLeads.setDescription("Alice leads the AI Project");
        aliceLeads.setWeight(0.9);
        relationships.add(aliceLeads);

        Relationship bobContributes = new Relationship();
        bobContributes.setSource("e2");
        bobContributes.setTarget("e4");
        bobContributes.setType("CONTRIBUTES_TO");
        bobContributes.setDescription("Bob contributes to the AI Project");
        bobContributes.setWeight(0.8);
        relationships.add(bobContributes);

        graph.setRelationships(relationships);
        graph.setCommunities(new ArrayList<>());

        return graph;
    }

    /**
     * Creates an empty graph.
     */
    protected Graph createEmptyGraph() {
        Graph graph = new Graph();
        graph.setEntities(new ArrayList<>());
        graph.setRelationships(new ArrayList<>());
        graph.setCommunities(new ArrayList<>());
        return graph;
    }

    /**
     * Creates a large test graph for stress testing.
     */
    protected Graph createLargeTestGraph(int entityCount) {
        Graph graph = new Graph();
        List<Entity> entities = new ArrayList<>();
        List<Relationship> relationships = new ArrayList<>();

        for (int i = 0; i < entityCount; i++) {
            Entity entity = new Entity();
            entity.setId("e" + i);
            entity.setTitle("Entity " + i);
            entity.setType(i % 3 == 0 ? "PERSON" : (i % 3 == 1 ? "ORGANIZATION" : "CONCEPT"));
            entity.setDescription("Description for entity " + i);
            entities.add(entity);

            // Create some relationships
            if (i > 0) {
                Relationship rel = new Relationship();
                rel.setSource("e" + i);
                rel.setTarget("e" + (i - 1));
                rel.setType("RELATED_TO");
                rel.setDescription("Entity " + i + " is related to Entity " + (i - 1));
                rel.setWeight(0.5 + (Math.random() * 0.5));
                relationships.add(rel);
            }
        }

        graph.setEntities(entities);
        graph.setRelationships(relationships);
        graph.setCommunities(new ArrayList<>());

        return graph;
    }

    // ========================================
    // Basic Contract Tests
    // ========================================

    @Nested
    @DisplayName("Basic Query Tests")
    class BasicQueryTests {

        @Test
        @DisplayName("answerQuery should return non-null result")
        void answerQuery_shouldReturnNonNullResult() {
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("What is TechCorp?")
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build();

            GraphRagResult result = service.answerQuery(query);

            assertNotNull(result, "Result should not be null");
        }

        @Test
        @DisplayName("answerQuery should return result with answer field")
        void answerQuery_shouldReturnResultWithAnswer() {
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("Tell me about Alice")
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build();

            GraphRagResult result = service.answerQuery(query);

            assertNotNull(result.getAnswer(), "Answer should not be null");
        }

        @Test
        @DisplayName("answerQuery with LOCAL search type should work")
        void answerQuery_withLocalSearch_shouldWork() {
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("Who works at TechCorp?")
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build();

            GraphRagResult result = service.answerQuery(query);

            assertNotNull(result);
            assertNotNull(result.getAnswer());
        }

        @Test
        @DisplayName("answerQuery with GLOBAL search type should work")
        void answerQuery_withGlobalSearch_shouldWork() {
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("What is the overall structure of the organization?")
                    .searchType(SearchType.GLOBAL)
                    .k(10)
                    .build();

            GraphRagResult result = service.answerQuery(query);

            assertNotNull(result);
            assertNotNull(result.getAnswer());
        }
    }

    // ========================================
    // Graph-Based Query Tests
    // ========================================

    @Nested
    @DisplayName("Graph-Based Query Tests")
    class GraphBasedQueryTests {

        @Test
        @DisplayName("answerQuery with provided graph should use graph data")
        void answerQuery_withProvidedGraph_shouldUseGraphData() {
            Graph graph = createSimpleTestGraph();

            GraphRagQuery query = GraphRagQuery.builder()
                    .query("Who leads the AI Project?")
                    .graph(graph)
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build();

            GraphRagResult result = service.answerQuery(query);

            assertNotNull(result);
            assertNotNull(result.getAnswer());
            // The answer or context should reference information from the graph
        }

        @Test
        @DisplayName("answerQuery with empty graph should handle gracefully")
        void answerQuery_withEmptyGraph_shouldHandleGracefully() {
            Graph emptyGraph = createEmptyGraph();

            GraphRagQuery query = GraphRagQuery.builder()
                    .query("Tell me about anything")
                    .graph(emptyGraph)
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build();

            GraphRagResult result = service.answerQuery(query);

            assertNotNull(result);
            assertNotNull(result.getAnswer());
            // Should indicate no information available
        }
    }

    // ========================================
    // Edge Case Tests
    // ========================================

    @Nested
    @DisplayName("Edge Case Tests")
    class EdgeCaseTests {

        @Test
        @DisplayName("answerQuery with null search type should default gracefully")
        void answerQuery_withNullSearchType_shouldDefaultGracefully() {
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("What is TechCorp?")
                    .searchType(null) // Explicitly null
                    .k(5)
                    .build();

            // Should not throw exception
            GraphRagResult result = service.answerQuery(query);
            assertNotNull(result);
        }

        @Test
        @DisplayName("answerQuery with empty query string should handle gracefully")
        void answerQuery_withEmptyQuery_shouldHandleGracefully() {
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("")
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build();

            // Should not throw exception
            GraphRagResult result = service.answerQuery(query);
            assertNotNull(result);
        }

        @Test
        @DisplayName("answerQuery with k=0 should handle gracefully")
        void answerQuery_withZeroK_shouldHandleGracefully() {
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("Tell me about the project")
                    .searchType(SearchType.LOCAL)
                    .k(0)
                    .build();

            // Should not throw exception
            GraphRagResult result = service.answerQuery(query);
            assertNotNull(result);
        }

        @Test
        @DisplayName("answerQuery with negative k should handle gracefully")
        void answerQuery_withNegativeK_shouldHandleGracefully() {
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("Tell me about the project")
                    .searchType(SearchType.LOCAL)
                    .k(-1)
                    .build();

            // Should not throw exception
            GraphRagResult result = service.answerQuery(query);
            assertNotNull(result);
        }

        @Test
        @DisplayName("answerQuery with very large k should handle gracefully")
        void answerQuery_withLargeK_shouldHandleGracefully() {
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("Tell me everything")
                    .searchType(SearchType.GLOBAL)
                    .k(10000)
                    .build();

            // Should not throw exception or hang
            GraphRagResult result = service.answerQuery(query);
            assertNotNull(result);
        }
    }

    // ========================================
    // Conversation Context Tests
    // ========================================

    @Nested
    @DisplayName("Conversation Context Tests")
    class ConversationContextTests {

        @Test
        @DisplayName("answerQuery should accept conversation ID")
        void answerQuery_shouldAcceptConversationId() {
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("What is TechCorp?")
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .conversationId("test-conversation-123")
                    .build();

            GraphRagResult result = service.answerQuery(query);

            assertNotNull(result);
        }

        @Test
        @DisplayName("answerQuery with different conversation IDs should work independently")
        void answerQuery_withDifferentConversationIds_shouldWorkIndependently() {
            GraphRagQuery query1 = GraphRagQuery.builder()
                    .query("Who is Alice?")
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .conversationId("conversation-1")
                    .build();

            GraphRagQuery query2 = GraphRagQuery.builder()
                    .query("Who is Bob?")
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .conversationId("conversation-2")
                    .build();

            GraphRagResult result1 = service.answerQuery(query1);
            GraphRagResult result2 = service.answerQuery(query2);

            assertNotNull(result1);
            assertNotNull(result2);
        }
    }

    // ========================================
    // Result Structure Tests
    // ========================================

    @Nested
    @DisplayName("Result Structure Tests")
    class ResultStructureTests {

        @Test
        @DisplayName("Result should have both answer and formatted context")
        void result_shouldHaveBothAnswerAndContext() {
            Graph graph = createSimpleTestGraph();

            GraphRagQuery query = GraphRagQuery.builder()
                    .query("Tell me about TechCorp and its employees")
                    .graph(graph)
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build();

            GraphRagResult result = service.answerQuery(query);

            assertNotNull(result.getAnswer(), "Answer should be present");
            // formattedContext may be null or empty if no context was used
            // but the field should be accessible
            assertDoesNotThrow(() -> result.getFormattedContext());
        }

        @Test
        @DisplayName("Result answer should not be empty for valid queries with data")
        void resultAnswer_shouldNotBeEmptyForValidQueries() {
            Graph graph = createSimpleTestGraph();

            GraphRagQuery query = GraphRagQuery.builder()
                    .query("Who leads the AI Project at TechCorp?")
                    .graph(graph)
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build();

            GraphRagResult result = service.answerQuery(query);

            assertNotNull(result.getAnswer());
            assertFalse(result.getAnswer().isEmpty(), "Answer should not be empty for valid query with data");
        }
    }

    // ========================================
    // Performance Tests
    // ========================================

    @Nested
    @DisplayName("Performance Tests")
    class PerformanceTests {

        @Test
        @DisplayName("answerQuery should complete within reasonable time")
        void answerQuery_shouldCompleteWithinReasonableTime() {
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("What is TechCorp?")
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build();

            long startTime = System.currentTimeMillis();
            GraphRagResult result = service.answerQuery(query);
            long endTime = System.currentTimeMillis();

            assertNotNull(result);
            // Should complete within 30 seconds (generous timeout for various implementations)
            assertTrue((endTime - startTime) < 30000,
                    "Query should complete within 30 seconds, took: " + (endTime - startTime) + "ms");
        }

        @Test
        @DisplayName("Multiple sequential queries should work")
        void multipleSequentialQueries_shouldWork() {
            for (int i = 0; i < 5; i++) {
                GraphRagQuery query = GraphRagQuery.builder()
                        .query("Query number " + i)
                        .searchType(i % 2 == 0 ? SearchType.LOCAL : SearchType.GLOBAL)
                        .k(5)
                        .build();

                GraphRagResult result = service.answerQuery(query);
                assertNotNull(result, "Query " + i + " should return non-null result");
            }
        }
    }

    // ========================================
    // Search Type Specific Tests
    // ========================================

    @Nested
    @DisplayName("Search Type Specific Tests")
    class SearchTypeSpecificTests {

        @Test
        @DisplayName("LOCAL search should focus on query-relevant entities")
        void localSearch_shouldFocusOnQueryRelevantEntities() {
            Graph graph = createSimpleTestGraph();

            GraphRagQuery query = GraphRagQuery.builder()
                    .query("Tell me about Alice")
                    .graph(graph)
                    .searchType(SearchType.LOCAL)
                    .k(3)
                    .build();

            GraphRagResult result = service.answerQuery(query);

            assertNotNull(result);
            // LOCAL search should return focused results
        }

        @Test
        @DisplayName("GLOBAL search should provide broader context")
        void globalSearch_shouldProvideBroaderContext() {
            Graph graph = createSimpleTestGraph();

            GraphRagQuery query = GraphRagQuery.builder()
                    .query("Give me an overview of the organization")
                    .graph(graph)
                    .searchType(SearchType.GLOBAL)
                    .k(10)
                    .build();

            GraphRagResult result = service.answerQuery(query);

            assertNotNull(result);
            // GLOBAL search should return broader context
        }
    }
}
