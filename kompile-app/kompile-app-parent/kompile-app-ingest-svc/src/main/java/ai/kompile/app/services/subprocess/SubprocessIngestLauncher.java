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

import ai.kompile.app.config.IngestConfiguration;
import ai.kompile.app.config.KompileServerConstants;
import ai.kompile.app.config.Nd4jEnvironmentConfig;
import ai.kompile.app.config.SubprocessExecutableConfig;
import ai.kompile.utils.NativeImageInfo;
import ai.kompile.app.facts.domain.FactSheet;
import ai.kompile.app.facts.service.FactSheetService;
import ai.kompile.app.ingest.domain.IndexingJobHistory;
import ai.kompile.app.ingest.domain.IngestEvent;
import ai.kompile.app.ingest.service.IndexingJobHistoryService;
import ai.kompile.app.ingest.service.IngestEventService;
import ai.kompile.app.services.AppIndexConfigService;
import ai.kompile.app.config.DeviceRoutingConfig;
import ai.kompile.app.config.GpuDevice;
import ai.kompile.app.services.DeviceRoutingConfigService;
import ai.kompile.app.services.IngestProgressTracker;
import ai.kompile.app.services.ModelLifecycleManager;
import ai.kompile.app.services.Nd4jEnvironmentConfigService;
import ai.kompile.app.services.ServerPortService;
import ai.kompile.app.services.scheduler.JobResourceProfiles;
import ai.kompile.app.subprocess.AdaptiveRecoverySettings;
import ai.kompile.app.subprocess.BackendConfigurable;
import ai.kompile.app.subprocess.ManagedSubprocessLauncher.BackendPreference;
import ai.kompile.app.subprocess.SubprocessArgs;
import ai.kompile.app.subprocess.SubprocessBackendFlags;
import ai.kompile.app.subprocess.SubprocessEnvironmentPropagator;
import ai.kompile.app.subprocess.SubprocessMessage;
import ai.kompile.app.subprocess.SubprocessPlacement;
import ai.kompile.app.subprocess.SubprocessPlacementSupport;
import ai.kompile.app.subprocess.SubprocessProtocolChannel;
import ai.kompile.app.subprocess.SubprocessSignals;
import ai.kompile.app.web.dto.IngestProgressUpdate;
import ai.kompile.cli.common.logs.AgentLogRecord;
import ai.kompile.cli.common.logs.SubprocessLogWriter;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
// NOTE: Do NOT import Nd4j here - it would initialize ND4J native code in parent process
// which defeats the purpose of subprocess isolation. See captureNd4jConfig() comments.
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
// import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for launching and managing ingest subprocesses.
 *
 * This service spawns isolated JVM processes to run document ingestion,
 * preventing crashes and OOM errors in the subprocess from affecting
 * the main application.
 *
 * Key features:
 * - Spawns subprocess using same classpath as main app
 * - Parses progress JSON from subprocess stdout
 * - Forwards progress to WebSocket via IngestProgressTracker
 * - Handles subprocess crashes gracefully
 * - Supports cancellation and timeout
 * - Monitors subprocess health via heartbeats
 */
@Service
public class SubprocessIngestLauncher implements BackendConfigurable {

    private static final Logger logger = LoggerFactory.getLogger(SubprocessIngestLauncher.class);

    /**
     * Placement assigned through {@link BackendConfigurable}. Only the legacy five-argument
     * {@code launchIngest} reads it, once per job; the scheduler passes each job's placement instead.
     */
    private final SubprocessPlacementSupport placement = new SubprocessPlacementSupport();

    /** {@link BackendConfigurable} — the placement for the next launch that doesn't carry its own. */
    @Override
    public void applyPlacement(SubprocessPlacement p) {
        this.placement.applyPlacement(p);
    }

    // Scheduling intervals
    private static final long STALE_CHECK_INTERVAL_MS = 30_000L; // 30 seconds

    /** How long an exited child's COMPLETED message may still be in flight on its stdout reader. */
    private static final long COMPLETION_MESSAGE_GRACE_SECONDS = 5;

    /** How long an exited child's output readers may take to drain its pipes before its exit is judged. */
    private static final long OUTPUT_DRAIN_TIMEOUT_MS = 5_000;

    /** How long a job's result waits for the attempt that gave its verdict to exit. */
    private static final long VERDICT_EXIT_WAIT_SECONDS = 30;

    /**
     * Error types the child's memory watchdog reports when it stops its own run before an OOM
     * ({@code checkWatchdogOrExit} in IngestSubprocessMain) — retried with adaptive settings like one.
     */
    private static final Set<String> MEMORY_GUARD_ERROR_TYPES = Set.of("MemoryThreshold", "MemoryKillThreshold");

    private static final String SUBPROCESS_MAIN_CLASS = "ai.kompile.app.subprocess.IngestSubprocessMain";

    private String javaPath = "java";

    private String heapSize = "4g";

    private int timeoutMinutes = 60;

    private int heartbeatIntervalSeconds = 10;

    private int progressStallThresholdSeconds = 60;

    private int staleThresholdSeconds = 120;

    private final IngestProgressTracker progressTracker;
    private final IngestEventService eventService;
    private final IndexingJobHistoryService jobHistoryService;
    private final ServerPortService serverPortService;
    private final Nd4jEnvironmentConfigService nd4jEnvironmentConfigService;
    private final DeviceRoutingConfigService deviceRoutingConfigService;
    private final SubprocessConfigService subprocessConfigService;
    private final SubprocessExecutableConfig subprocessExecutableConfig;
    private final FactSheetService factSheetService;
    private final AppIndexConfigService appIndexConfigService;
    private final IngestConfiguration ingestConfiguration;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    ModelLifecycleManager modelLifecycleManager;

    /**
     * Optional task-completion sink. Implemented in app-main by {@code MonitorService}
     * (chat wake-up monitors); absent in persona apps that boot without that subsystem.
     */
    @Autowired(required = false)
    private ai.kompile.app.services.SubprocessTaskCompletionListener taskCompletionListener;

    @Autowired(required = false)
    private ai.kompile.app.subprocess.SubprocessRegistry subprocessRegistry;

    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private ai.kompile.app.services.scheduler.ResourceAwareJobScheduler resourceScheduler;

    @Autowired(required = false)
    private ai.kompile.app.services.SubprocessHeartbeatBroadcaster heartbeatBroadcaster;

    // Active subprocess tracking
    private final Map<String, SubprocessHandle> activeProcesses = new ConcurrentHashMap<>();

    // Store file paths by taskId for fact creation on completion
    private final Map<String, Path> taskFilePaths = new ConcurrentHashMap<>();

    // Store worker statuses per task for inclusion in progress updates (parity with
    // in-process mode)
    private final Map<String, Map<String, SubprocessMessage.WorkerStatus>> taskWorkerStatuses = new ConcurrentHashMap<>();

    // Track tasks that have already logged the missing progressTracker warning (to
    // avoid spam)
    private final Set<String> warnedTaskIds = ConcurrentHashMap.newKeySet();

    // === Adaptive Recovery Tracking ===

    /** Checkpoint paths by jobId (persists across retries) */
    private final Map<String, Path> jobCheckpointPaths = new ConcurrentHashMap<>();

    /** Retry state per jobId */
    private final Map<String, RetryState> jobRetryState = new ConcurrentHashMap<>();

    /** Original launch options per jobId (for retry) */
    private final Map<String, LaunchContext> jobLaunchContexts = new ConcurrentHashMap<>();

    /** Map from taskId to jobId (for looking up job context on completion) */
    private final Map<String, String> taskToJobId = new ConcurrentHashMap<>();

    /** JobIds whose GPU row this launcher acquired itself; released once, when the job ends */
    private final Set<String> launcherGpuHolds = ConcurrentHashMap.newKeySet();

    /** Set once shutdown begins; no attempt starts after it */
    private volatile boolean shuttingDown;

    /** Phase-2 log aggregation: JSON-lines writers keyed by taskId */
    private final Map<String, SubprocessLogWriter> logWriters = new ConcurrentHashMap<>();

    /** Directory for checkpoint storage */
    private Path checkpointBaseDir;

    /** Record to track retry state for adaptive recovery */
    private record RetryState(
            String jobId,
            int attemptNumber,
            ai.kompile.app.subprocess.AdaptiveRecoverySettings currentSettings,
            ai.kompile.app.subprocess.IngestCheckpoint checkpoint
    ) {}

    /**
     * Record to store original launch context for retry. Every attempt of the job runs on the same
     * placement; cancel reaches the current attempt and blocks any attempt not yet started.
     */
    private record LaunchContext(
            String jobId,
            Path filePath,
            String loaderName,
            String chunkerName,
            Map<String, Object> originalOptions,
            CompletableFuture<SubprocessHandle.SubprocessResult> resultFuture,
            SubprocessPlacement placement,
            AtomicReference<SubprocessHandle> currentAttempt,
            AtomicBoolean cancelled
    ) {}

    @Autowired
    public SubprocessIngestLauncher(
            @Autowired(required = false) IngestProgressTracker progressTracker,
            @Autowired(required = false) IngestEventService eventService,
            @Autowired(required = false) IndexingJobHistoryService jobHistoryService,
            @Autowired(required = false) ServerPortService serverPortService,
            @Autowired(required = false) Nd4jEnvironmentConfigService nd4jEnvironmentConfigService,
            @Autowired(required = false) DeviceRoutingConfigService deviceRoutingConfigService,
            @Autowired(required = false) SubprocessConfigService subprocessConfigService,
            @Autowired(required = false) SubprocessExecutableConfig subprocessExecutableConfig,
            @Autowired(required = false) FactSheetService factSheetService,
            @Autowired(required = false) AppIndexConfigService appIndexConfigService,
            @Autowired(required = false) IngestConfiguration ingestConfiguration) {
        this.progressTracker = progressTracker;
        this.eventService = eventService;
        this.jobHistoryService = jobHistoryService;
        this.serverPortService = serverPortService;
        this.nd4jEnvironmentConfigService = nd4jEnvironmentConfigService;
        this.deviceRoutingConfigService = deviceRoutingConfigService;
        this.subprocessConfigService = subprocessConfigService;
        this.subprocessExecutableConfig = subprocessExecutableConfig;
        this.factSheetService = factSheetService;
        this.appIndexConfigService = appIndexConfigService;
        this.ingestConfiguration = ingestConfiguration;
        this.objectMapper = JsonUtils.standardMapper();

        // Initialize checkpoint directory
        initializeCheckpointDirectory();

        // Warn if progress tracking dependencies are missing
        if (progressTracker == null) {
            logger.warn("SubprocessIngestLauncher initialized WITHOUT IngestProgressTracker - " +
                    "UI will NOT receive real-time progress updates in subprocess mode!");
        } else {
            logger.info("SubprocessIngestLauncher initialized with progress tracking enabled");
        }
    }

    /**
     * Initialize the checkpoint directory for storing progress state.
     */
    private void initializeCheckpointDirectory() {
        try {
            // Use ~/.kompile/checkpoints as the base directory
            Path kompileHome = Path.of(System.getProperty("user.home"), ".kompile");
            this.checkpointBaseDir = kompileHome.resolve("checkpoints");
            Files.createDirectories(checkpointBaseDir);
            logger.info("Checkpoint directory initialized: {}", checkpointBaseDir);
        } catch (IOException e) {
            logger.warn("Failed to create checkpoint directory, will use temp dir: {}", e.getMessage());
            try {
                this.checkpointBaseDir = Files.createTempDirectory("kompile-checkpoints-");
            } catch (IOException ex) {
                logger.error("Failed to create temp checkpoint directory", ex);
                this.checkpointBaseDir = Path.of(System.getProperty("java.io.tmpdir"));
            }
        }
    }

    /**
     * Get or create checkpoint path for a job.
     */
    private Path getCheckpointPath(String jobId) {
        return jobCheckpointPaths.computeIfAbsent(jobId, id ->
                checkpointBaseDir.resolve("ingest-" + id + ".checkpoint.json"));
    }

    /**
     * Launch a subprocess to ingest a document on the placement assigned via {@link #applyPlacement}.
     *
     * @param taskId      Unique task identifier
     * @param filePath    Path to the file to ingest
     * @param loaderName  Optional loader name (null for auto-detect)
     * @param chunkerName Optional chunker name (null for default)
     * @param options     Additional options
     * @return Future that completes when subprocess finishes
     */
    public CompletableFuture<SubprocessHandle.SubprocessResult> launchIngest(
            String taskId,
            Path filePath,
            String loaderName,
            String chunkerName,
            Map<String, Object> options) {
        return launchIngest(taskId, filePath, loaderName, chunkerName, options, placement.placement());
    }

    /**
     * Launch a subprocess to ingest a document on an explicit placement. The taskId is the job's id
     * for its whole life: every retry reuses this placement, and cancelling it reaches any attempt.
     *
     * @param placement the child's backend/device/memory cap — the scheduler's placement for the job
     *                  it holds a GPU row for; null lets the launcher reserve the job's own row
     * @return Future that completes when the job finishes (after any retries)
     */
    public CompletableFuture<SubprocessHandle.SubprocessResult> launchIngest(
            String taskId,
            Path filePath,
            String loaderName,
            String chunkerName,
            Map<String, Object> options,
            SubprocessPlacement placement) {
        // The first attempt's taskId is the jobId that persists across retries
        String jobId = taskId;
        LaunchContext existing = jobLaunchContexts.get(jobId);
        if (existing != null) {
            logger.warn("Ingest job {} is already running; not launching it again", jobId);
            return afterAttemptExits(existing);
        }

        // Settle the job's GPU row before any command is built, so the child is pinned to its device
        SubprocessPlacement jobPlacement = resolveJobPlacement(jobId, filePath.getFileName().toString(), placement);
        CompletableFuture<SubprocessHandle.SubprocessResult> resultFuture = new CompletableFuture<>();
        LaunchContext context = new LaunchContext(jobId, filePath, loaderName, chunkerName,
                options != null ? new HashMap<>(options) : new HashMap<>(), resultFuture,
                jobPlacement, new AtomicReference<>(), new AtomicBoolean());
        jobLaunchContexts.put(jobId, context);
        // Fire any chat monitors registered for this task when it completes.
        // Attach exactly once — on the original future only, not on retries.
        resultFuture.whenComplete((result, error) -> notifyMonitorService(taskId, result, error));
        launchIngestInternal(taskId, context, null);
        return afterAttemptExits(context);
    }

    /**
     * The job's result, once the attempt that gave it has exited. The verdict can come while the child
     * is still running — its COMPLETED or FAILED message, a timeout, a stall — and a caller holding the
     * job's GPU row releases it when this completes, so the device is not handed on while the child
     * still has it. Waits at most {@link #VERDICT_EXIT_WAIT_SECONDS}.
     */
    private CompletableFuture<SubprocessHandle.SubprocessResult> afterAttemptExits(LaunchContext context) {
        return context.resultFuture().thenCompose(result -> {
            SubprocessHandle attempt = context.currentAttempt().get();
            if (attempt == null || !attempt.isAlive()) {
                return CompletableFuture.completedFuture(result);
            }
            return attempt.getProcess().onExit()
                    .completeOnTimeout(null, VERDICT_EXIT_WAIT_SECONDS, TimeUnit.SECONDS)
                    .handle((exited, error) -> {
                        if (exited == null) {
                            logger.warn("Ingest job {} has ended, but its subprocess {} is still running after {}s",
                                    context.jobId(), attempt.getTaskId(), VERDICT_EXIT_WAIT_SECONDS);
                        }
                        return result;
                    });
        });
    }

    /**
     * The placement every attempt of a new job runs on. A CPU placement, or a GPU placement for a job
     * whose row is already held (the scheduler acquired it), is used as given. Otherwise the launcher
     * acquires the job's own row and places the child on that device; if it can't, the job runs on
     * CPU — a child is never pinned to a GPU without a row.
     */
    private SubprocessPlacement resolveJobPlacement(String jobId, String fileName, SubprocessPlacement requested) {
        if (modelLifecycleManager == null
                || (requested != null && requested.backend() == BackendPreference.CPU)) {
            return requested;
        }
        long capBytes = JobResourceProfiles.INGEST.peakGpuMemoryBytes();
        ModelLifecycleManager.JobGpuHold held = modelLifecycleManager.getActiveJobHolds().get(jobId);
        if (held != null) {
            return requested != null && requested.isGpu() ? requested : placementOn(held.device(), capBytes);
        }
        if (requested != null) {
            logger.warn("[ingest-{}] GPU placement {} has no GPU row; acquiring one for the job", jobId, requested);
        }
        try {
            GpuDevice device = modelLifecycleManager.acquireGpuForJob(jobId,
                    JobResourceProfiles.INGEST.serviceType(), "Ingest: " + fileName,
                    ModelLifecycleManager.HoldLifetime.BOUNDED, capBytes, null);
            launcherGpuHolds.add(jobId);
            logger.info("[ingest-{}] GPU row acquired for ingest job on {}", jobId, device.name());
            return placementOn(device, capBytes);
        } catch (IllegalStateException e) {
            logger.warn("[ingest-{}] Could not acquire GPU for ingest, running on CPU: {}", jobId, e.getMessage());
            return SubprocessPlacement.cpu();
        }
    }

    /** The child's placement on a reserved device — derived the same way the scheduler derives it. */
    private static SubprocessPlacement placementOn(GpuDevice device, long capBytes) {
        return SubprocessPlacement.gpu(device.cudaRuntimeIndex(),
                ModelLifecycleManager.clampToDevice(capBytes, device));
    }

    /**
     * Internal method to launch one attempt of a job with full control over settings.
     * Used for both initial launch and retry with adaptive settings.
     */
    private CompletableFuture<SubprocessHandle.SubprocessResult> launchIngestInternal(
            String taskId,
            LaunchContext context,
            AdaptiveRecoverySettings recoverySettings) {
        String jobId = context.jobId();
        Path filePath = context.filePath();
        String loaderName = context.loaderName();
        String chunkerName = context.chunkerName();
        Map<String, Object> options = context.originalOptions();
        CompletableFuture<SubprocessHandle.SubprocessResult> resultFuture = context.resultFuture();
        logger.info("Launching ingest subprocess for task: {} (jobId: {}) file: {}", taskId, jobId, filePath);

        // Store file path for fact creation on completion
        taskFilePaths.put(taskId, filePath);

        Path argsFile = null;
        Process process = null;
        boolean monitored = false;
        try {
            String fileName = filePath.getFileName().toString();

            String nd4jConfigJson = captureNd4jConfig();

            // Build subprocess args
            String callbackBaseUrl = serverPortService != null
                    ? serverPortService.getBaseUrl()
                    : KompileServerConstants.DEFAULT_APP_URL;

            // Get model source configuration from AnseriniEncoderFactory (inherits from
            // parent)
            String modelSourceType = ai.kompile.embedding.anserini.AnseriniEncoderFactory.getSourceType();
            String modelIdentifier = ai.kompile.embedding.anserini.AnseriniEncoderFactory
                    .getSelectedDenseRetrievalModel()
                    .orElse(null);

            // Get staging URL/API key or archive path directly from the parent's registry
            // manager
            String stagingUrl = ai.kompile.embedding.anserini.AnseriniEncoderFactory.getStagingUrl();
            String stagingApiKey = ai.kompile.embedding.anserini.AnseriniEncoderFactory.getStagingApiKey();
            Path archivePathObj = ai.kompile.embedding.anserini.AnseriniEncoderFactory
                    .getLoadedArchivePath();
            String archivePath = archivePathObj != null ? archivePathObj.toString() : null;

            logger.info(
                    "Subprocess model source (inherited from parent): type={}, modelId={}, stagingUrl={}, archivePath={}",
                    modelSourceType, modelIdentifier, stagingUrl, archivePath);

            // Get memory thresholds from IngestConfiguration (or use defaults)
            int memoryThresholdPercent = ingestConfiguration != null
                    ? ingestConfiguration.getMemoryThresholdPercent()
                    : SubprocessArgs.DEFAULT_MEMORY_THRESHOLD_PERCENT;
            int memoryCriticalPercent = ingestConfiguration != null
                    ? ingestConfiguration.getMemoryCriticalPercent()
                    : SubprocessArgs.DEFAULT_MEMORY_CRITICAL_PERCENT;
            int memoryKillThresholdPercent = ingestConfiguration != null
                    ? ingestConfiguration.getMemoryKillThresholdPercent()
                    : SubprocessArgs.DEFAULT_MEMORY_KILL_THRESHOLD_PERCENT;

            // Get GPU memory thresholds from SubprocessConfigService (or use defaults)
            int gpuMemoryThresholdPercent = subprocessConfigService != null
                    ? subprocessConfigService.getGpuMemoryThresholdPercent()
                    : SubprocessArgs.DEFAULT_GPU_MEMORY_THRESHOLD_PERCENT;
            int gpuMemoryCriticalPercent = subprocessConfigService != null
                    ? subprocessConfigService.getGpuMemoryCriticalPercent()
                    : SubprocessArgs.DEFAULT_GPU_MEMORY_CRITICAL_PERCENT;
            int gpuMemoryKillThresholdPercent = subprocessConfigService != null
                    ? subprocessConfigService.getGpuMemoryKillThresholdPercent()
                    : SubprocessArgs.DEFAULT_GPU_MEMORY_KILL_THRESHOLD_PERCENT;
            int gpuSoftLimitPercent = subprocessConfigService != null
                    ? subprocessConfigService.getGpuSoftLimitPercent()
                    : 0;

            // Get off-heap memory thresholds from SubprocessConfigService (or use defaults)
            int offHeapThresholdPercent = subprocessConfigService != null
                    ? subprocessConfigService.getOffHeapThresholdPercent()
                    : SubprocessArgs.DEFAULT_OFF_HEAP_THRESHOLD_PERCENT;
            int offHeapCriticalPercent = subprocessConfigService != null
                    ? subprocessConfigService.getOffHeapCriticalPercent()
                    : SubprocessArgs.DEFAULT_OFF_HEAP_CRITICAL_PERCENT;
            int offHeapKillThresholdPercent = subprocessConfigService != null
                    ? subprocessConfigService.getOffHeapKillThresholdPercent()
                    : SubprocessArgs.DEFAULT_OFF_HEAP_KILL_THRESHOLD_PERCENT;

            logger.debug("Subprocess memory thresholds: heap stop={}%, critical={}%, kill={}%; GPU stop={}%, critical={}%, kill={}%; off-heap stop={}%, critical={}%, kill={}%",
                    memoryThresholdPercent, memoryCriticalPercent, memoryKillThresholdPercent,
                    gpuMemoryThresholdPercent, gpuMemoryCriticalPercent, gpuMemoryKillThresholdPercent,
                    offHeapThresholdPercent, offHeapCriticalPercent, offHeapKillThresholdPercent);

            // Resolve paths from active FactSheet via AppIndexConfigService
            String resolvedVectorPath = null;
            String resolvedKeywordPath = null;
            if (appIndexConfigService != null) {
                ai.kompile.app.config.AppIndexConfig config = appIndexConfigService.getActualConfiguration();
                if (config != null) {
                    resolvedVectorPath = config.getVectorStorePath();
                    resolvedKeywordPath = config.getKeywordIndexPath();
                }
            }

            logger.info("Resolving paths for ingest subprocess: vector={}, keyword={}",
                    resolvedVectorPath, resolvedKeywordPath);

            // Get checkpoint path for this job
            Path checkpointPath = getCheckpointPath(jobId);
            boolean shouldResume = recoverySettings != null && Files.exists(checkpointPath);

            // Determine effective batch size and other settings
            int effectiveBatchSize = SubprocessArgs.DEFAULT_EMBEDDING_BATCH_SIZE;
            if (recoverySettings != null) {
                effectiveBatchSize = recoverySettings.getBatchSize();
                logger.info("Using adaptive recovery settings: {}", recoverySettings.toSummary());
            } else if (options != null && options.containsKey("embeddingBatchSize")) {
                effectiveBatchSize = ((Number) options.get("embeddingBatchSize")).intValue();
            }

            // Build subprocess options with adaptive settings
            Map<String, Object> effectiveOptions = new HashMap<>(options != null ? options : Map.of());
            effectiveOptions.put("jobId", jobId);
            if (recoverySettings != null) {
                effectiveOptions.put("nd4jThreads", recoverySettings.getNd4jThreads());
                effectiveOptions.put("ompThreads", recoverySettings.getOmpThreads());
                effectiveOptions.put("embeddingWorkers", recoverySettings.getEmbeddingWorkers());
                effectiveOptions.put("retryAttempt", recoverySettings.getRetryAttempt());
                // Override heap size in options for buildCommand to use
                effectiveOptions.put("heapSize", recoverySettings.getHeapSize());
            }

            SubprocessArgs args = SubprocessArgs.builder()
                    .taskId(taskId)
                    .filePath(filePath.toString())
                    .loaderName(loaderName)
                    .chunkerName(chunkerName)
                    .embeddingBatchSize(effectiveBatchSize)
                    .vectorStorePath(resolvedVectorPath)
                    .keywordIndexPath(resolvedKeywordPath)
                    .indexPath(resolvedKeywordPath) // Keep for legacy if needed
                    .callbackBaseUrl(callbackBaseUrl)
                    .nd4jConfigJson(nd4jConfigJson)
                    .checkpointPath(checkpointPath.toString())
                    .resume(shouldResume)
                    .modelSourceType(modelSourceType)
                    .modelIdentifier(modelIdentifier)
                    .stagingUrl(stagingUrl)
                    .stagingApiKey(stagingApiKey)
                    .archivePath(archivePath)
                    .memoryThresholdPercent(memoryThresholdPercent)
                    .memoryCriticalPercent(memoryCriticalPercent)
                    .memoryKillThresholdPercent(memoryKillThresholdPercent)
                    .memoryCheckIntervalMs(SubprocessArgs.DEFAULT_MEMORY_CHECK_INTERVAL_MS)
                    .gpuMemoryThresholdPercent(gpuMemoryThresholdPercent)
                    .gpuMemoryCriticalPercent(gpuMemoryCriticalPercent)
                    .gpuMemoryKillThresholdPercent(gpuMemoryKillThresholdPercent)
                    .gpuSoftLimitPercent(gpuSoftLimitPercent)
                    .offHeapThresholdPercent(offHeapThresholdPercent)
                    .offHeapCriticalPercent(offHeapCriticalPercent)
                    .offHeapKillThresholdPercent(offHeapKillThresholdPercent)
                    .options(effectiveOptions)
                    .build();

            logger.debug("Using callback URL: {}", callbackBaseUrl);

            // Create job history + persist an initial QUEUED event BEFORE broadcasting
            // progress.
            // This ensures the UI can immediately fetch the ND4J environment snapshot for
            // subprocess mode.
            createJobHistoryAndLogQueued(taskId, fileName, filePath, nd4jConfigJson);

            // Record checkpoint path in job history so the job can be resumed later
            if (jobHistoryService != null) {
                try {
                    jobHistoryService.recordCheckpointPath(taskId, checkpointPath.toString(),
                            ai.kompile.app.ingest.domain.IngestEvent.IngestPhase.EMBEDDING);
                } catch (Exception e) {
                    logger.debug("Failed to record checkpoint path for {}: {}", taskId, e.getMessage());
                }
            }

            // Write args to temp file
            argsFile = args.writeToTempFile();
            logger.debug("Wrote subprocess args to: {}", argsFile);

            // Build command with effective options (includes adaptive settings) on the job's placement
            List<String> command = buildCommand(argsFile, effectiveOptions, context.placement());
            logger.info("Subprocess command: {}", String.join(" ", command));
            if (shouldResume) {
                logger.info("ADAPTIVE RETRY: Resuming from checkpoint at {}", checkpointPath);
            }

            // Start process
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(false);

            // Propagate ND4J environment variables from parent process
            propagateNd4jEnvironment(processBuilder.environment(), context.placement());

            // Apply thread settings from recovery if specified
            if (recoverySettings != null) {
                processBuilder.environment().put("OMP_NUM_THREADS", String.valueOf(recoverySettings.getOmpThreads()));
                processBuilder.environment().put("MKL_NUM_THREADS", String.valueOf(recoverySettings.getOmpThreads()));
                processBuilder.environment().put("OPENBLAS_NUM_THREADS", String.valueOf(recoverySettings.getOmpThreads()));
                logger.info("Applied adaptive thread settings: OMP_NUM_THREADS={}", recoverySettings.getOmpThreads());
            }

            // Protocol messages get a pipe of their own, which native output written to fd 1 can't reach
            boolean wrapped = SubprocessProtocolChannel.apply(processBuilder);

            // The job's GPU row was settled when it was first launched; attempts never acquire one.
            // Starting under the context's lock orders this attempt against cancel: a cancel either
            // blocks the start or finds the started attempt.
            SubprocessHandle handle = null;
            synchronized (context) {
                if (!context.cancelled().get() && !resultFuture.isDone() && !shuttingDown) {
                    process = processBuilder.start();
                    handle = createHandle(taskId, fileName, process, resultFuture, argsFile);
                    context.currentAttempt().set(handle);
                }
            }
            if (handle == null) {
                logger.info("Ingest job {} was cancelled or has ended; not starting attempt {}", jobId, taskId);
                // The args file carries the staging API key
                deleteArgsFile(argsFile);
                taskFilePaths.remove(taskId);
                // The cancel may have found no attempt to end the job (a shutdown while a retry was
                // pending), so the job ends here
                endJobBetweenAttempts(context, SubprocessHandle.SubprocessResult.failure(
                        taskId, -1, "Cancelled before start", null, true, false));
                return resultFuture;
            }
            logger.info("Started subprocess with PID: {}", process.pid());

            // Register with centralized subprocess registry for orphan protection
            if (subprocessRegistry != null) {
                subprocessRegistry.register("ingest-" + taskId, process, "ingest");
            }

            // Phase-2 log aggregation: open central JSON-lines writer
            try {
                String workingDir = processBuilder.directory() != null
                        ? processBuilder.directory().getAbsolutePath()
                        : System.getProperty("user.dir");
                SubprocessLogWriter slw = new SubprocessLogWriter("ingest", taskId, workingDir);
                slw.writeStart(new SubprocessLogWriter.SubprocessRunContext(
                        null, command,
                        workingDir,
                        process.pid(), getEffectiveHeapSize(effectiveOptions)));
                logWriters.put(taskId, slw);
                logger.debug("[ingest-{}] SubprocessLogWriter opened: {}", taskId, slw.getLogFile());
            } catch (Exception _logEx) {
                logger.debug("[ingest-{}] SubprocessLogWriter init failed (non-fatal): {}", taskId, _logEx.getMessage());
            }

            // Track taskId -> jobId mapping for retry handling
            taskToJobId.put(taskId, jobId);

            activeProcesses.put(taskId, handle);

            // Start monitoring
            startMonitoring(handle, getEffectiveTimeoutMinutes(effectiveOptions),
                    SubprocessProtocolChannel.stderrProtocol(wrapped, SubprocessMessage.MESSAGE_PREFIX, "ingest-" + taskId));
            monitored = true;

            // Update progress tracker with active fact sheet association
            if (progressTracker != null) {
                Long factSheetId = null;
                if (factSheetService != null) {
                    try {
                        FactSheet activeSheet = factSheetService.getActiveSheet();
                        if (activeSheet != null) {
                            factSheetId = activeSheet.getId();
                        }
                    } catch (Exception e) {
                        logger.warn("Could not get active fact sheet for task {}: {}", taskId, e.getMessage());
                    }
                }
                progressTracker.startTask(taskId, fileName, factSheetId);
            }

        } catch (Exception e) {
            logger.error("Failed to launch subprocess for task: {}", taskId, e);
            if (!monitored) {
                // Nothing watches this attempt, so its failure ends the job here
                if (process != null) {
                    process.destroyForcibly();
                    activeProcesses.remove(taskId);
                    taskToJobId.remove(taskId);
                    if (subprocessRegistry != null) {
                        subprocessRegistry.deregister("ingest-" + taskId);
                    }
                    closeSubprocessLog(taskId, "FAILED", null, e.getMessage(), false, false);
                }
                // The args file carries the staging API key
                deleteArgsFile(argsFile);
                taskFilePaths.remove(taskId);
                boolean first = resultFuture.completeExceptionally(e);
                finishJob(jobId);
                if (first) {
                    reportLaunchFailure(taskId, filePath, e);
                }
            }
        }

        return resultFuture;
    }

    /** Report an attempt that failed to launch: nothing watches it, so nothing else reports its failure. */
    private void reportLaunchFailure(String taskId, Path filePath, Exception cause) {
        String message = "Failed to launch ingest subprocess: " + cause.getMessage();
        try {
            if (progressTracker != null) {
                progressTracker.failTask(taskId, filePath.getFileName().toString(), toProgressPhase(null), message);
            }
            if (jobHistoryService != null) {
                jobHistoryService.markJobFailed(taskId, toEventPhase(null), message, cause,
                        IndexingJobHistory.FailureReason.SUBPROCESS_ERROR);
            }
        } catch (Exception e) {
            logger.warn("Failed to report the launch failure of task {}: {}", taskId, e.getMessage());
        }
    }

    private void createJobHistoryAndLogQueued(String taskId, String fileName, Path filePath, String nd4jConfigJson) {
        try {
            if (eventService != null && eventService.isEnabled()) {
                eventService.logQueuedWithEnvironmentSnapshot(taskId, fileName, nd4jConfigJson);
            }

            if (jobHistoryService != null) {
                Long fileSizeBytes = null;
                String contentType = null;
                try {
                    if (filePath != null && Files.exists(filePath)) {
                        fileSizeBytes = Files.size(filePath);
                        contentType = Files.probeContentType(filePath);
                    }
                } catch (IOException e) {
                    logger.debug("Failed to read file metadata for job history: {}", e.getMessage());
                }

                jobHistoryService.createJobWithEnvironment(taskId, fileName, nd4jConfigJson, fileSizeBytes,
                        contentType);
            }
        } catch (Exception e) {
            logger.warn("Failed to create initial job history/event log for task {}: {}", taskId, e.getMessage());
        }
    }

    /**
     * Cancel an ingest job: its running attempt, and any retry that has not started yet.
     *
     * @param taskId the job's id (the taskId it was launched with, which the scheduler uses as its
     *               jobId) or the taskId of any of its attempts
     * @return true if cancelled, false if not found or already finished
     */
    public boolean cancelIngest(String taskId) {
        String jobId = jobLaunchContexts.containsKey(taskId) ? taskId : taskToJobId.get(taskId);
        LaunchContext context = jobId != null ? jobLaunchContexts.get(jobId) : null;
        if (context == null || context.resultFuture().isDone()) {
            return false;
        }

        SubprocessHandle attempt;
        synchronized (context) {
            context.cancelled().set(true);
            attempt = context.currentAttempt().get();
        }

        logger.info("Cancelling ingest job {} (attempt: {})", jobId, attempt != null ? attempt.getTaskId() : "none");
        if (attempt != null && (attempt.isAlive() || activeProcesses.containsKey(attempt.getTaskId()))) {
            // The attempt's completion watcher gives the job's verdict once the process has exited:
            // cancelled, unless the child reported its own outcome first
            attempt.cancel();
        } else {
            // No attempt is running (a retry is pending) and none will start, so the job ends here
            endJobBetweenAttempts(context, SubprocessHandle.SubprocessResult.failure(
                    attempt != null ? attempt.getTaskId() : jobId, -1, "Cancelled by user",
                    attempt != null ? attempt.getCurrentPhase() : null, true, false));
        }
        return true;
    }

    /**
     * End a job that has no running attempt to judge it — cancelled, or failed between attempts — with
     * the given result, reported only as the job's first verdict.
     */
    private void endJobBetweenAttempts(LaunchContext context, SubprocessHandle.SubprocessResult result) {
        boolean first = context.resultFuture().complete(result);
        finishJob(context.jobId());
        if (!first) {
            return;
        }
        String taskId = result.taskId();
        try {
            String fileName = context.filePath().getFileName().toString();
            if (progressTracker != null) {
                IngestProgressUpdate.IngestPhase phase = toProgressPhase(result.errorPhase());
                if (result.cancelled()) {
                    progressTracker.cancelTask(taskId, fileName, phase, result.errorMessage(), null);
                } else if (result.oomKilled()) {
                    progressTracker.failTaskOutOfMemory(taskId, fileName, phase, result.errorMessage());
                } else {
                    progressTracker.failTask(taskId, fileName, phase, result.errorMessage());
                }
            }
            if (jobHistoryService != null) {
                IndexingJobHistory.FailureReason reason = result.cancelled()
                        ? IndexingJobHistory.FailureReason.USER_CANCELLED
                        : result.oomKilled()
                                ? IndexingJobHistory.FailureReason.OUT_OF_MEMORY
                                : IndexingJobHistory.FailureReason.SUBPROCESS_ERROR;
                jobHistoryService.markJobFailed(taskId, toEventPhase(result.errorPhase()), result.errorMessage(),
                        null, reason);
            }
        } catch (Exception e) {
            logger.warn("Failed to report the end of ingest job {}: {}", context.jobId(), e.getMessage());
        }
    }

    /**
     * Get status of a subprocess.
     *
     * @param taskId Task identifier
     * @return Status or null if not found
     */
    public SubprocessHandle.SubprocessStatus getStatus(String taskId) {
        SubprocessHandle handle = activeProcesses.get(taskId);
        if (handle == null) {
            return null;
        }
        return handle.getStatus();
    }

    /**
     * Get all active subprocess statuses.
     */
    public List<SubprocessHandle.SubprocessStatus> getAllStatuses() {
        List<SubprocessHandle.SubprocessStatus> statuses = new ArrayList<>();
        for (SubprocessHandle handle : activeProcesses.values()) {
            statuses.add(handle.getStatus());
        }
        return statuses;
    }

    /**
     * Build the subprocess command.
     *
     * @param argsFile     Path to the args file
     * @param options      Per-request options (heapSize, timeoutMinutes, etc.)
     * @param jobPlacement The job's backend/device/memory placement (null = inherit)
     */
    private List<String> buildCommand(Path argsFile, Map<String, Object> options, SubprocessPlacement jobPlacement) {
        // Check if we should use native executable mode
        if (shouldUseNativeExecutableMode()) {
            return buildNativeCommand(argsFile, jobPlacement);
        }

        // JVM classpath mode
        return buildJvmCommand(argsFile, options, jobPlacement);
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
    private List<String> buildNativeCommand(Path argsFile, SubprocessPlacement jobPlacement) {
        if (subprocessConfigService == null) {
            throw new IllegalStateException(
                "Native executable mode required but SubprocessConfigService not available.");
        }

        String executablePath = subprocessConfigService.getExecutablePathForType("ingest");
        if (executablePath == null || executablePath.isBlank()) {
            throw new IllegalStateException(
                "Native executable mode required but no executable path configured. " +
                "Configure the native executable path in Processing Settings (Developer Hub).");
        }

        List<String> command = new ArrayList<>();
        command.add(executablePath);

        // Device-agnostic backend/device/memory -D flags go before the dispatch token, as in
        // ManagedSubprocessLauncher's native self-exec — a native image reads them at startup.
        command.addAll(SubprocessBackendFlags.jvmFlags(jobPlacement, BackendPreference.INHERIT));

        // Add subprocess type flag if using unified executable
        if (subprocessConfigService.useUnifiedExecutable("ingest")) {
            command.add(subprocessConfigService.getSubprocessTypeFlag() + "ingest");
        }

        // Add args file
        command.add(argsFile.toString());

        logger.info("Using native executable mode for ingest subprocess: {}", executablePath);
        return command;
    }

    /**
     * Build command for JVM classpath mode.
     */
    private List<String> buildJvmCommand(Path argsFile, Map<String, Object> options, SubprocessPlacement jobPlacement) {
        List<String> command = new ArrayList<>();

        // Java executable
        command.add(getEffectiveJavaPath());

        // JVM options - use per-request heap size if provided
        String heapSizeArg = toXmxArg(getEffectiveHeapSize(options));
        if (heapSizeArg != null) {
            command.add(heapSizeArg);
        }
        command.add("-XX:+ExitOnOutOfMemoryError"); // Exit cleanly on OOM
        command.add("-Dfile.encoding=UTF-8");

        Long offHeapBytes = getEffectiveOffHeapMaxBytes();
        if (offHeapBytes != null && offHeapBytes > 0) {
            command.add("-Dorg.bytedeco.javacpp.maxbytes=" + offHeapBytes);
            command.add("-Dorg.bytedeco.javacpp.maxphysicalbytes=" + offHeapBytes);
        }

        // Classpath - build comprehensive classpath from multiple sources
        String classpath = buildSubprocessClasspath();

        // Log classpath for debugging ClassNotFoundException issues
        logger.info("Subprocess classpath length: {} chars, entries: {}",
                classpath.length(),
                classpath.split(System.getProperty("path.separator")).length);
        if (logger.isDebugEnabled()) {
            String[] entries = classpath.split(System.getProperty("path.separator"));
            for (int i = 0; i < Math.min(entries.length, 20); i++) {
                logger.debug("  Classpath[{}]: {}", i, entries[i]);
            }
            if (entries.length > 20) {
                logger.debug("  ... and {} more entries", entries.length - 20);
            }
        }

        // Device-agnostic backend/device selection from the shared base infra — no CUDA_VISIBLE_DEVICES.
        command.addAll(SubprocessBackendFlags.jvmFlags(jobPlacement, BackendPreference.INHERIT));
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
        return javaPath;
    }

    private String getEffectiveHeapSize() {
        return getEffectiveHeapSize(null);
    }

    private String getEffectiveHeapSize(Map<String, Object> options) {
        // Check per-request options first
        if (options != null && options.containsKey("heapSize")) {
            String heapSizeOption = String.valueOf(options.get("heapSize"));
            if (heapSizeOption != null && !heapSizeOption.isBlank() && !"null".equals(heapSizeOption)) {
                return heapSizeOption.trim();
            }
        }
        // Fall back to config service
        if (subprocessConfigService != null) {
            String configured = subprocessConfigService.getHeapSize();
            if (configured != null && !configured.isBlank()) {
                return configured.trim();
            }
        }
        return heapSize;
    }

    private int getEffectiveStaleThresholdSeconds() {
        if (subprocessConfigService != null) {
            return subprocessConfigService.getStaleThresholdSeconds();
        }
        return staleThresholdSeconds;
    }

    /**
     * How long one attempt may run before it is killed: the request's {@code timeoutMinutes}, else the
     * configured subprocess timeout, else the launcher default.
     */
    private int getEffectiveTimeoutMinutes(Map<String, Object> options) {
        Object requested = options != null ? options.get("timeoutMinutes") : null;
        if (requested != null) {
            try {
                int minutes = requested instanceof Number n ? n.intValue() : Integer.parseInt(requested.toString().trim());
                if (minutes > 0) {
                    return minutes;
                }
            } catch (NumberFormatException e) {
                logger.warn("Ignoring invalid timeoutMinutes option: {}", requested);
            }
        }
        if (subprocessConfigService != null && subprocessConfigService.getTimeoutMinutes() > 0) {
            return subprocessConfigService.getTimeoutMinutes();
        }
        return timeoutMinutes;
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

    /**
     * Parse memory sizes like "8g", "8192m", "5000MB", or raw bytes ("8589934592")
     * into bytes.
     * Returns null for null/blank/unparseable values.
     */
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

        // Heuristic for unitless values:
        // - Small values (<= 1024) are almost always intended as GB in our UI/config
        // (e.g. "32" meaning "32g")
        // - Large values are assumed to be raw bytes (e.g. "34359738368")
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
     * Build a comprehensive classpath for the subprocess.
     *
     * This method extracts URLs from the classloader hierarchy to handle cases
     * where
     * Spring Boot or other frameworks use custom classloaders that don't expose
     * their
     * classpath via java.class.path system property.
     *
     * When running via `mvn spring-boot:run`, the java.class.path may only contain
     * a small launcher JAR, while the actual application classes are loaded by
     * Spring Boot's RestartClassLoader or similar. This method traverses the
     * classloader
     * chain to extract all URLs.
     *
     * @return A path-separator delimited string of classpath entries
     */
    private String buildSubprocessClasspath() {
        Set<String> classpathEntries = new LinkedHashSet<>();
        String pathSeparator = System.getProperty("path.separator");

        // 1. Start with java.class.path (may be incomplete when using Spring Boot)
        String systemClasspath = System.getProperty("java.class.path");
        if (systemClasspath != null && !systemClasspath.isBlank()) {
            for (String entry : systemClasspath.split(pathSeparator)) {
                if (!entry.isBlank()) {
                    classpathEntries.add(entry);
                }
            }
        }

        // 2. Extract URLs from classloader hierarchy (handles Spring Boot's
        // classloaders)
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = getClass().getClassLoader();
        }

        while (classLoader != null) {
            if (classLoader instanceof java.net.URLClassLoader urlClassLoader) {
                for (java.net.URL url : urlClassLoader.getURLs()) {
                    try {
                        // Convert URL to file path
                        String path = url.toURI().getPath();
                        if (path != null && !path.isBlank()) {
                            classpathEntries.add(path);
                        }
                    } catch (Exception e) {
                        // If URL can't be converted to path, try string representation
                        String urlStr = url.toString();
                        if (urlStr.startsWith("file:")) {
                            classpathEntries.add(urlStr.substring(5));
                        }
                    }
                }
            }

            // Also check for Spring Boot's specialized classloaders using reflection
            try {
                // Spring Boot RestartClassLoader and LaunchedURLClassLoader have getURLs()
                // method
                java.lang.reflect.Method getUrlsMethod = classLoader.getClass().getMethod("getURLs");
                Object result = getUrlsMethod.invoke(classLoader);
                if (result instanceof java.net.URL[] urls) {
                    for (java.net.URL url : urls) {
                        try {
                            String path = url.toURI().getPath();
                            if (path != null && !path.isBlank()) {
                                classpathEntries.add(path);
                            }
                        } catch (Exception e) {
                            logger.debug("Error converting classpath URL to path: {}", e.getMessage());
                        }
                    }
                }
            } catch (NoSuchMethodException e) {
                // Classloader doesn't have getURLs method, skip
            } catch (Exception e) {
                logger.debug("Error extracting URLs from classloader {}: {}",
                        classLoader.getClass().getName(), e.getMessage());
            }

            classLoader = classLoader.getParent();
        }

        // 3. Check for target/classes directories (important when running from
        // IDE/Maven)
        String userDir = System.getProperty("user.dir");
        if (userDir != null) {
            // Add common class output directories
            String[] possibleClassDirs = {
                    userDir + "/target/classes",
                    userDir + "/target/test-classes",
                    userDir + "/../kompile-app-core/target/classes",
                    userDir + "/../kompile-embedding-anserini/target/classes",
                    userDir + "/../kompile-vectorstore-anserini/target/classes",
                    userDir + "/../kompile-app-anserini/target/classes",
                    userDir + "/../kompile-model-manager/target/classes",
                    userDir + "/../kompile-loader-pdf-extended/target/classes",
                    userDir + "/../kompile-loader-microsoft/target/classes",
                    userDir + "/../kompile-app-loaders-orchestrator/target/classes"
            };

            for (String dir : possibleClassDirs) {
                Path dirPath = Path.of(dir).normalize();
                if (Files.exists(dirPath) && Files.isDirectory(dirPath)) {
                    classpathEntries.add(dirPath.toString());
                    logger.debug("Added target/classes directory to classpath: {}", dirPath);
                }
            }
        }

        // 4. Verify critical classes are accessible
        boolean hasParallelIngestPipeline = false;
        boolean hasPipelineResult = false;

        for (String entry : classpathEntries) {
            Path entryPath = Path.of(entry);
            if (Files.isDirectory(entryPath)) {
                Path pipelineDir = entryPath.resolve("ai/kompile/app/services/pipeline");
                if (Files.exists(pipelineDir)) {
                    if (Files.exists(pipelineDir.resolve("ParallelIngestPipeline$EmbeddedBatch.class"))) {
                        hasParallelIngestPipeline = true;
                    }
                    if (Files.exists(pipelineDir.resolve("PipelineResult.class"))) {
                        hasPipelineResult = true;
                    }
                }
            }
        }

        if (!hasParallelIngestPipeline || !hasPipelineResult) {
            logger.warn("Critical pipeline classes may be missing from classpath! " +
                    "ParallelIngestPipeline$EmbeddedBatch: {}, PipelineResult: {}",
                    hasParallelIngestPipeline, hasPipelineResult);
        }

        String result = String.join(pathSeparator, classpathEntries);
        logger.info("Built subprocess classpath with {} entries from classloader hierarchy", classpathEntries.size());

        return result;
    }

    /**
     * Create a subprocess handle. Its output readers are started by {@link #startMonitoring}.
     */
    private SubprocessHandle createHandle(String taskId, String fileName, Process process,
            CompletableFuture<SubprocessHandle.SubprocessResult> resultFuture,
            Path argsFile) {
        return new SubprocessHandle(taskId, fileName, process, null, null, resultFuture, argsFile);
    }

    /**
     * Start monitoring threads for a subprocess.
     *
     * @param timeoutMinutes how long the attempt may run before it is killed
     * @param stderrProtocol finds the protocol messages of a wrapped child that writes them to fd 1
     */
    private void startMonitoring(SubprocessHandle handle, int timeoutMinutes,
                                 SubprocessProtocolChannel.StderrProtocol stderrProtocol) {
        // Start stdout reader
        Thread stdoutReader = new Thread(() -> readStdout(handle), "subprocess-stdout-" + handle.getTaskId());
        stdoutReader.setDaemon(true);
        stdoutReader.start();

        // Start stderr reader
        Thread stderrReader = new Thread(() -> readStderr(handle, stderrProtocol),
                "subprocess-stderr-" + handle.getTaskId());
        stderrReader.setDaemon(true);
        stderrReader.start();

        // Start process completion watcher
        Thread completionWatcher = new Thread(
                () -> watchCompletion(handle, timeoutMinutes, stdoutReader, stderrReader),
                "subprocess-watcher-" + handle.getTaskId());
        completionWatcher.setDaemon(true);
        completionWatcher.start();
    }

    /**
     * Read and parse stdout from subprocess.
     * Protocol messages are parsed and handled; regular output is forwarded to
     * WebSocket as logs.
     */
    private void readStdout(SubprocessHandle handle) {
        Process process = getProcessForHandle(handle);
        if (process == null) {
            logger.debug("Process not found for task {}, cannot read stdout", handle.getTaskId());
            return;
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Write every non-null line to the central log store
                try {
                    SubprocessLogWriter slw = logWriters.get(handle.getTaskId());
                    if (slw != null) {
                        slw.writeLine(AgentLogRecord.Stream.STDOUT, line);
                    }
                } catch (Exception _logEx) {
                    logger.debug("[ingest-{}] stdout log write failed: {}", handle.getTaskId(), _logEx.getMessage());
                }
                // Check for protocol messages. A child started without the protocol channel shares this pipe
                // with native code, which writes to fd 1 beneath its System.setOut redirect, so a native
                // message without a newline can precede one on the same line.
                int prefixAt = line.indexOf(SubprocessMessage.MESSAGE_PREFIX);
                if (prefixAt > 0) {
                    forwardOutput(handle, line.substring(0, prefixAt));
                }
                if (prefixAt >= 0) {
                    String json = line.substring(prefixAt + SubprocessMessage.MESSAGE_PREFIX.length());
                    handleMessage(handle, json);
                } else {
                    forwardOutput(handle, line);
                }
            }
        } catch (IOException e) {
            if (!handle.isCancelled()) {
                logger.debug("Stdout reader terminated for task: {}", handle.getTaskId());
            }
        }
    }

    /** Stdout that is not a protocol message: regular log output, logged locally and forwarded to WebSocket. */
    private void forwardOutput(SubprocessHandle handle, String line) {
        if (line.isBlank()) {
            return;
        }
        logger.debug("[subprocess-{}] {}", handle.getTaskId(), line);

        // Forward to WebSocket for UI display
        if (progressTracker != null) {
            progressTracker.sendLog(handle.getTaskId(), "STDOUT", line);
        }
    }

    /**
     * Read stderr from subprocess.
     * All stderr output is forwarded to WebSocket for UI display. A wrapped child's fd 1 is this pipe
     * too, so it carries the native output that reaches fd 1, and the protocol messages of a child
     * that writes them there ({@link SubprocessProtocolChannel.StderrProtocol}).
     */
    private void readStderr(SubprocessHandle handle, SubprocessProtocolChannel.StderrProtocol stderrProtocol) {
        Process process = getProcessForHandle(handle);
        if (process == null) {
            logger.debug("Process not found for task {}, cannot read stderr", handle.getTaskId());
            return;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream()))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank())
                    continue;

                // A child that writes its protocol messages to fd 1 has them here, behind whatever else
                // reached fd 1
                int prefixAt = stderrProtocol.prefixIndex(line);
                if (prefixAt > 0) {
                    forwardStderr(handle, line.substring(0, prefixAt));
                }
                if (prefixAt >= 0) {
                    handleMessage(handle, line.substring(prefixAt + SubprocessMessage.MESSAGE_PREFIX.length()));
                } else {
                    forwardStderr(handle, line);
                }
                // Write to central log store
                try {
                    SubprocessLogWriter slw = logWriters.get(handle.getTaskId());
                    if (slw != null) {
                        slw.writeLine(AgentLogRecord.Stream.STDERR, line);
                    }
                } catch (Exception _logEx) {
                    logger.debug("[ingest-{}] stderr log write failed: {}", handle.getTaskId(), _logEx.getMessage());
                }
            }
        } catch (IOException e) {
            if (!handle.isCancelled()) {
                logger.debug("Stderr reader terminated for task: {}", handle.getTaskId());
            }
        }
    }

    /** Stderr that is not a protocol message: scanned for OOMs, logged locally and forwarded to WebSocket. */
    private void forwardStderr(SubprocessHandle handle, String line) {
        if (line.isBlank()) {
            return;
        }
        // Determine log level based on content
        String level = "INFO";

        // Check for GPU OOM patterns first (more specific)
        if (isGpuOomLine(line)) {
            logger.error("[subprocess-{}] GPU OOM detected: {}", handle.getTaskId(), line);
            handle.setGpuOomDetected(true);
            handle.setOomDetected(true); // Also set general OOM flag
            level = "ERROR";
        }
        // Check for Java heap OOM
        else if (line.contains("OutOfMemoryError") || line.contains("Java heap space")) {
            logger.error("[subprocess-{}] OOM detected: {}", handle.getTaskId(), line);
            handle.setOomDetected(true);
            level = "ERROR";
        } else if (line.contains("ERROR") || line.contains("Exception") || line.contains("FATAL")) {
            logger.info("[subprocess-{}] {}", handle.getTaskId(), line);
            level = "ERROR";
        } else if (line.contains("WARN")) {
            logger.info("[subprocess-{}] {}", handle.getTaskId(), line);
            level = "WARN";
        } else if (line.contains("EMBEDDING:") || line.contains("INDEXING:") ||
                line.contains("INFO") || line.contains("Starting") || line.contains("Complete")) {
            // Log important progress messages at INFO level
            logger.info("[subprocess-{}] {}", handle.getTaskId(), line);
            level = "INFO";
        } else if (line.contains("DEBUG") || line.contains("TRACE")) {
            logger.debug("[subprocess-{}] {}", handle.getTaskId(), line);
            level = "DEBUG";
        } else {
            // Default to INFO for general log lines
            logger.debug("[subprocess-{}] {}", handle.getTaskId(), line);
            level = "INFO";
        }

        // Forward ALL stderr to WebSocket for UI display (with detected level)
        if (progressTracker != null) {
            progressTracker.sendLog(handle.getTaskId(), "STDERR", level, line);
        }
    }

    /**
     * Check if a stderr line indicates GPU/CUDA OOM.
     */
    private boolean isGpuOomLine(String line) {
        String lower = line.toLowerCase();
        return lower.contains("cuda out of memory") ||
                lower.contains("cuda malloc failed") ||
                lower.contains("cublas_status_alloc_failed") ||
                lower.contains("out of memory") && (lower.contains("gpu") || lower.contains("cuda") || lower.contains("device")) ||
                lower.contains("nccl") && lower.contains("out of memory") ||
                lower.contains("could not allocate") && lower.contains("memory") && (lower.contains("gpu") || lower.contains("cuda"));
    }

    /**
     * Get process for a handle.
     */
    private Process getProcessForHandle(SubprocessHandle handle) {
        SubprocessHandle tracked = activeProcesses.get(handle.getTaskId());
        return tracked != null ? tracked.getProcess() : null;
    }

    /**
     * Watch for process completion. The exit is judged once the output readers have drained the child's
     * pipes: its FAILED report, or the OOM line the JVM prints right before exiting, is often still
     * unread when the process exits.
     *
     * @param timeoutMinutes how long the attempt may run before it is killed
     */
    private void watchCompletion(SubprocessHandle handle, int timeoutMinutes, Thread... outputReaders) {
        try {
            Process process = getProcessForHandle(handle);
            if (process == null)
                return;

            boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
            if (!finished) {
                logger.error("Subprocess {} timed out after {} minutes, force-killing it",
                        handle.getTaskId(), timeoutMinutes);
                // The timeout is the verdict, given before the kill so the kill is not taken for an OOM
                // and retried
                endAttemptBeforeKill(handle, "Timed out after " + timeoutMinutes + " minutes", "TIMEOUT",
                        IngestProgressUpdate.FailureReason.UNKNOWN, IndexingJobHistory.FailureReason.TIMEOUT);
                SubprocessSignals.kill(process);
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    logger.error("Subprocess {} (PID {}) is still running 30s after it was force-killed",
                            handle.getTaskId(), process.pid());
                }
            }
            int exitCode = process.isAlive() ? 137 : process.exitValue();
            logger.info("Subprocess {} exited with code: {}", handle.getTaskId(), exitCode);

            // Read the child's last output before judging its exit
            awaitOutputReaders(handle, outputReaders);

            // Handle completion
            handleCompletion(handle, exitCode);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.debug("Completion watcher interrupted for task: {}", handle.getTaskId());
        } finally {
            // Cleanup
            cleanup(handle);
        }
    }

    /**
     * Wait for the output readers to reach the end of the child's pipes. Bounded: a grandchild that
     * inherited the pipes keeps them open after the child exits.
     */
    private void awaitOutputReaders(SubprocessHandle handle, Thread... outputReaders) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(OUTPUT_DRAIN_TIMEOUT_MS);
        try {
            for (Thread reader : outputReaders) {
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remainingMs > 0) {
                    reader.join(remainingMs);
                }
                if (reader.isAlive()) {
                    logger.warn("[ingest-{}] {} still reading {} ms after the process exited; handling the exit",
                            handle.getTaskId(), reader.getName(), OUTPUT_DRAIN_TIMEOUT_MS);
                }
            }
        } catch (InterruptedException e) {
            // The process has exited, so its exit is still handled
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Give the job's verdict for an attempt the launcher is about to kill, so the kill's exit code is
     * not judged in its place — a SIGKILL reads as an OOM and would be retried. Reported only as the
     * job's first verdict.
     */
    private void endAttemptBeforeKill(SubprocessHandle handle, String message, String logState,
                                      IngestProgressUpdate.FailureReason progressReason,
                                      IndexingJobHistory.FailureReason historyReason) {
        String taskId = handle.getTaskId();
        if (!handle.getResultFuture().complete(SubprocessHandle.SubprocessResult.failure(
                taskId, -1, message, handle.getCurrentPhase(), false, false))) {
            return;
        }
        closeSubprocessLog(taskId, logState, null, message, handle.isOomDetected(), handle.isGpuOomDetected());
        taskWorkerStatuses.remove(taskId);
        try {
            if (progressTracker != null) {
                progressTracker.failTask(taskId, handle.getFileName(), toProgressPhase(handle.getCurrentPhase()),
                        message, progressReason);
            }
            if (jobHistoryService != null) {
                jobHistoryService.markJobFailed(taskId, toEventPhase(handle.getCurrentPhase()), message, null,
                        historyReason);
            }
        } catch (Exception e) {
            logger.warn("Failed to report the verdict for task {}: {}", taskId, e.getMessage());
        }
    }

    /**
     * Handle a parsed message from subprocess.
     */
    private void handleMessage(SubprocessHandle handle, String json) {
        try {
            SubprocessMessage message = objectMapper.readValue(json, SubprocessMessage.class);
            logger.debug("Received subprocess message: type={}, taskId={}",
                    message.getClass().getSimpleName(), handle.getTaskId());

            SubprocessMessage.dispatch(message, new SubprocessMessage.Handler() {
                @Override
                public void onProgress(SubprocessMessage.Progress progress) {
                    handle.updateProgress(progress.phase(), progress.progressPercent(), progress.message());
                    forwardProgress(handle, progress);
                }

                @Override
                public void onPhaseTransition(SubprocessMessage.PhaseTransition transition) {
                    handle.setCurrentPhase(transition.toPhase());
                    handle.updateHeartbeat();
                    logger.info("Task {} phase transition: {} -> {}",
                            handle.getTaskId(), transition.fromPhase(), transition.toPhase());
                    // Forward phase transition to UI
                    forwardPhaseTransition(handle, transition);
                    // Broadcast phase transition with duration to WebSocket
                    if (heartbeatBroadcaster != null) {
                        heartbeatBroadcaster.broadcastPhaseTransition(handle.getTaskId(), "ingest",
                                transition.fromPhase(), transition.toPhase(), transition.phaseDurationMs());
                    }
                    // Forward phase transition to scheduler for GPU yield/reacquire (keyed by the
                    // job, so a retry attempt's phases reach the scheduler's job too)
                    if (resourceScheduler != null) {
                        var profile = JobResourceProfiles.INGEST;
                        boolean requiresGpu = profile.phaseRequiresGpu(transition.toPhase());
                        long gpuMem = profile.gpuMemoryForPhase(transition.toPhase());
                        String jobId = taskToJobId.getOrDefault(handle.getTaskId(), handle.getTaskId());
                        resourceScheduler.reportPhaseTransition(jobId, transition.toPhase(), requiresGpu, gpuMem);
                    }
                }

                @Override
                public void onHeartbeat(SubprocessMessage.Heartbeat heartbeat) {
                    handle.updateMemoryFromHeartbeat(heartbeat);
                    logger.debug("Task {} heartbeat: uptime={}ms, heap={}%, offHeap={}%, gpu={}%",
                            handle.getTaskId(), heartbeat.uptimeMs(),
                            String.format("%.1f", heartbeat.memoryUsagePercent()),
                            String.format("%.1f", heartbeat.offHeapUsagePercent()),
                            String.format("%.1f", heartbeat.gpuUsagePercent()));
                    // Broadcast heartbeat memory data to WebSocket
                    if (heartbeatBroadcaster != null) {
                        heartbeatBroadcaster.broadcastHeartbeat(handle.getTaskId(), "ingest", heartbeat);
                    }
                }

                @Override
                public void onCompleted(SubprocessMessage.Completed completed) {
                    logger.info("Task {} completed: {} docs, {} chunks indexed",
                            handle.getTaskId(), completed.documentsLoaded(), completed.documentsIndexed());
                    if (!handle.getResultFuture().complete(SubprocessHandle.SubprocessResult.success(
                            handle.getTaskId(), completed))) {
                        logger.warn("Task {} reported completion after its job had already ended; not reporting it again",
                                handle.getTaskId());
                        return;
                    }
                    // Forward completion to UI
                    forwardCompletion(handle, completed);
                    taskWorkerStatuses.remove(handle.getTaskId());
                }

                @Override
                public void onFailed(SubprocessMessage.Failed failed) {
                    logger.error("Task {} failed in phase {}: {}",
                            handle.getTaskId(), failed.phase(), failed.errorMessage());

                    // Check if this is an OOM failure - if so, DON'T complete the future yet
                    // Let handleCompletion() trigger the adaptive retry when the process actually exits
                    boolean isOom = isOutOfMemoryError(failed.errorMessage(), failed.errorType());
                    if (isOom) {
                        logger.info("OOM failure detected for task {} - deferring to handleCompletion for retry logic",
                                handle.getTaskId());
                        handle.setOomDetected(true);
                        // Store the failure info on the handle for later use
                        handle.setCurrentPhase(failed.phase());
                        handle.setReportedError(failed.errorMessage());
                        // DON'T complete the future - let handleCompletion do it after retry attempt
                    } else if (handle.isCancelled()) {
                        // A child being stopped may report the stop as a failure; its exit ends the job as cancelled
                        logger.info("Task {} reported a failure while being cancelled; its exit ends the job",
                                handle.getTaskId());
                    } else {
                        // Non-OOM failure - complete immediately, reported only as the job's first verdict
                        if (!handle.getResultFuture().complete(SubprocessHandle.SubprocessResult.failure(
                                handle.getTaskId(), 1, failed.errorMessage(), failed.phase(), false, false))) {
                            logger.warn("Task {} reported a failure after its job had already ended; not reporting it again",
                                    handle.getTaskId());
                            return;
                        }
                        // Forward failure to UI
                        forwardFailure(handle, failed);
                        taskWorkerStatuses.remove(handle.getTaskId());
                    }
                }

                @Override
                public void onWorkerStatus(SubprocessMessage.WorkerStatus workerStatus) {
                    // Store worker status for inclusion in progress updates
                    taskWorkerStatuses
                            .computeIfAbsent(handle.getTaskId(), k -> new ConcurrentHashMap<>())
                            .put(workerStatus.workerId(), workerStatus);
                    // Worker status updates for detailed monitoring
                    logger.debug("Task {} worker {}: {} - {} items",
                            handle.getTaskId(), workerStatus.workerId(),
                            workerStatus.status(), workerStatus.itemsProcessed());
                }

                @Override
                public void onLog(SubprocessMessage.Log logMsg) {
                    // Forward log messages to WebSocket for real-time display
                    handle.updateHeartbeat(); // Log activity counts as liveness signal
                    forwardLogMessage(handle, logMsg);
                }
            });
        } catch (Exception e) {
            logger.warn("Failed to parse subprocess message: {}", json, e);
        }
    }

    /**
     * Forward progress to IngestProgressTracker for WebSocket broadcast.
     */
    private void forwardProgress(SubprocessHandle handle, SubprocessMessage.Progress progress) {
        if (progressTracker == null) {
            // Only log warning once per task to avoid log spam
            if (warnedTaskIds.add(handle.getTaskId())) {
                logger.warn(
                        "Cannot forward progress for task {}: IngestProgressTracker is not available - UI will not receive updates",
                        handle.getTaskId());
            }
            return;
        }

        try {
            IngestProgressUpdate.IngestPhase phase = toProgressPhase(progress.phase());

            // Convert subprocess stats to IngestStats for UI display
            // Pass the phase so we can populate activeStage correctly
            IngestProgressUpdate.IngestStats stats = convertProgressStats(handle.getTaskId(), progress.stats(),
                    progress.currentStep(), progress.phase());

            progressTracker.updateProgress(
                    handle.getTaskId(),
                    handle.getFileName(),
                    phase,
                    progress.progressPercent(),
                    progress.currentStep(),
                    progress.message(),
                    stats);
            // Use info level for significant progress milestones, debug for frequent
            // updates
            if (progress.progressPercent() % 10 == 0 || progress.progressPercent() >= 95) {
                logger.info("Forwarded progress to UI: task={}, phase={}, percent={}%",
                        handle.getTaskId(), progress.phase(), progress.progressPercent());
            } else {
                logger.debug("Forwarded progress: phase={}, percent={}", progress.phase(), progress.progressPercent());
            }
        } catch (Exception e) {
            logger.warn("Failed to forward progress for task {}: {}", handle.getTaskId(), e.getMessage(), e);
        }
    }

    /**
     * Convert subprocess ProgressStats to IngestProgressUpdate.IngestStats.
     * This enables the UI to display progress details for subprocess mode.
     *
     * IMPORTANT: The subprocess uses `documentsIndexed` to track indexed CHUNKS
     * (since it indexes chunks, not whole documents). The frontend expects
     * `chunksIndexed` for the indexing progress bar, so we map accordingly.
     *
     * This method aims to provide the same granularity as the parallel in-process
     * pipeline.
     */
    private IngestProgressUpdate.IngestStats convertProgressStats(String taskId,
            SubprocessMessage.ProgressStats subStats,
            String currentStep, String phase) {
        // Parse batch info from currentStep (e.g., "Embedding batch 3/66")
        int[] batchInfo = parseBatchNumbers(currentStep);
        Integer currentBatch = batchInfo[0] > 0 ? batchInfo[0] : null;
        Integer totalBatches = batchInfo[1] > 0 ? batchInfo[1] : null;

        // Convert worker statuses to DTOs
        // Prefer embedded workerStatuses from ProgressStats (new approach) over
        // separate WorkerStatus messages
        List<IngestProgressUpdate.WorkerStatusDto> workerDtos;
        if (subStats != null && subStats.workerStatuses() != null && !subStats.workerStatuses().isEmpty()) {
            // Use embedded worker statuses from ProgressStats (parity with parallel
            // pipeline)
            workerDtos = subStats.workerStatuses().stream()
                    .map(this::convertWorkerStatusSnapshot)
                    .toList();
        } else {
            // Fall back to separately-tracked worker statuses (for backward compatibility)
            workerDtos = taskWorkerStatuses
                    .getOrDefault(taskId, Map.of())
                    .values().stream()
                    .map(this::convertWorkerStatus)
                    .toList();
        }

        // Build embedding batch metrics with enhanced details
        IngestProgressUpdate.EmbeddingBatchMetrics batchMetrics = buildEnhancedBatchMetrics(
                currentStep, currentBatch, totalBatches, subStats);

        if (subStats == null) {
            // Create minimal stats with just the current step info parsed from the message
            // Try to parse indexing progress from the currentStep string
            // Format: "Indexed X/Y chunks (Z/sec)"
            Integer chunksIndexed = parseIndexedCountFromStep(currentStep);
            Integer totalChunks = parseTotalChunksFromStep(currentStep);

            // Create minimal subprocess runtime info so UI knows this is subprocess mode
            // Uses same pattern as SubprocessRuntimeInfo.empty() but with processMode =
            // "SUBPROCESS"
            IngestProgressUpdate.SubprocessRuntimeInfo minimalRuntimeInfo = new IngestProgressUpdate.SubprocessRuntimeInfo(
                    null, null, "SUBPROCESS", // processMode = SUBPROCESS
                    null, null, null, null, null,
                    null, null, null, null, null,
                    null, null,
                    null, null, null,
                    null, List.of(), List.of(),
                    null, null, null, null,
                    null, null,
                    null, null, null, null,
                    null, null, null);

            return IngestProgressUpdate.IngestStats.builder()
                    .activeStage(phase != null ? phase : "EMBEDDING")
                    .pipelineStatus("PROCESSING")
                    .currentBatch(currentBatch)
                    .totalBatches(totalBatches)
                    .workerStatuses(workerDtos)
                    .currentEmbeddingBatch(batchMetrics)
                    // Set chunksIndexed if we parsed it from the status message
                    .chunksCreated(totalChunks)
                    .chunksIndexed(chunksIndexed)
                    .documentsIndexed(chunksIndexed)
                    // Include subprocess runtime info so UI shows SUBPROCESS mode
                    .subprocessRuntimeInfo(minimalRuntimeInfo)
                    .build();
        }

        // Build queue status from subprocess stats
        // Subprocess provides chunkQueueSize and embeddingQueueSize
        int queueCapacity = 1000; // Default capacity for display
        IngestProgressUpdate.QueueStatusDto queueStatus = new IngestProgressUpdate.QueueStatusDto(
                subStats.chunkQueueSize(),
                queueCapacity,
                subStats.embeddingQueueSize(),
                queueCapacity,
                queueCapacity > 0 ? (subStats.chunkQueueSize() * 100.0 / queueCapacity) : 0,
                queueCapacity > 0 ? (subStats.embeddingQueueSize() * 100.0 / queueCapacity) : 0);

        // Map documentsIndexed to chunksIndexed since subprocess indexes chunks
        // The subprocess uses documentsIndexed field to track indexed chunks count
        Integer chunksIndexed = subStats.documentsIndexed();

        // Convert runtime info if present
        IngestProgressUpdate.SubprocessRuntimeInfo subprocessRuntimeInfo = convertRuntimeInfo(subStats.runtimeInfo());

        // Calculate throughput metrics
        double inferenceRate = 0.0;
        if (subStats.embeddingDurationMs() > 0 && subStats.chunksEmbedded() > 0) {
            inferenceRate = subStats.chunksEmbedded() * 1000.0 / subStats.embeddingDurationMs();
        }

        return IngestProgressUpdate.IngestStats.builder()
                .documentsLoaded(subStats.documentsLoaded())
                .chunksCreated(subStats.chunksCreated())
                .chunksEmbedded(subStats.chunksEmbedded())
                .chunksIndexed(chunksIndexed) // Used by frontend progress bar
                .documentsIndexed(chunksIndexed) // Legacy field, same value
                .totalProcessingTimeMs(subStats.totalProcessingTimeMs())
                // Timing breakdown - same fields as parallel mode
                .loadingTimeMs(subStats.loadingDurationMs() > 0 ? subStats.loadingDurationMs() : null)
                .chunkingTimeMs(subStats.chunkingDurationMs() > 0 ? subStats.chunkingDurationMs() : null)
                .embeddingTimeMs(subStats.embeddingDurationMs() > 0 ? subStats.embeddingDurationMs() : null)
                .indexingTimeMs(subStats.indexingDurationMs() > 0 ? subStats.indexingDurationMs() : null)
                // Batch info
                .currentBatch(currentBatch)
                .totalBatches(totalBatches)
                .batchSize(subStats.batchSize())
                // Configuration
                .loaderUsed(subStats.loaderUsed())
                .chunkerUsed(subStats.chunkerUsed())
                .workerThreads(subStats.workerThreads())
                .parallelProcessing(subStats.parallelProcessing())
                // Throughput metrics - same as parallel mode
                .chunksPerSecond(subStats.chunksPerSecond())
                .docsPerSecond(subStats.docsPerSecond())
                .inferenceRate(inferenceRate)
                // Memory info
                .memoryUsagePercent(subStats.memoryUsagePercent())
                .memoryStatus(subStats.memoryStatus())
                // Pipeline status
                .activeStage(subStats.activeStage())
                .pipelineStatus(subStats.pipelineStatus())
                // Per-worker status
                .workerStatuses(workerDtos)
                // Queue status
                .queueStatus(queueStatus)
                .chunkingQueueSize(subStats.chunkQueueSize())
                .embeddingQueueDepth(subStats.embeddingQueueSize())
                // Embedding batch metrics - detailed like parallel mode
                .currentEmbeddingBatch(batchMetrics)
                // Batch history - last N completed batches for UI visibility
                .batchHistory(convertBatchHistory(subStats.batchHistory()))
                // Subprocess-specific runtime info
                .subprocessRuntimeInfo(subprocessRuntimeInfo)
                .build();
    }

    /**
     * Convert batch history from subprocess format to UI DTO format.
     */
    private List<IngestProgressUpdate.BatchHistoryEntry> convertBatchHistory(
            List<SubprocessMessage.BatchHistoryEntry> subHistory) {
        if (subHistory == null || subHistory.isEmpty()) {
            return null;
        }
        return subHistory.stream()
                .map(h -> new IngestProgressUpdate.BatchHistoryEntry(
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

    private IngestProgressUpdate.WorkerStatusDto convertWorkerStatus(SubprocessMessage.WorkerStatus ws) {
        int workerId = parseWorkerId(ws.workerId());
        return new IngestProgressUpdate.WorkerStatusDto(
                workerId,
                ws.workerType() != null ? ws.workerType().toLowerCase() : null,
                ws.status() != null ? ws.status().toLowerCase() : null,
                ws.itemsProcessed(),
                ws.currentBatchSize(),
                ws.throughput(),
                ws.currentItem());
    }

    /**
     * Convert the new embedded WorkerStatusSnapshot (from
     * ProgressStats.workerStatuses) to UI DTO.
     * This provides parity with the parallel pipeline's worker status reporting.
     */
    private IngestProgressUpdate.WorkerStatusDto convertWorkerStatusSnapshot(
            SubprocessMessage.WorkerStatusSnapshot ws) {
        return new IngestProgressUpdate.WorkerStatusDto(
                ws.workerId(),
                ws.workerType() != null ? ws.workerType().toLowerCase() : null,
                ws.status() != null ? ws.status().toLowerCase() : null,
                ws.itemsProcessed(),
                ws.currentBatchSize(),
                ws.throughput(),
                ws.currentItem());
    }

    private int parseWorkerId(String workerId) {
        if (workerId == null) {
            return -1;
        }
        try {
            return Integer.parseInt(workerId);
        } catch (NumberFormatException ignore) {
            // Try extracting a numeric suffix (e.g., "embedding-0")
            Matcher matcher = Pattern.compile("(\\d+)$").matcher(workerId);
            if (matcher.find()) {
                try {
                    return Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException ignored) {
                    // Fall through
                }
            }
        }
        // Fallback to stable, non-negative identifier
        return workerId.hashCode() & 0x7fffffff;
    }

    /**
     * Parse batch numbers from currentStep string.
     * 
     * @return int array [currentBatch, totalBatches] or [0, 0] if not found
     */
    private int[] parseBatchNumbers(String currentStep) {
        if (currentStep == null) {
            return new int[] { 0, 0 };
        }
        Pattern batchPattern = Pattern.compile(
                "batch\\s+(\\d+)/(\\d+)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = batchPattern.matcher(currentStep);
        if (matcher.find()) {
            try {
                return new int[] {
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2))
                };
            } catch (NumberFormatException e) {
                logger.debug("Error parsing batch numbers from step string '{}': {}", currentStep, e.getMessage());
            }
        }
        return new int[] { 0, 0 };
    }

    /**
     * Parse total chunks from step like "Indexed 50/200 chunks"
     */
    private Integer parseTotalChunksFromStep(String currentStep) {
        if (currentStep == null)
            return null;
        Pattern pattern = Pattern.compile(
                "(?:Indexed|Embedded)\\s+\\d+/(\\d+)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(currentStep);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                // Ignore
            }
        }
        return null;
    }

    /**
     * Build enhanced embedding batch metrics with all fields the UI expects.
     */
    private IngestProgressUpdate.EmbeddingBatchMetrics buildEnhancedBatchMetrics(
            String currentStep, Integer currentBatch, Integer totalBatches,
            SubprocessMessage.ProgressStats subStats) {

        if (currentBatch == null && currentStep == null) {
            return null;
        }

        // Prefer batch numbers from subprocess stats (fixed values) over parsed values
        Integer effectiveBatch = (subStats != null && subStats.currentBatchNumber() != null && subStats.currentBatchNumber() > 0)
                ? subStats.currentBatchNumber()
                : currentBatch;
        Integer effectiveTotal = (subStats != null && subStats.totalBatches() != null && subStats.totalBatches() > 0)
                ? subStats.totalBatches()
                : totalBatches;

        IngestProgressUpdate.EmbeddingBatchMetrics.Builder builder = IngestProgressUpdate.EmbeddingBatchMetrics
                .builder()
                .batchNumber(effectiveBatch)
                .totalBatches(effectiveTotal)
                .currentStep(currentStep);

        // Parse throughput from step string like "Embedded 50/200 chunks (12.5/sec)"
        if (currentStep != null) {
            Pattern ratePattern = Pattern.compile(
                    "\\((\\d+\\.?\\d*)/sec\\)", Pattern.CASE_INSENSITIVE);
            Matcher rateMatcher = ratePattern.matcher(currentStep);
            if (rateMatcher.find()) {
                try {
                    double rate = Double.parseDouble(rateMatcher.group(1));
                    builder.batchThroughput(rate);
                    builder.embeddingsPerSecond(rate);
                } catch (NumberFormatException e) {
                    // Ignore
                }
            }

            // Determine status level based on batch progress
            if (effectiveBatch != null && effectiveTotal != null && effectiveTotal > 0) {
                double progress = (double) effectiveBatch / effectiveTotal;
                if (progress >= 0.9) {
                    builder.statusLevel("COMPLETING");
                } else if (progress >= 0.5) {
                    builder.statusLevel("PROCESSING");
                } else {
                    builder.statusLevel("RUNNING");
                }

                // Calculate ETA message
                if (subStats != null && subStats.chunksPerSecond() > 0) {
                    int remaining = effectiveTotal - effectiveBatch;
                    int batchSize = subStats.batchSize() > 0 ? subStats.batchSize() : 8;
                    int remainingChunks = remaining * batchSize;
                    double etaSeconds = remainingChunks / subStats.chunksPerSecond();
                    if (etaSeconds < 60) {
                        builder.etaMessage(String.format("~%.0fs remaining", etaSeconds));
                    } else {
                        builder.etaMessage(String.format("~%.1fm remaining", etaSeconds / 60));
                    }
                }
            }
        }

        // ========== Use actual tensor shapes from subprocess when available ==========
        // These come directly from the SameDiff encoder during inference
        if (subStats != null) {
            // Use actual tensor shapes from encoder if provided
            if (subStats.actualInputShape() != null) {
                builder.actualInputShape(subStats.actualInputShape());
            }
            if (subStats.actualOutputShape() != null) {
                builder.actualOutputShape(subStats.actualOutputShape());
            }

            // Use actual batch metrics from subprocess
            if (subStats.inputTexts() != null && subStats.inputTexts() > 0) {
                builder.inputTexts(subStats.inputTexts());
            } else if (subStats.batchSize() > 0) {
                builder.inputTexts(subStats.batchSize());
            }

            if (subStats.maxSequenceLength() != null && subStats.maxSequenceLength() > 0) {
                builder.maxSequenceLength(subStats.maxSequenceLength());
            }

            if (subStats.embeddingDimension() != null && subStats.embeddingDimension() > 0) {
                builder.embeddingDimension(subStats.embeddingDimension());
            }

            // Detailed timing from encoder
            if (subStats.tokenizationTimeMs() != null && subStats.tokenizationTimeMs() > 0) {
                builder.tokenizationTimeMs(subStats.tokenizationTimeMs());
            }
            if (subStats.paddingTimeMs() != null && subStats.paddingTimeMs() > 0) {
                builder.paddingTimeMs(subStats.paddingTimeMs());
            }
            if (subStats.tensorCreationTimeMs() != null && subStats.tensorCreationTimeMs() > 0) {
                builder.tensorCreationTimeMs(subStats.tensorCreationTimeMs());
            }
            if (subStats.forwardPassTimeMs() != null && subStats.forwardPassTimeMs() > 0) {
                builder.forwardPassTimeMs(subStats.forwardPassTimeMs());
            }
            if (subStats.extractionTimeMs() != null && subStats.extractionTimeMs() > 0) {
                builder.extractionTimeMs(subStats.extractionTimeMs());
            }

            // Override currentStep if provided in stats
            if (subStats.currentStep() != null) {
                builder.currentStep(subStats.currentStep());
            }

            builder.isBatched(true);

            // If we have runtime info with embedding model details, use them
            if (subStats.runtimeInfo() != null) {
                SubprocessMessage.RuntimeInfo ri = subStats.runtimeInfo();
                if (ri.embeddingModelId() != null) {
                    builder.modelName(ri.embeddingModelId());
                }
                if (ri.embeddingDimension() != null) {
                    builder.embeddingDimension(ri.embeddingDimension());
                }
                builder.deviceType(ri.nd4jBackend() != null ? ri.nd4jBackend() : "CPU");
            }

            // Fallback: Build tensor shape strings from metrics if actual shapes not available
            if (subStats.actualInputShape() == null) {
                int batchSize = subStats.inputTexts() != null ? subStats.inputTexts() :
                               (subStats.batchSize() > 0 ? subStats.batchSize() : 32);
                int maxSeqLen = subStats.maxSequenceLength() != null ? subStats.maxSequenceLength() : 512;
                builder.inputTensorShape("[" + batchSize + " x " + maxSeqLen + "]");
            }
            if (subStats.actualOutputShape() == null) {
                int batchSize = subStats.inputTexts() != null ? subStats.inputTexts() :
                               (subStats.batchSize() > 0 ? subStats.batchSize() : 32);
                int embDim = subStats.embeddingDimension() != null ? subStats.embeddingDimension() : 768;
                builder.outputTensorShape("[" + batchSize + " x " + embDim + "]");
            }
        } else {
            // No stats available - use defaults but still provide tensor shapes
            int batchSize = 32;
            int maxSeqLen = 512;
            int embDim = 768;
            builder.isBatched(true);
            builder.inputTexts(batchSize);
            builder.maxSequenceLength(maxSeqLen);
            builder.embeddingDimension(embDim);
            builder.deviceType("CPU");
            builder.inputTensorShape("[" + batchSize + " x " + maxSeqLen + "]");
            builder.outputTensorShape("[" + batchSize + " x " + embDim + "]");
        }

        return builder.build();
    }

    /**
     * Convert subprocess RuntimeInfo to IngestProgressUpdate.SubprocessRuntimeInfo.
     */
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

    /**
     * Parse indexed count from status message like "Indexed 50/200 chunks
     * (12.5/sec)"
     */
    private Integer parseIndexedCountFromStep(String currentStep) {
        if (currentStep == null) {
            return null;
        }
        Pattern pattern = Pattern.compile("Indexed\\s+(\\d+)/(\\d+)",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(currentStep);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                // Ignore
            }
        }
        return null;
    }

    /**
     * Forward phase transition to IngestProgressTracker for WebSocket broadcast.
     */
    private void forwardPhaseTransition(SubprocessHandle handle, SubprocessMessage.PhaseTransition transition) {
        if (progressTracker == null) {
            logger.debug("Cannot forward phase transition: progressTracker is null");
            return;
        }

        try {
            IngestProgressUpdate.IngestPhase phase = toProgressPhase(transition.toPhase());

            // Send a progress update with the new phase at 0%
            progressTracker.updateProgress(
                    handle.getTaskId(),
                    handle.getFileName(),
                    phase,
                    0, // Starting new phase
                    "Starting " + phase.name().toLowerCase(),
                    "Phase transition: " + (transition.fromPhase() != null ? transition.fromPhase() : "start") + " -> "
                            + transition.toPhase(),
                    null);
            logger.info("Forwarded phase transition to UI: {} -> {} for task {}",
                    transition.fromPhase(), transition.toPhase(), handle.getTaskId());
        } catch (Exception e) {
            logger.warn("Failed to forward phase transition for task {}: {}", handle.getTaskId(), e.getMessage(), e);
        }
    }

    /**
     * Notify MonitorService that a task finished so any chat monitors watching
     * the task can fire wake-up events. Called from the resultFuture completion
     * hook — must swallow all errors so completion is never blocked.
     */
    private void notifyMonitorService(String taskId, SubprocessHandle.SubprocessResult result, Throwable error) {
        if (taskCompletionListener == null) return;
        try {
            boolean success = error == null && result != null && result.success();
            String summary;
            if (error != null) {
                summary = "Task " + taskId + " ended with error: " + error.getMessage();
            } else if (result == null) {
                summary = "Task " + taskId + " completed";
            } else if (result.success()) {
                summary = String.format("Task %s finished: %d documents, %d chunks indexed in %dms",
                        taskId, result.documentsLoaded(), result.documentsIndexed(), result.totalDurationMs());
            } else {
                summary = "Task " + taskId + " failed: " +
                        (result.errorMessage() != null ? result.errorMessage() : "unknown error");
            }
            taskCompletionListener.onTaskCompleted(taskId, success, summary);
        } catch (Exception e) {
            logger.warn("Failed to notify monitor service for task {}: {}", taskId, e.getMessage());
        }
    }

    /**
     * Forward completion to IngestProgressTracker for WebSocket broadcast.
     * Also creates a Fact in the active sheet for the successfully processed file.
     */
    private void forwardCompletion(SubprocessHandle handle, SubprocessMessage.Completed completed) {
        String taskId = handle.getTaskId();

        try {
            // Build IngestStats from completed message using builder pattern
            Map<String, Long> durations = completed.phaseDurations();
            IngestProgressUpdate.IngestStats stats = IngestProgressUpdate.IngestStats.builder()
                    .documentsLoaded(completed.documentsLoaded())
                    .chunksCreated(completed.chunksCreated())
                    .chunksEmbedded(completed.chunksEmbedded())
                    .chunksIndexed(completed.documentsIndexed()) // documentsIndexed is actually chunks indexed
                    .documentsIndexed(completed.documentsIndexed())
                    .totalProcessingTimeMs(completed.totalDurationMs())
                    .loadingTimeMs(durations != null ? durations.get("LOADING") : null)
                    .chunkingTimeMs(durations != null ? durations.get("CHUNKING") : null)
                    .embeddingTimeMs(durations != null ? durations.get("EMBEDDING") : null)
                    .indexingTimeMs(durations != null ? durations.get("INDEXING") : null)
                    .build();

            // Forward to UI via progress tracker
            if (progressTracker != null) {
                progressTracker.completeTask(
                        taskId,
                        handle.getFileName(),
                        stats);
                logger.info("Forwarded completion to UI: task {} - {} docs, {} chunks indexed",
                        taskId, completed.documentsLoaded(), completed.documentsIndexed());
            }

            // Update job history with final stats and mark as completed
            if (jobHistoryService != null) {
                jobHistoryService.updateJobStats(taskId,
                        completed.documentsLoaded(),
                        completed.chunksCreated(),
                        completed.chunksEmbedded(),
                        completed.documentsIndexed());
                jobHistoryService.markJobCompleted(taskId);
                logger.debug("Updated job history for task {}: {} docs loaded, {} chunks created, {} embedded, {} indexed",
                        taskId, completed.documentsLoaded(), completed.chunksCreated(),
                        completed.chunksEmbedded(), completed.documentsIndexed());
            }

            // Create a Fact entry for the processed file in the active sheet
            createFactForCompletedJob(taskId, handle.getFileName());

        } catch (Exception e) {
            logger.warn("Failed to forward completion for task {}: {}", taskId, e.getMessage(), e);
        } finally {
            // Clean up file path tracking
            taskFilePaths.remove(taskId);
        }
    }

    /**
     * Create a Fact entry for a successfully processed file.
     */
    private void createFactForCompletedJob(String taskId, String fileName) {
        if (factSheetService == null) {
            logger.debug("Cannot create fact: factSheetService is null");
            return;
        }

        Path filePath = taskFilePaths.get(taskId);
        if (filePath == null) {
            logger.warn("Cannot create fact for task {}: file path not found", taskId);
            return;
        }

        try {
            // Gather file metadata
            String extension = getFileExtension(fileName);
            Long sizeBytes = null;
            String mimeType = null;
            String checksum = null;

            if (Files.exists(filePath)) {
                try {
                    sizeBytes = Files.size(filePath);
                    mimeType = Files.probeContentType(filePath);
                } catch (IOException e) {
                    logger.debug("Could not read file metadata for {}: {}", filePath, e.getMessage());
                }
            }

            // Determine view mode based on extension
            ai.kompile.app.facts.domain.Fact.ViewMode viewMode = determineViewMode(extension, mimeType);
            boolean canPreview = viewMode == ai.kompile.app.facts.domain.Fact.ViewMode.TEXT ||
                    viewMode == ai.kompile.app.facts.domain.Fact.ViewMode.IMAGE ||
                    viewMode == ai.kompile.app.facts.domain.Fact.ViewMode.EMBEDDED;

            // Create the fact
            ai.kompile.app.facts.domain.Fact fact = factSheetService.addFactToActiveSheet(
                    fileName,
                    filePath.toString(),
                    checksum,
                    ai.kompile.app.facts.domain.Fact.SourceType.UPLOAD,
                    extension,
                    mimeType,
                    sizeBytes,
                    viewMode,
                    canPreview,
                    null // sourceUrl
            );

            // Mark as indexed since we just finished indexing it
            if (fact != null) {
                factSheetService.markFactAsIndexed(fact.getId());
                logger.info("Created and indexed fact for task {}: factId={}, fileName={}",
                        taskId, fact.getId(), fileName);
            }

        } catch (Exception e) {
            logger.error("Failed to create fact for task {}: {}", taskId, e.getMessage(), e);
        }
    }

    /**
     * Extract file extension from filename.
     */
    private String getFileExtension(String fileName) {
        if (fileName == null)
            return null;
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0 && lastDot < fileName.length() - 1) {
            return fileName.substring(lastDot + 1).toLowerCase();
        }
        return null;
    }

    /**
     * Determine the appropriate view mode based on file extension and mime type.
     */
    private ai.kompile.app.facts.domain.Fact.ViewMode determineViewMode(String extension, String mimeType) {
        if (extension == null && mimeType == null) {
            return ai.kompile.app.facts.domain.Fact.ViewMode.DOWNLOAD_ONLY;
        }

        // Check by extension first
        if (extension != null) {
            switch (extension.toLowerCase()) {
                case "txt", "md", "json", "xml", "html", "css", "js", "java", "py", "c", "cpp", "h", "yaml", "yml",
                        "log":
                    return ai.kompile.app.facts.domain.Fact.ViewMode.TEXT;
                case "png", "jpg", "jpeg", "gif", "bmp", "svg", "webp":
                    return ai.kompile.app.facts.domain.Fact.ViewMode.IMAGE;
                case "pdf":
                    return ai.kompile.app.facts.domain.Fact.ViewMode.EMBEDDED;
            }
        }

        // Fall back to mime type
        if (mimeType != null) {
            if (mimeType.startsWith("text/")) {
                return ai.kompile.app.facts.domain.Fact.ViewMode.TEXT;
            }
            if (mimeType.startsWith("image/")) {
                return ai.kompile.app.facts.domain.Fact.ViewMode.IMAGE;
            }
            if (mimeType.equals("application/pdf")) {
                return ai.kompile.app.facts.domain.Fact.ViewMode.EMBEDDED;
            }
        }

        return ai.kompile.app.facts.domain.Fact.ViewMode.DOWNLOAD_ONLY;
    }

    /**
     * Forward failure to IngestProgressTracker for WebSocket broadcast.
     */
    private void forwardFailure(SubprocessHandle handle, SubprocessMessage.Failed failed) {
        String taskId = handle.getTaskId();

        try {
            // Detect OOM from error message or error type
            boolean isOom = isOutOfMemoryError(failed.errorMessage(), failed.errorType());
            if (isOom) {
                handle.setOomDetected(true);
            }

            IngestProgressUpdate.IngestPhase phase = toProgressPhase(failed.phase());

            // Update job history with failure
            if (jobHistoryService != null) {
                ai.kompile.app.ingest.domain.IndexingJobHistory.FailureReason reason = isOom
                        ? ai.kompile.app.ingest.domain.IndexingJobHistory.FailureReason.OUT_OF_MEMORY
                        : ai.kompile.app.ingest.domain.IndexingJobHistory.FailureReason.UNKNOWN;
                jobHistoryService.markJobFailed(taskId, toEventPhase(failed.phase()),
                        failed.errorMessage(), null, reason);
                logger.debug("Updated job history for failed task {}: phase={}, reason={}",
                        taskId, failed.phase(), reason);
            }

            // Forward to UI via progress tracker
            if (progressTracker != null) {
                if (isOom) {
                    // Use OOM-specific failure with prominent indicator for UI
                    progressTracker.failTaskOutOfMemory(
                            taskId,
                            handle.getFileName(),
                            phase,
                            failed.errorMessage());
                    logger.error("Forwarded OOM failure to UI: task {} failed at phase {} - {}",
                            taskId, failed.phase(), failed.errorMessage());
                } else {
                    // Regular failure
                    progressTracker.failTask(
                            taskId,
                            handle.getFileName(),
                            phase,
                            failed.errorMessage());
                    logger.info("Forwarded failure to UI: task {} failed at phase {} - {}",
                            taskId, failed.phase(), failed.errorMessage());
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to forward failure for task {}: {}", taskId, e.getMessage(), e);
        }
    }

    /**
     * Detect if an error is an OutOfMemoryError based on message and type, or the child's memory
     * watchdog stopping the run before one.
     */
    private boolean isOutOfMemoryError(String errorMessage, String errorType) {
        if (errorType != null) {
            if (MEMORY_GUARD_ERROR_TYPES.contains(errorType)) {
                return true;
            }
            String typeUpper = errorType.toUpperCase();
            if (typeUpper.contains("OUTOFMEMORY") || typeUpper.equals("OUTOFMEMORYERROR")) {
                return true;
            }
        }
        if (errorMessage != null) {
            String msgUpper = errorMessage.toUpperCase();
            if (msgUpper.contains("OUTOFMEMORY") ||
                msgUpper.contains("OUT OF MEMORY") ||
                msgUpper.contains("JAVA HEAP SPACE") ||
                msgUpper.contains("GC OVERHEAD LIMIT") ||
                msgUpper.contains("HEAP EXHAUSTED")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Forward log message to IngestProgressTracker for WebSocket broadcast.
     */
    private void forwardLogMessage(SubprocessHandle handle, SubprocessMessage.Log logMsg) {
        if (progressTracker == null) {
            // Just log locally if tracker not available
            logger.debug("[subprocess-{}] [{}] {}", handle.getTaskId(), logMsg.level(), logMsg.message());
            return;
        }

        try {
            // Forward to WebSocket with source as channel and level
            progressTracker.sendLog(handle.getTaskId(), logMsg.source(), logMsg.level(), logMsg.message());

            // Also log locally at appropriate level for debugging
            switch (logMsg.level().toUpperCase()) {
                case "ERROR":
                    logger.error("[subprocess-{}] [{}] {}", handle.getTaskId(), logMsg.source(), logMsg.message());
                    break;
                case "WARN":
                    logger.warn("[subprocess-{}] [{}] {}", handle.getTaskId(), logMsg.source(), logMsg.message());
                    break;
                case "DEBUG", "TRACE":
                    logger.debug("[subprocess-{}] [{}] {}", handle.getTaskId(), logMsg.source(), logMsg.message());
                    break;
                default:
                    logger.info("[subprocess-{}] [{}] {}", handle.getTaskId(), logMsg.source(), logMsg.message());
            }
        } catch (Exception e) {
            logger.warn("Failed to forward log message for task {}: {}", handle.getTaskId(), e.getMessage());
        }
    }

    /**
     * Handle process completion.
     */
    private void handleCompletion(SubprocessHandle handle, int exitCode) {
        if (handle.getResultFuture().isDone()) {
            // Already completed (via COMPLETED or FAILED message)
            return;
        }

        if (exitCode == 0 && completionMessageArrives(handle)) {
            // The COMPLETED message was still being read when the process exited
            return;
        } else {
            // Failure - determine cause from exit code
            String errorMessage;
            String failureReason;
            boolean isNativeCrash = false;
            boolean isOomFailure = false;

            if (handle.isCancelled()) {
                errorMessage = "Process cancelled";
                failureReason = "USER_CANCELLED";
            } else if (handle.isOomDetected() || exitCode == 137) {
                // OOM detected via stderr parsing or exit code 137 (SIGKILL from OOM killer)
                // Exit code 3 with isOomDetected=true means -XX:+ExitOnOutOfMemoryError triggered
                if (handle.getReportedError() != null) {
                    // The child reported the memory failure itself: an OOM, or its watchdog stopping the run
                    errorMessage = handle.getReportedError();
                } else if (exitCode == 137) {
                    errorMessage = "Process killed (SIGKILL) - OOM killer";
                } else if (exitCode == 3 && handle.isOomDetected()) {
                    errorMessage = "Out of memory - JVM exited via -XX:+ExitOnOutOfMemoryError";
                } else {
                    errorMessage = "Out of memory";
                }
                failureReason = "OUT_OF_MEMORY";
                isOomFailure = true;
                logger.info("OOM failure confirmed for task {}: exitCode={}, oomDetected={}",
                        handle.getTaskId(), exitCode, handle.isOomDetected());
            } else if (exitCode == 0) {
                // Success but no explicit COMPLETED message - unusual; the job still has to end
                errorMessage = "Process exited without reporting completion";
                failureReason = "UNKNOWN";
            } else if (exitCode == 130) {
                errorMessage = "Process interrupted (SIGINT)";
                failureReason = "USER_CANCELLED";
            } else if (exitCode == 134) {
                // SIGABRT - often from native assertion failure or abort()
                errorMessage = "Native crash (SIGABRT) - likely ND4J/native library assertion failure";
                failureReason = "UNKNOWN";
                isNativeCrash = true;
            } else if (exitCode == 136) {
                // SIGFPE - floating point exception
                errorMessage = "Native crash (SIGFPE) - floating point exception in native code";
                failureReason = "UNKNOWN";
                isNativeCrash = true;
            } else if (exitCode == 139) {
                // SIGSEGV - segmentation fault
                errorMessage = "Native crash (SIGSEGV) - segmentation fault in ND4J/native code";
                failureReason = "UNKNOWN";
                isNativeCrash = true;
            } else if (exitCode == 143) {
                // SIGTERM - terminated
                errorMessage = "Process terminated (SIGTERM)";
                failureReason = "USER_CANCELLED";
            } else if (exitCode > 128) {
                // Other signal-based exit (128 + signal number)
                int signal = exitCode - 128;
                errorMessage = "Process killed by signal " + signal + " - possible native crash";
                failureReason = "UNKNOWN";
                isNativeCrash = true;
            } else {
                errorMessage = "Process exited with code " + exitCode;
                failureReason = "UNKNOWN";
            }

            // Log with appropriate level
            if (isNativeCrash) {
                logger.error("NATIVE CRASH in subprocess {} during phase {}: {} (exit code {}). " +
                        "This indicates a crash in ND4J or native libraries. " +
                        "The parent process is unaffected due to subprocess isolation.",
                        handle.getTaskId(), handle.getCurrentPhase(), errorMessage, exitCode);
            } else if (isOomFailure) {
                logger.error("OOM in subprocess {} during phase {}: {} (exit code {})",
                        handle.getTaskId(), handle.getCurrentPhase(), errorMessage, exitCode);
            } else {
                logger.error("Subprocess {} failed: {} (exit code {})",
                        handle.getTaskId(), errorMessage, exitCode);
            }

            // === ADAPTIVE RETRY LOGIC ===
            if (isOomFailure && !handle.isCancelled()) {
                boolean retryInitiated = attemptAdaptiveRetry(handle, exitCode, errorMessage);
                if (retryInitiated) {
                    // Retry was started - don't complete the future yet; this attempt's log ends here
                    closeSubprocessLog(handle.getTaskId(), failureReason, exitCode, errorMessage + " (retrying)",
                            handle.isOomDetected(), handle.isGpuOomDetected());
                    return;
                }
            }

            // No retry (or retry exhausted) - complete with failure, reported only as the job's first verdict
            if (!handle.getResultFuture().complete(SubprocessHandle.SubprocessResult.failure(
                    handle.getTaskId(), exitCode, errorMessage, handle.getCurrentPhase(),
                    handle.isCancelled(), handle.isOomDetected(), handle.isGpuOomDetected()))) {
                logger.info("Subprocess {} exited after its job had already ended; not reporting it again",
                        handle.getTaskId());
                return;
            }

            // Phase-2 log aggregation: record terminal state
            closeSubprocessLog(handle.getTaskId(), failureReason, exitCode, errorMessage,
                    handle.isOomDetected(), handle.isGpuOomDetected());

            // Update progress tracker with detailed message
            if (progressTracker != null) {
                String uiMessage = isNativeCrash
                        ? "Native crash in embedding/indexing - see logs for details"
                        : errorMessage;

                IngestProgressUpdate.IngestPhase phase = toProgressPhase(handle.getCurrentPhase());

                if (handle.isCancelled()) {
                    progressTracker.cancelTask(handle.getTaskId(), handle.getFileName(),
                            phase, uiMessage, null);
                } else if (isOomFailure) {
                    // Use OOM-specific failure with prominent indicator for UI
                    progressTracker.failTaskOutOfMemory(handle.getTaskId(), handle.getFileName(),
                            phase, uiMessage);
                } else {
                    progressTracker.failTask(handle.getTaskId(), handle.getFileName(),
                            phase, uiMessage);
                }
            }

            // Update job history
            if (jobHistoryService != null) {
                jobHistoryService.markJobFailed(handle.getTaskId(), toEventPhase(handle.getCurrentPhase()),
                        errorMessage, null,
                        IndexingJobHistory.FailureReason.valueOf(failureReason));
            }
            // Job tracking ends in cleanup(), which runs next (the checkpoint is kept for debugging)
        }
    }

    /**
     * Whether a child that exited 0 reports completion within the grace period — its stdout reader
     * may still be draining output when the watcher sees the exit.
     */
    private boolean completionMessageArrives(SubprocessHandle handle) {
        try {
            handle.getResultFuture().get(COMPLETION_MESSAGE_GRACE_SECONDS, TimeUnit.SECONDS);
            return true;
        } catch (ExecutionException | CancellationException e) {
            return true;
        } catch (TimeoutException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return handle.getResultFuture().isDone();
        }
    }

    /**
     * Attempt adaptive retry after OOM failure.
     *
     * @return true if retry was initiated, false if retry exhausted or not applicable
     */
    private boolean attemptAdaptiveRetry(SubprocessHandle handle, int exitCode, String errorMessage) {
        String taskId = handle.getTaskId();
        String jobId = taskToJobId.get(taskId);

        if (jobId == null) {
            logger.warn("Cannot retry task {}: no jobId found", taskId);
            return false;
        }

        LaunchContext context = jobLaunchContexts.get(jobId);
        if (context == null) {
            logger.warn("Cannot retry task {}: no launch context found for job {}", taskId, jobId);
            return false;
        }
        if (context.cancelled().get()) {
            logger.info("Not retrying task {}: job {} was cancelled", taskId, jobId);
            return false;
        }

        // Load or create checkpoint
        Path checkpointPath = getCheckpointPath(jobId);
        ai.kompile.app.subprocess.IngestCheckpoint checkpoint =
                ai.kompile.app.subprocess.IngestCheckpoint.loadOrCreate(
                        checkpointPath, jobId, taskId, context.filePath().toString());

        // Record this OOM failure in checkpoint
        String currentHeapSize = getEffectiveHeapSize(context.originalOptions());
        int currentBatchSize = SubprocessArgs.DEFAULT_EMBEDDING_BATCH_SIZE;
        if (context.originalOptions() != null && context.originalOptions().containsKey("embeddingBatchSize")) {
            currentBatchSize = ((Number) context.originalOptions().get("embeddingBatchSize")).intValue();
        }
        int currentNd4jThreads = Runtime.getRuntime().availableProcessors() / 2;
        int currentOmpThreads = currentNd4jThreads;

        // If we have previous retry state, use those settings
        RetryState previousState = jobRetryState.get(jobId);
        if (previousState != null && previousState.currentSettings() != null) {
            currentHeapSize = previousState.currentSettings().getHeapSize();
            currentBatchSize = previousState.currentSettings().getBatchSize();
            currentNd4jThreads = previousState.currentSettings().getNd4jThreads();
            currentOmpThreads = previousState.currentSettings().getOmpThreads();
        }

        checkpoint.recordOomFailure(currentHeapSize, currentBatchSize, currentNd4jThreads,
                currentOmpThreads, errorMessage, handle.getCurrentPhase());

        // Save checkpoint
        try {
            checkpoint.save(checkpointPath);
        } catch (IOException e) {
            logger.error("Failed to save checkpoint for job {}: {}", jobId, e.getMessage());
        }

        // Calculate next settings using adaptive recovery
        String maxHeapSize = getMaxAllowedHeapSize();
        ai.kompile.app.subprocess.AdaptiveRecoverySettings newSettings =
                ai.kompile.app.subprocess.AdaptiveRecoverySettings.fromCheckpoint(
                        checkpoint, maxHeapSize, SubprocessArgs.DEFAULT_EMBEDDING_BATCH_SIZE);

        if (!newSettings.shouldRetry() || newSettings.isShouldGiveUp()) {
            logger.error("ADAPTIVE RETRY EXHAUSTED for job {}: {}", jobId, newSettings.getGiveUpReason());
            // Let the normal failure handling proceed
            return false;
        }

        // Initiate retry
        logger.info("========================================");
        logger.info("ADAPTIVE RETRY #{} for job {}", newSettings.getRetryAttempt(), jobId);
        logger.info("Previous settings failed: heap={}, batch={}, threads={}",
                currentHeapSize, currentBatchSize, currentNd4jThreads);
        logger.info("New settings: {}", newSettings.toSummary());
        logger.info("Checkpoint: {} embedded, {} indexed of {} chunks",
                checkpoint.getEmbeddedCount(), checkpoint.getIndexedCount(), checkpoint.getTotalChunks());
        logger.info("========================================");

        // Store new retry state
        jobRetryState.put(jobId, new RetryState(jobId, newSettings.getRetryAttempt(), newSettings, checkpoint));

        // Notify UI about retry
        if (progressTracker != null) {
            progressTracker.sendLog(taskId, "RETRY",
                    String.format("Adaptive retry #%d: Adjusting settings (heap=%s, batch=%d, threads=%d)",
                            newSettings.getRetryAttempt(), newSettings.getHeapSize(),
                            newSettings.getBatchSize(), newSettings.getNd4jThreads()));
        }

        // Generate new taskId for retry (keeps jobId the same)
        String newTaskId = taskId + "-retry" + newSettings.getRetryAttempt();

        // Launch new subprocess asynchronously — same job, same placement, same GPU row
        CompletableFuture.runAsync(() -> {
            try {
                // Small delay to allow cleanup
                Thread.sleep(1000);

                if (context.cancelled().get() || context.resultFuture().isDone()) {
                    logger.info("Ingest job {} ended while its retry was pending; not relaunching", jobId);
                    // A cancel that raced this retry's scheduling left the job to end here, with the verdict
                    // the cancel would have given
                    endJobBetweenAttempts(context, SubprocessHandle.SubprocessResult.failure(
                            taskId, exitCode,
                            shuttingDown ? "Ingest stopped: the application is shutting down" : "Cancelled by user",
                            handle.getCurrentPhase(), true, false));
                    return;
                }
                launchIngestInternal(newTaskId, context, newSettings);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                logger.warn("Retry subprocess launch interrupted for job {}", jobId);
                endJobBetweenAttempts(context, SubprocessHandle.SubprocessResult.failure(
                        taskId, exitCode, "Retry interrupted", handle.getCurrentPhase(),
                        false, true));
            } catch (Exception e) {
                logger.error("Failed to launch retry subprocess for job {}: {}", jobId, e.getMessage(), e);
                // Complete the original future with failure
                endJobBetweenAttempts(context, SubprocessHandle.SubprocessResult.failure(
                        taskId, exitCode, "Retry failed: " + e.getMessage(), handle.getCurrentPhase(),
                        false, true));
            }
        });

        return true;
    }

    /**
     * Get maximum allowed heap size for subprocess retry.
     */
    private String getMaxAllowedHeapSize() {
        // Could be configurable - for now, use 16GB or system max, whichever is lower
        long maxSystemMemory = Runtime.getRuntime().maxMemory();
        long targetMax = Math.min(16L * 1024 * 1024 * 1024, maxSystemMemory * 2);
        return ai.kompile.app.subprocess.AdaptiveRecoverySettings.formatHeapSize(targetMax);
    }

    /**
     * Cleanup after subprocess completes.
     */
    private void cleanup(SubprocessHandle handle) {
        // Remove from active processes
        activeProcesses.remove(handle.getTaskId());

        // Deregister from centralized subprocess registry
        if (subprocessRegistry != null) {
            subprocessRegistry.deregister("ingest-" + handle.getTaskId());
        }

        // Remove from warned task IDs
        warnedTaskIds.remove(handle.getTaskId());

        // Remove worker status tracking for this task
        taskWorkerStatuses.remove(handle.getTaskId());

        // === GPU LIFECYCLE: the job ends once its result is final ===
        // An attempt ending with a retry pending keeps the job (and its GPU row) alive.
        String jobId = taskToJobId.remove(handle.getTaskId());
        if (jobId != null) {
            LaunchContext context = jobLaunchContexts.get(jobId);
            if (context == null || context.resultFuture().isDone()) {
                finishJob(jobId);
            }
        }

        // Phase-2 log aggregation: safety-close writer if not already closed
        // (covers protocol-completed paths where handleCompletion returned early)
        SubprocessHandle.SubprocessResult verdict = verdictOf(handle);
        SubprocessLogWriter slw = logWriters.remove(handle.getTaskId());
        if (slw != null) {
            try {
                // Only write a terminal record if the writer is still open (writeEnd is idempotent)
                slw.writeEnd(logEndFor(handle, verdict));
            } catch (Exception _logEx) {
                logger.debug("[ingest-{}] SubprocessLogWriter safety writeEnd failed: {}", handle.getTaskId(), _logEx.getMessage());
            } finally {
                slw.close();
            }
        }

        // A completed attempt's path is removed by forwardCompletion, which may still be using it
        if (verdict == null || !verdict.success()) {
            taskFilePaths.remove(handle.getTaskId());
        }

        deleteArgsFile(handle.getArgsFile());
    }

    /** The job's verdict, or null if it has none yet. */
    private static SubprocessHandle.SubprocessResult verdictOf(SubprocessHandle handle) {
        CompletableFuture<SubprocessHandle.SubprocessResult> future = handle.getResultFuture();
        return future.isDone() && !future.isCompletedExceptionally() ? future.join() : null;
    }

    /**
     * The terminal log record for an attempt whose log is still open at cleanup: its job's verdict was given
     * elsewhere (its own COMPLETED or FAILED message, or a cancel between attempts), or it has none.
     */
    private static SubprocessLogWriter.SubprocessRunResult logEndFor(SubprocessHandle handle,
                                                                     SubprocessHandle.SubprocessResult verdict) {
        Process process = handle.getProcess();
        Integer exitCode = process.isAlive() ? null : process.exitValue();
        String state;
        if (verdict == null) {
            state = "FAILED";
        } else if (verdict.success()) {
            state = "COMPLETED";
        } else if (verdict.cancelled()) {
            state = "USER_CANCELLED";
        } else if (verdict.oomKilled()) {
            state = "OUT_OF_MEMORY";
        } else {
            state = "FAILED";
        }
        String errorMessage = verdict != null ? verdict.errorMessage() : "Subprocess ended without a verdict";
        return new SubprocessLogWriter.SubprocessRunResult(state, exitCode, errorMessage,
                handle.isOomDetected(), handle.isGpuOomDetected());
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
     * End a job's tracking once its result is final (or it never started), releasing its GPU row if
     * this launcher acquired it — a row the scheduler holds is the scheduler's to release. Idempotent:
     * the launcher's row is released exactly once.
     */
    private void finishJob(String jobId) {
        jobLaunchContexts.remove(jobId);
        jobRetryState.remove(jobId);
        if (launcherGpuHolds.remove(jobId) && modelLifecycleManager != null) {
            logger.info("[ingest-{}] Releasing GPU resources for finished ingest job", jobId);
            try {
                modelLifecycleManager.releaseGpuForJob(jobId);
            } catch (Exception e) {
                logger.warn("[ingest-{}] Error releasing GPU resources: {}", jobId, e.getMessage());
            }
        }
    }

    /**
     * Write the terminal record and close the SubprocessLogWriter for a task.
     * Called from the explicit failure path in handleCompletion.
     * Removes the writer from the map so cleanup()'s safety-close is a no-op.
     * All failures are swallowed — aggregation must never break an ingest run.
     */
    private void closeSubprocessLog(String taskId, String state, Integer exitCode,
                                    String errorMessage, boolean oomDetected, boolean gpuOomDetected) {
        SubprocessLogWriter slw = logWriters.remove(taskId);
        if (slw == null) return;
        try {
            slw.writeEnd(new SubprocessLogWriter.SubprocessRunResult(
                    state, exitCode, errorMessage, oomDetected, gpuOomDetected));
        } catch (Exception _logEx) {
            logger.debug("[ingest-{}] SubprocessLogWriter writeEnd failed (non-fatal): {}", taskId, _logEx.getMessage());
        } finally {
            slw.close();
        }
    }

    /**
     * Capture ND4J configuration as JSON from the live parent process environment.
     *
     * This method captures the ACTUAL ND4J environment settings from the running
     * parent process via Nd4jEnvironmentConfigService.getActualConfiguration().
     * This ensures the subprocess receives the same ND4J settings as the parent.
     *
     * If the config service is not available (e.g., during testing), falls back
     * to reading from environment variables.
     */
    private String captureNd4jConfig() {
        // Check if device routing provides a service-specific config for ingest
        if (deviceRoutingConfigService != null && deviceRoutingConfigService.isEnabled()) {
            try {
                Nd4jEnvironmentConfig routedConfig = deviceRoutingConfigService
                        .resolveNd4jConfigForService(DeviceRoutingConfig.SERVICE_INGEST);
                logger.info("Using device-routed ND4J config for ingest: maxThreads={}, cudaDevice={}",
                        routedConfig.maxThreads(), routedConfig.cudaCurrentDevice());
                return objectMapper.writeValueAsString(routedConfig);
            } catch (Exception e) {
                logger.warn("Failed to resolve device-routed config for ingest, falling back: {}", e.getMessage());
            }
        }

        Nd4jEnvironmentConfig config = null;

        // Prefer capturing the live ND4J config if the service is available, but fall
        // back gracefully.
        if (nd4jEnvironmentConfigService != null) {
            try {
                Nd4jEnvironmentConfig actualConfig = nd4jEnvironmentConfigService.getActualConfiguration();
                logger.info(
                        "Capturing actual live ND4J config: maxThreads={}, maxMasterThreads={}, lifecycleTracking={}",
                        actualConfig.maxThreads(), actualConfig.maxMasterThreads(), actualConfig.lifecycleTracking());
                config = actualConfig;
            } catch (Exception e) {
                logger.warn("Failed to capture ND4J config from Nd4jEnvironmentConfigService, falling back: {}",
                        e.getMessage());
            }
        } else {
            logger.warn("Nd4jEnvironmentConfigService not available, using environment variables for ND4J config");
        }

        if (config == null) {
            config = Nd4jEnvironmentConfig.builder()
                    .maxThreads(getIntEnvOrDefault("ND4J_MAX_THREADS",
                            getIntEnvOrDefault("OMP_NUM_THREADS", Runtime.getRuntime().availableProcessors())))
                    .maxMasterThreads(getIntEnvOrDefault("ND4J_MAX_MASTER_THREADS",
                            Math.max(1, Runtime.getRuntime().availableProcessors() / 2)))
                    .debug(getBoolEnvOrDefault("ND4J_DEBUG", false))
                    .verbose(getBoolEnvOrDefault("ND4J_VERBOSE", false))
                    .profiling(getBoolEnvOrDefault("ND4J_PROFILING", false))
                    .enableBlas(getBoolEnvOrDefault("ND4J_ENABLE_BLAS", true))
                    .helpersAllowed(getBoolEnvOrDefault("ND4J_HELPERS_ALLOWED", true))
                    .lifecycleTracking(getBoolEnvOrDefault("ND4J_LIFECYCLE_TRACKING", false))
                    .build();
        }

        try {
            return objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            logger.warn("Failed to serialize ND4J config to JSON: {}", e.getMessage());
            return null;
        }
    }

    private int getIntEnvOrDefault(String envName, int defaultValue) {
        String value = System.getenv(envName);
        if (value == null || value.isEmpty()) {
            value = System.getProperty(envName.toLowerCase().replace('_', '.'));
        }
        if (value != null && !value.isEmpty()) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        return defaultValue;
    }

    private boolean getBoolEnvOrDefault(String envName, boolean defaultValue) {
        String value = System.getenv(envName);
        if (value == null || value.isEmpty()) {
            value = System.getProperty(envName.toLowerCase().replace('_', '.'));
        }
        if (value != null && !value.isEmpty()) {
            return "true".equalsIgnoreCase(value) || "1".equals(value);
        }
        return defaultValue;
    }

    /**
     * Propagate ND4J-related environment variables from the parent process to the
     * subprocess.
     * This ensures the subprocess uses the same backend, thread settings, and GPU
     * configuration.
     *
     * @param env          The subprocess environment map to populate
     * @param jobPlacement The job's placement, whose per-device memory bound the child gets
     */
    private void propagateNd4jEnvironment(Map<String, String> env, SubprocessPlacement jobPlacement) {
        SubprocessEnvironmentPropagator.propagateToEnvironment(env);
        // Device-agnostic per-device memory bound (SD_MAX_DEVICE_BYTES) — shared base infra.
        SubprocessBackendFlags.applyEnv(env, jobPlacement);
    }

    /**
     * Scheduled task to check for stale subprocesses.
     */
    @Scheduled(fixedRate = STALE_CHECK_INTERVAL_MS)
    public void checkStaleProcesses() {
        int staleSeconds = getEffectiveStaleThresholdSeconds();
        Duration staleThreshold = Duration.ofSeconds(staleSeconds);

        for (SubprocessHandle handle : activeProcesses.values()) {
            // A cancelled attempt is already being stopped
            if (handle.isAlive() && !handle.isCancelled() && handle.isStale(staleThreshold)) {
                logger.warn("Subprocess {} appears stuck (no heartbeat for {} seconds), force killing",
                        handle.getTaskId(), staleSeconds);

                // The stall is the verdict, given before the kill so the kill is not judged a cancel
                endAttemptBeforeKill(handle, "Process became unresponsive (no heartbeat)", "PROCESS_STUCK",
                        IngestProgressUpdate.FailureReason.PROCESS_STUCK, IndexingJobHistory.FailureReason.TIMEOUT);
                handle.cancel();
            }
        }
    }

    /**
     * Cancel all active subprocesses on shutdown.
     */
    @PreDestroy
    public void shutdownAll() {
        logger.info("Shutting down all active ingest subprocesses...");
        shuttingDown = true;

        // No pending retry may start once shutdown begins; each job's started attempt is cancelled
        for (LaunchContext context : jobLaunchContexts.values()) {
            SubprocessHandle attempt;
            synchronized (context) {
                context.cancelled().set(true);
                attempt = context.currentAttempt().get();
            }
            if (attempt != null && attempt.isAlive()) {
                logger.info("Cancelling subprocess: {}", attempt.getTaskId());
                attempt.cancel();
            }
        }

        for (SubprocessHandle handle : activeProcesses.values()) {
            if (handle.isAlive() && !handle.isCancelled()) {
                logger.info("Cancelling subprocess: {}", handle.getTaskId());
                handle.cancel();
            }
        }

        // Wait for all to terminate
        for (SubprocessHandle handle : activeProcesses.values()) {
            handle.waitFor(Duration.ofSeconds(5));
            // Its watcher deletes the args file too, but the JVM may exit before the watcher gets there. The
            // file carries the staging API key
            deleteArgsFile(handle.getArgsFile());
        }

        // Release only the GPU rows this launcher acquired, once their children are gone; the
        // scheduler releases its own
        for (String jobId : List.copyOf(launcherGpuHolds)) {
            logger.info("[ingest-{}] Releasing GPU resources during shutdown", jobId);
            finishJob(jobId);
        }

        activeProcesses.clear();
        warnedTaskIds.clear();
        logger.info("All subprocesses terminated");
    }

    /**
     * Wait for all active subprocesses to terminate.
     */
    public void awaitTermination(Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();

        for (SubprocessHandle handle : activeProcesses.values()) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining > 0) {
                handle.waitFor(Duration.ofMillis(remaining));
            }
        }
    }

    /**
     * Convert a String phase to IngestProgressUpdate.IngestPhase.
     */
    private IngestProgressUpdate.IngestPhase toProgressPhase(String phase) {
        if (phase == null || phase.isEmpty()) {
            return IngestProgressUpdate.IngestPhase.QUEUED;
        }
        // Normalize phase name - handle common variations
        String normalizedPhase = normalizePhase(phase);
        try {
            return IngestProgressUpdate.IngestPhase.valueOf(normalizedPhase);
        } catch (IllegalArgumentException e) {
            logger.warn("Unknown progress phase: {} (normalized: {}), defaulting to QUEUED", phase, normalizedPhase);
            return IngestProgressUpdate.IngestPhase.QUEUED;
        }
    }

    /**
     * Convert a String phase to IngestEvent.IngestPhase.
     */
    private IngestEvent.IngestPhase toEventPhase(String phase) {
        if (phase == null || phase.isEmpty()) {
            return IngestEvent.IngestPhase.QUEUED;
        }
        // Normalize phase name - handle common variations
        String normalizedPhase = normalizePhase(phase);
        try {
            return IngestEvent.IngestPhase.valueOf(normalizedPhase);
        } catch (IllegalArgumentException e) {
            logger.warn("Unknown event phase: {} (normalized: {}), defaulting to QUEUED", phase, normalizedPhase);
            return IngestEvent.IngestPhase.QUEUED;
        }
    }

    /**
     * Normalize phase names to match enum values.
     * Handles common variations like "COMPLETE" -> "COMPLETED", "STARTING" ->
     * "LOADING".
     */
    private String normalizePhase(String phase) {
        if (phase == null)
            return "QUEUED";
        String upper = phase.toUpperCase().trim();
        return switch (upper) {
            case "COMPLETE" -> "COMPLETED";
            case "STARTING" -> "LOADING";
            case "DONE" -> "COMPLETED";
            case "FINISH", "FINISHED" -> "COMPLETED";
            case "EMBED" -> "EMBEDDING";
            case "INDEX" -> "INDEXING";
            case "CHUNK" -> "CHUNKING";
            case "LOAD" -> "LOADING";
            case "CONVERT" -> "CONVERTING";
            case "FAIL", "ERROR" -> "FAILED";
            case "INDEXING+EMBEDDING", "INDEX+EMBED" -> "INDEXING_AND_EMBEDDING";
            default -> upper;
        };
    }
}
