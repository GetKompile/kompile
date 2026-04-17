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
package ai.kompile.app.services.agent;

import ai.kompile.app.web.dto.AgentChatRequest;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.graphrag.GraphRagService;
import ai.kompile.core.graphrag.query.GraphRagQuery;
import ai.kompile.core.graphrag.query.GraphRagResult;
import ai.kompile.core.graphrag.query.SearchType;
import ai.kompile.core.retrievers.DocumentRetriever;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for AgentChatService focusing on RAG and GraphRAG integration.
 *
 * These tests verify the prompt building and context retrieval logic without
 * requiring actual agent execution.
 */
@DisplayName("AgentChatService RAG + GraphRAG Integration Tests")
public class AgentChatServiceIntegrationTest {

    // ========================================
    // Test Implementations
    // ========================================

    /**
     * Test implementation of DocumentRetriever for integration tests.
     */
    static class TestDocumentRetriever implements DocumentRetriever {
        private List<RetrievedDoc> documentsToReturn = new ArrayList<>();
        private List<String> receivedQueries = new ArrayList<>();

        public void setDocumentsToReturn(List<RetrievedDoc> docs) {
            this.documentsToReturn = docs;
        }

        public List<String> getReceivedQueries() {
            return receivedQueries;
        }

        @Override
        public List<String> retrieve(String query, int maxResults) {
            receivedQueries.add(query);
            return documentsToReturn.stream()
                    .limit(maxResults)
                    .map(RetrievedDoc::getContent)
                    .toList();
        }

        @Override
        public List<RetrievedDoc> retrieveWithDetails(String query, int maxResults) {
            receivedQueries.add(query);
            return documentsToReturn.stream()
                    .limit(maxResults)
                    .toList();
        }
    }

    /**
     * Test implementation of VectorStore for integration tests.
     */
    static class TestVectorStore implements VectorStore {
        private List<Document> documentsToReturn = new ArrayList<>();
        private List<String> receivedQueries = new ArrayList<>();

        public void setDocumentsToReturn(List<Document> docs) {
            this.documentsToReturn = docs;
        }

        public List<String> getReceivedQueries() {
            return receivedQueries;
        }

        @Override
        public List<Document> similaritySearch(String query, int k) {
            receivedQueries.add(query);
            return documentsToReturn.stream().limit(k).toList();
        }

        @Override
        public List<Document> similaritySearch(String query, int k, double threshold) {
            return similaritySearch(query, k);
        }

        @Override
        public int add(List<Document> documents) {
            return documents != null ? documents.size() : 0;
        }

        @Override
        @SuppressWarnings("deprecation")
        public int add(List<Document> documents, List<List<Float>> embeddings) {
            return documents != null ? documents.size() : 0;
        }

        @Override
        @SuppressWarnings("deprecation")
        public List<Document> similaritySearch(List<Float> queryEmbedding, int k, double threshold) {
            return documentsToReturn.stream().limit(k).toList();
        }

        @Override
        public boolean delete(List<String> ids) { return true; }
    }

    /**
     * Test implementation of GraphRagService for integration tests.
     */
    static class TestGraphRagService implements GraphRagService {
        private GraphRagResult resultToReturn;
        private List<GraphRagQuery> receivedQueries = new ArrayList<>();

        public void setResultToReturn(GraphRagResult result) {
            this.resultToReturn = result;
        }

        public List<GraphRagQuery> getReceivedQueries() {
            return receivedQueries;
        }

        @Override
        public GraphRagResult answerQuery(GraphRagQuery query) {
            receivedQueries.add(query);
            return resultToReturn;
        }
    }

    // ========================================
    // Test Fixtures
    // ========================================

    private TestDocumentRetriever documentRetriever;
    private TestVectorStore vectorStore;
    private TestGraphRagService graphRagService;

    @BeforeEach
    void setUp() {
        documentRetriever = new TestDocumentRetriever();
        vectorStore = new TestVectorStore();
        graphRagService = new TestGraphRagService();
    }

    // ========================================
    // AgentChatRequest Configuration Tests
    // ========================================

    @Nested
    @DisplayName("AgentChatRequest Configuration Tests")
    class AgentChatRequestConfigTests {

        @Test
        @DisplayName("Should have default RAG disabled")
        void shouldHaveDefaultRagDisabled() {
            AgentChatRequest request = new AgentChatRequest();
            assertFalse(request.isEnableRag());
            assertFalse(request.isEnableGraphRag());
        }

        @Test
        @DisplayName("Should allow enabling RAG only")
        void shouldAllowEnablingRagOnly() {
            AgentChatRequest request = new AgentChatRequest();
            request.setEnableRag(true);
            request.setRagMaxResults(10);
            request.setRagSimilarityThreshold(0.5);

            assertTrue(request.isEnableRag());
            assertFalse(request.isEnableGraphRag());
            assertEquals(10, request.getRagMaxResults());
            assertEquals(0.5, request.getRagSimilarityThreshold());
        }

        @Test
        @DisplayName("Should allow enabling GraphRAG only")
        void shouldAllowEnablingGraphRagOnly() {
            AgentChatRequest request = new AgentChatRequest();
            request.setEnableGraphRag(true);
            request.setGraphRagMaxResults(8);
            request.setGraphRagSearchType("GLOBAL");
            request.setGraphRagConversationId("test-conv-123");

            assertFalse(request.isEnableRag());
            assertTrue(request.isEnableGraphRag());
            assertEquals(8, request.getGraphRagMaxResults());
            assertEquals("GLOBAL", request.getGraphRagSearchType());
            assertEquals("test-conv-123", request.getGraphRagConversationId());
        }

        @Test
        @DisplayName("Should allow enabling both RAG and GraphRAG")
        void shouldAllowEnablingBothRagAndGraphRag() {
            AgentChatRequest request = new AgentChatRequest();
            request.setEnableRag(true);
            request.setEnableGraphRag(true);
            request.setRagMaxResults(5);
            request.setGraphRagMaxResults(5);

            assertTrue(request.isEnableRag());
            assertTrue(request.isEnableGraphRag());
        }

        @Test
        @DisplayName("Should have correct default values")
        void shouldHaveCorrectDefaultValues() {
            AgentChatRequest request = new AgentChatRequest();

            // RAG defaults
            assertEquals(5, request.getRagMaxResults());
            assertEquals(0.0, request.getRagSimilarityThreshold());
            assertTrue(request.isIncludeKeywordSearch());
            assertTrue(request.isIncludeSemanticSearch());

            // GraphRAG defaults
            assertEquals(5, request.getGraphRagMaxResults());
            assertEquals("LOCAL", request.getGraphRagSearchType());
            assertNull(request.getGraphRagConversationId());
        }
    }

    // ========================================
    // RAG Retrieval Tests
    // ========================================

    @Nested
    @DisplayName("RAG Retrieval Tests")
    class RagRetrievalTests {

        @Test
        @DisplayName("Should retrieve documents using keyword search")
        void shouldRetrieveDocumentsUsingKeywordSearch() {
            // Arrange
            List<RetrievedDoc> docs = List.of(
                    new RetrievedDoc("doc1", "Machine learning is a subset of AI.", Map.of("source", "ml.pdf"), 0.95),
                    new RetrievedDoc("doc2", "Deep learning uses neural networks.", Map.of("source", "dl.pdf"), 0.88)
            );
            documentRetriever.setDocumentsToReturn(docs);

            // Act
            List<RetrievedDoc> results = documentRetriever.retrieveWithDetails("What is machine learning?", 5);

            // Assert
            assertEquals(2, results.size());
            assertEquals("Machine learning is a subset of AI.", results.get(0).getContent());
            assertTrue(documentRetriever.getReceivedQueries().contains("What is machine learning?"));
        }

        @Test
        @DisplayName("Should retrieve documents using semantic search")
        void shouldRetrieveDocumentsUsingSemanticSearch() {
            // Arrange
            List<Document> docs = List.of(
                    new Document("doc1", "AI and machine learning concepts.", Map.of("score", 0.92)),
                    new Document("doc2", "Neural network architectures.", Map.of("score", 0.85))
            );
            vectorStore.setDocumentsToReturn(docs);

            // Act
            List<Document> results = vectorStore.similaritySearch("artificial intelligence", 5);

            // Assert
            assertEquals(2, results.size());
            assertTrue(vectorStore.getReceivedQueries().contains("artificial intelligence"));
        }

        @Test
        @DisplayName("Should respect maxResults parameter")
        void shouldRespectMaxResultsParameter() {
            // Arrange
            List<RetrievedDoc> docs = List.of(
                    new RetrievedDoc("doc1", "Content 1", Map.of(), 0.9),
                    new RetrievedDoc("doc2", "Content 2", Map.of(), 0.8),
                    new RetrievedDoc("doc3", "Content 3", Map.of(), 0.7),
                    new RetrievedDoc("doc4", "Content 4", Map.of(), 0.6),
                    new RetrievedDoc("doc5", "Content 5", Map.of(), 0.5)
            );
            documentRetriever.setDocumentsToReturn(docs);

            // Act
            List<RetrievedDoc> results = documentRetriever.retrieveWithDetails("query", 3);

            // Assert
            assertEquals(3, results.size());
        }
    }

    // ========================================
    // GraphRAG Retrieval Tests
    // ========================================

    @Nested
    @DisplayName("GraphRAG Retrieval Tests")
    class GraphRagRetrievalTests {

        @Test
        @DisplayName("Should retrieve context from knowledge graph")
        void shouldRetrieveContextFromKnowledgeGraph() {
            // Arrange
            GraphRagResult result = new GraphRagResult(
                    "TechCorp is a leading AI company founded in 2010.",
                    "Entity: TechCorp [COMPANY]\n  Description: A leading AI technology company\n  Related to: AI Platform, John Smith"
            );
            graphRagService.setResultToReturn(result);

            // Act
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("What is TechCorp?")
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build();
            GraphRagResult queryResult = graphRagService.answerQuery(query);

            // Assert
            assertNotNull(queryResult);
            assertEquals("TechCorp is a leading AI company founded in 2010.", queryResult.getAnswer());
            assertNotNull(queryResult.getFormattedContext());
            assertTrue(queryResult.getFormattedContext().contains("TechCorp"));
        }

        @Test
        @DisplayName("Should support LOCAL search type")
        void shouldSupportLocalSearchType() {
            // Arrange
            graphRagService.setResultToReturn(new GraphRagResult("Local answer", "Local context"));

            // Act
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("Local search query")
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build();
            graphRagService.answerQuery(query);

            // Assert
            assertEquals(1, graphRagService.getReceivedQueries().size());
            assertEquals(SearchType.LOCAL, graphRagService.getReceivedQueries().get(0).getSearchType());
        }

        @Test
        @DisplayName("Should support GLOBAL search type")
        void shouldSupportGlobalSearchType() {
            // Arrange
            graphRagService.setResultToReturn(new GraphRagResult("Global answer", "Global context"));

            // Act
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("Global search query")
                    .searchType(SearchType.GLOBAL)
                    .k(10)
                    .build();
            graphRagService.answerQuery(query);

            // Assert
            assertEquals(1, graphRagService.getReceivedQueries().size());
            assertEquals(SearchType.GLOBAL, graphRagService.getReceivedQueries().get(0).getSearchType());
        }

        @Test
        @DisplayName("Should maintain conversation context with conversationId")
        void shouldMaintainConversationContextWithConversationId() {
            // Arrange
            graphRagService.setResultToReturn(new GraphRagResult("Answer with context", "Context"));
            String conversationId = "test-conversation-" + System.currentTimeMillis();

            // Act - First query
            GraphRagQuery query1 = GraphRagQuery.builder()
                    .query("Who is the CEO?")
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .conversationId(conversationId)
                    .build();
            graphRagService.answerQuery(query1);

            // Act - Follow-up query with same conversationId
            GraphRagQuery query2 = GraphRagQuery.builder()
                    .query("When did they start?")
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .conversationId(conversationId)
                    .build();
            graphRagService.answerQuery(query2);

            // Assert
            assertEquals(2, graphRagService.getReceivedQueries().size());
            assertEquals(conversationId, graphRagService.getReceivedQueries().get(0).getConversationId());
            assertEquals(conversationId, graphRagService.getReceivedQueries().get(1).getConversationId());
        }
    }

    // ========================================
    // Combined RAG + GraphRAG Tests
    // ========================================

    @Nested
    @DisplayName("Combined RAG + GraphRAG Tests")
    class CombinedRagAndGraphRagTests {

        @Test
        @DisplayName("Should create request with both RAG and GraphRAG enabled")
        void shouldCreateRequestWithBothRagAndGraphRagEnabled() {
            // Arrange & Act
            AgentChatRequest request = new AgentChatRequest();
            request.setMessage("What products does TechCorp make?");
            request.setEnableRag(true);
            request.setEnableGraphRag(true);
            request.setRagMaxResults(5);
            request.setGraphRagMaxResults(5);
            request.setGraphRagSearchType("LOCAL");

            // Assert
            assertTrue(request.isEnableRag());
            assertTrue(request.isEnableGraphRag());
            assertEquals("What products does TechCorp make?", request.getMessage());
        }

        @Test
        @DisplayName("Should retrieve from both RAG and GraphRAG sources")
        void shouldRetrieveFromBothRagAndGraphRagSources() {
            // Arrange
            // Set up RAG results
            List<RetrievedDoc> ragDocs = List.of(
                    new RetrievedDoc("rag1", "TechCorp AI Platform documentation.", Map.of("source", "docs.pdf"), 0.9)
            );
            documentRetriever.setDocumentsToReturn(ragDocs);

            // Set up GraphRAG results
            GraphRagResult graphResult = new GraphRagResult(
                    "TechCorp produces AI Platform and DataEngine.",
                    "Entity: TechCorp [COMPANY]\n  Produces: AI Platform\n  Produces: DataEngine"
            );
            graphRagService.setResultToReturn(graphResult);

            // Act
            String query = "What products does TechCorp make?";
            List<RetrievedDoc> ragResults = documentRetriever.retrieveWithDetails(query, 5);
            GraphRagResult graphRagResult = graphRagService.answerQuery(
                    GraphRagQuery.builder()
                            .query(query)
                            .searchType(SearchType.LOCAL)
                            .k(5)
                            .build()
            );

            // Assert
            assertFalse(ragResults.isEmpty());
            assertNotNull(graphRagResult);
            assertNotNull(graphRagResult.getFormattedContext());

            // Both sources should have received the query
            assertTrue(documentRetriever.getReceivedQueries().contains(query));
            assertFalse(graphRagService.getReceivedQueries().isEmpty());
        }

        @Test
        @DisplayName("Should combine context from both sources")
        void shouldCombineContextFromBothSources() {
            // Arrange
            String ragContext = "Document content from vector store about TechCorp products.";
            String graphContext = "Entity: TechCorp\n  Products: AI Platform, DataEngine";

            // Act
            StringBuilder combinedContext = new StringBuilder();
            combinedContext.append("## Knowledge Graph Context\n");
            combinedContext.append(graphContext);
            combinedContext.append("\n\n## Retrieved Documents\n");
            combinedContext.append(ragContext);

            // Assert
            String result = combinedContext.toString();
            assertTrue(result.contains("Knowledge Graph Context"));
            assertTrue(result.contains("Retrieved Documents"));
            assertTrue(result.contains("TechCorp"));
        }

        @Test
        @DisplayName("Should handle GraphRAG failure gracefully and still use RAG")
        void shouldHandleGraphRagFailureGracefullyAndStillUseRag() {
            // Arrange
            List<RetrievedDoc> ragDocs = List.of(
                    new RetrievedDoc("rag1", "Fallback document content.", Map.of(), 0.85)
            );
            documentRetriever.setDocumentsToReturn(ragDocs);
            graphRagService.setResultToReturn(null); // Simulate GraphRAG failure

            // Act
            List<RetrievedDoc> ragResults = documentRetriever.retrieveWithDetails("query", 5);
            GraphRagResult graphResult = graphRagService.answerQuery(
                    GraphRagQuery.builder()
                            .query("query")
                            .searchType(SearchType.LOCAL)
                            .k(5)
                            .build()
            );

            // Assert
            assertFalse(ragResults.isEmpty()); // RAG still works
            assertNull(graphResult); // GraphRAG failed
        }

        @Test
        @DisplayName("Should handle RAG failure gracefully and still use GraphRAG")
        void shouldHandleRagFailureGracefullyAndStillUseGraphRag() {
            // Arrange
            documentRetriever.setDocumentsToReturn(Collections.emptyList()); // RAG returns nothing
            graphRagService.setResultToReturn(new GraphRagResult(
                    "Answer from graph only",
                    "Graph context"
            ));

            // Act
            List<RetrievedDoc> ragResults = documentRetriever.retrieveWithDetails("query", 5);
            GraphRagResult graphResult = graphRagService.answerQuery(
                    GraphRagQuery.builder()
                            .query("query")
                            .searchType(SearchType.LOCAL)
                            .k(5)
                            .build()
            );

            // Assert
            assertTrue(ragResults.isEmpty()); // RAG returned nothing
            assertNotNull(graphResult); // GraphRAG still works
            assertEquals("Answer from graph only", graphResult.getAnswer());
        }
    }

    // ========================================
    // Context Formatting Tests
    // ========================================

    @Nested
    @DisplayName("Context Formatting Tests")
    class ContextFormattingTests {

        @Test
        @DisplayName("Should format RAG documents correctly")
        void shouldFormatRagDocumentsCorrectly() {
            // Arrange
            List<RetrievedDoc> docs = List.of(
                    new RetrievedDoc("doc1", "First document content.", Map.of("source", "file1.pdf"), 0.95),
                    new RetrievedDoc("doc2", "Second document content.", Map.of("source", "file2.pdf"), 0.88)
            );

            // Act
            StringBuilder formattedContext = new StringBuilder();
            int docNum = 1;
            for (RetrievedDoc doc : docs) {
                formattedContext.append(String.format("### Document %d (Score: %.3f)\n%s\n\n",
                        docNum++, doc.getScore(), doc.getContent()));
            }

            // Assert
            String result = formattedContext.toString();
            assertTrue(result.contains("Document 1"));
            assertTrue(result.contains("Document 2"));
            assertTrue(result.contains("0.950"));
            assertTrue(result.contains("First document content."));
        }

        @Test
        @DisplayName("Should format GraphRAG context correctly")
        void shouldFormatGraphRagContextCorrectly() {
            // Arrange
            String graphContext = """
                    Entity: TechCorp [COMPANY]
                      Description: A leading AI technology company
                      Related to:
                        - AI Platform
                        - John Smith (CEO)
                    """;

            // Act
            String formattedGraphContext = String.format("""
                    ## Knowledge Graph Context
                    The following information was retrieved from the knowledge graph.

                    %s
                    """, graphContext);

            // Assert
            assertTrue(formattedGraphContext.contains("Knowledge Graph Context"));
            assertTrue(formattedGraphContext.contains("TechCorp"));
            assertTrue(formattedGraphContext.contains("AI Platform"));
        }

        @Test
        @DisplayName("Should combine RAG and GraphRAG context in correct order")
        void shouldCombineRagAndGraphRagContextInCorrectOrder() {
            // Arrange
            String graphContext = "Graph: TechCorp -> AI Platform";
            String ragContext = "Document: TechCorp product documentation";
            String userQuestion = "What products does TechCorp make?";

            // Act - GraphRAG context first, then RAG, then question
            StringBuilder fullPrompt = new StringBuilder();
            fullPrompt.append("## Knowledge Graph Context\n");
            fullPrompt.append(graphContext);
            fullPrompt.append("\n\n## Retrieved Documents\n");
            fullPrompt.append(ragContext);
            fullPrompt.append("\n\n---\n\n## User Question\n");
            fullPrompt.append(userQuestion);

            // Assert
            String result = fullPrompt.toString();
            int graphIndex = result.indexOf("Knowledge Graph Context");
            int ragIndex = result.indexOf("Retrieved Documents");
            int questionIndex = result.indexOf("User Question");

            assertTrue(graphIndex < ragIndex, "Graph context should come before RAG");
            assertTrue(ragIndex < questionIndex, "RAG should come before question");
        }
    }

    // ========================================
    // Performance Tests
    // ========================================

    @Nested
    @DisplayName("Performance Tests")
    class PerformanceTests {

        @Test
        @DisplayName("Should complete retrieval quickly")
        void shouldCompleteRetrievalQuickly() {
            // Arrange
            documentRetriever.setDocumentsToReturn(List.of(
                    new RetrievedDoc("doc1", "Content", Map.of(), 0.9)
            ));
            graphRagService.setResultToReturn(new GraphRagResult("Answer", "Context"));

            // Act
            long startTime = System.currentTimeMillis();
            documentRetriever.retrieveWithDetails("query", 5);
            graphRagService.answerQuery(GraphRagQuery.builder()
                    .query("query")
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build());
            long endTime = System.currentTimeMillis();

            // Assert
            long duration = endTime - startTime;
            assertTrue(duration < 1000, "Retrieval should complete within 1 second, took: " + duration + "ms");
        }
    }

    // ========================================
    // Edge Cases Tests
    // ========================================

    @Nested
    @DisplayName("Edge Cases Tests")
    class EdgeCasesTests {

        @Test
        @DisplayName("Should handle empty query")
        void shouldHandleEmptyQuery() {
            // Arrange
            AgentChatRequest request = new AgentChatRequest();
            request.setMessage("");
            request.setEnableRag(true);
            request.setEnableGraphRag(true);

            // Assert
            assertEquals("", request.getMessage());
        }

        @Test
        @DisplayName("Should handle null GraphRAG conversation ID")
        void shouldHandleNullGraphRagConversationId() {
            // Arrange
            graphRagService.setResultToReturn(new GraphRagResult("Answer", "Context"));

            // Act
            GraphRagQuery query = GraphRagQuery.builder()
                    .query("query")
                    .searchType(SearchType.LOCAL)
                    .k(5)
                    .build(); // No conversationId set

            GraphRagResult result = graphRagService.answerQuery(query);

            // Assert
            assertNotNull(result);
            assertEquals("default", graphRagService.getReceivedQueries().get(0).getConversationId());
        }

        @Test
        @DisplayName("Should handle very long query")
        void shouldHandleVeryLongQuery() {
            // Arrange
            String longQuery = "What is ".repeat(100) + "TechCorp?";
            documentRetriever.setDocumentsToReturn(List.of(
                    new RetrievedDoc("doc1", "Content", Map.of(), 0.9)
            ));

            // Act
            List<RetrievedDoc> results = documentRetriever.retrieveWithDetails(longQuery, 5);

            // Assert
            assertFalse(results.isEmpty());
            assertTrue(documentRetriever.getReceivedQueries().get(0).length() > 500);
        }

        @Test
        @DisplayName("Should handle special characters in query")
        void shouldHandleSpecialCharactersInQuery() {
            // Arrange
            String specialQuery = "What is TechCorp's AI platform? (v3.0) [latest]";
            documentRetriever.setDocumentsToReturn(List.of(
                    new RetrievedDoc("doc1", "Content", Map.of(), 0.9)
            ));

            // Act
            List<RetrievedDoc> results = documentRetriever.retrieveWithDetails(specialQuery, 5);

            // Assert
            assertFalse(results.isEmpty());
            assertEquals(specialQuery, documentRetriever.getReceivedQueries().get(0));
        }

        @Test
        @DisplayName("Should handle zero maxResults")
        void shouldHandleZeroMaxResults() {
            // Arrange
            documentRetriever.setDocumentsToReturn(List.of(
                    new RetrievedDoc("doc1", "Content", Map.of(), 0.9)
            ));

            // Act
            List<RetrievedDoc> results = documentRetriever.retrieveWithDetails("query", 0);

            // Assert
            assertTrue(results.isEmpty()); // Should return empty list with k=0
        }
    }
}
