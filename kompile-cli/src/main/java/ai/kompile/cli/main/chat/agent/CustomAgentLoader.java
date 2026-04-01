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

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.main.chat.permission.PermissionService;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Loads custom agent definitions from Markdown files with YAML-like frontmatter.
 * Follows the convention used by Claude Code and Codex for user-defined agents.
 *
 * <h3>Search locations (in order, later overrides earlier):</h3>
 * <ol>
 *   <li>{@code ~/.kompile/agents/} — user-scoped agents</li>
 *   <li>{@code .kompile/agents/} — project-scoped agents (relative to working directory)</li>
 * </ol>
 *
 * <h3>File format:</h3>
 * <pre>
 * ---
 * name: my-agent
 * description: Short description of what this agent does
 * model: fast
 * mode: subagent
 * max_steps: 20
 * tools: read, grep, glob, list, bash
 * deny_tools: edit, write, patch
 * ---
 * You are a specialized agent for...
 *
 * (The rest of the file is the system prompt)
 * </pre>
 *
 * <h3>Frontmatter fields:</h3>
 * <ul>
 *   <li><b>name</b> (required): Agent identifier (e.g. "my-researcher")</li>
 *   <li><b>description</b>: Human-readable description</li>
 *   <li><b>model</b>: Model hint — "fast", "default", "powerful" (default: "default")</li>
 *   <li><b>mode</b>: "primary" or "subagent" (default: "subagent")</li>
 *   <li><b>max_steps</b>: Maximum agentic loop iterations (default: 20)</li>
 *   <li><b>tools</b>: Comma-separated tool IDs, or "*" for all (default: "*")</li>
 *   <li><b>deny_tools</b>: Comma-separated tools to deny (e.g. "edit, write, patch")</li>
 *   <li><b>can_spawn</b>: Whether this agent can spawn subagents (default: false)</li>
 * </ul>
 */
public class CustomAgentLoader {

    private final Path workingDirectory;

    public CustomAgentLoader(Path workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    /**
     * Load all custom agents from user and project directories.
     * Project-scoped agents override user-scoped agents with the same name.
     *
     * @return map of agent name → AgentConfig
     */
    public Map<String, AgentConfig> loadAll() {
        Map<String, AgentConfig> agents = new LinkedHashMap<>();

        // 1. User-scoped: ~/.kompile/agents/
        Path userDir = KompileHome.homeDirectory().toPath().resolve("agents");
        loadFromDirectory(userDir, agents);

        // 2. Project-scoped: .kompile/agents/ (relative to working directory)
        Path projectDir = workingDirectory.resolve(".kompile").resolve("agents");
        loadFromDirectory(projectDir, agents);

        return agents;
    }

    /**
     * List all custom agent files found (for display purposes).
     */
    public List<Path> listFiles() {
        List<Path> files = new ArrayList<>();
        Path userDir = KompileHome.homeDirectory().toPath().resolve("agents");
        Path projectDir = workingDirectory.resolve(".kompile").resolve("agents");

        collectAgentFiles(userDir, files);
        collectAgentFiles(projectDir, files);

        return files;
    }

    private void loadFromDirectory(Path dir, Map<String, AgentConfig> agents) {
        if (!Files.isDirectory(dir)) return;

        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".md"))
                    .sorted()
                    .forEach(file -> {
                        try {
                            AgentConfig agent = parseAgentFile(file);
                            if (agent != null) {
                                agents.put(agent.getName(), agent);
                            }
                        } catch (Exception e) {
                            System.err.println("Warning: Failed to load agent from " + file + ": " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            // Directory not accessible, skip
        }
    }

    private void collectAgentFiles(Path dir, List<Path> files) {
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
     * Parse a single agent definition file.
     *
     * @return AgentConfig or null if the file is not a valid agent definition
     */
    AgentConfig parseAgentFile(Path file) throws IOException {
        String content = Files.readString(file);

        // Split frontmatter from body
        if (!content.startsWith("---")) {
            // No frontmatter — use filename as name, entire content as system prompt
            String name = file.getFileName().toString().replaceFirst("\\.md$", "");
            return AgentConfig.builder(name)
                    .displayName(name)
                    .description("Custom agent from " + file.getFileName())
                    .systemPrompt(content.trim())
                    .isSubagent(true)
                    .isCustom(true)
                    .maxSteps(20)
                    .build();
        }

        int endIdx = content.indexOf("---", 3);
        if (endIdx < 0) {
            // Malformed frontmatter — treat as no frontmatter
            String name = file.getFileName().toString().replaceFirst("\\.md$", "");
            return AgentConfig.builder(name)
                    .displayName(name)
                    .systemPrompt(content.substring(3).trim())
                    .isSubagent(true)
                    .isCustom(true)
                    .maxSteps(20)
                    .build();
        }

        String frontmatter = content.substring(3, endIdx).trim();
        String body = content.substring(endIdx + 3).trim();

        // Parse frontmatter (simple key: value format)
        Map<String, String> fields = parseFrontmatter(frontmatter);

        String name = fields.getOrDefault("name",
                file.getFileName().toString().replaceFirst("\\.md$", ""));
        String description = fields.getOrDefault("description", "Custom agent: " + name);
        String modelHint = fields.getOrDefault("model", "default");
        String mode = fields.getOrDefault("mode", "subagent");
        int maxSteps = parseIntOrDefault(fields.get("max_steps"), 20);
        boolean canSpawn = "true".equalsIgnoreCase(fields.getOrDefault("can_spawn", "false"));

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
        Map<String, PermissionService.PermissionLevel> overrides = new HashMap<>();
        String denyStr = fields.get("deny_tools");
        if (denyStr != null && !denyStr.isBlank()) {
            for (String t : denyStr.split(",")) {
                String trimmed = t.trim();
                if (!trimmed.isEmpty()) {
                    overrides.put(trimmed, PermissionService.PermissionLevel.DENY);
                }
            }
        }

        return AgentConfig.builder(name)
                .displayName(fields.getOrDefault("display_name", name))
                .description(description)
                .systemPrompt(body)
                .modelHint(modelHint)
                .enabledTools(enabledTools)
                .permissionOverrides(overrides)
                .isSubagent("subagent".equalsIgnoreCase(mode))
                .canSpawnSubagents(canSpawn)
                .isCustom(true)
                .maxSteps(maxSteps)
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
