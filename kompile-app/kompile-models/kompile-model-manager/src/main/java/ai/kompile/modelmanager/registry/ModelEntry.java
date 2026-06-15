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

package ai.kompile.modelmanager.registry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a single model entry in the registry.
 * Contains all information needed to locate and use a model.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModelEntry {

    /**
     * Unique model identifier (e.g., "bge-base-en-v1.5").
     */
    @JsonProperty("model_id")
    private String modelId;

    /**
     * Type of model: encoder, cross_encoder, or reranker.
     */
    @JsonProperty("type")
    private ModelType type;

    /**
     * Relative path from model directory to model folder.
     */
    @JsonProperty("path")
    private String path;

    /**
     * Model file name (e.g., "model.sdz").
     */
    @JsonProperty("model_file")
    @Builder.Default
    private String modelFile = "model.sdz";

    /**
     * Vocabulary file name (e.g., "vocab.txt").
     */
    @JsonProperty("vocab_file")
    @Builder.Default
    private String vocabFile = "vocab.txt";

    /**
     * SHA256 checksum of the model file for verification.
     */
    @JsonProperty("checksum")
    private String checksum;

    /**
     * Current status of the model in the registry.
     */
    @JsonProperty("status")
    @Builder.Default
    private ModelStatus status = ModelStatus.ACTIVE;

    /**
     * ISO 8601 timestamp when the model was promoted to active.
     */
    @JsonProperty("promoted_at")
    private String promotedAt;

    /**
     * Model metadata including dimensions, architecture info.
     */
    @JsonProperty("metadata")
    private ModelMetadata metadata;

    /**
     * Tokenizer configuration for the model.
     */
    @JsonProperty("tokenizer")
    private TokenizerConfig tokenizer;

    /**
     * Image preprocessor configuration for VLM models.
     * Controls resize, normalize, crop, pad settings for the vision encoder.
     */
    @JsonProperty("preprocessor")
    private ImagePreprocessorConfig preprocessor;

    /**
     * Check if the model is active and usable.
     */
    @JsonIgnore
    public boolean isActive() {
        return status == ModelStatus.ACTIVE;
    }

    /**
     * Get the effective version from metadata or fall back to null.
     */
    @JsonIgnore
    public String getEffectiveVersion() {
        if (metadata != null && metadata.getVersion() != null) {
            return metadata.getVersion();
        }
        return null;
    }

    /**
     * Get the full relative path to the model file.
     */
    @JsonIgnore
    public String getModelFilePath() {
        return path + "/" + modelFile;
    }

    /**
     * Get the full relative path to the vocab file.
     */
    @JsonIgnore
    public String getVocabFilePath() {
        return path + "/" + vocabFile;
    }

    /**
     * Create an encoder model entry with defaults.
     */
    public static ModelEntry encoder(String modelId, int embeddingDim) {
        return ModelEntry.builder()
                .modelId(modelId)
                .type(ModelType.ENCODER)
                .path("encoders/" + modelId)
                .metadata(ModelMetadata.builder()
                        .embeddingDim(embeddingDim)
                        .framework("samediff")
                        .modelType("dense")
                        .build())
                .tokenizer(TokenizerConfig.defaultBertConfig())
                .status(ModelStatus.ACTIVE)
                .build();
    }

    /**
     * Create a cross-encoder model entry with defaults.
     */
    public static ModelEntry crossEncoder(String modelId, int hiddenSize, int numLayers) {
        return ModelEntry.builder()
                .modelId(modelId)
                .type(ModelType.CROSS_ENCODER)
                .path("cross-encoders/" + modelId)
                .metadata(ModelMetadata.builder()
                        .hiddenSize(hiddenSize)
                        .numLayers(numLayers)
                        .framework("samediff")
                        .build())
                .tokenizer(TokenizerConfig.defaultBertConfig())
                .status(ModelStatus.ACTIVE)
                .build();
    }
}
