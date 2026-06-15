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
 *  limitations under the License.
 */

package ai.kompile.staging.staging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Configuration for benchmark validation of staged models.
 * Defines thresholds that a model must meet before promotion to production.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BenchmarkConfig {

    /**
     * Minimum throughput in tokens per second. 0 = no minimum.
     */
    @JsonProperty("min_throughput")
    @Builder.Default
    private double minThroughput = 0;

    /**
     * Maximum P99 latency in milliseconds. 0 = no maximum.
     */
    @JsonProperty("max_latency_p99")
    @Builder.Default
    private double maxLatencyP99 = 0;

    /**
     * Minimum token diversity (unique tokens / total tokens). 0 = no minimum.
     */
    @JsonProperty("min_token_diversity")
    @Builder.Default
    private double minTokenDiversity = 0;

    /**
     * Whether to validate output structure (e.g., DocTags format).
     */
    @JsonProperty("validate_structure")
    @Builder.Default
    private boolean validateStructure = false;

    /**
     * Model ID of the baseline model to compare against.
     * If set, regression detection is performed against this model's metrics.
     */
    @JsonProperty("baseline_model_id")
    private String baselineModelId;

    /**
     * Maximum allowed throughput regression percentage compared to baseline.
     */
    @JsonProperty("max_regression_percent")
    @Builder.Default
    private double maxRegressionPercent = 5.0;

    /**
     * Number of warmup iterations before measurement.
     */
    @JsonProperty("warmup_iterations")
    @Builder.Default
    private int warmupIterations = 3;

    /**
     * Number of measurement iterations for computing metrics.
     */
    @JsonProperty("measurement_iterations")
    @Builder.Default
    private int measurementIterations = 10;
}
