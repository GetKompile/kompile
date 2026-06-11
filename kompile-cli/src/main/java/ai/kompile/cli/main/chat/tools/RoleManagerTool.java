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

import ai.kompile.cli.main.chat.roles.RoleConfig;
import ai.kompile.cli.main.chat.roles.RoleManager;
import ai.kompile.core.agent.CliAgentRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * MCP tool for managing chat roles.
 * <p>
 * This tool allows agents (including subagents in passthrough mode) to:
 * - List all available roles
 * - Get details about a specific role
 * - Create new roles
 * - Update existing roles
 * - Delete roles
 * - Assign a role to the current agent
 * <p>
 * Exposed as an MCP tool so external agents (Claude, Codex, etc.) can
 * query and manage roles during delegation.
 */
public class RoleManagerTool implements CliTool {

    private final RoleManager roleManager;
    private final ObjectMapper objectMapper;

    public RoleManagerTool(RoleManager roleManager, ObjectMapper objectMapper) {
        this.roleManager = roleManager;
        this.objectMapper = objectMapper;
    }

    @Override
    public String id() {
        return "role_manager";
    }

    @Override
    public String description() {
        return "Manage chat roles. Supports operations: list_roles, get_role, create_role, update_role, delete_role, assign_role, get_agent_role. " +
               "Roles are agent personas with system prompts that define how the agent behaviors. " +
               "Use list_roles to see available roles, assign_role to activate a role for the current agent or assign to a specific agent.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.set("required", objectMapper.createArrayNode()
                .add("action"));

        ObjectNode properties = objectMapper.createObjectNode();

        // action (required)
        ObjectNode actionNode = objectMapper.createObjectNode();
        actionNode.put("type", "string");
        actionNode.set("enum", objectMapper.createArrayNode()
                .add("list_roles")
                .add("get_role")
                .add("create_role")
                .add("update_role")
                .add("delete_role")
                .add("assign_role")
                .add("get_agent_role"));
        actionNode.put("description", "The role management action to perform");
        properties.set("action", actionNode);

        // name (for get_role, update_role, delete_role, assign_role)
        ObjectNode nameNode = objectMapper.createObjectNode();
        nameNode.put("type", "string");
        nameNode.put("description", "Role name (required for get_role, update_role, delete_role, assign_role)");
        properties.set("name", nameNode);

        // agent (optional, for assign_role)
        ObjectNode agentNode = objectMapper.createObjectNode();
        agentNode.put("type", "string");
        agentNode.put("description", "Agent to assign role to (e.g., 'claude', 'gemini', 'qwen'). If omitted, assigns to the current session.");
        properties.set("agent", agentNode);

        // display_name (for create_role, update_role)
        ObjectNode displayNameNode = objectMapper.createObjectNode();
        displayNameNode.put("type", "string");
        displayNameNode.put("description", "Display name for the role");
        properties.set("display_name", displayNameNode);

        // description (for create_role, update_role)
        ObjectNode descNode = objectMapper.createObjectNode();
        descNode.put("type", "string");
        descNode.put("description", "Description of what this role does");
        properties.set("description", descNode);

        // category (for create_role, update_role)
        ObjectNode categoryNode = objectMapper.createObjectNode();
        categoryNode.put("type", "string");
        categoryNode.put("description", "Role category (e.g., development, research, devops, data)");
        properties.set("category", categoryNode);

        // system_prompt (for create_role, update_role)
        ObjectNode promptNode = objectMapper.createObjectNode();
        promptNode.put("type", "string");
        promptNode.put("description", "The system prompt that defines the role's behavior");
        properties.set("system_prompt", promptNode);

        schema.set("properties", properties);
        return schema;
    }

    @Override
    public String permissionKey() {
        return "write"; // Role management includes write operations (create, update, delete, assign)
    }

    @Override
    public McpToolAnnotations mcpAnnotations() { return McpToolAnnotations.WRITE; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        String action = params.path("action").asText("");

        try {
            return switch (action) {
                case "list_roles" -> listRoles();
                case "get_role" -> getRole(params);
                case "create_role" -> createRole(params);
                case "update_role" -> updateRole(params);
                case "delete_role" -> deleteRole(params);
                case "assign_role" -> assignRole(params);
                case "get_agent_role" -> getAgentRole(params);
                default -> errorResult("Unknown action: " + action + ". Valid actions: list_roles, get_role, create_role, update_role, delete_role, assign_role");
            };
        } catch (IllegalArgumentException e) {
            return errorResult(e.getMessage());
        } catch (Exception e) {
            return errorResult("Role operation failed: " + e.getMessage());
        }
    }

    private ToolResult listRoles() {
        List<RoleConfig> roles = roleManager.getAllRoles();
        Map<String, java.util.List<String>> byCategory = roleManager.getRolesByCategory();
        String activeRole = roleManager.getActiveRoleName();

        StringBuilder sb = new StringBuilder();
        sb.append("Available Roles:\n\n");

        for (Map.Entry<String, java.util.List<String>> entry : byCategory.entrySet()) {
            sb.append("[").append(entry.getKey()).append("]\n");
            for (String roleName : entry.getValue()) {
                RoleConfig role = roleManager.getRole(roleName);
                if (role != null) {
                    String activeMarker = roleName.equals(activeRole) ? " [ACTIVE]" : "";
                    String builtInMarker = role.isBuiltIn() ? " (built-in)" : "";
                    sb.append("  - ").append(roleName).append(activeMarker).append(builtInMarker).append("\n");
                    sb.append("    ").append(role.getDescription()).append("\n");
                }
            }
            sb.append("\n");
        }

        if (activeRole != null) {
            sb.append("Current active role: ").append(activeRole).append("\n");
        } else {
            sb.append("No role currently active (using default agent)\n");
        }

        return ToolResult.success(sb.toString().trim());
    }

    private ToolResult getRole(JsonNode params) {
        String name = params.path("name").asText("");
        if (name.isBlank()) {
            return errorResult("Parameter 'name' is required for get_role");
        }

        RoleConfig role = roleManager.getRole(name);
        if (role == null) {
            return errorResult("Role not found: " + name);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Role: ").append(role.getName()).append("\n");
        sb.append("Display Name: ").append(role.getDisplayName()).append("\n");
        sb.append("Category: ").append(role.getCategory()).append("\n");
        sb.append("Description: ").append(role.getDescription()).append("\n");
        sb.append("Max Steps: ").append(role.getMaxSteps()).append("\n");
        sb.append("Can Spawn Subagents: ").append(role.isCanSpawnSubagents()).append("\n");
        sb.append("Model Hint: ").append(role.getModelHint()).append("\n");
        sb.append("Tools: ").append(role.getEnabledTools().contains("*") ? "all" : role.getEnabledTools()).append("\n");
        if (role.getSourceFile() != null && !role.getSourceFile().isEmpty()) {
            sb.append("Source: ").append(role.getSourceFile()).append("\n");
        }
        sb.append("\nSystem Prompt:\n");
        sb.append(role.getSystemPrompt());

        return ToolResult.success(sb.toString());
    }

    private ToolResult createRole(JsonNode params) {
        String name = params.path("name").asText("");
        if (name.isBlank()) {
            return errorResult("Parameter 'name' is required for create_role");
        }

        String displayName = params.path("display_name").asText(null);
        String description = params.path("description").asText(null);
        String category = params.path("category").asText("general");
        String systemPrompt = params.path("system_prompt").asText("");

        if (systemPrompt.isBlank()) {
            return errorResult("Parameter 'system_prompt' is required for create_role");
        }

        try {
            RoleConfig role = roleManager.createRole(name, displayName, description, category, systemPrompt);

            return ToolResult.success("Role created successfully:\n" +
                    "  Name: " + role.getName() + "\n" +
                    "  Display Name: " + role.getDisplayName() + "\n" +
                    "  Category: " + role.getCategory() + "\n" +
                    "  Description: " + role.getDescription() + "\n" +
                    "  Saved to: " + role.getSourceFile() + "\n\n" +
                    "Use assign_role to activate this role.");
        } catch (IOException e) {
            return errorResult("Failed to create role: " + e.getMessage());
        }
    }

    private ToolResult updateRole(JsonNode params) {
        String name = params.path("name").asText("");
        if (name.isBlank()) {
            return errorResult("Parameter 'name' is required for update_role");
        }

        String displayName = params.path("display_name").asText(null);
        String description = params.path("description").asText(null);
        String category = params.path("category").asText(null);
        String systemPrompt = params.path("system_prompt").asText(null);

        try {
            RoleConfig role = roleManager.updateRole(name, displayName, description, category, systemPrompt);

            return ToolResult.success("Role updated successfully:\n" +
                    "  Name: " + role.getName() + "\n" +
                    "  Display Name: " + role.getDisplayName() + "\n" +
                    "  Category: " + role.getCategory() + "\n" +
                    "  Description: " + role.getDescription());
        } catch (IOException e) {
            return errorResult("Failed to update role: " + e.getMessage());
        }
    }

    private ToolResult deleteRole(JsonNode params) {
        String name = params.path("name").asText("");
        if (name.isBlank()) {
            return errorResult("Parameter 'name' is required for delete_role");
        }

        try {
            boolean deleted = roleManager.deleteRole(name);
            if (deleted) {
                return ToolResult.success("Role deleted: " + name);
            } else {
                return errorResult("Failed to delete role: " + name);
            }
        } catch (IOException e) {
            return errorResult("Failed to delete role: " + e.getMessage());
        }
    }

    private static final java.util.Set<String> VALID_AGENTS = new java.util.HashSet<>(CliAgentRegistry.commandNames());

    private ToolResult assignRole(JsonNode params) {
        String name = params.path("name").asText("");
        if (name.isBlank()) {
            return errorResult("Parameter 'name' is required for assign_role");
        }

        RoleConfig role = roleManager.getRole(name);
        if (role == null) {
            return errorResult("Role not found: " + name);
        }

        String agent = params.path("agent").asText("");
        if (!agent.isBlank()) {
            // Validate agent name before assignment
            String normalizedAgent = agent.toLowerCase().trim();
            if (!VALID_AGENTS.contains(normalizedAgent)) {
                return errorResult("Unknown agent: '" + agent + "'. Valid agents are: " + VALID_AGENTS);
            }
            // Assign role to a specific agent
            roleManager.setAgentRole(normalizedAgent, name);
            return ToolResult.success("Role '" + name + "' assigned to agent '" + normalizedAgent + "'.\n" +
                    "  Display Name: " + role.getDisplayName() + "\n" +
                    "  Description: " + role.getDescription() + "\n\n" +
                    "This agent will use this role's system prompt when spawned as a subagent.");
        }

        // Assign to current session
        roleManager.setActiveRole(name);
        return ToolResult.success("Role activated: " + role.getName() + "\n" +
                "  Display Name: " + role.getDisplayName() + "\n" +
                "  Description: " + role.getDescription() + "\n\n" +
                "The agent will now use this role's system prompt for future interactions.");
    }

    private ToolResult getAgentRole(JsonNode params) {
        String agent = params.path("agent").asText("");
        if (agent.isBlank()) {
            return errorResult("Parameter 'agent' is required for get_agent_role");
        }

        String roleName = roleManager.getAgentRole(agent);
        if (roleName == null) {
            return ToolResult.success("No role assigned to agent '" + agent + "'");
        }

        RoleConfig role = roleManager.getRole(roleName);
        if (role == null) {
            return ToolResult.success("Agent '" + agent + "' has role '" + roleName + "' (role config not found)");
        }

        return ToolResult.success("Agent '" + agent + "' has role: " + role.getName() + "\n" +
                "  Display Name: " + role.getDisplayName() + "\n" +
                "  Description: " + role.getDescription() + "\n\n" +
                "System Prompt:\n" + role.getSystemPrompt());
    }

    private static ToolResult errorResult(String message) {
        return new ToolResult("Role Manager Error", message, Map.of(), true);
    }
}
