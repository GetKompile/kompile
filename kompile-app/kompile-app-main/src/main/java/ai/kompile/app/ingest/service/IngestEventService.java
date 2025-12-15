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

package ai.kompile.app.ingest.service;

import ai.kompile.app.config.Nd4jEnvironmentConfig;
import ai.kompile.app.ingest.domain.IngestEvent;
import ai.kompile.app.ingest.domain.IngestEvent.EventType;
import ai.kompile.app.ingest.domain.IngestEvent.IngestPhase;
import ai.kompile.app.ingest.repository.IngestEventRepository;
import ai.kompile.app.services.Nd4jEnvironmentConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing ingest event logs.
 *
 * Provides:
 * - Event logging with automatic persistence
 * - Query methods for audit retrieval
 * - Automatic cleanup of old events
 * - Task timing tracking
 */
@Service
public class IngestEventService {

    private static final Logger logger = LoggerFactory.getLogger(IngestEventService.class);

    // WebSocket topics for event broadcasting
    private static final String EVENTS_TOPIC_ALL = "/topic/ingest/events";
    private static final String EVENTS_TOPIC_TASK = "/topic/ingest/events/";

    private final IngestEventRepository repository;
    private final SimpMessagingTemplate messagingTemplate;
    private final Nd4jEnvironmentConfigService nd4jEnvironmentConfigService;
    private final ObjectMapper objectMapper;

    // Track phase start times for duration calculation
    private final Map<String, Map<IngestPhase, Instant>> phaseStartTimes = new ConcurrentHashMap<>();

    // Track task start times
    private final Map<String, Instant> taskStartTimes = new ConcurrentHashMap<>();

    @Value("${kompile.ingest.eventlog.retention-hours:24}")
    private int retentionHours;

    @Value("${kompile.ingest.eventlog.max-database-size-mb:500}")
    private long maxDatabaseSizeMb;

    @Value("${kompile.ingest.eventlog.database-path:~/.kompile/data/ingest-events}")
    private String databasePath;

    @Value("${kompile.ingest.eventlog.enabled:true}")
    private boolean enabled;

    @Autowired
    public IngestEventService(
            @Autowired(required = false) IngestEventRepository repository,
            @Autowired(required = false) SimpMessagingTemplate messagingTemplate,
            @Autowired(required = false) Nd4jEnvironmentConfigService nd4jEnvironmentConfigService) {
        this.repository = repository;
        this.messagingTemplate = messagingTemplate;
        this.nd4jEnvironmentConfigService = nd4jEnvironmentConfigService;
        this.objectMapper = new ObjectMapper();
    }

    @PostConstruct
    public void init() {
        if (repository == null) {
            logger.warn("IngestEventRepository not available - event logging disabled");
            enabled = false;
        } else {
            logger.info("IngestEventService initialized: retention={}h, maxDbSize={}MB, enabled={}",
                    retentionHours, maxDatabaseSizeMb, enabled);
            // Perform startup cleanup if database is too large
            startupCleanup();
        }
    }

    /**
     * On startup, check database size and perform aggressive cleanup if needed.
     */
    private void startupCleanup() {
        String resolvedPath = databasePath.replace("~", System.getProperty("user.home"));
        File dbFile = new File(resolvedPath + ".mv.db");
        if (!dbFile.exists()) {
            // Try alternate H2 file name
            dbFile = new File(resolvedPath + ".h2.db");
        }

        if (dbFile.exists()) {
            long sizeMb = dbFile.length() / (1024 * 1024);
            logger.info("Ingest event database file size: {} MB (max: {} MB)", sizeMb, maxDatabaseSizeMb);

            if (sizeMb > maxDatabaseSizeMb) {
                logger.warn("═══════════════════════════════════════════════════════════");
                logger.warn("DATABASE SIZE EXCEEDS LIMIT - PERFORMING AGGRESSIVE CLEANUP");
                logger.warn("Current size: {} MB, Limit: {} MB", sizeMb, maxDatabaseSizeMb);
                logger.warn("═══════════════════════════════════════════════════════════");

                // First, try normal cleanup
                try {
                    cleanupOldEvents();
                } catch (Exception e) {
                    logger.error("Normal cleanup failed: {}", e.getMessage());
                }

                // If still too large, do more aggressive cleanup (keep only 6 hours)
                if (dbFile.exists()) {
                    long sizeAfterMb = dbFile.length() / (1024 * 1024);
                    if (sizeAfterMb > maxDatabaseSizeMb) {
                        logger.warn("Database still too large after normal cleanup - doing aggressive cleanup (6 hours retention)");
                        try {
                            forceCleanup(6);
                        } catch (Exception e) {
                            logger.error("Aggressive cleanup failed: {}", e.getMessage());
                        }
                    }
                }
            }
        }
    }

    /**
     * Check if event logging is enabled.
     */
    public boolean isEnabled() {
        return enabled && repository != null;
    }

    /**
     * Broadcast an event via WebSocket to all subscribers.
     */
    private void broadcastEvent(IngestEvent event) {
        if (messagingTemplate == null) return;

        try {
            // Send to task-specific topic
            messagingTemplate.convertAndSend(EVENTS_TOPIC_TASK + event.getTaskId(), event);
            // Send to global events topic
            messagingTemplate.convertAndSend(EVENTS_TOPIC_ALL, event);
        } catch (Exception e) {
            logger.warn("Failed to broadcast event via WebSocket: {}", e.getMessage());
        }
    }

    /**
     * Log that a task has been queued, capturing the ND4J environment configuration
     * at job start time to allow reproduction of environment-specific issues.
     */
    @Transactional(transactionManager = "ingestEventTransactionManager")
    public void logQueued(String taskId, String fileName) {
        if (!isEnabled()) {
            logger.warn("[{}] logQueued skipped - IngestEventService is disabled", taskId);
            return;
        }

        taskStartTimes.put(taskId, Instant.now());

        // Capture ND4J environment snapshot at job start
        String nd4jEnvJson = captureNd4jEnvironmentSnapshot();

        IngestEvent event = IngestEvent.queued(taskId, fileName, nd4jEnvJson);
        repository.save(event);
        broadcastEvent(event);

        if (nd4jEnvJson != null) {
            logger.info("[{}] QUEUED event saved with ND4J environment snapshot ({} chars)", taskId, nd4jEnvJson.length());
        } else {
            logger.warn("[{}] QUEUED event saved WITHOUT ND4J environment snapshot (nd4jEnvironmentConfigService={})",
                    taskId, nd4jEnvironmentConfigService != null ? "present" : "null");
        }
    }

    /**
     * Log that a task has been queued, using a provided ND4J environment snapshot.
     * This is primarily used for subprocess mode, where the parent process may intentionally
     * avoid initializing ND4J, but still needs to persist the exact configuration that was
     * passed to the subprocess at invocation time.
     */
    @Transactional(transactionManager = "ingestEventTransactionManager")
    public void logQueuedWithEnvironmentSnapshot(String taskId, String fileName, String nd4jEnvironmentJson) {
        if (!isEnabled()) {
            logger.warn("[{}] logQueuedWithEnvironmentSnapshot skipped - IngestEventService is disabled", taskId);
            return;
        }

        taskStartTimes.put(taskId, Instant.now());

        IngestEvent event = IngestEvent.queued(taskId, fileName, nd4jEnvironmentJson);
        repository.save(event);
        broadcastEvent(event);

        if (nd4jEnvironmentJson != null && !nd4jEnvironmentJson.isBlank()) {
            logger.info("[{}] QUEUED event saved with provided ND4J environment snapshot ({} chars)", taskId,
                    nd4jEnvironmentJson.length());
        } else {
            logger.warn("[{}] QUEUED event saved WITHOUT provided ND4J environment snapshot", taskId);
        }
    }

    /**
     * Public method to capture the current ND4J environment configuration.
     * Can be used by other services that need the environment snapshot.
     *
     * @return JSON representation of ND4J environment, or null if capture fails
     */
    public String captureNd4jEnvironment() {
        return captureNd4jEnvironmentSnapshot();
    }

    /**
     * Captures the current ND4J environment configuration as a JSON string.
     * This allows reproduction of environment-specific issues by providing
     * the exact configuration that was active when a job started.
     *
     * @return JSON representation of ND4J environment, or null if capture fails
     */
    private String captureNd4jEnvironmentSnapshot() {
        if (nd4jEnvironmentConfigService == null) {
            logger.trace("ND4J environment config service not available - skipping snapshot");
            return null;
        }

        try {
            Nd4jEnvironmentConfig actualConfig = nd4jEnvironmentConfigService.getActualConfiguration();
            return objectMapper.writeValueAsString(actualConfig);
        } catch (Exception e) {
            logger.warn("Failed to capture ND4J environment snapshot: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Log the start of a processing phase.
     */
    @Transactional(transactionManager = "ingestEventTransactionManager")
    public void logPhaseStarted(String taskId, String fileName, IngestPhase phase, IngestPhase previousPhase) {
        if (!isEnabled()) return;

        // Track phase start time
        phaseStartTimes.computeIfAbsent(taskId, k -> new ConcurrentHashMap<>())
                .put(phase, Instant.now());

        String message = String.format("Starting %s phase", phase.name().toLowerCase());
        IngestEvent event = IngestEvent.phaseStarted(taskId, fileName, phase, previousPhase, message);
        repository.save(event);
        broadcastEvent(event);
        logger.debug("[{}] Event logged: {} started", taskId, phase);
    }

    /**
     * Log progress within a phase.
     */
    @Transactional(transactionManager = "ingestEventTransactionManager")
    public void logProgress(String taskId, String fileName, IngestPhase phase,
                            int itemsProcessed, int totalItems, String message) {
        if (!isEnabled()) return;

        IngestEvent event = IngestEvent.progress(taskId, fileName, phase, itemsProcessed, totalItems, message);
        repository.save(event);
        broadcastEvent(event);
        // Don't log progress events at debug level - too verbose
    }

    /**
     * Log that a phase has completed.
     */
    @Transactional(transactionManager = "ingestEventTransactionManager")
    public void logPhaseCompleted(String taskId, String fileName, IngestPhase phase,
                                   int itemsProcessed, String message) {
        if (!isEnabled()) return;

        // Calculate duration
        long durationMs = calculatePhaseDuration(taskId, phase);

        IngestEvent event = IngestEvent.phaseCompleted(taskId, fileName, phase, durationMs, itemsProcessed, message);
        repository.save(event);
        broadcastEvent(event);
        logger.debug("[{}] Event logged: {} completed in {}ms", taskId, phase, durationMs);
    }

    /**
     * Log a state transition between phases.
     */
    @Transactional(transactionManager = "ingestEventTransactionManager")
    public void logStateTransition(String taskId, String fileName, IngestPhase fromPhase, IngestPhase toPhase) {
        if (!isEnabled()) return;

        long phaseDuration = calculatePhaseDuration(taskId, fromPhase);
        String message = String.format("Transition %s -> %s", fromPhase, toPhase);
        IngestEvent event = IngestEvent.stateTransition(taskId, fileName, fromPhase, toPhase, phaseDuration, message);
        repository.save(event);
        broadcastEvent(event);
        logger.debug("[{}] Event logged: {} -> {} ({}ms)", taskId, fromPhase, toPhase, phaseDuration);
    }

    /**
     * Log an error during processing.
     */
    @Transactional(transactionManager = "ingestEventTransactionManager")
    public void logError(String taskId, String fileName, IngestPhase phase,
                         String errorMessage, Throwable exception) {
        if (!isEnabled()) return;

        IngestEvent event = IngestEvent.error(taskId, fileName, phase, errorMessage, exception);
        repository.save(event);
        broadcastEvent(event);
        logger.debug("[{}] Event logged: ERROR in {} - {}", taskId, phase, errorMessage);
    }

    /**
     * Log successful task completion.
     */
    @Transactional(transactionManager = "ingestEventTransactionManager")
    public void logCompleted(String taskId, String fileName, int totalItemsProcessed, String summary) {
        if (!isEnabled()) return;

        long totalDuration = calculateTaskDuration(taskId);
        IngestEvent event = IngestEvent.completed(taskId, fileName, totalDuration, totalItemsProcessed, summary);
        repository.save(event);
        broadcastEvent(event);

        // Clean up tracking maps
        cleanupTaskTracking(taskId);
        logger.debug("[{}] Event logged: COMPLETED in {}ms, {} items", taskId, totalDuration, totalItemsProcessed);
    }

    /**
     * Log task failure.
     */
    @Transactional(transactionManager = "ingestEventTransactionManager")
    public void logFailed(String taskId, String fileName, IngestPhase failedPhase,
                          String errorMessage, Throwable exception) {
        if (!isEnabled()) return;

        long totalDuration = calculateTaskDuration(taskId);
        IngestEvent event = IngestEvent.failed(taskId, fileName, failedPhase, totalDuration, errorMessage, exception);
        repository.save(event);
        broadcastEvent(event);

        // Clean up tracking maps
        cleanupTaskTracking(taskId);
        logger.debug("[{}] Event logged: FAILED in {} after {}ms", taskId, failedPhase, totalDuration);
    }

    /**
     * Log task cancellation.
     */
    @Transactional(transactionManager = "ingestEventTransactionManager")
    public void logCancelled(String taskId, String fileName, IngestPhase currentPhase, String reason) {
        if (!isEnabled()) return;

        long duration = calculateTaskDuration(taskId);
        IngestEvent event = IngestEvent.cancelled(taskId, fileName, currentPhase, duration, reason);
        repository.save(event);
        broadcastEvent(event);

        // Clean up tracking maps
        cleanupTaskTracking(taskId);
        logger.debug("[{}] Event logged: CANCELLED in {} after {}ms", taskId, currentPhase, duration);
    }

    /**
     * Log that a task was forcibly killed due to memory pressure exceeding kill threshold.
     * This is a critical audit event that indicates system memory protection kicked in.
     *
     * @param taskId          The task identifier
     * @param fileName        The file being processed
     * @param killedPhase     The phase in which the job was killed
     * @param memoryPercent   Memory usage percentage at time of kill
     * @param killThreshold   The configured kill threshold percentage
     * @param itemsProcessed  Number of items processed before kill
     * @param details         Additional details about the job state at kill time
     */
    @Transactional(transactionManager = "ingestEventTransactionManager")
    public void logMemoryKilled(String taskId, String fileName, IngestPhase killedPhase,
                                 double memoryPercent, int killThreshold, int itemsProcessed, String details) {
        if (!isEnabled()) return;

        long duration = calculateTaskDuration(taskId);
        IngestEvent event = IngestEvent.memoryKilled(taskId, fileName, killedPhase, duration,
                memoryPercent, killThreshold, itemsProcessed, details);
        repository.save(event);
        broadcastEvent(event);

        // Clean up tracking maps
        cleanupTaskTracking(taskId);

        // Log at WARN level since this is a significant system event
        logger.warn("[{}] MEMORY_KILLED event logged: Job killed in {} phase after {}ms. " +
                        "Memory: {}% exceeded threshold {}%. Items processed: {}",
                taskId, killedPhase, duration, String.format("%.1f", memoryPercent), killThreshold, itemsProcessed);
    }

    // ========== Query Methods ==========

    /**
     * Get all events for a task.
     */
    @Transactional(transactionManager = "ingestEventTransactionManager", readOnly = true)
    public List<IngestEvent> getEventsForTask(String taskId) {
        if (!isEnabled()) return List.of();
        return repository.findByTaskIdOrderByTimestampAsc(taskId);
    }

    /**
     * Get the latest event for a task.
     */
    @Transactional(transactionManager = "ingestEventTransactionManager", readOnly = true)
    public IngestEvent getLatestEvent(String taskId) {
        if (!isEnabled()) return null;
        return repository.findLatestByTaskId(taskId);
    }

    /**
     * Get the ND4J environment snapshot that was captured when a job was queued.
     * This is useful for reproducing environment-specific issues.
     *
     * @param taskId The task identifier
     * @return The ND4J environment config JSON, or null if not found or not captured
     */
    @Transactional(transactionManager = "ingestEventTransactionManager", readOnly = true)
    public String getNd4jEnvironmentSnapshot(String taskId) {
        if (!isEnabled()) return null;

        // Find the QUEUED event which contains the environment snapshot
        List<IngestEvent> events = repository.findByTaskIdOrderByTimestampAsc(taskId);
        return events.stream()
                .filter(e -> e.getEventType() == EventType.QUEUED)
                .map(IngestEvent::getNd4jEnvironmentSnapshot)
                .findFirst()
                .orElse(null);
    }

    /**
     * Get the ND4J environment snapshot as a parsed configuration object.
     *
     * @param taskId The task identifier
     * @return The ND4J environment config, or null if not found or not captured
     */
    @Transactional(transactionManager = "ingestEventTransactionManager", readOnly = true)
    public Nd4jEnvironmentConfig getNd4jEnvironmentConfig(String taskId) {
        String json = getNd4jEnvironmentSnapshot(taskId);
        if (json == null || json.isEmpty()) {
            return null;
        }

        try {
            return objectMapper.readValue(json, Nd4jEnvironmentConfig.class);
        } catch (Exception e) {
            logger.warn("Failed to parse ND4J environment snapshot for task {}: {}", taskId, e.getMessage());
            return null;
        }
    }

    /**
     * Get recent terminal events (completions, failures, cancellations).
     */
    @Transactional(transactionManager = "ingestEventTransactionManager", readOnly = true)
    public List<IngestEvent> getRecentTerminalEvents(Duration since) {
        if (!isEnabled()) return List.of();
        return repository.findRecentTerminalEvents(Instant.now().minus(since));
    }

    /**
     * Get events in a time range.
     */
    @Transactional(transactionManager = "ingestEventTransactionManager", readOnly = true)
    public List<IngestEvent> getEventsBetween(Instant start, Instant end) {
        if (!isEnabled()) return List.of();
        return repository.findByTimestampBetweenOrderByTimestampAsc(start, end);
    }

    /**
     * Get error events in a time range.
     */
    @Transactional(transactionManager = "ingestEventTransactionManager", readOnly = true)
    public List<IngestEvent> getErrorEvents(Instant start, Instant end) {
        if (!isEnabled()) return List.of();
        return repository.findByEventTypeAndTimestampBetweenOrderByTimestampDesc(
                EventType.ERROR, start, end);
    }

    /**
     * Get distinct task IDs with events in a time range.
     */
    @Transactional(transactionManager = "ingestEventTransactionManager", readOnly = true)
    public List<String> getTaskIds(Instant start, Instant end) {
        if (!isEnabled()) return List.of();
        return repository.findDistinctTaskIdsByTimestampBetween(start, end);
    }

    /**
     * Get total event count.
     */
    @Transactional(transactionManager = "ingestEventTransactionManager", readOnly = true)
    public long getTotalEventCount() {
        if (!isEnabled()) return 0;
        return repository.countTotalEvents();
    }

    // ========== Cleanup ==========

    /**
     * Scheduled cleanup of old events.
     * Runs every hour to prevent database bloat.
     */
    @Scheduled(fixedRate = 3600000) // Run every hour (3600000 ms)
    @Transactional(transactionManager = "ingestEventTransactionManager")
    public void cleanupOldEvents() {
        if (!isEnabled()) return;

        Instant cutoff = Instant.now().minus(Duration.ofHours(retentionHours));

        try {
            long countBefore = repository.countTotalEvents();
            int deleted = repository.deleteEventsOlderThan(cutoff);
            long countAfter = repository.countTotalEvents();

            if (deleted > 0) {
                logger.info("Ingest event cleanup: deleted {} entries older than {} hours (before: {}, after: {})",
                        deleted, retentionHours, countBefore, countAfter);
            }
        } catch (Exception e) {
            logger.error("Failed to cleanup ingest events: {}", e.getMessage(), e);
        }
    }

    /**
     * Manually trigger cleanup (for testing or immediate need).
     * @param hoursToKeep Number of hours of logs to retain
     * @return Number of entries deleted
     */
    @Transactional(transactionManager = "ingestEventTransactionManager")
    public int forceCleanup(int hoursToKeep) {
        if (!isEnabled()) return 0;

        Instant cutoff = Instant.now().minus(Duration.ofHours(hoursToKeep));
        int deleted = repository.deleteEventsOlderThan(cutoff);
        logger.info("Manual ingest event cleanup: deleted {} entries older than {} hours", deleted, hoursToKeep);
        return deleted;
    }

    /**
     * Get current retention setting.
     */
    public int getRetentionHours() {
        return retentionHours;
    }

    /**
     * Delete all events for a specific task.
     */
    @Transactional(transactionManager = "ingestEventTransactionManager")
    public void deleteTaskEvents(String taskId) {
        if (!isEnabled()) return;
        repository.deleteByTaskId(taskId);
        logger.info("Deleted all events for task: {}", taskId);
    }

    // ========== Helper Methods ==========

    private long calculatePhaseDuration(String taskId, IngestPhase phase) {
        Map<IngestPhase, Instant> phases = phaseStartTimes.get(taskId);
        if (phases == null || !phases.containsKey(phase)) {
            return 0;
        }
        return Duration.between(phases.get(phase), Instant.now()).toMillis();
    }

    private long calculateTaskDuration(String taskId) {
        Instant startTime = taskStartTimes.get(taskId);
        if (startTime == null) {
            return 0;
        }
        return Duration.between(startTime, Instant.now()).toMillis();
    }

    private void cleanupTaskTracking(String taskId) {
        phaseStartTimes.remove(taskId);
        taskStartTimes.remove(taskId);
    }
}
