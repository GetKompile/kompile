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
 * Request DTO for LLM text generation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmGenerateRequest {
    private String prompt;
    @Builder.Default
    private int maxTokens = 256;
    @Builder.Default
    private double temperature = 1.0;
    @Builder.Default
    private int topK = 0;
    @Builder.Default
    private double topP = 1.0;
    @Builder.Default
    private double repetitionPenalty = 1.0;
    @Builder.Default
    private boolean doSample = false;
    /**
     * Optional preset name: "greedy", "default", "creative", "precise".
     * When set, overrides individual sampling parameters.
     */
    private String presetName;

    /**
     * Stop sequences that terminate generation when produced.
     */
    private List<String> stopSequences;

    /**
     * Random seed for reproducible generation. -1 for non-deterministic.
     */
    @Builder.Default
    private long seed = -1;

    /**
     * Minimum number of tokens to generate before allowing EOS.
     */
    @Builder.Default
    private int minTokens = 0;

    /**
     * Frequency penalty: penalizes tokens by how often they appear.
     * Range: 0.0 (no penalty) to 2.0
     */
    @Builder.Default
    private double frequencyPenalty = 0.0;

    /**
     * Presence penalty: penalizes tokens that have appeared at all.
     * Range: 0.0 (no penalty) to 2.0
     */
    @Builder.Default
    private double presencePenalty = 0.0;
}
