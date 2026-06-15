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

package ai.kompile.core.mcp.server;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for an MCP server instance.
 * Represents a complete server definition including tools, resources, and prompts.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpServerConfig {

    /**
     * Unique identifier for this server configuration
     */
    private String id;

    /**
     * Human-readable name for the server
     */
    private String name;

    /**
     * Server version (semantic versioning recommended)
     */
    @Builder.Default
    private String version = "1.0.0";

    /**
     * Description of the server's purpose and capabilities
     */
    private String description;

    /**
     * Transport type for the server (STDIO, SSE, STREAMABLE_HTTP)
     */
    @Builder.Default
    private TransportType transportType = TransportType.SSE;

    /**
     * Port to run the server on (for HTTP-based transports)
     */
    @Builder.Default
    private Integer port = 8081;

    /**
     * Base path for the server endpoints
     */
    @Builder.Default
    private String basePath = "/mcp";

    /**
     * List of tool configurations
     */
    @Builder.Default
    private List<McpToolConfig> tools = new ArrayList<>();

    /**
     * List of resource configurations
     */
    @Builder.Default
    private List<McpResourceConfig> resources = new ArrayList<>();

    /**
     * List of prompt configurations
     */
    @Builder.Default
    private List<McpPromptConfig> prompts = new ArrayList<>();

    /**
     * Whether logging is enabled for the server
     */
    @Builder.Default
    private boolean loggingEnabled = true;

    /**
     * Whether completions (autocomplete) are enabled
     */
    @Builder.Default
    private boolean completionsEnabled = false;

    /**
     * Server status
     */
    @Builder.Default
    private ServerStatus status = ServerStatus.STOPPED;

    /**
     * Creation timestamp
     */
    private Instant createdAt;

    /**
     * Last modified timestamp
     */
    private Instant updatedAt;

    /**
     * Transport types supported by MCP
     */
    public enum TransportType {
        STDIO,
        SSE,
        STREAMABLE_HTTP,
        STATELESS_STREAMABLE_HTTP
    }

    /**
     * Server runtime status
     */
    public enum ServerStatus {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING,
        ERROR
    }
}
