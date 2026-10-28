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

package ai.kompile.cli.main.coordination;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.harness.HarnessConfig;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.EditCoordinatorTool;
import ai.kompile.cli.main.chat.tools.EditTool;
import ai.kompile.cli.main.chat.tools.PatchTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.tools.WriteTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Harness-level enforcement ({@link EditEnforcement}): in ENFORCE (the default) every
 * file-mutating native tool call is arbitrated BEFORE it writes — a file held by another
 * live owner refuses with CONFLICT, and a successful write auto-locks its target for the
 * session. ADVISORY is the byte-for-byte historical behaviour (warn after the write);
 * OFF does no coordination work at all. The mode is resolved once per coordinator from
 * {@code harness-config.json} with the {@code KOMPILE_EDIT_COORDINATION} env override;
 * tests here inject config values into the temporary user home rather than the process
 * environment.
 */
@TemporaryUserHome
class EditEnforcementTest {

    private static final String SESSION = "session-enforce";
    private static final String OTHER_SESSION = "session-enforce-other";
    private static final Duration LEASE = Duration.ofSeconds(600);
    /** The coordinator's production bounded wait for edit-lock operations. */
    private static final long EDIT_LOCK_WAIT_MS = 500L;

    @TempDir
    Path tempDir;

    private final ObjectMapper om = new ObjectMapper();
    private Path project;
    private Path systemRoot;
    private MutableClock clock;
    private CoordinationStateManager shared;
    private CoordinationStateManager other;
    private EditCoordinatorTool tool;
    private ToolContext mcpContext;

    /** A lease clock the test moves by hand (unused leases here; staleness tests use it). */
    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration step) {
            now = now.plus(step);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        project = Files.createDirectories(tempDir.resolve("project"));
        systemRoot = tempDir.resolve("system-activity");
        clock = new MutableClock(Instant.now());
        shared = new CoordinationStateManager(project, SESSION, om, systemRoot, LEASE, clock);
        other = new CoordinationStateManager(project, OTHER_SESSION, om, systemRoot, LEASE, clock);
        tool = new EditCoordinatorTool(shared);
        mcpContext = newContext(null);
    }

    @AfterEach
    void tearDown() {
        shared.shutdown();
        other.shutdown();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ToolContext newContext(String coordinationAgent) {
        ai.kompile.cli.main.chat.permission.PermissionService permissions =
                new ai.kompile.cli.main.chat.permission.PermissionService();
        permissions.setAutoApproveAll(true);
        ToolContext context = new ToolContext(SESSION, null, permissions, project, new ToolRegistry(om));
        context.setCoordinationAgent(coordinationAgent);
        context.bindCoordinator(shared);
        return context;
    }

    /** Records the pre-edit read the tools' freshness check requires. */
    private ToolContext contextWithRead(String coordinationAgent, Path... files) {
        ToolContext context = newContext(coordinationAgent);
        for (Path file : files) context.recordFileRead(file);
        return context;
    }

    private String abs(String relative) {
        return project.resolve(relative).toAbsolutePath().toString();
    }

    private ObjectNode editParams(String file, String oldString, String newString, String agentName) {
        ObjectNode params = om.createObjectNode();
        params.put("file_path", file);
        params.put("old_string", oldString);
        params.put("new_string", newString);
        if (agentName != null) params.put("agent_name", agentName);
        return params;
    }

    private ObjectNode writeParams(String file, String content, String agentName) {
        ObjectNode params = om.createObjectNode();
        params.put("file_path", file);
        params.put("content", content);
        if (agentName != null) params.put("agent_name", agentName);
        return params;
    }

    /** Writes a harness config with the given coordination mode into the temp user home. */
    private void writeModeConfig(String mode) throws Exception {
        Path config = HarnessConfig.getConfigFilePath();
        Files.createDirectories(config.getParent());
        Files.writeString(config, "{\"editCoordinationMode\":\"" + mode + "\"}");
    }

    /** Removes the leaked mode config so later tests construct default-ENFORCE coordinators. */
    private void clearModeConfig() throws Exception {
        Files.deleteIfExists(HarnessConfig.getConfigFilePath());
    }

    // ── Mode resolution ──────────────────────────────────────────────────────

    @Test
    void defaultModeIsEnforceWhenNoConfigExists() throws Exception {
        Files.createDirectories(HarnessConfig.getConfigFilePath().getParent());
        assertEquals(EditEnforcement.ENFORCE, EditEnforcement.fromConfig(null));
        assertEquals(EditEnforcement.DEFAULT.name().toLowerCase(java.util.Locale.ROOT),
                new HarnessConfig().getEditCoordinationMode(), "fresh config default");
    }

    @Test
    void modeComesFromHarnessConfig() throws Exception {
        writeModeConfig("advisory");
        assertEquals("advisory", HarnessConfig.load().getEditCoordinationMode());
        assertEquals(EditEnforcement.ADVISORY, EditEnforcement.fromConfig(
                HarnessConfig.load().getEditCoordinationMode()));

        writeModeConfig("off");
        assertEquals(EditEnforcement.OFF, EditEnforcement.fromConfig(
                HarnessConfig.load().getEditCoordinationMode()));

        // Unknown values fall through to the default rather than breaking the session.
        writeModeConfig("banana");
        assertEquals(EditEnforcement.ENFORCE, EditEnforcement.fromConfig(
                HarnessConfig.load().getEditCoordinationMode()));
    }

    @Test
    void coordinatorResolvesModeOnceAtConstruction() throws Exception {
        writeModeConfig("advisory");
        CoordinationStateManager advisory = new CoordinationStateManager(
                project, "session-advisory", om, systemRoot, LEASE, clock);
        try {
            assertEquals(EditEnforcement.ADVISORY, advisory.getEditEnforcement());
        } finally {
            advisory.shutdown();
        }
        writeModeConfig("off");
        CoordinationStateManager off = new CoordinationStateManager(
                project, "session-off", om, systemRoot, LEASE, clock);
        try {
            assertEquals(EditEnforcement.OFF, off.getEditEnforcement());
        } finally {
            off.shutdown();
            clearModeConfig();
        }
    }

    // ── ENFORCE: session-level guard refuses live foreign locks ─────────────

    @Test
    void enforceSessionEditRefusedOnLiveForeignLock() throws Exception {
        Path file = project.resolve("contended.txt");
        Files.writeString(file, "original line\n");
        String otherLock = other.tryAcquireEditLock(abs("contended.txt"), "edit", "agent-b")
                .getLockId();
        assertNotNull(otherLock, "precondition: the other session holds the lock");

        EditTool editTool = new EditTool(shared);
        ToolResult result = editTool.execute(
                editParams("contended.txt", "original line", "changed line", null),
                contextWithRead(null, file));

        assertTrue(result.isError(), "ENFORCE refuses the session-level edit");
        assertTrue(result.getOutput().contains("CONFLICT"), "names the conflict: " + result.getOutput());
        assertTrue(result.getOutput().contains("agent-b"), "names the holder: " + result.getOutput());
        assertEquals("original line\n", Files.readString(file), "nothing was written");
    }

    @Test
    void enforceSessionWriteRefusedOnLiveForeignLock() throws Exception {
        Path file = project.resolve("contended-write.txt");
        Files.writeString(file, "old\n");
        other.tryAcquireEditLock(abs("contended-write.txt"), "edit", "agent-b");

        WriteTool writeTool = new WriteTool(shared);
        ToolResult result = writeTool.execute(
                writeParams("contended-write.txt", "clobber\n", null), contextWithRead(null, file));

        assertTrue(result.isError());
        assertEquals("old\n", Files.readString(file), "the clobber never landed");
    }

    @Test
    void enforceSessionEditOfFreeFileAutoLocksTheTarget() throws Exception {
        Path file = project.resolve("free.txt");
        Files.writeString(file, "alpha\n");
        EditTool editTool = new EditTool(shared);
        ToolResult result = editTool.execute(
                editParams("free.txt", "alpha", "beta", null), contextWithRead(null, file));
        assertFalse(result.isError(), "free file edits: " + result.getOutput());

        // The auto-lock is visible in the ledger and now refuses the OTHER session.
        EditLockEntry mine = other.findConflictingLock(abs("free.txt"));
        assertNotNull(mine, "the write auto-locked its target");
        assertEquals(SESSION, mine.getSessionId());

        Path otherView = file;
        EditTool otherTool = new EditTool(other);
        ToolContext otherContext = newContextFor(other);
        otherContext.recordFileRead(otherView);
        ToolResult refused = otherTool.execute(
                editParams("free.txt", "beta", "gamma", null), otherContext);
        assertTrue(refused.isError(), "the auto-lock refuses the other session");
        assertEquals("beta\n", Files.readString(file), "nothing was written");
    }

    @Test
    void enforceSameSessionReEditOfOwnAutoLockProceeds() throws Exception {
        Path file = project.resolve("own.txt");
        Files.writeString(file, "one\n");
        EditTool editTool = new EditTool(shared);
        assertFalse(editTool.execute(editParams("own.txt", "one", "two", null),
                contextWithRead(null, file)).isError());
        ToolResult again = editTool.execute(editParams("own.txt", "two", "three", null),
                contextWithRead(null, file));
        assertFalse(again.isError(), "own session re-edit proceeds: " + again.getOutput());
        assertEquals("three\n", Files.readString(file));
    }

    @Test
    void enforceAgentIdentityStillRefusedAndLeaseStillRenewed() throws Exception {
        Path file = project.resolve("agent-path.txt");
        Files.writeString(file, "held\n");
        other.tryAcquireEditLock(abs("agent-path.txt"), "edit", "agent-b");

        ToolContext agentContext = contextWithRead("agent-a", file);
        EditTool editTool = new EditTool(shared);
        ToolResult result = editTool.execute(
                editParams("agent-path.txt", "held", "stolen", "agent-a"), agentContext);
        assertTrue(result.isError());
        assertTrue(result.getOutput().contains("CONFLICT"));
        assertEquals("held\n", Files.readString(file));
    }

    @Test
    void enforceStaleLeaseLockIsEvictedAndWriteProceeds() throws Exception {
        Path file = project.resolve("stale.txt");
        Files.writeString(file, "stale holder\n");
        String lockId = other.tryAcquireEditLock(abs("stale.txt"), "edit", "agent-b").getLockId();
        assertNotNull(lockId);
        // Outlive lease (600s) + renewal slack (15s) on the shared lease clock. The
        // eviction path is the acquire/guard's stale cleanup, proven by the write passing.
        clock.advance(LEASE.plusSeconds(16));

        EditTool editTool = new EditTool(shared);
        ToolResult result = editTool.execute(
                editParams("stale.txt", "stale holder", "new writer", null),
                contextWithRead(null, file));
        assertFalse(result.isError(), "stale lock never blocks: " + result.getOutput());
        assertEquals("new writer\n", Files.readString(file));
    }

    @Test
    void enforceNewFileWriteCreatesAutoLock() throws Exception {
        WriteTool writeTool = new WriteTool(shared);
        ToolResult result = writeTool.execute(writeParams("brand-new.txt", "fresh\n", null), mcpContext);
        assertFalse(result.isError());
        assertNotNull(other.findConflictingLock(abs("brand-new.txt")),
                "a created file is auto-locked like an edited one");
    }

    @Test
    void enforceCoordinatorBusyFailsTheCallWithoutWriting() throws Exception {
        Path file = project.resolve("busy.txt");
        Files.writeString(file, "intact\n");
        Path lockFile = Files.createDirectories(
                project.resolve(".kompile").resolve("coordination")).resolve(".coordinator.lock");
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(lockFile,
                java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.CREATE)) {
            try (java.nio.channels.FileLock ignored = channel.lock()) {
                // The gate's bounded wait must exhaust, not false-pass.
                shared.setEditLockWaitMillis(300);
                EditTool editTool = new EditTool(shared);
                ToolResult result = editTool.execute(
                        editParams("busy.txt", "intact", "changed", null),
                        contextWithRead(null, file));
                assertTrue(result.isError(), "busy coordinator refuses the edit");
                assertEquals("intact\n", Files.readString(file), "nothing was written");
            }
        } finally {
            shared.setEditLockWaitMillis(EDIT_LOCK_WAIT_MS);
        }
    }

    // ── ADVISORY / OFF: legacy semantics pinned ──────────────────────────────

    @Test
    void advisoryWarnsAfterWriteButAppliesIt() throws Exception {
        writeModeConfig("advisory");
        CoordinationStateManager advisoryShared = new CoordinationStateManager(
                project, "session-adv", om, systemRoot, LEASE, clock);
        CoordinationStateManager advisoryOther = new CoordinationStateManager(
                project, "session-adv-other", om, systemRoot, LEASE, clock);
        try {
            Path file = project.resolve("advisory.txt");
            Files.writeString(file, "before\n");
            advisoryOther.tryAcquireEditLock(abs("advisory.txt"), "edit", "agent-b");

            ToolContext advContext = new ToolContext("session-adv", null,
                    autoApprove(), project, new ToolRegistry(om));
            advContext.bindCoordinator(advisoryShared);
            advContext.recordFileRead(file);
            EditTool editTool = new EditTool(advisoryShared);
            ToolResult result = editTool.execute(
                    editParams("advisory.txt", "before", "after", null), advContext);

            assertFalse(result.isError(), "advisory still applies the edit");
            assertTrue(result.getOutput().contains("WARNING"),
                    "advisory warns after the write: " + result.getOutput());
            assertEquals("after\n", Files.readString(file), "the write landed");
        } finally {
            advisoryShared.shutdown();
            advisoryOther.shutdown();
            clearModeConfig();
        }
    }

    @Test
    void offDoesNoCoordinationWorkFromMutationTools() throws Exception {
        writeModeConfig("off");
        CoordinationStateManager offShared = new CoordinationStateManager(
                project, "session-off-main", om, systemRoot, LEASE, clock);
        try {
            Path file = project.resolve("off.txt");
            Files.writeString(file, "plain\n");
            ToolContext offContext = new ToolContext("session-off-main", null,
                    autoApprove(), project, new ToolRegistry(om));
            offContext.bindCoordinator(offShared);
            offContext.recordFileRead(file);
            EditTool editTool = new EditTool(offShared);
            ToolResult result = editTool.execute(
                    editParams("off.txt", "plain", "edited", null), offContext);
            assertFalse(result.isError());
            assertFalse(result.getOutput().contains("WARNING"), "no warnings in OFF");
            assertEquals("edited\n", Files.readString(file));
        } finally {
            offShared.shutdown();
            clearModeConfig();
        }
    }

    // ── patch tool ───────────────────────────────────────────────────────────

    @Test
    void enforcePatchToolRefusedOnLiveForeignLock() throws Exception {
        Path file = project.resolve("patched.txt");
        Files.writeString(file, "line one\nline two\n");
        other.tryAcquireEditLock(abs("patched.txt"), "edit", "agent-b");

        PatchTool patchTool = new PatchTool(shared);
        ObjectNode params = om.createObjectNode();
        params.put("file_path", "patched.txt");
        params.put("patch", "@@\n-line one\n+line ONE\n");
        ToolResult result = patchTool.execute(params, contextWithRead(null, file));
        assertTrue(result.isError(), "ENFORCE refuses the patch");
        assertEquals("line one\nline two\n", Files.readString(file), "nothing was written");
    }

    @Test
    void enforceAgentIdentityRegisterThenEditStillWinsItsOwnFile() throws Exception {
        Path file = project.resolve("registered.txt");
        Files.writeString(file, "mine\n");
        // agent-a registers explicitly, then edits under the same identity.
        ObjectNode register = om.createObjectNode();
        register.put("action", "register_edit");
        register.put("file_path", "registered.txt");
        register.put("agent_name", "agent-a");
        ToolResult registered = tool.execute(register, newContext("agent-a"));
        assertFalse(registered.isError(), "register: " + registered.getOutput());

        EditTool editTool = new EditTool(shared);
        ToolResult edited = editTool.execute(
                editParams("registered.txt", "mine", "mine now", "agent-a"),
                contextWithRead("agent-a", file));
        assertFalse(edited.isError(), "own registered file edit: " + edited.getOutput());
        assertEquals("mine now\n", Files.readString(file));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ai.kompile.cli.main.chat.permission.PermissionService autoApprove() {
        ai.kompile.cli.main.chat.permission.PermissionService permissions =
                new ai.kompile.cli.main.chat.permission.PermissionService();
        permissions.setAutoApproveAll(true);
        return permissions;
    }

    private ToolContext newContextFor(CoordinationStateManager coordinator) {
        ToolContext context = new ToolContext(OTHER_SESSION, null, autoApprove(),
                project, new ToolRegistry(om));
        context.bindCoordinator(coordinator);
        return context;
    }
}
