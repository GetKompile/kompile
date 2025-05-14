/*
 * Copyright (c) 2022 Konduit K.K.
 * Copyright (c) 2023-2025 Kompile Inc. // Example updated copyright
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package ai.kompile.cli.main.build;

import ai.kompile.cli.main.Info;
// Removed PropertyResolver import from here as it's in the same package as this hypothetical merged file
// Re-adding for clarity if this class is standalone.
import ai.kompile.cli.main.properties.PropertyResolver; // Will be used as the "config accessor"
import ai.kompile.cli.main.install.InstallGraalvm;
import ai.kompile.cli.main.install.InstallPython;
import ai.kompile.cli.main.install.PropertyBasedInstaller;
import ai.kompile.cli.main.util.OSResolver;
import ai.kompile.cli.main.install.InstallHeaders;
import ai.kompile.cli.main.util.EnvironmentFile;
import ai.kompile.cli.main.util.EnvironmentUtils;

// Kompile Pipelines Framework imports
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.core.config.GenericStepConfig;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.core.data.serde.ObjectMappers;
import ai.kompile.pipelines.framework.runtime.pipeline.SequencePipeline;

// RunnerConstants imports are to be REMOVED as per user instruction.
// FQCNs will be solely from PropertyResolver.
// import ai.kompile.pipelines.steps.python.PythonRunnerConstants;
// import ai.kompile.pipelines.steps.deeplearning4j.DL4JRunnerConstants;
// import ai.kompile.pipelines.steps.onnx.ONNXRunnerConstants;
// import ai.kompile.pipelines.steps.samediff.SameDiffConstants;
// import ai.kompile.pipelines.steps.documentparser.DocumentParserConstants;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.zeroturnaround.exec.ProcessExecutor;
import picocli.CommandLine;
import oshi.SystemInfo;
import oshi.hardware.GlobalMemory;
import oshi.hardware.CentralProcessor;


import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
// import java.nio.charset.Charset; // Replaced by StandardCharsets
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Base class for working with any generate-image-and-sdk.sh
 * command delegation using Kompile Pipelines Framework.
 */
public abstract class BaseGenerateImageAndSdk implements Callable<Integer> {

    // --- Runner FQCN Property Keys (to be looked up via PropertyResolver) ---
    private static final String KEY_PYTHON_RUNNER_FQCN = "kompile.runner.python.fqcn";
    private static final String KEY_DL4J_RUNNER_FQCN = "kompile.runner.dl4j.fqcn";
    private static final String KEY_ONNX_RUNNER_FQCN = "kompile.runner.onnx.fqcn";
    private static final String KEY_SAMEDIFF_RUNNER_FQCN = "kompile.runner.samediff.fqcn";
    private static final String KEY_DOCPARSER_RUNNER_FQCN = "kompile.runner.docparser.fqcn";
    private static final String KEY_TVM_RUNNER_FQCN = "kompile.runner.tvm.fqcn";
    private static final String KEY_TENSORFLOW_RUNNER_FQCN = "kompile.runner.tensorflow.fqcn";
    private static final String KEY_IMAGE_RUNNER_FQCN = "kompile.runner.image.fqcn";

    // No more DEFAULT_XXX_RUNNER_FQCN constants here. FQCNs must be defined via properties.

    protected PropertyResolver propertyResolver; // Instance of PropertyResolver


    // CLI options ... (omitted for brevity, same as previous versions)
    @CommandLine.Option(names = {"--pipelineFile"},description = "Path to a Kompile Pipeline Framework JSON/YAML pipeline file",required = false,scope = CommandLine.ScopeType.INHERIT)
    protected String pipelineFile;
    @CommandLine.Option(names = {"--imageName"},description = "Name of image output file",required = false,scope = CommandLine.ScopeType.INHERIT)
    protected String imageName = "kompile-image";
    @CommandLine.Option(names = {"--kompilePythonPath"},description = "Path to kompile python sdk",required = false,scope = CommandLine.ScopeType.INHERIT)
    protected String kompilePythonPath = EnvironmentUtils.defaultKompilePythonPath();
    @CommandLine.Option(names = {"--kompileCPath"},description = "Path to kompile c library",required = false,scope = CommandLine.ScopeType.INHERIT)
    protected String kompileCPath = EnvironmentUtils.defaultKompileCPath();

    @CommandLine.Option(names = {"--nativeImageJvmArg"},description = "Extra JVM arguments for the native image build process. These will be" +
            "passed to the native image plugin in the form of: -JSOMEARG")
    private String[] nativeImageJvmArgs;
    @CommandLine.Option(names = {"--nativeImageHeapSpace"},description = "Heap space for the native image build process. For this argument don't specify the -Xmx extra argument. Just specify memory requirements like 2g or 1000mb. Usually 6 to 8g of ram is required.")
    private String nativeImageHeapSpace;

    @CommandLine.Option(names = {"--pomGenerateOutputPath"},description = "Output path of the generated pom.xml for compiling native image",
            required = false,scope = CommandLine.ScopeType.INHERIT)
    protected String pomGenerateOutputPath = "pom2.xml";
    @CommandLine.Option(names = {"--libOutputPath"},description = "Location of where to put c library after compilation",required = false,scope = CommandLine.ScopeType.INHERIT)
    protected String libOutputPath = "./lib";
    @CommandLine.Option(names = {"--includePath"},description = "Location of include path for compilation/linking",required = false,scope = CommandLine.ScopeType.INHERIT)
    protected String includePath = "./include";
    @CommandLine.Option(names = {"--bundleOutputPath"},description = "Path to output file of complete bundle",required = false,scope = CommandLine.ScopeType.INHERIT)
    protected String bundleOutputPath;
    @CommandLine.Option(names = {"--mavenHome"},description = "The maven home location for compiling native image. Defaults to $HOME/.kompile/mvn.",
            required = false,scope = CommandLine.ScopeType.INHERIT)
    protected String mavenHome = Info.mavenDirectory().getAbsolutePath();
    @CommandLine.Option(names = {"--buildPlatform"},description = "The platform to build for, usually a javacpp.platform value such as linux-x86_64",
            required = false,scope = CommandLine.ScopeType.INHERIT)
    protected String buildPlatform;
    @CommandLine.Option(names = {"--binaryExtension"},description = "Binary extension for the target platform (e.g., .so, .dylib, .dll)",
            required = false,scope = CommandLine.ScopeType.INHERIT)
    protected String binaryExtension;
    @CommandLine.Option(names = {"--nd4jBackend"},description = "The nd4j backend to use in the image",required = false,scope = CommandLine.ScopeType.INHERIT)
    protected String nd4jBackend = "nd4j-native";
    @CommandLine.Option(names = {"--nd4jClassifier"},description = "The nd4j classifier to use",required = false,scope = CommandLine.ScopeType.INHERIT)
    protected String nd4jClassifier = "linux-x86_64";


    @CommandLine.Option(names = {"--dl4jBranch"},description = "The branch to clone dl4j from",required = false,scope = CommandLine.ScopeType.INHERIT)
    protected String dl4jBranch = "master";

    @CommandLine.Option(names = {"--konduitServingBranch"},description = "The branch to clone konduit-serving from (if still needed for other components)",required = false,scope = CommandLine.ScopeType.INHERIT)
    protected String konduitServingBranch = "master"; // This might be deprecated if fully on KPF


    @CommandLine.Option(names = {"--nd4jExtension"},description = "The nd4j extension (e.g. for specific hardware capabilities)",required = false,scope = CommandLine.ScopeType.INHERIT)
    protected String nd4jExtension;

    @CommandLine.Option(names = {"--gcc"},description = "The path to a gcc folder containing libraries used for gcc.",required = false,scope = CommandLine.ScopeType.INHERIT)
    protected String gcc;

    @CommandLine.Option(names = {"--glibc"},description = "The path to a glibc folder containing libraries used for glibc.",required = false,scope = CommandLine.ScopeType.INHERIT)
    protected String glibc;

    @CommandLine.Option(names = {"--assembly"},description = "Whether to build a distribution tar file or a graalvm image",required = false,scope = CommandLine.ScopeType.INHERIT)
    protected boolean assembly = false;
    @CommandLine.Option(names = {"--nd4jOperations"},description = "The operations to build with libnd4j. Separated with a ';'.",scope = CommandLine.ScopeType.INHERIT)
    protected String nd4jOperations = "";
    @CommandLine.Option(names = {"--nd4jDataTypes"},
            description = "The data types to build with libnd4j. Separated with a ';'.",scope = CommandLine.ScopeType.INHERIT)
    protected String nd4jDataTypes = "";


    @CommandLine.Option(names = {"--nd4jHelper"},description = "Path or identifier for ND4J helper libraries/configurations",required = false,scope = CommandLine.ScopeType.INHERIT)
    protected String nd4jHelper;

    @CommandLine.Option(names = "--buildThreads",description = "Number of threads for compilation processes.",scope = CommandLine.ScopeType.INHERIT)
    protected long buildThreads = Runtime.getRuntime().availableProcessors();

    @CommandLine.Option(names = {"--nd4jUseLto"},description = "Whether to build with link time optimization for libnd4j. Defaults to false.")
    private boolean libnd4jUseLto = false;
    @CommandLine.Option(names = {"--enableJetsonNano"},description = "Whether to use jetson nano dependencies or not",required = false,scope = CommandLine.ScopeType.INHERIT)
    protected boolean enableJetsonNano = false;
    @CommandLine.Option(names = {"--buildSharedLibrary"},description = "Whether to build a shared library or not, defaults to true"
            ,required = false,scope = CommandLine.ScopeType.INHERIT)
    protected boolean buildSharedLibrary = true;
    @CommandLine.Option(names = {"--mainClass"},description = "The entry point to use in the image",required = false,scope = CommandLine.ScopeType.INHERIT)
    protected String mainClass;
    @CommandLine.Option(names = {"--minRamMegs"},description = "The minimum memory usage for the image",required = false,scope = CommandLine.ScopeType.INHERIT)
    protected long minRamMegs = 2000;
    @CommandLine.Option(names = {"--maxRamMegs"},description = "The maximum memory usage for the image",required = false,scope = CommandLine.ScopeType.INHERIT)
    protected long maxRamMegs = 2000;
    @CommandLine.Option(names = {"--noGc"},description = "Whether to use NoOpGC in the native image",required = false,scope = CommandLine.ScopeType.INHERIT)
    protected boolean noGc = false;

    @CommandLine.Option(names = "--nativeImageFilesPath",description = "The path to the files for building an image",scope = CommandLine.ScopeType.INHERIT)
    protected String nativeImageFilesPath = EnvironmentUtils.defaultNativeImageFilesPath();

    @CommandLine.Option(names = "--kompilePrefix",description = "The kompile prefix where the relevant kompile source code is for compilation.",scope = CommandLine.ScopeType.INHERIT)
    protected String kompilePrefix = "./";
    @CommandLine.Option(names = "--pythonExecutable",description = "The executable to use with python.",scope = CommandLine.ScopeType.INHERIT)
    protected String pythonExecutable = EnvironmentUtils.defaultPythonExecutable();
    @CommandLine.Option(names = {"--allowExternalCompilers"},description = "Whether to allow external compilers outside of managed .kompile installs in builds.")
    private boolean allowExternalCompilers = false;


    @CommandLine.Option(names = {"--python"},description = "Include Python step in auto-generated pipeline",scope = CommandLine.ScopeType.INHERIT)
    protected boolean python = false;
    @CommandLine.Option(names = {"--onnx"},description = "Include ONNX step in auto-generated pipeline",scope = CommandLine.ScopeType.INHERIT)
    protected boolean onnx = false;
    @CommandLine.Option(names = {"--tvm"},description = "Include TVM step in auto-generated pipeline",scope = CommandLine.ScopeType.INHERIT)
    protected boolean tvm = false;
    @CommandLine.Option(names = {"--dl4j"},description = "Include Deeplearning4J step in auto-generated pipeline",scope = CommandLine.ScopeType.INHERIT)
    protected boolean dl4j = false;
    @CommandLine.Option(names = "--samediff",description = "Include SameDiff step in auto-generated pipeline",scope = CommandLine.ScopeType.INHERIT)
    protected boolean samediff = false;
    @CommandLine.Option(names = "--nd4j",description = "Flag related to ND4J dependencies/operations.",scope = CommandLine.ScopeType.INHERIT)
    protected boolean nd4j = false;
    @CommandLine.Option(names = "--server",description = "Indicates server context for generated pipeline.",scope = CommandLine.ScopeType.INHERIT)
    protected boolean server = false;

    @CommandLine.Option(names = "--tensorflow",description = "Include TensorFlow step in auto-generated pipeline",scope = CommandLine.ScopeType.INHERIT)
    protected boolean tensorflow = false;
    @CommandLine.Option(names = "--nd4j-tensorflow",description = "Flag for ND4J-TensorFlow interoperability.",scope = CommandLine.ScopeType.INHERIT)
    protected boolean nd4jTensorflow = false;
    @CommandLine.Option(names = "--image",description = "Include ImageToNDArray step in auto-generated pipeline",scope = CommandLine.ScopeType.INHERIT)
    protected boolean image = false;
    @CommandLine.Option(names = "--doc",description = "Include DocumentParser step in auto-generated pipeline",scope = CommandLine.ScopeType.INHERIT)
    protected boolean doc = false;
    @CommandLine.Option(names = "--overridePath",description = "Whether to override PATH with Kompile managed utilities for builds.",scope = CommandLine.ScopeType.INHERIT)
    protected boolean overridePaths = true;

    @CommandLine.Option(names = {"--konduitServingBranchName"},description = "The branch to clone konduit-serving from (potentially deprecated)",scope = CommandLine.ScopeType.INHERIT)
    protected String konduitServingBranchName = "master";


    protected boolean anySpecifiedPipelines() {
        return tensorflow ||
                tvm ||
                nd4jTensorflow ||
                python ||
                dl4j ||
                onnx ||
                samediff ||
                image ||
                doc;
    }

    /**
     * Retrieves the FQCN for a runner using PropertyResolver.
     * If the property is not found or is empty, logs an error and returns null.
     *
     * @param propertyKey The key to look up in system properties (resolved by PropertyResolver).
     * @param componentName User-friendly name of the component for error messages.
     * @return The FQCN string or null if not found/configured.
     */
    private String getRequiredRunnerFqcn(String propertyKey, String componentName) {
        String fqcn = this.propertyResolver.getValue(propertyKey); // PropertyResolver handles System.getProperty & System.getenv
        if (fqcn == null || fqcn.trim().isEmpty()) {
            System.err.println("Error: FQCN for " + componentName + " runner (key: '" + propertyKey + "') is not configured. " +
                    "Please set the corresponding system property. Skipping " + componentName + " step.");
            return null;
        }
        System.out.println("Resolved " + componentName + " Runner FQCN from property '" + propertyKey + "': " + fqcn);
        return fqcn;
    }

    protected File generatePipelineBasedOnSpec() throws IOException {
        File newPipelineFile = new File(System.getProperty("java.io.tmpdir"),"kompile-temp-pipeline-" + UUID.randomUUID() + ".json");
        newPipelineFile.deleteOnExit();

        List<StepConfig> steps = new ArrayList<>();
        Data emptyParams = Data.Factory.get().fromMap(Collections.emptyMap());

        if (tvm) {
            String fqcn = getRequiredRunnerFqcn(KEY_TVM_RUNNER_FQCN, "TVM");
            if (fqcn != null) steps.add(new GenericStepConfig(fqcn, emptyParams));
        }
        if (dl4j) {
            String fqcn = getRequiredRunnerFqcn(KEY_DL4J_RUNNER_FQCN, "Deeplearning4J");
            if (fqcn != null) steps.add(new GenericStepConfig(fqcn, emptyParams));
        }
        if (tensorflow) {
            String fqcn = getRequiredRunnerFqcn(KEY_TENSORFLOW_RUNNER_FQCN, "TensorFlow");
            if (fqcn != null) steps.add(new GenericStepConfig(fqcn, emptyParams));
        }
        if (python) {
            String fqcn = getRequiredRunnerFqcn(KEY_PYTHON_RUNNER_FQCN, "Python");
            if (fqcn != null) steps.add(new GenericStepConfig(fqcn, emptyParams));
        }
        if (doc) {
            String fqcn = getRequiredRunnerFqcn(KEY_DOCPARSER_RUNNER_FQCN, "DocumentParser");
            if (fqcn != null) steps.add(new GenericStepConfig(fqcn, emptyParams));
        }
        if (samediff) {
            String fqcn = getRequiredRunnerFqcn(KEY_SAMEDIFF_RUNNER_FQCN, "SameDiff");
            if (fqcn != null) steps.add(new GenericStepConfig(fqcn, emptyParams));
        }
        if (onnx) {
            String fqcn = getRequiredRunnerFqcn(KEY_ONNX_RUNNER_FQCN, "ONNX");
            if (fqcn != null) steps.add(new GenericStepConfig(fqcn, emptyParams));
        }
        if (image) {
            String fqcn = getRequiredRunnerFqcn(KEY_IMAGE_RUNNER_FQCN, "ImageToNDArray");
            if (fqcn != null) steps.add(new GenericStepConfig(fqcn, emptyParams));
        }

        if (steps.isEmpty() && anySpecifiedPipelines()) {
            System.err.println("Warning: No steps were added to the pipeline, but components were specified. " +
                    "This might be due to missing FQCN configurations for all selected components.");
        }

        SequencePipeline kpfPipeline = SequencePipeline.builder().steps(steps).build();

        String pipelineJson;
        try {
            pipelineJson = ObjectMappers.getJsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(kpfPipeline);
        } catch (Exception e) {
            throw new IOException("Failed to serialize Kompile pipeline to JSON", e);
        }

        if (server) {
            System.out.println("Generating Kompile pipeline for server context (actual deployment wrapper is separate).");
        } else {
            System.out.println("Generating Kompile pipeline.");
        }

        FileUtils.writeStringToFile(newPipelineFile, pipelineJson, StandardCharsets.UTF_8);
        System.out.println("Wrote Kompile pipeline JSON to " + newPipelineFile.getAbsolutePath() + ":\n" + pipelineJson);

        return newPipelineFile;
    }

    // ... other methods (checkExists, addCommand, etc.) from previous version ...
    // For brevity, their full code is omitted here but assumed to be the refined versions from the prior step.
    // Ensure methods like createEnv, createResourceDirectory also do not use removed constants implicitly.

    protected void checkExists(File dir,String module) {
        if(!dir.exists()) {
            System.err.println(String.format("Unable to generate image. Kompile dependency '%s' not found at %s. Please run 'kompile install %s' or 'kompile install all'.", module, dir.getAbsolutePath(), module));
            System.exit(1);
        }
    }

    protected void addCommand(String commandValue,String scriptName,List<String> commandList) {
        if(commandValue != null && !commandValue.isEmpty()) {
            commandList.add(scriptName);
            commandList.add(commandValue);
        }
    }

    protected void addCommands(List<String> command) {
        addCommand(String.valueOf(libnd4jUseLto),"--use-lto",command);
        addCommand(String.valueOf(allowExternalCompilers),"--allow-external-compilers",command);
        addCommand(nd4jHelper,"--nd4j-helper",command);
        addCommand(nd4jDataTypes,"--nd4j-datatypes",command);
        addCommand(nd4jOperations,"--nd4j-operations",command);
        addCommand(konduitServingBranchName,"--konduit-serving-branch",command);
        addCommand(pipelineFile,"--pipeline-file",command);
        addCommand(imageName,"--image-name",command);
        addCommand(nativeImageFilesPath,"--native-image-file-path",command);
        addCommand(pomGenerateOutputPath,"--pom-path",command);
        addCommand(libOutputPath,"--lib-path",command);
        addCommand(mavenHome,"--maven-home",command);
        addCommand(kompilePrefix,"--kompile-prefix",command);
        addCommand(nd4jBackend,"--nd4j-backend",command);
        addCommand(nd4jClassifier,"--nd4j-classifier",command);
        addCommand(kompileCPath,"--c-library",command);
        addCommand(kompilePythonPath,"--python-sdk",command);
        addCommand(pythonExecutable,"--python-exec",command);
        addCommand(nd4jExtension,"--nd4j-extension",command);
        addCommand(gcc,"--gcc",command);
        addCommand(glibc,"--glibc",command);
        addCommand(String.valueOf(buildThreads),"--build-threads",command);
        addCommand(konduitServingBranch,"--konduit-serving-branch",command);
        addCommand(dl4jBranch,"--dl4j-branch",command);

        command.add("--assembly");
        command.add(String.valueOf(assembly));
        command.add("--server");
        command.add(String.valueOf(server));
        command.add("--enable-jetson-nano");
        command.add(String.valueOf(enableJetsonNano));
        command.add("--build-shared");
        command.add(String.valueOf(buildSharedLibrary));

        if(nativeImageHeapSpace != null) {
            addCommand(nativeImageHeapSpace,"--native-image-heap-space",command);
        }

        addCommand(mainClass,"--main-class",command);

        command.add("--min-ram");
        command.add(String.valueOf(minRamMegs));
        command.add("--max-ram");
        command.add(String.valueOf(maxRamMegs));
        command.add("--no-garbage-collection");
        command.add(String.valueOf(noGc));

        if(nativeImageJvmArgs != null) {
            for(String jvmArg : nativeImageJvmArgs) {
                if (!jvmArg.startsWith("-J")) {
                    command.add("--nativeImageJvmArg=-J" + jvmArg);
                } else {
                    command.add("--nativeImageJvmArg=" + jvmArg);
                }
            }
        }
        if (!command.isEmpty() && command.get(0).endsWith("generate-image-and-sdk.sh")) {
            System.out.println("Running generate image command arguments: " + String.join(" ", command.subList(1, command.size())));
        } else {
            System.out.println("Running generate image command: " + String.join(" ", command));
        }
    }

    protected void extractResources(File kompileResourcesDir) throws IOException {
        File headersBaseDir = InstallHeaders.headersDir();
        if (!headersBaseDir.exists() || !headersBaseDir.isDirectory()) {
            System.err.println("Headers directory not found: " + headersBaseDir.getAbsolutePath() + ". Skipping resource extraction.");
            return;
        }

        for(String s : new String[] {"numpy_struct.h","konduit-serving.h"}) { // "konduit-serving.h" should likely become "kompile.h" or similar
            File sourceHeaderFile = new File(headersBaseDir, s);
            if (!sourceHeaderFile.exists()) {
                System.err.println("Header file not found: " + sourceHeaderFile.getAbsolutePath() + ". Skipping this resource.");
                continue;
            }
            try(InputStream is = new FileInputStream(sourceHeaderFile)) {
                String headerContent = IOUtils.toString(is, StandardCharsets.UTF_8);
                File tempFile = new File(kompileResourcesDir,s);
                FileUtils.writeStringToFile(tempFile,headerContent,StandardCharsets.UTF_8,false);
            } catch (IOException e) {
                System.err.println("Failed to extract resource " + s + " into " + kompileResourcesDir.getAbsolutePath() + ": " + e.getMessage());
            }
        }
    }


    protected File extractScript(InputStream is) throws IOException {
        String scriptContent = IOUtils.toString(is, StandardCharsets.UTF_8);
        File prefixDir = new File(kompilePrefix);
        if (!prefixDir.exists()) prefixDir.mkdirs();
        if (!prefixDir.isDirectory()) throw new IOException("kompilePrefix is not a directory: " + kompilePrefix);

        File tempFile = new File(prefixDir,"generate-image-and-sdk.sh");
        FileUtils.writeStringToFile(tempFile,scriptContent,StandardCharsets.UTF_8,false);
        if (!tempFile.setExecutable(true)) {
            System.err.println("Warning: Failed to set executable permission on script: " + tempFile.getAbsolutePath());
        }
        return tempFile;
    }

    protected void setDefaultFlagsBasedOnPipeline() {
        if(anySpecifiedPipelines() && pipelineFile != null) {
            System.err.println("Error: Please only specify a pipeline file or a set of target components, not both.");
            System.exit(1);
        } else if(!anySpecifiedPipelines() && pipelineFile == null) {
            tvm = true;
            tensorflow = true;
            dl4j = true;
            samediff = true;
            nd4j = true;
            python = true;
            onnx = true;
            doc = true;
            image = true;
            System.out.println("No pipeline file or specific components specified. Defaulting to include all standard components in auto-generated pipeline.");
        }
    }


    protected void setPipelineFile() throws IOException {
        if(anySpecifiedPipelines() && pipelineFile == null) {
            File newPipeline = generatePipelineBasedOnSpec();
            pipelineFile = newPipeline.getAbsolutePath();
        }
    }

    protected Map<String,String> createEnv() throws IOException {
        Map<String,String> env = new HashMap<>(System.getenv());

        File graalvmDir = Info.graalvmDirectory();
        System.out.println("Setting GRAALVM_HOME and JAVA_HOME to " + graalvmDir.getAbsolutePath());
        env.put("GRAALVM_HOME",graalvmDir.getAbsolutePath());
        env.put("JAVA_HOME",graalvmDir.getAbsolutePath());

        if(!env.containsKey("USER") || env.get("USER") == null) {
            env.put("USER",System.getProperty("user.name","unknown_user"));
        }
        if(!env.containsKey("PLATFORM") || env.get("PLATFORM") == null) {
            env.put("PLATFORM", OSResolver.os());
        }

        if(nd4jClassifier != null && nd4jBackend != null) {
            File backendEnvFile = EnvironmentFile.envFileForBackendAndPlatform(nd4jBackend, nd4jClassifier);
            if (backendEnvFile.exists()) {
                Map<String, String> backendEnvMap = EnvironmentFile.loadFromEnvFile(backendEnvFile);
                env.putAll(backendEnvMap);
            } else {
                System.err.println("Warning: Backend environment file not found: " + backendEnvFile.getAbsolutePath());
            }
        }

        if(overridePaths) {
            System.out.println("Overriding PATH: Adding Kompile-managed cmake, mvn, python, java to PATH.");
            List<String> pathsToPrepend = new ArrayList<>();
            File kompileHome = Info.homeDirectory();

            File graalvmJavaBin = new File(graalvmDir, "bin");
            if (graalvmJavaBin.isDirectory()) pathsToPrepend.add(graalvmJavaBin.getAbsolutePath());

            File pythonDir = Info.pythonDirectory();
            File pythonBinDir = new File(pythonDir, "bin");
            File pythonScriptsDir = new File(pythonDir, "Scripts");
            if (pythonBinDir.isDirectory()) pathsToPrepend.add(pythonBinDir.getAbsolutePath());
            else if (pythonScriptsDir.isDirectory()) pathsToPrepend.add(pythonScriptsDir.getAbsolutePath());
            else System.err.println("Warning: Kompile Python bin/Scripts directory not found at " + pythonDir.getAbsolutePath());


            File mavenBinDir = new File(Info.mavenDirectory(), "bin");
            if (mavenBinDir.isDirectory()) pathsToPrepend.add(mavenBinDir.getAbsolutePath());

            File cmakeDir = new File(kompileHome, "cmake");
            File cmakeBinDir = new File(cmakeDir, "bin");
            if(cmakeBinDir.isDirectory()) {
                pathsToPrepend.add(cmakeBinDir.getAbsolutePath());
            } else {
                System.err.println("Warning: Kompile CMake bin directory not found at " + cmakeBinDir.getAbsolutePath() + ". Ensure CMake is installed via 'kompile install cmake'.");
            }


            String currentPath = env.getOrDefault("PATH", "");
            String newPath = String.join(File.pathSeparator, pathsToPrepend) +
                    (pathsToPrepend.isEmpty() || currentPath.isEmpty() ? "" : File.pathSeparator) +
                    currentPath;

            newPath = newPath.replace(File.pathSeparator + File.pathSeparator, File.pathSeparator);
            if (newPath.startsWith(File.pathSeparator) && newPath.length() > 1) newPath = newPath.substring(1);

            System.out.println("Updated PATH: " + newPath);
            env.put("PATH",newPath);
        }
        return env;
    }

    protected File createResourceDirectory() {
        File runtimeResourcesDir = new File(FileUtils.getTempDirectory(), "kompile_build_resources_" + UUID.randomUUID().toString());
        if(!runtimeResourcesDir.exists()) {
            runtimeResourcesDir.mkdirs();
        }
        System.out.println("Created temporary resource directory: " + runtimeResourcesDir.getAbsolutePath());
        return runtimeResourcesDir;
    }

    public abstract void setCustomDefaults();
    public abstract void doCustomCommands(List<String> commands);

    private String formatBytes(long bytes) {
        if (bytes < 0) return "N/A"; // Handle cases like -1 from OSHI for unknown values
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp-1) + "B"; // Corrected to "KMGTPE"
        return String.format("%.2f %s", bytes / Math.pow(1024, exp), pre);
    }

    @Override
    public Integer call() throws Exception {
        // Initialize PropertyResolver instance
        this.propertyResolver = new PropertyResolver();

        checkExists(Info.graalvmDirectory(), "graalvm");
        checkExists(Info.mavenDirectory(), "maven");
        checkExists(Info.pythonDirectory(), "python");

        setCustomDefaults();
        setDefaultFlagsBasedOnPipeline();

        List<String> command = new ArrayList<>();
        Map<String, String> env = createEnv();

        ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1, r -> {
            Thread t = Executors.defaultThreadFactory().newThread(r);
            t.setDaemon(true);
            return t;
        });

        executorService.scheduleAtFixedRate(() -> {
            SystemInfo si = new SystemInfo();
            GlobalMemory memory = si.getHardware().getMemory();
            CentralProcessor processor = si.getHardware().getProcessor();

            try {
                System.out.println("Available Memory: " + formatBytes(memory.getAvailable()) + " out of Total Memory: " + formatBytes(memory.getTotal()));
                System.out.println("Logical Processor Count: " + processor.getLogicalProcessorCount());

                double[] loadAverage = processor.getSystemLoadAverage(3); // Request 3 values
                String loadAvgStr = "N/A";
                if (loadAverage != null) { // OSHI's getSystemLoadAverage(int) should not return null
                    if (loadAverage.length >= 1 && loadAverage[0] >= 0) {
                        loadAvgStr = String.format("%.2f (1m)", loadAverage[0]);
                    }
                    if (loadAverage.length >= 2 && loadAverage[1] >= 0) {
                        loadAvgStr += (loadAvgStr.equals("N/A") ? "" : ", ") + String.format("%.2f (5m)", loadAverage[1]);
                    } else if (loadAvgStr.equals("N/A") && loadAverage.length >=2 ) loadAvgStr += ", N/A (5m)";

                    if (loadAverage.length >= 3 && loadAverage[2] >= 0) {
                        loadAvgStr += (loadAvgStr.equals("N/A") ? "" : ", ") + String.format("%.2f (15m)", loadAverage[2]);
                    } else if (loadAvgStr.equals("N/A") && loadAverage.length >= 3) loadAvgStr += ", N/A (15m)";
                }
                System.out.println("CPU System Load Average: " + loadAvgStr);
                System.out.println("CPU Load: " + String.format("%.2f%%", processor.getSystemLoadAverage(0)[0] * 100.0) +
                        " (Note: may be inaccurate with frequent SystemInfo re-initialization)");
            } catch (Exception e) {
                System.err.println("Error fetching system info: " + e.getMessage());
            }
        },1,1, TimeUnit.SECONDS);


        File kompileResourcesDir = createResourceDirectory();
        List<File> tempDirsToClean = new ArrayList<>();
        if (kompileResourcesDir.getName().startsWith("kompile_build_resources_")) {
            tempDirsToClean.add(kompileResourcesDir);
        }


        if(!assembly) {
            extractResources(kompileResourcesDir);
        }

        String scriptUrl = "https://raw.githubusercontent.com/KonduitAI/kompile-program-repository/main/generate-image-and-sdk.sh";
        scriptUrl = System.getProperty("KOMPILE_GENERATESCRIPT_URL", System.getenv().getOrDefault("KOMPILE_GENERATESCRIPT_URL", scriptUrl));

        File extractedScriptFile = null;
        try (InputStream is = URI.create(scriptUrl).toURL().openStream()) {
            extractedScriptFile = extractScript(is);
            command.add(extractedScriptFile.getAbsolutePath());

            setPipelineFile();

            addCommands(command);
            doCustomCommands(command);

            System.out.println("Executing script: " + extractedScriptFile.getAbsolutePath());

            ProcessExecutor processExecutor = new ProcessExecutor()
                    .environment(env)
                    .command(command)
                    .readOutput(true)
                    .redirectOutput(System.out)
                    .redirectError(System.err);

            int exitValue = processExecutor.execute().getExitValue();
            return exitValue;

        } finally {
            executorService.shutdownNow();
            for (File tempDir : tempDirsToClean) {
                if (tempDir.exists()) {
                    try {
                        FileUtils.deleteDirectory(tempDir);
                        System.out.println("Cleaned up temporary resource directory: " + tempDir.getAbsolutePath());
                    } catch (IOException e) {
                        System.err.println("Warning: Failed to clean up temporary resource directory " + tempDir.getAbsolutePath() + ": " + e.getMessage());
                    }
                }
            }
        }
    }
}