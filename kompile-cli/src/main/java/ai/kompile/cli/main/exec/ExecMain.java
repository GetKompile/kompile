/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.exec;

import picocli.CommandLine;

import java.io.File;
import java.util.Arrays;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "exec",subcommands = {
        PipelineGenerator.class,
        SequencePipelineCombiner.class
}, modelTransformer = NewStepCreator.class,
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
                    return NewStepCreator.run(parseResult,new File("step-output.json"),"json",new NewStepCreator());
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
