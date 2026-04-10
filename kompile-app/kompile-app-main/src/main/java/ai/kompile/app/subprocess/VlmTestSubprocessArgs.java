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

package ai.kompile.app.subprocess;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Arguments passed to the VLM test subprocess via JSON file.
 * Contains configuration for running VLM-only processing (no chunking, embedding, or indexing).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VlmTestSubprocessArgs(
        String taskId,
        String filePath,
        String modelId,
        String outputFormat,            // DOCTAGS, MARKDOWN, JSON, TEXT
        int maxNewTokens,
        double temperature,
        double topP,
        int beamSize,
        boolean doSample,
        int pdfRenderDpi,
        int pageBatchSize,
        int cudaPinnedHostLimitMb,
        String callbackBaseUrl,
        String nd4jConfigJson,
        // KV cache configuration
        String kvCacheStrategy,         // STATIC, QUANTIZED, PAGED
        int maxKvLen,                   // 0 = auto (prefill + maxNewTokens)
        // ND4J optimizer / graph configuration
        Boolean optimizerEnabled,       // nd4j.optimizer.enabled (default true)
        Boolean optimizerFp16,          // nd4j.optimizer.fp16 (default true) - halves VRAM for weights
        Boolean clearDecoderCache,      // Delete cached .sdz/.opt.sdz for fresh optimizer pass
        // Triton / kernel configuration
        Boolean tritonEnabled,          // nd4j.triton.skipKernels=false means triton ON (default true)
        Boolean tritonTf32,             // nd4j.triton.tf32 (default false)
        // DSP (decode-side processing) flags
        Boolean dspNoNativeDecode,      // nd4j.dsp.noNativeDecodeInputs (default false)
        Boolean dspNoFreeze,            // nd4j.dsp.nofreeze (default false)
        Boolean dspNoAttnOverride,      // nd4j.dsp.noAttnOverride (default false)
        Boolean dspNoDirect,            // nd4j.dsp.noDirect (default false)
        // CUDA / performance flags
        Boolean noCublasWorkspace,      // nd4j.cublas.captureWorkspace=0 (default false)
        // CUDA graph capture OOM retry / memory management
        Integer dspCaptureOomMaxRetries,       // ND4J_DSP_CAPTURE_OOM_MAX_RETRIES
        Integer dspCaptureOomRetryInterval,    // ND4J_DSP_CAPTURE_OOM_RETRY_INTERVAL
        Integer dspCublasWorkspaceMb,          // ND4J_DSP_CUBLAS_WORKSPACE_MB
        Integer dspGraphMetadataSafetyMb,      // ND4J_DSP_GRAPH_METADATA_SAFETY_MB
        Boolean dspProactiveEvictBeforeCapture, // ND4J_DSP_PROACTIVE_EVICT
        Boolean dspLruEviction,                // ND4J_DSP_LRU_EVICTION
        Integer dspCaptureWorkspaceMb,         // ND4J_DSP_CAPTURE_WORKSPACE_MB
        // Speculative decoding
        int speculativeTokens,          // 0 = disabled
        // Debug flags
        Boolean debugDiagnostics,       // nd4j.dsp.diagnostics=ALL (default false)
        Boolean opTiming,               // nd4j.opTiming=true (default false)
        // Page limit
        int maxPages,                   // 0 = all pages (default)
        // Model source configuration
        String modelSourceType,
        String modelIdentifier,
        String stagingUrl,
        String stagingApiKey,
        String archivePath,
        // Memory watchdog thresholds
        int memoryThresholdPercent,
        int memoryCriticalPercent,
        int memoryKillThresholdPercent,
        long memoryCheckIntervalMs,
        // GPU memory thresholds
        int gpuMemoryThresholdPercent,
        int gpuMemoryCriticalPercent,
        int gpuMemoryKillThresholdPercent,
        // Off-heap (JavaCPP native) memory thresholds
        int offHeapThresholdPercent,
        int offHeapCriticalPercent,
        int offHeapKillThresholdPercent,
        Map<String, String> options
) {
    public static final int DEFAULT_MAX_NEW_TOKENS = 4096;
    public static final double DEFAULT_TEMPERATURE = 1.0;
    public static final double DEFAULT_TOP_P = 1.0;
    public static final int DEFAULT_BEAM_SIZE = 1;
    public static final int DEFAULT_PDF_RENDER_DPI = 300;
    public static final int DEFAULT_PAGE_BATCH_SIZE = 1;
    public static final int DEFAULT_CUDA_PINNED_HOST_LIMIT_MB = 0;
    // Memory watchdog defaults
    public static final int DEFAULT_MEMORY_THRESHOLD_PERCENT = 80;
    public static final int DEFAULT_MEMORY_CRITICAL_PERCENT = 90;
    public static final int DEFAULT_MEMORY_KILL_THRESHOLD_PERCENT = 95;
    public static final long DEFAULT_MEMORY_CHECK_INTERVAL_MS = 2000;
    // GPU threshold defaults (more conservative for VLM)
    public static final int DEFAULT_GPU_MEMORY_THRESHOLD_PERCENT = 75;
    public static final int DEFAULT_GPU_MEMORY_CRITICAL_PERCENT = 85;
    public static final int DEFAULT_GPU_MEMORY_KILL_THRESHOLD_PERCENT = 92;
    // Off-heap threshold defaults
    public static final int DEFAULT_OFF_HEAP_THRESHOLD_PERCENT = 80;
    public static final int DEFAULT_OFF_HEAP_CRITICAL_PERCENT = 90;
    public static final int DEFAULT_OFF_HEAP_KILL_THRESHOLD_PERCENT = 95;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public VlmTestSubprocessArgs {
        if (maxNewTokens <= 0) maxNewTokens = DEFAULT_MAX_NEW_TOKENS;
        if (temperature <= 0) temperature = DEFAULT_TEMPERATURE;
        if (topP <= 0) topP = DEFAULT_TOP_P;
        if (beamSize <= 0) beamSize = DEFAULT_BEAM_SIZE;
        if (pdfRenderDpi <= 0) pdfRenderDpi = DEFAULT_PDF_RENDER_DPI;
        if (pageBatchSize <= 0) pageBatchSize = DEFAULT_PAGE_BATCH_SIZE;
        if (cudaPinnedHostLimitMb < 0) cudaPinnedHostLimitMb = DEFAULT_CUDA_PINNED_HOST_LIMIT_MB;
        if (outputFormat == null || outputFormat.isBlank()) outputFormat = "DOCTAGS";
        if (kvCacheStrategy == null || kvCacheStrategy.isBlank()) kvCacheStrategy = "STATIC";
        if (maxKvLen < 0) maxKvLen = 0;
        // ND4J optimizer defaults - ON by default (critical for VRAM usage)
        if (optimizerEnabled == null) optimizerEnabled = true;
        if (optimizerFp16 == null) optimizerFp16 = true;
        if (clearDecoderCache == null) clearDecoderCache = false;
        if (tritonEnabled == null) tritonEnabled = true;
        if (tritonTf32 == null) tritonTf32 = false;
        if (dspNoNativeDecode == null) dspNoNativeDecode = false;
        if (dspNoFreeze == null) dspNoFreeze = false;
        if (dspNoAttnOverride == null) dspNoAttnOverride = false;
        if (dspNoDirect == null) dspNoDirect = false;
        if (noCublasWorkspace == null) noCublasWorkspace = false;
        if (speculativeTokens < 0) speculativeTokens = 0;
        if (debugDiagnostics == null) debugDiagnostics = false;
        if (opTiming == null) opTiming = false;
        if (maxPages < 0) maxPages = 0;
        // Memory watchdog defaults
        if (memoryThresholdPercent <= 0) memoryThresholdPercent = DEFAULT_MEMORY_THRESHOLD_PERCENT;
        if (memoryCriticalPercent <= 0) memoryCriticalPercent = DEFAULT_MEMORY_CRITICAL_PERCENT;
        if (memoryKillThresholdPercent < 0) memoryKillThresholdPercent = DEFAULT_MEMORY_KILL_THRESHOLD_PERCENT;
        if (memoryCheckIntervalMs <= 0) memoryCheckIntervalMs = DEFAULT_MEMORY_CHECK_INTERVAL_MS;
        // GPU memory threshold defaults
        if (gpuMemoryThresholdPercent <= 0) gpuMemoryThresholdPercent = DEFAULT_GPU_MEMORY_THRESHOLD_PERCENT;
        if (gpuMemoryCriticalPercent <= 0) gpuMemoryCriticalPercent = DEFAULT_GPU_MEMORY_CRITICAL_PERCENT;
        if (gpuMemoryKillThresholdPercent < 0) gpuMemoryKillThresholdPercent = DEFAULT_GPU_MEMORY_KILL_THRESHOLD_PERCENT;
        // Off-heap memory threshold defaults
        if (offHeapThresholdPercent <= 0) offHeapThresholdPercent = DEFAULT_OFF_HEAP_THRESHOLD_PERCENT;
        if (offHeapCriticalPercent <= 0) offHeapCriticalPercent = DEFAULT_OFF_HEAP_CRITICAL_PERCENT;
        if (offHeapKillThresholdPercent < 0) offHeapKillThresholdPercent = DEFAULT_OFF_HEAP_KILL_THRESHOLD_PERCENT;
    }

    public static VlmTestSubprocessArgs fromFile(Path path) throws IOException {
        return MAPPER.readValue(Files.readString(path), VlmTestSubprocessArgs.class);
    }

    public void toFile(Path path) throws IOException {
        Files.writeString(path, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(this));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String taskId;
        private String filePath;
        private String modelId;
        private String outputFormat = "DOCTAGS";
        private int maxNewTokens = DEFAULT_MAX_NEW_TOKENS;
        private double temperature = DEFAULT_TEMPERATURE;
        private double topP = DEFAULT_TOP_P;
        private int beamSize = DEFAULT_BEAM_SIZE;
        private boolean doSample = false;
        private int pdfRenderDpi = DEFAULT_PDF_RENDER_DPI;
        private int pageBatchSize = DEFAULT_PAGE_BATCH_SIZE;
        private int cudaPinnedHostLimitMb = DEFAULT_CUDA_PINNED_HOST_LIMIT_MB;
        private String callbackBaseUrl;
        private String nd4jConfigJson;
        private String kvCacheStrategy = "STATIC";
        private int maxKvLen = 0;
        // ND4J optimizer flags
        private Boolean optimizerEnabled = true;
        private Boolean optimizerFp16 = true;
        private Boolean clearDecoderCache = false;
        private Boolean tritonEnabled = true;
        private Boolean tritonTf32 = false;
        private Boolean dspNoNativeDecode = false;
        private Boolean dspNoFreeze = false;
        private Boolean dspNoAttnOverride = false;
        private Boolean dspNoDirect = false;
        private Boolean noCublasWorkspace = false;
        // CUDA graph capture OOM retry fields
        private Integer dspCaptureOomMaxRetries;
        private Integer dspCaptureOomRetryInterval;
        private Integer dspCublasWorkspaceMb;
        private Integer dspGraphMetadataSafetyMb;
        private Boolean dspProactiveEvictBeforeCapture;
        private Boolean dspLruEviction;
        private Integer dspCaptureWorkspaceMb;
        private int speculativeTokens = 0;
        private Boolean debugDiagnostics = false;
        private Boolean opTiming = false;
        private int maxPages = 0;
        private String modelSourceType;
        private String modelIdentifier;
        private String stagingUrl;
        private String stagingApiKey;
        private String archivePath;
        // Memory watchdog thresholds
        private int memoryThresholdPercent = DEFAULT_MEMORY_THRESHOLD_PERCENT;
        private int memoryCriticalPercent = DEFAULT_MEMORY_CRITICAL_PERCENT;
        private int memoryKillThresholdPercent = DEFAULT_MEMORY_KILL_THRESHOLD_PERCENT;
        private long memoryCheckIntervalMs = DEFAULT_MEMORY_CHECK_INTERVAL_MS;
        // GPU memory thresholds
        private int gpuMemoryThresholdPercent = DEFAULT_GPU_MEMORY_THRESHOLD_PERCENT;
        private int gpuMemoryCriticalPercent = DEFAULT_GPU_MEMORY_CRITICAL_PERCENT;
        private int gpuMemoryKillThresholdPercent = DEFAULT_GPU_MEMORY_KILL_THRESHOLD_PERCENT;
        // Off-heap memory thresholds
        private int offHeapThresholdPercent = DEFAULT_OFF_HEAP_THRESHOLD_PERCENT;
        private int offHeapCriticalPercent = DEFAULT_OFF_HEAP_CRITICAL_PERCENT;
        private int offHeapKillThresholdPercent = DEFAULT_OFF_HEAP_KILL_THRESHOLD_PERCENT;
        private Map<String, String> options = Map.of();

        public Builder taskId(String taskId) { this.taskId = taskId; return this; }
        public Builder filePath(String filePath) { this.filePath = filePath; return this; }
        public Builder modelId(String modelId) { this.modelId = modelId; return this; }
        public Builder outputFormat(String outputFormat) { this.outputFormat = outputFormat; return this; }
        public Builder maxNewTokens(int maxNewTokens) { this.maxNewTokens = maxNewTokens; return this; }
        public Builder temperature(double temperature) { this.temperature = temperature; return this; }
        public Builder topP(double topP) { this.topP = topP; return this; }
        public Builder beamSize(int beamSize) { this.beamSize = beamSize; return this; }
        public Builder doSample(boolean doSample) { this.doSample = doSample; return this; }
        public Builder pdfRenderDpi(int pdfRenderDpi) { this.pdfRenderDpi = pdfRenderDpi; return this; }
        public Builder pageBatchSize(int pageBatchSize) { this.pageBatchSize = pageBatchSize; return this; }
        public Builder cudaPinnedHostLimitMb(int cudaPinnedHostLimitMb) { this.cudaPinnedHostLimitMb = cudaPinnedHostLimitMb; return this; }
        public Builder callbackBaseUrl(String callbackBaseUrl) { this.callbackBaseUrl = callbackBaseUrl; return this; }
        public Builder nd4jConfigJson(String nd4jConfigJson) { this.nd4jConfigJson = nd4jConfigJson; return this; }
        public Builder kvCacheStrategy(String kvCacheStrategy) { this.kvCacheStrategy = kvCacheStrategy; return this; }
        public Builder maxKvLen(int maxKvLen) { this.maxKvLen = maxKvLen; return this; }
        public Builder optimizerEnabled(Boolean optimizerEnabled) { this.optimizerEnabled = optimizerEnabled; return this; }
        public Builder optimizerFp16(Boolean optimizerFp16) { this.optimizerFp16 = optimizerFp16; return this; }
        public Builder clearDecoderCache(Boolean clearDecoderCache) { this.clearDecoderCache = clearDecoderCache; return this; }
        public Builder tritonEnabled(Boolean tritonEnabled) { this.tritonEnabled = tritonEnabled; return this; }
        public Builder tritonTf32(Boolean tritonTf32) { this.tritonTf32 = tritonTf32; return this; }
        public Builder dspNoNativeDecode(Boolean dspNoNativeDecode) { this.dspNoNativeDecode = dspNoNativeDecode; return this; }
        public Builder dspNoFreeze(Boolean dspNoFreeze) { this.dspNoFreeze = dspNoFreeze; return this; }
        public Builder dspNoAttnOverride(Boolean dspNoAttnOverride) { this.dspNoAttnOverride = dspNoAttnOverride; return this; }
        public Builder dspNoDirect(Boolean dspNoDirect) { this.dspNoDirect = dspNoDirect; return this; }
        public Builder noCublasWorkspace(Boolean noCublasWorkspace) { this.noCublasWorkspace = noCublasWorkspace; return this; }
        // CUDA graph capture OOM retry builder methods
        public Builder dspCaptureOomMaxRetries(Integer dspCaptureOomMaxRetries) { this.dspCaptureOomMaxRetries = dspCaptureOomMaxRetries; return this; }
        public Builder dspCaptureOomRetryInterval(Integer dspCaptureOomRetryInterval) { this.dspCaptureOomRetryInterval = dspCaptureOomRetryInterval; return this; }
        public Builder dspCublasWorkspaceMb(Integer dspCublasWorkspaceMb) { this.dspCublasWorkspaceMb = dspCublasWorkspaceMb; return this; }
        public Builder dspGraphMetadataSafetyMb(Integer dspGraphMetadataSafetyMb) { this.dspGraphMetadataSafetyMb = dspGraphMetadataSafetyMb; return this; }
        public Builder dspProactiveEvictBeforeCapture(Boolean dspProactiveEvictBeforeCapture) { this.dspProactiveEvictBeforeCapture = dspProactiveEvictBeforeCapture; return this; }
        public Builder dspLruEviction(Boolean dspLruEviction) { this.dspLruEviction = dspLruEviction; return this; }
        public Builder dspCaptureWorkspaceMb(Integer dspCaptureWorkspaceMb) { this.dspCaptureWorkspaceMb = dspCaptureWorkspaceMb; return this; }
        public Builder speculativeTokens(int speculativeTokens) { this.speculativeTokens = speculativeTokens; return this; }
        public Builder debugDiagnostics(Boolean debugDiagnostics) { this.debugDiagnostics = debugDiagnostics; return this; }
        public Builder opTiming(Boolean opTiming) { this.opTiming = opTiming; return this; }
        public Builder maxPages(int maxPages) { this.maxPages = maxPages; return this; }
        public Builder modelSourceType(String modelSourceType) { this.modelSourceType = modelSourceType; return this; }
        public Builder modelIdentifier(String modelIdentifier) { this.modelIdentifier = modelIdentifier; return this; }
        public Builder stagingUrl(String stagingUrl) { this.stagingUrl = stagingUrl; return this; }
        public Builder stagingApiKey(String stagingApiKey) { this.stagingApiKey = stagingApiKey; return this; }
        public Builder archivePath(String archivePath) { this.archivePath = archivePath; return this; }
        public Builder options(Map<String, String> options) { this.options = options; return this; }
        
        // Memory threshold builders
        public Builder memoryThresholdPercent(int memoryThresholdPercent) { this.memoryThresholdPercent = memoryThresholdPercent; return this; }
        public Builder memoryCriticalPercent(int memoryCriticalPercent) { this.memoryCriticalPercent = memoryCriticalPercent; return this; }
        public Builder memoryKillThresholdPercent(int memoryKillThresholdPercent) { this.memoryKillThresholdPercent = memoryKillThresholdPercent; return this; }
        public Builder memoryCheckIntervalMs(long memoryCheckIntervalMs) { this.memoryCheckIntervalMs = memoryCheckIntervalMs; return this; }
        
        // GPU memory threshold builders
        public Builder gpuMemoryThresholdPercent(int gpuMemoryThresholdPercent) { this.gpuMemoryThresholdPercent = gpuMemoryThresholdPercent; return this; }
        public Builder gpuMemoryCriticalPercent(int gpuMemoryCriticalPercent) { this.gpuMemoryCriticalPercent = gpuMemoryCriticalPercent; return this; }
        public Builder gpuMemoryKillThresholdPercent(int gpuMemoryKillThresholdPercent) { this.gpuMemoryKillThresholdPercent = gpuMemoryKillThresholdPercent; return this; }

        // Off-heap memory threshold builders
        public Builder offHeapThresholdPercent(int offHeapThresholdPercent) { this.offHeapThresholdPercent = offHeapThresholdPercent; return this; }
        public Builder offHeapCriticalPercent(int offHeapCriticalPercent) { this.offHeapCriticalPercent = offHeapCriticalPercent; return this; }
        public Builder offHeapKillThresholdPercent(int offHeapKillThresholdPercent) { this.offHeapKillThresholdPercent = offHeapKillThresholdPercent; return this; }

        public VlmTestSubprocessArgs build() {
            return new VlmTestSubprocessArgs(
                    taskId, filePath, modelId, outputFormat, maxNewTokens,
                    temperature, topP, beamSize, doSample, pdfRenderDpi,
                    pageBatchSize, cudaPinnedHostLimitMb,
                    callbackBaseUrl, nd4jConfigJson,
                    kvCacheStrategy, maxKvLen,
                    optimizerEnabled, optimizerFp16, clearDecoderCache,
                    tritonEnabled, tritonTf32,
                    dspNoNativeDecode, dspNoFreeze, dspNoAttnOverride, dspNoDirect,
                    noCublasWorkspace,
                    dspCaptureOomMaxRetries, dspCaptureOomRetryInterval,
                    dspCublasWorkspaceMb, dspGraphMetadataSafetyMb,
                    dspProactiveEvictBeforeCapture, dspLruEviction, dspCaptureWorkspaceMb,
                    speculativeTokens,
                    debugDiagnostics, opTiming,
                    maxPages,
                    modelSourceType, modelIdentifier, stagingUrl, stagingApiKey, archivePath,
                    memoryThresholdPercent, memoryCriticalPercent, memoryKillThresholdPercent, memoryCheckIntervalMs,
                    gpuMemoryThresholdPercent, gpuMemoryCriticalPercent, gpuMemoryKillThresholdPercent,
                    offHeapThresholdPercent, offHeapCriticalPercent, offHeapKillThresholdPercent,
                    options
            );
        }
    }
}
