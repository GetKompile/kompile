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

import ai.kompile.app.ingest.domain.IngestEvent;
import ai.kompile.app.ingest.service.IngestEventService;
import ai.kompile.app.services.IngestProgressTracker;
import ai.kompile.app.services.VectorPopulationProgressTracker;
import ai.kompile.app.services.VectorPopulationProgressTracker.VectorPopulationPhase;
import ai.kompile.app.services.subprocess.SubprocessRestartManager.FailureReason;
import ai.kompile.app.services.subprocess.SubprocessRestartManager.RestartConfig;
import ai.kompile.app.web.dto.IngestProgressUpdate.IngestPhase;
import ai.kompile.app.web.dto.IngestProgressUpdate.IngestStats;
import ai.kompile.app.web.dto.IngestProgressUpdate;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/**
 * Manages subprocess exit handling, automatic restart logic, and watchdog scheduling
 * for vector population jobs.
 *
 * Owns the @Scheduled watchdog methods (progress-stall detection and stale-process detection).
 * Delegates the actual re-launch to a callback provided by the launcher to avoid a circular
 * Spring dependency.
 */
@Component
public class SubprocessLifecycleManager {

    private static final Logger logger = LoggerFactory.getLogger(SubprocessLifecycleManager.class);

    @Value("${kompile.vectorpopulation.subprocess.heap-size:4g}")
    private String fallbackHeapSize;

    @Value("${kompile.vectorpopulation.subprocess.stale-threshold-seconds:180}")
    private int fallbackStaleThresholdSeconds;

    @Value("${kompile.vectorpopulation.subprocess.progress-stall-threshold-seconds:60}")
    private int fallbackProgressStallThresholdSeconds;

    private final SubprocessRestartManager restartManager;
    private final SubprocessConfigService subprocessConfigService;
    private final VectorPopulationProgressTracker progressTracker;
    private final IngestProgressTracker ingestProgressTracker;
    private final IngestEventService ingestEventService;
    private final VectorPopulationStatsConverter statsConverter;
    private final ObjectMapper objectMapper;

    /** Injected by the launcher after construction to avoid circular Spring dependency. */
    private Map<String, VectorPopulationHandle> activeProcesses;
    private Set<String> warnedTaskIds;
    /** Callback: (taskId, options) -> launch new process and return future */
    private BiFunction<String, Map<String, Object>, CompletableFuture<VectorPopulationResult>> relaunchCallback;
    /** Broadcast a progress update to WebSocket */
    private QuadConsumer<String, String, Integer, String> broadcastProgressCallback;
    /** Broadcast with stats map */
    private PentaConsumer<String, String, Integer, String, Map<String, Object>> broadcastProgressWithStatsCallback;

    @FunctionalInterface
    public interface QuadConsumer<A, B, C, D> {
        void accept(A a, B b, C c, D d);
    }

    @FunctionalInterface
    public interface PentaConsumer<A, B, C, D, E> {
        void accept(A a, B b, C c, D d, E e);
    }

    private final ScheduledExecutorService restartScheduler = Executors.newSingleThreadScheduledExecutor(
            r -> {
                Thread t = new Thread(r, "subprocess-restart-scheduler");
                t.setDaemon(true);
                return t;
            });

    @Autowired(required = false)
    private ai.kompile.embedding.anserini.config.AnseriniEmbeddingConfiguration.AnseriniEmbeddingProperties embeddingProperties;

    public SubprocessLifecycleManager(
            SubprocessRestartManager restartManager,
            SubprocessConfigService subprocessConfigService,
            VectorPopulationProgressTracker progressTracker,
            IngestProgressTracker ingestProgressTracker,
            IngestEventService ingestEventService,
            VectorPopulationStatsConverter statsConverter) {
        this.restartManager = restartManager;
        this.subprocessConfigService = subprocessConfigService;
        this.progressTracker = progressTracker;
        this.ingestProgressTracker = ingestProgressTracker;
        this.ingestEventService = ingestEventService;
        this.statsConverter = statsConverter;
        this.objectMapper = JsonUtils.standardMapper();
    }

    /** Wire in shared state and callbacks from the launcher. */
    public void setContext(
            Map<String, VectorPopulationHandle> activeProcesses,
            Set<String> warnedTaskIds,
            BiFunction<String, Map<String, Object>, CompletableFuture<VectorPopulationResult>> relaunchCallback,
            QuadConsumer<String, String, Integer, String> broadcastProgressCallback,
            PentaConsumer<String, String, Integer, String, Map<String, Object>> broadcastProgressWithStatsCallback) {
        this.activeProcesses = activeProcesses;
        this.warnedTaskIds = warnedTaskIds;
        this.relaunchCallback = relaunchCallback;
        this.broadcastProgressCallback = broadcastProgressCallback;
        this.broadcastProgressWithStatsCallback = broadcastProgressWithStatsCallback;
    }

    public ScheduledExecutorService getRestartScheduler() {
        return restartScheduler;
    }

    /**
     * Handle process completion (exit code interpretation and optional restart).
     */
    public void handleCompletion(VectorPopulationHandle handle, int exitCode) {
        if (handle.getResultFuture().isDone()) {
            return;
        }

        if (exitCode == 0) {
            String errorMessage = "Process exited successfully (0) but no completion message was received";
            logger.error("Vector population subprocess {}: {}", handle.getTaskId(), errorMessage);
            handle.getResultFuture().complete(VectorPopulationResult.failure(
                    handle.getTaskId(), handle.getCurrentPhase(), errorMessage));

            if (progressTracker != null) {
                progressTracker.failTask(handle.getTaskId(),
                        statsConverter.mapPhaseToEnum(handle.getCurrentPhase()), errorMessage);
            }
            if (ingestProgressTracker != null) {
                String displayName = buildTaskDisplayName(handle.getVectorIndexPath());
                IngestPhase ingestPhase = statsConverter.mapPhaseToIngestPhase(handle.getCurrentPhase());
                ingestProgressTracker.failTask(handle.getTaskId(), displayName, ingestPhase, errorMessage);
            }

            broadcastProgress(handle.getTaskId(), "FAILED", 0, "Failed");
            closeSubprocessLog(handle, "FAILED", exitCode, errorMessage, false, false);
        } else {
            String errorMessage;
            boolean isNativeCrash = false;
            boolean isCancelled = false;
            boolean isOomKilled = false;

            if (handle.isCancelled()) {
                errorMessage = "Process cancelled";
                isCancelled = true;
            } else if (handle.isOomDetected()) {
                FailureReason storedReason = handle.getFailureReason();
                FailureReason reason = storedReason != null ? storedReason : FailureReason.OUT_OF_MEMORY;

                if (reason == FailureReason.BATCH_SIZE_TOO_LARGE) {
                    errorMessage = "Batch size too large - array exceeds Integer.MAX_VALUE";
                    logger.info("=== BATCH_SIZE_TOO_LARGE DETECTED for task {} - attempting restart with reduced batch size ===",
                            handle.getTaskId());
                } else {
                    errorMessage = "Out of memory";
                    isOomKilled = true;
                    logger.info("=== OOM DETECTED for task {} - attempting restart ===", handle.getTaskId());
                }

                if (restartManager != null && !handle.isCancelled()) {
                    if (restartManager.shouldRestart(handle.getTaskId(), reason)) {
                        logger.info("=== SCHEDULING RESTART for task {} (reason: {}) ===", handle.getTaskId(), reason);
                        scheduleRestart(handle, exitCode, false, reason, errorMessage);
                        return;
                    } else {
                        logger.warn("restartManager.shouldRestart returned false for task {} (reason: {}) - " +
                                "either restart disabled or max attempts reached", handle.getTaskId(), reason);
                    }
                } else if (restartManager == null) {
                    logger.error("CANNOT RESTART: restartManager is NULL! {} will be reported as failure.", reason);
                    logger.error("This is a configuration error - SubprocessRestartManager bean is not available.");
                } else if (handle.isCancelled()) {
                    logger.info("Task {} was cancelled - not restarting", handle.getTaskId());
                }
            } else if (exitCode == 130) {
                errorMessage = "Process interrupted (SIGINT)";
                isCancelled = true;
            } else if (exitCode == 134) {
                errorMessage = "Native crash (SIGABRT) - likely ND4J/native library assertion failure";
                isNativeCrash = true;
            } else if (exitCode == 136) {
                errorMessage = "Native crash (SIGFPE) - floating point exception in native code";
                isNativeCrash = true;
            } else if (exitCode == 139) {
                errorMessage = "Native crash (SIGSEGV) - segmentation fault in ND4J/native code";
                isNativeCrash = true;
            } else if (exitCode == 137) {
                errorMessage = "Process killed (SIGKILL) - likely OOM killer or manual termination";
                isOomKilled = true;

                logger.info("=== EXIT CODE 137 (OOM KILLED) for task {} - attempting restart ===", handle.getTaskId());

                if (restartManager != null && !handle.isCancelled()) {
                    FailureReason reason = FailureReason.OOM_KILLED;
                    if (restartManager.shouldRestart(handle.getTaskId(), reason)) {
                        logger.info("=== SCHEDULING RESTART for task {} (OOM killed) ===", handle.getTaskId());
                        scheduleRestart(handle, exitCode, true, reason, errorMessage);
                        return;
                    } else {
                        logger.warn("restartManager.shouldRestart returned false for task {} - " +
                                "either restart disabled or max attempts reached", handle.getTaskId());
                    }
                } else if (restartManager == null) {
                    logger.error("CANNOT RESTART: restartManager is NULL! OOM killer exit will be reported as failure.");
                }
            } else if (exitCode == 143) {
                errorMessage = "Process terminated (SIGTERM)";
                isCancelled = true;
            } else if (exitCode > 128) {
                int signal = exitCode - 128;
                errorMessage = "Process killed by signal " + signal + " - possible native crash";
                isNativeCrash = true;
            } else {
                errorMessage = "Process exited with code " + exitCode;
            }

            if (isNativeCrash) {
                logger.error("NATIVE CRASH in vector population subprocess {} during phase {}: {} (exit code {}). " +
                        "The parent process is unaffected due to subprocess isolation.",
                        handle.getTaskId(), handle.getCurrentPhase(), errorMessage, exitCode);
            } else {
                logger.error("Vector population subprocess {} failed: {} (exit code {})",
                        handle.getTaskId(), errorMessage, exitCode);
            }

            handle.getResultFuture().complete(VectorPopulationResult.failure(
                    handle.getTaskId(), handle.getCurrentPhase(), errorMessage));

            String uiMessage = isNativeCrash
                    ? "Native crash in embedding/indexing - see logs for details"
                    : errorMessage;

            if (progressTracker != null) {
                if (isCancelled) {
                    progressTracker.cancelTask(handle.getTaskId(), uiMessage);
                } else {
                    progressTracker.failTask(handle.getTaskId(),
                            statsConverter.mapPhaseToEnum(handle.getCurrentPhase()), uiMessage);
                }
            }
            if (ingestProgressTracker != null) {
                String displayName = buildTaskDisplayName(handle.getVectorIndexPath());
                IngestPhase ingestPhase = statsConverter.mapPhaseToIngestPhase(handle.getCurrentPhase());
                if (isCancelled) {
                    IngestStats stats = IngestStats.builder()
                            .subprocessRuntimeInfo(
                                    IngestProgressUpdate.SubprocessRuntimeInfo.forProcessMode("SUBPROCESS"))
                            .build();
                    ingestProgressTracker.cancelTask(handle.getTaskId(), displayName, ingestPhase, uiMessage, stats);
                } else {
                    ingestProgressTracker.failTask(handle.getTaskId(), displayName, ingestPhase, uiMessage);
                }
            }

            broadcastProgress(handle.getTaskId(), "FAILED", 0, uiMessage);
            String logState = isCancelled ? "CANCELLED" : (isNativeCrash ? "CRASHED" : "FAILED");
            closeSubprocessLog(handle, logState, exitCode, uiMessage, isOomKilled, false);
        }
    }

    /**
     * Schedule a restart attempt after a restartable failure (OOM, batch size too large, etc.).
     */
    public void scheduleRestart(VectorPopulationHandle handle, int exitCode, boolean wasOomKilled,
                                FailureReason failureReason, String errorMessage) {
        String taskId = handle.getTaskId();
        String fileName = buildTaskDisplayName(handle.getVectorIndexPath());

        String recoveryType = switch (failureReason) {
            case BATCH_SIZE_TOO_LARGE -> "BATCH SIZE REDUCTION";
            case OOM_KILLED -> "OOM KILLER RECOVERY";
            case OUT_OF_MEMORY -> "OOM RECOVERY";
            default -> "ADAPTIVE RECOVERY";
        };

        logger.info("=== {} TRIGGERED for task {} ===", recoveryType, taskId);
        logger.info("Failure: reason={}, wasOomKilled={}, exitCode={}, error={}",
                failureReason, wasOomKilled, exitCode, errorMessage);

        String currentHeapSize = subprocessConfigService != null
                ? subprocessConfigService.getHeapSize()
                : fallbackHeapSize;
        Long parsedHeapBytes = SystemMemoryAnalyzer.parseMemoryToBytes(currentHeapSize);
        long currentHeapBytes = (parsedHeapBytes != null) ? parsedHeapBytes : 4L * 1024 * 1024 * 1024;

        String currentOffHeapStr = subprocessConfigService != null
                ? subprocessConfigService.getOffHeapMaxBytes()
                : null;
        Long parsedOffHeapBytes = SystemMemoryAnalyzer.parseMemoryToBytes(currentOffHeapStr);
        long currentOffHeapBytes = (parsedOffHeapBytes != null) ? parsedOffHeapBytes : currentHeapBytes * 2;

        int currentOmpThreads = getConfigInt("OMP_NUM_THREADS", 4);
        int currentBlasThreads = getConfigInt("OPENBLAS_NUM_THREADS", 4);
        int currentMaxThreads = subprocessConfigService != null
                ? subprocessConfigService.getEmbeddingThreads()
                : 1;
        int currentBatchSize = embeddingProperties != null
                ? embeddingProperties.getBaseOptimalBatchSize()
                : 4;

        logger.info("Current settings: heap={}, offHeap={}, OMP={}, BLAS={}, maxThreads={}, batch={}",
                currentHeapSize,
                SystemMemoryAnalyzer.formatBytes(currentOffHeapBytes),
                currentOmpThreads, currentBlasThreads, currentMaxThreads, currentBatchSize);

        RestartConfig restartConfig = restartManager.getRestartConfig(
                taskId, failureReason, wasOomKilled, currentHeapBytes, currentOffHeapBytes,
                currentOmpThreads, currentBlasThreads, currentMaxThreads, currentBatchSize);

        logger.info("Restart config computed (reason={}): {}", failureReason, restartConfig.getSummary());

        if (ingestEventService != null) {
            SystemMemoryAnalyzer.MemoryStatus memStatus = restartConfig.memoryAnalysis();
            try {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("totalSystemRam", memStatus.totalSystemRamBytes());
                details.put("totalSystemRamFormatted", SystemMemoryAnalyzer.formatBytes(memStatus.totalSystemRamBytes()));
                details.put("usedSystemRam", memStatus.usedSystemRamBytes());
                details.put("freeSystemRam", memStatus.availableSystemRamBytes());
                details.put("currentHeap", currentHeapSize);
                details.put("currentHeapBytes", currentHeapBytes);
                details.put("recommendedHeap", restartConfig.heapSize());
                details.put("recommendedHeapBytes", restartConfig.heapSizeBytes());
                details.put("heapIncreased", restartConfig.heapIncreased());
                details.put("currentOffHeap", SystemMemoryAnalyzer.formatBytes(currentOffHeapBytes));
                details.put("currentOffHeapBytes", currentOffHeapBytes);
                details.put("recommendedOffHeap", SystemMemoryAnalyzer.formatBytes(restartConfig.offHeapBytes()));
                details.put("recommendedOffHeapBytes", restartConfig.offHeapBytes());
                details.put("offHeapReduced", restartConfig.offHeapReduced());
                details.put("ompThreads", restartConfig.ompNumThreads());
                details.put("blasThreads", restartConfig.openBlasNumThreads());
                details.put("maxThreads", restartConfig.maxThreads());
                details.put("batchSize", restartConfig.batchSize());
                details.put("originalBatchSize", currentBatchSize);
                details.put("batchSizeReduced", restartConfig.batchSize() < currentBatchSize);
                details.put("wasOomKilled", wasOomKilled);
                details.put("exitCode", exitCode);
                details.put("failureReason", failureReason.name());

                String detailsJson = objectMapper.writeValueAsString(details);
                ingestEventService.logMemoryAnalysis(taskId, fileName,
                        IngestEvent.IngestPhase.EMBEDDING, memStatus.canIncreaseHeap(),
                        memStatus.reason(), detailsJson);
            } catch (Exception e) {
                logger.warn("Failed to serialize memory analysis details: {}", e.getMessage());
            }
        }

        if (ingestEventService != null) {
            try {
                Map<String, Object> restartDetails = new LinkedHashMap<>();
                restartDetails.put("heapSize", restartConfig.heapSize());
                restartDetails.put("heapSizeBytes", restartConfig.heapSizeBytes());
                restartDetails.put("heapIncreased", restartConfig.heapIncreased());
                restartDetails.put("offHeapBytes", restartConfig.offHeapBytes());
                restartDetails.put("offHeapFormatted", SystemMemoryAnalyzer.formatBytes(restartConfig.offHeapBytes()));
                restartDetails.put("offHeapReduced", restartConfig.offHeapReduced());
                restartDetails.put("ompThreads", restartConfig.ompNumThreads());
                restartDetails.put("blasThreads", restartConfig.openBlasNumThreads());
                restartDetails.put("maxThreads", restartConfig.maxThreads());
                restartDetails.put("batchSize", restartConfig.batchSize());
                restartDetails.put("originalBatchSize", currentBatchSize);
                restartDetails.put("batchSizeReduced", restartConfig.batchSize() < currentBatchSize);
                restartDetails.put("failureReason", failureReason.name());

                String detailsJson = objectMapper.writeValueAsString(restartDetails);
                ingestEventService.logRestartScheduled(taskId, fileName,
                        IngestEvent.IngestPhase.EMBEDDING,
                        restartConfig.attemptNumber(), restartConfig.maxAttempts(),
                        restartConfig.backoffMs(), detailsJson);
            } catch (Exception e) {
                logger.warn("Failed to serialize restart scheduled details: {}", e.getMessage());
            }
        }

        StringBuilder adjustmentMsg = new StringBuilder();
        adjustmentMsg.append(String.format("Restart %d/%d in %.1fs - ",
                restartConfig.attemptNumber(), restartConfig.maxAttempts(),
                restartConfig.backoffMs() / 1000.0));

        boolean batchSizeReduced = restartConfig.batchSize() < currentBatchSize;

        if (failureReason == FailureReason.BATCH_SIZE_TOO_LARGE && batchSizeReduced) {
            adjustmentMsg.append(String.format("reducing batch size from %d to %d (75%% reduction)",
                    currentBatchSize, restartConfig.batchSize()));
        } else if (restartConfig.offHeapReduced()) {
            adjustmentMsg.append(String.format("reducing off-heap to %s",
                    SystemMemoryAnalyzer.formatBytes(restartConfig.offHeapBytes())));
            if (batchSizeReduced) {
                adjustmentMsg.append(String.format(", batch %d->%d", currentBatchSize, restartConfig.batchSize()));
            }
        } else if (restartConfig.heapIncreased()) {
            adjustmentMsg.append(String.format("increasing heap to %s", restartConfig.heapSize()));
            if (batchSizeReduced) {
                adjustmentMsg.append(String.format(", reducing batch %d->%d", currentBatchSize, restartConfig.batchSize()));
            }
        } else if (batchSizeReduced) {
            adjustmentMsg.append(String.format("reducing batch size %d->%d", currentBatchSize, restartConfig.batchSize()));
        } else {
            adjustmentMsg.append("reducing threads and batch size");
        }

        Map<String, Object> restartStats = new LinkedHashMap<>();
        restartStats.put("restartAttempt", restartConfig.attemptNumber());
        restartStats.put("maxRestarts", restartConfig.maxAttempts());
        restartStats.put("backoffMs", restartConfig.backoffMs());
        restartStats.put("heapSize", restartConfig.heapSize());
        restartStats.put("heapIncreased", restartConfig.heapIncreased());
        restartStats.put("offHeapBytes", restartConfig.offHeapBytes());
        restartStats.put("offHeapFormatted", SystemMemoryAnalyzer.formatBytes(restartConfig.offHeapBytes()));
        restartStats.put("offHeapReduced", restartConfig.offHeapReduced());
        restartStats.put("ompThreads", restartConfig.ompNumThreads());
        restartStats.put("blasThreads", restartConfig.openBlasNumThreads());
        restartStats.put("batchSize", restartConfig.batchSize());
        restartStats.put("originalBatchSize", currentBatchSize);
        restartStats.put("batchSizeReduced", batchSizeReduced);
        restartStats.put("failureReason", failureReason.name());

        broadcastProgressWithStats(taskId, "RESTARTING",
                (restartConfig.attemptNumber() * 100) / restartConfig.maxAttempts(),
                adjustmentMsg.toString(),
                restartStats);

        if (ingestProgressTracker != null) {
            String displayName = buildTaskDisplayName(handle.getVectorIndexPath());
            IngestPhase currentPhase = statsConverter.mapPhaseToIngestPhase(handle.getCurrentPhase());
            long nextRestartTime = System.currentTimeMillis() + restartConfig.backoffMs();
            String memoryReason = restartConfig.memoryAnalysis() != null
                    ? restartConfig.memoryAnalysis().reason()
                    : "Memory adjustment for OOM recovery";

            ingestProgressTracker.notifyRestartScheduled(
                    taskId, displayName, currentPhase,
                    restartConfig.attemptNumber(), restartConfig.maxAttempts(),
                    nextRestartTime, restartConfig.heapSize(), restartConfig.heapIncreased(),
                    restartConfig.ompNumThreads(), restartConfig.openBlasNumThreads(),
                    memoryReason);
        }

        if (ingestEventService != null) {
            try {
                String details = objectMapper.writeValueAsString(Map.of(
                        "heapSize", restartConfig.heapSize(),
                        "heapIncreased", restartConfig.heapIncreased(),
                        "ompThreads", restartConfig.ompNumThreads(),
                        "blasThreads", restartConfig.openBlasNumThreads(),
                        "batchSize", restartConfig.batchSize(),
                        "memoryAnalysis", restartConfig.memoryAnalysis() != null
                                ? restartConfig.memoryAnalysis().reason() : "OOM recovery"
                ));
                IngestEvent.IngestPhase eventPhase = convertToEventPhase(handle.getCurrentPhase());
                ingestEventService.logRestartScheduled(
                        taskId,
                        buildTaskDisplayName(handle.getVectorIndexPath()),
                        eventPhase,
                        restartConfig.attemptNumber(),
                        restartConfig.maxAttempts(),
                        restartConfig.backoffMs(),
                        details);
            } catch (Exception e) {
                logger.warn("Failed to log restart scheduled event: {}", e.getMessage());
            }
        }

        logger.info("=== RESTART SCHEDULED for task {} ===", taskId);
        logger.info("Backoff: {}ms, Config: {}", restartConfig.backoffMs(), restartConfig.getSummary());

        restartScheduler.schedule(() -> executeRestart(handle, restartConfig),
                restartConfig.backoffMs(), TimeUnit.MILLISECONDS);
    }

    /**
     * Schedule a restart attempt after stall/deadlock detection.
     */
    public void scheduleStallRestart(VectorPopulationHandle handle,
                                     SubprocessRestartManager.FailureReason reason,
                                     String errorMessage) {
        String taskId = handle.getTaskId();
        String fileName = buildTaskDisplayName(handle.getVectorIndexPath());

        logger.info("=== STALL RECOVERY TRIGGERED for task {} ===", taskId);
        logger.info("Reason: {}, Error: {}", reason, errorMessage);

        String currentHeapSize = subprocessConfigService != null
                ? subprocessConfigService.getHeapSize()
                : fallbackHeapSize;
        Long parsedHeapBytes = SystemMemoryAnalyzer.parseMemoryToBytes(currentHeapSize);
        long currentHeapBytes = (parsedHeapBytes != null) ? parsedHeapBytes : 4L * 1024 * 1024 * 1024;

        String currentOffHeapStr = subprocessConfigService != null
                ? subprocessConfigService.getOffHeapMaxBytes()
                : null;
        Long parsedOffHeapBytes = SystemMemoryAnalyzer.parseMemoryToBytes(currentOffHeapStr);
        long currentOffHeapBytes = (parsedOffHeapBytes != null) ? parsedOffHeapBytes : currentHeapBytes * 2;

        int currentOmpThreads = getConfigInt("OMP_NUM_THREADS", 4);
        int currentBlasThreads = getConfigInt("OPENBLAS_NUM_THREADS", 4);
        int currentMaxThreads = subprocessConfigService != null
                ? subprocessConfigService.getEmbeddingThreads()
                : 1;
        int currentBatchSize = embeddingProperties != null
                ? embeddingProperties.getBaseOptimalBatchSize()
                : 4;

        RestartConfig restartConfig = restartManager.getRestartConfig(
                taskId, false, currentHeapBytes, currentOffHeapBytes,
                currentOmpThreads, currentBlasThreads, currentMaxThreads, currentBatchSize);

        logger.info("Stall restart config: {}", restartConfig.getSummary());

        if (ingestEventService != null) {
            try {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("reason", reason.name());
                details.put("stallType", reason == SubprocessRestartManager.FailureReason.STALLED_NO_HEARTBEAT
                        ? "no_heartbeat" : "no_progress");
                details.put("heapSize", restartConfig.heapSize());
                details.put("attemptNumber", restartConfig.attemptNumber());
                details.put("maxAttempts", restartConfig.maxAttempts());
                String detailsJson = objectMapper.writeValueAsString(details);
                ingestEventService.logRestartScheduled(taskId, fileName,
                        IngestEvent.IngestPhase.EMBEDDING,
                        restartConfig.attemptNumber(), restartConfig.maxAttempts(),
                        restartConfig.backoffMs(), detailsJson);
            } catch (Exception e) {
                logger.warn("Failed to serialize stall restart details: {}", e.getMessage());
            }
        }

        Map<String, Object> restartStats = new LinkedHashMap<>();
        restartStats.put("restartAttempt", restartConfig.attemptNumber());
        restartStats.put("maxRestarts", restartConfig.maxAttempts());
        restartStats.put("reason", reason.name());
        restartStats.put("backoffMs", restartConfig.backoffMs());
        restartStats.put("heapSize", restartConfig.heapSize());

        broadcastProgressWithStats(taskId, "RESTARTING",
                (restartConfig.attemptNumber() * 100) / restartConfig.maxAttempts(),
                String.format("Stall detected (%s) - restarting (attempt %d/%d)",
                        reason.name(), restartConfig.attemptNumber(), restartConfig.maxAttempts()),
                restartStats);

        logger.info("=== STALL RESTART SCHEDULED for task {} ===", taskId);
        logger.info("Backoff: {}ms, Config: {}", restartConfig.backoffMs(), restartConfig.getSummary());

        long stallBackoffMs = Math.min(1000, restartConfig.backoffMs());
        restartScheduler.schedule(() -> executeRestart(handle, restartConfig),
                stallBackoffMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Execute a restart attempt.
     */
    public void executeRestart(VectorPopulationHandle oldHandle, RestartConfig restartConfig) {
        String taskId = oldHandle.getTaskId();
        String fileName = buildTaskDisplayName(oldHandle.getVectorIndexPath());

        logger.info("=== EXECUTING RESTART for task {} ===", taskId);
        logger.info("Config: {}", restartConfig.getSummary());

        if (ingestEventService != null) {
            try {
                Map<String, Object> attemptDetails = new LinkedHashMap<>();
                attemptDetails.put("heapSize", restartConfig.heapSize());
                attemptDetails.put("heapSizeBytes", restartConfig.heapSizeBytes());
                attemptDetails.put("offHeapBytes", restartConfig.offHeapBytes());
                attemptDetails.put("offHeapFormatted", SystemMemoryAnalyzer.formatBytes(restartConfig.offHeapBytes()));
                attemptDetails.put("ompThreads", restartConfig.ompNumThreads());
                attemptDetails.put("blasThreads", restartConfig.openBlasNumThreads());
                attemptDetails.put("maxThreads", restartConfig.maxThreads());
                attemptDetails.put("batchSize", restartConfig.batchSize());

                String detailsJson = objectMapper.writeValueAsString(attemptDetails);
                ingestEventService.logRestartAttempted(taskId, fileName,
                        restartConfig.attemptNumber(), restartConfig.maxAttempts(),
                        restartConfig.heapSize(), detailsJson);
            } catch (Exception e) {
                logger.warn("Failed to serialize restart attempted details: {}", e.getMessage());
            }
        }

        String memoryInfo;
        if (restartConfig.offHeapReduced()) {
            memoryInfo = String.format("heap=%s, off-heap=%s (reduced)",
                    restartConfig.heapSize(),
                    SystemMemoryAnalyzer.formatBytes(restartConfig.offHeapBytes()));
        } else {
            memoryInfo = String.format("heap=%s, off-heap=%s",
                    restartConfig.heapSize(),
                    SystemMemoryAnalyzer.formatBytes(restartConfig.offHeapBytes()));
        }

        Map<String, Object> attemptStats = new LinkedHashMap<>();
        attemptStats.put("restartAttempt", restartConfig.attemptNumber());
        attemptStats.put("maxRestarts", restartConfig.maxAttempts());
        attemptStats.put("heapSize", restartConfig.heapSize());
        attemptStats.put("offHeapBytes", restartConfig.offHeapBytes());
        attemptStats.put("offHeapFormatted", SystemMemoryAnalyzer.formatBytes(restartConfig.offHeapBytes()));
        attemptStats.put("ompThreads", restartConfig.ompNumThreads());
        attemptStats.put("blasThreads", restartConfig.openBlasNumThreads());
        attemptStats.put("batchSize", restartConfig.batchSize());

        broadcastProgressWithStats(taskId, "RESTARTING",
                (restartConfig.attemptNumber() * 100) / restartConfig.maxAttempts(),
                String.format("Attempt %d/%d: %s", restartConfig.attemptNumber(), restartConfig.maxAttempts(), memoryInfo),
                attemptStats);

        if (ingestProgressTracker != null) {
            ingestProgressTracker.notifyRestartExecuting(
                    taskId,
                    fileName,
                    restartConfig.attemptNumber(),
                    restartConfig.maxAttempts(),
                    restartConfig.heapSize());
        }

        if (ingestEventService != null) {
            try {
                String details = objectMapper.writeValueAsString(Map.of(
                        "heapSize", restartConfig.heapSize(),
                        "heapIncreased", restartConfig.heapIncreased(),
                        "ompThreads", restartConfig.ompNumThreads(),
                        "blasThreads", restartConfig.openBlasNumThreads(),
                        "batchSize", restartConfig.batchSize()
                ));
                ingestEventService.logRestartAttempted(
                        taskId,
                        fileName,
                        restartConfig.attemptNumber(),
                        restartConfig.maxAttempts(),
                        restartConfig.heapSize(),
                        details);
            } catch (Exception e) {
                logger.warn("Failed to log restart attempted event: {}", e.getMessage());
            }
        }

        try {
            CompletableFuture<VectorPopulationResult> newResultFuture = launchVectorPopulationWithConfig(
                    taskId,
                    oldHandle.getKeywordIndexPath(),
                    oldHandle.getVectorIndexPath(),
                    restartConfig);

            newResultFuture.whenComplete((result, ex) -> {
                if (ex != null) {
                    logger.error("Restart attempt {} for task {} failed with exception: {}",
                            restartConfig.attemptNumber(), taskId, ex.getMessage());
                    restartManager.recordRestartAttempt(taskId, false);

                    if (restartManager.shouldRestart(taskId, FailureReason.UNKNOWN)) {
                        VectorPopulationHandle currentHandle = activeProcesses != null ? activeProcesses.get(taskId) : null;
                        if (currentHandle != null) {
                            scheduleRestart(currentHandle, -1, false, FailureReason.UNKNOWN, ex.getMessage());
                        }
                    } else {
                        handleRestartExhausted(oldHandle, restartConfig.attemptNumber(), ex.getMessage());
                    }
                } else if (!result.success()) {
                    logger.info("Restart attempt {} for task {} completed but task failed: {}",
                            restartConfig.attemptNumber(), taskId, result.errorMessage());
                } else {
                    logger.info("Restart attempt {} for task {} succeeded!", restartConfig.attemptNumber(), taskId);
                    restartManager.recordRestartAttempt(taskId, true);
                    restartManager.recordRestartSuccess(taskId);

                    if (ingestEventService != null) {
                        long recoveryTime = Duration.between(oldHandle.getStartTime(), Instant.now()).toMillis();
                        ingestEventService.logRestartSucceeded(taskId, fileName,
                                restartConfig.attemptNumber(), recoveryTime);
                    }

                    oldHandle.getResultFuture().complete(result);
                }
            });

            restartManager.recordRestartAttempt(taskId, true);

        } catch (Exception e) {
            logger.error("Failed to launch restart for task {}: {}", taskId, e.getMessage(), e);
            restartManager.recordRestartAttempt(taskId, false);

            if (restartManager.shouldRestart(taskId, FailureReason.UNKNOWN)) {
                scheduleRestart(oldHandle, -1, false, FailureReason.UNKNOWN, e.getMessage());
            } else {
                handleRestartExhausted(oldHandle, restartConfig.attemptNumber(), e.getMessage());
            }
        }
    }

    /**
     * Handle case where all restart attempts have been exhausted.
     */
    public void handleRestartExhausted(VectorPopulationHandle handle, int totalAttempts, String finalError) {
        String taskId = handle.getTaskId();
        String fileName = buildTaskDisplayName(handle.getVectorIndexPath());
        long totalTime = Duration.between(handle.getStartTime(), Instant.now()).toMillis();

        logger.error("All {} restart attempts exhausted for task {}: {}", totalAttempts, taskId, finalError);

        if (ingestEventService != null) {
            ingestEventService.logRestartFailed(taskId, fileName, totalAttempts, totalTime, finalError);
        }

        String errorMessage = String.format("All %d restart attempts failed: %s", totalAttempts, finalError);
        handle.getResultFuture().complete(VectorPopulationResult.failure(taskId, handle.getCurrentPhase(), errorMessage));

        if (progressTracker != null) {
            progressTracker.failTask(taskId, statsConverter.mapPhaseToEnum(handle.getCurrentPhase()), errorMessage);
        }
        if (ingestProgressTracker != null) {
            IngestPhase ingestPhase = statsConverter.mapPhaseToIngestPhase(handle.getCurrentPhase());
            ingestProgressTracker.failTask(taskId, fileName, ingestPhase, errorMessage);
        }

        broadcastProgressWithStats(taskId, "FAILED", 0, errorMessage,
                Map.of("totalRestartAttempts", totalAttempts));

        restartManager.clearRestartState(taskId);
    }

    /**
     * Launch vector population with specific restart configuration.
     */
    public CompletableFuture<VectorPopulationResult> launchVectorPopulationWithConfig(
            String taskId, String keywordIndexPath, String vectorIndexPath, RestartConfig config) {

        logger.info("RESTART: Launching vector population with adjusted settings for task {}", taskId);
        logger.info("RESTART: Config summary: {}", config.getSummary());

        Map<String, Object> options = new HashMap<>();
        options.put("heapSize", config.heapSize());
        options.put("offHeapBytes", config.offHeapBytes());
        options.put("ompNumThreads", config.ompNumThreads());
        options.put("openBlasNumThreads", config.openBlasNumThreads());
        options.put("embeddingThreads", config.maxThreads());
        options.put("embeddingBatchSize", config.batchSize());
        options.put("restartAttempt", config.attemptNumber());
        options.put("heapIncreased", config.heapIncreased());
        options.put("offHeapReduced", config.offHeapReduced());

        logger.info("RESTART: Memory overrides - heap={}, offHeap={}",
                config.heapSize(), SystemMemoryAnalyzer.formatBytes(config.offHeapBytes()));
        logger.info("RESTART: Thread overrides - OMP={}, BLAS={}, maxThreads={}",
                config.ompNumThreads(), config.openBlasNumThreads(), config.maxThreads());
        logger.info("RESTART: Batch size - {}", config.batchSize());

        if (relaunchCallback == null) {
            throw new IllegalStateException("Relaunch callback not set on SubprocessLifecycleManager");
        }
        return relaunchCallback.apply(taskId, options);
    }

    /**
     * Scheduled task to detect subprocesses that are alive but not emitting progress.
     */
    @Scheduled(fixedRateString = "${kompile.vectorpopulation.subprocess.progress-stall-check-interval-ms:30000}")
    public void checkProgressStalls() {
        if (activeProcesses == null) return;

        int warnSeconds = getEffectiveProgressStallThresholdSeconds();
        Duration warnThreshold = Duration.ofSeconds(warnSeconds);

        int restartSeconds = (restartManager != null)
                ? restartManager.getStallDetectionThresholdSeconds()
                : 300;
        Duration restartThreshold = Duration.ofSeconds(restartSeconds);

        for (VectorPopulationHandle handle : activeProcesses.values()) {
            if (!handle.isAlive() || handle.isCancelled()) {
                continue;
            }

            Duration sinceProgress = handle.timeSinceLastProgress();

            if (sinceProgress.compareTo(restartThreshold) > 0) {
                String errorMessage = String.format(
                        "Process stalled for %ds (phase=%s, progress=%d%%) - potential deadlock. Attempting restart.",
                        sinceProgress.getSeconds(),
                        handle.getCurrentPhase(),
                        handle.getProgressPercent());

                logger.error("Vector population subprocess {} appears deadlocked: {}",
                        handle.getTaskId(), errorMessage);

                if (restartManager != null && !handle.isCancelled()) {
                    SubprocessRestartManager.FailureReason reason = SubprocessRestartManager.FailureReason.STALLED_NO_PROGRESS;
                    if (restartManager.shouldRestart(handle.getTaskId(), reason)) {
                        logger.info("Attempting restart for stalled subprocess {} (no progress for {}s)",
                                handle.getTaskId(), sinceProgress.getSeconds());
                        handle.cancel();
                        scheduleStallRestart(handle, reason, errorMessage);
                        if (warnedTaskIds != null) warnedTaskIds.remove(handle.getTaskId());
                        continue;
                    }
                }

                handle.cancel();

                if (progressTracker != null) {
                    progressTracker.failTask(handle.getTaskId(),
                            statsConverter.mapPhaseToEnum(handle.getCurrentPhase()), errorMessage);
                }
                if (ingestProgressTracker != null) {
                    String displayName = buildTaskDisplayName(handle.getVectorIndexPath());
                    IngestPhase ingestPhase = statsConverter.mapPhaseToIngestPhase(handle.getCurrentPhase());
                    ingestProgressTracker.failTask(handle.getTaskId(), displayName, ingestPhase, errorMessage);
                }
                broadcastProgress(handle.getTaskId(), "FAILED", 0, errorMessage);
                if (warnedTaskIds != null) warnedTaskIds.remove(handle.getTaskId());
                continue;
            }

            if (sinceProgress.compareTo(warnThreshold) <= 0) {
                continue;
            }

            if (warnedTaskIds != null && !warnedTaskIds.add(handle.getTaskId())) {
                continue;
            }

            String msg = String.format(
                    "No progress updates for %ds (phase=%s, progress=%d%%). Will restart after %ds. Last message: %s. Heap: %s",
                    sinceProgress.getSeconds(),
                    handle.getCurrentPhase(),
                    handle.getProgressPercent(),
                    restartSeconds,
                    handle.getLastMessage() != null ? handle.getLastMessage() : "",
                    handle.getHeapSummary());

            logger.warn("Vector population subprocess {} appears stalled: {}", handle.getTaskId(), msg);
            if (progressTracker != null) {
                progressTracker.sendLog(handle.getTaskId(), "WATCHDOG", "WARN", msg);
            }
            if (ingestProgressTracker != null) {
                ingestProgressTracker.sendLog(handle.getTaskId(), "WATCHDOG", "WARN", msg);
            }
        }
    }

    /**
     * Scheduled task to check for stale subprocesses (no heartbeat).
     */
    @Scheduled(fixedRateString = "${kompile.vectorpopulation.subprocess.stale-check-interval-ms:60000}")
    public void checkStaleProcesses() {
        if (activeProcesses == null) return;

        int staleSeconds = getEffectiveStaleThresholdSeconds();
        Duration staleThreshold = Duration.ofSeconds(staleSeconds);

        for (VectorPopulationHandle handle : activeProcesses.values()) {
            if (handle.isAlive() && handle.isStale(staleThreshold)) {
                logger.warn(
                        "Vector population subprocess {} appears stuck (no heartbeat for {} seconds)",
                        handle.getTaskId(), staleSeconds);

                String errorMessage = "Process became unresponsive (no heartbeat for " + staleSeconds + " seconds)";

                if (restartManager != null && !handle.isCancelled()) {
                    SubprocessRestartManager.FailureReason reason = SubprocessRestartManager.FailureReason.STALLED_NO_HEARTBEAT;
                    if (restartManager.shouldRestart(handle.getTaskId(), reason)) {
                        logger.info("Attempting restart for stalled subprocess {} (no heartbeat)", handle.getTaskId());
                        handle.cancel();
                        scheduleStallRestart(handle, reason, errorMessage);
                        continue;
                    }
                }

                handle.cancel();

                if (progressTracker != null) {
                    progressTracker.failTask(handle.getTaskId(),
                            statsConverter.mapPhaseToEnum(handle.getCurrentPhase()), errorMessage);
                }
                if (ingestProgressTracker != null) {
                    String displayName = buildTaskDisplayName(handle.getVectorIndexPath());
                    IngestPhase ingestPhase = statsConverter.mapPhaseToIngestPhase(handle.getCurrentPhase());
                    ingestProgressTracker.failTask(handle.getTaskId(), displayName, ingestPhase, errorMessage);
                }

                broadcastProgress(handle.getTaskId(), "FAILED", 0, errorMessage);
            }
        }
    }

    // ---- private helpers ----

    private int getEffectiveStaleThresholdSeconds() {
        if (subprocessConfigService != null) {
            return subprocessConfigService.getStaleThresholdSeconds();
        }
        return fallbackStaleThresholdSeconds;
    }

    private int getEffectiveProgressStallThresholdSeconds() {
        if (subprocessConfigService != null) {
            return subprocessConfigService.getProgressStallWarningSeconds();
        }
        return fallbackProgressStallThresholdSeconds;
    }

    private int getConfigInt(String envVar, int defaultValue) {
        String value = System.getenv(envVar);
        if (value != null) {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        return defaultValue;
    }

    private String buildTaskDisplayName(String vectorIndexPath) {
        if (vectorIndexPath == null || vectorIndexPath.isBlank()) {
            return "Vector Population";
        }
        return "Vector Population: " + vectorIndexPath;
    }

    private IngestEvent.IngestPhase convertToEventPhase(String phase) {
        if (phase == null || phase.isBlank()) {
            return IngestEvent.IngestPhase.LOADING;
        }
        try {
            return IngestEvent.IngestPhase.valueOf(phase.toUpperCase());
        } catch (IllegalArgumentException e) {
            return IngestEvent.IngestPhase.LOADING;
        }
    }

    private void broadcastProgress(String taskId, String phase, int percent, String message) {
        if (broadcastProgressCallback != null) {
            broadcastProgressCallback.accept(taskId, phase, percent, message);
        }
    }

    private void broadcastProgressWithStats(String taskId, String phase, int percent, String message,
            Map<String, Object> stats) {
        if (broadcastProgressWithStatsCallback != null) {
            broadcastProgressWithStatsCallback.accept(taskId, phase, percent, message, stats);
        }
    }

    private void closeSubprocessLog(VectorPopulationHandle handle, String state,
            Integer exitCode, String errorMessage, boolean oomDetected, boolean gpuOomDetected) {
        ai.kompile.cli.common.logs.SubprocessLogWriter lw = handle.logWriter;
        if (lw == null) {
            return;
        }
        handle.logWriter = null;
        try {
            lw.writeEnd(new ai.kompile.cli.common.logs.SubprocessLogWriter.SubprocessRunResult(
                    state, exitCode, errorMessage, oomDetected, gpuOomDetected));
        } catch (Exception e) {
            logger.debug("[vector-pop-{}] log writeEnd failed: {}", handle.getTaskId(), e.getMessage());
        }
        try {
            lw.close();
        } catch (Exception e) {
            logger.debug("[vector-pop-{}] log close failed: {}", handle.getTaskId(), e.getMessage());
        }
    }
}
