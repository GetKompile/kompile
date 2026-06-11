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

import ai.kompile.app.services.Nd4jEnvironmentConfigService;
import ai.kompile.app.services.subprocess.SubprocessConfigService;
import ai.kompile.app.services.subprocess.SubprocessConfigService.SubprocessConfigResponse;
import ai.kompile.app.services.subprocess.SubprocessConfigService.SubprocessConfigUpdate;
import ai.kompile.app.services.subprocess.SubprocessHandle;
import ai.kompile.app.services.subprocess.SubprocessIngestLauncher;
import ai.kompile.cli.common.util.NativeImageInfo;
import ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl;
import ai.kompile.embedding.anserini.subprocess.EmbeddingSubprocessLauncher;
import ai.kompile.embedding.anserini.subprocess.EmbeddingSubprocessLauncher.DebugConfig;
import ai.kompile.embedding.anserini.subprocess.EmbeddingSubprocessLauncher.DebugMode;
import ai.kompile.embedding.anserini.subprocess.EmbeddingSubprocessLauncher.ToolMode;
import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for managing subprocess ingest configuration.
 * Provides endpoints for viewing, updating, and resetting subprocess settings.
 */
@RestController
@RequestMapping("/api/subprocess-config")
public class SubprocessConfigController {

    private static final Logger log = LoggerFactory.getLogger(SubprocessConfigController.class);

    private final SubprocessConfigService configService;
    private final SubprocessIngestLauncher subprocessLauncher;
    private final AnseriniEmbeddingModelImpl embeddingModel;
    private final Nd4jEnvironmentConfigService nd4jConfigService;

    @Autowired
    public SubprocessConfigController(
            @Autowired(required = false) SubprocessConfigService configService,
            @Autowired(required = false) SubprocessIngestLauncher subprocessLauncher,
            @Autowired(required = false) AnseriniEmbeddingModelImpl embeddingModel,
            @Autowired(required = false) Nd4jEnvironmentConfigService nd4jConfigService
    ) {
        this.configService = configService;
        this.subprocessLauncher = subprocessLauncher;
        this.embeddingModel = embeddingModel;
        this.nd4jConfigService = nd4jConfigService;
    }

    /**
     * Get current subprocess configuration.
     */
    @GetMapping
    public ResponseEntity<SubprocessConfigResponse> getConfiguration() {
        if (configService == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(configService.getConfiguration());
    }

    /**
     * Update subprocess configuration.
     */
    @PostMapping
    public ResponseEntity<SubprocessConfigResponse> updateConfiguration(
            @RequestBody SubprocessConfigUpdate update
    ) {
        if (configService == null) {
            return ResponseEntity.notFound().build();
        }

        log.info("Updating subprocess configuration: {}", update);
        configService.updateConfiguration(update);
        return ResponseEntity.ok(configService.getConfiguration());
    }

    /**
     * Reset subprocess configuration to defaults.
     */
    @PostMapping("/reset")
    public ResponseEntity<SubprocessConfigResponse> resetConfiguration() {
        if (configService == null) {
            return ResponseEntity.notFound().build();
        }

        log.info("Resetting subprocess configuration to defaults");
        configService.resetToDefaults();
        return ResponseEntity.ok(configService.getConfiguration());
    }

    /**
     * Enable subprocess mode.
     */
    @PostMapping("/enable")
    public ResponseEntity<SubprocessConfigResponse> enable() {
        if (configService == null) {
            log.error("Cannot enable subprocess mode: configService is NULL");
            return ResponseEntity.notFound().build();
        }

        log.info("=== SUBPROCESS ENABLE REQUEST RECEIVED ===");
        log.info("Before enable: isEnabled={}", configService.isEnabled());
        configService.setEnabled(true);
        log.info("After enable: isEnabled={}", configService.isEnabled());
        SubprocessConfigResponse response = configService.getConfiguration();
        log.info("Response enabled value: {}", response.enabled());
        return ResponseEntity.ok(response);
    }

    /**
     * Disable subprocess mode (use in-process mode).
     */
    @PostMapping("/disable")
    public ResponseEntity<SubprocessConfigResponse> disable() {
        if (configService == null) {
            return ResponseEntity.notFound().build();
        }

        log.info("Disabling subprocess mode");
        configService.setEnabled(false);
        return ResponseEntity.ok(configService.getConfiguration());
    }

    /**
     * Get available heap size options.
     */
    @GetMapping("/heap-options")
    public ResponseEntity<List<String>> getHeapSizeOptions() {
        if (configService == null) {
            return ResponseEntity.ok(List.of("1g", "2g", "4g", "6g", "8g", "12g", "16g"));
        }
        return ResponseEntity.ok(configService.getHeapSizeOptions());
    }

    /**
     * Get status of all active subprocesses.
     */
    @GetMapping("/active-processes")
    public ResponseEntity<List<SubprocessHandle.SubprocessStatus>> getActiveProcesses() {
        if (subprocessLauncher == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(subprocessLauncher.getAllStatuses());
    }

    /**
     * Get status of a specific subprocess.
     */
    @GetMapping("/active-processes/{taskId}")
    public ResponseEntity<SubprocessHandle.SubprocessStatus> getProcessStatus(
            @PathVariable String taskId
    ) {
        if (subprocessLauncher == null) {
            return ResponseEntity.notFound().build();
        }

        SubprocessHandle.SubprocessStatus status = subprocessLauncher.getStatus(taskId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }

    /**
     * Cancel a running subprocess.
     */
    @PostMapping("/active-processes/{taskId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelProcess(
            @PathVariable String taskId
    ) {
        if (subprocessLauncher == null) {
            return ResponseEntity.notFound().build();
        }

        boolean cancelled = subprocessLauncher.cancelIngest(taskId);
        return ResponseEntity.ok(Map.of(
                "taskId", taskId,
                "cancelled", cancelled,
                "message", cancelled ? "Process cancelled" : "Process not found or already completed"
        ));
    }

    /**
     * Validate Java path.
     */
    @PostMapping("/validate-java-path")
    public ResponseEntity<Map<String, Object>> validateJavaPath(
            @RequestBody Map<String, String> request
    ) {
        String javaPath = request.get("javaPath");
        if (javaPath == null || javaPath.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "valid", false,
                    "error", "Java path is required"
            ));
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(javaPath, "-version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                // Read version info
                String version = new String(process.getInputStream().readAllBytes());
                return ResponseEntity.ok(Map.of(
                        "valid", true,
                        "javaPath", javaPath,
                        "versionInfo", version.trim()
                ));
            } else {
                return ResponseEntity.ok(Map.of(
                        "valid", false,
                        "error", "Java path returned exit code " + exitCode
                ));
            }
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "valid", false,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Get system information relevant to subprocess configuration.
     */
    @GetMapping("/system-info")
    public ResponseEntity<Map<String, Object>> getSystemInfo() {
        Runtime runtime = Runtime.getRuntime();

        Map<String, Object> info = new HashMap<>();
        info.put("availableProcessors", runtime.availableProcessors());
        info.put("maxMemoryMb", runtime.maxMemory() / (1024 * 1024));
        info.put("totalMemoryMb", runtime.totalMemory() / (1024 * 1024));
        info.put("freeMemoryMb", runtime.freeMemory() / (1024 * 1024));
        info.put("usedMemoryMb", (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024));
        info.put("osName", System.getProperty("os.name"));
        info.put("osVersion", System.getProperty("os.version"));
        info.put("osArch", System.getProperty("os.arch"));
        info.put("javaVersion", System.getProperty("java.version"));
        info.put("javaVendor", System.getProperty("java.vendor"));
        info.put("javaHome", System.getProperty("java.home"));
        info.put("userDir", System.getProperty("user.dir"));

        return ResponseEntity.ok(info);
    }

    /**
     * Debug endpoint to verify subprocess configuration state.
     * Returns the current in-memory state and persisted file state.
     */
    @GetMapping("/debug")
    public ResponseEntity<Map<String, Object>> debugConfig() {
        Map<String, Object> debug = new HashMap<>();

        if (configService == null) {
            debug.put("error", "configService is NULL");
            return ResponseEntity.ok(debug);
        }

        debug.put("inMemoryEnabled", configService.isEnabled());
        debug.put("configuration", configService.getConfiguration());
        debug.put("launcherAvailable", subprocessLauncher != null);

        log.info("=== SUBPROCESS DEBUG === inMemoryEnabled={}, launcherAvailable={}",
                configService.isEnabled(), subprocessLauncher != null);

        return ResponseEntity.ok(debug);
    }

    /**
     * Validate a native executable path.
     */
    @PostMapping("/validate-native-executable")
    public ResponseEntity<Map<String, Object>> validateNativeExecutable(
            @RequestBody Map<String, String> request
    ) {
        String executablePath = request.get("executablePath");
        if (executablePath == null || executablePath.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "valid", false,
                    "error", "Executable path is required"
            ));
        }

        try {
            Path path = Paths.get(executablePath);
            if (!Files.exists(path)) {
                return ResponseEntity.ok(Map.of(
                        "valid", false,
                        "error", "File does not exist: " + executablePath
                ));
            }

            if (!Files.isExecutable(path)) {
                return ResponseEntity.ok(Map.of(
                        "valid", false,
                        "error", "File is not executable: " + executablePath
                ));
            }

            // Try to run the executable with --version or --help to verify it's valid
            ProcessBuilder pb = new ProcessBuilder(executablePath, "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Wait with timeout
            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ResponseEntity.ok(Map.of(
                        "valid", false,
                        "error", "Executable timed out when running --version"
                ));
            }

            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.exitValue();

            return ResponseEntity.ok(Map.of(
                    "valid", true,
                    "executablePath", path.toAbsolutePath().toString(),
                    "versionOutput", output.trim(),
                    "exitCode", exitCode
            ));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "valid", false,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Get native image runtime information.
     */
    @GetMapping("/native-image-info")
    public ResponseEntity<Map<String, Object>> getNativeImageInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("runningInNativeImage", NativeImageInfo.isRunningInNativeImage());
        info.put("hasClasspath", NativeImageInfo.hasClasspath());
        info.put("recommendedLaunchMode", NativeImageInfo.getRecommendedLaunchMode().name());

        if (NativeImageInfo.isRunningInNativeImage()) {
            String execPath = NativeImageInfo.getExecutablePath();
            info.put("currentExecutablePath", execPath != null ? execPath : "unknown");
        }

        if (configService != null) {
            info.put("configuredNativeMode", configService.getNativeExecutableMode());
            info.put("resolvedNativeMode", configService.shouldUseNativeExecutableMode());
        }

        return ResponseEntity.ok(info);
    }

    /**
     * Get available native executable mode options.
     */
    @GetMapping("/native-mode-options")
    public ResponseEntity<List<Map<String, String>>> getNativeModeOptions() {
        return ResponseEntity.ok(List.of(
                Map.of("value", "auto", "label", "Auto-detect",
                       "description", "Automatically detect based on runtime context"),
                Map.of("value", "jvm", "label", "JVM/Classpath",
                       "description", "Always use java -cp for subprocess launching"),
                Map.of("value", "native", "label", "Native Executable",
                       "description", "Always use native executable for subprocess launching")
        ));
    }

    // ==================== Subprocess Restart Endpoints ====================
    // These endpoints allow restarting subprocesses with updated environment variables
    // Useful for debugging and applying ND4J configuration changes without full app restart

    /**
     * Restart the embedding subprocess with updated ND4J environment variables.
     * This will:
     * 1. Apply current ND4J configuration to system properties
     * 2. Stop the current embedding subprocess
     * 3. Start a new subprocess that inherits the updated environment
     *
     * @param request Optional request body with additional environment variables to set
     * @return Status of the restart operation
     */
    @PostMapping("/restart-embedding-subprocess")
    public ResponseEntity<Map<String, Object>> restartEmbeddingSubprocess(
            @RequestBody(required = false) Map<String, String> request
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (embeddingModel == null) {
            result.put("success", false);
            result.put("error", "Embedding model not available");
            return ResponseEntity.badRequest().body(result);
        }

        try {
            log.info("=== RESTART EMBEDDING SUBPROCESS REQUESTED ===");

            // Step 1: Apply any additional environment variables from request
            if (request != null && !request.isEmpty()) {
                log.info("Applying {} additional environment variables from request", request.size());
                for (Map.Entry<String, String> entry : request.entrySet()) {
                    String key = entry.getKey();
                    String value = entry.getValue();
                    if (key != null && !key.isBlank() && value != null) {
                        System.setProperty(key, value);
                        log.info("Set system property: {}={}", key, value);
                    }
                }
            }

            // Step 2: Log current ND4J configuration (subprocess will inherit system properties)
            if (nd4jConfigService != null) {
                log.info("Current ND4J configuration: {}", nd4jConfigService.getConfigurationSummary());
            }

            // Step 3: Capture current ND4J environment state before restart
            Map<String, Object> nd4jStateBefore = captureNd4jEnvironmentState();
            result.put("nd4jStateBefore", nd4jStateBefore);

            // Step 4: Restart the subprocess
            log.info("Restarting embedding subprocess...");
            boolean success = embeddingModel.restartWithUpdatedEnvironment();

            // Step 5: Get the subprocess status after restart
            Map<String, Object> subprocessStatus = embeddingModel.getSubprocessStatus();

            result.put("success", success);
            result.put("message", success ?
                    "Embedding subprocess restarted successfully with updated environment" :
                    "Embedding subprocess restart completed but model may not be fully initialized");
            result.put("subprocessStatus", subprocessStatus);

            // Include applied environment variables info
            Map<String, String> appliedEnvVars = new LinkedHashMap<>();
            for (String key : System.getProperties().stringPropertyNames()) {
                if (key.startsWith("org.nd4j.") || key.startsWith("org.bytedeco.") ||
                    key.startsWith("cuda.") || key.startsWith("openblas.") ||
                    key.startsWith("mkl.") || key.startsWith("ND4J_")) {
                    appliedEnvVars.put(key, System.getProperty(key));
                }
            }
            result.put("appliedEnvironmentVariables", appliedEnvVars);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Error restarting embedding subprocess: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * Get the current embedding subprocess status.
     * Returns detailed information about the subprocess state, model loading status,
     * and configuration.
     */
    @GetMapping("/embedding-subprocess-status")
    public ResponseEntity<Map<String, Object>> getEmbeddingSubprocessStatus() {
        if (embeddingModel == null) {
            return ResponseEntity.ok(Map.of(
                    "available", false,
                    "error", "Embedding model not available"
            ));
        }

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("available", true);
        status.putAll(embeddingModel.getSubprocessStatus());

        // Include ND4J environment state
        status.put("nd4jEnvironment", captureNd4jEnvironmentState());

        return ResponseEntity.ok(status);
    }

    /**
     * Get current ND4J environment variables that will be passed to subprocesses.
     * This helps debugging to see what configuration the subprocess will inherit.
     */
    @GetMapping("/nd4j-subprocess-environment")
    public ResponseEntity<Map<String, Object>> getNd4jSubprocessEnvironment() {
        Map<String, Object> result = new LinkedHashMap<>();

        // System properties that will be passed to subprocess
        Map<String, String> nd4jProperties = new LinkedHashMap<>();
        String[] propertyPrefixes = {
            "org.nd4j.",
            "org.bytedeco.",
            "cuda.",
            "cudnn.",
            "openblas.",
            "mkl.",
            "ND4J_"
        };

        for (String key : System.getProperties().stringPropertyNames()) {
            for (String prefix : propertyPrefixes) {
                if (key.startsWith(prefix)) {
                    nd4jProperties.put(key, System.getProperty(key));
                    break;
                }
            }
        }
        result.put("systemProperties", nd4jProperties);

        // Current ND4J environment state (if ND4J is initialized)
        result.put("nd4jEnvironment", captureNd4jEnvironmentState());

        // ND4J config service state (if available)
        if (nd4jConfigService != null) {
            result.put("configServiceConfiguration", nd4jConfigService.getConfigurationSummary());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Update ND4J environment and optionally restart embedding subprocess.
     * This is a convenience endpoint that combines updating ND4J config with restarting.
     *
     * @param request The request containing environment updates and restart flag
     * @return Status of the update and optional restart
     */
    @PostMapping("/update-nd4j-and-restart")
    public ResponseEntity<Map<String, Object>> updateNd4jAndRestart(
            @RequestBody UpdateNd4jAndRestartRequest request
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            log.info("=== UPDATE ND4J AND RESTART REQUESTED ===");

            // Step 1: Apply environment variable updates
            if (request.environmentVariables != null && !request.environmentVariables.isEmpty()) {
                log.info("Applying {} environment variable updates", request.environmentVariables.size());
                for (Map.Entry<String, String> entry : request.environmentVariables.entrySet()) {
                    String key = entry.getKey();
                    String value = entry.getValue();
                    if (key != null && !key.isBlank()) {
                        if (value != null && !value.isBlank()) {
                            System.setProperty(key, value);
                            log.info("Set: {}={}", key, value);
                        } else {
                            System.clearProperty(key);
                            log.info("Cleared: {}", key);
                        }
                    }
                }
                result.put("environmentVariablesUpdated", request.environmentVariables.size());
            }

            // Step 2: Apply ND4J config updates via service
            if (nd4jConfigService != null && request.nd4jConfig != null && !request.nd4jConfig.isEmpty()) {
                log.info("Applying ND4J configuration updates");
                // Update individual settings
                if (request.nd4jConfig.containsKey("maxThreads")) {
                    int maxThreads = ((Number) request.nd4jConfig.get("maxThreads")).intValue();
                    Nd4j.getEnvironment().setMaxThreads(maxThreads);
                }
                if (request.nd4jConfig.containsKey("debug")) {
                    boolean debug = (Boolean) request.nd4jConfig.get("debug");
                    Nd4j.getEnvironment().setDebug(debug);
                }
                if (request.nd4jConfig.containsKey("verbose")) {
                    boolean verbose = (Boolean) request.nd4jConfig.get("verbose");
                    Nd4j.getEnvironment().setVerbose(verbose);
                }
                result.put("nd4jConfigUpdated", true);
            }

            // Step 3: Capture state before restart
            result.put("nd4jStateBefore", captureNd4jEnvironmentState());

            // Step 4: Optionally restart subprocess
            if (request.restartEmbeddingSubprocess && embeddingModel != null) {
                log.info("Restarting embedding subprocess with updated environment...");
                boolean restartSuccess = embeddingModel.restartWithUpdatedEnvironment();
                result.put("restartSuccess", restartSuccess);
                result.put("subprocessStatus", embeddingModel.getSubprocessStatus());
            } else {
                result.put("restartSkipped", true);
                result.put("restartReason", embeddingModel == null ?
                        "Embedding model not available" :
                        "Restart not requested");
            }

            result.put("success", true);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Error updating ND4J and restarting: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * Stop the embedding subprocess.
     * This gracefully shuts down the embedding subprocess.
     */
    @PostMapping("/stop-embedding-subprocess")
    public ResponseEntity<Map<String, Object>> stopEmbeddingSubprocess() {
        Map<String, Object> result = new LinkedHashMap<>();

        if (embeddingModel == null) {
            result.put("success", false);
            result.put("error", "Embedding model not available");
            return ResponseEntity.badRequest().body(result);
        }

        try {
            log.info("Stopping embedding subprocess...");
            embeddingModel.initiateShutdown();
            result.put("success", true);
            result.put("message", "Embedding subprocess stop initiated");
            result.put("subprocessStatus", embeddingModel.getSubprocessStatus());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error stopping embedding subprocess: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * Helper method to capture current ND4J environment state.
     */
    private Map<String, Object> captureNd4jEnvironmentState() {
        Map<String, Object> state = new LinkedHashMap<>();

        try {
            var env = Nd4j.getEnvironment();
            state.put("maxThreads", env.maxThreads());
            state.put("maxMasterThreads", env.maxMasterThreads());
            state.put("debug", env.isDebug());
            state.put("verbose", env.isVerbose());
            state.put("profiling", env.isProfiling());
            state.put("enableBlas", env.isEnableBlas());
            state.put("helpersAllowed", env.helpersAllowed());
            state.put("isCPU", env.isCPU());
            state.put("backend", Nd4j.getBackend().getClass().getSimpleName());

            // Memory limits - no direct getters available, leave them out
            // state.put("maxPrimaryMemory", ...);
            // state.put("maxSpecialMemory", ...);
            // state.put("maxDeviceMemory", ...);

        } catch (Exception e) {
            state.put("error", "ND4J not initialized or error accessing environment: " + e.getMessage());
        }

        return state;
    }

    /**
     * Request body for update-nd4j-and-restart endpoint.
     */
    public static class UpdateNd4jAndRestartRequest {
        public Map<String, String> environmentVariables;
        public Map<String, Object> nd4jConfig;
        public boolean restartEmbeddingSubprocess = true;
    }

    // ==================== Debug Mode Endpoints ====================
    // These endpoints allow configuring debug modes for the embedding subprocess
    // Useful for debugging CUDA issues, memory leaks, and native library problems

    /**
     * Get available debug options for subprocess.
     * Returns:
     * - toolModes: Mutually exclusive tools that wrap the JVM (valgrind, compute-sanitizer, etc.)
     * - additiveOptions: JVM options that can be combined (verbose-jni, native-memory-tracking, etc.)
     */
    @GetMapping("/debug-modes")
    public ResponseEntity<Map<String, Object>> getAvailableDebugModes() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Tool modes - mutually exclusive (only one can wrap the JVM)
        List<Map<String, Object>> toolModes = new ArrayList<>();
        for (ToolMode mode : ToolMode.values()) {
            Map<String, Object> modeInfo = new LinkedHashMap<>();
            modeInfo.put("value", mode.getValue());
            modeInfo.put("name", mode.name());
            modeInfo.put("description", mode.getDescription());
            modeInfo.put("requiresCuda", mode.requiresCuda());
            toolModes.add(modeInfo);
        }
        result.put("toolModes", toolModes);

        // Additive options - can enable multiple simultaneously
        List<Map<String, Object>> additiveOptions = new ArrayList<>();
        additiveOptions.add(Map.of(
            "key", "verboseJni",
            "label", "Verbose JNI",
            "description", "Enable verbose JNI logging (-verbose:jni)",
            "jvmArg", "-verbose:jni"
        ));
        additiveOptions.add(Map.of(
            "key", "nativeMemoryTracking",
            "label", "Native Memory Tracking",
            "description", "Enable JVM Native Memory Tracking (-XX:NativeMemoryTracking=detail)",
            "jvmArg", "-XX:NativeMemoryTracking=detail"
        ));
        additiveOptions.add(Map.of(
            "key", "extensiveErrorReports",
            "label", "Extensive Error Reports",
            "description", "Enable JVM extensive error reports (-XX:+ExtensiveErrorReports)",
            "jvmArg", "-XX:+ExtensiveErrorReports"
        ));
        additiveOptions.add(Map.of(
            "key", "disableJit",
            "label", "Disable JIT",
            "description", "Disable JIT compilation for debugging (-Djava.compiler=NONE). Auto-enabled for valgrind/asan/compute-sanitizer.",
            "jvmArg", "-Djava.compiler=NONE"
        ));
        result.put("additiveOptions", additiveOptions);

        // Valgrind-specific options
        Map<String, Object> valgrindOptions = new LinkedHashMap<>();
        valgrindOptions.put("generateSuppressions", Map.of(
            "key", "generateValgrindSuppressions",
            "label", "Auto-generate Suppression File",
            "description", "Dynamically generate valgrind suppression file for libjvm.so to focus on libnd4j issues",
            "default", true
        ));
        valgrindOptions.put("libnd4jSuppressionFile", Map.of(
            "key", "libnd4jSuppressionFile",
            "label", "External Suppression File",
            "description", "Path to additional suppression file (e.g., valgrind_libnd4j.supp)",
            "default", ""
        ));
        result.put("valgrindOptions", valgrindOptions);

        // Nsys (NVIDIA Nsight Systems) specific options
        Map<String, Object> nsysOptions = new LinkedHashMap<>();
        nsysOptions.put("outputFile", Map.of(
            "key", "nsysOutputFile",
            "label", "Output File Name",
            "description", "Custom output file name (without .nsys-rep extension). Defaults to embedding_subprocess_<timestamp>",
            "default", ""
        ));
        nsysOptions.put("stats", Map.of(
            "key", "nsysStats",
            "label", "Show Stats Summary",
            "description", "Show statistics summary after profiling completes",
            "default", true
        ));
        nsysOptions.put("cudaMemoryUsage", Map.of(
            "key", "nsysCudaMemoryUsage",
            "label", "Track CUDA Memory",
            "description", "Track CUDA memory allocation and deallocation",
            "default", true
        ));
        nsysOptions.put("forceOverwrite", Map.of(
            "key", "nsysForceOverwrite",
            "label", "Force Overwrite",
            "description", "Overwrite existing output files",
            "default", true
        ));
        nsysOptions.put("traceOptions", Map.of(
            "key", "nsysTraceOptions",
            "label", "Trace Options",
            "description", "Comma-separated list of trace targets: cuda, nvtx, cudnn, cublas, osrt, nvenc, nvdec",
            "default", "cuda,nvtx,cudnn,cublas"
        ));
        nsysOptions.put("duration", Map.of(
            "key", "nsysDuration",
            "label", "Duration (seconds)",
            "description", "Profiling duration in seconds. 0 = profile until process exit",
            "default", 0
        ));
        nsysOptions.put("waitForCuda", Map.of(
            "key", "nsysWaitForCuda",
            "label", "Wait for CUDA Init",
            "description", "Wait for CUDA to initialize before starting profiling (reduces startup noise)",
            "default", true
        ));
        nsysOptions.put("extraArgs", Map.of(
            "key", "nsysExtraArgs",
            "label", "Extra Arguments",
            "description", "Additional nsys command-line arguments",
            "default", ""
        ));
        result.put("nsysOptions", nsysOptions);

        // Nsys modes available
        result.put("nsysModeDescriptions", Map.of(
            "nsys", "Basic profiling with configured trace options",
            "nsys-profile", "Detailed profiling including CPU sampling, GPU metrics, and OS runtime",
            "nsys-cuda", "CUDA-focused profiling with comprehensive CUDA tracing and GPU metrics"
        ));

        // Legacy debug modes (deprecated, for backwards compatibility)
        List<Map<String, Object>> legacyModes = new ArrayList<>();
        for (DebugMode mode : DebugMode.values()) {
            Map<String, Object> modeInfo = new LinkedHashMap<>();
            modeInfo.put("value", mode.getValue());
            modeInfo.put("name", mode.name());
            modeInfo.put("description", mode.getDescription());
            modeInfo.put("requiresCuda", mode.requiresCuda());
            modeInfo.put("deprecated", true);
            legacyModes.add(modeInfo);
        }
        result.put("legacyModes", legacyModes);

        return ResponseEntity.ok(result);
    }

    /**
     * Get the current debug configuration for the embedding subprocess.
     * Returns the full configuration including tool mode, additive options,
     * system environment variables, and ND4J environment configuration.
     */
    @GetMapping("/debug-config")
    public ResponseEntity<Map<String, Object>> getDebugConfig() {
        Map<String, Object> result = new LinkedHashMap<>();

        if (embeddingModel == null) {
            result.put("available", false);
            result.put("error", "Embedding model not available");
            return ResponseEntity.ok(result);
        }

        try {
            result.put("available", true);

            // Current tool mode (mutually exclusive)
            result.put("toolMode", currentDebugConfig.getToolMode().getValue());
            result.put("toolModeDescription", currentDebugConfig.getToolMode().getDescription());

            // Additive options (can combine multiple)
            Map<String, Boolean> additiveOptions = new LinkedHashMap<>();
            additiveOptions.put("verboseJni", currentDebugConfig.isVerboseJni());
            additiveOptions.put("nativeMemoryTracking", currentDebugConfig.isNativeMemoryTracking());
            additiveOptions.put("extensiveErrorReports", currentDebugConfig.isExtensiveErrorReports());
            additiveOptions.put("disableJit", currentDebugConfig.isDisableJit());
            result.put("additiveOptions", additiveOptions);

            // Valgrind suppression settings
            Map<String, Object> valgrindSettings = new LinkedHashMap<>();
            valgrindSettings.put("generateSuppressions", currentDebugConfig.isGenerateValgrindSuppressions());
            valgrindSettings.put("libnd4jSuppressionFile", currentDebugConfig.getLibnd4jSuppressionFile());
            result.put("valgrindSettings", valgrindSettings);

            // Nsys (NVIDIA Nsight Systems) settings
            Map<String, Object> nsysSettings = new LinkedHashMap<>();
            nsysSettings.put("outputFile", currentDebugConfig.getNsysOutputFile());
            nsysSettings.put("stats", currentDebugConfig.isNsysStats());
            nsysSettings.put("cudaMemoryUsage", currentDebugConfig.isNsysCudaMemoryUsage());
            nsysSettings.put("forceOverwrite", currentDebugConfig.isNsysForceOverwrite());
            nsysSettings.put("traceOptions", currentDebugConfig.getNsysTraceOptions());
            nsysSettings.put("duration", currentDebugConfig.getNsysDuration());
            nsysSettings.put("waitForCuda", currentDebugConfig.isNsysWaitForCuda());
            nsysSettings.put("extraArgs", currentDebugConfig.getNsysExtraArgs());
            result.put("nsysSettings", nsysSettings);

            // Log directory
            result.put("logDirectory", currentDebugConfig.getLogDirectory());

            // Extra JVM args
            result.put("extraJvmArgs", currentDebugConfig.getExtraJvmArgs());

            // System environment variables (LD_PRELOAD, MALLOC_CHECK_, etc.)
            result.put("systemEnvironmentVariables", currentDebugConfig.getSystemEnvironmentVariables());

            // ND4J environment configuration (Nd4j.getEnvironment() settings)
            result.put("nd4jEnvironmentConfig", currentDebugConfig.getNd4jEnvironmentConfig());

            // What will be applied (preview)
            result.put("commandPrefixPreview", currentDebugConfig.buildCommandPrefix());
            result.put("jvmArgsPreview", currentDebugConfig.buildJvmArgs());
            result.put("envVarsPreview", currentDebugConfig.buildEnvironmentVariables());

            // Current subprocess status
            result.put("subprocessStatus", embeddingModel.getSubprocessStatus());

            // Current ND4J environment state
            result.put("nd4jEnvironmentState", captureNd4jEnvironmentState());

        } catch (Exception e) {
            log.error("Error getting debug config: {}", e.getMessage(), e);
            result.put("error", e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Set the debug configuration for the embedding subprocess.
     * This will be applied on the next restart.
     *
     * Supports:
     * - toolMode: Mutually exclusive tool (valgrind, compute-sanitizer, etc.)
     * - Additive options: verboseJni, nativeMemoryTracking, extensiveErrorReports, disableJit
     * - Valgrind settings: generateSuppressions, libnd4jSuppressionFile
     * - System environment variables (LD_PRELOAD, MALLOC_CHECK_, etc.)
     * - ND4J environment configuration (separate from system env vars)
     *
     * @param request The debug configuration request
     * @return Status of the operation with preview of what will be applied
     */
    @PostMapping("/debug-config")
    public ResponseEntity<Map<String, Object>> setDebugConfig(
            @RequestBody SetDebugConfigRequest request
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (embeddingModel == null) {
            result.put("success", false);
            result.put("error", "Embedding model not available");
            return ResponseEntity.badRequest().body(result);
        }

        try {
            log.info("Setting debug config: toolMode={}, additiveOptions=[verboseJni={}, nmt={}, errorReports={}, disableJit={}]",
                request.toolMode, request.verboseJni, request.nativeMemoryTracking,
                request.extensiveErrorReports, request.disableJit);

            // Create new debug config with tool mode
            ToolMode toolMode = ToolMode.fromValue(request.toolMode);
            DebugConfig debugConfig = new DebugConfig(toolMode);

            // Set additive options
            debugConfig.setVerboseJni(request.verboseJni);
            debugConfig.setNativeMemoryTracking(request.nativeMemoryTracking);
            debugConfig.setExtensiveErrorReports(request.extensiveErrorReports);
            debugConfig.setDisableJit(request.disableJit);

            // Valgrind suppression settings
            debugConfig.setGenerateValgrindSuppressions(request.generateValgrindSuppressions);
            if (request.libnd4jSuppressionFile != null) {
                debugConfig.setLibnd4jSuppressionFile(request.libnd4jSuppressionFile);
            }

            // Nsys (NVIDIA Nsight Systems) settings
            if (request.nsysOutputFile != null) {
                debugConfig.setNsysOutputFile(request.nsysOutputFile);
            }
            debugConfig.setNsysStats(request.nsysStats);
            debugConfig.setNsysCudaMemoryUsage(request.nsysCudaMemoryUsage);
            debugConfig.setNsysForceOverwrite(request.nsysForceOverwrite);
            if (request.nsysTraceOptions != null) {
                debugConfig.setNsysTraceOptions(request.nsysTraceOptions);
            }
            debugConfig.setNsysDuration(request.nsysDuration);
            debugConfig.setNsysWaitForCuda(request.nsysWaitForCuda);
            if (request.nsysExtraArgs != null) {
                debugConfig.setNsysExtraArgs(request.nsysExtraArgs);
            }

            // Log directory
            if (request.logDirectory != null && !request.logDirectory.isBlank()) {
                debugConfig.setLogDirectory(request.logDirectory);
            }

            // Extra JVM args
            if (request.extraJvmArgs != null) {
                debugConfig.setExtraJvmArgs(request.extraJvmArgs);
            }

            // System environment variables (LD_PRELOAD, MALLOC_CHECK_, etc.)
            if (request.systemEnvironmentVariables != null) {
                debugConfig.setSystemEnvironmentVariables(request.systemEnvironmentVariables);
            }

            // ND4J environment configuration (Nd4j.getEnvironment() settings)
            if (request.nd4jEnvironmentConfig != null) {
                debugConfig.setNd4jEnvironmentConfig(request.nd4jEnvironmentConfig);
            }

            // Store the config to be used on next restart
            currentDebugConfig = debugConfig;

            result.put("success", true);
            result.put("toolMode", toolMode.getValue());
            result.put("toolModeDescription", toolMode.getDescription());
            result.put("configDescription", debugConfig.getDescription());
            result.put("message", "Debug configuration set. Restart subprocess to apply.");

            // Preview what will be applied
            List<String> commandPrefix = debugConfig.buildCommandPrefix();
            result.put("commandPrefix", commandPrefix);
            result.put("jvmArgs", debugConfig.buildJvmArgs());
            result.put("systemEnvVars", debugConfig.buildEnvironmentVariables());

            // Notify user about generated suppression files
            if (toolMode == ToolMode.VALGRIND || toolMode == ToolMode.VALGRIND_MINIMAL) {
                Map<String, Object> suppressionInfo = new LinkedHashMap<>();
                suppressionInfo.put("willGenerateDynamicSuppression", request.generateValgrindSuppressions);
                if (request.generateValgrindSuppressions) {
                    suppressionInfo.put("dynamicSuppressionNote",
                        "A dynamic suppression file will be generated at runtime for libjvm.so. " +
                        "This suppresses JVM internal errors to focus on libnd4j issues. " +
                        "The file will be created in /tmp and cleaned up after use.");
                }
                if (request.libnd4jSuppressionFile != null && !request.libnd4jSuppressionFile.isBlank()) {
                    suppressionInfo.put("externalSuppressionFile", request.libnd4jSuppressionFile);
                }
                result.put("valgrindSuppressionInfo", suppressionInfo);
            }

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Error setting debug config: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    // Store the pending debug config
    private volatile DebugConfig currentDebugConfig = new DebugConfig();

    /**
     * Restart the embedding subprocess with the current debug configuration.
     * This combines setting the debug mode and restarting in one call.
     *
     * The full process includes:
     * 1. Parse and validate debug configuration
     * 2. Apply ND4J environment configuration to Nd4j.getEnvironment()
     * 3. Set system properties that subprocess will inherit
     * 4. Generate valgrind suppression files if needed (user is notified)
     * 5. Build command prefix, JVM args, and environment variables
     * 6. Restart the subprocess with all configuration applied
     *
     * @param request The restart request with debug options
     * @return Status of the operation with details about what was applied
     */
    @PostMapping("/restart-with-debug")
    public ResponseEntity<Map<String, Object>> restartWithDebug(
            @RequestBody RestartWithDebugRequest request
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (embeddingModel == null) {
            result.put("success", false);
            result.put("error", "Embedding model not available");
            return ResponseEntity.badRequest().body(result);
        }

        try {
            log.info("=== RESTART WITH DEBUG CONFIGURATION REQUESTED ===");
            log.info("Tool mode: {}", request.toolMode);
            log.info("Additive options: verboseJni={}, nmt={}, errorReports={}, disableJit={}",
                request.verboseJni, request.nativeMemoryTracking,
                request.extensiveErrorReports, request.disableJit);

            // Parse and create debug config
            ToolMode toolMode = ToolMode.fromValue(request.toolMode);
            DebugConfig debugConfig = new DebugConfig(toolMode);

            // Set additive options
            debugConfig.setVerboseJni(request.verboseJni);
            debugConfig.setNativeMemoryTracking(request.nativeMemoryTracking);
            debugConfig.setExtensiveErrorReports(request.extensiveErrorReports);
            debugConfig.setDisableJit(request.disableJit);

            // Valgrind suppression settings
            debugConfig.setGenerateValgrindSuppressions(request.generateValgrindSuppressions);
            if (request.libnd4jSuppressionFile != null) {
                debugConfig.setLibnd4jSuppressionFile(request.libnd4jSuppressionFile);
            }

            // Nsys (NVIDIA Nsight Systems) settings
            if (request.nsysOutputFile != null) {
                debugConfig.setNsysOutputFile(request.nsysOutputFile);
            }
            debugConfig.setNsysStats(request.nsysStats);
            debugConfig.setNsysCudaMemoryUsage(request.nsysCudaMemoryUsage);
            debugConfig.setNsysForceOverwrite(request.nsysForceOverwrite);
            if (request.nsysTraceOptions != null) {
                debugConfig.setNsysTraceOptions(request.nsysTraceOptions);
            }
            debugConfig.setNsysDuration(request.nsysDuration);
            debugConfig.setNsysWaitForCuda(request.nsysWaitForCuda);
            if (request.nsysExtraArgs != null) {
                debugConfig.setNsysExtraArgs(request.nsysExtraArgs);
            }

            // Log directory
            if (request.logDirectory != null && !request.logDirectory.isBlank()) {
                debugConfig.setLogDirectory(request.logDirectory);
            }

            // Extra JVM args
            if (request.extraJvmArgs != null) {
                debugConfig.setExtraJvmArgs(request.extraJvmArgs);
            }

            // System environment variables (LD_PRELOAD, MALLOC_CHECK_, etc.)
            if (request.systemEnvironmentVariables != null) {
                debugConfig.setSystemEnvironmentVariables(request.systemEnvironmentVariables);
            }

            // ND4J environment configuration (Nd4j.getEnvironment() settings)
            if (request.nd4jEnvironmentConfig != null) {
                debugConfig.setNd4jEnvironmentConfig(request.nd4jEnvironmentConfig);
            }

            // Store the config
            currentDebugConfig = debugConfig;

            // ====== STEP 1: Apply ND4J environment configuration ======
            Map<String, Object> nd4jChanges = new LinkedHashMap<>();
            if (request.nd4jEnvironmentConfig != null && !request.nd4jEnvironmentConfig.isEmpty()) {
                log.info("Applying ND4J environment configuration...");
                var nd4jEnv = Nd4j.getEnvironment();

                if (request.nd4jEnvironmentConfig.containsKey("maxThreads")) {
                    int val = ((Number) request.nd4jEnvironmentConfig.get("maxThreads")).intValue();
                    nd4jEnv.setMaxThreads(val);
                    nd4jChanges.put("maxThreads", val);
                    log.info("Set ND4J maxThreads={}", val);
                }
                if (request.nd4jEnvironmentConfig.containsKey("maxMasterThreads")) {
                    int val = ((Number) request.nd4jEnvironmentConfig.get("maxMasterThreads")).intValue();
                    nd4jEnv.setMaxMasterThreads(val);
                    nd4jChanges.put("maxMasterThreads", val);
                    log.info("Set ND4J maxMasterThreads={}", val);
                }
                if (request.nd4jEnvironmentConfig.containsKey("debug")) {
                    boolean val = (Boolean) request.nd4jEnvironmentConfig.get("debug");
                    nd4jEnv.setDebug(val);
                    nd4jChanges.put("debug", val);
                    log.info("Set ND4J debug={}", val);
                }
                if (request.nd4jEnvironmentConfig.containsKey("verbose")) {
                    boolean val = (Boolean) request.nd4jEnvironmentConfig.get("verbose");
                    nd4jEnv.setVerbose(val);
                    nd4jChanges.put("verbose", val);
                    log.info("Set ND4J verbose={}", val);
                }
                if (request.nd4jEnvironmentConfig.containsKey("profiling")) {
                    boolean val = (Boolean) request.nd4jEnvironmentConfig.get("profiling");
                    nd4jEnv.setProfiling(val);
                    nd4jChanges.put("profiling", val);
                    log.info("Set ND4J profiling={}", val);
                }
            }
            result.put("nd4jEnvironmentChangesApplied", nd4jChanges);

            // ====== STEP 2: Set system properties (subprocess will inherit) ======
            Map<String, String> systemPropsApplied = new LinkedHashMap<>();
            if (request.systemProperties != null && !request.systemProperties.isEmpty()) {
                log.info("Setting system properties...");
                for (Map.Entry<String, String> entry : request.systemProperties.entrySet()) {
                    if (entry.getKey() != null && !entry.getKey().isBlank() && entry.getValue() != null) {
                        System.setProperty(entry.getKey(), entry.getValue());
                        systemPropsApplied.put(entry.getKey(), entry.getValue());
                        log.info("Set system property: {}={}", entry.getKey(), entry.getValue());
                    }
                }
            }
            result.put("systemPropertiesApplied", systemPropsApplied);

            // Log current ND4J config summary
            if (nd4jConfigService != null) {
                log.info("Current ND4J configuration: {}", nd4jConfigService.getConfigurationSummary());
            }

            // ====== STEP 3: Build command prefix, JVM args, env vars ======
            List<String> commandPrefix = debugConfig.buildCommandPrefix();
            List<String> jvmArgs = debugConfig.buildJvmArgs();
            Map<String, String> envVars = debugConfig.buildEnvironmentVariables();

            result.put("toolMode", toolMode.getValue());
            result.put("toolModeDescription", toolMode.getDescription());
            result.put("configDescription", debugConfig.getDescription());
            result.put("commandPrefix", commandPrefix);
            result.put("jvmArgs", jvmArgs);
            result.put("systemEnvVars", envVars);

            // ====== STEP 4: Notify user about generated suppression files ======
            if (toolMode == ToolMode.VALGRIND || toolMode == ToolMode.VALGRIND_MINIMAL) {
                Map<String, Object> suppressionInfo = new LinkedHashMap<>();
                if (request.generateValgrindSuppressions) {
                    suppressionInfo.put("generatingDynamicSuppression", true);
                    suppressionInfo.put("description",
                        "Dynamic valgrind suppression file will be generated for libjvm.so at: /tmp/valgrind_dynamic_<pid>.supp");
                    suppressionInfo.put("purpose",
                        "Suppresses JVM internal errors (Addr1-8, Value1-8, Jump, Cond, Leak) to focus on libnd4j issues");
                    suppressionInfo.put("cleanup", "File will be automatically deleted after subprocess exits");

                    // Show suppression types being generated
                    suppressionInfo.put("errorTypesSuppressed",
                        List.of("Addr1", "Addr2", "Addr4", "Addr8", "Value1", "Value2", "Value4", "Value8", "Jump", "Cond"));
                    suppressionInfo.put("leakKindsSuppressed",
                        List.of("definite", "possible", "reachable", "indirect"));
                }

                if (request.libnd4jSuppressionFile != null && !request.libnd4jSuppressionFile.isBlank()) {
                    suppressionInfo.put("externalSuppressionFile", request.libnd4jSuppressionFile);
                    suppressionInfo.put("externalFileNote",
                        "Additional suppressions will be loaded from this file to further filter non-libnd4j errors");
                }

                result.put("valgrindSuppressionInfo", suppressionInfo);
                log.info("Valgrind suppression info: {}", suppressionInfo);
            }

            // ====== STEP 5: Restart the subprocess ======
            log.info("Restarting subprocess with debug configuration...");
            log.info("Command prefix: {}", commandPrefix);
            log.info("JVM args: {}", jvmArgs);
            log.info("Environment variables: {}", envVars);

            boolean success = embeddingModel.restartWithUpdatedEnvironment();

            result.put("success", success);
            result.put("message", success ?
                "Subprocess restarted with debug configuration: " + debugConfig.getDescription() :
                "Restart completed but model may not be fully initialized");
            result.put("subprocessStatus", embeddingModel.getSubprocessStatus());

            // Include current ND4J environment state
            result.put("nd4jEnvironmentState", captureNd4jEnvironmentState());

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Error restarting with debug: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * Request body for set-debug-config endpoint.
     * Supports the full debug configuration including tool mode, additive options,
     * valgrind settings, nsys settings, and clear separation of system env vars vs ND4J env config.
     */
    public static class SetDebugConfigRequest {
        // Tool mode (mutually exclusive - only one can wrap the JVM)
        public String toolMode = "none";

        // Additive JVM options (can combine multiple)
        public boolean verboseJni = false;
        public boolean nativeMemoryTracking = false;
        public boolean extensiveErrorReports = false;
        public boolean disableJit = false;

        // Valgrind-specific settings
        public boolean generateValgrindSuppressions = true;
        public String libnd4jSuppressionFile;

        // Nsys (NVIDIA Nsight Systems) settings
        public String nsysOutputFile;  // Custom output file name (without extension)
        public boolean nsysStats = true;  // Show stats summary after profiling
        public boolean nsysCudaMemoryUsage = true;  // Track CUDA memory usage
        public boolean nsysForceOverwrite = true;  // Overwrite existing output files
        public String nsysTraceOptions = "cuda,nvtx,cudnn,cublas";  // What to trace
        public int nsysDuration = 0;  // Duration in seconds (0 = until process exit)
        public boolean nsysWaitForCuda = true;  // Wait for CUDA initialization
        public String nsysExtraArgs;  // Additional nsys arguments

        // Log directory
        public String logDirectory = "./logs/debug";

        // Extra JVM args
        public List<String> extraJvmArgs;

        // System environment variables (LD_PRELOAD, MALLOC_CHECK_, ASAN_OPTIONS, etc.)
        // These are passed to the subprocess process environment
        public Map<String, String> systemEnvironmentVariables;

        // ND4J environment configuration (Nd4j.getEnvironment() settings)
        // This is SEPARATE from system env vars - these configure ND4J runtime
        // Supported keys: maxThreads, maxMasterThreads, debug, verbose, profiling
        public Map<String, Object> nd4jEnvironmentConfig;

        // Legacy field for backwards compatibility
        @Deprecated
        public String mode;
        @Deprecated
        public Map<String, String> environmentVariables;
    }

    /**
     * Request body for restart-with-debug endpoint.
     * Same structure as SetDebugConfigRequest plus systemProperties for JVM inheritance.
     */
    public static class RestartWithDebugRequest {
        // Tool mode (mutually exclusive - only one can wrap the JVM)
        public String toolMode = "none";

        // Additive JVM options (can combine multiple)
        public boolean verboseJni = false;
        public boolean nativeMemoryTracking = false;
        public boolean extensiveErrorReports = false;
        public boolean disableJit = false;

        // Valgrind-specific settings
        public boolean generateValgrindSuppressions = true;
        public String libnd4jSuppressionFile;

        // Nsys (NVIDIA Nsight Systems) settings
        public String nsysOutputFile;  // Custom output file name (without extension)
        public boolean nsysStats = true;  // Show stats summary after profiling
        public boolean nsysCudaMemoryUsage = true;  // Track CUDA memory usage
        public boolean nsysForceOverwrite = true;  // Overwrite existing output files
        public String nsysTraceOptions = "cuda,nvtx,cudnn,cublas";  // What to trace
        public int nsysDuration = 0;  // Duration in seconds (0 = until process exit)
        public boolean nsysWaitForCuda = true;  // Wait for CUDA initialization
        public String nsysExtraArgs;  // Additional nsys arguments

        // Log directory
        public String logDirectory = "./logs/debug";

        // Extra JVM args
        public List<String> extraJvmArgs;

        // System environment variables (LD_PRELOAD, MALLOC_CHECK_, ASAN_OPTIONS, etc.)
        // These are passed to the subprocess process environment
        public Map<String, String> systemEnvironmentVariables;

        // ND4J environment configuration (Nd4j.getEnvironment() settings)
        // This is SEPARATE from system env vars - these configure ND4J runtime
        // Supported keys: maxThreads, maxMasterThreads, debug, verbose, profiling
        public Map<String, Object> nd4jEnvironmentConfig;

        // System properties to set before restart (subprocess inherits via ProcessBuilder)
        public Map<String, String> systemProperties;

        // Legacy fields for backwards compatibility
        @Deprecated
        public String debugMode;
        @Deprecated
        public Map<String, String> environmentVariables;
    }
}
