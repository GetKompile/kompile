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

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A local CLI tool that executes on the client side, comparable to OpenCode's
 * tool system. Tools can read/write files, execute shell commands, search
 * codebases, and delegate work to subagents.
 */
public interface CliTool {

    /** Unique tool identifier (e.g. "read", "bash", "grep"). */
    String id();

    /** Human-readable description for the LLM system prompt. */
    String description();

    /** JSON Schema describing the tool's parameters. */
    JsonNode parameterSchema();

    /** The permission key for this tool (e.g. "read", "edit", "bash"). */
    String permissionKey();

    /**
     * Execute the tool with the given parameters and context.
     *
     * @param params  validated parameters matching {@link #parameterSchema()}
     * @param context execution context with session info, permissions, abort signal
     * @return result containing output text, title, and metadata
     * @throws ToolExecutionException if the tool fails
     */
    ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException;

    /**
     * MCP tool annotations that hint to clients about this tool's behavior.
     * Defaults to {@link McpToolAnnotations#WRITE} — tools that modify local state
     * but are not destructive. Override in subclasses for more precise hints.
     */
    default McpToolAnnotations mcpAnnotations() {
        return McpToolAnnotations.WRITE;
    }
}
