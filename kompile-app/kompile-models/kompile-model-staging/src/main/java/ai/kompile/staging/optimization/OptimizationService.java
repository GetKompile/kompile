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

import java.io.File;
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
     * Order matches GraphOptimizer.defaultOptimizations() execution order.
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

        BROADCAST_ELIMINATION("BroadcastEliminationOptimizations",
                "Broadcast Elimination",
                "Removes redundant broadcasts, double negation, and canonicalizes commutative ops",
                "simplification"),

        REORDERING("ReorderingOptimizations",
                "Expression Reordering",
                "Reassociates constants and eliminates double transpose",
                "simplification"),

        ALGEBRAIC("AlgebraicOptimizations",
                "Algebraic Simplification",
                "Simplifies algebraic identities: x+0=x, x*1=x, x*0=0, x-x=0",
                "simplification"),

        PEEPHOLE("PeepholeOptimizations",
                "Peephole Optimizations",
                "Removes idempotent ops, inverse pairs, and propagates negation",
                "simplification"),

        ARITHMETIC_CHAIN("ArithmeticChainOptimizations",
                "Arithmetic Chain Folding",
                "Folds arithmetic chains: add(add(x,c1),c2) becomes add(x,c1+c2)",
                "simplification"),

        STRENGTH_REDUCTION("StrengthReductionOptimizations",
                "Strength Reduction",
                "Replaces expensive ops with cheaper equivalents: pow(x,2) becomes square(x), div(x,c) becomes mul(x,1/c)",
                "simplification"),

        IDENTITY_FUNCTION("IdentityFunctionOptimizations",
                "Remove Identity Operations",
                "Removes identity operations and no-op permutations",
                "cleanup"),

        CONCAT_SPLIT("ConcatSplitOptimizations",
                "Concat/Split Optimization",
                "Flattens nested concat operations and eliminates concat-split pairs",
                "simplification"),

        SELECT_WHERE("SelectWhereOptimizations",
                "Select/Where Simplification",
                "Simplifies select/where operations with constant conditions",
                "simplification"),

        REDUNDANCY_ELIMINATION("RedundancyEliminationOptimizations",
                "Redundancy Elimination",
                "Removes single-input concat, full-extent slice, and identity gather operations",
                "simplification"),

        SHAPE_FUNCTION("ShapeFunctionOptimizations",
                "Shape Optimizations",
                "Fuses chained permutes/concats and removes redundant reshapes",
                "fusion"),

        COMMON_SUBEXPRESSION_ELIMINATION("CommonSubexpressionElimination",
                "Common Subexpression Elimination",
                "Deduplicates identical operations sharing the same inputs and arguments",
                "simplification"),

        ATTENTION_FUSION("AttentionFusionOptimizations",
                "Attention Fusion",
                "Detects and fuses attention patterns into optimized operations (must run before horizontal fusion)",
                "fusion"),

        HORIZONTAL_FUSION("HorizontalFusionOptimizations",
                "Horizontal Fusion",
                "Fuses parallel matmul operations sharing the same input into a single fused matmul plus slice",
                "fusion"),

        MATMUL_CHAIN("MatMulChainOptimizations",
                "MatMul Chain Optimization",
                "Folds constant matmul chains and absorbs transposes into matmul flags",
                "fusion"),

        ACTIVATION_FUSION("ActivationFusionOptimizations",
                "Activation Fusion",
                "Fuses activation patterns: sigmoid(x)*x becomes swish, detects SwiGLU gating",
                "fusion"),

        NORMALIZATION_FUSION("NormalizationFusionOptimizations",
                "Normalization Fusion",
                "Detects and fuses RMSNorm and mean-square normalization sub-graphs",
                "fusion"),

        REMATERIALIZATION("RematerializationOptimizations",
                "Rematerialization",
                "Duplicates cheap operations to shorten variable live ranges and reduce peak memory",
                "performance"),

        LINEAR_FUSION("LinearFusionOptimizations",
                "Linear Layer Fusion",
                "Fuses matmul+add into efficient xw_plus_b operations",
                "fusion"),

        QUANTIZATION("QuantizationOptimizations",
                "Quantization",
                "Converts weights to lower precision (INT8, FP16, BF16) for smaller size and faster inference",
                "quantization"),

        CUDNN_FUNCTION("CuDNNFunctionOptimizations",
                "cuDNN Optimizations",
                "Converts operations to use optimized cuDNN implementations (CUDA only)",
                "hardware");

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
     * Defaults include everything except hardware-specific (cuDNN) and quantization.
     */
    private boolean isDefaultOptimization(OptimizationType type) {
        return type != OptimizationType.CUDNN_FUNCTION &&
               type != OptimizationType.QUANTIZATION;
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
                    log.error("GraphOptimizer failed for model {}", modelId, e);
                    return OptimizationResult.failure(modelId, "Graph optimization failed: " + e.getMessage());
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
                validateSavedModel(modelPath, targetOutputs, isZipFormat, config.getSampleInputs());
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
            OptimizationResult result = OptimizationResult.builder()
                    .success(true)
                    .modelId(modelId)
                    .message(String.format("Model optimized successfully. Ops: %d -> %d, Vars: %d -> %d",
                            opsBefore, opsAfter, varsBefore, varsAfter))
                    .optimizationTimeMs(optimizationTimeMs)
                    .backupFile(regResult.getBackupFile())
                    .appliedOptimizations(appliedOptimizationNames)
                    .stats(stats)
                    .build();

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
     * Order matches GraphOptimizer.defaultOptimizations() exactly.
     */
    private List<OptimizerSet> buildOptimizerList(OptimizationConfig config) {
        List<OptimizerSet> optimizers = new ArrayList<>();
        Set<OptimizationType> enabled = config.getEnabledOptimizations();

        // Phase 1: Cleanup and simplification (order matches GraphOptimizer)
        if (enabled.contains(OptimizationType.UNUSED_FUNCTION)) {
            optimizers.add(new UnusedFunctionOptimizations());
        }
        if (enabled.contains(OptimizationType.CONSTANT_FUNCTION)) {
            optimizers.add(new ConstantFunctionOptimizations());
        }
        if (enabled.contains(OptimizationType.BROADCAST_ELIMINATION)) {
            optimizers.add(new BroadcastEliminationOptimizations());
        }
        if (enabled.contains(OptimizationType.REORDERING)) {
            optimizers.add(new ReorderingOptimizations());
        }
        if (enabled.contains(OptimizationType.ALGEBRAIC)) {
            optimizers.add(new AlgebraicOptimizations());
        }
        if (enabled.contains(OptimizationType.PEEPHOLE)) {
            optimizers.add(new PeepholeOptimizations());
        }
        if (enabled.contains(OptimizationType.ARITHMETIC_CHAIN)) {
            optimizers.add(new ArithmeticChainOptimizations());
        }
        if (enabled.contains(OptimizationType.STRENGTH_REDUCTION)) {
            optimizers.add(new StrengthReductionOptimizations());
        }
        if (enabled.contains(OptimizationType.IDENTITY_FUNCTION)) {
            optimizers.add(new IdentityFunctionOptimizations());
        }
        if (enabled.contains(OptimizationType.CONCAT_SPLIT)) {
            optimizers.add(new ConcatSplitOptimizations());
        }
        if (enabled.contains(OptimizationType.SELECT_WHERE)) {
            optimizers.add(new SelectWhereOptimizations());
        }
        if (enabled.contains(OptimizationType.REDUNDANCY_ELIMINATION)) {
            optimizers.add(new RedundancyEliminationOptimizations());
        }
        if (enabled.contains(OptimizationType.SHAPE_FUNCTION)) {
            optimizers.add(new ShapeFunctionOptimizations());
        }
        if (enabled.contains(OptimizationType.COMMON_SUBEXPRESSION_ELIMINATION)) {
            optimizers.add(new CommonSubexpressionElimination());
        }

        // Phase 2: Fusion passes (attention MUST run before horizontal fusion)
        if (enabled.contains(OptimizationType.ATTENTION_FUSION)) {
            optimizers.add(new AttentionFusionOptimizations());
        }
        if (enabled.contains(OptimizationType.HORIZONTAL_FUSION)) {
            optimizers.add(new HorizontalFusionOptimizations());
        }
        if (enabled.contains(OptimizationType.MATMUL_CHAIN)) {
            optimizers.add(new MatMulChainOptimizations());
        }
        // Activation and normalization fusion MUST run before rematerialization
        if (enabled.contains(OptimizationType.ACTIVATION_FUSION)) {
            optimizers.add(new ActivationFusionOptimizations());
        }
        if (enabled.contains(OptimizationType.NORMALIZATION_FUSION)) {
            optimizers.add(new NormalizationFusionOptimizations());
        }

        // Phase 3: Rematerialization (after all fusions to avoid breaking patterns)
        if (enabled.contains(OptimizationType.REMATERIALIZATION)) {
            optimizers.add(new RematerializationOptimizations());
        }

        // Phase 4: Linear fusion and quantization
        if (enabled.contains(OptimizationType.LINEAR_FUSION)) {
            optimizers.add(new LinearFusionOptimizations());
        }
        if (enabled.contains(OptimizationType.QUANTIZATION) && config.getQuantizationType() != null) {
            optimizers.add(new QuantizationOptimizations());
        }

        // Phase 5: Second pass of unused function removal (catch newly dead ops from fusions)
        if (enabled.contains(OptimizationType.UNUSED_FUNCTION)) {
            optimizers.add(new UnusedFunctionOptimizations());
        }

        // Phase 6: Hardware-specific
        if (enabled.contains(OptimizationType.CUDNN_FUNCTION)) {
            optimizers.add(new CuDNNFunctionOptimizations());
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
     * @param sampleInputs Real validation arrays keyed by SameDiff placeholder name
     * @throws Exception if validation fails
     */
    private void validateSavedModel(Path modelPath, List<String> outputs, boolean isZipFormat,
                                    Map<String, String> sampleInputs) throws Exception {
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

        List<String> placeholders = sd.inputs();
        Map<String, org.nd4j.linalg.api.ndarray.INDArray> placeholderValues =
                loadValidationInputs(modelPath, placeholders == null ? Collections.emptyList() : placeholders, sampleInputs);

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
            for (org.nd4j.linalg.api.ndarray.INDArray arr : placeholderValues.values()) {
                if (arr != null) {
                    arr.close();
                }
            }
        }
    }

    private Map<String, org.nd4j.linalg.api.ndarray.INDArray> loadValidationInputs(
            Path modelPath, List<String> placeholders, Map<String, String> sampleInputs) throws IOException {
        Map<String, org.nd4j.linalg.api.ndarray.INDArray> placeholderValues = new LinkedHashMap<>();
        if (placeholders.isEmpty()) {
            return placeholderValues;
        }
        if (sampleInputs == null || sampleInputs.isEmpty()) {
            throw new IllegalArgumentException("Optimization validation requires sampleInputs keyed by SameDiff placeholder name");
        }
        try {
            for (String placeholder : placeholders) {
                String configuredPath = sampleInputs.get(placeholder);
                if (configuredPath == null || configuredPath.isBlank()) {
                    throw new IllegalArgumentException("Missing optimization validation sample input for placeholder: " + placeholder);
                }
                Path inputPath = Path.of(configuredPath);
                if (!inputPath.isAbsolute()) {
                    Path parent = modelPath.toAbsolutePath().getParent();
                    inputPath = (parent == null ? inputPath : parent.resolve(inputPath)).normalize();
                }
                if (!Files.isRegularFile(inputPath)) {
                    throw new IllegalArgumentException("Optimization validation sample input does not exist: " + inputPath);
                }
                org.nd4j.linalg.api.ndarray.INDArray input = readValidationArray(inputPath);
                placeholderValues.put(placeholder, input);
                log.debug("Validation placeholder {} loaded from {}: shape={}, dtype={}",
                        placeholder, inputPath, java.util.Arrays.toString(input.shape()), input.dataType());
            }
            return placeholderValues;
        } catch (RuntimeException | IOException e) {
            for (org.nd4j.linalg.api.ndarray.INDArray arr : placeholderValues.values()) {
                if (arr != null) {
                    arr.close();
                }
            }
            throw e;
        }
    }

    private org.nd4j.linalg.api.ndarray.INDArray readValidationArray(Path inputPath) throws IOException {
        if (inputPath.getFileName().toString().endsWith(".npy")) {
            return readNpy(inputPath.toFile());
        }
        return org.nd4j.linalg.factory.Nd4j.readBinary(inputPath.toFile());
    }

    private org.nd4j.linalg.api.ndarray.INDArray readNpy(File inputFile) throws IOException {
        try {
            return org.nd4j.linalg.factory.Nd4j.createFromNpyFile(inputFile);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to read NPY validation input: " + inputFile, e);
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

        // Default preset - all non-hardware, non-quantization passes
        Map<String, Object> defaultPreset = new LinkedHashMap<>();
        defaultPreset.put("id", "default");
        defaultPreset.put("name", "Default Optimizations");
        defaultPreset.put("description", "Full optimization pipeline matching GraphOptimizer defaults (excludes cuDNN and quantization)");
        defaultPreset.put("optimizations", Arrays.asList(
                "UNUSED_FUNCTION", "CONSTANT_FUNCTION", "BROADCAST_ELIMINATION",
                "REORDERING", "ALGEBRAIC", "PEEPHOLE", "ARITHMETIC_CHAIN",
                "STRENGTH_REDUCTION", "IDENTITY_FUNCTION", "CONCAT_SPLIT",
                "SELECT_WHERE", "REDUNDANCY_ELIMINATION", "SHAPE_FUNCTION",
                "COMMON_SUBEXPRESSION_ELIMINATION", "ATTENTION_FUSION",
                "HORIZONTAL_FUSION", "MATMUL_CHAIN", "ACTIVATION_FUSION",
                "NORMALIZATION_FUSION", "REMATERIALIZATION", "LINEAR_FUSION"
        ));
        presets.add(defaultPreset);

        // Transformer preset - default plus transformer-specific fusions
        Map<String, Object> transformerPreset = new LinkedHashMap<>();
        transformerPreset.put("id", "transformer");
        transformerPreset.put("name", "Transformer/LLM Models");
        transformerPreset.put("description", "All default optimizations plus attention, activation, and normalization fusion for transformers");
        transformerPreset.put("optimizations", Arrays.asList(
                "UNUSED_FUNCTION", "CONSTANT_FUNCTION", "BROADCAST_ELIMINATION",
                "REORDERING", "ALGEBRAIC", "PEEPHOLE", "ARITHMETIC_CHAIN",
                "STRENGTH_REDUCTION", "IDENTITY_FUNCTION", "CONCAT_SPLIT",
                "SELECT_WHERE", "REDUNDANCY_ELIMINATION", "SHAPE_FUNCTION",
                "COMMON_SUBEXPRESSION_ELIMINATION", "ATTENTION_FUSION",
                "HORIZONTAL_FUSION", "MATMUL_CHAIN", "ACTIVATION_FUSION",
                "NORMALIZATION_FUSION", "REMATERIALIZATION", "LINEAR_FUSION"
        ));
        presets.add(transformerPreset);

        // Minimal preset - safe cleanup only
        Map<String, Object> minimalPreset = new LinkedHashMap<>();
        minimalPreset.put("id", "minimal");
        minimalPreset.put("name", "Minimal (Safe)");
        minimalPreset.put("description", "Conservative cleanup: dead code, identity removal, and algebraic simplification");
        minimalPreset.put("optimizations", Arrays.asList(
                "UNUSED_FUNCTION", "CONSTANT_FUNCTION", "IDENTITY_FUNCTION",
                "ALGEBRAIC", "REDUNDANCY_ELIMINATION"
        ));
        presets.add(minimalPreset);

        // Aggressive preset - everything including hardware-specific
        Map<String, Object> aggressivePreset = new LinkedHashMap<>();
        aggressivePreset.put("id", "aggressive");
        aggressivePreset.put("name", "Aggressive");
        aggressivePreset.put("description", "All optimizations including hardware-specific cuDNN replacements");
        aggressivePreset.put("optimizations", Arrays.asList(
                "UNUSED_FUNCTION", "CONSTANT_FUNCTION", "BROADCAST_ELIMINATION",
                "REORDERING", "ALGEBRAIC", "PEEPHOLE", "ARITHMETIC_CHAIN",
                "STRENGTH_REDUCTION", "IDENTITY_FUNCTION", "CONCAT_SPLIT",
                "SELECT_WHERE", "REDUNDANCY_ELIMINATION", "SHAPE_FUNCTION",
                "COMMON_SUBEXPRESSION_ELIMINATION", "ATTENTION_FUSION",
                "HORIZONTAL_FUSION", "MATMUL_CHAIN", "ACTIVATION_FUSION",
                "NORMALIZATION_FUSION", "REMATERIALIZATION", "LINEAR_FUSION",
                "CUDNN_FUNCTION"
        ));
        presets.add(aggressivePreset);

        // Size reduction preset
        Map<String, Object> sizePreset = new LinkedHashMap<>();
        sizePreset.put("id", "size_reduction");
        sizePreset.put("name", "Size Reduction");
        sizePreset.put("description", "Cleanup plus quantization for reducing model size");
        sizePreset.put("optimizations", Arrays.asList(
                "UNUSED_FUNCTION", "CONSTANT_FUNCTION", "REDUNDANCY_ELIMINATION",
                "COMMON_SUBEXPRESSION_ELIMINATION", "QUANTIZATION"
        ));
        sizePreset.put("quantizationType", "FLOAT16");
        presets.add(sizePreset);

        return presets;
    }
}
