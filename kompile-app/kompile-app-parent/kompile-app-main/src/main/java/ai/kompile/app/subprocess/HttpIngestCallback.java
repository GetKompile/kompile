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

package ai.kompile.app.subprocess;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP client for sending callbacks from the subprocess to the main application.
 *
 * This class is used to persist events to IngestEventService and IndexingJobHistoryService
 * in the main application. HTTP callbacks ensure events are logged even if the subprocess
 * crashes mid-processing.
 *
 * Uses Java's built-in HttpClient to avoid Spring dependencies in the lightweight subprocess.
 */
public class HttpIngestCallback implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(HttpIngestCallback.class);

    private static final String EVENTS_PATH = "/api/internal/ingest/events/log";
    private static final String JOB_HISTORY_PATH = "/api/internal/ingest/job-history/update";

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    // Retry configuration
    private static final int MAX_RETRIES = 3;
    private static final Duration RETRY_DELAY = Duration.ofMillis(500);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Create a new HTTP callback client.
     *
     * @param baseUrl Base URL of the main application (e.g., "http://localhost:8080")
     */
    public HttpIngestCallback(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        this.objectMapper = JsonUtils.standardMapper();

        // Force early class loading of inner records to avoid NoClassDefFoundError
        // when JVM is under memory pressure during error handling
        forceLoadInnerClasses();
    }

    /**
     * Force load inner classes to ensure they're available even under memory pressure.
     * This is critical for error handling paths where memory may be exhausted.
     */
    private void forceLoadInnerClasses() {
        try {
            // Access inner class constructors to force class loading
            Class.forName(EventCallback.class.getName());
            Class.forName(JobHistoryCallback.class.getName());
            logger.debug("Pre-loaded HttpIngestCallback inner classes for error handling");
        } catch (ClassNotFoundException e) {
            logger.warn("Failed to pre-load inner classes: {}", e.getMessage());
        }
    }

    /**
     * Log that a task has been queued.
     */
    public void logQueued(String taskId, String fileName) {
        sendEventCallback(new EventCallback(taskId, fileName, "QUEUED", null, null, null, null, null));
    }

    /**
     * Log that a phase has started.
     */
    public void logPhaseStarted(String taskId, String fileName, String phase, String previousPhase) {
        sendEventCallback(new EventCallback(taskId, fileName, "PHASE_STARTED", phase, previousPhase, null, null, null));
    }

    /**
     * Log progress within a phase.
     */
    public void logProgress(String taskId, String fileName, String phase, int itemsProcessed, int totalItems, String message) {
        logProgress(taskId, fileName, phase, itemsProcessed, totalItems, message, -1);
    }

    /**
     * Log progress within a phase with explicit progress percentage.
     * Use this when progress is calculated with time-based estimation rather than just items processed.
     */
    public void logProgress(String taskId, String fileName, String phase, int itemsProcessed, int totalItems, String message, int progressPercent) {
        Map<String, Object> details = new HashMap<>();
        details.put("itemsProcessed", itemsProcessed);
        details.put("totalItems", totalItems);
        details.put("message", message);
        if (progressPercent >= 0) {
            details.put("progressPercent", progressPercent);
        }
        sendEventCallback(new EventCallback(taskId, fileName, "PROGRESS", phase, null, null, null, details));
    }

    /**
     * Log that a phase has completed.
     */
    public void logPhaseCompleted(String taskId, String fileName, String phase, int itemsProcessed, String message) {
        Map<String, Object> details = new HashMap<>();
        details.put("itemsProcessed", itemsProcessed);
        details.put("message", message);
        sendEventCallback(new EventCallback(taskId, fileName, "PHASE_COMPLETED", phase, null, null, null, details));
    }

    /**
     * Log task completion.
     */
    public void logCompleted(String taskId, String fileName, int totalItemsProcessed, String summary) {
        Map<String, Object> details = new HashMap<>();
        details.put("totalItemsProcessed", totalItemsProcessed);
        details.put("summary", summary);
        sendEventCallback(new EventCallback(taskId, fileName, "COMPLETED", "COMPLETED", null, null, null, details));
    }

    /**
     * Log a failure.
     */
    public void logFailed(String taskId, String fileName, String phase, String errorMessage, String stackTrace) {
        sendEventCallback(new EventCallback(taskId, fileName, "FAILED", phase, null, errorMessage, stackTrace, null));
    }

    /**
     * Log that a task was cancelled.
     */
    public void logCancelled(String taskId, String fileName, String phase, String reason) {
        Map<String, Object> details = new HashMap<>();
        details.put("reason", reason);
        sendEventCallback(new EventCallback(taskId, fileName, "CANCELLED", phase, null, null, null, details));
    }

    /**
     * Log that a task was killed due to memory pressure.
     */
    public void logMemoryKilled(String taskId, String fileName, String phase, double memoryPercent, double killThreshold) {
        Map<String, Object> details = new HashMap<>();
        details.put("memoryPercent", memoryPercent);
        details.put("killThreshold", killThreshold);
        sendEventCallback(new EventCallback(taskId, fileName, "MEMORY_KILLED", phase, null, null, null, details));
    }

    /**
     * Log that a subprocess restart is being attempted.
     *
     * @param taskId Task identifier
     * @param fileName File being processed
     * @param phase Phase where crash occurred
     * @param attemptNumber Current restart attempt number (1-based)
     * @param maxAttempts Maximum restart attempts configured
     * @param reason Reason for restart (e.g., "OUT_OF_MEMORY", "OOM_KILLED")
     * @param newHeapBytes New heap size after adjustment
     * @param newBatchSize New batch size after adjustment
     * @param newThreadCount New thread count after adjustment
     * @param backoffMs Backoff time before restart in milliseconds
     */
    public void logRestarting(String taskId, String fileName, String phase,
                               int attemptNumber, int maxAttempts, String reason,
                               long newHeapBytes, int newBatchSize, int newThreadCount, long backoffMs) {
        Map<String, Object> details = new HashMap<>();
        details.put("attemptNumber", attemptNumber);
        details.put("maxAttempts", maxAttempts);
        details.put("reason", reason);
        details.put("newHeapBytes", newHeapBytes);
        details.put("newBatchSize", newBatchSize);
        details.put("newThreadCount", newThreadCount);
        details.put("backoffMs", backoffMs);
        sendEventCallback(new EventCallback(taskId, fileName, "RESTARTING", phase, null, null, null, details));
    }

    /**
     * Log that a subprocess restart completed (subprocess is back up).
     *
     * @param taskId Task identifier
     * @param fileName File being processed
     * @param attemptNumber The attempt that succeeded
     */
    public void logRestartCompleted(String taskId, String fileName, int attemptNumber) {
        Map<String, Object> details = new HashMap<>();
        details.put("attemptNumber", attemptNumber);
        details.put("restartedSuccessfully", true);
        sendEventCallback(new EventCallback(taskId, fileName, "RESTART_COMPLETED", null, null, null, null, details));
    }

    /**
     * Log that all restart attempts have been exhausted.
     *
     * @param taskId Task identifier
     * @param fileName File being processed
     * @param totalAttempts Total restart attempts made
     * @param lastReason Last failure reason
     */
    public void logRestartExhausted(String taskId, String fileName, int totalAttempts, String lastReason) {
        Map<String, Object> details = new HashMap<>();
        details.put("totalAttempts", totalAttempts);
        details.put("lastReason", lastReason);
        sendEventCallback(new EventCallback(taskId, fileName, "RESTART_EXHAUSTED", null, null,
            "All " + totalAttempts + " restart attempts exhausted", null, details));
    }

    /**
     * Update job history to mark job as running.
     */
    public void markJobRunning(String taskId) {
        sendJobHistoryCallback(new JobHistoryCallback(taskId, "RUNNING", null, null, null, null, null));
    }

    /**
     * Update job history progress.
     */
    public void updateJobProgress(String taskId, String phase, int progressPercent) {
        sendJobHistoryCallback(new JobHistoryCallback(taskId, null, phase, progressPercent, null, null, null));
    }

    /**
     * Update job history with stats.
     */
    public void updateJobStats(String taskId, int docsLoaded, int chunksCreated, int chunksEmbedded, int docsIndexed) {
        Map<String, Object> stats = new HashMap<>();
        stats.put("documentsLoaded", docsLoaded);
        stats.put("chunksCreated", chunksCreated);
        stats.put("chunksEmbedded", chunksEmbedded);
        stats.put("documentsIndexed", docsIndexed);
        sendJobHistoryCallback(new JobHistoryCallback(taskId, null, null, null, null, null, stats));
    }

    /**
     * Update job history with phase timing.
     */
    public void updatePhaseTiming(String taskId, String phase, long durationMs) {
        Map<String, Object> stats = new HashMap<>();
        stats.put("phaseDurationMs", durationMs);
        stats.put("phase", phase);
        sendJobHistoryCallback(new JobHistoryCallback(taskId, null, phase, null, null, null, stats));
    }

    /**
     * Mark job as completed.
     */
    public void markJobCompleted(String taskId) {
        sendJobHistoryCallback(new JobHistoryCallback(taskId, "COMPLETED", "COMPLETED", 100, null, null, null));
    }

    /**
     * Mark job as failed.
     */
    public void markJobFailed(String taskId, String phase, String errorMessage, String failureReason) {
        sendJobHistoryCallback(new JobHistoryCallback(taskId, "FAILED", phase, null, errorMessage, failureReason, null, null));
    }

    /**
     * Initialize restart tracking for a job.
     *
     * @param taskId Task identifier
     * @param maxAttempts Maximum restart attempts configured
     * @param heapBytes Initial heap size in bytes
     * @param batchSize Initial batch size
     * @param threadCount Initial thread count
     */
    public void initializeRestartTracking(String taskId, int maxAttempts, long heapBytes, int batchSize, int threadCount) {
        RestartInfo restartInfo = new RestartInfo(
            0,              // restartAttempts
            maxAttempts,    // maxRestartAttempts
            heapBytes,      // initialHeapBytes
            heapBytes,      // finalHeapBytes
            batchSize,      // initialBatchSize
            batchSize,      // finalBatchSize
            threadCount,    // initialThreadCount
            threadCount,    // finalThreadCount
            null,           // reason
            false           // recoveredAfterRestart
        );
        sendJobHistoryCallback(new JobHistoryCallback(taskId, null, null, null, null, null, null, restartInfo));
    }

    /**
     * Record a restart attempt in job history.
     *
     * @param taskId Task identifier
     * @param attemptNumber Current attempt number (1-based)
     * @param maxAttempts Maximum restart attempts
     * @param reason Reason for restart
     * @param newHeapBytes New heap size after adjustment
     * @param newBatchSize New batch size after adjustment
     * @param newThreadCount New thread count after adjustment
     */
    public void recordJobRestartAttempt(String taskId, int attemptNumber, int maxAttempts, String reason,
                                         long newHeapBytes, int newBatchSize, int newThreadCount) {
        RestartInfo restartInfo = new RestartInfo(
            attemptNumber,
            maxAttempts,
            null,           // initialHeapBytes - not changed
            newHeapBytes,
            null,           // initialBatchSize - not changed
            newBatchSize,
            null,           // initialThreadCount - not changed
            newThreadCount,
            reason,
            false           // recoveredAfterRestart - not yet
        );
        sendJobHistoryCallback(new JobHistoryCallback(taskId, null, null, null, null, null, null, restartInfo));
    }

    /**
     * Mark job restart as successful (job recovered and completed after restart).
     *
     * @param taskId Task identifier
     */
    public void markJobRestartSuccessful(String taskId) {
        RestartInfo restartInfo = new RestartInfo(
            null, null, null, null, null, null, null, null, null, true
        );
        sendJobHistoryCallback(new JobHistoryCallback(taskId, null, null, null, null, null, null, restartInfo));
    }

    /**
     * Send an event callback to the main application.
     */
    private void sendEventCallback(EventCallback callback) {
        sendPost(EVENTS_PATH, callback);
    }

    /**
     * Send a job history callback to the main application.
     */
    private void sendJobHistoryCallback(JobHistoryCallback callback) {
        sendPost(JOB_HISTORY_PATH, callback);
    }

    /**
     * Send a POST request with JSON body.
     */
    private void sendPost(String path, Object body) {
        String url = baseUrl + path;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String json = objectMapper.writeValueAsString(body);

                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(REQUEST_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    logger.debug("HTTP callback successful: {} -> {}", path, response.statusCode());
                    return;
                } else {
                    logger.warn("HTTP callback returned error: {} -> {} (attempt {}/{})",
                               path, response.statusCode(), attempt, MAX_RETRIES);
                }

            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    logger.warn("HTTP callback interrupted: {}", path);
                    return;
                }
                logger.warn("HTTP callback failed: {} -> {} (attempt {}/{})",
                           path, e.getMessage(), attempt, MAX_RETRIES);
            }

            // Wait before retry (except on last attempt)
            if (attempt < MAX_RETRIES) {
                try {
                    Thread.sleep(RETRY_DELAY.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        logger.error("HTTP callback failed after {} attempts: {}", MAX_RETRIES, path);
    }

    @Override
    public void close() {
        // HttpClient doesn't need explicit close in Java 11+
    }

    /**
     * Event callback payload.
     */
    public record EventCallback(
        String taskId,
        String fileName,
        String eventType,
        String phase,
        String previousPhase,
        String errorMessage,
        String stackTrace,
        Map<String, Object> details
    ) {}

    /**
     * Job history callback payload.
     */
    public record JobHistoryCallback(
        String taskId,
        String status,
        String phase,
        Integer progressPercent,
        String errorMessage,
        String failureReason,
        Map<String, Object> stats,
        RestartInfo restartInfo
    ) {
        /**
         * Constructor without restart info for backwards compatibility.
         */
        public JobHistoryCallback(String taskId, String status, String phase, Integer progressPercent,
                                   String errorMessage, String failureReason, Map<String, Object> stats) {
            this(taskId, status, phase, progressPercent, errorMessage, failureReason, stats, null);
        }
    }

    /**
     * Restart tracking information.
     */
    public record RestartInfo(
        Integer restartAttempts,
        Integer maxRestartAttempts,
        Long initialHeapBytes,
        Long finalHeapBytes,
        Integer initialBatchSize,
        Integer finalBatchSize,
        Integer initialThreadCount,
        Integer finalThreadCount,
        String reason,
        Boolean recoveredAfterRestart
    ) {}
}
