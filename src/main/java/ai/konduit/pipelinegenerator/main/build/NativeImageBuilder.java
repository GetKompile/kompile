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

import ai.konduit.pipelinegenerator.main.util.EnvironmentUtils;
import org.apache.commons.io.IOUtils;
import org.apache.maven.model.Model;
import org.apache.maven.shared.invoker.*;
import org.codehaus.plexus.util.FileUtils;
import org.nd4j.common.base.Preconditions;
import org.nd4j.common.io.ClassPathResource;
import picocli.CommandLine;

import java.io.*;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "native-image-generate",mixinStandardHelpOptions = false,description = "Generate a native image using the given konduit serving pipeline steps available below. This will also generate a project to build the native image with. Note that the generate-image-and-sdk command also uses this command.")
public class NativeImageBuilder implements Callable<Void> {


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
    @CommandLine.Option(names = "--image",description = "Whether to use image pre processing or not or not")
    private boolean image = false;
    @CommandLine.Option(names = "--resourceBaseUrl",description = "Whether to use image pre processing or not or not")
    private String resourceBaseUrl = "https://raw.githubusercontent.com/KonduitAI/kompile-program-repository/main/";
    @CommandLine.Option(names = "--server",description = "Whether to use an http server or not")
    private boolean server = false;

    private Model model;
    @CommandLine.Option(names = {"--imageName"},description = "The image name")
    private String imageName = "konduit-serving";
    //What's the main class we want to run? A generic serving class we pre include?
    @CommandLine.Option(names = {"--mainClass"},description = "The main class for the image")
    private String mainClass;

    @CommandLine.Option(names = {"--mavenHome"},description = "The maven home.", required = true)
    private File mavenHome = EnvironmentUtils.defaultMavenHome();

    @CommandLine.Option(names = {"--outputFile"},description = "The output file")
    private File outputFile = new File("pom2.xml");

    @CommandLine.Option(names = {"--numpySharedLibrary"},description = "Create a library with a numpy based entry point.")
    private boolean numpySharedLibrary;

    @CommandLine.Option(names = {"--javacppPlatform"},description = "Build for a specific specified platform. An example would be linux-x86_64 - this reduces binary size and prevents out of memories from trying to include binaries for too many platforms.")
    private String javacppPlatform = "linux-x86_64";

    @CommandLine.Option(names = {"--javacppExtension"},description = "An optional javacpp extension such as avx2 or cuda depending on the target set of dependencies.")
    private String javacppExtension;

    @CommandLine.Option(names = "--pipelinePath",description = "The pipeline path for building the image")
    private String pipelinePath;


    @CommandLine.Option(names = "--nativeImageFilesPath",description = "The path to the files for building an image")
    private String nativeImageFilesPath;

    private void downloadResource(String fileName,File parentPath) throws IOException {
        try(InputStream is = URI.create(resourceBaseUrl + "/" + fileName  ).toURL().openStream();
            FileOutputStream fileOutputStream = new FileOutputStream(new File(parentPath,fileName))) {
            IOUtils.copy(is,fileOutputStream);
        }
    }
    public void runMain(String...args) throws Exception {
        InvocationRequest invocationRequest = new DefaultInvocationRequest();
        File project = new File(imageName);
        if(project.listFiles() != null) {
            System.err.println("Found non empty directory at " + project.getAbsolutePath() + " please specify an empty directory.");
            System.exit(1);
        }

        Preconditions.checkState(project.mkdirs(),"Unable to make directory " + project.getAbsolutePath());
        File srcDir = new File(project,"src/main/java");

        Preconditions.checkState(srcDir.mkdirs(),"Unable to make directory " + srcDir.getAbsolutePath());

        if (numpySharedLibrary) {
            File cEntryPointDir = new File(srcDir,"ai/konduit/pipelinegenerator/main");
            Preconditions.checkState(cEntryPointDir.mkdirs(),"Unable to make directory " + cEntryPointDir.getAbsolutePath());
            downloadResource("NumpyEntryPoint.java",cEntryPointDir);
        }

        if(server) {
            File cEntryPointDir = new File(srcDir,"ai/konduit/pipelinegenerator/main");
            if(!numpySharedLibrary)
                Preconditions.checkState(cEntryPointDir.mkdirs(),"Unable to make directory " + cEntryPointDir.getAbsolutePath());
            else {
                //don't try to throw an error if it's also  a shared library build.
                cEntryPointDir.mkdirs();
            }

            downloadResource("ServingMain.java",cEntryPointDir);
        }

        File resourcesDir = new File(project,"src/main/resources");
        Preconditions.checkState(resourcesDir.mkdirs(),"Unable to make directories " + resourcesDir.getAbsolutePath());
        if (numpySharedLibrary) {
            downloadResource("numpy_struct.h",resourcesDir);
        }

        FileUtils.copyFile(outputFile,new File(project,"pom.xml"));
        ClassPathResource nativeImage = new ClassPathResource("META-INF/native-image");
        Preconditions.checkState(nativeImage.exists(),"META-INF/native-image does not exist!");
        File nativeImageResourceDir = new File(resourcesDir,"META-INF/native-image");
        Preconditions.checkState(nativeImageResourceDir.mkdirs(),"Unable to create native image resources directory!");
        String[] resources = {"jni-config.json","proxy-config.json","reflect-config.json","resource-config.json","serialization-config.json"};
        if(nativeImageFilesPath != null) {
            File f = new File(nativeImageFilesPath);
            for(String resource : resources) {
                File src = new File(nativeImageFilesPath,resource);
                File dst = new File(nativeImageResourceDir,resource);
                if(!dst.exists())
                    dst.createNewFile();
                try(InputStream inputStream = new FileInputStream(src);
                    FileOutputStream fileOutputStream = new FileOutputStream(dst)) {
                    IOUtils.copy(inputStream,fileOutputStream);
                    fileOutputStream.flush();
                }

                System.out.println("Wrote resource " + resource + " to " + dst.getAbsolutePath());

            }
        }

        if(javacppPlatform != null && !javacppPlatform.isEmpty()) {
            List<String> goals = new ArrayList<>();
            goals.add("-Djavacpp.platform=" + javacppPlatform);
            if(javacppExtension != null && !javacppExtension.isEmpty())
                goals.add("-Djavacpp.platform.extension=" + javacppExtension);
            goals.add("-Dorg.eclipse.python4j.numpyimport=false");
            goals.add("clean");
            goals.add("package");
            invocationRequest.setGoals(goals);
        }
        else {
            invocationRequest.setGoals(Arrays.asList("clean","package"));

        }

        Invoker invoker = new DefaultInvoker();
        invocationRequest.setPomFile(new File(project,"pom.xml"));
        invoker.setWorkingDirectory(project);
        invocationRequest.setBaseDirectory(project);
        invoker.setMavenHome(mavenHome);
        System.out.println("Invoking maven with args " + invocationRequest.getArgs());
        invoker.execute(invocationRequest);
    }

    public static void main(String...args) {
        new CommandLine(new NativeImageBuilder()).execute(args);
    }


    @Override
    public Void call() throws Exception {
        runMain(new String[]{});
        return null;
    }
}
