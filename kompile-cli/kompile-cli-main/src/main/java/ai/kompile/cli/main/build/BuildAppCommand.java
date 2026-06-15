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

package ai.kompile.cli.main.build;

import ai.kompile.cli.main.Info;
import ai.kompile.cli.main.build.config.*;
import ai.kompile.cli.main.build.generators.*;
import ai.kompile.cli.main.util.EnvironmentUtils;
import ai.kompile.modelmanager.KompileModelManager;
import ai.kompile.modelmanager.ModelConstants;
import ai.kompile.modelmanager.ModelDescriptor;
import ai.kompile.modelmanager.ModelType;
import org.apache.commons.io.FileUtils;
import org.apache.maven.model.Model;
import org.apache.maven.shared.invoker.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@Command(name = "app", mixinStandardHelpOptions = true,
        description = "Build a kompile application from a preset or custom module selection.\n\n" +
                "Examples:\n" +
                "  kompile build app --wizard\n" +
                "  kompile build app --configName=myapp --preset=hosted-llm-rag\n" +
                "  kompile build app --configName=myapp --llm=llm-openai,llm-anthropic --embedding=embedding-anserini\n" +
                "  kompile build app --configName=myapp --preset=full --exclude=graph-neo4j\n")
public class BuildAppCommand implements Callable<Integer> {

    // --- Wizard mode ---
    @Option(names = {"--wizard", "-w"}, description = "Launch interactive build wizard")
    private boolean wizard;

    // --- Required (unless --wizard is used) ---
    @Option(names = {"--configName"}, description = "Name for this application instance (used as artifactId)")
    private String configName;

    // --- Preset and module selection ---
    @Option(names = {"--preset"}, description = "Application preset: hosted-llm-rag, samediff-rag, pipeline, full, minimal. Default: hosted-llm-rag",
            defaultValue = "hosted-llm-rag")
    private String presetName;

    @Option(names = {"--llm"}, description = "Override LLM modules (comma-separated module IDs)", split = ",")
    private List<String> llmOverride;

    @Option(names = {"--embedding"}, description = "Override embedding modules (comma-separated module IDs)", split = ",")
    private List<String> embeddingOverride;

    @Option(names = {"--vectorstore"}, description = "Override vectorstore modules (comma-separated module IDs)", split = ",")
    private List<String> vectorstoreOverride;

    @Option(names = {"--loaders"}, description = "Override loader modules (comma-separated module IDs)", split = ",")
    private List<String> loadersOverride;

    @Option(names = {"--chunkers"}, description = "Override chunker modules (comma-separated module IDs)", split = ",")
    private List<String> chunkersOverride;

    @Option(names = {"--tools"}, description = "Override tool modules (comma-separated module IDs)", split = ",")
    private List<String> toolsOverride;

    @Option(names = {"--include"}, description = "Include additional module IDs (comma-separated)", split = ",")
    private List<String> includeModules;

    @Option(names = {"--exclude"}, description = "Exclude module IDs (comma-separated)", split = ",")
    private List<String> excludeModules;

    // --- Build options ---
    @Option(names = {"--outputDir"}, description = "Base directory for build output", defaultValue = "kompile-rag-builds")
    private File outputDirBase;

    @Option(names = {"--native"}, description = "Build GraalVM native image", defaultValue = "true", negatable = true)
    private boolean buildNative;

    @Option(names = {"--skipTests"}, description = "Skip Maven tests", defaultValue = "true", negatable = true)
    private boolean skipTests;

    @Option(names = {"--cleanBuild"}, description = "Clean before build", defaultValue = "true", negatable = true)
    private boolean cleanBuild;

    @Option(names = {"--skipMavenBuild"}, description = "Generate POM and application.properties only; do not invoke Maven",
            defaultValue = "false")
    private boolean skipMavenBuild;

    @Option(names = {"--container"}, description = "Build container image using Google Jib (no Docker daemon required for registry push)",
            defaultValue = "false", negatable = true)
    private boolean buildContainer;

    @Option(names = {"--containerImage"}, description = "Full container image name (e.g., myregistry.io/myapp). Default: kompile/<configName>")
    private String containerImageName;

    @Option(names = {"--containerBaseImage"}, description = "Base image for container. Default: eclipse-temurin:17-jre",
            defaultValue = "eclipse-temurin:17-jre")
    private String containerBaseImage;

    @Option(names = {"--containerRegistry"}, description = "Container registry prefix (e.g., gcr.io/my-project, docker.io/myuser)")
    private String containerRegistry;

    @Option(names = {"--containerPorts"}, description = "Ports to expose in container (comma-separated)", split = ",",
            defaultValue = "8080")
    private List<String> containerPorts;

    @Option(names = {"--containerJvmFlags"}, description = "Additional JVM flags for container runtime (comma-separated)", split = ",")
    private List<String> containerJvmFlags;

    @Option(names = {"--javacppPlatform"}, description = "JavaCPP platform (e.g., linux-x86_64)")
    private String javacppPlatform = "linux-x86_64";

    @Option(names = {"--javacppExtension"}, description = "JavaCPP extension (e.g., avx2, cuda)")
    private String javacppExtension;

    @Option(names = {"--backend"}, description = "ND4J backend artifactId: nd4j-cuda-12.9, nd4j-native, etc. Default: nd4j-cuda-12.9",
            defaultValue = "nd4j-cuda-12.9")
    private String backend;

    @Option(names = {"--mavenHome"}, description = "Path to Maven installation")
    private File mavenHome;

    @Option(names = {"--graalVmHome"}, description = "Path to GraalVM installation (for native builds)")
    private File graalVmHome;

    // --- App metadata ---
    @Option(names = {"--appTitle"}, description = "Application title for UI banner", defaultValue = "Kompile RAG Console")
    private String appTitle;

    @Option(names = {"--instanceGroupId"}, description = "Maven groupId", defaultValue = "ai.kompile.rag.instance")
    private String instanceGroupId;

    @Option(names = {"--instanceVersion"}, description = "Maven version", defaultValue = "0.1.0-SNAPSHOT")
    private String instanceVersion;

    @Option(names = {"--ragMcpVersion"}, description = "Kompile modules version", defaultValue = "0.1.0-SNAPSHOT")
    private String ragMcpVersion;

    // --- Database options ---
    @Option(names = {"--databaseUrl"}, description = "PostgreSQL database URL", defaultValue = "jdbc:postgresql://localhost:5432/kompile_db")
    private String databaseUrl;

    @Option(names = {"--databaseUsername"}, description = "Database username", defaultValue = "postgres")
    private String databaseUsername;

    @Option(names = {"--databasePassword"}, description = "Database password", defaultValue = "postgres")
    private String databasePassword;

    @Option(names = {"--enableSchemaInit"}, description = "Auto-initialize SQL schema", defaultValue = "true", negatable = true)
    private boolean enableSchemaInit;

    // --- Model options ---
    @Option(names = {"--supportedLanguages"}, description = "Language codes for OpenNLP models", split = ",", defaultValue = "en")
    private List<String> supportedLanguages;

    @Option(names = {"--anserini-indexes"}, description = "Anserini prebuilt index IDs", split = ",", arity = "0..*")
    private List<String> anseriniIndexIds = new ArrayList<>();

    @Option(names = {"--anserini-encoders"}, description = "Anserini encoder model IDs", split = ",", arity = "0..*")
    private List<String> anseriniEncoderModelIds = new ArrayList<>();

    // --- Archive options ---
    @Option(names = {"--archivePath"}, description = "Path to .karch archive for offline deployments")
    private File archivePath;

    @Option(names = {"--modelSourceType"}, description = "Model source: ARCHIVE, REGISTRY, HYBRID", defaultValue = "HYBRID")
    private String modelSourceType;

    @Option(names = {"--registryUrls"}, description = "Remote model registry URLs", split = ",")
    private List<String> registryUrls;

    @Option(names = {"--archiveOnly"}, description = "Offline-only mode", defaultValue = "false")
    private boolean archiveOnly;

    // Internal: set by wizard to override module selection directly
    private Set<String> wizardModuleIds;

    @Override
    public Integer call() throws Exception {
        // Launch wizard if requested or if configName is missing
        if (wizard || configName == null) {
            BuildWizard.WizardResult result = BuildWizard.run();
            if (result == null) {
                if (!wizard && configName == null) {
                    System.err.println("Error: --configName is required. Use --wizard for interactive setup.");
                }
                return 1;
            }
            configName = result.configName;
            if (result.preset != null) {
                presetName = result.preset.name().toLowerCase().replace("_", "-");
            }
            buildNative = result.buildNative;
            javacppPlatform = result.javacppPlatform;
            // Apply wizard module selection as include/exclude overrides
            wizardModuleIds = result.moduleIds;
        }

        // 1. Resolve preset
        BuildPreset preset = resolvePreset(presetName);

        // 2. Build module selection
        ModuleSelection modules;
        if (wizardModuleIds != null) {
            // Wizard provided an explicit module set — use it directly
            ModuleSelection.Builder selBuilder = ModuleSelection.empty();
            selBuilder.include(wizardModuleIds);
            modules = selBuilder.build();
        } else {
            ModuleSelection.Builder selBuilder = ModuleSelection.fromPreset(preset);

            // Apply category overrides
            if (llmOverride != null) selBuilder.overrideCategory(ModuleCatalog.Category.LLM, llmOverride);
            if (embeddingOverride != null) selBuilder.overrideCategory(ModuleCatalog.Category.EMBEDDING, embeddingOverride);
            if (vectorstoreOverride != null) selBuilder.overrideCategory(ModuleCatalog.Category.VECTORSTORE, vectorstoreOverride);
            if (loadersOverride != null) selBuilder.overrideCategory(ModuleCatalog.Category.LOADER, loadersOverride);
            if (chunkersOverride != null) selBuilder.overrideCategory(ModuleCatalog.Category.CHUNKER, chunkersOverride);
            if (toolsOverride != null) selBuilder.overrideCategory(ModuleCatalog.Category.TOOL, toolsOverride);

            // Apply fine-grained include/exclude
            if (includeModules != null) selBuilder.include(includeModules);
            if (excludeModules != null) selBuilder.exclude(excludeModules);

            modules = selBuilder.build();
        }

        // 3. Build configuration
        BuildConfiguration config = BuildConfiguration.builder()
                .configName(configName)
                .outputDir(outputDirBase)
                .modules(modules)
                .buildNative(buildNative)
                .skipTests(skipTests)
                .cleanBuild(cleanBuild)
                .javacppPlatform(javacppPlatform)
                .javacppExtension(javacppExtension)
                .backend(backend)
                .mavenHome(mavenHome)
                .graalVmHome(graalVmHome)
                .appTitle(appTitle)
                .instanceGroupId(instanceGroupId)
                .instanceVersion(instanceVersion)
                .ragMcpVersion(ragMcpVersion)
                .databaseUrl(databaseUrl)
                .databaseUsername(databaseUsername)
                .databasePassword(databasePassword)
                .enableSchemaInit(enableSchemaInit)
                .supportedLanguages(supportedLanguages)
                .anseriniIndexIds(anseriniIndexIds)
                .anseriniEncoderModelIds(anseriniEncoderModelIds)
                .archivePath(archivePath)
                .modelSourceType(modelSourceType)
                .registryUrls(registryUrls)
                .archiveOnly(archiveOnly)
                .buildContainer(buildContainer)
                .containerImageName(containerImageName)
                .containerBaseImage(containerBaseImage)
                .containerRegistry(containerRegistry)
                .containerPorts(containerPorts)
                .containerJvmFlags(containerJvmFlags)
                .build();

        // 4. Setup directories
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
            System.err.println("Failed to create project directory: " + projectBuildDir.getAbsolutePath());
            return 1;
        }

        // 5. Ensure models are available
        KompileModelManager modelManager = new KompileModelManager();
        Map<String, Path> resolvedModelPaths = new HashMap<>();
        ensureModelsAvailable(config, modelManager, resolvedModelPaths);

        // 6. Generate POM
        File pomFile = new File(projectBuildDir, "pom.xml");
        PomModelBuilder pomBuilder = new PomModelBuilder(config);
        pomBuilder.build();
        pomBuilder.writePom(pomFile);

        // 7. Generate application.properties
        ApplicationPropertiesGenerator propsGen = new ApplicationPropertiesGenerator(config, modelManager, resolvedModelPaths);
        propsGen.generate(projectBuildDir);

        // 8. Generate database schema if needed
        DatabaseSchemaGenerator dbGen = new DatabaseSchemaGenerator(config);
        dbGen.generate(projectBuildDir);

        // 9. Copy archive if specified
        if (archivePath != null && archivePath.exists()) {
            embedArchive(projectBuildDir);
        }

        // 10. Print module summary
        printEnabledModules(modules);

        // 11. Invoke Maven build (unless explicitly skipped)
        if (skipMavenBuild) {
            System.out.println("\n--skipMavenBuild set; generated POM + application.properties only.");
            System.out.println("  Project: " + projectBuildDir.getAbsolutePath());
            return 0;
        }
        return invokeMaven(projectBuildDir, pomFile);
    }

    private BuildPreset resolvePreset(String name) {
        try {
            return BuildPreset.valueOf(name.toUpperCase().replace("-", "_"));
        } catch (IllegalArgumentException e) {
            System.err.println("Unknown preset: " + name + ". Available: " +
                    Arrays.stream(BuildPreset.values()).map(p -> p.name().toLowerCase().replace("_", "-"))
                            .collect(Collectors.joining(", ")));
            throw e;
        }
    }

    private void ensureModelsAvailable(BuildConfiguration config, KompileModelManager modelManager,
                                        Map<String, Path> resolvedModelPaths) {
        // For LITE preset, ensure embedding model is available
        if (config.getModules().has("app-lite") && config.getModules().has("embedding-anserini")) {
            ModelDescriptor bgeDesc = ModelConstants.getAnseriniEncoderModelDescriptor("bge-base-en-v1.5");
            if (bgeDesc != null) {
                try {
                    Path path = modelManager.ensureModelAvailable(bgeDesc);
                    resolvedModelPaths.put(bgeDesc.getModelId(), path);
                    System.out.println("Embedding model 'bge-base-en-v1.5' available at: " + path);
                } catch (IOException e) {
                    System.err.println("Failed to ensure embedding model: " + e.getMessage());
                }
            }
        }

        // OpenNLP sentence models
        if (config.getModules().has("chunker-sentence")) {
            List<String> languages = config.getSupportedLanguages();
            if (languages == null || languages.isEmpty()) languages = List.of("en");

            for (String lang : languages) {
                String langKey = lang.toLowerCase().trim();
                String remoteFile = ModelConstants.getOpenNLPModelRemoteFilename(langKey);
                String localFile = ModelConstants.getOpenNLPModelLocalFilename(langKey);
                if (remoteFile == null || localFile == null) {
                    System.err.println("OpenNLP model not found for language: " + langKey);
                    continue;
                }
                ModelDescriptor desc = new ModelDescriptor(
                        "opennlp_sent_" + langKey, ModelType.OPENNLP_SENTENCE,
                        ModelConstants.OPENNLP_MODEL_BASE_URL + remoteFile,
                        Paths.get("opennlp", localFile).toString(),
                        "1.2-2.5.0", null, Map.of("language", langKey));
                try {
                    Path modelPath = modelManager.ensureModelAvailable(desc);
                    resolvedModelPaths.put(desc.getModelId(), modelPath);
                    System.out.println("OpenNLP model for " + langKey + " available at: " + modelPath);
                } catch (IOException e) {
                    System.err.println("Failed to ensure OpenNLP model for " + langKey + ": " + e.getMessage());
                }
            }
        }

        // Anserini indexes
        if (config.getModules().has("app-anserini") && config.getAnseriniIndexIds() != null) {
            for (String indexId : config.getAnseriniIndexIds()) {
                String trimmed = indexId.trim();
                if (trimmed.isEmpty()) continue;
                ModelDescriptor desc = ModelConstants.getAnseriniIndexDescriptor(trimmed);
                if (desc == null) {
                    System.err.println("Anserini index not found: " + trimmed);
                    continue;
                }
                try {
                    Path path = modelManager.ensureModelAvailable(desc);
                    resolvedModelPaths.put(desc.getModelId(), path);
                    System.out.println("Anserini index '" + trimmed + "' available at: " + path);
                } catch (IOException e) {
                    System.err.println("Failed to ensure Anserini index '" + trimmed + "': " + e.getMessage());
                }
            }
        }

        // Anserini encoder models
        if (config.getAnseriniEncoderModelIds() != null) {
            for (String encoderId : config.getAnseriniEncoderModelIds()) {
                String trimmed = encoderId.trim();
                if (trimmed.isEmpty()) continue;
                ModelDescriptor desc = ModelConstants.getAnseriniEncoderModelDescriptor(trimmed);
                if (desc == null) {
                    System.err.println("Anserini encoder model not found: " + trimmed);
                    continue;
                }
                try {
                    Path path = modelManager.ensureModelAvailable(desc);
                    resolvedModelPaths.put(desc.getModelId(), path);
                    System.out.println("Anserini encoder '" + trimmed + "' available at: " + path);
                } catch (IOException e) {
                    System.err.println("Failed to ensure encoder '" + trimmed + "': " + e.getMessage());
                }
            }
        }
    }

    private void embedArchive(File projectBuildDir) throws IOException {
        File resourcesDir = new File(projectBuildDir, "src/main/resources/models");
        if (!resourcesDir.exists() && !resourcesDir.mkdirs()) {
            System.err.println("Failed to create resources/models directory");
            return;
        }
        File destArchive = new File(resourcesDir, archivePath.getName());
        System.out.println("Embedding archive: " + archivePath.getName());
        FileUtils.copyFile(archivePath, destArchive);

        File appPropsDir = new File(projectBuildDir, "src/main/resources");
        File archiveProps = new File(appPropsDir, "application-archive.properties");
        StringBuilder sb = new StringBuilder();
        sb.append("# Auto-generated archive configuration\n");
        sb.append("kompile.models.source-type=").append(archiveOnly ? "ARCHIVE" : modelSourceType).append("\n");
        sb.append("kompile.models.embedded-archive=classpath:models/").append(archivePath.getName()).append("\n");
        sb.append("kompile.models.auto-extract-embedded=true\n");
        sb.append("kompile.models.allow-fallback=").append(!archiveOnly).append("\n");
        sb.append("kompile.models.verify-checksums=true\n");
        if (registryUrls != null) {
            for (int i = 0; i < registryUrls.size(); i++) {
                sb.append("kompile.models.registry-urls[").append(i).append("]=").append(registryUrls.get(i)).append("\n");
            }
        }
        FileUtils.writeStringToFile(archiveProps, sb.toString(), "UTF-8");
    }

    private int invokeMaven(File projectBuildDir, File pomFile) throws MavenInvocationException {
        InvocationRequest request = new DefaultInvocationRequest();
        request.setPomFile(pomFile);

        List<String> goals = new ArrayList<>();
        if (javacppPlatform != null && !javacppPlatform.isEmpty()) {
            goals.add("-Djavacpp.platform=" + javacppPlatform);
            if (javacppExtension != null && !javacppExtension.isEmpty()) {
                goals.add("-Djavacpp.platform.extension=" + javacppExtension);
            }
            goals.add("-Dorg.eclipse.python4j.numpyimport=false");
        }
        goals.add("clean");
        goals.add("package");
        request.setGoals(goals);

        Properties sysProps = new Properties();
        if (skipTests) sysProps.setProperty("skipTests", "true");
        request.setProperties(sysProps);

        List<String> profiles = new ArrayList<>();
        if (buildNative) {
            profiles.add("native");
            File effectiveGraalVm = (graalVmHome != null && graalVmHome.exists()) ? graalVmHome : Info.graalvmDirectory();
            if (effectiveGraalVm != null && effectiveGraalVm.exists()) {
                System.out.println("Using GraalVM: " + effectiveGraalVm.getAbsolutePath());
                request.setJavaHome(effectiveGraalVm);
            } else {
                System.err.println("GraalVM not found. Native build requires --graalVmHome or 'kompile install graalvm'.");
                return 1;
            }
        }
        if (buildContainer) {
            profiles.add("container");
        }
        if (!profiles.isEmpty()) {
            request.setProfiles(profiles);
        }

        Invoker invoker = new DefaultInvoker();
        File effectiveMaven = (mavenHome != null && mavenHome.exists()) ? mavenHome : EnvironmentUtils.defaultMavenHome();
        if (effectiveMaven == null || !effectiveMaven.exists()) {
            System.err.println("Maven not found. Set M2_HOME/MAVEN_HOME or use --mavenHome.");
            return 1;
        }

        request.setMavenOpts("-Dfile.encoding=UTF-8");
        invoker.setMavenHome(effectiveMaven);
        invoker.setWorkingDirectory(projectBuildDir);
        invoker.setOutputHandler(System.out::println);
        invoker.setErrorHandler(System.err::println);

        System.out.println("\nStarting Maven build for: " + configName);
        System.out.println("  Directory: " + projectBuildDir.getAbsolutePath());
        System.out.println("  Goals: " + goals);
        if (buildNative) System.out.println("  Native Profile: Activated");
        if (buildContainer) System.out.println("  Container Profile: Activated (Jib)");
        if (skipTests) System.out.println("  Tests: SKIPPED");

        InvocationResult result = invoker.execute(request);

        if (result.getExitCode() != 0) {
            System.err.println("Build failed! Exit code: " + result.getExitCode());
            if (result.getExecutionException() != null) {
                result.getExecutionException().printStackTrace(System.err);
            }
            return 1;
        }

        System.out.println("\nBuild successful!");
        File targetDir = new File(projectBuildDir, "target");
        if (buildNative) {
            String nativeName = configName + "-native";
            File exe = new File(targetDir, nativeName);
            if (exe.exists()) {
                System.out.println("  Native executable: " + exe.getAbsolutePath());
            }
        }
        if (buildContainer) {
            String image = resolveContainerImageName();
            System.out.println("  Container image: " + image);
            System.out.println("  Run: docker run --rm -p 8080:8080 " + image);
            System.out.println("  Push to registry: mvn package -Pcontainer-push (in " + projectBuildDir.getAbsolutePath() + ")");
        }
        if (!buildNative && !buildContainer) {
            String jarName = configName + "-" + instanceVersion + ".jar";
            File jar = new File(targetDir, jarName);
            if (jar.exists()) {
                System.out.println("  JAR: " + jar.getAbsolutePath());
                System.out.println("  Run: java -jar " + jar.getAbsolutePath());
            }
        }
        return 0;
    }

    private String resolveContainerImageName() {
        if (containerImageName != null && !containerImageName.isBlank()) {
            return containerImageName;
        }
        if (containerRegistry != null && !containerRegistry.isBlank()) {
            String reg = containerRegistry.endsWith("/")
                    ? containerRegistry.substring(0, containerRegistry.length() - 1) : containerRegistry;
            return reg + "/" + configName;
        }
        return "kompile/" + configName;
    }

    private void printEnabledModules(ModuleSelection modules) {
        System.out.println("\n  Enabled Modules (" + modules.getAll().size() + "):");
        for (ModuleCatalog.Category cat : ModuleCatalog.Category.values()) {
            List<ModuleCatalog.ModuleEntry> catModules = modules.getByCategory(cat);
            if (!catModules.isEmpty()) {
                String names = catModules.stream().map(ModuleCatalog.ModuleEntry::getId).collect(Collectors.joining(", "));
                System.out.println("    " + cat.name() + ": " + names);
            }
        }
        System.out.println();
    }
}
