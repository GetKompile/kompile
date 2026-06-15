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
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.autodiff.samediff.VariableType;
import picocli.CommandLine;

import java.io.File;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "change-variable-type",description = "Change variable in an existing samediff model.")
public class ChangeVariableType implements Callable<Integer> {

    @CommandLine.Option(names = {"--modelInputPath"},description = "Input path to model.",required = true)
    public File modelInputPath;
    @CommandLine.Option(names = {"--newModelOutputPath"},description = "Output path to new model file for saving a new model with the newly added variable.",required = true)
    public File newModelOutputPath;
    @CommandLine.Option(names = {"--variableToChange"},description = "Name of the variable to change.",required = true)
    public String variableToChange;

    @CommandLine.Option(names = {"--convertTo"},description = "The variable type to change the variable to",required = true,converter = VariableTypeConverter.class)
    public VariableType convertTo;

    public ChangeVariableType() {
    }

    @Override
    public Integer call() throws Exception {
        if(!modelInputPath.exists()) {
            System.err.println("No model file found at path " + modelInputPath + " exiting.");
            return 1;
        }


        SameDiff sameDiff = SameDiff.load(modelInputPath,false);
        if(!sameDiff.getVariables().containsKey(variableToChange)) {
            System.err.println("Variable " + variableToChange + " not found! Exiting.");
            return 1;
        }

        switch (convertTo) {
            case CONSTANT:
                sameDiff.convertToConstant(sameDiff.getVariable(variableToChange));
                break;
            case VARIABLE:
                sameDiff.convertToVariable(sameDiff.getVariable(variableToChange));
                break;
            default:
                sameDiff.getVariable(variableToChange).setVariableType(convertTo);
        }

        System.out.println("Changed variable " + variableToChange + " to " + convertTo);

        sameDiff.asFlatFile(newModelOutputPath);

        System.out.println("Saved model at " + newModelOutputPath);
        return 0;
    }
}
