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
import ai.kompile.app.services.GpuResourceManager;
import ai.kompile.app.services.GpuResourceManager.GpuReservation;
import ai.kompile.app.services.ModelLifecycleManager;
import ai.kompile.app.services.scheduler.JobSchedulerEvent.EventType;
import ai.kompile.app.services.scheduler.ScheduledJob.JobResult;
import ai.kompile.app.subprocess.SubprocessPlacement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResourceAwareJobScheduler} against the real GPU ledger ({@link GpuResourceManager} and
 * {@link ModelLifecycleManager}) on a 24 GB + 8 GB host: a job reserves and is bounded to what its child
 * may use, waits for room instead of failing, never leaks its hold, keeps a row on its own device through
 * its GPU phases, and a cancel is reported once, as a cancel. Backend-agnostic: devices are registered by hand.
 */
class SchedulerLedgerRegressionTest {

    private static final long GB = 1L << 30;

    // nvidia-smi and runtime indices differ on purpose, so using one for the other shows
    private static final GpuDevice RTX_4090 = GpuDevice.local(0, 1, "RTX 4090", 24 * GB);
    private static final GpuDevice RTX_3070_TI = GpuDevice.local(1, 0, "RTX 3070 Ti", 8 * GB);

    @TempDir
    Path tempDir;

    private final List<Consumer<JobSchedulerEvent>> hooks = new CopyOnWriteArrayList<>();
    private GpuResourceManager grm;
    private ModelLifecycleManager mlm;
    private ResourceSchedulerConfigService configService;
    private ResourceAwareJobScheduler scheduler;

    @BeforeEach
    void setUp() {
        grm = new GpuResourceManager();
        grm.initForTesting();
        grm.registerDevice(RTX_4090);
        grm.registerDevice(RTX_3070_TI);
        configService = new ResourceSchedulerConfigService(tempDir.toString());
        configService.getConfiguration().setDispatchIntervalMs(50);
        configService.getConfiguration().setPhaseAwareYieldEnabled(true);
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.stop();
        }
        if (mlm != null) {
            mlm.stop();
        }
    }

    // ==================== G1: a job reserves what its child may use ====================

    @Test
    void jobReservesItsProfilePeakNotTheBudget() throws Exception {
        startScheduler();
        AtomicReference<GpuReservation> rowWhileRunning = new AtomicReference<>();
        AtomicReference<SubprocessPlacement> placement = new AtomicReference<>();

        CompletableFuture<JobResult> future = scheduler.submit(gpuJob("train-peak", "training", 18 * GB, ctx -> {
            rowWhileRunning.set(grm.getReservation(ctx.jobId()).orElse(null));
            placement.set(ctx.placement());
        }));

        assertTrue(future.get(5, TimeUnit.SECONDS).success());
        GpuReservation row = rowWhileRunning.get();
        assertNotNull(row, "the job ran with no row");
        assertEquals(RTX_4090, row.device());
        assertEquals(18 * GB, row.reservedBytes(), "the child may use its 18 GB peak, not the training budget");
        assertEquals(RTX_4090.cudaRuntimeIndex(), placement.get().deviceId());
        assertEquals(18 * GB, placement.get().maxDeviceMemoryBytes(), "the child is bounded to what the job reserved");
        assertFalse(grm.hasReservation("train-peak"));
    }

    @Test
    void bigJobWaitsForTheDeviceThatHoldsIt() throws Exception {
        startScheduler();
        // 7 GB left on the 4090, 8 GB free on the 3070 — which can never hold a 9 GB child
        squat("squatter", RTX_4090, 17 * GB);
        CountDownLatch blocked = onEvent(EventType.JOB_BLOCKED, "ingest-big");
        AtomicReference<GpuReservation> rowWhileRunning = new AtomicReference<>();

        CompletableFuture<JobResult> future = scheduler.submit(gpuJob("ingest-big", "ingest", 9 * GB,
                ctx -> rowWhileRunning.set(grm.getReservation(ctx.jobId()).orElse(null))));

        assertTrue(blocked.await(5, TimeUnit.SECONDS), "the job was dispatched to a device that can't hold it");
        ScheduledJob.ScheduledJobView view = scheduler.getJobView("ingest-big");
        assertEquals("QUEUED", view.state());
        assertTrue(view.blockedReason().contains("RTX 4090"), view.blockedReason());
        assertFalse(future.isDone());
        assertEquals(0, grm.getReservedMemory(RTX_3070_TI));

        grm.release("squatter");

        assertTrue(future.get(5, TimeUnit.SECONDS).success());
        assertEquals(RTX_4090, rowWhileRunning.get().device());
        assertEquals(9 * GB, rowWhileRunning.get().reservedBytes());
    }

    @Test
    void acquireThatLosesTheRaceRequeues() throws Exception {
        startScheduler();
        AtomicBoolean stolen = new AtomicBoolean();
        CountDownLatch requeued = new CountDownLatch(1);
        hooks.add(event -> {
            if (!"train-race".equals(event.getJobId())) {
                return;
            }
            if (event.getEventType() == EventType.JOB_DISPATCHED && stolen.compareAndSet(false, true)) {
                // Admitted by the gate; the room goes elsewhere before its acquire
                squat("thief", RTX_4090, 10 * GB);
            } else if (event.getEventType() == EventType.JOB_BLOCKED) {
                requeued.countDown();
            }
        });
        AtomicInteger runs = new AtomicInteger();

        CompletableFuture<JobResult> future = scheduler.submit(gpuJob("train-race", "training", 20 * GB,
                ctx -> runs.incrementAndGet()));

        assertTrue(requeued.await(5, TimeUnit.SECONDS), "the job didn't go back to wait for room");
        assertEquals(0, runs.get());
        assertFalse(future.isDone(), "losing the race must not fail the job");

        grm.release("thief");

        assertTrue(future.get(5, TimeUnit.SECONDS).success());
        assertEquals(1, runs.get());
        assertEquals(0L, count("totalFailed"));
    }

    // ==================== G3: an Error must not leak the hold ====================

    @Test
    void executorErrorReleasesHoldAndFailsOnce() throws Exception {
        startScheduler();

        CompletableFuture<JobResult> future = scheduler.submit(gpuJob("ingest-crash", "ingest", 2 * GB, ctx -> {
            throw new Error("simulated native crash");
        }));

        JobResult result = future.get(5, TimeUnit.SECONDS);
        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("simulated native crash"), result.errorMessage());
        assertFalse(mlm.hasJobGpuHold("ingest-crash"));
        assertFalse(grm.hasReservation("ingest-crash"));
        assertTrue(scheduler.getRunningSnapshot().isEmpty());
        assertEquals("FAILED", scheduler.getJobView("ingest-crash").state());
        assertEquals(1L, count("totalFailed"));
        assertEquals(0L, count("totalCompleted"));
    }

    @Test
    void bypassModeErrorFailsTheJobInsteadOfEscaping() {
        configService.getConfiguration().setEnabled(false);
        startScheduler();

        CompletableFuture<JobResult> future = assertDoesNotThrow(() -> scheduler.submit(
                gpuJob("ingest-bypass", "ingest", 2 * GB, ctx -> {
                    throw new Error("simulated native crash");
                })));

        assertTrue(future.isDone());
        JobResult result = future.join();
        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("simulated native crash"), result.errorMessage());
        assertEquals("FAILED", scheduler.getJobView("ingest-bypass").state());
        assertTrue(scheduler.getRunningSnapshot().isEmpty());
        assertEquals(1L, count("totalFailed"));
    }

    // ==================== G4: GPU phases keep a row on the job's own device ====================

    @Test
    void phaseReacquireStaysOnPlacementDeviceWithPhaseBytes() throws Exception {
        startScheduler();
        AtomicReference<GpuReservation> embedRow = new AtomicReference<>();

        CompletableFuture<JobResult> future = scheduler.submit(gpuJob("ingest-phases", "ingest", 6 * GB, ctx -> {
            ctx.phaseCallback().onPhaseTransition(ctx.jobId(), "PARSE", false, 0);
            // While the job parses on the CPU the 4090 fills up, so the 3070 now has more room
            squat("squatter", RTX_4090, 17 * GB);
            ctx.phaseCallback().onPhaseTransition(ctx.jobId(), "EMBED", true, 4 * GB);
            embedRow.set(grm.getReservation(ctx.jobId()).orElse(null));
        }));

        assertTrue(future.get(5, TimeUnit.SECONDS).success());
        GpuReservation row = embedRow.get();
        assertNotNull(row, "the GPU phase ran with no row");
        assertEquals(RTX_4090, row.device(), "the child runs on the 4090, so its phase uses memory there");
        assertEquals(4 * GB, row.reservedBytes());
    }

    @Test
    void phaseReacquireThatDoesNotFitRecordsOverCommitRowOnSameDevice() throws Exception {
        startScheduler();
        squat("squatter-3070", RTX_3070_TI, 7 * GB);
        AtomicReference<GpuReservation> embedRow = new AtomicReference<>();
        AtomicLong availableDuringEmbed = new AtomicLong();
        AtomicBoolean heldDuringEmbed = new AtomicBoolean();

        CompletableFuture<JobResult> future = scheduler.submit(gpuJob("ingest-over", "ingest", 6 * GB, ctx -> {
            ctx.phaseCallback().onPhaseTransition(ctx.jobId(), "PARSE", false, 0);
            squat("squatter-4090", RTX_4090, 23 * GB);
            ctx.phaseCallback().onPhaseTransition(ctx.jobId(), "EMBED", true, 4 * GB);
            embedRow.set(grm.getReservation(ctx.jobId()).orElse(null));
            availableDuringEmbed.set(grm.getAvailableMemory(RTX_4090));
            heldDuringEmbed.set(mlm.hasJobGpuHold(ctx.jobId()));
        }));

        assertTrue(future.get(5, TimeUnit.SECONDS).success());
        GpuReservation row = embedRow.get();
        assertNotNull(row, "the GPU phase ran with no row, so its memory counted as free");
        assertEquals(RTX_4090, row.device());
        assertEquals(4 * GB, row.reservedBytes());
        assertEquals(-3 * GB, availableDuringEmbed.get());
        assertTrue(heldDuringEmbed.get());
        assertFalse(grm.hasReservation("ingest-over"), "the over-committed row goes with the job");
    }

    // ==================== G8: a cancel wins ====================

    @Test
    void cancelRacingNormalReturnIsReportedOnceAsCancelled() throws Exception {
        AtomicReference<CompletableFuture<JobResult>> futureRef = new AtomicReference<>();
        CountDownLatch cancelInRelease = new CountDownLatch(1);
        startScheduler(new ModelLifecycleManager(grm, null, null, 1, 1, 3600) {
            @Override
            public void releaseGpuForJob(String jobId) {
                if ("test-canceller".equals(Thread.currentThread().getName())) {
                    // The cancel is under way: the executor returns now, and its completion gets every
                    // chance to report before the cancel finishes
                    cancelInRelease.countDown();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                    while (!futureRef.get().isDone() && System.nanoTime() < deadline) {
                        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
                    }
                }
                super.releaseGpuForJob(jobId);
            }
        });
        CountDownLatch started = new CountDownLatch(1);

        CompletableFuture<JobResult> future = scheduler.submit(gpuJob("ingest-cancel", "ingest", 2 * GB, ctx -> {
            started.countDown();
            // Returns normally, like an executor that ignores interrupts
            awaitUninterruptibly(cancelInRelease, 5_000);
        }));
        futureRef.set(future);
        assertTrue(started.await(5, TimeUnit.SECONDS));

        Thread canceller = new Thread(() -> scheduler.cancel("ingest-cancel"), "test-canceller");
        canceller.start();
        canceller.join(10_000);

        JobResult result = future.get(5, TimeUnit.SECONDS);
        assertFalse(result.success(), "a cancelled job was reported as a success");
        assertEquals("CANCELLED", scheduler.getJobView("ingest-cancel").state());
        assertEquals(0L, count("totalCompleted"));
        assertEquals(1L, count("totalCancelled"));
        assertEquals(0L, count("totalFailed"));
        assertFalse(mlm.hasJobGpuHold("ingest-cancel"));
        assertFalse(grm.hasReservation("ingest-cancel"));
    }

    // ==================== Helpers ====================

    private void startScheduler() {
        startScheduler(new ModelLifecycleManager(grm, null, null, 1, 1, 3600));
    }

    private void startScheduler(ModelLifecycleManager lifecycle) {
        mlm = lifecycle;
        mlm.start();
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof JobSchedulerEvent schedulerEvent) {
                hooks.forEach(hook -> hook.accept(schedulerEvent));
            }
        };
        scheduler = new ResourceAwareJobScheduler(grm, mlm, configService, publisher, List.of(), null);
        scheduler.start();
    }

    private static ScheduledJob gpuJob(String jobId, String serviceType, long peakGpuBytes,
                                       ScheduledJob.JobExecutor executor) {
        return ScheduledJob.builder()
                .jobId(jobId)
                .jobType(serviceType)
                .description(jobId)
                .resourceProfile(JobResourceProfile.gpuRequired(serviceType, serviceType, peakGpuBytes, GB))
                .executor(executor)
                .build();
    }

    /** A row no eviction may free: a job's, at vlm priority. */
    private void squat(String id, GpuDevice device, long bytes) {
        grm.reserveWithId(id, "vlm", device, bytes);
    }

    private CountDownLatch onEvent(EventType type, String jobId) {
        CountDownLatch latch = new CountDownLatch(1);
        hooks.add(event -> {
            if (event.getEventType() == type && jobId.equals(event.getJobId())) {
                latch.countDown();
            }
        });
        return latch;
    }

    private long count(String key) {
        return ((Number) scheduler.getStatus().get(key)).longValue();
    }

    private static void awaitUninterruptibly(CountDownLatch latch, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    latch.await(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                    return;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
