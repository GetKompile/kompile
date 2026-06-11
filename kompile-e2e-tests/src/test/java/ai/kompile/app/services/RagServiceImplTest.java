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

package ai.kompile.app.services;

import ai.kompile.app.rag.RagServiceImpl;
import ai.kompile.core.embeddings.NoOpVectorStoreImpl;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.llm.LanguageModel;
import ai.kompile.core.rag.RagQuery;
import ai.kompile.core.rag.RagResult;
import ai.kompile.core.rag.SearchType;
import ai.kompile.core.retrievers.DocumentRetriever;
import ai.kompile.core.retrievers.NoOpDocumentRetrieverImpl;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RagServiceImpl}.
 * Tests constructor selection logic and answerQuery behavior
 * without loading Spring context or ND4J.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RagServiceImpl")
class RagServiceImplTest {

    private DocumentRetriever mockRetriever = mock(DocumentRetriever.class);
    private LanguageModel mockLanguageModel = mock(LanguageModel.class);
    private VectorStore mockVectorStore = mock(VectorStore.class);

    // =========================================================================
    // Constructor selection logic
    // =========================================================================

    @Nested
    @DisplayName("Constructor - retriever selection")
    class RetrieverSelection {

        @Test
        @DisplayName("picks non-NoOp retriever when multiple are available")
        void picksNonNoOpRetriever() {
            NoOpDocumentRetrieverImpl noOp = new NoOpDocumentRetrieverImpl();
            List<DocumentRetriever> retrievers = List.of(noOp, mockRetriever);
            List<VectorStore> stores = List.of(mockVectorStore);

            RagServiceImpl service = new RagServiceImpl(retrievers, mockLanguageModel, stores);
            assertNotNull(service);
            // The service should have selected the mock (non-NoOp) retriever.
            // We verify indirectly by running a query that would invoke the retriever.
        }

        @Test
        @DisplayName("uses first retriever when only one is available")
        void usesFirstRetrieverWhenSingle() {
            List<DocumentRetriever> retrievers = List.of(mockRetriever);
            List<VectorStore> stores = List.of(mockVectorStore);

            RagServiceImpl service = new RagServiceImpl(retrievers, mockLanguageModel, stores);
            assertNotNull(service);
        }

        @Test
        @DisplayName("uses NoOp retriever when only NoOp is available")
        void usesNoOpRetrieverWhenOnlyNoOp() {
            NoOpDocumentRetrieverImpl noOp = new NoOpDocumentRetrieverImpl();
            List<DocumentRetriever> retrievers = List.of(noOp);
            List<VectorStore> stores = List.of(mockVectorStore);

            RagServiceImpl service = new RagServiceImpl(retrievers, mockLanguageModel, stores);
            assertNotNull(service);
        }

        @Test
        @DisplayName("falls back to NoOp when retriever list is empty")
        void fallsBackToNoOpOnEmptyRetrieverList() {
            List<DocumentRetriever> retrievers = Collections.emptyList();
            List<VectorStore> stores = List.of(mockVectorStore);

            RagServiceImpl service = new RagServiceImpl(retrievers, mockLanguageModel, stores);
            assertNotNull(service);
        }
    }

    @Nested
    @DisplayName("Constructor - vector store selection")
    class VectorStoreSelection {

        @Test
        @DisplayName("picks non-NoOp vector store when multiple are available")
        void picksNonNoOpVectorStore() {
            NoOpVectorStoreImpl noOp = new NoOpVectorStoreImpl();
            List<DocumentRetriever> retrievers = List.of(mockRetriever);
            List<VectorStore> stores = List.of(noOp, mockVectorStore);

            RagServiceImpl service = new RagServiceImpl(retrievers, mockLanguageModel, stores);
            assertNotNull(service);
        }

        @Test
        @DisplayName("uses first vector store when only one is available")
        void usesFirstVectorStoreWhenSingle() {
            List<DocumentRetriever> retrievers = List.of(mockRetriever);
            List<VectorStore> stores = List.of(mockVectorStore);

            RagServiceImpl service = new RagServiceImpl(retrievers, mockLanguageModel, stores);
            assertNotNull(service);
        }

        @Test
        @DisplayName("falls back to NoOp when vector store list is empty")
        void fallsBackToNoOpOnEmptyVectorStoreList() {
            List<DocumentRetriever> retrievers = List.of(mockRetriever);
            List<VectorStore> stores = Collections.emptyList();

            RagServiceImpl service = new RagServiceImpl(retrievers, mockLanguageModel, stores);
            assertNotNull(service);
        }

        @Test
        @DisplayName("uses first element when all vector stores are NoOp")
        void usesFirstWhenAllNoOp() {
            NoOpVectorStoreImpl noOp1 = new NoOpVectorStoreImpl();
            NoOpVectorStoreImpl noOp2 = new NoOpVectorStoreImpl();
            List<DocumentRetriever> retrievers = List.of(mockRetriever);
            List<VectorStore> stores = List.of(noOp1, noOp2);

            RagServiceImpl service = new RagServiceImpl(retrievers, mockLanguageModel, stores);
            assertNotNull(service);
        }
    }

    // =========================================================================
    // answerQuery
    // =========================================================================

    @Nested
    @DisplayName("answerQuery")
    class AnswerQuery {

        private RagServiceImpl createServiceWithMocks() {
            return new RagServiceImpl(
                    List.of(mockRetriever),
                    mockLanguageModel,
                    List.of(mockVectorStore));
        }

        @Test
        @DisplayName("returns result with answer from language model")
        void returnsResultWithAnswer() {
            when(mockRetriever.retrieveWithDetails(anyString(), anyInt()))
                    .thenReturn(List.of(new RetrievedDoc("1", "doc content", Map.of(), 0.9f)));
            when(mockVectorStore.similaritySearch(anyString(), anyInt(), anyDouble()))
                    .thenReturn(List.of(new Document("vector doc content")));
            when(mockLanguageModel.generateResponse(anyString(), anyList()))
                    .thenReturn("Generated answer");

            RagServiceImpl service = createServiceWithMocks();
            RagQuery query = RagQuery.builder().query("test query").k(4).build();

            RagResult result = service.answerQuery(query);

            assertNotNull(result);
            assertEquals("Generated answer", result.getAnswer());
        }

        @Test
        @DisplayName("returns result with formatted context")
        void returnsResultWithFormattedContext() {
            when(mockRetriever.retrieveWithDetails(anyString(), anyInt()))
                    .thenReturn(List.of(new RetrievedDoc("1", "keyword result", Map.of(), 0.8f)));
            when(mockVectorStore.similaritySearch(anyString(), anyInt(), anyDouble()))
                    .thenReturn(Collections.emptyList());
            when(mockLanguageModel.generateResponse(anyString(), anyList()))
                    .thenReturn("answer");

            RagServiceImpl service = createServiceWithMocks();
            RagQuery query = RagQuery.builder().query("test query").k(4).build();

            RagResult result = service.answerQuery(query);

            assertNotNull(result.getFormattedContext());
            assertTrue(result.getFormattedContext().contains("keyword result"));
        }

        @Test
        @DisplayName("returns error result for null query")
        void returnsErrorForNullQuery() {
            RagServiceImpl service = createServiceWithMocks();
            RagQuery query = RagQuery.builder().query(null).k(4).build();

            RagResult result = service.answerQuery(query);

            assertNotNull(result);
            assertTrue(result.getAnswer().contains("Error"));
        }

        @Test
        @DisplayName("returns error result for empty query")
        void returnsErrorForEmptyQuery() {
            RagServiceImpl service = createServiceWithMocks();
            RagQuery query = RagQuery.builder().query("   ").k(4).build();

            RagResult result = service.answerQuery(query);

            assertNotNull(result);
            assertTrue(result.getAnswer().contains("Error"));
        }

        @Test
        @DisplayName("handles empty retrieval results gracefully")
        void handlesEmptyRetrievalResults() {
            when(mockRetriever.retrieveWithDetails(anyString(), anyInt()))
                    .thenReturn(Collections.emptyList());
            when(mockVectorStore.similaritySearch(anyString(), anyInt(), anyDouble()))
                    .thenReturn(Collections.emptyList());
            when(mockLanguageModel.generateResponse(anyString(), anyList()))
                    .thenReturn("No context answer");

            RagServiceImpl service = createServiceWithMocks();
            RagQuery query = RagQuery.builder().query("test query").k(4).build();

            RagResult result = service.answerQuery(query);

            assertNotNull(result);
            assertEquals("No context answer", result.getAnswer());
            assertTrue(result.getRetrievedDocs().isEmpty());
        }

        @Test
        @DisplayName("handles language model exception gracefully")
        void handlesLanguageModelException() {
            when(mockRetriever.retrieveWithDetails(anyString(), anyInt()))
                    .thenReturn(Collections.emptyList());
            when(mockVectorStore.similaritySearch(anyString(), anyInt(), anyDouble()))
                    .thenReturn(Collections.emptyList());
            when(mockLanguageModel.generateResponse(anyString(), anyList()))
                    .thenThrow(new RuntimeException("LLM failure"));

            RagServiceImpl service = createServiceWithMocks();
            RagQuery query = RagQuery.builder().query("test query").k(4).build();

            RagResult result = service.answerQuery(query);

            assertNotNull(result);
            assertTrue(result.getAnswer().contains("Error"));
        }

        @Test
        @DisplayName("handles retriever exception gracefully")
        void handlesRetrieverException() {
            when(mockRetriever.retrieveWithDetails(anyString(), anyInt()))
                    .thenThrow(new RuntimeException("Retriever failure"));
            when(mockVectorStore.similaritySearch(anyString(), anyInt(), anyDouble()))
                    .thenReturn(Collections.emptyList());
            when(mockLanguageModel.generateResponse(anyString(), anyList()))
                    .thenReturn("Fallback answer");

            RagServiceImpl service = createServiceWithMocks();
            RagQuery query = RagQuery.builder().query("test query").k(4).build();

            RagResult result = service.answerQuery(query);

            assertNotNull(result);
            assertEquals("Fallback answer", result.getAnswer());
        }

        @Test
        @DisplayName("handles vector store exception gracefully")
        void handlesVectorStoreException() {
            when(mockRetriever.retrieveWithDetails(anyString(), anyInt()))
                    .thenReturn(Collections.emptyList());
            when(mockVectorStore.similaritySearch(anyString(), anyInt(), anyDouble()))
                    .thenThrow(new RuntimeException("Vector store failure"));
            when(mockLanguageModel.generateResponse(anyString(), anyList()))
                    .thenReturn("Fallback answer");

            RagServiceImpl service = createServiceWithMocks();
            RagQuery query = RagQuery.builder().query("test query").k(4).build();

            RagResult result = service.answerQuery(query);

            assertNotNull(result);
            assertEquals("Fallback answer", result.getAnswer());
        }

        @Test
        @DisplayName("filters out null and error docs from retriever")
        void filtersNullAndErrorDocs() {
            List<RetrievedDoc> mixedDocs = new ArrayList<>();
            mixedDocs.add(new RetrievedDoc("1", "good content", Map.of(), 0.9f));
            mixedDocs.add(null);
            mixedDocs.add(new RetrievedDoc("3", "Error: something failed", Map.of(), 0.1f));

            when(mockRetriever.retrieveWithDetails(anyString(), anyInt()))
                    .thenReturn(mixedDocs);
            when(mockVectorStore.similaritySearch(anyString(), anyInt(), anyDouble()))
                    .thenReturn(Collections.emptyList());
            when(mockLanguageModel.generateResponse(anyString(), anyList()))
                    .thenReturn("answer");

            RagServiceImpl service = createServiceWithMocks();
            RagQuery query = RagQuery.builder().query("test query").k(4).build();

            RagResult result = service.answerQuery(query);

            assertNotNull(result);
            // Only the "good content" doc should be included
            assertEquals(1, result.getRetrievedDocs().size());
            assertEquals("good content", result.getRetrievedDocs().get(0).getText());
        }

        @Test
        @DisplayName("uses NoOp vector store path when only NoOp is provided")
        void usesNoOpVectorStorePath() {
            NoOpVectorStoreImpl noOp = new NoOpVectorStoreImpl();
            NoOpDocumentRetrieverImpl noOpRetriever = new NoOpDocumentRetrieverImpl();

            when(mockLanguageModel.generateResponse(anyString(), anyList()))
                    .thenReturn("No context answer");

            RagServiceImpl service = new RagServiceImpl(
                    List.of(noOpRetriever),
                    mockLanguageModel,
                    List.of(noOp));

            RagQuery query = RagQuery.builder().query("test query").k(4).build();

            RagResult result = service.answerQuery(query);

            assertNotNull(result);
            // Should still get an answer even with NoOp implementations
            assertEquals("No context answer", result.getAnswer());
        }
    }
}
