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

import com.fasterxml.jackson.databind.JsonNode; // For JSON Schema representation
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents the definition of an MCP tool, typically used when listing
 * available tools. It includes the tool's name, a description of its function,
 * and a JSON schema defining its input parameters.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class McpToolDefinition {
    /**
     * The unique name of the tool.
     */
    private String name;

    /**
     * A human-readable description of what the tool does, its purpose,
     * and potentially how to use it.
     */
    private String description;

    /**
     * A JSON schema object that defines the structure and types of the input
     * arguments the tool expects.
     */
    private JsonNode inputSchema;

    // Consider adding:
    // private JsonNode outputSchema; // To define the structure of the tool's output
}