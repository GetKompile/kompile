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

import ai.konduit.pipelinegenerator.main.PipelineStepType;
import ai.konduit.serving.pipeline.api.pipeline.Pipeline;
import ai.konduit.serving.pipeline.api.step.PipelineStep;
import ai.konduit.serving.pipeline.impl.pipeline.GraphPipeline;
import ai.konduit.serving.pipeline.impl.pipeline.SequencePipeline;
import ai.konduit.serving.pipeline.impl.pipeline.graph.GraphStep;
import ai.konduit.serving.pipeline.util.ObjectMappers;
import ai.konduit.serving.vertx.config.InferenceConfiguration;
import org.nd4j.shade.jackson.databind.ObjectMapper;
import picocli.CommandLine;

import java.io.File;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
@CommandLine.Command(name = "pipeline-command-generate",mixinStandardHelpOptions = false)
public class PipelineCommandGenerator implements Callable<Void> {

    @CommandLine.Option(names = {"--pipelineFile"},description = "The pipeline file to analyze",required = true)
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

        if(nd4jBackend != null) {
            command.append(" --nd4jBackend=" + nd4jBackend + " ");
        }

        if(nd4jBackendClassifier != null) {
            command.append(" --nd4jBackendClassifier=" + nd4jBackendClassifier + " ");
        }

        Pipeline pipeline = null;
        if(isServer) {
            if(pipelineFile.getName().equals("json")) {
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

        Set<String> commandsToAdd = getCommandsFromPipeline(pipeline);

        for(String command2 : commandsToAdd) {
            command.append("--" + command2 + " ");
        }

        System.out.println(command);

        return null;
    }

    private Set<String> getCommandsFromPipeline(Pipeline pipeline) throws java.io.IOException {


        Set<String> commandsToAdd = new HashSet<>();
        if(pipeline instanceof SequencePipeline) {
            SequencePipeline sequencePipeline = (SequencePipeline)  pipeline;
            for(PipelineStep pipelineStep : sequencePipeline.steps()) {
                addCommand(commandsToAdd, pipelineStep);
            }
        } else if(pipeline instanceof GraphPipeline) {
            GraphPipeline graphPipeline = (GraphPipeline) pipeline;
            for(Map.Entry<String, GraphStep> graphStepEntry : graphPipeline.steps().entrySet()) {
                if(graphStepEntry.getValue().hasStep()) {
                    addCommand(commandsToAdd,graphStepEntry.getValue().getStep());
                }
            }
        }
        return commandsToAdd;
    }

    private void addCommand(Set<String> commandsToAdd, PipelineStep pipelineStep) {
        switch(PipelineStepType.typeForClazz(pipelineStep.getClass())) {
            case ND4JTENSORFLOW:
                commandsToAdd.add("nd4j-tensorflow");
                break;
            case DRAW_BOUNDING_BOX:
            case DRAW_SEGMENTATION:
            case SSD_TO_BOUNDING_BOX:
            case VIDEO_FRAME_CAPTURE:
            case CAMERA_FRAME_CAPTURE:
            case EXTRACT_BOUNDING_BOX:
            case CROP_GRID:
            case DRAW_GRID:
            case IMAGE_TO_NDARRAY:
            case SHOW_IMAGE:
            case DRAW_FIXED_GRID:
            case CROP_FIXED_GRID:
                commandsToAdd.add("image");
                break;
            case ONNX:
                commandsToAdd.add("onnx");
                break;
            case DL4J:
            case KERAS:
                commandsToAdd.add("dl4j");
                break;
            case PYTHON:
                commandsToAdd.add("python");
                break;
            case CLASSIFIER_OUTPUT:
            case LOGGING:
                //already present by default in konduit-serving-pipeline as a transitive dependency
                break;
            case SAMEDIFF:
                commandsToAdd.add("samediff");
                break;
            case TENSORFLOW:
                commandsToAdd.add("tensorflow");
                break;

        }
    }
}
