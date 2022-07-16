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
import ai.konduit.serving.data.image.step.ndarray.ImageToNDArrayStep;
import ai.konduit.serving.models.deeplearning4j.step.DL4JStep;
import ai.konduit.serving.models.onnx.step.ONNXStep;
import ai.konduit.serving.models.samediff.step.SameDiffStep;
import ai.konduit.serving.models.tensorflow.step.TensorFlowStep;
import ai.konduit.serving.models.tvm.step.TVMStep;
import ai.konduit.serving.pipeline.impl.pipeline.SequencePipeline;
import ai.konduit.serving.python.PythonStep;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.nd4j.common.io.ClassPathResource;
import org.zeroturnaround.exec.ProcessExecutor;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "generate-serving-binary",
        mixinStandardHelpOptions = false,
        description = "Generate a binary meant for serving models. This will be a static linked binary meant for execution of konduit serving pipelines." +
                " This command may require additional tools such as graalvm, maven and a local compiler such as gcc to run correctly.")
public class GenerateServingBinary implements Callable<Integer>  {

    @CommandLine.Option(names = {"--pipelineFile"},description = "Whether to use a pipeline file or not",required = false)
    private String pipelineFile;
    @CommandLine.Option(names = {"--imageName"},description = "Name of image output file",required = false)
    private String imageName = "kompile-image";

    @CommandLine.Option(names = {"--protocol"},description = "Protocol to use for serving, http or grpc are valid",required = false)
    private String protocol;
    @CommandLine.Option(names = {"--pomGenerateOutputPath"},description = "Output path of the generated pom.xml for compiling native image",required = false)
    private String pomGenerateOutputPath = "pom2.xml";
    @CommandLine.Option(names = {"--libOutputPath"},description = "Location of where to put c library after compilation",required = false)
    private String libOutputPath = "./lib";
    @CommandLine.Option(names = {"--includePath"},description = "Location of include path for compilation/linking",required = false)
    private String includePath = "./include";
    @CommandLine.Option(names = {"--bundleOutputPath"},description = "Path to output file of complete bundle",required = false)
    private String bundleOutputPath;
    @CommandLine.Option(names = {"--mavenHome"},description = "The maven home location for compiling native image",required = false)
    private String mavenHome = Info.mavenDirectory().getAbsolutePath();
    @CommandLine.Option(names = {"--buildPlatform"},description = "The platform to build for, usually a javacpp.platform value such as linux-x86_64",required = false)
    private String buildPlatform;
    @CommandLine.Option(names = {"--binaryExtension"},description = "The platform to build for, usually a javacpp.platform value such as linux-x86_64",required = false)
    private String binaryExtension;
    @CommandLine.Option(names = {"--nd4jBackend"},description = "The nd4j backend to use in the image",required = false)
    private String nd4jBackend = "nd4j-native";
    @CommandLine.Option(names = {"--nd4jClassifier"},description = "The nd4j classifier to use",required = false)
    private String nd4jClassifier = "linux-x86_64";

    @CommandLine.Option(names = {"--nd4jOperations"},description = "The operations to build with libnd4j. If left empty, just builds with all ops. Otherwise builds with all ops. Op list is separated with a ;. These operations are parsed as cmake lists.")
    private String nd4jOperations = "";
    @CommandLine.Option(names = {"--nd4jDataTypes"},description = "The data types to build with libnd4j. If left empty, just builds with all data types. Otherwise builds with all data types. Data type list is separated with a ;. These operations are parsed as cmake lists.")
    private String nd4jDataTypes = "";


    @CommandLine.Option(names = {"--nd4jHelper"},description = "The nd4j classifier to use",required = false)
    private String nd4jHelper;


    @CommandLine.Option(names = {"--enableJetsonNano"},description = "Whether to use jetson nano dependencies or not",required = false)
    private boolean enableJetsonNano = false;
    @CommandLine.Option(names = {"--buildSharedLibrary"},description = "Whether to build a shared library or not, defaults to true",required = false)
    private boolean buildSharedLibrary = false;
    @CommandLine.Option(names = {"--mainClass"},description = "The entry point to use in the image",required = false)
    private String mainClass = "ai.konduit.pipelinegenerator.main.ServingMain";
    @CommandLine.Option(names = {"--minRamMegs"},description = "The minimum memory usage for the image",required = false)
    private long minRamMegs = 2000;
    @CommandLine.Option(names = {"--maxRamMegs"},description = "The maximum memory usage for the image",required = false)
    private long maxRamMegs = 2000;
    @CommandLine.Option(names = {"--noGc"},description = "Whether to use gc in the image or not",required = false)
    private boolean noGc = false;

    @CommandLine.Option(names = "--nativeImageFilesPath",description = "The path to the files for building an image")
    private String nativeImageFilesPath;

    @CommandLine.Option(names = "--kompilePrefix",description = "The kompile prefix where the relevant kompile source code is for compilation.")
    private String kompilePrefix = "./";
    @CommandLine.Option(names = {"--python"},description = "Whether to use python or not")
    private boolean python = false;
    @CommandLine.Option(names = {"--onnx"},description = "Whether to use onnx or not")
    private boolean onnx = false;
    @CommandLine.Option(names = {"--tvm"},description = "Whether to use tvm or not")
    private boolean tvm = false;
    @CommandLine.Option(names = {"--dl4j"},description = "Whether to use dl4j or not")
    private boolean dl4j = false;
    @CommandLine.Option(names = "--samediff",description = "Whether to use samediff or not")
    private boolean samediff = false;
    @CommandLine.Option(names = "--nd4j",description = "Whether to use nd4j or not")
    private boolean nd4j = false;
    @CommandLine.Option(names = "--tensorflow",description = "Whether to use tensorflow or not")
    private boolean tensorflow = false;
    @CommandLine.Option(names = "--nd4j-tensorflow",description = "Whether to use nd4j-tensorflow or not")
    private boolean nd4jTensorflow = false;
    @CommandLine.Option(names = "--image",description = "Whether to use image pre processing or not or not")
    private boolean image = false;

    @CommandLine.Option(names = {"--dl4jBranchName"},description = "The branch to clone for deeplearning4j: defaults to master")
    private String dl4jBranchName = "master";
    @CommandLine.Option(names = {"--konduitServingBranchName"},description = "The branch to clone konduit-serving: defaults to master")
    private String konduitServingBranchName = "master";

    public GenerateServingBinary() {
    }

    private boolean anySpecifiedPipelines() {
        return tensorflow ||
                tvm ||
                nd4jTensorflow ||
                python ||
                dl4j ||
                onnx ||
                samediff ||
                image;
    }

    private File generatePipelineBasedOnSpec() throws IOException {
        File newPipeline = new File(System.getProperty("java.io.tmpdir"),"temp-pipeline-" + UUID.randomUUID().toString());
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
        if(samediff)
            pipeline.add(new SameDiffStep());
        if(onnx)
            pipeline.add(new ONNXStep());
        if(image)
            pipeline.add(new ImageToNDArrayStep());


        SequencePipeline build = pipeline.build();
        FileUtils.write(newPipeline,build.toJson(), Charset.defaultCharset());
        return newPipeline;
    }

    private void checkExists(File dir,String module) {
        if(!dir.exists()) {
            System.err.println(String.format("Unable to generate image. Please run kompile install %s before continuing. You can also run kompile install all to install all relevant dependencies. Note that other dependencies may need to be installed such as your nd4j backend specific compiler such as gcc, nvcc, or ncc. ",module));
            System.exit(1);
        }
    }

    @Override
    public Integer call() throws Exception {
        checkExists(Info.graalvmDirectory(),"graalvm");
        checkExists(Info.mavenDirectory(),"maven");
        checkExists(Info.pythonDirectory(),"python");


        /**
         * TODO: add ability to automatically add inference configuration with port
         * + the desired pipeline potentially either automatically generating a
         * universal binary with the defaults or also wrapping a
         * pre existing pipeline in an inference configuration with a
         * specified port.
         *
         * This could be a flag for generating a server or other things.
         * Also note that we just changed the default to not include the CLI.
         * We may need to add specific flags to specify whether to add the cli or not
         * to generate image and sdk sh
         */
        //unpack resources needed for inclusion and linking of files for the generate images script
        File kompileResources = new File(kompilePrefix,"src/main/resources");
        if(!kompileResources.exists()) {
            kompileResources.mkdirs();
        }

        for(String s : new String[] {"numpy_struct.h","konduit-serving.h"}) {
            ClassPathResource classPathResource = new ClassPathResource(s);
            try(InputStream is = classPathResource.getInputStream()) {
                String headerContent = IOUtils.toString(is);
                File tempFile = new File(kompileResources,s);
                tempFile.createNewFile();
                tempFile.deleteOnExit();
                FileUtils.write(tempFile,headerContent,false);
            }
        }

        ClassPathResource classPathResource = new ClassPathResource("generate-image-and-sdk.sh");
        try(InputStream is = classPathResource.getInputStream()) {
            String scriptContent = IOUtils.toString(is);
            File tempFile = new File(kompilePrefix,"generate-image-and-sdk.sh");
            tempFile.createNewFile();
            tempFile.deleteOnExit();
            FileUtils.write(tempFile,scriptContent,false);
            tempFile.setExecutable(true);


            Map<String,String> env = new HashMap<>();
            //setup graalvm and java home for maven
            System.out.println("Setting graalvm and java home to " + Info.graalvmDirectory().getAbsolutePath());
            env.put("GRAALVM_HOME",Info.graalvmDirectory().getAbsolutePath());
            env.put("JAVA_HOME",Info.graalvmDirectory().getAbsolutePath());

            List<String> command = new ArrayList<>();
            command.add(tempFile.getAbsolutePath());
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
                System.out.println("No pipeline file or specific components specified. Building all.");
            }

            if(anySpecifiedPipelines()) {
                File newPipeline = generatePipelineBasedOnSpec();
                pipelineFile = newPipeline.getAbsolutePath();
            }

            if(nd4jHelper != null && !nd4jHelper.isEmpty()) {
                command.add("--nd4j-helper");
                command.add(nd4jHelper);
            }

            if(nd4jDataTypes != null && !nd4jDataTypes.isEmpty()) {
                command.add("--nd4j-datatypes");
                command.add(nd4jDataTypes);
            }

            if(nd4jOperations != null && !nd4jOperations.isEmpty()) {
                command.add("--nd4j-operations");
                command.add(nd4jOperations);
            }

            if(pipelineFile != null && !pipelineFile.isEmpty()) {
                command.add("--pipeline-file");
                command.add(pipelineFile);
            }


            if(imageName != null && !imageName.isEmpty()) {
                command.add("--image-name");
                command.add(imageName);
            }

            if(nativeImageFilesPath != null && !nativeImageFilesPath.isEmpty()) {
                command.add("--native-image-file-path");
                command.add(nativeImageFilesPath);
            }

            if(protocol != null && !protocol.isEmpty()) {
                command.add("--protocol");
                command.add(protocol);
            }



            if(pomGenerateOutputPath != null && !pomGenerateOutputPath.isEmpty()) {
                command.add("--pom-path");
                command.add(pomGenerateOutputPath);
            }

            if(libOutputPath != null && !libOutputPath.isEmpty()) {
                command.add("--lib-path");
                command.add(libOutputPath);
            }

            if(mavenHome != null && !mavenHome.isEmpty()) {
                command.add("--maven-home");
                command.add(mavenHome);
            }

            if(kompilePrefix != null && !kompilePrefix.isEmpty()) {
                command.add("--kompile-prefix");
                command.add(kompilePrefix);
            }

            if(nd4jBackend != null && !nd4jBackend.isEmpty()) {
                command.add("--nd4j-backend");
                command.add(nd4jBackend);
            }

            if(nd4jClassifier != null && !nd4jClassifier.isEmpty()) {
                command.add("--nd4j-classifier");
                command.add(nd4jClassifier);
            }

            command.add("--server");
            command.add(String.valueOf(true));
            command.add("--enable-jetson-nano");
            command.add(String.valueOf(enableJetsonNano));
            command.add("--build-shared");
            command.add(String.valueOf(buildSharedLibrary));


            if(mainClass != null && !mainClass.isEmpty()) {
                command.add("--main-class");
                command.add(mainClass);
            }

            command.add("--min-ram");
            command.add(String.valueOf(minRamMegs));
            command.add("--max-ram");
            command.add(String.valueOf(maxRamMegs));
            command.add("--no-garbage-collection");
            command.add(String.valueOf(noGc));
            System.out.println("Running generate image command: " + command);


            return new ProcessExecutor().environment(env)
                    .command(command)
                    .readOutput(true)
                    .redirectOutput(System.out)
                    .start().getFuture().get().getExitValue();
        }
    }
}
