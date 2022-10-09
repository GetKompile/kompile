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

import picocli.CommandLine;

import java.util.concurrent.Callable;
@CommandLine.Command(name = "model",mixinStandardHelpOptions = false,
        subcommands = {
                SameDiffSummary.class,
                DL4jSummary.class,
                Convert.class,
                OnnxPrint.class,
                TensorflowPrint.class,
                RequiredOpsDataTypes.class,
                AddLoss.class,
                AddVariable.class,
                AddTrainingConfig.class
        },
        description = "Utilities related to models including execution and debugging.")
public class ModelMain implements Callable<Integer> {
    public ModelMain() {
    }

    @Override
    public Integer call() throws Exception {
        CommandLine commandLine = new CommandLine(new ModelMain());
        commandLine.usage(System.err);
        return 0;
    }
}
