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

package ai.kompile.staging.archive;

import ai.kompile.staging.registry.ModelEntry;
import ai.kompile.staging.registry.ModelMetadata;
import ai.kompile.staging.registry.ModelStatus;
import ai.kompile.staging.registry.ModelType;
import ai.kompile.staging.registry.TokenizerConfig;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a model entry within a Kompile archive.
 * Extends the base ModelEntry with version information and archive-specific metadata.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ArchiveModelEntry {

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
     * Model version (e.g., "1.5.0").
     */
    @JsonProperty("version")
    private String version;

    /**
     * Relative path from archive root to model folder.
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
     * Size of the model file in bytes.
     */
    @JsonProperty("size_bytes")
    private long sizeBytes;

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
     * Brief description of the model.
     */
    @JsonProperty("description")
    private String description;

    /**
     * Source origin of the model (e.g., "huggingface:BAAI/bge-base-en-v1.5").
     */
    @JsonProperty("source")
    private String source;

    /**
     * License of the model (e.g., "MIT", "Apache-2.0").
     */
    @JsonProperty("license")
    private String license;

    /**
     * Creates an ArchiveModelEntry from a registry ModelEntry.
     */
    public static ArchiveModelEntry fromModelEntry(ModelEntry entry) {
        return ArchiveModelEntry.builder()
                .modelId(entry.getModelId())
                .type(entry.getType())
                .path(entry.getPath())
                .modelFile(entry.getModelFile())
                .vocabFile(entry.getVocabFile())
                .checksum(entry.getChecksum())
                .metadata(entry.getMetadata())
                .tokenizer(entry.getTokenizer())
                .build();
    }

    /**
     * Creates an ArchiveModelEntry from a registry ModelEntry with version.
     */
    public static ArchiveModelEntry fromModelEntry(ModelEntry entry, String version) {
        ArchiveModelEntry archiveEntry = fromModelEntry(entry);
        archiveEntry.setVersion(version);
        return archiveEntry;
    }

    /**
     * Converts this archive entry to a registry ModelEntry.
     */
    public ModelEntry toModelEntry() {
        return ModelEntry.builder()
                .modelId(modelId)
                .type(type)
                .path(path)
                .modelFile(modelFile)
                .vocabFile(vocabFile)
                .checksum(checksum)
                .status(ModelStatus.ACTIVE)
                .metadata(metadata)
                .tokenizer(tokenizer)
                .build();
    }

    /**
     * Get the full relative path to the model file.
     */
    public String getModelFilePath() {
        return path + "/" + modelFile;
    }

    /**
     * Get the full relative path to the vocab file.
     */
    public String getVocabFilePath() {
        return path + "/" + vocabFile;
    }

    /**
     * Creates an encoder archive entry with common defaults.
     */
    public static ArchiveModelEntry encoder(String modelId, String version, int embeddingDim) {
        return ArchiveModelEntry.builder()
                .modelId(modelId)
                .type(ModelType.ENCODER)
                .version(version)
                .path("models/encoders/" + modelId)
                .metadata(ModelMetadata.builder()
                        .embeddingDim(embeddingDim)
                        .framework("samediff")
                        .modelType("dense")
                        .build())
                .tokenizer(TokenizerConfig.defaultBertConfig())
                .build();
    }

    /**
     * Creates a cross-encoder archive entry with common defaults.
     */
    public static ArchiveModelEntry crossEncoder(String modelId, String version,
                                                   int hiddenSize, int numLayers) {
        return ArchiveModelEntry.builder()
                .modelId(modelId)
                .type(ModelType.CROSS_ENCODER)
                .version(version)
                .path("models/cross-encoders/" + modelId)
                .metadata(ModelMetadata.builder()
                        .hiddenSize(hiddenSize)
                        .numLayers(numLayers)
                        .framework("samediff")
                        .build())
                .tokenizer(TokenizerConfig.defaultBertConfig())
                .build();
    }

    /**
     * Returns the parsed version or null if unparseable.
     */
    public ArchiveVersion getParsedVersion() {
        return ArchiveVersion.tryParse(version);
    }
}
