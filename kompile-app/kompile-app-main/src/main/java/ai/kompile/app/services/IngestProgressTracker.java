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

import ai.kompile.app.web.dto.IngestProgressUpdate;
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
    private final Map<String, IngestProgressUpdate> activeTasks = new ConcurrentHashMap<>();
    private final Map<String, Long> taskStartTimes = new ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.atomic.AtomicLong> logSequenceCounters = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor();

    // Keep completed tasks for 5 minutes before cleanup
    private static final long COMPLETED_TASK_RETENTION_MS = 5 * 60 * 1000;

    public IngestProgressTracker(@org.springframework.beans.factory.annotation.Autowired(required = false) SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate; // May be null if WebSocket not configured

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
     */
    public void startTask(String taskId, String fileName) {
        taskStartTimes.put(taskId, System.currentTimeMillis());
        sendProgress(IngestProgressUpdate.queued(taskId, fileName));
        logger.info("[Task {}] Started tracking: {}", taskId, fileName);
    }

    /**
     * Updates progress for a task.
     */
    public void updateProgress(String taskId, String fileName, IngestPhase phase,
                               int progressPercent, String currentStep, String message, IngestStats stats) {
        IngestProgressUpdate update = IngestProgressUpdate.progress(
                taskId, fileName, phase, progressPercent, currentStep, message, stats);
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
        if (!taskStartTimes.containsKey(update.taskId())) {
            taskStartTimes.put(update.taskId(), System.currentTimeMillis());
        }

        sendProgress(update);
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
        IngestProgressUpdate update = IngestProgressUpdate.completed(taskId, fileName, finalStats);
        sendProgress(update);
        logger.info("[Task {}] Completed: {} - {} docs, {} chunks in {}ms",
                taskId, fileName, finalStats.documentsLoaded(), finalStats.chunksCreated(),
                finalStats.totalProcessingTimeMs());
        scheduleTaskCleanup(taskId);
    }

    /**
     * Marks a task as failed.
     */
    public void failTask(String taskId, String fileName, IngestPhase failedPhase, String errorMessage) {
        long elapsedMs = getElapsedTime(taskId);
        IngestStats stats = IngestStats.builder().totalProcessingTimeMs(elapsedMs).build();
        IngestProgressUpdate update = IngestProgressUpdate.failed(taskId, fileName, failedPhase, errorMessage, stats);
        sendProgress(update);
        logger.error("[Task {}] Failed at {}: {} - {}", taskId, failedPhase, fileName, errorMessage);
        scheduleTaskCleanup(taskId);
    }

    /**
     * Marks a task as cancelled.
     */
    public void cancelTask(String taskId, String fileName, IngestPhase currentPhase, String reason, IngestStats stats) {
        IngestProgressUpdate update = IngestProgressUpdate.cancelled(taskId, fileName, currentPhase, reason, stats);
        sendProgress(update);
        logger.warn("[Task {}] Cancelled at {}: {} - {}", taskId, currentPhase, fileName, reason);
        scheduleTaskCleanup(taskId);
    }

    /**
     * Sends a log entry from the subprocess to WebSocket clients.
     * Logs are sent to /topic/ingest/{taskId}/logs
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
        } else if ("STDERR".equals(source)) {
            logEntry = IngestLogEntry.stderr(taskId, message, seq);
        } else {
            logEntry = IngestLogEntry.system(taskId, "INFO", message, seq);
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
}
