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

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.agent.SubagentRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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

    public TaskTool(AgentRegistry agentRegistry, SubagentRunner subagentRunner) {
        if (subagentRunner == null) {
            throw new IllegalArgumentException("SubagentRunner must not be null. " +
                    "TaskTool requires a properly configured subagent runner for delegation.");
        }
        this.agentRegistry = agentRegistry;
        this.subagentRunner = subagentRunner;
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

        desc.append("\nThe subagent runs once and returns — it cannot send follow-up messages.");
        return desc.toString();
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = new ObjectMapper();
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

        AgentConfig subagentConfig = agentRegistry.get(agentType);
        if (subagentConfig == null || !subagentConfig.isSubagent()) {
            String available = agentRegistry.getSubagents().stream()
                    .map(AgentConfig::getName)
                    .collect(Collectors.joining(", "));
            return ToolResult.error("Unknown subagent type: " + agentType +
                    ". Available: " + available);
        }

        System.out.println("  [Spawning " + agentType + " subagent: " + desc + "]");

        try {
            String result = subagentRunner.runSubagent(subagentConfig, prompt, context);
            return ToolResult.success("subagent:" + agentType, result,
                    Map.of("agentType", agentType, "description", desc));
        } catch (Exception e) {
            return ToolResult.error("Subagent execution failed: " + e.getMessage());
        }
    }
}
