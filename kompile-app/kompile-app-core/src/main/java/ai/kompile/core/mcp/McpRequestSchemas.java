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

package ai.kompile.core.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List; // For ListToolsResult and potentially ContentBlock list

/**
 * Container for simplified Data Transfer Objects (DTOs) that model parts of
 * Model Context Protocol (MCP) JSON-RPC messages, primarily for tool interactions.
 * These are for conceptual mapping or if building/parsing MCP messages at a lower level.
 * Spring AI's MCP client/server typically abstracts the direct JSON-RPC handling.
 */
public class McpRequestSchemas {

    /**
     * Represents the "params" field for an MCP "tools/call" request.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CallToolParams {
        /**
         * The name of the tool to be invoked.
         */
        private String name;

        /**
         * The arguments for the tool, represented as a JSON object.
         * The structure should conform to the tool's inputSchema.
         * MCP also supports "tool_input" as a list of content blocks; this simplifies
         * to the common "arguments" pattern seen in function calling.
         */
        private JsonNode arguments;

        // An optional request ID (string or number) can be part of the outer JSON-RPC message,
        // not typically part of the "params" object itself for tools/call.
    }

    /**
     * Represents the content of a successful "tools/call" response's "result" field.
     * Note: MCP tool call results are often a list of content blocks. This DTO
     * simplifies it for common use cases where a single JSON object or a specific
     * content structure is returned. For full MCP compliance with content blocks,
     * this might be List<ContentBlock>.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CallToolResult {
        /**
         * The output of the tool. This could be a simple JSON object/value or,
         * more conformantly with MCP, a list of ContentBlock objects.
         * Using JsonNode here for flexibility.
         */
        private JsonNode content;

        /**
         * Indicates if the tool execution itself resulted in an error that the
         * model should be aware of (distinct from JSON-RPC protocol errors).
         * E.g., a content block with type="text", text="Error: ...", isError=true.
         * This field might be better placed within a ContentBlock structure if used.
         */
        private boolean isError; // This flag's placement might vary based on exact MCP interpretation.
        // Often, errors are part of the content blocks themselves.
    }

    /**
     * Represents the "result" field for an MCP "tools/list" response.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ListToolsResult {
        /**
         * A list of tool definitions available on the server.
         */
        private List<McpToolDefinition> tools;
    }

    /**
     * An illustrative (simplified) representation of an MCP Content Block.
     * MCP often uses lists of these for inputs (tool_input) and outputs (tool_output/result content).
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContentBlock {
        /**
         * The type of the content (e.g., "text", "json", "image_uri", "error").
         */
        private String type;

        /**
         * The actual content, its structure depends on the 'type'.
         * For type="text", this would be a string.
         * For type="json", this would be a JSON object/array.
         * For type="image_uri", this might be a URL string.
         * Using JsonNode for flexibility here, but could be more specific.
         */
        private JsonNode contentValue; // Renamed from 'content' to avoid clash if used in CallToolResult

        /**
         * The textual representation of the content, especially if type="text".
         */
        private String text;

        /**
         * Optional source identifier, e.g., a URI for an image or a reference.
         */
        private String source;

        /**
         * Optional flag indicating if this content block represents an error from the tool.
         */
        private Boolean isError;

        // Other fields might include 'name' (for named inputs), 'media_type', 'encoding', etc.
    }
}