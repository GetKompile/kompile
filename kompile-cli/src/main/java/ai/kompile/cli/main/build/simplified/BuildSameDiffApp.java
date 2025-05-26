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

package ai.kompile.cli.main.build.simplified;

import ai.kompile.cli.main.Info;
import ai.kompile.cli.main.build.KompileApplicationBuilder;
import ai.kompile.cli.main.util.OSResolver;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.Callable;

@Command(name = "build-samediff-app",
        mixinStandardHelpOptions = true,
        versionProvider = Info.ManifestVersionProvider.class,
        description = "Builds a Kompile application specifically for SameDiff models, " +
                "with simplified CPU/GPU configuration and optional RAG setup using Anserini.")
public class BuildSameDiffApp implements Callable<Integer> {

    // --- Simplified SameDiff Specific Options ---
    @Option(names = {"--samediff-model-uri"}, description = "URI for the SameDiff model (.sd or .fb file).", required = true)
    private String samediffModelUri;

    @Option(names = {"--input-names"}, description = "Comma-separated input tensor names for the SameDiff model.", required = true, split = ",")
    private List<String> samediffInputNames;

    @Option(names = {"--output-names"}, description = "Comma-separated output tensor names for the SameDiff model.", required = true, split = ",")
    private List<String> samediffOutputNames;

    public enum Device { CPU, GPU }
    @Option(names = {"--device"}, description = "Specify execution device: CPU or GPU. Default: CPU.", defaultValue = "CPU")
    private Device device;

    @Option(names = {"--cuda-version"}, description = "CUDA version to use if --device GPU (e.g., 11.8, 12.1). Default: 11.8.", defaultValue = "11.8")
    private String cudaVersion;


    // --- RAG Specific Options ---
    @Option(names = {"--rag-with-anserini"}, description = "Configure as a RAG application using Anserini for retrieval and this SameDiff model for embeddings. Default: false.")
    private boolean ragWithAnserini = false;

    // --- Common Output and Build Options ---
    @Option(names = {"-o", "--output-image-name"}, description = "Name of the output artifact (e.g., my-samediff-app). Default: kompile-samediff-app")
    String outputImageName = "kompile-samediff-app";

    @Option(names = {"--output-dir"}, description = "Directory to place the final artifact. Default: current directory")
    String outputDirectory = ".";

    @Option(names = {"--native"}, description = "Build a GraalVM native image. If not set, defaults to true unless --assembly is used.")
    Boolean buildNative;

    @Option(names = {"--assembly"}, description = "Build a distributable assembly (uberjar with dependencies). Default: false.")
    boolean assemblyBuild = false;

    @Option(names = {"--app-version"}, description = "Version for the application.", defaultValue = "0.1.0-SNAPSHOT") // Default to a common snapshot version
    String appVersion;


    @Override
    public Integer call() throws Exception {
        KompileApplicationBuilder builder = new KompileApplicationBuilder();

        // 1. Determine ND4J Backend and Classifier based on --device
        String nd4jBackend;
        String nd4jClassifier;
        String nd4jExtension = null;

        String platform = OSResolver.os();
        String arch = OSResolver.arch();

        if (device == Device.GPU) {
            if (cudaVersion == null || cudaVersion.trim().isEmpty()) {
                System.err.println("Warning: --device GPU specified but --cuda-version is missing. Defaulting to 11.8.");
                cudaVersion = "11.8"; // Default CUDA
            }
            nd4jBackend = "nd4j-cuda-" + cudaVersion;
            nd4jClassifier = platform + "-" + arch + "-cuda-" + cudaVersion;
            if ("x86_64".equals(arch) || "amd64".equals(arch)) {
                nd4jExtension = "-avx2"; // A common sensible default for x86_64
                nd4jClassifier += nd4jExtension;
            }
        } else { // CPU
            nd4jBackend = "nd4j-native";
            nd4jClassifier = platform + "-" + arch;
            if ("x86_64".equals(arch) || "amd64".equals(arch)) {
                nd4jExtension = "-avx2";
                nd4jClassifier += nd4jExtension;
            }
        }

        builder.nd4jBackend(nd4jBackend)
                .nd4jClassifier(nd4jClassifier);
        if(nd4jExtension != null) {
            builder.nd4jExtension(nd4jExtension);
        }

        // 2. Configure KompileApplicationBuilder based on use case (RAG or standard pipeline)
        Map<String, Map<String, Object>> stepParamsForAutoGen = new HashMap<>();
        List<String> componentsForAutoGen = new ArrayList<>();
        componentsForAutoGen.add("samediff"); // Always include samediff component

        if (ragWithAnserini) {
            builder.appType("kompile-spring-boot-webapp") // RAG apps are typically web services
                    .embeddingProvider("samediff")        // Use SameDiff for embeddings
                    .vectorStoreProvider("anserini")      // Use Anserini for retrieval
                    .enableRagService(true)
                    .enableFilesystemTool(true); // Often useful for RAG to manage documents
            // For RAG, the main application (kompile-app-main) usually defines the pipeline.
            // We ensure "samediff" is listed so PomGenerator includes kompile-embedding-samediff.
            // Anserini inclusion is triggered by vectorStoreProvider("anserini").
        } else {
            // Standard SameDiff pipeline executor
            builder.appType("pipeline-executor")
                    .autoGeneratePipelineFromCliFlags(true); // We will define a simple SameDiff step

            Map<String, Object> samediffStepPipelineParams = new HashMap<>();
            samediffStepPipelineParams.put("modelUri", this.samediffModelUri);
            samediffStepPipelineParams.put("inputNames", this.samediffInputNames);
            samediffStepPipelineParams.put("outputNames", this.samediffOutputNames);
            stepParamsForAutoGen.put("samediff", samediffStepPipelineParams);
            builder.autoGenStepCliParams(stepParamsForAutoGen);
        }
        builder.autoGenCliComponentFlags(componentsForAutoGen);


        // 3. Set common output and build options
        builder.outputImageName(this.outputImageName)
                .outputDirectory(this.outputDirectory)
                .assemblyBuild(this.assemblyBuild)
                .kompilePrefix(Files.createTempDirectory("kompile-samediff-build-").toString());

        boolean effectiveNativeBuild = !this.assemblyBuild; // Default to native if not assembly
        if (this.buildNative != null) { // If user explicitly sets --native
            effectiveNativeBuild = this.buildNative;
        }
        if (this.assemblyBuild && effectiveNativeBuild) {
            System.out.println("Warning: Both --assembly and --native specified for build-samediff-app. Building native image only.");
            builder.assemblyBuild(false); // Prioritize native
        }
        // Native image JVM args, heap space, etc., will use defaults from KompileApplicationBuilder
        // unless more simplified CLI options are added here to configure them.

        // 4. Set versions
        String effectiveAppVersion = this.appVersion != null ? this.appVersion : Info.getKompileAppVersion();
        builder.kompileVersion(Info.getVersion()) // Kompile parent/system version
                .kompilePipelinesVersion(Info.getKompilePipelinesVersion())
                .kompileAppVersion(effectiveAppVersion); // Version for the generated app artifact

        if (ragWithAnserini) {
            builder.ragMcpAssistantParentVersion(effectiveAppVersion); // RAG parent version
        }


        // --- Informational Printout (uses conceptual getters from KompileApplicationBuilder) ---
        System.out.println("--- Building SameDiff Application ---");
        System.out.println("  Output Name: " + builder.outputImageName); // KAB.outputImageName is public
        System.out.println("  Output Directory: " + builder.outputDirectory); // KAB.outputDirectory is public
        System.out.println("  Application Version: " + effectiveAppVersion);
        System.out.println("  App Type: " + builder.getAppType()); // Assumes public getAppType()
        System.out.println("  Target Device: " + this.device + (this.device == Device.GPU ? " (CUDA " + cudaVersion + ")" : ""));
        System.out.println("    ND4J Backend: " + nd4jBackend);
        System.out.println("    ND4J Classifier: " + nd4jClassifier);
        if (nd4jExtension != null) System.out.println("    ND4J Extension: " + nd4jExtension);
        System.out.println("  RAG with Anserini: " + this.ragWithAnserini);
        if (!ragWithAnserini) {
            System.out.println("  SameDiff Model URI: " + this.samediffModelUri);
            System.out.println("  SameDiff Input Names: " + this.samediffInputNames);
            System.out.println("  SameDiff Output Names: " + this.samediffOutputNames);
        }
        System.out.println("  Assembly Build (--assembly): " + builder.isAssemblyBuild()); // Assumes public isAssemblyBuild()
        System.out.println("  Native Build (--native): " + effectiveNativeBuild);
        System.out.println("------------------------------------");


        try {
            return builder.build();
        } finally {
            builder.cleanupTemporaryFiles();
        }
    }

    /**
     * Main method for direct CLI testing.
     * Example Usages:
     * CPU Simple Pipeline:
     * java -cp ... ai.kompile.cli.main.build.simplified.BuildSameDiffApp --samediff-model-uri file:/path/to/model.sd --input-names=input --output-names=output --output-image-name=my-cpu-samediff-app
     *
     * GPU Simple Pipeline:
     * java -cp ... ai.kompile.cli.main.build.simplified.BuildSameDiffApp --samediff-model-uri file:/path/to/model.sd --input-names=input --output-names=output --device GPU --cuda-version 12.1 --output-image-name=my-gpu-samediff-app
     *
     * RAG with Anserini (SameDiff for embeddings) on CPU:
     * java -cp ... ai.kompile.cli.main.build.simplified.BuildSameDiffApp --samediff-model-uri file:/path/to/embedding_model.sd --input-names=text_input --output-names=embedding --rag-with-anserini --output-image-name=my-rag-samediff-app --app-version 1.0.0
     */
    public static void main(String[] args) {
        // For testing, you would need to ensure all dependencies are on the classpath.
        // This is typically handled when the CLI is packaged.
        // Example direct invocation for testing (adjust classpath and arguments):
        // args = new String[]{
        //         "--samediff-model-uri", "file:/tmp/your_model.sd", // Replace with a real model path
        //         "--input-names", "input",
        //         "--output-names", "output",
        //         "--output-image-name", "my-samediff-test-app",
        //         "--device", "CPU",
        //         // "--rag-with-anserini" // Uncomment to test RAG mode
        // };
        int exitCode = new CommandLine(new BuildSameDiffApp()).execute(args);
        System.exit(exitCode);
    }
}