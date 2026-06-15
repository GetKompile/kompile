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

package ai.kompile.staging.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Request for configuring a knowledge distillation job.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DistillationConfigRequest {
    private String teacherModelId;
    private String studentModelId;
    private String distillationType; // LOGIT_KD, FEATURE_KD, ATTENTION_KD, COMBINED
    @Builder.Default
    private double temperature = 4.0;
    @Builder.Default
    private double alpha = 0.5;
    private Map<String, String> layerMappings;
    private String datasetId;
    private TrainingConfigRequest trainingConfig;
    private PeftConfigRequest studentPeftConfig;
}
