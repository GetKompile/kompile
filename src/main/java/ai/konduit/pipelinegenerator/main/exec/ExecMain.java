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

package ai.konduit.pipelinegenerator.main.exec;

import picocli.CommandLine;

import java.util.Arrays;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "exec",subcommands = {
       InferenceServerCreate.class,
        PipelineGenerator.class,
        SequencePipelineCombiner.class
}, modelTransformer = StepCreator.class,
        mixinStandardHelpOptions = false,
        description = "Execution configuration related classes for running ML pipelines")
public class ExecMain implements Callable<Integer> {
    public ExecMain() {
    }


    public static void main(String...args) throws Exception {
        CommandLine commandLine = new CommandLine(new ExecMain());

        if(args == null || args.length < 1) {
            commandLine.usage(System.err);
        }

        //creation step is dynamically generated and needs special support
        if(Arrays.asList(args).contains("step-create")) {
            commandLine.setExecutionStrategy(parseResult -> {
                try {
                    return StepCreator.run(parseResult);
                } catch (Exception e) {
                    e.printStackTrace();
                }

                return 1;
            });
        }

        int exit = commandLine.execute(args);
        if(args.length > 0 && !args[0].equals("serve") && args.length > 1 && !args[1].equals("serve"))
            System.exit(exit);
    }


    @Override
    public Integer call() throws Exception {
        CommandLine commandLine = new CommandLine(new ExecMain());
        commandLine.usage(System.err);
        return 0;
    }
}
