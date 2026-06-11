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

/**
 * Bridges training jobs from kompile-model-staging through the ResourceAwareJobScheduler.
 *
 * <p>Since kompile-model-staging cannot depend on kompile-app-main (where the scheduler lives),
 * this bridge lives in kompile-app-main and wires both together at runtime via Spring events.</p>
 *
 * <p>Listens for {@link TrainingJobStartedEvent} published by {@link TrainingService} and submits
 * a tracking job to the scheduler that polls the training launcher until the job is terminal.</p>
 */
@Configuration
@ConditionalOnBean(ResourceAwareJobScheduler.class)
public class TrainingSchedulerBridge {

    private static final Logger log = LoggerFactory.getLogger(TrainingSchedulerBridge.class);

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
                .executor(ctx -> {
                    while (true) {
                        TrainingJobStatus status = getTrainingStatus(jobId, event.isSubprocess());
                        if (status == null) {
                            log.warn("Training job {} status is null, treating as completed", jobId);
                            break;
                        }
                        String s = status.getStatus();
                        if ("COMPLETED".equalsIgnoreCase(s) || "FAILED".equalsIgnoreCase(s)
                                || "CANCELLED".equalsIgnoreCase(s)) {
                            if ("FAILED".equalsIgnoreCase(s)) {
                                throw new RuntimeException("Training failed: " + jobId);
                            }
                            break;
                        }
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Training tracking interrupted", e);
                        }
                    }
                })
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
