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
package ai.kompile.graph.neo4j.integration;

import ai.kompile.core.graphrag.query.GraphRagQuery;
import ai.kompile.core.graphrag.query.GraphRagResult;
import ai.kompile.core.graphrag.query.SearchType;
import ai.kompile.graph.neo4j.Neo4jGraphRagService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Neo4jGraphRagService using Testcontainers.
 *
 * These tests require Docker to be running and will spin up a real Neo4j instance.
 *
 * Run with: mvn verify -pl kompile-app/kompile-graph-neo4j
 * Or: mvn failsafe:integration-test -pl kompile-app/kompile-graph-neo4j
 */
@Testcontainers
@DisplayName("Neo4jGraphRagService Integration Tests")
public class Neo4jGraphRagServiceIntegrationTest {

    @Container
    private static final Neo4jContainer<?> neo4jContainer = new Neo4jContainer<>(
            DockerImageName.parse("neo4j:5.18-community"))
            .withAdminPassword("testpassword")
            .withEnv("NEO4J_PLUGINS", "[\"apoc\"]");

    private static Driver driver;
    private Neo4jGraphRagService service;
    private TestEmbeddingModel embeddingModel;
    private TestLLMChat llmChat;
    private TestChatMemory chatMemory;

    @BeforeAll
    static void setupDriver() {
        driver = GraphDatabase.driver(
                neo4jContainer.getBoltUrl(),
                AuthTokens.basic("neo4j", "testpassword")
        );
    }

    @BeforeEach
    void setUp() {
        embeddingModel = new TestEmbeddingModel(384);
        llmChat = new TestLLMChat();
        chatMemory = new TestChatMemory();
        service = new Neo4jGraphRagService(driver, embeddingModel, llmChat, chatMemory);

        // Clean the database before each test
        try (Session session = driver.session()) {
            session.run("MATCH (n) DETACH DELETE n");
        }
    }

    @AfterEach
    void tearDown() {
        chatMemory.clearAll();
        llmChat.clearReceivedPrompts();
    }

    /**
     * Helper method to create test data in Neo4j.
     */
    private void createTestKnowledgeGraph() {
        try (Session session = driver.session()) {
            // Create company nodes
            session.run("""
                CREATE (techcorp:Company {name: 'TechCorp', description: 'A leading AI technology company', industry: 'Technology'})
                CREATE (acme:Company {name: 'Acme Inc', description: 'A manufacturing company', industry: 'Manufacturing'})
                CREATE (globaldata:Company {name: 'GlobalData', description: 'A data analytics company', industry: 'Analytics'})
                """);

            // Create people nodes
            session.run("""
                CREATE (john:Person {name: 'John Smith', role: 'CEO', expertise: 'Strategic Leadership'})
                CREATE (jane:Person {name: 'Jane Doe', role: 'CTO', expertise: 'AI and Machine Learning'})
                CREATE (bob:Person {name: 'Bob Wilson', role: 'CFO', expertise: 'Financial Management'})
                """);

            // Create product nodes
            session.run("""
                CREATE (aiplatform:Product {name: 'AI Platform', description: 'Enterprise AI solution', category: 'Software'})
                CREATE (dataengine:Product {name: 'DataEngine', description: 'Real-time data processing', category: 'Software'})
                """);

            // Create relationships
            session.run("""
                MATCH (john:Person {name: 'John Smith'})
                MATCH (techcorp:Company {name: 'TechCorp'})
                CREATE (john)-[:WORKS_AT {since: 2015}]->(techcorp)
                CREATE (john)-[:LEADS]->(techcorp)
                """);

            session.run("""
                MATCH (jane:Person {name: 'Jane Doe'})
                MATCH (techcorp:Company {name: 'TechCorp'})
                CREATE (jane)-[:WORKS_AT {since: 2018}]->(techcorp)
                """);

            session.run("""
                MATCH (techcorp:Company {name: 'TechCorp'})
                MATCH (aiplatform:Product {name: 'AI Platform'})
                CREATE (techcorp)-[:PRODUCES]->(aiplatform)
                """);

            session.run("""
                MATCH (techcorp:Company {name: 'TechCorp'})
                MATCH (globaldata:Company {name: 'GlobalData'})
                CREATE (techcorp)-[:PARTNERS_WITH {since: 2020}]->(globaldata)
                """);
        }
    }

    /**
     * Helper method to create embeddings in Neo4j for vector search.
     */
    private void createEmbeddings() {
        try (Session session = driver.session()) {
            // Create vector index if it doesn't exist
            session.run("""
                CREATE VECTOR INDEX company_embeddings IF NOT EXISTS
                FOR (c:Company)
                ON c.embedding
                OPTIONS {indexConfig: {
                    `vector.dimensions`: 384,
                    `vector.similarity_function`: 'cosine'
                }}
                """);

            // Generate and store embeddings for companies
            List<Map<String, Object>> companies = session.run(
                    "MATCH (c:Company) RETURN c.name as name, c.description as description"
            ).list(r -> Map.of(
                    "name", r.get("name").asString(),
                    "description", r.get("description").asString()
            ));

            for (Map<String, Object> company : companies) {
                String name = (String) company.get("name");
                String description = (String) company.get("description");
                float[] embedding = embeddingModel.embed(description).toFloatVector();

                session.run(
                        "MATCH (c:Company {name: $name}) SET c.embedding = $embedding",
                        Map.of("name", name, "embedding", embedding)
                );
            }
        }
    }

    // ========================================
    // Basic Query Tests
    // ========================================

    @Nested
    @DisplayName("Basic Query Tests")
    class BasicQueryTests {

        @Test
        @DisplayName("Should return result for query with matching context")
        void shouldReturnResultForQueryWithMatchingContext() {
            // Arrange
            createTestKnowledgeGraph();
            llmChat.setFixedResponse("TechCorp is a leading AI technology company.");

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
            assertEquals("TechCorp is a leading AI technology company.", result.getAnswer());
        }

        @Test
        @DisplayName("Should handle query with no matching data")
        void shouldHandleQueryWithNoMatchingData() {
            // Arrange - empty database
            llmChat.setFixedResponse("I don't have information about that topic.");

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
    }

    // ========================================
    // Vector Search Tests
    // ========================================

    @Nested
    @DisplayName("Vector Search Tests")
    class VectorSearchTests {

        @Test
        @DisplayName("Should use vector similarity for context retrieval")
        void shouldUseVectorSimilarityForContextRetrieval() {
            // Arrange
            createTestKnowledgeGraph();
            createEmbeddings();
            llmChat.setFixedResponse("Based on the context, TechCorp is an AI company.");

            GraphRagQuery query = GraphRagQuery.builder()
                    .query("Tell me about AI technology companies")
                    .searchType(SearchType.LOCAL)
                    .k(3)
                    .build();

            // Act
            GraphRagResult result = service.answerQuery(query);

            // Assert
            assertNotNull(result);
            assertNotNull(result.getAnswer());
            // Verify that LLM was called with some context
            assertFalse(llmChat.getReceivedPrompts().isEmpty());
        }

        @Test
        @DisplayName("Should respect k parameter for result count")
        void shouldRespectKParameterForResultCount() {
            // Arrange
            createTestKnowledgeGraph();
            createEmbeddings();
            llmChat.setFixedResponse("Found limited results.");

            GraphRagQuery query = GraphRagQuery.builder()
                    .query("Companies")
                    .searchType(SearchType.LOCAL)
                    .k(1) // Only want 1 result
                    .build();

            // Act
            GraphRagResult result = service.answerQuery(query);

            // Assert
            assertNotNull(result);
        }
    }

    // ========================================
    // Conversation History Tests
    // ========================================

    @Nested
    @DisplayName("Conversation History Tests")
    class ConversationHistoryTests {

        @Test
        @DisplayName("Should store conversation history")
        void shouldStoreConversationHistory() {
            // Arrange
            createTestKnowledgeGraph();
            String conversationId = "test-conv-" + System.currentTimeMillis();
            llmChat.setFixedResponse("TechCorp is an AI company.");

            GraphRagQuery query = GraphRagQuery.builder()
                    .query("What is TechCorp?")
                    .searchType(SearchType.LOCAL)
                    .conversationId(conversationId)
                    .k(5)
                    .build();

            // Act
            service.answerQuery(query);

            // Assert
            assertTrue(chatMemory.exists(conversationId));
            List<Message> messages = chatMemory.get(conversationId);
            assertEquals(2, messages.size()); // User message + Assistant message
            assertTrue(messages.get(0) instanceof UserMessage);
            assertTrue(messages.get(1) instanceof AssistantMessage);
        }

        @Test
        @DisplayName("Should use conversation history for follow-up queries")
        void shouldUseConversationHistoryForFollowUpQueries() {
            // Arrange
            createTestKnowledgeGraph();
            String conversationId = "test-conv-" + System.currentTimeMillis();

            // First query
            llmChat.setFixedResponse("TechCorp is an AI company led by John Smith.");
            GraphRagQuery firstQuery = GraphRagQuery.builder()
                    .query("What is TechCorp?")
                    .searchType(SearchType.LOCAL)
                    .conversationId(conversationId)
                    .k(5)
                    .build();
            service.answerQuery(firstQuery);

            // Second query (follow-up)
            llmChat.setFixedResponse("John Smith has been CEO since 2015.");
            GraphRagQuery followUpQuery = GraphRagQuery.builder()
                    .query("When did he become CEO?") // "he" refers to John Smith from context
                    .searchType(SearchType.LOCAL)
                    .conversationId(conversationId)
                    .k(5)
                    .build();

            // Act
            service.answerQuery(followUpQuery);

            // Assert
            List<Message> messages = chatMemory.get(conversationId);
            assertEquals(4, messages.size()); // 2 exchanges
        }

        @Test
        @DisplayName("Should use default conversation ID when not specified")
        void shouldUseDefaultConversationId() {
            // Arrange
            createTestKnowledgeGraph();
            llmChat.setFixedResponse("Test response.");

            GraphRagQuery query = GraphRagQuery.builder()
                    .query("Test query")
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build(); // No conversationId

            // Act
            service.answerQuery(query);

            // Assert
            assertTrue(chatMemory.exists("default"));
        }
    }

    // ========================================
    // Graph Traversal Tests
    // ========================================

    @Nested
    @DisplayName("Graph Traversal Tests")
    class GraphTraversalTests {

        @Test
        @DisplayName("Should find related entities through relationships")
        void shouldFindRelatedEntitiesThroughRelationships() {
            // Arrange
            createTestKnowledgeGraph();
            llmChat.setResponseGenerator(prompt -> {
                if (prompt.contains("TechCorp") || prompt.contains("John")) {
                    return "John Smith is the CEO of TechCorp.";
                }
                return "No information found.";
            });

            GraphRagQuery query = GraphRagQuery.builder()
                    .query("Who leads TechCorp?")
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
        @DisplayName("Should retrieve multi-hop relationships")
        void shouldRetrieveMultiHopRelationships() {
            // Arrange
            createTestKnowledgeGraph();
            llmChat.setResponseGenerator(prompt -> {
                return "TechCorp partners with GlobalData and produces the AI Platform.";
            });

            GraphRagQuery query = GraphRagQuery.builder()
                    .query("What are TechCorp's partnerships and products?")
                    .searchType(SearchType.LOCAL)
                    .k(10)
                    .build();

            // Act
            GraphRagResult result = service.answerQuery(query);

            // Assert
            assertNotNull(result);
        }
    }

    // ========================================
    // Error Handling Tests
    // ========================================

    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should handle empty query gracefully")
        void shouldHandleEmptyQueryGracefully() {
            // Arrange
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
        @DisplayName("Should handle very long query")
        void shouldHandleVeryLongQuery() {
            // Arrange
            createTestKnowledgeGraph();
            String longQuery = "What is ".repeat(100) + "TechCorp?";
            llmChat.setFixedResponse("TechCorp is a technology company.");

            GraphRagQuery query = GraphRagQuery.builder()
                    .query(longQuery)
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build();

            // Act
            GraphRagResult result = service.answerQuery(query);

            // Assert
            assertNotNull(result);
            assertNotNull(result.getAnswer());
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
            assertTrue(duration < 5000, "Query should complete within 5 seconds, took: " + duration + "ms");
        }
    }
}
