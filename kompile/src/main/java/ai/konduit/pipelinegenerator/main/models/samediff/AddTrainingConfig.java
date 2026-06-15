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

import org.apache.commons.io.FileUtils;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.autodiff.samediff.TrainingConfig;
import picocli.CommandLine;

import java.io.File;
import java.nio.charset.Charset;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "add-training-config",description = "Add variable to an existing samediff model.")
public class AddTrainingConfig implements Callable<Integer> {

    @CommandLine.Option(names = {"--modelInputPath"},description = "Input path to model.",required = true)
    private String modelInputPath;
    @CommandLine.Option(names = {"--trainingConfigFile"},description = "Input path to json file generated with ./kompile config variable-descriptor-generator.",required = true)
    private File trainingConfigFile;

    @CommandLine.Option(names = {"--newModelOutputPath"},description = "Output path to new model file for saving a new model with the newly added variable.",required = true)
    private File newModelOutputPath;

    public AddTrainingConfig() {
    }

    @Override
    public Integer call() throws Exception {
        File modelFile = new File(modelInputPath);
        if(!modelFile.exists()) {
            System.err.println("No model file found at path " + modelInputPath + " exiting.");
            return 1;
        }

        if(!trainingConfigFile.exists()) {
            System.err.println("No descriptor file found at path " + trainingConfigFile + " exiting.");
            return 1;
        }

        TrainingConfig trainingConfig = TrainingConfig.fromJson(FileUtils.readFileToString(trainingConfigFile, Charset.defaultCharset()));


        SameDiff sameDiff = SameDiff.load(modelFile,false);
        sameDiff.setTrainingConfig(trainingConfig);
        sameDiff.asFlatFile(newModelOutputPath);

        System.out.println("Saved model at " + newModelOutputPath);
        return 0;
    }
}
