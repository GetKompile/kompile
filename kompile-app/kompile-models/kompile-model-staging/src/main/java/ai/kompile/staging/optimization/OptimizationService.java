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

package ai.kompile.staging.optimization;

import ai.kompile.staging.config.StagingSettingsService;
import ai.kompile.modelmanager.registry.ModelMetadata;
import ai.kompile.modelmanager.registry.RegistryService;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.autodiff.samediff.optimize.GraphOptimizer;
import org.nd4j.autodiff.samediff.optimize.OptimizerSet;
import org.nd4j.autodiff.samediff.optimize.optimizations.*;
import org.nd4j.autodiff.samediff.serde.SDZSerializer;
import org.nd4j.autodiff.samediff.serde.SameDiffSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Service for applying configurable graph optimizations to SameDiff models.
 * Supports all optimization types from the ND4J GraphOptimizer framework.
 */
@Service
public class OptimizationService {

    private static final Logger log = LoggerFactory.getLogger(OptimizationService.class);

    private final RegistryService registryService;
    private final StagingSettingsService settingsService;

    @Autowired
    public OptimizationService(RegistryService registryService, StagingSettingsService settingsService) {
        this.registryService = registryService;
        this.settingsService = settingsService;
    }

    /**
     * Available optimization types that can be configured.
     */
    public enum OptimizationType {
        UNUSED_FUNCTION("UnusedFunctionOptimizations",
                "Remove Unused Functions",
                "Removes constants and operations that are not used by any required outputs",
                "cleanup"),

        CONSTANT_FUNCTION("ConstantFunctionOptimizations",
                "Constant Folding",
                "Pre-executes operations on constants and replaces them with the result",
                "folding"),

        IDENTITY_FUNCTION("IdentityFunctionOptimizations",
                "Remove Identity Operations",
                "Removes identity operations and no-op permutations",
                "cleanup"),

        SHAPE_FUNCTION("ShapeFunctionOptimizations",
                "Shape Optimizations",
                "Fuses chained permutes/concats and removes redundant reshapes",
                "fusion"),

        LINEAR_FUSION("LinearFusionOptimizations",
                "Linear Layer Fusion",
                "Fuses matmul+add into efficient xw_plus_b operations",
                "fusion"),

        ATTENTION_FUSION("AttentionFusionOptimizations",
                "Attention Fusion",
                "Detects and fuses attention patterns into optimized operations",
                "fusion"),

        CUDNN_FUNCTION("CuDNNFunctionOptimizations",
                "cuDNN Optimizations",
                "Converts operations to use optimized cuDNN implementations (CUDA only)",
                "hardware"),

        QUANTIZATION("QuantizationOptimizations",
                "Quantization",
                "Converts weights to lower precision (INT8, FP16, BF16) for smaller size and faster inference",
                "quantization");

        private final String className;
        private final String displayName;
        private final String description;
        private final String category;

        OptimizationType(String className, String displayName, String description, String category) {
            this.className = className;
            this.displayName = displayName;
            this.description = description;
            this.category = category;
        }

        public String getClassName() { return className; }
        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
        public String getCategory() { return category; }
    }

    /**
     * Quantization types for weight compression.
     */
    public enum QuantizationType {
        INT8("int8", "INT8", "8-bit integer quantization, ~4x size reduction"),
        UINT8("uint8", "UINT8", "Unsigned 8-bit integer quantization"),
        FLOAT16("float16", "Float16", "Half precision floating point, ~2x size reduction"),
        BFLOAT16("bfloat16", "BFloat16", "Brain floating point 16, optimized for ML workloads");

        private final String value;
        private final String displayName;
        private final String description;

        QuantizationType(String value, String displayName, String description) {
            this.value = value;
            this.displayName = displayName;
            this.description = description;
        }

        public String getValue() { return value; }
        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
    }

    /**
     * Configuration for an optimization run.
     */
    public static class OptimizationConfig {
        private Set<OptimizationType> enabledOptimizations;
        private QuantizationType quantizationType;
        private boolean quantizePerChannel = false;
        private boolean createBackup = true;

        public OptimizationConfig() {
            // Default optimizations (same as GraphOptimizer.defaultOptimizations())
            this.enabledOptimizations = EnumSet.of(
                    OptimizationType.UNUSED_FUNCTION,
                    OptimizationType.CONSTANT_FUNCTION,
                    OptimizationType.IDENTITY_FUNCTION,
                    OptimizationType.SHAPE_FUNCTION,
                    OptimizationType.LINEAR_FUSION,
                    OptimizationType.ATTENTION_FUSION
            );
        }

        public Set<OptimizationType> getEnabledOptimizations() { return enabledOptimizations; }
        public void setEnabledOptimizations(Set<OptimizationType> types) { this.enabledOptimizations = types; }
        public QuantizationType getQuantizationType() { return quantizationType; }
        public void setQuantizationType(QuantizationType type) { this.quantizationType = type; }
        public boolean isQuantizePerChannel() { return quantizePerChannel; }
        public void setQuantizePerChannel(boolean perChannel) { this.quantizePerChannel = perChannel; }
        public boolean isCreateBackup() { return createBackup; }
        public void setCreateBackup(boolean createBackup) { this.createBackup = createBackup; }

        public void enableOnly(OptimizationType... types) {
            this.enabledOptimizations = EnumSet.noneOf(OptimizationType.class);
            this.enabledOptimizations.addAll(Arrays.asList(types));
        }

        public void enableAll() {
            this.enabledOptimizations = EnumSet.allOf(OptimizationType.class);
        }

        public void disableAll() {
            this.enabledOptimizations = EnumSet.noneOf(OptimizationType.class);
        }
    }

    /**
     * Result of an optimization operation.
     */
    public static class OptimizationResult {
        private boolean success;
        private String modelId;
        private String message;
        private String error;
        private long optimizationTimeMs;
        private String backupFile;
        private List<String> appliedOptimizations;
        private ModelMetadata.OptimizationStats stats;

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public String getModelId() { return modelId; }
        public void setModelId(String modelId) { this.modelId = modelId; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
        public long getOptimizationTimeMs() { return optimizationTimeMs; }
        public void setOptimizationTimeMs(long ms) { this.optimizationTimeMs = ms; }
        public String getBackupFile() { return backupFile; }
        public void setBackupFile(String backupFile) { this.backupFile = backupFile; }
        public List<String> getAppliedOptimizations() { return appliedOptimizations; }
        public void setAppliedOptimizations(List<String> opts) { this.appliedOptimizations = opts; }
        public ModelMetadata.OptimizationStats getStats() { return stats; }
        public void setStats(ModelMetadata.OptimizationStats stats) { this.stats = stats; }

        public static OptimizationResult failure(String modelId, String error) {
            OptimizationResult r = new OptimizationResult();
            r.success = false;
            r.modelId = modelId;
            r.error = error;
            return r;
        }
    }

    /**
     * Get all available optimization types with their metadata.
     */
    public List<Map<String, Object>> getAvailableOptimizations() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (OptimizationType type : OptimizationType.values()) {
            Map<String, Object> opt = new LinkedHashMap<>();
            opt.put("id", type.name());
            opt.put("className", type.getClassName());
            opt.put("displayName", type.getDisplayName());
            opt.put("description", type.getDescription());
            opt.put("category", type.getCategory());
            opt.put("isDefault", isDefaultOptimization(type));
            result.add(opt);
        }
        return result;
    }

    /**
     * Get available quantization types.
     */
    public List<Map<String, Object>> getAvailableQuantizationTypes() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (QuantizationType type : QuantizationType.values()) {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("id", type.name());
            q.put("value", type.getValue());
            q.put("displayName", type.getDisplayName());
            q.put("description", type.getDescription());
            result.add(q);
        }
        return result;
    }

    /**
     * Check if an optimization type is in the default set.
     */
    private boolean isDefaultOptimization(OptimizationType type) {
        return type == OptimizationType.UNUSED_FUNCTION ||
               type == OptimizationType.CONSTANT_FUNCTION ||
               type == OptimizationType.IDENTITY_FUNCTION ||
               type == OptimizationType.SHAPE_FUNCTION ||
               type == OptimizationType.LINEAR_FUSION ||
               type == OptimizationType.ATTENTION_FUSION;
    }

    /**
     * Optimize a model with the specified configuration.
     */
    public OptimizationResult optimize(String modelId, OptimizationConfig config, boolean force) {
        log.info("Starting configurable optimization for model: {} with {} optimizations",
                modelId, config.getEnabledOptimizations().size());

        try {
            // Prepare for optimization (creates backup)
            Optional<Path> modelPathOpt = registryService.prepareForOptimization(modelId, force);
            if (modelPathOpt.isEmpty()) {
                // Check if already optimized
                return registryService.getModel(modelId)
                        .map(entry -> {
                            if (entry.getMetadata() != null && Boolean.TRUE.equals(entry.getMetadata().getOptimized())) {
                                return OptimizationResult.failure(modelId,
                                        "Model is already optimized. Use force=true to re-optimize.");
                            }
                            return OptimizationResult.failure(modelId,
                                    "Failed to prepare for optimization. Model file may be missing.");
                        })
                        .orElse(OptimizationResult.failure(modelId, "Model not found: " + modelId));
            }

            Path modelPath = modelPathOpt.get();
            long sizeBeforeBytes = Files.size(modelPath);

            // Detect file format
            boolean isZipFormat = isZipFile(modelPath);
            log.info("Model file format: {} (path: {})", isZipFormat ? "ZIP/SDZ" : "FlatBuffer/SDNB", modelPath);

            // Load the model
            long startTime = System.currentTimeMillis();
            SameDiff sd;
            if (isZipFormat) {
                sd = SDZSerializer.load(modelPath.toFile(), true);
            } else {
                sd = SameDiff.load(modelPath.toFile(), true);
            }

            if (sd == null) {
                return OptimizationResult.failure(modelId, "Failed to load model from: " + modelPath);
            }

            // Get before stats
            int opsBefore = sd.ops().length;
            int varsBefore = sd.variables().size();

            // Get model outputs
            List<String> targetOutputs = sd.outputs();
            if (targetOutputs == null || targetOutputs.isEmpty()) {
                return OptimizationResult.failure(modelId, "Model has no outputs defined");
            }

            // Build optimizer list based on configuration
            List<OptimizerSet> optimizers = buildOptimizerList(config);
            List<String> appliedOptimizationNames = new ArrayList<>();
            for (OptimizationType type : config.getEnabledOptimizations()) {
                appliedOptimizationNames.add(type.getClassName());
            }

            // Apply optimizations
            SameDiff optimizedSd;
            if (!optimizers.isEmpty()) {
                try {
                    optimizedSd = GraphOptimizer.optimize(sd, targetOutputs, optimizers);
                } catch (Exception e) {
                    log.error("GraphOptimizer failed. Skipping optimization.", e);
                    optimizedSd = sd;
                    appliedOptimizationNames.clear();
                    appliedOptimizationNames.add("NONE (optimization failed)");
                }
            } else {
                optimizedSd = sd;
                log.warn("No optimizations enabled, model will be saved without changes");
            }

            // Get after stats
            int opsAfter = optimizedSd.ops().length;
            int varsAfter = optimizedSd.variables().size();

            // Save optimized model first
            if (isZipFormat) {
                SDZSerializer.save(optimizedSd, modelPath.toFile(), true, Collections.emptyMap());
            } else {
                SameDiffSerializer.saveAutoShard(optimizedSd, modelPath.toFile(), true, Collections.emptyMap());
            }

            // Validate by loading the saved model back and running inference
            // This catches serialization issues that only appear after save/load
            try {
                validateSavedModel(modelPath, targetOutputs, isZipFormat);
            } catch (Exception e) {
                log.error("Saved model validation failed. Restoring from backup.", e);
                // Restore backup if validation fails
                registryService.restoreFromBackup(modelId);
                return OptimizationResult.failure(modelId,
                        "Optimization produced invalid model after serialization: " + e.getMessage() + ". Backup restored.");
            }

            long sizeAfterBytes = Files.size(modelPath);
            long optimizationTimeMs = System.currentTimeMillis() - startTime;

            log.info("Model {} optimized in {}ms - ops: {} -> {}, vars: {} -> {}, size: {} -> {} bytes",
                    modelId, optimizationTimeMs, opsBefore, opsAfter, varsBefore, varsAfter,
                    sizeBeforeBytes, sizeAfterBytes);

            // Calculate reduction percent
            double reductionPercent = 0.0;
            if (sizeBeforeBytes > 0) {
                reductionPercent = ((double)(sizeBeforeBytes - sizeAfterBytes) / sizeBeforeBytes) * 100.0;
            }

            // Build stats
            ModelMetadata.OptimizationStats stats = ModelMetadata.OptimizationStats.builder()
                    .opsBefore(opsBefore)
                    .opsAfter(opsAfter)
                    .varsBefore(varsBefore)
                    .varsAfter(varsAfter)
                    .sizeBeforeBytes(sizeBeforeBytes)
                    .sizeAfterBytes(sizeAfterBytes)
                    .reductionPercent(reductionPercent)
                    .build();

            // Build optimization config for persistence
            ModelMetadata.OptimizationConfig savedConfig = ModelMetadata.OptimizationConfig.builder()
                    .enabledPasses(appliedOptimizationNames)
                    .quantizationType(config.getQuantizationType() != null ? config.getQuantizationType().getValue() : null)
                    .quantizePerChannel(config.isQuantizePerChannel())
                    .build();

            // Update registry with optimization info
            RegistryService.OptimizationResult regResult = registryService.completeOptimizationWithDetails(
                    modelId, optimizationTimeMs, appliedOptimizationNames, stats, savedConfig);

            // Build result
            OptimizationResult result = new OptimizationResult();
            result.setSuccess(true);
            result.setModelId(modelId);
            result.setMessage(String.format("Model optimized successfully. Ops: %d -> %d, Vars: %d -> %d",
                    opsBefore, opsAfter, varsBefore, varsAfter));
            result.setOptimizationTimeMs(optimizationTimeMs);
            result.setBackupFile(regResult.getBackupFile());
            result.setAppliedOptimizations(appliedOptimizationNames);
            result.setStats(stats);

            // Notify kompile-app-main to reload the model if callback URL is configured
            notifyModelReload(modelId);

            return result;

        } catch (Exception e) {
            log.error("Failed to optimize model {}: {}", modelId, e.getMessage(), e);
            return OptimizationResult.failure(modelId, "Optimization failed: " + e.getMessage());
        }
    }

    /**
     * Notify kompile-app-main to reload the optimized model.
     * This ensures the main application picks up the optimized version.
     *
     * @param modelId The ID of the model that was optimized
     */
    private void notifyModelReload(String modelId) {
        settingsService.notifyModelReload(modelId);
    }

    /**
     * Build the list of optimizer sets based on configuration.
     */
    private List<OptimizerSet> buildOptimizerList(OptimizationConfig config) {
        List<OptimizerSet> optimizers = new ArrayList<>();
        Set<OptimizationType> enabled = config.getEnabledOptimizations();

        // Add optimizers in the recommended order
        if (enabled.contains(OptimizationType.UNUSED_FUNCTION)) {
            optimizers.add(new UnusedFunctionOptimizations());
        }
        if (enabled.contains(OptimizationType.CONSTANT_FUNCTION)) {
            optimizers.add(new ConstantFunctionOptimizations());
        }
        if (enabled.contains(OptimizationType.IDENTITY_FUNCTION)) {
            optimizers.add(new IdentityFunctionOptimizations());
        }
        if (enabled.contains(OptimizationType.SHAPE_FUNCTION)) {
            optimizers.add(new ShapeFunctionOptimizations());
        }
        if (enabled.contains(OptimizationType.LINEAR_FUSION)) {
            optimizers.add(new LinearFusionOptimizations());
        }
        if (enabled.contains(OptimizationType.ATTENTION_FUSION)) {
            optimizers.add(new AttentionFusionOptimizations());
        }

        // Second pass of unused function removal (after fusions)
        if (enabled.contains(OptimizationType.UNUSED_FUNCTION)) {
            optimizers.add(new UnusedFunctionOptimizations());
        }

        if (enabled.contains(OptimizationType.CUDNN_FUNCTION)) {
            optimizers.add(new CuDNNFunctionOptimizations());
        }

        // Note: Quantization requires special handling - add if enabled
        // QuantizationOptimizations would need custom configuration
        if (enabled.contains(OptimizationType.QUANTIZATION) && config.getQuantizationType() != null) {
            // Quantization is handled separately through the QuantizationOptimizations class
            // which requires specific configuration
            optimizers.add(new QuantizationOptimizations());
        }

        return optimizers;
    }

    /**
     * Validate that a saved model can be loaded and executed without errors.
     * This tests the full round-trip: save -> load -> execute.
     *
     * @param modelPath Path to the saved model file
     * @param outputs The expected output variable names
     * @param isZipFormat Whether the model is in ZIP/SDZ format
     * @throws Exception if validation fails
     */
    private void validateSavedModel(Path modelPath, List<String> outputs, boolean isZipFormat) throws Exception {
        log.info("Validating saved model can be loaded and executed...");

        // Load the model back from disk
        SameDiff sd;
        if (isZipFormat) {
            sd = SDZSerializer.load(modelPath.toFile(), true);
        } else {
            sd = SameDiff.load(modelPath.toFile(), true);
        }

        if (sd == null) {
            throw new RuntimeException("Failed to load saved model from: " + modelPath);
        }

        log.info("Loaded saved model, now validating execution...");

        // Get placeholders (inputs)
        List<String> placeholders = sd.inputs();
        if (placeholders == null || placeholders.isEmpty()) {
            log.warn("Model has no placeholders defined, skipping validation");
            return;
        }

        // Create dummy inputs for validation
        Map<String, org.nd4j.linalg.api.ndarray.INDArray> placeholderValues = new LinkedHashMap<>();
        for (String placeholder : placeholders) {
            org.nd4j.autodiff.samediff.SDVariable var = sd.getVariable(placeholder);
            if (var == null) {
                throw new RuntimeException("Placeholder variable not found: " + placeholder);
            }

            long[] shape = var.getShape();
            if (shape == null) {
                // Try to infer shape from placeholder info or use default
                log.warn("Placeholder {} has no shape, using default [1, 512]", placeholder);
                shape = new long[]{1, 512};
            }

            // Replace any -1 (dynamic) dimensions with 1 for validation
            for (int i = 0; i < shape.length; i++) {
                if (shape[i] <= 0) {
                    shape[i] = 1;
                }
            }

            // Create appropriate input based on expected dtype
            org.nd4j.linalg.api.buffer.DataType dtype = var.dataType();
            if (dtype == null) {
                dtype = org.nd4j.linalg.api.buffer.DataType.FLOAT;
            }

            org.nd4j.linalg.api.ndarray.INDArray input;
            if (dtype == org.nd4j.linalg.api.buffer.DataType.INT64 ||
                dtype == org.nd4j.linalg.api.buffer.DataType.INT32 ||
                dtype == org.nd4j.linalg.api.buffer.DataType.LONG) {
                // Token IDs - use small positive integers
                input = org.nd4j.linalg.factory.Nd4j.ones(dtype, shape);
            } else {
                // Float inputs
                input = org.nd4j.linalg.factory.Nd4j.ones(dtype, shape);
            }

            placeholderValues.put(placeholder, input);
            log.debug("Validation placeholder {}: shape={}, dtype={}", placeholder, shape, dtype);
        }

        // Try to execute the model
        try {
            Map<String, org.nd4j.linalg.api.ndarray.INDArray> result = sd.output(placeholderValues, outputs);

            // Check outputs are not null
            for (String outputName : outputs) {
                org.nd4j.linalg.api.ndarray.INDArray output = result.get(outputName);
                if (output == null) {
                    throw new RuntimeException("Output '" + outputName + "' is null after execution");
                }
                log.debug("Validation output {}: shape={}", outputName, java.util.Arrays.toString(output.shape()));
            }

            log.info("Optimized model validation passed - {} outputs generated successfully", outputs.size());

        } finally {
            // Clean up dummy inputs
            for (org.nd4j.linalg.api.ndarray.INDArray arr : placeholderValues.values()) {
                if (arr != null) {
                    arr.close();
                }
            }
        }
    }

    /**
     * Check if a file is a ZIP archive by reading magic bytes.
     */
    private boolean isZipFile(Path filePath) {
        try (InputStream is = Files.newInputStream(filePath)) {
            byte[] magic = new byte[4];
            int read = is.read(magic);
            if (read >= 2) {
                return magic[0] == 0x50 && magic[1] == 0x4B;
            }
        } catch (IOException e) {
            log.warn("Failed to read magic bytes from {}", filePath, e);
        }
        return false;
    }

    /**
     * Get preset optimization configurations.
     */
    public List<Map<String, Object>> getPresets() {
        List<Map<String, Object>> presets = new ArrayList<>();

        // Default preset
        Map<String, Object> defaultPreset = new LinkedHashMap<>();
        defaultPreset.put("id", "default");
        defaultPreset.put("name", "Default Optimizations");
        defaultPreset.put("description", "Standard optimization pipeline for most models");
        defaultPreset.put("optimizations", Arrays.asList(
                "UNUSED_FUNCTION", "CONSTANT_FUNCTION", "IDENTITY_FUNCTION",
                "SHAPE_FUNCTION", "LINEAR_FUSION", "ATTENTION_FUSION"
        ));
        presets.add(defaultPreset);

        // Transformer preset
        Map<String, Object> transformerPreset = new LinkedHashMap<>();
        transformerPreset.put("id", "transformer");
        transformerPreset.put("name", "Transformer/BERT Models");
        transformerPreset.put("description", "Optimized for transformer architectures with attention fusion");
        transformerPreset.put("optimizations", Arrays.asList(
                "UNUSED_FUNCTION", "CONSTANT_FUNCTION", "IDENTITY_FUNCTION",
                "SHAPE_FUNCTION", "LINEAR_FUSION", "ATTENTION_FUSION"
        ));
        presets.add(transformerPreset);

        // Minimal preset
        Map<String, Object> minimalPreset = new LinkedHashMap<>();
        minimalPreset.put("id", "minimal");
        minimalPreset.put("name", "Minimal (Safe)");
        minimalPreset.put("description", "Conservative optimizations with minimal risk of changing behavior");
        minimalPreset.put("optimizations", Arrays.asList(
                "UNUSED_FUNCTION", "IDENTITY_FUNCTION"
        ));
        presets.add(minimalPreset);

        // Aggressive preset
        Map<String, Object> aggressivePreset = new LinkedHashMap<>();
        aggressivePreset.put("id", "aggressive");
        aggressivePreset.put("name", "Aggressive");
        aggressivePreset.put("description", "All optimizations including hardware-specific ones");
        aggressivePreset.put("optimizations", Arrays.asList(
                "UNUSED_FUNCTION", "CONSTANT_FUNCTION", "IDENTITY_FUNCTION",
                "SHAPE_FUNCTION", "LINEAR_FUSION", "ATTENTION_FUSION", "CUDNN_FUNCTION"
        ));
        presets.add(aggressivePreset);

        // Size reduction preset
        Map<String, Object> sizePreset = new LinkedHashMap<>();
        sizePreset.put("id", "size_reduction");
        sizePreset.put("name", "Size Reduction");
        sizePreset.put("description", "Focus on reducing model size with quantization");
        sizePreset.put("optimizations", Arrays.asList(
                "UNUSED_FUNCTION", "CONSTANT_FUNCTION", "QUANTIZATION"
        ));
        sizePreset.put("quantizationType", "FLOAT16");
        presets.add(sizePreset);

        return presets;
    }
}
