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

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.main.chat.agent.AgentLaunchDefaults;
import ai.kompile.cli.main.chat.permission.PermissionService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Loads role definitions from Markdown files with YAML-like frontmatter.
 * <p>
 * Roles are stored at:
 * - User-scoped: {@code ~/.kompile/roles/}
 * - Project-scoped: {@code .kompile/roles/} (relative to working directory)
 * <p>
 * File format:
 * <pre>
 * ---
 * name: backend-developer
 * category: development
 * description: Senior backend developer specializing in Java/Spring Boot
 * model: default
 * can_spawn: true
 * agent_defaults.codex.model: gpt-5.6-terra
 * agent_defaults.codex.thinking.default: medium
 * agent_defaults.codex.thinking.models.gpt-5.6-sol: ultra
 * tools: read, write, edit, bash, grep, glob
 * deny_tools: patch
 * ---
 * You are an expert backend developer...
 * </pre>
 */
public class RoleLoader {

    private static final String ROLES_DIR = "roles";
    private final Path workingDirectory;

    public RoleLoader(Path workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    /**
     * Load all roles from user and project directories.
     * Project-scoped roles override user-scoped roles with the same name.
     *
     * @return map of role name → RoleConfig
     */
    public Map<String, RoleConfig> loadAll() {
        Map<String, RoleConfig> roles = new LinkedHashMap<>();

        // 1. User-scoped: ~/.kompile/roles/
        Path userDir = KompileHome.homeDirectory().toPath().resolve(ROLES_DIR);
        loadFromDirectory(userDir, roles, false);

        // 2. Project-scoped: .kompile/roles/ (relative to working directory)
        Path projectDir = workingDirectory.resolve(".kompile").resolve(ROLES_DIR);
        loadFromDirectory(projectDir, roles, false);

        return roles;
    }

    /**
     * List all role files found (for display purposes).
     */
    public List<Path> listFiles() {
        List<Path> files = new ArrayList<>();
        Path userDir = KompileHome.homeDirectory().toPath().resolve(ROLES_DIR);
        Path projectDir = workingDirectory.resolve(".kompile").resolve(ROLES_DIR);

        collectRoleFiles(userDir, files);
        collectRoleFiles(projectDir, files);

        return files;
    }

    private void loadFromDirectory(Path dir, Map<String, RoleConfig> roles, boolean isBuiltIn) {
        if (!Files.isDirectory(dir)) return;

        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".md"))
                    .sorted()
                    .forEach(file -> {
                        try {
                            RoleConfig role = parseRoleFile(file, isBuiltIn);
                            if (role != null) {
                                roles.put(role.getName(), role);
                            }
                        } catch (Exception e) {
                            System.err.println("Warning: Failed to load role from " + file + ": " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            // Directory not accessible, skip
        }
    }

    private void collectRoleFiles(Path dir, List<Path> files) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".md"))
                    .sorted()
                    .forEach(files::add);
        } catch (IOException e) {
            // Skip
        }
    }

    /**
     * Parse a single role definition file.
     *
     * @return RoleConfig or null if the file is not a valid role definition
     */
    RoleConfig parseRoleFile(Path file, boolean isBuiltIn) throws IOException {
        String content = Files.readString(file);

        // Split frontmatter from body
        if (!content.startsWith("---")) {
            // No frontmatter — use filename as name, entire content as system prompt
            String name = file.getFileName().toString().replaceFirst("\\.md$", "");
            return RoleConfig.builder()
                    .name(name)
                    .displayName(name)
                    .description("Custom role from " + file.getFileName())
                    .systemPrompt(content.trim())
                    .sourceFile(file.toString())
                    .isBuiltIn(isBuiltIn)
                    .build();
        }

        int endIdx = findFrontmatterEnd(content);
        if (endIdx < 0) {
            // Malformed frontmatter — treat as no frontmatter
            String name = file.getFileName().toString().replaceFirst("\\.md$", "");
            return RoleConfig.builder()
                    .name(name)
                    .displayName(name)
                    .systemPrompt(content.substring(3).trim())
                    .sourceFile(file.toString())
                    .isBuiltIn(isBuiltIn)
                    .build();
        }

        String frontmatter = content.substring(3, endIdx).trim();
        String body = content.substring(endIdx + 3).trim();

        // Parse frontmatter (simple key: value format)
        Map<String, String> fields = parseFrontmatter(frontmatter);

        String name = fields.getOrDefault("name",
                file.getFileName().toString().replaceFirst("\\.md$", ""));
        String displayName = fields.getOrDefault("display_name", name);
        String description = fields.getOrDefault("description", "Role: " + name);
        String category = fields.getOrDefault("category", "general");
        String modelHint = fields.getOrDefault("model", "default");
        boolean canSpawn = "true".equalsIgnoreCase(fields.getOrDefault("can_spawn", "true"));

        // Parse agent fallback priority
        List<String> agentFallbackPriority = new ArrayList<>();
        String fallbackStr = fields.get("agent_fallback");
        if (fallbackStr != null && !fallbackStr.isBlank()) {
            for (String a : fallbackStr.split(",")) {
                String trimmed = a.trim().toLowerCase();
                if (!trimmed.isEmpty()) agentFallbackPriority.add(trimmed);
            }
        }

        // Parse tools
        Set<String> enabledTools;
        String toolsStr = fields.get("tools");
        if (toolsStr == null || toolsStr.isBlank() || "*".equals(toolsStr.trim())) {
            enabledTools = Set.of("*");
        } else {
            enabledTools = new LinkedHashSet<>();
            for (String t : toolsStr.split(",")) {
                String trimmed = t.trim();
                if (!trimmed.isEmpty()) enabledTools.add(trimmed);
            }
        }

        // Parse deny_tools as permission overrides
        Map<String, PermissionService.PermissionLevel> overrides = new java.util.HashMap<>();
        String denyStr = fields.get("deny_tools");
        if (denyStr != null && !denyStr.isBlank()) {
            for (String t : denyStr.split(",")) {
                String trimmed = t.trim();
                if (!trimmed.isEmpty()) {
                    overrides.put(trimmed, PermissionService.PermissionLevel.DENY);
                }
            }
        }

        return RoleConfig.builder()
                .name(name)
                .displayName(displayName)
                .description(description)
                .category(category)
                .systemPrompt(body)
                .modelHint(modelHint)
                .enabledTools(enabledTools)
                .permissionOverrides(overrides)
                .canSpawnSubagents(canSpawn)
                .agentDefaults(parseAgentDefaults(fields))
                .agentFallbackPriority(agentFallbackPriority)
                .sourceFile(file.toString())
                .isBuiltIn(isBuiltIn)
                .build();
    }

    private int findFrontmatterEnd(String content) {
        int searchFrom = 3;
        while (searchFrom < content.length()) {
            int candidate = content.indexOf("---", searchFrom);
            if (candidate < 0) {
                return -1;
            }
            boolean lineStart = candidate == 0
                    || content.charAt(candidate - 1) == '\n'
                    || content.charAt(candidate - 1) == '\r';
            int after = candidate + 3;
            boolean lineEnd = after == content.length()
                    || content.charAt(after) == '\n'
                    || content.charAt(after) == '\r';
            if (lineStart && lineEnd) {
                return candidate;
            }
            searchFrom = candidate + 3;
        }
        return -1;
    }

    private Map<String, RoleAgentDefaults> parseAgentDefaults(Map<String, String> fields) {
        Map<String, RoleAgentDefaults> defaults = new LinkedHashMap<>();
        for (String agent : AgentLaunchDefaults.SUPPORTED_AGENTS) {
            String prefix = "agent_defaults." + agent + ".";
            String model = fields.get(prefix + "model");
            String defaultThinking = fields.get(prefix + "thinking.default");
            if (defaultThinking == null || defaultThinking.isBlank()) {
                // Compatibility shorthand for hand-authored role files.
                defaultThinking = fields.get(prefix + "thinking");
            }

            String modelPrefix = prefix + "thinking.models.";
            Map<String, String> thinkingByModel = new LinkedHashMap<>();
            fields.forEach((key, value) -> {
                if (key.startsWith(modelPrefix) && key.length() > modelPrefix.length()) {
                    thinkingByModel.put(key.substring(modelPrefix.length()), value);
                }
            });

            RoleAgentDefaults agentDefaults =
                    new RoleAgentDefaults(model, defaultThinking, thinkingByModel);
            if (!agentDefaults.isEmpty()) {
                defaults.put(agent, agentDefaults);
            }
        }
        return Map.copyOf(defaults);
    }

    private Map<String, String> parseFrontmatter(String frontmatter) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : frontmatter.split("\n")) {
            int colonIdx = line.indexOf(": ");
            if (colonIdx < 0) {
                colonIdx = line.indexOf(':');
            }
            if (colonIdx > 0) {
                String key = normalizeFrontmatterKey(line.substring(0, colonIdx).trim());
                String value = line.substring(colonIdx + 1).trim();
                fields.put(key, value);
            }
        }
        return fields;
    }

    private String normalizeFrontmatterKey(String rawKey) {
        String lowerKey = rawKey.toLowerCase(Locale.ROOT);
        String modelMarker = ".thinking.models.";
        int markerIndex = lowerKey.indexOf(modelMarker);
        if (lowerKey.startsWith("agent_defaults.") && markerIndex >= 0) {
            int modelStart = markerIndex + modelMarker.length();
            return lowerKey.substring(0, modelStart) + rawKey.substring(modelStart);
        }
        return lowerKey;
    }


    /**
     * Save a role configuration to a Markdown file.
     *
     * @param role the role to save
     * @param targetPath the path to save to (including filename)
     * @return the path where the role was saved
     */
    public static Path saveRole(RoleConfig role, Path targetPath) throws IOException {
        Path parentDir = targetPath.getParent();
        if (parentDir != null && !Files.exists(parentDir)) {
            Files.createDirectories(parentDir);
        }

        String markdown = role.toMarkdown();
        Files.writeString(targetPath, markdown);
        return targetPath;
    }

    /**
     * Delete a role file.
     *
     * @param rolePath the path to the role file
     * @return true if the file was deleted, false if it didn't exist
     */
    public static boolean deleteRole(Path rolePath) throws IOException {
        if (Files.exists(rolePath)) {
            Files.delete(rolePath);
            return true;
        }
        return false;
    }
}
