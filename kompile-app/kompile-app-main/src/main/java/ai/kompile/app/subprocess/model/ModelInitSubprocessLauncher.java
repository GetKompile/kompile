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
import ai.kompile.app.config.Nd4jEnvironmentConfig;
import ai.kompile.app.config.SubprocessExecutableConfig;
import ai.kompile.app.services.DeviceRoutingConfigService;
import ai.kompile.app.services.ModelLifecycleManager;
import ai.kompile.app.services.subprocess.SubprocessConfigService;
import ai.kompile.cli.main.util.NativeImageInfo;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
public class ModelInitSubprocessLauncher {

    private static final Logger logger = LoggerFactory.getLogger(ModelInitSubprocessLauncher.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Configuration
    @Value("${kompile.model-init.subprocess.java-path:java}")
    private String javaPath;

    @Value("${kompile.model-init.subprocess.heap-size:4g}")
    private String heapSize;

    @Value("${kompile.model-init.subprocess.timeout-minutes:10}")
    private int timeoutMinutes;

    @Value("${kompile.model-init.subprocess.heartbeat-timeout-seconds:30}")
    private int heartbeatTimeoutSeconds;

    // Native image configuration
    @Autowired(required = false)
    private SubprocessExecutableConfig subprocessExecutableConfig;

    @Autowired(required = false)
    private SubprocessConfigService subprocessConfigService;

    @Autowired(required = false)
    private DeviceRoutingConfigService deviceRoutingConfigService;

    @Autowired(required = false)
    private ModelLifecycleManager modelLifecycleManager;

    // Active processes
    private final Map<String, SubprocessHandle> activeProcesses = new ConcurrentHashMap<>();

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
            args = ModelInitSubprocessArgs.builder()
                    .taskId(taskId)
                    .modelIdentifier(args.modelIdentifier())
                    .modelSourceType(args.modelSourceType())
                    .stagingUrl(args.stagingUrl())
                    .stagingApiKey(args.stagingApiKey())
                    .archivePath(args.archivePath())
                    .optimalBatchSize(args.optimalBatchSize())
                    .maxBatchSize(args.maxBatchSize())
                    .nd4jConfigJson(args.nd4jConfigJson())
                    .callbackBaseUrl(args.callbackBaseUrl())
                    .memoryThresholdPercent(args.memoryThresholdPercent())
                    .memoryCriticalPercent(args.memoryCriticalPercent())
                    .memoryKillThresholdPercent(args.memoryKillThresholdPercent())
                    .memoryCheckIntervalMs(args.memoryCheckIntervalMs())
                    .gpuMemoryThresholdPercent(args.gpuMemoryThresholdPercent())
                    .gpuMemoryCriticalPercent(args.gpuMemoryCriticalPercent())
                    .gpuMemoryKillThresholdPercent(args.gpuMemoryKillThresholdPercent())
                    .offHeapThresholdPercent(args.offHeapThresholdPercent())
                    .offHeapCriticalPercent(args.offHeapCriticalPercent())
                    .offHeapKillThresholdPercent(args.offHeapKillThresholdPercent())
                    .skipValidation(args.skipValidation())
                    .validationTestText(args.validationTestText())
                    .options(args.options())
                    .build();
        }

        // Apply device routing overlay for modelInit service if enabled
        if (deviceRoutingConfigService != null && deviceRoutingConfigService.isEnabled()) {
            try {
                Nd4jEnvironmentConfig routedConfig = deviceRoutingConfigService
                        .resolveNd4jConfigForService(DeviceRoutingConfig.SERVICE_MODEL_INIT);
                String routedJson = OBJECT_MAPPER.writeValueAsString(routedConfig);
                logger.info("Using device-routed ND4J config for modelInit: maxThreads={}, cudaDevice={}",
                        routedConfig.maxThreads(), routedConfig.cudaCurrentDevice());
                args = ModelInitSubprocessArgs.builder()
                        .taskId(taskId)
                        .modelIdentifier(args.modelIdentifier())
                        .modelSourceType(args.modelSourceType())
                        .stagingUrl(args.stagingUrl())
                        .stagingApiKey(args.stagingApiKey())
                        .archivePath(args.archivePath())
                        .optimalBatchSize(args.optimalBatchSize())
                        .maxBatchSize(args.maxBatchSize())
                        .nd4jConfigJson(routedJson)
                        .callbackBaseUrl(args.callbackBaseUrl())
                        .memoryThresholdPercent(args.memoryThresholdPercent())
                        .memoryCriticalPercent(args.memoryCriticalPercent())
                        .memoryKillThresholdPercent(args.memoryKillThresholdPercent())
                        .memoryCheckIntervalMs(args.memoryCheckIntervalMs())
                        .gpuMemoryThresholdPercent(args.gpuMemoryThresholdPercent())
                        .gpuMemoryCriticalPercent(args.gpuMemoryCriticalPercent())
                        .gpuMemoryKillThresholdPercent(args.gpuMemoryKillThresholdPercent())
                        .skipValidation(args.skipValidation())
                        .validationTestText(args.validationTestText())
                        .options(args.options())
                        .build();
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
            } catch (Exception e) {
                logger.error("Failed to launch model init subprocess", e);
                currentStatus = ModelInitStatus.failed(finalTaskId, finalArgs.modelIdentifier(),
                        ModelInitMessage.Phase.STARTING, e.getMessage(), false);

                if (failureListener != null) {
                    failureListener.accept(ModelInitMessage.failed(finalTaskId, finalArgs.modelIdentifier(),
                            ModelInitMessage.Phase.STARTING, e, false));
                }
                throw new RuntimeException("Model init subprocess failed", e);
            }
        });
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

        // Write args to temp file
        Path argsFile = args.writeToTempFile();
        logger.debug("Args file created: {}", argsFile);

        // Build command
        List<String> command = buildCommand(argsFile);
        logger.info("Command: {}", String.join(" ", command));

        // Start process
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false); // Keep stderr separate for logging

        // === GPU LIFECYCLE: Acquire GPU resources for this model init job ===
        boolean gpuAcquired = false;
        if (modelLifecycleManager != null) {
            try {
                modelLifecycleManager.acquireGpuForModelInit(taskId);
                gpuAcquired = true;
                logger.info("[model-init-{}] GPU resources acquired for model init", taskId);
            } catch (IllegalStateException e) {
                logger.warn("[model-init-{}] Could not acquire GPU for model init (may use CPU fallback): {}",
                        taskId, e.getMessage());
            }
        }

        Process process = pb.start();

        // Create handle
        SubprocessHandle handle = new SubprocessHandle(taskId, modelId, process, argsFile);
        activeProcesses.put(taskId, handle);

        // Result holder
        CompletableFuture<ModelInitResult> resultFuture = new CompletableFuture<>();

        // Start stdout reader thread (for protocol messages)
        Thread stdoutThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    processStdoutLine(line, taskId, modelId, progressListener, completionListener,
                            failureListener, resultFuture);
                }
            } catch (IOException e) {
                if (!handle.isCancelled()) {
                    logger.warn("Error reading subprocess stdout", e);
                }
            }
        }, "model-init-stdout-" + taskId);
        stdoutThread.setDaemon(true);
        stdoutThread.start();

        // Start stderr reader thread (for logging)
        Thread stderrThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.info("[subprocess:{}] {}", modelId, line);
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
        boolean completed = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);

        if (!completed) {
            logger.error("Model init subprocess timed out after {} minutes", timeoutMinutes);
            process.destroyForcibly();
            currentStatus = ModelInitStatus.failed(taskId, modelId, ModelInitMessage.Phase.CREATING_ENCODER,
                    "Timeout after " + timeoutMinutes + " minutes", true);

            // Release GPU on timeout
            releaseModelInitGpu(taskId, gpuAcquired);

            if (failureListener != null) {
                failureListener.accept(ModelInitMessage.failed(taskId, modelId, ModelInitMessage.Phase.CREATING_ENCODER,
                        "Timeout", "TimeoutException", null, true));
            }

            throw new RuntimeException("Model init subprocess timed out");
        }

        int exitCode = process.exitValue();
        logger.info("Subprocess exited with code: {}", exitCode);

        // Cleanup
        activeProcesses.remove(taskId);
        try {
            java.nio.file.Files.deleteIfExists(argsFile);
        } catch (IOException e) {
            logger.debug("Could not delete args file: {}", argsFile);
        }

        // === GPU LIFECYCLE: Release GPU resources after model init completes ===
        releaseModelInitGpu(taskId, gpuAcquired);

        // Wait for result from parsed messages
        if (exitCode == 0) {
            // Success - result should be set by stdout parser
            try {
                return resultFuture.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                // If we didn't get a result message but exit was 0, consider it success
                return new ModelInitResult(taskId, modelId, true, null);
            }
        } else {
            // Failure
            boolean retriable = exitCode == 2 || exitCode == 137; // Retriable errors
            currentStatus = ModelInitStatus.failed(taskId, modelId, null,
                    "Subprocess exited with code " + exitCode, retriable);
            throw new RuntimeException("Subprocess failed with exit code: " + exitCode);
        }
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

        // Check for protocol message prefix
        if (!line.startsWith(ModelInitMessage.MESSAGE_PREFIX)) {
            // Not a protocol message - just log it
            logger.trace("[subprocess:{}] {}", modelId, line);
            return;
        }

        String json = line.substring(ModelInitMessage.MESSAGE_PREFIX.length());

        try {
            ModelInitMessage message = OBJECT_MAPPER.readValue(json, ModelInitMessage.class);

            if (message instanceof ModelInitMessage.Progress progress) {
                logger.debug("[{}] Progress: {} - {} ({}%)",
                        modelId, progress.phase(), progress.message(), progress.progressPercent());

                currentStatus = ModelInitStatus.inProgress(taskId, modelId,
                        progress.phase(), progress.progressPercent(), progress.message());

                if (progressListener != null) {
                    progressListener.accept(progress);
                }
            } else if (message instanceof ModelInitMessage.PhaseTransition transition) {
                logger.info("[{}] Phase: {} -> {} ({}ms)",
                        modelId, transition.fromPhase(), transition.toPhase(), transition.phaseDurationMs());

                currentStatus = ModelInitStatus.inProgress(taskId, modelId,
                        transition.toPhase(), -1, transition.toPhase().getDescription());
            } else if (message instanceof ModelInitMessage.Heartbeat heartbeat) {
                logger.trace("[{}] Heartbeat: uptime={}ms, memory={}%",
                        modelId, heartbeat.uptimeMs(), String.format("%.1f", heartbeat.memoryUsagePercent()));
            } else if (message instanceof ModelInitMessage.Completed completed) {
                logger.info("[{}] COMPLETED: dims={}, type={}, time={}ms",
                        modelId, completed.embeddingDimensions(),
                        completed.encoderType(), completed.totalDurationMs());

                currentStatus = ModelInitStatus.completed(taskId, modelId,
                        completed.embeddingDimensions(), completed.encoderType());

                if (completionListener != null) {
                    completionListener.accept(completed);
                }

                resultFuture.complete(new ModelInitResult(taskId, modelId, true, completed));
            } else if (message instanceof ModelInitMessage.Failed failed) {
                logger.error("[{}] FAILED in phase {}: {} (retriable={})",
                        modelId, failed.phase(), failed.errorMessage(), failed.retriable());

                currentStatus = ModelInitStatus.failed(taskId, modelId,
                        failed.phase(), failed.errorMessage(), failed.retriable());

                if (failureListener != null) {
                    failureListener.accept(failed);
                }

                resultFuture.completeExceptionally(
                        new RuntimeException("Model init failed: " + failed.errorMessage()));
            } else if (message instanceof ModelInitMessage.Log logMsg) {
                // Already logged by subprocess stderr, but could forward to UI
                logger.trace("[{}] Log: [{}] {}", modelId, logMsg.level(), logMsg.message());
            } else if (message instanceof ModelInitMessage.ModelInfo info) {
                logger.info("[{}] Model info: type={}, dims={}, maxSeq={}",
                        modelId, info.modelType(), info.embeddingDimensions(), info.maxSequenceLength());
            }

        } catch (Exception e) {
            logger.warn("Failed to parse model init message: {}", e.getMessage());
            logger.debug("Raw line: {}", line);
        }
    }

    /**
     * Build the command to launch the subprocess.
     */
    private List<String> buildCommand(Path argsFile) {
        // Check if we should use native executable mode
        if (shouldUseNativeExecutableMode()) {
            return buildNativeCommand(argsFile);
        }

        // JVM classpath mode
        return buildJvmCommand(argsFile);
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
    private List<String> buildNativeCommand(Path argsFile) {
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
    private List<String> buildJvmCommand(Path argsFile) {
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

        // Classpath - use the current classpath
        String classpath = System.getProperty("java.class.path");
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
        activeProcesses.remove(taskId);
        currentStatus = ModelInitStatus.idle();
        return true;
    }

    @PreDestroy
    public void cleanup() {
        logger.info("Shutting down model init subprocess launcher");

        // Cancel all active processes and release GPU holds
        for (SubprocessHandle handle : activeProcesses.values()) {
            try {
                handle.cancel();
                // Release GPU hold for this job if held
                if (modelLifecycleManager != null && modelLifecycleManager.hasJobGpuHold(handle.taskId)) {
                    logger.info("[model-init-{}] Releasing GPU resources during shutdown", handle.taskId);
                    try {
                        modelLifecycleManager.releaseGpuForModelInit(handle.taskId);
                    } catch (Exception e) {
                        logger.warn("[model-init-{}] Error releasing GPU during shutdown: {}",
                                handle.taskId, e.getMessage());
                    }
                }
            } catch (Exception e) {
                logger.warn("Error cancelling subprocess: {}", e.getMessage());
            }
        }
        activeProcesses.clear();
    }

    /**
     * Release GPU resources for a model init job.
     */
    private void releaseModelInitGpu(String taskId, boolean gpuAcquired) {
        if (gpuAcquired && modelLifecycleManager != null && modelLifecycleManager.hasJobGpuHold(taskId)) {
            logger.info("[model-init-{}] Releasing GPU resources for completed/failed model init", taskId);
            try {
                modelLifecycleManager.releaseGpuForModelInit(taskId);
            } catch (Exception e) {
                logger.warn("[model-init-{}] Error releasing GPU resources: {}", taskId, e.getMessage());
            }
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

        SubprocessHandle(String taskId, String modelId, Process process, Path argsFile) {
            this.taskId = taskId;
            this.modelId = modelId;
            this.process = process;
            this.argsFile = argsFile;
        }

        void cancel() {
            cancelled.set(true);
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            try {
                java.nio.file.Files.deleteIfExists(argsFile);
            } catch (IOException ignored) {}
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
