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

package ai.kompile.tool.crawler;

import ai.kompile.core.crawl.graph.*;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link UnifiedCrawlGraphTool} — verifies input conversion,
 * tool method outputs, and error handling at the MCP tool layer.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UnifiedCrawlGraphToolTest {

    @Mock
    private UnifiedCrawlService unifiedCrawlService;

    private UnifiedCrawlGraphTool tool;

    @BeforeEach
    void setUp() {
        tool = new UnifiedCrawlGraphTool(unifiedCrawlService);
    }

    // ──────────────────────────────────────────────────────────────────
    // 1. START UNIFIED CRAWL
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Start crawl with single directory source returns jobId")
    void startCrawl_singleDirectorySource() {
        UnifiedCrawlJob mockJob = buildMockJob("job-1", UnifiedCrawlJob.Status.PENDING);
        when(unifiedCrawlService.startJob(any(UnifiedCrawlRequest.class))).thenReturn(mockJob);

        var input = new UnifiedCrawlGraphTool.StartUnifiedCrawlInput(
                "test crawl",
                List.of(new UnifiedCrawlGraphTool.SourceInput(
                        "docs", "DIRECTORY", "/data/docs",
                        3, 100, null, null, null
                )),
                null, null
        );

        Map<String, Object> result = tool.startUnifiedCrawl(input);

        assertEquals("job-1", result.get("jobId"));
        assertEquals("PENDING", result.get("status"));
        assertEquals(1, result.get("sourceCount"));
        assertNull(result.get("error"));

        // Verify source type conversion
        ArgumentCaptor<UnifiedCrawlRequest> captor = ArgumentCaptor.forClass(UnifiedCrawlRequest.class);
        verify(unifiedCrawlService).startJob(captor.capture());
        UnifiedCrawlRequest captured = captor.getValue();
        assertEquals("test crawl", captured.getName());
        assertEquals(1, captured.getSources().size());
        assertEquals(DocumentSourceDescriptor.SourceType.DIRECTORY, captured.getSources().get(0).getSourceType());
        assertEquals("/data/docs", captured.getSources().get(0).getPathOrUrl());
        assertEquals(3, captured.getSources().get(0).getMaxDepth());
        assertEquals(100, captured.getSources().get(0).getMaxDocuments());
    }

    @Test
    @DisplayName("Start crawl with multiple sources of different types")
    void startCrawl_multipleSources() {
        UnifiedCrawlJob mockJob = buildMockJob("job-2", UnifiedCrawlJob.Status.PENDING);
        when(unifiedCrawlService.startJob(any())).thenReturn(mockJob);

        var input = new UnifiedCrawlGraphTool.StartUnifiedCrawlInput(
                "multi-source",
                List.of(
                        new UnifiedCrawlGraphTool.SourceInput("files", "FILE", "/data/report.pdf",
                                null, null, null, null, null),
                        new UnifiedCrawlGraphTool.SourceInput("email", "EMAIL", "imap://mail.example.com",
                                null, null, null, null, Map.of("folder", "INBOX")),
                        new UnifiedCrawlGraphTool.SourceInput("web", "WEB_CRAWL", "https://docs.example.com",
                                2, 50, List.of("*.html"), List.of("*.pdf"), null)
                ),
                null, null
        );

        Map<String, Object> result = tool.startUnifiedCrawl(input);

        assertEquals("job-2", result.get("jobId"));
        assertEquals(3, result.get("sourceCount"));

        ArgumentCaptor<UnifiedCrawlRequest> captor = ArgumentCaptor.forClass(UnifiedCrawlRequest.class);
        verify(unifiedCrawlService).startJob(captor.capture());
        List<UnifiedCrawlSource> sources = captor.getValue().getSources();
        assertEquals(DocumentSourceDescriptor.SourceType.FILE, sources.get(0).getSourceType());
        assertEquals(DocumentSourceDescriptor.SourceType.EMAIL, sources.get(1).getSourceType());
        assertEquals(DocumentSourceDescriptor.SourceType.WEB_CRAWL, sources.get(2).getSourceType());
        assertEquals(Map.of("folder", "INBOX"), sources.get(1).getProperties());
        assertEquals(List.of("*.html"), sources.get(2).getIncludePatterns());
        assertEquals(List.of("*.pdf"), sources.get(2).getExcludePatterns());
    }

    @Test
    @DisplayName("Start crawl with graph extraction config passes entity/relationship types")
    void startCrawl_withGraphConfig() {
        UnifiedCrawlJob mockJob = buildMockJob("job-3", UnifiedCrawlJob.Status.PENDING);
        when(unifiedCrawlService.startJob(any())).thenReturn(mockJob);

        var input = new UnifiedCrawlGraphTool.StartUnifiedCrawlInput(
                "graph test",
                List.of(new UnifiedCrawlGraphTool.SourceInput("docs", "DIRECTORY", "/data", null, null, null, null, null)),
                new UnifiedCrawlGraphTool.GraphConfigInput(
                        true,
                        List.of("PERSON", "ORGANIZATION", "TECHNOLOGY"),
                        List.of("WORKS_AT", "USES", "DEPENDS_ON"),
                        "openai", "gpt-4", 0.1,
                        "LENIENT", true, 0.86, false, 0.9,
                        0.7, "Focus on technical entities"
                ),
                null
        );

        tool.startUnifiedCrawl(input);

        ArgumentCaptor<UnifiedCrawlRequest> captor = ArgumentCaptor.forClass(UnifiedCrawlRequest.class);
        verify(unifiedCrawlService).startJob(captor.capture());
        GraphExtractionConfig gc = captor.getValue().getGraphExtraction();
        assertNotNull(gc);
        assertTrue(gc.isEnabled());
        assertEquals(List.of("PERSON", "ORGANIZATION", "TECHNOLOGY"), gc.getEntityTypes());
        assertEquals(List.of("WORKS_AT", "USES", "DEPENDS_ON"), gc.getRelationshipTypes());
        assertEquals("openai", gc.getLlmProvider());
        assertEquals("gpt-4", gc.getModelName());
        assertEquals(0.1, gc.getTemperature());
        assertTrue(gc.isEntityResolution());
        assertEquals(0.86, gc.getEntityResolutionSimilarityThreshold());
        assertFalse(gc.isEntityResolutionUseEmbeddings());
        assertEquals(0.9, gc.getEntityResolutionEmbeddingThreshold());
        assertEquals(0.7, gc.getMinConfidence());
        assertEquals("Focus on technical entities", gc.getCustomPrompt());
    }

    @Test
    @DisplayName("Start crawl with vector index config passes collection settings")
    void startCrawl_withVectorConfig() {
        UnifiedCrawlJob mockJob = buildMockJob("job-4", UnifiedCrawlJob.Status.PENDING);
        when(unifiedCrawlService.startJob(any())).thenReturn(mockJob);

        var input = new UnifiedCrawlGraphTool.StartUnifiedCrawlInput(
                "index test",
                List.of(new UnifiedCrawlGraphTool.SourceInput("docs", "FILE", "/data/doc.txt", null, null, null, null, null)),
                null,
                new UnifiedCrawlGraphTool.IndexConfigInput(true, "my-collection", "recursive", 500, 50)
        );

        tool.startUnifiedCrawl(input);

        ArgumentCaptor<UnifiedCrawlRequest> captor = ArgumentCaptor.forClass(UnifiedCrawlRequest.class);
        verify(unifiedCrawlService).startJob(captor.capture());
        VectorIndexConfig vc = captor.getValue().getVectorIndex();
        assertNotNull(vc);
        assertTrue(vc.isEnabled());
        assertEquals("my-collection", vc.getCollectionName());
        assertEquals("recursive", vc.getChunkerName());
        assertEquals(500, vc.getChunkSize());
        assertEquals(50, vc.getChunkOverlap());
    }

    @Test
    @DisplayName("Start crawl with empty sources returns error map")
    void startCrawl_emptySourcesReturnsError() {
        var input = new UnifiedCrawlGraphTool.StartUnifiedCrawlInput("empty", List.of(), null, null);

        Map<String, Object> result = tool.startUnifiedCrawl(input);
        assertNotNull(result.get("error"));
        assertTrue(result.get("error").toString().contains("At least one source"));
    }

    @Test
    @DisplayName("Start crawl with null sources returns error map")
    void startCrawl_nullSourcesReturnsError() {
        var input = new UnifiedCrawlGraphTool.StartUnifiedCrawlInput("null", null, null, null);

        Map<String, Object> result = tool.startUnifiedCrawl(input);
        assertNotNull(result.get("error"));
    }

    @Test
    @DisplayName("Start crawl with unknown source type logs warning but proceeds")
    void startCrawl_unknownSourceType() {
        UnifiedCrawlJob mockJob = buildMockJob("job-5", UnifiedCrawlJob.Status.PENDING);
        when(unifiedCrawlService.startJob(any())).thenReturn(mockJob);

        var input = new UnifiedCrawlGraphTool.StartUnifiedCrawlInput(
                "unknown type",
                List.of(new UnifiedCrawlGraphTool.SourceInput("custom", "NONEXISTENT_TYPE", "/data", null, null, null, null, null)),
                null, null
        );

        Map<String, Object> result = tool.startUnifiedCrawl(input);
        assertEquals("job-5", result.get("jobId"));

        ArgumentCaptor<UnifiedCrawlRequest> captor = ArgumentCaptor.forClass(UnifiedCrawlRequest.class);
        verify(unifiedCrawlService).startJob(captor.capture());
        // Source type should be null because valueOf failed
        assertNull(captor.getValue().getSources().get(0).getSourceType());
    }

    @Test
    @DisplayName("Start crawl with null name defaults to 'Unified crawl'")
    void startCrawl_nullNameDefaults() {
        UnifiedCrawlJob mockJob = buildMockJob("job-6", UnifiedCrawlJob.Status.PENDING);
        when(unifiedCrawlService.startJob(any())).thenReturn(mockJob);

        var input = new UnifiedCrawlGraphTool.StartUnifiedCrawlInput(
                null,
                List.of(new UnifiedCrawlGraphTool.SourceInput("docs", "FILE", "/data/f.txt", null, null, null, null, null)),
                null, null
        );

        tool.startUnifiedCrawl(input);

        ArgumentCaptor<UnifiedCrawlRequest> captor = ArgumentCaptor.forClass(UnifiedCrawlRequest.class);
        verify(unifiedCrawlService).startJob(captor.capture());
        assertEquals("Unified crawl", captor.getValue().getName());
    }

    @Test
    @DisplayName("Start crawl service exception returns error map")
    void startCrawl_serviceThrows() {
        when(unifiedCrawlService.startJob(any())).thenThrow(new RuntimeException("Service down"));

        var input = new UnifiedCrawlGraphTool.StartUnifiedCrawlInput(
                "failing",
                List.of(new UnifiedCrawlGraphTool.SourceInput("docs", "FILE", "/data", null, null, null, null, null)),
                null, null
        );

        Map<String, Object> result = tool.startUnifiedCrawl(input);
        assertNotNull(result.get("error"));
        assertTrue(result.get("error").toString().contains("Service down"));
    }

    // ──────────────────────────────────────────────────────────────────
    // 2. STATUS / GET JOB
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Get status of completed job with entities")
    void getStatus_completedJobWithEntities() {
        UnifiedCrawlJob job = buildMockJob("job-status-1", UnifiedCrawlJob.Status.COMPLETED);
        job.getDocumentsLoaded().set(5);
        job.getChunksProcessed().set(5);
        job.getEntitiesExtracted().set(10);
        job.getRelationshipsExtracted().set(7);
        job.getDocumentsIndexed().set(5);
        job.setRequest(UnifiedCrawlRequest.builder().name("status test").sources(List.of()).build());
        job.setSourceProgress(List.of(
                UnifiedCrawlJob.SourceProgress.builder()
                        .label("docs").sourceType("DIRECTORY").status(UnifiedCrawlJob.Status.COMPLETED)
                        .documentsLoaded(5).entitiesExtracted(10)
                        .build()
        ));

        when(unifiedCrawlService.getJob("job-status-1")).thenReturn(Optional.of(job));

        Map<String, Object> result = tool.getUnifiedCrawlStatus(new UnifiedCrawlGraphTool.JobIdInput("job-status-1"));

        assertEquals("job-status-1", result.get("jobId"));
        assertEquals("COMPLETED", result.get("status"));
        assertEquals(5, result.get("documentsLoaded"));
        assertEquals(10, result.get("entitiesExtracted"));
        assertEquals(7, result.get("relationshipsExtracted"));
        assertEquals(5, result.get("documentsIndexed"));
        assertNotNull(result.get("sources"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sources = (List<Map<String, Object>>) result.get("sources");
        assertEquals(1, sources.size());
        assertEquals("docs", sources.get(0).get("label"));
    }

    @Test
    @DisplayName("Get status of unknown job returns error")
    void getStatus_unknownJob() {
        when(unifiedCrawlService.getJob("nonexistent")).thenReturn(Optional.empty());

        Map<String, Object> result = tool.getUnifiedCrawlStatus(new UnifiedCrawlGraphTool.JobIdInput("nonexistent"));
        assertNotNull(result.get("error"));
        assertTrue(result.get("error").toString().contains("nonexistent"));
    }

    @Test
    @DisplayName("Get status of running job shows intermediate progress")
    void getStatus_runningJob() {
        UnifiedCrawlJob job = buildMockJob("job-running", UnifiedCrawlJob.Status.RUNNING);
        job.getDocumentsDiscovered().set(20);
        job.getDocumentsLoaded().set(8);
        job.getEntitiesExtracted().set(3);
        job.setRequest(UnifiedCrawlRequest.builder().name("running test").sources(List.of()).build());
        job.setSourceProgress(List.of());

        when(unifiedCrawlService.getJob("job-running")).thenReturn(Optional.of(job));

        Map<String, Object> result = tool.getUnifiedCrawlStatus(new UnifiedCrawlGraphTool.JobIdInput("job-running"));

        assertEquals("RUNNING", result.get("status"));
        assertEquals(20, result.get("documentsDiscovered"));
        assertEquals(8, result.get("documentsLoaded"));
        assertEquals(3, result.get("entitiesExtracted"));
    }

    // ──────────────────────────────────────────────────────────────────
    // 3. LIST JOBS
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("List jobs returns all jobs with summaries")
    void listJobs_returnsSummaries() {
        UnifiedCrawlJob job1 = buildMockJob("j1", UnifiedCrawlJob.Status.COMPLETED);
        job1.getEntitiesExtracted().set(5);
        job1.setRequest(UnifiedCrawlRequest.builder().name("Job 1").sources(List.of(
                UnifiedCrawlSource.builder().label("a").build())).build());

        UnifiedCrawlJob job2 = buildMockJob("j2", UnifiedCrawlJob.Status.RUNNING);
        job2.getEntitiesExtracted().set(2);
        job2.setRequest(UnifiedCrawlRequest.builder().name("Job 2").sources(List.of(
                UnifiedCrawlSource.builder().label("b").build(),
                UnifiedCrawlSource.builder().label("c").build())).build());

        when(unifiedCrawlService.getAllJobs()).thenReturn(List.of(job1, job2));

        Map<String, Object> result = tool.listUnifiedCrawlJobs();

        assertEquals(2, result.get("total"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> jobs = (List<Map<String, Object>>) result.get("jobs");
        assertEquals(2, jobs.size());
        assertEquals("j1", jobs.get(0).get("jobId"));
        assertEquals("COMPLETED", jobs.get(0).get("status"));
        assertEquals(5, jobs.get(0).get("entitiesExtracted"));
        assertEquals(1, jobs.get(0).get("sourceCount"));
        assertEquals("j2", jobs.get(1).get("jobId"));
        assertEquals(2, jobs.get(1).get("sourceCount"));
    }

    @Test
    @DisplayName("List jobs when empty returns zero total")
    void listJobs_empty() {
        when(unifiedCrawlService.getAllJobs()).thenReturn(List.of());

        Map<String, Object> result = tool.listUnifiedCrawlJobs();
        assertEquals(0, result.get("total"));
    }

    // ──────────────────────────────────────────────────────────────────
    // 4. CANCEL
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Cancel running job returns success message")
    void cancelJob_success() {
        when(unifiedCrawlService.cancelJob("j-cancel")).thenReturn(true);

        Map<String, Object> result = tool.cancelUnifiedCrawl(new UnifiedCrawlGraphTool.JobIdInput("j-cancel"));
        assertEquals("Job cancelled", result.get("message"));
        assertEquals("j-cancel", result.get("jobId"));
    }

    @Test
    @DisplayName("Cancel finished or unknown job returns error")
    void cancelJob_failure() {
        when(unifiedCrawlService.cancelJob("j-done")).thenReturn(false);

        Map<String, Object> result = tool.cancelUnifiedCrawl(new UnifiedCrawlGraphTool.JobIdInput("j-done"));
        assertNotNull(result.get("error"));
    }

    // ──────────────────────────────────────────────────────────────────
    // 5. SOURCE TYPES
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("List source types returns available types with metadata")
    void listSourceTypes_returnsAll() {
        when(unifiedCrawlService.getAvailableSourceTypes()).thenReturn(List.of(
                new UnifiedCrawlService.AvailableSourceType("DIRECTORY", "Local Directory",
                        "Crawl local dir", true, List.of("pathOrUrl"), List.of("maxDepth")),
                new UnifiedCrawlService.AvailableSourceType("EMAIL", "Email",
                        "Crawl email", false, List.of("pathOrUrl"), List.of("host", "port"))
        ));

        Map<String, Object> result = tool.listSourceTypes();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> types = (List<Map<String, Object>>) result.get("sourceTypes");
        assertEquals(2, types.size());
        assertEquals("DIRECTORY", types.get(0).get("type"));
        assertEquals(true, types.get(0).get("available"));
        assertEquals("EMAIL", types.get(1).get("type"));
        assertEquals(false, types.get(1).get("available"));
        assertEquals(List.of("host", "port"), types.get(1).get("optionalProperties"));
    }

    // ──────────────────────────────────────────────────────────────────
    // HELPERS
    // ──────────────────────────────────────────────────────────────────

    private static UnifiedCrawlJob buildMockJob(String jobId, UnifiedCrawlJob.Status status) {
        return UnifiedCrawlJob.builder()
                .jobId(jobId)
                .status(new AtomicReference<>(status))
                .createdAt(Instant.now())
                .documentsDiscovered(new AtomicInteger(0))
                .documentsLoaded(new AtomicInteger(0))
                .chunksProcessed(new AtomicInteger(0))
                .documentsIndexed(new AtomicInteger(0))
                .entitiesExtracted(new AtomicInteger(0))
                .relationshipsExtracted(new AtomicInteger(0))
                .errorCount(new AtomicInteger(0))
                .sourceProgress(new ArrayList<>())
                .build();
    }
}
