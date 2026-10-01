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

import ai.kompile.app.config.DeviceRoutingConfig;
import ai.kompile.app.config.GpuDevice;
import ai.kompile.app.config.Nd4jEnvironmentConfig;
import ai.kompile.app.config.SubprocessExecutableConfig;
import ai.kompile.app.services.DeviceRoutingConfigService;
import ai.kompile.app.services.ModelLifecycleManager;
import ai.kompile.app.services.ModelLifecycleManager.GpuShortfallException;
import ai.kompile.app.services.scheduler.JobResourceProfiles;
import ai.kompile.app.services.subprocess.SubprocessConfigService;
import ai.kompile.app.subprocess.BackendConfigurable;
import ai.kompile.app.subprocess.ManagedSubprocessLauncher.BackendPreference;
import ai.kompile.app.subprocess.SubprocessBackendFlags;
import ai.kompile.app.subprocess.SubprocessClasspathBuilder;
import ai.kompile.app.subprocess.SubprocessEnvironmentPropagator;
import ai.kompile.app.subprocess.SubprocessPlacement;
import ai.kompile.app.subprocess.SubprocessPlacementSupport;
import ai.kompile.app.subprocess.SubprocessProtocolChannel;
import ai.kompile.app.subprocess.SubprocessSignals;
import ai.kompile.cli.common.logs.AgentLogRecord;
import ai.kompile.cli.common.logs.SubprocessLogWriter;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.utils.NativeImageInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Service for launching and monitoring model initialization subprocesses.
 *
 * <p>This service manages:
 * <ul>
 *   <li>Launching model init subprocesses with proper JVM configuration</li>
 *   <li>Parsing progress messages from subprocess STDOUT</li>
 *   <li>Forwarding progress updates to registered listeners</li>
 *   <li>Handling subprocess completion and failure</li>
 *   <li>Cleanup on shutdown</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>
 * ModelInitSubprocessArgs args = ModelInitSubprocessArgs.builder()
 *     .modelIdentifier("bge-base-en-v1.5")
 *     .build();
 *
 * CompletableFuture&lt;ModelInitResult&gt; future = launcher.launchModelInit(args,
 *     progress -&gt; log.info("Progress: {}", progress),
 *     result -&gt; log.info("Complete: {}", result),
 *     failure -&gt; log.error("Failed: {}", failure)
 * );
 * </pre>
 */
@Service
public class ModelInitSubprocessLauncher implements BackendConfigurable {

    private static final Logger logger = LoggerFactory.getLogger(ModelInitSubprocessLauncher.class);
    private static final ObjectMapper OBJECT_MAPPER = JsonUtils.standardMapper();

    /** How long a killed child may take to exit before its GPU row is left to be released on exit */
    private static final long CHILD_EXIT_WAIT_SECONDS = 30;

    /** How long the output readers may take to pass on what an ended child wrote before the launcher judges it */
    private static final long OUTPUT_DRAIN_SECONDS = 5;

    /** Shared device-agnostic placement (same base infra every subprocess uses). */
    private final SubprocessPlacementSupport placement = new SubprocessPlacementSupport();

    /** {@link BackendConfigurable} — the scheduler assigns backend/device/memory before spawn. */
    @Override
    public void applyPlacement(SubprocessPlacement p) {
        this.placement.applyPlacement(p);
    }

    // Configuration
    @Value("${kompile.model-init.subprocess.java-path:java}")
    String javaPath;

    @Value("${kompile.model-init.subprocess.heap-size:4g}")
    String heapSize;

    @Value("${kompile.model-init.subprocess.timeout-minutes:10}")
    int timeoutMinutes;

    @Value("${kompile.model-init.subprocess.heartbeat-timeout-seconds:30}")
    private int heartbeatTimeoutSeconds;

    // Native image configuration
    @Autowired(required = false)
    private SubprocessExecutableConfig subprocessExecutableConfig;

    @Autowired(required = false)
    SubprocessConfigService subprocessConfigService;

    @Autowired(required = false)
    private DeviceRoutingConfigService deviceRoutingConfigService;

    @Autowired(required = false)
    ModelLifecycleManager modelLifecycleManager;

    // Active processes
    final Map<String, SubprocessHandle> activeProcesses = new ConcurrentHashMap<>();

    /** Orders tracking a started child against {@link #cleanup()}, so no child outlives shutdown */
    private final Object lifecycleLock = new Object();

    /** Set by {@link #cleanup()}: no init starts after it */
    private volatile boolean shutDown;

    /** TaskIds whose GPU row this launcher acquired itself; released once, when the child is gone */
    private final Set<String> launcherGpuHolds = ConcurrentHashMap.newKeySet();

    // Current model init status
    private volatile ModelInitStatus currentStatus = ModelInitStatus.idle();

    /**
     * Launch a model initialization subprocess.
     *
     * @param args             Subprocess arguments
     * @param progressListener Listener for progress updates (may be null)
     * @param completionListener Listener for successful completion (may be null)
     * @param failureListener  Listener for failures (may be null)
     * @return CompletableFuture that completes when initialization is done
     */
    public CompletableFuture<ModelInitResult> launchModelInit(
            ModelInitSubprocessArgs args,
            Consumer<ModelInitMessage.Progress> progressListener,
            Consumer<ModelInitMessage.Completed> completionListener,
            Consumer<ModelInitMessage.Failed> failureListener) {

        String taskId = args.taskId();
        if (taskId == null || taskId.isBlank()) {
            taskId = UUID.randomUUID().toString();
            args = copyWithTaskAndNd4jConfig(args, taskId, args.nd4jConfigJson());
        }

        // Apply device routing overlay for modelInit service if enabled
        if (deviceRoutingConfigService != null && deviceRoutingConfigService.isEnabled()) {
            try {
                Nd4jEnvironmentConfig routedConfig = deviceRoutingConfigService
                        .resolveNd4jConfigForService(DeviceRoutingConfig.SERVICE_MODEL_INIT);
                String routedJson = OBJECT_MAPPER.writeValueAsString(routedConfig);
                logger.info("Using device-routed ND4J config for modelInit: maxThreads={}, cudaDevice={}",
                        routedConfig.maxThreads(), routedConfig.cudaCurrentDevice());
                args = copyWithTaskAndNd4jConfig(args, taskId, routedJson);
            } catch (Exception e) {
                logger.warn("Failed to apply device routing for modelInit, using original config: {}", e.getMessage());
            }
        }

        final String finalTaskId = taskId;
        final ModelInitSubprocessArgs finalArgs = args;

        // Update status
        currentStatus = ModelInitStatus.starting(finalTaskId, finalArgs.modelIdentifier());

        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeModelInit(finalArgs, progressListener, completionListener, failureListener);
            } catch (ReportedFailure e) {
                // The status and the failure listener already carry this failure's verdict
                logger.warn("[model-init-{}] Failed: {}", finalTaskId, e.getMessage());
                throw new RuntimeException("Model init subprocess failed", e);
            } catch (Exception e) {
                // No GPU room right now: the init never started and can be retried later
                boolean retriable = e instanceof GpuShortfallException;
                if (retriable) {
                    logger.warn("[model-init-{}] Not started, no GPU room: {}", finalTaskId, e.getMessage());
                } else {
                    logger.error("Failed to launch model init subprocess", e);
                }
                currentStatus = ModelInitStatus.failed(finalTaskId, finalArgs.modelIdentifier(),
                        ModelInitMessage.Phase.STARTING, e.getMessage(), retriable);

                if (failureListener != null) {
                    failureListener.accept(ModelInitMessage.failed(finalTaskId, finalArgs.modelIdentifier(),
                            ModelInitMessage.Phase.STARTING, e, retriable));
                }
                throw new RuntimeException("Model init subprocess failed", e);
            }
        });
    }

    static ModelInitSubprocessArgs copyWithTaskAndNd4jConfig(
            ModelInitSubprocessArgs source,
            String taskId,
            String nd4jConfigJson) {
        return ModelInitSubprocessArgs.builder()
                .taskId(taskId)
                .modelIdentifier(source.modelIdentifier())
                .modelSourceType(source.modelSourceType())
                .stagingUrl(source.stagingUrl())
                .stagingApiKey(source.stagingApiKey())
                .archivePath(source.archivePath())
                .optimalBatchSize(source.optimalBatchSize())
                .maxBatchSize(source.maxBatchSize())
                .nd4jConfigJson(nd4jConfigJson)
                .callbackBaseUrl(source.callbackBaseUrl())
                .memoryThresholdPercent(source.memoryThresholdPercent())
                .memoryCriticalPercent(source.memoryCriticalPercent())
                .memoryKillThresholdPercent(source.memoryKillThresholdPercent())
                .memoryCheckIntervalMs(source.memoryCheckIntervalMs())
                .gpuMemoryThresholdPercent(source.gpuMemoryThresholdPercent())
                .gpuMemoryCriticalPercent(source.gpuMemoryCriticalPercent())
                .gpuMemoryKillThresholdPercent(source.gpuMemoryKillThresholdPercent())
                .offHeapThresholdPercent(source.offHeapThresholdPercent())
                .offHeapCriticalPercent(source.offHeapCriticalPercent())
                .offHeapKillThresholdPercent(source.offHeapKillThresholdPercent())
                .skipValidation(source.skipValidation())
                .validationTestText(source.validationTestText())
                .options(source.options())
                .build();
    }

    /**
     * Execute model initialization in subprocess.
     */
    private ModelInitResult executeModelInit(
            ModelInitSubprocessArgs args,
            Consumer<ModelInitMessage.Progress> progressListener,
            Consumer<ModelInitMessage.Completed> completionListener,
            Consumer<ModelInitMessage.Failed> failureListener) throws Exception {

        String taskId = args.taskId();
        String modelId = args.modelIdentifier();

        logger.info("Launching model init subprocess for model: {} (task: {})", modelId, taskId);

        // After shutdown nothing starts: no GPU row is acquired and no child spawned
        if (shutDown) {
            throw new IllegalStateException("Model init launcher is shut down");
        }

        // Settle the task's GPU row before any command is built, so the child is pinned to its device
        SubprocessPlacement taskPlacement = resolveTaskPlacement(taskId, placement.placement());

        // Until the child is running, a failure deletes the args file and releases the row here
        Path argsFile = null;
        List<String> command;
        ProcessBuilder pb;
        Process process;
        boolean wrapped;
        boolean started = false;
        try {
            // Write args to temp file
            argsFile = args.writeToTempFile();
            logger.debug("Args file created: {}", argsFile);

            // Build command
            command = buildCommand(argsFile, taskPlacement);
            logger.info("Command: {}", String.join(" ", command));

            // Start process
            pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false); // Keep stderr separate for logging

            // Propagate all ND4J/CUDA/threading/Triton env vars via central propagator
            SubprocessEnvironmentPropagator.propagateToEnvironment(pb.environment());
            // Device-agnostic per-device memory bound (SD_MAX_DEVICE_BYTES) — shared base infra.
            SubprocessBackendFlags.applyEnv(pb.environment(), taskPlacement);
            // Protocol messages get a pipe of their own, which native output written to fd 1 can't reach
            wrapped = SubprocessProtocolChannel.apply(pb);

            process = pb.start();
            started = true;
        } finally {
            if (!started) {
                deleteArgsFile(argsFile);
                releaseModelInitGpu(taskId);
            }
        }

        // Create handle
        SubprocessHandle handle = new SubprocessHandle(taskId, modelId, process, argsFile);
        try {
            // A child the shutdown can no longer find is killed below instead of tracked
            track(handle);
            SubprocessLogWriter logWriter = openLog(handle, pb, command);
            return awaitModelInit(handle, logWriter, wrapped, progressListener, completionListener, failureListener);
        } finally {
            // However the init ended, its child is killed and its GPU row released once the child is gone
            if (process.isAlive()) {
                SubprocessSignals.kill(process);
            }
            activeProcesses.remove(taskId, handle);
            deleteArgsFile(argsFile);
            releaseWhenExited(taskId, process);
        }
    }

    /** Tracks a started child where cancel and shutdown find it; once shut down, refuses it instead. */
    private void track(SubprocessHandle handle) {
        synchronized (lifecycleLock) {
            if (shutDown) {
                throw new IllegalStateException(
                        "Model init launcher shut down while " + handle.taskId + " was starting");
            }
            activeProcesses.put(handle.taskId, handle);
        }
    }

    /** Opens the child's subprocess log. Optional: without it the init runs unlogged. */
    private SubprocessLogWriter openLog(SubprocessHandle handle, ProcessBuilder pb, List<String> command) {
        try {
            String workingDir = pb.directory() != null
                    ? pb.directory().getAbsolutePath()
                    : System.getProperty("user.dir");
            SubprocessLogWriter logWriter = new SubprocessLogWriter("model-init", handle.taskId, workingDir);
            handle.logWriter = logWriter;
            logWriter.writeStart(new SubprocessLogWriter.SubprocessRunContext(
                    handle.taskId, command, workingDir, handle.process.pid(), heapSize));
            return logWriter;
        } catch (Exception _logEx) {
            logger.debug("SubprocessLogWriter init failed (non-fatal): {}", _logEx.getMessage());
            return null;
        }
    }

    /**
     * Stream the child's protocol messages and logs, wait for it to exit, and turn its exit into a result.
     * The first verdict settles the init and is reported once: the child's own COMPLETED or FAILED, else the
     * launcher's, from the timeout or the exit code.
     *
     * @param wrapped whether the child was started with a protocol channel of its own
     *                ({@link SubprocessProtocolChannel#apply})
     */
    private ModelInitResult awaitModelInit(
            SubprocessHandle handle,
            SubprocessLogWriter logWriter,
            boolean wrapped,
            Consumer<ModelInitMessage.Progress> progressListener,
            Consumer<ModelInitMessage.Completed> completionListener,
            Consumer<ModelInitMessage.Failed> failureListener) throws Exception {

        String taskId = handle.taskId;
        String modelId = handle.modelId;
        Process process = handle.process;

        // Result holder
        CompletableFuture<ModelInitResult> resultFuture = new CompletableFuture<>();

        // Start stdout reader thread (for protocol messages)
        Thread stdoutThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    processStdoutLine(line, taskId, modelId, progressListener, completionListener,
                            failureListener, resultFuture);
                    if (logWriter != null) {
                        try {
                            logWriter.writeLine(AgentLogRecord.Stream.STDOUT, line);
                        } catch (Exception _logEx) {
                            logger.debug("SubprocessLogWriter stdout write failed: {}", _logEx.getMessage());
                        }
                    }
                }
            } catch (IOException e) {
                if (!handle.isCancelled()) {
                    logger.warn("Error reading subprocess stdout", e);
                }
            }
        }, "model-init-stdout-" + taskId);
        stdoutThread.setDaemon(true);
        stdoutThread.start();

        // Start stderr reader thread (for logging). A wrapped child's fd 1 is this pipe too, so it carries the
        // native output that reaches fd 1, and the protocol messages of a child that writes them there.
        SubprocessProtocolChannel.StderrProtocol stderrProtocol = SubprocessProtocolChannel.stderrProtocol(
                wrapped, ModelInitMessage.MESSAGE_PREFIX, "model-init-" + taskId);
        Thread stderrThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int prefixAt = stderrProtocol.prefixIndex(line);
                    if (prefixAt > 0) {
                        logger.info("[subprocess:{}] {}", modelId, line.substring(0, prefixAt));
                    }
                    if (prefixAt >= 0) {
                        handleMessage(line.substring(prefixAt + ModelInitMessage.MESSAGE_PREFIX.length()), line,
                                taskId, modelId, progressListener, completionListener, failureListener, resultFuture);
                    } else {
                        logger.info("[subprocess:{}] {}", modelId, line);
                    }
                    if (logWriter != null) {
                        try {
                            logWriter.writeLine(AgentLogRecord.Stream.STDERR, line);
                        } catch (Exception _logEx) {
                            logger.debug("SubprocessLogWriter stderr write failed: {}", _logEx.getMessage());
                        }
                    }
                }
            } catch (IOException e) {
                if (!handle.isCancelled()) {
                    logger.debug("Error reading subprocess stderr", e);
                }
            }
        }, "model-init-stderr-" + taskId);
        stderrThread.setDaemon(true);
        stderrThread.start();

        // Wait for process with timeout
        boolean exited = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        if (!exited) {
            logger.error("Model init subprocess timed out after {} minutes", timeoutMinutes);
            SubprocessSignals.kill(process);
        }
        // A COMPLETED or FAILED the child wrote before it ended is its own verdict: let the readers pass it on
        awaitOutputReaders(taskId, stdoutThread, stderrThread);

        if (!exited) {
            String timeout = "Timeout after " + timeoutMinutes + " minutes";
            settleFailed(resultFuture, taskId, modelId, ModelInitMessage.Phase.CREATING_ENCODER, timeout,
                    "TimeoutException", true, failureListener);
            endLog(logWriter, "TIMEOUT", null, timeout);
        } else {
            int exitCode = process.exitValue();
            logger.info("Subprocess exited with code: {}", exitCode);
            if (exitCode == 0) {
                endLog(logWriter, "SUCCESS", exitCode, null);
                if (!resultFuture.isDone()) {
                    // Exit 0 without a COMPLETED message still counts as success; one read late still reports
                    return new ModelInitResult(taskId, modelId, true, null);
                }
            } else {
                String exit = "Subprocess exited with code " + exitCode;
                // Exit 2 (retriable error) and 137 (killed) may succeed on retry; a cancelled init is not retried
                boolean retriable = !handle.isCancelled() && (exitCode == 2 || exitCode == 137);
                settleFailed(resultFuture, taskId, modelId, ModelInitMessage.Phase.FAILED, exit,
                        "SubprocessExit", retriable, failureListener);
                endLog(logWriter, "FAILED", exitCode, exit);
            }
        }

        try {
            return resultFuture.join();
        } catch (CompletionException e) {
            throw new ReportedFailure(e.getCause().getMessage());
        }
    }

    /**
     * Wait for the output readers to reach the end of the child's pipes. Bounded: a grandchild that
     * inherited the pipes keeps them open after the child exits.
     */
    private static void awaitOutputReaders(String taskId, Thread... outputReaders) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(OUTPUT_DRAIN_SECONDS);
        for (Thread reader : outputReaders) {
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remainingMs > 0) {
                reader.join(remainingMs);
            }
            if (reader.isAlive()) {
                logger.warn("[model-init-{}] {} still reading {}s after the child ended; judging the init",
                        taskId, reader.getName(), OUTPUT_DRAIN_SECONDS);
            }
        }
    }

    /**
     * Settles the init as failed with the launcher's own verdict and reports it — unless a verdict already
     * settled it, which then stands and was reported instead.
     */
    private void settleFailed(CompletableFuture<ModelInitResult> resultFuture, String taskId, String modelId,
                              ModelInitMessage.Phase phase, String errorMessage, String errorType,
                              boolean retriable, Consumer<ModelInitMessage.Failed> failureListener) {
        if (!resultFuture.completeExceptionally(new RuntimeException(errorMessage))) {
            return;
        }
        currentStatus = ModelInitStatus.failed(taskId, modelId, phase, errorMessage, retriable);
        if (failureListener != null) {
            failureListener.accept(ModelInitMessage.failed(taskId, modelId, phase, errorMessage, errorType,
                    null, retriable));
        }
    }

    /** Writes the run's end record to the child's subprocess log, if it has one, and closes it. */
    private static void endLog(SubprocessLogWriter logWriter, String status, Integer exitCode, String error) {
        if (logWriter == null) {
            return;
        }
        try {
            logWriter.writeEnd(new SubprocessLogWriter.SubprocessRunResult(status, exitCode, error, false, false));
        } catch (Exception _logEx) {
            logger.debug("SubprocessLogWriter writeEnd ({}) failed: {}", status, _logEx.getMessage());
        } finally {
            logWriter.close();
        }
    }

    /**
     * The placement a model init runs on. A CPU placement, or a GPU placement for a task whose row is
     * already held (the scheduler acquired it), is used as given. Otherwise the launcher acquires the
     * task's own row and places the child on that device. With no GPU to acquire (none present, or the
     * lifecycle manager not running) the init runs on CPU — a child is never pinned to a GPU without a
     * row. A GPU that has no room right now fails the init instead: the optimization cache it writes is
     * fingerprinted with the backend, so a CPU run could never satisfy a GPU parent, and the encoder
     * optimizes itself when it is first loaded.
     *
     * @throws GpuShortfallException if a GPU exists but has no room for the init right now
     */
    private SubprocessPlacement resolveTaskPlacement(String taskId, SubprocessPlacement requested) {
        if (modelLifecycleManager == null
                || (requested != null && requested.backend() == BackendPreference.CPU)) {
            return requested;
        }
        long capBytes = JobResourceProfiles.MODEL_INIT.peakGpuMemoryBytes();
        ModelLifecycleManager.JobGpuHold held = modelLifecycleManager.getActiveJobHolds().get(taskId);
        if (held != null) {
            return requested != null && requested.isGpu() ? requested : placementOn(held.device(), capBytes);
        }
        if (requested != null) {
            logger.warn("[model-init-{}] GPU placement {} has no GPU row; acquiring one for the task", taskId, requested);
        }
        try {
            GpuDevice device = modelLifecycleManager.acquireGpuForJob(taskId,
                    JobResourceProfiles.MODEL_INIT.serviceType(), "Model init: " + taskId,
                    ModelLifecycleManager.HoldLifetime.BOUNDED, capBytes, null);
            launcherGpuHolds.add(taskId);
            logger.info("[model-init-{}] GPU row acquired for model init on {}", taskId, device.name());
            return placementOn(device, capBytes);
        } catch (GpuShortfallException e) {
            throw e;
        } catch (IllegalStateException e) {
            logger.warn("[model-init-{}] No GPU to acquire for model init, running on CPU: {}",
                    taskId, e.getMessage());
            return SubprocessPlacement.cpu();
        }
    }

    /** The child's placement on a reserved device — derived the same way the scheduler derives it. */
    private static SubprocessPlacement placementOn(GpuDevice device, long capBytes) {
        return SubprocessPlacement.gpu(device.cudaRuntimeIndex(),
                ModelLifecycleManager.clampToDevice(capBytes, device));
    }

    /**
     * Process a line from subprocess stdout.
     */
    private void processStdoutLine(
            String line, String taskId, String modelId,
            Consumer<ModelInitMessage.Progress> progressListener,
            Consumer<ModelInitMessage.Completed> completionListener,
            Consumer<ModelInitMessage.Failed> failureListener,
            CompletableFuture<ModelInitResult> resultFuture) {

        // Check for protocol message prefix. A child started without the protocol channel shares this pipe with
        // libnd4j, which logs with printf straight to fd 1, beneath the child's System.setOut redirect, so a
        // native message without a newline can precede one on the same line (the run log keeps the whole line).
        int prefixAt = line.indexOf(ModelInitMessage.MESSAGE_PREFIX);
        if (prefixAt < 0) {
            // Not a protocol message - just log it
            logger.trace("[subprocess:{}] {}", modelId, line);
            return;
        }

        handleMessage(line.substring(prefixAt + ModelInitMessage.MESSAGE_PREFIX.length()), line, taskId, modelId,
                progressListener, completionListener, failureListener, resultFuture);
    }

    /**
     * Dispatch one protocol message: {@code json}, the text after the prefix on {@code line}. A message that
     * does not parse is logged and skipped.
     */
    private void handleMessage(
            String json, String line, String taskId, String modelId,
            Consumer<ModelInitMessage.Progress> progressListener,
            Consumer<ModelInitMessage.Completed> completionListener,
            Consumer<ModelInitMessage.Failed> failureListener,
            CompletableFuture<ModelInitResult> resultFuture) {
        try {
            ModelInitMessage message = OBJECT_MAPPER.readValue(json, ModelInitMessage.class);

            ModelInitMessage.dispatch(message, new ModelInitMessage.Handler() {
                @Override
                public void onProgress(ModelInitMessage.Progress progress) {
                    logger.debug("[{}] Progress: {} - {} ({}%)",
                            modelId, progress.phase(), progress.message(), progress.progressPercent());
                    currentStatus = ModelInitStatus.inProgress(taskId, modelId,
                            progress.phase(), progress.progressPercent(), progress.message());
                    if (progressListener != null) {
                        progressListener.accept(progress);
                    }
                }

                @Override
                public void onPhaseTransition(ModelInitMessage.PhaseTransition transition) {
                    logger.info("[{}] Phase: {} -> {} ({}ms)",
                            modelId, transition.fromPhase(), transition.toPhase(), transition.phaseDurationMs());
                    currentStatus = ModelInitStatus.inProgress(taskId, modelId,
                            transition.toPhase(), -1, transition.toPhase().getDescription());
                }

                @Override
                public void onHeartbeat(ModelInitMessage.Heartbeat heartbeat) {
                    logger.trace("[{}] Heartbeat: uptime={}ms, memory={}%",
                            modelId, heartbeat.uptimeMs(), String.format("%.1f", heartbeat.memoryUsagePercent()));
                }

                @Override
                public void onCompleted(ModelInitMessage.Completed completed) {
                    logger.info("[{}] COMPLETED: dims={}, type={}, time={}ms",
                            modelId, completed.embeddingDimensions(),
                            completed.encoderType(), completed.totalDurationMs());
                    // Reported only as the init's verdict: one that already settled it stands
                    if (!resultFuture.complete(new ModelInitResult(taskId, modelId, true, completed))) {
                        return;
                    }
                    currentStatus = ModelInitStatus.completed(taskId, modelId,
                            completed.embeddingDimensions(), completed.encoderType());
                    if (completionListener != null) {
                        completionListener.accept(completed);
                    }
                }

                @Override
                public void onFailed(ModelInitMessage.Failed failed) {
                    logger.error("[{}] FAILED in phase {}: {} (retriable={})",
                            modelId, failed.phase(), failed.errorMessage(), failed.retriable());
                    // Reported only as the init's verdict: one that already settled it stands
                    if (!resultFuture.completeExceptionally(
                            new RuntimeException("Model init failed: " + failed.errorMessage()))) {
                        return;
                    }
                    currentStatus = ModelInitStatus.failed(taskId, modelId,
                            failed.phase(), failed.errorMessage(), failed.retriable());
                    if (failureListener != null) {
                        failureListener.accept(failed);
                    }
                }

                @Override
                public void onLog(ModelInitMessage.Log logMsg) {
                    // Already logged by subprocess stderr, but could forward to UI
                    logger.trace("[{}] Log: [{}] {}", modelId, logMsg.level(), logMsg.message());
                }

                @Override
                public void onModelInfo(ModelInitMessage.ModelInfo info) {
                    logger.info("[{}] Model info: type={}, dims={}, maxSeq={}",
                            modelId, info.modelType(), info.embeddingDimensions(), info.maxSequenceLength());
                }
            });

        } catch (Exception e) {
            logger.warn("Failed to parse model init message: {}", e.getMessage());
            logger.debug("Raw line: {}", line);
        }
    }

    /**
     * Build the command to launch the subprocess.
     */
    private List<String> buildCommand(Path argsFile, SubprocessPlacement taskPlacement) {
        // Check if we should use native executable mode
        if (shouldUseNativeExecutableMode()) {
            return buildNativeCommand(argsFile, taskPlacement);
        }

        // JVM classpath mode
        return buildJvmCommand(argsFile, taskPlacement);
    }

    /**
     * Check if native executable mode should be used.
     * Uses SubprocessConfigService (UI-configured) for the decision.
     */
    private boolean shouldUseNativeExecutableMode() {
        // Use SubprocessConfigService (UI-managed) for the decision
        if (subprocessConfigService != null) {
            return subprocessConfigService.shouldUseNativeExecutableMode();
        }

        // Fallback: If running in native image and no classpath available, native mode is required
        if (NativeImageInfo.isRunningInNativeImage() && !NativeImageInfo.hasClasspath()) {
            return true;
        }

        return false;
    }

    /**
     * Build command for native executable mode.
     * Uses SubprocessConfigService (UI-configured) for executable paths.
     */
    private List<String> buildNativeCommand(Path argsFile, SubprocessPlacement taskPlacement) {
        if (subprocessConfigService == null) {
            throw new IllegalStateException(
                "Native executable mode required but SubprocessConfigService not available.");
        }

        String executablePath = subprocessConfigService.getExecutablePathForType("model-init");
        if (executablePath == null || executablePath.isBlank()) {
            throw new IllegalStateException(
                "Native executable mode required but no executable path configured. " +
                "Configure the native executable path in Processing Settings (Developer Hub).");
        }

        List<String> command = new ArrayList<>();
        command.add(executablePath);

        // Device-agnostic backend/device selection, before the dispatch token (as in the JVM command)
        command.addAll(SubprocessBackendFlags.jvmFlags(taskPlacement, BackendPreference.INHERIT));

        // Add subprocess type flag if using unified executable
        if (subprocessConfigService.useUnifiedExecutable("model-init")) {
            command.add(subprocessConfigService.getSubprocessTypeFlag() + "model-init");
        }

        // Add args file
        command.add(argsFile.toString());

        logger.info("Using native executable mode for model init subprocess: {}", executablePath);
        return command;
    }

    /**
     * Build command for JVM classpath mode.
     */
    private List<String> buildJvmCommand(Path argsFile, SubprocessPlacement taskPlacement) {
        List<String> command = new ArrayList<>();
        command.add(javaPath);

        // Memory settings
        command.add("-Xmx" + heapSize);
        command.add("-Xms" + Math.min(parseHeapSize(heapSize) / 2, 1024) + "m");

        // GC settings for model loading
        command.add("-XX:+UseG1GC");
        command.add("-XX:MaxGCPauseMillis=200");

        // ND4J settings
        command.add("-Dorg.bytedeco.javacpp.pathsFirst=true");
        command.add("-Dorg.bytedeco.javacpp.logger.debug=false");

        // Device-agnostic backend/device selection from the shared base infra — no CUDA_VISIBLE_DEVICES.
        command.addAll(SubprocessBackendFlags.jvmFlags(taskPlacement, BackendPreference.INHERIT));

        // Classpath - expand Spring Boot BOOT-INF entries when running from an exec jar.
        String classpath = SubprocessClasspathBuilder.buildClasspath();
        command.add("-cp");
        command.add(classpath);

        // Main class
        command.add(ModelInitSubprocessMain.class.getName());

        // Args file
        command.add(argsFile.toString());

        return command;
    }

    /**
     * Parse heap size string to MB.
     */
    private int parseHeapSize(String heapSize) {
        String lower = heapSize.toLowerCase();
        try {
            if (lower.endsWith("g")) {
                return Integer.parseInt(lower.substring(0, lower.length() - 1)) * 1024;
            } else if (lower.endsWith("m")) {
                return Integer.parseInt(lower.substring(0, lower.length() - 1));
            } else {
                return Integer.parseInt(lower);
            }
        } catch (NumberFormatException e) {
            return 4096; // Default 4GB
        }
    }

    /**
     * Get the current model initialization status.
     */
    public ModelInitStatus getCurrentStatus() {
        return currentStatus;
    }

    /**
     * Check if a model initialization is currently running.
     */
    public boolean isInitializationRunning() {
        return currentStatus.status() == ModelInitStatus.Status.IN_PROGRESS ||
                currentStatus.status() == ModelInitStatus.Status.STARTING;
    }

    /**
     * Cancel a running model initialization.
     */
    public boolean cancelInitialization(String taskId) {
        SubprocessHandle handle = activeProcesses.get(taskId);
        if (handle == null) {
            return false;
        }

        logger.info("Cancelling model init subprocess: {}", taskId);
        handle.cancel();
        activeProcesses.remove(taskId, handle);
        currentStatus = ModelInitStatus.idle();
        return true;
    }

    @PreDestroy
    public void cleanup() {
        logger.info("Shutting down model init subprocess launcher");

        // Cancel all active processes; a launch still starting its child kills it rather than track it
        List<SubprocessHandle> handles;
        synchronized (lifecycleLock) {
            shutDown = true;
            handles = List.copyOf(activeProcesses.values());
            activeProcesses.clear();
        }
        for (SubprocessHandle handle : handles) {
            try {
                handle.cancel();
            } catch (Exception e) {
                logger.warn("Error cancelling subprocess: {}", e.getMessage());
            }
            // Release only the GPU rows this launcher acquired — the scheduler releases its own — once
            // each child is gone
            handle.process.onExit().thenRun(() -> releaseModelInitGpu(handle.taskId));
        }
    }

    /**
     * Release the task's GPU row once its killed or exited child is gone: now if it exits within
     * {@link #CHILD_EXIT_WAIT_SECONDS}, otherwise when it does.
     */
    private void releaseWhenExited(String taskId, Process process) {
        if (awaitExit(process)) {
            releaseModelInitGpu(taskId);
        } else {
            logger.warn("[model-init-{}] Child still running {}s after being killed; its GPU row is released when it exits",
                    taskId, CHILD_EXIT_WAIT_SECONDS);
            process.onExit().thenRun(() -> releaseModelInitGpu(taskId));
        }
    }

    private static boolean awaitExit(Process process) {
        try {
            return process.waitFor(CHILD_EXIT_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return !process.isAlive();
        }
    }

    /** Args files carry credentials (the staging API key), so every path deletes them. */
    private static void deleteArgsFile(Path argsFile) {
        if (argsFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(argsFile);
        } catch (IOException e) {
            logger.warn("Could not delete model init args file {}: {}", argsFile, e.getMessage());
        }
    }

    /**
     * Release the task's GPU row if this launcher acquired it — a row the scheduler holds is the
     * scheduler's to release. Idempotent: the launcher's row is released exactly once.
     */
    private void releaseModelInitGpu(String taskId) {
        if (launcherGpuHolds.remove(taskId) && modelLifecycleManager != null) {
            logger.info("[model-init-{}] Releasing GPU resources for finished model init", taskId);
            try {
                modelLifecycleManager.releaseGpuForModelInit(taskId);
            } catch (Exception e) {
                logger.warn("[model-init-{}] Error releasing GPU resources: {}", taskId, e.getMessage());
            }
        }
    }

    /** A failed init whose status and failure listener already carry its verdict: nothing reports it again. */
    private static final class ReportedFailure extends RuntimeException {
        ReportedFailure(String message) {
            super(message);
        }
    }

    /**
     * Handle for tracking a subprocess.
     */
    private static class SubprocessHandle {
        private final String taskId;
        private final String modelId;
        private final Process process;
        private final Path argsFile;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        volatile SubprocessLogWriter logWriter;

        SubprocessHandle(String taskId, String modelId, Process process, Path argsFile) {
            this.taskId = taskId;
            this.modelId = modelId;
            this.process = process;
            this.argsFile = argsFile;
        }

        void cancel() {
            cancelled.set(true);
            if (process.isAlive()) {
                SubprocessSignals.kill(process);
            }
            deleteArgsFile(argsFile);
            SubprocessLogWriter lw = logWriter;
            if (lw != null) {
                try {
                    lw.writeEnd(new SubprocessLogWriter.SubprocessRunResult(
                            "CANCELLED", null, "Subprocess cancelled", false, false));
                } catch (Exception e) {
                    logger.warn("Failed to write cancellation end record to subprocess log: {}", e.getMessage());
                }
                lw.close();
            }
        }

        boolean isCancelled() {
            return cancelled.get();
        }
    }

    /**
     * Result of model initialization.
     */
    public record ModelInitResult(
            String taskId,
            String modelId,
            boolean success,
            ModelInitMessage.Completed completedMessage
    ) {}

    /**
     * Current status of model initialization.
     */
    public record ModelInitStatus(
            Status status,
            String taskId,
            String modelId,
            ModelInitMessage.Phase phase,
            int progressPercent,
            String message,
            Integer embeddingDimensions,
            String encoderType,
            String errorMessage,
            boolean errorRetriable
    ) {
        public enum Status {
            IDLE,
            STARTING,
            IN_PROGRESS,
            COMPLETED,
            FAILED
        }

        public static ModelInitStatus idle() {
            return new ModelInitStatus(Status.IDLE, null, null, null, 0, "Idle", null, null, null, false);
        }

        public static ModelInitStatus starting(String taskId, String modelId) {
            return new ModelInitStatus(Status.STARTING, taskId, modelId, ModelInitMessage.Phase.STARTING,
                    0, "Starting model initialization...", null, null, null, false);
        }

        public static ModelInitStatus inProgress(String taskId, String modelId, ModelInitMessage.Phase phase,
                                                 int percent, String message) {
            return new ModelInitStatus(Status.IN_PROGRESS, taskId, modelId, phase, percent, message,
                    null, null, null, false);
        }

        public static ModelInitStatus completed(String taskId, String modelId, int dimensions, String encoderType) {
            return new ModelInitStatus(Status.COMPLETED, taskId, modelId, ModelInitMessage.Phase.COMPLETE,
                    100, "Model ready", dimensions, encoderType, null, false);
        }

        public static ModelInitStatus failed(String taskId, String modelId, ModelInitMessage.Phase phase,
                                             String errorMessage, boolean retriable) {
            return new ModelInitStatus(Status.FAILED, taskId, modelId,
                    phase != null ? phase : ModelInitMessage.Phase.FAILED,
                    0, errorMessage, null, null, errorMessage, retriable);
        }
    }
}
