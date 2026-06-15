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

package ai.konduit.pipelinegenerator.main.helpers;

import ai.konduit.serving.model.PythonConfig;
import ai.konduit.serving.model.PythonIO;
import ai.konduit.serving.pipeline.api.data.ValueType;
import ai.konduit.serving.pipeline.impl.pipeline.SequencePipeline;
import ai.konduit.serving.python.PythonStep;
import picocli.CommandLine;

import java.util.List;
import java.util.concurrent.Callable;
@CommandLine.Command(name = "ndarray-helper", mixinStandardHelpOptions = false)
public class NDArrayPipelineHelper implements Callable<Integer> {
    @CommandLine.Option(names = {"--inputName"},description = "The input name(s) for the pipeline",required = true)
    private List<String> inputNames;
    @CommandLine.Option(names = {"--outputName"},description = "The output name(s) for the pipeline",required = false)
    private List<String> outputNames;
    @CommandLine.Option(names = {"--outputFormat"},description = "json or yaml for output, defaults to json",required = false)
    private String format = "json";
    @CommandLine.Option(names = {"--pythonPathResolution"},description = "how to resolve the python path",required = false)
    private String pythonPathResolution;
    @CommandLine.Option(names = {"--returnAllInputs"},description = "Whether to return all outputs from the python execution",required = false)
    private boolean returnAllInputs;
    @CommandLine.Option(names = {"--setupAndRun"},description = "Whether to use setup and run definition (2 separate functions for init and execution) or single script",required = false)
    private boolean setupAndRun;
    @CommandLine.Option(names = {"--generatePipeline"},description = "Whether to generate a pipeline or just a python configuration",required = false)
    private boolean generatePipeline;

    @Override
    public Integer call() throws Exception {
        StringBuilder stringBuilder = new StringBuilder();
        PythonConfig.PythonConfigBuilder pythonConfig = PythonConfig.builder();
        for(int i = 0; i < inputNames.size(); i++) {
            pythonConfig.ioInput(inputNames.get(i), PythonIO.builder()
                    .pythonType("numpy.ndarray").type(ValueType.NDARRAY)
                    .name(inputNames.get(i)).build());
        }

        for(int i = 0; i < outputNames.size(); i++) {
            pythonConfig.ioOutput(outputNames.get(i), PythonIO.builder()
                    .pythonType("numpy.ndarray").type(ValueType.NDARRAY)
                    .name(outputNames.get(i)).build());
        }


        PythonStep pythonStep = PythonStep.builder()
                .pythonConfig(pythonConfig.build())
                .build();

        SequencePipeline pipeline = SequencePipeline.builder()
                .add(pythonStep)
                .build();

        if(format == null || format.equals("json"))
            stringBuilder.append(pythonConfig.build().toJson());
        else {
            stringBuilder.append(pythonConfig.build().toYaml());
        }

        System.out.println(stringBuilder);

        return null;
    }
}
