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
import ai.kompile.app.services.mcp.ToolPermissionService;
import ai.kompile.app.services.mcp.ToolPermissionService.PermissionLevel;
import ai.kompile.app.services.mcp.ToolPermissionConfig;
import ai.kompile.core.mcp.EnhancedToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/tool-permissions")
public class ToolPermissionController {

    private static final Logger log = LoggerFactory.getLogger(ToolPermissionController.class);

    private final ToolPermissionService permissionService;

    @Autowired(required = false)
    private BuiltInToolDiscoveryService toolDiscoveryService;

    public ToolPermissionController(ToolPermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @GetMapping
    public ResponseEntity<ToolPermissionConfig> getConfig() {
        return ResponseEntity.ok(permissionService.getConfig());
    }

    @GetMapping("/tools-with-status")
    public ResponseEntity<Map<String, Object>> getToolsWithStatus() {
        ToolPermissionConfig config = permissionService.getConfig();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("defaultPermission", config.getDefaultPermission());

        // Build category info
        Map<String, Object> categories = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> toolsByCategory = new LinkedHashMap<>();

        if (toolDiscoveryService != null) {
            List<EnhancedToolDefinition> allTools = toolDiscoveryService.getEnhancedToolDefinitions();

            // Group tools by category
            for (EnhancedToolDefinition tool : allTools) {
                String category = tool.getCategory() != null ? tool.getCategory() : "system";
                toolsByCategory.computeIfAbsent(category, k -> new ArrayList<>());

                String resolvedPermission = permissionService.isToolAllowed(tool.getName(), category)
                        ? "ALLOW" : "DENY";
                boolean hasOverride = config.getToolRules().containsKey(tool.getName());

                Map<String, Object> toolInfo = new LinkedHashMap<>();
                toolInfo.put("name", tool.getName());
                toolInfo.put("category", category);
                toolInfo.put("description", tool.getDescription());
                toolInfo.put("resolvedPermission", resolvedPermission);
                toolInfo.put("hasOverride", hasOverride);
                toolsByCategory.get(category).add(toolInfo);
            }

            // Build category summaries
            for (Map.Entry<String, List<Map<String, Object>>> entry : toolsByCategory.entrySet()) {
                String catName = entry.getKey();
                Map<String, Object> catInfo = new LinkedHashMap<>();
                catInfo.put("displayName", formatCategoryName(catName));
                PermissionLevel catPermission = config.getCategoryRules().get(catName);
                catInfo.put("permission", catPermission != null ? catPermission.name() : null);
                catInfo.put("toolCount", entry.getValue().size());
                categories.put(catName, catInfo);
            }
        }

        result.put("categories", categories);

        // Flatten tools list
        List<Map<String, Object>> allToolsList = toolsByCategory.values().stream()
                .flatMap(Collection::stream)
                .collect(Collectors.toList());
        result.put("tools", allToolsList);

        return ResponseEntity.ok(result);
    }

    @PutMapping("/default")
    public ResponseEntity<Map<String, Object>> setDefaultPermission(@RequestBody Map<String, String> body) {
        PermissionLevel level = PermissionLevel.valueOf(body.get("permission"));
        permissionService.setDefaultPermission(level);
        log.info("Updated default tool permission to {}", level);
        return ResponseEntity.ok(Map.of("defaultPermission", level.name()));
    }

    @PutMapping("/category/{name}")
    public ResponseEntity<Map<String, Object>> setCategoryRule(
            @PathVariable String name, @RequestBody Map<String, String> body) {
        PermissionLevel level = PermissionLevel.valueOf(body.get("permission"));
        permissionService.setCategoryRule(name, level);
        log.info("Set category '{}' permission to {}", name, level);
        return ResponseEntity.ok(Map.of("category", name, "permission", level.name()));
    }

    @DeleteMapping("/category/{name}")
    public ResponseEntity<Map<String, Object>> removeCategoryRule(@PathVariable String name) {
        permissionService.removeCategoryRule(name);
        log.info("Removed category '{}' permission rule", name);
        return ResponseEntity.ok(Map.of("category", name, "removed", true));
    }

    @PutMapping("/tool/{name}")
    public ResponseEntity<Map<String, Object>> setToolRule(
            @PathVariable String name, @RequestBody Map<String, String> body) {
        PermissionLevel level = PermissionLevel.valueOf(body.get("permission"));
        permissionService.setToolRule(name, level);
        log.info("Set tool '{}' permission to {}", name, level);
        return ResponseEntity.ok(Map.of("tool", name, "permission", level.name()));
    }

    @DeleteMapping("/tool/{name}")
    public ResponseEntity<Map<String, Object>> removeToolRule(@PathVariable String name) {
        permissionService.removeToolRule(name);
        log.info("Removed tool '{}' permission override", name);
        return ResponseEntity.ok(Map.of("tool", name, "removed", true));
    }

    @PostMapping("/bulk")
    public ResponseEntity<Map<String, Object>> bulkUpdate(@RequestBody Map<String, Object> body) {
        Map<String, PermissionLevel> categoryRules = parseRules(body.get("categoryRules"));
        Map<String, PermissionLevel> toolRules = parseRules(body.get("toolRules"));

        if (body.containsKey("defaultPermission")) {
            permissionService.setDefaultPermission(
                    PermissionLevel.valueOf((String) body.get("defaultPermission")));
        }

        permissionService.bulkUpdate(categoryRules, toolRules);
        log.info("Bulk updated permissions: {} category rules, {} tool rules",
                categoryRules != null ? categoryRules.size() : 0,
                toolRules != null ? toolRules.size() : 0);
        return ResponseEntity.ok(Map.of("updated", true));
    }

    @SuppressWarnings("unchecked")
    private Map<String, PermissionLevel> parseRules(Object rulesObj) {
        if (rulesObj == null) return null;
        Map<String, String> raw = (Map<String, String>) rulesObj;
        Map<String, PermissionLevel> result = new HashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            result.put(entry.getKey(), PermissionLevel.valueOf(entry.getValue()));
        }
        return result;
    }

    private String formatCategoryName(String category) {
        return switch (category) {
            case "rag" -> "RAG & Document Search";
            case "filesystem" -> "File System Operations";
            case "indexing" -> "Index Management";
            case "model" -> "Model Management";
            case "system" -> "System Diagnostics";
            case "config" -> "Application Configuration";
            case "action_log" -> "Action History";
            case "chat" -> "Chat & Sessions";
            case "factsheet" -> "Fact Sheet Management";
            case "evaluation" -> "Evaluation & Testing";
            case "ingestion" -> "Document Ingestion";
            case "pipeline" -> "Pipeline Management";
            case "settings" -> "Filter & Guardrail Settings";
            case "chunk" -> "Chunk Management";
            case "prompt" -> "Prompt Templates";
            case "timing" -> "Performance Timing";
            case "benchmark" -> "Benchmarks";
            case "backup" -> "Backup & Restore";
            case "orchestrator" -> "Orchestrator Workflows";
            case "experiment" -> "Experiments";
            case "crossindex" -> "Cross-Index Sync";
            case "archive" -> "Archive Management";
            case "vlm" -> "Vision Language Models";
            case "kvcache" -> "KV Cache";
            case "device" -> "Device Routing";
            case "subprocess" -> "Subprocess Management";
            case "delegation" -> "Agent Delegation";
            default -> category.substring(0, 1).toUpperCase() + category.substring(1);
        };
    }
}
