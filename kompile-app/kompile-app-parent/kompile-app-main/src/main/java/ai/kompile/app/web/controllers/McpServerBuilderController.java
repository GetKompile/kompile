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

package ai.kompile.app.web.controllers;

import ai.kompile.core.mcp.server.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for managing MCP servers.
 * Provides endpoints for creating, configuring, and managing custom MCP servers.
 */
@RestController
@RequestMapping("/api/mcp/servers")
public class McpServerBuilderController {

    private static final Logger logger = LoggerFactory.getLogger(McpServerBuilderController.class);

    private final McpServerManager serverManager;

    @Autowired
    public McpServerBuilderController(McpServerManager serverManager) {
        this.serverManager = serverManager;
    }

    /**
     * List all MCP server configurations.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<McpServerConfig>> listServers() {
        logger.debug("Listing all MCP servers");
        return ResponseEntity.ok(serverManager.listServers());
    }

    /**
     * Get a specific MCP server configuration.
     */
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getServer(@PathVariable String id) {
        logger.debug("Getting MCP server: {}", id);
        return serverManager.getServer(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Create a new MCP server configuration.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createServer(@RequestBody McpServerConfig config) {
        logger.info("Creating new MCP server: {}", config.getName());
        try {
            McpServerConfig created = serverManager.createServer(config);
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (IllegalArgumentException e) {
            logger.warn("Failed to create server: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Update an existing MCP server configuration.
     */
    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> updateServer(@PathVariable String id, @RequestBody McpServerConfig config) {
        logger.info("Updating MCP server: {}", id);
        try {
            McpServerConfig updated = serverManager.updateServer(id, config);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            logger.warn("Server not found: {}", id);
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            logger.warn("Cannot update server: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Delete an MCP server configuration.
     */
    @DeleteMapping(value = "/{id}")
    public ResponseEntity<?> deleteServer(@PathVariable String id) {
        logger.info("Deleting MCP server: {}", id);
        try {
            serverManager.deleteServer(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Start an MCP server.
     */
    @PostMapping(value = "/{id}/start", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> startServer(@PathVariable String id) {
        logger.info("Starting MCP server: {}", id);
        try {
            McpServerConfig config = serverManager.startServer(id);
            return ResponseEntity.ok(config);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            logger.error("Failed to start server: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to start server: " + e.getMessage()));
        }
    }

    /**
     * Stop an MCP server.
     */
    @PostMapping(value = "/{id}/stop", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> stopServer(@PathVariable String id) {
        logger.info("Stopping MCP server: {}", id);
        try {
            McpServerConfig config = serverManager.stopServer(id);
            return ResponseEntity.ok(config);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            logger.error("Failed to stop server: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to stop server: " + e.getMessage()));
        }
    }

    /**
     * Restart an MCP server.
     */
    @PostMapping(value = "/{id}/restart", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> restartServer(@PathVariable String id) {
        logger.info("Restarting MCP server: {}", id);
        try {
            McpServerConfig config = serverManager.restartServer(id);
            return ResponseEntity.ok(config);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (RuntimeException e) {
            logger.error("Failed to restart server: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to restart server: " + e.getMessage()));
        }
    }

    /**
     * Get the status of an MCP server.
     */
    @GetMapping(value = "/{id}/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getServerStatus(@PathVariable String id) {
        try {
            McpServerConfig.ServerStatus status = serverManager.getServerStatus(id);
            return ResponseEntity.ok(Map.of("id", id, "status", status));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Validate an MCP server configuration without saving it.
     */
    @PostMapping(value = "/validate", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> validateConfig(@RequestBody McpServerConfig config) {
        List<String> errors = serverManager.validateConfig(config);
        if (errors.isEmpty()) {
            return ResponseEntity.ok(Map.of("valid", true));
        } else {
            return ResponseEntity.ok(Map.of("valid", false, "errors", errors));
        }
    }

    /**
     * Export an MCP server configuration as JSON.
     */
    @GetMapping(value = "/{id}/export", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> exportConfig(@PathVariable String id) {
        try {
            String json = serverManager.exportConfig(id);
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"mcp-server-" + id + ".json\"")
                    .body(json);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Import an MCP server configuration from JSON.
     */
    @PostMapping(value = "/import", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> importConfig(@RequestBody String json) {
        try {
            McpServerConfig config = serverManager.importConfig(json);
            return ResponseEntity.status(HttpStatus.CREATED).body(config);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Failed to import: " + e.getMessage()));
        }
    }

    /**
     * Get available transport types.
     */
    @GetMapping(value = "/transport-types", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<McpServerConfig.TransportType[]> getTransportTypes() {
        return ResponseEntity.ok(McpServerConfig.TransportType.values());
    }

    /**
     * Get available tool implementation types.
     */
    @GetMapping(value = "/tool-types", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<McpToolConfig.ToolImplementationType[]> getToolTypes() {
        return ResponseEntity.ok(McpToolConfig.ToolImplementationType.values());
    }

    /**
     * Get available resource types.
     */
    @GetMapping(value = "/resource-types", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<McpResourceConfig.ResourceType[]> getResourceTypes() {
        return ResponseEntity.ok(McpResourceConfig.ResourceType.values());
    }

    /**
     * Add a tool to an existing server configuration.
     */
    @PostMapping(value = "/{id}/tools", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> addTool(@PathVariable String id, @RequestBody McpToolConfig tool) {
        return serverManager.getServer(id)
                .map(config -> {
                    config.getTools().add(tool);
                    try {
                        McpServerConfig updated = serverManager.updateServer(id, config);
                        return ResponseEntity.ok(updated);
                    } catch (Exception e) {
                        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Remove a tool from an existing server configuration.
     */
    @DeleteMapping(value = "/{id}/tools/{toolName}")
    public ResponseEntity<?> removeTool(@PathVariable String id, @PathVariable String toolName) {
        return serverManager.getServer(id)
                .map(config -> {
                    config.getTools().removeIf(t -> t.getName().equals(toolName));
                    try {
                        McpServerConfig updated = serverManager.updateServer(id, config);
                        return ResponseEntity.ok(updated);
                    } catch (Exception e) {
                        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Add a resource to an existing server configuration.
     */
    @PostMapping(value = "/{id}/resources", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> addResource(@PathVariable String id, @RequestBody McpResourceConfig resource) {
        return serverManager.getServer(id)
                .map(config -> {
                    config.getResources().add(resource);
                    try {
                        McpServerConfig updated = serverManager.updateServer(id, config);
                        return ResponseEntity.ok(updated);
                    } catch (Exception e) {
                        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Remove a resource from an existing server configuration.
     */
    @DeleteMapping(value = "/{id}/resources/{resourceUri}")
    public ResponseEntity<?> removeResource(@PathVariable String id, @PathVariable String resourceUri) {
        return serverManager.getServer(id)
                .map(config -> {
                    config.getResources().removeIf(r -> r.getUri().equals(resourceUri));
                    try {
                        McpServerConfig updated = serverManager.updateServer(id, config);
                        return ResponseEntity.ok(updated);
                    } catch (Exception e) {
                        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Add a prompt to an existing server configuration.
     */
    @PostMapping(value = "/{id}/prompts", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> addPrompt(@PathVariable String id, @RequestBody McpPromptConfig prompt) {
        return serverManager.getServer(id)
                .map(config -> {
                    config.getPrompts().add(prompt);
                    try {
                        McpServerConfig updated = serverManager.updateServer(id, config);
                        return ResponseEntity.ok(updated);
                    } catch (Exception e) {
                        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Remove a prompt from an existing server configuration.
     */
    @DeleteMapping(value = "/{id}/prompts/{promptName}")
    public ResponseEntity<?> removePrompt(@PathVariable String id, @PathVariable String promptName) {
        return serverManager.getServer(id)
                .map(config -> {
                    config.getPrompts().removeIf(p -> p.getName().equals(promptName));
                    try {
                        McpServerConfig updated = serverManager.updateServer(id, config);
                        return ResponseEntity.ok(updated);
                    } catch (Exception e) {
                        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
