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

package ai.kompile.staging.conversion.ggml;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Model information extracted from GGML/GGUF files.
 *
 * <p>GGUF (GPT-Generated Unified Format) is the successor to GGML format,
 * used primarily for quantized LLM inference with llama.cpp and compatible runtimes.</p>
 *
 * <h3>Supported Formats:</h3>
 * <ul>
 *   <li><b>GGUF</b> - Modern format with extensible metadata (magic: "GGUF")</li>
 *   <li><b>GGML</b> - Legacy format (magic: 0x67676d6c "ggml")</li>
 * </ul>
 *
 * <h3>Common Quantization Types:</h3>
 * <ul>
 *   <li>Q4_0, Q4_1 - 4-bit quantization</li>
 *   <li>Q5_0, Q5_1 - 5-bit quantization</li>
 *   <li>Q8_0 - 8-bit quantization</li>
 *   <li>Q2_K, Q3_K_S/M/L, Q4_K_S/M, Q5_K_S/M, Q6_K - K-quant variants</li>
 *   <li>F16 - 16-bit float</li>
 *   <li>F32 - 32-bit float</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GgmlModelInfo {

    /**
     * Format version: "gguf" or "ggml"
     */
    private String format;

    /**
     * GGUF version number (1, 2, or 3)
     */
    private int version;

    /**
     * Model architecture: llama, mistral, phi, qwen, gemma, etc.
     */
    private String architecture;

    /**
     * Model name/identifier
     */
    private String modelName;

    /**
     * Quantization type: Q4_0, Q4_K_M, Q5_K_S, Q8_0, F16, etc.
     */
    private String quantizationType;

    /**
     * Number of parameters in the model
     */
    private long parameterCount;

    /**
     * Context length (max sequence length)
     */
    private int contextLength;

    /**
     * Embedding dimension (hidden size)
     */
    private int embeddingDimension;

    /**
     * Number of attention heads
     */
    private int numAttentionHeads;

    /**
     * Number of key-value heads (for GQA)
     */
    private int numKvHeads;

    /**
     * Number of layers
     */
    private int numLayers;

    /**
     * Vocabulary size
     */
    private int vocabSize;

    /**
     * Feed-forward hidden dimension
     */
    private int feedForwardDimension;

    /**
     * RoPE frequency base (for rotary position embeddings)
     */
    private float ropeFrequencyBase;

    /**
     * RoPE scaling factor
     */
    private float ropeScalingFactor;

    /**
     * BOS (beginning of sequence) token ID
     */
    private int bosTokenId;

    /**
     * EOS (end of sequence) token ID
     */
    private int eosTokenId;

    /**
     * PAD token ID
     */
    private int padTokenId;

    /**
     * Number of tensors in the model
     */
    private int tensorCount;

    /**
     * Total model file size in bytes
     */
    private long fileSizeBytes;

    /**
     * Additional metadata key-value pairs
     */
    private Map<String, Object> metadata;

    /**
     * Whether the file was successfully parsed
     */
    private boolean valid;

    /**
     * Error message if parsing failed
     */
    private String errorMessage;

    /**
     * Create an info object for an invalid/unparseable file
     */
    public static GgmlModelInfo invalid(String errorMessage) {
        return GgmlModelInfo.builder()
                .valid(false)
                .errorMessage(errorMessage)
                .build();
    }

    /**
     * Get a human-readable summary of the model
     */
    public String getSummary() {
        if (!valid) {
            return "Invalid GGML file: " + errorMessage;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(format.toUpperCase()).append(" v").append(version);
        if (architecture != null) {
            sb.append(" | ").append(architecture);
        }
        if (modelName != null) {
            sb.append(" (").append(modelName).append(")");
        }
        if (quantizationType != null) {
            sb.append(" | ").append(quantizationType);
        }
        if (parameterCount > 0) {
            sb.append(" | ").append(formatParameterCount(parameterCount));
        }
        if (contextLength > 0) {
            sb.append(" | ctx=").append(contextLength);
        }
        return sb.toString();
    }

    private String formatParameterCount(long count) {
        if (count >= 1_000_000_000) {
            return String.format("%.1fB params", count / 1_000_000_000.0);
        } else if (count >= 1_000_000) {
            return String.format("%.1fM params", count / 1_000_000.0);
        } else if (count >= 1_000) {
            return String.format("%.1fK params", count / 1_000.0);
        }
        return count + " params";
    }
}
