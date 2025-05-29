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

package ai.kompile.embedding.anserini.config;

import ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Configuration for Anserini-based embedding models.
 */
@Configuration
@ConditionalOnProperty(name = "kompile.embedding.anserini.enabled", havingValue = "true")
public class AnseriniEmbeddingConfiguration {

    /**
     * Configuration properties for Anserini embedding.
     */
    @Data
    @ConfigurationProperties(prefix = "kompile.embedding.anserini")
    public static class AnseriniEmbeddingProperties {
        /**
         * Whether Anserini embedding is enabled.
         */
        private boolean enabled = false;

        /**
         * Model identifier for the embedding model.
         * Examples: "bge-base-en-v1.5-onnx", "arctic-embed-base-onnx"
         */
        private String modelIdentifier = "bge-base-en-v1.5-onnx";

        /**
         * Path to the ONNX model file (optional if using model management).
         */
        private String modelPath;

        /**
         * Path to the vocabulary file (optional if using model management).
         */
        private String vocabPath;

        /**
         * Input tensor names for the model.
         */
        private List<String> inputTensorNames = Arrays.asList("input_ids", "attention_mask", "token_type_ids");

        /**
         * Output tensor name from the model.
         */
        private String outputTensorName = "last_hidden_state";

        /**
         * Whether to lowercase text during tokenization.
         */
        private boolean doLowerCase = true;

        /**
         * Maximum sequence length for tokenization.
         */
        private int maxSequenceLength = 512;

        /**
         * Whether to add special tokens (CLS, SEP) during tokenization.
         */
        private boolean addSpecialTokens = true;

        /**
         * Whether to normalize the output embeddings.
         */
        private boolean normalizeOutput = true;
    }

    /**
     * Creates the Anserini embedding model bean.
     */
    @Bean
    @ConditionalOnProperty(name = "kompile.embedding.anserini.enabled", havingValue = "true")
    public AnseriniEmbeddingModelImpl anseriniEmbeddingModel(AnseriniEmbeddingProperties properties) throws IOException {
        
        // If explicit paths are provided, use the detailed constructor
        if (properties.getModelPath() != null && properties.getVocabPath() != null) {
            return new AnseriniEmbeddingModelImpl(
                properties.getModelIdentifier(),
                properties.getModelPath(),
                properties.getVocabPath(),
                properties.getInputTensorNames(),
                properties.getOutputTensorName(),
                properties.isDoLowerCase(),
                properties.getMaxSequenceLength(),
                properties.isAddSpecialTokens(),
                properties.isNormalizeOutput()
            );
        } else {
            // Use the simplified constructor that relies on model management
            return new AnseriniEmbeddingModelImpl(properties.getModelIdentifier());
        }
    }

    /**
     * Creates the configuration properties bean.
     */
    @Bean
    @ConfigurationProperties(prefix = "kompile.embedding.anserini")
    public AnseriniEmbeddingProperties anseriniEmbeddingProperties() {
        return new AnseriniEmbeddingProperties();
    }
}
