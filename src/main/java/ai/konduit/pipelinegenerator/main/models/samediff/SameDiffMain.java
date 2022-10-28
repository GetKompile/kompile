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

import picocli.CommandLine;

import java.util.Arrays;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "samediff",
        subcommands = {
                SameDiffSummary.class,
                AddLoss.class,
                AddTrainingConfig.class,
                AddVariable.class,
                ChangeVariableType.class,
                PrepareForTraining.class,
                CreateBlank.class,
                RemoveOp.class,
                RemoveVariable.class,
                RenameVariable.class
        },modelTransformer = AddOp.class,
        description = "Utilities related to samediff models")
public class SameDiffMain implements Callable<Integer> {
    public SameDiffMain() {
    }

    @Override
    public Integer call() throws Exception {
        CommandLine commandLine = new CommandLine(new SameDiffMain());
        commandLine.usage(System.err);
        return 0;
    }


    public static void main(String...args) {
        CommandLine commandLine = new CommandLine(new SameDiffMain());

        if(args == null || args.length < 1) {
            commandLine.usage(System.err);
        }

        //creation step is dynamically generated and needs special support
        if(Arrays.asList(args).contains("add-op")) {
            commandLine.setExecutionStrategy(parseResult -> {
                try {
                    return AddOp.run(parseResult);
                } catch (Exception e) {
                    e.printStackTrace();
                }

                return 1;
            });
        }

        int exit = commandLine.execute(args);
        if(args.length > 0 && !args[0].equals("add-op") && args.length > 1 && !args[1].equals("add-op"))
            System.exit(exit);
    }

}
