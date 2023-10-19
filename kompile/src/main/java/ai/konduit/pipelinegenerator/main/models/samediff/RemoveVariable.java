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

import org.nd4j.autodiff.functions.DifferentialFunction;
import org.nd4j.autodiff.samediff.SameDiff;
import picocli.CommandLine;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "remove-variable",description = "Remove variable from a model.")
public class RemoveVariable implements Callable<Integer> {

    @CommandLine.Option(names = {"--modelInputPath"},description = "Input path to model.",required = true)
    private File modelInputPath;
    @CommandLine.Option(names = {"--newModelOutputPath"},description = "Output path to new.",required = false)
    private File newModelOutputPath;

    @CommandLine.Option(names = {"--variableToRemove"},description = "Name of variable to rename",required = true)
    private String variableToRemove;



    public RemoveVariable() {
    }

    @Override
    public Integer call() throws Exception {
        SameDiff sameDiff = SameDiff.load(modelInputPath,true);
        if(!sameDiff.hasVariable(variableToRemove)) {
            System.err.println("Variable " + variableToRemove + " not found. Exiting.");
            return 1;
        }

        sameDiff.getVariables().remove(variableToRemove);

        DifferentialFunction variableOutputOp = sameDiff.getVariableOutputOp(variableToRemove);
        if(variableOutputOp != null) {
            sameDiff.getOps().remove(variableOutputOp.getOwnName());
            System.out.println("Removed associated op " + variableOutputOp);
        }

        Set<String> opArgs = new HashSet<>();
        Set<String> variablesToRemove = new HashSet<>();
        sameDiff.getOps().values().stream().filter(input -> input.getInputsToOp().contains(variableToRemove))
                .forEach(opToRemove -> {
                    opToRemove.getOutputsOfOp().forEach(input -> variablesToRemove.add(input));
                    opArgs.add(opToRemove.getName());
                });


        if(!opArgs.isEmpty()) {
            System.err.println("Removing ops with variable " + variableToRemove + " as args.");
        }

        opArgs.forEach(op -> sameDiff.getOps().remove(op));
        variablesToRemove.forEach(variable ->{
            sameDiff.getVariables().remove(variable);
            if(sameDiff.getLossVariables().contains(variable)) {
                sameDiff.getLossVariables().remove(variable);
            }
        });



        System.out.println("Removed variable from " + variableToRemove + " saving file to "  + newModelOutputPath.getAbsolutePath());
        sameDiff.asFlatFile(newModelOutputPath);
        return 0;
    }



}
