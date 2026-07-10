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
 * limitations under the License.
 */

package ai.kompile.staging.training;

import ai.kompile.core.staging.TrainingJobStatus;
import ai.kompile.staging.subprocess.TrainingSubprocessLauncher;
import ai.kompile.staging.web.dto.DistillationConfigRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Public lifecycle facade for knowledge-distillation jobs.
 *
 * <p>Distillation uses the same isolated subprocess runtime as fine-tuning and
 * PEFT jobs. Keeping lifecycle ownership in {@link TrainingSubprocessLauncher}
 * ensures JSONL datasets, model resources, cancellation, history, and SSE logs
 * all follow one implementation.</p>
 */
@Service
public class DistillationService {

    private final TrainingSubprocessLauncher subprocessLauncher;

    public DistillationService(TrainingSubprocessLauncher subprocessLauncher) {
        this.subprocessLauncher = Objects.requireNonNull(
                subprocessLauncher, "trainingSubprocessLauncher");
    }

    /**
     * Start an isolated knowledge-distillation job.
     */
    public TrainingJobStatus startDistillation(DistillationConfigRequest request) {
        try {
            return subprocessLauncher.launchDistillation(request);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to launch distillation subprocess", e);
        }
    }

    /**
     * Return the current status of a distillation job.
     */
    public TrainingJobStatus getJob(String jobId) {
        return subprocessLauncher.getJobStatus(jobId);
    }

    /**
     * Request cancellation of a running distillation job.
     */
    public boolean cancelJob(String jobId) {
        return subprocessLauncher.cancelTraining(jobId);
    }

    /**
     * Subscribe to live subprocess logs for a distillation job.
     */
    public SseEmitter subscribeToJobLogs(String jobId) {
        return subprocessLauncher.subscribeToJobLogs(jobId);
    }

    /**
     * Return only objectives implemented by the subprocess training graph.
     */
    public List<Map<String, String>> getAvailableDistillationTypes() {
        Map<String, String> logitKd = new LinkedHashMap<>();
        logitKd.put("id", "LOGIT_KD");
        logitKd.put("name", "Logit Knowledge Distillation");
        logitKd.put("description",
                "Transfers knowledge by matching raw teacher and student logits "
                        + "with temperature-scaled KL divergence.");
        return List.of(logitKd);
    }
}
