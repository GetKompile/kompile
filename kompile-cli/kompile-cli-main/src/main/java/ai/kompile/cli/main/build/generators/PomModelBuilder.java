/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.build.generators;

import ai.kompile.cli.main.build.config.BuildConfiguration;
import ai.kompile.cli.main.build.config.ModuleCatalog;
import ai.kompile.cli.main.build.config.ModuleSelection;
import org.apache.maven.model.*;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.*;
import java.util.*;

/**
 * Builds a Maven POM Model for a kompile application from a BuildConfiguration.
 * Extracted from RagPomGenerator's POM construction logic.
 */
public class PomModelBuilder {

    public static final String DEFAULT_SPRING_BOOT_VERSION = "3.2.5";
    public static final String DEFAULT_ND4J_VERSION = "1.0.0-SNAPSHOT";
    public static final String DEFAULT_SPRING_AI_VERSION = "1.0.0";
    public static final String DEFAULT_LOMBOK_VERSION = "1.18.38";
    public static final String DEFAULT_JACKSON_VERSION = "2.15.3";
    public static final String DEFAULT_GUAVA_VERSION = "32.1.3-jre";
    public static final String DEFAULT_LOG4J_VERSION = "2.24.3";
    public static final String DEFAULT_MAVEN_COMPILER_PLUGIN_VERSION = "3.13.0";
    public static final String DEFAULT_MAVEN_RESOURCES_PLUGIN_VERSION = "3.3.1";
    public static final String DEFAULT_MAVEN_JAR_PLUGIN_VERSION = "3.3.0";
    public static final String DEFAULT_POSTGRES_VERSION = "42.7.5";
    public static final String DEFAULT_NATIVE_MAVEN_PLUGIN_VERSION = "0.10.6";
    public static final String DEFAULT_BUILD_HELPER_MAVEN_PLUGIN_VERSION = "3.6.0";
    public static final String DEFAULT_JIB_MAVEN_PLUGIN_VERSION = "3.4.4";
    public static final String DEFAULT_JAKARTA_MAIL_VERSION = "2.1.3";
    public static final String DEFAULT_EMBEDDED_POSTGRES_VERSION = "2.0.7";
    public static final String CORE_APP_MAIN_CLASS_FQCN = "ai.kompile.app.MainApplication";
    public static final String LITE_APP_MAIN_CLASS_FQCN = "ai.kompile.lite.LiteApplication";

    private final BuildConfiguration config;
    private Model model;

    public PomModelBuilder(BuildConfiguration config) {
        this.config = config;
    }

    /**
     * Build the complete Maven Model.
     */
    public Model build() {
        model = new Model();
        model.setModelVersion("4.0.0");
        model.setGroupId(config.getInstanceGroupId());
        model.setArtifactId(config.getConfigName());
        model.setVersion(config.getInstanceVersion());
        model.setPackaging("jar");

        Parent parentPom = new Parent();
        parentPom.setGroupId("org.springframework.boot");
        parentPom.setArtifactId("spring-boot-starter-parent");
        parentPom.setVersion(DEFAULT_SPRING_BOOT_VERSION);
        model.setParent(parentPom);

        addProperties();
        addDependencyManagement();
        addDependencies();
        addBuildPlugins();

        if (config.isBuildNative()) {
            NativeProfileBuilder nativeBuilder = new NativeProfileBuilder(model);
            nativeBuilder.addNativeProfile(resolveMainClass());
        }

        if (config.isBuildContainer()) {
            ContainerProfileBuilder containerBuilder = new ContainerProfileBuilder(model, config);
            containerBuilder.addContainerProfile(resolveMainClass());
        }

        addSpringRepositories();

        return model;
    }

    /**
     * Write the model to a POM file.
     */
    public void writePom(File pomFile) throws IOException {
        if (model == null) {
            build();
        }
        try (FileWriter fileWriter = new FileWriter(pomFile)) {
            new MavenXpp3Writer().write(fileWriter, model);
        }
        System.out.println("Generated POM: " + pomFile.getAbsolutePath());
    }

    private String resolveMainClass() {
        ModuleSelection modules = config.getModules();
        if (modules.has("app-lite") && !modules.has("app-main")) {
            return LITE_APP_MAIN_CLASS_FQCN;
        }
        return CORE_APP_MAIN_CLASS_FQCN;
    }

    private void addProperties() {
        String mainClass = resolveMainClass();
        Properties props = new Properties();
        props.setProperty("java.version", "17");
        props.setProperty("start-class", mainClass);
        props.setProperty("kompile.project.version", config.getRagMcpVersion());
        props.setProperty("spring-boot.version", DEFAULT_SPRING_BOOT_VERSION);
        props.setProperty("spring-ai.version", DEFAULT_SPRING_AI_VERSION);
        props.setProperty("lombok.version", DEFAULT_LOMBOK_VERSION);
        props.setProperty("jackson.version", DEFAULT_JACKSON_VERSION);
        props.setProperty("guava.version", DEFAULT_GUAVA_VERSION);
        props.setProperty("log4j.version", DEFAULT_LOG4J_VERSION);
        props.setProperty("maven-compiler-plugin.version", DEFAULT_MAVEN_COMPILER_PLUGIN_VERSION);
        props.setProperty("maven-resources-plugin.version", DEFAULT_MAVEN_RESOURCES_PLUGIN_VERSION);
        props.setProperty("maven-jar-plugin.version", DEFAULT_MAVEN_JAR_PLUGIN_VERSION);
        props.setProperty("postgres.version", DEFAULT_POSTGRES_VERSION);
        props.setProperty("native-maven-plugin.version", DEFAULT_NATIVE_MAVEN_PLUGIN_VERSION);
        props.setProperty("build-helper-maven-plugin.version", DEFAULT_BUILD_HELPER_MAVEN_PLUGIN_VERSION);
        if (config.isBuildContainer()) {
            props.setProperty("jib-maven-plugin.version", DEFAULT_JIB_MAVEN_PLUGIN_VERSION);
        }
        boolean isLite = config.getModules().has("app-lite") && !config.getModules().has("app-main");
        props.setProperty("native.image.name", config.getConfigName() + (isLite ? "-lite" : "-native"));
        props.setProperty("embedded-postgres.version", DEFAULT_EMBEDDED_POSTGRES_VERSION);
        props.setProperty("instanceArtifactId", config.getConfigName());

        if (config.getJavacppPlatform() != null) {
            props.setProperty("javacpp.platform", config.getJavacppPlatform());
        }

        // ND4J CUDA version property — drives the backend artifact ID.
        // Default: 12.9. Override at build time with -Dnd4j.cuda.version=12.6
        if (config.getCudaVersion() != null && !config.getCudaVersion().isBlank()) {
            props.setProperty("nd4j.cuda.version", config.getCudaVersion());
        } else {
            props.setProperty("nd4j.cuda.version", "12.9");
        }

        // ND4J backend property — referenced by the ND4J backend dependencies below.
        // Uses ${nd4j.cuda.version} so CUDA version is overridable via a single property.
        if (config.getBackend() != null && !config.getBackend().isBlank()) {
            props.setProperty("backend", config.getBackend());
        }
        props.setProperty("nd4j.version", DEFAULT_ND4J_VERSION);

        String defaultLang = "en";
        if (config.getSupportedLanguages() != null && !config.getSupportedLanguages().isEmpty()) {
            defaultLang = config.getSupportedLanguages().get(0).toLowerCase().trim();
        }
        props.setProperty("kompile.opennlp.sentence.language", defaultLang);

        model.setProperties(props);
    }

    /**
     * Import the kompile-app BOM so transitive kompile dependencies (which may be
     * declared without an explicit version inside kompile-app-main's installed POM)
     * resolve cleanly in the generated project.
     */
    private void addDependencyManagement() {
        DependencyManagement dm = model.getDependencyManagement();
        if (dm == null) {
            dm = new DependencyManagement();
            model.setDependencyManagement(dm);
        }
        Dependency kompileBom = new Dependency();
        kompileBom.setGroupId("ai.kompile");
        kompileBom.setArtifactId("kompile-app");
        kompileBom.setVersion("${kompile.project.version}");
        kompileBom.setType("pom");
        kompileBom.setScope("import");
        dm.addDependency(kompileBom);
    }

    private void addDependencies() {
        List<Dependency> deps = new ArrayList<>();
        ModuleSelection modules = config.getModules();

        boolean isLite = modules.has("app-lite") && !modules.has("app-main");

        // ND4J backend (artifactId selected via ${backend}, e.g. nd4j-cuda-12.9 or
        // nd4j-native). Both the platform-agnostic jar and the platform-specific
        // classifier jar are required so the native libs make it onto the classpath.
        Dependency nd4j = new Dependency();
        nd4j.setGroupId("org.eclipse.deeplearning4j");
        nd4j.setArtifactId("${backend}");
        nd4j.setVersion("${nd4j.version}");
        deps.add(nd4j);

        if (config.getJavacppPlatform() != null && !config.getJavacppPlatform().isBlank()) {
            Dependency nd4jClassified = new Dependency();
            nd4jClassified.setGroupId("org.eclipse.deeplearning4j");
            nd4jClassified.setArtifactId("${backend}");
            nd4jClassified.setVersion("${nd4j.version}");
            nd4jClassified.setClassifier(config.getJavacppPlatform());
            deps.add(nd4jClassified);
        }

        // Always-included Spring Boot starters
        addDep(deps, "org.springframework.boot", "spring-boot-starter-web", "${spring-boot.version}");
        addDep(deps, "org.springframework.boot", "spring-boot-starter", "${spring-boot.version}");
        addDep(deps, "org.springframework.boot", "spring-boot-starter-validation", "${spring-boot.version}");
        // Required by kompile-app-main's WebSocket configurer (e.g. ChatBroadcastConfig).
        addDep(deps, "org.springframework.boot", "spring-boot-starter-websocket", "${spring-boot.version}");

        // MCP and Quartz only for non-lite builds
        if (!isLite) {
            addDep(deps, "org.springframework.boot", "spring-boot-starter-quartz", "${spring-boot.version}");
            addDep(deps, "org.springframework.ai", "spring-ai-starter-mcp-client", "${spring-ai.version}");
            addDep(deps, "org.springframework.ai", "spring-ai-starter-mcp-server", "${spring-ai.version}");
        }

        // Tokenizers native
        addDep(deps, "org.eclipse.deeplearning4j", "tokenizers-native", "${nd4j.version}", "compile",
                config.getJavacppPlatform(), false);

        // Jakarta mail
        addDep(deps, "jakarta.mail", "jakarta.mail-api", DEFAULT_JAKARTA_MAIL_VERSION);

        // Logging
        addDep(deps, "org.apache.logging.log4j", "log4j-api", "${log4j.version}");
        addDep(deps, "org.apache.logging.log4j", "log4j-core", "${log4j.version}");

        // PostgreSQL (if pgvector selected)
        if (modules.has("vectorstore-pgvector")) {
            addDep(deps, "org.postgresql", "postgresql", "${postgres.version}");
            addDep(deps, "org.springframework.boot", "spring-boot-starter-jdbc", "${spring-boot.version}");
        }

        // Add all selected kompile modules
        for (ModuleCatalog.ModuleEntry entry : modules.getAllEntries()) {
            addDep(deps, entry.getGroupId(), entry.getArtifactId(), "${kompile.project.version}");
        }

        // Common utility deps
        addDep(deps, "org.projectlombok", "lombok", "${lombok.version}", "provided", null, true);
        addDep(deps, "com.fasterxml.jackson.core", "jackson-databind", "${jackson.version}");
        addDep(deps, "com.google.guava", "guava", "${guava.version}");

        model.setDependencies(deps);
    }

    private void addBuildPlugins() {
        if (model.getBuild() == null) {
            model.setBuild(new Build());
        }
        Build build = model.getBuild();

        try {
            // Compiler plugin
            Plugin compilerPlugin = createPlugin("org.apache.maven.plugins", "maven-compiler-plugin",
                    "${maven-compiler-plugin.version}");
            Xpp3Dom compilerConfig = Xpp3DomBuilder.build(new StringReader(
                    "<configuration>" +
                            "  <release>${java.version}</release>\n" +
                            "<parameters>true</parameters>\n" +
                            "  <annotationProcessorPaths>" +
                            "    <path><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>${lombok.version}</version></path>" +
                            "    <path><groupId>org.springframework.boot</groupId><artifactId>spring-boot-configuration-processor</artifactId><version>${spring-boot.version}</version></path>" +
                            "  </annotationProcessorPaths>" +
                            "</configuration>"));
            compilerPlugin.setConfiguration(compilerConfig);
            build.addPlugin(compilerPlugin);

            // Resources plugin
            Plugin resourcesPlugin = createPlugin("org.apache.maven.plugins", "maven-resources-plugin",
                    "${maven-resources-plugin.version}");
            build.addPlugin(resourcesPlugin);

            // Jar plugin
            Plugin jarPlugin = createPlugin("org.apache.maven.plugins", "maven-jar-plugin",
                    "${maven-jar-plugin.version}");
            Xpp3Dom jarConfig = Xpp3DomBuilder.build(new StringReader(
                    "<configuration>" +
                            "  <archive><manifest><mainClass>${start-class}</mainClass><addClasspath>true</addClasspath><classpathPrefix>BOOT-INF/lib/</classpathPrefix></manifest></archive>" +
                            "</configuration>"));
            jarPlugin.setConfiguration(jarConfig);
            build.addPlugin(jarPlugin);

            // Spring Boot plugin
            Plugin springBootPlugin = createPlugin("org.springframework.boot", "spring-boot-maven-plugin",
                    "${spring-boot.version}");
            Xpp3Dom sbConfig = Xpp3DomBuilder.build(new StringReader(
                    "<configuration>" +
                            "  <mainClass>${start-class}</mainClass>" +
                            "  <excludes><exclude><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId></exclude></excludes>" +
                            "</configuration>"));
            springBootPlugin.setConfiguration(sbConfig);
            PluginExecution repackage = new PluginExecution();
            repackage.setId("repackage");
            repackage.addGoal("repackage");
            springBootPlugin.addExecution(repackage);
            build.addPlugin(springBootPlugin);

        } catch (XmlPullParserException | IOException e) {
            throw new RuntimeException("Error configuring build plugins", e);
        }
    }

    private void addSpringRepositories() {
        Repository springMilestones = new Repository();
        springMilestones.setId("spring-milestones");
        springMilestones.setName("Spring Milestones");
        springMilestones.setUrl("https://repo.spring.io/milestone");
        RepositoryPolicy snapshots = new RepositoryPolicy();
        snapshots.setEnabled(false);
        springMilestones.setSnapshots(snapshots);
        model.addRepository(springMilestones);

        Repository springReleases = new Repository();
        springReleases.setId("spring-releases");
        springReleases.setName("Spring Releases");
        springReleases.setUrl("https://repo.spring.io/release");
        RepositoryPolicy snapshots2 = new RepositoryPolicy();
        snapshots2.setEnabled(false);
        springReleases.setSnapshots(snapshots2);
        model.addRepository(springReleases);
    }

    // --- Helper methods ---

    private void addDep(List<Dependency> deps, String groupId, String artifactId, String version) {
        addDep(deps, groupId, artifactId, version, "compile", null, false);
    }

    private void addDep(List<Dependency> deps, String groupId, String artifactId, String version,
                         String scope, String classifier, boolean optional) {
        Dependency dep = new Dependency();
        dep.setGroupId(groupId);
        dep.setArtifactId(artifactId);
        dep.setVersion(version);
        if (scope != null && !scope.isEmpty()) dep.setScope(scope);
        if (classifier != null && !classifier.isEmpty()) dep.setClassifier(classifier);
        if (optional) dep.setOptional(true);
        deps.add(dep);
    }

    static Plugin createPlugin(String groupId, String artifactId, String version) {
        Plugin plugin = new Plugin();
        plugin.setGroupId(groupId);
        plugin.setArtifactId(artifactId);
        if (version != null && !version.isEmpty()) plugin.setVersion(version);
        return plugin;
    }

    static Xpp3Dom addChild(Xpp3Dom parent, String name, String value) {
        Xpp3Dom child = new Xpp3Dom(name);
        if (value != null) child.setValue(value);
        if (parent != null) parent.addChild(child);
        return child;
    }

    static void addBuildArg(Xpp3Dom buildArgsDom, String argValue) {
        if (buildArgsDom == null) return;
        Xpp3Dom arg = new Xpp3Dom("buildArg");
        arg.setValue(argValue);
        buildArgsDom.addChild(arg);
    }

    public Model getModel() {
        return model;
    }
}
