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
import ai.kompile.app.config.DeviceRoutingConfig;
import ai.kompile.app.ingest.service.IngestEventService;
import ai.kompile.app.services.IngestProgressTracker;
import ai.kompile.app.services.DeviceRoutingConfigService;
import ai.kompile.app.services.ModelLifecycleManager;
import ai.kompile.app.services.Nd4jEnvironmentConfigService;
import ai.kompile.app.services.OpTimingService;
import ai.kompile.app.services.ServerPortService;
import ai.kompile.app.services.VectorPopulationProgressTracker;
import ai.kompile.app.services.subprocess.SubprocessCommandBuilder.MemoryOverrides;
import ai.kompile.app.services.subprocess.SubprocessCommandBuilder.ThreadOverrides;
import ai.kompile.app.services.subprocess.SubprocessRestartManager.FailureReason;
import ai.kompile.app.subprocess.SubprocessMessage;
import ai.kompile.app.subprocess.VectorPopulationSubprocessArgs;
import ai.kompile.app.web.dto.IngestProgressUpdate;
import ai.kompile.app.web.dto.IngestProgressUpdate.IngestPhase;
import ai.kompile.app.web.dto.IngestProgressUpdate.IngestStats;
import ai.kompile.cli.common.logs.SubprocessLogWriter;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.embedding.anserini.config.AnseriniEmbeddingConfiguration.AnseriniEmbeddingProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for launching and managing vector population subprocesses.
 *
 * This service spawns isolated JVM processes to run Lucene to Vector Store
 * population, preventing crashes and OOM errors from affecting the main application.
 *
 * Key features:
 * - Spawns subprocess using same classpath as main app (or native executable)
 * - Delegates command building to {@link SubprocessCommandBuilder}
 * - Delegates I/O monitoring and message dispatch to {@link SubprocessOutputHandler}
 * - Delegates exit handling and watchdog scheduling to {@link SubprocessLifecycleManager}
 * - Delegates DTO conversion to {@link VectorPopulationStatsConverter}
 * - Forwards progress to WebSocket for UI updates
 * - Handles subprocess crashes gracefully
 * - Supports cancellation and timeout
 * - Monitors subprocess health via heartbeats
 */
@Service
public class VectorPopulationSubprocessLauncher {

    private static final Logger logger = LoggerFactory.getLogger(VectorPopulationSubprocessLauncher.class);

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
    private final AnseriniEmbeddingProperties embeddingProperties;
    private final VectorPopulationProgressTracker progressTracker;
    private final IngestProgressTracker ingestProgressTracker;
    private final SubprocessRestartManager restartManager;
    private final IngestEventService ingestEventService;
    private final OpTimingService opTimingService;
    private final ObjectMapper objectMapper;

    // Collaborators (extracted classes)
    private final SubprocessCommandBuilder commandBuilder;
    private final SubprocessOutputHandler outputHandler;
    private final SubprocessLifecycleManager lifecycleManager;
    private final VectorPopulationStatsConverter statsConverter;

    @Autowired(required = false)
    private ModelLifecycleManager modelLifecycleManager;

    @Autowired(required = false)
    private ai.kompile.app.subprocess.SubprocessRegistry subprocessRegistry;

    @Autowired(required = false)
    private ai.kompile.app.services.scheduler.ResourceAwareJobScheduler resourceScheduler;

    @Autowired(required = false)
    private ai.kompile.app.services.SubprocessHeartbeatBroadcaster heartbeatBroadcaster;

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
            @Autowired(required = false) OpTimingService opTimingService,
            @Autowired SubprocessCommandBuilder commandBuilder,
            @Autowired SubprocessOutputHandler outputHandler,
            @Autowired SubprocessLifecycleManager lifecycleManager,
            @Autowired VectorPopulationStatsConverter statsConverter) {
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
        this.objectMapper = JsonUtils.standardMapper();
        this.commandBuilder = commandBuilder;
        this.outputHandler = outputHandler;
        this.lifecycleManager = lifecycleManager;
        this.statsConverter = statsConverter;

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

        // Wire shared state and callbacks into the lifecycle manager
        lifecycleManager.setContext(
                activeProcesses,
                warnedTaskIds,
                (taskId, options) -> {
                    VectorPopulationHandle existing = activeProcesses.get(taskId);
                    String kwPath = existing != null ? existing.getKeywordIndexPath() : null;
                    String vPath = existing != null ? existing.getVectorIndexPath() : null;
                    return launchVectorPopulation(taskId, kwPath, vPath, options);
                },
                (taskId, phase, percent, message) -> broadcastProgress(taskId, phase, percent, "Step", message, null),
                (taskId, phase, percent, message, stats) -> broadcastProgress(taskId, phase, percent, "Step", message, stats));

        // Wire callbacks into the output handler
        outputHandler.setCallbacks(
                (handle, progress) -> forwardProgress(handle, progress),
                (handle, transition) -> {
                    broadcastProgress(handle.getTaskId(), transition.toPhase(), 0,
                            "Starting " + transition.toPhase().toLowerCase(),
                            "Phase transition: " + transition.fromPhase() + " -> " + transition.toPhase(),
                            null);
                    if (heartbeatBroadcaster != null) {
                        heartbeatBroadcaster.broadcastPhaseTransition(handle.getTaskId(), "vectorPopulation",
                                transition.fromPhase(), transition.toPhase(), transition.phaseDurationMs());
                    }
                    if (resourceScheduler != null) {
                        var profile = ai.kompile.app.services.scheduler.JobResourceProfiles.VECTOR_POPULATION;
                        boolean requiresGpu = profile.phaseRequiresGpu(transition.toPhase());
                        long gpuMem = profile.gpuMemoryForPhase(transition.toPhase());
                        resourceScheduler.reportPhaseTransition(handle.getTaskId(), transition.toPhase(), requiresGpu, gpuMem);
                    }
                },
                (handle, completed) -> forwardCompletion(handle, completed),
                (handle, failed) -> {
                    // Restartable failures: broadcast RECOVERY_SCHEDULED but do not forward as full failure
                    if (handle.isOomDetected()) {
                        FailureReason failureReason = handle.getFailureReason() != null
                                ? handle.getFailureReason() : FailureReason.OUT_OF_MEMORY;
                        String recoveryType = failureReason == FailureReason.BATCH_SIZE_TOO_LARGE
                                ? "BATCH SIZE TOO LARGE" : "OOM";
                        String recoveryAction = failureReason == FailureReason.BATCH_SIZE_TOO_LARGE
                                ? "reducing batch size by 75%" : "adjusting memory settings";
                        broadcastProgress(handle.getTaskId(), "RECOVERY_SCHEDULED",
                                handle.getProgressPercent(),
                                "Adaptive Recovery",
                                recoveryType + " detected during " + failed.phase() + " - " + recoveryAction,
                                Map.of("phase", failed.phase(),
                                       "failureReason", failureReason.name(),
                                       "isRecovery", true));
                    } else {
                        forwardFailure(handle, failed);
                        closeSubprocessLog(handle, "FAILED", null, failed.errorMessage(), false, false);
                    }
                },
                (handle, heartbeat) -> {
                    if (heartbeatBroadcaster != null) {
                        heartbeatBroadcaster.broadcastHeartbeat(handle.getTaskId(), "vectorPopulation", heartbeat);
                    }
                },
                (handle, exitCode) -> {
                    lifecycleManager.handleCompletion(handle, exitCode);
                    cleanup(handle);
                },
                warnedTaskIds);
    }

    /**
     * Launch a subprocess to populate vector store from Lucene keyword index.
     *
     * @param taskId           Unique task identifier
     * @param keywordIndexPath Path to the source Lucene keyword index
     * @param vectorIndexPath  Path to the destination vector store index
     * @param options          Additional options (embeddingBatchSize, parallelIndexing, indexingWorkers)
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

            String callbackBaseUrl = subprocessConfigService != null
                    ? subprocessConfigService.getCallbackBaseUrl()
                    : (serverPortService != null ? serverPortService.getBaseUrl() : "http://localhost:8080");

            String modelId = getStringOption(options, "modelId",
                    embeddingProperties != null ? embeddingProperties.getModelIdentifier() : "bge-base-en-v1.5");

            int propsOptimal = embeddingProperties != null ? embeddingProperties.getEffectiveOptimalBatchSize(modelId) : 32;
            int propsMax = embeddingProperties != null ? embeddingProperties.getEffectiveMaxBatchSize(modelId) : 64;
            boolean hasOptionOverride = options != null && options.containsKey("embeddingBatchSize");
            int embeddingBatchSize = getIntOption(options, "embeddingBatchSize", propsOptimal);
            int maxBatchSize = getIntOption(options, "maxBatchSize", propsMax);

            logger.info("Batch size resolution: propsOptimal={}, propsMax={}, hasOptionOverride={}, final embeddingBatchSize={}, maxBatchSize={}",
                    propsOptimal, propsMax, hasOptionOverride, embeddingBatchSize, maxBatchSize);

            int queueCapacity = getIntOption(options, "queueCapacity",
                    subprocessConfigService != null ? subprocessConfigService.getQueueCapacity() : 1000);
            boolean parallelIndexing = getBoolOption(options, "parallelIndexing",
                    subprocessConfigService != null ? subprocessConfigService.isParallelIndexing() : true);
            int indexingWorkers = getIntOption(options, "indexingWorkers",
                    subprocessConfigService != null ? subprocessConfigService.getIndexingWorkers() : 4);
            int indexingBatchAccumulationSize = getIntOption(options, "indexingBatchAccumulationSize",
                    subprocessConfigService != null ? subprocessConfigService.getIndexingBatchAccumulationSize() : 8);
            int embeddingThreads = getIntOption(options, "embeddingThreads",
                    subprocessConfigService != null ? subprocessConfigService.getEmbeddingThreads() : 1);

            logger.info("Using benchmark config for model '{}': optimalBatch={}, maxBatch={}",
                    modelId, embeddingBatchSize, maxBatchSize);

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

            String checkpointBasePath = null;
            if (vectorIndexPath != null) {
                Path vectorPath = Path.of(vectorIndexPath);
                Path checkpointPath = vectorPath.getParent() != null
                        ? vectorPath.getParent().resolve("checkpoints")
                        : Path.of("checkpoints");
                checkpointBasePath = checkpointPath.toString();
                logger.info("Checkpoint path for resume support: {}", checkpointBasePath);
            }

            int memoryThresholdPercent = VectorPopulationSubprocessArgs.DEFAULT_MEMORY_THRESHOLD_PERCENT;
            int memoryCriticalPercent = VectorPopulationSubprocessArgs.DEFAULT_MEMORY_CRITICAL_PERCENT;
            int memoryKillThresholdPercent = VectorPopulationSubprocessArgs.DEFAULT_MEMORY_KILL_THRESHOLD_PERCENT;

            int gpuMemoryThresholdPercent = subprocessConfigService != null
                    ? subprocessConfigService.getGpuMemoryThresholdPercent()
                    : VectorPopulationSubprocessArgs.DEFAULT_GPU_MEMORY_THRESHOLD_PERCENT;
            int gpuMemoryCriticalPercent = subprocessConfigService != null
                    ? subprocessConfigService.getGpuMemoryCriticalPercent()
                    : VectorPopulationSubprocessArgs.DEFAULT_GPU_MEMORY_CRITICAL_PERCENT;
            int gpuMemoryKillThresholdPercent = subprocessConfigService != null
                    ? subprocessConfigService.getGpuMemoryKillThresholdPercent()
                    : VectorPopulationSubprocessArgs.DEFAULT_GPU_MEMORY_KILL_THRESHOLD_PERCENT;

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

            // Build command via SubprocessCommandBuilder
            List<String> command = commandBuilder.buildCommand(argsFile, memoryOverrides);
            logger.info("Subprocess command: {}", String.join(" ", command));

            // Start process
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(false);

            // Propagate ND4J environment variables with thread overrides
            commandBuilder.propagateNd4jEnvironment(processBuilder.environment(), threadOverrides);

            // === GPU LIFECYCLE: Acquire GPU resources for this vector population job ===
            if (modelLifecycleManager != null && !modelLifecycleManager.hasJobGpuHold(taskId)) {
                try {
                    modelLifecycleManager.acquireGpuForVectorPopulation(taskId);
                    logger.info("[vecpop-{}] GPU resources acquired for vector population job", taskId);
                } catch (IllegalStateException e) {
                    logger.warn("[vecpop-{}] Could not acquire GPU for vector population (may use CPU fallback): {}",
                            taskId, e.getMessage());
                }
            } else if (modelLifecycleManager != null) {
                logger.info("[vecpop-{}] GPU already held by scheduler, skipping launcher acquire", taskId);
            }

            Process process = processBuilder.start();
            logger.info("Started vector population subprocess with PID: {}", process.pid());

            // Register with centralized subprocess registry for orphan protection
            if (subprocessRegistry != null) {
                subprocessRegistry.register("vector-pop-" + taskId, process, "vector-population");
            }

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
                        ? memoryOverrides.heapSize() : commandBuilder.getEffectiveHeapSize();
                logWriter.writeStart(new SubprocessLogWriter.SubprocessRunContext(
                        taskId, command, System.getProperty("user.dir"), process.pid(), effectiveHeap));
                handle.logWriter = logWriter;
            } catch (Exception e) {
                logger.debug("[vector-pop-{}] Failed to open subprocess log writer: {}", taskId, e.getMessage());
            }

            // Start monitoring via SubprocessOutputHandler
            outputHandler.startMonitoring(handle);

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

            // Send initial progress
            broadcastProgress(taskId, "INITIALIZING", 0, "Starting subprocess",
                    "Initializing ND4J and loading embedding model...", null);
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

        if (progressTracker != null) {
            progressTracker.cancelTask(taskId, "Vector population cancelled by user");
        }
        if (ingestProgressTracker != null) {
            String displayName = buildTaskDisplayName(handle.getVectorIndexPath());
            IngestPhase ingestPhase = statsConverter.mapPhaseToIngestPhase(handle.getCurrentPhase());
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
        lifecycleManager.getRestartScheduler().shutdownNow();
        logger.info("All vector population subprocesses terminated");
    }

    // ---- WebSocket broadcast methods ----

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

        VectorPopulationProgressTracker.VectorPopulationStats derivedStats = statsConverter.buildStatsFromProgress(progress);
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
        closeSubprocessLog(handle, "COMPLETED", 0, null, false, false);
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

    // ---- Cleanup ----

    private void cleanup(VectorPopulationHandle handle) {
        activeProcesses.remove(handle.getTaskId());
        warnedTaskIds.remove(handle.getTaskId());

        if (subprocessRegistry != null) {
            subprocessRegistry.deregister("vector-pop-" + handle.getTaskId());
        }

        if (modelLifecycleManager != null && modelLifecycleManager.hasJobGpuHold(handle.getTaskId())) {
            logger.info("[vecpop-{}] Releasing GPU resources for completed/failed vector population job", handle.getTaskId());
            try {
                modelLifecycleManager.releaseGpuForVectorPopulation(handle.getTaskId());
            } catch (Exception e) {
                logger.warn("[vecpop-{}] Error releasing GPU resources: {}", handle.getTaskId(), e.getMessage());
            }
        }

        SubprocessLogWriter lw = handle.logWriter;
        if (lw != null) {
            handle.logWriter = null;
            try { lw.close(); } catch (Exception e) {
                logger.warn("Failed to close subprocess log writer: {}", e.getMessage());
            }
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

    // ---- ND4J config capture ----

    private String captureNd4jConfig() {
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

    // ---- Option parsing helpers ----

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

    private String buildTaskDisplayName(String vectorIndexPath) {
        if (vectorIndexPath == null || vectorIndexPath.isBlank()) {
            return "Vector Population";
        }
        return "Vector Population: " + vectorIndexPath;
    }
}
