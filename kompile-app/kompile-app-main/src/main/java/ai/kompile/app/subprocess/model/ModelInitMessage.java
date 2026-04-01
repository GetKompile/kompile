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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Map;

/**
 * Sealed interface for model initialization subprocess communication messages.
 * All messages are serialized to JSON and written to STDOUT with prefix "MODEL_INIT_MSG:".
 *
 * <p>The main application parses these messages to:
 * <ul>
 *   <li>Track model initialization progress</li>
 *   <li>Forward progress updates to WebSocket clients for UI display</li>
 *   <li>Detect subprocess health via heartbeats</li>
 *   <li>Handle completion or failure</li>
 * </ul>
 *
 * <p>Model initialization phases:
 * <ol>
 *   <li>STARTING - Subprocess starting up</li>
 *   <li>INITIALIZING_ND4J - Initializing ND4J backend and environment</li>
 *   <li>CONFIGURING_MODEL_SOURCE - Configuring staging service or archive</li>
 *   <li>LOOKING_UP_REGISTRY - Looking up model in registry</li>
 *   <li>DOWNLOADING_MODEL - Downloading model files (if needed)</li>
 *   <li>LOADING_MODEL - Loading model files from disk</li>
 *   <li>CREATING_ENCODER - Creating encoder instance (SameDiff graph)</li>
 *   <li>VALIDATING_MODEL - Running validation test embedding</li>
 *   <li>COMPLETE - Model ready for use</li>
 *   <li>FAILED - Initialization failed</li>
 * </ol>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ModelInitMessage.Progress.class, name = "PROGRESS"),
        @JsonSubTypes.Type(value = ModelInitMessage.PhaseTransition.class, name = "PHASE_TRANSITION"),
        @JsonSubTypes.Type(value = ModelInitMessage.Heartbeat.class, name = "HEARTBEAT"),
        @JsonSubTypes.Type(value = ModelInitMessage.Completed.class, name = "COMPLETED"),
        @JsonSubTypes.Type(value = ModelInitMessage.Failed.class, name = "FAILED"),
        @JsonSubTypes.Type(value = ModelInitMessage.Log.class, name = "LOG"),
        @JsonSubTypes.Type(value = ModelInitMessage.ModelInfo.class, name = "MODEL_INFO")
})
public sealed interface ModelInitMessage
        permits ModelInitMessage.Progress,
        ModelInitMessage.PhaseTransition,
        ModelInitMessage.Heartbeat,
        ModelInitMessage.Completed,
        ModelInitMessage.Failed,
        ModelInitMessage.Log,
        ModelInitMessage.ModelInfo {

    /** Message prefix used to distinguish model init JSON from other stdout output */
    String MESSAGE_PREFIX = "MODEL_INIT_MSG:";

    /** Get the task ID associated with this message */
    String taskId();

    /**
     * Model initialization phases.
     */
    enum Phase {
        STARTING("Starting model initialization"),
        INITIALIZING_ND4J("Initializing ND4J backend"),
        CONFIGURING_MODEL_SOURCE("Configuring model source"),
        LOOKING_UP_REGISTRY("Looking up model in registry"),
        DOWNLOADING_MODEL("Downloading model files"),
        LOADING_MODEL("Loading model from disk"),
        CREATING_ENCODER("Creating encoder (building SameDiff graph)"),
        VALIDATING_MODEL("Validating model output"),
        COMPLETE("Model ready"),
        FAILED("Initialization failed");

        private final String description;

        Phase(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * Progress update during model initialization.
     * Sent at regular intervals during each phase.
     */
    record Progress(
            String taskId,
            String modelId,
            Phase phase,
            int progressPercent,      // 0-100 overall progress
            int phaseProgressPercent, // 0-100 progress within current phase
            String message,           // Human-readable status message
            ProgressDetails details   // Optional detailed metrics
    ) implements ModelInitMessage {
    }

    /**
     * Phase transition notification.
     * Sent when moving from one phase to another.
     */
    record PhaseTransition(
            String taskId,
            String modelId,
            Phase fromPhase,     // Previous phase (null if starting)
            Phase toPhase,       // New phase
            long phaseDurationMs // Duration of the completed phase (0 if starting)
    ) implements ModelInitMessage {
    }

    /**
     * Heartbeat for liveness detection.
     * Sent at regular intervals (default every 3 seconds).
     */
    record Heartbeat(
            String taskId,
            String modelId,
            long uptimeMs,              // Time since subprocess started
            double memoryUsagePercent,  // Current JVM heap usage percentage
            long heapUsedBytes,         // Current heap used in bytes
            long heapMaxBytes,          // Maximum heap size in bytes
            Phase currentPhase          // Current phase for context
    ) implements ModelInitMessage {
    }

    /**
     * Successful completion message.
     * Sent when model initialization completes successfully.
     */
    record Completed(
            String taskId,
            String modelId,
            String modelSource,         // REGISTRY, ARCHIVE, STAGING
            String encoderType,         // GENERIC_DENSE, BGE, ARCTIC_EMBED, etc.
            int embeddingDimensions,    // e.g., 768, 1024
            int maxSequenceLength,      // e.g., 512
            long totalDurationMs,       // Total initialization time
            Map<Phase, Long> phaseDurations, // Duration per phase
            ModelMetrics metrics        // Detailed model metrics
    ) implements ModelInitMessage {
    }

    /**
     * Failure message.
     * Sent when an error occurs during initialization.
     */
    record Failed(
            String taskId,
            String modelId,
            Phase phase,                // Phase where failure occurred
            String errorMessage,        // Error message
            String errorType,           // Exception class name
            String stackTrace,          // Full stack trace (may be truncated)
            boolean retriable           // True if error might succeed on retry
    ) implements ModelInitMessage {
    }

    /**
     * Log message from subprocess.
     * Sent for real-time log streaming to the main application.
     */
    record Log(
            String taskId,
            String modelId,
            String level,     // INFO, WARN, ERROR, DEBUG, TRACE
            String source,    // Logger name or class name
            String message,   // Log message content
            long timestamp    // Epoch milliseconds when log was created
    ) implements ModelInitMessage {
    }

    /**
     * Model information discovered during initialization.
     * Sent when model metadata becomes available.
     */
    record ModelInfo(
            String taskId,
            String modelId,
            String modelType,           // dense_encoder, sparse_encoder, cross_encoder
            String encoderClass,        // Full class name of encoder
            int embeddingDimensions,
            int maxSequenceLength,
            int vocabSize,
            String tokenizerType,       // e.g., "bpe", "wordpiece"
            Map<String, String> metadata // Additional model metadata
    ) implements ModelInitMessage {
    }

    /**
     * Detailed progress metrics for progress updates.
     */
    record ProgressDetails(
            // Download metrics (if downloading)
            Long downloadedBytes,
            Long totalBytes,
            Double downloadSpeedBytesPerSec,

            // Model loading metrics
            Long modelFileSizeBytes,
            Integer numModelFiles,

            // Encoder creation metrics
            Integer numOpsInGraph,
            Integer numVariablesInGraph,
            Long graphBuildTimeMs,

            // Validation metrics
            Long validationTimeMs,
            Integer testTokenCount,
            Double testMagnitude,      // Magnitude of test embedding
            boolean validationPassed,

            // Memory metrics
            long heapUsedBytes,
            long heapMaxBytes,
            double memoryUsagePercent
    ) {
        public static ProgressDetails empty() {
            return new ProgressDetails(null, null, null, null, null, null, null, null, null, null, null, false, 0, 0, 0);
        }

        public static ProgressDetails withMemory(long heapUsed, long heapMax) {
            double percent = heapMax > 0 ? (heapUsed * 100.0 / heapMax) : 0;
            return new ProgressDetails(null, null, null, null, null, null, null, null, null, null, null, false, heapUsed, heapMax, percent);
        }
    }

    /**
     * Model metrics collected after successful initialization.
     */
    record ModelMetrics(
            // Model file info
            long modelFileSizeBytes,
            int numModelFiles,
            String modelPath,

            // Graph info
            int numOpsInGraph,
            int numVariablesInGraph,

            // Tokenizer info
            int vocabSize,
            String tokenizerType,

            // Performance metrics from test
            long testEmbeddingTimeMs,
            double testThroughputTokensPerSec,

            // Batch configuration
            int optimalBatchSize,
            int maxBatchSize
    ) {
    }

    // ===================== Factory Methods =====================

    /**
     * Factory method to create a progress message.
     */
    static Progress progress(String taskId, String modelId, Phase phase, int percent, String message) {
        return new Progress(taskId, modelId, phase, percent, percent, message, null);
    }

    /**
     * Factory method to create a progress message with details.
     */
    static Progress progress(String taskId, String modelId, Phase phase, int overallPercent,
                             int phasePercent, String message, ProgressDetails details) {
        return new Progress(taskId, modelId, phase, overallPercent, phasePercent, message, details);
    }

    /**
     * Factory method to create a phase transition message.
     */
    static PhaseTransition phaseTransition(String taskId, String modelId, Phase from, Phase to, long durationMs) {
        return new PhaseTransition(taskId, modelId, from, to, durationMs);
    }

    /**
     * Factory method to create a heartbeat message.
     */
    static Heartbeat heartbeat(String taskId, String modelId, long uptimeMs, Phase currentPhase) {
        Runtime runtime = Runtime.getRuntime();
        long heapUsed = runtime.totalMemory() - runtime.freeMemory();
        long heapMax = runtime.maxMemory();
        double memoryUsage = heapMax > 0 ? (heapUsed * 100.0 / heapMax) : 0;
        return new Heartbeat(taskId, modelId, uptimeMs, memoryUsage, heapUsed, heapMax, currentPhase);
    }

    /**
     * Factory method to create a completed message.
     */
    static Completed completed(String taskId, String modelId, String source, String encoderType,
                               int dimensions, int maxSeqLen, long totalDuration,
                               Map<Phase, Long> phaseDurations, ModelMetrics metrics) {
        return new Completed(taskId, modelId, source, encoderType, dimensions, maxSeqLen,
                totalDuration, phaseDurations, metrics);
    }

    /**
     * Factory method to create a failed message.
     */
    static Failed failed(String taskId, String modelId, Phase phase, Throwable exception, boolean retriable) {
        String stackTrace = getStackTrace(exception);
        return new Failed(taskId, modelId, phase, exception.getMessage(),
                exception.getClass().getName(), stackTrace, retriable);
    }

    /**
     * Factory method to create a failed message with explicit details.
     */
    static Failed failed(String taskId, String modelId, Phase phase, String errorMessage,
                         String errorType, String stackTrace, boolean retriable) {
        return new Failed(taskId, modelId, phase, errorMessage, errorType, stackTrace, retriable);
    }

    /**
     * Factory method to create a log message.
     */
    static Log log(String taskId, String modelId, String level, String source, String message) {
        return new Log(taskId, modelId, level, source, message, System.currentTimeMillis());
    }

    /**
     * Factory method to create a model info message.
     */
    static ModelInfo modelInfo(String taskId, String modelId, String modelType, String encoderClass,
                               int dimensions, int maxSeqLen, int vocabSize, String tokenizerType,
                               Map<String, String> metadata) {
        return new ModelInfo(taskId, modelId, modelType, encoderClass, dimensions, maxSeqLen,
                vocabSize, tokenizerType, metadata);
    }

    /**
     * Convert exception to stack trace string, truncated to avoid huge messages.
     */
    private static String getStackTrace(Throwable exception) {
        StringBuilder sb = new StringBuilder();
        sb.append(exception.toString()).append("\n");

        StackTraceElement[] trace = exception.getStackTrace();
        int maxLines = 50;
        for (int i = 0; i < Math.min(trace.length, maxLines); i++) {
            sb.append("\tat ").append(trace[i]).append("\n");
        }
        if (trace.length > maxLines) {
            sb.append("\t... ").append(trace.length - maxLines).append(" more\n");
        }

        Throwable cause = exception.getCause();
        if (cause != null && cause != exception) {
            sb.append("Caused by: ").append(cause.toString()).append("\n");
            StackTraceElement[] causeTrace = cause.getStackTrace();
            for (int i = 0; i < Math.min(causeTrace.length, 10); i++) {
                sb.append("\tat ").append(causeTrace[i]).append("\n");
            }
            if (causeTrace.length > 10) {
                sb.append("\t... ").append(causeTrace.length - 10).append(" more\n");
            }
        }

        return sb.toString();
    }
}
