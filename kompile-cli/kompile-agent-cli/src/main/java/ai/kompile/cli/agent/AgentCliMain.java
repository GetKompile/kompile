/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.agent;

import ai.kompile.cli.agent.spin.SpinCommand;
import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "kompile-agent",
        subcommands = {
                AgentWorkflowCommand.class,
                AgentTaskCommand.class,
                AgentDefaultsCommand.class,
                AgentSessionCommand.class,
                AgentChatCommand.class,
                AgentBundleValidateCommand.class,
                AgentBundleInspectCommand.class,
                AgentBundlePackCommand.class,
                AgentBundleRunCommand.class,
                AgentBundleToolsCommand.class,
                AgentBundleServeCommand.class,
                SpinCommand.class,
                AgentMonitorCommand.class,
                AgentLogsCommand.class,
                SubprocessLogsCommand.class,
                ChatImportCommand.class,
                ProcessCommand.class,
                ProcessDiscoveryCommand.class,
                RulesCommand.class
        },
        mixinStandardHelpOptions = true,
        usageHelpAutoWidth = true,
        description = "Agent and workflow management: spins, packaged agents, workflow, task, defaults, session, chat, monitor, logs, chat import, process discovery, and rules. Channel credentials are managed by `kompile auth channel`.")
public class AgentCliMain implements Callable<Integer> {

    @Override
    public Integer call() {
        System.err.println("No command specified. Showing help:\n");
        new CommandLine(this).usage(System.err);
        return 0;
    }

    public static void main(String... args) {
        int exitCode = new CommandLine(new AgentCliMain()).execute(args);
        System.exit(exitCode);
    }
}
