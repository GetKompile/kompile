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
package ai.kompile.core.staging;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Status of a training job. Shared between kompile-app-main and kompile-model-staging.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingJobStatus {
    private String jobId;
    private String status;
    private String modelId;
    private String datasetId;
    private int currentEpoch;
    private int totalEpochs;
    private long currentStep;
    private long totalSteps;
    private double loss;
    private double learningRate;
    private double epochProgress;
    private double overallProgress;
    private Map<String, Double> metrics;
    private String startedAt;
    private String completedAt;
    private long elapsedMs;
    private String error;
    private String outputModelPath;
}
