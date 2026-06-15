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
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * CLI command for managing kompile skills.
 * Skills are markdown-based prompt templates invoked as /skillname in the chat.
 *
 * <p>Usage:</p>
 * <pre>
 *   kompile skills list                    # List all available skills
 *   kompile skills show &lt;name&gt;             # Show skill details
 *   kompile skills create &lt;name&gt;           # Create a new skill from template
 *   kompile skills delete &lt;name&gt;           # Delete a custom skill
 *   kompile skills generate                # Generate skills.md for current project
 *   kompile skills path                    # Show skill file locations
 * </pre>
 */
@Command(
        name = "skills",
        description = "Manage kompile skills (slash command prompt templates)",
        subcommands = {
                SkillsCommand.ListSkills.class,
                SkillsCommand.ShowSkill.class,
                SkillsCommand.CreateSkill.class,
                SkillsCommand.DeleteSkill.class,
                SkillsCommand.GenerateSkillsMd.class,
                SkillsCommand.SkillPaths.class
        },
        mixinStandardHelpOptions = true
)
public class SkillsCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        new CommandLine(this).usage(System.out);
        return 0;
    }

    // ── List all skills ──────────────────────────────────────────────────────

    @Command(name = "list", aliases = {"ls"}, description = "List all available skills")
    static class ListSkills implements Callable<Integer> {

        @Option(names = {"--category", "-c"}, description = "Filter by category")
        String category;

        @Option(names = {"--json"}, description = "Output as JSON")
        boolean json;

        @Override
        public Integer call() {
            SkillRegistry registry = loadRegistry();

            Collection<SkillConfig> skills = category != null
                    ? registry.getByCategory(category)
                    : registry.all();

            if (skills.isEmpty()) {
                System.out.println("No skills found" + (category != null ? " in category: " + category : "") + ".");
                return 0;
            }

            if (json) {
                printJson(skills);
            } else {
                printTable(skills);
            }
            return 0;
        }

        private void printTable(Collection<SkillConfig> skills) {
            System.out.printf("%-15s %-12s %-8s %s%n", "NAME", "CATEGORY", "SOURCE", "DESCRIPTION");
            System.out.println("─".repeat(75));
            for (SkillConfig skill : skills) {
                String source = skill.isBuiltIn() ? "built-in" : "custom";
                String desc = skill.getDescription();
                if (desc.length() > 38) desc = desc.substring(0, 35) + "...";
                System.out.printf("%-15s %-12s %-8s %s%n",
                        "/" + skill.getName(), skill.getCategory(), source, desc);
            }
            System.out.println();
            System.out.println("Total: " + skills.size() + " skills");
            System.out.println("Categories: " + String.join(", ", loadRegistry().categories()));
        }

        private void printJson(Collection<SkillConfig> skills) {
            System.out.println("[");
            Iterator<SkillConfig> it = skills.iterator();
            while (it.hasNext()) {
                SkillConfig s = it.next();
                System.out.printf("  {\"name\": \"%s\", \"category\": \"%s\", \"builtIn\": %s, \"description\": \"%s\"}%s%n",
                        s.getName(), s.getCategory(), s.isBuiltIn(),
                        s.getDescription().replace("\"", "\\\""),
                        it.hasNext() ? "," : "");
            }
            System.out.println("]");
        }
    }

    // ── Show skill details ───────────────────────────────────────────────────

    @Command(name = "show", aliases = {"get"}, description = "Show details of a specific skill")
    static class ShowSkill implements Callable<Integer> {

        @Parameters(index = "0", description = "Skill name")
        String name;

        @Override
        public Integer call() {
            SkillRegistry registry = loadRegistry();
            SkillConfig skill = registry.get(name);
            if (skill == null) {
                System.err.println("Skill not found: " + name);
                System.err.println("Available skills: " + String.join(", ", registry.names()));
                return 1;
            }

            System.out.println("Name:        /" + skill.getName());
            System.out.println("Display:     " + skill.getDisplayName());
            System.out.println("Category:    " + skill.getCategory());
            System.out.println("Source:      " + (skill.isBuiltIn() ? "built-in" : "custom"));
            System.out.println("Description: " + skill.getDescription());
            if (skill.getAllowedTools() != null && !skill.getAllowedTools().isEmpty()) {
                System.out.println("Tools:       " + String.join(", ", skill.getAllowedTools()));
            }
            if (skill.getModelHint() != null) {
                System.out.println("Model hint:  " + skill.getModelHint());
            }
            if (skill.getMaxSteps() > 0) {
                System.out.println("Max steps:   " + skill.getMaxSteps());
            }
            System.out.println();
            System.out.println("── Prompt Template ──────────────────────────────────────");
            System.out.println(skill.getPromptTemplate());
            return 0;
        }
    }

    // ── Create a new skill ───────────────────────────────────────────────────

    @Command(name = "create", aliases = {"new"}, description = "Create a new custom skill")
    static class CreateSkill implements Callable<Integer> {

        @Parameters(index = "0", description = "Skill name (used as /name in chat)")
        String name;

        @Option(names = {"--description", "-d"}, description = "Skill description",
                defaultValue = "Custom skill")
        String description;

        @Option(names = {"--category", "-c"}, description = "Skill category",
                defaultValue = "custom")
        String category;

        @Option(names = {"--tools", "-t"}, description = "Allowed tools (comma-separated, or * for all)")
        String tools;

        @Option(names = {"--project", "-p"}, description = "Create in project scope (.kompile/skills/) instead of user scope")
        boolean projectScope;

        @Option(names = {"--template"}, description = "Initial prompt template text")
        String template;

        @Override
        public Integer call() {
            // Validate name
            if (!name.matches("[a-zA-Z][a-zA-Z0-9_-]*")) {
                System.err.println("Invalid skill name. Must start with a letter and contain only letters, digits, hyphens, or underscores.");
                return 1;
            }

            Path targetDir;
            if (projectScope) {
                targetDir = Path.of(System.getProperty("user.dir"), ".kompile", "skills");
            } else {
                targetDir = KompileHome.homeDirectory().toPath().resolve("skills");
            }

            try {
                Files.createDirectories(targetDir);
            } catch (IOException e) {
                System.err.println("Could not create skills directory: " + e.getMessage());
                return 1;
            }

            Path skillFile = targetDir.resolve(name + ".md");
            if (Files.exists(skillFile)) {
                System.err.println("Skill already exists: " + skillFile);
                System.err.println("Edit it directly or delete it first with: kompile skills delete " + name);
                return 1;
            }

            String promptTemplate = template != null ? template :
                    "Perform the task described below. {{args}}\n\n" +
                    "Follow these steps:\n" +
                    "1. Analyze the request\n" +
                    "2. Implement the solution\n" +
                    "3. Verify the result\n";

            StringBuilder content = new StringBuilder();
            content.append("---\n");
            content.append("name: ").append(name).append("\n");
            content.append("description: ").append(description).append("\n");
            content.append("category: ").append(category).append("\n");
            if (tools != null && !tools.isBlank()) {
                content.append("tools: ").append(tools).append("\n");
            }
            content.append("---\n");
            content.append(promptTemplate);

            try {
                Files.writeString(skillFile, content.toString());
                System.out.println("Created skill: " + skillFile);
                System.out.println("Edit the file to customize the prompt template.");
                System.out.println("Use /" + name + " in chat to invoke it.");
                return 0;
            } catch (IOException e) {
                System.err.println("Could not write skill file: " + e.getMessage());
                return 1;
            }
        }
    }

    // ── Delete a skill ───────────────────────────────────────────────────────

    @Command(name = "delete", aliases = {"rm"}, description = "Delete a custom skill")
    static class DeleteSkill implements Callable<Integer> {

        @Parameters(index = "0", description = "Skill name to delete")
        String name;

        @Override
        public Integer call() {
            // Check if it's a built-in skill
            SkillRegistry registry = new SkillRegistry();
            if (registry.get(name) != null) {
                System.err.println("Cannot delete built-in skill: " + name);
                return 1;
            }

            // Try project scope first, then user scope
            Path projectFile = Path.of(System.getProperty("user.dir"), ".kompile", "skills", name + ".md");
            Path userFile = KompileHome.homeDirectory().toPath().resolve("skills").resolve(name + ".md");

            boolean deleted = false;
            if (Files.exists(projectFile)) {
                try {
                    Files.delete(projectFile);
                    System.out.println("Deleted project skill: " + projectFile);
                    deleted = true;
                } catch (IOException e) {
                    System.err.println("Could not delete: " + e.getMessage());
                    return 1;
                }
            }
            if (Files.exists(userFile)) {
                try {
                    Files.delete(userFile);
                    System.out.println("Deleted user skill: " + userFile);
                    deleted = true;
                } catch (IOException e) {
                    System.err.println("Could not delete: " + e.getMessage());
                    return 1;
                }
            }

            if (!deleted) {
                System.err.println("Skill file not found: " + name);
                System.err.println("Checked: " + projectFile + " and " + userFile);
                return 1;
            }
            return 0;
        }
    }

    // ── Generate skills.md ───────────────────────────────────────────────────

    @Command(name = "generate", aliases = {"gen"}, description = "Generate skills.md file for the current project")
    static class GenerateSkillsMd implements Callable<Integer> {

        @Option(names = {"--output", "-o"}, description = "Output file path",
                defaultValue = ".kompile/skills.md")
        String output;

        @Option(names = {"--compact"}, description = "Generate compact listing")
        boolean compact;

        @Override
        public Integer call() {
            SkillRegistry registry = loadRegistry();

            String content;
            if (compact) {
                content = SkillsMarkdownGenerator.generateCompact(registry.all());
            } else {
                content = SkillsMarkdownGenerator.generate(registry.all());
            }

            if (content == null) {
                System.err.println("No skills available to generate.");
                return 1;
            }

            Path outputPath = Path.of(output);
            try {
                Files.createDirectories(outputPath.getParent());
                Files.writeString(outputPath, content);
                System.out.println("Generated: " + outputPath.toAbsolutePath());
                System.out.println("Skills documented: " + registry.all().size());
                return 0;
            } catch (IOException e) {
                System.err.println("Could not write output: " + e.getMessage());
                return 1;
            }
        }
    }

    // ── Show skill paths ─────────────────────────────────────────────────────

    @Command(name = "path", aliases = {"paths"}, description = "Show skill file locations")
    static class SkillPaths implements Callable<Integer> {
        @Override
        public Integer call() {
            Path userDir = KompileHome.homeDirectory().toPath().resolve("skills");
            Path projectDir = Path.of(System.getProperty("user.dir"), ".kompile", "skills");

            System.out.println("Skill file locations (later overrides earlier):");
            System.out.println();
            System.out.println("  User scope:    " + userDir);
            System.out.println("    Exists: " + Files.isDirectory(userDir));
            if (Files.isDirectory(userDir)) {
                listSkillFiles(userDir);
            }
            System.out.println();
            System.out.println("  Project scope: " + projectDir);
            System.out.println("    Exists: " + Files.isDirectory(projectDir));
            if (Files.isDirectory(projectDir)) {
                listSkillFiles(projectDir);
            }
            return 0;
        }

        private void listSkillFiles(Path dir) {
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.toString().endsWith(".md"))
                        .sorted()
                        .forEach(p -> System.out.println("    - " + p.getFileName()));
            } catch (IOException e) {
                System.out.println("    (could not list files)");
            }
        }
    }

    // ── Shared helpers ───────────────────────────────────────────────────────

    private static SkillRegistry loadRegistry() {
        SkillRegistry registry = new SkillRegistry();
        CustomSkillLoader loader = new CustomSkillLoader(
                Path.of(System.getProperty("user.dir")));
        Map<String, SkillConfig> customSkills = loader.loadAll();
        for (SkillConfig skill : customSkills.values()) {
            registry.register(skill);
        }
        return registry;
    }
}
