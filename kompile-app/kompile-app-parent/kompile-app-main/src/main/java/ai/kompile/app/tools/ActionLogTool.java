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

import ai.kompile.app.services.mcp.McpActionLogService;
import ai.kompile.app.services.mcp.McpActionLogService.ActionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * MCP Tool for viewing action logs and performing undo operations.
 * Provides visibility into all MCP tool invocations and ability to undo write operations.
 */
@Component
public class ActionLogTool {

    private static final Logger logger = LoggerFactory.getLogger(ActionLogTool.class);

    private final McpActionLogService actionLogService;

    public ActionLogTool(McpActionLogService actionLogService) {
        this.actionLogService = actionLogService;
        logger.info("ActionLogTool initialized");
    }

    // Input records for tools
    public record GetActionLogInput(Integer limit, String toolName, String actionType, Boolean undoableOnly) {}
    public record GetActionInput(Long actionId) {}
    public record UndoActionInput(Long actionId) {}
    public record UndoLastActionInput() {}
    public record GetActionStatsInput() {}
    public record ClearActionLogInput(Boolean confirm) {}

    /**
     * Gets the action log with optional filtering.
     */
    @Tool(name = "get_action_log",
            description = "Gets the log of recent MCP tool actions. Optionally filter by: limit (max entries to return, default 50), " +
                    "toolName (specific tool), actionType (READ, WRITE, DELETE, EXECUTE, CONFIG), undoableOnly (true to show only undoable actions).")
    public Map<String, Object> getActionLog(GetActionLogInput input) {
        logger.info("Getting action log with filters: limit={}, toolName={}, actionType={}, undoableOnly={}",
                input.limit(), input.toolName(), input.actionType(), input.undoableOnly());

        try {
            int limit = input.limit() != null && input.limit() > 0 ? input.limit() : 50;
            String toolName = input.toolName();
            ActionType actionType = null;

            if (input.actionType() != null && !input.actionType().trim().isEmpty()) {
                try {
                    actionType = ActionType.valueOf(input.actionType().toUpperCase());
                } catch (IllegalArgumentException e) {
                    return Map.of("status", "error", "error",
                            "Invalid actionType. Valid values: READ, WRITE, DELETE, EXECUTE, CONFIG");
                }
            }

            boolean undoableOnly = input.undoableOnly() != null && input.undoableOnly();

            List<Map<String, Object>> actions = actionLogService.getActionLog(limit, toolName, actionType, undoableOnly);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("count", actions.size());
            result.put("limit", limit);
            if (toolName != null) result.put("filteredByTool", toolName);
            if (actionType != null) result.put("filteredByType", actionType.name());
            if (undoableOnly) result.put("undoableOnly", true);
            result.put("actions", actions);

            return result;

        } catch (Exception e) {
            logger.error("Error getting action log: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to get action log: " + e.getMessage());
        }
    }

    /**
     * Gets details of a specific action by ID.
     */
    @Tool(name = "get_action",
            description = "Gets the details of a specific action by its ID, including the full result and arguments.")
    public Map<String, Object> getAction(GetActionInput input) {
        logger.info("Getting action by ID: {}", input.actionId());

        if (input.actionId() == null) {
            return Map.of("status", "error", "error", "actionId is required");
        }

        try {
            Map<String, Object> action = actionLogService.getAction(input.actionId());

            if (action == null) {
                return Map.of("status", "error", "error", "Action not found with ID: " + input.actionId());
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.putAll(action);

            return result;

        } catch (Exception e) {
            logger.error("Error getting action {}: {}", input.actionId(), e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to get action: " + e.getMessage());
        }
    }

    /**
     * Undoes a specific action by ID.
     */
    @Tool(name = "undo_action",
            description = "Attempts to undo a specific action by its ID. Only works for undoable write operations that haven't already been undone. " +
                    "Returns the result of the undo operation.")
    public Map<String, Object> undoAction(UndoActionInput input) {
        logger.info("Attempting to undo action: {}", input.actionId());

        if (input.actionId() == null) {
            return Map.of("status", "error", "error", "actionId is required");
        }

        try {
            return actionLogService.undoAction(input.actionId());

        } catch (Exception e) {
            logger.error("Error undoing action {}: {}", input.actionId(), e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to undo action: " + e.getMessage());
        }
    }

    /**
     * Undoes the most recent undoable action.
     */
    @Tool(name = "undo_last_action",
            description = "Undoes the most recent undoable action that hasn't already been undone. " +
                    "Equivalent to finding the most recent undoable action and calling undo_action on it.")
    public Map<String, Object> undoLastAction(UndoLastActionInput input) {
        logger.info("Attempting to undo last action");

        try {
            return actionLogService.undoLastAction();

        } catch (Exception e) {
            logger.error("Error undoing last action: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to undo last action: " + e.getMessage());
        }
    }

    /**
     * Gets statistics about the action log.
     */
    @Tool(name = "get_action_stats",
            description = "Gets statistics about the action log including total actions, counts by type, undoable/undone counts, " +
                    "and top tools by usage.")
    public Map<String, Object> getActionStats(GetActionStatsInput input) {
        logger.info("Getting action log statistics");

        try {
            Map<String, Object> stats = actionLogService.getStatistics();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.putAll(stats);

            return result;

        } catch (Exception e) {
            logger.error("Error getting action stats: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to get stats: " + e.getMessage());
        }
    }

    /**
     * Clears the action log.
     */
    @Tool(name = "clear_action_log",
            description = "Clears all entries from the action log. This is irreversible - all undo data will be lost. " +
                    "Set confirm=true to proceed.")
    public Map<String, Object> clearActionLog(ClearActionLogInput input) {
        logger.info("Clear action log requested, confirm={}", input.confirm());

        if (input.confirm() == null || !input.confirm()) {
            return Map.of("status", "error", "error",
                    "Please set confirm=true to clear the action log. This is irreversible.");
        }

        try {
            int cleared = actionLogService.clearLog();

            return Map.of("status", "success",
                    "message", "Action log cleared",
                    "entriesCleared", cleared);

        } catch (Exception e) {
            logger.error("Error clearing action log: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", "Failed to clear log: " + e.getMessage());
        }
    }
}
