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

package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.main.chat.permission.PermissionService;

import java.util.Map;
import java.util.Set;

/**
 * Configuration for an agent (primary or subagent).
 *
 * Primary agents (user-facing):
 *   - coder: Full tool access for development work
 *   - planner: Read-only, edit tools denied, bash set to ask
 *
 * Subagents (invoked by primary agents via TaskTool):
 *   - general: Full tool access for multi-step delegation
 *   - explorer: Read-only, restricted to grep/glob/list/bash/webfetch
 */
public class AgentConfig {
    private final String name;
    private final String displayName;
    private final String description;
    private final String systemPrompt;
    private final Set<String> enabledTools;
    private final Map<String, PermissionService.PermissionLevel> permissionOverrides;
    private final boolean isSubagent;
    private final int maxSteps;
    private final boolean canSpawnSubagents;
    private final String modelHint; // e.g. "fast", "default", "powerful" - guides model selection
    private final boolean isCustom; // loaded from .kompile/agents/ file
    private final String roleName; // optional role to apply to this agent

    private AgentConfig(Builder builder) {
        this.name = builder.name;
        this.displayName = builder.displayName;
        this.description = builder.description;
        this.systemPrompt = builder.systemPrompt;
        this.enabledTools = builder.enabledTools;
        this.permissionOverrides = builder.permissionOverrides;
        this.isSubagent = builder.isSubagent;
        this.maxSteps = builder.maxSteps;
        this.canSpawnSubagents = builder.canSpawnSubagents;
        this.modelHint = builder.modelHint;
        this.isCustom = builder.isCustom;
        this.roleName = builder.roleName;
    }

    public String getName() { return name; }
    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public String getSystemPrompt() { return systemPrompt; }
    public Set<String> getEnabledTools() { return enabledTools; }
    public Map<String, PermissionService.PermissionLevel> getPermissionOverrides() { return permissionOverrides; }
    public boolean isSubagent() { return isSubagent; }
    public int getMaxSteps() { return maxSteps; }
    public boolean canSpawnSubagents() { return canSpawnSubagents; }
    public String getModelHint() { return modelHint; }
    public boolean isCustom() { return isCustom; }
    public String getRoleName() { return roleName; }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static class Builder {
        private final String name;
        private String displayName;
        private String description = "";
        private String systemPrompt = "";
        private Set<String> enabledTools = Set.of("*");
        private Map<String, PermissionService.PermissionLevel> permissionOverrides = Map.of();
        private boolean isSubagent = false;
        private int maxSteps = 50;
        private boolean canSpawnSubagents = false;
        private String modelHint = "default";
        private boolean isCustom = false;
        private String roleName = null;

        public Builder(String name) {
            this.name = name;
            this.displayName = name;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder enabledTools(Set<String> enabledTools) {
            this.enabledTools = enabledTools;
            return this;
        }

        public Builder permissionOverrides(Map<String, PermissionService.PermissionLevel> overrides) {
            this.permissionOverrides = overrides;
            return this;
        }

        public Builder isSubagent(boolean isSubagent) {
            this.isSubagent = isSubagent;
            return this;
        }

        public Builder maxSteps(int maxSteps) {
            this.maxSteps = maxSteps;
            return this;
        }

        public Builder canSpawnSubagents(boolean canSpawnSubagents) {
            this.canSpawnSubagents = canSpawnSubagents;
            return this;
        }

        public Builder modelHint(String modelHint) {
            this.modelHint = modelHint;
            return this;
        }

        public Builder isCustom(boolean isCustom) {
            this.isCustom = isCustom;
            return this;
        }

        public Builder roleName(String roleName) {
            this.roleName = roleName;
            return this;
        }

        public AgentConfig build() {
            return new AgentConfig(this);
        }
    }
}
