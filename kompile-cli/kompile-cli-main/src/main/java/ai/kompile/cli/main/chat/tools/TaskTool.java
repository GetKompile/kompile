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

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.agent.SubagentRunner;
import ai.kompile.cli.main.chat.roles.RoleConfig;
import ai.kompile.cli.main.chat.roles.RoleManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Spawn a subagent to handle a delegated task. Comparable to Claude Code's Agent tool
 * and OpenCode/Codex task delegation.
 *
 * The subagent runs in a child session with its own context window and tool access,
 * then returns its final response as the tool result. Subagents cannot spawn
 * additional subagents (no recursive delegation).
 *
 * Available subagent types include built-in agents (explore-quick, explore-deep,
 * general, code-reviewer, architect, researcher) and any custom agents loaded
 * from .kompile/agents/ or ~/.kompile/agents/ directories.
 */
public class TaskTool implements CliTool {

    private final AgentRegistry agentRegistry;
    private final SubagentRunner subagentRunner;
    private final RoleManager roleManager;

    public TaskTool(AgentRegistry agentRegistry, SubagentRunner subagentRunner) {
        this(agentRegistry, subagentRunner, null);
    }

    public TaskTool(AgentRegistry agentRegistry, SubagentRunner subagentRunner, RoleManager roleManager) {
        if (subagentRunner == null) {
            throw new IllegalArgumentException("SubagentRunner must not be null. " +
                    "TaskTool requires a properly configured subagent runner for delegation.");
        }
        this.agentRegistry = agentRegistry;
        this.subagentRunner = subagentRunner;
        this.roleManager = roleManager;
    }

    /**
     * Validate that this TaskTool is properly configured and ready for use.
     *
     * @return true if properly configured, false otherwise
     */
    public boolean isHealthy() {
        return subagentRunner != null && agentRegistry != null;
    }

    /**
     * Get the list of available subagent types.
     *
     * @return list of subagent configs
     */
    public List<AgentConfig> getAvailableSubagents() {
        return agentRegistry.getSubagents();
    }

    @Override
    public String id() { return "task"; }

    @Override
    public String description() {
        StringBuilder desc = new StringBuilder();
        desc.append("Delegate a task to a specialized subagent. The subagent runs autonomously ")
                .append("with its own context window and tools, then returns a result. Use this for:\n")
                .append("- Codebase exploration and research (use explore-quick or explore-deep)\n")
                .append("- Code review of changes (use code-reviewer)\n")
                .append("- Architecture analysis and planning (use architect)\n")
                .append("- Web research and documentation lookup (use researcher)\n")
                .append("- Complex multi-step tasks requiring full tool access (use general)\n\n")
                .append("Available subagent types:\n");

        for (AgentConfig agent : agentRegistry.getSubagents()) {
            String agentDesc = agent.getDescription() != null && !agent.getDescription().isEmpty()
                    ? agent.getDescription() : agent.getDisplayName();
            desc.append("- '").append(agent.getName()).append("': ").append(agentDesc);
            if ("fast".equals(agent.getModelHint())) {
                desc.append(" [fast]");
            }
            if (agent.isCustom()) {
                desc.append(" [custom]");
            }
            desc.append("\n");
        }

        desc.append("\nFor direct-model chat, model and thinking select this child's model and reasoning effort ")
                .append("on the parent's provider without changing parent/global settings. ")
                .append("Use role instead of agent_type to select a named role's prompt and tool policy. ")
                .append("CLI agent selection and per-CLI role defaults belong to the MCP task tool, not this direct chat tool.\n")
                .append("In standard chat, Ctrl+B backgrounds a running subagent invocation. ")
                .append("Select its activity row and press Delete to stop it, or open it and type ")
                .append("to continue its retained conversation.");
        return desc.toString();
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode description = props.putObject("description");
        description.put("type", "string");
        description.put("description", "A short (3-5 word) description of the delegated task");

        ObjectNode prompt = props.putObject("prompt");
        prompt.put("type", "string");
        prompt.put("description", "Detailed task description for the subagent. Include all " +
                "necessary context — the subagent has no access to the parent conversation.");

        // Build enum from registered subagents
        List<AgentConfig> subagents = agentRegistry.getSubagents();
        ObjectNode agentType = props.putObject("agent_type");
        agentType.put("type", "string");

        StringBuilder agentDesc = new StringBuilder("Subagent type. ");
        agentDesc.append("Use 'explore-quick' for fast file/code lookups. ");
        agentDesc.append("Use 'explore-deep' for thorough codebase analysis. ");
        agentDesc.append("Use 'code-reviewer' for reviewing changes. ");
        agentDesc.append("Use 'architect' for design and planning. ");
        agentDesc.append("Use 'researcher' for web search and documentation. ");
        agentDesc.append("Use 'general' for full-access multi-step tasks. ");
        agentDesc.append("Default: 'explore-quick'");
        agentType.put("description", agentDesc.toString());

        ArrayNode enumValues = agentType.putArray("enum");
        for (AgentConfig a : subagents) {
            enumValues.add(a.getName());
        }

        props.putObject("model").put("type", "string").put("description",
                "Request-scoped model id on the parent's direct chat provider. Omit to inherit; never changes defaults.");
        props.putObject("thinking").put("type", "string").put("description",
                "Request-scoped provider-native reasoning effort (e.g. xhigh). Direct-model chat only; omit to inherit.");
        props.putObject("role").put("type", "string").put("description",
                "Named role's prompt and tool policy, instead of agent_type. Does not apply CLI-agent launch defaults. "
                        + "Recursive delegation remains disabled.");
        schema.putArray("required").add("description").add("prompt");
        return schema;
    }

    @Override
    public String permissionKey() { return "task"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Spawn subagent");

        String desc = params.path("description").asText("");
        String prompt = params.path("prompt").asText("");
        String agentType = params.path("agent_type").asText("explore-quick");

        if (prompt.isEmpty()) {
            return ToolResult.error("prompt is required");
        }

        if (params.hasNonNull("agent")) {
            return ToolResult.error("Chat task inherits the parent's provider; CLI agent selection requires the MCP task tool.");
        }
        for (String selector : List.of("model", "thinking", "role")) {
            if (params.hasNonNull(selector) && !params.get(selector).isTextual()) {
                return ToolResult.error(selector + " must be a string");
            }
        }
        String model = params.path("model").asText("").trim();
        String thinking = params.path("thinking").asText("").trim();
        String roleName = params.path("role").asText("").trim();
        AgentConfig subagentConfig;
        if (!roleName.isEmpty()) {
            if (params.hasNonNull("agent_type")) {
                return ToolResult.error("Specify either role or agent_type, not both.");
            }
            RoleConfig role = roleManager != null ? roleManager.getRole(roleName) : agentRegistry.getRole(roleName);
            if (role == null) return ToolResult.error("Unknown role: " + roleName);
            subagentConfig = role.toAgentConfig().toBuilder()
                    .isSubagent(true).canSpawnSubagents(false).roleName(roleName).build();
            agentType = roleName;
        } else {
            subagentConfig = agentRegistry.get(agentType);
            if (subagentConfig == null || !subagentConfig.isSubagent()) {
                String available = agentRegistry.getSubagents().stream()
                        .map(AgentConfig::getName)
                        .collect(Collectors.joining(", "));
                return ToolResult.error("Unknown subagent type: " + agentType + ". Available: " + available);
            }
        }
        if (!model.isEmpty() && !subagentConfig.isModelAllowed(model)) {
            return ToolResult.error("Model is not allowed for " + agentType + ": " + model);
        }
        AgentConfig.Builder child = subagentConfig.toBuilder().canSpawnSubagents(false);
        if (!model.isEmpty()) child.modelOverride(model);
        if (!thinking.isEmpty()) child.thinkingOverride(thinking);
        subagentConfig = child.build();

        context.emitOutput("  [Spawning " + agentType + " subagent: " + desc + "]");

        try {
            String result = subagentRunner.runSubagent(subagentConfig, prompt, context);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("agentType", agentType);
            metadata.put("description", desc);
            if (subagentConfig.getModelOverride() != null) metadata.put("model", subagentConfig.getModelOverride());
            if (subagentConfig.getThinkingOverride() != null) metadata.put("thinking", subagentConfig.getThinkingOverride());
            if (!roleName.isEmpty()) metadata.put("role", roleName);
            return ToolResult.success("subagent:" + agentType, result, metadata);
        } catch (Exception e) {
            return ToolResult.error("Subagent execution failed: " + e.getMessage());
        }
    }
}
