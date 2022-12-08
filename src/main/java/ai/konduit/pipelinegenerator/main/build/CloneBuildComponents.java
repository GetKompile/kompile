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
import ai.konduit.pipelinegenerator.main.util.EnvironmentFile;
import ai.konduit.pipelinegenerator.main.util.EnvironmentUtils;
import org.apache.commons.io.FileUtils;
import org.apache.maven.shared.invoker.*;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "clone-build",description = "Clones and builds deeplearning4j and konduit-serving depending on parameters using git. Note: Git is built in to this CLI and does not need to be installed. Note that for building dl4j, various dependencies such as compilers may need to be installed as pre requisites depending on your target architecture such as CPU, CUDA, or a different architecture with cross compilation like ARM.")
public class CloneBuildComponents implements Callable<Integer> {
    @CommandLine.Option(names = {"--dl4jDirectory"},description = "The place to clone deeplearning4j for a build: defaults to $USER/.kompile/deeplearning4j")
    private String dl4jDirectory = System.getProperty("user.home") + "/.kompile/deeplearning4j";
    @CommandLine.Option(names = {"--konduitServingDirectory"},description = "The place to clone konduit-serving for a build: defaults to $USER/.kompile/konduit-serving")
    private String konduitServingDirectory = System.getProperty("user.home") + "/.kompile/konduit-serving";
    @CommandLine.Option(names = {"--dl4jGitUrl"},description = "The URL to clone deeplearning4j from: Defaults to https://github.com/deeplearning4j/deeplearning4j")
    private String dl4jGitUrl = "https://github.com/deeplearning4j/deeplearning4j";
    @CommandLine.Option(names = {"--konduitServingGitUrl"},description = "The URL to clone konduit-serving from: Defaults to https://github.com/KonduitAI/konduit-serving")
    private String konduitServingGitUrl = "https://github.com/KonduitAI/konduit-serving";
    @CommandLine.Option(names = {"--dl4jBranchName"},description = "The branch to clone for deeplearning4j: defaults to master")
    private String dl4jBranchName = "master";
    @CommandLine.Option(names = {"--konduitServingBranchName"},description = "The branch to clone konduit-serving: defaults to master")
    private String konduitServingBranchName = "master";
    @CommandLine.Option(names = {"--buildDl4j"},description = "Whether to build dl4j or not.")
    private boolean buildDl4j = false;


    @CommandLine.Option(names = {"--allowExternalCompilers"},description = "Whether to allow external compilers outside of managed .kompile installs in builds. Setting this flag means you need to specify the absolute path to the parent directory of the gcc and g++ executables.")
    private boolean allowExternalCompilers = false;

    @CommandLine.Option(names = {"--mvnHome"},description = "The maven home.")
    private String mvnHome = System.getProperty("user.home") + "/.kompile/mvn";





    @CommandLine.Option(names = {"--buildKonduitServing"},description = "Whether to build konduit-serving or not")
    private boolean buildKonduitServing = false;
    @CommandLine.Option(names = {"--forceDl4jClone"},description = "Whether to force clone dl4j even if the specified directory exists. If it is, WARNING: it will be deleted.")
    private boolean forceDl4jClone = false;
    @CommandLine.Option(names = {"--forceKonduitServingClone"},description = "Whether to force clone konduit-serving even if the specified directory exists. If it is, WARNING: it will be deleted.")
    private boolean forceKonduitServingClone = false;
    @CommandLine.Option(names = {"--libnd4jBuildType"},description = "How to build the libnd4j c++ code base: release or debug builds. Defaults to release.")
    private String libnd4jBuildType = "release";
    @CommandLine.Option(names = {"--libnd4jChip"},description = "The libnd4j chip to build for. Usually either cpu or cuda. Defaults to cpu.")
    private String libnd4jChip = "cpu";
    @CommandLine.Option(names = {"--platform"},description = "The libnd4j platform to build for. This usually should be the OS + system architecture to build for. Valid values are anything in javacpp.platform such as: linux-x86_64, windows-x86_64, linux-arm64,...")
    private String platform = "linux-x86_64";
    @CommandLine.Option(names = {"--libnd4jExtension"},description = "The chip extension. Usually reserved for cuda. This usually covers something like cudnn.")
    private String libnd4jExtension = "";


    @CommandLine.Option(names = {"--javacppExtension"},description = "The javacpp  extension. This should mainly be for platform extensions specific to javacpp. Also specify libnd4j to be safe.")
    private String javacppExtension = "";

    @CommandLine.Option(names = {"--chipVersion"},description = "The version of the chip to use. Usually reserved for cuda. Values normally would be the target cuda version.")
    private String chipVersion = "";
    @CommandLine.Option(names = {"--chipCompute"},description = "The compute capability to use. Usually used for cuda.")
    private String chipCompute = "";
    @CommandLine.Option(names = {"--libnd4jBuildThreads"},description = "The number of build threads to use for libnd4j: usually known as the -j parameter in make builds.")
    private long libnd4jBuildThreads = Runtime.getRuntime().availableProcessors();
    @CommandLine.Option(names = {"--libnd4jHelper"},description = "The helper to use for libnd4j builds. Usually something like cudnn,onednn,vednn")
    private String libnd4jHelper = "";
    @CommandLine.Option(names = {"--libnd4jOperations"},description = "The operations to build with libnd4j. If left empty, just builds with all ops. Otherwise builds with all ops. Op list is separated with a ;. These operations are parsed as cmake lists.")
    private String libnd4jOperations = "";
    @CommandLine.Option(names = {"--libnd4jDataTypes"},description = "The data types to build with libnd4j. If left empty, just builds with all data types. Otherwise builds with all data types. Data type list is separated with a ;. These operations are parsed as cmake lists.")
    private String libnd4jDataTypes = "";
    @CommandLine.Option(names = {"--libnd4jSanitize"},description = "Whether to build libnd4j with address sanitizer. Defaults to false.")
    private boolean libnd4jSanitize = false;
    @CommandLine.Option(names = {"--libnd4jArch"},description = "Whether to build libnd4j with address sanitizer. Defaults to false.")
    private String libnd4jArch = "";
    @CommandLine.Option(names = {"--libnd4jUseLto"},description = "Whether to build with link time optimization or not. When link time optimization is used, the linker can take a long time. Turn this on for smaller binaries, but longer build times. Defaults to false.")
    private boolean libnd4jUseLto = false;
    @CommandLine.Option(names = {"--nd4jBackend"},description = "Whether to build the cpu backend or not. This means including nd4j-native in the build.")
    private String nd4jBackend = "nd4j-native";
    @CommandLine.Option(names = {"--dl4jBuildCommand"},description = "The build command for maven. Defaults to clean install -Dmaven.test.skip=true for installing the relevant modules and skipping compilation of tests")
    private String dl4jBuildCommand = "clean install -Dmaven.test.skip=true";

    @CommandLine.Option(names = {"--konduitServingBuildCommand"},description = "The build command for maven. Defaults to clean install -Dmaven.test.skip=true for installing the relevant modules and skipping compilation of tests")
    private String konduitServingBuildCommand = "clean install -Dmaven.test.skip=true";
    @CommandLine.Option(names = {"--konduitServingChip"},description = "The chip to use for konduit serving.")
    private String konduitServingChip = "cpu";

    @CommandLine.Option(names = {"--dl4jModule"},description = "The modules to build with dl4j")
    private List<String> dl4jModules;

    @CommandLine.Option(names = {"--glibc"},description = "A custom glibc directory to use with the build. This will modify both the PATH and LD_LIBRARY_PATH for this execution.")
    private String glibc;

    @CommandLine.Option(names = {"--gcc"},description = "A custom gcc directory to use with the build. This will modify both the PATH and LD_LIBRARY_PATH for this execution.")
    private String gcc;
    @CommandLine.Option(names = {"--konduitServingModule"},description = "The modules to build with konduit serving")
    private List<String> konduitServingModule;
    public CloneBuildComponents() {
    }

    @Override
    public Integer call() throws Exception {
        Invoker invoker = new DefaultInvoker();
        platform = platform.replace("-onednn","");
        platform = platform.replace("-vednn","");
        platform = platform.replace("-cudnn","");
        platform = platform.replace("-avx2","");
        platform = platform.replace("-avx512","");


        if(buildDl4j) {
            File dl4jLocation = new File(dl4jDirectory);
            InvocationRequest invocationRequest = new DefaultInvocationRequest();
            if(dl4jLocation.exists() && forceDl4jClone || dl4jLocation.exists() &&  dl4jLocation.listFiles() == null || dl4jLocation.listFiles() != null &&  dl4jLocation.listFiles().length < 1) {
                System.out.println("Forcing deletion of specified dl4j location: " + dl4jLocation);
                FileUtils.deleteDirectory(dl4jLocation);
            }

            if(!dl4jLocation.exists()) {
                System.out.println("Dl4j location not found. Cloning to " + dl4jLocation.getAbsolutePath() + " from url " + dl4jGitUrl + " from branch " + dl4jBranchName);
                Git.cloneRepository()
                        .setURI(dl4jGitUrl)
                        .setDirectory(dl4jLocation)
                        .setBranch(dl4jBranchName)
                        .setProgressMonitor(new TextProgressMonitor())
                        .call();
            }

            //cross compile
            if(platform.contains("arm") || platform.contains("android")) {
                //platform
                System.out.println("Loading configuration for environment for android/embedded.");
                StringBuilder command = new StringBuilder();
                command.append("cd " + new File(dl4jLocation,"libnd4j").getAbsolutePath() + " && chmod +x pi_build.sh && ./pi_build.sh");


                File backendFile = EnvironmentFile.envFileForBackendAndPlatform("nd4j-native", platform );
                if(!backendFile.exists()) {
                    System.err.println("No environment file for platform " + platform + " at expected path " + backendFile.getAbsolutePath());
                }

                Map<String, String> env = EnvironmentFile.loadFromEnvFile(backendFile);
                if(env.isEmpty()) {
                    System.err.println("No environment found for platform " + platform);

                }
                for(Map.Entry<String,String> envEntries : env.entrySet()) {
                    System.out.println("ENV: " + envEntries.getKey() + " = " + envEntries.getValue());
                }


                File tempFileWrite = new File(UUID.randomUUID() + ".sh");

                StringBuilder stringBuilder = new StringBuilder();
                stringBuilder.append("#!/bin/bash\n");
                stringBuilder.append(command + "\n");
                tempFileWrite.deleteOnExit();
                FileUtils.write(tempFileWrite,stringBuilder.toString(), Charset.defaultCharset());
                tempFileWrite.setExecutable(true);

                ProcessResult processResult = new ProcessExecutor().environment(System.getenv())
                        .environment(env)
                        .command(tempFileWrite.getAbsolutePath())
                        .readOutput(true)
                        .redirectOutput(System.out)
                        .start().getFuture().get();
                if(processResult.getExitValue() != 0) {
                    System.err.println("DL4J Installation failed.");
                    return 1;
                }

            } else {
                if(nd4jBackend.contains("cuda")) {
                    String cudaVersion = nd4jBackend.replace("nd4j-cuda-","");
                    File tempFileWrite = new File(UUID.randomUUID() + ".sh");
                    System.out.println("Setting build chip to gpu");

                    StringBuilder stringBuilder = new StringBuilder();
                    stringBuilder.append("#!/bin/bash\n");
                    stringBuilder.append("chmod +x change-cuda-versions.sh && ./change-cuda-versions.sh " + cudaVersion + " \n");
                    tempFileWrite.deleteOnExit();
                    FileUtils.write(tempFileWrite,stringBuilder.toString(), Charset.defaultCharset());
                    tempFileWrite.setExecutable(true);
                    libnd4jChip = "cuda";
                    ProcessResult processResult = new ProcessExecutor().environment(System.getenv())
                            .directory(dl4jLocation)
                            .command(tempFileWrite.getAbsolutePath())
                            .readOutput(true)
                            .redirectOutput(System.out)
                            .start().getFuture().get();
                    if(processResult.getExitValue() != 0) {
                        System.err.println("DL4J Installation failed. Unable to change cuda version.");
                        return 1;
                    }
                } else {
                    libnd4jChip = "cpu";
                    System.out.println("Setting build chip to cpu");
                }

                invocationRequest.setPomFile(new File(dl4jDirectory,"pom.xml"));
                Properties properties = new Properties();
                properties.put("libnd4j.build",libnd4jBuildType);
                properties.put("libnd4j.extension", libnd4jExtension.startsWith("-") ? libnd4jExtension.substring(1) : libnd4jExtension);
                StringBuilder classifier = new StringBuilder();
                classifier.append(platform);
                //build real classifier based on platform
                if(libnd4jHelper != null && !libnd4jHelper.isEmpty()) {
                    classifier.append("-");
                    classifier.append(libnd4jHelper.startsWith("-") ? libnd4jHelper.substring(1) : libnd4jExtension);
                }

                if(libnd4jExtension != null && !libnd4jExtension.isEmpty()) {
                    classifier.append("-");
                    classifier.append(libnd4jExtension.startsWith("-") ? libnd4jExtension.substring(1) : libnd4jExtension);
                }

                if(libnd4jExtension != null && !libnd4jExtension.isEmpty()) {
                    properties.put("libnd4j.extension",libnd4jExtension.startsWith("-") ? libnd4jExtension.substring(1) : libnd4jExtension);
                }


                properties.put("libnd4j.classifier",classifier.toString());
                properties.put("libnd4j.compute",chipCompute);
                properties.put("libnd4j.buildthreads",String.valueOf(libnd4jBuildThreads));
                properties.put("libnd4j.helper",libnd4jHelper.startsWith("-") ? libnd4jHelper.substring(1): libnd4jHelper);
                properties.put("libnd4j.chip",libnd4jChip);
                properties.put("libnd4j.operations",libnd4jOperations);
                properties.put("libnd4j.datatypes",libnd4jDataTypes);
                properties.put("libnd4j.sanitize",libnd4jSanitize ? "ON" : "OFF");
                properties.put("libnd4j.arch",libnd4jArch);
                properties.put("libnd4j.lto",libnd4jUseLto ? "ON" : "OFF");
                properties.put("libnd4j.platform",platform);
                properties.put("javacpp.platform",platform);
                StringBuilder javacppExtension = new StringBuilder();
                if(libnd4jHelper != null && !libnd4jHelper.isEmpty()) {
                    javacppExtension.append(!libnd4jHelper.startsWith("-") ? "-" + libnd4jHelper : libnd4jHelper);
                }


                if(libnd4jExtension != null && !libnd4jExtension.isEmpty()) {
                    javacppExtension.append(!libnd4jExtension.startsWith("-") ? "-" + libnd4jExtension : libnd4jExtension);
                }


                properties.put("javacpp.platform.extension",javacppExtension.toString());

                invocationRequest.setProperties(properties);
                invocationRequest.setGoals(Arrays.asList(dl4jBuildCommand.split(" ")));

                StringBuilder libraryPath = new StringBuilder();
                StringBuilder path = new StringBuilder();

                if(glibc != null && !glibc.isEmpty()) {
                    File glibcDir = new File(Info.homeDirectory(),glibc + File.separator + "lib");
                    libraryPath.append(glibcDir.getAbsolutePath());
                }

                if(gcc != null && !gcc.isEmpty()) {
                    File gccDir = allowExternalCompilers ? new File(gcc) : new File(Info.homeDirectory(),gcc);
                    File gccPath = allowExternalCompilers ? new File(gcc,"gcc") : new File(gccDir, "/bin/gcc");
                    File cxxPath = allowExternalCompilers ? new File(gcc,"g++") : new File(gccDir, "/bin/g++");
                    if(!gccPath.exists()) {
                        return 1;
                    }
                    invocationRequest.addShellEnvironment("CC",gccPath.getAbsolutePath());
                    invocationRequest.addShellEnvironment("CXX",cxxPath.getAbsolutePath());
                    if(!libraryPath.toString().isEmpty()) {
                        libraryPath.append(File.pathSeparator);
                        File gccLdPath = new File(gccDir,"lib64");
                        libraryPath.append(gccLdPath.getAbsolutePath());
                    } else {
                        File gccLdPath = new File(gccDir,"lib");
                        libraryPath.append(gccLdPath.getAbsolutePath());
                    }

                    if(!path.toString().isEmpty()) {
                        path.append(File.pathSeparator);
                        File gccBin = new File(gccDir,"bin");
                        path.append(gccBin.getAbsolutePath());

                    } else {
                        File gccBin = new File(gccDir,"bin");
                        path.append(gccBin.getAbsolutePath());
                    }

                    properties.put("platform.compiler",gccPath.getAbsolutePath() + File.separator + "g++");
                }

                if(System.getenv().containsKey("PATH")) {
                    if(!path.toString().isEmpty()) {
                        path.append(File.pathSeparator);
                        path.append(System.getenv("PATH"));
                    } else {
                        path.append(System.getenv("PATH"));
                    }
                }

                if(System.getenv().containsKey("LD_LIBRARY_PATH")) {
                    if(!libraryPath.toString().isEmpty()) {
                        libraryPath.append(File.pathSeparator);
                        libraryPath.append(System.getenv("LD_LIBRARY_PATH"));
                    } else {
                        libraryPath.append(System.getenv("LD_LIBRARY_PATH"));
                    }
                }

                if(!path.toString().isEmpty()) {
                    System.out.println("Using custom PATH " + path);
                    invocationRequest.addShellEnvironment("PATH",path.toString());
                }

                if(!libraryPath.toString().isEmpty()) {
                    System.out.println("Using custom LD_LIBRARY_PATH " + libraryPath);
                    invocationRequest.addShellEnvironment("LD_LIBRARY_PATH",libraryPath.toString());
                }

                if(nd4jBackend != null && nd4jBackend.contains("native")
                        && libnd4jHelper == null
                        || !libnd4jHelper.contains("vednn") ||
                        libnd4jHelper.contains("onednn")) {
                    System.out.println("Setting cpu profile.");
                    invocationRequest.setProfiles(Arrays.asList("cpu"));
                }

                if(nd4jBackend != null && nd4jBackend.equals("nd4j-native") && libnd4jHelper != null && libnd4jHelper.contains("vednn")) {
                    System.out.println("Setting aurora profile.");
                    invocationRequest.setProfiles(Arrays.asList("aurora"));
                }

                if(nd4jBackend != null && nd4jBackend.contains("mini")) {
                    System.out.println("Setting minimizer profile.");
                    invocationRequest.setProfiles(Arrays.asList("minimial-cpu"));
                }


                if(nd4jBackend != null && nd4jBackend.contains("cuda")) {
                    System.out.println("Setting cuda profile.");
                    invocationRequest.setProfiles(Arrays.asList("cuda"));
                }

                invocationRequest.setBaseDirectory(dl4jLocation);
                invoker.setMavenHome(new File(mvnHome));
                if(dl4jModules != null && !dl4jModules.isEmpty()) {
                    invocationRequest.setProjects(dl4jModules);
                }

                invocationRequest.setShowErrors(true);
                invocationRequest.setErrorHandler(s -> System.out.println(s));
                invocationRequest.setDebug(true);
                invoker.setLogger(new SystemOutLogger());

                InvocationResult execute = invoker.execute(invocationRequest);
                if(execute != null && execute.getExitCode() != 0) {
                    if(execute.getExecutionException() != null) {
                        System.err.println("DL4J build failed. Reason below:");
                        execute.getExecutionException().printStackTrace();
                        return execute.getExitCode();
                    }
                    else {
                        System.err.println("No error output from maven. Please see above for error.");
                        return execute.getExitCode();
                    }
                }  else if(execute.getExitCode() == 0) {
                    //only a subset of projects are valid, ensure we only build what's necessary
                    //the build will fail with everything that isn't the backend and related artifacts.
                    if(libnd4jExtension != null && !libnd4jExtension.isEmpty()
                            || libnd4jHelper != null
                            && !libnd4jHelper.isEmpty()) {
                        System.out.println("Base build completed. Building for subset.");
                        System.out.println("Helper: " + libnd4jHelper  + " Extension: " + libnd4jExtension  + " specified. Only building subset of projects.");
                        invocationRequest.setProjects(Arrays.asList(
                                ":libnd4j",
                                ":" + nd4jBackend,
                                ":" + nd4jBackend + "-preset"
                        ));

                    }

                    invocationRequest.setAlsoMake(true);
                    execute = invoker.execute(invocationRequest);
                    if(execute != null && execute.getExitCode() != 0) {
                        if (execute.getExecutionException() != null) {
                            System.err.println("DL4J build failed. Reason below:");
                            execute.getExecutionException().printStackTrace();
                            return execute.getExitCode();
                        } else {
                            System.err.println("No error output from maven. Please see above for error.");
                            return execute.getExitCode();
                        }
                    }

                    System.err.println("Finished cloning and building Deeplearning4j.");
                }
                else if(execute.getExitCode() != 0) {
                    System.err.println("Failed to build Deeplearning4j. Exiting.");
                    return 1;
                }
            }
        }


        if(buildKonduitServing) {
            File konduitServingLocation = new File(konduitServingDirectory);
            if(konduitServingLocation.exists() && forceKonduitServingClone || konduitServingLocation.exists() && konduitServingLocation.listFiles() == null || konduitServingLocation.listFiles() != null && konduitServingLocation.listFiles().length < 1) {
                System.out.println("Forcing deletion of specified konduit serving location: " + konduitServingLocation);
                FileUtils.deleteDirectory(konduitServingLocation);
            }


            if(!konduitServingLocation.exists()) {
                System.out.println("Konduit Serving location not found. Cloning to " + konduitServingLocation.getAbsolutePath() + " from url " + konduitServingGitUrl + " from branch " + konduitServingBranchName);
                Git.cloneRepository()
                        .setURI(konduitServingGitUrl)
                        .setDirectory(konduitServingLocation)
                        .setBranch(konduitServingBranchName)
                        .setProgressMonitor(new TextProgressMonitor())
                        .call();
            }

            InvocationRequest invocationRequest = new DefaultInvocationRequest();
            invocationRequest.setPomFile(new File(konduitServingLocation,"pom.xml"));
            invocationRequest.setGoals(Arrays.asList(konduitServingBuildCommand.split(" ")));
            invocationRequest.setAlsoMake(true);
            invocationRequest.setShowErrors(true);
            invocationRequest.setErrorHandler(s -> System.out.println(s));
            invocationRequest.setDebug(true);

            if(konduitServingModule != null && !konduitServingModule.isEmpty()) {
                invocationRequest.setProjects(konduitServingModule);
            }
            Properties properties = new Properties();
            properties.put("chip",konduitServingChip);
            properties.put("javacpp.platform",platform);
            invoker.setLogger(new SystemOutLogger());

            invocationRequest.setBaseDirectory(konduitServingLocation);
            invoker.setMavenHome(new File(mvnHome));
            invocationRequest.setProperties(properties);
            InvocationResult execute = invoker.execute(invocationRequest);
            if(execute != null && execute.getExitCode() != 0) {
                System.err.println("Konduit Serving build failed. Reason below:");
                if(execute.getExecutionException() != null)
                    execute.getExecutionException().printStackTrace();
                else {
                    System.err.println("Unable to find reason for konduit serving build failing. Please see error message above if any. Exiting.");
                    return 1;
                }
            } else if(execute.getExitCode() == 0) {
                System.err.println("Finished building Konduit Serving.");
            } else {
                System.err.println("Failed to clone Konduit Serving. Exiting.");
                return 1;
            }

        }

        return 0;
    }

    public static void main(String...args) {
        new CommandLine(new CloneBuildComponents()).execute(args);
    }

}
