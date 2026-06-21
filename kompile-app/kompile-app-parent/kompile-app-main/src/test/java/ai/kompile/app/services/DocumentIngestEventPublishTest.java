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
package ai.kompile.app.services;

import ai.kompile.app.web.dto.IngestProgressUpdate.IngestPhase;
import ai.kompile.app.web.dto.IngestProgressUpdate.IngestStats;
import ai.kompile.core.graphbuilder.GraphBuildCompletedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Verifies that completing an upload task via {@link IngestProgressTracker} publishes a
 * {@link GraphBuildCompletedEvent} — triggering the grounding-cascade (enrichment, MAP
 * inference, GroundingCascadeHook) exactly as a crawl-graph completion does.
 *
 * <p>This closes the gap documented in
 * {@code docs/architecture/learning-loop-multisource-promotion-design.md}, section 1:
 * "Sources-Interface Trigger Gap".</p>
 */
class DocumentIngestEventPublishTest {

    /**
     * Helper: create a publisher that records the most-recent ApplicationEvent published
     * via either overload ({@code publishEvent(ApplicationEvent)} or
     * {@code publishEvent(Object)}).
     */
    private static ApplicationEventPublisher capturingPublisher(AtomicReference<Object> captured) {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        // Capture via the ApplicationEvent overload (used when arg extends ApplicationEvent)
        doAnswer(inv -> { captured.set(inv.getArgument(0)); return null; })
                .when(publisher).publishEvent(any(ApplicationEvent.class));
        // Capture via the Object overload (used when caller explicitly targets it)
        doAnswer(inv -> { captured.set(inv.getArgument(0)); return null; })
                .when(publisher).publishEvent(any(Object.class));
        return publisher;
    }

    // ---------------------------------------------------------------------------
    // IngestProgressTracker (sync upload path)
    // ---------------------------------------------------------------------------

    @Test
    void completeTask_publishesGraphBuildCompletedEvent_withFactSheetId() {
        AtomicReference<Object> captured = new AtomicReference<>();
        ApplicationEventPublisher publisher = capturingPublisher(captured);
        IngestProgressTracker tracker = new IngestProgressTracker(null, null, publisher);

        tracker.startTask("task-1", "report.pdf", 42L);

        IngestStats stats = IngestStats.builder()
                .documentsLoaded(5)
                .chunksCreated(50)
                .totalProcessingTimeMs(3000L)
                .build();
        tracker.completeTask("task-1", "report.pdf", stats);

        Object event = captured.get();
        assertNotNull(event, "A GraphBuildCompletedEvent must be published on completeTask");
        assertInstanceOf(GraphBuildCompletedEvent.class, event);

        GraphBuildCompletedEvent gbe = (GraphBuildCompletedEvent) event;
        assertEquals(42L, gbe.getFactSheetId(),
                "factSheetId must be threaded from tracker into the event");
        assertTrue(gbe.getJobId().startsWith("upload:"),
                "jobId must carry the 'upload:' prefix so listeners can identify source");
        assertTrue(gbe.getJobId().contains("task-1"),
                "jobId must contain the taskId for traceability");
        assertEquals(5, gbe.getEntitiesExtracted(),
                "entitiesExtracted should reflect documentsLoaded from the pipeline");
    }

    @Test
    void completeTask_publishesEvent_withNullFactSheetId_whenNotRegistered() {
        AtomicReference<Object> captured = new AtomicReference<>();
        ApplicationEventPublisher publisher = capturingPublisher(captured);
        IngestProgressTracker tracker = new IngestProgressTracker(null, null, publisher);

        // No factSheetId registered — cascade hook drops it gracefully (per design)
        tracker.startTask("task-no-fs", "data.csv");
        tracker.completeTask("task-no-fs", "data.csv", IngestStats.builder().documentsLoaded(1).build());

        assertInstanceOf(GraphBuildCompletedEvent.class, captured.get(),
                "Event must still be published even without a factSheetId");
        GraphBuildCompletedEvent gbe = (GraphBuildCompletedEvent) captured.get();
        assertNull(gbe.getFactSheetId(),
                "null factSheetId must pass through — cascade hook handles it gracefully");
        assertTrue(gbe.getJobId().contains("task-no-fs"));
    }

    @Test
    void completeTask_doesNotThrow_whenPublisherIsNull() {
        // eventPublisher is null (minimal/native context) — must not blow up
        IngestProgressTracker tracker = new IngestProgressTracker(null, null, null);
        tracker.startTask("task-2", "file.pdf", 99L);
        assertDoesNotThrow(() ->
                tracker.completeTask("task-2", "file.pdf", IngestStats.builder().documentsLoaded(2).build()));
    }

    @Test
    void completeTask_publishesExactlyOnce_perCompletion() {
        AtomicReference<Object> captured = new AtomicReference<>();
        ApplicationEventPublisher publisher = capturingPublisher(captured);
        IngestProgressTracker tracker = new IngestProgressTracker(null, null, publisher);

        tracker.startTask("task-3", "doc.docx", 7L);
        tracker.completeTask("task-3", "doc.docx", IngestStats.builder().documentsLoaded(3).build());

        assertNotNull(captured.get(), "Event must be published exactly once per completion");
        assertInstanceOf(GraphBuildCompletedEvent.class, captured.get());
        // Verify the mock recorded exactly one ApplicationEvent call
        verify(publisher, times(1)).publishEvent(any(ApplicationEvent.class));
    }

    @Test
    void failedTask_doesNotPublishGraphBuildCompletedEvent() {
        AtomicReference<Object> captured = new AtomicReference<>();
        ApplicationEventPublisher publisher = capturingPublisher(captured);
        IngestProgressTracker tracker = new IngestProgressTracker(null, null, publisher);

        tracker.startTask("task-fail", "bad.pdf", 5L);
        tracker.failTask("task-fail", "bad.pdf", IngestPhase.LOADING, "File corrupt");

        // No GraphBuildCompletedEvent on failure — cascade must not fire for failed ingests
        assertNull(captured.get(), "No event must be published on ingest failure");
    }

    // ---------------------------------------------------------------------------
    // GraphProvenanceKeys.upload() — per-source provenance distinct from crawl
    // ---------------------------------------------------------------------------

    @Test
    void provenanceUpload_sourceIsDistinctFromCrawl() {
        var uploadMap = ai.kompile.knowledgegraph.domain.GraphProvenanceKeys.upload("task-abc", "report.pdf");
        var crawlMap = ai.kompile.knowledgegraph.domain.GraphProvenanceKeys.crawl("run-1", "report.pdf", "chunk-0");

        assertEquals("upload", uploadMap.get(ai.kompile.knowledgegraph.domain.GraphProvenanceKeys.SOURCE),
                "_source=upload distinguishes uploaded documents from crawled ones");
        assertEquals("crawl", crawlMap.get(ai.kompile.knowledgegraph.domain.GraphProvenanceKeys.SOURCE));
        assertEquals("task-abc",
                uploadMap.get(ai.kompile.knowledgegraph.domain.GraphProvenanceKeys.SOURCE_DOCUMENT_ID),
                "taskId maps to _sourceDocumentId for per-document traceability");
        assertEquals("report.pdf",
                uploadMap.get(ai.kompile.knowledgegraph.domain.GraphProvenanceKeys.SOURCE_CHUNK_ID),
                "fileName maps to _sourceChunkId for file-level granularity");
        assertFalse(uploadMap.containsKey(ai.kompile.knowledgegraph.domain.GraphProvenanceKeys.CRAWL_RUN_ID),
                "Upload provenance must not carry a crawlRunId");
    }
}
