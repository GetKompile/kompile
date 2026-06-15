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

package ai.kompile.app.tools;

import ai.kompile.app.services.subprocess.VlmTestSubprocessLauncher;
import ai.kompile.app.services.subprocess.VlmTestSubprocessLauncher.VlmTestResult;
import ai.kompile.app.services.subprocess.VlmTestSubprocessLauncher.VlmTestStatus;
import ai.kompile.app.web.controllers.VlmTestWorkflowController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
public class VlmTestTool {

    private static final Logger logger = LoggerFactory.getLogger(VlmTestTool.class);

    private final VlmTestSubprocessLauncher launcher;
    private final VlmTestWorkflowController vlmTestWorkflowController;
    private final Map<String, CompletableFuture<VlmTestResult>> activeFutures = new ConcurrentHashMap<>();
    private final Map<String, VlmTestResult> completedResults = new ConcurrentHashMap<>();

    @Autowired
    public VlmTestTool(VlmTestSubprocessLauncher launcher,
                        @Autowired(required = false) VlmTestWorkflowController vlmTestWorkflowController) {
        this.launcher = launcher;
        this.vlmTestWorkflowController = vlmTestWorkflowController;
        logger.info("VlmTestTool initialized");
    }

    public record RunVlmTestInput(String filePath, String modelId, String outputFormat,
                                   Integer maxNewTokens, Double temperature, Double topP,
                                   Integer pdfRenderDpi, Integer pageBatchSize,
                                   Integer cudaPinnedHostLimitMb, String kvCacheStrategy,
                                   Integer maxKvLen,
                                   // ND4J optimizer flags
                                   Boolean optimizerEnabled, Boolean optimizerFp16,
                                   Boolean clearDecoderCache,
                                   // Triton / kernel flags
                                   Boolean tritonEnabled, Boolean tritonTf32,
                                   // DSP flags
                                   Boolean dspNoNativeDecode, Boolean dspNoFreeze,
                                   Boolean dspNoAttnOverride, Boolean dspNoDirect,
                                   // CUDA flags
                                   Boolean noCublasWorkspace,
                                   // Speculative decoding
                                   Integer speculativeTokens,
                                   // Debug
                                   Boolean debugDiagnostics, Boolean opTiming) {}

    public record GetVlmTestStatusInput(String taskId) {}
    public record GetVlmTestResultsInput(String taskId) {}
    public record CancelVlmTestInput(String taskId) {}
    public record WaitVlmTestInput(String taskId, Integer timeoutSeconds) {}
    public record GetVlmTestConfigInput() {}
    public record UpdateVlmTestConfigInput(String heapSize, Integer offHeapMultiplier, Integer timeoutMinutes,
                                               Integer cudaPinnedHostLimitMb,
                                               Boolean optimizerEnabled, Boolean optimizerFp16,
                                               Boolean tritonEnabled, Boolean tritonTf32) {}

    @Tool(name = "run_vlm_test",
            description = "Runs a VLM (Vision Language Model) test on a local file (PDF, PNG, JPG, TIFF). " +
                    "Launches a subprocess that processes the document with the specified VLM model. " +
                    "Returns a taskId to poll for status/results with get_vlm_test_status and get_vlm_test_results. " +
                    "Parameters: filePath (absolute path to file), modelId (e.g. 'smoldocling-256m'), " +
                    "outputFormat (DOCTAGS, MARKDOWN, JSON, or TEXT - default DOCTAGS), " +
                    "maxNewTokens (default 4096), temperature (default 1.0), topP (default 1.0), " +
                    "pdfRenderDpi (default 300), pageBatchSize (default 1), cudaPinnedHostLimitMb, " +
                    "kvCacheStrategy (STATIC, QUANTIZED, or PAGED), maxKvLen (hard cap on KV cache length), " +
                    "optimizerEnabled (default true - graph optimizer), optimizerFp16 (default true - halves VRAM), " +
                    "clearDecoderCache (default false - delete cached .sdz for fresh optimizer pass), " +
                    "tritonEnabled (default true), tritonTf32 (default false), " +
                    "dspNoNativeDecode (default false), dspNoFreeze (default false), " +
                    "dspNoAttnOverride (default false), dspNoDirect (default false), " +
                    "noCublasWorkspace (default false), speculativeTokens (default 0 = disabled), " +
                    "debugDiagnostics (default false - nd4j.dsp.diagnostics=ALL), opTiming (default false).")
    public Map<String, Object> runVlmTest(RunVlmTestInput input) {
        try {
            if (input.filePath == null || input.filePath.isBlank()) {
                return Map.of("error", "filePath is required");
            }

            File file = new File(input.filePath);
            if (!file.exists()) {
                return Map.of("error", "File not found: " + input.filePath);
            }

            String modelId = input.modelId != null ? input.modelId : "smoldocling-256m";
            String outputFormat = input.outputFormat != null ? input.outputFormat : "DOCTAGS";
            String taskId = "vlm-test-" + UUID.randomUUID().toString().substring(0, 8);

            Map<String, String> options = new HashMap<>();
            if (input.maxNewTokens != null) options.put("maxNewTokens", String.valueOf(input.maxNewTokens));
            if (input.temperature != null) options.put("temperature", String.valueOf(input.temperature));
            if (input.topP != null) options.put("topP", String.valueOf(input.topP));
            if (input.pdfRenderDpi != null) options.put("pdfRenderDpi", String.valueOf(input.pdfRenderDpi));
            if (input.pageBatchSize != null) options.put("pageBatchSize", String.valueOf(input.pageBatchSize));
            if (input.cudaPinnedHostLimitMb != null) options.put("cudaPinnedHostLimitMb", String.valueOf(input.cudaPinnedHostLimitMb));
            if (input.kvCacheStrategy != null) options.put("kvCacheStrategy", input.kvCacheStrategy);
            if (input.maxKvLen != null) options.put("maxKvLen", String.valueOf(input.maxKvLen));
            // ND4J optimizer flags
            if (input.optimizerEnabled != null) options.put("optimizerEnabled", String.valueOf(input.optimizerEnabled));
            if (input.optimizerFp16 != null) options.put("optimizerFp16", String.valueOf(input.optimizerFp16));
            if (input.clearDecoderCache != null) options.put("clearDecoderCache", String.valueOf(input.clearDecoderCache));
            // Triton flags
            if (input.tritonEnabled != null) options.put("tritonEnabled", String.valueOf(input.tritonEnabled));
            if (input.tritonTf32 != null) options.put("tritonTf32", String.valueOf(input.tritonTf32));
            // DSP flags
            if (input.dspNoNativeDecode != null) options.put("dspNoNativeDecode", String.valueOf(input.dspNoNativeDecode));
            if (input.dspNoFreeze != null) options.put("dspNoFreeze", String.valueOf(input.dspNoFreeze));
            if (input.dspNoAttnOverride != null) options.put("dspNoAttnOverride", String.valueOf(input.dspNoAttnOverride));
            if (input.dspNoDirect != null) options.put("dspNoDirect", String.valueOf(input.dspNoDirect));
            // CUDA flags
            if (input.noCublasWorkspace != null) options.put("noCublasWorkspace", String.valueOf(input.noCublasWorkspace));
            // Speculative decoding
            if (input.speculativeTokens != null) options.put("speculativeTokens", String.valueOf(input.speculativeTokens));
            // Debug flags
            if (input.debugDiagnostics != null) options.put("debugDiagnostics", String.valueOf(input.debugDiagnostics));
            if (input.opTiming != null) options.put("opTiming", String.valueOf(input.opTiming));

            CompletableFuture<VlmTestResult> future = launcher.launchTest(
                    taskId, file.getAbsolutePath(), modelId, outputFormat, options);

            activeFutures.put(taskId, future);
            future.whenComplete((result, error) -> {
                activeFutures.remove(taskId);
                if (result != null) {
                    completedResults.put(taskId, result);
                } else if (error != null) {
                    VlmTestResult failedResult = new VlmTestResult(taskId, input.filePath, "FAILED");
                    failedResult.errorMessage = error.getMessage();
                    completedResults.put(taskId, failedResult);
                }
            });

            logger.info("Started VLM test {} for file {} with model {}", taskId, input.filePath, modelId);

            return Map.of(
                    "taskId", taskId,
                    "filePath", input.filePath,
                    "status", "STARTED",
                    "modelId", modelId,
                    "outputFormat", outputFormat
            );

        } catch (Exception e) {
            logger.error("Failed to start VLM test", e);
            return Map.of("error", "Failed to start VLM test: " + e.getMessage());
        }
    }

    @Tool(name = "get_vlm_test_status",
            description = "Gets the status of a running or completed VLM test by taskId.")
    public Map<String, Object> getVlmTestStatus(GetVlmTestStatusInput input) {
        if (input.taskId == null) return Map.of("error", "taskId is required");

        VlmTestResult completed = completedResults.get(input.taskId);
        if (completed != null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("taskId", input.taskId);
            result.put("status", completed.status);
            result.put("progressPercent", 100);
            result.put("currentPhase", "DONE");
            return result;
        }

        VlmTestStatus status = launcher.getStatus(input.taskId);
        if (status != null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("taskId", status.taskId());
            result.put("status", status.status());
            result.put("progressPercent", status.progressPercent());
            result.put("currentPhase", status.currentPhase());
            result.put("pagesCompleted", status.pagesCompleted());
            return result;
        }

        return Map.of("error", "No VLM test found with taskId: " + input.taskId);
    }

    @Tool(name = "get_vlm_test_results",
            description = "Gets the completed results of a VLM test including per-page output, " +
                    "generated tokens, processing times, and performance metrics.")
    public Map<String, Object> getVlmTestResults(GetVlmTestResultsInput input) {
        if (input.taskId == null) return Map.of("error", "taskId is required");

        VlmTestResult result = completedResults.get(input.taskId);
        if (result == null) {
            if (launcher.isRunning(input.taskId)) {
                return Map.of("taskId", input.taskId, "status", "RUNNING",
                        "message", "Test is still in progress");
            }
            return Map.of("error", "No completed VLM test found with taskId: " + input.taskId);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("taskId", result.taskId);
        response.put("filePath", result.filePath);
        response.put("status", result.status);
        response.put("totalTimeMs", result.totalTimeMs);
        if (result.pageResults != null) response.put("pages", result.pageResults);
        if (result.completionData != null) response.put("performance", result.completionData);
        if (result.phaseDurations != null) response.put("phaseDurations", result.phaseDurations);
        if (result.errorMessage != null) response.put("errorMessage", result.errorMessage);
        return response;
    }

    @Tool(name = "cancel_vlm_test",
            description = "Cancels a running VLM test by taskId.")
    public Map<String, Object> cancelVlmTest(CancelVlmTestInput input) {
        if (input.taskId == null) return Map.of("error", "taskId is required");

        boolean cancelled = launcher.cancelTest(input.taskId);
        if (cancelled) {
            return Map.of("taskId", input.taskId, "status", "CANCELLED",
                    "message", "VLM test cancelled");
        }
        return Map.of("error", "No running VLM test found with taskId: " + input.taskId);
    }

    @Tool(name = "wait_vlm_test",
            description = "Waits for a VLM test to complete and returns the results. " +
                    "Optional timeoutSeconds (default 300). Returns results when done or times out.")
    public Map<String, Object> waitVlmTest(WaitVlmTestInput input) {
        if (input.taskId == null) return Map.of("error", "taskId is required");

        // Already completed?
        VlmTestResult completed = completedResults.get(input.taskId);
        if (completed != null) {
            return getVlmTestResults(new GetVlmTestResultsInput(input.taskId));
        }

        CompletableFuture<VlmTestResult> future = activeFutures.get(input.taskId);
        if (future == null) {
            return Map.of("error", "No VLM test found with taskId: " + input.taskId);
        }

        int timeout = input.timeoutSeconds != null ? input.timeoutSeconds : 300;
        try {
            future.get(timeout, TimeUnit.SECONDS);
            return getVlmTestResults(new GetVlmTestResultsInput(input.taskId));
        } catch (java.util.concurrent.TimeoutException e) {
            VlmTestStatus status = launcher.getStatus(input.taskId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("taskId", input.taskId);
            result.put("status", "TIMEOUT");
            result.put("message", "Timed out after " + timeout + "s, test still running");
            if (status != null) {
                result.put("progressPercent", status.progressPercent());
                result.put("currentPhase", status.currentPhase());
            }
            return result;
        } catch (Exception e) {
            return Map.of("error", "Error waiting for VLM test: " + e.getMessage());
        }
    }

    @Tool(name = "get_vlm_test_config",
            description = "Gets the current VLM subprocess configuration including heap size, off-heap multiplier, and timeout.")
    public Map<String, Object> getVlmTestConfig(GetVlmTestConfigInput input) {
        try {
            if (vlmTestWorkflowController == null) return Map.of("status", "error", "error", "VLM test controller not available");
            ResponseEntity<?> response = vlmTestWorkflowController.getConfig();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting VLM test config: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "update_vlm_test_config",
            description = "Updates the VLM subprocess configuration. Parameters: heapSize (e.g. '16g'), " +
                    "offHeapMultiplier (e.g. 3), timeoutMinutes (e.g. 30), cudaPinnedHostLimitMb (e.g. 4096), " +
                    "optimizerEnabled (default true), optimizerFp16 (default true), " +
                    "tritonEnabled (default true), tritonTf32 (default false).")
    public Map<String, Object> updateVlmTestConfig(UpdateVlmTestConfigInput input) {
        try {
            if (vlmTestWorkflowController == null) return Map.of("status", "error", "error", "VLM test controller not available");
            Map<String, Object> update = new LinkedHashMap<>();
            if (input.heapSize() != null) update.put("heapSize", input.heapSize());
            if (input.offHeapMultiplier() != null) update.put("offHeapMultiplier", input.offHeapMultiplier());
            if (input.timeoutMinutes() != null) update.put("timeoutMinutes", input.timeoutMinutes());
            if (input.cudaPinnedHostLimitMb() != null) update.put("vlmCudaPinnedHostLimitMb", input.cudaPinnedHostLimitMb());
            ResponseEntity<?> response = vlmTestWorkflowController.updateConfig(update);
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error updating VLM test config: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }
}
