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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link McpActionLogService}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class McpActionLogServiceTest {

    private McpActionLogService service;
    private String originalUserHome;

    @TempDir
    Path tempHome;

    @BeforeEach
    void setUp() {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        service = new McpActionLogService(new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        } else {
            System.clearProperty("user.home");
        }
    }

    // ─── logAction ────────────────────────────────────────────────────────────

    @Test
    void logAction_returnsActionWithIncrementingId() {
        McpActionLogService.McpAction a1 = service.logAction(
                "rag_query", "rag", Map.of("q", "hello"), "result1",
                McpActionLogService.ActionType.READ, false, null, null);
        McpActionLogService.McpAction a2 = service.logAction(
                "write_file", "filesystem", Map.of("path", "/tmp/f"), "result2",
                McpActionLogService.ActionType.WRITE, true, "sess1", "user1");

        assertTrue(a2.getId() > a1.getId(), "IDs should be monotonically increasing");
        assertEquals("write_file", a2.getToolName());
        assertEquals("filesystem", a2.getToolCategory());
        assertEquals("sess1", a2.getSessionId());
        assertEquals("user1", a2.getUserId());
    }

    @Test
    void logAction_readTypeIsNeverUndoable() {
        // When no handler is registered the undoable flag is always false,
        // even for READ with undoable=true requested
        McpActionLogService.McpAction action = service.logAction(
                "rag_query", "rag", Map.of(), "res",
                McpActionLogService.ActionType.READ, true /* requested */,
                null, null);

        // No handler registered → not undoable regardless of type
        assertFalse(action.isUndoable());
    }

    @Test
    void logAction_writeTypeIsUndoableWhenHandlerRegistered() {
        service.registerUndoHandler("write_file", (action, data) -> "undo");

        McpActionLogService.McpAction action = service.logAction(
                "write_file", "filesystem", Map.of(), "res",
                McpActionLogService.ActionType.WRITE, true, null, null);

        assertTrue(action.isUndoable());
    }

    @Test
    void logAction_writeTypeIsNotUndoableWhenNoHandlerRegistered() {
        McpActionLogService.McpAction action = service.logAction(
                "write_file", "filesystem", Map.of(), "res",
                McpActionLogService.ActionType.WRITE, true, null, null);

        assertFalse(action.isUndoable(), "No handler registered — should not be undoable");
    }

    // ─── registerUndoHandler + undoAction ─────────────────────────────────────

    @Test
    void undoAction_successfullyUndoes() {
        service.registerUndoHandler("delete_file", (action, data) -> "file restored");

        McpActionLogService.McpAction action = service.logAction(
                "delete_file", "filesystem", Map.of("path", "/tmp/x"), "done",
                McpActionLogService.ActionType.DELETE, true, null, null);

        Map<String, Object> result = service.undoAction(action.getId());

        assertEquals("success", result.get("status"));
        assertTrue(action.isUndone());
    }

    @Test
    void undoAction_errorWhenActionNotFound() {
        Map<String, Object> result = service.undoAction(999999L);
        assertEquals("error", result.get("status"));
        assertNotNull(result.get("error"));
    }

    @Test
    void undoAction_errorWhenNotUndoable() {
        McpActionLogService.McpAction action = service.logAction(
                "rag_query", "rag", Map.of(), "res",
                McpActionLogService.ActionType.READ, false, null, null);

        Map<String, Object> result = service.undoAction(action.getId());
        assertEquals("error", result.get("status"));
    }

    @Test
    void undoAction_errorWhenAlreadyUndone() {
        service.registerUndoHandler("write_file", (action, data) -> "undone");

        McpActionLogService.McpAction action = service.logAction(
                "write_file", "filesystem", Map.of(), "res",
                McpActionLogService.ActionType.WRITE, true, null, null);

        service.undoAction(action.getId());
        Map<String, Object> secondUndo = service.undoAction(action.getId());

        assertEquals("error", secondUndo.get("status"));
    }

    @Test
    void undoAction_handlerExceptionProducesErrorResult() {
        service.registerUndoHandler("write_file", (action, data) -> {
            throw new RuntimeException("disk full");
        });

        McpActionLogService.McpAction action = service.logAction(
                "write_file", "filesystem", Map.of(), "res",
                McpActionLogService.ActionType.WRITE, true, null, null);

        Map<String, Object> result = service.undoAction(action.getId());
        assertEquals("error", result.get("status"));
        assertTrue(result.get("error").toString().contains("disk full"));
    }

    // ─── undoLastAction ───────────────────────────────────────────────────────

    @Test
    void undoLastAction_returnsErrorWhenNoUndoableActions() {
        Map<String, Object> result = service.undoLastAction();
        assertEquals("error", result.get("status"));
    }

    @Test
    void undoLastAction_undoesMostRecentUndoableAction() {
        service.registerUndoHandler("write_file", (action, data) -> "reverted");

        service.logAction("rag_query", "rag", Map.of(), "res",
                McpActionLogService.ActionType.READ, false, null, null);
        McpActionLogService.McpAction writeAction = service.logAction(
                "write_file", "filesystem", Map.of(), "res",
                McpActionLogService.ActionType.WRITE, true, null, null);

        Map<String, Object> result = service.undoLastAction();
        assertEquals("success", result.get("status"));
        assertEquals(writeAction.getId(), ((Number) result.get("actionId")).longValue());
    }

    // ─── getActionLog ─────────────────────────────────────────────────────────

    @Test
    void getActionLog_limitsResults() {
        for (int i = 0; i < 10; i++) {
            service.logAction("tool_" + i, "cat", Map.of(), null,
                    McpActionLogService.ActionType.READ, false, null, null);
        }
        List<Map<String, Object>> log = service.getActionLog(3, null, null, false);
        assertEquals(3, log.size());
    }

    @Test
    void getActionLog_filtersByToolName() {
        service.logAction("rag_query", "rag", Map.of(), null,
                McpActionLogService.ActionType.READ, false, null, null);
        service.logAction("write_file", "filesystem", Map.of(), null,
                McpActionLogService.ActionType.WRITE, false, null, null);

        List<Map<String, Object>> log = service.getActionLog(100, "rag_query", null, false);
        assertEquals(1, log.size());
        assertEquals("rag_query", log.get(0).get("toolName"));
    }

    @Test
    void getActionLog_filtersByActionType() {
        service.logAction("rag_query", "rag", Map.of(), null,
                McpActionLogService.ActionType.READ, false, null, null);
        service.logAction("write_file", "filesystem", Map.of(), null,
                McpActionLogService.ActionType.WRITE, false, null, null);

        List<Map<String, Object>> log = service.getActionLog(100, null,
                McpActionLogService.ActionType.WRITE, false);
        assertEquals(1, log.size());
        assertEquals("WRITE", log.get(0).get("actionType"));
    }

    @Test
    void getActionLog_undoableOnlyFilter() {
        service.registerUndoHandler("write_file", (action, data) -> "ok");
        service.logAction("rag_query", "rag", Map.of(), null,
                McpActionLogService.ActionType.READ, false, null, null);
        service.logAction("write_file", "filesystem", Map.of(), null,
                McpActionLogService.ActionType.WRITE, true, null, null);

        List<Map<String, Object>> log = service.getActionLog(100, null, null, true);
        assertEquals(1, log.size());
        assertEquals("write_file", log.get(0).get("toolName"));
    }

    // ─── getAction ────────────────────────────────────────────────────────────

    @Test
    void getAction_returnsNullForUnknownId() {
        assertNull(service.getAction(999999L));
    }

    @Test
    void getAction_returnsFullResultForKnownId() {
        McpActionLogService.McpAction action = service.logAction(
                "rag_query", "rag", Map.of("q", "test"), "full_result",
                McpActionLogService.ActionType.READ, false, null, null);

        Map<String, Object> found = service.getAction(action.getId());
        assertNotNull(found);
        assertEquals("rag_query", found.get("toolName"));
        assertEquals("full_result", found.get("result"));
    }

    // ─── getStatistics ────────────────────────────────────────────────────────

    @Test
    void getStatistics_reflectsLoggedActions() {
        service.registerUndoHandler("write_file", (action, data) -> "ok");
        service.logAction("rag_query", "rag", Map.of(), null,
                McpActionLogService.ActionType.READ, false, null, null);
        service.logAction("write_file", "filesystem", Map.of(), null,
                McpActionLogService.ActionType.WRITE, true, null, null);

        Map<String, Object> stats = service.getStatistics();
        assertEquals(2, ((Number) stats.get("totalActions")).intValue());
        assertEquals(1, ((Number) stats.get("undoableTotal")).intValue());
        assertEquals(1, ((Number) stats.get("undoablePending")).intValue());
        assertEquals(0, ((Number) stats.get("undone")).intValue());
    }

    // ─── clearLog ─────────────────────────────────────────────────────────────

    @Test
    void clearLog_removesAllEntries() {
        service.logAction("t1", "cat", Map.of(), null,
                McpActionLogService.ActionType.READ, false, null, null);
        service.logAction("t2", "cat", Map.of(), null,
                McpActionLogService.ActionType.READ, false, null, null);

        int cleared = service.clearLog();
        assertEquals(2, cleared);

        List<Map<String, Object>> log = service.getActionLog(100, null, null, false);
        assertTrue(log.isEmpty());
    }

    // ─── logActionWithUndoData ────────────────────────────────────────────────

    @Test
    void logActionWithUndoData_storesUndoData() {
        service.registerUndoHandler("write_file", (action, data) -> {
            // handler receives the stored undo data
            assertNotNull(data);
            assertEquals("previous_content", data);
            return "restored";
        });

        McpActionLogService.McpAction action = service.logActionWithUndoData(
                "write_file", "filesystem", Map.of("path", "/tmp/test"), "result",
                McpActionLogService.ActionType.WRITE, "previous_content", null, null);

        Map<String, Object> undoResult = service.undoAction(action.getId());
        assertEquals("success", undoResult.get("status"));
    }

    // ─── executeAndLog ────────────────────────────────────────────────────────

    @Test
    void executeAndLog_executesOperationAndLogsIt() {
        String[] executed = {null};
        String result = service.executeAndLog(
                "rag_query", "rag", Map.of("q", "hello"),
                McpActionLogService.ActionType.READ,
                () -> { executed[0] = "ran"; return "query_result"; },
                null);

        assertEquals("query_result", result);
        assertEquals("ran", executed[0]);

        List<Map<String, Object>> log = service.getActionLog(10, "rag_query", null, false);
        assertFalse(log.isEmpty());
    }

    @Test
    void executeAndLog_capturesUndoDataBeforeOperation() {
        service.registerUndoHandler("write_file", (action, data) -> "ok: " + data);

        service.executeAndLog(
                "write_file", "filesystem", Map.of("path", "/tmp/x"),
                McpActionLogService.ActionType.WRITE,
                () -> "written",
                () -> "captured_undo");

        List<Map<String, Object>> log = service.getActionLog(10, "write_file", null, true);
        assertFalse(log.isEmpty());
    }

    // ─── logActionStart / logActionSuccess / logActionFailure ─────────────────

    @Test
    void logActionStart_createsLogEntry() {
        McpActionLogService.McpAction action = service.logActionStart(
                "rag_query", Map.of("q", "test"));

        assertNotNull(action);
        assertEquals("rag_query", action.getToolName());
        assertEquals(McpActionLogService.ActionStatus.STARTED, action.getStatus());
    }

    @Test
    void logActionSuccess_doesNotThrow() {
        McpActionLogService.McpAction action = service.logActionStart("write_file", Map.of());
        assertDoesNotThrow(() -> service.logActionSuccess(action.getId(), "done", Map.of("key", "val")));
        Map<String, Object> found = service.getAction(action.getId());
        assertEquals("SUCCESS", found.get("status"));
        assertEquals("done", found.get("resultSummary"));
    }

    @Test
    void logActionFailure_doesNotThrow() {
        McpActionLogService.McpAction action = service.logActionStart("write_file", Map.of());
        assertDoesNotThrow(() -> service.logActionFailure(action.getId(), "something went wrong"));
        Map<String, Object> found = service.getAction(action.getId());
        assertEquals("FAILURE", found.get("status"));
        assertEquals("something went wrong", found.get("errorMessage"));
        assertEquals(true, found.get("failed"));
    }

    // ─── persistence ─────────────────────────────────────────────────────────

    @Test
    void logAction_persistsAndReloadsFromHistory() {
        McpActionLogService.McpAction action = service.logAction(
                "rag_query", "rag", Map.of("q", "persist"), "result",
                McpActionLogService.ActionType.READ, false, "sess1", "user1");

        Path historyFile = tempHome.resolve(".kompile/conversations/mcp-action-log/actions.jsonl");
        assertTrue(Files.exists(historyFile));

        McpActionLogService reloaded = new McpActionLogService(new ObjectMapper());
        Map<String, Object> found = reloaded.getAction(action.getId());

        assertNotNull(found);
        assertEquals("rag_query", found.get("toolName"));
        assertEquals("SUCCESS", found.get("status"));
        assertEquals("result", found.get("result"));
        assertEquals("sess1", found.get("sessionId"));
        assertEquals("user1", found.get("userId"));
    }

    @Test
    void logActionSuccess_persistsStatusAcrossReload() {
        McpActionLogService.McpAction action = service.logActionStart("write_file", Map.of("path", "/tmp/x"));
        service.logActionSuccess(action.getId(), "wrote file", Map.of("previous", "old"));

        McpActionLogService reloaded = new McpActionLogService(new ObjectMapper());
        Map<String, Object> found = reloaded.getAction(action.getId());

        assertNotNull(found);
        assertEquals("SUCCESS", found.get("status"));
        assertEquals("wrote file", found.get("resultSummary"));
        assertEquals(true, found.get("hasResult"));
        assertEquals(false, found.get("failed"));
    }

    @Test
    void logActionFailure_persistsStatusAcrossReload() {
        McpActionLogService.McpAction action = service.logActionStart("write_file", Map.of("path", "/tmp/x"));
        service.logActionFailure(action.getId(), "denied by policy");

        McpActionLogService reloaded = new McpActionLogService(new ObjectMapper());
        Map<String, Object> found = reloaded.getAction(action.getId());

        assertNotNull(found);
        assertEquals("FAILURE", found.get("status"));
        assertEquals("denied by policy", found.get("errorMessage"));
        assertEquals(true, found.get("failed"));
    }

    @Test
    void undoAction_persistsUndoneStateAcrossReload() {
        service.registerUndoHandler("write_file", (action, data) -> "restored");
        McpActionLogService.McpAction action = service.logAction(
                "write_file", "filesystem", Map.of("path", "/tmp/x"), "done",
                McpActionLogService.ActionType.WRITE, true, null, null);

        service.undoAction(action.getId());

        McpActionLogService reloaded = new McpActionLogService(new ObjectMapper());
        Map<String, Object> found = reloaded.getAction(action.getId());

        assertNotNull(found);
        assertEquals(true, found.get("undone"));
        assertEquals("restored", found.get("undoResult"));
    }

    // ─── McpAction.toMap ──────────────────────────────────────────────────────

    @Test
    void mcpAction_toMap_containsExpectedKeys() {
        McpActionLogService.McpAction action = service.logAction(
                "write_file", "filesystem", Map.of("path", "/tmp/x"), "result",
                McpActionLogService.ActionType.WRITE, false, "sess", "user");

        Map<String, Object> map = action.toMap();
        assertTrue(map.containsKey("id"));
        assertTrue(map.containsKey("toolName"));
        assertTrue(map.containsKey("timestamp"));
        assertTrue(map.containsKey("actionType"));
        assertTrue(map.containsKey("undoable"));
        assertEquals("write_file", map.get("toolName"));
        assertEquals("WRITE", map.get("actionType"));
        assertEquals("sess", map.get("sessionId"));
        assertEquals("user", map.get("userId"));
    }
}
