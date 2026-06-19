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

package ai.kompile.staging.subprocess;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Arguments passed to the training subprocess.
 * Serialized to a temporary JSON file to avoid command-line length limits.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TrainingSubprocessArgs(
        /** Unique task identifier for tracking */
        String taskId,

        /** Training type: FINETUNE, LORA, DISTILLATION, ALIGNMENT */
        String trainingType,

        /** Model identifier to train */
        String modelId,

        /** Dataset identifier */
        String datasetId,

        /** Number of training epochs */
        int epochs,

        /** Training batch size */
        int batchSize,

        /** Learning rate */
        double learningRate,

        /** Learning rate schedule: COSINE, LINEAR, CONSTANT, etc. */
        String lrSchedule,

        /** Warmup ratio (0.0-1.0) */
        double warmupRatio,

        /** Maximum training steps (-1 for unlimited) */
        int maxSteps,

        /** Maximum gradient norm for clipping */
        double maxGradNorm,

        /** Enable FP16 mixed precision */
        boolean fp16,

        /** Enable BF16 mixed precision */
        boolean bf16,

        /** Log every N steps */
        int loggingSteps,

        /** Save checkpoint every N steps */
        int saveSteps,

        /** Evaluate every N steps */
        int evalSteps,

        /** Output directory for trained model */
        String outputDir,

        /** Random seed */
        int seed,

        /** Gradient accumulation steps */
        int gradientAccumulationSteps,

        /** PEFT configuration as JSON string (for LORA type) */
        String peftConfigJson,

        /** Updater configuration as JSON string */
        String updaterConfigJson,

        /** Distillation configuration as JSON string (for DISTILLATION type) */
        String distillationConfigJson,

        /** Alignment configuration as JSON string (for ALIGNMENT type) */
        String alignmentConfigJson,

        /** Base URL for HTTP callbacks to main app */
        String callbackBaseUrl,

        /** ND4J environment configuration as JSON string */
        String nd4jConfigJson,

        /** Path for checkpoint storage */
        String checkpointPath,

        /** Whether to resume from existing checkpoint */
        boolean resume,

        // === Memory Monitoring ===

        /** Memory threshold percentage for graceful stop. Default: 80 */
        int memoryThresholdPercent,

        /** Memory critical percentage for pause and GC. Default: 90 */
        int memoryCriticalPercent,

        /** Memory kill threshold percentage. Default: 95 */
        int memoryKillThresholdPercent,

        /** Memory check interval in milliseconds. Default: 2000 */
        long memoryCheckIntervalMs,

        /** Additional options as key-value pairs */
        Map<String, Object> options) {

    private static final ObjectMapper OBJECT_MAPPER = JsonUtils.standardMapper();

    public static final int DEFAULT_EPOCHS = 3;
    public static final int DEFAULT_BATCH_SIZE = 8;
    public static final double DEFAULT_LEARNING_RATE = 1e-4;
    public static final double DEFAULT_WARMUP_RATIO = 0.1;
    public static final double DEFAULT_MAX_GRAD_NORM = 1.0;
    public static final int DEFAULT_LOGGING_STEPS = 10;
    public static final int DEFAULT_SAVE_STEPS = 500;
    public static final int DEFAULT_EVAL_STEPS = 500;
    public static final int DEFAULT_SEED = 42;
    public static final int DEFAULT_MEMORY_THRESHOLD_PERCENT = 80;
    public static final int DEFAULT_MEMORY_CRITICAL_PERCENT = 90;
    public static final int DEFAULT_MEMORY_KILL_THRESHOLD_PERCENT = 95;
    public static final long DEFAULT_MEMORY_CHECK_INTERVAL_MS = 2000;

    /**
     * Write arguments to a temporary JSON file.
     */
    public Path writeToTempFile() throws IOException {
        Path tempFile = Files.createTempFile("training-args-" + taskId + "-", ".json");
        OBJECT_MAPPER.writeValue(tempFile.toFile(), this);
        return tempFile;
    }

    /**
     * Read arguments from a JSON file.
     */
    public static TrainingSubprocessArgs readFromFile(Path filePath) throws IOException {
        return OBJECT_MAPPER.readValue(filePath.toFile(), TrainingSubprocessArgs.class);
    }

    /**
     * Read arguments from a JSON string.
     */
    public static TrainingSubprocessArgs fromJson(String json) throws IOException {
        return OBJECT_MAPPER.readValue(json, TrainingSubprocessArgs.class);
    }

    /**
     * Convert to JSON string.
     */
    public String toJson() throws IOException {
        return OBJECT_MAPPER.writeValueAsString(this);
    }

    /**
     * Get an option value with a default.
     */
    @SuppressWarnings("unchecked")
    public <T> T getOption(String key, T defaultValue) {
        if (options == null) return defaultValue;
        Object value = options.get(key);
        if (value == null) return defaultValue;
        try {
            return (T) value;
        } catch (ClassCastException e) {
            return defaultValue;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String taskId;
        private String trainingType = "FINETUNE";
        private String modelId;
        private String datasetId;
        private int epochs = DEFAULT_EPOCHS;
        private int batchSize = DEFAULT_BATCH_SIZE;
        private double learningRate = DEFAULT_LEARNING_RATE;
        private String lrSchedule = "COSINE";
        private double warmupRatio = DEFAULT_WARMUP_RATIO;
        private int maxSteps = -1;
        private double maxGradNorm = DEFAULT_MAX_GRAD_NORM;
        private boolean fp16 = false;
        private boolean bf16 = false;
        private int loggingSteps = DEFAULT_LOGGING_STEPS;
        private int saveSteps = DEFAULT_SAVE_STEPS;
        private int evalSteps = DEFAULT_EVAL_STEPS;
        private String outputDir;
        private int seed = DEFAULT_SEED;
        private int gradientAccumulationSteps = 1;
        private String peftConfigJson;
        private String updaterConfigJson;
        private String distillationConfigJson;
        private String alignmentConfigJson;
        private String callbackBaseUrl;
        private String nd4jConfigJson;
        private String checkpointPath;
        private boolean resume = false;
        private int memoryThresholdPercent = DEFAULT_MEMORY_THRESHOLD_PERCENT;
        private int memoryCriticalPercent = DEFAULT_MEMORY_CRITICAL_PERCENT;
        private int memoryKillThresholdPercent = DEFAULT_MEMORY_KILL_THRESHOLD_PERCENT;
        private long memoryCheckIntervalMs = DEFAULT_MEMORY_CHECK_INTERVAL_MS;
        private Map<String, Object> options = new HashMap<>();

        public Builder taskId(String taskId) { this.taskId = taskId; return this; }
        public Builder trainingType(String trainingType) { this.trainingType = trainingType; return this; }
        public Builder modelId(String modelId) { this.modelId = modelId; return this; }
        public Builder datasetId(String datasetId) { this.datasetId = datasetId; return this; }
        public Builder epochs(int epochs) { this.epochs = epochs; return this; }
        public Builder batchSize(int batchSize) { this.batchSize = batchSize; return this; }
        public Builder learningRate(double learningRate) { this.learningRate = learningRate; return this; }
        public Builder lrSchedule(String lrSchedule) { this.lrSchedule = lrSchedule; return this; }
        public Builder warmupRatio(double warmupRatio) { this.warmupRatio = warmupRatio; return this; }
        public Builder maxSteps(int maxSteps) { this.maxSteps = maxSteps; return this; }
        public Builder maxGradNorm(double maxGradNorm) { this.maxGradNorm = maxGradNorm; return this; }
        public Builder fp16(boolean fp16) { this.fp16 = fp16; return this; }
        public Builder bf16(boolean bf16) { this.bf16 = bf16; return this; }
        public Builder loggingSteps(int loggingSteps) { this.loggingSteps = loggingSteps; return this; }
        public Builder saveSteps(int saveSteps) { this.saveSteps = saveSteps; return this; }
        public Builder evalSteps(int evalSteps) { this.evalSteps = evalSteps; return this; }
        public Builder outputDir(String outputDir) { this.outputDir = outputDir; return this; }
        public Builder seed(int seed) { this.seed = seed; return this; }
        public Builder gradientAccumulationSteps(int v) { this.gradientAccumulationSteps = v; return this; }
        public Builder peftConfigJson(String peftConfigJson) { this.peftConfigJson = peftConfigJson; return this; }
        public Builder updaterConfigJson(String updaterConfigJson) { this.updaterConfigJson = updaterConfigJson; return this; }
        public Builder distillationConfigJson(String v) { this.distillationConfigJson = v; return this; }
        public Builder alignmentConfigJson(String v) { this.alignmentConfigJson = v; return this; }
        public Builder callbackBaseUrl(String callbackBaseUrl) { this.callbackBaseUrl = callbackBaseUrl; return this; }
        public Builder nd4jConfigJson(String nd4jConfigJson) { this.nd4jConfigJson = nd4jConfigJson; return this; }
        public Builder checkpointPath(String checkpointPath) { this.checkpointPath = checkpointPath; return this; }
        public Builder resume(boolean resume) { this.resume = resume; return this; }
        public Builder memoryThresholdPercent(int v) { this.memoryThresholdPercent = Math.max(0, Math.min(v, 100)); return this; }
        public Builder memoryCriticalPercent(int v) { this.memoryCriticalPercent = Math.max(0, Math.min(v, 100)); return this; }
        public Builder memoryKillThresholdPercent(int v) { this.memoryKillThresholdPercent = Math.max(0, Math.min(v, 100)); return this; }
        public Builder memoryCheckIntervalMs(long v) { this.memoryCheckIntervalMs = Math.max(500, v); return this; }
        public Builder options(Map<String, Object> options) { this.options = options != null ? new HashMap<>(options) : new HashMap<>(); return this; }
        public Builder option(String key, Object value) { this.options.put(key, value); return this; }

        public TrainingSubprocessArgs build() {
            if (taskId == null || taskId.isBlank()) {
                throw new IllegalArgumentException("taskId is required");
            }
            if (modelId == null || modelId.isBlank()) {
                throw new IllegalArgumentException("modelId is required");
            }
            return new TrainingSubprocessArgs(
                    taskId, trainingType, modelId, datasetId,
                    epochs, batchSize, learningRate, lrSchedule, warmupRatio,
                    maxSteps, maxGradNorm, fp16, bf16,
                    loggingSteps, saveSteps, evalSteps, outputDir, seed,
                    gradientAccumulationSteps,
                    peftConfigJson, updaterConfigJson, distillationConfigJson, alignmentConfigJson,
                    callbackBaseUrl, nd4jConfigJson, checkpointPath, resume,
                    memoryThresholdPercent, memoryCriticalPercent, memoryKillThresholdPercent,
                    memoryCheckIntervalMs, options);
        }
    }
}
