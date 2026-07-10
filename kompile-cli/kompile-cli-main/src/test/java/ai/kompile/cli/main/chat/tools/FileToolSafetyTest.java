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

package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.permission.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileToolSafetyTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper om = new ObjectMapper();
    private ToolContext context;

    @BeforeEach
    void setUp() {
        PermissionService permissions = new PermissionService();
        permissions.setAutoApproveAll(true);
        context = new ToolContext("test-session", null, permissions, tempDir, new ToolRegistry(om));
    }

    @Test
    void editRequiresFreshRead() throws Exception {
        Path file = tempDir.resolve("sample.txt");
        Files.writeString(file, "alpha\n");

        ToolResult result = new EditTool().execute(editParams("sample.txt", "alpha", "beta"), context);

        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("has not been read"));
        assertEquals("alpha\n", Files.readString(file));
    }

    /**
     * The MCP servers construct a fresh ToolContext per call (stdio: per pool thread;
     * socket daemon: per tool call). A read must therefore stay visible to an edit that
     * runs through a DIFFERENT context instance with the same session id — this was the
     * "has not been read in this tool session" regression that rejected every MCP edit.
     */
    @Test
    void readSurvivesAcrossContextInstancesWithinSameSession() throws Exception {
        Path file = tempDir.resolve("cross-context.txt");
        Files.writeString(file, "alpha\n");

        ToolResult read = new ReadTool().execute(readParams("cross-context.txt"), context);
        assertFalse(read.isError());

        ToolContext freshContext = new ToolContext("test-session", null,
                context.getPermissionService(), tempDir, new ToolRegistry(om));
        ToolResult edit = new EditTool().execute(editParams("cross-context.txt", "alpha", "beta"), freshContext);
        assertFalse(edit.isError(), () -> "edit through a fresh same-session context failed: " + edit.getOutput());
        assertEquals("beta\n", Files.readString(file));
    }

    @Test
    void readDoesNotLeakAcrossSessions() throws Exception {
        Path file = tempDir.resolve("cross-session.txt");
        Files.writeString(file, "alpha\n");

        ToolResult read = new ReadTool().execute(readParams("cross-session.txt"), context);
        assertFalse(read.isError());

        ToolContext otherSession = new ToolContext("another-session", null,
                context.getPermissionService(), tempDir, new ToolRegistry(om));
        ToolResult edit = new EditTool().execute(editParams("cross-session.txt", "alpha", "beta"), otherSession);
        assertTrue(edit.isError());
        assertTrue(edit.getOutput().contains("has not been read"));
        assertEquals("alpha\n", Files.readString(file));
    }

    @Test
    void writeAllowsNewFileButRequiresFreshReadForOverwrite() throws Exception {
        WriteTool write = new WriteTool();

        ToolResult create = write.execute(writeParams("created.txt", "first\n"), context);
        assertFalse(create.isError());
        assertEquals("first\n", Files.readString(tempDir.resolve("created.txt")));

        Path existing = tempDir.resolve("existing.txt");
        Files.writeString(existing, "old\n");
        ToolResult overwrite = write.execute(writeParams("existing.txt", "new\n"), context);

        assertTrue(overwrite.isError());
        assertTrue(overwrite.getOutput().contains("has not been read"));
        assertEquals("old\n", Files.readString(existing));
    }

    @Test
    void editSucceedsAfterFreshReadAndRejectsStaleRead() throws Exception {
        Path file = tempDir.resolve("sample.txt");
        Files.writeString(file, "alpha\n");

        ToolResult read = new ReadTool().execute(readParams("sample.txt"), context);
        assertFalse(read.isError());

        ToolResult edit = new EditTool().execute(editParams("sample.txt", "alpha", "beta"), context);
        assertFalse(edit.isError());
        assertEquals("beta\n", Files.readString(file));

        Files.writeString(file, "external\n");
        ToolResult stale = new EditTool().execute(editParams("sample.txt", "external", "agent"), context);
        assertTrue(stale.isError());
        assertTrue(stale.getOutput().contains("changed since it was read"));
        assertEquals("external\n", Files.readString(file));
    }

    private ObjectNode readParams(String filePath) {
        ObjectNode params = om.createObjectNode();
        params.put("file_path", filePath);
        return params;
    }

    private ObjectNode editParams(String filePath, String oldString, String newString) {
        ObjectNode params = om.createObjectNode();
        params.put("file_path", filePath);
        params.put("old_string", oldString);
        params.put("new_string", newString);
        return params;
    }

    private ObjectNode writeParams(String filePath, String content) {
        ObjectNode params = om.createObjectNode();
        params.put("file_path", filePath);
        params.put("content", content);
        return params;
    }
}
