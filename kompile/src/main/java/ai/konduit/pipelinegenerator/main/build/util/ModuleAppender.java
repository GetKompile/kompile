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

package ai.konduit.pipelinegenerator.main.build.util;

import ai.konduit.pipelinegenerator.main.PipelineStepType;
import ai.konduit.serving.pipeline.api.pipeline.Pipeline;
import ai.konduit.serving.pipeline.api.step.PipelineStep;
import ai.konduit.serving.pipeline.impl.pipeline.GraphPipeline;
import ai.konduit.serving.pipeline.impl.pipeline.SequencePipeline;
import ai.konduit.serving.pipeline.impl.pipeline.graph.GraphStep;
import ai.konduit.serving.pipeline.util.ObjectMappers;
import ai.konduit.serving.vertx.config.InferenceConfiguration;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ModuleAppender {
    public static Set<String> getCommandsFromPipeline(Pipeline pipeline) throws java.io.IOException {
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

    private static void addCommand(Set<String> commandsToAdd, PipelineStep pipelineStep) {
        switch(PipelineStepType.typeForClazz(pipelineStep.getClass())) {
            case ND4JTENSORFLOW:
                commandsToAdd.add("nd4j-tensorflow");
                break;
            case GRAY_SCALE:
            case IMAGE_CROP:
            case IMAGE_RESIZE:
            case RELATIVE_TO_ABSOLUTE:
            case DRAW_POINTS:
            case DRAW_HEATMAP:
            case PERSPECTIVE_TRANSFORM:
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
            case DEEPLEARNING4J:
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
            case SAMEDIFF_TRAINING:
                commandsToAdd.add("samediff");
                break;
            case TENSORFLOW:
                commandsToAdd.add("tensorflow");
                break;
            case TVM:
                commandsToAdd.add("tvm");
                break;
            case DOCUMENTPARSER:
                commandsToAdd.add("doc");
                break;

        }
    }

    public static void main(String...args) throws Exception {
        File newFile = new File("pipeline-config.json");
        System.out.println(ModuleAppender.getCommandsFromPipeline(ObjectMappers.fromJson(FileUtils.readFileToString(newFile, Charset.defaultCharset()),Pipeline.class)));
    }

}
