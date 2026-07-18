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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ReadBatchToolTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper om = new ObjectMapper();
    private final ReadBatchTool tool = new ReadBatchTool();
    private ToolContext context;

    @BeforeEach
    void setUp() {
        PermissionService permissions = new PermissionService();
        permissions.setAutoApproveAll(true);
        context = new ToolContext("read-batch-test-" + System.nanoTime(), null,
                permissions, tempDir, new ToolRegistry(om));
    }

    @Test
    void readsMultipleFilesWithLineNumbers() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "alpha\nbeta\n");
        Files.writeString(tempDir.resolve("b.txt"), "gamma\n");

        ObjectNode params = om.createObjectNode();
        params.putArray("files").add("a.txt").add("b.txt");
        ToolResult result = tool.execute(params, context);

        assertFalse(result.isError(), () -> result.getOutput());
        assertTrue(result.getOutput().contains("2/2 files read"));
        assertTrue(result.getOutput().contains("== a.txt"));
        assertTrue(result.getOutput().contains("== b.txt"));
        assertTrue(result.getOutput().contains("1\talpha"));
        assertTrue(result.getOutput().contains("1\tgamma"));
    }

    @Test
    void satisfiesReadBeforeEditGate() throws Exception {
        Path target = tempDir.resolve("edit-me.txt");
        Files.writeString(target, "original\n");

        ObjectNode readParams = om.createObjectNode();
        readParams.putArray("files").add("edit-me.txt");
        assertFalse(tool.execute(readParams, context).isError());

        ObjectNode editParams = om.createObjectNode();
        editParams.put("file_path", "edit-me.txt");
        editParams.put("old_string", "original");
        editParams.put("new_string", "changed");
        ToolResult edit = new EditTool().execute(editParams, context);

        assertFalse(edit.isError(), () -> "edit after read_batch should pass the gate: " + edit.getOutput());
        assertEquals("changed\n", Files.readString(target));
    }

    @Test
    void objectEntriesSupportOffsetAndLimit() throws Exception {
        Files.writeString(tempDir.resolve("window.txt"), "l1\nl2\nl3\nl4\nl5\n");

        ObjectNode params = om.createObjectNode();
        ArrayNode files = params.putArray("files");
        ObjectNode entry = files.addObject();
        entry.put("file_path", "window.txt");
        entry.put("offset", 2);
        entry.put("limit", 2);
        ToolResult result = tool.execute(params, context);

        assertFalse(result.isError(), () -> result.getOutput());
        assertTrue(result.getOutput().contains("2\tl2"));
        assertTrue(result.getOutput().contains("3\tl3"));
        assertFalse(result.getOutput().contains("4\tl4"));
        assertTrue(result.getOutput().contains("lines 2-3"));
    }

    @Test
    void missingFileReportsInSectionWithoutFailingOthers() throws Exception {
        Files.writeString(tempDir.resolve("present.txt"), "here\n");

        ObjectNode params = om.createObjectNode();
        params.putArray("files").add("present.txt").add("missing.txt");
        ToolResult result = tool.execute(params, context);

        assertFalse(result.isError(), () -> result.getOutput());
        assertTrue(result.getOutput().contains("1/2 files read"));
        assertTrue(result.getOutput().contains("missing.txt — ERROR: file not found"));
        assertTrue(result.getOutput().contains("1\there"));
    }

    @Test
    void allMissingIsAnError() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.putArray("files").add("nope1.txt").add("nope2.txt");
        ToolResult result = tool.execute(params, context);

        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("0/2 files read"));
    }

    @Test
    void emptyFilesArrayIsAnError() throws Exception {
        ObjectNode params = om.createObjectNode();
        params.putArray("files");
        ToolResult result = tool.execute(params, context);

        assertTrue(result.isError());
    }
}
