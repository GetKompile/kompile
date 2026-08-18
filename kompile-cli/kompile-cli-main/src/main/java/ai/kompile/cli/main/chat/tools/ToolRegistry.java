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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry for all CLI tools. Manages built-in tools and provides
 * filtered tool lists based on agent permissions.
 */
public class ToolRegistry {
    private static final Set<String> AGENT_CRAWL_LIFECYCLE_TOOLS = Set.of(
            "crawl_discover", "model_runtime", "pipeline", "crawl_documents", "crawl_source", "crawl_control", "crawl_result",
            "knowledge_status", "knowledge_search", "graph_reasoning_query",
            "ask_graph_assert", "ask_graph_retract", "activate_tools");

    private final Map<String, CliTool> tools = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final DynamicToolManager dynamicToolManager;
    private volatile ai.kompile.cli.main.chat.agent.SubagentRunner subagentRunner;

    public ToolRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.dynamicToolManager = new DynamicToolManager();
    }

    /**
     * Set the subagent runner used by the task tool.
     * Allows external callers (e.g. StatusBar) to attach lifecycle listeners.
     */
    public void setSubagentRunner(ai.kompile.cli.main.chat.agent.SubagentRunner runner) {
        this.subagentRunner = runner;
    }

    /**
     * Get the subagent runner, if set.
     */
    public ai.kompile.cli.main.chat.agent.SubagentRunner getSubagentRunner() {
        return subagentRunner;
    }

    public void register(CliTool tool) {
        Objects.requireNonNull(tool, "tool");
        String id = tool.id();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Tool id must not be blank");
        }
        tools.put(id, tool);
        dynamicToolManager.register(id, tool.description(), tool.parameterSchema());
    }

    public void unregister(String id) {
        tools.remove(id);
        dynamicToolManager.unregister(id);
    }

    public CliTool get(String id) {
        return tools.get(id);
    }

    public Collection<CliTool> all() {
        return Collections.unmodifiableCollection(tools.values());
    }

    public Set<String> ids() {
        return Collections.unmodifiableSet(tools.keySet());
    }

    /**
     * Get tools available to a specific agent. The agent's enabled-tools list
     * remains authoritative for ordinary tools, while the complete crawl lifecycle
     * is platform-level so every agent can start, inspect, reason over, and update
     * a crawl. Mutating calls remain subject to the permission service.
     */
    public List<CliTool> getToolsForAgent(AgentConfig agent) {
        Set<String> enabled = agent.getEnabledTools();
        if (enabled == null || enabled.contains("*")) {
            return new ArrayList<>(tools.values());
        }
        List<CliTool> result = new ArrayList<>();
        for (CliTool tool : tools.values()) {
            if (enabled.contains(tool.id())
                    || AGENT_CRAWL_LIFECYCLE_TOOLS.contains(tool.id())) {
                result.add(tool);
            }
        }
        return result;
    }

    /**
     * Return only the currently active progressive tool surface, intersected with
     * the agent's normal permissions. Activation order is preserved so the small
     * model sees stable, task-oriented definitions.
     */
    public List<CliTool> getProgressiveToolsForAgent(AgentConfig agent) {
        Map<String, CliTool> allowed = new HashMap<>();
        for (CliTool tool : getToolsForAgent(agent)) {
            allowed.put(tool.id(), tool);
        }
        List<CliTool> result = new ArrayList<>();
        for (String id : dynamicToolManager.getActiveToolIds()) {
            CliTool tool = allowed.get(id);
            if (tool != null) {
                result.add(tool);
            }
        }
        return result;
    }

    public DynamicToolManager getDynamicToolManager() {
        return dynamicToolManager;
    }

    /**
     * Seed the one capability group implied by a specialized agent role. General
     * agents stay on the core surface and discover groups through activate_tools.
     */
    public void prepareProgressiveTools(AgentConfig agent) {
        if (agent == null || agent.getName() == null) {
            return;
        }
        switch (agent.getName()) {
            case "crawler", "crawl-worker" -> dynamicToolManager.activateGroup("crawl");
            case "researcher" -> dynamicToolManager.activateGroup("web");
            case "explore-quick", "explore-deep", "code-reviewer", "architect" -> {
                dynamicToolManager.activateGroup("files");
                dynamicToolManager.activateGroup("code");
            }
            default -> {
                // The compact core plus activate_tools is intentional.
            }
        }
    }

    /**
     * Build the tools array for an LLM API call, formatted as OpenAI-compatible
     * function definitions.
     */
    public ArrayNode buildToolDefinitions(AgentConfig agent) {
        return buildToolDefinitions(getToolsForAgent(agent));
    }

    /** Build OpenAI-compatible definitions for the progressive tool surface. */
    public ArrayNode buildProgressiveToolDefinitions(AgentConfig agent) {
        return ToolSchemaOptimizer.optimize(
                buildToolDefinitions(getProgressiveToolsForAgent(agent)),
                ToolSchemaOptimizer.OptimizationLevel.MODERATE);
    }

    private ArrayNode buildToolDefinitions(List<CliTool> selectedTools) {
        ArrayNode toolsArray = objectMapper.createArrayNode();
        for (CliTool tool : selectedTools) {
            ObjectNode toolDef = objectMapper.createObjectNode();
            toolDef.put("type", "function");
            ObjectNode function = toolDef.putObject("function");
            function.put("name", tool.id());
            function.put("description", tool.description());
            function.set("parameters", tool.parameterSchema());
            toolsArray.add(toolDef);
        }
        return toolsArray;
    }

    /**
     * Build provider-neutral definitions for DirectLlmClient. Direct provider
     * adapters translate this MCP-style shape to Responses, Chat Completions,
     * Anthropic, or Pi without losing the tool name.
     */
    public ArrayNode buildDirectToolDefinitions(AgentConfig agent) {
        return buildDirectToolDefinitions(getToolsForAgent(agent));
    }

    /** Build provider-neutral definitions for the progressive tool surface. */
    public ArrayNode buildProgressiveDirectToolDefinitions(AgentConfig agent) {
        return ToolSchemaOptimizer.optimize(
                buildDirectToolDefinitions(getProgressiveToolsForAgent(agent)),
                ToolSchemaOptimizer.OptimizationLevel.MODERATE);
    }

    private ArrayNode buildDirectToolDefinitions(List<CliTool> selectedTools) {
        ArrayNode toolsArray = objectMapper.createArrayNode();
        for (CliTool tool : selectedTools) {
            ObjectNode toolDef = objectMapper.createObjectNode();
            toolDef.put("name", tool.id());
            toolDef.put("description", tool.description());
            toolDef.set("inputSchema", tool.parameterSchema());
            toolsArray.add(toolDef);
        }
        return toolsArray;
    }

    /**
     * Build tool descriptions for inclusion in a system prompt (for models
     * that don't support native tool calling).
     */
    public String buildToolDescriptionsText(AgentConfig agent) {
        StringBuilder sb = new StringBuilder();
        sb.append("You have access to the following tools:\n\n");
        for (CliTool tool : getToolsForAgent(agent)) {
            sb.append("## ").append(tool.id()).append("\n");
            sb.append(tool.description()).append("\n");
            sb.append("Parameters: ").append(tool.parameterSchema().toString()).append("\n\n");
        }
        return sb.toString();
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }
}
