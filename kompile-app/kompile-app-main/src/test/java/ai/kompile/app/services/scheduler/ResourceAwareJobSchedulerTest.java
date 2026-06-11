package ai.kompile.app.services.scheduler;

import ai.kompile.app.config.GpuDevice;
import ai.kompile.app.config.ResourceSchedulerConfig;
import ai.kompile.app.services.GpuResourceManager;
import ai.kompile.app.services.ModelLifecycleManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ResourceAwareJobScheduler")
class ResourceAwareJobSchedulerTest {

    private static final long GB = 1024L * 1024 * 1024;
    private static final GpuDevice TEST_GPU = new GpuDevice(0, 0, "Test GPU", 24 * GB, "local");

    @Mock private GpuResourceManager gpuResourceManager;
    @Mock private ModelLifecycleManager modelLifecycleManager;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ResourceSchedulerConfigService configService;
    private ResourceAwareJobScheduler scheduler;

    @BeforeEach
    void setUp() {
        ResourceSchedulerConfig config = ResourceSchedulerConfig.defaults();
        config.setDispatchIntervalMs(50); // fast dispatch for tests

        configService = mock(ResourceSchedulerConfigService.class);
        when(configService.getConfiguration()).thenReturn(config);

        when(gpuResourceManager.findBestDevice(anyString())).thenReturn(Optional.of(TEST_GPU));
        when(gpuResourceManager.canFit(anyString(), any())).thenReturn(true);
        when(modelLifecycleManager.acquireGpuForJob(anyString(), anyString(), anyString()))
                .thenReturn(TEST_GPU);

        scheduler = new ResourceAwareJobScheduler(
                gpuResourceManager, modelLifecycleManager, configService,
                eventPublisher, List.of(), null);
        scheduler.start();
    }

    @AfterEach
    void tearDown() {
        if (scheduler.isRunning()) {
            scheduler.stop();
        }
    }

    private ScheduledJob buildJob(String id, String type, ScheduledJob.JobExecutor executor) {
        return ScheduledJob.builder()
                .jobId(id).jobType(type)
                .description("Test " + type)
                .resourceProfile(JobResourceProfile.cpuOnly(type, "Test", GB))
                .executor(executor)
                .build();
    }

    private ScheduledJob buildGpuJob(String id, String type, ScheduledJob.JobExecutor executor) {
        return ScheduledJob.builder()
                .jobId(id).jobType(type)
                .description("GPU " + type)
                .resourceProfile(JobResourceProfile.gpuRequired(type, "Test GPU", 2 * GB, 4 * GB))
                .executor(executor)
                .build();
    }

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle {

        @Test
        void startAndStopLifecycle() {
            assertTrue(scheduler.isRunning());
            assertEquals(Integer.MAX_VALUE - 200, scheduler.getPhase());

            scheduler.stop();
            assertFalse(scheduler.isRunning());
        }

        @Test
        void doubleStartIsIdempotent() {
            assertTrue(scheduler.isRunning());
            scheduler.start(); // second call
            assertTrue(scheduler.isRunning());
        }

        @Test
        void doubleStopIsIdempotent() {
            scheduler.stop();
            assertFalse(scheduler.isRunning());
            scheduler.stop(); // second call
            assertFalse(scheduler.isRunning());
        }
    }

    @Nested
    @DisplayName("Job submission")
    class Submission {

        @Test
        void cpuJobExecutesToCompletion() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            ScheduledJob job = buildJob("j1", "crawl", ctx -> latch.countDown());

            CompletableFuture<ScheduledJob.JobResult> future = scheduler.submit(job);

            assertTrue(latch.await(5, TimeUnit.SECONDS), "Job should have executed");
            ScheduledJob.JobResult result = future.get(5, TimeUnit.SECONDS);
            assertTrue(result.success());
            assertNull(result.errorMessage());
            assertTrue(result.durationMs() >= 0);
        }

        @Test
        void gpuJobAcquiresAndReleasesGpu() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            ScheduledJob job = buildGpuJob("j1", "ingest", ctx -> latch.countDown());

            CompletableFuture<ScheduledJob.JobResult> future = scheduler.submit(job);
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            future.get(5, TimeUnit.SECONDS);

            verify(modelLifecycleManager).acquireGpuForJob(eq("j1"), anyString(), anyString());
            verify(modelLifecycleManager).releaseGpuForJob("j1");
        }

        @Test
        void failedJobReportsError() throws Exception {
            ScheduledJob job = buildJob("j1", "crawl", ctx -> {
                throw new RuntimeException("Intentional failure");
            });

            ScheduledJob.JobResult result = scheduler.submit(job).get(5, TimeUnit.SECONDS);

            assertFalse(result.success());
            assertTrue(result.errorMessage().contains("Intentional failure"));
        }

        @Test
        void queueFullRejectsJob() throws Exception {
            ResourceSchedulerConfig config = ResourceSchedulerConfig.defaults();
            config.setGlobalQueueDepth(1);
            config.setDispatchIntervalMs(50);
            // Only allow 1 concurrent crawl so the second job stays queued
            config.setMaxConcurrentByType(Map.of("crawl", 1));
            when(configService.getConfiguration()).thenReturn(config);

            // Block the first job so it stays running
            CountDownLatch blockingStarted = new CountDownLatch(1);
            CountDownLatch blockLatch = new CountDownLatch(1);
            ScheduledJob blocking = buildJob("blocking", "crawl", ctx -> {
                blockingStarted.countDown();
                try { blockLatch.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            });
            scheduler.submit(blocking);

            // Wait for blocking job to actually start executing
            assertTrue(blockingStarted.await(5, TimeUnit.SECONDS),
                    "Blocking job should have started");

            // Submit second job — blocked by concurrency limit, fills the queue (depth=1)
            ScheduledJob second = buildJob("second", "crawl", ctx -> {});
            scheduler.submit(second);

            // Give dispatch loop time to see the second job is blocked
            Thread.sleep(200);

            // Submit third job — queue should be full
            ScheduledJob overflow = buildJob("overflow", "crawl", ctx -> {});
            ScheduledJob.JobResult result = scheduler.submit(overflow).get(5, TimeUnit.SECONDS);

            assertFalse(result.success());
            assertTrue(result.errorMessage().contains("queue full"));

            blockLatch.countDown(); // unblock
        }
    }

    @Nested
    @DisplayName("Bypass mode")
    class BypassMode {

        @Test
        void disabledSchedulerExecutesImmediately() throws Exception {
            ResourceSchedulerConfig config = ResourceSchedulerConfig.defaults();
            config.setEnabled(false);
            when(configService.getConfiguration()).thenReturn(config);

            AtomicBoolean executed = new AtomicBoolean(false);
            ScheduledJob job = buildJob("j1", "crawl", ctx -> executed.set(true));

            ScheduledJob.JobResult result = scheduler.submit(job).get(5, TimeUnit.SECONDS);

            assertTrue(executed.get());
            assertTrue(result.success());
        }

        @Test
        void disabledSchedulerCapturesFailure() throws Exception {
            ResourceSchedulerConfig config = ResourceSchedulerConfig.defaults();
            config.setEnabled(false);
            when(configService.getConfiguration()).thenReturn(config);

            ScheduledJob job = buildJob("j1", "crawl", ctx -> {
                throw new RuntimeException("Bypass fail");
            });

            ScheduledJob.JobResult result = scheduler.submit(job).get(5, TimeUnit.SECONDS);
            assertFalse(result.success());
            assertTrue(result.errorMessage().contains("Bypass fail"));
        }
    }

    @Nested
    @DisplayName("Cancellation")
    class Cancellation {

        @Test
        void cancelQueuedJob() throws Exception {
            // Block dispatch so the second job stays queued
            ResourceSchedulerConfig config = ResourceSchedulerConfig.defaults();
            config.setMaxConcurrentByType(Map.of("crawl", 1));
            config.setDispatchIntervalMs(50);
            when(configService.getConfiguration()).thenReturn(config);

            CountDownLatch blockLatch = new CountDownLatch(1);
            ScheduledJob blocking = buildJob("blocking", "crawl", ctx -> {
                try { blockLatch.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            });
            scheduler.submit(blocking);
            Thread.sleep(200); // wait for blocking to start

            ScheduledJob queued = buildJob("queued", "crawl", ctx -> {});
            scheduler.submit(queued);
            Thread.sleep(100);

            boolean cancelled = scheduler.cancel("queued");
            assertTrue(cancelled);

            ScheduledJob.JobResult result = queued.getResultFuture().get(5, TimeUnit.SECONDS);
            assertFalse(result.success());
            assertTrue(result.errorMessage().contains("Cancelled"));

            blockLatch.countDown();
        }

        @Test
        void cancelUnknownJobReturnsFalse() {
            assertFalse(scheduler.cancel("nonexistent"));
        }

        @Test
        void cancelCompletedJobReturnsFalse() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            ScheduledJob job = buildJob("j1", "crawl", ctx -> latch.countDown());
            scheduler.submit(job);
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            job.getResultFuture().get(5, TimeUnit.SECONDS);

            assertFalse(scheduler.cancel("j1"));
        }
    }

    @Nested
    @DisplayName("Promotion")
    class Promotion {

        @Test
        void promoteChangesJobPriority() throws Exception {
            // Block dispatch so jobs stay queued
            ResourceSchedulerConfig config = ResourceSchedulerConfig.defaults();
            config.setMaxConcurrentByType(Map.of("crawl", 0)); // block all
            config.setDispatchIntervalMs(50);
            when(configService.getConfiguration()).thenReturn(config);

            ScheduledJob job = buildJob("j1", "crawl", ctx -> {});
            job.setPriority(30);
            scheduler.submit(job);
            Thread.sleep(100);

            scheduler.promote("j1", 100);

            ScheduledJob.ScheduledJobView view = scheduler.getJobView("j1");
            assertNotNull(view);
            assertEquals(100, view.priority());
        }

        @Test
        void promoteNonexistentJobIsNoOp() {
            assertDoesNotThrow(() -> scheduler.promote("nonexistent", 100));
        }
    }

    @Nested
    @DisplayName("Status and snapshots")
    class StatusTests {

        @Test
        void statusContainsExpectedFields() {
            Map<String, Object> status = scheduler.getStatus();

            assertNotNull(status.get("enabled"));
            assertNotNull(status.get("running"));
            assertNotNull(status.get("algorithm"));
            assertNotNull(status.get("queueDepth"));
            assertNotNull(status.get("runningCount"));
            assertNotNull(status.get("totalSubmitted"));
            assertNotNull(status.get("totalCompleted"));
            assertNotNull(status.get("totalFailed"));
            assertNotNull(status.get("totalCancelled"));
            assertNotNull(status.get("runningByType"));
            assertNotNull(status.get("queuedByType"));
            assertNotNull(status.get("maxConcurrentByType"));
            assertNotNull(status.get("externalSchedulerMode"));
            assertNotNull(status.get("availableExternalModes"));
        }

        @Test
        void statusTracksTotals() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            ScheduledJob job = buildJob("j1", "crawl", ctx -> latch.countDown());
            scheduler.submit(job);
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            job.getResultFuture().get(5, TimeUnit.SECONDS);

            Map<String, Object> status = scheduler.getStatus();
            assertEquals(1L, ((Number) status.get("totalSubmitted")).longValue());
            assertEquals(1L, ((Number) status.get("totalCompleted")).longValue());
        }

        @Test
        void queueSnapshotIsEmpty() {
            assertTrue(scheduler.getQueueSnapshot().isEmpty());
        }

        @Test
        void runningSnapshotIsEmpty() {
            assertTrue(scheduler.getRunningSnapshot().isEmpty());
        }

        @Test
        void getJobViewReturnsNullForUnknownJob() {
            assertNull(scheduler.getJobView("nonexistent"));
        }

        @Test
        void cleanupTerminalJobsRemovesOldEntries() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            ScheduledJob job = buildJob("j1", "crawl", ctx -> latch.countDown());
            scheduler.submit(job);
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            job.getResultFuture().get(5, TimeUnit.SECONDS);

            // Job just completed — cleanup should NOT remove it (within 5 min window)
            int removed = scheduler.cleanupTerminalJobs();
            assertEquals(0, removed);
        }
    }

    @Nested
    @DisplayName("Phase-aware GPU yield")
    class PhaseYield {

        @Test
        void phaseTransitionReleasesGpuDuringCpuPhase() throws Exception {
            CountDownLatch runningLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(1);

            ScheduledJob job = buildGpuJob("j1", "unifiedCrawl", ctx -> {
                runningLatch.countDown();
                try { doneLatch.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            });

            scheduler.submit(job);
            assertTrue(runningLatch.await(5, TimeUnit.SECONDS));
            Thread.sleep(100); // let state settle

            // Report transition to CPU-only phase
            scheduler.reportPhaseTransition("j1", "CHUNKING", false, 0);

            // Should have released GPU
            verify(modelLifecycleManager).releaseGpuForJob("j1");

            doneLatch.countDown();
        }

        @Test
        void phaseTransitionReacquiresGpuForGpuPhase() throws Exception {
            CountDownLatch runningLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(1);

            ScheduledJob job = buildGpuJob("j1", "unifiedCrawl", ctx -> {
                runningLatch.countDown();
                try { doneLatch.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            });

            scheduler.submit(job);
            assertTrue(runningLatch.await(5, TimeUnit.SECONDS));
            Thread.sleep(100);

            // Yield GPU
            scheduler.reportPhaseTransition("j1", "CHUNKING", false, 0);
            Thread.sleep(50);

            // Re-acquire GPU
            scheduler.reportPhaseTransition("j1", "VECTOR_INDEXING", true, 5 * GB);

            // acquireGpuForJob called twice: initial + re-acquire
            verify(modelLifecycleManager, atLeast(2)).acquireGpuForJob(eq("j1"), anyString(), anyString());

            doneLatch.countDown();
        }

        @Test
        void phaseTransitionForUnknownJobIsNoOp() {
            assertDoesNotThrow(() ->
                    scheduler.reportPhaseTransition("nonexistent", "PHASE", false, 0));
        }
    }

    @Nested
    @DisplayName("Concurrency limits")
    class ConcurrencyLimits {

        @Test
        void respectsPerTypeConcurrencyLimit() throws Exception {
            ResourceSchedulerConfig config = ResourceSchedulerConfig.defaults();
            config.setMaxConcurrentByType(Map.of("ingest", 1));
            config.setDispatchIntervalMs(50);
            when(configService.getConfiguration()).thenReturn(config);

            CountDownLatch firstRunning = new CountDownLatch(1);
            CountDownLatch firstDone = new CountDownLatch(1);
            AtomicBoolean secondStarted = new AtomicBoolean(false);

            ScheduledJob first = buildJob("first", "ingest", ctx -> {
                firstRunning.countDown();
                try { firstDone.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            });
            ScheduledJob second = buildJob("second", "ingest", ctx -> secondStarted.set(true));

            scheduler.submit(first);
            scheduler.submit(second);

            assertTrue(firstRunning.await(5, TimeUnit.SECONDS));
            Thread.sleep(300); // give dispatch loop time

            // Second should NOT have started while first is running
            assertFalse(secondStarted.get(), "Second job should be blocked by concurrency limit");

            firstDone.countDown();

            // Now second should execute
            second.getResultFuture().get(5, TimeUnit.SECONDS);
            assertTrue(secondStarted.get());
        }
    }

    @Nested
    @DisplayName("Conflict detection")
    class ConflictDetection {

        @Test
        void conflictingJobWaitsForRunningJob() throws Exception {
            ResourceSchedulerConfig config = ResourceSchedulerConfig.defaults();
            config.setMaxConcurrentByType(Map.of("training", 1, "vlm", 1));
            config.setDispatchIntervalMs(50);
            when(configService.getConfiguration()).thenReturn(config);

            CountDownLatch trainingRunning = new CountDownLatch(1);
            CountDownLatch trainingDone = new CountDownLatch(1);
            AtomicBoolean vlmStarted = new AtomicBoolean(false);

            ScheduledJob training = ScheduledJob.builder()
                    .jobId("t1").jobType("training")
                    .description("Train")
                    .resourceProfile(JobResourceProfiles.TRAINING)
                    .executor(ctx -> {
                        trainingRunning.countDown();
                        try { trainingDone.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                    })
                    .build();

            ScheduledJob vlm = ScheduledJob.builder()
                    .jobId("v1").jobType("vlm")
                    .description("VLM")
                    .resourceProfile(JobResourceProfiles.VLM)
                    .executor(ctx -> vlmStarted.set(true))
                    .build();

            scheduler.submit(training);
            assertTrue(trainingRunning.await(5, TimeUnit.SECONDS));

            scheduler.submit(vlm);
            Thread.sleep(300);

            // VLM conflicts with training — should not have started
            assertFalse(vlmStarted.get(), "VLM should be blocked by conflict with running training");

            trainingDone.countDown();

            // Now VLM should run
            vlm.getResultFuture().get(5, TimeUnit.SECONDS);
            assertTrue(vlmStarted.get());
        }
    }

    @Nested
    @DisplayName("GPU acquisition failure")
    class GpuFailure {

        @Test
        void gpuAcquisitionFailureFailsJob() throws Exception {
            when(modelLifecycleManager.acquireGpuForJob(anyString(), anyString(), anyString()))
                    .thenThrow(new IllegalStateException("No GPU available"));

            ScheduledJob job = buildGpuJob("j1", "ingest", ctx -> fail("Should not execute"));

            ScheduledJob.JobResult result = scheduler.submit(job).get(5, TimeUnit.SECONDS);

            assertFalse(result.success());
            assertTrue(result.errorMessage().contains("GPU acquisition failed"));
        }
    }

    @Nested
    @DisplayName("Event publishing")
    class EventPublishing {

        @Test
        void jobLifecyclePublishesEvents() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            ScheduledJob job = buildJob("j1", "crawl", ctx -> latch.countDown());

            scheduler.submit(job);
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            job.getResultFuture().get(5, TimeUnit.SECONDS);

            // Should have published at least: QUEUED, DISPATCHED, COMPLETED
            ArgumentCaptor<JobSchedulerEvent> captor = ArgumentCaptor.forClass(JobSchedulerEvent.class);
            verify(eventPublisher, atLeast(3)).publishEvent(captor.capture());

            List<JobSchedulerEvent> events = captor.getAllValues();
            assertTrue(events.stream().anyMatch(e ->
                    e.getEventType() == JobSchedulerEvent.EventType.JOB_QUEUED));
            assertTrue(events.stream().anyMatch(e ->
                    e.getEventType() == JobSchedulerEvent.EventType.JOB_DISPATCHED));
            assertTrue(events.stream().anyMatch(e ->
                    e.getEventType() == JobSchedulerEvent.EventType.JOB_COMPLETED));
        }
    }

    @Nested
    @DisplayName("External delegate dispatch")
    class ExternalDelegateDispatch {

        @Test
        void jobDispatchesToExternalWhenConfigured() throws Exception {
            ResourceSchedulerConfig config = ResourceSchedulerConfig.defaults();
            config.setExternalSchedulerMode("kubernetes");
            config.setDispatchIntervalMs(50);
            when(configService.getConfiguration()).thenReturn(config);

            ExternalJobSchedulerDelegate mockDelegate = mock(ExternalJobSchedulerDelegate.class);
            when(mockDelegate.getMode()).thenReturn("kubernetes");
            when(mockDelegate.isAvailable()).thenReturn(true);
            when(mockDelegate.submitJob(anyString(), anyString(), anyString(), any(), any()))
                    .thenReturn(CompletableFuture.completedFuture(
                            new ExternalJobSchedulerDelegate.ExternalJobRef("k8s-job-1", "SUBMITTED", "ok")));

            // Rebuild scheduler with external delegate
            scheduler.stop();
            scheduler = new ResourceAwareJobScheduler(
                    gpuResourceManager, modelLifecycleManager, configService,
                    eventPublisher, List.of(mockDelegate), null);
            scheduler.start();

            ScheduledJob job = buildJob("j1", "crawl", ctx -> {});
            scheduler.submit(job);

            Thread.sleep(500);

            verify(mockDelegate).submitJob(eq("j1"), eq("crawl"), anyString(), any(), any());
        }

        @Test
        void externalSubmitFailureCompletesJobAsFailed() throws Exception {
            ResourceSchedulerConfig config = ResourceSchedulerConfig.defaults();
            config.setExternalSchedulerMode("webhook");
            config.setDispatchIntervalMs(50);
            when(configService.getConfiguration()).thenReturn(config);

            ExternalJobSchedulerDelegate mockDelegate = mock(ExternalJobSchedulerDelegate.class);
            when(mockDelegate.getMode()).thenReturn("webhook");
            when(mockDelegate.isAvailable()).thenReturn(true);
            when(mockDelegate.submitJob(anyString(), anyString(), anyString(), any(), any()))
                    .thenReturn(CompletableFuture.completedFuture(
                            new ExternalJobSchedulerDelegate.ExternalJobRef("ext-1", "FAILED", "Connection refused")));

            scheduler.stop();
            scheduler = new ResourceAwareJobScheduler(
                    gpuResourceManager, modelLifecycleManager, configService,
                    eventPublisher, List.of(mockDelegate), null);
            scheduler.start();

            ScheduledJob job = buildJob("j1", "crawl", ctx -> fail("Should not execute locally"));
            CompletableFuture<ScheduledJob.JobResult> future = scheduler.submit(job);

            ScheduledJob.JobResult result = future.get(5, TimeUnit.SECONDS);
            assertFalse(result.success());
            assertTrue(result.errorMessage().contains("rejected"));
        }

        @Test
        void externalCallbackCompletesJob() throws Exception {
            ResourceSchedulerConfig config = ResourceSchedulerConfig.defaults();
            config.setExternalSchedulerMode("kubernetes");
            config.setDispatchIntervalMs(50);
            when(configService.getConfiguration()).thenReturn(config);

            ExternalJobSchedulerDelegate mockDelegate = mock(ExternalJobSchedulerDelegate.class);
            when(mockDelegate.getMode()).thenReturn("kubernetes");
            when(mockDelegate.isAvailable()).thenReturn(true);
            when(mockDelegate.submitJob(anyString(), anyString(), anyString(), any(), any()))
                    .thenReturn(CompletableFuture.completedFuture(
                            new ExternalJobSchedulerDelegate.ExternalJobRef("k8s-job-1", "SUBMITTED", "ok")));
            when(mockDelegate.getJobStatus(anyString(), anyString()))
                    .thenReturn(CompletableFuture.completedFuture(
                            new ExternalJobSchedulerDelegate.ExternalJobStatus("k8s-job-1", "RUNNING", "In progress", Map.of())));

            scheduler.stop();
            scheduler = new ResourceAwareJobScheduler(
                    gpuResourceManager, modelLifecycleManager, configService,
                    eventPublisher, List.of(mockDelegate), null);
            scheduler.start();

            ScheduledJob job = buildJob("j1", "crawl", ctx -> {});
            CompletableFuture<ScheduledJob.JobResult> future = scheduler.submit(job);
            Thread.sleep(300); // let it dispatch to external

            // Simulate external callback
            scheduler.handleExternalCallback("j1", true, "Done");

            ScheduledJob.JobResult result = future.get(5, TimeUnit.SECONDS);
            assertTrue(result.success());
        }

        @Test
        void handleExternalCallbackForUnknownJobIsNoOp() {
            assertDoesNotThrow(() -> scheduler.handleExternalCallback("unknown", true, "ok"));
        }
    }

    @Nested
    @DisplayName("External delegate status")
    class ExternalDelegateStatus {

        @Test
        void getExternalDelegateStatusWithNoDelegates() {
            List<Map<String, Object>> status = scheduler.getExternalDelegateStatus();
            assertTrue(status.isEmpty());
        }

        @Test
        void getExternalDelegateStatusShowsAvailability() {
            scheduler.stop();

            ExternalJobSchedulerDelegate delegate = mock(ExternalJobSchedulerDelegate.class);
            when(delegate.getMode()).thenReturn("kubernetes");
            when(delegate.isAvailable()).thenReturn(true);

            scheduler = new ResourceAwareJobScheduler(
                    gpuResourceManager, modelLifecycleManager, configService,
                    eventPublisher, List.of(delegate), null);

            List<Map<String, Object>> status = scheduler.getExternalDelegateStatus();
            assertEquals(1, status.size());
            assertEquals("kubernetes", status.get(0).get("mode"));
            assertTrue((Boolean) status.get(0).get("available"));
        }
    }

    @Nested
    @DisplayName("Shutdown behavior")
    class ShutdownBehavior {

        @Test
        void shutdownCancelsQueuedJobs() throws Exception {
            ResourceSchedulerConfig config = ResourceSchedulerConfig.defaults();
            config.setMaxConcurrentByType(Map.of("crawl", 0)); // prevent dispatch
            config.setDispatchIntervalMs(50);
            when(configService.getConfiguration()).thenReturn(config);

            ScheduledJob job = buildJob("j1", "crawl", ctx -> {});
            scheduler.submit(job);
            Thread.sleep(100);

            scheduler.stop();

            ScheduledJob.JobResult result = job.getResultFuture().get(5, TimeUnit.SECONDS);
            assertFalse(result.success());
            assertTrue(result.errorMessage().contains("Cancelled"));
        }
    }
}
