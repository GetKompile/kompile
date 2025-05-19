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
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "rag-pom-generate", mixinStandardHelpOptions = false,
        description = "Generates a pom.xml for a RAG MCP Assistant application instance.")
public class RagPomGenerator implements Callable<Void> {

    @CommandLine.Option(names = {"--outputFile"}, description = "The output file for the generated pom.xml", defaultValue = "pom-rag-instance.xml")
    private File outputFile;

    @CommandLine.Option(names = {"--instanceGroupId"}, description = "GroupId for the generated RAG instance", defaultValue = "ai.kompile.rag.instance")
    private String instanceGroupId;

    @CommandLine.Option(names = {"--instanceArtifactId"}, description = "ArtifactId for the generated RAG instance", defaultValue = "my-rag-app")
    private String instanceArtifactId;

    @CommandLine.Option(names = {"--instanceVersion"}, description = "Version for the generated RAG instance", defaultValue = "0.1.0-SNAPSHOT")
    private String instanceVersion;

    @CommandLine.Option(names = {"--ragMcpVersion"}, description = "Version of the ai.kompile modules", defaultValue = "0.1.0-SNAPSHOT")
    private String ragMcpVersion;

    // --- RAG Module Selection Flags ---
    @CommandLine.Option(names = {"--includeAppCore"}, description = "Include kompile-app-core module", defaultValue = "true", negatable = true)
    private boolean includeAppCore;

    @CommandLine.Option(names = {"--includeLoadersOrchestrator"}, description = "Include kompile-app-loaders-orchestrator module", defaultValue = "true", negatable = true)
    private boolean includeLoadersOrchestrator;

    @CommandLine.Option(names = {"--includeLoaderTika"}, description = "Include kompile-loader-tika module")
    private boolean includeLoaderTika = false;

    @CommandLine.Option(names = {"--includeLoaderPdf"}, description = "Include kompile-loader-pdf module")
    private boolean includeLoaderPdf = false;

    @CommandLine.Option(names = {"--includeAnserini"}, description = "Include kompile-app-anserini module")
    private boolean includeAnserini = false;

    @CommandLine.Option(names = {"--includeLlmOpenai"}, description = "Include kompile-app-openai-llm module")
    private boolean includeLlmOpenai = false;

    @CommandLine.Option(names = {"--includeLlmAnthropic"}, description = "Include kompile-app-anthropic-llm module")
    private boolean includeLlmAnthropic = false;

    @CommandLine.Option(names = {"--includeLlmGemini"}, description = "Include kompile-app-gemini-llm module")
    private boolean includeLlmGemini = false;

    @CommandLine.Option(names = {"--includeEmbeddingOpenai"}, description = "Include kompile-embedding-openai module")
    private boolean includeEmbeddingOpenai = false;

    @CommandLine.Option(names = {"--includeEmbeddingSentenceTransformer"}, description = "Include kompile-embedding-sentence-transformer module")
    private boolean includeEmbeddingSentenceTransformer = false;

    @CommandLine.Option(names = {"--includeVectorstoreChroma"}, description = "Include kompile-vectorstore-chroma module")
    private boolean includeVectorstoreChroma = false;

    @CommandLine.Option(names = {"--includeVectorstorePgvector"}, description = "Include kompile-vectorstore-pgvector module")
    private boolean includeVectorstorePgvector = false;

    @CommandLine.Option(names = {"--includeToolFilesystem"}, description = "Include kompile-tool-filesystem module", defaultValue = "true", negatable = true)
    private boolean includeToolFilesystem;

    @CommandLine.Option(names = {"--includeToolRag"}, description = "Include kompile-tool-rag module", defaultValue = "true", negatable = true)
    private boolean includeToolRag;

    @CommandLine.Option(names = {"--buildNative"}, description = "Configure build for GraalVM native image")
    private boolean buildNative = false;


    private Model model;
    private List<Dependency> defaultDependencies = new ArrayList<>();

    private static final String DEFAULT_SPRING_BOOT_VERSION = "3.2.5";
    private static final String DEFAULT_SPRING_AI_VERSION = "1.0.0-M8";
    private static final String DEFAULT_LOMBOK_VERSION = "1.18.38";
    private static final String DEFAULT_JACKSON_VERSION = "2.15.3";
    private static final String DEFAULT_GUAVA_VERSION = "32.1.3-jre";

    private static final String DEFAULT_MAVEN_COMPILER_PLUGIN_VERSION = "3.13.0";
    private static final String DEFAULT_MAVEN_RESOURCES_PLUGIN_VERSION = "3.3.1";
    private static final String DEFAULT_MAVEN_JAR_PLUGIN_VERSION = "3.3.0";
    private static final String DEFAULT_FRONTEND_MAVEN_PLUGIN_VERSION = "1.15.0";
    private static final String DEFAULT_NODE_VERSION = "v20.11.1";
    private static final String DEFAULT_NPM_VERSION = "10.2.4";
    private static final String DEFAULT_NATIVE_MAVEN_PLUGIN_VERSION = "0.10.1"; // Or your desired GraalVM plugin version


    private Dependency createDependencyInternal(String groupId, String artifactId, String versionProperty, String scope, String classifier, boolean optional) {
        Dependency dependency = new Dependency();
        dependency.setGroupId(groupId);
        dependency.setArtifactId(artifactId);
        dependency.setVersion(versionProperty);
        if (scope != null && !scope.isEmpty()) {
            dependency.setScope(scope);
        }
        if (classifier != null && !classifier.isEmpty()) {
            dependency.setClassifier(classifier);
        }
        if (optional) {
            dependency.setOptional(true);
        }
        return dependency;
    }

    // Main helper to add a dependency
    private void addDependency(List<Dependency> addTo, String groupId, String artifactId, String versionProperty, String scope, String classifier, boolean optional) {
        addTo.add(createDependencyInternal(groupId, artifactId, versionProperty, scope, classifier, optional));
    }

    // Convenience for compile scope, non-optional
    private void addDependency(List<Dependency> addTo, String groupId, String artifactId, String versionProperty) {
        addDependency(addTo, groupId, artifactId, versionProperty, "compile", null, false);
    }


    @Override
    public Void call() throws Exception {
        model = new Model();
        model.setModelVersion("4.0.0");
        model.setGroupId(instanceGroupId);
        model.setArtifactId(instanceArtifactId);
        model.setVersion(instanceVersion);
        model.setPackaging("jar");

        Properties props = new Properties();
        props.setProperty("java.version", "17");
        props.setProperty("start-class", "ai.kompile.app.MainApplication");

        props.setProperty("kompile.project.version", this.ragMcpVersion);
        props.setProperty("spring-boot.version", DEFAULT_SPRING_BOOT_VERSION);
        props.setProperty("spring-ai.version", DEFAULT_SPRING_AI_VERSION);
        props.setProperty("lombok.version", DEFAULT_LOMBOK_VERSION);
        props.setProperty("jackson.version", DEFAULT_JACKSON_VERSION);
        props.setProperty("guava.version", DEFAULT_GUAVA_VERSION);

        props.setProperty("maven-compiler-plugin.version", DEFAULT_MAVEN_COMPILER_PLUGIN_VERSION);
        props.setProperty("maven-resources-plugin.version", DEFAULT_MAVEN_RESOURCES_PLUGIN_VERSION);
        props.setProperty("maven-jar-plugin.version", DEFAULT_MAVEN_JAR_PLUGIN_VERSION);
        props.setProperty("frontend-maven-plugin.version", DEFAULT_FRONTEND_MAVEN_PLUGIN_VERSION);
        props.setProperty("node.version", DEFAULT_NODE_VERSION);
        props.setProperty("npm.version", DEFAULT_NPM_VERSION);

        if (buildNative) {
            props.setProperty("native.image.name", instanceArtifactId + "-native");
            props.setProperty("native-maven-plugin.version", DEFAULT_NATIVE_MAVEN_PLUGIN_VERSION);
        }
        model.setProperties(props);

        addApplicationDependencies();
        addApplicationBuild();
        addSpringRepositories();

        MavenXpp3Writer mavenXpp3Writer = new MavenXpp3Writer();
        try (FileWriter fileWriter = new FileWriter(outputFile)) {
            mavenXpp3Writer.write(fileWriter, model);
            System.out.println("Successfully generated RAG application POM: " + outputFile.getAbsolutePath());
        }
        return null;
    }

    private void addApplicationDependencies() {
        defaultDependencies.clear(); // Ensure it's a fresh list for each generation

        addDependency(defaultDependencies, "org.springframework.boot", "spring-boot-starter-web", "${spring-boot.version}");
        addDependency(defaultDependencies, "org.springframework.boot", "spring-boot-starter", "${spring-boot.version}");

        addDependency(defaultDependencies, "org.springframework.ai", "spring-ai-starter-mcp-client", "${spring-ai.version}");
        addDependency(defaultDependencies, "org.springframework.ai", "spring-ai-starter-mcp-server", "${spring-ai.version}");

        if (includeAppCore) addDependency(defaultDependencies, "ai.kompile", "kompile-app-core", "${kompile.project.version}");
        if (includeLoadersOrchestrator) addDependency(defaultDependencies, "ai.kompile", "kompile-app-loaders-orchestrator", "${kompile.project.version}");
        if (includeLoaderTika) addDependency(defaultDependencies, "ai.kompile", "kompile-loader-tika", "${kompile.project.version}");
        if (includeLoaderPdf) addDependency(defaultDependencies, "ai.kompile", "kompile-loader-pdf", "${kompile.project.version}");
        if (includeAnserini) addDependency(defaultDependencies, "ai.kompile", "kompile-app-anserini", "${kompile.project.version}");

        if (includeLlmOpenai) addDependency(defaultDependencies, "ai.kompile", "kompile-app-openai-llm", "${kompile.project.version}");
        if (includeLlmAnthropic) addDependency(defaultDependencies, "ai.kompile", "kompile-app-anthropic-llm", "${kompile.project.version}");
        if (includeLlmGemini) addDependency(defaultDependencies, "ai.kompile", "kompile-app-gemini-llm", "${kompile.project.version}");

        if (includeEmbeddingOpenai) addDependency(defaultDependencies, "ai.kompile", "kompile-embedding-openai", "${kompile.project.version}");
        if (includeEmbeddingSentenceTransformer) addDependency(defaultDependencies, "ai.kompile", "kompile-embedding-sentence-transformer", "${kompile.project.version}");

        if (includeVectorstoreChroma) addDependency(defaultDependencies, "ai.kompile", "kompile-vectorstore-chroma", "${kompile.project.version}");
        if (includeVectorstorePgvector) addDependency(defaultDependencies, "ai.kompile", "kompile-vectorstore-pgvector", "${kompile.project.version}");

        if (includeToolFilesystem) addDependency(defaultDependencies, "ai.kompile", "kompile-tool-filesystem", "${kompile.project.version}");
        if (includeToolRag) addDependency(defaultDependencies, "ai.kompile", "kompile-tool-rag", "${kompile.project.version}");

        addDependency(defaultDependencies, "org.projectlombok", "lombok", "${lombok.version}", "provided", null, true);
        addDependency(defaultDependencies, "com.fasterxml.jackson.core", "jackson-databind", "${jackson.version}");
        addDependency(defaultDependencies, "com.google.guava", "guava", "${guava.version}");

        // Removed test dependencies as per instruction
        // addDependency(defaultDependencies, "org.springframework.boot", "spring-boot-starter-test", "${spring-boot.version}", "test", null, false);
        // if (buildNative) {
        //     addDependency(defaultDependencies, "org.junit.platform", "junit-platform-launcher", "${spring-boot.version}", "test", null, false); // Version managed by Spring Boot BOM
        // }

        model.setDependencies(defaultDependencies);
    }


    private void addApplicationBuild() throws XmlPullParserException, IOException {
        Build build = new Build();

        Plugin compilerPlugin = new Plugin();
        compilerPlugin.setGroupId("org.apache.maven.plugins");
        compilerPlugin.setArtifactId("maven-compiler-plugin");
        compilerPlugin.setVersion("${maven-compiler-plugin.version}");
        Xpp3Dom compilerConfig = Xpp3DomBuilder.build(new StringReader(
                "<configuration>" +
                        "  <release>${java.version}</release>" +
                        "  <annotationProcessorPaths>" +
                        "    <path>" +
                        "      <groupId>org.projectlombok</groupId>" +
                        "      <artifactId>lombok</artifactId>" +
                        "      <version>${lombok.version}</version>" +
                        "    </path>" +
                        // Add other annotation processors if needed, e.g., Spring Configuration Processor
                        "    <path>" +
                        "      <groupId>org.springframework.boot</groupId>" +
                        "      <artifactId>spring-boot-configuration-processor</artifactId>" +
                        "      <version>${spring-boot.version}</version>" +
                        "    </path>" +
                        "  </annotationProcessorPaths>" +
                        "</configuration>"
        ));
        compilerPlugin.setConfiguration(compilerConfig);
        build.addPlugin(compilerPlugin);

        Plugin resourcesPlugin = new Plugin();
        resourcesPlugin.setGroupId("org.apache.maven.plugins");
        resourcesPlugin.setArtifactId("maven-resources-plugin");
        resourcesPlugin.setVersion("${maven-resources-plugin.version}");

        PluginExecution defaultResourcesExecution = new PluginExecution();
        defaultResourcesExecution.setId("default-resources");
        defaultResourcesExecution.addGoal("resources");
        // Note: The <directory>src/main/resources</directory> assumes such a directory exists in the generated project.
        // If it doesn't, this execution might not do anything or could be removed if not needed for a basic generated project.
        Xpp3Dom defaultResConfig = Xpp3DomBuilder.build(new StringReader(
                "<configuration>" +
                        "  <outputDirectory>${project.build.outputDirectory}</outputDirectory>" +
                        "  <resources>" +
                        "    <resource>" +
                        "      <directory>src/main/resources</directory>" + // This path is relative to the generated project
                        "      <filtering>false</filtering>" +
                        "      <includes><include>**/*</include></includes>" + // Explicitly include all files
                        "    </resource>" +
                        "  </resources>" +
                        "</configuration>"));
        defaultResourcesExecution.setConfiguration(defaultResConfig);
        resourcesPlugin.addExecution(defaultResourcesExecution);

        // Parameterize these paths using system properties set by BuildRagApp
        String ragMcpSourceContextDir = System.getProperty("rag.mcp.source.context.dir", "."); // Default to current dir if not set

        PluginExecution copyFrontendToTargetExecution = new PluginExecution();
        copyFrontendToTargetExecution.setId("copy-angular-to-target-classes");
        copyFrontendToTargetExecution.addGoal("copy-resources");
        copyFrontendToTargetExecution.setPhase("process-resources");
        Xpp3Dom frontendToTargetConfig = Xpp3DomBuilder.build(new StringReader(
                "<configuration>" +
                        "  <outputDirectory>${project.build.outputDirectory}/static</outputDirectory>" +
                        "  <resources>" +
                        "    <resource>" +
                        "      <directory>" + ragMcpSourceContextDir + "/kompile-app-main/src/main/frontend/dist/rag-frontend/browser</directory>" +
                        "      <filtering>false</filtering>" +
                        "    </resource>" +
                        "  </resources>" +
                        "</configuration>"));
        copyFrontendToTargetExecution.setConfiguration(frontendToTargetConfig);
        resourcesPlugin.addExecution(copyFrontendToTargetExecution);

        PluginExecution copyFrontendToSrcExecution = new PluginExecution();
        copyFrontendToSrcExecution.setId("copy-angular-to-src-main-resources");
        copyFrontendToSrcExecution.addGoal("copy-resources");
        copyFrontendToSrcExecution.setPhase("process-resources");
        Xpp3Dom frontendToSrcConfig = Xpp3DomBuilder.build(new StringReader(
                "<configuration>" +
                        "  <outputDirectory>" + ragMcpSourceContextDir + "/kompile-app-main/src/main/resources/static</outputDirectory>" +
                        "  <overwrite>true</overwrite>" +
                        "  <resources>" +
                        "    <resource>" +
                        "      <directory>" + ragMcpSourceContextDir + "/kompile-app-main/src/main/frontend/dist/rag-frontend/browser</directory>" +
                        "      <filtering>false</filtering>" +
                        "    </resource>" +
                        "  </resources>" +
                        "</configuration>"));
        copyFrontendToSrcExecution.setConfiguration(frontendToSrcConfig);
        resourcesPlugin.addExecution(copyFrontendToSrcExecution);
        build.addPlugin(resourcesPlugin);


        Plugin jarPlugin = new Plugin();
        jarPlugin.setGroupId("org.apache.maven.plugins");
        jarPlugin.setArtifactId("maven-jar-plugin");
        jarPlugin.setVersion("${maven-jar-plugin.version}");
        Xpp3Dom jarConfig = Xpp3DomBuilder.build(new StringReader(
                "<configuration>" +
                        "  <archive>" +
                        "    <manifest>" +
                        "      <mainClass>${start-class}</mainClass>" +
                        "      <addClasspath>true</addClasspath>" +
                        "      <classpathPrefix>BOOT-INF/lib/</classpathPrefix>" +
                        "    </manifest>" +
                        "  </archive>" +
                        "</configuration>"));
        jarPlugin.setConfiguration(jarConfig);
        build.addPlugin(jarPlugin);

        Plugin springBootPlugin = new Plugin();
        springBootPlugin.setGroupId("org.springframework.boot");
        springBootPlugin.setArtifactId("spring-boot-maven-plugin");
        springBootPlugin.setVersion("${spring-boot.version}");
        Xpp3Dom springBootConfig = Xpp3DomBuilder.build(new StringReader(
                "<configuration>" +
                        "  <mainClass>${start-class}</mainClass>" +
                        "  <excludes>" +
                        "    <exclude>" +
                        "      <groupId>org.projectlombok</groupId>" +
                        "      <artifactId>lombok</artifactId>" +
                        "    </exclude>" +
                        "  </excludes>" +
                        "</configuration>"));
        springBootPlugin.setConfiguration(springBootConfig);
        PluginExecution springBootRepackage = new PluginExecution();
        springBootRepackage.setId("repackage");
        springBootRepackage.addGoal("repackage");
        springBootPlugin.addExecution(springBootRepackage);

        if (buildNative) {
            PluginExecution processAot = new PluginExecution();
            processAot.setId("process-aot");
            processAot.addGoal("process-aot");
            springBootPlugin.addExecution(processAot);
        }
        build.addPlugin(springBootPlugin);


        Plugin frontendPlugin = new Plugin();
        frontendPlugin.setGroupId("com.github.eirslett");
        frontendPlugin.setArtifactId("frontend-maven-plugin");
        frontendPlugin.setVersion("${frontend-maven-plugin.version}");
        Xpp3Dom frontendConfig = Xpp3DomBuilder.build(new StringReader(
                "<configuration>" +
                        "  <workingDirectory>" + ragMcpSourceContextDir + "/kompile-app-main/src/main/frontend</workingDirectory>" +
                        "  <installDirectory>${project.build.directory}/frontend-build</installDirectory>" +
                        "</configuration>"));
        frontendPlugin.setConfiguration(frontendConfig);

        PluginExecution feInstallNode = new PluginExecution();
        feInstallNode.setId("install node and npm");
        feInstallNode.addGoal("install-node-and-npm");
        feInstallNode.setPhase("initialize");
        feInstallNode.setConfiguration(Xpp3DomBuilder.build(new StringReader(
                "<configuration><nodeVersion>${node.version}</nodeVersion><npmVersion>${npm.version}</npmVersion></configuration>"
        )));

        PluginExecution feNpmInstall = new PluginExecution();
        feNpmInstall.setId("npm install");
        feNpmInstall.addGoal("npm");
        feNpmInstall.setPhase("generate-sources");
        feNpmInstall.setConfiguration(Xpp3DomBuilder.build(new StringReader(
                "<configuration><arguments>install</arguments></configuration>"
        )));

        PluginExecution feNpmBuild = new PluginExecution();
        feNpmBuild.setId("npm run build");
        feNpmBuild.addGoal("npm");
        feNpmBuild.setPhase("generate-resources");
        feNpmBuild.setConfiguration(Xpp3DomBuilder.build(new StringReader(
                "<configuration><arguments>run build</arguments></configuration>"
        )));

        frontendPlugin.addExecution(feInstallNode);
        frontendPlugin.addExecution(feNpmInstall);
        frontendPlugin.addExecution(feNpmBuild);
        build.addPlugin(frontendPlugin);

        if (buildNative) {
            Plugin nativePlugin = new Plugin();
            nativePlugin.setGroupId("org.graalvm.buildtools");
            nativePlugin.setArtifactId("native-maven-plugin");
            nativePlugin.setVersion("${native-maven-plugin.version}");
            nativePlugin.setExtensions(true);

            Xpp3Dom nativeConfig = Xpp3DomBuilder.build(new StringReader(
                    "<configuration>" +
                            "  <imageName>${native.image.name}</imageName>" +
                            "  <mainClass>${start-class}</mainClass>" +
                            "  <quickBuild>false</quickBuild>" +
                            "  <buildArgs>" +
                            "    <buildArg>--verbose</buildArg>" +
                            "    <buildArg>-H:+ReportExceptionStackTraces</buildArg>" +
                            "    <buildArg>--no-fallback</buildArg>" +
                            "    <buildArg>--class-path</buildArg>" +
                            "    <buildArg>${project.build.outputDirectory}${path.separator}${project.build.directory}/spring-aot/main/classes</buildArg>" +
                            "  </buildArgs>" +
                            "</configuration>"));
            nativePlugin.setConfiguration(nativeConfig);

            PluginExecution nativeAddMeta = new PluginExecution();
            nativeAddMeta.setId("add-reachability-metadata");
            nativeAddMeta.addGoal("add-reachability-metadata");

            PluginExecution nativeBuildExecution = new PluginExecution(); // Renamed to avoid conflict
            nativeBuildExecution.setId("build-native");
            nativeBuildExecution.addGoal("compile-no-fork");
            nativeBuildExecution.setPhase("package");

            nativePlugin.addExecution(nativeAddMeta);
            nativePlugin.addExecution(nativeBuildExecution);
            build.addPlugin(nativePlugin);
        }

        model.setBuild(build);
    }


    private void addSpringRepositories() {
        Repository springMilestones = new Repository();
        springMilestones.setId("spring-milestones");
        springMilestones.setName("Spring Milestones");
        springMilestones.setUrl("https://repo.spring.io/milestone");
        RepositoryPolicy snapshotsPolicyMilestones = new RepositoryPolicy();
        snapshotsPolicyMilestones.setEnabled(false);
        springMilestones.setSnapshots(snapshotsPolicyMilestones);
        model.addRepository(springMilestones);

        Repository springReleases = new Repository();
        springReleases.setId("spring-releases");
        springReleases.setName("Spring Releases");
        springReleases.setUrl("https://repo.spring.io/release");
        RepositoryPolicy snapshotsPolicyReleases = new RepositoryPolicy();
        snapshotsPolicyReleases.setEnabled(false);
        springReleases.setSnapshots(snapshotsPolicyReleases);
        model.addRepository(springReleases);
    }

    public static void main(String... args) {
        new CommandLine(new RagPomGenerator()).execute(args);
    }
}