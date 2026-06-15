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

package ai.konduit.pipelinegenerator.main.exec;

import ai.konduit.serving.pipeline.api.step.PipelineStep;
import ai.konduit.serving.pipeline.impl.pipeline.SequencePipeline;
import ai.konduit.serving.pipeline.util.ObjectMappers;
import org.apache.commons.io.FileUtils;
import org.nd4j.shade.jackson.databind.ObjectMapper;
import picocli.CommandLine;

import java.io.File;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.Callable;
@CommandLine.Command(name = "sequence-pipeline-creator",mixinStandardHelpOptions = false)
public class SequencePipelineCombiner implements Callable<Void> {
    @CommandLine.Option(names = {"--pipeline"},description = "Pipeline String",required = true)
    private List<File> pipelineStep;
    @CommandLine.Option(names = {"--file-format"},description = "Pipeline String")
    private String format = "json";



    private ObjectMapper jsonMapper = ObjectMappers.json();
    private ObjectMapper yamlMapper = ObjectMappers.yaml();

    @Override
    public Void call() throws Exception {
        SequencePipeline.Builder pipelineBuilder = SequencePipeline.builder();
        for(File f : pipelineStep) {
            if(format.equals("json")) {
                PipelineStep pipelineStep = jsonMapper.readValue(f, PipelineStep.class);
                pipelineBuilder.add(pipelineStep);
            } else if(format.equals("yml") || format.equals("yaml")) {
                PipelineStep pipelineStep = yamlMapper.readValue(f,PipelineStep.class);
                pipelineBuilder.add(pipelineStep);
            }
        }
        if(format.equals("json")) {
            System.out.println(pipelineBuilder.build().toJson());
        } else if(format.equals("yml") || format.equals("yaml")) {
            System.out.println(pipelineBuilder.build().toYaml());
        } else {
            System.err.println("Invalid format: please specify json,yml,yaml");
        }

        return null;
    }


    public static void main(String...args) throws Exception  {
        StepCreator stepCreator = new StepCreator();
        CommandLine.Model.CommandSpec spec = stepCreator.spec();
        CommandLine commandLine = new CommandLine(spec);
        // CommandLine.ParseResult crop_grid = commandLine.parseArgs("image_to_ndarray", "--config=height=250,width=250,outputNames=image,dataType=double,normalization=scale");
        CommandLine.ParseResult onnx = commandLine.parseArgs("onnx","--inputNames=1","--inputNames=2","--outputNames=1","--outputNames=2","--modelUri=add.onnx");
        PipelineStep stepFromResult = stepCreator.createStepFromResult(onnx);
        FileUtils.write(new File("onnx-1.json"),stepFromResult.toJson(), Charset.defaultCharset().name());
        CommandLine.ParseResult onnx2 = commandLine.parseArgs("onnx","--inputNames=1","--inputNames=2","--outputNames=1","--outputNames=2","--modelUri=add.onnx");
        PipelineStep stepFromResult2 = stepCreator.createStepFromResult(onnx2);
        FileUtils.write(new File("onnx-2.json"),stepFromResult2.toJson(), Charset.defaultCharset().name());
        CommandLine combineCommand = new CommandLine(new SequencePipelineCombiner());
        combineCommand.execute("--pipeline","onnx-1.json","--pipeline","onnx-2.json");


    }

}
