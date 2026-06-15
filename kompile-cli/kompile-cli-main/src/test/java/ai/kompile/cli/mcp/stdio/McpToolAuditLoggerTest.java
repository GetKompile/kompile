/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.mcp.stdio;

import ai.kompile.cli.main.chat.ToolCallIndex;
import ai.kompile.cli.main.chat.ToolCallRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolAuditLoggerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private String originalUserHome;

    @TempDir
    Path tempHome;

    @BeforeEach
    void setUp() throws Exception {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        resetToolCallIndex();
    }

    @AfterEach
    void tearDown() throws Exception {
        System.setProperty("user.home", originalUserHome);
        resetToolCallIndex();
    }

    @Test
    void recordExecutedWritesSearchableToolCallRecord() throws Exception {
        Path projectDir = tempHome.resolve("project");
        McpToolAuditLogger logger = new McpToolAuditLogger(
                "session-1", "kompile-mcp-stdio", "mcp-stdio", projectDir, objectMapper);

        logger.recordExecuted("read", Map.of("file_path", "AGENTS.md"), false, 12);

        ToolCallRecord record = readOnlyRecord();
        assertEquals("session-1", record.getSessionId());
        assertEquals("read", record.getToolName());
        assertEquals("mcp-stdio", record.getSource());
        assertEquals("kompile-mcp-stdio", record.getAgentName());
        assertEquals("filesystem", record.getCategory());
        assertEquals(projectDir.toAbsolutePath().normalize().toString(), record.getProjectDirectory());
        assertFalse(record.isError());
        assertEquals(12, record.getDurationMs());
        assertTrue(record.getToolInput().contains("AGENTS.md"));
    }

    @Test
    void recordDecisionIncludesAuditEnvelopeForBlockedCalls() throws Exception {
        McpToolAuditLogger logger = new McpToolAuditLogger(
                "session-2", "kompile-daemon", "mcp-daemon", tempHome.resolve("project"), objectMapper);

        logger.recordDecision("bash",
                Map.of("cmd", "rm -rf target"),
                null,
                "gateway_blocked",
                "destructive command",
                true,
                0);

        ToolCallRecord record = readOnlyRecord();
        JsonNode input = objectMapper.readTree(record.getToolInput());

        assertEquals("bash", record.getToolName());
        assertEquals("mcp-daemon", record.getSource());
        assertTrue(record.isError());
        assertEquals("rm -rf target", input.path("arguments").path("cmd").asText());
        assertEquals("gateway_blocked", input.path("audit").path("decision").asText());
        assertEquals("destructive command", input.path("audit").path("reason").asText());
    }

    private ToolCallRecord readOnlyRecord() throws Exception {
        Path index = tempHome.resolve(".kompile/conversations/tool-calls/all-tool-calls.jsonl");
        assertTrue(Files.exists(index), "expected combined tool-call index");
        var lines = Files.readAllLines(index);
        assertEquals(1, lines.size());
        return objectMapper.readValue(lines.get(0), ToolCallRecord.class);
    }

    private static void resetToolCallIndex() throws Exception {
        Field instance = ToolCallIndex.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }
}
