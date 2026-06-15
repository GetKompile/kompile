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
 * Configuration DTO for decoder and pipeline settings.
 * Controls how the generation pipeline processes tokens.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DecoderConfigRequest {
    /**
     * Explicit path to the decoder model file.
     * When set, overrides the model path derived from modelId.
     */
    private String decoderPath;

    /**
     * End-of-sequence token ID. Generation stops when this token is produced.
     * Set to -1 to use the model's default EOS token.
     */
    @Builder.Default
    private int eosTokenId = -1;

    /**
     * Maximum context length (prompt + generated tokens).
     * Set to 0 to use the model's default context window.
     */
    @Builder.Default
    private int maxContextLength = 0;

    /**
     * Minimum number of tokens to generate before allowing EOS.
     */
    @Builder.Default
    private int minNewTokens = 0;

    /**
     * Stop sequences that terminate generation when produced.
     */
    private List<String> stopSequences;

    /**
     * Random seed for reproducible generation. Set to -1 for non-deterministic.
     */
    @Builder.Default
    private long seed = -1;

    /**
     * Frequency penalty: penalizes tokens based on how often they appear in the
     * generated text so far. Higher values reduce repetition.
     * Range: 0.0 (no penalty) to 2.0
     */
    @Builder.Default
    private double frequencyPenalty = 0.0;

    /**
     * Presence penalty: penalizes tokens based on whether they have appeared
     * in the generated text at all. Higher values encourage topic diversity.
     * Range: 0.0 (no penalty) to 2.0
     */
    @Builder.Default
    private double presencePenalty = 0.0;

    /**
     * Number of attention heads in the decoder (for KV cache sizing).
     * Set to 0 to auto-detect from model.
     */
    @Builder.Default
    private int numHeads = 0;

    /**
     * Dimension per attention head (for KV cache sizing).
     * Set to 0 to auto-detect from model.
     */
    @Builder.Default
    private int headDim = 0;

    /**
     * Number of KV cache layers in the decoder.
     * Set to 0 to auto-detect from model.
     */
    @Builder.Default
    private int numKvLayers = 0;
}
