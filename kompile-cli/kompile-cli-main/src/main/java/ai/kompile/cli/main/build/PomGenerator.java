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

// getkompile/kompile/kompile-ag_new_kompile_cli/kompile-cli/src/main/java/ai/kompile/cli/main/build/PomGenerator.java
package ai.kompile.cli.main.build;

import ai.kompile.cli.main.Info;
import ai.kompile.cli.main.pomfileappender.PomFileAppender;
import ai.kompile.cli.main.pomfileappender.impl.KompileApacheCommonsPomFileAppender;
import ai.kompile.cli.main.pomfileappender.impl.KompileJavaCppPomFileAppender;
import ai.kompile.cli.main.pomfileappender.impl.KompileJodaPomFileAppender;
import ai.kompile.cli.main.pomfileappender.impl.KompileNd4jClassLoadingPomFileAppender;
import ai.kompile.cli.main.pomfileappender.impl.KompileOpenblasPomFileAppender;
import ai.kompile.cli.main.pomfileappender.impl.KompilePythonPomFileAppender;
import ai.kompile.cli.main.pomfileappender.impl.KompileSunXmlFileAppender;

import ai.kompile.pipelines.framework.api.Pipeline;
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.core.config.GenericStepConfig;
import ai.kompile.pipelines.framework.core.data.serde.ObjectMappers;
import ai.kompile.pipelines.framework.runtime.pipeline.SequencePipeline;
import ai.kompile.pipelines.framework.runtime.pipeline.graph.GraphPipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import org.apache.maven.model.*;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import picocli.CommandLine;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@CommandLine.Command(name = "pom-generate", mixinStandardHelpOptions = false,
        description = "Generates a pom.xml for building Kompile applications or pipelines.")
@Getter
@Setter
public class PomGenerator implements Callable<Void> {

    // --- Configuration Fields (set by KompileApplicationBuilder) ---
    private boolean assembly;
    private String imageName = "kompile-app";
    private String mainClass;
    private String[] nativeImageJvmArgs;
    private String extraDependencies;
    private String includeResources;
    private String nd4jBackend = "nd4j-native";
    private String nd4jBackendClassifier = "";
    private boolean numpySharedLibrary;
    private File outputFile = new File("pom.xml");
    private boolean debugNative = false;
    private int debugNativePort = 8000;
    private String pipelineJsonFilePathForAnalysis;
    private String pipelineResourcePath = "kompile_pipeline.json";

    private String appType = "pipeline-executor";
    private String llmProvider;
    private String vectorStoreProvider;
    private String embeddingProvider;
    private String documentLoaderProvider;
    private boolean enableRagService;
    private boolean enableFilesystemTool;
    private boolean enableFrontendBuild;

    // Versions from Info.java
    private String kompileParentVersion = Info.getVersion();
    private String ragMcpAssistantParentVersion = Info.getKompileAppVersion();
    private String kompilePipelinesVersion = Info.getKompilePipelinesVersion();
    private String kompileAppVersion = Info.getKompileAppVersion();
    private String springBootVersion = Info.getSpringBootVersion();
    private String springAiVersion = Info.getSpringAiVersion();
    private String nativeImagePluginVersion = Info.getNativeImagePluginVersion();

    // Flags for pipeline step types (fallback or direct config)
    private boolean python, onnx, tvm, doc, dl4j, samediff, tensorflow, image;
    private boolean server; // Derived from appType

    // ND4J Native Build Params
    private String nd4jExtension, nd4jOperations, nd4jDataTypes;
    private boolean nd4jUseLto;

    private Model model;
    private List<Dependency> resolvedDependencies = new ArrayList<>();

    // --- Custom mutators ---
    public void addNativeImageJvmArgIfMissing(String arg) {
        if (this.nativeImageJvmArgs == null) this.nativeImageJvmArgs = new String[0];
        List<String> argsList = new ArrayList<>(Arrays.asList(this.nativeImageJvmArgs));
        String argPrefix = arg.contains("=") ? arg.substring(0, arg.indexOf("=") + 1) : arg;
        boolean alreadyExists = argsList.stream().anyMatch(existingArg ->
                existingArg.startsWith(argPrefix) || existingArg.equals(arg)
        );
        if (!alreadyExists) {
            argsList.add(arg);
        }
        this.nativeImageJvmArgs = argsList.toArray(new String[0]);
    }
    // Alias setters where the setter name differs from the standard Lombok-generated name
    public void setPipelinePath(String resourcePath) { this.pipelineResourcePath = resourcePath; }
    public void setKompileVersion(String version) { this.kompileParentVersion = version; }

    private void addDependency(List<Dependency> addTo, String groupId, String artifactId, String version, String scope, String classifier, boolean optional) {
        Dependency dependency = new Dependency();
        dependency.setGroupId(groupId);
        dependency.setArtifactId(artifactId);
        if (version != null && (model.getParent() == null || !parentManagesVersion(model.getParent(), groupId, artifactId))) {
            dependency.setVersion(version);
        }
        if (scope != null && !scope.isEmpty()) dependency.setScope(scope);
        if (classifier != null && !classifier.isEmpty()) dependency.setClassifier(classifier);
        if (optional) {
            dependency.setOptional(true);
        }

        boolean exists = addTo.stream().anyMatch(d ->
                d.getGroupId().equals(groupId) &&
                        d.getArtifactId().equals(artifactId) &&
                        Objects.equals(d.getClassifier(), classifier)
        );
        if(!exists) {
            addTo.add(dependency);
        }
    }

    private boolean parentManagesVersion(Parent parent, String groupId, String artifactId) {
        if (parent == null) return false;
        if ("kompile-app".equals(parent.getArtifactId()) && "ai.kompile".equals(parent.getGroupId())) {
            return groupId.startsWith("ai.kompile") ||
                    groupId.startsWith("org.springframework.boot") ||
                    groupId.startsWith("org.springframework.ai") ||
                    groupId.startsWith("org.eclipse.deeplearning4j") ||
                    groupId.startsWith("org.deeplearning4j") ||
                    groupId.startsWith("ch.qos.logback") ||
                    groupId.startsWith("org.slf4j") ||
                    groupId.startsWith("org.graalvm");
        }
        if ("kompile".equals(parent.getArtifactId()) && "ai.kompile".equals(parent.getGroupId())) {
            return groupId.startsWith("ai.kompile") ||
                    groupId.startsWith("org.eclipse.deeplearning4j") ||
                    groupId.startsWith("org.deeplearning4j") ||
                    groupId.startsWith("ch.qos.logback") ||
                    groupId.startsWith("org.slf4j") ||
                    groupId.startsWith("org.graalvm");
        }
        return false;
    }

    private void addKompilePipelineStepDependency(List<Dependency> addTo, String stepArtifactIdSuffix) {
        addDependency(addTo, "ai.kompile", "kompile-pipelines-steps-" + stepArtifactIdSuffix, this.kompilePipelinesVersion, "compile", null, false);
    }
    private void addKompileAppProviderDependency(List<Dependency> addTo, String providerModuleSuffix) {
        addDependency(addTo, "ai.kompile", "kompile-app-" + providerModuleSuffix, this.kompileAppVersion, "compile", null, false);
    }
    private void addKompileEmbeddingProviderDependency(List<Dependency> addTo, String providerModuleSuffix) {
        addDependency(addTo, "ai.kompile", "kompile-embedding-" + providerModuleSuffix, this.kompileAppVersion, "compile", null, false);
    }
    private void addKompileVectorStoreProviderDependency(List<Dependency> addTo, String providerModuleSuffix) {
        addDependency(addTo, "ai.kompile", "kompile-vectorstore-" + providerModuleSuffix, this.kompileAppVersion, "compile", null, false);
    }
    private void addKompileLoaderProviderDependency(List<Dependency> addTo, String providerModuleSuffix) {
        addDependency(addTo, "ai.kompile", "kompile-loader-" + providerModuleSuffix, this.kompileAppVersion, "compile", null, false);
    }
    private void addKompileToolDependency(List<Dependency> addTo, String toolModuleSuffix) {
        addDependency(addTo, "ai.kompile", "kompile-tool-" + toolModuleSuffix, this.kompileAppVersion, "compile", null, false);
    }

    private void addDefaultFrameworkAndAppCoreDependencies(List<Dependency> deps) {
        addDependency(deps, "ai.kompile", "kompile-pipelines-framework-api", this.kompilePipelinesVersion, "compile", null, false);
        addDependency(deps, "ai.kompile", "kompile-pipelines-framework-core", this.kompilePipelinesVersion, "compile", null, false);
        addDependency(deps, "ai.kompile", "kompile-pipelines-framework-runtime", this.kompilePipelinesVersion, "compile", null, false);
        addDependency(deps, "ch.qos.logback", "logback-classic", null, "compile", null, false);
        addDependency(deps, "org.slf4j", "slf4j-api", null, "compile", null, false);
        addDependency(deps, "org.graalvm.sdk", "graal-sdk", null, "provided", null, false);
        addDependency(deps, "org.graalvm.nativeimage", "svm", null, "provided", null, false);

        if ("kompile-spring-boot-webapp".equalsIgnoreCase(this.appType)) {
            addDependency(deps, "ai.kompile", "kompile-app-main", this.kompileAppVersion, "compile", null, false);
            if (this.enableRagService) {
                addDependency(deps, "org.springframework.ai", "spring-ai-starter-mcp-client", null, "compile", null, false);
                addDependency(deps, "org.springframework.ai", "spring-ai-starter-mcp-server", null, "compile", null, false);
            }
        }
    }

    private void addProviderDependencies(List<Dependency> deps) {
        if (this.llmProvider != null && !this.llmProvider.equalsIgnoreCase("noop") && !this.llmProvider.equalsIgnoreCase("none")) {
            String suffix = this.llmProvider.toLowerCase();
            if (!suffix.endsWith("-llm")) suffix += "-llm";
            addKompileAppProviderDependency(deps, suffix);
        }
        if (this.embeddingProvider != null && !this.embeddingProvider.equalsIgnoreCase("noop") && !this.embeddingProvider.equalsIgnoreCase("none")) {
            addKompileEmbeddingProviderDependency(deps, this.embeddingProvider.toLowerCase());
        }
        if (this.vectorStoreProvider != null && !this.vectorStoreProvider.equalsIgnoreCase("noop") && !this.vectorStoreProvider.equalsIgnoreCase("none")) {
            addKompileVectorStoreProviderDependency(deps, this.vectorStoreProvider.toLowerCase());
        }
        if (this.documentLoaderProvider != null && !this.documentLoaderProvider.equalsIgnoreCase("none")) {
            addKompileLoaderProviderDependency(deps, this.documentLoaderProvider.toLowerCase());
        }
        if (this.enableRagService) addKompileToolDependency(deps, "rag");
        if (this.enableFilesystemTool) addKompileToolDependency(deps, "filesystem");
    }

    private void addPipelineStepDependenciesFromPipelineJson(List<Dependency> deps) {
        if (this.pipelineJsonFilePathForAnalysis == null || this.pipelineJsonFilePathForAnalysis.isEmpty()) {
            System.out.println("No pipeline JSON provided for analysis, using CLI flags for step dependencies.");
            addPipelineStepDependenciesFromCliFlags(deps);
            return;
        }
        File pipelineJsonFile = new File(this.pipelineJsonFilePathForAnalysis);
        if (!pipelineJsonFile.exists()) {
            System.err.println("Pipeline JSON file not found for dependency analysis: " + this.pipelineJsonFilePathForAnalysis + ". Falling back to CLI flags.");
            addPipelineStepDependenciesFromCliFlags(deps);
            return;
        }

        try {
            ObjectMapper mapper = ObjectMappers.getJsonMapper();
            Pipeline parsedPipeline;
            try {
                parsedPipeline = mapper.readValue(pipelineJsonFile, SequencePipeline.class);
            } catch (Exception eSeq) {
                try {
                    parsedPipeline = mapper.readValue(pipelineJsonFile, GraphPipeline.class);
                } catch (Exception eGraph) {
                    System.err.println("Failed to parse pipeline JSON as SequencePipeline or GraphPipeline: " + eGraph.getMessage());
                    throw new IOException("Unsupported pipeline JSON structure or invalid format.", eGraph);
                }
            }

            List<StepConfig> stepConfigs = parsedPipeline.getSteps();

            Set<String> addedStepModules = new HashSet<>();
            for (StepConfig stepConfig : stepConfigs) {
                String runnerClassName = null;
                String symbolicType = null;

                if (stepConfig instanceof GenericStepConfig) {
                    runnerClassName = ((GenericStepConfig) stepConfig).runnerClassName();
                    symbolicType = ((GenericStepConfig) stepConfig).type();
                }

                String stepModule = null;
                if (runnerClassName != null && !runnerClassName.isEmpty()) {
                    if (runnerClassName.startsWith("ai.kompile.pipelines.steps.python.")) stepModule = "python";
                    else if (runnerClassName.startsWith("ai.kompile.pipelines.steps.onnx.")) stepModule = "onnx";
                    else if (runnerClassName.startsWith("ai.kompile.pipelines.steps.deeplearning4j.")) stepModule = "deeplearning4j";
                    else if (runnerClassName.startsWith("ai.kompile.pipelines.steps.samediff.")) stepModule = "samediff";
                    else if (runnerClassName.startsWith("ai.kompile.pipelines.steps.tensorflow.")) stepModule = "tensorflow";
                    else if (runnerClassName.startsWith("ai.kompile.pipelines.steps.tvm.")) stepModule = "tvm";
                    else if (runnerClassName.startsWith("ai.kompile.pipelines.steps.image.")) stepModule = "image";
                    else if (runnerClassName.startsWith("ai.kompile.pipelines.steps.documentparser.")) stepModule = "document-parser";
                } else if (symbolicType != null && !symbolicType.isEmpty()) {
                    String lowerType = symbolicType.toLowerCase();
                    if (("python".equals(lowerType) || "py".equals(lowerType))) stepModule = "python";
                    else if ("onnx".equals(lowerType)) stepModule = "onnx";
                    else if ("dl4j".equals(lowerType)) stepModule = "deeplearning4j";
                    else if ("samediff".equals(lowerType)) stepModule = "samediff";
                    else if ("tensorflow".equals(lowerType)) stepModule = "tensorflow";
                    else if ("tvm".equals(lowerType)) stepModule = "tvm";
                    else if ("image_to_ndarray".equals(lowerType) || "image".equals(lowerType)) stepModule = "image";
                    else if ("document_parser".equals(lowerType) || "doc".equals(lowerType)) stepModule = "document-parser";
                }

                if (stepModule != null && addedStepModules.add(stepModule)) {
                    addKompilePipelineStepDependency(deps, stepModule);
                }
            }
        } catch (IOException e) {
            System.err.println("Error parsing pipeline JSON " + this.pipelineJsonFilePathForAnalysis + " for step dependencies: " + e.getMessage() + ". Falling back to CLI flags.");
            addPipelineStepDependenciesFromCliFlags(deps);
        }
    }

    private void addPipelineStepDependenciesFromCliFlags(List<Dependency> deps) {
        if (this.python) addKompilePipelineStepDependency(deps, "python");
        if (this.onnx) addKompilePipelineStepDependency(deps, "onnx");
        if (this.dl4j) addKompilePipelineStepDependency(deps, "deeplearning4j");
        if (this.samediff) addKompilePipelineStepDependency(deps, "samediff");
        if (this.tensorflow) addKompilePipelineStepDependency(deps, "tensorflow");
        if (this.tvm) addKompilePipelineStepDependency(deps, "tvm");
        if (this.image) addKompilePipelineStepDependency(deps, "image");
        if (this.doc) addKompilePipelineStepDependency(deps, "document-parser");
    }

    private List<PomFileAppender> getActivePomFileAppenders() {
        List<PomFileAppender> appenders = new ArrayList<>();
        appenders.add(new KompileApacheCommonsPomFileAppender());
        appenders.add(new KompileJavaCppPomFileAppender());
        appenders.add(new KompileJodaPomFileAppender());
        appenders.add(new KompileSunXmlFileAppender());

        boolean hasPython = resolvedDependencies.stream().anyMatch(d -> "kompile-pipelines-steps-python".equals(d.getArtifactId()));
        boolean hasNd4j = resolvedDependencies.stream().anyMatch(d -> d.getGroupId().equals("org.eclipse.deeplearning4j"));
        boolean hasOpenBLAS = resolvedDependencies.stream().anyMatch(d -> d.getArtifactId().contains("openblas"));

        if (hasPython) {
            appenders.add(new KompilePythonPomFileAppender());
        }
        if (hasNd4j) {
            appenders.add(new KompileNd4jClassLoadingPomFileAppender());
        }
        if (hasOpenBLAS || (hasNd4j && "nd4j-native".equals(this.nd4jBackend))) {
            appenders.add(new KompileOpenblasPomFileAppender());
        }
        return appenders.stream().distinct().collect(Collectors.toList());
    }

    private String graalBuildArgs() {
        StringBuilder sb = new StringBuilder();
        sb.append("--verbose\n");
        sb.append("--no-fallback\n");
        sb.append("-H:+ReportExceptionStackTraces\n");
        sb.append("-H:DeadlockWatchdogInterval=30\n");
        sb.append("-H:+DeadlockWatchdogExitOnTimeout\n");
        sb.append("-H:+AllowDeprecatedBuilderClassesOnImageClasspath\n");
        sb.append("-H:-CheckToolchain\n");
        sb.append("-H:+AllowIncompleteClasspath\n");

        sb.append("--initialize-at-build-time=org.slf4j.LoggerFactory,ch.qos.logback.classic.LoggerContext,ch.qos.logback.classic.spi.StaticLoggerBinder,ch.qos.logback.core.spi.StatusManager,org.slf4j.helpers\n");

        sb.append("--initialize-at-run-time=io.netty.**,org.bytedeco.**,com.sun.jna.**,org.eclipse.jgit.**\n");
        sb.append("--initialize-at-run-time=ai.kompile.pipelines.**,ai.kompile.app.**\n");
        sb.append("--initialize-at-run-time=org.eclipse.deeplearning4j.linalg.factory.Nd4j,org.eclipse.deeplearning4j.nativeblas.NativeOpsHolder\n");
        sb.append("--initialize-at-run-time=org.apache.pdfbox.pdmodel.font.PDType1Font\n");

        sb.append("-Dorg.bytedeco.javacpp.logger.debug=true\n");
        sb.append("-Dorg.bytedeco.javacpp.nopointergc=true\n");
        sb.append("-Djavacpp.platform=${javacpp.platform}\n");

        sb.append("-H:IncludeResources=META-INF/native-image/.*\\.json$\n");
        sb.append("-H:IncludeResources=ai/kompile/.*\\.schema\\.json$\n");
        sb.append("-H:IncludeResources=.*/org/nd4j/.*\\.(so|dylib|dll|properties|json|txt| 기울기)$\n");
        sb.append("-H:IncludeResources=.*/org/bytedeco/.*\\.(so|dylib|dll|properties|json)$\n");
        sb.append("-H:IncludeResources=META-INF/services/.*$\n");
        sb.append("-H:IncludeResources=logback.xml,logback-test.xml,logging.properties\n");
        sb.append("-H:IncludeResources=reference.conf\n");
        sb.append("-H:IncludeResources=").append(this.pipelineResourcePath).append("$\n");

        if ("kompile-spring-boot-webapp".equalsIgnoreCase(this.appType)) {
            sb.append("-H:IncludeResources=static/.*,templates/.*,META-INF/resources/.*,application.yml,application.properties,banner.txt\n");
            sb.append("-H:IncludeResources=META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports$\n");
            sb.append("-H:IncludeResources=META-INF/spring.factories$\n");
            sb.append("-H:IncludeResources=META-INF/spring.tooling$\n");
            sb.append("-H:IncludeResources=META-INF/additional-spring-configuration-metadata.json$\n");
            sb.append("--initialize-at-run-time=org.springframework.**,jakarta.servlet.**,org.apache.tomcat.**,org.apache.coyote.**,org.hibernate.validator.**,org.apache.catalina.**,org.apache.el.**\n");
            sb.append("-H:-AddAllFileSystemProviders\n");
            if (this.llmProvider != null && this.llmProvider.contains("openai")) {
                sb.append("--initialize-at-run-time=com.knuddels.jtokkit.**\n");
                sb.append("--initialize-at-run-time=com.azure.ai.openai.**\n");
                sb.append("--initialize-at-run-time=com.theokanning.openai.**\n");
            }
            if (this.embeddingProvider != null && this.embeddingProvider.contains("sentence-transformer")) {
                sb.append("--initialize-at-run-time=ai.djl.**\n");
                sb.append("--initialize-at-run-time=org.pytorch.**\n");
            }
        }

        if (this.includeResources != null && !this.includeResources.isEmpty()) {
            for (String resourcePattern : this.includeResources.split(",")) {
                sb.append("-H:IncludeResources=").append(resourcePattern.trim()).append("$\n");
            }
        }

        sb.append("-Dpipeline.path=").append(this.pipelineResourcePath).append("\n");
        sb.append("-Dfile.encoding=UTF-8\n");

        if (this.nativeImageJvmArgs != null) {
            for (String jvmArg : this.nativeImageJvmArgs) {
                String trimmedArg = jvmArg.trim();
                if (!trimmedArg.startsWith("-J")) sb.append("-J");
                sb.append(trimmedArg).append("\n");
            }
        }
        if (this.numpySharedLibrary) sb.append("--shared\n");
        if (this.debugNative) sb.append("--debug-attach=").append(this.debugNativePort).append("\n");

        for (PomFileAppender appender : getActivePomFileAppenders()) {
            appender.append(sb);
            appender.appendReInitialize(sb);
        }
        return sb.toString().trim();
    }

    private void addBuildConfiguration(Model model) throws XmlPullParserException, IOException {
        Build build = new Build();
        Extension osMavenPluginExtension = new Extension();
        osMavenPluginExtension.setGroupId("kr.motd.maven");
        osMavenPluginExtension.setArtifactId("os-maven-plugin");
        osMavenPluginExtension.setVersion("${os-maven-plugin.version}");
        build.addExtension(osMavenPluginExtension);

        Plugin compilerPlugin = new Plugin();
        compilerPlugin.setGroupId("org.apache.maven.plugins");
        compilerPlugin.setArtifactId("maven-compiler-plugin");
        compilerPlugin.setVersion("${maven-compiler-plugin.version}");
        String javaVersion = model.getProperties().getProperty("java.version");
        Xpp3Dom compilerConfig = Xpp3DomBuilder.build(new StringReader(
                "<configuration><release>" + javaVersion + "</release></configuration>"
        ));
        compilerPlugin.setConfiguration(compilerConfig);
        build.addPlugin(compilerPlugin);

        Plugin resourcesPlugin = new Plugin();
        resourcesPlugin.setGroupId("org.apache.maven.plugins");
        resourcesPlugin.setArtifactId("maven-resources-plugin");
        resourcesPlugin.setVersion("${maven-resources-plugin.version}");
        build.addPlugin(resourcesPlugin);

        if (this.assembly) {
            Plugin assemblyPlugin = new Plugin();
            assemblyPlugin.setGroupId("org.apache.maven.plugins");
            assemblyPlugin.setArtifactId("maven-assembly-plugin");
            assemblyPlugin.setVersion("${maven-assembly-plugin.version}");
            Xpp3Dom assemblyConfigDom = Xpp3DomBuilder.build(new StringReader(
                    "<configuration><finalName>" + this.imageName + "</finalName><appendAssemblyId>true</appendAssemblyId>" +
                            "<descriptors><descriptor>src/main/assembly/kompile-app-assembly.xml</descriptor></descriptors></configuration>"
            ));
            assemblyPlugin.setConfiguration(assemblyConfigDom);
            PluginExecution assemblyExec = new PluginExecution();
            assemblyExec.setId("make-assembly");
            assemblyExec.setPhase("package");
            assemblyExec.addGoal("single");
            assemblyPlugin.addExecution(assemblyExec);
            build.addPlugin(assemblyPlugin);

        } else if ("kompile-spring-boot-webapp".equalsIgnoreCase(this.appType)) {
            Plugin springBootPlugin = new Plugin();
            springBootPlugin.setGroupId("org.springframework.boot");
            springBootPlugin.setArtifactId("spring-boot-maven-plugin");
            springBootPlugin.setVersion("${spring-boot-maven-plugin.version}");
            springBootPlugin.setConfiguration(Xpp3DomBuilder.build(new StringReader(
                    "<configuration><mainClass>" + this.mainClass + "</mainClass><finalName>" + this.imageName + "</finalName></configuration>"
            )));
            PluginExecution repackageExec = new PluginExecution();
            repackageExec.setId("repackage");
            repackageExec.addGoal("repackage");
            springBootPlugin.addExecution(repackageExec);
            PluginExecution processAotExec = new PluginExecution();
            processAotExec.setId("process-aot");
            processAotExec.addGoal("process-aot");
            springBootPlugin.addExecution(processAotExec);
            build.addPlugin(springBootPlugin);

            if (this.enableFrontendBuild) {
                Plugin frontendPlugin = new Plugin();
                frontendPlugin.setGroupId("com.github.eirslett");
                frontendPlugin.setArtifactId("frontend-maven-plugin");
                frontendPlugin.setVersion("${frontend-maven-plugin.version}");
                String nodeVersion = model.getProperties().getProperty("node.version", "v20.11.1");
                String npmVersion = model.getProperties().getProperty("npm.version", "10.2.4");
                Xpp3Dom frontendConfig = Xpp3DomBuilder.build(new StringReader(
                        "<configuration>" +
                                "<workingDirectory>src/main/frontend</workingDirectory>" +
                                "<installDirectory>${project.build.directory}/frontend-build</installDirectory>" +
                                "<nodeVersion>" + nodeVersion + "</nodeVersion>" +
                                "<npmVersion>" + npmVersion + "</npmVersion>" +
                                "</configuration>"
                ));
                frontendPlugin.setConfiguration(frontendConfig);
                build.addPlugin(frontendPlugin);
            }

            Plugin nativePlugin = new Plugin();
            nativePlugin.setGroupId("org.graalvm.buildtools");
            nativePlugin.setArtifactId("native-maven-plugin");
            nativePlugin.setVersion("${native-maven-plugin.version}");
            nativePlugin.setExtensions(true);

            List<String> finalNativeBuildArgs = new ArrayList<>();
            String[] baseArgs = graalBuildArgs().split("\n");
            for(String arg : baseArgs) if(arg != null && !arg.trim().isEmpty()) finalNativeBuildArgs.add(arg.trim());

            finalNativeBuildArgs.add("--class-path");
            finalNativeBuildArgs.add("${project.build.outputDirectory}${path.separator}${project.build.directory}/spring-aot/main/classes");

            Xpp3Dom nativeConfig = Xpp3DomBuilder.build(new StringReader(
                    "<configuration>" +
                            "<imageName>" + this.imageName + "</imageName>" +
                            "<mainClass>" + this.mainClass + "</mainClass>" +
                            "<quickBuild>false</quickBuild>" +
                            "</configuration>"
            ));
            Xpp3Dom buildArgsNode = new Xpp3Dom("buildArgs");
            for(String arg : finalNativeBuildArgs) {
                Xpp3Dom buildArgNode = new Xpp3Dom("buildArg");
                buildArgNode.setValue(arg);
                buildArgsNode.addChild(buildArgNode);
            }
            nativeConfig.addChild(buildArgsNode);
            nativePlugin.setConfiguration(nativeConfig);

            PluginExecution nativeAddMetaExec = new PluginExecution();
            nativeAddMetaExec.setId("add-reachability-metadata");
            nativeAddMetaExec.addGoal("add-reachability-metadata");
            nativePlugin.addExecution(nativeAddMetaExec);

            PluginExecution nativeBuildExec = new PluginExecution();
            nativeBuildExec.setId("build-native");
            nativeBuildExec.setPhase("package");
            nativeBuildExec.addGoal("compile-no-fork");
            nativePlugin.addExecution(nativeBuildExec);
            build.addPlugin(nativePlugin);

        } else {
            Plugin nativePlugin = new Plugin();
            nativePlugin.setGroupId("org.graalvm.buildtools");
            nativePlugin.setArtifactId("native-maven-plugin");
            nativePlugin.setVersion("${native-maven-plugin.version}");
            nativePlugin.setExtensions(true);
            Xpp3Dom nativeConfig = Xpp3DomBuilder.build(new StringReader(
                    "<configuration>" +
                            "<imageName>" + this.imageName + "</imageName>" +
                            "<mainClass>" + this.mainClass + "</mainClass>" +
                            "</configuration>"
            ));
            Xpp3Dom buildArgsNode = new Xpp3Dom("buildArgs");
            for(String arg : graalBuildArgs().split("\n")) {
                if(arg == null || arg.trim().isEmpty()) continue;
                Xpp3Dom buildArgNode = new Xpp3Dom("buildArg");
                buildArgNode.setValue(arg.trim());
                buildArgsNode.addChild(buildArgNode);
            }
            nativeConfig.addChild(buildArgsNode);
            nativePlugin.setConfiguration(nativeConfig);

            PluginExecution nativeBuildExec = new PluginExecution();
            nativeBuildExec.setId("build-native");
            nativeBuildExec.setPhase("package");
            nativeBuildExec.addGoal("compile-no-fork");
            nativePlugin.addExecution(nativeBuildExec);
            build.addPlugin(nativePlugin);
        }
        model.setBuild(build);
    }

    private void addExtraDependenciesFromString(List<Dependency> addTo, String extraDepsString) {
        if (extraDepsString != null && !extraDepsString.isEmpty()) {
            String[] split = extraDepsString.split(",");
            for (String artifact : split) {
                String[] artifactSplit = artifact.trim().split(":");
                if (artifactSplit.length >= 3) {
                    String groupId = artifactSplit[0];
                    String artifactId = artifactSplit[1];
                    String version = artifactSplit[2];
                    String classifier = (artifactSplit.length >= 4) ? artifactSplit[3] : null;
                    String scope = (artifactSplit.length >= 5) ? artifactSplit[4] : "compile";
                    addDependency(addTo, groupId, artifactId, version, scope, classifier, false);
                } else {
                    System.err.println("Warning: Skipping malformed extra dependency: " + artifact);
                }
            }
        }
    }

    public void create() throws Exception {
        this.model = new Model();
        this.resolvedDependencies.clear();

        if ("kompile-spring-boot-webapp".equalsIgnoreCase(this.appType)) {
            Parent parent = new Parent();
            parent.setGroupId("ai.kompile");
            parent.setArtifactId("kompile-app");
            parent.setVersion(this.ragMcpAssistantParentVersion);
            model.setParent(parent);
            model.setGroupId("ai.kompile.generated.app");
            model.setArtifactId(this.imageName.toLowerCase().replace(" ", "-") + "-sba");
            model.setVersion(this.kompileAppVersion);
        } else {
            Parent parent = new Parent();
            parent.setGroupId("ai.kompile");
            parent.setArtifactId("kompile");
            parent.setVersion(this.kompileParentVersion);
            model.setParent(parent);
            model.setGroupId("ai.kompile.generated.pipeline");
            model.setArtifactId(this.imageName.toLowerCase().replace(" ", "-") + "-executor");
            model.setVersion(this.kompilePipelinesVersion);
        }
        model.setModelVersion("4.0.0");
        model.setName("Generated Kompile Application: " + this.imageName);
        model.setDescription("Dynamically generated Kompile application.");

        Properties pomProps = new Properties();
        pomProps.setProperty("java.version", ("kompile-spring-boot-webapp".equalsIgnoreCase(this.appType)) ? "17" : "11");
        pomProps.setProperty("maven.compiler.release", pomProps.getProperty("java.version"));
        pomProps.setProperty("project.build.sourceEncoding", "UTF-8");

        if (model.getParent() == null || !model.getParent().getArtifactId().equals("kompile-app")) {
            pomProps.setProperty("spring-boot.version", this.springBootVersion);
            pomProps.setProperty("spring-ai.version", this.springAiVersion);
        }
        pomProps.setProperty("project.version", this.kompilePipelinesVersion);
        pomProps.setProperty("kompile.app.version", this.kompileAppVersion);

        // Add properties for standard Maven plugin versions
        pomProps.setProperty("maven-compiler-plugin.version", Info.getMavenCompilerPluginVersion());
        pomProps.setProperty("maven-resources-plugin.version", Info.getMavenResourcesPluginVersion());
        pomProps.setProperty("maven-assembly-plugin.version", Info.getMavenAssemblyPluginVersion());
        pomProps.setProperty("native-maven-plugin.version", this.nativeImagePluginVersion);
        pomProps.setProperty("spring-boot-maven-plugin.version", this.springBootVersion);
        if (this.enableFrontendBuild) {
            pomProps.setProperty("frontend-maven-plugin.version", Info.getFrontendMavenPluginVersion());
            // node.version and npm.version will be defaulted in addBuildConfiguration if not set here
        }
        pomProps.setProperty("os-maven-plugin.version", Info.getOsMavenPluginVersion());

        model.setProperties(pomProps);

        addRepositories(model);
        addJavacppProfiles(model);

        addDefaultFrameworkAndAppCoreDependencies(this.resolvedDependencies);
        addProviderDependencies(this.resolvedDependencies);
        addPipelineStepDependenciesFromPipelineJson(this.resolvedDependencies);
        if (this.resolvedDependencies.stream().noneMatch(d -> d.getArtifactId().contains("-steps-"))) {
            addPipelineStepDependenciesFromCliFlags(this.resolvedDependencies);
        }

        if (this.nd4jBackend != null && !this.nd4jBackend.isEmpty()) {
            addDependency(this.resolvedDependencies, "org.eclipse.deeplearning4j", this.nd4jBackend, null, "compile", this.nd4jBackendClassifier, false);
        }
        addExtraDependenciesFromString(this.resolvedDependencies, this.extraDependencies);
        model.setDependencies(this.resolvedDependencies);

        DependencyManagement depMgmt = new DependencyManagement();
        if (model.getParent() == null || !model.getParent().getArtifactId().equals("kompile-app")) {
            if ("kompile-spring-boot-webapp".equalsIgnoreCase(this.appType)) {
                Dependency springBootBom = new Dependency();
                springBootBom.setGroupId("org.springframework.boot");
                springBootBom.setArtifactId("spring-boot-dependencies");
                springBootBom.setVersion(this.springBootVersion);
                springBootBom.setType("pom"); springBootBom.setScope("import");
                depMgmt.addDependency(springBootBom);
                Dependency springAiBom = new Dependency();
                springAiBom.setGroupId("org.springframework.ai");
                springAiBom.setArtifactId("spring-ai-bom");
                springAiBom.setVersion(this.springAiVersion);
                springAiBom.setType("pom"); springAiBom.setScope("import");
                depMgmt.addDependency(springAiBom);
            }
        }
        if (model.getParent() == null || !model.getParent().getArtifactId().equals("kompile")) {
            Dependency kompileParentDepMgmt = new Dependency();
            kompileParentDepMgmt.setGroupId("ai.kompile");
            kompileParentDepMgmt.setArtifactId("kompile");
            kompileParentDepMgmt.setVersion(this.kompileParentVersion);
            kompileParentDepMgmt.setType("pom"); kompileParentDepMgmt.setScope("import");
            depMgmt.addDependency(kompileParentDepMgmt);
        }
        if (!depMgmt.getDependencies().isEmpty()) {
            model.setDependencyManagement(depMgmt);
        }

        addBuildConfiguration(model);

        MavenXpp3Writer mavenXpp3Writer = new MavenXpp3Writer();
        try (FileWriter fileWriter = new FileWriter(this.outputFile)) {
            mavenXpp3Writer.write(fileWriter, model);
        }
        System.out.println("Successfully generated POM: " + this.outputFile.getAbsolutePath());
    }

    public void addJavacppProfiles(Model model) {
        Profile defaultProfile =new Profile();
        defaultProfile.setId("javacpp-platform-default");
        Activation activation = new Activation();
        ActivationProperty activationProperty = new ActivationProperty();
        activationProperty.setName("!javacpp.platform");
        activation.setProperty(activationProperty);
        defaultProfile.setActivation(activation);
        defaultProfile.addProperty("javacpp.platform","${os.detected.name}-${os.detected.arch}");
        model.addProfile(defaultProfile);
    }

    public void addRepositories(Model model) {
        Repository sonatypeSnapshots = new Repository();
        sonatypeSnapshots.setId("sonatype-nexus-snapshots");
        sonatypeSnapshots.setUrl("https://oss.sonatype.org/content/repositories/snapshots");
        RepositoryPolicy snapshotsPolicy = new RepositoryPolicy();
        snapshotsPolicy.setEnabled(true);
        snapshotsPolicy.setUpdatePolicy("always");
        sonatypeSnapshots.setSnapshots(snapshotsPolicy);
        model.addRepository(sonatypeSnapshots);

        Repository central = new Repository();
        central.setId("central");
        central.setUrl("https://repo.maven.apache.org/maven2");
        model.addRepository(central);
    }

    @Override
    public Void call() throws Exception {
        create();
        return null;
    }

    public static void main(String... args) {
        int exitCode = new CommandLine(new PomGenerator()).execute(args);
        System.exit(exitCode);
    }
}