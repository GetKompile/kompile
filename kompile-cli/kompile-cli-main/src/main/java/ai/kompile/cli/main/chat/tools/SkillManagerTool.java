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

package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.main.chat.skill.CustomSkillLoader;
import ai.kompile.cli.main.chat.skill.SkillConfig;
import ai.kompile.cli.main.chat.skill.SkillRegistry;
import ai.kompile.cli.main.chat.skill.SkillsMarkdownGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP tool for managing kompile skills (slash command prompt templates).
 * <p>
 * This tool allows agents to:
 * - List all available skills (built-in and custom)
 * - Get details about a specific skill
 * - Create new custom skills
 * - Update existing custom skills
 * - Delete custom skills
 * - Generate skills.md markdown
 * - Expand a skill's template with arguments
 * <p>
 * Skills are stored as Markdown files with YAML frontmatter in
 * {@code ~/.kompile/skills/} (user scope) or {@code .kompile/skills/} (project scope).
 */
public class SkillManagerTool implements CliTool {

    private final ObjectMapper objectMapper;
    private final Path workingDirectory;

    public SkillManagerTool(ObjectMapper objectMapper, Path workingDirectory) {
        this.objectMapper = objectMapper;
        this.workingDirectory = workingDirectory;
    }

    @Override
    public String id() {
        return "skill_manager";
    }

    @Override
    public String description() {
        return "Manage kompile skills (slash command prompt templates). Supports operations: list_skills, get_skill, create_skill, update_skill, delete_skill, generate_markdown, expand_template. " +
               "Skills are reusable prompt templates invoked as /skillname [args] in chat. " +
               "Use list_skills to see all available skills, create_skill to add new ones.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.set("required", objectMapper.createArrayNode().add("action"));

        ObjectNode properties = objectMapper.createObjectNode();

        // action (required)
        ObjectNode actionNode = objectMapper.createObjectNode();
        actionNode.put("type", "string");
        actionNode.set("enum", objectMapper.createArrayNode()
                .add("list_skills")
                .add("get_skill")
                .add("create_skill")
                .add("update_skill")
                .add("delete_skill")
                .add("generate_markdown")
                .add("expand_template"));
        actionNode.put("description", "The skill management action to perform");
        properties.set("action", actionNode);

        // name (for get, create, update, delete, expand)
        ObjectNode nameNode = objectMapper.createObjectNode();
        nameNode.put("type", "string");
        nameNode.put("description", "Skill name (required for get_skill, create_skill, update_skill, delete_skill, expand_template). Must start with a letter; letters, digits, hyphens, underscores only.");
        properties.set("name", nameNode);

        // description (for create, update)
        ObjectNode descNode = objectMapper.createObjectNode();
        descNode.put("type", "string");
        descNode.put("description", "Description of what the skill does (for create_skill, update_skill)");
        properties.set("description", descNode);

        // category (for create, update, list filter)
        ObjectNode categoryNode = objectMapper.createObjectNode();
        categoryNode.put("type", "string");
        categoryNode.put("description", "Skill category (e.g., git, code, debug, devops, custom). For list_skills: filter by category. For create_skill/update_skill: set the category.");
        properties.set("category", categoryNode);

        // prompt_template (for create, update)
        ObjectNode templateNode = objectMapper.createObjectNode();
        templateNode.put("type", "string");
        templateNode.put("description", "The prompt template text. Use {{args}} where user arguments should be inserted. Required for create_skill.");
        properties.set("prompt_template", templateNode);

        // tools (for create, update)
        ObjectNode toolsNode = objectMapper.createObjectNode();
        toolsNode.put("type", "string");
        toolsNode.put("description", "Comma-separated list of allowed tools (e.g., 'bash, read, grep, glob'), or '*' for all tools. For create_skill/update_skill.");
        properties.set("tools", toolsNode);

        // display_name (for create, update)
        ObjectNode displayNameNode = objectMapper.createObjectNode();
        displayNameNode.put("type", "string");
        displayNameNode.put("description", "Human-readable display name for the skill (for create_skill, update_skill)");
        properties.set("display_name", displayNameNode);

        // model_hint (for create, update)
        ObjectNode modelNode = objectMapper.createObjectNode();
        modelNode.put("type", "string");
        modelNode.put("description", "Suggested model for this skill (for create_skill, update_skill). Optional.");
        properties.set("model_hint", modelNode);

        // max_steps (for create, update)
        ObjectNode maxStepsNode = objectMapper.createObjectNode();
        maxStepsNode.put("type", "integer");
        maxStepsNode.put("description", "Maximum steps for this skill (0 = inherit from agent). For create_skill/update_skill.");
        properties.set("max_steps", maxStepsNode);

        // project_scope (for create)
        ObjectNode scopeNode = objectMapper.createObjectNode();
        scopeNode.put("type", "boolean");
        scopeNode.put("description", "If true, create in project scope (.kompile/skills/) instead of user scope (~/.kompile/skills/). Default: false.");
        properties.set("project_scope", scopeNode);

        // args (for expand_template)
        ObjectNode argsNode = objectMapper.createObjectNode();
        argsNode.put("type", "string");
        argsNode.put("description", "Arguments to substitute into the skill's {{args}} placeholder (for expand_template)");
        properties.set("args", argsNode);

        // compact (for generate_markdown)
        ObjectNode compactNode = objectMapper.createObjectNode();
        compactNode.put("type", "boolean");
        compactNode.put("description", "If true, generate compact listing instead of full document (for generate_markdown). Default: false.");
        properties.set("compact", compactNode);

        schema.set("properties", properties);
        return schema;
    }

    @Override
    public String permissionKey() {
        return "write";
    }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        String action = params.path("action").asText("");

        try {
            return switch (action) {
                case "list_skills" -> listSkills(params);
                case "get_skill" -> getSkill(params);
                case "create_skill" -> createSkill(params);
                case "update_skill" -> updateSkill(params);
                case "delete_skill" -> deleteSkill(params);
                case "generate_markdown" -> generateMarkdown(params);
                case "expand_template" -> expandTemplate(params);
                default -> errorResult("Unknown action: " + action + ". Valid actions: list_skills, get_skill, create_skill, update_skill, delete_skill, generate_markdown, expand_template");
            };
        } catch (IllegalArgumentException e) {
            return errorResult(e.getMessage());
        } catch (Exception e) {
            return errorResult("Skill operation failed: " + e.getMessage());
        }
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    private ToolResult listSkills(JsonNode params) {
        SkillRegistry registry = loadRegistry();
        String categoryFilter = params.path("category").asText(null);

        Collection<SkillConfig> skills;
        if (categoryFilter != null && !categoryFilter.isBlank()) {
            skills = registry.getByCategory(categoryFilter);
        } else {
            skills = registry.all();
        }

        if (skills.isEmpty()) {
            return ToolResult.success("No skills found" +
                    (categoryFilter != null ? " in category: " + categoryFilter : "") + ".");
        }

        // Group by category for display
        Map<String, List<SkillConfig>> byCategory = skills.stream()
                .collect(Collectors.groupingBy(
                        s -> s.getCategory() != null ? s.getCategory() : "general",
                        LinkedHashMap::new,
                        Collectors.toList()));

        StringBuilder sb = new StringBuilder();
        sb.append("Available Skills:\n\n");

        int total = 0;
        int builtInCount = 0;
        int customCount = 0;

        for (Map.Entry<String, List<SkillConfig>> entry : byCategory.entrySet()) {
            sb.append("[").append(entry.getKey()).append("]\n");
            for (SkillConfig skill : entry.getValue()) {
                String source = skill.isBuiltIn() ? "(built-in)" : "(custom)";
                sb.append("  /").append(skill.getName()).append(" ").append(source).append("\n");
                sb.append("    ").append(skill.getDescription()).append("\n");
                total++;
                if (skill.isBuiltIn()) builtInCount++;
                else customCount++;
            }
            sb.append("\n");
        }

        sb.append("Total: ").append(total).append(" skills (")
                .append(builtInCount).append(" built-in, ")
                .append(customCount).append(" custom)\n");
        sb.append("Categories: ").append(String.join(", ", registry.categories()));

        return ToolResult.success(sb.toString());
    }

    private ToolResult getSkill(JsonNode params) {
        String name = params.path("name").asText("");
        if (name.isBlank()) {
            return errorResult("Parameter 'name' is required for get_skill");
        }

        SkillRegistry registry = loadRegistry();
        SkillConfig skill = registry.get(name);
        if (skill == null) {
            return errorResult("Skill not found: " + name + ". Available: " + String.join(", ", registry.names()));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Skill: /").append(skill.getName()).append("\n");
        sb.append("Display Name: ").append(skill.getDisplayName()).append("\n");
        sb.append("Category: ").append(skill.getCategory()).append("\n");
        sb.append("Source: ").append(skill.isBuiltIn() ? "built-in" : "custom").append("\n");
        sb.append("Description: ").append(skill.getDescription()).append("\n");
        if (skill.getAllowedTools() != null && !skill.getAllowedTools().isEmpty()) {
            if (skill.getAllowedTools().contains("*")) {
                sb.append("Tools: all\n");
            } else {
                sb.append("Tools: ").append(String.join(", ", skill.getAllowedTools())).append("\n");
            }
        } else {
            sb.append("Tools: inherit\n");
        }
        if (skill.getModelHint() != null) {
            sb.append("Model Hint: ").append(skill.getModelHint()).append("\n");
        }
        if (skill.getMaxSteps() > 0) {
            sb.append("Max Steps: ").append(skill.getMaxSteps()).append("\n");
        }
        sb.append("\nPrompt Template:\n");
        sb.append(skill.getPromptTemplate());

        return ToolResult.success(sb.toString());
    }

    private ToolResult createSkill(JsonNode params) throws IOException {
        String name = params.path("name").asText("");
        if (name.isBlank()) {
            return errorResult("Parameter 'name' is required for create_skill");
        }
        if (!name.matches("[a-zA-Z][a-zA-Z0-9_-]*")) {
            return errorResult("Invalid skill name '" + name + "'. Must start with a letter and contain only letters, digits, hyphens, or underscores.");
        }

        // Check if built-in
        SkillRegistry builtInCheck = new SkillRegistry();
        if (builtInCheck.get(name) != null) {
            return errorResult("Cannot create skill '" + name + "': a built-in skill with that name already exists.");
        }

        String promptTemplate = params.path("prompt_template").asText("");
        if (promptTemplate.isBlank()) {
            promptTemplate = "Perform the task described below. {{args}}\n\nFollow these steps:\n1. Analyze the request\n2. Implement the solution\n3. Verify the result\n";
        }

        String description = params.path("description").asText("Custom skill");
        String category = params.path("category").asText("custom");
        String displayName = params.path("display_name").asText(null);
        String tools = params.path("tools").asText(null);
        String modelHint = params.path("model_hint").asText(null);
        int maxSteps = params.path("max_steps").asInt(0);
        boolean projectScope = params.path("project_scope").asBoolean(false);

        Path targetDir;
        if (projectScope) {
            targetDir = workingDirectory.resolve(".kompile").resolve("skills");
        } else {
            targetDir = KompileHome.homeDirectory().toPath().resolve("skills");
        }

        Files.createDirectories(targetDir);
        Path skillFile = targetDir.resolve(name + ".md");

        if (Files.exists(skillFile)) {
            return errorResult("Skill file already exists: " + skillFile + ". Use update_skill to modify it, or delete_skill first.");
        }

        StringBuilder content = new StringBuilder();
        content.append("---\n");
        content.append("name: ").append(name).append("\n");
        if (displayName != null && !displayName.isBlank()) {
            content.append("display_name: ").append(displayName).append("\n");
        }
        content.append("description: ").append(description).append("\n");
        content.append("category: ").append(category).append("\n");
        if (tools != null && !tools.isBlank()) {
            content.append("tools: ").append(tools).append("\n");
        }
        if (modelHint != null && !modelHint.isBlank()) {
            content.append("model: ").append(modelHint).append("\n");
        }
        if (maxSteps > 0) {
            content.append("max_steps: ").append(maxSteps).append("\n");
        }
        content.append("---\n");
        content.append(promptTemplate);

        Files.writeString(skillFile, content.toString());

        String scope = projectScope ? "project" : "user";
        return ToolResult.success("Skill created successfully:\n" +
                "  Name: /" + name + "\n" +
                "  Category: " + category + "\n" +
                "  Description: " + description + "\n" +
                "  Scope: " + scope + "\n" +
                "  File: " + skillFile + "\n\n" +
                "Use /" + name + " in chat to invoke this skill.");
    }

    private ToolResult updateSkill(JsonNode params) throws IOException {
        String name = params.path("name").asText("");
        if (name.isBlank()) {
            return errorResult("Parameter 'name' is required for update_skill");
        }

        // Check built-in
        SkillRegistry builtInCheck = new SkillRegistry();
        if (builtInCheck.get(name) != null) {
            return errorResult("Cannot update built-in skill: " + name);
        }

        // Find existing file
        Path projectFile = workingDirectory.resolve(".kompile").resolve("skills").resolve(name + ".md");
        Path userFile = KompileHome.homeDirectory().toPath().resolve("skills").resolve(name + ".md");

        Path existingFile = null;
        if (Files.exists(projectFile)) {
            existingFile = projectFile;
        } else if (Files.exists(userFile)) {
            existingFile = userFile;
        }

        if (existingFile == null) {
            return errorResult("Custom skill not found: " + name + ". Checked: " + projectFile + " and " + userFile);
        }

        // Load current skill to preserve fields not being updated
        CustomSkillLoader loader = new CustomSkillLoader(workingDirectory);
        SkillConfig current = loader.parseSkillFile(existingFile);
        if (current == null) {
            return errorResult("Failed to parse existing skill file: " + existingFile);
        }

        // Apply updates (only override fields that are provided)
        String description = params.has("description") ? params.path("description").asText() : current.getDescription();
        String category = params.has("category") ? params.path("category").asText() : current.getCategory();
        String displayName = params.has("display_name") ? params.path("display_name").asText() : current.getDisplayName();
        String promptTemplate = params.has("prompt_template") ? params.path("prompt_template").asText() : current.getPromptTemplate();
        String modelHint = params.has("model_hint") ? params.path("model_hint").asText() : current.getModelHint();
        int maxSteps = params.has("max_steps") ? params.path("max_steps").asInt() : current.getMaxSteps();

        String tools;
        if (params.has("tools")) {
            tools = params.path("tools").asText(null);
        } else if (current.getAllowedTools() != null && !current.getAllowedTools().isEmpty()) {
            tools = String.join(", ", current.getAllowedTools());
        } else {
            tools = null;
        }

        // Rewrite file
        StringBuilder content = new StringBuilder();
        content.append("---\n");
        content.append("name: ").append(name).append("\n");
        if (displayName != null && !displayName.isBlank() && !displayName.equals(name)) {
            content.append("display_name: ").append(displayName).append("\n");
        }
        content.append("description: ").append(description).append("\n");
        content.append("category: ").append(category).append("\n");
        if (tools != null && !tools.isBlank()) {
            content.append("tools: ").append(tools).append("\n");
        }
        if (modelHint != null && !modelHint.isBlank()) {
            content.append("model: ").append(modelHint).append("\n");
        }
        if (maxSteps > 0) {
            content.append("max_steps: ").append(maxSteps).append("\n");
        }
        content.append("---\n");
        content.append(promptTemplate);

        Files.writeString(existingFile, content.toString());

        return ToolResult.success("Skill updated successfully:\n" +
                "  Name: /" + name + "\n" +
                "  Category: " + category + "\n" +
                "  Description: " + description + "\n" +
                "  File: " + existingFile);
    }

    private ToolResult deleteSkill(JsonNode params) throws IOException {
        String name = params.path("name").asText("");
        if (name.isBlank()) {
            return errorResult("Parameter 'name' is required for delete_skill");
        }

        // Check built-in
        SkillRegistry builtInCheck = new SkillRegistry();
        if (builtInCheck.get(name) != null) {
            return errorResult("Cannot delete built-in skill: " + name);
        }

        Path projectFile = workingDirectory.resolve(".kompile").resolve("skills").resolve(name + ".md");
        Path userFile = KompileHome.homeDirectory().toPath().resolve("skills").resolve(name + ".md");

        List<String> deleted = new ArrayList<>();
        if (Files.exists(projectFile)) {
            Files.delete(projectFile);
            deleted.add("project: " + projectFile);
        }
        if (Files.exists(userFile)) {
            Files.delete(userFile);
            deleted.add("user: " + userFile);
        }

        if (deleted.isEmpty()) {
            return errorResult("Skill file not found: " + name + ". Checked: " + projectFile + " and " + userFile);
        }

        return ToolResult.success("Skill deleted: /" + name + "\n  Removed: " + String.join(", ", deleted));
    }

    private ToolResult generateMarkdown(JsonNode params) {
        SkillRegistry registry = loadRegistry();
        boolean compact = params.path("compact").asBoolean(false);

        String content;
        if (compact) {
            content = SkillsMarkdownGenerator.generateCompact(registry.all());
        } else {
            content = SkillsMarkdownGenerator.generate(registry.all());
        }

        if (content == null) {
            return errorResult("No skills available to generate markdown from.");
        }

        return ToolResult.success(content);
    }

    private ToolResult expandTemplate(JsonNode params) {
        String name = params.path("name").asText("");
        if (name.isBlank()) {
            return errorResult("Parameter 'name' is required for expand_template");
        }

        SkillRegistry registry = loadRegistry();
        SkillConfig skill = registry.get(name);
        if (skill == null) {
            return errorResult("Skill not found: " + name + ". Available: " + String.join(", ", registry.names()));
        }

        String args = params.path("args").asText("");
        String expanded = skill.expandTemplate(args);

        return ToolResult.success("Expanded template for /" + name + ":\n\n" + expanded);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private SkillRegistry loadRegistry() {
        SkillRegistry registry = new SkillRegistry();
        CustomSkillLoader loader = new CustomSkillLoader(workingDirectory);
        Map<String, SkillConfig> customSkills = loader.loadAll();
        for (SkillConfig skill : customSkills.values()) {
            registry.register(skill);
        }
        return registry;
    }

    private static ToolResult errorResult(String message) {
        return new ToolResult("Skill Manager Error", message, Map.of(), true);
    }
}
