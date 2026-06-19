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

package ai.kompile.cli.main.build;

import ai.kompile.cli.main.Info;
import ai.kompile.cli.main.build.config.Nd4jBuildConfig;
import ai.kompile.cli.main.build.config.Nd4jBuildConfig.*;
import ai.kompile.cli.common.util.EnvironmentUtils;
import ai.kompile.cli.main.util.OSResolver;
import org.apache.maven.shared.invoker.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.File;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * CLI command for building custom ND4J/libnd4j backends with fine-grained control
 * over data types, operations, helpers, and optimization options.
 *
 * This enables creating minimal, optimized builds for specific deployment scenarios:
 * - Inference-only builds with reduced binary size
 * - Training builds with full type support
 * - Platform-specific optimizations (CUDA, ARM, etc.)
 * - GraalVM native image compatible builds
 */
@Command(
    name = "build-nd4j-backend",
    mixinStandardHelpOptions = true,
    description = "Build a custom ND4J/libnd4j backend with selected data types, operations, and optimizations.\n\n" +
            "Examples:\n" +
            "  # Minimal CPU build for inference (fastest compilation)\n" +
            "  kompile build nd4j-backend --preset=minimal-inference\n\n" +
            "  # CUDA build with cuDNN for training\n" +
            "  kompile build nd4j-backend --backend=cuda --cuda-version=12.3 --helper-cudnn\n\n" +
            "  # Custom type profile with OneDNN\n" +
            "  kompile build nd4j-backend --type-profile=TRAINING --helper-onednn --use-lto"
)
public class BuildNd4jBackend implements Callable<Integer> {

    // =============================================
    // Preset Options
    // =============================================

    @Option(names = {"--preset"}, description = "Use a preset configuration:\n" +
            "  minimal-inference: Minimal types for inference (int8, float16, float32)\n" +
            "  minimal-cpu: Fastest build with minimal types\n" +
            "  training: Full training support with common types\n" +
            "  cuda-training: CUDA with cuDNN for training\n" +
            "  zluda-amd-training: AMD GPU via ZLUDA with MIOpen for training\n" +
            "  zluda-amd-inference: AMD GPU via ZLUDA with MIOpen for inference\n" +
            "  full: All types and operations")
    private String preset;

    // =============================================
    // Backend Selection
    // =============================================

    @Option(names = {"--backend"}, description = "Backend type: CPU (default), CUDA, TPU, ZLUDA")
    private Backend backend = Backend.CPU;

    @Option(names = {"--maven-profile"}, description = "Maven profile to use (overrides --backend):\n" +
            "  aurora, minimial-cpu, cpu, cuda, tpu, zluda, zluda-amd, zluda-intel")
    private String mavenProfile;

    @Option(names = {"--zluda-target"}, description = "ZLUDA target: AMD or INTEL (only with --backend=ZLUDA)")
    private ZludaTarget zludaTarget;

    // =============================================
    // ZLUDA-Specific Options
    // =============================================

    @Option(names = {"--rocm-version"}, description = "ROCm version for AMD ZLUDA target (e.g., 6.1)")
    private String rocmVersion;

    @Option(names = {"--hip-version"}, description = "HIP version for AMD ZLUDA target (e.g., 6.0)")
    private String hipVersion;

    @Option(names = {"--miopen-version"}, description = "MIOpen version for AMD deep learning acceleration (e.g., 3.1)")
    private String miopenVersion;

    @Option(names = {"--amd-gpu-targets"}, description = "AMD GPU architecture targets (semicolon-separated):\n" +
            "  gfx900  - Vega (MI25, Radeon VII)\n" +
            "  gfx906  - Vega 20 (MI50/MI60)\n" +
            "  gfx908  - CDNA (MI100)\n" +
            "  gfx90a  - CDNA2 (MI210/MI250)\n" +
            "  gfx942  - CDNA3 (MI300)\n" +
            "  gfx1030 - RDNA2 (RX 6800/6900)\n" +
            "  gfx1100 - RDNA3 (RX 7900)\n" +
            "  gfx1150 - RDNA3.5 (RX 9070)\n" +
            "  gfx1200 - RDNA4 (RX 9060)")
    private String amdGpuTargets;

    @Option(names = {"--oneapi-version"}, description = "Intel oneAPI version for Intel ZLUDA target (e.g., 2024.1)")
    private String oneApiVersion;

    @Option(names = {"--zluda-path"}, description = "Path to ZLUDA installation directory")
    private String zludaPath;

    // =============================================
    // Type Profiles
    // =============================================

    @Option(names = {"--type-profile"}, description = "Type profile for compilation:\n" +
            "  MINIMAL_INDEXING: float32, double, int32, int64 (fastest)\n" +
            "  ESSENTIAL: adds int8, int16\n" +
            "  FLOATS_ONLY: float32, double, float16\n" +
            "  QUANTIZATION: int8, uint8, float32, int32 (inference focus)\n" +
            "  TRAINING: float32, float16, bfloat16, int32, int64, double\n" +
            "  INFERENCE: int8, uint8, float16, float32, int32\n" +
            "  STANDARD_ALL_TYPES: all types")
    private TypeProfile typeProfile;

    @Option(names = {"--data-types"}, split = ";", description = "Custom data types (semicolon-separated):\n" +
            "  bool, int8, uint8, int16, uint16, int32, uint32, int64, uint64,\n" +
            "  float16, bfloat16, float32, double")
    private List<String> dataTypes;

    // =============================================
    // Helper Libraries
    // =============================================

    @Option(names = {"--helper-onednn"}, description = "Enable OneDNN (Intel MKLDNN) for CPU optimization")
    private boolean helperOnednn = false;

    @Option(names = {"--helper-cudnn"}, description = "Enable cuDNN for NVIDIA GPU optimization")
    private boolean helperCudnn = false;

    @Option(names = {"--helper-armcompute"}, description = "Enable ARM Compute Library for ARM64")
    private boolean helperArmcompute = false;

    @Option(names = {"--helper-mps"}, description = "Enable Metal Performance Shaders (macOS)")
    private boolean helperMps = false;

    @Option(names = {"--helper-accelerate"}, description = "Enable Apple Accelerate framework (macOS)")
    private boolean helperAccelerate = false;

    @Option(names = {"--helper-mlir"}, description = "Enable MLIR/LLVM for JIT compilation")
    private boolean helperMlir = false;

    @Option(names = {"--mlir-version"}, description = "MLIR version (e.g., 18)")
    private String mlirVersion;

    @Option(names = {"--helper-pjrt"}, description = "Enable PJRT for TPU support")
    private boolean helperPjrt = false;

    @Option(names = {"--helper-miopen"}, description = "Enable MIOpen for AMD GPUs (ZLUDA)")
    private boolean helperMiopen = false;

    @Option(names = {"--helper-vlm"}, description = "Enable VLM for Vision-Language Models")
    private boolean helperVlm = false;

    @Option(names = {"--helpers"}, description = "Comma-separated list of helpers to enable")
    private String helpersList;

    @Option(names = {"--helper-priority"}, description = "Helper priority order (e.g., \"cudnn;onednn;cpu\")")
    private String helperPriority;

    // =============================================
    // Dynamic Kernel Selection
    // =============================================

    @Option(names = {"--dynamic-kernel-selection"}, negatable = true,
            description = "Enable runtime kernel selection (default: true)")
    private boolean dynamicKernelSelection = true;

    @Option(names = {"--kernel-strategy"}, description = "Kernel selection strategy: FASTEST, FIRST, ROUNDROBIN, MEMORY, POWER")
    private KernelStrategy kernelStrategy = KernelStrategy.FASTEST;

    @Option(names = {"--kernel-autotuning"}, description = "Enable runtime auto-tuning of kernels")
    private boolean kernelAutotuning = false;

    @Option(names = {"--kernel-caching"}, negatable = true,
            description = "Enable caching of kernel selection decisions (default: true)")
    private boolean kernelCaching = true;

    // =============================================
    // Build Type and Optimization
    // =============================================

    @Option(names = {"--build-type"}, description = "CMake build type: RELEASE, REL_WITH_DEB_INFO, DEBUG")
    private BuildType buildType = BuildType.RELEASE;

    @Option(names = {"--optimization-level"}, description = "Optimization level 0-3 (default: 3)")
    private int optimizationLevel = 3;

    @Option(names = {"--use-lto"}, description = "Enable Link Time Optimization")
    private boolean useLto = false;

    @Option(names = {"--check-vectorization"}, description = "Enable SSE/AVX vectorization checks")
    private boolean checkVectorization = false;

    @Option(names = {"--native-optimization"}, description = "Optimize for build machine (may not be portable)")
    private boolean nativeOptimization = false;

    // =============================================
    // Platform and Architecture
    // =============================================

    @Option(names = {"--platform"}, description = "Target platform (e.g., linux-x86_64, macosx-arm64)")
    private String platform;

    @Option(names = {"--extension"}, description = "CPU extension (e.g., avx2, avx512)")
    private String extension;

    @Option(names = {"--arch"}, description = "Target architecture (e.g., native, x86_64, aarch64)")
    private String arch;

    // =============================================
    // CUDA-Specific Options
    // =============================================

    @Option(names = {"--cuda-version"}, description = "CUDA version (e.g., 12.3)")
    private String cudaVersion;

    @Option(names = {"--cudnn-version"}, description = "cuDNN version (e.g., 8.9)")
    private String cudnnVersion;

    @Option(names = {"--compute-capability"}, description = "GPU compute capabilities (e.g., \"80,86,89\")")
    private String computeCapability;

    // =============================================
    // TPU-Specific Options
    // =============================================

    @Option(names = {"--tpu-version"}, description = "TPU version (e.g., v4, v5)")
    private String tpuVersion;

    // =============================================
    // Template and Compilation Control
    // =============================================

    @Option(names = {"--semantic-filtering"}, negatable = true,
            description = "Enable semantic filtering to reduce template combinations (default: true)")
    private boolean semanticFiltering = true;

    @Option(names = {"--aggressive-semantic-filtering"},
            description = "Enable aggressive semantic filtering")
    private boolean aggressiveSemanticFiltering = false;

    @Option(names = {"--max-template-combinations"}, description = "Maximum template combinations (default: 1000)")
    private int maxTemplateCombinations = 1000;

    @Option(names = {"--parallel-jobs", "-j"}, description = "Number of parallel compilation jobs")
    private int parallelCompileJobs = Runtime.getRuntime().availableProcessors();

    // =============================================
    // Operations Selection
    // =============================================

    @Option(names = {"--operations"}, description = "Semicolon-separated list of operations to include")
    private String operations;

    @Option(names = {"--exclude-operations"}, description = "Semicolon-separated list of operations to exclude")
    private String excludeOperations;

    // =============================================
    // Output and Build Configuration
    // =============================================

    @Option(names = {"--output-dir", "-o"}, description = "Output directory for built artifacts")
    private String outputDirectory = "./nd4j-build";

    @Option(names = {"--skip-tests"}, negatable = true, description = "Skip tests during build (default: true)")
    private boolean skipTests = true;

    @Option(names = {"--skip-javadoc"}, negatable = true, description = "Skip javadoc generation (default: true)")
    private boolean skipJavadoc = true;

    @Option(names = {"--install"}, negatable = true, description = "Install to local Maven repository (default: true)")
    private boolean installToLocalRepo = true;

    @Option(names = {"--maven-home"}, description = "Maven home directory")
    private File mavenHome = EnvironmentUtils.defaultMavenHome();

    @Option(names = {"--dl4j-source"}, description = "Path to local DL4J source directory")
    private File dl4jSourceDir;

    @Option(names = {"--dry-run"}, description = "Print build configuration without executing")
    private boolean dryRun = false;

    @Option(names = {"--verbose", "-v"}, description = "Verbose output")
    private boolean verbose = false;

    @Spec
    private CommandSpec spec;

    private PrintWriter out() {
        return spec.commandLine().getOut();
    }

    private PrintWriter err() {
        return spec.commandLine().getErr();
    }

    @Override
    public Integer call() throws Exception {
        // Apply preset if specified
        Nd4jBuildConfig config = buildConfigFromOptions();

        if (preset != null) {
            config = applyPreset(preset, config);
        }

        // Print configuration
        printConfiguration(config);

        if (dryRun) {
            out().println("\n[DRY RUN] Build not executed. Use without --dry-run to build.");
            return 0;
        }

        // Execute the build
        return executeBuild(config);
    }

    private Nd4jBuildConfig buildConfigFromOptions() {
        return Nd4jBuildConfig.builder()
                .backend(backend)
                .mavenProfile(mavenProfile)
                .zludaTarget(zludaTarget)
                .rocmVersion(rocmVersion)
                .hipVersion(hipVersion)
                .miopenVersion(miopenVersion)
                .amdGpuTargets(amdGpuTargets)
                .oneApiVersion(oneApiVersion)
                .zludaPath(zludaPath)
                .typeProfile(typeProfile)
                .dataTypes(dataTypes)
                .helperOnednn(helperOnednn)
                .helperCudnn(helperCudnn)
                .helperArmcompute(helperArmcompute)
                .helperMps(helperMps)
                .helperAccelerate(helperAccelerate)
                .helperMlir(helperMlir)
                .mlirVersion(mlirVersion)
                .helperPjrt(helperPjrt)
                .helperMiopen(helperMiopen)
                .helperVlm(helperVlm)
                .helpersList(helpersList)
                .helperPriority(helperPriority)
                .dynamicKernelSelection(dynamicKernelSelection)
                .kernelStrategy(kernelStrategy)
                .kernelAutotuning(kernelAutotuning)
                .kernelCaching(kernelCaching)
                .buildType(buildType)
                .optimizationLevel(optimizationLevel)
                .useLto(useLto)
                .checkVectorization(checkVectorization)
                .nativeOptimization(nativeOptimization)
                .platform(platform != null ? platform : OSResolver.os() + "-" + OSResolver.arch())
                .extension(extension)
                .arch(arch)
                .cudaVersion(cudaVersion)
                .cudnnVersion(cudnnVersion)
                .computeCapability(computeCapability)
                .tpuVersion(tpuVersion)
                .semanticFiltering(semanticFiltering)
                .aggressiveSemanticFiltering(aggressiveSemanticFiltering)
                .maxTemplateCombinations(maxTemplateCombinations)
                .parallelCompileJobs(parallelCompileJobs)
                .operations(operations)
                .excludeOperations(excludeOperations)
                .outputDirectory(outputDirectory)
                .skipTests(skipTests)
                .skipJavadoc(skipJavadoc)
                .installToLocalRepo(installToLocalRepo)
                .build();
    }

    private Nd4jBuildConfig applyPreset(String presetName, Nd4jBuildConfig baseConfig) {
        switch (presetName.toLowerCase().replace("_", "-")) {
            case "minimal-inference":
                out().println("Applying preset: minimal-inference");
                return Nd4jBuildConfig.minimalInference();

            case "minimal-cpu":
                out().println("Applying preset: minimal-cpu");
                return Nd4jBuildConfig.minimalCpu();

            case "training":
                out().println("Applying preset: training");
                return Nd4jBuildConfig.training();

            case "cuda-training":
                out().println("Applying preset: cuda-training");
                String cv = cudaVersion != null ? cudaVersion : "12.3";
                String cc = computeCapability != null ? computeCapability : "80,86,89";
                return Nd4jBuildConfig.cudaTraining(cv, cc);

            case "zluda-amd-training":
                out().println("Applying preset: zluda-amd-training");
                String rv = rocmVersion != null ? rocmVersion : "6.1";
                String gt = amdGpuTargets != null ? amdGpuTargets : "gfx1030;gfx1100";
                return Nd4jBuildConfig.zludaAmdTraining(rv, gt);

            case "zluda-amd-inference":
                out().println("Applying preset: zluda-amd-inference");
                String rvi = rocmVersion != null ? rocmVersion : "6.1";
                String gti = amdGpuTargets != null ? amdGpuTargets : "gfx1030;gfx1100";
                return Nd4jBuildConfig.zludaAmdInference(rvi, gti);

            case "full":
                out().println("Applying preset: full (all types and operations)");
                return Nd4jBuildConfig.builder()
                        .backend(backend)
                        .typeProfile(TypeProfile.STANDARD_ALL_TYPES)
                        .helperOnednn(true)
                        .dynamicKernelSelection(true)
                        .platform(platform != null ? platform : OSResolver.os() + "-" + OSResolver.arch())
                        .build();

            default:
                out().println("Unknown preset: " + presetName + ". Using specified options.");
                return baseConfig;
        }
    }

    private void printConfiguration(Nd4jBuildConfig config) {
        PrintWriter pw = out();
        pw.println("\n========================================");
        pw.println("ND4J Backend Build Configuration");
        pw.println("========================================");

        pw.println("\nBackend:");
        pw.println("  Type: " + config.getBackend());
        pw.println("  Maven Profile: " + config.getEffectiveMavenProfile());
        pw.println("  Platform: " + config.getPlatform());
        if (config.getExtension() != null) {
            pw.println("  Extension: " + config.getExtension());
        }

        pw.println("\nData Types:");
        if (config.getTypeProfile() != null) {
            pw.println("  Profile: " + config.getTypeProfile());
        }
        String effectiveTypes = config.getEffectiveDataTypes();
        if (effectiveTypes != null) {
            pw.println("  Types: " + effectiveTypes);
            int typeCount = effectiveTypes.split(";").length;
            pw.println("  Count: " + typeCount + " types");
            // Estimate template combinations
            int twoTypeCombos = typeCount * typeCount;
            int threeTypeCombos = typeCount * typeCount * typeCount;
            pw.println("  Estimated 2-type combinations: ~" + twoTypeCombos);
            pw.println("  Estimated 3-type combinations: ~" + threeTypeCombos);
        }

        String helpers = config.getEffectiveHelpersList();
        if (helpers != null && !helpers.isEmpty()) {
            pw.println("\nHelper Libraries: " + helpers);
            if (config.getHelperPriority() != null) {
                pw.println("  Priority: " + config.getHelperPriority());
            }
        }

        pw.println("\nKernel Selection:");
        pw.println("  Dynamic Selection: " + config.isDynamicKernelSelection());
        pw.println("  Strategy: " + config.getKernelStrategy());
        pw.println("  Auto-tuning: " + config.isKernelAutotuning());
        pw.println("  Caching: " + config.isKernelCaching());

        pw.println("\nBuild Options:");
        pw.println("  Build Type: " + config.getCmakeBuildType());
        pw.println("  Optimization Level: -O" + config.getOptimizationLevel());
        pw.println("  LTO: " + config.isUseLto());
        pw.println("  Semantic Filtering: " + config.isSemanticFiltering());
        pw.println("  Aggressive Filtering: " + config.isAggressiveSemanticFiltering());
        pw.println("  Parallel Jobs: " + config.getParallelCompileJobs());

        if (config.getOperations() != null) {
            pw.println("\nOperations:");
            pw.println("  Include: " + config.getOperations());
        }
        if (config.getExcludeOperations() != null) {
            pw.println("  Exclude: " + config.getExcludeOperations());
        }

        if (config.getBackend() == Backend.CUDA) {
            pw.println("\nCUDA Settings:");
            if (config.getCudaVersion() != null) pw.println("  CUDA Version: " + config.getCudaVersion());
            if (config.getCudnnVersion() != null) pw.println("  cuDNN Version: " + config.getCudnnVersion());
            if (config.getComputeCapability() != null) pw.println("  Compute Capability: " + config.getComputeCapability());
        }

        if (config.getBackend() == Backend.ZLUDA) {
            pw.println("\nZLUDA Settings:");
            if (config.getZludaTarget() != null) pw.println("  Target: " + config.getZludaTarget());
            if (config.getZludaPath() != null) pw.println("  ZLUDA Path: " + config.getZludaPath());
            if (config.getZludaTarget() == ZludaTarget.AMD) {
                if (config.getRocmVersion() != null) pw.println("  ROCm Version: " + config.getRocmVersion());
                if (config.getHipVersion() != null) pw.println("  HIP Version: " + config.getHipVersion());
                if (config.getMiopenVersion() != null) pw.println("  MIOpen Version: " + config.getMiopenVersion());
                if (config.getAmdGpuTargets() != null) pw.println("  GPU Targets: " + config.getAmdGpuTargets());
            }
            if (config.getZludaTarget() == ZludaTarget.INTEL) {
                if (config.getOneApiVersion() != null) pw.println("  oneAPI Version: " + config.getOneApiVersion());
            }
        }

        if (config.getBackend() == Backend.TPU && config.getTpuVersion() != null) {
            pw.println("\nTPU Settings:");
            pw.println("  TPU Version: " + config.getTpuVersion());
        }

        pw.println("\nOutput:");
        pw.println("  Directory: " + config.getOutputDirectory());
        pw.println("  Install to Local Repo: " + config.isInstallToLocalRepo());
        pw.println("========================================\n");
    }

    private int executeBuild(Nd4jBuildConfig config) throws MavenInvocationException {
        File sourceDir;
        if (dl4jSourceDir != null && dl4jSourceDir.exists()) {
            sourceDir = dl4jSourceDir;
            System.out.println("Building from local source: " + sourceDir.getAbsolutePath());
        } else {
            // Clone or use cached DL4J source
            System.out.println("Note: For custom builds, specify --dl4j-source pointing to a local DL4J checkout.");
            System.out.println("Example: git clone https://github.com/deeplearning4j/deeplearning4j.git");
            System.err.println("Error: --dl4j-source is required for building a custom backend.");
            return 1;
        }

        // Build Maven invocation
        InvocationRequest request = new DefaultInvocationRequest();
        request.setBaseDirectory(sourceDir);
        request.setPomFile(new File(sourceDir, "pom.xml"));

        // Set goals
        List<String> goals = new ArrayList<>();
        goals.add("clean");
        if (config.isInstallToLocalRepo()) {
            goals.add("install");
        } else {
            goals.add("package");
        }
        request.setGoals(goals);

        // Set profiles
        List<String> profiles = new ArrayList<>();
        profiles.add(config.getEffectiveMavenProfile());
        request.setProfiles(profiles);

        // Set properties
        Properties props = buildMavenProperties(config);
        request.setProperties(props);

        // Set up invoker
        Invoker invoker = new DefaultInvoker();
        invoker.setMavenHome(mavenHome);
        invoker.setWorkingDirectory(sourceDir);

        if (verbose) {
            invoker.setLogger(new SystemOutLogger());
            invoker.setOutputHandler(line -> System.out.println("[Maven] " + line));
        }
        invoker.setErrorHandler(line -> System.err.println("[Maven ERROR] " + line));

        System.out.println("Executing Maven build...");
        System.out.println("  Goals: " + goals);
        System.out.println("  Profiles: " + profiles);
        if (verbose) {
            System.out.println("  Properties: " + props);
        }

        InvocationResult result = invoker.execute(request);

        if (result.getExitCode() != 0) {
            System.err.println("Build failed with exit code: " + result.getExitCode());
            if (result.getExecutionException() != null) {
                result.getExecutionException().printStackTrace();
            }
            return result.getExitCode();
        }

        System.out.println("\nBuild completed successfully!");
        System.out.println("Artifacts available in: " + new File(sourceDir, "nd4j/nd4j-backends/nd4j-backend-impls").getAbsolutePath());

        return 0;
    }

    private Properties buildMavenProperties(Nd4jBuildConfig config) {
        Properties props = new Properties();

        // Platform
        if (config.getPlatform() != null) {
            props.setProperty("javacpp.platform", config.getPlatform());
            props.setProperty("libnd4j.platform", config.getPlatform());
        }

        // Extension
        if (config.getExtension() != null) {
            props.setProperty("javacpp.platform.extension", config.getExtension());
            props.setProperty("libnd4j.extension", config.getExtension());
        }

        // Data types
        String dataTypes = config.getEffectiveDataTypes();
        if (dataTypes != null) {
            props.setProperty("libnd4j.datatypes", dataTypes);
        }

        // Type profile
        if (config.getTypeProfile() != null) {
            props.setProperty("SD_TYPE_PROFILE", config.getTypeProfile().name());
        }

        // Backend-specific flags
        switch (config.getBackend()) {
            case CPU:
                props.setProperty("SD_CPU", "ON");
                break;
            case CUDA:
                props.setProperty("SD_CUDA", "ON");
                if (config.getCudaVersion() != null) {
                    props.setProperty("CUDA_VERSION", config.getCudaVersion());
                }
                if (config.getCudnnVersion() != null) {
                    props.setProperty("CUDNN_VERSION", config.getCudnnVersion());
                }
                if (config.getComputeCapability() != null) {
                    props.setProperty("SD_COMPUTE_CAPABILITY", config.getComputeCapability());
                }
                break;
            case TPU:
                props.setProperty("SD_TPU", "ON");
                if (config.getTpuVersion() != null) {
                    props.setProperty("TPU_VERSION", config.getTpuVersion());
                }
                break;
            case ZLUDA:
                props.setProperty("SD_ZLUDA", "ON");
                if (config.getZludaTarget() != null) {
                    props.setProperty("SD_ZLUDA_TARGET", config.getZludaTarget().name());
                }
                if (config.getZludaPath() != null) {
                    props.setProperty("SD_ZLUDA_PATH", config.getZludaPath());
                }
                if (config.getRocmVersion() != null) {
                    props.setProperty("ROCM_VERSION", config.getRocmVersion());
                }
                if (config.getHipVersion() != null) {
                    props.setProperty("HIP_VERSION", config.getHipVersion());
                }
                if (config.getMiopenVersion() != null) {
                    props.setProperty("MIOPEN_VERSION", config.getMiopenVersion());
                }
                if (config.getAmdGpuTargets() != null) {
                    props.setProperty("SD_AMD_GPU_TARGETS", config.getAmdGpuTargets());
                }
                if (config.getOneApiVersion() != null) {
                    props.setProperty("ONEAPI_VERSION", config.getOneApiVersion());
                }
                break;
        }

        // Helpers
        String helpers = config.getEffectiveHelpersList();
        if (helpers != null && !helpers.isEmpty()) {
            props.setProperty("libnd4j.helpers", helpers.replace(";", ","));
            props.setProperty("HELPERS_LIST", helpers);
        }

        // Individual helper flags
        if (config.isHelperOnednn()) props.setProperty("HELPERS_onednn", "ON");
        if (config.isHelperCudnn()) props.setProperty("HELPERS_cudnn", "ON");
        if (config.isHelperArmcompute()) props.setProperty("HELPERS_armcompute", "ON");
        if (config.isHelperMps()) props.setProperty("HELPERS_mps", "ON");
        if (config.isHelperAccelerate()) props.setProperty("HELPERS_accelerate", "ON");
        if (config.isHelperMlir()) {
            props.setProperty("HELPERS_mlir", "ON");
            if (config.getMlirVersion() != null) {
                props.setProperty("MLIR_VERSION", config.getMlirVersion());
            }
        }
        if (config.isHelperPjrt()) props.setProperty("HELPERS_pjrt", "ON");
        if (config.isHelperMiopen()) props.setProperty("HELPERS_miopen", "ON");
        if (config.isHelperVlm()) props.setProperty("HELPERS_vlm", "ON");

        // Kernel selection
        if (config.getHelperPriority() != null) {
            props.setProperty("SD_HELPER_PRIORITY", config.getHelperPriority());
        }
        props.setProperty("SD_DYNAMIC_KERNEL_SELECTION", config.isDynamicKernelSelection() ? "ON" : "OFF");
        props.setProperty("SD_KERNEL_STRATEGY", config.getKernelStrategy().name().toLowerCase());
        props.setProperty("SD_KERNEL_AUTOTUNING", config.isKernelAutotuning() ? "ON" : "OFF");
        props.setProperty("SD_KERNEL_CACHING", config.isKernelCaching() ? "ON" : "OFF");

        // Build type and optimization
        props.setProperty("CMAKE_BUILD_TYPE", config.getCmakeBuildType());
        props.setProperty("SD_OPTIMIZATION_LEVEL", String.valueOf(config.getOptimizationLevel()));
        props.setProperty("SD_USE_LTO", config.isUseLto() ? "ON" : "OFF");
        props.setProperty("libnd4j.lto", config.isUseLto() ? "ON" : "OFF");
        props.setProperty("SD_CHECK_VECTORIZATION", config.isCheckVectorization() ? "ON" : "OFF");
        props.setProperty("SD_NATIVE", config.isNativeOptimization() ? "ON" : "OFF");

        // Template control
        props.setProperty("SD_ENABLE_SEMANTIC_FILTERING", config.isSemanticFiltering() ? "ON" : "OFF");
        props.setProperty("SD_AGGRESSIVE_SEMANTIC_FILTERING", config.isAggressiveSemanticFiltering() ? "ON" : "OFF");
        props.setProperty("SD_MAX_TEMPLATE_COMBINATIONS", String.valueOf(config.getMaxTemplateCombinations()));
        props.setProperty("SD_PARALLEL_COMPILE_JOBS", String.valueOf(config.getParallelCompileJobs()));
        props.setProperty("libnd4j.buildthreads", String.valueOf(config.getParallelCompileJobs()));

        // Operations
        if (config.getOperations() != null) {
            props.setProperty("libnd4j.operations", config.getOperations());
        }

        // Skip flags
        props.setProperty("skipTests", String.valueOf(config.isSkipTests()));
        props.setProperty("maven.test.skip", String.valueOf(config.isSkipTests()));
        props.setProperty("maven.javadoc.skip", String.valueOf(config.isSkipJavadoc()));

        return props;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new BuildNd4jBackend()).execute(args);
        System.exit(exitCode);
    }
}
