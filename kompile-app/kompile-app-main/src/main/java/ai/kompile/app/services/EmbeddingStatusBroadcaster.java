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

import ai.kompile.app.ingest.domain.IndexingJobHistory;
import ai.kompile.app.ingest.domain.IngestEvent.IngestPhase;
import ai.kompile.app.ingest.domain.JobLogEntry;
import ai.kompile.app.ingest.service.IndexingJobHistoryService;
import ai.kompile.app.ingest.service.JobLogService;
import ai.kompile.app.staging.service.StagingClientService;
import ai.kompile.app.staging.service.StagingServiceConfigService;
import ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl;
import ai.kompile.embedding.anserini.event.EmbeddingSubprocessEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service that broadcasts embedding model and staging connection status via WebSocket.
 * Provides real-time updates to the frontend about model availability, loading status,
 * and staging service connection state.
 */
@Service
public class EmbeddingStatusBroadcaster {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddingStatusBroadcaster.class);

    public static final String TOPIC_EMBEDDING_STATUS = "/topic/embedding/status";
    public static final String TOPIC_STAGING_STATUS = "/topic/staging/status";
    public static final String TOPIC_COMBINED_STATUS = "/topic/model/status";
    public static final String TOPIC_SUBPROCESS_LOGS = "/topic/embedding/subprocess";

    // Keep recent subprocess logs for clients that connect after events were sent
    private static final int MAX_RECENT_LOGS = 200;
    private final Deque<Map<String, Object>> recentSubprocessLogs = new ConcurrentLinkedDeque<>();

    private final SimpMessagingTemplate messagingTemplate;
    private final AnseriniEmbeddingModelImpl embeddingModel;
    private final StagingClientService stagingClientService;
    private final StagingServiceConfigService stagingConfigService;
    private final JobLogService jobLogService;
    private final IndexingJobHistoryService jobHistoryService;

    @Value("${kompile.embedding.status.broadcast.enabled:true}")
    private boolean broadcastEnabled;

    @Value("${kompile.embedding.status.broadcast.interval-ms:3000}")
    private long broadcastIntervalMs;

    // Track connected subscribers
    private final AtomicInteger subscriberCount = new AtomicInteger(0);
    private final AtomicBoolean broadcasting = new AtomicBoolean(false);

    // Cache previous state to only broadcast on changes
    private volatile String previousStatusHash = "";
    private volatile boolean forceNextBroadcast = false;

    // Track active embedding job task IDs
    private final Map<String, String> activeEmbeddingJobs = new ConcurrentHashMap<>();

    @Autowired
    public EmbeddingStatusBroadcaster(
            @Autowired(required = false) SimpMessagingTemplate messagingTemplate,
            @Lazy @Autowired(required = false) AnseriniEmbeddingModelImpl embeddingModel,
            @Autowired(required = false) StagingClientService stagingClientService,
            @Autowired(required = false) StagingServiceConfigService stagingConfigService,
            @Autowired(required = false) JobLogService jobLogService,
            @Autowired(required = false) IndexingJobHistoryService jobHistoryService) {
        this.messagingTemplate = messagingTemplate;
        this.embeddingModel = embeddingModel;
        this.stagingClientService = stagingClientService;
        this.stagingConfigService = stagingConfigService;
        this.jobLogService = jobLogService;
        this.jobHistoryService = jobHistoryService;
    }

    @PostConstruct
    public void init() {
        logger.info("EmbeddingStatusBroadcaster initialized - broadcast enabled: {}, interval: {}ms",
                broadcastEnabled, broadcastIntervalMs);
    }

    @PreDestroy
    public void shutdown() {
        broadcasting.set(false);
        logger.info("EmbeddingStatusBroadcaster shutting down");
    }

    /**
     * Scheduled method that broadcasts embedding and staging status at fixed intervals.
     * Only broadcasts when there are active subscribers and status has changed.
     */
    @Scheduled(fixedRateString = "${kompile.embedding.status.broadcast.interval-ms:3000}")
    public void broadcastStatus() {
        if (!broadcastEnabled || messagingTemplate == null) {
            return;
        }

        // Only broadcast if there are subscribers
        if (!broadcasting.get() && subscriberCount.get() == 0) {
            return;
        }

        try {
            Map<String, Object> status = collectStatus();
            String currentHash = computeStatusHash(status);

            // Only broadcast if status changed or forced
            if (forceNextBroadcast || !currentHash.equals(previousStatusHash)) {
                messagingTemplate.convertAndSend(TOPIC_COMBINED_STATUS, status);
                previousStatusHash = currentHash;
                forceNextBroadcast = false;
                logger.debug("Broadcasted embedding/staging status update");
            }
        } catch (Exception e) {
            logger.debug("Error broadcasting embedding status: {}", e.getMessage());
        }
    }

    /**
     * Force an immediate broadcast of the current status.
     * Called after model reload or connection changes.
     */
    public void broadcastNow() {
        if (messagingTemplate == null) {
            return;
        }

        try {
            Map<String, Object> status = collectStatus();
            messagingTemplate.convertAndSend(TOPIC_COMBINED_STATUS, status);
            previousStatusHash = computeStatusHash(status);
            logger.info("Immediate status broadcast sent");
        } catch (Exception e) {
            logger.warn("Error in immediate status broadcast: {}", e.getMessage());
        }
    }

    /**
     * Mark that the next scheduled broadcast should be sent regardless of changes.
     */
    public void forceNextBroadcast() {
        this.forceNextBroadcast = true;
    }

    /**
     * Enable broadcasting (called when a client subscribes).
     */
    public void enableBroadcasting() {
        int count = subscriberCount.incrementAndGet();
        broadcasting.set(true);
        logger.debug("Embedding status broadcasting enabled, subscriber count: {}", count);
    }

    /**
     * Disable broadcasting (called when a client unsubscribes).
     */
    public void disableBroadcasting() {
        int count = subscriberCount.decrementAndGet();
        if (count <= 0) {
            subscriberCount.set(0);
            broadcasting.set(false);
            logger.debug("Embedding status broadcasting disabled, no more subscribers");
        } else {
            logger.debug("Subscriber disconnected, remaining: {}", count);
        }
    }

    /**
     * Force start broadcasting regardless of subscriber count.
     */
    public void startBroadcasting() {
        broadcasting.set(true);
        logger.debug("Embedding status broadcasting force-started");
    }

    /**
     * Stop broadcasting.
     */
    public void stopBroadcasting() {
        broadcasting.set(false);
        logger.debug("Embedding status broadcasting stopped");
    }

    /**
     * Collect all status information for broadcasting.
     */
    public Map<String, Object> collectStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("timestamp", System.currentTimeMillis());

        // Embedding model status
        status.put("embedding", collectEmbeddingStatus());

        // Staging connection status
        status.put("staging", collectStagingStatus());

        // Combined readiness
        boolean embeddingReady = embeddingModel != null &&
                embeddingModel.isInitialized() &&
                embeddingModel.dimensions() > 0;
        status.put("ready", embeddingReady);

        return status;
    }

    private Map<String, Object> collectEmbeddingStatus() {
        Map<String, Object> embedding = new LinkedHashMap<>();

        if (embeddingModel == null) {
            embedding.put("available", false);
            embedding.put("error", "Embedding model not configured");
            return embedding;
        }

        try {
            embedding.put("available", true);
            embedding.put("modelId", embeddingModel.getActiveModelId());

            // Check initialization FIRST - isInitialized() properly checks subprocess state
            // and updates local state (including loading flags) if subprocess has loaded the model
            boolean initialized = embeddingModel.isInitialized();
            embedding.put("initialized", initialized);

            // Now get loading state AFTER initialization check has synced all state
            boolean isLoading = embeddingModel.isLoading();
            embedding.put("loading", isLoading);
            embedding.put("loadingPhase", embeddingModel.getLoadingPhase());
            embedding.put("loadingMessage", embeddingModel.getLoadingMessage());
            embedding.put("loadingElapsedMs", embeddingModel.getLoadingElapsedMs());

            // Also include model source for debugging
            AnseriniEmbeddingModelImpl.ModelSource source = embeddingModel.getModelSource();
            embedding.put("source", source.name());

            if (initialized) {
                // Safe to call dimensions if initialized
                int dims = embeddingModel.dimensions();
                embedding.put("dimensions", dims);
                embedding.put("optimalBatchSize", embeddingModel.getOptimalBatchSize());
            } else {
                embedding.put("dimensions", -1);
            }

            // Error information
            String initError = embeddingModel.getInitializationError();
            if (initError != null && !initError.isEmpty()) {
                embedding.put("error", initError);
            }

            if (source == AnseriniEmbeddingModelImpl.ModelSource.FAILED) {
                embedding.put("canRetry", true);
            }

        } catch (Exception e) {
            embedding.put("error", "Error collecting status: " + e.getMessage());
            logger.debug("Error collecting embedding status: {}", e.getMessage());
        }

        return embedding;
    }

    private Map<String, Object> collectStagingStatus() {
        Map<String, Object> staging = new LinkedHashMap<>();

        if (stagingClientService == null) {
            staging.put("available", false);
            return staging;
        }

        try {
            StagingClientService.ConnectionStatus connStatus = stagingClientService.getConnectionStatus();

            staging.put("available", true);
            staging.put("connected", connStatus.connected());
            staging.put("attempted", connStatus.attempted());
            staging.put("endpointUrl", connStatus.endpointUrl());
            staging.put("canRetry", connStatus.canRetry());
            staging.put("consecutiveFailures", connStatus.consecutiveFailures());

            if (connStatus.lastError() != null) {
                staging.put("lastError", connStatus.lastError());
            }

            if (connStatus.lastAttemptTimeMs() > 0) {
                staging.put("lastAttemptTimeMs", connStatus.lastAttemptTimeMs());
                staging.put("timeSinceLastAttemptMs", connStatus.timeSinceLastAttemptMs());
            }

            // Include active config info
            if (stagingConfigService != null) {
                stagingConfigService.getActiveConfig().ifPresent(config -> {
                    staging.put("activeConfigId", config.getId());
                    staging.put("activeConfigName", config.getName());
                    staging.put("verified", config.isVerified());
                });
            }

        } catch (Exception e) {
            staging.put("error", "Error collecting status: " + e.getMessage());
            logger.debug("Error collecting staging status: {}", e.getMessage());
        }

        return staging;
    }

    /**
     * Compute a hash of the status for change detection.
     */
    private String computeStatusHash(Map<String, Object> status) {
        // Simple hash based on key status fields
        StringBuilder sb = new StringBuilder();

        Map<String, Object> embedding = (Map<String, Object>) status.get("embedding");
        if (embedding != null) {
            sb.append("e:");
            sb.append(embedding.get("initialized"));
            sb.append(embedding.get("loading"));
            sb.append(embedding.get("loadingPhase"));
            sb.append(embedding.get("modelId"));
            sb.append(embedding.get("dimensions"));
            sb.append(embedding.get("error"));
        }

        Map<String, Object> staging = (Map<String, Object>) status.get("staging");
        if (staging != null) {
            sb.append("s:");
            sb.append(staging.get("connected"));
            sb.append(staging.get("consecutiveFailures"));
            sb.append(staging.get("lastError"));
        }

        return sb.toString();
    }

    /**
     * Check if broadcasting is currently active.
     */
    public boolean isBroadcasting() {
        return broadcasting.get();
    }

    /**
     * Get current subscriber count.
     */
    public int getSubscriberCount() {
        return subscriberCount.get();
    }

    // ========== Subprocess Event Handling ==========

    /**
     * Handle subprocess events from AnseriniEmbeddingModelImpl and broadcast to UI.
     * Also persists events to job log history for later retrieval.
     */
    @EventListener
    public void handleSubprocessEvent(EmbeddingSubprocessEvent event) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("eventType", event.getEventType().name());
            payload.put("modelId", event.getModelId());
            payload.put("timestamp", event.getTimestamp());
            payload.put("data", event.getData());

            // Store in recent logs (in-memory cache)
            recentSubprocessLogs.addLast(payload);
            while (recentSubprocessLogs.size() > MAX_RECENT_LOGS) {
                recentSubprocessLogs.removeFirst();
            }

            // Broadcast to WebSocket if available
            if (messagingTemplate != null) {
                messagingTemplate.convertAndSend(TOPIC_SUBPROCESS_LOGS, payload);
            }

            // Persist to job log history for later retrieval
            persistEmbeddingLog(event);

            // Update job history based on event type
            updateJobHistory(event);

            // Log certain events at appropriate levels
            switch (event.getEventType()) {
                case MODEL_LOADED:
                    logger.info("Subprocess event: Model loaded - {}", event.getModelId());
                    forceNextBroadcast(); // Trigger status update
                    break;
                case MODEL_FAILED:
                    logger.warn("Subprocess event: Model failed - {}: {}",
                            event.getModelId(), event.getData().get("error"));
                    forceNextBroadcast();
                    break;
                case SUBPROCESS_CRASHED:
                    logger.error("Subprocess event: Subprocess crashed - {}: {}",
                            event.getModelId(), event.getData().get("error"));
                    forceNextBroadcast();
                    break;
                case ERROR:
                    logger.warn("Subprocess event: Error in {} - {}: {}",
                            event.getData().get("phase"),
                            event.getData().get("errorType"),
                            event.getData().get("errorMessage"));
                    break;
                default:
                    logger.debug("Subprocess event: {} - {}", event.getEventType(), event.getModelId());
            }
        } catch (Exception e) {
            logger.debug("Error handling subprocess event: {}", e.getMessage());
        }
    }

    /**
     * Update job history based on embedding subprocess events.
     * Creates job when subprocess starts, updates progress, marks complete/failed.
     */
    private void updateJobHistory(EmbeddingSubprocessEvent event) {
        if (jobHistoryService == null) {
            return;
        }

        try {
            String modelId = event.getModelId() != null ? event.getModelId() : "unknown";
            String taskId = "embedding-" + modelId;

            switch (event.getEventType()) {
                case SUBPROCESS_STARTED:
                    // Create a new job history entry
                    String fileName = "Embedding Model: " + modelId;
                    jobHistoryService.createJobWithEnvironment(
                            taskId,
                            fileName,
                            null, // nd4jEnvironmentJson
                            null, // fileSizeBytes
                            "embedding" // contentType - identifies this as an embedding job
                    );
                    jobHistoryService.markJobRunning(taskId);
                    activeEmbeddingJobs.put(modelId, taskId);
                    logger.debug("Created embedding job history: {}", taskId);
                    break;

                case PROGRESS:
                    // Update progress
                    Object progressObj = event.getData().get("progressPercent");
                    int progress = 0;
                    if (progressObj instanceof Number) {
                        progress = ((Number) progressObj).intValue();
                    }
                    String phase = (String) event.getData().get("phase");
                    IngestPhase ingestPhase = mapPhase(phase);
                    jobHistoryService.updateJobProgress(taskId, ingestPhase, progress);
                    break;

                case MODEL_LOADED:
                    // Mark job as completed
                    jobHistoryService.updateJobProgress(taskId, IngestPhase.COMPLETED, 100);
                    jobHistoryService.markJobCompleted(taskId);
                    activeEmbeddingJobs.remove(modelId);

                    // Update with model info
                    Object dimensions = event.getData().get("dimensions");
                    if (dimensions instanceof Number) {
                        jobHistoryService.setEmbeddingDimension(taskId, ((Number) dimensions).intValue());
                    }
                    String embeddingModel = (String) event.getData().get("encoderType");
                    if (embeddingModel != null) {
                        jobHistoryService.updateJobParameters(
                                taskId, null, null, embeddingModel, null,
                                null, null, null, null, null, null
                        );
                    }
                    logger.debug("Completed embedding job history: {}", taskId);
                    break;

                case MODEL_FAILED:
                case SUBPROCESS_CRASHED:
                    // Mark job as failed with appropriate failure reason
                    String errorMsg = (String) event.getData().get("error");
                    if (errorMsg == null) {
                        errorMsg = (String) event.getData().get("errorMessage");
                    }
                    if (errorMsg == null) {
                        errorMsg = "Embedding model failed to load";
                    }

                    // Determine the specific failure reason from the error message
                    IndexingJobHistory.FailureReason failureReason = categorizeEmbeddingError(errorMsg);

                    jobHistoryService.markJobFailed(
                            taskId,
                            IngestPhase.EMBEDDING,
                            errorMsg,
                            null,
                            failureReason
                    );
                    activeEmbeddingJobs.remove(modelId);
                    logger.info("Failed embedding job history: {} - reason={}, error={}", taskId, failureReason, errorMsg);
                    break;

                case ERROR:
                    // Log error but don't fail the job yet (it might recover)
                    break;

                case SUBPROCESS_STOPPED:
                    // If job is still active, mark as cancelled
                    if (activeEmbeddingJobs.containsKey(modelId)) {
                        jobHistoryService.markJobCancelled(taskId, IngestPhase.EMBEDDING, "Subprocess stopped");
                        activeEmbeddingJobs.remove(modelId);
                    }
                    break;

                default:
                    // Other events don't affect job history
                    break;
            }
        } catch (Exception e) {
            logger.debug("Failed to update embedding job history: {}", e.getMessage());
        }
    }

    /**
     * Map embedding phase string to IngestPhase.
     */
    private IngestPhase mapPhase(String phase) {
        if (phase == null) {
            return IngestPhase.LOADING;
        }
        return switch (phase.toUpperCase()) {
            case "LOADING", "INITIALIZING", "STARTING" -> IngestPhase.LOADING;
            case "EMBEDDING", "MODEL_LOADING", "ENCODER_INIT" -> IngestPhase.EMBEDDING;
            case "INDEXING" -> IngestPhase.INDEXING;
            case "COMPLETED", "DONE" -> IngestPhase.COMPLETED;
            default -> IngestPhase.LOADING;
        };
    }

    /**
     * Categorize embedding error message to determine the specific failure reason.
     * Analyzes the error message text to identify model not found, staging service,
     * or other specific error conditions.
     */
    private IndexingJobHistory.FailureReason categorizeEmbeddingError(String errorMsg) {
        if (errorMsg == null || errorMsg.isEmpty()) {
            return IndexingJobHistory.FailureReason.EMBEDDING_ERROR;
        }

        String lowerError = errorMsg.toLowerCase();

        // Check for model not found errors
        if (lowerError.contains("model not found") ||
                lowerError.contains("not found in") ||
                lowerError.contains("no models") ||
                lowerError.contains("model is not available") ||
                lowerError.contains("not registered")) {
            return IndexingJobHistory.FailureReason.MODEL_NOT_FOUND;
        }

        // Check for staging service errors
        if (lowerError.contains("staging service") ||
                lowerError.contains("staging is") ||
                lowerError.contains("staging may be") ||
                lowerError.contains("staging server") ||
                lowerError.contains("staging endpoint")) {
            return IndexingJobHistory.FailureReason.STAGING_ERROR;
        }

        // Check for subprocess crash
        if (lowerError.contains("subprocess crashed") ||
                lowerError.contains("subprocess died") ||
                lowerError.contains("process terminated") ||
                lowerError.contains("exit code")) {
            return IndexingJobHistory.FailureReason.SUBPROCESS_ERROR;
        }

        // Check for out of memory
        if (lowerError.contains("out of memory") ||
                lowerError.contains("outofmemory") ||
                lowerError.contains("oom") ||
                lowerError.contains("heap space") ||
                lowerError.contains("gc overhead")) {
            return IndexingJobHistory.FailureReason.OUT_OF_MEMORY;
        }

        // Check for timeout errors
        if (lowerError.contains("timeout") ||
                lowerError.contains("timed out") ||
                lowerError.contains("deadline exceeded")) {
            return IndexingJobHistory.FailureReason.TIMEOUT;
        }

        // Check for network/IO errors
        if (lowerError.contains("connection refused") ||
                lowerError.contains("connection reset") ||
                lowerError.contains("unreachable") ||
                lowerError.contains("network") ||
                lowerError.contains("socket")) {
            return IndexingJobHistory.FailureReason.IO_ERROR;
        }

        // Default to embedding error for other cases
        return IndexingJobHistory.FailureReason.EMBEDDING_ERROR;
    }

    /**
     * Persist embedding subprocess event to job log history.
     * Uses task ID format: "embedding-{modelId}" for grouping logs by model.
     */
    private void persistEmbeddingLog(EmbeddingSubprocessEvent event) {
        if (jobLogService == null || !jobLogService.isEnabled()) {
            return;
        }

        try {
            // Use a consistent task ID for embedding logs: "embedding-{modelId}"
            String taskId = "embedding-" + (event.getModelId() != null ? event.getModelId() : "unknown");

            // Determine log level based on event type
            JobLogEntry.LogLevel level = switch (event.getEventType()) {
                case ERROR, MODEL_FAILED, SUBPROCESS_CRASHED -> JobLogEntry.LogLevel.ERROR;
                case PROGRESS, PHASE_TRANSITION -> JobLogEntry.LogLevel.DEBUG;
                case LOG -> {
                    String eventLevel = (String) event.getData().get("level");
                    yield mapLogLevel(eventLevel);
                }
                default -> JobLogEntry.LogLevel.INFO;
            };

            // Format message based on event type
            String message = formatEventMessage(event);

            // Persist to database
            jobLogService.logEntry(
                    taskId,
                    level,
                    JobLogEntry.LogSource.EMBEDDING,
                    message,
                    "EmbeddingSubprocess",
                    Thread.currentThread().getName()
            );
        } catch (Exception e) {
            logger.debug("Failed to persist embedding log: {}", e.getMessage());
        }
    }

    /**
     * Map string log level to JobLogEntry.LogLevel.
     */
    private JobLogEntry.LogLevel mapLogLevel(String level) {
        if (level == null) {
            return JobLogEntry.LogLevel.INFO;
        }
        return switch (level.toUpperCase()) {
            case "ERROR" -> JobLogEntry.LogLevel.ERROR;
            case "WARN", "WARNING" -> JobLogEntry.LogLevel.WARN;
            case "DEBUG" -> JobLogEntry.LogLevel.DEBUG;
            case "TRACE" -> JobLogEntry.LogLevel.TRACE;
            default -> JobLogEntry.LogLevel.INFO;
        };
    }

    /**
     * Format event message for logging.
     */
    private String formatEventMessage(EmbeddingSubprocessEvent event) {
        Map<String, Object> data = event.getData();
        return switch (event.getEventType()) {
            case LOG -> String.format("[%s] %s",
                    data.getOrDefault("source", "subprocess"),
                    data.getOrDefault("message", ""));
            case PROGRESS -> String.format("[%s] %d%% - %s",
                    data.getOrDefault("phase", "progress"),
                    data.getOrDefault("progressPercent", 0),
                    data.getOrDefault("message", data.getOrDefault("currentStep", "")));
            case PHASE_TRANSITION -> String.format("Phase: %s -> %s (%dms)",
                    data.getOrDefault("fromPhase", "start"),
                    data.getOrDefault("toPhase", "unknown"),
                    data.getOrDefault("durationMs", 0));
            case ERROR -> String.format("[%s] %s: %s",
                    data.getOrDefault("phase", "error"),
                    data.getOrDefault("errorType", "Error"),
                    data.getOrDefault("errorMessage", "Unknown error"));
            case MODEL_LOADED -> String.format("Model loaded: %s (dimensions=%s, type=%s)",
                    event.getModelId(),
                    data.getOrDefault("dimensions", "?"),
                    data.getOrDefault("encoderType", "?"));
            case MODEL_FAILED -> String.format("Model failed to load: %s - %s",
                    event.getModelId(),
                    data.getOrDefault("error", "Unknown error"));
            case SUBPROCESS_STARTED -> String.format("Embedding subprocess started for model: %s",
                    event.getModelId());
            case SUBPROCESS_STOPPED -> String.format("Embedding subprocess stopped for model: %s",
                    event.getModelId());
            case SUBPROCESS_CRASHED -> String.format("Embedding subprocess crashed: %s",
                    data.getOrDefault("error", "Unknown error"));
            case HEARTBEAT -> "Heartbeat";
            default -> String.format("Event: %s - %s", event.getEventType(), data);
        };
    }

    /**
     * Get recent subprocess logs for clients that connect after events were sent.
     * @return List of recent subprocess log entries
     */
    public List<Map<String, Object>> getRecentSubprocessLogs() {
        return new ArrayList<>(recentSubprocessLogs);
    }

    /**
     * Get recent subprocess logs, limited to specific count.
     * @param limit Maximum number of logs to return
     * @return List of recent subprocess log entries
     */
    public List<Map<String, Object>> getRecentSubprocessLogs(int limit) {
        List<Map<String, Object>> logs = new ArrayList<>(recentSubprocessLogs);
        if (logs.size() > limit) {
            return logs.subList(logs.size() - limit, logs.size());
        }
        return logs;
    }

    /**
     * Clear recent subprocess logs.
     */
    public void clearSubprocessLogs() {
        recentSubprocessLogs.clear();
    }
}
