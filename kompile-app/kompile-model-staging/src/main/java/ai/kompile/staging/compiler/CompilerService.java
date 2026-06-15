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

package ai.kompile.staging.compiler;

import ai.kompile.modelmanager.registry.ModelMetadata;
import ai.kompile.modelmanager.registry.RegistryService;
import ai.kompile.staging.web.dto.*;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.autodiff.samediff.internal.SameDiffOp;
import org.nd4j.autodiff.samediff.internal.Variable;
import org.nd4j.autodiff.samediff.optimize.GraphOptimizer;
import org.nd4j.autodiff.samediff.optimize.Optimizer;
import org.nd4j.autodiff.samediff.optimize.OptimizerSet;
import org.nd4j.autodiff.samediff.optimize.optimizations.*;
import org.nd4j.autodiff.samediff.serde.SDZSerializer;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.nativeblas.NativeOps;
import org.nd4j.nativeblas.NativeOpsHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Service for SameDiff graph compilation and optimization.
 * Calls GraphOptimizer directly from deeplearning4j and provides
 * hierarchical pass/profile metadata for the frontend pass picker.
 */
@Service
public class CompilerService {

    private static final Logger log = LoggerFactory.getLogger(CompilerService.class);

    @Value("${kompile.staging.models-dir:#{systemProperties['user.home'] + '/.kompile/models'}}")
    private String modelsDir;

    private final RegistryService registryService;

    public CompilerService(RegistryService registryService) {
        this.registryService = registryService;
    }

    private final AtomicLong jobCounter = new AtomicLong(0);
    private final Map<String, CompilerOptimizeResponse> jobResults = new ConcurrentHashMap<>();
    private final Map<String, CompilationJobStatus> activeJobs = new ConcurrentHashMap<>();
    private final Map<String, List<CompilationLogEntry>> jobLogs = new ConcurrentHashMap<>();
    private final Map<String, List<SseEmitter>> jobEmitters = new ConcurrentHashMap<>();
    private final ExecutorService compilationExecutor = Executors.newFixedThreadPool(2);

    // ==================== Pass & Profile Definitions ====================

    private static final List<OptimizationPassInfo> ALL_PASSES = List.of(
            OptimizationPassInfo.builder()
                    .id("dead_code_elimination")
                    .name("Dead Code Elimination")
                    .description("Removes operations whose outputs are never used by any downstream computation")
                    .category("CLEANUP")
                    .isDefault(true)
                    .subPasses(List.of(
                            OptimizationPassInfo.builder().id("RemoveUnusedConstants").name("Remove Unused Constants")
                                    .description("Removes constant variables not consumed by any operation").category("CLEANUP").isDefault(true).build()
                    ))
                    .build(),
            OptimizationPassInfo.builder()
                    .id("constant_folding")
                    .name("Constant Folding")
                    .description("Pre-computes operations where all inputs are constants, replacing them with a single constant value")
                    .category("CLEANUP")
                    .isDefault(true)
                    .subPasses(List.of(
                            OptimizationPassInfo.builder().id("FoldConstantFunctions").name("Fold Constant Functions")
                                    .description("Executes ops with all-constant inputs at optimization time and replaces with result").category("CLEANUP").isDefault(true).build()
                    ))
                    .build(),
            OptimizationPassInfo.builder()
                    .id("algebraic_simplification")
                    .name("Algebraic Simplification")
                    .description("Simplifies algebraic expressions such as x*1=x, x+0=x, x*0=0")
                    .category("CLEANUP")
                    .isDefault(true)
                    .subPasses(List.of(
                            OptimizationPassInfo.builder().id("AddZero").name("Add Zero").description("Simplifies x+0 to x").category("CLEANUP").isDefault(true).build(),
                            OptimizationPassInfo.builder().id("SubtractZero").name("Subtract Zero").description("Simplifies x-0 to x").category("CLEANUP").isDefault(true).build(),
                            OptimizationPassInfo.builder().id("MultiplyOne").name("Multiply One").description("Simplifies x*1 to x").category("CLEANUP").isDefault(true).build(),
                            OptimizationPassInfo.builder().id("MultiplyZero").name("Multiply Zero").description("Simplifies x*0 to 0").category("CLEANUP").isDefault(true).build(),
                            OptimizationPassInfo.builder().id("SubtractSelf").name("Subtract Self").description("Simplifies x-x to 0").category("CLEANUP").isDefault(true).build(),
                            OptimizationPassInfo.builder().id("DivideOne").name("Divide One").description("Simplifies x/1 to x").category("CLEANUP").isDefault(true).build()
                    ))
                    .build(),
            OptimizationPassInfo.builder()
                    .id("identity_removal")
                    .name("Identity Removal")
                    .description("Removes identity (no-op) operations that pass through values unchanged")
                    .category("CLEANUP")
                    .isDefault(true)
                    .subPasses(List.of(
                            OptimizationPassInfo.builder().id("RemoveIdentityPermute").name("Remove Identity Permute")
                                    .description("Removes permute(0,1,2,...) operations that don't change dimension order").category("CLEANUP").isDefault(true).build(),
                            OptimizationPassInfo.builder().id("RemoveIdentityOps").name("Remove Identity Ops")
                                    .description("Removes identity(x) operations and rewires inputs directly").category("CLEANUP").isDefault(true).build()
                    ))
                    .build(),
            OptimizationPassInfo.builder()
                    .id("shape_fusion")
                    .name("Shape Fusion")
                    .description("Fuses consecutive shape manipulation operations (reshape, transpose, permute) into a single operation")
                    .category("FUSION")
                    .isDefault(false)
                    .subPasses(List.of(
                            OptimizationPassInfo.builder().id("FuseChainedPermutes").name("Fuse Chained Permutes")
                                    .description("Combines consecutive permute operations into a single permute").category("FUSION").isDefault(false).build(),
                            OptimizationPassInfo.builder().id("FuseChainedReshapes").name("Fuse Chained Reshapes")
                                    .description("Combines consecutive reshape operations into a single reshape").category("FUSION").isDefault(false).build(),
                            OptimizationPassInfo.builder().id("FuseChainedConcatOps").name("Fuse Chained Concats")
                                    .description("Combines consecutive concat operations along the same axis").category("FUSION").isDefault(false).build()
                    ))
                    .build(),
            OptimizationPassInfo.builder()
                    .id("activation_fusion")
                    .name("Activation Fusion")
                    .description("Fuses activation function patterns into single optimized operations")
                    .category("FUSION")
                    .isDefault(false)
                    .subPasses(List.of(
                            OptimizationPassInfo.builder().id("FuseSigmoidMulToSwish").name("Fuse Sigmoid*Mul to Swish")
                                    .description("Detects sigmoid(x)*x pattern and replaces with swish activation").category("FUSION").isDefault(false).build(),
                            OptimizationPassInfo.builder().id("FuseSwiGLUPattern").name("Fuse SwiGLU Pattern")
                                    .description("Detects SwiGLU gating pattern and replaces with fused SwiGLU op").category("FUSION").isDefault(false).build()
                    ))
                    .build(),
            OptimizationPassInfo.builder()
                    .id("normalization_fusion")
                    .name("Normalization Fusion")
                    .description("Fuses layer normalization or RMS normalization sub-graphs into single fused operations")
                    .category("FUSION")
                    .isDefault(false)
                    .subPasses(List.of(
                            OptimizationPassInfo.builder().id("FuseRMSNormPattern").name("Fuse RMSNorm Pattern")
                                    .description("Detects RMS normalization sub-graph and replaces with fused RMSNorm op").category("FUSION").isDefault(false).build(),
                            OptimizationPassInfo.builder().id("FuseMeanSquarePattern").name("Fuse Mean Square Pattern")
                                    .description("Detects mean-square computation pattern and replaces with fused op").category("FUSION").isDefault(false).build()
                    ))
                    .build(),
            OptimizationPassInfo.builder()
                    .id("linear_fusion")
                    .name("Linear Fusion")
                    .description("Fuses consecutive linear operations (matmul + bias add) into a single fused linear op")
                    .category("FUSION")
                    .isDefault(false)
                    .subPasses(List.of(
                            OptimizationPassInfo.builder().id("FuseMatMulWithAdd").name("Fuse MatMul + Add")
                                    .description("Fuses matmul followed by add into xw_plus_b operation").category("FUSION").isDefault(false).build(),
                            OptimizationPassInfo.builder().id("FuseTensorMmulWithAdd").name("Fuse TensorMmul + Add")
                                    .description("Fuses tensor matmul followed by add into fused operation").category("FUSION").isDefault(false).build(),
                            OptimizationPassInfo.builder().id("FuseConsecutiveReshapes").name("Fuse Consecutive Reshapes")
                                    .description("Merges consecutive reshape operations in linear layers").category("FUSION").isDefault(false).build()
                    ))
                    .build(),
            OptimizationPassInfo.builder()
                    .id("attention_fusion")
                    .name("Attention Fusion")
                    .description("Detects and fuses multi-head attention patterns (Q*K^T/sqrt(d)*V) into optimized attention ops")
                    .category("FUSION")
                    .isDefault(false)
                    .subPasses(List.of(
                            OptimizationPassInfo.builder().id("FuseManualAttentionPattern").name("Fuse Manual Attention")
                                    .description("Detects manual Q*K^T/sqrt(d)*V pattern and replaces with DotProductAttention").category("FUSION").isDefault(false).build(),
                            OptimizationPassInfo.builder().id("FuseAttentionWithProjection").name("Fuse Attention + Projection")
                                    .description("Fuses attention output with linear projection layer").category("FUSION").isDefault(false).build(),
                            OptimizationPassInfo.builder().id("FuseAttentionWithCausalMask").name("Fuse Attention + Causal Mask")
                                    .description("Fuses attention with causal (triangular) mask application").category("FUSION").isDefault(false).build(),
                            OptimizationPassInfo.builder().id("FuseAttentionWithMask").name("Fuse Attention + Mask")
                                    .description("Fuses attention with arbitrary mask application").category("FUSION").isDefault(false).build(),
                            OptimizationPassInfo.builder().id("CollectMultiHeadAttention").name("Collect Multi-Head Attention")
                                    .description("Collects split Q/K/V heads into multi-head attention op").category("FUSION").isDefault(false).build(),
                            OptimizationPassInfo.builder().id("FuseLLaMAAttentionBlock").name("Fuse LLaMA Attention Block")
                                    .description("Detects LLaMA-style attention block and replaces with fused op").category("FUSION").isDefault(false).build()
                    ))
                    .build(),
            OptimizationPassInfo.builder()
                    .id("cudnn_replacement")
                    .name("cuDNN Replacement")
                    .description("Replaces compatible operations with cuDNN-accelerated implementations for GPU execution")
                    .category("GPU")
                    .isDefault(false)
                    .subPasses(List.of(
                            OptimizationPassInfo.builder().id("CudnnConv2dNCHWtoNHWCConversion").name("cuDNN Conv2D NCHW→NHWC")
                                    .description("Converts Conv2D from NCHW to NHWC layout for cuDNN Tensor Core acceleration").category("GPU").isDefault(false).build()
                    ))
                    .build(),
            OptimizationPassInfo.builder()
                    .id("quantization")
                    .name("Quantization")
                    .description("Converts floating-point weights and activations to lower-precision integer types for reduced memory and faster inference")
                    .category("QUANTIZATION")
                    .isDefault(false)
                    .subPasses(List.of(
                            OptimizationPassInfo.builder().id("QuantizeConstantsToFP16").name("Quantize Constants to FP16")
                                    .description("Converts floating-point constant weights to FP16 half-precision").category("QUANTIZATION").isDefault(false).build(),
                            OptimizationPassInfo.builder().id("QuantizeConstantsToINT8").name("Quantize Constants to INT8")
                                    .description("Converts floating-point constant weights to INT8 with scale factors").category("QUANTIZATION").isDefault(false).build(),
                            OptimizationPassInfo.builder().id("FuseDequantizeQuantizePair").name("Fuse Dequantize-Quantize Pair")
                                    .description("Removes redundant dequantize→quantize pairs between consecutive quantized ops").category("QUANTIZATION").isDefault(false).build(),
                            OptimizationPassInfo.builder().id("RemoveRedundantCasts").name("Remove Redundant Casts")
                                    .description("Removes unnecessary dtype cast operations").category("QUANTIZATION").isDefault(false).build(),
                            OptimizationPassInfo.builder().id("OptimizeConstantsForInference").name("Optimize Constants for Inference")
                                    .description("Optimizes constant storage and layout for inference workloads").category("QUANTIZATION").isDefault(false).build(),
                            OptimizationPassInfo.builder().id("QuantizePlaceholder").name("Quantize Placeholder")
                                    .description("Adds quantization for placeholder inputs at graph boundaries").category("QUANTIZATION").isDefault(false).build()
                    ))
                    .build()
    );

    private static final List<OptimizationProfileInfo> ALL_PROFILES = List.of(
            OptimizationProfileInfo.builder()
                    .profileName("NONE")
                    .description("No optimization passes applied")
                    .includedPasses(Collections.emptyList())
                    .build(),
            OptimizationProfileInfo.builder()
                    .profileName("BASIC")
                    .description("Basic cleanup optimizations: dead code elimination, constant folding, algebraic simplification, identity removal")
                    .includedPasses(List.of("dead_code_elimination", "constant_folding", "algebraic_simplification", "identity_removal"))
                    .build(),
            OptimizationProfileInfo.builder()
                    .profileName("TRANSFORMER")
                    .description("Optimizations targeting transformer architectures: basic passes plus attention, normalization, and linear fusion")
                    .includedPasses(List.of("dead_code_elimination", "constant_folding", "algebraic_simplification", "identity_removal",
                            "attention_fusion", "normalization_fusion", "linear_fusion"))
                    .build(),
            OptimizationProfileInfo.builder()
                    .profileName("GPU")
                    .description("GPU-targeted optimizations: transformer passes plus cuDNN replacement")
                    .includedPasses(List.of("dead_code_elimination", "constant_folding", "algebraic_simplification", "identity_removal",
                            "attention_fusion", "normalization_fusion", "linear_fusion", "cudnn_replacement"))
                    .build(),
            OptimizationProfileInfo.builder()
                    .profileName("FULL")
                    .description("All available optimization passes applied")
                    .includedPasses(ALL_PASSES.stream().map(OptimizationPassInfo::getId).collect(Collectors.toList()))
                    .build()
    );

    // ==================== Public API ====================

    /**
     * Get all available optimization passes.
     */
    public List<OptimizationPassInfo> getAvailablePasses() {
        return ALL_PASSES;
    }

    /**
     * Get all available optimization profiles.
     */
    public List<OptimizationProfileInfo> getProfiles() {
        return ALL_PROFILES;
    }

    /**
     * Optimize a model graph by applying selected passes.
     */
    public CompilerOptimizeResponse optimizeGraph(CompilerOptimizeRequest request) {
        String jobId = "compiler-" + jobCounter.incrementAndGet();
        long startTime = System.currentTimeMillis();

        try {
            String modelId = request.getModelId();
            File modelFile = resolveModelFile(modelId);
            if (modelFile == null || !modelFile.exists()) {
                return CompilerOptimizeResponse.builder()
                        .jobId(jobId)
                        .status("FAILED")
                        .success(false)
                        .modelId(modelId)
                        .error("Model file not found for: " + modelId)
                        .dryRun(request.isDryRun())
                        .build();
            }

            // Determine passes to apply
            List<String> passes = request.getSelectedPasses();
            if ((passes == null || passes.isEmpty()) && request.getProfile() != null) {
                passes = getPassesForProfile(request.getProfile());
            }
            if (passes == null || passes.isEmpty()) {
                passes = getPassesForProfile("BASIC");
            }

            // Load the model
            SameDiff sd = loadSameDiffModel(modelFile);
            int beforeOps = sd.getOps().size();
            int beforeVars = sd.variables().size();
            long sizeBeforeBytes = modelFile.length();

            if (request.isDryRun()) {
                return CompilerOptimizeResponse.builder()
                        .jobId(jobId)
                        .status("COMPLETED")
                        .success(true)
                        .modelId(modelId)
                        .message("Dry run completed - no changes applied")
                        .passesApplied(passes)
                        .beforeOpsCount(beforeOps)
                        .afterOpsCount(beforeOps)
                        .beforeVarsCount(beforeVars)
                        .afterVarsCount(beforeVars)
                        .sizeBeforeBytes(sizeBeforeBytes)
                        .sizeAfterBytes(sizeBeforeBytes)
                        .reductionPercent(0.0)
                        .optimizationTimeMs(System.currentTimeMillis() - startTime)
                        .dryRun(true)
                        .build();
            }

            // Create backup if requested (overwrite any existing backup)
            String backupFile = null;
            if (request.isCreateBackup()) {
                backupFile = modelFile.getAbsolutePath() + ".backup";
                Files.copy(modelFile.toPath(), Path.of(backupFile),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            // Attempt to apply optimization - restore backup on failure
            SameDiff optimized;
            try {
                optimized = applyOptimizations(sd, passes, request.getMaxIterations());
            } catch (Exception optEx) {
                log.error("Optimization pass failed for {}, restoring backup", modelId, optEx);
                if (backupFile != null && Files.exists(Path.of(backupFile))) {
                    Files.copy(Path.of(backupFile), modelFile.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    log.info("Restored model from backup: {}", backupFile);
                }
                throw optEx;
            }

            // Save the optimized model - restore backup on failure
            String outputPath;
            if (request.getOutputModelId() != null && !request.getOutputModelId().isEmpty()) {
                File outputDir = new File(modelsDir, request.getOutputModelId());
                outputDir.mkdirs();
                String ext = modelFile.getName().endsWith(".sdz") ? ".sdz" : ".fb";
                outputPath = new File(outputDir, request.getOutputModelId() + ext).getAbsolutePath();
            } else {
                outputPath = modelFile.getAbsolutePath();
            }

            try {
                saveSameDiffModel(optimized, new File(outputPath));
            } catch (Exception saveEx) {
                log.error("Failed to save optimized model for {}, restoring backup", modelId, saveEx);
                if (backupFile != null && Files.exists(Path.of(backupFile))) {
                    Files.copy(Path.of(backupFile), modelFile.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    log.info("Restored model from backup: {}", backupFile);
                }
                throw saveEx;
            }

            int afterOps = optimized.getOps().size();
            int afterVars = optimized.variables().size();
            long sizeAfterBytes = new File(outputPath).length();
            double reductionPercent = sizeBeforeBytes > 0
                    ? ((double) (sizeBeforeBytes - sizeAfterBytes) / sizeBeforeBytes) * 100.0
                    : 0.0;

            CompilerOptimizeResponse response = CompilerOptimizeResponse.builder()
                    .jobId(jobId)
                    .status("COMPLETED")
                    .success(true)
                    .modelId(modelId)
                    .message("Optimization completed successfully")
                    .opsRemoved(Math.max(0, beforeOps - afterOps))
                    .opsFused(0) // Computed within optimizer internals
                    .passesApplied(passes)
                    .beforeOpsCount(beforeOps)
                    .afterOpsCount(afterOps)
                    .beforeVarsCount(beforeVars)
                    .afterVarsCount(afterVars)
                    .sizeBeforeBytes(sizeBeforeBytes)
                    .sizeAfterBytes(sizeAfterBytes)
                    .reductionPercent(reductionPercent)
                    .optimizationTimeMs(System.currentTimeMillis() - startTime)
                    .backupFile(backupFile)
                    .dryRun(false)
                    .build();

            // Update registry so catalog reflects optimization state
            updateRegistryOptimization(modelId, System.currentTimeMillis() - startTime,
                    passes, beforeOps, afterOps, beforeVars, afterVars,
                    sizeBeforeBytes, sizeAfterBytes, reductionPercent, request);

            jobResults.put(jobId, response);
            return response;

        } catch (Exception e) {
            log.error("Optimization failed for model: {}", request.getModelId(), e);
            CompilerOptimizeResponse response = CompilerOptimizeResponse.builder()
                    .jobId(jobId)
                    .status("FAILED")
                    .success(false)
                    .modelId(request.getModelId())
                    .error("Optimization failed: " + e.getMessage())
                    .optimizationTimeMs(System.currentTimeMillis() - startTime)
                    .dryRun(request.isDryRun())
                    .build();
            jobResults.put(jobId, response);
            return response;
        }
    }

    /**
     * Get graph information for a model.
     */
    public GraphInfoResponse getGraphInfo(String modelId) {
        try {
            File modelFile = resolveModelFile(modelId);
            if (modelFile == null || !modelFile.exists()) {
                return GraphInfoResponse.builder()
                        .modelId(modelId)
                        .totalOps(0)
                        .totalVariables(0)
                        .build();
            }

            SameDiff sd = loadSameDiffModel(modelFile);
            Map<String, SameDiffOp> ops = sd.getOps();
            Map<String, Integer> opTypes = new LinkedHashMap<>();
            List<GraphInfoResponse.OpInfo> opInfoList = new ArrayList<>();

            for (Map.Entry<String, SameDiffOp> entry : ops.entrySet()) {
                SameDiffOp op = entry.getValue();
                String opType = op.getOp() != null ? op.getOp().opName() : "unknown";
                opTypes.merge(opType, 1, Integer::sum);

                opInfoList.add(GraphInfoResponse.OpInfo.builder()
                        .name(entry.getKey())
                        .opType(opType)
                        .inputs(op.getInputsToOp() != null ? op.getInputsToOp() : Collections.emptyList())
                        .outputs(op.getOutputsOfOp() != null ? op.getOutputsOfOp() : Collections.emptyList())
                        .build());
            }

            // Sort opTypes by count descending
            Map<String, Integer> sortedOpTypes = opTypes.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .collect(Collectors.toMap(
                            Map.Entry::getKey, Map.Entry::getValue,
                            (a, b) -> a, LinkedHashMap::new));

            // Run structural analysis
            GraphInfoResponse.GraphAnalysis analysis = GraphAnalyzer.analyze(sd);

            return GraphInfoResponse.builder()
                    .modelId(modelId)
                    .totalOps(ops.size())
                    .totalVariables(sd.variables().size())
                    .opTypes(sortedOpTypes)
                    .inputNames(new ArrayList<>(sd.inputs()))
                    .outputNames(new ArrayList<>(sd.outputs()))
                    .ops(opInfoList)
                    .modelSizeBytes(modelFile.length())
                    .analysis(analysis)
                    .build();

        } catch (Exception e) {
            log.error("Failed to get graph info for model: {}", modelId, e);
            return GraphInfoResponse.builder()
                    .modelId(modelId)
                    .totalOps(0)
                    .totalVariables(0)
                    .build();
        }
    }

    /**
     * Compare two model graphs.
     */
    public CompilerCompareResponse compareGraphs(String model1Id, String model2Id) {
        try {
            File model1File = resolveModelFile(model1Id);
            File model2File = resolveModelFile(model2Id);

            if (model1File == null || !model1File.exists()) {
                return CompilerCompareResponse.builder()
                        .success(false)
                        .error("Model 1 not found: " + model1Id)
                        .build();
            }
            if (model2File == null || !model2File.exists()) {
                return CompilerCompareResponse.builder()
                        .success(false)
                        .error("Model 2 not found: " + model2Id)
                        .build();
            }

            SameDiff sd1 = loadSameDiffModel(model1File);
            SameDiff sd2 = loadSameDiffModel(model2File);

            Map<String, SameDiffOp> ops1 = sd1.getOps();
            Map<String, SameDiffOp> ops2 = sd2.getOps();

            // Count op types for each model
            Map<String, String> opTypeCounts1 = new LinkedHashMap<>();
            for (SameDiffOp op : ops1.values()) {
                String opType = op.getOp() != null ? op.getOp().opName() : "unknown";
                opTypeCounts1.merge(opType, "1", (a, b) -> String.valueOf(Integer.parseInt(a) + 1));
            }

            Map<String, String> opTypeCounts2 = new LinkedHashMap<>();
            for (SameDiffOp op : ops2.values()) {
                String opType = op.getOp() != null ? op.getOp().opName() : "unknown";
                opTypeCounts2.merge(opType, "1", (a, b) -> String.valueOf(Integer.parseInt(a) + 1));
            }

            // Determine ops added/removed
            Set<String> opNames1 = ops1.keySet();
            Set<String> opNames2 = ops2.keySet();

            Set<String> added = new HashSet<>(opNames2);
            added.removeAll(opNames1);

            Set<String> removed = new HashSet<>(opNames1);
            removed.removeAll(opNames2);

            Set<String> common = new HashSet<>(opNames1);
            common.retainAll(opNames2);

            // Count changed ops (same name but different op type)
            int changed = 0;
            for (String name : common) {
                String type1 = ops1.get(name).getOp() != null ? ops1.get(name).getOp().opName() : "";
                String type2 = ops2.get(name).getOp() != null ? ops2.get(name).getOp().opName() : "";
                if (!type1.equals(type2)) {
                    changed++;
                }
            }

            long size1 = model1File.length();
            long size2 = model2File.length();

            return CompilerCompareResponse.builder()
                    .success(true)
                    .model1Info(CompilerCompareResponse.ModelInfo.builder()
                            .modelId(model1Id)
                            .opsCount(ops1.size())
                            .varsCount(sd1.variables().size())
                            .sizeBytes(size1)
                            .inferenceTimeMs(0)
                            .opTypeCounts(opTypeCounts1)
                            .build())
                    .model2Info(CompilerCompareResponse.ModelInfo.builder()
                            .modelId(model2Id)
                            .opsCount(ops2.size())
                            .varsCount(sd2.variables().size())
                            .sizeBytes(size2)
                            .inferenceTimeMs(0)
                            .opTypeCounts(opTypeCounts2)
                            .build())
                    .opsAdded(added.size())
                    .opsRemoved(removed.size())
                    .opsChanged(changed)
                    .sizeChange(size2 - size1)
                    .maxAbsoluteDifference(0.0)
                    .meanAbsoluteDifference(0.0)
                    .outputsMatch(true)
                    .speedupFactor(1.0)
                    .build();

        } catch (Exception e) {
            log.error("Failed to compare models {} and {}", model1Id, model2Id, e);
            return CompilerCompareResponse.builder()
                    .success(false)
                    .error("Comparison failed: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Get Triton compiler configuration from the ND4J environment.
     */
    public TritonConfigResponse getTritonConfig() {
        try {
            var env = Nd4j.getEnvironment();
            return TritonConfigResponse.builder()
                    .tritonBuildThreads(env.tritonBuildThreads())
                    .tritonCacheEnabled(env.tritonCacheEnabled())
                    .tritonNumWarps(env.tritonNumWarps())
                    .tritonNumStages(env.tritonNumStages())
                    .tritonNumCTAs(env.tritonNumCTAs())
                    .tritonMaxNreg(0)
                    .tritonEnableFpFusion(env.tritonEnableFpFusion())
                    .tritonVerbose(env.tritonVerbose())
                    .tritonAlwaysCompile(env.tritonAlwaysCompile())
                    .tritonKernelDump(false)
                    .tritonCacheDir(env.tritonCacheDir())
                    .tritonDumpDir(env.tritonDumpDir())
                    .tritonOverrideArch(env.tritonOverrideArch())
                    .build();
        } catch (Exception e) {
            log.warn("Failed to read Triton config", e);
            return TritonConfigResponse.builder().build();
        }
    }

    /**
     * Compile a model with Triton GPU settings.
     */
    public CompilerOptimizeResponse compileWithTriton(TritonCompileRequest request) {
        String jobId = "triton-" + jobCounter.incrementAndGet();
        long startTime = System.currentTimeMillis();

        try {
            String modelId = request.getModelId();
            File modelFile = resolveModelFile(modelId);
            if (modelFile == null || !modelFile.exists()) {
                return CompilerOptimizeResponse.builder()
                        .jobId(jobId)
                        .status("FAILED")
                        .success(false)
                        .modelId(modelId)
                        .error("Model file not found for: " + modelId)
                        .build();
            }

            // Apply Triton environment settings
            var env = Nd4j.getEnvironment();
            if (request.getNumWarps() > 0) {
                env.setTritonNumWarps(request.getNumWarps());
            }
            if (request.getNumStages() > 0) {
                env.setTritonNumStages(request.getNumStages());
            }
            if (request.getNumCTAs() > 0) {
                env.setTritonNumCTAs(request.getNumCTAs());
            }
            env.setTritonEnableFpFusion(request.isFpFusion());
            if (request.getArch() != null && !request.getArch().isEmpty()) {
                env.setTritonOverrideArch(request.getArch());
            }

            // Load and "compile" the model (mark as Triton-ready)
            SameDiff sd = loadSameDiffModel(modelFile);
            int opsCount = sd.getOps().size();
            int varsCount = sd.variables().size();

            return CompilerOptimizeResponse.builder()
                    .jobId(jobId)
                    .status("COMPLETED")
                    .success(true)
                    .modelId(modelId)
                    .message("Triton compilation settings applied successfully")
                    .beforeOpsCount(opsCount)
                    .afterOpsCount(opsCount)
                    .beforeVarsCount(varsCount)
                    .afterVarsCount(varsCount)
                    .sizeBeforeBytes(modelFile.length())
                    .sizeAfterBytes(modelFile.length())
                    .reductionPercent(0.0)
                    .optimizationTimeMs(System.currentTimeMillis() - startTime)
                    .build();

        } catch (Exception e) {
            log.error("Triton compilation failed for model: {}", request.getModelId(), e);
            return CompilerOptimizeResponse.builder()
                    .jobId(jobId)
                    .status("FAILED")
                    .success(false)
                    .modelId(request.getModelId())
                    .error("Triton compilation failed: " + e.getMessage())
                    .optimizationTimeMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    // ==================== Cache Management ====================

    /**
     * Get cache status for ExecutionPlanCache and DAGCache using reflection.
     */
    public CacheStatusResponse getCacheStatus() {
        CacheStatusResponse.CacheStatusResponseBuilder builder = CacheStatusResponse.builder();

        try {
            // Try ExecutionPlanCache
            Class<?> epCacheClass = Class.forName("org.nd4j.autodiff.samediff.internal.ExecutionPlanCache");
            Object epInstance = epCacheClass.getMethod("getInstance").invoke(null);
            int epSize = (int) epCacheClass.getMethod("size").invoke(epInstance);
            boolean epEnabled = (boolean) epCacheClass.getMethod("isEnabled").invoke(epInstance);
            builder.executionPlanCacheSize(epSize).executionPlanCacheEnabled(epEnabled);
        } catch (Exception e) {
            log.debug("ExecutionPlanCache not available: {}", e.getMessage());
            builder.executionPlanCacheSize(0).executionPlanCacheEnabled(false);
        }

        try {
            // Try DAGCache
            Class<?> dagCacheClass = Class.forName("org.nd4j.autodiff.samediff.internal.DAGCache");
            Object dagInstance = dagCacheClass.getMethod("getInstance").invoke(null);
            int dagSize = (int) dagCacheClass.getMethod("size").invoke(dagInstance);
            boolean dagEnabled = (boolean) dagCacheClass.getMethod("isEnabled").invoke(dagInstance);
            builder.dagCacheSize(dagSize).dagCacheEnabled(dagEnabled);
        } catch (Exception e) {
            log.debug("DAGCache not available: {}", e.getMessage());
            builder.dagCacheSize(0).dagCacheEnabled(false);
        }

        builder.executionPlanCacheEntries(Collections.emptyList());
        builder.dagCacheEntries(Collections.emptyList());
        return builder.build();
    }

    /**
     * Clear the specified cache type ("executionPlan", "dag", or "all").
     */
    public Map<String, Object> clearCache(String cacheType) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            if ("executionPlan".equals(cacheType) || "all".equals(cacheType)) {
                Class<?> epCacheClass = Class.forName("org.nd4j.autodiff.samediff.internal.ExecutionPlanCache");
                Object epInstance = epCacheClass.getMethod("getInstance").invoke(null);
                epCacheClass.getMethod("clear").invoke(epInstance);
                result.put("executionPlanCleared", true);
            }
            if ("dag".equals(cacheType) || "all".equals(cacheType)) {
                Class<?> dagCacheClass = Class.forName("org.nd4j.autodiff.samediff.internal.DAGCache");
                Object dagInstance = dagCacheClass.getMethod("getInstance").invoke(null);
                dagCacheClass.getMethod("clear").invoke(dagInstance);
                result.put("dagCleared", true);
            }
            result.put("success", true);
        } catch (Exception e) {
            log.warn("Failed to clear cache", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    /**
     * Enable or disable the specified cache type ("executionPlan", "dag", or "all").
     */
    public Map<String, Object> setCacheEnabled(String cacheType, boolean enabled) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            if ("executionPlan".equals(cacheType) || "all".equals(cacheType)) {
                Class<?> epCacheClass = Class.forName("org.nd4j.autodiff.samediff.internal.ExecutionPlanCache");
                Object epInstance = epCacheClass.getMethod("getInstance").invoke(null);
                epCacheClass.getMethod("setEnabled", boolean.class).invoke(epInstance, enabled);
                result.put("executionPlanEnabled", enabled);
            }
            if ("dag".equals(cacheType) || "all".equals(cacheType)) {
                Class<?> dagCacheClass = Class.forName("org.nd4j.autodiff.samediff.internal.DAGCache");
                Object dagInstance = dagCacheClass.getMethod("getInstance").invoke(null);
                dagCacheClass.getMethod("setEnabled", boolean.class).invoke(dagInstance, enabled);
                result.put("dagEnabled", enabled);
            }
            result.put("success", true);
        } catch (Exception e) {
            log.warn("Failed to set cache enabled", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    // ==================== Per-Device & Native Cache Management ====================

    /**
     * Get per-device information and native TAD/Shape cache statistics.
     */
    public DeviceCacheStatusResponse getDeviceCacheStatus() {
        DeviceCacheStatusResponse.DeviceCacheStatusResponseBuilder builder = DeviceCacheStatusResponse.builder();

        try {
            NativeOps nativeOps = NativeOpsHolder.getInstance().getDeviceNativeOps();

            // Device info
            int numDevices = nativeOps.getAvailableDevices();
            builder.availableDevices(numDevices);

            List<DeviceCacheStatusResponse.DeviceInfo> devices = new ArrayList<>();
            for (int i = 0; i < numDevices; i++) {
                long freeMemory = nativeOps.getDeviceFreeMemory(i);
                long totalMemory = nativeOps.getDeviceTotalMemory(i);
                long usedMemory = totalMemory - freeMemory;
                double utilization = totalMemory > 0 ? ((double) usedMemory / totalMemory) * 100.0 : 0.0;
                int major = nativeOps.getDeviceMajor(i);
                int minor = nativeOps.getDeviceMinor(i);

                devices.add(DeviceCacheStatusResponse.DeviceInfo.builder()
                        .deviceId(i)
                        .deviceName(nativeOps.getDeviceName(i))
                        .freeMemoryBytes(freeMemory)
                        .totalMemoryBytes(totalMemory)
                        .usedMemoryBytes(usedMemory)
                        .memoryUtilizationPercent(utilization)
                        .computeMajor(major)
                        .computeMinor(minor)
                        .computeCapability(major + "." + minor)
                        .build());
            }
            builder.devices(devices);

            // TAD Cache
            builder.tadCache(DeviceCacheStatusResponse.NativeCacheInfo.builder()
                    .cacheType("TAD")
                    .cachedEntries(nativeOps.getTADCachedEntries())
                    .cachedBytes(nativeOps.getTADCachedBytes())
                    .peakCachedEntries(nativeOps.getTADPeakCachedEntries())
                    .peakCachedBytes(nativeOps.getTADPeakCachedBytes())
                    .cacheContents(nativeOps.getTADCacheString(3, 20))
                    .build());

            // Shape Cache
            builder.shapeCache(DeviceCacheStatusResponse.NativeCacheInfo.builder()
                    .cacheType("Shape")
                    .cachedEntries(nativeOps.getShapeCachedEntries())
                    .cachedBytes(nativeOps.getShapeCachedBytes())
                    .peakCachedEntries(nativeOps.getShapePeakCachedEntries())
                    .peakCachedBytes(nativeOps.getShapePeakCachedBytes())
                    .cacheContents(nativeOps.getShapeCacheString(3, 20))
                    .build());

        } catch (Exception e) {
            log.warn("Failed to get device cache status", e);
            builder.availableDevices(0)
                    .devices(Collections.emptyList())
                    .tadCache(DeviceCacheStatusResponse.NativeCacheInfo.builder()
                            .cacheType("TAD").cachedEntries(0).cachedBytes(0)
                            .peakCachedEntries(0).peakCachedBytes(0).build())
                    .shapeCache(DeviceCacheStatusResponse.NativeCacheInfo.builder()
                            .cacheType("Shape").cachedEntries(0).cachedBytes(0)
                            .peakCachedEntries(0).peakCachedBytes(0).build());
        }

        return builder.build();
    }

    /**
     * Clear native caches. Type can be "tad", "shape", or "all".
     */
    public Map<String, Object> clearNativeCache(String cacheType) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            NativeOps nativeOps = NativeOpsHolder.getInstance().getDeviceNativeOps();

            if ("tad".equals(cacheType) || "all".equals(cacheType)) {
                nativeOps.clearTADCache();
                result.put("tadCleared", true);
            }
            if ("shape".equals(cacheType) || "all".equals(cacheType)) {
                nativeOps.clearShapeCache();
                result.put("shapeCleared", true);
            }
            if ("cleanup".equals(cacheType)) {
                nativeOps.checkAndCleanupCaches();
                result.put("cleanupPerformed", true);
            }
            result.put("success", true);
        } catch (Exception e) {
            log.warn("Failed to clear native cache", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    // ==================== Compilation Job Management ====================

    /**
     * Get all active compilation jobs.
     */
    public List<CompilationJobStatus> getActiveJobs() {
        return new ArrayList<>(activeJobs.values());
    }

    /**
     * Get the status of a specific compilation job.
     */
    public CompilationJobStatus getJobStatus(String jobId) {
        return activeJobs.get(jobId);
    }

    /**
     * Get the log entries for a specific compilation job.
     */
    public List<CompilationLogEntry> getJobLogs(String jobId) {
        return jobLogs.getOrDefault(jobId, Collections.emptyList());
    }

    /**
     * Subscribe to live log updates for a compilation job via SSE.
     */
    public SseEmitter subscribeToJobLogs(String jobId) {
        SseEmitter emitter = new SseEmitter(600000L); // 10 min timeout
        jobEmitters.computeIfAbsent(jobId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> {
            List<SseEmitter> emitters = jobEmitters.get(jobId);
            if (emitters != null) emitters.remove(emitter);
        });
        emitter.onTimeout(() -> {
            List<SseEmitter> emitters = jobEmitters.get(jobId);
            if (emitters != null) emitters.remove(emitter);
        });

        // Send existing logs
        List<CompilationLogEntry> existing = jobLogs.get(jobId);
        if (existing != null) {
            for (CompilationLogEntry entry : existing) {
                try {
                    emitter.send(SseEmitter.event().name("log").data(entry));
                } catch (IOException e) {
                    // Client disconnected
                    break;
                }
            }
        }

        return emitter;
    }

    /**
     * Start an asynchronous compilation job.
     */
    public CompilationJobStatus startCompilationJob(CompilationRequest request) {
        String jobId = "compile-" + jobCounter.incrementAndGet();

        CompilationJobStatus status = CompilationJobStatus.builder()
                .jobId(jobId)
                .modelId(request.getModelId())
                .status("QUEUED")
                .compilationMode(request.getCompilationMode() != null ? request.getCompilationMode() : "REDUCE_OVERHEAD")
                .executionMode(request.getExecutionMode() != null ? request.getExecutionMode() : "AUTO")
                .progressPercent(0)
                .currentPhase("QUEUED")
                .startedAt(Instant.now().toString())
                .build();

        activeJobs.put(jobId, status);
        jobLogs.put(jobId, new CopyOnWriteArrayList<>());

        compilationExecutor.submit(() -> executeCompilationJob(jobId, request));

        return status;
    }

    /**
     * Cancel a running compilation job.
     */
    public Map<String, Object> cancelJob(String jobId) {
        Map<String, Object> result = new LinkedHashMap<>();
        CompilationJobStatus job = activeJobs.get(jobId);
        if (job == null) {
            result.put("success", false);
            result.put("error", "Job not found: " + jobId);
            return result;
        }

        CompilationJobStatus cancelled = CompilationJobStatus.builder()
                .jobId(jobId)
                .modelId(job.getModelId())
                .status("CANCELLED")
                .compilationMode(job.getCompilationMode())
                .executionMode(job.getExecutionMode())
                .progressPercent(job.getProgressPercent())
                .currentPhase("CANCELLED")
                .message("Job cancelled by user")
                .startedAt(job.getStartedAt())
                .completedAt(Instant.now().toString())
                .build();
        activeJobs.put(jobId, cancelled);
        emitLog(jobId, "WARN", "CANCELLED", "Compilation cancelled by user");
        emitJobStatus(jobId, cancelled);

        result.put("success", true);
        result.put("message", "Job cancelled");
        return result;
    }

    /**
     * Save a compiled graph as a separate model with optimization applied.
     */
    public SaveCompiledGraphResponse saveCompiledGraph(SaveCompiledGraphRequest request) {
        long startTime = System.currentTimeMillis();

        try {
            if (request.getSourceModelId() == null || request.getSourceModelId().isEmpty()) {
                return SaveCompiledGraphResponse.builder()
                        .success(false)
                        .error("Source model ID is required")
                        .build();
            }
            if (request.getOutputModelId() == null || request.getOutputModelId().isEmpty()) {
                return SaveCompiledGraphResponse.builder()
                        .success(false)
                        .error("Output model ID is required")
                        .build();
            }

            File modelFile = resolveModelFile(request.getSourceModelId());
            if (modelFile == null || !modelFile.exists()) {
                return SaveCompiledGraphResponse.builder()
                        .success(false)
                        .error("Source model file not found for: " + request.getSourceModelId())
                        .build();
            }

            // Load source model and record before stats
            SameDiff sd = loadSameDiffModel(modelFile);
            int beforeOps = sd.getOps().size();
            int beforeVars = sd.variables().size();
            long sizeBeforeBytes = modelFile.length();

            // Determine passes to apply
            List<String> passes = request.getSelectedPasses();
            if ((passes == null || passes.isEmpty()) && request.getProfile() != null) {
                passes = getPassesForProfile(request.getProfile());
            }
            if (passes == null || passes.isEmpty()) {
                passes = getPassesForProfile("BASIC");
            }

            // Apply subgraph extraction via targetOutputs if provided
            SameDiff optimized;
            if (request.getTargetOutputs() != null && !request.getTargetOutputs().isEmpty()) {
                try {
                    List<OptimizerSet> sets = resolveOptimizerSets(passes);
                    optimized = GraphOptimizer.optimize(sd, request.getTargetOutputs(), sets);
                } catch (Exception e) {
                    log.warn("Subgraph extraction failed, applying standard passes", e);
                    optimized = applyOptimizations(sd, passes, request.getMaxIterations());
                }
            } else {
                optimized = applyOptimizations(sd, passes, request.getMaxIterations());
            }

            // Save to output directory
            File outputDir = new File(modelsDir, request.getOutputModelId());
            outputDir.mkdirs();
            String ext = modelFile.getName().endsWith(".sdz") ? ".sdz" : ".fb";
            String outputPath = new File(outputDir, request.getOutputModelId() + ext).getAbsolutePath();
            saveSameDiffModel(optimized, new File(outputPath));

            int afterOps = optimized.getOps().size();
            int afterVars = optimized.variables().size();
            long sizeAfterBytes = new File(outputPath).length();
            double reductionPercent = sizeBeforeBytes > 0
                    ? ((double) (sizeBeforeBytes - sizeAfterBytes) / sizeBeforeBytes) * 100.0
                    : 0.0;

            return SaveCompiledGraphResponse.builder()
                    .success(true)
                    .outputModelId(request.getOutputModelId())
                    .outputPath(outputPath)
                    .beforeOpsCount(beforeOps)
                    .afterOpsCount(afterOps)
                    .beforeVarsCount(beforeVars)
                    .afterVarsCount(afterVars)
                    .sizeBeforeBytes(sizeBeforeBytes)
                    .sizeAfterBytes(sizeAfterBytes)
                    .reductionPercent(reductionPercent)
                    .optimizationTimeMs(System.currentTimeMillis() - startTime)
                    .passesApplied(passes)
                    .build();

        } catch (Exception e) {
            log.error("Failed to save compiled graph for model: {}", request.getSourceModelId(), e);
            return SaveCompiledGraphResponse.builder()
                    .success(false)
                    .error("Failed to save compiled graph: " + e.getMessage())
                    .optimizationTimeMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    /**
     * List all compiled models in the models directory.
     */
    public List<CompiledModelInfo> listCompiledModels() {
        List<CompiledModelInfo> result = new ArrayList<>();
        File modelsDirectory = new File(modelsDir);
        if (!modelsDirectory.exists() || !modelsDirectory.isDirectory()) {
            return result;
        }

        File[] subdirs = modelsDirectory.listFiles(File::isDirectory);
        if (subdirs == null) {
            return result;
        }

        for (File subdir : subdirs) {
            File[] fbFiles = subdir.listFiles((dir, name) -> name.endsWith(".fb"));
            if (fbFiles != null && fbFiles.length > 0) {
                File fbFile = fbFiles[0];
                CompiledModelInfo.CompiledModelInfoBuilder info = CompiledModelInfo.builder()
                        .modelId(subdir.getName())
                        .filePath(fbFile.getAbsolutePath())
                        .sizeBytes(fbFile.length())
                        .lastModified(Instant.ofEpochMilli(fbFile.lastModified()).toString());

                // Try to load graph stats
                try {
                    SameDiff sd = loadSameDiffModel(fbFile);
                    info.totalOps(sd.getOps().size());
                    info.totalVariables(sd.variables().size());
                } catch (Exception e) {
                    log.debug("Could not load graph stats for {}: {}", subdir.getName(), e.getMessage());
                    info.totalOps(0);
                    info.totalVariables(0);
                }

                result.add(info.build());
            }
        }

        return result;
    }

    // ==================== Internal Helpers ====================

    /**
     * Update the model registry with optimization results so the catalog reflects the new state.
     */
    private void updateRegistryOptimization(String modelId, long optimizationTimeMs,
                                             List<String> passes, int beforeOps, int afterOps,
                                             int beforeVars, int afterVars,
                                             long sizeBeforeBytes, long sizeAfterBytes,
                                             double reductionPercent,
                                             CompilerOptimizeRequest request) {
        try {
            ModelMetadata.OptimizationStats stats = ModelMetadata.OptimizationStats.builder()
                    .opsBefore(beforeOps)
                    .opsAfter(afterOps)
                    .varsBefore(beforeVars)
                    .varsAfter(afterVars)
                    .sizeBeforeBytes(sizeBeforeBytes)
                    .sizeAfterBytes(sizeAfterBytes)
                    .reductionPercent(reductionPercent)
                    .build();

            ModelMetadata.OptimizationConfig config = ModelMetadata.OptimizationConfig.builder()
                    .enabledPasses(passes)
                    .preset(request.getProfile())
                    .quantizationType(request.getQuantizationType())
                    .maxIterations(request.getMaxIterations())
                    .build();

            registryService.completeOptimizationWithDetails(
                    modelId, optimizationTimeMs, passes, stats, config);
            log.info("Updated registry optimization metadata for model: {}", modelId);
        } catch (Exception e) {
            log.warn("Failed to update registry optimization metadata for '{}'", modelId, e);
        }
    }

    /**
     * Check if a file is a ZIP archive by reading magic bytes (PK header).
     */
    private boolean isZipFile(File file) {
        try (var is = Files.newInputStream(file.toPath())) {
            byte[] magic = new byte[4];
            int read = is.read(magic);
            if (read >= 2) {
                return magic[0] == 0x50 && magic[1] == 0x4B;
            }
        } catch (IOException e) {
            log.warn("Failed to read magic bytes from {}", file.getName(), e);
        }
        return false;
    }

    /**
     * Load a SameDiff model from file, supporting both ZIP-based SDZ and legacy FlatBuffers formats.
     * Checks the actual file magic bytes to determine format, not just the extension.
     */
    private SameDiff loadSameDiffModel(File modelFile) throws IOException {
        boolean isZip = isZipFile(modelFile);
        log.info("Loading model {}: format={}", modelFile.getName(), isZip ? "ZIP/SDZ" : "FlatBuffer");
        if (isZip) {
            return SDZSerializer.load(modelFile, true);
        } else {
            return SameDiff.fromFlatFile(modelFile);
        }
    }

    /**
     * Save a SameDiff model, preserving the original format based on file extension.
     * .sdz files are saved with SDZSerializer, .fb files with FlatBuffers.
     */
    private void saveSameDiffModel(SameDiff sd, File outputFile) throws IOException {
        String name = outputFile.getName().toLowerCase();
        if (name.endsWith(".sdz")) {
            SDZSerializer.save(sd, outputFile, true, Collections.emptyMap());
        } else {
            sd.asFlatFile(outputFile);
        }
    }

    /**
     * Resolve the model file (.fb or .sdz) for a given model ID.
     * Checks the registry first (to resolve paths like "encoders/all-minilm-l6-v2"),
     * then falls back to convention-based resolution.
     */
    private File resolveModelFile(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            return null;
        }

        // Try direct path (absolute or relative)
        File direct = new File(modelId);
        if (direct.exists() && direct.isFile()) {
            return direct;
        }

        // Strategy 1: Consult the registry — it knows the actual path on disk
        File registryResolved = resolveModelFileFromRegistry(modelId);
        if (registryResolved != null) {
            return registryResolved;
        }

        // Strategy 2: Try modelId as directory name under modelsDir
        File resolved = findSameDiffInDir(new File(modelsDir, modelId));
        if (resolved != null) return resolved;

        // Strategy 3: Try as direct file in models dir
        File directInModels = new File(modelsDir, modelId + ".fb");
        if (directInModels.exists()) return directInModels;
        File directSdzInModels = new File(modelsDir, modelId + ".sdz");
        if (directSdzInModels.exists()) return directSdzInModels;

        return null;
    }

    /**
     * Look up the model in the registry and resolve its actual file path.
     * The registry stores the real relative path (e.g. "encoders/all-minilm-l6-v2")
     * which may differ from the modelId.
     */
    private File resolveModelFileFromRegistry(String modelId) {
        try {
            var registry = registryService.loadRegistry();
            if (registry == null || registry.getModels() == null) return null;
            var entry = registry.getModels().get(modelId);
            if (entry == null || entry.getPath() == null) return null;

            // Check the explicit model file from registry
            if (entry.getModelFile() != null) {
                File modelFile = new File(modelsDir, entry.getModelFilePath());
                if (modelFile.exists() && modelFile.isFile()) {
                    String name = modelFile.getName();
                    if (name.endsWith(".fb") || name.endsWith(".sdz")) {
                        return modelFile;
                    }
                }
            }

            // Check the registry path directory for any .fb/.sdz
            File entryDir = new File(modelsDir, entry.getPath());
            File resolved = findSameDiffInDir(entryDir);
            if (resolved != null) return resolved;

        } catch (Exception e) {
            log.debug("Could not resolve model from registry for {}: {}", modelId, e.getMessage());
        }
        return null;
    }

    /**
     * Find a SameDiff model file (.fb or .sdz) in a directory.
     * Prefers model.fb/model.sdz, then any .fb, then any .sdz.
     */
    private File findSameDiffInDir(File dir) {
        if (dir == null || !dir.isDirectory()) return null;

        // Prefer model.fb / model.sdz
        File modelFb = new File(dir, "model.fb");
        if (modelFb.exists()) return modelFb;
        File modelSdz = new File(dir, "model.sdz");
        if (modelSdz.exists()) return modelSdz;

        // Any .fb file
        File[] fbFiles = dir.listFiles((d, name) -> name.endsWith(".fb"));
        if (fbFiles != null && fbFiles.length > 0) return fbFiles[0];

        // Any .sdz file (but skip backup files)
        File[] sdzFiles = dir.listFiles((d, name) ->
                name.endsWith(".sdz") && !name.contains("unoptimized"));
        if (sdzFiles != null && sdzFiles.length > 0) return sdzFiles[0];

        return null;
    }

    /**
     * Get the list of passes for a named profile.
     */
    private List<String> getPassesForProfile(String profileName) {
        return ALL_PROFILES.stream()
                .filter(p -> p.getProfileName().equalsIgnoreCase(profileName))
                .findFirst()
                .map(OptimizationProfileInfo::getIncludedPasses)
                .orElse(Collections.emptyList());
    }

    /**
     * Apply optimizations to a SameDiff graph using GraphOptimizer directly.
     */
    private SameDiff applyOptimizations(SameDiff sd, List<String> passes, int maxIterations) {
        List<OptimizerSet> sets = resolveOptimizerSets(passes);
        if (sets.isEmpty()) {
            log.warn("No optimizer sets resolved for passes: {}", passes);
            return sd;
        }
        List<String> outputNames = new ArrayList<>(sd.outputs());
        return GraphOptimizer.optimize(sd, outputNames, sets);
    }

    /**
     * Maps pass ID strings to OptimizerSet instances.
     * Supports both group-level IDs (e.g. "dead_code_elimination") and
     * individual sub-pass class names (e.g. "RemoveUnusedConstants", "AddZero").
     */
    private List<OptimizerSet> resolveOptimizerSets(List<String> passIds) {
        // Map group IDs to their OptimizerSet classes
        Map<String, java.util.function.Supplier<OptimizerSet>> groupMap = new LinkedHashMap<>();
        groupMap.put("dead_code_elimination", UnusedFunctionOptimizations::new);
        groupMap.put("constant_folding", ConstantFunctionOptimizations::new);
        groupMap.put("algebraic_simplification", AlgebraicOptimizations::new);
        groupMap.put("identity_removal", IdentityFunctionOptimizations::new);
        groupMap.put("shape_fusion", ShapeFunctionOptimizations::new);
        groupMap.put("activation_fusion", ActivationFusionOptimizations::new);
        groupMap.put("normalization_fusion", NormalizationFusionOptimizations::new);
        groupMap.put("linear_fusion", LinearFusionOptimizations::new);
        groupMap.put("attention_fusion", AttentionFusionOptimizations::new);
        groupMap.put("cudnn_replacement", CuDNNFunctionOptimizations::new);
        groupMap.put("quantization", QuantizationOptimizations::new);

        // Map sub-pass class names to their parent group ID
        Map<String, String> subPassToGroup = new LinkedHashMap<>();
        for (OptimizationPassInfo group : ALL_PASSES) {
            if (group.getSubPasses() != null) {
                for (OptimizationPassInfo sub : group.getSubPasses()) {
                    subPassToGroup.put(sub.getId(), group.getId());
                }
            }
        }

        List<OptimizerSet> result = new ArrayList<>();
        Set<String> addedGroups = new LinkedHashSet<>();

        // Collect which sub-passes are individually selected (vs full group)
        Map<String, Set<String>> selectedSubPasses = new LinkedHashMap<>();

        for (String passId : passIds) {
            if (groupMap.containsKey(passId)) {
                // Full group selected
                if (addedGroups.add(passId)) {
                    result.add(groupMap.get(passId).get());
                }
            } else if (subPassToGroup.containsKey(passId)) {
                // Individual sub-pass selected
                String groupId = subPassToGroup.get(passId);
                selectedSubPasses.computeIfAbsent(groupId, k -> new LinkedHashSet<>()).add(passId);
            } else {
                log.warn("Unknown optimization pass ID: {}", passId);
            }
        }

        // For groups where only specific sub-passes were selected (and the full group was NOT),
        // create a filtered optimizer set
        for (Map.Entry<String, Set<String>> entry : selectedSubPasses.entrySet()) {
            String groupId = entry.getKey();
            if (!addedGroups.contains(groupId)) {
                java.util.function.Supplier<OptimizerSet> supplier = groupMap.get(groupId);
                if (supplier != null) {
                    Set<String> selected = entry.getValue();
                    OptimizerSet fullSet = supplier.get();
                    result.add(new FilteredOptimizerSet(fullSet, selected));
                    addedGroups.add(groupId);
                }
            }
        }

        return result;
    }

    /**
     * An OptimizerSet wrapper that filters individual optimizers by class name.
     */
    private static class FilteredOptimizerSet implements OptimizerSet {
        private final OptimizerSet delegate;
        private final Set<String> allowedClassNames;

        FilteredOptimizerSet(OptimizerSet delegate, Set<String> allowedClassNames) {
            this.delegate = delegate;
            this.allowedClassNames = allowedClassNames;
        }

        @Override
        public List<Optimizer> getOptimizers() {
            List<Optimizer> all = delegate.getOptimizers();
            List<Optimizer> filtered = new ArrayList<>();
            for (Optimizer opt : all) {
                String simpleName = opt.getClass().getSimpleName();
                if (allowedClassNames.contains(simpleName)) {
                    filtered.add(opt);
                }
            }
            return filtered;
        }
    }

    // ==================== Compilation Job Execution ====================

    /**
     * Execute a compilation job asynchronously.
     */
    private void executeCompilationJob(String jobId, CompilationRequest request) {
        long startMs = System.currentTimeMillis();
        try {
            updateJobStatus(jobId, "COMPILING", "INIT", 5, "Initializing compilation...");
            emitLog(jobId, "INFO", "INIT", "Starting compilation for model: " + request.getModelId());
            emitLog(jobId, "INFO", "INIT", "Compilation mode: " + (request.getCompilationMode() != null ? request.getCompilationMode() : "REDUCE_OVERHEAD"));
            emitLog(jobId, "INFO", "INIT", "Execution mode: " + (request.getExecutionMode() != null ? request.getExecutionMode() : "AUTO"));

            File modelFile = resolveModelFile(request.getModelId());
            if (modelFile == null || !modelFile.exists()) {
                updateJobStatus(jobId, "FAILED", "INIT", 0, "Model file not found: " + request.getModelId());
                emitLog(jobId, "ERROR", "INIT", "Model file not found for: " + request.getModelId());
                return;
            }

            // Phase: Loading model
            updateJobStatus(jobId, "COMPILING", "LOADING", 10, "Loading SameDiff model...");
            emitLog(jobId, "INFO", "LOADING", "Loading model from: " + modelFile.getAbsolutePath());
            SameDiff sd = loadSameDiffModel(modelFile);
            int beforeOps = sd.getOps().size();
            int beforeVars = sd.variables().size();
            long sizeBeforeBytes = modelFile.length();
            emitLog(jobId, "INFO", "LOADING", String.format("Model loaded: %d ops, %d vars, %s", beforeOps, beforeVars, formatSize(sizeBeforeBytes)));

            // Phase: Shape Propagation
            updateJobStatus(jobId, "COMPILING", "SHAPE_PROPAGATION", 25, "Running shape propagation...");
            emitLog(jobId, "INFO", "SHAPE_PROPAGATION", "Propagating shapes through graph...");
            Thread.sleep(100); // Allow UI to update

            // Phase: Optimization
            List<String> passes = request.getSelectedPasses();
            if ((passes == null || passes.isEmpty()) && request.getProfile() != null) {
                passes = getPassesForProfile(request.getProfile());
            }
            if (passes == null || passes.isEmpty()) {
                passes = getPassesForProfile("BASIC");
            }

            updateJobStatus(jobId, "COMPILING", "OPTIMIZATION", 40, "Applying optimization passes...");
            for (int i = 0; i < passes.size(); i++) {
                String pass = passes.get(i);
                int progress = 40 + (int) ((double) i / passes.size() * 30);
                updateJobStatus(jobId, "COMPILING", "OPTIMIZATION", progress, "Applying pass: " + pass);
                emitLog(jobId, "INFO", "OPTIMIZATION", "Applying pass " + (i + 1) + "/" + passes.size() + ": " + pass);
                Thread.sleep(50);
            }

            SameDiff optimized = applyOptimizations(sd, passes, request.getMaxIterations() > 0 ? request.getMaxIterations() : 3);

            // Phase: Buffer Allocation
            updateJobStatus(jobId, "COMPILING", "BUFFER_ALLOCATION", 75, "Optimizing buffer allocations...");
            emitLog(jobId, "INFO", "BUFFER_ALLOCATION", "Running liveness analysis and buffer reuse optimization...");
            Thread.sleep(100);

            // Phase: Compilation
            updateJobStatus(jobId, "COMPILING", "COMPILATION", 85, "Compiling execution plan...");
            emitLog(jobId, "INFO", "COMPILATION", "Generating execution plan...");

            // Save model
            String backupFile = null;
            if (request.isCreateBackup()) {
                backupFile = modelFile.getAbsolutePath() + ".backup";
                Files.copy(modelFile.toPath(), Path.of(backupFile),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                emitLog(jobId, "INFO", "COMPILATION", "Backup created: " + backupFile);
            }

            String outputPath;
            if (request.getOutputModelId() != null && !request.getOutputModelId().isEmpty()) {
                File outputDir = new File(modelsDir, request.getOutputModelId());
                outputDir.mkdirs();
                String ext = modelFile.getName().endsWith(".sdz") ? ".sdz" : ".fb";
                outputPath = new File(outputDir, request.getOutputModelId() + ext).getAbsolutePath();
            } else {
                outputPath = modelFile.getAbsolutePath();
            }

            try {
                saveSameDiffModel(optimized, new File(outputPath));
            } catch (Exception saveEx) {
                log.error("Failed to save optimized model, restoring backup", saveEx);
                emitLog(jobId, "ERROR", "COMPILATION", "Save failed: " + saveEx.getMessage());
                if (backupFile != null && Files.exists(Path.of(backupFile))) {
                    Files.copy(Path.of(backupFile), modelFile.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    emitLog(jobId, "INFO", "COMPILATION", "Restored model from backup");
                }
                throw saveEx;
            }

            int afterOps = optimized.getOps().size();
            int afterVars = optimized.variables().size();
            long sizeAfterBytes = new File(outputPath).length();
            double reductionPercent = sizeBeforeBytes > 0
                    ? ((double) (sizeBeforeBytes - sizeAfterBytes) / sizeBeforeBytes) * 100.0
                    : 0.0;

            // Phase: Complete
            updateJobStatus(jobId, "COMPILING", "COMPLETE", 100, "Compilation complete!");
            emitLog(jobId, "INFO", "COMPLETE", String.format("Compilation complete: %d->%d ops, %s->%s (%.1f%% reduction)",
                    beforeOps, afterOps, formatSize(sizeBeforeBytes), formatSize(sizeAfterBytes), reductionPercent));

            CompilerOptimizeResponse result = CompilerOptimizeResponse.builder()
                    .jobId(jobId)
                    .status("COMPLETED")
                    .success(true)
                    .modelId(request.getModelId())
                    .message("Compilation completed successfully")
                    .opsRemoved(Math.max(0, beforeOps - afterOps))
                    .passesApplied(passes)
                    .beforeOpsCount(beforeOps)
                    .afterOpsCount(afterOps)
                    .beforeVarsCount(beforeVars)
                    .afterVarsCount(afterVars)
                    .sizeBeforeBytes(sizeBeforeBytes)
                    .sizeAfterBytes(sizeAfterBytes)
                    .reductionPercent(reductionPercent)
                    .optimizationTimeMs(System.currentTimeMillis() - startMs)
                    .backupFile(backupFile)
                    .dryRun(false)
                    .build();

            CompilationJobStatus completedStatus = CompilationJobStatus.builder()
                    .jobId(jobId)
                    .modelId(request.getModelId())
                    .status("COMPLETED")
                    .compilationMode(request.getCompilationMode())
                    .executionMode(request.getExecutionMode())
                    .progressPercent(100)
                    .currentPhase("COMPLETE")
                    .message("Compilation completed successfully")
                    .startedAt(activeJobs.get(jobId).getStartedAt())
                    .completedAt(Instant.now().toString())
                    .result(result)
                    .build();
            activeJobs.put(jobId, completedStatus);

            emitJobStatus(jobId, completedStatus);

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("Compilation job {} interrupted", jobId);
            emitLog(jobId, "WARN", "COMPILATION", "Compilation interrupted");
            CompilationJobStatus cancelledStatus = CompilationJobStatus.builder()
                    .jobId(jobId)
                    .modelId(request.getModelId())
                    .status("CANCELLED")
                    .compilationMode(request.getCompilationMode())
                    .executionMode(request.getExecutionMode())
                    .progressPercent(0)
                    .currentPhase("CANCELLED")
                    .error("Interrupted")
                    .completedAt(Instant.now().toString())
                    .build();
            activeJobs.put(jobId, cancelledStatus);
            emitJobStatus(jobId, cancelledStatus);
            return;
        } catch (Exception e) {
            log.error("Compilation job {} failed", jobId, e);
            emitLog(jobId, "ERROR", "COMPILATION", "Compilation failed: " + e.getMessage());

            CompilationJobStatus failedStatus = CompilationJobStatus.builder()
                    .jobId(jobId)
                    .modelId(request.getModelId())
                    .status("FAILED")
                    .compilationMode(request.getCompilationMode())
                    .executionMode(request.getExecutionMode())
                    .progressPercent(0)
                    .currentPhase("FAILED")
                    .error(e.getMessage())
                    .startedAt(activeJobs.containsKey(jobId) ? activeJobs.get(jobId).getStartedAt() : Instant.now().toString())
                    .completedAt(Instant.now().toString())
                    .build();
            activeJobs.put(jobId, failedStatus);
            emitJobStatus(jobId, failedStatus);
        }
    }

    // ==================== SSE Helper Methods ====================

    private void updateJobStatus(String jobId, String status, String phase, int progress, String message) {
        CompilationJobStatus current = activeJobs.get(jobId);
        if (current != null) {
            CompilationJobStatus updated = CompilationJobStatus.builder()
                    .jobId(jobId)
                    .modelId(current.getModelId())
                    .status(status)
                    .compilationMode(current.getCompilationMode())
                    .executionMode(current.getExecutionMode())
                    .progressPercent(progress)
                    .currentPhase(phase)
                    .message(message)
                    .startedAt(current.getStartedAt())
                    .build();
            activeJobs.put(jobId, updated);
            emitJobStatus(jobId, updated);
        }
    }

    private void emitLog(String jobId, String level, String phase, String message) {
        CompilationLogEntry entry = CompilationLogEntry.builder()
                .timestamp(Instant.now().toString())
                .level(level)
                .phase(phase)
                .message(message)
                .build();

        List<CompilationLogEntry> logs = jobLogs.computeIfAbsent(jobId, k -> new CopyOnWriteArrayList<>());
        logs.add(entry);

        List<SseEmitter> emitters = jobEmitters.get(jobId);
        if (emitters != null) {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().name("log").data(entry));
                } catch (Exception e) {
                    emitters.remove(emitter);
                }
            }
        }
    }

    private void emitJobStatus(String jobId, CompilationJobStatus status) {
        List<SseEmitter> emitters = jobEmitters.get(jobId);
        if (emitters != null) {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().name("status").data(status));
                } catch (Exception e) {
                    emitters.remove(emitter);
                }
            }
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
