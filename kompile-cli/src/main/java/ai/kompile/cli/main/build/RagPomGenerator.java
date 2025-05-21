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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    @CommandLine.Option(names = {"--instanceArtifactId"}, description = "ArtifactId for the generated RAG instance", defaultValue = "kompile-sample")
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
    @CommandLine.Option(names = {"--includeLlmOpenai"}, description = "Include kompile-app-openai-llm module", defaultValue = "true", negatable = true)
    private boolean includeLlmOpenai;
    @CommandLine.Option(names = {"--includeLlmAnthropic"}, description = "Include kompile-app-anthropic-llm module")
    private boolean includeLlmAnthropic = false;
    @CommandLine.Option(names = {"--includeLlmGemini"}, description = "Include kompile-app-gemini-llm module")
    private boolean includeLlmGemini = false;
    @CommandLine.Option(names = {"--includeEmbeddingOpenai"}, description = "Include kompile-embedding-openai module", defaultValue = "true", negatable = true)
    private boolean includeEmbeddingOpenai;
    @CommandLine.Option(names = {"--includeEmbeddingSentenceTransformer"}, description = "Include kompile-embedding-sentence-transformer module")
    private boolean includeEmbeddingSentenceTransformer = false;
    @CommandLine.Option(names = {"--includeVectorstoreChroma"}, description = "Include kompile-vectorstore-chroma module")
    private boolean includeVectorstoreChroma = false;
    @CommandLine.Option(names = {"--includeVectorstorePgvector"}, description = "Include kompile-vectorstore-pgvector module", defaultValue = "true", negatable = true)
    private boolean includeVectorstorePgvector;
    @CommandLine.Option(names = {"--includeToolFilesystem"}, description = "Include kompile-tool-filesystem module", defaultValue = "true", negatable = true)
    private boolean includeToolFilesystem;
    @CommandLine.Option(names = {"--includeToolRag"}, description = "Include kompile-tool-rag module", defaultValue = "true", negatable = true)
    private boolean includeToolRag;

    @CommandLine.Option(names = {"--buildNative"}, description = "Configure build for GraalVM native image", defaultValue = "true")
    private boolean buildNative = true;

    private Model model;
    private final List<Dependency> defaultDependencies = new ArrayList<>();

    private static final String DEFAULT_SPRING_BOOT_VERSION = "3.4.5";
    private static final String DEFAULT_SPRING_AI_VERSION = "1.0.0";
    private static final String DEFAULT_LOMBOK_VERSION = "1.18.38";
    private static final String DEFAULT_JACKSON_VERSION = "2.15.3";
    private static final String DEFAULT_GUAVA_VERSION = "32.1.3-jre";
    private static final String DEFAULT_LOG4J_VERSION = "2.24.3";

    private static final String DEFAULT_MAVEN_COMPILER_PLUGIN_VERSION = "3.13.0";
    private static final String DEFAULT_MAVEN_RESOURCES_PLUGIN_VERSION = "3.3.1";
    private static final String DEFAULT_MAVEN_JAR_PLUGIN_VERSION = "3.3.0";
    private static final String DEFAULT_NATIVE_MAVEN_PLUGIN_VERSION = "0.10.6";
    private static final String DEFAULT_BUILD_HELPER_MAVEN_PLUGIN_VERSION = "3.6.0";

    private static final String DEFAULT_JAKARTA_MAIL_VERSION = "2.1.3";
    private static final String GENERATED_MAIN_CLASS_SIMPLE_NAME = "GeneratedMainApplication";
    private static final String CORE_APP_MAIN_CLASS_FQCN = "ai.kompile.app.MainApplication";

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

    private Xpp3Dom addChild(Xpp3Dom parent, String name, String value) {
        Xpp3Dom child = new Xpp3Dom(name);
        if (value != null) {
            child.setValue(value);
        }
        if (parent != null) {
            parent.addChild(child);
        }
        return child;
    }

    private void addBuildArg(Xpp3Dom buildArgsDom, String argValue) {
        if (buildArgsDom == null) return;
        Xpp3Dom buildArgElement = new Xpp3Dom("buildArg");
        buildArgElement.setValue(argValue);
        buildArgsDom.addChild(buildArgElement);
    }

    private void generateMainApplicationClass(File projectBaseDir, String packageName, String className) throws IOException {
        String packagePath = packageName.replace('.', '/');
        Path mainJavaDir = Paths.get(projectBaseDir.getAbsolutePath(), "src", "main", "java", packagePath);
        Files.createDirectories(mainJavaDir);

        Path mainAppFile = mainJavaDir.resolve(className + ".java");
        String mainAppContent = String.format(
                "package %s;\n\n" +
                        "import ai.kompile.app.MainApplication;\n" +
                        "import org.springframework.boot.SpringApplication;\n" +
                        "import org.springframework.boot.autoconfigure.SpringBootApplication;\n" +
                        "import org.springframework.context.annotation.Import;\n\n" +
                        "@SpringBootApplication\n" +
                        "@Import(MainApplication.class)\n" +
                        "public class %s {\n\n" +
                        "    public static void main(String[] args) {\n" +
                        "        SpringApplication.run(%s.class, args);\n" +
                        "    }\n" +
                        "}\n",
                packageName, className, className
        );

        try (FileWriter writer = new FileWriter(mainAppFile.toFile())) {
            writer.write(mainAppContent);
        }
        System.out.println("Generated main application class for JAR manifest: " + mainAppFile.toAbsolutePath());
    }


    @Override
    public Void call() throws Exception {
        model = new Model();
        model.setModelVersion("4.0.0");
        model.setGroupId(instanceGroupId);
        model.setArtifactId(instanceArtifactId);
        model.setVersion(instanceVersion);
        model.setPackaging("jar");

        Parent parent = new Parent();
        parent.setGroupId("org.springframework.boot");
        parent.setArtifactId("spring-boot-starter-parent");
        parent.setVersion("3.4.5");
        model.setParent(parent);
        File projectDir = outputFile.getParentFile();
        if (projectDir == null) {
            projectDir = new File(".").getAbsoluteFile();
        }
        if (!projectDir.exists() && !projectDir.mkdirs()) {
            throw new IOException("Could not create project directory: " + projectDir.getAbsolutePath());
        }
        if (!projectDir.isDirectory()) {
            projectDir = outputFile.getCanonicalFile().getParentFile();
            if (projectDir == null) {
                projectDir = new File(".");
            }
        }

        String jarManifestStartClass;
        String nativeImageEntryPointClass;

        if (buildNative) {
            jarManifestStartClass = CORE_APP_MAIN_CLASS_FQCN;
            generateMainApplicationClass(projectDir, instanceGroupId, GENERATED_MAIN_CLASS_SIMPLE_NAME);
            nativeImageEntryPointClass = CORE_APP_MAIN_CLASS_FQCN; // Point native image to the core app
        } else {
            if (includeAppMain) {
                jarManifestStartClass = CORE_APP_MAIN_CLASS_FQCN;
            } else {
                jarManifestStartClass = instanceGroupId + "." + GENERATED_MAIN_CLASS_SIMPLE_NAME;
                Path generatedMainJavaFileParent = Paths.get(projectDir.getAbsolutePath(), "src", "main", "java", jarManifestStartClass.replace('.', '/'));
                if (!Files.exists(generatedMainJavaFileParent)) {
                    Files.createDirectories(generatedMainJavaFileParent);
                }
                Path generatedMainJavaFile = generatedMainJavaFileParent.resolve(GENERATED_MAIN_CLASS_SIMPLE_NAME + ".java");
                if (!Files.exists(generatedMainJavaFile)) {
                    generateMainApplicationClass(projectDir, instanceGroupId, GENERATED_MAIN_CLASS_SIMPLE_NAME);
                }
            }
            nativeImageEntryPointClass = jarManifestStartClass; // Not used if !buildNative
        }

        Properties props = new Properties();
        props.setProperty("java.version", "17");
        props.setProperty("start-class", jarManifestStartClass);

        props.setProperty("kompile.project.version", this.ragMcpVersion);
        props.setProperty("spring-boot.version", DEFAULT_SPRING_BOOT_VERSION);
        props.setProperty("spring-ai.version", DEFAULT_SPRING_AI_VERSION);
        props.setProperty("lombok.version", DEFAULT_LOMBOK_VERSION);
        props.setProperty("jackson.version", DEFAULT_JACKSON_VERSION);
        props.setProperty("guava.version", DEFAULT_GUAVA_VERSION);
        props.setProperty("log4j.version", DEFAULT_LOG4J_VERSION);
        props.setProperty("maven-compiler-plugin.version", DEFAULT_MAVEN_COMPILER_PLUGIN_VERSION);
        props.setProperty("maven-resources-plugin.version", DEFAULT_MAVEN_RESOURCES_PLUGIN_VERSION);
        props.setProperty("maven-jar-plugin.version", DEFAULT_MAVEN_JAR_PLUGIN_VERSION);

        props.setProperty("native-maven-plugin.version", DEFAULT_NATIVE_MAVEN_PLUGIN_VERSION);
        props.setProperty("build-helper-maven-plugin.version", DEFAULT_BUILD_HELPER_MAVEN_PLUGIN_VERSION);
        props.setProperty("native.image.name", this.instanceArtifactId + "-native");

        model.setProperties(props);

        addApplicationDependencies();
        addApplicationBuild();

        addNativeProfile(nativeImageEntryPointClass);

        addSpringRepositories();

        MavenXpp3Writer mavenXpp3Writer = new MavenXpp3Writer();
        try (FileWriter fileWriter = new FileWriter(outputFile)) {
            mavenXpp3Writer.write(fileWriter, model);
            System.out.println("Successfully generated RAG application POM: " + outputFile.getAbsolutePath());
        }
        return null;
    }

    private void addApplicationDependencies() {
        defaultDependencies.clear();
        addDependency(defaultDependencies, "org.springframework.boot", "spring-boot-starter-web", "${spring-boot.version}");
        addDependency(defaultDependencies, "org.springframework.boot", "spring-boot-starter", "${spring-boot.version}");
        addDependency(defaultDependencies, "org.springframework.ai", "spring-ai-starter-mcp-client", "${spring-ai.version}");
        addDependency(defaultDependencies, "org.springframework.ai", "spring-ai-starter-mcp-server", "${spring-ai.version}");
        addDependency(defaultDependencies,"jakarta.mail", "jakarta.mail-api",DEFAULT_JAKARTA_MAIL_VERSION);
        addDependency(defaultDependencies, "org.apache.logging.log4j", "log4j-api", "${log4j.version}");
        addDependency(defaultDependencies, "org.apache.logging.log4j", "log4j-core", "${log4j.version}");

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

    private void addApplicationBuild() {
        if (model.getBuild() == null) {
            model.setBuild(new Build());
        }
        Build build = model.getBuild();
        try {
            Plugin compilerPlugin = createPlugin("org.apache.maven.plugins", "maven-compiler-plugin", "${maven-compiler-plugin.version}");
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

            Plugin resourcesPlugin = createPlugin("org.apache.maven.plugins", "maven-resources-plugin", "${maven-resources-plugin.version}");
            PluginExecution defaultResourcesExecution = new PluginExecution();
            defaultResourcesExecution.setId("default-resources");
            defaultResourcesExecution.addGoal("resources");
            // Using default configuration for resources plugin by not setting <configuration> explicitly for the execution.
            resourcesPlugin.addExecution(defaultResourcesExecution);
            build.addPlugin(resourcesPlugin);


            Plugin jarPlugin = createPlugin("org.apache.maven.plugins", "maven-jar-plugin", "${maven-jar-plugin.version}");
            Xpp3Dom jarConfig = Xpp3DomBuilder.build(new StringReader(
                    "<configuration>" +
                            "  <archive><manifest><mainClass>${start-class}</mainClass><addClasspath>true</addClasspath><classpathPrefix>BOOT-INF/lib/</classpathPrefix></manifest></archive>" +
                            "</configuration>"));
            jarPlugin.setConfiguration(jarConfig);
            build.addPlugin(jarPlugin);

            // This spring-boot-maven-plugin is for the main build (non-native).
            // The native profile will define its own complete spring-boot-maven-plugin configuration.
            Plugin springBootMainBuildPlugin = createPlugin("org.springframework.boot", "spring-boot-maven-plugin", "${spring-boot.version}");
            Xpp3Dom springBootMainConfig = Xpp3DomBuilder.build(new StringReader(
                    "<configuration>" +
                            "  <mainClass>${start-class}</mainClass>" +
                            "  <excludes><exclude><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId></exclude></excludes>" +
                            "</configuration>"));
            springBootMainBuildPlugin.setConfiguration(springBootMainConfig);
            PluginExecution springBootRepackageMain = new PluginExecution();
            springBootRepackageMain.setId("repackage"); // Standard ID
            springBootRepackageMain.addGoal("repackage");
            springBootMainBuildPlugin.addExecution(springBootRepackageMain);
            build.addPlugin(springBootMainBuildPlugin);

        } catch (XmlPullParserException | IOException e) {
            throw new RuntimeException("Error configuring build plugins", e);
        }
    }

    private void addNativeProfile(String nativeImageMainClassFqcn) {
        Profile nativeProfile = new Profile();
        nativeProfile.setId("native");
        Build nativeProfileBuild = new Build();

        // 1. Spring Boot Maven Plugin - Fully configured for the native profile
        Plugin springBootPluginNative = createPlugin("org.springframework.boot", "spring-boot-maven-plugin", "${spring-boot.version}");
        try {
            Xpp3Dom springBootNativeConfig = Xpp3DomBuilder.build(new StringReader(
                    "<configuration>" +
                            "  <mainClass>${start-class}</mainClass>" + // ${start-class} is GeneratedMainApplication
                            "  <classifier>exec</classifier>" + // Create a distinct JAR: artifactId-version-exec.jar
                            "  <excludes><exclude><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId></exclude></excludes>" +
                            "</configuration>"
            ));
            springBootPluginNative.setConfiguration(springBootNativeConfig);
        } catch (XmlPullParserException | IOException e) {
            throw new RuntimeException("Error configuring spring-boot-maven-plugin in native profile", e);
        }

        PluginExecution processAotExecution = new PluginExecution();
        processAotExecution.setId("process-aot");
        processAotExecution.addGoal("process-aot");
        springBootPluginNative.addExecution(processAotExecution);

        PluginExecution repackageExecutionNative = new PluginExecution();
        // Use a different ID for the repackage execution in the native profile
        // to ensure it uses the profile-specific configuration (like the classifier).
        repackageExecutionNative.setId("repackage-native-profile");
        repackageExecutionNative.addGoal("repackage");
        springBootPluginNative.addExecution(repackageExecutionNative);
        nativeProfileBuild.addPlugin(springBootPluginNative);

        // 2. Build Helper Maven Plugin
        Plugin buildHelperPlugin = createPlugin("org.codehaus.mojo", "build-helper-maven-plugin", "${build-helper-maven-plugin.version}");
        PluginExecution addAotSourcesExecution = new PluginExecution();
        addAotSourcesExecution.setId("add-spring-aot-sources");
        addAotSourcesExecution.addGoal("add-source");
        addAotSourcesExecution.setPhase("generate-sources");
        try {
            Xpp3Dom addAotSourcesConfig = Xpp3DomBuilder.build(new StringReader(
                    "<configuration><sources><source>${project.build.directory}/spring-aot/main/sources</source></sources></configuration>"
            ));
            addAotSourcesExecution.setConfiguration(addAotSourcesConfig);
        } catch (XmlPullParserException | IOException e) {
            throw new RuntimeException("Error configuring build-helper-maven-plugin for AOT sources", e);
        }
        buildHelperPlugin.addExecution(addAotSourcesExecution);

        PluginExecution addAotResourcesExecution = new PluginExecution();
        addAotResourcesExecution.setId("add-spring-aot-resources");
        addAotResourcesExecution.addGoal("add-resource");
        addAotResourcesExecution.setPhase("generate-resources");
        try {
            Xpp3Dom addAotResourcesConfig = Xpp3DomBuilder.build(new StringReader(
                    "<configuration><resources><resource><directory>${project.build.directory}/spring-aot/main/resources</directory></resource></resources></configuration>"
            ));
            addAotResourcesExecution.setConfiguration(addAotResourcesConfig);
        } catch (XmlPullParserException | IOException e) {
            throw new RuntimeException("Error configuring build-helper-maven-plugin for AOT resources", e);
        }
        buildHelperPlugin.addExecution(addAotResourcesExecution);
        nativeProfileBuild.addPlugin(buildHelperPlugin);

        // 3. Native Maven Plugin (GraalVM Build Tools)
        Plugin nativeMavenPlugin = createPlugin("org.graalvm.buildtools", "native-maven-plugin", "${native-maven-plugin.version}");
        nativeMavenPlugin.setExtensions(true);

        Xpp3Dom nativePluginConfig = new Xpp3Dom("configuration");
        addChild(nativePluginConfig, "imageName", "${native.image.name}");
        addChild(nativePluginConfig, "mainClass", nativeImageMainClassFqcn); // This is CORE_APP_MAIN_CLASS_FQCN
        addChild(nativePluginConfig, "quickBuild", "false");
        // Explicitly tell native-maven-plugin to use the classified JAR
        addChild(nativePluginConfig, "jarArtifact", "${project.build.directory}/${project.build.finalName}-exec.jar");


        Xpp3Dom metadataRepoElement = addChild(nativePluginConfig, "metadataRepository", null);
        addChild(metadataRepoElement, "enabled", "true");

        Xpp3Dom buildArgsDom = new Xpp3Dom("buildArgs");
        addBuildArg(buildArgsDom, "-J-Xmx16g");
        addBuildArg(buildArgsDom, "--verbose");
        addBuildArg(buildArgsDom, "--no-fallback");
        addBuildArg(buildArgsDom, "--allow-incomplete-classpath");
        addBuildArg(buildArgsDom, "-H:+ReportExceptionStackTraces");
        addBuildArg(buildArgsDom, "-Dspring.native.remove-unused-autoconfig=true");
        addBuildArg(buildArgsDom, "-H:-AddAllFileSystemProviders");
        addBuildArg(buildArgsDom, "--enable-url-protocols=http,https");
        addBuildArg(buildArgsDom, "-Djava.awt.headless=true");
        String initializeAtBuildTimeArg = "org.apache.logging.log4j.Util,org.apache.logging.log4j.status.StatusLogger,org.apache.logging.log4j.util.ProviderUtil,org.apache.logging.log4j.util.PropertySource$Util,org.apache.logging.log4j.core.impl.Log4jProvider,org.apache.logging.log4j.spi.AbstractLogger,org.apache.logging.log4j.core.impl.Log4jContextFactory,org.apache.logging.log4j.core.selector.ClassLoaderContextSelector,org.apache.logging.log4j.core.LifeCycle$State,org.apache.logging.log4j.status.StatusLogger,org.apache.logging.log4j.spi.StandardLevel,,org.apache.logging.log4j.util.Strings,org.apache.logging.log4j.Level,org.apache.logging.log4j.util.PropertiesUtil,org.apache.logging.log4j.util.OsgiServiceLocator,org.apache.logging.log4j.util.PropertyFilePropertySource,org.apache.logging.log4j.message.ParameterFormatter,org.apache.logging.log4j.status.StatusLogger$Config,org.apache.logging.log4j.status.StatusLogger$InstanceHolder";
        addBuildArg(buildArgsDom, "--initialize-at-build-time=" + initializeAtBuildTimeArg);
        String initializeAtRunTimeArg = "java.rmi.server,sun.java.rmi.server,sun.rmi.transport,org.apache.tomcat.jni.SSL,sun.awt.X11GraphicsConfig,reactor.core.scheduler.BoundedElasticScheduler,reactor.core.scheduler.Schedulers,org.springframework.web.reactive.function.client.DefaultExchangeStrategiesBuilder,org.springframework.boot.loader.ref.DefaultCleaner,org.apache.tomcat.util.net.openssl.OpenSSLContext,org.apache.tomcat.util.net.openssl.OpenSSLEngine,sun.awt.dnd.SunDropTargetContextPeer$EventDispatcher,org.springframework.core.io.VfsUtils,org.springframework.boot.loader.ref.Cleaner,org.springframework.boot.loader.ref.DefaultCleaner,reactor.core.scheduler.BoundedElasticScheduler,reactor.core.scheduler.Schedulers,reactor.core.scheduler.BoundedElasticScheduler,org.springframework.web.reactive.function.client.DefaultExchangeStrategiesBuilder,reactor.core.scheduler.SchedulerState$DisposeAwaiterRunnable,org.apache.catalina.mbeans.MBeanUtils,org.apache.catalina.mbeans.MBeanFactory";
        addBuildArg(buildArgsDom, "--initialize-at-run-time=" + initializeAtRunTimeArg);
        addBuildArg(buildArgsDom, "-H:IncludeResources=log4j2.xml");
        addBuildArg(buildArgsDom,"--trace-class-initialization=java.security.SecureRandom,com.sun.jndi.dns.DnsClient");
        addBuildArg(buildArgsDom, "-H:IncludeResources=log4j2-spring.xml");
        addBuildArg(buildArgsDom, "-H:IncludeResources=log4j2.component.properties");
        addBuildArg(buildArgsDom, "-H:IncludeResources=.*Log4j2Plugins.dat$");
        addBuildArg(buildArgsDom, "-H:IncludeResources=META-INF/services/org.apache.logging.log4j.spi.Provider");
        addBuildArg(buildArgsDom, "-H:+AllowDeprecatedBuilderClassesOnImageClasspath");
        addBuildArg(buildArgsDom, "-H:IncludeResources=META-INF/native-image/.*\\.json");
        addBuildArg(buildArgsDom, "-H:IncludeResources=META-INF/services/.*");
        addBuildArg(buildArgsDom, "-H:IncludeResources=ai/kompile/.*\\.schema\\.json");
        addBuildArg(buildArgsDom, "-H:IncludeResources=META-INF/spring/.*\\.imports");
        addBuildArg(buildArgsDom, "-H:IncludeResources=META-INF/spring\\.components");
        addBuildArg(buildArgsDom, "-H:DeadlockWatchdogInterval=30");
        addBuildArg(buildArgsDom, "-H:+DeadlockWatchdogExitOnTimeout");
        addBuildArg(buildArgsDom,"--trace-class-initialization=sun.rmi.server.UnicastRef,java.rmi.server.LogStream,com.sun.jndi.dns.DnsClient");
        nativePluginConfig.addChild(buildArgsDom);
        nativeMavenPlugin.setConfiguration(nativePluginConfig);

        PluginExecution nativeBuildExecution = new PluginExecution();
        nativeBuildExecution.setId("build-native");
        nativeBuildExecution.addGoal("compile-no-fork");
        nativeBuildExecution.setPhase("package");
        nativeMavenPlugin.addExecution(nativeBuildExecution);

        nativeProfileBuild.addPlugin(nativeMavenPlugin);

        nativeProfile.setBuild(nativeProfileBuild);
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

    private Plugin createPlugin(String groupId, String artifactId, String version) {
        Plugin plugin = new Plugin();
        plugin.setGroupId(groupId);
        plugin.setArtifactId(artifactId);
        if (version != null && !version.isEmpty()) {
            plugin.setVersion(version);
        }
        return plugin;
    }

    public static void main(String... args) {
        int exitCode = new CommandLine(new RagPomGenerator()).execute(args);
        System.exit(exitCode);
    }
}