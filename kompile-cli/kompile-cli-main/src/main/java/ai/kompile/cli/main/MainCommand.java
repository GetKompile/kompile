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

package ai.kompile.cli.main;

import ai.kompile.cli.main.a2a.A2ACommand;
import ai.kompile.cli.main.app.AppCommand;
import ai.kompile.cli.main.chat.ChatCommand;
import ai.kompile.cli.main.chat.EnforcerCommand;
import ai.kompile.cli.main.chat.LiteChatCommand;
import ai.kompile.cli.main.chat.PassthroughCommand;
import ai.kompile.cli.main.chat.ResumeAllCommand;
import ai.kompile.cli.main.chat.ResumeCommand;
import ai.kompile.cli.main.chat.SessionCommand;
import ai.kompile.cli.main.chat.exec.ExecCommand;
import ai.kompile.cli.main.chat.harness.HarnessCommand;
import ai.kompile.cli.main.chat.harness.eval.EvalCommand;
import ai.kompile.cli.main.chat.skill.SkillsCommand;
import ai.kompile.cli.main.codeindex.CodeIndexCommand;
import ai.kompile.cli.main.coordination.EditCoordinatorCommand;
import ai.kompile.cli.main.mcp.McpStdioCommand;
import ai.kompile.cli.main.build.BuildMain;
import ai.kompile.cli.main.build.DeployCommand;
import ai.kompile.cli.main.build.InitProjectCommand;
import ai.kompile.cli.main.build.KompileApplicationBuilder;
import ai.kompile.cli.main.config.ConfigMain;
import ai.kompile.cli.main.configure.ConfigureCommand;
import ai.kompile.cli.main.graph.GraphCommand;
import ai.kompile.cli.main.install.InstallMain;
import ai.kompile.cli.main.knowledge.KnowledgeCommand;
import ai.kompile.cli.main.manage.ManageComponents;
import ai.kompile.cli.main.pipeline.PipelineMain;
import ai.kompile.cli.main.project.ProjectCommand;
import ai.kompile.cli.main.run.RunCommand;
import ai.kompile.cli.main.sdk.SdkMain;
import ai.kompile.cli.main.serve.DaemonCommand;
import ai.kompile.cli.main.serve.ServeCommand;
import ai.kompile.cli.main.uninstall.UnInstallMain;
import ai.kompile.cli.main.cloud.CloudCommand;
import ai.kompile.cli.main.web.WebCommand;
import ai.kompile.cli.plugin.api.CliCommandRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.util.ServiceLoader;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "kompile",
        subcommands = {
                Info.class,
                Bootstrap.class,
                Init.class,
                ConfigureCommand.class,
                ConfigMain.class,
                BuildMain.class,
                KompileApplicationBuilder.BuildKompileAppCommand.class,
                InitProjectCommand.class,
                DeployCommand.class,
                ProjectCommand.class,
                InstallMain.class,
                UnInstallMain.class,
                ManageComponents.class,
                SdkMain.class,
                PipelineMain.class,
                // Chat and agent commands
                ChatCommand.class,
                ExecCommand.class,
                LiteChatCommand.class,
                SessionCommand.class,
                PassthroughCommand.class,
                ResumeCommand.class,
                ResumeAllCommand.class,
                EnforcerCommand.class,
                HarnessCommand.class,
                EvalCommand.class,
                SkillsCommand.class,
                // App interaction commands
                AppCommand.class,
                A2ACommand.class,
                GraphCommand.class,
                KnowledgeCommand.class,
                // Infrastructure commands
                McpStdioCommand.class,
                ServeCommand.class,
                DaemonCommand.class,
                CodeIndexCommand.class,
                EditCoordinatorCommand.class,
                WebCommand.class,
                RunCommand.class,
                CloudCommand.class
        },
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        usageHelpAutoWidth = true,
        description = "Kompile CLI: Streamline AI/ML Application Development and Deployment")
public class MainCommand implements Callable<Integer> {
    private static final Logger log = LoggerFactory.getLogger(MainCommand.class);


    public MainCommand() {
    }


    @Override
    public Integer call() throws Exception {
        File kompileDir = Info.homeDirectory();
        if(!kompileDir.exists()) {
            System.err.println("Kompile directory not initialized. Please run 'kompile configure init' first.");
            return 1;
        }
        System.err.println("No command specified. Showing help:\n");
        new CommandLine(new MainCommand()).usage(System.err);
        return 0;
    }



    public static void main(String...args) {
        CommandLine commandLine = new CommandLine(new MainCommand());

        // Discover and register plugin commands via ServiceLoader
        ServiceLoader<CliCommandRegistrar> registrars = ServiceLoader.load(CliCommandRegistrar.class);
        for (CliCommandRegistrar registrar : registrars) {
            try {
                registrar.registerCommands(commandLine);
            } catch (Exception e) {
                log.warn("Failed to register plugin commands from {}: {}", registrar.getClass().getName(), e.getMessage());
            }
        }

        // Add delegation subcommands for federated CLIs (kompile-model, kompile-agent, kompile-lite)
        commandLine.addSubcommand("model", new DelegatingCommand("kompile-model",
                "Model lifecycle management (list, download, convert, export, import)."));
        commandLine.addSubcommand("agent", new DelegatingCommand("kompile-agent",
                "Agent and workflow management (workflow, task, channel, session, chat)."));
        commandLine.addSubcommand("lite", new DelegatingCommand("kompile-lite",
                "Kompile Lite self-contained chat + RAG + Graph RAG application."));

        int exitCode;
        try {
            exitCode = commandLine.execute(args);
        } catch (NoClassDefFoundError e) {
            // Shade plugin classloader can lose picocli inner classes during shutdown
            exitCode = 0;
        }
        // Always terminate the JVM explicitly — do NOT fall through to a bare return
        // on exit code 0. Long-running commands (mcp-stdio, chat) start non-daemon
        // background threads (file watcher, ambient gardener, semantic memory, async
        // tool executor). When such a command finishes cleanly and main() merely
        // returns, those threads keep the process — and its ~200 MB heap — alive
        // forever. A day of agent sessions then leaves dozens of orphaned JVMs that
        // drive the box into swap, so even a trivial local tool call (e.g. grep)
        // stalls long enough for the MCP client to time out and "disconnect".
        // System.exit() still runs the registered shutdown hooks (which persist
        // session/coordination state) before reaping the process.
        System.exit(exitCode);
    }

    /**
     * A command that delegates to an external CLI binary found on PATH.
     * For example, "kompile app start" finds and execs "kompile-app start".
     */
    @CommandLine.Command(mixinStandardHelpOptions = true)
    static class DelegatingCommand implements Callable<Integer> {
        private final String binaryName;

        @CommandLine.Unmatched
        private String[] remainingArgs;

        DelegatingCommand(String binaryName, String description) {
            this.binaryName = binaryName;
        }

        @Override
        public Integer call() throws Exception {
            // Search for the binary on PATH
            String path = System.getenv("PATH");
            if (path != null) {
                for (String dir : path.split(File.pathSeparator)) {
                    File candidate = new File(dir, binaryName);
                    if (candidate.canExecute()) {
                        return execBinary(candidate);
                    }
                    // On Windows, try with .exe
                    File candidateExe = new File(dir, binaryName + ".exe");
                    if (candidateExe.canExecute()) {
                        return execBinary(candidateExe);
                    }
                }
            }

            // Also check ~/.kompile/bin/
            File kompileBin = new File(Info.homeDirectory(), "bin/" + binaryName);
            if (kompileBin.canExecute()) {
                return execBinary(kompileBin);
            }

            System.err.println("'" + binaryName + "' not found on PATH or in ~/.kompile/bin/.");
            System.err.println("Install it with: kompile install " + binaryName.replace("kompile-", ""));
            return 1;
        }

        private int execBinary(File binary) throws IOException, InterruptedException {
            String[] cmd;
            if (remainingArgs != null && remainingArgs.length > 0) {
                cmd = new String[remainingArgs.length + 1];
                cmd[0] = binary.getAbsolutePath();
                System.arraycopy(remainingArgs, 0, cmd, 1, remainingArgs.length);
            } else {
                cmd = new String[]{binary.getAbsolutePath(), "--help"};
            }

            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .inheritIO();
            Process process = pb.start();
            try {
                return process.waitFor();
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                return 1;
            }
        }
    }
}
