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

package ai.kompile.modelmanager.llm.dynamic;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Dynamic stage definition for LLM pipelines, analogous to {@code VlmStageDefinition}.
 *
 * <p>Describes the interface of a pipeline stage: what it consumes, what it produces,
 * and whether it requires a model component.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LlmStageDefinition {

    private String stageId;
    private String displayName;
    private String inputDescription;
    private String outputDescription;
    private String modelComponentKey;
    private boolean requiresModel;
    private boolean builtin;

    // --- Builtin stage definitions ---

    public static final LlmStageDefinition TOKENIZATION = LlmStageDefinition.builder()
            .stageId("TOKENIZATION")
            .displayName("Tokenization")
            .inputDescription("Text prompt (string)")
            .outputDescription("Token IDs (long array) + attention mask")
            .modelComponentKey("tokenizer")
            .requiresModel(true)
            .builtin(true)
            .build();

    public static final LlmStageDefinition TOKEN_EMBEDDING = LlmStageDefinition.builder()
            .stageId("TOKEN_EMBEDDING")
            .displayName("Token Embedding")
            .inputDescription("Token IDs (long array)")
            .outputDescription("Hidden states (float tensor [batch, seq, hidden])")
            .modelComponentKey("embed_tokens")
            .requiresModel(true)
            .builtin(true)
            .build();

    public static final LlmStageDefinition AUTOREGRESSIVE_DECODING = LlmStageDefinition.builder()
            .stageId("AUTOREGRESSIVE_DECODING")
            .displayName("Autoregressive Decoding")
            .inputDescription("Hidden states + KV cache")
            .outputDescription("Logits (float tensor [batch, 1, vocab]) + updated KV cache")
            .modelComponentKey("decoder")
            .requiresModel(true)
            .builtin(true)
            .build();

    public static final LlmStageDefinition TOKEN_SAMPLING = LlmStageDefinition.builder()
            .stageId("TOKEN_SAMPLING")
            .displayName("Token Sampling")
            .inputDescription("Logits (float tensor)")
            .outputDescription("Sampled token ID + is_eos flag")
            .modelComponentKey(null)
            .requiresModel(false)
            .builtin(true)
            .build();

    public static final LlmStageDefinition TOKEN_DECODING = LlmStageDefinition.builder()
            .stageId("TOKEN_DECODING")
            .displayName("Token Decoding")
            .inputDescription("Generated token IDs (long array)")
            .outputDescription("Response text (string)")
            .modelComponentKey("tokenizer")
            .requiresModel(true)
            .builtin(true)
            .build();
}
