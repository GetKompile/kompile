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

import ai.konduit.pipelinegenerator.main.config.updater.VariableDescriptor;
import org.apache.commons.io.FileUtils;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import picocli.CommandLine;

import java.io.File;
import java.nio.charset.Charset;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "add-variable",description = "Add variable to an existing samediff model.")
public class AddVariable implements Callable<Integer> {

    @CommandLine.Option(names = {"--modelInputPath"},description = "Input path to model.",required = true)
    private String modelInputPath;
    @CommandLine.Option(names = {"--inputVariableDescriptorPath"},description = "Input path to json file generated with ./kompile config variable-descriptor-generator.",required = true)
    private File inputVariableDescriptorPath;

    @CommandLine.Option(names = {"--newModelOutputPath"},description = "Output path to new model file for saving a new model with the newly added variable.",required = true)
    private File newModelOutputPath;
    @CommandLine.Option(names = {"--constantArray"},description = "Path to an npy file saved with numpy.save(..)",required = false)
    private File numpyFile;


    public AddVariable() {
    }

    @Override
    public Integer call() throws Exception {
        File modelFile = new File(modelInputPath);
        if(!modelFile.exists()) {
            System.err.println("No model file found at path " + modelInputPath + " exiting.");
            return 1;
        }

        if(!inputVariableDescriptorPath.exists()) {
            System.err.println("No descriptor file found at path " + inputVariableDescriptorPath + " exiting.");
            return 1;
        }

        SameDiff sameDiff = SameDiff.load(modelFile,false);
        System.out.println("Loaded model.");
        VariableDescriptor variableDescriptor = VariableDescriptor.fromJson(FileUtils.readFileToString(inputVariableDescriptorPath, Charset.defaultCharset()));
        switch(variableDescriptor.getVariableType()) {
            case VARIABLE:
                if(numpyFile != null && numpyFile.exists()) {
                    System.out.println("Numpy file specified. Creating variable from input file.");
                    INDArray arr = Nd4j.createFromNpyFile(numpyFile);
                    sameDiff.var(variableDescriptor.getVarName(),arr);
                }
                else
                    sameDiff.var(variableDescriptor.getVarName(),variableDescriptor.getDataType(),variableDescriptor.getShape());
                break;
            case CONSTANT:
                if(numpyFile == null || !numpyFile.exists()) {
                    System.err.println("No numpy file specified or found at path " + inputVariableDescriptorPath + " exiting.");
                    return 1;
                }

                INDArray arr = Nd4j.createFromNpyFile(numpyFile);
                sameDiff.constant(variableDescriptor.getVarName(),arr);

                break;
            case PLACEHOLDER:
                sameDiff.placeHolder(variableDescriptor.getVarName(),variableDescriptor.getDataType(),variableDescriptor.getShape());
                break;
        }

        sameDiff.asFlatFile(newModelOutputPath);

        System.out.println("Saved model at " + newModelOutputPath);
        return 0;
    }
}
