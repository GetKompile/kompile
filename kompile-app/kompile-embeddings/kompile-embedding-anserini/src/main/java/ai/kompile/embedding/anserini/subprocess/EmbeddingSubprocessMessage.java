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

package ai.kompile.embedding.anserini.subprocess;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;
import java.util.Map;

/**
 * Sealed interface for embedding subprocess communication messages.
 * All messages are serialized to JSON and written to STDOUT with prefix "EMBEDDING_MSG:".
 *
 * The main application parses these messages to:
 * - Forward progress updates to WebSocket clients
 * - Track embedding model status
 * - Detect subprocess health via heartbeats
 * - Receive embedding results
 *
 * This follows the same patterns as SubprocessMessage used for indexing.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    // Requests (main -> subprocess)
    @JsonSubTypes.Type(value = EmbeddingSubprocessMessage.LoadModelRequest.class, name = "LOAD_MODEL_REQUEST"),
    @JsonSubTypes.Type(value = EmbeddingSubprocessMessage.EmbedRequest.class, name = "EMBED_REQUEST"),
    @JsonSubTypes.Type(value = EmbeddingSubprocessMessage.EmbedBatchRequest.class, name = "EMBED_BATCH_REQUEST"),
    @JsonSubTypes.Type(value = EmbeddingSubprocessMessage.StatusRequest.class, name = "STATUS_REQUEST"),
    @JsonSubTypes.Type(value = EmbeddingSubprocessMessage.ShutdownRequest.class, name = "SHUTDOWN_REQUEST"),
    @JsonSubTypes.Type(value = EmbeddingSubprocessMessage.OpTimingConfigRequest.class, name = "OP_TIMING_CONFIG_REQUEST"),
    @JsonSubTypes.Type(value = EmbeddingSubprocessMessage.OpTimingFlushRequest.class, name = "OP_TIMING_FLUSH_REQUEST"),

    // Responses (subprocess -> main)
    @JsonSubTypes.Type(value = EmbeddingSubprocessMessage.LoadModelResponse.class, name = "LOAD_MODEL_RESPONSE"),
    @JsonSubTypes.Type(value = EmbeddingSubprocessMessage.EmbedResponse.class, name = "EMBED_RESPONSE"),
    @JsonSubTypes.Type(value = EmbeddingSubprocessMessage.EmbedBatchResponse.class, name = "EMBED_BATCH_RESPONSE"),
    @JsonSubTypes.Type(value = EmbeddingSubprocessMessage.StatusResponse.class, name = "STATUS_RESPONSE"),
    @JsonSubTypes.Type(value = EmbeddingSubprocessMessage.OpTimingConfigResponse.class, name = "OP_TIMING_CONFIG_RESPONSE"),
    @JsonSubTypes.Type(value = EmbeddingSubprocessMessage.OpTimingFlushResponse.class, name = "OP_TIMING_FLUSH_RESPONSE"),

    // Status/Progress messages (subprocess -> main)
    @JsonSubTypes.Type(value = EmbeddingSubprocessMessage.Progress.class, name = "PROGRESS"),
    @JsonSubTypes.Type(value = EmbeddingSubprocessMessage.PhaseTransition.class, name = "PHASE_TRANSITION"),
    @JsonSubTypes.Type(value = EmbeddingSubprocessMessage.Heartbeat.class, name = "HEARTBEAT"),
    @JsonSubTypes.Type(value = EmbeddingSubprocessMessage.Log.class, name = "LOG"),
    @JsonSubTypes.Type(value = EmbeddingSubprocessMessage.Error.class, name = "ERROR")
})
public sealed interface EmbeddingSubprocessMessage
        permits EmbeddingSubprocessMessage.LoadModelRequest,
                EmbeddingSubprocessMessage.LoadModelResponse,
                EmbeddingSubprocessMessage.EmbedRequest,
                EmbeddingSubprocessMessage.EmbedResponse,
                EmbeddingSubprocessMessage.EmbedBatchRequest,
                EmbeddingSubprocessMessage.EmbedBatchResponse,
                EmbeddingSubprocessMessage.OpTimingConfigRequest,
                EmbeddingSubprocessMessage.OpTimingConfigResponse,
                EmbeddingSubprocessMessage.OpTimingFlushRequest,
                EmbeddingSubprocessMessage.OpTimingFlushResponse,
                EmbeddingSubprocessMessage.StatusRequest,
                EmbeddingSubprocessMessage.StatusResponse,
                EmbeddingSubprocessMessage.ShutdownRequest,
                EmbeddingSubprocessMessage.Progress,
                EmbeddingSubprocessMessage.PhaseTransition,
                EmbeddingSubprocessMessage.Heartbeat,
                EmbeddingSubprocessMessage.Log,
                EmbeddingSubprocessMessage.Error {

    /** Message prefix used to distinguish protocol JSON from other stdout output */
    String MESSAGE_PREFIX = "EMBEDDING_MSG:";

    // ═══════════════════════════════════════════════════════════════════════════════
    // REQUESTS (Main JVM -> Subprocess)
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Request to load an embedding model.
     */
    record LoadModelRequest(
            String requestId,
            String modelId,
            int optimalBatchSize,
            int maxBatchSize,
            Map<String, String> modelConfig  // Additional model configuration
    ) implements EmbeddingSubprocessMessage {}

    /**
     * Request to embed a single text.
     */
    record EmbedRequest(
            String requestId,
            String text
    ) implements EmbeddingSubprocessMessage {}

    /**
     * Request to embed a batch of texts.
     */
    record EmbedBatchRequest(
            String requestId,
            List<String> texts
    ) implements EmbeddingSubprocessMessage {}

    /**
     * Request current status of the subprocess.
     */
    record StatusRequest(
            String requestId
    ) implements EmbeddingSubprocessMessage {}

    /**
     * Request graceful shutdown.
     */
    record ShutdownRequest(
            String requestId
    ) implements EmbeddingSubprocessMessage {}

    /**
     * Request to configure ND4J op timing in subprocess.
     */
    record OpTimingConfigRequest(
            String requestId,
            boolean enabled,
            boolean detailedMode  // true for detailed timing, false for basic
    ) implements EmbeddingSubprocessMessage {}

    /**
     * Request to flush op timing stats and return them.
     */
    record OpTimingFlushRequest(
            String requestId,
            int topN,       // Number of top ops to return (by total time)
            boolean reset   // Whether to reset timing data after flush
    ) implements EmbeddingSubprocessMessage {}

    // ═══════════════════════════════════════════════════════════════════════════════
    // RESPONSES (Subprocess -> Main JVM)
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Response after loading a model.
     */
    record LoadModelResponse(
            String requestId,
            boolean success,
            String modelId,
            int dimensions,
            String modelSource,
            String encoderType,
            long loadTimeMs,
            String error
    ) implements EmbeddingSubprocessMessage {}

    /**
     * Response with embedding for a single text.
     */
    record EmbedResponse(
            String requestId,
            boolean success,
            float[] embedding,
            long embedTimeMs,
            String error
    ) implements EmbeddingSubprocessMessage {}

    /**
     * Response with embeddings for a batch.
     */
    record EmbedBatchResponse(
            String requestId,
            boolean success,
            List<float[]> embeddings,
            int inputCount,
            int outputCount,
            long totalTimeMs,
            BatchMetrics metrics,
            String error
    ) implements EmbeddingSubprocessMessage {}

    /**
     * Response with current status.
     */
    record StatusResponse(
            String requestId,
            boolean initialized,
            boolean loading,
            String loadingPhase,
            String modelId,
            String modelSource,
            String encoderType,
            int dimensions,
            int optimalBatchSize,
            int maxBatchSize,
            long uptimeMs,
            long totalEmbeddingsProcessed,
            RuntimeInfo runtimeInfo,
            String error
    ) implements EmbeddingSubprocessMessage {}

    /**
     * Response confirming op timing configuration change.
     */
    record OpTimingConfigResponse(
            String requestId,
            boolean success,
            boolean enabled,
            boolean detailedMode,
            String error
    ) implements EmbeddingSubprocessMessage {}

    /**
     * Response with op timing statistics from subprocess.
     */
    record OpTimingFlushResponse(
            String requestId,
            boolean success,
            long totalExecutions,
            int numOps,
            List<OpTimingStat> hotspots,
            String error
    ) implements EmbeddingSubprocessMessage {}

    /**
     * Single op timing statistic.
     */
    record OpTimingStat(
            int rank,
            String opName,
            long calls,
            double totalMs,
            double avgUs,
            double stdDevUs,
            double minUs,
            double maxUs,
            double helperPercent
    ) {}

    // ═══════════════════════════════════════════════════════════════════════════════
    // STATUS/PROGRESS MESSAGES (Subprocess -> Main JVM)
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Progress update during model loading or batch processing.
     */
    record Progress(
            String phase,           // INITIALIZING, LOADING_MODEL, EMBEDDING, IDLE
            int progressPercent,    // 0-100
            String currentStep,     // Human-readable step description
            String message,         // Status message
            ProgressStats stats     // Detailed statistics (may be null)
    ) implements EmbeddingSubprocessMessage {}

    /**
     * Phase transition notification.
     */
    record PhaseTransition(
            String fromPhase,       // Previous phase (null if starting)
            String toPhase,         // New phase
            long phaseDurationMs    // Duration of the completed phase (0 if starting)
    ) implements EmbeddingSubprocessMessage {}

    /**
     * Heartbeat for liveness detection.
     * Sent at regular intervals (default every 10 seconds).
     */
    record Heartbeat(
            long uptimeMs,              // Time since subprocess started
            boolean modelLoaded,        // Whether model is loaded
            String modelId,             // Current model ID (null if not loaded)
            double memoryUsagePercent,  // Current JVM heap usage percentage
            long heapUsedBytes,         // Current heap used
            long heapMaxBytes,          // Maximum heap
            long totalEmbeddings,       // Total embeddings processed since start
            double avgEmbedTimeMs       // Average embedding time
    ) implements EmbeddingSubprocessMessage {}

    /**
     * Log message from subprocess for real-time streaming.
     */
    record Log(
            String level,       // INFO, WARN, ERROR, DEBUG, TRACE
            String source,      // Logger name or class name
            String message,     // Log message content
            long timestamp      // Epoch milliseconds
    ) implements EmbeddingSubprocessMessage {}

    /**
     * Error message for failures.
     */
    record Error(
            String requestId,       // Request that caused error (null for general errors)
            String errorMessage,
            String errorType,       // Exception class name
            String stackTrace,
            String phase            // Phase where error occurred
    ) implements EmbeddingSubprocessMessage {}

    // ═══════════════════════════════════════════════════════════════════════════════
    // SUPPORTING TYPES
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Detailed metrics for a batch embedding operation.
     */
    record BatchMetrics(
            int batchSize,
            int maxSequenceLength,
            int totalTokens,
            String inputShape,          // Actual tensor shape, e.g., "[32 x 512]"
            String outputShape,         // Actual tensor shape, e.g., "[32 x 768]"
            long tokenizeTimeMs,
            long paddingTimeMs,
            long tensorCreationTimeMs,
            long forwardPassTimeMs,
            long extractionTimeMs,
            double tokensPerSecond,
            double textsPerSecond,
            int[] passageTokenCounts    // Token count per passage
    ) {}

    /**
     * Detailed progress statistics.
     */
    record ProgressStats(
            // Counts
            long totalEmbeddingsProcessed,
            long totalBatchesProcessed,
            long totalTokensProcessed,

            // Current batch info
            Integer currentBatchNumber,
            Integer currentBatchSize,
            Integer currentMaxSeqLength,
            String currentStep,         // TOKENIZING, PADDING, TENSOR_CREATION, FORWARD_PASS, EXTRACTION

            // Timing
            long avgBatchTimeMs,
            long avgTokenizeTimeMs,
            long avgForwardPassTimeMs,
            double throughputTextsPerSec,
            double throughputTokensPerSec,

            // Memory
            double memoryUsagePercent,
            long heapUsedBytes,
            long heapMaxBytes
    ) {}

    /**
     * Runtime information about the subprocess JVM.
     */
    record RuntimeInfo(
            Long pid,
            Long uptimeMs,
            String javaVersion,
            String javaVendor,
            Long heapMaxBytes,
            Long heapUsedBytes,
            Double heapUsagePercent,
            Integer availableProcessors,
            String nd4jBackend,
            String embeddingModelId,
            Integer embeddingDimension,
            String ompNumThreads,
            String mklNumThreads
    ) {
        /**
         * Collect runtime info from the current JVM.
         */
        public static RuntimeInfo collect(String modelId, Integer dimensions) {
            Runtime runtime = Runtime.getRuntime();
            java.lang.management.RuntimeMXBean runtimeMx =
                java.lang.management.ManagementFactory.getRuntimeMXBean();
            java.lang.management.MemoryUsage heapUsage =
                java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();

            long heapUsed = heapUsage.getUsed();
            long heapMax = heapUsage.getMax();
            double heapPercent = heapMax > 0 ? (heapUsed * 100.0 / heapMax) : 0;

            Long pid = null;
            try {
                pid = ProcessHandle.current().pid();
            } catch (Exception e) {
                // Ignore
            }

            String nd4jBackend = null;
            try {
                Class<?> nd4jClass = Class.forName("org.nd4j.linalg.factory.Nd4j");
                Object backend = nd4jClass.getMethod("getBackend").invoke(null);
                if (backend != null) {
                    nd4jBackend = backend.getClass().getSimpleName();
                }
            } catch (Exception e) {
                nd4jBackend = "UNKNOWN";
            }

            return new RuntimeInfo(
                    pid,
                    runtimeMx.getUptime(),
                    System.getProperty("java.version"),
                    System.getProperty("java.vendor"),
                    heapMax,
                    heapUsed,
                    heapPercent,
                    runtime.availableProcessors(),
                    nd4jBackend,
                    modelId,
                    dimensions,
                    System.getenv("OMP_NUM_THREADS"),
                    System.getenv("MKL_NUM_THREADS")
            );
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // FACTORY METHODS
    // ═══════════════════════════════════════════════════════════════════════════════

    static Progress progress(String phase, int percent, String step, String message) {
        return new Progress(phase, percent, step, message, null);
    }

    static Progress progress(String phase, int percent, String step, String message, ProgressStats stats) {
        return new Progress(phase, percent, step, message, stats);
    }

    static PhaseTransition phaseTransition(String fromPhase, String toPhase, long durationMs) {
        return new PhaseTransition(fromPhase, toPhase, durationMs);
    }

    static Heartbeat heartbeat(long uptimeMs, boolean modelLoaded, String modelId,
                               long totalEmbeddings, double avgEmbedTimeMs) {
        Runtime runtime = Runtime.getRuntime();
        long heapUsed = runtime.totalMemory() - runtime.freeMemory();
        long heapMax = runtime.maxMemory();
        double memoryUsage = heapMax > 0 ? (heapUsed * 100.0 / heapMax) : 0;
        return new Heartbeat(uptimeMs, modelLoaded, modelId, memoryUsage,
                           heapUsed, heapMax, totalEmbeddings, avgEmbedTimeMs);
    }

    static Log log(String level, String source, String message) {
        return new Log(level, source, message, System.currentTimeMillis());
    }

    static Error error(String requestId, Throwable exception, String phase) {
        return new Error(requestId, exception.getMessage(),
                        exception.getClass().getName(), getStackTrace(exception), phase);
    }

    static Error error(String requestId, String message, String errorType, String phase) {
        return new Error(requestId, message, errorType, null, phase);
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
