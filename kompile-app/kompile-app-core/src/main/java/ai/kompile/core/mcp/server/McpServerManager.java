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

import java.util.List;
import java.util.Optional;

/**
 * Interface for managing MCP server configurations and runtime instances.
 * Provides CRUD operations for server configurations and lifecycle management.
 */
public interface McpServerManager {

    /**
     * Creates a new MCP server configuration.
     *
     * @param config the server configuration to create
     * @return the created configuration with generated ID
     */
    McpServerConfig createServer(McpServerConfig config);

    /**
     * Updates an existing MCP server configuration.
     *
     * @param id the server ID
     * @param config the updated configuration
     * @return the updated configuration
     * @throws IllegalArgumentException if server not found
     */
    McpServerConfig updateServer(String id, McpServerConfig config);

    /**
     * Deletes an MCP server configuration.
     *
     * @param id the server ID to delete
     * @throws IllegalArgumentException if server not found
     * @throws IllegalStateException if server is currently running
     */
    void deleteServer(String id);

    /**
     * Retrieves an MCP server configuration by ID.
     *
     * @param id the server ID
     * @return optional containing the configuration if found
     */
    Optional<McpServerConfig> getServer(String id);

    /**
     * Lists all MCP server configurations.
     *
     * @return list of all server configurations
     */
    List<McpServerConfig> listServers();

    /**
     * Starts an MCP server instance.
     *
     * @param id the server ID to start
     * @return the updated server configuration with RUNNING status
     * @throws IllegalArgumentException if server not found
     * @throws IllegalStateException if server is already running
     */
    McpServerConfig startServer(String id);

    /**
     * Stops a running MCP server instance.
     *
     * @param id the server ID to stop
     * @return the updated server configuration with STOPPED status
     * @throws IllegalArgumentException if server not found
     * @throws IllegalStateException if server is not running
     */
    McpServerConfig stopServer(String id);

    /**
     * Restarts an MCP server instance.
     *
     * @param id the server ID to restart
     * @return the updated server configuration
     */
    McpServerConfig restartServer(String id);

    /**
     * Gets the current status of an MCP server.
     *
     * @param id the server ID
     * @return the server status
     * @throws IllegalArgumentException if server not found
     */
    McpServerConfig.ServerStatus getServerStatus(String id);

    /**
     * Validates a server configuration.
     *
     * @param config the configuration to validate
     * @return list of validation errors (empty if valid)
     */
    List<String> validateConfig(McpServerConfig config);

    /**
     * Exports a server configuration to JSON.
     *
     * @param id the server ID to export
     * @return JSON string representation of the configuration
     */
    String exportConfig(String id);

    /**
     * Imports a server configuration from JSON.
     *
     * @param json the JSON configuration string
     * @return the imported server configuration
     */
    McpServerConfig importConfig(String json);
}
