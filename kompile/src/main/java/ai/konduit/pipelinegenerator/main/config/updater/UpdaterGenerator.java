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

import ai.konduit.serving.pipeline.util.ObjectMappers;
import org.apache.commons.io.FileUtils;
import org.nd4j.linalg.learning.config.*;
import org.nd4j.linalg.schedule.ISchedule;
import picocli.CommandLine;

import java.io.File;
import java.nio.charset.Charset;
import java.util.Set;
import java.util.concurrent.Callable;
@CommandLine.Command(name = "generate-updater-config",mixinStandardHelpOptions = false,description = "Generates an updater configuration. Used in combination with samediff training to specify an updater to use.")
public class UpdaterGenerator implements Callable<Integer> {
    @CommandLine.Option(names = {"--updaterType"},description = "The updater type: amsgrad,adabelief,adadelta,adagrad,adamax,adam,nadam,nesterovs,noop,rmsprop,sgd",required = true)
    private String updaterType;
    @CommandLine.Option(names = {"--beta1"},description = "The beta1 parameter. Decay factor for first momentum. Used in various adam derivative updaters: amsgrad,adabelief,adadelta,adamax,adam,nadam ",required = false)
    private Double beta1;
    @CommandLine.Option(names = {"--beta2"},description = "The beta2 parameter. Decay factor for infinity norm. Used in various adam derivative updaters: amsgrad,adabelief,adadelta,adamax,adam,nadam",required = false)
    private Double beta2;
    @CommandLine.Option(names = {"--learningRate"},description = "Step size for each updater for the updater. Relevant for all algorithms.",required = false)
    private Double learningRate;

    @CommandLine.Option(names = {"--rho"},description = "The decay rate. Used in adadelta.",required = false)
    private Double rho;

    @CommandLine.Option(names = {"--epsilon"},description = "Used to ensure numerical stability in various algorithms: adadelta,adabelief,adamax,adam,nadam,rmsprop",required = false)
    private Double epsilon;

    @CommandLine.Option(names = {"--momentum"},description = "momentum parameter for tuning the step size of the momentum algorithm",required = false)
    private Double momentum;

    @CommandLine.Option(names = {"--rmsDecay"},description = "The decay rate for the gradient weighted moving average in rmsprop.",required = false)
    private Double rmsDecay;
    @CommandLine.Option(names = {"--stepSchedulePath"},description = "The path to a json file describing a step schedule. Generated with ./kompile config generate-schedule-config only needed when specifying a" +
            "learning rate steps schedule.",required = false)
    private String stepSchedulePath;
    private static Set<String> adamUpdaters = Set.of("amsgrad","adabelief","adadelta","adamax","adam","nadam");
    private static Set<String> epsilonUpdaters = Set.of("adadelta","adabelief","adamax","adam","nadam","rmsprop");


    public UpdaterGenerator() {
    }

    @Override
    public Integer call() throws Exception {
        //amsgrad,adabelief,adadelta,adagrad,adamax,adam,nadam,nesterovs,noop,rmsprop,sgd
        if(!validateBeta1()) {
            System.err.println("Beta1 parameter specified but wrong updater type: " + updaterType);
            return 1;
        }

        if(!validateBeta2()) {
            System.err.println("Beta2 parameter specified but wrong updater type: " + updaterType);
            return 1;
        }

        if(!validateEpsilon()) {
            System.err.println("Epsilon parameter specified but wrong updater type: " + updaterType);
            return 1;
        }

        if(!validateRho()) {
            System.err.println("Rho parameter specified but wrong updater type: " + updaterType);
            return 1;
        }

        if(!validateRmsDecay()) {
            System.err.println("RMS Decay parameter specified but wrong updater type: " + updaterType);
            return 1;
        }

        if(!validateMomentum()) {
            System.err.println("Momentum parameter specified but wrong updater type: " + updaterType);
            return 1;
        }


        IUpdater create = null;
        ISchedule schedule = null;
        if(stepSchedulePath != null) {
            File schedulePath = new File(stepSchedulePath);
            if(!schedulePath.exists()) {
                System.err.println("Step schedule file path specified, but file does not exist.");
                return 1;
            }

            String json = FileUtils.readFileToString(schedulePath, Charset.defaultCharset());
            schedule = ObjectMappers.fromJson(json,ISchedule.class);

        }


        switch(updaterType) {
            case "amsgrad":
                create = AMSGrad.builder()
                        .beta1(beta1 == null ? AMSGrad.DEFAULT_AMSGRAD_BETA1_MEAN_DECAY : beta1)
                        .beta2(beta2 == null ? AMSGrad.DEFAULT_AMSGRAD_BETA2_VAR_DECAY : beta2)
                        .learningRate(learningRate == null ? AMSGrad.DEFAULT_AMSGRAD_LEARNING_RATE : learningRate)
                        .epsilon(epsilon == null ? AMSGrad.DEFAULT_AMSGRAD_EPSILON : epsilon)
                        .learningRateSchedule(schedule)
                        .build();
                break;
            case "adabelief":
                create = AdaBelief.builder()
                        .beta1(beta1 == null ? AdaBelief.DEFAULT_BETA1_MEAN_DECAY : beta1)
                        .beta2(beta2 == null ? AdaBelief.DEFAULT_BETA2_VAR_DECAY : beta2)
                        .learningRate(learningRate == null ? AdaBelief.DEFAULT_LEARNING_RATE : learningRate)
                        .epsilon(epsilon == null ? AdaBelief.DEFAULT_EPSILON : epsilon)
                        .learningRateSchedule(schedule)
                        .build();
                break;
            case "adadelta":
                create = AdaDelta.builder()
                        .epsilon(epsilon == null ? AdaDelta.DEFAULT_ADADELTA_EPSILON : epsilon)
                        .rho(rho == null ? AdaDelta.DEFAULT_ADADELTA_RHO : rho)
                        .build();
                break;
            case "adagrad":
                create = AdaGrad.builder()
                        .epsilon(epsilon == null ? AdaGrad.DEFAULT_ADAGRAD_EPSILON : epsilon)
                        .learningRate(learningRate == null ? AdaGrad.DEFAULT_ADAGRAD_LEARNING_RATE : learningRate)
                        .learningRateSchedule(schedule)
                        .build();
                break;
            case "adamax":
                create = new AdaMax();
                AdaMax adaMax = (AdaMax) create;
                if(beta1 != null)
                    adaMax.setBeta1(beta1);
                if(beta2 != null)
                    adaMax.setBeta2(beta2);
                if(epsilon != null)
                    adaMax.setEpsilon(epsilon);
                if(schedule != null)
                    adaMax.setLearningRateSchedule(schedule);

                break;
            case "adam":
                create = Adam.builder()
                        .beta1(beta1 == null ? Adam.DEFAULT_ADAM_BETA1_MEAN_DECAY : beta1)
                        .beta2(beta2 == null ? Adam.DEFAULT_ADAM_BETA2_VAR_DECAY : beta2)
                        .epsilon(epsilon == null ? Adam.DEFAULT_ADAM_EPSILON : epsilon)
                        .learningRate(learningRate == null ? Adam.DEFAULT_ADAM_LEARNING_RATE : learningRate)
                        .learningRateSchedule(schedule)
                        .build();
                break;
            case "nadam":
                create = Nadam.builder()
                        .beta1(beta1 == null ? Nadam.DEFAULT_NADAM_BETA1_MEAN_DECAY : beta1)
                        .beta2(beta2 == null ? Nadam.DEFAULT_NADAM_BETA2_VAR_DECAY : beta2)
                        .epsilon(epsilon == null ? Nadam.DEFAULT_NADAM_EPSILON : epsilon)
                        .learningRate(learningRate == null ? Nadam.DEFAULT_NADAM_LEARNING_RATE : learningRate)
                        .learningRateSchedule(schedule)
                        .build();
                break;
            case "nesterovs":
                create = Nesterovs.builder()
                        .learningRate(learningRate == null ? Nesterovs.DEFAULT_NESTEROV_LEARNING_RATE : learningRate)
                        .momentum(momentum == null ? Nesterovs.DEFAULT_NESTEROV_MOMENTUM : momentum)
                        .learningRateSchedule(schedule)
                        .build();
                break;
            case "noop":
                create = new NoOp();
                break;
            case "rmsprop":
                create = RmsProp.builder()
                        .epsilon(epsilon == null ? RmsProp.DEFAULT_RMSPROP_EPSILON : epsilon)
                        .learningRate(learningRate == null ? RmsProp.DEFAULT_RMSPROP_LEARNING_RATE : learningRate)
                        .rmsDecay(rmsDecay == null ? RmsProp.DEFAULT_RMSPROP_RMSDECAY : rmsDecay)
                        .learningRateSchedule(schedule)
                        .build();
                break;
            case "sgd":
                create = Sgd.builder()
                        .learningRate(learningRate == null ? Sgd.DEFAULT_SGD_LR : learningRate)
                        .learningRateSchedule(schedule)
                        .build();
                break;
        }

        if(create == null) {
            System.err.println("Invalid updater type specified. Please specify one of: amsgrad,adabelief,adadelta,adagrad,adamax,adam,nadam,nesterovs,noop,rmsprop,sgd ");
            return 1;
        }

        System.out.println(ObjectMappers.toJson(create));
        return 0;
    }

    private boolean validateBeta1() {
        if(beta1 != null) {
            return adamUpdaters.contains(updaterType);
        }

        return true;
    }

    private boolean validateBeta2() {
        if(beta2 != null)
            return adamUpdaters.contains(updaterType);
        return true;
    }

    private boolean validateEpsilon() {
        if(epsilon != null) {
            return epsilonUpdaters.contains(updaterType);
        }
        return true;
    }

    private boolean validateMomentum() {
        if(momentum != null) {
            return updaterType.equals("momentum");
        }
        return true;
    }

    private boolean validateRho() {
        if(rho != null) {
            return updaterType.equals("rmsprop");
        }
        return true;
    }

    private boolean validateRmsDecay() {
        if(rmsDecay != null) {
            return updaterType.equals("rmsprop");
        }
        return true;
    }

}
