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

/**
 * Dynamic stage definition for LLM pipelines, analogous to {@code VlmStageDefinition}.
 *
 * <p>Describes the interface of a pipeline stage: what it consumes, what it produces,
 * and whether it requires a model component.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LlmStageDefinition {

    private String stageId;
    private String displayName;
    private String inputDescription;
    private String outputDescription;
    private String modelComponentKey;
    private boolean requiresModel;
    private boolean isBuiltin;

    public LlmStageDefinition() {}

    public LlmStageDefinition(String stageId, String displayName,
                               String inputDescription, String outputDescription,
                               String modelComponentKey, boolean requiresModel, boolean isBuiltin) {
        this.stageId = stageId;
        this.displayName = displayName;
        this.inputDescription = inputDescription;
        this.outputDescription = outputDescription;
        this.modelComponentKey = modelComponentKey;
        this.requiresModel = requiresModel;
        this.isBuiltin = isBuiltin;
    }

    // --- Builtin stage definitions ---

    public static final LlmStageDefinition TOKENIZATION = new LlmStageDefinition(
            "TOKENIZATION", "Tokenization",
            "Text prompt (string)", "Token IDs (long array) + attention mask",
            "tokenizer", true, true);

    public static final LlmStageDefinition TOKEN_EMBEDDING = new LlmStageDefinition(
            "TOKEN_EMBEDDING", "Token Embedding",
            "Token IDs (long array)", "Hidden states (float tensor [batch, seq, hidden])",
            "embed_tokens", true, true);

    public static final LlmStageDefinition AUTOREGRESSIVE_DECODING = new LlmStageDefinition(
            "AUTOREGRESSIVE_DECODING", "Autoregressive Decoding",
            "Hidden states + KV cache", "Logits (float tensor [batch, 1, vocab]) + updated KV cache",
            "decoder", true, true);

    public static final LlmStageDefinition TOKEN_SAMPLING = new LlmStageDefinition(
            "TOKEN_SAMPLING", "Token Sampling",
            "Logits (float tensor)", "Sampled token ID + is_eos flag",
            null, false, true);

    public static final LlmStageDefinition TOKEN_DECODING = new LlmStageDefinition(
            "TOKEN_DECODING", "Token Decoding",
            "Generated token IDs (long array)", "Response text (string)",
            "tokenizer", true, true);

    // --- Getters and Setters ---

    public String getStageId() { return stageId; }
    public void setStageId(String stageId) { this.stageId = stageId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getInputDescription() { return inputDescription; }
    public void setInputDescription(String inputDescription) { this.inputDescription = inputDescription; }
    public String getOutputDescription() { return outputDescription; }
    public void setOutputDescription(String outputDescription) { this.outputDescription = outputDescription; }
    public String getModelComponentKey() { return modelComponentKey; }
    public void setModelComponentKey(String modelComponentKey) { this.modelComponentKey = modelComponentKey; }
    public boolean isRequiresModel() { return requiresModel; }
    public void setRequiresModel(boolean requiresModel) { this.requiresModel = requiresModel; }
    public boolean isBuiltin() { return isBuiltin; }
    public void setBuiltin(boolean builtin) { isBuiltin = builtin; }
}
