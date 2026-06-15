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
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
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
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl")
@ConditionalOnProperty(name = "kompile.embedding.anserini.enabled", havingValue = "true", matchIfMissing = true)
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
         * Whether to eagerly initialize the embedding model on application startup.
         *
         * <p>When enabled (default), the embedding model is initialized asynchronously
         * when the application starts, so it's ready when users open the UI.
         *
         * <p>When disabled, the model uses lazy initialization and is only loaded
         * when the first embedding operation is requested (e.g., when a user triggers
         * a search or indexing operation).
         *
         * <p>Set to false if you prefer faster startup at the cost of delayed first use.
         *
         * Default: true
         */
        private boolean eagerInit = true;

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
        // These settings control how many chunks are sent to the embedding model at once.
        // The encoder internally uses dynamic sub-batching based on actual sequence lengths:
        //   internal_batch = base_batch × (512/seqLen)² × memoryScale
        //
        // For typical document chunks (200-400 tokens), the encoder can handle larger batches
        // than for full 512-token sequences. Setting higher pipeline batch sizes allows
        // the encoder to optimize based on actual sequence lengths.

        /**
         * Base optimal batch size - number of chunks to send to embedBatch().
         *
         * <p>The encoder will internally sub-batch if sequences are long (512 tokens).
         * For typical document chunks (200-400 tokens), larger batches are efficient.</p>
         *
         * <p>Recommended values:
         * <ul>
         *   <li>CPU (low memory, 4GB): 16-32</li>
         *   <li>CPU (medium memory, 8GB): 32-64</li>
         *   <li>CPU (high memory, 16GB+): 64-128</li>
         *   <li>GPU: 128-256</li>
         * </ul>
         *
         * Default: 32 (good balance for typical CPU inference)
         */
        private int baseOptimalBatchSize = 32;

        /**
         * Base maximum batch size - upper limit for chunk batching.
         * The encoder handles internal sub-batching for long sequences.
         *
         * Default: 64 (allows efficient batching for shorter chunks)
         */
        private int baseMaxBatchSize = 64;

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
         * Configured absolute maximum batch size (baseline).
         * The actual max is computed dynamically based on available memory.
         * Set to 0 or negative to use pure memory-based calculation.
         *
         * Default: 0 (memory-based)
         */
        private int absoluteMaxBatchSize = 0;

        /**
         * Gets the absolute maximum batch size, calculated based on available heap memory.
         * This allows higher batch sizes on systems with more RAM.
         *
         * <p>Memory-based scaling:
         * <ul>
         *   <li>4GB heap → 512 max</li>
         *   <li>8GB heap → 1024 max</li>
         *   <li>16GB heap → 2048 max</li>
         *   <li>32GB heap → 4096 max</li>
         *   <li>64GB+ heap → 8192 max</li>
         * </ul>
         *
         * @return computed absolute max batch size
         */
        public int getAbsoluteMaxBatchSize() {
            // If explicitly configured, use that as a ceiling
            if (absoluteMaxBatchSize > 0) {
                return absoluteMaxBatchSize;
            }

            // Calculate based on available heap memory
            Runtime runtime = Runtime.getRuntime();
            long maxHeapMb = runtime.maxMemory() / (1024 * 1024);

            if (maxHeapMb >= 64 * 1024) {      // 64GB+
                return 8192;
            } else if (maxHeapMb >= 32 * 1024) { // 32GB
                return 4096;
            } else if (maxHeapMb >= 16 * 1024) { // 16GB
                return 2048;
            } else if (maxHeapMb >= 8 * 1024) {  // 8GB
                return 1024;
            } else if (maxHeapMb >= 4 * 1024) {  // 4GB
                return 512;
            } else if (maxHeapMb >= 2 * 1024) {  // 2GB
                return 256;
            } else {
                return 128; // Minimum safe default
            }
        }

        /**
         * Enable verbose logging for dynamic batch sizing decisions.
         * Useful for tuning batch size parameters.
         *
         * Default: false
         */
        private boolean verboseBatchSizing = false;

        // ========== TIMEOUT CONFIGURATION ==========
        // These settings control timeouts for subprocess operations.
        // Set to 0 or negative value to disable timeout (wait indefinitely).

        /**
         * Timeout in seconds for loading a model in the subprocess.
         * This timeout applies when the subprocess is started and the model is being loaded.
         *
         * <p>Set to 0 or negative to disable timeout (wait indefinitely).
         *
         * Default: 300 (5 minutes) - model loading can take a while but shouldn't hang forever
         */
        private long modelLoadTimeoutSeconds = 300;

        /**
         * Timeout in milliseconds for subprocess request operations.
         * This is used by the subprocess launcher for general request/response handling.
         *
         * <p>Set to 0 or negative to disable timeout (wait indefinitely).
         *
         * Default: 60000 (60 seconds) - general requests should complete quickly
         */
        private long requestTimeoutMs = 60000;

        /**
         * Timeout in milliseconds for subprocess heartbeat detection.
         * If no heartbeat is received within this time, the subprocess is considered unresponsive.
         *
         * <p>Set to 0 or negative to disable heartbeat timeout.
         *
         * Default: 60000 (60 seconds) - detect unresponsive subprocess
         */
        private long heartbeatTimeoutMs = 60000;

        /**
         * Timeout in seconds for single text embedding operations.
         *
         * <p>Set to 0 or negative to disable timeout (wait indefinitely).
         *
         * Default: 120 (2 minutes) - single embedding operations
         */
        private long embedTimeoutSeconds = 120;

        /**
         * Timeout in seconds for batch embedding operations.
         *
         * <p>Set to 0 or negative to disable timeout (wait indefinitely).
         *
         * Default: 300 (5 minutes) - batch operations can process many documents
         */
        private long embedBatchTimeoutSeconds = 300;

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
         * @param modelId the model identifier (can be null for global)
         * @return effective optimal batch size
         */
        public int getEffectiveOptimalBatchSize(String modelId) {
            if (modelId != null) {
                BatchSizeOverride override = modelOverrides.get(modelId);
                if (override != null && override.optimalBatchSize() != null) {
                    return override.optimalBatchSize();
                }
            }
            return baseOptimalBatchSize;
        }

        /**
         * Gets the effective max batch size for a model.
         * Checks for per-model override first, then falls back to global setting.
         *
         * @param modelId the model identifier (can be null for global)
         * @return effective max batch size
         */
        public int getEffectiveMaxBatchSize(String modelId) {
            if (modelId != null) {
                BatchSizeOverride override = modelOverrides.get(modelId);
                if (override != null && override.maxBatchSize() != null) {
                    return override.maxBatchSize();
                }
            }
            return baseMaxBatchSize;
        }

        /**
         * Gets the effective memory scale factor for a model.
         * Checks for per-model override first, then falls back to global setting.
         *
         * @param modelId the model identifier (can be null for global)
         * @return effective memory scale factor
         */
        public double getEffectiveMemoryScaleFactor(String modelId) {
            if (modelId != null) {
                BatchSizeOverride override = modelOverrides.get(modelId);
                if (override != null && override.memoryScaleFactor() != null) {
                    return override.memoryScaleFactor();
                }
            }
            return memoryScaleFactor;
        }

        /**
         * Sets a per-model batch size override.
         *
         * @param modelId the model identifier (cannot be null)
         * @param override the override configuration (null to remove)
         */
        public void setModelOverride(String modelId, BatchSizeOverride override) {
            if (modelId == null) {
                return; // Cannot set override for null modelId
            }
            if (override == null) {
                modelOverrides.remove(modelId);
            } else {
                modelOverrides.put(modelId, override);
            }
        }

        /**
         * Gets a per-model batch size override.
         *
         * @param modelId the model identifier (can be null)
         * @return the override, or null if none set or modelId is null
         */
        public BatchSizeOverride getModelOverride(String modelId) {
            if (modelId == null) return null;
            return modelOverrides.get(modelId);
        }

        /**
         * Checks if a model has a runtime override.
         *
         * @param modelId the model identifier (can be null)
         * @return true if override exists, false if modelId is null
         */
        public boolean hasModelOverride(String modelId) {
            if (modelId == null) return false;
            return modelOverrides.containsKey(modelId);
        }

        /**
         * Clears the override for a model.
         *
         * @param modelId the model identifier (can be null, which is a no-op)
         */
        public void clearModelOverride(String modelId) {
            if (modelId != null) {
                modelOverrides.remove(modelId);
            }
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
