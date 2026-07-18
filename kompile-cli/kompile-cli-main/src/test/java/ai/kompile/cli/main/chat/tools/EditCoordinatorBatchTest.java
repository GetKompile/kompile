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
import ai.kompile.cli.main.coordination.CoordinationStateManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Batch lock actions (register_edits / release_edits) plus the passive lock
 * conflict surfacing in edit/edit_batch.
 */
class EditCoordinatorBatchTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper om = new ObjectMapper();
    private CoordinationStateManager mine;
    private CoordinationStateManager other;
    private EditCoordinatorTool tool;
    private ToolContext context;

    @BeforeEach
    void setUp() {
        mine = new CoordinationStateManager(tempDir, "session-mine", om);
        other = new CoordinationStateManager(tempDir, "session-other", om);
        tool = new EditCoordinatorTool(mine);
        PermissionService permissions = new PermissionService();
        permissions.setAutoApproveAll(true);
        context = new ToolContext("session-mine", null, permissions, tempDir, new ToolRegistry(om));
    }

    @AfterEach
    void tearDown() {
        mine.shutdown();
        other.shutdown();
    }

    private ObjectNode registerEdits(boolean allowPartial, String... paths) {
        ObjectNode params = om.createObjectNode();
        params.put("action", "register_edits");
        if (allowPartial) params.put("allow_partial", true);
        ArrayNode arr = params.putArray("file_paths");
        for (String p : paths) arr.add(p);
        return params;
    }

    @Test
    void registerEditsAcquiresAllInOneCall() throws Exception {
        ToolResult result = tool.execute(registerEdits(false, "a.txt", "b.txt", "c.txt"), context);

        assertFalse(result.isError(), () -> result.getOutput());
        assertEquals(3, result.getOutput().lines().filter(l -> l.startsWith("acquired")).count());
        assertEquals(3L, ((Number) result.getMetadata().get("acquired")).longValue());
    }

    @Test
    void registerEditsIsAllOrNothingOnConflict() throws Exception {
        String contested = tempDir.resolve("contested.txt").toAbsolutePath().toString();
        assertTrue(other.tryAcquireEditLock(contested, "edit", "other-agent").isAcquired());

        ToolResult result = tool.execute(registerEdits(false, "free.txt", "contested.txt"), context);

        assertFalse(result.isError());
        assertTrue(result.getOutput().contains("CONFLICT"));
        assertTrue(result.getOutput().contains("Batch aborted"));
        // Nothing was locked by this session — both files still lock cleanly from the other side.
        String free = tempDir.resolve("free.txt").toAbsolutePath().toString();
        assertTrue(other.tryAcquireEditLock(free, "edit", "other-agent").isAcquired());
    }

    @Test
    void registerEditsAllowPartialLocksTheFreeFiles() throws Exception {
        String contested = tempDir.resolve("contested.txt").toAbsolutePath().toString();
        assertTrue(other.tryAcquireEditLock(contested, "edit", "other-agent").isAcquired());

        ToolResult result = tool.execute(registerEdits(true, "free.txt", "contested.txt"), context);

        assertFalse(result.isError());
        assertTrue(result.getOutput().contains("acquired"));
        assertTrue(result.getOutput().contains("CONFLICT"));
        assertEquals(1L, ((Number) result.getMetadata().get("acquired")).longValue());
        assertEquals(1L, ((Number) result.getMetadata().get("conflicts")).longValue());
    }

    @Test
    void releaseEditsReleasesEverything() throws Exception {
        ToolResult acquire = tool.execute(registerEdits(false, "x.txt", "y.txt"), context);
        assertFalse(acquire.isError());

        List<String> lockIds = new ArrayList<>();
        acquire.getOutput().lines()
                .filter(l -> l.startsWith("acquired"))
                .forEach(l -> lockIds.add(l.substring(l.indexOf("lock_id ") + "lock_id ".length()).trim()));
        assertEquals(2, lockIds.size());

        ObjectNode params = om.createObjectNode();
        params.put("action", "release_edits");
        ArrayNode arr = params.putArray("lock_ids");
        lockIds.forEach(arr::add);
        ToolResult release = tool.execute(params, context);

        assertFalse(release.isError(), () -> release.getOutput());
        assertTrue(release.getOutput().contains("2/2 locks released"));
        assertTrue(mine.queryEdits().isEmpty());
    }

    @Test
    void editBatchFailsFastOnForeignLock() throws Exception {
        Path locked = tempDir.resolve("locked.txt");
        Files.writeString(locked, "content\n");
        context.recordFileRead(locked);
        assertTrue(other.tryAcquireEditLock(
                locked.toAbsolutePath().toString(), "edit", "other-agent").isAcquired());

        EditBatchTool editBatch = new EditBatchTool(mine);
        ObjectNode params = om.createObjectNode();
        ObjectNode edit = params.putArray("edits").addObject();
        edit.put("file_path", "locked.txt");
        edit.put("old_string", "content");
        edit.put("new_string", "changed");

        ToolResult result = editBatch.execute(params, context);

        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("locked by other-agent"));
        assertEquals("content\n", Files.readString(locked));
    }

    @Test
    void editWarnsOnForeignLockButStillApplies() throws Exception {
        Path locked = tempDir.resolve("warn.txt");
        Files.writeString(locked, "content\n");
        context.recordFileRead(locked);
        assertTrue(other.tryAcquireEditLock(
                locked.toAbsolutePath().toString(), "edit", "other-agent").isAcquired());

        EditTool edit = new EditTool(mine);
        ObjectNode params = om.createObjectNode();
        params.put("file_path", "warn.txt");
        params.put("old_string", "content");
        params.put("new_string", "changed");

        ToolResult result = edit.execute(params, context);

        assertFalse(result.isError(), () -> result.getOutput());
        assertEquals("changed\n", Files.readString(locked));
        assertTrue(result.getOutput().contains("WARNING"));
        assertTrue(result.getOutput().contains("other-agent"));
    }
}
