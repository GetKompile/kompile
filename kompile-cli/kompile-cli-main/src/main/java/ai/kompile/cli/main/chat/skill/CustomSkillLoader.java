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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * model: default
 * ---
 * Deploy the current branch to staging. {{args}}
 *
 * Follow these steps:
 * 1. ...
 * </pre>
 */
public class CustomSkillLoader {

    private static final Logger log = LoggerFactory.getLogger(CustomSkillLoader.class);
    private static final long MAX_SKILL_BYTES = 1_048_576L;

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

        // Provider-shared skills are available to normal Kompile chat too. Explicit
        // Kompile definitions load later and retain override precedence.
        Path userHome = Path.of(System.getProperty("user.home"));
        Map<String, SkillConfig> providerSkills = new LinkedHashMap<>();
        for (Path providerDir : List.of(
                userHome.resolve(".claude/skills"),
                userHome.resolve(".codex/skills"),
                userHome.resolve(".agents/skills"),
                userHome.resolve(".gemini/skills"),
                userHome.resolve(".config/opencode/skills"),
                userHome.resolve(".qwen/skills"))) {
            loadFromDirectory(providerDir, providerSkills, false);
        }
        skills.putAll(providerSkills);

        // User-scoped Kompile skills override provider-shared definitions.
        Path userDir = KompileHome.homeDirectory().toPath().resolve("skills");
        loadFromDirectory(userDir, skills, true);

        // Project provider skills override user definitions, while collisions between
        // providers keep the first deterministic definition.
        Map<String, SkillConfig> projectProviderSkills = new LinkedHashMap<>();
        for (String provider : List.of(".claude", ".codex", ".agents", ".gemini", ".opencode", ".qwen")) {
            loadFromDirectory(workingDirectory.resolve(provider).resolve("skills"),
                    projectProviderSkills, false);
        }
        skills.putAll(projectProviderSkills);

        // Project-scoped Kompile skills have the highest precedence.
        Path projectDir = workingDirectory.resolve(".kompile").resolve("skills");
        loadFromDirectory(projectDir, skills, true);

        return skills;
    }

    private void loadFromDirectory(Path dir, Map<String, SkillConfig> skills, boolean overwrite) {
        if (SkillPathPolicy.hasSymlinkComponent(dir)
                || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return;
        Path normalizedDir = dir.toAbsolutePath().normalize();

        try (Stream<Path> stream = Files.list(dir)) {
            stream.map(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                            ? path.resolve("SKILL.md") : path)
                    .map(path -> path.toAbsolutePath().normalize())
                    .filter(path -> path.startsWith(normalizedDir))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> p.toString().endsWith(".md"))
                    .sorted()
                    .forEach(file -> {
                        try {
                            SkillConfig skill = parseSkillFile(file);
                            if (skill == null || !SkillRegistry.isInvokableName(skill.getName())) {
                                log.warn("Skipping skill with invalid name from {}", file);
                            } else {
                                String key = skill.getName().toLowerCase(Locale.ROOT);
                                if (overwrite) {
                                    skills.put(key, skill);
                                } else if (skills.putIfAbsent(key, skill) != null) {
                                    log.debug("Ignoring duplicate provider skill '{}' from {}",
                                            skill.getName(), file);
                                }
                            }
                        } catch (Exception e) {
                            log.warn("Failed to load skill from {}: {}", file, e.getMessage(), e);
                        }
                    });
        } catch (IOException e) {
            // Directory not accessible, skip
        }
    }

    /**
     * Parse a single skill definition file.
     */
    public SkillConfig parseSkillFile(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Skill must be a regular non-symlink file: " + file);
        }
        if (Files.size(file) > MAX_SKILL_BYTES) {
            throw new IOException("Skill exceeds " + MAX_SKILL_BYTES + " bytes: " + file);
        }
        String content;
        try (var input = Files.newInputStream(file, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
            content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

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

}
