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
import ai.kompile.core.staging.TrainingJobStartedEvent;
import ai.kompile.core.staging.TrainingJobStatus;
import ai.kompile.core.staging.TrainingServiceApi;
import ai.kompile.core.staging.TrainingSubprocessLauncherApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The scheduler job that tracks a training run must not end as completed while the run was
 * cancelled or is still going, and cancelling that job must cancel the run.
 */
@ExtendWith(MockitoExtension.class)
@Timeout(30)
class TrainingSchedulerBridgeTest {

    private static final String JOB_ID = "train-1";

    @Mock
    private ResourceAwareJobScheduler scheduler;

    @Mock
    private TrainingSubprocessLauncherApi trainingLauncher;

    @Mock
    private TrainingServiceApi trainingService;

    @InjectMocks
    private TrainingSchedulerBridge bridge;

    @Test
    void schedulerCancelCancelsTheSubprocessRunAndFailsTheJob() {
        ScheduledJob job = submittedJob(true);

        assertThrows(CancellationException.class, () -> job.getExecutor().execute(context(true)));

        verify(trainingLauncher).cancelTraining(JOB_ID);
    }

    @Test
    void schedulerCancelCancelsAnInProcessRunThroughTheTrainingService() {
        ScheduledJob job = submittedJob(false);

        assertThrows(CancellationException.class, () -> job.getExecutor().execute(context(true)));

        verify(trainingService).cancelJob(JOB_ID);
        verify(trainingLauncher, never()).cancelTraining(JOB_ID);
    }

    @Test
    void interruptCancelsTheRunAndFailsTheJob() {
        when(trainingLauncher.getJobStatus(JOB_ID)).thenReturn(status("RUNNING"));
        ScheduledJob job = submittedJob(true);

        Thread.currentThread().interrupt();
        assertThrows(RuntimeException.class, () -> job.getExecutor().execute(context(false)));
        assertTrue(Thread.interrupted(), "the interrupt is restored for the scheduler");

        verify(trainingLauncher).cancelTraining(JOB_ID);
    }

    @Test
    void cancelledRunFailsTheJob() {
        when(trainingLauncher.getJobStatus(JOB_ID)).thenReturn(status("CANCELLED"));
        ScheduledJob job = submittedJob(true);

        assertThrows(CancellationException.class, () -> job.getExecutor().execute(context(false)));
    }

    @Test
    void deadlineCancelsTheRunAndFailsTheJob() {
        assertThrows(TimeoutException.class, () -> bridge.trackTraining(context(false), JOB_ID, true, 0));

        verify(trainingLauncher).cancelTraining(JOB_ID);
    }

    @Test
    void completedRunCompletesTheJob() throws Exception {
        when(trainingLauncher.getJobStatus(JOB_ID)).thenReturn(status("COMPLETED"));
        ScheduledJob job = submittedJob(true);

        job.getExecutor().execute(context(false));

        verify(trainingLauncher, never()).cancelTraining(JOB_ID);
    }

    private ScheduledJob submittedJob(boolean subprocess) {
        bridge.onTrainingJobStarted(new TrainingJobStartedEvent(this, JOB_ID, "model-1", subprocess));
        ArgumentCaptor<ScheduledJob> submitted = ArgumentCaptor.forClass(ScheduledJob.class);
        verify(scheduler).submit(submitted.capture());
        return submitted.getValue();
    }

    private static ScheduledJob.JobExecutionContext context(boolean cancelRequested) {
        return new ScheduledJob.JobExecutionContext(JOB_ID, JobResourceProfiles.TRAINING, null, null,
                new AtomicBoolean(cancelRequested));
    }

    private static TrainingJobStatus status(String status) {
        return TrainingJobStatus.builder().jobId(JOB_ID).status(status).build();
    }
}
