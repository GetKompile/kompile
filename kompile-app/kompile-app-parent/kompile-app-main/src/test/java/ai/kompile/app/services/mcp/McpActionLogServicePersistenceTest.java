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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpActionLogServicePersistenceTest {

    private String originalUserHome;
    private McpActionLogService service;

    @TempDir
    Path tempHome;

    @BeforeEach
    void setUp() {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        service = new McpActionLogService(new ObjectMapper(), new ToolCallWriterService());
    }

    @AfterEach
    void tearDown() {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        } else {
            System.clearProperty("user.home");
        }
    }

    @Test
    void logActionPersistsAndReloadsFromHistory() {
        McpActionLogService.McpAction action = service.logAction(
                "rag_query", "rag", Map.of("q", "persist"), "result",
                McpActionLogService.ActionType.READ, false, "sess1", "user1");

        assertTrue(Files.exists(historyFile()));

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
    void completionStatusPersistsAcrossReload() {
        McpActionLogService.McpAction action = service.logActionStart(
                "write_file", Map.of("path", "/tmp/x"));

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
    void failureStatusPersistsAcrossReload() {
        McpActionLogService.McpAction action = service.logActionStart(
                "write_file", Map.of("path", "/tmp/x"));

        service.logActionFailure(action.getId(), "denied by policy");

        McpActionLogService reloaded = new McpActionLogService(new ObjectMapper());
        Map<String, Object> found = reloaded.getAction(action.getId());

        assertNotNull(found);
        assertEquals("FAILURE", found.get("status"));
        assertEquals("denied by policy", found.get("errorMessage"));
        assertEquals(true, found.get("failed"));
    }

    @Test
    void undoneStatePersistsAcrossReload() {
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

    @Test
    void completedActionsAppendToSharedToolCallHistory() throws Exception {
        McpActionLogService.McpAction action = service.logActionStart(
                "write_file", Map.of("path", "/tmp/x"));

        service.logActionFailure(action.getId(), "denied by policy");

        Path index = tempHome.resolve(".kompile/conversations/tool-calls/all-tool-calls.jsonl");
        assertTrue(Files.exists(index));

        String history = Files.readString(index);
        assertTrue(history.contains("\"toolName\":\"write_file\""));
        assertTrue(history.contains("\"source\":\"mcp\""));
        assertTrue(history.contains("\"agentName\":\"kompile-app\""));
        assertTrue(history.contains("\"isError\":true"));
        assertTrue(history.contains("denied by policy"));
    }

    private Path historyFile() {
        return tempHome.resolve(".kompile/conversations/mcp-action-log/actions.jsonl");
    }
}
