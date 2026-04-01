/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.openclaw.agent;

import ai.kompile.openclaw.model.AgentDefinition;
import ai.kompile.openclaw.tool.ShellExecutionTool;
import ai.kompile.openclaw.tool.MemoryTool;
import ai.kompile.react.context.Toolkit;
import ai.kompile.react.context.impl.DefaultToolkit;
import ai.kompile.react.model.ToolDefinition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ToolkitRegistry {

    private final Map<String, ToolDefinition> tools = new HashMap<>();

    public ToolkitRegistry(
            ShellExecutionTool shellTool,
            MemoryTool memoryTool) {
        registerTool(shellTool.getToolDefinition());
        registerTool(memoryTool.getSaveToolDefinition());
        registerTool(memoryTool.getSearchToolDefinition());
    }

    public void registerTool(ToolDefinition tool) {
        tools.put(tool.getName(), tool);
    }

    public Toolkit getToolkit(AgentDefinition agentDef) {
        List<ToolDefinition> agentTools = new ArrayList<>();
        
        for (String toolName : agentDef.getTools()) {
            ToolDefinition tool = tools.get(toolName);
            if (tool != null) {
                agentTools.add(tool);
            }
        }

        return new DefaultToolkit(agentTools);
    }

    public List<ToolDefinition> getAllTools() {
        return new ArrayList<>(tools.values());
    }

    public List<String> getToolNames() {
        return new ArrayList<>(tools.keySet());
    }
}
