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
package ai.kompile.react.context.impl;

import ai.kompile.react.context.Toolkit;
import ai.kompile.react.model.ToolCall;
import ai.kompile.react.model.ToolDefinition;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Default implementation of the Toolkit interface.
 * Provides tool registration, discovery, and execution.
 */
@Slf4j
public class DefaultToolkit implements Toolkit {

    private final Map<String, ToolDefinition> tools = new ConcurrentHashMap<>();

    public DefaultToolkit() {
    }

    public DefaultToolkit(List<ToolDefinition> initialTools) {
        if (initialTools != null) {
            for (ToolDefinition tool : initialTools) {
                registerTool(tool);
            }
        }
    }

    @Override
    public List<ToolDefinition> getTools() {
        return new ArrayList<>(tools.values());
    }

    @Override
    public Optional<ToolDefinition> getTool(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    @Override
    public void registerTool(ToolDefinition tool) {
        if (tool == null || tool.getName() == null) {
            throw new IllegalArgumentException("Tool and tool name cannot be null");
        }
        tools.put(tool.getName(), tool);
        log.debug("Registered tool: {}", tool.getName());
    }

    @Override
    public boolean unregisterTool(String name) {
        ToolDefinition removed = tools.remove(name);
        if (removed != null) {
            log.debug("Unregistered tool: {}", name);
            return true;
        }
        return false;
    }

    @Override
    public CompletableFuture<String> execute(ToolCall toolCall) {
        return CompletableFuture.supplyAsync(() -> {
            String toolName = toolCall.getName();
            ToolDefinition tool = tools.get(toolName);

            if (tool == null) {
                String error = "Tool not found: " + toolName;
                log.warn(error);
                return error;
            }

            try {
                log.debug("Executing tool: {} with args: {}", toolName, toolCall.getArguments());
                String result = tool.execute(toolCall.getArguments());
                log.debug("Tool {} completed successfully", toolName);
                return result;
            } catch (Exception e) {
                String error = "Tool execution failed: " + e.getMessage();
                log.error("Error executing tool {}: {}", toolName, e.getMessage(), e);
                return error;
            }
        });
    }

    @Override
    public CompletableFuture<Map<String, String>> executeParallel(List<ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyMap());
        }

        // Create a future for each tool call
        List<CompletableFuture<Map.Entry<String, String>>> futures = toolCalls.stream()
                .map(tc -> execute(tc)
                        .thenApply(result -> Map.entry(tc.getId(), result)))
                .collect(Collectors.toList());

        // Combine all futures
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join)
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    @Override
    public List<Map<String, Object>> getToolSchemas() {
        return tools.values().stream()
                .map(this::toSchema)
                .collect(Collectors.toList());
    }

    private Map<String, Object> toSchema(ToolDefinition tool) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "function");

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", tool.getName());
        function.put("description", tool.getDescription());

        if (tool.getParameters() != null) {
            function.put("parameters", tool.getParameters());
        } else {
            // Default empty parameters
            function.put("parameters", Map.of(
                    "type", "object",
                    "properties", Collections.emptyMap()
            ));
        }

        schema.put("function", function);
        return schema;
    }
}
