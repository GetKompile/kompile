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

package ai.kompile.app.subprocess.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reports progress from the model initialization subprocess to the main application
 * via STDOUT JSON messages.
 *
 * <p>All messages are prefixed with "MODEL_INIT_MSG:" to distinguish them from other stdout output.
 *
 * <p>This class also manages:
 * <ul>
 *   <li>Heartbeat thread for liveness detection</li>
 *   <li>Phase timing tracking</li>
 *   <li>Progress throttling to prevent spam</li>
 * </ul>
 */
public class ModelInitProgressReporter implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ModelInitProgressReporter.class);

    private final ObjectMapper objectMapper;
    private final PrintStream out;
    private final String taskId;
    private final String modelId;
    private final long startTimeMs;
    private final AtomicLong lastProgressTime = new AtomicLong(0);

    // Phase tracking
    private volatile ModelInitMessage.Phase currentPhase = ModelInitMessage.Phase.STARTING;
    private volatile long currentPhaseStartTime;
    private final Map<ModelInitMessage.Phase, Long> phaseDurations = new EnumMap<>(ModelInitMessage.Phase.class);

    // Heartbeat configuration
    private static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 3_000; // 3 seconds
    private final ScheduledExecutorService heartbeatExecutor;
    private ScheduledFuture<?> heartbeatFuture;

    // Progress throttling
    private static final long PROGRESS_THROTTLE_MS = 100; // 100ms between progress updates

    /**
     * Create a new progress reporter.
     *
     * @param taskId  The task ID for all messages
     * @param modelId The model ID being initialized
     */
    public ModelInitProgressReporter(String taskId, String modelId) {
        this(taskId, modelId, System.out);
    }

    /**
     * Create a new progress reporter with custom output stream.
     *
     * @param taskId  The task ID for all messages
     * @param modelId The model ID being initialized
     * @param out     The output stream to write to
     */
    public ModelInitProgressReporter(String taskId, String modelId, PrintStream out) {
        this.taskId = taskId;
        this.modelId = modelId;
        this.out = out;
        this.objectMapper = new ObjectMapper();
        this.startTimeMs = System.currentTimeMillis();
        this.currentPhaseStartTime = startTimeMs;
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "model-init-heartbeat-" + taskId);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start the heartbeat thread with default interval.
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

    private void sendHeartbeat() {
        try {
            long uptimeMs = System.currentTimeMillis() - startTimeMs;
            send(ModelInitMessage.heartbeat(taskId, modelId, uptimeMs, currentPhase));
        } catch (Exception e) {
            logger.warn("Failed to send heartbeat: {}", e.getMessage());
        }
    }

    /**
     * Report a phase transition.
     *
     * @param toPhase The new phase
     */
    public void reportPhaseTransition(ModelInitMessage.Phase toPhase) {
        long now = System.currentTimeMillis();
        long phaseDuration = now - currentPhaseStartTime;

        // Store duration of completed phase
        if (currentPhase != null) {
            phaseDurations.put(currentPhase, phaseDuration);
        }

        // Send transition message
        send(ModelInitMessage.phaseTransition(taskId, modelId, currentPhase, toPhase, phaseDuration));

        // Update current phase
        currentPhase = toPhase;
        currentPhaseStartTime = now;

        // Also log locally
        logger.info("[{}] Phase transition: {} -> {} (previous phase took {}ms)",
                modelId, currentPhase, toPhase, phaseDuration);
    }

    /**
     * Report progress within current phase.
     *
     * @param overallPercent Overall progress percentage (0-100)
     * @param phasePercent   Progress within current phase (0-100)
     * @param message        Human-readable status message
     */
    public void reportProgress(int overallPercent, int phasePercent, String message) {
        reportProgress(overallPercent, phasePercent, message, null);
    }

    /**
     * Report progress with detailed metrics.
     *
     * @param overallPercent Overall progress percentage (0-100)
     * @param phasePercent   Progress within current phase (0-100)
     * @param message        Human-readable status message
     * @param details        Detailed progress metrics
     */
    public void reportProgress(int overallPercent, int phasePercent, String message,
                               ModelInitMessage.ProgressDetails details) {
        // Throttle progress updates
        long now = System.currentTimeMillis();
        long lastTime = lastProgressTime.get();
        if (now - lastTime < PROGRESS_THROTTLE_MS) {
            return; // Skip this update
        }
        lastProgressTime.set(now);

        send(ModelInitMessage.progress(taskId, modelId, currentPhase, overallPercent, phasePercent, message, details));
    }

    /**
     * Force send a progress update without throttling.
     */
    public void reportProgressImmediate(int overallPercent, int phasePercent, String message) {
        lastProgressTime.set(System.currentTimeMillis());
        send(ModelInitMessage.progress(taskId, modelId, currentPhase, overallPercent, phasePercent, message, null));
    }

    /**
     * Report model information.
     *
     * @param modelType      Model type (dense_encoder, sparse_encoder, etc.)
     * @param encoderClass   Full class name of encoder
     * @param dimensions     Embedding dimensions
     * @param maxSeqLen      Maximum sequence length
     * @param vocabSize      Vocabulary size
     * @param tokenizerType  Tokenizer type
     * @param metadata       Additional metadata
     */
    public void reportModelInfo(String modelType, String encoderClass, int dimensions,
                                int maxSeqLen, int vocabSize, String tokenizerType,
                                Map<String, String> metadata) {
        send(ModelInitMessage.modelInfo(taskId, modelId, modelType, encoderClass,
                dimensions, maxSeqLen, vocabSize, tokenizerType, metadata));
    }

    /**
     * Report successful completion.
     *
     * @param source       Model source (REGISTRY, STAGING, ARCHIVE)
     * @param encoderType  Encoder type name
     * @param dimensions   Embedding dimensions
     * @param maxSeqLen    Maximum sequence length
     * @param metrics      Detailed model metrics
     */
    public void reportCompleted(String source, String encoderType, int dimensions, int maxSeqLen,
                                ModelInitMessage.ModelMetrics metrics) {
        long totalDuration = System.currentTimeMillis() - startTimeMs;

        // Finalize current phase duration
        if (currentPhase != null && currentPhase != ModelInitMessage.Phase.COMPLETE) {
            phaseDurations.put(currentPhase, System.currentTimeMillis() - currentPhaseStartTime);
        }

        send(ModelInitMessage.completed(taskId, modelId, source, encoderType, dimensions, maxSeqLen,
                totalDuration, Map.copyOf(phaseDurations), metrics));

        logger.info("[{}] Model initialization completed in {}ms", modelId, totalDuration);
    }

    /**
     * Report a failure.
     *
     * @param exception The exception that caused the failure
     * @param retriable True if the error might succeed on retry
     */
    public void reportFailed(Throwable exception, boolean retriable) {
        send(ModelInitMessage.failed(taskId, modelId, currentPhase, exception, retriable));
        logger.error("[{}] Model initialization failed in phase {}: {}",
                modelId, currentPhase, exception.getMessage());
    }

    /**
     * Report a failure with explicit details.
     *
     * @param errorMessage Error message
     * @param errorType    Exception type name
     * @param stackTrace   Stack trace
     * @param retriable    True if the error might succeed on retry
     */
    public void reportFailed(String errorMessage, String errorType, String stackTrace, boolean retriable) {
        send(ModelInitMessage.failed(taskId, modelId, currentPhase, errorMessage, errorType, stackTrace, retriable));
        logger.error("[{}] Model initialization failed in phase {}: {}", modelId, currentPhase, errorMessage);
    }

    /**
     * Report a log message.
     *
     * @param level   Log level (INFO, WARN, ERROR, DEBUG, TRACE)
     * @param message The log message
     */
    public void reportLog(String level, String message) {
        reportLog(level, "subprocess", message);
    }

    /**
     * Report a log message with source.
     *
     * @param level   Log level
     * @param source  Logger name or class name
     * @param message The log message
     */
    public void reportLog(String level, String source, String message) {
        // Log locally
        switch (level.toUpperCase()) {
            case "ERROR" -> logger.error("[{}] {}", source, message);
            case "WARN" -> logger.warn("[{}] {}", source, message);
            case "DEBUG" -> logger.debug("[{}] {}", source, message);
            case "TRACE" -> logger.trace("[{}] {}", source, message);
            default -> logger.info("[{}] {}", source, message);
        }

        // Send via protocol
        send(ModelInitMessage.log(taskId, modelId, level.toUpperCase(), source, message));
    }

    /**
     * Get the current phase.
     */
    public ModelInitMessage.Phase getCurrentPhase() {
        return currentPhase;
    }

    /**
     * Get phase durations collected so far.
     */
    public Map<ModelInitMessage.Phase, Long> getPhaseDurations() {
        return Map.copyOf(phaseDurations);
    }

    /**
     * Get elapsed time since reporter creation.
     */
    public long getElapsedMs() {
        return System.currentTimeMillis() - startTimeMs;
    }

    /**
     * Get the task ID.
     */
    public String getTaskId() {
        return taskId;
    }

    /**
     * Get the model ID.
     */
    public String getModelId() {
        return modelId;
    }

    /**
     * Send a message to stdout.
     */
    private synchronized void send(ModelInitMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            out.println(ModelInitMessage.MESSAGE_PREFIX + json);
            out.flush();
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize model init message: {}", e.getMessage(), e);
        }
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
