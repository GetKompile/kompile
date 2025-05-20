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

    @Option(names = {"--ragMcpVersion"}, description = "Version of the ai.kompile:rag-mcp-assistant-parent and its modules", defaultValue = "0.1.0-SNAPSHOT")
    private String ragMcpVersion;

    @Option(names = {"--includeAppMain"}, description = "Include kompile-app-main module (provides UI and main app)", defaultValue = "true", negatable = true)
    private boolean includeAppMain;

    @Option(names = {"--includeAppCore"}, description = "Include kompile-app-core module", defaultValue = "true", negatable = true)
    private boolean includeAppCore;
    @Option(names = {"--includeLoadersOrchestrator"}, description = "Include kompile-app-loaders-orchestrator module", defaultValue = "true", negatable = true)
    private boolean includeLoadersOrchestrator;
    @Option(names = {"--includeLoaderTika"}, description = "Include kompile-loader-tika module")
    private boolean includeLoaderTika = true;
    @Option(names = {"--includeLoaderPdf"}, description = "Include kompile-loader-pdf module")
    private boolean includeLoaderPdf = true;
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

    @Option(names = {"--native"}, description = "Build a GraalVM native image")
    private boolean buildNative = false;

    @Option(names = {"--mavenHome"}, description = "Path to Maven installation (default: resolved by EnvironmentUtils)")
    private File mavenHome;

    @Option(names = {"--graalVmHome"}, description = "Path to GraalVM installation (for native builds; default: resolved by Info.graalvmDirectory())")
    private File graalVmHome;

    @Option(names = {"--cleanBuild"}, description = "Perform a clean Maven build", defaultValue = "true")
    private boolean cleanBuild;

    @Option(names = {"--skipTests"}, description = "Skip Maven tests during the build", defaultValue = "true")
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

        File projectBuildDir = new File(buildInstanceDir, "project");
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
        ragPomCliArgs.add("--includeAnserini=" + this.includeAnserini);
        ragPomCliArgs.add("--includeLlmOpenai=" + this.includeLlmOpenai);
        ragPomCliArgs.add("--includeLlmAnthropic=" + this.includeLlmAnthropic);
        ragPomCliArgs.add("--includeLlmGemini=" + this.includeLlmGemini);
        ragPomCliArgs.add("--includeEmbeddingOpenai=" + this.includeEmbeddingOpenai);
        ragPomCliArgs.add("--includeEmbeddingSentenceTransformer=" + this.includeEmbeddingSentenceTransformer);
        ragPomCliArgs.add("--includeVectorstoreChroma=" + this.includeVectorstoreChroma);
        ragPomCliArgs.add("--includeVectorstorePgvector=" + this.includeVectorstorePgvector);
        ragPomCliArgs.add("--includeToolFilesystem=" + this.includeToolFilesystem);
        ragPomCliArgs.add("--includeToolRag=" + this.includeToolRag);
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

        if (skipTests) {
            request.addArg("-DskipTests=true");
        } else if (buildNative) {
            System.out.println("Note: Tests are not skipped by default for native builds to aid AOT, unless --skipTests is explicitly specified by the user.");
            request.addArg("-DskipTests=false");
        }

        if (buildNative) {
            request.setProfiles(Arrays.asList("native")); // Corrected: Use setProfiles
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

        StringBuilder buildOutput = new StringBuilder();
        invoker.setOutputHandler(line -> {
            System.out.println(line);
            buildOutput.append(line).append(System.lineSeparator());
        });
        invoker.setErrorHandler(line -> {
            System.err.println(line);
            buildOutput.append("ERROR: ").append(line).append(System.lineSeparator());
        });

        System.out.println("Starting Maven build for RAG instance: " + configName);
        System.out.println("  Build Directory: " + projectBuildDir.getAbsolutePath());
        System.out.println("  POM File: " + instancePomFile.getName());
        System.out.println("  Maven Goals: " + goals);
        if (buildNative) System.out.println("  Native Profile: Activated (via -Pnative)");
        System.out.println("  Maven Home: " + effectiveMavenHome.getAbsolutePath());

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
        String jarNameBase = configName + "-" + this.instanceVersion;
        String finalJarName = jarNameBase + ".jar";

        if (buildNative) {
            // Try to get native.image.name from generated pom properties if possible, else default
            // This requires parsing the generated pom, or relying on RagPomGenerator to use a predictable name.
            // For now, using the default pattern.
            String nativeImageName = jarNameBase + "-native";
            File nativeExecutable = new File(targetDir, nativeImageName);
            if (nativeExecutable.exists()) {
                System.out.println("  Native Executable: " + nativeExecutable.getAbsolutePath());
                System.out.println("  To run (Native): " + nativeExecutable.getAbsolutePath());
            } else {
                System.out.println("  Native Executable expected at: " + nativeExecutable.getAbsolutePath() + " but not found. Check build logs.");
            }
        }

        File appJar = new File(targetDir, finalJarName);
        if(appJar.exists()) {
            System.out.println("  Executable JAR: " + appJar.getAbsolutePath());
            System.out.println("  To run (JAR): java -jar " + appJar.getAbsolutePath());
        } else {
            System.out.println("  Executable JAR expected at: " + appJar.getAbsolutePath() + " but not found. Check build logs for why the primary artifact was not created (e.g., if Shade plugin was meant to create it but failed).");
        }

        return 0;
    }

    public static void main(String... args) {
        new CommandLine(new BuildRagApp()).execute(args);
    }
}