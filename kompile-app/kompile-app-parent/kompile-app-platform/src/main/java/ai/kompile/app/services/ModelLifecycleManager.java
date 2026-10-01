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

import ai.kompile.app.config.DeviceRoutingConfig;
import ai.kompile.app.config.GpuDevice;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Priority-based subprocess lifecycle coordinator.
 *
 * <p>This is the central orchestrator that coordinates GPU-hungry subprocesses.
 * Before a high-priority service (e.g., VLM) launches, it:
 * <ol>
 *   <li>Checks if there's room on the target GPU device</li>
 *   <li>If not, identifies lower-priority services to evict</li>
 *   <li>Calls the registered {@link ManagedService#suspend()} on each eviction target</li>
 *   <li>Waits for confirmation that the subprocess has stopped</li>
 *   <li>Creates a GPU reservation for the new service</li>
 *   <li>Returns control to the caller so it can launch its subprocess</li>
 * </ol>
 *
 * When the high-priority service completes, the caller invokes {@link #releaseGpu(String)},
 * which releases the reservation and restores previously evicted services.</p>
 *
 * <h3>ManagedService Registration</h3>
 * <p>Each subprocess-managing bean (e.g., AnseriniEmbeddingModelImpl) registers itself
 * as a {@link ManagedService} with this manager. The manager calls {@code suspend()} to
 * stop the subprocess and {@code resume()} to restart it. The service must set a
 * preemption flag to prevent its auto-restart logic from racing.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>The {@code acquireGpu()} and {@code releaseGpu()} methods are serialized via a
 * reentrant lock to prevent concurrent acquisition races.</p>
 */
@Service
public class ModelLifecycleManager implements SmartLifecycle {

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected ModelLifecycleManager() {}


    private static final Logger log = LoggerFactory.getLogger(ModelLifecycleManager.class);

    /** Default maximum time to wait for a service to stop its subprocess (seconds) */
    private static final int DEFAULT_EVICTION_TIMEOUT_SECONDS = 60;

    /** Default maximum time to wait for a service to resume (seconds) */
    private static final int DEFAULT_RESUME_TIMEOUT_SECONDS = 120;

    /** Default stale job threshold (seconds). Jobs holding GPU longer than this are flagged. */
    private static final long DEFAULT_STALE_JOB_THRESHOLD_SECONDS = 3600; // 1 hour

    /**
     * SmartLifecycle phase — runs after most beans are initialized (high phase = late start),
     * but shuts down early (high phase = early stop in shutdown).
     * We want GPU lifecycle to shut down BEFORE the individual subprocess @PreDestroy handlers
     * so we can cleanly release reservations while services are still reachable.
     */
    private static final int LIFECYCLE_PHASE = Integer.MAX_VALUE - 100;

    /** Configurable eviction timeout (seconds). Defaults to {@value DEFAULT_EVICTION_TIMEOUT_SECONDS}. */
    private int evictionTimeoutSeconds;

    /** Configurable resume timeout (seconds). Defaults to {@value DEFAULT_RESUME_TIMEOUT_SECONDS}. */
    private int resumeTimeoutSeconds;

    /** Configurable stale job threshold (seconds). Defaults to {@value DEFAULT_STALE_JOB_THRESHOLD_SECONDS}. */
    private long staleJobThresholdSeconds;

    /**
     * Interface that GPU-managed services must implement to participate
     * in lifecycle coordination.
     */
    public interface ManagedService {

        /**
         * The service type identifier (must match DeviceRoutingConfig constants).
         */
        String getServiceType();

        /**
         * Suspend this service — stop its subprocess and release GPU memory.
         * This method must:
         * <ol>
         *   <li>Set a preemption flag to prevent auto-restart</li>
         *   <li>Stop the subprocess gracefully</li>
         *   <li>Return true when the subprocess has fully stopped</li>
         * </ol>
         *
         * @param reason human-readable reason for the suspension
         * @return true if the service was successfully suspended
         */
        boolean suspend(String reason);

        /**
         * Resume this service — restart its subprocess.
         * Called after the preempting service has released its GPU reservation.
         *
         * @return true if the service was successfully resumed
         */
        boolean resume();

        /**
         * Check if the service's subprocess is currently running.
         */
        boolean isRunning();

        /**
         * Check if the service is currently suspended due to preemption.
         */
        boolean isSuspended();
    }

    /**
     * Extended interface for services that support warmup before serving.
     * Implement this to participate in automatic warmup after GPU restoration.
     */
    public interface WarmableService extends ManagedService {

        /**
         * Run warmup iterations to eliminate first-request latency spikes.
         *
         * @param iterations number of warmup iterations
         * @param warmupText sample text to use for warmup (e.g., for embedding)
         * @return total warmup latency in milliseconds
         */
        long warmup(int iterations, String warmupText);

        /**
         * Check if this service has already been warmed up.
         */
        boolean isWarmedUp();
    }

    @Autowired
    private GpuResourceManager gpuResourceManager;
    @Autowired(required = false)
    private DeviceRoutingConfigService deviceRoutingConfigService;
    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;

    /** Registered managed services keyed by service type */
    private final Map<String, ManagedService> managedServices = new ConcurrentHashMap<>();

    /** Track which services were evicted by which acquirer, so we can restore them on release */
    final Map<String, List<String>> evictedByAcquirer = new ConcurrentHashMap<>();

    /** Serialization lock for acquire/release operations */
    private final ReentrantLock acquisitionLock = new ReentrantLock();

    /** Tracks whether this lifecycle manager is running (for SmartLifecycle) */
    private final AtomicBoolean running = new AtomicBoolean(false);

    // ==================== Job-Level GPU Tracking ====================

    /**
     * A GPU hold tied to a specific job. Each job that acquires GPU resources gets a
     * JobGpuHold that tracks what was acquired and when, so we can:
     * <ol>
     *   <li>Release GPU on job completion, failure, or cancellation</li>
     *   <li>Release all holds on application shutdown</li>
     *   <li>Provide visibility into which jobs are holding GPU resources</li>
     * </ol>
     */
    public record JobGpuHold(
            /** Unique job/task identifier */
            String jobId,
            /** The service type this job runs under (e.g., "vlm", "ingest") */
            String serviceType,
            /** The device reserved for this job */
            GpuDevice device,
            /** When the GPU was acquired */
            Instant acquiredAt,
            /** Human-readable description of the job */
            String description,
            /** Whether this hold is expected to remain active indefinitely while its owner is alive */
            HoldLifetime lifetime
    ) {
        public JobGpuHold(String jobId, String serviceType, GpuDevice device,
                          Instant acquiredAt, String description) {
            this(jobId, serviceType, device, acquiredAt, description, HoldLifetime.BOUNDED);
        }
    }

    public enum HoldLifetime {
        BOUNDED,
        LONG_LIVED
    }

    /**
     * The outcome of {@link #admitJob}: the device a job would run on, the bytes it would reserve there
     * (its cap, clamped to the device), and the services to evict first. {@code blockReason} is set when
     * the device can't make room right now (the job should wait); {@code device} is null only when there
     * is no GPU at all.
     */
    public record GpuAdmission(GpuDevice device, long reservedBytes, List<String> toEvict, String blockReason) {
        public boolean admitted() {
            return device != null && blockReason == null;
        }

        public boolean shortfall() {
            return device != null && blockReason != null;
        }

        public boolean unavailable() {
            return device == null;
        }
    }

    /**
     * A job's GPU acquisition failed only because its device lacks memory right now (including losing
     * a race after admission). The job can wait and retry; any other acquisition failure is final.
     */
    public static class GpuShortfallException extends IllegalStateException {
        public GpuShortfallException(String message) {
            super(message);
        }

        public GpuShortfallException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Active GPU holds keyed by jobId */
    private final Map<String, JobGpuHold> activeJobHolds = new ConcurrentHashMap<>();

    @Autowired
    public ModelLifecycleManager(
            GpuResourceManager gpuResourceManager,
            @Autowired(required = false) DeviceRoutingConfigService deviceRoutingConfigService,
            @Autowired(required = false) ApplicationEventPublisher eventPublisher,
            @Value("${kompile.gpu.eviction-timeout-seconds:" + DEFAULT_EVICTION_TIMEOUT_SECONDS + "}") int evictionTimeoutSeconds,
            @Value("${kompile.gpu.resume-timeout-seconds:" + DEFAULT_RESUME_TIMEOUT_SECONDS + "}") int resumeTimeoutSeconds,
            @Value("${kompile.gpu.stale-job-threshold-seconds:" + DEFAULT_STALE_JOB_THRESHOLD_SECONDS + "}") long staleJobThresholdSeconds) {
        this.gpuResourceManager = gpuResourceManager;
        this.deviceRoutingConfigService = deviceRoutingConfigService;
        this.eventPublisher = eventPublisher;
        this.evictionTimeoutSeconds = evictionTimeoutSeconds;
        this.resumeTimeoutSeconds = resumeTimeoutSeconds;
        this.staleJobThresholdSeconds = staleJobThresholdSeconds;
        log.info("ModelLifecycleManager initialized (evictionTimeout={}s, resumeTimeout={}s, staleJobThreshold={}s)",
                evictionTimeoutSeconds, resumeTimeoutSeconds, staleJobThresholdSeconds);
    }

    // ==================== SmartLifecycle ====================

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("=== ModelLifecycleManager STARTED (phase={}) ===", getPhase());
            log.info("Registered services: {}", managedServices.keySet());
            log.info("GPU devices: {}", gpuResourceManager.getDevices().size());
            publishEvent(GpuLifecycleEvent.lifecycleStarted(this));
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("=== ModelLifecycleManager STOPPING (phase={}) ===", getPhase());
            shutdownAllJobHolds();
            shutdownAllManagedServices();
            publishEvent(GpuLifecycleEvent.lifecycleStopped(this));
            log.info("=== ModelLifecycleManager STOPPED ===");
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

    /**
     * Shutdown hook — releases all GPU reservations and logs final state.
     * Called as a safety net if SmartLifecycle.stop() wasn't called.
     */
    @PreDestroy
    public void shutdown() {
        if (running.compareAndSet(true, false)) {
            log.info("=== ModelLifecycleManager @PreDestroy (SmartLifecycle.stop() was not called) ===");
            shutdownAllJobHolds();
            shutdownAllManagedServices();
        } else {
            log.info("ModelLifecycleManager @PreDestroy — already stopped via SmartLifecycle");
        }

        // Always log final state
        logFinalState();
    }

    /**
     * Release all active job GPU holds. Called during shutdown.
     */
    private void shutdownAllJobHolds() {
        if (activeJobHolds.isEmpty()) {
            log.info("No active job GPU holds to release");
            return;
        }

        log.info("Releasing {} active job GPU hold(s) during shutdown:", activeJobHolds.size());
        for (JobGpuHold hold : activeJobHolds.values()) {
            log.info("  Releasing hold: jobId='{}', service='{}', device='{}', acquiredAt={}",
                    hold.jobId(), hold.serviceType(), hold.device().name(), hold.acquiredAt());
            try {
                gpuResourceManager.release(hold.jobId());
            } catch (Exception e) {
                log.warn("Error releasing GPU hold for job '{}'", hold.jobId(), e);
            }
        }
        activeJobHolds.clear();
        evictedByAcquirer.clear();
    }

    /**
     * Suspend all managed services during shutdown so their subprocesses
     * are stopped cleanly before the JVM exits.
     */
    private void shutdownAllManagedServices() {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Map.Entry<String, ManagedService> entry : managedServices.entrySet()) {
            ManagedService service = entry.getValue();
            if (service.isRunning() && !service.isSuspended()) {
                log.info("Shutting down managed service '{}' (running=true)", entry.getKey());
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        service.suspend("Application shutdown");
                    } catch (Exception e) {
                        log.warn("Error suspending service '{}' during shutdown",
                                entry.getKey(), e);
                    }
                }));
            }
        }
        if (!futures.isEmpty()) {
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Timed out or interrupted waiting for managed services to suspend during shutdown", e);
            }
        }
    }

    /**
     * Log the final state of GPU resources and managed services for diagnostics.
     */
    private void logFinalState() {
        log.info("=== ModelLifecycleManager Final State ===");
        log.info("  Active job holds: {}", activeJobHolds.size());
        log.info("  Active reservations: {}", gpuResourceManager.getActiveReservations().size());
        log.info("  Managed services: {}", managedServices.size());
        for (ManagedService service : managedServices.values()) {
            log.info("    '{}': running={}, suspended={}",
                    service.getServiceType(), service.isRunning(), service.isSuspended());
        }
        log.info("  Eviction map: {}", evictedByAcquirer);
        log.info("=== End Final State ===");
    }

    // ==================== Service Registration ====================

    /**
     * Register a managed service for lifecycle coordination.
     * Must be called during bean initialization (e.g., @PostConstruct).
     */
    public void registerService(ManagedService service) {
        managedServices.put(service.getServiceType(), service);
        log.info("Registered managed service: '{}' (running={})",
                service.getServiceType(), service.isRunning());
    }

    /**
     * Unregister a managed service.
     */
    public void unregisterService(String serviceType) {
        managedServices.remove(serviceType);
        log.info("Unregistered managed service: '{}'", serviceType);
    }

    /**
     * Get a registered managed service by type.
     */
    public Optional<ManagedService> getService(String serviceType) {
        return Optional.ofNullable(managedServices.get(serviceType));
    }

    // ==================== GPU Acquisition ====================

    /**
     * Acquire GPU resources for a service, evicting lower-priority services if necessary.
     *
     * <p>This is the primary coordination method. It:
     * <ol>
     *   <li>Determines the target device (from device routing config, or auto-select)</li>
     *   <li>Checks if the service can fit without eviction</li>
     *   <li>If not, identifies and evicts lower-priority services</li>
     *   <li>Creates a reservation for the requesting service</li>
     * </ol>
     *
     * <p>This method is <b>synchronous and blocking</b>. It will wait up to
     * {@value EVICTION_TIMEOUT_SECONDS} seconds for each evicted service to stop.</p>
     *
     * @param serviceType the service requesting GPU resources
     * @return the GPU device that was reserved, for passing to subprocess launch
     * @throws IllegalStateException if the service cannot be accommodated
     */
    public GpuDevice acquireGpu(String serviceType) {
        return acquireGpu(serviceType, null);
    }

    /**
     * Acquire GPU resources on a specific device.
     *
     * @param serviceType the service requesting GPU resources
     * @param preferredDevice the preferred device, or null for auto-select
     * @return the GPU device that was reserved
     * @throws IllegalStateException if the service cannot be accommodated
     */
    public GpuDevice acquireGpu(String serviceType, GpuDevice preferredDevice) {
        acquisitionLock.lock();
        try {
            log.info("=== GPU ACQUISITION: '{}' requesting GPU resources ===", serviceType);

            // Step 1: Determine target device
            GpuDevice targetDevice = resolveTargetDevice(serviceType, preferredDevice);
            if (targetDevice == null) {
                throw new IllegalStateException(
                        "No GPU device available for service '" + serviceType + "'. " +
                        "Check nvidia-smi and device routing configuration.");
            }

            log.info("Target device for '{}': {} (available: {}MB)",
                    serviceType, targetDevice.name(),
                    gpuResourceManager.getAvailableMemory(targetDevice) / (1024 * 1024));

            // Step 2: Check if we fit without eviction
            if (gpuResourceManager.canFit(serviceType, targetDevice)) {
                log.info("Service '{}' fits on {} without eviction", serviceType, targetDevice.name());
                gpuResourceManager.reserve(serviceType, targetDevice,
                        gpuResourceManager.getMemoryBudget(serviceType));
                return targetDevice;
            }

            // Step 3: Find eviction candidates (only a running managed service's own row can be freed)
            List<String> toEvict = gpuResourceManager.findEvictionCandidates(serviceType, targetDevice,
                    gpuResourceManager.getMemoryBudget(serviceType), this::isEvictable);
            if (toEvict.isEmpty()) {
                throw new IllegalStateException(String.format(
                        "Cannot accommodate '%s' on %s. Need %dMB, available %dMB, " +
                        "and no lower-priority services to evict.",
                        serviceType, targetDevice.name(),
                        gpuResourceManager.getMemoryBudget(serviceType) / (1024 * 1024),
                        gpuResourceManager.getAvailableMemory(targetDevice) / (1024 * 1024)));
            }

            log.info("Service '{}' requires eviction of {} service(s): {}",
                    serviceType, toEvict.size(), toEvict);

            // Step 4: Evict each service
            List<String> actuallyEvicted = new ArrayList<>();
            for (String evictTarget : toEvict) {
                boolean evicted = evictService(evictTarget, serviceType);
                if (evicted) {
                    actuallyEvicted.add(evictTarget);
                } else {
                    log.warn("Failed to evict '{}' — continuing anyway", evictTarget);
                }
            }

            // Step 5: Create reservation. If it still doesn't fit (a failed eviction), bring back what
            // was evicted before failing — nothing else would.
            try {
                gpuResourceManager.reserve(serviceType, targetDevice,
                        gpuResourceManager.getMemoryBudget(serviceType));
            } catch (IllegalStateException e) {
                restoreServices(serviceType, actuallyEvicted);
                throw e;
            }
            recordEvictions(serviceType, actuallyEvicted);

            log.info("=== GPU ACQUISITION COMPLETE: '{}' reserved {}MB on {} (evicted: {}) ===",
                    serviceType,
                    gpuResourceManager.getMemoryBudget(serviceType) / (1024 * 1024),
                    targetDevice.name(), actuallyEvicted);

            return targetDevice;

        } finally {
            acquisitionLock.unlock();
        }
    }

    /**
     * Release GPU resources held by a service and restore previously evicted services.
     *
     * @param serviceType the service releasing its GPU resources
     */
    public void releaseGpu(String serviceType) {
        acquisitionLock.lock();
        try {
            log.info("=== GPU RELEASE: '{}' releasing GPU resources ===", serviceType);

            // Step 1: Release the reservation
            gpuResourceManager.release(serviceType);

            // Step 2: Restore previously evicted services
            restoreEvictedBy(serviceType);

            log.info("=== GPU RELEASE COMPLETE: '{}' ===", serviceType);

        } finally {
            acquisitionLock.unlock();
        }
    }

    /**
     * Check if a service currently holds any GPU reservation(s).
     * For job-level reservations, this returns true if ANY job of that service type
     * has an active reservation.
     */
    public boolean hasGpuReservation(String serviceType) {
        return gpuResourceManager.hasReservationForService(serviceType);
    }

    // ==================== Job-Level GPU Acquisition ====================

    /**
     * Acquire GPU resources for a specific job. This ties the GPU reservation to a
     * concrete job/task lifecycle — the GPU is held until {@link #releaseGpuForJob(String)}
     * is called (on completion, failure, or cancellation).
     *
     * <p>Unlike {@link #acquireGpu(String)} which keys the reservation by service type
     * (suitable for singleton services), this method uses the {@code jobId} as the
     * reservation key. This allows multiple concurrent jobs of the same service type
     * without reservation collision.</p>
     *
     * <p>This method is the recommended entry point for all subprocess launchers.
     * It provides:
     * <ul>
     *   <li>Job-level tracking — visible via the REST API</li>
     *   <li>Automatic cleanup on shutdown — all job holds are released</li>
     *   <li>Eviction/preemption — same priority-based logic as {@link #acquireGpu(String)}</li>
     * </ul>
     *
     * @param jobId unique job/task identifier (e.g., taskId from ingest, VLM test)
     * @param serviceType the service type (DeviceRoutingConfig constants)
     * @param description human-readable description (e.g., "Ingest: document.pdf")
     * @return the GPU device that was reserved
     * @throws IllegalStateException if the service cannot be accommodated or manager is stopped
     */
    public GpuDevice acquireGpuForJob(String jobId, String serviceType, String description) {
        return acquireGpuForJob(jobId, serviceType, description, HoldLifetime.BOUNDED);
    }

    /**
     * Acquire GPU resources for a job with an explicit lifetime classification.
     * Long-lived holds remain visible in monitoring but are excluded from age-only stale detection.
     */
    public GpuDevice acquireGpuForJob(String jobId, String serviceType, String description,
                                      HoldLifetime lifetime) {
        return acquireGpuForJob(jobId, serviceType, description, lifetime, 0L, null);
    }

    /**
     * Acquire GPU resources for a job whose child may use up to {@code capBytes}. Reserves that cap,
     * clamped to the chosen device's total (see {@link #admitJob}); the caller bounds the child to the
     * same number, so what is reserved is what the child may use.
     *
     * @param capBytes the most GPU memory the job's child may use; {@code <= 0} means the service's budget
     * @param preferredDevice the device to acquire on (e.g. the job's own device when re-acquiring for a
     *                        phase), or null to choose one
     * @return the GPU device that was reserved
     * @throws GpuShortfallException if the device can't make room right now — the job may wait and retry
     * @throws IllegalStateException if there is no GPU at all or the manager is not running
     */
    public GpuDevice acquireGpuForJob(String jobId, String serviceType, String description,
                                      HoldLifetime lifetime, long capBytes, GpuDevice preferredDevice) {
        Objects.requireNonNull(lifetime, "lifetime");
        if (!running.get()) {
            throw new IllegalStateException(
                    "ModelLifecycleManager is not running — cannot acquire GPU for job '" + jobId + "'");
        }

        acquisitionLock.lock();
        try {
            log.info("=== GPU JOB ACQUIRE: jobId='{}', service='{}', desc='{}' ===",
                    jobId, serviceType, description);

            // Step 1: Admission — the target device, what to reserve there, and what to evict
            GpuAdmission admission = admitJob(serviceType, capBytes, preferredDevice);
            if (admission.unavailable()) {
                throw new IllegalStateException(
                        "No GPU device available for service '" + serviceType + "' (job '" + jobId + "'). " +
                        "Check nvidia-smi and device routing configuration.");
            }
            if (admission.shortfall()) {
                throw new GpuShortfallException(String.format("Cannot accommodate '%s' (job '%s'): %s",
                        serviceType, jobId, admission.blockReason()));
            }
            GpuDevice targetDevice = admission.device();
            long reservedBytes = admission.reservedBytes();

            log.info("Target device for job '{}' ({}): {} (available: {}MB, reserving: {}MB)",
                    jobId, serviceType, targetDevice.name(),
                    gpuResourceManager.getAvailableMemory(targetDevice) / (1024 * 1024),
                    reservedBytes / (1024 * 1024));

            // Step 2: Evict what admission picked (nothing when the job fits as-is)
            List<String> toEvict = admission.toEvict();
            List<String> actuallyEvicted = new ArrayList<>();
            if (!toEvict.isEmpty()) {
                log.info("Job '{}' ({}) requires eviction of {} service(s): {}",
                        jobId, serviceType, toEvict.size(), toEvict);

                for (String evictTarget : toEvict) {
                    boolean evicted = evictService(evictTarget, serviceType);
                    if (evicted) {
                        actuallyEvicted.add(evictTarget);
                    } else {
                        log.warn("Failed to evict '{}' for job '{}' — continuing anyway",
                                evictTarget, jobId);
                    }
                }
            }

            // Step 3: Create reservation keyed by jobId (not serviceType). If it still doesn't fit (a
            // failed eviction, a lost race), bring back what was evicted before failing — nothing else would.
            try {
                gpuResourceManager.reserveWithId(jobId, serviceType, targetDevice, reservedBytes);
            } catch (IllegalStateException e) {
                restoreServices(jobId, actuallyEvicted);
                throw new GpuShortfallException(e.getMessage(), e);
            }
            recordEvictions(jobId, actuallyEvicted);

            // Step 4: Record the job hold
            JobGpuHold hold = new JobGpuHold(jobId, serviceType, targetDevice,
                    Instant.now(), description, lifetime);
            activeJobHolds.put(jobId, hold);

            log.info("=== GPU JOB ACQUIRE COMPLETE: jobId='{}', service='{}', device='{}', " +
                            "reserved {}MB (active job holds: {}) ===",
                    jobId, serviceType, targetDevice.name(),
                    reservedBytes / (1024 * 1024), activeJobHolds.size());

            // Publish GPU acquired event
            publishEvent(GpuLifecycleEvent.gpuAcquired(this, jobId, serviceType, targetDevice,
                    reservedBytes / (1024 * 1024)));

            return targetDevice;

        } finally {
            acquisitionLock.unlock();
        }
    }

    /**
     * Admission for a GPU job — the one check shared by the scheduler's dispatch gate and
     * {@link #acquireGpuForJob}. A job reserves what its child may actually use: {@code capBytes} (the
     * service's budget when {@code <= 0}), clamped to the chosen device's total.
     *
     * <p>Device choice: an explicit device ({@code preferred}, else device routing) wins. Otherwise,
     * among the devices whose total holds the whole cap, the one where it fits now with the most
     * available memory, else the one with the most available plus evictable memory. Only when no
     * device's total holds the cap does the job get the largest device, with the cap clamped to it.</p>
     *
     * <p>Not locked: from the gate it is advisory, and {@link #acquireGpuForJob} calls it under the
     * acquisition lock.</p>
     */
    public GpuAdmission admitJob(String serviceType, long capBytes, GpuDevice preferred) {
        long cap = capBytes > 0 ? capBytes : gpuResourceManager.getMemoryBudget(serviceType);
        GpuDevice device = resolveExplicitDevice(serviceType, preferred);
        if (device == null) {
            device = chooseDeviceForCap(serviceType, cap);
        }
        if (device == null) {
            return new GpuAdmission(null, 0L, List.of(), "No GPU device available");
        }

        long bytes = clampToDevice(cap, device);
        long available = gpuResourceManager.getAvailableMemory(device);
        if (bytes <= available) {
            return new GpuAdmission(device, bytes, List.of(), null);
        }
        List<String> toEvict = gpuResourceManager.findEvictionCandidates(serviceType, device, bytes,
                this::isEvictable);
        if (!toEvict.isEmpty()) {
            return new GpuAdmission(device, bytes, toEvict, null);
        }
        return new GpuAdmission(device, bytes, List.of(), String.format(
                "Insufficient GPU memory on %s for '%s': need %dMB, available %dMB, evictable %dMB",
                device.name(), serviceType, bytes / (1024 * 1024), Math.max(0L, available) / (1024 * 1024),
                gpuResourceManager.getEvictableMemory(serviceType, device, this::isEvictable) / (1024 * 1024)));
    }

    private GpuDevice chooseDeviceForCap(String serviceType, long cap) {
        List<GpuDevice> holdsCap = gpuResourceManager.getDevices().stream()
                .filter(d -> d.totalMemoryBytes() >= cap)
                .toList();
        if (holdsCap.isEmpty()) {
            return gpuResourceManager.getLargestDevice().orElse(null);
        }
        Optional<GpuDevice> fitsNow = holdsCap.stream()
                .filter(d -> gpuResourceManager.getAvailableMemory(d) >= cap)
                .max(Comparator.comparingLong(gpuResourceManager::getAvailableMemory));
        return fitsNow.orElseGet(() -> holdsCap.stream()
                .max(Comparator.comparingLong(d -> gpuResourceManager.getAvailableMemory(d)
                        + gpuResourceManager.getEvictableMemory(serviceType, d, this::isEvictable)))
                .orElseThrow());
    }

    /**
     * A job's cap limited to what {@code device} physically has — both the bytes the job reserves
     * there and the bound its child runs under.
     */
    public static long clampToDevice(long capBytes, GpuDevice device) {
        return Math.min(capBytes, device.totalMemoryBytes());
    }

    /**
     * Record a job's GPU hold WITHOUT the availability check, for a GPU phase that must run on the
     * job's device although the ledger says it doesn't fit there (the child already runs on it). The
     * row over-commits the device — see {@link GpuResourceManager#reserveOverCommit}.
     */
    public void recordOverCommitHold(String jobId, String serviceType, GpuDevice device, long bytes,
                                     String description, HoldLifetime lifetime) {
        Objects.requireNonNull(lifetime, "lifetime");
        acquisitionLock.lock();
        try {
            gpuResourceManager.reserveOverCommit(jobId, serviceType, device, bytes);
            activeJobHolds.put(jobId, new JobGpuHold(jobId, serviceType, device, Instant.now(),
                    description, lifetime));
            publishEvent(GpuLifecycleEvent.gpuAcquired(this, jobId, serviceType, device,
                    bytes / (1024 * 1024)));
        } finally {
            acquisitionLock.unlock();
        }
    }

    /**
     * Release GPU resources held by a specific job.
     * Called on job completion, failure, or cancellation.
     *
     * <p>This is idempotent — calling it multiple times for the same jobId is safe.</p>
     *
     * @param jobId the job/task identifier that was passed to {@link #acquireGpuForJob}
     */
    public void releaseGpuForJob(String jobId) {
        acquisitionLock.lock();
        try {
            JobGpuHold hold = activeJobHolds.remove(jobId);
            if (hold == null) {
                log.debug("No GPU hold found for job '{}' — already released or never acquired", jobId);
                // Services evicted for this job still come back, even though it holds nothing now
                restoreEvictedBy(jobId);
                return;
            }

            long heldMs = Duration.between(hold.acquiredAt(), Instant.now()).toMillis();
            log.info("=== GPU JOB RELEASE: jobId='{}', service='{}', device='{}', heldFor={}ms ===",
                    jobId, hold.serviceType(), hold.device().name(), heldMs);

            // Release the reservation by jobId (the reservation was keyed by jobId in acquireGpuForJob)
            gpuResourceManager.release(jobId);

            // Publish GPU released event
            publishEvent(GpuLifecycleEvent.gpuReleased(this, jobId, hold.serviceType(),
                    hold.device(), heldMs));

            // Restore previously evicted services for this job
            restoreEvictedBy(jobId);

            log.info("GPU released for job '{}' (remaining job holds: {})",
                    jobId, activeJobHolds.size());
        } finally {
            acquisitionLock.unlock();
        }
    }

    /**
     * Check if a specific job currently holds GPU resources.
     */
    public boolean hasJobGpuHold(String jobId) {
        return activeJobHolds.containsKey(jobId);
    }

    /**
     * Get all active job GPU holds for monitoring.
     */
    public Map<String, JobGpuHold> getActiveJobHolds() {
        return Collections.unmodifiableMap(activeJobHolds);
    }

    // ==================== Service Lifecycle Operations ====================

    /**
     * Evict a service — suspend its subprocess and release its GPU reservation.
     *
     * <p>Only a registered managed service that is running can be evicted, and only its own
     * (singleton) row is released: rows of jobs with the same service type belong to children that
     * nothing here suspends. A service that can't be suspended keeps its row — its memory is still in
     * use.</p>
     *
     * @param targetService the service to evict
     * @param requester the service requesting the eviction (for logging)
     * @return true if the service was successfully evicted
     */
    private boolean evictService(String targetService, String requester) {
        log.info("Evicting '{}' (requested by '{}')", targetService, requester);

        ManagedService service = managedServices.get(targetService);
        if (service == null) {
            log.warn("No managed service registered for '{}' — not evictable, keeping its reservation",
                    targetService);
            return false;
        }

        if (!service.isRunning()) {
            log.warn("Service '{}' is not running — not evictable, keeping its reservation", targetService);
            return false;
        }

        // Suspend the service (this should stop the subprocess and set a preemption flag)
        String reason = String.format("Preempted by higher-priority service '%s'", requester);
        boolean suspended;
        try {
            suspended = service.suspend(reason);
        } catch (RuntimeException e) {
            log.error("FAILED to suspend service '{}' — keeping its reservation", targetService, e);
            return false;
        }

        if (!suspended) {
            log.error("FAILED to suspend service '{}'. The service may still be using GPU memory — " +
                    "keeping its reservation.", targetService);
            return false;
        }

        // Wait for the service to actually stop
        boolean stopped = waitForServiceStop(service, evictionTimeoutSeconds);
        if (!stopped) {
            log.error("Service '{}' did not stop within {}s after suspension. " +
                    "GPU memory may not be fully released.", targetService, evictionTimeoutSeconds);
        }

        // Release the service's own reservation
        gpuResourceManager.release(targetService);

        log.info("Service '{}' evicted successfully (stopped={})", targetService, stopped);

        // Publish service evicted event
        publishEvent(GpuLifecycleEvent.serviceEvicted(this, targetService, requester));

        return true;
    }

    /**
     * Restore a previously evicted service.
     */
    private void restoreService(String serviceType) {
        ManagedService service = managedServices.get(serviceType);
        if (service == null) {
            log.warn("No managed service registered for '{}' — cannot restore", serviceType);
            return;
        }

        if (service.isRunning()) {
            log.info("Service '{}' is already running — no restore needed", serviceType);
            return;
        }

        log.info("Restoring service '{}'...", serviceType);

        // Determine device for the restored service
        GpuDevice device = resolveTargetDevice(serviceType, null);
        if (device != null) {
            long budget = gpuResourceManager.getMemoryBudget(serviceType);
            boolean reserved = false;
            if (gpuResourceManager.canFit(serviceType, device)) {
                // Reserve GPU memory before resuming
                try {
                    reserved = gpuResourceManager.reserve(serviceType, device, budget);
                } catch (IllegalStateException e) {
                    log.debug("Reservation for restored service '{}' lost a race: {}", serviceType, e.getMessage());
                }
            }
            if (!reserved) {
                // Resume anyway — the service may fall back to CPU or use less memory — but the ledger
                // still records what it may use, so no later admission counts that memory as free
                log.warn("Cannot reserve {}MB for restored service '{}' on {} — resuming it anyway " +
                                "with an over-committed reservation",
                        budget / (1024 * 1024), serviceType, device.name());
                gpuResourceManager.reserveOverCommit(serviceType, serviceType, device, budget);
            }
        }

        boolean resumed = service.resume();
        if (resumed) {
            log.info("Service '{}' restored successfully", serviceType);
            publishEvent(GpuLifecycleEvent.serviceRestored(this, serviceType, device));
        } else {
            log.warn("Failed to restore service '{}'", serviceType);
            // Release the reservation if resume failed
            gpuResourceManager.release(serviceType);
        }
    }

    /** Restore every service evicted for {@code acquirerId} (a service type or a jobId). */
    private void restoreEvictedBy(String acquirerId) {
        restoreServices(acquirerId, evictedByAcquirer.remove(acquirerId));
    }

    /** Restore services evicted for {@code acquirerId}; one failed restore doesn't stop the others. */
    private void restoreServices(String acquirerId, List<String> evicted) {
        if (evicted == null || evicted.isEmpty()) {
            return;
        }
        log.info("Restoring {} service(s) evicted for '{}': {}", evicted.size(), acquirerId, evicted);
        for (String evictedService : evicted) {
            try {
                restoreService(evictedService);
            } catch (RuntimeException e) {
                log.error("Failed to restore service '{}' (evicted for '{}')", evictedService, acquirerId, e);
            }
        }
    }

    /** Remember what {@code acquirerId} evicted, so its release restores it. */
    private void recordEvictions(String acquirerId, List<String> evicted) {
        if (!evicted.isEmpty()) {
            evictedByAcquirer.merge(acquirerId, evicted, (prior, added) -> {
                List<String> all = new ArrayList<>(prior);
                all.addAll(added);
                return all;
            });
        }
    }

    /**
     * Whether eviction may free {@code reservation}: only the singleton row of a registered managed
     * service that is running — suspending that service is what frees the memory.
     */
    private boolean isEvictable(GpuResourceManager.GpuReservation reservation) {
        if (!GpuResourceManager.isSingletonRow(reservation)) {
            return false;
        }
        ManagedService service = managedServices.get(reservation.serviceType());
        return service != null && service.isRunning();
    }

    /**
     * Wait for a service to stop its subprocess.
     */
    private boolean waitForServiceStop(ManagedService service, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);
        while (System.currentTimeMillis() < deadline) {
            if (!service.isRunning()) {
                return true;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !service.isRunning();
    }

    // ==================== Device Resolution ====================

    /**
     * Resolve the target GPU device for a service.
     * Checks device routing config first, then falls back to auto-selection.
     */
    private GpuDevice resolveTargetDevice(String serviceType, GpuDevice preferred) {
        GpuDevice explicit = resolveExplicitDevice(serviceType, preferred);
        if (explicit != null) {
            return explicit;
        }

        // Auto-select: use findBestDevice from GPU resource manager
        return gpuResourceManager.findBestDevice(serviceType, this::isEvictable).orElse(
                // Ultimate fallback: largest device
                gpuResourceManager.getLargestDevice().orElse(null));
    }

    /**
     * The device a service is explicitly placed on: {@code preferred}, else the device routing
     * config's device, else null (auto-select).
     */
    private GpuDevice resolveExplicitDevice(String serviceType, GpuDevice preferred) {
        // Use explicit preference if provided
        if (preferred != null) {
            return preferred;
        }

        // Check device routing config for a pre-configured device
        if (deviceRoutingConfigService != null && deviceRoutingConfigService.isEnabled()) {
            DeviceRoutingConfig.ServiceDeviceConfig sdConfig =
                    deviceRoutingConfigService.getServiceConfig(serviceType);
            if (sdConfig != null && sdConfig.cudaDeviceId() != null) {
                Optional<GpuDevice> routed =
                        gpuResourceManager.getDeviceByCudaRuntimeIndex(sdConfig.cudaDeviceId());
                if (routed.isPresent()) {
                    log.debug("Device routing config directs '{}' to CUDA device {} ({})",
                            serviceType, sdConfig.cudaDeviceId(), routed.get().name());
                    return routed.get();
                }
            }
        }
        return null;
    }

    // ==================== Status ====================

    /**
     * Get lifecycle manager status for monitoring.
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("running", running.get());

        List<Map<String, Object>> services = new ArrayList<>();
        for (ManagedService service : managedServices.values()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("serviceType", service.getServiceType());
            s.put("running", service.isRunning());
            s.put("suspended", service.isSuspended());
            s.put("hasReservation", gpuResourceManager.hasReservationForService(service.getServiceType()));

            List<GpuResourceManager.GpuReservation> reservations =
                    gpuResourceManager.getReservationsForService(service.getServiceType());
            if (!reservations.isEmpty()) {
                List<Map<String, Object>> reservationList = new ArrayList<>();
                for (GpuResourceManager.GpuReservation r : reservations) {
                    Map<String, Object> rm = new LinkedHashMap<>();
                    rm.put("reservationId", r.reservationId());
                    rm.put("reservedMb", r.reservedMb());
                    rm.put("device", r.device().name());
                    rm.put("priority", r.priority());
                    reservationList.add(rm);
                }
                s.put("reservations", reservationList);
            }

            services.add(s);
        }
        status.put("managedServices", services);
        status.put("evictionMap", evictedByAcquirer);
        status.put("gpuResources", gpuResourceManager.getStatus());

        // Job-level GPU holds
        List<Map<String, Object>> jobHolds = new ArrayList<>();
        for (JobGpuHold hold : activeJobHolds.values()) {
            Map<String, Object> jh = new LinkedHashMap<>();
            jh.put("jobId", hold.jobId());
            jh.put("serviceType", hold.serviceType());
            jh.put("device", hold.device().name());
            jh.put("acquiredAt", hold.acquiredAt().toString());
            jh.put("heldForMs", java.time.Duration.between(hold.acquiredAt(), Instant.now()).toMillis());
            jh.put("description", hold.description());
            jobHolds.add(jh);
        }
        status.put("activeJobHolds", jobHolds);
        status.put("totalActiveJobs", activeJobHolds.size());

        return status;
    }

    // ==================== Event Publishing ====================

    /**
     * Publish a GPU lifecycle event. Silently ignores if no event publisher is configured.
     */
    private void publishEvent(GpuLifecycleEvent event) {
        if (eventPublisher != null) {
            try {
                eventPublisher.publishEvent(event);
            } catch (Exception e) {
                log.warn("Failed to publish GPU lifecycle event {}", event.getEventType(), e);
            }
        }
    }

    // ==================== Stale Job Detection ====================

    /**
     * Scheduled task that detects jobs holding GPU resources for longer than the
     * configured threshold. Logs warnings for stale jobs and publishes events
     * so monitoring systems can alert operators.
     *
     * <p>Runs every 60 seconds. Only active when the lifecycle manager is running.</p>
     */
    @Scheduled(fixedDelayString = "${kompile.gpu.stale-job-check-interval-ms:60000}")
    public void detectStaleJobs() {
        if (!running.get() || activeJobHolds.isEmpty()) {
            return;
        }

        Instant now = Instant.now();

        for (JobGpuHold hold : activeJobHolds.values()) {
            if (isStale(hold, now, staleJobThresholdSeconds)) {
                long heldMs = Duration.between(hold.acquiredAt(), now).toMillis();
                log.warn("STALE GPU HOLD DETECTED: jobId='{}', service='{}', device='{}', " +
                                "heldFor={}ms (threshold={}s), desc='{}'",
                        hold.jobId(), hold.serviceType(), hold.device().name(),
                        heldMs, staleJobThresholdSeconds, hold.description());

                publishEvent(GpuLifecycleEvent.staleJobDetected(this, hold.jobId(),
                        hold.serviceType(), hold.device(), heldMs));
            }
        }
    }

    static boolean isStale(JobGpuHold hold, Instant now, long thresholdSeconds) {
        return hold.lifetime() != HoldLifetime.LONG_LIVED
                && hold.acquiredAt().isBefore(now.minusSeconds(thresholdSeconds));
    }

    /**
     * Get the configured eviction timeout in seconds.
     */
    public int getEvictionTimeoutSeconds() {
        return evictionTimeoutSeconds;
    }

    /**
     * Get the configured resume timeout in seconds.
     */
    public int getResumeTimeoutSeconds() {
        return resumeTimeoutSeconds;
    }

    /**
     * Get the configured stale job threshold in seconds.
     */
    public long getStaleJobThresholdSeconds() {
        return staleJobThresholdSeconds;
    }

    /**
     * Convenience: acquire GPU for VLM with job-level tracking.
     *
     * @param jobId the VLM test task identifier
     * @return the GPU device reserved for VLM
     */
    public GpuDevice acquireGpuForVlm(String jobId) {
        return acquireGpuForJob(jobId, DeviceRoutingConfig.SERVICE_VLM, "VLM test: " + jobId);
    }

    /**
     * Convenience: release GPU for a VLM job.
     *
     * @param jobId the VLM test task identifier
     */
    public void releaseGpuForVlm(String jobId) {
        releaseGpuForJob(jobId);
    }

    /**
     * Convenience: acquire GPU for an ingest job.
     *
     * @param jobId the ingest task identifier
     * @param fileName the file being ingested (for description)
     * @return the GPU device reserved, or null if GPU is not needed for ingest
     */
    public GpuDevice acquireGpuForIngest(String jobId, String fileName) {
        return acquireGpuForJob(jobId, DeviceRoutingConfig.SERVICE_INGEST, "Ingest: " + fileName);
    }

    /**
     * Convenience: release GPU for an ingest job.
     *
     * @param jobId the ingest task identifier
     */
    public void releaseGpuForIngest(String jobId) {
        releaseGpuForJob(jobId);
    }

    /**
     * Convenience: acquire GPU for vector population.
     *
     * @param jobId the vector population task identifier
     * @return the GPU device reserved
     */
    public GpuDevice acquireGpuForVectorPopulation(String jobId) {
        return acquireGpuForJob(jobId, DeviceRoutingConfig.SERVICE_VECTOR_POPULATION,
                "Vector population: " + jobId);
    }

    /**
     * Convenience: release GPU for vector population.
     *
     * @param jobId the vector population task identifier
     */
    public void releaseGpuForVectorPopulation(String jobId) {
        releaseGpuForJob(jobId);
    }

    /**
     * Convenience: acquire GPU for model initialization.
     *
     * @param jobId the model init task identifier
     * @return the GPU device reserved
     */
    public GpuDevice acquireGpuForModelInit(String jobId) {
        return acquireGpuForJob(jobId, DeviceRoutingConfig.SERVICE_MODEL_INIT,
                "Model init: " + jobId);
    }

    /**
     * Convenience: release GPU for model initialization.
     *
     * @param jobId the model init task identifier
     */
    public void releaseGpuForModelInit(String jobId) {
        releaseGpuForJob(jobId);
    }

    /**
     * @deprecated Use {@link #acquireGpuForVlm(String)} with a jobId instead.
     * Kept for backward compatibility during transition.
     */
    @Deprecated
    public GpuDevice acquireGpuForVlm() {
        return acquireGpu(DeviceRoutingConfig.SERVICE_VLM);
    }

    /**
     * @deprecated Use {@link #releaseGpuForVlm(String)} with a jobId instead.
     * Kept for backward compatibility during transition.
     */
    @Deprecated
    public void releaseGpuForVlm() {
        releaseGpu(DeviceRoutingConfig.SERVICE_VLM);
    }
}
