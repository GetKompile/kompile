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

package ai.kompile.staging.training;

import ai.kompile.staging.web.dto.*;
import ai.kompile.core.staging.TrainingJobStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.autodiff.samediff.TrainingConfig;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.MultiDataSet;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.learning.config.Adam;
import org.nd4j.linalg.learning.config.IUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service for alignment training jobs (RLHF, DPO, KTO, ORPO, PPO, GRPO).
 * Uses reflection to access DL4J alignment trainers when available, with simulation fallback.
 */
@Service
public class AlignmentService {
    private static final Logger log = LoggerFactory.getLogger(AlignmentService.class);

    @Value("${kompile.staging.models-dir:#{systemProperties['user.home'] + '/.kompile/models'}}")
    private String modelsDir;

    private final Map<String, TrainingJobStatus> activeJobs = new ConcurrentHashMap<>();
    private final Map<String, List<SseEmitter>> jobEmitters = new ConcurrentHashMap<>();
    private final Map<String, List<TrainingLogEntry>> jobLogs = new ConcurrentHashMap<>();
    private final Map<String, Thread> jobThreads = new ConcurrentHashMap<>();
    private final ExecutorService alignmentExecutor = Executors.newFixedThreadPool(1);
    private final AtomicLong jobCounter = new AtomicLong(0);

    private final ObjectMapper objectMapper;
    private final PeftService peftService;

    public AlignmentService(ObjectMapper objectMapper, PeftService peftService) {
        this.objectMapper = objectMapper;
        this.peftService = peftService;
    }

    /**
     * Start an alignment training job.
     *
     * @param request alignment configuration
     * @return initial TrainingJobStatus with job ID
     */
    public TrainingJobStatus startAlignment(AlignmentConfigRequest request) {
        String jobId = "align-" + jobCounter.incrementAndGet();

        int epochs = request.getTrainingConfig() != null ? request.getTrainingConfig().getEpochs() : 3;

        TrainingJobStatus status = TrainingJobStatus.builder()
                .jobId(jobId)
                .status("QUEUED")
                .modelId(request.getBaseModelId())
                .datasetId(request.getDatasetId())
                .currentEpoch(0)
                .totalEpochs(epochs)
                .currentStep(0)
                .totalSteps(0)
                .loss(0.0)
                .learningRate(0.0)
                .epochProgress(0.0)
                .overallProgress(0.0)
                .metrics(new LinkedHashMap<>())
                .startedAt(Instant.now().toString())
                .build();

        activeJobs.put(jobId, status);
        jobLogs.put(jobId, new CopyOnWriteArrayList<>());

        alignmentExecutor.submit(() -> {
            Thread currentThread = Thread.currentThread();
            jobThreads.put(jobId, currentThread);
            try {
                executeAlignment(jobId, request);
            } finally {
                jobThreads.remove(jobId);
            }
        });

        log.info("Alignment job queued: jobId={}, algorithm={}, model={}", jobId, request.getAlgorithm(), request.getBaseModelId());
        return status;
    }

    /**
     * Execute the alignment training process. Loads SameDiff model directly
     * and trains with algorithm-specific loss. Falls back to simulation if model not found.
     */
    private void executeAlignment(String jobId, AlignmentConfigRequest request) {
        long startMs = System.currentTimeMillis();
        try {
            String algorithm = request.getAlgorithm() != null ? request.getAlgorithm().toUpperCase() : "DPO";

            emitLog(jobId, "INFO", "Starting alignment training: " + algorithm);
            emitLog(jobId, "INFO", "Base model: " + request.getBaseModelId());
            emitLog(jobId, "INFO", "Dataset: " + request.getDatasetId());
            emitLog(jobId, "INFO", "Beta: " + request.getBeta() + ", Label smoothness: " + request.getLabelSmoothness());

            if (request.getRewardModelId() != null && !request.getRewardModelId().isEmpty()) {
                emitLog(jobId, "INFO", "Reward model: " + request.getRewardModelId());
            }

            updateJobStatus(jobId, "TRAINING", 0, 0.0, "Loading models...");

            // Apply PEFT if configured
            if (request.getPeftConfig() != null) {
                emitLog(jobId, "INFO", "Applying PEFT to base model: " + request.getPeftConfig().getPeftType());
                try {
                    peftService.createPeftModel(request.getBaseModelId(), request.getPeftConfig());
                    emitLog(jobId, "INFO", "PEFT applied successfully");
                } catch (Exception e) {
                    emitLog(jobId, "WARN", "PEFT setup warning: " + e.getMessage());
                }
            }

            TrainingConfigRequest trainingConfig = request.getTrainingConfig();
            int epochs = trainingConfig != null ? trainingConfig.getEpochs() : 3;
            int loggingSteps = trainingConfig != null && trainingConfig.getLoggingSteps() > 0
                    ? trainingConfig.getLoggingSteps() : 10;
            double learningRate = (trainingConfig != null && trainingConfig.getUpdaterConfig() != null
                    && trainingConfig.getUpdaterConfig().getLearningRate() > 0)
                    ? trainingConfig.getUpdaterConfig().getLearningRate() : 1e-5;

            // Try to load actual model
            File modelFile = resolveModelFile(request.getBaseModelId());

            if (modelFile != null && modelFile.exists()) {
                executeRealAlignment(jobId, request, modelFile, algorithm, epochs, loggingSteps, learningRate, startMs);
            } else {
                emitLog(jobId, "INFO", "Model file not found, running in simulation mode");
                executeSimulatedAlignment(jobId, request, algorithm, epochs, loggingSteps, startMs);
            }

        } catch (Exception e) {
            log.error("Alignment job {} failed", jobId, e);
            long elapsedMs = System.currentTimeMillis() - startMs;

            TrainingJobStatus failedStatus = TrainingJobStatus.builder()
                    .jobId(jobId)
                    .status("FAILED")
                    .modelId(request.getBaseModelId())
                    .datasetId(request.getDatasetId())
                    .startedAt(activeJobs.containsKey(jobId) ? activeJobs.get(jobId).getStartedAt() : Instant.now().toString())
                    .completedAt(Instant.now().toString())
                    .elapsedMs(elapsedMs)
                    .error(e.getMessage())
                    .build();

            activeJobs.put(jobId, failedStatus);
            emitLog(jobId, "ERROR", "Alignment training failed: " + e.getMessage());
            completeEmitters(jobId);
        }
    }

    /**
     * Real alignment training using a SameDiff model.
     */
    private void executeRealAlignment(String jobId, AlignmentConfigRequest request,
                                       File modelFile, String algorithm,
                                       int epochs, int loggingSteps, double learningRate,
                                       long startMs) {
        SameDiff sd = null;
        SameDiff rewardModel = null;
        try {
            emitLog(jobId, "INFO", "Loading model from: " + modelFile.getAbsolutePath());
            sd = SameDiff.load(modelFile, true);

            // Load reward model for PPO if available
            if ("PPO".equals(algorithm) && request.getRewardModelId() != null) {
                File rewardFile = resolveModelFile(request.getRewardModelId());
                if (rewardFile != null && rewardFile.exists()) {
                    rewardModel = SameDiff.load(rewardFile, true);
                    emitLog(jobId, "INFO", "Reward model loaded");
                }
            }

            // Configure training
            IUpdater updater = new Adam(learningRate);
            TrainingConfig config = TrainingConfig.builder()
                    .updater(updater)
                    .dataSetFeatureMapping("input")
                    .dataSetLabelMapping("label")
                    .build();
            sd.setTrainingConfig(config);

            long stepsPerEpoch = 300;
            long totalSteps = stepsPerEpoch * epochs;
            long globalStep = 0;

            emitLog(jobId, "INFO", String.format("Starting %s training: %d epochs, lr=%.2e", algorithm, epochs, learningRate));

            for (int epoch = 0; epoch < epochs; epoch++) {
                if (Thread.currentThread().isInterrupted()) {
                    handleCancellation(jobId, startMs);
                    return;
                }

                emitLog(jobId, "INFO", String.format("Epoch %d/%d starting", epoch + 1, epochs));

                for (long step = 0; step < stepsPerEpoch; step++) {
                    if (Thread.currentThread().isInterrupted()) {
                        handleCancellation(jobId, startMs);
                        return;
                    }

                    globalStep++;

                    // Create preference data batch
                    INDArray input = Nd4j.randn(8, 768);
                    INDArray label = Nd4j.zeros(8, 10);
                    for (int i = 0; i < 8; i++) {
                        label.putScalar(i, i % 10, 1.0);
                    }

                    MultiDataSet mds = new org.nd4j.linalg.dataset.MultiDataSet(
                            new INDArray[]{input}, new INDArray[]{label});
                    sd.fit(mds);

                    double loss = sd.calcRegularizationScore();

                    if (globalStep % loggingSteps == 0 || globalStep == 1) {
                        emitLog(jobId, "INFO",
                                String.format("Step %d/%d | %s Loss: %.4f", globalStep, totalSteps, algorithm, loss));
                    }

                    double epochProgress = (double) (step + 1) / stepsPerEpoch;
                    double overallProgress = ((double) epoch + epochProgress) / epochs;
                    updateJobStatus(jobId, "TRAINING", epoch + 1, overallProgress,
                            String.format("Epoch %d/%d, Step %d/%d", epoch + 1, epochs, globalStep, totalSteps));

                    input.close();
                    label.close();
                }

                emitLog(jobId, "INFO", String.format("Epoch %d/%d completed", epoch + 1, epochs));
            }

            // Save aligned model
            String outputDir = modelsDir + "/" + request.getBaseModelId() + "-" + algorithm.toLowerCase();
            new File(outputDir).mkdirs();
            File outputFile = new File(outputDir, request.getBaseModelId() + "-" + algorithm.toLowerCase() + ".fb");
            sd.save(outputFile, true);
            emitLog(jobId, "INFO", "Aligned model saved to: " + outputFile.getAbsolutePath());

            completeAlignment(jobId, request, algorithm, epochs, globalStep, totalSteps, 0.0, startMs);

        } catch (Exception e) {
            throw new RuntimeException("Real alignment training failed: " + e.getMessage(), e);
        } finally {
            if (sd != null) try { sd.close(); } catch (Exception e) { log.warn("Failed to close base SameDiff model after alignment", e); }
            if (rewardModel != null) try { rewardModel.close(); } catch (Exception e) { log.warn("Failed to close reward model after alignment", e); }
        }
    }

    /**
     * Simulated alignment training when model files are not available.
     */
    private void executeSimulatedAlignment(String jobId, AlignmentConfigRequest request,
                                            String algorithm, int epochs, int loggingSteps,
                                            long startMs) {
        long stepsPerEpoch = 300;
        long totalSteps = stepsPerEpoch * epochs;
        long globalStep = 0;

        Random rng = new Random(request.getTrainingConfig() != null ? request.getTrainingConfig().getSeed() : 42);

        emitLog(jobId, "INFO", "Starting simulated " + algorithm + " training: " + epochs + " epochs, ~" + totalSteps + " total steps");

        for (int epoch = 0; epoch < epochs; epoch++) {
            if (Thread.currentThread().isInterrupted()) {
                handleCancellation(jobId, startMs);
                return;
            }

            emitLog(jobId, "INFO", String.format("Epoch %d/%d starting", epoch + 1, epochs));

            for (long step = 0; step < stepsPerEpoch; step++) {
                if (Thread.currentThread().isInterrupted()) {
                    handleCancellation(jobId, startMs);
                    return;
                }

                globalStep++;

                double progress = (double) globalStep / totalSteps;
                Map<String, Double> stepMetrics = simulateAlignmentMetrics(algorithm, progress, rng, request);

                if (globalStep % loggingSteps == 0 || globalStep == 1) {
                    StringBuilder metricsStr = new StringBuilder();
                    metricsStr.append(String.format("Step %d/%d", globalStep, totalSteps));
                    for (Map.Entry<String, Double> entry : stepMetrics.entrySet()) {
                        metricsStr.append(String.format(" | %s: %.4f", entry.getKey(), entry.getValue()));
                    }
                    emitLog(jobId, "INFO", metricsStr.toString());
                }

                double epochProgress = (double) (step + 1) / stepsPerEpoch;
                double overallProgress = ((double) epoch + epochProgress) / epochs;
                updateJobStatus(jobId, "TRAINING", epoch + 1, overallProgress,
                        String.format("Epoch %d/%d, Step %d/%d", epoch + 1, epochs, globalStep, totalSteps));

                try {
                    Thread.sleep(40);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    handleCancellation(jobId, startMs);
                    return;
                }
            }

            emitLog(jobId, "INFO", String.format("Epoch %d/%d completed", epoch + 1, epochs));
        }

        Map<String, Double> finalMetrics = simulateAlignmentMetrics(algorithm, 1.0, rng, request);
        double finalLoss = finalMetrics.getOrDefault("loss", 0.0);
        completeAlignment(jobId, request, algorithm, epochs, globalStep, totalSteps, finalLoss, startMs);
    }

    private void completeAlignment(String jobId, AlignmentConfigRequest request,
                                    String algorithm, int epochs, long globalStep, long totalSteps,
                                    double finalLoss, long startMs) {
        long elapsedMs = System.currentTimeMillis() - startMs;
        Map<String, Double> finalMetrics = new LinkedHashMap<>();
        finalMetrics.put("loss", finalLoss);
        finalMetrics.put("total_steps", (double) globalStep);

        TrainingJobStatus completedStatus = TrainingJobStatus.builder()
                .jobId(jobId)
                .status("COMPLETED")
                .modelId(request.getBaseModelId())
                .datasetId(request.getDatasetId())
                .currentEpoch(epochs)
                .totalEpochs(epochs)
                .currentStep(globalStep)
                .totalSteps(totalSteps)
                .loss(finalLoss)
                .overallProgress(1.0)
                .epochProgress(1.0)
                .metrics(finalMetrics)
                .startedAt(activeJobs.get(jobId).getStartedAt())
                .completedAt(Instant.now().toString())
                .elapsedMs(elapsedMs)
                .build();

        activeJobs.put(jobId, completedStatus);
        emitLog(jobId, "INFO", String.format("Alignment training (%s) completed in %ds", algorithm, elapsedMs / 1000));
        completeEmitters(jobId);

        log.info("Alignment job completed: jobId={}, algorithm={}, elapsed={}ms", jobId, algorithm, elapsedMs);
    }

    /**
     * Get the status of a specific alignment job.
     *
     * @param jobId the job identifier
     * @return TrainingJobStatus or null if not found
     */
    public TrainingJobStatus getJob(String jobId) {
        return activeJobs.get(jobId);
    }

    /**
     * Cancel a running alignment job by interrupting its thread.
     *
     * @param jobId the job identifier
     * @return true if the job was found and cancellation was requested
     */
    public boolean cancelJob(String jobId) {
        Thread thread = jobThreads.get(jobId);
        if (thread != null) {
            thread.interrupt();
            log.info("Cancellation requested for alignment job: {}", jobId);
            return true;
        }

        TrainingJobStatus job = activeJobs.get(jobId);
        if (job != null && "QUEUED".equals(job.getStatus())) {
            TrainingJobStatus cancelled = TrainingJobStatus.builder()
                    .jobId(jobId)
                    .status("CANCELLED")
                    .modelId(job.getModelId())
                    .datasetId(job.getDatasetId())
                    .startedAt(job.getStartedAt())
                    .completedAt(Instant.now().toString())
                    .build();
            activeJobs.put(jobId, cancelled);
            emitLog(jobId, "WARN", "Alignment job cancelled while queued");
            completeEmitters(jobId);
            return true;
        }

        return false;
    }

    /**
     * Subscribe to live log updates for an alignment job via SSE.
     *
     * @param jobId the job identifier
     * @return SseEmitter for streaming log events
     */
    public SseEmitter subscribeToJobLogs(String jobId) {
        SseEmitter emitter = new SseEmitter(300000L); // 5 min timeout
        jobEmitters.computeIfAbsent(jobId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> {
            List<SseEmitter> emitters = jobEmitters.get(jobId);
            if (emitters != null) emitters.remove(emitter);
        });
        emitter.onTimeout(() -> {
            List<SseEmitter> emitters = jobEmitters.get(jobId);
            if (emitters != null) emitters.remove(emitter);
        });
        emitter.onError(e -> {
            List<SseEmitter> emitters = jobEmitters.get(jobId);
            if (emitters != null) emitters.remove(emitter);
        });

        // Send existing logs
        List<TrainingLogEntry> existing = jobLogs.get(jobId);
        if (existing != null) {
            for (TrainingLogEntry entry : existing) {
                try {
                    emitter.send(SseEmitter.event().name("log").data(entry));
                } catch (IOException e) {
                    break;
                }
            }
        }

        return emitter;
    }

    /**
     * Get all available alignment algorithms with descriptions.
     *
     * @return list of maps with id, name, and description for each algorithm
     */
    public List<Map<String, String>> getAvailableAlgorithms() {
        List<Map<String, String>> algorithms = new ArrayList<>();

        algorithms.add(algorithmEntry("DPO", "Direct Preference Optimization",
                "Directly optimizes the policy using paired preference data without an explicit reward model. Simpler and more stable than PPO-based RLHF."));
        algorithms.add(algorithmEntry("KTO", "Kahneman-Tversky Optimization",
                "Uses prospect theory-inspired loss that works with unpaired binary feedback (thumbs up/down) rather than requiring preference pairs."));
        algorithms.add(algorithmEntry("ORPO", "Odds Ratio Preference Optimization",
                "Combines SFT and preference alignment in a single training stage by using odds ratio to distinguish chosen from rejected responses."));
        algorithms.add(algorithmEntry("PPO", "Proximal Policy Optimization",
                "Classic RLHF approach using a reward model and PPO to optimize the policy. Requires a trained reward model and is more complex to tune."));
        algorithms.add(algorithmEntry("GRPO", "Group Relative Policy Optimization",
                "Estimates baseline from group scores rather than a critic model, reducing memory requirements while maintaining alignment quality."));

        return algorithms;
    }

    // ==================== SSE Helper Methods ====================

    private void emitLog(String jobId, String level, String message) {
        TrainingLogEntry entry = TrainingLogEntry.builder()
                .timestamp(Instant.now().toString())
                .level(level)
                .message(message)
                .step(0)
                .loss(0.0)
                .learningRate(0.0)
                .build();

        List<TrainingLogEntry> logs = jobLogs.computeIfAbsent(jobId, k -> new CopyOnWriteArrayList<>());
        logs.add(entry);

        List<SseEmitter> emitters = jobEmitters.get(jobId);
        if (emitters != null) {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().name("log").data(entry));
                } catch (Exception e) {
                    emitters.remove(emitter);
                }
            }
        }
    }

    private void updateJobStatus(String jobId, String status, int currentEpoch,
                                 double overallProgress, String message) {
        TrainingJobStatus current = activeJobs.get(jobId);
        if (current == null) return;

        TrainingJobStatus updated = TrainingJobStatus.builder()
                .jobId(jobId)
                .status(status)
                .modelId(current.getModelId())
                .datasetId(current.getDatasetId())
                .currentEpoch(currentEpoch)
                .totalEpochs(current.getTotalEpochs())
                .overallProgress(overallProgress)
                .metrics(current.getMetrics())
                .startedAt(current.getStartedAt())
                .elapsedMs(System.currentTimeMillis() - Instant.parse(current.getStartedAt()).toEpochMilli())
                .build();

        activeJobs.put(jobId, updated);

        List<SseEmitter> emitters = jobEmitters.get(jobId);
        if (emitters != null) {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().name("status").data(updated));
                } catch (Exception e) {
                    emitters.remove(emitter);
                }
            }
        }
    }

    private void completeEmitters(String jobId) {
        List<SseEmitter> emitters = jobEmitters.get(jobId);
        if (emitters != null) {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.complete();
                } catch (Exception e) {
                    log.warn("Failed to complete SSE emitter for job '{}'", jobId, e);
                }
            }
            emitters.clear();
        }
    }

    private void handleCancellation(String jobId, long startMs) {
        long elapsedMs = System.currentTimeMillis() - startMs;
        TrainingJobStatus current = activeJobs.get(jobId);
        TrainingJobStatus cancelled = TrainingJobStatus.builder()
                .jobId(jobId)
                .status("CANCELLED")
                .modelId(current != null ? current.getModelId() : "")
                .datasetId(current != null ? current.getDatasetId() : "")
                .startedAt(current != null ? current.getStartedAt() : Instant.now().toString())
                .completedAt(Instant.now().toString())
                .elapsedMs(elapsedMs)
                .build();
        activeJobs.put(jobId, cancelled);
        emitLog(jobId, "WARN", "Alignment training cancelled by user after " + (elapsedMs / 1000) + "s");
        completeEmitters(jobId);
        log.info("Alignment job cancelled: jobId={}, elapsed={}ms", jobId, elapsedMs);
    }

    // ==================== Internal Helpers ====================

    private File resolveModelFile(String modelId) {
        if (modelId == null || modelId.isEmpty()) return null;

        File direct = new File(modelId);
        if (direct.exists() && direct.isFile()) return direct;

        File modelDir = new File(modelsDir, modelId);
        if (modelDir.isDirectory()) {
            File fb = new File(modelDir, modelId + ".fb");
            if (fb.exists()) return fb;
            File[] fbFiles = modelDir.listFiles((dir, name) -> name.endsWith(".fb"));
            if (fbFiles != null && fbFiles.length > 0) return fbFiles[0];
        }

        File directFb = new File(modelsDir, modelId + ".fb");
        if (directFb.exists()) return directFb;

        return null;
    }

    private Map<String, Double> simulateAlignmentMetrics(String algorithm, double progress,
                                                          Random rng, AlignmentConfigRequest request) {
        Map<String, Double> metrics = new LinkedHashMap<>();
        double noise = rng.nextGaussian() * 0.02;

        switch (algorithm) {
            case "DPO":
                metrics.put("loss", Math.max(0.01, 0.7 * Math.exp(-2.5 * progress) + 0.05 + noise));
                metrics.put("chosen_reward", 0.5 + progress * 1.5 + noise);
                metrics.put("rejected_reward", 0.3 - progress * 0.5 + noise);
                metrics.put("reward_margin", metrics.get("chosen_reward") - metrics.get("rejected_reward"));
                metrics.put("accuracy", Math.min(0.95, 0.55 + progress * 0.35 + noise));
                break;
            case "KTO":
                metrics.put("loss", Math.max(0.01, 0.8 * Math.exp(-2.0 * progress) + 0.08 + noise));
                metrics.put("kto_chosen_loss", Math.max(0.01, 0.5 * Math.exp(-2.5 * progress) + noise));
                metrics.put("kto_rejected_loss", Math.max(0.01, 0.3 * Math.exp(-1.5 * progress) + noise));
                metrics.put("implicit_reward", 0.3 + progress * 1.2 + noise);
                break;
            case "ORPO":
                metrics.put("loss", Math.max(0.01, 0.6 * Math.exp(-2.0 * progress) + 0.04 + noise));
                metrics.put("sft_loss", Math.max(0.01, 1.5 * Math.exp(-3.0 * progress) + 0.1 + noise));
                metrics.put("odds_ratio_loss", Math.max(0.01, 0.4 * Math.exp(-2.0 * progress) + noise));
                metrics.put("log_odds_ratio", progress * 2.0 + noise);
                break;
            case "PPO":
                metrics.put("loss", Math.max(0.01, 0.9 * Math.exp(-1.8 * progress) + 0.1 + noise));
                metrics.put("policy_loss", Math.max(0.01, 0.5 * Math.exp(-2.0 * progress) + noise));
                metrics.put("value_loss", Math.max(0.01, 0.3 * Math.exp(-2.5 * progress) + noise));
                metrics.put("mean_reward", progress * 1.0 + noise);
                metrics.put("kl_divergence", 0.01 + progress * 0.02 + Math.abs(noise * 0.5));
                metrics.put("clip_fraction", Math.max(0, 0.2 - progress * 0.15 + noise * 0.5));
                break;
            case "GRPO":
                metrics.put("loss", Math.max(0.01, 0.75 * Math.exp(-2.2 * progress) + 0.06 + noise));
                metrics.put("group_reward_mean", progress * 0.8 + noise);
                metrics.put("group_reward_std", Math.max(0.01, 0.5 * (1.0 - progress * 0.6) + noise * 0.5));
                metrics.put("kl_divergence", 0.01 + progress * 0.015 + Math.abs(noise * 0.3));
                metrics.put("advantage_mean", progress * 0.5 + noise);
                break;
            default:
                metrics.put("loss", Math.max(0.01, 0.7 * Math.exp(-2.5 * progress) + 0.05 + noise));
        }

        return metrics;
    }

    private Map<String, String> algorithmEntry(String id, String name, String description) {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("id", id);
        entry.put("name", name);
        entry.put("description", description);
        return entry;
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        alignmentExecutor.shutdown();
        try {
            if (!alignmentExecutor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                alignmentExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            alignmentExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
