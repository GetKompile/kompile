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

import ai.kompile.app.services.DocumentIngestService.ProcessingMode;
import ai.kompile.app.services.VectorStorePopulationService.PopulationResult;
import ai.kompile.app.services.VectorStorePopulationService.PopulationTaskStatus;
import ai.kompile.app.services.pipeline.ParallelIngestPipeline;
import ai.kompile.app.services.scheduler.JobResourceProfiles;
import ai.kompile.app.services.scheduler.ResourceAwareJobScheduler;
import ai.kompile.app.services.scheduler.ScheduledJob;
import ai.kompile.app.services.subprocess.SubprocessConfigService;
import ai.kompile.app.services.subprocess.SubprocessHandle;
import ai.kompile.app.services.subprocess.SubprocessIngestLauncher;
import ai.kompile.app.services.subprocess.VectorPopulationSubprocessLauncher;
import ai.kompile.app.subprocess.SubprocessPlacement;
import ai.kompile.app.web.dto.IngestProgressUpdate;
import ai.kompile.app.web.dto.IngestProgressUpdate.IngestPhase;
import ai.kompile.app.web.dto.IngestProgressUpdate.IngestStats;
import ai.kompile.core.indexers.IndexerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The executors the ingest services hand to the scheduler must not return normally once their job
 * was cancelled or their work stopped: a normal return is recorded as a completed job. A cancel that
 * interrupts the wait on a subprocess must stop the child as well. A scheduled subprocess ingest runs
 * as its own job on that job's placement, and cancelling a task reaches its scheduler job unless an
 * in-process pipeline is running it.
 */
@Timeout(30)
class SchedulerExecutorCancellationTest {

    private static final String TASK_ID = "task-1";
    private static final Path FILE = Path.of("doc.pdf");

    private final ResourceAwareJobScheduler scheduler = mock(ResourceAwareJobScheduler.class);

    @Test
    void ingestJobCancelledByTheSchedulerDoesNotComplete() throws Exception {
        ScheduledJob job = scheduledIngestJob(ingestService(null));

        assertThrows(InterruptedException.class, () -> job.getExecutor().execute(context(true)));
    }

    @Test
    void cancelledIngestDoesNotCompleteItsJob() throws Exception {
        ScheduledJob job = scheduledIngestJob(ingestService(IngestProgressUpdate.cancelled(
                TASK_ID, "doc.pdf", IngestPhase.EMBEDDING, "Cancelled by user", IngestStats.empty())));

        assertThrows(CancellationException.class, () -> job.getExecutor().execute(context(false)));
    }

    @Test
    void failedIngestFailsItsJob() throws Exception {
        ScheduledJob job = scheduledIngestJob(ingestService(IngestProgressUpdate.failed(
                TASK_ID, "doc.pdf", IngestPhase.EMBEDDING, "loader failed", IngestStats.empty())));

        assertThrows(IllegalStateException.class, () -> job.getExecutor().execute(context(false)));
    }

    @Test
    void completedIngestCompletesItsJob() throws Exception {
        ScheduledJob job = scheduledIngestJob(ingestService(
                IngestProgressUpdate.completed(TASK_ID, "doc.pdf", IngestStats.empty())));

        job.getExecutor().execute(context(false));
    }

    @Test
    void interruptedSubprocessIngestStopsTheChild() throws Exception {
        SubprocessIngestLauncher launcher = mock(SubprocessIngestLauncher.class);
        when(launcher.launchIngest(eq(TASK_ID), any(), any(), any(), any(), any())).thenReturn(new CompletableFuture<>());
        DocumentIngestService ingest = new DocumentIngestService();
        ingest.resourceScheduler = scheduler;
        ingest.subprocessIngestLauncher = launcher;
        ingest.processDocumentAsync(TASK_ID, FILE, null, null, ProcessingMode.SUBPROCESS, null);
        ScheduledJob job = submittedJob();

        Thread.currentThread().interrupt();
        assertThrows(RuntimeException.class, () -> job.getExecutor().execute(context(false)));
        assertTrue(Thread.interrupted(), "the interrupt is restored for the scheduler");

        verify(launcher).cancelIngest(TASK_ID);
    }

    @Test
    void scheduledSubprocessIngestRunsAsItsOwnJob() throws Exception {
        SubprocessPlacement placement = SubprocessPlacement.gpu(1, 2L << 30);
        SubprocessIngestLauncher launcher = mock(SubprocessIngestLauncher.class);
        when(launcher.launchIngest(eq(TASK_ID), eq(FILE), any(), any(), any(), eq(placement)))
                .thenReturn(CompletableFuture.completedFuture(ingestResult(true)));
        ScheduledJob job = scheduledIngestJob(subprocessIngestService(launcher));

        job.getExecutor().execute(context(false, placement));

        // Launched on the job's own placement, with no second job queued under its id
        verify(launcher).launchIngest(eq(TASK_ID), eq(FILE), any(), any(), any(), eq(placement));
        verify(scheduler).submit(any());
    }

    @Test
    void failedScheduledSubprocessIngestFailsItsJob() throws Exception {
        SubprocessIngestLauncher launcher = mock(SubprocessIngestLauncher.class);
        when(launcher.launchIngest(eq(TASK_ID), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(ingestResult(false)));
        ScheduledJob job = scheduledIngestJob(subprocessIngestService(launcher));

        assertThrows(RuntimeException.class, () -> job.getExecutor().execute(context(false)));
    }

    @Test
    void cancellingAQueuedSubprocessIngestCancelsItsJobAndChild() throws Exception {
        SubprocessIngestLauncher launcher = mock(SubprocessIngestLauncher.class);
        DocumentIngestService ingest = subprocessIngestService(launcher);
        ingest.processDocumentAsync(TASK_ID, FILE, null, null, ProcessingMode.SUBPROCESS, null);
        when(scheduler.cancel(TASK_ID)).thenReturn(true);

        assertTrue(ingest.cancelTask(TASK_ID));

        verify(scheduler).cancel(TASK_ID);
        verify(launcher).cancelIngest(TASK_ID);
    }

    @Test
    void cancellingAScheduledIngestBeforeItStartsCancelsItsJob() throws Exception {
        DocumentIngestService ingest = subprocessIngestService(mock(SubprocessIngestLauncher.class));
        ingest.scheduleIngest(TASK_ID, FILE, null, null, Map.of());
        when(scheduler.cancel(TASK_ID)).thenReturn(true);

        assertTrue(ingest.cancelTask(TASK_ID));
        verify(scheduler).cancel(TASK_ID);
    }

    @Test
    void cancellingARunningInProcessIngestDoesNotInterruptItsJob() throws Exception {
        DocumentIngestService ingest = new DocumentIngestService();
        ingest.resourceScheduler = scheduler;
        ParallelIngestPipeline pipeline = mock(ParallelIngestPipeline.class);
        ingest.activePipelines.put(TASK_ID, pipeline);

        assertTrue(ingest.cancelTask(TASK_ID));

        verify(pipeline).cancel();
        verify(scheduler, never()).cancel(any());
    }

    @Test
    void vectorPopulationJobCancelledByTheSchedulerDoesNotComplete() throws Exception {
        ScheduledJob job = scheduledVectorPopulationJob();

        assertThrows(InterruptedException.class, () -> job.getExecutor().execute(context(true)));
    }

    @Test
    void successfulVectorPopulationCompletesItsJob() throws Exception {
        ScheduledJob job = scheduledVectorPopulationJob();

        job.getExecutor().execute(context(false));
    }

    @Test
    void interruptedSubprocessVectorPopulationStopsTheChild() throws Exception {
        VectorPopulationSubprocessLauncher launcher = mock(VectorPopulationSubprocessLauncher.class);
        when(launcher.launchVectorPopulation(eq(TASK_ID), any(), any(), any(), any())).thenReturn(new CompletableFuture<>());
        IndexerService indexer = mock(IndexerService.class);
        when(indexer.getIndexPath()).thenReturn("keyword-index");
        when(scheduler.submit(any())).thenReturn(new CompletableFuture<>());
        VectorStorePopulationService population = new VectorStorePopulationService();
        population.resourceScheduler = scheduler;
        population.subprocessLauncher = launcher;
        population.indexerService = indexer;
        population.setSubprocessModeEnabled(true);
        population.populateVectorStoreAsync(TASK_ID);
        ScheduledJob job = submittedJob();

        Thread.currentThread().interrupt();
        assertThrows(RuntimeException.class, () -> job.getExecutor().execute(context(false)));
        assertTrue(Thread.interrupted(), "the interrupt is restored for the scheduler");

        verify(launcher).cancelVectorPopulation(TASK_ID);
    }

    @Test
    void cancellingAScheduledVectorPopulationBeforeItStartsCancelsItsJob() throws Exception {
        VectorStorePopulationService population = new VectorStorePopulationService();
        population.resourceScheduler = scheduler;
        population.subprocessLauncher = mock(VectorPopulationSubprocessLauncher.class);
        when(scheduler.cancel(TASK_ID)).thenReturn(true);

        assertTrue(population.cancelTask(TASK_ID));
        verify(scheduler).cancel(TASK_ID);
    }

    @Test
    void cancellingARunningInProcessVectorPopulationDoesNotInterruptItsJob() throws Exception {
        VectorStorePopulationService population = new VectorStorePopulationService();
        population.resourceScheduler = scheduler;
        ParallelIngestPipeline pipeline = mock(ParallelIngestPipeline.class);
        population.activePipelines.put(TASK_ID, pipeline);
        population.activeTasks.put(TASK_ID, new PopulationTaskStatus(TASK_ID, 10));

        assertTrue(population.cancelTask(TASK_ID));

        verify(pipeline).cancel();
        verify(scheduler, never()).cancel(any());
    }

    /** An ingest service whose in-process ingest only records {@code outcome}, or nothing when null. */
    private DocumentIngestService ingestService(IngestProgressUpdate outcome) {
        DocumentIngestService ingest = spy(new DocumentIngestService());
        ingest.resourceScheduler = scheduler;
        Map<String, IngestProgressUpdate> taskStatuses = ingest.activeTasksStatus;
        doAnswer(invocation -> {
            if (outcome != null) {
                taskStatuses.put(TASK_ID, outcome);
            }
            return null;
        }).when(ingest).processDocumentAsync(any(), any(), any(), any(), any(), any());
        return ingest;
    }

    /** An ingest service that runs AUTO-mode ingests through {@code launcher}. */
    private DocumentIngestService subprocessIngestService(SubprocessIngestLauncher launcher) {
        SubprocessConfigService config = mock(SubprocessConfigService.class);
        when(config.isEnabled()).thenReturn(true);
        DocumentIngestService ingest = new DocumentIngestService();
        ingest.resourceScheduler = scheduler;
        ingest.subprocessIngestLauncher = launcher;
        ingest.subprocessConfigService = config;
        return ingest;
    }

    private static SubprocessHandle.SubprocessResult ingestResult(boolean success) {
        return new SubprocessHandle.SubprocessResult(TASK_ID, success, success ? 0 : 1, 1, 2, 2, 1, 10L, "index",
                success ? null : "loader failed", success ? null : "LOADING", false, false, false);
    }

    private ScheduledJob scheduledIngestJob(DocumentIngestService ingest) {
        ingest.scheduleIngest(TASK_ID, FILE, null, null, Map.of());
        return submittedJob();
    }

    /** A population job whose in-process population succeeds. */
    private ScheduledJob scheduledVectorPopulationJob() throws Exception {
        VectorStorePopulationService population = spy(new VectorStorePopulationService());
        population.resourceScheduler = scheduler;
        doReturn(new PopulationResult(TASK_ID, true, 3, 10, null)).when(population).populateVectorStore(TASK_ID);
        population.scheduleVectorPopulation(TASK_ID);
        return submittedJob();
    }

    private ScheduledJob submittedJob() {
        ArgumentCaptor<ScheduledJob> submitted = ArgumentCaptor.forClass(ScheduledJob.class);
        verify(scheduler).submit(submitted.capture());
        return submitted.getValue();
    }

    private static ScheduledJob.JobExecutionContext context(boolean cancelRequested) {
        return context(cancelRequested, null);
    }

    private static ScheduledJob.JobExecutionContext context(boolean cancelRequested, SubprocessPlacement placement) {
        return new ScheduledJob.JobExecutionContext(TASK_ID, JobResourceProfiles.INGEST, null, placement,
                new AtomicBoolean(cancelRequested));
    }
}
