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

package ai.kompile.cli.main.chat.skill;

import java.util.Set;

/**
 * Configuration for a skill — a reusable prompt template invoked as /skillname [args].
 * Skills guide the LLM through specific workflows (like commit, review, test).
 *
 * <p>Template uses {@code {{args}}} placeholder for user arguments.</p>
 */
public class SkillConfig {
    private final String name;
    private final String displayName;
    private final String description;
    private final String promptTemplate;
    private final Set<String> allowedTools; // null → inherit from current agent
    private final int maxSteps; // 0 → inherit from current agent
    private final String modelHint; // null → inherit from current agent
    private final boolean builtIn;
    private final String category;

    private SkillConfig(Builder builder) {
        this.name = builder.name;
        this.displayName = builder.displayName;
        this.description = builder.description;
        this.promptTemplate = builder.promptTemplate;
        this.allowedTools = builder.allowedTools;
        this.maxSteps = builder.maxSteps;
        this.modelHint = builder.modelHint;
        this.builtIn = builder.builtIn;
        this.category = builder.category;
    }

    public String getName() { return name; }
    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public String getPromptTemplate() { return promptTemplate; }
    public Set<String> getAllowedTools() { return allowedTools; }
    public int getMaxSteps() { return maxSteps; }
    public String getModelHint() { return modelHint; }
    public boolean isBuiltIn() { return builtIn; }
    public String getCategory() { return category; }

    /**
     * Expand the prompt template by replacing {{args}} with the provided arguments.
     */
    public String expandTemplate(String args) {
        String expanded = promptTemplate;
        if (args == null || args.isBlank()) {
            expanded = expanded.replace("{{args}}", "");
        } else {
            expanded = expanded.replace("{{args}}", args);
        }
        return expanded.trim();
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static class Builder {
        private final String name;
        private String displayName;
        private String description = "";
        private String promptTemplate = "";
        private Set<String> allowedTools = null;
        private int maxSteps = 0;
        private String modelHint = null;
        private boolean builtIn = false;
        private String category = "general";

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

        public Builder promptTemplate(String promptTemplate) {
            this.promptTemplate = promptTemplate;
            return this;
        }

        public Builder allowedTools(Set<String> allowedTools) {
            this.allowedTools = allowedTools;
            return this;
        }

        public Builder maxSteps(int maxSteps) {
            this.maxSteps = maxSteps;
            return this;
        }

        public Builder modelHint(String modelHint) {
            this.modelHint = modelHint;
            return this;
        }

        public Builder builtIn(boolean builtIn) {
            this.builtIn = builtIn;
            return this;
        }

        public Builder category(String category) {
            this.category = category;
            return this;
        }

        public SkillConfig build() {
            return new SkillConfig(this);
        }
    }
}
