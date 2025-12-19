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
        @JsonSubTypes.Type(value = SubprocessMessage.Progress.class, name = "PROGRESS"),
        @JsonSubTypes.Type(value = SubprocessMessage.PhaseTransition.class, name = "PHASE_TRANSITION"),
        @JsonSubTypes.Type(value = SubprocessMessage.Heartbeat.class, name = "HEARTBEAT"),
        @JsonSubTypes.Type(value = SubprocessMessage.Completed.class, name = "COMPLETED"),
        @JsonSubTypes.Type(value = SubprocessMessage.Failed.class, name = "FAILED"),
        @JsonSubTypes.Type(value = SubprocessMessage.WorkerStatus.class, name = "WORKER_STATUS"),
        @JsonSubTypes.Type(value = SubprocessMessage.Log.class, name = "LOG")
})
public sealed interface SubprocessMessage
        permits SubprocessMessage.Progress,
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
            long heapMaxBytes // Maximum heap size in bytes
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
            RuntimeInfo runtimeInfo) {
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
        return new Heartbeat(taskId, uptimeMs, memoryUsage, heapUsed, heapMax);
    }

    /**
     * Factory method to create a completed message.
     */
    static Completed completed(String taskId, int docsLoaded, int chunksCreated,
            int chunksEmbedded, int docsIndexed,
            long totalDurationMs, String indexPath,
            Map<String, Long> phaseDurations) {
        return new Completed(taskId, docsLoaded, chunksCreated, chunksEmbedded,
                docsIndexed, totalDurationMs, indexPath, phaseDurations);
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
