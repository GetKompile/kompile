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

import ai.kompile.app.config.Nd4jEnvironmentConfig;
import ai.kompile.ocr.document.ParsedDocument;
import ai.kompile.ocr.OcrPipelineConfig;
import ai.kompile.ocr.integration.OcrPipelineService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.nd4j.common.config.ND4JSystemProperties;
import org.nd4j.imports.converters.DifferentialFunctionClassHolder;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.factory.Nd4jBackend;
import org.nd4j.nativeblas.NativeOps;
import org.nd4j.nativeblas.NativeOpsHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Subprocess entry point for VLM-only testing.
 *
 * Runs VLM processing on a document without chunking, embedding, or indexing.
 * Reports per-page results and performance metrics via INGEST_MSG: protocol.
 *
 * Usage:
 *   java -cp <classpath> ai.kompile.app.subprocess.VlmTestSubprocessMain <args-file.json>
 */
public class VlmTestSubprocessMain {

    private static final Logger logger = LoggerFactory.getLogger(VlmTestSubprocessMain.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static PrintStream originalStdout;
    private static volatile VlmTestSubprocessArgs currentArgs;
    private static volatile SubprocessMemoryWatchdog memoryWatchdog;

    public static VlmTestSubprocessArgs getCurrentArgs() {
        return currentArgs;
    }

    public static SubprocessMemoryWatchdog getMemoryWatchdog() {
        return memoryWatchdog;
    }

    public static void main(String[] args) {
        originalStdout = System.out;
        System.setOut(System.err);

        int exitCode = 0;
        VlmTestSubprocessArgs vlmArgs = null;
        SubprocessProgressReporter reporter = null;

        try {
            if (args.length < 1) {
                System.err.println("Usage: VlmTestSubprocessMain <args-file.json>");
                System.exit(1);
            }

            Path argsFile = Paths.get(args[0]);
            if (!Files.exists(argsFile)) {
                System.err.println("Args file not found: " + argsFile);
                System.exit(1);
            }

            vlmArgs = VlmTestSubprocessArgs.fromFile(argsFile);
            currentArgs = vlmArgs;
            logger.info("Loaded VLM test args for task: {}", vlmArgs.taskId());

            // Initialize and start memory watchdog with GPU thresholds
            memoryWatchdog = new SubprocessMemoryWatchdog(
                    vlmArgs.memoryThresholdPercent(),
                    vlmArgs.memoryCriticalPercent(),
                    vlmArgs.memoryKillThresholdPercent(),
                    vlmArgs.memoryCheckIntervalMs(),
                    vlmArgs.gpuMemoryThresholdPercent(),
                    vlmArgs.gpuMemoryCriticalPercent(),
                    vlmArgs.gpuMemoryKillThresholdPercent()
            );
            memoryWatchdog.start();
            logger.info("Memory watchdog started: heap stop={}%, critical={}%, kill={}% GPU stop={}%, critical={}%, kill={}",
                    vlmArgs.memoryThresholdPercent(),
                    vlmArgs.memoryCriticalPercent(),
                    vlmArgs.memoryKillThresholdPercent(),
                    vlmArgs.gpuMemoryThresholdPercent(),
                    vlmArgs.gpuMemoryCriticalPercent(),
                    vlmArgs.gpuMemoryKillThresholdPercent());

            reporter = new SubprocessProgressReporter(vlmArgs.taskId(), originalStdout);
            reporter.startHeartbeat();

            // Initialize ND4J
            reporter.reportProgress("INIT", 5, "ND4J", "Initializing ND4J environment");
            logger.info("Initializing ND4J environment...");
            initializeNd4j(vlmArgs);

            // Record start heap
            long startHeapUsed = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

            // Create minimal Spring context with OCR beans
            reporter.reportProgress("INIT", 10, "Spring", "Creating application context");
            logger.info("Creating Spring context...");

            long modelLoadStart = System.currentTimeMillis();
            try (AnnotationConfigApplicationContext context = createContext(vlmArgs)) {
                OcrPipelineService ocrService = context.getBean(OcrPipelineService.class);

                // Load VLM models only - skip traditional OCR (dbnet/crnn) which aren't needed
                reporter.reportProgress("INIT", 15, "Models", "Loading VLM models");
                ocrService.initializeVlmOnly();
                long modelLoadTime = System.currentTimeMillis() - modelLoadStart;

                reporter.reportProgress("INIT", 20, "Ready", "VLM pipeline ready");

                // Process file
                File inputFile = new File(vlmArgs.filePath());
                if (!inputFile.exists()) {
                    reporter.reportFailed("INIT", "Input file not found: " + vlmArgs.filePath(),
                            "FileNotFoundException", null);
                    System.exit(1);
                }

                String modelId = vlmArgs.modelId();
                OcrPipelineConfig.VlmOutputFormat format;
                try {
                    format = OcrPipelineConfig.VlmOutputFormat.valueOf(vlmArgs.outputFormat());
                } catch (IllegalArgumentException e) {
                    format = OcrPipelineConfig.VlmOutputFormat.DOCTAGS;
                }

                // Track per-page results
                List<Map<String, Object>> pageResults = new ArrayList<>();
                int totalTokens = 0;
                long totalProcessingTime = 0;
                long pipelineStart = System.currentTimeMillis();

                // Use progress callback to get per-page info
                final OcrPipelineConfig.VlmOutputFormat finalFormat = format;
                final SubprocessProgressReporter finalReporter = reporter;
                final int[] pageCount = {0};

                reporter.reportProgress("VLM_PROCESSING", 25, "Processing", "Starting VLM processing");

                // Build full OcrPipelineConfig from subprocess args
                OcrPipelineConfig pipelineConfig = OcrPipelineConfig.builder()
                        .useVlm(true)
                        .vlmModelId(modelId)
                        .vlmOutputFormat(finalFormat)
                        .maxNewTokens(vlmArgs.maxNewTokens())
                        .temperature(vlmArgs.temperature())
                        .topP(vlmArgs.topP())
                        .beamSize(vlmArgs.beamSize())
                        .doSample(vlmArgs.doSample())
                        .pdfRenderDpi(vlmArgs.pdfRenderDpi())
                        .pageBatchSize(vlmArgs.pageBatchSize())
                        .kvCacheStrategy(vlmArgs.kvCacheStrategy())
                        .maxKvLen(vlmArgs.maxKvLen())
                        .maxPages(vlmArgs.maxPages())
                        .sourceId(vlmArgs.filePath())
                        .includeAuditTrail(true)
                        .build();

                List<ParsedDocument> results = ocrService.processPdfWithVlm(
                        inputFile, pipelineConfig,
                        progress -> {
                            int pct = 25 + (int) (progress.overallProgress() * 0.7);
                            pct = Math.min(pct, 95);

                            // Report per-page completion with token metrics
                            if ("Page completed".equals(progress.currentStage())) {
                                pageCount[0]++;
                                Map<String, Object> pageStats = new HashMap<>();
                                pageStats.put("pageNumber", progress.currentPage());
                                pageStats.put("totalPages", progress.totalPages());
                                if (progress.generatedTokens() != null) {
                                    pageStats.put("generatedTokens", progress.generatedTokens());
                                }
                                if (progress.tokensPerSecond() != null) {
                                    pageStats.put("tokensPerSecond", progress.tokensPerSecond());
                                }
                                if (progress.generateTimeMs() != null) {
                                    pageStats.put("processingTimeMs", progress.generateTimeMs());
                                }

                                // Send progress with page stats encoded in message
                                try {
                                    String statsJson = OBJECT_MAPPER.writeValueAsString(pageStats);
                                    finalReporter.reportProgressImmediate("VLM_PROCESSING", pct,
                                            "Page " + progress.currentPage() + "/" + progress.totalPages(),
                                            "PAGE_RESULT:" + statsJson);
                                } catch (Exception e) {
                                    logger.warn("Failed to serialize page stats", e);
                                }
                            } else {
                                finalReporter.reportProgress("VLM_PROCESSING", pct,
                                        progress.currentStage(), progress.statusMessage());
                            }
                        });

                long pipelineEnd = System.currentTimeMillis();
                totalProcessingTime = pipelineEnd - pipelineStart;

                // Build per-page result list
                for (ParsedDocument doc : results) {
                    Map<String, Object> pageResult = new LinkedHashMap<>();
                    pageResult.put("pageNumber", doc.getPageNumber());
                    pageResult.put("text", doc.getText());
                    pageResult.put("success", doc.isSuccess());
                    pageResult.put("processingTimeMs", doc.getProcessingTimeMs());
                    if (!doc.isSuccess()) {
                        pageResult.put("error", doc.getErrorMessage());
                    }
                    // Token info from metadata
                    if (doc.getMetadata() != null) {
                        if (doc.getMetadata().containsKey("generatedTokens")) {
                            int tokens = ((Number) doc.getMetadata().get("generatedTokens")).intValue();
                            pageResult.put("generatedTokens", tokens);
                            totalTokens += tokens;
                        }
                        if (doc.getMetadata().containsKey("tokensPerSecond")) {
                            pageResult.put("tokensPerSecond", doc.getMetadata().get("tokensPerSecond"));
                        }
                        if (doc.getMetadata().containsKey("rawDocTags")) {
                            pageResult.put("rawDocTags", doc.getMetadata().get("rawDocTags"));
                        }
                    }
                    pageResults.add(pageResult);
                }

                // Check for page failures
                long failedPages = pageResults.stream()
                        .filter(p -> Boolean.FALSE.equals(p.get("success")))
                        .count();
                boolean allFailed = failedPages == results.size() && !results.isEmpty();

                long endHeapUsed = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                long peakMemory = Runtime.getRuntime().totalMemory();

                // Build performance summary
                Map<String, Long> phaseDurations = new LinkedHashMap<>();
                phaseDurations.put("MODEL_LOAD", modelLoadTime);
                phaseDurations.put("VLM_PROCESSING", totalProcessingTime);

                // Report completion with full results
                Map<String, Object> completionData = new LinkedHashMap<>();
                completionData.put("pages", pageResults);
                completionData.put("totalPages", results.size());
                completionData.put("totalGeneratedTokens", totalTokens);
                completionData.put("totalProcessingTimeMs", totalProcessingTime);
                completionData.put("modelLoadTimeMs", modelLoadTime);
                completionData.put("startHeapUsedBytes", startHeapUsed);
                completionData.put("endHeapUsedBytes", endHeapUsed);
                completionData.put("peakMemoryBytes", peakMemory);
                completionData.put("phaseDurations", phaseDurations);
                if (totalProcessingTime > 0 && totalTokens > 0) {
                    completionData.put("avgTokensPerSecond",
                            (double) totalTokens / (totalProcessingTime / 1000.0));
                }

                if (allFailed) {
                    // All pages failed - report as failure
                    String firstError = pageResults.stream()
                            .filter(p -> p.containsKey("error"))
                            .map(p -> (String) p.get("error"))
                            .findFirst().orElse("All pages failed");
                    completionData.put("allPagesFailed", true);
                    String resultsJson = OBJECT_MAPPER.writeValueAsString(completionData);
                    reporter.reportProgressImmediate("FAILED", 100, "Failed",
                            "VLM_RESULTS:" + resultsJson);
                    reporter.reportFailed("VLM_PROCESSING",
                            failedPages + "/" + results.size() + " pages failed: " + firstError,
                            "PageProcessingError", null);
                    logger.error("VLM test failed: all {} pages had errors", results.size());
                    exitCode = 1;
                } else {
                    if (failedPages > 0) {
                        completionData.put("partialFailure", true);
                        completionData.put("failedPages", failedPages);
                    }

                    // Send results as a special completion message
                    String resultsJson = OBJECT_MAPPER.writeValueAsString(completionData);
                    reporter.reportProgressImmediate("COMPLETED", 100, "Done",
                            "VLM_RESULTS:" + resultsJson);

                    reporter.reportCompleted(
                            1, // documentsLoaded
                            0, // chunksCreated (no chunking)
                            0, // chunksEmbedded (no embedding)
                            0, // documentsIndexed (no indexing)
                            totalTokens,
                            0,
                            vlmArgs.filePath(),
                            phaseDurations);

                    logger.info("VLM test completed: {} pages ({} failed), {} tokens in {}ms",
                            results.size(), failedPages, totalTokens, totalProcessingTime);
                }
            }

        } catch (InterruptedException e) {
            logger.info("VLM test subprocess interrupted (likely cancelled)");
            if (reporter != null) {
                reporter.reportFailed("VLM_PROCESSING", "Process interrupted", "InterruptedException", null);
            }
            Thread.currentThread().interrupt();
            exitCode = 130;

        } catch (OutOfMemoryError oom) {
            logger.error("VLM test subprocess OOM", oom);
            if (reporter != null) {
                reporter.reportFailed("VLM_PROCESSING", "OutOfMemoryError", "OutOfMemoryError", null);
            }
            exitCode = 137;

        } catch (Exception e) {
            logger.error("VLM test subprocess failed", e);
            if (reporter != null) {
                reporter.reportFailed("VLM_PROCESSING", e.getMessage(),
                        e.getClass().getSimpleName(), getStackTrace(e));
            }
            exitCode = 1;

        } finally {
            // Stop memory watchdog first
            if (memoryWatchdog != null) {
                memoryWatchdog.close();
                memoryWatchdog = null;
            }
            if (reporter != null) {
                reporter.stopHeartbeat();
                reporter.close();
            }
            cleanupNd4j();
        }

        System.exit(exitCode);
    }

    private static void initializeNd4j(VlmTestSubprocessArgs vlmArgs) throws Exception {
        logger.info("Initializing ND4J backend and environment...");

        DifferentialFunctionClassHolder.initInstance();

        Nd4jBackend backend = Nd4jBackend.load();
        Nd4j.backend = backend;
        logger.info("Loaded ND4J backend: {}", backend.getClass().getSimpleName());

        NativeOps nativeOps = NativeOpsHolder.getInstance().getDeviceNativeOps();
        nativeOps.initializeDevicesAndFunctions();

        // Apply full ND4J environment config from JSON (captured from parent process)
        // This includes thread settings, CUDA config, memory limits, AND DSP/optimizer flags
        Nd4jEnvironmentConfig config = null;
        String nd4jConfigJson = vlmArgs.nd4jConfigJson();
        if (nd4jConfigJson != null && !nd4jConfigJson.isBlank()) {
            try {
                config = OBJECT_MAPPER.readValue(nd4jConfigJson, Nd4jEnvironmentConfig.class);
                applyNd4jEnvironmentConfig(config);
            } catch (Exception e) {
                logger.warn("Failed to parse ND4J config JSON, using defaults: {}", e.getMessage());
                config = Nd4jEnvironmentConfig.defaults();
                applyNd4jEnvironmentConfig(config);
            }
        } else {
            config = Nd4jEnvironmentConfig.defaults();
            applyNd4jEnvironmentConfig(config);
        }

        // Apply optimal LLM configuration (Triton, CUDA graph capture, cuBLAS TF32, batched GEMM, etc.)
        // This is the single source of truth for ~90 tok/s inference performance.
        // Must be called AFTER Nd4j.backend and nativeOps are initialized but BEFORE model loading.
        if (NativeOpsHolder.getInstance().getDeviceNativeOps().isTritonAvailable()) {
            logger.info("Triton available — applying optimal LLM config (graph capture, fusion, TF32)");
            Nd4j.getEnvironment().applyOptimalLLMConfig();
            Nd4j.getEnvironment().setTritonTf32Enabled(true);
            // Set graph execution mode to TRITON so auto-compiled DSP plans use Triton kernels
            setPropertyIfAbsent(ND4JSystemProperties.DSP_GRAPH_EXECUTION_MODE, "TRITON");
        } else {
            logger.info("Triton not available — applying basic LLM config (cuBLAS TF32 + batched GEMM)");
            Nd4j.getEnvironment().applyBasicLLMConfig();
        }

        // Enable FP16 weight pre-casting (halves weight memory bandwidth, ~2x decoder speedup)
        // This is applied at model import time by GraphOptimizer/QuantizationOptimizations
        setPropertyIfAbsent(ND4JSystemProperties.OPTIMIZER_ENABLED, "true");
        setPropertyIfAbsent(ND4JSystemProperties.OPTIMIZER_FP16, "true");

        // Apply DSP/optimizer system properties from the nd4j config
        // These are read by SameDiff/StaticKvCacheDecodeLoop at runtime via System.getProperty()
        applyDspSystemProperties(config);

        // Per-test overrides from VlmTestSubprocessArgs take precedence over nd4j config
        applyPerTestOverrides(vlmArgs);

        logger.info("ND4J config: optimizer={}, fp16={}, graphExecMode={}, tritonSkip={}, tritonTf32={}, dspNoNativeDecode={}, " +
                        "dspNoFreeze={}, dspNoAttnOverride={}, dspNoDirect={}, cublasWorkspace={}, " +
                        "speculativeTokens={}, diagnostics={}, opTiming={}",
                System.getProperty(ND4JSystemProperties.OPTIMIZER_ENABLED, "unset"),
                System.getProperty(ND4JSystemProperties.OPTIMIZER_FP16, "unset"),
                System.getProperty(ND4JSystemProperties.DSP_GRAPH_EXECUTION_MODE, "unset"),
                System.getProperty(ND4JSystemProperties.TRITON_SKIP_KERNELS, "unset"),
                System.getProperty(ND4JSystemProperties.TRITON_TF32, "unset"),
                System.getProperty(ND4JSystemProperties.DSP_NO_NATIVE_DECODE_INPUTS, "unset"),
                System.getProperty(ND4JSystemProperties.DSP_NO_FREEZE, "unset"),
                System.getProperty(ND4JSystemProperties.DSP_NO_ATTN_OVERRIDE, "unset"),
                System.getProperty(ND4JSystemProperties.DSP_NO_DIRECT, "unset"),
                System.getProperty(ND4JSystemProperties.CUBLAS_CAPTURE_WORKSPACE, "unset"),
                vlmArgs.speculativeTokens(),
                System.getProperty(ND4JSystemProperties.DSP_DIAGNOSTICS, "unset"),
                System.getProperty(ND4JSystemProperties.OP_TIMING, "unset"));

        // Handle clearDecoderCache - delete cached .sdz files to force fresh optimizer pass
        if (Boolean.TRUE.equals(vlmArgs.clearDecoderCache())) {
            clearCachedModels();
        }

        // Clear stale .opt.sdz files if FP16 optimizer is now enabled but cached models
        // were previously optimized without FP16 (timestamp-based cache won't detect this)
        if ("true".equalsIgnoreCase(System.getProperty(ND4JSystemProperties.OPTIMIZER_FP16, "false"))) {
            clearStaleOptimizedModels();
        }

        logger.info("ND4J initialized: backend={}", Nd4j.getBackend().getClass().getSimpleName());
    }

    /**
     * Apply DSP/optimizer system properties from Nd4jEnvironmentConfig.
     * These are read by SameDiff at runtime via System.getProperty().
     */
    private static void applyDspSystemProperties(Nd4jEnvironmentConfig config) {
        if (config.optimizerEnabled() != null) {
            setPropertyIfAbsent(ND4JSystemProperties.OPTIMIZER_ENABLED, String.valueOf(config.optimizerEnabled()));
        }
        if (config.optimizerFp16() != null) {
            setPropertyIfAbsent(ND4JSystemProperties.OPTIMIZER_FP16, String.valueOf(config.optimizerFp16()));
        }
        if (Boolean.TRUE.equals(config.dspNoFreeze())) {
            setPropertyIfAbsent(ND4JSystemProperties.DSP_NO_FREEZE, "true");
        }
        if (Boolean.TRUE.equals(config.dspNoNativeDecode())) {
            setPropertyIfAbsent(ND4JSystemProperties.DSP_NO_NATIVE_DECODE_INPUTS, "true");
        }
        if (Boolean.TRUE.equals(config.dspNoAttnOverride())) {
            setPropertyIfAbsent(ND4JSystemProperties.DSP_NO_ATTN_OVERRIDE, "true");
        }
        if (Boolean.TRUE.equals(config.dspNoDirect())) {
            setPropertyIfAbsent(ND4JSystemProperties.DSP_NO_DIRECT, "true");
        }
        if (Boolean.TRUE.equals(config.tritonSkipKernels())) {
            setPropertyIfAbsent(ND4JSystemProperties.TRITON_SKIP_KERNELS, "true");
        }
        if (Boolean.TRUE.equals(config.tritonTf32())) {
            setPropertyIfAbsent(ND4JSystemProperties.TRITON_TF32, "true");
        }
        if (Boolean.TRUE.equals(config.cublasDisableWorkspace())) {
            setPropertyIfAbsent(ND4JSystemProperties.CUBLAS_CAPTURE_WORKSPACE, "0");
        }
        if (config.dspDiagnostics() != null && !config.dspDiagnostics().isBlank()) {
            setPropertyIfAbsent(ND4JSystemProperties.DSP_DIAGNOSTICS, config.dspDiagnostics());
        }
        if (Boolean.TRUE.equals(config.opTiming())) {
            setPropertyIfAbsent(ND4JSystemProperties.OP_TIMING, "true");
        }
    }

    /**
     * Apply per-test overrides from VlmTestSubprocessArgs.
     * These take precedence over the nd4j environment config.
     */
    private static void applyPerTestOverrides(VlmTestSubprocessArgs vlmArgs) {
        // Per-test args override the nd4j config (force set, not ifAbsent)
        if (vlmArgs.optimizerEnabled() != null) {
            System.setProperty(ND4JSystemProperties.OPTIMIZER_ENABLED, String.valueOf(vlmArgs.optimizerEnabled()));
        }
        if (vlmArgs.optimizerFp16() != null) {
            System.setProperty(ND4JSystemProperties.OPTIMIZER_FP16, String.valueOf(vlmArgs.optimizerFp16()));
        }
        if (Boolean.FALSE.equals(vlmArgs.tritonEnabled())) {
            System.setProperty(ND4JSystemProperties.TRITON_SKIP_KERNELS, "true");
        }
        if (Boolean.TRUE.equals(vlmArgs.tritonTf32())) {
            System.setProperty(ND4JSystemProperties.TRITON_TF32, "true");
        }
        if (Boolean.TRUE.equals(vlmArgs.dspNoNativeDecode())) {
            System.setProperty(ND4JSystemProperties.DSP_NO_NATIVE_DECODE_INPUTS, "true");
        }
        if (Boolean.TRUE.equals(vlmArgs.dspNoFreeze())) {
            System.setProperty(ND4JSystemProperties.DSP_NO_FREEZE, "true");
        }
        if (Boolean.TRUE.equals(vlmArgs.dspNoAttnOverride())) {
            System.setProperty(ND4JSystemProperties.DSP_NO_ATTN_OVERRIDE, "true");
        }
        if (Boolean.TRUE.equals(vlmArgs.dspNoDirect())) {
            System.setProperty(ND4JSystemProperties.DSP_NO_DIRECT, "true");
        }
        if (Boolean.TRUE.equals(vlmArgs.noCublasWorkspace())) {
            System.setProperty(ND4JSystemProperties.CUBLAS_CAPTURE_WORKSPACE, "0");
        }
        if (Boolean.TRUE.equals(vlmArgs.debugDiagnostics())) {
            System.setProperty(ND4JSystemProperties.DSP_DIAGNOSTICS, "ALL");
        }
        if (Boolean.TRUE.equals(vlmArgs.opTiming())) {
            System.setProperty(ND4JSystemProperties.OP_TIMING, "true");
        }
    }

    /**
     * Apply ND4J environment configuration.
     * This mirrors MainApplication.applyNd4jEnvironmentConfig() exactly.
     */
    private static void applyNd4jEnvironmentConfig(Nd4jEnvironmentConfig config) {
        if (config == null) {
            config = Nd4jEnvironmentConfig.defaults();
        }
        logger.info("Applying ND4J environment configuration (VLM subprocess)...");

        try {
            if (config.enableBlas() != null) Nd4j.getEnvironment().setEnableBlas(config.enableBlas());
            if (config.helpersAllowed() != null) Nd4j.getEnvironment().allowHelpers(config.helpersAllowed());
            if (config.maxThreads() != null) Nd4j.getEnvironment().setMaxThreads(config.maxThreads());
            if (config.maxMasterThreads() != null) Nd4j.getEnvironment().setMaxMasterThreads(config.maxMasterThreads());
            if (config.debug() != null) Nd4j.getEnvironment().setDebug(config.debug());
            if (config.verbose() != null) Nd4j.getEnvironment().setVerbose(config.verbose());
            if (config.profiling() != null) Nd4j.getEnvironment().setProfiling(config.profiling());
            if (config.leaksDetector() != null) Nd4j.getEnvironment().setLeaksDetector(config.leaksDetector());
            if (config.tadThreshold() != null) Nd4j.getEnvironment().setTadThreshold(config.tadThreshold());
            if (config.elementwiseThreshold() != null) Nd4j.getEnvironment().setElementwiseThreshold(config.elementwiseThreshold());
            if (config.maxPrimaryMemory() != null && config.maxPrimaryMemory() > 0) Nd4j.getEnvironment().setMaxPrimaryMemory(config.maxPrimaryMemory());
            if (config.maxSpecialMemory() != null && config.maxSpecialMemory() > 0) Nd4j.getEnvironment().setMaxSpecialMemory(config.maxSpecialMemory());
            if (config.maxDeviceMemory() != null && config.maxDeviceMemory() > 0) Nd4j.getEnvironment().setMaxDeviceMemory(config.maxDeviceMemory());
            if (config.lifecycleTracking() != null) Nd4j.getEnvironment().setLifecycleTracking(config.lifecycleTracking());
            if (config.trackViews() != null) Nd4j.getEnvironment().setTrackViews(config.trackViews());
            if (config.trackDeletions() != null) Nd4j.getEnvironment().setTrackDeletions(config.trackDeletions());
            if (config.snapshotFiles() != null) Nd4j.getEnvironment().setSnapshotFiles(config.snapshotFiles());
            if (config.trackOperations() != null) Nd4j.getEnvironment().setTrackOperations(config.trackOperations());
            if (config.stackDepth() != null) Nd4j.getEnvironment().setStackDepth(config.stackDepth());
            if (config.reportInterval() != null) Nd4j.getEnvironment().setReportInterval(config.reportInterval());
            if (config.maxDeletionHistory() != null) Nd4j.getEnvironment().setMaxDeletionHistory(config.maxDeletionHistory());
            if (config.ndArrayTracking() != null) Nd4j.getEnvironment().setNDArrayTracking(config.ndArrayTracking());
            if (config.dataBufferTracking() != null) Nd4j.getEnvironment().setDataBufferTracking(config.dataBufferTracking());
            if (config.tadCacheTracking() != null) Nd4j.getEnvironment().setTADCacheTracking(config.tadCacheTracking());
            if (config.shapeCacheTracking() != null) Nd4j.getEnvironment().setShapeCacheTracking(config.shapeCacheTracking());
            if (config.opContextTracking() != null) Nd4j.getEnvironment().setOpContextTracking(config.opContextTracking());
            if (config.funcTracePrintAllocate() != null) Nd4j.getEnvironment().setFuncTraceForAllocate(config.funcTracePrintAllocate());
            if (config.funcTracePrintDeallocate() != null) Nd4j.getEnvironment().setFuncTraceForDeallocate(config.funcTracePrintDeallocate());
            if (config.funcTracePrintJavaOnly() != null) Nd4j.getEnvironment().setFuncTracePrintJavaOnly(config.funcTracePrintJavaOnly());
            if (config.logNativeNDArrayCreation() != null) Nd4j.getEnvironment().setLogNativeNDArrayCreation(config.logNativeNDArrayCreation());
            if (config.logNDArrayEvents() != null) Nd4j.getEnvironment().setLogNDArrayEvents(config.logNDArrayEvents());
            if (config.checkInputChange() != null) Nd4j.getEnvironment().setCheckInputChange(config.checkInputChange());
            if (config.checkOutputChange() != null) Nd4j.getEnvironment().setCheckOutputChange(config.checkOutputChange());
            if (config.trackWorkspaceOpenClose() != null) Nd4j.getEnvironment().setTrackWorkspaceOpenClose(config.trackWorkspaceOpenClose());
            if (config.deleteShapeInfo() != null) Nd4j.getEnvironment().setDeleteShapeInfo(config.deleteShapeInfo());
            if (config.deletePrimary() != null) Nd4j.getEnvironment().setDeletePrimary(config.deletePrimary());
            if (config.deleteSpecial() != null) Nd4j.getEnvironment().setDeleteSpecial(config.deleteSpecial());
            if (config.variableTracingEnabled() != null) Nd4j.getEnvironment().setVariableTracingEnabled(config.variableTracingEnabled());
            if (config.javacppLoggerDebug() != null) System.setProperty("org.bytedeco.javacpp.logger.debug", config.javacppLoggerDebug().toString());
            if (config.javacppPathsFirst() != null) System.setProperty("org.bytedeco.javacpp.pathsFirst", config.javacppPathsFirst().toString());
            if (config.ompNumThreads() != null && config.ompNumThreads() > 0) System.setProperty("OMP_NUM_THREADS", String.valueOf(config.ompNumThreads()));

            logger.info("ND4J environment applied: enableBlas={}, helpersAllowed={}, maxThreads={}, maxMasterThreads={}, lifecycleTracking={}",
                    Nd4j.getEnvironment().isEnableBlas(), Nd4j.getEnvironment().helpersAllowed(),
                    Nd4j.getEnvironment().maxThreads(), Nd4j.getEnvironment().maxMasterThreads(),
                    Nd4j.getEnvironment().isLifecycleTracking());
        } catch (Exception e) {
            logger.error("Error applying ND4J environment configuration: {}", e.getMessage(), e);
        }
    }

    private static void setPropertyIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }

    private static void clearCachedModels() {
        // Clear .sdz and .opt.sdz cached files from ~/.kompile/models/
        String userHome = System.getProperty("user.home");
        if (userHome == null) return;
        Path modelsDir = Paths.get(userHome, ".kompile", "models");
        if (!Files.exists(modelsDir)) return;
        try (var stream = Files.walk(modelsDir)) {
            stream.filter(p -> {
                String name = p.getFileName().toString();
                return name.endsWith(".sdz") || name.endsWith(".opt.sdz");
            }).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                    logger.info("Cleared cached model: {}", p);
                } catch (Exception e) {
                    logger.warn("Failed to delete cached model {}: {}", p, e.getMessage());
                }
            });
        } catch (Exception e) {
            logger.warn("Failed to clear cached models: {}", e.getMessage());
        }
    }

    /**
     * Clear only .opt.sdz files (optimized/compiled models) to force re-optimization
     * with current settings (e.g., FP16 pre-casting). Leaves original .sdz files intact.
     */
    private static void clearStaleOptimizedModels() {
        String userHome = System.getProperty("user.home");
        Path modelsDir = Paths.get(userHome, ".kompile", "models");
        if (!Files.exists(modelsDir)) return;
        try (var stream = Files.walk(modelsDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".opt.sdz"))
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                        logger.info("Cleared stale optimized model: {}", p);
                    } catch (Exception e) {
                        logger.warn("Failed to delete optimized model {}: {}", p, e.getMessage());
                    }
                });
        } catch (Exception e) {
            logger.warn("Failed to clear stale optimized models: {}", e.getMessage());
        }
    }

    private static AnnotationConfigApplicationContext createContext(VlmTestSubprocessArgs args) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();

        // Enable subprocess mode so SubprocessVlmTestConfiguration is activated
        context.getEnvironment().getSystemProperties().put("kompile.subprocess.vlmtest.mode", "true");

        // Register the dedicated VLM subprocess configuration
        // This uses a whitelist approach with exclude filters to avoid loading
        // REST controllers, document processors, and other unrelated beans
        context.register(SubprocessVlmTestConfiguration.class);

        // Set active profile
        context.getEnvironment().setActiveProfiles("subprocess");

        context.refresh();
        return context;
    }

    private static void cleanupNd4j() {
        try {
            Nd4j.getWorkspaceManager().destroyAllWorkspacesForCurrentThread();
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    private static String getStackTrace(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement element : t.getStackTrace()) {
            sb.append("  at ").append(element).append("\n");
            if (sb.length() > 2000) {
                sb.append("  ... (truncated)\n");
                break;
            }
        }
        return sb.toString();
    }
}
