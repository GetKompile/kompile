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

    @CommandLine.Option(names = {"--includeAppMain"}, description = "Include kompile-app-main module", defaultValue = "true", negatable = true)
    private boolean includeAppMain;

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
    private static final String DEFAULT_MAVEN_SHADE_PLUGIN_VERSION = "3.5.2";
    private static final String DEFAULT_NATIVE_MAVEN_PLUGIN_VERSION = "0.10.1";
    private static final String DEFAULT_MAVEN_SUREFIRE_PLUGIN_VERSION = "3.2.5";


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

    private void addDependency(List<Dependency> addTo, String groupId, String artifactId, String versionProperty, String scope, String classifier, boolean optional) {
        addTo.add(createDependencyInternal(groupId, artifactId, versionProperty, scope, classifier, optional));
    }

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
        props.setProperty("maven-shade-plugin.version", DEFAULT_MAVEN_SHADE_PLUGIN_VERSION);
        props.setProperty("maven-surefire-plugin.version", DEFAULT_MAVEN_SUREFIRE_PLUGIN_VERSION);

        if (this.buildNative) {
            props.setProperty("native-maven-plugin.version", DEFAULT_NATIVE_MAVEN_PLUGIN_VERSION);
            props.setProperty("native.image.name", instanceArtifactId + "-native");
        }
        model.setProperties(props);

        addApplicationDependencies();
        addApplicationBuild();

        if (this.buildNative) {
            addNativeProfile();
        }
        addSpringRepositories();

        MavenXpp3Writer mavenXpp3Writer = new MavenXpp3Writer();
        try (FileWriter fileWriter = new FileWriter(outputFile)) {
            mavenXpp3Writer.write(fileWriter, model);
            System.out.println("Successfully generated RAG application POM: " + outputFile.getAbsolutePath());
            if (this.buildNative && model.getProfiles().stream().anyMatch(p -> "native".equals(p.getId()))) {
                System.out.println("Native profile was included in the generated POM.");
            } else if (this.buildNative) {
                System.out.println("WARNING: Native profile was intended but NOT found in the generated POM model.");
            }
            else {
                System.out.println("Native profile was NOT included in the generated POM (buildNative flag was false).");
            }
        }
        return null;
    }

    private void addApplicationDependencies() {
        defaultDependencies.clear();

        addDependency(defaultDependencies, "org.springframework.boot", "spring-boot-starter-web", "${spring-boot.version}");
        addDependency(defaultDependencies, "org.springframework.boot", "spring-boot-starter", "${spring-boot.version}");
        addDependency(defaultDependencies, "org.springframework.ai", "spring-ai-starter-mcp-client", "${spring-ai.version}");
        addDependency(defaultDependencies, "org.springframework.ai", "spring-ai-starter-mcp-server", "${spring-ai.version}");

        if (includeAppMain) addDependency(defaultDependencies, "ai.kompile", "kompile-app-main", "${kompile.project.version}");
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

        addDependency(defaultDependencies, "org.springframework.boot", "spring-boot-starter-test", "${spring-boot.version}", "test", null, false);

        model.setDependencies(defaultDependencies);
    }

    private void addApplicationBuild() throws XmlPullParserException, IOException {
        if (model.getBuild() == null) {
            model.setBuild(new Build());
        }
        Build build = model.getBuild();

        Plugin compilerPlugin = new Plugin();
        compilerPlugin.setGroupId("org.apache.maven.plugins");
        compilerPlugin.setArtifactId("maven-compiler-plugin");
        compilerPlugin.setVersion("${maven-compiler-plugin.version}");
        Xpp3Dom compilerConfig = Xpp3DomBuilder.build(new StringReader(
                "<configuration>" +
                        "  <release>${java.version}</release>" +
                        "  <annotationProcessorPaths>" +
                        "    <path><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>${lombok.version}</version></path>" +
                        "    <path><groupId>org.springframework.boot</groupId><artifactId>spring-boot-configuration-processor</artifactId><version>${spring-boot.version}</version></path>" +
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
        Xpp3Dom defaultResConfig = Xpp3DomBuilder.build(new StringReader(
                "<configuration>" +
                        "  <outputDirectory>${project.build.outputDirectory}</outputDirectory>" +
                        "  <resources><resource><directory>src/main/resources</directory><filtering>false</filtering><includes><include>**/*</include></includes></resource></resources>" +
                        "</configuration>"));
        defaultResourcesExecution.setConfiguration(defaultResConfig);
        resourcesPlugin.addExecution(defaultResourcesExecution);
        build.addPlugin(resourcesPlugin);

        Plugin surefirePlugin = new Plugin();
        surefirePlugin.setGroupId("org.apache.maven.plugins");
        surefirePlugin.setArtifactId("maven-surefire-plugin");
        surefirePlugin.setVersion("${maven-surefire-plugin.version}");
        surefirePlugin.setConfiguration(Xpp3DomBuilder.build(new StringReader("<configuration><skipTests>${skipTests}</skipTests></configuration>")));
        build.addPlugin(surefirePlugin);

        Plugin jarPlugin = new Plugin();
        jarPlugin.setGroupId("org.apache.maven.plugins");
        jarPlugin.setArtifactId("maven-jar-plugin");
        jarPlugin.setVersion("${maven-jar-plugin.version}");
        Xpp3Dom jarConfig = Xpp3DomBuilder.build(new StringReader(
                "<configuration>" +
                        "  <archive><manifest><mainClass>${start-class}</mainClass><addClasspath>true</addClasspath><classpathPrefix>BOOT-INF/lib/</classpathPrefix></manifest></archive>" +
                        "</configuration>"));
        jarPlugin.setConfiguration(jarConfig);
        build.addPlugin(jarPlugin);

        Plugin springBootPlugin = new Plugin();
        springBootPlugin.setGroupId("org.springframework.boot");
        springBootPlugin.setArtifactId("spring-boot-maven-plugin");
        springBootPlugin.setVersion("${spring-boot.version}");
        Xpp3Dom springBootMainConfig = Xpp3DomBuilder.build(new StringReader(
                "<configuration>" +
                        "  <mainClass>${start-class}</mainClass>" +
                        "  <excludes><exclude><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId></exclude></excludes>" +
                        "</configuration>"));
        springBootPlugin.setConfiguration(springBootMainConfig);

        PluginExecution springBootRepackage = new PluginExecution();
        springBootRepackage.setId("repackage");
        springBootRepackage.addGoal("repackage");
        springBootPlugin.addExecution(springBootRepackage);
        build.addPlugin(springBootPlugin);
    }

    private void addNativeProfile() throws XmlPullParserException, IOException {
        Profile nativeProfile = new Profile();
        nativeProfile.setId("native");

        Build nativeBuild = new Build();

        Plugin nativeMavenPlugin = new Plugin();
        nativeMavenPlugin.setGroupId("org.graalvm.buildtools");
        nativeMavenPlugin.setArtifactId("native-maven-plugin");
        nativeMavenPlugin.setVersion("${native-maven-plugin.version}");
        nativeMavenPlugin.setExtensions(true);

        // Add the user-requested -H:-AddAllFileSystemProviders flag.
        // Remove manual --class-path argument to let native-maven-plugin handle classpath.
        Xpp3Dom nativePluginConfig = Xpp3DomBuilder.build(new StringReader(
                "<configuration>" +
                        "  <imageName>${native.image.name}</imageName>" +
                        "  <mainClass>${start-class}</mainClass>" +
                        "  <metadataRepository><enabled>true</enabled></metadataRepository>" +
                        "  <quickBuild>false</quickBuild>" +
                        "  <buildArgs>" +
                        "    <buildArg>--verbose</buildArg>" +
                        "    <buildArg>-H:+ReportExceptionStackTraces</buildArg>" +
                        "    <buildArg>--no-fallback</buildArg>" +
                        "    <buildArg>--enable-url-protocols=http,https</buildArg>" +
                        "    <buildArg>-Dspring.native.remove-unused-autoconfig=true</buildArg>" +
                        "    <buildArg>-H:-AddAllFileSystemProviders</buildArg>" + // User requested this
                        // Consider adding other necessary --add-exports or --initialize-at-build-time if identified
                        // from successful kompile-app-main builds, but avoid generic ones without specific need.
                        "  </buildArgs>" +
                        "</configuration>"
        ));
        nativeMavenPlugin.setConfiguration(nativePluginConfig);

        PluginExecution nativeBuildExecution = new PluginExecution();
        nativeBuildExecution.setId("build-native");
        nativeBuildExecution.addGoal("compile-no-fork");
        nativeBuildExecution.setPhase("package");
        nativeMavenPlugin.addExecution(nativeBuildExecution);

        PluginExecution nativeAddMeta = new PluginExecution();
        nativeAddMeta.setId("add-reachability-metadata");
        nativeAddMeta.addGoal("add-reachability-metadata");
        nativeMavenPlugin.addExecution(nativeAddMeta);

        nativeBuild.addPlugin(nativeMavenPlugin);
        nativeProfile.setBuild(nativeBuild);

        model.addProfile(nativeProfile);
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