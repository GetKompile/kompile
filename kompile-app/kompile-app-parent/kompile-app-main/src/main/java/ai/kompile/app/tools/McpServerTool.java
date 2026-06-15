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

package ai.kompile.app.tools;

import ai.kompile.app.web.controllers.ExternalMcpServerController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * MCP Tool for managing external MCP servers.
 * Exposes functionality to list, add, start, stop, and configure external MCP servers.
 */
@Component
public class McpServerTool {

    private static final Logger logger = LoggerFactory.getLogger(McpServerTool.class);

    private final ExternalMcpServerController mcpServerController;

    @Autowired
    public McpServerTool(@Autowired(required = false) ExternalMcpServerController mcpServerController) {
        this.mcpServerController = mcpServerController;
    }

    public record ListMcpServersInput() {}
    public record GetMcpServerInput(String id) {}
    public record StartMcpServerInput(String id) {}
    public record StopMcpServerInput(String id) {}
    public record RestartMcpServerInput(String id) {}
    public record GetMcpServerStatusInput(String id) {}
    public record DeleteMcpServerInput(String id) {}
    public record GetMcpConfigInput() {}
    public record ExportMcpConfigInput() {}
    public record ReloadMcpConfigInput() {}

    @Tool(name = "list_external_mcp_servers",
            description = "Lists all configured external MCP servers with their connection status.")
    public Map<String, Object> listServers(ListMcpServersInput input) {
        try {
            if (mcpServerController == null) return Map.of("status", "error", "error", "MCP server management not available");
            ResponseEntity<?> response = mcpServerController.listServers();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error listing MCP servers: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_external_mcp_server",
            description = "Gets detailed configuration for a specific external MCP server by ID.")
    public Map<String, Object> getServer(GetMcpServerInput input) {
        try {
            if (mcpServerController == null) return Map.of("status", "error", "error", "MCP server management not available");
            if (input.id() == null) return Map.of("status", "error", "error", "Server ID is required");
            ResponseEntity<?> response = mcpServerController.getServer(input.id());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting MCP server: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "start_external_mcp_server",
            description = "Starts an external MCP server by its ID.")
    public Map<String, Object> startServer(StartMcpServerInput input) {
        try {
            if (mcpServerController == null) return Map.of("status", "error", "error", "MCP server management not available");
            if (input.id() == null) return Map.of("status", "error", "error", "Server ID is required");
            ResponseEntity<?> response = mcpServerController.startServer(input.id());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error starting MCP server: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "stop_external_mcp_server",
            description = "Stops a running external MCP server by its ID.")
    public Map<String, Object> stopServer(StopMcpServerInput input) {
        try {
            if (mcpServerController == null) return Map.of("status", "error", "error", "MCP server management not available");
            if (input.id() == null) return Map.of("status", "error", "error", "Server ID is required");
            ResponseEntity<?> response = mcpServerController.stopServer(input.id());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error stopping MCP server: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "restart_external_mcp_server",
            description = "Restarts an external MCP server by its ID.")
    public Map<String, Object> restartServer(RestartMcpServerInput input) {
        try {
            if (mcpServerController == null) return Map.of("status", "error", "error", "MCP server management not available");
            if (input.id() == null) return Map.of("status", "error", "error", "Server ID is required");
            ResponseEntity<?> response = mcpServerController.restartServer(input.id());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error restarting MCP server: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_external_mcp_server_status",
            description = "Gets the status of an external MCP server including PID and connection state.")
    public Map<String, Object> getServerStatus(GetMcpServerStatusInput input) {
        try {
            if (mcpServerController == null) return Map.of("status", "error", "error", "MCP server management not available");
            if (input.id() == null) return Map.of("status", "error", "error", "Server ID is required");
            ResponseEntity<?> response = mcpServerController.getServerStatus(input.id());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting MCP server status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "delete_external_mcp_server",
            description = "Deletes an external MCP server configuration by its ID.")
    public Map<String, Object> deleteServer(DeleteMcpServerInput input) {
        try {
            if (mcpServerController == null) return Map.of("status", "error", "error", "MCP server management not available");
            if (input.id() == null) return Map.of("status", "error", "error", "Server ID is required");
            ResponseEntity<?> response = mcpServerController.deleteServer(input.id());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error deleting MCP server: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_mcp_config",
            description = "Gets the full unified MCP configuration JSON.")
    public Map<String, Object> getConfig(GetMcpConfigInput input) {
        try {
            if (mcpServerController == null) return Map.of("status", "error", "error", "MCP server management not available");
            ResponseEntity<?> response = mcpServerController.getConfig();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting MCP config: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "export_mcp_config",
            description = "Exports MCP configuration in Claude Desktop format.")
    public Map<String, Object> exportConfig(ExportMcpConfigInput input) {
        try {
            if (mcpServerController == null) return Map.of("status", "error", "error", "MCP server management not available");
            ResponseEntity<?> response = mcpServerController.exportConfig();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error exporting MCP config: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "reload_mcp_config",
            description = "Reloads MCP configuration from disk.")
    public Map<String, Object> reloadConfig(ReloadMcpConfigInput input) {
        try {
            if (mcpServerController == null) return Map.of("status", "error", "error", "MCP server management not available");
            ResponseEntity<?> response = mcpServerController.reloadConfig();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error reloading MCP config: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }
}
