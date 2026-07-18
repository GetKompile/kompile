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
import ai.kompile.core.crawl.graph.*;
import ai.kompile.core.crawl.graph.archive.CrawlStepArchiveService;
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.loaders.DocumentSourceDescriptor.SourceType;
import ai.kompile.core.llm.chat.LLMChat;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.crawler.CrawlerService;
import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * E2E tests for {@link UnifiedCrawlGraphServiceImpl} focusing on the synchronous
 * job management API: startJob validation, getJob, getAllJobs, getActiveJobs,
 * cancelJob, cleanupJobs, getAvailableSourceTypes, progress tracking, and
 * the job state machine.
 *
 * <p>Runs as the standard crawl-graph slice (the impl's collaborators — source loading,
 * step tracking, extraction orchestration — are real; leaf infra is mocked). Loader/crawler
 * availability toggles are applied to {@link CrawlSourceLoadingService}, which owns them.
 */
@SpringBootTest(classes = {
        UnifiedCrawlServiceJobManagementTest.TestConfig.class,
        UnifiedCrawlServiceJobManagementTest.Mocks.class})
class UnifiedCrawlServiceJobManagementTest {

    /** Minimal context: scan the crawl-graph package; exclude sibling test configs. */
    @SpringBootConfiguration
    @ComponentScan(
            basePackageClasses = UnifiedCrawlGraphServiceImpl.class,
            excludeFilters = @ComponentScan.Filter(
                    type = FilterType.ANNOTATION,
                    classes = {SpringBootConfiguration.class, TestConfiguration.class}))
    static class TestConfig {}

    /** Named leaf mocks for duplicate-type beans. */
    @TestConfiguration
    static class Mocks {
        @Bean DocumentLoader fileLoader() { return mock(DocumentLoader.class); }
        @Bean DocumentLoader emailLoader() { return mock(DocumentLoader.class); }
        @Bean TextChunker tableAwareChunker() { return mock(TextChunker.class); }
        @Bean TextChunker htmlChunker() { return mock(TextChunker.class); }
    }

    @Autowired private UnifiedCrawlGraphServiceImpl service;
    @Autowired private CrawlSourceLoadingService sourceLoadingService;
    @Autowired private GraphExtractionOrchestrator orchestrator;
    @SpyBean private CrawlRuntimeConfigManager runtimeConfigManager;
    @MockBean private CrawlerService crawlerService;
    @MockBean private VectorStore vectorStore;
    @MockBean private EmbeddingModel embeddingModel;
    @MockBean private LLMChat llmChat;
    @MockBean private KnowledgeGraphService knowledgeGraphService;
    @MockBean private CrossDocumentRelationCallback crossDocumentRelationCallback;
    @MockBean private CrawlStepArchiveService crawlStepArchiveService;
    @MockBean private GraphExtractionCheckpointStore graphExtractionCheckpointStore;
    @Autowired private DocumentLoader fileLoader;
    @Autowired private DocumentLoader emailLoader;
    @Autowired private TextChunker tableAwareChunker;
    @Autowired private TextChunker htmlChunker;

    /** The config the spied manager hands to every startJob; tests tune it in place. */
    private CrawlRuntimeConfigManager.CrawlRuntimeConfig cfg;

    @BeforeEach
    void setUp() throws Exception {
        drainJobs();

        if (orchestrator != null) orchestrator.graphConstructor = null;

        cfg = CrawlRuntimeConfigManager.CrawlRuntimeConfig.defaults();
        cfg.graphExtractionBatchSize = 10;
        cfg.graphExtractionParallelism = 1;
        cfg.backgroundGraphThreads = 1;
        cfg.graphExtractionChunksPerPrompt = 1;
        doReturn(cfg).when(runtimeConfigManager).refreshRuntimeConfig();

        reset(crawlerService, vectorStore, embeddingModel, llmChat, knowledgeGraphService,
                fileLoader, emailLoader, tableAwareChunker, htmlChunker,
                crossDocumentRelationCallback, graphExtractionCheckpointStore);
        when(graphExtractionCheckpointStore.completedChunkKeys(any(), any())).thenReturn(Set.of());

        // LLM chain: minimal valid extraction so document-bearing jobs can complete.
        LLMChat.ChatClientRequestSpec requestSpec = mock(LLMChat.ChatClientRequestSpec.class);
        LLMChat.CallResponseSpec callResponseSpec = mock(LLMChat.CallResponseSpec.class);
        when(llmChat.prompt(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(
                "{\"$schema\":\"kompile-graph-extraction/v1\",\"entities\":[{\"id\":\"e1\","
                        + "\"name\":\"Default Entity\",\"type\":\"CONCEPT\",\"confidence\":0.9}],"
                        + "\"relations\":[]}");

        when(fileLoader.supports(any(DocumentSourceDescriptor.class)))
                .thenAnswer(inv -> {
                    DocumentSourceDescriptor d = inv.getArgument(0);
                    return d != null && (d.getType() == SourceType.FILE || d.getType() == SourceType.DIRECTORY);
                });
        when(fileLoader.getName()).thenReturn("File Loader");

        when(emailLoader.supports(any(DocumentSourceDescriptor.class)))
                .thenAnswer(inv -> {
                    DocumentSourceDescriptor d = inv.getArgument(0);
                    return d != null && d.getType() == SourceType.EMAIL;
                });
        when(emailLoader.getName()).thenReturn("Email Loader");

        when(crawlerService.hasCrawlerForSourceType(any(DocumentSourceDescriptor.SourceType.class)))
                .thenAnswer(inv -> inv.getArgument(0) == SourceType.WEB_CRAWL);

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
        when(embeddingModel.embedBatch(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> new float[]{0.1f, 0.2f, 0.3f}).toList();
        });
        when(vectorStore.addWithFloatArrayEmbeddings(anyList(), any(float[][].class)))
                .thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());

        doReturn(GraphNode.builder().nodeId("doc-1").nodeType(NodeLevel.DOCUMENT).build())
                .when(knowledgeGraphService).addDocument(anyString(), anyString(), anyString(),
                        anyString(), anyString(), anyString(), any(), any());
        doReturn(Optional.empty())
                .when(knowledgeGraphService).getNodeByExternalId(anyString(), any(NodeLevel.class));
        doReturn(Optional.empty())
                .when(knowledgeGraphService).getNodeByExternalId(anyString(), any(NodeLevel.class), any());
        doReturn(false)
                .when(knowledgeGraphService).edgeExists(anyString(), anyString());
        doReturn(GraphNode.builder().nodeId("entity-1").nodeType(NodeLevel.ENTITY).build())
                .when(knowledgeGraphService).createNode(any(NodeLevel.class), anyString(), anyString(),
                        anyString(), anyMap());
        doReturn(GraphNode.builder().nodeId("entity-1").nodeType(NodeLevel.ENTITY).build())
                .when(knowledgeGraphService).createNode(any(NodeLevel.class), anyString(), anyString(),
                        anyString(), anyMap(), any());
        doReturn(GraphEdge.builder().edgeId("edge-1").build())
                .when(knowledgeGraphService).createEdge(anyString(), anyString(), any(EdgeType.class),
                        anyDouble(), anyString());
        doReturn(GraphEdge.builder().edgeId("edge-1").build())
                .when(knowledgeGraphService).createEdgeWithMetadata(anyString(), anyString(), any(EdgeType.class),
                        anyDouble(), anyString(), any(), any(), any(EdgeProvenance.class), any());
        doReturn(GraphNode.builder().nodeId("table-1").nodeType(NodeLevel.TABLE).build())
                .when(knowledgeGraphService).createTableNode(anyString(), anyString(), anyString(),
                        anyInt(), anyInt(), any(), any(), any());
        doCallRealMethod().when(knowledgeGraphService).createNodesBatch(anyList(), any());
        doCallRealMethod().when(knowledgeGraphService).createSnippetNodesBatch(anyList());

        when(crossDocumentRelationCallback.extractRelationsFromGraphNodes(any())).thenReturn(0);

        // Restore the default source-loading wiring (mutation tests null these out).
        setField(sourceLoadingService, "crawlerService", crawlerService);
        setField(sourceLoadingService, "documentLoaders", List.of(fileLoader, emailLoader));
    }

    /** Cancel and remove every job left over from the previous test (shared slice context). */
    private void drainJobs() throws InterruptedException {
        for (UnifiedCrawlJob job : service.getActiveJobs()) {
            service.cancelJob(job.getJobId());
        }
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline
                && service.getAllJobs().stream().anyMatch(j -> !isTerminal(j.getStatus().get()))) {
            Thread.sleep(25);
        }
        service.cleanupJobs();
    }

    private static boolean isTerminal(UnifiedCrawlJob.Status status) {
        return status == UnifiedCrawlJob.Status.COMPLETED
                || status == UnifiedCrawlJob.Status.COMPLETED_PENDING_GRAPH
                || status == UnifiedCrawlJob.Status.COMPLETED_PENDING_EMBEDDING
                || status == UnifiedCrawlJob.Status.FAILED
                || status == UnifiedCrawlJob.Status.CANCELLED;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private UnifiedCrawlSource fileSource(String label, String path) {
        return UnifiedCrawlSource.builder()
                .label(label)
                .sourceType(SourceType.FILE)
                .pathOrUrl(path)
                .build();
    }

    private UnifiedCrawlSource emailSource(String label) {
        return UnifiedCrawlSource.builder()
                .label(label)
                .sourceType(SourceType.EMAIL)
                .pathOrUrl("imap://mail.example.com")
                .build();
    }

    private UnifiedCrawlRequest simpleRequest(String name, UnifiedCrawlSource... sources) {
        return UnifiedCrawlRequest.builder()
                .name(name)
                .sources(List.of(sources))
                .graphExtraction(GraphExtractionConfig.builder().build())
                .vectorIndex(VectorIndexConfig.builder().enabled(false).build())
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // startJob validation
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class StartJobValidation {

        @Test
        void rejectsNullSources() {
            UnifiedCrawlRequest request = UnifiedCrawlRequest.builder()
                    .name("bad request")
                    .sources(null)
                    .build();

            assertThrows(IllegalArgumentException.class, () -> service.startJob(request));
        }

        @Test
        void rejectsEmptySources() {
            UnifiedCrawlRequest request = UnifiedCrawlRequest.builder()
                    .name("bad request")
                    .sources(List.of())
                    .build();

            assertThrows(IllegalArgumentException.class, () -> service.startJob(request));
        }

        @Test
        void validRequestReturnsJob() throws Exception {
            // Use a latch-based loader to control execution
            when(fileLoader.load(any(), any())).thenReturn(List.of());

            UnifiedCrawlJob job = service.startJob(simpleRequest("test", fileSource("f", "/data")));

            assertNotNull(job);
            assertNotNull(job.getJobId());
            assertNotNull(job.getCreatedAt());
            assertEquals("test", job.getRequest().getName());
        }

        @Test
        void jobStartsInPendingOrRunning() throws Exception {
            when(fileLoader.load(any(), any())).thenReturn(List.of());

            UnifiedCrawlJob job = service.startJob(simpleRequest("test", fileSource("f", "/data")));

            // It starts as PENDING and quickly transitions to RUNNING
            UnifiedCrawlJob.Status status = job.getStatus().get();
            assertTrue(status == UnifiedCrawlJob.Status.PENDING
                    || status == UnifiedCrawlJob.Status.RUNNING
                    || status == UnifiedCrawlJob.Status.COMPLETED,
                    "Job should start as PENDING or have transitioned to RUNNING/COMPLETED");
        }

        @Test
        void sourceProgressInitialized() throws Exception {
            when(fileLoader.load(any(), any())).thenReturn(List.of());

            UnifiedCrawlJob job = service.startJob(simpleRequest("test",
                    fileSource("docs", "/data/docs"),
                    emailSource("emails")));

            assertEquals(2, job.getSourceProgress().size());
            assertEquals("docs", job.getSourceProgress().get(0).getLabel());
            assertEquals("FILE", job.getSourceProgress().get(0).getSourceType());
            assertEquals("/data/docs", job.getSourceProgress().get(0).getPathOrUrl());
            assertEquals("emails", job.getSourceProgress().get(1).getLabel());
            assertEquals("EMAIL", job.getSourceProgress().get(1).getSourceType());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // getJob / getAllJobs / getActiveJobs
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class JobRetrieval {

        @Test
        void getJobReturnsCreatedJob() throws Exception {
            when(fileLoader.load(any(), any())).thenReturn(List.of());

            UnifiedCrawlJob created = service.startJob(simpleRequest("test", fileSource("f", "/data")));

            Optional<UnifiedCrawlJob> retrieved = service.getJob(created.getJobId());
            assertTrue(retrieved.isPresent());
            assertEquals(created.getJobId(), retrieved.get().getJobId());
        }

        @Test
        void getJobReturnsEmptyForUnknownId() {
            Optional<UnifiedCrawlJob> result = service.getJob("nonexistent-id");
            assertFalse(result.isPresent());
        }

        @Test
        void getAllJobsReturnsAllCreated() throws Exception {
            when(fileLoader.load(any(), any())).thenReturn(List.of());

            service.startJob(simpleRequest("job1", fileSource("f1", "/a")));
            service.startJob(simpleRequest("job2", fileSource("f2", "/b")));

            List<UnifiedCrawlJob> all = service.getAllJobs();
            assertEquals(2, all.size());
        }

        @Test
        void getActiveJobsFiltersCompleted() throws Exception {
            CountDownLatch startedLatch = new CountDownLatch(1);
            CountDownLatch blockLatch = new CountDownLatch(1);

            // One job that blocks in the loader (stays RUNNING)
            when(fileLoader.load(any(), any())).thenAnswer(inv -> {
                startedLatch.countDown();
                blockLatch.await(10, TimeUnit.SECONDS);
                return List.of();
            });

            UnifiedCrawlJob runningJob = service.startJob(simpleRequest("blocking", fileSource("f", "/data")));
            assertTrue(startedLatch.await(3, TimeUnit.SECONDS), "Job should start loading");

            List<UnifiedCrawlJob> active = service.getActiveJobs();
            assertTrue(active.stream().anyMatch(j -> j.getJobId().equals(runningJob.getJobId())),
                    "Running job should appear in active list");

            blockLatch.countDown(); // unblock
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // cancelJob
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class CancelJobTests {

        @Test
        void cancelRunningJobSucceeds() throws Exception {
            CountDownLatch startedLatch = new CountDownLatch(1);
            CountDownLatch blockLatch = new CountDownLatch(1);
            when(fileLoader.load(any(), any())).thenAnswer(inv -> {
                startedLatch.countDown(); // signal that we're inside load
                blockLatch.await(5, TimeUnit.SECONDS);
                return List.of();
            });

            UnifiedCrawlJob job = service.startJob(simpleRequest("test", fileSource("f", "/data")));
            // Wait until the job is actually inside the loader (guaranteed RUNNING)
            assertTrue(startedLatch.await(3, TimeUnit.SECONDS), "Job should start loading");

            boolean cancelled = service.cancelJob(job.getJobId());
            assertTrue(cancelled);
            assertEquals(UnifiedCrawlJob.Status.CANCELLING, job.getStatus().get());
            assertNull(job.getCompletedAt(), "Cancellation is not complete while the loader owns the worker");
            assertTrue(service.getActiveJobs().stream()
                    .anyMatch(active -> active.getJobId().equals(job.getJobId())));
            assertEquals(0, service.cleanupJobs());

            blockLatch.countDown();
            awaitJobStatus(job, UnifiedCrawlJob.Status.CANCELLED, 5);
            assertNotNull(job.getCompletedAt());
        }

        @Test
        void cancelNonexistentJobReturnsFalse() {
            assertFalse(service.cancelJob("nonexistent-id"));
        }

        @Test
        void cancelCompletedJobReturnsFalse() throws Exception {
            when(fileLoader.load(any(), any())).thenReturn(List.of());

            UnifiedCrawlJob job = service.startJob(simpleRequest("test", fileSource("f", "/data")));
            // Wait for completion
            awaitJobStatus(job, UnifiedCrawlJob.Status.COMPLETED, 5);

            assertFalse(service.cancelJob(job.getJobId()));
        }

        @Test
        void cancelAlreadyCancelledJobReturnsFalse() throws Exception {
            CountDownLatch startedLatch = new CountDownLatch(1);
            CountDownLatch blockLatch = new CountDownLatch(1);
            when(fileLoader.load(any(), any())).thenAnswer(inv -> {
                startedLatch.countDown();
                blockLatch.await(5, TimeUnit.SECONDS);
                return List.of();
            });

            UnifiedCrawlJob job = service.startJob(simpleRequest("test", fileSource("f", "/data")));
            assertTrue(startedLatch.await(3, TimeUnit.SECONDS));
            assertTrue(service.cancelJob(job.getJobId()), "First cancel should succeed");

            assertFalse(service.cancelJob(job.getJobId()), "Second cancel should return false");

            blockLatch.countDown();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // cleanupJobs
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class CleanupJobTests {

        @Test
        void cleanupRemovesCompletedJobs() throws Exception {
            when(fileLoader.load(any(), any())).thenReturn(List.of());

            UnifiedCrawlJob job = service.startJob(simpleRequest("done", fileSource("f", "/data")));
            awaitJobStatus(job, UnifiedCrawlJob.Status.COMPLETED, 5);

            int removed = service.cleanupJobs();
            assertEquals(1, removed);
            assertTrue(service.getAllJobs().isEmpty());
        }

        @Test
        void cleanupRemovesCancelledJobs() throws Exception {
            CountDownLatch startedLatch = new CountDownLatch(1);
            CountDownLatch blockLatch = new CountDownLatch(1);
            when(fileLoader.load(any(), any())).thenAnswer(inv -> {
                startedLatch.countDown();
                blockLatch.await(5, TimeUnit.SECONDS);
                return List.of();
            });

            UnifiedCrawlJob job = service.startJob(simpleRequest("cancel-me", fileSource("f", "/data")));
            assertTrue(startedLatch.await(3, TimeUnit.SECONDS));
            assertTrue(service.cancelJob(job.getJobId()));

            assertEquals(0, service.cleanupJobs(), "A cancelling worker must remain tracked");
            assertTrue(service.getJob(job.getJobId()).isPresent());

            blockLatch.countDown();
            awaitJobStatus(job, UnifiedCrawlJob.Status.CANCELLED, 5);
            assertEquals(1, service.cleanupJobs());
            assertTrue(service.getAllJobs().isEmpty());
        }

        @Test
        void cleanupRemovesFailedJobs() throws Exception {
            when(fileLoader.load(any(), any())).thenThrow(new RuntimeException("load failure"));

            UnifiedCrawlJob job = service.startJob(simpleRequest("fail", fileSource("f", "/data")));
            awaitJobTerminal(job, 5);

            int removed = service.cleanupJobs();
            assertTrue(removed >= 1);
        }

        @Test
        void cleanupLeavesActiveJobsIntact() throws Exception {
            CountDownLatch startedLatch = new CountDownLatch(1);
            CountDownLatch blockLatch = new CountDownLatch(1);
            when(fileLoader.load(any(), any())).thenAnswer(inv -> {
                startedLatch.countDown();
                blockLatch.await(10, TimeUnit.SECONDS);
                return List.of();
            });

            service.startJob(simpleRequest("running", fileSource("f", "/data")));
            assertTrue(startedLatch.await(3, TimeUnit.SECONDS), "Job should start loading");

            int removed = service.cleanupJobs();
            assertEquals(0, removed);
            assertEquals(1, service.getAllJobs().size());

            blockLatch.countDown();
        }

        @Test
        void cleanupOnEmptyReturnsZero() {
            assertEquals(0, service.cleanupJobs());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // getAvailableSourceTypes
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class AvailableSourceTypeTests {

        @Test
        void alwaysIncludesDirectoryFileUrl() {
            List<UnifiedCrawlService.AvailableSourceType> types = service.getAvailableSourceTypes();

            assertTrue(types.stream().anyMatch(t -> "DIRECTORY".equals(t.type()) && t.available()));
            assertTrue(types.stream().anyMatch(t -> "FILE".equals(t.type()) && t.available()));
            assertTrue(types.stream().anyMatch(t -> "URL".equals(t.type()) && t.available()));
        }

        @Test
        void webCrawlAvailableWhenCrawlerServicePresent() {
            List<UnifiedCrawlService.AvailableSourceType> types = service.getAvailableSourceTypes();

            assertTrue(types.stream().anyMatch(t -> "WEB_CRAWL".equals(t.type()) && t.available()),
                    "WEB_CRAWL should be available when crawlerService is injected");
        }

        @Test
        void webCrawlStaysAvailableWithoutCrawlerService() throws Exception {
            // WEB_CRAWL is a built-in type: without a crawler the load path falls back to
            // document loaders, so availability no longer depends on the crawler bean.
            setField(sourceLoadingService, "crawlerService", null);

            List<UnifiedCrawlService.AvailableSourceType> types = service.getAvailableSourceTypes();

            assertTrue(types.stream().anyMatch(t -> "WEB_CRAWL".equals(t.type()) && t.available()),
                    "WEB_CRAWL is built-in and stays available without a crawler");
        }

        @Test
        void emailAvailableWhenEmailLoaderPresent() {
            List<UnifiedCrawlService.AvailableSourceType> types = service.getAvailableSourceTypes();

            assertTrue(types.stream().anyMatch(t -> "EMAIL".equals(t.type()) && t.available()));
        }

        @Test
        void emailUnavailableWhenNoEmailLoader() throws Exception {
            setField(sourceLoadingService, "documentLoaders", List.of(fileLoader));

            List<UnifiedCrawlService.AvailableSourceType> types = service.getAvailableSourceTypes();

            assertTrue(types.stream().anyMatch(t -> "EMAIL".equals(t.type()) && !t.available()));
        }

        @Test
        void sourceTypeHasRequiredProperties() {
            List<UnifiedCrawlService.AvailableSourceType> types = service.getAvailableSourceTypes();

            UnifiedCrawlService.AvailableSourceType email = types.stream()
                    .filter(t -> "EMAIL".equals(t.type())).findFirst().orElseThrow();

            assertTrue(email.requiredProperties().contains("pathOrUrl"));
            assertFalse(email.optionalProperties().isEmpty());
        }

        @Test
        void allSourceTypesHaveDisplayNameAndDescription() {
            List<UnifiedCrawlService.AvailableSourceType> types = service.getAvailableSourceTypes();

            for (UnifiedCrawlService.AvailableSourceType type : types) {
                assertNotNull(type.displayName(), "Missing displayName for " + type.type());
                assertNotNull(type.description(), "Missing description for " + type.type());
            }
        }

        @Test
        void catalogEntriesAreDistinctValidSourceTypes() {
            // The catalog is a curated UI list, not enum parity — internal source types
            // are deliberately absent. Every entry must be a real, unique SourceType.
            List<UnifiedCrawlService.AvailableSourceType> types = service.getAvailableSourceTypes();

            assertEquals(types.size(), types.stream().map(UnifiedCrawlService.AvailableSourceType::type)
                    .distinct().count(), "no duplicate catalog entries");
            for (UnifiedCrawlService.AvailableSourceType type : types) {
                assertDoesNotThrow(() -> DocumentSourceDescriptor.SourceType.valueOf(type.type()),
                        "catalog entry is not a real SourceType: " + type.type());
            }
            assertTrue(types.size() >= 20, "the curated catalog covers the supported sources");
        }

        @Test
        void noLoadersReturnsListWithBaseTypes() throws Exception {
            setField(sourceLoadingService, "documentLoaders", null);
            setField(sourceLoadingService, "crawlerService", null);

            List<UnifiedCrawlService.AvailableSourceType> types = service.getAvailableSourceTypes();

            // DIRECTORY, FILE, URL are always available (built-in)
            assertTrue(types.stream().anyMatch(t -> "DIRECTORY".equals(t.type()) && t.available()));
            // EMAIL, SLACK, etc. should be unavailable
            assertTrue(types.stream().anyMatch(t -> "EMAIL".equals(t.type()) && !t.available()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Progress snapshot
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class ProgressSnapshotTests {

        @Test
        void snapshotCapturesJobState() throws Exception {
            when(fileLoader.load(any(), any())).thenReturn(List.of(
                    new Document("text", Map.of())));

            UnifiedCrawlJob job = service.startJob(simpleRequest("snap", fileSource("f", "/data")));
            awaitJobTerminal(job, 10);

            UnifiedCrawlJob.ProgressSnapshot snap = job.toProgressSnapshot();
            assertEquals(job.getJobId(), snap.getJobId());
            assertEquals("snap", snap.getName());
            assertNotNull(snap.getCreatedAt());
            assertTrue(snap.getSourceProgress().size() >= 1);
        }

        @Test
        void snapshotReflectsCounters() throws Exception {
            when(fileLoader.load(any(), any())).thenReturn(List.of(
                    new Document("doc1", Map.of()),
                    new Document("doc2", Map.of())));

            UnifiedCrawlJob job = service.startJob(simpleRequest("cnt", fileSource("f", "/data")));
            awaitJobTerminal(job, 10);

            UnifiedCrawlJob.ProgressSnapshot snap = job.toProgressSnapshot();
            assertTrue(snap.getDocumentsLoaded() >= 2,
                    "Should reflect at least 2 documents loaded");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Multi-job isolation
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class MultiJobTests {

        @Test
        void jobsHaveUniqueIds() throws Exception {
            when(fileLoader.load(any(), any())).thenReturn(List.of());

            UnifiedCrawlJob job1 = service.startJob(simpleRequest("a", fileSource("f1", "/a")));
            UnifiedCrawlJob job2 = service.startJob(simpleRequest("b", fileSource("f2", "/b")));

            assertNotEquals(job1.getJobId(), job2.getJobId());
        }

        @Test
        void cancelOneJobDoesNotAffectOther() throws Exception {
            cfg.maxConcurrentJobs = 2;
            CountDownLatch started1 = new CountDownLatch(1);
            CountDownLatch started2 = new CountDownLatch(1);
            CountDownLatch blockLatch = new CountDownLatch(1);

            java.util.concurrent.atomic.AtomicInteger loadCount = new java.util.concurrent.atomic.AtomicInteger(0);
            when(fileLoader.load(any(), any())).thenAnswer(inv -> {
                int n = loadCount.incrementAndGet();
                if (n == 1) started1.countDown();
                else started2.countDown();
                blockLatch.await(10, TimeUnit.SECONDS);
                return List.of();
            });

            UnifiedCrawlJob job1 = service.startJob(simpleRequest("a", fileSource("f1", "/a")));
            UnifiedCrawlJob job2 = service.startJob(simpleRequest("b", fileSource("f2", "/b")));
            assertTrue(started1.await(3, TimeUnit.SECONDS), "Job1 should start loading");
            assertTrue(started2.await(3, TimeUnit.SECONDS), "Job2 should start loading");

            assertTrue(service.cancelJob(job1.getJobId()));

            assertEquals(UnifiedCrawlJob.Status.CANCELLING, job1.getStatus().get());
            assertNotEquals(UnifiedCrawlJob.Status.CANCELLING, job2.getStatus().get());

            blockLatch.countDown();
            awaitJobStatus(job1, UnifiedCrawlJob.Status.CANCELLED, 5);
            assertNotEquals(UnifiedCrawlJob.Status.CANCELLED, job2.getStatus().get());
        }

        @Test
        void cleanupOnlyRemovesTerminalJobs() throws Exception {
            cfg.maxConcurrentJobs = 2;
            CountDownLatch runningStarted = new CountDownLatch(1);
            CountDownLatch cancelledStarted = new CountDownLatch(1);
            CountDownLatch blockLatch = new CountDownLatch(1);

            // Track invocation count to distinguish the two jobs
            java.util.concurrent.atomic.AtomicInteger loadCount = new java.util.concurrent.atomic.AtomicInteger(0);
            when(fileLoader.load(any(), any())).thenAnswer(inv -> {
                int n = loadCount.incrementAndGet();
                if (n == 1) runningStarted.countDown();
                else cancelledStarted.countDown();
                blockLatch.await(10, TimeUnit.SECONDS);
                return List.of();
            });

            UnifiedCrawlJob running = service.startJob(simpleRequest("running", fileSource("f1", "/a")));
            assertTrue(runningStarted.await(3, TimeUnit.SECONDS), "First job should start loading");

            // Create and wait for the second job to start, then cancel it
            UnifiedCrawlJob cancelled = service.startJob(simpleRequest("cancelled", fileSource("f2", "/b")));
            assertTrue(cancelledStarted.await(3, TimeUnit.SECONDS), "Second job should start loading");
            assertTrue(service.cancelJob(cancelled.getJobId()));

            assertEquals(UnifiedCrawlJob.Status.CANCELLING, cancelled.getStatus().get());
            assertEquals(0, service.cleanupJobs());
            assertEquals(2, service.getAllJobs().size());

            blockLatch.countDown();
            awaitJobStatus(cancelled, UnifiedCrawlJob.Status.CANCELLED, 5);

            int removed = service.cleanupJobs();
            assertTrue(removed >= 1);
            assertTrue(service.getJob(cancelled.getJobId()).isEmpty());
        }
    }

    // ── Utilities ──────────────────────────────────────────────────────────

    private void awaitJobStatus(UnifiedCrawlJob job, UnifiedCrawlJob.Status expected, int timeoutSecs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSecs * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (job.getStatus().get() == expected) return;
            Thread.sleep(50);
        }
        assertEquals(expected, job.getStatus().get(), "Job did not reach " + expected + " within timeout");
    }

    private void awaitJobTerminal(UnifiedCrawlJob job, int timeoutSecs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSecs * 1000L;
        while (System.currentTimeMillis() < deadline) {
            UnifiedCrawlJob.Status s = job.getStatus().get();
            if (isTerminal(s)) return;
            Thread.sleep(50);
        }
        assertTrue(isTerminal(job.getStatus().get()),
                "Job did not reach terminal state within timeout, was: " + job.getStatus().get());
    }
}
