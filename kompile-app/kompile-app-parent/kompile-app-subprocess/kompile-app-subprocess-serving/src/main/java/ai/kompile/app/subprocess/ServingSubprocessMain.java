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
import ai.kompile.app.llm.pipeline.SameDiffLanguageModelImpl;
import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.pipelines.framework.core.context.NoOpMetrics;
import ai.kompile.pipelines.framework.core.context.NoOpProfiler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.nd4j.common.config.ND4JSystemProperties;
import org.nd4j.imports.converters.DifferentialFunctionClassHolder;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.nativeblas.NativeOpsHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
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
            initializeNd4j(servingArgs);

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
                preloadModel(components.languageModel(), servingArgs);
                trimGpuMemoryPools("post-model-preload");
            }

            ServingSubprocessHttpServer httpServer = ServingSubprocessHttpServer.start(
                    host,
                    port,
                    components.objectMapper(),
                    components.modelController(),
                    components.generateController());
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

    private static void preloadModel(SameDiffLanguageModelImpl llm,
                                     ServingSubprocessArgs args) throws Exception {
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
        if (args.optimizerEnabled() != null) {
            opts.put("graphOptimizerEnabled", args.optimizerEnabled());
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

    private static void initializeNd4j(ServingSubprocessArgs servingArgs) throws Exception {
        // Parse ND4J config from args (thread counts, memory limits, triton settings, etc.)
        String nd4jConfigJson = servingArgs.nd4jConfigJson();
        Nd4jEnvironmentConfig config;
        if (nd4jConfigJson != null && !nd4jConfigJson.isBlank()) {
            try {
                config = OBJECT_MAPPER.readValue(nd4jConfigJson, Nd4jEnvironmentConfig.class);
            } catch (Exception e) {
                logger.warn("Failed to parse ND4J config, using defaults: {}", e.getMessage());
                config = Nd4jEnvironmentConfig.defaults();
            }
        } else {
            config = Nd4jEnvironmentConfig.defaults();
        }

        // Complete Nd4j/backend/native initialization before scanning and constructing
        // DifferentialFunction implementations. Op constructors access Nd4j; initializing
        // the registry first causes re-entrant, partial Nd4j initialization.
        Nd4j.scalar(0.0f);
        logger.info("Loaded ND4J backend: {}", Nd4j.getBackend().getClass().getSimpleName());

        // Apply full ND4J environment config (threads, memory, debug flags, etc.)
        applyNd4jEnvironmentConfig(config);

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
        if (servingArgs.optimizerEnabled() != null) {
            System.setProperty(ND4JSystemProperties.OPTIMIZER_ENABLED,
                    String.valueOf(servingArgs.optimizerEnabled()));
        }
        if (servingArgs.optimizerFp16() != null) {
            System.setProperty(ND4JSystemProperties.OPTIMIZER_FP16,
                    String.valueOf(servingArgs.optimizerFp16()));
        }

        logger.info("ND4J initialized: backend={}", Nd4j.getBackend().getClass().getSimpleName());
    }

    private static void applyNd4jEnvironmentConfig(Nd4jEnvironmentConfig config) {
        if (config == null) return;
        try {
            if (config.enableBlas() != null) Nd4j.getEnvironment().setEnableBlas(config.enableBlas());
            if (config.helpersAllowed() != null) Nd4j.getEnvironment().allowHelpers(config.helpersAllowed());
            if (config.maxThreads() != null) Nd4j.getEnvironment().setMaxThreads(config.maxThreads());
            if (config.maxMasterThreads() != null) Nd4j.getEnvironment().setMaxMasterThreads(config.maxMasterThreads());
            if (config.debug() != null) Nd4j.getEnvironment().setDebug(config.debug());
            if (config.verbose() != null) Nd4j.getEnvironment().setVerbose(config.verbose());
            if (config.maxPrimaryMemory() != null && config.maxPrimaryMemory() > 0)
                Nd4j.getEnvironment().setMaxPrimaryMemory(config.maxPrimaryMemory());
            if (config.maxSpecialMemory() != null && config.maxSpecialMemory() > 0)
                Nd4j.getEnvironment().setMaxSpecialMemory(config.maxSpecialMemory());
            if (config.maxDeviceMemory() != null && config.maxDeviceMemory() > 0)
                Nd4j.getEnvironment().setMaxDeviceMemory(config.maxDeviceMemory());
        } catch (Exception e) {
            logger.error("Error applying ND4J config: {}", e.getMessage(), e);
        }
    }

    private static void setPropertyIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }
}
