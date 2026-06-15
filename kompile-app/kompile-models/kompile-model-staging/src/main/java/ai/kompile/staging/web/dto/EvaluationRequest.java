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

import java.util.List;

/**
 * Request for evaluating a model against a dataset.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationRequest {
    private String modelId;
    private String datasetId;
    private List<String> metrics; // e.g. "perplexity", "accuracy", "f1", "bleu", "rouge"
    @Builder.Default
    private int batchSize = 8;
    @Builder.Default
    private int maxSamples = -1;
    private String benchmarkName; // run a built-in benchmark instead of custom dataset
    @Builder.Default
    private int numFewShot = -1; // -1 means use benchmark default
    @Builder.Default
    private int maxNewTokens = -1; // -1 means use benchmark default
    @Builder.Default
    private boolean logSamples = false; // include per-sample results
}
