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

package ai.kompile.graph.neo4j;

import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.graphrag.query.GraphRagQuery;
import ai.kompile.core.graphrag.query.GraphRagResult;
import ai.kompile.core.graphrag.query.SearchType;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Neo4jGraphRagService.
 *
 * NOTE: Full unit tests are disabled due to Java 24 compatibility issues with Mockito's
 * inline mocking of interfaces that extend AutoCloseable (like Neo4j Driver).
 * The Neo4j driver has many abstract methods that would require extensive stub implementations.
 *
 * For actual testing, integration tests with Testcontainers are recommended:
 * @see Neo4jGraphRagServiceIntegrationTest (to be implemented)
 *
 * These tests verify the basic structure of GraphRagQuery and can serve as a template
 * for future integration tests.
 */
@DisplayName("Neo4jGraphRagService Tests")
public class Neo4jGraphRagServiceTest {

    // ========================================
    // GraphRagQuery Builder Tests
    // ========================================

    @Nested
    @DisplayName("GraphRagQuery Builder Tests")
    class GraphRagQueryBuilderTests {

        @Test
        @DisplayName("Should build query with all parameters")
        void shouldBuildQueryWithAllParameters() {
            // Arrange & Act
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("What is TechCorp?")
                    .searchType(SearchType.LOCAL)
                    .conversationId("test-conv-123")
                    .k(5)
                    .build();

            // Assert
            assertEquals("What is TechCorp?", query.getQuery());
            assertEquals(SearchType.LOCAL, query.getSearchType());
            assertEquals("test-conv-123", query.getConversationId());
            assertEquals(5, query.getK());
        }

        @Test
        @DisplayName("Should build query with minimal parameters")
        void shouldBuildQueryWithMinimalParameters() {
            // Arrange & Act
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("Test query")
                    .build();

            // Assert
            assertEquals("Test query", query.getQuery());
            assertNull(query.getSearchType());
            // conversationId defaults to "default" when not specified
            assertEquals("default", query.getConversationId());
        }

        @Test
        @DisplayName("Should handle different search types")
        void shouldHandleDifferentSearchTypes() {
            // Arrange & Act
            GraphRagQuery localQuery = GraphRagQuery.builder()
                    .query("Local search")
                    .searchType(SearchType.LOCAL)
                    .build();

            GraphRagQuery globalQuery = GraphRagQuery.builder()
                    .query("Global search")
                    .searchType(SearchType.GLOBAL)
                    .build();

            // Assert
            assertEquals(SearchType.LOCAL, localQuery.getSearchType());
            assertEquals(SearchType.GLOBAL, globalQuery.getSearchType());
        }
    }

    @Nested
    @DisplayName("Query Validation Tests")
    class QueryValidationTests {

        @Test
        @DisplayName("Should return an empty result for a blank query without embedding")
        void shouldReturnEmptyResultForBlankQueryWithoutEmbedding() {
            StubEmbeddingModel embeddingModel = new StubEmbeddingModel(null);
            Neo4jGraphRagService service = new Neo4jGraphRagService(null, embeddingModel, null, null);

            GraphRagResult result = service.answerQuery(GraphRagQuery.builder().query("  ").build());

            assertEquals("Please provide a valid query.", result.getAnswer());
            assertEquals("", result.getFormattedContext());
            assertEquals(0, embeddingModel.singleEmbeddingCalls);
        }

        @Test
        @DisplayName("Should reject a zero embedding for a nonblank query")
        void shouldRejectZeroEmbeddingForNonblankQuery() {
            StubEmbeddingModel embeddingModel = new StubEmbeddingModel(Nd4j.zeros(3));
            Neo4jGraphRagService service = new Neo4jGraphRagService(null, embeddingModel, null, null);

            GraphRagResult result = service.answerQuery(GraphRagQuery.builder()
                    .query("valid query")
                    .conversationId("")
                    .build());

            assertEquals("Error generating query embedding. Please try again.", result.getAnswer());
            assertEquals("", result.getFormattedContext());
            assertEquals(1, embeddingModel.singleEmbeddingCalls);
        }

        @Test
        @DisplayName("Should reject a nonfinite embedding for a nonblank query")
        void shouldRejectNonfiniteEmbeddingForNonblankQuery() {
            StubEmbeddingModel embeddingModel = new StubEmbeddingModel(Nd4j.create(new float[]{Float.NaN}));
            Neo4jGraphRagService service = new Neo4jGraphRagService(null, embeddingModel, null, null);

            GraphRagResult result = service.answerQuery(GraphRagQuery.builder()
                    .query("valid query")
                    .conversationId("")
                    .build());

            assertEquals("Error generating query embedding. Please try again.", result.getAnswer());
            assertEquals("", result.getFormattedContext());
            assertEquals(1, embeddingModel.singleEmbeddingCalls);
        }
    }

    private static final class StubEmbeddingModel implements EmbeddingModel {
        private final INDArray embedding;
        private int singleEmbeddingCalls;

        private StubEmbeddingModel(INDArray embedding) {
            this.embedding = embedding;
        }

        @Override
        public INDArray embed(String text) {
            singleEmbeddingCalls++;
            return embedding;
        }

        @Override
        public INDArray embed(List<String> texts) {
            return null;
        }

        @Override
        public INDArray embedDocuments(List<Document> documents) {
            return null;
        }

        @Override
        public int dimensions() {
            return embedding == null ? 0 : (int) embedding.length();
        }
    }

    // ========================================
    // Integration Test Placeholder
    // ========================================

    @Nested
    @DisplayName("Integration Tests (Disabled - require real Neo4j)")
    @Disabled("These tests require a real Neo4j instance or Testcontainers setup")
    class IntegrationTests {

        @Test
        @DisplayName("Should return result for valid query with context")
        void shouldReturnResultForValidQuery() {
            // This test would require actual Neo4j connection
            // Placeholder for future Testcontainers-based integration test
            fail("Integration test not implemented - requires Neo4j instance");
        }

        @Test
        @DisplayName("Should handle empty context gracefully")
        void shouldHandleEmptyContext() {
            // This test would require actual Neo4j connection
            // Placeholder for future Testcontainers-based integration test
            fail("Integration test not implemented - requires Neo4j instance");
        }

        @Test
        @DisplayName("Should update conversation memory after query")
        void shouldUpdateConversationMemory() {
            // This test would require actual Neo4j connection
            // Placeholder for future Testcontainers-based integration test
            fail("Integration test not implemented - requires Neo4j instance");
        }

        @Test
        @DisplayName("Should use k parameter for top-k retrieval")
        void shouldUseKParameterForTopK() {
            // This test would require actual Neo4j connection
            // Placeholder for future Testcontainers-based integration test
            fail("Integration test not implemented - requires Neo4j instance");
        }

        @Test
        @DisplayName("Should properly close session after query")
        void shouldProperlyCloseSession() {
            // This test would require actual Neo4j connection
            // Placeholder for future Testcontainers-based integration test
            fail("Integration test not implemented - requires Neo4j instance");
        }
    }
}
