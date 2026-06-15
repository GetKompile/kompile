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

import ai.kompile.modelmanager.ModelConstants;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Defines a complete set of models needed for an LLM pipeline.
 *
 * <p>A model set groups all components required to run a specific LLM architecture,
 * enabling batch download and validation of the complete pipeline.</p>
 *
 * <p>Analogous to {@link ai.kompile.modelmanager.vlm.VlmModelSet}.</p>
 *
 * <h2>Example: SmolLM Model Set</h2>
 * <pre>
 * LlmModelSet smolLm = LlmModelSet.SMOLLM_135M_INSTRUCT;
 *
 * // Download all components at once
 * LlmModelSetDownloader.getInstance().downloadModelSet(smolLm);
 *
 * // Check which stages have models available
 * for (LlmPipelineStage stage : smolLm.getStagesRequiringModels()) {
 *     System.out.println(stage.getDisplayName() + " -> " +
 *         smolLm.getComponentForStage(stage).getFileName());
 * }
 * </pre>
 */
public class LlmModelSet {

    private final String setId;
    private final String displayName;
    private final String description;
    private final String huggingFaceRepo;
    private final List<LlmModelComponent> components;
    private final Map<String, Object> pipelineConfig;

    // --- Predefined model sets ---

    public static final LlmModelSet SMOLLM_135M_INSTRUCT;
    public static final LlmModelSet SMOLLM_360M_INSTRUCT;
    public static final LlmModelSet PHI_2;

    private static final Map<String, LlmModelSet> ALL_MODEL_SETS;

    static {
        String baseUrl = ModelConstants.KOMPILE_MODEL_BASE_URL;

        SMOLLM_135M_INSTRUCT = new LlmModelSet(
                "smollm-135m-instruct",
                "SmolLM 135M Instruct",
                "Small efficient language model for edge deployment. 135M parameters with instruction tuning.",
                "HuggingFaceTB/SmolLM-135M-Instruct",
                Arrays.asList(
                        LlmModelComponent.builder()
                                .componentKey("embed_tokens")
                                .fileName("embed_tokens.fb")
                                .downloadUrl(baseUrl + "samediff-llm/smollm-135m-instruct/embed_tokens.fb")
                                .pipelineStage(LlmPipelineStage.TOKEN_EMBEDDING)
                                .description("Token embedding layer - converts token IDs to 576-dim vectors")
                                .inputShape(1, -1) // [batch, seq_len]
                                .outputShape(1, -1, 576) // [batch, seq_len, hidden]
                                .build(),
                        LlmModelComponent.builder()
                                .componentKey("decoder")
                                .fileName("decoder.fb")
                                .downloadUrl(baseUrl + "samediff-llm/smollm-135m-instruct/decoder.fb")
                                .pipelineStage(LlmPipelineStage.AUTOREGRESSIVE_DECODING)
                                .description("30-layer transformer decoder with grouped-query attention")
                                .inputShape(1, -1, 576) // [batch, seq_len, hidden]
                                .outputShape(1, -1, 49152) // [batch, seq_len, vocab]
                                .build(),
                        LlmModelComponent.builder()
                                .componentKey("tokenizer")
                                .fileName("tokenizer.json")
                                .downloadUrl(baseUrl + "samediff-llm/smollm-135m-instruct/tokenizer.json")
                                .pipelineStage(LlmPipelineStage.TOKENIZATION)
                                .description("HuggingFace tokenizer configuration")
                                .build()
                ),
                Map.of(
                        "architecture", "smollm",
                        "vocab_size", 49152,
                        "hidden_size", 576,
                        "num_layers", 30,
                        "num_heads", 9,
                        "num_kv_heads", 6,
                        "head_dim", 64,
                        "max_position_embeddings", 2048,
                        "eos_token_id", 2,
                        "pad_token_id", 0
                )
        );

        SMOLLM_360M_INSTRUCT = new LlmModelSet(
                "smollm-360m-instruct",
                "SmolLM 360M Instruct",
                "Medium-size language model with better quality. 360M parameters with instruction tuning.",
                "HuggingFaceTB/SmolLM-360M-Instruct",
                Arrays.asList(
                        LlmModelComponent.builder()
                                .componentKey("embed_tokens")
                                .fileName("embed_tokens.fb")
                                .downloadUrl(baseUrl + "samediff-llm/smollm-360m-instruct/embed_tokens.fb")
                                .pipelineStage(LlmPipelineStage.TOKEN_EMBEDDING)
                                .description("Token embedding layer - converts token IDs to 960-dim vectors")
                                .inputShape(1, -1)
                                .outputShape(1, -1, 960)
                                .build(),
                        LlmModelComponent.builder()
                                .componentKey("decoder")
                                .fileName("decoder.fb")
                                .downloadUrl(baseUrl + "samediff-llm/smollm-360m-instruct/decoder.fb")
                                .pipelineStage(LlmPipelineStage.AUTOREGRESSIVE_DECODING)
                                .description("32-layer transformer decoder with grouped-query attention")
                                .inputShape(1, -1, 960)
                                .outputShape(1, -1, 49152)
                                .build(),
                        LlmModelComponent.builder()
                                .componentKey("tokenizer")
                                .fileName("tokenizer.json")
                                .downloadUrl(baseUrl + "samediff-llm/smollm-360m-instruct/tokenizer.json")
                                .pipelineStage(LlmPipelineStage.TOKENIZATION)
                                .description("HuggingFace tokenizer configuration")
                                .build()
                ),
                Map.of(
                        "architecture", "smollm",
                        "vocab_size", 49152,
                        "hidden_size", 960,
                        "num_layers", 32,
                        "num_heads", 15,
                        "num_kv_heads", 10,
                        "head_dim", 64,
                        "max_position_embeddings", 2048,
                        "eos_token_id", 2,
                        "pad_token_id", 0
                )
        );

        PHI_2 = new LlmModelSet(
                "phi-2",
                "Phi-2",
                "Microsoft's compact general-purpose model. 2.7B parameters with strong reasoning capabilities.",
                "microsoft/phi-2",
                Arrays.asList(
                        LlmModelComponent.builder()
                                .componentKey("embed_tokens")
                                .fileName("embed_tokens.fb")
                                .downloadUrl(baseUrl + "samediff-llm/phi-2/embed_tokens.fb")
                                .pipelineStage(LlmPipelineStage.TOKEN_EMBEDDING)
                                .description("Token embedding layer - converts token IDs to 2560-dim vectors")
                                .inputShape(1, -1)
                                .outputShape(1, -1, 2560)
                                .build(),
                        LlmModelComponent.builder()
                                .componentKey("decoder")
                                .fileName("decoder.fb")
                                .downloadUrl(baseUrl + "samediff-llm/phi-2/decoder.fb")
                                .pipelineStage(LlmPipelineStage.AUTOREGRESSIVE_DECODING)
                                .description("32-layer transformer decoder with multi-head attention")
                                .inputShape(1, -1, 2560)
                                .outputShape(1, -1, 50257)
                                .build(),
                        LlmModelComponent.builder()
                                .componentKey("tokenizer")
                                .fileName("tokenizer.json")
                                .downloadUrl(baseUrl + "samediff-llm/phi-2/tokenizer.json")
                                .pipelineStage(LlmPipelineStage.TOKENIZATION)
                                .description("HuggingFace tokenizer configuration")
                                .build()
                ),
                Map.of(
                        "architecture", "phi",
                        "vocab_size", 50257,
                        "hidden_size", 2560,
                        "num_layers", 32,
                        "num_heads", 32,
                        "num_kv_heads", 32,
                        "head_dim", 80,
                        "max_position_embeddings", 2048,
                        "eos_token_id", 50256,
                        "pad_token_id", 50256
                )
        );

        Map<String, LlmModelSet> allSets = new LinkedHashMap<>();
        allSets.put(SMOLLM_135M_INSTRUCT.getSetId(), SMOLLM_135M_INSTRUCT);
        allSets.put(SMOLLM_360M_INSTRUCT.getSetId(), SMOLLM_360M_INSTRUCT);
        allSets.put(PHI_2.getSetId(), PHI_2);
        ALL_MODEL_SETS = Collections.unmodifiableMap(allSets);
    }

    public LlmModelSet(String setId, String displayName, String description,
                        String huggingFaceRepo, List<LlmModelComponent> components,
                        Map<String, Object> pipelineConfig) {
        this.setId = setId;
        this.displayName = displayName;
        this.description = description;
        this.huggingFaceRepo = huggingFaceRepo;
        this.components = Collections.unmodifiableList(new ArrayList<>(components));
        this.pipelineConfig = pipelineConfig != null ? Collections.unmodifiableMap(new HashMap<>(pipelineConfig)) : Collections.emptyMap();
    }

    // --- Getters ---

    public String getSetId() { return setId; }
    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public String getHuggingFaceRepo() { return huggingFaceRepo; }
    public List<LlmModelComponent> getComponents() { return components; }
    public Map<String, Object> getPipelineConfig() { return pipelineConfig; }

    // --- Convenience methods ---

    public LlmModelComponent getComponent(String componentKey) {
        return components.stream()
                .filter(c -> c.getComponentKey().equals(componentKey))
                .findFirst()
                .orElse(null);
    }

    public LlmModelComponent getComponentForStage(LlmPipelineStage stage) {
        return components.stream()
                .filter(c -> c.getPipelineStage() == stage)
                .findFirst()
                .orElse(null);
    }

    public Set<LlmPipelineStage> getStagesRequiringModels() {
        return components.stream()
                .filter(c -> c.getPipelineStage() != null)
                .map(LlmModelComponent::getPipelineStage)
                .collect(Collectors.toSet());
    }

    // --- Pipeline config accessors ---

    public String getArchitecture() {
        return (String) pipelineConfig.getOrDefault("architecture", "unknown");
    }

    public int getVocabSize() {
        return ((Number) pipelineConfig.getOrDefault("vocab_size", 0)).intValue();
    }

    public int getHiddenSize() {
        return ((Number) pipelineConfig.getOrDefault("hidden_size", 0)).intValue();
    }

    public int getNumLayers() {
        return ((Number) pipelineConfig.getOrDefault("num_layers", 0)).intValue();
    }

    public int getNumHeads() {
        return ((Number) pipelineConfig.getOrDefault("num_heads", 0)).intValue();
    }

    public int getNumKvHeads() {
        return ((Number) pipelineConfig.getOrDefault("num_kv_heads", 0)).intValue();
    }

    public int getHeadDim() {
        return ((Number) pipelineConfig.getOrDefault("head_dim", 0)).intValue();
    }

    public int getMaxPositionEmbeddings() {
        return ((Number) pipelineConfig.getOrDefault("max_position_embeddings", 2048)).intValue();
    }

    public int getEosTokenId() {
        return ((Number) pipelineConfig.getOrDefault("eos_token_id", 2)).intValue();
    }

    public int getPadTokenId() {
        return ((Number) pipelineConfig.getOrDefault("pad_token_id", 0)).intValue();
    }

    // --- Static accessors ---

    public static LlmModelSet getModelSet(String setId) {
        return ALL_MODEL_SETS.get(setId);
    }

    public static Map<String, LlmModelSet> getAllModelSets() {
        return ALL_MODEL_SETS;
    }

    public static boolean isModelSetSupported(String setId) {
        return ALL_MODEL_SETS.containsKey(setId);
    }

    @Override
    public String toString() {
        return "LlmModelSet{" +
                "setId='" + setId + '\'' +
                ", displayName='" + displayName + '\'' +
                ", architecture='" + getArchitecture() + '\'' +
                ", components=" + components.size() +
                '}';
    }
}
