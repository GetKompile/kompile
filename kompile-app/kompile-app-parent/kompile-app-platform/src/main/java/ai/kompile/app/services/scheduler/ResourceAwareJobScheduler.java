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
import ai.kompile.app.subprocess.SubprocessPlacement;
import ai.kompile.app.services.ModelLifecycleManager;
import ai.kompile.app.services.ResourceGovernor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Resource-aware job scheduler that coordinates GPU-hungry jobs across all service types.
 *
 * <p>Sits between callers (controllers, services) and subprocess launchers. Accepts job
 * submissions, queues when resources are unavailable, batches compatible jobs, and
 * orchestrates GPU acquire/release through the existing {@link ModelLifecycleManager}
 * and {@link GpuResourceManager}.</p>
 *
 * <p>Key features:
 * <ul>
 *   <li><b>Priority queue</b>: higher-priority jobs dispatch first</li>
 *   <li><b>Phase-aware GPU yield</b>: multi-phase jobs (crawl) release GPU during
 *       CPU-only phases so other jobs can use it</li>
 *   <li><b>Concurrency limits</b>: per-type max concurrent (e.g., 4 ingest, 1 training)</li>
 *   <li><b>Conflict detection</b>: jobs declaring conflicts with other types are serialized</li>
 *   <li><b>Job history</b>: completed/failed jobs recorded for monitoring</li>
 * </ul>
 */
@Service
public class ResourceAwareJobScheduler implements SmartLifecycle {

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected ResourceAwareJobScheduler() {}


    private static final Logger log = LoggerFactory.getLogger(ResourceAwareJobScheduler.class);

    /**
     * SmartLifecycle phase — starts before ModelLifecycleManager so the scheduler
     * is ready to accept jobs when other services come online.
     */
    private static final int LIFECYCLE_PHASE = Integer.MAX_VALUE - 200;

    @Autowired
    private GpuResourceManager gpuResourceManager;
    @Autowired
    private ModelLifecycleManager modelLifecycleManager;
    @Autowired
    private ResourceSchedulerConfigService configService;
    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;
    @Autowired(required = false)
    private List<ExternalJobSchedulerDelegate> externalDelegates;
    @Autowired(required = false)
    private JobSchedulerHistoryService historyService;

    /** Resource governor — adds CPU/RAM admission gating and dynamic pool sizing. May be absent. */
    @Autowired(required = false)
    private ResourceGovernor governor;

    // --- Internal state ---
    private final PriorityBlockingQueue<ScheduledJob> queue = new PriorityBlockingQueue<>();
    private final ConcurrentHashMap<String, ScheduledJob> runningJobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Future<?>> executionFutures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledJob> allJobs = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong totalSubmitted = new AtomicLong(0);
    private final AtomicLong totalCompleted = new AtomicLong(0);
    private final AtomicLong totalFailed = new AtomicLong(0);
    private final AtomicLong totalCancelled = new AtomicLong(0);

    private ScheduledExecutorService dispatchExecutor;
    private ExecutorService jobExecutionPool;

    @Autowired
    public ResourceAwareJobScheduler(
            GpuResourceManager gpuResourceManager,
            ModelLifecycleManager modelLifecycleManager,
            ResourceSchedulerConfigService configService,
            @Autowired(required = false) ApplicationEventPublisher eventPublisher,
            @Autowired(required = false) List<ExternalJobSchedulerDelegate> externalDelegates,
            @Autowired(required = false) JobSchedulerHistoryService historyService) {
        this.gpuResourceManager = gpuResourceManager;
        this.modelLifecycleManager = modelLifecycleManager;
        this.configService = configService;
        this.eventPublisher = eventPublisher;
        this.externalDelegates = externalDelegates != null ? externalDelegates : List.of();
        this.historyService = historyService;
        log.info("ResourceAwareJobScheduler initialized (external delegates: {})",
                this.externalDelegates.stream()
                        .map(ExternalJobSchedulerDelegate::getMode)
                        .collect(Collectors.joining(", ")));
    }

    /**
     * Get the active external scheduler delegate, if configured and available.
     */
    private Optional<ExternalJobSchedulerDelegate> getActiveExternalDelegate() {
        String mode = configService.getConfiguration().getExternalSchedulerMode();
        if (mode == null || mode.isBlank() || "none".equalsIgnoreCase(mode)) {
            return Optional.empty();
        }
        return externalDelegates.stream()
                .filter(d -> mode.equalsIgnoreCase(d.getMode()))
                .filter(ExternalJobSchedulerDelegate::isAvailable)
                .findFirst();
    }

    /**
     * Handle a callback from an external scheduler reporting job completion.
     * Called by the REST controller's callback endpoint.
     */
    public void handleExternalCallback(String jobId, boolean success, String message) {
        ScheduledJob job = allJobs.get(jobId);
        if (job == null) {
            log.warn("External callback for unknown job '{}', ignoring", jobId);
            return;
        }

        log.info("External callback received: jobId='{}', success={}, message='{}'",
                jobId, success, message);

        Instant start = job.getStartedAt() != null ? job.getStartedAt() : job.getQueuedAt();
        completeJob(job, success, success ? null : message, start);
    }

    // ==================== SmartLifecycle ====================

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            ResourceSchedulerConfig config = configService.getConfiguration();

            dispatchExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "scheduler-dispatch");
                t.setDaemon(true);
                return t;
            });

            // Pool size derived from CPU count + current load (was hardcoded 4/16).
            int poolSize = governor != null
                    ? governor.concurrencyForSchedulerPool()
                    : Math.max(4, Runtime.getRuntime().availableProcessors() / 2);
            jobExecutionPool = new ThreadPoolExecutor(
                    poolSize, poolSize, 60L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(100),
                    r -> {
                        Thread t = new Thread(r, "scheduler-exec-" + Thread.currentThread().getId());
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.CallerRunsPolicy()
            );
            log.info("Scheduler job execution pool sized to {} thread(s) (governor={})",
                    poolSize, governor != null);

            dispatchExecutor.scheduleWithFixedDelay(
                    this::dispatchLoop,
                    config.getDispatchIntervalMs(),
                    config.getDispatchIntervalMs(),
                    TimeUnit.MILLISECONDS
            );

            log.info("=== ResourceAwareJobScheduler STARTED (phase={}, dispatch={}ms, algorithm={}) ===",
                    getPhase(), config.getDispatchIntervalMs(), config.getSchedulingAlgorithm());
            publishEvent(JobSchedulerEvent.schedulerStarted(this));
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("=== ResourceAwareJobScheduler STOPPING ===");

            if (dispatchExecutor != null) {
                dispatchExecutor.shutdownNow();
            }

            // Cancel all queued jobs
            ScheduledJob job;
            while ((job = queue.poll()) != null) {
                cancelJob(job, "Scheduler shutdown");
            }

            // Wait for running jobs
            if (jobExecutionPool != null) {
                jobExecutionPool.shutdown();
                try {
                    if (!jobExecutionPool.awaitTermination(5, TimeUnit.SECONDS)) {
                        jobExecutionPool.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    jobExecutionPool.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }

            log.info("=== ResourceAwareJobScheduler STOPPED (submitted={}, completed={}, " +
                            "failed={}, cancelled={}) ===",
                    totalSubmitted.get(), totalCompleted.get(),
                    totalFailed.get(), totalCancelled.get());
            publishEvent(JobSchedulerEvent.schedulerStopped(this));
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return LIFECYCLE_PHASE;
    }

    // ==================== Public API ====================

    /**
     * Submit a job to the scheduler. Returns immediately; the caller receives
     * a {@link CompletableFuture} that resolves when the job completes or fails.
     *
     * <p>If the scheduler is disabled, the job executes immediately inline.</p>
     */
    public CompletableFuture<ScheduledJob.JobResult> submit(ScheduledJob job) {
        ResourceSchedulerConfig config = configService.getConfiguration();

        // Pass-through when disabled
        if (!config.isEnabled()) {
            log.debug("Scheduler disabled — executing job '{}' ({}) immediately", job.getJobId(), job.getJobType());
            return executeImmediate(job);
        }

        // Check queue capacity
        if (queue.size() >= config.getGlobalQueueDepth()) {
            log.warn("Scheduler queue full ({}/{}), rejecting job '{}' ({})",
                    queue.size(), config.getGlobalQueueDepth(), job.getJobId(), job.getJobType());
            publishEvent(JobSchedulerEvent.queueFull(this, job.getJobId(), job.getJobType(),
                    job.getDescription(), queue.size(), runningJobs.size()));
            job.getResultFuture().complete(new ScheduledJob.JobResult(
                    false, "Scheduler queue full", 0));
            return job.getResultFuture();
        }

        allJobs.put(job.getJobId(), job);
        queue.offer(job);
        totalSubmitted.incrementAndGet();

        log.info("Job QUEUED: id='{}', type='{}', desc='{}', priority={}, " +
                        "gpu={}, queueDepth={}, runningCount={}",
                job.getJobId(), job.getJobType(), job.getDescription(),
                job.getPriority(), job.getResourceProfile().requiresGpu(),
                queue.size(), runningJobs.size());

        Map<String, Object> queuedData = new LinkedHashMap<>();
        queuedData.put("priority", job.getPriority());
        queuedData.put("requiresGpu", job.getResourceProfile().requiresGpu());
        queuedData.put("peakGpuMb", job.getResourceProfile().peakGpuMemoryBytes() / (1024L * 1024L));
        if (job.getDescription() != null) {
            queuedData.put("description", job.getDescription());
        }
        publishEvent(JobSchedulerEvent.jobQueued(this, job.getJobId(), job.getJobType(),
                queue.size(), runningJobs.size(), queuedData));

        // Nudge the dispatch loop
        triggerDispatch();

        return job.getResultFuture();
    }

    /**
     * Cancel a queued or running job.
     */
    public boolean cancel(String jobId) {
        ScheduledJob job = allJobs.get(jobId);
        if (job == null) {
            log.debug("Cancel request for unknown job '{}'", jobId);
            return false;
        }

        if (job.isTerminal()) {
            log.debug("Job '{}' already in terminal state: {}", jobId, job.getState());
            return false;
        }

        return cancelJob(job, "User cancelled");
    }

    /**
     * Promote a queued job by raising its priority.
     */
    public void promote(String jobId, int newPriority) {
        ScheduledJob job = allJobs.get(jobId);
        if (job == null || job.getState() != ScheduledJob.JobState.QUEUED) {
            log.debug("Cannot promote job '{}': not found or not queued", jobId);
            return;
        }

        int oldPriority = job.getPriority();
        job.setPriority(newPriority);

        // Re-insert to fix priority ordering
        if (queue.remove(job)) {
            queue.offer(job);
        }

        log.info("Job PROMOTED: id='{}', type='{}', priority {} → {}",
                jobId, job.getJobType(), oldPriority, newPriority);
        publishEvent(JobSchedulerEvent.jobPromoted(this, jobId, job.getJobType(),
                oldPriority, newPriority, queue.size(), runningJobs.size()));
    }

    /**
     * Report a phase transition from a running job. If phase-aware yield is enabled
     * and the new phase doesn't need GPU, the GPU reservation is released for other jobs.
     */
    public void reportPhaseTransition(String jobId, String phaseName,
                                       boolean requiresGpu, long gpuMemoryBytes) {
        ScheduledJob job = runningJobs.get(jobId);
        if (job == null) {
            log.debug("Phase transition for unknown/non-running job '{}'", jobId);
            return;
        }

        String previousPhase = job.getCurrentPhase();
        job.setCurrentPhase(phaseName);

        ResourceSchedulerConfig config = configService.getConfiguration();

        log.info("Job PHASE TRANSITION: id='{}', type='{}', {} → {}, " +
                        "requiresGpu={}, gpuMemMb={}",
                jobId, job.getJobType(), previousPhase, phaseName,
                requiresGpu, gpuMemoryBytes / (1024L * 1024L));

        if (config.isPhaseAwareYieldEnabled()) {
            if (!requiresGpu && job.isGpuHeld()) {
                // Release GPU — CPU-only phase
                log.info("Job '{}' yielding GPU at phase '{}' (phase-aware yield)",
                        jobId, phaseName);
                try {
                    modelLifecycleManager.releaseGpuForJob(jobId);
                    job.setGpuHeld(false);
                    job.advanceState(ScheduledJob.JobState.PHASE_YIELDING);
                    log.info("Job '{}' GPU released, state=PHASE_YIELDING", jobId);
                    triggerDispatch();
                } catch (Exception e) {
                    log.warn("Failed to release GPU for job '{}' during phase yield: {}",
                            jobId, e.getMessage());
                }
            } else if (requiresGpu && !job.isGpuHeld() && !job.isTerminal()) {
                // Re-acquire GPU — entering GPU phase
                reacquireGpuForPhase(job, phaseName, gpuMemoryBytes);
            }
        }

        publishEvent(JobSchedulerEvent.jobPhaseTransition(this, jobId, job.getJobType(),
                previousPhase, phaseName, requiresGpu, queue.size(), runningJobs.size()));
    }

    /**
     * Re-acquire a yielded job's GPU for a GPU phase, sized to the phase and on the job's own device —
     * its child already runs there. The phase runs either way, so when the device can't fit it the job
     * gets an over-committed row rather than using memory the ledger can't see.
     */
    private void reacquireGpuForPhase(ScheduledJob job, String phaseName, long gpuMemoryBytes) {
        String jobId = job.getJobId();
        String serviceType = job.getResourceProfile().serviceType();
        String description = String.format("Phase %s of %s", phaseName, job.getDescription());
        ModelLifecycleManager.HoldLifetime lifetime = holdLifetime(job);
        long phaseBytes = gpuMemoryBytes > 0 ? gpuMemoryBytes : gpuCapBytes(job.getResourceProfile());
        GpuDevice jobDevice = placementDevice(job);

        log.info("Job '{}' re-acquiring GPU for phase '{}' ({}MB)",
                jobId, phaseName, phaseBytes / (1024L * 1024L));
        try {
            GpuDevice device = modelLifecycleManager.acquireGpuForJob(
                    jobId, serviceType, description, lifetime, phaseBytes, jobDevice);
            job.setGpuHeld(true);
            log.info("Job '{}' GPU re-acquired on device '{}' for phase '{}'",
                    jobId, device.name(), phaseName);
        } catch (Exception e) {
            GpuDevice device = jobDevice != null
                    ? jobDevice
                    : modelLifecycleManager.admitJob(serviceType, phaseBytes, null).device();
            if (device == null) {
                log.error("Failed to re-acquire GPU for job '{}' at phase '{}': {}",
                        jobId, phaseName, e.getMessage());
                return;
            }
            long bytes = ModelLifecycleManager.clampToDevice(phaseBytes, device);
            log.warn("Job '{}' can't re-acquire {}MB on '{}' for phase '{}' ({}) — the phase runs there anyway; " +
                            "recording an over-committed reservation",
                    jobId, bytes / (1024L * 1024L), device.name(), phaseName, e.getMessage());
            modelLifecycleManager.recordOverCommitHold(jobId, serviceType, device, bytes, description, lifetime);
            job.setGpuHeld(true);
        }

        if (!job.advanceState(ScheduledJob.JobState.RUNNING)) {
            // Cancelled meanwhile — the cancel may have released before this hold existed
            modelLifecycleManager.releaseGpuForJob(jobId);
            job.setGpuHeld(false);
        }
    }

    // ==================== Status / Monitoring ====================

    /**
     * Get a full scheduler status snapshot.
     */
    public Map<String, Object> getStatus() {
        ResourceSchedulerConfig config = configService.getConfiguration();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", config.isEnabled());
        status.put("running", running.get());
        status.put("algorithm", config.getSchedulingAlgorithm());
        status.put("phaseAwareYield", config.isPhaseAwareYieldEnabled());
        status.put("queueDepth", queue.size());
        status.put("queueCapacity", config.getGlobalQueueDepth());
        status.put("runningCount", runningJobs.size());
        status.put("totalSubmitted", totalSubmitted.get());
        status.put("totalCompleted", totalCompleted.get());
        status.put("totalFailed", totalFailed.get());
        status.put("totalCancelled", totalCancelled.get());

        // Running count by type
        Map<String, Long> runningByType = runningJobs.values().stream()
                .collect(Collectors.groupingBy(ScheduledJob::getJobType, Collectors.counting()));
        status.put("runningByType", runningByType);

        // Queued count by type
        Map<String, Long> queuedByType = queue.stream()
                .collect(Collectors.groupingBy(ScheduledJob::getJobType, Collectors.counting()));
        status.put("queuedByType", queuedByType);

        status.put("maxConcurrentByType", config.getMaxConcurrentByType());

        // External scheduler info
        String externalMode = config.getExternalSchedulerMode();
        status.put("externalSchedulerMode", externalMode != null ? externalMode : "none");
        status.put("externalSchedulerEnabled", config.isExternalSchedulerEnabled());
        if (config.isExternalSchedulerEnabled()) {
            Optional<ExternalJobSchedulerDelegate> activeDelegate = getActiveExternalDelegate();
            status.put("externalSchedulerAvailable", activeDelegate.isPresent());
            activeDelegate.ifPresent(d -> status.put("externalSchedulerActiveMode", d.getMode()));

            long externalRunning = runningJobs.values().stream()
                    .filter(ScheduledJob::isExternallyDelegated)
                    .count();
            status.put("externallyDelegatedCount", externalRunning);
        }
        status.put("availableExternalModes", externalDelegates.stream()
                .map(ExternalJobSchedulerDelegate::getMode)
                .collect(Collectors.toList()));

        return status;
    }

    /**
     * Get queued job views.
     */
    public List<ScheduledJob.ScheduledJobView> getQueueSnapshot() {
        return queue.stream()
                .sorted()
                .map(ScheduledJob::toView)
                .collect(Collectors.toList());
    }

    /**
     * Get running job views.
     */
    public List<ScheduledJob.ScheduledJobView> getRunningSnapshot() {
        return runningJobs.values().stream()
                .sorted(Comparator.comparing(
                        ScheduledJob::getStartedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(ScheduledJob::toView)
                .collect(Collectors.toList());
    }

    /**
     * Get a specific job's view, or null.
     */
    public ScheduledJob.ScheduledJobView getJobView(String jobId) {
        ScheduledJob job = allJobs.get(jobId);
        return job != null ? job.toView() : null;
    }

    /**
     * Get status information about available external scheduler delegates.
     */
    public List<Map<String, Object>> getExternalDelegateStatus() {
        return externalDelegates.stream()
                .map(d -> {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("mode", d.getMode());
                    info.put("available", d.isAvailable());
                    info.put("active", getActiveExternalDelegate()
                            .map(active -> active.getMode().equals(d.getMode()))
                            .orElse(false));
                    return info;
                })
                .collect(Collectors.toList());
    }

    /**
     * Cancel an externally-delegated job via the external scheduler.
     */
    public CompletableFuture<Boolean> cancelExternal(String jobId) {
        ScheduledJob job = allJobs.get(jobId);
        if (job == null || !job.isExternallyDelegated() || job.getExternalRef() == null) {
            return CompletableFuture.completedFuture(cancel(jobId));
        }

        Optional<ExternalJobSchedulerDelegate> delegateOpt = getActiveExternalDelegate();
        if (delegateOpt.isEmpty()) {
            return CompletableFuture.completedFuture(cancel(jobId));
        }

        return delegateOpt.get().cancelJob(job.getJobId(), job.getExternalRef())
                .thenApply(externalCancelled -> {
                    boolean localCancelled = cancel(jobId);
                    log.info("Cancel external job '{}': external={}, local={}",
                            jobId, externalCancelled, localCancelled);
                    return externalCancelled || localCancelled;
                });
    }

    // ==================== Dispatch Loop ====================

    private void dispatchLoop() {
        if (!running.get()) return;

        try {
            ResourceSchedulerConfig config = configService.getConfiguration();
            cleanupTimedOutJobs(config);
            pollExternalJobs();
            dispatchCandidates(config);
            // Periodically clean up terminal jobs from allJobs to prevent memory leak
            if (allJobs.size() > 100) {
                cleanupTerminalJobs();
            }
        } catch (Exception e) {
            log.error("Dispatch loop error: {}", e.getMessage(), e);
        }
    }

    /**
     * Poll externally-delegated running jobs for status updates.
     * This catches completion/failure if the callback was missed.
     */
    private void pollExternalJobs() {
        Optional<ExternalJobSchedulerDelegate> delegateOpt = getActiveExternalDelegate();
        if (delegateOpt.isEmpty()) return;

        ExternalJobSchedulerDelegate delegate = delegateOpt.get();
        for (ScheduledJob job : runningJobs.values()) {
            if (!job.isExternallyDelegated() || job.getExternalRef() == null) continue;
            if (job.isTerminal()) continue;

            try {
                delegate.getJobStatus(job.getJobId(), job.getExternalRef())
                        .thenAccept(status -> {
                            switch (status.status()) {
                                case "COMPLETED" -> {
                                    log.info("External job '{}' completed (polled): {}",
                                            job.getJobId(), status.message());
                                    handleExternalCallback(job.getJobId(), true, status.message());
                                }
                                case "FAILED" -> {
                                    log.warn("External job '{}' failed (polled): {}",
                                            job.getJobId(), status.message());
                                    handleExternalCallback(job.getJobId(), false, status.message());
                                }
                                // PENDING, RUNNING, UNKNOWN — continue waiting
                            }
                        });
            } catch (Exception e) {
                log.debug("Error polling external job '{}': {}", job.getJobId(), e.getMessage());
            }
        }
    }

    private void dispatchCandidates(ResourceSchedulerConfig config) {
        // Snapshot of the queue for iteration (PriorityBlockingQueue doesn't guarantee
        // iterator order, so we copy and sort)
        List<ScheduledJob> candidates = new ArrayList<>(queue);
        candidates.sort(null); // Uses ScheduledJob.compareTo (priority desc, queuedAt asc)

        // Track which higher-priority jobs were blocked so we can emit skip-ahead events
        List<BlockedCandidate> blockedHigherPriority = new ArrayList<>();
        int dispatched = 0;

        for (ScheduledJob candidate : candidates) {
            if (!running.get()) return;

            String type = candidate.getJobType();
            String blockReason = null;

            // Check per-type concurrency limit
            long typeRunningCount = runningJobs.values().stream()
                    .filter(j -> j.getJobType().equals(type))
                    .count();
            int maxConcurrent = config.getMaxConcurrentForType(type);
            if (typeRunningCount >= maxConcurrent) {
                blockReason = "Concurrency limit reached (" + typeRunningCount + "/" + maxConcurrent + " " + type + " jobs running)";
            }

            // Check conflicts
            if (blockReason == null) {
                JobResourceProfile profile = candidate.getResourceProfile();
                if (profile.conflictsWith() != null && !profile.conflictsWith().isEmpty()) {
                    Optional<String> conflicting = runningJobs.values().stream()
                            .map(ScheduledJob::getJobType)
                            .filter(jt -> profile.conflictsWith().contains(jt))
                            .findFirst();
                    if (conflicting.isPresent()) {
                        blockReason = "Conflicts with running " + conflicting.get() + " job";
                    }
                }
            }

            // Check CPU / host-RAM admission. The governor adds the CPU/RAM gates the GPU-only
            // reservation check below cannot see — the main defense against a job pile-up crashing
            // the host. GPU-requiring jobs are not deferred on CPU pressure (they block on GPU).
            if (blockReason == null && governor != null) {
                ResourceGovernor.AdmissionResult admission =
                        governor.admitJob(candidate.getResourceProfile());
                if (!admission.admit()) {
                    blockReason = admission.blockReason();
                }
            }

            // Check GPU availability for GPU-requiring jobs — the same admission acquireGpuForJob
            // applies, for the job's whole cap. Only a shortfall waits: with no GPU at all the
            // acquire fails the job.
            if (blockReason == null) {
                JobResourceProfile profile = candidate.getResourceProfile();
                boolean needsGpuNow = profile.requiresGpu();
                if (profile.hasPhaseBreakdown()) {
                    var firstPhase = profile.phaseProfiles().get(0);
                    needsGpuNow = firstPhase.requiresGpu();
                }

                if (needsGpuNow) {
                    ModelLifecycleManager.GpuAdmission admission =
                            modelLifecycleManager.admitJob(profile.serviceType(), gpuCapBytes(profile), null);
                    if (admission.shortfall()) {
                        blockReason = admission.blockReason();
                    }
                }
            }

            if (blockReason != null) {
                // Job is blocked — record reason and continue to next candidate
                String previousReason = candidate.getBlockedReason();
                candidate.setBlockedReason(blockReason);

                // Only emit event if this is a newly blocked job (reason changed)
                if (previousReason == null || !previousReason.equals(blockReason)) {
                    log.info("Job BLOCKED: id='{}', type='{}', reason='{}'",
                            candidate.getJobId(), candidate.getJobType(), blockReason);
                    publishEvent(JobSchedulerEvent.jobBlocked(this, candidate.getJobId(),
                            candidate.getJobType(), blockReason, queue.size(), runningJobs.size()));
                }

                blockedHigherPriority.add(new BlockedCandidate(
                        candidate.getJobId(), candidate.getJobType(),
                        candidate.getPriority(), blockReason));
                continue;
            }

            // Clear blocked reason — job is now dispatchable
            candidate.setBlockedReason(null);

            // If higher-priority jobs were blocked but this one can run, emit skip-ahead
            if (!blockedHigherPriority.isEmpty()) {
                BlockedCandidate highestBlocked = blockedHigherPriority.get(0);
                log.info("Job SKIP-AHEAD: id='{}' ({}, pri={}) dispatching ahead of blocked '{}' ({}, pri={}, reason='{}')",
                        candidate.getJobId(), candidate.getJobType(), candidate.getPriority(),
                        highestBlocked.jobId, highestBlocked.jobType, highestBlocked.priority,
                        highestBlocked.blockReason);

                publishEvent(JobSchedulerEvent.jobSkippedAhead(this,
                        candidate.getJobId(), candidate.getJobType(),
                        highestBlocked.jobId, highestBlocked.jobType,
                        highestBlocked.blockReason,
                        queue.size(), runningJobs.size()));
            }

            // Candidate can run — dispatch it
            if (queue.remove(candidate)) {
                dispatchJob(candidate);
                dispatched++;
            }
        }

        // If we had both blocked and dispatched jobs, emit a reorder event
        if (!blockedHigherPriority.isEmpty() && dispatched > 0) {
            publishEvent(JobSchedulerEvent.jobReordered(this,
                    queue.size(), runningJobs.size(),
                    blockedHigherPriority.size(), dispatched));
        }
    }

    private record BlockedCandidate(String jobId, String jobType, int priority, String blockReason) {}

    private void dispatchJob(ScheduledJob job) {
        // A cancel that won since the gate already ended the job
        if (!job.advanceState(ScheduledJob.JobState.ACQUIRING)) {
            return;
        }
        runningJobs.put(job.getJobId(), job);

        log.info("Job DISPATCHED: id='{}', type='{}', desc='{}', " +
                        "queueDepth={}, runningCount={}",
                job.getJobId(), job.getJobType(), job.getDescription(),
                queue.size(), runningJobs.size());

        publishEvent(JobSchedulerEvent.jobDispatched(this, job.getJobId(), job.getJobType(),
                job.getDescription(), queue.size(), runningJobs.size()));

        // Check for external scheduler delegation
        Optional<ExternalJobSchedulerDelegate> delegate = getActiveExternalDelegate();
        if (delegate.isPresent()) {
            dispatchToExternal(job, delegate.get());
        } else {
            FutureTask<Void> future = new FutureTask<>(() -> {
                executeJob(job);
                return null;
            });
            executionFutures.put(job.getJobId(), future);
            if (job.getCancellationRequested().get()) {
                // Cancelled while being dispatched — the cancel may have missed these entries
                future.cancel(true);
                executionFutures.remove(job.getJobId());
                runningJobs.remove(job.getJobId());
            } else {
                jobExecutionPool.execute(future);
            }
        }
    }

    /**
     * Dispatch a job to an external scheduler (Kubernetes, webhook, etc.).
     * The external system handles actual execution; we wait for the callback
     * at {@code POST /api/scheduler/callback} to complete the job.
     */
    private void dispatchToExternal(ScheduledJob job, ExternalJobSchedulerDelegate delegate) {
        if (!job.advanceState(ScheduledJob.JobState.RUNNING)) {
            // Cancelled since dispatch — nothing to submit
            runningJobs.remove(job.getJobId());
            return;
        }
        job.setExternallyDelegated(true);
        job.setStartedAt(Instant.now());
        job.setCurrentPhase("EXTERNAL_SUBMITTED");

        String mode = delegate.getMode();
        log.info("Job '{}' ({}) delegated to external scheduler: mode='{}'",
                job.getJobId(), job.getJobType(), mode);

        delegate.submitJob(
                job.getJobId(), job.getJobType(), job.getDescription(),
                job.getResourceProfile(), job.getMetadata()
        ).whenComplete((ref, ex) -> {
            if (ex != null) {
                log.error("External submission failed for job '{}' via '{}': {}",
                        job.getJobId(), mode, ex.getMessage(), ex);
                completeJob(job, false,
                        "External scheduler submission failed (" + mode + "): " + ex.getMessage(),
                        job.getStartedAt());
                return;
            }

            if ("FAILED".equals(ref.status())) {
                log.error("External scheduler rejected job '{}': {}",
                        job.getJobId(), ref.message());
                completeJob(job, false,
                        "External scheduler rejected: " + ref.message(),
                        job.getStartedAt());
                return;
            }

            job.setExternalRef(ref.externalId());
            job.setCurrentPhase("EXTERNAL_RUNNING");
            log.info("Job '{}' accepted by external scheduler '{}': externalId='{}', status='{}'",
                    job.getJobId(), mode, ref.externalId(), ref.status());

            // Job stays in RUNNING state in runningJobs until external callback arrives
            // via handleExternalCallback() or periodic status polling detects completion
        });
    }

    private void executeJob(ScheduledJob job) {
        Instant start = Instant.now();
        job.setStartedAt(start);

        // Every run ends in exactly one completeJob, from the finally block — even when the executor
        // throws an Error — so its GPU hold is released and it leaves runningJobs. Only a job that
        // goes back to the queue skips it.
        boolean succeeded = false;
        boolean requeued = false;
        String error = null;
        try {
            // Acquire GPU if needed
            JobResourceProfile profile = job.getResourceProfile();
            boolean needsGpuNow = profile.requiresGpu();
            if (profile.hasPhaseBreakdown()) {
                var firstPhase = profile.phaseProfiles().get(0);
                needsGpuNow = firstPhase.requiresGpu();
            }

            long capBytes = gpuCapBytes(profile);
            GpuDevice acquiredDevice = null;
            if (needsGpuNow) {
                log.info("Job '{}' ({}) acquiring GPU for service type '{}' ({}MB)",
                        job.getJobId(), job.getJobType(), profile.serviceType(), capBytes / (1024L * 1024L));
                try {
                    acquiredDevice = modelLifecycleManager.acquireGpuForJob(
                            job.getJobId(), profile.serviceType(), job.getDescription(),
                            holdLifetime(job), capBytes, null);
                    job.setGpuHeld(true);
                } catch (ModelLifecycleManager.GpuShortfallException e) {
                    // Admitted at dispatch, but the memory went elsewhere since: wait in the queue
                    requeued = requeue(job, e.getMessage());
                    if (!requeued) {
                        error = "GPU acquisition failed: " + e.getMessage();
                    }
                    return;
                } catch (Exception e) {
                    log.error("Job '{}' failed to acquire GPU: {}", job.getJobId(), e.getMessage());
                    error = "GPU acquisition failed: " + e.getMessage();
                    return;
                }
            }

            // A cancel that won meanwhile already ended the job — don't start the work
            if (!job.advanceState(ScheduledJob.JobState.RUNNING)) {
                return;
            }
            job.setCurrentPhase("RUNNING");

            // Device-agnostic placement from the acquired device (previously discarded — the return of
            // acquireGpuForJob was dropped): pins the subprocess to that device via ND4J placement and
            // bounds it to exactly what the job reserved there. CPU placement when no GPU was needed.
            // Rides the per-invocation execution context so concurrent dispatch of one launcher type
            // can't race.
            SubprocessPlacement placement = acquiredDevice != null
                    ? SubprocessPlacement.gpu(acquiredDevice.cudaRuntimeIndex(),
                            ModelLifecycleManager.clampToDevice(capBytes, acquiredDevice))
                    : SubprocessPlacement.cpu();
            job.setAssignedPlacement(placement);

            log.info("Job EXECUTING: id='{}', type='{}', gpuHeld={}, placement=[backend={}, device={}, boundMB={}]",
                    job.getJobId(), job.getJobType(), job.isGpuHeld(),
                    placement.backend(), placement.deviceId(), placement.maxDeviceMemoryBytes() / (1024L * 1024L));

            // Build execution context with phase callback + the device-agnostic placement.
            ScheduledJob.PhaseCallback phaseCallback = (jobId, phaseName, requiresGpu, gpuMem) ->
                    reportPhaseTransition(jobId, phaseName, requiresGpu, gpuMem);

            ScheduledJob.JobExecutionContext context = new ScheduledJob.JobExecutionContext(
                    job.getJobId(), profile, phaseCallback, placement, job.getCancellationRequested());

            // Execute the actual work
            job.getExecutor().execute(context);
            succeeded = true;

        } catch (Throwable t) {
            error = errorMessage(t);
            if (job.getState() == ScheduledJob.JobState.CANCELLED) {
                log.debug("Cancelled job '{}' executor stopped with: {}", job.getJobId(), error);
            } else {
                log.error("Job '{}' ({}) failed: {}", job.getJobId(), job.getJobType(), error, t);
            }
        } finally {
            // For a job a cancel already ended, completeJob only cleans up
            if (!requeued) {
                completeJob(job, succeeded, error, start);
            }
        }
    }

    /**
     * Put a job whose GPU acquire lost a race after the gate admitted it back in the queue — QUEUED,
     * blocked with the reason — instead of failing it. False when a cancel ended the job first.
     */
    private boolean requeue(ScheduledJob job, String reason) {
        if (!job.transitionState(ScheduledJob.JobState.ACQUIRING, ScheduledJob.JobState.QUEUED)) {
            return false;
        }
        runningJobs.remove(job.getJobId());
        executionFutures.remove(job.getJobId());
        job.setBlockedReason(reason);
        queue.offer(job);
        if (job.isTerminal()) {
            // A cancel ended it while it was on its way back — its queue.remove may have come first
            queue.remove(job);
            return true;
        }
        log.info("Job REQUEUED: id='{}', type='{}', reason='{}'", job.getJobId(), job.getJobType(), reason);
        publishEvent(JobSchedulerEvent.jobBlocked(this, job.getJobId(), job.getJobType(),
                reason, queue.size(), runningJobs.size()));
        return true;
    }

    /**
     * The most GPU memory a job's child may use — its profile's peak, else its service's budget. The job
     * reserves this (clamped to its device) and its placement bounds the child to the same number.
     */
    private long gpuCapBytes(JobResourceProfile profile) {
        return profile.peakGpuMemoryBytes() > 0
                ? profile.peakGpuMemoryBytes()
                : gpuResourceManager.getMemoryBudget(profile.serviceType());
    }

    private static ModelLifecycleManager.HoldLifetime holdLifetime(ScheduledJob job) {
        return job.isLongLivedGpuHold()
                ? ModelLifecycleManager.HoldLifetime.LONG_LIVED
                : ModelLifecycleManager.HoldLifetime.BOUNDED;
    }

    /** The GPU a job was placed on (its child runs there), or null for a CPU placement. */
    private GpuDevice placementDevice(ScheduledJob job) {
        SubprocessPlacement placement = job.getAssignedPlacement();
        if (placement == null || !placement.isGpu()) {
            return null;
        }
        return gpuResourceManager.getDeviceByCudaRuntimeIndex(placement.deviceId()).orElse(null);
    }

    private static String errorMessage(Throwable t) {
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }

    private void completeJob(ScheduledJob job, boolean success, String error, Instant start) {
        long durationMs = Duration.between(start, Instant.now()).toMillis();

        // Release GPU if still held
        if (job.isGpuHeld()) {
            try {
                modelLifecycleManager.releaseGpuForJob(job.getJobId());
                job.setGpuHeld(false);
            } catch (Exception e) {
                log.warn("Error releasing GPU for completed job '{}': {}", job.getJobId(), e.getMessage());
            }
        }

        runningJobs.remove(job.getJobId());
        executionFutures.remove(job.getJobId());

        // A requested cancel is never reported as success
        if (success && job.getCancellationRequested().get()) {
            success = false;
            error = "Cancellation requested";
        }
        // Only the terminal transition's winner records the outcome and completes the future — a cancel
        // that got there first already did
        if (!job.tryTransitionToTerminal(success
                ? ScheduledJob.JobState.COMPLETED : ScheduledJob.JobState.FAILED)) {
            log.debug("Job '{}' already {} — not recording its {}", job.getJobId(), job.getState(),
                    success ? "completion" : "failure");
            triggerDispatch();
            return;
        }
        job.setCompletedAt(Instant.now());

        if (success) {
            job.setCurrentPhase("COMPLETED");
            totalCompleted.incrementAndGet();
            log.info("Job COMPLETED: id='{}', type='{}', desc='{}', duration={}ms",
                    job.getJobId(), job.getJobType(), job.getDescription(), durationMs);
        } else {
            job.setCurrentPhase("FAILED");
            totalFailed.incrementAndGet();
            log.error("Job FAILED: id='{}', type='{}', desc='{}', duration={}ms, error='{}'",
                    job.getJobId(), job.getJobType(), job.getDescription(), durationMs, error);
        }

        // Store error on the job so recordFromJob can read it (future not yet complete)
        if (!success && error != null) {
            job.setErrorMessage(error);
        }

        // Record full job data BEFORE publishing event so fullyRecordedIds is populated
        // and the event listener's sparse path is skipped (prevents double-recording)
        if (historyService != null) {
            try {
                historyService.recordFromJob(job);
            } catch (Exception e) {
                log.debug("Failed to record job history for '{}': {}", job.getJobId(), e.getMessage());
            }
        }

        if (success) {
            publishEvent(JobSchedulerEvent.jobCompleted(this, job.getJobId(), job.getJobType(),
                    job.getDescription(), durationMs, queue.size(), runningJobs.size()));
        } else {
            publishEvent(JobSchedulerEvent.jobFailed(this, job.getJobId(), job.getJobType(),
                    job.getDescription(), error, queue.size(), runningJobs.size()));
        }

        // Complete future AFTER recording + event, so callers see fully-published state
        job.getResultFuture().complete(new ScheduledJob.JobResult(success, error, durationMs));

        // Trigger dispatch for next queued jobs
        triggerDispatch();
    }

    private boolean cancelJob(ScheduledJob job, String reason) {
        // Win the terminal transition BEFORE interrupting anything: a completion racing this cancel then
        // records nothing, and the job is reported once — as cancelled
        if (!job.tryTransitionToTerminal(ScheduledJob.JobState.CANCELLED)) return false;

        job.getCancellationRequested().set(true);
        queue.remove(job);
        runningJobs.remove(job.getJobId());
        Future<?> execution = executionFutures.remove(job.getJobId());
        if (execution != null) {
            execution.cancel(true);
        }

        if (job.isGpuHeld()) {
            try {
                modelLifecycleManager.releaseGpuForJob(job.getJobId());
                job.setGpuHeld(false);
            } catch (Exception e) {
                log.warn("Error releasing GPU for cancelled job '{}': {}", job.getJobId(), e.getMessage());
            }
        }

        job.setCurrentPhase("CANCELLED");
        job.setCompletedAt(Instant.now());
        job.setCancelReason(reason);
        job.setBlockedReason(null); // Clear stale blocked reason on cancel
        totalCancelled.incrementAndGet();

        log.info("Job CANCELLED: id='{}', type='{}', reason='{}'",
                job.getJobId(), job.getJobType(), reason);

        // Record BEFORE publishing event to populate fullyRecordedIds (prevents double-recording)
        if (historyService != null) {
            try {
                historyService.recordFromJob(job);
            } catch (Exception e) {
                log.debug("Failed to record cancelled job history for '{}': {}", job.getJobId(), e.getMessage());
            }
        }

        publishEvent(JobSchedulerEvent.jobCancelled(this, job.getJobId(), job.getJobType(),
                reason, queue.size(), runningJobs.size()));

        // resultFuture.complete LAST — callers waiting on it see final state
        job.getResultFuture().complete(new ScheduledJob.JobResult(false, "Cancelled: " + reason, 0));

        triggerDispatch();
        return true;
    }

    private CompletableFuture<ScheduledJob.JobResult> executeImmediate(ScheduledJob job) {
        allJobs.put(job.getJobId(), job);
        totalSubmitted.incrementAndGet();
        runningJobs.put(job.getJobId(), job);

        Instant start = Instant.now();
        job.setStartedAt(start);
        if (!job.advanceState(ScheduledJob.JobState.RUNNING)) {
            // Cancelled as it was submitted — the cancel recorded it
            runningJobs.remove(job.getJobId());
            return job.getResultFuture();
        }
        job.setCurrentPhase("DISPATCHED");

        log.info("Job DISPATCHED (bypass): id='{}', type='{}', desc='{}'",
                job.getJobId(), job.getJobType(), job.getDescription());
        publishEvent(JobSchedulerEvent.jobDispatched(this, job.getJobId(), job.getJobType(),
                job.getDescription(), queue.size(), runningJobs.size()));

        // Any Throwable — an Error too — ends the job as FAILED; it always leaves runningJobs
        Throwable failure = null;
        try {
            ScheduledJob.PhaseCallback phaseCallback = (jobId, phaseName, requiresGpu, gpuMem) -> {
                String previousPhase = job.getCurrentPhase();
                log.debug("Phase transition (bypass mode): job='{}', phase='{}' -> '{}'", jobId, previousPhase, phaseName);
                job.setCurrentPhase(phaseName);
                publishEvent(JobSchedulerEvent.jobPhaseTransition(this, jobId, job.getJobType(),
                        previousPhase, phaseName, requiresGpu, queue.size(), runningJobs.size()));
            };

            // The job's own cancel flag, so a cancel reaches a cooperative executor in bypass mode too
            ScheduledJob.JobExecutionContext context = new ScheduledJob.JobExecutionContext(
                    job.getJobId(), job.getResourceProfile(), phaseCallback, null, job.getCancellationRequested());

            job.getExecutor().execute(context);
        } catch (Throwable t) {
            failure = t;
        } finally {
            runningJobs.remove(job.getJobId());
        }

        long durationMs = Duration.between(start, Instant.now()).toMillis();
        String error = failure != null ? errorMessage(failure)
                : job.getCancellationRequested().get() ? "Cancellation requested" : null;
        boolean success = error == null;
        // Only the terminal transition's winner records the outcome — a cancel that won already did
        if (!job.tryTransitionToTerminal(success
                ? ScheduledJob.JobState.COMPLETED : ScheduledJob.JobState.FAILED)) {
            return job.getResultFuture();
        }
        job.setCompletedAt(Instant.now());

        if (success) {
            job.setCurrentPhase("COMPLETED");
            totalCompleted.incrementAndGet();
            log.info("Job COMPLETED (bypass): id='{}', type='{}', desc='{}', duration={}ms",
                    job.getJobId(), job.getJobType(), job.getDescription(), durationMs);
        } else {
            job.setCurrentPhase("FAILED");
            totalFailed.incrementAndGet();
            log.error("Job FAILED (bypass): id='{}', type='{}', desc='{}', duration={}ms, error='{}'",
                    job.getJobId(), job.getJobType(), job.getDescription(), durationMs, error);

            // Store error on the job so recordFromJob can read it (future not yet complete)
            job.setErrorMessage(error);
        }

        // Record BEFORE publishing event to populate fullyRecordedIds (prevents double-recording)
        if (historyService != null) {
            try {
                historyService.recordFromJob(job);
            } catch (Exception he) {
                log.debug("Failed to record bypass job history for '{}': {}", job.getJobId(), he.getMessage());
            }
        }

        publishEvent(success
                ? JobSchedulerEvent.jobCompleted(this, job.getJobId(), job.getJobType(),
                        job.getDescription(), durationMs, queue.size(), runningJobs.size())
                : JobSchedulerEvent.jobFailed(this, job.getJobId(), job.getJobType(),
                        job.getDescription(), error, queue.size(), runningJobs.size()));

        // resultFuture.complete LAST — callers waiting on it see final state
        job.getResultFuture().complete(new ScheduledJob.JobResult(success, error, durationMs));
        return job.getResultFuture();
    }

    private void cleanupTimedOutJobs(ResourceSchedulerConfig config) {
        Instant cutoff = Instant.now().minusMillis(config.getQueueTimeoutMs());
        List<ScheduledJob> timedOut = queue.stream()
                .filter(j -> j.getQueuedAt().isBefore(cutoff))
                .collect(Collectors.toList());

        for (ScheduledJob job : timedOut) {
            log.warn("Job TIMED OUT in queue: id='{}', type='{}', queuedFor={}ms",
                    job.getJobId(), job.getJobType(),
                    Duration.between(job.getQueuedAt(), Instant.now()).toMillis());
            cancelJob(job, "Queue timeout (" + config.getQueueTimeoutMs() + "ms)");
        }
    }

    private void triggerDispatch() {
        // Schedule an immediate dispatch iteration
        if (dispatchExecutor != null && !dispatchExecutor.isShutdown()) {
            dispatchExecutor.submit(this::dispatchLoop);
        }
    }

    private void publishEvent(JobSchedulerEvent event) {
        if (eventPublisher != null) {
            try {
                eventPublisher.publishEvent(event);
            } catch (Exception e) {
                log.debug("Failed to publish scheduler event: {}", e.getMessage());
            }
        }
    }

    /**
     * Remove terminal jobs from the allJobs map to prevent unbounded growth.
     * Called periodically or when allJobs exceeds a threshold.
     */
    public int cleanupTerminalJobs() {
        int removed = 0;
        Iterator<Map.Entry<String, ScheduledJob>> it = allJobs.entrySet().iterator();
        Instant cutoff = Instant.now().minusSeconds(300); // Keep last 5 min
        while (it.hasNext()) {
            ScheduledJob job = it.next().getValue();
            if (job.isTerminal() && job.getCompletedAt() != null
                    && job.getCompletedAt().isBefore(cutoff)) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("Cleaned up {} terminal jobs from scheduler tracking", removed);
        }
        return removed;
    }
}
