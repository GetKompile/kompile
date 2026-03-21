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

import ai.kompile.modelmanager.registry.ModelMetadata;
import ai.kompile.modelmanager.registry.ModelStatus;
import ai.kompile.modelmanager.registry.ModelType;
import ai.kompile.modelmanager.registry.TokenizerConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for updating a model entry.
 * Only non-null fields will be updated.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateModelRequest {
    /**
     * Model type (dense_encoder, sparse_encoder, cross_encoder).
     */
    private String type;

    /**
     * Model status (active, staged, deprecated).
     */
    private String status;

    /**
     * Model metadata updates.
     */
    private MetadataUpdate metadata;

    /**
     * Tokenizer configuration updates.
     */
    private TokenizerUpdate tokenizer;

    /**
     * Metadata update fields.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MetadataUpdate {
        private String description;
        private Integer embeddingDim;
        private Integer hiddenSize;
        private Integer numLayers;
        private Integer maxSequenceLength;
        private String framework;
        private String trainingData;
        private String sourceOrigin;
        private String sourceRepository;
        private Integer vocabSize;
    }

    /**
     * Tokenizer configuration update fields.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TokenizerUpdate {
        private Boolean doLowerCase;
        private Boolean addSpecialTokens;
        private Boolean stripAccents;
        private Integer maxLength;
        private String padding;
        private Boolean truncation;
    }

    /**
     * Convert to ModelMetadata.
     */
    public ModelMetadata toModelMetadata() {
        if (metadata == null) {
            return null;
        }
        return ModelMetadata.builder()
                .description(metadata.description)
                .embeddingDim(metadata.embeddingDim)
                .hiddenSize(metadata.hiddenSize)
                .numLayers(metadata.numLayers)
                .maxSequenceLength(metadata.maxSequenceLength)
                .framework(metadata.framework)
                .trainingData(metadata.trainingData)
                .sourceOrigin(metadata.sourceOrigin)
                .sourceRepository(metadata.sourceRepository)
                .vocabSize(metadata.vocabSize)
                .build();
    }

    /**
     * Convert to TokenizerConfig.
     */
    public TokenizerConfig toTokenizerConfig() {
        if (tokenizer == null) {
            return null;
        }
        return TokenizerConfig.builder()
                .doLowerCase(tokenizer.doLowerCase != null ? tokenizer.doLowerCase : true)
                .addSpecialTokens(tokenizer.addSpecialTokens != null ? tokenizer.addSpecialTokens : true)
                .stripAccents(tokenizer.stripAccents != null ? tokenizer.stripAccents : false)
                .maxLength(tokenizer.maxLength != null ? tokenizer.maxLength : 512)
                .padding(tokenizer.padding)
                .truncation(tokenizer.truncation)
                .build();
    }

    /**
     * Convert to ModelType.
     */
    public ModelType toModelType() {
        if (type == null) {
            return null;
        }
        try {
            return ModelType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Convert to ModelStatus.
     */
    public ModelStatus toModelStatus() {
        if (status == null) {
            return null;
        }
        try {
            return ModelStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
