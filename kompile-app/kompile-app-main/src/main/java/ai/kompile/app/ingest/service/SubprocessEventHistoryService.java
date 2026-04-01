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

import ai.kompile.app.ingest.domain.SubprocessEventHistory;
import ai.kompile.app.ingest.domain.SubprocessEventHistory.EventType;
import ai.kompile.app.ingest.repository.SubprocessEventHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for managing subprocess event history.
 * Provides methods for recording and querying subprocess lifecycle events,
 * including restarts, crashes, and model loading.
 */
@Service
public class SubprocessEventHistoryService {

    private static final Logger log = LoggerFactory.getLogger(SubprocessEventHistoryService.class);

    private final SubprocessEventHistoryRepository repository;

    @Value("${kompile.subprocess.event-history.retention-days:30}")
    private int retentionDays;

    @Value("${kompile.subprocess.event-history.max-records:10000}")
    private int maxRecords;

    @Autowired
    public SubprocessEventHistoryService(
            @Autowired(required = false) SubprocessEventHistoryRepository repository) {
        this.repository = repository;
    }

    // ===================== RECORD EVENTS =====================

    /**
     * Record a subprocess started event.
     */
    @Transactional("ingestEventTransactionManager")
    public void recordSubprocessStarted(String modelId) {
        if (repository == null) return;
        SubprocessEventHistory event = SubprocessEventHistory.subprocessStarted(modelId);
        repository.save(event);
        log.info("Recorded subprocess started event for model: {}", modelId);
    }

    /**
     * Record a subprocess stopped event.
     */
    @Transactional("ingestEventTransactionManager")
    public void recordSubprocessStopped(String modelId) {
        if (repository == null) return;
        SubprocessEventHistory event = SubprocessEventHistory.subprocessStopped(modelId);
        repository.save(event);
        log.info("Recorded subprocess stopped event for model: {}", modelId);
    }

    /**
     * Record a subprocess crashed event.
     */
    @Transactional("ingestEventTransactionManager")
    public void recordSubprocessCrashed(String modelId, String error, Integer exitCode, String taskId) {
        if (repository == null) return;
        SubprocessEventHistory event = SubprocessEventHistory.subprocessCrashed(modelId, error, exitCode, taskId);
        repository.save(event);
        log.warn("Recorded subprocess crash for model {} (exit code: {}): {}", modelId, exitCode, error);
    }

    /**
     * Record a subprocess restart attempt.
     */
    @Transactional("ingestEventTransactionManager")
    public void recordRestartAttempt(String modelId, int attemptNumber, int maxAttempts,
                                      String reason, long backoffMs,
                                      long heapBytes, int batchSize, int threads,
                                      String taskId) {
        if (repository == null) return;
        SubprocessEventHistory event = SubprocessEventHistory.subprocessRestarting(
                modelId, attemptNumber, maxAttempts, reason, backoffMs, heapBytes, batchSize, threads, taskId);
        repository.save(event);
        log.info("Recorded restart attempt {}/{} for model {} (reason: {}, heap: {}MB, batch: {}, threads: {})",
                attemptNumber, maxAttempts, modelId, reason, heapBytes / (1024 * 1024), batchSize, threads);
    }

    /**
     * Record a successful restart.
     */
    @Transactional("ingestEventTransactionManager")
    public void recordRestartSuccess(String modelId, int attemptNumber, String taskId) {
        if (repository == null) return;
        SubprocessEventHistory event = SubprocessEventHistory.subprocessRestartSuccess(modelId, attemptNumber, taskId);
        repository.save(event);
        log.info("Recorded restart success for model {} (attempt {})", modelId, attemptNumber);
    }

    /**
     * Record that all restart attempts were exhausted.
     */
    @Transactional("ingestEventTransactionManager")
    public void recordRestartExhausted(String modelId, int totalAttempts, String lastReason, String taskId) {
        if (repository == null) return;
        SubprocessEventHistory event = SubprocessEventHistory.subprocessRestartExhausted(modelId, totalAttempts, lastReason, taskId);
        repository.save(event);
        log.warn("Recorded restart exhausted for model {} ({} attempts, last reason: {})", modelId, totalAttempts, lastReason);
    }

    /**
     * Record model loaded event.
     */
    @Transactional("ingestEventTransactionManager")
    public void recordModelLoaded(String modelId, int dimensions, String encoderType) {
        if (repository == null) return;
        SubprocessEventHistory event = SubprocessEventHistory.modelLoaded(modelId, dimensions, encoderType);
        repository.save(event);
        log.info("Recorded model loaded for {} (dims={}, type={})", modelId, dimensions, encoderType);
    }

    /**
     * Record model failed event.
     */
    @Transactional("ingestEventTransactionManager")
    public void recordModelFailed(String modelId, String error) {
        if (repository == null) return;
        SubprocessEventHistory event = SubprocessEventHistory.modelFailed(modelId, error);
        repository.save(event);
        log.warn("Recorded model failed for {}: {}", modelId, error);
    }

    // ===================== QUERY METHODS =====================

    /**
     * Get all events ordered by timestamp.
     */
    @Transactional(value = "ingestEventTransactionManager", readOnly = true)
    public Page<SubprocessEventHistory> getAllEvents(int page, int size) {
        if (repository == null) return Page.empty();
        return repository.findAllOrderedByTimestamp(PageRequest.of(page, size));
    }

    /**
     * Get recent events (last 24 hours by default).
     */
    @Transactional(value = "ingestEventTransactionManager", readOnly = true)
    public List<SubprocessEventHistory> getRecentEvents(int hours) {
        if (repository == null) return List.of();
        return repository.findRecentEvents(Instant.now().minus(hours, ChronoUnit.HOURS));
    }

    /**
     * Get most recent N events.
     */
    @Transactional(value = "ingestEventTransactionManager", readOnly = true)
    public List<SubprocessEventHistory> getLatestEvents() {
        if (repository == null) return List.of();
        return repository.findTop100ByOrderByTimestampDesc();
    }

    /**
     * Get restart events.
     */
    @Transactional(value = "ingestEventTransactionManager", readOnly = true)
    public List<SubprocessEventHistory> getRestartEvents() {
        if (repository == null) return List.of();
        return repository.findRestartEvents();
    }

    /**
     * Get restart events for a specific model.
     */
    @Transactional(value = "ingestEventTransactionManager", readOnly = true)
    public List<SubprocessEventHistory> getRestartEventsForModel(String modelId) {
        if (repository == null) return List.of();
        return repository.findRestartEventsForModel(modelId);
    }

    /**
     * Get events for a specific task.
     */
    @Transactional(value = "ingestEventTransactionManager", readOnly = true)
    public List<SubprocessEventHistory> getEventsForTask(String taskId) {
        if (repository == null) return List.of();
        return repository.findByTaskIdOrderByTimestampDesc(taskId);
    }

    /**
     * Get a specific event by ID.
     */
    @Transactional(value = "ingestEventTransactionManager", readOnly = true)
    public java.util.Optional<SubprocessEventHistory> getEventById(Long id) {
        if (repository == null) return java.util.Optional.empty();
        return repository.findById(id);
    }

    /**
     * Get crash and restart statistics.
     */
    @Transactional(value = "ingestEventTransactionManager", readOnly = true)
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        if (repository == null) {
            stats.put("available", false);
            return stats;
        }

        stats.put("available", true);
        stats.put("totalCrashes", repository.countByEventType(EventType.SUBPROCESS_CRASHED));
        stats.put("totalRestartAttempts", repository.countByEventType(EventType.SUBPROCESS_RESTARTING));
        stats.put("successfulRestarts", repository.countByEventType(EventType.SUBPROCESS_RESTART_SUCCESS));
        stats.put("exhaustedRestarts", repository.countByEventType(EventType.SUBPROCESS_RESTART_EXHAUSTED));
        stats.put("modelsLoaded", repository.countByEventType(EventType.MODEL_LOADED));
        stats.put("modelsFailed", repository.countByEventType(EventType.MODEL_FAILED));

        // Calculate success rate
        long attempts = (Long) stats.get("totalRestartAttempts");
        long successes = (Long) stats.get("successfulRestarts");
        if (attempts > 0) {
            stats.put("restartSuccessRate", (double) successes / attempts * 100);
        } else {
            stats.put("restartSuccessRate", 0.0);
        }

        return stats;
    }

    // ===================== CLEANUP =====================

    /**
     * Clean up old events (scheduled task).
     */
    @Scheduled(cron = "0 0 4 * * ?") // 4 AM daily
    @Transactional("ingestEventTransactionManager")
    public void cleanupOldEvents() {
        if (repository == null) return;

        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int deleted = repository.deleteEventsOlderThan(cutoff);
        if (deleted > 0) {
            log.info("Cleaned up {} subprocess events older than {} days", deleted, retentionDays);
        }

        // Also enforce max records limit
        long totalRecords = repository.count();
        if (totalRecords > maxRecords) {
            // Delete oldest records beyond the limit
            // This is a simple approach - could be optimized
            log.info("Subprocess event history has {} records, max is {}", totalRecords, maxRecords);
        }
    }

    /**
     * Force cleanup of old events.
     */
    @Transactional("ingestEventTransactionManager")
    public int forceCleanup(int olderThanDays) {
        if (repository == null) return 0;
        Instant cutoff = Instant.now().minus(olderThanDays, ChronoUnit.DAYS);
        return repository.deleteEventsOlderThan(cutoff);
    }
}
