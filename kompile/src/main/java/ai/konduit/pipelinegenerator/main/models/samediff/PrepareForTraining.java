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

import org.nd4j.autodiff.samediff.SDVariable;
import org.nd4j.autodiff.samediff.SameDiff;
import picocli.CommandLine;

import java.io.File;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "prepare-for-training",description = "After a loss function, updater and training configuration are added, this ensures all variables are ready for training.")
public class PrepareForTraining implements Callable<Integer> {

    @CommandLine.Option(names = {"--modelInputPath"},description = "Input path to model.",required = true)
    private String modelInputPath;
    @CommandLine.Option(names = {"--newModelOutputPath"},description = "Output path to new.",required = false)
    private File newModelOutputPath;


    public PrepareForTraining() {
    }

    @Override
    public Integer call() throws Exception {
        File modelFile = new File(modelInputPath);
        if(!modelFile.exists()) {
            System.err.println("No model file found at path " + modelInputPath + " exiting.");
            return 1;
        }


        if(newModelOutputPath == null) {
            newModelOutputPath = new File(modelInputPath);
        }


        SameDiff sameDiff = SameDiff.load(modelFile,false);
        System.out.println("Loaded model.");
        if(sameDiff.getTrainingConfig() == null) {
            System.err.println("Unable to prepare for training: missing training configuration. Add one with ./kompile model samediff add-training-configuration");
            return 1;
        }

        if(sameDiff.getLossVariables() == null) {
            System.err.println("Missing loss variables. Please add them with ./kompile model samediff add-loss");
            return 1;
        }



        sameDiff.prepareForTraining();
        sameDiff.asFlatFile(newModelOutputPath);

        System.out.println("Saved model at " + newModelOutputPath);
        return 0;
    }



    private SDVariable getOptionalVariable(SameDiff sameDiff,String name) {
        return sameDiff.getVariables().containsKey(name) ?
                sameDiff
                        .getVariable(name) : null;
    }
}
