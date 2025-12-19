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

package ai.kompile.staging.registry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Metadata about a model including dimensions, architecture, and provenance.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModelMetadata {

    /**
     * Embedding dimension for encoder models.
     */
    @JsonProperty("embedding_dim")
    private Integer embeddingDim;

    /**
     * Hidden size for transformer models.
     */
    @JsonProperty("hidden_size")
    private Integer hiddenSize;

    /**
     * Number of transformer layers.
     */
    @JsonProperty("num_layers")
    private Integer numLayers;

    /**
     * Maximum sequence length the model supports.
     */
    @JsonProperty("max_sequence_length")
    private Integer maxSequenceLength;

    /**
     * Model type: 'dense' or 'sparse'.
     */
    @JsonProperty("model_type")
    @Builder.Default
    private String modelType = "dense";

    /**
     * Encoder type for factory instantiation (e.g., 'bge', 'arctic_embed', 'cosdpr_distil').
     * Used by AnseriniEncoderFactory to create the correct encoder implementation.
     */
    @JsonProperty("encoder_type")
    private String encoderType;

    /**
     * RAG pipeline role: 'retrieval' or 'reranking'.
     * Used to assign models to specific phases of the RAG pipeline.
     */
    @JsonProperty("rag_role")
    private String ragRole;

    /**
     * Framework used: always 'samediff' for production models.
     */
    @JsonProperty("framework")
    @Builder.Default
    private String framework = "samediff";

    /**
     * Training data description (e.g., "MS MARCO", "Natural Questions").
     */
    @JsonProperty("training_data")
    private String trainingData;

    /**
     * Source origin: 'huggingface', 'github', 'custom'.
     */
    @JsonProperty("source_origin")
    private String sourceOrigin;

    /**
     * Source repository (e.g., "BAAI/bge-base-en-v1.5").
     */
    @JsonProperty("source_repository")
    private String sourceRepository;

    /**
     * Original format before conversion: 'onnx', 'tensorflow', 'keras'.
     */
    @JsonProperty("original_format")
    private String originalFormat;

    /**
     * Date when the model was converted.
     */
    @JsonProperty("conversion_date")
    private String conversionDate;

    /**
     * Human-readable description of the model.
     */
    @JsonProperty("description")
    private String description;

    /**
     * Vocabulary size for the model's tokenizer.
     */
    @JsonProperty("vocab_size")
    private Integer vocabSize;

    /**
     * Model version string.
     */
    @JsonProperty("version")
    private String version;
}
