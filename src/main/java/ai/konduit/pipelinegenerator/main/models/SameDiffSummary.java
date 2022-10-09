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

package ai.konduit.pipelinegenerator.main.models;

import org.nd4j.autodiff.samediff.SameDiff;
import picocli.CommandLine;

import java.io.File;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@CommandLine.Command(name = "samediff-summary",description = "Print summary of a target samediff model.")
public class SameDiffSummary implements Callable<Integer> {
    @CommandLine.Option(names = {"--modelInputPath"},description = "Input path to model.",required = true)
    private String modelInputPath;

    @CommandLine.Option(names = {"--printOpNames"},description = "Print op names",required = false)
    private boolean printOpNames;
    @CommandLine.Option(names = {"--printVariableNames"},description = "Print variable names.",required = false)
    private boolean printVariableNames;
    @CommandLine.Option(names = {"--printFullSummary"},description = "Print full summary.",required = false)
    private boolean printFullSummary;
    @CommandLine.Option(names = {"--printTrainingConfig"},description = "Print training config only.",required = false)
    private boolean printTrainingConfig;
    @CommandLine.Option(names = {"--printLossVariables"},description = "Print loss variables only.",required = false)
    private boolean printLossVariables;

    public SameDiffSummary() {
    }

    @Override
    public Integer call() throws Exception {
        File modelFile = new File(modelInputPath);
        if(!modelFile.exists()) {
            System.err.println("No model file found at path " + modelInputPath + " exiting.");
            return 1;
        }

        SameDiff sameDiff = SameDiff.load(new File(modelInputPath),false);
        if(printVariableNames)
            sameDiff.variables().stream().map(input -> input.name()).collect(Collectors.toList()).
                    forEach(variable -> System.out.println(variable));
        if(printOpNames)
            sameDiff.getOps().keySet().stream()
                    .forEach(input -> System.out.println(input));
        if(printFullSummary)
            System.out.println(sameDiff.summary());

        if(printLossVariables) {
            System.out.println(sameDiff.getLossVariables());
        }

        if(printTrainingConfig) {
            if(sameDiff.getTrainingConfig() == null) {
                System.err.println("No training configuration found. Exiting.");
                return 1;
            }

            System.out.println(sameDiff.getTrainingConfig().toJson());
        }

        return 0;
    }
}
