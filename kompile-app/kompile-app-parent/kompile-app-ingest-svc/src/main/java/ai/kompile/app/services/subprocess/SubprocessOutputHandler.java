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
import ai.kompile.app.subprocess.SubprocessProtocolChannel;
import ai.kompile.app.subprocess.SubprocessSignals;
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

    private static final long TIMEOUT_MINUTES = 120;

    /** Upper bound on waiting for the output readers to drain the child's pipes after it exits. */
    private static final long OUTPUT_DRAIN_TIMEOUT_MS = 5_000;

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
    /** Called when an attempt fails for good */
    private BiConsumer<VectorPopulationHandle, SubprocessMessage.Failed> onFailedCallback;
    /** Called when a failed message makes the attempt restartable once it exits */
    private BiConsumer<VectorPopulationHandle, SubprocessMessage.Failed> onRecoveryScheduledCallback;
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
            BiConsumer<VectorPopulationHandle, SubprocessMessage.Failed> onRecoveryScheduled,
            BiConsumer<VectorPopulationHandle, SubprocessMessage.Heartbeat> onHeartbeat,
            BiConsumer<VectorPopulationHandle, Integer> onCompletion,
            Set<String> warnedTaskIds) {
        this.onProgressCallback = onProgress;
        this.onPhaseTransitionCallback = onPhaseTransition;
        this.onCompletedCallback = onCompleted;
        this.onFailedCallback = onFailed;
        this.onRecoveryScheduledCallback = onRecoveryScheduled;
        this.onHeartbeatCallback = onHeartbeat;
        this.onCompletionCallback = onCompletion;
        this.warnedTaskIds = warnedTaskIds;
    }

    /**
     * Start stdout, stderr, and completion-watcher threads for the given handle.
     *
     * @param stderrProtocol finds the protocol messages of a wrapped child that writes them to fd 1
     */
    public void startMonitoring(VectorPopulationHandle handle, SubprocessProtocolChannel.StderrProtocol stderrProtocol) {
        Thread stdoutReader = new Thread(() -> readStdout(handle), "vector-pop-stdout-" + handle.getTaskId());
        stdoutReader.setDaemon(true);
        stdoutReader.start();

        Thread stderrReader = new Thread(() -> readStderr(handle, stderrProtocol),
                "vector-pop-stderr-" + handle.getTaskId());
        stderrReader.setDaemon(true);
        stderrReader.start();

        Thread completionWatcher = new Thread(() -> watchCompletion(handle, stdoutReader, stderrReader),
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
                // A child started without the protocol channel shares this pipe with native code, which writes
                // to fd 1 beneath its System.setOut redirect, so a native message without a newline can precede
                // a protocol message on the same line.
                int prefixAt = line.indexOf(SubprocessMessage.MESSAGE_PREFIX);
                if (prefixAt > 0) {
                    forwardOutput(handle, line.substring(0, prefixAt));
                }
                if (prefixAt >= 0) {
                    String json = line.substring(prefixAt + SubprocessMessage.MESSAGE_PREFIX.length());
                    handleMessage(handle, json);
                } else {
                    forwardOutput(handle, line);
                }
            }
        } catch (IOException e) {
            if (!handle.isCancelled()) {
                logger.debug("Stdout reader terminated for task: {}", handle.getTaskId());
            }
        }
    }

    /** Stdout that is not a protocol message: regular log output. */
    private void forwardOutput(VectorPopulationHandle handle, String line) {
        if (line.isBlank()) {
            return;
        }
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

    /**
     * Read stderr from subprocess. A wrapped child's fd 1 is this pipe too, so it carries the native output
     * that reaches fd 1, and the protocol messages of a child that writes them there
     * ({@link SubprocessProtocolChannel.StderrProtocol}).
     */
    public void readStderr(VectorPopulationHandle handle, SubprocessProtocolChannel.StderrProtocol stderrProtocol) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(handle.getProcess().getErrorStream()))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank())
                    continue;

                // A child that writes its protocol messages to fd 1 has them here, behind whatever else
                // reached fd 1
                int prefixAt = stderrProtocol.prefixIndex(line);
                if (prefixAt > 0) {
                    forwardStderr(handle, line.substring(0, prefixAt));
                }
                if (prefixAt >= 0) {
                    handleMessage(handle, line.substring(prefixAt + SubprocessMessage.MESSAGE_PREFIX.length()));
                } else {
                    forwardStderr(handle, line);
                }
            }
        } catch (IOException e) {
            if (!handle.isCancelled()) {
                logger.debug("Stderr reader terminated for task: {}", handle.getTaskId());
            }
        }
    }

    /** Stderr that is not a protocol message: scanned for OOMs and forwarded as log output. */
    private void forwardStderr(VectorPopulationHandle handle, String line) {
        if (line.isBlank()) {
            return;
        }
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

    /**
     * Watch for process completion. The exit is handled once the output readers have drained the child's
     * pipes: its COMPLETED message, or the OOM that makes the exit restartable, is often still unread
     * when the process exits.
     */
    public void watchCompletion(VectorPopulationHandle handle, Thread... outputReaders) {
        try {
            Process process = handle.getProcess();
            int exitCode;
            if (process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                exitCode = process.exitValue();
                logger.info("Vector population subprocess {} exited with code: {}", handle.getTaskId(), exitCode);
            } else {
                logger.error("Vector population subprocess {} timed out after {} minutes, destroying",
                        handle.getTaskId(), TIMEOUT_MINUTES);
                // Failed before the kill, so its exit is not taken for an OOM and restarted
                failAttempt(handle, new SubprocessMessage.Failed(handle.getTaskId(), handle.getCurrentPhase(),
                        "Timed out after " + TIMEOUT_MINUTES + " minutes", "TIMEOUT", null));
                SubprocessSignals.kill(process);
                exitCode = process.waitFor(30, TimeUnit.SECONDS) ? process.exitValue() : 137;
            }

            awaitOutputReaders(handle, outputReaders);

            if (onCompletionCallback != null) {
                onCompletionCallback.accept(handle, exitCode);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.debug("Completion watcher interrupted for task: {}", handle.getTaskId());
        }
    }

    /**
     * Wait for the output readers to reach the end of the child's pipes. Bounded: a grandchild that
     * inherited the pipes keeps them open after the child exits.
     */
    private void awaitOutputReaders(VectorPopulationHandle handle, Thread... outputReaders) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(OUTPUT_DRAIN_TIMEOUT_MS);
        try {
            for (Thread reader : outputReaders) {
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remainingMs > 0) {
                    reader.join(remainingMs);
                }
                if (reader.isAlive()) {
                    logger.warn("[vector-pop-{}] {} still reading {} ms after the process exited; handling the exit",
                            handle.getTaskId(), reader.getName(), OUTPUT_DRAIN_TIMEOUT_MS);
                }
            }
        } catch (InterruptedException e) {
            // The process has exited, so its exit is still handled
            Thread.currentThread().interrupt();
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

                    if (!handle.getResultFuture().complete(VectorPopulationResult.success(
                            handle.getTaskId(), completed.documentsLoaded(), completed.chunksEmbedded(),
                            completed.documentsIndexed(), completed.totalDurationMs(), handle.getVectorIndexPath()))) {
                        logger.warn("Task {} reported completion after its attempt had already ended; not reporting it again",
                                handle.getTaskId());
                        return;
                    }

                    if (opTimingService != null) {
                        opTimingService.recordSubprocessComplete(handle.getTaskId(), true);
                    }

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
                        if (handle.getResultFuture().isDone()) {
                            logger.warn("Task {} reported {} after its attempt had already ended; not scheduling a recovery",
                                    handle.getTaskId(), failureReason);
                            return;
                        }
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
                        if (onRecoveryScheduledCallback != null) {
                            onRecoveryScheduledCallback.accept(handle, failed);
                        }
                        return;
                    }

                    failAttempt(handle, failed);
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

    /**
     * Fail an attempt for good: its result, the progress trackers, and the launcher's failure handling.
     * Reported once — an attempt whose verdict was already given is left as it is.
     */
    private void failAttempt(VectorPopulationHandle handle, SubprocessMessage.Failed failed) {
        if (!handle.getResultFuture().complete(VectorPopulationResult.failure(
                handle.getTaskId(), failed.phase(), failed.errorMessage()))) {
            logger.warn("Task {} reported a failure after its attempt had already ended; not reporting it again: {}",
                    handle.getTaskId(), failed.errorMessage());
            return;
        }

        if (opTimingService != null) {
            opTimingService.recordSubprocessComplete(handle.getTaskId(), false);
        }

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

    private String buildTaskDisplayName(String vectorIndexPath) {
        if (vectorIndexPath == null || vectorIndexPath.isBlank()) {
            return "Vector Population";
        }
        return "Vector Population: " + vectorIndexPath;
    }
}
