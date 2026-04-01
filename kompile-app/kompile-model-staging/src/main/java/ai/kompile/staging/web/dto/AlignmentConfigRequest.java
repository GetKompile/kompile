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

/**
 * Request for configuring an alignment training job (RLHF/DPO/etc.).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlignmentConfigRequest {
    private String algorithm; // DPO, KTO, ORPO, PPO, GRPO
    private String baseModelId;
    private String rewardModelId;
    private String datasetId;
    @Builder.Default
    private double beta = 0.1;
    @Builder.Default
    private double labelSmoothness = 0.0;
    @Builder.Default
    private int maxPromptLength = 512;
    @Builder.Default
    private int maxCompletionLength = 256;
    private TrainingConfigRequest trainingConfig;
    private PeftConfigRequest peftConfig;
}
