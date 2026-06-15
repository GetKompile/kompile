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

import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates a skills.md markdown document from the skill registry.
 * This document is injected into agents' system prompts so they
 * know about available kompile skills and how to invoke them.
 *
 * <p>The generated markdown follows a format similar to Claude Code's
 * {@code <system-reminder>} skill listings, providing the agent with
 * skill names, descriptions, and usage patterns.</p>
 */
public class SkillsMarkdownGenerator {

    private SkillsMarkdownGenerator() {}

    /**
     * Generate a complete skills.md markdown document from the given skills.
     *
     * @param skills the collection of skills to document
     * @return the markdown content, or null if no skills are available
     */
    public static String generate(Collection<SkillConfig> skills) {
        if (skills == null || skills.isEmpty()) return null;

        StringBuilder md = new StringBuilder();
        md.append("# Available Kompile Skills\n\n");
        md.append("These are kompile skills — reusable prompt templates loaded via the `mcp__kompile__skill_manager` MCP tool.\n\n");
        md.append("**IMPORTANT:** These are NOT native slash commands. Do NOT use the Skill tool to invoke them. ");
        md.append("To use a skill, call the MCP tool:\n");
        md.append("```\n");
        md.append("mcp__kompile__skill_manager action=apply_skill name=<skill-name> args=\"<your instructions>\"\n");
        md.append("```\n");
        md.append("This returns the expanded prompt template. Follow those instructions exactly.\n\n");
        md.append("When a user says `/skillname` or asks to use a skill, use `mcp__kompile__skill_manager` — never the native Skill tool.\n\n");

        // Group by category
        Map<String, List<SkillConfig>> byCategory = skills.stream()
                .collect(Collectors.groupingBy(
                        s -> s.getCategory() != null ? s.getCategory() : "general",
                        LinkedHashMap::new,
                        Collectors.toList()));

        for (Map.Entry<String, List<SkillConfig>> entry : byCategory.entrySet()) {
            String category = entry.getKey();
            List<SkillConfig> categorySkills = entry.getValue();

            md.append("## ").append(capitalize(category)).append("\n\n");

            for (SkillConfig skill : categorySkills) {
                md.append("- **`").append(skill.getName()).append("`**");
                if (skill.getDisplayName() != null
                        && !skill.getDisplayName().equals(skill.getName())) {
                    md.append(" (").append(skill.getDisplayName()).append(")");
                }
                md.append(": ").append(skill.getDescription()).append("\n");

                // Show allowed tools if restricted
                if (skill.getAllowedTools() != null
                        && !skill.getAllowedTools().isEmpty()
                        && !skill.getAllowedTools().contains("*")) {
                    md.append("  - Tools: ")
                            .append(String.join(", ", skill.getAllowedTools()))
                            .append("\n");
                }

                // Show if it accepts arguments
                if (skill.getPromptTemplate() != null
                        && skill.getPromptTemplate().contains("{{args}}")) {
                    md.append("  - Accepts arguments: `mcp__kompile__skill_manager action=apply_skill name=")
                            .append(skill.getName())
                            .append(" args=\"<your instructions>\"`\n");
                }
            }
            md.append("\n");
        }

        return md.toString().stripTrailing() + "\n";
    }

    /**
     * Generate a compact skills listing suitable for appending to existing prompts.
     * Shorter than the full document — just names and one-line descriptions.
     *
     * @param skills the collection of skills
     * @return compact listing, or null if empty
     */
    public static String generateCompact(Collection<SkillConfig> skills) {
        if (skills == null || skills.isEmpty()) return null;

        StringBuilder md = new StringBuilder();
        md.append("Available skills (invoke with `/skillname [args]`):\n");
        for (SkillConfig skill : skills) {
            md.append("- /").append(skill.getName())
                    .append(": ").append(skill.getDescription()).append("\n");
        }
        return md.toString();
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
}
