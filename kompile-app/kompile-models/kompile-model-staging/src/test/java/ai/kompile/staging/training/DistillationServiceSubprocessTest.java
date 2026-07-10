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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ai.kompile.staging.training;

import ai.kompile.core.staging.TrainingJobStatus;
import ai.kompile.staging.subprocess.TrainingSubprocessLauncher;
import ai.kompile.staging.web.dto.DistillationConfigRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DistillationServiceSubprocessTest {

    @Test
    void delegatesPublicDistillationLifecycleToTrainingSubprocess() throws Exception {
        TrainingSubprocessLauncher launcher = mock(TrainingSubprocessLauncher.class);
        DistillationService service = new DistillationService(launcher);

        DistillationConfigRequest request = DistillationConfigRequest.builder()
                .teacherModelId("teacher")
                .studentModelId("student")
                .datasetId("/tmp/sft.jsonl")
                .build();
        TrainingJobStatus status = TrainingJobStatus.builder()
                .jobId("distill-sub-1")
                .status("RUNNING")
                .modelId("student")
                .datasetId("/tmp/sft.jsonl")
                .build();

        when(launcher.launchDistillation(request)).thenReturn(status);
        when(launcher.getJobStatus("distill-sub-1")).thenReturn(status);
        when(launcher.cancelTraining("distill-sub-1")).thenReturn(true);

        assertSame(status, service.startDistillation(request));
        assertSame(status, service.getJob("distill-sub-1"));
        assertTrue(service.cancelJob("distill-sub-1"));

        verify(launcher).launchDistillation(request);
        verify(launcher).getJobStatus("distill-sub-1");
        verify(launcher).cancelTraining("distill-sub-1");
    }

    @Test
    void requiresSubprocessLauncherAtConstruction() {
        NullPointerException error = assertThrows(NullPointerException.class,
                () -> new DistillationService(null));

        assertTrue(error.getMessage().contains("trainingSubprocessLauncher"));
    }
}
