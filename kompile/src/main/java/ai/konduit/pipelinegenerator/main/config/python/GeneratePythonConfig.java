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

package ai.konduit.pipelinegenerator.main.config.python;

import ai.konduit.serving.model.PythonConfig;
import ai.konduit.serving.model.PythonIO;
import ai.konduit.serving.pipeline.api.data.ValueType;
import ai.konduit.serving.pipeline.api.python.models.PythonConfigType;
import ai.konduit.serving.pipeline.util.ObjectMappers;
import org.apache.commons.io.FileUtils;
import picocli.CommandLine;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@CommandLine.Command(name = "generate-python-config",mixinStandardHelpOptions = false,description = "Generates a python configuration for use with python execution. A user should also specify a set of python variables for execution." +
        " Inputs and outputs are required. Users can also specify other parameters such as a python path to " +
        "use for execution.")
public class GeneratePythonConfig implements Callable<Integer> {

    @CommandLine.Option(names = {"--pythonPath"},description = "The python path to use",required = false)
    private String pythonPath;
    @CommandLine.Option(names = {"--pythonConfigType"},description = "The python path to use",required = false)
    private String pythonConfigType;
    @CommandLine.Option(names = {"--pythonCode"},description = "The python code to run. ",required = false)
    private String pythonCode;
    @CommandLine.Option(names = {"--pythonCodePath"},description = "The path to a python file to run. Should not overlap with pythonCode..",required = false)
    private String pythonCodePath;
    @CommandLine.Option(names = {"--returnAllInputs"},description = "Whether to return all outputs of the specified python script. If set to true, a user does not need to specify output variables.",required = false)
    private boolean returnAllInputs;
    @CommandLine.Option(names = {"--setupAndRun"},description = "Whether a setup() function and run() function are specified",required = false)
    private boolean setupAndRun;

    @CommandLine.Option(names = {"--inputVariable"},description = "The input variables to use",required = false)
    private List<String> inputVariables;

    @CommandLine.Option(names = {"--outputVariable"},description = "The output variables to return from the result",required = false)
    private List<String> outputVariables;

    public GeneratePythonConfig() {
    }


    @Override
    public Integer call() throws Exception {
        List<ValueType> types = Arrays.asList(ValueType.values());

        PythonConfig.PythonConfigBuilder pythonConfig = PythonConfig.builder();

        if(pythonCode != null) {
            pythonConfig.pythonCode(pythonCode);
        }
        if(pythonCodePath != null) {
            pythonConfig.pythonCodePath(pythonCodePath);

        }
        if(pythonConfigType != null) {
            pythonConfig.pythonConfigType(PythonConfigType.valueOf(pythonConfigType.toUpperCase(Locale.ROOT)));

        }


        if(pythonPath != null) {
            pythonConfig.pythonPath(pythonPath);
            pythonConfig.pythonLibrariesPath(pythonPath);
        }


        pythonConfig.returnAllInputs(returnAllInputs);
        pythonConfig.setupAndRun(setupAndRun);

        if(inputVariables != null) {
            for(int i = 0; i < inputVariables.size(); i++) {
                String input = inputVariables.get(i);
                String json = FileUtils.readFileToString(new File(input));
                PythonIO pythonIO = ObjectMappers.fromJson(json,PythonIO.class);
                pythonConfig.ioInput(pythonIO.name(),pythonIO);
            }
        }


        if(outputVariables != null) {
            for(int i = 0; i < outputVariables.size(); i++) {
                String output = outputVariables.get(i);
                String json = FileUtils.readFileToString(new File(output));
                PythonIO pythonIO = ObjectMappers.fromJson(json,PythonIO.class);
                pythonConfig.ioOutput(pythonIO.name(),pythonIO);
            }
        }

        System.out.println(pythonConfig.build().toJson());

        return 0;
    }


}
