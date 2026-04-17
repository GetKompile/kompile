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

package ai.kompile.app.web.controllers;

import ai.kompile.app.services.subprocess.SubprocessConfigService;
import ai.kompile.app.services.subprocess.SubprocessConfigService.SubprocessConfigUpdate;
import ai.kompile.app.services.subprocess.VlmTestSubprocessLauncher;
import ai.kompile.app.services.subprocess.VlmTestSubprocessLauncher.VlmTestResult;
import ai.kompile.app.services.subprocess.VlmTestSubprocessLauncher.VlmTestStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST controller for VLM test workflow.
 *
 * Provides endpoints to upload documents, run VLM-only processing in a subprocess,
 * and retrieve per-page results with performance metrics.
 */
@RestController
@RequestMapping("/api/vlm/test")
public class VlmTestWorkflowController {

    private static final Logger logger = LoggerFactory.getLogger(VlmTestWorkflowController.class);

    private final VlmTestSubprocessLauncher launcher;
    private final SubprocessConfigService configService;

    // Track completed results so they can be polled after subprocess finishes
    private final Map<String, VlmTestResult> completedResults = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<VlmTestResult>> activeFutures = new ConcurrentHashMap<>();

    public VlmTestWorkflowController(VlmTestSubprocessLauncher launcher,
                                      @Autowired(required = false) SubprocessConfigService configService) {
        this.launcher = launcher;
        this.configService = configService;
    }

    /**
     * Upload a file and start VLM test processing in a subprocess.
     */
    @PostMapping("/run")
    public ResponseEntity<?> runTest(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "modelId") String modelId,
            @RequestParam(value = "outputFormat", defaultValue = "DOCTAGS") String outputFormat,
            @RequestParam(value = "maxNewTokens", required = false) Integer maxNewTokens,
            @RequestParam(value = "temperature", required = false) Double temperature,
            @RequestParam(value = "topP", required = false) Double topP,
            @RequestParam(value = "pdfRenderDpi", required = false) Integer pdfRenderDpi,
            @RequestParam(value = "pageBatchSize", required = false) Integer pageBatchSize,
            @RequestParam(value = "cudaPinnedHostLimitMb", required = false) Integer cudaPinnedHostLimitMb,
            @RequestParam(value = "kvCacheStrategy", required = false) String kvCacheStrategy,
            @RequestParam(value = "maxKvLen", required = false) Integer maxKvLen,
            @RequestParam(value = "maxPages", required = false) Integer maxPages) {

        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "File is empty"));
            }

            // Save uploaded file to temp location
            String originalName = file.getOriginalFilename();
            String suffix = originalName != null && originalName.contains(".")
                    ? originalName.substring(originalName.lastIndexOf("."))
                    : ".pdf";
            Path tempFile = Files.createTempFile("vlm-test-", suffix);
            file.transferTo(tempFile.toFile());

            String taskId = "vlm-test-" + UUID.randomUUID().toString().substring(0, 8);

            // Build options map
            Map<String, String> options = new HashMap<>();
            if (maxNewTokens != null) options.put("maxNewTokens", String.valueOf(maxNewTokens));
            if (temperature != null) options.put("temperature", String.valueOf(temperature));
            if (topP != null) options.put("topP", String.valueOf(topP));
            if (pdfRenderDpi != null) options.put("pdfRenderDpi", String.valueOf(pdfRenderDpi));
            if (pageBatchSize != null) options.put("pageBatchSize", String.valueOf(pageBatchSize));
            if (cudaPinnedHostLimitMb != null) options.put("cudaPinnedHostLimitMb", String.valueOf(cudaPinnedHostLimitMb));
            if (kvCacheStrategy != null) options.put("kvCacheStrategy", kvCacheStrategy);
            if (maxKvLen != null) options.put("maxKvLen", String.valueOf(maxKvLen));
            if (maxPages != null) options.put("maxPages", String.valueOf(maxPages));

            // Launch subprocess
            CompletableFuture<VlmTestResult> future = launcher.launchTest(
                    taskId, tempFile.toAbsolutePath().toString(), modelId, outputFormat, options);

            activeFutures.put(taskId, future);

            // When complete, move result to completed map
            future.whenComplete((result, error) -> {
                activeFutures.remove(taskId);
                if (result != null) {
                    completedResults.put(taskId, result);
                } else if (error != null) {
                    VlmTestResult failedResult = new VlmTestResult(taskId,
                            tempFile.toAbsolutePath().toString(), "FAILED");
                    failedResult.errorMessage = error.getMessage();
                    completedResults.put(taskId, failedResult);
                }
                // Clean up temp file after a delay
                try { Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
            });

            logger.info("Started VLM test {} for file {} with model {}", taskId, originalName, modelId);

            return ResponseEntity.ok(Map.of(
                    "taskId", taskId,
                    "fileName", originalName != null ? originalName : "unknown",
                    "status", "STARTED",
                    "modelId", modelId,
                    "outputFormat", outputFormat
            ));

        } catch (Exception e) {
            logger.error("Failed to start VLM test", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to start VLM test: " + e.getMessage()
            ));
        }
    }

    /**
     * Get status of a running VLM test.
     */
    @GetMapping("/status/{taskId}")
    public ResponseEntity<?> getStatus(@PathVariable String taskId) {
        // Check if already completed
        VlmTestResult completedResult = completedResults.get(taskId);
        if (completedResult != null) {
            return ResponseEntity.ok(Map.of(
                    "taskId", taskId,
                    "status", completedResult.status,
                    "progressPercent", 100,
                    "currentPhase", "DONE"
            ));
        }

        // Check active subprocess
        VlmTestStatus status = launcher.getStatus(taskId);
        if (status != null) {
            return ResponseEntity.ok(Map.of(
                    "taskId", status.taskId(),
                    "status", status.status(),
                    "progressPercent", status.progressPercent(),
                    "currentPhase", status.currentPhase(),
                    "pagesCompleted", status.pagesCompleted()
            ));
        }

        return ResponseEntity.notFound().build();
    }

    /**
     * Get completed results for a VLM test.
     */
    @GetMapping("/results/{taskId}")
    public ResponseEntity<?> getResults(@PathVariable String taskId) {
        VlmTestResult result = completedResults.get(taskId);
        if (result == null) {
            // Check if still running
            if (launcher.isRunning(taskId)) {
                return ResponseEntity.ok(Map.of(
                        "taskId", taskId,
                        "status", "RUNNING",
                        "message", "Test is still in progress"
                ));
            }
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("taskId", result.taskId);
        response.put("filePath", result.filePath);
        response.put("status", result.status);
        response.put("totalTimeMs", result.totalTimeMs);

        if (result.pageResults != null) {
            response.put("pages", result.pageResults);
        }
        if (result.completionData != null) {
            response.put("performance", result.completionData);
        }
        if (result.phaseDurations != null) {
            response.put("phaseDurations", result.phaseDurations);
        }
        if (result.errorMessage != null) {
            response.put("errorMessage", result.errorMessage);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Cancel a running VLM test.
     */
    @DeleteMapping("/cancel/{taskId}")
    public ResponseEntity<?> cancelTest(@PathVariable String taskId) {
        boolean cancelled = launcher.cancelTest(taskId);
        if (cancelled) {
            return ResponseEntity.ok(Map.of(
                    "taskId", taskId,
                    "status", "CANCELLED",
                    "message", "VLM test cancelled"
            ));
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * Get current VLM subprocess configuration from the centralized SubprocessConfigService.
     */
    @GetMapping("/config")
    public ResponseEntity<?> getConfig() {
        if (configService == null) {
            return ResponseEntity.ok(Map.of(
                    "heapSize", "4g",
                    "offHeapMultiplier", 3,
                    "timeoutMinutes", 30,
                    "javaPath", "java"
            ));
        }
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("heapSize", configService.getVlmHeapSize());
        config.put("offHeapMultiplier", configService.getVlmOffHeapMultiplier());
        config.put("timeoutMinutes", configService.getVlmTimeoutMinutes());
        config.put("javaPath", configService.getJavaPath());
        return ResponseEntity.ok(config);
    }

    /**
     * Update VLM subprocess configuration via SubprocessConfigService (persisted).
     */
    @PostMapping("/config")
    public ResponseEntity<?> updateConfig(@RequestBody Map<String, Object> update) {
        if (configService == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "SubprocessConfigService not available"));
        }

        // Validate before applying
        if (update.containsKey("heapSize")) {
            String heapSize = String.valueOf(update.get("heapSize"));
            if (!heapSize.matches("\\d+[gGmM]")) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid heap size format. Use e.g. '4g', '8g', '16g'"));
            }
        }
        if (update.containsKey("offHeapMultiplier")) {
            int multiplier = ((Number) update.get("offHeapMultiplier")).intValue();
            if (multiplier < 1 || multiplier > 10) {
                return ResponseEntity.badRequest().body(Map.of("error", "Off-heap multiplier must be between 1 and 10"));
            }
        }
        if (update.containsKey("timeoutMinutes")) {
            int timeout = ((Number) update.get("timeoutMinutes")).intValue();
            if (timeout < 1 || timeout > 1440) {
                return ResponseEntity.badRequest().body(Map.of("error", "Timeout must be between 1 and 1440 minutes"));
            }
        }

        // Apply via SubprocessConfigService (persisted)
        SubprocessConfigUpdate configUpdate = new SubprocessConfigUpdate(
                null, // enabled
                update.containsKey("javaPath") ? String.valueOf(update.get("javaPath")) : null,
                null, // heapSize (ingest)
                null, // offHeapMaxBytes
                null, // offHeapMultiplier (ingest)
                null, // timeoutMinutes (ingest)
                update.containsKey("heapSize") ? String.valueOf(update.get("heapSize")) : null, // vlmHeapSize
                update.containsKey("offHeapMultiplier") ? ((Number) update.get("offHeapMultiplier")).intValue() : null, // vlmOffHeapMultiplier
                update.containsKey("timeoutMinutes") ? ((Number) update.get("timeoutMinutes")).intValue() : null, // vlmTimeoutMinutes
                update.containsKey("vlmCudaPinnedHostLimitMb") ? ((Number) update.get("vlmCudaPinnedHostLimitMb")).intValue() : null, // vlmCudaPinnedHostLimitMb
                null, null, // heartbeat, stale
                null, null, null, null, null, // pipeline settings
                null, null, null, null, null, null, // restart config
                null, null, null, null, // stall detection
                null, null, null, null, null, null, null, // native exec config
                null, null, null, null, null, null // memory watchdog thresholds
        );
        configService.updateConfiguration(configUpdate);

        logger.info("Updated VLM subprocess config via SubprocessConfigService: vlmHeapSize={}, vlmOffHeapMultiplier={}, vlmTimeoutMinutes={}",
                configService.getVlmHeapSize(), configService.getVlmOffHeapMultiplier(), configService.getVlmTimeoutMinutes());

        return getConfig();
    }
}
