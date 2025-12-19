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
import ai.kompile.cli.main.util.EnvironmentUtils;
import org.apache.commons.io.FileUtils;
import org.apache.maven.shared.invoker.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;

@Command(name = "build-rag-app", mixinStandardHelpOptions = true,
        description = "Builds a RAG MCP Assistant application instance, optionally as a GraalVM native image.")
public class BuildRagApp implements Callable<Integer> {

    @Option(names = {"--configName"}, description = "A name for this RAG application configuration/instance's artifactId", required = true)
    private String configName;

    @Option(names = {"--outputDir"}, description = "Base directory for the build output of this instance", defaultValue = "kompile-rag-builds")
    private File outputDirBase;

    @Option(names = {"--instanceGroupId"}, description = "GroupId for the generated RAG instance", defaultValue = "ai.kompile.rag.instance")
    private String instanceGroupId;

    @Option(names = {"--instanceVersion"}, description = "Version for the generated RAG instance", defaultValue = "0.1.0-SNAPSHOT")
    private String instanceVersion;

    @Option(names = {"--ragMcpVersion"}, description = "Version of the ai.kompile modules", defaultValue = "0.1.0-SNAPSHOT")
    private String ragMcpVersion;

    @Option(names = {"--includeAppMain"}, description = "Include kompile-app-main module (provides UI and main app)", defaultValue = "true", negatable = true)
    private boolean includeAppMain;

    @Option(names = {"--includeAppCore"}, description = "Include kompile-app-core module", defaultValue = "true", negatable = true)
    private boolean includeAppCore;

    @Option(names = {"--includeLoadersOrchestrator"}, description = "Include kompile-app-loaders-orchestrator module", defaultValue = "true", negatable = true)
    private boolean includeLoadersOrchestrator;

    // Original loaders (deprecated/heavy)
    @Option(names = {"--includeLoaderTika"}, description = "Include kompile-loader-tika module (deprecated - use specialized loaders instead)")
    private boolean includeLoaderTika = false;
    @Option(names = {"--includeLoaderPdf"}, description = "Include kompile-loader-pdf module")
    private boolean includeLoaderPdf = false;

    // New specialized loader modules
    @Option(names = {"--includeLoaderMicrosoft"}, description = "Include kompile-loader-microsoft module for Office documents")
    private boolean includeLoaderMicrosoft = false;
    @Option(names = {"--includeLoaderMail"}, description = "Include kompile-loader-mail module for email parsing")
    private boolean includeLoaderMail = false;
    @Option(names = {"--includeLoaderPdfExtended"}, description = "Include kompile-loader-pdf-extended module for advanced PDF processing")
    private boolean includeLoaderPdfExtended = false;

    // Chunker Options
    @Option(names = {"--includeChunkerSentence"}, description = "Include kompile-chunker-sentence module")
    private boolean includeChunkerSentence = false;

    @Option(names = {"--supportedLanguages"}, description = "Comma-separated list of language codes for OpenNLP models (e.g., en,de,ja) if --includeChunkerSentence is true.", arity = "1..*", split = ",", defaultValue = "en")
    private List<String> supportedLanguages = new ArrayList<>(Arrays.asList("en"));

    @Option(names = {"--includeChunkerRecursiveCharacter"}, description = "Include kompile-chunker-recursivecharacter module")
    private boolean includeChunkerRecursiveCharacter = false;
    @Option(names = {"--includeChunkerMarkdown"}, description = "Include kompile-chunker-markdown module")
    private boolean includeChunkerMarkdown = false;
    @Option(names = {"--includeChunkerToken"}, description = "Include kompile-chunker-token module")
    private boolean includeChunkerToken = false;

    // LLM Options
    @Option(names = {"--includeAnserini"}, description = "Include kompile-app-anserini module")
    private boolean includeAnserini = false;
    @Option(names = {"--includeLlmOpenai"}, description = "Include kompile-app-openai-llm module")
    private boolean includeLlmOpenai = false;
    @Option(names = {"--includeLlmAnthropic"}, description = "Include kompile-app-anthropic-llm module")
    private boolean includeLlmAnthropic = false;
    @Option(names = {"--includeLlmGemini"}, description = "Include kompile-app-gemini-llm module")
    private boolean includeLlmGemini = false;

    // Embedding Options
    @Option(names = {"--includeEmbeddingOpenai"}, description = "Include kompile-embedding-openai module")
    private boolean includeEmbeddingOpenai = false;
    @Option(names = {"--includeEmbeddingAnserini"}, description = "Include kompile-embedding-anserini module for BGE, Arctic Embed, and other SameDiff-based embeddings")
    private boolean includeEmbeddingAnserini = false;
    @Option(names = {"--includeEmbeddingSentenceTransformer"}, description = "Include kompile-embedding-sentence-transformer module")
    private boolean includeEmbeddingSentenceTransformer = false;
    @Option(names = {"--includeEmbeddingPostgresml"}, description = "Include kompile-embedding-postgresml module")
    private boolean includeEmbeddingPostgresml = false;

    // Vector Store Options
    @Option(names = {"--includeVectorstoreChroma"}, description = "Include kompile-vectorstore-chroma module")
    private boolean includeVectorstoreChroma = false;
    @Option(names = {"--includeVectorstorePgvector"}, description = "Include kompile-vectorstore-pgvector module")
    private boolean includeVectorstorePgvector = false;

    @Option(names = {"--includeVectorStoreAnserini"}, description = "Include kompile-vectorstore-anserini module")
    private boolean includeVectorStoreAnserini = false;

    @CommandLine.Option(names = {"--javacppPlatform"},description = "Build for a specific specified platform. An example would be linux-x86_64 - this reduces binary size and prevents out of memories from trying to include binaries for too many platforms.")
    private String javacppPlatform = "linux-x86_64";

    @CommandLine.Option(names = {"--javacppExtension"},description = "An optional javacpp extension such as avx2 or cuda depending on the target set of dependencies.")
    private String javacppExtension;

    // Tool Options
    @Option(names = {"--includeToolFilesystem"}, description = "Include kompile-tool-filesystem module", defaultValue = "true", negatable = true)
    private boolean includeToolFilesystem;
    @Option(names = {"--includeToolRag"}, description = "Include kompile-tool-rag module", defaultValue = "true", negatable = true)
    private boolean includeToolRag;

    // Additional Options
    @Option(names = {"--includePgmlIndexer"}, description = "Include kompile-app-pgml-indexer module")
    private boolean includePgmlIndexer = false;

    @Option(names = {"--includeModelStaging"}, description = "Include kompile-model-staging module for model management and archives")
    private boolean includeModelStaging = false;

    // Archive Options
    @Option(names = {"--archivePath"}, description = "Path to a .karch archive to embed in the application (for offline/air-gapped deployments)")
    private File archivePath;

    @Option(names = {"--modelSourceType"}, description = "Model source type: ARCHIVE (offline), REGISTRY (online), HYBRID (try archive first, fall back to registry)", defaultValue = "HYBRID")
    private String modelSourceType = "HYBRID";

    @Option(names = {"--registryUrls"}, description = "Comma-separated list of remote model registry URLs", arity = "1..*", split = ",")
    private List<String> registryUrls;

    @Option(names = {"--archiveOnly"}, description = "Enable archive-only mode (no network model downloads)", defaultValue = "false")
    private boolean archiveOnly;

    @Option(names = {"--native"}, description = "Build a GraalVM native image (activates 'native' profile)", defaultValue = "true", negatable = true)
    private boolean buildNative;

    @Option(names = {"--mavenHome"}, description = "Path to Maven installation (default: resolved by EnvironmentUtils)")
    private File mavenHome;

    @Option(names = {"--graalVmHome"}, description = "Path to GraalVM installation (for native builds; default: resolved by Info.graalvmDirectory())")
    private File graalVmHome;

    @Option(names = {"--cleanBuild"}, description = "Perform a clean Maven build", defaultValue = "true", negatable = true)
    private boolean cleanBuild;

    @Option(names = {"--skipTests"}, description = "Skip Maven tests during the build (-DskipTests=true)", defaultValue = "true", negatable = true)
    private boolean skipTests;

    @Option(names = {"--appTitle"}, description = "Application title displayed in the UI banner", defaultValue = "Kompile RAG Console")
    private String appTitle;

    @Override
    public Integer call() throws Exception {
        File buildInstanceDir = new File(outputDirBase, configName);
        if (buildInstanceDir.exists() && cleanBuild) {
            System.out.println("Cleaning previous build directory: " + buildInstanceDir.getAbsolutePath());
            FileUtils.deleteDirectory(buildInstanceDir);
        }
        if (!buildInstanceDir.exists() && !buildInstanceDir.mkdirs()) {
            System.err.println("Failed to create build directory: " + buildInstanceDir.getAbsolutePath());
            return 1;
        }

        File projectBuildDir = new File(buildInstanceDir, "project"); // This is the root of the generated Maven project
        if (!projectBuildDir.exists() && !projectBuildDir.mkdirs()) {
            System.err.println("Failed to create project build sub-directory: " + projectBuildDir.getAbsolutePath());
            return 1;
        }

        File instancePomFile = new File(projectBuildDir, "pom.xml");

        System.out.println("Generating instance-specific POM: " + instancePomFile.getAbsolutePath());

        List<String> ragPomCliArgs = new ArrayList<>();
        ragPomCliArgs.add("--outputFile=" + instancePomFile.getAbsolutePath());
        ragPomCliArgs.add("--instanceGroupId=" + this.instanceGroupId);
        ragPomCliArgs.add("--instanceArtifactId=" + this.configName);
        ragPomCliArgs.add("--instanceVersion=" + this.instanceVersion);
        ragPomCliArgs.add("--ragMcpVersion=" + this.ragMcpVersion);

        // Core modules
        ragPomCliArgs.add("--includeAppMain=" + this.includeAppMain);
        ragPomCliArgs.add("--includeAppCore=" + this.includeAppCore);
        ragPomCliArgs.add("--includeLoadersOrchestrator=" + this.includeLoadersOrchestrator);

        // Original loaders
        ragPomCliArgs.add("--includeLoaderTika=" + this.includeLoaderTika);
        ragPomCliArgs.add("--includeLoaderPdf=" + this.includeLoaderPdf);

        // New specialized loaders
        ragPomCliArgs.add("--includeLoaderMicrosoft=" + this.includeLoaderMicrosoft);
        ragPomCliArgs.add("--includeLoaderMail=" + this.includeLoaderMail);
        ragPomCliArgs.add("--includeLoaderPdfExtended=" + this.includeLoaderPdfExtended);
        ragPomCliArgs.add("--javacppPlatform=" + javacppPlatform);
        ragPomCliArgs.add("--javacppExtension=" + javacppExtension);
        // Chunker options
        ragPomCliArgs.add("--includeChunkerSentence=" + this.includeChunkerSentence);
        if (this.includeChunkerSentence && this.supportedLanguages != null && !this.supportedLanguages.isEmpty()) {
            ragPomCliArgs.add("--supportedLanguages=" + String.join(",", this.supportedLanguages));
        }
        ragPomCliArgs.add("--includeChunkerRecursiveCharacter=" + this.includeChunkerRecursiveCharacter);
        ragPomCliArgs.add("--includeChunkerMarkdown=" + this.includeChunkerMarkdown);
        ragPomCliArgs.add("--includeChunkerToken=" + this.includeChunkerToken);

        // LLM options
        ragPomCliArgs.add("--includeAnserini=" + this.includeAnserini);
        ragPomCliArgs.add("--includeLlmOpenai=" + this.includeLlmOpenai);
        ragPomCliArgs.add("--includeLlmAnthropic=" + this.includeLlmAnthropic);
        ragPomCliArgs.add("--includeLlmGemini=" + this.includeLlmGemini);
        ragPomCliArgs.add("--includeVectorStoreAnserini=" + this.includeVectorStoreAnserini);
        
        // Embedding options
        ragPomCliArgs.add("--includeEmbeddingOpenai=" + this.includeEmbeddingOpenai);
        ragPomCliArgs.add("--includeEmbeddingAnserini=" + this.includeEmbeddingAnserini);
        ragPomCliArgs.add("--includeEmbeddingSentenceTransformer=" + this.includeEmbeddingSentenceTransformer);
        ragPomCliArgs.add("--includeEmbeddingPostgresml=" + this.includeEmbeddingPostgresml);

        // Vector store options
        ragPomCliArgs.add("--includeVectorstoreChroma=" + this.includeVectorstoreChroma);
        ragPomCliArgs.add("--includeVectorstorePgvector=" + this.includeVectorstorePgvector);

        // Tool options
        ragPomCliArgs.add("--includeToolFilesystem=" + this.includeToolFilesystem);
        ragPomCliArgs.add("--includeToolRag=" + this.includeToolRag);

        // Additional options
        ragPomCliArgs.add("--includePgmlIndexer=" + this.includePgmlIndexer);
        ragPomCliArgs.add("--includeModelStaging=" + this.includeModelStaging);
        ragPomCliArgs.add("--buildNative=" + this.buildNative);

        // Archive options
        ragPomCliArgs.add("--modelSourceType=" + this.modelSourceType);
        if (this.archivePath != null && this.archivePath.exists()) {
            ragPomCliArgs.add("--archivePath=" + this.archivePath.getAbsolutePath());
        }
        if (this.registryUrls != null && !this.registryUrls.isEmpty()) {
            ragPomCliArgs.add("--registryUrls=" + String.join(",", this.registryUrls));
        }
        ragPomCliArgs.add("--archiveOnly=" + this.archiveOnly);

        // UI customization
        ragPomCliArgs.add("--appTitle=" + this.appTitle);

        int pomGenExitCode = new CommandLine(new RagPomGenerator()).execute(ragPomCliArgs.toArray(new String[0]));

        if (pomGenExitCode != 0) {
            System.err.println("Instance POM generation failed using RagPomGenerator. Exit code: " + pomGenExitCode);
            return 1;
        }
        if (!instancePomFile.exists()) {
            System.err.println("Generated instance POM file not found: " + instancePomFile.getAbsolutePath());
            return 1;
        }

        // Copy archive to resources if specified
        if (this.archivePath != null && this.archivePath.exists()) {
            File resourcesDir = new File(projectBuildDir, "src/main/resources/models");
            if (!resourcesDir.exists() && !resourcesDir.mkdirs()) {
                System.err.println("Failed to create resources/models directory");
                return 1;
            }
            File destArchive = new File(resourcesDir, this.archivePath.getName());
            System.out.println("Embedding archive: " + this.archivePath.getName() + " -> " + destArchive.getAbsolutePath());
            FileUtils.copyFile(this.archivePath, destArchive);

            // Create application.properties with archive configuration
            File appPropsDir = new File(projectBuildDir, "src/main/resources");
            File appPropsFile = new File(appPropsDir, "application-archive.properties");
            StringBuilder propsContent = new StringBuilder();
            propsContent.append("# Auto-generated archive configuration\n");
            propsContent.append("kompile.models.source-type=").append(archiveOnly ? "ARCHIVE" : modelSourceType).append("\n");
            propsContent.append("kompile.models.embedded-archive=classpath:models/").append(this.archivePath.getName()).append("\n");
            propsContent.append("kompile.models.auto-extract-embedded=true\n");
            propsContent.append("kompile.models.allow-fallback=").append(!archiveOnly).append("\n");
            propsContent.append("kompile.models.verify-checksums=true\n");
            if (this.registryUrls != null && !this.registryUrls.isEmpty()) {
                for (int i = 0; i < this.registryUrls.size(); i++) {
                    propsContent.append("kompile.models.registry-urls[").append(i).append("]=").append(this.registryUrls.get(i)).append("\n");
                }
            }
            FileUtils.writeStringToFile(appPropsFile, propsContent.toString(), "UTF-8");
            System.out.println("Created archive configuration: " + appPropsFile.getAbsolutePath());
        }

        InvocationRequest request = new DefaultInvocationRequest();
        request.setPomFile(instancePomFile);
        List<String> goals = new ArrayList<>();

        if(javacppPlatform != null && !javacppPlatform.isEmpty()) {
            goals.add("-Djavacpp.platform=" + javacppPlatform);
            if(javacppExtension != null && !javacppExtension.isEmpty())
                goals.add("-Djavacpp.platform.extension=" + javacppExtension);
            goals.add("-Dorg.eclipse.python4j.numpyimport=false");
            goals.add("clean");
            goals.add("package");
            request.setGoals(goals);
        }
        else {
            request.setGoals(Arrays.asList("clean","package"));

        }


        Properties systemProperties = new Properties();
        if (skipTests) {
            systemProperties.setProperty("skipTests", "true");
        } else if (buildNative) {
            systemProperties.setProperty("skipTests", "false");
            System.out.println("Note: Tests are configured to run for native builds (skipTests=false) to aid AOT, unless --skipTests is true.");
        }
        request.setProperties(systemProperties);

        if (buildNative) {
            request.setProfiles(Arrays.asList("native"));
            File effectiveGraalVmHome = (this.graalVmHome != null && this.graalVmHome.exists()) ?
                    this.graalVmHome : Info.graalvmDirectory();

            if (effectiveGraalVmHome != null && effectiveGraalVmHome.exists()) {
                System.out.println("Using GraalVM from: " + effectiveGraalVmHome.getAbsolutePath() + " for native build.");
                request.setJavaHome(effectiveGraalVmHome);
            } else {
                System.err.println("Error: GraalVM home not specified or default not found (" +
                        (Info.graalvmDirectory() == null ? "null" : Info.graalvmDirectory().getAbsolutePath()) +
                        "). Native build requires GraalVM to be set via --graalVmHome or accessible via Info.graalvmDirectory().");
                return 1;
            }
        }

        Invoker invoker = new DefaultInvoker();
        File effectiveMavenHome = (this.mavenHome != null && this.mavenHome.exists()) ?
                this.mavenHome : EnvironmentUtils.defaultMavenHome();

        if (effectiveMavenHome == null || !effectiveMavenHome.exists()) {
            System.err.println("Error: Maven home not specified or default not found. Build requires Maven.");
            System.err.println("Please set M2_HOME, MAVEN_HOME, or specify --mavenHome.");
            return 1;
        }

        request.setMavenOpts("-Dfile.encoding=UTF-8");

        invoker.setMavenHome(effectiveMavenHome);
        invoker.setWorkingDirectory(projectBuildDir);

        invoker.setOutputHandler(System.out::println);
        invoker.setErrorHandler(System.err::println);


        System.out.println("Starting Maven build for RAG instance: " + configName);
        System.out.println("  Build Directory: " + projectBuildDir.getAbsolutePath());
        System.out.println("  POM File: " + instancePomFile.getName());
        System.out.println("  Maven Goals: " + goals);
        if (buildNative) System.out.println("  Native Profile: Activated");
        if (skipTests) System.out.println("  Tests: SKIPPED");
        System.out.println("  Maven Home: " + effectiveMavenHome.getAbsolutePath());

        // Print enabled modules for better visibility  
        printEnabledModules();

        InvocationResult result = invoker.execute(request);

        if (result.getExitCode() != 0) {
            System.err.println("RAG application build failed! Exit Code: " + result.getExitCode());
            if (result.getExecutionException() != null) {
                System.err.println("Maven Invocation Exception: ");
                result.getExecutionException().printStackTrace(System.err);
            }
            return 1;
        }

        System.out.println("RAG application build successful!");
        File targetDir = new File(projectBuildDir, "target");

        String finalArtifactName;
        String artifactType;

        if (buildNative) {
            // The native executable name is based on the native.image.name property in the POM
            finalArtifactName = this.configName + "-native"; // This aligns with the <native.image.name> property
            artifactType = "Native Executable";
            File nativeExecutable = new File(targetDir, finalArtifactName); // On Linux/macOS
            File nativeExecutableExe = new File(targetDir, finalArtifactName + ".exe"); // On Windows

            if (nativeExecutable.exists()) {
                System.out.println("  " + artifactType + ": " + nativeExecutable.getAbsolutePath());
                System.out.println("  To run (" + artifactType + "): " + nativeExecutable.getAbsolutePath());
            } else if (nativeExecutableExe.exists()) {
                System.out.println("  " + artifactType + ": " + nativeExecutableExe.getAbsolutePath());
                System.out.println("  To run (" + artifactType + "): " + nativeExecutableExe.getAbsolutePath());
            }
            else {
                System.out.println("  " + artifactType + " expected at: " + nativeExecutable.getAbsolutePath() + " (or .exe) but not found. Check build logs.");
            }
            File classifiedJar = new File(targetDir, this.configName + "-" + this.instanceVersion + "-exec.jar");
            if(classifiedJar.exists()){
                System.out.println("  Input classified JAR for native image: " + classifiedJar.getAbsolutePath());
            }

        } else {
            finalArtifactName = this.configName + "-" + this.instanceVersion + ".jar";
            artifactType = "Executable JAR";
            File appJar = new File(targetDir, finalArtifactName);
            if(appJar.exists()) {
                System.out.println("  " + artifactType + ": " + appJar.getAbsolutePath());
                System.out.println("  To run (" + artifactType + "): java -jar " + appJar.getAbsolutePath());
            } else {
                System.out.println("  " + artifactType + " expected at: " + appJar.getAbsolutePath() + " but not found. Check build logs.");
            }
        }
        return 0;
    }

    private void printEnabledModules() {
        System.out.println("  Enabled Modules:");

        // Core modules
        if (includeAppMain) System.out.println("    ✓ App Main (UI & Main Application)");
        if (includeAppCore) System.out.println("    ✓ App Core");
        if (includeLoadersOrchestrator) System.out.println("    ✓ Loaders Orchestrator");

        // Document loaders
        List<String> loaders = new ArrayList<>();
        if (includeLoaderTika) loaders.add("Tika (deprecated)");
        if (includeLoaderPdf) loaders.add("PDF Basic");
        if (includeLoaderMicrosoft) loaders.add("Microsoft Office");
        if (includeLoaderMail) loaders.add("Email/Mail");
        if (includeLoaderPdfExtended) loaders.add("PDF Extended");
        if (!loaders.isEmpty()) {
            System.out.println("    ✓ Document Loaders: " + String.join(", ", loaders));
        }

        // Chunkers
        List<String> chunkers = new ArrayList<>();
        if (includeChunkerSentence) chunkers.add("Sentence (" + String.join(",", supportedLanguages) + ")");
        if (includeChunkerRecursiveCharacter) chunkers.add("Recursive Character");
        if (includeChunkerMarkdown) chunkers.add("Markdown");
        if (includeChunkerToken) chunkers.add("Token");
        if (!chunkers.isEmpty()) {
            System.out.println("    ✓ Chunkers: " + String.join(", ", chunkers));
        }

        // LLMs
        List<String> llms = new ArrayList<>();
        if (includeLlmOpenai) llms.add("OpenAI");
        if (includeLlmAnthropic) llms.add("Anthropic");
        if (includeLlmGemini) llms.add("Gemini");
        if (!llms.isEmpty()) {
            System.out.println("    ✓ LLM Providers: " + String.join(", ", llms));
        }

        // Embeddings
        List<String> embeddings = new ArrayList<>();
        if (includeEmbeddingOpenai) embeddings.add("OpenAI");
        if (includeEmbeddingAnserini) embeddings.add("Anserini (BGE, Arctic Embed, SameDiff)");
        if (includeEmbeddingSentenceTransformer) embeddings.add("Sentence Transformers");
        if (includeEmbeddingPostgresml) embeddings.add("PostgresML");
        if (!embeddings.isEmpty()) {
            System.out.println("    ✓ Embedding Providers: " + String.join(", ", embeddings));
        }

        // Vector stores
        List<String> vectorStores = new ArrayList<>();
        if (includeVectorstoreChroma) vectorStores.add("Chroma");
        if (includeVectorstorePgvector) vectorStores.add("pgvector");
        if(includeVectorStoreAnserini) vectorStores.add("Anserini");

        if (!vectorStores.isEmpty()) {
            System.out.println("    ✓ Vector Stores: " + String.join(", ", vectorStores));
        }

        // Tools
        List<String> tools = new ArrayList<>();
        if (includeToolFilesystem) tools.add("Filesystem");
        if (includeToolRag) tools.add("RAG");
        if (!tools.isEmpty()) {
            System.out.println("    ✓ Tools: " + String.join(", ", tools));
        }

        // Additional
        if (includeAnserini) System.out.println("    ✓ Anserini Search");
        if (includePgmlIndexer) System.out.println("    ✓ PostgresML Indexer");
        if (includeModelStaging) System.out.println("    ✓ Model Staging");

        // Archive configuration
        if (archivePath != null && archivePath.exists()) {
            System.out.println("    ✓ Embedded Archive: " + archivePath.getName());
            System.out.println("      Model Source: " + (archiveOnly ? "ARCHIVE (offline mode)" : modelSourceType));
        } else if (registryUrls != null && !registryUrls.isEmpty()) {
            System.out.println("    ✓ Registry URLs: " + String.join(", ", registryUrls));
        }

        System.out.println();
    }

    public static void main(String... args) {
        System.exit(new CommandLine(new BuildRagApp()).execute(args));
    }
}