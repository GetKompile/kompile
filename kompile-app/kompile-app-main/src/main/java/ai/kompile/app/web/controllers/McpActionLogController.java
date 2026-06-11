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

import ai.kompile.app.services.mcp.McpActionLogService;
import ai.kompile.app.services.mcp.McpActionLogService.ActionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller exposing MCP tool action log for the UI.
 */
@RestController
@RequestMapping("/api/mcp/action-log")
public class McpActionLogController {

    private static final Logger logger = LoggerFactory.getLogger(McpActionLogController.class);

    private final McpActionLogService actionLogService;

    public McpActionLogController(McpActionLogService actionLogService) {
        this.actionLogService = actionLogService;
    }

    @GetMapping
    public ResponseEntity<?> getActionLog(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String toolName,
            @RequestParam(required = false) String actionType,
            @RequestParam(defaultValue = "false") boolean undoableOnly) {
        ActionType type = null;
        if (actionType != null && !actionType.isEmpty()) {
            try {
                type = ActionType.valueOf(actionType);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid actionType: " + actionType));
            }
        }
        List<Map<String, Object>> actions = actionLogService.getActionLog(
                limit, toolName, type, undoableOnly);
        return ResponseEntity.ok(Map.of("actions", actions, "count", actions.size()));
    }

    @GetMapping("/{actionId}")
    public ResponseEntity<?> getAction(@PathVariable long actionId) {
        Map<String, Object> action = actionLogService.getAction(actionId);
        if (action == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(action);
    }

    @GetMapping("/stats")
    public ResponseEntity<?> getStats() {
        return ResponseEntity.ok(actionLogService.getStatistics());
    }

    @PostMapping("/{actionId}/undo")
    public ResponseEntity<?> undoAction(@PathVariable long actionId) {
        Map<String, Object> result = actionLogService.undoAction(actionId);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/undo-last")
    public ResponseEntity<?> undoLastAction() {
        Map<String, Object> result = actionLogService.undoLastAction();
        return ResponseEntity.ok(result);
    }

    @DeleteMapping
    public ResponseEntity<?> clearLog() {
        int cleared = actionLogService.clearLog();
        return ResponseEntity.ok(Map.of("status", "success", "entriesCleared", cleared));
    }
}
