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

package ai.kompile.app.mcp;

import ai.kompile.app.services.mcp.McpActionLogService;
import ai.kompile.app.services.mcp.McpActionLogService.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.BiFunction;

/**
 * Wrapper for MCP tools that adds automatic logging and undo support.
 *
 * This wrapper intercepts tool calls to:
 * 1. Log the start of each tool invocation with arguments
 * 2. Log success/failure status with results
 * 3. Capture undo information for reversible operations
 *
 * Usage:
 * <pre>
 * // For read-only tools (logged but not undoable):
 * mcpToolWrapper.wrap(tool, handler)
 *
 * // For write tools (logged + can be undone):
 * mcpToolWrapper.wrapUndoable(tool, handler)
 * </pre>
 */
@Component
public class McpToolWrapper {

    private static final Logger log = LoggerFactory.getLogger(McpToolWrapper.class);

    private final McpActionLogService actionLogService;
    private final ObjectMapper objectMapper;

    @Autowired
    public McpToolWrapper(McpActionLogService actionLogService, ObjectMapper objectMapper) {
        this.actionLogService = actionLogService;
        this.objectMapper = objectMapper;
    }

    /**
     * Wrap a tool handler with automatic logging (read-only tools).
     *
     * @param tool The tool definition
     * @param handler The original handler
     * @return Wrapped tool specification with logging
     */
    public McpServerFeatures.SyncToolSpecification wrap(
            Tool tool,
            BiFunction<Object, Map<String, Object>, CallToolResult> handler) {

        return new McpServerFeatures.SyncToolSpecification(
                tool,
                (exchange, args) -> {
                    McpAction logEntry = actionLogService.logActionStart(tool.name(), args);

                    try {
                        CallToolResult result = handler.apply(exchange, args);

                        if (result.isError() != null && result.isError()) {
                            String errorMsg = extractErrorMessage(result);
                            actionLogService.logActionFailure(logEntry.getId(), errorMsg);
                        } else {
                            actionLogService.logActionSuccess(logEntry.getId(), extractResultContent(result), null);
                        }

                        return result;
                    } catch (Exception e) {
                        actionLogService.logActionFailure(logEntry.getId(), e.getMessage());
                        throw e;
                    }
                }
        );
    }

    /**
     * Wrap a tool handler with automatic logging and undo support (write tools).
     *
     * @param tool The tool definition
     * @param handler The original handler that returns both result and undo info
     * @return Wrapped tool specification with logging and undo support
     */
    public McpServerFeatures.SyncToolSpecification wrapUndoable(
            Tool tool,
            BiFunction<Object, Map<String, Object>, UndoableResult> handler) {

        return new McpServerFeatures.SyncToolSpecification(
                tool,
                (exchange, args) -> {
                    McpAction logEntry = actionLogService.logActionStart(tool.name(), args);

                    try {
                        UndoableResult undoableResult = handler.apply(exchange, args);
                        CallToolResult result = undoableResult.result();

                        if (result.isError() != null && result.isError()) {
                            String errorMsg = extractErrorMessage(result);
                            actionLogService.logActionFailure(logEntry.getId(), errorMsg);
                        } else {
                            actionLogService.logActionSuccess(
                                    logEntry.getId(),
                                    extractResultContent(result),
                                    undoableResult.undoData()
                            );
                        }

                        return result;
                    } catch (Exception e) {
                        actionLogService.logActionFailure(logEntry.getId(), e.getMessage());
                        throw e;
                    }
                }
        );
    }

    /**
     * Create undo data for a CREATE operation (undone by DELETE).
     */
    public static Map<String, Object> createUndoData(String entityType, Object entityId) {
        return Map.of(
                "undoType", "DELETE",
                "entityType", entityType,
                "entityId", entityId
        );
    }

    /**
     * Create undo data for a DELETE operation (undone by RESTORE).
     */
    public static Map<String, Object> deleteUndoData(String entityType, Object previousState) {
        return Map.of(
                "undoType", "RESTORE",
                "entityType", entityType,
                "previousState", previousState
        );
    }

    /**
     * Create undo data for an UPDATE operation (undone by REVERT).
     */
    public static Map<String, Object> updateUndoData(String entityType, Object entityId, Object previousState) {
        return Map.of(
                "undoType", "REVERT",
                "entityType", entityType,
                "entityId", entityId,
                "previousState", previousState
        );
    }

    /**
     * Result container for undoable operations.
     */
    public record UndoableResult(CallToolResult result, Map<String, Object> undoData) {
        public static UndoableResult of(CallToolResult result, Map<String, Object> undoData) {
            return new UndoableResult(result, undoData);
        }

        public static UndoableResult notUndoable(CallToolResult result) {
            return new UndoableResult(result, null);
        }
    }

    private String extractErrorMessage(CallToolResult result) {
        if (result.content() != null && !result.content().isEmpty()) {
            Object first = result.content().get(0);
            if (first instanceof TextContent tc) {
                return tc.text();
            }
        }
        return "Unknown error";
    }

    private String extractResultContent(CallToolResult result) {
        if (result.content() != null && !result.content().isEmpty()) {
            Object first = result.content().get(0);
            if (first instanceof TextContent tc) {
                String text = tc.text();
                // Truncate long results
                if (text.length() > 500) {
                    return text.substring(0, 497) + "...";
                }
                return text;
            }
        }
        return null;
    }
}
