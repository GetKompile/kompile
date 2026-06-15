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

package ai.kompile.staging.subprocess;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Map;

/**
 * Sealed interface for training subprocess communication messages.
 * All messages are serialized to JSON and written to STDOUT with prefix "TRAINING_MSG:".
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TrainingSubprocessMessage.Progress.class, name = "PROGRESS"),
        @JsonSubTypes.Type(value = TrainingSubprocessMessage.PhaseTransition.class, name = "PHASE_TRANSITION"),
        @JsonSubTypes.Type(value = TrainingSubprocessMessage.Heartbeat.class, name = "HEARTBEAT"),
        @JsonSubTypes.Type(value = TrainingSubprocessMessage.Completed.class, name = "COMPLETED"),
        @JsonSubTypes.Type(value = TrainingSubprocessMessage.Failed.class, name = "FAILED"),
        @JsonSubTypes.Type(value = TrainingSubprocessMessage.MetricsUpdate.class, name = "METRICS_UPDATE"),
        @JsonSubTypes.Type(value = TrainingSubprocessMessage.Log.class, name = "LOG"),
        @JsonSubTypes.Type(value = TrainingSubprocessMessage.CheckpointSaved.class, name = "CHECKPOINT_SAVED")
})
public sealed interface TrainingSubprocessMessage
        permits TrainingSubprocessMessage.Progress,
        TrainingSubprocessMessage.PhaseTransition,
        TrainingSubprocessMessage.Heartbeat,
        TrainingSubprocessMessage.Completed,
        TrainingSubprocessMessage.Failed,
        TrainingSubprocessMessage.MetricsUpdate,
        TrainingSubprocessMessage.Log,
        TrainingSubprocessMessage.CheckpointSaved {

    String MESSAGE_PREFIX = "TRAINING_MSG:";

    String taskId();

    /**
     * Training progress update.
     */
    record Progress(
            String taskId,
            long step,
            int epoch,
            int totalEpochs,
            double loss,
            double learningRate,
            String phase,       // INITIALIZING, TRAINING, EVALUATING, SAVING
            double epochProgress,
            double overallProgress,
            String message
    ) implements TrainingSubprocessMessage {
    }

    /**
     * Phase transition notification.
     */
    record PhaseTransition(
            String taskId,
            String fromPhase,
            String toPhase,
            long phaseDurationMs
    ) implements TrainingSubprocessMessage {
    }

    /**
     * Heartbeat for liveness detection.
     */
    record Heartbeat(
            String taskId,
            long uptimeMs,
            double memoryUsagePercent,
            long heapUsedBytes,
            long heapMaxBytes
    ) implements TrainingSubprocessMessage {
    }

    /**
     * Training completed successfully.
     */
    record Completed(
            String taskId,
            double finalLoss,
            double finalEvalLoss,
            long totalSteps,
            int totalEpochs,
            long totalDurationMs,
            String outputPath,
            Map<String, Double> finalMetrics
    ) implements TrainingSubprocessMessage {
    }

    /**
     * Training failed.
     */
    record Failed(
            String taskId,
            String phase,
            String errorMessage,
            String errorType,
            String stackTrace
    ) implements TrainingSubprocessMessage {
    }

    /**
     * Metrics update (replaces SSE-based metrics streaming).
     */
    record MetricsUpdate(
            String taskId,
            long step,
            int epoch,
            double trainLoss,
            double evalLoss,
            double learningRate,
            double gradNorm,
            double tokensPerSecond,
            double samplesPerSecond,
            Map<String, Double> customMetrics,
            // Per-segment execution breakdown
            int dspSegmentsWarmup,
            int dspSegmentsReplayed,
            int dspSegmentsCaptured,
            int dspSegmentsSlotBySlot,
            int dspSegmentsFailed,
            // Buffer pool stats
            long dspBufferPoolBytes,
            long dspBufferPoolReused,
            long dspColoringSavedBytes,
            // GPU memory
            long gpuMemUsedBytes,
            long gpuMemFreeBytes,
            long gpuMemTotalBytes,
            long gpuPoolUsedBytes,
            long gpuPoolReservedBytes,
            int numGpuDevices,
            String gpuDeviceNames,
            // JVM heap
            long heapUsedBytes,
            long heapMaxBytes,
            double heapUsagePercent
    ) implements TrainingSubprocessMessage {
    }

    /**
     * Log message from subprocess.
     */
    record Log(
            String taskId,
            String level,
            String source,
            String message,
            long timestamp
    ) implements TrainingSubprocessMessage {
    }

    /**
     * Checkpoint saved notification.
     */
    record CheckpointSaved(
            String taskId,
            long step,
            int epoch,
            String checkpointPath,
            double loss
    ) implements TrainingSubprocessMessage {
    }

    // ==================== Factory Methods ====================

    static Progress progress(String taskId, long step, int epoch, int totalEpochs,
                             double loss, double lr, String phase,
                             double epochProgress, double overallProgress, String message) {
        return new Progress(taskId, step, epoch, totalEpochs, loss, lr, phase,
                epochProgress, overallProgress, message);
    }

    static PhaseTransition phaseTransition(String taskId, String fromPhase, String toPhase, long durationMs) {
        return new PhaseTransition(taskId, fromPhase, toPhase, durationMs);
    }

    static Heartbeat heartbeat(String taskId, long uptimeMs) {
        Runtime runtime = Runtime.getRuntime();
        long heapUsed = runtime.totalMemory() - runtime.freeMemory();
        long heapMax = runtime.maxMemory();
        double memoryUsage = heapMax > 0 ? (heapUsed * 100.0 / heapMax) : 0;
        return new Heartbeat(taskId, uptimeMs, memoryUsage, heapUsed, heapMax);
    }

    static Completed completed(String taskId, double finalLoss, double finalEvalLoss,
                               long totalSteps, int totalEpochs, long totalDurationMs,
                               String outputPath, Map<String, Double> finalMetrics) {
        return new Completed(taskId, finalLoss, finalEvalLoss, totalSteps, totalEpochs,
                totalDurationMs, outputPath, finalMetrics);
    }

    static Failed failed(String taskId, String phase, Throwable exception) {
        String stackTrace = getStackTrace(exception);
        return new Failed(taskId, phase, exception.getMessage(),
                exception.getClass().getName(), stackTrace);
    }

    static Failed failed(String taskId, String phase, String errorMessage, String errorType, String stackTrace) {
        return new Failed(taskId, phase, errorMessage, errorType, stackTrace);
    }

    static MetricsUpdate metricsUpdate(String taskId, long step, int epoch,
                                       double trainLoss, double evalLoss, double lr,
                                       double gradNorm, double tokensPerSec, double samplesPerSec,
                                       Map<String, Double> customMetrics) {
        return new MetricsUpdate(taskId, step, epoch, trainLoss, evalLoss, lr,
                gradNorm, tokensPerSec, samplesPerSec, customMetrics,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, 0, 0, 0.0);
    }

    static MetricsUpdate metricsUpdateWithDsp(String taskId, long step, int epoch,
                                               double trainLoss, double evalLoss, double lr,
                                               double gradNorm, double tokensPerSec, double samplesPerSec,
                                               Map<String, Double> customMetrics,
                                               int dspSegmentsWarmup, int dspSegmentsReplayed,
                                               int dspSegmentsCaptured, int dspSegmentsSlotBySlot,
                                               int dspSegmentsFailed,
                                               long dspBufferPoolBytes, long dspBufferPoolReused,
                                               long dspColoringSavedBytes,
                                               long gpuMemUsedBytes, long gpuMemFreeBytes,
                                               long gpuMemTotalBytes, long gpuPoolUsedBytes,
                                               long gpuPoolReservedBytes, int numGpuDevices,
                                               String gpuDeviceNames,
                                               long heapUsedBytes, long heapMaxBytes,
                                               double heapUsagePercent) {
        return new MetricsUpdate(taskId, step, epoch, trainLoss, evalLoss, lr,
                gradNorm, tokensPerSec, samplesPerSec, customMetrics,
                dspSegmentsWarmup, dspSegmentsReplayed, dspSegmentsCaptured,
                dspSegmentsSlotBySlot, dspSegmentsFailed,
                dspBufferPoolBytes, dspBufferPoolReused, dspColoringSavedBytes,
                gpuMemUsedBytes, gpuMemFreeBytes, gpuMemTotalBytes,
                gpuPoolUsedBytes, gpuPoolReservedBytes, numGpuDevices, gpuDeviceNames,
                heapUsedBytes, heapMaxBytes, heapUsagePercent);
    }

    static Log log(String taskId, String level, String source, String message) {
        return new Log(taskId, level, source, message, System.currentTimeMillis());
    }

    static CheckpointSaved checkpointSaved(String taskId, long step, int epoch,
                                           String checkpointPath, double loss) {
        return new CheckpointSaved(taskId, step, epoch, checkpointPath, loss);
    }

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
