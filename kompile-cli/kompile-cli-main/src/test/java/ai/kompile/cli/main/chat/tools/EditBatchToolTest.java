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

class EditBatchToolTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper om = new ObjectMapper();
    private final EditBatchTool tool = new EditBatchTool();
    private ToolContext context;

    @BeforeEach
    void setUp() {
        PermissionService permissions = new PermissionService();
        permissions.setAutoApproveAll(true);
        context = new ToolContext("edit-batch-test-" + System.nanoTime(), null,
                permissions, tempDir, new ToolRegistry(om));
    }

    private Path file(String name, String content) throws Exception {
        Path path = tempDir.resolve(name);
        Files.writeString(path, content);
        context.recordFileRead(path);
        return path;
    }

    private ObjectNode params(ObjectNode... edits) {
        ObjectNode params = om.createObjectNode();
        ArrayNode arr = params.putArray("edits");
        for (ObjectNode e : edits) arr.add(e);
        return params;
    }

    private ObjectNode edit(String file, String oldString, String newString) {
        ObjectNode e = om.createObjectNode();
        e.put("file_path", file);
        e.put("old_string", oldString);
        e.put("new_string", newString);
        return e;
    }

    @Test
    void editsMultipleFilesInOneCall() throws Exception {
        Path a = file("A.java", "class A { int x = 1; }\n");
        Path b = file("B.java", "class B { int y = 2; }\n");

        ToolResult result = tool.execute(params(
                edit("A.java", "int x = 1", "int x = 10"),
                edit("B.java", "int y = 2", "int y = 20")), context);

        assertFalse(result.isError(), () -> result.getOutput());
        assertEquals("class A { int x = 10; }\n", Files.readString(a));
        assertEquals("class B { int y = 20; }\n", Files.readString(b));
        assertTrue(result.getOutput().contains("2/2 files edited"));
    }

    @Test
    void editsToSameFileChainInOrder() throws Exception {
        Path a = file("chain.txt", "one two three\n");

        ToolResult result = tool.execute(params(
                edit("chain.txt", "one two three", "one TWO three"),
                edit("chain.txt", "one TWO three", "ONE TWO THREE")), context);

        assertFalse(result.isError(), () -> result.getOutput());
        assertEquals("ONE TWO THREE\n", Files.readString(a));
    }

    @Test
    void failingFileIsAtomicAndDoesNotStopOthers() throws Exception {
        Path good = file("good.txt", "alpha\n");
        Path bad = file("bad.txt", "beta\n");

        ToolResult result = tool.execute(params(
                edit("bad.txt", "beta", "BETA"),
                edit("bad.txt", "does-not-exist", "x"),
                edit("good.txt", "alpha", "ALPHA")), context);

        // bad.txt: first edit matches but the second fails -> file untouched.
        assertFalse(result.isError());
        assertEquals("beta\n", Files.readString(bad));
        assertEquals("ALPHA\n", Files.readString(good));
        assertTrue(result.getOutput().contains("FAILED"));
        assertTrue(result.getOutput().contains("edit #2"));
    }

    @Test
    void stopOnErrorSkipsRemainingFiles() throws Exception {
        file("first.txt", "aaa\n");
        Path second = file("second.txt", "bbb\n");

        ObjectNode params = params(
                edit("first.txt", "zzz", "x"),
                edit("second.txt", "bbb", "BBB"));
        params.put("stop_on_error", true);

        ToolResult result = tool.execute(params, context);

        assertTrue(result.isError()); // nothing was edited
        assertEquals("bbb\n", Files.readString(second));
        assertTrue(result.getOutput().contains("skipped (stop_on_error)"));
    }

    @Test
    void requiresFreshReadPerFile() throws Exception {
        Path unread = tempDir.resolve("unread.txt");
        Files.writeString(unread, "alpha\n");

        ToolResult result = tool.execute(params(
                edit("unread.txt", "alpha", "beta")), context);

        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("has not been read"));
        assertEquals("alpha\n", Files.readString(unread));
    }

    @Test
    void toleratesTrailingWhitespaceDrift() throws Exception {
        Path a = file("ws.txt", "line one   \nline two\t\nline three\n");

        ToolResult result = tool.execute(params(
                edit("ws.txt", "line one\nline two\nline three", "replaced")), context);

        assertFalse(result.isError(), () -> result.getOutput());
        assertEquals("replaced\n", Files.readString(a));
        assertTrue(result.getOutput().contains("whitespace-tolerant"));
    }

    @Test
    void toleratesCrlfFiles() throws Exception {
        Path a = file("crlf.txt", "first\r\nsecond\r\nthird\r\n");

        ToolResult result = tool.execute(params(
                edit("crlf.txt", "first\nsecond", "FIRST\nSECOND")), context);

        assertFalse(result.isError(), () -> result.getOutput());
        assertTrue(Files.readString(a).contains("FIRST"));
    }

    @Test
    void ambiguousMatchReportsLineNumbers() throws Exception {
        file("dup.txt", "same\nother\nsame\n");

        ToolResult result = tool.execute(params(
                edit("dup.txt", "same", "changed")), context);

        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("2 times"));
        assertTrue(result.getOutput().contains("lines 1, 3"));
    }

    @Test
    void replaceAllReplacesEveryOccurrence() throws Exception {
        Path a = file("all.txt", "x=1; x=1; x=1;\n");

        ObjectNode e = edit("all.txt", "x=1", "x=2");
        e.put("replace_all", true);
        ToolResult result = tool.execute(params(e), context);

        assertFalse(result.isError(), () -> result.getOutput());
        assertEquals("x=2; x=2; x=2;\n", Files.readString(a));
    }

    @Test
    void noMatchDiagnosticNamesNearestCandidateLine() throws Exception {
        file("diag.txt", "public void run() {\n    doWork();\n}\n");

        ToolResult result = tool.execute(params(
                edit("diag.txt", "public void run() {\n    doOtherWork();\n}", "x")), context);

        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("appears at line 1"),
                () -> "diagnostic should locate the anchor line: " + result.getOutput());
    }

    @Test
    void lineNumberPrefixPasteIsCalledOut() throws Exception {
        file("prefix.txt", "alpha\nbeta\n");

        ToolResult result = tool.execute(params(
                edit("prefix.txt", "     1\talpha\n     2\tbeta", "x")), context);

        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("line-number prefixes"),
                () -> "diagnostic should detect pasted read prefixes: " + result.getOutput());
    }
}
