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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for managing ND4J environment configuration.
 * Provides endpoints to view, update, and persist ND4J settings.
 *
 * All changes are automatically persisted to disk and will be
 * restored on the next application startup.
 */
@RestController
@RequestMapping("/api/nd4j/environment")
public class Nd4jEnvironmentController {

    private static final Logger log = LoggerFactory.getLogger(Nd4jEnvironmentController.class);

    private final Nd4jEnvironmentConfigService configService;

    public Nd4jEnvironmentController(Nd4jEnvironmentConfigService configService) {
        this.configService = configService;
    }

    /**
     * Get the current ND4J environment configuration.
     * Returns both the persisted configuration and the actual runtime values.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getConfiguration() {
        Map<String, Object> response = new HashMap<>();
        response.put("persisted", configService.getConfiguration());
        response.put("actual", configService.getActualConfiguration());
        response.put("summary", configService.getConfigurationSummary());
        return ResponseEntity.ok(response);
    }

    /**
     * Get a summary of the current configuration.
     * Organized by category for easier reading.
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getConfigurationSummary() {
        return ResponseEntity.ok(configService.getConfigurationSummary());
    }

    /**
     * Update ND4J environment configuration.
     * Only provided (non-null) values will be updated.
     * Changes are automatically persisted.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> updateConfiguration(@RequestBody Nd4jEnvironmentConfig update) {
        log.info("Updating ND4J environment configuration");

        Nd4jEnvironmentConfig newConfig = configService.updateConfiguration(update);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", "Configuration updated and persisted");
        response.put("config", newConfig);
        return ResponseEntity.ok(response);
    }

    /**
     * Update specific thread settings.
     */
    @PostMapping("/threads")
    public ResponseEntity<Map<String, Object>> updateThreadSettings(
            @RequestParam(required = false) Integer maxThreads,
            @RequestParam(required = false) Integer maxMasterThreads) {

        Nd4jEnvironmentConfig update = Nd4jEnvironmentConfig.builder()
                .maxThreads(maxThreads)
                .maxMasterThreads(maxMasterThreads)
                .build();

        Nd4jEnvironmentConfig newConfig = configService.updateConfiguration(update);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("maxThreads", newConfig.maxThreads());
        response.put("maxMasterThreads", newConfig.maxMasterThreads());
        return ResponseEntity.ok(response);
    }

    /**
     * Enable or disable lifecycle tracking with a single toggle.
     */
    @PostMapping("/lifecycle-tracking")
    public ResponseEntity<Map<String, Object>> setLifecycleTracking(
            @RequestParam boolean enabled,
            @RequestParam(required = false, defaultValue = "false") Boolean enableAllTrackers) {

        Nd4jEnvironmentConfig.Builder builder = Nd4jEnvironmentConfig.builder()
                .lifecycleTracking(enabled);

        if (enabled && Boolean.TRUE.equals(enableAllTrackers)) {
            // Enable all individual trackers
            builder.ndArrayTracking(true)
                    .dataBufferTracking(true)
                    .tadCacheTracking(true)
                    .shapeCacheTracking(true)
                    .opContextTracking(true)
                    .snapshotFiles(true)
                    .trackOperations(true);
        } else if (!enabled) {
            // Disable all individual trackers
            builder.ndArrayTracking(false)
                    .dataBufferTracking(false)
                    .tadCacheTracking(false)
                    .shapeCacheTracking(false)
                    .opContextTracking(false)
                    .snapshotFiles(false)
                    .trackOperations(false)
                    .trackViews(false)
                    .trackDeletions(false);
        }

        Nd4jEnvironmentConfig newConfig = configService.updateConfiguration(builder.build());

        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("lifecycleTracking", newConfig.lifecycleTracking());
        response.put("message", enabled ? "Lifecycle tracking enabled" : "Lifecycle tracking disabled");
        return ResponseEntity.ok(response);
    }

    /**
     * Update individual tracker toggles.
     */
    @PostMapping("/trackers")
    public ResponseEntity<Map<String, Object>> updateTrackers(
            @RequestParam(required = false) Boolean ndArrayTracking,
            @RequestParam(required = false) Boolean dataBufferTracking,
            @RequestParam(required = false) Boolean tadCacheTracking,
            @RequestParam(required = false) Boolean shapeCacheTracking,
            @RequestParam(required = false) Boolean opContextTracking) {

        Nd4jEnvironmentConfig update = Nd4jEnvironmentConfig.builder()
                .ndArrayTracking(ndArrayTracking)
                .dataBufferTracking(dataBufferTracking)
                .tadCacheTracking(tadCacheTracking)
                .shapeCacheTracking(shapeCacheTracking)
                .opContextTracking(opContextTracking)
                .build();

        Nd4jEnvironmentConfig newConfig = configService.updateConfiguration(update);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("ndArrayTracking", newConfig.ndArrayTracking());
        response.put("dataBufferTracking", newConfig.dataBufferTracking());
        response.put("tadCacheTracking", newConfig.tadCacheTracking());
        response.put("shapeCacheTracking", newConfig.shapeCacheTracking());
        response.put("opContextTracking", newConfig.opContextTracking());
        return ResponseEntity.ok(response);
    }

    /**
     * Apply a named preset configuration.
     * Available presets: minimal, balanced, detailed, performance
     */
    @PostMapping("/preset/{presetName}")
    public ResponseEntity<Map<String, Object>> applyPreset(@PathVariable String presetName) {
        log.info("Applying ND4J environment preset: {}", presetName);

        try {
            Nd4jEnvironmentConfig newConfig = configService.applyPreset(presetName);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("preset", presetName);
            response.put("message", "Preset '" + presetName + "' applied and persisted");
            response.put("config", newConfig);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", e.getMessage());
            error.put("availablePresets", List.of("minimal", "balanced", "detailed", "performance"));
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Get available presets and their descriptions.
     */
    @GetMapping("/presets")
    public ResponseEntity<Map<String, Object>> getAvailablePresets() {
        Map<String, Object> presets = new HashMap<>();

        Map<String, String> minimal = new HashMap<>();
        minimal.put("name", "minimal");
        minimal.put("description", "Minimal overhead - 2 threads, basic lifecycle tracking only");
        minimal.put("useCase", "Production environments with minimal debugging needs");
        presets.put("minimal", minimal);

        Map<String, String> balanced = new HashMap<>();
        balanced.put("name", "balanced");
        balanced.put("description", "Balanced configuration - 4 threads, moderate tracking with all trackers enabled");
        balanced.put("useCase", "Development and testing with memory leak detection");
        presets.put("balanced", balanced);

        Map<String, String> detailed = new HashMap<>();
        detailed.put("name", "detailed");
        detailed.put("description", "Full tracking - debug/verbose/profiling enabled, deep stack traces");
        detailed.put("useCase", "Deep debugging and performance analysis (high overhead)");
        presets.put("detailed", detailed);

        Map<String, String> performance = new HashMap<>();
        performance.put("name", "performance");
        performance.put("description", "Maximum performance - all CPUs, no tracking overhead");
        performance.put("useCase", "Production environments prioritizing speed");
        presets.put("performance", performance);

        Map<String, Object> response = new HashMap<>();
        response.put("presets", presets);
        response.put("currentPreset", "custom"); // We don't track which preset was applied
        return ResponseEntity.ok(response);
    }

    /**
     * Reset configuration to defaults.
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> resetConfiguration() {
        log.info("Resetting ND4J environment configuration to defaults");

        Nd4jEnvironmentConfig defaultConfig = configService.resetConfiguration();

        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", "Configuration reset to defaults and persisted");
        response.put("config", defaultConfig);
        return ResponseEntity.ok(response);
    }

    /**
     * Update lifecycle tracking parameters.
     */
    @PostMapping("/lifecycle-params")
    public ResponseEntity<Map<String, Object>> updateLifecycleParams(
            @RequestParam(required = false) Integer stackDepth,
            @RequestParam(required = false) Integer reportInterval,
            @RequestParam(required = false) Integer maxDeletionHistory) {

        // Validate inputs
        if (stackDepth != null && (stackDepth < 0 || stackDepth > 256)) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "stackDepth must be between 0 and 256");
            return ResponseEntity.badRequest().body(error);
        }
        if (reportInterval != null && (reportInterval < 0 || reportInterval > 3600)) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "reportInterval must be between 0 and 3600 seconds");
            return ResponseEntity.badRequest().body(error);
        }
        if (maxDeletionHistory != null && (maxDeletionHistory < 0 || maxDeletionHistory > 100000)) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "maxDeletionHistory must be between 0 and 100000");
            return ResponseEntity.badRequest().body(error);
        }

        Nd4jEnvironmentConfig update = Nd4jEnvironmentConfig.builder()
                .stackDepth(stackDepth)
                .reportInterval(reportInterval)
                .maxDeletionHistory(maxDeletionHistory)
                .build();

        Nd4jEnvironmentConfig newConfig = configService.updateConfiguration(update);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("stackDepth", newConfig.stackDepth());
        response.put("reportInterval", newConfig.reportInterval());
        response.put("maxDeletionHistory", newConfig.maxDeletionHistory());
        return ResponseEntity.ok(response);
    }

    /**
     * Update view and deletion tracking (high overhead options).
     */
    @PostMapping("/high-overhead-tracking")
    public ResponseEntity<Map<String, Object>> updateHighOverheadTracking(
            @RequestParam(required = false) Boolean trackViews,
            @RequestParam(required = false) Boolean trackDeletions) {

        if (Boolean.TRUE.equals(trackViews) || Boolean.TRUE.equals(trackDeletions)) {
            log.warn("Enabling high-overhead tracking options: trackViews={}, trackDeletions={}",
                    trackViews, trackDeletions);
        }

        Nd4jEnvironmentConfig update = Nd4jEnvironmentConfig.builder()
                .trackViews(trackViews)
                .trackDeletions(trackDeletions)
                .build();

        Nd4jEnvironmentConfig newConfig = configService.updateConfiguration(update);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("trackViews", newConfig.trackViews());
        response.put("trackDeletions", newConfig.trackDeletions());
        if (Boolean.TRUE.equals(newConfig.trackViews()) || Boolean.TRUE.equals(newConfig.trackDeletions())) {
            response.put("warning", "High overhead tracking enabled - performance may be impacted");
        }
        return ResponseEntity.ok(response);
    }

    /**
     * Update BLAS configuration settings.
     * Controls BLAS call serialization for thread safety and OpenBLAS internal threading.
     *
     * @param serializationEnabled Enable/disable BLAS call serialization (default: true for safety)
     * @param openBlasThreads Number of OpenBLAS internal threads (default: 1 for safety)
     */
    @PostMapping("/blas")
    public ResponseEntity<Map<String, Object>> updateBlasSettings(
            @RequestParam(required = false) Boolean serializationEnabled,
            @RequestParam(required = false) Integer openBlasThreads) {

        // Validate openBlasThreads if provided
        if (openBlasThreads != null && (openBlasThreads < 1 || openBlasThreads > Runtime.getRuntime().availableProcessors() * 2)) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "openBlasThreads must be between 1 and " + (Runtime.getRuntime().availableProcessors() * 2));
            return ResponseEntity.badRequest().body(error);
        }

        log.info("Updating BLAS configuration: serializationEnabled={}, openBlasThreads={}",
                serializationEnabled, openBlasThreads);

        Nd4jEnvironmentConfig update = Nd4jEnvironmentConfig.builder()
                .blasSerializationEnabled(serializationEnabled)
                .openBlasThreads(openBlasThreads)
                .build();

        Nd4jEnvironmentConfig newConfig = configService.updateConfiguration(update);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("blasSerializationEnabled", newConfig.blasSerializationEnabled());
        response.put("openBlasThreads", newConfig.openBlasThreads());

        // Add explanation
        if (Boolean.FALSE.equals(newConfig.blasSerializationEnabled())) {
            response.put("warning", "BLAS serialization disabled - ensure thread safety is handled externally");
        }
        if (newConfig.openBlasThreads() != null && newConfig.openBlasThreads() > 1) {
            response.put("info", "OpenBLAS using " + newConfig.openBlasThreads() + " threads per operation");
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Get current BLAS configuration.
     */
    @GetMapping("/blas")
    public ResponseEntity<Map<String, Object>> getBlasSettings() {
        Nd4jEnvironmentConfig actual = configService.getActualConfiguration();

        Map<String, Object> response = new HashMap<>();
        response.put("blasSerializationEnabled", actual.blasSerializationEnabled());
        response.put("openBlasThreads", actual.openBlasThreads());
        response.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        response.put("description", Map.of(
                "blasSerializationEnabled", "When true, BLAS calls are serialized via mutex for thread safety",
                "openBlasThreads", "Number of threads OpenBLAS uses internally for each operation"
        ));
        return ResponseEntity.ok(response);
    }

    /**
     * Update OpenMP thread configuration.
     * Controls the number of threads used by OpenMP for parallel operations.
     *
     * @param ompNumThreads Number of OpenMP threads (default: 4)
     */
    @PostMapping("/omp")
    public ResponseEntity<Map<String, Object>> updateOmpSettings(
            @RequestParam(name = "ompNumThreads") Integer ompNumThreads) {

        // Validate ompNumThreads
        if (ompNumThreads < 1 || ompNumThreads > Runtime.getRuntime().availableProcessors() * 2) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "ompNumThreads must be between 1 and " + (Runtime.getRuntime().availableProcessors() * 2));
            return ResponseEntity.badRequest().body(error);
        }

        log.info("Updating OMP configuration: ompNumThreads={}", ompNumThreads);

        Nd4jEnvironmentConfig update = Nd4jEnvironmentConfig.builder()
                .ompNumThreads(ompNumThreads)
                .build();

        Nd4jEnvironmentConfig newConfig = configService.updateConfiguration(update);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("ompNumThreads", configService.getOmpNumThreads());
        response.put("configuredOmpNumThreads", newConfig.ompNumThreads());
        response.put("info", "OpenMP using " + configService.getOmpNumThreads() + " threads");

        return ResponseEntity.ok(response);
    }

    /**
     * Get current OpenMP configuration.
     */
    @GetMapping("/omp")
    public ResponseEntity<Map<String, Object>> getOmpSettings() {
        Nd4jEnvironmentConfig actual = configService.getActualConfiguration();

        Map<String, Object> response = new HashMap<>();
        response.put("ompNumThreads", configService.getOmpNumThreads());
        response.put("configuredOmpNumThreads", actual.ompNumThreads());
        response.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        response.put("description", "Number of threads used by OpenMP for parallel operations in NativeOps");
        return ResponseEntity.ok(response);
    }

    /**
     * Update debug/verbose settings.
     * Controls debug mode, verbose output, profiling, and leak detection.
     */
    @PostMapping("/debug")
    public ResponseEntity<Map<String, Object>> updateDebugSettings(
            @RequestParam(required = false) Boolean debug,
            @RequestParam(required = false) Boolean verbose,
            @RequestParam(required = false) Boolean profiling,
            @RequestParam(required = false) Boolean leaksDetector) {

        log.info("Updating debug settings: debug={}, verbose={}, profiling={}, leaksDetector={}",
                debug, verbose, profiling, leaksDetector);

        Nd4jEnvironmentConfig update = Nd4jEnvironmentConfig.builder()
                .debug(debug)
                .verbose(verbose)
                .profiling(profiling)
                .leaksDetector(leaksDetector)
                .build();

        Nd4jEnvironmentConfig newConfig = configService.updateConfiguration(update);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("debug", newConfig.debug());
        response.put("verbose", newConfig.verbose());
        response.put("profiling", newConfig.profiling());
        response.put("leaksDetector", newConfig.leaksDetector());

        if (Boolean.TRUE.equals(newConfig.debug()) || Boolean.TRUE.equals(newConfig.verbose())) {
            response.put("warning", "Debug/verbose mode enabled - performance may be impacted");
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Get current debug settings.
     */
    @GetMapping("/debug")
    public ResponseEntity<Map<String, Object>> getDebugSettings() {
        Nd4jEnvironmentConfig actual = configService.getActualConfiguration();

        Map<String, Object> response = new HashMap<>();
        response.put("debug", actual.debug());
        response.put("verbose", actual.verbose());
        response.put("profiling", actual.profiling());
        response.put("leaksDetector", actual.leaksDetector());
        response.put("description", Map.of(
                "debug", "Enable debug mode - adds extra validation and logging",
                "verbose", "Enable verbose output - detailed operation logging",
                "profiling", "Enable profiling - tracks operation timing",
                "leaksDetector", "Enable memory leak detection"
        ));
        return ResponseEntity.ok(response);
    }

    /**
     * Update advanced debugging settings.
     * Controls native array logging, event logging, input/output change detection, etc.
     */
    @PostMapping("/advanced-debug")
    public ResponseEntity<Map<String, Object>> updateAdvancedDebugSettings(
            @RequestParam(required = false) Boolean logNativeNDArrayCreation,
            @RequestParam(required = false) Boolean logNDArrayEvents,
            @RequestParam(required = false) Boolean checkInputChange,
            @RequestParam(required = false) Boolean checkOutputChange,
            @RequestParam(required = false) Boolean trackWorkspaceOpenClose,
            @RequestParam(required = false) Boolean variableTracingEnabled) {

        log.info("Updating advanced debug settings: logNativeNDArrayCreation={}, logNDArrayEvents={}, " +
                        "checkInputChange={}, checkOutputChange={}, trackWorkspaceOpenClose={}, variableTracingEnabled={}",
                logNativeNDArrayCreation, logNDArrayEvents, checkInputChange, checkOutputChange,
                trackWorkspaceOpenClose, variableTracingEnabled);

        Nd4jEnvironmentConfig update = Nd4jEnvironmentConfig.builder()
                .logNativeNDArrayCreation(logNativeNDArrayCreation)
                .logNDArrayEvents(logNDArrayEvents)
                .checkInputChange(checkInputChange)
                .checkOutputChange(checkOutputChange)
                .trackWorkspaceOpenClose(trackWorkspaceOpenClose)
                .variableTracingEnabled(variableTracingEnabled)
                .build();

        Nd4jEnvironmentConfig newConfig = configService.updateConfiguration(update);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("logNativeNDArrayCreation", newConfig.logNativeNDArrayCreation());
        response.put("logNDArrayEvents", newConfig.logNDArrayEvents());
        response.put("checkInputChange", newConfig.checkInputChange());
        response.put("checkOutputChange", newConfig.checkOutputChange());
        response.put("trackWorkspaceOpenClose", newConfig.trackWorkspaceOpenClose());
        response.put("variableTracingEnabled", newConfig.variableTracingEnabled());

        return ResponseEntity.ok(response);
    }

    /**
     * Get current advanced debug settings.
     */
    @GetMapping("/advanced-debug")
    public ResponseEntity<Map<String, Object>> getAdvancedDebugSettings() {
        Nd4jEnvironmentConfig actual = configService.getActualConfiguration();

        Map<String, Object> response = new HashMap<>();
        response.put("logNativeNDArrayCreation", actual.logNativeNDArrayCreation());
        response.put("logNDArrayEvents", actual.logNDArrayEvents());
        response.put("checkInputChange", actual.checkInputChange());
        response.put("checkOutputChange", actual.checkOutputChange());
        response.put("trackWorkspaceOpenClose", actual.trackWorkspaceOpenClose());
        response.put("variableTracingEnabled", actual.variableTracingEnabled());
        response.put("description", Map.of(
                "logNativeNDArrayCreation", "Log when native NDArrays are created",
                "logNDArrayEvents", "Log NDArray lifecycle events",
                "checkInputChange", "Check if input arrays change during operations",
                "checkOutputChange", "Check if output arrays change unexpectedly",
                "trackWorkspaceOpenClose", "Track workspace open/close operations",
                "variableTracingEnabled", "Enable variable tracing in SameDiff"
        ));
        return ResponseEntity.ok(response);
    }

    /**
     * Update function tracing settings.
     * Controls tracing of allocate/deallocate calls and Java-only tracing.
     */
    @PostMapping("/function-trace")
    public ResponseEntity<Map<String, Object>> updateFunctionTraceSettings(
            @RequestParam(required = false) Boolean funcTracePrintAllocate,
            @RequestParam(required = false) Boolean funcTracePrintDeallocate,
            @RequestParam(required = false) Boolean funcTracePrintJavaOnly) {

        log.info("Updating function trace settings: allocate={}, deallocate={}, javaOnly={}",
                funcTracePrintAllocate, funcTracePrintDeallocate, funcTracePrintJavaOnly);

        Nd4jEnvironmentConfig update = Nd4jEnvironmentConfig.builder()
                .funcTracePrintAllocate(funcTracePrintAllocate)
                .funcTracePrintDeallocate(funcTracePrintDeallocate)
                .funcTracePrintJavaOnly(funcTracePrintJavaOnly)
                .build();

        Nd4jEnvironmentConfig newConfig = configService.updateConfiguration(update);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("funcTracePrintAllocate", newConfig.funcTracePrintAllocate());
        response.put("funcTracePrintDeallocate", newConfig.funcTracePrintDeallocate());
        response.put("funcTracePrintJavaOnly", newConfig.funcTracePrintJavaOnly());

        if (Boolean.TRUE.equals(newConfig.funcTracePrintAllocate()) ||
                Boolean.TRUE.equals(newConfig.funcTracePrintDeallocate())) {
            response.put("warning", "Function tracing enabled - expect high volume of output");
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Get current function trace settings.
     */
    @GetMapping("/function-trace")
    public ResponseEntity<Map<String, Object>> getFunctionTraceSettings() {
        Nd4jEnvironmentConfig actual = configService.getActualConfiguration();

        Map<String, Object> response = new HashMap<>();
        response.put("funcTracePrintAllocate", actual.funcTracePrintAllocate());
        response.put("funcTracePrintDeallocate", actual.funcTracePrintDeallocate());
        response.put("funcTracePrintJavaOnly", actual.funcTracePrintJavaOnly());
        response.put("description", Map.of(
                "funcTracePrintAllocate", "Print stack trace on memory allocations",
                "funcTracePrintDeallocate", "Print stack trace on memory deallocations",
                "funcTracePrintJavaOnly", "Only print Java stack frames (exclude native)"
        ));
        return ResponseEntity.ok(response);
    }

    /**
     * Update memory deletion settings.
     * Controls whether shape info, primary memory, and special memory are deleted.
     */
    @PostMapping("/deletion")
    public ResponseEntity<Map<String, Object>> updateDeletionSettings(
            @RequestParam(required = false) Boolean deleteShapeInfo,
            @RequestParam(required = false) Boolean deletePrimary,
            @RequestParam(required = false) Boolean deleteSpecial) {

        log.info("Updating deletion settings: deleteShapeInfo={}, deletePrimary={}, deleteSpecial={}",
                deleteShapeInfo, deletePrimary, deleteSpecial);

        Nd4jEnvironmentConfig update = Nd4jEnvironmentConfig.builder()
                .deleteShapeInfo(deleteShapeInfo)
                .deletePrimary(deletePrimary)
                .deleteSpecial(deleteSpecial)
                .build();

        Nd4jEnvironmentConfig newConfig = configService.updateConfiguration(update);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("deleteShapeInfo", newConfig.deleteShapeInfo());
        response.put("deletePrimary", newConfig.deletePrimary());
        response.put("deleteSpecial", newConfig.deleteSpecial());

        if (Boolean.FALSE.equals(newConfig.deletePrimary()) || Boolean.FALSE.equals(newConfig.deleteSpecial())) {
            response.put("warning", "Memory deletion disabled - memory leaks may occur");
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Get current deletion settings.
     */
    @GetMapping("/deletion")
    public ResponseEntity<Map<String, Object>> getDeletionSettings() {
        Nd4jEnvironmentConfig actual = configService.getActualConfiguration();

        Map<String, Object> response = new HashMap<>();
        response.put("deleteShapeInfo", actual.deleteShapeInfo());
        response.put("deletePrimary", actual.deletePrimary());
        response.put("deleteSpecial", actual.deleteSpecial());
        response.put("description", Map.of(
                "deleteShapeInfo", "Delete shape info when no longer needed",
                "deletePrimary", "Delete primary (CPU) memory when arrays are closed",
                "deleteSpecial", "Delete special (GPU) memory when arrays are closed"
        ));
        return ResponseEntity.ok(response);
    }

    /**
     * Update core settings.
     * Controls BLAS enablement and helper allowance.
     */
    @PostMapping("/core")
    public ResponseEntity<Map<String, Object>> updateCoreSettings(
            @RequestParam(required = false) Boolean enableBlas,
            @RequestParam(required = false) Boolean helpersAllowed) {

        log.info("Updating core settings: enableBlas={}, helpersAllowed={}",
                enableBlas, helpersAllowed);

        Nd4jEnvironmentConfig update = Nd4jEnvironmentConfig.builder()
                .enableBlas(enableBlas)
                .helpersAllowed(helpersAllowed)
                .build();

        Nd4jEnvironmentConfig newConfig = configService.updateConfiguration(update);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("enableBlas", newConfig.enableBlas());
        response.put("helpersAllowed", newConfig.helpersAllowed());

        if (Boolean.FALSE.equals(newConfig.enableBlas())) {
            response.put("warning", "BLAS disabled - matrix operations will use fallback implementations");
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Get current core settings.
     */
    @GetMapping("/core")
    public ResponseEntity<Map<String, Object>> getCoreSettings() {
        Nd4jEnvironmentConfig actual = configService.getActualConfiguration();

        Map<String, Object> response = new HashMap<>();
        response.put("enableBlas", actual.enableBlas());
        response.put("helpersAllowed", actual.helpersAllowed());
        response.put("description", Map.of(
                "enableBlas", "Enable BLAS (Basic Linear Algebra Subprograms) for matrix operations",
                "helpersAllowed", "Allow cuDNN/MKLDNN helpers for accelerated operations"
        ));
        return ResponseEntity.ok(response);
    }

    /**
     * Update performance threshold settings.
     * Controls TAD and elementwise operation thresholds.
     */
    @PostMapping("/thresholds")
    public ResponseEntity<Map<String, Object>> updateThresholdSettings(
            @RequestParam(required = false) Integer tadThreshold,
            @RequestParam(required = false) Integer elementwiseThreshold) {

        // Validate inputs
        if (tadThreshold != null && tadThreshold < 0) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "tadThreshold must be >= 0");
            return ResponseEntity.badRequest().body(error);
        }
        if (elementwiseThreshold != null && elementwiseThreshold < 0) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "elementwiseThreshold must be >= 0");
            return ResponseEntity.badRequest().body(error);
        }

        log.info("Updating threshold settings: tadThreshold={}, elementwiseThreshold={}",
                tadThreshold, elementwiseThreshold);

        Nd4jEnvironmentConfig update = Nd4jEnvironmentConfig.builder()
                .tadThreshold(tadThreshold)
                .elementwiseThreshold(elementwiseThreshold)
                .build();

        Nd4jEnvironmentConfig newConfig = configService.updateConfiguration(update);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("tadThreshold", newConfig.tadThreshold());
        response.put("elementwiseThreshold", newConfig.elementwiseThreshold());

        return ResponseEntity.ok(response);
    }

    /**
     * Get current threshold settings.
     */
    @GetMapping("/thresholds")
    public ResponseEntity<Map<String, Object>> getThresholdSettings() {
        Nd4jEnvironmentConfig actual = configService.getActualConfiguration();

        Map<String, Object> response = new HashMap<>();
        response.put("tadThreshold", actual.tadThreshold());
        response.put("elementwiseThreshold", actual.elementwiseThreshold());
        response.put("description", Map.of(
                "tadThreshold", "Minimum number of TADs before parallelization kicks in",
                "elementwiseThreshold", "Minimum number of elements before parallelization kicks in"
        ));
        return ResponseEntity.ok(response);
    }

    /**
     * Update memory limit settings.
     * Controls maximum primary, special, and device memory limits.
     */
    @PostMapping("/memory-limits")
    public ResponseEntity<Map<String, Object>> updateMemoryLimitSettings(
            @RequestParam(required = false) Long maxPrimaryMemory,
            @RequestParam(required = false) Long maxSpecialMemory,
            @RequestParam(required = false) Long maxDeviceMemory) {

        // Validate inputs
        if (maxPrimaryMemory != null && maxPrimaryMemory < 0) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "maxPrimaryMemory must be >= 0 (0 = unlimited)");
            return ResponseEntity.badRequest().body(error);
        }
        if (maxSpecialMemory != null && maxSpecialMemory < 0) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "maxSpecialMemory must be >= 0 (0 = unlimited)");
            return ResponseEntity.badRequest().body(error);
        }
        if (maxDeviceMemory != null && maxDeviceMemory < 0) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "maxDeviceMemory must be >= 0 (0 = unlimited)");
            return ResponseEntity.badRequest().body(error);
        }

        log.info("Updating memory limit settings: maxPrimaryMemory={}, maxSpecialMemory={}, maxDeviceMemory={}",
                maxPrimaryMemory, maxSpecialMemory, maxDeviceMemory);

        Nd4jEnvironmentConfig update = Nd4jEnvironmentConfig.builder()
                .maxPrimaryMemory(maxPrimaryMemory)
                .maxSpecialMemory(maxSpecialMemory)
                .maxDeviceMemory(maxDeviceMemory)
                .build();

        Nd4jEnvironmentConfig newConfig = configService.updateConfiguration(update);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("maxPrimaryMemory", newConfig.maxPrimaryMemory());
        response.put("maxSpecialMemory", newConfig.maxSpecialMemory());
        response.put("maxDeviceMemory", newConfig.maxDeviceMemory());
        response.put("note", "0 = unlimited");

        return ResponseEntity.ok(response);
    }

    /**
     * Get current memory limit settings.
     */
    @GetMapping("/memory-limits")
    public ResponseEntity<Map<String, Object>> getMemoryLimitSettings() {
        Nd4jEnvironmentConfig actual = configService.getActualConfiguration();

        Map<String, Object> response = new HashMap<>();
        response.put("maxPrimaryMemory", actual.maxPrimaryMemory());
        response.put("maxSpecialMemory", actual.maxSpecialMemory());
        response.put("maxDeviceMemory", actual.maxDeviceMemory());
        response.put("description", Map.of(
                "maxPrimaryMemory", "Maximum CPU memory in bytes (0 = unlimited)",
                "maxSpecialMemory", "Maximum special/pinned memory in bytes (0 = unlimited)",
                "maxDeviceMemory", "Maximum GPU memory in bytes (0 = unlimited)"
        ));
        return ResponseEntity.ok(response);
    }

    /**
     * Update JavaCPP settings.
     * Controls JavaCPP debug logging and path prioritization.
     */
    @PostMapping("/javacpp")
    public ResponseEntity<Map<String, Object>> updateJavacppSettings(
            @RequestParam(required = false) Boolean loggerDebug,
            @RequestParam(required = false) Boolean pathsFirst) {

        log.info("Updating JavaCPP settings: loggerDebug={}, pathsFirst={}",
                loggerDebug, pathsFirst);

        Nd4jEnvironmentConfig update = Nd4jEnvironmentConfig.builder()
                .javacppLoggerDebug(loggerDebug)
                .javacppPathsFirst(pathsFirst)
                .build();

        Nd4jEnvironmentConfig newConfig = configService.updateConfiguration(update);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("javacppLoggerDebug", newConfig.javacppLoggerDebug());
        response.put("javacppPathsFirst", newConfig.javacppPathsFirst());
        response.put("note", "These settings are applied via system properties");

        return ResponseEntity.ok(response);
    }

    /**
     * Update Triton compiler settings.
     * Controls GPU kernel compilation and optimization.
     * Only applicable when running on CUDA backend.
     */
    @PostMapping("/triton")
    public ResponseEntity<Map<String, Object>> updateTritonSettings(
            @RequestParam(required = false) Integer buildThreads,
            @RequestParam(required = false) Boolean cacheEnabled,
            @RequestParam(required = false) Boolean verbose,
            @RequestParam(required = false) Boolean alwaysCompile,
            @RequestParam(required = false) Integer numWarps,
            @RequestParam(required = false) Integer numStages,
            @RequestParam(required = false) Integer numCTAs,
            @RequestParam(required = false) Boolean enableFpFusion,
            @RequestParam(required = false) String cacheDir,
            @RequestParam(required = false) String dumpDir,
            @RequestParam(required = false) String overrideArch) {

        log.info("Updating Triton settings: buildThreads={}, numWarps={}, numStages={}, enableFpFusion={}",
                buildThreads, numWarps, numStages, enableFpFusion);

        Nd4jEnvironmentConfig update = Nd4jEnvironmentConfig.builder()
                .tritonBuildThreads(buildThreads)
                .tritonCacheEnabled(cacheEnabled)
                .tritonVerbose(verbose)
                .tritonAlwaysCompile(alwaysCompile)
                .tritonNumWarps(numWarps)
                .tritonNumStages(numStages)
                .tritonNumCTAs(numCTAs)
                .tritonEnableFpFusion(enableFpFusion)
                .tritonCacheDir(cacheDir)
                .tritonDumpDir(dumpDir)
                .tritonOverrideArch(overrideArch)
                .build();

        Nd4jEnvironmentConfig newConfig = configService.updateConfiguration(update);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("tritonBuildThreads", newConfig.tritonBuildThreads());
        response.put("tritonCacheEnabled", newConfig.tritonCacheEnabled());
        response.put("tritonVerbose", newConfig.tritonVerbose());
        response.put("tritonAlwaysCompile", newConfig.tritonAlwaysCompile());
        response.put("tritonNumWarps", newConfig.tritonNumWarps());
        response.put("tritonNumStages", newConfig.tritonNumStages());
        response.put("tritonNumCTAs", newConfig.tritonNumCTAs());
        response.put("tritonEnableFpFusion", newConfig.tritonEnableFpFusion());
        response.put("tritonCacheDir", newConfig.tritonCacheDir());
        response.put("tritonDumpDir", newConfig.tritonDumpDir());
        response.put("tritonOverrideArch", newConfig.tritonOverrideArch());

        return ResponseEntity.ok(response);
    }

    /**
     * Get current Triton compiler settings.
     */
    @GetMapping("/triton")
    public ResponseEntity<Map<String, Object>> getTritonSettings() {
        Nd4jEnvironmentConfig actual = configService.getActualConfiguration();

        Map<String, Object> response = new HashMap<>();
        response.put("tritonBuildThreads", actual.tritonBuildThreads());
        response.put("tritonCacheEnabled", actual.tritonCacheEnabled());
        response.put("tritonVerbose", actual.tritonVerbose());
        response.put("tritonAlwaysCompile", actual.tritonAlwaysCompile());
        response.put("tritonNumWarps", actual.tritonNumWarps());
        response.put("tritonNumStages", actual.tritonNumStages());
        response.put("tritonNumCTAs", actual.tritonNumCTAs());
        response.put("tritonEnableFpFusion", actual.tritonEnableFpFusion());
        response.put("tritonCacheDir", actual.tritonCacheDir());
        response.put("tritonDumpDir", actual.tritonDumpDir());
        response.put("tritonOverrideArch", actual.tritonOverrideArch());
        response.put("isCudaBackend", !org.nd4j.linalg.factory.Nd4j.getEnvironment().isCPU());
        Map<String, String> descriptions = new HashMap<>();
        descriptions.put("buildThreads", "Number of threads for Triton compilation");
        descriptions.put("cacheEnabled", "Cache compiled kernels for reuse");
        descriptions.put("verbose", "Enable verbose compiler output");
        descriptions.put("alwaysCompile", "Force recompilation even with cache");
        descriptions.put("numWarps", "Warps per thread block (0 = auto)");
        descriptions.put("numStages", "Pipeline stages (0 = auto)");
        descriptions.put("numCTAs", "Cooperative thread arrays per kernel");
        descriptions.put("enableFpFusion", "Allow floating-point fusion in kernels");
        descriptions.put("cacheDir", "Directory for cached compiled kernels");
        descriptions.put("dumpDir", "Directory for dumping compiled code");
        descriptions.put("overrideArch", "Override GPU architecture (e.g. sm_80, sm_90)");
        response.put("description", descriptions);
        return ResponseEntity.ok(response);
    }

    /**
     * Get current JavaCPP settings.
     */
    @GetMapping("/javacpp")
    public ResponseEntity<Map<String, Object>> getJavacppSettings() {
        Nd4jEnvironmentConfig actual = configService.getActualConfiguration();

        Map<String, Object> response = new HashMap<>();
        response.put("javacppLoggerDebug", actual.javacppLoggerDebug());
        response.put("javacppPathsFirst", actual.javacppPathsFirst());
        response.put("description", Map.of(
                "javacppLoggerDebug", "Enable JavaCPP debug logging",
                "javacppPathsFirst", "Prioritize JavaCPP paths when loading native libraries"
        ));
        return ResponseEntity.ok(response);
    }

    /**
     * Update SameDiff graph optimizer / DSP (decode-side processing) framework settings.
     * Controls graph optimization, FP16 conversion, and kernel execution modes.
     */
    @PostMapping("/framework")
    public ResponseEntity<Map<String, Object>> updateFrameworkSettings(
            @RequestParam(required = false) Boolean optimizerEnabled,
            @RequestParam(required = false) Boolean optimizerFp16,
            @RequestParam(required = false) Boolean dspNoFreeze,
            @RequestParam(required = false) Boolean dspNoNativeDecode,
            @RequestParam(required = false) Boolean dspNoAttnOverride,
            @RequestParam(required = false) Boolean dspNoDirect,
            @RequestParam(required = false) Boolean tritonSkipKernels,
            @RequestParam(required = false) Boolean tritonTf32,
            @RequestParam(required = false) Boolean cublasDisableWorkspace,
            @RequestParam(required = false) String dspDiagnostics,
            @RequestParam(required = false) Boolean opTiming) {

        log.info("Updating SameDiff framework settings: optimizerEnabled={}, optimizerFp16={}, dspNoFreeze={}, " +
                        "dspNoNativeDecode={}, dspNoAttnOverride={}, dspNoDirect={}, tritonSkipKernels={}, " +
                        "tritonTf32={}, cublasDisableWorkspace={}, dspDiagnostics={}, opTiming={}",
                optimizerEnabled, optimizerFp16, dspNoFreeze, dspNoNativeDecode, dspNoAttnOverride,
                dspNoDirect, tritonSkipKernels, tritonTf32, cublasDisableWorkspace, dspDiagnostics, opTiming);

        Nd4jEnvironmentConfig update = Nd4jEnvironmentConfig.builder()
                .optimizerEnabled(optimizerEnabled)
                .optimizerFp16(optimizerFp16)
                .dspNoFreeze(dspNoFreeze)
                .dspNoNativeDecode(dspNoNativeDecode)
                .dspNoAttnOverride(dspNoAttnOverride)
                .dspNoDirect(dspNoDirect)
                .tritonSkipKernels(tritonSkipKernels)
                .tritonTf32(tritonTf32)
                .cublasDisableWorkspace(cublasDisableWorkspace)
                .dspDiagnostics(dspDiagnostics)
                .opTiming(opTiming)
                .build();

        Nd4jEnvironmentConfig newConfig = configService.updateConfiguration(update);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("optimizerEnabled", newConfig.optimizerEnabled());
        response.put("optimizerFp16", newConfig.optimizerFp16());
        response.put("dspNoFreeze", newConfig.dspNoFreeze());
        response.put("dspNoNativeDecode", newConfig.dspNoNativeDecode());
        response.put("dspNoAttnOverride", newConfig.dspNoAttnOverride());
        response.put("dspNoDirect", newConfig.dspNoDirect());
        response.put("tritonSkipKernels", newConfig.tritonSkipKernels());
        response.put("tritonTf32", newConfig.tritonTf32());
        response.put("cublasDisableWorkspace", newConfig.cublasDisableWorkspace());
        response.put("dspDiagnostics", newConfig.dspDiagnostics());
        response.put("opTiming", newConfig.opTiming());

        // Add warnings for potentially impactful settings
        if (Boolean.FALSE.equals(newConfig.optimizerEnabled())) {
            response.put("warning", "Graph optimizer disabled - performance may be significantly impacted");
        }
        if (Boolean.TRUE.equals(newConfig.dspDiagnostics())) {
            response.put("info", "DSP diagnostics enabled - verbose output will be generated");
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Get current SameDiff graph optimizer / DSP framework settings.
     * Returns both configuration toggles and live framework subsystem status from Nd4j.framework API.
     */
    @GetMapping("/framework")
    public ResponseEntity<Map<String, Object>> getFrameworkSettings() {
        Nd4jEnvironmentConfig actual = configService.getActualConfiguration();

        Map<String, Object> response = new HashMap<>();
        response.put("optimizerEnabled", actual.optimizerEnabled());
        response.put("optimizerFp16", actual.optimizerFp16());
        response.put("dspNoFreeze", actual.dspNoFreeze());
        response.put("dspNoNativeDecode", actual.dspNoNativeDecode());
        response.put("dspNoAttnOverride", actual.dspNoAttnOverride());
        response.put("dspNoDirect", actual.dspNoDirect());
        response.put("tritonSkipKernels", actual.tritonSkipKernels());
        response.put("tritonTf32", actual.tritonTf32());
        response.put("cublasDisableWorkspace", actual.cublasDisableWorkspace());
        response.put("dspDiagnostics", actual.dspDiagnostics());
        response.put("opTiming", actual.opTiming());

        Map<String, String> descriptions = new HashMap<>();
        descriptions.put("optimizerEnabled", "Enable SameDiff graph optimizer for improved performance");
        descriptions.put("optimizerFp16", "Convert weights to FP16 to reduce VRAM usage");
        descriptions.put("dspNoFreeze", "Disable graph freezing (false = freeze enabled)");
        descriptions.put("dspNoNativeDecode", "Disable native decode inputs (false = native decode enabled)");
        descriptions.put("dspNoAttnOverride", "Disable attention override (false = override enabled)");
        descriptions.put("dspNoDirect", "Disable direct mode (false = direct mode enabled)");
        descriptions.put("tritonSkipKernels", "Skip Triton kernels (false = Triton kernels enabled)");
        descriptions.put("tritonTf32", "Enable TF32 precision for Triton kernels");
        descriptions.put("cublasDisableWorkspace", "Disable cuBLAS workspace capture (true = disabled)");
        descriptions.put("dspDiagnostics", "Enable DSP diagnostics (e.g. 'ALL' for verbose output)");
        descriptions.put("opTiming", "Enable operation timing for performance analysis");

        response.put("description", descriptions);
        response.put("isCudaBackend", !org.nd4j.linalg.factory.Nd4j.getEnvironment().isCPU());

        // === Nd4j.framework subsystem status ===
        try {
            var framework = org.nd4j.linalg.factory.Nd4j.framework;

            // Execution subsystem
            Map<String, Object> execution = new HashMap<>();
            try {
                var execEnv = framework.execution().environment();
                execution.put("dspEnabled", execEnv.isDspEnabled());
                execution.put("summary", execEnv.getSummary());
            } catch (Exception e) {
                execution.put("error", e.getMessage());
            }

            // DSP diagnostics subsystem
            Map<String, Object> dsp = new HashMap<>();
            try {
                var dspSub = framework.execution().dsp();
                dsp.put("enabledCategories", dspSub.getEnabledCategories());
                dsp.put("planReport", dspSub.getPlanReport());
            } catch (Exception e) {
                dsp.put("error", e.getMessage());
            }

            // Memory subsystem
            Map<String, Object> memory = new HashMap<>();
            try {
                var memStats = framework.memory().stats();
                memory.put("heapUsedBytes", memStats.getHeapUsedBytes());
                memory.put("heapMaxBytes", memStats.getHeapMaxBytes());
                var memMgr = framework.memory().manager();
                memory.put("gcFrequency", memMgr.getGcFrequency());
                memory.put("managerType", memMgr.getType());
            } catch (Exception e) {
                memory.put("error", e.getMessage());
            }

            // Profiling subsystem
            Map<String, Object> profiling = new HashMap<>();
            try {
                profiling.put("enabled", framework.profiling().isEnabled());
                var profilingEnv = framework.profiling().environment();
                profiling.put("frequency", profilingEnv.getProfilingFrequency());
            } catch (Exception e) {
                profiling.put("error", e.getMessage());
            }

            // Lifecycle subsystem
            Map<String, Object> lifecycle = new HashMap<>();
            try {
                lifecycle.put("totalCreated", framework.lifecycle().totalCreated());
                lifecycle.put("totalDestroyed", framework.lifecycle().totalDestroyed());
                lifecycle.put("liveCount", framework.lifecycle().liveCount());
            } catch (Exception e) {
                lifecycle.put("error", e.getMessage());
            }

            // Device subsystem
            Map<String, Object> device = new HashMap<>();
            try {
                var deviceInfo = framework.device().info();
                device.put("count", deviceInfo.count());
                device.put("hasGpu", deviceInfo.hasGpu());
                device.put("multiDevice", deviceInfo.isMultiDevice());
                device.put("summaries", deviceInfo.getAllSummaries());
            } catch (Exception e) {
                device.put("error", e.getMessage());
            }

            // Workspace subsystem
            Map<String, Object> workspaces = new HashMap<>();
            try {
                var wsEnv = framework.workspaces().environment();
                workspaces.put("defaultSize", wsEnv.getDefaultWorkspaceSize());
                workspaces.put("initialSize", wsEnv.getWorkspaceInitialSize());
                workspaces.put("learningEnabled", wsEnv.isWorkspaceLearningEnabled());
                workspaces.put("debugMode", wsEnv.getWorkspaceDebugMode());
            } catch (Exception e) {
                workspaces.put("error", e.getMessage());
            }

            // Constant cache subsystem
            Map<String, Object> constants = new HashMap<>();
            try {
                constants.put("cachedMb", framework.constants().getCachedMb());
                constants.put("tadCacheEntries", framework.constants().getTadCacheEntries());
            } catch (Exception e) {
                constants.put("error", e.getMessage());
            }

            Map<String, Object> subsystems = new HashMap<>();
            subsystems.put("execution", execution);
            subsystems.put("dsp", dsp);
            subsystems.put("memory", memory);
            subsystems.put("profiling", profiling);
            subsystems.put("lifecycle", lifecycle);
            subsystems.put("device", device);
            subsystems.put("workspaces", workspaces);
            subsystems.put("constants", constants);
            response.put("subsystems", subsystems);

        } catch (Exception e) {
            log.debug("Could not read Nd4j.framework subsystem status: {}", e.getMessage());
            response.put("subsystems", Map.of("error", "Framework API not available: " + e.getMessage()));
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Apply a framework preset configuration.
     * Available presets: performance, debug, minimal, balanced
     */
    @PostMapping("/framework/preset/{presetName}")
    public ResponseEntity<Map<String, Object>> applyFrameworkPreset(@PathVariable String presetName) {
        log.info("Applying SameDiff framework preset: {}", presetName);

        try {
            Nd4jEnvironmentConfig newConfig = configService.applyFrameworkPreset(presetName);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("preset", presetName);
            response.put("message", "Framework preset '" + presetName + "' applied and persisted");
            response.put("config", Map.ofEntries(
                    Map.entry("optimizerEnabled", newConfig.optimizerEnabled()),
                    Map.entry("optimizerFp16", newConfig.optimizerFp16()),
                    Map.entry("dspNoFreeze", newConfig.dspNoFreeze()),
                    Map.entry("dspNoNativeDecode", newConfig.dspNoNativeDecode()),
                    Map.entry("dspNoAttnOverride", newConfig.dspNoAttnOverride()),
                    Map.entry("dspNoDirect", newConfig.dspNoDirect()),
                    Map.entry("tritonSkipKernels", newConfig.tritonSkipKernels()),
                    Map.entry("tritonTf32", newConfig.tritonTf32()),
                    Map.entry("cublasDisableWorkspace", newConfig.cublasDisableWorkspace()),
                    Map.entry("dspDiagnostics", newConfig.dspDiagnostics()),
                    Map.entry("opTiming", newConfig.opTiming())
            ));
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", e.getMessage());
            error.put("availablePresets", List.of("performance", "debug", "minimal", "balanced"));
            return ResponseEntity.badRequest().body(error);
        }
    }
}
