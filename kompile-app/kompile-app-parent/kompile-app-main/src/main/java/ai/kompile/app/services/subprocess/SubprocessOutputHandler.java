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
import ai.kompile.app.services.OpTimingService;
import ai.kompile.app.services.VectorPopulationProgressTracker;
import ai.kompile.app.services.VectorPopulationProgressTracker.VectorPopulationStats;
import ai.kompile.app.subprocess.SubprocessMessage;
import ai.kompile.app.web.dto.IngestProgressUpdate;
import ai.kompile.app.web.dto.IngestProgressUpdate.IngestPhase;
import ai.kompile.app.web.dto.IngestProgressUpdate.IngestStats;
import ai.kompile.cli.common.logs.AgentLogRecord;
import ai.kompile.cli.common.logs.SubprocessLogWriter;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Handles I/O monitoring and the subprocess message protocol for vector population jobs.
 *
 * Reads stdout/stderr from the subprocess, dispatches structured messages,
 * and invokes the appropriate callbacks on the launcher.
 */
@Component
public class SubprocessOutputHandler {

    private static final Logger logger = LoggerFactory.getLogger(SubprocessOutputHandler.class);

    private final VectorPopulationProgressTracker progressTracker;
    private final IngestProgressTracker ingestProgressTracker;
    private final OpTimingService opTimingService;
    private final IngestEventService ingestEventService;
    private final ObjectMapper objectMapper;
    private final VectorPopulationStatsConverter statsConverter;

    // Callbacks provided by the launcher (avoid circular Spring dependency)
    /** Called when a progress message arrives; args: (handle, progress) */
    private BiConsumer<VectorPopulationHandle, SubprocessMessage.Progress> onProgressCallback;
    /** Called when a phase transition arrives */
    private BiConsumer<VectorPopulationHandle, SubprocessMessage.PhaseTransition> onPhaseTransitionCallback;
    /** Called when a completed message arrives */
    private BiConsumer<VectorPopulationHandle, SubprocessMessage.Completed> onCompletedCallback;
    /** Called when a failed message arrives */
    private BiConsumer<VectorPopulationHandle, SubprocessMessage.Failed> onFailedCallback;
    /** Called to broadcast a raw progress update via WebSocket */
    private BiConsumer<VectorPopulationHandle, SubprocessMessage.Heartbeat> onHeartbeatCallback;
    /** Called when the subprocess watchCompletion thread detects exit */
    private BiConsumer<VectorPopulationHandle, Integer> onCompletionCallback;
    /** Set of task IDs that have already emitted a stall warning (managed by lifecycle layer) */
    private Set<String> warnedTaskIds;

    public SubprocessOutputHandler(
            VectorPopulationProgressTracker progressTracker,
            IngestProgressTracker ingestProgressTracker,
            OpTimingService opTimingService,
            IngestEventService ingestEventService,
            VectorPopulationStatsConverter statsConverter) {
        this.progressTracker = progressTracker;
        this.ingestProgressTracker = ingestProgressTracker;
        this.opTimingService = opTimingService;
        this.ingestEventService = ingestEventService;
        this.statsConverter = statsConverter;
        this.objectMapper = JsonUtils.standardMapper();
    }

    /** Wire in the launcher-side callbacks after construction (avoids circular dependency). */
    public void setCallbacks(
            BiConsumer<VectorPopulationHandle, SubprocessMessage.Progress> onProgress,
            BiConsumer<VectorPopulationHandle, SubprocessMessage.PhaseTransition> onPhaseTransition,
            BiConsumer<VectorPopulationHandle, SubprocessMessage.Completed> onCompleted,
            BiConsumer<VectorPopulationHandle, SubprocessMessage.Failed> onFailed,
            BiConsumer<VectorPopulationHandle, SubprocessMessage.Heartbeat> onHeartbeat,
            BiConsumer<VectorPopulationHandle, Integer> onCompletion,
            Set<String> warnedTaskIds) {
        this.onProgressCallback = onProgress;
        this.onPhaseTransitionCallback = onPhaseTransition;
        this.onCompletedCallback = onCompleted;
        this.onFailedCallback = onFailed;
        this.onHeartbeatCallback = onHeartbeat;
        this.onCompletionCallback = onCompletion;
        this.warnedTaskIds = warnedTaskIds;
    }

    /**
     * Start stdout, stderr, and completion-watcher threads for the given handle.
     */
    public void startMonitoring(VectorPopulationHandle handle) {
        Thread stdoutReader = new Thread(() -> readStdout(handle), "vector-pop-stdout-" + handle.getTaskId());
        stdoutReader.setDaemon(true);
        stdoutReader.start();

        Thread stderrReader = new Thread(() -> readStderr(handle), "vector-pop-stderr-" + handle.getTaskId());
        stderrReader.setDaemon(true);
        stderrReader.start();

        Thread completionWatcher = new Thread(() -> watchCompletion(handle),
                "vector-pop-watcher-" + handle.getTaskId());
        completionWatcher.setDaemon(true);
        completionWatcher.start();
    }

    /**
     * Read and parse stdout from subprocess.
     */
    public void readStdout(VectorPopulationHandle handle) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(handle.getProcess().getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(SubprocessMessage.MESSAGE_PREFIX)) {
                    String json = line.substring(SubprocessMessage.MESSAGE_PREFIX.length());
                    handleMessage(handle, json);
                } else if (!line.isBlank()) {
                    logger.debug("[vector-pop-{}] {}", handle.getTaskId(), line);
                    if (progressTracker != null) {
                        progressTracker.sendLog(handle.getTaskId(), "STDOUT", "INFO", line);
                    }
                    if (ingestProgressTracker != null) {
                        ingestProgressTracker.sendLog(handle.getTaskId(), "STDOUT", "INFO", line);
                    }
                    SubprocessLogWriter lw = handle.logWriter;
                    if (lw != null) {
                        try {
                            lw.writeLine(AgentLogRecord.Stream.STDOUT, line);
                        } catch (Exception logEx) {
                            logger.debug("[vector-pop-{}] log write failed: {}", handle.getTaskId(), logEx.getMessage());
                        }
                    }
                }
            }
        } catch (IOException e) {
            if (!handle.isCancelled()) {
                logger.debug("Stdout reader terminated for task: {}", handle.getTaskId());
            }
        }
    }

    /**
     * Read stderr from subprocess.
     */
    public void readStderr(VectorPopulationHandle handle) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(handle.getProcess().getErrorStream()))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank())
                    continue;

                String level;
                if (line.contains("OutOfMemoryError") || line.contains("Java heap space")) {
                    logger.error("[vector-pop-{}] OOM detected: {}", handle.getTaskId(), line);
                    handle.setOomDetected(true);
                    level = "ERROR";
                } else if (line.startsWith("\tat") || line.startsWith("Caused by:") || line.startsWith("Suppressed:")) {
                    logger.error("[vector-pop-{}] {}", handle.getTaskId(), line);
                    level = "ERROR";
                } else if (line.contains("ERROR") || line.contains("Exception") || line.contains("FATAL")) {
                    logger.error("[vector-pop-{}] {}", handle.getTaskId(), line);
                    level = "ERROR";
                } else if (line.contains("WARN")) {
                    logger.warn("[vector-pop-{}] {}", handle.getTaskId(), line);
                    level = "WARN";
                } else if (line.contains(" INFO ")) {
                    logger.info("[vector-pop-{}] {}", handle.getTaskId(), line);
                    level = "INFO";
                } else if (line.contains("DEBUG")) {
                    logger.debug("[vector-pop-{}] {}", handle.getTaskId(), line);
                    level = "DEBUG";
                } else {
                    logger.debug("[vector-pop-{}] {}", handle.getTaskId(), line);
                    level = "INFO";
                }

                if (progressTracker != null) {
                    progressTracker.sendLog(handle.getTaskId(), "STDERR", level, line);
                }
                if (ingestProgressTracker != null) {
                    ingestProgressTracker.sendLog(handle.getTaskId(), "STDERR", level, line);
                }
                SubprocessLogWriter lw = handle.logWriter;
                if (lw != null) {
                    try {
                        lw.writeLine(AgentLogRecord.Stream.STDERR, line);
                    } catch (Exception logEx) {
                        logger.debug("[vector-pop-{}] log write failed: {}", handle.getTaskId(), logEx.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            if (!handle.isCancelled()) {
                logger.debug("Stderr reader terminated for task: {}", handle.getTaskId());
            }
        }
    }

    /**
     * Watch for process completion.
     */
    public void watchCompletion(VectorPopulationHandle handle) {
        try {
            boolean exited = handle.getProcess().waitFor(120, TimeUnit.MINUTES);
            if (!exited) {
                logger.error("Vector population subprocess {} timed out after 120 minutes, destroying", handle.getTaskId());
                handle.getProcess().destroyForcibly();
                return;
            }
            int exitCode = handle.getProcess().exitValue();
            logger.info("Vector population subprocess {} exited with code: {}", handle.getTaskId(), exitCode);

            if (onCompletionCallback != null) {
                onCompletionCallback.accept(handle, exitCode);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.debug("Completion watcher interrupted for task: {}", handle.getTaskId());
        }
    }

    /**
     * Handle a parsed message from subprocess stdout.
     */
    public void handleMessage(VectorPopulationHandle handle, String json) {
        try {
            SubprocessMessage message = objectMapper.readValue(json, SubprocessMessage.class);
            logger.debug("Received subprocess message: type={}, taskId={}",
                    message.getClass().getSimpleName(), handle.getTaskId());

            final boolean[] earlyReturn = {false};

            SubprocessMessage.dispatch(message, new SubprocessMessage.Handler() {
                @Override
                public void onProgress(SubprocessMessage.Progress progress) {
                    handle.updateProgress(progress.phase(), progress.progressPercent(), progress.message());
                    if (warnedTaskIds != null) {
                        warnedTaskIds.remove(handle.getTaskId());
                    }

                    if (progressTracker != null) {
                        VectorPopulationStats stats = statsConverter.buildStatsFromProgress(progress);
                        progressTracker.updateProgress(
                                handle.getTaskId(),
                                statsConverter.mapPhaseToEnum(progress.phase()),
                                progress.progressPercent(),
                                progress.currentStep(),
                                progress.message(),
                                stats);
                    }

                    if (ingestProgressTracker != null) {
                        String displayName = buildTaskDisplayName(handle.getVectorIndexPath());
                        IngestStats ingestStats = statsConverter.buildIngestStatsFromProgress(progress, handle.getTaskId());
                        IngestPhase ingestPhase = statsConverter.mapPhaseToIngestPhase(progress.phase());
                        ingestProgressTracker.updateProgress(
                                handle.getTaskId(),
                                displayName,
                                ingestPhase,
                                progress.progressPercent(),
                                progress.currentStep(),
                                progress.message(),
                                ingestStats);
                    }

                    if (onProgressCallback != null) {
                        onProgressCallback.accept(handle, progress);
                    }
                }

                @Override
                public void onPhaseTransition(SubprocessMessage.PhaseTransition transition) {
                    handle.setCurrentPhase(transition.toPhase());
                    handle.updateHeartbeat();
                    logger.info("Task {} phase transition: {} -> {}",
                            handle.getTaskId(), transition.fromPhase(), transition.toPhase());

                    if (opTimingService != null) {
                        String toPhase = transition.toPhase() != null ? transition.toPhase().toUpperCase() : "";
                        String fromPhase = transition.fromPhase() != null ? transition.fromPhase().toUpperCase() : "";

                        if (toPhase.equals("LOADING") || toPhase.equals("MODEL_LOADING") || toPhase.equals("INITIALIZING")) {
                            opTimingService.recordModelLoadStart(handle.getTaskId(), "embedding-model");
                        } else if ((fromPhase.equals("LOADING") || fromPhase.equals("MODEL_LOADING") || fromPhase.equals("INITIALIZING"))
                                && (toPhase.equals("EMBEDDING") || toPhase.equals("INDEXING") || toPhase.equals("PROCESSING"))) {
                            opTimingService.recordModelLoadComplete(handle.getTaskId());
                        }
                    }

                    if (progressTracker != null) {
                        progressTracker.updateProgress(
                                handle.getTaskId(),
                                statsConverter.mapPhaseToEnum(transition.toPhase()),
                                0,
                                "Starting " + transition.toPhase().toLowerCase(),
                                "Phase transition: " + transition.fromPhase() + " -> " + transition.toPhase(),
                                null);
                    }

                    if (ingestProgressTracker != null) {
                        String displayName = buildTaskDisplayName(handle.getVectorIndexPath());
                        IngestPhase ingestPhase = statsConverter.mapPhaseToIngestPhase(transition.toPhase());
                        IngestStats ingestStats = IngestStats.builder()
                                .subprocessRuntimeInfo(
                                        IngestProgressUpdate.SubprocessRuntimeInfo.forProcessMode("SUBPROCESS"))
                                .build();
                        ingestProgressTracker.updateProgress(
                                handle.getTaskId(),
                                displayName,
                                ingestPhase,
                                0,
                                "Starting " + transition.toPhase().toLowerCase(),
                                "Phase transition: " + transition.fromPhase() + " -> " + transition.toPhase(),
                                ingestStats);
                    }

                    if (onPhaseTransitionCallback != null) {
                        onPhaseTransitionCallback.accept(handle, transition);
                    }
                }

                @Override
                public void onHeartbeat(SubprocessMessage.Heartbeat heartbeat) {
                    if (opTimingService != null && !handle.isStartupComplete()) {
                        opTimingService.recordSubprocessStartupComplete(handle.getTaskId());
                        handle.setStartupComplete(true);
                    }
                    handle.updateHeartbeat(heartbeat);
                    logger.debug("Task {} heartbeat: uptime={}ms, heap={}%, offHeap={}%, gpu={}%",
                            handle.getTaskId(), heartbeat.uptimeMs(),
                            String.format("%.1f", heartbeat.memoryUsagePercent()),
                            String.format("%.1f", heartbeat.offHeapUsagePercent()),
                            String.format("%.1f", heartbeat.gpuUsagePercent()));
                    if (onHeartbeatCallback != null) {
                        onHeartbeatCallback.accept(handle, heartbeat);
                    }
                }

                @Override
                public void onLog(SubprocessMessage.Log log) {
                    if (progressTracker != null) {
                        progressTracker.sendLog(handle.getTaskId(), log.source(), log.level(), log.message());
                    }
                    if (ingestProgressTracker != null) {
                        ingestProgressTracker.sendLog(handle.getTaskId(), log.source(), log.level(), log.message());
                    }
                }

                @Override
                public void onCompleted(SubprocessMessage.Completed completed) {
                    logger.info("Task {} completed: {} docs embedded and indexed",
                            handle.getTaskId(), completed.documentsIndexed());

                    if (opTimingService != null) {
                        opTimingService.recordSubprocessComplete(handle.getTaskId(), true);
                    }

                    handle.getResultFuture().complete(VectorPopulationResult.success(
                            handle.getTaskId(), completed.documentsLoaded(), completed.chunksEmbedded(),
                            completed.documentsIndexed(), completed.totalDurationMs(), handle.getVectorIndexPath()));

                    if (progressTracker != null) {
                        VectorPopulationStats finalStats = new VectorPopulationStats(
                                completed.documentsLoaded(),
                                completed.chunksCreated(),
                                completed.chunksEmbedded(),
                                completed.documentsIndexed(),
                                completed.documentsLoaded(),
                                completed.tokensProcessed(),
                                completed.totalTokensInIndex(),
                                completed.totalDurationMs(),
                                completed.documentsIndexed() > 0 && completed.totalDurationMs() > 0
                                        ? (completed.documentsIndexed() * 1000.0 / completed.totalDurationMs())
                                        : 0,
                                0,
                                null,
                                null,
                                null,
                                null,
                                IngestProgressUpdate.SubprocessRuntimeInfo.forProcessMode("SUBPROCESS"));
                        progressTracker.completeTask(handle.getTaskId(), finalStats);
                    }

                    if (ingestProgressTracker != null) {
                        String displayName = buildTaskDisplayName(handle.getVectorIndexPath());
                        IngestStats ingestStats = statsConverter.buildIngestStatsFromCompleted(completed);
                        ingestProgressTracker.completeTask(handle.getTaskId(), displayName, ingestStats);
                    }

                    if (onCompletedCallback != null) {
                        onCompletedCallback.accept(handle, completed);
                    }
                }

                @Override
                public void onFailed(SubprocessMessage.Failed failed) {
                    logger.error("Task {} failed in phase {}: {}",
                            handle.getTaskId(), failed.phase(), failed.errorMessage());

                    SubprocessRestartManager.FailureReason failureReason =
                            statsConverter.determineFailureReason(failed.errorMessage(), failed.errorType());

                    boolean isRestartableFailure = failureReason == SubprocessRestartManager.FailureReason.OUT_OF_MEMORY ||
                            failureReason == SubprocessRestartManager.FailureReason.BATCH_SIZE_TOO_LARGE;

                    if (isRestartableFailure) {
                        handle.setOomDetected(true);
                        handle.setCurrentPhase(failed.phase());
                        handle.setFailureReason(failureReason);

                        String recoveryType = failureReason == SubprocessRestartManager.FailureReason.BATCH_SIZE_TOO_LARGE
                                ? "BATCH SIZE TOO LARGE" : "OOM";
                        String recoveryAction = failureReason == SubprocessRestartManager.FailureReason.BATCH_SIZE_TOO_LARGE
                                ? "reducing batch size by 75%" : "adjusting memory settings";

                        logger.warn("{} detected via protocol message for task {} - " +
                                "NOT marking as failed yet, will attempt restart after process exit ({})",
                                recoveryType, handle.getTaskId(), recoveryAction);

                        if (ingestProgressTracker != null) {
                            String displayName = buildTaskDisplayName(handle.getVectorIndexPath());
                            ingestProgressTracker.sendLog(handle.getTaskId(), "SYSTEM", "WARN",
                                    "[ADAPTIVE RECOVERY] " + recoveryType + " detected during " + failed.phase() +
                                    " - subprocess will restart with " + recoveryAction);
                        }

                        earlyReturn[0] = true;
                        // Signal the launcher for the broadcast (via onFailed with restartable flag)
                        if (onFailedCallback != null) {
                            // Pass through so launcher can broadcast RECOVERY_SCHEDULED
                            onFailedCallback.accept(handle, failed);
                        }
                        return;
                    }

                    if (opTimingService != null) {
                        opTimingService.recordSubprocessComplete(handle.getTaskId(), false);
                    }

                    handle.getResultFuture().complete(VectorPopulationResult.failure(
                            handle.getTaskId(), failed.phase(), failed.errorMessage()));

                    if (progressTracker != null) {
                        progressTracker.failTask(handle.getTaskId(),
                                statsConverter.mapPhaseToEnum(failed.phase()), failed.errorMessage());
                    }

                    if (ingestProgressTracker != null) {
                        String displayName = buildTaskDisplayName(handle.getVectorIndexPath());
                        IngestPhase ingestPhase = statsConverter.mapPhaseToIngestPhase(failed.phase());
                        ingestProgressTracker.failTask(handle.getTaskId(), displayName, ingestPhase, failed.errorMessage());
                    }

                    if (onFailedCallback != null) {
                        onFailedCallback.accept(handle, failed);
                    }
                }
            });

            if (earlyReturn[0]) {
                return;
            }
        } catch (Exception e) {
            logger.warn("Failed to parse subprocess message: {}", json, e);
            if (progressTracker != null) {
                progressTracker.sendLog(handle.getTaskId(), "PARENT", "ERROR",
                        "Failed to parse subprocess protocol message: " + e.getMessage());
            }
            if (ingestProgressTracker != null) {
                ingestProgressTracker.sendLog(handle.getTaskId(), "PARENT", "ERROR",
                        "Failed to parse subprocess protocol message: " + e.getMessage());
            }
        }
    }

    private String buildTaskDisplayName(String vectorIndexPath) {
        if (vectorIndexPath == null || vectorIndexPath.isBlank()) {
            return "Vector Population";
        }
        return "Vector Population: " + vectorIndexPath;
    }
}
