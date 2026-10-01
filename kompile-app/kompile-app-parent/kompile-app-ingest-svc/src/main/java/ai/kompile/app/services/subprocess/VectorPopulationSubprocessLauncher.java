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
import ai.kompile.app.config.GpuDevice;
import ai.kompile.app.ingest.service.IngestEventService;
import ai.kompile.app.services.IngestProgressTracker;
import ai.kompile.app.services.DeviceRoutingConfigService;
import ai.kompile.app.services.ModelLifecycleManager;
import ai.kompile.app.services.Nd4jEnvironmentConfigService;
import ai.kompile.app.services.OpTimingService;
import ai.kompile.app.services.ServerPortService;
import ai.kompile.app.services.VectorPopulationProgressTracker;
import ai.kompile.app.services.scheduler.JobResourceProfiles;
import ai.kompile.app.services.subprocess.SubprocessCommandBuilder.MemoryOverrides;
import ai.kompile.app.services.subprocess.SubprocessCommandBuilder.ThreadOverrides;
import ai.kompile.app.services.subprocess.SubprocessRestartManager.FailureReason;
import ai.kompile.app.subprocess.BackendConfigurable;
import ai.kompile.app.subprocess.ManagedSubprocessLauncher.BackendPreference;
import ai.kompile.app.subprocess.SubprocessBackendFlags;
import ai.kompile.app.subprocess.SubprocessMessage;
import ai.kompile.app.subprocess.SubprocessPlacement;
import ai.kompile.app.subprocess.SubprocessPlacementSupport;
import ai.kompile.app.subprocess.SubprocessProtocolChannel;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
public class VectorPopulationSubprocessLauncher implements BackendConfigurable {

    private static final Logger logger = LoggerFactory.getLogger(VectorPopulationSubprocessLauncher.class);

    /**
     * Placement assigned through {@link BackendConfigurable}. Only the legacy four-argument
     * {@code launchVectorPopulation} reads it, once per task; the scheduler passes each task's placement instead.
     */
    private final SubprocessPlacementSupport placement = new SubprocessPlacementSupport();

    /** {@link BackendConfigurable} — the placement for the next launch that doesn't carry its own. */
    @Override
    public void applyPlacement(SubprocessPlacement p) {
        this.placement.applyPlacement(p);
    }

    private static final String VECTOR_POPULATION_TOPIC = "/topic/vector-population/progress";

    private static final String CANCELLED_BY_USER = "Vector population cancelled by user";
    private static final String STOPPED_FOR_SHUTDOWN = "Vector population stopped: the application is shutting down";

    /** How long a caller's result waits, after the verdict, for the attempt that gave it to exit */
    private static final long VERDICT_EXIT_WAIT_SECONDS = 30;

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
    ModelLifecycleManager modelLifecycleManager;

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

    /**
     * One vector population task across its restarts, which reuse its taskId: the index paths and
     * placement every attempt runs on, the future its caller holds, and the attempt now running.
     */
    private record TaskLaunch(String keywordIndexPath, String vectorIndexPath, SubprocessPlacement placement,
            CompletableFuture<VectorPopulationResult> resultFuture,
            AtomicReference<VectorPopulationHandle> currentAttempt, AtomicBoolean cancelled) {}

    // Launch state per task, keyed by taskId (the scheduler's jobId on the scheduler path)
    private final Map<String, TaskLaunch> taskLaunches = new ConcurrentHashMap<>();

    /** TaskIds whose GPU row this launcher acquired itself; released once, when the task ends */
    private final Set<String> launcherGpuHolds = ConcurrentHashMap.newKeySet();

    /** Set once shutdown begins; no attempt starts after it */
    private volatile boolean shuttingDown;

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
                this::relaunchVectorPopulation,
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
                        var profile = JobResourceProfiles.VECTOR_POPULATION;
                        boolean requiresGpu = profile.phaseRequiresGpu(transition.toPhase());
                        long gpuMem = profile.gpuMemoryForPhase(transition.toPhase());
                        resourceScheduler.reportPhaseTransition(handle.getTaskId(), transition.toPhase(), requiresGpu, gpuMem);
                    }
                },
                (handle, completed) -> forwardCompletion(handle, completed),
                (handle, failed) -> {
                    forwardFailure(handle, failed);
                    closeSubprocessLog(handle, "FAILED", null, failed.errorMessage(), false, false);
                },
                (handle, failed) -> {
                    // Restartable failure: its restart is scheduled once the attempt exits
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
                },
                (handle, heartbeat) -> {
                    if (heartbeatBroadcaster != null) {
                        heartbeatBroadcaster.broadcastHeartbeat(handle.getTaskId(), "vectorPopulation", heartbeat);
                    }
                },
                (handle, exitCode) -> {
                    // An exit whose handling throws (a restart the shut-down scheduler rejects) still frees
                    // the attempt and deletes its args file
                    try {
                        lifecycleManager.handleCompletion(handle, exitCode);
                    } finally {
                        cleanup(handle);
                    }
                },
                warnedTaskIds);
    }

    /**
     * Launch a subprocess to populate vector store from Lucene keyword index, on the placement assigned
     * via {@link #applyPlacement}.
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
        return launchVectorPopulation(taskId, keywordIndexPath, vectorIndexPath, options, placement.placement());
    }

    /**
     * Launch a vector population subprocess on an explicit placement. The taskId names the task for its
     * whole life: every restart reuses its index paths and this placement, and cancelling it reaches any
     * attempt.
     *
     * @param placement the child's backend/device/memory cap — the scheduler's placement for the task it
     *                  holds a GPU row for; null lets the launcher reserve the task's own row
     * @return Future that completes when the task finishes (after any restarts)
     */
    public CompletableFuture<VectorPopulationResult> launchVectorPopulation(
            String taskId,
            String keywordIndexPath,
            String vectorIndexPath,
            Map<String, Object> options,
            SubprocessPlacement placement) {
        TaskLaunch existing = taskLaunches.get(taskId);
        if (existing != null) {
            logger.warn("Vector population task {} is already running; not launching it again", taskId);
            return afterAttemptExits(existing);
        }

        // Settle the task's GPU row before any command is built, so the child is pinned to its device
        TaskLaunch task = new TaskLaunch(keywordIndexPath, vectorIndexPath, resolveTaskPlacement(taskId, placement),
                new CompletableFuture<>(), new AtomicReference<>(), new AtomicBoolean());
        taskLaunches.put(taskId, task);
        // A task that ends with no attempt running (never started, cancelled between restarts) is
        // finished here; otherwise cleanup finishes it when its last attempt exits
        task.resultFuture().whenComplete((result, error) -> {
            if (!activeProcesses.containsKey(taskId)) {
                finishTask(taskId);
            }
        });
        launchAttempt(taskId, task, options, task.resultFuture());
        return afterAttemptExits(task);
    }

    /**
     * A task's result as its caller sees it: complete once the attempt that gave the verdict has exited, or
     * {@link #VERDICT_EXIT_WAIT_SECONDS} after the verdict. After COMPLETED the child still flushes its index
     * on its device, and a caller such as the scheduler frees the task's GPU row as soon as this completes.
     */
    private CompletableFuture<VectorPopulationResult> afterAttemptExits(TaskLaunch task) {
        return task.resultFuture().thenCompose(result -> {
            VectorPopulationHandle attempt = task.currentAttempt().get();
            if (attempt == null || !attempt.isAlive()) {
                return CompletableFuture.completedFuture(result);
            }
            return attempt.getProcess().onExit()
                    .completeOnTimeout(null, VERDICT_EXIT_WAIT_SECONDS, TimeUnit.SECONDS)
                    .handle((exited, error) -> {
                        if (exited == null) {
                            logger.warn("[vecpop-{}] Subprocess still running {}s after its verdict; not waiting for it",
                                    attempt.getTaskId(), VERDICT_EXIT_WAIT_SECONDS);
                        }
                        return result;
                    });
        });
    }

    /**
     * The placement every attempt of a new task runs on. A CPU placement, or a GPU placement for a task
     * whose row is already held (the scheduler acquired it), is used as given. Otherwise the launcher
     * acquires the task's own row and places the child on that device; if it can't, the task runs on
     * CPU — a child is never pinned to a GPU without a row.
     */
    private SubprocessPlacement resolveTaskPlacement(String taskId, SubprocessPlacement requested) {
        if (modelLifecycleManager == null
                || (requested != null && requested.backend() == BackendPreference.CPU)) {
            return requested;
        }
        long capBytes = JobResourceProfiles.VECTOR_POPULATION.peakGpuMemoryBytes();
        ModelLifecycleManager.JobGpuHold held = modelLifecycleManager.getActiveJobHolds().get(taskId);
        if (held != null) {
            return requested != null && requested.isGpu() ? requested : placementOn(held.device(), capBytes);
        }
        if (requested != null) {
            logger.warn("[vecpop-{}] GPU placement {} has no GPU row; acquiring one for the task", taskId, requested);
        }
        try {
            GpuDevice device = modelLifecycleManager.acquireGpuForJob(taskId,
                    JobResourceProfiles.VECTOR_POPULATION.serviceType(), "Vector population: " + taskId,
                    ModelLifecycleManager.HoldLifetime.BOUNDED, capBytes, null);
            launcherGpuHolds.add(taskId);
            logger.info("[vecpop-{}] GPU row acquired for vector population on {}", taskId, device.name());
            return placementOn(device, capBytes);
        } catch (IllegalStateException e) {
            logger.warn("[vecpop-{}] Could not acquire GPU for vector population, running on CPU: {}",
                    taskId, e.getMessage());
            return SubprocessPlacement.cpu();
        }
    }

    /** The child's placement on a reserved device — derived the same way the scheduler derives it. */
    private static SubprocessPlacement placementOn(GpuDevice device, long capBytes) {
        return SubprocessPlacement.gpu(device.cudaRuntimeIndex(),
                ModelLifecycleManager.clampToDevice(capBytes, device));
    }

    /**
     * Restart callback for {@link SubprocessLifecycleManager}: relaunch a task on the index paths and
     * placement of its first attempt, unless it was cancelled or ended while the restart was pending.
     */
    private CompletableFuture<VectorPopulationResult> relaunchVectorPopulation(
            String taskId, Map<String, Object> options) {
        TaskLaunch task = taskLaunches.get(taskId);
        if (task == null || task.cancelled().get() || task.resultFuture().isDone()) {
            logger.info("[vecpop-{}] Task was cancelled or has ended; not restarting it", taskId);
            if (task != null && task.cancelled().get()) {
                // The restart's progress updates may have landed after the cancel's — restate it
                reportCancelled(taskId, null, task.vectorIndexPath(),
                        shuttingDown ? STOPPED_FOR_SHUTDOWN : CANCELLED_BY_USER);
            }
            return CompletableFuture.completedFuture(
                    VectorPopulationResult.failure(taskId, "RESTARTING", "Cancelled before restart"));
        }
        return launchAttempt(taskId, task, options, new CompletableFuture<>());
    }

    /** Start one attempt of a task; {@code resultFuture} completes with that attempt's result. */
    private CompletableFuture<VectorPopulationResult> launchAttempt(String taskId, TaskLaunch task,
            Map<String, Object> options, CompletableFuture<VectorPopulationResult> resultFuture) {
        String keywordIndexPath = task.keywordIndexPath();
        String vectorIndexPath = task.vectorIndexPath();

        logger.info("Launching vector population subprocess for task: {} keywordIndex: {} vectorIndex: {}",
                taskId, keywordIndexPath, vectorIndexPath);

        Path argsFile = null;
        Process process = null;
        VectorPopulationHandle handle = null;
        boolean monitored = false;
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

            argsFile = Files.createTempFile("vector-pop-args-" + taskId, ".json");
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

            // Build command via SubprocessCommandBuilder (a shared bean — keep per-spawn placement in
            // this launcher, not the bean). Inject the task's device-agnostic backend/device flags right
            // after the executable (index 1): before -cp/main class for java, before the --subprocess=
            // dispatch token for the native executable. No CUDA_VISIBLE_DEVICES.
            List<String> command = new ArrayList<>(commandBuilder.buildCommand(argsFile, memoryOverrides));
            List<String> deviceFlags = SubprocessBackendFlags.jvmFlags(task.placement(), BackendPreference.INHERIT);
            if (!deviceFlags.isEmpty()) {
                command.addAll(1, deviceFlags);
            }
            logger.info("Subprocess command: {}", String.join(" ", command));

            // Start process
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(false);

            // Propagate ND4J environment variables with thread overrides
            commandBuilder.propagateNd4jEnvironment(processBuilder.environment(), threadOverrides);
            // Device-agnostic per-device memory bound (SD_MAX_DEVICE_BYTES) — shared base infra.
            SubprocessBackendFlags.applyEnv(processBuilder.environment(), task.placement());
            // Protocol messages get a pipe of their own, which native output written to fd 1 can't reach
            boolean wrapped = SubprocessProtocolChannel.apply(processBuilder);

            // Start under the task's lock: a cancel either reaches this attempt or keeps it from starting
            synchronized (task) {
                if (task.cancelled().get() || shuttingDown) {
                    logger.info("[vecpop-{}] Task was cancelled or the application is shutting down; "
                            + "not starting this attempt", taskId);
                    resultFuture.complete(VectorPopulationResult.failure(taskId, "STARTING", "Cancelled before start"));
                    Files.deleteIfExists(argsFile);
                    return resultFuture;
                }

                process = processBuilder.start();
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
                handle = new VectorPopulationHandle(
                        taskId, keywordIndexPath, vectorIndexPath,
                        process, resultFuture, argsFile);
                task.currentAttempt().set(handle);
                activeProcesses.put(taskId, handle);
            }

            // Open subprocess log writer (Phase 2 log aggregation)
            try {
                String workingDir = processBuilder.directory() != null
                        ? processBuilder.directory().getAbsolutePath()
                        : System.getProperty("user.dir");
                SubprocessLogWriter logWriter = new SubprocessLogWriter("vector-population", taskId, workingDir);
                String effectiveHeap = memoryOverrides.hasOverrides() && memoryOverrides.heapSize() != null
                        ? memoryOverrides.heapSize() : commandBuilder.getEffectiveHeapSize();
                logWriter.writeStart(new SubprocessLogWriter.SubprocessRunContext(
                        taskId, command, workingDir, process.pid(), effectiveHeap));
                handle.logWriter = logWriter;
            } catch (Exception e) {
                logger.debug("[vector-pop-{}] Failed to open subprocess log writer: {}", taskId, e.getMessage());
            }

            // Start monitoring via SubprocessOutputHandler
            outputHandler.startMonitoring(handle, SubprocessProtocolChannel.stderrProtocol(
                    wrapped, SubprocessMessage.MESSAGE_PREFIX, "vector-pop-" + taskId));
            monitored = true;

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
            if (monitored) {
                // The monitor owns the running attempt and completes its result
                return resultFuture;
            }
            // Nothing watches this attempt, so it ends here. The args file carries the staging API key.
            if (process != null) {
                process.destroyForcibly();
            }
            if (handle != null) {
                closeSubprocessLog(handle, "FAILED", null, "Launch failed: " + e.getMessage(), false, false);
                cleanup(handle);
            } else {
                if (process != null && subprocessRegistry != null) {
                    subprocessRegistry.deregister("vector-pop-" + taskId);
                }
                deleteArgsFile(argsFile);
            }
            // Completing after cleanup: a first attempt then ends its task, a restart's failure goes
            // back to the lifecycle manager
            resultFuture.completeExceptionally(e);
        }

        return resultFuture;
    }

    /**
     * Cancel a vector population task by its taskId: the attempt now running is stopped, and a restart
     * still pending (or a first attempt not yet started) never starts.
     */
    public boolean cancelVectorPopulation(String taskId) {
        TaskLaunch task = taskLaunches.get(taskId);
        VectorPopulationHandle handle;
        if (task != null) {
            // Under the task's lock, so an attempt about to start sees the cancel
            synchronized (task) {
                task.cancelled().set(true);
                handle = task.currentAttempt().get();
            }
        } else {
            handle = activeProcesses.get(taskId);
        }

        // A running attempt is stopped, and its exit is reported as cancelled. One that already has its
        // verdict is left alone: after COMPLETED it is still flushing its index
        if (handle != null && handle.isAlive() && !handle.isCancelled() && !handle.getResultFuture().isDone()) {
            logger.info("Cancelling vector population subprocess for task: {}", taskId);
            handle.cancel();
            return true;
        }

        // No attempt left to stop (between restarts, not started yet, or already being killed for a stall
        // restart): the task ends here, and its pending restart sees that and never runs
        CompletableFuture<VectorPopulationResult> pending = handle != null ? handle.getResultFuture()
                : task != null ? task.resultFuture() : null;
        if (pending == null || !pending.complete(VectorPopulationResult.failure(taskId,
                handle != null ? handle.getCurrentPhase() : "STARTING", CANCELLED_BY_USER))) {
            return false;
        }
        logger.info("Cancelling vector population task {} between attempts", taskId);
        reportCancelled(taskId, handle, task != null ? task.vectorIndexPath() : handle.getVectorIndexPath(),
                CANCELLED_BY_USER);
        return true;
    }

    /** Report a task's cancel: to the progress trackers, the UI, and the log of the attempt it ended. */
    private void reportCancelled(String taskId, VectorPopulationHandle handle, String vectorIndexPath, String message) {
        String phase = handle != null ? handle.getCurrentPhase() : "STARTING";
        if (progressTracker != null) {
            progressTracker.cancelTask(taskId, message);
        }
        if (ingestProgressTracker != null) {
            String displayName = buildTaskDisplayName(vectorIndexPath);
            IngestPhase ingestPhase = statsConverter.mapPhaseToIngestPhase(phase);
            IngestStats stats = IngestStats.builder()
                    .subprocessRuntimeInfo(IngestProgressUpdate.SubprocessRuntimeInfo.forProcessMode("SUBPROCESS"))
                    .build();
            ingestProgressTracker.cancelTask(taskId, displayName, ingestPhase, message, stats);
        }

        broadcastProgress(taskId, phase, handle != null ? handle.getProgressPercent() : 0,
                "Cancelled", message, null);
        if (handle != null) {
            closeSubprocessLog(handle, "CANCELLED", null, message, false, false);
        }
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
        shuttingDown = true;

        // No pending restart may start once shutdown begins. A task with no attempt left running (between
        // restarts, not started, or its attempt being stopped for a restart) ends here: the restart scheduler
        // is shut down below, so nothing else would end it
        for (Map.Entry<String, TaskLaunch> entry : taskLaunches.entrySet()) {
            TaskLaunch task = entry.getValue();
            VectorPopulationHandle attempt;
            synchronized (task) {
                task.cancelled().set(true);
                attempt = task.currentAttempt().get();
            }
            if (attempt != null && attempt.isAlive() && !attempt.isCancelled()) {
                continue;
            }
            CompletableFuture<VectorPopulationResult> pending = attempt != null
                    ? attempt.getResultFuture() : task.resultFuture();
            if (pending.complete(VectorPopulationResult.failure(entry.getKey(),
                    attempt != null ? attempt.getCurrentPhase() : "STARTING", STOPPED_FOR_SHUTDOWN))) {
                reportCancelled(entry.getKey(), attempt, task.vectorIndexPath(), STOPPED_FOR_SHUTDOWN);
            }
        }

        for (VectorPopulationHandle handle : activeProcesses.values()) {
            if (handle.isAlive()) {
                logger.info("Cancelling subprocess: {}", handle.getTaskId());
                handle.cancel();
            }
        }

        for (VectorPopulationHandle handle : activeProcesses.values()) {
            handle.waitFor(Duration.ofSeconds(5));
            // Its watcher deletes the args file too, but the JVM may exit before the watcher gets there. The
            // file carries the staging API key
            deleteArgsFile(handle.getArgsFile());
        }

        // Release only the GPU rows this launcher acquired — the scheduler releases its own
        for (String taskId : List.copyOf(launcherGpuHolds)) {
            finishTask(taskId);
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
        // A restart may already have registered the task's next attempt — leave its tracking alone
        if (activeProcesses.remove(handle.getTaskId(), handle)) {
            warnedTaskIds.remove(handle.getTaskId());
            if (subprocessRegistry != null) {
                subprocessRegistry.deregister("vector-pop-" + handle.getTaskId());
            }
        }

        // The task's placement and GPU row outlive an attempt whose restart is pending
        if (handle.getResultFuture().isDone()) {
            finishTask(handle.getTaskId());
        }

        // Safety net: every verdict closes its attempt's log, so one still open here never got a verdict or
        // failed partway through reporting it
        if (handle.logWriter != null) {
            CompletableFuture<VectorPopulationResult> future = handle.getResultFuture();
            VectorPopulationResult verdict = future.isDone() && !future.isCompletedExceptionally()
                    ? future.join() : null;
            Process process = handle.getProcess();
            closeSubprocessLog(handle, verdict != null && verdict.success() ? "COMPLETED" : "FAILED",
                    process.isAlive() ? null : process.exitValue(),
                    verdict != null ? verdict.errorMessage() : "Subprocess ended without a verdict",
                    handle.isOomDetected(), false);
        }

        deleteArgsFile(handle.getArgsFile());
    }

    /** Idempotent: shutdown and the attempt's watcher may both delete it. */
    private void deleteArgsFile(Path argsFile) {
        if (argsFile == null) {
            return;
        }
        try {
            if (Files.deleteIfExists(argsFile)) {
                logger.debug("Deleted args file: {}", argsFile);
            }
        } catch (IOException e) {
            logger.warn("Failed to delete args file: {}", argsFile);
        }
    }

    /**
     * End a task's tracking once its result is final and no attempt is running, releasing its GPU row if
     * this launcher acquired it — a row the scheduler holds is the scheduler's to release. Idempotent:
     * the launcher's row is released exactly once.
     */
    private void finishTask(String taskId) {
        taskLaunches.remove(taskId);
        if (launcherGpuHolds.remove(taskId) && modelLifecycleManager != null) {
            logger.info("[vecpop-{}] Releasing GPU resources for finished vector population task", taskId);
            try {
                modelLifecycleManager.releaseGpuForVectorPopulation(taskId);
            } catch (Exception e) {
                logger.warn("[vecpop-{}] Error releasing GPU resources: {}", taskId, e.getMessage());
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
