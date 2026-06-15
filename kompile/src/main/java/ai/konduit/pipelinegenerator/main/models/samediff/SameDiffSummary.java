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

package ai.konduit.pipelinegenerator.main.models.samediff;

import ai.konduit.pipelinegenerator.main.converter.VariableTypeConverter;
import org.nd4j.autodiff.samediff.SDVariable;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.autodiff.samediff.VariableType;
import org.nd4j.autodiff.samediff.internal.SameDiffOp;
import picocli.CommandLine;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@CommandLine.Command(name = "summary",description = "Print summary of a target samediff model.")
public class SameDiffSummary implements Callable<Integer> {
    @CommandLine.Option(names = {"--modelInputPath"},description = "Input path to model.",required = true)
    private String modelInputPath;

    @CommandLine.Option(names = {"--printOpSummary"},description = "Print op summary including name, inputs, outputs, op types,..",required = false)
    private boolean printOpSummary;
    @CommandLine.Option(names = {"--opTypeToPrint"},description = "Print ops only of a certain type",required = false)
    private String[] opTypes;

    @CommandLine.Option(names = {"--printVariableSummary"},description = "Print summary of variables including their types, shapes, name",required = false)
    private boolean printVariableSummary;
    @CommandLine.Option(names = {"--printFullSummary"},description = "Print full summary.",required = false)
    private boolean printFullSummary;
    @CommandLine.Option(names = {"--printTrainingConfig"},description = "Print training config only.",required = false)
    private boolean printTrainingConfig;
    @CommandLine.Option(names = {"--printLossVariables"},description = "Print loss variables only.",required = false)
    private boolean printLossVariables;
    @CommandLine.Option(names = {"--variableTypeToPrint"},description = "Print only certain variable types. Valid types are:" +
            "variable, placeholder,constant,array,sequence ",required = false,converter = VariableTypeConverter.class)
    private VariableType[] typesToPrint;
    @CommandLine.Option(names = {"--printOpTypes"},description = "Print list of op types in graph.",required = false)
    private boolean printOpTypes;


    public SameDiffSummary() {
    }

    @Override
    public Integer call() throws Exception {
        File modelFile = new File(modelInputPath);
        if(!modelFile.exists()) {
            System.err.println("No model file found at path " + modelInputPath + " exiting.");
            return 1;
        }

        SameDiff sameDiff = SameDiff.load(new File(modelInputPath),false);
        if(printVariableSummary) {
            StringBuilder variablePrint = new StringBuilder();
            variablePrint.append("Variable name,Variable Type,Shape,Data type\n");
            if(this.typesToPrint != null) {
                List<VariableType> variableTypeList = Arrays.asList(typesToPrint);
                sameDiff.variables().stream().
                        filter(input -> variableTypeList.contains(input.getVariableType())).
                        forEach(variable -> appendVariableToStringBuilder(variablePrint,variable));
            } else {
                sameDiff.variables().stream().
                        forEach(variable -> appendVariableToStringBuilder(variablePrint,variable));
            }


            System.out.println(variablePrint);
        }

        if(printOpSummary) {
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("Name, Op name, Op type, Op inputs, Op outputs\n");
            if(opTypes != null) {
                List<String> opTypes = Arrays.asList(this.opTypes);
                sameDiff.getOps().values().stream()
                        .filter(input -> opTypes.contains(input.getOp().opName()))
                        .forEach(input ->
                                appendOpToStringBuilder(stringBuilder,input));

            } else {
                sameDiff.getOps().values().stream()
                        .forEach(input ->
                                appendOpToStringBuilder(stringBuilder,input));
            }

            System.out.println(stringBuilder);

        }
        if(printFullSummary)
            System.out.println(sameDiff.summary());

        if(printLossVariables) {
            System.out.println(sameDiff.getLossVariables());
        }

        if(printOpTypes) {
            System.out.println(sameDiff.getOps().values()
                    .stream().map(input -> input.getOp().opName())
                    .collect(Collectors.toSet()));
        }

        if(printTrainingConfig) {
            if(sameDiff.getTrainingConfig() == null) {
                System.err.println("No training configuration found. Exiting.");
                return 1;
            }

            System.out.println(sameDiff.getTrainingConfig().toJson());
        }

        return 0;
    }

    private void appendOpToStringBuilder(StringBuilder stringBuilder, SameDiffOp sameDiffOp) {
        stringBuilder.append(sameDiffOp.getName());
        stringBuilder.append(",");
        stringBuilder.append(sameDiffOp.getOp().getOwnName());
        stringBuilder.append(",");
        stringBuilder.append(sameDiffOp.getOp().opName());
        stringBuilder.append(",");
        stringBuilder.append(sameDiffOp.getInputsToOp());
        stringBuilder.append(",");
        stringBuilder.append(sameDiffOp.getOutputsOfOp());
        stringBuilder.append("\n");
    }

    private void appendVariableToStringBuilder(StringBuilder stringBuilder, SDVariable variable) {
        stringBuilder.append(variable.name());
        stringBuilder.append(",");
        stringBuilder.append(variable.getVariableType());
        stringBuilder.append(",");
        stringBuilder.append(Arrays.toString(variable.getShape()));
        stringBuilder.append(",");
        stringBuilder.append(variable.dataType());
        stringBuilder.append("\n");
    }

}
