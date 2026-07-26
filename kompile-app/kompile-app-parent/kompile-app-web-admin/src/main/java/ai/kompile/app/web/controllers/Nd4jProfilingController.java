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

import ai.kompile.app.config.Nd4jEnvironmentConfig;
import ai.kompile.app.services.Nd4jEnvironmentConfigService;
import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST controller for ND4J profiling metrics, presets, and thread dump.
 * Extracted from ModelDebugController.
 *
 * Endpoints:
 *   GET  /api/models/nd4j/profiling-metrics
 *   POST /api/models/nd4j/profiling-preset/{preset}
 *   GET  /api/models/nd4j/thread-dump
 */
@RestController
@RequestMapping("/api/models")
public class Nd4jProfilingController {

    private static final Logger logger = LoggerFactory.getLogger(Nd4jProfilingController.class);

    @Autowired(required = false)
    private Nd4jEnvironmentConfigService nd4jEnvironmentConfigService;

    /**
     * Get comprehensive ND4J profiling metrics including memory, threads, BLAS info,
     * and all environment configuration. This is the main endpoint for UI profiling display.
     */
    @GetMapping("/nd4j/profiling-metrics")
    public ResponseEntity<Map<String, Object>> getProfilingMetrics() {
        Map<String, Object> response = new LinkedHashMap<>();
        long startTime = System.currentTimeMillis();

        try {
            org.nd4j.linalg.factory.Environment env = Nd4j.getEnvironment();

            // ========== BACKEND INFO ==========
            Map<String, Object> backendInfo = new LinkedHashMap<>();
            backendInfo.put("backend", Nd4j.getBackend().getClass().getSimpleName());
            backendInfo.put("isCPU", env.isCPU());
            backendInfo.put("blasMajorVersion", env.blasMajorVersion());
            backendInfo.put("blasMinorVersion", env.blasMinorVersion());
            backendInfo.put("blasPatchVersion", env.blasPatchVersion());
            backendInfo.put("blasVersion", env.blasMajorVersion() + "." + env.blasMinorVersion() + "." + env.blasPatchVersion());
            response.put("backend", backendInfo);

            // ========== THREAD CONFIGURATION ==========
            Map<String, Object> threadConfig = new LinkedHashMap<>();
            threadConfig.put("maxThreads", env.maxThreads());
            threadConfig.put("maxMasterThreads", env.maxMasterThreads());
            threadConfig.put("availableProcessors", Runtime.getRuntime().availableProcessors());
            threadConfig.put("activeThreadCount", Thread.activeCount());
            response.put("threads", threadConfig);

            // ========== MEMORY METRICS ==========
            Map<String, Object> memoryMetrics = new LinkedHashMap<>();
            Runtime runtime = Runtime.getRuntime();
            long maxMemory = runtime.maxMemory();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory;

            memoryMetrics.put("jvmMaxMemoryMB", maxMemory / (1024 * 1024));
            memoryMetrics.put("jvmTotalMemoryMB", totalMemory / (1024 * 1024));
            memoryMetrics.put("jvmUsedMemoryMB", usedMemory / (1024 * 1024));
            memoryMetrics.put("jvmFreeMemoryMB", freeMemory / (1024 * 1024));
            memoryMetrics.put("jvmUsagePercent", String.format("%.1f", (usedMemory * 100.0) / maxMemory));

            // Device memory counters (may return 0 on CPU)
            try {
                memoryMetrics.put("device0AllocatedBytes", env.getDeviceCounter(0));
                memoryMetrics.put("device0LimitBytes", env.getDeviceLimit(0));
                memoryMetrics.put("group0LimitBytes", env.getGroupLimit(0));
            } catch (Exception e) {
                memoryMetrics.put("deviceMemoryError", e.getMessage());
            }
            response.put("memory", memoryMetrics);

            // ========== PROFILING MODE STATUS ==========
            Map<String, Object> profilingStatus = new LinkedHashMap<>();
            profilingStatus.put("verbose", env.isVerbose());
            profilingStatus.put("debug", env.isDebug());
            profilingStatus.put("profiling", env.isProfiling());
            profilingStatus.put("debugAndVerbose", env.isDebugAndVerbose());
            profilingStatus.put("detectingLeaks", env.isDetectingLeaks());
            profilingStatus.put("lifecycleTracking", env.isLifecycleTracking());
            profilingStatus.put("trackOperations", env.isTrackOperations());
            profilingStatus.put("trackViews", env.isTrackViews());
            profilingStatus.put("trackDeletions", env.isTrackDeletions());
            profilingStatus.put("logNDArrayEvents", env.isLogNDArrayEvents());
            profilingStatus.put("logNativeNDArrayCreation", env.isLogNativeNDArrayCreation());
            profilingStatus.put("funcTraceAllocate", env.isFuncTracePrintAllocate());
            profilingStatus.put("funcTraceDeallocate", env.isFuncTracePrintDeallocate());
            response.put("profilingStatus", profilingStatus);

            // ========== PERFORMANCE THRESHOLDS ==========
            Map<String, Object> thresholds = new LinkedHashMap<>();
            thresholds.put("tadThreshold", env.tadThreshold());
            thresholds.put("elementwiseThreshold", env.elementwiseThreshold());
            response.put("thresholds", thresholds);

            // ========== BLAS/HELPER STATUS ==========
            Map<String, Object> blasStatus = new LinkedHashMap<>();
            blasStatus.put("blasEnabled", env.isEnableBlas());
            blasStatus.put("helpersAllowed", env.helpersAllowed());
            response.put("blas", blasStatus);

            // ========== TRACKING CONFIGURATION ==========
            Map<String, Object> trackingConfig = new LinkedHashMap<>();
            trackingConfig.put("stackDepth", env.getStackDepth());
            trackingConfig.put("reportInterval", env.getReportInterval());
            trackingConfig.put("maxDeletionHistory", env.getMaxDeletionHistory());
            trackingConfig.put("snapshotFiles", env.isSnapshotFiles());
            trackingConfig.put("trackWorkspaceOpenClose", env.isTrackWorkspaceOpenClose());
            trackingConfig.put("numWorkspaceEventsToKeep", env.numWorkspaceEventsToKeep());
            response.put("tracking", trackingConfig);

            // ========== DEBUG SETTINGS ==========
            Map<String, Object> debugSettings = new LinkedHashMap<>();
            debugSettings.put("checkInputChange", env.isCheckInputChange());
            debugSettings.put("checkOutputChange", env.isCheckOutputChange());
            debugSettings.put("deletePrimary", env.isDeletePrimary());
            debugSettings.put("deleteSpecial", env.isDeleteSpecial());
            debugSettings.put("deleteShapeInfo", env.isDeleteShapeInfo());
            debugSettings.put("variableTracingEnabled", env.isVariableTracingEnabled());
            response.put("debug", debugSettings);

            // ========== PROFILING MODE PRESETS ==========
            // Show available presets for quick configuration
            Map<String, Object> presets = new LinkedHashMap<>();
            presets.put("production", "Minimal overhead: all profiling disabled");
            presets.put("monitoring", "Light monitoring: lifecycle + operations tracking");
            presets.put("debugging", "Full debugging: all tracking + verbose + debug modes");
            presets.put("memoryAnalysis", "Memory focus: lifecycle + deletions + snapshots");
            response.put("availablePresets", presets);

            // ========== INFERENCE METRICS PLACEHOLDER ==========
            // This will be populated by actual inference operations
            Map<String, Object> inferenceMetrics = new LinkedHashMap<>();
            inferenceMetrics.put("note", "Real-time inference metrics available via [INFERENCE-TIMING] logs");
            inferenceMetrics.put("heartbeatInterval", "5 seconds during active inference");
            response.put("inference", inferenceMetrics);

            response.put("status", "success");
            response.put("timestamp", System.currentTimeMillis());
            response.put("collectionTimeMs", System.currentTimeMillis() - startTime);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error collecting profiling metrics", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Apply a profiling preset configuration.
     * Presets: production, monitoring, debugging, memoryAnalysis
     */
    @PostMapping("/nd4j/profiling-preset/{preset}")
    public ResponseEntity<Map<String, Object>> applyProfilingPreset(
            @PathVariable(name = "preset") String preset) {

        Map<String, Object> response = new LinkedHashMap<>();

        try {
            org.nd4j.linalg.factory.Environment env = Nd4j.getEnvironment();

            switch (preset.toLowerCase()) {
                case "production":
                    // Minimal overhead - disable all profiling
                    env.setVerbose(false);
                    env.setDebug(false);
                    env.setProfiling(false);
                    env.setLeaksDetector(false);
                    env.setLifecycleTracking(false);
                    env.setTrackViews(false);
                    env.setTrackDeletions(false);
                    env.setTrackOperations(false);
                    env.setLogNDArrayEvents(false);
                    env.setLogNativeNDArrayCreation(false);
                    env.setFuncTraceForAllocate(false);
                    env.setFuncTraceForDeallocate(false);
                    env.setSnapshotFiles(false);
                    env.setCheckInputChange(false);
                    env.setCheckOutputChange(false);
                    logger.info("Applied PRODUCTION preset - all profiling disabled");
                    response.put("message", "Production preset applied - minimal overhead");
                    break;

                case "monitoring":
                    // Light monitoring for production issues
                    env.setVerbose(false);
                    env.setDebug(false);
                    env.setProfiling(true);
                    env.setLeaksDetector(false);
                    env.setLifecycleTracking(true);
                    env.setTrackViews(false);
                    env.setTrackDeletions(false);
                    env.setTrackOperations(true);
                    env.setLogNDArrayEvents(false);
                    env.setLogNativeNDArrayCreation(false);
                    env.setFuncTraceForAllocate(false);
                    env.setFuncTraceForDeallocate(false);
                    env.setSnapshotFiles(false);
                    env.setStackDepth(16);
                    env.setReportInterval(60);
                    logger.info("Applied MONITORING preset - light profiling enabled");
                    response.put("message", "Monitoring preset applied - light overhead");
                    break;

                case "debugging":
                    // Full debugging for development
                    env.setVerbose(true);
                    env.setDebug(true);
                    env.setProfiling(true);
                    env.setLeaksDetector(true);
                    env.setLifecycleTracking(true);
                    env.setTrackViews(true);
                    env.setTrackDeletions(true);
                    env.setTrackOperations(true);
                    env.setLogNDArrayEvents(true);
                    env.setLogNativeNDArrayCreation(true);
                    env.setFuncTraceForAllocate(false); // Still high overhead
                    env.setFuncTraceForDeallocate(false);
                    env.setSnapshotFiles(true);
                    env.setStackDepth(32);
                    env.setReportInterval(30);
                    logger.warn("Applied DEBUGGING preset - HIGH overhead, use only for debugging");
                    response.put("message", "Debugging preset applied - HIGH overhead");
                    response.put("warning", "This will significantly impact performance");
                    break;

                case "memoryanalysis":
                    // Focus on memory leak detection
                    env.setVerbose(false);
                    env.setDebug(false);
                    env.setProfiling(true);
                    env.setLeaksDetector(true);
                    env.setLifecycleTracking(true);
                    env.setTrackViews(true);
                    env.setTrackDeletions(true);
                    env.setTrackOperations(true);
                    env.setLogNDArrayEvents(false);
                    env.setLogNativeNDArrayCreation(false);
                    env.setFuncTraceForAllocate(false);
                    env.setFuncTraceForDeallocate(false);
                    env.setSnapshotFiles(true);
                    env.setStackDepth(48);
                    env.setReportInterval(30);
                    env.setMaxDeletionHistory(50000);
                    logger.info("Applied MEMORY ANALYSIS preset - focused on leak detection");
                    response.put("message", "Memory analysis preset applied - moderate overhead");
                    break;

                default:
                    response.put("status", "error");
                    response.put("message", "Unknown preset: " + preset);
                    response.put("validPresets", Arrays.asList("production", "monitoring", "debugging", "memoryAnalysis"));
                    return ResponseEntity.badRequest().body(response);
            }

            // Persist the preset configuration to disk
            persistProfilingPreset(preset);

            response.put("status", "success");
            response.put("preset", preset);
            response.put("currentState", getCurrentEnvironmentState());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error applying profiling preset: " + preset, e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Persist profiling preset configuration to disk.
     */
    private void persistProfilingPreset(String preset) {
        if (nd4jEnvironmentConfigService == null) {
            logger.warn("Nd4jEnvironmentConfigService not available - preset not persisted to disk");
            return;
        }

        try {
            Nd4jEnvironmentConfig.Builder builder = Nd4jEnvironmentConfig.builder();

            switch (preset.toLowerCase()) {
                case "production":
                    builder.verbose(false)
                            .debug(false)
                            .profiling(false)
                            .leaksDetector(false)
                            .lifecycleTracking(false)
                            .trackViews(false)
                            .trackDeletions(false)
                            .trackOperations(false)
                            .logNDArrayEvents(false)
                            .logNativeNDArrayCreation(false)
                            .funcTracePrintAllocate(false)
                            .funcTracePrintDeallocate(false)
                            .snapshotFiles(false)
                            .checkInputChange(false)
                            .checkOutputChange(false);
                    break;

                case "monitoring":
                    builder.verbose(false)
                            .debug(false)
                            .profiling(true)
                            .leaksDetector(false)
                            .lifecycleTracking(true)
                            .trackViews(false)
                            .trackDeletions(false)
                            .trackOperations(true)
                            .logNDArrayEvents(false)
                            .logNativeNDArrayCreation(false)
                            .funcTracePrintAllocate(false)
                            .funcTracePrintDeallocate(false)
                            .snapshotFiles(false)
                            .stackDepth(16)
                            .reportInterval(60);
                    break;

                case "debugging":
                    builder.verbose(true)
                            .debug(true)
                            .profiling(true)
                            .leaksDetector(true)
                            .lifecycleTracking(true)
                            .trackViews(true)
                            .trackDeletions(true)
                            .trackOperations(true)
                            .logNDArrayEvents(true)
                            .logNativeNDArrayCreation(true)
                            .funcTracePrintAllocate(false)
                            .funcTracePrintDeallocate(false)
                            .snapshotFiles(true)
                            .stackDepth(32)
                            .reportInterval(30);
                    break;

                case "memoryanalysis":
                    builder.verbose(false)
                            .debug(false)
                            .profiling(true)
                            .leaksDetector(true)
                            .lifecycleTracking(true)
                            .trackViews(true)
                            .trackDeletions(true)
                            .trackOperations(true)
                            .logNDArrayEvents(false)
                            .logNativeNDArrayCreation(false)
                            .funcTracePrintAllocate(false)
                            .funcTracePrintDeallocate(false)
                            .snapshotFiles(true)
                            .stackDepth(48)
                            .reportInterval(30)
                            .maxDeletionHistory(50000);
                    break;

                default:
                    return; // Unknown preset, don't persist
            }

            nd4jEnvironmentConfigService.updateConfiguration(builder.build());
            logger.debug("Persisted profiling preset '{}' to config file", preset);
        } catch (Exception e) {
            logger.warn("Failed to persist profiling preset '{}': {}", preset, e.getMessage());
        }
    }

    /**
     * Get real-time thread dump for ND4J/embedding workers.
     * Useful for diagnosing stuck threads in native code.
     */
    @GetMapping("/nd4j/thread-dump")
    public ResponseEntity<Map<String, Object>> getThreadDump(
            @RequestParam(name = "filter", required = false, defaultValue = "") String filter) {

        Map<String, Object> response = new LinkedHashMap<>();

        try {
            Map<Thread, StackTraceElement[]> allThreads = Thread.getAllStackTraces();
            List<Map<String, Object>> threadList = new ArrayList<>();

            for (Map.Entry<Thread, StackTraceElement[]> entry : allThreads.entrySet()) {
                Thread thread = entry.getKey();
                StackTraceElement[] stack = entry.getValue();

                // Apply filter if provided
                String threadName = thread.getName().toLowerCase();
                if (!filter.isEmpty() && !threadName.contains(filter.toLowerCase())) {
                    continue;
                }

                Map<String, Object> threadInfo = new LinkedHashMap<>();
                threadInfo.put("name", thread.getName());
                threadInfo.put("id", thread.getId());
                threadInfo.put("state", thread.getState().toString());
                threadInfo.put("priority", thread.getPriority());
                threadInfo.put("isDaemon", thread.isDaemon());
                threadInfo.put("isAlive", thread.isAlive());
                threadInfo.put("isInterrupted", thread.isInterrupted());

                // Include stack trace (limit depth for readability)
                List<String> stackTrace = new ArrayList<>();
                int maxDepth = Math.min(20, stack.length);
                for (int i = 0; i < maxDepth; i++) {
                    stackTrace.add(stack[i].toString());
                }
                if (stack.length > maxDepth) {
                    stackTrace.add("... " + (stack.length - maxDepth) + " more frames");
                }
                threadInfo.put("stackTrace", stackTrace);
                threadInfo.put("stackDepth", stack.length);

                // Flag potentially stuck threads
                boolean mightBeStuck = false;
                for (StackTraceElement elem : stack) {
                    String className = elem.getClassName();
                    if (className.contains("Nd4jCpu") || className.contains("execCustomOp") ||
                        className.contains("NativeOp") || className.contains("openblas")) {
                        mightBeStuck = true;
                        threadInfo.put("inNativeCode", true);
                        threadInfo.put("nativeMethod", elem.toString());
                        break;
                    }
                }
                threadInfo.put("mightBeStuck", mightBeStuck);

                threadList.add(threadInfo);
            }

            // Sort by state (RUNNABLE first, then by name)
            threadList.sort((a, b) -> {
                String stateA = (String) a.get("state");
                String stateB = (String) b.get("state");
                if (stateA.equals("RUNNABLE") && !stateB.equals("RUNNABLE")) return -1;
                if (!stateA.equals("RUNNABLE") && stateB.equals("RUNNABLE")) return 1;
                return ((String) a.get("name")).compareTo((String) b.get("name"));
            });

            response.put("status", "success");
            response.put("totalThreads", allThreads.size());
            response.put("filteredThreads", threadList.size());
            response.put("filter", filter.isEmpty() ? "none" : filter);
            response.put("threads", threadList);

            // Summary
            Map<String, Long> stateCounts = threadList.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            t -> (String) t.get("state"),
                            java.util.stream.Collectors.counting()));
            response.put("stateCounts", stateCounts);

            long inNativeCount = threadList.stream()
                    .filter(t -> Boolean.TRUE.equals(t.get("inNativeCode")))
                    .count();
            response.put("threadsInNativeCode", inNativeCount);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error getting thread dump", e);
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Get the current ND4J environment state as a map.
     * Used internally by preset methods to report current state after applying a preset.
     */
    private Map<String, Object> getCurrentEnvironmentState() {
        org.nd4j.linalg.factory.Environment env = Nd4j.getEnvironment();
        Map<String, Object> state = new LinkedHashMap<>();

        // Boolean toggles
        state.put("lifecycleTracking", env.isLifecycleTracking());
        state.put("trackViews", env.isTrackViews());
        state.put("trackDeletions", env.isTrackDeletions());
        state.put("snapshotFiles", env.isSnapshotFiles());
        state.put("trackOperations", env.isTrackOperations());
        state.put("verbose", env.isVerbose());
        state.put("debug", env.isDebug());
        state.put("profiling", env.isProfiling());
        state.put("leaksDetector", env.isDetectingLeaks());
        state.put("logNDArrayEvents", env.isLogNDArrayEvents());
        state.put("logNativeNDArrayCreation", env.isLogNativeNDArrayCreation());
        state.put("truncateLogStrings", env.isTruncateNDArrayLogStrings());
        state.put("enableBlas", env.isEnableBlas());
        state.put("helpersAllowed", env.helpersAllowed());
        state.put("funcTraceAllocate", env.isFuncTracePrintAllocate());
        state.put("funcTraceDeallocate", env.isFuncTracePrintDeallocate());
        state.put("funcTracePrintJavaOnly", env.isFuncTracePrintJavaOnly());
        state.put("variableTracingEnabled", env.isVariableTracingEnabled());
        state.put("checkInputChange", env.isCheckInputChange());
        state.put("checkOutputChange", env.isCheckOutputChange());
        state.put("trackWorkspaceOpenClose", env.isTrackWorkspaceOpenClose());
        state.put("deletePrimary", env.isDeletePrimary());
        state.put("deleteSpecial", env.isDeleteSpecial());
        state.put("deleteShapeInfo", env.isDeleteShapeInfo());

        // Integer configs
        state.put("stackDepth", env.getStackDepth());
        state.put("reportInterval", env.getReportInterval());
        state.put("maxDeletionHistory", env.getMaxDeletionHistory());
        state.put("tadThreshold", env.tadThreshold());
        state.put("elementwiseThreshold", env.elementwiseThreshold());
        state.put("maxThreads", env.maxThreads());
        state.put("maxMasterThreads", env.maxMasterThreads());

        return state;
    }
}
