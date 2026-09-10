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
 *  distributed under the License is distributed on an "AS IS" BASIS,
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MemoryMutationGuardTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private ToolContext context;

    @BeforeEach
    void setUp() {
        PermissionService permissions = new PermissionService();
        permissions.setAutoApproveAll(true);
        context = new ToolContext("memory-guard-" + System.nanoTime(), null,
                permissions, tempDir, new ToolRegistry(mapper));
    }

    @Test
    void everyGenericFileMutationToolRejectsKompileMemory() throws Exception {
        Path memoryFile = tempDir.resolve(".kompile/memory/note.md");
        Files.createDirectories(memoryFile.getParent());
        Files.writeString(memoryFile, "old");
        context.recordFileRead(memoryFile);
        String path = ".kompile/memory/note.md";

        assertMemoryBlocked(() -> new WriteTool().execute(
                mapper.createObjectNode().put("file_path", path).put("content", "new"), context));
        assertMemoryBlocked(() -> new EditTool().execute(
                mapper.createObjectNode().put("file_path", path)
                        .put("old_string", "old").put("new_string", "new"), context));

        ObjectNode batchEdit = mapper.createObjectNode();
        batchEdit.putArray("edits").addObject()
                .put("file_path", path).put("old_string", "old").put("new_string", "new");
        assertMemoryBlocked(() -> new EditBatchTool().execute(batchEdit, context));

        ObjectNode batchPatch = mapper.createObjectNode();
        batchPatch.putArray("patches").addObject()
                .put("file_path", path).put("patch", "@@\n-old\n+new");
        assertMemoryBlocked(() -> new EditPatchTool().execute(batchPatch, context));

        ObjectNode patch = mapper.createObjectNode()
                .put("file_path", path).put("patch", "@@\n-old\n+new");
        assertMemoryBlocked(() -> new PatchTool().execute(patch, context));

        assertEquals("old", Files.readString(memoryFile));
    }

    @Test
    void memoryToolRemainsTheAuthorizedMutationChannel() throws Exception {
        ObjectNode params = mapper.createObjectNode()
                .put("action", "write")
                .put("scope", "project")
                .put("file", "note.md")
                .put("content", "written through memory");

        ToolResult result = new MemoryTool().execute(params, context);

        assertFalse(result.isError(), result::getOutput);
        assertEquals("written through memory",
                Files.readString(tempDir.resolve(".kompile/memory/note.md")));
    }

    @Test
    void ordinaryProjectFilesRemainWritable() throws Exception {
        ToolResult result = new WriteTool().execute(
                mapper.createObjectNode().put("file_path", "notes/output.md")
                        .put("content", "ordinary file"), context);

        assertFalse(result.isError(), result::getOutput);
        assertEquals("ordinary file", Files.readString(tempDir.resolve("notes/output.md")));
    }

    @Test
    void providerMemoryAndExternalKompileMemoryPathsAreBlocked() {
        assertMemoryBlocked(() -> context.resolveMutationPath(".codex/memory/note.md"));
        assertMemoryBlocked(() -> context.resolveMutationPath(
                tempDir.resolve("other-project/.kompile/memory/note.md").toString()));
    }

    @Test
    void symlinkAliasCannotBypassMemoryBoundary() throws Exception {
        Path memoryDir = tempDir.resolve(".kompile/memory");
        Files.createDirectories(memoryDir);
        Path alias = tempDir.resolve("memory-alias");
        try {
            Files.createSymbolicLink(alias, memoryDir);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "symbolic links unavailable: " + e.getMessage());
        }

        assertMemoryBlocked(() -> context.resolveMutationPath("memory-alias/note.md"));
    }

    @Test
    void bashAndBackgroundProcessRejectShellFileWritesBeforeExecution() throws Exception {
        Path bashTarget = tempDir.resolve(".kompile/memory/bash.md");
        ToolResult bashResult = new BashTool().execute(
                mapper.createObjectNode().put("command",
                        "cat <<'EOF' >> .kompile/memory/bash.md\nnote\nEOF"), context);
        assertTrue(bashResult.isError());
        assertTrue(bashResult.getOutput().contains("`memory` tool"), bashResult::getOutput);
        assertFalse(Files.exists(bashTarget));

        try (BackgroundProcessManager manager = new BackgroundProcessManager(
                "memory-process-" + System.nanoTime(), tempDir)) {
            ProcessManagementTool processTool = new ProcessManagementTool(manager);
            ToolResult processResult = processTool.execute(
                    mapper.createObjectNode().put("action", "launch")
                            .put("command", "echo note >> .kompile/memory/process.md"), context);
            assertTrue(processResult.isError());
            assertTrue(manager.listAll().isEmpty(), "blocked command must not launch");
            assertFalse(Files.exists(tempDir.resolve(".kompile/memory/process.md")));
        }
    }

    @Test
    void bashAllowsSystemCommandThatOnlyDiscardsStandardError() throws Exception {
        ToolResult result = new BashTool().execute(
                mapper.createObjectNode().put("command", "printf 'ok\\n' 2>/dev/null"), context);

        assertFalse(result.isError(), result::getOutput);
        assertTrue(result.getOutput().contains("ok"), result::getOutput);
    }

    private void assertMemoryBlocked(ThrowingCall call) {
        ToolExecutionException error = assertThrows(ToolExecutionException.class, call::run);
        assertTrue(error.isPermissionDenied());
        assertTrue(error.getMessage().contains("`memory` MCP tool"), error::getMessage);
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }
}
