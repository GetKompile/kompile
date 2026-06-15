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
import picocli.CommandLine;

import java.io.File;
import java.util.Arrays;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "rename-variable",description = "Rename variable.")
public class RenameVariable implements Callable<Integer> {

    @CommandLine.Option(names = {"--modelInputPath"},description = "Input path to model.",required = true)
    private File modelInputPath;
    @CommandLine.Option(names = {"--newModelOutputPath"},description = "Output path to new.",required = false)
    private File newModelOutputPath;

    @CommandLine.Option(names = {"--variableToRename"},description = "Name of variable to rename",required = true)
    private String variableToRename;

    @CommandLine.Option(names = {"--newVariableName"},description = "New name of variable",required = true)
    private String newVariableName;

    public RenameVariable() {
    }

    @Override
    public Integer call() throws Exception {
        SameDiff sameDiff = SameDiff.load(modelInputPath,true);
        sameDiff.renameVariable(variableToRename,newVariableName);

        System.out.println("Renamed variable from " + variableToRename + " to " + newVariableName + " saving file to " + newModelOutputPath.getAbsolutePath());
        sameDiff.asFlatFile(newModelOutputPath);
        return 0;
    }



}
