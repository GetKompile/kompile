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
    @Builder.Default int maxSteps = 50;
    @Builder.Default boolean canSpawnSubagents = true;
    @Builder.Default String modelHint = "default";
    String sourceFile;
    @Builder.Default boolean isBuiltIn = false;

    public boolean isCustom() {
        return !isBuiltIn;
    }

    public AgentConfig toAgentConfig() {
        return AgentConfig.builder(name)
                .displayName(displayName)
                .description(description)
                .systemPrompt(systemPrompt)
                .enabledTools(enabledTools)
                .permissionOverrides(permissionOverrides)
                .isSubagent(false)
                .maxSteps(maxSteps)
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
        sb.append("max_steps: ").append(maxSteps).append("\n");
        sb.append("can_spawn: ").append(canSpawnSubagents).append("\n");

        if (!enabledTools.contains("*")) {
            sb.append("tools: ").append(String.join(", ", enabledTools)).append("\n");
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
