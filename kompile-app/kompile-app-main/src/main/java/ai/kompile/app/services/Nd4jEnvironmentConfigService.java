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

package ai.kompile.app.services;

import ai.kompile.app.config.Nd4jEnvironmentConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.nativeblas.NativeOps;
import org.nd4j.nativeblas.NativeOpsHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Service for managing ND4J environment configuration persistence.
 * Loads persisted settings on startup and applies them to the ND4J environment.
 * Persists configuration changes to disk for retention across restarts.
 */
@Service
public class Nd4jEnvironmentConfigService {

    private static final Logger log = LoggerFactory.getLogger(Nd4jEnvironmentConfigService.class);
    private static final String CONFIG_FILENAME = "nd4j-environment-config.json";

    private final ObjectMapper objectMapper;
    private final Path configFilePath;

    private volatile Nd4jEnvironmentConfig currentConfig;
    // Track OMP threads ourselves since omp_get_num_threads() returns 1 outside parallel regions
    private volatile int currentOmpThreads = 4;

    public Nd4jEnvironmentConfigService(
            @Value("${kompile.data.dir:#{null}}") String dataDir) {
        this.objectMapper = new ObjectMapper();

        // Use provided dataDir, or fall back to ~/.kompile if not set
        String effectiveDataDir = dataDir;
        if (effectiveDataDir == null || effectiveDataDir.isBlank()) {
            effectiveDataDir = System.getProperty("user.home") + "/.kompile";
        }
        this.configFilePath = Paths.get(effectiveDataDir, "config", CONFIG_FILENAME);
        this.currentConfig = Nd4jEnvironmentConfig.defaults();
        this.currentOmpThreads = currentConfig.ompNumThreads() != null ? currentConfig.ompNumThreads() : 4;
        log.info("Nd4jEnvironmentConfigService initialized, config path: {}, initial OMP threads: {}",
                configFilePath, currentOmpThreads);
    }

    /**
     * Synchronize internal state with persisted configuration on startup.
     *
     * NOTE: The persisted ND4J config is already loaded and applied by
     * MainApplication.main()
     * BEFORE the Spring context starts. This ensures that all beans (like embedding
     * models)
     * use the correct settings from the start.
     *
     * This @PostConstruct method simply synchronizes this service's internal state
     * with
     * the persisted config file, without re-applying settings (they're already
     * applied).
     * If no persisted config exists, we persist the defaults.
     */
    @PostConstruct
    public void syncWithPersistedConfig() {
        try {
            syncWithPersistedConfigInternal();
        } catch (Throwable e) {
            // Catch NoClassDefFoundError/ExceptionInInitializerError when ND4J backend is unavailable
            // (e.g., running as GraalVM native image without native libs)
            log.warn("ND4J config sync skipped - backend not available: {}", e.getMessage());
            currentConfig = Nd4jEnvironmentConfig.defaults();
        }
    }

    private void syncWithPersistedConfigInternal() {
        // Skip loading if configFilePath is null (dataDir not configured)
        if (configFilePath == null) {
            log.warn("Cannot sync ND4J config - kompile.data.dir not configured. Using defaults.");
            currentConfig = getActualConfiguration();
            return;
        }

        log.info("Synchronizing Nd4jEnvironmentConfigService state with persisted config at: {}", configFilePath);
        log.info("NOTE: ND4J environment was already configured by MainApplication.main() before Spring started");

        if (!Files.exists(configFilePath)) {
            log.info("No persisted ND4J config found at {} - syncing with defaults and persisting", configFilePath);
            // Read actual state from ND4J (already configured by main())
            currentConfig = getActualConfiguration();
            // Persist it so the file exists for future use
            persistConfig();
            return;
        }

        try {
            String json = Files.readString(configFilePath);
            log.info("Read persisted ND4J config file: {} bytes", json.length());

            Nd4jEnvironmentConfig loaded = objectMapper.readValue(json, Nd4jEnvironmentConfig.class);

            // Merge with defaults to ensure all fields have values
            currentConfig = Nd4jEnvironmentConfig.defaults().merge(loaded);

            // CRITICAL: Apply ALL settings from persisted config to ND4J environment.
            log.info("Applying ALL persisted ND4J settings to environment...");
            applyConfiguration(currentConfig);

            log.info("Successfully synchronized with persisted ND4J environment config");
        } catch (IOException e) {
            log.error("Failed to load persisted ND4J config from {}: {}", configFilePath, e.getMessage(), e);
            log.info("Syncing with actual ND4J state (already configured by main())");
            currentConfig = getActualConfiguration();
        } catch (Exception e) {
            log.error("Unexpected error loading ND4J config: {}", e.getMessage(), e);
            currentConfig = getActualConfiguration();
        }
    }

    /**
     * Check if two configs have the same core settings.
     */
    private boolean configsMatch(Nd4jEnvironmentConfig a, Nd4jEnvironmentConfig b) {
        if (a == null || b == null)
            return false;
        // Check key settings including OMP threads
        return java.util.Objects.equals(a.maxThreads(), b.maxThreads())
                && java.util.Objects.equals(a.maxMasterThreads(), b.maxMasterThreads())
                && java.util.Objects.equals(a.lifecycleTracking(), b.lifecycleTracking())
                && java.util.Objects.equals(a.ompNumThreads(), b.ompNumThreads());
    }

    /**
     * Gets the current configuration.
     */
    public Nd4jEnvironmentConfig getConfiguration() {
        return currentConfig;
    }

    /**
     * Gets the current configuration as read from the ND4J environment.
     * This reflects the actual runtime state.
     */
    public Nd4jEnvironmentConfig getActualConfiguration() {
        try {
            return Nd4jEnvironmentConfig.captureFromEnvironment(currentConfig, currentOmpThreads);
        } catch (Exception e) {
            log.warn("Error reading actual ND4J configuration: {}", e.getMessage());
            return currentConfig;
        }
    }

    /**
     * Updates the configuration with the provided values.
     * Only non-null values in the update will be applied.
     *
     * @param update The partial configuration to apply
     * @return The new complete configuration
     */
    public Nd4jEnvironmentConfig updateConfiguration(Nd4jEnvironmentConfig update) {
        if (update == null) {
            return currentConfig;
        }

        // Merge with current config
        currentConfig = currentConfig.merge(update);

        // Apply to ND4J environment
        applyConfiguration(currentConfig);

        // Persist to disk
        persistConfig();

        log.info("ND4J environment configuration updated and persisted");
        return currentConfig;
    }

    /**
     * Resets configuration to defaults.
     */
    public Nd4jEnvironmentConfig resetConfiguration() {
        currentConfig = Nd4jEnvironmentConfig.defaults();
        applyConfiguration(currentConfig);
        persistConfig();
        log.info("ND4J environment configuration reset to defaults");
        return currentConfig;
    }

    /**
     * Applies a preset configuration.
     *
     * @param presetName The preset name: "minimal", "balanced", "detailed", or
     *                   "performance"
     * @return The applied configuration
     */
    public Nd4jEnvironmentConfig applyPreset(String presetName) {
        Nd4jEnvironmentConfig preset = switch (presetName.toLowerCase()) {
            case "minimal" -> Nd4jEnvironmentConfig.builder()
                    .maxThreads(2)
                    .maxMasterThreads(2)
                    .debug(false)
                    .verbose(false)
                    .profiling(false)
                    .lifecycleTracking(true)
                    .trackViews(false)
                    .trackDeletions(false)
                    .snapshotFiles(true)
                    .trackOperations(false)
                    .stackDepth(8)
                    .reportInterval(300)
                    .maxDeletionHistory(500)
                    .ndArrayTracking(false)
                    .dataBufferTracking(false)
                    .tadCacheTracking(false)
                    .shapeCacheTracking(false)
                    .opContextTracking(false)
                    .blasSerializationEnabled(true) // Safe default
                    .openBlasThreads(1) // Safe default
                    .ompNumThreads(2) // Match maxThreads
                    .build();

            case "balanced" -> Nd4jEnvironmentConfig.builder()
                    .maxThreads(4)
                    .maxMasterThreads(4)
                    .debug(false)
                    .verbose(false)
                    .profiling(false)
                    .lifecycleTracking(true)
                    .trackViews(false)
                    .trackDeletions(false)
                    .snapshotFiles(true)
                    .trackOperations(true)
                    .stackDepth(16)
                    .reportInterval(120)
                    .maxDeletionHistory(1000)
                    .ndArrayTracking(true)
                    .dataBufferTracking(true)
                    .tadCacheTracking(true)
                    .shapeCacheTracking(true)
                    .opContextTracking(true)
                    .blasSerializationEnabled(true) // Safe default
                    .openBlasThreads(2) // Balanced threading
                    .ompNumThreads(4) // Match maxThreads
                    .build();

            case "detailed" -> Nd4jEnvironmentConfig.builder()
                    .maxThreads(4)
                    .maxMasterThreads(4)
                    .debug(true)
                    .verbose(true)
                    .profiling(true)
                    .lifecycleTracking(true)
                    .trackViews(true)
                    .trackDeletions(true)
                    .snapshotFiles(true)
                    .trackOperations(true)
                    .stackDepth(64)
                    .reportInterval(30)
                    .maxDeletionHistory(5000)
                    .ndArrayTracking(true)
                    .dataBufferTracking(true)
                    .tadCacheTracking(true)
                    .shapeCacheTracking(true)
                    .opContextTracking(true)
                    .blasSerializationEnabled(true) // Safe default for debugging
                    .openBlasThreads(1) // Single thread for easier debugging
                    .ompNumThreads(1) // Single thread for easier debugging
                    .build();

            case "performance" -> Nd4jEnvironmentConfig.builder()
                    .maxThreads(Runtime.getRuntime().availableProcessors())
                    .maxMasterThreads(Runtime.getRuntime().availableProcessors())
                    .debug(false)
                    .verbose(false)
                    .profiling(false)
                    .lifecycleTracking(false)
                    .trackViews(false)
                    .trackDeletions(false)
                    .snapshotFiles(false)
                    .trackOperations(false)
                    .stackDepth(0)
                    .reportInterval(0)
                    .maxDeletionHistory(0)
                    .ndArrayTracking(false)
                    .dataBufferTracking(false)
                    .tadCacheTracking(false)
                    .shapeCacheTracking(false)
                    .opContextTracking(false)
                    .blasSerializationEnabled(true) // Keep serialization for safety
                    .openBlasThreads(Math.max(1, Runtime.getRuntime().availableProcessors() / 2)) // Use half of
                                                                                                  // available CPUs for
                                                                                                  // OpenBLAS
                    .ompNumThreads(Runtime.getRuntime().availableProcessors()) // Use all CPUs for OMP
                    .build();

            case "triton-optimal" -> Nd4jEnvironmentConfig.builder()
                    .maxThreads(Runtime.getRuntime().availableProcessors())
                    .maxMasterThreads(Runtime.getRuntime().availableProcessors())
                    .debug(false)
                    .verbose(false)
                    .profiling(false)
                    .lifecycleTracking(false)
                    .tritonBuildThreads(Runtime.getRuntime().availableProcessors())
                    .tritonCacheEnabled(true)
                    .tritonVerbose(false)
                    .tritonAlwaysCompile(false)
                    .tritonNumWarps(8)
                    .tritonNumStages(3)
                    .tritonNumCTAs(1)
                    .tritonEnableFpFusion(true)
                    .build();

            default -> throw new IllegalArgumentException(
                    "Unknown preset: " + presetName + ". Valid presets: minimal, balanced, detailed, performance, triton-optimal");
        };

        // Merge preset with defaults to fill in any missing values
        currentConfig = Nd4jEnvironmentConfig.defaults().merge(preset);
        applyConfiguration(currentConfig);
        persistConfig();

        log.info("Applied ND4J environment preset: {}", presetName);
        return currentConfig;
    }

    /**
     * Applies the configuration to the ND4J environment.
     * This includes all available environment settings.
     */
    private void applyConfiguration(Nd4jEnvironmentConfig config) {
        log.info("Applying ND4J environment configuration...");

        try {
            // === CORE SETTINGS ===
            if (config.enableBlas() != null) {
                Nd4j.getEnvironment().setEnableBlas(config.enableBlas());
            }
            if (config.helpersAllowed() != null) {
                Nd4j.getEnvironment().allowHelpers(config.helpersAllowed());
            }

            // === THREAD CONFIGURATION ===
            if (config.maxThreads() != null) {
                Nd4j.getEnvironment().setMaxThreads(config.maxThreads());
            }
            if (config.maxMasterThreads() != null) {
                Nd4j.getEnvironment().setMaxMasterThreads(config.maxMasterThreads());
            }

            // === DEBUG/VERBOSE MODES ===
            if (config.debug() != null) {
                Nd4j.getEnvironment().setDebug(config.debug());
            }
            if (config.verbose() != null) {
                Nd4j.getEnvironment().setVerbose(config.verbose());
            }
            if (config.profiling() != null) {
                Nd4j.getEnvironment().setProfiling(config.profiling());
            }
            if (config.leaksDetector() != null) {
                Nd4j.getEnvironment().setLeaksDetector(config.leaksDetector());
            }

            // === PERFORMANCE THRESHOLDS ===
            if (config.tadThreshold() != null) {
                Nd4j.getEnvironment().setTadThreshold(config.tadThreshold());
            }
            if (config.elementwiseThreshold() != null) {
                Nd4j.getEnvironment().setElementwiseThreshold(config.elementwiseThreshold());
            }

            // === MEMORY LIMITS ===
            if (config.maxPrimaryMemory() != null && config.maxPrimaryMemory() > 0) {
                Nd4j.getEnvironment().setMaxPrimaryMemory(config.maxPrimaryMemory());
            }
            if (config.maxSpecialMemory() != null && config.maxSpecialMemory() > 0) {
                Nd4j.getEnvironment().setMaxSpecialMemory(config.maxSpecialMemory());
            }
            if (config.maxDeviceMemory() != null && config.maxDeviceMemory() > 0) {
                Nd4j.getEnvironment().setMaxDeviceMemory(config.maxDeviceMemory());
            }

            // === LIFECYCLE TRACKING MASTER SWITCH ===
            if (config.lifecycleTracking() != null) {
                Nd4j.getEnvironment().setLifecycleTracking(config.lifecycleTracking());
            }

            // === LIFECYCLE TRACKING SUB-OPTIONS ===
            if (config.trackViews() != null) {
                Nd4j.getEnvironment().setTrackViews(config.trackViews());
            }
            if (config.trackDeletions() != null) {
                Nd4j.getEnvironment().setTrackDeletions(config.trackDeletions());
            }
            if (config.snapshotFiles() != null) {
                Nd4j.getEnvironment().setSnapshotFiles(config.snapshotFiles());
            }
            if (config.trackOperations() != null) {
                Nd4j.getEnvironment().setTrackOperations(config.trackOperations());
            }

            // === LIFECYCLE TRACKING PARAMETERS ===
            if (config.stackDepth() != null) {
                Nd4j.getEnvironment().setStackDepth(config.stackDepth());
            }
            if (config.reportInterval() != null) {
                Nd4j.getEnvironment().setReportInterval(config.reportInterval());
            }
            if (config.maxDeletionHistory() != null) {
                Nd4j.getEnvironment().setMaxDeletionHistory(config.maxDeletionHistory());
            }

            // === INDIVIDUAL TRACKER TOGGLES ===
            if (config.ndArrayTracking() != null) {
                Nd4j.getEnvironment().setNDArrayTracking(config.ndArrayTracking());
            }
            if (config.dataBufferTracking() != null) {
                Nd4j.getEnvironment().setDataBufferTracking(config.dataBufferTracking());
            }
            if (config.tadCacheTracking() != null) {
                Nd4j.getEnvironment().setTADCacheTracking(config.tadCacheTracking());
            }
            if (config.shapeCacheTracking() != null) {
                Nd4j.getEnvironment().setShapeCacheTracking(config.shapeCacheTracking());
            }
            if (config.opContextTracking() != null) {
                Nd4j.getEnvironment().setOpContextTracking(config.opContextTracking());
            }

            // === ADVANCED DEBUGGING - FUNCTION TRACING ===
            if (config.funcTracePrintAllocate() != null) {
                Nd4j.getEnvironment().setFuncTraceForAllocate(config.funcTracePrintAllocate());
            }
            if (config.funcTracePrintDeallocate() != null) {
                Nd4j.getEnvironment().setFuncTraceForDeallocate(config.funcTracePrintDeallocate());
            }
            if (config.funcTracePrintJavaOnly() != null) {
                Nd4j.getEnvironment().setFuncTracePrintJavaOnly(config.funcTracePrintJavaOnly());
            }

            // === ADVANCED DEBUGGING - OTHER ===
            if (config.logNativeNDArrayCreation() != null) {
                Nd4j.getEnvironment().setLogNativeNDArrayCreation(config.logNativeNDArrayCreation());
            }
            if (config.logNDArrayEvents() != null) {
                Nd4j.getEnvironment().setLogNDArrayEvents(config.logNDArrayEvents());
            }
            if (config.truncateNDArrayLogStrings() != null) {
                Nd4j.getEnvironment().setTruncateLogStrings(config.truncateNDArrayLogStrings());
            }
            // Note: numWorkspaceEventsToKeep is read-only in Environment interface (no setter)
            if (config.checkInputChange() != null) {
                Nd4j.getEnvironment().setCheckInputChange(config.checkInputChange());
            }
            if (config.checkOutputChange() != null) {
                Nd4j.getEnvironment().setCheckOutputChange(config.checkOutputChange());
            }
            if (config.trackWorkspaceOpenClose() != null) {
                Nd4j.getEnvironment().setTrackWorkspaceOpenClose(config.trackWorkspaceOpenClose());
            }
            if (config.deleteShapeInfo() != null) {
                Nd4j.getEnvironment().setDeleteShapeInfo(config.deleteShapeInfo());
            }
            if (config.deletePrimary() != null) {
                Nd4j.getEnvironment().setDeletePrimary(config.deletePrimary());
            }
            if (config.deleteSpecial() != null) {
                Nd4j.getEnvironment().setDeleteSpecial(config.deleteSpecial());
            }
            if (config.variableTracingEnabled() != null) {
                Nd4j.getEnvironment().setVariableTracingEnabled(config.variableTracingEnabled());
            }

            // === JAVACPP SETTINGS (system properties) ===
            if (config.javacppLoggerDebug() != null) {
                System.setProperty("org.bytedeco.javacpp.logger.debug", config.javacppLoggerDebug().toString());
            }
            if (config.javacppPathsFirst() != null) {
                System.setProperty("org.bytedeco.javacpp.pathsFirst", config.javacppPathsFirst().toString());
            }

            // === BLAS CONFIGURATION ===
            // Note: ND4J Environment doesn't expose setters for BLAS serialization or
            // OpenBLAS threads
            // These settings are persisted in the config file but not applied to ND4J
            // runtime
            // The values are still tracked for diagnostic/documentation purposes
            if (config.blasSerializationEnabled() != null) {
                log.debug("blasSerializationEnabled={} (not applied - no ND4J setter available)",
                        config.blasSerializationEnabled());
            }
            if (config.openBlasThreads() != null) {
                log.debug("openBlasThreads={} (not applied - no ND4J setter available)",
                        config.openBlasThreads());
            }

            // === OMP CONFIGURATION ===
            if (config.ompNumThreads() != null && config.ompNumThreads() > 0) {
                setOmpNumThreads(config.ompNumThreads());
                log.info("Set OpenMP threads to {}", config.ompNumThreads());
            }

            // === SAMEDIFF FRAMEWORK / DSP SETTINGS ===
            // These are applied via system properties and Nd4j.framework API where available
            if (config.optimizerEnabled() != null) {
                System.setProperty(org.nd4j.common.config.ND4JSystemProperties.OPTIMIZER_ENABLED,
                        config.optimizerEnabled().toString());
            }
            if (config.optimizerFp16() != null) {
                System.setProperty(org.nd4j.common.config.ND4JSystemProperties.OPTIMIZER_FP16,
                        config.optimizerFp16().toString());
            }
            if (config.dspNoFreeze() != null) {
                System.setProperty(org.nd4j.common.config.ND4JSystemProperties.DSP_NO_FREEZE,
                        config.dspNoFreeze().toString());
            }
            if (config.dspNoNativeDecode() != null) {
                System.setProperty(org.nd4j.common.config.ND4JSystemProperties.DSP_NO_NATIVE_DECODE_INPUTS,
                        config.dspNoNativeDecode().toString());
            }
            if (config.dspNoAttnOverride() != null) {
                System.setProperty(org.nd4j.common.config.ND4JSystemProperties.DSP_NO_ATTN_OVERRIDE,
                        config.dspNoAttnOverride().toString());
            }
            if (config.dspNoDirect() != null) {
                System.setProperty(org.nd4j.common.config.ND4JSystemProperties.DSP_NO_DIRECT,
                        config.dspNoDirect().toString());
            }
            if (config.tritonSkipKernels() != null) {
                System.setProperty(org.nd4j.common.config.ND4JSystemProperties.TRITON_SKIP_KERNELS,
                        config.tritonSkipKernels().toString());
            }
            if (config.tritonTf32() != null) {
                System.setProperty(org.nd4j.common.config.ND4JSystemProperties.TRITON_TF32,
                        config.tritonTf32().toString());
            }
            if (config.cublasDisableWorkspace() != null) {
                // cublas.captureWorkspace: "0" = disabled, "1" = enabled
                System.setProperty(org.nd4j.common.config.ND4JSystemProperties.CUBLAS_CAPTURE_WORKSPACE,
                        config.cublasDisableWorkspace() ? "0" : "1");
            }
            if (config.dspDiagnostics() != null) {
                System.setProperty(org.nd4j.common.config.ND4JSystemProperties.DSP_DIAGNOSTICS,
                        config.dspDiagnostics());
                // Also apply via framework API if available
                try {
                    Nd4j.framework.execution().environment().get().setDspDiagnostics(config.dspDiagnostics());
                } catch (Exception e) {
                    log.debug("Could not set DSP diagnostics via framework API: {}", e.getMessage());
                }
            }
            if (config.opTiming() != null) {
                System.setProperty(org.nd4j.common.config.ND4JSystemProperties.OP_TIMING,
                        config.opTiming().toString());
                // Also apply via framework profiling API if available
                try {
                    if (config.opTiming()) {
                        Nd4j.framework.profiling().enableOpTiming(false);
                    } else {
                        Nd4j.framework.profiling().disableOpTiming();
                    }
                } catch (Exception e) {
                    log.debug("Could not set op timing via framework API: {}", e.getMessage());
                }
            }
            log.info("Applied SameDiff framework/DSP settings: optimizer={}, fp16={}, diagnostics={}",
                    config.optimizerEnabled(), config.optimizerFp16(), config.dspDiagnostics());

            // === CUDA CONFIGURATION ===
            // Only apply CUDA settings if running on CUDA backend
            if (!Nd4j.getEnvironment().isCPU()) {
                var env = Nd4j.getEnvironment();
                if (config.cudaCurrentDevice() != null) {
                    env.setCudaCurrentDevice(config.cudaCurrentDevice());
                }
                if (config.cudaMemoryPinned() != null) {
                    env.setCudaMemoryPinned(config.cudaMemoryPinned());
                }
                if (config.cudaUseManagedMemory() != null) {
                    env.setCudaUseManagedMemory(config.cudaUseManagedMemory());
                }
                if (config.cudaMemoryPoolSize() != null) {
                    env.setCudaMemoryPoolSize(config.cudaMemoryPoolSize());
                }
                if (config.cudaForceP2P() != null) {
                    env.setCudaForceP2P(config.cudaForceP2P());
                }
                if (config.cudaAllocatorEnabled() != null) {
                    env.setCudaAllocatorEnabled(config.cudaAllocatorEnabled());
                }
                if (config.cudaMaxBlocks() != null) {
                    env.setCudaMaxBlocks(config.cudaMaxBlocks());
                }
                if (config.cudaMaxThreadsPerBlock() != null) {
                    env.setCudaMaxThreadsPerBlock(config.cudaMaxThreadsPerBlock());
                }
                if (config.cudaAsyncExecution() != null) {
                    env.setCudaAsyncExecution(config.cudaAsyncExecution());
                }
                if (config.cudaStreamLimit() != null) {
                    env.setCudaStreamLimit(config.cudaStreamLimit());
                }
                if (config.cudaUseDeviceHost() != null) {
                    env.setCudaUseDeviceHost(config.cudaUseDeviceHost());
                }
                if (config.cudaEventLimit() != null) {
                    env.setCudaEventLimit(config.cudaEventLimit());
                }
                if (config.cudaCachingAllocatorLimit() != null) {
                    env.setCudaCachingAllocatorLimit(config.cudaCachingAllocatorLimit());
                }
                if (config.cudaUseUnifiedMemory() != null) {
                    env.setCudaUseUnifiedMemory(config.cudaUseUnifiedMemory());
                }
                if (config.cudaPrefetchSize() != null) {
                    env.setCudaPrefetchSize(config.cudaPrefetchSize());
                }
                if (config.cudaGraphOptimization() != null) {
                    env.setCudaGraphOptimization(config.cudaGraphOptimization());
                }
                if (config.cudaTensorCoreEnabled() != null) {
                    env.setCudaTensorCoreEnabled(config.cudaTensorCoreEnabled());
                }
                if (config.cudaBlockingSync() != null) {
                    env.setCudaBlockingSync(config.cudaBlockingSync());
                }
                if (config.cudaDeviceSchedule() != null) {
                    env.setCudaDeviceSchedule(config.cudaDeviceSchedule());
                }
                if (config.cudaStackSize() != null) {
                    env.setCudaStackSize(config.cudaStackSize());
                }
                if (config.cudaMallocHeapSize() != null) {
                    env.setCudaMallocHeapSize(config.cudaMallocHeapSize());
                }
                if (config.cudaPrintfFifoSize() != null) {
                    env.setCudaPrintfFifoSize(config.cudaPrintfFifoSize());
                }
                if (config.cudaDevRuntimeSyncDepth() != null) {
                    env.setCudaDevRuntimeSyncDepth(config.cudaDevRuntimeSyncDepth());
                }
                if (config.cudaDevRuntimePendingLaunchCount() != null) {
                    env.setCudaDevRuntimePendingLaunchCount(config.cudaDevRuntimePendingLaunchCount());
                }
                if (config.cudaMaxL2FetchGranularity() != null) {
                    env.setCudaMaxL2FetchGranularity(config.cudaMaxL2FetchGranularity());
                }
                if (config.cudaPersistingL2CacheSize() != null) {
                    env.setCudaPersistingL2CacheSize(config.cudaPersistingL2CacheSize());
                }
                log.info("Applied CUDA-specific configuration settings");

                // === TRITON COMPILER CONFIGURATION (GPU only) ===
                if (config.tritonBuildThreads() != null) {
                    env.setTritonBuildThreads(config.tritonBuildThreads());
                }
                if (config.tritonCacheEnabled() != null) {
                    env.setTritonCacheEnabled(config.tritonCacheEnabled());
                }
                if (config.tritonVerbose() != null) {
                    env.setTritonVerbose(config.tritonVerbose());
                }
                if (config.tritonAlwaysCompile() != null) {
                    env.setTritonAlwaysCompile(config.tritonAlwaysCompile());
                }
                if (config.tritonNumWarps() != null) {
                    env.setTritonNumWarps(config.tritonNumWarps());
                }
                if (config.tritonNumStages() != null) {
                    env.setTritonNumStages(config.tritonNumStages());
                }
                if (config.tritonNumCTAs() != null) {
                    env.setTritonNumCTAs(config.tritonNumCTAs());
                }
                if (config.tritonEnableFpFusion() != null) {
                    env.setTritonEnableFpFusion(config.tritonEnableFpFusion());
                }
                if (config.tritonCacheDir() != null) {
                    env.setTritonCacheDir(config.tritonCacheDir());
                }
                if (config.tritonDumpDir() != null) {
                    env.setTritonDumpDir(config.tritonDumpDir());
                }
                if (config.tritonOverrideArch() != null) {
                    env.setTritonOverrideArch(config.tritonOverrideArch());
                }
                log.info("Applied Triton compiler configuration settings");
            }

            log.info("ND4J environment configuration applied successfully");
            log.info("  enableBlas={}, helpersAllowed={}, maxThreads={}, maxMasterThreads={}",
                    Nd4j.getEnvironment().isEnableBlas(),
                    Nd4j.getEnvironment().helpersAllowed(),
                    Nd4j.getEnvironment().maxThreads(),
                    Nd4j.getEnvironment().maxMasterThreads());
            log.info("  debug={}, verbose={}, profiling={}, lifecycleTracking={}",
                    Nd4j.getEnvironment().isDebug(),
                    Nd4j.getEnvironment().isVerbose(),
                    Nd4j.getEnvironment().isProfiling(),
                    Nd4j.getEnvironment().isLifecycleTracking());
            log.info("  blasSerializationEnabled={}, openBlasThreads={} (config values, not from ND4J)",
                    config.blasSerializationEnabled(),
                    config.openBlasThreads());
            log.info("  ompNumThreads={} (from NativeOps)", getOmpNumThreads());

        } catch (Throwable e) {
            log.warn("Error applying ND4J environment configuration (backend may not be available): {}", e.getMessage());
        }
    }

    /**
     * Gets the current number of OpenMP threads.
     * Note: We track this ourselves because omp_get_num_threads() returns 1
     * when called outside a parallel region (standard OpenMP behavior).
     * @return The current OMP thread count
     */
    public int getOmpNumThreads() {
        return currentOmpThreads;
    }

    /**
     * Sets the number of OpenMP threads via NativeOps.
     * @param threads The number of threads to set
     */
    public void setOmpNumThreads(int threads) {
        try {
            if (threads < 1) {
                log.warn("Invalid OMP thread count: {}. Must be >= 1", threads);
                return;
            }
            NativeOps nativeOps = NativeOpsHolder.getInstance().getDeviceNativeOps();
            nativeOps.setOmpNumThreads(threads);
            currentOmpThreads = threads;  // Track the value ourselves
            log.info("OpenMP threads set to {}", threads);
        } catch (Exception e) {
            log.error("Failed to set OMP num threads to {}: {}", threads, e.getMessage(), e);
        }
    }

    /**
     * Persists the current configuration to disk.
     * Uses atomic write (write to temp file, then rename) for safety.
     * Creates a backup of existing config before overwriting.
     */
    private void persistConfig() {
        if (configFilePath == null) {
            log.debug("Config file path is null, skipping persistence");
            return;
        }
        try {
            // Ensure directory exists
            Path parentDir = configFilePath.getParent();
            if (!Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
                log.info("Created config directory: {}", parentDir);
            }

            // Create backup of existing config if it exists
            if (Files.exists(configFilePath)) {
                Path backupPath = configFilePath.resolveSibling(
                        configFilePath.getFileName().toString() + ".backup");
                try {
                    Files.copy(configFilePath, backupPath,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    log.debug("Created backup at: {}", backupPath);
                } catch (IOException e) {
                    log.warn("Failed to create backup of config: {}", e.getMessage());
                    // Continue anyway - backup is best-effort
                }
            }

            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(currentConfig);

            // Atomic write: write to temp file with unique name, then rename
            // Use UUID to prevent race conditions when multiple threads call persistConfig() concurrently
            Path tempFile = configFilePath.resolveSibling(
                    configFilePath.getFileName().toString() + ".tmp." + java.util.UUID.randomUUID());
            try {
                Files.writeString(tempFile, json);
                Files.move(tempFile, configFilePath,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                // Fall back to non-atomic move if filesystem doesn't support atomic move
                log.debug("Atomic move not supported, using standard move");
                Files.move(tempFile, configFilePath,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } finally {
                // Clean up temp file if it still exists (in case of error)
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // Ignore cleanup errors
                }
            }

            log.info("Persisted ND4J environment config to {}", configFilePath);
        } catch (IOException e) {
            log.error("Failed to persist ND4J environment config to {}: {}", configFilePath, e.getMessage(), e);
        }
    }

    /**
     * Gets a summary of the current configuration as a map for REST responses.
     */
    public Map<String, Object> getConfigurationSummary() {
        Map<String, Object> summary = new HashMap<>();

        Nd4jEnvironmentConfig actual = getActualConfiguration();

        // Thread settings
        Map<String, Object> threads = new HashMap<>();
        threads.put("maxThreads", actual.maxThreads());
        threads.put("maxMasterThreads", actual.maxMasterThreads());
        threads.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        summary.put("threads", threads);

        // Debug settings
        Map<String, Object> debug = new HashMap<>();
        debug.put("debug", actual.debug());
        debug.put("verbose", actual.verbose());
        debug.put("profiling", actual.profiling());
        summary.put("debug", debug);

        // Lifecycle tracking
        Map<String, Object> lifecycle = new HashMap<>();
        lifecycle.put("enabled", actual.lifecycleTracking());
        lifecycle.put("trackViews", actual.trackViews());
        lifecycle.put("trackDeletions", actual.trackDeletions());
        lifecycle.put("snapshotFiles", actual.snapshotFiles());
        lifecycle.put("trackOperations", actual.trackOperations());
        lifecycle.put("stackDepth", actual.stackDepth());
        lifecycle.put("reportInterval", actual.reportInterval());
        lifecycle.put("maxDeletionHistory", actual.maxDeletionHistory());
        summary.put("lifecycle", lifecycle);

        // Individual trackers
        Map<String, Object> trackers = new HashMap<>();
        trackers.put("ndArrayTracking", actual.ndArrayTracking());
        trackers.put("dataBufferTracking", actual.dataBufferTracking());
        trackers.put("tadCacheTracking", actual.tadCacheTracking());
        trackers.put("shapeCacheTracking", actual.shapeCacheTracking());
        trackers.put("opContextTracking", actual.opContextTracking());
        summary.put("trackers", trackers);

        // JavaCPP settings
        Map<String, Object> javacpp = new HashMap<>();
        javacpp.put("loggerDebug", actual.javacppLoggerDebug());
        javacpp.put("pathsFirst", actual.javacppPathsFirst());
        summary.put("javacpp", javacpp);

        // BLAS configuration
        Map<String, Object> blas = new HashMap<>();
        blas.put("serializationEnabled", actual.blasSerializationEnabled());
        blas.put("openBlasThreads", actual.openBlasThreads());
        blas.put("ompNumThreads", getOmpNumThreads());
        summary.put("blas", blas);

        // Advanced debugging settings
        Map<String, Object> advancedDebug = new HashMap<>();
        advancedDebug.put("truncateNDArrayLogStrings", actual.truncateNDArrayLogStrings());
        advancedDebug.put("numWorkspaceEventsToKeep", actual.numWorkspaceEventsToKeep());
        summary.put("advancedDebug", advancedDebug);

        // CUDA configuration (only if CUDA backend)
        boolean isCuda = !Nd4j.getEnvironment().isCPU();
        summary.put("isCudaBackend", isCuda);
        if (isCuda) {
            Map<String, Object> cuda = new HashMap<>();
            cuda.put("deviceCount", Nd4j.getEnvironment().cudaDeviceCount());
            cuda.put("currentDevice", actual.cudaCurrentDevice());
            cuda.put("memoryPinned", actual.cudaMemoryPinned());
            cuda.put("useManagedMemory", actual.cudaUseManagedMemory());
            cuda.put("memoryPoolSize", actual.cudaMemoryPoolSize());
            cuda.put("forceP2P", actual.cudaForceP2P());
            cuda.put("allocatorEnabled", actual.cudaAllocatorEnabled());
            cuda.put("maxBlocks", actual.cudaMaxBlocks());
            cuda.put("maxThreadsPerBlock", actual.cudaMaxThreadsPerBlock());
            cuda.put("asyncExecution", actual.cudaAsyncExecution());
            cuda.put("streamLimit", actual.cudaStreamLimit());
            cuda.put("useDeviceHost", actual.cudaUseDeviceHost());
            cuda.put("eventLimit", actual.cudaEventLimit());
            cuda.put("cachingAllocatorLimit", actual.cudaCachingAllocatorLimit());
            cuda.put("useUnifiedMemory", actual.cudaUseUnifiedMemory());
            cuda.put("prefetchSize", actual.cudaPrefetchSize());
            cuda.put("graphOptimization", actual.cudaGraphOptimization());
            cuda.put("tensorCoreEnabled", actual.cudaTensorCoreEnabled());
            cuda.put("blockingSync", actual.cudaBlockingSync());
            cuda.put("deviceSchedule", actual.cudaDeviceSchedule());
            cuda.put("stackSize", actual.cudaStackSize());
            cuda.put("mallocHeapSize", actual.cudaMallocHeapSize());
            cuda.put("printfFifoSize", actual.cudaPrintfFifoSize());
            cuda.put("devRuntimeSyncDepth", actual.cudaDevRuntimeSyncDepth());
            cuda.put("devRuntimePendingLaunchCount", actual.cudaDevRuntimePendingLaunchCount());
            cuda.put("maxL2FetchGranularity", actual.cudaMaxL2FetchGranularity());
            cuda.put("persistingL2CacheSize", actual.cudaPersistingL2CacheSize());
            summary.put("cuda", cuda);
        }

        // Triton configuration (only if CUDA backend)
        if (isCuda) {
            Map<String, Object> triton = new HashMap<>();
            triton.put("buildThreads", actual.tritonBuildThreads());
            triton.put("cacheEnabled", actual.tritonCacheEnabled());
            triton.put("verbose", actual.tritonVerbose());
            triton.put("alwaysCompile", actual.tritonAlwaysCompile());
            triton.put("numWarps", actual.tritonNumWarps());
            triton.put("numStages", actual.tritonNumStages());
            triton.put("numCTAs", actual.tritonNumCTAs());
            triton.put("enableFpFusion", actual.tritonEnableFpFusion());
            triton.put("cacheDir", actual.tritonCacheDir());
            triton.put("dumpDir", actual.tritonDumpDir());
            triton.put("overrideArch", actual.tritonOverrideArch());
            summary.put("triton", triton);
        }

        // Config file path
        summary.put("configFilePath", configFilePath != null ? configFilePath.toString() : "N/A");
        summary.put("configFileExists", configFilePath != null && Files.exists(configFilePath));

        return summary;
    }

    /**
     * Applies a framework preset configuration.
     *
     * @param presetName The preset name: "performance", "debug", "minimal", or "balanced"
     * @return The applied configuration
     */
    public Nd4jEnvironmentConfig applyFrameworkPreset(String presetName) {
        Nd4jEnvironmentConfig preset = switch (presetName.toLowerCase()) {
            case "performance" -> Nd4jEnvironmentConfig.builder()
                    .optimizerEnabled(true)
                    .optimizerFp16(true)
                    .dspNoFreeze(false)      // freeze enabled
                    .dspNoNativeDecode(false) // native decode enabled
                    .dspNoAttnOverride(false) // attention override enabled
                    .dspNoDirect(false)       // direct mode enabled
                    .tritonSkipKernels(false) // Triton kernels enabled
                    .tritonTf32(false)        // TF32 disabled for max precision
                    .cublasDisableWorkspace(false) // workspace capture enabled
                    .dspDiagnostics(null)     // no diagnostics
                    .opTiming(false)          // op timing disabled
                    .build();

            case "debug" -> Nd4jEnvironmentConfig.builder()
                    .optimizerEnabled(true)
                    .optimizerFp16(false)     // FP32 for debugging
                    .dspNoFreeze(true)        // freeze disabled for inspection
                    .dspNoNativeDecode(false)
                    .dspNoAttnOverride(false)
                    .dspNoDirect(false)
                    .tritonSkipKernels(true)  // skip Triton for easier debugging
                    .tritonTf32(false)
                    .cublasDisableWorkspace(false)
                    .dspDiagnostics("ALL")    // enable all diagnostics
                    .opTiming(true)           // enable op timing
                    .build();

            case "minimal" -> Nd4jEnvironmentConfig.builder()
                    .optimizerEnabled(false)  // disable optimizer
                    .optimizerFp16(false)
                    .dspNoFreeze(true)
                    .dspNoNativeDecode(true)  // disable native decode
                    .dspNoAttnOverride(true)  // disable attention override
                    .dspNoDirect(true)        // disable direct mode
                    .tritonSkipKernels(true)  // skip Triton
                    .tritonTf32(false)
                    .cublasDisableWorkspace(false)
                    .dspDiagnostics(null)
                    .opTiming(false)
                    .build();

            case "balanced" -> Nd4jEnvironmentConfig.builder()
                    .optimizerEnabled(true)
                    .optimizerFp16(true)
                    .dspNoFreeze(false)
                    .dspNoNativeDecode(false)
                    .dspNoAttnOverride(false)
                    .dspNoDirect(false)
                    .tritonSkipKernels(false)
                    .tritonTf32(true)         // TF32 enabled for performance
                    .cublasDisableWorkspace(false)
                    .dspDiagnostics(null)
                    .opTiming(false)
                    .build();

            default -> throw new IllegalArgumentException(
                    "Unknown framework preset: " + presetName + ". Valid presets: performance, debug, minimal, balanced");
        };

        // Merge preset with defaults to fill in any missing values
        currentConfig = Nd4jEnvironmentConfig.defaults().merge(preset);
        applyConfiguration(currentConfig);
        persistConfig();

        log.info("Applied SameDiff framework preset: {}", presetName);
        return currentConfig;
    }
}
