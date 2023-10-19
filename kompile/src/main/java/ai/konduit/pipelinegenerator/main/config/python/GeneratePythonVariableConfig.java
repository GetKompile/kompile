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

import ai.konduit.serving.model.PythonIO;
import ai.konduit.serving.pipeline.api.data.ValueType;
import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "generate-python-variable-config",mixinStandardHelpOptions = false,description = "Generates a python variable configuration. Used in combination with python configuration to specify input and output types for variables. This should be output to a file to be used with pythonConfig.")
public class GeneratePythonVariableConfig implements Callable<Integer> {

    @CommandLine.Option(names = {"--variableName"},description = "The input variable names to generate the configuration for",required = true)
    private String variableName;
    @CommandLine.Option(names = {"--pythonType"},description = "The python type of the variable (like numpy.ndarray)",required = false)
    private String pythonType;
    @CommandLine.Option(names = {"--secondaryType"},description = "The secondary type of the variable. Typically used for containers like lists of ndarrays: " +     " Value types include:" +
            " NDARRAY,\n" +
            "    STRING,\n" +
            "    BYTES,\n" +
            "    IMAGE,\n" +
            "    DOUBLE,\n" +
            "    INT64,\n" +
            "    BOOLEAN,\n" +
            "    BOUNDING_BOX,\n" +
            "    DATA,\n" +
            "    LIST,\n" +
            "    POINT,\n" +
            "    BYTEBUFFER,\n" +
            "    NONE",required = false)
    private ValueType secondaryType;
    @CommandLine.Option(names = {"--valueType"},description = "The internal value types to generate the configuration for." +
            " Value types include:" +
            " NDARRAY,\n" +
            "    STRING,\n" +
            "    BYTES,\n" +
            "    IMAGE,\n" +
            "    DOUBLE,\n" +
            "    INT64,\n" +
            "    BOOLEAN,\n" +
            "    BOUNDING_BOX,\n" +
            "    DATA,\n" +
            "    LIST,\n" +
            "    POINT,\n" +
            "    BYTEBUFFER,\n" +
            "    NONE",required = false)
    private ValueType valueType;
    @Override
    public Integer call() throws Exception {
        PythonIO.PythonIOBuilder pythonIOBuilder = PythonIO.builder();
        if(variableName != null) {
            pythonIOBuilder.name(variableName);
        }

        if(pythonType != null) {
            pythonIOBuilder.pythonType(pythonType);
        }

        if(valueType != null) {
            pythonIOBuilder.type(valueType);
        } else if(valueType == null && pythonType != null) {
            ValueType valueType1 = valueTypeForPython(pythonType);
            if(valueType1 != null) {
                pythonIOBuilder.type(valueType1);
            } else {
                System.err.println("Please ensure a value type is specified. Unable to automatically infer from python type.");
                return 1;
            }
        }

        if(secondaryType != null) {
            pythonIOBuilder.secondaryType(secondaryType);
        }

        System.out.println(pythonIOBuilder.build().toJson());


        return 0;
    }

    private ValueType valueTypeForPython(String pythonType) {
        switch(pythonType) {
            case "int":
                return ValueType.INT64;
            case "float":
                return ValueType.DOUBLE;
            case "numpy.ndarray":
                return ValueType.NDARRAY;
            case "bool":
                return ValueType.BOOLEAN;
            case "bytes":
                return ValueType.BYTES;
            case "list":
                return ValueType.LIST;
            default:
                return null;
        }
    }

}
