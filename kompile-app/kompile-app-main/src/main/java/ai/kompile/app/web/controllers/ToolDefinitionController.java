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

import ai.kompile.app.services.mcp.ToolDefinitionService;
import ai.kompile.core.mcp.EnhancedToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for managing tool definitions.
 * Provides CRUD operations for tools and agent-friendly tool discovery endpoints.
 */
@RestController
@RequestMapping("/api/tools")
public class ToolDefinitionController {

    private static final Logger logger = LoggerFactory.getLogger(ToolDefinitionController.class);

    private final ToolDefinitionService toolDefinitionService;

    @Autowired
    public ToolDefinitionController(ToolDefinitionService toolDefinitionService) {
        this.toolDefinitionService = toolDefinitionService;
    }

    /**
     * Lists all available tools.
     * GET /api/tools
     */
    @GetMapping
    public ResponseEntity<List<EnhancedToolDefinition>> getAllTools(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String query,
            @RequestParam(required = false, defaultValue = "false") boolean enabledOnly) {

        List<EnhancedToolDefinition> tools;

        if (query != null && !query.isEmpty()) {
            tools = toolDefinitionService.searchTools(query);
        } else if (category != null && !category.isEmpty()) {
            tools = toolDefinitionService.getToolsByCategory(category);
        } else if (enabledOnly) {
            tools = toolDefinitionService.getEnabledTools();
        } else {
            tools = toolDefinitionService.getAllTools();
        }

        return ResponseEntity.ok(tools);
    }

    /**
     * Gets a specific tool by name.
     * GET /api/tools/{name}
     */
    @GetMapping("/{name}")
    public ResponseEntity<EnhancedToolDefinition> getToolByName(@PathVariable String name) {
        return toolDefinitionService.getToolByName(name)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Gets the agent-friendly prompt for a specific tool.
     * GET /api/tools/{name}/prompt
     */
    @GetMapping("/{name}/prompt")
    public ResponseEntity<String> getToolPrompt(@PathVariable String name) {
        return toolDefinitionService.getToolByName(name)
                .map(tool -> ResponseEntity.ok(tool.getAgentPrompt()))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Creates a new custom tool definition.
     * POST /api/tools
     */
    @PostMapping
    public ResponseEntity<?> createTool(@RequestBody EnhancedToolDefinition definition) {
        try {
            EnhancedToolDefinition created = toolDefinitionService.createCustomTool(definition);
            logger.info("Created custom tool: {}", created.getName());
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Failed to create tool: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to create tool: " + e.getMessage()));
        }
    }

    /**
     * Updates an existing tool definition.
     * PUT /api/tools/{name}
     */
    @PutMapping("/{name}")
    public ResponseEntity<?> updateTool(@PathVariable String name,
                                        @RequestBody EnhancedToolDefinition updates) {
        try {
            EnhancedToolDefinition updated = toolDefinitionService.updateToolDefinition(name, updates);
            logger.info("Updated tool: {}", name);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Failed to update tool {}: {}", name, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to update tool: " + e.getMessage()));
        }
    }

    /**
     * Deletes a custom tool definition.
     * DELETE /api/tools/{name}
     */
    @DeleteMapping("/{name}")
    public ResponseEntity<?> deleteTool(@PathVariable String name) {
        try {
            boolean deleted = toolDefinitionService.deleteToolDefinition(name);
            if (deleted) {
                logger.info("Deleted tool: {}", name);
                return ResponseEntity.noContent().build();
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Failed to delete tool {}: {}", name, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to delete tool: " + e.getMessage()));
        }
    }

    /**
     * Enables or disables a tool.
     * PATCH /api/tools/{name}/enabled
     */
    @PatchMapping("/{name}/enabled")
    public ResponseEntity<?> setToolEnabled(@PathVariable String name,
                                            @RequestBody Map<String, Boolean> body) {
        Boolean enabled = body.get("enabled");
        if (enabled == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing 'enabled' field"));
        }

        try {
            EnhancedToolDefinition updated = toolDefinitionService.setToolEnabled(name, enabled);
            logger.info("{} tool: {}", enabled ? "Enabled" : "Disabled", name);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            logger.error("Failed to set enabled state for tool {}: {}", name, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to update tool: " + e.getMessage()));
        }
    }

    /**
     * Gets available tool categories.
     * GET /api/tools/categories
     */
    @GetMapping("/meta/categories")
    public ResponseEntity<Map<String, ToolDefinitionService.CategoryInfo>> getCategories() {
        return ResponseEntity.ok(toolDefinitionService.getCategories());
    }

    /**
     * Gets tools grouped by category.
     * GET /api/tools/grouped
     */
    @GetMapping("/meta/grouped")
    public ResponseEntity<Map<String, List<EnhancedToolDefinition>>> getToolsGroupedByCategory() {
        return ResponseEntity.ok(toolDefinitionService.getToolsByCategories());
    }

    /**
     * Generates the complete agent tools prompt.
     * GET /api/tools/agent-prompt
     */
    @GetMapping("/meta/agent-prompt")
    public ResponseEntity<String> getAgentToolsPrompt() {
        return ResponseEntity.ok(toolDefinitionService.generateAgentToolsPrompt());
    }

    /**
     * Gets a summary of all tools for quick reference.
     * GET /api/tools/summary
     */
    @GetMapping("/meta/summary")
    public ResponseEntity<ToolsSummary> getToolsSummary() {
        List<EnhancedToolDefinition> allTools = toolDefinitionService.getAllTools();
        List<EnhancedToolDefinition> enabledTools = toolDefinitionService.getEnabledTools();
        Map<String, List<EnhancedToolDefinition>> byCategory = toolDefinitionService.getToolsByCategories();

        long builtInCount = allTools.stream()
                .filter(t -> t.getSource() == EnhancedToolDefinition.ToolSource.BUILT_IN)
                .count();
        long customCount = allTools.stream()
                .filter(t -> t.getSource() == EnhancedToolDefinition.ToolSource.CUSTOM)
                .count();

        ToolsSummary summary = new ToolsSummary(
                allTools.size(),
                enabledTools.size(),
                builtInCount,
                customCount,
                byCategory.size(),
                byCategory.keySet().stream().toList()
        );

        return ResponseEntity.ok(summary);
    }

    /**
     * Refreshes the tool definitions from disk and rediscovers built-in tools.
     * POST /api/tools/refresh
     */
    @PostMapping("/meta/refresh")
    public ResponseEntity<?> refreshTools() {
        try {
            toolDefinitionService.refresh();
            logger.info("Refreshed tool definitions");
            return ResponseEntity.ok(Map.of(
                    "message", "Tools refreshed successfully",
                    "toolCount", toolDefinitionService.getAllTools().size()
            ));
        } catch (Exception e) {
            logger.error("Failed to refresh tools: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to refresh tools: " + e.getMessage()));
        }
    }

    /**
     * Adds usage examples to a tool.
     * POST /api/tools/{name}/examples
     */
    @PostMapping("/{name}/examples")
    public ResponseEntity<?> addExample(@PathVariable String name,
                                        @RequestBody EnhancedToolDefinition.UsageExample example) {
        return toolDefinitionService.getToolByName(name)
                .map(tool -> {
                    if (tool.getExamples() == null) {
                        tool.setExamples(new java.util.ArrayList<>());
                    }
                    tool.getExamples().add(example);
                    EnhancedToolDefinition updated = toolDefinitionService.saveToolDefinition(tool);
                    logger.info("Added example to tool: {}", name);
                    return ResponseEntity.ok(updated);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Updates usage hints for a tool.
     * PUT /api/tools/{name}/hints
     */
    @PutMapping("/{name}/hints")
    public ResponseEntity<?> updateHints(@PathVariable String name,
                                         @RequestBody List<String> hints) {
        return toolDefinitionService.getToolByName(name)
                .map(tool -> {
                    tool.setUsageHints(hints);
                    EnhancedToolDefinition updated = toolDefinitionService.saveToolDefinition(tool);
                    logger.info("Updated hints for tool: {}", name);
                    return ResponseEntity.ok(updated);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Updates related tools for a tool.
     * PUT /api/tools/{name}/related
     */
    @PutMapping("/{name}/related")
    public ResponseEntity<?> updateRelatedTools(@PathVariable String name,
                                                @RequestBody List<String> relatedTools) {
        return toolDefinitionService.getToolByName(name)
                .map(tool -> {
                    tool.setRelatedTools(relatedTools);
                    EnhancedToolDefinition updated = toolDefinitionService.saveToolDefinition(tool);
                    logger.info("Updated related tools for: {}", name);
                    return ResponseEntity.ok(updated);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Summary record for quick overview.
     */
    public record ToolsSummary(
            int totalTools,
            int enabledTools,
            long builtInTools,
            long customTools,
            int categoryCount,
            List<String> categories
    ) {}
}
