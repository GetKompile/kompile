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

package ai.kompile.cli.main.lsp;

import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolExecutionException;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.RenameFile;
import org.eclipse.lsp4j.ResourceOperation;
import org.eclipse.lsp4j.TextDocumentEdit;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceEditApplierTest {

    private ToolContext contextFor(Path workingDir) {
        PermissionService perms = new PermissionService();
        perms.setAutoApproveAll(true);
        return new ToolContext("test", null, perms, workingDir, null);
    }

    private WorkspaceEditApplier applierFor(Path workingDir) {
        return new WorkspaceEditApplier(contextFor(workingDir), null);
    }

    private static TextEdit edit(int line, int startCol, int endCol, String newText) {
        return new TextEdit(new Range(new Position(line, startCol), new Position(line, endCol)), newText);
    }

    private static WorkspaceEdit changes(Map<String, List<TextEdit>> map) {
        WorkspaceEdit edit = new WorkspaceEdit();
        edit.setChanges(new LinkedHashMap<>(map));
        return edit;
    }

    @Test
    void multipleEditsInOneFileApplyInDescendingOrder(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("a.txt");
        Files.writeString(file, "abcdef");
        // Supplied ascending; the applier must sort descending so offsets don't shift.
        WorkspaceEdit edit = changes(Map.of(file.toUri().toString(),
                List.of(edit(0, 0, 1, "X"), edit(0, 3, 4, "Y"))));

        WorkspaceEditApplier.Result result = applierFor(tmp).apply(edit, false);

        assertTrue(result.applied());
        assertEquals("XbcYef", Files.readString(file));
    }

    @Test
    void editsAcrossMultipleFiles(@TempDir Path tmp) throws Exception {
        Path a = tmp.resolve("a.txt");
        Path b = tmp.resolve("b.txt");
        Files.writeString(a, "hello");
        Files.writeString(b, "world");
        Map<String, List<TextEdit>> map = new LinkedHashMap<>();
        map.put(a.toUri().toString(), List.of(edit(0, 0, 5, "HELLO")));
        map.put(b.toUri().toString(), List.of(edit(0, 0, 5, "WORLD")));

        applierFor(tmp).apply(changes(map), false);

        assertEquals("HELLO", Files.readString(a));
        assertEquals("WORLD", Files.readString(b));
    }

    @Test
    void utf16ColumnEditLandsAfterSurrogatePair(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("emoji.txt");
        Files.writeString(file, "a😀bc"); // a, 😀(2 units), b, c
        // Replace 'c' at UTF-16 columns 4..5.
        WorkspaceEdit edit = changes(Map.of(file.toUri().toString(), List.of(edit(0, 4, 5, "Z"))));

        applierFor(tmp).apply(edit, false);

        assertEquals("a😀bZ", Files.readString(file));
    }

    @Test
    void renameFileResourceOperationMovesContent(@TempDir Path tmp) throws Exception {
        Path from = tmp.resolve("old.txt");
        Path to = tmp.resolve("new.txt");
        Files.writeString(from, "payload");

        WorkspaceEdit edit = new WorkspaceEdit();
        List<Either<TextDocumentEdit, ResourceOperation>> ops = new ArrayList<>();
        ops.add(Either.forRight(new RenameFile(from.toUri().toString(), to.toUri().toString())));
        edit.setDocumentChanges(ops);

        WorkspaceEditApplier.Result result = applierFor(tmp).apply(edit, false);

        assertTrue(result.applied());
        assertFalse(Files.exists(from));
        assertTrue(Files.exists(to));
        assertEquals("payload", Files.readString(to));
    }

    @Test
    void dryRunTouchesNothing(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("a.txt");
        Files.writeString(file, "original");
        WorkspaceEdit edit = changes(Map.of(file.toUri().toString(), List.of(edit(0, 0, 8, "changed"))));

        WorkspaceEditApplier.Result result = applierFor(tmp).apply(edit, true);

        assertFalse(result.applied());
        assertTrue(result.dryRun());
        assertTrue(result.output().contains("DRY RUN"));
        assertEquals("original", Files.readString(file), "dry run must not write");
    }

    @Test
    void midApplyFailureRollsBack(@TempDir Path tmp) throws Exception {
        Path good = tmp.resolve("good.txt");
        Files.writeString(good, "AAA");
        // A regular file used as a directory prefix forces createDirectories to throw for the second write.
        Path blocker = tmp.resolve("blocker");
        Files.writeString(blocker, "i am a file");
        Path bad = blocker.resolve("child.txt");

        Map<String, List<TextEdit>> map = new LinkedHashMap<>();
        map.put(good.toUri().toString(), List.of(edit(0, 0, 3, "XXX")));   // applied first
        map.put(bad.toUri().toString(), List.of(edit(0, 0, 0, "new")));    // second write fails

        assertThrows(ToolExecutionException.class, () -> applierFor(tmp).apply(changes(map), false));

        assertEquals("AAA", Files.readString(good), "the first file must be rolled back");
        assertFalse(Files.exists(bad));
    }

    @Test
    void allOrNothingSimulationFailsBeforeWriting(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("a.txt");
        Files.writeString(file, "short");
        // A reversed range (start offset > end offset) is rejected during simulation, disk untouched.
        WorkspaceEdit bad = changes(Map.of(file.toUri().toString(),
                List.of(new TextEdit(new Range(new Position(0, 3), new Position(0, 1)), "oops"))));

        assertThrows(ToolExecutionException.class, () -> applierFor(tmp).apply(bad, false));
        assertEquals("short", Files.readString(file));
    }
}
