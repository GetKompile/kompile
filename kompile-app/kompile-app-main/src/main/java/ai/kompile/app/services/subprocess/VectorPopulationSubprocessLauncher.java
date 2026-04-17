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

package ai.kompile.app.services.subprocess;

import ai.kompile.app.config.Nd4jEnvironmentConfig;
import ai.kompile.app.config.SubprocessExecutableConfig;
import ai.kompile.cli.main.util.NativeImageInfo;
import ai.kompile.app.ingest.domain.IngestEvent;
import ai.kompile.app.ingest.service.IngestEventService;
import ai.kompile.app.services.IngestProgressTracker;
import ai.kompile.app.config.DeviceRoutingConfig;
import ai.kompile.app.services.DeviceRoutingConfigService;
import ai.kompile.app.services.ModelLifecycleManager;
import ai.kompile.app.services.Nd4jEnvironmentConfigService;
import ai.kompile.app.services.OpTimingService;
import ai.kompile.app.services.ServerPortService;
import ai.kompile.app.services.VectorPopulationProgressTracker;
import ai.kompile.app.services.VectorPopulationProgressTracker.VectorPopulationPhase;
import ai.kompile.app.services.VectorPopulationProgressTracker.VectorPopulationStats;
import ai.kompile.app.services.subprocess.SubprocessRestartManager.FailureReason;
import ai.kompile.app.services.subprocess.SubprocessRestartManager.RestartConfig;
import ai.kompile.app.web.dto.IngestProgressUpdate;
import ai.kompile.app.web.dto.IngestProgressUpdate.IngestPhase;
import ai.kompile.app.web.dto.IngestProgressUpdate.IngestStats;
import ai.kompile.app.subprocess.SubprocessMessage;
import ai.kompile.app.subprocess.VectorPopulationSubprocessArgs;
import ai.kompile.embedding.anserini.config.AnseriniEmbeddingConfiguration.AnseriniEmbeddingProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import ai.kompile.cli.common.logs.AgentLogRecord;
import ai.kompile.cli.common.logs.SubprocessLogWriter;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for launching and managing vector population subprocesses.
 *
 * This service spawns isolated JVM processes to run Lucene → Vector Store
 * population,
 * preventing crashes and OOM errors from affecting the main application.
 *
 * Key features:
 * - Spawns subprocess using same classpath as main app
 * - Parses progress JSON from subprocess stdout
 * - Forwards progress to WebSocket for UI updates
 * - Handles subprocess crashes gracefully
 * - Supports cancellation and timeout
 * - Monitors subprocess health via heartbeats
 */
@Service
public class VectorPopulationSubprocessLauncher {

    private static final Logger logger = LoggerFactory.getLogger(VectorPopulationSubprocessLauncher.class);

    private static final String SUBPROCESS_MAIN_CLASS = "ai.kompile.app.subprocess.VectorPopulationSubprocessMain";
    private static final String VECTOR_POPULATION_TOPIC = "/topic/vector-population/progress";

    // Fallback values if SubprocessConfigService is not available
    @Value("${kompile.vectorpopulation.subprocess.java-path:java}")
    private String fallbackJavaPath;

    @Value("${kompile.vectorpopulation.subprocess.heap-size:4g}")
    private String fallbackHeapSize;

    @Value("${kompile.vectorpopulation.subprocess.timeout-minutes:120}")
    private int fallbackTimeoutMinutes;

    @Value("${kompile.vectorpopulation.subprocess.heartbeat-interval-seconds:10}")
    private int fallbackHeartbeatIntervalSeconds;

    @Value("${kompile.vectorpopulation.subprocess.stale-threshold-seconds:180}")
    private int fallbackStaleThresholdSeconds;

    @Value("${kompile.vectorpopulation.subprocess.progress-stall-threshold-seconds:60}")
    private int fallbackProgressStallThresholdSeconds;

    private final SimpMessagingTemplate messagingTemplate;
    private final ServerPortService serverPortService;
    private final Nd4jEnvironmentConfigService nd4jEnvironmentConfigService;
    private final DeviceRoutingConfigService deviceRoutingConfigService;
    private final SubprocessConfigService subprocessConfigService;
    private final SubprocessExecutableConfig subprocessExecutableConfig;
    private final AnseriniEmbeddingProperties embeddingProperties; // For benchmark config
    private final VectorPopulationProgressTracker progressTracker;
    private final IngestProgressTracker ingestProgressTracker;
    private final SubprocessRestartManager restartManager;
    private final IngestEventService ingestEventService;
    private final OpTimingService opTimingService;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private ModelLifecycleManager modelLifecycleManager;

    // Scheduler for restart delays
    private final ScheduledExecutorService restartScheduler = Executors.newSingleThreadScheduledExecutor(
            r -> {
                Thread t = new Thread(r, "subprocess-restart-scheduler");
                t.setDaemon(true);
                return t;
            });

    // Active subprocess tracking
    private final Map<String, VectorPopulationHandle> activeProcesses = new ConcurrentHashMap<>();

    // Track tasks that have already logged warnings
    private final Set<String> warnedTaskIds = ConcurrentHashMap.newKeySet();

    @Autowired
    public VectorPopulationSubprocessLauncher(
            @Autowired(required = false) SimpMessagingTemplate messagingTemplate,
            @Autowired(required = false) ServerPortService serverPortService,
            @Autowired(required = false) Nd4jEnvironmentConfigService nd4jEnvironmentConfigService,
            @Autowired(required = false) DeviceRoutingConfigService deviceRoutingConfigService,
            @Autowired(required = false) SubprocessConfigService subprocessConfigService,
            @Autowired(required = false) SubprocessExecutableConfig subprocessExecutableConfig,
            @Autowired(required = false) AnseriniEmbeddingProperties embeddingProperties,
            @Autowired(required = false) VectorPopulationProgressTracker progressTracker,
            @Autowired(required = false) IngestProgressTracker ingestProgressTracker,
            @Autowired SubprocessRestartManager restartManager,
            @Autowired(required = false) IngestEventService ingestEventService,
            @Autowired(required = false) OpTimingService opTimingService) {
        this.messagingTemplate = messagingTemplate;
        this.serverPortService = serverPortService;
        this.nd4jEnvironmentConfigService = nd4jEnvironmentConfigService;
        this.deviceRoutingConfigService = deviceRoutingConfigService;
        this.subprocessConfigService = subprocessConfigService;
        this.subprocessExecutableConfig = subprocessExecutableConfig;
        this.embeddingProperties = embeddingProperties;
        this.progressTracker = progressTracker;
        this.ingestProgressTracker = ingestProgressTracker;
        this.restartManager = restartManager;
        this.ingestEventService = ingestEventService;
        this.opTimingService = opTimingService;
        this.objectMapper = new ObjectMapper();

        if (progressTracker == null) {
            logger.warn("VectorPopulationSubprocessLauncher initialized WITHOUT progress tracker - " +
                    "progress will only be tracked via direct WebSocket!");
        } else {
            logger.info("VectorPopulationSubprocessLauncher initialized with progress tracker enabled");
        }

        if (restartManager != null) {
            logger.info("VectorPopulationSubprocessLauncher initialized with restart manager: " +
                    "maxAttempts={}, enabled={}", restartManager.getMaxRestartAttempts(), restartManager.isRestartEnabled());
        } else {
            logger.error("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
            logger.error("CRITICAL: VectorPopulationSubprocessLauncher initialized WITHOUT restart manager!");
            logger.error("OOM failures will NOT trigger automatic restart!");
            logger.error("Check that SubprocessRestartManager and SystemMemoryAnalyzer beans are available.");
            logger.error("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
        }
    }

    /**
     * Launch a subprocess to populate vector store from Lucene keyword index.
     *
     * @param taskId           Unique task identifier
     * @param keywordIndexPath Path to the source Lucene keyword index
     * @param vectorIndexPath  Path to the destination vector store index
     * @param options          Additional options (embeddingBatchSize,
     *                         parallelIndexing, indexingWorkers)
     * @return Future that completes when subprocess finishes
     */
    public CompletableFuture<VectorPopulationResult> launchVectorPopulation(
            String taskId,
            String keywordIndexPath,
            String vectorIndexPath,
            Map<String, Object> options) {

        logger.info("Launching vector population subprocess for task: {} keywordIndex: {} vectorIndex: {}",
                taskId, keywordIndexPath, vectorIndexPath);

        CompletableFuture<VectorPopulationResult> resultFuture = new CompletableFuture<>();

        try {
            String nd4jConfigJson = captureNd4jConfig();

            // Build subprocess args - get defaults from persisted config services
            String callbackBaseUrl = subprocessConfigService != null
                    ? subprocessConfigService.getCallbackBaseUrl()
                    : (serverPortService != null ? serverPortService.getBaseUrl() : "http://localhost:8080");

            // Get the model identifier for looking up benchmark config
            String modelId = getStringOption(options, "modelId",
                    embeddingProperties != null ? embeddingProperties.getModelIdentifier() : "bge-base-en-v1.5");

            // Get embedding batch sizes from BENCHMARK CONFIG (AnseriniEmbeddingProperties)
            // This uses persisted benchmark results from batch-size-config.json
            // The encoder will internally sub-batch if sequences are long
            int propsOptimal = embeddingProperties != null ? embeddingProperties.getEffectiveOptimalBatchSize(modelId) : 32;
            int propsMax = embeddingProperties != null ? embeddingProperties.getEffectiveMaxBatchSize(modelId) : 64;
            boolean hasOptionOverride = options != null && options.containsKey("embeddingBatchSize");
            int embeddingBatchSize = getIntOption(options, "embeddingBatchSize", propsOptimal);
            int maxBatchSize = getIntOption(options, "maxBatchSize", propsMax);

            logger.info("Batch size resolution: propsOptimal={}, propsMax={}, hasOptionOverride={}, final embeddingBatchSize={}, maxBatchSize={}",
                    propsOptimal, propsMax, hasOptionOverride, embeddingBatchSize, maxBatchSize);

            // Get pipeline settings from subprocess config (SubprocessConfigService)
            int queueCapacity = getIntOption(options, "queueCapacity",
                    subprocessConfigService != null ? subprocessConfigService.getQueueCapacity() : 1000);
            boolean parallelIndexing = getBoolOption(options, "parallelIndexing",
                    subprocessConfigService != null ? subprocessConfigService.isParallelIndexing() : true);
            int indexingWorkers = getIntOption(options, "indexingWorkers",
                    subprocessConfigService != null ? subprocessConfigService.getIndexingWorkers() : 4);
            int indexingBatchAccumulationSize = getIntOption(options, "indexingBatchAccumulationSize",
                    subprocessConfigService != null ? subprocessConfigService.getIndexingBatchAccumulationSize() : 8);
            // Use 1 embedding worker - parallelism comes from OpenMP/BLAS internally
            int embeddingThreads = getIntOption(options, "embeddingThreads",
                    subprocessConfigService != null ? subprocessConfigService.getEmbeddingThreads() : 1);

            logger.info("Using benchmark config for model '{}': optimalBatch={}, maxBatch={}",
                    modelId, embeddingBatchSize, maxBatchSize);

            // Get model source configuration from parent's AnseriniEncoderFactory
            // This ensures subprocess uses the same model source (staging or archive) as
            // parent
            String modelSourceType = ai.kompile.embedding.anserini.AnseriniEncoderFactory.getSourceType();
            String modelIdentifier = ai.kompile.embedding.anserini.AnseriniEncoderFactory
                    .getSelectedDenseRetrievalModel()
                    .orElse(modelId);
            String stagingUrl = ai.kompile.embedding.anserini.AnseriniEncoderFactory.getStagingUrl();
            String stagingApiKey = ai.kompile.embedding.anserini.AnseriniEncoderFactory.getStagingApiKey();
            java.nio.file.Path archivePathObj = ai.kompile.embedding.anserini.AnseriniEncoderFactory
                    .getLoadedArchivePath();
            String archivePath = archivePathObj != null ? archivePathObj.toString() : null;

            logger.info("Passing model source to subprocess: type={}, model={}", modelSourceType, modelIdentifier);

            // Derive checkpoint path from vector index path (sibling directory)
            // This enables resume on failure - embeddings and progress are cached here
            String checkpointBasePath = null;
            if (vectorIndexPath != null) {
                Path vectorPath = Path.of(vectorIndexPath);
                Path checkpointPath = vectorPath.getParent() != null
                        ? vectorPath.getParent().resolve("checkpoints")
                        : Path.of("checkpoints");
                checkpointBasePath = checkpointPath.toString();
                logger.info("Checkpoint path for resume support: {}", checkpointBasePath);
            }

            // Get memory thresholds from SubprocessConfigService (or use defaults)
            // Note: VectorPopulation uses same thresholds as ingest for consistency
            int memoryThresholdPercent = VectorPopulationSubprocessArgs.DEFAULT_MEMORY_THRESHOLD_PERCENT;
            int memoryCriticalPercent = VectorPopulationSubprocessArgs.DEFAULT_MEMORY_CRITICAL_PERCENT;
            int memoryKillThresholdPercent = VectorPopulationSubprocessArgs.DEFAULT_MEMORY_KILL_THRESHOLD_PERCENT;
            
            // GPU memory thresholds (more conservative than heap)
            int gpuMemoryThresholdPercent = subprocessConfigService != null
                    ? subprocessConfigService.getGpuMemoryThresholdPercent()
                    : VectorPopulationSubprocessArgs.DEFAULT_GPU_MEMORY_THRESHOLD_PERCENT;
            int gpuMemoryCriticalPercent = subprocessConfigService != null
                    ? subprocessConfigService.getGpuMemoryCriticalPercent()
                    : VectorPopulationSubprocessArgs.DEFAULT_GPU_MEMORY_CRITICAL_PERCENT;
            int gpuMemoryKillThresholdPercent = subprocessConfigService != null
                    ? subprocessConfigService.getGpuMemoryKillThresholdPercent()
                    : VectorPopulationSubprocessArgs.DEFAULT_GPU_MEMORY_KILL_THRESHOLD_PERCENT;

            // Off-heap memory thresholds
            int offHeapThresholdPercent = subprocessConfigService != null
                    ? subprocessConfigService.getOffHeapThresholdPercent()
                    : VectorPopulationSubprocessArgs.DEFAULT_OFF_HEAP_THRESHOLD_PERCENT;
            int offHeapCriticalPercent = subprocessConfigService != null
                    ? subprocessConfigService.getOffHeapCriticalPercent()
                    : VectorPopulationSubprocessArgs.DEFAULT_OFF_HEAP_CRITICAL_PERCENT;
            int offHeapKillThresholdPercent = subprocessConfigService != null
                    ? subprocessConfigService.getOffHeapKillThresholdPercent()
                    : VectorPopulationSubprocessArgs.DEFAULT_OFF_HEAP_KILL_THRESHOLD_PERCENT;

            VectorPopulationSubprocessArgs args = VectorPopulationSubprocessArgs.builder()
                    .taskId(taskId)
                    .keywordIndexPath(keywordIndexPath)
                    .vectorIndexPath(vectorIndexPath)
                    .checkpointBasePath(checkpointBasePath)
                    .embeddingBatchSize(embeddingBatchSize)
                    .maxBatchSize(maxBatchSize)
                    .queueCapacity(queueCapacity)
                    .parallelIndexing(parallelIndexing)
                    .indexingWorkers(indexingWorkers)
                    .indexingBatchAccumulationSize(indexingBatchAccumulationSize)
                    .embeddingThreads(embeddingThreads)
                    .callbackBaseUrl(callbackBaseUrl)
                    .nd4jConfigJson(nd4jConfigJson)
                    .modelSourceType(modelSourceType)
                    .modelIdentifier(modelIdentifier)
                    .stagingUrl(stagingUrl)
                    .stagingApiKey(stagingApiKey)
                    .archivePath(archivePath)
                    .memoryThresholdPercent(memoryThresholdPercent)
                    .memoryCriticalPercent(memoryCriticalPercent)
                    .memoryKillThresholdPercent(memoryKillThresholdPercent)
                    .memoryCheckIntervalMs(VectorPopulationSubprocessArgs.DEFAULT_MEMORY_CHECK_INTERVAL_MS)
                    .gpuMemoryThresholdPercent(gpuMemoryThresholdPercent)
                    .gpuMemoryCriticalPercent(gpuMemoryCriticalPercent)
                    .gpuMemoryKillThresholdPercent(gpuMemoryKillThresholdPercent)
                    .offHeapThresholdPercent(offHeapThresholdPercent)
                    .offHeapCriticalPercent(offHeapCriticalPercent)
                    .offHeapKillThresholdPercent(offHeapKillThresholdPercent)
                    .options(options != null ? convertOptionsToStringMap(options) : Map.of())
                    .build();

            logger.info("Launching subprocess with config: batchSize={}, maxBatch={}, queue={}, " +
                    "indexThreads={}, indexBatchAccum={}, embeddingThreads={}",
                    embeddingBatchSize, maxBatchSize, queueCapacity,
                    indexingWorkers, indexingBatchAccumulationSize, embeddingThreads);

            logger.debug("Using callback URL: {}", callbackBaseUrl);

            // Write args to temp file
            Path argsFile = Files.createTempFile("vector-pop-args-" + taskId, ".json");
            args.toFile(argsFile);
            logger.debug("Wrote subprocess args to: {}", argsFile);

            // Extract memory overrides from options (used for restart recovery)
            String heapSizeOverride = getStringOption(options, "heapSize", null);
            Long offHeapOverride = getLongOption(options, "offHeapBytes", null);
            MemoryOverrides memoryOverrides = (heapSizeOverride != null || offHeapOverride != null)
                    ? new MemoryOverrides(heapSizeOverride, offHeapOverride)
                    : MemoryOverrides.none();

            // Extract thread overrides from options (used for restart recovery)
            Integer ompThreadsOverride = getIntOptionOrNull(options, "ompNumThreads");
            Integer blasThreadsOverride = getIntOptionOrNull(options, "openBlasNumThreads");
            ThreadOverrides threadOverrides = (ompThreadsOverride != null || blasThreadsOverride != null)
                    ? ThreadOverrides.from(
                            ompThreadsOverride != null ? ompThreadsOverride : 4,
                            blasThreadsOverride != null ? blasThreadsOverride : 4)
                    : ThreadOverrides.none();

            if (memoryOverrides.hasOverrides() || threadOverrides.hasOverrides()) {
                logger.info("RESTART RECOVERY: Using memory/thread overrides for task {}", taskId);
                if (memoryOverrides.hasOverrides()) {
                    logger.info("  Memory: heap={}, offHeap={}",
                            memoryOverrides.heapSize(),
                            memoryOverrides.offHeapBytes() != null
                                    ? SystemMemoryAnalyzer.formatBytes(memoryOverrides.offHeapBytes())
                                    : "default");
                }
                if (threadOverrides.hasOverrides()) {
                    logger.info("  Threads: OMP={}, BLAS={}",
                            threadOverrides.ompNumThreads(),
                            threadOverrides.openBlasNumThreads());
                }
            }

            // Build command with memory overrides
            List<String> command = buildCommand(argsFile, memoryOverrides);
            logger.info("Subprocess command: {}", String.join(" ", command));

            // Start process
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(false);

            // Propagate ND4J environment variables with thread overrides
            propagateNd4jEnvironment(processBuilder.environment(), threadOverrides);

            // === GPU LIFECYCLE: Acquire GPU resources for this vector population job ===
            if (modelLifecycleManager != null) {
                try {
                    modelLifecycleManager.acquireGpuForVectorPopulation(taskId);
                    logger.info("[vecpop-{}] GPU resources acquired for vector population job", taskId);
                } catch (IllegalStateException e) {
                    logger.warn("[vecpop-{}] Could not acquire GPU for vector population (may use CPU fallback): {}",
                            taskId, e.getMessage());
                }
            }

            Process process = processBuilder.start();
            logger.info("Started vector population subprocess with PID: {}", process.pid());

            // Record subprocess start for timing
            if (opTimingService != null) {
                opTimingService.recordSubprocessStart(taskId, "VECTOR_POPULATION");
            }

            // Create handle
            VectorPopulationHandle handle = new VectorPopulationHandle(
                    taskId, keywordIndexPath, vectorIndexPath,
                    process, resultFuture, argsFile);
            activeProcesses.put(taskId, handle);

            // Open subprocess log writer (Phase 2 log aggregation)
            try {
                SubprocessLogWriter logWriter = new SubprocessLogWriter("vector-population", taskId);
                String effectiveHeap = memoryOverrides.hasOverrides() && memoryOverrides.heapSize() != null
                        ? memoryOverrides.heapSize() : getEffectiveHeapSize();
                logWriter.writeStart(new SubprocessLogWriter.SubprocessRunContext(
                        taskId, command, System.getProperty("user.dir"), process.pid(), effectiveHeap));
                handle.logWriter = logWriter;
            } catch (Exception e) {
                logger.debug("[vector-pop-{}] Failed to open subprocess log writer: {}", taskId, e.getMessage());
            }

            // Start monitoring
            startMonitoring(handle);

            // Start tracking via progress tracker
            if (progressTracker != null) {
                progressTracker.startTask(taskId, keywordIndexPath, vectorIndexPath);
            }

            if (ingestProgressTracker != null) {
                String displayName = buildTaskDisplayName(vectorIndexPath);
                ingestProgressTracker.startTask(taskId, displayName);
                IngestStats stats = IngestStats.builder()
                        .subprocessRuntimeInfo(IngestProgressUpdate.SubprocessRuntimeInfo.forProcessMode("SUBPROCESS"))
                        .build();
                ingestProgressTracker.updateProgress(taskId, displayName, IngestPhase.LOADING, 0,
                        "Starting subprocess", "Initializing vector population...", stats);
            }

            // Send initial progress (also via direct WebSocket for backward compatibility)
            broadcastProgress(taskId, "INITIALIZING", 0, "Starting subprocess",
                    "Initializing ND4J and loading embedding model...", null);

            // Send a follow-up progress to show we're actively loading
            broadcastProgress(taskId, "INITIALIZING", 5, "Loading model",
                    "Loading embedding model weights (this may take a moment)...", null);

        } catch (Exception e) {
            logger.error("Failed to launch vector population subprocess for task: {}", taskId, e);
            resultFuture.completeExceptionally(e);
        }

        return resultFuture;
    }

    /**
     * Cancel a running subprocess.
     */
    public boolean cancelVectorPopulation(String taskId) {
        VectorPopulationHandle handle = activeProcesses.get(taskId);
        if (handle == null || !handle.isAlive()) {
            return false;
        }

        logger.info("Cancelling vector population subprocess for task: {}", taskId);
        handle.cancel();

        // Notify tracker
        if (progressTracker != null) {
            progressTracker.cancelTask(taskId, "Vector population cancelled by user");
        }
        if (ingestProgressTracker != null) {
            String displayName = buildTaskDisplayName(handle.getVectorIndexPath());
            IngestPhase ingestPhase = mapPhaseToIngestPhase(handle.getCurrentPhase());
            IngestStats stats = IngestStats.builder()
                    .subprocessRuntimeInfo(IngestProgressUpdate.SubprocessRuntimeInfo.forProcessMode("SUBPROCESS"))
                    .build();
            ingestProgressTracker.cancelTask(taskId, displayName, ingestPhase,
                    "Vector population cancelled by user", stats);
        }

        broadcastProgress(taskId, handle.getCurrentPhase(), handle.getProgressPercent(),
                "Cancelled", "Vector population cancelled by user", null);

        return true;
    }

    /**
     * Get status of a subprocess.
     */
    public VectorPopulationHandle.Status getStatus(String taskId) {
        VectorPopulationHandle handle = activeProcesses.get(taskId);
        if (handle == null) {
            return null;
        }
        return handle.getStatus();
    }

    /**
     * Get all active subprocess statuses.
     */
    public List<VectorPopulationHandle.Status> getAllStatuses() {
        List<VectorPopulationHandle.Status> statuses = new ArrayList<>();
        for (VectorPopulationHandle handle : activeProcesses.values()) {
            statuses.add(handle.getStatus());
        }
        return statuses;
    }

    /**
     * Memory overrides for subprocess restart.
     */
    public record MemoryOverrides(
            String heapSize,
            Long offHeapBytes
    ) {
        public static MemoryOverrides none() {
            return new MemoryOverrides(null, null);
        }

        public static MemoryOverrides of(String heap, long offHeap) {
            return new MemoryOverrides(heap, offHeap);
        }

        public boolean hasOverrides() {
            return (heapSize != null && !heapSize.isBlank()) || (offHeapBytes != null && offHeapBytes > 0);
        }
    }

    /**
     * Build the subprocess command.
     *
     * @param argsFile Path to the subprocess arguments JSON file
     * @param memoryOverrides Optional memory overrides from restart config. If null/empty, uses defaults.
     */
    private List<String> buildCommand(Path argsFile, MemoryOverrides memoryOverrides) {
        // Check if we should use native executable mode
        if (shouldUseNativeExecutableMode()) {
            return buildNativeCommand(argsFile);
        }

        // JVM classpath mode
        return buildJvmCommand(argsFile, memoryOverrides);
    }

    /**
     * Check if native executable mode should be used.
     * Uses SubprocessConfigService (UI-configured) for the decision.
     */
    private boolean shouldUseNativeExecutableMode() {
        // Use SubprocessConfigService (UI-managed) for the decision
        if (subprocessConfigService != null) {
            return subprocessConfigService.shouldUseNativeExecutableMode();
        }

        // Fallback: If running in native image and no classpath available, native mode is required
        if (NativeImageInfo.isRunningInNativeImage() && !NativeImageInfo.hasClasspath()) {
            return true;
        }

        return false;
    }

    /**
     * Build command for native executable mode.
     * Uses SubprocessConfigService (UI-configured) for executable paths.
     */
    private List<String> buildNativeCommand(Path argsFile) {
        if (subprocessConfigService == null) {
            throw new IllegalStateException(
                "Native executable mode required but SubprocessConfigService not available.");
        }

        String executablePath = subprocessConfigService.getExecutablePathForType("vector-population");
        if (executablePath == null || executablePath.isBlank()) {
            throw new IllegalStateException(
                "Native executable mode required but no executable path configured. " +
                "Configure the native executable path in Processing Settings (Developer Hub).");
        }

        List<String> command = new ArrayList<>();
        command.add(executablePath);

        // Add subprocess type flag if using unified executable
        if (subprocessConfigService.useUnifiedExecutable("vector-population")) {
            command.add(subprocessConfigService.getSubprocessTypeFlag() + "vector-population");
        }

        // Add args file
        command.add(argsFile.toString());

        logger.info("Using native executable mode for vector population subprocess: {}", executablePath);
        return command;
    }

    /**
     * Build command for JVM classpath mode.
     */
    private List<String> buildJvmCommand(Path argsFile, MemoryOverrides memoryOverrides) {
        List<String> command = new ArrayList<>();

        // Java executable
        command.add(getEffectiveJavaPath());

        // Heap size - use override if provided (for restart with adjusted memory)
        String effectiveHeapSize;
        if (memoryOverrides != null && memoryOverrides.heapSize() != null && !memoryOverrides.heapSize().isBlank()) {
            effectiveHeapSize = memoryOverrides.heapSize();
            logger.info("RESTART: Using heap size override: {} (was: {})", effectiveHeapSize, getEffectiveHeapSize());
        } else {
            effectiveHeapSize = getEffectiveHeapSize();
        }

        String heapSizeArg = toXmxArg(effectiveHeapSize);
        if (heapSizeArg != null) {
            command.add(heapSizeArg);
        }
        command.add("-XX:+ExitOnOutOfMemoryError");
        command.add("-Dfile.encoding=UTF-8");

        // Off-heap memory (JavaCPP) - use override if provided, otherwise derive from heap
        Long offHeapBytes;
        if (memoryOverrides != null && memoryOverrides.offHeapBytes() != null && memoryOverrides.offHeapBytes() > 0) {
            offHeapBytes = memoryOverrides.offHeapBytes();
            logger.info("RESTART: Using off-heap override: {} (org.bytedeco.javacpp.maxbytes)",
                    SystemMemoryAnalyzer.formatBytes(offHeapBytes));
        } else {
            // Default: off-heap = multiplier x heap
            Long heapBytes = parseMemoryToBytes(effectiveHeapSize);
            int multiplier = subprocessConfigService != null ? subprocessConfigService.getOffHeapMultiplier() : 2;
            offHeapBytes = (heapBytes != null) ? heapBytes * multiplier : null;
        }

        if (offHeapBytes != null && offHeapBytes > 0) {
            command.add("-Dorg.bytedeco.javacpp.maxbytes=" + offHeapBytes);
            command.add("-Dorg.bytedeco.javacpp.maxphysicalbytes=" + offHeapBytes);
            logger.debug("Set JavaCPP off-heap limits: maxbytes={}, maxphysicalbytes={}",
                    SystemMemoryAnalyzer.formatBytes(offHeapBytes), SystemMemoryAnalyzer.formatBytes(offHeapBytes));
        }

        // Classpath - use same classpath as running process
        String classpath = System.getProperty("java.class.path");
        command.add("-cp");
        command.add(classpath);

        // Main class
        command.add(SUBPROCESS_MAIN_CLASS);

        // Args file
        command.add(argsFile.toString());

        return command;
    }

    private String getEffectiveJavaPath() {
        if (subprocessConfigService != null) {
            String configured = subprocessConfigService.getJavaPath();
            if (configured != null && !configured.isBlank()) {
                return configured.trim();
            }
        }
        return fallbackJavaPath;
    }

    private String getEffectiveHeapSize() {
        if (subprocessConfigService != null) {
            String configured = subprocessConfigService.getHeapSize();
            if (configured != null && !configured.isBlank()) {
                return configured.trim();
            }
        }
        return fallbackHeapSize;
    }

    private int getEffectiveStaleThresholdSeconds() {
        if (subprocessConfigService != null) {
            return subprocessConfigService.getStaleThresholdSeconds();
        }
        return fallbackStaleThresholdSeconds;
    }

    private Long getEffectiveOffHeapMaxBytes() {
        String configured = null;
        if (subprocessConfigService != null) {
            configured = subprocessConfigService.getOffHeapMaxBytes();
        }
        Long configuredBytes = parseMemoryToBytes(configured);
        if (configuredBytes != null) {
            return configuredBytes;
        }

        Long heapBytes = parseMemoryToBytes(getEffectiveHeapSize());
        if (heapBytes == null) {
            return null;
        }
        try {
            int multiplier = subprocessConfigService != null ? subprocessConfigService.getOffHeapMultiplier() : 2;
            return Math.multiplyExact(heapBytes, (long) multiplier);
        } catch (ArithmeticException e) {
            return null;
        }
    }

    private static String toXmxArg(String heapSize) {
        if (heapSize == null) {
            return null;
        }
        String trimmed = heapSize.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.startsWith("-Xmx")) {
            return trimmed;
        }
        return "-Xmx" + trimmed;
    }

    private static final Pattern MEMORY_SIZE_PATTERN = Pattern.compile("^([0-9]+)\\s*([a-zA-Z]{0,2})$");

    private static Long parseMemoryToBytes(String value) {
        if (value == null) {
            return null;
        }
        String s = value.trim();
        if (s.isEmpty()) {
            return null;
        }

        s = s.replace("_", "").replace(",", "");
        Matcher matcher = MEMORY_SIZE_PATTERN.matcher(s);
        if (!matcher.matches()) {
            return null;
        }

        long amount;
        try {
            amount = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }

        String unitRaw = matcher.group(2) != null ? matcher.group(2).trim() : "";
        String unit = unitRaw.toLowerCase();

        if (unit.isEmpty() && amount > 0 && amount <= 1024) {
            unit = "g";
        }
        long multiplier = switch (unit) {
            case "", "b" -> 1L;
            case "k", "kb" -> 1024L;
            case "m", "mb" -> 1024L * 1024;
            case "g", "gb" -> 1024L * 1024 * 1024;
            case "t", "tb" -> 1024L * 1024 * 1024 * 1024;
            default -> -1L;
        };
        if (multiplier < 0) {
            return null;
        }

        try {
            return Math.multiplyExact(amount, multiplier);
        } catch (ArithmeticException e) {
            return null;
        }
    }

    /**
     * Start monitoring threads for a subprocess.
     */
    private void startMonitoring(VectorPopulationHandle handle) {
        // Start stdout reader
        Thread stdoutReader = new Thread(() -> readStdout(handle), "vector-pop-stdout-" + handle.getTaskId());
        stdoutReader.setDaemon(true);
        stdoutReader.start();

        // Start stderr reader
        Thread stderrReader = new Thread(() -> readStderr(handle), "vector-pop-stderr-" + handle.getTaskId());
        stderrReader.setDaemon(true);
        stderrReader.start();

        // Start process completion watcher
        Thread completionWatcher = new Thread(() -> watchCompletion(handle),
                "vector-pop-watcher-" + handle.getTaskId());
        completionWatcher.setDaemon(true);
        completionWatcher.start();
    }

    /**
     * Read and parse stdout from subprocess.
     */
    private void readStdout(VectorPopulationHandle handle) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(handle.getProcess().getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(SubprocessMessage.MESSAGE_PREFIX)) {
                    String json = line.substring(SubprocessMessage.MESSAGE_PREFIX.length());
                    handleMessage(handle, json);
                } else if (!line.isBlank()) {
                    logger.debug("[vector-pop-{}] {}", handle.getTaskId(), line);
                    // Forward non-message output to WebSocket for UI visibility
                    if (progressTracker != null) {
                        progressTracker.sendLog(handle.getTaskId(), "STDOUT", "INFO", line);
                    }
                    if (ingestProgressTracker != null) {
                        ingestProgressTracker.sendLog(handle.getTaskId(), "STDOUT", "INFO", line);
                    }
                    // Write to subprocess log file
                    SubprocessLogWriter lw = handle.logWriter;
                    if (lw != null) {
                        try {
                            lw.writeLine(AgentLogRecord.Stream.STDOUT, line);
                        } catch (Exception logEx) {
                            logger.debug("[vector-pop-{}] log write failed: {}", handle.getTaskId(), logEx.getMessage());
                        }
                    }
                }
            }
        } catch (IOException e) {
            if (!handle.isCancelled()) {
                logger.debug("Stdout reader terminated for task: {}", handle.getTaskId());
            }
        }
    }

    /**
     * Read stderr from subprocess.
     */
    private void readStderr(VectorPopulationHandle handle) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(handle.getProcess().getErrorStream()))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank())
                    continue;

                String level;
                if (line.contains("OutOfMemoryError") || line.contains("Java heap space")) {
                    logger.error("[vector-pop-{}] OOM detected: {}", handle.getTaskId(), line);
                    handle.setOomDetected(true);
                    level = "ERROR";
                } else if (line.startsWith("\tat") || line.startsWith("Caused by:") || line.startsWith("Suppressed:")) {
                    // Stack trace continuation lines often don't include ERROR/WARN tokens
                    logger.error("[vector-pop-{}] {}", handle.getTaskId(), line);
                    level = "ERROR";
                } else if (line.contains("ERROR") || line.contains("Exception") || line.contains("FATAL")) {
                    logger.error("[vector-pop-{}] {}", handle.getTaskId(), line);
                    level = "ERROR";
                } else if (line.contains("WARN")) {
                    logger.warn("[vector-pop-{}] {}", handle.getTaskId(), line);
                    level = "WARN";
                } else if (line.contains(" INFO ")) {
                    logger.info("[vector-pop-{}] {}", handle.getTaskId(), line);
                    level = "INFO";
                } else if (line.contains("DEBUG")) {
                    logger.debug("[vector-pop-{}] {}", handle.getTaskId(), line);
                    level = "DEBUG";
                } else {
                    logger.debug("[vector-pop-{}] {}", handle.getTaskId(), line);
                    level = "INFO";
                }

                // Forward stderr to WebSocket for UI visibility
                if (progressTracker != null) {
                    progressTracker.sendLog(handle.getTaskId(), "STDERR", level, line);
                }
                if (ingestProgressTracker != null) {
                    ingestProgressTracker.sendLog(handle.getTaskId(), "STDERR", level, line);
                }
                // Write to subprocess log file
                SubprocessLogWriter lw = handle.logWriter;
                if (lw != null) {
                    try {
                        lw.writeLine(AgentLogRecord.Stream.STDERR, line);
                    } catch (Exception logEx) {
                        logger.debug("[vector-pop-{}] log write failed: {}", handle.getTaskId(), logEx.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            if (!handle.isCancelled()) {
                logger.debug("Stderr reader terminated for task: {}", handle.getTaskId());
            }
        }
    }

    /**
     * Watch for process completion.
     */
    private void watchCompletion(VectorPopulationHandle handle) {
        try {
            int exitCode = handle.getProcess().waitFor();
            logger.info("Vector population subprocess {} exited with code: {}", handle.getTaskId(), exitCode);

            handleCompletion(handle, exitCode);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.debug("Completion watcher interrupted for task: {}", handle.getTaskId());
        } finally {
            cleanup(handle);
        }
    }

    /**
     * Handle a parsed message from subprocess.
     */
    private void handleMessage(VectorPopulationHandle handle, String json) {
        try {
            SubprocessMessage message = objectMapper.readValue(json, SubprocessMessage.class);
            logger.debug("Received subprocess message: type={}, taskId={}",
                    message.getClass().getSimpleName(), handle.getTaskId());

            if (message instanceof SubprocessMessage.Progress progress) {
                handle.updateProgress(progress.phase(), progress.progressPercent(), progress.message());
                warnedTaskIds.remove(handle.getTaskId());

                // Forward to progress tracker
                if (progressTracker != null) {
                    VectorPopulationStats stats = buildStatsFromProgress(progress);
                    progressTracker.updateProgress(
                            handle.getTaskId(),
                            mapPhaseToEnum(progress.phase()),
                            progress.progressPercent(),
                            progress.currentStep(),
                            progress.message(),
                            stats);
                }

                if (ingestProgressTracker != null) {
                    String displayName = buildTaskDisplayName(handle.getVectorIndexPath());
                    IngestStats ingestStats = buildIngestStatsFromProgress(progress, handle.getTaskId());
                    IngestPhase ingestPhase = mapPhaseToIngestPhase(progress.phase());
                    ingestProgressTracker.updateProgress(
                            handle.getTaskId(),
                            displayName,
                            ingestPhase,
                            progress.progressPercent(),
                            progress.currentStep(),
                            progress.message(),
                            ingestStats);
                }

                forwardProgress(handle, progress);
            } else if (message instanceof SubprocessMessage.PhaseTransition transition) {
                handle.setCurrentPhase(transition.toPhase());
                handle.updateHeartbeat();
                logger.info("Task {} phase transition: {} -> {}",
                        handle.getTaskId(), transition.fromPhase(), transition.toPhase());

                // Track model loading timing based on phase transitions
                if (opTimingService != null) {
                    String toPhase = transition.toPhase() != null ? transition.toPhase().toUpperCase() : "";
                    String fromPhase = transition.fromPhase() != null ? transition.fromPhase().toUpperCase() : "";

                    // Record model load start when entering LOADING or MODEL_LOADING phase
                    if (toPhase.equals("LOADING") || toPhase.equals("MODEL_LOADING") || toPhase.equals("INITIALIZING")) {
                        opTimingService.recordModelLoadStart(handle.getTaskId(), "embedding-model");
                    }
                    // Record model load complete when leaving LOADING phase (transitioning to EMBEDDING)
                    else if ((fromPhase.equals("LOADING") || fromPhase.equals("MODEL_LOADING") || fromPhase.equals("INITIALIZING"))
                            && (toPhase.equals("EMBEDDING") || toPhase.equals("INDEXING") || toPhase.equals("PROCESSING"))) {
                        opTimingService.recordModelLoadComplete(handle.getTaskId());
                    }
                }

                // Forward phase transition to tracker
                if (progressTracker != null) {
                    progressTracker.updateProgress(
                            handle.getTaskId(),
                            mapPhaseToEnum(transition.toPhase()),
                            0,
                            "Starting " + transition.toPhase().toLowerCase(),
                            "Phase transition: " + transition.fromPhase() + " -> " + transition.toPhase(),
                            null);
                }

                if (ingestProgressTracker != null) {
                    String displayName = buildTaskDisplayName(handle.getVectorIndexPath());
                    IngestPhase ingestPhase = mapPhaseToIngestPhase(transition.toPhase());
                    IngestStats ingestStats = IngestStats.builder()
                            .subprocessRuntimeInfo(
                                    IngestProgressUpdate.SubprocessRuntimeInfo.forProcessMode("SUBPROCESS"))
                            .build();
                    ingestProgressTracker.updateProgress(
                            handle.getTaskId(),
                            displayName,
                            ingestPhase,
                            0,
                            "Starting " + transition.toPhase().toLowerCase(),
                            "Phase transition: " + transition.fromPhase() + " -> " + transition.toPhase(),
                            ingestStats);
                }

                broadcastProgress(handle.getTaskId(), transition.toPhase(), 0,
                        "Starting " + transition.toPhase().toLowerCase(),
                        "Phase transition: " + transition.fromPhase() + " -> " + transition.toPhase(),
                        null);
            } else if (message instanceof SubprocessMessage.Heartbeat heartbeat) {
                // Record startup complete on first heartbeat (indicates subprocess is up and running)
                if (opTimingService != null && !handle.isStartupComplete()) {
                    opTimingService.recordSubprocessStartupComplete(handle.getTaskId());
                    handle.setStartupComplete(true);
                }
                handle.updateHeartbeat(heartbeat);
                logger.debug("Task {} heartbeat: uptime={}ms, heap={}%, offHeap={}%, gpu={}%",
                        handle.getTaskId(), heartbeat.uptimeMs(),
                        String.format("%.1f", heartbeat.memoryUsagePercent()),
                        String.format("%.1f", heartbeat.offHeapUsagePercent()),
                        String.format("%.1f", heartbeat.gpuUsagePercent()));
            } else if (message instanceof SubprocessMessage.Log log) {
                // Forward structured subprocess logs to UI (in addition to raw stderr/stdout
                // forwarding)
                if (progressTracker != null) {
                    progressTracker.sendLog(handle.getTaskId(), log.source(), log.level(), log.message());
                }
                if (ingestProgressTracker != null) {
                    ingestProgressTracker.sendLog(handle.getTaskId(), log.source(), log.level(), log.message());
                }
            } else if (message instanceof SubprocessMessage.Completed completed) {
                logger.info("Task {} completed: {} docs embedded and indexed",
                        handle.getTaskId(), completed.documentsIndexed());

                // Record subprocess completion for timing
                if (opTimingService != null) {
                    opTimingService.recordSubprocessComplete(handle.getTaskId(), true);
                }

                handle.getResultFuture().complete(VectorPopulationResult.success(
                        handle.getTaskId(), completed.documentsLoaded(), completed.chunksEmbedded(),
                        completed.documentsIndexed(), completed.totalDurationMs(), handle.getVectorIndexPath()));

                // Forward completion to tracker
                if (progressTracker != null) {
                    VectorPopulationStats finalStats = new VectorPopulationStats(
                            completed.documentsLoaded(),
                            completed.chunksCreated(),
                            completed.chunksEmbedded(),
                            completed.documentsIndexed(),
                            completed.documentsLoaded(),
                            completed.tokensProcessed(),
                            completed.totalTokensInIndex(),
                            completed.totalDurationMs(),
                            completed.documentsIndexed() > 0 && completed.totalDurationMs() > 0
                                    ? (completed.documentsIndexed() * 1000.0 / completed.totalDurationMs())
                                    : 0,
                            0,
                            null,
                            null,
                            null,
                            null, // batchHistory - not available on completion
                            IngestProgressUpdate.SubprocessRuntimeInfo.forProcessMode("SUBPROCESS"));
                    progressTracker.completeTask(handle.getTaskId(), finalStats);
                }

                if (ingestProgressTracker != null) {
                    String displayName = buildTaskDisplayName(handle.getVectorIndexPath());
                    IngestStats ingestStats = buildIngestStatsFromCompleted(completed);
                    ingestProgressTracker.completeTask(handle.getTaskId(), displayName, ingestStats);
                }

                forwardCompletion(handle, completed);
                closeSubprocessLog(handle, "COMPLETED", 0, null, false, false);
            } else if (message instanceof SubprocessMessage.Failed failed) {
                logger.error("Task {} failed in phase {}: {}",
                        handle.getTaskId(), failed.phase(), failed.errorMessage());

                // Determine the failure reason to decide if we should restart
                SubprocessRestartManager.FailureReason failureReason =
                        determineFailureReason(failed.errorMessage(), failed.errorType());

                // Check if this is a restartable failure (OOM or batch size too large)
                boolean isRestartableFailure = failureReason == SubprocessRestartManager.FailureReason.OUT_OF_MEMORY ||
                        failureReason == SubprocessRestartManager.FailureReason.BATCH_SIZE_TOO_LARGE;

                if (isRestartableFailure) {
                    // Store the failure reason for handleCompletion to use
                    handle.setOomDetected(true);  // Reuse this flag for any restartable failure
                    handle.setCurrentPhase(failed.phase());
                    handle.setFailureReason(failureReason);  // Store the actual reason

                    String recoveryType = failureReason == SubprocessRestartManager.FailureReason.BATCH_SIZE_TOO_LARGE
                            ? "BATCH SIZE TOO LARGE" : "OOM";
                    String recoveryAction = failureReason == SubprocessRestartManager.FailureReason.BATCH_SIZE_TOO_LARGE
                            ? "reducing batch size by 75%" : "adjusting memory settings";

                    logger.warn("{} detected via protocol message for task {} - " +
                            "NOT marking as failed yet, will attempt restart after process exit ({})",
                            recoveryType, handle.getTaskId(), recoveryAction);

                    // Send restart notification to UI
                    if (ingestProgressTracker != null) {
                        String displayName = buildTaskDisplayName(handle.getVectorIndexPath());
                        ingestProgressTracker.sendLog(handle.getTaskId(), "SYSTEM", "WARN",
                                "[ADAPTIVE RECOVERY] " + recoveryType + " detected during " + failed.phase() +
                                " - subprocess will restart with " + recoveryAction);
                    }

                    // Broadcast warning to WebSocket (but not as failure)
                    broadcastProgress(handle.getTaskId(), "RECOVERY_SCHEDULED",
                            handle.getProgressPercent(),
                            "Adaptive Recovery",
                            recoveryType + " detected during " + failed.phase() + " - " + recoveryAction,
                            Map.of("phase", failed.phase(),
                                   "failureReason", failureReason.name(),
                                   "isRecovery", true));

                    // DON'T complete result future or fail task - let handleCompletion do it
                    return;
                }

                // Non-restartable failure - proceed with normal failure handling
                // Record subprocess completion (as failure) for timing
                if (opTimingService != null) {
                    opTimingService.recordSubprocessComplete(handle.getTaskId(), false);
                }

                handle.getResultFuture().complete(VectorPopulationResult.failure(
                        handle.getTaskId(), failed.phase(), failed.errorMessage()));

                // Forward failure to tracker
                if (progressTracker != null) {
                    progressTracker.failTask(handle.getTaskId(), mapPhaseToEnum(failed.phase()), failed.errorMessage());
                }

                if (ingestProgressTracker != null) {
                    String displayName = buildTaskDisplayName(handle.getVectorIndexPath());
                    IngestPhase ingestPhase = mapPhaseToIngestPhase(failed.phase());
                    ingestProgressTracker.failTask(handle.getTaskId(), displayName, ingestPhase, failed.errorMessage());
                }

                forwardFailure(handle, failed);
                closeSubprocessLog(handle, "FAILED", null, failed.errorMessage(), false, false);
            }
        } catch (Exception e) {
            logger.warn("Failed to parse subprocess message: {}", json, e);
            if (progressTracker != null) {
                progressTracker.sendLog(handle.getTaskId(), "PARENT", "ERROR",
                        "Failed to parse subprocess protocol message: " + e.getMessage());
            }
            if (ingestProgressTracker != null) {
                ingestProgressTracker.sendLog(handle.getTaskId(), "PARENT", "ERROR",
                        "Failed to parse subprocess protocol message: " + e.getMessage());
            }
        }
    }

    /**
     * Forward progress to WebSocket.
     */
    private void forwardProgress(VectorPopulationHandle handle, SubprocessMessage.Progress progress) {
        SubprocessMessage.ProgressStats stats = progress.stats();

        Map<String, Object> progressUpdate = new LinkedHashMap<>();
        progressUpdate.put("taskId", handle.getTaskId());
        progressUpdate.put("phase", progress.phase());
        progressUpdate.put("progressPercent", progress.progressPercent());
        progressUpdate.put("currentStep", progress.currentStep());
        progressUpdate.put("message", progress.message());
        progressUpdate.put("keywordIndexPath", handle.getKeywordIndexPath());
        progressUpdate.put("vectorIndexPath", handle.getVectorIndexPath());

        VectorPopulationStats derivedStats = buildStatsFromProgress(progress);
        if (derivedStats != null) {
            long elapsedMs = handle.getElapsedMs();
            Map<String, Object> statsMap = new LinkedHashMap<>();
            int documentsLoaded = derivedStats.documentsLoaded();
            statsMap.put("documentsLoaded", documentsLoaded);
            statsMap.put("documentsProcessed", documentsLoaded);
            statsMap.put("totalDocuments", derivedStats.totalDocuments());
            statsMap.put("chunksCreated", derivedStats.chunksCreated());
            statsMap.put("chunksEmbedded", derivedStats.chunksEmbedded());
            statsMap.put("chunksIndexed", derivedStats.chunksIndexed());
            statsMap.put("throughputDocsPerSec", derivedStats.throughputDocsPerSec());
            statsMap.put("chunksPerSecond", derivedStats.throughputDocsPerSec());
            statsMap.put("elapsedTimeMs", elapsedMs);
            statsMap.put("totalProcessingTimeMs", elapsedMs);
            statsMap.put("memoryUsagePercent", derivedStats.memoryUsagePercent());
            if (stats != null) {
                statsMap.put("activeStage", stats.activeStage());
                statsMap.put("pipelineStatus", stats.pipelineStatus());
            }
            statsMap.put("workerStatuses", derivedStats.workerStatuses());
            statsMap.put("queueStatus", derivedStats.queueStatus());
            statsMap.put("currentEmbeddingBatch", derivedStats.currentEmbeddingBatch());
            statsMap.put("batchHistory", derivedStats.batchHistory());
            statsMap.put("runtimeInfo", derivedStats.runtimeInfo());
            progressUpdate.put("stats", statsMap);
        }

        broadcastToWebSocket(progressUpdate);
    }

    /**
     * Forward completion to WebSocket.
     */
    private void forwardCompletion(VectorPopulationHandle handle, SubprocessMessage.Completed completed) {
        // For vector population from existing index, use chunks; for document ingest, use documents
        int itemsProcessed = completed.documentsIndexed() > 0
                ? completed.documentsIndexed()
                : completed.chunksEmbedded();
        String itemType = completed.documentsIndexed() > 0 ? "documents" : "chunks";

        double throughput = completed.totalDurationMs() > 0
                ? (itemsProcessed * 1000.0 / completed.totalDurationMs())
                : 0.0;
        Map<String, Object> progressUpdate = new LinkedHashMap<>();
        progressUpdate.put("taskId", handle.getTaskId());
        progressUpdate.put("phase", "COMPLETED");
        progressUpdate.put("progressPercent", 100);
        progressUpdate.put("currentStep", "Complete");
        progressUpdate.put("message", String.format("Vector population complete! %d %s indexed (%.1f %s/sec)",
                itemsProcessed, itemType, throughput, itemType));
        progressUpdate.put("keywordIndexPath", handle.getKeywordIndexPath());
        progressUpdate.put("vectorIndexPath", handle.getVectorIndexPath());

        Map<String, Object> statsMap = new LinkedHashMap<>();
        int documentsLoaded = completed.documentsLoaded();
        statsMap.put("documentsLoaded", documentsLoaded);
        statsMap.put("documentsProcessed", documentsLoaded);
        statsMap.put("totalDocuments", documentsLoaded);
        statsMap.put("chunksCreated", completed.chunksCreated());
        statsMap.put("chunksEmbedded", completed.chunksEmbedded());
        statsMap.put("chunksIndexed", completed.documentsIndexed());
        statsMap.put("throughputDocsPerSec", throughput);
        statsMap.put("chunksPerSecond", throughput);
        statsMap.put("elapsedTimeMs", completed.totalDurationMs());
        statsMap.put("totalProcessingTimeMs", completed.totalDurationMs());
        statsMap.put("totalDurationMs", completed.totalDurationMs());
        statsMap.put("phaseDurations", completed.phaseDurations());
        progressUpdate.put("stats", statsMap);

        broadcastToWebSocket(progressUpdate);
    }

    /**
     * Forward failure to WebSocket.
     */
    private void forwardFailure(VectorPopulationHandle handle, SubprocessMessage.Failed failed) {
        Map<String, Object> progressUpdate = new LinkedHashMap<>();
        progressUpdate.put("taskId", handle.getTaskId());
        progressUpdate.put("phase", "FAILED");
        progressUpdate.put("progressPercent", 0);
        progressUpdate.put("currentStep", "Failed");
        progressUpdate.put("message", failed.errorMessage());
        progressUpdate.put("errorPhase", failed.phase());
        progressUpdate.put("keywordIndexPath", handle.getKeywordIndexPath());
        progressUpdate.put("vectorIndexPath", handle.getVectorIndexPath());

        broadcastToWebSocket(progressUpdate);
    }

    /**
     * Broadcast progress update via WebSocket.
     */
    private void broadcastProgress(String taskId, String phase, int progressPercent,
            String currentStep, String message, Map<String, Object> stats) {
        Map<String, Object> progressUpdate = new LinkedHashMap<>();
        progressUpdate.put("taskId", taskId);
        progressUpdate.put("phase", phase);
        progressUpdate.put("progressPercent", progressPercent);
        progressUpdate.put("currentStep", currentStep);
        progressUpdate.put("message", message);
        if (stats != null) {
            progressUpdate.put("stats", stats);
        }

        broadcastToWebSocket(progressUpdate);
    }

    private void broadcastToWebSocket(Map<String, Object> progressUpdate) {
        if (messagingTemplate != null) {
            try {
                messagingTemplate.convertAndSend(VECTOR_POPULATION_TOPIC, progressUpdate);
                logger.debug("Broadcast vector population progress: {}", progressUpdate.get("taskId"));
            } catch (Exception e) {
                logger.warn("Failed to broadcast progress via WebSocket: {}", e.getMessage());
            }
        }
    }

    /**
     * Handle process completion.
     */
    private void handleCompletion(VectorPopulationHandle handle, int exitCode) {
        if (handle.getResultFuture().isDone()) {
            return;
        }

        if (exitCode == 0) {
            String errorMessage = "Process exited successfully (0) but no completion message was received";
            logger.error("Vector population subprocess {}: {}", handle.getTaskId(), errorMessage);
            handle.getResultFuture().complete(VectorPopulationResult.failure(
                    handle.getTaskId(), handle.getCurrentPhase(), errorMessage));

            if (progressTracker != null) {
                progressTracker.failTask(handle.getTaskId(), mapPhaseToEnum(handle.getCurrentPhase()), errorMessage);
            }
            if (ingestProgressTracker != null) {
                String displayName = buildTaskDisplayName(handle.getVectorIndexPath());
                IngestPhase ingestPhase = mapPhaseToIngestPhase(handle.getCurrentPhase());
                ingestProgressTracker.failTask(handle.getTaskId(), displayName, ingestPhase, errorMessage);
            }

            broadcastProgress(handle.getTaskId(), "FAILED", 0, "Failed", errorMessage, null);
            closeSubprocessLog(handle, "FAILED", exitCode, errorMessage, false, false);
        } else {
            String errorMessage;
            boolean isNativeCrash = false;
            boolean isCancelled = false;
            boolean isOomKilled = false;

            if (handle.isCancelled()) {
                errorMessage = "Process cancelled";
                isCancelled = true;
            } else if (handle.isOomDetected()) {
                // Get the stored failure reason (could be OOM, BATCH_SIZE_TOO_LARGE, etc.)
                FailureReason storedReason = handle.getFailureReason();
                FailureReason reason = storedReason != null ? storedReason : FailureReason.OUT_OF_MEMORY;

                // Set appropriate error message based on failure type
                if (reason == FailureReason.BATCH_SIZE_TOO_LARGE) {
                    errorMessage = "Batch size too large - array exceeds Integer.MAX_VALUE";
                    logger.info("=== BATCH_SIZE_TOO_LARGE DETECTED for task {} - attempting restart with reduced batch size ===",
                            handle.getTaskId());
                } else {
                    errorMessage = "Out of memory";
                    isOomKilled = true;
                    logger.info("=== OOM DETECTED for task {} - attempting restart ===", handle.getTaskId());
                }

                // Check if we should attempt automatic restart
                if (restartManager != null && !handle.isCancelled()) {
                    if (restartManager.shouldRestart(handle.getTaskId(), reason)) {
                        logger.info("=== SCHEDULING RESTART for task {} (reason: {}) ===", handle.getTaskId(), reason);
                        scheduleRestart(handle, exitCode, false, reason, errorMessage);
                        return; // Don't complete as failure yet - restart in progress
                    } else {
                        logger.warn("restartManager.shouldRestart returned false for task {} (reason: {}) - " +
                                "either restart disabled or max attempts reached", handle.getTaskId(), reason);
                    }
                } else if (restartManager == null) {
                    logger.error("CANNOT RESTART: restartManager is NULL! {} will be reported as failure.", reason);
                    logger.error("This is a configuration error - SubprocessRestartManager bean is not available.");
                } else if (handle.isCancelled()) {
                    logger.info("Task {} was cancelled - not restarting", handle.getTaskId());
                }
            } else if (exitCode == 130) {
                errorMessage = "Process interrupted (SIGINT)";
                isCancelled = true;
            } else if (exitCode == 134) {
                // SIGABRT - often from native assertion failure or abort()
                errorMessage = "Native crash (SIGABRT) - likely ND4J/native library assertion failure";
                isNativeCrash = true;
            } else if (exitCode == 136) {
                // SIGFPE - floating point exception
                errorMessage = "Native crash (SIGFPE) - floating point exception in native code";
                isNativeCrash = true;
            } else if (exitCode == 139) {
                // SIGSEGV - segmentation fault
                errorMessage = "Native crash (SIGSEGV) - segmentation fault in ND4J/native code";
                isNativeCrash = true;
            } else if (exitCode == 137) {
                // SIGKILL - killed (often OOM killer)
                errorMessage = "Process killed (SIGKILL) - likely OOM killer or manual termination";
                isOomKilled = true;

                logger.info("=== EXIT CODE 137 (OOM KILLED) for task {} - attempting restart ===", handle.getTaskId());

                // Check if we should attempt automatic restart
                if (restartManager != null && !handle.isCancelled()) {
                    FailureReason reason = FailureReason.OOM_KILLED;
                    if (restartManager.shouldRestart(handle.getTaskId(), reason)) {
                        logger.info("=== SCHEDULING RESTART for task {} (OOM killed) ===", handle.getTaskId());
                        scheduleRestart(handle, exitCode, true, reason, errorMessage);
                        return; // Don't complete as failure yet - restart in progress
                    } else {
                        logger.warn("restartManager.shouldRestart returned false for task {} - " +
                                "either restart disabled or max attempts reached", handle.getTaskId());
                    }
                } else if (restartManager == null) {
                    logger.error("CANNOT RESTART: restartManager is NULL! OOM killer exit will be reported as failure.");
                }
            } else if (exitCode == 143) {
                // SIGTERM - terminated
                errorMessage = "Process terminated (SIGTERM)";
                isCancelled = true;
            } else if (exitCode > 128) {
                // Other signal-based exit (128 + signal number)
                int signal = exitCode - 128;
                errorMessage = "Process killed by signal " + signal + " - possible native crash";
                isNativeCrash = true;
            } else {
                errorMessage = "Process exited with code " + exitCode;
            }

            // Log with appropriate level - native crashes are critical
            if (isNativeCrash) {
                logger.error("NATIVE CRASH in vector population subprocess {} during phase {}: {} (exit code {}). " +
                        "The parent process is unaffected due to subprocess isolation.",
                        handle.getTaskId(), handle.getCurrentPhase(), errorMessage, exitCode);
            } else {
                logger.error("Vector population subprocess {} failed: {} (exit code {})",
                        handle.getTaskId(), errorMessage, exitCode);
            }

            handle.getResultFuture().complete(VectorPopulationResult.failure(
                    handle.getTaskId(), handle.getCurrentPhase(), errorMessage));

            // Build UI-friendly message for native crashes
            String uiMessage = isNativeCrash
                    ? "Native crash in embedding/indexing - see logs for details"
                    : errorMessage;

            // Update progress tracker with failure (ensures UI gets the failure
            // notification)
            if (progressTracker != null) {
                if (isCancelled) {
                    progressTracker.cancelTask(handle.getTaskId(), uiMessage);
                } else {
                    progressTracker.failTask(handle.getTaskId(), mapPhaseToEnum(handle.getCurrentPhase()), uiMessage);
                }
            }
            if (ingestProgressTracker != null) {
                String displayName = buildTaskDisplayName(handle.getVectorIndexPath());
                IngestPhase ingestPhase = mapPhaseToIngestPhase(handle.getCurrentPhase());
                if (isCancelled) {
                    IngestStats stats = IngestStats.builder()
                            .subprocessRuntimeInfo(
                                    IngestProgressUpdate.SubprocessRuntimeInfo.forProcessMode("SUBPROCESS"))
                            .build();
                    ingestProgressTracker.cancelTask(handle.getTaskId(), displayName, ingestPhase, uiMessage, stats);
                } else {
                    ingestProgressTracker.failTask(handle.getTaskId(), displayName, ingestPhase, uiMessage);
                }
            }

            // Also broadcast directly via WebSocket for backward compatibility
            broadcastProgress(handle.getTaskId(), "FAILED", 0, "Failed", uiMessage, null);
            String logState = isCancelled ? "CANCELLED" : (isNativeCrash ? "CRASHED" : "FAILED");
            closeSubprocessLog(handle, logState, exitCode, uiMessage, isOomKilled, false);
        }
    }

    /**
     * Schedule a restart attempt after a restartable failure (OOM, batch size too large, etc.).
     *
     * @param handle        The failed process handle
     * @param exitCode      The exit code from the failed process
     * @param wasOomKilled  True if exit code was 137 (OOM killer)
     * @param failureReason The specific reason for the failure (used to determine recovery strategy)
     * @param errorMessage  The error message from the failure
     */
    private void scheduleRestart(VectorPopulationHandle handle, int exitCode, boolean wasOomKilled,
                                  FailureReason failureReason, String errorMessage) {
        String taskId = handle.getTaskId();
        String fileName = buildTaskDisplayName(handle.getVectorIndexPath());

        String recoveryType = switch (failureReason) {
            case BATCH_SIZE_TOO_LARGE -> "BATCH SIZE REDUCTION";
            case OOM_KILLED -> "OOM KILLER RECOVERY";
            case OUT_OF_MEMORY -> "OOM RECOVERY";
            default -> "ADAPTIVE RECOVERY";
        };

        logger.info("=== {} TRIGGERED for task {} ===", recoveryType, taskId);
        logger.info("Failure: reason={}, wasOomKilled={}, exitCode={}, error={}",
                failureReason, wasOomKilled, exitCode, errorMessage);

        // Get current heap size from config
        String currentHeapSize = subprocessConfigService != null
                ? subprocessConfigService.getHeapSize()
                : fallbackHeapSize;
        Long parsedHeapBytes = SystemMemoryAnalyzer.parseMemoryToBytes(currentHeapSize);
        long currentHeapBytes = (parsedHeapBytes != null) ? parsedHeapBytes : 4L * 1024 * 1024 * 1024; // Default 4GB

        // Get current off-heap size from config (default = 2x heap)
        String currentOffHeapStr = subprocessConfigService != null
                ? subprocessConfigService.getOffHeapMaxBytes()
                : null;
        Long parsedOffHeapBytes = SystemMemoryAnalyzer.parseMemoryToBytes(currentOffHeapStr);
        long currentOffHeapBytes = (parsedOffHeapBytes != null) ? parsedOffHeapBytes : currentHeapBytes * 2;

        // Get current thread settings from config or defaults
        int currentOmpThreads = getConfigInt("OMP_NUM_THREADS", 4);
        int currentBlasThreads = getConfigInt("OPENBLAS_NUM_THREADS", 4);
        int currentMaxThreads = subprocessConfigService != null
                ? subprocessConfigService.getEmbeddingThreads()
                : 1;
        int currentBatchSize = embeddingProperties != null
                ? embeddingProperties.getBaseOptimalBatchSize()
                : 4;

        logger.info("Current settings: heap={}, offHeap={}, OMP={}, BLAS={}, maxThreads={}, batch={}",
                currentHeapSize,
                SystemMemoryAnalyzer.formatBytes(currentOffHeapBytes),
                currentOmpThreads, currentBlasThreads, currentMaxThreads, currentBatchSize);

        // Get restart configuration with memory analysis (includes both heap and off-heap)
        // Use the failureReason-aware overload for targeted adjustments (e.g., aggressive batch reduction)
        RestartConfig restartConfig = restartManager.getRestartConfig(
                taskId, failureReason, wasOomKilled, currentHeapBytes, currentOffHeapBytes,
                currentOmpThreads, currentBlasThreads, currentMaxThreads, currentBatchSize);

        logger.info("Restart config computed (reason={}): {}", failureReason, restartConfig.getSummary());

        // Log memory analysis event
        if (ingestEventService != null) {
            SystemMemoryAnalyzer.MemoryStatus memStatus = restartConfig.memoryAnalysis();
            try {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("totalSystemRam", memStatus.totalSystemRamBytes());
                details.put("totalSystemRamFormatted", SystemMemoryAnalyzer.formatBytes(memStatus.totalSystemRamBytes()));
                details.put("usedSystemRam", memStatus.usedSystemRamBytes());
                details.put("freeSystemRam", memStatus.availableSystemRamBytes());
                details.put("currentHeap", currentHeapSize);
                details.put("currentHeapBytes", currentHeapBytes);
                details.put("recommendedHeap", restartConfig.heapSize());
                details.put("recommendedHeapBytes", restartConfig.heapSizeBytes());
                details.put("heapIncreased", restartConfig.heapIncreased());
                details.put("currentOffHeap", SystemMemoryAnalyzer.formatBytes(currentOffHeapBytes));
                details.put("currentOffHeapBytes", currentOffHeapBytes);
                details.put("recommendedOffHeap", SystemMemoryAnalyzer.formatBytes(restartConfig.offHeapBytes()));
                details.put("recommendedOffHeapBytes", restartConfig.offHeapBytes());
                details.put("offHeapReduced", restartConfig.offHeapReduced());
                details.put("ompThreads", restartConfig.ompNumThreads());
                details.put("blasThreads", restartConfig.openBlasNumThreads());
                details.put("maxThreads", restartConfig.maxThreads());
                details.put("batchSize", restartConfig.batchSize());
                details.put("originalBatchSize", currentBatchSize);
                details.put("batchSizeReduced", restartConfig.batchSize() < currentBatchSize);
                details.put("wasOomKilled", wasOomKilled);
                details.put("exitCode", exitCode);
                details.put("failureReason", failureReason.name());

                String detailsJson = objectMapper.writeValueAsString(details);
                ingestEventService.logMemoryAnalysis(taskId, fileName,
                        IngestEvent.IngestPhase.EMBEDDING, memStatus.canIncreaseHeap(),
                        memStatus.reason(), detailsJson);
            } catch (Exception e) {
                logger.warn("Failed to serialize memory analysis details: {}", e.getMessage());
            }
        }

        // Log restart scheduled event
        if (ingestEventService != null) {
            try {
                Map<String, Object> restartDetails = new LinkedHashMap<>();
                restartDetails.put("heapSize", restartConfig.heapSize());
                restartDetails.put("heapSizeBytes", restartConfig.heapSizeBytes());
                restartDetails.put("heapIncreased", restartConfig.heapIncreased());
                restartDetails.put("offHeapBytes", restartConfig.offHeapBytes());
                restartDetails.put("offHeapFormatted", SystemMemoryAnalyzer.formatBytes(restartConfig.offHeapBytes()));
                restartDetails.put("offHeapReduced", restartConfig.offHeapReduced());
                restartDetails.put("ompThreads", restartConfig.ompNumThreads());
                restartDetails.put("blasThreads", restartConfig.openBlasNumThreads());
                restartDetails.put("maxThreads", restartConfig.maxThreads());
                restartDetails.put("batchSize", restartConfig.batchSize());
                restartDetails.put("originalBatchSize", currentBatchSize);
                restartDetails.put("batchSizeReduced", restartConfig.batchSize() < currentBatchSize);
                restartDetails.put("failureReason", failureReason.name());

                String detailsJson = objectMapper.writeValueAsString(restartDetails);
                ingestEventService.logRestartScheduled(taskId, fileName,
                        IngestEvent.IngestPhase.EMBEDDING,
                        restartConfig.attemptNumber(), restartConfig.maxAttempts(),
                        restartConfig.backoffMs(), detailsJson);
            } catch (Exception e) {
                logger.warn("Failed to serialize restart scheduled details: {}", e.getMessage());
            }
        }

        // Build user-friendly message describing the adjustments based on failure reason
        StringBuilder adjustmentMsg = new StringBuilder();
        adjustmentMsg.append(String.format("Restart %d/%d in %.1fs - ",
                restartConfig.attemptNumber(), restartConfig.maxAttempts(),
                restartConfig.backoffMs() / 1000.0));

        boolean batchSizeReduced = restartConfig.batchSize() < currentBatchSize;

        // Prioritize the most relevant adjustment in the message
        if (failureReason == FailureReason.BATCH_SIZE_TOO_LARGE && batchSizeReduced) {
            adjustmentMsg.append(String.format("reducing batch size from %d to %d (75%% reduction)",
                    currentBatchSize, restartConfig.batchSize()));
        } else if (restartConfig.offHeapReduced()) {
            adjustmentMsg.append(String.format("reducing off-heap to %s",
                    SystemMemoryAnalyzer.formatBytes(restartConfig.offHeapBytes())));
            if (batchSizeReduced) {
                adjustmentMsg.append(String.format(", batch %d->%d", currentBatchSize, restartConfig.batchSize()));
            }
        } else if (restartConfig.heapIncreased()) {
            adjustmentMsg.append(String.format("increasing heap to %s", restartConfig.heapSize()));
            if (batchSizeReduced) {
                adjustmentMsg.append(String.format(", reducing batch %d->%d", currentBatchSize, restartConfig.batchSize()));
            }
        } else if (batchSizeReduced) {
            adjustmentMsg.append(String.format("reducing batch size %d->%d", currentBatchSize, restartConfig.batchSize()));
        } else {
            adjustmentMsg.append("reducing threads and batch size");
        }

        // Broadcast restart scheduled to UI with comprehensive stats
        Map<String, Object> restartStats = new LinkedHashMap<>();
        restartStats.put("restartAttempt", restartConfig.attemptNumber());
        restartStats.put("maxRestarts", restartConfig.maxAttempts());
        restartStats.put("backoffMs", restartConfig.backoffMs());
        restartStats.put("heapSize", restartConfig.heapSize());
        restartStats.put("heapIncreased", restartConfig.heapIncreased());
        restartStats.put("offHeapBytes", restartConfig.offHeapBytes());
        restartStats.put("offHeapFormatted", SystemMemoryAnalyzer.formatBytes(restartConfig.offHeapBytes()));
        restartStats.put("offHeapReduced", restartConfig.offHeapReduced());
        restartStats.put("ompThreads", restartConfig.ompNumThreads());
        restartStats.put("blasThreads", restartConfig.openBlasNumThreads());
        restartStats.put("batchSize", restartConfig.batchSize());
        restartStats.put("originalBatchSize", currentBatchSize);
        restartStats.put("batchSizeReduced", batchSizeReduced);
        restartStats.put("failureReason", failureReason.name());

        broadcastProgress(taskId, "RESTARTING",
                (restartConfig.attemptNumber() * 100) / restartConfig.maxAttempts(),
                "Adaptive Recovery",
                adjustmentMsg.toString(),
                restartStats);

        // Notify IngestProgressTracker with structured restart info for UI
        if (ingestProgressTracker != null) {
            String displayName = buildTaskDisplayName(handle.getVectorIndexPath());
            IngestPhase currentPhase = mapPhaseToIngestPhase(handle.getCurrentPhase());
            long nextRestartTime = System.currentTimeMillis() + restartConfig.backoffMs();
            String memoryReason = restartConfig.memoryAnalysis() != null
                    ? restartConfig.memoryAnalysis().reason()
                    : "Memory adjustment for OOM recovery";

            ingestProgressTracker.notifyRestartScheduled(
                    taskId,
                    displayName,
                    currentPhase,
                    restartConfig.attemptNumber(),
                    restartConfig.maxAttempts(),
                    nextRestartTime,
                    restartConfig.heapSize(),
                    restartConfig.heapIncreased(),
                    restartConfig.ompNumThreads(),
                    restartConfig.openBlasNumThreads(),
                    memoryReason
            );
        }

        // Log restart event to IngestEventService (for WebSocket broadcast to frontend)
        if (ingestEventService != null) {
            try {
                String details = objectMapper.writeValueAsString(Map.of(
                        "heapSize", restartConfig.heapSize(),
                        "heapIncreased", restartConfig.heapIncreased(),
                        "ompThreads", restartConfig.ompNumThreads(),
                        "blasThreads", restartConfig.openBlasNumThreads(),
                        "batchSize", restartConfig.batchSize(),
                        "memoryAnalysis", restartConfig.memoryAnalysis() != null
                                ? restartConfig.memoryAnalysis().reason() : "OOM recovery"
                ));
                // Convert IngestProgressUpdate.IngestPhase to IngestEvent.IngestPhase
                IngestEvent.IngestPhase eventPhase = convertToEventPhase(handle.getCurrentPhase());
                ingestEventService.logRestartScheduled(
                        taskId,
                        buildTaskDisplayName(handle.getVectorIndexPath()),
                        eventPhase,
                        restartConfig.attemptNumber(),
                        restartConfig.maxAttempts(),
                        restartConfig.backoffMs(),
                        details
                );
            } catch (Exception e) {
                logger.warn("Failed to log restart scheduled event: {}", e.getMessage());
            }
        }

        logger.info("=== RESTART SCHEDULED for task {} ===", taskId);
        logger.info("Backoff: {}ms, Config: {}", restartConfig.backoffMs(), restartConfig.getSummary());

        // Schedule the restart after backoff
        restartScheduler.schedule(() -> executeRestart(handle, restartConfig),
                restartConfig.backoffMs(), TimeUnit.MILLISECONDS);
    }

    /**
     * Schedule a restart attempt after stall/deadlock detection.
     * Uses shorter backoff than OOM since there's no memory issue to wait for.
     *
     * @param handle       The stalled process handle
     * @param reason       The stall reason (STALLED_NO_HEARTBEAT or STALLED_NO_PROGRESS)
     * @param errorMessage The error message describing the stall
     */
    private void scheduleStallRestart(VectorPopulationHandle handle,
                                       SubprocessRestartManager.FailureReason reason,
                                       String errorMessage) {
        String taskId = handle.getTaskId();
        String fileName = buildTaskDisplayName(handle.getVectorIndexPath());

        logger.info("=== STALL RECOVERY TRIGGERED for task {} ===", taskId);
        logger.info("Reason: {}, Error: {}", reason, errorMessage);

        // Get current settings for restart (no memory adjustment needed for stalls)
        String currentHeapSize = subprocessConfigService != null
                ? subprocessConfigService.getHeapSize()
                : fallbackHeapSize;
        Long parsedHeapBytes = SystemMemoryAnalyzer.parseMemoryToBytes(currentHeapSize);
        long currentHeapBytes = (parsedHeapBytes != null) ? parsedHeapBytes : 4L * 1024 * 1024 * 1024;

        String currentOffHeapStr = subprocessConfigService != null
                ? subprocessConfigService.getOffHeapMaxBytes()
                : null;
        Long parsedOffHeapBytes = SystemMemoryAnalyzer.parseMemoryToBytes(currentOffHeapStr);
        long currentOffHeapBytes = (parsedOffHeapBytes != null) ? parsedOffHeapBytes : currentHeapBytes * 2;

        int currentOmpThreads = getConfigInt("OMP_NUM_THREADS", 4);
        int currentBlasThreads = getConfigInt("OPENBLAS_NUM_THREADS", 4);
        int currentMaxThreads = subprocessConfigService != null
                ? subprocessConfigService.getEmbeddingThreads()
                : 1;
        int currentBatchSize = embeddingProperties != null
                ? embeddingProperties.getBaseOptimalBatchSize()
                : 4;

        // Get restart config (no OOM adjustment, just use current settings)
        RestartConfig restartConfig = restartManager.getRestartConfig(
                taskId, false /* not OOM */, currentHeapBytes, currentOffHeapBytes,
                currentOmpThreads, currentBlasThreads, currentMaxThreads, currentBatchSize);

        logger.info("Stall restart config: {}", restartConfig.getSummary());

        // Log restart scheduled event
        if (ingestEventService != null) {
            try {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("reason", reason.name());
                details.put("stallType", reason == SubprocessRestartManager.FailureReason.STALLED_NO_HEARTBEAT
                        ? "no_heartbeat" : "no_progress");
                details.put("heapSize", restartConfig.heapSize());
                details.put("attemptNumber", restartConfig.attemptNumber());
                details.put("maxAttempts", restartConfig.maxAttempts());
                String detailsJson = objectMapper.writeValueAsString(details);
                // Stalls typically occur during embedding phase
                ingestEventService.logRestartScheduled(taskId, fileName,
                        IngestEvent.IngestPhase.EMBEDDING,
                        restartConfig.attemptNumber(), restartConfig.maxAttempts(),
                        restartConfig.backoffMs(), detailsJson);
            } catch (Exception e) {
                logger.warn("Failed to serialize stall restart details: {}", e.getMessage());
            }
        }

        // Broadcast restart scheduled to UI
        Map<String, Object> restartStats = new LinkedHashMap<>();
        restartStats.put("restartAttempt", restartConfig.attemptNumber());
        restartStats.put("maxRestarts", restartConfig.maxAttempts());
        restartStats.put("reason", reason.name());
        restartStats.put("backoffMs", restartConfig.backoffMs());
        restartStats.put("heapSize", restartConfig.heapSize());

        broadcastProgress(taskId, "RESTARTING",
                (restartConfig.attemptNumber() * 100) / restartConfig.maxAttempts(),
                "Recovering from stall",
                String.format("Stall detected (%s) - restarting (attempt %d/%d)",
                        reason.name(), restartConfig.attemptNumber(), restartConfig.maxAttempts()),
                restartStats);

        logger.info("=== STALL RESTART SCHEDULED for task {} ===", taskId);
        logger.info("Backoff: {}ms, Config: {}", restartConfig.backoffMs(), restartConfig.getSummary());

        // Use shorter backoff for stall recovery (1 second instead of full backoff)
        long stallBackoffMs = Math.min(1000, restartConfig.backoffMs());
        restartScheduler.schedule(() -> executeRestart(handle, restartConfig),
                stallBackoffMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Execute a restart attempt.
     */
    private void executeRestart(VectorPopulationHandle oldHandle, RestartConfig restartConfig) {
        String taskId = oldHandle.getTaskId();
        String fileName = buildTaskDisplayName(oldHandle.getVectorIndexPath());

        logger.info("=== EXECUTING RESTART for task {} ===", taskId);
        logger.info("Config: {}", restartConfig.getSummary());

        // Log restart attempted event
        if (ingestEventService != null) {
            try {
                Map<String, Object> attemptDetails = new LinkedHashMap<>();
                attemptDetails.put("heapSize", restartConfig.heapSize());
                attemptDetails.put("heapSizeBytes", restartConfig.heapSizeBytes());
                attemptDetails.put("offHeapBytes", restartConfig.offHeapBytes());
                attemptDetails.put("offHeapFormatted", SystemMemoryAnalyzer.formatBytes(restartConfig.offHeapBytes()));
                attemptDetails.put("ompThreads", restartConfig.ompNumThreads());
                attemptDetails.put("blasThreads", restartConfig.openBlasNumThreads());
                attemptDetails.put("maxThreads", restartConfig.maxThreads());
                attemptDetails.put("batchSize", restartConfig.batchSize());

                String detailsJson = objectMapper.writeValueAsString(attemptDetails);
                ingestEventService.logRestartAttempted(taskId, fileName,
                        restartConfig.attemptNumber(), restartConfig.maxAttempts(),
                        restartConfig.heapSize(), detailsJson);
            } catch (Exception e) {
                logger.warn("Failed to serialize restart attempted details: {}", e.getMessage());
            }
        }

        // Build user-friendly message for UI
        String memoryInfo;
        if (restartConfig.offHeapReduced()) {
            memoryInfo = String.format("heap=%s, off-heap=%s (reduced)",
                    restartConfig.heapSize(),
                    SystemMemoryAnalyzer.formatBytes(restartConfig.offHeapBytes()));
        } else {
            memoryInfo = String.format("heap=%s, off-heap=%s",
                    restartConfig.heapSize(),
                    SystemMemoryAnalyzer.formatBytes(restartConfig.offHeapBytes()));
        }

        // Broadcast restart attempted to UI with full details
        Map<String, Object> attemptStats = new LinkedHashMap<>();
        attemptStats.put("restartAttempt", restartConfig.attemptNumber());
        attemptStats.put("maxRestarts", restartConfig.maxAttempts());
        attemptStats.put("heapSize", restartConfig.heapSize());
        attemptStats.put("offHeapBytes", restartConfig.offHeapBytes());
        attemptStats.put("offHeapFormatted", SystemMemoryAnalyzer.formatBytes(restartConfig.offHeapBytes()));
        attemptStats.put("ompThreads", restartConfig.ompNumThreads());
        attemptStats.put("blasThreads", restartConfig.openBlasNumThreads());
        attemptStats.put("batchSize", restartConfig.batchSize());

        broadcastProgress(taskId, "RESTARTING",
                (restartConfig.attemptNumber() * 100) / restartConfig.maxAttempts(),
                "Restarting subprocess",
                String.format("Attempt %d/%d: %s", restartConfig.attemptNumber(), restartConfig.maxAttempts(), memoryInfo),
                attemptStats);

        // Notify IngestProgressTracker that restart is executing now
        if (ingestProgressTracker != null) {
            ingestProgressTracker.notifyRestartExecuting(
                    taskId,
                    fileName,
                    restartConfig.attemptNumber(),
                    restartConfig.maxAttempts(),
                    restartConfig.heapSize()
            );
        }

        // Log restart attempted event to IngestEventService (for WebSocket broadcast to frontend)
        if (ingestEventService != null) {
            try {
                String details = objectMapper.writeValueAsString(Map.of(
                        "heapSize", restartConfig.heapSize(),
                        "heapIncreased", restartConfig.heapIncreased(),
                        "ompThreads", restartConfig.ompNumThreads(),
                        "blasThreads", restartConfig.openBlasNumThreads(),
                        "batchSize", restartConfig.batchSize()
                ));
                ingestEventService.logRestartAttempted(
                        taskId,
                        fileName,
                        restartConfig.attemptNumber(),
                        restartConfig.maxAttempts(),
                        restartConfig.heapSize(),
                        details
                );
            } catch (Exception e) {
                logger.warn("Failed to log restart attempted event: {}", e.getMessage());
            }
        }

        try {
            // Launch new subprocess with adjusted settings
            CompletableFuture<VectorPopulationResult> newResultFuture = launchVectorPopulationWithConfig(
                    taskId,
                    oldHandle.getKeywordIndexPath(),
                    oldHandle.getVectorIndexPath(),
                    restartConfig);

            // When new process completes, record result
            newResultFuture.whenComplete((result, ex) -> {
                if (ex != null) {
                    logger.error("Restart attempt {} for task {} failed with exception: {}",
                            restartConfig.attemptNumber(), taskId, ex.getMessage());
                    restartManager.recordRestartAttempt(taskId, false);

                    // Check if we should try again
                    if (restartManager.shouldRestart(taskId, FailureReason.UNKNOWN)) {
                        // Schedule another restart - use UNKNOWN since this is a retry after restart failure
                        VectorPopulationHandle currentHandle = activeProcesses.get(taskId);
                        if (currentHandle != null) {
                            scheduleRestart(currentHandle, -1, false, FailureReason.UNKNOWN, ex.getMessage());
                        }
                    } else {
                        // All attempts exhausted
                        handleRestartExhausted(oldHandle, restartConfig.attemptNumber(), ex.getMessage());
                    }
                } else if (!result.success()) {
                    // Process completed but failed - this will be handled in handleCompletion
                    // which may trigger another restart
                    logger.info("Restart attempt {} for task {} completed but task failed: {}",
                            restartConfig.attemptNumber(), taskId, result.errorMessage());
                } else {
                    // Success!
                    logger.info("Restart attempt {} for task {} succeeded!", restartConfig.attemptNumber(), taskId);
                    restartManager.recordRestartAttempt(taskId, true);
                    restartManager.recordRestartSuccess(taskId);

                    // Log success event
                    if (ingestEventService != null) {
                        long recoveryTime = Duration.between(oldHandle.getStartTime(), Instant.now()).toMillis();
                        ingestEventService.logRestartSucceeded(taskId, fileName,
                                restartConfig.attemptNumber(), recoveryTime);
                    }

                    // Complete the original future
                    oldHandle.getResultFuture().complete(result);
                }
            });

            restartManager.recordRestartAttempt(taskId, true);

        } catch (Exception e) {
            logger.error("Failed to launch restart for task {}: {}", taskId, e.getMessage(), e);
            restartManager.recordRestartAttempt(taskId, false);

            // Check if we should try again - use UNKNOWN since this is a launch failure, not the original error
            if (restartManager.shouldRestart(taskId, FailureReason.UNKNOWN)) {
                scheduleRestart(oldHandle, -1, false, FailureReason.UNKNOWN, e.getMessage());
            } else {
                handleRestartExhausted(oldHandle, restartConfig.attemptNumber(), e.getMessage());
            }
        }
    }

    /**
     * Handle case where all restart attempts have been exhausted.
     */
    private void handleRestartExhausted(VectorPopulationHandle handle, int totalAttempts, String finalError) {
        String taskId = handle.getTaskId();
        String fileName = buildTaskDisplayName(handle.getVectorIndexPath());
        long totalTime = Duration.between(handle.getStartTime(), Instant.now()).toMillis();

        logger.error("All {} restart attempts exhausted for task {}: {}", totalAttempts, taskId, finalError);

        // Log restart failed event
        if (ingestEventService != null) {
            ingestEventService.logRestartFailed(taskId, fileName, totalAttempts, totalTime, finalError);
        }

        // Complete the original future as failure
        String errorMessage = String.format("All %d restart attempts failed: %s", totalAttempts, finalError);
        handle.getResultFuture().complete(VectorPopulationResult.failure(taskId, handle.getCurrentPhase(), errorMessage));

        // Update progress trackers
        if (progressTracker != null) {
            progressTracker.failTask(taskId, mapPhaseToEnum(handle.getCurrentPhase()), errorMessage);
        }
        if (ingestProgressTracker != null) {
            IngestPhase ingestPhase = mapPhaseToIngestPhase(handle.getCurrentPhase());
            ingestProgressTracker.failTask(taskId, fileName, ingestPhase, errorMessage);
        }

        // Broadcast failure
        broadcastProgress(taskId, "FAILED", 0, "All restarts failed", errorMessage,
                Map.of("totalRestartAttempts", totalAttempts));

        // Clean up restart state
        restartManager.clearRestartState(taskId);
    }

    /**
     * Launch vector population with specific restart configuration.
     * This includes memory (heap + off-heap) and thread overrides.
     */
    private CompletableFuture<VectorPopulationResult> launchVectorPopulationWithConfig(
            String taskId, String keywordIndexPath, String vectorIndexPath, RestartConfig config) {

        logger.info("RESTART: Launching vector population with adjusted settings for task {}", taskId);
        logger.info("RESTART: Config summary: {}", config.getSummary());

        // Build options with restart config - these will be extracted in launchVectorPopulation
        Map<String, Object> options = new HashMap<>();

        // Memory settings (heap + off-heap)
        options.put("heapSize", config.heapSize());
        options.put("offHeapBytes", config.offHeapBytes());

        // Thread settings
        options.put("ompNumThreads", config.ompNumThreads());
        options.put("openBlasNumThreads", config.openBlasNumThreads());
        options.put("embeddingThreads", config.maxThreads());

        // Batch size
        options.put("embeddingBatchSize", config.batchSize());

        // Metadata for tracking
        options.put("restartAttempt", config.attemptNumber());
        options.put("heapIncreased", config.heapIncreased());
        options.put("offHeapReduced", config.offHeapReduced());

        logger.info("RESTART: Memory overrides - heap={}, offHeap={}",
                config.heapSize(), SystemMemoryAnalyzer.formatBytes(config.offHeapBytes()));
        logger.info("RESTART: Thread overrides - OMP={}, BLAS={}, maxThreads={}",
                config.ompNumThreads(), config.openBlasNumThreads(), config.maxThreads());
        logger.info("RESTART: Batch size - {}", config.batchSize());

        // Use the existing launch method (which now extracts overrides from options)
        return launchVectorPopulation(taskId, keywordIndexPath, vectorIndexPath, options);
    }

    /**
     * Get integer from environment variable or default.
     */
    private int getConfigInt(String envVar, int defaultValue) {
        String value = System.getenv(envVar);
        if (value != null) {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        return defaultValue;
    }

    /**
     * Cleanup after subprocess completes.
     */
    private void cleanup(VectorPopulationHandle handle) {
        activeProcesses.remove(handle.getTaskId());
        warnedTaskIds.remove(handle.getTaskId());

        // === GPU LIFECYCLE: Release GPU resources for this vector population job ===
        if (modelLifecycleManager != null && modelLifecycleManager.hasJobGpuHold(handle.getTaskId())) {
            logger.info("[vecpop-{}] Releasing GPU resources for completed/failed vector population job", handle.getTaskId());
            try {
                modelLifecycleManager.releaseGpuForVectorPopulation(handle.getTaskId());
            } catch (Exception e) {
                logger.warn("[vecpop-{}] Error releasing GPU resources: {}", handle.getTaskId(), e.getMessage());
            }
        }

        // Safety close for log writer (normally already closed by handleCompletion)
        SubprocessLogWriter lw = handle.logWriter;
        if (lw != null) {
            handle.logWriter = null;
            try { lw.close(); } catch (Exception ignored) {}
        }

        Path argsFile = handle.getArgsFile();
        if (argsFile != null && Files.exists(argsFile)) {
            try {
                Files.delete(argsFile);
                logger.debug("Deleted args file: {}", argsFile);
            } catch (IOException e) {
                logger.warn("Failed to delete args file: {}", argsFile);
            }
        }
    }

    /**
     * Write the terminal record to the subprocess log and close it.
     * Swallows all errors so aggregation failures never break a run.
     */
    private void closeSubprocessLog(VectorPopulationHandle handle, String state,
            Integer exitCode, String errorMessage, boolean oomDetected, boolean gpuOomDetected) {
        SubprocessLogWriter lw = handle.logWriter;
        if (lw == null) {
            return;
        }
        handle.logWriter = null;
        try {
            lw.writeEnd(new SubprocessLogWriter.SubprocessRunResult(
                    state, exitCode, errorMessage, oomDetected, gpuOomDetected));
        } catch (Exception e) {
            logger.debug("[vector-pop-{}] log writeEnd failed: {}", handle.getTaskId(), e.getMessage());
        }
        try {
            lw.close();
        } catch (Exception e) {
            logger.debug("[vector-pop-{}] log close failed: {}", handle.getTaskId(), e.getMessage());
        }
    }

    /**
     * Capture ND4J configuration as JSON.
     */
    private String captureNd4jConfig() {
        // Check if device routing provides a service-specific config for vector population
        if (deviceRoutingConfigService != null && deviceRoutingConfigService.isEnabled()) {
            try {
                Nd4jEnvironmentConfig routedConfig = deviceRoutingConfigService
                        .resolveNd4jConfigForService(DeviceRoutingConfig.SERVICE_VECTOR_POPULATION);
                logger.info("Using device-routed ND4J config for vectorPopulation: maxThreads={}, cudaDevice={}",
                        routedConfig.maxThreads(), routedConfig.cudaCurrentDevice());
                return objectMapper.writeValueAsString(routedConfig);
            } catch (Exception e) {
                logger.warn("Failed to resolve device-routed config for vectorPopulation, falling back: {}", e.getMessage());
            }
        }

        Nd4jEnvironmentConfig config = null;

        if (nd4jEnvironmentConfigService != null) {
            try {
                // Use persisted configuration, not runtime state
                // This ensures subprocess uses the intended config from the UI/config file
                config = nd4jEnvironmentConfigService.getConfiguration();
                logger.info("Capturing persisted ND4J config for subprocess: maxThreads={}, ompNumThreads={}",
                        config.maxThreads(), config.ompNumThreads());
            } catch (Exception e) {
                logger.warn("Failed to capture ND4J config from service: {}", e.getMessage());
            }
        }

        if (config == null) {
            config = Nd4jEnvironmentConfig.builder()
                    .maxThreads(Runtime.getRuntime().availableProcessors())
                    .maxMasterThreads(Math.max(1, Runtime.getRuntime().availableProcessors() / 2))
                    .debug(false)
                    .verbose(false)
                    .profiling(false)
                    .enableBlas(true)
                    .helpersAllowed(true)
                    .lifecycleTracking(false)
                    .build();
        }

        try {
            return objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            logger.warn("Failed to serialize ND4J config to JSON: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Thread override settings for restart recovery.
     */
    public record ThreadOverrides(
            Integer ompNumThreads,
            Integer openBlasNumThreads,
            Integer mklNumThreads
    ) {
        public static ThreadOverrides none() {
            return new ThreadOverrides(null, null, null);
        }

        public static ThreadOverrides from(int ompThreads, int blasThreads) {
            return new ThreadOverrides(ompThreads, blasThreads, ompThreads);
        }

        public boolean hasOverrides() {
            return ompNumThreads != null || openBlasNumThreads != null || mklNumThreads != null;
        }
    }

    /**
     * Propagate ND4J-related environment variables to subprocess.
     * Sets OMP_NUM_THREADS to match ND4J's maxThreads setting.
     *
     * @param env The environment map to populate
     * @param threadOverrides Optional thread overrides from restart config. If non-null, these take precedence.
     */
    private void propagateNd4jEnvironment(Map<String, String> env, ThreadOverrides threadOverrides) {
        List<String> nd4jEnvVars = List.of(
                "ND4J_BACKEND", "ND4J_DATA_BUFFER_OPS", "ND4J_RESOURCES_DIR",
                "CUDA_VISIBLE_DEVICES", "CUDA_DEVICE_ORDER",
                "JAVACPP_PLATFORM", "KOMPILE_EMBEDDING_MODEL", "KOMPILE_MODELS_DIR");

        int propagated = 0;
        for (String varName : nd4jEnvVars) {
            String value = System.getenv(varName);
            if (value != null && !value.isEmpty()) {
                env.put(varName, value);
                propagated++;
            }
        }

        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            String key = entry.getKey();
            if ((key.startsWith("ND4J_") || key.startsWith("KOMPILE_")) && !env.containsKey(key)) {
                env.put(key, entry.getValue());
                propagated++;
            }
        }

        // Apply thread overrides from restart config (takes precedence)
        if (threadOverrides != null && threadOverrides.hasOverrides()) {
            if (threadOverrides.ompNumThreads() != null) {
                env.put("OMP_NUM_THREADS", String.valueOf(threadOverrides.ompNumThreads()));
                logger.info("RESTART: Setting OMP_NUM_THREADS={} (override from restart config)", threadOverrides.ompNumThreads());
                propagated++;
            }
            if (threadOverrides.openBlasNumThreads() != null) {
                env.put("OPENBLAS_NUM_THREADS", String.valueOf(threadOverrides.openBlasNumThreads()));
                logger.info("RESTART: Setting OPENBLAS_NUM_THREADS={} (override from restart config)", threadOverrides.openBlasNumThreads());
                propagated++;
            }
            if (threadOverrides.mklNumThreads() != null) {
                env.put("MKL_NUM_THREADS", String.valueOf(threadOverrides.mklNumThreads()));
                logger.info("RESTART: Setting MKL_NUM_THREADS={} (override from restart config)", threadOverrides.mklNumThreads());
                propagated++;
            }
        } else {
            // No overrides - use system env if set, otherwise ND4J defaults
            String ompEnv = System.getenv("OMP_NUM_THREADS");
            String blasEnv = System.getenv("OPENBLAS_NUM_THREADS");
            String mklEnv = System.getenv("MKL_NUM_THREADS");

            if (ompEnv != null && !ompEnv.isEmpty()) {
                env.put("OMP_NUM_THREADS", ompEnv);
                propagated++;
            }
            if (blasEnv != null && !blasEnv.isEmpty()) {
                env.put("OPENBLAS_NUM_THREADS", blasEnv);
                propagated++;
            }
            if (mklEnv != null && !mklEnv.isEmpty()) {
                env.put("MKL_NUM_THREADS", mklEnv);
                propagated++;
            }

            // Set from ND4J if not already set
            if (!env.containsKey("OMP_NUM_THREADS")) {
                try {
                    int maxThreads = (int) org.nd4j.linalg.factory.Nd4j.getEnvironment().maxThreads();
                    if (maxThreads > 0) {
                        env.put("OMP_NUM_THREADS", String.valueOf(maxThreads));
                        env.put("MKL_NUM_THREADS", String.valueOf(maxThreads));
                        env.put("OPENBLAS_NUM_THREADS", String.valueOf(maxThreads));
                        logger.info("Set OMP_NUM_THREADS={} from ND4J maxThreads", maxThreads);
                        propagated += 3;
                    }
                } catch (Exception e) {
                    logger.debug("Could not get ND4J maxThreads: {}", e.getMessage());
                }
            }
        }

        logger.debug("Propagated {} ND4J environment variables to subprocess", propagated);
    }

    /**
     * Scheduled task to detect subprocesses that are alive but not emitting
     * progress.
     * This catches cases like long/hung model initialization where heartbeats
     * continue but progress appears stuck.
     *
     * Two-phase detection:
     * 1. Warn phase: After progress-stall-threshold-seconds (default 60s)
     * 2. Restart phase: After stall-detection-threshold-seconds (default 300s)
     */
    @Scheduled(fixedRateString = "${kompile.vectorpopulation.subprocess.progress-stall-check-interval-ms:30000}")
    public void checkProgressStalls() {
        int warnSeconds = getEffectiveProgressStallThresholdSeconds();
        Duration warnThreshold = Duration.ofSeconds(warnSeconds);

        // Restart threshold - get from restart manager or use default
        int restartSeconds = (restartManager != null)
                ? restartManager.getStallDetectionThresholdSeconds()
                : 300;
        Duration restartThreshold = Duration.ofSeconds(restartSeconds);

        for (VectorPopulationHandle handle : activeProcesses.values()) {
            if (!handle.isAlive() || handle.isCancelled()) {
                continue;
            }

            Duration sinceProgress = handle.timeSinceLastProgress();

            // Phase 2: Restart if stalled for too long
            if (sinceProgress.compareTo(restartThreshold) > 0) {
                String errorMessage = String.format(
                        "Process stalled for %ds (phase=%s, progress=%d%%) - potential deadlock. Attempting restart.",
                        sinceProgress.getSeconds(),
                        handle.getCurrentPhase(),
                        handle.getProgressPercent());

                logger.error("Vector population subprocess {} appears deadlocked: {}",
                        handle.getTaskId(), errorMessage);

                // Check if we should attempt restart
                if (restartManager != null && !handle.isCancelled()) {
                    SubprocessRestartManager.FailureReason reason = SubprocessRestartManager.FailureReason.STALLED_NO_PROGRESS;
                    if (restartManager.shouldRestart(handle.getTaskId(), reason)) {
                        logger.info("Attempting restart for stalled subprocess {} (no progress for {}s)",
                                handle.getTaskId(), sinceProgress.getSeconds());
                        // Cancel the current process first
                        handle.cancel();
                        // Schedule restart
                        scheduleStallRestart(handle, reason, errorMessage);
                        warnedTaskIds.remove(handle.getTaskId()); // Clear warning state
                        continue;
                    }
                }

                // No restart available - cancel and fail
                handle.cancel();

                if (progressTracker != null) {
                    progressTracker.failTask(handle.getTaskId(), mapPhaseToEnum(handle.getCurrentPhase()),
                            errorMessage);
                }
                if (ingestProgressTracker != null) {
                    String displayName = buildTaskDisplayName(handle.getVectorIndexPath());
                    IngestPhase ingestPhase = mapPhaseToIngestPhase(handle.getCurrentPhase());
                    ingestProgressTracker.failTask(handle.getTaskId(), displayName, ingestPhase, errorMessage);
                }
                broadcastProgress(handle.getTaskId(), "FAILED", 0, "Failed", errorMessage, null);
                warnedTaskIds.remove(handle.getTaskId());
                continue;
            }

            // Phase 1: Warn if stalled but not yet at restart threshold
            if (sinceProgress.compareTo(warnThreshold) <= 0) {
                continue;
            }

            // Warn once per stall window; cleared when progress resumes.
            if (!warnedTaskIds.add(handle.getTaskId())) {
                continue;
            }

            String msg = String.format(
                    "No progress updates for %ds (phase=%s, progress=%d%%). Will restart after %ds. Last message: %s. Heap: %s",
                    sinceProgress.getSeconds(),
                    handle.getCurrentPhase(),
                    handle.getProgressPercent(),
                    restartSeconds,
                    handle.getLastMessage() != null ? handle.getLastMessage() : "",
                    handle.getHeapSummary());

            logger.warn("Vector population subprocess {} appears stalled: {}", handle.getTaskId(), msg);
            if (progressTracker != null) {
                progressTracker.sendLog(handle.getTaskId(), "WATCHDOG", "WARN", msg);
            }
            if (ingestProgressTracker != null) {
                ingestProgressTracker.sendLog(handle.getTaskId(), "WATCHDOG", "WARN", msg);
            }
        }
    }

    private int getEffectiveProgressStallThresholdSeconds() {
        if (subprocessConfigService != null) {
            return subprocessConfigService.getProgressStallWarningSeconds();
        }
        return fallbackProgressStallThresholdSeconds;
    }

    /**
     * Scheduled task to check for stale subprocesses (no heartbeat).
     * Attempts restart if configured, otherwise cancels.
     */
    @Scheduled(fixedRateString = "${kompile.vectorpopulation.subprocess.stale-check-interval-ms:60000}")
    public void checkStaleProcesses() {
        int staleSeconds = getEffectiveStaleThresholdSeconds();
        Duration staleThreshold = Duration.ofSeconds(staleSeconds);

        for (VectorPopulationHandle handle : activeProcesses.values()) {
            if (handle.isAlive() && handle.isStale(staleThreshold)) {
                logger.warn(
                        "Vector population subprocess {} appears stuck (no heartbeat for {} seconds)",
                        handle.getTaskId(), staleSeconds);

                String errorMessage = "Process became unresponsive (no heartbeat for " + staleSeconds + " seconds)";

                // Check if we should attempt restart instead of just failing
                if (restartManager != null && !handle.isCancelled()) {
                    SubprocessRestartManager.FailureReason reason = SubprocessRestartManager.FailureReason.STALLED_NO_HEARTBEAT;
                    if (restartManager.shouldRestart(handle.getTaskId(), reason)) {
                        logger.info("Attempting restart for stalled subprocess {} (no heartbeat)", handle.getTaskId());
                        // Cancel the current process first
                        handle.cancel();
                        // Schedule restart
                        scheduleStallRestart(handle, reason, errorMessage);
                        continue; // Don't mark as failed - restart in progress
                    }
                }

                // No restart - just cancel and fail
                handle.cancel();

                // Update progress tracker (ensures UI gets the failure notification)
                if (progressTracker != null) {
                    progressTracker.failTask(handle.getTaskId(), mapPhaseToEnum(handle.getCurrentPhase()),
                            errorMessage);
                }
                if (ingestProgressTracker != null) {
                    String displayName = buildTaskDisplayName(handle.getVectorIndexPath());
                    IngestPhase ingestPhase = mapPhaseToIngestPhase(handle.getCurrentPhase());
                    ingestProgressTracker.failTask(handle.getTaskId(), displayName, ingestPhase, errorMessage);
                }

                // Also broadcast directly via WebSocket for backward compatibility
                broadcastProgress(handle.getTaskId(), "FAILED", 0, "Failed", errorMessage, null);
            }
        }
    }

    /**
     * Cancel all active subprocesses on shutdown.
     */
    @PreDestroy
    public void shutdownAll() {
        logger.info("Shutting down all active vector population subprocesses...");

        for (VectorPopulationHandle handle : activeProcesses.values()) {
            if (handle.isAlive()) {
                logger.info("Cancelling subprocess: {}", handle.getTaskId());
                handle.cancel();
            }

            // Release GPU hold for this job if held
            if (modelLifecycleManager != null && modelLifecycleManager.hasJobGpuHold(handle.getTaskId())) {
                logger.info("[vecpop-{}] Releasing GPU resources during shutdown", handle.getTaskId());
                try {
                    modelLifecycleManager.releaseGpuForVectorPopulation(handle.getTaskId());
                } catch (Exception e) {
                    logger.warn("[vecpop-{}] Error releasing GPU during shutdown: {}",
                            handle.getTaskId(), e.getMessage());
                }
            }
        }

        for (VectorPopulationHandle handle : activeProcesses.values()) {
            handle.waitFor(Duration.ofSeconds(5));
        }

        activeProcesses.clear();
        warnedTaskIds.clear();
        logger.info("All vector population subprocesses terminated");
    }

    private int getIntOption(Map<String, Object> options, String key, int defaultValue) {
        if (options == null || !options.containsKey(key)) {
            return defaultValue;
        }
        Object value = options.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private boolean getBoolOption(Map<String, Object> options, String key, boolean defaultValue) {
        if (options == null || !options.containsKey(key)) {
            return defaultValue;
        }
        Object value = options.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return "true".equalsIgnoreCase((String) value);
        }
        return defaultValue;
    }

    private String getStringOption(Map<String, Object> options, String key, String defaultValue) {
        if (options == null || !options.containsKey(key)) {
            return defaultValue;
        }
        Object value = options.get(key);
        if (value instanceof String) {
            String str = (String) value;
            return str.isBlank() ? defaultValue : str;
        }
        if (value != null) {
            return value.toString();
        }
        return defaultValue;
    }

    private Long getLongOption(Map<String, Object> options, String key, Long defaultValue) {
        if (options == null || !options.containsKey(key)) {
            return defaultValue;
        }
        Object value = options.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private Integer getIntOptionOrNull(Map<String, Object> options, String key) {
        if (options == null || !options.containsKey(key)) {
            return null;
        }
        Object value = options.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private Map<String, String> convertOptionsToStringMap(Map<String, Object> options) {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : options.entrySet()) {
            if (entry.getValue() != null) {
                result.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
        return result;
    }

    /**
     * Build VectorPopulationStats from subprocess progress message.
     */
    private VectorPopulationStats buildStatsFromProgress(SubprocessMessage.Progress progress) {
        if (progress == null) {
            return null;
        }

        SubprocessMessage.ProgressStats stats = progress.stats();
        if (stats == null) {
            return null;
        }

        // Convert worker statuses from subprocess format to DTO format
        List<VectorPopulationProgressTracker.WorkerStatusDto> workerStatusDtos = null;
        if (stats.workerStatuses() != null && !stats.workerStatuses().isEmpty()) {
            workerStatusDtos = stats.workerStatuses().stream()
                    .map(ws -> new VectorPopulationProgressTracker.WorkerStatusDto(
                            ws.workerId(),
                            ws.workerType(),
                            ws.status(),
                            ws.itemsProcessed(),
                            ws.currentBatchSize(),
                            ws.throughput(),
                            ws.currentItem()))
                    .toList();
        }

        // Build queue status from subprocess stats
        VectorPopulationProgressTracker.QueueStatusDto queueStatus = new VectorPopulationProgressTracker.QueueStatusDto(
                stats.chunkQueueSize(),
                1000, // default capacity
                stats.embeddingQueueSize(),
                1000 // default capacity
        );

        // Build embedding batch metrics if we have batch info
        VectorPopulationProgressTracker.EmbeddingBatchMetricsDto embeddingBatch = null;
        if (stats.batchSize() > 0) {
            // Use batch numbers from subprocess if available (fixed values), otherwise calculate as fallback
            int totalBatches = stats.totalBatches() != null && stats.totalBatches() > 0
                    ? stats.totalBatches()
                    : (stats.documentsLoaded() > 0
                            ? (int) Math.ceil((double) stats.documentsLoaded() / stats.batchSize())
                            : 0);
            int currentBatch = stats.currentBatchNumber() != null && stats.currentBatchNumber() > 0
                    ? stats.currentBatchNumber()
                    : (stats.chunksEmbedded() > 0
                            ? (int) Math.ceil((double) stats.chunksEmbedded() / stats.batchSize())
                            : 1);

            String modelName = stats.runtimeInfo() != null ? stats.runtimeInfo().embeddingModelId() : "Unknown Model";
            String deviceType = "CPU";
            if (stats.runtimeInfo() != null) {
                if (Boolean.TRUE.equals(stats.runtimeInfo().cudaAvailable())) {
                    deviceType = "GPU (CUDA)";
                } else if (stats.runtimeInfo().nd4jBackend() != null
                        && stats.runtimeInfo().nd4jBackend().toLowerCase().contains("cuda")) {
                    deviceType = "GPU (CUDA)";
                }
            }

            embeddingBatch = new VectorPopulationProgressTracker.EmbeddingBatchMetricsDto(
                    currentBatch,
                    totalBatches,
                    stats.batchSize(),
                    stats.activeStage(),
                    0, // heartbeatSeconds
                    0, // forwardPassTimeMs
                    0, // totalBatchTimeMs
                    false, // isStuck
                    null, // sourceDocuments
                    0, // sourceDocumentCount
                    stats.actualInputShape(), // inputTensorShape - use actual from encoder
                    stats.actualOutputShape(), // outputTensorShape - use actual from encoder
                    stats.embeddingDimension() != null ? stats.embeddingDimension() : 0, // embeddingDimension
                    0, // inferenceTimeMs
                    stats.chunksPerSecond(), // batchThroughput
                    modelName, // modelName
                    deviceType, // deviceType
                    stats.passageTokenCounts() // passageTokenCounts
            );
        }

        // Convert batch history from subprocess format to DTO format
        List<VectorPopulationProgressTracker.BatchHistoryEntryDto> batchHistoryDtos = null;
        if (stats.batchHistory() != null && !stats.batchHistory().isEmpty()) {
            batchHistoryDtos = stats.batchHistory().stream()
                    .map(h -> new VectorPopulationProgressTracker.BatchHistoryEntryDto(
                            h.batchNumber(),
                            h.inputTexts(),
                            h.maxSequenceLength(),
                            h.embeddingDimension(),
                            h.actualInputShape(),
                            h.actualOutputShape(),
                            h.totalBatchTimeMs(),
                            h.currentStep(),
                            h.tokensPerSecond(),
                            h.passageTokenCounts()))
                    .toList();
        }

        return new VectorPopulationStats(
                stats.documentsLoaded(),
                stats.chunksCreated(),
                stats.chunksEmbedded(),
                stats.documentsIndexed(),
                stats.documentsLoaded(), // totalDocuments
                stats.tokensProcessed(),
                stats.totalTokensInIndex(),
                0, // elapsedTimeMs - computed elsewhere
                stats.chunksPerSecond(),
                stats.memoryUsagePercent(),
                workerStatusDtos,
                queueStatus,
                embeddingBatch,
                batchHistoryDtos,
                convertRuntimeInfo(stats.runtimeInfo()));
    }

    private IngestStats buildIngestStatsFromProgress(SubprocessMessage.Progress progress, String taskId) {
        IngestStats.Builder builder = IngestStats.builder()
                .subprocessRuntimeInfo(IngestProgressUpdate.SubprocessRuntimeInfo.forProcessMode("SUBPROCESS"));

        // Add restart tracking info if available (subprocess restart within session)
        if (restartManager != null && taskId != null) {
            SubprocessRestartManager.RestartStatus restartStatus = restartManager.getRestartStatus(taskId);
            if (restartStatus.attemptsMade() > 0) {
                builder.restartAttempt(restartStatus.attemptsMade())
                        .maxRestartAttempts(restartStatus.maxAttempts());
            }
        }

        if (progress == null || progress.stats() == null) {
            return builder.build();
        }

        SubprocessMessage.ProgressStats stats = progress.stats();

        // Check for checkpoint resume (previous session) - show as "resume" indicator
        boolean isResumedFromCheckpoint = Boolean.TRUE.equals(stats.isResumedRun())
                && stats.resumedFromIndexedCount() != null
                && stats.resumedFromIndexedCount() > 0;

        if (isResumedFromCheckpoint) {
            // If no subprocess restart, but we're resuming from checkpoint, show as restart attempt 1
            // to indicate to the UI that this is a resumed job
            if (builder.build().restartAttempt() == null || builder.build().restartAttempt() == 0) {
                builder.restartAttempt(1)
                        .maxRestartAttempts(1);  // Indicate single "resume" rather than retry cycle
            }
        }

        // Calculate total chunks embedded (include resumed count if applicable)
        int totalChunksEmbedded = stats.chunksEmbedded()
                + (isResumedFromCheckpoint ? stats.resumedFromIndexedCount() : 0);

        builder.documentsLoaded(stats.documentsLoaded())
                .chunksCreated(stats.chunksCreated())
                .chunksEmbedded(totalChunksEmbedded)
                .chunksIndexed(stats.documentsIndexed())
                .documentsIndexed(stats.documentsIndexed())
                .totalProcessingTimeMs(stats.totalProcessingTimeMs())
                .batchSize(stats.batchSize())
                .workerThreads(stats.workerThreads())
                .parallelProcessing(stats.parallelProcessing())
                .chunksPerSecond(stats.chunksPerSecond())
                .docsPerSecond(stats.docsPerSecond())
                .memoryUsagePercent(stats.memoryUsagePercent())
                .memoryStatus(stats.memoryStatus())
                .activeStage(stats.activeStage())
                .pipelineStatus(stats.pipelineStatus());

        int queueCapacity = 1000;
        IngestProgressUpdate.QueueStatusDto queueStatus = new IngestProgressUpdate.QueueStatusDto(
                stats.chunkQueueSize(),
                queueCapacity,
                stats.embeddingQueueSize(),
                queueCapacity,
                queueCapacity > 0 ? (stats.chunkQueueSize() * 100.0 / queueCapacity) : 0,
                queueCapacity > 0 ? (stats.embeddingQueueSize() * 100.0 / queueCapacity) : 0);
        builder.queueStatus(queueStatus);

        if (stats.workerStatuses() != null && !stats.workerStatuses().isEmpty()) {
            List<IngestProgressUpdate.WorkerStatusDto> workerDtos = stats.workerStatuses().stream()
                    .map(this::convertWorkerStatusSnapshot)
                    .toList();
            builder.workerStatuses(workerDtos);
        }

        IngestProgressUpdate.SubprocessRuntimeInfo runtimeInfo = convertRuntimeInfo(stats.runtimeInfo());
        if (runtimeInfo != null) {
            builder.subprocessRuntimeInfo(runtimeInfo);
        }

        // ========== Build EmbeddingBatchMetrics from subprocess tensor shape info ==========
        // This captures the actual tensor shapes from SameDiff inference in the subprocess
        if (stats.actualInputShape() != null || stats.actualOutputShape() != null ||
            stats.currentStep() != null || stats.inputTexts() != null) {

            IngestProgressUpdate.EmbeddingBatchMetrics.Builder batchBuilder =
                    IngestProgressUpdate.EmbeddingBatchMetrics.builder();

            // Batch identification
            if (stats.currentBatchNumber() != null) {
                batchBuilder.batchNumber(stats.currentBatchNumber());
            }
            if (stats.totalBatches() != null) {
                batchBuilder.totalBatches(stats.totalBatches());
            }

            // Input metrics
            if (stats.inputTexts() != null) {
                batchBuilder.inputTexts(stats.inputTexts());
            }
            if (stats.maxSequenceLength() != null) {
                batchBuilder.maxSequenceLength(stats.maxSequenceLength());
            }

            // Output metrics
            if (stats.embeddingDimension() != null) {
                batchBuilder.embeddingDimension(stats.embeddingDimension());
            }

            // Tensor shapes - the key data for the UI
            if (stats.actualInputShape() != null) {
                batchBuilder.actualInputShape(stats.actualInputShape());
            }
            if (stats.actualOutputShape() != null) {
                batchBuilder.actualOutputShape(stats.actualOutputShape());
            }

            // Current step tracking
            if (stats.currentStep() != null) {
                batchBuilder.currentStep(stats.currentStep());
            }

            // Timing breakdown from encoder
            if (stats.tokenizationTimeMs() != null) {
                batchBuilder.tokenizationTimeMs(stats.tokenizationTimeMs());
            }
            if (stats.paddingTimeMs() != null) {
                batchBuilder.paddingTimeMs(stats.paddingTimeMs());
            }
            if (stats.tensorCreationTimeMs() != null) {
                batchBuilder.tensorCreationTimeMs(stats.tensorCreationTimeMs());
            }
            if (stats.forwardPassTimeMs() != null) {
                batchBuilder.forwardPassTimeMs(stats.forwardPassTimeMs());
            }
            if (stats.extractionTimeMs() != null) {
                batchBuilder.extractionTimeMs(stats.extractionTimeMs());
            }

            // Per-passage token counts
            if (stats.passageTokenCounts() != null) {
                batchBuilder.passageTokenCounts(stats.passageTokenCounts());
            }

            builder.currentEmbeddingBatch(batchBuilder.build());
        }

        return builder.build();
    }

    private IngestStats buildIngestStatsFromCompleted(SubprocessMessage.Completed completed) {
        if (completed == null) {
            return IngestStats.builder()
                    .subprocessRuntimeInfo(IngestProgressUpdate.SubprocessRuntimeInfo.forProcessMode("SUBPROCESS"))
                    .build();
        }

        double rate = completed.totalDurationMs() > 0
                ? (completed.documentsIndexed() * 1000.0 / completed.totalDurationMs())
                : 0.0;

        return IngestStats.builder()
                .documentsLoaded(completed.documentsLoaded())
                .chunksCreated(completed.chunksCreated())
                .chunksEmbedded(completed.chunksEmbedded())
                .chunksIndexed(completed.documentsIndexed())
                .documentsIndexed(completed.documentsIndexed())
                .totalProcessingTimeMs(completed.totalDurationMs())
                .chunksPerSecond(rate)
                .subprocessRuntimeInfo(IngestProgressUpdate.SubprocessRuntimeInfo.forProcessMode("SUBPROCESS"))
                .build();
    }

    private IngestPhase mapPhaseToIngestPhase(String phase) {
        VectorPopulationPhase vectorPhase = mapPhaseToEnum(phase);
        return switch (vectorPhase) {
            case LOADING -> IngestPhase.LOADING;
            case EMBEDDING -> IngestPhase.EMBEDDING;
            case INDEXING -> IngestPhase.INDEXING;
            case COMPLETED -> IngestPhase.COMPLETED;
            case FAILED, CANCELLED -> IngestPhase.FAILED;
        };
    }

    private IngestProgressUpdate.SubprocessRuntimeInfo convertRuntimeInfo(SubprocessMessage.RuntimeInfo ri) {
        if (ri == null) {
            return null;
        }
        return new IngestProgressUpdate.SubprocessRuntimeInfo(
                ri.pid(),
                ri.uptimeMs(),
                "SUBPROCESS",
                ri.javaVersion(),
                ri.javaVendor(),
                ri.javaHome(),
                ri.vmName(),
                ri.vmVersion(),
                ri.heapMaxBytes(),
                ri.heapUsedBytes(),
                ri.heapFreeBytes(),
                ri.heapUsagePercent(),
                ri.nonHeapUsedBytes(),
                ri.gcCount(),
                ri.gcTimeMs(),
                ri.availableProcessors(),
                ri.workingDirectory(),
                ri.tempDirectory(),
                ri.commandLine(),
                ri.jvmArguments(),
                ri.inputFiles(),
                ri.nd4jBackendEnv(),
                ri.cudaVisibleDevices(),
                ri.ompNumThreads(),
                ri.mklNumThreads(),
                ri.nd4jEnvironmentInvoked(),
                ri.nd4jEnvironmentUsed(),
                ri.nd4jBackend(),
                ri.blasVendor(),
                ri.cudaAvailable(),
                ri.cudaVersion(),
                ri.embeddingModelId(),
                ri.embeddingModelPath(),
                ri.embeddingDimension());
    }

    private IngestProgressUpdate.WorkerStatusDto convertWorkerStatusSnapshot(
            SubprocessMessage.WorkerStatusSnapshot ws) {
        return new IngestProgressUpdate.WorkerStatusDto(
                ws.workerId(),
                ws.workerType() != null ? ws.workerType().toLowerCase(java.util.Locale.ROOT) : null,
                ws.status() != null ? ws.status().toLowerCase(java.util.Locale.ROOT) : null,
                ws.itemsProcessed(),
                ws.currentBatchSize(),
                ws.throughput(),
                ws.currentItem());
    }

    /**
     * Check if an error message indicates an OOM-related failure.
     * Used to detect OOM from subprocess protocol messages so we can schedule restart
     * instead of immediately failing the task.
     */
    private boolean isOomRelatedMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String upper = message.toUpperCase();
        return upper.contains("OUTOFMEMORY") ||
               upper.contains("OUT OF MEMORY") ||
               upper.contains("JAVA HEAP SPACE") ||
               upper.contains("GC OVERHEAD LIMIT") ||
               upper.contains("HEAP EXHAUSTED") ||
               upper.contains("OOM") ||
               upper.contains("SUBPROCESS WILL RESTART");
    }

    /**
     * Check if an error message indicates a batch size too large failure.
     * This happens when matrix multiplication creates an array exceeding Integer.MAX_VALUE.
     */
    private boolean isBatchSizeTooLargeMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        // Check for the specific ND4J error
        return message.contains("Length of buffer can not be >= Integer.MAX_VALUE") ||
               message.contains("buffer can not be >= Integer") ||
               message.contains("array size exceeds") ||
               message.contains("Requested array size exceeds");
    }

    /**
     * Determine the failure reason from error messages.
     */
    private SubprocessRestartManager.FailureReason determineFailureReason(String errorMessage, String errorType) {
        if (isBatchSizeTooLargeMessage(errorMessage)) {
            return SubprocessRestartManager.FailureReason.BATCH_SIZE_TOO_LARGE;
        }
        if (isOomRelatedMessage(errorMessage) || isOomRelatedMessage(errorType)) {
            return SubprocessRestartManager.FailureReason.OUT_OF_MEMORY;
        }
        return SubprocessRestartManager.FailureReason.UNKNOWN;
    }

    /**
     * Convert a phase string to IngestEvent.IngestPhase.
     * Used when logging events to IngestEventService.
     */
    private IngestEvent.IngestPhase convertToEventPhase(String phase) {
        if (phase == null || phase.isBlank()) {
            return IngestEvent.IngestPhase.LOADING;
        }
        try {
            return IngestEvent.IngestPhase.valueOf(phase.toUpperCase());
        } catch (IllegalArgumentException e) {
            return IngestEvent.IngestPhase.LOADING;
        }
    }

    private String buildTaskDisplayName(String vectorIndexPath) {
        if (vectorIndexPath == null || vectorIndexPath.isBlank()) {
            return "Vector Population";
        }
        return "Vector Population: " + vectorIndexPath;
    }

    /**
     * Map string phase from subprocess to VectorPopulationPhase enum.
     */
    private VectorPopulationPhase mapPhaseToEnum(String phase) {
        if (phase == null) {
            return VectorPopulationPhase.LOADING;
        }

        return switch (phase.toUpperCase()) {
            case "LOADING", "STARTING", "INITIALIZING" -> VectorPopulationPhase.LOADING;
            case "EMBEDDING", "EMBED" -> VectorPopulationPhase.EMBEDDING;
            case "INDEXING", "INDEX" -> VectorPopulationPhase.INDEXING;
            case "COMPLETED", "COMPLETE", "DONE" -> VectorPopulationPhase.COMPLETED;
            case "FAILED", "ERROR" -> VectorPopulationPhase.FAILED;
            case "CANCELLED", "CANCELED" -> VectorPopulationPhase.CANCELLED;
            default -> VectorPopulationPhase.LOADING;
        };
    }

    /**
     * Handle for tracking a vector population subprocess.
     */
    public static class VectorPopulationHandle {
        private final String taskId;
        private final String keywordIndexPath;
        private final String vectorIndexPath;
        private final Process process;
        private final CompletableFuture<VectorPopulationResult> resultFuture;
        private final Path argsFile;
        private final Instant startTime;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicBoolean oomDetected = new AtomicBoolean(false);
        private volatile SubprocessRestartManager.FailureReason failureReason = null;

        private volatile Instant lastHeartbeat;
        private volatile Instant lastProgress;
        private volatile double lastHeapUsagePercent = 0.0;
        private volatile long lastHeapUsedBytes = 0L;
        private volatile long lastHeapMaxBytes = 0L;
        private volatile double lastOffHeapUsagePercent = 0.0;
        private volatile long lastOffHeapUsedBytes = 0L;
        private volatile long lastOffHeapMaxBytes = 0L;
        private volatile double lastGpuUsagePercent = 0.0;
        private volatile long lastGpuUsedBytes = 0L;
        private volatile long lastGpuMaxBytes = 0L;
        private volatile String currentPhase = "STARTING";
        private volatile int progressPercent = 0;
        private volatile String lastMessage = "";
        private volatile boolean startupComplete = false;
        volatile SubprocessLogWriter logWriter;

        public VectorPopulationHandle(String taskId, String keywordIndexPath, String vectorIndexPath,
                Process process, CompletableFuture<VectorPopulationResult> resultFuture,
                Path argsFile) {
            this.taskId = taskId;
            this.keywordIndexPath = keywordIndexPath;
            this.vectorIndexPath = vectorIndexPath;
            this.process = process;
            this.resultFuture = resultFuture;
            this.argsFile = argsFile;
            this.startTime = Instant.now();
            this.lastHeartbeat = Instant.now();
            this.lastProgress = Instant.now();
        }

        public String getTaskId() {
            return taskId;
        }

        public String getKeywordIndexPath() {
            return keywordIndexPath;
        }

        public String getVectorIndexPath() {
            return vectorIndexPath;
        }

        public Process getProcess() {
            return process;
        }

        public CompletableFuture<VectorPopulationResult> getResultFuture() {
            return resultFuture;
        }

        public Path getArgsFile() {
            return argsFile;
        }

        public Instant getStartTime() {
            return startTime;
        }

        public boolean isAlive() {
            return process.isAlive();
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        public boolean isOomDetected() {
            return oomDetected.get();
        }

        public void setOomDetected(boolean detected) {
            oomDetected.set(detected);
        }

        public boolean isStartupComplete() {
            return startupComplete;
        }

        public void setStartupComplete(boolean complete) {
            this.startupComplete = complete;
        }

        public SubprocessRestartManager.FailureReason getFailureReason() {
            return failureReason;
        }

        public void setFailureReason(SubprocessRestartManager.FailureReason reason) {
            this.failureReason = reason;
        }

        public String getCurrentPhase() {
            return currentPhase;
        }

        public void setCurrentPhase(String phase) {
            this.currentPhase = phase;
        }

        public int getProgressPercent() {
            return progressPercent;
        }

        public String getLastMessage() {
            return lastMessage;
        }

        public void updateProgress(String phase, int percent, String message) {
            this.currentPhase = phase;
            this.progressPercent = percent;
            this.lastMessage = message;
            this.lastProgress = Instant.now();
            updateHeartbeat();
        }

        public void updateHeartbeat() {
            this.lastHeartbeat = Instant.now();
        }

        public void updateHeartbeat(SubprocessMessage.Heartbeat heartbeat) {
            updateHeartbeat();
            if (heartbeat != null) {
                this.lastHeapUsagePercent = heartbeat.memoryUsagePercent();
                this.lastHeapUsedBytes = heartbeat.heapUsedBytes();
                this.lastHeapMaxBytes = heartbeat.heapMaxBytes();
                this.lastOffHeapUsagePercent = heartbeat.offHeapUsagePercent();
                this.lastOffHeapUsedBytes = heartbeat.offHeapUsedBytes();
                this.lastOffHeapMaxBytes = heartbeat.offHeapMaxBytes();
                this.lastGpuUsagePercent = heartbeat.gpuUsagePercent();
                this.lastGpuUsedBytes = heartbeat.gpuUsedBytes();
                this.lastGpuMaxBytes = heartbeat.gpuMaxBytes();
            }
        }

        public Duration timeSinceLastProgress() {
            Instant lp = lastProgress;
            if (lp == null) {
                return Duration.ZERO;
            }
            return Duration.between(lp, Instant.now());
        }

        public long getElapsedMs() {
            return Duration.between(startTime, Instant.now()).toMillis();
        }

        public String getHeapSummary() {
            long max = lastHeapMaxBytes;
            long used = lastHeapUsedBytes;
            double pct = lastHeapUsagePercent;
            if (max <= 0) {
                return "unknown";
            }
            long usedMb = used / (1024 * 1024);
            long maxMb = max / (1024 * 1024);
            return String.format("%.1f%% (%d/%d MB)", pct, usedMb, maxMb);
        }

        public boolean isStale(Duration threshold) {
            if (lastHeartbeat == null)
                return false;
            return Duration.between(lastHeartbeat, Instant.now()).compareTo(threshold) > 0;
        }

        public void cancel() {
            if (cancelled.getAndSet(true))
                return;
            process.destroy();
            try {
                if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }

        public void waitFor(Duration timeout) {
            try {
                process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        public Status getStatus() {
            return new Status(taskId, keywordIndexPath, vectorIndexPath,
                    process.pid(), isAlive(), isCancelled(), isOomDetected(),
                    currentPhase, progressPercent, lastMessage, startTime, lastHeartbeat,
                    lastHeapUsagePercent, lastHeapUsedBytes, lastHeapMaxBytes,
                    lastOffHeapUsagePercent, lastOffHeapUsedBytes, lastOffHeapMaxBytes,
                    lastGpuUsagePercent, lastGpuUsedBytes, lastGpuMaxBytes);
        }

        public record Status(String taskId, String keywordIndexPath, String vectorIndexPath,
                long pid, boolean alive, boolean cancelled, boolean oomDetected,
                String currentPhase, int progressPercent, String lastMessage,
                Instant startTime, Instant lastHeartbeat,
                double heapUsagePercent, long heapUsedBytes, long heapMaxBytes,
                double offHeapUsagePercent, long offHeapUsedBytes, long offHeapMaxBytes,
                double gpuUsagePercent, long gpuUsedBytes, long gpuMaxBytes) {
        }
    }

    /**
     * Result of vector population subprocess execution.
     */
    public record VectorPopulationResult(
            String taskId,
            boolean success,
            int documentsLoaded,
            int chunksEmbedded,
            int documentsIndexed,
            long totalDurationMs,
            String vectorIndexPath,
            String errorPhase,
            String errorMessage) {
        public static VectorPopulationResult success(String taskId, int documentsLoaded,
                int chunksEmbedded, int documentsIndexed,
                long totalDurationMs, String vectorIndexPath) {
            return new VectorPopulationResult(taskId, true, documentsLoaded, chunksEmbedded,
                    documentsIndexed, totalDurationMs, vectorIndexPath, null, null);
        }

        public static VectorPopulationResult failure(String taskId, String errorPhase, String errorMessage) {
            return new VectorPopulationResult(taskId, false, 0, 0, 0, 0, null, errorPhase, errorMessage);
        }
    }
}
