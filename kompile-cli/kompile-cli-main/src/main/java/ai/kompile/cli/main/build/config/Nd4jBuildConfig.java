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

package ai.kompile.cli.main.build.config;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Configuration for building ND4J/libnd4j backends.
 * Maps to the CMake and Maven build options available in the deeplearning4j build system.
 */
@Data
@Builder
public class Nd4jBuildConfig {

    // =============================================
    // Backend Selection
    // =============================================

    /**
     * Backend type: cpu, cuda, tpu, zluda
     */
    @Builder.Default
    private Backend backend = Backend.CPU;

    /**
     * Maven profile to use: aurora, minimial-cpu, cpu, cuda, tpu, zluda, zluda-amd, zluda-intel
     */
    private String mavenProfile;

    /**
     * ZLUDA target for AMD or Intel GPUs (only when backend=ZLUDA)
     */
    private ZludaTarget zludaTarget;

    // =============================================
    // ZLUDA-Specific Options
    // =============================================

    /**
     * ROCm version for AMD ZLUDA target (e.g., "6.0", "6.1")
     */
    private String rocmVersion;

    /**
     * HIP version for AMD ZLUDA target (e.g., "6.0")
     */
    private String hipVersion;

    /**
     * MIOpen version for AMD deep learning acceleration (e.g., "3.1")
     */
    private String miopenVersion;

    /**
     * AMD GPU architecture targets (e.g., "gfx900;gfx1030;gfx1100").
     * Semicolon-separated list of GCN/RDNA/CDNA ISA targets.
     * Common values:
     *   gfx900  - Vega (MI25, Radeon VII)
     *   gfx906  - Vega 20 (MI50/MI60)
     *   gfx908  - CDNA (MI100)
     *   gfx90a  - CDNA2 (MI210/MI250)
     *   gfx942  - CDNA3 (MI300)
     *   gfx1030 - RDNA2 (RX 6800/6900)
     *   gfx1100 - RDNA3 (RX 7900)
     *   gfx1150 - RDNA3.5 (RX 9070)
     *   gfx1200 - RDNA4 (RX 9060)
     */
    private String amdGpuTargets;

    /**
     * Intel oneAPI version for Intel ZLUDA target (e.g., "2024.1")
     */
    private String oneApiVersion;

    /**
     * Path to ZLUDA installation directory.
     * ZLUDA translates CUDA API calls to run on AMD/Intel GPUs.
     */
    private String zludaPath;

    // =============================================
    // Type Profiles (controls which data types are compiled)
    // =============================================

    /**
     * Type profile preset for reduced compilation time.
     * MINIMAL_INDEXING: float32, double, int32, int64 (fastest, 4 types)
     * ESSENTIAL: adds int8, int16 (6 types)
     * FLOATS_ONLY: float32, double, float16
     * INTEGERS_ONLY: all integer types
     * SINGLE_PRECISION: float32, int32, int64
     * DOUBLE_PRECISION: double, int32, int64
     * QUANTIZATION: int8, uint8, float, int32 (inference focus)
     * TRAINING: float32, float16, bfloat16, int32, int64, double
     * INFERENCE: int8, uint8, float16, float32, int32 (deployment focus)
     * DATA_PIPELINE: all 14 essential types (default optimized)
     * STANDARD_ALL_TYPES: all 14 types with semantic filtering
     */
    private TypeProfile typeProfile;

    /**
     * Custom data types list (overrides typeProfile if set).
     * Valid types: bool, int8, uint8, int16, uint16, int32, uint32, int64, uint64,
     *              float16, bfloat16, float32, double, utf8, utf16, utf32
     */
    private List<String> dataTypes;

    // =============================================
    // Helper Libraries (can enable multiple simultaneously)
    // =============================================

    /**
     * Enable OneDNN (Intel MKLDNN) for x86/x64 CPU optimization
     */
    @Builder.Default
    private boolean helperOnednn = false;

    /**
     * Enable cuDNN for NVIDIA CUDA GPU optimization
     */
    @Builder.Default
    private boolean helperCudnn = false;

    /**
     * Enable ARM Compute Library for ARM64 platforms
     */
    @Builder.Default
    private boolean helperArmcompute = false;

    /**
     * Enable Metal Performance Shaders for macOS/iOS
     */
    @Builder.Default
    private boolean helperMps = false;

    /**
     * Enable Apple Accelerate framework for macOS/iOS BLAS
     */
    @Builder.Default
    private boolean helperAccelerate = false;

    /**
     * Enable MLIR/LLVM for JIT compilation
     */
    @Builder.Default
    private boolean helperMlir = false;

    /**
     * MLIR version to use (e.g., 18)
     */
    private String mlirVersion;

    /**
     * Enable PJRT for TPU support
     */
    @Builder.Default
    private boolean helperPjrt = false;

    /**
     * Enable MIOpen for AMD GPUs (via ZLUDA)
     */
    @Builder.Default
    private boolean helperMiopen = false;

    /**
     * Enable VLM for Vision-Language Models
     */
    @Builder.Default
    private boolean helperVlm = false;

    /**
     * Comma-separated list of helpers to enable (alternative to individual flags)
     */
    private String helpersList;

    /**
     * Helper priority order for dynamic kernel selection (e.g., "cudnn;onednn;cpu")
     */
    private String helperPriority;

    // =============================================
    // Dynamic Kernel Selection
    // =============================================

    /**
     * Enable runtime kernel selection (choose best implementation dynamically)
     */
    @Builder.Default
    private boolean dynamicKernelSelection = true;

    /**
     * Kernel selection strategy: fastest, first, roundrobin, memory, power
     */
    @Builder.Default
    private KernelStrategy kernelStrategy = KernelStrategy.FASTEST;

    /**
     * Enable runtime auto-tuning of kernels
     */
    @Builder.Default
    private boolean kernelAutotuning = false;

    /**
     * Enable caching of kernel selection decisions
     */
    @Builder.Default
    private boolean kernelCaching = true;

    // =============================================
    // Build Type and Optimization
    // =============================================

    /**
     * CMake build type: Release, RelWithDebInfo, Debug
     */
    @Builder.Default
    private BuildType buildType = BuildType.RELEASE;

    /**
     * Optimization level (0-3, default 3 for Release)
     */
    @Builder.Default
    private int optimizationLevel = 3;

    /**
     * Enable Link Time Optimization
     */
    @Builder.Default
    private boolean useLto = false;

    /**
     * Enable SSE/AVX vectorization checks
     */
    @Builder.Default
    private boolean checkVectorization = false;

    /**
     * Optimize for build machine (may not work on other machines)
     */
    @Builder.Default
    private boolean nativeOptimization = false;

    // =============================================
    // Platform and Architecture
    // =============================================

    /**
     * Target platform (e.g., linux-x86_64, macosx-arm64, windows-x86_64)
     */
    private String platform;

    /**
     * CPU extension (e.g., avx2, avx512)
     */
    private String extension;

    /**
     * Target architecture (e.g., native, x86_64, aarch64)
     */
    private String arch;

    // =============================================
    // CUDA-Specific Options
    // =============================================

    /**
     * CUDA version (e.g., 12.3)
     */
    private String cudaVersion;

    /**
     * cuDNN version (e.g., 8.9)
     */
    private String cudnnVersion;

    /**
     * GPU compute capabilities (e.g., "80,86,89" for sm_80, sm_86, sm_89)
     */
    private String computeCapability;

    // =============================================
    // TPU-Specific Options
    // =============================================

    /**
     * TPU version (e.g., v4, v5)
     */
    private String tpuVersion;

    // =============================================
    // Template and Compilation Control
    // =============================================

    /**
     * Enable semantic filtering to reduce template combinations
     */
    @Builder.Default
    private boolean semanticFiltering = true;

    /**
     * Enable aggressive semantic filtering (removes more invalid type combos)
     */
    @Builder.Default
    private boolean aggressiveSemanticFiltering = false;

    /**
     * Maximum template combinations (safety limit)
     */
    @Builder.Default
    private int maxTemplateCombinations = 1000;

    /**
     * Number of parallel compilation jobs
     */
    @Builder.Default
    private int parallelCompileJobs = Runtime.getRuntime().availableProcessors();

    /**
     * Extract instantiations for analysis
     */
    @Builder.Default
    private boolean extractInstantiations = false;

    /**
     * Auto-generate missing instantiation fix files
     */
    @Builder.Default
    private boolean generateFixFiles = false;

    // =============================================
    // Operations Selection (for minimal builds)
    // =============================================

    /**
     * Semicolon-separated list of operations to include.
     * Leave empty for all operations.
     */
    private String operations;

    /**
     * Exclude specific operations (semicolon-separated)
     */
    private String excludeOperations;

    // =============================================
    // Output Configuration
    // =============================================

    /**
     * Output directory for built artifacts
     */
    @Builder.Default
    private String outputDirectory = "./nd4j-build";

    /**
     * Skip tests during build
     */
    @Builder.Default
    private boolean skipTests = true;

    /**
     * Skip javadoc generation
     */
    @Builder.Default
    private boolean skipJavadoc = true;

    /**
     * Install to local Maven repository
     */
    @Builder.Default
    private boolean installToLocalRepo = true;

    // =============================================
    // Enums
    // =============================================

    public enum Backend {
        CPU,
        CUDA,
        TPU,
        ZLUDA
    }

    public enum ZludaTarget {
        AMD,
        INTEL
    }

    public enum TypeProfile {
        MINIMAL_INDEXING,
        ESSENTIAL,
        FLOATS_ONLY,
        INTEGERS_ONLY,
        SINGLE_PRECISION,
        DOUBLE_PRECISION,
        QUANTIZATION,
        TRAINING,
        INFERENCE,
        DATA_PIPELINE,
        STANDARD_ALL_TYPES
    }

    public enum KernelStrategy {
        FASTEST,
        FIRST,
        ROUNDROBIN,
        MEMORY,
        POWER
    }

    public enum BuildType {
        RELEASE,
        REL_WITH_DEB_INFO,
        DEBUG
    }

    // =============================================
    // Helper Methods
    // =============================================

    /**
     * Get the Maven profile name based on configuration
     */
    public String getEffectiveMavenProfile() {
        if (mavenProfile != null && !mavenProfile.isEmpty()) {
            return mavenProfile;
        }

        switch (backend) {
            case CUDA:
                return "cuda";
            case TPU:
                return "tpu";
            case ZLUDA:
                if (zludaTarget == ZludaTarget.AMD) {
                    return "zluda-amd";
                } else if (zludaTarget == ZludaTarget.INTEL) {
                    return "zluda-intel";
                }
                return "zluda";
            case CPU:
            default:
                // Check if minimal build is desired
                if (typeProfile == TypeProfile.MINIMAL_INDEXING) {
                    return "minimial-cpu";
                }
                return "cpu";
        }
    }

    /**
     * Get CMake build type string
     */
    public String getCmakeBuildType() {
        switch (buildType) {
            case REL_WITH_DEB_INFO:
                return "RelWithDebInfo";
            case DEBUG:
                return "Debug";
            case RELEASE:
            default:
                return "Release";
        }
    }

    /**
     * Build the helpers list string for CMake
     */
    public String getEffectiveHelpersList() {
        if (helpersList != null && !helpersList.isEmpty()) {
            return helpersList;
        }

        StringBuilder sb = new StringBuilder();
        if (helperOnednn) append(sb, "onednn");
        if (helperCudnn) append(sb, "cudnn");
        if (helperArmcompute) append(sb, "armcompute");
        if (helperMps) append(sb, "mps");
        if (helperAccelerate) append(sb, "accelerate");
        if (helperMlir) append(sb, "mlir");
        if (helperPjrt) append(sb, "pjrt");
        if (helperMiopen) append(sb, "miopen");
        if (helperVlm) append(sb, "vlm");

        return sb.toString();
    }

    private void append(StringBuilder sb, String value) {
        if (sb.length() > 0) {
            sb.append(";");
        }
        sb.append(value);
    }

    /**
     * Get data types string for CMake/Maven
     */
    public String getEffectiveDataTypes() {
        if (dataTypes != null && !dataTypes.isEmpty()) {
            return String.join(";", dataTypes);
        }

        if (typeProfile != null) {
            switch (typeProfile) {
                case MINIMAL_INDEXING:
                    return "float32;double;int32;int64";
                case ESSENTIAL:
                    return "float32;double;int32;int64;int8;int16";
                case FLOATS_ONLY:
                    return "float32;double;float16";
                case INTEGERS_ONLY:
                    return "int8;uint8;int16;uint16;int32;uint32;int64;uint64";
                case SINGLE_PRECISION:
                    return "float32;int32;int64";
                case DOUBLE_PRECISION:
                    return "double;int32;int64";
                case QUANTIZATION:
                    return "int8;uint8;float32;int32";
                case TRAINING:
                    return "float32;float16;bfloat16;int32;int64;double";
                case INFERENCE:
                    return "int8;uint8;float16;float32;int32";
                case DATA_PIPELINE:
                case STANDARD_ALL_TYPES:
                default:
                    return "bool;int8;uint8;int16;uint16;int32;uint32;int64;uint64;float16;bfloat16;float32;double";
            }
        }

        return null; // Use default (all types)
    }

    /**
     * Create a minimal inference-focused configuration
     */
    public static Nd4jBuildConfig minimalInference() {
        return Nd4jBuildConfig.builder()
                .backend(Backend.CPU)
                .typeProfile(TypeProfile.INFERENCE)
                .helperOnednn(true)
                .dynamicKernelSelection(true)
                .semanticFiltering(true)
                .aggressiveSemanticFiltering(true)
                .useLto(true)
                .build();
    }

    /**
     * Create a training-focused configuration
     */
    public static Nd4jBuildConfig training() {
        return Nd4jBuildConfig.builder()
                .backend(Backend.CPU)
                .typeProfile(TypeProfile.TRAINING)
                .helperOnednn(true)
                .dynamicKernelSelection(true)
                .semanticFiltering(true)
                .build();
    }

    /**
     * Create a CUDA training configuration
     */
    public static Nd4jBuildConfig cudaTraining(String cudaVersion, String computeCapability) {
        return Nd4jBuildConfig.builder()
                .backend(Backend.CUDA)
                .typeProfile(TypeProfile.TRAINING)
                .helperCudnn(true)
                .cudaVersion(cudaVersion)
                .computeCapability(computeCapability)
                .dynamicKernelSelection(true)
                .semanticFiltering(true)
                .build();
    }

    /**
     * Create a minimal CPU configuration for fastest build
     */
    public static Nd4jBuildConfig minimalCpu() {
        return Nd4jBuildConfig.builder()
                .backend(Backend.CPU)
                .mavenProfile("minimial-cpu")
                .typeProfile(TypeProfile.MINIMAL_INDEXING)
                .semanticFiltering(true)
                .aggressiveSemanticFiltering(true)
                .build();
    }

    /**
     * Create a ZLUDA AMD training configuration.
     * ZLUDA translates CUDA calls to HIP/ROCm for AMD GPUs.
     *
     * @param rocmVersion ROCm version (e.g., "6.1")
     * @param amdGpuTargets semicolon-separated GCN/RDNA/CDNA targets (e.g., "gfx1030;gfx1100")
     */
    public static Nd4jBuildConfig zludaAmdTraining(String rocmVersion, String amdGpuTargets) {
        return Nd4jBuildConfig.builder()
                .backend(Backend.ZLUDA)
                .zludaTarget(ZludaTarget.AMD)
                .typeProfile(TypeProfile.TRAINING)
                .helperMiopen(true)
                .rocmVersion(rocmVersion)
                .amdGpuTargets(amdGpuTargets)
                .dynamicKernelSelection(true)
                .semanticFiltering(true)
                .build();
    }

    /**
     * Create a ZLUDA AMD inference configuration.
     *
     * @param rocmVersion ROCm version (e.g., "6.1")
     * @param amdGpuTargets semicolon-separated GCN/RDNA/CDNA targets (e.g., "gfx1030;gfx1100")
     */
    public static Nd4jBuildConfig zludaAmdInference(String rocmVersion, String amdGpuTargets) {
        return Nd4jBuildConfig.builder()
                .backend(Backend.ZLUDA)
                .zludaTarget(ZludaTarget.AMD)
                .typeProfile(TypeProfile.INFERENCE)
                .helperMiopen(true)
                .rocmVersion(rocmVersion)
                .amdGpuTargets(amdGpuTargets)
                .dynamicKernelSelection(true)
                .semanticFiltering(true)
                .aggressiveSemanticFiltering(true)
                .useLto(true)
                .build();
    }
}
