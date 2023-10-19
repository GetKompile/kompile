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

import ai.konduit.pipelinegenerator.main.build.util.ModuleAppender;
import ai.konduit.serving.pipeline.api.pipeline.Pipeline;
import ai.konduit.serving.pipeline.util.ObjectMappers;
import ai.konduit.serving.vertx.config.InferenceConfiguration;
import org.nd4j.shade.jackson.databind.ObjectMapper;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.Callable;
@CommandLine.Command(name = "pipeline-command-generate",mixinStandardHelpOptions = false)
public class PipelineCommandGenerator implements Callable<Void> {

    @CommandLine.Option(names = {"--pipelineFile"},description = "The pipeline file to analyze",required = false)
    private File pipelineFile;
    @CommandLine.Option(names = {"--protocol"},description = "The protocol to use for serving")
    private String protocol = "http";

    @CommandLine.Option(names = {"--imageName"},description = "The image name")
    private String imageName = "konduit-serving";
    //What's the main class we want to run? A generic serving class we pre include?
    @CommandLine.Option(names = {"--mainClass"},description = "The main class for the image")
    private String mainClass;

    @CommandLine.Option(names = {"--nd4jBackend"},description = "The nd4j backend to include")
    private String nd4jBackend = "nd4j-native";

    @CommandLine.Option(names = {"--nd4jBackendClassifier"},description = "The nd4j backend to include")
    private String nd4jBackendClassifier = "";

    @CommandLine.Option(names = {"--extraDependencies"},description = "Extra dependencies to include in the form of: groupId:artifactId,version:classifier with a comma separating each dependency")
    private String extraDependencies;
    @CommandLine.Option(names = {"--includeResources"},description = "Extra resources to include in the image, comma separated")
    private String includeResources;
    @CommandLine.Option(names = {"--server"},description = "Whether the file is an inference  server configuration or a pipeline.")
    private boolean isServer = false;
    @CommandLine.Option(names = {"--assembly"},description = "Whether to build a maven assembly of all jars")
    private boolean assembly;
    @CommandLine.Option(names = {"--nativeImageJvmArg"},description = "Extra JVM arguments for the native image build process. These will be" +
            "passed to the native image plugin in the form of: -JSOMEARG")
    private String[] nativeImageJvmArgs;
    @CommandLine.Option(names = {"--numpySharedLibrary"},description = "Whether to build a numpy based shared library for the native image.")
    private boolean numpySharedLibrary = false;
    @CommandLine.Option(names = {"--outputFile"},description = "The output file")
    private File outputFile = new File("pom2.xml");

    private ObjectMapper jsonMapper = ObjectMappers.json();
    private ObjectMapper yamlMapper = ObjectMappers.yaml();


    @Override
    public Void call() throws Exception {
        StringBuilder command = new StringBuilder();
        command.append(" build pom-generate ");
        if(protocol != null && !protocol.isEmpty()) {
            command.append(" --server=true ");
        }

        if(mainClass != null && !mainClass.isEmpty()) {
            command.append(" --mainClass=" + mainClass + " ");
        }

        if(imageName != null && !imageName.isEmpty()) {
            command.append(" --imageName=" + imageName + " ");
        }

        if(extraDependencies != null && !extraDependencies.isEmpty()) {
            command.append(" --extraDependencies=" + extraDependencies + " ");
        }

        if(includeResources != null && !includeResources.isEmpty()) {
            command.append(" --includeResources=" + includeResources + " ");
        }

        command.append(" --numpySharedLibrary=" + numpySharedLibrary);

        if(outputFile != null) {
            command.append(" --outputFile=" + outputFile.getAbsolutePath() + " ");
        }

        if(nd4jBackend != null && !nd4jBackend.isEmpty()) {
            command.append(" --nd4jBackend=" + nd4jBackend + " ");
        }

        if(nd4jBackendClassifier != null && !nd4jBackendClassifier.isEmpty()) {
            command.append(" --nd4jBackendClassifier=" + nd4jBackendClassifier + " ");
        }

        if(nativeImageJvmArgs != null) {
            for(String jvmArg : nativeImageJvmArgs) {
                command.append("--nativeImageJvmArg=" + jvmArg + " ");
            }
        }

        command.append("--assembly=" + assembly + " ");

        if(!assembly) {
            Pipeline pipeline = null;
            if(isServer) {
                if(pipelineFile.getName().endsWith("json")) {
                    InferenceConfiguration inferenceConfiguration = jsonMapper.readValue(pipelineFile,InferenceConfiguration.class);
                    pipeline = inferenceConfiguration.pipeline();
                } else if(pipelineFile.getName().endsWith("yaml") || pipelineFile.getName().endsWith("yml")) {
                    InferenceConfiguration inferenceConfiguration = yamlMapper.readValue(pipelineFile,InferenceConfiguration.class);
                    pipeline = inferenceConfiguration.pipeline();

                }
            } else {
                if(pipelineFile.getName().endsWith("json")) {
                    pipeline = jsonMapper.readValue(pipelineFile,Pipeline.class);
                } else if(pipelineFile.getName().endsWith("yaml") || pipelineFile.getName().endsWith("yml")) {
                    pipeline = yamlMapper.readValue(pipelineFile,Pipeline.class);
                }
            }

            if(pipeline.size() < 1) {
                throw new IllegalStateException("Specified pipeline file invalid. Please ensure that a server is specified. This can be generated with ./kompile exec inference-server-create --pipelineFile=./yourPipelinefile.json --protocol=$YOUR_PROTOCOL --port=$YOUR_PORT");
            }

            appendCommands(command, pipeline);

        }

        System.out.println(command);

        return null;
    }

    private void appendCommands(StringBuilder command, Pipeline pipeline) throws IOException {
        Set<String> commandsToAdd = ModuleAppender.getCommandsFromPipeline(pipeline);

        for(String command2 : commandsToAdd) {
            command.append("--" + command2 + "=true ");
        }
    }

    public static void main(String...args) {
        CommandLine commandLine = new CommandLine(new PipelineCommandGenerator());
        System.exit(commandLine.execute(args));
    }

}
