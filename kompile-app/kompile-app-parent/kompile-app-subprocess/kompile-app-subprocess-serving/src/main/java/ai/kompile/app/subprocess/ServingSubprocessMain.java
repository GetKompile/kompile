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

import ai.kompile.app.config.NativeLibraryResolver;
import ai.kompile.app.config.Nd4jEnvironmentConfig;
import ai.kompile.app.llm.pipeline.LlmGenerateController;
import ai.kompile.app.llm.pipeline.LlmModelController;
import ai.kompile.app.llm.pipeline.LoadRequest;
import ai.kompile.app.llm.pipeline.SameDiffLanguageModelImpl;
import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.pipelines.framework.core.context.NoOpMetrics;
import ai.kompile.pipelines.framework.core.context.NoOpProfiler;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.nd4j.common.config.ND4JSystemProperties;
import org.nd4j.imports.converters.DifferentialFunctionClassHolder;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.factory.Environment;
import org.nd4j.nativeblas.NativeOpsHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Subprocess entry point for modular LLM serving.
 *
 * <p>Starts a bounded JDK HTTP server backed by an explicitly constructed,
 * reflection-free serving graph with LLM load/unload/generate endpoints. Runs as an
 * independent process with its own ND4J backend (CPU or CUDA). Can be deployed
 * separately from the main kompile-app for modular model serving.</p>
 *
 * <h3>Usage:</h3>
 * <pre>
 *   # Standalone executable JAR:
 *   java -jar kompile-app-subprocess-serving-exec.jar args-file.json
 *
 *   # Standalone native binary:
 *   kompile-model-serving args-file.json
 *
 * </pre>
 *
 * <h3>Endpoints exposed:</h3>
 * <ul>
 *   <li>{@code POST /api/llm/load} — Load a model from staging registry or local path</li>
 *   <li>{@code POST /api/llm/unload} — Unload the current model</li>
 *   <li>{@code GET  /api/llm/status} — Current model status</li>
 *   <li>{@code POST /api/llm/generate} — Text generation (via {@link SameDiffLanguageModelImpl})</li>
 * </ul>
 */
public class ServingSubprocessMain {

    private static final Logger logger = LoggerFactory.getLogger(ServingSubprocessMain.class);
    private static final ObjectMapper OBJECT_MAPPER = JsonUtils.standardMapper();
    private static final ObjectReader ND4J_CONFIG_READER = JsonUtils.newStandardMapper()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .readerFor(Nd4jEnvironmentConfig.class);

    /**
     * In-JVM heap/GPU/off-heap watchdog. Started from args thresholds before any model
     * allocation so even the load phase is covered; null when the kill threshold is 0.
     */
    private static volatile SubprocessMemoryWatchdog memoryWatchdog;

    public static void main(String[] args) {
        NativeLibraryResolver.bootstrapModelExecutionOrThrow();
        final ServingSubprocessArgs servingArgs;
        try {
            servingArgs = requireArgs(args);
            logger.info("Loaded serving args from: {}", args[0]);
        } catch (Exception e) {
            logger.error("Serving subprocess requires exactly one valid args JSON file", e);
            System.exit(1);
            return;
        }

        try {
            // Initialize ND4J backend
            logger.info("Initializing ND4J backend...");
            Nd4jEnvironmentConfig nd4jConfig = initializeNd4j(servingArgs);

            // Heap/GPU/off-heap pressure protection must cover the model-load phase too,
            // so start the watchdog immediately after backend init — before pre-load.
            startMemoryWatchdog(servingArgs);
            if (servingArgs.modelId() != null) {
                if (memoryWatchdog != null) {
                    memoryWatchdog.setModelId(servingArgs.modelId());
                }
            }

            // Set proactive soft limit on CudaMemoryPool
            int softLimitPercent = servingArgs.gpuSoftLimitPercent();
            if (softLimitPercent > 0) {
                try {
                    var nativeOps = org.nd4j.nativeblas.NativeOpsHolder.getInstance().getDeviceNativeOps();
                    nativeOps.setMemoryPoolSoftLimitPercent(softLimitPercent);
                    logger.info("CudaMemoryPool soft limit set to {}%", softLimitPercent);
                } catch (Exception e) {
                    logger.debug("Could not set memory pool soft limit (CPU backend or method not available): {}", e.getMessage());
                }
            }

            // Start the native-safe serving context and bounded JDK HTTP server.
            int port = servingArgs.port() > 0 ? servingArgs.port() : 8091;
            String host = servingArgs.host() != null && !servingArgs.host().isBlank()
                    ? servingArgs.host() : "127.0.0.1";
            String stagingUrl = servingArgs.stagingUrl();

            logger.info("Starting LLM serving subprocess on {}:{}", host, port);

            if (stagingUrl != null && !stagingUrl.isBlank()) {
                System.setProperty("kompile.staging.url", stagingUrl);
            }
            if (System.getProperty("kompile.llm.cache.dir") == null) {
                System.setProperty("kompile.llm.cache.dir",
                        KompileHome.llmCacheDirectory().getAbsolutePath());
            }
            // This is a fixed request-scoped worker graph. Construct it explicitly instead
            // of asking a native image to reflectively bootstrap Spring's annotation context.
            ServingComponents components = createServingComponents(
                    stagingUrl, System.getProperty("kompile.llm.cache.dir"));

            AtomicReference<ServingSubprocessHttpServer> httpServerRef = new AtomicReference<>();
            CountDownLatch shutdownLatch = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Shutting down LLM serving subprocess...");
                closeWatchdog();
                ServingSubprocessHttpServer server = httpServerRef.get();
                if (server != null) {
                    server.close();
                }
                components.close();
                shutdownLatch.countDown();
            }, "serving-subprocess-shutdown"));

            // Finish optional initialization before accepting requests. This prevents
            // concurrent load/generate calls from racing startup model construction.
            if (servingArgs.modelId() != null && servingArgs.modelPath() != null) {
                preloadModel(components.languageModel(), servingArgs, nd4jConfig);
                trimGpuMemoryPools("post-model-preload");
            }

            ServingSubprocessHttpServer httpServer = ServingSubprocessHttpServer.start(
                    host,
                    port,
                    components.objectMapper(),
                    servingApiWithWatchdog(components),
                    // Keep the transport defaults identical to the controller-based overload.
                    defaultPositive("kompile.serving.subprocess.max-request-bytes",
                            ServingSubprocessHttpServer.DEFAULT_MAX_REQUEST_BYTES),
                    defaultPositive("kompile.serving.subprocess.max-response-bytes",
                            ServingSubprocessHttpServer.DEFAULT_MAX_RESPONSE_BYTES),
                    defaultPositive("kompile.serving.subprocess.http-threads",
                            ServingSubprocessHttpServer.DEFAULT_HTTP_THREADS),
                    defaultPositive("kompile.serving.subprocess.http-queue-capacity",
                            ServingSubprocessHttpServer.DEFAULT_HTTP_QUEUE_CAPACITY));
            httpServerRef.set(httpServer);

            logger.info("LLM serving subprocess started on port {}", port);

            // Block until shutdown
            logger.info("Serving subprocess ready. Waiting for requests...");
            shutdownLatch.await();

        } catch (Exception e) {
            logger.error("Serving subprocess failed to start", e);
            System.exit(1);
        }
    }

    /**
     * Start the in-JVM {@link SubprocessMemoryWatchdog} from the args thresholds.
     * A kill threshold of 0 disables the watchdog entirely (documented opt-out);
     * otherwise the child self-protects exactly like the ingest/graph children do.
     */
    private static void startMemoryWatchdog(ServingSubprocessArgs args) {
        try {
            if (args.memoryKillThresholdPercent() <= 0) {
                logger.info("Memory watchdog disabled (memoryKillThresholdPercent=0)");
                return;
            }
            memoryWatchdog = new SubprocessMemoryWatchdog(
                    args.memoryThresholdPercent(),
                    args.memoryCriticalPercent(),
                    args.memoryKillThresholdPercent(),
                    args.memoryCheckIntervalMs(),
                    args.gpuMemoryThresholdPercent(),
                    args.gpuMemoryCriticalPercent(),
                    args.gpuMemoryKillThresholdPercent(),
                    args.offHeapThresholdPercent(),
                    args.offHeapCriticalPercent(),
                    args.offHeapKillThresholdPercent());
            memoryWatchdog.start();
            logger.info("Serving memory watchdog active: heap stop={}%/crit={}%/kill={}%; "
                            + "interval={}ms",
                    args.memoryThresholdPercent(), args.memoryCriticalPercent(),
                    args.memoryKillThresholdPercent(), args.memoryCheckIntervalMs());
        } catch (Throwable t) {
            // Watchdog failure must never prevent serving from starting.
            memoryWatchdog = null;
            logger.warn("Could not start serving memory watchdog (non-fatal): {}", t.getMessage());
        }
    }

    /** Latest in-JVM watchdog snapshot for the status endpoint, or null when disabled. */
    static Map<String, Object> watchdogStatus() {
        SubprocessMemoryWatchdog watchdog = memoryWatchdog;
        if (watchdog == null) {
            return null;
        }
        Map<String, Object> status = new HashMap<>();
        SubprocessMemoryWatchdog.MemorySnapshot snapshot = watchdog.getLastSnapshot();
        if (snapshot != null) {
            status.put("heapUsedMB", snapshot.usedMB());
            status.put("heapMaxMB", snapshot.maxMB());
            status.put("heapUsagePercent", round1(snapshot.usagePercent()));
            status.put("gpuUsedMB", snapshot.gpuUsedMB());
            status.put("gpuTotalMB", snapshot.gpuTotalMB());
            status.put("gpuUsagePercent", round1(snapshot.gpuUsagePercent()));
            status.put("javacppMB", snapshot.javacppMB());
            status.put("directBufferMB", snapshot.directBufferMB());
            status.put("offHeapMaxMB", snapshot.offHeapMaxMB());
            status.put("offHeapUsagePercent", round1(snapshot.offHeapUsagePercent()));
            status.put("timestampMs", snapshot.timestampMs());
        }
        status.put("running", snapshot != null);
        status.put("shouldStop", watchdog.shouldStop());
        status.put("shouldKill", watchdog.shouldKill());
        status.put("criticalMemory", watchdog.isCriticalMemory());
        status.put("rapidMemoryGrowth", watchdog.isRapidMemoryGrowth());
        return status;
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    /** Same package-private overload so the main class can decorate the API surface. */
    private static long defaultPositive(String property, long fallback) {
        try {
            String value = System.getProperty(property);
            long parsed = value == null || value.isBlank() ? 0L : Long.parseLong(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int defaultPositive(String property, int fallback) {
        try {
            String value = System.getProperty(property);
            int parsed = value == null || value.isBlank() ? 0 : Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Wrap the serving API so {@code GET /api/llm/status} also reports the in-JVM
     * watchdog state. Load/generate/chat/unload are passed through untouched.
     */
    private static ServingSubprocessHttpServer.Api servingApiWithWatchdog(ServingComponents components) {
        return new ServingSubprocessHttpServer.Api() {
            @Override
            public synchronized ResponseEntity<Map<String, Object>> load(LoadRequest request) {
                return components.modelController().load(request);
            }

            @Override
            public ResponseEntity<Map<String, Object>> status() {
                ResponseEntity<Map<String, Object>> response =
                        components.modelController().status();
                Map<String, Object> watchdog = watchdogStatus();
                if (watchdog != null && response.getBody() != null) {
                    response.getBody().put("memoryWatchdog", watchdog);
                }
                return response;
            }

            @Override
            public ResponseEntity<Map<String, Object>> generate(
                    Map<String, Object> request) {
                return components.generateController().generate(request);
            }

            @Override
            public ResponseEntity<Map<String, Object>> chat(
                    Map<String, Object> request) {
                return components.generateController().chat(request);
            }

            @Override
            public synchronized ResponseEntity<Map<String, Object>> unload() {
                return components.modelController().unload();
            }
        };
    }

    private static void closeWatchdog() {
        SubprocessMemoryWatchdog watchdog = memoryWatchdog;
        memoryWatchdog = null;
        if (watchdog != null) {
            try {
                watchdog.close();
            } catch (Exception ignored) {
                // Shutdown is best-effort; the JVM is exiting anyway.
            }
        }
    }

    static ServingComponents createServingComponents(String stagingUrl, String cacheDir) {
        ObjectMapper objectMapper = JsonUtils.newStandardMapper();
        SameDiffLanguageModelImpl languageModel = new SameDiffLanguageModelImpl(
                Optional.of(NoOpMetrics.INSTANCE),
                Optional.of(NoOpProfiler.INSTANCE));
        LlmModelController modelController = new LlmModelController(
                languageModel,
                new RestTemplateBuilder(),
                objectMapper,
                stagingUrl,
                cacheDir);
        LlmGenerateController generateController = new LlmGenerateController(languageModel);
        return new ServingComponents(
                objectMapper, languageModel, modelController, generateController);
    }

    record ServingComponents(
            ObjectMapper objectMapper,
            SameDiffLanguageModelImpl languageModel,
            LlmModelController modelController,
            LlmGenerateController generateController) implements AutoCloseable {
        @Override
        public void close() {
            languageModel.unloadModel();
        }
    }

    static ServingSubprocessArgs requireArgs(String[] args) throws IOException {
        if (args == null || args.length != 1 || args[0] == null || args[0].isBlank()) {
            throw new IllegalArgumentException("expected exactly one args JSON file");
        }
        Path argsPath = Paths.get(args[0]);
        if (!Files.isRegularFile(argsPath)) {
            throw new IllegalArgumentException("args JSON file does not exist: " + argsPath);
        }
        return ServingSubprocessArgs.fromFile(argsPath);
    }

    static void preloadModel(SameDiffLanguageModelImpl llm,
                             ServingSubprocessArgs args,
                             Nd4jEnvironmentConfig config) throws Exception {
        Path modelPath = Paths.get(args.modelPath());
        Path tokenizerPath = args.tokenizerPath() != null
                ? Paths.get(args.tokenizerPath())
                : modelPath.getParent().resolve("tokenizer.json");

        Map<String, Object> opts = new HashMap<>();
        opts.put("maxNewTokens", args.maxNewTokens() > 0 ? args.maxNewTokens() : 256);
        if (args.temperature() != null) {
            opts.put("temperature", args.temperature());
        }
        if (args.topK() != null) {
            opts.put("topK", args.topK());
        }
        if (args.dspEnabled() != null) {
            opts.put("dspEnabled", args.dspEnabled());
            // Recovery mode is a complete Kompile-side Java decode loop. Merely disabling
            // SameDiff DSP still leaves GGUF GenerationPipeline dependent on the native
            // autoregressive_decode plan, so select the existing lifecycle-managed runner.
            opts.put("legacyGeneration", !args.dspEnabled());
        }
        Boolean optimizerEnabled = effectiveOptimizerEnabled(args, config);
        if (optimizerEnabled != null) {
            opts.put("graphOptimizerEnabled", optimizerEnabled);
        }
        // Optional model/runtime knobs — present in the args JSON only when the caller
        // set them; null/0 values fall through to model-owned defaults in loadModel.
        if (args.chatTemplate() != null) {
            opts.put("chatTemplate", args.chatTemplate());
        }
        if (args.kvCacheType() != null) {
            opts.put("kvCacheType", args.kvCacheType());
        }
        if (args.maxKvCacheLength() != null) {
            opts.put("maxKvCacheLength", args.maxKvCacheLength());
        }
        if (args.maxPrefillLength() != null) {
            opts.put("maxPrefillLength", args.maxPrefillLength());
        }
        if (args.continuationEnabled() != null) {
            opts.put("continuationEnabled", args.continuationEnabled());
        }
        if (args.continuationChunkTokens() != null) {
            opts.put("continuationChunkTokens", args.continuationChunkTokens());
        }
        if (args.prefixCacheEnabled() != null) {
            opts.put("prefixCacheEnabled", args.prefixCacheEnabled());
        }
        if (args.prefixCacheMaxBytes() != null) {
            opts.put("prefixCacheMaxBytes", args.prefixCacheMaxBytes());
        }
        if (args.prefixCacheBlockSize() != null) {
            opts.put("prefixCacheBlockSize", args.prefixCacheBlockSize());
        }

        logger.info("Pre-loading model: {} from {}", args.modelId(), modelPath);
        try {
            llm.loadModel(args.modelId(), modelPath, tokenizerPath, opts);
        } catch (Exception failure) {
            logger.error("Failed to pre-load model {} from {}",
                    args.modelId(), modelPath, failure);
            throw failure;
        }
        logger.info("Model {} pre-loaded successfully", args.modelId());
    }

    /**
     * Trim CUDA memory pools on all devices to release reserved-but-unused GPU memory.
     */
    private static void trimGpuMemoryPools(String reason) {
        try {
            var nativeOps = Nd4j.getNativeOps();
            int numDevices = Nd4j.getAffinityManager().getNumberOfDevices();
            for (int d = 0; d < numDevices; d++) {
                nativeOps.trimMemoryPool(d);
            }
            logger.info("Trimmed GPU memory pools on {} device(s) (reason: {})", numDevices, reason);
        } catch (Exception e) {
            logger.debug("Could not trim GPU memory pools (CPU backend?): {}", e.getMessage());
        }
    }

    static Nd4jEnvironmentConfig readNd4jEnvironmentConfig(String json) throws IOException {
        if (json == null) {
            return Nd4jEnvironmentConfig.defaults();
        }
        Nd4jEnvironmentConfig config = ND4J_CONFIG_READER.readValue(json);
        if (config == null) {
            throw new IOException("nd4jConfigJson must contain a JSON object");
        }
        if ((config.maxPrimaryMemory() != null && config.maxPrimaryMemory() < 0)
                || (config.maxSpecialMemory() != null && config.maxSpecialMemory() < 0)
                || (config.maxDeviceMemory() != null && config.maxDeviceMemory() < 0)) {
            throw new IOException("ND4J memory limits must be nonnegative byte counts (0 = unlimited)");
        }
        return config;
    }

    private static Boolean effectiveOptimizerEnabled(ServingSubprocessArgs args,
                                                     Nd4jEnvironmentConfig config) {
        return args.optimizerEnabled() != null ? args.optimizerEnabled()
                : args.nd4jConfigJson() != null ? config.optimizerEnabled() : null;
    }

    static void applyOptimizerProperties(ServingSubprocessArgs args, Nd4jEnvironmentConfig config) {
        Boolean optimizerEnabled = effectiveOptimizerEnabled(args, config);
        Boolean optimizerFp16 = args.optimizerFp16() != null ? args.optimizerFp16()
                : args.nd4jConfigJson() != null ? config.optimizerFp16() : null;
        if (optimizerEnabled != null) {
            System.setProperty(ND4JSystemProperties.OPTIMIZER_ENABLED, optimizerEnabled.toString());
        }
        if (optimizerFp16 != null) {
            System.setProperty(ND4JSystemProperties.OPTIMIZER_FP16, optimizerFp16.toString());
        }
    }

    private static Nd4jEnvironmentConfig initializeNd4j(ServingSubprocessArgs servingArgs) throws Exception {
        // Explicit malformed configuration is fatal; never replace requested limits with defaults.
        Nd4jEnvironmentConfig config = readNd4jEnvironmentConfig(servingArgs.nd4jConfigJson());

        // Complete Nd4j/backend/native initialization before scanning and constructing
        // DifferentialFunction implementations. Op constructors access Nd4j; initializing
        // the registry first causes re-entrant, partial Nd4j initialization.
        Nd4j.scalar(0.0f);
        logger.info("Loaded ND4J backend: {}", Nd4j.getBackend().getClass().getSimpleName());

        // Apply full ND4J environment config (threads, memory, debug flags, etc.)
        applyNd4jEnvironmentConfig(Nd4j.getEnvironment(), config);

        // Safe only after Nd4j and NativeOps have completed initialization.
        DifferentialFunctionClassHolder.initInstance();

        // Apply Triton / LLM optimizations if available
        if (NativeOpsHolder.getInstance().getDeviceNativeOps().isTritonAvailable()) {
            logger.info("Triton available — applying optimal LLM config");
            Nd4j.getEnvironment().applyOptimalLLMConfig();
            setPropertyIfAbsent(ND4JSystemProperties.DSP_GRAPH_EXECUTION_MODE, "TRITON");
        } else {
            logger.info("Triton not available — applying basic LLM config");
            Nd4j.getEnvironment().applyBasicLLMConfig();
        }

        // Apply DSP / optimizer flags from args
        if (servingArgs.dspEnabled() != null && !servingArgs.dspEnabled()) {
            System.setProperty(ND4JSystemProperties.DSP_NO_FREEZE, "true");
        }
        // Explicit args override JSON config; absent settings retain runtime defaults.
        applyOptimizerProperties(servingArgs, config);

        // Apply after backend presets, but before model loading. Explicit cap failures
        // must propagate and abort startup.
        applyDeviceMemoryLimits(Nd4j.getEnvironment(),
                Nd4j.getAffinityManager().getNumberOfDevices(), servingArgs.deviceMemoryLimitsBytes());
        logger.info("ND4J initialized: backend={}", Nd4j.getBackend().getClass().getSimpleName());
        return config;
    }

    static void applyDeviceMemoryLimits(Environment environment, int deviceCount, List<Long> requested) {
        if (requested == null) return;
        if (requested.isEmpty() || requested.size() != deviceCount) {
            throw new IllegalArgumentException("deviceMemoryLimitsBytes requires one limit for each of "
                    + deviceCount + " visible logical devices");
        }
        long[] effective = new long[deviceCount];
        // Validate every device before changing any limit. Never relax an existing ceiling.
        for (int device = 0; device < deviceCount; device++) {
            Long limit = requested.get(device);
            if (limit == null || limit <= 0) {
                throw new IllegalArgumentException("deviceMemoryLimitsBytes[" + device + "] must be positive");
            }
            long existing = environment.getDeviceLimit(device);
            effective[device] = existing > 0 ? Math.min(existing, limit) : limit;
            long allocated = environment.getDeviceCounter(device);
            if (allocated > effective[device]) {
                throw new IllegalStateException("Device " + device + " already has " + allocated
                        + " bytes allocated, exceeding requested ceiling " + effective[device]);
            }
        }
        for (int device = 0; device < deviceCount; device++) {
            environment.setDeviceLimit(device, effective[device]);
            // Some backends expose no-op limit setters: fail startup rather than serving uncapped.
            if (environment.getDeviceLimit(device) != effective[device]) {
                throw new IllegalStateException("Backend did not enforce memory ceiling for device " + device);
            }
            logger.info("Serving logical device {} memory ceiling: {} bytes", device, effective[device]);
        }
    }

    static void applyNd4jEnvironmentConfig(Environment environment, Nd4jEnvironmentConfig config) {
        if (config == null) return;
        // Configuration errors, especially rejected memory ceilings, must abort startup.
        if (config.enableBlas() != null) environment.setEnableBlas(config.enableBlas());
        if (config.helpersAllowed() != null) environment.allowHelpers(config.helpersAllowed());
        if (config.maxThreads() != null) environment.setMaxThreads(config.maxThreads());
        if (config.maxMasterThreads() != null) environment.setMaxMasterThreads(config.maxMasterThreads());
        if (config.debug() != null) environment.setDebug(config.debug());
        if (config.verbose() != null) environment.setVerbose(config.verbose());
        if (config.maxPrimaryMemory() != null && config.maxPrimaryMemory() > 0)
            environment.setMaxPrimaryMemory(config.maxPrimaryMemory());
        if (config.maxSpecialMemory() != null && config.maxSpecialMemory() > 0)
            environment.setMaxSpecialMemory(config.maxSpecialMemory());
        if (config.maxDeviceMemory() != null && config.maxDeviceMemory() > 0)
            environment.setMaxDeviceMemory(config.maxDeviceMemory());
    }

    private static void setPropertyIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }
}
