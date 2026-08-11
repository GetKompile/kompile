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
package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.agent.AgentDefaultsStore;
import ai.kompile.cli.main.chat.roles.RoleAgentDefaults;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Resolves shared agent defaults and translates them to provider-native CLI flags.
 *
 * <p>Resolution is explicit launch override, role default, nearest project
 * default, user default, then the external CLI's native default.
 */
public final class AgentLaunchDefaults {

    public static final String CONFIG_FILE = AgentDefaultsStore.CONFIG_FILE;
    public static final List<String> SUPPORTED_AGENTS = AgentDefaultsStore.SUPPORTED_AGENTS;

    private AgentLaunchDefaults() {
    }

    public enum LaunchMode {
        INTERACTIVE,
        MANAGED
    }

    public static final class Selection {
        private final String model;
        private final String thinking;

        Selection(String model, String thinking) {
            this.model = clean(model);
            this.thinking = clean(thinking);
        }

        public String model() {
            return model;
        }

        public String thinking() {
            return thinking;
        }

        @Override
        public String toString() {
            return "Selection{model=" + display(model) + ", thinking=" + display(thinking) + "}";
        }
    }

    public static Selection resolve(String agentName,
                                    Path workingDirectory,
                                    String explicitModel,
                                    String explicitThinking) {
        return resolve(agentName, workingDirectory, explicitModel, explicitThinking, null);
    }

    /**
     * Resolve model and thinking independently so an explicit model can still
     * select a model-specific thinking value from the active role.
     */
    public static Selection resolve(String agentName,
                                    Path workingDirectory,
                                    String explicitModel,
                                    String explicitThinking,
                                    RoleAgentDefaults roleDefaults) {
        String agent = normalizeSupportedAgent(agentName);
        RoleAgentDefaults selectedRoleDefaults = agent != null ? roleDefaults : null;

        String model = clean(explicitModel);
        if (model == null && selectedRoleDefaults != null) {
            model = selectedRoleDefaults.getModel();
        }
        if (model == null && agent != null) {
            model = configuredModel(agent, workingDirectory).orElse(null);
        }

        String thinking = clean(explicitThinking);
        if (thinking == null && selectedRoleDefaults != null) {
            thinking = selectedRoleDefaults.resolveThinking(model);
        }
        if (thinking == null && agent != null) {
            thinking = configuredThinking(agent, model, workingDirectory).orElse(null);
        }
        return new Selection(model, thinking);
    }

    public static Optional<String> configuredModel(String agentName, Path workingDirectory) {
        return AgentDefaultsStore.configuredModel(agentName, workingDirectory);
    }

    public static Optional<String> configuredThinking(String agentName,
                                                      String model,
                                                      Path workingDirectory) {
        return AgentDefaultsStore.configuredThinking(agentName, model, workingDirectory);
    }

    /**
     * Build provider-native selection flags. For managed OpenCode launches these
     * flags belong after the {@code run} subcommand. OpenCode's interactive TUI
     * has no {@code --variant} option, so only its model is emitted there.
     */
    public static List<String> commandArguments(String agentName,
                                                String model,
                                                String thinking,
                                                LaunchMode launchMode) {
        String agent = AgentFlagOverrides.agentKey(agentName);
        String selectedModel = cleanCliValue(model, "model");
        String selectedThinking = cleanCliValue(thinking, "thinking");
        List<String> arguments = new ArrayList<>();

        if (selectedModel != null) {
            arguments.add("--model");
            arguments.add(selectedModel);
        }

        if (selectedThinking == null) {
            return List.copyOf(arguments);
        }

        switch (agent) {
            case "codex" -> {
                arguments.add("-c");
                arguments.add("model_reasoning_effort=\"" + escapeToml(selectedThinking) + "\"");
            }
            case "claude" -> {
                arguments.add("--effort");
                arguments.add(selectedThinking);
            }
            case "opencode" -> {
                if (launchMode == LaunchMode.MANAGED) {
                    arguments.add("--variant");
                    arguments.add(selectedThinking);
                }
            }
            default -> {
                // Other CLIs retain model support but do not share a safe thinking flag contract.
            }
        }
        return List.copyOf(arguments);
    }

    /**
     * Returns provider-native arguments for attaching an interactive process to
     * an existing native session. Keeping this mapping beside the other launch
     * defaults prevents managed and direct resume paths from drifting.
     */
    public static List<String> resumeArguments(String agentName, String sessionId) {
        String id = cleanCliValue(sessionId, "session id");
        if (id == null) {
            return List.of();
        }
        return switch (AgentFlagOverrides.agentKey(agentName)) {
            case "codex" -> List.of("resume", id);
            case "claude", "qwen", "gemini" -> List.of("--resume", id);
            case "opencode", "pi" -> List.of("--session", id);
            default -> List.of();
        };
    }

    public static boolean isSupportedAgent(String agentName) {
        return AgentDefaultsStore.isSupportedAgent(agentName);
    }

    public static String normalizeSupportedAgent(String agentName) {
        return AgentDefaultsStore.normalizeSupportedAgent(agentName);
    }

    public static Path userConfigPath() {
        return AgentDefaultsStore.userConfigPath();
    }

    public static Path projectConfigPath(Path projectDirectory) {
        return AgentDefaultsStore.projectConfigPath(projectDirectory);
    }

    public static void save(Path configPath,
                            String agentName,
                            String model,
                            String thinking,
                            String thinkingModel) throws IOException {
        AgentDefaultsStore.save(configPath, agentName, model, thinking, thinkingModel);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String cleanCliValue(String value, String field) {
        String cleaned = clean(value);
        if (cleaned == null) {
            return null;
        }
        for (int i = 0; i < cleaned.length(); i++) {
            if (Character.isISOControl(cleaned.charAt(i))) {
                throw new IllegalArgumentException(
                        "Agent " + field + " must not contain control characters");
            }
        }
        return cleaned;
    }

    private static String escapeToml(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String display(String value) {
        return value == null ? "native default" : value;
    }
}
