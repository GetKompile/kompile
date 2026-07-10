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

package ai.kompile.staging.subprocess;

import ai.kompile.staging.web.dto.DistillationConfigRequest;
import ai.kompile.staging.web.dto.TrainingConfigRequest;
import ai.kompile.staging.web.dto.UpdaterConfigDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrainingSubprocessLauncherDistillationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TrainingSubprocessLauncher launcher =
            new TrainingSubprocessLauncher(objectMapper, null);

    @Test
    void mapsDistillationRequestAndJsonlDatasetIntoSubprocessArgs() throws Exception {
        TrainingConfigRequest training = TrainingConfigRequest.builder()
                .datasetId("/tmp/training.jsonl")
                .epochs(2)
                .batchSize(3)
                .maxSteps(7)
                .seed(123)
                .updaterConfig(UpdaterConfigDto.builder().learningRate(2.5e-4).build())
                .build();
        DistillationConfigRequest request = DistillationConfigRequest.builder()
                .teacherModelId("teacher-model")
                .studentModelId("student-model")
                .distillationType("LOGIT_KD")
                .temperature(3.5)
                .alpha(1.0)
                .trainingConfig(training)
                .build();

        TrainingSubprocessArgs args = launcher.createDistillationArgs("distill-sub-9", request);

        assertEquals("DISTILLATION", args.trainingType());
        assertEquals("student-model", args.modelId());
        assertEquals("/tmp/training.jsonl", args.datasetId());
        assertEquals(2, args.epochs());
        assertEquals(3, args.batchSize());
        assertEquals(7, args.maxSteps());
        assertEquals(123, args.seed());
        assertEquals(2.5e-4, args.learningRate(), 1e-12);

        Map<?, ?> distillation = objectMapper.readValue(args.distillationConfigJson(), Map.class);
        assertEquals("teacher-model", distillation.get("teacherModelId"));
        assertEquals("student-model", distillation.get("studentModelId"));
        assertEquals(3.5, ((Number) distillation.get("temperature")).doubleValue(), 1e-12);
        assertEquals(1.0, ((Number) distillation.get("alpha")).doubleValue(), 1e-12);
    }

    @Test
    void rejectsMixedHardLabelAlphaUntilLabelsAreExplicitlyMapped() {
        DistillationConfigRequest request = DistillationConfigRequest.builder()
                .teacherModelId("teacher-model")
                .studentModelId("student-model")
                .datasetId("/tmp/training.jsonl")
                .alpha(0.5)
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> launcher.createDistillationArgs("distill-sub-10", request));

        assertTrue(error.getMessage().contains("alpha=1.0"));
    }

    @Test
    void rejectsDistillationWithoutDataset() {
        DistillationConfigRequest request = DistillationConfigRequest.builder()
                .teacherModelId("teacher-model")
                .studentModelId("student-model")
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> launcher.createDistillationArgs("distill-sub-10", request));

        assertTrue(error.getMessage().contains("datasetId"));
    }
}
