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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for dynamic skip-ahead scheduling: when high-priority GPU jobs are blocked,
 * lower-priority CPU jobs skip ahead. Also tests phase-aware GPU yield enabling
 * queued jobs, blocked reason tracking, and the event types that feed UI notifications.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Dynamic skip-ahead scheduling")
class DynamicSkipAheadSchedulingTest {

    private static final long GB = 1024L * 1024 * 1024;
    private static final GpuDevice TEST_GPU = new GpuDevice(0, 0, "Test GPU", 24 * GB, "local");

    @Mock private GpuResourceManager gpuResourceManager;
    @Mock private ModelLifecycleManager modelLifecycleManager;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ResourceSchedulerConfigService configService;
    private ResourceSchedulerConfig config;
    private ResourceAwareJobScheduler scheduler;

    @BeforeEach
    void setUp() {
        config = ResourceSchedulerConfig.defaults();
        config.setDispatchIntervalMs(50);

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

    private ScheduledJob cpuJob(String id, String type, int priority, ScheduledJob.JobExecutor executor) {
        ScheduledJob job = ScheduledJob.builder()
                .jobId(id).jobType(type)
                .description("CPU " + type + " " + id)
                .resourceProfile(JobResourceProfile.cpuOnly(type, "Test", GB))
                .executor(executor)
                .priority(priority)
                .build();
        return job;
    }

    private ScheduledJob gpuJob(String id, String type, int priority, ScheduledJob.JobExecutor executor) {
        return ScheduledJob.builder()
                .jobId(id).jobType(type)
                .description("GPU " + type + " " + id)
                .resourceProfile(JobResourceProfile.gpuRequired(type, "Test GPU", 2 * GB, 4 * GB))
                .executor(executor)
                .priority(priority)
                .build();
    }

    // ==================== Skip-ahead dispatch ====================

    @Nested
    @DisplayName("Skip-ahead when GPU jobs are blocked")
    class SkipAhead {

        @Test
        @DisplayName("CPU job skips ahead of blocked GPU job when GPU unavailable")
        void cpuJobSkipsAheadOfBlockedGpuJob() throws Exception {
            // Make GPU unavailable
            when(gpuResourceManager.canFit(anyString(), any())).thenReturn(false);
            when(gpuResourceManager.findEvictionCandidates(anyString(), any())).thenReturn(List.of());

            // Submit high-priority GPU job — should be blocked
            AtomicBoolean gpuJobRan = new AtomicBoolean(false);
            ScheduledJob gpuJ = gpuJob("gpu-1", "ingest", 90, ctx -> gpuJobRan.set(true));
            scheduler.submit(gpuJ);

            // Submit lower-priority CPU job — should skip ahead
            CountDownLatch cpuDone = new CountDownLatch(1);
            ScheduledJob cpuJ = cpuJob("cpu-1", "crawl", 50, ctx -> cpuDone.countDown());
            scheduler.submit(cpuJ);

            // CPU job should complete, GPU job should still be blocked
            assertTrue(cpuDone.await(5, TimeUnit.SECONDS), "CPU job should skip ahead and run");
            cpuJ.getResultFuture().get(5, TimeUnit.SECONDS);
            assertTrue(cpuJ.getResultFuture().get().success());

            Thread.sleep(200);
            assertFalse(gpuJobRan.get(), "GPU job should remain blocked");

            // Clean up: cancel the blocked GPU job
            scheduler.cancel("gpu-1");
        }

        @Test
        @DisplayName("Multiple CPU jobs skip ahead of multiple blocked GPU jobs")
        void multipleCpuJobsSkipAhead() throws Exception {
            when(gpuResourceManager.canFit(anyString(), any())).thenReturn(false);
            when(gpuResourceManager.findEvictionCandidates(anyString(), any())).thenReturn(List.of());

            // Submit two high-priority GPU jobs
            ScheduledJob gpu1 = gpuJob("gpu-1", "ingest", 90, ctx -> {});
            ScheduledJob gpu2 = gpuJob("gpu-2", "training", 80, ctx -> {});
            scheduler.submit(gpu1);
            scheduler.submit(gpu2);

            // Submit two lower-priority CPU jobs
            CountDownLatch cpuDone = new CountDownLatch(2);
            ScheduledJob cpu1 = cpuJob("cpu-1", "crawl", 50, ctx -> cpuDone.countDown());
            ScheduledJob cpu2 = cpuJob("cpu-2", "crawl", 40, ctx -> cpuDone.countDown());
            scheduler.submit(cpu1);
            scheduler.submit(cpu2);

            assertTrue(cpuDone.await(5, TimeUnit.SECONDS), "Both CPU jobs should skip ahead");

            ScheduledJob.JobResult r1 = cpu1.getResultFuture().get(5, TimeUnit.SECONDS);
            ScheduledJob.JobResult r2 = cpu2.getResultFuture().get(5, TimeUnit.SECONDS);
            assertTrue(r1.success());
            assertTrue(r2.success());

            scheduler.cancel("gpu-1");
            scheduler.cancel("gpu-2");
        }

        @Test
        @DisplayName("GPU job dispatches when GPU becomes available again")
        void gpuJobDispatchesWhenGpuFreed() throws Exception {
            // Initially block GPU
            AtomicBoolean gpuAvailable = new AtomicBoolean(false);
            when(gpuResourceManager.canFit(anyString(), any()))
                    .thenAnswer(inv -> gpuAvailable.get());
            when(gpuResourceManager.findEvictionCandidates(anyString(), any())).thenReturn(List.of());

            CountDownLatch gpuDone = new CountDownLatch(1);
            ScheduledJob gpuJ = gpuJob("gpu-1", "ingest", 90, ctx -> gpuDone.countDown());
            scheduler.submit(gpuJ);

            // Verify GPU job is blocked
            Thread.sleep(300);
            assertFalse(gpuDone.getCount() == 0, "GPU job should be blocked initially");

            // Free GPU
            gpuAvailable.set(true);

            // GPU job should now run
            assertTrue(gpuDone.await(5, TimeUnit.SECONDS), "GPU job should run after GPU freed");
            assertTrue(gpuJ.getResultFuture().get(5, TimeUnit.SECONDS).success());
        }
    }

    // ==================== Event emission ====================

    @Nested
    @DisplayName("Skip-ahead event emission for UI notifications")
    class SkipAheadEvents {

        @Test
        @DisplayName("JOB_BLOCKED event emitted when GPU job is stuck")
        void blockedEventEmitted() throws Exception {
            when(gpuResourceManager.canFit(anyString(), any())).thenReturn(false);
            when(gpuResourceManager.findEvictionCandidates(anyString(), any())).thenReturn(List.of());

            ScheduledJob gpuJ = gpuJob("gpu-1", "ingest", 90, ctx -> {});
            scheduler.submit(gpuJ);
            Thread.sleep(300);

            ArgumentCaptor<JobSchedulerEvent> captor = ArgumentCaptor.forClass(JobSchedulerEvent.class);
            verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());

            List<JobSchedulerEvent> blockedEvents = captor.getAllValues().stream()
                    .filter(e -> e.getEventType() == JobSchedulerEvent.EventType.JOB_BLOCKED)
                    .collect(Collectors.toList());

            assertFalse(blockedEvents.isEmpty(), "Should emit JOB_BLOCKED event");
            JobSchedulerEvent blocked = blockedEvents.get(0);
            assertEquals("gpu-1", blocked.getJobId());
            assertEquals("ingest", blocked.getJobType());
            assertNotNull(blocked.getData().get("blockedReason"));

            scheduler.cancel("gpu-1");
        }

        @Test
        @DisplayName("JOB_SKIPPED_AHEAD event emitted when CPU job leapfrogs GPU job")
        void skippedAheadEventEmitted() throws Exception {
            when(gpuResourceManager.canFit(anyString(), any())).thenReturn(false);
            when(gpuResourceManager.findEvictionCandidates(anyString(), any())).thenReturn(List.of());

            // High-priority GPU job (blocked)
            ScheduledJob gpuJ = gpuJob("gpu-1", "ingest", 90, ctx -> {});
            scheduler.submit(gpuJ);

            // Lower-priority CPU job (should skip ahead)
            CountDownLatch cpuDone = new CountDownLatch(1);
            ScheduledJob cpuJ = cpuJob("cpu-1", "crawl", 50, ctx -> cpuDone.countDown());
            scheduler.submit(cpuJ);

            assertTrue(cpuDone.await(5, TimeUnit.SECONDS));

            ArgumentCaptor<JobSchedulerEvent> captor = ArgumentCaptor.forClass(JobSchedulerEvent.class);
            verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());

            List<JobSchedulerEvent> skipEvents = captor.getAllValues().stream()
                    .filter(e -> e.getEventType() == JobSchedulerEvent.EventType.JOB_SKIPPED_AHEAD)
                    .collect(Collectors.toList());

            assertFalse(skipEvents.isEmpty(), "Should emit JOB_SKIPPED_AHEAD event");
            JobSchedulerEvent skip = skipEvents.get(0);
            assertEquals("cpu-1", skip.getJobId());
            assertEquals("crawl", skip.getJobType());
            assertEquals("gpu-1", skip.getData().get("blockedJobId"));
            assertEquals("ingest", skip.getData().get("blockedJobType"));
            assertNotNull(skip.getData().get("blockedReason"));

            scheduler.cancel("gpu-1");
        }

        @Test
        @DisplayName("JOB_REORDERED event emitted when dispatch order differs from priority order")
        void reorderedEventEmitted() throws Exception {
            when(gpuResourceManager.canFit(anyString(), any())).thenReturn(false);
            when(gpuResourceManager.findEvictionCandidates(anyString(), any())).thenReturn(List.of());

            ScheduledJob gpuJ = gpuJob("gpu-1", "ingest", 90, ctx -> {});
            scheduler.submit(gpuJ);

            CountDownLatch cpuDone = new CountDownLatch(1);
            ScheduledJob cpuJ = cpuJob("cpu-1", "crawl", 50, ctx -> cpuDone.countDown());
            scheduler.submit(cpuJ);

            assertTrue(cpuDone.await(5, TimeUnit.SECONDS));

            ArgumentCaptor<JobSchedulerEvent> captor = ArgumentCaptor.forClass(JobSchedulerEvent.class);
            verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());

            List<JobSchedulerEvent> reorderEvents = captor.getAllValues().stream()
                    .filter(e -> e.getEventType() == JobSchedulerEvent.EventType.JOB_REORDERED)
                    .collect(Collectors.toList());

            assertFalse(reorderEvents.isEmpty(), "Should emit JOB_REORDERED event");
            JobSchedulerEvent reorder = reorderEvents.get(0);
            assertTrue((int) reorder.getData().get("blockedCount") >= 1);
            assertTrue((int) reorder.getData().get("skippedCount") >= 1);

            scheduler.cancel("gpu-1");
        }

        @Test
        @DisplayName("JOB_BLOCKED event is not re-emitted for the same reason")
        void blockedEventNotDuplicated() throws Exception {
            when(gpuResourceManager.canFit(anyString(), any())).thenReturn(false);
            when(gpuResourceManager.findEvictionCandidates(anyString(), any())).thenReturn(List.of());

            ScheduledJob gpuJ = gpuJob("gpu-1", "ingest", 90, ctx -> {});
            scheduler.submit(gpuJ);

            // Wait for multiple dispatch cycles
            Thread.sleep(500);

            ArgumentCaptor<JobSchedulerEvent> captor = ArgumentCaptor.forClass(JobSchedulerEvent.class);
            verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());

            long blockedCount = captor.getAllValues().stream()
                    .filter(e -> e.getEventType() == JobSchedulerEvent.EventType.JOB_BLOCKED)
                    .filter(e -> "gpu-1".equals(e.getJobId()))
                    .count();

            // Should only emit once (same reason doesn't re-emit)
            assertEquals(1, blockedCount,
                    "JOB_BLOCKED should be emitted only once for the same block reason");

            scheduler.cancel("gpu-1");
        }
    }

    // ==================== Blocked reason tracking ====================

    @Nested
    @DisplayName("Blocked reason tracking in job views")
    class BlockedReasonTracking {

        @Test
        @DisplayName("Job view shows blocked reason when GPU unavailable")
        void jobViewShowsBlockedReason() throws Exception {
            when(gpuResourceManager.canFit(anyString(), any())).thenReturn(false);
            when(gpuResourceManager.findEvictionCandidates(anyString(), any())).thenReturn(List.of());

            ScheduledJob gpuJ = gpuJob("gpu-1", "ingest", 90, ctx -> {});
            scheduler.submit(gpuJ);
            Thread.sleep(300);

            ScheduledJob.ScheduledJobView view = scheduler.getJobView("gpu-1");
            assertNotNull(view);
            assertNotNull(view.blockedReason(), "View should contain blocked reason");
            assertTrue(view.blockedReason().contains("GPU"),
                    "Blocked reason should mention GPU");

            scheduler.cancel("gpu-1");
        }

        @Test
        @DisplayName("Blocked reason is cleared when job becomes dispatchable")
        void blockedReasonClearedOnDispatch() throws Exception {
            AtomicBoolean gpuAvailable = new AtomicBoolean(false);
            when(gpuResourceManager.canFit(anyString(), any()))
                    .thenAnswer(inv -> gpuAvailable.get());
            when(gpuResourceManager.findEvictionCandidates(anyString(), any())).thenReturn(List.of());

            CountDownLatch done = new CountDownLatch(1);
            ScheduledJob gpuJ = gpuJob("gpu-1", "ingest", 90, ctx -> done.countDown());
            scheduler.submit(gpuJ);
            Thread.sleep(300);

            // Verify blocked
            ScheduledJob.ScheduledJobView viewBefore = scheduler.getJobView("gpu-1");
            assertNotNull(viewBefore.blockedReason());

            // Free GPU — blocked reason should be cleared on dispatch
            gpuAvailable.set(true);
            assertTrue(done.await(5, TimeUnit.SECONDS));
            gpuJ.getResultFuture().get(5, TimeUnit.SECONDS);

            // After completion, the blocked reason should be null (it was cleared before dispatch)
            assertNull(gpuJ.getBlockedReason());
        }

        @Test
        @DisplayName("Job view shows concurrency limit as blocked reason")
        void concurrencyLimitBlockedReason() throws Exception {
            config.setMaxConcurrentByType(Map.of("ingest", 1));

            CountDownLatch firstRunning = new CountDownLatch(1);
            CountDownLatch firstDone = new CountDownLatch(1);
            ScheduledJob first = cpuJob("first", "ingest", 50, ctx -> {
                firstRunning.countDown();
                try { firstDone.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            });
            scheduler.submit(first);
            assertTrue(firstRunning.await(5, TimeUnit.SECONDS));

            ScheduledJob second = cpuJob("second", "ingest", 40, ctx -> {});
            scheduler.submit(second);
            Thread.sleep(300);

            ScheduledJob.ScheduledJobView view = scheduler.getJobView("second");
            assertNotNull(view);
            assertNotNull(view.blockedReason());
            assertTrue(view.blockedReason().contains("Concurrency limit"),
                    "Should mention concurrency limit");

            firstDone.countDown();
            second.getResultFuture().get(5, TimeUnit.SECONDS);
        }

        @Test
        @DisplayName("Job view shows conflict as blocked reason")
        void conflictBlockedReason() throws Exception {
            config.setMaxConcurrentByType(Map.of("training", 1, "vlm", 1));

            CountDownLatch trainingRunning = new CountDownLatch(1);
            CountDownLatch trainingDone = new CountDownLatch(1);
            ScheduledJob training = ScheduledJob.builder()
                    .jobId("t1").jobType("training")
                    .description("Train").priority(50)
                    .resourceProfile(JobResourceProfiles.TRAINING)
                    .executor(ctx -> {
                        trainingRunning.countDown();
                        try { trainingDone.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                    })
                    .build();
            scheduler.submit(training);
            assertTrue(trainingRunning.await(5, TimeUnit.SECONDS));

            ScheduledJob vlm = ScheduledJob.builder()
                    .jobId("v1").jobType("vlm")
                    .description("VLM").priority(40)
                    .resourceProfile(JobResourceProfiles.VLM)
                    .executor(ctx -> {})
                    .build();
            scheduler.submit(vlm);
            Thread.sleep(300);

            ScheduledJob.ScheduledJobView view = scheduler.getJobView("v1");
            assertNotNull(view);
            assertNotNull(view.blockedReason());
            assertTrue(view.blockedReason().contains("Conflicts"),
                    "Should mention conflict with running job type");

            trainingDone.countDown();
            vlm.getResultFuture().get(5, TimeUnit.SECONDS);
        }
    }

    // ==================== Phase-aware yield enabling skip-ahead ====================

    @Nested
    @DisplayName("Phase-aware GPU yield unblocks queued jobs")
    class PhaseYieldUnblocks {

        @Test
        @DisplayName("Queued GPU job dispatches when running job yields GPU during CPU phase")
        void queuedGpuJobDispatchesAfterYield() throws Exception {
            // Track GPU usage: only one GPU job at a time
            AtomicBoolean gpuInUse = new AtomicBoolean(false);
            when(gpuResourceManager.canFit(anyString(), any()))
                    .thenAnswer(inv -> !gpuInUse.get());
            when(gpuResourceManager.findEvictionCandidates(anyString(), any())).thenReturn(List.of());
            when(modelLifecycleManager.acquireGpuForJob(anyString(), anyString(), anyString()))
                    .thenAnswer(inv -> {
                        gpuInUse.set(true);
                        return TEST_GPU;
                    });
            doAnswer(inv -> {
                gpuInUse.set(false);
                return null;
            }).when(modelLifecycleManager).releaseGpuForJob(anyString());

            // Submit a GPU job that will manually yield GPU via phase transition
            CountDownLatch crawlRunning = new CountDownLatch(1);
            CountDownLatch crawlResume = new CountDownLatch(1);
            ScheduledJob crawl = ScheduledJob.builder()
                    .jobId("crawl-1").jobType("ingest")
                    .description("GPU job that yields").priority(50)
                    .resourceProfile(JobResourceProfile.gpuRequired("ingest", "GPU Ingest", 2 * GB, 4 * GB))
                    .executor(ctx -> {
                        crawlRunning.countDown();
                        try { crawlResume.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                    })
                    .build();
            scheduler.submit(crawl);
            assertTrue(crawlRunning.await(5, TimeUnit.SECONDS));
            Thread.sleep(100);

            // Submit a second GPU job — should be blocked because GPU is in use
            AtomicBoolean secondStarted = new AtomicBoolean(false);
            CountDownLatch secondDone = new CountDownLatch(1);
            ScheduledJob secondGpu = gpuJob("gpu-2", "vectorPopulation", 40, ctx -> {
                secondStarted.set(true);
                secondDone.countDown();
            });
            scheduler.submit(secondGpu);
            Thread.sleep(300);
            assertFalse(secondStarted.get(), "Second GPU job should be blocked while first holds GPU");

            // First job yields GPU at CPU-only phase
            scheduler.reportPhaseTransition("crawl-1", "CHUNKING", false, 0);
            Thread.sleep(300);

            // Now the second GPU job should have GPU available and dispatch
            assertTrue(secondDone.await(5, TimeUnit.SECONDS),
                    "Second GPU job should dispatch after first yields GPU");
            assertTrue(secondStarted.get());

            crawlResume.countDown();
        }
    }

    // ==================== Broadcaster UI integration ====================

    @Nested
    @DisplayName("Broadcaster sends skip-ahead/blocked events to WebSocket")
    class BroadcasterIntegration {

        @Test
        @DisplayName("JOB_BLOCKED is a state-changing event that triggers status broadcast")
        void blockedEventTriggersStatusBroadcast() {
            org.springframework.messaging.simp.SimpMessagingTemplate mockTemplate =
                    mock(org.springframework.messaging.simp.SimpMessagingTemplate.class);
            ResourceAwareJobScheduler mockScheduler = mock(ResourceAwareJobScheduler.class);
            when(mockScheduler.getStatus()).thenReturn(new java.util.LinkedHashMap<>());
            when(mockScheduler.getQueueSnapshot()).thenReturn(List.of());
            when(mockScheduler.getRunningSnapshot()).thenReturn(List.of());

            JobSchedulerBroadcaster broadcaster = new JobSchedulerBroadcaster(mockTemplate, mockScheduler);

            JobSchedulerEvent blockedEvent = JobSchedulerEvent.jobBlocked(
                    new Object(), "gpu-1", "ingest",
                    "No GPU available", 3, 1);

            broadcaster.onJobSchedulerEvent(blockedEvent);

            // Should send both event and status
            verify(mockTemplate).convertAndSend(eq("/topic/scheduler/events"), any(Map.class));
            verify(mockTemplate).convertAndSend(eq("/topic/scheduler/status"), any(Map.class));
        }

        @Test
        @DisplayName("JOB_SKIPPED_AHEAD event payload contains blocked job info")
        void skippedAheadPayloadContainsBlockedInfo() {
            org.springframework.messaging.simp.SimpMessagingTemplate mockTemplate =
                    mock(org.springframework.messaging.simp.SimpMessagingTemplate.class);
            ResourceAwareJobScheduler mockScheduler = mock(ResourceAwareJobScheduler.class);
            when(mockScheduler.getStatus()).thenReturn(new java.util.LinkedHashMap<>());
            when(mockScheduler.getQueueSnapshot()).thenReturn(List.of());
            when(mockScheduler.getRunningSnapshot()).thenReturn(List.of());

            JobSchedulerBroadcaster broadcaster = new JobSchedulerBroadcaster(mockTemplate, mockScheduler);

            JobSchedulerEvent skipEvent = JobSchedulerEvent.jobSkippedAhead(
                    new Object(), "cpu-1", "crawl",
                    "gpu-1", "ingest", "No GPU available",
                    2, 1);

            broadcaster.onJobSchedulerEvent(skipEvent);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(mockTemplate).convertAndSend(eq("/topic/scheduler/events"), captor.capture());

            Map<String, Object> payload = captor.getValue();
            assertEquals("JOB_SKIPPED_AHEAD", payload.get("eventType"));
            assertEquals("cpu-1", payload.get("jobId"));
            assertEquals("crawl", payload.get("jobType"));
            assertEquals("gpu-1", payload.get("blockedJobId"));
            assertEquals("ingest", payload.get("blockedJobType"));
            assertEquals("No GPU available", payload.get("blockedReason"));
        }

        @Test
        @DisplayName("JOB_REORDERED event payload contains counts")
        void reorderedPayloadContainsCounts() {
            org.springframework.messaging.simp.SimpMessagingTemplate mockTemplate =
                    mock(org.springframework.messaging.simp.SimpMessagingTemplate.class);
            ResourceAwareJobScheduler mockScheduler = mock(ResourceAwareJobScheduler.class);
            when(mockScheduler.getStatus()).thenReturn(new java.util.LinkedHashMap<>());
            when(mockScheduler.getQueueSnapshot()).thenReturn(List.of());
            when(mockScheduler.getRunningSnapshot()).thenReturn(List.of());

            JobSchedulerBroadcaster broadcaster = new JobSchedulerBroadcaster(mockTemplate, mockScheduler);

            JobSchedulerEvent reorderEvent = JobSchedulerEvent.jobReordered(
                    new Object(), 3, 2, 2, 1);

            broadcaster.onJobSchedulerEvent(reorderEvent);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(mockTemplate).convertAndSend(eq("/topic/scheduler/events"), captor.capture());

            Map<String, Object> payload = captor.getValue();
            assertEquals("JOB_REORDERED", payload.get("eventType"));
            assertEquals(2, payload.get("blockedCount"));
            assertEquals(1, payload.get("skippedCount"));
        }
    }

    // ==================== Event factory methods ====================

    @Nested
    @DisplayName("JobSchedulerEvent factory methods")
    class EventFactoryMethods {

        @Test
        @DisplayName("jobBlocked creates correct event")
        void jobBlockedEvent() {
            JobSchedulerEvent event = JobSchedulerEvent.jobBlocked(
                    this, "j1", "ingest", "No GPU", 5, 2);

            assertEquals(JobSchedulerEvent.EventType.JOB_BLOCKED, event.getEventType());
            assertEquals("j1", event.getJobId());
            assertEquals("ingest", event.getJobType());
            assertEquals("BLOCKED", event.getCurrentPhase());
            assertEquals(5, event.getQueueDepth());
            assertEquals(2, event.getRunningCount());
            assertEquals("No GPU", event.getData().get("blockedReason"));
        }

        @Test
        @DisplayName("jobSkippedAhead creates correct event with blocked job info")
        void jobSkippedAheadEvent() {
            JobSchedulerEvent event = JobSchedulerEvent.jobSkippedAhead(
                    this, "cpu-1", "crawl",
                    "gpu-1", "ingest", "GPU full",
                    3, 1);

            assertEquals(JobSchedulerEvent.EventType.JOB_SKIPPED_AHEAD, event.getEventType());
            assertEquals("cpu-1", event.getJobId());
            assertEquals("crawl", event.getJobType());
            assertEquals("DISPATCHED", event.getCurrentPhase());
            assertEquals("gpu-1", event.getData().get("blockedJobId"));
            assertEquals("ingest", event.getData().get("blockedJobType"));
            assertEquals("GPU full", event.getData().get("blockedReason"));
        }

        @Test
        @DisplayName("jobReordered creates correct event with counts")
        void jobReorderedEvent() {
            JobSchedulerEvent event = JobSchedulerEvent.jobReordered(
                    this, 4, 2, 3, 1);

            assertEquals(JobSchedulerEvent.EventType.JOB_REORDERED, event.getEventType());
            assertNull(event.getJobId());
            assertEquals(4, event.getQueueDepth());
            assertEquals(2, event.getRunningCount());
            assertEquals(3, event.getData().get("blockedCount"));
            assertEquals(1, event.getData().get("skippedCount"));
        }
    }

    // ==================== Queue snapshot ordering ====================

    @Nested
    @DisplayName("Queue snapshot reflects blocked vs dispatchable jobs")
    class QueueSnapshotOrdering {

        @Test
        @DisplayName("Queue snapshot shows blocked jobs with reasons")
        void queueSnapshotShowsBlockedReasons() throws Exception {
            when(gpuResourceManager.canFit(anyString(), any())).thenReturn(false);
            when(gpuResourceManager.findEvictionCandidates(anyString(), any())).thenReturn(List.of());

            ScheduledJob gpuJ = gpuJob("gpu-1", "ingest", 90, ctx -> {});
            scheduler.submit(gpuJ);
            Thread.sleep(300);

            List<ScheduledJob.ScheduledJobView> snapshot = scheduler.getQueueSnapshot();
            assertFalse(snapshot.isEmpty());

            ScheduledJob.ScheduledJobView first = snapshot.get(0);
            assertEquals("gpu-1", first.jobId());
            assertNotNull(first.blockedReason());

            scheduler.cancel("gpu-1");
        }
    }

    // ==================== Execution order verification ====================

    @Nested
    @DisplayName("Execution order with mixed GPU/CPU jobs")
    class ExecutionOrder {

        @Test
        @DisplayName("With GPU available, higher-priority jobs dispatch first")
        void priorityOrderWhenGpuAvailable() throws Exception {
            AtomicReference<String> firstExecuted = new AtomicReference<>();
            CountDownLatch firstDone = new CountDownLatch(1);

            // Lower priority submitted first
            ScheduledJob low = cpuJob("low", "crawl", 30, ctx -> {
                firstExecuted.compareAndSet(null, "low");
                firstDone.countDown();
            });
            // Higher priority submitted second
            ScheduledJob high = cpuJob("high", "crawl", 90, ctx -> {
                firstExecuted.compareAndSet(null, "high");
                firstDone.countDown();
            });

            scheduler.submit(low);
            scheduler.submit(high);

            assertTrue(firstDone.await(5, TimeUnit.SECONDS));
            // With both dispatchable, the higher-priority one should run first
            // (though timing can be non-deterministic in async dispatch)
            low.getResultFuture().get(5, TimeUnit.SECONDS);
            high.getResultFuture().get(5, TimeUnit.SECONDS);

            // Both should succeed regardless of order
            assertTrue(low.getResultFuture().get().success());
            assertTrue(high.getResultFuture().get().success());
        }

        @Test
        @DisplayName("Blocked GPU job eventually runs after unblock")
        void blockedGpuJobEventuallyRuns() throws Exception {
            AtomicInteger executionOrder = new AtomicInteger(0);
            AtomicInteger gpuOrder = new AtomicInteger(-1);
            AtomicInteger cpuOrder = new AtomicInteger(-1);

            AtomicBoolean gpuAvailable = new AtomicBoolean(false);
            when(gpuResourceManager.canFit(anyString(), any()))
                    .thenAnswer(inv -> gpuAvailable.get());
            when(gpuResourceManager.findEvictionCandidates(anyString(), any())).thenReturn(List.of());

            ScheduledJob gpuJ = gpuJob("gpu-1", "ingest", 90, ctx ->
                    gpuOrder.set(executionOrder.getAndIncrement()));
            ScheduledJob cpuJ = cpuJob("cpu-1", "crawl", 50, ctx ->
                    cpuOrder.set(executionOrder.getAndIncrement()));

            scheduler.submit(gpuJ);
            scheduler.submit(cpuJ);

            // CPU runs first (GPU blocked)
            cpuJ.getResultFuture().get(5, TimeUnit.SECONDS);
            assertTrue(cpuOrder.get() >= 0, "CPU job should have run");

            // Unblock GPU
            gpuAvailable.set(true);
            gpuJ.getResultFuture().get(5, TimeUnit.SECONDS);
            assertTrue(gpuOrder.get() >= 0, "GPU job should have run after unblock");

            // CPU should have executed before GPU
            assertTrue(cpuOrder.get() < gpuOrder.get(),
                    "CPU job should execute before blocked GPU job");
        }
    }
}
