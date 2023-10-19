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
import org.nd4j.autodiff.samediff.SDVariable;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.codegen.Namespace;
import org.nd4j.codegen.api.LossReduce;
import org.nd4j.codegen.api.Op;
import org.nd4j.linalg.api.buffer.DataType;
import picocli.CommandLine;

import java.io.File;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@CommandLine.Command(name = "remove-op",description = "Remove op and associated output variable from op.")
public class RemoveOp implements Callable<Integer> {

    @CommandLine.Option(names = {"--modelInputPath"},description = "Input path to model.",required = true)
    private File modelInputPath;
    @CommandLine.Option(names = {"--newModelOutputPath"},description = "Output path to new.",required = false)
    private File newModelOutputPath;

    @CommandLine.Option(names = {"--opToRemove"},description = "Name of op to remove",required = true)
    private String opToRemove;
    public RemoveOp() {
    }

    @Override
    public Integer call() throws Exception {
        SameDiff sameDiff = SameDiff.load(modelInputPath,true);
        DifferentialFunction opByName = sameDiff.getOpById(opToRemove);
        if(opByName == null) {
            System.err.println("Unable to find op by name " + opToRemove + " . Exiting.");
            return 1;
        }

        SDVariable[] outputOfOp = sameDiff.getOutputVariablesForOp(opByName);
        for(SDVariable remove : outputOfOp) {
            sameDiff.getVariables().remove(remove.name());
            sameDiff.getOps().values().forEach(op -> {
                if(op.getInputsToOp().contains(remove.name())) {
                    sameDiff.removeArgFromOp(remove.name(),op.getOp());
                }
            });
        }


        sameDiff.getOps().remove(opToRemove);

        System.out.println("Removed op " + opToRemove + " and outputs " + Arrays.toString(outputOfOp) + " saving file to " + newModelOutputPath.getAbsolutePath());
        sameDiff.asFlatFile(newModelOutputPath);
        return 0;
    }



}
