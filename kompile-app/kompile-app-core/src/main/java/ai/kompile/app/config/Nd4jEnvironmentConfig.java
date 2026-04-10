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

package ai.kompile.app.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.nd4j.common.config.ND4JSystemProperties;

/**
 * Configuration record for ND4J environment settings.
 * These settings are persisted to disk and loaded on startup.
 *
 * IMPORTANT: These settings MUST be applied before SameDiff usage.
 * The MainApplication loads and applies these settings before Spring context starts.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Nd4jEnvironmentConfig(
        // Thread configuration
        @JsonProperty("maxThreads") Integer maxThreads,
        @JsonProperty("maxMasterThreads") Integer maxMasterThreads,

        // Debug/verbose modes
        @JsonProperty("debug") Boolean debug,
        @JsonProperty("verbose") Boolean verbose,
        @JsonProperty("profiling") Boolean profiling,

        // Core settings
        @JsonProperty("enableBlas") Boolean enableBlas,
        @JsonProperty("helpersAllowed") Boolean helpersAllowed,
        @JsonProperty("leaksDetector") Boolean leaksDetector,

        // Performance thresholds
        @JsonProperty("tadThreshold") Integer tadThreshold,
        @JsonProperty("elementwiseThreshold") Integer elementwiseThreshold,

        // Memory limits (in bytes, 0 = unlimited)
        @JsonProperty("maxPrimaryMemory") Long maxPrimaryMemory,
        @JsonProperty("maxSpecialMemory") Long maxSpecialMemory,
        @JsonProperty("maxDeviceMemory") Long maxDeviceMemory,

        // Lifecycle tracking master switch
        @JsonProperty("lifecycleTracking") Boolean lifecycleTracking,

        // Lifecycle tracking sub-options
        @JsonProperty("trackViews") Boolean trackViews,
        @JsonProperty("trackDeletions") Boolean trackDeletions,
        @JsonProperty("snapshotFiles") Boolean snapshotFiles,
        @JsonProperty("trackOperations") Boolean trackOperations,

        // Lifecycle tracking parameters
        @JsonProperty("stackDepth") Integer stackDepth,
        @JsonProperty("reportInterval") Integer reportInterval,
        @JsonProperty("maxDeletionHistory") Integer maxDeletionHistory,

        // Individual tracker toggles
        @JsonProperty("ndArrayTracking") Boolean ndArrayTracking,
        @JsonProperty("dataBufferTracking") Boolean dataBufferTracking,
        @JsonProperty("tadCacheTracking") Boolean tadCacheTracking,
        @JsonProperty("shapeCacheTracking") Boolean shapeCacheTracking,
        @JsonProperty("opContextTracking") Boolean opContextTracking,

        // Advanced debugging - function tracing
        @JsonProperty("funcTracePrintAllocate") Boolean funcTracePrintAllocate,
        @JsonProperty("funcTracePrintDeallocate") Boolean funcTracePrintDeallocate,
        @JsonProperty("funcTracePrintJavaOnly") Boolean funcTracePrintJavaOnly,

        // Advanced debugging - other
        @JsonProperty("logNativeNDArrayCreation") Boolean logNativeNDArrayCreation,
        @JsonProperty("logNDArrayEvents") Boolean logNDArrayEvents,
        @JsonProperty("truncateNDArrayLogStrings") Boolean truncateNDArrayLogStrings,
        @JsonProperty("numWorkspaceEventsToKeep") Integer numWorkspaceEventsToKeep,
        @JsonProperty("checkInputChange") Boolean checkInputChange,
        @JsonProperty("checkOutputChange") Boolean checkOutputChange,
        @JsonProperty("trackWorkspaceOpenClose") Boolean trackWorkspaceOpenClose,
        @JsonProperty("deleteShapeInfo") Boolean deleteShapeInfo,
        @JsonProperty("deletePrimary") Boolean deletePrimary,
        @JsonProperty("deleteSpecial") Boolean deleteSpecial,
        @JsonProperty("variableTracingEnabled") Boolean variableTracingEnabled,

        // JavaCPP settings
        @JsonProperty("javacppLoggerDebug") Boolean javacppLoggerDebug,
        @JsonProperty("javacppPathsFirst") Boolean javacppPathsFirst,

        // BLAS configuration (new in ND4J)
        @JsonProperty("blasSerializationEnabled") Boolean blasSerializationEnabled,
        @JsonProperty("openBlasThreads") Integer openBlasThreads,

        // OpenMP thread configuration
        @JsonProperty("ompNumThreads") Integer ompNumThreads,

        // CUDA configuration
        @JsonProperty("cudaCurrentDevice") Integer cudaCurrentDevice,
        @JsonProperty("cudaMemoryPinned") Boolean cudaMemoryPinned,
        @JsonProperty("cudaUseManagedMemory") Boolean cudaUseManagedMemory,
        @JsonProperty("cudaMemoryPoolSize") Integer cudaMemoryPoolSize,
        @JsonProperty("cudaForceP2P") Boolean cudaForceP2P,
        @JsonProperty("cudaAllocatorEnabled") Boolean cudaAllocatorEnabled,
        @JsonProperty("cudaMaxBlocks") Integer cudaMaxBlocks,
        @JsonProperty("cudaMaxThreadsPerBlock") Integer cudaMaxThreadsPerBlock,
        @JsonProperty("cudaAsyncExecution") Boolean cudaAsyncExecution,
        @JsonProperty("cudaStreamLimit") Integer cudaStreamLimit,
        @JsonProperty("cudaUseDeviceHost") Boolean cudaUseDeviceHost,
        @JsonProperty("cudaEventLimit") Integer cudaEventLimit,
        @JsonProperty("cudaCachingAllocatorLimit") Integer cudaCachingAllocatorLimit,
        @JsonProperty("cudaUseUnifiedMemory") Boolean cudaUseUnifiedMemory,
        @JsonProperty("cudaPrefetchSize") Integer cudaPrefetchSize,
        @JsonProperty("cudaGraphOptimization") Boolean cudaGraphOptimization,
        @JsonProperty("cudaTensorCoreEnabled") Boolean cudaTensorCoreEnabled,
        @JsonProperty("cudaBlockingSync") Integer cudaBlockingSync,
        @JsonProperty("cudaDeviceSchedule") Integer cudaDeviceSchedule,
        @JsonProperty("cudaStackSize") Long cudaStackSize,
        @JsonProperty("cudaMallocHeapSize") Long cudaMallocHeapSize,
        @JsonProperty("cudaPrintfFifoSize") Long cudaPrintfFifoSize,
        @JsonProperty("cudaDevRuntimeSyncDepth") Long cudaDevRuntimeSyncDepth,
        @JsonProperty("cudaDevRuntimePendingLaunchCount") Long cudaDevRuntimePendingLaunchCount,
        @JsonProperty("cudaMaxL2FetchGranularity") Long cudaMaxL2FetchGranularity,
        @JsonProperty("cudaPersistingL2CacheSize") Long cudaPersistingL2CacheSize,

        // Triton compiler configuration
        @JsonProperty("tritonBuildThreads") Integer tritonBuildThreads,
        @JsonProperty("tritonCacheEnabled") Boolean tritonCacheEnabled,
        @JsonProperty("tritonVerbose") Boolean tritonVerbose,
        @JsonProperty("tritonAlwaysCompile") Boolean tritonAlwaysCompile,
        @JsonProperty("tritonNumWarps") Integer tritonNumWarps,
        @JsonProperty("tritonNumStages") Integer tritonNumStages,
        @JsonProperty("tritonNumCTAs") Integer tritonNumCTAs,
        @JsonProperty("tritonEnableFpFusion") Boolean tritonEnableFpFusion,
        @JsonProperty("tritonCacheDir") String tritonCacheDir,
        @JsonProperty("tritonDumpDir") String tritonDumpDir,
        @JsonProperty("tritonOverrideArch") String tritonOverrideArch,

        // SameDiff graph optimizer / DSP (decode-side processing) configuration
        @JsonProperty("optimizerEnabled") Boolean optimizerEnabled,       // nd4j.optimizer.enabled
        @JsonProperty("optimizerFp16") Boolean optimizerFp16,             // nd4j.optimizer.fp16
        @JsonProperty("dspNoFreeze") Boolean dspNoFreeze,                 // nd4j.dsp.nofreeze
        @JsonProperty("dspNoNativeDecode") Boolean dspNoNativeDecode,     // nd4j.dsp.noNativeDecodeInputs
        @JsonProperty("dspNoAttnOverride") Boolean dspNoAttnOverride,     // nd4j.dsp.noAttnOverride
        @JsonProperty("dspNoDirect") Boolean dspNoDirect,                 // nd4j.dsp.noDirect
        @JsonProperty("tritonSkipKernels") Boolean tritonSkipKernels,     // nd4j.triton.skipKernels
        @JsonProperty("tritonTf32") Boolean tritonTf32,                   // nd4j.triton.tf32
        @JsonProperty("cublasDisableWorkspace") Boolean cublasDisableWorkspace, // nd4j.cublas.captureWorkspace=0
        @JsonProperty("dspDiagnostics") String dspDiagnostics,            // nd4j.dsp.diagnostics (e.g. "ALL")
        @JsonProperty("opTiming") Boolean opTiming,                       // nd4j.opTiming

        // CUDA graph capture OOM retry and memory management (env vars in libnd4j)
        @JsonProperty("dspCaptureOomMaxRetries") Integer dspCaptureOomMaxRetries,       // ND4J_DSP_CAPTURE_OOM_MAX_RETRIES
        @JsonProperty("dspCaptureOomRetryInterval") Integer dspCaptureOomRetryInterval, // ND4J_DSP_CAPTURE_OOM_RETRY_INTERVAL
        @JsonProperty("dspCublasWorkspaceMb") Integer dspCublasWorkspaceMb,             // ND4J_DSP_CUBLAS_WORKSPACE_MB
        @JsonProperty("dspGraphMetadataSafetyMb") Integer dspGraphMetadataSafetyMb,     // ND4J_DSP_GRAPH_METADATA_SAFETY_MB
        @JsonProperty("dspProactiveEvictBeforeCapture") Boolean dspProactiveEvictBeforeCapture, // ND4J_DSP_PROACTIVE_EVICT
        @JsonProperty("dspLruEviction") Boolean dspLruEviction,                         // ND4J_DSP_LRU_EVICTION
        @JsonProperty("dspCaptureWorkspaceMb") Integer dspCaptureWorkspaceMb            // ND4J_DSP_CAPTURE_WORKSPACE_MB
) {

    /**
     * Creates a default configuration with sensible defaults.
     * These values match the current hardcoded defaults in MainApplication.
     */
    public static Nd4jEnvironmentConfig defaults() {
        return new Nd4jEnvironmentConfig(
                4,      // maxThreads
                4,      // maxMasterThreads
                false,  // debug
                false,  // verbose
                false,  // profiling
                true,   // enableBlas - BLAS should be enabled by default
                true,   // helpersAllowed - allow cuDNN/MKLDNN by default
                false,  // leaksDetector
                8,      // tadThreshold
                8,      // elementwiseThreshold
                0L,     // maxPrimaryMemory (0 = unlimited)
                0L,     // maxSpecialMemory (0 = unlimited)
                0L,     // maxDeviceMemory (0 = unlimited)
                false,  // lifecycleTracking - disabled by default for fast startup
                false,  // trackViews
                false,  // trackDeletions
                false,  // snapshotFiles
                false,  // trackOperations
                16,     // stackDepth
                120,    // reportInterval
                1000,   // maxDeletionHistory
                false,  // ndArrayTracking
                false,  // dataBufferTracking
                false,  // tadCacheTracking
                false,  // shapeCacheTracking
                false,  // opContextTracking
                false,  // funcTracePrintAllocate
                false,  // funcTracePrintDeallocate
                false,  // funcTracePrintJavaOnly
                false,  // logNativeNDArrayCreation
                false,  // logNDArrayEvents
                false,  // truncateNDArrayLogStrings
                -1,     // numWorkspaceEventsToKeep (-1 = unlimited)
                false,  // checkInputChange
                false,  // checkOutputChange
                false,  // trackWorkspaceOpenClose
                true,   // deleteShapeInfo - should delete by default
                true,   // deletePrimary - should delete by default
                true,   // deleteSpecial - should delete by default
                false,  // variableTracingEnabled
                true,   // javacppLoggerDebug
                true,   // javacppPathsFirst
                true,   // blasSerializationEnabled - serialize BLAS calls for thread safety (default: true)
                1,      // openBlasThreads - OpenBLAS internal threads (default: 1 for safety)
                4,      // ompNumThreads - OpenMP threads (default: 4)
                // CUDA defaults (null = use CUDA defaults)
                null,   // cudaCurrentDevice
                null,   // cudaMemoryPinned
                null,   // cudaUseManagedMemory
                null,   // cudaMemoryPoolSize
                null,   // cudaForceP2P
                null,   // cudaAllocatorEnabled
                null,   // cudaMaxBlocks
                null,   // cudaMaxThreadsPerBlock
                null,   // cudaAsyncExecution
                null,   // cudaStreamLimit
                null,   // cudaUseDeviceHost
                null,   // cudaEventLimit
                null,   // cudaCachingAllocatorLimit
                null,   // cudaUseUnifiedMemory
                null,   // cudaPrefetchSize
                null,   // cudaGraphOptimization
                null,   // cudaTensorCoreEnabled
                null,   // cudaBlockingSync
                null,   // cudaDeviceSchedule
                null,   // cudaStackSize
                null,   // cudaMallocHeapSize
                null,   // cudaPrintfFifoSize
                null,   // cudaDevRuntimeSyncDepth
                null,   // cudaDevRuntimePendingLaunchCount
                null,   // cudaMaxL2FetchGranularity
                null,   // cudaPersistingL2CacheSize
                // Triton defaults (null = use Triton defaults)
                null,   // tritonBuildThreads
                null,   // tritonCacheEnabled
                null,   // tritonVerbose
                null,   // tritonAlwaysCompile
                null,   // tritonNumWarps
                null,   // tritonNumStages
                null,   // tritonNumCTAs
                null,   // tritonEnableFpFusion
                null,   // tritonCacheDir
                null,   // tritonDumpDir
                null,   // tritonOverrideArch
                // SameDiff graph optimizer / DSP defaults
                true,   // optimizerEnabled - graph optimizer ON by default
                true,   // optimizerFp16 - FP16 weight conversion ON by default (halves VRAM)
                false,  // dspNoFreeze - freeze ON by default (false = don't skip freeze)
                false,  // dspNoNativeDecode - native decode inputs ON by default
                false,  // dspNoAttnOverride - attention override ON by default
                false,  // dspNoDirect - direct mode ON by default
                false,  // tritonSkipKernels - triton kernels ON by default
                false,  // tritonTf32 - TF32 precision OFF by default
                false,  // cublasDisableWorkspace - workspace capture ON by default
                null,   // dspDiagnostics - no diagnostics by default
                false,  // opTiming - op timing OFF by default
                // CUDA graph capture OOM retry defaults
                null,   // dspCaptureOomMaxRetries - use native default (3)
                null,   // dspCaptureOomRetryInterval - use native default (4)
                null,   // dspCublasWorkspaceMb - use native default (256)
                null,   // dspGraphMetadataSafetyMb - use native default (16)
                null,   // dspProactiveEvictBeforeCapture - use native default (true)
                null,   // dspLruEviction - use native default (true)
                null    // dspCaptureWorkspaceMb - use native default (128)
        );
    }

    /**
     * Captures the actual ND4J environment state from the running process.
     * Use this instead of manually constructing with 100+ positional args.
     *
     * @param currentConfig optional current persisted config for fields with no native getter
     * @param ompThreadsOverride OMP threads override (omp_get_num_threads returns 1 outside parallel regions)
     * @return snapshot of the live ND4J environment
     */
    public static Nd4jEnvironmentConfig captureFromEnvironment(Nd4jEnvironmentConfig currentConfig, int ompThreadsOverride) {
        var env = org.nd4j.linalg.factory.Nd4j.getEnvironment();
        boolean isCuda = !env.isCPU();

        return Nd4jEnvironmentConfig.builder()
                .maxThreads((int) env.maxThreads())
                .maxMasterThreads((int) env.maxMasterThreads())
                .debug(env.isDebug())
                .verbose(env.isVerbose())
                .profiling(env.isProfiling())
                .enableBlas(env.isEnableBlas())
                .helpersAllowed(env.helpersAllowed())
                .leaksDetector(env.isDetectingLeaks())
                .tadThreshold(env.tadThreshold())
                .elementwiseThreshold(env.elementwiseThreshold())
                .maxPrimaryMemory(0L)
                .maxSpecialMemory(0L)
                .maxDeviceMemory(0L)
                .lifecycleTracking(env.isLifecycleTracking())
                .trackViews(env.isTrackViews())
                .trackDeletions(env.isTrackDeletions())
                .snapshotFiles(env.isSnapshotFiles())
                .trackOperations(env.isTrackOperations())
                .stackDepth((int) env.getStackDepth())
                .reportInterval((int) env.getReportInterval())
                .maxDeletionHistory((int) env.getMaxDeletionHistory())
                .ndArrayTracking(env.isNDArrayTracking())
                .dataBufferTracking(env.isDataBufferTracking())
                .tadCacheTracking(env.isTADCacheTracking())
                .shapeCacheTracking(env.isShapeCacheTracking())
                .opContextTracking(env.isOpContextTracking())
                .funcTracePrintAllocate(env.isFuncTracePrintAllocate())
                .funcTracePrintDeallocate(env.isFuncTracePrintDeallocate())
                .funcTracePrintJavaOnly(env.isFuncTracePrintJavaOnly())
                .logNativeNDArrayCreation(env.isLogNativeNDArrayCreation())
                .logNDArrayEvents(env.isLogNDArrayEvents())
                .truncateNDArrayLogStrings(env.isTruncateNDArrayLogStrings())
                .numWorkspaceEventsToKeep(env.numWorkspaceEventsToKeep())
                .checkInputChange(env.isCheckInputChange())
                .checkOutputChange(env.isCheckOutputChange())
                .trackWorkspaceOpenClose(env.isTrackWorkspaceOpenClose())
                .deleteShapeInfo(env.isDeleteShapeInfo())
                .deletePrimary(env.isDeletePrimary())
                .deleteSpecial(env.isDeleteSpecial())
                .variableTracingEnabled(env.isVariableTracingEnabled())
                .javacppLoggerDebug("true".equals(System.getProperty("org.bytedeco.javacpp.logger.debug")))
                .javacppPathsFirst("true".equals(System.getProperty("org.bytedeco.javacpp.pathsFirst")))
                .blasSerializationEnabled(currentConfig != null ? currentConfig.blasSerializationEnabled() : true)
                .openBlasThreads(currentConfig != null ? currentConfig.openBlasThreads() : 1)
                .ompNumThreads(ompThreadsOverride)
                // CUDA settings - only populated if running on CUDA backend
                .cudaCurrentDevice(isCuda ? env.cudaCurrentDevice() : null)
                .cudaMemoryPinned(isCuda ? env.cudaMemoryPinned() : null)
                .cudaUseManagedMemory(isCuda ? env.cudaUseManagedMemory() : null)
                .cudaMemoryPoolSize(isCuda ? env.cudaMemoryPoolSize() : null)
                .cudaForceP2P(isCuda ? env.cudaForceP2P() : null)
                .cudaAllocatorEnabled(isCuda ? env.cudaAllocatorEnabled() : null)
                .cudaMaxBlocks(isCuda ? env.cudaMaxBlocks() : null)
                .cudaMaxThreadsPerBlock(isCuda ? env.cudaMaxThreadsPerBlock() : null)
                .cudaAsyncExecution(isCuda ? env.cudaAsyncExecution() : null)
                .cudaStreamLimit(isCuda ? env.cudaStreamLimit() : null)
                .cudaUseDeviceHost(isCuda ? env.cudaUseDeviceHost() : null)
                .cudaEventLimit(isCuda ? env.cudaEventLimit() : null)
                .cudaCachingAllocatorLimit(isCuda ? env.cudaCachingAllocatorLimit() : null)
                .cudaUseUnifiedMemory(isCuda ? env.cudaUseUnifiedMemory() : null)
                .cudaPrefetchSize(isCuda ? env.cudaPrefetchSize() : null)
                .cudaGraphOptimization(isCuda ? env.cudaGraphOptimization() : null)
                .cudaTensorCoreEnabled(isCuda ? env.cudaTensorCoreEnabled() : null)
                .cudaBlockingSync(isCuda ? env.cudaBlockingSync() : null)
                .cudaDeviceSchedule(isCuda ? env.cudaDeviceSchedule() : null)
                .cudaStackSize(isCuda ? env.cudaStackSize() : null)
                .cudaMallocHeapSize(isCuda ? env.cudaMallocHeapSize() : null)
                .cudaPrintfFifoSize(isCuda ? env.cudaPrintfFifoSize() : null)
                .cudaDevRuntimeSyncDepth(isCuda ? env.cudaDevRuntimeSyncDepth() : null)
                .cudaDevRuntimePendingLaunchCount(isCuda ? env.cudaDevRuntimePendingLaunchCount() : null)
                .cudaMaxL2FetchGranularity(isCuda ? env.cudaMaxL2FetchGranularity() : null)
                .cudaPersistingL2CacheSize(isCuda ? env.cudaPersistingL2CacheSize() : null)
                // Triton settings
                .tritonBuildThreads(isCuda ? env.tritonBuildThreads() : null)
                .tritonCacheEnabled(isCuda ? env.tritonCacheEnabled() : null)
                .tritonVerbose(isCuda ? env.tritonVerbose() : null)
                .tritonAlwaysCompile(isCuda ? env.tritonAlwaysCompile() : null)
                .tritonNumWarps(isCuda ? env.tritonNumWarps() : null)
                .tritonNumStages(isCuda ? env.tritonNumStages() : null)
                .tritonNumCTAs(isCuda ? env.tritonNumCTAs() : null)
                .tritonEnableFpFusion(isCuda ? env.tritonEnableFpFusion() : null)
                .tritonCacheDir(isCuda ? env.tritonCacheDir() : null)
                .tritonDumpDir(isCuda ? env.tritonDumpDir() : null)
                .tritonOverrideArch(isCuda ? env.tritonOverrideArch() : null)
                // DSP/optimizer - capture from system properties (these are set by config, not ND4J env)
                .optimizerEnabled(getBoolProp(ND4JSystemProperties.OPTIMIZER_ENABLED))
                .optimizerFp16(getBoolProp(ND4JSystemProperties.OPTIMIZER_FP16))
                .dspNoFreeze(getBoolProp(ND4JSystemProperties.DSP_NO_FREEZE))
                .dspNoNativeDecode(getBoolProp(ND4JSystemProperties.DSP_NO_NATIVE_DECODE_INPUTS))
                .dspNoAttnOverride(getBoolProp(ND4JSystemProperties.DSP_NO_ATTN_OVERRIDE))
                .dspNoDirect(getBoolProp(ND4JSystemProperties.DSP_NO_DIRECT))
                .tritonSkipKernels(getBoolProp(ND4JSystemProperties.TRITON_SKIP_KERNELS))
                .tritonTf32(getBoolProp(ND4JSystemProperties.TRITON_TF32))
                .cublasDisableWorkspace("0".equals(System.getProperty(ND4JSystemProperties.CUBLAS_CAPTURE_WORKSPACE)) ? true : null)
                .dspDiagnostics(System.getProperty(ND4JSystemProperties.DSP_DIAGNOSTICS))
                .opTiming(getBoolProp(ND4JSystemProperties.OP_TIMING))
                // CUDA graph capture OOM retry - read from env vars
                .dspCaptureOomMaxRetries(getIntEnv("ND4J_DSP_CAPTURE_OOM_MAX_RETRIES"))
                .dspCaptureOomRetryInterval(getIntEnv("ND4J_DSP_CAPTURE_OOM_RETRY_INTERVAL"))
                .dspCublasWorkspaceMb(getIntEnv("ND4J_DSP_CUBLAS_WORKSPACE_MB"))
                .dspGraphMetadataSafetyMb(getIntEnv("ND4J_DSP_GRAPH_METADATA_SAFETY_MB"))
                .dspProactiveEvictBeforeCapture(getBoolEnv("ND4J_DSP_PROACTIVE_EVICT"))
                .dspLruEviction(getBoolEnv("ND4J_DSP_LRU_EVICTION"))
                .dspCaptureWorkspaceMb(getIntEnv("ND4J_DSP_CAPTURE_WORKSPACE_MB"))
                .build();
    }

    private static Boolean getBoolProp(String key) {
        String val = System.getProperty(key);
        return val != null ? Boolean.parseBoolean(val) : null;
    }

    private static Integer getIntEnv(String key) {
        String val = System.getenv(key);
        if (val == null) val = System.getProperty(key);
        if (val != null) {
            try { return Integer.parseInt(val); } catch (NumberFormatException e) { /* ignore */ }
        }
        return null;
    }

    private static Boolean getBoolEnv(String key) {
        String val = System.getenv(key);
        if (val == null) val = System.getProperty(key);
        return val != null ? Boolean.parseBoolean(val) : null;
    }

    /**
     * Merges this config with another, preferring non-null values from the other config.
     * Useful for partial updates.
     */
    public Nd4jEnvironmentConfig merge(Nd4jEnvironmentConfig other) {
        if (other == null) {
            return this;
        }
        return new Nd4jEnvironmentConfig(
                other.maxThreads() != null ? other.maxThreads() : this.maxThreads(),
                other.maxMasterThreads() != null ? other.maxMasterThreads() : this.maxMasterThreads(),
                other.debug() != null ? other.debug() : this.debug(),
                other.verbose() != null ? other.verbose() : this.verbose(),
                other.profiling() != null ? other.profiling() : this.profiling(),
                other.enableBlas() != null ? other.enableBlas() : this.enableBlas(),
                other.helpersAllowed() != null ? other.helpersAllowed() : this.helpersAllowed(),
                other.leaksDetector() != null ? other.leaksDetector() : this.leaksDetector(),
                other.tadThreshold() != null ? other.tadThreshold() : this.tadThreshold(),
                other.elementwiseThreshold() != null ? other.elementwiseThreshold() : this.elementwiseThreshold(),
                other.maxPrimaryMemory() != null ? other.maxPrimaryMemory() : this.maxPrimaryMemory(),
                other.maxSpecialMemory() != null ? other.maxSpecialMemory() : this.maxSpecialMemory(),
                other.maxDeviceMemory() != null ? other.maxDeviceMemory() : this.maxDeviceMemory(),
                other.lifecycleTracking() != null ? other.lifecycleTracking() : this.lifecycleTracking(),
                other.trackViews() != null ? other.trackViews() : this.trackViews(),
                other.trackDeletions() != null ? other.trackDeletions() : this.trackDeletions(),
                other.snapshotFiles() != null ? other.snapshotFiles() : this.snapshotFiles(),
                other.trackOperations() != null ? other.trackOperations() : this.trackOperations(),
                other.stackDepth() != null ? other.stackDepth() : this.stackDepth(),
                other.reportInterval() != null ? other.reportInterval() : this.reportInterval(),
                other.maxDeletionHistory() != null ? other.maxDeletionHistory() : this.maxDeletionHistory(),
                other.ndArrayTracking() != null ? other.ndArrayTracking() : this.ndArrayTracking(),
                other.dataBufferTracking() != null ? other.dataBufferTracking() : this.dataBufferTracking(),
                other.tadCacheTracking() != null ? other.tadCacheTracking() : this.tadCacheTracking(),
                other.shapeCacheTracking() != null ? other.shapeCacheTracking() : this.shapeCacheTracking(),
                other.opContextTracking() != null ? other.opContextTracking() : this.opContextTracking(),
                other.funcTracePrintAllocate() != null ? other.funcTracePrintAllocate() : this.funcTracePrintAllocate(),
                other.funcTracePrintDeallocate() != null ? other.funcTracePrintDeallocate() : this.funcTracePrintDeallocate(),
                other.funcTracePrintJavaOnly() != null ? other.funcTracePrintJavaOnly() : this.funcTracePrintJavaOnly(),
                other.logNativeNDArrayCreation() != null ? other.logNativeNDArrayCreation() : this.logNativeNDArrayCreation(),
                other.logNDArrayEvents() != null ? other.logNDArrayEvents() : this.logNDArrayEvents(),
                other.truncateNDArrayLogStrings() != null ? other.truncateNDArrayLogStrings() : this.truncateNDArrayLogStrings(),
                other.numWorkspaceEventsToKeep() != null ? other.numWorkspaceEventsToKeep() : this.numWorkspaceEventsToKeep(),
                other.checkInputChange() != null ? other.checkInputChange() : this.checkInputChange(),
                other.checkOutputChange() != null ? other.checkOutputChange() : this.checkOutputChange(),
                other.trackWorkspaceOpenClose() != null ? other.trackWorkspaceOpenClose() : this.trackWorkspaceOpenClose(),
                other.deleteShapeInfo() != null ? other.deleteShapeInfo() : this.deleteShapeInfo(),
                other.deletePrimary() != null ? other.deletePrimary() : this.deletePrimary(),
                other.deleteSpecial() != null ? other.deleteSpecial() : this.deleteSpecial(),
                other.variableTracingEnabled() != null ? other.variableTracingEnabled() : this.variableTracingEnabled(),
                other.javacppLoggerDebug() != null ? other.javacppLoggerDebug() : this.javacppLoggerDebug(),
                other.javacppPathsFirst() != null ? other.javacppPathsFirst() : this.javacppPathsFirst(),
                other.blasSerializationEnabled() != null ? other.blasSerializationEnabled() : this.blasSerializationEnabled(),
                other.openBlasThreads() != null ? other.openBlasThreads() : this.openBlasThreads(),
                other.ompNumThreads() != null ? other.ompNumThreads() : this.ompNumThreads(),
                // CUDA settings
                other.cudaCurrentDevice() != null ? other.cudaCurrentDevice() : this.cudaCurrentDevice(),
                other.cudaMemoryPinned() != null ? other.cudaMemoryPinned() : this.cudaMemoryPinned(),
                other.cudaUseManagedMemory() != null ? other.cudaUseManagedMemory() : this.cudaUseManagedMemory(),
                other.cudaMemoryPoolSize() != null ? other.cudaMemoryPoolSize() : this.cudaMemoryPoolSize(),
                other.cudaForceP2P() != null ? other.cudaForceP2P() : this.cudaForceP2P(),
                other.cudaAllocatorEnabled() != null ? other.cudaAllocatorEnabled() : this.cudaAllocatorEnabled(),
                other.cudaMaxBlocks() != null ? other.cudaMaxBlocks() : this.cudaMaxBlocks(),
                other.cudaMaxThreadsPerBlock() != null ? other.cudaMaxThreadsPerBlock() : this.cudaMaxThreadsPerBlock(),
                other.cudaAsyncExecution() != null ? other.cudaAsyncExecution() : this.cudaAsyncExecution(),
                other.cudaStreamLimit() != null ? other.cudaStreamLimit() : this.cudaStreamLimit(),
                other.cudaUseDeviceHost() != null ? other.cudaUseDeviceHost() : this.cudaUseDeviceHost(),
                other.cudaEventLimit() != null ? other.cudaEventLimit() : this.cudaEventLimit(),
                other.cudaCachingAllocatorLimit() != null ? other.cudaCachingAllocatorLimit() : this.cudaCachingAllocatorLimit(),
                other.cudaUseUnifiedMemory() != null ? other.cudaUseUnifiedMemory() : this.cudaUseUnifiedMemory(),
                other.cudaPrefetchSize() != null ? other.cudaPrefetchSize() : this.cudaPrefetchSize(),
                other.cudaGraphOptimization() != null ? other.cudaGraphOptimization() : this.cudaGraphOptimization(),
                other.cudaTensorCoreEnabled() != null ? other.cudaTensorCoreEnabled() : this.cudaTensorCoreEnabled(),
                other.cudaBlockingSync() != null ? other.cudaBlockingSync() : this.cudaBlockingSync(),
                other.cudaDeviceSchedule() != null ? other.cudaDeviceSchedule() : this.cudaDeviceSchedule(),
                other.cudaStackSize() != null ? other.cudaStackSize() : this.cudaStackSize(),
                other.cudaMallocHeapSize() != null ? other.cudaMallocHeapSize() : this.cudaMallocHeapSize(),
                other.cudaPrintfFifoSize() != null ? other.cudaPrintfFifoSize() : this.cudaPrintfFifoSize(),
                other.cudaDevRuntimeSyncDepth() != null ? other.cudaDevRuntimeSyncDepth() : this.cudaDevRuntimeSyncDepth(),
                other.cudaDevRuntimePendingLaunchCount() != null ? other.cudaDevRuntimePendingLaunchCount() : this.cudaDevRuntimePendingLaunchCount(),
                other.cudaMaxL2FetchGranularity() != null ? other.cudaMaxL2FetchGranularity() : this.cudaMaxL2FetchGranularity(),
                other.cudaPersistingL2CacheSize() != null ? other.cudaPersistingL2CacheSize() : this.cudaPersistingL2CacheSize(),
                // Triton settings
                other.tritonBuildThreads() != null ? other.tritonBuildThreads() : this.tritonBuildThreads(),
                other.tritonCacheEnabled() != null ? other.tritonCacheEnabled() : this.tritonCacheEnabled(),
                other.tritonVerbose() != null ? other.tritonVerbose() : this.tritonVerbose(),
                other.tritonAlwaysCompile() != null ? other.tritonAlwaysCompile() : this.tritonAlwaysCompile(),
                other.tritonNumWarps() != null ? other.tritonNumWarps() : this.tritonNumWarps(),
                other.tritonNumStages() != null ? other.tritonNumStages() : this.tritonNumStages(),
                other.tritonNumCTAs() != null ? other.tritonNumCTAs() : this.tritonNumCTAs(),
                other.tritonEnableFpFusion() != null ? other.tritonEnableFpFusion() : this.tritonEnableFpFusion(),
                other.tritonCacheDir() != null ? other.tritonCacheDir() : this.tritonCacheDir(),
                other.tritonDumpDir() != null ? other.tritonDumpDir() : this.tritonDumpDir(),
                other.tritonOverrideArch() != null ? other.tritonOverrideArch() : this.tritonOverrideArch(),
                // DSP/optimizer settings
                other.optimizerEnabled() != null ? other.optimizerEnabled() : this.optimizerEnabled(),
                other.optimizerFp16() != null ? other.optimizerFp16() : this.optimizerFp16(),
                other.dspNoFreeze() != null ? other.dspNoFreeze() : this.dspNoFreeze(),
                other.dspNoNativeDecode() != null ? other.dspNoNativeDecode() : this.dspNoNativeDecode(),
                other.dspNoAttnOverride() != null ? other.dspNoAttnOverride() : this.dspNoAttnOverride(),
                other.dspNoDirect() != null ? other.dspNoDirect() : this.dspNoDirect(),
                other.tritonSkipKernels() != null ? other.tritonSkipKernels() : this.tritonSkipKernels(),
                other.tritonTf32() != null ? other.tritonTf32() : this.tritonTf32(),
                other.cublasDisableWorkspace() != null ? other.cublasDisableWorkspace() : this.cublasDisableWorkspace(),
                other.dspDiagnostics() != null ? other.dspDiagnostics() : this.dspDiagnostics(),
                other.opTiming() != null ? other.opTiming() : this.opTiming(),
                // CUDA graph capture OOM retry
                other.dspCaptureOomMaxRetries() != null ? other.dspCaptureOomMaxRetries() : this.dspCaptureOomMaxRetries(),
                other.dspCaptureOomRetryInterval() != null ? other.dspCaptureOomRetryInterval() : this.dspCaptureOomRetryInterval(),
                other.dspCublasWorkspaceMb() != null ? other.dspCublasWorkspaceMb() : this.dspCublasWorkspaceMb(),
                other.dspGraphMetadataSafetyMb() != null ? other.dspGraphMetadataSafetyMb() : this.dspGraphMetadataSafetyMb(),
                other.dspProactiveEvictBeforeCapture() != null ? other.dspProactiveEvictBeforeCapture() : this.dspProactiveEvictBeforeCapture(),
                other.dspLruEviction() != null ? other.dspLruEviction() : this.dspLruEviction(),
                other.dspCaptureWorkspaceMb() != null ? other.dspCaptureWorkspaceMb() : this.dspCaptureWorkspaceMb()
        );
    }

    /**
     * Builder for creating partial configurations for updates.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Integer maxThreads;
        private Integer maxMasterThreads;
        private Boolean debug;
        private Boolean verbose;
        private Boolean profiling;
        private Boolean enableBlas;
        private Boolean helpersAllowed;
        private Boolean leaksDetector;
        private Integer tadThreshold;
        private Integer elementwiseThreshold;
        private Long maxPrimaryMemory;
        private Long maxSpecialMemory;
        private Long maxDeviceMemory;
        private Boolean lifecycleTracking;
        private Boolean trackViews;
        private Boolean trackDeletions;
        private Boolean snapshotFiles;
        private Boolean trackOperations;
        private Integer stackDepth;
        private Integer reportInterval;
        private Integer maxDeletionHistory;
        private Boolean ndArrayTracking;
        private Boolean dataBufferTracking;
        private Boolean tadCacheTracking;
        private Boolean shapeCacheTracking;
        private Boolean opContextTracking;
        private Boolean funcTracePrintAllocate;
        private Boolean funcTracePrintDeallocate;
        private Boolean funcTracePrintJavaOnly;
        private Boolean logNativeNDArrayCreation;
        private Boolean logNDArrayEvents;
        private Boolean truncateNDArrayLogStrings;
        private Integer numWorkspaceEventsToKeep;
        private Boolean checkInputChange;
        private Boolean checkOutputChange;
        private Boolean trackWorkspaceOpenClose;
        private Boolean deleteShapeInfo;
        private Boolean deletePrimary;
        private Boolean deleteSpecial;
        private Boolean variableTracingEnabled;
        private Boolean javacppLoggerDebug;
        private Boolean javacppPathsFirst;
        private Boolean blasSerializationEnabled;
        private Integer openBlasThreads;
        private Integer ompNumThreads;
        // CUDA fields
        private Integer cudaCurrentDevice;
        private Boolean cudaMemoryPinned;
        private Boolean cudaUseManagedMemory;
        private Integer cudaMemoryPoolSize;
        private Boolean cudaForceP2P;
        private Boolean cudaAllocatorEnabled;
        private Integer cudaMaxBlocks;
        private Integer cudaMaxThreadsPerBlock;
        private Boolean cudaAsyncExecution;
        private Integer cudaStreamLimit;
        private Boolean cudaUseDeviceHost;
        private Integer cudaEventLimit;
        private Integer cudaCachingAllocatorLimit;
        private Boolean cudaUseUnifiedMemory;
        private Integer cudaPrefetchSize;
        private Boolean cudaGraphOptimization;
        private Boolean cudaTensorCoreEnabled;
        private Integer cudaBlockingSync;
        private Integer cudaDeviceSchedule;
        private Long cudaStackSize;
        private Long cudaMallocHeapSize;
        private Long cudaPrintfFifoSize;
        private Long cudaDevRuntimeSyncDepth;
        private Long cudaDevRuntimePendingLaunchCount;
        private Long cudaMaxL2FetchGranularity;
        private Long cudaPersistingL2CacheSize;
        // Triton fields
        private Integer tritonBuildThreads;
        private Boolean tritonCacheEnabled;
        private Boolean tritonVerbose;
        private Boolean tritonAlwaysCompile;
        private Integer tritonNumWarps;
        private Integer tritonNumStages;
        private Integer tritonNumCTAs;
        private Boolean tritonEnableFpFusion;
        private String tritonCacheDir;
        private String tritonDumpDir;
        private String tritonOverrideArch;
        // DSP/optimizer fields
        private Boolean optimizerEnabled;
        private Boolean optimizerFp16;
        private Boolean dspNoFreeze;
        private Boolean dspNoNativeDecode;
        private Boolean dspNoAttnOverride;
        private Boolean dspNoDirect;
        private Boolean tritonSkipKernels;
        private Boolean tritonTf32;
        private Boolean cublasDisableWorkspace;
        private String dspDiagnostics;
        private Boolean opTiming;
        // CUDA graph capture OOM retry fields
        private Integer dspCaptureOomMaxRetries;
        private Integer dspCaptureOomRetryInterval;
        private Integer dspCublasWorkspaceMb;
        private Integer dspGraphMetadataSafetyMb;
        private Boolean dspProactiveEvictBeforeCapture;
        private Boolean dspLruEviction;
        private Integer dspCaptureWorkspaceMb;

        public Builder maxThreads(Integer maxThreads) { this.maxThreads = maxThreads; return this; }
        public Builder maxMasterThreads(Integer maxMasterThreads) { this.maxMasterThreads = maxMasterThreads; return this; }
        public Builder debug(Boolean debug) { this.debug = debug; return this; }
        public Builder verbose(Boolean verbose) { this.verbose = verbose; return this; }
        public Builder profiling(Boolean profiling) { this.profiling = profiling; return this; }
        public Builder enableBlas(Boolean enableBlas) { this.enableBlas = enableBlas; return this; }
        public Builder helpersAllowed(Boolean helpersAllowed) { this.helpersAllowed = helpersAllowed; return this; }
        public Builder leaksDetector(Boolean leaksDetector) { this.leaksDetector = leaksDetector; return this; }
        public Builder tadThreshold(Integer tadThreshold) { this.tadThreshold = tadThreshold; return this; }
        public Builder elementwiseThreshold(Integer elementwiseThreshold) { this.elementwiseThreshold = elementwiseThreshold; return this; }
        public Builder maxPrimaryMemory(Long maxPrimaryMemory) { this.maxPrimaryMemory = maxPrimaryMemory; return this; }
        public Builder maxSpecialMemory(Long maxSpecialMemory) { this.maxSpecialMemory = maxSpecialMemory; return this; }
        public Builder maxDeviceMemory(Long maxDeviceMemory) { this.maxDeviceMemory = maxDeviceMemory; return this; }
        public Builder lifecycleTracking(Boolean lifecycleTracking) { this.lifecycleTracking = lifecycleTracking; return this; }
        public Builder trackViews(Boolean trackViews) { this.trackViews = trackViews; return this; }
        public Builder trackDeletions(Boolean trackDeletions) { this.trackDeletions = trackDeletions; return this; }
        public Builder snapshotFiles(Boolean snapshotFiles) { this.snapshotFiles = snapshotFiles; return this; }
        public Builder trackOperations(Boolean trackOperations) { this.trackOperations = trackOperations; return this; }
        public Builder stackDepth(Integer stackDepth) { this.stackDepth = stackDepth; return this; }
        public Builder reportInterval(Integer reportInterval) { this.reportInterval = reportInterval; return this; }
        public Builder maxDeletionHistory(Integer maxDeletionHistory) { this.maxDeletionHistory = maxDeletionHistory; return this; }
        public Builder ndArrayTracking(Boolean ndArrayTracking) { this.ndArrayTracking = ndArrayTracking; return this; }
        public Builder dataBufferTracking(Boolean dataBufferTracking) { this.dataBufferTracking = dataBufferTracking; return this; }
        public Builder tadCacheTracking(Boolean tadCacheTracking) { this.tadCacheTracking = tadCacheTracking; return this; }
        public Builder shapeCacheTracking(Boolean shapeCacheTracking) { this.shapeCacheTracking = shapeCacheTracking; return this; }
        public Builder opContextTracking(Boolean opContextTracking) { this.opContextTracking = opContextTracking; return this; }
        public Builder funcTracePrintAllocate(Boolean funcTracePrintAllocate) { this.funcTracePrintAllocate = funcTracePrintAllocate; return this; }
        public Builder funcTracePrintDeallocate(Boolean funcTracePrintDeallocate) { this.funcTracePrintDeallocate = funcTracePrintDeallocate; return this; }
        public Builder funcTracePrintJavaOnly(Boolean funcTracePrintJavaOnly) { this.funcTracePrintJavaOnly = funcTracePrintJavaOnly; return this; }
        public Builder logNativeNDArrayCreation(Boolean logNativeNDArrayCreation) { this.logNativeNDArrayCreation = logNativeNDArrayCreation; return this; }
        public Builder logNDArrayEvents(Boolean logNDArrayEvents) { this.logNDArrayEvents = logNDArrayEvents; return this; }
        public Builder truncateNDArrayLogStrings(Boolean truncateNDArrayLogStrings) { this.truncateNDArrayLogStrings = truncateNDArrayLogStrings; return this; }
        public Builder numWorkspaceEventsToKeep(Integer numWorkspaceEventsToKeep) { this.numWorkspaceEventsToKeep = numWorkspaceEventsToKeep; return this; }
        public Builder checkInputChange(Boolean checkInputChange) { this.checkInputChange = checkInputChange; return this; }
        public Builder checkOutputChange(Boolean checkOutputChange) { this.checkOutputChange = checkOutputChange; return this; }
        public Builder trackWorkspaceOpenClose(Boolean trackWorkspaceOpenClose) { this.trackWorkspaceOpenClose = trackWorkspaceOpenClose; return this; }
        public Builder deleteShapeInfo(Boolean deleteShapeInfo) { this.deleteShapeInfo = deleteShapeInfo; return this; }
        public Builder deletePrimary(Boolean deletePrimary) { this.deletePrimary = deletePrimary; return this; }
        public Builder deleteSpecial(Boolean deleteSpecial) { this.deleteSpecial = deleteSpecial; return this; }
        public Builder variableTracingEnabled(Boolean variableTracingEnabled) { this.variableTracingEnabled = variableTracingEnabled; return this; }
        public Builder javacppLoggerDebug(Boolean javacppLoggerDebug) { this.javacppLoggerDebug = javacppLoggerDebug; return this; }
        public Builder javacppPathsFirst(Boolean javacppPathsFirst) { this.javacppPathsFirst = javacppPathsFirst; return this; }
        public Builder blasSerializationEnabled(Boolean blasSerializationEnabled) { this.blasSerializationEnabled = blasSerializationEnabled; return this; }
        public Builder openBlasThreads(Integer openBlasThreads) { this.openBlasThreads = openBlasThreads; return this; }
        public Builder ompNumThreads(Integer ompNumThreads) { this.ompNumThreads = ompNumThreads; return this; }
        // CUDA builder methods
        public Builder cudaCurrentDevice(Integer cudaCurrentDevice) { this.cudaCurrentDevice = cudaCurrentDevice; return this; }
        public Builder cudaMemoryPinned(Boolean cudaMemoryPinned) { this.cudaMemoryPinned = cudaMemoryPinned; return this; }
        public Builder cudaUseManagedMemory(Boolean cudaUseManagedMemory) { this.cudaUseManagedMemory = cudaUseManagedMemory; return this; }
        public Builder cudaMemoryPoolSize(Integer cudaMemoryPoolSize) { this.cudaMemoryPoolSize = cudaMemoryPoolSize; return this; }
        public Builder cudaForceP2P(Boolean cudaForceP2P) { this.cudaForceP2P = cudaForceP2P; return this; }
        public Builder cudaAllocatorEnabled(Boolean cudaAllocatorEnabled) { this.cudaAllocatorEnabled = cudaAllocatorEnabled; return this; }
        public Builder cudaMaxBlocks(Integer cudaMaxBlocks) { this.cudaMaxBlocks = cudaMaxBlocks; return this; }
        public Builder cudaMaxThreadsPerBlock(Integer cudaMaxThreadsPerBlock) { this.cudaMaxThreadsPerBlock = cudaMaxThreadsPerBlock; return this; }
        public Builder cudaAsyncExecution(Boolean cudaAsyncExecution) { this.cudaAsyncExecution = cudaAsyncExecution; return this; }
        public Builder cudaStreamLimit(Integer cudaStreamLimit) { this.cudaStreamLimit = cudaStreamLimit; return this; }
        public Builder cudaUseDeviceHost(Boolean cudaUseDeviceHost) { this.cudaUseDeviceHost = cudaUseDeviceHost; return this; }
        public Builder cudaEventLimit(Integer cudaEventLimit) { this.cudaEventLimit = cudaEventLimit; return this; }
        public Builder cudaCachingAllocatorLimit(Integer cudaCachingAllocatorLimit) { this.cudaCachingAllocatorLimit = cudaCachingAllocatorLimit; return this; }
        public Builder cudaUseUnifiedMemory(Boolean cudaUseUnifiedMemory) { this.cudaUseUnifiedMemory = cudaUseUnifiedMemory; return this; }
        public Builder cudaPrefetchSize(Integer cudaPrefetchSize) { this.cudaPrefetchSize = cudaPrefetchSize; return this; }
        public Builder cudaGraphOptimization(Boolean cudaGraphOptimization) { this.cudaGraphOptimization = cudaGraphOptimization; return this; }
        public Builder cudaTensorCoreEnabled(Boolean cudaTensorCoreEnabled) { this.cudaTensorCoreEnabled = cudaTensorCoreEnabled; return this; }
        public Builder cudaBlockingSync(Integer cudaBlockingSync) { this.cudaBlockingSync = cudaBlockingSync; return this; }
        public Builder cudaDeviceSchedule(Integer cudaDeviceSchedule) { this.cudaDeviceSchedule = cudaDeviceSchedule; return this; }
        public Builder cudaStackSize(Long cudaStackSize) { this.cudaStackSize = cudaStackSize; return this; }
        public Builder cudaMallocHeapSize(Long cudaMallocHeapSize) { this.cudaMallocHeapSize = cudaMallocHeapSize; return this; }
        public Builder cudaPrintfFifoSize(Long cudaPrintfFifoSize) { this.cudaPrintfFifoSize = cudaPrintfFifoSize; return this; }
        public Builder cudaDevRuntimeSyncDepth(Long cudaDevRuntimeSyncDepth) { this.cudaDevRuntimeSyncDepth = cudaDevRuntimeSyncDepth; return this; }
        public Builder cudaDevRuntimePendingLaunchCount(Long cudaDevRuntimePendingLaunchCount) { this.cudaDevRuntimePendingLaunchCount = cudaDevRuntimePendingLaunchCount; return this; }
        public Builder cudaMaxL2FetchGranularity(Long cudaMaxL2FetchGranularity) { this.cudaMaxL2FetchGranularity = cudaMaxL2FetchGranularity; return this; }
        public Builder cudaPersistingL2CacheSize(Long cudaPersistingL2CacheSize) { this.cudaPersistingL2CacheSize = cudaPersistingL2CacheSize; return this; }
        // Triton builder methods
        public Builder tritonBuildThreads(Integer tritonBuildThreads) { this.tritonBuildThreads = tritonBuildThreads; return this; }
        public Builder tritonCacheEnabled(Boolean tritonCacheEnabled) { this.tritonCacheEnabled = tritonCacheEnabled; return this; }
        public Builder tritonVerbose(Boolean tritonVerbose) { this.tritonVerbose = tritonVerbose; return this; }
        public Builder tritonAlwaysCompile(Boolean tritonAlwaysCompile) { this.tritonAlwaysCompile = tritonAlwaysCompile; return this; }
        public Builder tritonNumWarps(Integer tritonNumWarps) { this.tritonNumWarps = tritonNumWarps; return this; }
        public Builder tritonNumStages(Integer tritonNumStages) { this.tritonNumStages = tritonNumStages; return this; }
        public Builder tritonNumCTAs(Integer tritonNumCTAs) { this.tritonNumCTAs = tritonNumCTAs; return this; }
        public Builder tritonEnableFpFusion(Boolean tritonEnableFpFusion) { this.tritonEnableFpFusion = tritonEnableFpFusion; return this; }
        public Builder tritonCacheDir(String tritonCacheDir) { this.tritonCacheDir = tritonCacheDir; return this; }
        public Builder tritonDumpDir(String tritonDumpDir) { this.tritonDumpDir = tritonDumpDir; return this; }
        public Builder tritonOverrideArch(String tritonOverrideArch) { this.tritonOverrideArch = tritonOverrideArch; return this; }
        // DSP/optimizer builder methods
        public Builder optimizerEnabled(Boolean optimizerEnabled) { this.optimizerEnabled = optimizerEnabled; return this; }
        public Builder optimizerFp16(Boolean optimizerFp16) { this.optimizerFp16 = optimizerFp16; return this; }
        public Builder dspNoFreeze(Boolean dspNoFreeze) { this.dspNoFreeze = dspNoFreeze; return this; }
        public Builder dspNoNativeDecode(Boolean dspNoNativeDecode) { this.dspNoNativeDecode = dspNoNativeDecode; return this; }
        public Builder dspNoAttnOverride(Boolean dspNoAttnOverride) { this.dspNoAttnOverride = dspNoAttnOverride; return this; }
        public Builder dspNoDirect(Boolean dspNoDirect) { this.dspNoDirect = dspNoDirect; return this; }
        public Builder tritonSkipKernels(Boolean tritonSkipKernels) { this.tritonSkipKernels = tritonSkipKernels; return this; }
        public Builder tritonTf32(Boolean tritonTf32) { this.tritonTf32 = tritonTf32; return this; }
        public Builder cublasDisableWorkspace(Boolean cublasDisableWorkspace) { this.cublasDisableWorkspace = cublasDisableWorkspace; return this; }
        public Builder dspDiagnostics(String dspDiagnostics) { this.dspDiagnostics = dspDiagnostics; return this; }
        public Builder opTiming(Boolean opTiming) { this.opTiming = opTiming; return this; }
        // CUDA graph capture OOM retry builder methods
        public Builder dspCaptureOomMaxRetries(Integer dspCaptureOomMaxRetries) { this.dspCaptureOomMaxRetries = dspCaptureOomMaxRetries; return this; }
        public Builder dspCaptureOomRetryInterval(Integer dspCaptureOomRetryInterval) { this.dspCaptureOomRetryInterval = dspCaptureOomRetryInterval; return this; }
        public Builder dspCublasWorkspaceMb(Integer dspCublasWorkspaceMb) { this.dspCublasWorkspaceMb = dspCublasWorkspaceMb; return this; }
        public Builder dspGraphMetadataSafetyMb(Integer dspGraphMetadataSafetyMb) { this.dspGraphMetadataSafetyMb = dspGraphMetadataSafetyMb; return this; }
        public Builder dspProactiveEvictBeforeCapture(Boolean dspProactiveEvictBeforeCapture) { this.dspProactiveEvictBeforeCapture = dspProactiveEvictBeforeCapture; return this; }
        public Builder dspLruEviction(Boolean dspLruEviction) { this.dspLruEviction = dspLruEviction; return this; }
        public Builder dspCaptureWorkspaceMb(Integer dspCaptureWorkspaceMb) { this.dspCaptureWorkspaceMb = dspCaptureWorkspaceMb; return this; }

        public Nd4jEnvironmentConfig build() {
            return new Nd4jEnvironmentConfig(
                    maxThreads, maxMasterThreads, debug, verbose, profiling,
                    enableBlas, helpersAllowed, leaksDetector,
                    tadThreshold, elementwiseThreshold,
                    maxPrimaryMemory, maxSpecialMemory, maxDeviceMemory,
                    lifecycleTracking, trackViews, trackDeletions, snapshotFiles, trackOperations,
                    stackDepth, reportInterval, maxDeletionHistory,
                    ndArrayTracking, dataBufferTracking, tadCacheTracking, shapeCacheTracking, opContextTracking,
                    funcTracePrintAllocate, funcTracePrintDeallocate, funcTracePrintJavaOnly,
                    logNativeNDArrayCreation, logNDArrayEvents, truncateNDArrayLogStrings, numWorkspaceEventsToKeep,
                    checkInputChange, checkOutputChange,
                    trackWorkspaceOpenClose, deleteShapeInfo, deletePrimary, deleteSpecial, variableTracingEnabled,
                    javacppLoggerDebug, javacppPathsFirst,
                    blasSerializationEnabled, openBlasThreads, ompNumThreads,
                    // CUDA settings
                    cudaCurrentDevice, cudaMemoryPinned, cudaUseManagedMemory, cudaMemoryPoolSize, cudaForceP2P,
                    cudaAllocatorEnabled, cudaMaxBlocks, cudaMaxThreadsPerBlock, cudaAsyncExecution, cudaStreamLimit,
                    cudaUseDeviceHost, cudaEventLimit, cudaCachingAllocatorLimit, cudaUseUnifiedMemory, cudaPrefetchSize,
                    cudaGraphOptimization, cudaTensorCoreEnabled, cudaBlockingSync, cudaDeviceSchedule, cudaStackSize,
                    cudaMallocHeapSize, cudaPrintfFifoSize, cudaDevRuntimeSyncDepth, cudaDevRuntimePendingLaunchCount,
                    cudaMaxL2FetchGranularity, cudaPersistingL2CacheSize,
                    // Triton settings
                    tritonBuildThreads, tritonCacheEnabled, tritonVerbose, tritonAlwaysCompile,
                    tritonNumWarps, tritonNumStages, tritonNumCTAs, tritonEnableFpFusion,
                    tritonCacheDir, tritonDumpDir, tritonOverrideArch,
                    // DSP/optimizer settings
                    optimizerEnabled, optimizerFp16, dspNoFreeze, dspNoNativeDecode,
                    dspNoAttnOverride, dspNoDirect, tritonSkipKernels, tritonTf32,
                    cublasDisableWorkspace, dspDiagnostics, opTiming,
                    // CUDA graph capture OOM retry
                    dspCaptureOomMaxRetries, dspCaptureOomRetryInterval,
                    dspCublasWorkspaceMb, dspGraphMetadataSafetyMb,
                    dspProactiveEvictBeforeCapture, dspLruEviction, dspCaptureWorkspaceMb
            );
        }
    }
}
