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
import ai.kompile.app.facts.domain.FactSheet;
import ai.kompile.app.facts.service.FactSheetService;
import ai.kompile.app.ingest.domain.IngestEvent;
import ai.kompile.app.ingest.service.IndexingJobHistoryService;
import ai.kompile.app.ingest.service.IngestEventService;
import ai.kompile.app.services.IngestProgressTracker;
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

    @Value("${kompile.ingest.subprocess.stale-threshold-seconds:120}")
    private int staleThresholdSeconds;

    @Value("${kompile.vectorstore.anserini.index-path:${user.home}/.kompile/anserini-vector-index}")
    private String keywordIndexPath;

    private final IngestProgressTracker progressTracker;
    private final IngestEventService eventService;
    private final IndexingJobHistoryService jobHistoryService;
    private final ServerPortService serverPortService;
    private final Nd4jEnvironmentConfigService nd4jEnvironmentConfigService;
    private final SubprocessConfigService subprocessConfigService;
    private final FactSheetService factSheetService;
    private final ObjectMapper objectMapper;

    // Active subprocess tracking
    private final Map<String, SubprocessHandle> activeProcesses = new ConcurrentHashMap<>();

    // Store worker statuses per task for inclusion in progress updates (parity with
    // in-process mode)
    private final Map<String, Map<String, SubprocessMessage.WorkerStatus>> taskWorkerStatuses = new ConcurrentHashMap<>();

    // Track tasks that have already logged the missing progressTracker warning (to
    // avoid spam)
    private final Set<String> warnedTaskIds = ConcurrentHashMap.newKeySet();

    @Autowired
    public SubprocessIngestLauncher(
            @Autowired(required = false) IngestProgressTracker progressTracker,
            @Autowired(required = false) IngestEventService eventService,
            @Autowired(required = false) IndexingJobHistoryService jobHistoryService,
            @Autowired(required = false) ServerPortService serverPortService,
            @Autowired(required = false) Nd4jEnvironmentConfigService nd4jEnvironmentConfigService,
            @Autowired(required = false) SubprocessConfigService subprocessConfigService,
            @Autowired(required = false) FactSheetService factSheetService) {
        this.progressTracker = progressTracker;
        this.eventService = eventService;
        this.jobHistoryService = jobHistoryService;
        this.serverPortService = serverPortService;
        this.nd4jEnvironmentConfigService = nd4jEnvironmentConfigService;
        this.subprocessConfigService = subprocessConfigService;
        this.factSheetService = factSheetService;
        this.objectMapper = new ObjectMapper();

        // Warn if progress tracking dependencies are missing
        if (progressTracker == null) {
            logger.warn("SubprocessIngestLauncher initialized WITHOUT IngestProgressTracker - " +
                    "UI will NOT receive real-time progress updates in subprocess mode!");
        } else {
            logger.info("SubprocessIngestLauncher initialized with progress tracking enabled");
        }
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
        logger.info("Launching ingest subprocess for task: {} file: {}", taskId, filePath);

        CompletableFuture<SubprocessHandle.SubprocessResult> resultFuture = new CompletableFuture<>();

        try {
            String fileName = filePath.getFileName().toString();

            String nd4jConfigJson = captureNd4jConfig();

            // Build subprocess args
            String callbackBaseUrl = serverPortService != null
                    ? serverPortService.getBaseUrl()
                    : "http://localhost:8080";

            SubprocessArgs args = SubprocessArgs.builder()
                    .taskId(taskId)
                    .filePath(filePath.toString())
                    .loaderName(loaderName)
                    .chunkerName(chunkerName)
                    .indexPath(keywordIndexPath)
                    .callbackBaseUrl(callbackBaseUrl)
                    .nd4jConfigJson(nd4jConfigJson)
                    .options(options != null ? options : Map.of())
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

            // Build command with per-request options
            List<String> command = buildCommand(argsFile, options);
            logger.info("Subprocess command: {}", String.join(" ", command));

            // Start process
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(false);

            // Propagate ND4J environment variables from parent process
            propagateNd4jEnvironment(processBuilder.environment());

            Process process = processBuilder.start();
            logger.info("Started subprocess with PID: {}", process.pid());

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
     * @param argsFile Path to the args file
     * @param options Per-request options (heapSize, timeoutMinutes, etc.)
     */
    private List<String> buildCommand(Path argsFile, Map<String, Object> options) {
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
            return Math.multiplyExact(heapBytes, 2L);
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
     * This method extracts URLs from the classloader hierarchy to handle cases where
     * Spring Boot or other frameworks use custom classloaders that don't expose their
     * classpath via java.class.path system property.
     *
     * When running via `mvn spring-boot:run`, the java.class.path may only contain
     * a small launcher JAR, while the actual application classes are loaded by
     * Spring Boot's RestartClassLoader or similar. This method traverses the classloader
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

        // 2. Extract URLs from classloader hierarchy (handles Spring Boot's classloaders)
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
                // Spring Boot RestartClassLoader and LaunchedURLClassLoader have getURLs() method
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

        // 3. Check for target/classes directories (important when running from IDE/Maven)
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
                if (line.contains("OutOfMemoryError") || line.contains("Java heap space")) {
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
                handle.updateHeartbeat();
                logger.debug("Task {} heartbeat: uptime={}ms, memory={}%",
                        handle.getTaskId(), heartbeat.uptimeMs(), heartbeat.memoryUsagePercent());
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
                handle.getResultFuture().complete(SubprocessHandle.SubprocessResult.failure(
                        handle.getTaskId(), 1, failed.errorMessage(), failed.phase(), false, false));
                // Forward failure to UI
                forwardFailure(handle, failed);
                taskWorkerStatuses.remove(handle.getTaskId());
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
            IngestProgressUpdate.IngestPhase phase = IngestProgressUpdate.IngestPhase.valueOf(progress.phase());

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
            // Uses same pattern as SubprocessRuntimeInfo.empty() but with processMode = "SUBPROCESS"
            IngestProgressUpdate.SubprocessRuntimeInfo minimalRuntimeInfo =
                    new IngestProgressUpdate.SubprocessRuntimeInfo(
                            null, null, "SUBPROCESS",  // processMode = SUBPROCESS
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
                // Subprocess-specific runtime info
                .subprocessRuntimeInfo(subprocessRuntimeInfo)
                .build();
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

        IngestProgressUpdate.EmbeddingBatchMetrics.Builder builder = IngestProgressUpdate.EmbeddingBatchMetrics
                .builder()
                .batchNumber(currentBatch)
                .totalBatches(totalBatches)
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
            if (currentBatch != null && totalBatches != null && totalBatches > 0) {
                double progress = (double) currentBatch / totalBatches;
                if (progress >= 0.9) {
                    builder.statusLevel("COMPLETING");
                } else if (progress >= 0.5) {
                    builder.statusLevel("PROCESSING");
                } else {
                    builder.statusLevel("RUNNING");
                }

                // Calculate ETA message
                if (subStats != null && subStats.chunksPerSecond() > 0) {
                    int remaining = totalBatches - currentBatch;
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

        // Add additional info from subprocess stats if available
        if (subStats != null) {
            builder.isBatched(true);

            // If we have runtime info with embedding model details, add them
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
     */
    private void forwardCompletion(SubprocessHandle handle, SubprocessMessage.Completed completed) {
        if (progressTracker == null) {
            logger.debug("Cannot forward completion: progressTracker is null");
            return;
        }

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

            progressTracker.completeTask(
                    handle.getTaskId(),
                    handle.getFileName(),
                    stats);
            logger.info("Forwarded completion to UI: task {} - {} docs, {} chunks indexed",
                    handle.getTaskId(), completed.documentsLoaded(), completed.documentsIndexed());
        } catch (Exception e) {
            logger.warn("Failed to forward completion for task {}: {}", handle.getTaskId(), e.getMessage(), e);
        }
    }

    /**
     * Forward failure to IngestProgressTracker for WebSocket broadcast.
     */
    private void forwardFailure(SubprocessHandle handle, SubprocessMessage.Failed failed) {
        if (progressTracker == null) {
            logger.debug("Cannot forward failure: progressTracker is null");
            return;
        }

        try {
            progressTracker.failTask(
                    handle.getTaskId(),
                    handle.getFileName(),
                    toProgressPhase(failed.phase()),
                    failed.errorMessage());
            logger.info("Forwarded failure to UI: task {} failed at phase {} - {}",
                    handle.getTaskId(), failed.phase(), failed.errorMessage());
        } catch (Exception e) {
            logger.warn("Failed to forward failure for task {}: {}", handle.getTaskId(), e.getMessage(), e);
        }
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

            if (handle.isCancelled()) {
                errorMessage = "Process cancelled";
                failureReason = "USER_CANCELLED";
            } else if (handle.isOomDetected()) {
                errorMessage = "Out of memory";
                failureReason = "OUT_OF_MEMORY";
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
            } else if (exitCode == 137) {
                // SIGKILL - killed (often OOM killer)
                errorMessage = "Process killed (SIGKILL) - likely OOM killer or manual termination";
                failureReason = "OUT_OF_MEMORY";
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

            // Log with appropriate level - native crashes are critical
            if (isNativeCrash) {
                logger.error("NATIVE CRASH in subprocess {} during phase {}: {} (exit code {}). " +
                        "This indicates a crash in ND4J or native libraries. " +
                        "The parent process is unaffected due to subprocess isolation.",
                        handle.getTaskId(), handle.getCurrentPhase(), errorMessage, exitCode);
            } else {
                logger.error("Subprocess {} failed: {} (exit code {})",
                        handle.getTaskId(), errorMessage, exitCode);
            }

            handle.getResultFuture().complete(SubprocessHandle.SubprocessResult.failure(
                    handle.getTaskId(), exitCode, errorMessage, handle.getCurrentPhase(),
                    handle.isCancelled(), handle.isOomDetected()));

            // Update progress tracker with detailed message
            if (progressTracker != null) {
                String uiMessage = isNativeCrash
                        ? "Native crash in embedding/indexing - see logs for details"
                        : errorMessage;

                if (handle.isCancelled()) {
                    progressTracker.cancelTask(handle.getTaskId(), handle.getFileName(),
                            toProgressPhase(handle.getCurrentPhase()), uiMessage, null);
                } else {
                    progressTracker.failTask(handle.getTaskId(), handle.getFileName(),
                            toProgressPhase(handle.getCurrentPhase()), uiMessage);
                }
            }

            // Update job history
            if (jobHistoryService != null) {
                jobHistoryService.markJobFailed(handle.getTaskId(), toEventPhase(handle.getCurrentPhase()),
                        errorMessage, null,
                        ai.kompile.app.ingest.domain.IndexingJobHistory.FailureReason.valueOf(failureReason));
            }
        }
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

                // Update status
                if (progressTracker != null) {
                    progressTracker.failTask(handle.getTaskId(), handle.getFileName(),
                            toProgressPhase(handle.getCurrentPhase()), "Process became unresponsive (no heartbeat)");
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
     * Handles common variations like "COMPLETE" -> "COMPLETED", "STARTING" -> "LOADING".
     */
    private String normalizePhase(String phase) {
        if (phase == null) return "QUEUED";
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
            default -> upper;
        };
    }
}
