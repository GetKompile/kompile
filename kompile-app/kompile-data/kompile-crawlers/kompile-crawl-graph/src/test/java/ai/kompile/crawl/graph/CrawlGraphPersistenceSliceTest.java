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

package ai.kompile.crawl.graph;

import ai.kompile.app.core.chunking.TextChunker;
import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.core.crawl.graph.UnifiedCrawlSource;
import ai.kompile.core.crawl.graph.VectorIndexConfig;
import ai.kompile.core.crawl.graph.archive.CrawlStepArchiveService;
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.llm.chat.LLMChat;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.crawler.CrawlerService;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.matrix.service.MatrixKnowledgeGraphService;
import ai.kompile.knowledgegraph.matrix.store.InMemoryMatrixGraphStore;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * Crawl-pipeline slice with REAL graph persistence: unlike {@link UnifiedCrawlGraphServiceImplTest}
 * (which mocks {@code KnowledgeGraphService} and can only verify calls), this slice wires the real
 * {@link MatrixKnowledgeGraphService} onto an {@link InMemoryMatrixGraphStore}, so a crawl's
 * GRAPH_EXTRACTION output can be asserted as nodes and edges that actually landed in the store —
 * titles, node counts, and semantic relation types included.
 */
@SpringBootTest(classes = {
        CrawlGraphPersistenceSliceTest.TestConfig.class,
        CrawlGraphPersistenceSliceTest.RealGraphStore.class,
        CrawlGraphPersistenceSliceTest.Mocks.class})
class CrawlGraphPersistenceSliceTest {

    private static final long FACT_SHEET_ID = 7L;

    /** Same slice as the main impl test: scan crawl-graph, exclude sibling test configs. */
    @SpringBootConfiguration
    @ComponentScan(
            basePackageClasses = UnifiedCrawlGraphServiceImpl.class,
            excludeFilters = @ComponentScan.Filter(
                    type = FilterType.ANNOTATION,
                    classes = {SpringBootConfiguration.class, TestConfiguration.class}))
    static class TestConfig {}

    /** The real persistence stack: matrix service over the heap-backed store. */
    @TestConfiguration
    static class RealGraphStore {
        @Bean
        InMemoryMatrixGraphStore matrixGraphStore() {
            return new InMemoryMatrixGraphStore();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        KnowledgeGraphService knowledgeGraphService() {
            return new MatrixKnowledgeGraphService();
        }
    }

    /** Named leaf mocks (duplicate-type beans), as in the main impl test. */
    @TestConfiguration
    static class Mocks {
        @Bean DocumentLoader fileLoader() { return org.mockito.Mockito.mock(DocumentLoader.class); }
        @Bean DocumentLoader emailLoader() { return org.mockito.Mockito.mock(DocumentLoader.class); }
        @Bean TextChunker tableAwareChunker() { return org.mockito.Mockito.mock(TextChunker.class); }
        @Bean TextChunker htmlChunker() { return org.mockito.Mockito.mock(TextChunker.class); }
    }

    @Autowired private UnifiedCrawlGraphServiceImpl service;
    @Autowired private GraphExtractionOrchestrator orchestrator;
    @Autowired private KnowledgeGraphService knowledgeGraphService;
    @Autowired private InMemoryMatrixGraphStore store;
    @SpyBean private CrawlRuntimeConfigManager runtimeConfigManager;
    @MockBean private CrawlerService crawlerService;
    @MockBean private VectorStore vectorStore;
    @MockBean private EmbeddingModel embeddingModel;
    @MockBean private LLMChat llmChat;
    @MockBean private CrossDocumentRelationCallback crossDocumentRelationCallback;
    @MockBean private CrawlStepArchiveService crawlStepArchiveService;
    @MockBean private GraphExtractionCheckpointStore graphExtractionCheckpointStore;
    @Autowired private DocumentLoader fileLoader;
    @Autowired private TextChunker tableAwareChunker;
    @Autowired private TextChunker htmlChunker;

    private LLMChat.CallResponseSpec callResponseSpec;

    @BeforeEach
    void setUp() {
        service.cleanupJobs();
        store.clearAll();
        orchestrator.graphConstructor = null;

        CrawlRuntimeConfigManager.CrawlRuntimeConfig cfg = CrawlRuntimeConfigManager.CrawlRuntimeConfig.defaults();
        cfg.retainResultGraph = true;
        cfg.graphExtractionBatchSize = 10;
        cfg.graphExtractionParallelism = 1;
        cfg.backgroundGraphThreads = 1;
        cfg.graphExtractionChunksPerPrompt = 1;
        doReturn(cfg).when(runtimeConfigManager).refreshRuntimeConfig();

        when(graphExtractionCheckpointStore.completedChunkKeys(any(), any())).thenReturn(Set.of());

        LLMChat.ChatClientRequestSpec requestSpec = org.mockito.Mockito.mock(LLMChat.ChatClientRequestSpec.class);
        callResponseSpec = org.mockito.Mockito.mock(LLMChat.CallResponseSpec.class);
        when(llmChat.prompt(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);

        when(fileLoader.supports(argThat(d -> d != null
                && (d.getType() == DocumentSourceDescriptor.SourceType.FILE
                || d.getType() == DocumentSourceDescriptor.SourceType.DIRECTORY))))
                .thenReturn(true);
        when(fileLoader.getName()).thenReturn("File Loader");

        when(tableAwareChunker.getName()).thenReturn("table-aware");
        when(htmlChunker.getName()).thenReturn("html");
        when(tableAwareChunker.chunk(any(RetrievedDoc.class), anyMap()))
                .thenAnswer(inv -> List.of((RetrievedDoc) inv.getArgument(0)));
        when(htmlChunker.chunk(any(RetrievedDoc.class), anyMap()))
                .thenAnswer(inv -> List.of((RetrievedDoc) inv.getArgument(0)));
        when(tableAwareChunker.getDefaultOptions()).thenReturn(Map.of("chunkSize", 1000));
        when(htmlChunker.getDefaultOptions()).thenReturn(Map.of("chunkSize", 1000));

        when(embeddingModel.isInitialized()).thenReturn(true);
        when(embeddingModel.getOptimalBatchSize()).thenReturn(32);
        when(embeddingModel.getMaxBatchSize()).thenReturn(128);
        when(embeddingModel.embedBatch(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return texts.stream().map(t -> new float[]{0.1f, 0.2f, 0.3f}).collect(Collectors.toList());
        });
        when(vectorStore.addWithFloatArrayEmbeddings(anyList(), any(float[][].class)))
                .thenAnswer(invocation -> ((List<?>) invocation.getArgument(0)).size());

        when(crossDocumentRelationCallback.extractRelationsFromGraphNodes(any())).thenReturn(0);
    }

    @Test
    @DisplayName("A crawl's extracted graph actually lands in the matrix store — nodes, edges, semantic types")
    void crawlPersistsExtractedGraphIntoTheRealMatrixStore() throws Exception {
        when(fileLoader.load(any(DocumentSourceDescriptor.class), any())).thenReturn(List.of(
                new Document("""
                        Dana Reyes requested purchase order PO-7001 for the engineering laptop refresh
                        on 2026-03-02. Erin Wu, the procurement lead, approved PO-7001 on 2026-03-04.
                        Initech Finance paid the supplier invoice for PO-7001 on 2026-03-09.
                        """, Map.of())
        ));
        when(callResponseSpec.content()).thenReturn("""
                {"$schema":"kompile-graph-extraction/v1",
                 "entities":[
                   {"id":"po-7001","name":"PO-7001","type":"PURCHASE_ORDER","confidence":0.95},
                   {"id":"dana-reyes","name":"Dana Reyes","type":"PERSON","confidence":0.9},
                   {"id":"erin-wu","name":"Erin Wu","type":"PERSON","confidence":0.9},
                   {"id":"initech-finance","name":"Initech Finance","type":"ORGANIZATION","confidence":0.9}],
                 "relations":[
                   {"source":"po-7001","target":"dana-reyes","type":"REQUESTED_BY","confidence":0.9},
                   {"source":"po-7001","target":"erin-wu","type":"APPROVED_BY","confidence":0.9},
                   {"source":"po-7001","target":"initech-finance","type":"PAID_BY","confidence":0.85}]}
                """);

        UnifiedCrawlJob job = service.startJob(UnifiedCrawlRequest.builder()
                .name("real-persistence slice")
                .sources(List.of(UnifiedCrawlSource.builder()
                        .label("docs")
                        .sourceType(DocumentSourceDescriptor.SourceType.FILE)
                        .pathOrUrl("/data/procurement")
                        .build()))
                .factSheetId(FACT_SHEET_ID)
                .graphExtraction(GraphExtractionConfig.builder().build())
                .vectorIndex(VectorIndexConfig.builder().enabled(false).build())
                .build());

        awaitCompletion(job);
        assertEquals(UnifiedCrawlJob.Status.COMPLETED, job.getStatus().get());
        assertEquals(4, job.getEntitiesExtracted().get());
        assertEquals(3, job.getRelationshipsExtracted().get());

        // The store itself — not a mock — now holds the graph.
        assertFalse(store.listGraphs().isEmpty(), "the crawl created at least one matrix graph");

        List<GraphNode> nodes = knowledgeGraphService.getNodesInFactSheet(FACT_SHEET_ID);
        Set<String> titles = nodes.stream().map(GraphNode::getTitle).collect(Collectors.toSet());
        assertTrue(titles.containsAll(Set.of("PO-7001", "Dana Reyes", "Erin Wu", "Initech Finance")),
                "all extracted entities persisted as real nodes; got: " + titles);
        assertTrue(nodes.size() >= 5,
                "entity nodes plus the document node are persisted; got " + nodes.size());

        List<GraphEdge> edges = knowledgeGraphService.getEdgesInFactSheet(FACT_SHEET_ID);
        assertFalse(edges.isEmpty(), "extracted relations persisted as real edges");
        Set<String> relationTypes = edges.stream()
                .map(GraphEdge::getRelationType)
                .filter(type -> type != null && !type.isBlank())
                .collect(Collectors.toSet());
        assertTrue(relationTypes.containsAll(Set.of("REQUESTED_BY", "APPROVED_BY", "PAID_BY")),
                "semantic relation types survive persistence; got: " + relationTypes);
    }

    private static void awaitCompletion(UnifiedCrawlJob job) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            UnifiedCrawlJob.Status status = job.getStatus().get();
            if (status == UnifiedCrawlJob.Status.COMPLETED
                    || status == UnifiedCrawlJob.Status.COMPLETED_PENDING_GRAPH
                    || status == UnifiedCrawlJob.Status.COMPLETED_PENDING_EMBEDDING
                    || status == UnifiedCrawlJob.Status.FAILED
                    || status == UnifiedCrawlJob.Status.CANCELLED) {
                return;
            }
            Thread.sleep(50);
        }
        fail("Job did not complete within 10 seconds. Status: " + job.getStatus().get());
    }
}
