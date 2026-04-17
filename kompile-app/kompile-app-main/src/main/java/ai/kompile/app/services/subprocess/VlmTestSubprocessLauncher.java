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

import ai.kompile.app.config.Nd4jEnvironmentConfig;
import ai.kompile.app.config.SubprocessExecutableConfig;
import ai.kompile.app.services.GpuResourceManager;
import ai.kompile.app.services.ModelLifecycleManager;
import ai.kompile.app.services.Nd4jEnvironmentConfigService;
import ai.kompile.app.services.ServerPortService;
import ai.kompile.app.subprocess.SubprocessMessage;
import ai.kompile.app.subprocess.VlmTestSubprocessArgs;
import ai.kompile.cli.common.logs.AgentLogRecord;
import ai.kompile.cli.common.logs.SubprocessLogWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service for launching and managing VLM test subprocesses.
 *
 * Spawns isolated JVM processes to run VLM-only processing (no chunking, embedding, or indexing).
 * Parses INGEST_MSG: JSON messages from subprocess stdout and forwards to WebSocket.
 */
@Service
public class VlmTestSubprocessLauncher {

    private static final Logger logger = LoggerFactory.getLogger(VlmTestSubprocessLauncher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Fallback values if SubprocessConfigService is not available
    @Value("${kompile.vlm-test.subprocess.java-path:java}")
    private String fallbackJavaPath;

    @Value("${kompile.vlm-test.subprocess.heap-size:16g}")
    private String fallbackHeapSize;

    @Value("${kompile.vlm-test.subprocess.timeout-minutes:30}")
    private int fallbackTimeoutMinutes;

    @Value("${kompile.vlm-test.subprocess.off-heap-multiplier:3}")
    private int fallbackOffHeapMultiplier;

    @Value("${kompile.subprocess.vlm.cuda-devices:}")
    private String cudaVisibleDevices;

    private final SubprocessExecutableConfig execConfig;
    private final ServerPortService serverPortService;
    private final SubprocessConfigService subprocessConfigService;
    private final Nd4jEnvironmentConfigService nd4jEnvironmentConfigService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired(required = false)
    private SimpMessagingTemplate messagingTemplate;

    @Autowired(required = false)
    private ModelLifecycleManager modelLifecycleManager;

    private final Map<String, VlmTestHandle> activeTests = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> logSequenceCounters = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "vlm-test-launcher");
        t.setDaemon(true);
        return t;
    });

    public VlmTestSubprocessLauncher(SubprocessExecutableConfig execConfig,
                                      ServerPortService serverPortService,
                                      @Autowired(required = false) SubprocessConfigService subprocessConfigService,
                                      @Autowired(required = false) Nd4jEnvironmentConfigService nd4jEnvironmentConfigService) {
        this.execConfig = execConfig;
        this.serverPortService = serverPortService;
        this.subprocessConfigService = subprocessConfigService;
        this.nd4jEnvironmentConfigService = nd4jEnvironmentConfigService;
    }

    private String getEffectiveJavaPath() {
        if (subprocessConfigService != null) {
            String configured = subprocessConfigService.getJavaPath();
            if (configured != null && !configured.isBlank()) return configured.trim();
        }
        return fallbackJavaPath;
    }

    private String getEffectiveHeapSize() {
        if (subprocessConfigService != null) {
            String configured = subprocessConfigService.getVlmHeapSize();
            if (configured != null && !configured.isBlank()) return configured.trim();
        }
        return fallbackHeapSize;
    }

    private int getEffectiveOffHeapMultiplier() {
        if (subprocessConfigService != null) {
            return subprocessConfigService.getVlmOffHeapMultiplier();
        }
        return fallbackOffHeapMultiplier;
    }

    private int getEffectiveTimeoutMinutes() {
        if (subprocessConfigService != null) {
            return subprocessConfigService.getVlmTimeoutMinutes();
        }
        return fallbackTimeoutMinutes;
    }

    /**
     * Launch a VLM test subprocess.
     *
     * @param taskId Unique task identifier
     * @param filePath Path to uploaded file
     * @param modelId VLM model to use
     * @param outputFormat Output format (DOCTAGS, MARKDOWN, etc.)
     * @param options Additional options (maxNewTokens, temperature, etc.)
     * @return CompletableFuture that completes when subprocess finishes
     */
    public CompletableFuture<VlmTestResult> launchTest(String taskId, String filePath,
                                                         String modelId, String outputFormat,
                                                         Map<String, String> options) {
        CompletableFuture<VlmTestResult> future = new CompletableFuture<>();

        executor.submit(() -> {
            try {
                runTest(taskId, filePath, modelId, outputFormat, options, future);
            } catch (Exception e) {
                logger.error("Failed to launch VLM test subprocess for task {}", taskId, e);
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    private void runTest(String taskId, String filePath, String modelId, String outputFormat,
                          Map<String, String> options, CompletableFuture<VlmTestResult> future) {
        Process process = null;
        boolean gpuAcquired = false;
        try {
            // === GPU LIFECYCLE: Acquire GPU resources, evicting lower-priority services ===
            if (modelLifecycleManager != null) {
                logger.info("[vlm-test-{}] Acquiring GPU resources via ModelLifecycleManager (will evict embedding if needed)", taskId);
                sendWebSocketUpdate(taskId, "RUNNING", 0, "GPU_ACQUIRE", "Acquiring GPU resources...", null);
                try {
                    modelLifecycleManager.acquireGpuForVlm(taskId);
                    gpuAcquired = true;
                    logger.info("[vlm-test-{}] GPU resources acquired successfully", taskId);
                    sendWebSocketUpdate(taskId, "RUNNING", 0, "GPU_ACQUIRE", "GPU resources acquired", null);
                } catch (IllegalStateException e) {
                    logger.error("[vlm-test-{}] Failed to acquire GPU resources: {}", taskId, e.getMessage());
                    sendWebSocketUpdate(taskId, "FAILED", 0, "GPU_ACQUIRE", "Failed to acquire GPU: " + e.getMessage(), null);
                    future.complete(new VlmTestResult(taskId, filePath, "FAILED"));
                    return;
                }
            } else {
                logger.warn("[vlm-test-{}] ModelLifecycleManager not available — launching VLM without GPU coordination", taskId);
            }

            // Capture ND4J config from the live parent process
            String nd4jConfigJson = captureNd4jConfig();

            // Build subprocess args
            VlmTestSubprocessArgs.Builder argsBuilder = VlmTestSubprocessArgs.builder()
                    .taskId(taskId)
                    .filePath(filePath)
                    .modelId(modelId)
                    .outputFormat(outputFormat != null ? outputFormat : "DOCTAGS")
                    .callbackBaseUrl("http://localhost:" + serverPortService.getActualPort() + "/api")
                    .nd4jConfigJson(nd4jConfigJson);

            if (options != null) {
                if (options.containsKey("maxNewTokens")) {
                    argsBuilder.maxNewTokens(Integer.parseInt(options.get("maxNewTokens")));
                }
                if (options.containsKey("temperature")) {
                    argsBuilder.temperature(Double.parseDouble(options.get("temperature")));
                }
                if (options.containsKey("topP")) {
                    argsBuilder.topP(Double.parseDouble(options.get("topP")));
                }
                if (options.containsKey("pdfRenderDpi")) {
                    argsBuilder.pdfRenderDpi(Integer.parseInt(options.get("pdfRenderDpi")));
                }
                if (options.containsKey("pageBatchSize")) {
                    argsBuilder.pageBatchSize(Integer.parseInt(options.get("pageBatchSize")));
                }
                if (options.containsKey("cudaPinnedHostLimitMb")) {
                    argsBuilder.cudaPinnedHostLimitMb(Integer.parseInt(options.get("cudaPinnedHostLimitMb")));
                }
                if (options.containsKey("kvCacheStrategy")) {
                    argsBuilder.kvCacheStrategy(options.get("kvCacheStrategy"));
                }
                if (options.containsKey("maxKvLen")) {
                    argsBuilder.maxKvLen(Integer.parseInt(options.get("maxKvLen")));
                }
                if (options.containsKey("maxPages")) {
                    argsBuilder.maxPages(Integer.parseInt(options.get("maxPages")));
                }
                // ND4J optimizer flags
                if (options.containsKey("optimizerEnabled")) {
                    argsBuilder.optimizerEnabled(Boolean.parseBoolean(options.get("optimizerEnabled")));
                }
                if (options.containsKey("optimizerFp16")) {
                    argsBuilder.optimizerFp16(Boolean.parseBoolean(options.get("optimizerFp16")));
                }
                if (options.containsKey("clearDecoderCache")) {
                    argsBuilder.clearDecoderCache(Boolean.parseBoolean(options.get("clearDecoderCache")));
                }
                // Triton flags
                if (options.containsKey("tritonEnabled")) {
                    argsBuilder.tritonEnabled(Boolean.parseBoolean(options.get("tritonEnabled")));
                }
                if (options.containsKey("tritonTf32")) {
                    argsBuilder.tritonTf32(Boolean.parseBoolean(options.get("tritonTf32")));
                }
                // DSP flags
                if (options.containsKey("dspNoNativeDecode")) {
                    argsBuilder.dspNoNativeDecode(Boolean.parseBoolean(options.get("dspNoNativeDecode")));
                }
                if (options.containsKey("dspNoFreeze")) {
                    argsBuilder.dspNoFreeze(Boolean.parseBoolean(options.get("dspNoFreeze")));
                }
                if (options.containsKey("dspNoAttnOverride")) {
                    argsBuilder.dspNoAttnOverride(Boolean.parseBoolean(options.get("dspNoAttnOverride")));
                }
                if (options.containsKey("dspNoDirect")) {
                    argsBuilder.dspNoDirect(Boolean.parseBoolean(options.get("dspNoDirect")));
                }
                // CUDA flags
                if (options.containsKey("noCublasWorkspace")) {
                    argsBuilder.noCublasWorkspace(Boolean.parseBoolean(options.get("noCublasWorkspace")));
                }
                // CUDA graph capture OOM retry / memory management
                if (options.containsKey("dspCaptureOomMaxRetries")) {
                    argsBuilder.dspCaptureOomMaxRetries(Integer.parseInt(options.get("dspCaptureOomMaxRetries")));
                }
                if (options.containsKey("dspCaptureOomRetryInterval")) {
                    argsBuilder.dspCaptureOomRetryInterval(Integer.parseInt(options.get("dspCaptureOomRetryInterval")));
                }
                if (options.containsKey("dspCublasWorkspaceMb")) {
                    argsBuilder.dspCublasWorkspaceMb(Integer.parseInt(options.get("dspCublasWorkspaceMb")));
                }
                if (options.containsKey("dspGraphMetadataSafetyMb")) {
                    argsBuilder.dspGraphMetadataSafetyMb(Integer.parseInt(options.get("dspGraphMetadataSafetyMb")));
                }
                if (options.containsKey("dspProactiveEvictBeforeCapture")) {
                    argsBuilder.dspProactiveEvictBeforeCapture(Boolean.parseBoolean(options.get("dspProactiveEvictBeforeCapture")));
                }
                if (options.containsKey("dspLruEviction")) {
                    argsBuilder.dspLruEviction(Boolean.parseBoolean(options.get("dspLruEviction")));
                }
                if (options.containsKey("dspCaptureWorkspaceMb")) {
                    argsBuilder.dspCaptureWorkspaceMb(Integer.parseInt(options.get("dspCaptureWorkspaceMb")));
                }
                // Speculative decoding
                if (options.containsKey("speculativeTokens")) {
                    argsBuilder.speculativeTokens(Integer.parseInt(options.get("speculativeTokens")));
                }
                // Debug flags
                if (options.containsKey("debugDiagnostics")) {
                    argsBuilder.debugDiagnostics(Boolean.parseBoolean(options.get("debugDiagnostics")));
                }
                if (options.containsKey("opTiming")) {
                    argsBuilder.opTiming(Boolean.parseBoolean(options.get("opTiming")));
                }
                argsBuilder.options(options);
            }

            // Set memory watchdog thresholds (use defaults for VLM)
            argsBuilder.memoryThresholdPercent(VlmTestSubprocessArgs.DEFAULT_MEMORY_THRESHOLD_PERCENT)
                    .memoryCriticalPercent(VlmTestSubprocessArgs.DEFAULT_MEMORY_CRITICAL_PERCENT)
                    .memoryKillThresholdPercent(VlmTestSubprocessArgs.DEFAULT_MEMORY_KILL_THRESHOLD_PERCENT)
                    .memoryCheckIntervalMs(VlmTestSubprocessArgs.DEFAULT_MEMORY_CHECK_INTERVAL_MS);
            
            // Set GPU memory thresholds from config service (more conservative for VLM)
            argsBuilder.gpuMemoryThresholdPercent(
                    subprocessConfigService != null ? subprocessConfigService.getGpuMemoryThresholdPercent() : VlmTestSubprocessArgs.DEFAULT_GPU_MEMORY_THRESHOLD_PERCENT)
                    .gpuMemoryCriticalPercent(
                            subprocessConfigService != null ? subprocessConfigService.getGpuMemoryCriticalPercent() : VlmTestSubprocessArgs.DEFAULT_GPU_MEMORY_CRITICAL_PERCENT)
                    .gpuMemoryKillThresholdPercent(
                            subprocessConfigService != null ? subprocessConfigService.getGpuMemoryKillThresholdPercent() : VlmTestSubprocessArgs.DEFAULT_GPU_MEMORY_KILL_THRESHOLD_PERCENT);

            // Set off-heap memory thresholds from config service
            argsBuilder.offHeapThresholdPercent(
                    subprocessConfigService != null ? subprocessConfigService.getOffHeapThresholdPercent() : VlmTestSubprocessArgs.DEFAULT_OFF_HEAP_THRESHOLD_PERCENT)
                    .offHeapCriticalPercent(
                            subprocessConfigService != null ? subprocessConfigService.getOffHeapCriticalPercent() : VlmTestSubprocessArgs.DEFAULT_OFF_HEAP_CRITICAL_PERCENT)
                    .offHeapKillThresholdPercent(
                            subprocessConfigService != null ? subprocessConfigService.getOffHeapKillThresholdPercent() : VlmTestSubprocessArgs.DEFAULT_OFF_HEAP_KILL_THRESHOLD_PERCENT);

            VlmTestSubprocessArgs args = argsBuilder.build();

            // Write args to temp file
            Path argsFile = Files.createTempFile("vlm-test-args-" + taskId + "-", ".json");
            args.toFile(argsFile);

            // Build subprocess command
            String classpath = getClasspath();
            List<String> command = execConfig.buildVlmTestCommand(argsFile, getEffectiveHeapSize(), getEffectiveJavaPath(), classpath, getEffectiveOffHeapMultiplier());

            logger.info("Launching VLM test subprocess for task {}: {}", taskId, String.join(" ", command));

            // Start process
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);

            // Propagate ND4J environment variables to subprocess
            propagateNd4jEnvironment(pb.environment());

            // Set CUDA pinned host memory limit for allocateFailover.
            // Default 8 GB is too low for VLM decoders (24 layers, 2K+ seq) which need
            // ~14 GB for a forward pass. When GPU pool fills, allocateFailover cascades to
            // pinned host memory. Default to 16 GB to provide headroom.
            int cudaPinnedLimitMb = args.cudaPinnedHostLimitMb();
            if (cudaPinnedLimitMb <= 0 && subprocessConfigService != null) {
                cudaPinnedLimitMb = subprocessConfigService.getVlmCudaPinnedHostLimitMb();
            }
            if (cudaPinnedLimitMb <= 0) {
                cudaPinnedLimitMb = 16384; // 16 GB default for VLM
            }
            // SD_CUDA_PINNED_HOST_LIMIT is in MB (not bytes) per CudaMemoryPool.cu line 94
            pb.environment().put("SD_CUDA_PINNED_HOST_LIMIT", String.valueOf(cudaPinnedLimitMb));
            logger.info("Set SD_CUDA_PINNED_HOST_LIMIT={} MB for VLM subprocess {}",
                    cudaPinnedLimitMb, taskId);

            // DSP diagnostics are off by default — enable via debugDiagnostics=true in args.
            // IMPORTANT: The EXECUTE category forces cudaStreamSynchronize after every graph
            // replay (328 syncs/step), causing ~33x slowdown (2.7 vs 86+ tok/s). Only enable
            // when explicitly requested for debugging.

            // Propagate CUDA graph capture OOM retry / memory management settings
            propagateDspCaptureConfig(pb.environment(), args);

            // Allow explicit GPU restriction via runtime option or config property.
            // Do NOT auto-restrict: the multi-device framework (DeviceMemoryManager,
            // DynamicShapePlan.assignDevices()) needs visibility of all GPUs to distribute
            // decoder ops across devices and avoid OOM on a single GPU. CUDA context
            // overhead on secondary devices is minimal (~300MB) compared to the benefit
            // of multi-GPU execution for large models.
            String effectiveCudaDevices = (options != null && options.containsKey("cudaDevices"))
                    ? options.get("cudaDevices") : cudaVisibleDevices;
            if (effectiveCudaDevices != null && !effectiveCudaDevices.isBlank()) {
                pb.environment().put("CUDA_DEVICE_ORDER", "PCI_BUS_ID");
                pb.environment().put("CUDA_VISIBLE_DEVICES", effectiveCudaDevices);
                logger.info("Set CUDA_DEVICE_ORDER=PCI_BUS_ID CUDA_VISIBLE_DEVICES={} for VLM subprocess {}", effectiveCudaDevices, taskId);
            } else {
                logger.info("VLM subprocess {} will see all GPUs (multi-device framework enabled)", taskId);
            }

            process = pb.start();

            // Create persistent log file for debugging
            Path logFile = Path.of("/tmp/vlm-test-" + taskId + ".log");
            BufferedWriter logWriter = null;
            try {
                logWriter = new BufferedWriter(new FileWriter(logFile.toFile(), false));
                logWriter.write("[" + Instant.now() + "] VLM test subprocess started for task " + taskId);
                logWriter.newLine();
                logWriter.write("[" + Instant.now() + "] Command: " + String.join(" ", command));
                logWriter.newLine();
                logWriter.write("[" + Instant.now() + "] File: " + filePath + ", Model: " + modelId + ", Format: " + outputFormat);
                logWriter.newLine();
                logWriter.flush();
            } catch (IOException e) {
                logger.warn("Failed to create VLM test log file at {}: {}", logFile, e.getMessage());
            }

            VlmTestHandle handle = new VlmTestHandle(taskId, process, future, filePath, logFile, logWriter);
            activeTests.put(taskId, handle);

            // --- Phase-2 log aggregation: central JSON-lines store ---
            try {
                SubprocessLogWriter slw = new SubprocessLogWriter("vlm-test", taskId);
                handle.subprocessLogWriter = slw;
                slw.writeStart(new SubprocessLogWriter.SubprocessRunContext(
                        taskId, command, null, process.pid(), getEffectiveHeapSize()));
                logger.debug("[vlm-test-{}] SubprocessLogWriter opened: {}", taskId, slw.getLogFile());
            } catch (Exception _logEx) {
                logger.debug("[vlm-test-{}] SubprocessLogWriter init failed (non-fatal): {}", taskId, _logEx.getMessage());
            }

            logger.info("VLM test subprocess log file for task {}: {}", taskId, logFile);
            sendWebSocketUpdate(taskId, "RUNNING", 0, "VLM_INIT", "Subprocess started", null);

            // Read stdout for progress messages
            CompletableFuture<Void> stderrFuture = CompletableFuture.runAsync(
                    () -> readStderr(handle, handle.process), executor);

            readStdout(handle);

            // Wait for process to complete
            int exitCode = process.waitFor();
            stderrFuture.cancel(true);

            // Clean up temp file
            try { Files.deleteIfExists(argsFile); } catch (Exception ignored) {}

            if (exitCode == 0) {
                VlmTestResult result = handle.buildResult("COMPLETED");
                // Check if any pages reported failure despite exit code 0
                boolean hasPageFailures = result.pageResults != null
                        && result.pageResults.stream()
                            .anyMatch(p -> Boolean.FALSE.equals(p.get("success")));
                if (hasPageFailures) {
                    boolean allFailed = result.pageResults.stream()
                            .allMatch(p -> Boolean.FALSE.equals(p.get("success")));
                    String status = allFailed ? "FAILED" : "COMPLETED_WITH_ERRORS";
                    result = handle.buildResult(status);
                    sendWebSocketUpdate(taskId, status, 100, "DONE",
                            "VLM test " + (allFailed ? "failed" : "completed with some page errors"), result);
                    writeEndSubprocessLog(handle, status, exitCode, null);
                } else {
                    sendWebSocketUpdate(taskId, "COMPLETED", 100, "DONE", "VLM test completed", result);
                    writeEndSubprocessLog(handle, "COMPLETED", exitCode, null);
                }
                future.complete(result);
            } else if (exitCode == 130) {
                VlmTestResult result = handle.buildResult("CANCELLED");
                sendWebSocketUpdate(taskId, "CANCELLED", 0, "CANCELLED", "VLM test cancelled", null);
                writeEndSubprocessLog(handle, "CANCELLED", exitCode, null);
                future.complete(result);
            } else {
                String error = "Subprocess exited with code " + exitCode;
                logger.error("VLM test subprocess failed for task {} with exit code {}. Log file: {}",
                        taskId, exitCode, handle.logFilePath);
                VlmTestResult result = handle.buildResult("FAILED");
                result.errorMessage = error;
                result.logFilePath = handle.logFilePath.toString();
                sendWebSocketUpdate(taskId, "FAILED", 0, "ERROR", error, null);
                writeEndSubprocessLog(handle, "FAILED", exitCode, error);
                future.complete(result);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendWebSocketUpdate(taskId, "CANCELLED", 0, "CANCELLED", "Interrupted", null);
            VlmTestHandle interruptedHandle = activeTests.get(taskId);
            if (interruptedHandle != null) {
                writeEndSubprocessLog(interruptedHandle, "CANCELLED", null, "Interrupted");
            }
            future.complete(new VlmTestResult(taskId, filePath, "CANCELLED"));
        } catch (Exception e) {
            logger.error("VLM test subprocess error for task {}. Log file: /tmp/vlm-test-{}.log",
                    taskId, taskId, e);
            sendWebSocketUpdate(taskId, "FAILED", 0, "ERROR", e.getMessage(), null);
            VlmTestHandle errHandle = activeTests.get(taskId);
            if (errHandle != null) {
                writeEndSubprocessLog(errHandle, "FAILED", null, e.getMessage());
            }
            future.completeExceptionally(e);
        } finally {
            VlmTestHandle handle = activeTests.get(taskId);
            if (handle != null) {
                handle.closeLog();
                handle.closeSubprocessLog();
            }
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            activeTests.remove(taskId);
            logSequenceCounters.remove(taskId);

            // === GPU LIFECYCLE: Release GPU resources and restore evicted services ===
            if (gpuAcquired && modelLifecycleManager != null) {
                logger.info("[vlm-test-{}] Releasing GPU resources via ModelLifecycleManager (will restore embedding)", taskId);
                try {
                    modelLifecycleManager.releaseGpuForVlm(taskId);
                    logger.info("[vlm-test-{}] GPU resources released, evicted services being restored", taskId);
                } catch (Exception e) {
                    logger.error("[vlm-test-{}] Error releasing GPU resources: {}", taskId, e.getMessage(), e);
                }
            }
        }
    }

    private void readStdout(VlmTestHandle handle) {
        Process process = handle.process;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                handle.writeToLog("STDOUT", line);
                // Central log aggregation
                if (handle.subprocessLogWriter != null) {
                    try {
                        handle.subprocessLogWriter.writeLine(AgentLogRecord.Stream.STDOUT, line);
                    } catch (Exception _logEx) {
                        logger.debug("[vlm-test-{}] SubprocessLogWriter stdout write failed: {}", handle.taskId, _logEx.getMessage());
                    }
                }
                if (line.startsWith(SubprocessMessage.MESSAGE_PREFIX)) {
                    String json = line.substring(SubprocessMessage.MESSAGE_PREFIX.length());
                    handleMessage(handle, json);
                } else if (!line.isBlank()) {
                    logger.debug("[vlm-test-{}] {}", handle.taskId, line);
                    sendLogEntry(handle.taskId, "STDOUT", "INFO", line);
                }
            }
        } catch (IOException e) {
            if (!handle.cancelled) {
                logger.warn("Error reading VLM test subprocess stdout for task {}", handle.taskId, e);
            }
        }
    }

    private void readStderr(VlmTestHandle handle, Process process) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                handle.writeToLog("STDERR", line);
                // Central log aggregation
                if (handle.subprocessLogWriter != null) {
                    try {
                        handle.subprocessLogWriter.writeLine(AgentLogRecord.Stream.STDERR, line);
                    } catch (Exception _logEx) {
                        logger.debug("[vlm-test-{}] SubprocessLogWriter stderr write failed: {}", handle.taskId, _logEx.getMessage());
                    }
                }

                String level;
                if (line.contains("OutOfMemoryError") || line.contains("Java heap space")) {
                    logger.error("[vlm-test-{}] OOM detected: {}", handle.taskId, line);
                    level = "ERROR";
                } else if (line.startsWith("\tat") || line.startsWith("Caused by:") || line.startsWith("Suppressed:")) {
                    logger.error("[vlm-test-{}] {}", handle.taskId, line);
                    level = "ERROR";
                } else if (line.contains("ERROR") || line.contains("Exception") || line.contains("FATAL")) {
                    logger.error("[vlm-test-{}] {}", handle.taskId, line);
                    level = "ERROR";
                } else if (line.contains("WARN")) {
                    logger.warn("[vlm-test-{}] {}", handle.taskId, line);
                    level = "WARN";
                } else if (line.contains(" INFO ")) {
                    logger.info("[vlm-test-{}] {}", handle.taskId, line);
                    level = "INFO";
                } else if (line.contains("DEBUG")) {
                    logger.debug("[vlm-test-{}] {}", handle.taskId, line);
                    level = "DEBUG";
                } else {
                    logger.debug("[vlm-test-{}] {}", handle.taskId, line);
                    level = "INFO";
                }

                sendLogEntry(handle.taskId, "STDERR", level, line);
            }
        } catch (IOException e) {
            if (!handle.cancelled) {
                logger.debug("Stderr reader terminated for VLM test task: {}", handle.taskId);
            }
        }
    }

    private void handleMessage(VlmTestHandle handle, String json) {
        try {
            SubprocessMessage message = MAPPER.readValue(json, SubprocessMessage.class);

            if (message instanceof SubprocessMessage.Progress progress) {
                handle.lastProgress = progress.progressPercent();
                handle.lastPhase = progress.phase();

                // Check for per-page result or VLM completion data
                String msg = progress.message();
                if (msg != null && msg.startsWith("PAGE_RESULT:")) {
                    String pageJson = msg.substring("PAGE_RESULT:".length());
                    Map<String, Object> pageData = MAPPER.readValue(pageJson, Map.class);
                    handle.pageResults.add(pageData);
                } else if (msg != null && msg.startsWith("VLM_RESULTS:")) {
                    String resultsJson = msg.substring("VLM_RESULTS:".length());
                    Map<String, Object> resultsData = MAPPER.readValue(resultsJson, Map.class);
                    handle.completionData = resultsData;
                }

                sendWebSocketUpdate(handle.taskId, "RUNNING",
                        progress.progressPercent(), progress.phase(),
                        progress.currentStep(), null);

            } else if (message instanceof SubprocessMessage.Completed completed) {
                handle.phaseDurations = completed.phaseDurations();

            } else if (message instanceof SubprocessMessage.Failed failed) {
                handle.errorMessage = failed.errorMessage();
                sendWebSocketUpdate(handle.taskId, "FAILED", 0, "ERROR",
                        failed.errorMessage(), null);

            } else if (message instanceof SubprocessMessage.Heartbeat) {
                handle.lastHeartbeat = System.currentTimeMillis();
            }
        } catch (Exception e) {
            logger.warn("Failed to parse VLM test subprocess message: {}", json, e);
        }
    }

    private void sendWebSocketUpdate(String taskId, String status, int progress,
                                       String phase, String message, VlmTestResult result) {
        if (messagingTemplate == null) return;

        try {
            Map<String, Object> update = new LinkedHashMap<>();
            update.put("taskId", taskId);
            update.put("status", status);
            update.put("progressPercent", progress);
            update.put("currentPhase", phase);
            update.put("message", message);
            if (result != null) {
                update.put("result", result);
            }
            messagingTemplate.convertAndSend("/topic/vlm-test/" + taskId, update);
        } catch (Exception e) {
            logger.warn("Failed to send WebSocket update for VLM test {}", taskId, e);
        }
    }

    /**
     * Sends a log entry to WebSocket clients for real-time streaming.
     */
    private void sendLogEntry(String taskId, String source, String level, String message) {
        if (messagingTemplate == null || message == null || message.isBlank()) return;

        try {
            AtomicLong seqCounter = logSequenceCounters.computeIfAbsent(
                    taskId, k -> new AtomicLong(0));
            long seq = seqCounter.incrementAndGet();

            Map<String, Object> logEntry = new LinkedHashMap<>();
            logEntry.put("taskId", taskId);
            logEntry.put("level", level);
            logEntry.put("source", source);
            logEntry.put("message", message);
            logEntry.put("timestamp", Instant.now().toString());
            logEntry.put("sequenceNumber", seq);

            messagingTemplate.convertAndSend("/topic/vlm-test/" + taskId + "/logs", logEntry);
        } catch (Exception e) {
            logger.warn("Failed to send log entry for VLM test {}", taskId, e);
        }
    }

    /**
     * Write the terminal SubprocessRunResult to the central log store.
     * All failures are swallowed so aggregation never breaks a VLM test.
     */
    private void writeEndSubprocessLog(VlmTestHandle handle, String state, Integer exitCode, String errorMessage) {
        SubprocessLogWriter slw = handle.subprocessLogWriter;
        if (slw == null) return;
        try {
            slw.writeEnd(new SubprocessLogWriter.SubprocessRunResult(
                    state, exitCode, errorMessage, false, false));
        } catch (Exception _logEx) {
            logger.debug("[vlm-test-{}] SubprocessLogWriter writeEnd failed (non-fatal): {}", handle.taskId, _logEx.getMessage());
        }
    }

    /**
     * Cancel a running VLM test.
     */
    public boolean cancelTest(String taskId) {
        VlmTestHandle handle = activeTests.get(taskId);
        if (handle != null && handle.process.isAlive()) {
            handle.cancelled = true;
            handle.process.destroyForcibly();
            return true;
        }
        return false;
    }

    /**
     * Get status of a running VLM test.
     */
    public VlmTestStatus getStatus(String taskId) {
        VlmTestHandle handle = activeTests.get(taskId);
        if (handle == null) {
            return null;
        }
        return new VlmTestStatus(taskId, handle.process.isAlive() ? "RUNNING" : "FINISHED",
                handle.lastProgress, handle.lastPhase, handle.pageResults.size());
    }

    /**
     * Check if a test is currently running.
     */
    public boolean isRunning(String taskId) {
        VlmTestHandle handle = activeTests.get(taskId);
        return handle != null && handle.process.isAlive();
    }

    private String getClasspath() {
        Set<String> classpathEntries = new LinkedHashSet<>();
        String pathSeparator = System.getProperty("path.separator");

        // 1. Start with java.class.path (may be incomplete under Spring Boot)
        String systemClasspath = System.getProperty("java.class.path");
        if (systemClasspath != null && !systemClasspath.isBlank()) {
            for (String entry : systemClasspath.split(pathSeparator)) {
                if (!entry.isBlank()) {
                    classpathEntries.add(entry);
                }
            }
        }

        // 2. Extract URLs from classloader hierarchy (handles Spring Boot classloaders)
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = getClass().getClassLoader();
        }

        while (classLoader != null) {
            if (classLoader instanceof java.net.URLClassLoader urlClassLoader) {
                for (java.net.URL url : urlClassLoader.getURLs()) {
                    try {
                        String path = url.toURI().getPath();
                        if (path != null && !path.isBlank()) {
                            classpathEntries.add(path);
                        }
                    } catch (Exception e) {
                        String urlStr = url.toString();
                        if (urlStr.startsWith("file:")) {
                            classpathEntries.add(urlStr.substring(5));
                        }
                    }
                }
            }

            // Check for Spring Boot's specialized classloaders via reflection
            try {
                java.lang.reflect.Method getUrlsMethod = classLoader.getClass().getMethod("getURLs");
                Object result = getUrlsMethod.invoke(classLoader);
                if (result instanceof java.net.URL[] urls) {
                    for (java.net.URL url : urls) {
                        try {
                            String path = url.toURI().getPath();
                            if (path != null && !path.isBlank()) {
                                classpathEntries.add(path);
                            }
                        } catch (Exception e) {
                            // Ignore
                        }
                    }
                }
            } catch (NoSuchMethodException e) {
                // Classloader doesn't have getURLs method, skip
            } catch (Exception e) {
                logger.debug("Error extracting URLs from classloader {}: {}",
                        classLoader.getClass().getName(), e.getMessage());
            }

            classLoader = classLoader.getParent();
        }

        // 3. Check for target/classes directories (IDE/Maven runs)
        String userDir = System.getProperty("user.dir");
        if (userDir != null) {
            String[] possibleClassDirs = {
                    userDir + "/target/classes",
                    userDir + "/target/test-classes",
                    userDir + "/../kompile-ocr-models/target/classes",
                    userDir + "/../kompile-ocr-integration/target/classes",
                    userDir + "/../kompile-model-manager/target/classes",
                    userDir + "/../kompile-app-core/target/classes"
            };

            for (String dir : possibleClassDirs) {
                Path dirPath = Path.of(dir).normalize();
                if (Files.exists(dirPath) && Files.isDirectory(dirPath)) {
                    classpathEntries.add(dirPath.toString());
                    logger.debug("Added target/classes directory to classpath: {}", dirPath);
                }
            }
        }

        // 4. Handle Spring Boot fat JAR: extract BOOT-INF/lib and BOOT-INF/classes
        // When running via 'java -jar app.jar', the classpath only contains the fat JAR.
        // A subprocess can't use a Spring Boot fat JAR with -cp because classes are under
        // BOOT-INF/. We need to extract the nested JARs as classpath entries.
        Set<String> fatJarExpanded = new LinkedHashSet<>();
        for (String entry : classpathEntries) {
            if (entry.endsWith(".jar") && isSpringBootFatJar(entry)) {
                logger.info("Detected Spring Boot fat JAR: {}, extracting BOOT-INF entries", entry);
                try {
                    extractBootInfClasspath(entry, fatJarExpanded);
                } catch (Exception e) {
                    logger.warn("Failed to extract BOOT-INF from {}: {}", entry, e.getMessage());
                }
            }
        }
        if (!fatJarExpanded.isEmpty()) {
            // Replace the fat JAR entry with the expanded entries
            classpathEntries.addAll(fatJarExpanded);
            logger.info("Added {} BOOT-INF entries from fat JAR to classpath", fatJarExpanded.size());
        }

        logger.info("Built VLM subprocess classpath with {} entries from classloader hierarchy", classpathEntries.size());
        return String.join(pathSeparator, classpathEntries);
    }

    /**
     * Check if a JAR file is a Spring Boot fat JAR by looking for BOOT-INF/lib/ entries.
     */
    private boolean isSpringBootFatJar(String jarPath) {
        try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(jarPath)) {
            return jarFile.getEntry("BOOT-INF/lib/") != null || jarFile.getEntry("BOOT-INF/classes/") != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Extract BOOT-INF/lib/*.jar and BOOT-INF/classes/ from a Spring Boot fat JAR
     * into a temp directory, returning the extracted paths for use as classpath entries.
     */
    private void extractBootInfClasspath(String fatJarPath, Set<String> outputEntries) throws IOException {
        Path fatJar = Path.of(fatJarPath).toAbsolutePath();
        // Create extraction directory next to the fat JAR
        Path extractDir = fatJar.getParent().resolve(".boot-inf-extracted");
        Path libDir = extractDir.resolve("lib");
        Path classesDir = extractDir.resolve("classes");

        try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(fatJarPath)) {
            // Extract BOOT-INF/classes/ if present
            if (jarFile.getEntry("BOOT-INF/classes/") != null) {
                Files.createDirectories(classesDir);
                java.util.Enumeration<java.util.jar.JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    java.util.jar.JarEntry entry = entries.nextElement();
                    if (entry.getName().startsWith("BOOT-INF/classes/") && !entry.isDirectory()) {
                        String relativePath = entry.getName().substring("BOOT-INF/classes/".length());
                        Path targetFile = classesDir.resolve(relativePath);
                        Files.createDirectories(targetFile.getParent());
                        if (!Files.exists(targetFile) || Files.getLastModifiedTime(targetFile).toMillis() < entry.getTime()) {
                            try (InputStream is = jarFile.getInputStream(entry)) {
                                Files.copy(is, targetFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            }
                        }
                    }
                }
                outputEntries.add(classesDir.toString());
            }

            // Extract BOOT-INF/lib/*.jar
            if (jarFile.getEntry("BOOT-INF/lib/") != null) {
                Files.createDirectories(libDir);
                java.util.Enumeration<java.util.jar.JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    java.util.jar.JarEntry entry = entries.nextElement();
                    if (entry.getName().startsWith("BOOT-INF/lib/") && entry.getName().endsWith(".jar")) {
                        String jarName = entry.getName().substring("BOOT-INF/lib/".length());
                        Path targetJar = libDir.resolve(jarName);
                        if (!Files.exists(targetJar) || Files.size(targetJar) != entry.getSize()) {
                            try (InputStream is = jarFile.getInputStream(entry)) {
                                Files.copy(is, targetJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            }
                        }
                        outputEntries.add(targetJar.toString());
                    }
                }
            }
        }
        logger.info("Extracted BOOT-INF entries to {}", extractDir);
    }

    /**
     * Capture ND4J configuration as JSON from the live parent process environment.
     * Reuses the same pattern as SubprocessIngestLauncher.captureNd4jConfig().
     */
    private String captureNd4jConfig() {
        Nd4jEnvironmentConfig config = null;

        // Prefer capturing the live ND4J config if the service is available
        if (nd4jEnvironmentConfigService != null) {
            try {
                Nd4jEnvironmentConfig actualConfig = nd4jEnvironmentConfigService.getActualConfiguration();
                logger.info("Capturing actual live ND4J config for VLM subprocess: maxThreads={}, optimizerEnabled={}, dspNoFreeze={}",
                        actualConfig.maxThreads(), actualConfig.optimizerEnabled(), actualConfig.dspNoFreeze());
                config = actualConfig;
            } catch (Exception e) {
                logger.warn("Failed to capture ND4J config from Nd4jEnvironmentConfigService, falling back: {}", e.getMessage());
            }
        } else {
            logger.warn("Nd4jEnvironmentConfigService not available, using defaults for VLM subprocess ND4J config");
        }

        if (config == null) {
            config = Nd4jEnvironmentConfig.defaults();
        }

        try {
            return objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            logger.warn("Failed to serialize ND4J config to JSON: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Query nvidia-smi to find the GPU with the most free memory.
     * Returns the GPU index as a string, or null if nvidia-smi is unavailable.
     */
    private String selectBestGpuViaNvidiaSmi() {
        try {
            Process proc = new ProcessBuilder("nvidia-smi",
                    "--query-gpu=index,memory.free", "--format=csv,noheader,nounits")
                    .redirectErrorStream(true)
                    .start();

            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                output = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
            }

            if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return null;
            }
            if (proc.exitValue() != 0) {
                return null;
            }

            int bestIdx = -1;
            long bestFree = -1;
            for (String line : output.split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split(",\\s*");
                if (parts.length >= 2) {
                    int idx = Integer.parseInt(parts[0].trim());
                    long freeMiB = Long.parseLong(parts[1].trim());
                    logger.debug("nvidia-smi: GPU {} has {} MiB free", idx, freeMiB);
                    if (freeMiB > bestFree) {
                        bestFree = freeMiB;
                        bestIdx = idx;
                    }
                }
            }
            return bestIdx >= 0 ? String.valueOf(bestIdx) : null;
        } catch (Exception e) {
            logger.debug("nvidia-smi query failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Propagate ND4J-related environment variables from the parent process to the subprocess.
     * Reuses the same pattern as SubprocessIngestLauncher.propagateNd4jEnvironment().
     */
    private void propagateDspCaptureConfig(Map<String, String> env, VlmTestSubprocessArgs args) {
        if (args.dspCaptureOomMaxRetries() != null)
            env.put("ND4J_DSP_CAPTURE_OOM_MAX_RETRIES", args.dspCaptureOomMaxRetries().toString());
        if (args.dspCaptureOomRetryInterval() != null)
            env.put("ND4J_DSP_CAPTURE_OOM_RETRY_INTERVAL", args.dspCaptureOomRetryInterval().toString());
        if (args.dspCublasWorkspaceMb() != null)
            env.put("ND4J_DSP_CUBLAS_WORKSPACE_MB", args.dspCublasWorkspaceMb().toString());
        if (args.dspGraphMetadataSafetyMb() != null)
            env.put("ND4J_DSP_GRAPH_METADATA_SAFETY_MB", args.dspGraphMetadataSafetyMb().toString());
        if (args.dspProactiveEvictBeforeCapture() != null)
            env.put("ND4J_DSP_PROACTIVE_EVICT", args.dspProactiveEvictBeforeCapture() ? "1" : "0");
        if (args.dspLruEviction() != null)
            env.put("ND4J_DSP_LRU_EVICTION", args.dspLruEviction() ? "1" : "0");
        if (args.dspCaptureWorkspaceMb() != null)
            env.put("ND4J_DSP_CAPTURE_WORKSPACE_MB", args.dspCaptureWorkspaceMb().toString());
    }

    private void propagateNd4jEnvironment(Map<String, String> env) {
        List<String> nd4jEnvVars = List.of(
                "ND4J_BACKEND", "ND4J_DATA_BUFFER_OPS", "ND4J_RESOURCES_DIR", "ND4J_ALLOW_FALLBACK",
                "OMP_NUM_THREADS", "MKL_NUM_THREADS", "OPENBLAS_NUM_THREADS", "GOTO_NUM_THREADS",
                "VECLIB_MAXIMUM_THREADS", "NUMEXPR_NUM_THREADS",
                "CUDA_VISIBLE_DEVICES", "CUDA_DEVICE_ORDER", "CUDA_LAUNCH_BLOCKING", "CUDA_CACHE_PATH",
                "JAVACPP_PLATFORM", "JAVACPP_CACHESFX",
                "ND4J_HEAP_SPACE", "ND4J_OFF_HEAP_SPACE",
                "KOMPILE_MODELS_DIR");

        int propagated = 0;
        for (String varName : nd4jEnvVars) {
            String value = System.getenv(varName);
            if (value != null && !value.isEmpty()) {
                env.put(varName, value);
                propagated++;
            }
        }

        // Also propagate any ND4J_ or KOMPILE_ prefixed env vars
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            String key = entry.getKey();
            if ((key.startsWith("ND4J_") || key.startsWith("KOMPILE_")) && !env.containsKey(key)) {
                env.put(key, entry.getValue());
                propagated++;
            }
        }

        // Set OMP_NUM_THREADS from ND4J if not already set
        if (!env.containsKey("OMP_NUM_THREADS")) {
            try {
                int maxThreads = (int) org.nd4j.linalg.factory.Nd4j.getEnvironment().maxThreads();
                if (maxThreads > 0) {
                    env.put("OMP_NUM_THREADS", String.valueOf(maxThreads));
                    env.put("MKL_NUM_THREADS", String.valueOf(maxThreads));
                    env.put("OPENBLAS_NUM_THREADS", String.valueOf(maxThreads));
                    propagated += 3;
                }
            } catch (Exception e) {
                logger.debug("Could not get ND4J maxThreads: {}", e.getMessage());
            }
        }

        logger.info("Propagated {} ND4J environment variables to VLM subprocess", propagated);
    }

    @PreDestroy
    public void shutdown() {
        activeTests.values().forEach(handle -> {
            if (handle.process.isAlive()) {
                handle.process.destroyForcibly();
            }
        });
        executor.shutdownNow();
    }

    // ==================== Inner Classes ====================

    private static class VlmTestHandle {
        final String taskId;
        final Process process;
        final CompletableFuture<VlmTestResult> future;
        final String filePath;
        final long startTime = System.currentTimeMillis();
        final List<Map<String, Object>> pageResults = new CopyOnWriteArrayList<>();
        final Path logFilePath;
        final BufferedWriter logWriter;

        /** Central JSON-lines log writer (Phase-2 log aggregation). May be null if init failed. */
        volatile SubprocessLogWriter subprocessLogWriter;

        volatile boolean cancelled = false;
        volatile int lastProgress = 0;
        volatile String lastPhase = "INIT";
        volatile long lastHeartbeat = System.currentTimeMillis();
        volatile String errorMessage;
        volatile Map<String, Object> completionData;
        volatile Map<String, Long> phaseDurations;

        VlmTestHandle(String taskId, Process process, CompletableFuture<VlmTestResult> future,
                       String filePath, Path logFilePath, BufferedWriter logWriter) {
            this.taskId = taskId;
            this.process = process;
            this.future = future;
            this.filePath = filePath;
            this.logFilePath = logFilePath;
            this.logWriter = logWriter;
        }

        void writeToLog(String source, String line) {
            if (logWriter == null) return;
            try {
                synchronized (logWriter) {
                    logWriter.write("[" + java.time.Instant.now() + "] [" + source + "] " + line);
                    logWriter.newLine();
                    logWriter.flush();
                }
            } catch (IOException e) {
                // Silently ignore log write failures to avoid disrupting main flow
            }
        }

        void closeLog() {
            if (logWriter != null) {
                try {
                    logWriter.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }

        /** Close the central SubprocessLogWriter (non-fatal). */
        void closeSubprocessLog() {
            SubprocessLogWriter slw = subprocessLogWriter;
            if (slw != null) {
                try {
                    slw.close();
                } catch (Exception _e) {
                    // best-effort
                }
            }
        }

        VlmTestResult buildResult(String status) {
            VlmTestResult result = new VlmTestResult(taskId, filePath, status);
            result.pageResults = new ArrayList<>(pageResults);
            result.completionData = completionData;
            result.phaseDurations = phaseDurations;
            result.totalTimeMs = System.currentTimeMillis() - startTime;
            result.errorMessage = errorMessage;
            result.logFilePath = logFilePath != null ? logFilePath.toString() : null;
            return result;
        }
    }

    /**
     * Result from a VLM test subprocess.
     */
    public static class VlmTestResult {
        public String taskId;
        public String filePath;
        public String status;
        public List<Map<String, Object>> pageResults;
        public Map<String, Object> completionData;
        public Map<String, Long> phaseDurations;
        public long totalTimeMs;
        public String errorMessage;
        public String logFilePath;

        public VlmTestResult(String taskId, String filePath, String status) {
            this.taskId = taskId;
            this.filePath = filePath;
            this.status = status;
        }
    }

    /**
     * Status of a running VLM test.
     */
    public record VlmTestStatus(String taskId, String status, int progressPercent,
                                  String currentPhase, int pagesCompleted) {}
}
