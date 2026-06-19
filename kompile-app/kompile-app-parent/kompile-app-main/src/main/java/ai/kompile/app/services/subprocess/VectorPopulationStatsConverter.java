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

package ai.kompile.app.services.subprocess;

import ai.kompile.app.services.VectorPopulationProgressTracker;
import ai.kompile.app.services.VectorPopulationProgressTracker.VectorPopulationPhase;
import ai.kompile.app.services.VectorPopulationProgressTracker.VectorPopulationStats;
import ai.kompile.app.subprocess.SubprocessMessage;
import ai.kompile.app.web.dto.IngestProgressUpdate;
import ai.kompile.app.web.dto.IngestProgressUpdate.IngestPhase;
import ai.kompile.app.web.dto.IngestProgressUpdate.IngestStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Converts subprocess progress/completion/runtime messages to application DTO types.
 *
 * All conversion methods are pure (no side effects) and can be tested in isolation.
 */
@Component
public class VectorPopulationStatsConverter {

    private static final Logger logger = LoggerFactory.getLogger(VectorPopulationStatsConverter.class);

    private final SubprocessRestartManager restartManager;

    public VectorPopulationStatsConverter(SubprocessRestartManager restartManager) {
        this.restartManager = restartManager;
    }

    /**
     * Build VectorPopulationStats from subprocess progress message.
     */
    public VectorPopulationStats buildStatsFromProgress(SubprocessMessage.Progress progress) {
        if (progress == null) {
            return null;
        }

        SubprocessMessage.ProgressStats stats = progress.stats();
        if (stats == null) {
            return null;
        }

        List<VectorPopulationProgressTracker.WorkerStatusDto> workerStatusDtos = null;
        if (stats.workerStatuses() != null && !stats.workerStatuses().isEmpty()) {
            workerStatusDtos = stats.workerStatuses().stream()
                    .map(ws -> new VectorPopulationProgressTracker.WorkerStatusDto(
                            ws.workerId(),
                            ws.workerType(),
                            ws.status(),
                            ws.itemsProcessed(),
                            ws.currentBatchSize(),
                            ws.throughput(),
                            ws.currentItem()))
                    .toList();
        }

        VectorPopulationProgressTracker.QueueStatusDto queueStatus = new VectorPopulationProgressTracker.QueueStatusDto(
                stats.chunkQueueSize(),
                1000,
                stats.embeddingQueueSize(),
                1000);

        VectorPopulationProgressTracker.EmbeddingBatchMetricsDto embeddingBatch = null;
        if (stats.batchSize() > 0) {
            int totalBatches = stats.totalBatches() != null && stats.totalBatches() > 0
                    ? stats.totalBatches()
                    : (stats.documentsLoaded() > 0
                            ? (int) Math.ceil((double) stats.documentsLoaded() / stats.batchSize())
                            : 0);
            int currentBatch = stats.currentBatchNumber() != null && stats.currentBatchNumber() > 0
                    ? stats.currentBatchNumber()
                    : (stats.chunksEmbedded() > 0
                            ? (int) Math.ceil((double) stats.chunksEmbedded() / stats.batchSize())
                            : 1);

            String modelName = stats.runtimeInfo() != null ? stats.runtimeInfo().embeddingModelId() : "Unknown Model";
            String deviceType = "CPU";
            if (stats.runtimeInfo() != null) {
                if (Boolean.TRUE.equals(stats.runtimeInfo().cudaAvailable())) {
                    deviceType = "GPU (CUDA)";
                } else if (stats.runtimeInfo().nd4jBackend() != null
                        && stats.runtimeInfo().nd4jBackend().toLowerCase().contains("cuda")) {
                    deviceType = "GPU (CUDA)";
                }
            }

            embeddingBatch = new VectorPopulationProgressTracker.EmbeddingBatchMetricsDto(
                    currentBatch,
                    totalBatches,
                    stats.batchSize(),
                    stats.activeStage(),
                    0,
                    0,
                    0,
                    false,
                    null,
                    0,
                    stats.actualInputShape(),
                    stats.actualOutputShape(),
                    stats.embeddingDimension() != null ? stats.embeddingDimension() : 0,
                    0,
                    stats.chunksPerSecond(),
                    modelName,
                    deviceType,
                    stats.passageTokenCounts());
        }

        List<VectorPopulationProgressTracker.BatchHistoryEntryDto> batchHistoryDtos = null;
        if (stats.batchHistory() != null && !stats.batchHistory().isEmpty()) {
            batchHistoryDtos = stats.batchHistory().stream()
                    .map(h -> new VectorPopulationProgressTracker.BatchHistoryEntryDto(
                            h.batchNumber(),
                            h.inputTexts(),
                            h.maxSequenceLength(),
                            h.embeddingDimension(),
                            h.actualInputShape(),
                            h.actualOutputShape(),
                            h.totalBatchTimeMs(),
                            h.currentStep(),
                            h.tokensPerSecond(),
                            h.passageTokenCounts()))
                    .toList();
        }

        return new VectorPopulationStats(
                stats.documentsLoaded(),
                stats.chunksCreated(),
                stats.chunksEmbedded(),
                stats.documentsIndexed(),
                stats.documentsLoaded(),
                stats.tokensProcessed(),
                stats.totalTokensInIndex(),
                0,
                stats.chunksPerSecond(),
                stats.memoryUsagePercent(),
                workerStatusDtos,
                queueStatus,
                embeddingBatch,
                batchHistoryDtos,
                convertRuntimeInfo(stats.runtimeInfo()));
    }

    public IngestStats buildIngestStatsFromProgress(SubprocessMessage.Progress progress, String taskId) {
        IngestStats.Builder builder = IngestStats.builder()
                .subprocessRuntimeInfo(IngestProgressUpdate.SubprocessRuntimeInfo.forProcessMode("SUBPROCESS"));

        if (restartManager != null && taskId != null) {
            SubprocessRestartManager.RestartStatus restartStatus = restartManager.getRestartStatus(taskId);
            if (restartStatus.attemptsMade() > 0) {
                builder.restartAttempt(restartStatus.attemptsMade())
                        .maxRestartAttempts(restartStatus.maxAttempts());
            }
        }

        if (progress == null || progress.stats() == null) {
            return builder.build();
        }

        SubprocessMessage.ProgressStats stats = progress.stats();

        boolean isResumedFromCheckpoint = Boolean.TRUE.equals(stats.isResumedRun())
                && stats.resumedFromIndexedCount() != null
                && stats.resumedFromIndexedCount() > 0;

        if (isResumedFromCheckpoint) {
            if (builder.build().restartAttempt() == null || builder.build().restartAttempt() == 0) {
                builder.restartAttempt(1)
                        .maxRestartAttempts(1);
            }
        }

        int totalChunksEmbedded = stats.chunksEmbedded()
                + (isResumedFromCheckpoint ? stats.resumedFromIndexedCount() : 0);

        builder.documentsLoaded(stats.documentsLoaded())
                .chunksCreated(stats.chunksCreated())
                .chunksEmbedded(totalChunksEmbedded)
                .chunksIndexed(stats.documentsIndexed())
                .documentsIndexed(stats.documentsIndexed())
                .totalProcessingTimeMs(stats.totalProcessingTimeMs())
                .batchSize(stats.batchSize())
                .workerThreads(stats.workerThreads())
                .parallelProcessing(stats.parallelProcessing())
                .chunksPerSecond(stats.chunksPerSecond())
                .docsPerSecond(stats.docsPerSecond())
                .memoryUsagePercent(stats.memoryUsagePercent())
                .memoryStatus(stats.memoryStatus())
                .activeStage(stats.activeStage())
                .pipelineStatus(stats.pipelineStatus());

        int queueCapacity = 1000;
        IngestProgressUpdate.QueueStatusDto queueStatus = new IngestProgressUpdate.QueueStatusDto(
                stats.chunkQueueSize(),
                queueCapacity,
                stats.embeddingQueueSize(),
                queueCapacity,
                queueCapacity > 0 ? (stats.chunkQueueSize() * 100.0 / queueCapacity) : 0,
                queueCapacity > 0 ? (stats.embeddingQueueSize() * 100.0 / queueCapacity) : 0);
        builder.queueStatus(queueStatus);

        if (stats.workerStatuses() != null && !stats.workerStatuses().isEmpty()) {
            List<IngestProgressUpdate.WorkerStatusDto> workerDtos = stats.workerStatuses().stream()
                    .map(this::convertWorkerStatusSnapshot)
                    .toList();
            builder.workerStatuses(workerDtos);
        }

        IngestProgressUpdate.SubprocessRuntimeInfo runtimeInfo = convertRuntimeInfo(stats.runtimeInfo());
        if (runtimeInfo != null) {
            builder.subprocessRuntimeInfo(runtimeInfo);
        }

        if (stats.actualInputShape() != null || stats.actualOutputShape() != null ||
            stats.currentStep() != null || stats.inputTexts() != null) {

            IngestProgressUpdate.EmbeddingBatchMetrics.Builder batchBuilder =
                    IngestProgressUpdate.EmbeddingBatchMetrics.builder();

            if (stats.currentBatchNumber() != null) {
                batchBuilder.batchNumber(stats.currentBatchNumber());
            }
            if (stats.totalBatches() != null) {
                batchBuilder.totalBatches(stats.totalBatches());
            }
            if (stats.inputTexts() != null) {
                batchBuilder.inputTexts(stats.inputTexts());
            }
            if (stats.maxSequenceLength() != null) {
                batchBuilder.maxSequenceLength(stats.maxSequenceLength());
            }
            if (stats.embeddingDimension() != null) {
                batchBuilder.embeddingDimension(stats.embeddingDimension());
            }
            if (stats.actualInputShape() != null) {
                batchBuilder.actualInputShape(stats.actualInputShape());
            }
            if (stats.actualOutputShape() != null) {
                batchBuilder.actualOutputShape(stats.actualOutputShape());
            }
            if (stats.currentStep() != null) {
                batchBuilder.currentStep(stats.currentStep());
            }
            if (stats.tokenizationTimeMs() != null) {
                batchBuilder.tokenizationTimeMs(stats.tokenizationTimeMs());
            }
            if (stats.paddingTimeMs() != null) {
                batchBuilder.paddingTimeMs(stats.paddingTimeMs());
            }
            if (stats.tensorCreationTimeMs() != null) {
                batchBuilder.tensorCreationTimeMs(stats.tensorCreationTimeMs());
            }
            if (stats.forwardPassTimeMs() != null) {
                batchBuilder.forwardPassTimeMs(stats.forwardPassTimeMs());
            }
            if (stats.extractionTimeMs() != null) {
                batchBuilder.extractionTimeMs(stats.extractionTimeMs());
            }
            if (stats.passageTokenCounts() != null) {
                batchBuilder.passageTokenCounts(stats.passageTokenCounts());
            }

            builder.currentEmbeddingBatch(batchBuilder.build());
        }

        return builder.build();
    }

    public IngestStats buildIngestStatsFromCompleted(SubprocessMessage.Completed completed) {
        if (completed == null) {
            return IngestStats.builder()
                    .subprocessRuntimeInfo(IngestProgressUpdate.SubprocessRuntimeInfo.forProcessMode("SUBPROCESS"))
                    .build();
        }

        double rate = completed.totalDurationMs() > 0
                ? (completed.documentsIndexed() * 1000.0 / completed.totalDurationMs())
                : 0.0;

        return IngestStats.builder()
                .documentsLoaded(completed.documentsLoaded())
                .chunksCreated(completed.chunksCreated())
                .chunksEmbedded(completed.chunksEmbedded())
                .chunksIndexed(completed.documentsIndexed())
                .documentsIndexed(completed.documentsIndexed())
                .totalProcessingTimeMs(completed.totalDurationMs())
                .chunksPerSecond(rate)
                .subprocessRuntimeInfo(IngestProgressUpdate.SubprocessRuntimeInfo.forProcessMode("SUBPROCESS"))
                .build();
    }

    public IngestPhase mapPhaseToIngestPhase(String phase) {
        VectorPopulationPhase vectorPhase = mapPhaseToEnum(phase);
        return switch (vectorPhase) {
            case LOADING -> IngestPhase.LOADING;
            case EMBEDDING -> IngestPhase.EMBEDDING;
            case INDEXING -> IngestPhase.INDEXING;
            case COMPLETED -> IngestPhase.COMPLETED;
            case FAILED, CANCELLED -> IngestPhase.FAILED;
        };
    }

    public IngestProgressUpdate.SubprocessRuntimeInfo convertRuntimeInfo(SubprocessMessage.RuntimeInfo ri) {
        if (ri == null) {
            return null;
        }
        return new IngestProgressUpdate.SubprocessRuntimeInfo(
                ri.pid(),
                ri.uptimeMs(),
                "SUBPROCESS",
                ri.javaVersion(),
                ri.javaVendor(),
                ri.javaHome(),
                ri.vmName(),
                ri.vmVersion(),
                ri.heapMaxBytes(),
                ri.heapUsedBytes(),
                ri.heapFreeBytes(),
                ri.heapUsagePercent(),
                ri.nonHeapUsedBytes(),
                ri.gcCount(),
                ri.gcTimeMs(),
                ri.availableProcessors(),
                ri.workingDirectory(),
                ri.tempDirectory(),
                ri.commandLine(),
                ri.jvmArguments(),
                ri.inputFiles(),
                ri.nd4jBackendEnv(),
                ri.cudaVisibleDevices(),
                ri.ompNumThreads(),
                ri.mklNumThreads(),
                ri.nd4jEnvironmentInvoked(),
                ri.nd4jEnvironmentUsed(),
                ri.nd4jBackend(),
                ri.blasVendor(),
                ri.cudaAvailable(),
                ri.cudaVersion(),
                ri.embeddingModelId(),
                ri.embeddingModelPath(),
                ri.embeddingDimension());
    }

    public IngestProgressUpdate.WorkerStatusDto convertWorkerStatusSnapshot(
            SubprocessMessage.WorkerStatusSnapshot ws) {
        return new IngestProgressUpdate.WorkerStatusDto(
                ws.workerId(),
                ws.workerType() != null ? ws.workerType().toLowerCase(java.util.Locale.ROOT) : null,
                ws.status() != null ? ws.status().toLowerCase(java.util.Locale.ROOT) : null,
                ws.itemsProcessed(),
                ws.currentBatchSize(),
                ws.throughput(),
                ws.currentItem());
    }

    /**
     * Map string phase from subprocess to VectorPopulationPhase enum.
     */
    public VectorPopulationPhase mapPhaseToEnum(String phase) {
        if (phase == null) {
            return VectorPopulationPhase.LOADING;
        }

        return switch (phase.toUpperCase()) {
            case "LOADING", "STARTING", "INITIALIZING" -> VectorPopulationPhase.LOADING;
            case "EMBEDDING", "EMBED" -> VectorPopulationPhase.EMBEDDING;
            case "INDEXING", "INDEX" -> VectorPopulationPhase.INDEXING;
            case "COMPLETED", "COMPLETE", "DONE" -> VectorPopulationPhase.COMPLETED;
            case "FAILED", "ERROR" -> VectorPopulationPhase.FAILED;
            case "CANCELLED", "CANCELED" -> VectorPopulationPhase.CANCELLED;
            default -> VectorPopulationPhase.LOADING;
        };
    }

    /**
     * Check if an error message indicates an OOM-related failure.
     */
    private boolean isOomRelatedMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String upper = message.toUpperCase();
        return upper.contains("OUTOFMEMORY") ||
               upper.contains("OUT OF MEMORY") ||
               upper.contains("JAVA HEAP SPACE") ||
               upper.contains("GC OVERHEAD LIMIT") ||
               upper.contains("HEAP EXHAUSTED") ||
               upper.contains("OOM") ||
               upper.contains("SUBPROCESS WILL RESTART");
    }

    /**
     * Check if an error message indicates a batch size too large failure.
     */
    private boolean isBatchSizeTooLargeMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        return message.contains("Length of buffer can not be >= Integer.MAX_VALUE") ||
               message.contains("buffer can not be >= Integer") ||
               message.contains("array size exceeds") ||
               message.contains("Requested array size exceeds");
    }

    /**
     * Determine the failure reason from error messages.
     */
    public SubprocessRestartManager.FailureReason determineFailureReason(String errorMessage, String errorType) {
        if (isBatchSizeTooLargeMessage(errorMessage)) {
            return SubprocessRestartManager.FailureReason.BATCH_SIZE_TOO_LARGE;
        }
        if (isOomRelatedMessage(errorMessage) || isOomRelatedMessage(errorType)) {
            return SubprocessRestartManager.FailureReason.OUT_OF_MEMORY;
        }
        return SubprocessRestartManager.FailureReason.UNKNOWN;
    }
}
