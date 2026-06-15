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

import ai.kompile.modelmanager.registry.ImagePreprocessorConfig;
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
     * Image preprocessor configuration updates (for VLM models).
     */
    private PreprocessorUpdate preprocessor;

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
        // Pipeline identity fields
        private String encoderType;
        private String ragRole;
        private String version;
        // OCR-specific fields
        private Integer inputHeight;
        private Integer inputWidth;
        private java.util.List<String> supportedLanguages;
        private Boolean supportsBatch;
        private Integer maxBatchSize;
        private Boolean supportsHandwriting;
        private Double averageAccuracy;
        private Integer ocrVocabSize;
        private Boolean usesCtc;
        // VLM-specific fields
        private Integer visionFrames;
        private Integer imageSize;
        private Integer tileSize;
        private java.util.List<String> components;
        // Vision encoder IO config
        private String visionEncoderPixelValuesName;
        private String visionEncoderPixelAttentionMaskName;
        private String visionEncoderPrimaryOutputName;
        private java.util.List<String> visionEncoderOutputNames;
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
     * Image preprocessor configuration update fields (for VLM models).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PreprocessorUpdate {
        private String imageProcessorType;
        private Boolean doResize;
        private Integer sizeHeight;
        private Integer sizeWidth;
        private Integer sizeShortestEdge;
        private Integer sizeLongestEdge;
        private Integer resample;
        private Boolean doRescale;
        private Double rescaleFactor;
        private Boolean doNormalize;
        private double[] imageMean;
        private double[] imageStd;
        private Boolean doConvertRgb;
        private Boolean doCenterCrop;
        private Integer cropSizeHeight;
        private Integer cropSizeWidth;
        private Boolean doPad;
        private Integer padSizeHeight;
        private Integer padSizeWidth;
        private Integer patchSize;
        private Integer numChannels;
    }

    /**
     * Convert to ImagePreprocessorConfig.
     */
    public ImagePreprocessorConfig toImagePreprocessorConfig() {
        if (preprocessor == null) {
            return null;
        }
        return ImagePreprocessorConfig.builder()
                .imageProcessorType(preprocessor.imageProcessorType)
                .doResize(preprocessor.doResize != null ? preprocessor.doResize : true)
                .sizeHeight(preprocessor.sizeHeight)
                .sizeWidth(preprocessor.sizeWidth)
                .sizeShortestEdge(preprocessor.sizeShortestEdge)
                .sizeLongestEdge(preprocessor.sizeLongestEdge)
                .resample(preprocessor.resample)
                .doRescale(preprocessor.doRescale != null ? preprocessor.doRescale : true)
                .rescaleFactor(preprocessor.rescaleFactor != null ? preprocessor.rescaleFactor : 1.0 / 255.0)
                .doNormalize(preprocessor.doNormalize != null ? preprocessor.doNormalize : true)
                .imageMean(preprocessor.imageMean)
                .imageStd(preprocessor.imageStd)
                .doConvertRgb(preprocessor.doConvertRgb != null ? preprocessor.doConvertRgb : true)
                .doCenterCrop(preprocessor.doCenterCrop != null ? preprocessor.doCenterCrop : false)
                .cropSizeHeight(preprocessor.cropSizeHeight)
                .cropSizeWidth(preprocessor.cropSizeWidth)
                .doPad(preprocessor.doPad != null ? preprocessor.doPad : false)
                .padSizeHeight(preprocessor.padSizeHeight)
                .padSizeWidth(preprocessor.padSizeWidth)
                .patchSize(preprocessor.patchSize)
                .numChannels(preprocessor.numChannels != null ? preprocessor.numChannels : 3)
                .build();
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
                // Pipeline identity
                .encoderType(metadata.encoderType)
                .ragRole(metadata.ragRole)
                .version(metadata.version)
                // OCR fields
                .inputHeight(metadata.inputHeight)
                .inputWidth(metadata.inputWidth)
                .supportedLanguages(metadata.supportedLanguages)
                .supportsBatch(metadata.supportsBatch)
                .maxBatchSize(metadata.maxBatchSize)
                .supportsHandwriting(metadata.supportsHandwriting)
                .averageAccuracy(metadata.averageAccuracy)
                .ocrVocabSize(metadata.ocrVocabSize)
                .usesCtc(metadata.usesCtc)
                // VLM fields
                .visionFrames(metadata.visionFrames)
                .imageSize(metadata.imageSize)
                .tileSize(metadata.tileSize)
                .components(metadata.components)
                // Vision encoder IO
                .visionEncoderPixelValuesName(metadata.visionEncoderPixelValuesName)
                .visionEncoderPixelAttentionMaskName(metadata.visionEncoderPixelAttentionMaskName)
                .visionEncoderPrimaryOutputName(metadata.visionEncoderPrimaryOutputName)
                .visionEncoderOutputNames(metadata.visionEncoderOutputNames)
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
