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

package ai.konduit.pipelinegenerator.main.config.updater;

import ai.konduit.pipelinegenerator.main.converter.LossReduceConverter;
import org.nd4j.autodiff.loss.LossReduce;
import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "generate-loss-descriptor",mixinStandardHelpOptions = false,description = "Generates a loss descriptor to be used with samediff_training. It is common to need to add loss functions from a graph when importing. This descriptor covers adding/removing loss functions from a model after conversion using the ./kompile model convert command.")
public class LossDescriptorGenerator implements Callable<Integer> {
    @CommandLine.Option(names = {"--lossFunctionType"},description = "The loss function type: absolute_diff,cosine_distance,ctc_loss,hinge,huber,l2,log,log_poisson,mean_pairwise_squared,mse,sigmoid_cross_entropy,softmax_cross_entropy,sparse_softmax_cross_entropy",required = true)
    private String lossFunctionType;
    @CommandLine.Option(names = {"--inputVariable"},description = "Name of the variable for the descriptor",required = true)
    private String inputVariable;
    @CommandLine.Option(names = {"--targetVariable"},description = "Name of the variable for the target",required = true)
    private String targetVariable;
    @CommandLine.Option(names = {"--weightsVariable"},description = "Name of the weights for weighted loss",required = false)
    private String weightsVariable;
    @CommandLine.Option(names = {"--targetLabelLengths"},description = "Name of the variable for the target label lengths. Used for CTC",required = false)
    private String targetLabelLengths;
    @CommandLine.Option(names = {"--logitInputsLengths"},description = "Name of the variable for the input  lengths. Used for CTC",required = false)
    private String logitInputsLengths;
    @CommandLine.Option(names = {"--lossReduce"},description = "The loss reduce accumulation type: none,sum,mean_by_weight,mean_by_nonzero_weightcount",required = false,converter = LossReduceConverter.class)
    private LossReduce lossReduce;


    @CommandLine.Option(names = {"--dimension"},description = "The dimension to do calculation along.",required = false)
    private int dimension;
    @CommandLine.Option(names = {"--dimensionName"},description = "Name of the variable to do loss calculation along.",required = false)
    private String dimensionName;


    public LossDescriptorGenerator() {
    }

    @Override
    public Integer call() throws Exception {
        LossDescriptor lossDescriptor = LossDescriptor.builder()
                .lossReduce(lossReduce)
                .dimensionName(dimensionName)
                .dimension(dimension)
                .lossFunctionType(lossFunctionType)
                .inputVariable(inputVariable)
                .targetLabelLengths(targetLabelLengths)
                .labelVariable(targetVariable)
                .weightsVariable(weightsVariable)
                .lossReduce(lossReduce)
                .build();
        System.out.println(lossDescriptor.toJson());
        return 0;
    }
}
