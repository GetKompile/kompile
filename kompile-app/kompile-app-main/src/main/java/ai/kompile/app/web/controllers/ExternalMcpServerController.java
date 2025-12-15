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

import ai.kompile.app.services.mcp.ExternalMcpServerManager;
import ai.kompile.core.mcp.server.ExternalMcpServerConfig;
import ai.kompile.core.mcp.server.ExternalMcpServerConfig.ServerStatus;
import ai.kompile.core.mcp.server.UnifiedMcpConfig;
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
 * REST controller for managing external MCP servers in Claude Desktop format.
 * Provides endpoints for CRUD operations, lifecycle management, and configuration import/export.
 */
@RestController
@RequestMapping("/api/mcp")
public class ExternalMcpServerController {

    private static final Logger logger = LoggerFactory.getLogger(ExternalMcpServerController.class);

    private final ExternalMcpServerManager serverManager;

    @Autowired
    public ExternalMcpServerController(ExternalMcpServerManager serverManager) {
        this.serverManager = serverManager;
    }

    // ==================== Server CRUD Operations ====================

    /**
     * List all external MCP server configurations.
     */
    @GetMapping(value = "/external-servers", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<ExternalMcpServerConfig>> listServers() {
        logger.debug("Listing all external MCP servers");
        return ResponseEntity.ok(serverManager.listServers());
    }

    /**
     * Get a specific external MCP server configuration.
     */
    @GetMapping(value = "/external-servers/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getServer(@PathVariable String id) {
        logger.debug("Getting external MCP server: {}", id);
        return serverManager.getServer(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Add a new external MCP server configuration.
     */
    @PostMapping(value = "/external-servers", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> addServer(@RequestBody ExternalMcpServerConfig config) {
        logger.info("Adding new external MCP server: {}", config.getId());
        try {
            ExternalMcpServerConfig created = serverManager.addServer(config.getId(), config);
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (IllegalArgumentException e) {
            logger.warn("Failed to add server: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Update an existing external MCP server configuration.
     */
    @PutMapping(value = "/external-servers/{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> updateServer(@PathVariable String id, @RequestBody ExternalMcpServerConfig config) {
        logger.info("Updating external MCP server: {}", id);
        try {
            ExternalMcpServerConfig updated = serverManager.updateServer(id, config);
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
     * Delete an external MCP server configuration.
     */
    @DeleteMapping(value = "/external-servers/{id}")
    public ResponseEntity<?> deleteServer(@PathVariable String id) {
        logger.info("Deleting external MCP server: {}", id);
        try {
            serverManager.deleteServer(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== Server Lifecycle Operations ====================

    /**
     * Start an external MCP server.
     */
    @PostMapping(value = "/external-servers/{id}/start", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> startServer(@PathVariable String id) {
        logger.info("Starting external MCP server: {}", id);
        try {
            ExternalMcpServerConfig config = serverManager.startServer(id);
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
     * Stop an external MCP server.
     */
    @PostMapping(value = "/external-servers/{id}/stop", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> stopServer(@PathVariable String id) {
        logger.info("Stopping external MCP server: {}", id);
        try {
            ExternalMcpServerConfig config = serverManager.stopServer(id);
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
     * Restart an external MCP server.
     */
    @PostMapping(value = "/external-servers/{id}/restart", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> restartServer(@PathVariable String id) {
        logger.info("Restarting external MCP server: {}", id);
        try {
            ExternalMcpServerConfig config = serverManager.restartServer(id);
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
     * Get the status of an external MCP server.
     */
    @GetMapping(value = "/external-servers/{id}/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getServerStatus(@PathVariable String id) {
        try {
            ServerStatus status = serverManager.getServerStatus(id);
            ExternalMcpServerConfig config = serverManager.getServer(id).orElse(null);
            return ResponseEntity.ok(Map.of(
                    "id", id,
                    "status", status,
                    "pid", config != null && config.getPid() != null ? config.getPid() : "",
                    "errorMessage", config != null && config.getErrorMessage() != null ? config.getErrorMessage() : ""
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ==================== Configuration Operations ====================

    /**
     * Get the full unified configuration JSON.
     */
    @GetMapping(value = "/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getConfig() {
        logger.debug("Getting full MCP configuration");
        return ResponseEntity.ok(serverManager.exportConfig());
    }

    /**
     * Replace the entire configuration with new JSON.
     */
    @PutMapping(value = "/config", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> replaceConfig(@RequestBody String json) {
        logger.info("Replacing MCP configuration");
        try {
            UnifiedMcpConfig config = serverManager.replaceConfig(json);
            return ResponseEntity.ok(Map.of(
                    "message", "Configuration replaced successfully",
                    "serverCount", config.getServerCount()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Import servers from Claude Desktop format JSON (merges with existing).
     */
    @PostMapping(value = "/config/import", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> importConfig(@RequestBody String json) {
        logger.info("Importing MCP configuration");
        try {
            UnifiedMcpConfig config = serverManager.importConfig(json);
            return ResponseEntity.ok(Map.of(
                    "message", "Configuration imported successfully",
                    "serverCount", config.getServerCount()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Export the current configuration as Claude Desktop format JSON.
     */
    @GetMapping(value = "/config/export", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> exportConfig() {
        logger.debug("Exporting MCP configuration");
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"mcp-config.json\"")
                .body(serverManager.exportConfig());
    }

    /**
     * Validate configuration JSON without saving.
     */
    @PostMapping(value = "/config/validate", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> validateConfig(@RequestBody String json) {
        logger.debug("Validating MCP configuration");
        List<String> errors = serverManager.validateConfig(json);
        if (errors.isEmpty()) {
            return ResponseEntity.ok(Map.of("valid", true));
        } else {
            return ResponseEntity.ok(Map.of("valid", false, "errors", errors));
        }
    }

    /**
     * Reload configuration from disk.
     */
    @PostMapping(value = "/config/reload", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> reloadConfig() {
        logger.info("Reloading MCP configuration from disk");
        serverManager.loadConfig();
        return ResponseEntity.ok(Map.of(
                "message", "Configuration reloaded successfully",
                "serverCount", serverManager.getConfig().getServerCount()
        ));
    }
}
