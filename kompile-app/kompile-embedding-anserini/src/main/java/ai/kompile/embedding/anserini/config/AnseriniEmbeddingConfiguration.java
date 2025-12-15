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

import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

        // ========== DYNAMIC BATCH SIZE CONFIGURATION ==========
        // These settings control how batch sizes are calculated based on sequence length.
        // The encoder uses: batch_size = base_batch × (512/seqLen)² × memoryScale

        /**
         * Base optimal batch size for 512-token sequences.
         * Actual batch size scales inversely with sequence length squared.
         *
         * <p>Recommended values by hardware:
         * <ul>
         *   <li>CPU (low memory): 4-8</li>
         *   <li>CPU (high memory): 8-16</li>
         *   <li>GPU: 32-64</li>
         * </ul>
         *
         * Default: 8 (conservative for CPU inference)
         */
        private int baseOptimalBatchSize = 8;

        /**
         * Base maximum batch size for 512-token sequences.
         * Actual maximum scales inversely with sequence length squared.
         *
         * Default: 16 (conservative for CPU inference)
         */
        private int baseMaxBatchSize = 16;

        /**
         * Memory scale factor for batch size calculation.
         * Applied as multiplier to the calculated batch size.
         *
         * <ul>
         *   <li>0.5 = halve batch sizes (for low-memory systems)</li>
         *   <li>1.0 = use calculated sizes</li>
         *   <li>2.0 = double batch sizes (for high-memory systems)</li>
         * </ul>
         *
         * Default: -1 (auto-detect based on heap size)
         */
        private double memoryScaleFactor = -1.0;

        /**
         * Absolute maximum batch size, regardless of sequence length.
         * Prevents OOM on systems with very short sequences.
         *
         * Default: 128
         */
        private int absoluteMaxBatchSize = 128;

        /**
         * Enable verbose logging for dynamic batch sizing decisions.
         * Useful for tuning batch size parameters.
         *
         * Default: false
         */
        private boolean verboseBatchSizing = false;

        // ========== RUNTIME OVERRIDE SUPPORT ==========

        /**
         * Per-model batch size overrides (modelId -> override config).
         * These take precedence over global settings.
         */
        private final Map<String, BatchSizeOverride> modelOverrides = new ConcurrentHashMap<>();

        /**
         * Gets the effective optimal batch size for a model.
         * Checks for per-model override first, then falls back to global setting.
         *
         * @param modelId the model identifier
         * @return effective optimal batch size
         */
        public int getEffectiveOptimalBatchSize(String modelId) {
            BatchSizeOverride override = modelOverrides.get(modelId);
            if (override != null && override.optimalBatchSize() != null) {
                return override.optimalBatchSize();
            }
            return baseOptimalBatchSize;
        }

        /**
         * Gets the effective max batch size for a model.
         * Checks for per-model override first, then falls back to global setting.
         *
         * @param modelId the model identifier
         * @return effective max batch size
         */
        public int getEffectiveMaxBatchSize(String modelId) {
            BatchSizeOverride override = modelOverrides.get(modelId);
            if (override != null && override.maxBatchSize() != null) {
                return override.maxBatchSize();
            }
            return baseMaxBatchSize;
        }

        /**
         * Gets the effective memory scale factor for a model.
         * Checks for per-model override first, then falls back to global setting.
         *
         * @param modelId the model identifier
         * @return effective memory scale factor
         */
        public double getEffectiveMemoryScaleFactor(String modelId) {
            BatchSizeOverride override = modelOverrides.get(modelId);
            if (override != null && override.memoryScaleFactor() != null) {
                return override.memoryScaleFactor();
            }
            return memoryScaleFactor;
        }

        /**
         * Sets a per-model batch size override.
         *
         * @param modelId the model identifier
         * @param override the override configuration
         */
        public void setModelOverride(String modelId, BatchSizeOverride override) {
            if (override == null) {
                modelOverrides.remove(modelId);
            } else {
                modelOverrides.put(modelId, override);
            }
        }

        /**
         * Gets a per-model batch size override.
         *
         * @param modelId the model identifier
         * @return the override, or null if none set
         */
        public BatchSizeOverride getModelOverride(String modelId) {
            return modelOverrides.get(modelId);
        }

        /**
         * Checks if a model has a runtime override.
         *
         * @param modelId the model identifier
         * @return true if override exists
         */
        public boolean hasModelOverride(String modelId) {
            return modelOverrides.containsKey(modelId);
        }

        /**
         * Clears the override for a model.
         *
         * @param modelId the model identifier
         */
        public void clearModelOverride(String modelId) {
            modelOverrides.remove(modelId);
        }

        /**
         * Gets all model overrides.
         *
         * @return unmodifiable map of overrides
         */
        public Map<String, BatchSizeOverride> getAllModelOverrides() {
            return Map.copyOf(modelOverrides);
        }
    }

    /**
     * Record for per-model batch size override.
     * All fields are optional - null means use global default.
     */
    public record BatchSizeOverride(
            Integer optimalBatchSize,
            Integer maxBatchSize,
            Double memoryScaleFactor
    ) {
        /**
         * Creates an override with only optimal batch size.
         */
        public static BatchSizeOverride ofOptimal(int optimalBatchSize) {
            return new BatchSizeOverride(optimalBatchSize, null, null);
        }

        /**
         * Creates an override with only max batch size.
         */
        public static BatchSizeOverride ofMax(int maxBatchSize) {
            return new BatchSizeOverride(null, maxBatchSize, null);
        }

        /**
         * Creates an override with both batch sizes.
         */
        public static BatchSizeOverride of(int optimalBatchSize, int maxBatchSize) {
            return new BatchSizeOverride(optimalBatchSize, maxBatchSize, null);
        }

        /**
         * Creates a full override.
         */
        public static BatchSizeOverride of(int optimalBatchSize, int maxBatchSize, double memoryScaleFactor) {
            return new BatchSizeOverride(optimalBatchSize, maxBatchSize, memoryScaleFactor);
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
