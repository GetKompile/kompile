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
 *  limitations under the License.
 */

package ai.kompile.cli.main.coordination;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.EditCoordinatorTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * edit_coordinator monitor_edit / unmonitor_edit / query_edit_monitors: an agent blocked on
 * another session's lock registers a one-shot monitor and is woken when the lock leaves the
 * active ledger — by explicit release, or by eviction after the holder session ends.
 */
@TemporaryUserHome
class EditLockMonitorToolTest {

    private static final String SESSION = "session-monitor";
    private static final String OTHER_SESSION = "session-holder";

    @TempDir
    Path tempDir;

    private final ObjectMapper om = JsonUtils.standardMapper();
    private Path project;
    private CoordinationStateManager shared;
    private CoordinationStateManager holder;
    private EditCoordinatorTool tool;
    private EditCoordinatorTool holderTool;
    private ToolContext mcpContext;
    private ToolContext holderContext;

    @BeforeEach
    void setUp() throws Exception {
        project = Files.createDirectories(tempDir.resolve("project"));
        Path systemRoot = tempDir.resolve("system-activity");
        shared = new CoordinationStateManager(project, SESSION, om, systemRoot);
        holder = new CoordinationStateManager(project, OTHER_SESSION, om, systemRoot);
        tool = new EditCoordinatorTool(shared);
        holderTool = new EditCoordinatorTool(holder);
        mcpContext = newContext(SESSION);
        holderContext = newContext(OTHER_SESSION);
    }

    @AfterEach
    void tearDown() {
        shared.shutdown();
        holder.shutdown();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ToolContext newContext(String sessionId) {
        PermissionService permissions = new PermissionService();
        permissions.setAutoApproveAll(true);
        return new ToolContext(sessionId, null, permissions, project, new ToolRegistry(om));
    }

    private ObjectNode params(String action) {
        ObjectNode node = om.createObjectNode();
        node.put("action", action);
        return node;
    }

    private ObjectNode monitorParams(String relative, String lockId) {
        ObjectNode node = params("monitor_edit");
        node.put("file_path", relative);
        if (lockId != null) node.put("lock_id", lockId);
        node.put("monitor_message", "retry registering the lock");
        return node;
    }

    /** The holder session registers its lock through its own tool view. */
    private String holderLocks(String relative, String agentName) throws Exception {
        ObjectNode node = params("register_edit");
        node.put("file_path", project.resolve(relative).toAbsolutePath().toString());
        if (agentName != null) node.put("agent_name", agentName);
        ToolResult result = holderTool.execute(node, holderContext);
        assertFalse(result.isError(), result::getOutput);
        Object lockId = result.getMetadata().get("lockId");
        assertNotNull(lockId);
        return lockId.toString();
    }

    private void holderReleases(String lockId) throws Exception {
        ObjectNode release = params("release_edit");
        release.put("lock_id", lockId);
        release.put("agent_name", "holder-agent");
        ToolResult result = holderTool.execute(release, holderContext);
        assertFalse(result.isError(), result::getOutput);
    }

    /** Monitors fire on the registry's daemon tick — no manual checkNow, no tool polling. */
    private void assertMonitorReaches(String monitorId,
                                      EditLockMonitorRegistry.State expected) throws Exception {
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (shared.editLockMonitors().get(SESSION, monitorId).state() == expected) return;
            Thread.sleep(200);
        }
        assertEquals(expected, shared.editLockMonitors().get(SESSION, monitorId).state(),
                "monitor did not reach " + expected + " within 15s");
    }

    private String writeFile(String relative, String content) throws Exception {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return holderLocks(relative, "holder-agent");
    }

    // ── Monitor lifecycle ────────────────────────────────────────────────────

    @Test
    void monitorEditWakesWhenHolderReleases() throws Exception {
        String lockId = writeFile("src/A.java", "class A {}\n");

        ToolResult registered = tool.execute(monitorParams("src/A.java", lockId), mcpContext);
        assertFalse(registered.isError(), registered::getOutput);
        assertEquals("WAITING", registered.getMetadata().get("state"));
        String monitorId = registered.getMetadata().get("editMonitorId").toString();

        ToolResult listed = tool.execute(params("query_edit_monitors"), mcpContext);
        assertFalse(listed.isError(), listed::getOutput);
        assertTrue(listed.getOutput().contains(monitorId));
        assertTrue(listed.getOutput().contains("WAITING"));

        holderReleases(lockId);
        assertMonitorReaches(monitorId, EditLockMonitorRegistry.State.RELEASED);
    }

    @Test
    void monitorEditFiresWhenHolderSessionEnds() throws Exception {
        String lockId = writeFile("src/B.java", "class B {}\n");
        ToolResult registered = tool.execute(monitorParams("src/B.java", null), mcpContext);
        assertFalse(registered.isError(), registered::getOutput);
        assertEquals("WAITING", registered.getMetadata().get("state"));
        String monitorId = registered.getMetadata().get("editMonitorId").toString();

        // The holder goes away entirely; deregistration evicts its locks on the next read.
        holder.shutdown();

        assertMonitorReaches(monitorId, EditLockMonitorRegistry.State.RELEASED);
        assertTrue(shared.editLockMonitors().get(SESSION, monitorId).notification()
                .contains("src/B.java"));
    }

    @Test
    void monitorEditWithoutAnyHeldLockReportsReleasedImmediately() throws Exception {
        ToolResult result = tool.execute(monitorParams("src/free.java", null), mcpContext);
        assertFalse(result.isError(), result::getOutput);
        assertEquals("RELEASED", result.getMetadata().get("state"));
    }

    @Test
    void monitorEditRequiresATarget() throws Exception {
        ToolResult result = tool.execute(params("monitor_edit"), mcpContext);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("lock_id or file_path is required"));
    }

    @Test
    void duplicateMonitorForTheSameFileIsIdempotent() throws Exception {
        writeFile("src/C.java", "class C {}\n");
        ToolResult first = tool.execute(monitorParams("src/C.java", null), mcpContext);
        ToolResult second = tool.execute(monitorParams("src/C.java", null), mcpContext);
        assertFalse(first.isError(), first::getOutput);
        assertFalse(second.isError(), second::getOutput);
        assertEquals(first.getMetadata().get("editMonitorId"),
                second.getMetadata().get("editMonitorId"));
    }

    @Test
    void unmonitorEditCancelsASessionMonitor() throws Exception {
        writeFile("src/D.java", "class D {}\n");
        String monitorId = tool.execute(monitorParams("src/D.java", null), mcpContext)
                .getMetadata().get("editMonitorId").toString();

        ObjectNode cancel = params("unmonitor_edit");
        cancel.put("monitor_id", monitorId);
        ToolResult cancelled = tool.execute(cancel, mcpContext);
        assertFalse(cancelled.isError(), cancelled::getOutput);
        assertEquals(EditLockMonitorRegistry.State.CANCELLED,
                shared.editLockMonitors().get(SESSION, monitorId).state());
    }

    @Test
    void unmonitorEditRejectsUnknownAndForeignMonitorIds() throws Exception {
        writeFile("src/D.java", "class D {}\n");
        ToolResult unknown = tool.execute(
                params("unmonitor_edit").put("monitor_id", "no-such-id"), mcpContext);
        assertTrue(unknown.isError());
        assertTrue(unknown.getOutput().contains("No edit-lock monitor"));

        // Same registry, a DIFFERENT tool session: this session must not cancel it.
        ToolResult theirs = tool.execute(monitorParams("src/D.java", null),
                newContext("session-third"));
        assertEquals("WAITING", theirs.getMetadata().get("state"), theirs::getOutput);
        String foreignId = theirs.getMetadata().get("editMonitorId").toString();
        ToolResult attack = tool.execute(
                params("unmonitor_edit").put("monitor_id", foreignId), mcpContext);
        assertTrue(attack.isError(), "must not cancel another session's monitor");
        assertEquals(EditLockMonitorRegistry.State.WAITING,
                shared.editLockMonitors().get("session-third", foreignId).state());
    }

    @Test
    void queryEditMonitorsIsEmptyWithoutMonitors() throws Exception {
        ToolResult result = tool.execute(params("query_edit_monitors"), mcpContext);
        assertFalse(result.isError(), result::getOutput);
        assertTrue(result.getOutput().contains("No active edit-lock monitors"));
    }

    // ── Wake-up delivery plumbing ────────────────────────────────────────────

    @Test
    void registryWakeHandlerReceivesOneReleaseNotification() throws Exception {
        String lockId = writeFile("src/E.java", "class E {}\n");

        List<String> received = new CopyOnWriteArrayList<>();
        shared.editLockMonitors().setWakeHandler(SESSION, received::add);
        ToolResult registered = tool.execute(monitorParams("src/E.java", lockId), mcpContext);
        String monitorId = registered.getMetadata().get("editMonitorId").toString();

        holderReleases(lockId);
        assertMonitorReaches(monitorId, EditLockMonitorRegistry.State.RELEASED);
        Thread.sleep(500); // allow a second tick to prove the monitor is one-shot
        assertEquals(1, received.size(), "exactly one wake-up: " + received);
        assertTrue(received.get(0).contains("[System edit lock release]"));
        assertTrue(received.get(0).contains("src/E.java"));
    }

    @Test
    void sessionWithoutWakeHandlerStillReachesTerminalState() throws Exception {
        String lockId = writeFile("src/F.java", "class F {}\n");
        ToolResult registered = tool.execute(monitorParams("src/F.java", lockId), mcpContext);
        String monitorId = registered.getMetadata().get("editMonitorId").toString();
        holderReleases(lockId);
        assertMonitorReaches(monitorId, EditLockMonitorRegistry.State.RELEASED);
        // A snapshot without a handler still carries actionable guidance for the caller.
        assertTrue(shared.editLockMonitors().get(SESSION, monitorId).notification()
                .contains("query_edits"));
    }
}
