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

    @CommandLine.Option(names = {"--instanceVersion"}, description = "Version for the generated RAG instance", defaultValue = "0.0.1-SNAPSHOT")
    private String instanceVersion;

    @CommandLine.Option(names = {"--ragMcpVersion"}, description = "Version of the ai.kompile:rag-mcp-assistant-parent and its modules", defaultValue = "0.0.1-SNAPSHOT")
    private String ragMcpVersion;

    // --- RAG Module Selection Flags ---
    @CommandLine.Option(names = {"--includeAppCore"}, description = "Include kompile-app-core module (typically always true)", defaultValue = "true", negatable = true)
    private boolean includeAppCore;

    @CommandLine.Option(names = {"--includeLoadersOrchestrator"}, description = "Include kompile-app-loaders-orchestrator module (typically always true)", defaultValue = "true", negatable = true)
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

    @CommandLine.Option(names = {"--includeToolFilesystem"}, description = "Include kompile-tool-filesystem module (typically always true)", defaultValue = "true", negatable = true)
    private boolean includeToolFilesystem;

    @CommandLine.Option(names = {"--includeToolRag"}, description = "Include kompile-tool-rag module (typically always true)", defaultValue = "true", negatable = true)
    private boolean includeToolRag;

    @CommandLine.Option(names = {"--buildNative"}, description = "Configure build for GraalVM native image")
    private boolean buildNative = false;


    private Model model;
    private List<Dependency> defaultDependencies = new ArrayList<>();


    // Helper method to create a dependency
    private Dependency getDependency(String groupId, String artifactId, String version, String scope, String classifier) {
        Dependency dependency = new Dependency();
        dependency.setGroupId(groupId);
        dependency.setArtifactId(artifactId);
        if (version != null && !version.isEmpty()) {
            dependency.setVersion(version);
        }
        if (scope != null && !scope.isEmpty()) {
            dependency.setScope(scope);
        }
        if (classifier != null && !classifier.isEmpty()) {
            dependency.setClassifier(classifier);
        }
        return dependency;
    }

    // Helper method to add a dependency
    private void addDependency(List<Dependency> addTo, String groupId, String artifactId, String version, String scope, String classifier) {
        addTo.add(getDependency(groupId, artifactId, version, scope, classifier));
    }
    private void addDependency(List<Dependency> addTo, String groupId, String artifactId, String version) {
        addDependency(addTo, groupId, artifactId, version, "compile", null);
    }


    @Override
    public Void call() throws Exception {
        model = new Model();
        model.setModelVersion("4.0.0");
        model.setGroupId(instanceGroupId);
        model.setArtifactId(instanceArtifactId);
        model.setVersion(instanceVersion);
        model.setPackaging("jar"); // This instance will be a runnable JAR

        // Set Parent (rag-mcp-assistant-parent)
        Parent parent = new Parent();
        parent.setGroupId("ai.kompile");
        parent.setArtifactId("rag-mcp-assistant-parent");
        parent.setVersion(ragMcpVersion);
        model.setParent(parent);

        // Set Properties (inherited or overridden)
        Properties props = new Properties();
        props.setProperty("java.version", "17"); // As per parent
        props.setProperty("start-class", "ai.kompile.app.MainApplication"); // As per kompile-app-main
        if (buildNative) {
            props.setProperty("native.image.name", instanceArtifactId + "-native");
        }
        model.setProperties(props);

        // Define Dependencies based on flags
        addApplicationDependencies();

        // Define Build section
        addApplicationBuild();

        // Add Spring Milestones repository for Spring AI
        addSpringRepositories();

        // Write the POM file
        MavenXpp3Writer mavenXpp3Writer = new MavenXpp3Writer();
        try (FileWriter fileWriter = new FileWriter(outputFile)) {
            mavenXpp3Writer.write(fileWriter, model);
            System.out.println("Successfully generated RAG application POM: " + outputFile.getAbsolutePath());
        }
        return null;
    }

    private void addApplicationDependencies() {
        // Spring Boot Starters (versions managed by spring-boot-dependencies in parent)
        addDependency(defaultDependencies, "org.springframework.boot", "spring-boot-starter-web", null);
        addDependency(defaultDependencies, "org.springframework.boot", "spring-boot-starter", null);

        // Spring AI MCP Starters (versions managed by spring-ai-bom in parent)
        addDependency(defaultDependencies, "org.springframework.ai", "spring-ai-starter-mcp-client", null);
        addDependency(defaultDependencies, "org.springframework.ai", "spring-ai-starter-mcp-server", null);

        // Kompile RAG Modules (versions managed by parent: rag-mcp-assistant-parent)
        if (includeAppCore) addDependency(defaultDependencies, "ai.kompile", "kompile-app-core", null);
        if (includeLoadersOrchestrator) addDependency(defaultDependencies, "ai.kompile", "kompile-app-loaders-orchestrator", null);
        if (includeLoaderTika) addDependency(defaultDependencies, "ai.kompile", "kompile-loader-tika", null);
        if (includeLoaderPdf) addDependency(defaultDependencies, "ai.kompile", "kompile-loader-pdf", null);
        if (includeAnserini) addDependency(defaultDependencies, "ai.kompile", "kompile-app-anserini", null);

        if (includeLlmOpenai) addDependency(defaultDependencies, "ai.kompile", "kompile-app-openai-llm", null);
        if (includeLlmAnthropic) addDependency(defaultDependencies, "ai.kompile", "kompile-app-anthropic-llm", null);
        if (includeLlmGemini) addDependency(defaultDependencies, "ai.kompile", "kompile-app-gemini-llm", null);

        if (includeEmbeddingOpenai) addDependency(defaultDependencies, "ai.kompile", "kompile-embedding-openai", null);
        if (includeEmbeddingSentenceTransformer) addDependency(defaultDependencies, "ai.kompile", "kompile-embedding-sentence-transformer", null);

        if (includeVectorstoreChroma) addDependency(defaultDependencies, "ai.kompile", "kompile-vectorstore-chroma", null);
        if (includeVectorstorePgvector) addDependency(defaultDependencies, "ai.kompile", "kompile-vectorstore-pgvector", null);

        if (includeToolFilesystem) addDependency(defaultDependencies, "ai.kompile", "kompile-tool-filesystem", null);
        if (includeToolRag) addDependency(defaultDependencies, "ai.kompile", "kompile-tool-rag", null);


        // Lombok (version managed by parent)
        Dependency lombokDep = getDependency("org.projectlombok", "lombok", null, "provided", null);
        lombokDep.setOptional(true);
        defaultDependencies.add(lombokDep);

        // Jackson Databind (version managed by spring-boot-dependencies)
        addDependency(defaultDependencies, "com.fasterxml.jackson.core", "jackson-databind", null);

        // Spring Boot Test (version managed, scope test)
        addDependency(defaultDependencies, "org.springframework.boot", "spring-boot-starter-test", null, "test", null);

        if (buildNative) {
            // JUnit Platform Launcher for native tests
            addDependency(defaultDependencies, "org.junit.platform", "junit-platform-launcher", null, "test", null);
        }

        model.setDependencies(defaultDependencies);
    }


    private void addApplicationBuild() throws XmlPullParserException, IOException {
        Build build = new Build();

        // maven-compiler-plugin (configuration inherited from parent)
        Plugin compilerPlugin = new Plugin();
        compilerPlugin.setGroupId("org.apache.maven.plugins");
        compilerPlugin.setArtifactId("maven-compiler-plugin");
        build.addPlugin(compilerPlugin);

        // maven-resources-plugin (configuration adapted from kompile-app-main)
        Plugin resourcesPlugin = new Plugin();
        resourcesPlugin.setGroupId("org.apache.maven.plugins");
        resourcesPlugin.setArtifactId("maven-resources-plugin");

        // Default execution for src/main/resources
        PluginExecution defaultResourcesExecution = new PluginExecution();
        defaultResourcesExecution.setId("default-resources");
        defaultResourcesExecution.addGoal("resources");
        Xpp3Dom defaultResConfig = Xpp3DomBuilder.build(new StringReader(
                "<configuration>" +
                        "  <outputDirectory>${project.build.outputDirectory}</outputDirectory>" +
                        "  <resources>" +
                        "    <resource>" +
                        "      <directory>src/main/resources</directory>" + // Path relative to the root of cloned rag-mcp project
                        "      <filtering>false</filtering>" +
                        "    </resource>" +
                        "  </resources>" +
                        "</configuration>"));
        defaultResourcesExecution.setConfiguration(defaultResConfig);
        resourcesPlugin.addExecution(defaultResourcesExecution);

        // Execution to copy frontend to target/classes/static
        PluginExecution copyFrontendToTargetExecution = new PluginExecution();
        copyFrontendToTargetExecution.setId("copy-angular-to-target-classes");
        copyFrontendToTargetExecution.addGoal("copy-resources");
        copyFrontendToTargetExecution.setPhase("process-resources");
        Xpp3Dom frontendToTargetConfig = Xpp3DomBuilder.build(new StringReader(
                "<configuration>" +
                        "  <outputDirectory>${project.build.outputDirectory}/static</outputDirectory>" +
                        "  <resources>" +
                        "    <resource>" +
                        // IMPORTANT: This path needs to be correct relative to where this generated POM will be executed.
                        // If this POM is at the root of the cloned 'rag-mcp-assistant-parent',
                        // and 'kompile-app-main' contains the frontend, the path would be:
                        // 'kompile-app-main/src/main/frontend/dist/rag-frontend/browser'
                        // Adjust if the frontend is elsewhere or if this POM is placed inside 'kompile-app-main'.
                        // Assuming the generated POM is at the root of the cloned multi-module project:
                        "      <directory>kompile-app-main/src/main/frontend/dist/rag-frontend/browser</directory>" +
                        "      <filtering>false</filtering>" +
                        "    </resource>" +
                        "  </resources>" +
                        "</configuration>"));
        copyFrontendToTargetExecution.setConfiguration(frontendToTargetConfig);
        resourcesPlugin.addExecution(copyFrontendToTargetExecution);

        // Execution to copy frontend to kompile-app-main/src/main/resources/static
        PluginExecution copyFrontendToSrcExecution = new PluginExecution();
        copyFrontendToSrcExecution.setId("copy-angular-to-src-main-resources"); // Should be app-main's src
        copyFrontendToSrcExecution.addGoal("copy-resources");
        copyFrontendToSrcExecution.setPhase("process-resources");
        Xpp3Dom frontendToSrcConfig = Xpp3DomBuilder.build(new StringReader(
                "<configuration>" +
                        // This output directory is tricky if the generated pom is at the root.
                        // It assumes it's writing into the source tree of one of its modules.
                        // This is generally okay for local builds but might need adjustment for CI.
                        "  <outputDirectory>kompile-app-main/src/main/resources/static</outputDirectory>" +
                        "  <overwrite>true</overwrite>" +
                        "  <resources>" +
                        "    <resource>" +
                        "      <directory>kompile-app-main/src/main/frontend/dist/rag-frontend/browser</directory>" +
                        "      <filtering>false</filtering>" +
                        "    </resource>" +
                        "  </resources>" +
                        "</configuration>"));
        copyFrontendToSrcExecution.setConfiguration(frontendToSrcConfig);
        resourcesPlugin.addExecution(copyFrontendToSrcExecution);
        build.addPlugin(resourcesPlugin);


        // maven-jar-plugin (from kompile-app-main)
        Plugin jarPlugin = new Plugin();
        jarPlugin.setGroupId("org.apache.maven.plugins");
        jarPlugin.setArtifactId("maven-jar-plugin");
        Xpp3Dom jarConfig = Xpp3DomBuilder.build(new StringReader(
                "<configuration>" +
                        "  <archive>" +
                        "    <manifest>" +
                        "      <mainClass>${start-class}</mainClass>" +
                        "      <addClasspath>true</addClasspath>" +
                        "      <classpathPrefix>BOOT-INF/lib/</classpathPrefix>" + // Important for Spring Boot
                        "    </manifest>" +
                        "  </archive>" +
                        "</configuration>"));
        jarPlugin.setConfiguration(jarConfig);
        build.addPlugin(jarPlugin);

        // spring-boot-maven-plugin (from kompile-app-main, version from parent)
        Plugin springBootPlugin = new Plugin();
        springBootPlugin.setGroupId("org.springframework.boot");
        springBootPlugin.setArtifactId("spring-boot-maven-plugin");
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


        // frontend-maven-plugin (from kompile-app-main, version from parent)
        // This plugin builds the frontend. It assumes the frontend source is in 'kompile-app-main/src/main/frontend'
        // relative to where this generated POM is executed.
        Plugin frontendPlugin = new Plugin();
        frontendPlugin.setGroupId("com.github.eirslett");
        frontendPlugin.setArtifactId("frontend-maven-plugin");
        Xpp3Dom frontendConfig = Xpp3DomBuilder.build(new StringReader(
                "<configuration>" +
                        // This path is relative to the generated POM's location.
                        // If the POM is at the root of rag-mcp-assistant-parent,
                        // and the frontend is in kompile-app-main, this path is correct.
                        "  <workingDirectory>kompile-app-main/src/main/frontend</workingDirectory>" +
                        "  <installDirectory>${project.build.directory}/frontend-build</installDirectory>" + // Temporary install dir for node/npm
                        "</configuration>"));
        frontendPlugin.setConfiguration(frontendConfig);

        // Executions for frontend plugin
        PluginExecution feInstallNode = new PluginExecution();
        feInstallNode.setId("install node and npm");
        feInstallNode.addGoal("install-node-and-npm");
        feInstallNode.setPhase("initialize");
        feInstallNode.setConfiguration(Xpp3DomBuilder.build(new StringReader(
                "<configuration><nodeVersion>${node.version}</nodeVersion><npmVersion>${npm.version}</npmVersion></configuration>"
        ))); // node.version and npm.version from parent

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
            nativePlugin.setExtensions(true); // As per kompile-app-main

            Xpp3Dom nativeConfig = Xpp3DomBuilder.build(new StringReader(
                    "<configuration>" +
                            "  <imageName>${native.image.name}</imageName>" +
                            "  <mainClass>${start-class}</mainClass>" +
                            "  <quickBuild>false</quickBuild>" + // Can be made configurable
                            "  <buildArgs>" +
                            "    <buildArg>--verbose</buildArg>" +
                            "    <buildArg>-H:+ReportExceptionStackTraces</buildArg>" +
                            "    <buildArg>--no-fallback</buildArg>" +
                            // Classpath for AOT processing:
                            "    <buildArg>--class-path</buildArg>" +
                            "    <buildArg>${project.build.outputDirectory}:${project.build.directory}/spring-aot/main/classes</buildArg>" +
                            "  </buildArgs>" +
                            "</configuration>"));
            nativePlugin.setConfiguration(nativeConfig);

            PluginExecution nativeAddMeta = new PluginExecution();
            nativeAddMeta.setId("add-reachability-metadata");
            nativeAddMeta.addGoal("add-reachability-metadata");

            PluginExecution nativeBuild = new PluginExecution();
            nativeBuild.setId("build-native");
            nativeBuild.addGoal("compile-no-fork"); // Or "build"
            nativeBuild.setPhase("package");

            nativePlugin.addExecution(nativeAddMeta);
            nativePlugin.addExecution(nativeBuild);
            build.addPlugin(nativePlugin);
        }

        model.setBuild(build);
    }


    private void addSpringRepositories() {
        Repository springMilestones = new Repository();
        springMilestones.setId("spring-milestones");
        springMilestones.setName("Spring Milestones");
        springMilestones.setUrl("https://repo.spring.io/milestone");
        RepositoryPolicy snapshotsPolicy = new RepositoryPolicy();
        snapshotsPolicy.setEnabled(false); // Milestones are not typically snapshots
        springMilestones.setSnapshots(snapshotsPolicy);
        model.addRepository(springMilestones);

        // Spring Releases (often not needed if artifacts are on Central, but good for completeness)
        Repository springReleases = new Repository();
        springReleases.setId("spring-releases");
        springReleases.setName("Spring Releases");
        springReleases.setUrl("https://repo.spring.io/release");
        RepositoryPolicy releasesPolicy = new RepositoryPolicy();
        releasesPolicy.setEnabled(false);
        springReleases.setSnapshots(releasesPolicy); // Snapshots usually false for a release repo
        model.addRepository(springReleases);
    }

    public static void main(String... args) {
        // Example usage:
        // new CommandLine(new RagPomGenerator()).execute(
        //    "--instanceArtifactId=my-custom-rag",
        //    "--includeLlmOpenai=true",
        //    "--includeVectorstoreChroma=true",
        //    "--outputFile=my-custom-rag-pom.xml"
        // );
        // For native build:
        // new CommandLine(new RagPomGenerator()).execute(
        //    "--instanceArtifactId=my-native-rag",
        //    "--includeLlmOpenai=true",
        //    "--includeVectorstoreChroma=true",
        //    "--buildNative=true",
        //    "--outputFile=my-native-rag-pom.xml"
        // );
        new CommandLine(new RagPomGenerator()).execute(args);
    }
}