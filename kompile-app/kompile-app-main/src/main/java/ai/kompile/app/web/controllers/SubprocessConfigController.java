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
import ai.kompile.app.services.subprocess.SubprocessConfigService.SubprocessConfigResponse;
import ai.kompile.app.services.subprocess.SubprocessConfigService.SubprocessConfigUpdate;
import ai.kompile.app.services.subprocess.SubprocessHandle;
import ai.kompile.app.services.subprocess.SubprocessIngestLauncher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
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

    @Autowired
    public SubprocessConfigController(
            @Autowired(required = false) SubprocessConfigService configService,
            @Autowired(required = false) SubprocessIngestLauncher subprocessLauncher
    ) {
        this.configService = configService;
        this.subprocessLauncher = subprocessLauncher;
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
}
