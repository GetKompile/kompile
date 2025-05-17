/*
 *  Copyright 2025 Kompile Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@Command(name = "build-hosted-llm-rag-app",
        mixinStandardHelpOptions = true,
        versionProvider = Info.ManifestVersionProvider.class,
        description = "Builds a RAG application using a hosted LLM, with local Anserini for retrieval and a local embedding model.")
public class BuildHostedLlmRagApp implements Callable<Integer> {

    public enum HostedLlmProvider {
        OPENAI, ANTHROPIC, GEMINI, NOOP // Add more as supported by KompileApplicationBuilder
    }

    @Option(names = {"--llm-provider"}, description = "Hosted LLM provider. Valid values: ${COMPLETION-CANDIDATES}. Required.", required = true)
    private HostedLlmProvider llmProvider;

    public enum LocalEmbeddingProvider {
        SENTENCE_TRANSFORMER, SAMEDIFF, NOOP // NOOP implies embeddings might be handled by LLM provider or not used.
    }

    @Option(names = {"--embedding-provider"}, description = "Local embedding model provider for RAG. Valid values: ${COMPLETION-CANDIDATES}. Default: SENTENCE_TRANSFORMER.", defaultValue = "SENTENCE_TRANSFORMER")
    private LocalEmbeddingProvider localEmbeddingProvider;

    // --- SameDiff specific options (if localEmbeddingProvider is SAMEDIFF) ---
    @Option(names = {"--samediff-model-uri"}, description = "URI for the local SameDiff embedding model (if --embedding-provider SAMEDIFF).")
    private String samediffModelUri;

    @Option(names = {"--samediff-input-names"}, description = "Comma-separated input tensor names for SameDiff embedding model (if --embedding-provider SAMEDIFF).", split = ",")
    private List<String> samediffInputNames;

    @Option(names = {"--samediff-output-names"}, description = "Comma-separated output tensor names for SameDiff embedding model (if --embedding-provider SAMEDIFF).", split = ",")
    private List<String> samediffOutputNames;


    // --- Device Configuration for Local Embedding Model ---
    public enum Device { CPU, GPU }
    @Option(names = {"--device"}, description = "Specify execution device for local embedding model: CPU or GPU. Default: CPU.", defaultValue = "CPU")
    private Device device;

    @Option(names = {"--cuda-version"}, description = "CUDA version for GPU device (e.g., 11.8, 12.1). Default: 11.8.", defaultValue = "11.8")
    private String cudaVersion;

    // --- Common Output and Build Options ---
    @Option(names = {"-o", "--output-image-name"}, description = "Name of the output RAG application artifact. Default: kompile-hosted-llm-rag-app")
    String outputImageName = "kompile-hosted-llm-rag-app";

    @Option(names = {"--output-dir"}, description = "Directory to place the final artifact. Default: current directory")
    String outputDirectory = ".";

    @Option(names = {"--app-version"}, description = "Version for the application.", defaultValue = "0.1.0-SNAPSHOT")
    String appVersion;

    @Option(names = {"--native"}, description = "Build a GraalVM native image. If not set, defaults to true unless --assembly is used.")
    Boolean buildNative;

    @Option(names = {"--assembly"}, description = "Build a distributable assembly. Default: false.")
    boolean assemblyBuild = false;

    @Option(names = {"--document-loader-provider"}, description = "Document loader provider for RAG data ingestion. Default: tika.", defaultValue = "tika")
    String documentLoaderProvider = "tika";


    @Override
    public Integer call() throws Exception {
        KompileApplicationBuilder builder = new KompileApplicationBuilder();

        // 1. Validate inputs
        if (localEmbeddingProvider == LocalEmbeddingProvider.SAMEDIFF) {
            if (samediffModelUri == null || samediffInputNames == null || samediffOutputNames == null ||
                    samediffInputNames.isEmpty() || samediffOutputNames.isEmpty()) {
                System.err.println("Error: When --embedding-provider is SAMEDIFF, you must specify --samediff-model-uri, --samediff-input-names, and --samediff-output-names.");
                return 1;
            }
        }

        // 2. Configure Hosted LLM Provider
        builder.llmProvider(this.llmProvider.name().toLowerCase());

        // 3. Configure RAG settings with Anserini and selected Local Embedding Provider
        builder.appType("kompile-spring-boot-webapp") // RAG applications are typically Spring Boot web services
                .enableRagService(true)
                .vectorStoreProvider("anserini") // Anserini for retrieval
                .documentLoaderProvider(this.documentLoaderProvider)
                .enableFilesystemTool(true); // Useful for managing local documents

        String localEmbeddingProviderStr = this.localEmbeddingProvider.name().toLowerCase().replace("_", "-");
        builder.embeddingProvider(localEmbeddingProviderStr);


        // 4. Configure Device for Local Embedding Model (ND4J Backend)
        // This primarily affects the chosen local embedding model if it's ND4J-based (like SameDiff or Sentence Transformers via DJL/PyTorch-ND4J).
        // Anserini itself is Lucene-based but advanced dense retrieval features might eventually use this.
        String nd4jBackend = null;
        String nd4jClassifier = null;
        String nd4jExtension = null;

        if (localEmbeddingProvider != LocalEmbeddingProvider.NOOP) { // Only configure ND4J if a local embedder is used
            String platform = OSResolver.os();
            String arch = OSResolver.arch();

            if (device == Device.GPU) {
                if (cudaVersion == null || cudaVersion.trim().isEmpty()) {
                    System.err.println("Warning: --device GPU specified but --cuda-version is missing. Defaulting to 11.8.");
                    cudaVersion = "11.8";
                }
                nd4jBackend = "nd4j-cuda-" + cudaVersion;
                nd4jClassifier = platform + "-" + arch + "-cuda-" + cudaVersion;
                if ("x86_64".equals(arch) || "amd64".equals(arch)) {
                    nd4jExtension = "-avx2"; // Common default
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
            if (nd4jExtension != null) {
                builder.nd4jExtension(nd4jExtension);
            }
        }

        // 5. Auto-generated components for PomGenerator
        // These hints help PomGenerator include the right modules if its provider mapping isn't exhaustive.
        List<String> componentsForAutoGen = new ArrayList<>(Arrays.asList("anserini"));
        if (localEmbeddingProvider == LocalEmbeddingProvider.SENTENCE_TRANSFORMER) {
            componentsForAutoGen.add("sentence-transformer"); // This string needs to map to the module in PomGenerator
        } else if (localEmbeddingProvider == LocalEmbeddingProvider.SAMEDIFF) {
            componentsForAutoGen.add("samediff");
            // Runtime configuration for SameDiff embedding model (model URI, tensor names)
            // would typically be set via application.properties in the generated app.
            // This CLI doesn't directly generate pipeline steps if it's a full RAG app.
        }
        // Add the LLM provider component string if PomGenerator relies on this pattern
        // e.g., componentsForAutoGen.add(this.llmProvider.name().toLowerCase());
        builder.autoGenCliComponentFlags(componentsForAutoGen.stream().distinct().collect(Collectors.toList()));


        // 6. Set common output and build options
        builder.outputImageName(this.outputImageName)
                .outputDirectory(this.outputDirectory)
                .assemblyBuild(this.assemblyBuild)
                .kompilePrefix(Files.createTempDirectory("kompile-hlrag-build-").toString());

        boolean effectiveNativeBuild = !this.assemblyBuild; // Default to native if not assembly
        if (this.buildNative != null) {
            effectiveNativeBuild = this.buildNative;
        }
        if (this.assemblyBuild && effectiveNativeBuild) {
            System.out.println("Warning: Both --assembly and --native specified. Building native image only.");
            builder.assemblyBuild(false);
        }
        // Other native args (JVM, heap) will use KompileApplicationBuilder defaults

        // 7. Set versions
        String effectiveAppVersion = this.appVersion != null ? this.appVersion : Info.getKompileAppVersion();
        builder.kompileVersion(Info.getVersion())
                .kompilePipelinesVersion(Info.getKompilePipelinesVersion()) // For any core pipeline framework parts
                .kompileAppVersion(effectiveAppVersion)          // For kompile-app-* modules
                .ragMcpAssistantParentVersion(effectiveAppVersion); // RAG Parent version

        // --- Informational Printout ---
        System.out.println("--- Building Hosted LLM RAG Application ---");
        System.out.println("  Output Name: " + builder.getOutputImageName());
        System.out.println("  Output Directory: " + builder.getOutputDirectory());
        System.out.println("  Application Version: " + effectiveAppVersion);
        System.out.println("  App Type: " + builder.getAppType());
        System.out.println("  Hosted LLM Provider: " + this.llmProvider);
        System.out.println("  Local Embedding Provider: " + this.localEmbeddingProvider);
        if (localEmbeddingProvider == LocalEmbeddingProvider.SAMEDIFF) {
            System.out.println("    SameDiff Model URI: " + this.samediffModelUri);
        }
        if (localEmbeddingProvider != LocalEmbeddingProvider.NOOP) {
            System.out.println("  Local Embedding Device: " + this.device + (this.device == Device.GPU ? " (CUDA " + cudaVersion + ")" : ""));
            System.out.println("    ND4J Backend: " + nd4jBackend);
            System.out.println("    ND4J Classifier: " + nd4jClassifier);
            if (nd4jExtension != null) System.out.println("    ND4J Extension: " + nd4jExtension);
        }
        System.out.println("  Retrieval (Vector Store): Anserini");
        System.out.println("  Document Loader: " + this.documentLoaderProvider);
        System.out.println("  Assembly Build (--assembly): " + builder.isAssemblyBuild());
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
     *
     * OpenAI LLM, local Sentence Transformer embeddings on CPU, Anserini RAG:
     * java -cp ... ai.kompile.cli.main.build.simplified.BuildHostedLlmRagApp \
     * --llm-provider OPENAI \
     * --embedding-provider SENTENCE_TRANSFORMER \
     * --device CPU \
     * --output-image-name my-openai-rag-app
     *
     * Gemini LLM, local SameDiff embeddings on GPU (CUDA 12.1), Anserini RAG:
     * java -cp ... ai.kompile.cli.main.build.simplified.BuildHostedLlmRagApp \
     * --llm-provider GEMINI \
     * --embedding-provider SAMEDIFF \
     * --samediff-model-uri file:/path/to/my_embedding_model.sd \
     * --samediff-input-names=input_ids,attention_mask \
     * --samediff-output-names=last_hidden_state \
     * --device GPU --cuda-version 12.1 \
     * --output-image-name my-gemini-samediff-rag-app \
     * --app-version 1.1.0
     */
    public static void main(String[] args) {
        // Example direct invocation for testing:
        // args = new String[]{
        //         "--llm-provider", "OPENAI",
        //         "--embedding-provider", "SENTENCE_TRANSFORMER",
        //         // "--embedding-provider", "SAMEDIFF",
        //         // "--samediff-model-uri", "file:/tmp/dummy_embed.sd",
        //         // "--samediff-input-names", "input",
        //         // "--samediff-output-names", "embedding",
        //         "--device", "CPU",
        //         "--output-image-name", "my-hosted-llm-rag-test",
        //         "--app-version", "0.0.1-test"
        // };
        int exitCode = new CommandLine(new BuildHostedLlmRagApp()).execute(args);
        System.exit(exitCode);
    }
}