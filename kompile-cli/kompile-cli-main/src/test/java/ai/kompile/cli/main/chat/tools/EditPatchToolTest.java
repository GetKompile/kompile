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

class EditPatchToolTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper om = new ObjectMapper();
    private final EditPatchTool tool = new EditPatchTool();
    private ToolContext context;

    @BeforeEach
    void setUp() {
        PermissionService permissions = new PermissionService();
        permissions.setAutoApproveAll(true);
        context = new ToolContext("edit-patch-test-" + System.nanoTime(), null,
                permissions, tempDir, new ToolRegistry(om));
    }

    private Path file(String name, String content) throws Exception {
        Path path = tempDir.resolve(name);
        Files.writeString(path, content);
        context.recordFileRead(path);
        return path;
    }

    private ObjectNode params(ObjectNode... patches) {
        ObjectNode params = om.createObjectNode();
        ArrayNode arr = params.putArray("patches");
        for (ObjectNode p : patches) arr.add(p);
        return params;
    }

    private ObjectNode patch(String file, String patch) {
        ObjectNode p = om.createObjectNode();
        p.put("file_path", file);
        p.put("patch", patch);
        return p;
    }

    @Test
    void appliesV4aStyleHunksToMultipleFiles() throws Exception {
        Path a = file("A.java", "class A {\n    int x = 1;\n}\n");
        Path b = file("B.java", "class B {\n    int y = 2;\n}\n");

        ToolResult result = tool.execute(params(
                patch("A.java", " class A {\n-    int x = 1;\n+    int x = 10;\n }"),
                patch("B.java", " class B {\n-    int y = 2;\n+    int y = 20;\n }")), context);

        assertFalse(result.isError(), () -> result.getOutput());
        assertEquals("class A {\n    int x = 10;\n}\n", Files.readString(a));
        assertEquals("class B {\n    int y = 20;\n}\n", Files.readString(b));
        assertTrue(result.getOutput().contains("2/2 files patched"));
    }

    @Test
    void unifiedDiffLineNumbersAreIgnored() throws Exception {
        Path a = file("wrong-numbers.txt", "alpha\nbeta\ngamma\n");

        // Deliberately wrong @@ numbers — hunks are located by content.
        ToolResult result = tool.execute(params(
                patch("wrong-numbers.txt",
                        "@@ -999,3 +999,3 @@\n alpha\n-beta\n+BETA\n gamma")), context);

        assertFalse(result.isError(), () -> result.getOutput());
        assertEquals("alpha\nBETA\ngamma\n", Files.readString(a));
    }

    @Test
    void unifiedDiffHeadersAreTolerated() throws Exception {
        Path a = file("headers.txt", "one\ntwo\n");

        ToolResult result = tool.execute(params(
                patch("headers.txt",
                        "diff --git a/headers.txt b/headers.txt\n"
                                + "--- a/headers.txt\n"
                                + "+++ b/headers.txt\n"
                                + "@@ -1,2 +1,2 @@\n"
                                + " one\n"
                                + "-two\n"
                                + "+TWO\n"
                                + "\\ No newline at end of file")), context);

        assertFalse(result.isError(), () -> result.getOutput());
        assertEquals("one\nTWO\n", Files.readString(a));
    }

    @Test
    void multipleHunksSeparatedByAtAt() throws Exception {
        Path a = file("multi.txt", "start\nmiddle\nend\n");

        ToolResult result = tool.execute(params(
                patch("multi.txt", "@@\n-start\n+START\n@@\n-end\n+END")), context);

        assertFalse(result.isError(), () -> result.getOutput());
        assertEquals("START\nmiddle\nEND\n", Files.readString(a));
    }

    @Test
    void failingFileLeavesItUnchangedAndOthersApply() throws Exception {
        Path good = file("good.txt", "hello\n");
        Path bad = file("bad.txt", "world\n");

        ToolResult result = tool.execute(params(
                patch("bad.txt", "-no-such-line\n+x"),
                patch("good.txt", "-hello\n+HELLO")), context);

        assertFalse(result.isError());
        assertEquals("world\n", Files.readString(bad));
        assertEquals("HELLO\n", Files.readString(good));
        assertTrue(result.getOutput().contains("FAILED"));
    }

    @Test
    void addFileDirectiveIsRejectedTowardPatchTool() throws Exception {
        file("existing.txt", "content\n");

        ToolResult result = tool.execute(params(
                patch("existing.txt", "*** Add File: new.txt\n+hello")), context);

        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("patch tool"));
    }

    @Test
    void requiresFreshRead() throws Exception {
        Path unread = tempDir.resolve("unread.txt");
        Files.writeString(unread, "alpha\n");

        ToolResult result = tool.execute(params(
                patch("unread.txt", "-alpha\n+beta")), context);

        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("has not been read"));
        assertEquals("alpha\n", Files.readString(unread));
    }

    @Test
    void bareContextLinesWithoutLeadingSpaceAreAccepted() throws Exception {
        Path a = file("bare.txt", "keep\nchange\nkeep2\n");

        // Models often drop the leading space on context lines.
        ToolResult result = tool.execute(params(
                patch("bare.txt", "keep\n-change\n+CHANGED\nkeep2")), context);

        assertFalse(result.isError(), () -> result.getOutput());
        assertEquals("keep\nCHANGED\nkeep2\n", Files.readString(a));
    }

    @Test
    void missingFileReportsCleanly() throws Exception {
        ToolResult result = tool.execute(params(
                patch("nope.txt", "-a\n+b")), context);

        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("file not found"));
    }
}
