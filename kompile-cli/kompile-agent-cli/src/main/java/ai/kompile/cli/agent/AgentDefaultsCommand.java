/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.agent;

import ai.kompile.cli.common.agent.AgentDefaultsStore;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Configures the same per-agent launch defaults consumed by MCP delegation tools.
 */
@CommandLine.Command(
        name = "defaults",
        aliases = {"agent-defaults"},
        mixinStandardHelpOptions = true,
        description = "Configure model and per-model thinking defaults for Codex, Claude, or OpenCode.")
public class AgentDefaultsCommand implements Callable<Integer> {

    @CommandLine.Option(names = "--agent", required = true,
            description = "Target agent: codex, claude, opencode")
    private String agent;

    @CommandLine.Option(names = {"--model", "-m"},
            description = "Default model for this agent")
    private String model;

    @CommandLine.Option(names = {"--thinking", "--effort"},
            description = "Thinking/effort value to persist")
    private String thinking;

    @CommandLine.Option(names = "--thinking-model",
            description = "Exact model to associate with --thinking (defaults to --model)")
    private String thinkingModel;

    @CommandLine.Option(names = "--global",
            description = "Write user defaults under ~/.kompile/config instead of this project")
    private boolean globalConfig;

    @CommandLine.Option(names = {"--project-dir", "-d"},
            description = "Project directory to configure (default: current directory)")
    private String projectDir;

    @CommandLine.Option(names = "--show",
            description = "Show the effective selection without changing configuration")
    private boolean showOnly;

    @Override
    public Integer call() {
        String selectedAgent = AgentDefaultsStore.normalizeSupportedAgent(agent);
        if (selectedAgent == null) {
            System.err.println("Unsupported agent '" + agent + "'. Expected one of: "
                    + String.join(", ", AgentDefaultsStore.SUPPORTED_AGENTS));
            return 2;
        }
        if (thinkingModel != null && !thinkingModel.isBlank()
                && (thinking == null || thinking.isBlank())) {
            System.err.println("--thinking-model requires --thinking");
            return 2;
        }

        Path projectRoot = projectDir == null || projectDir.isBlank()
                ? Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
                : Path.of(projectDir).toAbsolutePath().normalize();
        Path configPath = globalConfig
                ? AgentDefaultsStore.userConfigPath()
                : AgentDefaultsStore.projectConfigPath(projectRoot);
        boolean hasUpdate = (model != null && !model.isBlank())
                || (thinking != null && !thinking.isBlank());
        if (showOnly && hasUpdate) {
            System.err.println("--show cannot be combined with --model or --thinking");
            return 2;
        }

        try {
            if (!showOnly && hasUpdate) {
                String selectedThinkingModel = thinkingModel;
                if ((selectedThinkingModel == null || selectedThinkingModel.isBlank())
                        && model != null && !model.isBlank()
                        && thinking != null && !thinking.isBlank()) {
                    selectedThinkingModel = model;
                }
                AgentDefaultsStore.save(
                        configPath, selectedAgent, model, thinking, selectedThinkingModel);
                System.out.println("Saved " + selectedAgent + " defaults to " + configPath);
            }

            AgentDefaultsStore.Selection effective = AgentDefaultsStore.resolve(
                    selectedAgent, projectRoot, null, null);
            System.out.println("Effective " + selectedAgent + " model: "
                    + display(effective.model()));
            System.out.println("Effective " + selectedAgent + " thinking: "
                    + display(effective.thinking()));
            System.out.println("Precedence: explicit MCP/CLI > project > user > native CLI");
            if (!hasUpdate && !showOnly) {
                System.out.println("Pass --model and/or --thinking to update these defaults.");
            }
            return 0;
        } catch (Exception e) {
            System.err.println("Could not configure agent defaults: " + e.getMessage());
            return 1;
        }
    }

    private static String display(String value) {
        return value == null || value.isBlank() ? "native default" : value;
    }
}
