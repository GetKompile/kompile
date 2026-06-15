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

import ai.kompile.app.config.IngestConfiguration;
import ai.kompile.app.ingest.domain.IngestEvent;
import ai.kompile.app.ingest.service.IngestEventService;
import ai.kompile.app.web.dto.IngestProgressUpdate;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Memory watchdog service that monitors system memory usage and stops
 * running indexing jobs when memory exceeds the configured threshold (80% by default).
 *
 * This service runs a background thread that periodically checks memory usage
 * and signals jobs to stop gracefully when memory pressure is detected.
 */
@Service
public class MemoryWatchdogService {

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected MemoryWatchdogService() {}


    private static final Logger logger = LoggerFactory.getLogger(MemoryWatchdogService.class);

    @Autowired
    private IngestConfiguration ingestConfiguration;
    @Autowired(required = false)
    private SimpMessagingTemplate messagingTemplate;
    @Autowired(required = false)
    private IngestEventService ingestEventService;

    // Track jobs that should be stopped
    private final Set<String> jobsToStop = ConcurrentHashMap.newKeySet();

    // Track jobs that have been forcibly killed (to prevent duplicate kill events)
    private final Set<String> jobsKilled = ConcurrentHashMap.newKeySet();

    // Track all currently running jobs for potential stopping
    private final Map<String, JobInfo> runningJobs = new ConcurrentHashMap<>();

    // Watchdog state
    private final AtomicBoolean watchdogEnabled = new AtomicBoolean(true);
    private final AtomicBoolean memoryPressureDetected = new AtomicBoolean(false);
    private final AtomicBoolean killThresholdExceeded = new AtomicBoolean(false);

    private ScheduledExecutorService watchdogExecutor;

    // Dedicated executor for async WebSocket sends - prevents blocking watchdog thread
    private final ExecutorService webSocketExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "watchdog-websocket");
        t.setDaemon(true);
        return t;
    });
    private volatile Instant lastMemoryPressureTime;
    private volatile Instant lastKillThresholdTime;
    private volatile double lastMemoryUsage;

    // Configuration
    private long checkIntervalMs = 2000; // Check every 2 seconds
    private volatile int consecutiveHighMemoryChecks = 0;
    private volatile int consecutiveKillThresholdChecks = 0;
    private static final int HIGH_MEMORY_THRESHOLD_CHECKS = 3; // Require 3 consecutive high readings
    private static final int KILL_THRESHOLD_CHECKS = 2; // Require 2 consecutive readings before killing (faster response)

    @Autowired
    public MemoryWatchdogService(
            IngestConfiguration ingestConfiguration,
            @Autowired(required = false) SimpMessagingTemplate messagingTemplate,
            @Autowired(required = false) IngestEventService ingestEventService
    ) {
        this.ingestConfiguration = ingestConfiguration;
        this.messagingTemplate = messagingTemplate;
        this.ingestEventService = ingestEventService;
    }

    @PostConstruct
    public void init() {
        startWatchdog();
        int killThreshold = ingestConfiguration.getMemoryKillThresholdPercent();
        logger.info("Memory watchdog service initialized. Threshold: {}%, Critical: {}%, Kill: {}%{}",
                ingestConfiguration.getMemoryThresholdPercent(),
                ingestConfiguration.getMemoryCriticalPercent(),
                killThreshold,
                killThreshold == 0 ? " (disabled)" : "");
    }

    @PreDestroy
    public void shutdown() {
        stopWatchdog();
    }

    /**
     * Starts the memory watchdog background thread.
     */
    public void startWatchdog() {
        if (watchdogExecutor != null && !watchdogExecutor.isShutdown()) {
            return;
        }

        watchdogExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "memory-watchdog");
            t.setDaemon(true);
            return t;
        });

        watchdogExecutor.scheduleAtFixedRate(
                this::checkMemoryAndStopJobsIfNeeded,
                checkIntervalMs,
                checkIntervalMs,
                TimeUnit.MILLISECONDS
        );

        logger.info("Memory watchdog started with {}ms check interval", checkIntervalMs);
    }

    /**
     * Stops the memory watchdog.
     */
    public void stopWatchdog() {
        if (watchdogExecutor != null) {
            watchdogExecutor.shutdown();
            try {
                if (!watchdogExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    watchdogExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                watchdogExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            logger.info("Memory watchdog stopped");
        }

        // Also shutdown the WebSocket executor
        webSocketExecutor.shutdown();
        try {
            if (!webSocketExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                webSocketExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            webSocketExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Registers a job as running. Call this when a job starts.
     */
    public void registerJob(String taskId, String fileName) {
        registerJob(taskId, fileName, null);
    }

    /**
     * Registers a job as running with a cancellation callback.
     * The callback will be invoked if the job needs to be forcibly killed due to memory pressure.
     *
     * @param taskId   The task identifier
     * @param fileName The file being processed
     * @param cancellationCallback A runnable to invoke when the job should be killed (may be null)
     */
    public void registerJob(String taskId, String fileName, Runnable cancellationCallback) {
        runningJobs.put(taskId, new JobInfo(taskId, fileName, Instant.now(), cancellationCallback));
        logger.debug("Registered job {} for memory monitoring (has cancellation callback: {})",
                taskId, cancellationCallback != null);
    }

    /**
     * Unregisters a job. Call this when a job completes or fails.
     */
    public void unregisterJob(String taskId) {
        runningJobs.remove(taskId);
        jobsToStop.remove(taskId);
        jobsKilled.remove(taskId);
        logger.debug("Unregistered job {} from memory monitoring", taskId);
    }

    /**
     * Checks if a specific job should stop due to memory pressure.
     * Jobs should call this periodically (e.g., between batches) and stop gracefully if true.
     */
    public boolean shouldJobStop(String taskId) {
        return jobsToStop.contains(taskId);
    }

    /**
     * Checks if a specific job has been forcibly killed due to memory kill threshold.
     * Unlike shouldJobStop, this indicates the job must terminate immediately.
     */
    public boolean isJobKilled(String taskId) {
        return jobsKilled.contains(taskId);
    }

    /**
     * Gets the set of jobs that have been forcibly killed.
     */
    public Set<String> getJobsKilled() {
        return Set.copyOf(jobsKilled);
    }

    /**
     * Checks if there is currently memory pressure affecting jobs.
     */
    public boolean isMemoryPressureDetected() {
        return memoryPressureDetected.get();
    }

    /**
     * Gets the current memory usage percentage.
     */
    public double getCurrentMemoryUsage() {
        return lastMemoryUsage;
    }

    /**
     * Gets information about currently running jobs.
     */
    public Map<String, JobInfo> getRunningJobs() {
        return Map.copyOf(runningJobs);
    }

    /**
     * Gets the set of jobs marked for stopping.
     */
    public Set<String> getJobsMarkedForStop() {
        return Set.copyOf(jobsToStop);
    }

    /**
     * Gets the last time memory pressure was detected.
     */
    public Instant getLastMemoryPressureTime() {
        return lastMemoryPressureTime;
    }

    /**
     * Enables or disables the watchdog.
     */
    public void setWatchdogEnabled(boolean enabled) {
        watchdogEnabled.set(enabled);
        logger.info("Memory watchdog {}", enabled ? "enabled" : "disabled");
    }

    /**
     * Checks if the watchdog is enabled.
     */
    public boolean isWatchdogEnabled() {
        return watchdogEnabled.get();
    }

    /**
     * Sets the check interval in milliseconds.
     */
    public void setCheckIntervalMs(long intervalMs) {
        this.checkIntervalMs = Math.max(500, Math.min(intervalMs, 60000));
        // Restart watchdog with new interval
        stopWatchdog();
        startWatchdog();
    }

    /**
     * Gets watchdog status for monitoring.
     */
    public WatchdogStatus getStatus() {
        IngestConfiguration.MemoryInfo memInfo = ingestConfiguration.getMemoryInfo();
        return new WatchdogStatus(
                watchdogEnabled.get(),
                memoryPressureDetected.get(),
                killThresholdExceeded.get(),
                memInfo.usagePercent(),
                ingestConfiguration.getMemoryThresholdPercent(),
                ingestConfiguration.getMemoryCriticalPercent(),
                ingestConfiguration.getMemoryKillThresholdPercent(),
                runningJobs.size(),
                jobsToStop.size(),
                jobsKilled.size(),
                lastMemoryPressureTime,
                lastKillThresholdTime,
                checkIntervalMs,
                consecutiveHighMemoryChecks,
                consecutiveKillThresholdChecks
        );
    }

    /**
     * Main watchdog check method - runs periodically.
     */
    private void checkMemoryAndStopJobsIfNeeded() {
        if (!watchdogEnabled.get()) {
            return;
        }

        try {
            IngestConfiguration.MemoryInfo memInfo = ingestConfiguration.getMemoryInfo();
            lastMemoryUsage = memInfo.usagePercent();

            int threshold = ingestConfiguration.getMemoryThresholdPercent();
            int killThreshold = ingestConfiguration.getMemoryKillThresholdPercent();

            // Check kill threshold first (highest priority)
            if (killThreshold > 0 && memInfo.killThresholdExceeded()) {
                consecutiveKillThresholdChecks++;

                if (consecutiveKillThresholdChecks >= KILL_THRESHOLD_CHECKS) {
                    if (!killThresholdExceeded.getAndSet(true)) {
                        logger.error("╔═══════════════════════════════════════════════════════════════╗");
                        logger.error("║  CRITICAL: MEMORY KILL THRESHOLD EXCEEDED                     ║");
                        logger.error("║  Memory: {}% >= {}% (sustained for {} checks)                 ║",
                                String.format("%.1f", memInfo.usagePercent()), killThreshold, consecutiveKillThresholdChecks);
                        logger.error("║  Jobs will be FORCIBLY KILLED to protect system stability     ║");
                        logger.error("╚═══════════════════════════════════════════════════════════════╝");
                        lastKillThresholdTime = Instant.now();
                    }

                    // Forcibly kill all running jobs and fire audit events
                    killJobsDueToMemory(memInfo, killThreshold);
                }

                // Also trigger normal pressure detection
                consecutiveHighMemoryChecks++;
                memoryPressureDetected.set(true);
                lastMemoryPressureTime = Instant.now();

            } else if (memInfo.usagePercent() >= threshold) {
                // Normal threshold exceeded - mark for graceful stop
                consecutiveHighMemoryChecks++;
                consecutiveKillThresholdChecks = 0; // Reset kill counter

                if (consecutiveHighMemoryChecks >= HIGH_MEMORY_THRESHOLD_CHECKS) {
                    if (!memoryPressureDetected.getAndSet(true)) {
                        logger.warn("Memory pressure detected: {}% >= {}% (sustained for {} checks)",
                                String.format("%.1f", memInfo.usagePercent()), threshold, consecutiveHighMemoryChecks);
                        lastMemoryPressureTime = Instant.now();
                    }

                    // Mark all running jobs for stopping (graceful)
                    markJobsForStopping(memInfo);
                }
            } else {
                // Memory is OK, reset counters
                if (consecutiveHighMemoryChecks > 0) {
                    logger.info("Memory pressure relieved: {}% < {}%", String.format("%.1f", memInfo.usagePercent()), threshold);
                }
                if (consecutiveKillThresholdChecks > 0) {
                    logger.info("Memory kill threshold no longer exceeded: {}% < {}%",
                            String.format("%.1f", memInfo.usagePercent()), killThreshold);
                }
                consecutiveHighMemoryChecks = 0;
                consecutiveKillThresholdChecks = 0;
                memoryPressureDetected.set(false);
                killThresholdExceeded.set(false);

                // Clear stop signals for jobs that haven't stopped yet
                // (they may continue if memory is now OK)
                // Note: We keep the signals so jobs that are mid-batch can still stop gracefully
            }

        } catch (Exception e) {
            logger.error("Error in memory watchdog check", e);
        }
    }

    /**
     * Marks running jobs for stopping due to memory pressure (graceful stop).
     */
    private void markJobsForStopping(IngestConfiguration.MemoryInfo memInfo) {
        if (runningJobs.isEmpty()) {
            logger.debug("No running jobs to stop");
            return;
        }

        // Sort jobs by start time (oldest first) - stop oldest jobs first
        runningJobs.values().stream()
                .sorted((a, b) -> a.startTime().compareTo(b.startTime()))
                .forEach(job -> {
                    if (!jobsToStop.contains(job.taskId())) {
                        jobsToStop.add(job.taskId());
                        logger.warn("Marking job {} ({}) for stopping due to memory pressure ({}%)",
                                job.taskId(), job.fileName(), String.format("%.1f", memInfo.usagePercent()));

                        // Send notification via WebSocket if available
                        notifyJobStopping(job, memInfo);
                    }
                });

        // Trigger GC to try to free memory
        System.gc();
    }

    /**
     * Forcibly kills running jobs due to memory exceeding kill threshold.
     * This fires audit log events for each killed job and invokes cancellation callbacks.
     */
    private void killJobsDueToMemory(IngestConfiguration.MemoryInfo memInfo, int killThreshold) {
        if (runningJobs.isEmpty()) {
            logger.debug("No running jobs to kill");
            return;
        }

        // Kill all running jobs
        runningJobs.values().forEach(job -> {
            // Check if already killed (avoid duplicate events)
            if (jobsKilled.contains(job.taskId())) {
                logger.debug("Job {} already killed, skipping", job.taskId());
                return;
            }

            // Mark as killed
            jobsKilled.add(job.taskId());
            jobsToStop.add(job.taskId()); // Also mark for stop in case job checks this

            logger.error("KILLING job {} ({}) - Memory: {}% >= {}% kill threshold",
                    job.taskId(), job.fileName(), String.format("%.1f", memInfo.usagePercent()), killThreshold);

            // Fire audit log event FIRST (before cancellation callback)
            fireMemoryKillAuditEvent(job, memInfo, killThreshold);

            // Send WebSocket notification
            notifyJobKilled(job, memInfo, killThreshold);

            // CRITICAL: Invoke cancellation callback to actually stop the job
            // This allows the DocumentIngestService to cancel its pipeline
            if (job.cancellationCallback() != null) {
                logger.info("Invoking cancellation callback for job {} to force termination", job.taskId());
                try {
                    job.cancellationCallback().run();
                    logger.info("Cancellation callback invoked successfully for job {}", job.taskId());
                } catch (Exception e) {
                    logger.error("Failed to invoke cancellation callback for job {}: {}",
                            job.taskId(), e.getMessage(), e);
                }
            } else {
                logger.warn("No cancellation callback registered for job {} - job may continue running until it checks kill flag",
                        job.taskId());
            }
        });

        // Aggressive GC to try to reclaim memory
        System.gc();
        System.runFinalization();
        System.gc();
    }

    /**
     * Fires an audit log event for a job being killed due to memory pressure.
     */
    private void fireMemoryKillAuditEvent(JobInfo job, IngestConfiguration.MemoryInfo memInfo, int killThreshold) {
        if (ingestEventService == null || !ingestEventService.isEnabled()) {
            logger.warn("IngestEventService not available - memory kill audit event NOT logged for job {}", job.taskId());
            return;
        }

        try {
            // Build details JSON with memory state
            String details = String.format(
                    "{\"memoryUsedMB\": %d, \"memoryMaxMB\": %d, \"memoryFreeMB\": %d, " +
                    "\"killThreshold\": %d, \"thresholdPercent\": %d, \"criticalPercent\": %d, " +
                    "\"jobStartTime\": \"%s\", \"killTime\": \"%s\"}",
                    memInfo.usedMB(), memInfo.maxMB(), memInfo.freeMB(),
                    killThreshold,
                    ingestConfiguration.getMemoryThresholdPercent(),
                    ingestConfiguration.getMemoryCriticalPercent(),
                    job.startTime(), Instant.now()
            );

            // Determine the phase - since we don't track this in JobInfo, use a generic phase
            // The actual phase will be captured by DocumentIngestService when it handles the kill
            IngestEvent.IngestPhase killedPhase = IngestEvent.IngestPhase.EMBEDDING; // Most likely phase for memory issues

            ingestEventService.logMemoryKilled(
                    job.taskId(),
                    job.fileName(),
                    killedPhase,
                    memInfo.usagePercent(),
                    killThreshold,
                    0, // itemsProcessed - will be updated by the job when it catches the kill
                    details
            );

            logger.info("Audit event logged for memory-killed job: {}", job.taskId());

        } catch (Exception e) {
            logger.error("Failed to log memory kill audit event for job {}: {}", job.taskId(), e.getMessage(), e);
        }
    }

    /**
     * Sends a WebSocket notification that a job is being forcibly killed.
     * Uses async executor to avoid blocking the watchdog thread.
     */
    private void notifyJobKilled(JobInfo job, IngestConfiguration.MemoryInfo memInfo, int killThreshold) {
        if (messagingTemplate == null) {
            return;
        }

        // Offload WebSocket send to dedicated executor - don't block watchdog
        webSocketExecutor.submit(() -> {
            try {
                IngestProgressUpdate update = IngestProgressUpdate.memoryKilled(
                        job.taskId(),
                        job.fileName(),
                        String.format("CRITICAL: Job forcibly killed - memory usage %.1f%% exceeded kill threshold %d%%",
                                memInfo.usagePercent(), killThreshold)
                );

                messagingTemplate.convertAndSend("/topic/ingest/" + job.taskId(), update);
                messagingTemplate.convertAndSend("/topic/ingest/all", update);
            } catch (Exception e) {
                logger.debug("Failed to send WebSocket notification for job killed", e);
            }
        });
    }

    /**
     * Sends a WebSocket notification that a job is being stopped.
     * Uses async executor to avoid blocking the watchdog thread.
     */
    private void notifyJobStopping(JobInfo job, IngestConfiguration.MemoryInfo memInfo) {
        if (messagingTemplate == null) {
            return;
        }

        // Offload WebSocket send to dedicated executor - don't block watchdog
        webSocketExecutor.submit(() -> {
            try {
                IngestProgressUpdate update = IngestProgressUpdate.memoryPressure(
                        job.taskId(),
                        job.fileName(),
                        String.format("Job stopping due to memory pressure (%.1f%% > %d%% threshold)",
                                memInfo.usagePercent(), ingestConfiguration.getMemoryThresholdPercent())
                );

                messagingTemplate.convertAndSend("/topic/ingest/" + job.taskId(), update);
                messagingTemplate.convertAndSend("/topic/ingest/all", update);
            } catch (Exception e) {
                logger.debug("Failed to send WebSocket notification for job stopping", e);
            }
        });
    }

    /**
     * Information about a running job.
     */
    public record JobInfo(
            String taskId,
            String fileName,
            Instant startTime,
            Runnable cancellationCallback
    ) {
        /**
         * Legacy constructor without cancellation callback (for backward compatibility).
         */
        public JobInfo(String taskId, String fileName, Instant startTime) {
            this(taskId, fileName, startTime, null);
        }
    }

    /**
     * Watchdog status for monitoring endpoints.
     */
    public record WatchdogStatus(
            boolean enabled,
            boolean memoryPressureDetected,
            boolean killThresholdExceeded,
            double currentMemoryUsagePercent,
            int memoryThresholdPercent,
            int memoryCriticalPercent,
            int memoryKillThresholdPercent,
            int runningJobCount,
            int jobsMarkedForStopCount,
            int jobsKilledCount,
            Instant lastMemoryPressureTime,
            Instant lastKillThresholdTime,
            long checkIntervalMs,
            int consecutiveHighMemoryChecks,
            int consecutiveKillThresholdChecks
    ) {}
}
