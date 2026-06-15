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

import ai.kompile.app.config.Nd4jEnvironmentConfig;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Map;

/**
 * Sealed interface for subprocess communication messages.
 * All messages are serialized to JSON and written to STDOUT with prefix
 * "INGEST_MSG:".
 *
 * The main application parses these messages to:
 * - Forward progress updates to WebSocket clients
 * - Update job history and event logs
 * - Detect subprocess health via heartbeats
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = SubprocessMessage.Ready.class, name = "READY"),
        @JsonSubTypes.Type(value = SubprocessMessage.Progress.class, name = "PROGRESS"),
        @JsonSubTypes.Type(value = SubprocessMessage.PhaseTransition.class, name = "PHASE_TRANSITION"),
        @JsonSubTypes.Type(value = SubprocessMessage.Heartbeat.class, name = "HEARTBEAT"),
        @JsonSubTypes.Type(value = SubprocessMessage.Completed.class, name = "COMPLETED"),
        @JsonSubTypes.Type(value = SubprocessMessage.Failed.class, name = "FAILED"),
        @JsonSubTypes.Type(value = SubprocessMessage.WorkerStatus.class, name = "WORKER_STATUS"),
        @JsonSubTypes.Type(value = SubprocessMessage.Log.class, name = "LOG")
})
public sealed interface SubprocessMessage
        permits SubprocessMessage.Ready,
        SubprocessMessage.Progress,
        SubprocessMessage.PhaseTransition,
        SubprocessMessage.Heartbeat,
        SubprocessMessage.Completed,
        SubprocessMessage.Failed,
        SubprocessMessage.WorkerStatus,
        SubprocessMessage.Log {

    /** Message prefix used to distinguish progress JSON from other stdout output */
    String MESSAGE_PREFIX = "INGEST_MSG:";

    /** Get the task ID associated with this message */
    String taskId();

    /**
     * Explicit readiness signal emitted once after initialization completes
     * (model loaded, beans wired, ready to accept work).
     *
     * Launchers should wait for this message with a configurable timeout
     * rather than relying on the first heartbeat to detect startup.
     */
    record Ready(
            String taskId,
            long startupTimeMs,       // Time from process start to ready
            String subprocessType,    // "embedding", "vector-population", "ingest", etc.
            String modelId,           // Model identifier if applicable (null otherwise)
            Integer modelDimension,   // Embedding dimension if applicable
            long pid                  // Subprocess PID for cross-reference
    ) implements SubprocessMessage {
    }

    /**
     * Progress update during processing.
     * Sent at regular intervals during each phase.
     */
    record Progress(
            String taskId,
            String phase, // LOADING, CONVERTING, CHUNKING, EMBEDDING, INDEXING
            int progressPercent, // 0-100
            String currentStep, // Human-readable current step description
            String message, // Status message
            ProgressStats stats // Detailed statistics (may be null)
    ) implements SubprocessMessage {
    }

    /**
     * Phase transition notification.
     * Sent when moving from one phase to another.
     */
    record PhaseTransition(
            String taskId,
            String fromPhase, // Previous phase (null if starting)
            String toPhase, // New phase
            long phaseDurationMs // Duration of the completed phase (0 if starting)
    ) implements SubprocessMessage {
    }

    /**
     * Heartbeat for liveness detection.
     * Sent at regular intervals (default every 10 seconds).
     * If main app doesn't receive heartbeat for 2+ minutes, process is considered
     * stuck.
     */
    record Heartbeat(
            String taskId,
            long uptimeMs, // Time since subprocess started
            double memoryUsagePercent, // Current JVM heap usage percentage
            long heapUsedBytes, // Current heap used in bytes
            long heapMaxBytes, // Maximum heap size in bytes
            // Off-heap (JavaCPP native) memory
            long offHeapUsedBytes, // Current off-heap used in bytes
            long offHeapMaxBytes, // Maximum off-heap size in bytes (0 if unlimited)
            double offHeapUsagePercent, // Current off-heap usage percentage
            // GPU memory (CUDA)
            long gpuUsedBytes, // Current GPU memory used in bytes
            long gpuMaxBytes, // Maximum GPU memory in bytes (0 if no GPU)
            double gpuUsagePercent // Current GPU usage percentage
    ) implements SubprocessMessage {
    }

    /**
     * Final completion message.
     * Sent when all phases complete successfully.
     */
    record Completed(
            String taskId,
            int documentsLoaded,
            int chunksCreated,
            int chunksEmbedded,
            int documentsIndexed,
            long tokensProcessed,
            long totalTokensInIndex,
            long totalDurationMs,
            String indexPath,
            Map<String, Long> phaseDurations // Duration per phase in ms
    ) implements SubprocessMessage {
    }

    /**
     * Failure message.
     * Sent when an error occurs during processing.
     */
    record Failed(
            String taskId,
            String phase, // Phase where failure occurred
            String errorMessage, // Error message
            String errorType, // Exception class name
            String stackTrace // Full stack trace (may be truncated)
    ) implements SubprocessMessage {
    }

    /**
     * Worker status update.
     * Sent to provide detailed worker thread information.
     */
    record WorkerStatus(
            String taskId,
            String workerId,
            String workerType, // CHUNKING, EMBEDDING, INDEXING
            String status, // IDLE, PROCESSING, WAITING, COMPLETE
            int itemsProcessed,
            int currentBatchSize,
            double throughput, // Items per second
            String currentItem // Currently processing item (may be null)
    ) implements SubprocessMessage {
    }

    /**
     * Log message from subprocess.
     * Sent for real-time log streaming to the main application.
     */
    record Log(
            String taskId,
            String level,     // INFO, WARN, ERROR, DEBUG, TRACE
            String source,    // Logger name or class name
            String message,   // Log message content
            long timestamp    // Epoch milliseconds when log was created
    ) implements SubprocessMessage {
    }

    /**
     * Runtime information about the subprocess JVM.
     */
    record RuntimeInfo(
            // Process identification
            Long pid,
            Long uptimeMs,

            // JVM info
            String javaVersion,
            String javaVendor,
            String javaHome,
            String vmName,
            String vmVersion,

            // Memory
            Long heapMaxBytes,
            Long heapUsedBytes,
            Long heapFreeBytes,
            Double heapUsagePercent,
            Long nonHeapUsedBytes,

            // GC
            Long gcCount,
            Long gcTimeMs,

            // Resources
            Integer availableProcessors,
            String workingDirectory,
            String tempDirectory,

            // Command info
            String commandLine,
            java.util.List<String> jvmArguments,
            java.util.List<String> inputFiles,

            // Environment
            String nd4jBackendEnv,
            String cudaVisibleDevices,
            String ompNumThreads,
            String mklNumThreads,

            // ND4J environment config
            Nd4jEnvironmentConfig nd4jEnvironmentInvoked,
            Nd4jEnvironmentConfig nd4jEnvironmentUsed,

            // Native info
            String nd4jBackend,
            String blasVendor,
            Boolean cudaAvailable,
            String cudaVersion,

            // Model info
            String embeddingModelId,
            String embeddingModelPath,
            Integer embeddingDimension) {
        /**
         * Collect runtime info from the current JVM.
         */
        public static RuntimeInfo collect(String embeddingModelId, String embeddingModelPath,
                Integer embeddingDimension, java.util.List<String> inputFiles,
                Nd4jEnvironmentConfig nd4jEnvironmentInvoked, Nd4jEnvironmentConfig nd4jEnvironmentUsed) {
            Runtime runtime = Runtime.getRuntime();
            java.lang.management.RuntimeMXBean runtimeMx = java.lang.management.ManagementFactory.getRuntimeMXBean();
            java.lang.management.MemoryMXBean memoryMx = java.lang.management.ManagementFactory.getMemoryMXBean();

            // Get heap info
            java.lang.management.MemoryUsage heapUsage = memoryMx.getHeapMemoryUsage();
            long heapUsed = heapUsage.getUsed();
            long heapMax = heapUsage.getMax();
            long heapFree = heapMax - heapUsed;
            double heapPercent = heapMax > 0 ? (heapUsed * 100.0 / heapMax) : 0;

            // Get non-heap
            java.lang.management.MemoryUsage nonHeapUsage = memoryMx.getNonHeapMemoryUsage();

            // Get GC info
            long gcCount = 0;
            long gcTime = 0;
            for (java.lang.management.GarbageCollectorMXBean gc : java.lang.management.ManagementFactory
                    .getGarbageCollectorMXBeans()) {
                gcCount += gc.getCollectionCount();
                gcTime += gc.getCollectionTime();
            }

            // Get PID
            Long pid = null;
            try {
                pid = ProcessHandle.current().pid();
            } catch (Exception e) {
                // Ignore
            }

            // Get JVM arguments
            java.util.List<String> jvmArgs = runtimeMx.getInputArguments();

            // Build command line approximation
            String commandLine = String.join(" ", runtimeMx.getInputArguments());

            // Check ND4J backend
            String nd4jBackend = null;
            String blasVendor = null;
            Boolean cudaAvailable = null;
            try {
                // Try to detect via class presence
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
                    System.getProperty("java.home"),
                    System.getProperty("java.vm.name"),
                    System.getProperty("java.vm.version"),
                    heapMax,
                    heapUsed,
                    heapFree,
                    heapPercent,
                    nonHeapUsage.getUsed(),
                    gcCount,
                    gcTime,
                    runtime.availableProcessors(),
                    System.getProperty("user.dir"),
                    System.getProperty("java.io.tmpdir"),
                    commandLine,
                    new java.util.ArrayList<>(jvmArgs),
                    inputFiles != null ? inputFiles : java.util.List.of(),
                    System.getenv("ND4J_BACKEND"),
                    System.getenv("CUDA_VISIBLE_DEVICES"),
                    System.getenv("OMP_NUM_THREADS"),
                    System.getenv("MKL_NUM_THREADS"),
                    nd4jEnvironmentInvoked,
                    nd4jEnvironmentUsed,
                    nd4jBackend,
                    blasVendor,
                    cudaAvailable,
                    null, // cudaVersion - would need CUDA API call
                    embeddingModelId,
                    embeddingModelPath,
                    embeddingDimension);
        }
    }

    /**
     * Detailed statistics for progress updates.
     */
    record ProgressStats(
            // Counts
            int documentsLoaded,
            int chunksCreated,
            int chunksEmbedded,
            int documentsIndexed,
            long tokensProcessed,
            long totalTokensInIndex,

            // Timing
            long totalProcessingTimeMs,
            long loadingDurationMs,
            long chunkingDurationMs,
            long embeddingDurationMs,
            long indexingDurationMs,

            // Configuration
            String loaderUsed,
            String chunkerUsed,
            int batchSize,
            int workerThreads,
            boolean parallelProcessing,

            // Throughput
            double chunksPerSecond,
            double docsPerSecond,

            // Memory
            double memoryUsagePercent,
            String memoryStatus,

            // Pipeline details
            String activeStage,
            String pipelineStatus,
            int chunkQueueSize,
            int embeddingQueueSize,

            // Worker statuses for parity with parallel pipeline
            java.util.List<WorkerStatusSnapshot> workerStatuses,

            // Runtime info (optional, sent periodically)
            RuntimeInfo runtimeInfo,

            // ========== EMBEDDING BATCH METRICS ==========
            // These fields capture the actual tensor shapes and timing from the encoder
            // during SameDiff inference, updated with each batch

            // Batch identification
            Integer currentBatchNumber,      // Current batch number (1-indexed)
            Integer totalBatches,            // Total expected batches

            // Input metrics
            Integer inputTexts,              // Number of text chunks in this batch
            Integer maxSequenceLength,       // Max sequence length after padding

            // Output metrics
            Integer embeddingDimension,      // Dimension of each embedding vector (e.g., 768, 1024)

            // Tensor shapes (as human-readable strings from encoder)
            String actualInputShape,         // Actual input tensor shape, e.g., "[3 x 512 x 768]"
            String actualOutputShape,        // Actual output tensor shape, e.g., "[3 x 768]"

            // Current step tracking
            String currentStep,              // Current inference step: TOKENIZING, PADDING, TENSOR_CREATION, FORWARD_PASS, EXTRACTION

            // Detailed timing from encoder (milliseconds)
            Long tokenizationTimeMs,         // Time spent tokenizing
            Long paddingTimeMs,              // Time spent padding sequences
            Long tensorCreationTimeMs,       // Time spent creating input tensors (INDArray)
            Long forwardPassTimeMs,          // Time spent in actual neural network forward pass
            Long extractionTimeMs,           // Time spent extracting embeddings from output

            // Batch history - last N completed batches for UI visibility
            java.util.List<BatchHistoryEntry> batchHistory,

            // Per-passage token counts for the current batch
            int[] passageTokenCounts,

            // ========== RESUME/RESTART INFO ==========
            // Tracks progress from previous runs for restart/resume scenarios
            Integer resumedFromChunkCount,    // Number of chunks already processed from previous run (null if fresh start)
            Integer resumedFromEmbeddedCount, // Number of embeddings already done from previous run
            Integer resumedFromIndexedCount,  // Number already indexed from previous run
            Boolean isResumedRun              // True if this is a resumed run from checkpoint
    ) {
    }

    /**
     * Simplified batch history entry for UI display.
     * Contains key metrics from completed batches.
     */
    record BatchHistoryEntry(
            int batchNumber,
            int inputTexts,
            int maxSequenceLength,
            int embeddingDimension,
            String actualInputShape,
            String actualOutputShape,
            long totalBatchTimeMs,
            String currentStep,
            double tokensPerSecond,
            int[] passageTokenCounts
    ) {
    }

    /**
     * Snapshot of worker status for progress reporting.
     * Matches the structure of PipelineProgress.WorkerStatus for UI consistency.
     */
    record WorkerStatusSnapshot(
            int workerId,
            String workerType, // "embedding", "indexing"
            String status, // "idle", "processing", "waiting", "complete"
            int itemsProcessed,
            int currentBatchSize,
            double throughput, // items per second
            String currentItem // current batch/item description
    ) {
        public static WorkerStatusSnapshot processing(int id, String type, int processed, int batchSize,
                double throughput, String item) {
            return new WorkerStatusSnapshot(id, type, "processing", processed, batchSize, throughput, item);
        }

        public static WorkerStatusSnapshot complete(int id, String type, int processed, double throughput) {
            return new WorkerStatusSnapshot(id, type, "complete", processed, 0, throughput, null);
        }

        public static WorkerStatusSnapshot waiting(int id, String type, int processed) {
            return new WorkerStatusSnapshot(id, type, "waiting", processed, 0, 0, null);
        }
    }

    // ===================== Visitor / Dispatch Support =====================

    /**
     * Callback interface for dispatching {@link SubprocessMessage} variants.
     * All methods are no-ops by default so callers only override what they need.
     * Methods declare {@code throws Exception} so overrides may propagate checked exceptions;
     * callers are expected to wrap {@link #dispatch} in a try-catch.
     */
    interface Handler {
        default void onReady(Ready msg) throws Exception {}
        default void onProgress(Progress msg) throws Exception {}
        default void onPhaseTransition(PhaseTransition msg) throws Exception {}
        default void onHeartbeat(Heartbeat msg) throws Exception {}
        default void onCompleted(Completed msg) throws Exception {}
        default void onFailed(Failed msg) throws Exception {}
        default void onWorkerStatus(WorkerStatus msg) throws Exception {}
        default void onLog(Log msg) throws Exception {}
    }

    /**
     * Dispatch {@code msg} to the appropriate {@link Handler} callback.
     * Eliminates repetitive {@code instanceof} chains in launcher classes.
     *
     * @throws Exception propagated from the handler callback
     */
    static void dispatch(SubprocessMessage msg, Handler handler) throws Exception {
        if (msg instanceof Ready r)                handler.onReady(r);
        else if (msg instanceof Progress p)        handler.onProgress(p);
        else if (msg instanceof PhaseTransition t) handler.onPhaseTransition(t);
        else if (msg instanceof Heartbeat h)       handler.onHeartbeat(h);
        else if (msg instanceof Completed c)       handler.onCompleted(c);
        else if (msg instanceof Failed f)          handler.onFailed(f);
        else if (msg instanceof WorkerStatus w)    handler.onWorkerStatus(w);
        else if (msg instanceof Log l)             handler.onLog(l);
    }

    /**
     * Factory method to create a ready message.
     */
    static Ready ready(String taskId, long startupTimeMs, String subprocessType,
                        String modelId, Integer modelDimension) {
        long pid = ProcessHandle.current().pid();
        return new Ready(taskId, startupTimeMs, subprocessType, modelId, modelDimension, pid);
    }

    /**
     * Factory method to create a progress message.
     */
    static Progress progress(String taskId, String phase, int percent, String step, String message) {
        return new Progress(taskId, phase, percent, step, message, null);
    }

    /**
     * Factory method to create a progress message with stats.
     */
    static Progress progress(String taskId, String phase, int percent, String step, String message,
            ProgressStats stats) {
        return new Progress(taskId, phase, percent, step, message, stats);
    }

    /**
     * Factory method to create a phase transition message.
     */
    static PhaseTransition phaseTransition(String taskId, String fromPhase, String toPhase, long durationMs) {
        return new PhaseTransition(taskId, fromPhase, toPhase, durationMs);
    }

    /**
     * Factory method to create a heartbeat message.
     */
    static Heartbeat heartbeat(String taskId, long uptimeMs) {
        Runtime runtime = Runtime.getRuntime();
        long heapUsed = runtime.totalMemory() - runtime.freeMemory();
        long heapMax = runtime.maxMemory();
        double memoryUsage = heapMax > 0 ? (heapUsed * 100.0 / heapMax) : 0;

        // Off-heap (JavaCPP) memory
        long offHeapUsed = 0;
        long offHeapMax = 0;
        double offHeapPercent = 0;
        try {
            Class<?> pointerClass = Class.forName("org.bytedeco.javacpp.Pointer");
            offHeapUsed = (Long) pointerClass.getMethod("totalBytes").invoke(null);
            String maxBytesStr = System.getProperty("org.bytedeco.javacpp.maxbytes");
            if (maxBytesStr != null && !maxBytesStr.isEmpty()) {
                offHeapMax = Long.parseLong(maxBytesStr);
            }
            if (offHeapMax <= 0) {
                String maxPhysStr = System.getProperty("org.bytedeco.javacpp.maxphysicalbytes");
                if (maxPhysStr != null && !maxPhysStr.isEmpty()) {
                    offHeapMax = Long.parseLong(maxPhysStr);
                }
            }
            // Also check NIO direct buffers
            for (java.lang.management.BufferPoolMXBean pool :
                    java.lang.management.ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class)) {
                if ("direct".equals(pool.getName())) {
                    offHeapUsed = Math.max(offHeapUsed, pool.getMemoryUsed());
                    break;
                }
            }
            offHeapPercent = offHeapMax > 0 ? (offHeapUsed * 100.0 / offHeapMax) : 0;
        } catch (Exception e) {
            // JavaCPP not available, ignore
        }

        // GPU memory
        long gpuUsed = 0;
        long gpuMax = 0;
        double gpuPercent = 0;
        try {
            Class<?> nd4jClass = Class.forName("org.nd4j.linalg.factory.Nd4j");
            Object backend = nd4jClass.getMethod("getBackend").invoke(null);
            if (backend != null && backend.getClass().getSimpleName().contains("Cuda")) {
                Class<?> nativeOpsClass = Class.forName("org.nd4j.nativeblas.NativeOpsHolder");
                Object holder = nativeOpsClass.getMethod("getInstance").invoke(null);
                Object nativeOps = holder.getClass().getMethod("getDeviceNativeOps").invoke(holder);
                long free = (Long) nativeOps.getClass().getMethod("getDeviceFreeMemory", int.class).invoke(nativeOps, 0);
                long total = (Long) nativeOps.getClass().getMethod("getDeviceTotalMemory", int.class).invoke(nativeOps, 0);
                gpuMax = total;
                gpuUsed = total - free;
                gpuPercent = total > 0 ? (gpuUsed * 100.0 / total) : 0;
            }
        } catch (Exception e) {
            // No GPU or CUDA not available, ignore
        }

        return new Heartbeat(taskId, uptimeMs, memoryUsage, heapUsed, heapMax,
                offHeapUsed, offHeapMax, offHeapPercent,
                gpuUsed, gpuMax, gpuPercent);
    }

    /**
     * Factory method to create a completed message.
     */
    static Completed completed(String taskId, int docsLoaded, int chunksCreated,
            int chunksEmbedded, int docsIndexed,
            long tokensProcessed, long totalTokensInIndex,
            long totalDurationMs, String indexPath,
            Map<String, Long> phaseDurations) {
        return new Completed(taskId, docsLoaded, chunksCreated, chunksEmbedded,
                docsIndexed, tokensProcessed, totalTokensInIndex, totalDurationMs, indexPath, phaseDurations);
    }

    /**
     * Factory method to create a failed message.
     */
    static Failed failed(String taskId, String phase, Throwable exception) {
        String stackTrace = getStackTrace(exception);
        return new Failed(taskId, phase, exception.getMessage(),
                exception.getClass().getName(), stackTrace);
    }

    /**
     * Factory method to create a failed message with explicit error details.
     */
    static Failed failed(String taskId, String phase, String errorMessage, String errorType, String stackTrace) {
        return new Failed(taskId, phase, errorMessage, errorType, stackTrace);
    }

    /**
     * Factory method to create a worker status message.
     */
    static WorkerStatus workerStatus(String taskId, String workerId, String workerType,
            String status, int itemsProcessed, int batchSize,
            double throughput, String currentItem) {
        return new WorkerStatus(taskId, workerId, workerType, status,
                itemsProcessed, batchSize, throughput, currentItem);
    }

    /**
     * Factory method to create a log message.
     */
    static Log log(String taskId, String level, String source, String message) {
        return new Log(taskId, level, source, message, System.currentTimeMillis());
    }

    /**
     * Factory method to create a log message with explicit timestamp.
     */
    static Log log(String taskId, String level, String source, String message, long timestamp) {
        return new Log(taskId, level, source, message, timestamp);
    }

    /**
     * Convert exception to stack trace string, truncated to avoid huge messages.
     */
    private static String getStackTrace(Throwable exception) {
        StringBuilder sb = new StringBuilder();
        sb.append(exception.toString()).append("\n");

        StackTraceElement[] trace = exception.getStackTrace();
        int maxLines = 50; // Limit to prevent huge messages
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
