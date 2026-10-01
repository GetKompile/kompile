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

package ai.kompile.app.config;

import ai.kompile.app.services.scheduler.JobResourceProfiles;
import ai.kompile.app.services.scheduler.ResourceAwareJobScheduler;
import ai.kompile.app.services.scheduler.ScheduledJob;
import ai.kompile.core.staging.TrainingSubprocessLauncherApi;
import ai.kompile.core.staging.TrainingJobStartedEvent;
import ai.kompile.core.staging.TrainingPhaseTransitionEvent;
import ai.kompile.core.staging.TrainingServiceApi;
import ai.kompile.core.staging.TrainingJobStatus;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Bridges training jobs from kompile-model-staging through the ResourceAwareJobScheduler.
 *
 * <p>Since kompile-model-staging cannot depend on kompile-app-main (where the scheduler lives),
 * this bridge lives in kompile-app-main and wires both together at runtime via Spring events.</p>
 *
 * <p>Listens for {@link TrainingJobStartedEvent} published by {@link TrainingService} and submits
 * a tracking job to the scheduler that polls the training launcher until the job is terminal.</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(ResourceAwareJobScheduler.class)
public class TrainingSchedulerBridge {

    private static final Logger log = LoggerFactory.getLogger(TrainingSchedulerBridge.class);

    /** How long a tracking job waits for its training run before cancelling it. */
    private static final long TRACKING_TIMEOUT_MS = TimeUnit.HOURS.toMillis(24);

    @Autowired
    private ResourceAwareJobScheduler scheduler;

    @Autowired(required = false)
    private TrainingSubprocessLauncherApi trainingLauncher;

    @Autowired(required = false)
    private TrainingServiceApi trainingService;

    @PostConstruct
    public void init() {
        log.info("TrainingSchedulerBridge active — training jobs will be tracked by scheduler");
    }

    @EventListener
    public void onTrainingJobStarted(TrainingJobStartedEvent event) {
        String jobId = event.getJobId();
        String modelId = event.getModelId();

        log.info("Training job started event received: jobId={}, modelId={}, subprocess={}",
                jobId, modelId, event.isSubprocess());

        // For subprocess training, poll the subprocess launcher
        // For in-process training, poll the training service
        ScheduledJob job = ScheduledJob.builder()
                .jobId(jobId)
                .jobType("training")
                .description("Training: " + modelId)
                .resourceProfile(JobResourceProfiles.TRAINING)
                .executor(ctx -> trackTraining(ctx, jobId, event.isSubprocess(), TRACKING_TIMEOUT_MS))
                .priority(30)
                .build();

        scheduler.submit(job);
        log.info("Training job '{}' submitted to scheduler for tracking", jobId);
    }

    @EventListener
    public void onTrainingPhaseTransition(TrainingPhaseTransitionEvent event) {
        var profile = JobResourceProfiles.TRAINING;
        boolean requiresGpu = profile.phaseRequiresGpu(event.getToPhase());
        long gpuMem = profile.gpuMemoryForPhase(event.getToPhase());
        scheduler.reportPhaseTransition(event.getJobId(), event.getToPhase(), requiresGpu, gpuMem);
    }

    /**
     * Polls a training run until it is terminal. A scheduler cancel, an interrupt or the deadline
     * cancels the run and throws, so the tracking job never ends as completed while its run goes on.
     */
    void trackTraining(ScheduledJob.JobExecutionContext ctx, String jobId, boolean subprocess, long timeoutMs)
            throws TimeoutException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (ctx.isCancellationRequested()) {
                cancelTraining(jobId, subprocess);
                throw new CancellationException("Training tracking cancelled: " + jobId);
            }
            TrainingJobStatus status = getTrainingStatus(jobId, subprocess);
            if (status == null) {
                log.warn("Training job {} status is null, treating as completed", jobId);
                return;
            }
            String s = status.getStatus();
            if ("COMPLETED".equalsIgnoreCase(s)) {
                return;
            }
            if ("FAILED".equalsIgnoreCase(s)) {
                throw new RuntimeException("Training failed: " + jobId);
            }
            if ("CANCELLED".equalsIgnoreCase(s)) {
                throw new CancellationException("Training cancelled: " + jobId);
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                cancelTraining(jobId, subprocess);
                Thread.currentThread().interrupt();
                throw new RuntimeException("Training tracking interrupted", e);
            }
        }
        cancelTraining(jobId, subprocess);
        throw new TimeoutException("Training " + jobId + " did not finish within " + timeoutMs + "ms");
    }

    private void cancelTraining(String jobId, boolean subprocess) {
        boolean cancelled = false;
        if (subprocess && trainingLauncher != null) {
            cancelled = trainingLauncher.cancelTraining(jobId);
        } else if (trainingService != null) {
            cancelled = trainingService.cancelJob(jobId);
        }
        log.info("Training job {} cancelled with its tracking job: {}", jobId, cancelled);
    }

    private TrainingJobStatus getTrainingStatus(String jobId, boolean subprocess) {
        if (subprocess && trainingLauncher != null) {
            return trainingLauncher.getJobStatus(jobId);
        }
        if (trainingService != null) {
            return trainingService.getJob(jobId);
        }
        return null;
    }
}
