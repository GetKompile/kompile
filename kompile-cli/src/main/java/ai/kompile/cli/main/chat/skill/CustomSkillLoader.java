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

import ai.kompile.cli.common.KompileHome;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Loads custom skill definitions from Markdown files with frontmatter.
 *
 * <h3>Search locations (in order, later overrides earlier):</h3>
 * <ol>
 *   <li>{@code ~/.kompile/skills/} — user-scoped skills</li>
 *   <li>{@code .kompile/skills/} — project-scoped skills (relative to working directory)</li>
 * </ol>
 *
 * <h3>File format:</h3>
 * <pre>
 * ---
 * name: deploy
 * description: Deploy the current branch to staging
 * category: devops
 * tools: bash, read, grep, glob
 * max_steps: 30
 * model: default
 * ---
 * Deploy the current branch to staging. {{args}}
 *
 * Follow these steps:
 * 1. ...
 * </pre>
 */
public class CustomSkillLoader {

    private final Path workingDirectory;

    public CustomSkillLoader(Path workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    /**
     * Load all custom skills from user and project directories.
     * Project-scoped skills override user-scoped skills with the same name.
     *
     * @return map of skill name -> SkillConfig
     */
    public Map<String, SkillConfig> loadAll() {
        Map<String, SkillConfig> skills = new LinkedHashMap<>();

        // 1. User-scoped: ~/.kompile/skills/
        Path userDir = KompileHome.homeDirectory().toPath().resolve("skills");
        loadFromDirectory(userDir, skills);

        // 2. Project-scoped: .kompile/skills/ (relative to working directory)
        Path projectDir = workingDirectory.resolve(".kompile").resolve("skills");
        loadFromDirectory(projectDir, skills);

        return skills;
    }

    private void loadFromDirectory(Path dir, Map<String, SkillConfig> skills) {
        if (!Files.isDirectory(dir)) return;

        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".md"))
                    .sorted()
                    .forEach(file -> {
                        try {
                            SkillConfig skill = parseSkillFile(file);
                            if (skill != null) {
                                skills.put(skill.getName(), skill);
                            }
                        } catch (Exception e) {
                            System.err.println("Warning: Failed to load skill from " + file + ": " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            // Directory not accessible, skip
        }
    }

    /**
     * Parse a single skill definition file.
     */
    SkillConfig parseSkillFile(Path file) throws IOException {
        String content = Files.readString(file);

        // Split frontmatter from body
        if (!content.startsWith("---")) {
            // No frontmatter — use filename as name, entire content as prompt template
            String name = file.getFileName().toString().replaceFirst("\\.md$", "");
            return SkillConfig.builder(name)
                    .displayName(name)
                    .description("Custom skill from " + file.getFileName())
                    .promptTemplate(content.trim())
                    .builtIn(false)
                    .build();
        }

        int endIdx = content.indexOf("---", 3);
        if (endIdx < 0) {
            String name = file.getFileName().toString().replaceFirst("\\.md$", "");
            return SkillConfig.builder(name)
                    .displayName(name)
                    .promptTemplate(content.substring(3).trim())
                    .builtIn(false)
                    .build();
        }

        String frontmatter = content.substring(3, endIdx).trim();
        String body = content.substring(endIdx + 3).trim();

        Map<String, String> fields = parseFrontmatter(frontmatter);

        String name = fields.getOrDefault("name",
                file.getFileName().toString().replaceFirst("\\.md$", ""));
        String description = fields.getOrDefault("description", "Custom skill: " + name);
        String category = fields.getOrDefault("category", "custom");
        String modelHint = fields.getOrDefault("model", null);
        int maxSteps = parseIntOrDefault(fields.get("max_steps"), 0);

        // Parse tools
        Set<String> allowedTools = null;
        String toolsStr = fields.get("tools");
        if (toolsStr != null && !toolsStr.isBlank()) {
            if ("*".equals(toolsStr.trim())) {
                allowedTools = Set.of("*");
            } else {
                allowedTools = new LinkedHashSet<>();
                for (String t : toolsStr.split(",")) {
                    String trimmed = t.trim();
                    if (!trimmed.isEmpty()) allowedTools.add(trimmed);
                }
            }
        }

        return SkillConfig.builder(name)
                .displayName(fields.getOrDefault("display_name", name))
                .description(description)
                .promptTemplate(body)
                .category(category)
                .modelHint(modelHint)
                .maxSteps(maxSteps)
                .allowedTools(allowedTools)
                .builtIn(false)
                .build();
    }

    private Map<String, String> parseFrontmatter(String frontmatter) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : frontmatter.split("\n")) {
            int colonIdx = line.indexOf(':');
            if (colonIdx > 0) {
                String key = line.substring(0, colonIdx).trim().toLowerCase();
                String value = line.substring(colonIdx + 1).trim();
                fields.put(key, value);
            }
        }
        return fields;
    }

    private int parseIntOrDefault(String value, int defaultValue) {
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
