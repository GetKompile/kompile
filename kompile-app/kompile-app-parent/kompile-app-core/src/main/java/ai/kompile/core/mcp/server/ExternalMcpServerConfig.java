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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for an external MCP server that Kompile connects to as a client.
 * Supports both STDIO (Claude Desktop format) and REST/SSE transport types.
 *
 * This is distinct from McpServerConfig which represents servers that Kompile hosts.
 * ExternalMcpServerConfig represents servers that Kompile connects to as a client.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExternalMcpServerConfig {

    /**
     * Transport type for connecting to the MCP server.
     */
    public enum TransportType {
        /** STDIO transport - spawn process and communicate via stdin/stdout */
        STDIO,
        /** REST/SSE transport - connect to remote HTTP endpoint */
        REST,
        /** SSE transport - server-sent events for streaming */
        SSE
    }

    /**
     * Unique identifier/name for this server (used as key in mcpServers map).
     * This is the server name like "filesystem", "github", etc.
     */
    private String id;

    /**
     * Transport type for this server connection.
     * Defaults to STDIO for backward compatibility with Claude Desktop format.
     */
    @Builder.Default
    private TransportType transportType = TransportType.STDIO;

    // ==================== STDIO Configuration ====================

    /**
     * The command to execute to start the MCP server (STDIO only).
     * Examples: "npx", "node", "python", "uvx", etc.
     */
    private String command;

    /**
     * Arguments to pass to the command (STDIO only).
     * Example: ["-y", "@modelcontextprotocol/server-filesystem", "/path/to/files"]
     */
    @Builder.Default
    private List<String> args = new ArrayList<>();

    /**
     * Environment variables to set when spawning the process (STDIO only).
     * Example: {"GITHUB_PERSONAL_ACCESS_TOKEN": "ghp_xxx"}
     */
    @Builder.Default
    private Map<String, String> env = new HashMap<>();

    // ==================== REST/SSE Configuration ====================

    /**
     * Base URL for the MCP server (REST/SSE only).
     * Example: "http://localhost:3000/mcp" or "https://mcp-server.example.com"
     */
    private String url;

    /**
     * HTTP headers to include in requests (REST/SSE only).
     * Used for authentication, API keys, etc.
     * Example: {"Authorization": "Bearer token123", "X-API-Key": "key"}
     */
    @Builder.Default
    private Map<String, String> headers = new HashMap<>();

    /**
     * Connection timeout in milliseconds (REST/SSE only).
     * Default: 30000 (30 seconds)
     */
    @Builder.Default
    private Integer connectionTimeout = 30000;

    /**
     * Request timeout in milliseconds (REST/SSE only).
     * Default: 60000 (60 seconds)
     */
    @Builder.Default
    private Integer requestTimeout = 60000;

    /**
     * Whether to verify SSL certificates (REST/SSE only).
     * Set to false for self-signed certificates (not recommended for production).
     */
    @Builder.Default
    private Boolean verifySsl = true;

    /**
     * SSE endpoint path for server-sent events (SSE only).
     * Default: "/sse"
     */
    private String sseEndpoint;

    /**
     * Messages endpoint path for sending requests (REST/SSE only).
     * Default: "/message" or derived from base URL
     */
    private String messagesEndpoint;

    /**
     * Whether this server configuration is enabled.
     * Disabled servers will not be started automatically.
     */
    @Builder.Default
    private boolean enabled = true;

    /**
     * Optional human-readable description of what this server does.
     * Not part of Claude Desktop format but useful for UI.
     */
    private String description;

    /**
     * Current runtime status of the server.
     * Not persisted to config file.
     */
    @JsonIgnore
    @Builder.Default
    private ServerStatus status = ServerStatus.STOPPED;

    /**
     * Timestamp when this server was last started.
     * Not persisted to config file.
     */
    @JsonIgnore
    private Instant lastStarted;

    /**
     * Timestamp when this server was last stopped.
     * Not persisted to config file.
     */
    @JsonIgnore
    private Instant lastStopped;

    /**
     * Error message if the server is in ERROR status.
     * Not persisted to config file.
     */
    @JsonIgnore
    private String errorMessage;

    /**
     * Process ID if the server is running.
     * Not persisted to config file.
     */
    @JsonIgnore
    private Long pid;

    /**
     * Server runtime status enumeration.
     */
    public enum ServerStatus {
        /** Server is not running */
        STOPPED,
        /** Server is in the process of starting */
        STARTING,
        /** Server is running and connected */
        RUNNING,
        /** Server is in the process of stopping */
        STOPPING,
        /** Server encountered an error */
        ERROR
    }

    /**
     * Creates a copy of this config suitable for serialization.
     * Excludes runtime-only fields.
     */
    public ExternalMcpServerConfig toSerializableConfig() {
        ExternalMcpServerConfig.ExternalMcpServerConfigBuilder builder = ExternalMcpServerConfig.builder()
                .id(id)
                .transportType(transportType)
                .enabled(enabled)
                .description(description);

        if (transportType == TransportType.STDIO || transportType == null) {
            // STDIO configuration
            builder.command(command)
                    .args(args != null ? new ArrayList<>(args) : new ArrayList<>())
                    .env(env != null ? new HashMap<>(env) : new HashMap<>());
        } else {
            // REST/SSE configuration
            builder.url(url)
                    .headers(headers != null ? new HashMap<>(headers) : new HashMap<>())
                    .connectionTimeout(connectionTimeout)
                    .requestTimeout(requestTimeout)
                    .verifySsl(verifySsl)
                    .sseEndpoint(sseEndpoint)
                    .messagesEndpoint(messagesEndpoint);
        }

        return builder.build();
    }

    /**
     * Validates this configuration.
     * @return List of validation error messages, empty if valid.
     */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();

        if (id == null || id.isBlank()) {
            errors.add("Server ID/name is required");
        } else if (!id.matches("^[a-zA-Z][a-zA-Z0-9_-]*$")) {
            errors.add("Server ID must start with a letter and contain only letters, numbers, underscores, and hyphens");
        }

        TransportType effectiveTransport = transportType != null ? transportType : TransportType.STDIO;

        if (effectiveTransport == TransportType.STDIO) {
            // Validate STDIO configuration
            if (command == null || command.isBlank()) {
                errors.add("Command is required for STDIO transport");
            }
        } else {
            // Validate REST/SSE configuration
            if (url == null || url.isBlank()) {
                errors.add("URL is required for REST/SSE transport");
            } else {
                // Basic URL validation
                try {
                    java.net.URL parsedUrl = new java.net.URL(url);
                    String protocol = parsedUrl.getProtocol();
                    if (!protocol.equals("http") && !protocol.equals("https")) {
                        errors.add("URL must use http or https protocol");
                    }
                } catch (java.net.MalformedURLException e) {
                    errors.add("Invalid URL format: " + e.getMessage());
                }
            }

            if (connectionTimeout != null && connectionTimeout < 0) {
                errors.add("Connection timeout must be non-negative");
            }

            if (requestTimeout != null && requestTimeout < 0) {
                errors.add("Request timeout must be non-negative");
            }
        }

        return errors;
    }

    /**
     * Checks if this is a STDIO transport configuration.
     */
    public boolean isStdio() {
        return transportType == null || transportType == TransportType.STDIO;
    }

    /**
     * Checks if this is a REST transport configuration.
     */
    public boolean isRest() {
        return transportType == TransportType.REST;
    }

    /**
     * Checks if this is an SSE transport configuration.
     */
    public boolean isSse() {
        return transportType == TransportType.SSE;
    }

    /**
     * Gets the effective SSE endpoint, defaulting to "/sse" if not specified.
     */
    public String getEffectiveSseEndpoint() {
        return sseEndpoint != null ? sseEndpoint : "/sse";
    }

    /**
     * Gets the effective messages endpoint, defaulting to "/message" if not specified.
     */
    public String getEffectiveMessagesEndpoint() {
        return messagesEndpoint != null ? messagesEndpoint : "/message";
    }
}
