/*
 * Copyright (c) 2022 Konduit K.K.
 *
 *     This program and the accompanying materials are made available under the
 *     terms of the Apache License, Version 2.0 which is available at
 *     https://www.apache.org/licenses/LICENSE-2.0.
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *     WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *     License for the specific language governing permissions and limitations
 *     under the License.
 *
 *     SPDX-License-Identifier: Apache-2.0
 */

package ai.konduit.pipelinegenerator.main.build;

import ai.konduit.pipelinegenerator.main.Info;
import ai.konduit.pipelinegenerator.main.install.InstallGraalvm;
import ai.konduit.pipelinegenerator.main.install.InstallHeaders;
import ai.konduit.pipelinegenerator.main.install.InstallPython;
import ai.konduit.pipelinegenerator.main.install.PropertyBasedInstaller;
import ai.konduit.pipelinegenerator.main.util.EnvironmentFile;
import ai.konduit.pipelinegenerator.main.util.EnvironmentUtils;
import ai.konduit.pipelinegenerator.main.util.OSResolver;
import ai.konduit.serving.data.image.step.ndarray.ImageToNDArrayStep;
import ai.konduit.serving.documentparser.DocumentParserStep;
import ai.konduit.serving.models.deeplearning4j.step.DL4JStep;
import ai.konduit.serving.models.onnx.step.ONNXStep;
import ai.konduit.serving.models.samediff.step.SameDiffStep;
import ai.konduit.serving.models.tensorflow.step.TensorFlowStep;
import ai.konduit.serving.models.tvm.step.TVMStep;
import ai.konduit.serving.pipeline.impl.pipeline.SequencePipeline;
import ai.konduit.serving.python.PythonStep;
import ai.konduit.serving.vertx.config.InferenceConfiguration;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.zeroturnaround.exec.ProcessExecutor;
import oshi.SystemInfo;
import picocli.CommandLine;


import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.*;

/**
 * Base class for working with any generate-image-and-sdk.sh
 * command delegation.
 */
public abstract class BaseGenerateImageAndSdk implements Callable<Integer> {

    @CommandLine.Option(names = {"--pipelineFile"},description = "Whether to use a pipeline file or not",required = false,scope = CommandLine.ScopeType.INHERIT)
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
    @CommandLine.Option(names = {"--nativeImageHeapSpace"},description = "Heap space for the native image build process. These will be" +
            "passed to the native image plugin in the form of: -JSOMEARG. For this argument don't specify the -Xmx extra argument. Just specify memory requirements like 2g or 1000mb. Usually 6 to 8g of ram is required. If a build" +
            "is taking a while this maybe due to JVM heap space being about used up. Specify more to fix this. Most of the time it is safe to leave this argument unspecified.")
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
    @CommandLine.Option(names = {"--mavenHome"},description = "The maven home location for compiling native image. Defaults to $HOME/.kompile/mvn - this is with the assumption " +
            "the user is using kompile install for maintaining kompile dependencies. A user however may override this.",
            required = false,scope = CommandLine.ScopeType.INHERIT)
    protected String mavenHome = Info.mavenDirectory().getAbsolutePath();
    @CommandLine.Option(names = {"--buildPlatform"},description = "The platform to build for, usually a javacpp.platform value such as linux-x86_64",
            required = false,scope = CommandLine.ScopeType.INHERIT)
    protected String buildPlatform;
    @CommandLine.Option(names = {"--binaryExtension"},description = "The platform to build for, usually a javacpp.platform value such as linux-x86_64",
            required = false,scope = CommandLine.ScopeType.INHERIT)
    protected String binaryExtension;
    @CommandLine.Option(names = {"--nd4jBackend"},description = "The nd4j backend to use in the image",required = false,scope = CommandLine.ScopeType.INHERIT)
    protected String nd4jBackend = "nd4j-native";
    @CommandLine.Option(names = {"--nd4jClassifier"},description = "The nd4j classifier to use",required = false,scope = CommandLine.ScopeType.INHERIT)
    protected String nd4jClassifier = "linux-x86_64";


    @CommandLine.Option(names = {"--dl4jBranch"},description = "The branch to clone dl4j from",required = false,scope = CommandLine.ScopeType.INHERIT)
    protected String dl4jBranch = "master";

    @CommandLine.Option(names = {"--konduitServingBranch"},description = "The branch to clone dl4j from",required = false,scope = CommandLine.ScopeType.INHERIT)
    protected String konduitServingBranch = "master";



    @CommandLine.Option(names = {"--nd4jExtension"},description = "The nd4j classifier to use",required = false,scope = CommandLine.ScopeType.INHERIT)
    protected String nd4jExtension;

    @CommandLine.Option(names = {"--gcc"},description = "The path to a gcc folder containing libraries used for gcc. This override is mainly meant to be used where custom gcc are needed. A custom CC/CXX and PATH update will be used when running the c++ library build.",required = false,scope = CommandLine.ScopeType.INHERIT)
    protected String gcc;

    @CommandLine.Option(names = {"--glibc"},description = "The path to a glibc folder containing libraries used for glibc. This  override is mainly meant to be used where a custom glibc is needed. A custom LD_LIBRARY_PATH update will be used when running the c++ library build.",required = false,scope = CommandLine.ScopeType.INHERIT)
    protected String glibc;

    @CommandLine.Option(names = {"--assembly"},description = "Whether to build a dl4j distribution tar file or a graalvm image",required = false,scope = CommandLine.ScopeType.INHERIT)
    protected boolean assembly = false;
    @CommandLine.Option(names = {"--nd4jOperations"},description = "The operations to build with libnd4j. If left empty," +
            " just builds with all ops. Otherwise builds with all ops. Op list is separated with a ;. " +
            "These operations are parsed as cmake lists.",scope = CommandLine.ScopeType.INHERIT)
    protected String nd4jOperations = "";
    @CommandLine.Option(names = {"--nd4jDataTypes"},
            description = "The data types to build with libnd4j. If left empty, just builds with all data types." +
                    " Otherwise builds with all data types. Data type list is separated with a ;. These operations are parsed as cmake lists.",scope = CommandLine.ScopeType.INHERIT)
    protected String nd4jDataTypes = "";


    @CommandLine.Option(names = {"--nd4jHelper"},description = "The nd4j classifier to use",required = false,scope = CommandLine.ScopeType.INHERIT)
    protected String nd4jHelper;

    @CommandLine.Option(names = "--buildThreads",description = "The kompile prefix where the relevant kompile source code is for compilation.",scope = CommandLine.ScopeType.INHERIT)
    protected long buildThreads = Runtime.getRuntime().availableProcessors();

    @CommandLine.Option(names = {"--nd4jUseLto"},description = "Whether to build with link time optimization or not. When link time optimization is used, the linker can take a long time. Turn this on for smaller binaries, but longer build times. Defaults to false.")
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
    @CommandLine.Option(names = {"--noGc"},description = "Whether to use gc in the image or not",required = false,scope = CommandLine.ScopeType.INHERIT)
    protected boolean noGc = false;

    @CommandLine.Option(names = "--nativeImageFilesPath",description = "The path to the files for building an image",scope = CommandLine.ScopeType.INHERIT)
    protected String nativeImageFilesPath = EnvironmentUtils.defaultNativeImageFilesPath();

    @CommandLine.Option(names = "--kompilePrefix",description = "The kompile prefix where the relevant kompile source code is for compilation.",scope = CommandLine.ScopeType.INHERIT)
    protected String kompilePrefix = "./";
    @CommandLine.Option(names = "--pythonExecutable",description = "The executable to use with python. " +
            "Defaults to the python found on the path. Otherwise will use the built in python installed with ./kompile install python",scope = CommandLine.ScopeType.INHERIT)
    protected String pythonExecutable = EnvironmentUtils.defaultPythonExecutable();
    @CommandLine.Option(names = {"--allowExternalCompilers"},description = "Whether to allow external compilers outside of managed .kompile installs in builds. Setting this flag means you need to specify the absolute path to the parent directory of the gcc and g++ executables.")
    private boolean allowExternalCompilers = false;


    @CommandLine.Option(names = {"--python"},description = "Whether to use python or not",scope = CommandLine.ScopeType.INHERIT)
    protected boolean python = false;
    @CommandLine.Option(names = {"--onnx"},description = "Whether to use onnx or not",scope = CommandLine.ScopeType.INHERIT)
    protected boolean onnx = false;
    @CommandLine.Option(names = {"--tvm"},description = "Whether to use tvm or not",scope = CommandLine.ScopeType.INHERIT)
    protected boolean tvm = false;
    @CommandLine.Option(names = {"--dl4j"},description = "Whether to use dl4j or not",scope = CommandLine.ScopeType.INHERIT)
    protected boolean dl4j = false;
    @CommandLine.Option(names = "--samediff",description = "Whether to use samediff or not",scope = CommandLine.ScopeType.INHERIT)
    protected boolean samediff = false;
    @CommandLine.Option(names = "--nd4j",description = "Whether to use nd4j or not",scope = CommandLine.ScopeType.INHERIT)
    protected boolean nd4j = false;
    @CommandLine.Option(names = "--server",description = "Whether to the expected file is a server configuration or not. Defaults to false.",scope = CommandLine.ScopeType.INHERIT)
    protected boolean server = false;

    @CommandLine.Option(names = "--tensorflow",description = "Whether to use tensorflow or not",scope = CommandLine.ScopeType.INHERIT)
    protected boolean tensorflow = false;
    @CommandLine.Option(names = "--nd4j-tensorflow",description = "Whether to use nd4j-tensorflow or not",scope = CommandLine.ScopeType.INHERIT)
    protected boolean nd4jTensorflow = false;
    @CommandLine.Option(names = "--image",description = "Whether to use image pre processing or not or not",scope = CommandLine.ScopeType.INHERIT)
    protected boolean image = false;
    @CommandLine.Option(names = "--doc",description = "Whether to use document  processing or not or not",scope = CommandLine.ScopeType.INHERIT)
    protected boolean doc = false;
    @CommandLine.Option(names = "--overridePath",description = "Whether to override path with various utilities for builds. This contains programs used for building various tools including: cmake,mvn,graalvm. The files managed by kompile will be automatically included in the path." +
            "",scope = CommandLine.ScopeType.INHERIT)
    protected boolean overridePaths = true;

    @CommandLine.Option(names = {"--konduitServingBranchName"},description = "The branch to clone konduit-serving: defaults to master",scope = CommandLine.ScopeType.INHERIT)
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

    protected File generatePipelineBasedOnSpec() throws IOException {
        File newPipeline = new File(System.getProperty("java.io.tmpdir"),"temp-pipeline-" + UUID.randomUUID() + ".json");
        newPipeline.deleteOnExit();
        SequencePipeline.Builder pipeline = SequencePipeline.builder();
        if(tvm)
            pipeline.add(new TVMStep());
        if(dl4j)
            pipeline.add(new DL4JStep());
        if(tensorflow)
            pipeline.add(new TensorFlowStep());
        if(python)
            pipeline.add(new PythonStep());

        if(doc)
            pipeline.add(new DocumentParserStep());

        if(samediff)
            pipeline.add(new SameDiffStep());
        if(onnx)
            pipeline.add(new ONNXStep());
        if(image)
            pipeline.add(new ImageToNDArrayStep());


        SequencePipeline build = pipeline.build();
        if(server) {
            System.out.println("Generating server.");
            //ensure that it's a server for consistency with isServer flag being true in configuration
            InferenceConfiguration inferenceConfiguration = new InferenceConfiguration()
                    .pipeline(build);
            System.out.println("Wrote json " + inferenceConfiguration.toJson());

            FileUtils.write(newPipeline,inferenceConfiguration.toJson(), Charset.defaultCharset());
            System.out.println("Wrote json " + inferenceConfiguration.toJson());
        } else {
            System.out.println("Generating pipeline.");
            FileUtils.write(newPipeline,build.toJson(), Charset.defaultCharset());
            System.out.println("Wrote json " + build.toJson());
        }

        return newPipeline;
    }

    protected void checkExists(File dir,String module) {
        if(!dir.exists()) {
            System.err.println(String.format("Unable to generate image. Please run kompile install %s before continuing. You can also run kompile install all to install all relevant dependencies. Note that other dependencies may need to be installed such as your nd4j backend specific compiler such as gcc, nvcc, or ncc. ",module));
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
                command.add("--nativeImageJvmArg=" + jvmArg);
            }
        }
        System.out.println("Running generate image command: " + command);
    }

    protected void extractResources(File kompileResources) throws IOException {
        for(String s : new String[] {"numpy_struct.h","konduit-serving.h"}) {
            try(InputStream is =new FileInputStream(new File(InstallHeaders.headersDir(),s))) {
                String headerContent = IOUtils.toString(is,Charset.defaultCharset());
                File tempFile = new File(kompileResources,s);
                tempFile.createNewFile();
                tempFile.deleteOnExit();
                FileUtils.write(tempFile,headerContent,Charset.defaultCharset(),false);
            }
        }
    }


    protected File extractScript(InputStream is) throws IOException {
        String scriptContent = IOUtils.toString(is,Charset.defaultCharset());
        File tempFile = new File(kompilePrefix,"generate-image-and-sdk.sh");
        tempFile.createNewFile();
        tempFile.deleteOnExit();
        FileUtils.write(tempFile,scriptContent,Charset.defaultCharset(),false);
        tempFile.setExecutable(true);
        return tempFile;
    }

    protected void setDefaultFlagsBasedOnPipeline() {
        if(anySpecifiedPipelines() && pipelineFile != null) {
            System.err.println("Please only specify a pipeline file or a set of target components.");
            System.exit(1);
        } else if(!anySpecifiedPipelines() && pipelineFile == null) {
            tvm = true;
            tensorflow = true;
            nd4jTensorflow = true;
            dl4j = true;
            samediff = true;
            nd4j = true;
            python = true;
            onnx = true;
            doc = true;
            System.out.println("No pipeline file or specific components specified. Building all.");
        }
    }


    protected void setPipelineFile() throws IOException {
        if(anySpecifiedPipelines()) {
            File newPipeline = generatePipelineBasedOnSpec();
            pipelineFile = newPipeline.getAbsolutePath();
        }
    }

    protected Map<String,String> createEnv() throws IOException {
        Map<String,String> env = new HashMap<>();
        //setup graalvm and java home for maven
        System.out.println("Setting graalvm and java home to " + Info.graalvmDirectory().getAbsolutePath());
        env.put("GRAALVM_HOME",Info.graalvmDirectory().getAbsolutePath());
        env.put("JAVA_HOME",Info.graalvmDirectory().getAbsolutePath());
        if(System.getenv().containsKey("USER"))
            env.put("USER",System.getenv("USER"));
        else
            env.put("USER",System.getProperty("user.name"));
        if(System.getenv().containsKey("PLATFORM"))
            env.put("PLATFORM",System.getenv("PLATFORM"));
        else
            env.put("PLATFORM", OSResolver.os());
        if(nd4jClassifier != null && nd4jBackend != null) {
            File file = EnvironmentFile.envFileForBackendAndPlatform(nd4jBackend, nd4jClassifier);
            Map<String, String> envMap = EnvironmentFile.loadFromEnvFile(file);
            env.putAll(envMap);
        }

        //add additional environment variables to the path like mvn,cmake,python,java
        StringBuilder addPath = new StringBuilder();
        String currPath = System.getenv("PATH");
        if(currPath == null)
            currPath = "";


        if(overridePaths) {
            String[] additionalBuilds = {"cmake","mvn","java"};
            System.out.println("Overriding paths adding cmake,mvn,java managed by kompile to path");
            for(String exec : additionalBuilds) {
                File execFile = EnvironmentUtils.executableOnPath(exec);
                File kompileHome = Info.homeDirectory();
                //check that it's the managed installation, we need this to ensure libc compatibility and to guarantee builds happen
                if(execFile != null && execFile.getParentFile().getName().equals(".kompile")) {
                    addPath.append(execFile.getParentFile().getAbsolutePath());
                    System.out.println("Added path for " + exec + " as " + execFile.getAbsolutePath());
                    addPath.append(File.pathSeparator);
                } else {
                    switch (exec) {
                        case "java":
                            InstallGraalvm installGraalvm = new InstallGraalvm();
                            CommandLine commandLine = new CommandLine(installGraalvm);
                            commandLine.execute();
                            execFile = new File(kompileHome,"graalvm" + File.separator + "bin" + File.separator + "java");
                            break;
                        case "mvn":
                            PropertyBasedInstaller propertyBasedInstaller = new PropertyBasedInstaller();
                            CommandLine commandLine2 = new CommandLine(propertyBasedInstaller);
                            commandLine2.execute("--programName=mvn");
                            execFile = new File(kompileHome,"mvn" + File.separator + "bin" + File.separator + "mvn");
                            break;
                        case "python":
                            InstallPython installPython = new InstallPython();
                            CommandLine commandLine1 = new CommandLine(installPython);
                            commandLine1.execute();
                            execFile = new File(kompileHome,"python" + File.separator + "bin" + File.separator + "python");
                            break;
                        case "cmake":
                            PropertyBasedInstaller propertyBasedInstaller2 = new PropertyBasedInstaller();
                            CommandLine commandLine23 = new CommandLine(propertyBasedInstaller2);
                            commandLine23.execute("--programName=cmake");
                            execFile = new File(kompileHome,"cmake" + File.separator + "bin" + File.separator + "cmake");
                            break;
                    }

                    addPath.append(execFile.getParentFile().getAbsolutePath());
                    addPath.append(File.pathSeparator);
                    System.out.println("New path for executable " + exec + " with file " + execFile);
                }
            }

        }

        if(currPath != null) {
            if(!addPath.toString().isEmpty())
                addPath.append(File.pathSeparator);
            addPath.append(currPath);
        }

        String realPath = addPath.toString().replace(File.pathSeparator + File.pathSeparator,File.pathSeparator);
        System.out.println("Added additional path: " + realPath);

        env.put("PATH",realPath);

        return env;
    }

    protected File createResourceDirectory() {
        //unpack resources needed for inclusion and linking of files for the generate images script
        File kompileResources = new File(kompilePrefix,"src/main/resources");
        if(!kompileResources.exists()) {
            kompileResources.mkdirs();
        }
        return kompileResources;
    }



    public abstract void setCustomDefaults();
    public abstract void doCustomCommands(List<String> commands);

    @Override
    public Integer call() throws Exception {
        checkExists(Info.graalvmDirectory(), "graalvm");
        checkExists(Info.mavenDirectory(), "maven");
        checkExists(Info.pythonDirectory(), "python");

        if(nd4jBackend.equals("nd4j-cuda-10.2"))
            enableJetsonNano = true;
        //set just in case we need to customize flags
        setCustomDefaults();
        setDefaultFlagsBasedOnPipeline();
        List<String> command = new ArrayList<>();


        ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1);
        executorService.scheduleAtFixedRate(() -> {
            SystemInfo systemInfo = new SystemInfo();
            System.out.println("Available Memory: " + systemInfo.getHardware().getMemory().getAvailable() + " out of Total Memory: " +  systemInfo.getHardware().getMemory().getTotal());
            System.out.println("Swap memory used: " + systemInfo.getHardware().getMemory().getSwapUsed() + " out of Total Swap: " + systemInfo.getHardware().getMemory().getSwapTotal());
            System.out.println("Logical Processor Count: " + systemInfo.getHardware().getProcessor().getLogicalProcessorCount());
            System.out.println("CPU System Load Average" + systemInfo.getHardware().getProcessor().getSystemLoadAverage());
            System.out.println("CPU Load" + systemInfo.getHardware().getProcessor().getSystemCpuLoad());

        },1,1, TimeUnit.SECONDS);


        //unpack resources needed for inclusion and linking of files for the generate images script
        File kompileResources = createResourceDirectory();

        if(!assembly) {
            extractResources(kompileResources);
        }

        try (InputStream is = URI.create("https://raw.githubusercontent.com/KonduitAI/kompile-program-repository/main/generate-image-and-sdk.sh").toURL().openStream()) {
            File tempFile = extractScript(is);
            Map<String, String> env = createEnv();
            System.out.println("Setting graalvm and java home to " + Info.graalvmDirectory().getAbsolutePath());
            command.add(tempFile.getAbsolutePath());

            setPipelineFile();

            //add all command values
            addCommands(command);
            //add other values specific to command assumptions
            doCustomCommands(command);


            System.out.println("Running generate image command: " + command);


            return new ProcessExecutor().environment(env)
                    .command(command)
                    .readOutput(true)
                    .redirectOutput(System.out)
                    .start().getFuture().get().getExitValue();
        }
    }
}
