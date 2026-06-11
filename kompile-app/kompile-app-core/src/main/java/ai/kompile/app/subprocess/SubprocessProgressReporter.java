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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reports progress from the subprocess to the main application via STDOUT JSON messages.
 *
 * All messages are prefixed with "INGEST_MSG:" to distinguish them from other stdout output
 * (such as log messages or debug output from native libraries).
 *
 * This class also manages a heartbeat thread that periodically sends liveness signals
 * to help the main application detect stuck subprocesses.
 */
public class SubprocessProgressReporter implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SubprocessProgressReporter.class);

    private final ObjectMapper objectMapper;
    private final PrintStream out;
    private final String taskId;
    private final long startTimeMs;
    private final AtomicLong lastProgressTime = new AtomicLong(0);

    // Heartbeat configuration - reduced for faster stall detection
    private static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 3_000; // 3 seconds (was 10s)
    private final ScheduledExecutorService heartbeatExecutor;
    private ScheduledFuture<?> heartbeatFuture;

    // Progress throttling - minimum interval between progress updates (reduced for more frequent UI updates)
    private static final long PROGRESS_THROTTLE_MS = 50;

    /**
     * Create a new progress reporter.
     *
     * @param taskId The task ID for all messages
     */
    public SubprocessProgressReporter(String taskId) {
        this(taskId, System.out);
    }

    /**
     * Create a new progress reporter with custom output stream (mainly for testing).
     *
     * @param taskId The task ID for all messages
     * @param out The output stream to write to
     */
    public SubprocessProgressReporter(String taskId, PrintStream out) {
        this.taskId = taskId;
        this.out = out;
        this.objectMapper = new ObjectMapper();
        this.startTimeMs = System.currentTimeMillis();
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ingest-heartbeat-" + taskId);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start the heartbeat thread.
     * Heartbeats are sent at regular intervals to help the main app detect stuck processes.
     */
    public void startHeartbeat() {
        startHeartbeat(DEFAULT_HEARTBEAT_INTERVAL_MS);
    }

    /**
     * Start the heartbeat thread with custom interval.
     *
     * @param intervalMs Interval between heartbeats in milliseconds
     */
    public void startHeartbeat(long intervalMs) {
        if (heartbeatFuture != null && !heartbeatFuture.isCancelled()) {
            return; // Already running
        }

        heartbeatFuture = heartbeatExecutor.scheduleAtFixedRate(
            this::sendHeartbeat,
            intervalMs,
            intervalMs,
            TimeUnit.MILLISECONDS
        );
        logger.debug("Started heartbeat thread with {}ms interval", intervalMs);
    }

    /**
     * Stop the heartbeat thread.
     */
    public void stopHeartbeat() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
            logger.debug("Stopped heartbeat thread");
        }
    }

    /**
     * Send a heartbeat message.
     */
    private void sendHeartbeat() {
        try {
            long uptimeMs = System.currentTimeMillis() - startTimeMs;
            SubprocessMessage.Heartbeat heartbeat = SubprocessMessage.heartbeat(taskId, uptimeMs);
            send(heartbeat);
        } catch (Exception e) {
            logger.warn("Failed to send heartbeat: {}", e.getMessage());
        }
    }

    /**
     * Report that the subprocess is ready to accept work.
     * Should be called once after model loading and initialization complete.
     *
     * @param subprocessType  e.g. "embedding", "vector-population", "ingest"
     * @param modelId         model identifier if applicable (null otherwise)
     * @param modelDimension  embedding dimension if applicable (null otherwise)
     */
    public void reportReady(String subprocessType, String modelId, Integer modelDimension) {
        long startupTimeMs = System.currentTimeMillis() - startTimeMs;
        send(SubprocessMessage.ready(taskId, startupTimeMs, subprocessType, modelId, modelDimension));
        logger.info("Reported READY: type={}, model={}, dimension={}, startupTime={}ms",
                subprocessType, modelId, modelDimension, startupTimeMs);
    }

    /**
     * Report a phase transition.
     *
     * @param fromPhase Previous phase (null if starting)
     * @param toPhase New phase
     * @param durationMs Duration of the completed phase (0 if starting)
     */
    public void reportPhaseTransition(String fromPhase, String toPhase, long durationMs) {
        send(SubprocessMessage.phaseTransition(taskId, fromPhase, toPhase, durationMs));
    }

    /**
     * Report a phase start.
     *
     * @param phase The phase being started
     */
    public void reportPhaseStart(String phase) {
        reportPhaseTransition(null, phase, 0);
    }

    /**
     * Report progress within a phase.
     *
     * @param phase Current phase
     * @param percent Progress percentage (0-100)
     * @param step Current step description
     * @param message Status message
     */
    public void reportProgress(String phase, int percent, String step, String message) {
        // Throttle progress updates
        long now = System.currentTimeMillis();
        long lastTime = lastProgressTime.get();
        if (now - lastTime < PROGRESS_THROTTLE_MS) {
            return; // Skip this update
        }
        lastProgressTime.set(now);

        send(SubprocessMessage.progress(taskId, phase, percent, step, message));
    }

    /**
     * Report progress with detailed statistics.
     *
     * @param phase Current phase
     * @param percent Progress percentage (0-100)
     * @param step Current step description
     * @param message Status message
     * @param stats Detailed statistics
     */
    public void reportProgress(String phase, int percent, String step, String message,
                               SubprocessMessage.ProgressStats stats) {
        // Throttle progress updates
        long now = System.currentTimeMillis();
        long lastTime = lastProgressTime.get();
        if (now - lastTime < PROGRESS_THROTTLE_MS) {
            return; // Skip this update
        }
        lastProgressTime.set(now);

        send(SubprocessMessage.progress(taskId, phase, percent, step, message, stats));
    }

    /**
     * Force send a progress update without throttling (for important milestones).
     */
    public void reportProgressImmediate(String phase, int percent, String step, String message) {
        lastProgressTime.set(System.currentTimeMillis());
        send(SubprocessMessage.progress(taskId, phase, percent, step, message));
    }

    /**
     * Force send a progress update with stats without throttling (for important milestones).
     */
    public void reportProgressImmediate(String phase, int percent, String step, String message,
                                        SubprocessMessage.ProgressStats stats) {
        lastProgressTime.set(System.currentTimeMillis());
        send(SubprocessMessage.progress(taskId, phase, percent, step, message, stats));
    }

    /**
     * Report worker status.
     *
     * @param workerId Worker identifier
     * @param workerType Type of worker (CHUNKING, EMBEDDING, INDEXING)
     * @param status Current status (IDLE, PROCESSING, WAITING, COMPLETE)
     * @param itemsProcessed Number of items processed
     * @param batchSize Current batch size
     * @param throughput Items per second
     * @param currentItem Currently processing item (may be null)
     */
    public void reportWorkerStatus(String workerId, String workerType, String status,
                                   int itemsProcessed, int batchSize, double throughput,
                                   String currentItem) {
        send(SubprocessMessage.workerStatus(taskId, workerId, workerType, status,
                                           itemsProcessed, batchSize, throughput, currentItem));
    }

    /**
     * Report successful completion.
     *
     * @param documentsLoaded Number of documents loaded
     * @param chunksCreated Number of chunks created
     * @param chunksEmbedded Number of chunks embedded
     * @param documentsIndexed Number of documents indexed
     * @param tokensProcessed Number of tokens processed in this run
     * @param totalTokensInIndex Total tokens in the index after completion
     * @param indexPath Path to the index
     * @param phaseDurations Duration of each phase in milliseconds
     */
    public void reportCompleted(int documentsLoaded, int chunksCreated,
                                int chunksEmbedded, int documentsIndexed,
                                long tokensProcessed, long totalTokensInIndex,
                                String indexPath, Map<String, Long> phaseDurations) {
        long totalDuration = System.currentTimeMillis() - startTimeMs;
        send(SubprocessMessage.completed(taskId, documentsLoaded, chunksCreated,
                                        chunksEmbedded, documentsIndexed,
                                        tokensProcessed, totalTokensInIndex,
                                        totalDuration, indexPath, phaseDurations));
    }

    /**
     * Report a failure.
     *
     * @param phase Phase where failure occurred
     * @param exception The exception that caused the failure
     */
    public void reportFailed(String phase, Throwable exception) {
        send(SubprocessMessage.failed(taskId, phase, exception));
    }

    /**
     * Report a failure with explicit error details.
     *
     * @param phase Phase where failure occurred
     * @param errorMessage Error message
     * @param errorType Exception type name
     * @param stackTrace Stack trace (may be null)
     */
    public void reportFailed(String phase, String errorMessage, String errorType, String stackTrace) {
        send(SubprocessMessage.failed(taskId, phase, errorMessage, errorType, stackTrace));
    }

    /**
     * Report a log message. Sends both to local logger and via protocol for real-time display.
     *
     * @param level Log level (INFO, WARN, ERROR, DEBUG, TRACE)
     * @param message The log message
     */
    public void reportLog(String level, String message) {
        reportLog(level, "subprocess", message);
    }

    /**
     * Report a log message with source. Sends both to local logger and via protocol for real-time display.
     *
     * @param level Log level (INFO, WARN, ERROR, DEBUG, TRACE)
     * @param source Logger name or class name
     * @param message The log message
     */
    public void reportLog(String level, String source, String message) {
        // Log locally for subprocess stdout
        switch (level.toUpperCase()) {
            case "ERROR":
                logger.error("[{}] {}", source, message);
                break;
            case "WARN":
                logger.warn("[{}] {}", source, message);
                break;
            case "DEBUG":
                logger.debug("[{}] {}", source, message);
                break;
            case "TRACE":
                logger.trace("[{}] {}", source, message);
                break;
            default:
                logger.info("[{}] {}", source, message);
        }

        // Send via protocol for real-time display in UI
        send(SubprocessMessage.log(taskId, level.toUpperCase(), source, message));
    }

    /**
     * Send a message to stdout.
     *
     * @param message The message to send
     */
    private synchronized void send(SubprocessMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            out.println(SubprocessMessage.MESSAGE_PREFIX + json);
            out.flush();
        } catch (JsonProcessingException e) {
            // Log to stderr since stdout is for protocol messages
            System.err.println("Failed to serialize subprocess message: " + e.getMessage());
            logger.error("Failed to serialize subprocess message", e);
        }
    }

    /**
     * Get the task ID.
     */
    public String getTaskId() {
        return taskId;
    }

    /**
     * Get the elapsed time since the reporter was created.
     */
    public long getElapsedMs() {
        return System.currentTimeMillis() - startTimeMs;
    }

    @Override
    public void close() {
        stopHeartbeat();
        heartbeatExecutor.shutdown();
        try {
            if (!heartbeatExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                heartbeatExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            heartbeatExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
