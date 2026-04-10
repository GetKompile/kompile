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
import ai.kompile.app.config.Nd4jEnvironmentConfig;
import ai.kompile.app.config.SubprocessExecutableConfig;
import ai.kompile.cli.main.util.NativeImageInfo;
import ai.kompile.app.facts.domain.FactSheet;
import ai.kompile.app.facts.service.FactSheetService;
import ai.kompile.app.ingest.domain.IngestEvent;
import ai.kompile.app.ingest.service.IndexingJobHistoryService;
import ai.kompile.app.ingest.service.IngestEventService;
import ai.kompile.app.services.AppIndexConfigService;
import ai.kompile.app.config.DeviceRoutingConfig;
import ai.kompile.app.services.DeviceRoutingConfigService;
import ai.kompile.app.services.IngestProgressTracker;
import ai.kompile.app.services.ModelLifecycleManager;
import ai.kompile.app.services.Nd4jEnvironmentConfigService;
import ai.kompile.app.services.ServerPortService;
import ai.kompile.app.subprocess.SubprocessArgs;
import ai.kompile.app.subprocess.SubprocessMessage;
import ai.kompile.app.web.dto.IngestProgressUpdate;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
// NOTE: Do NOT import Nd4j here - it would initialize ND4J native code in parent process
// which defeats the purpose of subprocess isolation. See captureNd4jConfig() comments.
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
public class SubprocessIngestLauncher {

    private static final Logger logger = LoggerFactory.getLogger(SubprocessIngestLauncher.class);

    private static final String SUBPROCESS_MAIN_CLASS = "ai.kompile.app.subprocess.IngestSubprocessMain";

    @Value("${kompile.ingest.subprocess.java-path:java}")
    private String javaPath;

    @Value("${kompile.ingest.subprocess.heap-size:4g}")
    private String heapSize;

    @Value("${kompile.ingest.subprocess.timeout-minutes:60}")
    private int timeoutMinutes;

    @Value("${kompile.ingest.subprocess.heartbeat-interval-seconds:10}")
    private int heartbeatIntervalSeconds;

    @Value("${kompile.ingest.subprocess.progress-stall-threshold-seconds:60}")
    private int progressStallThresholdSeconds;

    @Value("${kompile.ingest.subprocess.stale-threshold-seconds:120}")
    private int staleThresholdSeconds;

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
    private ModelLifecycleManager modelLifecycleManager;

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

    /** Directory for checkpoint storage */
    private Path checkpointBaseDir;

    /** Record to track retry state for adaptive recovery */
    private record RetryState(
            String jobId,
            int attemptNumber,
            ai.kompile.app.subprocess.AdaptiveRecoverySettings currentSettings,
            ai.kompile.app.subprocess.IngestCheckpoint checkpoint
    ) {}

    /** Record to store original launch context for retry */
    private record LaunchContext(
            String jobId,
            Path filePath,
            String loaderName,
            String chunkerName,
            Map<String, Object> originalOptions,
            CompletableFuture<SubprocessHandle.SubprocessResult> resultFuture
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
        this.objectMapper = new ObjectMapper();

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
     * Launch a subprocess to ingest a document.
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
        // Generate a unique jobId that persists across retries
        String jobId = taskId; // Use taskId as jobId for the initial attempt
        return launchIngestInternal(taskId, jobId, filePath, loaderName, chunkerName, options, null);
    }

    /**
     * Internal method to launch ingest with full control over settings.
     * Used for both initial launch and retry with adaptive settings.
     */
    private CompletableFuture<SubprocessHandle.SubprocessResult> launchIngestInternal(
            String taskId,
            String jobId,
            Path filePath,
            String loaderName,
            String chunkerName,
            Map<String, Object> options,
            ai.kompile.app.subprocess.AdaptiveRecoverySettings recoverySettings) {
        logger.info("Launching ingest subprocess for task: {} (jobId: {}) file: {}", taskId, jobId, filePath);

        // For initial launch, create a new future; for retry, we reuse the original
        CompletableFuture<SubprocessHandle.SubprocessResult> resultFuture;
        LaunchContext existingContext = jobLaunchContexts.get(jobId);
        if (existingContext != null) {
            resultFuture = existingContext.resultFuture();
        } else {
            resultFuture = new CompletableFuture<>();
            // Store launch context for potential retry
            jobLaunchContexts.put(jobId, new LaunchContext(jobId, filePath, loaderName, chunkerName,
                    options != null ? new java.util.HashMap<>(options) : new java.util.HashMap<>(), resultFuture));
        }

        // Store file path for fact creation on completion
        taskFilePaths.put(taskId, filePath);

        try {
            String fileName = filePath.getFileName().toString();

            String nd4jConfigJson = captureNd4jConfig();

            // Build subprocess args
            String callbackBaseUrl = serverPortService != null
                    ? serverPortService.getBaseUrl()
                    : "http://localhost:8080";

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
            java.nio.file.Path archivePathObj = ai.kompile.embedding.anserini.AnseriniEncoderFactory
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
            Map<String, Object> effectiveOptions = new java.util.HashMap<>(options != null ? options : Map.of());
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

            // Write args to temp file
            Path argsFile = args.writeToTempFile();
            logger.debug("Wrote subprocess args to: {}", argsFile);

            // Build command with effective options (includes adaptive settings)
            List<String> command = buildCommand(argsFile, effectiveOptions);
            logger.info("Subprocess command: {}", String.join(" ", command));
            if (shouldResume) {
                logger.info("ADAPTIVE RETRY: Resuming from checkpoint at {}", checkpointPath);
            }

            // Start process
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(false);

            // Propagate ND4J environment variables from parent process
            propagateNd4jEnvironment(processBuilder.environment());

            // Apply thread settings from recovery if specified
            if (recoverySettings != null) {
                processBuilder.environment().put("OMP_NUM_THREADS", String.valueOf(recoverySettings.getOmpThreads()));
                processBuilder.environment().put("MKL_NUM_THREADS", String.valueOf(recoverySettings.getOmpThreads()));
                processBuilder.environment().put("OPENBLAS_NUM_THREADS", String.valueOf(recoverySettings.getOmpThreads()));
                logger.info("Applied adaptive thread settings: OMP_NUM_THREADS={}", recoverySettings.getOmpThreads());
            }

            // === GPU LIFECYCLE: Acquire GPU resources for this ingest job ===
            if (modelLifecycleManager != null) {
                try {
                    modelLifecycleManager.acquireGpuForIngest(taskId, fileName);
                    logger.info("[ingest-{}] GPU resources acquired for ingest job", taskId);
                } catch (IllegalStateException e) {
                    logger.warn("[ingest-{}] Could not acquire GPU for ingest (may use CPU fallback): {}",
                            taskId, e.getMessage());
                    // Don't fail the ingest — it may be able to run on CPU or with reduced GPU
                }
            }

            Process process = processBuilder.start();
            logger.info("Started subprocess with PID: {}", process.pid());

            // Track taskId -> jobId mapping for retry handling
            taskToJobId.put(taskId, jobId);

            // Create handle
            SubprocessHandle handle = createHandle(taskId, fileName,
                    process, resultFuture, argsFile);
            activeProcesses.put(taskId, handle);

            // Start monitoring
            startMonitoring(handle);

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
            resultFuture.completeExceptionally(e);
        }

        return resultFuture;
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
     * Cancel a running subprocess.
     *
     * @param taskId Task identifier
     * @return true if cancelled, false if not found or already finished
     */
    public boolean cancelIngest(String taskId) {
        SubprocessHandle handle = activeProcesses.get(taskId);
        if (handle == null || !handle.isAlive()) {
            return false;
        }

        logger.info("Cancelling subprocess for task: {}", taskId);
        handle.cancel();

        // Update progress tracker
        if (progressTracker != null) {
            progressTracker.cancelTask(taskId, handle.getFileName(), toProgressPhase(handle.getCurrentPhase()),
                    "Cancelled by user", null);
        }

        return true;
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
     * @param argsFile Path to the args file
     * @param options  Per-request options (heapSize, timeoutMinutes, etc.)
     */
    private List<String> buildCommand(Path argsFile, Map<String, Object> options) {
        // Check if we should use native executable mode
        if (shouldUseNativeExecutableMode()) {
            return buildNativeCommand(argsFile);
        }

        // JVM classpath mode
        return buildJvmCommand(argsFile, options);
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

        String executablePath = subprocessConfigService.getExecutablePathForType("ingest");
        if (executablePath == null || executablePath.isBlank()) {
            throw new IllegalStateException(
                "Native executable mode required but no executable path configured. " +
                "Configure the native executable path in Processing Settings (Developer Hub).");
        }

        List<String> command = new ArrayList<>();
        command.add(executablePath);

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
    private List<String> buildJvmCommand(Path argsFile, Map<String, Object> options) {
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
                            // Ignore
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
     * Create a subprocess handle with stdout/stderr readers.
     */
    private SubprocessHandle createHandle(String taskId, String fileName, Process process,
            CompletableFuture<SubprocessHandle.SubprocessResult> resultFuture,
            Path argsFile) {

        // Create reader threads (will be started after handle creation)
        Thread[] readers = new Thread[2];

        SubprocessHandle handle = new SubprocessHandle(
                taskId, fileName, process,
                null, null, // Will set readers after creating them
                resultFuture, argsFile);

        // Create stdout reader
        readers[0] = new Thread(() -> readStdout(handle), "subprocess-stdout-" + taskId);
        readers[0].setDaemon(true);

        // Create stderr reader
        readers[1] = new Thread(() -> readStderr(handle), "subprocess-stderr-" + taskId);
        readers[1].setDaemon(true);

        // Update handle with readers (using reflection to set final fields - not ideal
        // but works)
        // Alternatively, make fields non-final in SubprocessHandle

        return new SubprocessHandle(
                taskId, fileName, process,
                readers[0], readers[1],
                resultFuture, argsFile);
    }

    /**
     * Start monitoring threads for a subprocess.
     */
    private void startMonitoring(SubprocessHandle handle) {
        // Start stdout reader
        Thread stdoutReader = new Thread(() -> readStdout(handle), "subprocess-stdout-" + handle.getTaskId());
        stdoutReader.setDaemon(true);
        stdoutReader.start();

        // Start stderr reader
        Thread stderrReader = new Thread(() -> readStderr(handle), "subprocess-stderr-" + handle.getTaskId());
        stderrReader.setDaemon(true);
        stderrReader.start();

        // Start process completion watcher
        Thread completionWatcher = new Thread(() -> watchCompletion(handle),
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
                // Check for protocol messages
                if (line.startsWith(SubprocessMessage.MESSAGE_PREFIX)) {
                    String json = line.substring(SubprocessMessage.MESSAGE_PREFIX.length());
                    handleMessage(handle, json);
                } else if (!line.isBlank()) {
                    // Regular log output - log locally and forward to WebSocket
                    logger.debug("[subprocess-{}] {}", handle.getTaskId(), line);

                    // Forward to WebSocket for UI display
                    if (progressTracker != null) {
                        progressTracker.sendLog(handle.getTaskId(), "STDOUT", line);
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
     * All stderr output is forwarded to WebSocket for UI display.
     */
    private void readStderr(SubprocessHandle handle) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(getProcessForHandle(handle).getErrorStream()))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank())
                    continue;

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
        } catch (IOException e) {
            if (!handle.isCancelled()) {
                logger.debug("Stderr reader terminated for task: {}", handle.getTaskId());
            }
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
     * Get process for a handle (helper to access the process from handle).
     */
    private Process getProcessForHandle(SubprocessHandle handle) {
        // This is a workaround since we can't access the process directly from handle
        // In practice, we'd need to either expose it or track it separately
        SubprocessHandle tracked = activeProcesses.get(handle.getTaskId());
        if (tracked != null) {
            // Use reflection or add a getter
            try {
                var field = SubprocessHandle.class.getDeclaredField("process");
                field.setAccessible(true);
                return (Process) field.get(tracked);
            } catch (Exception e) {
                logger.error("Failed to get process from handle", e);
            }
        }
        return null;
    }

    /**
     * Watch for process completion.
     */
    private void watchCompletion(SubprocessHandle handle) {
        try {
            Process process = getProcessForHandle(handle);
            if (process == null)
                return;

            int exitCode = process.waitFor();
            logger.info("Subprocess {} exited with code: {}", handle.getTaskId(), exitCode);

            // Give stderr/stdout readers time to finish processing output
            // This is important for OOM detection - the JVM prints the OOM message to stderr
            // right before exiting, and we need to read it before calling handleCompletion
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }

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
     * Handle a parsed message from subprocess.
     */
    private void handleMessage(SubprocessHandle handle, String json) {
        try {
            SubprocessMessage message = objectMapper.readValue(json, SubprocessMessage.class);
            logger.debug("Received subprocess message: type={}, taskId={}",
                    message.getClass().getSimpleName(), handle.getTaskId());

            if (message instanceof SubprocessMessage.Progress progress) {
                handle.updateProgress(progress.phase(), progress.progressPercent(), progress.message());
                forwardProgress(handle, progress);
            } else if (message instanceof SubprocessMessage.PhaseTransition transition) {
                handle.setCurrentPhase(transition.toPhase());
                handle.updateHeartbeat();
                logger.info("Task {} phase transition: {} -> {}",
                        handle.getTaskId(), transition.fromPhase(), transition.toPhase());
                // Forward phase transition to UI
                forwardPhaseTransition(handle, transition);
            } else if (message instanceof SubprocessMessage.Heartbeat heartbeat) {
                handle.updateMemoryFromHeartbeat(heartbeat);
                logger.debug("Task {} heartbeat: uptime={}ms, heap={}%, offHeap={}%, gpu={}%",
                        handle.getTaskId(), heartbeat.uptimeMs(),
                        String.format("%.1f", heartbeat.memoryUsagePercent()),
                        String.format("%.1f", heartbeat.offHeapUsagePercent()),
                        String.format("%.1f", heartbeat.gpuUsagePercent()));
            } else if (message instanceof SubprocessMessage.Completed completed) {
                logger.info("Task {} completed: {} docs, {} chunks indexed",
                        handle.getTaskId(), completed.documentsLoaded(), completed.documentsIndexed());
                handle.getResultFuture().complete(SubprocessHandle.SubprocessResult.success(
                        handle.getTaskId(), completed));
                // Forward completion to UI
                forwardCompletion(handle, completed);
                taskWorkerStatuses.remove(handle.getTaskId());
            } else if (message instanceof SubprocessMessage.Failed failed) {
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
                    // DON'T complete the future - let handleCompletion do it after retry attempt
                } else {
                    // Non-OOM failure - complete immediately
                    handle.getResultFuture().complete(SubprocessHandle.SubprocessResult.failure(
                            handle.getTaskId(), 1, failed.errorMessage(), failed.phase(), false, false));
                    // Forward failure to UI
                    forwardFailure(handle, failed);
                    taskWorkerStatuses.remove(handle.getTaskId());
                }
            } else if (message instanceof SubprocessMessage.WorkerStatus workerStatus) {
                // Store worker status for inclusion in progress updates
                taskWorkerStatuses
                        .computeIfAbsent(handle.getTaskId(), k -> new ConcurrentHashMap<>())
                        .put(workerStatus.workerId(), workerStatus);

                // Worker status updates for detailed monitoring
                logger.debug("Task {} worker {}: {} - {} items",
                        handle.getTaskId(), workerStatus.workerId(),
                        workerStatus.status(), workerStatus.itemsProcessed());
            } else if (message instanceof SubprocessMessage.Log logMsg) {
                // Forward log messages to WebSocket for real-time display
                handle.updateHeartbeat(); // Log activity counts as liveness signal
                forwardLogMessage(handle, logMsg);
            }
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
            java.util.List<SubprocessMessage.BatchHistoryEntry> subHistory) {
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
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)$").matcher(workerId);
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
        java.util.regex.Pattern batchPattern = java.util.regex.Pattern.compile(
                "batch\\s+(\\d+)/(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher matcher = batchPattern.matcher(currentStep);
        if (matcher.find()) {
            try {
                return new int[] {
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2))
                };
            } catch (NumberFormatException e) {
                // Ignore
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
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "(?:Indexed|Embedded)\\s+\\d+/(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher matcher = pattern.matcher(currentStep);
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
            java.util.regex.Pattern ratePattern = java.util.regex.Pattern.compile(
                    "\\((\\d+\\.?\\d*)/sec\\)", java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher rateMatcher = ratePattern.matcher(currentStep);
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
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("Indexed\\s+(\\d+)/(\\d+)",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher matcher = pattern.matcher(currentStep);
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
     * Detect if an error is an OutOfMemoryError based on message and type.
     */
    private boolean isOutOfMemoryError(String errorMessage, String errorType) {
        if (errorType != null) {
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

        if (exitCode == 0) {
            // Success but no explicit COMPLETED message - unusual
            logger.warn("Subprocess {} exited successfully but no completion message received",
                    handle.getTaskId());
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
                // Exit code 1 with isOomDetected=true means -XX:+ExitOnOutOfMemoryError triggered
                if (exitCode == 137) {
                    errorMessage = "Process killed (SIGKILL) - OOM killer";
                } else if (exitCode == 1 && handle.isOomDetected()) {
                    errorMessage = "Out of memory - JVM exited via -XX:+ExitOnOutOfMemoryError";
                } else {
                    errorMessage = "Out of memory";
                }
                failureReason = "OUT_OF_MEMORY";
                isOomFailure = true;
                logger.info("OOM failure confirmed for task {}: exitCode={}, oomDetected={}",
                        handle.getTaskId(), exitCode, handle.isOomDetected());
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
                    // Retry was started - don't complete the future yet
                    return;
                }
            }

            // No retry (or retry exhausted) - complete with failure
            handle.getResultFuture().complete(SubprocessHandle.SubprocessResult.failure(
                    handle.getTaskId(), exitCode, errorMessage, handle.getCurrentPhase(),
                    handle.isCancelled(), handle.isOomDetected(), handle.isGpuOomDetected()));

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
                        ai.kompile.app.ingest.domain.IndexingJobHistory.FailureReason.valueOf(failureReason));
            }

            // Cleanup job tracking on final failure
            String jobId = taskToJobId.remove(handle.getTaskId());
            if (jobId != null) {
                jobLaunchContexts.remove(jobId);
                jobRetryState.remove(jobId);
                // Keep checkpoint for debugging, but could delete here if desired
            }
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

        // Launch new subprocess asynchronously
        CompletableFuture.runAsync(() -> {
            try {
                // Small delay to allow cleanup
                Thread.sleep(1000);

                launchIngestInternal(
                        newTaskId,
                        jobId,
                        context.filePath(),
                        context.loaderName(),
                        context.chunkerName(),
                        context.originalOptions(),
                        newSettings
                );
            } catch (Exception e) {
                logger.error("Failed to launch retry subprocess for job {}: {}", jobId, e.getMessage(), e);
                // Complete the original future with failure
                context.resultFuture().complete(SubprocessHandle.SubprocessResult.failure(
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

        // Remove from warned task IDs
        warnedTaskIds.remove(handle.getTaskId());

        // Remove worker status tracking for this task
        taskWorkerStatuses.remove(handle.getTaskId());

        // === GPU LIFECYCLE: Release GPU resources for this ingest job ===
        if (modelLifecycleManager != null && modelLifecycleManager.hasJobGpuHold(handle.getTaskId())) {
            logger.info("[ingest-{}] Releasing GPU resources for completed/failed ingest job", handle.getTaskId());
            try {
                modelLifecycleManager.releaseGpuForIngest(handle.getTaskId());
            } catch (Exception e) {
                logger.warn("[ingest-{}] Error releasing GPU resources: {}", handle.getTaskId(), e.getMessage());
            }
        }

        // Delete args file
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
     * @param env The subprocess environment map to populate
     */
    private void propagateNd4jEnvironment(Map<String, String> env) {
        // List of ND4J-related environment variables to propagate
        List<String> nd4jEnvVars = List.of(
                // ND4J core settings
                "ND4J_BACKEND",
                "ND4J_DATA_BUFFER_OPS",
                "ND4J_RESOURCES_DIR",
                "ND4J_ALLOW_FALLBACK",

                // Thread configuration
                "OMP_NUM_THREADS",
                "MKL_NUM_THREADS",
                "OPENBLAS_NUM_THREADS",
                "GOTO_NUM_THREADS",
                "VECLIB_MAXIMUM_THREADS",
                "NUMEXPR_NUM_THREADS",

                // CUDA/GPU settings
                "CUDA_VISIBLE_DEVICES",
                "CUDA_DEVICE_ORDER",
                "CUDA_LAUNCH_BLOCKING",
                "CUDA_CACHE_PATH",

                // JavaCPP settings
                "JAVACPP_PLATFORM",
                "JAVACPP_CACHESFX",

                // Memory settings
                "ND4J_HEAP_SPACE",
                "ND4J_OFF_HEAP_SPACE",

                // Kompile-specific settings
                "KOMPILE_EMBEDDING_MODEL",
                "KOMPILE_INDEX_PATH",
                "KOMPILE_MODELS_DIR");

        int propagated = 0;
        for (String varName : nd4jEnvVars) {
            String value = System.getenv(varName);
            if (value != null && !value.isEmpty()) {
                env.put(varName, value);
                propagated++;
                logger.debug("Propagated env var to subprocess: {}={}", varName, value);
            }
        }

        // Also propagate any environment variables starting with ND4J_ or KOMPILE_
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            String key = entry.getKey();
            if ((key.startsWith("ND4J_") || key.startsWith("KOMPILE_")) && !env.containsKey(key)) {
                env.put(key, entry.getValue());
                propagated++;
                logger.debug("Propagated env var to subprocess: {}={}", key, entry.getValue());
            }
        }

        // Set OMP_NUM_THREADS to ND4J's maxThreads if not already set
        // This ensures OpenMP uses the same thread count as ND4J
        if (!env.containsKey("OMP_NUM_THREADS")) {
            try {
                int maxThreads = (int) org.nd4j.linalg.factory.Nd4j.getEnvironment().maxThreads();
                if (maxThreads > 0) {
                    env.put("OMP_NUM_THREADS", String.valueOf(maxThreads));
                    env.put("MKL_NUM_THREADS", String.valueOf(maxThreads));
                    env.put("OPENBLAS_NUM_THREADS", String.valueOf(maxThreads));
                    env.put("GOTO_NUM_THREADS", String.valueOf(maxThreads));
                    logger.info("Set OMP_NUM_THREADS={} from ND4J maxThreads", maxThreads);
                    propagated += 4;
                }
            } catch (Exception e) {
                logger.debug("Could not get ND4J maxThreads: {}", e.getMessage());
            }
        }

        logger.info("Propagated {} ND4J environment variables to subprocess", propagated);
    }

    /**
     * Scheduled task to check for stale subprocesses.
     */
    @Scheduled(fixedRateString = "${kompile.ingest.subprocess.stale-check-interval-ms:30000}")
    public void checkStaleProcesses() {
        int staleSeconds = getEffectiveStaleThresholdSeconds();
        Duration staleThreshold = Duration.ofSeconds(staleSeconds);

        for (SubprocessHandle handle : activeProcesses.values()) {
            if (handle.isAlive() && handle.isStale(staleThreshold)) {
                logger.warn("Subprocess {} appears stuck (no heartbeat for {} seconds), force killing",
                        handle.getTaskId(), staleSeconds);

                handle.cancel();

                // Update status with specific failure reason for stuck processes
                if (progressTracker != null) {
                    progressTracker.failTask(handle.getTaskId(), handle.getFileName(),
                            toProgressPhase(handle.getCurrentPhase()), "Process became unresponsive (no heartbeat)",
                            IngestProgressUpdate.FailureReason.PROCESS_STUCK);
                }
            }
        }
    }

    /**
     * Cancel all active subprocesses on shutdown.
     */
    @PreDestroy
    public void shutdownAll() {
        logger.info("Shutting down all active ingest subprocesses...");

        for (SubprocessHandle handle : activeProcesses.values()) {
            if (handle.isAlive()) {
                logger.info("Cancelling subprocess: {}", handle.getTaskId());
                handle.cancel();
            }

            // Release GPU hold for this job if held
            if (modelLifecycleManager != null && modelLifecycleManager.hasJobGpuHold(handle.getTaskId())) {
                logger.info("[ingest-{}] Releasing GPU resources during shutdown", handle.getTaskId());
                try {
                    modelLifecycleManager.releaseGpuForIngest(handle.getTaskId());
                } catch (Exception e) {
                    logger.warn("[ingest-{}] Error releasing GPU during shutdown: {}", handle.getTaskId(), e.getMessage());
                }
            }
        }

        // Wait for all to terminate
        for (SubprocessHandle handle : activeProcesses.values()) {
            handle.waitFor(Duration.ofSeconds(5));
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
