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
 * Request for configuring and launching a training job.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingConfigRequest {
    private String modelId;
    private String datasetId;
    private PeftConfigRequest peftConfig;
    private UpdaterConfigDto updaterConfig;
    @Builder.Default
    private String lrSchedule = "COSINE";
    @Builder.Default
    private double warmupRatio = 0.1;
    @Builder.Default
    private int epochs = 3;
    @Builder.Default
    private int batchSize = 8;
    @Builder.Default
    private int gradientAccumulationSteps = 1;
    @Builder.Default
    private int maxSteps = -1;
    @Builder.Default
    private double maxGradNorm = 1.0;
    @Builder.Default
    private boolean fp16 = false;
    @Builder.Default
    private boolean bf16 = false;
    @Builder.Default
    private int loggingSteps = 10;
    @Builder.Default
    private int saveSteps = 500;
    @Builder.Default
    private int evalSteps = 500;
    private String outputDir;
    @Builder.Default
    private int seed = 42;
}
