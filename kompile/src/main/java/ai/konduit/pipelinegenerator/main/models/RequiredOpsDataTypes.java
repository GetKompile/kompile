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

package ai.konduit.pipelinegenerator.main.models;

import org.nd4j.autodiff.samediff.SameDiff;
import picocli.CommandLine;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@CommandLine.Command(name = "graph-ops-datatypes",description = "Print required ops and data types for a given graph given a samediff model. Meant for use as input in to generate-image-and-sdk and generate-serving-binary" +
        " for specifying the required nd4j data types and operations for a given graph. Outputs datatypes separated by ; as expected in cmake lists which are used in" +
        "compiling the c++ base.")
public class RequiredOpsDataTypes implements Callable<Integer> {

    @CommandLine.Option(names = {"--modelInputPath"},description = "Input path to model to analyze.",required = true)
    private String modelInputPath;

    @CommandLine.Option(names = {"--printOps"},description = "Whether to print required ops or not.",required = false)
    private boolean printOps = false;
    @CommandLine.Option(names = {"--printDataTypes"},description = "Whether to print required data types or not.",required = false)
    private boolean printDataTypes = false;

    public RequiredOpsDataTypes() {
    }

    @Override
    public Integer call() throws Exception {
        if(!printDataTypes && !printOps) {
            System.err.println("Printing data types and ops are both disabled. Exiting.");
            return 1;
        }

        SameDiff sameDiff = SameDiff.load(new File(modelInputPath),true);
        StringBuilder stringBuilder = new StringBuilder();

        if(printOps) {
            Set<String> ops = new HashSet<>();
            ops.addAll(Arrays.stream(sameDiff.ops()).map(input -> input.getOwnName()).collect(Collectors.toSet()));

            if(sameDiff.definedFunctionNames() != null && !sameDiff.definedFunctionNames().isEmpty()) {
                for(String functionName : sameDiff.definedFunctionNames()) {
                    SameDiff func = sameDiff.getFunction(functionName);
                    ops.addAll(Arrays.stream(func.ops()).map(input -> input.getOwnName())
                            .collect(Collectors.toSet()));
                }
            }

            ops.forEach(input -> {
                stringBuilder.append(input);
                stringBuilder.append(";");
            });
        }

        if(printDataTypes) {
            if(printOps) {
                //print spaces if we are also printing data types to separate the 2 lists
                stringBuilder.append(" ");
            }

            Set<String> dataTypes = new HashSet<>();

            dataTypes.addAll(sameDiff.variables().stream().
                    filter(input -> input.dataType() != null)
                    .map(input -> input.dataType().name())
                    .collect(Collectors.toSet()));


            if(sameDiff.definedFunctionNames() != null && !sameDiff.definedFunctionNames().isEmpty()) {
                for(String functionName : sameDiff.definedFunctionNames()) {
                    SameDiff func = sameDiff.getFunction(functionName);
                    dataTypes.addAll(func.variables().stream().
                            filter(input -> input.dataType() != null)
                            .map(input -> input.dataType().name())
                            .collect(Collectors.toSet()));
                }
            }

            dataTypes.forEach(input -> {
                stringBuilder.append(input.toLowerCase());
                stringBuilder.append(";");
            });
        }


        System.out.println(stringBuilder);

        return 0;
    }
}
