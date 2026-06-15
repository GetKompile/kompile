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

package ai.kompile.modelmanager.llm;

/**
 * Pipeline stages for LLM inference, analogous to {@link ai.kompile.modelmanager.vlm.VlmPipelineStage}.
 *
 * <p>Each stage represents a distinct processing step in the LLM pipeline:</p>
 * <ol>
 *   <li>{@link #TOKENIZATION} - Convert text to token IDs</li>
 *   <li>{@link #TOKEN_EMBEDDING} - Convert token IDs to dense embeddings</li>
 *   <li>{@link #AUTOREGRESSIVE_DECODING} - Autoregressive generation with KV cache</li>
 *   <li>{@link #TOKEN_SAMPLING} - Sample next token from logits</li>
 *   <li>{@link #TOKEN_DECODING} - Convert generated token IDs to text</li>
 * </ol>
 */
public enum LlmPipelineStage {

    TOKENIZATION("tokenization", "Tokenization",
            "Converts input text to token IDs using the model's tokenizer",
            "tokenizer"),

    TOKEN_EMBEDDING("token_embedding", "Token Embedding",
            "Converts token IDs to dense vector embeddings via embed_tokens layer",
            "embed_tokens"),

    AUTOREGRESSIVE_DECODING("autoregressive_decoding", "Autoregressive Decoding",
            "Runs the decoder model with KV cache for autoregressive generation",
            "decoder"),

    TOKEN_SAMPLING("token_sampling", "Token Sampling",
            "Samples next token from logits using configured strategy (greedy, top-k, top-p)",
            null),

    TOKEN_DECODING("token_decoding", "Token Decoding",
            "Converts generated token IDs back to human-readable text",
            "tokenizer");

    private final String id;
    private final String displayName;
    private final String description;
    private final String modelComponentKey;

    LlmPipelineStage(String id, String displayName, String description, String modelComponentKey) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
        this.modelComponentKey = modelComponentKey;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public String getModelComponentKey() { return modelComponentKey; }
    public boolean requiresModel() { return modelComponentKey != null; }

    /**
     * Find a stage by its ID string.
     */
    public static LlmPipelineStage fromId(String id) {
        for (LlmPipelineStage stage : values()) {
            if (stage.id.equals(id)) {
                return stage;
            }
        }
        throw new IllegalArgumentException("Unknown LLM pipeline stage: " + id);
    }
}
