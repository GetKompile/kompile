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
package ai.kompile.app.web.controllers;

import ai.kompile.app.facts.domain.FactSheet;
import ai.kompile.app.facts.service.FactSheetService;
import ai.kompile.app.ontology.OntologySchemaEnrichmentService;
import ai.kompile.app.services.SingleSourceCrawlPreviewService;
import ai.kompile.app.services.SingleSourceCrawlStarter;
import ai.kompile.app.services.scheduler.JobResourceProfiles;
import ai.kompile.app.services.scheduler.ResourceAwareJobScheduler;
import ai.kompile.app.services.scheduler.ScheduledJob;
import ai.kompile.app.web.dto.ontology.OwlClassificationResponse;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.core.crawl.graph.UnifiedCrawlService;
import ai.kompile.core.crawl.graph.UnifiedCrawlSource;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.crawl.graph.GraphExtractionPreviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UnifiedCrawlControllerTest {

    @Test
    void historyRerunRunsSchemaEnrichmentForEnrichmentStep() throws Exception {
        UnifiedCrawlController controller = new UnifiedCrawlController(mock(UnifiedCrawlService.class));
        OntologySchemaEnrichmentService schema = mock(OntologySchemaEnrichmentService.class);
        OwlClassificationResponse expected = response();
        when(schema.generateSchemaAndTypes(7L)).thenReturn(expected);
        controller.schemaEnrichmentService = schema;

        OwlClassificationResponse result = controller.runSchemaEnrichmentForHistoryRerun("ENRICHMENT", 7L);

        assertSame(expected, result);
        verify(schema).generateSchemaAndTypes(7L);
    }

    @Test
    void historyRerunDoesNotRunSchemaEnrichmentForStandaloneReasoningSteps() throws Exception {
        UnifiedCrawlController controller = new UnifiedCrawlController(mock(UnifiedCrawlService.class));
        OntologySchemaEnrichmentService schema = mock(OntologySchemaEnrichmentService.class);
        controller.schemaEnrichmentService = schema;

        OwlClassificationResponse result = controller.runSchemaEnrichmentForHistoryRerun("DERIVATION", 7L);

        assertNull(result);
        verifyNoInteractions(schema);
    }

    private static OwlClassificationResponse response() {
        return OwlClassificationResponse.builder()
                .factSheetId(7L)
                .ontologyBound(true)
                .ontologyName("ontology")
                .inferredTypeCount(1)
                .inferredRelationCount(1)
                .entitiesClassified(1)
                .edgesMaterialized(1)
                .consistent(true)
                .reasonerActive(true)
                .build();
    }

    // ---- POST /api/unified-crawl/single-source ----

    private static SingleSourceCrawlStarter.SingleSourceRunRequest runRequest(
            String pathOrUrl, String content, boolean dryRun, List<String> steps, Integer waitTimeoutSeconds) {
        return runRequest(pathOrUrl, content, dryRun, steps, null, null, waitTimeoutSeconds);
    }

    private static SingleSourceCrawlStarter.SingleSourceRunRequest runRequest(
            String pathOrUrl,
            String content,
            boolean dryRun,
            List<String> steps,
            String modelName,
            String llmProvider,
            Integer waitTimeoutSeconds) {
        return new SingleSourceCrawlStarter.SingleSourceRunRequest(
                null, null, pathOrUrl, content, null, null, null, null, null, null,
                dryRun, steps, null, null, null, modelName, llmProvider, waitTimeoutSeconds);
    }

    @Test
    void singleSource_rejectsBothOrNeitherInputs() {
        UnifiedCrawlController controller = new UnifiedCrawlController(mock(UnifiedCrawlService.class));

        ResponseEntity<?> both = controller.runSingleSource(
                runRequest("/tmp/doc.md", "inline text", true, null, null));
        assertEquals(400, both.getStatusCode().value());

        ResponseEntity<?> neither = controller.runSingleSource(
                runRequest(null, null, true, null, null));
        assertEquals(400, neither.getStatusCode().value());
    }

    @Test
    void singleSourceDry_delegatesToPreviewAndMapsUnifiedResponse() throws Exception {
        UnifiedCrawlController controller = new UnifiedCrawlController(mock(UnifiedCrawlService.class));
        SingleSourceCrawlPreviewService preview = mock(SingleSourceCrawlPreviewService.class);
        controller.singleSourceCrawlPreviewService = preview;

        GraphExtractionPreviewService.PreviewResponse graph = new GraphExtractionPreviewService.PreviewResponse(
                true, true, true, true, "COMPLETED", 1, 0, 2, 1, "test-model",
                List.of(new GraphExtractionPreviewService.EntityPreview(
                        "e1", "Alice", "PERSON", List.of(), null, 0.9, "d1", "Doc", Map.of())),
                List.of(new GraphExtractionPreviewService.RelationPreview(
                        "e1", "e2", "WORKS_AT", "Alice", "Acme", null, 0.8, "d1", "Doc", Map.of(), null)),
                List.of("graph warning"));
        SingleSourceCrawlPreviewService.SingleSourcePreviewResponse previewResponse =
                new SingleSourceCrawlPreviewService.SingleSourcePreviewResponse(
                        "p1", true, "file", "doc.md", "COMPLETED", false, null, "tika", "default",
                        1, 1, 3, 0, 0, false, List.of(), List.of("preview warning"), graph);
        when(preview.preview(any())).thenReturn(previewResponse);

        ResponseEntity<?> response = controller.runSingleSource(
                runRequest("/tmp/doc.md", null, true, List.of("GRAPH_EXTRACTION"),
                        "lfm2.5-1.2b-instruct", "serving", null));

        assertEquals(200, response.getStatusCode().value());
        SingleSourceCrawlStarter.SingleSourceRunResponse body =
                (SingleSourceCrawlStarter.SingleSourceRunResponse) response.getBody();
        assertNotNull(body);
        assertTrue(body.dryRun());
        assertTrue(body.completed());
        assertEquals(false, body.persisted());
        assertNull(body.jobId());
        assertEquals(2, body.entityCount());
        assertEquals(1, body.relationCount());
        assertEquals(3, body.chunksCreated());
        assertEquals(1, body.documentsLoaded());
        assertEquals(1L, body.entityTypeCounts().get("PERSON"));
        assertEquals(1L, body.relationshipTypeCounts().get("WORKS_AT"));
        assertEquals(1, body.sampleEntities().size());
        assertEquals("Alice", body.sampleEntities().get(0).name());
        assertTrue(body.warnings().stream().anyMatch(w -> w.contains("persist runs only")),
                "steps + dryRun must warn, not error");
        assertTrue(body.warnings().contains("preview warning"));
        assertTrue(body.warnings().contains("graph warning"));

        ArgumentCaptor<SingleSourceCrawlPreviewService.SingleSourcePreviewRequest> captor =
                ArgumentCaptor.forClass(SingleSourceCrawlPreviewService.SingleSourcePreviewRequest.class);
        verify(preview).preview(captor.capture());
        assertEquals("file", captor.getValue().sourceType());
        assertEquals(1, captor.getValue().maxDocuments());
        assertEquals(Boolean.TRUE, captor.getValue().properties().get("graphPreviewEnabled"));
        assertEquals("lfm2.5-1.2b-instruct",
                captor.getValue().properties().get("graphPreviewModelName"));
        assertEquals("serving",
                captor.getValue().properties().get("graphPreviewLlmProvider"));
    }

    @Test
    void singleSourcePersist_startsViaStarterWithWaitOptions() throws Exception {
        UnifiedCrawlController controller = new UnifiedCrawlController(mock(UnifiedCrawlService.class));
        SingleSourceCrawlStarter starter = mock(SingleSourceCrawlStarter.class);
        when(starter.isAvailable()).thenReturn(true);
        SingleSourceCrawlStarter.SingleSourceCrawlResult result =
                new SingleSourceCrawlStarter.SingleSourceCrawlResult(
                        "job-9", "COMPLETED", 5L, 1, true, true,
                        Boolean.TRUE, Boolean.TRUE,
                        Map.of("GRAPH_EXTRACTION", "RUN", "ENRICHMENT", "SKIP"),
                        List.of(), 4, 2, Map.of("PERSON", 4L), Map.of(), 3, 1, 0,
                        List.of(), List.of(), 120L);
        when(starter.start(anyString(), any(UnifiedCrawlSource.class),
                any(SingleSourceCrawlStarter.SingleSourceCrawlOptions.class))).thenReturn(result);
        controller.singleSourceCrawlStarter = starter;

        ResponseEntity<?> response = controller.runSingleSource(
                runRequest("/tmp/doc.md", null, false, List.of("GRAPH_EXTRACTION"), 7));

        assertEquals(200, response.getStatusCode().value());
        SingleSourceCrawlStarter.SingleSourceRunResponse body =
                (SingleSourceCrawlStarter.SingleSourceRunResponse) response.getBody();
        assertNotNull(body);
        assertEquals(false, body.dryRun());
        assertTrue(body.completed());
        assertTrue(body.persisted());
        assertEquals("job-9", body.jobId());
        assertEquals(5L, body.factSheetId());
        assertEquals(4, body.entityCount());
        assertEquals("RUN", body.stepsPlanned().get("GRAPH_EXTRACTION"));

        ArgumentCaptor<SingleSourceCrawlStarter.SingleSourceCrawlOptions> optionsCaptor =
                ArgumentCaptor.forClass(SingleSourceCrawlStarter.SingleSourceCrawlOptions.class);
        ArgumentCaptor<UnifiedCrawlSource> sourceCaptor = ArgumentCaptor.forClass(UnifiedCrawlSource.class);
        verify(starter).start(anyString(), sourceCaptor.capture(), optionsCaptor.capture());
        assertTrue(optionsCaptor.getValue().waitForCompletion());
        assertEquals(7_000L, optionsCaptor.getValue().waitTimeoutMs());
        assertEquals(List.of("GRAPH_EXTRACTION"), optionsCaptor.getValue().steps());
        assertEquals(DocumentSourceDescriptor.SourceType.FILE, sourceCaptor.getValue().getSourceType());
        assertEquals("/tmp/doc.md", sourceCaptor.getValue().getPathOrUrl());
    }

    @Test
    void singleSourcePersist_queueFullMapsTo503() throws Exception {
        UnifiedCrawlController controller = new UnifiedCrawlController(mock(UnifiedCrawlService.class));
        SingleSourceCrawlStarter starter = mock(SingleSourceCrawlStarter.class);
        when(starter.isAvailable()).thenReturn(true);
        when(starter.start(anyString(), any(UnifiedCrawlSource.class),
                any(SingleSourceCrawlStarter.SingleSourceCrawlOptions.class)))
                .thenThrow(new IllegalStateException("Unified crawl queue is full; try again later"));
        controller.singleSourceCrawlStarter = starter;

        ResponseEntity<?> response = controller.runSingleSource(
                runRequest("/tmp/doc.md", null, false, null, 5));

        assertEquals(503, response.getStatusCode().value());
    }

    @Test
    void startWithFilesPreservesFactSheetNameFromMultipartConfig(@TempDir Path tempDir) throws Exception {
        UnifiedCrawlService crawlService = mock(UnifiedCrawlService.class);
        when(crawlService.startJob(any())).thenAnswer(invocation -> UnifiedCrawlJob.builder()
                .jobId("job-upload")
                .request(invocation.getArgument(0))
                .status(new AtomicReference<>(UnifiedCrawlJob.Status.PENDING))
                .build());

        FactSheetService factSheetService = mock(FactSheetService.class);
        when(factSheetService.getSheetByName("Planning")).thenReturn(Optional.of(
                FactSheet.builder().id(91L).name("Planning").build()));

        UnifiedCrawlController controller = new UnifiedCrawlController(crawlService);
        controller.objectMapper = new ObjectMapper();
        controller.factSheetService = factSheetService;
        controller.uploadsPath = tempDir;

        MockMultipartFile file = new MockMultipartFile(
                "files", "budget.txt", "text/plain", "budget".getBytes());
        String config = """
                {"name":"Planning upload","factSheetName":"Planning","vectorIndex":{"enabled":false}}
                """;

        ResponseEntity<?> response = controller.startJobWithFiles(
                new MockMultipartFile[]{file}, config);

        assertEquals(200, response.getStatusCode().value());
        ArgumentCaptor<UnifiedCrawlRequest> requestCaptor =
                ArgumentCaptor.forClass(UnifiedCrawlRequest.class);
        verify(crawlService).startJob(requestCaptor.capture());
        assertEquals("Planning", requestCaptor.getValue().getFactSheetName());
        assertEquals(91L, requestCaptor.getValue().getFactSheetId());
    }

    @Test
    void singleSourceInlineContent_writesUploadsFileAndCrawlsIt(@TempDir Path tempDir) throws Exception {
        UnifiedCrawlController controller = new UnifiedCrawlController(mock(UnifiedCrawlService.class));
        SingleSourceCrawlStarter starter = mock(SingleSourceCrawlStarter.class);
        when(starter.isAvailable()).thenReturn(true);
        when(starter.start(anyString(), any(UnifiedCrawlSource.class),
                any(SingleSourceCrawlStarter.SingleSourceCrawlOptions.class)))
                .thenReturn(new SingleSourceCrawlStarter.SingleSourceCrawlResult(
                        "job-txt", "PENDING", null, 1, true, true,
                        Boolean.FALSE, Boolean.TRUE, Map.of(), List.of(),
                        0, 0, Map.of(), Map.of(), 0, 0, 0, List.of(), List.of(), 5L));
        controller.singleSourceCrawlStarter = starter;
        controller.uploadsPath = tempDir;

        SingleSourceCrawlStarter.SingleSourceRunRequest request =
                new SingleSourceCrawlStarter.SingleSourceRunRequest(
                        null, "My Note", null, "Alice works at Acme Corp.", null, null, null, null, null, null,
                        false, null, null, null, null, null, null, 1);
        ResponseEntity<?> response = controller.runSingleSource(request);

        assertEquals(200, response.getStatusCode().value());
        ArgumentCaptor<UnifiedCrawlSource> sourceCaptor = ArgumentCaptor.forClass(UnifiedCrawlSource.class);
        verify(starter).start(anyString(), sourceCaptor.capture(),
                any(SingleSourceCrawlStarter.SingleSourceCrawlOptions.class));
        UnifiedCrawlSource source = sourceCaptor.getValue();
        assertEquals(DocumentSourceDescriptor.SourceType.FILE, source.getSourceType());
        Path written = Path.of(source.getPathOrUrl());
        assertTrue(written.startsWith(tempDir), "inline content must land under uploadsPath");
        assertTrue(written.getFileName().toString().endsWith(".txt"));
        assertEquals("Alice works at Acme Corp.", Files.readString(written));
    }

    // ---- Scheduled crawl executor: a cancel must reach the crawl, and only a finished crawl succeeds ----

    private static UnifiedCrawlService crawlServiceStarting(UnifiedCrawlJob.Status status) {
        UnifiedCrawlService crawlService = mock(UnifiedCrawlService.class);
        when(crawlService.startJob(any())).thenReturn(UnifiedCrawlJob.builder()
                .jobId("crawl-internal")
                .status(new AtomicReference<>(status))
                .build());
        when(crawlService.cancelJob("crawl-internal")).thenReturn(true);
        return crawlService;
    }

    private static UnifiedCrawlRequest scheduledRequest() {
        return UnifiedCrawlRequest.builder().name("Scheduled crawl").factSheetId(3L).build();
    }

    private static ScheduledJob.JobExecutionContext context(String jobId, boolean cancellationRequested) {
        return new ScheduledJob.JobExecutionContext(jobId, JobResourceProfiles.UNIFIED_CRAWL,
                (id, phase, gpu, bytes) -> { }, null, new AtomicBoolean(cancellationRequested));
    }

    /** Starts a crawl through the (mock) scheduler and returns the job the controller submitted. */
    private static ScheduledJob submitScheduledCrawl(UnifiedCrawlController controller) {
        ResourceAwareJobScheduler scheduler = mock(ResourceAwareJobScheduler.class);
        controller.resourceScheduler = scheduler;
        assertEquals(200, controller.startJob(scheduledRequest()).getStatusCode().value());
        ArgumentCaptor<ScheduledJob> captor = ArgumentCaptor.forClass(ScheduledJob.class);
        verify(scheduler).submit(captor.capture());
        return captor.getValue();
    }

    @Test
    void scheduledCrawl_cancelRequestCancelsTheCrawlAndThrows() throws Exception {
        UnifiedCrawlService crawlService = crawlServiceStarting(UnifiedCrawlJob.Status.RUNNING);
        ScheduledJob job = submitScheduledCrawl(new UnifiedCrawlController(crawlService));

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThrows(CancellationException.class,
                () -> job.getExecutor().execute(context(job.getJobId(), true))));
        verify(crawlService).cancelJob("crawl-internal");
    }

    @Test
    void scheduledCrawl_interruptCancelsTheCrawlAndThrows() throws Exception {
        UnifiedCrawlService crawlService = crawlServiceStarting(UnifiedCrawlJob.Status.RUNNING);
        ScheduledJob job = submitScheduledCrawl(new UnifiedCrawlController(crawlService));

        // The scheduler's cancel interrupts the thread running the executor
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class,
                    () -> job.getExecutor().execute(context(job.getJobId(), false)));
        } finally {
            Thread.interrupted();
        }
        verify(crawlService).cancelJob("crawl-internal");
    }

    @Test
    void scheduledCrawl_timeoutCancelsTheCrawlAndFailsTheJob() {
        UnifiedCrawlService crawlService = crawlServiceStarting(UnifiedCrawlJob.Status.RUNNING);
        UnifiedCrawlController controller = new UnifiedCrawlController(crawlService);

        assertThrows(TimeoutException.class, () -> controller.runScheduledCrawl(
                context("crawl-sched", false), scheduledRequest(), "Scheduled crawl", 0L));
        verify(crawlService).cancelJob("crawl-internal");
    }

    @Test
    void scheduledCrawl_crawlCancelledOutsideTheSchedulerIsNotASuccess() throws Exception {
        UnifiedCrawlService crawlService = crawlServiceStarting(UnifiedCrawlJob.Status.CANCELLED);
        ScheduledJob job = submitScheduledCrawl(new UnifiedCrawlController(crawlService));

        assertThrows(CancellationException.class,
                () -> job.getExecutor().execute(context(job.getJobId(), false)));
    }

    @Test
    void scheduledCrawl_completedPendingGraphFinishesTheJob() throws Exception {
        UnifiedCrawlService crawlService = crawlServiceStarting(UnifiedCrawlJob.Status.COMPLETED_PENDING_GRAPH);
        ScheduledJob job = submitScheduledCrawl(new UnifiedCrawlController(crawlService));

        assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> job.getExecutor().execute(context(job.getJobId(), false)));
        verify(crawlService, never()).cancelJob(anyString());
    }

    // ---- Scheduled crawl executor: the scheduler hears the declared phase a reported phase stands for ----

    private static final long VECTOR_INDEXING_GPU_BYTES = 5L * 1024 * 1024 * 1024;

    private static UnifiedCrawlJob runningCrawl(String phase) {
        return UnifiedCrawlJob.builder()
                .jobId("crawl-internal")
                .status(new AtomicReference<>(UnifiedCrawlJob.Status.RUNNING))
                .currentPhase(new AtomicReference<>(phase))
                .build();
    }

    private static UnifiedCrawlService crawlServiceStarting(UnifiedCrawlJob crawl) {
        UnifiedCrawlService crawlService = mock(UnifiedCrawlService.class);
        when(crawlService.startJob(any())).thenReturn(crawl);
        when(crawlService.cancelJob("crawl-internal")).thenReturn(true);
        return crawlService;
    }

    /** A context whose phase callback records each transition, then lets {@code next} move the crawl on. */
    private static ScheduledJob.JobExecutionContext recordingContext(List<String> transitions, Runnable next) {
        return new ScheduledJob.JobExecutionContext("crawl-sched", JobResourceProfiles.UNIFIED_CRAWL,
                (id, phase, gpu, bytes) -> {
                    transitions.add(phase + " gpu=" + gpu + " bytes=" + bytes);
                    next.run();
                }, null, new AtomicBoolean(false));
    }

    @Test
    void scheduledCrawl_forwardsTheDeclaredPhaseAReportedPhaseStandsFor() {
        UnifiedCrawlJob crawl = runningCrawl("EMBEDDING");
        UnifiedCrawlController controller = new UnifiedCrawlController(crawlServiceStarting(crawl));
        List<String> transitions = new ArrayList<>();
        // Vector indexing, then a decomposed extraction pass, then the crawl completes
        ScheduledJob.JobExecutionContext ctx = recordingContext(transitions, () -> {
            if (transitions.size() == 1) {
                crawl.getCurrentPhase().set("GRAPH_EXTRACTION_RELATIONS");
            } else {
                crawl.getStatus().set(UnifiedCrawlJob.Status.COMPLETED);
            }
        });

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> controller.runScheduledCrawl(
                ctx, scheduledRequest(), "Scheduled crawl", 60_000L));
        assertEquals(List.of(
                "VECTOR_INDEXING gpu=true bytes=" + VECTOR_INDEXING_GPU_BYTES,
                "GRAPH_EXTRACTION gpu=false bytes=0"), transitions);
    }

    /** A crawl sets an end marker such as PARTITION_COMPLETE before its status flips; it is never forwarded. */
    @Test
    void scheduledCrawl_neverForwardsAnEndMarker() {
        UnifiedCrawlJob crawl = runningCrawl("GRAPH_PREP");
        UnifiedCrawlService crawlService = crawlServiceStarting(crawl);
        UnifiedCrawlController controller = new UnifiedCrawlController(crawlService);
        List<String> transitions = new ArrayList<>();
        // The crawl reports PARTITION_COMPLETE and stays RUNNING until the deadline: two more polls see it
        ScheduledJob.JobExecutionContext ctx = recordingContext(transitions,
                () -> crawl.getCurrentPhase().set("PARTITION_COMPLETE"));

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThrows(TimeoutException.class,
                () -> controller.runScheduledCrawl(ctx, scheduledRequest(), "Scheduled crawl", 2_500L)));
        assertEquals(List.of("GRAPH_PREP gpu=false bytes=0"), transitions);
        verify(crawlService).cancelJob("crawl-internal");
    }
}
