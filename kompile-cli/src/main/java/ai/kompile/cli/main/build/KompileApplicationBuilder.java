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
import ai.kompile.cli.main.properties.PropertyResolver;
import ai.kompile.cli.main.install.InstallHeaders;
import ai.kompile.cli.main.util.EnvironmentFile;
import ai.kompile.cli.main.util.OSResolver;
// ClassifierHelper is a CLI tool, not used programmatically here for classifier detection in this class.

// Kompile Pipelines Framework imports
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.core.config.GenericStepConfig; // For constructing StepConfig
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.core.data.serde.ObjectMappers;
import ai.kompile.pipelines.framework.runtime.pipeline.SequencePipeline;

// Parameter keys will be string literals matching schema definitions.
// The Picocli command will need to know these keys.

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import org.apache.commons.io.FileUtils;
import org.apache.maven.model.Model; // For storing the generated POM model
import org.apache.maven.shared.invoker.*;
import picocli.CommandLine;
import oshi.SystemInfo;
import oshi.hardware.GlobalMemory;
import oshi.hardware.CentralProcessor;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Orchestrates the build of a Kompile application (pipeline executor, webapp, etc.)
 * by dynamically generating a Maven project and invoking Maven to build it.
 */

@Getter
public class KompileApplicationBuilder {

    // Runner FQCN Property Keys
    private static final String KEY_PYTHON_RUNNER_FQCN = "kompile.runner.python.fqcn";
    private static final String KEY_DL4J_RUNNER_FQCN = "kompile.runner.dl4j.fqcn";
    private static final String KEY_ONNX_RUNNER_FQCN = "kompile.runner.onnx.fqcn";
    private static final String KEY_SAMEDIFF_RUNNER_FQCN = "kompile.runner.samediff.fqcn";
    private static final String KEY_DOCPARSER_RUNNER_FQCN = "kompile.runner.docparser.fqcn";
    private static final String KEY_TVM_RUNNER_FQCN = "kompile.runner.tvm.fqcn";
    private static final String KEY_TENSORFLOW_RUNNER_FQCN = "kompile.runner.tensorflow.fqcn";
    private static final String KEY_IMAGE_RUNNER_FQCN = "kompile.runner.image.fqcn";

    // --- Configuration fields ---
    private String pipelineJsonFilePath;
    private boolean autoGeneratePipelineFromCliFlags;
    private List<String> autoGenCliComponentFlags;
    private Map<String, Map<String, Object>> autoGenStepCliParams;


    private String imageNormalization;
    public String outputImageName = "kompile-app";
    public String outputDirectory = ".";
    private String finalArtifactNameOverride;
    private String ragMcpAssistantParentVersion;

    private String libOutputPath = "./lib";
    private String includePath = "./include"; // For C/C++ headers if needed by a direct build step

    private String appType = "pipeline-executor";
    private String appEntryPointClass;
    private String webappStaticResourcesDir;
    private List<String> appExtraConfigFiles;
    private boolean enableFrontendBuild = false;

    private boolean assemblyBuild = false;
    private String pomFileNameInBuildDir = "pom.xml";
    private String mavenHome = Info.mavenDirectory().getAbsolutePath();
    private String buildPlatform;
    private String binaryExtension;
    private long buildThreads = -1;
    private boolean overridePaths = true;
    private String kompilePrefix = null;
    private String pipelineResourcePath;

    // Versions (sourced from Info.java)
    private String kompileVersion = Info.getVersion();
    private String kompilePipelinesVersion = Info.getKompilePipelinesVersion();
    private String kompileAppVersion = Info.getKompileAppVersion();
    private String springBootVersion = Info.getSpringBootVersion();
    private String springAiVersion = Info.getSpringAiVersion();
    private String nativeImagePluginVersion = Info.getNativeImagePluginVersion();

    // Native Image Specific
    private String[] nativeImageJvmArgs;
    private String nativeImageHeapSpace;
    private boolean noGcForNativeImage = false;
    private boolean numpySharedLibrary = false;
    private boolean debugNativeBuild = false;
    private int debugNativePort = 8000;
    private String extraGraalvmBuildArgsString; // For raw GraalVM args
    private String extraDependencies; // Comma-separated G:A:V for PomGenerator
    private String includeResources; // Comma-separated regex for GraalVM resources for PomGenerator


    // ND4J/DL4J Specific
    private String nd4jBackend = "nd4j-native";
    private String nd4jClassifier;
    private String nd4jExtension;
    private String nd4jHelper;
    private String nd4jOperations = "";
    private String nd4jDataTypes = "";
    private boolean nd4jUseLto = false;

    private String dl4jSourceDir;
    private String dl4jBuildCommand = "clean install -DskipTests=true -Dmaven.javadoc.skip=true";
    private List<String> dl4jNativeBuildProps;
    private List<String> dl4jProfiles;

    // C/C++ build related
    private String gccPath;
    private String glibcPath;
    private boolean allowExternalCompilers = false;

    // Provider Selection for Kompile App
    private String llmProvider = "noop";
    private String vectorStoreProvider = "noop";
    private String embeddingProvider = "noop";
    private String documentLoaderProvider = "none";
    private boolean enableRagService = false;
    private boolean enableFilesystemTool = false;

    // Internal state
    private PropertyResolver propertyResolver;
    private File temporaryBuildDir;
    private final List<File> tempFilesAndDirsToClean = new ArrayList<>();
    private ScheduledExecutorService systemMonitorService;
    private Model generatedMavenModel; // To store the model generated by PomGenerator

    public KompileApplicationBuilder() {
        this.propertyResolver = new PropertyResolver();
    }

    // --- Fluent Setters (ensure all fields have setters) ---
    public KompileApplicationBuilder pipelineJsonFilePath(String path) { this.pipelineJsonFilePath = path; return this; }
    public KompileApplicationBuilder autoGeneratePipelineFromCliFlags(boolean autoGen) { this.autoGeneratePipelineFromCliFlags = autoGen; return this; }
    public KompileApplicationBuilder autoGenCliComponentFlags(List<String> components) { this.autoGenCliComponentFlags = components; return this; }
    public KompileApplicationBuilder autoGenStepCliParams(Map<String, Map<String, Object>> params) { this.autoGenStepCliParams = params; return this; }
    public KompileApplicationBuilder outputImageName(String name) { this.outputImageName = name != null ? name : "kompile-app"; return this; }
    public KompileApplicationBuilder outputDirectory(String dir) { this.outputDirectory = dir != null ? dir : "."; return this; }
    public KompileApplicationBuilder finalArtifactNameOverride(String name) { this.finalArtifactNameOverride = name; return this; }
    public KompileApplicationBuilder libOutputPath(String path) { this.libOutputPath = path; return this; }
    public KompileApplicationBuilder includePath(String path) { this.includePath = path; return this; }
    public KompileApplicationBuilder appType(String type) { this.appType = type != null ? type : "pipeline-executor"; return this; }
    public KompileApplicationBuilder appEntryPointClass(String fqcn) { this.appEntryPointClass = fqcn; return this; }
    public KompileApplicationBuilder webappStaticResourcesDir(String path) { this.webappStaticResourcesDir = path; return this; }
    public KompileApplicationBuilder appExtraConfigFiles(List<String> files) { this.appExtraConfigFiles = files; return this; }
    public KompileApplicationBuilder enableFrontendBuild(boolean enable) { this.enableFrontendBuild = enable; return this; }
    public KompileApplicationBuilder assemblyBuild(boolean assembly) { this.assemblyBuild = assembly; return this; }
    public KompileApplicationBuilder mavenHome(String path) { this.mavenHome = path != null ? path : Info.mavenDirectory().getAbsolutePath(); return this; }
    public KompileApplicationBuilder buildPlatform(String platform) { this.buildPlatform = platform; return this; }
    public KompileApplicationBuilder binaryExtension(String ext) { this.binaryExtension = ext; return this; }
    public KompileApplicationBuilder buildThreads(long threads) { this.buildThreads = threads > 0 ? threads : Runtime.getRuntime().availableProcessors(); return this; }
    public KompileApplicationBuilder overridePaths(boolean override) { this.overridePaths = override; return this; }
    public KompileApplicationBuilder kompilePrefix(String prefix) { this.kompilePrefix = prefix; return this; }
    public KompileApplicationBuilder nativeImageJvmArgs(String[] args) { this.nativeImageJvmArgs = args; return this; }
    public KompileApplicationBuilder nativeImageHeapSpace(String heap) { this.nativeImageHeapSpace = heap; return this; }
    public KompileApplicationBuilder noGcForNativeImage(boolean noGc) { this.noGcForNativeImage = noGc; return this; }
    public KompileApplicationBuilder numpySharedLibrary(boolean buildShared) { this.numpySharedLibrary = buildShared; return this; }
    public KompileApplicationBuilder debugNativeBuild(boolean debug) { this.debugNativeBuild = debug; return this; }
    public KompileApplicationBuilder debugNativePort(int port) { this.debugNativePort = port; return this; }
    public KompileApplicationBuilder extraGraalvmBuildArgsString(String args) { this.extraGraalvmBuildArgsString = args; return this;}
    public KompileApplicationBuilder extraDependencies(String deps) { this.extraDependencies = deps; return this; } // Added
    public KompileApplicationBuilder includeResources(String resources) { this.includeResources = resources; return this; } // Added
    public KompileApplicationBuilder nd4jBackend(String backend) { this.nd4jBackend = backend != null ? backend : "nd4j-native"; return this; }
    public KompileApplicationBuilder nd4jClassifier(String classifier) { this.nd4jClassifier = classifier; return this; }
    public KompileApplicationBuilder nd4jExtension(String ext) { this.nd4jExtension = ext; return this; }
    public KompileApplicationBuilder nd4jHelper(String helper) { this.nd4jHelper = helper; return this; }
    public KompileApplicationBuilder nd4jOperations(String ops) { this.nd4jOperations = ops; return this; }
    public KompileApplicationBuilder nd4jDataTypes(String types) { this.nd4jDataTypes = types; return this; }
    public KompileApplicationBuilder nd4jUseLto(boolean useLto) { this.nd4jUseLto = useLto; return this; }
    public KompileApplicationBuilder dl4jSourceDir(String path) { this.dl4jSourceDir = path; return this; }
    public KompileApplicationBuilder dl4jBuildCommand(String command) { this.dl4jBuildCommand = command; return this; }
    public KompileApplicationBuilder dl4jNativeBuildProps(List<String> props) { this.dl4jNativeBuildProps = props; return this; }
    public KompileApplicationBuilder dl4jProfiles(List<String> profiles) { this.dl4jProfiles = profiles; return this; }
    public KompileApplicationBuilder gccPath(String path) { this.gccPath = path; return this; }
    public KompileApplicationBuilder glibcPath(String path) { this.glibcPath = path; return this; }
    public KompileApplicationBuilder allowExternalCompilers(boolean allow) { this.allowExternalCompilers = allow; return this; }
    public KompileApplicationBuilder llmProvider(String provider) { this.llmProvider = provider; return this; }
    public KompileApplicationBuilder vectorStoreProvider(String provider) { this.vectorStoreProvider = provider; return this; }
    public KompileApplicationBuilder embeddingProvider(String provider) { this.embeddingProvider = provider; return this; }
    public KompileApplicationBuilder documentLoaderProvider(String provider) { this.documentLoaderProvider = provider; return this; }
    public KompileApplicationBuilder enableRagService(boolean enable) { this.enableRagService = enable; return this; }
    public KompileApplicationBuilder enableFilesystemTool(boolean enable) { this.enableFilesystemTool = enable; return this; }
    public KompileApplicationBuilder kompileVersion(String version) { this.kompileVersion = version; return this; }
    public KompileApplicationBuilder kompilePipelinesVersion(String version) { this.kompilePipelinesVersion = version; return this; }
    public KompileApplicationBuilder kompileAppVersion(String version) { this.kompileAppVersion = version; return this; }
    public KompileApplicationBuilder springBootVersion(String version) { this.springBootVersion = version; return this; }
    public KompileApplicationBuilder springAiVersion(String version) { this.springAiVersion = version; return this; }
    public KompileApplicationBuilder nativeImagePluginVersion(String version) { this.nativeImagePluginVersion = version; return this; }


    private String getRequiredRunnerFqcnFromProperties(String propertyKey, String componentName) {
        if (this.propertyResolver == null) this.propertyResolver = new PropertyResolver();
        String fqcn = this.propertyResolver.getValue(propertyKey);
        if (fqcn == null || fqcn.trim().isEmpty()) {
            System.err.println("ERROR: FQCN for " + componentName + " runner (property key: '" + propertyKey + "') is not configured. " +
                    "Please set the corresponding system property (e.g., -D" + propertyKey + "=some.fq.ClassName). Skipping " + componentName + " step.");
            return null;
        }
        System.out.println("Resolved " + componentName + " Runner FQCN from property '" + propertyKey + "': " + fqcn);
        return fqcn;
    }

    public int build() throws Exception {
        startSystemMonitor();
        try {
            checkRequiredStaticPaths();
            Map<String, String> sharedEnv = createSharedEnvironment();

            if (this.dl4jSourceDir != null && !this.dl4jSourceDir.isEmpty()) {
                preBuildDl4jFromSource(sharedEnv);
            }

            this.temporaryBuildDir = (this.kompilePrefix != null && !this.kompilePrefix.equals("./") && !this.kompilePrefix.trim().isEmpty()) ?
                    new File(this.kompilePrefix, "kompile-build-" + UUID.randomUUID().toString()) :
                    Files.createTempDirectory("kompile-app-build-" + UUID.randomUUID().toString()).toFile();
            if(!this.temporaryBuildDir.mkdirs() && !this.temporaryBuildDir.exists()) {
                if(!this.temporaryBuildDir.exists())
                    throw new IOException("Failed to create temporary build directory: " + this.temporaryBuildDir.getAbsolutePath());
            }
            this.tempFilesAndDirsToClean.add(this.temporaryBuildDir);

            File srcMainJava = new File(this.temporaryBuildDir, "src/main/java");
            srcMainJava.mkdirs();
            File srcMainResources = new File(this.temporaryBuildDir, "src/main/resources");
            srcMainResources.mkdirs();
            if(this.enableFrontendBuild && "kompile-spring-boot-webapp".equalsIgnoreCase(this.appType)){
                File srcMainFrontend = new File(this.temporaryBuildDir, "src/main/frontend");
                srcMainFrontend.mkdirs();
                if (this.webappStaticResourcesDir != null && new File(this.webappStaticResourcesDir).isDirectory()) {
                    System.out.println("Copying Angular frontend source from " + this.webappStaticResourcesDir + " to " + srcMainFrontend.getAbsolutePath() + " for frontend-maven-plugin build.");
                    FileUtils.copyDirectory(new File(this.webappStaticResourcesDir), srcMainFrontend);
                } else if (this.enableFrontendBuild) {
                    System.err.println("Warning: Frontend build enabled (--enable-frontend-build) but --webapp-static-dir is not a valid source directory for Angular project. Frontend may not build correctly.");
                }
            }

            File targetPipelineJsonInResources = new File(srcMainResources, "kompile_pipeline.json");
            preparePipelineJson(targetPipelineJsonInResources);

            copyApplicationResources(srcMainResources);
            extractHeadersIfNecessary(srcMainResources);

            File generatedPomFile = new File(this.temporaryBuildDir, this.pomFileNameInBuildDir);
            // Corrected: Call the renamed method that returns the Model
            this.generatedMavenModel = generateProjectPomAndGetModel(generatedPomFile, targetPipelineJsonInResources.getAbsolutePath());

            int buildExitCode = invokeMavenBuild(this.temporaryBuildDir, generatedPomFile, sharedEnv);

            if (buildExitCode == 0) {
                copyFinalArtifact(new File(this.temporaryBuildDir, "target"));
            }
            return buildExitCode;
        } finally {
            stopSystemMonitor();
        }
    }

    private void checkRequiredStaticPaths() {
        if (!Info.graalvmDirectory().exists()) throw new IllegalStateException("GraalVM directory not found: " + Info.graalvmDirectory().getAbsolutePath() + ". Please run 'kompile install graalvm'.");
        if (!new File(this.mavenHome).exists()) throw new IllegalStateException("Maven directory not found: " + this.mavenHome + ". Check --maven-home or run 'kompile install maven'.");
        if (!Info.pythonDirectory().exists()) throw new IllegalStateException("Python directory not found: " + Info.pythonDirectory().getAbsolutePath() + ". Please run 'kompile install python'.");
        File cmakeDir = Info.cmakeDirectory();
        if (!cmakeDir.exists() && (this.gccPath != null || this.glibcPath != null || this.dl4jSourceDir != null || this.enableFrontendBuild)) {
            System.err.println("Warning: Kompile CMake directory not found at " + cmakeDir.getAbsolutePath() + ". Some builds might require CMake. Run 'kompile install cmake'.");
        }
    }

    private Map<String, String> createSharedEnvironment() throws IOException {
        Map<String, String> env = new HashMap<>(System.getenv());
        File graalvmDir = Info.graalvmDirectory();
        env.put("GRAALVM_HOME", graalvmDir.getAbsolutePath());
        env.put("JAVA_HOME", graalvmDir.getAbsolutePath());

        env.putIfAbsent("USER", System.getProperty("user.name", "unknown_user"));
        env.putIfAbsent("PLATFORM", OSResolver.os());

        if (this.nd4jClassifier != null && this.nd4jBackend != null) {
            File backendEnvFile = EnvironmentFile.envFileForBackendAndPlatform(this.nd4jBackend, this.nd4jClassifier);
            if (backendEnvFile.exists()) {
                Map<String, String> backendEnvMap = EnvironmentFile.loadFromEnvFile(backendEnvFile);
                env.putAll(backendEnvMap);
                System.out.println("Loaded ND4J backend environment variables from: " + backendEnvFile.getAbsolutePath());
            } else {
                System.err.println("Warning: ND4J backend environment file not found for " + this.nd4jBackend + "/" + this.nd4jClassifier + " at: " + backendEnvFile.getAbsolutePath());
            }
        }

        if (this.overridePaths) {
            System.out.println("Overriding PATH: Adding Kompile-managed tools to PATH.");
            List<String> pathsToPrepend = new ArrayList<>();

            File graalvmJavaBin = new File(graalvmDir, "bin");
            if (graalvmJavaBin.isDirectory()) pathsToPrepend.add(graalvmJavaBin.getAbsolutePath());

            File pythonDir = Info.pythonDirectory();
            File pythonBinDir = OSResolver.isWindows() ? new File(pythonDir, "Scripts") : new File(pythonDir, "bin");
            if (pythonBinDir.isDirectory()) pathsToPrepend.add(pythonBinDir.getAbsolutePath());
            else System.err.println("Warning: Kompile Python bin/Scripts directory not found at " + pythonDir.getAbsolutePath());

            File configuredMavenHome = new File(this.mavenHome);
            File mavenBinDir = new File(configuredMavenHome, "bin");
            if (mavenBinDir.isDirectory()) pathsToPrepend.add(mavenBinDir.getAbsolutePath());

            File cmakeBinDir = new File(Info.cmakeDirectory(), "bin");
            if(cmakeBinDir.isDirectory()) pathsToPrepend.add(cmakeBinDir.getAbsolutePath());

            String currentLdPath = env.getOrDefault("LD_LIBRARY_PATH", "");
            if (this.glibcPath != null && !this.glibcPath.isEmpty()) {
                File glibcDir = new File(this.glibcPath);
                File glibcBin = new File(glibcDir, "bin");
                File glibcLib = new File(glibcDir, "lib");
                if (!glibcLib.exists()) glibcLib = new File(glibcDir, "lib64");
                if (glibcBin.exists() && glibcBin.isDirectory()) pathsToPrepend.add(glibcBin.getAbsolutePath());
                if (glibcLib.exists() && glibcLib.isDirectory()) {
                    currentLdPath = glibcLib.getAbsolutePath() + (currentLdPath.isEmpty() ? "" : File.pathSeparator + currentLdPath);
                }
            }
            if (this.gccPath != null && !this.gccPath.isEmpty()) {
                File gccDir = new File(this.gccPath);
                File gccBin = new File(gccDir, "bin");
                File gccLib = new File(gccDir, "lib64");
                if (!gccLib.exists()) gccLib = new File(gccDir, "lib");

                if (gccBin.exists() && gccBin.isDirectory()) {
                    pathsToPrepend.add(gccBin.getAbsolutePath());
                    File ccTool = new File(gccBin, "gcc");
                    File cxxTool = new File(gccBin, "g++");
                    if (ccTool.exists()) env.put("CC", ccTool.getAbsolutePath());
                    if (cxxTool.exists()) env.put("CXX", cxxTool.getAbsolutePath());
                }
                if (gccLib.exists() && gccLib.isDirectory()) {
                    currentLdPath = gccLib.getAbsolutePath() + (currentLdPath.isEmpty() ? "" : File.pathSeparator + currentLdPath);
                }
            }
            if (!currentLdPath.isEmpty()) env.put("LD_LIBRARY_PATH", currentLdPath);

            String currentPath = env.getOrDefault("PATH", "");
            String newPath = String.join(File.pathSeparator, pathsToPrepend) +
                    (pathsToPrepend.isEmpty() || currentPath.isEmpty() ? "" : File.pathSeparator) +
                    currentPath;
            newPath = newPath.replaceAll(File.pathSeparator + File.pathSeparator + "+", File.pathSeparator);
            if (newPath.startsWith(File.pathSeparator) && newPath.length() > 1 && !OSResolver.isWindows()) newPath = newPath.substring(1);

            env.put("PATH", newPath);
            System.out.println("Effective PATH for build: " + newPath);
            if (env.containsKey("LD_LIBRARY_PATH") && !env.get("LD_LIBRARY_PATH").isEmpty()) {
                System.out.println("Effective LD_LIBRARY_PATH for build: " + env.get("LD_LIBRARY_PATH"));
            }
        }
        return env;
    }

    private void preBuildDl4jFromSource(Map<String, String> executionEnv) throws Exception {
        System.out.println("Starting pre-build of DL4J from source directory: " + this.dl4jSourceDir);
        File dl4jSourcePath = new File(this.dl4jSourceDir);
        if (!dl4jSourcePath.exists() || !dl4jSourcePath.isDirectory()) {
            throw new IOException("DL4J source directory not found or not a directory: " + this.dl4jSourceDir);
        }

        InvocationRequest request = new DefaultInvocationRequest();
        request.setBaseDirectory(dl4jSourcePath);
        request.setPomFile(new File(dl4jSourcePath, "pom.xml"));
        request.setGoals(Arrays.asList(this.dl4jBuildCommand.split("\\s+")));

        Properties mavenProps = new Properties();
        if (this.dl4jNativeBuildProps != null) {
            for (String propPair : this.dl4jNativeBuildProps) {
                String[] parts = propPair.split("=", 2);
                if (parts.length == 2) {
                    mavenProps.setProperty(parts[0].trim(), parts[1].trim());
                } else {
                    System.err.println("Warning: Malformed DL4J native property, skipping: " + propPair);
                }
            }
        }
        if (this.nd4jBackend != null) mavenProps.setProperty("libnd4j.chip", this.nd4jBackend.contains("cuda") ? "cuda" : "cpu");
        if (this.buildPlatform != null) mavenProps.setProperty("libnd4j.platform", this.buildPlatform);
        if (this.nd4jClassifier != null) mavenProps.setProperty("javacpp.platform", this.nd4jClassifier);
        if (this.nd4jExtension != null) mavenProps.setProperty("libnd4j.extension", this.nd4jExtension.startsWith("-") ? this.nd4jExtension.substring(1) : this.nd4jExtension);
        if (this.nd4jHelper != null) mavenProps.setProperty("libnd4j.helper", this.nd4jHelper.startsWith("-") ? this.nd4jHelper.substring(1) : this.nd4jHelper);
        mavenProps.setProperty("libnd4j.buildthreads", String.valueOf(this.buildThreads));
        if (this.nd4jOperations != null) mavenProps.setProperty("libnd4j.operations", this.nd4jOperations);
        if (this.nd4jDataTypes != null) mavenProps.setProperty("libnd4j.datatypes", this.nd4jDataTypes);
        mavenProps.setProperty("libnd4j.lto", this.nd4jUseLto ? "ON" : "OFF");

        if (this.gccPath != null && !this.gccPath.isEmpty() && this.allowExternalCompilers) {
            File gccBin = new File(new File(this.gccPath), "bin");
            File gxxCompiler = new File(gccBin, "g++");
            File clangxxCompiler = new File(gccBin, "clang++");
            if (gxxCompiler.exists()) {
                mavenProps.setProperty("platform.compiler", gxxCompiler.getAbsolutePath());
            } else if (clangxxCompiler.exists()) {
                mavenProps.setProperty("platform.compiler", clangxxCompiler.getAbsolutePath());
            }
        }
        request.setProperties(mavenProps);

        if (this.dl4jProfiles != null && !this.dl4jProfiles.isEmpty()) {
            request.setProfiles(this.dl4jProfiles);
        }

        for(Map.Entry<String, String> envEntry : executionEnv.entrySet()) {
            request.addShellEnvironment(envEntry.getKey(), envEntry.getValue());
        }

        Invoker invoker = new DefaultInvoker();
        invoker.setMavenHome(new File(this.mavenHome));
        invoker.setLogger(new SystemOutLogger());
        invoker.setOutputHandler(line -> System.out.println("[DL4J Source Build] " + line));
        invoker.setErrorHandler(line -> System.err.println("[DL4J Source Build ERROR] " + line));

        System.out.println("Executing DL4J source build: " + this.dl4jBuildCommand +
                " with properties: " + mavenProps +
                " and profiles: " + this.dl4jProfiles);
        InvocationResult result = invoker.execute(request);
        if (result.getExitCode() != 0) {
            String errorMsg = "Failed to pre-build DL4J from source. Exit code: " + result.getExitCode();
            if (result.getExecutionException() != null) {
                throw new MavenInvocationException(errorMsg, result.getExecutionException());
            }
            throw new MavenInvocationException(errorMsg);
        }
        System.out.println("Successfully pre-built and installed DL4J from source.");
    }

    private void preparePipelineJson(File targetPipelineJsonInResources) throws IOException {
        if (this.autoGeneratePipelineFromCliFlags) {
            if (this.autoGenCliComponentFlags == null || this.autoGenCliComponentFlags.isEmpty()) {
                this.autoGenCliComponentFlags = Arrays.asList("python", "onnx", "dl4j", "samediff", "tensorflow", "tvm", "image", "doc");
                System.out.println("No specific components for auto-generated pipeline, defaulting to include all standard components.");
            }
            File generatedPipelineFile = generateKpfPipelineFromCliFlags(this.temporaryBuildDir);
            FileUtils.copyFile(generatedPipelineFile, targetPipelineJsonInResources);
            if (generatedPipelineFile.getName().startsWith("kompile-temp-pipeline-")) {
                this.tempFilesAndDirsToClean.add(generatedPipelineFile);
            }
            this.pipelineJsonFilePath = targetPipelineJsonInResources.getAbsolutePath();
        } else if (this.pipelineJsonFilePath != null) {
            File sourcePipelineFile = new File(this.pipelineJsonFilePath);
            if (!sourcePipelineFile.exists()) {
                throw new IOException("Specified pipeline JSON file not found: " + this.pipelineJsonFilePath);
            }
            FileUtils.copyFile(sourcePipelineFile, targetPipelineJsonInResources);
            this.pipelineJsonFilePath = targetPipelineJsonInResources.getAbsolutePath();
        } else {
            System.out.println("No pipeline file provided and auto-generation not triggered. Creating an empty pipeline for the application shell.");
            SequencePipeline emptyPipeline = SequencePipeline.builder().steps(Collections.emptyList()).build();
            String emptyPipelineJson = ObjectMappers.getJsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(emptyPipeline);
            FileUtils.writeStringToFile(targetPipelineJsonInResources, emptyPipelineJson, StandardCharsets.UTF_8);
            this.pipelineJsonFilePath = targetPipelineJsonInResources.getAbsolutePath();
        }
    }

    private File generateKpfPipelineFromCliFlags(File outputDirForTempFile) throws IOException {
        File tempPipelineFile = new File(outputDirForTempFile,"kompile-temp-pipeline-" + UUID.randomUUID() + ".json");
        List<StepConfig> steps = new ArrayList<>();
        Map<String, Object> defaultEmptyStepParams = Collections.emptyMap();

        if (this.autoGenCliComponentFlags == null) this.autoGenCliComponentFlags = Collections.emptyList();

        for (String componentFlagName : this.autoGenCliComponentFlags) {
            String component = componentFlagName.toLowerCase();
            String fqcnKey = null;
            String runnerName = component;
            String stepTypeSymbol = component.toUpperCase();

            Map<String, Object> stepSpecificParams = (this.autoGenStepCliParams != null) ?
                    this.autoGenStepCliParams.getOrDefault(component, defaultEmptyStepParams) :
                    defaultEmptyStepParams;

            if ("python".equals(component)) { fqcnKey = KEY_PYTHON_RUNNER_FQCN; runnerName = "Python"; stepTypeSymbol = "PYTHON"; }
            else if ("onnx".equals(component)) { fqcnKey = KEY_ONNX_RUNNER_FQCN; runnerName = "ONNX"; stepTypeSymbol = "ONNX_RUNTIME"; }
            else if ("dl4j".equals(component)) { fqcnKey = KEY_DL4J_RUNNER_FQCN; runnerName = "Deeplearning4J"; stepTypeSymbol = "DL4J_INFERENCE"; }
            else if ("samediff".equals(component)) { fqcnKey = KEY_SAMEDIFF_RUNNER_FQCN; runnerName = "SameDiff"; stepTypeSymbol = "SAMEDIFF_INFERENCE"; }
            else if ("tensorflow".equals(component)) { fqcnKey = KEY_TENSORFLOW_RUNNER_FQCN; runnerName = "TensorFlow"; stepTypeSymbol = "TENSORFLOW_INFERENCE"; }
            else if ("tvm".equals(component)) { fqcnKey = KEY_TVM_RUNNER_FQCN; runnerName = "TVM"; stepTypeSymbol = "TVM_INFERENCE"; }
            else if ("image".equals(component)) { fqcnKey = KEY_IMAGE_RUNNER_FQCN; runnerName = "ImageToNDArray"; stepTypeSymbol = "IMAGE_TO_NDARRAY"; }
            else if ("doc".equals(component)) { fqcnKey = KEY_DOCPARSER_RUNNER_FQCN; runnerName = "DocumentParser"; stepTypeSymbol = "DOCUMENT_PARSER"; }
            else {
                System.err.println("Warning: Unknown component '" + component + "' specified for auto-generation. Skipping.");
                continue;
            }

            String fqcn = getRequiredRunnerFqcnFromProperties(fqcnKey, runnerName);
            if (fqcn != null) {
                if (stepSpecificParams.isEmpty() && !defaultEmptyStepParams.equals(stepSpecificParams) ) {
                    System.out.println("No specific parameters found for auto-generated '" + runnerName + "' step in autoGenStepCliParams. Using empty parameters. Ensure CLI options populate autoGenStepCliParams correctly.");
                }
                // Using the GenericStepConfig constructor that includes 'type'
                steps.add(new GenericStepConfig(stepTypeSymbol));
            }
        }
        if (steps.isEmpty() && !this.autoGenCliComponentFlags.isEmpty()) {
            System.err.println("Warning: No steps were added to the auto-generated pipeline, though components were specified. " +
                    "This might be due to missing FQCN configurations or parameters for all selected components.");
        }
        SequencePipeline kpfPipeline = SequencePipeline.builder().steps(steps).build();
        String pipelineJson = ObjectMappers.getJsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(kpfPipeline);
        FileUtils.writeStringToFile(tempPipelineFile, pipelineJson, StandardCharsets.UTF_8);
        System.out.println("Auto-generated Kompile pipeline JSON at: " + tempPipelineFile.getAbsolutePath());
        return tempPipelineFile;
    }

    private void copyApplicationResources(File srcMainResourcesDir) throws IOException {
        if (this.appExtraConfigFiles != null && !this.appExtraConfigFiles.isEmpty()) {
            for (String configFilePath : this.appExtraConfigFiles) {
                File configFile = new File(configFilePath.trim());
                if (configFile.exists() && configFile.isFile()) {
                    FileUtils.copyFileToDirectory(configFile, srcMainResourcesDir);
                    System.out.println("Copied app config file: " + configFile.getName() + " to " + srcMainResourcesDir);
                } else {
                    System.err.println("Warning: Application config file not found or not a file: " + configFilePath);
                }
            }
        }

        if (this.webappStaticResourcesDir != null && !this.webappStaticResourcesDir.isEmpty() &&
                (this.appType.toLowerCase().contains("webapp") || this.appType.toLowerCase().contains("server")) ) {
            File staticSourceDir = new File(this.webappStaticResourcesDir);
            if (staticSourceDir.exists() && staticSourceDir.isDirectory()) {
                File targetStaticDir;
                if ("vertx-webapp".equalsIgnoreCase(this.appType)) {
                    targetStaticDir = new File(srcMainResourcesDir, "webroot");
                } else {
                    targetStaticDir = new File(srcMainResourcesDir, "static");
                }
                targetStaticDir.mkdirs();
                if (!this.enableFrontendBuild) {
                    FileUtils.copyDirectory(staticSourceDir, targetStaticDir);
                    System.out.println("Copied webapp static resources from " + staticSourceDir + " to " + targetStaticDir);
                } else {
                    System.out.println("Frontend build enabled, skipping direct copy of webappStaticResourcesDir to resources. Frontend plugin will handle output.");
                }
            } else if (!this.enableFrontendBuild) {
                System.err.println("Warning: Webapp static resources directory not found or not a directory: " + this.webappStaticResourcesDir);
            }
        }
    }

    private void extractHeadersIfNecessary(File resourcesDir) throws IOException {
        boolean shouldExtract = !this.assemblyBuild || this.numpySharedLibrary;

        if (shouldExtract) {
            File headersBaseDir = InstallHeaders.headersDir();
            if (!headersBaseDir.exists() || !headersBaseDir.isDirectory()) {
                System.err.println("Kompile Headers directory not found: " + headersBaseDir.getAbsolutePath() + ". Skipping header extraction.");
                return;
            }
            File includeDirInResources = new File(resourcesDir, "include");
            includeDirInResources.mkdirs();

            for (String headerName : new String[]{"numpy_struct.h", "kompile.h"}) {
                File sourceHeaderFile = new File(headersBaseDir, headerName);
                if (headerName.equals("kompile.h") && !sourceHeaderFile.exists()) {
                    sourceHeaderFile = new File(headersBaseDir, "konduit-serving.h");
                    if(sourceHeaderFile.exists()) System.out.println("Using legacy 'konduit-serving.h' as 'kompile.h' was not found.");
                }

                if (!sourceHeaderFile.exists()) {
                    System.err.println("Header file not found: " + sourceHeaderFile.getAbsolutePath() + " (tried for " + headerName + "). Skipping this resource.");
                    continue;
                }
                try (InputStream is = new FileInputStream(sourceHeaderFile)) {
                    String headerContent = org.apache.commons.io.IOUtils.toString(is, StandardCharsets.UTF_8);
                    File targetHeaderFile = new File(includeDirInResources, headerName);
                    FileUtils.writeStringToFile(targetHeaderFile, headerContent, StandardCharsets.UTF_8, false);
                    System.out.println("Extracted header " + headerName + " to " + targetHeaderFile.getAbsolutePath());
                } catch (IOException e) {
                    System.err.println("Failed to extract resource " + headerName + " into " + includeDirInResources.getAbsolutePath() + ": " + e.getMessage());
                }
            }
        }
    }

    /**
     * Generates the pom.xml and returns the created Maven Model object.
     */
    private Model generateProjectPomAndGetModel(File pomFile, String absolutePathToPipelineJsonForAnalysis) throws Exception {
        PomGenerator pomGen = new PomGenerator();
        // Pass all relevant configurations from this builder to PomGenerator
        pomGen.setOutputFile(pomFile);
        pomGen.setAssembly(this.assemblyBuild);
        pomGen.setImageName(this.outputImageName);

        String resolvedEntryPointClass = this.appEntryPointClass;
        if (resolvedEntryPointClass == null || resolvedEntryPointClass.trim().isEmpty()) {
            if ("kompile-spring-boot-webapp".equalsIgnoreCase(this.appType) || "spring-boot-webapp".equalsIgnoreCase(this.appType)) {
                resolvedEntryPointClass = "ai.kompile.app.MainApplication";
            } else if ("vertx-webapp".equalsIgnoreCase(this.appType)) {
                resolvedEntryPointClass = "ai.kompile.runtime.vertx.KompileVertxApplication"; // Conceptual
            } else {
                resolvedEntryPointClass = "ai.kompile.runtime.KompilePipelineExecutorApp"; // Conceptual
            }
        }
        pomGen.setMainClass(resolvedEntryPointClass);
        pomGen.setAppType(this.appType);
        pomGen.setPipelineJsonFilePathForAnalysis(absolutePathToPipelineJsonForAnalysis);
        pomGen.setPipelinePath(this.pipelineResourcePath); // Corrected: Use the field name

        pomGen.setNativeImageJvmArgs(this.nativeImageJvmArgs);
        if (this.nativeImageHeapSpace != null && !this.nativeImageHeapSpace.trim().isEmpty()) {
            pomGen.addNativeImageJvmArgIfMissing("-Xmx" + this.nativeImageHeapSpace.trim());
        }
        if (this.noGcForNativeImage) {
            pomGen.addNativeImageJvmArgIfMissing("--gc=epsilon");
        }
        pomGen.setNumpySharedLibrary(this.numpySharedLibrary);
        pomGen.setDebugNative(this.debugNativeBuild);
        pomGen.setDebugNativePort(this.debugNativePort);
        // pomGen.setExtraGraalvmBuildArgsString(this.extraGraalvmBuildArgsString); // PomGenerator needs setter for this field

        pomGen.setNd4jBackend(this.nd4jBackend);
        pomGen.setNd4jBackendClassifier(this.nd4jClassifier);
        pomGen.setNd4jExtension(this.nd4jExtension);
        // pomGen.setNd4jHelper(this.nd4jHelper); // PomGenerator doesn't have this setter directly
        pomGen.setNd4jOperations(this.nd4jOperations);
        pomGen.setNd4jDataTypes(this.nd4jDataTypes);
        pomGen.setNd4jUseLto(this.nd4jUseLto);

        pomGen.setExtraDependencies(this.extraDependencies); // Ensured this is passed
        pomGen.setIncludeResources(this.includeResources);   // Ensured this is passed

        // Pass versions
        pomGen.setKompileVersion(this.kompileVersion); // Ensured this is passed
        pomGen.setRagMcpAssistantParentVersion(this.ragMcpAssistantParentVersion); // Ensured this is passed
        pomGen.setKompilePipelinesVersion(this.kompilePipelinesVersion);
        pomGen.setKompileAppVersion(this.kompileAppVersion);
        pomGen.setSpringBootVersion(this.springBootVersion);
        pomGen.setSpringAiVersion(this.springAiVersion);
        pomGen.setNativeImagePluginVersion(this.nativeImagePluginVersion);

        // Pass provider selections
        pomGen.setLlmProvider(this.llmProvider);
        pomGen.setVectorStoreProvider(this.vectorStoreProvider);
        pomGen.setEmbeddingProvider(this.embeddingProvider);
        pomGen.setDocumentLoaderProvider(this.documentLoaderProvider);
        pomGen.setEnableRagService(this.enableRagService);
        pomGen.setEnableFilesystemTool(this.enableFilesystemTool);
        pomGen.setEnableFrontendBuild(this.enableFrontendBuild);

        if (this.autoGenCliComponentFlags != null) {
            pomGen.setPython(this.autoGenCliComponentFlags.stream().anyMatch("python"::equalsIgnoreCase));
            pomGen.setOnnx(this.autoGenCliComponentFlags.stream().anyMatch("onnx"::equalsIgnoreCase));
            pomGen.setDl4j(this.autoGenCliComponentFlags.stream().anyMatch("dl4j"::equalsIgnoreCase));
            pomGen.setSamediff(this.autoGenCliComponentFlags.stream().anyMatch("samediff"::equalsIgnoreCase));
            pomGen.setTensorflow(this.autoGenCliComponentFlags.stream().anyMatch("tensorflow"::equalsIgnoreCase));
            pomGen.setTvm(this.autoGenCliComponentFlags.stream().anyMatch("tvm"::equalsIgnoreCase));
            pomGen.setImage(this.autoGenCliComponentFlags.stream().anyMatch("image"::equalsIgnoreCase));
            pomGen.setDoc(this.autoGenCliComponentFlags.stream().anyMatch("doc"::equalsIgnoreCase));
        }
        pomGen.setServer(this.appType.toLowerCase().contains("webapp") || this.appType.toLowerCase().contains("server"));

        System.out.println("Generating project POM at: " + pomFile.getAbsolutePath());
        pomGen.create();
        return pomGen.getModel(); // Assuming PomGenerator has a getModel() method to return the created Model
    }

    private int invokeMavenBuild(File projectBuildDir, File pomFile, Map<String, String> executionEnv) throws MavenInvocationException {
        InvocationRequest request = new DefaultInvocationRequest();
        request.setBaseDirectory(projectBuildDir);
        request.setPomFile(pomFile);

        List<String> goals = new ArrayList<>();
        goals.add("clean");
        goals.add("package");

        request.setGoals(goals);
        request.setUpdateSnapshots(true);

        Properties mavenProps = new Properties();
        if (this.buildPlatform != null && !this.buildPlatform.isEmpty()) {
            mavenProps.setProperty("javacpp.platform", this.buildPlatform);
        }
        if (this.nd4jBackend != null) mavenProps.setProperty("nd4j.backend", this.nd4jBackend);
        if (this.nd4jExtension != null) mavenProps.setProperty("nd4j.extension", this.nd4jExtension);
        if (this.nd4jHelper != null) mavenProps.setProperty("nd4j.helper", this.nd4jHelper);
        if (this.nd4jOperations != null && !this.nd4jOperations.isEmpty()) mavenProps.setProperty("nd4j.operations", this.nd4jOperations);
        if (this.nd4jDataTypes != null && !this.nd4jDataTypes.isEmpty()) mavenProps.setProperty("nd4j.datatypes", this.nd4jDataTypes);
        mavenProps.setProperty("nd4j.lto", String.valueOf(this.nd4jUseLto));

        mavenProps.setProperty("maven.threads", String.valueOf(this.buildThreads));
        request.setProperties(mavenProps);

        if(this.buildThreads > 1 && !OSResolver.isWindows()) {
            request.setThreads(String.valueOf(this.buildThreads));
        }

        for(Map.Entry<String, String> envEntry : executionEnv.entrySet()) {
            request.addShellEnvironment(envEntry.getKey(), envEntry.getValue());
        }

        Invoker invoker = new DefaultInvoker();
        invoker.setMavenHome(new File(this.mavenHome));
        invoker.setWorkingDirectory(projectBuildDir);
        invoker.setLogger(new SystemOutLogger());
        invoker.setOutputHandler(line -> System.out.println("[Maven Build] " + line));
        invoker.setErrorHandler(line -> System.err.println("[Maven Build ERROR] " + line));

        System.out.println("Executing Maven build in " + projectBuildDir.getAbsolutePath() + " with goals: " + goals + " and properties: " + mavenProps);
        InvocationResult result = invoker.execute(request);

        if (result.getExitCode() != 0) {
            System.err.println("Maven build failed. Exit code: " + result.getExitCode());
            if (result.getExecutionException() != null) {
                System.err.println("Maven Exception: " + result.getExecutionException().getMessage());
            }
        } else {
            System.out.println("Maven build successful.");
        }
        return result.getExitCode();
    }

    private void copyFinalArtifact(File mavenTargetDir) throws IOException {
        if (!mavenTargetDir.exists() || !mavenTargetDir.isDirectory()) {
            System.err.println("Build target directory not found: " + mavenTargetDir.getAbsolutePath());
            throw new IOException("Build target directory not found: " + mavenTargetDir.getAbsolutePath());
        }

        File sourceArtifact = null;
        String artifactIdToSearch = this.outputImageName;
        String versionToSearch = this.kompileAppVersion;

        // Use the generatedMavenModel if available for more accurate artifact naming
        if (this.generatedMavenModel != null && this.generatedMavenModel.getArtifactId() != null) {
            artifactIdToSearch = this.generatedMavenModel.getArtifactId();
            versionToSearch = this.generatedMavenModel.getVersion();
        } else {
            System.err.println("Warning: Generated Maven model was not available for precise artifact name resolution. Using outputImageName as base.");
            if (!"kompile-spring-boot-webapp".equalsIgnoreCase(this.appType) && !"spring-boot-webapp".equalsIgnoreCase(this.appType)) {
                versionToSearch = this.kompilePipelinesVersion; // Executors might align with pipeline version
            }
        }

        if (this.assemblyBuild) {
            final String assemblyFinalNameBase = (this.generatedMavenModel != null && this.generatedMavenModel.getBuild() != null && this.generatedMavenModel.getBuild().getFinalName() != null) ?
                    this.generatedMavenModel.getBuild().getFinalName() : artifactIdToSearch;
            Optional<File> assemblyFile = Arrays.stream(Optional.ofNullable(mavenTargetDir.listFiles()).orElse(new File[0]))
                    .filter(f -> (f.getName().startsWith(assemblyFinalNameBase) || f.getName().startsWith(this.outputImageName)) &&
                            (f.getName().endsWith(".tar.gz") || f.getName().endsWith(".zip") || f.getName().contains("-dist.")))
                    .sorted(Comparator.comparingLong(File::lastModified).reversed())
                    .findFirst();
            if (assemblyFile.isPresent()) {
                sourceArtifact = assemblyFile.get();
            } else {
                System.err.println("Assembly artifact not found in " + mavenTargetDir.getAbsolutePath() + " matching base '" + assemblyFinalNameBase + "' or '" + this.outputImageName + "'. Listing target dir:");
                try (java.util.stream.Stream<java.nio.file.Path> listing = Files.list(mavenTargetDir.toPath())) {
                    listing.forEach(f -> System.err.println("  - " + f.getFileName()));
                }
                throw new IOException("Assembly artifact not found.");
            }
        } else {
            sourceArtifact = new File(mavenTargetDir, this.outputImageName);
            if (!sourceArtifact.exists() && OSResolver.isWindows()) {
                File exeArtifact = new File(mavenTargetDir, this.outputImageName + ".exe");
                if(exeArtifact.exists()) sourceArtifact = exeArtifact;
            }
            if (!sourceArtifact.exists()) {
                File appJar = new File(mavenTargetDir, artifactIdToSearch + "-" + versionToSearch + ".jar");
                if (appJar.exists()) {
                    sourceArtifact = appJar;
                } else {
                    appJar = new File(mavenTargetDir, this.outputImageName + ".jar");
                    if (appJar.exists()) sourceArtifact = appJar;
                }
            }
        }

        if (sourceArtifact == null || !sourceArtifact.exists()) {
            System.err.println("Error: Final built artifact could not be definitively located in " + mavenTargetDir.getAbsolutePath() + ". Listing target dir:");
            try (java.util.stream.Stream<java.nio.file.Path> listing = Files.list(mavenTargetDir.toPath())) {
                listing.forEach(f -> System.err.println("  - " + f.getFileName()));
            }
            throw new IOException("Final built artifact not found for base name: " + this.outputImageName + " or " + artifactIdToSearch);
        }

        File outputDirFile = new File(this.outputDirectory);
        outputDirFile.mkdirs();

        File destinationFile;
        if (this.finalArtifactNameOverride != null && !this.finalArtifactNameOverride.isEmpty()) {
            destinationFile = new File(outputDirFile, this.finalArtifactNameOverride);
        } else {
            destinationFile = new File(outputDirFile, sourceArtifact.getName());
        }

        FileUtils.copyFile(sourceArtifact, destinationFile);
        System.out.println("Successfully built and copied artifact to: " + destinationFile.getAbsolutePath());

        boolean isLikelyNativeExecutable = !this.assemblyBuild && !destinationFile.getName().endsWith(".jar") && !destinationFile.getName().endsWith(".zip") && !destinationFile.getName().endsWith(".tar.gz");
        if (isLikelyNativeExecutable && !OSResolver.isWindows()) {
            if(destinationFile.setExecutable(true, false)) {
                System.out.println("Made artifact executable: " + destinationFile.getAbsolutePath());
            } else {
                System.err.println("Warning: Failed to make artifact executable: " + destinationFile.getAbsolutePath());
            }
        }

        boolean buildSharedLibrary = this.numpySharedLibrary;
        if (buildSharedLibrary && this.libOutputPath != null && !this.libOutputPath.isEmpty()) {
            String sharedLibExt = (this.binaryExtension != null ? this.binaryExtension : OSResolver.sharedLibraryExtension());
            String sharedLibBaseName = this.outputImageName;
            String sharedLibName = (OSResolver.isWindows() ? sharedLibBaseName : "lib" + sharedLibBaseName) + sharedLibExt;

            File sourceSharedLib = new File(mavenTargetDir, sharedLibName);
            if (!sourceSharedLib.exists() && (OSResolver.isMac() || OSResolver.isWindows())) {
                sourceSharedLib = new File(mavenTargetDir, sharedLibBaseName + sharedLibExt);
            }

            if (sourceSharedLib.exists()) {
                File libOutputDir = new File(this.libOutputPath);
                libOutputDir.mkdirs();
                File destLib = new File(libOutputDir, sourceSharedLib.getName());
                FileUtils.copyFile(sourceSharedLib, destLib);
                System.out.println("Copied shared library to: " + destLib.getAbsolutePath());
            } else {
                System.err.println("Warning: Expected shared library '" + sharedLibName + "' (or similar) not found in " + mavenTargetDir.getAbsolutePath() + " for copying to libOutputPath.");
            }
        }
    }

    private void startSystemMonitor() {
        this.systemMonitorService = Executors.newScheduledThreadPool(1, r -> {
            Thread t = Executors.defaultThreadFactory().newThread(r);
            t.setDaemon(true);
            return t;
        });

        this.systemMonitorService.scheduleAtFixedRate(() -> {
            SystemInfo si = new SystemInfo();
            GlobalMemory memory = si.getHardware().getMemory();
            CentralProcessor processor = si.getHardware().getProcessor();
            try {
                double[] loadAverages = processor.getSystemLoadAverage(1);
                String loadAvgStr = "N/A";
                if (loadAverages != null && loadAverages.length > 0 && loadAverages[0] >=0) {
                    loadAvgStr = String.format(Locale.US, "%.2f%%", loadAverages[0] * 100.0);
                }

                System.out.printf("[System Monitor] Memory: %s / %s available. CPU Load (1m): %s%n",
                        formatBytes(memory.getAvailable()),
                        formatBytes(memory.getTotal()),
                        loadAvgStr
                );
            } catch (Exception e) { /* ignore */ }
        }, 10, 30, TimeUnit.SECONDS);
    }

    private void stopSystemMonitor() {
        if (this.systemMonitorService != null && !this.systemMonitorService.isShutdown()) {
            this.systemMonitorService.shutdownNow();
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 0) return "N/A";
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        if (exp == 0 && bytes > 0) return bytes + " B";
        if (exp <= 0) return bytes + " B";
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format(Locale.US, "%.2f %sB", bytes / Math.pow(1024, exp), pre);
    }

    public void cleanupTemporaryFiles() {
        for (File tempItem : this.tempFilesAndDirsToClean) {
            if (tempItem != null && tempItem.exists()) {
                try {
                    if (tempItem.isDirectory()) FileUtils.deleteDirectory(tempItem);
                    else FileUtils.deleteQuietly(tempItem);
                } catch (IOException e) {
                    System.err.println("Warning: Failed to clean up temporary item " + tempItem.getAbsolutePath() + ": " + e.getMessage());
                }
            }
        }
        this.tempFilesAndDirsToClean.clear();
        System.out.println("Temporary build files cleaned up.");
    }

    public KompileApplicationBuilder ragMcpAssistantParentVersion(String effectiveAppVersion) {
        this.ragMcpAssistantParentVersion = effectiveAppVersion;
        return this;
    }


    // --- Picocli Command Wrapper (Example) ---
    @CommandLine.Command(name = "build-kompile-app",
            mixinStandardHelpOptions = true,
            versionProvider = Info.ManifestVersionProvider.class,
            description = "Builds a Kompile application with an embedded pipeline and optional providers.")
    public static class BuildKompileAppCommand implements Callable<Integer> {

        // CLI Options mirroring KompileApplicationBuilder fields + BaseGenerateImageAndSdk fields
        @CommandLine.Option(names = {"--pipeline-file"}, description = "Path to the Kompile Pipeline JSON definition file.")
        String pipelineJsonFilePath;

        @CommandLine.Option(names = {"--auto-generate-pipeline"}, description = "If true, generate a pipeline based on --components. Can be omitted if --components are provided without --pipeline-file.")
        Boolean autoGeneratePipelineOverride;

        @CommandLine.Option(names = {"--components"}, description = "Comma-separated list of components for auto-generated pipeline (e.g., python,onnx,dl4j).", split = ",")
        List<String> autoGenCliComponentFlags;

        // --- Parameters for auto-generated steps ---
        // Python
        @CommandLine.Option(names = "--python-script-uri", description = "URI for Python script (if 'python' in --components)") String pythonScriptUri;
        @CommandLine.Option(names = "--python-input-vars", description = "Python input vars (dataKey=pyVar,...)", split = ",") Map<String,String> pythonInputVars;
        @CommandLine.Option(names = "--python-output-vars", description = "Python output vars (pyVar=dataKey,...)", split = ",") Map<String,String> pythonOutputVars;
        @CommandLine.Option(names = "--python-path", description = "Path to specific python executable or virtual env for Python step") String pythonPathForStep;
        @CommandLine.Option(names = "--python-setup-code", description = "Python code to run once at init for Python step") String pythonSetupCode;
        @CommandLine.Option(names = "--python-return-full-globals", description = "Return all Python globals as output for Python step") boolean pythonReturnFullGlobals;
        @CommandLine.Option(names = "--python-script-args", description = "Arguments for scriptPath in Python step (comma-separated)", split = ",") List<String> pythonScriptArgs;
        @CommandLine.Option(names = "--python-env-vars", description = "Environment variables for Python step (KEY=VALUE,...)", split = ",") Map<String,String> pythonEnvVars;

        // ONNX
        @CommandLine.Option(names = "--onnx-model-uri", description = "URI for ONNX model (if 'onnx' in --components)") String onnxModelUri;
        @CommandLine.Option(names = "--onnx-input-names", description = "ONNX input names (comma-separated)", split = ",") List<String> onnxInputNames;
        @CommandLine.Option(names = "--onnx-output-names", description = "ONNX output names (comma-separated)", split = ",") List<String> onnxOutputNames;
        @CommandLine.Option(names = "--onnx-execution-provider", description = "ONNX execution provider (e.g., CPU_FP32, CUDA_FP16)") String onnxExecutionProvider;
        @CommandLine.Option(names = "--onnx-intra-op-threads", description = "ONNX intra op num threads") Integer onnxIntraOpThreads;
        @CommandLine.Option(names = "--onnx-inter-op-threads", description = "ONNX inter op num threads") Integer onnxInterOpThreads;

        // DL4J
        @CommandLine.Option(names = "--dl4j-model-uri", description = "URI for DL4J model (if 'dl4j' in --components)") String dl4jModelUri;
        @CommandLine.Option(names = "--dl4j-input-names", description = "DL4J input names (comma-separated)", split = ",") List<String> dl4jInputNames;
        @CommandLine.Option(names = "--dl4j-output-names", description = "DL4J output names (comma-separated)", split = ",") List<String> dl4jOutputNames;

        // SameDiff
        @CommandLine.Option(names = "--samediff-model-uri", description = "URI for SameDiff model (if 'samediff' in --components)") String samediffModelUri;
        @CommandLine.Option(names = "--samediff-input-names", description = "SameDiff input names (comma-separated)", split = ",") List<String> samediffInputNames;
        @CommandLine.Option(names = "--samediff-output-names", description = "SameDiff output names (comma-separated)", split = ",") List<String> samediffOutputNames;

        // TensorFlow
        @CommandLine.Option(names = "--tensorflow-model-uri", description = "URI for TensorFlow model (if 'tensorflow' in --components)") String tensorflowModelUri;
        @CommandLine.Option(names = "--tensorflow-input-names", description = "TensorFlow input names (comma-separated)", split = ",") List<String> tensorflowInputNames;
        @CommandLine.Option(names = "--tensorflow-output-names", description = "TensorFlow output names (comma-separated)", split = ",") List<String> tensorflowOutputNames;
        @CommandLine.Option(names = "--tensorflow-saved-model-tag", description = "TensorFlow SavedModel tag (e.g., serve)") String tensorflowSavedModelTag;
        @CommandLine.Option(names = "--tensorflow-signature-def-key", description = "TensorFlow SignatureDef key (e.g., serving_default)") String tensorflowSignatureDefKey;

        // TVM
        @CommandLine.Option(names = "--tvm-model-uri", description = "URI for TVM model (if 'tvm' in --components)") String tvmModelUri;
        @CommandLine.Option(names = "--tvm-input-names", description = "TVM input names (comma-separated)", split = ",") List<String> tvmInputNames;
        @CommandLine.Option(names = "--tvm-output-names", description = "TVM output names (comma-separated)", split = ",") List<String> tvmOutputNames;

        // ImageToNDArray
        @CommandLine.Option(names = "--image-height", description = "Target height for ImageToNDArray step") Integer imageHeight;
        @CommandLine.Option(names = "--image-width", description = "Target width for ImageToNDArray step") Integer imageWidth;
        @CommandLine.Option(names = "--image-format", description = "Image format (e.g., RGB, BGR) for ImageToNDArray step") String imageFormat;
        @CommandLine.Option(names = "--image-normalization-config", description = "Image normalization config as JSON string for ImageToNDArray (e.g. '{\"type\":\"SCALE\",\"value\":255.0}')") String imageNormalizationConfigJson;
        @CommandLine.Option(names = "--image-channel-layout", description = "Channel layout (e.g., NCHW, NHWC) for ImageToNDArray step") String imageChannelLayout;
        @CommandLine.Option(names = "--image-data-type", description = "Output NDArray data type (e.g., FLOAT, UINT8) for ImageToNDArray step") String imageDataType;


        // DocumentParser
        @CommandLine.Option(names = "--docparser-input-type", description = "Input type for DocumentParser (e.g., URI, BYTES)") String docparserInputType;
        @CommandLine.Option(names = "--docparser-output-type", description = "Output type for DocumentParser (e.g., STRING, DATA)") String docparserOutputType;
        @CommandLine.Option(names = "--docparser-text-extraction", description = "Text extraction type (e.g., RAW, OCR_ONLY) for DocumentParser") String docparserTextExtraction;


        @CommandLine.Option(names = {"-o", "--output-image-name"}, description = "Name of the output artifact (e.g., my-app). Default: kompile-app")
        String outputImageName = "kompile-app";
        @CommandLine.Option(names = {"--output-dir"}, description = "Directory to place the final artifact. Default: current directory")
        String outputDirectory = ".";
        @CommandLine.Option(names = {"--final-artifact-name"}, description = "Optional: specific full name for the final output file (e.g., my-app-dist.zip).")
        String finalArtifactNameOverride;
        @CommandLine.Option(names = {"--lib-output-path"},description = "Output path for shared library if built." )
        String libOutputPath = "./lib";
        @CommandLine.Option(names = {"--include-path"},description = "Include path for C/C++ compilation if needed." )
        String includePath = "./include";

        @CommandLine.Option(names = {"--app-type"}, description = "Type of application: pipeline-executor (default), kompile-spring-boot-webapp.")
        String appType = "pipeline-executor";
        @CommandLine.Option(names = {"--entry-point-class"}, description = "FQCN of the main class for the application.")
        String appEntryPointClass;
        @CommandLine.Option(names = {"--webapp-static-dir"}, description = "Path to static web resources (for webapp types).")
        String webappStaticResourcesDir;
        @CommandLine.Option(names = {"--app-configs"}, description = "Comma-separated paths to app config files (e.g., application.properties).", split = ",")
        List<String> appExtraConfigFiles;
        @CommandLine.Option(names = "--enable-frontend-build", description = "Enable Angular frontend build for 'kompile-spring-boot-webapp' type. Default: false")
        boolean enableFrontendBuild = false;

        @CommandLine.Option(names = {"--assembly"}, description = "Build a distributable assembly. Default: false.")
        boolean assemblyBuild = false;

        @CommandLine.Option(names = {"--maven-home"}, description = "Path to Maven installation. Defaults to Kompile-managed Maven.")
        String mavenHome = Info.mavenDirectory().getAbsolutePath();
        @CommandLine.Option(names = {"--build-platform"}, description = "Target platform (e.g., linux-x86_64). Auto-detected if not set.")
        String buildPlatform;
        @CommandLine.Option(names = {"--binary-extension"},description = "Binary extension for the target platform (e.g., .so, .dylib, .dll). Auto-detected if not set." )
        String binaryExtension;
        @CommandLine.Option(names = "--build-threads",description = "Number of threads for compilation processes.")
        long buildThreads = -1;
        @CommandLine.Option(names = "--override-paths",description = "Prepend Kompile-managed tools to PATH. Default: true")
        boolean overridePaths = true;
        @CommandLine.Option(names = "--kompile-prefix",description = "Base working directory for the build. If null, a temp dir is used.")
        String kompilePrefix;

        @CommandLine.Option(names = {"--native"}, description = "Build a native image if app type supports it. Default: true if not --assembly.")
        Boolean buildNative;

        @CommandLine.Option(names = {"--native-image-jvm-arg"}, arity = "0..*", description = "Extra JVM arguments for GraalVM native image build (e.g., -Xmx8g).")
        String[] nativeImageJvmArgs;
        @CommandLine.Option(names = {"--native-image-heap"}, description = "Max heap for GraalVM native image build (e.g., 8g).")
        String nativeImageHeapSpace;
        @CommandLine.Option(names = {"--no-gc"},description = "Use NoOpGC/EpsilonGC in the native image.")
        boolean noGcForNativeImage = false;
        @CommandLine.Option(names = {"--numpy-shared-library"}, description = "Build native image as a shared library for numpy interop. Default: false")
        boolean numpySharedLibrary = false;
        @CommandLine.Option(names = {"--debug-native-build"}, description = "Enable GraalVM debug attach for native image build. Default: false")
        boolean debugNativeBuild = false;
        @CommandLine.Option(names = {"--debug-native-port"}, description = "Port for GraalVM debug attach. Default: 8000")
        int debugNativePort = 8000;
        @CommandLine.Option(names = {"--extra-graalvm-args"}, description = "Raw string of additional GraalVM build arguments, space separated.")
        String extraGraalvmBuildArgsString;


        @CommandLine.Option(names = {"--nd4j-backend"}, description = "ND4J backend. Default: nd4j-native")
        String nd4jBackend = "nd4j-native";
        @CommandLine.Option(names = {"--nd4j-classifier"}, description = "ND4J classifier. Auto-detected if possible.")
        String nd4jClassifier;
        @CommandLine.Option(names = {"--nd4j-extension"},description = "ND4J extension (e.g. -avx2, -cuda-11.8).")
        String nd4jExtension;
        @CommandLine.Option(names = {"--nd4j-helper"}, description = "ND4J helper (e.g., cudnn, onednn).")
        String nd4jHelper;
        @CommandLine.Option(names = {"--nd4j-operations"},description = "ND4J operations to build with libnd4j. Separated with a ';'.")
        String nd4jOperations = "";
        @CommandLine.Option(names = {"--nd4j-datatypes"}, description = "ND4J data types to build with libnd4j. Separated with a ';'.")
        String nd4jDataTypes = "";
        @CommandLine.Option(names = {"--nd4j-use-lto"},description = "Build libnd4j with link time optimization. Defaults to false.")
        boolean nd4jUseLto = false;

        @CommandLine.Option(names = {"--build-dl4j-from-source"}, description = "Path to local DL4J source directory to build and use.")
        String dl4jSourceDir;
        @CommandLine.Option(names = {"--dl4j-build-command"}, description = "Maven command for DL4J source build. Default: clean install -DskipTests=true -Dmaven.javadoc.skip=true")
        String dl4jBuildCommand = "clean install -DskipTests=true -Dmaven.javadoc.skip=true";
        @CommandLine.Option(names = {"--dl4j-native-props"}, description = "Semicolon-separated Maven -D properties for DL4J native build (prop=val;prop2=val2).", split = ";")
        List<String> dl4jNativeBuildProps;
        @CommandLine.Option(names = {"--dl4j-profiles"}, description = "Comma-separated Maven profiles for DL4J source build.", split = ",")
        List<String> dl4jProfiles;

        @CommandLine.Option(names = {"--gcc"},description = "Path to a GCC folder (for custom compiler toolchain).")
        String gccPath;
        @CommandLine.Option(names = {"--glibc"},description = "Path to a GLIBC folder (for custom C library toolchain).")
        String glibcPath;
        @CommandLine.Option(names = {"--allow-external-compilers"},description = "Allow use of GCC/GLIBC paths outside Kompile-managed installs.")
        boolean allowExternalCompilers = false;

        // --- Provider Selection CLI Options ---
        @CommandLine.Option(names = "--llm-provider", description = "LLM provider: openai, gemini, anthropic, noop. Default: noop")
        String llmProvider = "noop";
        @CommandLine.Option(names = "--vectorstore-provider", description = "VectorStore provider: chroma, pgvector, noop. Default: noop")
        String vectorStoreProvider = "noop";
        @CommandLine.Option(names = "--embedding-provider", description = "Embedding provider: openai, sentence-transformer, noop. Default: noop")
        String embeddingProvider = "noop";
        @CommandLine.Option(names = "--document-loader-provider", description = "Document loader: tika, pdf, none. Default: none")
        String documentLoaderProvider = "none";
        @CommandLine.Option(names = "--enable-rag-service", description = "Include RAG service components. Default: false")
        boolean enableRagService = false;
        @CommandLine.Option(names = "--enable-filesystem-tool", description = "Include Filesystem tool components. Default: false")
        boolean enableFilesystemTool = false;
        @CommandLine.Option(names = "--image-normalization", description = "Include Filesystem tool components. Default: false")

        String imageNormalization;

        private KompileApplicationBuilder builderInternal;

        @Override
        public Integer call() throws Exception {
            builderInternal = new KompileApplicationBuilder();
            try {
                String resolvedBuildPlatform = this.buildPlatform;
                if (resolvedBuildPlatform == null || resolvedBuildPlatform.trim().isEmpty()) {
                    resolvedBuildPlatform = OSResolver.os() + "-" + OSResolver.arch();
                }
                String resolvedNd4jClassifier = this.nd4jClassifier;
                if (resolvedNd4jClassifier == null || resolvedNd4jClassifier.trim().isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    sb.append(resolvedBuildPlatform);
                    if (this.nd4jHelper != null && !this.nd4jHelper.isEmpty()) {
                        String cleanHelper = this.nd4jHelper.replaceFirst("^-", "");
                        if(!cleanHelper.isEmpty()) sb.append("-").append(cleanHelper);
                    }
                    if (this.nd4jExtension != null && !this.nd4jExtension.isEmpty()) {
                        String cleanExtension = this.nd4jExtension.replaceFirst("^-", "");
                        if(!cleanExtension.isEmpty()) sb.append("-").append(cleanExtension);
                    }
                    resolvedNd4jClassifier = sb.toString();
                    System.out.println("Auto-derived base for ND4J classifier: " + resolvedNd4jClassifier + ". For specific versions (e.g. CUDA), please provide the full classifier string via --nd4j-classifier.");
                }
                String resolvedBinaryExtension = this.binaryExtension;
                if (resolvedBinaryExtension == null || resolvedBinaryExtension.trim().isEmpty()) {
                    resolvedBinaryExtension = OSResolver.sharedLibraryExtension();
                }

                boolean effectiveAutoGenPipeline;
                if (this.autoGeneratePipelineOverride != null) {
                    effectiveAutoGenPipeline = this.autoGeneratePipelineOverride;
                    if (effectiveAutoGenPipeline && (this.autoGenCliComponentFlags == null || this.autoGenCliComponentFlags.isEmpty())) {
                        System.err.println("Error: --auto-generate-pipeline is true, but no components specified via --components."); return 1;
                    }
                    if (!effectiveAutoGenPipeline && this.pipelineJsonFilePath == null) {
                        System.err.println("Error: --auto-generate-pipeline is false, but no --pipeline-file specified."); return 1;
                    }
                    if (effectiveAutoGenPipeline && this.pipelineJsonFilePath != null) {
                        System.out.println("Warning: Both --pipeline-file and --auto-generate-pipeline=true specified. Auto-generation will be used (pipeline file ignored).");
                        this.pipelineJsonFilePath = null;
                    }
                } else {
                    if (this.pipelineJsonFilePath == null) {
                        if (this.autoGenCliComponentFlags == null || this.autoGenCliComponentFlags.isEmpty()) {
                            System.out.println("Neither --pipeline-file nor --components provided. Building application shell without an embedded pipeline (unless app-type defaults one).");
                            effectiveAutoGenPipeline = true;
                            this.autoGenCliComponentFlags = Collections.emptyList();
                        } else {
                            effectiveAutoGenPipeline = true;
                        }
                    } else {
                        effectiveAutoGenPipeline = false;
                        if (this.autoGenCliComponentFlags != null && !this.autoGenCliComponentFlags.isEmpty()) {
                            System.out.println("Warning: Both --pipeline-file and --components specified. --pipeline-file will be used as --auto-generate-pipeline is not set/false.");
                        }
                    }
                }

                Map<String, Map<String, Object>> stepParamsForAutoGen = new HashMap<>();
                if (effectiveAutoGenPipeline && autoGenCliComponentFlags != null) {
                    // Using String literals for keys, assuming they match StepSchema definitions.
                    // These keys MUST be the exact names expected by the respective step runners' schemas.
                    for(String component : autoGenCliComponentFlags) {
                        Map<String, Object> specificParams = new HashMap<>();
                        String compLower = component.toLowerCase();

                        // String literals for parameter keys. These MUST match the schema of the respective runner.
                        if ("python".equals(compLower)) {
                            if (pythonScriptUri != null) specificParams.put("scriptPath", pythonScriptUri);
                            if (pythonInputVars != null) specificParams.put("inputVariables", pythonInputVars);
                            if (pythonOutputVars != null) specificParams.put("outputVariables", pythonOutputVars);
                            if (pythonPathForStep != null) specificParams.put("pythonPath", pythonPathForStep);
                            if (pythonSetupCode != null) specificParams.put("setupCode", pythonSetupCode);
                            specificParams.put("returnFullPythonGlobals", pythonReturnFullGlobals);
                            if (pythonScriptArgs != null) specificParams.put("args", pythonScriptArgs);
                            if (pythonEnvVars != null) specificParams.put("environmentVariables", pythonEnvVars);
                        } else if ("onnx".equals(compLower)) {
                            if (onnxModelUri != null) specificParams.put("modelUri", onnxModelUri);
                            if (onnxInputNames != null) specificParams.put("inputNames", onnxInputNames);
                            if (onnxOutputNames != null) specificParams.put("outputNames", onnxOutputNames);
                            if (onnxExecutionProvider != null) specificParams.put("executionProvider", onnxExecutionProvider);
                            if (onnxIntraOpThreads != null) specificParams.put("intraOpNumThreads", onnxIntraOpThreads);
                            if (onnxInterOpThreads != null) specificParams.put("interOpNumThreads", onnxInterOpThreads);
                        } else if ("dl4j".equals(compLower)) {
                            if (dl4jModelUri != null) specificParams.put("modelUri", dl4jModelUri);
                            if (dl4jInputNames != null) specificParams.put("inputNames", dl4jInputNames);
                            if (dl4jOutputNames != null) specificParams.put("outputNames", dl4jOutputNames);
                        } else if ("samediff".equals(compLower)) {
                            if (samediffModelUri != null) specificParams.put("modelUri", samediffModelUri);
                            if (samediffInputNames != null) specificParams.put("inputNames", samediffInputNames);
                            if (samediffOutputNames != null) specificParams.put("outputNames", samediffOutputNames);
                        } else if ("tensorflow".equals(compLower)) {
                            if (tensorflowModelUri != null) specificParams.put("modelUri", tensorflowModelUri);
                            if (tensorflowInputNames != null) specificParams.put("inputNames", tensorflowInputNames);
                            if (tensorflowOutputNames != null) specificParams.put("outputNames", tensorflowOutputNames);
                            if (tensorflowSavedModelTag != null) specificParams.put("savedModelTags", Collections.singletonList(tensorflowSavedModelTag));
                            if (tensorflowSignatureDefKey != null) specificParams.put("signatureKey", tensorflowSignatureDefKey);
                        } else if ("tvm".equals(compLower)) {
                            if (tvmModelUri != null) specificParams.put("modelUri", tvmModelUri);
                            if (tvmInputNames != null) specificParams.put("inputNames", tvmInputNames);
                            if (tvmOutputNames != null) specificParams.put("outputNames", tvmOutputNames);
                        } else if ("image".equals(compLower)) { // ImageToNDArray step
                            if (imageHeight != null) specificParams.put("height", imageHeight);
                            if (imageWidth != null) specificParams.put("width", imageWidth);
                            if (imageFormat != null) specificParams.put("imageFormat", imageFormat);
                            if (imageChannelLayout != null) specificParams.put("channelLayout", imageChannelLayout);
                            if (imageDataType != null) specificParams.put("dataType", imageDataType);
                            if (imageNormalizationConfigJson != null && !imageNormalizationConfigJson.isEmpty()) {
                                try {
                                    Map<String,Object> normConf = new ObjectMapper().readValue(imageNormalizationConfigJson, Map.class);
                                    specificParams.put("normalizationConfig", normConf);
                                } catch (Exception e) {
                                    System.err.println("Warning: Could not parse imageNormalizationConfigJson: " + e.getMessage());
                                }
                            } else if (imageNormalization != null) { // Fallback to simple string if complex JSON not provided
                                specificParams.put("normalization", imageNormalization);
                            }
                        } else if ("doc".equals(compLower)) { // DocumentParser step
                            if (docparserInputType != null) specificParams.put("inputType", docparserInputType);
                            if (docparserOutputType != null) specificParams.put("outputType", docparserOutputType);
                            if (docparserTextExtraction != null) specificParams.put("textExtractionType", docparserTextExtraction);
                        }

                        if(!specificParams.isEmpty() || (autoGenCliComponentFlags.contains(component) && !stepParamsForAutoGen.containsKey(compLower)) ) {
                            stepParamsForAutoGen.put(compLower, specificParams);
                        } else if (!stepParamsForAutoGen.containsKey(compLower)) {
                            stepParamsForAutoGen.put(compLower, Collections.emptyMap());
                        }
                    }
                }

                boolean actualAssemblyBuild = this.assemblyBuild;
                boolean actualNativeBuild = (this.buildNative == null) ? !this.assemblyBuild : this.buildNative;
                if (actualAssemblyBuild && actualNativeBuild) {
                    System.out.println("Warning: Both --assembly and --native specified. Building native image only.");
                    actualAssemblyBuild = false;
                }

                builderInternal
                        .pipelineJsonFilePath(this.pipelineJsonFilePath)
                        .autoGeneratePipelineFromCliFlags(effectiveAutoGenPipeline)
                        .autoGenCliComponentFlags(this.autoGenCliComponentFlags)
                        .autoGenStepCliParams(stepParamsForAutoGen)
                        .outputImageName(this.outputImageName)
                        .outputDirectory(this.outputDirectory)
                        .finalArtifactNameOverride(this.finalArtifactNameOverride)
                        .libOutputPath(this.libOutputPath)
                        .includePath(this.includePath)
                        .appType(this.appType)
                        .appEntryPointClass(this.appEntryPointClass)
                        .webappStaticResourcesDir(this.webappStaticResourcesDir)
                        .appExtraConfigFiles(this.appExtraConfigFiles)
                        .enableFrontendBuild(this.enableFrontendBuild)
                        .assemblyBuild(actualAssemblyBuild)
                        .mavenHome(this.mavenHome)
                        .buildPlatform(resolvedBuildPlatform)
                        .binaryExtension(resolvedBinaryExtension)
                        .buildThreads(this.buildThreads > 0 ? this.buildThreads : Runtime.getRuntime().availableProcessors())
                        .overridePaths(this.overridePaths)
                        .kompilePrefix(this.kompilePrefix != null ? this.kompilePrefix : Files.createTempDirectory("kompile-build-").toString())
                        .nativeImageJvmArgs(this.nativeImageJvmArgs)
                        .nativeImageHeapSpace(this.nativeImageHeapSpace)
                        .noGcForNativeImage(this.noGcForNativeImage)
                        .numpySharedLibrary(this.numpySharedLibrary)
                        .debugNativeBuild(this.debugNativeBuild)
                        .debugNativePort(this.debugNativePort)
                        .extraGraalvmBuildArgsString(this.extraGraalvmBuildArgsString)
                        .nd4jBackend(this.nd4jBackend)
                        .nd4jClassifier(resolvedNd4jClassifier)
                        .nd4jExtension(this.nd4jExtension)
                        .nd4jHelper(this.nd4jHelper)
                        .nd4jOperations(this.nd4jOperations)
                        .nd4jDataTypes(this.nd4jDataTypes)
                        .nd4jUseLto(this.nd4jUseLto)
                        .dl4jSourceDir(this.dl4jSourceDir)
                        .dl4jBuildCommand(this.dl4jBuildCommand)
                        .dl4jNativeBuildProps(this.dl4jNativeBuildProps)
                        .dl4jProfiles(this.dl4jProfiles)
                        .gccPath(this.gccPath)
                        .glibcPath(this.glibcPath)
                        .allowExternalCompilers(this.allowExternalCompilers)
                        .llmProvider(this.llmProvider)
                        .vectorStoreProvider(this.vectorStoreProvider)
                        .embeddingProvider(this.embeddingProvider)
                        .documentLoaderProvider(this.documentLoaderProvider)
                        .enableRagService(this.enableRagService)
                        .enableFilesystemTool(this.enableFilesystemTool)
                        .kompileVersion(Info.getVersion())
                        .kompilePipelinesVersion(Info.getKompilePipelinesVersion())
                        .kompileAppVersion(Info.getKompileAppVersion());

                return builderInternal.build();
            } finally {
                if (builderInternal != null) {
                    builderInternal.cleanupTemporaryFiles();
                }
            }
        }
    }
}
