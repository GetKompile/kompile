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

import ai.kompile.cli.common.logs.AgentLogRecord;
import ai.kompile.cli.common.logs.SubprocessLogWriter;
import ai.kompile.staging.domain.TrainingJobHistory;
import ai.kompile.staging.service.TrainingJobHistoryService;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Launches and manages training subprocesses.
 * Follows the same pattern as SubprocessIngestLauncher but for training jobs.
 */
@Service
@ConditionalOnClass(name = "ai.kompile.staging.catalog.CatalogService")
@ConditionalOnProperty(name = "kompile.training.subprocess.enabled", havingValue = "true", matchIfMissing = false)
public class TrainingSubprocessLauncher implements ai.kompile.core.staging.TrainingSubprocessLauncherApi {

    private static final Logger log = LoggerFactory.getLogger(TrainingSubprocessLauncher.class);

    private final ObjectMapper objectMapper;
    private final TrainingJobHistoryService historyService;

    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;

    @Value("${kompile.staging.models-dir:#{systemProperties['user.home'] + '/.kompile/models'}}")
    private String modelsDir;

    @Value("${kompile.staging.training-jobs-dir:#{systemProperties['user.home'] + '/.kompile/training-jobs'}}")
    private String trainingJobsDir;

    @Value("${kompile.training.subprocess.heap-size:4g}")
    private String subprocessHeapSize;

    @Value("${kompile.training.subprocess.stale-timeout-ms:120000}")
    private long staleTimeoutMs;

    private final ConcurrentHashMap<String, SubprocessHandle> activeProcesses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<SseEmitter>> jobEmitters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<TrainingLogEntry>> jobLogs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<TrainingMetricsSnapshot>> jobMetrics = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TrainingJobStatus> jobStatuses = new ConcurrentHashMap<>();
    private final AtomicLong jobCounter = new AtomicLong(0);

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

        // Build subprocess args
        TrainingSubprocessArgs args = TrainingSubprocessArgs.builder()
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
                .peftConfigJson(request.getPeftConfig() != null ? objectMapper.writeValueAsString(request.getPeftConfig()) : null)
                .updaterConfigJson(request.getUpdaterConfig() != null ? objectMapper.writeValueAsString(request.getUpdaterConfig()) : null)
                .build();

        // Write args to temp file
        Path argsFile = args.writeToTempFile();

        // Create initial status
        TrainingJobStatus status = TrainingJobStatus.builder()
                .jobId(jobId)
                .status("QUEUED")
                .modelId(request.getModelId())
                .datasetId(request.getDatasetId())
                .currentEpoch(0)
                .totalEpochs(request.getEpochs())
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

        // Create persistent job history
        TrainingJobHistory.TrainingType historyType = TrainingJobHistory.TrainingType.valueOf(
                args.trainingType() != null ? args.trainingType().toUpperCase() : "FINETUNE");
        historyService.createJob(jobId, historyType, request.getModelId(), request.getDatasetId());
        historyService.updateTrainingParameters(jobId, request.getBatchSize(), request.getLrSchedule(),
                request.getWarmupRatio(), request.getMaxGradNorm(), request.isFp16(), request.isBf16(),
                request.getPeftConfig() != null ? request.getPeftConfig().getPeftType() : null, request.getSeed());

        // Build and launch subprocess
        List<String> command = buildCommand(argsFile);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        propagateEnvironment(pb);

        log.info("Launching training subprocess: jobId={}, model={}", jobId, request.getModelId());
        Process process = pb.start();

        SubprocessHandle handle = new SubprocessHandle(process, jobId, System.currentTimeMillis());

        // Initialise the centralized log writer now that the process is running and pid is known.
        try {
            SubprocessLogWriter logWriter = new SubprocessLogWriter("training", jobId);
            String workingDir = pb.directory() != null ? pb.directory().getAbsolutePath() : null;
            logWriter.writeStart(new SubprocessLogWriter.SubprocessRunContext(
                    null, command, workingDir, process.pid(), subprocessHeapSize));
            handle.logWriter = logWriter;
        } catch (Exception e) {
            log.debug("Failed to initialise SubprocessLogWriter for training job {}: {}", jobId, e.getMessage());
        }

        activeProcesses.put(jobId, handle);

        historyService.markRunning(jobId);
        updateJobStatus(jobId, "RUNNING");

        // Start monitoring threads
        startStdoutReader(jobId, process);
        startStderrReader(jobId, process);
        startCompletionWatcher(jobId, process);

        log.info("Training subprocess launched: jobId={}, pid={}", jobId, process.pid());
        return jobStatuses.get(jobId);
    }

    /**
     * Cancel a running training subprocess.
     */
    public boolean cancelTraining(String jobId) {
        SubprocessHandle handle = activeProcesses.get(jobId);
        if (handle == null) return false;

        log.info("Cancelling training subprocess: {}", jobId);
        handle.process.destroyForcibly();
        activeProcesses.remove(jobId);
        historyService.markCancelled(jobId, "Cancelled by user");
        updateJobStatus(jobId, "CANCELLED");
        // Finalise the centralized log aggregation entry
        if (handle.logWriter != null) {
            try {
                handle.logWriter.writeEnd(new SubprocessLogWriter.SubprocessRunResult(
                        "CANCELLED", null, "Cancelled by user", false, false));
                handle.logWriter.close();
            } catch (Exception ex) {
                log.debug("SubprocessLogWriter close failed on cancel for {}: {}", jobId, ex.getMessage());
            }
        }
        completeEmitters(jobId);
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
        command.add("-cp");
        command.add(classpath);
        command.add("ai.kompile.staging.subprocess.TrainingSubprocessMain");
        command.add(argsFile.toString());
        return command;
    }

    private void propagateEnvironment(ProcessBuilder pb) {
        Map<String, String> env = pb.environment();
        // Propagate ND4J/CUDA environment variables
        String[] envVars = {"ND4J_BACKEND", "CUDA_VISIBLE_DEVICES", "OMP_NUM_THREADS",
                "MKL_NUM_THREADS", "OPENBLAS_NUM_THREADS"};
        for (String var : envVars) {
            String value = System.getenv(var);
            if (value != null) env.put(var, value);
        }
    }

    private void startStdoutReader(String jobId, Process process) {
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith(TrainingSubprocessMessage.MESSAGE_PREFIX)) {
                        String json = line.substring(TrainingSubprocessMessage.MESSAGE_PREFIX.length());
                        handleMessage(jobId, json);
                    } else {
                        log.trace("[training-{}] stdout: {}", jobId, line);
                    }
                    // Write to centralized log aggregation store
                    final String finalLine = line;
                    try {
                        SubprocessHandle h = activeProcesses.get(jobId);
                        if (h != null && h.logWriter != null) {
                            h.logWriter.writeLine(AgentLogRecord.Stream.STDOUT, finalLine);
                        }
                    } catch (Exception ex) {
                        log.debug("SubprocessLogWriter stdout write failed for {}: {}", jobId, ex.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("Error reading training subprocess stdout for {}: {}", jobId, e.getMessage());
            }
        }, "training-stdout-" + jobId);
        reader.setDaemon(true);
        reader.start();
    }

    private void startStderrReader(String jobId, Process process) {
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    log.debug("[training-{}] stderr: {}", jobId, line);
                    // Detect OOM
                    if (line.contains("OutOfMemoryError") || line.contains("Cannot allocate")) {
                        historyService.markMemoryKilled(jobId, 100.0);
                        updateJobStatus(jobId, "MEMORY_KILLED");
                    }
                    // Write to centralized log aggregation store
                    final String finalLine = line;
                    try {
                        SubprocessHandle h = activeProcesses.get(jobId);
                        if (h != null && h.logWriter != null) {
                            h.logWriter.writeLine(AgentLogRecord.Stream.STDERR, finalLine);
                        }
                    } catch (Exception ex) {
                        log.debug("SubprocessLogWriter stderr write failed for {}: {}", jobId, ex.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("Error reading training subprocess stderr for {}: {}", jobId, e.getMessage());
            }
        }, "training-stderr-" + jobId);
        reader.setDaemon(true);
        reader.start();
    }

    private void startCompletionWatcher(String jobId, Process process) {
        Thread watcher = new Thread(() -> {
            try {
                int exitCode = process.waitFor();
                log.info("Training subprocess {} exited with code {}", jobId, exitCode);
                SubprocessHandle handle = activeProcesses.remove(jobId);

                String finalState = "COMPLETED";
                String errorMessage = null;
                boolean oomDetected = false;

                if (exitCode != 0) {
                    TrainingJobStatus current = jobStatuses.get(jobId);
                    if (current != null && !"COMPLETED".equals(current.getStatus())
                            && !"CANCELLED".equals(current.getStatus())
                            && !"MEMORY_KILLED".equals(current.getStatus())) {
                        errorMessage = "Subprocess exited with code " + exitCode;
                        historyService.markFailed(jobId, errorMessage,
                                null, TrainingJobHistory.FailureReason.TRAINING_ERROR);
                        updateJobStatus(jobId, "FAILED");
                        finalState = "FAILED";
                    } else if (current != null && "MEMORY_KILLED".equals(current.getStatus())) {
                        finalState = "OOM";
                        oomDetected = true;
                    } else if (current != null && "CANCELLED".equals(current.getStatus())) {
                        finalState = "CANCELLED";
                    }
                }

                // Finalise the centralized log aggregation entry
                if (handle != null && handle.logWriter != null) {
                    try {
                        handle.logWriter.writeEnd(new SubprocessLogWriter.SubprocessRunResult(
                                finalState, exitCode, errorMessage, oomDetected, false));
                        handle.logWriter.close();
                    } catch (Exception ex) {
                        log.debug("SubprocessLogWriter writeEnd failed for {}: {}", jobId, ex.getMessage());
                    }
                }

                completeEmitters(jobId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "training-watcher-" + jobId);
        watcher.setDaemon(true);
        watcher.start();
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
            log.warn("Failed to parse training message for {}: {}", jobId, e.getMessage());
        }
    }

    private void handleProgress(String jobId, TrainingSubprocessMessage.Progress p) {
        TrainingJobStatus current = jobStatuses.get(jobId);
        if (current == null) return;

        TrainingJobStatus updated = TrainingJobStatus.builder()
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

        // Update persistent history periodically (every 50 steps to reduce DB writes)
        if (p.step() % 50 == 0) {
            historyService.updateProgress(jobId, p.epoch(), p.step(), p.loss(), p.learningRate());
        }

        emitToSse(jobId, "status", updated);
    }

    private void handleHeartbeat(String jobId, TrainingSubprocessMessage.Heartbeat h) {
        SubprocessHandle handle = activeProcesses.get(jobId);
        if (handle != null) {
            handle.lastHeartbeatMs = System.currentTimeMillis();
        }
    }

    private void handleCompleted(String jobId, TrainingSubprocessMessage.Completed c) {
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
        jobStatuses.put(jobId, completed);
        historyService.markCompleted(jobId, c.finalLoss(), c.finalEvalLoss(), c.totalSteps(), c.outputPath());
        emitToSse(jobId, "status", completed);
        log.info("Training subprocess completed: jobId={}, finalLoss={}", jobId, c.finalLoss());
    }

    private void handleFailed(String jobId, TrainingSubprocessMessage.Failed f) {
        TrainingJobStatus current = jobStatuses.get(jobId);
        TrainingJobStatus failed = TrainingJobStatus.builder()
                .jobId(jobId)
                .status("FAILED")
                .modelId(current != null ? current.getModelId() : "")
                .datasetId(current != null ? current.getDatasetId() : "")
                .startedAt(current != null ? current.getStartedAt() : Instant.now().toString())
                .completedAt(Instant.now().toString())
                .error(f.errorMessage())
                .build();
        jobStatuses.put(jobId, failed);
        historyService.markFailed(jobId, f.errorMessage(), null, TrainingJobHistory.FailureReason.TRAINING_ERROR);
        emitToSse(jobId, "status", failed);
    }

    private void handleMetrics(String jobId, TrainingSubprocessMessage.MetricsUpdate m) {
        TrainingMetricsSnapshot snapshot = TrainingMetricsSnapshot.builder()
                .step(m.step())
                .epoch(m.epoch())
                .trainLoss(m.trainLoss())
                .evalLoss(m.evalLoss())
                .learningRate(m.learningRate())
                .tokensPerSecond(m.tokensPerSecond())
                .samplesPerSecond(m.samplesPerSecond())
                .customMetrics(m.customMetrics())
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

    private void updateJobStatus(String jobId, String status) {
        TrainingJobStatus current = jobStatuses.get(jobId);
        if (current != null) {
            TrainingJobStatus updated = TrainingJobStatus.builder()
                    .jobId(jobId)
                    .status(status)
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
                    .build();
            jobStatuses.put(jobId, updated);
        }
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
                try { emitter.complete(); } catch (Exception ignored) {}
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
        for (Map.Entry<String, SubprocessHandle> entry : activeProcesses.entrySet()) {
            SubprocessHandle handle = entry.getValue();
            if (now - handle.lastHeartbeatMs > staleTimeoutMs) {
                log.warn("Training subprocess {} appears stale (no heartbeat for {}ms), force killing",
                        entry.getKey(), now - handle.lastHeartbeatMs);
                handle.process.destroyForcibly();
                activeProcesses.remove(entry.getKey());
                historyService.markFailed(entry.getKey(), "Subprocess stalled (no heartbeat)",
                        null, TrainingJobHistory.FailureReason.TIMEOUT);
                updateJobStatus(entry.getKey(), "FAILED");
                completeEmitters(entry.getKey());
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down training subprocess launcher, cancelling {} active processes", activeProcesses.size());
        for (Map.Entry<String, SubprocessHandle> entry : activeProcesses.entrySet()) {
            SubprocessHandle handle = entry.getValue();
            handle.process.destroyForcibly();
            historyService.markCancelled(entry.getKey(), "Application shutdown");
            // Finalise the centralized log aggregation entry
            if (handle.logWriter != null) {
                try {
                    handle.logWriter.writeEnd(new SubprocessLogWriter.SubprocessRunResult(
                            "CANCELLED", null, "Application shutdown", false, false));
                    handle.logWriter.close();
                } catch (Exception ex) {
                    log.debug("SubprocessLogWriter close failed on shutdown for {}: {}", entry.getKey(), ex.getMessage());
                }
            }
        }
        activeProcesses.clear();
    }

    /**
     * Tracks a running subprocess.
     */
    private static class SubprocessHandle {
        final Process process;
        final String jobId;
        final long startTimeMs;
        volatile long lastHeartbeatMs;
        volatile SubprocessLogWriter logWriter;

        SubprocessHandle(Process process, String jobId, long startTimeMs) {
            this.process = process;
            this.jobId = jobId;
            this.startTimeMs = startTimeMs;
            this.lastHeartbeatMs = startTimeMs;
        }
    }
}
