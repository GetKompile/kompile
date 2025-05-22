/*
 * Copyright 2025 Kompile Inc.
 * *
 * * Licensed under the Apache License, Version 2.0 (the "License");
 * * you may not use this file except in compliance with the License.
 * * You may obtain a copy of the License at
 * *
 * * http://www.apache.org/licenses/LICENSE-2.0
 * *
 * * Unless required by applicable law or agreed to in writing, software
 * * distributed under the License is distributed on an "AS IS" BASIS,
 * * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * * See the License for the specific language governing permissions and
 * * limitations under the License.
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
import java.util.stream.Collectors;

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
    @Option(names = {"--includeLoaderTika"}, description = "Include kompile-loader-tika module")
    private boolean includeLoaderTika = false;
    @Option(names = {"--includeLoaderPdf"}, description = "Include kompile-loader-pdf module")
    private boolean includeLoaderPdf = false;

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


    @Option(names = {"--includeAnserini"}, description = "Include kompile-app-anserini module")
    private boolean includeAnserini = false;
    @Option(names = {"--includeLlmOpenai"}, description = "Include kompile-app-openai-llm module")
    private boolean includeLlmOpenai = false;
    @Option(names = {"--includeLlmAnthropic"}, description = "Include kompile-app-anthropic-llm module")
    private boolean includeLlmAnthropic = false;
    @Option(names = {"--includeLlmGemini"}, description = "Include kompile-app-gemini-llm module")
    private boolean includeLlmGemini = false;
    @Option(names = {"--includeEmbeddingOpenai"}, description = "Include kompile-embedding-openai module")
    private boolean includeEmbeddingOpenai = false;
    @Option(names = {"--includeEmbeddingSentenceTransformer"}, description = "Include kompile-embedding-sentence-transformer module")
    private boolean includeEmbeddingSentenceTransformer = false;
    @Option(names = {"--includeVectorstoreChroma"}, description = "Include kompile-vectorstore-chroma module")
    private boolean includeVectorstoreChroma = false;
    @Option(names = {"--includeVectorstorePgvector"}, description = "Include kompile-vectorstore-pgvector module")
    private boolean includeVectorstorePgvector = false;
    @Option(names = {"--includeToolFilesystem"}, description = "Include kompile-tool-filesystem module", defaultValue = "true", negatable = true)
    private boolean includeToolFilesystem;
    @Option(names = {"--includeToolRag"}, description = "Include kompile-tool-rag module", defaultValue = "true", negatable = true)
    private boolean includeToolRag;
    @Option(names = {"--includeEmbeddingPostgresml"}, description = "Include kompile-embedding-postgresml module")
    private boolean includeEmbeddingPostgresml = false;
    @Option(names = {"--includePgmlIndexer"}, description = "Include kompile-app-pgml-indexer module")
    private boolean includePgmlIndexer = false;


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

        ragPomCliArgs.add("--includeAppMain=" + this.includeAppMain);
        ragPomCliArgs.add("--includeAppCore=" + this.includeAppCore);
        ragPomCliArgs.add("--includeLoadersOrchestrator=" + this.includeLoadersOrchestrator);
        ragPomCliArgs.add("--includeLoaderTika=" + this.includeLoaderTika);
        ragPomCliArgs.add("--includeLoaderPdf=" + this.includeLoaderPdf);

        ragPomCliArgs.add("--includeChunkerSentence=" + this.includeChunkerSentence);
        if (this.includeChunkerSentence && this.supportedLanguages != null && !this.supportedLanguages.isEmpty()) {
            ragPomCliArgs.add("--supportedLanguages=" + String.join(",", this.supportedLanguages));
        }
        ragPomCliArgs.add("--includeChunkerRecursiveCharacter=" + this.includeChunkerRecursiveCharacter);
        ragPomCliArgs.add("--includeChunkerMarkdown=" + this.includeChunkerMarkdown);
        ragPomCliArgs.add("--includeChunkerToken=" + this.includeChunkerToken);

        ragPomCliArgs.add("--includeAnserini=" + this.includeAnserini);
        ragPomCliArgs.add("--includeLlmOpenai=" + this.includeLlmOpenai);
        ragPomCliArgs.add("--includeLlmAnthropic=" + this.includeLlmAnthropic);
        ragPomCliArgs.add("--includeLlmGemini=" + this.includeLlmGemini);
        ragPomCliArgs.add("--includeEmbeddingOpenai=" + this.includeEmbeddingOpenai);
        ragPomCliArgs.add("--includeEmbeddingSentenceTransformer=" + this.includeEmbeddingSentenceTransformer);
        ragPomCliArgs.add("--includeEmbeddingPostgresml=" + this.includeEmbeddingPostgresml);
        ragPomCliArgs.add("--includeVectorstoreChroma=" + this.includeVectorstoreChroma);
        ragPomCliArgs.add("--includeVectorstorePgvector=" + this.includeVectorstorePgvector);
        ragPomCliArgs.add("--includeToolFilesystem=" + this.includeToolFilesystem);
        ragPomCliArgs.add("--includeToolRag=" + this.includeToolRag);
        ragPomCliArgs.add("--includePgmlIndexer=" + this.includePgmlIndexer);
        ragPomCliArgs.add("--buildNative=" + this.buildNative);


        int pomGenExitCode = new CommandLine(new RagPomGenerator()).execute(ragPomCliArgs.toArray(new String[0]));

        if (pomGenExitCode != 0) {
            System.err.println("Instance POM generation failed using RagPomGenerator. Exit code: " + pomGenExitCode);
            return 1;
        }
        if (!instancePomFile.exists()) {
            System.err.println("Generated instance POM file not found: " + instancePomFile.getAbsolutePath());
            return 1;
        }

        InvocationRequest request = new DefaultInvocationRequest();
        request.setPomFile(instancePomFile);

        List<String> goals = new ArrayList<>();
        if (cleanBuild) {
            goals.add("clean");
        }
        goals.add("package");

        request.setGoals(goals);

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
        invoker.setMavenHome(effectiveMavenHome);
        invoker.setWorkingDirectory(projectBuildDir);

        final StringBuilder buildLogOutput = new StringBuilder();
        invoker.setOutputHandler(line -> {
            System.out.println(line);
            buildLogOutput.append(line).append(System.lineSeparator());
        });
        invoker.setErrorHandler(line -> {
            System.err.println(line);
            buildLogOutput.append("ERROR: ").append(line).append(System.lineSeparator());
        });

        System.out.println("Starting Maven build for RAG instance: " + configName);
        System.out.println("  Build Directory: " + projectBuildDir.getAbsolutePath());
        System.out.println("  POM File: " + instancePomFile.getName());
        System.out.println("  Maven Goals: " + goals);
        if (buildNative) System.out.println("  Native Profile: Activated");
        if (skipTests) System.out.println("  Tests: SKIPPED");
        System.out.println("  Maven Home: " + effectiveMavenHome.getAbsolutePath());


        InvocationResult result = invoker.execute(request);

        if (result.getExitCode() != 0) {
            System.err.println("RAG application build failed! Exit Code: " + result.getExitCode());
            if (result.getExecutionException() != null) {
                System.err.println("Maven Invocation Exception: ");
                result.getExecutionException().printStackTrace(System.err);
            }
            // Optionally write buildLogOutput to a file here
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

    public static void main(String... args) {
        System.exit(new CommandLine(new BuildRagApp()).execute(args));
    }
}