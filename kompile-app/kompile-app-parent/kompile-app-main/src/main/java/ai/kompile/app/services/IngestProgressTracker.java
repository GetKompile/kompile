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

import ai.kompile.app.ingest.domain.JobLogEntry.LogLevel;
import ai.kompile.app.ingest.domain.JobLogEntry.LogSource;
import ai.kompile.app.ingest.service.JobLogService;
import ai.kompile.app.web.dto.IngestProgressUpdate;
import ai.kompile.app.web.dto.IngestProgressUpdate.FailureReason;
import ai.kompile.app.web.dto.IngestProgressUpdate.IngestLogEntry;
import ai.kompile.app.web.dto.IngestProgressUpdate.IngestPhase;
import ai.kompile.app.web.dto.IngestProgressUpdate.IngestStats;
import ai.kompile.app.web.dto.IngestProgressUpdate.IngestStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Centralized service for tracking document ingest progress.
 * Provides real-time updates via WebSocket and maintains status for REST queries.
 *
 * This service is used by both synchronous and asynchronous upload flows to ensure
 * all document processing is visible to the frontend.
 */
@Service
public class IngestProgressTracker implements DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(IngestProgressTracker.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final JobLogService jobLogService;
    private final Map<String, IngestProgressUpdate> activeTasks = new ConcurrentHashMap<>();
    private final Map<String, Long> taskStartTimes = new ConcurrentHashMap<>();
    private final Map<String, Long> taskFactSheetIds = new ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.atomic.AtomicLong> logSequenceCounters = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor();

    // Keep completed tasks for 5 minutes before cleanup
    private static final long COMPLETED_TASK_RETENTION_MS = 5 * 60 * 1000;

    public IngestProgressTracker(
            @org.springframework.beans.factory.annotation.Autowired(required = false) SimpMessagingTemplate messagingTemplate,
            @org.springframework.beans.factory.annotation.Autowired(required = false) JobLogService jobLogService) {
        this.messagingTemplate = messagingTemplate; // May be null if WebSocket not configured
        this.jobLogService = jobLogService; // May be null if job logging not configured

        // Schedule periodic cleanup of old completed tasks
        cleanupScheduler.scheduleAtFixedRate(this::cleanupOldTasks, 1, 1, TimeUnit.MINUTES);

        // Warn if WebSocket is not available
        if (messagingTemplate == null) {
            logger.warn("IngestProgressTracker initialized WITHOUT WebSocket support (SimpMessagingTemplate is null) - " +
                       "UI will NOT receive real-time progress updates!");
        } else {
            logger.info("IngestProgressTracker initialized with WebSocket support enabled");
        }
    }

    /**
     * Properly shuts down the cleanup scheduler to prevent resource leaks.
     */
    @PreDestroy
    @Override
    public void destroy() {
        logger.info("Shutting down IngestProgressTracker cleanup scheduler...");
        cleanupScheduler.shutdown();
        try {
            if (!cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("Cleanup scheduler did not terminate in time, forcing shutdown");
                cleanupScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            logger.warn("Interrupted while waiting for cleanup scheduler shutdown");
            cleanupScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("IngestProgressTracker shutdown complete");
    }

    /**
     * Generates a unique task ID for tracking.
     */
    public String generateTaskId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Starts tracking a new ingest task.
     *
     * @return true if this was the first call for this taskId, false if already started
     */
    public boolean startTask(String taskId, String fileName) {
        return startTask(taskId, fileName, null);
    }

    /**
     * Starts tracking a new ingest task associated with a specific fact sheet.
     * This method is idempotent - calling it multiple times for the same taskId
     * will only send the QUEUED event once (on the first call).
     *
     * @return true if this was the first call for this taskId, false if already started
     */
    public boolean startTask(String taskId, String fileName, Long factSheetId) {
        // Use putIfAbsent to make this idempotent - only the first call will succeed
        Long previousStartTime = taskStartTimes.putIfAbsent(taskId, System.currentTimeMillis());

        if (previousStartTime != null) {
            // Task was already started - update factSheetId if provided but don't send duplicate QUEUED event
            if (factSheetId != null) {
                taskFactSheetIds.putIfAbsent(taskId, factSheetId);
            }
            logger.debug("[Task {}] Already started, skipping duplicate startTask call", taskId);
            return false;
        }

        // First call for this taskId - send QUEUED event
        if (factSheetId != null) {
            taskFactSheetIds.put(taskId, factSheetId);
        }
        sendProgress(IngestProgressUpdate.queued(taskId, fileName, factSheetId));
        logger.info("[Task {}] Started tracking: {} (factSheetId={})", taskId, fileName, factSheetId);
        return true;
    }

    /**
     * Gets the fact sheet ID associated with a task, if any.
     */
    public Long getTaskFactSheetId(String taskId) {
        return taskFactSheetIds.get(taskId);
    }

    /**
     * Updates progress for a task.
     */
    public void updateProgress(String taskId, String fileName, IngestPhase phase,
                               int progressPercent, String currentStep, String message, IngestStats stats) {
        Long factSheetId = taskFactSheetIds.get(taskId);
        IngestProgressUpdate update = IngestProgressUpdate.progress(
                taskId, fileName, phase, progressPercent, currentStep, message, stats, factSheetId);
        sendProgress(update);
    }

    /**
     * Updates progress using a pre-built IngestProgressUpdate object.
     * Useful for receiving progress updates from subprocesses via HTTP callbacks.
     */
    public void updateProgress(IngestProgressUpdate update) {
        if (update == null) {
            logger.warn("Received null progress update, ignoring");
            return;
        }

        // Track start time if this is a new task
        taskStartTimes.putIfAbsent(update.taskId(), System.currentTimeMillis());

        // Track factSheetId if provided and not already tracked
        if (update.factSheetId() != null) {
            taskFactSheetIds.putIfAbsent(update.taskId(), update.factSheetId());
        }

        // Ensure factSheetId is included if we have it
        IngestProgressUpdate updateWithFactSheet = update;
        if (update.factSheetId() == null) {
            Long factSheetId = taskFactSheetIds.get(update.taskId());
            if (factSheetId != null) {
                // Create a new update with the factSheetId
                updateWithFactSheet = new IngestProgressUpdate(
                        update.taskId(), update.fileName(), update.phase(), update.status(),
                        update.progressPercent(), update.currentStep(), update.message(),
                        update.stats(), update.errorMessage(), update.timestamp(), factSheetId,
                        update.failureReason(), update.restartInfo());
            }
        }

        sendProgress(updateWithFactSheet);
    }

    /**
     * Updates progress with elapsed time calculation.
     */
    public void updateProgressWithTime(String taskId, String fileName, IngestPhase phase,
                                        int progressPercent, String currentStep, String message,
                                        int documentsLoaded, int chunksCreated, int chunksEmbedded,
                                        int documentsIndexed, String loaderUsed, String chunkerUsed,
                                        List<String> processedDocumentIds) {
        long elapsedMs = getElapsedTime(taskId);
        IngestStats stats = IngestStats.builder()
                .documentsLoaded(documentsLoaded)
                .chunksCreated(chunksCreated)
                .chunksEmbedded(chunksEmbedded)
                .chunksIndexed(documentsIndexed)  // documentsIndexed is actually chunks indexed
                .documentsIndexed(documentsIndexed)
                .totalProcessingTimeMs(elapsedMs)
                .loaderUsed(loaderUsed)
                .chunkerUsed(chunkerUsed)
                .processedDocumentIds(processedDocumentIds != null ? processedDocumentIds : List.of())
                .build();
        updateProgress(taskId, fileName, phase, progressPercent, currentStep, message, stats);
    }

    /**
     * Marks a task as completed.
     */
    public void completeTask(String taskId, String fileName, IngestStats finalStats) {
        Long factSheetId = taskFactSheetIds.get(taskId);
        IngestProgressUpdate update = IngestProgressUpdate.completed(taskId, fileName, finalStats, factSheetId);
        sendProgress(update);
        logger.info("[Task {}] Completed: {} - {} docs, {} chunks in {}ms (factSheetId={})",
                taskId, fileName, finalStats.documentsLoaded(), finalStats.chunksCreated(),
                finalStats.totalProcessingTimeMs(), factSheetId);
        scheduleTaskCleanup(taskId);
    }

    /**
     * Marks a task as failed.
     */
    public void failTask(String taskId, String fileName, IngestPhase failedPhase, String errorMessage) {
        failTask(taskId, fileName, failedPhase, errorMessage, FailureReason.UNKNOWN);
    }

    /**
     * Marks a task as failed with a specific failure reason.
     */
    public void failTask(String taskId, String fileName, IngestPhase failedPhase, String errorMessage,
                         FailureReason failureReason) {
        long elapsedMs = getElapsedTime(taskId);
        Long factSheetId = taskFactSheetIds.get(taskId);
        IngestStats stats = IngestStats.builder().totalProcessingTimeMs(elapsedMs).build();
        IngestProgressUpdate update = IngestProgressUpdate.failed(taskId, fileName, failedPhase, errorMessage,
                stats, factSheetId, failureReason);
        sendProgress(update);
        logger.error("[Task {}] Failed at {}: {} - {} (reason={}, factSheetId={})",
                taskId, failedPhase, fileName, errorMessage, failureReason, factSheetId);
        scheduleTaskCleanup(taskId);
    }

    /**
     * Marks a task as failed due to out of memory error in subprocess.
     * This provides a clear OOM indicator to the UI.
     */
    public void failTaskOutOfMemory(String taskId, String fileName, IngestPhase failedPhase, String errorMessage) {
        long elapsedMs = getElapsedTime(taskId);
        Long factSheetId = taskFactSheetIds.get(taskId);
        IngestStats stats = IngestStats.builder().totalProcessingTimeMs(elapsedMs).build();
        IngestProgressUpdate update = IngestProgressUpdate.outOfMemory(taskId, fileName, failedPhase,
                errorMessage, stats, factSheetId);
        sendProgress(update);
        logger.error("[Task {}] OUT OF MEMORY at {}: {} - {} (factSheetId={})",
                taskId, failedPhase, fileName, errorMessage, factSheetId);
        scheduleTaskCleanup(taskId);
    }

    /**
     * Marks a task as cancelled.
     */
    public void cancelTask(String taskId, String fileName, IngestPhase currentPhase, String reason, IngestStats stats) {
        Long factSheetId = taskFactSheetIds.get(taskId);
        IngestProgressUpdate update = IngestProgressUpdate.cancelled(taskId, fileName, currentPhase, reason, stats, factSheetId);
        sendProgress(update);
        logger.warn("[Task {}] Cancelled at {}: {} - {} (factSheetId={})", taskId, currentPhase, fileName, reason, factSheetId);
        scheduleTaskCleanup(taskId);
    }

    /**
     * Notifies the UI that a restart is scheduled for a task after OOM.
     * This keeps the task in IN_PROGRESS state but provides restart info.
     */
    public void notifyRestartScheduled(String taskId, String fileName, IngestPhase currentPhase,
                                        int attemptNumber, int maxAttempts, long nextRestartTimeMs,
                                        String heapSize, boolean heapIncreased,
                                        Integer ompThreads, Integer blasThreads, String memoryAnalysisReason) {
        Long factSheetId = taskFactSheetIds.get(taskId);
        long elapsedMs = getElapsedTime(taskId);

        IngestProgressUpdate.RestartInfo restartInfo = IngestProgressUpdate.RestartInfo.builder()
                .attemptNumber(attemptNumber)
                .maxAttempts(maxAttempts)
                .restartScheduled(true)
                .nextRestartTime(nextRestartTimeMs)
                .currentHeapSize(heapSize)
                .heapIncreased(heapIncreased)
                .ompThreads(ompThreads)
                .blasThreads(blasThreads)
                .memoryAnalysisReason(memoryAnalysisReason)
                .build();

        IngestStats stats = IngestStats.builder()
                .totalProcessingTimeMs(elapsedMs)
                .memoryStatus("RESTARTING")
                .pipelineStatus("RESTARTING")
                .build();

        // Create update with restart info - status stays IN_PROGRESS
        IngestProgressUpdate update = new IngestProgressUpdate(
                taskId,
                fileName,
                currentPhase,
                IngestProgressUpdate.IngestStatus.IN_PROGRESS,
                0, // Reset progress for restart
                String.format("Restart %d/%d scheduled", attemptNumber, maxAttempts),
                String.format("OOM detected - restarting with %s heap in %.1fs",
                        heapSize, (nextRestartTimeMs - System.currentTimeMillis()) / 1000.0),
                stats,
                null, // No error - we're recovering
                java.time.Instant.now(),
                factSheetId,
                null, // No failure reason - we're recovering
                restartInfo
        );

        sendProgress(update);
        logger.info("[Task {}] RESTART SCHEDULED: attempt {}/{}, heap={}, nextRestart={}ms (factSheetId={})",
                taskId, attemptNumber, maxAttempts, heapSize, nextRestartTimeMs, factSheetId);
    }

    /**
     * Notifies the UI that a restart is now executing for a task.
     */
    public void notifyRestartExecuting(String taskId, String fileName, int attemptNumber, int maxAttempts,
                                        String heapSize) {
        Long factSheetId = taskFactSheetIds.get(taskId);

        IngestProgressUpdate.RestartInfo restartInfo = IngestProgressUpdate.RestartInfo.builder()
                .attemptNumber(attemptNumber)
                .maxAttempts(maxAttempts)
                .restartScheduled(false) // No longer scheduled, executing now
                .currentHeapSize(heapSize)
                .build();

        IngestStats stats = IngestStats.builder()
                .memoryStatus("RESTARTING")
                .pipelineStatus("INITIALIZING")
                .build();

        IngestProgressUpdate update = new IngestProgressUpdate(
                taskId,
                fileName,
                IngestPhase.LOADING,
                IngestProgressUpdate.IngestStatus.IN_PROGRESS,
                5, // Small progress to show we're starting
                String.format("Restart attempt %d/%d", attemptNumber, maxAttempts),
                String.format("Launching subprocess with %s heap...", heapSize),
                stats,
                null,
                java.time.Instant.now(),
                factSheetId,
                null,
                restartInfo
        );

        sendProgress(update);
        logger.info("[Task {}] RESTART EXECUTING: attempt {}/{}, heap={} (factSheetId={})",
                taskId, attemptNumber, maxAttempts, heapSize, factSheetId);
    }

    /**
     * Sends a log entry from the subprocess to WebSocket clients.
     * Logs are sent to /topic/ingest/{taskId}/logs and persisted to the database.
     *
     * @param taskId Task ID
     * @param source Source of the log (STDOUT, STDERR, SYSTEM)
     * @param message Log message content
     */
    public void sendLog(String taskId, String source, String message) {
        if (message == null || message.isBlank()) {
            return;
        }

        // Get or create sequence counter for this task
        java.util.concurrent.atomic.AtomicLong seqCounter = logSequenceCounters.computeIfAbsent(
                taskId, k -> new java.util.concurrent.atomic.AtomicLong(0));
        long seq = seqCounter.incrementAndGet();

        IngestLogEntry logEntry;
        if ("STDOUT".equals(source)) {
            logEntry = IngestLogEntry.stdout(taskId, message, seq);
            // Persist to database
            persistLogEntry(taskId, LogLevel.INFO, LogSource.STDOUT, message);
        } else if ("STDERR".equals(source)) {
            logEntry = IngestLogEntry.stderr(taskId, message, seq);
            // Persist to database
            persistLogEntry(taskId, LogLevel.ERROR, LogSource.STDERR, message);
        } else {
            logEntry = IngestLogEntry.system(taskId, "INFO", message, seq);
            // Persist to database
            persistLogEntry(taskId, LogLevel.INFO, LogSource.SYSTEM, message);
        }

        sendLogEntry(logEntry);
    }

    /**
     * Sends a log entry with a specific level.
     */
    public void sendLog(String taskId, String source, String level, String message) {
        if (message == null || message.isBlank()) {
            return;
        }

        java.util.concurrent.atomic.AtomicLong seqCounter = logSequenceCounters.computeIfAbsent(
                taskId, k -> new java.util.concurrent.atomic.AtomicLong(0));
        long seq = seqCounter.incrementAndGet();

        IngestLogEntry logEntry = new IngestLogEntry(taskId, level, source, message, null,
                java.time.Instant.now(), seq);
        sendLogEntry(logEntry);

        // Persist to database
        LogLevel logLevel = mapStringToLogLevel(level);
        LogSource logSource = mapStringToLogSource(source);
        persistLogEntry(taskId, logLevel, logSource, message);
    }

    /**
     * Persists a log entry to the database via JobLogService.
     */
    private void persistLogEntry(String taskId, LogLevel level, LogSource source, String message) {
        if (jobLogService != null && jobLogService.isEnabled()) {
            try {
                jobLogService.logEntry(taskId, level, source, message, null, Thread.currentThread().getName());
            } catch (Exception e) {
                logger.warn("[Task {}] Failed to persist log to database: {}", taskId, e.getMessage());
            }
        }
    }

    /**
     * Maps string log level to LogLevel enum.
     */
    private LogLevel mapStringToLogLevel(String level) {
        if (level == null) return LogLevel.INFO;
        return switch (level.toUpperCase()) {
            case "TRACE" -> LogLevel.TRACE;
            case "DEBUG" -> LogLevel.DEBUG;
            case "WARN", "WARNING" -> LogLevel.WARN;
            case "ERROR" -> LogLevel.ERROR;
            default -> LogLevel.INFO;
        };
    }

    /**
     * Maps string source to LogSource enum.
     */
    private LogSource mapStringToLogSource(String source) {
        if (source == null) return LogSource.APPLICATION;
        return switch (source.toUpperCase()) {
            case "STDOUT" -> LogSource.STDOUT;
            case "STDERR" -> LogSource.STDERR;
            case "SYSTEM" -> LogSource.SYSTEM;
            default -> LogSource.APPLICATION;
        };
    }

    /**
     * Sends an IngestLogEntry to WebSocket clients.
     */
    private void sendLogEntry(IngestLogEntry logEntry) {
        if (messagingTemplate != null) {
            try {
                // Send to task-specific log topic
                String logTopic = "/topic/ingest/" + logEntry.taskId() + "/logs";
                messagingTemplate.convertAndSend(logTopic, logEntry);

                // Also send to global log topic for monitoring all logs
                messagingTemplate.convertAndSend("/topic/ingest/logs", logEntry);

                logger.trace("[Task {}] Log sent: [{}] {}", logEntry.taskId(), logEntry.level(),
                        logEntry.message().length() > 100 ? logEntry.message().substring(0, 100) + "..." : logEntry.message());
            } catch (Exception e) {
                logger.error("[Task {}] Failed to send log via WebSocket: {}", logEntry.taskId(), e.getMessage());
            }
        }
    }

    /**
     * Gets the current status of a specific task.
     */
    public Optional<IngestProgressUpdate> getTaskStatus(String taskId) {
        return Optional.ofNullable(activeTasks.get(taskId));
    }

    /**
     * Gets all active and recently completed tasks.
     */
    public Collection<IngestProgressUpdate> getAllTasks() {
        return new ArrayList<>(activeTasks.values());
    }

    /**
     * Gets only tasks that are currently in progress.
     */
    public Collection<IngestProgressUpdate> getInProgressTasks() {
        return activeTasks.values().stream()
                .filter(task -> task.status() == IngestStatus.IN_PROGRESS ||
                               task.status() == IngestStatus.PENDING)
                .toList();
    }

    /**
     * Gets the count of active tasks.
     */
    public int getActiveTaskCount() {
        return (int) activeTasks.values().stream()
                .filter(task -> task.status() == IngestStatus.IN_PROGRESS ||
                               task.status() == IngestStatus.PENDING)
                .count();
    }

    /**
     * Checks if a specific task is still active.
     */
    public boolean isTaskActive(String taskId) {
        IngestProgressUpdate task = activeTasks.get(taskId);
        if (task == null) return false;
        return task.status() == IngestStatus.IN_PROGRESS || task.status() == IngestStatus.PENDING;
    }

    /**
     * Gets elapsed time for a task.
     */
    public long getElapsedTime(String taskId) {
        Long startTime = taskStartTimes.get(taskId);
        if (startTime == null) return 0;
        return System.currentTimeMillis() - startTime;
    }

    private void sendProgress(IngestProgressUpdate update) {
        // Store latest status
        activeTasks.put(update.taskId(), update);

        // Send to WebSocket topics (only if messaging template is available)
        if (messagingTemplate != null) {
            try {
                // Send to task-specific topic
                String taskTopic = "/topic/ingest/" + update.taskId();
                messagingTemplate.convertAndSend(taskTopic, update);

                // Send to global topic for monitoring all ingests
                messagingTemplate.convertAndSend("/topic/ingest/all", update);

                // Log at INFO level for important updates to trace WebSocket delivery
                if (update.progressPercent() % 10 == 0 || update.progressPercent() >= 95 ||
                    update.status() == IngestStatus.COMPLETED || update.status() == IngestStatus.FAILED) {
                    logger.info("[Task {}] WebSocket message SENT: phase={}, progress={}%, status={}, step={}",
                            update.taskId(), update.phase(), update.progressPercent(), update.status(), update.currentStep());
                } else {
                    logger.debug("[Task {}] WebSocket message sent: phase={}, progress={}%, step={}",
                            update.taskId(), update.phase(), update.progressPercent(), update.currentStep());
                }
            } catch (Exception e) {
                logger.error("[Task {}] FAILED to send WebSocket message: {}", update.taskId(), e.getMessage(), e);
            }
        } else {
            logger.warn("[Task {}] Progress stored but NOT sent (no WebSocket): phase={}, progress={}%, step={}",
                    update.taskId(), update.phase(), update.progressPercent(), update.currentStep());
        }
    }

    private void scheduleTaskCleanup(String taskId) {
        cleanupScheduler.schedule(() -> {
            activeTasks.remove(taskId);
            taskStartTimes.remove(taskId);
            taskFactSheetIds.remove(taskId);
            logSequenceCounters.remove(taskId);
            logger.debug("[Task {}] Cleaned up from tracking", taskId);
        }, COMPLETED_TASK_RETENTION_MS, TimeUnit.MILLISECONDS);
    }

    private void cleanupOldTasks() {
        long now = System.currentTimeMillis();
        List<String> toRemove = new ArrayList<>();

        for (Map.Entry<String, IngestProgressUpdate> entry : activeTasks.entrySet()) {
            IngestProgressUpdate task = entry.getValue();
            // Remove tasks that are completed/failed and older than retention period
            if (task.status() == IngestStatus.COMPLETED ||
                task.status() == IngestStatus.FAILED ||
                task.status() == IngestStatus.CANCELLED) {

                Long startTime = taskStartTimes.get(entry.getKey());
                if (startTime != null && (now - startTime) > COMPLETED_TASK_RETENTION_MS) {
                    toRemove.add(entry.getKey());
                }
            }
        }

        for (String taskId : toRemove) {
            activeTasks.remove(taskId);
            taskStartTimes.remove(taskId);
            taskFactSheetIds.remove(taskId);
            logSequenceCounters.remove(taskId);
        }

        if (!toRemove.isEmpty()) {
            logger.debug("Cleaned up {} old tasks", toRemove.size());
        }
    }

    /**
     * Helper class to track progress within a processing method.
     * Simplifies progress reporting by managing state internally.
     */
    public class TaskProgressContext {
        private final String taskId;
        private final String fileName;
        private String loaderUsed;
        private String chunkerUsed;
        private int documentsLoaded;
        private int chunksCreated;
        private int chunksEmbedded;
        private int documentsIndexed;
        private List<String> processedDocumentIds = new ArrayList<>();

        public TaskProgressContext(String taskId, String fileName) {
            this.taskId = taskId;
            this.fileName = fileName;
        }

        public void setLoaderUsed(String loader) { this.loaderUsed = loader; }
        public void setChunkerUsed(String chunker) { this.chunkerUsed = chunker; }
        public void setDocumentsLoaded(int count) { this.documentsLoaded = count; }
        public void setChunksCreated(int count) { this.chunksCreated = count; }
        public void setChunksEmbedded(int count) { this.chunksEmbedded = count; }
        public void setDocumentsIndexed(int count) { this.documentsIndexed = count; }
        public void addProcessedDocumentId(String id) { this.processedDocumentIds.add(id); }
        public void setProcessedDocumentIds(List<String> ids) { this.processedDocumentIds = new ArrayList<>(ids); }

        public void updateProgress(IngestPhase phase, int progressPercent, String currentStep, String message) {
            IngestProgressTracker.this.updateProgressWithTime(
                    taskId, fileName, phase, progressPercent, currentStep, message,
                    documentsLoaded, chunksCreated, chunksEmbedded, documentsIndexed,
                    loaderUsed, chunkerUsed, processedDocumentIds
            );
        }

        public void complete() {
            long elapsedMs = getElapsedTime(taskId);
            IngestStats finalStats = IngestStats.builder()
                    .documentsLoaded(documentsLoaded)
                    .chunksCreated(chunksCreated)
                    .chunksEmbedded(chunksEmbedded)
                    .chunksIndexed(documentsIndexed)  // documentsIndexed is actually chunks indexed
                    .documentsIndexed(documentsIndexed)
                    .totalProcessingTimeMs(elapsedMs)
                    .loaderUsed(loaderUsed)
                    .chunkerUsed(chunkerUsed)
                    .processedDocumentIds(processedDocumentIds)
                    .build();
            completeTask(taskId, fileName, finalStats);
        }

        public void fail(IngestPhase failedPhase, String errorMessage) {
            failTask(taskId, fileName, failedPhase, errorMessage);
        }

        public String getTaskId() { return taskId; }
        public String getFileName() { return fileName; }
    }

    /**
     * Creates a new progress context for tracking a task.
     */
    public TaskProgressContext createContext(String taskId, String fileName) {
        startTask(taskId, fileName);
        return new TaskProgressContext(taskId, fileName);
    }

    /**
     * Creates a new progress context for tracking a task associated with a specific fact sheet.
     */
    public TaskProgressContext createContext(String taskId, String fileName, Long factSheetId) {
        startTask(taskId, fileName, factSheetId);
        return new TaskProgressContext(taskId, fileName);
    }
}
