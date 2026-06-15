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
package ai.kompile.react.context;

import ai.kompile.react.model.ToolCall;
import ai.kompile.react.model.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Toolkit interface for managing and executing tools.
 * Provides tool discovery, registration, and execution capabilities.
 */
public interface Toolkit {

    /**
     * Get all available tool definitions.
     *
     * @return List of tool definitions
     */
    List<ToolDefinition> getTools();

    /**
     * Get a tool by name.
     *
     * @param name The tool name
     * @return The tool definition, if found
     */
    Optional<ToolDefinition> getTool(String name);

    /**
     * Register a new tool.
     *
     * @param tool The tool definition
     */
    void registerTool(ToolDefinition tool);

    /**
     * Unregister a tool by name.
     *
     * @param name The tool name
     * @return true if the tool was removed
     */
    boolean unregisterTool(String name);

    /**
     * Execute a tool call.
     *
     * @param toolCall The tool call to execute
     * @return The result of the tool execution
     */
    CompletableFuture<String> execute(ToolCall toolCall);

    /**
     * Execute a tool call synchronously.
     *
     * @param toolCall The tool call to execute
     * @return The result of the tool execution
     */
    default String executeSync(ToolCall toolCall) {
        return execute(toolCall).join();
    }

    /**
     * Execute multiple tool calls in parallel.
     *
     * @param toolCalls The tool calls to execute
     * @return A map of tool call IDs to their results
     */
    CompletableFuture<Map<String, String>> executeParallel(List<ToolCall> toolCalls);

    /**
     * Check if a tool exists.
     *
     * @param name The tool name
     * @return true if the tool exists
     */
    default boolean hasTool(String name) {
        return getTool(name).isPresent();
    }

    /**
     * Get tool definitions formatted for LLM consumption (JSON Schema format).
     *
     * @return List of tool schemas for the LLM
     */
    List<Map<String, Object>> getToolSchemas();
}
