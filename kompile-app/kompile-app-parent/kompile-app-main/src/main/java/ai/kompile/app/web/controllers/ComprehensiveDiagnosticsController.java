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

import ai.kompile.app.diagnostics.dto.AllocationEntry;
import ai.kompile.app.diagnostics.dto.AllocationLogSummary;
import ai.kompile.app.diagnostics.service.AllocationLogParser;
import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * Comprehensive diagnostics controller providing a unified endpoint for ALL debugging information.
 *
 * Integrates:
 * - ADR 53: Allocation logging (NDArray and OpContext allocations)
 *   - ALWAYS ACTIVE in functrace builds - no runtime toggles
 *   - Read-only access to allocation logs
 *   - Automatic session-based logging (PID-based)
 * - ADR 52: Operation execution logging
 * - ADR 51: Lifecycle tracking (memory leak detection)
 * - Cache statistics (Shape and TAD caches)
 * - Lifecycle tracking configuration
 * - Model debugging information
 *
 * This simplifies usage of gcc functrace allocation tracking by providing
 * a single endpoint that returns everything needed for debugging.
 *
 * IMPORTANT: Allocation logging (ADR 53) is a build-time feature, not a runtime toggle.
 * When built with -Dlibnd4j.calltrace=ON, allocation logging is automatically enabled.
 * This controller only provides read-only access to view the logs.
 */
@RestController
@RequestMapping("/api/diagnostics")
public class ComprehensiveDiagnosticsController {

    private static final Logger logger = LoggerFactory.getLogger(ComprehensiveDiagnosticsController.class);

    @Autowired
    private AllocationLogParser allocationLogParser;

    /**
     * Get ALL diagnostic information in a single call.
     * This is the main endpoint that consolidates all debugging data.
     *
     * @param includeAllocationDetails Whether to include detailed allocation entries (default: false)
     * @param topAllocations Number of largest allocations to include (default: 20)
     * @param topAllocationSites Number of top allocation sites to include (default: 10)
     * @return Comprehensive diagnostic report
     */
    @GetMapping("/comprehensive")
    public ResponseEntity<Map<String, Object>> getComprehensiveDiagnostics(
            @RequestParam(defaultValue = "false") boolean includeAllocationDetails,
            @RequestParam(defaultValue = "20") int topAllocations,
            @RequestParam(defaultValue = "10") int topAllocationSites) {

        Map<String, Object> response = new LinkedHashMap<>();

        try {
            // 1. System Information
            Map<String, Object> systemInfo = new LinkedHashMap<>();
            systemInfo.put("pid", ProcessHandle.current().pid());
            systemInfo.put("timestamp", System.currentTimeMillis());
            systemInfo.put("timestampIso", new Date().toString());
            systemInfo.put("javaVersion", System.getProperty("java.version"));
            systemInfo.put("javaVendor", System.getProperty("java.vendor"));
            systemInfo.put("osName", System.getProperty("os.name"));
            systemInfo.put("osArch", System.getProperty("os.arch"));
            systemInfo.put("osVersion", System.getProperty("os.version"));

            Runtime runtime = Runtime.getRuntime();
            systemInfo.put("availableProcessors", runtime.availableProcessors());
            systemInfo.put("totalMemoryMB", runtime.totalMemory() / (1024 * 1024));
            systemInfo.put("freeMemoryMB", runtime.freeMemory() / (1024 * 1024));
            systemInfo.put("maxMemoryMB", runtime.maxMemory() / (1024 * 1024));
            systemInfo.put("usedMemoryMB", (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024));

            response.put("system", systemInfo);

            // 2. Lifecycle Tracking Configuration (ADR 51)
            Map<String, Object> lifecycleConfig = new LinkedHashMap<>();
            lifecycleConfig.put("enabled", Nd4j.getEnvironment().isLifecycleTracking());
            lifecycleConfig.put("trackViews", Nd4j.getEnvironment().isTrackViews());
            lifecycleConfig.put("trackDeletions", Nd4j.getEnvironment().isTrackDeletions());
            lifecycleConfig.put("snapshotFiles", Nd4j.getEnvironment().isSnapshotFiles());
            lifecycleConfig.put("trackOperations", Nd4j.getEnvironment().isTrackOperations());
            lifecycleConfig.put("stackDepth", Nd4j.getEnvironment().getStackDepth());
            lifecycleConfig.put("reportInterval", Nd4j.getEnvironment().getReportInterval());
            lifecycleConfig.put("maxDeletionHistory", Nd4j.getEnvironment().getMaxDeletionHistory());
            response.put("lifecycleTracking", lifecycleConfig);

            // 3. Cache Statistics
            Map<String, Object> cacheStats = new LinkedHashMap<>();

            // Shape cache
            Map<String, Object> shapeCache = new LinkedHashMap<>();
            shapeCache.put("currentEntries", Nd4j.getNativeOps().getShapeCachedEntries());
            shapeCache.put("currentBytes", Nd4j.getNativeOps().getShapeCachedBytes());
            shapeCache.put("currentMB", String.format("%.2f", Nd4j.getNativeOps().getShapeCachedBytes() / (1024.0 * 1024.0)));
            shapeCache.put("peakEntries", Nd4j.getNativeOps().getShapePeakCachedEntries());
            shapeCache.put("peakBytes", Nd4j.getNativeOps().getShapePeakCachedBytes());
            shapeCache.put("peakMB", String.format("%.2f", Nd4j.getNativeOps().getShapePeakCachedBytes() / (1024.0 * 1024.0)));

            // TAD cache
            Map<String, Object> tadCache = new LinkedHashMap<>();
            tadCache.put("currentEntries", Nd4j.getNativeOps().getTADCachedEntries());
            tadCache.put("currentBytes", Nd4j.getNativeOps().getTADCachedBytes());
            tadCache.put("currentMB", String.format("%.2f", Nd4j.getNativeOps().getTADCachedBytes() / (1024.0 * 1024.0)));
            tadCache.put("peakEntries", Nd4j.getNativeOps().getTADPeakCachedEntries());
            tadCache.put("peakBytes", Nd4j.getNativeOps().getTADPeakCachedBytes());
            tadCache.put("peakMB", String.format("%.2f", Nd4j.getNativeOps().getTADPeakCachedBytes() / (1024.0 * 1024.0)));

            cacheStats.put("shape", shapeCache);
            cacheStats.put("tad", tadCache);

            long totalCacheBytes = Nd4j.getNativeOps().getShapeCachedBytes() + Nd4j.getNativeOps().getTADCachedBytes();
            long totalCacheEntries = Nd4j.getNativeOps().getShapeCachedEntries() + Nd4j.getNativeOps().getTADCachedEntries();
            cacheStats.put("totalEntries", totalCacheEntries);
            cacheStats.put("totalMB", String.format("%.2f", totalCacheBytes / (1024.0 * 1024.0)));

            response.put("cacheStatistics", cacheStats);

            // 4. Allocation Logging (ADR 53)
            // NOTE: Allocation logging is ALWAYS ACTIVE in functrace builds (SD_GCC_FUNCTRACE).
            // There are no runtime toggles - it's a build-time feature.
            // We only provide read-only access to the log files.
            Map<String, Object> allocationLogging = new LinkedHashMap<>();

            try {
                // Get allocation log path from native ops
                String allocationLogPath = Nd4j.getNativeOps().getAllocationLogPath();

                if (allocationLogPath != null && !allocationLogPath.isEmpty()) {
                    allocationLogging.put("logPath", allocationLogPath);
                    // "enabled" is a STATUS indicator (not a toggle) - indicates functrace build
                    allocationLogging.put("enabled", true);

                    // Parse the allocation log
                    AllocationLogSummary summary = allocationLogParser.parseAllocationLog(
                        allocationLogPath, topAllocations, topAllocationSites);

                    allocationLogging.put("summary", summary);

                    // Optionally include detailed allocation entries
                    if (includeAllocationDetails && summary.getLogFileExists()) {
                        List<AllocationEntry> allEntries = allocationLogParser.getAllEntries(allocationLogPath);
                        allocationLogging.put("detailedEntries", allEntries);
                        allocationLogging.put("detailedEntriesCount", allEntries.size());
                    }
                } else {
                    allocationLogging.put("enabled", false);
                    allocationLogging.put("message", "Allocation logging not available (SD_GCC_FUNCTRACE not enabled)");
                }
            } catch (Exception e) {
                logger.error("Error retrieving allocation log information", e);
                allocationLogging.put("enabled", false);
                allocationLogging.put("error", e.getMessage());
            }

            response.put("allocationLogging", allocationLogging);

            // 5. Operation Execution Logging (ADR 52)
            Map<String, Object> opExecutionLogging = new LinkedHashMap<>();
            try {
                // Operation execution logging is enabled via SD_GCC_FUNCTRACE
                // Check if we can detect its presence
                String opLogPath = System.getenv("SD_OP_EXECUTION_LOG_PATH");
                if (opLogPath != null && !opLogPath.isEmpty() && Files.exists(Paths.get(opLogPath))) {
                    opExecutionLogging.put("enabled", true);
                    opExecutionLogging.put("logPath", opLogPath);
                    opExecutionLogging.put("fileSize", Files.size(Paths.get(opLogPath)));
                } else {
                    opExecutionLogging.put("enabled", false);
                    opExecutionLogging.put("message", "Operation execution logging not detected");
                }
            } catch (Exception e) {
                opExecutionLogging.put("enabled", false);
                opExecutionLogging.put("error", e.getMessage());
            }
            response.put("operationExecutionLogging", opExecutionLogging);

            // 6. Leak Reports
            Map<String, Object> leakReports = new LinkedHashMap<>();
            try {
                String leakReportDir = "./leak_reports";
                if (Files.exists(Paths.get(leakReportDir))) {
                    long fileCount;
                    try (var leakFiles = Files.list(Paths.get(leakReportDir))) {
                        fileCount = leakFiles.count();
                    }
                    leakReports.put("directory", leakReportDir);
                    leakReports.put("reportCount", fileCount);
                    leakReports.put("exists", true);
                } else {
                    leakReports.put("directory", leakReportDir);
                    leakReports.put("exists", false);
                    leakReports.put("message", "No leak reports generated yet");
                }
            } catch (Exception e) {
                leakReports.put("error", e.getMessage());
            }
            response.put("leakReports", leakReports);

            // 7. Summary
            Map<String, String> summary = new LinkedHashMap<>();
            summary.put("lifecycleTracking", String.valueOf(lifecycleConfig.get("enabled")));
            summary.put("allocationLogging", allocationLogging.getOrDefault("enabled", false).toString());
            summary.put("operationLogging", opExecutionLogging.getOrDefault("enabled", false).toString());
            summary.put("cacheMemoryUsageMB", String.valueOf(cacheStats.get("totalMB")));

            if (allocationLogging.containsKey("summary")) {
                AllocationLogSummary allocSummary = (AllocationLogSummary) allocationLogging.get("summary");
                if (allocSummary != null && allocSummary.getTotalAllocations() != null) {
                    summary.put("totalAllocations", allocSummary.getTotalAllocations().toString());
                    summary.put("totalAllocationsMB",
                        String.format("%.2f", allocSummary.getTotalBytesAllocated() / (1024.0 * 1024.0)));
                }
            }

            response.put("summary", summary);

            response.put("status", "success");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error generating comprehensive diagnostics", e);
            response.put("status", "error");
            response.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Get allocation log summary only (faster than comprehensive endpoint).
     */
    @GetMapping("/allocation-log/summary")
    public ResponseEntity<AllocationLogSummary> getAllocationLogSummary(
            @RequestParam(defaultValue = "20") int topAllocations,
            @RequestParam(defaultValue = "10") int topAllocationSites) {

        try {
            String allocationLogPath = Nd4j.getNativeOps().getAllocationLogPath();

            if (allocationLogPath == null || allocationLogPath.isEmpty()) {
                return ResponseEntity.ok(AllocationLogSummary.builder()
                    .logFileExists(false)
                    .totalAllocations(0)
                    .ndarrayAllocations(0)
                    .opContextAllocations(0)
                    .totalBytesAllocated(0L)
                    .error("Allocation logging not enabled (SD_GCC_FUNCTRACE not defined)")
                    .build());
            }

            AllocationLogSummary summary = allocationLogParser.parseAllocationLog(
                allocationLogPath, topAllocations, topAllocationSites);

            return ResponseEntity.ok(summary);

        } catch (Exception e) {
            logger.error("Error getting allocation log summary", e);
            return ResponseEntity.ok(AllocationLogSummary.builder()
                .logFileExists(false)
                .totalAllocations(0)
                .ndarrayAllocations(0)
                .opContextAllocations(0)
                .totalBytesAllocated(0L)
                .error("Error: " + e.getMessage())
                .build());
        }
    }

    /**
     * Get all allocation log entries (detailed view).
     */
    @GetMapping("/allocation-log/entries")
    public ResponseEntity<List<AllocationEntry>> getAllocationLogEntries() {
        try {
            String allocationLogPath = Nd4j.getNativeOps().getAllocationLogPath();

            if (allocationLogPath == null || allocationLogPath.isEmpty()) {
                return ResponseEntity.ok(Collections.emptyList());
            }

            List<AllocationEntry> entries = allocationLogParser.getAllEntries(allocationLogPath);
            return ResponseEntity.ok(entries);

        } catch (Exception e) {
            logger.error("Error getting allocation log entries", e);
            return ResponseEntity.ok(Collections.emptyList());
        }
    }

    /**
     * Get allocation log file path.
     */
    @GetMapping("/allocation-log/path")
    public ResponseEntity<Map<String, String>> getAllocationLogPath() {
        Map<String, String> response = new LinkedHashMap<>();

        try {
            String allocationLogPath = Nd4j.getNativeOps().getAllocationLogPath();

            if (allocationLogPath != null && !allocationLogPath.isEmpty()) {
                response.put("logPath", allocationLogPath);
                response.put("exists", String.valueOf(Files.exists(Paths.get(allocationLogPath))));

                if (Files.exists(Paths.get(allocationLogPath))) {
                    response.put("sizeBytes", String.valueOf(Files.size(Paths.get(allocationLogPath))));
                    response.put("sizeMB", String.format("%.2f",
                        Files.size(Paths.get(allocationLogPath)) / (1024.0 * 1024.0)));
                }
            } else {
                response.put("enabled", "false");
                response.put("message", "Allocation logging not enabled (SD_GCC_FUNCTRACE not defined)");
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error getting allocation log path", e);
            response.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Get raw allocation log content (for debugging).
     */
    @GetMapping(value = "/allocation-log/raw", produces = "text/plain")
    public ResponseEntity<String> getRawAllocationLog(
            @RequestParam(defaultValue = "1000") int maxLines) {

        try {
            String allocationLogPath = Nd4j.getNativeOps().getAllocationLogPath();

            if (allocationLogPath == null || allocationLogPath.isEmpty()) {
                return ResponseEntity.ok("Allocation logging not enabled (SD_GCC_FUNCTRACE not defined)");
            }

            if (!Files.exists(Paths.get(allocationLogPath))) {
                return ResponseEntity.ok("Allocation log file not found: " + allocationLogPath);
            }

            List<String> lines = Files.readAllLines(Paths.get(allocationLogPath));

            if (lines.size() > maxLines) {
                lines = lines.subList(Math.max(0, lines.size() - maxLines), lines.size());
            }

            String content = String.join("\n", lines);
            return ResponseEntity.ok()
                .header("Content-Type", "text/plain; charset=UTF-8")
                .body(content);

        } catch (Exception e) {
            logger.error("Error reading raw allocation log", e);
            return ResponseEntity.internalServerError()
                .body("Error: " + e.getMessage());
        }
    }
}
