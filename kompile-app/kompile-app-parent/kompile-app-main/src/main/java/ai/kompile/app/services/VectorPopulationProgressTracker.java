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

import ai.kompile.app.config.Nd4jEnvironmentConfig;
import ai.kompile.app.ingest.domain.IndexingJobHistory;
import ai.kompile.app.ingest.domain.IngestEvent.IngestPhase;
import ai.kompile.app.ingest.domain.JobLogEntry.LogLevel;
import ai.kompile.app.ingest.domain.JobLogEntry.LogSource;
import ai.kompile.app.ingest.service.IndexingJobHistoryService;
import ai.kompile.app.ingest.service.JobLogService;
import ai.kompile.app.services.subprocess.VectorPopulationHandle;
import ai.kompile.app.services.subprocess.VectorPopulationResult;
import ai.kompile.app.web.dto.IngestProgressUpdate;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.utils.MapUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;


import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Centralized service for tracking vector population progress.
 * Similar to IngestProgressTracker but specialized for Lucene -> Vector Store
 * population.
 *
 * Provides real-time updates via WebSocket and maintains status for REST
 * queries.
 */
@Service
public class VectorPopulationProgressTracker implements DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(VectorPopulationProgressTracker.class);

    private static final String VECTOR_POPULATION_TOPIC = "/topic/vector-population/progress";
    private static final String VECTOR_POPULATION_LOGS_TOPIC = "/topic/vector-population/logs";

    private final SimpMessagingTemplate messagingTemplate;
    private final Nd4jEnvironmentConfigService nd4jEnvironmentConfigService;
    private final IndexingJobHistoryService jobHistoryService;
    private final JobLogService jobLogService;
    private final ObjectMapper objectMapper;
    private final Map<String, VectorPopulationUpdate> activeTasks = new ConcurrentHashMap<>();
    private final Map<String, Long> taskStartTimes = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> logSequenceCounters = new ConcurrentHashMap<>();
    private final Map<String, TaskEnvironmentSnapshot> taskEnvironments = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor();

    // Keep completed tasks for 5 minutes before cleanup (in-memory only, persisted
    // in DB)
    private static final long COMPLETED_TASK_RETENTION_MS = 5 * 60 * 1000;

    public VectorPopulationProgressTracker(
            @Autowired(required = false) SimpMessagingTemplate messagingTemplate,
            @Autowired(required = false) Nd4jEnvironmentConfigService nd4jEnvironmentConfigService,
            @Autowired(required = false) IndexingJobHistoryService jobHistoryService,
            @Autowired(required = false) JobLogService jobLogService,
            @Autowired(required = false) ObjectMapper objectMapper) {
        this.messagingTemplate = messagingTemplate;
        this.nd4jEnvironmentConfigService = nd4jEnvironmentConfigService;
        this.jobHistoryService = jobHistoryService;
        this.jobLogService = jobLogService;
        this.objectMapper = objectMapper != null ? objectMapper : JsonUtils.standardMapper();

        // Schedule periodic cleanup of old completed tasks
        cleanupScheduler.scheduleAtFixedRate(this::cleanupOldTasks, 1, 1, TimeUnit.MINUTES);

        if (messagingTemplate == null) {
            logger.warn("VectorPopulationProgressTracker initialized WITHOUT WebSocket support - " +
                    "UI will NOT receive real-time progress updates!");
        } else {
            logger.info("VectorPopulationProgressTracker initialized with WebSocket support enabled");
        }
    }

    @Override
    public void destroy() {
        logger.info("Shutting down VectorPopulationProgressTracker cleanup scheduler...");
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
        logger.info("VectorPopulationProgressTracker shutdown complete");
    }

    /**
     * Generates a unique task ID for tracking.
     */
    public String generateTaskId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Starts tracking a new vector population task.
     * Also persists the job to the IndexingJobHistory database.
     */
    public void startTask(String taskId, String keywordIndexPath, String vectorIndexPath) {
        taskStartTimes.put(taskId, System.currentTimeMillis());

        // Capture ND4J environment at task start
        captureTaskEnvironment(taskId, keywordIndexPath, vectorIndexPath);

        // Persist to job history database
        if (jobHistoryService != null) {
            try {
                String nd4jEnvJson = null;
                TaskEnvironmentSnapshot snapshot = taskEnvironments.get(taskId);
                if (snapshot != null && snapshot.nd4jEnvironmentRaw() != null) {
                    nd4jEnvJson = snapshot.nd4jEnvironmentRaw();
                }

                IndexingJobHistory job = jobHistoryService.createJobWithEnvironment(
                        taskId,
                        "Vector Population: " + vectorIndexPath,
                        nd4jEnvJson,
                        null, // file size not applicable
                        "vector-population");
                if (job != null) {
                    jobHistoryService.markJobRunning(taskId);
                    jobHistoryService.setIndexPath(taskId, vectorIndexPath);
                    logger.info("[VectorPop {}] Job history record created", taskId);
                }
            } catch (Exception e) {
                logger.warn("[VectorPop {}] Failed to create job history record: {}", taskId, e.getMessage());
            }
        }

        VectorPopulationUpdate update = new VectorPopulationUpdate(
                taskId,
                VectorPopulationPhase.LOADING,
                VectorPopulationStatus.PENDING,
                0,
                "Starting",
                "Initializing vector population...",
                keywordIndexPath,
                vectorIndexPath,
                null,
                null,
                Instant.now());

        sendProgress(update);
        logger.info("[VectorPop {}] Started tracking: {} -> {}", taskId, keywordIndexPath, vectorIndexPath);
    }

    /**
     * Captures the ND4J environment configuration at task start time.
     */
    private void captureTaskEnvironment(String taskId, String keywordIndexPath, String vectorIndexPath) {
        try {
            Nd4jEnvironmentConfig config = null;
            String configJson = null;

            if (nd4jEnvironmentConfigService != null) {
                config = nd4jEnvironmentConfigService.getActualConfiguration();
                if (config != null) {
                    configJson = objectMapper.writeValueAsString(config);
                }
            }

            TaskEnvironmentSnapshot snapshot = new TaskEnvironmentSnapshot(
                    taskId,
                    keywordIndexPath,
                    vectorIndexPath,
                    Instant.now(),
                    config != null,
                    config,
                    configJson,
                    null);
            taskEnvironments.put(taskId, snapshot);

            if (config != null) {
                logger.info("[VectorPop {}] Captured ND4J environment snapshot ({} chars)", taskId,
                        configJson != null ? configJson.length() : 0);
            } else {
                logger.warn("[VectorPop {}] Started WITHOUT ND4J environment snapshot", taskId);
            }
        } catch (Exception e) {
            logger.warn("[VectorPop {}] Failed to capture ND4J environment: {}", taskId, e.getMessage());
            taskEnvironments.put(taskId, new TaskEnvironmentSnapshot(
                    taskId, keywordIndexPath, vectorIndexPath, Instant.now(),
                    false, null, null, "Failed to capture: " + e.getMessage()));
        }
    }

    /**
     * Gets the ND4J environment snapshot for a task.
     */
    public Optional<TaskEnvironmentSnapshot> getTaskEnvironment(String taskId) {
        return Optional.ofNullable(taskEnvironments.get(taskId));
    }

    /**
     * Gets all task environments (for debugging/admin purposes).
     */
    public Map<String, TaskEnvironmentSnapshot> getAllTaskEnvironments() {
        return new HashMap<>(taskEnvironments);
    }

    /**
     * Updates progress for a task.
     * Also updates job history in the database.
     */
    public void updateProgress(String taskId, VectorPopulationPhase phase, int progressPercent,
            String currentStep, String message, VectorPopulationStats stats) {
        VectorPopulationUpdate existing = activeTasks.get(taskId);
        VectorPopulationUpdate update = new VectorPopulationUpdate(
                taskId,
                phase,
                VectorPopulationStatus.IN_PROGRESS,
                progressPercent,
                currentStep,
                message,
                existing != null ? existing.keywordIndexPath() : null,
                existing != null ? existing.vectorIndexPath() : null,
                stats,
                null,
                Instant.now());

        // Update job history in database
        if (jobHistoryService != null) {
            try {
                IngestPhase dbPhase = mapPhaseToIngestPhase(phase);
                jobHistoryService.updateJobProgress(taskId, dbPhase, progressPercent);
                if (stats != null) {
                    jobHistoryService.updateJobStats(taskId,
                            stats.documentsLoaded(),
                            stats.chunksCreated(),
                            stats.chunksEmbedded(),
                            stats.chunksIndexed());
                    if (stats.memoryUsagePercent() > 0) {
                        jobHistoryService.updateMemoryUsage(taskId, stats.memoryUsagePercent());
                    }
                }
            } catch (Exception e) {
                logger.debug("[VectorPop {}] Failed to update job history: {}", taskId, e.getMessage());
            }
        }

        sendProgress(update);
    }

    /**
     * Maps VectorPopulationPhase to IngestPhase for database storage.
     */
    private IngestPhase mapPhaseToIngestPhase(VectorPopulationPhase phase) {
        return switch (phase) {
            case LOADING -> IngestPhase.LOADING;
            case EMBEDDING -> IngestPhase.EMBEDDING;
            case INDEXING -> IngestPhase.INDEXING;
            case COMPLETED -> IngestPhase.COMPLETED;
            case FAILED -> IngestPhase.FAILED;
            case CANCELLED -> IngestPhase.FAILED; // No CANCELLED in IngestPhase, map to FAILED
        };
    }

    /**
     * Updates progress with a pre-built update object (for subprocess callbacks).
     */
    public void updateProgress(VectorPopulationUpdate update) {
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
     * Marks a task as completed.
     * Also marks the job as completed in the database.
     */
    public void completeTask(String taskId, VectorPopulationStats finalStats) {
        VectorPopulationUpdate existing = activeTasks.get(taskId);

        // For vector population from existing index, use chunks; for document ingest, use documents
        int itemsProcessed = 0;
        String itemType = "chunks";
        if (finalStats != null) {
            // If we have documents loaded, it's a document ingest; otherwise it's chunk-based
            if (finalStats.documentsLoaded() > 0 && finalStats.chunksIndexed() > 0) {
                itemsProcessed = finalStats.documentsLoaded();
                itemType = "documents";
            } else {
                itemsProcessed = finalStats.chunksIndexed() > 0 ? finalStats.chunksIndexed() : finalStats.chunksEmbedded();
            }
        }

        VectorPopulationUpdate update = new VectorPopulationUpdate(
                taskId,
                VectorPopulationPhase.COMPLETED,
                VectorPopulationStatus.COMPLETED,
                100,
                "Complete",
                String.format("Vector population complete! %d %s indexed", itemsProcessed, itemType),
                existing != null ? existing.keywordIndexPath() : null,
                existing != null ? existing.vectorIndexPath() : null,
                finalStats,
                null,
                Instant.now());

        // Mark job as completed in database
        if (jobHistoryService != null) {
            try {
                if (finalStats != null) {
                    jobHistoryService.updateJobStats(taskId,
                            finalStats.documentsLoaded(),
                            finalStats.chunksCreated(),
                            finalStats.chunksEmbedded(),
                            finalStats.chunksIndexed());
                }
                jobHistoryService.markJobCompleted(taskId);
                logger.info("[VectorPop {}] Job history marked as COMPLETED", taskId);
            } catch (Exception e) {
                logger.warn("[VectorPop {}] Failed to mark job as completed in history: {}", taskId, e.getMessage());
            }
        }

        sendProgress(update);
        logger.info("[VectorPop {}] Completed: {} documents indexed in {}ms",
                taskId,
                finalStats != null ? finalStats.chunksIndexed() : 0,
                finalStats != null ? finalStats.elapsedTimeMs() : 0);
        scheduleTaskCleanup(taskId);
    }

    /**
     * Marks a task as failed.
     * Also marks the job as failed in the database.
     */
    public void failTask(String taskId, VectorPopulationPhase failedPhase, String errorMessage) {
        long elapsedMs = getElapsedTime(taskId);
        VectorPopulationUpdate existing = activeTasks.get(taskId);
        VectorPopulationStats stats = new VectorPopulationStats(
                0, 0, 0, 0, 0, 0, 0, elapsedMs, 0, 0, null, null, null, null, null);

        VectorPopulationUpdate update = new VectorPopulationUpdate(
                taskId,
                VectorPopulationPhase.FAILED,
                VectorPopulationStatus.FAILED,
                0,
                "Failed",
                errorMessage,
                existing != null ? existing.keywordIndexPath() : null,
                existing != null ? existing.vectorIndexPath() : null,
                stats,
                errorMessage,
                Instant.now());

        // Mark job as failed in database
        if (jobHistoryService != null) {
            try {
                IngestPhase dbPhase = mapPhaseToIngestPhase(failedPhase);
                jobHistoryService.markJobFailed(taskId, dbPhase, errorMessage, null,
                        IndexingJobHistory.FailureReason.UNKNOWN);
                logger.info("[VectorPop {}] Job history marked as FAILED", taskId);
            } catch (Exception e) {
                logger.warn("[VectorPop {}] Failed to mark job as failed in history: {}", taskId, e.getMessage());
            }
        }

        sendProgress(update);
        logger.error("[VectorPop {}] Failed at {}: {}", taskId, failedPhase, errorMessage);
        scheduleTaskCleanup(taskId);
    }

    /**
     * Marks a task as cancelled.
     * Also marks the job as cancelled in the database.
     */
    public void cancelTask(String taskId, String reason) {
        VectorPopulationUpdate existing = activeTasks.get(taskId);
        VectorPopulationUpdate update = new VectorPopulationUpdate(
                taskId,
                VectorPopulationPhase.CANCELLED,
                VectorPopulationStatus.CANCELLED,
                existing != null ? existing.progressPercent() : 0,
                "Cancelled",
                reason,
                existing != null ? existing.keywordIndexPath() : null,
                existing != null ? existing.vectorIndexPath() : null,
                existing != null ? existing.stats() : null,
                null,
                Instant.now());

        // Mark job as cancelled in database
        if (jobHistoryService != null) {
            try {
                VectorPopulationPhase lastPhase = existing != null ? existing.phase() : VectorPopulationPhase.LOADING;
                IngestPhase dbPhase = mapPhaseToIngestPhase(lastPhase);
                jobHistoryService.markJobCancelled(taskId, dbPhase, reason);
                logger.info("[VectorPop {}] Job history marked as CANCELLED", taskId);
            } catch (Exception e) {
                logger.warn("[VectorPop {}] Failed to mark job as cancelled in history: {}", taskId, e.getMessage());
            }
        }

        sendProgress(update);
        logger.warn("[VectorPop {}] Cancelled: {}", taskId, reason);
        scheduleTaskCleanup(taskId);
    }

    /**
     * Sends a log entry to WebSocket clients for real-time streaming.
     * Logs are also persisted to the database via JobLogService.
     */
    public void sendLog(String taskId, String source, String level, String message) {
        if (message == null || message.isBlank()) {
            return;
        }

        AtomicLong seqCounter = logSequenceCounters.computeIfAbsent(
                taskId, k -> new AtomicLong(0));
        long seq = seqCounter.incrementAndGet();

        VectorPopulationLogEntry logEntry = new VectorPopulationLogEntry(
                taskId, level, source, message, Instant.now(), seq);

        // Persist to database
        persistLogEntry(taskId, source, level, message);

        if (messagingTemplate != null) {
            try {
                // Send to task-specific log topic
                String taskLogTopic = VECTOR_POPULATION_LOGS_TOPIC + "/" + taskId;
                messagingTemplate.convertAndSend(taskLogTopic, logEntry);

                // Also send to global log topic
                messagingTemplate.convertAndSend(VECTOR_POPULATION_LOGS_TOPIC, logEntry);

                logger.trace("[VectorPop {}] Log sent: [{}] {}",
                        taskId, level, message.length() > 100 ? message.substring(0, 100) + "..." : message);
            } catch (Exception e) {
                logger.error("[VectorPop {}] Failed to send log via WebSocket: {}", taskId, e.getMessage());
            }
        }
    }

    /**
     * Persists a log entry to the database via JobLogService.
     */
    private void persistLogEntry(String taskId, String source, String level, String message) {
        if (jobLogService != null && jobLogService.isEnabled()) {
            try {
                LogLevel logLevel = mapStringToLogLevel(level);
                LogSource logSource = mapStringToLogSource(source);
                jobLogService.logEntry(taskId, logLevel, logSource, message, null, Thread.currentThread().getName());
            } catch (Exception e) {
                logger.warn("[VectorPop {}] Failed to persist log to database: {}", taskId, e.getMessage());
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
     * Gets the current status of a specific task.
     */
    public Optional<VectorPopulationUpdate> getTaskStatus(String taskId) {
        return Optional.ofNullable(activeTasks.get(taskId));
    }

    /**
     * Gets all active and recently completed tasks.
     */
    public Collection<VectorPopulationUpdate> getAllTasks() {
        return new ArrayList<>(activeTasks.values());
    }

    /**
     * Gets only tasks that are currently in progress.
     */
    public Collection<VectorPopulationUpdate> getInProgressTasks() {
        return activeTasks.values().stream()
                .filter(task -> task.status() == VectorPopulationStatus.IN_PROGRESS ||
                        task.status() == VectorPopulationStatus.PENDING)
                .toList();
    }

    /**
     * Gets the count of active tasks.
     */
    public int getActiveTaskCount() {
        return (int) activeTasks.values().stream()
                .filter(task -> task.status() == VectorPopulationStatus.IN_PROGRESS ||
                        task.status() == VectorPopulationStatus.PENDING)
                .count();
    }

    /**
     * Checks if a specific task is still active.
     */
    public boolean isTaskActive(String taskId) {
        VectorPopulationUpdate task = activeTasks.get(taskId);
        if (task == null)
            return false;
        return task.status() == VectorPopulationStatus.IN_PROGRESS ||
                task.status() == VectorPopulationStatus.PENDING;
    }

    /**
     * Gets elapsed time for a task.
     */
    public long getElapsedTime(String taskId) {
        Long startTime = taskStartTimes.get(taskId);
        if (startTime == null)
            return 0;
        return System.currentTimeMillis() - startTime;
    }

    private void sendProgress(VectorPopulationUpdate update) {
        // Store latest status
        activeTasks.put(update.taskId(), update);

        // Send to WebSocket topics
        if (messagingTemplate != null) {
            try {
                messagingTemplate.convertAndSend(VECTOR_POPULATION_TOPIC, update);

                // Log at INFO level for important updates
                if (update.progressPercent() % 10 == 0 || update.progressPercent() >= 95 ||
                        update.status() == VectorPopulationStatus.COMPLETED ||
                        update.status() == VectorPopulationStatus.FAILED) {
                    logger.info("[VectorPop {}] WebSocket message SENT: phase={}, progress={}%, status={}, step={}",
                            update.taskId(), update.phase(), update.progressPercent(), update.status(),
                            update.currentStep());
                } else {
                    logger.debug("[VectorPop {}] WebSocket message sent: phase={}, progress={}%, step={}",
                            update.taskId(), update.phase(), update.progressPercent(), update.currentStep());
                }
            } catch (Exception e) {
                logger.error("[VectorPop {}] FAILED to send WebSocket message: {}", update.taskId(), e.getMessage(), e);
            }
        } else {
            logger.warn("[VectorPop {}] Progress stored but NOT sent (no WebSocket): phase={}, progress={}%, step={}",
                    update.taskId(), update.phase(), update.progressPercent(), update.currentStep());
        }
    }

    private void scheduleTaskCleanup(String taskId) {
        cleanupScheduler.schedule(() -> {
            activeTasks.remove(taskId);
            taskStartTimes.remove(taskId);
            logSequenceCounters.remove(taskId);
            taskEnvironments.remove(taskId);
            logger.debug("[VectorPop {}] Cleaned up from in-memory tracking (job persisted in database)", taskId);
        }, COMPLETED_TASK_RETENTION_MS, TimeUnit.MILLISECONDS);
    }

    private void cleanupOldTasks() {
        long now = System.currentTimeMillis();
        List<String> toRemove = new ArrayList<>();

        for (Map.Entry<String, VectorPopulationUpdate> entry : activeTasks.entrySet()) {
            VectorPopulationUpdate task = entry.getValue();
            if (task.status() == VectorPopulationStatus.COMPLETED ||
                    task.status() == VectorPopulationStatus.FAILED ||
                    task.status() == VectorPopulationStatus.CANCELLED) {

                Long startTime = taskStartTimes.get(entry.getKey());
                if (startTime != null && (now - startTime) > COMPLETED_TASK_RETENTION_MS) {
                    toRemove.add(entry.getKey());
                }
            }
        }

        for (String taskId : toRemove) {
            activeTasks.remove(taskId);
            taskStartTimes.remove(taskId);
            logSequenceCounters.remove(taskId);
            taskEnvironments.remove(taskId);
        }

        if (!toRemove.isEmpty()) {
            logger.debug("Cleaned up {} old vector population tasks from in-memory tracking", toRemove.size());
        }
    }

    // ========== Data Types ==========

    public enum VectorPopulationPhase {
        LOADING,
        EMBEDDING,
        INDEXING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public enum VectorPopulationStatus {
        PENDING,
        IN_PROGRESS,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public record VectorPopulationUpdate(
            String taskId,
            VectorPopulationPhase phase,
            VectorPopulationStatus status,
            int progressPercent,
            String currentStep,
            String message,
            String keywordIndexPath,
            String vectorIndexPath,
            VectorPopulationStats stats,
            String errorMessage,
            Instant timestamp) {
    }

    public record VectorPopulationStats(
            int documentsLoaded,
            int chunksCreated,
            int chunksEmbedded,
            int chunksIndexed,
            int totalDocuments,
            long tokensProcessed,
            long totalTokensInIndex,
            long elapsedTimeMs,
            double throughputDocsPerSec,
            double memoryUsagePercent,
            List<WorkerStatusDto> workerStatuses,
            QueueStatusDto queueStatus,
            EmbeddingBatchMetricsDto currentEmbeddingBatch,
            List<BatchHistoryEntryDto> batchHistory,
            IngestProgressUpdate.SubprocessRuntimeInfo runtimeInfo) {
        public static VectorPopulationStats fromSubprocessProgress(Map<String, Object> statsMap) {
            if (statsMap == null)
                return null;

            int batchSize = MapUtils.getInt(statsMap, "batchSize", 64);
            int documentsLoaded = MapUtils.getInt(statsMap, "documentsLoaded", 0);
            int chunksEmbedded = MapUtils.getInt(statsMap, "chunksEmbedded", 0);

            // Build queue status
            QueueStatusDto queueStatus = new QueueStatusDto(
                    MapUtils.getInt(statsMap, "chunkQueueSize", 0),
                    1000,
                    MapUtils.getInt(statsMap, "embeddingQueueSize", 0),
                    1000);

            // Build embedding batch metrics
            EmbeddingBatchMetricsDto embeddingBatch = null;
            if (batchSize > 0 && documentsLoaded > 0) {
                int totalBatches = (int) Math.ceil((double) documentsLoaded / batchSize);
                int currentBatch = chunksEmbedded > 0 ? (int) Math.ceil((double) chunksEmbedded / batchSize) : 1;

                embeddingBatch = new EmbeddingBatchMetricsDto(
                        currentBatch, totalBatches, batchSize,
                        MapUtils.getString(statsMap, "activeStage", "processing"),
                        0, 0, 0, false, null, 0, null, null, 0, 0,
                        MapUtils.getDouble(statsMap, "chunksPerSecond", 0),
                        null, null, null);
            }

            return new VectorPopulationStats(
                    documentsLoaded,
                    MapUtils.getInt(statsMap, "chunksCreated", 0),
                    chunksEmbedded,
                    MapUtils.getInt(statsMap, "chunksIndexed", 0),
                    MapUtils.getInt(statsMap, "totalDocuments", 0),
                    MapUtils.getLong(statsMap, "tokensProcessed", 0),
                    MapUtils.getLong(statsMap, "totalTokensInIndex", 0),
                    MapUtils.getLong(statsMap, "elapsedTimeMs", 0),
                    MapUtils.getDouble(statsMap, "chunksPerSecond", 0),
                    MapUtils.getDouble(statsMap, "memoryUsagePercent", 0),
                    null, // workerStatuses - populated elsewhere
                    queueStatus,
                    embeddingBatch,
                    null, // batchHistory - populated from subprocess messages
                    null);
        }
    }

    public record WorkerStatusDto(
            int workerId,
            String workerType,
            String status,
            int itemsProcessed,
            int currentBatchSize,
            double throughput,
            String currentItem) {
    }

    public record QueueStatusDto(
            int chunkQueueSize,
            int chunkQueueCapacity,
            int embeddedQueueSize,
            int embeddedQueueCapacity) {
        public double chunkQueueUtilization() {
            return chunkQueueCapacity > 0 ? (chunkQueueSize * 100.0 / chunkQueueCapacity) : 0;
        }

        public double embeddedQueueUtilization() {
            return embeddedQueueCapacity > 0 ? (embeddedQueueSize * 100.0 / embeddedQueueCapacity) : 0;
        }
    }

    public record EmbeddingBatchMetricsDto(
            int batchNumber,
            int totalBatches,
            int inputTexts,
            String currentStep,
            int heartbeatSeconds,
            long forwardPassTimeMs,
            long totalBatchTimeMs,
            boolean isStuck,
            String sourceDocuments,
            int sourceDocumentCount,
            String inputTensorShape,
            String outputTensorShape,
            int embeddingDimension,
            long inferenceTimeMs,
            double batchThroughput,
            String modelName,
            String deviceType,
            int[] passageTokenCounts) {
    }

    /**
     * DTO for batch history entries displayed in the UI.
     * Contains key metrics from completed embedding batches.
     */
    public record BatchHistoryEntryDto(
            int batchNumber,
            int inputTexts,
            int maxSequenceLength,
            int embeddingDimension,
            String actualInputShape,
            String actualOutputShape,
            long totalBatchTimeMs,
            String currentStep,
            double tokensPerSecond,
            int[] passageTokenCounts) {
    }

    public record VectorPopulationLogEntry(
            String taskId,
            String level,
            String source,
            String message,
            Instant timestamp,
            long sequenceNumber) {
    }

    /**
     * Snapshot of ND4J environment captured at task start time.
     * Useful for reproducing environment-specific issues.
     */
    public record TaskEnvironmentSnapshot(
            String taskId,
            String keywordIndexPath,
            String vectorIndexPath,
            Instant timestamp,
            boolean environmentCaptured,
            Nd4jEnvironmentConfig nd4jEnvironment,
            String nd4jEnvironmentRaw,
            String message) {
    }
}
