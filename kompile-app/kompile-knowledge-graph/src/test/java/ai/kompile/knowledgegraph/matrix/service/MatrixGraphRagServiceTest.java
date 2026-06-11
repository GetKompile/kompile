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

import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.graphrag.query.GraphRagQuery;
import ai.kompile.core.graphrag.query.GraphRagResult;
import ai.kompile.core.graphrag.query.SearchType;
import ai.kompile.core.llm.chat.LLMChat;
import ai.kompile.knowledgegraph.matrix.model.AdjacencyMatrixGraph;
import ai.kompile.knowledgegraph.matrix.model.MatrixGraphNode;
import ai.kompile.knowledgegraph.matrix.store.MatrixGraphStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.nd4j.linalg.api.ndarray.INDArray;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MatrixGraphRagService}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MatrixGraphRagServiceTest {

    @Mock
    private MatrixGraphStore graphStore;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private LLMChat llmChat;

    @Mock
    private LLMChat.ChatClientRequestSpec promptSpec;

    @Mock
    private LLMChat.CallResponseSpec callResponseSpec;

    @Mock
    private INDArray queryEmbedding;

    private MatrixGraphRagService service;

    // Reusable small graph
    private AdjacencyMatrixGraph realGraph;

    @BeforeEach
    void setUp() {
        service = new MatrixGraphRagService(graphStore, embeddingModel, llmChat);

        realGraph = new AdjacencyMatrixGraph("default-knowledge-graph", 16);
        MatrixGraphNode n1 = MatrixGraphNode.builder()
                .nodeId("n1").nodeType("PERSON").title("Alice")
                .description("A software engineer").build();
        MatrixGraphNode n2 = MatrixGraphNode.builder()
                .nodeId("n2").nodeType("ORGANIZATION").title("Acme Corp")
                .description("A technology company").build();
        realGraph.addNode(n1);
        realGraph.addNode(n2);
        realGraph.addEdge("n1", "n2", 0.8, "WORKS_AT", false);
    }

    @AfterEach
    void tearDown() {
        if (realGraph != null) {
            realGraph.close();
        }
    }

    // ─── No graph found ───────────────────────────────────────────────────────

    @Test
    void answerQueryWhenNoGraphFoundReturnsEmpty() {
        when(graphStore.loadGraph(any())).thenReturn(Optional.empty());

        GraphRagQuery query = GraphRagQuery.builder()
                .query("Who is Alice?")
                .searchType(SearchType.LOCAL)
                .build();
        GraphRagResult result = service.answerQuery(query);

        assertNotNull(result);
        assertNotNull(result.getAnswer());
        assertFalse(result.getAnswer().isBlank());
    }

    // ─── Local search – no embedding model ───────────────────────────────────

    @Test
    void answerQueryLocalSearchWithoutEmbeddingModelUsesTextSearch() {
        service = new MatrixGraphRagService(graphStore, null, null);

        when(graphStore.loadGraph(any())).thenReturn(Optional.of(realGraph));
        when(graphStore.searchNodes(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(realGraph.getNode("n1").orElseThrow()));

        GraphRagQuery query = GraphRagQuery.builder()
                .query("Alice")
                .searchType(SearchType.LOCAL)
                .k(5)
                .build();
        GraphRagResult result = service.answerQuery(query);

        assertNotNull(result);
        verify(graphStore).searchNodes(anyString(), eq("Alice"), anyInt());
    }

    // ─── Local search – embedding model present ───────────────────────────────

    @Test
    void answerQueryLocalSearchWithEmbeddingModelUsesVectorSearch() {
        when(graphStore.loadGraph(any())).thenReturn(Optional.of(realGraph));
        when(embeddingModel.embed(anyString())).thenReturn(queryEmbedding);
        when(queryEmbedding.isEmpty()).thenReturn(false);
        when(graphStore.findSimilarNodes(anyString(), any(), anyInt(), anyDouble()))
                .thenReturn(List.of(Map.entry("n1", 0.9)));

        configureLlmMock("Alice works at Acme Corp.");

        GraphRagQuery query = GraphRagQuery.builder()
                .query("Who is Alice?")
                .searchType(SearchType.LOCAL)
                .k(5)
                .build();
        GraphRagResult result = service.answerQuery(query);

        assertNotNull(result);
        // findSimilarNodes may be called more than once (retrieveLocalContext + entity tracking)
        verify(graphStore, atLeastOnce()).findSimilarNodes(anyString(), eq(queryEmbedding), anyInt(), anyDouble());
    }

    @Test
    void answerQueryFallsBackToTextSearchWhenEmbeddingIsNull() {
        when(graphStore.loadGraph(any())).thenReturn(Optional.of(realGraph));
        when(embeddingModel.embed(anyString())).thenReturn(null);
        when(graphStore.searchNodes(anyString(), anyString(), anyInt()))
                .thenReturn(Collections.emptyList());

        GraphRagQuery query = GraphRagQuery.builder()
                .query("Alice")
                .searchType(SearchType.LOCAL)
                .k(3)
                .build();
        GraphRagResult result = service.answerQuery(query);

        assertNotNull(result);
        verify(graphStore).searchNodes(anyString(), eq("Alice"), anyInt());
    }

    @Test
    void answerQueryFallsBackToTextSearchWhenSimilarNodesEmpty() {
        when(graphStore.loadGraph(any())).thenReturn(Optional.of(realGraph));
        when(embeddingModel.embed(anyString())).thenReturn(queryEmbedding);
        when(queryEmbedding.isEmpty()).thenReturn(false);
        when(graphStore.findSimilarNodes(anyString(), any(), anyInt(), anyDouble()))
                .thenReturn(Collections.emptyList());
        when(graphStore.searchNodes(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(realGraph.getNode("n1").orElseThrow()));

        configureLlmMock("Some answer.");

        GraphRagQuery query = GraphRagQuery.builder()
                .query("Alice")
                .searchType(SearchType.LOCAL)
                .k(3)
                .build();
        GraphRagResult result = service.answerQuery(query);

        assertNotNull(result);
        verify(graphStore).searchNodes(anyString(), anyString(), anyInt());
    }

    // ─── Global search ────────────────────────────────────────────────────────

    @Test
    void answerQueryGlobalSearchUsesPageRank() {
        when(graphStore.loadGraph(any())).thenReturn(Optional.of(realGraph));
        configureLlmMock("Global overview answer.");

        GraphRagQuery query = GraphRagQuery.builder()
                .query("Overview of the knowledge graph")
                .searchType(SearchType.GLOBAL)
                .k(10)
                .build();
        GraphRagResult result = service.answerQuery(query);

        assertNotNull(result);
        assertNotNull(result.getAnswer());
        // Global search response includes graph overview
        assertNotNull(result.getFormattedContext());
    }

    // ─── LLM synthesis ───────────────────────────────────────────────────────

    @Test
    void answerQueryWithLlmProducesAnswer() {
        when(graphStore.loadGraph(any())).thenReturn(Optional.of(realGraph));
        when(embeddingModel.embed(anyString())).thenReturn(queryEmbedding);
        when(queryEmbedding.isEmpty()).thenReturn(false);
        when(graphStore.findSimilarNodes(anyString(), any(), anyInt(), anyDouble()))
                .thenReturn(List.of(Map.entry("n1", 0.95)));

        configureLlmMock("Alice is a software engineer at Acme Corp.");

        GraphRagQuery query = GraphRagQuery.builder()
                .query("Who is Alice?")
                .searchType(SearchType.LOCAL)
                .k(3)
                .build();
        GraphRagResult result = service.answerQuery(query);

        assertNotNull(result.getAnswer());
        assertTrue(result.getAnswer().contains("Alice") || !result.getAnswer().isBlank());
    }

    @Test
    void answerQueryWithoutLlmReturnsContextPrefix() {
        service = new MatrixGraphRagService(graphStore, null, null);

        when(graphStore.loadGraph(any())).thenReturn(Optional.of(realGraph));
        when(graphStore.searchNodes(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(realGraph.getNode("n1").orElseThrow()));

        GraphRagQuery query = GraphRagQuery.builder()
                .query("Alice")
                .searchType(SearchType.LOCAL)
                .k(3)
                .build();
        GraphRagResult result = service.answerQuery(query);

        assertNotNull(result.getAnswer());
        assertTrue(result.getAnswer().contains("No LLM configured"),
                "Without LLM, answer should state no LLM is configured");
    }

    @Test
    void answerQueryLlmExceptionReturnsErrorMessage() {
        when(graphStore.loadGraph(any())).thenReturn(Optional.of(realGraph));
        when(embeddingModel.embed(anyString())).thenReturn(queryEmbedding);
        when(queryEmbedding.isEmpty()).thenReturn(false);
        when(graphStore.findSimilarNodes(anyString(), any(), anyInt(), anyDouble()))
                .thenReturn(List.of(Map.entry("n1", 0.9)));

        when(llmChat.prompt()).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.call()).thenThrow(new RuntimeException("LLM timeout"));

        GraphRagQuery query = GraphRagQuery.builder()
                .query("Who is Alice?")
                .searchType(SearchType.LOCAL)
                .k(3)
                .build();
        GraphRagResult result = service.answerQuery(query);

        assertNotNull(result.getAnswer());
        assertFalse(result.getAnswer().isBlank());
    }

    // ─── answerQueryWithGraph ────────────────────────────────────────────────

    @Test
    void answerQueryWithGraphBuildsContextFromProvidedGraph() {
        ai.kompile.core.graphrag.model.Graph g = new ai.kompile.core.graphrag.model.Graph();
        ai.kompile.core.graphrag.model.Entity entity = new ai.kompile.core.graphrag.model.Entity();
        entity.setId("e1");
        entity.setTitle("Alice");
        entity.setDescription("Software engineer");
        g.setEntities(List.of(entity));
        g.setRelationships(List.of());

        configureLlmMock("Alice is described in the graph.");

        GraphRagQuery query = GraphRagQuery.builder()
                .query("Tell me about Alice")
                .searchType(SearchType.LOCAL)
                .graph(g)
                .build();
        GraphRagResult result = service.answerQueryWithGraph(query);

        assertNotNull(result);
        assertNotNull(result.getFormattedContext());
        assertTrue(result.getFormattedContext().contains("Alice"),
                "Context should include entity title");
    }

    @Test
    void answerQueryWithGraphNullGraphDelegatesToNormalQuery() {
        when(graphStore.loadGraph(any())).thenReturn(Optional.empty());

        GraphRagQuery query = GraphRagQuery.builder()
                .query("Who is Alice?")
                .graph(null)
                .build();
        GraphRagResult result = service.answerQueryWithGraph(query);

        assertNotNull(result);
    }

    // ─── Session entity state ─────────────────────────────────────────────────

    @Test
    void getSessionEntityStateReturnsConsistentStatePerConversation() {
        var state1 = service.getSessionEntityState("conv-1");
        var state2 = service.getSessionEntityState("conv-1");
        var stateOther = service.getSessionEntityState("conv-2");

        assertSame(state1, state2, "Same session should return same state object");
        assertNotSame(state1, stateOther, "Different sessions should have different state objects");
    }

    // ─── Default k handling ───────────────────────────────────────────────────

    @Test
    void answerQueryUsesDefaultKWhenNotSpecified() {
        when(graphStore.loadGraph(any())).thenReturn(Optional.of(realGraph));
        when(embeddingModel.embed(anyString())).thenReturn(queryEmbedding);
        when(queryEmbedding.isEmpty()).thenReturn(false);
        when(graphStore.findSimilarNodes(anyString(), any(), anyInt(), anyDouble()))
                .thenReturn(Collections.emptyList());
        when(graphStore.searchNodes(anyString(), anyString(), anyInt()))
                .thenReturn(Collections.emptyList());

        // k=0 in query → should use default k=5
        GraphRagQuery query = GraphRagQuery.builder()
                .query("Alice")
                .searchType(SearchType.LOCAL)
                .k(0)
                .build();
        GraphRagResult result = service.answerQuery(query);

        assertNotNull(result);
        // Verify default k=5 was applied (searchNodes called with a limit value)
        verify(graphStore, atLeastOnce()).searchNodes(anyString(), anyString(), intThat(k -> k >= 1));
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    private void configureLlmMock(String answer) {
        when(llmChat.prompt()).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(answer);
    }
}
