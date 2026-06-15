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

import ai.kompile.app.services.mcp.optimization.McpOptimizationConfigService;
import ai.kompile.core.mcp.optimization.McpOptimizationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * REST controller for managing MCP token-saving optimization configuration.
 * Uses JSON-based persistence so settings survive restarts, and is the only
 * control surface — there are no Spring properties for these knobs.
 */
@RestController
@RequestMapping("/api/config/mcp-optimization")
public class McpOptimizationController {

    private static final Logger logger = LoggerFactory.getLogger(McpOptimizationController.class);

    private final McpOptimizationConfigService configService;

    @Autowired
    public McpOptimizationController(McpOptimizationConfigService configService) {
        this.configService = configService;
    }

    /**
     * Get current MCP optimization configuration.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getConfig() {
        return ResponseEntity.ok(toResponse(configService.getConfiguration(),
                "Current MCP optimization configuration"));
    }

    /**
     * Update MCP optimization configuration. Only non-null fields in the body
     * are applied — all other values are preserved.
     */
    @PutMapping
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody McpOptimizationConfig update) {
        try {
            McpOptimizationConfig updated = configService.updateConfiguration(update);
            return ResponseEntity.ok(toResponse(updated, "MCP optimization configuration updated"));
        } catch (IllegalArgumentException e) {
            logger.warn("Rejected invalid MCP optimization config update: {}", e.getMessage());
            Map<String, Object> body = new HashMap<>();
            body.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(body);
        } catch (Exception e) {
            logger.error("Error updating MCP optimization configuration: {}", e.getMessage(), e);
            Map<String, Object> body = new HashMap<>();
            body.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(body);
        }
    }

    /**
     * Reset MCP optimization configuration to defaults.
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> resetConfig() {
        McpOptimizationConfig reset = configService.resetConfiguration();
        return ResponseEntity.ok(toResponse(reset, "MCP optimization configuration reset to defaults"));
    }

    private Map<String, Object> toResponse(McpOptimizationConfig cfg, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("enabled", cfg.getEnabled());
        response.put("ragMaxContentChars", cfg.getRagMaxContentChars());
        response.put("ragMaxDocs", cfg.getRagMaxDocs());
        response.put("filesystemStorePreviousContentInCache", cfg.getFilesystemStorePreviousContentInCache());
        response.put("filesystemUndoTtlSeconds", cfg.getFilesystemUndoTtlSeconds());
        response.put("knowledgeGraphTruncateChars", cfg.getKnowledgeGraphTruncateChars());
        response.put("compressionThresholdChars", cfg.getCompressionThresholdChars());
        response.put("resultCacheMaxEntries", cfg.getResultCacheMaxEntries());
        response.put("resultCacheTtlSeconds", cfg.getResultCacheTtlSeconds());
        response.put("metaToolMode", cfg.getMetaToolMode() != null ? cfg.getMetaToolMode().name() : null);
        response.put("alwaysExposedTools", cfg.getAlwaysExposedTools());
        response.put("toolOverrides", cfg.getToolOverrides());
        response.put("configFilePath", configService.getConfigFilePath());
        response.put("message", message);
        return response;
    }
}
