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
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.nd4j.common.io.ClassPathResource;
import org.zeroturnaround.exec.ProcessExecutor;
import picocli.CommandLine;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "generate-image-and-sdk",
        mixinStandardHelpOptions = false,
        description = "Generate and build a python SDK using an embedded shell script. " +
                "Pass parameters down to the shell script using the parameters below. This command may require additional tools such as graalvm, maven and a local compiler such as gcc to run correctly.")
public class GenerateImageAndSDK implements Callable<Integer>  {

    @CommandLine.Option(names = {"--pipelineFile"},description = "Whether to use a pipeline file or not",required = false)
    private String pipelineFile;
    @CommandLine.Option(names = {"--imageName"},description = "Name of image output file",required = false)
    private String imageName = "kompile-image";
    @CommandLine.Option(names = {"--kompilePythonPath"},description = "Path to kompile python sdk",required = false)
    private String kompilePythonPath = "../kompile-python";
    @CommandLine.Option(names = {"--kompileCPath"},description = "Path to kompile c library",required = false)
    private String kompileCPath = "../kompile-c-library";
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

    @CommandLine.Option(names = {"--enableJetsonNano"},description = "Whether to use jetson nano dependencies or not",required = false)
    private boolean enableJetsonNano = false;
    @CommandLine.Option(names = {"--buildSharedLibrary"},description = "Whether to build a shared library or not, defaults to true",required = false)
    private boolean buildSharedLibrary = true;
    @CommandLine.Option(names = {"--mainClass"},description = "The entry point to use in the image",required = false)
    private String mainClass;
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
    @CommandLine.Option(names = "--pythonExecutable",description = "The executable to use with python. Defaults to the python found on the path. Otherwise will use the built in python installed with ./kompile install python")
    private String pythonExecutable = "python";

    public GenerateImageAndSDK() {
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
            File tempFile = new File("generate-image-and-sdk.sh");
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
            if(pipelineFile != null && !pipelineFile.isEmpty()) {
                command.add("--pipeline-file");
                command.add(String.valueOf(pipelineFile));
            }

            if(kompilePythonPath != null && !kompilePythonPath.isEmpty()) {
                command.add("--python-sdk");
                command.add(kompilePythonPath);
            }

            if(kompileCPath != null && !kompileCPath.isEmpty()) {
                command.add("--c-library");
                command.add(kompileCPath);
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

            if(pythonExecutable != null && !pythonExecutable.isEmpty()) {
                command.add("--python-exec");
                command.add(pythonExecutable);
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
