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

import ai.kompile.cli.main.build.config.Nd4jBuildConfig;
import ai.kompile.cli.main.build.config.Nd4jBuildConfig.*;
import ai.kompile.cli.common.util.EnvironmentUtils;
import ai.kompile.cli.main.util.OSResolver;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.Invoker;
import org.codehaus.plexus.util.FileUtils;
import org.nd4j.common.base.Preconditions;
import picocli.CommandLine;

import java.io.File;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Generate a DL4J build output as a tar file containing an ND4J backend and related dependencies.
 * Supports comprehensive build options for type profiles, helper libraries, and optimizations.
 */
@CommandLine.Command(
    name = "dl4j-build-generate",
    mixinStandardHelpOptions = true,
    description = "Generate a DL4J build output as a tar file containing an ND4J backend and related dependencies.\n\n" +
            "This command creates a distributable archive with the compiled ND4J backend for deployment.\n\n" +
            "Examples:\n" +
            "  # Basic CPU build\n" +
            "  kompile build dl4j-build-generate --javacppPlatform=linux-x86_64\n\n" +
            "  # Minimal inference build with OneDNN\n" +
            "  kompile build dl4j-build-generate --type-profile=INFERENCE --helper-onednn\n\n" +
            "  # CUDA build with cuDNN\n" +
            "  kompile build dl4j-build-generate --backend=cuda --cuda-version=12.3 --helper-cudnn"
)
public class GenerateDl4jBuild implements Callable<Void> {

    // =============================================
    // Output Configuration
    // =============================================

    @CommandLine.Option(names = {"--outputDirectory", "-o"}, description = "Output directory for the build")
    private String outputDirectory = "dl4j-build";

    @CommandLine.Option(names = {"--pomFile"}, description = "POM file to use as template")
    private String pomFile = "pom2.xml";

    @CommandLine.Option(names = {"--mavenHome"}, description = "Maven home directory", required = true)
    private File mavenHome = EnvironmentUtils.defaultMavenHome();

    // =============================================
    // Platform Configuration
    // =============================================

    @CommandLine.Option(names = {"--javacppPlatform", "--platform"},
            description = "Build for a specific platform (e.g., linux-x86_64, macosx-arm64, windows-x86_64)")
    private String javacppPlatform = OSResolver.os() + "-" + OSResolver.arch();

    @CommandLine.Option(names = {"--javacppExtension", "--extension"},
            description = "Platform extension (e.g., avx2, avx512, cuda-12.3)")
    private String javacppExtension;

    // =============================================
    // Backend Selection
    // =============================================

    @CommandLine.Option(names = {"--backend"}, description = "Backend type: CPU (default), CUDA, TPU, ZLUDA")
    private Backend backend = Backend.CPU;

    @CommandLine.Option(names = {"--maven-profile"},
            description = "Maven profile: aurora, minimial-cpu, cpu, cuda, tpu, zluda, zluda-amd, zluda-intel")
    private String mavenProfile;

    // =============================================
    // Type Profiles
    // =============================================

    @CommandLine.Option(names = {"--type-profile"}, description = "Type profile for compilation:\n" +
            "  MINIMAL_INDEXING: float32, double, int32, int64 (fastest)\n" +
            "  ESSENTIAL: adds int8, int16\n" +
            "  FLOATS_ONLY: float32, double, float16\n" +
            "  QUANTIZATION: int8, uint8, float32, int32\n" +
            "  TRAINING: float32, float16, bfloat16, int32, int64, double\n" +
            "  INFERENCE: int8, uint8, float16, float32, int32\n" +
            "  STANDARD_ALL_TYPES: all types")
    private TypeProfile typeProfile;

    @CommandLine.Option(names = {"--data-types"}, split = ";",
            description = "Custom data types (semicolon-separated)")
    private List<String> dataTypes;

    // =============================================
    // Helper Libraries
    // =============================================

    @CommandLine.Option(names = {"--helper-onednn"}, description = "Enable OneDNN for CPU optimization")
    private boolean helperOnednn = false;

    @CommandLine.Option(names = {"--helper-cudnn"}, description = "Enable cuDNN for NVIDIA GPU")
    private boolean helperCudnn = false;

    @CommandLine.Option(names = {"--helper-armcompute"}, description = "Enable ARM Compute Library")
    private boolean helperArmcompute = false;

    @CommandLine.Option(names = {"--helper-mps"}, description = "Enable Metal Performance Shaders (macOS)")
    private boolean helperMps = false;

    @CommandLine.Option(names = {"--helper-accelerate"}, description = "Enable Apple Accelerate (macOS)")
    private boolean helperAccelerate = false;

    @CommandLine.Option(names = {"--helper-miopen"}, description = "Enable MIOpen for AMD GPUs (ZLUDA)")
    private boolean helperMiopen = false;

    @CommandLine.Option(names = {"--helpers"}, description = "Comma-separated list of helpers")
    private String helpersList;

    // =============================================
    // ZLUDA Options
    // =============================================

    @CommandLine.Option(names = {"--amd-gpu-targets"}, description = "AMD GPU architecture targets (semicolon-separated, e.g., gfx1030;gfx1100)")
    private String amdGpuTargets;

    @CommandLine.Option(names = {"--rocm-version"}, description = "ROCm version for AMD ZLUDA target (e.g., 6.1)")
    private String rocmVersion;

    // =============================================
    // CUDA Options
    // =============================================

    @CommandLine.Option(names = {"--cuda-version"}, description = "CUDA version (e.g., 12.3)")
    private String cudaVersion;

    @CommandLine.Option(names = {"--cudnn-version"}, description = "cuDNN version (e.g., 8.9)")
    private String cudnnVersion;

    @CommandLine.Option(names = {"--compute-capability"}, description = "GPU compute capabilities (e.g., 80,86,89)")
    private String computeCapability;

    // =============================================
    // Optimization Options
    // =============================================

    @CommandLine.Option(names = {"--use-lto"}, description = "Enable Link Time Optimization")
    private boolean useLto = false;

    @CommandLine.Option(names = {"--semantic-filtering"}, negatable = true,
            description = "Enable semantic filtering (default: true)")
    private boolean semanticFiltering = true;

    @CommandLine.Option(names = {"--aggressive-filtering"},
            description = "Enable aggressive semantic filtering")
    private boolean aggressiveFiltering = false;

    @CommandLine.Option(names = {"--parallel-jobs", "-j"}, description = "Number of parallel jobs")
    private int parallelJobs = Runtime.getRuntime().availableProcessors();

    // =============================================
    // Operations Selection
    // =============================================

    @CommandLine.Option(names = {"--operations"},
            description = "Semicolon-separated list of operations to include")
    private String operations;

    // =============================================
    // Build Options
    // =============================================

    @CommandLine.Option(names = {"--skip-tests"}, negatable = true, description = "Skip tests (default: true)")
    private boolean skipTests = true;

    @CommandLine.Option(names = {"--verbose", "-v"}, description = "Verbose output")
    private boolean verbose = false;

    public void runMain(String... args) throws Exception {
        InvocationRequest invocationRequest = new DefaultInvocationRequest();
        File project = new File(outputDirectory);
        if (project.listFiles() != null && project.listFiles().length > 0) {
            System.err.println("Found non-empty directory at " + project.getAbsolutePath() +
                    ". Please specify an empty directory or delete existing contents.");
            System.exit(1);
        }

        Preconditions.checkState(project.mkdirs() || project.exists(),
                "Unable to create directory " + project.getAbsolutePath());

        File targetPomToCopy = new File(pomFile);
        if (targetPomToCopy.exists()) {
            FileUtils.copyFile(targetPomToCopy, new File(project, "pom.xml"));
        } else {
            System.out.println("No template POM file found at " + pomFile + ". Creating basic POM.");
            createBasicPom(new File(project, "pom.xml"));
        }

        File resourcesDir = new File(project, "src/assembly/");
        Preconditions.checkState(resourcesDir.mkdirs() || resourcesDir.exists(),
                "Unable to create directories " + resourcesDir.getAbsolutePath());

        // Create assembly descriptor
        createAssemblyDescriptor(new File(resourcesDir, "kompile.xml"));

        // Build Maven goals and properties
        List<String> goals = buildMavenGoals();
        invocationRequest.setGoals(goals);

        Invoker invoker = new DefaultInvoker();
        invocationRequest.setPomFile(new File(project, "pom.xml"));
        invoker.setWorkingDirectory(project);
        invocationRequest.setBaseDirectory(project);
        invoker.setMavenHome(mavenHome);

        if (verbose) {
            System.out.println("\n========================================");
            System.out.println("DL4J Build Configuration");
            System.out.println("========================================");
            System.out.println("Output Directory: " + outputDirectory);
            System.out.println("Platform: " + javacppPlatform);
            if (javacppExtension != null) System.out.println("Extension: " + javacppExtension);
            System.out.println("Backend: " + backend);
            if (typeProfile != null) System.out.println("Type Profile: " + typeProfile);
            if (helpersList != null || helperOnednn || helperCudnn) {
                System.out.println("Helpers: " + buildHelpersList());
            }
            System.out.println("LTO: " + useLto);
            System.out.println("Parallel Jobs: " + parallelJobs);
            System.out.println("========================================\n");
        }

        System.out.println("Invoking Maven with goals: " + goals);
        invoker.execute(invocationRequest);
    }

    private List<String> buildMavenGoals() {
        List<String> goals = new ArrayList<>();

        // Platform configuration
        if (javacppPlatform != null && !javacppPlatform.isEmpty()) {
            goals.add("-Djavacpp.platform=" + javacppPlatform);
            goals.add("-Dlibnd4j.platform=" + javacppPlatform);
        }
        if (javacppExtension != null && !javacppExtension.isEmpty()) {
            goals.add("-Djavacpp.platform.extension=" + javacppExtension);
            goals.add("-Dlibnd4j.extension=" + javacppExtension);
        }

        // Backend configuration
        switch (backend) {
            case CUDA:
                goals.add("-DSD_CUDA=ON");
                if (cudaVersion != null) goals.add("-DCUDA_VERSION=" + cudaVersion);
                if (cudnnVersion != null) goals.add("-DCUDNN_VERSION=" + cudnnVersion);
                if (computeCapability != null) goals.add("-DSD_COMPUTE_CAPABILITY=" + computeCapability);
                break;
            case TPU:
                goals.add("-DSD_TPU=ON");
                break;
            case ZLUDA:
                goals.add("-DSD_ZLUDA=ON");
                if (amdGpuTargets != null) goals.add("-DSD_AMD_GPU_TARGETS=" + amdGpuTargets);
                if (rocmVersion != null) goals.add("-DROCM_VERSION=" + rocmVersion);
                break;
            default:
                goals.add("-DSD_CPU=ON");
        }

        // Type profile
        if (typeProfile != null) {
            goals.add("-DSD_TYPE_PROFILE=" + typeProfile.name());
            String dataTypesStr = getDataTypesForProfile(typeProfile);
            if (dataTypesStr != null) {
                goals.add("-Dlibnd4j.datatypes=" + dataTypesStr);
            }
        } else if (dataTypes != null && !dataTypes.isEmpty()) {
            goals.add("-Dlibnd4j.datatypes=" + String.join(";", dataTypes));
        }

        // Helpers
        String helpers = buildHelpersList();
        if (helpers != null && !helpers.isEmpty()) {
            goals.add("-Dlibnd4j.helpers=" + helpers.replace(";", ","));
            if (helperOnednn) goals.add("-DHELPERS_onednn=ON");
            if (helperCudnn) goals.add("-DHELPERS_cudnn=ON");
            if (helperArmcompute) goals.add("-DHELPERS_armcompute=ON");
            if (helperMps) goals.add("-DHELPERS_mps=ON");
            if (helperAccelerate) goals.add("-DHELPERS_accelerate=ON");
            if (helperMiopen) goals.add("-DHELPERS_miopen=ON");
        }

        // Optimization options
        goals.add("-DSD_USE_LTO=" + (useLto ? "ON" : "OFF"));
        goals.add("-Dlibnd4j.lto=" + (useLto ? "ON" : "OFF"));
        goals.add("-DSD_ENABLE_SEMANTIC_FILTERING=" + (semanticFiltering ? "ON" : "OFF"));
        goals.add("-DSD_AGGRESSIVE_SEMANTIC_FILTERING=" + (aggressiveFiltering ? "ON" : "OFF"));
        goals.add("-Dlibnd4j.buildthreads=" + parallelJobs);
        goals.add("-DSD_PARALLEL_COMPILE_JOBS=" + parallelJobs);

        // Operations
        if (operations != null && !operations.isEmpty()) {
            goals.add("-Dlibnd4j.operations=" + operations);
        }

        // Skip tests
        if (skipTests) {
            goals.add("-DskipTests=true");
            goals.add("-Dmaven.test.skip=true");
        }

        // Maven profile
        if (mavenProfile != null && !mavenProfile.isEmpty()) {
            goals.add("-P" + mavenProfile);
        }

        goals.add("assembly:single");

        return goals;
    }

    private String buildHelpersList() {
        if (helpersList != null && !helpersList.isEmpty()) {
            return helpersList;
        }

        StringBuilder sb = new StringBuilder();
        if (helperOnednn) append(sb, "onednn");
        if (helperCudnn) append(sb, "cudnn");
        if (helperArmcompute) append(sb, "armcompute");
        if (helperMps) append(sb, "mps");
        if (helperAccelerate) append(sb, "accelerate");
        if (helperMiopen) append(sb, "miopen");

        return sb.toString();
    }

    private void append(StringBuilder sb, String value) {
        if (sb.length() > 0) sb.append(";");
        sb.append(value);
    }

    private String getDataTypesForProfile(TypeProfile profile) {
        switch (profile) {
            case MINIMAL_INDEXING:
                return "float32;double;int32;int64";
            case ESSENTIAL:
                return "float32;double;int32;int64;int8;int16";
            case FLOATS_ONLY:
                return "float32;double;float16";
            case QUANTIZATION:
                return "int8;uint8;float32;int32";
            case TRAINING:
                return "float32;float16;bfloat16;int32;int64;double";
            case INFERENCE:
                return "int8;uint8;float16;float32;int32";
            case STANDARD_ALL_TYPES:
            default:
                return null; // Use default
        }
    }

    private void createAssemblyDescriptor(File assemblyFile) throws Exception {
        String config = "<assembly xmlns=\"http://maven.apache.org/ASSEMBLY/2.1.1\"\n" +
                "  xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                "  xsi:schemaLocation=\"http://maven.apache.org/ASSEMBLY/2.1.1 https://maven.apache.org/xsd/assembly-2.1.1.xsd\">\n" +
                "  <id>bin</id>\n" +
                "  <formats>\n" +
                "    <format>tar.gz</format>\n" +
                "    <format>tar.bz2</format>\n" +
                "    <format>zip</format>\n" +
                "  </formats>\n" +
                "  <dependencySets>\n" +
                "    <dependencySet>\n" +
                "      <outputDirectory>lib/</outputDirectory>\n" +
                "      <includes>\n" +
                "        <include>org.eclipse.deeplearning4j:*:*:*</include>\n" +
                "      </includes>\n" +
                "    </dependencySet>\n" +
                "  </dependencySets>\n" +
                "</assembly>\n";
        org.apache.commons.io.FileUtils.write(assemblyFile, config, Charset.defaultCharset());
    }

    private void createBasicPom(File pomFile) throws Exception {
        String pom = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n" +
                "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n" +
                "    <modelVersion>4.0.0</modelVersion>\n" +
                "    <groupId>ai.kompile.generated</groupId>\n" +
                "    <artifactId>dl4j-build</artifactId>\n" +
                "    <version>1.0.0-SNAPSHOT</version>\n" +
                "    <packaging>pom</packaging>\n" +
                "\n" +
                "    <properties>\n" +
                "        <dl4j.version>1.0.0-SNAPSHOT</dl4j.version>\n" +
                "    </properties>\n" +
                "\n" +
                "    <dependencies>\n" +
                "        <dependency>\n" +
                "            <groupId>org.eclipse.deeplearning4j</groupId>\n" +
                "            <artifactId>nd4j-native</artifactId>\n" +
                "            <version>${dl4j.version}</version>\n" +
                "        </dependency>\n" +
                "    </dependencies>\n" +
                "\n" +
                "    <build>\n" +
                "        <plugins>\n" +
                "            <plugin>\n" +
                "                <groupId>org.apache.maven.plugins</groupId>\n" +
                "                <artifactId>maven-assembly-plugin</artifactId>\n" +
                "                <version>3.6.0</version>\n" +
                "                <configuration>\n" +
                "                    <descriptors>\n" +
                "                        <descriptor>src/assembly/kompile.xml</descriptor>\n" +
                "                    </descriptors>\n" +
                "                </configuration>\n" +
                "            </plugin>\n" +
                "        </plugins>\n" +
                "    </build>\n" +
                "\n" +
                "    <repositories>\n" +
                "        <repository>\n" +
                "            <id>sonatype-nexus-snapshots</id>\n" +
                "            <url>https://oss.sonatype.org/content/repositories/snapshots</url>\n" +
                "            <snapshots><enabled>true</enabled></snapshots>\n" +
                "        </repository>\n" +
                "    </repositories>\n" +
                "</project>\n";
        org.apache.commons.io.FileUtils.write(pomFile, pom, Charset.defaultCharset());
    }

    public static void main(String... args) {
        new CommandLine(new GenerateDl4jBuild()).execute(args);
    }

    @Override
    public Void call() throws Exception {
        runMain();
        return null;
    }
}
