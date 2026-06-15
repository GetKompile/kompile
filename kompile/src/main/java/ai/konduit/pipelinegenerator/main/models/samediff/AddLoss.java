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

import ai.konduit.pipelinegenerator.main.config.updater.LossDescriptor;
import org.apache.commons.io.FileUtils;
import org.deeplearning4j.nn.conf.layers.samediff.SameDiffVertex;
import org.nd4j.autodiff.samediff.SDVariable;
import org.nd4j.autodiff.samediff.SameDiff;
import picocli.CommandLine;

import java.io.File;
import java.nio.charset.Charset;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "add-loss",description = "Add loss function to an existing samediff model.")
public class AddLoss implements Callable<Integer> {

    @CommandLine.Option(names = {"--lossVariableName"},description = "The output variable name for the loss calculation.",required = false)
    private String lossVariableName;
    @CommandLine.Option(names = {"--modelInputPath"},description = "Input path to model.",required = true)
    private String modelInputPath;
    @CommandLine.Option(names = {"--inputLossDescriptorPath"},description = "Input path to json file generated with ./kompile config variable-descriptor-generator.",required = true)
    private File inputLossDescriptorPath;

    @CommandLine.Option(names = {"--newModelOutputPath"},description = "Output path to new model file for saving a new model with the newly added variable.",required = true)
    private File newModelOutputPath;
    @CommandLine.Option(names = {"--fullLogPoisson"},description = "See: https://github.com/deeplearning4j/deeplearning4j/blob/394cf869eee1f773ca0947dc0f67805e46a8eb30/nd4j/nd4j-backends/nd4j-api-parent/nd4j-api/src/main/java/org/nd4j/autodiff/samediff/ops/SDLoss.java#L571",required = false)
    private boolean fullLogPoisson;
    @CommandLine.Option(names = {"--huberDelta"},description = "Output path to new model file for saving a new model with the newly added variable.",required = false)
    private double huberDelta;

    @CommandLine.Option(names = {"--logEpsilon"},description = "See: https://github.com/deeplearning4j/deeplearning4j/blob/394cf869eee1f773ca0947dc0f67805e46a8eb30/nd4j/nd4j-backends/nd4j-api-parent/nd4j-api/src/main/java/org/nd4j/autodiff/samediff/ops/SDLoss.java#L475.",required = false)
    private double logEpsilon;


    @CommandLine.Option(names = {"--labelSmoothing"},description = "See: https://github.com/deeplearning4j/deeplearning4j/blob/394cf869eee1f773ca0947dc0f67805e46a8eb30/nd4j/nd4j-backends/nd4j-api-parent/nd4j-api/src/main/java/org/nd4j/autodiff/samediff/ops/SDLoss.java#L1014",required = false)
    private double labelSmoothing;

    public AddLoss() {
    }

    @Override
    public Integer call() throws Exception {
        File modelFile = new File(modelInputPath);
        if(!modelFile.exists()) {
            System.err.println("No model file found at path " + modelInputPath + " exiting.");
            return 1;
        }

        if(!inputLossDescriptorPath.exists()) {
            System.err.println("No descriptor file found at path " + inputLossDescriptorPath.getAbsolutePath() + " exiting.");
            return 1;
        }

        SameDiff sameDiff = SameDiff.load(modelFile,false);
        System.out.println("Loaded model.");
        LossDescriptor lossDescriptor = LossDescriptor.fromJson(FileUtils.readFileToString(inputLossDescriptorPath, Charset.defaultCharset()));
        if(lossDescriptor.getLossFunctionType() == null) {
            System.err.println("Missing loss function type. Exiting.");
            return 1;
        }

        for(String inputVar : new String[]{lossDescriptor.getInputVariable(),lossDescriptor.getLabelVariable()}) {
            if (!sameDiff.hasVariable(inputVar)) {
                System.err.println("Missing input variable: " + inputVar);
                return 1;
            }
        }

        if(lossDescriptor.getInputVariable() == null || lossDescriptor.getLabelVariable() == null) {
            System.err.println("Invalid descriptor: both an input and label need to be specified.");
            return 1;
        }

        //The loss function type: absolute_diff,cosine_distance,ctc_loss,hinge,huber,l2,log,log_poisson,mean_pairwise_squared,mse,sigmoid_cross_entropy,softmax_cross_entropy,sparse_softmax_cross_entropy
        switch(lossDescriptor.getLossFunctionType()) {
            case "absolute_diff":
                sameDiff.loss().absoluteDifference(lossVariableName,
                        sameDiff.getVariable(lossDescriptor.getLabelVariable())
                        ,sameDiff.getVariable(lossDescriptor.getInputVariable()),
                        getOptionalVariable(sameDiff,lossDescriptor.getWeightsVariable()),
                        lossDescriptor.getLossReduce());
                break;
            case "cosine_distance":
                sameDiff.loss().cosineDistance(
                        lossVariableName,
                        sameDiff.getVariable(lossDescriptor.getLabelVariable())
                        ,sameDiff.getVariable(lossDescriptor.getInputVariable()),
                        getOptionalVariable(sameDiff,lossDescriptor.getWeightsVariable()),
                        lossDescriptor.getLossReduce(), lossDescriptor.getDimension());
                break;
            case "ctc_loss":
                for(String inputVar : new String[]{lossDescriptor.getTargetLabelLengths(),lossDescriptor.getLogitInputsLengths()}) {
                    if (!sameDiff.hasVariable(inputVar)) {
                        System.err.println("Missing CTC input variable: " + lossDescriptor.getInputVariable());
                        return 1;
                    }
                }
                sameDiff.loss().ctcLoss(
                        lossVariableName,
                        sameDiff.getVariable(lossDescriptor.getLabelVariable()),
                        sameDiff.getVariable(lossDescriptor.getInputVariable()),
                        sameDiff.getVariable(lossDescriptor.getTargetLabelLengths()),
                        sameDiff.getVariable(lossDescriptor.getLogitInputsLengths())
                );
                break;
            case "hinge":
                sameDiff.loss().hingeLoss(
                        lossVariableName,
                        sameDiff.getVariable(lossDescriptor.getLabelVariable())
                        ,sameDiff.getVariable(lossDescriptor.getInputVariable()),
                        getOptionalVariable(sameDiff,lossDescriptor.getWeightsVariable()),
                        lossDescriptor.getLossReduce());

                break;
            case "huber":
                sameDiff.loss().huberLoss(
                        lossVariableName,
                        sameDiff.getVariable(lossDescriptor.getLabelVariable())
                        ,sameDiff.getVariable(lossDescriptor.getInputVariable()),
                        getOptionalVariable(sameDiff,lossDescriptor.getWeightsVariable()),
                        lossDescriptor.getLossReduce(),
                        huberDelta);

                break;
            case "l2":
                sameDiff.loss().l2Loss(
                        lossVariableName,
                        sameDiff.getVariable(
                                lossDescriptor.getInputVariable()));
                break;
            case "log":
                sameDiff.loss().logLoss(
                        lossVariableName,
                        sameDiff.getVariable(lossDescriptor.getLabelVariable())
                        ,sameDiff.getVariable(lossDescriptor.getInputVariable()),
                        getOptionalVariable(sameDiff,lossDescriptor.getWeightsVariable()),
                        lossDescriptor.getLossReduce(),
                        logEpsilon);

                break;
            case "log_poisson":
                sameDiff.loss().logPoisson(
                        lossVariableName,
                        sameDiff.getVariable(lossDescriptor.getLabelVariable())
                        ,sameDiff.getVariable(lossDescriptor.getInputVariable()),
                        getOptionalVariable(sameDiff,lossDescriptor.getWeightsVariable()),
                        lossDescriptor.getLossReduce(),
                        fullLogPoisson);

                break;
            case "mean_pairwise_squared":
                sameDiff.loss().meanPairwiseSquaredError(
                        lossVariableName,
                        sameDiff.getVariable(lossDescriptor.getLabelVariable())
                        ,sameDiff.getVariable(lossDescriptor.getInputVariable()),
                        getOptionalVariable(sameDiff,lossDescriptor.getWeightsVariable()),
                        lossDescriptor.getLossReduce());

                break;
            case "mse":
                sameDiff.loss().meanSquaredError(
                        lossVariableName,
                        sameDiff.getVariable(lossDescriptor.getLabelVariable())
                        ,sameDiff.getVariable(lossDescriptor.getInputVariable()),
                        getOptionalVariable(sameDiff,lossDescriptor.getWeightsVariable()),
                        lossDescriptor.getLossReduce());

                break;
            case "sigmoid_cross_entropy":
                sameDiff.loss().sigmoidCrossEntropy(
                        lossVariableName,
                        sameDiff.getVariable(lossDescriptor.getLabelVariable())
                        ,sameDiff.getVariable(lossDescriptor.getInputVariable()),
                        getOptionalVariable(sameDiff,lossDescriptor.getWeightsVariable()),
                        lossDescriptor.getLossReduce(),
                        labelSmoothing);

                break;
            case "softmax_cross_entropy":
                sameDiff.loss().softmaxCrossEntropy(
                        lossVariableName,
                        sameDiff.getVariable(lossDescriptor.getLabelVariable())
                        ,sameDiff.getVariable(lossDescriptor.getInputVariable()),
                        getOptionalVariable(sameDiff,lossDescriptor.getWeightsVariable()),lossDescriptor.getLossReduce(),
                        labelSmoothing);
                break;
            case "sparse_softmax_cross_entropy":
                sameDiff.loss().sparseSoftmaxCrossEntropy(
                        lossVariableName,
                        sameDiff.getVariable(lossDescriptor.getLabelVariable())
                        ,sameDiff.getVariable(lossDescriptor.getInputVariable()));
                break;

        }

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
