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

package ai.kompile.embedding.anserini.subprocess;

import ai.kompile.app.config.NativeLibraryResolver;
import ai.kompile.app.subprocess.SubprocessMemoryWatchdog;
import ai.kompile.embedding.anserini.AnseriniEncoderFactory;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.anserini.encoder.samediff.ArcticEmbedSameDiffEncoder;
import io.anserini.encoder.samediff.BgeSameDiffEncoder;
import io.anserini.encoder.samediff.GenericDenseSameDiffEncoder;
import io.anserini.encoder.samediff.SameDiffEncoder;
import org.nd4j.common.config.ND4JSystemProperties;
import org.nd4j.imports.converters.DifferentialFunctionClassHolder;
import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Main entry point for the embedding subprocess.
 *
 * This is a standalone process that:
 * 1. Initializes ND4J/SameDiff environment (isolated from main application)
 * 2. Loads embedding models
 * 3. Listens for JSON commands on stdin
 * 4. Returns results via stdout with EMBEDDING_MSG: prefix
 * 5. Reports progress, phase transitions, heartbeats, and logs via the message protocol
 *
 * All SameDiff/ND4J operations happen here - the main application JVM
 * never loads these native libraries.
 *
 * This follows the same patterns as IngestSubprocessMain for consistent
 * progress reporting and logging infrastructure.
 *
 * Usage:
 * java -cp <classpath> ai.kompile.embedding.anserini.subprocess.EmbeddingSubprocessMain
 *
 * Commands are sent as JSON on stdin, responses are written to stdout.
 */
public class EmbeddingSubprocessMain {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddingSubprocessMain.class);
    private static final ObjectMapper OBJECT_MAPPER = JsonUtils.standardMapper();

    // Original stdout for protocol messages (stderr for logging)
    private static PrintStream originalStdout;

    // Lock for stdout writes — originalStdout is a non-final static field assigned
    // in main(), so we must never synchronize on the field reference itself.
    private static final Object stdoutLock = new Object();

    // Current encoder state
    private static volatile SameDiffEncoder<float[]> encoder;
    private static volatile String currentModelId;
    private static volatile int currentDimensions = -1;
    private static volatile String modelSource = "NOT_LOADED";
    private static volatile String encoderType = "UNKNOWN";
    private static volatile boolean modelLoaded = false;
    private static volatile boolean loading = false;
    private static volatile String loadingPhase = "IDLE";

    // Batch configuration
    private static volatile int optimalBatchSize = 32;
    private static volatile int maxBatchSize = 64;
    private static final double MIN_EMBEDDING_MAGNITUDE = 1e-18;

    // Statistics tracking
    private static final AtomicLong totalEmbeddingsProcessed = new AtomicLong(0);
    private static final AtomicLong totalBatchesProcessed = new AtomicLong(0);
    private static final AtomicLong totalTokensProcessed = new AtomicLong(0);
    private static final AtomicLong totalEmbedTimeMs = new AtomicLong(0);

    // Heartbeat
    private static ScheduledExecutorService heartbeatExecutor;
    private static long startTime;
    
    // Memory watchdog for monitoring heap and GPU memory
    private static volatile SubprocessMemoryWatchdog memoryWatchdog;

    // Current phase tracking
    private static volatile String currentPhase = "INITIALIZING";

    // ── Request scheduling (priority lane) ──────────────────────────────────
    // The reader thread only parses + classifies incoming messages; a single
    // compute worker drains this priority lane. Keeping every encoder call on
    // one worker preserves serialization (the SameDiff encoder is NOT
    // concurrency-safe), while the priority ordering gives latency-sensitive
    // work (single embeds, model load, small batches) a fast lane ahead of large
    // throughput batches so a giant crawl batch can't head-of-line-block an
    // interactive query embed.
    private static final int PRIORITY_URGENT = -1;  // graceful shutdown — runs right after the in-flight op
    private static final int PRIORITY_FAST = 0;     // single embeds, model load, op-timing, small batches
    private static final int PRIORITY_NORMAL = 1;   // large throughput batches
    private static final AtomicLong requestSequence = new AtomicLong(0);
    private static final PriorityBlockingQueue<PendingRequest> computeQueue =
            new PriorityBlockingQueue<>(64,
                    Comparator.comparingInt(PendingRequest::priority)
                            .thenComparingLong(PendingRequest::sequence));
    private static volatile Thread computeWorker;

    // ── Device-level CUDA error detection ────────────────────────────────────
    static final int DEVICE_ERROR_EXIT_CODE = 78;
    static final String DEVICE_FATAL_PREFIX = "EMBEDDING_DEVICE_FATAL:";
    /** Set when a CUDA device-level error has poisoned the context; all subsequent ops will fail. */
    private static final java.util.concurrent.atomic.AtomicBoolean deviceContextPoisoned =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** A queued compute-plane request, ordered by (priority, FIFO sequence). */
    private record PendingRequest(int priority, long sequence, EmbeddingSubprocessMessage message) {}

    public static void main(String[] args) {
        NativeLibraryResolver.bootstrapOrThrow();
        // Capture original stdout for protocol messages
        originalStdout = System.out;

        // Redirect System.out to stderr so logging doesn't interfere with protocol
        System.setOut(System.err);

        startTime = System.currentTimeMillis();

        try {
            // Send initial phase transition
            sendPhaseTransition(null, "INITIALIZING", 0);
            sendProgress("INITIALIZING", 0, "Starting subprocess", "Embedding subprocess starting...");
            sendLog("INFO", "EmbeddingSubprocessMain", "Subprocess starting");

            logger.info("===============================================================================");
            logger.info("          EMBEDDING SUBPROCESS STARTING");
            logger.info("===============================================================================");
            logger.info("PID: {}", ProcessHandle.current().pid());
            logger.info("Java Version: {}", System.getProperty("java.version"));
            logger.info("Java Vendor: {}", System.getProperty("java.vendor"));
            logger.info("Max Heap: {} MB", Runtime.getRuntime().maxMemory() / (1024 * 1024));
            logger.info("Available Processors: {}", Runtime.getRuntime().availableProcessors());

            // Initialize ND4J environment FIRST - before any model operations
            sendProgress("INITIALIZING", 10, "Initializing ND4J", "Setting up native backends...");
            logger.info("Initializing ND4J environment...");
            initializeNd4j();
            sendProgress("INITIALIZING", 40, "ND4J ready", "Native backend initialized");

            // Initialize memory watchdog with configurable GPU thresholds
            int gpuStopPercent = Integer.getInteger("kompile.subprocess.memory.gpu-stop-threshold-percent", 75);
            int gpuCriticalPercent = Integer.getInteger("kompile.subprocess.memory.gpu-critical-threshold-percent", 85);
            int gpuKillPercent = Integer.getInteger("kompile.subprocess.memory.gpu-kill-threshold-percent", 92);
            memoryWatchdog = new SubprocessMemoryWatchdog(
                    80, 90, 95,  // Heap thresholds: stop=80%, critical=90%, kill=95%
                    2000,        // Check interval: 2 seconds
                    gpuStopPercent, gpuCriticalPercent, gpuKillPercent,
                    80, 90, 95   // Off-heap thresholds: stop=80%, critical=90%, kill=95%
            );
            memoryWatchdog.start();
            logger.info("Memory watchdog started: heap stop=80%, critical=90%, kill=95%; GPU stop={}%, critical={}%, kill={}%; off-heap stop=80%, critical=90%, kill=95%",
                    gpuStopPercent, gpuCriticalPercent, gpuKillPercent);

            // Configure model source from system properties (passed from main process)
            // MUST happen AFTER ND4J init since registry may use ND4J
            sendProgress("INITIALIZING", 50, "Configuring model source", "Reading configuration from main process...");
            configureModelSource();

            // Log feature set
            logSubprocessFeatures();

            // Start heartbeat thread
            sendProgress("INITIALIZING", 80, "Starting heartbeat", "Initializing health monitoring...");
            startHeartbeat();

            // Ready for commands
            sendPhaseTransition("INITIALIZING", "IDLE", System.currentTimeMillis() - startTime);
            sendProgress("IDLE", 100, "Ready", "Waiting for commands...");
            sendLog("INFO", "EmbeddingSubprocessMain", "Subprocess ready, entering command loop");

            // Enter command loop
            logger.info("Entering command loop - waiting for requests on stdin...");
            commandLoop();

        } catch (Exception e) {
            logger.error("Fatal error in embedding subprocess", e);
            sendError(null, e, "FATAL");
            System.exit(1);
        } finally {
            cleanup();
        }

        System.exit(0);
    }

    /**
     * Configure model source from system properties passed by the main process.
     * This allows the subprocess to find models via staging service or loaded archives.
     */
    private static void configureModelSource() {
        logger.info("Configuring model source from system properties...");

        // Read staging service configuration
        String stagingUrl = System.getProperty("kompile.staging.url");
        String stagingApiKey = System.getProperty("kompile.staging.apiKey");
        String archivePath = System.getProperty("kompile.models.archivePath");

        boolean configured = false;

        // Configure staging service if URL is provided
        if (stagingUrl != null && !stagingUrl.isBlank()) {
            logger.info("Configuring staging service: {}", stagingUrl);
            sendLog("INFO", "ModelConfig", "Configuring staging service: " + stagingUrl);
            AnseriniEncoderFactory.configureStagingService(stagingUrl, stagingApiKey);
            configured = true;
        }

        // Load archive if path is provided
        if (archivePath != null && !archivePath.isBlank()) {
            java.nio.file.Path path = java.nio.file.Paths.get(archivePath);
            if (java.nio.file.Files.exists(path)) {
                logger.info("Loading model archive: {}", archivePath);
                sendLog("INFO", "ModelConfig", "Loading model archive: " + archivePath);
                try {
                    AnseriniEncoderFactory.loadArchive(path);
                    configured = true;
                } catch (Exception e) {
                    logger.warn("Failed to load archive: {}", e.getMessage());
                    sendLog("WARN", "ModelConfig", "Failed to load archive: " + e.getMessage());
                }
            } else {
                logger.warn("Archive path does not exist: {}", archivePath);
                sendLog("WARN", "ModelConfig", "Archive path does not exist: " + archivePath);
            }
        }

        // Refresh registry to pick up any configured sources
        if (configured) {
            logger.info("Refreshing model registry...");
            AnseriniEncoderFactory.refreshRegistry();
        }

        // Log configured source
        String sourceType = AnseriniEncoderFactory.getSourceType();
        logger.info("Model source configured: {}", sourceType);
        sendLog("INFO", "ModelConfig", "Model source: " + sourceType);
    }

    // Track whether Nd4j was successfully initialized (for safe cleanup)
    private static volatile boolean nd4jInitialized = false;

    /**
     * Initialize ND4J environment - must be called BEFORE any ND4J/SameDiff operations.
     * Uses configuration inherited from parent process via system properties.
     * Does NOT override any settings - uses exactly what parent configured.
     */
    private static void initializeNd4j() throws Exception {
        logger.info("Initializing ND4J backend and environment...");
        sendLog("INFO", "ND4J", "Initializing ND4J for subprocess (using parent configuration)...");

        // Log relevant system properties for debugging
        logger.info("ND4J System Properties:");
        sendLog("DEBUG", "ND4J", "ND4J System Properties:");
        for (String prop : new String[]{
                "org.nd4j.linalg.defaultbackend",
                "org.nd4j.backend",
                "org.bytedeco.javacpp.platform",
                "org.bytedeco.javacpp.cachedir",
                "java.library.path"
        }) {
            String value = System.getProperty(prop);
            if (value != null) {
                logger.info("  {}={}", prop, value);
                sendLog("DEBUG", "ND4J", "  " + prop + "=" + value);
            }
        }

        // CRITICAL: Initialize Nd4j FIRST via scalar() to properly trigger backend loading
        // This must happen BEFORE DifferentialFunctionClassHolder because that class
        // tries to instantiate op classes which reference Nd4j in their constructors.
        // If Nd4j isn't already initialized, those op instantiations trigger partial
        // Nd4j initialization that fails.
        sendLog("INFO", "ND4J", "Triggering backend initialization...");
        try {
            // This triggers full Nd4j initialization including NativeOps loading
            logger.info("Calling Nd4j.scalar(0.0f) to trigger initialization...");
            Nd4j.scalar(0.0f);
            nd4jInitialized = true;

            String backendName = Nd4j.getBackend() != null ?
                    Nd4j.getBackend().getClass().getSimpleName() : "UNKNOWN";
            sendLog("INFO", "ND4J", "Backend loaded: " + backendName);
            logger.info("ND4J backend initialized: {}", backendName);

            // Apply ND4J environment configuration from parent JVM
            applyNd4jEnvironmentFromProperties();

        } catch (ExceptionInInitializerError e) {
            // This is the real error - log it fully before re-throwing
            logger.error("ND4J ExceptionInInitializerError during backend initialization", e);
            if (e.getCause() != null) {
                logger.error("Caused by: {}", e.getCause().getClass().getName(), e.getCause());
            }
            sendError(null, "ND4J initialization failed: " + e.getMessage() +
                    (e.getCause() != null ? " Caused by: " + e.getCause().getMessage() : ""),
                    "ND4J_INIT", "INITIALIZING");
            throw e;
        } catch (NoClassDefFoundError e) {
            // This often wraps an ExceptionInInitializerError
            logger.error("ND4J NoClassDefFoundError during backend initialization", e);
            if (e.getCause() != null) {
                logger.error("Caused by: {}", e.getCause().getClass().getName(), e.getCause());
            }
            sendError(null, "ND4J class loading failed: " + e.getMessage() +
                    (e.getCause() != null ? " Caused by: " + e.getCause().getMessage() : ""),
                    "ND4J_INIT", "INITIALIZING");
            throw e;
        } catch (Exception e) {
            logger.error("Failed to initialize Nd4j backend", e);
            sendError(null, "Failed to initialize Nd4j backend: " + e.getMessage(), "ND4J_INIT", "INITIALIZING");
            throw e;
        }

        // NOW initialize DifferentialFunctionClassHolder - after Nd4j is ready
        sendLog("INFO", "ND4J", "Initializing DifferentialFunctionClassHolder (Nd4j already loaded)");
        try {
            DifferentialFunctionClassHolder.initInstance();
        } catch (Exception e) {
            logger.error("Failed to initialize DifferentialFunctionClassHolder", e);
            sendError(null, "Failed to initialize DifferentialFunctionClassHolder: " + e.getMessage(),
                    "ND4J_INIT", "INITIALIZING");
            throw e;
        }

        String backendName = Nd4j.getBackend() != null ?
                Nd4j.getBackend().getClass().getSimpleName() : "UNKNOWN";
        int maxThreads = Nd4j.getEnvironment().maxThreads();
        logger.info("ND4J fully initialized: backend={}, maxThreads={}",
                backendName, maxThreads);
        sendLog("INFO", "ND4J", "ND4J fully initialized: backend=" + backendName + ", maxThreads=" + maxThreads);
    }

    /**
     * Apply ND4J environment configuration from system properties passed by parent JVM.
     * This ensures the subprocess has the exact same ND4J environment as the parent.
     */
    private static void applyNd4jEnvironmentFromProperties() {
        try {
            org.nd4j.linalg.factory.Environment env = Nd4j.getEnvironment();
            if (env == null) {
                logger.warn("Nd4j.getEnvironment() returned null, cannot apply environment config");
                return;
            }

            // Apply all boolean flags from parent
            if ("true".equalsIgnoreCase(System.getProperty("nd4j.environment.verbose"))) {
                env.setVerbose(true);
            }
            if ("true".equalsIgnoreCase(System.getProperty("nd4j.environment.debug"))) {
                env.setDebug(true);
            }
            if ("true".equalsIgnoreCase(System.getProperty("nd4j.environment.profiling"))) {
                env.setProfiling(true);
            }
            if ("true".equalsIgnoreCase(System.getProperty("nd4j.environment.detectingLeaks"))) {
                env.setLeaksDetector(true);
            }
            if ("true".equalsIgnoreCase(System.getProperty("nd4j.environment.lifecycleTracking"))) {
                env.setLifecycleTracking(true);
            }
            if ("true".equalsIgnoreCase(System.getProperty("nd4j.environment.trackViews"))) {
                env.setTrackViews(true);
            }
            if ("true".equalsIgnoreCase(System.getProperty("nd4j.environment.trackDeletions"))) {
                env.setTrackDeletions(true);
            }
            if ("true".equalsIgnoreCase(System.getProperty("nd4j.environment.trackOperations"))) {
                env.setTrackOperations(true);
            }
            if ("true".equalsIgnoreCase(System.getProperty("nd4j.environment.ndArrayTracking"))) {
                env.setNDArrayTracking(true);
            }
            if ("true".equalsIgnoreCase(System.getProperty("nd4j.environment.dataBufferTracking"))) {
                env.setDataBufferTracking(true);
            }
            if ("true".equalsIgnoreCase(System.getProperty("nd4j.environment.tadCacheTracking"))) {
                env.setTADCacheTracking(true);
            }
            if ("true".equalsIgnoreCase(System.getProperty("nd4j.environment.shapeCacheTracking"))) {
                env.setShapeCacheTracking(true);
            }
            if ("true".equalsIgnoreCase(System.getProperty("nd4j.environment.opContextTracking"))) {
                env.setOpContextTracking(true);
            }

            // Apply OpExecutioner NaN/Inf profiling mode
            String nanCheck = System.getProperty("nd4j.opProfiler.nanCheck");
            if ("true".equalsIgnoreCase(nanCheck)) {
                logger.info("Enabling NAN_PANIC profiling mode — will throw on first NaN-producing op");
                org.nd4j.linalg.factory.Nd4j.getExecutioner().setProfilingMode(
                        org.nd4j.linalg.api.ops.executioner.OpExecutioner.ProfilingMode.NAN_PANIC);
            }

            // Apply thread settings
            String maxThreads = System.getProperty("nd4j.environment.maxThreads");
            if (maxThreads != null) {
                try {
                    env.setMaxThreads(Integer.parseInt(maxThreads));
                } catch (NumberFormatException e) {
                    logger.warn("Invalid nd4j.environment.maxThreads value '{}': {}", maxThreads, e.getMessage());
                }
            }
            String maxMasterThreads = System.getProperty("nd4j.environment.maxMasterThreads");
            if (maxMasterThreads != null) {
                try {
                    env.setMaxMasterThreads(Integer.parseInt(maxMasterThreads));
                } catch (NumberFormatException e) {
                    logger.warn("Invalid nd4j.environment.maxMasterThreads value '{}': {}", maxMasterThreads, e.getMessage());
                }
            }

            applyLongMemoryProperty(ND4JSystemProperties.ENV_MAX_PRIMARY_MEMORY,
                    "maxPrimaryMemory", env::setMaxPrimaryMemory);
            applyLongMemoryProperty(ND4JSystemProperties.ENV_MAX_SPECIAL_MEMORY,
                    "maxSpecialMemory", env::setMaxSpecialMemory);
            applyLongMemoryProperty(ND4JSystemProperties.ENV_MAX_DEVICE_MEMORY,
                    "maxDeviceMemory", env::setMaxDeviceMemory);

            logger.info("Applied ND4J environment configuration from parent JVM");
            sendLog("INFO", "ND4J", "Applied ND4J environment from parent");

        } catch (Exception e) {
            logger.warn("Failed to apply ND4J environment from properties: {}", e.getMessage());
        }
    }

    private static void applyLongMemoryProperty(String propertyName, String label,
                                                java.util.function.LongConsumer setter) {
        String rawValue = System.getProperty(propertyName);
        if (rawValue == null || rawValue.isBlank()) {
            return;
        }
        try {
            long bytes = Long.parseLong(rawValue.trim());
            if (bytes <= 0L) {
                logger.debug("Ignoring non-positive ND4J {} cap from {}={}", label, propertyName, rawValue);
                return;
            }
            setter.accept(bytes);
            long mib = bytes / (1024L * 1024L);
            logger.info("Applied ND4J {} cap from {}: {} bytes ({} MiB)", label, propertyName, bytes, mib);
            sendLog("INFO", "ND4J", "Applied " + label + " cap: " + mib + " MiB");
        } catch (NumberFormatException e) {
            logger.warn("Invalid {} value '{}': {}", propertyName, rawValue, e.getMessage());
        }
    }

    private static String formatMemoryCapMiB(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return "unbounded";
        }
        try {
            long bytes = Long.parseLong(rawValue.trim());
            if (bytes <= 0L) {
                return "unbounded";
            }
            return (bytes / (1024L * 1024L)) + " MiB";
        } catch (NumberFormatException e) {
            return "invalid(" + rawValue + ")";
        }
    }

    /**
     * Log subprocess feature set - similar to IngestSubprocessMain.
     */
    private static void logSubprocessFeatures() {
        logger.info("===============================================================================");
        logger.info("                    EMBEDDING SUBPROCESS FEATURE SET");
        logger.info("===============================================================================");

        logger.info("+-----------------------------------------------------------------------------+");
        logger.info("| ND4J ENVIRONMENT                                                            |");
        logger.info("+-----------------------------------------------------------------------------+");
        try {
            String backend = Nd4j.getBackend().getClass().getSimpleName();
            int maxThreads = Nd4j.getEnvironment().maxThreads();
            int maxMasterThreads = Nd4j.getEnvironment().maxMasterThreads();
            boolean blasEnabled = Nd4j.getEnvironment().isEnableBlas();
            boolean helpersAllowed = Nd4j.getEnvironment().helpersAllowed();
            boolean debugMode = Nd4j.getEnvironment().isDebug();
            boolean verboseMode = Nd4j.getEnvironment().isVerbose();
            String maxDeviceMemory = System.getProperty(ND4JSystemProperties.ENV_MAX_DEVICE_MEMORY);
            String maxDeviceMemoryDisplay = formatMemoryCapMiB(maxDeviceMemory);

            logger.info("| Backend:            {}", backend);
            logger.info("| Max Threads:        {}", maxThreads);
            logger.info("| Max Master Threads: {}", maxMasterThreads);
            logger.info("| Max Device Memory:  {}", maxDeviceMemoryDisplay);
            logger.info("| BLAS Enabled:       {}", blasEnabled);
            logger.info("| Helpers Allowed:    {}", helpersAllowed);
            logger.info("| Debug Mode:         {}", debugMode);
            logger.info("| Verbose Mode:       {}", verboseMode);

            // Send ND4J environment info to UI
            sendLog("INFO", "ND4J", "ND4J Environment: backend=" + backend + ", maxThreads=" + maxThreads +
                    ", maxMasterThreads=" + maxMasterThreads + ", maxDeviceMemory=" + maxDeviceMemoryDisplay +
                    ", BLAS=" + blasEnabled);
        } catch (Exception e) {
            logger.info("| Error reading ND4J environment: {}", e.getMessage());
            sendLog("WARN", "ND4J", "Error reading ND4J environment: " + e.getMessage());
        }
        logger.info("+-----------------------------------------------------------------------------+");

        logger.info("+-----------------------------------------------------------------------------+");
        logger.info("| SYSTEM RESOURCES                                                            |");
        logger.info("+-----------------------------------------------------------------------------+");
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory() / (1024 * 1024);
        long totalMemory = runtime.totalMemory() / (1024 * 1024);
        long freeMemory = runtime.freeMemory() / (1024 * 1024);
        long usedMemory = totalMemory - freeMemory;
        int cpus = runtime.availableProcessors();
        String javaVersion = System.getProperty("java.version");
        String os = System.getProperty("os.name") + " " + System.getProperty("os.arch");

        logger.info("| Available CPUs:     {}", cpus);
        logger.info("| Max Heap:           {} MB", maxMemory);
        logger.info("| Total Heap:         {} MB", totalMemory);
        logger.info("| Used Heap:          {} MB", usedMemory);
        logger.info("| Free Heap:          {} MB", freeMemory);
        logger.info("| Java Version:       {}", javaVersion);
        logger.info("| OS:                 {}", os);
        logger.info("+-----------------------------------------------------------------------------+");

        // Send system resources info to UI
        sendLog("INFO", "System", "System Resources: CPUs=" + cpus + ", maxHeap=" + maxMemory + "MB, usedHeap=" + usedMemory + "MB, Java=" + javaVersion);

        logger.info("+-----------------------------------------------------------------------------+");
        logger.info("| AVAILABLE EMBEDDING MODELS                                                  |");
        logger.info("+-----------------------------------------------------------------------------+");
        try {
            var availableModels = AnseriniEncoderFactory.getAvailableModelIds();
            if (availableModels.isEmpty()) {
                logger.info("| No models available yet (configure source first)                          |");
            } else {
                logger.info("| {} model(s) available:", availableModels.size());
                for (String modelId : availableModels) {
                    Integer dim = AnseriniEncoderFactory.getEmbeddingDimension(modelId);
                    logger.info("|   - {} (dim: {})", modelId, dim != null ? dim : "unknown");
                }
            }
        } catch (Exception e) {
            logger.info("| Error listing models: {}", e.getMessage());
        }
        logger.info("+-----------------------------------------------------------------------------+");
        logger.info("===============================================================================");
    }

    /**
     * Start the heartbeat thread for liveness monitoring.
     */
    private static void startHeartbeat() {
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "embedding-heartbeat");
            t.setDaemon(true);
            return t;
        });

        heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                sendHeartbeat();
            } catch (Exception e) {
                logger.warn("Error sending heartbeat: {}", e.getMessage());
            }
        }, 10, 10, TimeUnit.SECONDS);

        logger.info("Heartbeat thread started (10s interval)");
        sendLog("INFO", "Heartbeat", "Heartbeat thread started with 10s interval");
    }

    /**
     * Send a heartbeat message.
     */
    private static void sendHeartbeat() {
        long uptimeMs = System.currentTimeMillis() - startTime;
        long total = totalEmbeddingsProcessed.get();
        long totalTime = totalEmbedTimeMs.get();
        double avgEmbedTime = total > 0 ? (double) totalTime / total : 0.0;

        EmbeddingSubprocessMessage.Heartbeat heartbeat = EmbeddingSubprocessMessage.heartbeat(
                uptimeMs, modelLoaded, currentModelId, total, avgEmbedTime);
        sendMessage(heartbeat);
    }

    /**
     * Main command loop - reads JSON commands from stdin and processes them.
     */
    private static void commandLoop() {
        // Start the single compute worker that drains the priority lane. Doing the
        // encoder work off the reader thread means a big in-flight batch can never
        // stall stdin draining (which would back-pressure the pipe and delay every
        // subsequent request, including interactive query embeds).
        startComputeWorker();

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String line;

        try {
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                EmbeddingSubprocessMessage message;
                try {
                    message = OBJECT_MAPPER.readValue(line, EmbeddingSubprocessMessage.class);
                } catch (Exception e) {
                    logger.error("Error processing message: {} - {}", line, e.getMessage());
                    sendError(null, e, "MESSAGE_PARSE");
                    continue;
                }

                // The hard kill threshold applies to every message type.
                if (memoryWatchdog != null && memoryWatchdog.shouldKill()) {
                    logger.error("Memory watchdog kill threshold exceeded - terminating command loop");
                    sendError(null, "Memory kill threshold exceeded - subprocess terminating",
                            "MemoryKillThreshold", currentPhase);
                    System.exit(137);
                }

                // Status is read-only and latency-sensitive — answer it inline on the
                // reader thread so it never waits behind a queued or in-flight batch.
                if (isControlPlane(message)) {
                    processMessage(message);
                    continue;
                }

                // Compute-plane work (load / embed / batch / op-timing / shutdown): gate
                // embeds on the soft stop threshold, then hand off to the priority lane.
                // Shutdown is always admitted so the process can still drain cleanly.
                boolean gatedByMemory = memoryWatchdog != null && memoryWatchdog.shouldStop()
                        && !(message instanceof EmbeddingSubprocessMessage.ShutdownRequest);
                if (gatedByMemory) {
                    logger.warn("Memory watchdog stop threshold exceeded - rejecting compute request");
                    sendError(extractRequestId(message),
                            "Memory threshold exceeded - subprocess cannot process more commands",
                            "MemoryThreshold", currentPhase);
                    continue;
                }

                computeQueue.put(new PendingRequest(
                        priorityFor(message), requestSequence.incrementAndGet(), message));
            }
        } catch (IOException e) {
            logger.info("Input stream closed, shutting down");
        }

        // stdin closed: stop accepting work and let the in-flight op finish before
        // cleanup() closes the encoder (closing it under a live encode would crash).
        stopComputeWorker();
        logger.info("Command loop exited");
    }

    /**
     * Start the single serial compute worker. It is the ONLY thread that touches the
     * encoder, so encoder calls remain serialized even though the reader thread now
     * runs concurrently. The priority lane lets single/small embeds and model loads
     * preempt large throughput batches.
     */
    private static void startComputeWorker() {
        Thread worker = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                PendingRequest pending;
                try {
                    pending = computeQueue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                // Re-check the soft stop threshold at dequeue time; memory state may have
                // changed while the request waited. Shutdown always proceeds.
                if (memoryWatchdog != null && memoryWatchdog.shouldStop()
                        && !(pending.message() instanceof EmbeddingSubprocessMessage.ShutdownRequest)) {
                    sendError(extractRequestId(pending.message()),
                            "Memory threshold exceeded - subprocess cannot process more commands",
                            "MemoryThreshold", currentPhase);
                    continue;
                }
                processMessage(pending.message());
            }
        }, "embedding-compute");
        worker.setDaemon(true);
        computeWorker = worker;
        worker.start();
    }

    /**
     * Signal the compute worker to stop and wait (bounded) for any in-flight encode to
     * finish, so the subsequent encoder close in cleanup() doesn't race a live op.
     */
    private static void stopComputeWorker() {
        Thread worker = computeWorker;
        if (worker == null) {
            return;
        }
        worker.interrupt();
        try {
            worker.join(TimeUnit.SECONDS.toMillis(15));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Control-plane messages are answered inline on the reader thread, so they must be
     * cheap and free of encoder side effects. Only status qualifies today: it reads
     * volatile/atomic state and can safely run concurrently with the compute worker.
     */
    static boolean isControlPlane(EmbeddingSubprocessMessage message) {
        return message instanceof EmbeddingSubprocessMessage.StatusRequest;
    }

    /**
     * Assign a scheduling priority. Shutdown preempts queued work (but not the in-flight
     * op); large batches are throughput work; everything else rides the fast lane.
     */
    static int priorityFor(EmbeddingSubprocessMessage message) {
        if (message instanceof EmbeddingSubprocessMessage.ShutdownRequest) {
            return PRIORITY_URGENT;
        }
        if (message instanceof EmbeddingSubprocessMessage.EmbedBatchRequest batch) {
            int size = batch.texts() == null ? 0 : batch.texts().size();
            return size > optimalBatchSize ? PRIORITY_NORMAL : PRIORITY_FAST;
        }
        // Single embeds, model load, and op-timing control all ride the fast lane.
        return PRIORITY_FAST;
    }

    /**
     * Process a single message and send response.
     */
    private static void processMessage(EmbeddingSubprocessMessage message) {
        logger.debug("Processing message: {}", message.getClass().getSimpleName());

        try {
            if (message instanceof EmbeddingSubprocessMessage.LoadModelRequest req) {
                handleLoadModel(req);
            } else if (message instanceof EmbeddingSubprocessMessage.EmbedRequest req) {
                handleEmbed(req);
            } else if (message instanceof EmbeddingSubprocessMessage.EmbedBatchRequest req) {
                handleEmbedBatch(req);
            } else if (message instanceof EmbeddingSubprocessMessage.StatusRequest req) {
                handleStatus(req);
            } else if (message instanceof EmbeddingSubprocessMessage.ShutdownRequest req) {
                handleShutdown(req);
            } else if (message instanceof EmbeddingSubprocessMessage.OpTimingConfigRequest req) {
                handleOpTimingConfig(req);
            } else if (message instanceof EmbeddingSubprocessMessage.OpTimingFlushRequest req) {
                handleOpTimingFlush(req);
            } else {
                logger.warn("Unknown message type: {}", message.getClass().getSimpleName());
                sendError(null, "Unknown message type: " + message.getClass().getSimpleName(),
                        "UnknownMessageType", "UNKNOWN");
            }
        } catch (Exception e) {
            logger.error("Error handling message: {}", e.getMessage(), e);
            String requestId = extractRequestId(message);
            sendError(requestId, e, currentPhase);
        }
    }

    /**
     * Handle LoadModelRequest - loads the embedding model.
     */
    private static void handleLoadModel(EmbeddingSubprocessMessage.LoadModelRequest req) {
        long loadStart = System.currentTimeMillis();
        loading = true;
        loadingPhase = "STARTING";

        logger.info("Loading model: {} (optimalBatch={}, maxBatch={})",
                req.modelId(), req.optimalBatchSize(), req.maxBatchSize());

        sendPhaseTransition(currentPhase, "LOADING_MODEL", 0);
        currentPhase = "LOADING_MODEL";
        sendProgress("LOADING_MODEL", 0, "Starting model load", "Loading model: " + req.modelId());
        sendLog("INFO", "ModelLoader", "Loading model: " + req.modelId());

        try {
            // Close existing encoder if any
            if (encoder != null) {
                sendProgress("LOADING_MODEL", 5, "Closing previous model", "Cleaning up previous encoder...");
                try {
                    encoder.initiateShutdown();
                    encoder.close();
                    logger.info("Closed previous encoder");
                    sendLog("INFO", "ModelLoader", "Previous encoder closed");
                } catch (Exception e) {
                    logger.warn("Error closing previous encoder: {}", e.getMessage());
                    sendLog("WARN", "ModelLoader", "Error closing previous encoder: " + e.getMessage());
                }
            }

            // Store batch configuration
            optimalBatchSize = req.optimalBatchSize() > 0 ? req.optimalBatchSize() : 32;
            maxBatchSize = req.maxBatchSize() > 0 ? req.maxBatchSize() : 64;

            // Create new encoder
            loadingPhase = "CREATING_ENCODER";
            sendProgress("LOADING_MODEL", 20, "Creating encoder", "Initializing encoder for " + req.modelId());
            Map<String, String> modelConfig = req.modelConfig() == null ? Map.of() : req.modelConfig();
            String explicitModelPath = modelConfig.get("modelPath");
            String explicitVocabPath = modelConfig.get("vocabPath");
            boolean explicitLocalModel = (explicitModelPath != null && !explicitModelPath.isBlank())
                    || (explicitVocabPath != null && !explicitVocabPath.isBlank());
            if (explicitLocalModel) {
                if (explicitModelPath == null || explicitModelPath.isBlank()
                        || explicitVocabPath == null || explicitVocabPath.isBlank()) {
                    throw new IOException("Explicit local embedding models require both modelPath and vocabPath");
                }
                sendLog("INFO", "ModelLoader", "Creating encoder from explicit local artifact paths");
                encoder = AnseriniEncoderFactory.createEncoderFromPaths(
                        req.modelId(), explicitModelPath, explicitVocabPath,
                        modelConfig.get("encoderType"));
            } else {
                sendLog("INFO", "ModelLoader", "Creating encoder via model registry");
                encoder = AnseriniEncoderFactory.createEncoder(req.modelId());
            }
            encoderType = encoder.getClass().getSimpleName();
            sendLog("INFO", "ModelLoader", "Encoder created: " + encoderType);

            // Configure batch size if supported
            loadingPhase = "CONFIGURING";
            sendProgress("LOADING_MODEL", 50, "Configuring batch sizes", "Setting optimal=" + optimalBatchSize + ", max=" + maxBatchSize);

            // Use the absoluteMaxBatchSize from the request (default 0 -> falls back to maxBatchSize).
            // The old hardcoded 8192 caused ~36GB native forward-pass activations and host OOM.
            int absoluteMax = req.absoluteMaxBatchSize() > 0 ? req.absoluteMaxBatchSize() : maxBatchSize;
            if (encoder instanceof GenericDenseSameDiffEncoder denseEncoder) {
                denseEncoder.configureBatchSize(optimalBatchSize, maxBatchSize, absoluteMax, -1.0);
                logger.info("Configured dense encoder batch sizes: optimal={}, max={}, absoluteMax={}",
                        optimalBatchSize, maxBatchSize, absoluteMax);
                sendLog("INFO", "ModelLoader", "Dense batch sizes configured: optimal=" + optimalBatchSize
                        + ", max=" + maxBatchSize + ", absoluteMax=" + absoluteMax);

                // Register callback so native-memory-pressure resize decisions are forwarded
                // over the subprocess stdout JSON protocol to the parent process.
                denseEncoder.setBatchResizeListener((oldBatch, newBatch, reason, physBytes, maxPhysBytes) -> {
                    logger.warn("EMBED_DECISION batchResize old={} new={} reason={} physicalBytes={} maxPhysicalBytes={}",
                            oldBatch, newBatch, reason, physBytes, maxPhysBytes);
                    sendMessage(EmbeddingSubprocessMessage.batchResizeNotice(
                            oldBatch, newBatch, reason, physBytes, maxPhysBytes));
                });
            } else if (encoder instanceof BgeSameDiffEncoder bgeEncoder) {
                bgeEncoder.configureBatchSize(optimalBatchSize, maxBatchSize, absoluteMax);
                logger.info("Configured BGE encoder batch sizes: optimal={}, max={}, absoluteMax={}",
                        optimalBatchSize, maxBatchSize, absoluteMax);
                sendLog("INFO", "ModelLoader", "BGE batch sizes configured: optimal=" + optimalBatchSize
                        + ", max=" + maxBatchSize + ", absoluteMax=" + absoluteMax);
            } else if (encoder instanceof ArcticEmbedSameDiffEncoder arcticEncoder) {
                arcticEncoder.configureBatchSize(optimalBatchSize, maxBatchSize, absoluteMax);
                logger.info("Configured Arctic encoder batch sizes: optimal={}, max={}, absoluteMax={}",
                        optimalBatchSize, maxBatchSize, absoluteMax);
                sendLog("INFO", "ModelLoader", "Arctic batch sizes configured: optimal=" + optimalBatchSize
                        + ", max=" + maxBatchSize + ", absoluteMax=" + absoluteMax);
            }

            // Test the encoder and get dimensions
            loadingPhase = "TESTING";
            sendProgress("LOADING_MODEL", 80, "Testing encoder", "Running test embedding...");
            sendLog("INFO", "ModelLoader", "Testing encoder with warmup embedding");

            float[] testEmbedding = encoder.encode("test");
            validateEmbedding(testEmbedding, "model-load readiness probe");

            currentModelId = req.modelId();
            currentDimensions = testEmbedding.length;
            modelSource = explicitLocalModel
                    ? modelConfig.getOrDefault("modelSource", "LOCAL_PATH") : "REGISTRY";

            // Warmup DSP plans at key bucket sizes to avoid cold-start latency
            // and ensure the planner has seen representative shapes before real traffic.
            if (encoder instanceof GenericDenseSameDiffEncoder denseEncoder) {
                warmupDspBuckets(denseEncoder);

                // FIX C: Release transient activation buffers that accumulated during warmup.
                // destroyAllWorkspacesForCurrentThread() reclaims per-thread ND4J workspaces
                // (temporary tensors, activation scratch) WITHOUT touching the compiled DSP
                // plan cache — plans are keyed by shape and live outside these workspaces.
                // System.gc() encourages the JVM to release Java-side references to off-heap
                // buffers so JavaCPP can deallocate them promptly.
                try {
                    Nd4j.getWorkspaceManager().destroyAllWorkspacesForCurrentThread();
                    System.gc();
                    sendLog("INFO", "DspWarmup", "Released post-warmup transient workspaces (plan cache intact)");
                } catch (Exception e) {
                    logger.debug("Post-warmup workspace cleanup skipped: {}", e.getMessage());
                }
            }

            // Set model context on watchdog so OOM kill logs identify the model
            if (memoryWatchdog != null) {
                memoryWatchdog.setModelId(currentModelId);
            }

            modelLoaded = true;
            loading = false;
            loadingPhase = "COMPLETE";

            // Trim GPU memory pools after model load + test embedding to release
            // temporary allocations back to the OS
            trimGpuMemoryPools("post-model-load");

            long loadTimeMs = System.currentTimeMillis() - loadStart;

            logger.info("Model loaded successfully: {} (dimensions={}, loadTime={}ms)",
                    currentModelId, currentDimensions, loadTimeMs);
            sendLog("INFO", "ModelLoader", "Model loaded: " + currentModelId +
                    " (dim=" + currentDimensions + ", time=" + loadTimeMs + "ms)");

            sendProgress("LOADING_MODEL", 100, "Model loaded", "Successfully loaded " + currentModelId);
            sendPhaseTransition("LOADING_MODEL", "IDLE", loadTimeMs);
            currentPhase = "IDLE";

            // Send response
            EmbeddingSubprocessMessage.LoadModelResponse response =
                new EmbeddingSubprocessMessage.LoadModelResponse(
                    req.requestId(), true, currentModelId, currentDimensions,
                    modelSource, encoderType, loadTimeMs, null);
            sendMessage(response);

        } catch (Exception e) {
            logger.error("Failed to load model: {}", req.modelId(), e);
            sendLog("ERROR", "ModelLoader", "Failed to load model " + req.modelId() + ": " + e.getMessage());

            modelLoaded = false;
            loading = false;
            currentModelId = null;
            currentDimensions = -1;
            loadingPhase = "FAILED";

            long loadTimeMs = System.currentTimeMillis() - loadStart;
            sendPhaseTransition("LOADING_MODEL", "IDLE", loadTimeMs);
            currentPhase = "IDLE";

            EmbeddingSubprocessMessage.LoadModelResponse response =
                new EmbeddingSubprocessMessage.LoadModelResponse(
                    req.requestId(), false, req.modelId(), -1, "FAILED", "NONE", loadTimeMs, e.getMessage());
            sendMessage(response);
        }
    }

    /**
     * Handle EmbedRequest - embed a single text.
     */
    private static void handleEmbed(EmbeddingSubprocessMessage.EmbedRequest req) {
        if (!modelLoaded || encoder == null) {
            EmbeddingSubprocessMessage.EmbedResponse response =
                new EmbeddingSubprocessMessage.EmbedResponse(
                    req.requestId(), false, null, 0, "Model not loaded");
            sendMessage(response);
            return;
        }

        try {
            long start = System.currentTimeMillis();
            float[] embedding = encoder.encode(req.text());
            long embedTimeMs = System.currentTimeMillis() - start;

            validateEmbedding(embedding, "request " + req.requestId());

            // Update statistics
            totalEmbeddingsProcessed.incrementAndGet();
            totalEmbedTimeMs.addAndGet(embedTimeMs);

            EmbeddingSubprocessMessage.EmbedResponse response =
                new EmbeddingSubprocessMessage.EmbedResponse(
                    req.requestId(), true, embedding, embedTimeMs, null);
            sendMessage(response);

        } catch (Exception e) {
            if (isRecoverableEmbeddingValidationFailure(e)) {
                logger.warn("Recoverable embedding validation failure: {}", e.getMessage());
            } else {
                logger.error("Error embedding text: {}", e.getMessage(), e);
            }
            EmbeddingSubprocessMessage.EmbedResponse response =
                new EmbeddingSubprocessMessage.EmbedResponse(
                    req.requestId(), false, null, 0, e.getMessage());
            sendMessage(response);
        }
    }

    /**
     * Handle EmbedBatchRequest - embed multiple texts in a batch.
     */
    private static void handleEmbedBatch(EmbeddingSubprocessMessage.EmbedBatchRequest req) {
        if (!modelLoaded || encoder == null) {
            EmbeddingSubprocessMessage.EmbedBatchResponse response =
                new EmbeddingSubprocessMessage.EmbedBatchResponse(
                    req.requestId(), false, null, 0, 0, 0, null, "Model not loaded");
            sendMessage(response);
            return;
        }

        try {
            List<String> texts = req.texts();
            int inputCount = texts.size();
            logger.info("Embedding batch of {} texts", inputCount);

            sendPhaseTransition(currentPhase, "EMBEDDING", 0);
            String prevPhase = currentPhase;
            currentPhase = "EMBEDDING";

            sendProgress("EMBEDDING", 0, "Starting batch", "Processing " + inputCount + " texts");

            long start = System.currentTimeMillis();
            long tokenizeStart = start;

            // For detailed metrics, we'd need access to encoder internals
            // For now, we track what we can
            List<float[]> embeddings = encoder.encodeBatch(texts);

            long totalTimeMs = System.currentTimeMillis() - start;

            validateEmbeddings(embeddings, inputCount, req.requestId());
            int outputCount = embeddings.size();

            // Update statistics before logging so the running total is accurate
            long runningTotal = totalEmbeddingsProcessed.addAndGet(outputCount);
            long batchNum = totalBatchesProcessed.incrementAndGet();
            totalEmbedTimeMs.addAndGet(totalTimeMs);

            // Single concise throughput line per batch — the only INFO per embed call.
            double msPerText = inputCount == 0 ? 0.0 : (double) totalTimeMs / inputCount;
            logger.info("EMBED_BATCH_DONE: {} texts in {}ms ({} ms/text) batch={} runningTotal={}",
                    inputCount, totalTimeMs, String.format("%.1f", msPerText), batchNum, runningTotal);

            // Build metrics
            double textsPerSecond = totalTimeMs > 0 ? (outputCount * 1000.0 / totalTimeMs) : 0;
            EmbeddingSubprocessMessage.BatchMetrics metrics = new EmbeddingSubprocessMessage.BatchMetrics(
                    inputCount,
                    0, // maxSequenceLength - would need encoder access
                    0, // totalTokens - would need encoder access
                    "[" + inputCount + " x ?]", // inputShape
                    "[" + outputCount + " x " + currentDimensions + "]", // outputShape
                    0, // tokenizeTimeMs
                    0, // paddingTimeMs
                    0, // tensorCreationTimeMs
                    totalTimeMs, // forwardPassTimeMs (approximate)
                    0, // extractionTimeMs
                    0, // tokensPerSecond
                    textsPerSecond,
                    null // passageTokenCounts
            );

            sendProgress("EMBEDDING", 100, "Batch complete",
                    "Processed " + outputCount + " embeddings in " + totalTimeMs + "ms");
            sendPhaseTransition("EMBEDDING", "IDLE", totalTimeMs);
            currentPhase = "IDLE";

            EmbeddingSubprocessMessage.EmbedBatchResponse response =
                new EmbeddingSubprocessMessage.EmbedBatchResponse(
                    req.requestId(), true, embeddings, inputCount, outputCount, totalTimeMs, metrics, null);
            sendMessage(response);

        } catch (Exception e) {
            boolean recoverableValidationFailure = isRecoverableEmbeddingValidationFailure(e);
            if (recoverableValidationFailure) {
                logger.warn("Recoverable embedding batch validation failure: {}", e.getMessage());
            } else {
                logger.error("Error embedding batch: {}", e.getMessage(), e);
            }
            sendLog(recoverableValidationFailure ? "WARN" : "ERROR", "BatchEmbedding",
                    (recoverableValidationFailure ? "Batch validation failed; caller may retry: " : "Batch failed: ")
                            + e.getMessage());

            sendPhaseTransition("EMBEDDING", "IDLE", 0);
            currentPhase = "IDLE";

            EmbeddingSubprocessMessage.EmbedBatchResponse response =
                new EmbeddingSubprocessMessage.EmbedBatchResponse(
                    req.requestId(), false, null, req.texts().size(), 0, 0, null, e.getMessage());
            sendMessage(response);

            if (isDeviceLevelError(e) && deviceContextPoisoned.compareAndSet(false, true)) {
                String msg = DEVICE_FATAL_PREFIX + " Device-level CUDA error during embed batch request — "
                        + "CUDA context poisoned. Exiting with code " + DEVICE_ERROR_EXIT_CODE
                        + " for bounded restart handling.";
                logger.error(msg, e);
                sendLog("ERROR", "BatchEmbedding", msg);
                System.err.flush();
                System.exit(DEVICE_ERROR_EXIT_CODE);
            }
        }
    }

    private static void validateEmbeddings(List<float[]> embeddings, int expectedCount, String requestId) {
        if (embeddings == null) {
            throw new IllegalStateException("Encoder returned null batch embeddings");
        }
        if (embeddings.size() != expectedCount) {
            throw new IllegalStateException("Encoder returned " + embeddings.size()
                    + " embedding(s) for " + expectedCount + " text(s)");
        }
        for (int i = 0; i < embeddings.size(); i++) {
            validateEmbedding(embeddings.get(i), "batch request " + requestId + " row " + i);
        }
    }

    private static void validateEmbedding(float[] embedding, String label) {
        if (embedding == null || embedding.length == 0) {
            throw new IllegalStateException("Encoder returned null or empty embedding for " + label);
        }
        double magnitude = 0.0;
        for (float value : embedding) {
            if (!Float.isFinite(value)) {
                throw new IllegalStateException("Encoder returned non-finite embedding value for " + label);
            }
            magnitude += (double) value * value;
        }
        if (!Double.isFinite(magnitude) || magnitude <= MIN_EMBEDDING_MAGNITUDE) {
            throw new IllegalStateException("Encoder returned zero-magnitude embedding for " + label);
        }
    }

    /**
     * Handle StatusRequest - return current status.
     */
    private static void handleStatus(EmbeddingSubprocessMessage.StatusRequest req) {
        long uptimeMs = System.currentTimeMillis() - startTime;

        // Collect runtime info
        EmbeddingSubprocessMessage.RuntimeInfo runtimeInfo =
            EmbeddingSubprocessMessage.RuntimeInfo.collect(currentModelId, currentDimensions);

        EmbeddingSubprocessMessage.StatusResponse response =
            new EmbeddingSubprocessMessage.StatusResponse(
                req.requestId(),
                modelLoaded,
                loading,
                loadingPhase,
                currentModelId,
                modelSource,
                encoderType,
                currentDimensions,
                optimalBatchSize,
                maxBatchSize,
                uptimeMs,
                totalEmbeddingsProcessed.get(),
                runtimeInfo,
                null
            );
        sendMessage(response);
    }

    /**
     * Handle ShutdownRequest - gracefully shutdown.
     */
    private static void handleShutdown(EmbeddingSubprocessMessage.ShutdownRequest req) {
        logger.info("Shutdown requested");
        sendLog("INFO", "Shutdown", "Graceful shutdown requested");

        sendPhaseTransition(currentPhase, "SHUTDOWN", 0);
        currentPhase = "SHUTDOWN";
        sendProgress("SHUTDOWN", 0, "Shutting down", "Graceful shutdown in progress...");

        // Stop heartbeat
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
        }

        // Close encoder
        if (encoder != null) {
            sendProgress("SHUTDOWN", 30, "Closing encoder", "Shutting down embedding model...");
            try {
                encoder.initiateShutdown();
                encoder.close();
                sendLog("INFO", "Shutdown", "Encoder closed successfully");
            } catch (Exception e) {
                logger.warn("Error closing encoder during shutdown: {}", e.getMessage());
                sendLog("WARN", "Shutdown", "Error closing encoder: " + e.getMessage());
            }
        }

        // Cleanup ND4J
        sendProgress("SHUTDOWN", 70, "Cleaning up ND4J", "Releasing native resources...");
        cleanupNd4j();

        sendProgress("SHUTDOWN", 100, "Shutdown complete", "Exiting...");
        logger.info("Shutdown complete, exiting");
        System.exit(0);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // OP TIMING HANDLERS
    // ═══════════════════════════════════════════════════════════════════════════════

    // Op timing state
    private static volatile boolean opTimingEnabled = false;
    private static volatile boolean opTimingDetailedMode = false;

    /**
     * Handle OpTimingConfigRequest - enable/disable ND4J op timing.
     */
    private static void handleOpTimingConfig(EmbeddingSubprocessMessage.OpTimingConfigRequest req) {
        logger.info("=== OP TIMING CONFIG REQUEST RECEIVED === enabled={}, detailedMode={}, requestId={}",
                req.enabled(), req.detailedMode(), req.requestId());
        sendLog("INFO", "OpTiming", "CONFIG REQUEST: enabled=" + req.enabled() + ", detailed=" + req.detailedMode());

        try {
            org.nd4j.nativeblas.NativeOps nativeOps = Nd4j.getNativeOps();
            logger.info("NativeOps class: {}", nativeOps.getClass().getName());

            if (req.enabled()) {
                // setOpTimingEnabled(int enabled, int detailed) - 1 to enable, 0 to disable
                logger.info("Calling nativeOps.setOpTimingEnabled(1, {})", req.detailedMode() ? 1 : 0);
                nativeOps.setOpTimingEnabled(1, req.detailedMode() ? 1 : 0);
                opTimingEnabled = true;
                opTimingDetailedMode = req.detailedMode();
                logger.info("=== OP TIMING ENABLED in subprocess (detailed={}) ===", opTimingDetailedMode);
                sendLog("INFO", "OpTiming", "ENABLED: detailed=" + opTimingDetailedMode);
            } else {
                logger.info("Calling nativeOps.setOpTimingEnabled(0, 0)");
                nativeOps.setOpTimingEnabled(0, 0);
                opTimingEnabled = false;
                opTimingDetailedMode = false;
                logger.info("=== OP TIMING DISABLED in subprocess ===");
                sendLog("INFO", "OpTiming", "DISABLED");
            }

            logger.info("Sending OpTimingConfigResponse: requestId={}, success=true", req.requestId());
            sendMessage(new EmbeddingSubprocessMessage.OpTimingConfigResponse(
                    req.requestId(), true, opTimingEnabled, opTimingDetailedMode, null));
            logger.info("OpTimingConfigResponse sent successfully");

        } catch (Exception e) {
            logger.error("Failed to configure op timing: {}", e.getMessage(), e);
            sendLog("ERROR", "OpTiming", "CONFIG FAILED: " + e.getMessage());
            sendMessage(new EmbeddingSubprocessMessage.OpTimingConfigResponse(
                    req.requestId(), false, opTimingEnabled, opTimingDetailedMode, e.getMessage()));
        }
    }

    /**
     * Handle OpTimingFlushRequest - flush and return op timing stats.
     */
    private static void handleOpTimingFlush(EmbeddingSubprocessMessage.OpTimingFlushRequest req) {
        logger.info("=== OP TIMING FLUSH REQUEST === topN={}, reset={}, opTimingEnabled={}",
                req.topN(), req.reset(), opTimingEnabled);
        sendLog("INFO", "OpTiming", "FLUSH REQUEST: opTimingEnabled=" + opTimingEnabled);

        try {
            org.nd4j.nativeblas.NativeOps nativeOps = Nd4j.getNativeOps();

            // Flush timing data to be collected
            logger.info("Calling nativeOps.flushOpTiming()...");
            nativeOps.flushOpTiming();

            // Get counts
            int numOps = nativeOps.getOpTimingNumOps();
            long totalExecs = nativeOps.getOpTimingTotalExecutions();
            logger.info("After flush: numOps={}, totalExecs={}", numOps, totalExecs);

            // Export to CSV and parse
            List<EmbeddingSubprocessMessage.OpTimingStat> hotspots = new java.util.ArrayList<>();

            try {
                java.nio.file.Path csvPath = java.nio.file.Files.createTempFile("nd4j_timing_subprocess_", ".csv");
                logger.info("Exporting op timing CSV to: {}", csvPath);
                nativeOps.exportOpTimingCSV(csvPath.toString());
                String csvContent = java.nio.file.Files.readString(csvPath);
                java.nio.file.Files.deleteIfExists(csvPath);

                logger.info("CSV content length: {} bytes, lines: {}", csvContent.length(),
                        csvContent.split("\n").length);
                if (csvContent.length() < 500) {
                    logger.info("CSV content (small file):\n{}", csvContent);
                } else {
                    logger.info("CSV first 500 chars:\n{}", csvContent.substring(0, 500));
                }

                // Parse CSV
                String[] lines = csvContent.split("\n");
                int rank = 1;

                for (String line : lines) {
                    // Skip header line
                    if (line.startsWith("OpName") || line.startsWith("op_name") ||
                        line.startsWith("#") || line.trim().isEmpty()) {
                        continue;
                    }

                    String[] parts = line.split(",");
                    // CSV format: OpName,Hash,Calls,TotalMs,AvgUs,StdDevUs,MinUs,MaxUs,HelperPct,...
                    if (parts.length >= 9) {
                        try {
                            String opName = parts[0].trim();
                            // parts[1] is Hash - skip it
                            long calls = Long.parseLong(parts[2].trim());
                            double totalMs = Double.parseDouble(parts[3].trim());
                            double avgUs = Double.parseDouble(parts[4].trim());
                            double stdDevUs = parts.length > 5 ? Double.parseDouble(parts[5].trim()) : 0;
                            double minUs = parts.length > 6 ? Double.parseDouble(parts[6].trim()) : 0;
                            double maxUs = parts.length > 7 ? Double.parseDouble(parts[7].trim()) : 0;
                            double helperPercent = parts.length > 8 ? Double.parseDouble(parts[8].trim()) : 0;

                            hotspots.add(new EmbeddingSubprocessMessage.OpTimingStat(
                                    rank, opName, calls, totalMs, avgUs, stdDevUs, minUs, maxUs, helperPercent));
                            rank++;

                            if (req.topN() > 0 && rank > req.topN()) {
                                break;
                            }
                        } catch (NumberFormatException e) {
                            logger.debug("Failed to parse CSV line: {}", line);
                        }
                    }
                }

                // Sort by totalMs descending and re-rank
                hotspots.sort((a, b) -> Double.compare(b.totalMs(), a.totalMs()));
                List<EmbeddingSubprocessMessage.OpTimingStat> reRanked = new java.util.ArrayList<>();
                for (int i = 0; i < hotspots.size(); i++) {
                    EmbeddingSubprocessMessage.OpTimingStat stat = hotspots.get(i);
                    reRanked.add(new EmbeddingSubprocessMessage.OpTimingStat(
                            i + 1, stat.opName(), stat.calls(), stat.totalMs(), stat.avgUs(),
                            stat.stdDevUs(), stat.minUs(), stat.maxUs(), stat.helperPercent()));
                }
                hotspots = reRanked;

            } catch (Exception e) {
                logger.warn("Failed to export/parse CSV: {}", e.getMessage());
            }

            logger.info("=== OP TIMING FLUSH RESULTS === numOps={}, totalExecs={}, parsedHotspots={}, opTimingEnabled={}",
                    numOps, totalExecs, hotspots.size(), opTimingEnabled);
            sendLog("INFO", "OpTiming", "FLUSH RESULTS: " + numOps + " ops, " + totalExecs + " executions, " +
                    hotspots.size() + " hotspots, enabled=" + opTimingEnabled);

            // Log first few hotspots for debugging
            if (!hotspots.isEmpty()) {
                logger.info("Top hotspots:");
                for (int i = 0; i < Math.min(5, hotspots.size()); i++) {
                    EmbeddingSubprocessMessage.OpTimingStat stat = hotspots.get(i);
                    logger.info("  #{}: {} - {} calls, {} ms total", stat.rank(), stat.opName(), stat.calls(), stat.totalMs());
                }
            } else {
                logger.warn("No hotspots parsed from CSV! opTimingEnabled={}", opTimingEnabled);
            }

            // Reset if requested
            if (req.reset()) {
                nativeOps.resetOpTiming();
                logger.debug("Op timing data reset");
            }

            logger.info("Sending OpTimingFlushResponse with {} hotspots", hotspots.size());
            sendMessage(new EmbeddingSubprocessMessage.OpTimingFlushResponse(
                    req.requestId(), true, totalExecs, numOps, hotspots, null));
            logger.info("OpTimingFlushResponse sent successfully");

        } catch (Exception e) {
            logger.error("Failed to flush op timing: {}", e.getMessage(), e);
            sendMessage(new EmbeddingSubprocessMessage.OpTimingFlushResponse(
                    req.requestId(), false, 0, 0, List.of(), e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // MESSAGE SENDING HELPERS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Send a message to the parent process via stdout.
     */
    private static void sendMessage(EmbeddingSubprocessMessage message) {
        try {
            String json = OBJECT_MAPPER.writeValueAsString(message);
            synchronized (stdoutLock) {
                originalStdout.println(EmbeddingSubprocessMessage.MESSAGE_PREFIX + json);
                originalStdout.flush();
            }
        } catch (Exception e) {
            logger.error("Error sending message: {}", e.getMessage());
        }
    }

    /**
     * Send a progress message.
     */
    private static void sendProgress(String phase, int percent, String step, String message) {
        EmbeddingSubprocessMessage.Progress progress = EmbeddingSubprocessMessage.progress(phase, percent, step, message);
        sendMessage(progress);
    }

    /**
     * Send a progress message with stats.
     */
    private static void sendProgress(String phase, int percent, String step, String message,
                                      EmbeddingSubprocessMessage.ProgressStats stats) {
        EmbeddingSubprocessMessage.Progress progress = EmbeddingSubprocessMessage.progress(phase, percent, step, message, stats);
        sendMessage(progress);
    }

    /**
     * Send a phase transition message.
     */
    private static void sendPhaseTransition(String fromPhase, String toPhase, long durationMs) {
        EmbeddingSubprocessMessage.PhaseTransition transition =
            EmbeddingSubprocessMessage.phaseTransition(fromPhase, toPhase, durationMs);
        sendMessage(transition);
    }

    /**
     * Send a log message.
     */
    private static void sendLog(String level, String source, String message) {
        EmbeddingSubprocessMessage.Log log = EmbeddingSubprocessMessage.log(level, source, message);
        sendMessage(log);
    }

    /**
     * Send an error message from exception.
     */
    private static void sendError(String requestId, Throwable exception, String phase) {
        EmbeddingSubprocessMessage.Error error = EmbeddingSubprocessMessage.error(requestId, exception, phase);
        sendMessage(error);
    }

    /**
     * Send an error message with explicit details.
     */
    private static void sendError(String requestId, String errorMessage, String errorType, String phase) {
        EmbeddingSubprocessMessage.Error error = EmbeddingSubprocessMessage.error(requestId, errorMessage, errorType, phase);
        sendMessage(error);
    }

    /**
     * Extract request ID from a message if available.
     */
    private static String extractRequestId(EmbeddingSubprocessMessage message) {
        if (message instanceof EmbeddingSubprocessMessage.LoadModelRequest req) {
            return req.requestId();
        } else if (message instanceof EmbeddingSubprocessMessage.EmbedRequest req) {
            return req.requestId();
        } else if (message instanceof EmbeddingSubprocessMessage.EmbedBatchRequest req) {
            return req.requestId();
        } else if (message instanceof EmbeddingSubprocessMessage.StatusRequest req) {
            return req.requestId();
        } else if (message instanceof EmbeddingSubprocessMessage.ShutdownRequest req) {
            return req.requestId();
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // GPU MEMORY POOL TRIMMING
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Trim GPU memory pools on all CUDA devices to release unused reserved memory
     * back to the OS. This is especially useful after DSP warmup or model loading
     * where temporary allocations may have expanded the pool.
     *
     * @param reason descriptive reason for the trim (logged for diagnostics)
     */
    private static void trimGpuMemoryPools(String reason) {
        try {
            var nativeOps = Nd4j.getNativeOps();
            int numDevices = Nd4j.getAffinityManager().getNumberOfDevices();
            for (int d = 0; d < numDevices; d++) {
                nativeOps.trimMemoryPool(d);
            }
            logger.info("Trimmed GPU memory pools on {} device(s) (reason: {})", numDevices, reason);
            sendLog("INFO", "GPU", "Trimmed GPU memory pools on " + numDevices + " device(s) (reason: " + reason + ")");
        } catch (Exception e) {
            logger.debug("Could not trim GPU memory pools (CPU backend?): {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // CLEANUP
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Cleanup on exit.
     */
    private static void cleanup() {
        logger.info("Cleaning up...");

        // Stop memory watchdog first
        if (memoryWatchdog != null) {
            try {
                memoryWatchdog.close();
            } catch (Exception e) {
                logger.debug("Error closing memory watchdog: {}", e.getMessage());
            }
            memoryWatchdog = null;
        }

        if (heartbeatExecutor != null) {
            try {
                heartbeatExecutor.shutdownNow();
            } catch (Exception e) {
                logger.debug("Error shutting down heartbeat executor: {}", e.getMessage());
            }
        }

        if (encoder != null) {
            try {
                encoder.close();
            } catch (Exception e) {
                logger.warn("Error closing encoder: {}", e.getMessage());
            }
        }

        // Only clean up ND4J if it was successfully initialized
        if (nd4jInitialized) {
            cleanupNd4j();
        } else {
            logger.info("Skipping ND4J cleanup - ND4J was not successfully initialized");
        }

        logger.info("Cleanup complete");
    }

    /**
     * Cleanup ND4J resources.
     * Only call this if nd4jInitialized is true!
     */
    private static void cleanupNd4j() {
        try {
            logger.info("Cleaning up ND4J resources...");
            sendLog("INFO", "ND4J", "Cleaning up ND4J resources");

            try {
                Nd4j.getWorkspaceManager().destroyAllWorkspacesForCurrentThread();
            } catch (Exception | NoClassDefFoundError e) {
                logger.debug("Could not destroy workspaces: {}", e.getMessage());
            }

            try {
                Nd4j.getMemoryManager().releaseCurrentContext();
            } catch (Exception | NoClassDefFoundError e) {
                logger.debug("Could not release memory context: {}", e.getMessage());
            }

            try {
                DifferentialFunctionClassHolder.cleanup();
            } catch (Exception | NoClassDefFoundError e) {
                logger.debug("Could not cleanup DifferentialFunctionClassHolder: {}", e.getMessage());
            }

            try {
                Nd4j.getNativeOps().clearTADCache();
                Nd4j.getNativeOps().clearShapeCache();
            } catch (Exception | NoClassDefFoundError e) {
                logger.debug("Could not clear native caches: {}", e.getMessage());
            }

            logger.info("ND4J cleanup completed");
            sendLog("INFO", "ND4J", "ND4J cleanup completed");
        } catch (Exception | NoClassDefFoundError e) {
            logger.warn("Error during ND4J cleanup: {}", e.getMessage());
        }
    }

    /**
     * Warm the SINGLE max-shape DSP plan the encoder runs.
     *
     * <p>SameDiff's {@code DynamicShapePlanExecutor} compiles one plan per unique input shape
     * {@code [batchSize, seqLen]}, and each compiled plan "protects" ~674 model DataBuffers for its
     * lifetime. The encoder now pins every forward pass to a single fixed shape
     * {@code [maxRows, seqHardCap]} (see {@code GenericDenseSameDiffEncoder.fixedForwardRows}), so
     * exactly ONE plan is ever needed — smaller inputs pad up to it and reuse it. We therefore warm
     * just that one shape: one plan, one workspace, no on-demand recompilation during a crawl.</p>
     *
     * <p>(Previously this warmed a 3×3 grid {@code {128,256,512} × {1,16,64}} — 9 plans, each
     * retaining its own workspace (~22 GB steady-state), and the 64×512 entry forced an oversized
     * forward pass that OOM-killed the subprocess.) Warmup failure is non-fatal: the plan compiles
     * on first real use.</p>
     */
    private static void warmupDspBuckets(GenericDenseSameDiffEncoder denseEncoder) {
        // The one shape fixed-shape inference always runs: [maxRows x seqHardCap].
        int maxRows = Math.max(1, denseEncoder.plannedMaxRows());
        int maxSeq = Math.max(1, denseEncoder.getMaxSequenceLength());
        // Dummy text long enough to fill the seq hard cap even if pad-to-max is ever disabled (~maxSeq/1.3 words).
        int wordCount = (int) (maxSeq / 1.3) + 10;
        StringBuilder sb = new StringBuilder(wordCount * 8);
        for (int w = 0; w < wordCount; w++) {
            sb.append("warmup ");
        }
        String dummyText = sb.toString();

        sendLog("INFO", "DspWarmup", "Pre-warming the single max-shape DSP plan [" + maxRows + " x " + maxSeq + "]");
        sendProgress("LOADING_MODEL", 90, "Warming DSP plan", "Compiling the [" + maxRows + " x " + maxSeq + "] plan...");

        try {
            long start = System.currentTimeMillis();
            List<String> batch = new ArrayList<>(maxRows);
            for (int b = 0; b < maxRows; b++) {
                batch.add(dummyText);
            }
            List<float[]> results = denseEncoder.encodeBatch(batch);
            long elapsed = System.currentTimeMillis() - start;
            if (results != null && !results.isEmpty() && results.get(0) != null) {
                sendLog("INFO", "DspWarmup", "DSP plan ready: [" + maxRows + " x " + maxSeq + "] (" + elapsed + "ms)");
            } else {
                sendLog("WARN", "DspWarmup", "Warmup returned empty for [" + maxRows + " x " + maxSeq + "]");
            }
        } catch (Exception e) {
            if (isDeviceLevelError(e)) {
                String msg = DEVICE_FATAL_PREFIX + " DSP warmup encountered device-level CUDA error for ["
                        + maxRows + " x " + maxSeq + "]: " + e.getMessage()
                        + " — CUDA context is poisoned, embedding lane cannot serve requests. Exiting with code "
                        + DEVICE_ERROR_EXIT_CODE + " for bounded restart handling.";
                logger.error(msg, e);
                sendLog("ERROR", "DspWarmup", msg);
                System.err.flush();
                System.exit(DEVICE_ERROR_EXIT_CODE);
            }
            sendLog("WARN", "DspWarmup", "Warmup failed for [" + maxRows + " x " + maxSeq + "]: " + e.getMessage());
            logger.warn("DSP warmup failed for [{} x {}]: {}", maxRows, maxSeq, e.getMessage());
        }

        sendLog("INFO", "DspWarmup", "DSP plan warmup complete (1 plan)");
    }

    static boolean isRecoverableEmbeddingValidationFailure(Throwable t) {
        if (t == null) {
            return false;
        }
        String msg = t.getMessage();
        if (msg != null) {
            String lower = msg.toLowerCase();
            if (lower.contains("encoder returned null")
                    || lower.contains("encoder returned non-finite embedding value")
                    || lower.contains("encoder returned zero-magnitude embedding")
                    || (lower.contains("encoder returned") && lower.contains("embedding(s)"))) {
                return true;
            }
        }
        return isRecoverableEmbeddingValidationFailure(t.getCause());
    }

    /**
     * Returns true if the exception (or any cause chain) indicates a device-level CUDA error
     * that poisons the CUDA context and prevents all subsequent device operations.
     */
    private static boolean isDeviceLevelError(Throwable t) {
        if (t == null) return false;
        String msg = t.getMessage();
        if (msg != null && (
                msg.contains("error code [700]")
                || msg.contains("illegal memory access")
                || msg.contains("DEVICE] allocation failed")
                || msg.contains("cudaStreamSynchronize error")
                || msg.contains("cudaErrorIllegalAddress"))) {
            return true;
        }
        return isDeviceLevelError(t.getCause());
    }
}
