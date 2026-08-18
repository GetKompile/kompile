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

package ai.kompile.cli.main.chat.roles;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import lombok.Builder;
import lombok.Value;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Value
@Builder
public class RoleConfig {

    String name;
    String displayName;
    String description;
    String category;
    String systemPrompt;
    @Builder.Default Set<String> enabledTools = Set.of("*");
    @Builder.Default Map<String, PermissionService.PermissionLevel> permissionOverrides = Map.of();
    @Builder.Default boolean canSpawnSubagents = true;
    @Builder.Default String modelHint = "default";
    /**
     * Provider-specific launch defaults used when this role is selected for a
     * delegated agent. The legacy {@code modelHint} remains prompt metadata.
     */
    @Builder.Default Map<String, RoleAgentDefaults> agentDefaults = Map.of();
    /**
     * Ordered list of preferred agents for rate-limit fallback when this role is active.
     * e.g. ["qwen", "claude", "gemini"]. Empty list means use the global default order.
     */
    @Builder.Default List<String> agentFallbackPriority = List.of();
    String sourceFile;
    @Builder.Default boolean isBuiltIn = false;

    public boolean isCustom() {
        return !isBuiltIn;
    }

    public RoleAgentDefaults getAgentDefaultsFor(String agentName) {
        if (agentName == null || agentDefaults == null) {
            return null;
        }
        return agentDefaults.get(agentName.trim().toLowerCase(java.util.Locale.ROOT));
    }

    public AgentConfig toAgentConfig() {
        return AgentConfig.builder(name)
                .displayName(displayName)
                .description(description)
                .systemPrompt(systemPrompt)
                .enabledTools(enabledTools)
                .permissionOverrides(permissionOverrides)
                .isSubagent(false)
                .canSpawnSubagents(canSpawnSubagents)
                .modelHint(modelHint)
                .isCustom(!isBuiltIn)
                .build();
    }

    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(name).append("\n");
        sb.append("display_name: ").append(displayName).append("\n");
        sb.append("description: ").append(description).append("\n");
        sb.append("category: ").append(category).append("\n");
        sb.append("model: ").append(modelHint).append("\n");
        sb.append("can_spawn: ").append(canSpawnSubagents).append("\n");

        if (agentDefaults != null && !agentDefaults.isEmpty()) {
            agentDefaults.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        String prefix = "agent_defaults." + entry.getKey() + ".";
                        RoleAgentDefaults defaults = entry.getValue();
                        if (defaults == null || defaults.isEmpty()) {
                            return;
                        }
                        if (defaults.getModel() != null) {
                            sb.append(prefix).append("model: ")
                                    .append(defaults.getModel()).append("\n");
                        }
                        if (defaults.getDefaultThinking() != null) {
                            sb.append(prefix).append("thinking.default: ")
                                    .append(defaults.getDefaultThinking()).append("\n");
                        }
                        defaults.getThinkingByModel().forEach((model, thinking) ->
                                sb.append(prefix).append("thinking.models.")
                                        .append(model).append(": ").append(thinking).append("\n"));
                    });
        }

        if (!enabledTools.contains("*")) {
            sb.append("tools: ").append(String.join(", ", enabledTools)).append("\n");
        }

        if (agentFallbackPriority != null && !agentFallbackPriority.isEmpty()) {
            sb.append("agent_fallback: ").append(String.join(", ", agentFallbackPriority)).append("\n");
        }

        if (!permissionOverrides.isEmpty()) {
            Set<String> denyTools = new LinkedHashSet<>();
            for (Map.Entry<String, PermissionService.PermissionLevel> entry : permissionOverrides.entrySet()) {
                if (entry.getValue() == PermissionService.PermissionLevel.DENY) {
                    denyTools.add(entry.getKey());
                }
            }
            if (!denyTools.isEmpty()) {
                sb.append("deny_tools: ").append(String.join(", ", denyTools)).append("\n");
            }
        }

        sb.append("---\n");
        sb.append(systemPrompt);
        return sb.toString();
    }
}
