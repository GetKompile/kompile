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
 *  limitations under the License.
 */

package ai.kompile.staging.web;

import ai.kompile.staging.config.PerformanceProfile;
import ai.kompile.staging.web.dto.*;
import org.nd4j.linalg.api.environment.Nd4jEnvironment;
import org.nd4j.linalg.factory.Environment;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.nativeblas.NativeOps;
import org.nd4j.nativeblas.NativeOpsHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API controller for ND4J Environment configuration.
 * Provides endpoints to read and modify the ND4J runtime environment settings
 * including memory management, CUDA, Triton compiler, DSP, debugging, lifecycle tracking,
 * and LLM benchmark config presets.
 */
@ConditionalOnClass(name = "ai.kompile.staging.catalog.CatalogService")
@ConditionalOnProperty(name = "kompile.staging.app.enabled", havingValue = "true")
@RestController
@RequestMapping("/api/environment")
@CrossOrigin(origins = "*")
public class EnvironmentController {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentController.class);

    // ==================== Full Environment ====================

    /**
     * Get all environment settings as JSON.
     */
    @GetMapping
    public ResponseEntity<EnvironmentResponse> getEnvironment() {
        try {
            var env = Nd4j.getEnvironment();
            EnvironmentResponse response = EnvironmentResponse.builder()
                    .cpu(env.isCPU())
                    .blasMajorVersion(env.blasMajorVersion())
                    .blasMinorVersion(env.blasMinorVersion())
                    .blasPatchVersion(env.blasPatchVersion())
                    .enableBlas(env.isEnableBlas())
                    .maxPrimaryMemory(getMaxPrimaryMemory(env))
                    .maxSpecialMemory(getMaxSpecialMemory(env))
                    .maxDeviceMemory(getMaxDeviceMemory(env))
                    .verbose(env.isVerbose())
                    .debug(env.isDebug())
                    .profiling(env.isProfiling())
                    .detectingLeaks(env.isDetectingLeaks())
                    .helpersAllowed(env.helpersAllowed())
                    .logNativeNDArrayCreation(env.isLogNativeNDArrayCreation())
                    .checkOutputChange(env.isCheckOutputChange())
                    .checkInputChange(env.isCheckInputChange())
                    .lifecycleTracking(env.isLifecycleTracking())
                    .trackViews(env.isTrackViews())
                    .trackDeletions(env.isTrackDeletions())
                    .ndArrayTracking(env.isNDArrayTracking())
                    .dataBufferTracking(env.isDataBufferTracking())
                    .stackDepth(env.getStackDepth())
                    .reportInterval(env.getReportInterval())
                    .maxDeletionHistory(env.getMaxDeletionHistory())
                    .tadThreshold(env.tadThreshold())
                    .elementwiseThreshold(env.elementwiseThreshold())
                    .maxThreads(env.maxThreads())
                    .maxMasterThreads(env.maxMasterThreads())
                    .gpuAvailable(!env.isCPU())
                    .cuda(buildCudaResponse(env))
                    .triton(buildTritonResponse(env))
                    .dsp(buildDspResponse(env))
                    .build();
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to read environment settings", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Update environment settings.
     */
    @PutMapping
    public ResponseEntity<Map<String, Object>> updateEnvironment(@RequestBody Map<String, Object> settings) {
        try {
            var env = Nd4j.getEnvironment();
            int updated = 0;

            if (settings.containsKey("enableBlas")) {
                env.setEnableBlas((Boolean) settings.get("enableBlas"));
                updated++;
            }
            if (settings.containsKey("maxPrimaryMemory")) {
                env.setMaxPrimaryMemory(toLong(settings.get("maxPrimaryMemory")));
                updated++;
            }
            if (settings.containsKey("maxSpecialMemory")) {
                env.setMaxSpecialMemory(toLong(settings.get("maxSpecialMemory")));
                updated++;
            }
            if (settings.containsKey("maxDeviceMemory")) {
                env.setMaxDeviceMemory(toLong(settings.get("maxDeviceMemory")));
                updated++;
            }
            if (settings.containsKey("verbose")) {
                env.setVerbose((Boolean) settings.get("verbose"));
                updated++;
            }
            if (settings.containsKey("debug")) {
                env.setDebug((Boolean) settings.get("debug"));
                updated++;
            }
            if (settings.containsKey("profiling")) {
                env.setProfiling((Boolean) settings.get("profiling"));
                updated++;
            }
            if (settings.containsKey("tadThreshold")) {
                env.setTadThreshold(toInt(settings.get("tadThreshold")));
                updated++;
            }
            if (settings.containsKey("elementwiseThreshold")) {
                env.setElementwiseThreshold(toInt(settings.get("elementwiseThreshold")));
                updated++;
            }
            if (settings.containsKey("maxThreads")) {
                env.setMaxThreads(toInt(settings.get("maxThreads")));
                updated++;
            }
            if (settings.containsKey("maxMasterThreads")) {
                env.setMaxMasterThreads(toInt(settings.get("maxMasterThreads")));
                updated++;
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("updatedCount", updated);
            response.put("message", "Updated " + updated + " settings");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to update environment settings", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "error", "Failed to update settings: " + e.getMessage()
            ));
        }
    }

    // ==================== Memory ====================

    /**
     * Get memory stats (primary, special, device allocations).
     */
    @GetMapping("/memory")
    public ResponseEntity<MemorySettingsResponse> getMemorySettings() {
        try {
            var env = Nd4j.getEnvironment();
            Runtime runtime = Runtime.getRuntime();
            MemorySettingsResponse response = MemorySettingsResponse.builder()
                    .enableBlas(env.isEnableBlas())
                    .maxPrimaryMemory(getMaxPrimaryMemory(env))
                    .maxSpecialMemory(getMaxSpecialMemory(env))
                    .maxDeviceMemory(getMaxDeviceMemory(env))
                    .jvmMaxMemory(runtime.maxMemory())
                    .jvmTotalMemory(runtime.totalMemory())
                    .jvmFreeMemory(runtime.freeMemory())
                    .build();
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to read memory settings", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== CUDA ====================

    /**
     * Get CUDA device info and settings.
     */
    @GetMapping("/cuda")
    public ResponseEntity<CudaSettingsResponse> getCudaSettings() {
        try {
            var env = Nd4j.getEnvironment();
            CudaSettingsResponse response = buildCudaResponse(env);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to read CUDA settings", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Update CUDA settings.
     */
    @PutMapping("/cuda")
    public ResponseEntity<Map<String, Object>> updateCudaSettings(@RequestBody CudaSettingsRequest request) {
        try {
            var env = Nd4j.getEnvironment();
            if (env.isCPU()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "CUDA is not available on CPU backend"
                ));
            }

            int updated = 0;
            if (request.getCurrentDevice() != null) {
                env.setCudaCurrentDevice(request.getCurrentDevice());
                updated++;
            }
            if (request.getMemoryPinned() != null) {
                env.setCudaMemoryPinned(request.getMemoryPinned());
                updated++;
            }
            if (request.getUseManagedMemory() != null) {
                env.setCudaUseManagedMemory(request.getUseManagedMemory());
                updated++;
            }
            if (request.getMemoryPoolSize() != null) {
                env.setCudaMemoryPoolSize(request.getMemoryPoolSize().intValue());
                updated++;
            }
            if (request.getMaxBlocks() != null) {
                env.setCudaMaxBlocks(request.getMaxBlocks());
                updated++;
            }
            if (request.getAsyncExecution() != null) {
                env.setCudaAsyncExecution(request.getAsyncExecution());
                updated++;
            }
            if (request.getTensorCoreEnabled() != null) {
                env.setCudaTensorCoreEnabled(request.getTensorCoreEnabled());
                updated++;
            }
            if (request.getGraphOptimization() != null) {
                env.setCudaGraphOptimization(request.getGraphOptimization());
                updated++;
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("updatedCount", updated);
            response.put("message", "Updated " + updated + " CUDA settings");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to update CUDA settings", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "error", "Failed to update CUDA settings: " + e.getMessage()
            ));
        }
    }

    // ==================== Triton ====================

    /**
     * Get Triton compiler settings.
     */
    @GetMapping("/triton")
    public ResponseEntity<TritonSettingsResponse> getTritonSettings() {
        try {
            var env = Nd4j.getEnvironment();
            TritonSettingsResponse response = buildTritonResponse(env);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to read Triton settings", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Update Triton settings.
     */
    @PutMapping("/triton")
    public ResponseEntity<Map<String, Object>> updateTritonSettings(@RequestBody TritonSettingsRequest request) {
        try {
            var env = Nd4j.getEnvironment();
            int updated = 0;

            // Compiler settings
            if (request.getBuildThreads() != null) { env.setTritonBuildThreads(request.getBuildThreads()); updated++; }
            if (request.getCacheEnabled() != null) { env.setTritonCacheEnabled(request.getCacheEnabled()); updated++; }
            if (request.getVerbose() != null) { env.setTritonVerbose(request.getVerbose()); updated++; }
            if (request.getAlwaysCompile() != null) { env.setTritonAlwaysCompile(request.getAlwaysCompile()); updated++; }
            if (request.getNumWarps() != null) { env.setTritonNumWarps(request.getNumWarps()); updated++; }
            if (request.getNumStages() != null) { env.setTritonNumStages(request.getNumStages()); updated++; }
            if (request.getNumCTAs() != null) { env.setTritonNumCTAs(request.getNumCTAs()); updated++; }
            if (request.getEnableFpFusion() != null) { env.setTritonEnableFpFusion(request.getEnableFpFusion()); updated++; }
            if (request.getCacheDir() != null) { env.setTritonCacheDir(request.getCacheDir()); updated++; }
            if (request.getDumpDir() != null) { env.setTritonDumpDir(request.getDumpDir()); updated++; }
            if (request.getOverrideArch() != null) { env.setTritonOverrideArch(request.getOverrideArch()); updated++; }
            if (request.getDisableLineInfo() != null) { env.setTritonDisableLineInfo(request.getDisableLineInfo()); updated++; }
            if (request.getMaxNreg() != null) { env.setTritonMaxNreg(request.getMaxNreg()); updated++; }
            if (request.getAttentionBlockN() != null) { env.setTritonAttentionBlockN(request.getAttentionBlockN()); updated++; }

            // Compilation mode
            if (request.getCompileAll() != null) { env.setTritonCompileAll(request.getCompileAll()); updated++; }
            if (request.getIncludeTypes() != null) { env.setTritonIncludeTypes(request.getIncludeTypes()); updated++; }
            if (request.getExcludeOps() != null) { env.setTritonExcludeOps(request.getExcludeOps()); updated++; }

            // Section fusion
            if (request.getSectionFusion() != null) { env.setTritonSectionFusion(request.getSectionFusion()); updated++; }
            if (request.getFusionScoring() != null) { env.setTritonFusionScoring(request.getFusionScoring()); updated++; }
            if (request.getFusionMinScore() != null) { env.setTritonFusionMinScore(request.getFusionMinScore()); updated++; }

            // Graph capture
            if (request.getGraphCapture() != null) { env.setTritonGraphCapture(request.getGraphCapture()); updated++; }
            if (request.getAllowFallbackCapture() != null) { env.setTritonAllowFallbackCapture(request.getAllowFallbackCapture()); updated++; }
            if (request.getForceRecapture() != null) { env.setTritonForceRecapture(request.getForceRecapture()); updated++; }
            if (request.getCaptureMinExec() != null) { env.setTritonCaptureMinExec(request.getCaptureMinExec()); updated++; }

            // Arg table
            if (request.getConsolidatedArgTable() != null) { env.setTritonConsolidatedArgTable(request.getConsolidatedArgTable()); updated++; }
            if (request.getArgDirtyTracking() != null) { env.setTritonArgDirtyTracking(request.getArgDirtyTracking()); updated++; }

            // Cooperative launch
            if (request.getCooperativeLaunch() != null) { env.setTritonCooperativeLaunch(request.getCooperativeLaunch()); updated++; }
            if (request.getCoopTargetBlocks() != null) { env.setTritonCoopTargetBlocks(request.getCoopTargetBlocks()); updated++; }

            // Debug/verification
            if (request.getSkipKernels() != null) { env.setTritonSkipKernels(request.getSkipKernels()); updated++; }
            if (request.getVerifyKernels() != null) { env.setTritonVerifyKernels(request.getVerifyKernels()); updated++; }
            if (request.getVerifyFullSnapshot() != null) { env.setTritonVerifyFullSnapshot(request.getVerifyFullSnapshot()); updated++; }
            if (request.getDumpSections() != null) { env.setTritonDumpSections(request.getDumpSections()); updated++; }
            if (request.getDumpArgs() != null) { env.setTritonDumpArgs(request.getDumpArgs()); updated++; }

            // Subsegment limits
            if (request.getMaxSubsegmentOps() != null) { env.setTritonMaxSubsegmentOps(request.getMaxSubsegmentOps()); updated++; }
            if (request.getMaxSubsegmentSections() != null) { env.setTritonMaxSubsegmentSections(request.getMaxSubsegmentSections()); updated++; }

            // Segment fusion optimization flags
            if (request.getFuseIdentityShapes() != null) { env.setTritonFuseIdentityShapes(request.getFuseIdentityShapes()); updated++; }
            if (request.getFuseCastChains() != null) { env.setTritonFuseCastChains(request.getFuseCastChains()); updated++; }
            if (request.getSpecializePermuteSeq1() != null) { env.setTritonSpecializePermuteSeq1(request.getSpecializePermuteSeq1()); updated++; }
            if (request.getFusedMatmul() != null) { env.setTritonFusedMatmul(request.getFusedMatmul()); updated++; }
            if (request.getFuseAttentionNeighborhoods() != null) { env.setTritonFuseAttentionNeighborhoods(request.getFuseAttentionNeighborhoods()); updated++; }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("updatedCount", updated);
            response.put("message", "Updated " + updated + " Triton settings");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to update Triton settings", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "error", "Failed to update Triton settings: " + e.getMessage()
            ));
        }
    }

    // ==================== Debug ====================

    @GetMapping("/debug")
    public ResponseEntity<DebugSettingsResponse> getDebugSettings() {
        try {
            var env = Nd4j.getEnvironment();
            DebugSettingsResponse response = DebugSettingsResponse.builder()
                    .verbose(env.isVerbose())
                    .debug(env.isDebug())
                    .profiling(env.isProfiling())
                    .detectingLeaks(env.isDetectingLeaks())
                    .helpersAllowed(env.helpersAllowed())
                    .logNativeNDArrayCreation(env.isLogNativeNDArrayCreation())
                    .checkOutputChange(env.isCheckOutputChange())
                    .checkInputChange(env.isCheckInputChange())
                    .build();
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to read debug settings", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PutMapping("/debug")
    public ResponseEntity<Map<String, Object>> updateDebugSettings(@RequestBody DebugSettingsRequest request) {
        try {
            var env = Nd4j.getEnvironment();
            int updated = 0;

            if (request.getVerbose() != null) { env.setVerbose(request.getVerbose()); updated++; }
            if (request.getDebug() != null) { env.setDebug(request.getDebug()); updated++; }
            if (request.getProfiling() != null) { env.setProfiling(request.getProfiling()); updated++; }
            if (request.getDetectingLeaks() != null) { env.setLeaksDetector(request.getDetectingLeaks()); updated++; }
            if (request.getHelpersAllowed() != null) { env.allowHelpers(request.getHelpersAllowed()); updated++; }
            if (request.getLogNativeNDArrayCreation() != null) { env.setLogNativeNDArrayCreation(request.getLogNativeNDArrayCreation()); updated++; }
            if (request.getCheckOutputChange() != null) { env.setCheckOutputChange(request.getCheckOutputChange()); updated++; }
            if (request.getCheckInputChange() != null) { env.setCheckInputChange(request.getCheckInputChange()); updated++; }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("updatedCount", updated);
            response.put("message", "Updated " + updated + " debug settings");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to update debug settings", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "error", "Failed to update debug settings: " + e.getMessage()
            ));
        }
    }

    // ==================== Lifecycle ====================

    @GetMapping("/lifecycle")
    public ResponseEntity<LifecycleSettingsResponse> getLifecycleSettings() {
        try {
            var env = Nd4j.getEnvironment();
            LifecycleSettingsResponse response = LifecycleSettingsResponse.builder()
                    .lifecycleTracking(env.isLifecycleTracking())
                    .trackViews(env.isTrackViews())
                    .trackDeletions(env.isTrackDeletions())
                    .ndArrayTracking(env.isNDArrayTracking())
                    .dataBufferTracking(env.isDataBufferTracking())
                    .stackDepth(env.getStackDepth())
                    .reportInterval(env.getReportInterval())
                    .maxDeletionHistory(env.getMaxDeletionHistory())
                    .build();
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to read lifecycle settings", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PutMapping("/lifecycle")
    public ResponseEntity<Map<String, Object>> updateLifecycleSettings(@RequestBody LifecycleSettingsRequest request) {
        try {
            var env = Nd4j.getEnvironment();
            int updated = 0;

            if (request.getLifecycleTracking() != null) { env.setLifecycleTracking(request.getLifecycleTracking()); updated++; }
            if (request.getTrackViews() != null) { env.setTrackViews(request.getTrackViews()); updated++; }
            if (request.getTrackDeletions() != null) { env.setTrackDeletions(request.getTrackDeletions()); updated++; }
            if (request.getNdArrayTracking() != null) { env.setNDArrayTracking(request.getNdArrayTracking()); updated++; }
            if (request.getDataBufferTracking() != null) { env.setDataBufferTracking(request.getDataBufferTracking()); updated++; }
            if (request.getStackDepth() != null) { env.setStackDepth(request.getStackDepth()); updated++; }
            if (request.getReportInterval() != null) { env.setReportInterval(request.getReportInterval()); updated++; }
            if (request.getMaxDeletionHistory() != null) { env.setMaxDeletionHistory(request.getMaxDeletionHistory()); updated++; }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("updatedCount", updated);
            response.put("message", "Updated " + updated + " lifecycle settings");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to update lifecycle settings", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "error", "Failed to update lifecycle settings: " + e.getMessage()
            ));
        }
    }

    // ==================== Per-Device & Native Cache ====================

    @GetMapping("/devices")
    public ResponseEntity<DeviceCacheStatusResponse> getDeviceCacheStatus() {
        try {
            NativeOps nativeOps = NativeOpsHolder.getInstance().getDeviceNativeOps();
            int numDevices = nativeOps.getAvailableDevices();

            List<DeviceCacheStatusResponse.DeviceInfo> devices = new ArrayList<>();
            for (int i = 0; i < numDevices; i++) {
                long free = nativeOps.getDeviceFreeMemory(i);
                long total = nativeOps.getDeviceTotalMemory(i);
                long used = total - free;
                double utilization = total > 0 ? (used * 100.0 / total) : 0.0;

                devices.add(DeviceCacheStatusResponse.DeviceInfo.builder()
                        .deviceId(i)
                        .deviceName(nativeOps.getDeviceName(i))
                        .freeMemoryBytes(free)
                        .totalMemoryBytes(total)
                        .usedMemoryBytes(used)
                        .memoryUtilizationPercent(Math.round(utilization * 100.0) / 100.0)
                        .computeMajor(nativeOps.getDeviceMajor(i))
                        .computeMinor(nativeOps.getDeviceMinor(i))
                        .computeCapability(nativeOps.getDeviceMajor(i) + "." + nativeOps.getDeviceMinor(i))
                        .build());
            }

            DeviceCacheStatusResponse.NativeCacheInfo tadCache = DeviceCacheStatusResponse.NativeCacheInfo.builder()
                    .cacheType("TAD")
                    .cachedEntries(nativeOps.getTADCachedEntries())
                    .cachedBytes(nativeOps.getTADCachedBytes())
                    .peakCachedEntries(nativeOps.getTADPeakCachedEntries())
                    .peakCachedBytes(nativeOps.getTADPeakCachedBytes())
                    .cacheContents(nativeOps.getTADCacheString(3, 50))
                    .build();

            DeviceCacheStatusResponse.NativeCacheInfo shapeCache = DeviceCacheStatusResponse.NativeCacheInfo.builder()
                    .cacheType("Shape")
                    .cachedEntries(nativeOps.getShapeCachedEntries())
                    .cachedBytes(nativeOps.getShapeCachedBytes())
                    .peakCachedEntries(nativeOps.getShapePeakCachedEntries())
                    .peakCachedBytes(nativeOps.getShapePeakCachedBytes())
                    .cacheContents(nativeOps.getShapeCacheString(3, 50))
                    .build();

            DeviceCacheStatusResponse response = DeviceCacheStatusResponse.builder()
                    .availableDevices(numDevices)
                    .devices(devices)
                    .tadCache(tadCache)
                    .shapeCache(shapeCache)
                    .build();

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to get device cache status", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/devices/cache/clear")
    public ResponseEntity<Map<String, Object>> clearNativeCache(
            @RequestParam(defaultValue = "all") String type) {
        try {
            log.info("Clearing native cache: {}", type);
            NativeOps nativeOps = NativeOpsHolder.getInstance().getDeviceNativeOps();
            Map<String, Object> result = new LinkedHashMap<>();

            switch (type.toLowerCase()) {
                case "tad":
                    nativeOps.clearTADCache();
                    result.put("cleared", "tad");
                    break;
                case "shape":
                    nativeOps.clearShapeCache();
                    result.put("cleared", "shape");
                    break;
                case "cleanup":
                    nativeOps.checkAndCleanupCaches();
                    result.put("cleared", "cleanup");
                    break;
                case "all":
                default:
                    nativeOps.clearTADCache();
                    nativeOps.clearShapeCache();
                    result.put("cleared", "all");
                    break;
            }

            result.put("success", true);
            result.put("message", "Native cache cleared: " + type);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to clear native cache", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "error", "Failed to clear native cache: " + e.getMessage()
            ));
        }
    }

    // ==================== DSP ====================

    @GetMapping("/dsp")
    public ResponseEntity<DspSettingsResponse> getDspSettings() {
        try {
            var env = Nd4j.getEnvironment();
            DspSettingsResponse response = buildDspResponse(env);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to read DSP settings", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PutMapping("/dsp")
    public ResponseEntity<Map<String, Object>> updateDspSettings(@RequestBody DspSettingsRequest request) {
        try {
            var env = Nd4j.getEnvironment();
            int updated = 0;

            if (request.getBatchedGemm() != null) { env.setDspBatchedGemm(request.getBatchedGemm()); updated++; }
            if (request.getCublasTf32() != null) { env.setCublasTf32Enabled(request.getCublasTf32()); updated++; }
            if (request.getCublasCaptureWorkspace() != null) { env.setCublasCaptureWorkspace(request.getCublasCaptureWorkspace()); updated++; }
            if (request.getFp16Compute() != null) { env.setDspFp16Compute(request.getFp16Compute()); updated++; }
            if (request.getMatmulSegmentation() != null) { env.setDspMatmulSegmentation(request.getMatmulSegmentation()); updated++; }
            if (request.getCastElimination() != null) { env.setDspCastElimination(request.getCastElimination()); updated++; }
            if (request.getCastSinkMatmul() != null) { env.setDspCastSinkMatmul(request.getCastSinkMatmul()); updated++; }
            if (request.getBatchZero() != null) { env.setDspBatchZero(request.getBatchZero()); updated++; }
            if (request.getBatchZeroKernel() != null) { env.setDspBatchZeroKernel(request.getBatchZeroKernel()); updated++; }
            if (request.getSymbolicShapes() != null) { env.setDspSymbolicShapes(request.getSymbolicShapes()); updated++; }
            if (request.getCapturePoolEnabled() != null) { env.setDspCapturePoolEnabled(request.getCapturePoolEnabled()); updated++; }
            if (request.getCapturePoolMaxBytes() != null) { env.setDspCapturePoolMaxBytes(request.getCapturePoolMaxBytes()); updated++; }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("updatedCount", updated);
            response.put("message", "Updated " + updated + " DSP settings");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to update DSP settings", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "error", "Failed to update DSP settings: " + e.getMessage()
            ));
        }
    }

    // ==================== Performance Profiles ====================

    @GetMapping("/profiles")
    public ResponseEntity<List<Map<String, Object>>> getPerformanceProfiles() {
        try {
            List<Map<String, Object>> profiles = new ArrayList<>();
            for (PerformanceProfile profile : PerformanceProfile.values()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", profile.name());
                entry.put("name", profile.getDisplayName());
                entry.put("description", profile.getDescription());
                entry.put("settings", profile.getRecommendedSettings());
                profiles.add(entry);
            }
            return ResponseEntity.ok(profiles);
        } catch (Exception e) {
            log.error("Failed to get performance profiles", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/profiles/apply")
    public ResponseEntity<Map<String, Object>> applyPerformanceProfile(@RequestParam String profile) {
        try {
            PerformanceProfile perfProfile;
            try {
                perfProfile = PerformanceProfile.valueOf(profile.toUpperCase());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "Unknown profile: " + profile + ". Available: " + java.util.Arrays.toString(PerformanceProfile.values())
                ));
            }

            var env = Nd4j.getEnvironment();
            Map<String, Object> settings = perfProfile.getRecommendedSettings();
            int applied = applySettingsMap(env, settings);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("profile", perfProfile.name());
            response.put("profileName", perfProfile.getDisplayName());
            response.put("appliedCount", applied);
            response.put("message", "Applied profile '" + perfProfile.getDisplayName() + "' with " + applied + " settings");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to apply performance profile", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "error", "Failed to apply profile: " + e.getMessage()
            ));
        }
    }

    // ==================== LLM Config Presets ====================

    /**
     * Apply an LLM benchmark config preset directly using the Environment interface methods.
     * Presets: optimal, basic, debug
     */
    @PostMapping("/llm-config/apply")
    public ResponseEntity<Map<String, Object>> applyLlmConfig(@RequestParam String preset) {
        try {
            var env = Nd4j.getEnvironment();
            String presetName;
            switch (preset.toLowerCase()) {
                case "optimal":
                    env.applyOptimalLLMConfig();
                    presetName = "LLM Optimal";
                    break;
                case "basic":
                    env.applyBasicLLMConfig();
                    presetName = "LLM Basic";
                    break;
                case "debug":
                    env.applyDebugLLMConfig();
                    presetName = "LLM Debug";
                    break;
                default:
                    return ResponseEntity.badRequest().body(Map.of(
                            "success", false,
                            "error", "Unknown LLM preset: " + preset + ". Available: optimal, basic, debug"
                    ));
            }

            log.info("Applied LLM config preset: {}", presetName);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("preset", preset.toLowerCase());
            response.put("presetName", presetName);
            response.put("message", "Applied LLM config preset: " + presetName);
            response.put("triton", buildTritonResponse(env));
            response.put("dsp", buildDspResponse(env));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to apply LLM config preset", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "error", "Failed to apply LLM config: " + e.getMessage()
            ));
        }
    }

    /**
     * Get available LLM config presets.
     */
    @GetMapping("/llm-config/presets")
    public ResponseEntity<List<Map<String, Object>>> getLlmConfigPresets() {
        List<Map<String, Object>> presets = new ArrayList<>();

        Map<String, Object> optimal = new LinkedHashMap<>();
        optimal.put("id", "optimal");
        optimal.put("name", "LLM Optimal");
        optimal.put("description", "Best-known LLM inference config (~86 tok/s on RTX 4090). "
                + "Enables Triton compilation, section fusion, CUDA graph capture, consolidated arg table, "
                + "cuBLAS TF32, and batched GEMM. Matches BenchmarkConfig.optimal().");
        presets.add(optimal);

        Map<String, Object> basic = new LinkedHashMap<>();
        basic.put("id", "basic");
        basic.put("name", "LLM Basic");
        basic.put("description", "Conservative config for systems without Triton/CUDA graph support. "
                + "Enables cuBLAS TF32, batched GEMM, and cast elimination only.");
        presets.add(basic);

        Map<String, Object> debug = new LinkedHashMap<>();
        debug.put("id", "debug");
        debug.put("name", "LLM Debug");
        debug.put("description", "Debug-friendly config based on Optimal with verbose logging, "
                + "section dumping, and kernel verification enabled. Reduced parallelism for easier debugging.");
        presets.add(debug);

        return ResponseEntity.ok(presets);
    }

    // ==================== Helpers ====================

    /**
     * Apply a settings map to the environment, handling all known keys.
     */
    private int applySettingsMap(Environment env, Map<String, Object> settings) {
        int applied = 0;

        // Triton compiler settings
        if (settings.containsKey("tritonEnableFpFusion")) { env.setTritonEnableFpFusion((Boolean) settings.get("tritonEnableFpFusion")); applied++; }
        if (settings.containsKey("tritonAlwaysCompile")) { env.setTritonAlwaysCompile((Boolean) settings.get("tritonAlwaysCompile")); applied++; }
        if (settings.containsKey("tritonCacheEnabled")) { env.setTritonCacheEnabled((Boolean) settings.get("tritonCacheEnabled")); applied++; }
        if (settings.containsKey("tritonNumWarps")) { env.setTritonNumWarps((Integer) settings.get("tritonNumWarps")); applied++; }
        if (settings.containsKey("tritonNumStages")) { env.setTritonNumStages((Integer) settings.get("tritonNumStages")); applied++; }
        if (settings.containsKey("tritonNumCTAs")) { env.setTritonNumCTAs((Integer) settings.get("tritonNumCTAs")); applied++; }
        if (settings.containsKey("tritonBuildThreads")) { env.setTritonBuildThreads((Integer) settings.get("tritonBuildThreads")); applied++; }
        if (settings.containsKey("tritonDisableLineInfo")) { env.setTritonDisableLineInfo((Boolean) settings.get("tritonDisableLineInfo")); applied++; }

        // Triton advanced flags
        if (settings.containsKey("tritonCompileAll")) { env.setTritonCompileAll((Boolean) settings.get("tritonCompileAll")); applied++; }
        if (settings.containsKey("tritonIncludeTypes")) { env.setTritonIncludeTypes((String) settings.get("tritonIncludeTypes")); applied++; }
        if (settings.containsKey("tritonExcludeOps")) { env.setTritonExcludeOps((String) settings.get("tritonExcludeOps")); applied++; }
        if (settings.containsKey("tritonSectionFusion")) { env.setTritonSectionFusion((Boolean) settings.get("tritonSectionFusion")); applied++; }
        if (settings.containsKey("tritonFusionScoring")) { env.setTritonFusionScoring((Boolean) settings.get("tritonFusionScoring")); applied++; }
        if (settings.containsKey("tritonGraphCapture")) { env.setTritonGraphCapture((Boolean) settings.get("tritonGraphCapture")); applied++; }
        if (settings.containsKey("tritonAllowFallbackCapture")) { env.setTritonAllowFallbackCapture((Boolean) settings.get("tritonAllowFallbackCapture")); applied++; }
        if (settings.containsKey("tritonForceRecapture")) { env.setTritonForceRecapture((Boolean) settings.get("tritonForceRecapture")); applied++; }
        if (settings.containsKey("tritonConsolidatedArgTable")) { env.setTritonConsolidatedArgTable((Boolean) settings.get("tritonConsolidatedArgTable")); applied++; }
        if (settings.containsKey("tritonArgDirtyTracking")) { env.setTritonArgDirtyTracking((Boolean) settings.get("tritonArgDirtyTracking")); applied++; }
        if (settings.containsKey("tritonCooperativeLaunch")) { env.setTritonCooperativeLaunch((Boolean) settings.get("tritonCooperativeLaunch")); applied++; }
        if (settings.containsKey("tritonSkipKernels")) { env.setTritonSkipKernels((Boolean) settings.get("tritonSkipKernels")); applied++; }
        if (settings.containsKey("tritonVerifyKernels")) { env.setTritonVerifyKernels((Boolean) settings.get("tritonVerifyKernels")); applied++; }
        if (settings.containsKey("tritonVerifyFullSnapshot")) { env.setTritonVerifyFullSnapshot((Boolean) settings.get("tritonVerifyFullSnapshot")); applied++; }
        if (settings.containsKey("tritonVerbose")) { env.setTritonVerbose((Boolean) settings.get("tritonVerbose")); applied++; }
        if (settings.containsKey("tritonDumpSections")) { env.setTritonDumpSections((Boolean) settings.get("tritonDumpSections")); applied++; }

        // CUDA settings
        if (!env.isCPU()) {
            if (settings.containsKey("cudaTensorCoreEnabled")) { env.setCudaTensorCoreEnabled((Boolean) settings.get("cudaTensorCoreEnabled")); applied++; }
            if (settings.containsKey("cudaGraphOptimization")) { env.setCudaGraphOptimization((Boolean) settings.get("cudaGraphOptimization")); applied++; }
            if (settings.containsKey("cudaAsyncExecution")) { env.setCudaAsyncExecution((Boolean) settings.get("cudaAsyncExecution")); applied++; }
        }

        // DSP settings
        if (settings.containsKey("batchedGemm")) { env.setDspBatchedGemm((Boolean) settings.get("batchedGemm")); applied++; }
        if (settings.containsKey("castElimination")) { env.setDspCastElimination((Boolean) settings.get("castElimination")); applied++; }
        if (settings.containsKey("cublasTf32")) { env.setCublasTf32Enabled((Boolean) settings.get("cublasTf32")); applied++; }
        if (settings.containsKey("fp16Compute")) { env.setDspFp16Compute((Boolean) settings.get("fp16Compute")); applied++; }
        if (settings.containsKey("castSinkMatmul")) { env.setDspCastSinkMatmul((Boolean) settings.get("castSinkMatmul")); applied++; }
        if (settings.containsKey("batchZero")) { env.setDspBatchZero((Boolean) settings.get("batchZero")); applied++; }
        if (settings.containsKey("batchZeroKernel")) { env.setDspBatchZeroKernel((Boolean) settings.get("batchZeroKernel")); applied++; }

        // Debug/verbose
        if (settings.containsKey("verbose")) { env.setVerbose((Boolean) settings.get("verbose")); applied++; }
        if (settings.containsKey("debug")) { env.setDebug((Boolean) settings.get("debug")); applied++; }

        return applied;
    }

    private CudaSettingsResponse buildCudaResponse(Environment env) {
        if (env.isCPU()) {
            return CudaSettingsResponse.builder().available(false).build();
        }
        try {
            return CudaSettingsResponse.builder()
                    .available(true)
                    .deviceCount(env.cudaDeviceCount())
                    .currentDevice(env.cudaCurrentDevice())
                    .memoryPinned(env.cudaMemoryPinned())
                    .useManagedMemory(env.cudaUseManagedMemory())
                    .memoryPoolSize(env.cudaMemoryPoolSize())
                    .maxBlocks(env.cudaMaxBlocks())
                    .asyncExecution(env.cudaAsyncExecution())
                    .tensorCoreEnabled(env.cudaTensorCoreEnabled())
                    .graphOptimization(env.cudaGraphOptimization())
                    .build();
        } catch (Exception e) {
            log.warn("Failed to read CUDA settings (backend may not support it): {}", e.getMessage());
            return CudaSettingsResponse.builder().available(false).build();
        }
    }

    private TritonSettingsResponse buildTritonResponse(Environment env) {
        try {
            return TritonSettingsResponse.builder()
                    // Compiler settings
                    .buildThreads(env.tritonBuildThreads())
                    .cacheEnabled(env.tritonCacheEnabled())
                    .verbose(env.tritonVerbose())
                    .alwaysCompile(env.tritonAlwaysCompile())
                    .numWarps(env.tritonNumWarps())
                    .numStages(env.tritonNumStages())
                    .numCTAs(env.tritonNumCTAs())
                    .enableFpFusion(env.tritonEnableFpFusion())
                    .cacheDir(env.tritonCacheDir())
                    .dumpDir(env.tritonDumpDir())
                    .overrideArch(env.tritonOverrideArch())
                    .disableLineInfo(env.tritonDisableLineInfo())
                    .maxNreg(env.tritonMaxNreg())
                    .attentionBlockN(env.tritonAttentionBlockN())
                    // Compilation mode
                    .compileAll(env.tritonCompileAll())
                    .includeTypes(env.tritonIncludeTypes())
                    .excludeOps(env.tritonExcludeOps())
                    // Section fusion
                    .sectionFusion(env.tritonSectionFusion())
                    .fusionScoring(env.tritonFusionScoring())
                    .fusionMinScore(env.tritonFusionMinScore())
                    // Graph capture
                    .graphCapture(env.tritonGraphCapture())
                    .allowFallbackCapture(env.tritonAllowFallbackCapture())
                    .forceRecapture(env.tritonForceRecapture())
                    .captureMinExec(env.tritonCaptureMinExec())
                    // Arg table
                    .consolidatedArgTable(env.tritonConsolidatedArgTable())
                    .argDirtyTracking(env.tritonArgDirtyTracking())
                    // Cooperative launch
                    .cooperativeLaunch(env.tritonCooperativeLaunch())
                    .coopTargetBlocks(env.tritonCoopTargetBlocks())
                    // Debug/verification
                    .skipKernels(env.tritonSkipKernels())
                    .verifyKernels(env.tritonVerifyKernels())
                    .verifyFullSnapshot(env.tritonVerifyFullSnapshot())
                    .dumpSections(env.tritonDumpSections())
                    .dumpArgs(env.tritonDumpArgs())
                    // Subsegment limits
                    .maxSubsegmentOps(env.tritonMaxSubsegmentOps())
                    .maxSubsegmentSections(env.tritonMaxSubsegmentSections())
                    // Segment fusion optimization flags
                    .fuseIdentityShapes(env.tritonFuseIdentityShapes())
                    .fuseCastChains(env.tritonFuseCastChains())
                    .specializePermuteSeq1(env.tritonSpecializePermuteSeq1())
                    .fusedMatmul(env.tritonFusedMatmul())
                    .fuseAttentionNeighborhoods(env.tritonFuseAttentionNeighborhoods())
                    .build();
        } catch (Exception e) {
            log.warn("Failed to read Triton settings: {}", e.getMessage());
            return TritonSettingsResponse.builder().build();
        }
    }

    private DspSettingsResponse buildDspResponse(Environment env) {
        try {
            return DspSettingsResponse.builder()
                    .batchedGemm(env.dspBatchedGemm())
                    .cublasTf32(env.cublasTf32Enabled())
                    .cublasCaptureWorkspace(env.cublasCaptureWorkspace())
                    .fp16Compute(env.dspFp16Compute())
                    .matmulSegmentation(env.dspMatmulSegmentation())
                    .castElimination(env.dspCastElimination())
                    .castSinkMatmul(env.dspCastSinkMatmul())
                    .batchZero(env.dspBatchZero())
                    .batchZeroKernel(env.dspBatchZeroKernel())
                    .symbolicShapes(env.dspSymbolicShapes())
                    .capturePoolEnabled(env.dspCapturePoolEnabled())
                    .capturePoolMaxBytes(env.dspCapturePoolMaxBytes())
                    .build();
        } catch (Exception e) {
            log.warn("Failed to read DSP settings: {}", e.getMessage());
            return DspSettingsResponse.builder().build();
        }
    }

    private long toLong(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        return Long.parseLong(value.toString());
    }

    private int toInt(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        return Integer.parseInt(value.toString());
    }

    private long getMaxPrimaryMemory(Environment env) {
        try { return (long) env.getClass().getMethod("maxPrimaryMemory").invoke(env); }
        catch (Exception e) { return Runtime.getRuntime().maxMemory(); }
    }

    private long getMaxSpecialMemory(Environment env) {
        try { return (long) env.getClass().getMethod("maxSpecialMemory").invoke(env); }
        catch (Exception e) { return 0; }
    }

    private long getMaxDeviceMemory(Environment env) {
        try { return (long) env.getClass().getMethod("maxDeviceMemory").invoke(env); }
        catch (Exception e) { return 0; }
    }
}
