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

import ai.kompile.app.services.ToolCallWriterService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final ToolCallWriterService toolCallWriterService;
    private final AtomicLong actionIdGenerator = new AtomicLong(0);
    private final Object persistenceLock = new Object();
    private final String catalogSessionId = "mcp-app-" + UUID.randomUUID();

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

    @Value("${kompile.mcp.action-log.history-file:}")
    private String historyFileProperty = "";

    private volatile Path historyFile;

    @Autowired
    public McpActionLogService(ObjectMapper objectMapper,
                               ObjectProvider<ToolCallWriterService> toolCallWriterProvider) {
        this(objectMapper, toolCallWriterProvider.getIfAvailable());
    }

    public McpActionLogService(ObjectMapper objectMapper) {
        this(objectMapper, (ToolCallWriterService) null);
    }

    McpActionLogService(ObjectMapper objectMapper, ToolCallWriterService toolCallWriterService) {
        this.objectMapper = objectMapper;
        this.toolCallWriterService = toolCallWriterService;
        this.historyFile = resolveHistoryFile();
        loadPersistentHistory();
        logger.info("McpActionLogService initialized with maxEntries={}, retentionHours={}, historyFile={}",
                maxLogEntries, retentionHours, historyFile);
    }

    @PostConstruct
    public void initializePersistentHistory() {
        Path configuredHistoryFile = resolveHistoryFile();
        if (!Objects.equals(configuredHistoryFile, historyFile)) {
            historyFile = configuredHistoryFile;
            loadPersistentHistory();
        } else {
            synchronized (persistenceLock) {
                trimLogLocked();
                persistLogLocked();
            }
        }
        logger.info("MCP action log history ready: {}", historyFile);
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
        private ActionStatus status;
        private String resultSummary;
        private String errorMessage;
        private Instant completedTimestamp;

        public McpAction(long id, String toolName, String toolCategory, Map<String, Object> arguments,
                        Object result, ActionType actionType, boolean undoable,
                        String sessionId, String userId) {
            this(id, toolName, toolCategory, arguments, result, Instant.now(), actionType, undoable,
                    false, null, null, sessionId, userId, ActionStatus.SUCCESS,
                    summarizeResult(result), null, Instant.now());
        }

        private McpAction(long id, String toolName, String toolCategory, Map<String, Object> arguments,
                          Object result, Instant timestamp, ActionType actionType, boolean undoable,
                          boolean undone, String undoResult, Instant undoTimestamp,
                          String sessionId, String userId, ActionStatus status,
                          String resultSummary, String errorMessage, Instant completedTimestamp) {
            this.id = id;
            this.toolName = toolName;
            this.toolCategory = toolCategory;
            this.arguments = arguments != null ? new LinkedHashMap<>(arguments) : Collections.emptyMap();
            this.result = result;
            this.timestamp = timestamp != null ? timestamp : Instant.now();
            this.actionType = actionType;
            this.undoable = undoable;
            this.undone = undone;
            this.undoResult = undoResult;
            this.undoTimestamp = undoTimestamp;
            this.sessionId = sessionId;
            this.userId = userId;
            this.status = status != null ? status : ActionStatus.STARTED;
            this.resultSummary = resultSummary;
            this.errorMessage = errorMessage;
            this.completedTimestamp = completedTimestamp;
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
        public ActionStatus getStatus() { return status; }
        public String getResultSummary() { return resultSummary; }
        public String getErrorMessage() { return errorMessage; }
        public Instant getCompletedTimestamp() { return completedTimestamp; }
        public boolean isError() { return status == ActionStatus.FAILURE; }

        public void markUndone(String result) {
            this.undone = true;
            this.undoResult = result;
            this.undoTimestamp = Instant.now();
        }

        public void markSuccess(String resultSummary) {
            this.status = ActionStatus.SUCCESS;
            this.resultSummary = resultSummary;
            this.errorMessage = null;
            this.completedTimestamp = Instant.now();
        }

        public void markFailure(String errorMessage) {
            this.status = ActionStatus.FAILURE;
            this.errorMessage = errorMessage;
            this.completedTimestamp = Instant.now();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("toolName", toolName);
            map.put("toolCategory", toolCategory);
            map.put("arguments", arguments);
            map.put("timestamp", timestamp.toString());
            map.put("status", status.name());
            map.put("actionType", actionType.name());
            map.put("undoable", undoable);
            map.put("undone", undone);
            map.put("failed", isError());
            if (completedTimestamp != null) {
                map.put("completedTimestamp", completedTimestamp.toString());
                long durationMs = Math.max(0L, completedTimestamp.toEpochMilli() - timestamp.toEpochMilli());
                map.put("durationMs", durationMs);
            }
            if (undone) {
                map.put("undoResult", undoResult);
                map.put("undoTimestamp", undoTimestamp != null ? undoTimestamp.toString() : null);
            }
            if (resultSummary != null) map.put("resultSummary", resultSummary);
            if (errorMessage != null) map.put("errorMessage", errorMessage);
            if (sessionId != null) map.put("sessionId", sessionId);
            if (userId != null) map.put("userId", userId);
            // Don't include full result in log list for brevity
            map.put("hasResult", result != null || resultSummary != null);
            return map;
        }

        private Map<String, Object> toPersistedMap(Object undoData) {
            Map<String, Object> map = toMap();
            map.put("result", result);
            if (undoData != null) {
                map.put("undoData", undoData);
            }
            return map;
        }

        private static String summarizeResult(Object result) {
            if (result == null) {
                return null;
            }
            String value = result.toString();
            return value.length() > 1000 ? value.substring(0, 997) + "..." : value;
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
     * Execution status for a logged MCP action.
     */
    public enum ActionStatus {
        STARTED,
        SUCCESS,
        FAILURE
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

        synchronized (persistenceLock) {
            actionLog.addFirst(action);
            trimLogLocked();
            persistLogLocked();
        }

        logger.info("Logged MCP action: id={}, tool={}, type={}, undoable={}",
                id, toolName, actionType, action.isUndoable());
        recordToolCallHistory(action);

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
            synchronized (persistenceLock) {
                undoDataStore.put(action.getId(), undoData);
                persistLogLocked();
            }
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

            synchronized (persistenceLock) {
                action.markUndone(undoResult != null ? undoResult.toString() : "Undo completed");
                undoDataStore.remove(actionId); // Clean up undo data
                persistLogLocked();
            }

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
        stats.put("historyFile", historyFile != null ? historyFile.toString() : null);

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

        Map<ActionStatus, Long> byStatus = new LinkedHashMap<>();
        for (ActionStatus status : ActionStatus.values()) {
            byStatus.put(status, actionLog.stream().filter(a -> a.getStatus() == status).count());
        }
        stats.put("byStatus", byStatus);

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
        synchronized (persistenceLock) {
            actionLog.clear();
            undoDataStore.clear();
            persistLogLocked();
        }
        logger.info("Cleared action log: {} entries removed", size);
        return size;
    }

    /**
     * Trims the log to stay within limits.
     */
    private void trimLogLocked() {
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
                Instant.now(), ActionType.EXECUTE, false, false, null, null,
                null, null, ActionStatus.STARTED, null, null, null);

        synchronized (persistenceLock) {
            actionLog.addFirst(action);
            trimLogLocked();
            persistLogLocked();
        }

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
        McpAction completedAction = null;
        synchronized (persistenceLock) {
            McpAction action = findActionById(actionId);
            if (action != null) {
                if (undoData != null) {
                    undoDataStore.put(actionId, undoData);
                }
                action.markSuccess(resultSummary);
                persistLogLocked();
                completedAction = action;
            }
        }
        if (completedAction != null) {
            if (undoData != null) {
                logger.info("MCP Action Success: id={}, tool={} (undo data stored)", actionId, completedAction.getToolName());
            } else {
                logger.info("MCP Action Success: id={}, tool={}", actionId, completedAction.getToolName());
            }
            recordToolCallHistory(completedAction);
        }
    }

    /**
     * Updates an action log entry on failure.
     * Used by McpToolWrapper for automatic logging.
     *
     * @param actionId The ID of the action to update
     * @param errorMessage The error message
     */
    public void logActionFailure(long actionId, String errorMessage) {
        McpAction failedAction = null;
        synchronized (persistenceLock) {
            McpAction action = findActionById(actionId);
            if (action != null) {
                action.markFailure(errorMessage);
                persistLogLocked();
                failedAction = action;
            }
        }
        if (failedAction != null) {
            logger.warn("MCP Action Failed: id={}, tool={}, error={}", actionId, failedAction.getToolName(), errorMessage);
            recordToolCallHistory(failedAction);
        }
    }

    private McpAction findActionById(long actionId) {
        return actionLog.stream()
                .filter(a -> a.getId() == actionId)
                .findFirst()
                .orElse(null);
    }

    private void recordToolCallHistory(McpAction action) {
        if (toolCallWriterService == null || action == null) {
            return;
        }
        try {
            toolCallWriterService.record(
                    action.getSessionId() != null ? action.getSessionId() : catalogSessionId,
                    action.getToolName(),
                    serializeToolInputForHistory(action),
                    "kompile-app",
                    "mcp",
                    action.isError(),
                    null
            );
        } catch (Exception e) {
            logger.debug("Failed to append MCP action {} to tool-call history: {}", action.getId(), e.getMessage());
        }
    }

    private String serializeToolInputForHistory(McpAction action) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("arguments", action.getArguments());
        record.put("status", action.getStatus().name());
        if (action.getResultSummary() != null) {
            record.put("resultSummary", action.getResultSummary());
        }
        if (action.getErrorMessage() != null) {
            record.put("errorMessage", action.getErrorMessage());
        }
        try {
            return objectMapper.writeValueAsString(record);
        } catch (Exception e) {
            return record.toString();
        }
    }

    private Path resolveHistoryFile() {
        if (historyFileProperty != null && !historyFileProperty.isBlank()) {
            return Path.of(historyFileProperty);
        }
        return Path.of(System.getProperty("user.home"), ".kompile", "conversations",
                "mcp-action-log", "actions.jsonl");
    }

    private void loadPersistentHistory() {
        synchronized (persistenceLock) {
            actionLog.clear();
            undoDataStore.clear();
            actionIdGenerator.set(0L);
            loadPersistentHistoryLocked();
            trimLogLocked();
            persistLogLocked();
        }
    }

    private void loadPersistentHistoryLocked() {
        Path file = historyFile != null ? historyFile : resolveHistoryFile();
        if (!Files.exists(file)) {
            return;
        }

        long maxId = 0L;
        int loaded = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    Map<String, Object> persisted = objectMapper.readValue(line, MAP_TYPE);
                    McpAction action = deserializeAction(persisted);
                    if (action == null) {
                        continue;
                    }
                    actionLog.addLast(action);
                    Object undoData = persisted.get("undoData");
                    if (undoData != null && !action.isUndone()) {
                        undoDataStore.put(action.getId(), undoData);
                    }
                    maxId = Math.max(maxId, action.getId());
                    loaded++;
                } catch (Exception e) {
                    logger.warn("Skipping malformed MCP action history line in {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to load MCP action history from {}: {}", file, e.getMessage());
        }
        actionIdGenerator.set(maxId);
        if (loaded > 0) {
            logger.info("Loaded {} persisted MCP actions from {}", loaded, file);
        }
    }

    private McpAction deserializeAction(Map<String, Object> persisted) {
        Long id = asLong(persisted.get("id"));
        if (id == null) {
            return null;
        }

        ActionType actionType = parseEnum(ActionType.class, asString(persisted.get("actionType")), ActionType.EXECUTE);
        ActionStatus status = parseEnum(ActionStatus.class, asString(persisted.get("status")),
                Boolean.TRUE.equals(persisted.get("failed")) ? ActionStatus.FAILURE : ActionStatus.SUCCESS);

        return new McpAction(
                id,
                asString(persisted.get("toolName")),
                asString(persisted.get("toolCategory")),
                asMap(persisted.get("arguments")),
                persisted.get("result"),
                parseInstant(asString(persisted.get("timestamp"))),
                actionType,
                Boolean.TRUE.equals(persisted.get("undoable")),
                Boolean.TRUE.equals(persisted.get("undone")),
                asString(persisted.get("undoResult")),
                parseInstant(asString(persisted.get("undoTimestamp"))),
                asString(persisted.get("sessionId")),
                asString(persisted.get("userId")),
                status,
                asString(persisted.get("resultSummary")),
                asString(persisted.get("errorMessage")),
                parseInstant(asString(persisted.get("completedTimestamp")))
        );
    }

    private void persistLogLocked() {
        Path file = historyFile != null ? historyFile : resolveHistoryFile();
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (BufferedWriter writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                for (McpAction action : actionLog) {
                    Map<String, Object> persisted = action.toPersistedMap(undoDataStore.get(action.getId()));
                    writePersistedAction(writer, persisted);
                    writer.newLine();
                }
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.warn("Failed to persist MCP action history to {}: {}", file, e.getMessage());
        }
    }

    private void writePersistedAction(BufferedWriter writer, Map<String, Object> persisted) throws IOException {
        try {
            writer.write(objectMapper.writeValueAsString(persisted));
        } catch (Exception e) {
            Object result = persisted.get("result");
            Object undoData = persisted.get("undoData");
            if (result != null) {
                persisted.put("result", result.toString());
            }
            if (undoData != null) {
                persisted.put("undoData", undoData.toString());
            }
            writer.write(objectMapper.writeValueAsString(persisted));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), v));
            return copy;
        }
        return Collections.emptyMap();
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String string && !string.isBlank()) {
            try {
                return Long.parseLong(string);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String asString(Object value) {
        return value != null ? value.toString() : null;
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private <T extends Enum<T>> T parseEnum(Class<T> enumType, String value, T fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(enumType, value);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
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
