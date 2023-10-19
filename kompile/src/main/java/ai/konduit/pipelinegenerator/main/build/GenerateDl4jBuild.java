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
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.Invoker;
import org.codehaus.plexus.util.FileUtils;
import org.nd4j.common.base.Preconditions;
import picocli.CommandLine;

import java.io.File;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "dl4j-build-generate",mixinStandardHelpOptions = false,description = "Generate a dl4j build output as a tar file containing an nd4j backend and related dependencies.")
public class GenerateDl4jBuild implements Callable<Void> {

    @CommandLine.Option(names = {"--outputDirectory"},description = "The maven home.", required = false)
    private String outputDirectory = "dl4j-build";

    @CommandLine.Option(names = {"--pomFile"},description = "The maven home.", required = false)
    private String pomFile = "pom2.xml";

    @CommandLine.Option(names = {"--mavenHome"},description = "The maven home.", required = true)
    private File mavenHome = EnvironmentUtils.defaultMavenHome();


    @CommandLine.Option(names = {"--javacppPlatform"},description = "Build for a specific specified platform. An example would be linux-x86_64 - this reduces binary size and prevents out of memories from trying to include binaries for too many platforms.")
    private String javacppPlatform = "linux-x86_64";

    @CommandLine.Option(names = {"--javacppExtension"},description = "An optional javacpp extension such as avx2 or cuda depending on the target set of dependencies.")
    private String javacppExtension;



    public void runMain(String...args) throws Exception {
        InvocationRequest invocationRequest = new DefaultInvocationRequest();
        File project = new File(outputDirectory);
        if(project.listFiles() != null) {
            System.err.println("Found non empty directory at " + project.getAbsolutePath() + " please specify an empty directory.");
            System.exit(1);
        }

        Preconditions.checkState(project.mkdirs(),"Unable to make directory " + project.getAbsolutePath());
        File targetPomToCopy = new File(pomFile);
        FileUtils.copyFile(targetPomToCopy,new File(project,"pom.xml"));

        File resourcesDir = new File(project,"src/assembly/");
        Preconditions.checkState(resourcesDir.mkdirs(),"Unable to make directories " + resourcesDir.getAbsolutePath());
        StringBuilder config = new StringBuilder();
        config.append("<assembly xmlns=\"http://maven.apache.org/ASSEMBLY/2.1.1\"\n" +
                "  xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                "  xsi:schemaLocation=\"http://maven.apache.org/ASSEMBLY/2.1.1 https://maven.apache.org/xsd/assembly-2.1.1.xsd\">\n" +
                "  <id>bin</id>\n" +
                "  <formats>\n" +
                "    <format>tar.gz</format>\n" +
                "    <format>tar.bz2</format>\n" +
                "    <format>zip</format>\n" +
                "  </formats>  " +
                "<dependencySets>" +
                "<dependencySet>       " +
                "    <outputDirectory>lib/</outputDirectory>\n" +
                "            <includes>\n" +
                "              <include>org.nd4j:*:*:*</include>\n" +
                "           </includes>\n" +
                "        </dependencySet>\n" +
                "</dependencySets>\n" +
                "</assembly>\n");
        File assemblyXml = new File(resourcesDir,"kompile.xml");
        org.apache.commons.io.FileUtils.write(assemblyXml,config.toString(), Charset.defaultCharset());
        if(javacppPlatform != null && !javacppPlatform.isEmpty()) {
            List<String> goals = new ArrayList<>();
            goals.add("-Djavacpp.platform=" + javacppPlatform);
            goals.add("-Dlibnd4j.platform=" + javacppPlatform);
            if(javacppExtension != null && !javacppExtension.isEmpty())
                goals.add("-Djavacpp.platform.extension=" + javacppExtension);
            goals.add("assembly:single");
            invocationRequest.setGoals(goals);
        }
        else {
            invocationRequest.setGoals(Arrays.asList("clean","assembly:single"));

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
        new CommandLine(new GenerateDl4jBuild()).execute(args);
    }


    @Override
    public Void call() throws Exception {
        runMain(new String[]{});
        return null;
    }
}
