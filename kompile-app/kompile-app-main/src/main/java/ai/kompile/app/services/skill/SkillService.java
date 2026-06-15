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

package ai.kompile.app.services.skill;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Service for managing kompile skills — markdown-based prompt templates
 * that are invoked as {@code /skillname [args]} in the CLI chat, and can
 * be injected into agent system prompts as skills.md.
 *
 * <p>Skills are loaded from two locations (later overrides earlier):</p>
 * <ol>
 *   <li>{@code ~/.kompile/skills/} — user-scoped skills</li>
 *   <li>{@code ./data/skills/} — app-scoped skills (configurable)</li>
 * </ol>
 *
 * <p>Built-in skills are registered programmatically at startup.</p>
 */
@Service
public class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);

    private final Map<String, SkillDefinition> skills = new ConcurrentHashMap<>();
    private final Path userSkillsDir;
    private final Path appSkillsDir;

    public SkillService() {
        String userHome = System.getProperty("user.home");
        this.userSkillsDir = Path.of(userHome, ".kompile", "skills");
        this.appSkillsDir = Path.of("data", "skills");
    }

    @PostConstruct
    public void init() {
        registerBuiltIns();
        loadFromDisk();
        log.info("Skills loaded: {} total ({} built-in, {} custom)",
                skills.size(),
                skills.values().stream().filter(s -> s.builtIn).count(),
                skills.values().stream().filter(s -> !s.builtIn).count());
    }

    // ── CRUD Operations ──────────────────────────────────────────────────────

    public List<SkillDefinition> listAll() {
        return new ArrayList<>(skills.values());
    }

    public List<SkillDefinition> listByCategory(String category) {
        return skills.values().stream()
                .filter(s -> category.equalsIgnoreCase(s.category))
                .collect(Collectors.toList());
    }

    public List<SkillDefinition> search(String query) {
        String q = query.toLowerCase();
        return skills.values().stream()
                .filter(s -> s.name.toLowerCase().contains(q)
                        || s.description.toLowerCase().contains(q)
                        || s.category.toLowerCase().contains(q))
                .collect(Collectors.toList());
    }

    public SkillDefinition getByName(String name) {
        return skills.get(name);
    }

    public SkillDefinition create(SkillDefinition skill) {
        if (skill.name == null || !skill.name.matches("[a-zA-Z][a-zA-Z0-9_-]*")) {
            throw new IllegalArgumentException("Invalid skill name. Must start with a letter and contain only letters, digits, hyphens, or underscores.");
        }
        if (skills.containsKey(skill.name) && skills.get(skill.name).builtIn) {
            throw new IllegalArgumentException("Cannot overwrite built-in skill: " + skill.name);
        }

        skill.builtIn = false;
        if (skill.category == null || skill.category.isBlank()) skill.category = "custom";
        if (skill.promptTemplate == null) skill.promptTemplate = "";

        skills.put(skill.name, skill);
        saveToDisk(skill);
        return skill;
    }

    public SkillDefinition update(String name, SkillDefinition updates) {
        SkillDefinition existing = skills.get(name);
        if (existing == null) {
            throw new IllegalArgumentException("Skill not found: " + name);
        }
        if (existing.builtIn) {
            throw new IllegalArgumentException("Cannot modify built-in skill: " + name);
        }

        if (updates.description != null) existing.description = updates.description;
        if (updates.displayName != null) existing.displayName = updates.displayName;
        if (updates.category != null) existing.category = updates.category;
        if (updates.promptTemplate != null) existing.promptTemplate = updates.promptTemplate;
        if (updates.allowedTools != null) existing.allowedTools = updates.allowedTools;
        if (updates.modelHint != null) existing.modelHint = updates.modelHint;
        if (updates.maxSteps > 0) existing.maxSteps = updates.maxSteps;

        saveToDisk(existing);
        return existing;
    }

    public void delete(String name) {
        SkillDefinition skill = skills.get(name);
        if (skill == null) {
            throw new IllegalArgumentException("Skill not found: " + name);
        }
        if (skill.builtIn) {
            throw new IllegalArgumentException("Cannot delete built-in skill: " + name);
        }

        skills.remove(name);
        deleteFromDisk(name);
    }

    // ── Skills.md Generation ─────────────────────────────────────────────────

    /**
     * Generate the full skills.md markdown document.
     */
    public String generateSkillsMarkdown() {
        if (skills.isEmpty()) return null;

        StringBuilder md = new StringBuilder();
        md.append("# Available Kompile Skills\n\n");
        md.append("The following skills are available as slash commands. ");
        md.append("Invoke them with `/skillname [args]` in the chat.\n\n");

        Map<String, List<SkillDefinition>> byCategory = skills.values().stream()
                .collect(Collectors.groupingBy(
                        s -> s.category != null ? s.category : "general",
                        LinkedHashMap::new,
                        Collectors.toList()));

        for (Map.Entry<String, List<SkillDefinition>> entry : byCategory.entrySet()) {
            String category = entry.getKey();
            md.append("## ").append(capitalize(category)).append("\n\n");

            for (SkillDefinition skill : entry.getValue()) {
                md.append("- **`/").append(skill.name).append("`**");
                if (skill.displayName != null && !skill.displayName.equals(skill.name)) {
                    md.append(" (").append(skill.displayName).append(")");
                }
                md.append(": ").append(skill.description).append("\n");

                if (skill.allowedTools != null && !skill.allowedTools.isEmpty()
                        && !skill.allowedTools.contains("*")) {
                    md.append("  - Tools: ").append(String.join(", ", skill.allowedTools)).append("\n");
                }
                if (skill.promptTemplate != null && skill.promptTemplate.contains("{{args}}")) {
                    md.append("  - Accepts arguments: `/").append(skill.name).append(" <your instructions>`\n");
                }
            }
            md.append("\n");
        }
        return md.toString().stripTrailing() + "\n";
    }

    /**
     * Generate a compact skills listing.
     */
    public String generateCompactListing() {
        if (skills.isEmpty()) return null;
        StringBuilder md = new StringBuilder();
        md.append("Available skills (invoke with `/skillname [args]`):\n");
        for (SkillDefinition skill : skills.values()) {
            md.append("- /").append(skill.name).append(": ").append(skill.description).append("\n");
        }
        return md.toString();
    }

    /**
     * Get summary statistics about skills.
     */
    public SkillsSummary getSummary() {
        long builtIn = skills.values().stream().filter(s -> s.builtIn).count();
        long custom = skills.values().stream().filter(s -> !s.builtIn).count();
        List<String> categories = skills.values().stream()
                .map(s -> s.category)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        return new SkillsSummary(skills.size(), (int) builtIn, (int) custom, categories);
    }

    /**
     * Reload all skills from disk.
     */
    public int refresh() {
        skills.clear();
        registerBuiltIns();
        loadFromDisk();
        return skills.size();
    }

    /**
     * Expand a skill template with the given arguments.
     */
    public String expandTemplate(String name, String args) {
        SkillDefinition skill = skills.get(name);
        if (skill == null) throw new IllegalArgumentException("Skill not found: " + name);

        String expanded = skill.promptTemplate;
        if (args == null || args.isBlank()) {
            expanded = expanded.replace("{{args}}", "");
        } else {
            expanded = expanded.replace("{{args}}", args);
        }
        return expanded.trim();
    }

    // ── Built-in Skills ──────────────────────────────────────────────────────

    private void registerBuiltIns() {
        register("commit", "Commit", "Stage and commit changes with a generated message",
                "git", List.of("bash", "read", "grep", "glob"),
                "Create a git commit for the current changes. {{args}}\n\nFollow these steps:\n1. Run `git status` to see all changed files\n2. Run `git diff` to see staged and unstaged changes\n3. Run `git log --oneline -5` to see recent commit message style\n4. Analyze changes and draft a concise commit message\n5. Stage the relevant files\n6. Create the commit\n7. Run `git status` to verify");

        register("pr", "Pull Request", "Create or update a pull request",
                "git", List.of("bash", "read", "grep", "glob"),
                "Create or update a pull request. {{args}}\n\nFollow these steps:\n1. Run `git status` to check for uncommitted changes\n2. Run `git branch --show-current` to get the current branch\n3. Run `git log main..HEAD --oneline` to see all commits\n4. Draft a PR title and description\n5. Push the branch if needed\n6. Create the PR with gh pr create");

        register("review", "Review", "Review uncommitted changes for quality issues",
                "code", List.of("bash", "read", "grep", "glob"),
                "Review the current uncommitted changes for code quality. {{args}}\n\nAnalyze for:\n- Bugs: logic errors, null safety, race conditions\n- Security: injection, hardcoded secrets\n- Performance: unnecessary allocations, N+1 queries\n- Style: naming, organization, DRY violations\n- Error handling: missing try/catch, unclosed resources");

        register("simplify", "Simplify", "Refactor and simplify recent changes",
                "code", List.of("*"),
                "Review and simplify recent code changes. {{args}}\n\nLook for opportunities to:\n- Remove unnecessary complexity\n- Eliminate dead code\n- Simplify control flow\n- Replace verbose patterns with idiomatic alternatives\n- Consolidate duplicate logic");

        register("explain", "Explain", "Explain code or recent changes",
                "code", List.of("bash", "read", "grep", "glob"),
                "Explain the code or recent changes. {{args}}\n\nCover:\n- What: What the code does at a high level\n- How: Key implementation details\n- Why: Design decisions and trade-offs\n- Dependencies: What this code interacts with");

        register("test", "Test", "Generate or run tests for recent changes",
                "code", List.of("*"),
                "Generate or run tests for recent changes. {{args}}\n\nFollow these steps:\n1. Identify changed files\n2. Identify the testing framework\n3. Read changed files and existing tests\n4. Generate or run tests covering changed code paths");

        register("fix", "Fix", "Fix build or test failures",
                "debug", List.of("*"),
                "Fix build or test failures. {{args}}\n\nFollow these steps:\n1. Reproduce the failure\n2. Read the error output carefully\n3. Diagnose the root cause\n4. Apply the fix\n5. Re-run to verify");
    }

    private void register(String name, String displayName, String description,
                          String category, List<String> tools, String template) {
        SkillDefinition skill = new SkillDefinition();
        skill.name = name;
        skill.displayName = displayName;
        skill.description = description;
        skill.category = category;
        skill.allowedTools = tools;
        skill.promptTemplate = template;
        skill.builtIn = true;
        skills.put(name, skill);
    }

    // ── Disk Persistence ─────────────────────────────────────────────────────

    private void loadFromDisk() {
        loadFromDirectory(userSkillsDir);
        loadFromDirectory(appSkillsDir);
    }

    private void loadFromDirectory(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".md"))
                    .sorted()
                    .forEach(file -> {
                        try {
                            SkillDefinition skill = parseSkillFile(file);
                            if (skill != null) {
                                skill.builtIn = false;
                                skills.put(skill.name, skill);
                            }
                        } catch (Exception e) {
                            log.warn("Failed to load skill from {}: {}", file, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.debug("Could not list skills directory {}: {}", dir, e.getMessage());
        }
    }

    private SkillDefinition parseSkillFile(Path file) throws IOException {
        String content = Files.readString(file);

        if (!content.startsWith("---")) {
            String name = file.getFileName().toString().replaceFirst("\\.md$", "");
            SkillDefinition skill = new SkillDefinition();
            skill.name = name;
            skill.displayName = name;
            skill.description = "Custom skill from " + file.getFileName();
            skill.category = "custom";
            skill.promptTemplate = content.trim();
            return skill;
        }

        int endIdx = content.indexOf("---", 3);
        if (endIdx < 0) {
            String name = file.getFileName().toString().replaceFirst("\\.md$", "");
            SkillDefinition skill = new SkillDefinition();
            skill.name = name;
            skill.displayName = name;
            skill.category = "custom";
            skill.promptTemplate = content.substring(3).trim();
            return skill;
        }

        String frontmatter = content.substring(3, endIdx).trim();
        String body = content.substring(endIdx + 3).trim();
        Map<String, String> fields = parseFrontmatter(frontmatter);

        SkillDefinition skill = new SkillDefinition();
        skill.name = fields.getOrDefault("name",
                file.getFileName().toString().replaceFirst("\\.md$", ""));
        skill.displayName = fields.getOrDefault("display_name", skill.name);
        skill.description = fields.getOrDefault("description", "Custom skill: " + skill.name);
        skill.category = fields.getOrDefault("category", "custom");
        skill.modelHint = fields.getOrDefault("model", null);
        skill.promptTemplate = body;

        String maxStepsStr = fields.get("max_steps");
        if (maxStepsStr != null) {
            try { skill.maxSteps = Integer.parseInt(maxStepsStr.trim()); }
            catch (NumberFormatException e) {
                log.debug("Invalid max_steps value '{}' in skill '{}', using default: {}", maxStepsStr, skill.name, e.getMessage());
            }
        }

        String toolsStr = fields.get("tools");
        if (toolsStr != null && !toolsStr.isBlank()) {
            if ("*".equals(toolsStr.trim())) {
                skill.allowedTools = List.of("*");
            } else {
                skill.allowedTools = Arrays.stream(toolsStr.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList());
            }
        }

        return skill;
    }

    private void saveToDisk(SkillDefinition skill) {
        try {
            Files.createDirectories(appSkillsDir);
            Path file = appSkillsDir.resolve(skill.name + ".md");
            Files.writeString(file, skillToMarkdown(skill));
        } catch (IOException e) {
            log.warn("Could not save skill {}: {}", skill.name, e.getMessage());
        }
    }

    private void deleteFromDisk(String name) {
        try {
            Path appFile = appSkillsDir.resolve(name + ".md");
            Files.deleteIfExists(appFile);
            Path userFile = userSkillsDir.resolve(name + ".md");
            Files.deleteIfExists(userFile);
        } catch (IOException e) {
            log.warn("Could not delete skill file {}: {}", name, e.getMessage());
        }
    }

    private String skillToMarkdown(SkillDefinition skill) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(skill.name).append("\n");
        if (skill.displayName != null) sb.append("display_name: ").append(skill.displayName).append("\n");
        if (skill.description != null) sb.append("description: ").append(skill.description).append("\n");
        if (skill.category != null) sb.append("category: ").append(skill.category).append("\n");
        if (skill.allowedTools != null && !skill.allowedTools.isEmpty()) {
            sb.append("tools: ").append(String.join(", ", skill.allowedTools)).append("\n");
        }
        if (skill.modelHint != null) sb.append("model: ").append(skill.modelHint).append("\n");
        if (skill.maxSteps > 0) sb.append("max_steps: ").append(skill.maxSteps).append("\n");
        sb.append("---\n");
        if (skill.promptTemplate != null) sb.append(skill.promptTemplate);
        return sb.toString();
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

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    // ── Data Models ──────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SkillDefinition {
        public String name;
        public String displayName;
        public String description;
        public String category;
        public String promptTemplate;
        public List<String> allowedTools;
        public int maxSteps;
        public String modelHint;
        public boolean builtIn;

        public SkillDefinition() {}
    }

    public record SkillsSummary(int total, int builtIn, int custom, List<String> categories) {}
}
