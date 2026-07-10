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

import ai.kompile.staging.subprocess.TrainingSubprocessLauncher;
import ai.kompile.staging.web.dto.*;
import ai.kompile.core.staging.TrainingJobStatus;
import ai.kompile.core.staging.TrainingJobStartedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Service for managing model training jobs with SSE-based live log streaming.
 * Training execution is delegated to the training subprocess; missing subprocess support is a hard failure.
 */
@Service
public class TrainingService implements ai.kompile.core.staging.TrainingServiceApi {
    private static final Logger log = LoggerFactory.getLogger(TrainingService.class);

    private final Map<String, TrainingJobStatus> activeJobs = new ConcurrentHashMap<>();
    private final Map<String, List<TrainingLogEntry>> jobLogs = new ConcurrentHashMap<>();
    private final Map<String, List<TrainingMetricsSnapshot>> jobMetrics = new ConcurrentHashMap<>();
    private final Map<String, List<SseEmitter>> jobEmitters = new ConcurrentHashMap<>();

    private final TrainingSubprocessLauncher subprocessLauncher;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${kompile.training.subprocess.enabled:true}")
    private boolean subprocessEnabled;

    public TrainingService(@Autowired(required = false) TrainingSubprocessLauncher subprocessLauncher,
                           @Autowired(required = false) ApplicationEventPublisher eventPublisher) {
        this.subprocessLauncher = subprocessLauncher;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Start a new training job through the training subprocess.
     *
     * @param request training configuration
     * @return initial TrainingJobStatus with job ID
     */
    public TrainingJobStatus startTraining(TrainingConfigRequest request) {
        if (!subprocessEnabled || subprocessLauncher == null) {
            throw new IllegalStateException("Training subprocess launcher is required; in-process training is not implemented");
        }
        try {
            log.info("Delegating training to subprocess launcher for model: {}", request.getModelId());
            TrainingJobStatus status = subprocessLauncher.launchTraining(request);
            if (eventPublisher != null && status != null) {
                eventPublisher.publishEvent(new TrainingJobStartedEvent(
                        this, status.getJobId(), request.getModelId(), true));
            }
            return status;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to launch training subprocess", e);
        }
    }

    /**
     * Cancel a running training job by interrupting its thread.
     *
     * @param jobId the job identifier
     * @return true if the job was found and cancellation was requested
     */
    public boolean cancelJob(String jobId) {
        // Try subprocess cancellation first
        if (subprocessEnabled && subprocessLauncher != null) {
            if (subprocessLauncher.cancelTraining(jobId)) {
                return true;
            }
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
            emitLog(jobId, "WARN", "Training job cancelled while queued", 0, 0.0, 0.0, null);
            completeEmitters(jobId);
            return true;
        }

        return false;
    }

    /**
     * Get the status of a specific training job.
     *
     * @param jobId the job identifier
     * @return TrainingJobStatus or null if not found
     */
    public TrainingJobStatus getJob(String jobId) {
        // Check subprocess launcher first
        if (subprocessEnabled && subprocessLauncher != null) {
            TrainingJobStatus subStatus = subprocessLauncher.getJobStatus(jobId);
            if (subStatus != null) return subStatus;
        }
        return activeJobs.get(jobId);
    }

    /**
     * Get all training jobs (active, completed, and failed).
     *
     * @return list of all training job statuses
     */
    public List<TrainingJobStatus> getAllJobs() {
        List<TrainingJobStatus> all = new ArrayList<>(activeJobs.values());
        // Merge subprocess jobs
        if (subprocessEnabled && subprocessLauncher != null) {
            all.addAll(subprocessLauncher.getAllJobStatuses());
        }
        return all;
    }

    /**
     * Subscribe to live log updates for a training job via SSE.
     *
     * @param jobId the job identifier
     * @return SseEmitter for streaming log events
     */
    public SseEmitter subscribeToJobLogs(String jobId) {
        // Delegate to subprocess launcher if it owns this job
        if (subprocessEnabled && subprocessLauncher != null && subprocessLauncher.getJobStatus(jobId) != null) {
            return subprocessLauncher.subscribeToJobLogs(jobId);
        }

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

        // Send existing metrics
        List<TrainingMetricsSnapshot> existingMetrics = jobMetrics.get(jobId);
        if (existingMetrics != null) {
            for (TrainingMetricsSnapshot snapshot : existingMetrics) {
                try {
                    emitter.send(SseEmitter.event().name("metrics").data(snapshot));
                } catch (IOException e) {
                    break;
                }
            }
        }

        return emitter;
    }

    /**
     * Get all log entries for a training job.
     *
     * @param jobId the job identifier
     * @return list of log entries, or empty list if job not found
     */
    public List<TrainingLogEntry> getJobLogs(String jobId) {
        if (subprocessEnabled && subprocessLauncher != null && subprocessLauncher.getJobStatus(jobId) != null) {
            return subprocessLauncher.getJobLogs(jobId);
        }
        return jobLogs.getOrDefault(jobId, Collections.emptyList());
    }

    /**
     * Get the full metrics history for a training job.
     *
     * @param jobId the job identifier
     * @return list of metrics snapshots, or empty list if job not found
     */
    public List<TrainingMetricsSnapshot> getMetricsHistory(String jobId) {
        if (subprocessEnabled && subprocessLauncher != null && subprocessLauncher.getJobStatus(jobId) != null) {
            return subprocessLauncher.getJobMetrics(jobId);
        }
        return jobMetrics.getOrDefault(jobId, Collections.emptyList());
    }

    // ==================== SSE Helper Methods ====================

    private void emitLog(String jobId, String level, String message, long step,
                         double loss, double learningRate, Map<String, Double> metrics) {
        TrainingLogEntry entry = TrainingLogEntry.builder()
                .timestamp(Instant.now().toString())
                .level(level)
                .message(message)
                .step(step)
                .loss(loss)
                .learningRate(learningRate)
                .metrics(metrics)
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

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        // Training subprocess lifecycle is owned by TrainingSubprocessLauncher.
    }
}
