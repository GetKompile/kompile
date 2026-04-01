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
    private final Map<String, CliTool> tools = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public ToolRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void register(CliTool tool) {
        tools.put(tool.id(), tool);
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
     * Get tools available to a specific agent, filtered by the agent's
     * enabled tools list.
     */
    public List<CliTool> getToolsForAgent(AgentConfig agent) {
        Set<String> enabled = agent.getEnabledTools();
        if (enabled == null || enabled.contains("*")) {
            return new ArrayList<>(tools.values());
        }
        List<CliTool> result = new ArrayList<>();
        for (CliTool tool : tools.values()) {
            if (enabled.contains(tool.id())) {
                result.add(tool);
            }
        }
        return result;
    }

    /**
     * Build the tools array for an LLM API call, formatted as OpenAI-compatible
     * function definitions.
     */
    public ArrayNode buildToolDefinitions(AgentConfig agent) {
        ArrayNode toolsArray = objectMapper.createArrayNode();
        for (CliTool tool : getToolsForAgent(agent)) {
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
