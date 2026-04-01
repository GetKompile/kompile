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

package ai.kompile.app.web.controllers;

import ai.kompile.app.ingest.domain.IndexingJobHistory;
import ai.kompile.app.ingest.domain.IngestEvent;
import ai.kompile.app.ingest.service.IndexingJobHistoryService;
import ai.kompile.app.ingest.service.IngestEventService;
import ai.kompile.app.subprocess.HttpIngestCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Internal REST controller for handling callbacks from ingest subprocesses.
 *
 * These endpoints are called by the subprocess to persist events and update job history,
 * AND to broadcast progress updates to WebSocket for real-time UI updates.
 *
 * Note: In production, consider binding these endpoints to localhost only or using
 * authentication to prevent unauthorized access.
 */
@RestController
@RequestMapping("/api/internal/ingest")
public class InternalIngestCallbackController {

    private static final Logger logger = LoggerFactory.getLogger(InternalIngestCallbackController.class);

    private final IngestEventService eventService;
    private final IndexingJobHistoryService jobHistoryService;

    @Autowired
    public InternalIngestCallbackController(
            @Autowired(required = false) IngestEventService eventService,
            @Autowired(required = false) IndexingJobHistoryService jobHistoryService
    ) {
        this.eventService = eventService;
        this.jobHistoryService = jobHistoryService;
    }

    /**
     * Log an ingest event from a subprocess.
     *
     * @param callback Event callback data
     * @return 200 OK on success
     */
    @PostMapping("/events/log")
    public ResponseEntity<Void> logEvent(@RequestBody HttpIngestCallback.EventCallback callback) {
        logger.debug("Received event callback: task={}, type={}, phase={}",
                    callback.taskId(), callback.eventType(), callback.phase());

        if (eventService == null) {
            logger.debug("Event service not available, ignoring callback");
            return ResponseEntity.ok().build();
        }

        try {
            switch (callback.eventType()) {
                case "QUEUED" -> eventService.logQueued(callback.taskId(), callback.fileName());

                case "PHASE_STARTED" -> {
                    IngestEvent.IngestPhase phase = parsePhase(callback.phase());
                    IngestEvent.IngestPhase previousPhase = callback.previousPhase() != null ?
                        parsePhase(callback.previousPhase()) : null;
                    eventService.logPhaseStarted(callback.taskId(), callback.fileName(), phase, previousPhase);
                }

                case "PROGRESS" -> {
                    IngestEvent.IngestPhase phase = parsePhase(callback.phase());
                    int itemsProcessed = getIntFromDetails(callback.details(), "itemsProcessed", 0);
                    int totalItems = getIntFromDetails(callback.details(), "totalItems", 0);
                    String message = getStringFromDetails(callback.details(), "message", "");
                    eventService.logProgress(callback.taskId(), callback.fileName(), phase,
                                            itemsProcessed, totalItems, message);
                    // Note: WebSocket broadcasting is handled by SubprocessIngestLauncher via STDOUT parsing
                    // HTTP callbacks are only for persistence, not real-time UI updates
                }

                case "PHASE_COMPLETED" -> {
                    IngestEvent.IngestPhase phase = parsePhase(callback.phase());
                    int itemsProcessed = getIntFromDetails(callback.details(), "itemsProcessed", 0);
                    String message = getStringFromDetails(callback.details(), "message", "");
                    eventService.logPhaseCompleted(callback.taskId(), callback.fileName(), phase,
                                                  itemsProcessed, message);
                }

                case "COMPLETED" -> {
                    int totalItems = getIntFromDetails(callback.details(), "totalItemsProcessed", 0);
                    String summary = getStringFromDetails(callback.details(), "summary", "Completed");
                    eventService.logCompleted(callback.taskId(), callback.fileName(), totalItems, summary);
                }

                case "FAILED" -> {
                    IngestEvent.IngestPhase phase = parsePhase(callback.phase());
                    eventService.logFailed(callback.taskId(), callback.fileName(), phase,
                                          callback.errorMessage(), null);
                }

                case "CANCELLED" -> {
                    IngestEvent.IngestPhase phase = parsePhase(callback.phase());
                    String reason = getStringFromDetails(callback.details(), "reason", "Cancelled");
                    eventService.logCancelled(callback.taskId(), callback.fileName(), phase, reason);
                }

                case "MEMORY_KILLED" -> {
                    IngestEvent.IngestPhase phase = parsePhase(callback.phase());
                    double memoryPercent = getDoubleFromDetails(callback.details(), "memoryPercent", 0);
                    int killThreshold = getIntFromDetails(callback.details(), "killThreshold", 0);
                    eventService.logMemoryKilled(callback.taskId(), callback.fileName(), phase,
                                                memoryPercent, killThreshold, 0, "Subprocess OOM");
                }

                case "RESTARTING" -> {
                    // Log restart event in the event service
                    IngestEvent.IngestPhase phase = parsePhase(callback.phase());
                    int attemptNumber = getIntFromDetails(callback.details(), "attemptNumber", 1);
                    int maxAttempts = getIntFromDetails(callback.details(), "maxAttempts", 3);
                    String reason = getStringFromDetails(callback.details(), "reason", "UNKNOWN");
                    long newHeapBytes = getLongFromDetails(callback.details(), "newHeapBytes", 0);
                    int newBatchSize = getIntFromDetails(callback.details(), "newBatchSize", 0);
                    int newThreadCount = getIntFromDetails(callback.details(), "newThreadCount", 0);
                    long backoffMs = getLongFromDetails(callback.details(), "backoffMs", 0);

                    logger.info("Subprocess restart attempt {} for task {} (reason: {}, heap: {}MB, batch: {}, threads: {})",
                               attemptNumber, callback.taskId(), reason, newHeapBytes / (1024 * 1024),
                               newBatchSize, newThreadCount);

                    // Also update job history directly with restart attempt
                    if (jobHistoryService != null) {
                        jobHistoryService.recordRestartAttempt(callback.taskId(), attemptNumber, reason,
                                                               newHeapBytes, newBatchSize, newThreadCount);
                    }
                }

                case "RESTART_COMPLETED" -> {
                    int attemptNumber = getIntFromDetails(callback.details(), "attemptNumber", 1);
                    logger.info("Subprocess restart completed for task {} (attempt {})",
                               callback.taskId(), attemptNumber);
                    // Don't mark as successful yet - that's done when job completes
                }

                case "RESTART_EXHAUSTED" -> {
                    int totalAttempts = getIntFromDetails(callback.details(), "totalAttempts", 0);
                    String lastReason = getStringFromDetails(callback.details(), "lastReason", "UNKNOWN");
                    logger.warn("All restart attempts exhausted for task {} ({} attempts, last reason: {})",
                               callback.taskId(), totalAttempts, lastReason);
                }

                default -> logger.warn("Unknown event type: {}", callback.eventType());
            }
        } catch (Exception e) {
            logger.error("Failed to process event callback: {}", e.getMessage(), e);
            // Still return OK to prevent subprocess from retrying
        }

        return ResponseEntity.ok().build();
    }

    /**
     * Update job history from a subprocess.
     *
     * @param callback Job history callback data
     * @return 200 OK on success
     */
    @PostMapping("/job-history/update")
    public ResponseEntity<Void> updateJobHistory(@RequestBody HttpIngestCallback.JobHistoryCallback callback) {
        logger.debug("Received job history callback: task={}, status={}, phase={}",
                    callback.taskId(), callback.status(), callback.phase());

        if (jobHistoryService == null) {
            logger.debug("Job history service not available, ignoring callback");
            return ResponseEntity.ok().build();
        }

        try {
            // Update status if provided
            if (callback.status() != null) {
                switch (callback.status()) {
                    case "RUNNING" -> jobHistoryService.markJobRunning(callback.taskId());
                    case "COMPLETED" -> jobHistoryService.markJobCompleted(callback.taskId());
                    case "FAILED" -> {
                        IndexingJobHistory.FailureReason reason = callback.failureReason() != null ?
                            IndexingJobHistory.FailureReason.valueOf(callback.failureReason()) :
                            IndexingJobHistory.FailureReason.UNKNOWN;
                        IngestEvent.IngestPhase failedPhase = parsePhase(callback.phase());
                        jobHistoryService.markJobFailed(callback.taskId(), failedPhase,
                                                       callback.errorMessage(), null, reason);
                    }
                }
            }

            // Update progress if provided
            if (callback.progressPercent() != null && callback.phase() != null) {
                IngestEvent.IngestPhase progressPhase = parsePhase(callback.phase());
                jobHistoryService.updateJobProgress(callback.taskId(), progressPhase,
                                                   callback.progressPercent());
            }

            // Update stats if provided
            if (callback.stats() != null) {
                Map<String, Object> stats = callback.stats();

                // Update phase timing
                if (stats.containsKey("phaseDurationMs") && stats.containsKey("phase")) {
                    long durationMs = ((Number) stats.get("phaseDurationMs")).longValue();
                    String phaseStr = (String) stats.get("phase");
                    IngestEvent.IngestPhase timingPhase = parsePhase(phaseStr);
                    jobHistoryService.updatePhaseTiming(callback.taskId(), timingPhase, durationMs);
                }

                // Update document counts
                if (stats.containsKey("documentsLoaded") || stats.containsKey("chunksCreated")) {
                    int docsLoaded = stats.containsKey("documentsLoaded") ?
                        ((Number) stats.get("documentsLoaded")).intValue() : 0;
                    int chunksCreated = stats.containsKey("chunksCreated") ?
                        ((Number) stats.get("chunksCreated")).intValue() : 0;
                    int chunksEmbedded = stats.containsKey("chunksEmbedded") ?
                        ((Number) stats.get("chunksEmbedded")).intValue() : 0;
                    int docsIndexed = stats.containsKey("documentsIndexed") ?
                        ((Number) stats.get("documentsIndexed")).intValue() : 0;
                    jobHistoryService.updateJobStats(callback.taskId(), docsLoaded, chunksCreated,
                                                    chunksEmbedded, docsIndexed);
                }
            }

            // Handle restart info if provided
            if (callback.restartInfo() != null) {
                HttpIngestCallback.RestartInfo restartInfo = callback.restartInfo();
                jobHistoryService.updateRestartInfo(
                    callback.taskId(),
                    restartInfo.restartAttempts(),
                    restartInfo.maxRestartAttempts(),
                    restartInfo.initialHeapBytes(),
                    restartInfo.finalHeapBytes(),
                    restartInfo.initialBatchSize(),
                    restartInfo.finalBatchSize(),
                    restartInfo.initialThreadCount(),
                    restartInfo.finalThreadCount(),
                    restartInfo.reason(),
                    restartInfo.recoveredAfterRestart()
                );
            }

        } catch (Exception e) {
            logger.error("Failed to process job history callback: {}", e.getMessage(), e);
            // Still return OK to prevent subprocess from retrying
        }

        return ResponseEntity.ok().build();
    }

    /**
     * Parse phase string to enum.
     */
    private IngestEvent.IngestPhase parsePhase(String phase) {
        if (phase == null) {
            return IngestEvent.IngestPhase.QUEUED;
        }
        try {
            return IngestEvent.IngestPhase.valueOf(phase.toUpperCase());
        } catch (IllegalArgumentException e) {
            logger.warn("Unknown phase: {}", phase);
            return IngestEvent.IngestPhase.QUEUED;
        }
    }

    /**
     * Get int from details map.
     */
    private int getIntFromDetails(Map<String, Object> details, String key, int defaultValue) {
        if (details == null || !details.containsKey(key)) {
            return defaultValue;
        }
        Object value = details.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }

    /**
     * Get double from details map.
     */
    private double getDoubleFromDetails(Map<String, Object> details, String key, double defaultValue) {
        if (details == null || !details.containsKey(key)) {
            return defaultValue;
        }
        Object value = details.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return defaultValue;
    }

    /**
     * Get long from details map.
     */
    private long getLongFromDetails(Map<String, Object> details, String key, long defaultValue) {
        if (details == null || !details.containsKey(key)) {
            return defaultValue;
        }
        Object value = details.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return defaultValue;
    }

    /**
     * Get string from details map.
     */
    private String getStringFromDetails(Map<String, Object> details, String key, String defaultValue) {
        if (details == null || !details.containsKey(key)) {
            return defaultValue;
        }
        Object value = details.get(key);
        return value != null ? value.toString() : defaultValue;
    }
}
