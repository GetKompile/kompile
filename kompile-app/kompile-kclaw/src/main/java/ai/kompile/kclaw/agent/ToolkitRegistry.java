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
package ai.kompile.kclaw.agent;

import ai.kompile.kclaw.model.AgentDefinition;
import ai.kompile.kclaw.tool.ShellExecutionTool;
import ai.kompile.kclaw.tool.MemoryTool;
import ai.kompile.react.context.Toolkit;
import ai.kompile.react.context.impl.DefaultToolkit;
import ai.kompile.react.model.ToolDefinition;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class ToolkitRegistry {

    private final Map<String, ToolDefinition> tools = new ConcurrentHashMap<>();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ToolkitRegistry(
            ShellExecutionTool shellTool,
            MemoryTool memoryTool) {
        registerTool(shellTool.getToolDefinition());
        registerTool(memoryTool.getSaveToolDefinition());
        registerTool(memoryTool.getSearchToolDefinition());
    }

    public void registerTool(ToolDefinition tool) {
        tools.put(tool.getName(), tool);
        log.debug("Registered tool: {}", tool.getName());
    }

    /**
     * Registers all Spring AI @Tool-annotated methods from a bean by bridging
     * ToolCallback instances into the native ToolDefinition format.
     */
    public void registerSpringAiTools(Object toolBean) {
        try {
            Class<?> toolCallbacksClass = Class.forName("org.springframework.ai.tool.ToolCallbacks");
            Method fromMethod = toolCallbacksClass.getMethod("from", Object[].class);
            Object[] callbacks = (Object[]) fromMethod.invoke(null, (Object) new Object[]{toolBean});

            for (Object callback : callbacks) {
                try {
                    registerToolCallback(callback);
                } catch (Exception e) {
                    log.warn("Failed to register tool from {}: {}", toolBean.getClass().getSimpleName(), e.getMessage());
                }
            }
        } catch (ClassNotFoundException e) {
            log.debug("Spring AI ToolCallbacks not on classpath, skipping auto-discovery for {}", toolBean.getClass().getSimpleName());
        } catch (Exception e) {
            log.warn("Failed to register Spring AI tools from {}: {}", toolBean.getClass().getSimpleName(), e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void registerToolCallback(Object callback) throws Exception {
        // Get the tool definition from the callback
        Method getDefMethod = callback.getClass().getMethod("getToolDefinition");
        Object springDef = getDefMethod.invoke(callback);

        Method nameMethod = springDef.getClass().getMethod("name");
        Method descMethod = springDef.getClass().getMethod("description");
        Method schemaMethod = springDef.getClass().getMethod("inputSchema");

        String name = (String) nameMethod.invoke(springDef);
        String description = (String) descMethod.invoke(springDef);
        String inputSchema = (String) schemaMethod.invoke(springDef);

        Map<String, Object> parameters = Map.of();
        if (inputSchema != null && !inputSchema.isBlank()) {
            try {
                parameters = MAPPER.readValue(inputSchema, new TypeReference<>() {});
            } catch (Exception e) {
                log.debug("Could not parse input schema for tool {}", name);
            }
        }

        // Get the call method to create the executor
        Method callMethod = callback.getClass().getMethod("call", String.class);

        ToolDefinition td = ToolDefinition.builder()
                .name(name)
                .description(description)
                .parameters(parameters)
                .executor(args -> {
                    try {
                        String argsJson = MAPPER.writeValueAsString(args);
                        return (String) callMethod.invoke(callback, argsJson);
                    } catch (Exception e) {
                        log.error("Error executing Spring AI tool {}", name, e);
                        return "Error: " + e.getMessage();
                    }
                })
                .build();

        registerTool(td);
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

    /**
     * Returns tool metadata (name + description) for the UI without exposing executors.
     */
    public List<Map<String, String>> getToolMetadata() {
        List<Map<String, String>> metadata = new ArrayList<>();
        for (ToolDefinition tool : tools.values()) {
            metadata.add(Map.of(
                    "name", tool.getName(),
                    "description", tool.getDescription() != null ? tool.getDescription() : ""
            ));
        }
        return metadata;
    }
}
