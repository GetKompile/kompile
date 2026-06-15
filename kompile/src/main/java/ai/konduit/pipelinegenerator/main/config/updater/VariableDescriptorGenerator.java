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

package ai.konduit.pipelinegenerator.main.config.updater;

import ai.konduit.serving.pipeline.util.ObjectMappers;
import org.nd4j.autodiff.samediff.VariableType;
import org.nd4j.linalg.api.buffer.DataType;
import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "generate-variable-descriptor",mixinStandardHelpOptions = false,description = "Generates a variable configuration to be used with samediff_training. It is common to need to add/remove variables from a graph when importing. This descriptor covers adding/removing variables from a model after conversion using the ./kompile model convert command.")
public class VariableDescriptorGenerator implements Callable<Integer> {

    @CommandLine.Option(names = {"--varName"},description = "Name of the variable for the descriptor",required = true)
    private String varName;
    @CommandLine.Option(names = {"--variableType"},description = "The type of the variable. Valid values: VARIABLE,\n" +
            "    VARIABLE,\n" +
            "    VARIABLE,\n",required = true)
    private VariableType variableType;
    @CommandLine.Option(names = {"--shape"},description = "Shape of the variable for the descriptor. Specify each dimension as a separate argument with --shape=1 --shape=2",required = false)
    private long[] shape;
    @CommandLine.Option(names = {"--dataType"},description = "Data type of the variable for the descriptor",required = false)
    private DataType dataType;

    public VariableDescriptorGenerator() {
    }

    @Override
    public Integer call() throws Exception {
        VariableDescriptor variableDescriptor = VariableDescriptor.builder()
                .dataType(dataType)
                .shape(shape)
                .variableType(variableType)
                .varName(varName)
                .build();
        System.out.println(ObjectMappers.toJson(variableDescriptor));
        return 0;
    }
}
