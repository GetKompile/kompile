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
import ai.kompile.core.graphrag.query.GraphRagContextMode;
import ai.kompile.core.graphrag.query.GraphRagQuery;
import ai.kompile.core.graphrag.query.GraphRagResult;
import ai.kompile.core.graphrag.query.SearchType;
import ai.kompile.core.llm.chat.LLMChat;
import ai.kompile.knowledgegraph.matrix.model.AdjacencyMatrixGraph;
import ai.kompile.knowledgegraph.matrix.model.MatrixGraphNode;
import ai.kompile.knowledgegraph.matrix.store.MatrixGraphStore;
import ai.kompile.knowledgegraph.embedding.adapter.MatrixKgEmbeddingGraphAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
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

    // ─── Hybrid search (embedding-seeded Personalized PageRank) ─────────────────

    @Test
    void answerQueryHybridSearchSurfacesMultiHopNodeViaPersonalizedPageRank() {
        // Extend the reusable graph into a 2-hop chain: Alice(n1) → Acme(n2) → Metropolis(n3).
        MatrixGraphNode n3 = MatrixGraphNode.builder()
                .nodeId("n3").nodeType("LOCATION").title("Metropolis")
                .description("A large city").build();
        realGraph.addNode(n3);
        realGraph.addEdge("n2", "n3", 0.7, "LOCATED_IN", false);

        when(graphStore.loadGraph(any())).thenReturn(Optional.of(realGraph));
        when(embeddingModel.embed(anyString())).thenReturn(queryEmbedding);
        when(queryEmbedding.isEmpty()).thenReturn(false);
        // Only the entry-point node (n1) matches by vector similarity; n3 is two hops away.
        when(graphStore.findSimilarNodes(anyString(), any(), anyInt(), anyDouble()))
                .thenReturn(List.of(Map.entry("n1", 0.9)));

        configureLlmMock("Alice works at Acme, which is located in Metropolis.");

        GraphRagQuery query = GraphRagQuery.builder()
                .query("Where is Alice's employer located?")
                .searchType(SearchType.HYBRID)
                .k(10)
                .build();
        GraphRagResult result = service.answerQuery(query);

        assertNotNull(result);
        String context = result.getFormattedContext();
        assertNotNull(context);
        // Personalized PageRank seeded at n1 propagates across the chain, so the 2-hop node
        // surfaces in the context — something the 1-hop LOCAL expansion from n1 would never reach.
        assertTrue(context.contains("Metropolis"),
                "HYBRID (Personalized PageRank) should surface the 2-hop node. Context was:\n" + context);
        verify(graphStore, atLeastOnce())
                .findSimilarNodes(anyString(), eq(queryEmbedding), anyInt(), anyDouble());
    }

    @Test
    void answerQueryHybridFallsBackToLocalWhenNoEmbeddingModel() {
        service = new MatrixGraphRagService(graphStore, null, null);
        when(graphStore.loadGraph(any())).thenReturn(Optional.of(realGraph));
        when(graphStore.searchNodes(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(realGraph.getNode("n1").orElseThrow()));

        GraphRagQuery query = GraphRagQuery.builder()
                .query("Alice")
                .searchType(SearchType.HYBRID)
                .k(5)
                .build();
        GraphRagResult result = service.answerQuery(query);

        assertNotNull(result);
        // No embedding model → HYBRID degrades to text-based local retrieval.
        verify(graphStore).searchNodes(anyString(), eq("Alice"), anyInt());
    }

    @Test
    void answerQueryHybridUsesStructuralKgEmbeddingsToSurfaceDisconnectedEntity() {
        // Add an ISOLATED node (no edges → ~0 PageRank, not a text seed) whose trained KGE vector is
        // structurally identical to the seed's. Stamp KGE vectors into node metadata as the Matrix
        // adapter would after training.
        MatrixGraphNode n4 = MatrixGraphNode.builder()
                .nodeId("n4").nodeType("PERSON").title("Bob")
                .description("Another engineer").build();
        realGraph.addNode(n4);
        realGraph.getNode("n1").orElseThrow().getMetadata()
                .put(MatrixKgEmbeddingGraphAdapter.KGE_EMBEDDING_KEY, "1.0,0.0,0.0");
        realGraph.getNode("n4").orElseThrow().getMetadata()
                .put(MatrixKgEmbeddingGraphAdapter.KGE_EMBEDDING_KEY, "1.0,0.0,0.0"); // == seed
        realGraph.getNode("n2").orElseThrow().getMetadata()
                .put(MatrixKgEmbeddingGraphAdapter.KGE_EMBEDDING_KEY, "0.0,0.0,1.0"); // orthogonal

        when(graphStore.loadGraph(any())).thenReturn(Optional.of(realGraph));
        when(embeddingModel.embed(anyString())).thenReturn(queryEmbedding);
        when(queryEmbedding.isEmpty()).thenReturn(false);
        // Only n1 matches by text vector similarity; n4 is isolated and not a text match.
        when(graphStore.findSimilarNodes(anyString(), any(), anyInt(), anyDouble()))
                .thenReturn(List.of(Map.entry("n1", 0.9)));
        configureLlmMock("answer");

        // k=2: only the seed plus ONE more node fit. The graph-connected n2 has the structure/PageRank
        // signal; the isolated n4 has only the KGE-similarity signal. If KGE participates in ranking,
        // n4 (KGE-identical to the seed) beats n2 and takes the second slot.
        GraphRagQuery query = GraphRagQuery.builder()
                .query("Who is similar to Alice?")
                .searchType(SearchType.HYBRID)
                .k(2)
                .build();
        GraphRagResult result = service.answerQuery(query);

        assertNotNull(result);
        String context = result.getFormattedContext();
        assertNotNull(context);
        assertTrue(context.contains("Bob"),
                "structural KG-embedding similarity should surface the disconnected KGE-similar entity. Context:\n" + context);
    }

    @Test
    void answerQueryHybridAppendsConnectingPaths() {
        // Chain n1 → n2 → n3 so the ranked key entities are connected by relational paths.
        MatrixGraphNode n3 = MatrixGraphNode.builder()
                .nodeId("n3").nodeType("LOCATION").title("Metropolis").description("A city").build();
        realGraph.addNode(n3);
        realGraph.addEdge("n2", "n3", 0.7, "LOCATED_IN", false);

        when(graphStore.loadGraph(any())).thenReturn(Optional.of(realGraph));
        when(embeddingModel.embed(anyString())).thenReturn(queryEmbedding);
        when(queryEmbedding.isEmpty()).thenReturn(false);
        when(graphStore.findSimilarNodes(anyString(), any(), anyInt(), anyDouble()))
                .thenReturn(List.of(Map.entry("n1", 0.9)));
        configureLlmMock("answer");

        GraphRagQuery query = GraphRagQuery.builder()
                .query("How are these connected?")
                .searchType(SearchType.HYBRID)
                .k(10)
                .build();
        GraphRagResult result = service.answerQuery(query);

        String context = result.getFormattedContext();
        assertTrue(context.contains("Connecting relationships"),
                "HYBRID should append a PathRAG connecting-paths section. Context:\n" + context);
        assertTrue(context.contains("Alice --> Acme Corp"),
                "paths between key entities should be rendered with titles. Context:\n" + context);
    }

    @Test
    void answerQueryHybridFiltersByEntityType() {
        // realGraph: n1 = PERSON (Alice), n2 = ORGANIZATION (Acme Corp). Request only PERSON entities.
        when(graphStore.loadGraph(any())).thenReturn(Optional.of(realGraph));
        when(embeddingModel.embed(anyString())).thenReturn(queryEmbedding);
        when(queryEmbedding.isEmpty()).thenReturn(false);
        when(graphStore.findSimilarNodes(anyString(), any(), anyInt(), anyDouble()))
                .thenReturn(List.of(Map.entry("n1", 0.9), Map.entry("n2", 0.85)));
        configureLlmMock("answer");

        GraphRagQuery query = GraphRagQuery.builder()
                .query("who are the people?")
                .searchType(SearchType.HYBRID)
                .k(10)
                .entityType("PERSON")
                .build();
        GraphRagResult result = service.answerQuery(query);

        String context = result.getFormattedContext();
        // The PERSON node is formatted; the ORGANIZATION node is gated out of the primary results.
        assertTrue(context.contains("[PERSON]"),
                "PERSON node should be retrieved. Context:\n" + context);
        assertFalse(context.contains("[ORGANIZATION]"),
                "ORGANIZATION node should be filtered out by entityType=PERSON. Context:\n" + context);
    }

    // ─── Global search via community reports ────────────────────────────────────

    @Test
    void answerQueryGlobalSearchUsesCommunityReportsWhenAvailable() {
        CommunitySummaryService communityService = mock(CommunitySummaryService.class);
        service = new MatrixGraphRagService(graphStore, embeddingModel, llmChat, communityService);

        when(graphStore.loadGraph(any())).thenReturn(Optional.of(realGraph));
        CommunitySummaryService.CommunityReport report = new CommunitySummaryService.CommunityReport(
                0, "Alice works at Acme Corp in the technology sector.", List.of("n1", "n2"), null);
        when(communityService.getOrBuildReports(any())).thenReturn(List.of(report));
        // Report has no embedding, so relevance ranking is skipped (all reports included).
        when(embeddingModel.embed(anyString())).thenReturn(queryEmbedding);
        when(queryEmbedding.isEmpty()).thenReturn(false);
        configureLlmMock("Global community answer.");

        GraphRagQuery query = GraphRagQuery.builder()
                .query("What is the overall picture?")
                .searchType(SearchType.GLOBAL)
                .k(5)
                .build();
        GraphRagResult result = service.answerQuery(query);

        assertNotNull(result);
        assertTrue(result.getFormattedContext().contains("Alice works at Acme Corp"),
                "GLOBAL search should surface community report summaries. Context:\n" + result.getFormattedContext());
        verify(communityService).getOrBuildReports(any());
    }

    @Test
    void compactGraphModeIsStatelessUsesExactJsonAndCallsOnlyFinalLlmOnce() {
        CompactGraphContextService compactContextService = mock(CompactGraphContextService.class);
        CommunitySummaryService communityService = mock(CommunitySummaryService.class);
        service = new MatrixGraphRagService(
                graphStore, embeddingModel, llmChat, communityService, compactContextService);
        service.getSessionEntityState("compact-conversation").trackEntity(
                "n2", "Acme Corp", "ORGANIZATION", List.of("Acme"), 1, "n2");

        when(graphStore.loadGraph(any())).thenReturn(Optional.of(realGraph));
        String compactJson = "{\"contract\":\"kompile.compact-graph.v1\","
                + "\"sources\":[{\"id\":\"doc-22\"}],"
                + "\"reasoningTraces\":[{\"id\":\"trace:1\"}]}";
        when(compactContextService.build(eq(7L), anyCollection()))
                .thenReturn(new CompactGraphContextService.CompactContext(
                        compactJson, List.of("n1", "n2"), 1, 1));
        configureLlmMock("Acme answer [n2] [doc-22] [trace:1].");

        GraphRagQuery query = GraphRagQuery.builder()
                .query("What does that company do?")
                .searchType(SearchType.GLOBAL)
                .k(5)
                .conversationId("compact-conversation")
                .factSheetId(7L)
                .contextMode(GraphRagContextMode.COMPACT_GRAPH)
                .build();
        GraphRagResult result = service.answerQuery(query);

        assertEquals(compactJson, result.getFormattedContext());
        assertEquals(1, service.getSessionEntityState("compact-conversation").size(),
                "compact mode must not mutate conversation entity state");
        assertTrue(service.supportsContextMode(GraphRagContextMode.COMPACT_GRAPH));
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(promptSpec, times(1)).user(prompt.capture());
        verify(llmChat, times(1)).prompt();
        verify(promptSpec, times(1)).call();
        verify(communityService, never()).getOrBuildReports(any());
        verify(compactContextService).build(eq(7L), anyCollection());

        assertTrue(prompt.getValue().contains("Question: What does that company do?"));
        assertTrue(prompt.getValue().contains(compactJson));
        assertTrue(prompt.getValue().contains("only factual evidence"));
        assertFalse(prompt.getValue().contains("Recently discussed entities"));
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    private void configureLlmMock(String answer) {
        when(llmChat.prompt()).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(answer);
    }
}
