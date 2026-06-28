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

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Configuration for Anserini-based embedding models.
 */
public class AnseriniEmbeddingConfiguration {

    /**
     * Managed-JSON configuration properties for Anserini embedding.
     * <p>
     * Configuration is loaded from {@code <dataDir>/config/embedding-anserini-config.json} at
     * startup and can be persisted back via {@link #persist()}. When the file is
     * absent the class field defaults are used unchanged.
     * </p>
     */
    @Data
    @Component
    @ConditionalOnClass(name = "ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AnseriniEmbeddingProperties {

        private static final Logger log = LoggerFactory.getLogger(AnseriniEmbeddingProperties.class);
        private static final String CONFIG_FILENAME = "embedding-anserini-config.json";

        @JsonIgnore
        @EqualsAndHashCode.Exclude
        @ToString.Exclude
        private final Path configFilePath;

        @JsonIgnore
        @EqualsAndHashCode.Exclude
        @ToString.Exclude
        private final ObjectMapper objectMapper;

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
         * <p><b>OOM safety:</b> This value is forwarded to the embedding subprocess as the
         * {@code absoluteMaxBatchSize} parameter of {@code GenericDenseSameDiffEncoder.configureBatchSize()}.
         * The old hardcoded value of 8192 caused 36 GB native forward-pass activations and
         * crashed the host. The default here is conservative (same as {@code baseMaxBatchSize})
         * and should be tuned per hardware.
         *
         * Default: 0 (falls back to baseMaxBatchSize — NOT memory-heap-scaled 8192)
         */
        private int absoluteMaxBatchSize = 0;

        /**
         * Heap size in MB for the embedding subprocess JVM.
         * Passed as {@code -Xmx}/{@code -Xms} to the subprocess.
         *
         * Default: 4096 (4 GB)
         */
        private int subprocessHeapMb = 4096;

        /**
         * JavaCPP maxphysicalbytes cap for the embedding subprocess, in MB.
         * Passed as {@code -Dorg.bytedeco.javacpp.maxphysicalbytes=<N>m} to the subprocess
         * so native activation memory is bounded even if the JVM heap limit is not triggered.
         * A value of 0 means "4 × subprocessHeapMb" (the default multiplier).
         *
         * Default: 0 (auto = 4 × subprocessHeapMb)
         */
        private long subprocessMaxPhysicalMb = 0;

        /**
         * Returns the effective JavaCPP maxphysicalbytes value in MB.
         * Uses {@code subprocessMaxPhysicalMb} if > 0, otherwise 4 × {@code subprocessHeapMb}.
         */
        public long getEffectiveSubprocessMaxPhysicalMb() {
            if (subprocessMaxPhysicalMb > 0) {
                return subprocessMaxPhysicalMb;
            }
            // Ceiling for the embedding subprocess's native memory. With per-batch session-cache clearing
            // (InferenceSession.clearAllCaches between batches) the encode is bounded to ~model-resident +
            // one batch's activations (the ~47MB/batch is reclaimed each batch, not accumulated), so a 32GB
            // ceiling has ample headroom; the reactive OOM-split guard + RSS watchdog are backstops.
            return Math.max(32768L, (long) subprocessHeapMb * 8L);
        }

        /**
         * Gets the absolute maximum batch size sent to the encoder subprocess.
         *
         * <p><b>OOM safety note:</b> The old auto-scaling implementation grew this to 8192
         * based on JVM heap (NOT native memory), which caused ~36 GB transformer forward-pass
         * activations and crashed the host. The new default is conservative: if
         * {@code absoluteMaxBatchSize} is not set (0), this returns {@code baseMaxBatchSize}
         * (same value passed as the ordinary max). Operators who want larger values should
         * set {@code absoluteMaxBatchSize} explicitly in embedding-anserini-config.json.
         *
         * @return the configured or defaulted absolute max batch size
         */
        public int getAbsoluteMaxBatchSize() {
            // If explicitly configured and positive, use it.
            if (absoluteMaxBatchSize > 0) {
                return absoluteMaxBatchSize;
            }
            // Default: same as baseMaxBatchSize — the native-memory pressure guard in the
            // encoder will halve further if needed, but we do not auto-inflate to 8192.
            return Math.max(1, baseMaxBatchSize);
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
         * Timeout in milliseconds for the LoadModel request specifically.
         * CPU SameDiff/DSP model warm-up can take ~111s or more; the general
         * {@code requestTimeoutMs} (60s) fires before load completes and triggers
         * a crash-loop.  This separate knob lets model load succeed without
         * raising the timeout for all other short-lived requests.
         *
         * <p>Configurable via the JSON config file.
         *
         * <p>Set to 0 or negative to fall back to {@code requestTimeoutMs}
         * (or no timeout if that is also 0).
         *
         * Default: 240000 (4 minutes) — covers observed ~111s CPU warm-up with ample headroom.
         */
        private long loadModelTimeoutMs = 240000;

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

        public AnseriniEmbeddingProperties(@Value("${kompile.data.dir:#{null}}") String dataDir) {
            String effectiveDataDir = dataDir;
            if (effectiveDataDir == null || effectiveDataDir.isBlank()) {
                effectiveDataDir = System.getProperty("user.home") + "/.kompile";
            }
            this.objectMapper = JsonUtils.newStandardMapper();
            this.configFilePath = Paths.get(effectiveDataDir, "config", CONFIG_FILENAME);
            log.info("AnseriniEmbeddingProperties initialized, config path: {}", configFilePath);
        }

        /**
         * Loads the JSON config file on startup, overlaying values onto this instance.
         * When the file is absent all field defaults are kept as-is. Never throws.
         */
        @PostConstruct
        public void init() {
            if (!Files.exists(configFilePath)) {
                log.info("No Anserini embedding config found at {} — using defaults", configFilePath);
                return;
            }
            try {
                String json = Files.readString(configFilePath);
                // readerForUpdating calls setters on *this* for each present key;
                // absent keys keep their existing (default) values.
                objectMapper.readerForUpdating(this).readValue(json);
                log.info("Loaded Anserini embedding config from {}: enabled={}, modelIdentifier={}",
                        configFilePath, enabled, modelIdentifier);
            } catch (IOException e) {
                log.warn("Could not read Anserini embedding config from {} — using defaults: {}",
                        configFilePath, e.getMessage());
            }
        }

        /**
         * Persists the current field values to {@code <dataDir>/config/embedding-anserini-config.json}
         * as pretty-printed JSON. Parent directories are created if absent.
         */
        public void persist() {
            try {
                Path parent = configFilePath.getParent();
                if (!Files.exists(parent)) {
                    Files.createDirectories(parent);
                    log.info("Created config directory: {}", parent);
                }
                String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(this);
                Files.writeString(configFilePath, json);
                log.info("Persisted Anserini embedding config to {}", configFilePath);
            } catch (IOException e) {
                log.error("Failed to persist Anserini embedding config to {}: {}", configFilePath, e.getMessage(), e);
            }
        }

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
}
