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

package ai.kompile.app.subprocess.model;

import ai.kompile.app.config.Nd4jEnvironmentConfig;
import ai.kompile.embedding.anserini.AnseriniEncoderFactory;
import ai.kompile.embedding.anserini.RetryableErrorClassifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.anserini.encoder.samediff.SameDiffEncoder;
import org.nd4j.imports.converters.DifferentialFunctionClassHolder;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.factory.Nd4jBackend;
import org.nd4j.nativeblas.NativeOps;
import org.nd4j.nativeblas.NativeOpsHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Main entry point for the model initialization subprocess.
 *
 * <p>This is a lightweight standalone application that:
 * <ol>
 *   <li>Initializes ND4J environment (isolated from main application)</li>
 *   <li>Configures model source (staging service or archive)</li>
 *   <li>Creates and validates the embedding encoder</li>
 *   <li>Reports progress and results via STDOUT JSON messages</li>
 * </ol>
 *
 * <p>Usage:
 * <pre>
 * java -cp classpath ai.kompile.app.subprocess.model.ModelInitSubprocessMain args-file.json
 * </pre>
 *
 * <p>The args file contains a JSON-serialized {@link ModelInitSubprocessArgs} object.
 *
 * <p><b>Why a subprocess?</b>
 * <ul>
 *   <li>ND4J native library loading is isolated from the main JVM</li>
 *   <li>OOM during model loading doesn't crash the main application</li>
 *   <li>Model initialization can be retried without restarting the app</li>
 *   <li>Progress is reported in real-time to the UI</li>
 * </ul>
 */
public class ModelInitSubprocessMain {

    private static final Logger logger = LoggerFactory.getLogger(ModelInitSubprocessMain.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Redirect System.out to System.err for logging, keep protocol messages on original stdout
    private static PrintStream originalStdout;

    // Static holder for args - accessible by other components if needed
    private static volatile ModelInitSubprocessArgs currentArgs;

    // Result holder - the initialized encoder (if successful)
    private static volatile SameDiffEncoder<?> initializedEncoder;

    /**
     * Get the current subprocess args.
     */
    public static ModelInitSubprocessArgs getCurrentArgs() {
        return currentArgs;
    }

    /**
     * Get the initialized encoder (available after successful initialization).
     */
    public static SameDiffEncoder<?> getInitializedEncoder() {
        return initializedEncoder;
    }

    public static void main(String[] args) {
        // Capture original stdout for protocol messages
        originalStdout = System.out;

        // Redirect System.out to stderr so logging doesn't interfere with protocol
        System.setOut(System.err);

        int exitCode = 0;
        ModelInitSubprocessArgs subprocessArgs = null;
        ModelInitProgressReporter reporter = null;

        try {
            // Parse arguments
            if (args.length < 1) {
                System.err.println("Usage: ModelInitSubprocessMain <args-file.json>");
                System.exit(1);
            }

            Path argsFile = Paths.get(args[0]);
            if (!Files.exists(argsFile)) {
                System.err.println("Args file not found: " + argsFile);
                System.exit(1);
            }

            subprocessArgs = ModelInitSubprocessArgs.readFromFile(argsFile);
            currentArgs = subprocessArgs;
            logger.info("Loaded subprocess args for task: {}, model: {}",
                    subprocessArgs.taskId(), subprocessArgs.modelIdentifier());

            // Initialize progress reporter
            reporter = new ModelInitProgressReporter(subprocessArgs.taskId(),
                    subprocessArgs.modelIdentifier(), originalStdout);

            // Start heartbeat immediately
            reporter.startHeartbeat();

            // Log startup
            logger.info("═══════════════════════════════════════════════════════════════════════════════");
            logger.info("           MODEL INITIALIZATION SUBPROCESS STARTING");
            logger.info("═══════════════════════════════════════════════════════════════════════════════");
            logger.info("Task ID:     {}", subprocessArgs.taskId());
            logger.info("Model ID:    {}", subprocessArgs.modelIdentifier());
            logger.info("Source Type: {}", subprocessArgs.modelSourceType());
            logger.info("═══════════════════════════════════════════════════════════════════════════════");

            // Phase 1: Initialize ND4J
            reporter.reportPhaseTransition(ModelInitMessage.Phase.INITIALIZING_ND4J);
            reporter.reportProgress(5, 0, "Initializing ND4J backend...");
            initializeNd4j(subprocessArgs.nd4jConfigJson());
            reporter.reportProgress(10, 100, "ND4J initialized");

            // Phase 2: Configure model source
            reporter.reportPhaseTransition(ModelInitMessage.Phase.CONFIGURING_MODEL_SOURCE);
            reporter.reportProgress(15, 0, "Configuring model source...");
            configureModelSource(subprocessArgs, reporter);
            reporter.reportProgress(20, 100, "Model source configured");

            // Phase 3: Look up model in registry
            reporter.reportPhaseTransition(ModelInitMessage.Phase.LOOKING_UP_REGISTRY);
            reporter.reportProgress(25, 0, "Looking up model in registry...");

            String modelId = subprocessArgs.modelIdentifier();
            boolean modelAvailable = AnseriniEncoderFactory.isModelAvailable(modelId);
            if (!modelAvailable) {
                throw new IllegalStateException("Model '" + modelId + "' not found in registry. " +
                        "Available models: " + AnseriniEncoderFactory.getAvailableModelIds());
            }
            reporter.reportProgress(30, 100, "Model found in registry");

            // Phase 4: Create encoder (this does the heavy lifting - loading model files)
            reporter.reportPhaseTransition(ModelInitMessage.Phase.CREATING_ENCODER);
            reporter.reportProgress(35, 0, "Creating encoder (loading model files)...");
            reporter.reportLog("INFO", "This may take a moment as model files are loaded into memory");

            long encoderStartTime = System.currentTimeMillis();
            SameDiffEncoder<float[]> encoder = AnseriniEncoderFactory.createEncoder(modelId);
            long encoderCreateTime = System.currentTimeMillis() - encoderStartTime;

            initializedEncoder = encoder;
            reporter.reportProgress(70, 100, String.format("Encoder created in %dms", encoderCreateTime));

            // Get encoder info
            String encoderType = AnseriniEncoderFactory.getEncoderTypeFromModelId(modelId).name();
            Integer embeddingDim = AnseriniEncoderFactory.getEmbeddingDimension(modelId);
            Integer maxSeqLen = AnseriniEncoderFactory.getMaxSequenceLength(modelId);

            // Phase 5: Validate model (run test embedding)
            if (!subprocessArgs.skipValidation()) {
                reporter.reportPhaseTransition(ModelInitMessage.Phase.VALIDATING_MODEL);
                reporter.reportProgress(75, 0, "Validating model output...");

                String testText = subprocessArgs.validationTestText();
                if (testText == null || testText.isBlank()) {
                    testText = "This is a validation test for the embedding model.";
                }

                long validationStart = System.currentTimeMillis();
                float[] testEmbedding = encoder.encode(testText);
                long validationTime = System.currentTimeMillis() - validationStart;

                if (testEmbedding == null || testEmbedding.length == 0) {
                    throw new IllegalStateException("Model validation failed: encoder returned null/empty embedding");
                }

                // Check for zero-magnitude vector
                double magnitude = 0.0;
                for (float v : testEmbedding) {
                    magnitude += v * v;
                }
                magnitude = Math.sqrt(magnitude);

                if (magnitude < 1e-10) {
                    throw new IllegalStateException(String.format(
                            "Model validation failed: encoder produced zero-magnitude vector (magnitude=%.2e). " +
                                    "This indicates the model forward pass is broken.", magnitude));
                }

                // Update embedding dimension from actual output
                if (embeddingDim == null || embeddingDim <= 0) {
                    embeddingDim = testEmbedding.length;
                }

                reporter.reportProgress(90, 100, String.format(
                        "Validation passed in %dms (dims=%d, magnitude=%.4f)",
                        validationTime, testEmbedding.length, magnitude));

                logger.info("Model validation PASSED: dims={}, magnitude={}, time={}ms",
                        testEmbedding.length, String.format("%.4f", magnitude), validationTime);
            } else {
                reporter.reportLog("WARN", "Model validation skipped (skipValidation=true)");
            }

            // Phase 6: Complete
            reporter.reportPhaseTransition(ModelInitMessage.Phase.COMPLETE);

            // Build model metrics
            ModelInitMessage.ModelMetrics metrics = new ModelInitMessage.ModelMetrics(
                    0, // modelFileSizeBytes - would need to calculate
                    0, // numModelFiles
                    null, // modelPath
                    0, // numOpsInGraph
                    0, // numVariablesInGraph
                    0, // vocabSize
                    null, // tokenizerType
                    encoderCreateTime, // testEmbeddingTimeMs (approximation)
                    0, // testThroughputTokensPerSec
                    subprocessArgs.optimalBatchSize(),
                    subprocessArgs.maxBatchSize()
            );

            // Report completion
            reporter.reportCompleted(
                    "REGISTRY",
                    encoderType,
                    embeddingDim != null ? embeddingDim : 768,
                    maxSeqLen != null ? maxSeqLen : 512,
                    metrics
            );

            logger.info("═══════════════════════════════════════════════════════════════════════════════");
            logger.info("           MODEL INITIALIZATION COMPLETE");
            logger.info("═══════════════════════════════════════════════════════════════════════════════");
            logger.info("Model ID:    {}", modelId);
            logger.info("Encoder:     {}", encoder.getClass().getSimpleName());
            logger.info("Dimensions:  {}", embeddingDim);
            logger.info("Max Seq Len: {}", maxSeqLen);
            logger.info("Time:        {}ms", reporter.getElapsedMs());
            logger.info("═══════════════════════════════════════════════════════════════════════════════");

            // Don't close the encoder here - it will be used by the callback
            // The main app will signal when it has received the encoder

        } catch (OutOfMemoryError oom) {
            logger.error("OUT OF MEMORY during model initialization");
            logger.error("Heap: max={}MB, used={}MB",
                    Runtime.getRuntime().maxMemory() / (1024 * 1024),
                    (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024));

            System.gc(); // Try to recover for cleanup

            if (reporter != null) {
                reporter.reportFailed("OutOfMemoryError - increase heap size for model initialization",
                        "OutOfMemoryError", null, true);
            }
            exitCode = 137;

        } catch (Exception e) {
            logger.error("Model initialization failed", e);

            // Classify error as retriable or permanent
            boolean retriable = RetryableErrorClassifier.isRetriable(e);

            if (reporter != null) {
                reporter.reportFailed(e, retriable);
            }
            exitCode = retriable ? 2 : 1; // Different exit codes for retriable vs permanent

        } finally {
            // Stop heartbeat
            if (reporter != null) {
                reporter.stopHeartbeat();
                reporter.close();
            }

            // Note: We don't cleanup ND4J here because the encoder might still be needed
            // The parent process will handle cleanup when it's done
        }

        System.exit(exitCode);
    }

    /**
     * Initialize ND4J environment.
     */
    private static void initializeNd4j(String nd4jConfigJson) throws Exception {
        logger.info("Initializing ND4J backend and environment...");

        // Initialize DifferentialFunctionClassHolder
        DifferentialFunctionClassHolder.initInstance();

        // Use built-in backend discovery - automatically finds CUDA, CPU, or other available backends
        // This mirrors MainApplication's approach and avoids hardcoding nd4j-native
        Nd4jBackend backend = Nd4jBackend.load();
        Nd4j.backend = backend;
        logger.info("Loaded ND4J backend: {}", backend.getClass().getSimpleName());

        // NativeOps is automatically initialized by backend loading
        NativeOps nativeOps = NativeOpsHolder.getInstance().getDeviceNativeOps();
        nativeOps.initializeDevicesAndFunctions();

        // Apply ND4J environment config if provided
        if (nd4jConfigJson != null && !nd4jConfigJson.isBlank()) {
            try {
                Nd4jEnvironmentConfig config = OBJECT_MAPPER.readValue(nd4jConfigJson, Nd4jEnvironmentConfig.class);
                applyNd4jEnvironmentConfig(config);
            } catch (Exception e) {
                logger.warn("Failed to parse ND4J config JSON, using defaults: {}", e.getMessage());
                applyNd4jEnvironmentConfig(Nd4jEnvironmentConfig.defaults());
            }
        } else {
            applyNd4jEnvironmentConfig(Nd4jEnvironmentConfig.defaults());
        }

        logger.info("ND4J initialized: maxThreads={}, backend={}",
                Nd4j.getEnvironment().maxThreads(),
                Nd4j.getBackend().getClass().getSimpleName());
    }

    /**
     * Apply ND4J environment configuration.
     */
    private static void applyNd4jEnvironmentConfig(Nd4jEnvironmentConfig config) {
        if (config == null) {
            config = Nd4jEnvironmentConfig.defaults();
        }

        try {
            if (config.enableBlas() != null) {
                Nd4j.getEnvironment().setEnableBlas(config.enableBlas());
            }
            if (config.helpersAllowed() != null) {
                Nd4j.getEnvironment().allowHelpers(config.helpersAllowed());
            }
            if (config.maxThreads() != null) {
                Nd4j.getEnvironment().setMaxThreads(config.maxThreads());
            }
            if (config.maxMasterThreads() != null) {
                Nd4j.getEnvironment().setMaxMasterThreads(config.maxMasterThreads());
            }
            if (config.debug() != null) {
                Nd4j.getEnvironment().setDebug(config.debug());
            }
            if (config.verbose() != null) {
                Nd4j.getEnvironment().setVerbose(config.verbose());
            }

            logger.info("ND4J environment configured: enableBlas={}, maxThreads={}, debug={}",
                    Nd4j.getEnvironment().isEnableBlas(),
                    Nd4j.getEnvironment().maxThreads(),
                    Nd4j.getEnvironment().isDebug());

        } catch (Exception e) {
            logger.warn("Error applying ND4J config: {}", e.getMessage());
        }
    }

    /**
     * Configure the model source (staging service or archive).
     */
    private static void configureModelSource(ModelInitSubprocessArgs args, ModelInitProgressReporter reporter) {
        String sourceType = args.modelSourceType();
        String modelId = args.modelIdentifier();

        logger.info("Configuring model source: type={}, modelId={}", sourceType, modelId);

        if (sourceType == null || sourceType.isBlank()) {
            logger.info("No model source type specified - using default registry");
            return;
        }

        try {
            if ("staging".equalsIgnoreCase(sourceType)) {
                String stagingUrl = args.stagingUrl();
                String stagingApiKey = args.stagingApiKey();

                if (stagingUrl == null || stagingUrl.isBlank()) {
                    logger.warn("Staging source type specified but no staging URL provided");
                    return;
                }

                logger.info("Configuring staging service: url={}", stagingUrl);
                reporter.reportLog("INFO", "Connecting to staging service at " + stagingUrl);

                AnseriniEncoderFactory.configureStagingService(stagingUrl, stagingApiKey);
                logger.info("Staging service configured successfully");

            } else if ("archive".equalsIgnoreCase(sourceType)) {
                String archivePath = args.archivePath();

                if (archivePath == null || archivePath.isBlank()) {
                    logger.warn("Archive source type specified but no archive path provided");
                    return;
                }

                Path path = Paths.get(archivePath);
                if (!Files.exists(path)) {
                    throw new IllegalArgumentException("Archive file not found: " + archivePath);
                }

                logger.info("Loading archive: {}", archivePath);
                reporter.reportLog("INFO", "Loading model archive from " + archivePath);

                AnseriniEncoderFactory.loadArchive(path);
                logger.info("Archive loaded successfully");

            } else {
                logger.info("Source type '{}' - using default registry lookup", sourceType);
            }

            // Refresh registry to pick up configured source
            AnseriniEncoderFactory.refreshRegistry();

            // Log available models
            var availableModels = AnseriniEncoderFactory.getAvailableModelIds();
            logger.info("Available models after configuration: {}", availableModels);

            if (modelId != null && !modelId.isBlank()) {
                boolean available = AnseriniEncoderFactory.isModelAvailable(modelId);
                logger.info("Requested model '{}' available: {}", modelId, available);
            }

        } catch (Exception e) {
            logger.error("Failed to configure model source: {}", e.getMessage(), e);
            reporter.reportLog("ERROR", "Failed to configure model source: " + e.getMessage());
            throw new RuntimeException("Model source configuration failed", e);
        }
    }
}
