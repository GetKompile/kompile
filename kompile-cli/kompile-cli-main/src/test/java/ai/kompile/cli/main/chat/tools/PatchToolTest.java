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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the real-world failure classes seen in mcp-activity.log: apply_patch
 * ("*** Begin Patch") input that GNU patch cannot parse, miscounted @@ headers,
 * whitespace drift in context lines, escaped-newline payloads, new-file
 * creation diffs, and the stale-read safety gate.
 */
class PatchToolTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper om = new ObjectMapper();
    private final PatchTool tool = new PatchTool();
    private ToolContext context;

    private static final String HELLO = String.join("\n",
            "public class Hello {",
            "    public static void main(String[] args) {",
            "        System.out.println(\"hello\");",
            "    }",
            "",
            "    static int add(int a, int b) {",
            "        return a + b;",
            "    }",
            "}") + "\n";

    private static final String GOOD_DIFF = "--- Hello.java\n"
            + "+++ Hello.java\n"
            + "@@ -1,6 +1,6 @@\n"
            + " public class Hello {\n"
            + "     public static void main(String[] args) {\n"
            + "-        System.out.println(\"hello\");\n"
            + "+        System.out.println(\"hello world\");\n"
            + "     }\n"
            + " \n"
            + "     static int add(int a, int b) {\n";

    @BeforeEach
    void setUp() {
        PermissionService permissions = new PermissionService();
        permissions.setAutoApproveAll(true);
        context = new ToolContext("patch-test-session", null, permissions, tempDir, new ToolRegistry(om));
    }

    // ── apply_patch ("*** Begin Patch") format ─────────────────────────────

    @Test
    void v4aUpdateApplies() throws Exception {
        Path file = writeHello();
        context.recordFileRead(file);

        String patch = "*** Begin Patch\n"
                + "*** Update File: Hello.java\n"
                + "@@\n"
                + "-        return a + b;\n"
                + "+        return a + b + 1;\n"
                + "*** End Patch\n";
        ToolResult result = tool.execute(params(null, patch), context);

        assertFalse(result.isError(), () -> result.getOutput());
        assertTrue(Files.readString(file).contains("return a + b + 1;"));
    }

    @Test
    void v4aUpdateToleratesWhitespaceDrift() throws Exception {
        Path file = writeHello();
        context.recordFileRead(file);

        // Model lost the real indentation in the removed line — content match must rescue it.
        String patch = "*** Begin Patch\n"
                + "*** Update File: Hello.java\n"
                + "@@\n"
                + "-  return a + b;\n"
                + "+        return a + b + 2;\n"
                + "*** End Patch\n";
        ToolResult result = tool.execute(params(null, patch), context);

        assertFalse(result.isError(), () -> result.getOutput());
        assertTrue(Files.readString(file).contains("return a + b + 2;"));
    }

    @Test
    void v4aAddCreatesNestedFile() throws Exception {
        String patch = "*** Begin Patch\n"
                + "*** Add File: sub/dir/fresh.txt\n"
                + "+hello\n"
                + "+world\n"
                + "*** End Patch\n";
        ToolResult result = tool.execute(params(null, patch), context);

        assertFalse(result.isError(), () -> result.getOutput());
        assertEquals("hello\nworld\n", Files.readString(tempDir.resolve("sub/dir/fresh.txt")));
    }

    @Test
    void v4aDeleteRemovesFile() throws Exception {
        Path file = tempDir.resolve("gone.txt");
        Files.writeString(file, "bye\n");
        context.recordFileRead(file);

        String patch = "*** Begin Patch\n"
                + "*** Delete File: gone.txt\n"
                + "*** End Patch\n";
        ToolResult result = tool.execute(params(null, patch), context);

        assertFalse(result.isError(), () -> result.getOutput());
        assertFalse(Files.exists(file));
    }

    @Test
    void v4aUpdateRequiresFreshRead() throws Exception {
        Path file = writeHello();

        String patch = "*** Begin Patch\n"
                + "*** Update File: Hello.java\n"
                + "@@\n"
                + "-        return a + b;\n"
                + "+        return a + b + 1;\n"
                + "*** End Patch\n";
        ToolResult result = tool.execute(params(null, patch), context);

        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("has not been read"));
        assertEquals(HELLO, Files.readString(file));
    }

    @Test
    void v4aParseErrorIsExplained() throws Exception {
        ToolResult result = tool.execute(params(null,
                "*** Begin Patch\n*** Frobnicate File: x\n*** End Patch\n"), context);

        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("Unknown apply_patch directive"));
    }

    @Test
    void v4aUnmatchableHunkLeavesFileUntouched() throws Exception {
        Path file = writeHello();
        context.recordFileRead(file);

        String patch = "*** Begin Patch\n"
                + "*** Update File: Hello.java\n"
                + "@@\n"
                + "-this line does not exist anywhere\n"
                + "+replacement\n"
                + "*** End Patch\n";
        ToolResult result = tool.execute(params(null, patch), context);

        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("Could not locate hunk"));
        assertEquals(HELLO, Files.readString(file));
    }

    // ── unified diff, single-file mode ─────────────────────────────────────

    @Test
    void unifiedDiffStrictApplies() throws Exception {
        assumePatchBinary();
        Path file = writeHello();
        context.recordFileRead(file);

        ToolResult result = tool.execute(params("Hello.java", GOOD_DIFF), context);

        assertFalse(result.isError(), () -> result.getOutput());
        assertTrue(Files.readString(file).contains("hello world"));
    }

    @Test
    void unifiedDiffWrongLineNumbersStillApplies() throws Exception {
        assumePatchBinary();
        Path file = writeHello();
        context.recordFileRead(file);

        ToolResult result = tool.execute(
                params("Hello.java", GOOD_DIFF.replace("@@ -1,6 +1,6 @@", "@@ -4,6 +4,6 @@")), context);

        assertFalse(result.isError(), () -> result.getOutput());
        assertTrue(Files.readString(file).contains("hello world"));
    }

    @Test
    void unifiedDiffMiscountedHunkIsRescued() throws Exception {
        assumePatchBinary();
        Assumptions.assumeTrue(commandAvailable("git"), "git not available");
        Path file = writeHello();
        context.recordFileRead(file);

        // The single most common LLM diff defect: @@ counts that don't match the body.
        ToolResult result = tool.execute(
                params("Hello.java", GOOD_DIFF.replace("@@ -1,6 +1,6 @@", "@@ -1,9 +1,9 @@")), context);

        assertFalse(result.isError(), () -> result.getOutput());
        assertTrue(Files.readString(file).contains("hello world"));
    }

    @Test
    void unifiedDiffIndentDriftIsRescued() throws Exception {
        assumePatchBinary();
        Path file = writeHello();
        context.recordFileRead(file);

        String drifted = "--- Hello.java\n"
                + "+++ Hello.java\n"
                + "@@ -1,6 +1,6 @@\n"
                + " public class Hello {\n"
                + "   public static void main(String[] args) {\n"
                + "-     System.out.println(\"hello\");\n"
                + "+        System.out.println(\"hello world\");\n"
                + "   }\n"
                + " \n"
                + "   static int add(int a, int b) {\n";
        ToolResult result = tool.execute(params("Hello.java", drifted), context);

        assertFalse(result.isError(), () -> result.getOutput());
        assertTrue(Files.readString(file).contains("        System.out.println(\"hello world\");"));
    }

    @Test
    void escapedNewlinePayloadIsNormalized() throws Exception {
        assumePatchBinary();
        Path file = writeHello();
        context.recordFileRead(file);

        ToolResult result = tool.execute(params("Hello.java", GOOD_DIFF.replace("\n", "\\n")), context);

        assertFalse(result.isError(), () -> result.getOutput());
        assertTrue(Files.readString(file).contains("hello world"));
    }

    @Test
    void creationDiffCreatesNewFile() throws Exception {
        assumePatchBinary();
        String diff = "--- /dev/null\n"
                + "+++ b/newdir/fresh.txt\n"
                + "@@ -0,0 +1,2 @@\n"
                + "+alpha\n"
                + "+beta\n";
        ToolResult result = tool.execute(params("newdir/fresh.txt", diff), context);

        assertFalse(result.isError(), () -> result.getOutput());
        assertEquals("alpha\nbeta\n", Files.readString(tempDir.resolve("newdir/fresh.txt")));
    }

    @Test
    void failureNamesStrategiesAndRestoresFile() throws Exception {
        assumePatchBinary();
        Path file = tempDir.resolve("three.txt");
        Files.writeString(file, "line1\nline2\nline3\n");
        context.recordFileRead(file);

        String diff = "--- three.txt\n"
                + "+++ three.txt\n"
                + "@@ -1,3 +1,3 @@\n"
                + " line1\n"
                + "-THIS LINE DOES NOT EXIST\n"
                + "+replacement\n"
                + " line3\n";
        ToolResult result = tool.execute(params("three.txt", diff), context);

        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("Tried:"), () -> result.getOutput());
        assertTrue(result.getOutput().contains("left unchanged"));
        assertEquals("line1\nline2\nline3\n", Files.readString(file));
    }

    @Test
    void unifiedDiffRequiresFreshRead() throws Exception {
        Path file = writeHello();

        ToolResult result = tool.execute(params("Hello.java", GOOD_DIFF), context);

        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("has not been read"));
        assertEquals(HELLO, Files.readString(file));
    }

    // ── unified diff, multi-file header mode ───────────────────────────────

    @Test
    void headerModeAppliesToMultipleFiles() throws Exception {
        assumePatchBinary();
        Path one = tempDir.resolve("one.txt");
        Path two = tempDir.resolve("two.txt");
        Files.writeString(one, "first\n");
        Files.writeString(two, "second\n");
        context.recordFileRead(one);
        context.recordFileRead(two);

        String diff = "--- a/one.txt\n"
                + "+++ b/one.txt\n"
                + "@@ -1,1 +1,1 @@\n"
                + "-first\n"
                + "+FIRST\n"
                + "--- a/two.txt\n"
                + "+++ b/two.txt\n"
                + "@@ -1,1 +1,1 @@\n"
                + "-second\n"
                + "+SECOND\n";
        ToolResult result = tool.execute(params(null, diff), context);

        assertFalse(result.isError(), () -> result.getOutput());
        assertEquals("FIRST\n", Files.readString(one));
        assertEquals("SECOND\n", Files.readString(two));
    }

    // ── normalization unit tests ────────────────────────────────────────────

    @Test
    void normalizeUnescapesSingleLinePayloads() {
        assertEquals("a\nb\n", PatchTool.normalizePatchText("a\\nb"));
        assertEquals("a\nb\n", PatchTool.normalizePatchText("a\r\nb"));
        assertEquals("a\nb\n", PatchTool.normalizePatchText("a\nb\n"));
    }

    @Test
    void normalizeKeepsBlankContextLines() {
        // A trailing blank context line (" \n") must survive — String.trim() used to eat it.
        String diff = "@@ -1,2 +1,2 @@\n-x\n+y\n \n";
        assertEquals(diff, PatchTool.normalizePatchText(diff));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Path writeHello() throws Exception {
        Path file = tempDir.resolve("Hello.java");
        Files.writeString(file, HELLO);
        return file;
    }

    private ObjectNode params(String filePath, String patch) {
        ObjectNode node = om.createObjectNode();
        if (filePath != null) node.put("file_path", filePath);
        node.put("patch", patch);
        return node;
    }

    private void assumePatchBinary() {
        Assumptions.assumeTrue(commandAvailable("patch"), "GNU patch not available");
    }

    private static boolean commandAvailable(String binary) {
        try {
            Process process = new ProcessBuilder(binary, "--version")
                    .redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
