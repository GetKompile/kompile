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

package ai.kompile.app.services.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Service for logging all MCP tool actions and providing undo functionality for write operations.
 * Maintains an in-memory action log with configurable retention.
 */
@Service
public class McpActionLogService {

    private static final Logger logger = LoggerFactory.getLogger(McpActionLogService.class);

    private final ObjectMapper objectMapper;
    private final AtomicLong actionIdGenerator = new AtomicLong(0);

    // Action log - most recent first
    private final Deque<McpAction> actionLog = new ConcurrentLinkedDeque<>();

    // Undo handlers registered by tool name
    private final Map<String, UndoHandler> undoHandlers = new ConcurrentHashMap<>();

    // Undo data storage keyed by action ID
    private final Map<Long, Object> undoDataStore = new ConcurrentHashMap<>();

    @Value("${kompile.mcp.action-log.max-entries:1000}")
    private int maxLogEntries = 1000;

    @Value("${kompile.mcp.action-log.retention-hours:24}")
    private int retentionHours = 24;

    public McpActionLogService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        logger.info("McpActionLogService initialized with maxEntries={}, retentionHours={}",
                maxLogEntries, retentionHours);
    }

    /**
     * Represents a single MCP action in the log.
     */
    public static class McpAction {
        private final long id;
        private final String toolName;
        private final String toolCategory;
        private final Map<String, Object> arguments;
        private final Object result;
        private final Instant timestamp;
        private final ActionType actionType;
        private final boolean undoable;
        private boolean undone;
        private String undoResult;
        private Instant undoTimestamp;
        private final String sessionId;
        private final String userId;

        public McpAction(long id, String toolName, String toolCategory, Map<String, Object> arguments,
                        Object result, ActionType actionType, boolean undoable,
                        String sessionId, String userId) {
            this.id = id;
            this.toolName = toolName;
            this.toolCategory = toolCategory;
            this.arguments = arguments != null ? new LinkedHashMap<>(arguments) : Collections.emptyMap();
            this.result = result;
            this.timestamp = Instant.now();
            this.actionType = actionType;
            this.undoable = undoable;
            this.undone = false;
            this.sessionId = sessionId;
            this.userId = userId;
        }

        // Getters
        public long getId() { return id; }
        public String getToolName() { return toolName; }
        public String getToolCategory() { return toolCategory; }
        public Map<String, Object> getArguments() { return Collections.unmodifiableMap(arguments); }
        public Object getResult() { return result; }
        public Instant getTimestamp() { return timestamp; }
        public ActionType getActionType() { return actionType; }
        public boolean isUndoable() { return undoable; }
        public boolean isUndone() { return undone; }
        public String getUndoResult() { return undoResult; }
        public Instant getUndoTimestamp() { return undoTimestamp; }
        public String getSessionId() { return sessionId; }
        public String getUserId() { return userId; }

        public void markUndone(String result) {
            this.undone = true;
            this.undoResult = result;
            this.undoTimestamp = Instant.now();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("toolName", toolName);
            map.put("toolCategory", toolCategory);
            map.put("arguments", arguments);
            map.put("timestamp", timestamp.toString());
            map.put("actionType", actionType.name());
            map.put("undoable", undoable);
            map.put("undone", undone);
            if (undone) {
                map.put("undoResult", undoResult);
                map.put("undoTimestamp", undoTimestamp != null ? undoTimestamp.toString() : null);
            }
            if (sessionId != null) map.put("sessionId", sessionId);
            if (userId != null) map.put("userId", userId);
            // Don't include full result in log list for brevity
            map.put("hasResult", result != null);
            return map;
        }
    }

    /**
     * Type of action for categorization.
     */
    public enum ActionType {
        READ,       // Read-only operations (not undoable)
        WRITE,      // Create/update operations (potentially undoable)
        DELETE,     // Delete operations (potentially undoable with backup)
        EXECUTE,    // Execution operations (may or may not be undoable)
        CONFIG      // Configuration changes (potentially undoable)
    }

    /**
     * Functional interface for undo handlers.
     */
    @FunctionalInterface
    public interface UndoHandler {
        /**
         * Performs the undo operation.
         *
         * @param action The action to undo
         * @param undoData Optional data saved during the original operation to facilitate undo
         * @return Result message or object describing the undo result
         * @throws Exception if undo fails
         */
        Object undo(McpAction action, Object undoData) throws Exception;
    }

    /**
     * Registers an undo handler for a specific tool.
     *
     * @param toolName The tool name
     * @param handler The undo handler
     */
    public void registerUndoHandler(String toolName, UndoHandler handler) {
        undoHandlers.put(toolName, handler);
        logger.debug("Registered undo handler for tool: {}", toolName);
    }

    /**
     * Logs an MCP action.
     *
     * @param toolName The name of the tool invoked
     * @param toolCategory The category of the tool
     * @param arguments The arguments passed to the tool
     * @param result The result of the tool invocation
     * @param actionType The type of action
     * @param undoable Whether this action can be undone
     * @param sessionId Optional session ID
     * @param userId Optional user ID
     * @return The created action record
     */
    public McpAction logAction(String toolName, String toolCategory, Map<String, Object> arguments,
                               Object result, ActionType actionType, boolean undoable,
                               String sessionId, String userId) {
        long id = actionIdGenerator.incrementAndGet();
        McpAction action = new McpAction(id, toolName, toolCategory, arguments, result,
                actionType, undoable && undoHandlers.containsKey(toolName), sessionId, userId);

        actionLog.addFirst(action);

        // Trim log if needed
        trimLog();

        logger.info("Logged MCP action: id={}, tool={}, type={}, undoable={}",
                id, toolName, actionType, action.isUndoable());

        return action;
    }

    /**
     * Logs an action with undo data.
     *
     * @param toolName The name of the tool
     * @param toolCategory The category of the tool
     * @param arguments The arguments passed
     * @param result The result
     * @param actionType The action type
     * @param undoData Data needed to undo this action (e.g., previous state)
     * @param sessionId Optional session ID
     * @param userId Optional user ID
     * @return The created action record
     */
    public McpAction logActionWithUndoData(String toolName, String toolCategory,
                                            Map<String, Object> arguments, Object result,
                                            ActionType actionType, Object undoData,
                                            String sessionId, String userId) {
        McpAction action = logAction(toolName, toolCategory, arguments, result,
                actionType, true, sessionId, userId);

        if (undoData != null) {
            undoDataStore.put(action.getId(), undoData);
            logger.debug("Stored undo data for action {}", action.getId());
        }

        return action;
    }

    /**
     * Attempts to undo an action by ID.
     *
     * @param actionId The ID of the action to undo
     * @return The result of the undo operation
     */
    public Map<String, Object> undoAction(long actionId) {
        // Find the action
        McpAction action = actionLog.stream()
                .filter(a -> a.getId() == actionId)
                .findFirst()
                .orElse(null);

        if (action == null) {
            return Map.of("status", "error", "error", "Action not found: " + actionId);
        }

        if (!action.isUndoable()) {
            return Map.of("status", "error", "error", "Action is not undoable",
                    "actionId", actionId, "toolName", action.getToolName());
        }

        if (action.isUndone()) {
            return Map.of("status", "error", "error", "Action has already been undone",
                    "actionId", actionId, "undoTimestamp", action.getUndoTimestamp().toString());
        }

        UndoHandler handler = undoHandlers.get(action.getToolName());
        if (handler == null) {
            return Map.of("status", "error", "error", "No undo handler registered for tool: " + action.getToolName());
        }

        try {
            Object undoData = undoDataStore.get(actionId);
            Object undoResult = handler.undo(action, undoData);

            action.markUndone(undoResult != null ? undoResult.toString() : "Undo completed");
            undoDataStore.remove(actionId); // Clean up undo data

            logger.info("Successfully undid action: id={}, tool={}", actionId, action.getToolName());

            return Map.of(
                    "status", "success",
                    "actionId", actionId,
                    "toolName", action.getToolName(),
                    "undoResult", undoResult != null ? undoResult : "Undo completed"
            );
        } catch (Exception e) {
            logger.error("Failed to undo action {}: {}", actionId, e.getMessage(), e);
            return Map.of("status", "error", "error", "Undo failed: " + e.getMessage(),
                    "actionId", actionId, "toolName", action.getToolName());
        }
    }

    /**
     * Undoes the most recent undoable action.
     *
     * @return The result of the undo operation
     */
    public Map<String, Object> undoLastAction() {
        McpAction lastUndoable = actionLog.stream()
                .filter(a -> a.isUndoable() && !a.isUndone())
                .findFirst()
                .orElse(null);

        if (lastUndoable == null) {
            return Map.of("status", "error", "error", "No undoable actions found");
        }

        return undoAction(lastUndoable.getId());
    }

    /**
     * Gets the action log with optional filtering.
     *
     * @param limit Maximum number of entries to return
     * @param toolName Optional tool name filter
     * @param actionType Optional action type filter
     * @param undoableOnly If true, only return undoable actions
     * @return List of action records
     */
    public List<Map<String, Object>> getActionLog(int limit, String toolName,
                                                   ActionType actionType, boolean undoableOnly) {
        return actionLog.stream()
                .filter(a -> toolName == null || a.getToolName().equals(toolName))
                .filter(a -> actionType == null || a.getActionType() == actionType)
                .filter(a -> !undoableOnly || (a.isUndoable() && !a.isUndone()))
                .limit(limit > 0 ? limit : 100)
                .map(McpAction::toMap)
                .toList();
    }

    /**
     * Gets a specific action by ID.
     *
     * @param actionId The action ID
     * @return The action details or null if not found
     */
    public Map<String, Object> getAction(long actionId) {
        return actionLog.stream()
                .filter(a -> a.getId() == actionId)
                .findFirst()
                .map(action -> {
                    Map<String, Object> map = action.toMap();
                    // Include full result for single action lookup
                    map.put("result", action.getResult());
                    return map;
                })
                .orElse(null);
    }

    /**
     * Gets statistics about the action log.
     *
     * @return Statistics map
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalActions", actionLog.size());
        stats.put("maxEntries", maxLogEntries);
        stats.put("retentionHours", retentionHours);

        // Count by type
        Map<ActionType, Long> byType = new LinkedHashMap<>();
        for (ActionType type : ActionType.values()) {
            byType.put(type, actionLog.stream().filter(a -> a.getActionType() == type).count());
        }
        stats.put("byActionType", byType);

        // Count undoable
        long undoable = actionLog.stream().filter(McpAction::isUndoable).count();
        long undoablePending = actionLog.stream().filter(a -> a.isUndoable() && !a.isUndone()).count();
        long undone = actionLog.stream().filter(McpAction::isUndone).count();
        stats.put("undoableTotal", undoable);
        stats.put("undoablePending", undoablePending);
        stats.put("undone", undone);

        // Count by tool (top 10)
        Map<String, Long> byTool = new LinkedHashMap<>();
        actionLog.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        McpAction::getToolName,
                        java.util.stream.Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .forEach(e -> byTool.put(e.getKey(), e.getValue()));
        stats.put("topToolsByUsage", byTool);

        // Registered undo handlers
        stats.put("registeredUndoHandlers", new ArrayList<>(undoHandlers.keySet()));

        return stats;
    }

    /**
     * Clears the action log.
     *
     * @return Number of entries cleared
     */
    public int clearLog() {
        int size = actionLog.size();
        actionLog.clear();
        undoDataStore.clear();
        logger.info("Cleared action log: {} entries removed", size);
        return size;
    }

    /**
     * Trims the log to stay within limits.
     */
    private void trimLog() {
        // Remove old entries beyond max
        while (actionLog.size() > maxLogEntries) {
            McpAction removed = actionLog.pollLast();
            if (removed != null) {
                undoDataStore.remove(removed.getId());
            }
        }

        // Remove expired entries
        Instant cutoff = Instant.now().minusSeconds(retentionHours * 3600L);
        actionLog.removeIf(action -> {
            if (action.getTimestamp().isBefore(cutoff)) {
                undoDataStore.remove(action.getId());
                return true;
            }
            return false;
        });
    }

    /**
     * Helper method to execute a tool operation with automatic logging.
     *
     * @param toolName The tool name
     * @param toolCategory The tool category
     * @param arguments The arguments
     * @param actionType The action type
     * @param operation The operation to execute
     * @param undoDataSupplier Optional supplier for undo data (called before operation)
     * @param <T> The result type
     * @return The operation result
     */
    public <T> T executeAndLog(String toolName, String toolCategory, Map<String, Object> arguments,
                               ActionType actionType, Supplier<T> operation,
                               Supplier<Object> undoDataSupplier) {
        Object undoData = null;
        if (undoDataSupplier != null && actionType != ActionType.READ) {
            try {
                undoData = undoDataSupplier.get();
            } catch (Exception e) {
                logger.warn("Failed to capture undo data for {}: {}", toolName, e.getMessage());
            }
        }

        T result = operation.get();

        if (undoData != null) {
            logActionWithUndoData(toolName, toolCategory, arguments, result, actionType, undoData, null, null);
        } else {
            logAction(toolName, toolCategory, arguments, result, actionType,
                    actionType != ActionType.READ, null, null);
        }

        return result;
    }

    // ============================================================
    // Methods for McpToolWrapper integration
    // ============================================================

    /**
     * Logs the start of a tool action (before execution).
     * Used by McpToolWrapper for automatic logging.
     *
     * @param toolName The name of the tool being invoked
     * @param arguments The arguments passed to the tool
     * @return The created action record (in STARTED status)
     */
    public McpAction logActionStart(String toolName, Map<String, Object> arguments) {
        long id = actionIdGenerator.incrementAndGet();
        String category = determineCategory(toolName);

        McpAction action = new McpAction(id, toolName, category, arguments, null,
                ActionType.EXECUTE, false, null, null);

        actionLog.addFirst(action);
        trimLog();

        logger.debug("MCP Action Started: id={}, tool={}, args={}", id, toolName, arguments);
        return action;
    }

    /**
     * Updates an action log entry on successful completion.
     * Used by McpToolWrapper for automatic logging.
     *
     * @param actionId The ID of the action to update
     * @param resultSummary A summary of the result (truncated if needed)
     * @param undoData Optional data needed to undo this action
     */
    public void logActionSuccess(long actionId, String resultSummary, Map<String, Object> undoData) {
        actionLog.stream()
                .filter(a -> a.getId() == actionId)
                .findFirst()
                .ifPresent(action -> {
                    // Update the action as successful
                    // Since McpAction is immutable after creation, we need to track success separately
                    // or we can store result info in undoDataStore
                    if (undoData != null) {
                        undoDataStore.put(actionId, undoData);
                        logger.info("MCP Action Success: id={}, tool={} (undoable)", actionId, action.getToolName());
                    } else {
                        logger.info("MCP Action Success: id={}, tool={}", actionId, action.getToolName());
                    }
                });
    }

    /**
     * Updates an action log entry on failure.
     * Used by McpToolWrapper for automatic logging.
     *
     * @param actionId The ID of the action to update
     * @param errorMessage The error message
     */
    public void logActionFailure(long actionId, String errorMessage) {
        actionLog.stream()
                .filter(a -> a.getId() == actionId)
                .findFirst()
                .ifPresent(action -> {
                    logger.warn("MCP Action Failed: id={}, tool={}, error={}", actionId, action.getToolName(), errorMessage);
                });
    }

    /**
     * Determines the category for a tool based on its name.
     */
    private String determineCategory(String toolName) {
        if (toolName == null) return "unknown";

        // RAG tools
        if (toolName.contains("rag") || toolName.contains("query") || toolName.contains("search")) {
            return "rag";
        }
        // Filesystem tools
        if (toolName.contains("file") || toolName.contains("directory") || toolName.contains("list_files")) {
            return "filesystem";
        }
        // Model tools
        if (toolName.contains("model") || toolName.contains("embedding") || toolName.contains("samediff") || toolName.contains("nd4j")) {
            return "model";
        }
        // Index tools
        if (toolName.contains("index")) {
            return "indexing";
        }
        // Document tools
        if (toolName.contains("document") || toolName.contains("loader") || toolName.contains("chunker")) {
            return "document";
        }
        // Chat tools
        if (toolName.contains("chat") || toolName.contains("session") || toolName.contains("agent")) {
            return "chat";
        }
        // System tools
        if (toolName.contains("system") || toolName.contains("memory") || toolName.contains("cpu") || toolName.contains("jvm")) {
            return "system";
        }
        // Config tools
        if (toolName.contains("config") || toolName.contains("profile") || toolName.contains("bean")) {
            return "configuration";
        }
        // Action log tools
        if (toolName.contains("action") || toolName.contains("undo")) {
            return "action_log";
        }

        return "general";
    }
}
