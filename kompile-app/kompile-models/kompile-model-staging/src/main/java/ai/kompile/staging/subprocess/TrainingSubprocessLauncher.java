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

package ai.kompile.staging.subprocess;

import ai.kompile.app.subprocess.BackendConfigurable;
import ai.kompile.app.subprocess.SubprocessEnvironmentPropagator;
import ai.kompile.app.subprocess.SubprocessPlacement;
import ai.kompile.app.subprocess.SubprocessPlacementSupport;
import ai.kompile.app.subprocess.SubprocessProtocolChannel;
import ai.kompile.app.subprocess.SubprocessSignals;
import ai.kompile.cli.common.logs.AgentLogRecord;
import ai.kompile.cli.common.logs.SubprocessLogWriter;
import ai.kompile.staging.config.StagingPropertyKeys;
import ai.kompile.staging.domain.TrainingJobHistory;
import ai.kompile.staging.service.TrainingJobHistoryService;
import ai.kompile.staging.web.dto.DistillationConfigRequest;
import ai.kompile.staging.web.dto.TrainingConfigRequest;
import ai.kompile.core.staging.TrainingJobStatus;
import ai.kompile.staging.web.dto.TrainingLogEntry;
import ai.kompile.staging.web.dto.TrainingMetricsSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Launches and manages training subprocesses.
 * Follows the same pattern as SubprocessIngestLauncher but for training jobs.
 */
@Service
@ConditionalOnClass(name = "ai.kompile.staging.catalog.CatalogService")
// Training runs as a subprocess by default when the staging module is present.
// Enable/disable is kompile JSON managed-config (SubprocessConfigService), not a Spring property.
public class TrainingSubprocessLauncher implements ai.kompile.core.staging.TrainingSubprocessLauncherApi, BackendConfigurable {

    private static final Logger log = LoggerFactory.getLogger(TrainingSubprocessLauncher.class);

    /** How long the exit judge waits for the readers to hand over the child's last output lines. */
    private static final long OUTPUT_DRAIN_TIMEOUT_MS = 5_000;
    /** How long a timed-out child gets to exit once it has been killed. */
    private static final long KILL_EXIT_WAIT_SECONDS = 30;
    /** How long shutdown waits for the watchers to record their children's exits. */
    private static final long SHUTDOWN_JOIN_MS = 10_000;
    private static final String STALLED_ERROR = "Subprocess stalled (no heartbeat)";
    private static final String SHUTDOWN_REASON = "Application shutdown";

    /** Shared device-agnostic placement (same base infra every subprocess uses). */
    private final SubprocessPlacementSupport placement = new SubprocessPlacementSupport();

    /** {@link BackendConfigurable} — the scheduler assigns backend/device/memory before spawn. */
    @Override
    public void applyPlacement(SubprocessPlacement p) {
        this.placement.applyPlacement(p);
    }

    private final ObjectMapper objectMapper;
    private final TrainingJobHistoryService historyService;

    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;

    @Value(StagingPropertyKeys.MODELS_DIR_VALUE)
    private String modelsDir;

    @Value("${kompile.staging.training-jobs-dir:#{systemProperties['user.home'] + '/.kompile/training-jobs'}}")
    private String trainingJobsDir;

    @Value("${kompile.training.subprocess.heap-size:4g}")
    String subprocessHeapSize;

    @Value("${kompile.training.subprocess.stale-timeout-ms:120000}")
    long staleTimeoutMs;

    private final ConcurrentHashMap<String, SubprocessHandle> activeProcesses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<SseEmitter>> jobEmitters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<TrainingLogEntry>> jobLogs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<TrainingMetricsSnapshot>> jobMetrics = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TrainingJobStatus> jobStatuses = new ConcurrentHashMap<>();
    private final AtomicLong jobCounter = new AtomicLong(0);
    /** Orders spawns against shutdown, so no child can start after shutdown took its snapshot. */
    private final Object lifecycleLock = new Object();
    private volatile boolean shuttingDown;

    /** Main class of the training child; a field so a test can run a fake child. */
    String mainClass = "ai.kompile.staging.subprocess.TrainingSubprocessMain";
    /** How long a child may run before it is killed as timed out. */
    long maxRunMillis = TimeUnit.HOURS.toMillis(24);

    public TrainingSubprocessLauncher(ObjectMapper objectMapper,
                                       TrainingJobHistoryService historyService) {
        this.objectMapper = objectMapper;
        this.historyService = historyService;
    }

    /**
     * Launch a training job as a subprocess.
     */
    public TrainingJobStatus launchTraining(TrainingConfigRequest request) throws IOException {
        String jobId = "train-sub-" + jobCounter.incrementAndGet();
        TrainingSubprocessArgs args = createTrainingArgs(jobId, request);
        String peftType = request.getPeftConfig() != null ? request.getPeftConfig().getPeftType() : null;
        return launchSubprocess(args, peftType);
    }

    /**
     * Launch knowledge distillation through the same isolated process used by
     * fine-tuning and LoRA so dataset resolution and JSONL parsing are identical.
     */
    public TrainingJobStatus launchDistillation(DistillationConfigRequest request) throws IOException {
        String jobId = "distill-sub-" + jobCounter.incrementAndGet();
        TrainingSubprocessArgs args = createDistillationArgs(jobId, request);
        String peftType = request.getStudentPeftConfig() != null
                ? request.getStudentPeftConfig().getPeftType() : null;
        return launchSubprocess(args, peftType);
    }

    TrainingSubprocessArgs createTrainingArgs(String jobId, TrainingConfigRequest request) throws IOException {
        return TrainingSubprocessArgs.builder()
                .taskId(jobId)
                .trainingType(resolveTrainingType(request))
                .modelId(request.getModelId())
                .datasetId(request.getDatasetId())
                .epochs(request.getEpochs())
                .batchSize(request.getBatchSize())
                .learningRate(request.getUpdaterConfig() != null && request.getUpdaterConfig().getLearningRate() > 0
                        ? request.getUpdaterConfig().getLearningRate() : 1e-4)
                .lrSchedule(request.getLrSchedule())
                .warmupRatio(request.getWarmupRatio())
                .maxSteps(request.getMaxSteps())
                .maxGradNorm(request.getMaxGradNorm())
                .fp16(request.isFp16())
                .bf16(request.isBf16())
                .loggingSteps(request.getLoggingSteps())
                .saveSteps(request.getSaveSteps())
                .evalSteps(request.getEvalSteps())
                .outputDir(request.getOutputDir())
                .seed(request.getSeed())
                .gradientAccumulationSteps(request.getGradientAccumulationSteps())
                .peftConfigJson(request.getPeftConfig() != null
                        ? objectMapper.writeValueAsString(request.getPeftConfig()) : null)
                .updaterConfigJson(request.getUpdaterConfig() != null
                        ? objectMapper.writeValueAsString(request.getUpdaterConfig()) : null)
                .build();
    }

    TrainingSubprocessArgs createDistillationArgs(String jobId, DistillationConfigRequest request) throws IOException {
        requireText(request.getTeacherModelId(), "teacherModelId");
        requireText(request.getStudentModelId(), "studentModelId");
        String distillationType = hasText(request.getDistillationType())
                ? request.getDistillationType() : "LOGIT_KD";
        if (!"LOGIT_KD".equalsIgnoreCase(distillationType)) {
            throw new IllegalArgumentException(
                    "Only LOGIT_KD distillation is supported; requested " + distillationType);
        }
        if (request.getTemperature() <= 0.0) {
            throw new IllegalArgumentException("temperature must be positive");
        }
        if (Math.abs(request.getAlpha() - 1.0) > 1.0e-12) {
            throw new IllegalArgumentException(
                    "LOGIT_KD currently uses pure KL loss and requires alpha=1.0");
        }

        TrainingConfigRequest training = request.getTrainingConfig() != null
                ? request.getTrainingConfig() : TrainingConfigRequest.builder().build();
        String datasetId = hasText(request.getDatasetId()) ? request.getDatasetId() : training.getDatasetId();
        requireText(datasetId, "datasetId");

        return TrainingSubprocessArgs.builder()
                .taskId(jobId)
                .trainingType("DISTILLATION")
                .modelId(request.getStudentModelId())
                .datasetId(datasetId)
                .epochs(training.getEpochs())
                .batchSize(training.getBatchSize())
                .learningRate(training.getUpdaterConfig() != null
                        && training.getUpdaterConfig().getLearningRate() > 0
                        ? training.getUpdaterConfig().getLearningRate() : 1e-4)
                .lrSchedule(training.getLrSchedule())
                .warmupRatio(training.getWarmupRatio())
                .maxSteps(training.getMaxSteps())
                .maxGradNorm(training.getMaxGradNorm())
                .fp16(training.isFp16())
                .bf16(training.isBf16())
                .loggingSteps(training.getLoggingSteps())
                .saveSteps(training.getSaveSteps())
                .evalSteps(training.getEvalSteps())
                .outputDir(training.getOutputDir())
                .seed(training.getSeed())
                .gradientAccumulationSteps(training.getGradientAccumulationSteps())
                .peftConfigJson(request.getStudentPeftConfig() != null
                        ? objectMapper.writeValueAsString(request.getStudentPeftConfig()) : null)
                .updaterConfigJson(training.getUpdaterConfig() != null
                        ? objectMapper.writeValueAsString(training.getUpdaterConfig()) : null)
                .distillationConfigJson(objectMapper.writeValueAsString(request))
                .build();
    }

    TrainingJobStatus launchSubprocess(TrainingSubprocessArgs args, String peftType) throws IOException {
        String jobId = args.taskId();
        if (shuttingDown) {
            throw new LaunchRefusedException(jobId);
        }
        Path argsFile = args.writeToTempFile();

        TrainingJobStatus status = TrainingJobStatus.builder()
                .jobId(jobId)
                .status("QUEUED")
                .modelId(args.modelId())
                .datasetId(args.datasetId())
                .currentEpoch(0)
                .totalEpochs(args.epochs())
                .currentStep(0)
                .totalSteps(0)
                .loss(0.0)
                .learningRate(args.learningRate())
                .epochProgress(0.0)
                .overallProgress(0.0)
                .metrics(new LinkedHashMap<>())
                .startedAt(Instant.now().toString())
                .build();
        jobStatuses.put(jobId, status);
        jobLogs.put(jobId, new CopyOnWriteArrayList<>());
        jobMetrics.put(jobId, new CopyOnWriteArrayList<>());

        SubprocessHandle handle;
        try {
            TrainingJobHistory.TrainingType historyType = TrainingJobHistory.TrainingType.valueOf(
                    args.trainingType() != null ? args.trainingType().toUpperCase(Locale.ROOT) : "FINETUNE");
            historyService.createJob(jobId, historyType, args.modelId(), args.datasetId());
            historyService.updateTrainingParameters(jobId, args.batchSize(), args.lrSchedule(),
                    args.warmupRatio(), args.maxGradNorm(), args.fp16(), args.bf16(),
                    peftType, args.seed());

            List<String> command = buildCommand(argsFile);
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            propagateEnvironment(pb);
            // Reports get a pipe of their own, which native output written to fd 1 can't reach
            boolean wrapped = SubprocessProtocolChannel.apply(pb);

            log.info("Launching {} subprocess: jobId={}, model={}, dataset={}",
                    args.trainingType(), jobId, args.modelId(), args.datasetId());
            synchronized (lifecycleLock) {
                // Shutdown may have begun while the history row was written. A child spawned now
                // would miss shutdown's snapshot, and nothing would ever stop it.
                if (shuttingDown) {
                    throw new LaunchRefusedException(jobId);
                }
                Process process = pb.start();
                handle = new SubprocessHandle(process, jobId, argsFile, System.currentTimeMillis(),
                        SubprocessProtocolChannel.stderrProtocol(wrapped, TrainingSubprocessMessage.MESSAGE_PREFIX,
                                "training-" + jobId));
                handle.logWriter = openLogWriter(jobId, pb, command, process);
                activeProcesses.put(jobId, handle);
            }
        } catch (IOException | RuntimeException e) {
            abandonLaunch(jobId, argsFile, e);
            throw e;
        }

        markRunning(handle);
        startStdoutReader(handle);
        startStderrReader(handle);
        startCompletionWatcher(handle);

        log.info("Training subprocess launched: jobId={}, pid={}", jobId, handle.process.pid());
        return jobStatuses.get(jobId);
    }

    private SubprocessLogWriter openLogWriter(String jobId, ProcessBuilder pb, List<String> command,
                                              Process process) {
        try {
            String workingDir = pb.directory() != null
                    ? pb.directory().getAbsolutePath()
                    : System.getProperty("user.dir");
            SubprocessLogWriter logWriter = new SubprocessLogWriter("training", jobId, workingDir);
            logWriter.writeStart(new SubprocessLogWriter.SubprocessRunContext(
                    null, command, workingDir, process.pid(), subprocessHeapSize));
            return logWriter;
        } catch (Exception e) {
            log.debug("Failed to initialise SubprocessLogWriter for training job {}: {}", jobId, e.getMessage());
            return null;
        }
    }

    /**
     * Ends a launch that never produced a running child. Without this the job would stay QUEUED
     * forever and its args file would stay in the temp dir.
     */
    private void abandonLaunch(String jobId, Path argsFile, Exception cause) {
        deleteArgsFile(jobId, argsFile);
        TrainingJobStatus status;
        if (cause instanceof LaunchRefusedException) {
            String reason = SHUTDOWN_REASON + " before start";
            status = terminalStatus(jobId, "CANCELLED", null);
            recordHistory(jobId, "refused launch", () -> historyService.markCancelled(jobId, reason));
        } else {
            String error = "Failed to start training subprocess: "
                    + (cause.getMessage() != null ? cause.getMessage() : cause.toString());
            status = terminalStatus(jobId, "FAILED", error);
            recordHistory(jobId, "failed launch", () -> historyService.markFailed(jobId, error, cause,
                    TrainingJobHistory.FailureReason.IO_ERROR));
        }
        jobStatuses.put(jobId, status);
        emitToSse(jobId, "status", status);
        completeEmitters(jobId);
    }

    /** Moves a freshly spawned job to RUNNING, unless an outcome (an early cancel) came first. */
    private void markRunning(SubprocessHandle handle) {
        synchronized (handle) {
            if (handle.verdict != null) {
                return;
            }
            // Written under the handle's monitor, so it can never land after the job's outcome.
            recordHistory(handle.jobId, "start", () -> historyService.markRunning(handle.jobId));
            TrainingJobStatus current = jobStatuses.get(handle.jobId);
            if (current != null && "QUEUED".equals(current.getStatus())) {
                jobStatuses.put(handle.jobId, withState(current, "RUNNING", null));
            }
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void requireText(String value, String field) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    /**
     * Cancel a running training subprocess. Returns false when the job already has an outcome;
     * its watcher then finishes the run.
     */
    public boolean cancelTraining(String jobId) {
        SubprocessHandle handle = activeProcesses.get(jobId);
        if (handle == null) return false;

        String reason = "Cancelled by user";
        TrainingJobStatus cancelled = terminalStatus(jobId, "CANCELLED", null);
        if (!claimVerdict(handle, cancelled, reason)) {
            return false;
        }
        log.info("Cancelling training subprocess: {}", jobId);
        recordHistory(jobId, "cancellation", () -> historyService.markCancelled(jobId, reason));
        emitToSse(jobId, "status", cancelled);
        // Killed through its handle, which leaves the pipes open: what the child wrote before it died is still
        // read and logged. The watcher sees the exit, then closes the log, deletes the args file and ends the
        // SSE streams.
        SubprocessSignals.kill(handle.process);
        return true;
    }

    /**
     * Get current status of a training job.
     */
    public TrainingJobStatus getJobStatus(String jobId) {
        return jobStatuses.get(jobId);
    }

    /**
     * Get all job statuses.
     */
    public List<TrainingJobStatus> getAllJobStatuses() {
        return new ArrayList<>(jobStatuses.values());
    }

    /**
     * Subscribe to live log stream for a training job.
     */
    public SseEmitter subscribeToJobLogs(String jobId) {
        SseEmitter emitter = new SseEmitter(300000L);
        jobEmitters.computeIfAbsent(jobId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(jobId, emitter));
        emitter.onTimeout(() -> removeEmitter(jobId, emitter));
        emitter.onError(e -> removeEmitter(jobId, emitter));

        // Send existing logs
        List<TrainingLogEntry> existing = jobLogs.get(jobId);
        if (existing != null) {
            for (TrainingLogEntry entry : existing) {
                try { emitter.send(SseEmitter.event().name("log").data(entry)); } catch (IOException e) { break; }
            }
        }

        List<TrainingMetricsSnapshot> existingMetrics = jobMetrics.get(jobId);
        if (existingMetrics != null) {
            for (TrainingMetricsSnapshot s : existingMetrics) {
                try { emitter.send(SseEmitter.event().name("metrics").data(s)); } catch (IOException e) { break; }
            }
        }

        return emitter;
    }

    public List<TrainingLogEntry> getJobLogs(String jobId) {
        return jobLogs.getOrDefault(jobId, Collections.emptyList());
    }

    public List<TrainingMetricsSnapshot> getJobMetrics(String jobId) {
        return jobMetrics.getOrDefault(jobId, Collections.emptyList());
    }

    // ==================== Internal Methods ====================

    private List<String> buildCommand(Path argsFile) {
        // JVM classpath mode
        String javaPath = ProcessHandle.current().info().command().orElse("java");
        String classpath = System.getProperty("java.class.path");

        List<String> command = new ArrayList<>();
        command.add(javaPath);
        command.add("-Xmx" + subprocessHeapSize);
        command.add("-XX:+UseG1GC");
        command.add("-XX:MaxGCPauseMillis=200");
        command.add("-XX:+ExitOnOutOfMemoryError");
        command.add("-Dfile.encoding=UTF-8");
        // Device-agnostic backend/device selection from the shared base infra — no CUDA_VISIBLE_DEVICES.
        command.addAll(placement.jvmFlags());
        command.add("-cp");
        command.add(classpath);
        command.add(mainClass);
        command.add(argsFile.toString());
        return command;
    }

    private void propagateEnvironment(ProcessBuilder pb) {
        SubprocessEnvironmentPropagator.propagateToEnvironment(pb.environment());
        // Device-agnostic per-device memory bound (SD_MAX_DEVICE_BYTES) — shared base infra.
        placement.applyEnv(pb.environment());
    }

    private void startStdoutReader(SubprocessHandle handle) {
        String jobId = handle.jobId;
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(handle.process.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    // A child started without the protocol channel shares this pipe with native code, which
                    // writes to fd 1 beneath its System.setOut redirect, so a native message without a newline
                    // can precede a report on the same line.
                    int prefixAt = line.indexOf(TrainingSubprocessMessage.MESSAGE_PREFIX);
                    if (prefixAt > 0) {
                        noteOutput(handle, line.substring(0, prefixAt));
                    }
                    if (prefixAt >= 0) {
                        String json = line.substring(prefixAt + TrainingSubprocessMessage.MESSAGE_PREFIX.length());
                        handleMessage(jobId, json);
                    } else {
                        noteOutput(handle, line);
                    }
                    // Write to centralized log aggregation store
                    writeLogLine(handle, AgentLogRecord.Stream.STDOUT, line);
                }
            } catch (Exception e) {
                log.warn("Error reading training subprocess stdout for '{}'", jobId, e);
            }
        }, "training-stdout-" + jobId);
        reader.setDaemon(true);
        handle.stdoutReader = reader;
        reader.start();
    }

    private void startStderrReader(SubprocessHandle handle) {
        String jobId = handle.jobId;
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(handle.process.getErrorStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    // A child that writes its reports to fd 1 has them here, behind whatever else reached fd 1
                    int prefixAt = handle.stderrProtocol.prefixIndex(line);
                    if (prefixAt > 0) {
                        noteError(handle, line.substring(0, prefixAt));
                    }
                    if (prefixAt >= 0) {
                        String json = line.substring(prefixAt + TrainingSubprocessMessage.MESSAGE_PREFIX.length());
                        handleMessage(jobId, json);
                    } else {
                        noteError(handle, line);
                    }
                    // Write to centralized log aggregation store
                    writeLogLine(handle, AgentLogRecord.Stream.STDERR, line);
                }
            } catch (Exception e) {
                log.warn("Error reading training subprocess stderr for '{}'", jobId, e);
            }
        }, "training-stderr-" + jobId);
        reader.setDaemon(true);
        handle.stderrReader = reader;
        reader.start();
    }

    /** A line of stdout that is not a report. */
    private static void noteOutput(SubprocessHandle handle, String line) {
        // HotSpot prints its ExitOnOutOfMemoryError notice on fd 1: here for a child started without the
        // protocol channel, on stderr otherwise.
        noteOutOfMemory(handle, line);
        log.trace("[training-{}] stdout: {}", handle.jobId, line);
    }

    /** A line of stderr that is not a report. */
    private static void noteError(SubprocessHandle handle, String line) {
        log.debug("[training-{}] stderr: {}", handle.jobId, line);
        noteOutOfMemory(handle, line);
    }

    /**
     * Out-of-memory evidence only. A line decides nothing on its own: the child may recover, or report
     * its own failure. {@link #judgeExit} weighs it when the child exits without an outcome.
     */
    private static void noteOutOfMemory(SubprocessHandle handle, String line) {
        if (line.contains("OutOfMemoryError") || line.contains("Cannot allocate")) {
            handle.oomSeen = true;
        }
    }

    private static void writeLogLine(SubprocessHandle handle, AgentLogRecord.Stream stream, String line) {
        SubprocessLogWriter writer = handle.logWriter;
        if (writer == null) {
            return;
        }
        try {
            writer.writeLine(stream, line);
        } catch (Exception ex) {
            log.debug("SubprocessLogWriter {} write failed for {}: {}", stream, handle.jobId, ex.getMessage());
        }
    }

    private void startCompletionWatcher(SubprocessHandle handle) {
        Thread watcher = new Thread(() -> watch(handle), "training-watcher-" + handle.jobId);
        watcher.setDaemon(true);
        handle.watcher = watcher;
        watcher.start();
    }

    /** Waits for the child to exit, gives the job an outcome if it has none yet, then ends the run. */
    private void watch(SubprocessHandle handle) {
        String jobId = handle.jobId;
        Process process = handle.process;
        Integer exitCode = null;
        try {
            if (!process.waitFor(maxRunMillis, TimeUnit.MILLISECONDS)) {
                String error = "Subprocess timed out after " + formatLimit(maxRunMillis);
                log.warn("Training subprocess {}: {}, destroying", jobId, error);
                TrainingJobStatus failed = terminalStatus(jobId, "FAILED", error);
                if (claimVerdict(handle, failed, error)) {
                    recordHistory(jobId, "timeout", () -> historyService.markFailed(jobId, error, null,
                            TrainingJobHistory.FailureReason.TIMEOUT));
                    emitToSse(jobId, "status", failed);
                }
                SubprocessSignals.kill(process);
                process.waitFor(KILL_EXIT_WAIT_SECONDS, TimeUnit.SECONDS);
            }
            if (process.isAlive()) {
                log.warn("Training subprocess {} is still alive {}s after it was killed", jobId, KILL_EXIT_WAIT_SECONDS);
            } else {
                exitCode = process.exitValue();
                log.info("Training subprocess {} exited with code {}", jobId, exitCode);
            }
            // Its last lines (a FAILED report, an out-of-memory notice) may still be in the pipes.
            awaitOutputReaders(handle);
            judgeExit(handle, exitCode);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("Training subprocess watcher failed for {}", jobId, e);
        } finally {
            finalizeRun(handle, exitCode);
        }
    }

    /** Waits, boundedly, for both readers to reach end of stream, so every line the child wrote is handled. */
    private static void awaitOutputReaders(SubprocessHandle handle) throws InterruptedException {
        long deadline = System.currentTimeMillis() + OUTPUT_DRAIN_TIMEOUT_MS;
        for (Thread reader : new Thread[] {handle.stdoutReader, handle.stderrReader}) {
            if (reader == null) {
                continue;
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining > 0) {
                reader.join(remaining);
            }
            if (reader.isAlive()) {
                // A grandchild can hold the pipe open after the child is gone.
                log.warn("Output of training subprocess {} still open {} ms after its exit; judging without it",
                        handle.jobId, OUTPUT_DRAIN_TIMEOUT_MS);
                return;
            }
        }
    }

    /** Gives a job whose child exited without an outcome of its own the one its exit implies. */
    private void judgeExit(SubprocessHandle handle, Integer exitCode) {
        if (handle.verdict != null) {
            return;
        }
        String jobId = handle.jobId;
        boolean clean = exitCode != null && exitCode == 0;
        if (!clean && handle.oomSeen) {
            String error = "Subprocess ran out of memory (exit code " + exitCode + ")";
            TrainingJobStatus killed = terminalStatus(jobId, "MEMORY_KILLED", error);
            if (claimVerdict(handle, killed, error)) {
                recordHistory(jobId, "memory kill", () -> historyService.markMemoryKilled(jobId, 100.0));
                emitToSse(jobId, "status", killed);
            }
            return;
        }
        // Exit 0 without a COMPLETED report: no trained model was reported either.
        String error = clean
                ? "Subprocess exited without reporting completion"
                : "Subprocess exited with code " + exitCode;
        TrainingJobStatus failed = terminalStatus(jobId, "FAILED", error);
        if (claimVerdict(handle, failed, error)) {
            recordHistory(jobId, "exit", () -> historyService.markFailed(jobId, error, null,
                    TrainingJobHistory.FailureReason.TRAINING_ERROR));
            emitToSse(jobId, "status", failed);
        }
    }

    /** Ends a run exactly once: unregisters it, closes its log, deletes its args file, ends its SSE streams. */
    private void finalizeRun(SubprocessHandle handle, Integer exitCode) {
        if (!handle.finalized.compareAndSet(false, true)) {
            return;
        }
        String jobId = handle.jobId;
        // Unregistered first: a message still in flight after this is dropped, not applied to an ended run.
        activeProcesses.remove(jobId, handle);
        TrainingJobStatus verdict = handle.verdict;
        String outcome = verdict != null ? verdict.getStatus() : "FAILED";
        // Finalise the centralized log aggregation entry
        if (handle.logWriter != null) {
            try {
                String state = switch (outcome) {
                    case "COMPLETED", "CANCELLED" -> outcome;
                    case "MEMORY_KILLED" -> "OOM";
                    default -> "FAILED";
                };
                handle.logWriter.writeEnd(new SubprocessLogWriter.SubprocessRunResult(
                        state, exitCode, handle.verdictReason, "MEMORY_KILLED".equals(outcome), false));
                handle.logWriter.close();
            } catch (Exception ex) {
                log.debug("SubprocessLogWriter writeEnd failed for {}: {}", jobId, ex.getMessage());
            }
        }
        deleteArgsFile(jobId, handle.argsFile);
        completeEmitters(jobId);
    }

    private void handleMessage(String jobId, String json) {
        try {
            TrainingSubprocessMessage message = objectMapper.readValue(json, TrainingSubprocessMessage.class);

            if (message instanceof TrainingSubprocessMessage.Progress p) {
                handleProgress(jobId, p);
            } else if (message instanceof TrainingSubprocessMessage.Heartbeat h) {
                handleHeartbeat(jobId, h);
            } else if (message instanceof TrainingSubprocessMessage.Completed c) {
                handleCompleted(jobId, c);
            } else if (message instanceof TrainingSubprocessMessage.Failed f) {
                handleFailed(jobId, f);
            } else if (message instanceof TrainingSubprocessMessage.MetricsUpdate m) {
                handleMetrics(jobId, m);
            } else if (message instanceof TrainingSubprocessMessage.Log l) {
                handleLog(jobId, l);
            } else if (message instanceof TrainingSubprocessMessage.CheckpointSaved cs) {
                handleCheckpoint(jobId, cs);
            } else if (message instanceof TrainingSubprocessMessage.PhaseTransition pt) {
                handlePhaseTransition(jobId, pt);
            }
        } catch (Exception e) {
            log.warn("Failed to parse training message for '{}'", jobId, e);
        }
    }

    private boolean rejectNonFiniteMap(String jobId, String phase, Map<String, Double> metrics) {
        if (metrics == null) {
            return false;
        }
        for (Map.Entry<String, Double> entry : metrics.entrySet()) {
            Double value = entry.getValue();
            if (value == null) {
                rejectTrainingMessage(jobId, phase, "metric '" + entry.getKey() + "' is null");
                return true;
            }
            if (rejectNonFinite(jobId, phase, entry.getKey(), value)) {
                return true;
            }
        }
        return false;
    }

    private boolean rejectNonFinite(String jobId, String phase, String field, double value) {
        if (Double.isFinite(value)) {
            return false;
        }
        rejectTrainingMessage(jobId, phase, "metric '" + field + "' is non-finite: " + value);
        return true;
    }

    private void rejectTrainingMessage(String jobId, String phase, String reason) {
        String message = "Rejected training subprocess " + phase + " message: " + reason;
        log.warn("{}", message);
        boolean decided = handleFailed(jobId, new TrainingSubprocessMessage.Failed(
                jobId, phase, message, "NON_FINITE_TRAINING_METRIC", null));
        SubprocessHandle handle = activeProcesses.get(jobId);
        if (decided && handle != null) {
            // The job has failed; a child that kept training would only hold its GPU.
            SubprocessSignals.kill(handle.process);
        }
    }

    private void handleProgress(String jobId, TrainingSubprocessMessage.Progress p) {
        if (rejectNonFinite(jobId, "PROGRESS", "loss", p.loss())
                || rejectNonFinite(jobId, "PROGRESS", "learningRate", p.learningRate())
                || rejectNonFinite(jobId, "PROGRESS", "epochProgress", p.epochProgress())
                || rejectNonFinite(jobId, "PROGRESS", "overallProgress", p.overallProgress())) {
            return;
        }
        SubprocessHandle handle = activeProcesses.get(jobId);
        if (handle == null) {
            log.debug("Dropping progress for ended training job {}", jobId);
            return;
        }
        TrainingJobStatus updated;
        synchronized (handle) {
            // An outcome is final: progress still in flight must not reopen the job.
            TrainingJobStatus current = jobStatuses.get(jobId);
            if (handle.verdict != null || current == null) return;

            updated = TrainingJobStatus.builder()
                    .jobId(jobId)
                    .status("TRAINING")
                    .modelId(current.getModelId())
                    .datasetId(current.getDatasetId())
                    .currentEpoch(p.epoch())
                    .totalEpochs(p.totalEpochs())
                    .currentStep(p.step())
                    .totalSteps(current.getTotalSteps())
                    .loss(p.loss())
                    .learningRate(p.learningRate())
                    .epochProgress(p.epochProgress())
                    .overallProgress(p.overallProgress())
                    .metrics(current.getMetrics())
                    .startedAt(current.getStartedAt())
                    .elapsedMs(System.currentTimeMillis() - Instant.parse(current.getStartedAt()).toEpochMilli())
                    .build();
            jobStatuses.put(jobId, updated);

            // Update persistent history periodically (every 50 steps to reduce DB writes).
            // Under the handle's monitor: the history save rewrites the whole row, so a progress
            // save that landed after the outcome's would put the job back to RUNNING.
            if (p.step() % 50 == 0) {
                recordHistory(jobId, "progress", () -> historyService.updateProgress(
                        jobId, p.epoch(), p.step(), p.loss(), p.learningRate()));
            }
        }

        if (handle.verdict == null) {
            emitToSse(jobId, "status", updated);
        }
    }

    private void handleHeartbeat(String jobId, TrainingSubprocessMessage.Heartbeat h) {
        SubprocessHandle handle = activeProcesses.get(jobId);
        if (handle != null) {
            handle.lastHeartbeatMs = System.currentTimeMillis();
        }
    }

    private void handleCompleted(String jobId, TrainingSubprocessMessage.Completed c) {
        if (rejectNonFinite(jobId, "COMPLETED", "finalLoss", c.finalLoss())
                || rejectNonFinite(jobId, "COMPLETED", "finalEvalLoss", c.finalEvalLoss())
                || rejectNonFiniteMap(jobId, "COMPLETED", c.finalMetrics())) {
            return;
        }
        SubprocessHandle handle = activeProcesses.get(jobId);
        if (handle == null) {
            log.debug("Dropping completion report for ended training job {}", jobId);
            return;
        }
        TrainingJobStatus current = jobStatuses.get(jobId);
        TrainingJobStatus completed = TrainingJobStatus.builder()
                .jobId(jobId)
                .status("COMPLETED")
                .modelId(current != null ? current.getModelId() : "")
                .datasetId(current != null ? current.getDatasetId() : "")
                .currentEpoch(c.totalEpochs())
                .totalEpochs(c.totalEpochs())
                .currentStep(c.totalSteps())
                .totalSteps(c.totalSteps())
                .loss(c.finalLoss())
                .learningRate(0.0)
                .epochProgress(1.0)
                .overallProgress(1.0)
                .metrics(c.finalMetrics())
                .startedAt(current != null ? current.getStartedAt() : Instant.now().toString())
                .completedAt(Instant.now().toString())
                .elapsedMs(c.totalDurationMs())
                .outputModelPath(c.outputPath())
                .build();
        if (!claimVerdict(handle, completed, null)) {
            log.info("Ignoring completion report for training job {}: already {}",
                    jobId, handle.verdict.getStatus());
            return;
        }
        recordHistory(jobId, "completion", () -> historyService.markCompleted(
                jobId, c.finalLoss(), c.finalEvalLoss(), c.totalSteps(), c.outputPath()));
        emitToSse(jobId, "status", completed);
        log.info("Training subprocess completed: jobId={}, finalLoss={}", jobId, c.finalLoss());
    }

    /**
     * Fails the job with the child's own report, unless the job already has an outcome.
     *
     * @return true when this report became the job's outcome
     */
    private boolean handleFailed(String jobId, TrainingSubprocessMessage.Failed f) {
        SubprocessHandle handle = activeProcesses.get(jobId);
        if (handle == null) {
            log.debug("Dropping failure report for ended training job {}: {}", jobId, f.errorMessage());
            return false;
        }
        TrainingJobStatus failed = terminalStatus(jobId, "FAILED", f.errorMessage());
        if (!claimVerdict(handle, failed, f.errorMessage())) {
            log.info("Ignoring failure report for training job {}: already {}: {}",
                    jobId, handle.verdict.getStatus(), f.errorMessage());
            return false;
        }
        recordHistory(jobId, "failure", () -> historyService.markFailed(
                jobId, f.errorMessage(), null, TrainingJobHistory.FailureReason.TRAINING_ERROR));
        emitToSse(jobId, "status", failed);
        return true;
    }

    private void handleMetrics(String jobId, TrainingSubprocessMessage.MetricsUpdate m) {
        if (rejectNonFinite(jobId, "METRICS", "trainLoss", m.trainLoss())
                || rejectNonFinite(jobId, "METRICS", "evalLoss", m.evalLoss())
                || rejectNonFinite(jobId, "METRICS", "learningRate", m.learningRate())
                || rejectNonFinite(jobId, "METRICS", "gradNorm", m.gradNorm())
                || rejectNonFinite(jobId, "METRICS", "tokensPerSecond", m.tokensPerSecond())
                || rejectNonFinite(jobId, "METRICS", "samplesPerSecond", m.samplesPerSecond())
                || rejectNonFinite(jobId, "METRICS", "heapUsagePercent", m.heapUsagePercent())
                || rejectNonFiniteMap(jobId, "METRICS", m.customMetrics())) {
            return;
        }
        TrainingMetricsSnapshot snapshot = TrainingMetricsSnapshot.builder()
                .step(m.step())
                .epoch(m.epoch())
                .trainLoss(m.trainLoss())
                .evalLoss(m.evalLoss())
                .learningRate(m.learningRate())
                .tokensPerSecond(m.tokensPerSecond())
                .samplesPerSecond(m.samplesPerSecond())
                .customMetrics(m.customMetrics())
                // Per-segment execution breakdown
                .dspSegmentsWarmup(m.dspSegmentsWarmup())
                .dspSegmentsReplayed(m.dspSegmentsReplayed())
                .dspSegmentsCaptured(m.dspSegmentsCaptured())
                .dspSegmentsSlotBySlot(m.dspSegmentsSlotBySlot())
                .dspSegmentsFailed(m.dspSegmentsFailed())
                // Buffer pool stats
                .dspBufferPoolBytes(m.dspBufferPoolBytes())
                .dspBufferPoolReused(m.dspBufferPoolReused())
                .dspColoringSavedBytes(m.dspColoringSavedBytes())
                // GPU memory
                .gpuMemUsedBytes(m.gpuMemUsedBytes())
                .gpuMemFreeBytes(m.gpuMemFreeBytes())
                .gpuMemTotalBytes(m.gpuMemTotalBytes())
                .gpuPoolUsedBytes(m.gpuPoolUsedBytes())
                .gpuPoolReservedBytes(m.gpuPoolReservedBytes())
                .numGpuDevices(m.numGpuDevices())
                .gpuDeviceNames(m.gpuDeviceNames())
                // JVM heap
                .heapUsedBytes(m.heapUsedBytes())
                .heapMaxBytes(m.heapMaxBytes())
                .heapUsagePercent(m.heapUsagePercent())
                .build();

        List<TrainingMetricsSnapshot> metrics = jobMetrics.computeIfAbsent(jobId, k -> new CopyOnWriteArrayList<>());
        metrics.add(snapshot);
        emitToSse(jobId, "metrics", snapshot);
    }

    private void handleLog(String jobId, TrainingSubprocessMessage.Log l) {
        TrainingLogEntry entry = TrainingLogEntry.builder()
                .timestamp(Instant.ofEpochMilli(l.timestamp()).toString())
                .level(l.level())
                .message(l.message())
                .build();

        List<TrainingLogEntry> logs = jobLogs.computeIfAbsent(jobId, k -> new CopyOnWriteArrayList<>());
        logs.add(entry);
        emitToSse(jobId, "log", entry);
    }

    private void handleCheckpoint(String jobId, TrainingSubprocessMessage.CheckpointSaved cs) {
        log.info("Training checkpoint saved: jobId={}, step={}, path={}", jobId, cs.step(), cs.checkpointPath());
        TrainingLogEntry entry = TrainingLogEntry.builder()
                .timestamp(Instant.now().toString())
                .level("INFO")
                .message("Checkpoint saved at step " + cs.step() + ": " + cs.checkpointPath())
                .build();
        List<TrainingLogEntry> logs = jobLogs.computeIfAbsent(jobId, k -> new CopyOnWriteArrayList<>());
        logs.add(entry);
        emitToSse(jobId, "log", entry);
    }

    private void handlePhaseTransition(String jobId, TrainingSubprocessMessage.PhaseTransition pt) {
        log.debug("Training phase transition: jobId={}, {} -> {}", jobId, pt.fromPhase(), pt.toPhase());
        // Publish event for scheduler bridge to forward to ResourceAwareJobScheduler
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new ai.kompile.core.staging.TrainingPhaseTransitionEvent(
                    this, jobId, pt.fromPhase(), pt.toPhase()));
        }
    }

    /** A copy of {@code current} in another state; keeps the progress the job had reached. */
    private static TrainingJobStatus withState(TrainingJobStatus current, String state, String error) {
        return TrainingJobStatus.builder()
                .jobId(current.getJobId())
                .status(state)
                .modelId(current.getModelId())
                .datasetId(current.getDatasetId())
                .currentEpoch(current.getCurrentEpoch())
                .totalEpochs(current.getTotalEpochs())
                .currentStep(current.getCurrentStep())
                .totalSteps(current.getTotalSteps())
                .loss(current.getLoss())
                .learningRate(current.getLearningRate())
                .epochProgress(current.getEpochProgress())
                .overallProgress(current.getOverallProgress())
                .metrics(current.getMetrics())
                .startedAt(current.getStartedAt())
                .completedAt(current.getCompletedAt())
                .elapsedMs(current.getElapsedMs())
                .outputModelPath(current.getOutputModelPath())
                .error(error)
                .build();
    }

    /** The job's current status moved to a final state, stamped with when it ended and how long it ran. */
    private TrainingJobStatus terminalStatus(String jobId, String state, String error) {
        TrainingJobStatus current = jobStatuses.get(jobId);
        TrainingJobStatus status = current != null
                ? withState(current, state, error)
                : TrainingJobStatus.builder().jobId(jobId).status(state).error(error).build();
        Instant now = Instant.now();
        status.setCompletedAt(now.toString());
        if (status.getStartedAt() != null) {
            status.setElapsedMs(now.toEpochMilli() - Instant.parse(status.getStartedAt()).toEpochMilli());
        }
        return status;
    }

    /**
     * Makes {@code status} the job's outcome unless it already has one. Exactly one caller wins, and
     * only the winner writes history and tells SSE subscribers, so no job is reported twice.
     *
     * @param reason why the run ended, for the subprocess log; null for a completion
     */
    private boolean claimVerdict(SubprocessHandle handle, TrainingJobStatus status, String reason) {
        synchronized (handle) {
            if (handle.verdict != null) {
                return false;
            }
            handle.verdict = status;
            handle.verdictReason = reason;
            jobStatuses.put(handle.jobId, status);
            return true;
        }
    }

    /** History is a record, not a gate: a failed write must never stop a kill or a cleanup. */
    private static void recordHistory(String jobId, String what, Runnable write) {
        try {
            write.run();
        } catch (RuntimeException e) {
            log.warn("Could not record the {} of training job {} in history: {}", what, jobId, e.toString());
        }
    }

    private static void deleteArgsFile(String jobId, Path argsFile) {
        try {
            Files.deleteIfExists(argsFile);
        } catch (IOException e) {
            log.debug("Could not delete the args file of training job {}: {}", jobId, e.getMessage());
        }
    }

    private static String formatLimit(long millis) {
        long hour = TimeUnit.HOURS.toMillis(1);
        return millis % hour == 0 ? millis / hour + " hours" : millis + " ms";
    }

    private void emitToSse(String jobId, String eventName, Object data) {
        List<SseEmitter> emitters = jobEmitters.get(jobId);
        if (emitters == null) return;
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
            } catch (Exception e) {
                emitters.remove(emitter);
            }
        }
    }

    private void completeEmitters(String jobId) {
        List<SseEmitter> emitters = jobEmitters.get(jobId);
        if (emitters != null) {
            for (SseEmitter emitter : emitters) {
                try { emitter.complete(); }
                catch (Exception e) { log.debug("Failed to complete training job SSE emitter for job {}: {}", jobId, e.getMessage()); }
            }
            emitters.clear();
        }
    }

    private void removeEmitter(String jobId, SseEmitter emitter) {
        List<SseEmitter> emitters = jobEmitters.get(jobId);
        if (emitters != null) emitters.remove(emitter);
    }

    private String resolveTrainingType(TrainingConfigRequest request) {
        if (request.getPeftConfig() != null && request.getPeftConfig().getPeftType() != null) {
            String peftType = request.getPeftConfig().getPeftType().toUpperCase();
            if (peftType.contains("LORA")) return "LORA";
        }
        return "FINETUNE";
    }

    /**
     * Detect stale subprocesses that have stopped sending heartbeats.
     */
    @Scheduled(fixedDelayString = "${kompile.training.subprocess.stale-check-ms:30000}")
    public void checkForStaleProcesses() {
        long now = System.currentTimeMillis();
        for (SubprocessHandle handle : activeProcesses.values()) {
            long silentMs = now - handle.lastHeartbeatMs;
            // A child that already exited is its watcher's to judge.
            if (silentMs <= staleTimeoutMs || !handle.process.isAlive()) {
                continue;
            }
            log.warn("Training subprocess {} appears stale (no heartbeat for {}ms), force killing",
                    handle.jobId, silentMs);
            TrainingJobStatus failed = terminalStatus(handle.jobId, "FAILED", STALLED_ERROR);
            if (claimVerdict(handle, failed, STALLED_ERROR)) {
                recordHistory(handle.jobId, "stall", () -> historyService.markFailed(handle.jobId,
                        STALLED_ERROR, null, TrainingJobHistory.FailureReason.TIMEOUT));
                emitToSse(handle.jobId, "status", failed);
            }
            // Killed even when it already has an outcome: a hung child must not keep its GPU.
            SubprocessSignals.kill(handle.process);
        }
    }

    @PreDestroy
    public void shutdown() {
        List<SubprocessHandle> handles;
        synchronized (lifecycleLock) {
            shuttingDown = true;
            handles = new ArrayList<>(activeProcesses.values());
        }
        log.info("Shutting down training subprocess launcher, cancelling {} active processes", handles.size());
        for (SubprocessHandle handle : handles) {
            TrainingJobStatus cancelled = terminalStatus(handle.jobId, "CANCELLED", null);
            if (claimVerdict(handle, cancelled, SHUTDOWN_REASON)) {
                recordHistory(handle.jobId, "shutdown",
                        () -> historyService.markCancelled(handle.jobId, SHUTDOWN_REASON));
                emitToSse(handle.jobId, "status", cancelled);
            }
            SubprocessSignals.kill(handle.process);
        }
        // Each watcher records its child's exit; a run still open after the wait is ended here.
        long deadline = System.currentTimeMillis() + SHUTDOWN_JOIN_MS;
        for (SubprocessHandle handle : handles) {
            long remaining = deadline - System.currentTimeMillis();
            Thread watcher = handle.watcher;
            if (watcher == null || remaining <= 0) {
                continue;
            }
            try {
                watcher.join(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        for (SubprocessHandle handle : handles) {
            finalizeRun(handle, handle.process.isAlive() ? null : handle.process.exitValue());
        }
    }

    /**
     * Tracks a running subprocess.
     */
    private static class SubprocessHandle {
        final Process process;
        final String jobId;
        final Path argsFile;
        final long startTimeMs;
        /** Finds the reports of a child that writes them to fd 1, which reaches stderr. */
        final SubprocessProtocolChannel.StderrProtocol stderrProtocol;
        final AtomicBoolean finalized = new AtomicBoolean();
        volatile long lastHeartbeatMs;
        volatile SubprocessLogWriter logWriter;
        /** The job's outcome; set once, under this handle's monitor (see claimVerdict). */
        volatile TrainingJobStatus verdict;
        volatile String verdictReason;
        /** The child printed an out-of-memory notice; weighed if it exits without an outcome. */
        volatile boolean oomSeen;
        volatile Thread stdoutReader;
        volatile Thread stderrReader;
        volatile Thread watcher;

        SubprocessHandle(Process process, String jobId, Path argsFile, long startTimeMs,
                         SubprocessProtocolChannel.StderrProtocol stderrProtocol) {
            this.process = process;
            this.jobId = jobId;
            this.argsFile = argsFile;
            this.startTimeMs = startTimeMs;
            this.stderrProtocol = stderrProtocol;
            this.lastHeartbeatMs = startTimeMs;
        }
    }

    /** A launch that arrived after shutdown began; no child was started. */
    private static final class LaunchRefusedException extends IllegalStateException {
        LaunchRefusedException(String jobId) {
            super("Training launcher is shutting down; job " + jobId + " was not started");
        }
    }
}
