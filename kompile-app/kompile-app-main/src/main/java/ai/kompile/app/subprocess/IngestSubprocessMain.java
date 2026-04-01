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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.nd4j.imports.converters.DifferentialFunctionClassHolder;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.factory.Nd4jBackend;
import org.nd4j.nativeblas.NativeOps;
import org.nd4j.nativeblas.NativeOpsHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Main entry point for the ingest subprocess.
 *
 * This is a lightweight standalone application that:
 * 1. Initializes ND4J environment (same as MainApplication)
 * 2. Creates a minimal Spring ApplicationContext (not full Spring Boot)
 * 3. Runs the ingest pipeline
 * 4. Reports progress via STDOUT JSON and HTTP callbacks
 *
 * Usage:
 * java -cp <classpath> ai.kompile.app.subprocess.IngestSubprocessMain
 * <args-file.json>
 *
 * The args file contains a JSON-serialized SubprocessArgs object.
 */
public class IngestSubprocessMain {

    private static final Logger logger = LoggerFactory.getLogger(IngestSubprocessMain.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Redirect System.out to System.err for logging, keep protocol messages on
    // original stdout
    private static PrintStream originalStdout;

    // Static holder for subprocess args - accessible by
    // SubprocessIngestConfiguration
    private static volatile SubprocessArgs currentArgs;

    // Static holder for memory watchdog - accessible by IngestPipelineRunner and
    // ParallelIngestPipeline
    private static volatile SubprocessMemoryWatchdog memoryWatchdog;

    // Static holder for checkpoint - accessible by ParallelIngestPipeline
    private static volatile IngestCheckpoint currentCheckpoint;

    /**
     * Get the current subprocess args.
     * Used by SubprocessIngestConfiguration to get model configuration.
     */
    public static SubprocessArgs getCurrentArgs() {
        return currentArgs;
    }

    /**
     * Get the memory watchdog for this subprocess.
     * Used by IngestPipelineRunner and ParallelIngestPipeline to check memory
     * constraints.
     *
     * @return The memory watchdog, or null if not initialized
     */
    public static SubprocessMemoryWatchdog getMemoryWatchdog() {
        return memoryWatchdog;
    }

    /**
     * Get the current checkpoint for progress tracking.
     * Used by ParallelIngestPipeline to record progress after each batch.
     *
     * @return The checkpoint, or null if not using checkpointing
     */
    public static IngestCheckpoint getCurrentCheckpoint() {
        return currentCheckpoint;
    }

    /**
     * Save the current checkpoint to disk.
     * Called by ParallelIngestPipeline after successful batch completion.
     */
    public static void saveCheckpoint() {
        if (currentCheckpoint != null && currentArgs != null && currentArgs.checkpointPath() != null) {
            try {
                currentCheckpoint.save(Path.of(currentArgs.checkpointPath()));
            } catch (Exception e) {
                logger.warn("Failed to save checkpoint: {}", e.getMessage());
            }
        }
    }

    public static void main(String[] args) {
        // Capture original stdout for protocol messages
        originalStdout = System.out;

        // Redirect System.out to stderr so logging doesn't interfere with protocol
        System.setOut(System.err);

        int exitCode = 0;
        SubprocessArgs subprocessArgs = null;
        SubprocessProgressReporter reporter = null;
        HttpIngestCallback httpCallback = null;

        try {
            // Parse arguments
            if (args.length < 1) {
                System.err.println("Usage: IngestSubprocessMain <args-file.json>");
                System.exit(1);
            }

            Path argsFile = Paths.get(args[0]);
            if (!Files.exists(argsFile)) {
                System.err.println("Args file not found: " + argsFile);
                System.exit(1);
            }

            subprocessArgs = SubprocessArgs.readFromFile(argsFile);
            currentArgs = subprocessArgs; // Store for SubprocessIngestConfiguration access
            logger.info("Loaded subprocess args for task: {}", subprocessArgs.taskId());

            // Load or create checkpoint for progress tracking
            String jobId = subprocessArgs.getOption("jobId", subprocessArgs.taskId());
            if (subprocessArgs.checkpointPath() != null) {
                Path checkpointPath = Path.of(subprocessArgs.checkpointPath());
                currentCheckpoint = IngestCheckpoint.loadOrCreate(
                        checkpointPath, jobId, subprocessArgs.taskId(), subprocessArgs.filePath());

                if (subprocessArgs.resume() && currentCheckpoint.needsResume()) {
                    logger.info("========================================");
                    logger.info("RESUMING FROM CHECKPOINT");
                    logger.info("  JobId: {}", jobId);
                    logger.info("  Already embedded: {} chunks", currentCheckpoint.getEmbeddedCount());
                    logger.info("  Already indexed: {} chunks", currentCheckpoint.getIndexedCount());
                    logger.info("  OOM failures: {}", currentCheckpoint.getOomFailureCount());
                    logger.info("========================================");
                } else {
                    logger.info("Starting fresh ingest (no checkpoint or resume=false)");
                }
            }

            // Initialize progress reporter and HTTP callback
            reporter = new SubprocessProgressReporter(subprocessArgs.taskId(), originalStdout);
            httpCallback = new HttpIngestCallback(subprocessArgs.callbackBaseUrl());

            // Initialize and start memory watchdog
            memoryWatchdog = SubprocessMemoryWatchdog.fromArgs(subprocessArgs);
            memoryWatchdog.setProgressReporter(reporter);
            memoryWatchdog.start();
            logger.info("Memory watchdog started: thresholds stop={}%, critical={}%, kill={}%",
                    subprocessArgs.memoryThresholdPercent(),
                    subprocessArgs.memoryCriticalPercent(),
                    subprocessArgs.memoryKillThresholdPercent());

            // Start heartbeat immediately
            reporter.startHeartbeat();

            // Initialize ND4J BEFORE Spring context
            logger.info("Initializing ND4J environment...");
            initializeNd4j(subprocessArgs.nd4jConfigJson());

            // Configure model source BEFORE Spring context (so AnseriniEncoderFactory uses
            // correct source)
            logger.info("Configuring model source...");
            configureModelSource(subprocessArgs);

            // Notify main app that we're starting
            httpCallback.markJobRunning(subprocessArgs.taskId());

            // Create minimal Spring context
            logger.info("Creating Spring context...");
            try (AnnotationConfigApplicationContext context = createContext(subprocessArgs)) {

                // Get the pipeline runner
                IngestPipelineRunner runner = context.getBean(IngestPipelineRunner.class);

                // Log full feature set
                logSubprocessFeatures(context, runner, subprocessArgs, reporter);

                // Execute the pipeline
                logger.info("Starting ingest pipeline for file: {}", subprocessArgs.filePath());
                IngestPipelineRunner.PipelineResult result = runner.execute(subprocessArgs, reporter, httpCallback);

                // CRITICAL: Flush any pending batched commits before reporting completion
                // With batch commit optimization, some documents may be buffered but not yet
                // committed
                try {
                    Map<String, ai.kompile.core.embeddings.VectorStore> vectorStores = context
                            .getBeansOfType(ai.kompile.core.embeddings.VectorStore.class);
                    for (ai.kompile.core.embeddings.VectorStore vs : vectorStores.values()) {
                        if (!vs.getClass().getSimpleName().contains("NoOp")) {
                            logger.info("Flushing vector store: {}", vs.getClass().getSimpleName());
                            vs.flushAndCommit();
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Error flushing vector stores: {}", e.getMessage());
                }

                // Report completion
                Map<String, Long> phaseDurations = new HashMap<>();
                phaseDurations.put("LOADING", result.loadingDurationMs());
                phaseDurations.put("CHUNKING", result.chunkingDurationMs());
                phaseDurations.put("EMBEDDING", result.embeddingDurationMs());
                phaseDurations.put("INDEXING", result.indexingDurationMs());

                reporter.reportCompleted(
                        result.documentsLoaded(),
                        result.chunksCreated(),
                        result.chunksEmbedded(),
                        result.documentsIndexed(),
                        result.tokensProcessed(),
                        0,  // totalTokensInIndex - would need to query the index
                        subprocessArgs.vectorStorePath() != null ? subprocessArgs.vectorStorePath()
                                : subprocessArgs.indexPath(),
                        phaseDurations);

                httpCallback.markJobCompleted(subprocessArgs.taskId());
                logger.info("Ingest completed successfully");

                // Mark checkpoint as completed and save
                if (currentCheckpoint != null) {
                    currentCheckpoint.markCompleted(result.totalDurationMs());
                    saveCheckpoint();
                    logger.info("Checkpoint marked as completed");
                }
            }

        } catch (InterruptedException e) {
            logger.info("Subprocess interrupted (likely cancelled)");
            if (reporter != null && subprocessArgs != null) {
                reporter.reportFailed("UNKNOWN", "Process interrupted", "InterruptedException", null);
            }
            if (httpCallback != null && subprocessArgs != null) {
                httpCallback.logCancelled(subprocessArgs.taskId(), subprocessArgs.filePath(), "UNKNOWN",
                        "Process interrupted");
            }
            Thread.currentThread().interrupt();
            exitCode = 130; // Standard exit code for interrupted

        } catch (OutOfMemoryError oom) {
            // Handle OOM gracefully - log detailed info and exit cleanly
            // The parent process (SubprocessIngestLauncher) will detect this exit code
            // and initiate adaptive retry with adjusted settings (reduced batch/threads/increased heap)
            logger.error("====================================================================");
            logger.error("OUT OF MEMORY - subprocess will be restarted with adjusted settings");
            logger.error("====================================================================");
            logger.error("Current settings:");
            logger.error("  Heap: max={}MB, used={}MB, free={}MB",
                    Runtime.getRuntime().maxMemory() / (1024 * 1024),
                    (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024),
                    Runtime.getRuntime().freeMemory() / (1024 * 1024));
            logger.error("  Batch size: {}", subprocessArgs != null ? subprocessArgs.embeddingBatchSize() : "unknown");
            if (currentCheckpoint != null) {
                logger.error("  Progress: {} embedded, {} indexed chunks",
                        currentCheckpoint.getEmbeddedCount(), currentCheckpoint.getIndexedCount());
            }
            logger.error("");
            logger.error("The parent process will:");
            logger.error("  1) Reduce batch size by 50% (if above minimum)");
            logger.error("  2) Reduce threads (if batch already at minimum)");
            logger.error("  3) Increase heap size (if threads already at minimum)");
            logger.error("  4) Resume from checkpoint (skip already-processed chunks)");
            logger.error("====================================================================");

            // Save checkpoint before exit so we can resume
            if (currentCheckpoint != null) {
                currentCheckpoint.setCurrentPhase("OOM_RECOVERY");
                saveCheckpoint();
            }

            // Try to recover memory for cleanup
            System.gc();

            String phase = "EMBEDDING";
            String errorMsg = "OutOfMemoryError - parent will retry with adjusted settings";

            if (reporter != null) {
                reporter.reportFailed(phase, errorMsg, "OutOfMemoryError", null);
            }

            if (httpCallback != null && subprocessArgs != null) {
                httpCallback.logFailed(
                        subprocessArgs.taskId(),
                        subprocessArgs.filePath(),
                        phase,
                        errorMsg,
                        getStackTrace(oom));
                // DON'T mark job as failed - let parent handle retry logic
                logger.info("OOM detected - NOT marking job as failed. Parent will handle retry.");
            }

            exitCode = 137; // Standard OOM exit code

        } catch (Exception e) {
            logger.error("Subprocess failed", e);

            String phase = "UNKNOWN";
            if (e instanceof IngestPipelineRunner.PipelineException pe) {
                phase = pe.getPhase();
            }

            // Check if this is an OOM-related exception
            boolean isOomRelated = isOomRelated(e);

            if (reporter != null) {
                reporter.reportFailed(phase, e);
            }

            if (httpCallback != null && subprocessArgs != null) {
                httpCallback.logFailed(
                        subprocessArgs.taskId(),
                        subprocessArgs.filePath(),
                        phase,
                        e.getMessage(),
                        getStackTrace(e));
                // DON'T mark job as failed for OOM-related errors - let parent handle retry
                if (!isOomRelated) {
                    httpCallback.markJobFailed(subprocessArgs.taskId(), phase, e.getMessage(), "UNKNOWN");
                } else {
                    logger.info("OOM-related exception detected - NOT marking job as failed. Parent will handle retry.");
                }
            }

            exitCode = isOomRelated ? 137 : 1;

        } finally {
            // Stop memory watchdog first (before cleanup)
            if (memoryWatchdog != null) {
                memoryWatchdog.close();
                memoryWatchdog = null;
            }

            // Stop heartbeat
            if (reporter != null) {
                reporter.stopHeartbeat();
                reporter.close();
            }

            // Close HTTP callback
            if (httpCallback != null) {
                httpCallback.close();
            }

            // Cleanup ND4J resources
            cleanupNd4j();
        }

        System.exit(exitCode);
    }

    /**
     * Initialize ND4J environment - must be called BEFORE any ND4J/SameDiff
     * operations.
     * This mirrors the initialization in MainApplication.
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
     * Configure the model source for the embedding model.
     * This must be called BEFORE the Spring context is created so that
     * AnseriniEncoderFactory
     * uses the correct model source (staging or archive).
     */
    private static void configureModelSource(SubprocessArgs args) {
        String sourceType = args.modelSourceType();
        String modelId = args.modelIdentifier();

        logger.info("Configuring model source: type={}, modelId={}", sourceType, modelId);

        if (sourceType == null || sourceType.isBlank()) {
            logger.warn("No model source type specified in subprocess args - model loading may fail");
            return;
        }

        try {
            if ("staging".equalsIgnoreCase(sourceType)) {
                // Configure remote staging service
                String stagingUrl = args.stagingUrl();
                String stagingApiKey = args.stagingApiKey();

                if (stagingUrl == null || stagingUrl.isBlank()) {
                    logger.error("Staging source type specified but no staging URL provided");
                    return;
                }

                logger.info("Configuring staging service: url={}", stagingUrl);
                ai.kompile.embedding.anserini.AnseriniEncoderFactory.configureStagingService(stagingUrl, stagingApiKey);
                logger.info("Staging service configured successfully");

            } else if ("archive".equalsIgnoreCase(sourceType)) {
                // Load archive file
                String archivePath = args.archivePath();

                if (archivePath == null || archivePath.isBlank()) {
                    logger.error("Archive source type specified but no archive path provided");
                    return;
                }

                java.nio.file.Path path = java.nio.file.Paths.get(archivePath);
                if (!java.nio.file.Files.exists(path)) {
                    logger.error("Archive file not found: {}", archivePath);
                    return;
                }

                logger.info("Loading archive: {}", archivePath);
                ai.kompile.embedding.anserini.AnseriniEncoderFactory.loadArchive(path);
                logger.info("Archive loaded successfully");

            } else {
                logger.warn("Unknown model source type: {} - expected 'staging' or 'archive'", sourceType);
            }

            // Refresh the registry to pick up the newly configured source
            ai.kompile.embedding.anserini.AnseriniEncoderFactory.refreshRegistry();

            // Log available models after configuration
            java.util.Set<String> availableModels = ai.kompile.embedding.anserini.AnseriniEncoderFactory
                    .getAvailableModelIds();
            logger.info("Available models after configuration: {}", availableModels);

            if (modelId != null && !modelId.isBlank()) {
                boolean available = ai.kompile.embedding.anserini.AnseriniEncoderFactory.isModelAvailable(modelId);
                logger.info("Requested model '{}' available: {}", modelId, available);
                if (!available) {
                    logger.error("Requested model '{}' is NOT available in configured source. Available: {}",
                            modelId, availableModels);
                }
            }

        } catch (Exception e) {
            logger.error("Failed to configure model source: {}", e.getMessage(), e);
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
        logger.info("Applying ND4J environment configuration (subprocess)...");

        try {
            // === CORE SETTINGS (must be set first) ===
            if (config.enableBlas() != null) {
                Nd4j.getEnvironment().setEnableBlas(config.enableBlas());
                logger.debug("Set enableBlas to {}", config.enableBlas());
            }
            if (config.helpersAllowed() != null) {
                Nd4j.getEnvironment().allowHelpers(config.helpersAllowed());
                logger.debug("Set helpersAllowed to {}", config.helpersAllowed());
            }

            // === THREAD CONFIGURATION ===
            if (config.maxThreads() != null) {
                Nd4j.getEnvironment().setMaxThreads(config.maxThreads());
                logger.debug("Set maxThreads to {}", config.maxThreads());
            }
            if (config.maxMasterThreads() != null) {
                Nd4j.getEnvironment().setMaxMasterThreads(config.maxMasterThreads());
                logger.debug("Set maxMasterThreads to {}", config.maxMasterThreads());
            }

            // === DEBUG/VERBOSE MODES ===
            if (config.debug() != null) {
                Nd4j.getEnvironment().setDebug(config.debug());
                logger.debug("Set debug to {}", config.debug());
            }
            if (config.verbose() != null) {
                Nd4j.getEnvironment().setVerbose(config.verbose());
                logger.debug("Set verbose to {}", config.verbose());
            }
            if (config.profiling() != null) {
                Nd4j.getEnvironment().setProfiling(config.profiling());
                logger.debug("Set profiling to {}", config.profiling());
            }
            if (config.leaksDetector() != null) {
                Nd4j.getEnvironment().setLeaksDetector(config.leaksDetector());
                logger.debug("Set leaksDetector to {}", config.leaksDetector());
            }

            // === PERFORMANCE THRESHOLDS ===
            if (config.tadThreshold() != null) {
                Nd4j.getEnvironment().setTadThreshold(config.tadThreshold());
                logger.debug("Set tadThreshold to {}", config.tadThreshold());
            }
            if (config.elementwiseThreshold() != null) {
                Nd4j.getEnvironment().setElementwiseThreshold(config.elementwiseThreshold());
                logger.debug("Set elementwiseThreshold to {}", config.elementwiseThreshold());
            }

            // === MEMORY LIMITS ===
            if (config.maxPrimaryMemory() != null && config.maxPrimaryMemory() > 0) {
                Nd4j.getEnvironment().setMaxPrimaryMemory(config.maxPrimaryMemory());
                logger.debug("Set maxPrimaryMemory to {}", config.maxPrimaryMemory());
            }
            if (config.maxSpecialMemory() != null && config.maxSpecialMemory() > 0) {
                Nd4j.getEnvironment().setMaxSpecialMemory(config.maxSpecialMemory());
                logger.debug("Set maxSpecialMemory to {}", config.maxSpecialMemory());
            }
            if (config.maxDeviceMemory() != null && config.maxDeviceMemory() > 0) {
                Nd4j.getEnvironment().setMaxDeviceMemory(config.maxDeviceMemory());
                logger.debug("Set maxDeviceMemory to {}", config.maxDeviceMemory());
            }

            // === LIFECYCLE TRACKING MASTER SWITCH ===
            if (config.lifecycleTracking() != null) {
                Nd4j.getEnvironment().setLifecycleTracking(config.lifecycleTracking());
                logger.debug("Set lifecycleTracking to {}", config.lifecycleTracking());
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

            logger.info("ND4J environment configuration applied successfully (subprocess)");
            logger.info("  enableBlas={}, helpersAllowed={}, maxThreads={}, maxMasterThreads={}",
                    Nd4j.getEnvironment().isEnableBlas(),
                    Nd4j.getEnvironment().helpersAllowed(),
                    Nd4j.getEnvironment().maxThreads(),
                    Nd4j.getEnvironment().maxMasterThreads());
            logger.info("  debug={}, verbose={}, profiling={}, lifecycleTracking={}",
                    Nd4j.getEnvironment().isDebug(),
                    Nd4j.getEnvironment().isVerbose(),
                    Nd4j.getEnvironment().isProfiling(),
                    Nd4j.getEnvironment().isLifecycleTracking());

        } catch (Exception e) {
            logger.error("Error applying ND4J environment configuration: {}", e.getMessage(), e);
            logger.warn("Falling back to ND4J default settings");
        }
    }

    /**
     * Create the minimal Spring context with only required beans.
     */
    private static AnnotationConfigApplicationContext createContext(SubprocessArgs args) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();

        // CRITICAL: Enable subprocess mode so SubprocessIngestConfiguration is loaded
        // This property activates the @ConditionalOnProperty on
        // SubprocessIngestConfiguration
        context.getEnvironment().getSystemProperties().put("kompile.subprocess.mode", "true");

        // Set properties from args
        context.getEnvironment().getSystemProperties().put("kompile.subprocess.taskId", args.taskId());
        context.getEnvironment().getSystemProperties().put("kompile.subprocess.filePath", args.filePath());

        // Propagate index paths to system properties
        String vsPath = args.vectorStorePath();
        String kwPath = args.keywordIndexPath();

        if (vsPath != null) {
            context.getEnvironment().getSystemProperties().put("kompile.vectorstore.anserini.index-path", vsPath);
        }
        if (kwPath != null) {
            context.getEnvironment().getSystemProperties().put("anserini.indexPath", kwPath);
        }

        // Fallback for legacy indexPath field - only for keyword index
        if (kwPath == null && args.indexPath() != null) {
            context.getEnvironment().getSystemProperties().put("anserini.indexPath", args.indexPath());
        }

        // CRITICAL: Enable the Anserini vector store so it's created (not NoOp
        // fallback)
        context.getEnvironment().getSystemProperties().put("kompile.vectorstore.anserini.enabled", "true");
        // Ensure persistence is enabled so subprocess writes to the same path as main
        // app
        context.getEnvironment().getSystemProperties().put("kompile.vectorstore.anserini.persistence-enabled", "true");

        context.getEnvironment().getSystemProperties().put("kompile.subprocess.chunkSize",
                String.valueOf(args.chunkSize()));
        context.getEnvironment().getSystemProperties().put("kompile.subprocess.chunkOverlap",
                String.valueOf(args.chunkOverlap()));
        context.getEnvironment().getSystemProperties().put("kompile.subprocess.embeddingBatchSize",
                String.valueOf(args.embeddingBatchSize()));

        // Register configuration class
        context.register(SubprocessIngestConfiguration.class);

        // Set active profile
        context.getEnvironment().setActiveProfiles("subprocess");

        // Refresh context
        context.refresh();

        return context;
    }

    /**
     * Log comprehensive subprocess feature set.
     * This provides full visibility into what capabilities the subprocess has.
     */
    private static void logSubprocessFeatures(AnnotationConfigApplicationContext context,
            IngestPipelineRunner runner,
            SubprocessArgs args,
            SubprocessProgressReporter reporter) {
        logger.info("═══════════════════════════════════════════════════════════════════════════════");
        logger.info("                    SUBPROCESS FEATURE SET REPORT");
        logger.info("═══════════════════════════════════════════════════════════════════════════════");

        // Task Info
        logger.info("┌─────────────────────────────────────────────────────────────────────────────┐");
        logger.info("│ TASK CONFIGURATION                                                          │");
        logger.info("├─────────────────────────────────────────────────────────────────────────────┤");
        logger.info("│ Task ID:           {}", args.taskId());
        logger.info("│ File Path:         {}", args.filePath());
        logger.info("│ Vector Path:       {}", args.vectorStorePath());
        logger.info("│ Keyword Path:      {}", args.keywordIndexPath());
        logger.info("│ Legacy Path:       {}", args.indexPath());
        logger.info("│ Chunk Size:        {}", args.chunkSize());
        logger.info("│ Chunk Overlap:     {}", args.chunkOverlap());
        logger.info("│ Embedding Batch:   {}", args.embeddingBatchSize());
        logger.info("│ Loader Name:       {}", args.loaderName() != null ? args.loaderName() : "(auto-detect)");
        logger.info("│ Chunker Name:      {}", args.chunkerName() != null ? args.chunkerName() : "(auto-detect)");
        logger.info("│ Resume Mode:       {}", args.resume());
        logger.info("│ Checkpoint Path:   {}", args.checkpointPath() != null ? args.checkpointPath() : "(none)");
        logger.info("└─────────────────────────────────────────────────────────────────────────────┘");

        // Document Loaders
        logger.info("┌─────────────────────────────────────────────────────────────────────────────┐");
        logger.info("│ DOCUMENT LOADERS                                                            │");
        logger.info("├─────────────────────────────────────────────────────────────────────────────┤");
        try {
            Map<String, ai.kompile.core.loaders.DocumentLoader> loaders = context
                    .getBeansOfType(ai.kompile.core.loaders.DocumentLoader.class);
            if (loaders.isEmpty()) {
                logger.info("│ ⚠ NO DOCUMENT LOADERS AVAILABLE                                            │");
            } else {
                logger.info("│ {} loader(s) available:                                                     ",
                        loaders.size());
                for (Map.Entry<String, ai.kompile.core.loaders.DocumentLoader> entry : loaders.entrySet()) {
                    String name = entry.getValue().getClass().getSimpleName();
                    String bean = entry.getKey();
                    logger.info("│   ✓ {} (bean: {})", name, bean);
                }
            }
        } catch (Exception e) {
            logger.info("│ ⚠ Error listing loaders: {}", e.getMessage());
        }
        logger.info("└─────────────────────────────────────────────────────────────────────────────┘");

        // Text Chunkers
        logger.info("┌─────────────────────────────────────────────────────────────────────────────┐");
        logger.info("│ TEXT CHUNKERS                                                               │");
        logger.info("├─────────────────────────────────────────────────────────────────────────────┤");
        try {
            Map<String, ai.kompile.app.core.chunking.TextChunker> chunkers = context
                    .getBeansOfType(ai.kompile.app.core.chunking.TextChunker.class);
            if (chunkers.isEmpty()) {
                logger.info("│ ⚠ NO TEXT CHUNKERS AVAILABLE                                               │");
            } else {
                logger.info("│ {} chunker(s) available:                                                    ",
                        chunkers.size());
                for (Map.Entry<String, ai.kompile.app.core.chunking.TextChunker> entry : chunkers.entrySet()) {
                    String name = entry.getValue().getClass().getSimpleName();
                    String bean = entry.getKey();
                    logger.info("│   ✓ {} (bean: {})", name, bean);
                }
            }
        } catch (Exception e) {
            logger.info("│ ⚠ Error listing chunkers: {}", e.getMessage());
        }
        logger.info("└─────────────────────────────────────────────────────────────────────────────┘");

        // Embedding Models
        logger.info("┌─────────────────────────────────────────────────────────────────────────────┐");
        logger.info("│ EMBEDDING MODELS                                                            │");
        logger.info("├─────────────────────────────────────────────────────────────────────────────┤");
        try {
            Map<String, ai.kompile.core.embeddings.EmbeddingModel> embeddings = context
                    .getBeansOfType(ai.kompile.core.embeddings.EmbeddingModel.class);
            if (embeddings.isEmpty()) {
                logger.info("│ ⚠ NO EMBEDDING MODELS AVAILABLE                                            │");
            } else {
                logger.info("│ {} embedding model(s) available:                                            ",
                        embeddings.size());
                for (Map.Entry<String, ai.kompile.core.embeddings.EmbeddingModel> entry : embeddings.entrySet()) {
                    ai.kompile.core.embeddings.EmbeddingModel model = entry.getValue();
                    String name = model.getClass().getSimpleName();
                    String bean = entry.getKey();
                    boolean isNoOp = name.contains("NoOp");
                    String marker = isNoOp ? "⚠" : "✓";
                    logger.info("│   {} {} (bean: {})", marker, name, bean);
                    // Log model details if available
                    try {
                        String modelId = model.getModelIdentifier();
                        int dimensions = model.getDimensions();
                        logger.info("│       Model ID: {}", modelId != null ? modelId : "(unknown)");
                        logger.info("│       Dimensions: {}", dimensions > 0 ? dimensions : "(unknown)");

                        // Check if model is in failed state
                        if (model instanceof ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl anseriniModel) {
                            if (anseriniModel
                                    .getModelSource() == ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl.ModelSource.FAILED) {
                                String error = anseriniModel.getInitializationError();
                                logger.error("│       ⚠ MODEL FAILED TO INITIALIZE: {}",
                                        error != null ? error : "Unknown error");
                            }
                        }
                    } catch (Exception ex) {
                        logger.error("│       ⚠ Error getting model info: {}", ex.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            logger.info("│ ⚠ Error listing embedding models: {}", e.getMessage());
        }
        logger.info("└─────────────────────────────────────────────────────────────────────────────┘");

        // Indexer Services
        logger.info("┌─────────────────────────────────────────────────────────────────────────────┐");
        logger.info("│ INDEXER SERVICES                                                            │");
        logger.info("├─────────────────────────────────────────────────────────────────────────────┤");
        try {
            Map<String, ai.kompile.core.indexers.IndexerService> indexers = context
                    .getBeansOfType(ai.kompile.core.indexers.IndexerService.class);
            if (indexers.isEmpty()) {
                logger.info("│ ⚠ NO INDEXER SERVICES AVAILABLE                                            │");
            } else {
                logger.info("│ {} indexer service(s) available:                                            ",
                        indexers.size());
                for (Map.Entry<String, ai.kompile.core.indexers.IndexerService> entry : indexers.entrySet()) {
                    String name = entry.getValue().getClass().getSimpleName();
                    String bean = entry.getKey();
                    boolean isNoOp = name.contains("NoOp");
                    String marker = isNoOp ? "⚠" : "✓";
                    logger.info("│   {} {} (bean: {})", marker, name, bean);
                }
            }
        } catch (Exception e) {
            logger.info("│ ⚠ Error listing indexers: {}", e.getMessage());
        }
        logger.info("└─────────────────────────────────────────────────────────────────────────────┘");

        // Vector Stores
        logger.info("┌─────────────────────────────────────────────────────────────────────────────┐");
        logger.info("│ VECTOR STORES                                                               │");
        logger.info("├─────────────────────────────────────────────────────────────────────────────┤");
        try {
            Map<String, ai.kompile.core.embeddings.VectorStore> vectorStores = context
                    .getBeansOfType(ai.kompile.core.embeddings.VectorStore.class);
            if (vectorStores.isEmpty()) {
                logger.info("│ ⚠ NO VECTOR STORES AVAILABLE                                               │");
            } else {
                logger.info("│ {} vector store(s) available:                                               ",
                        vectorStores.size());
                for (Map.Entry<String, ai.kompile.core.embeddings.VectorStore> entry : vectorStores.entrySet()) {
                    String name = entry.getValue().getClass().getSimpleName();
                    String bean = entry.getKey();
                    boolean isNoOp = name.contains("NoOp");
                    String marker = isNoOp ? "⚠" : "✓";
                    logger.info("│   {} {} (bean: {})", marker, name, bean);
                }
            }
        } catch (Exception e) {
            logger.info("│ ⚠ Error listing vector stores: {}", e.getMessage());
        }
        logger.info("└─────────────────────────────────────────────────────────────────────────────┘");

        // ND4J Environment
        logger.info("┌─────────────────────────────────────────────────────────────────────────────┐");
        logger.info("│ ND4J ENVIRONMENT                                                            │");
        logger.info("├─────────────────────────────────────────────────────────────────────────────┤");
        try {
            logger.info("│ Backend:           {}", Nd4j.getBackend().getClass().getSimpleName());
            logger.info("│ Max Threads:       {}", Nd4j.getEnvironment().maxThreads());
            logger.info("│ Max Master Threads:{}", Nd4j.getEnvironment().maxMasterThreads());
            logger.info("│ BLAS Enabled:      {}", Nd4j.getEnvironment().isEnableBlas());
            logger.info("│ Helpers Allowed:   {}", Nd4j.getEnvironment().helpersAllowed());
            logger.info("│ Debug Mode:        {}", Nd4j.getEnvironment().isDebug());
            logger.info("│ Verbose Mode:      {}", Nd4j.getEnvironment().isVerbose());
            logger.info("│ Profiling:         {}", Nd4j.getEnvironment().isProfiling());
            logger.info("│ Lifecycle Tracking:{}", Nd4j.getEnvironment().isLifecycleTracking());
            logger.info("│ Leak Detector:     {}", Nd4j.getEnvironment().isDetectingLeaks());
        } catch (Exception e) {
            logger.info("│ ⚠ Error reading ND4J environment: {}", e.getMessage());
        }
        logger.info("└─────────────────────────────────────────────────────────────────────────────┘");

        // System Resources
        logger.info("┌─────────────────────────────────────────────────────────────────────────────┐");
        logger.info("│ SYSTEM RESOURCES                                                            │");
        logger.info("├─────────────────────────────────────────────────────────────────────────────┤");
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory() / (1024 * 1024);
        long totalMemory = runtime.totalMemory() / (1024 * 1024);
        long freeMemory = runtime.freeMemory() / (1024 * 1024);
        long usedMemory = totalMemory - freeMemory;
        int availableProcessors = runtime.availableProcessors();
        logger.info("│ Available CPUs:    {}", availableProcessors);
        logger.info("│ Max Heap:          {} MB", maxMemory);
        logger.info("│ Total Heap:        {} MB", totalMemory);
        logger.info("│ Used Heap:         {} MB", usedMemory);
        logger.info("│ Free Heap:         {} MB", freeMemory);
        logger.info("│ Java Version:      {}", System.getProperty("java.version"));
        logger.info("│ OS:                {} {}", System.getProperty("os.name"), System.getProperty("os.arch"));
        logger.info("└─────────────────────────────────────────────────────────────────────────────┘");

        // Feature Summary
        logger.info("┌─────────────────────────────────────────────────────────────────────────────┐");
        logger.info("│ FEATURE SUMMARY                                                             │");
        logger.info("├─────────────────────────────────────────────────────────────────────────────┤");
        try {
            Map<String, ai.kompile.core.loaders.DocumentLoader> loaders = context
                    .getBeansOfType(ai.kompile.core.loaders.DocumentLoader.class);
            Map<String, ai.kompile.app.core.chunking.TextChunker> chunkers = context
                    .getBeansOfType(ai.kompile.app.core.chunking.TextChunker.class);
            Map<String, ai.kompile.core.embeddings.EmbeddingModel> embeddings = context
                    .getBeansOfType(ai.kompile.core.embeddings.EmbeddingModel.class);
            Map<String, ai.kompile.core.indexers.IndexerService> indexers = context
                    .getBeansOfType(ai.kompile.core.indexers.IndexerService.class);
            Map<String, ai.kompile.core.embeddings.VectorStore> vectorStores = context
                    .getBeansOfType(ai.kompile.core.embeddings.VectorStore.class);

            // Count non-NoOp implementations
            long realEmbeddings = embeddings.values().stream()
                    .filter(e -> !e.getClass().getSimpleName().contains("NoOp"))
                    .count();
            long realIndexers = indexers.values().stream()
                    .filter(i -> !i.getClass().getSimpleName().contains("NoOp"))
                    .count();
            long realVectorStores = vectorStores.values().stream()
                    .filter(v -> !v.getClass().getSimpleName().contains("NoOp"))
                    .count();

            boolean fullyFunctional = realEmbeddings > 0 && realIndexers > 0 && !loaders.isEmpty()
                    && !chunkers.isEmpty();

            logger.info("│ Document Loading:  {} (loaders: {})", loaders.isEmpty() ? "✗" : "✓", loaders.size());
            logger.info("│ Text Chunking:     {} (chunkers: {})", chunkers.isEmpty() ? "✗" : "✓", chunkers.size());
            logger.info("│ Embedding:         {} (models: {}, real: {})", realEmbeddings == 0 ? "✗" : "✓",
                    embeddings.size(), realEmbeddings);
            logger.info("│ Keyword Indexing:  {} (indexers: {}, real: {})", realIndexers == 0 ? "✗" : "✓",
                    indexers.size(), realIndexers);
            logger.info("│ Vector Storage:    {} (stores: {}, real: {})", realVectorStores == 0 ? "✗" : "✓",
                    vectorStores.size(), realVectorStores);
            logger.info("├─────────────────────────────────────────────────────────────────────────────┤");
            if (fullyFunctional) {
                logger.info("│ ✓ SUBPROCESS IS FULLY FUNCTIONAL                                           │");
            } else {
                logger.info("│ ⚠ SUBPROCESS HAS LIMITED FUNCTIONALITY                                     │");
                if (realEmbeddings == 0)
                    logger.info("│   - Missing real embedding model                                           │");
                if (realIndexers == 0)
                    logger.info("│   - Missing real indexer service                                           │");
                if (loaders.isEmpty())
                    logger.info("│   - No document loaders available                                          │");
                if (chunkers.isEmpty())
                    logger.info("│   - No text chunkers available                                            │");
            }
        } catch (Exception e) {
            logger.info("│ ⚠ Error generating summary: {}", e.getMessage());
        }
        logger.info("└─────────────────────────────────────────────────────────────────────────────┘");
        logger.info("═══════════════════════════════════════════════════════════════════════════════");

        // Also report via subprocess protocol for main app visibility
        reporter.reportLog("INFO", "Subprocess feature set initialized - see subprocess logs for details");
    }

    /**
     * Cleanup ND4J resources before exit.
     */
    private static void cleanupNd4j() {
        try {
            logger.info("Cleaning up ND4J resources...");

            // Destroy workspaces
            Nd4j.getWorkspaceManager().destroyAllWorkspacesForCurrentThread();

            // Release memory context
            Nd4j.getMemoryManager().releaseCurrentContext();

            // Cleanup operation prototypes
            DifferentialFunctionClassHolder.cleanup();

            // Clear caches
            try {
                Nd4j.getNativeOps().clearTADCache();
                Nd4j.getNativeOps().clearShapeCache();
            } catch (Exception e) {
                logger.debug("Could not clear native caches", e);
            }

            logger.info("ND4J cleanup completed");

        } catch (Exception e) {
            logger.warn("Error during ND4J cleanup", e);
        }
    }

    /**
     * Check if an exception is OOM-related (either is OOM or wraps OOM or has OOM in message).
     */
    private static boolean isOomRelated(Throwable e) {
        if (e == null) return false;

        // Check if it's an OutOfMemoryError
        if (e instanceof OutOfMemoryError) return true;

        // Check message for OOM indicators
        String msg = e.getMessage();
        if (msg != null) {
            String upper = msg.toUpperCase();
            if (upper.contains("OUTOFMEMORY") ||
                upper.contains("OUT OF MEMORY") ||
                upper.contains("JAVA HEAP SPACE") ||
                upper.contains("GC OVERHEAD LIMIT") ||
                upper.contains("HEAP EXHAUSTED")) {
                return true;
            }
        }

        // Check cause chain
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof OutOfMemoryError) return true;
            String causeMsg = cause.getMessage();
            if (causeMsg != null) {
                String upper = causeMsg.toUpperCase();
                if (upper.contains("OUTOFMEMORY") || upper.contains("OUT OF MEMORY")) {
                    return true;
                }
            }
            cause = cause.getCause();
        }

        return false;
    }

    /**
     * Get stack trace as string.
     */
    private static String getStackTrace(Throwable e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.toString()).append("\n");
        for (StackTraceElement element : e.getStackTrace()) {
            sb.append("\tat ").append(element).append("\n");
        }
        if (e.getCause() != null) {
            sb.append("Caused by: ").append(e.getCause().toString()).append("\n");
        }
        return sb.toString();
    }
}
