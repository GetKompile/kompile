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

import ai.konduit.pipelinegenerator.main.converter.DataTypeConverter;
import ai.konduit.pipelinegenerator.main.converter.IUpdaterTypeConverter;
import ai.konduit.pipelinegenerator.main.converter.RegularizationConverter;
import ai.konduit.serving.pipeline.util.ObjectMappers;
import org.apache.commons.io.FileUtils;
import org.nd4j.autodiff.samediff.TrainingConfig;
import org.nd4j.evaluation.classification.Evaluation;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.learning.config.*;
import org.nd4j.linalg.learning.regularization.Regularization;
import org.nd4j.linalg.schedule.ISchedule;
import picocli.CommandLine;

import java.io.File;
import java.nio.charset.Charset;
import java.util.Set;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "generate-training-config",mixinStandardHelpOptions = false,description = "Generates an updater configuration. Used in combination with samediff training to specify an updater to use.")
public class TrainingConfigGenerator implements Callable<Integer> {
    @CommandLine.Option(names = {"--l2"},description = "l2 value for regularization",required = false)
    private Double l2;

    @CommandLine.Option(names = {"--l1"},description = "l1 value for regularization",required = false)
    private Double l1;

    @CommandLine.Option(names = {"--dataSetFeatureMapping"},description = "list of variables by index for inputs",required = false)
    private String[] dataSetFeatureMapping;

    @CommandLine.Option(names = {"--dataSetLabelMapping"},description = "list of variables by index for inputs, specify flag for each variable",required = false)
    private String[] dataSetLabelMapping;
    @CommandLine.Option(names = {"--lossVariables"},description = "list of variables to minimize during training, specify flag for each variable",required = false)
    private String[] lossVariables;

    @CommandLine.Option(names = {"--dataSetLabelMaskMapping"},description = "list of variables to use for masks, specify flag for each variable",required = false)
    private String[] dataSetLabelMaskMapping;

    @CommandLine.Option(names = {"--initialDataTypeLoss"},description = "initial data type for loss",required = false,converter = DataTypeConverter.class)
    private DataType initialDataTypeLoss = DataType.FLOAT;
    @CommandLine.Option(names = {"--updaterConfiguration"},description = "initial data type for loss",required = false,converter = IUpdaterTypeConverter.class)
    private IUpdater updaterConfiguration;
    @CommandLine.Option(names = {"--weightDecayCoefficient"},description = "weight decay oefficient",required = false)
    private Double weightDecayCoefficient;
    @CommandLine.Option(names = {"--applyLearningRateDuringWeightDecay"},description = "weight decay coefficient",required = false)
    private Boolean applyLearningRateDuringWeightDecay;
    @CommandLine.Option(names = {"--regularization"},description = "regularizations to apply",required = false,converter = RegularizationConverter.class)
    private Regularization[] regularization;

    public TrainingConfigGenerator() {
    }

    @Override
    public Integer call() throws Exception {
        TrainingConfig.Builder trainingConfigBuilder = TrainingConfig.builder()
                .initialLossDataType(initialDataTypeLoss);

        if(dataSetLabelMaskMapping != null)
            trainingConfigBuilder.dataSetLabelMaskMapping(dataSetLabelMaskMapping);

        if(dataSetLabelMapping != null)
            trainingConfigBuilder.dataSetLabelMapping(dataSetLabelMapping);

        if(dataSetFeatureMapping != null)
            trainingConfigBuilder.dataSetFeatureMapping(dataSetFeatureMapping);

        if(regularization != null)
            trainingConfigBuilder.regularization(regularization);

        if(lossVariables != null)
            // trainingConfigBuilder.minimize(lossVariables); // TODO note: comment for running build

        if(updaterConfiguration != null)
            trainingConfigBuilder.updater(updaterConfiguration);

        if(l1 != null)
            trainingConfigBuilder.l1(l1);
        if(l2 != null)
            trainingConfigBuilder.l2(l2);
        if(applyLearningRateDuringWeightDecay != null && weightDecayCoefficient != null) {
            trainingConfigBuilder.weightDecay(weightDecayCoefficient,applyLearningRateDuringWeightDecay);
        } else if(applyLearningRateDuringWeightDecay != null && weightDecayCoefficient == null) {
            System.err.println("Specified whether to apply weight decay but no coefficient. Please specify both. Exiting.");
            return 1;
        } else if(weightDecayCoefficient != null && applyLearningRateDuringWeightDecay == null) {
            System.err.println("Specified weight decay coefficient but not whether to apply weight decay. Please specify both. Exiting.");
            return 1;
        }




        TrainingConfig trainingConfig = trainingConfigBuilder.build();

        System.out.println(ObjectMappers.toJson(trainingConfig));
        return 0;
    }



}
