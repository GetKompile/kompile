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

package ai.kompile.app.subprocess.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * Configuration arguments for the model initialization subprocess.
 *
 * <p>This record contains all the configuration needed to initialize an embedding model
 * in an isolated subprocess. The subprocess reads this from a JSON file passed as a
 * command-line argument.
 *
 * <p>Usage:
 * <pre>
 * // Create args
 * ModelInitSubprocessArgs args = ModelInitSubprocessArgs.builder()
 *     .taskId(UUID.randomUUID().toString())
 *     .modelIdentifier("bge-base-en-v1.5")
 *     .modelSourceType("staging")
 *     .stagingUrl("http://localhost:8081")
 *     .build();
 *
 * // Write to temp file
 * Path argsFile = args.writeToTempFile();
 *
 * // Pass to subprocess
 * ProcessBuilder pb = new ProcessBuilder("java", "-cp", classpath,
 *     "ai.kompile.app.subprocess.model.ModelInitSubprocessMain", argsFile.toString());
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelInitSubprocessArgs(
        // === Task Identification ===
        String taskId,                          // Unique task identifier

        // === Model Configuration ===
        String modelIdentifier,                 // Model ID (e.g., "bge-base-en-v1.5")
        String modelSourceType,                 // "staging", "archive", or "registry"
        String stagingUrl,                      // Staging service URL (if source=staging)
        String stagingApiKey,                   // Staging service API key (if needed)
        String archivePath,                     // Archive file path (if source=archive)

        // === Batch Size Configuration ===
        int optimalBatchSize,                   // Optimal batch size for embedding
        int maxBatchSize,                       // Maximum batch size for embedding

        // === ND4J Configuration ===
        String nd4jConfigJson,                  // ND4J environment config as JSON

        // === Callback Configuration ===
        String callbackBaseUrl,                 // HTTP callback URL for status updates

        // === Memory Configuration ===
        int memoryThresholdPercent,             // Warning threshold (default 80%)
        int memoryCriticalPercent,              // Critical threshold (default 90%)
        int memoryKillThresholdPercent,         // Kill threshold (default 95%)
        long memoryCheckIntervalMs,             // Check interval (default 2000ms)
        // GPU memory thresholds
        int gpuMemoryThresholdPercent,          // GPU warning threshold (default 75%)
        int gpuMemoryCriticalPercent,           // GPU critical threshold (default 85%)
        int gpuMemoryKillThresholdPercent,      // GPU kill threshold (default 92%)
        // Off-heap (JavaCPP native) memory thresholds
        int offHeapThresholdPercent,            // Off-heap warning threshold (default 80%)
        int offHeapCriticalPercent,             // Off-heap critical threshold (default 90%)
        int offHeapKillThresholdPercent,        // Off-heap kill threshold (default 95%)

        // === Validation Configuration ===
        boolean skipValidation,                 // Skip model output validation
        String validationTestText,              // Custom test text for validation

        // === Additional Options ===
        Map<String, Object> options             // Additional options map
) {

    // Uses SubprocessArgsIo shared mapper

    // Default values
    private static final int DEFAULT_OPTIMAL_BATCH = 32;
    private static final int DEFAULT_MAX_BATCH = 64;
    private static final int DEFAULT_MEMORY_THRESHOLD = 80;
    private static final int DEFAULT_MEMORY_CRITICAL = 90;
    private static final int DEFAULT_MEMORY_KILL = 95;
    private static final long DEFAULT_MEMORY_CHECK_INTERVAL = 2000L;
    // GPU threshold defaults
    private static final int DEFAULT_GPU_MEMORY_THRESHOLD = 75;
    private static final int DEFAULT_GPU_MEMORY_CRITICAL = 85;
    private static final int DEFAULT_GPU_MEMORY_KILL = 92;
    // Off-heap threshold defaults
    private static final int DEFAULT_OFF_HEAP_THRESHOLD = 80;
    private static final int DEFAULT_OFF_HEAP_CRITICAL = 90;
    private static final int DEFAULT_OFF_HEAP_KILL = 95;
    private static final String DEFAULT_VALIDATION_TEXT = "This is a validation test for the embedding model.";

    /**
     * Read args from a JSON file.
     */
    public static ModelInitSubprocessArgs readFromFile(Path path) throws IOException {
        return ai.kompile.app.subprocess.SubprocessArgsIo.fromFile(path, ModelInitSubprocessArgs.class);
    }

    /**
     * Write args to a temporary file and return the path.
     */
    public Path writeToTempFile() throws IOException {
        return ai.kompile.app.subprocess.SubprocessArgsIo.writeToTempFile(this, "model-init-args-");
    }

    /**
     * Get an option value with default.
     */
    @SuppressWarnings("unchecked")
    public <T> T getOption(String key, T defaultValue) {
        if (options == null || !options.containsKey(key)) {
            return defaultValue;
        }
        Object value = options.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return (T) value;
        } catch (ClassCastException e) {
            return defaultValue;
        }
    }

    /**
     * Create a builder with default values.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for ModelInitSubprocessArgs.
     */
    public static class Builder {
        private String taskId = UUID.randomUUID().toString();
        private String modelIdentifier;
        private String modelSourceType = "registry";
        private String stagingUrl;
        private String stagingApiKey;
        private String archivePath;
        private int optimalBatchSize = DEFAULT_OPTIMAL_BATCH;
        private int maxBatchSize = DEFAULT_MAX_BATCH;
        private String nd4jConfigJson;
        private String callbackBaseUrl;
        private int memoryThresholdPercent = DEFAULT_MEMORY_THRESHOLD;
        private int memoryCriticalPercent = DEFAULT_MEMORY_CRITICAL;
        private int memoryKillThresholdPercent = DEFAULT_MEMORY_KILL;
        private long memoryCheckIntervalMs = DEFAULT_MEMORY_CHECK_INTERVAL;
        // GPU memory thresholds
        private int gpuMemoryThresholdPercent = DEFAULT_GPU_MEMORY_THRESHOLD;
        private int gpuMemoryCriticalPercent = DEFAULT_GPU_MEMORY_CRITICAL;
        private int gpuMemoryKillThresholdPercent = DEFAULT_GPU_MEMORY_KILL;
        // Off-heap memory thresholds
        private int offHeapThresholdPercent = DEFAULT_OFF_HEAP_THRESHOLD;
        private int offHeapCriticalPercent = DEFAULT_OFF_HEAP_CRITICAL;
        private int offHeapKillThresholdPercent = DEFAULT_OFF_HEAP_KILL;
        private boolean skipValidation = false;
        private String validationTestText = DEFAULT_VALIDATION_TEXT;
        private Map<String, Object> options;

        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder modelIdentifier(String modelIdentifier) {
            this.modelIdentifier = modelIdentifier;
            return this;
        }

        public Builder modelSourceType(String modelSourceType) {
            this.modelSourceType = modelSourceType;
            return this;
        }

        public Builder stagingUrl(String stagingUrl) {
            this.stagingUrl = stagingUrl;
            return this;
        }

        public Builder stagingApiKey(String stagingApiKey) {
            this.stagingApiKey = stagingApiKey;
            return this;
        }

        public Builder archivePath(String archivePath) {
            this.archivePath = archivePath;
            return this;
        }

        public Builder optimalBatchSize(int optimalBatchSize) {
            this.optimalBatchSize = optimalBatchSize;
            return this;
        }

        public Builder maxBatchSize(int maxBatchSize) {
            this.maxBatchSize = maxBatchSize;
            return this;
        }

        public Builder nd4jConfigJson(String nd4jConfigJson) {
            this.nd4jConfigJson = nd4jConfigJson;
            return this;
        }

        public Builder callbackBaseUrl(String callbackBaseUrl) {
            this.callbackBaseUrl = callbackBaseUrl;
            return this;
        }

        public Builder memoryThresholdPercent(int memoryThresholdPercent) {
            this.memoryThresholdPercent = memoryThresholdPercent;
            return this;
        }

        public Builder memoryCriticalPercent(int memoryCriticalPercent) {
            this.memoryCriticalPercent = memoryCriticalPercent;
            return this;
        }

        public Builder memoryKillThresholdPercent(int memoryKillThresholdPercent) {
            this.memoryKillThresholdPercent = memoryKillThresholdPercent;
            return this;
        }

        public Builder memoryCheckIntervalMs(long memoryCheckIntervalMs) {
            this.memoryCheckIntervalMs = memoryCheckIntervalMs;
            return this;
        }

        public Builder gpuMemoryThresholdPercent(int gpuMemoryThresholdPercent) {
            this.gpuMemoryThresholdPercent = gpuMemoryThresholdPercent;
            return this;
        }

        public Builder gpuMemoryCriticalPercent(int gpuMemoryCriticalPercent) {
            this.gpuMemoryCriticalPercent = gpuMemoryCriticalPercent;
            return this;
        }

        public Builder gpuMemoryKillThresholdPercent(int gpuMemoryKillThresholdPercent) {
            this.gpuMemoryKillThresholdPercent = gpuMemoryKillThresholdPercent;
            return this;
        }

        public Builder offHeapThresholdPercent(int offHeapThresholdPercent) {
            this.offHeapThresholdPercent = offHeapThresholdPercent;
            return this;
        }

        public Builder offHeapCriticalPercent(int offHeapCriticalPercent) {
            this.offHeapCriticalPercent = offHeapCriticalPercent;
            return this;
        }

        public Builder offHeapKillThresholdPercent(int offHeapKillThresholdPercent) {
            this.offHeapKillThresholdPercent = offHeapKillThresholdPercent;
            return this;
        }

        public Builder skipValidation(boolean skipValidation) {
            this.skipValidation = skipValidation;
            return this;
        }

        public Builder validationTestText(String validationTestText) {
            this.validationTestText = validationTestText;
            return this;
        }

        public Builder options(Map<String, Object> options) {
            this.options = options;
            return this;
        }

        public ModelInitSubprocessArgs build() {
            if (modelIdentifier == null || modelIdentifier.isBlank()) {
                modelIdentifier = "bge-base-en-v1.5"; // Default model
            }
            return new ModelInitSubprocessArgs(
                    taskId,
                    modelIdentifier,
                    modelSourceType,
                    stagingUrl,
                    stagingApiKey,
                    archivePath,
                    optimalBatchSize,
                    maxBatchSize,
                    nd4jConfigJson,
                    callbackBaseUrl,
                    memoryThresholdPercent,
                    memoryCriticalPercent,
                    memoryKillThresholdPercent,
                    memoryCheckIntervalMs,
                    gpuMemoryThresholdPercent,
                    gpuMemoryCriticalPercent,
                    gpuMemoryKillThresholdPercent,
                    offHeapThresholdPercent,
                    offHeapCriticalPercent,
                    offHeapKillThresholdPercent,
                    skipValidation,
                    validationTestText,
                    options
            );
        }
    }
}
