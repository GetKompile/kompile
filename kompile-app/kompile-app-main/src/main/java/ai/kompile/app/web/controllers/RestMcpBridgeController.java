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

import ai.kompile.app.services.mcp.BuiltInToolDiscoveryService;
import ai.kompile.app.services.mcp.BuiltInToolDiscoveryService.DiscoveredTool;
import ai.kompile.core.mcp.bridge.RestMcpBridgeConfig;
import ai.kompile.core.mcp.bridge.RestMcpBridgeManager;
import ai.kompile.core.mcp.bridge.RestMcpBridgeManager.EndpointTestResult;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * REST controller for managing REST-MCP bridges.
 * Provides endpoints for creating, configuring, and managing bridges that connect
 * REST APIs to MCP servers and vice versa.
 */
@RestController
@RequestMapping("/api/mcp/bridges")
public class RestMcpBridgeController {

    private static final Logger logger = LoggerFactory.getLogger(RestMcpBridgeController.class);

    private final RestMcpBridgeManager bridgeManager;
    private final BuiltInToolDiscoveryService toolDiscoveryService;

    @Autowired
    public RestMcpBridgeController(RestMcpBridgeManager bridgeManager,
                                   BuiltInToolDiscoveryService toolDiscoveryService) {
        this.bridgeManager = bridgeManager;
        this.toolDiscoveryService = toolDiscoveryService;
    }

    /**
     * List all bridge configurations.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<RestMcpBridgeConfig>> listBridges() {
        logger.debug("Listing all REST-MCP bridges");
        return ResponseEntity.ok(bridgeManager.listBridges());
    }

    /**
     * Get a specific bridge configuration.
     */
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getBridge(@PathVariable String id) {
        logger.debug("Getting REST-MCP bridge: {}", id);
        return bridgeManager.getBridge(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Create a new bridge configuration.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createBridge(@RequestBody RestMcpBridgeConfig config) {
        logger.info("Creating new REST-MCP bridge: {}", config.getName());
        try {
            RestMcpBridgeConfig created = bridgeManager.createBridge(config);
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (IllegalArgumentException e) {
            logger.warn("Failed to create bridge: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Update an existing bridge configuration.
     */
    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> updateBridge(@PathVariable String id, @RequestBody RestMcpBridgeConfig config) {
        logger.info("Updating REST-MCP bridge: {}", id);
        try {
            RestMcpBridgeConfig updated = bridgeManager.updateBridge(id, config);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            logger.warn("Bridge not found: {}", id);
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            logger.warn("Cannot update bridge: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Delete a bridge configuration.
     */
    @DeleteMapping(value = "/{id}")
    public ResponseEntity<?> deleteBridge(@PathVariable String id) {
        logger.info("Deleting REST-MCP bridge: {}", id);
        try {
            bridgeManager.deleteBridge(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Start a bridge.
     */
    @PostMapping(value = "/{id}/start", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> startBridge(@PathVariable String id) {
        logger.info("Starting REST-MCP bridge: {}", id);
        try {
            RestMcpBridgeConfig config = bridgeManager.startBridge(id);
            return ResponseEntity.ok(config);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            logger.error("Failed to start bridge: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to start bridge: " + e.getMessage()));
        }
    }

    /**
     * Stop a bridge.
     */
    @PostMapping(value = "/{id}/stop", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> stopBridge(@PathVariable String id) {
        logger.info("Stopping REST-MCP bridge: {}", id);
        try {
            RestMcpBridgeConfig config = bridgeManager.stopBridge(id);
            return ResponseEntity.ok(config);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            logger.error("Failed to stop bridge: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to stop bridge: " + e.getMessage()));
        }
    }

    /**
     * Get the status of a bridge.
     */
    @GetMapping(value = "/{id}/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getBridgeStatus(@PathVariable String id) {
        try {
            RestMcpBridgeConfig.BridgeStatus status = bridgeManager.getBridgeStatus(id);
            return ResponseEntity.ok(Map.of("id", id, "status", status));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Discover endpoints from an OpenAPI specification.
     */
    @PostMapping(value = "/discover/openapi", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> discoverFromOpenApi(@RequestBody Map<String, String> request) {
        String openApiUrl = request.get("openApiUrl");
        if (openApiUrl == null || openApiUrl.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "openApiUrl is required"));
        }

        logger.info("Discovering endpoints from OpenAPI: {}", openApiUrl);
        try {
            List<RestMcpBridgeConfig.EndpointMapping> mappings = bridgeManager.discoverEndpoints(openApiUrl);
            return ResponseEntity.ok(Map.of(
                    "openApiUrl", openApiUrl,
                    "mappings", mappings,
                    "count", mappings.size()
            ));
        } catch (Exception e) {
            logger.error("Failed to discover endpoints from OpenAPI: {}", openApiUrl, e);
            return ResponseEntity.badRequest().body(Map.of("error", "Failed to parse OpenAPI: " + e.getMessage()));
        }
    }

    /**
     * Discover endpoints by probing a base URL.
     */
    @PostMapping(value = "/discover/probe", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> probeEndpoints(@RequestBody Map<String, String> request) {
        String baseUrl = request.get("baseUrl");
        if (baseUrl == null || baseUrl.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "baseUrl is required"));
        }

        logger.info("Probing endpoints at: {}", baseUrl);
        try {
            List<RestMcpBridgeConfig.EndpointMapping> mappings = bridgeManager.probeEndpoints(baseUrl);
            return ResponseEntity.ok(Map.of(
                    "baseUrl", baseUrl,
                    "mappings", mappings,
                    "count", mappings.size()
            ));
        } catch (Exception e) {
            logger.error("Failed to probe endpoints at: {}", baseUrl, e);
            return ResponseEntity.badRequest().body(Map.of("error", "Failed to probe endpoints: " + e.getMessage()));
        }
    }

    /**
     * Test a specific endpoint mapping.
     */
    @PostMapping(value = "/{bridgeId}/mappings/{mappingId}/test", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> testMapping(
            @PathVariable String bridgeId,
            @PathVariable String mappingId,
            @RequestBody(required = false) Object testInput) {
        logger.info("Testing mapping {} in bridge {}", mappingId, bridgeId);
        try {
            EndpointTestResult result = bridgeManager.testMapping(bridgeId, mappingId, testInput);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            logger.error("Failed to test mapping: {}", mappingId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Test failed: " + e.getMessage()));
        }
    }

    /**
     * Sync bridge mappings with the target (re-discover and update).
     */
    @PostMapping(value = "/{id}/sync", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> syncBridge(@PathVariable String id) {
        logger.info("Syncing REST-MCP bridge: {}", id);
        try {
            RestMcpBridgeConfig config = bridgeManager.syncBridge(id);
            return ResponseEntity.ok(config);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            logger.error("Failed to sync bridge: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Sync failed: " + e.getMessage()));
        }
    }

    /**
     * Validate a bridge configuration without saving it.
     */
    @PostMapping(value = "/validate", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> validateConfig(@RequestBody RestMcpBridgeConfig config) {
        List<String> errors = bridgeManager.validateConfig(config);
        if (errors.isEmpty()) {
            return ResponseEntity.ok(Map.of("valid", true));
        } else {
            return ResponseEntity.ok(Map.of("valid", false, "errors", errors));
        }
    }

    /**
     * Export a bridge configuration as JSON.
     */
    @GetMapping(value = "/{id}/export", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> exportConfig(@PathVariable String id) {
        try {
            String json = bridgeManager.exportConfig(id);
            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"mcp-bridge-" + id + ".json\"")
                    .body(json);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Import a bridge configuration from JSON.
     */
    @PostMapping(value = "/import", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> importConfig(@RequestBody String json) {
        try {
            RestMcpBridgeConfig config = bridgeManager.importConfig(json);
            return ResponseEntity.status(HttpStatus.CREATED).body(config);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Failed to import: " + e.getMessage()));
        }
    }

    /**
     * Get available bridge directions.
     */
    @GetMapping(value = "/directions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RestMcpBridgeConfig.BridgeDirection[]> getBridgeDirections() {
        return ResponseEntity.ok(RestMcpBridgeConfig.BridgeDirection.values());
    }

    /**
     * Get available authentication types.
     */
    @GetMapping(value = "/auth-types", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RestMcpBridgeConfig.AuthType[]> getAuthTypes() {
        return ResponseEntity.ok(RestMcpBridgeConfig.AuthType.values());
    }

    /**
     * Get available transform types.
     */
    @GetMapping(value = "/transform-types", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RestMcpBridgeConfig.TransformType[]> getTransformTypes() {
        return ResponseEntity.ok(RestMcpBridgeConfig.TransformType.values());
    }

    /**
     * Add a mapping to an existing bridge configuration.
     */
    @PostMapping(value = "/{id}/mappings", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> addMapping(@PathVariable String id, @RequestBody RestMcpBridgeConfig.EndpointMapping mapping) {
        return bridgeManager.getBridge(id)
                .map(config -> {
                    config.getMappings().add(mapping);
                    try {
                        RestMcpBridgeConfig updated = bridgeManager.updateBridge(id, config);
                        return ResponseEntity.ok(updated);
                    } catch (Exception e) {
                        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Update a specific mapping in a bridge configuration.
     */
    @PutMapping(value = "/{bridgeId}/mappings/{mappingId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> updateMapping(
            @PathVariable String bridgeId,
            @PathVariable String mappingId,
            @RequestBody RestMcpBridgeConfig.EndpointMapping mapping) {
        return bridgeManager.getBridge(bridgeId)
                .map(config -> {
                    config.getMappings().removeIf(m -> m.getId().equals(mappingId));
                    mapping.setId(mappingId);
                    config.getMappings().add(mapping);
                    try {
                        RestMcpBridgeConfig updated = bridgeManager.updateBridge(bridgeId, config);
                        return ResponseEntity.ok(updated);
                    } catch (Exception e) {
                        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Remove a mapping from an existing bridge configuration.
     */
    @DeleteMapping(value = "/{bridgeId}/mappings/{mappingId}")
    public ResponseEntity<?> removeMapping(@PathVariable String bridgeId, @PathVariable String mappingId) {
        return bridgeManager.getBridge(bridgeId)
                .map(config -> {
                    config.getMappings().removeIf(m -> m.getId().equals(mappingId));
                    try {
                        RestMcpBridgeConfig updated = bridgeManager.updateBridge(bridgeId, config);
                        return ResponseEntity.ok(updated);
                    } catch (Exception e) {
                        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Toggle a mapping's enabled status.
     */
    @PostMapping(value = "/{bridgeId}/mappings/{mappingId}/toggle", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> toggleMapping(@PathVariable String bridgeId, @PathVariable String mappingId) {
        return bridgeManager.getBridge(bridgeId)
                .map(config -> {
                    config.getMappings().stream()
                            .filter(m -> m.getId().equals(mappingId))
                            .findFirst()
                            .ifPresent(m -> m.setEnabled(!m.isEnabled()));
                    try {
                        RestMcpBridgeConfig updated = bridgeManager.updateBridge(bridgeId, config);
                        return ResponseEntity.ok(updated);
                    } catch (Exception e) {
                        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ==================== Built-in Tool Integration Endpoints ====================

    /**
     * Discover built-in MCP tools from the application.
     * Returns all @Tool annotated methods from registered beans.
     */
    @GetMapping(value = "/discover/builtin", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> discoverBuiltInTools() {
        logger.info("Discovering built-in MCP tools");
        try {
            List<DiscoveredTool> tools = toolDiscoveryService.getDiscoveredTools();
            List<RestMcpBridgeConfig.EndpointMapping> mappings = toolDiscoveryService.createMappingsForBuiltInTools();

            return ResponseEntity.ok(Map.of(
                    "tools", tools,
                    "mappings", mappings,
                    "count", tools.size()
            ));
        } catch (Exception e) {
            logger.error("Failed to discover built-in tools", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to discover built-in tools: " + e.getMessage()));
        }
    }

    /**
     * Get an OpenAPI specification for the application's MCP tools.
     * This can be used to import the application's tools into external systems.
     */
    @GetMapping(value = "/discover/builtin/openapi", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getBuiltInToolsOpenApiSpec() {
        logger.info("Generating OpenAPI spec for built-in MCP tools");
        try {
            ObjectNode spec = toolDiscoveryService.generateOpenApiSpec();
            return ResponseEntity.ok(spec);
        } catch (Exception e) {
            logger.error("Failed to generate OpenAPI spec", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to generate OpenAPI spec: " + e.getMessage()));
        }
    }

    /**
     * Create a pre-configured bridge for the application's built-in MCP tools.
     * This creates a bridge that exposes all @Tool annotated methods via MCP.
     */
    @PostMapping(value = "/create-builtin-bridge", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createBuiltInToolsBridge() {
        logger.info("Creating bridge for built-in MCP tools");
        try {
            // Check if a built-in bridge already exists
            for (RestMcpBridgeConfig existing : bridgeManager.listBridges()) {
                if ("Kompile Built-in Tools Bridge".equals(existing.getName())) {
                    return ResponseEntity.ok(Map.of(
                            "message", "Built-in tools bridge already exists",
                            "bridge", existing
                    ));
                }
            }

            RestMcpBridgeConfig config = toolDiscoveryService.createSelfBridgeConfig();
            RestMcpBridgeConfig created = bridgeManager.createBridge(config);

            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "message", "Built-in tools bridge created successfully",
                    "bridge", created
            ));
        } catch (Exception e) {
            logger.error("Failed to create built-in tools bridge", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to create bridge: " + e.getMessage()));
        }
    }

    /**
     * Add built-in tool mappings to an existing bridge.
     */
    @PostMapping(value = "/{id}/add-builtin-tools", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> addBuiltInToolsToBridge(@PathVariable String id) {
        logger.info("Adding built-in tools to bridge: {}", id);
        return bridgeManager.getBridge(id)
                .map(config -> {
                    try {
                        List<RestMcpBridgeConfig.EndpointMapping> builtInMappings =
                                toolDiscoveryService.createMappingsForBuiltInTools();

                        // Add mappings that don't already exist
                        for (RestMcpBridgeConfig.EndpointMapping mapping : builtInMappings) {
                            boolean exists = config.getMappings().stream()
                                    .anyMatch(m -> m.getMcpTool().getName().equals(mapping.getMcpTool().getName()));
                            if (!exists) {
                                config.getMappings().add(mapping);
                            }
                        }

                        RestMcpBridgeConfig updated = bridgeManager.updateBridge(id, config);
                        return ResponseEntity.ok(Map.of(
                                "message", "Built-in tools added to bridge",
                                "addedCount", builtInMappings.size(),
                                "bridge", updated
                        ));
                    } catch (Exception e) {
                        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Refresh the built-in tool discovery cache.
     * Call this if new @Tool annotated beans have been registered.
     */
    @PostMapping(value = "/discover/builtin/refresh", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> refreshBuiltInToolDiscovery() {
        logger.info("Refreshing built-in tool discovery");
        try {
            toolDiscoveryService.discoverBuiltInTools();
            List<DiscoveredTool> tools = toolDiscoveryService.getDiscoveredTools();

            return ResponseEntity.ok(Map.of(
                    "message", "Built-in tool discovery refreshed",
                    "tools", tools,
                    "count", tools.size()
            ));
        } catch (Exception e) {
            logger.error("Failed to refresh built-in tool discovery", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to refresh: " + e.getMessage()));
        }
    }
}
