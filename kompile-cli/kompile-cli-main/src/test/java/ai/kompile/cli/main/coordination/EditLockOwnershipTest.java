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

package ai.kompile.cli.main.coordination;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.EditBatchTool;
import ai.kompile.cli.main.chat.tools.EditCoordinatorTool;
import ai.kompile.cli.main.chat.tools.EditTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.coordination.CoordinationStateManager.BatchAcquireResult;
import ai.kompile.cli.main.coordination.CoordinationStateManager.ReleaseResult;
import ai.kompile.cli.main.coordination.CoordinationStateManager.ReleaseStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edit-lock ownership is (sessionId, agentName): subagents that share ONE coordination
 * session (one MCP stdio connection, or native in-process subagents of one chat) must
 * still arbitrate with each other, while callers without an agent identity keep the
 * historical session-level semantics.
 */
@TemporaryUserHome
class EditLockOwnershipTest {

    private static final String SESSION = "session-shared";
    private static final String OTHER_SESSION = "session-other";

    @TempDir
    Path tempDir;

    private final ObjectMapper om = new ObjectMapper();
    private Path project;
    private CoordinationStateManager shared;
    private CoordinationStateManager other;
    private EditCoordinatorTool tool;
    /** An MCP call: shared session, no agent identity. */
    private ToolContext mcpContext;

    @BeforeEach
    void setUp() throws Exception {
        project = Files.createDirectories(tempDir.resolve("project"));
        Path systemRoot = tempDir.resolve("system-activity");
        shared = new CoordinationStateManager(project, SESSION, om, systemRoot);
        other = new CoordinationStateManager(project, OTHER_SESSION, om, systemRoot);
        tool = new EditCoordinatorTool(shared);
        mcpContext = newContext(null);
    }

    @AfterEach
    void tearDown() {
        shared.shutdown();
        other.shutdown();
    }

    private ToolContext newContext(String coordinationAgent) {
        PermissionService permissions = new PermissionService();
        permissions.setAutoApproveAll(true);
        ToolContext context = new ToolContext(SESSION, null, permissions, project, new ToolRegistry(om));
        context.setCoordinationAgent(coordinationAgent);
        return context;
    }

    private String abs(String relative) {
        return project.resolve(relative).toAbsolutePath().toString();
    }

    private static String fileHash(String absolutePath) {
        return Integer.toHexString(absolutePath.hashCode());
    }

    private ObjectNode registerEdit(String file, String agentName) {
        ObjectNode params = om.createObjectNode();
        params.put("action", "register_edit");
        params.put("file_path", file);
        if (agentName != null) params.put("agent_name", agentName);
        return params;
    }

    private ObjectNode registerEdits(String agentName, boolean allowPartial, String... files) {
        ObjectNode params = om.createObjectNode();
        params.put("action", "register_edits");
        if (agentName != null) params.put("agent_name", agentName);
        if (allowPartial) params.put("allow_partial", true);
        ArrayNode arr = params.putArray("file_paths");
        for (String f : files) arr.add(f);
        return params;
    }

    private ObjectNode releaseEdit(String lockId, String agentName) {
        ObjectNode params = om.createObjectNode();
        params.put("action", "release_edit");
        params.put("lock_id", lockId);
        if (agentName != null) params.put("agent_name", agentName);
        return params;
    }

    private ObjectNode releaseEdits(String agentName, String... lockIds) {
        ObjectNode params = om.createObjectNode();
        params.put("action", "release_edits");
        if (agentName != null) params.put("agent_name", agentName);
        ArrayNode arr = params.putArray("lock_ids");
        for (String id : lockIds) arr.add(id);
        return params;
    }

    private Path lockFile(String lockId) throws Exception {
        try (Stream<Path> files = Files.walk(project)) {
            return files.filter(p -> p.getFileName().toString().equals(lockId + ".lock.json"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no lock file for " + lockId));
        }
    }

    private boolean lockExists(String lockId) throws Exception {
        try (Stream<Path> files = Files.walk(project)) {
            return files.anyMatch(p -> p.getFileName().toString().equals(lockId + ".lock.json"));
        }
    }

    private void expireLock(String lockId) throws Exception {
        Path file = lockFile(lockId);
        ObjectMapper timeAware = JsonUtils.standardMapper();
        EditLockEntry entry = timeAware.readValue(file.toFile(), EditLockEntry.class);
        entry.setLastHeartbeat(Instant.now().minusSeconds(86_400));
        timeAware.writeValue(file.toFile(), entry);
    }

    // ── Same session, different agents: conflict ─────────────────────────────

    @Test
    void sameSessionDifferentAgentConflictsOnSingleAcquire() {
        String path = abs("shared.txt");

        EditLockResult a = shared.tryAcquireEditLock(path, "edit", "probe-a");
        EditLockResult b = shared.tryAcquireEditLock(path, "edit", "probe-b");

        assertTrue(a.isAcquired());
        assertEquals(SESSION + "-probe-a-" + fileHash(path), a.getLockId());
        assertTrue(b.hasConflict(), "a sibling agent in the same session must conflict");
        assertEquals("probe-a", b.getConflictEntry().getAgentName());
        assertEquals("probe-a", b.getConflictEntry().getOwnerAgent());
        assertEquals(a.getLockId(), b.getConflictEntry().getLockId());
        // Same message format as a cross-session conflict.
        assertEquals(path + " is actively being edited by probe-a (session " + SESSION + ")",
                b.getConflictMessage());
    }

    @Test
    void threeProbesOnOneMcpSessionOnlyTheFirstAcquiresThroughTheTool() throws Exception {
        ToolResult a = tool.execute(registerEdit("probe.txt", "probe-a"), mcpContext);
        ToolResult b = tool.execute(registerEdit("probe.txt", "probe-b"), mcpContext);
        ToolResult c = tool.execute(registerEdit("probe.txt", "probe-c"), mcpContext);

        assertEquals("acquired", a.getMetadata().get("status"), a::getOutput);
        assertEquals("conflict", b.getMetadata().get("status"), b::getOutput);
        assertEquals("conflict", c.getMetadata().get("status"), c::getOutput);
        String heldLockId = (String) a.getMetadata().get("lockId");
        assertTrue(a.getOutput().contains("lock_id " + heldLockId), a::getOutput);
        assertTrue(a.getOutput().contains("owned by agent 'probe-a'"), a::getOutput);
        assertTrue(b.getOutput().contains("is actively being edited by probe-a (session " + SESSION + ")"),
                b::getOutput);
        assertTrue(b.getOutput().contains("Lock id: " + heldLockId), b::getOutput);
        assertEquals(heldLockId, b.getMetadata().get("conflictLockId"));
        assertEquals(1, shared.queryEdits().size());
    }

    @Test
    void sameSessionDifferentAgentConflictsOnBatchAcquire() {
        String contested = abs("contested.txt");
        String free = abs("free.txt");
        assertTrue(shared.tryAcquireEditLock(contested, "edit", "probe-a").isAcquired());

        BatchAcquireResult aborted = shared.tryAcquireEditLocks(
                List.of(free, contested), "edit", "probe-b", false);
        assertTrue(aborted.aborted());
        assertTrue(aborted.results().get(contested).hasConflict());
        assertEquals("probe-a", aborted.results().get(contested).getConflictEntry().getAgentName());
        assertEquals(EditLockResult.Status.SKIPPED, aborted.results().get(free).getStatus());
        assertEquals(1, shared.queryEdits().size(), "all-or-nothing batch must take no lock");

        BatchAcquireResult partial = shared.tryAcquireEditLocks(
                List.of(free, contested), "edit", "probe-b", true);
        assertFalse(partial.aborted());
        assertTrue(partial.results().get(free).isAcquired());
        assertEquals(SESSION + "-probe-b-" + fileHash(free), partial.results().get(free).getLockId());
        assertTrue(partial.results().get(contested).hasConflict());
    }

    @Test
    void sameSessionDifferentAgentConflictsOnBatchAcquireThroughTheTool() throws Exception {
        assertEquals("acquired", tool.execute(
                registerEdit("contested.txt", "probe-a"), mcpContext).getMetadata().get("status"));

        ToolResult aborted = tool.execute(
                registerEdits("probe-b", false, "free.txt", "contested.txt"), mcpContext);
        assertEquals("conflict", aborted.getMetadata().get("status"), aborted::getOutput);
        assertTrue(aborted.getOutput().contains("CONFLICT  " + abs("contested.txt") + " — held by probe-a"),
                aborted::getOutput);
        assertTrue(aborted.getOutput().contains("Batch aborted"), aborted::getOutput);

        ToolResult partial = tool.execute(
                registerEdits("probe-b", true, "free.txt", "contested.txt"), mcpContext);
        assertEquals("partial", partial.getMetadata().get("status"), partial::getOutput);
        assertEquals(1L, ((Number) partial.getMetadata().get("acquired")).longValue());
        assertEquals(1L, ((Number) partial.getMetadata().get("conflicts")).longValue());
    }

    // ── Same agent: idempotent re-acquire ────────────────────────────────────

    @Test
    void sameAgentReacquiresIdempotently() {
        String path = abs("mine.txt");
        EditLockResult first = shared.tryAcquireEditLock(path, "edit", "probe-a");
        EditLockResult again = shared.tryAcquireEditLock(path, "edit", "probe-a");

        assertTrue(first.isAcquired());
        assertFalse(first.isAlreadyHeld());
        assertTrue(again.isAcquired());
        assertTrue(again.isAlreadyHeld());
        assertEquals(first.getLockId(), again.getLockId());

        BatchAcquireResult batch = shared.tryAcquireEditLocks(List.of(path), "edit", "probe-a", false);
        assertTrue(batch.results().get(path).isAcquired());
        assertTrue(batch.results().get(path).isAlreadyHeld());
        assertEquals(first.getLockId(), batch.results().get(path).getLockId());
        assertEquals(1, shared.queryEdits().size());
    }

    // ── No agent_name: historical session semantics ──────────────────────────

    @Test
    void noAgentNameKeepsSessionLevelSemantics() {
        String path = abs("session.txt");
        EditLockResult first = shared.tryAcquireEditLock(path, "edit");
        EditLockResult second = shared.tryAcquireEditLock(path, "edit", (String) null);
        EditLockResult unknown = shared.tryAcquireEditLock(path, "edit", "unknown");

        assertTrue(first.isAcquired());
        assertEquals(SESSION + "-" + fileHash(path), first.getLockId());
        assertTrue(second.isAcquired());
        assertEquals(first.getLockId(), second.getLockId());
        assertTrue(second.isAlreadyHeld());
        assertTrue(unknown.isAcquired(), "the historical 'unknown' placeholder is session-level");
        assertEquals(first.getLockId(), unknown.getLockId());

        EditLockEntry lock = shared.queryEdits().get(0);
        assertNull(lock.getOwnerAgent());
        assertEquals("unknown", lock.getAgentName());
        assertNull(shared.findConflictingLock(path), "own session never conflicts without identity");

        // Session-level and agent-scoped locks of one session do not arbitrate each other:
        // a caller without agent_name has no identity to arbitrate on.
        assertTrue(shared.tryAcquireEditLock(path, "edit", "probe-a").isAcquired());
        assertNull(shared.findConflictingLock(path, "probe-a"));
        assertEquals(2, shared.queryEdits().size());
    }

    @Test
    void preOwnershipLockFileIsTreatedAsSessionLevel() throws Exception {
        String path = abs("legacy.txt");
        String lockId = SESSION + "-" + fileHash(path);
        EditLockEntry legacy = new EditLockEntry(lockId, SESSION, "legacy-agent",
                path, path, "edit", Instant.now(), 300);
        ObjectMapper timeAware = JsonUtils.standardMapper();
        ObjectNode json = timeAware.valueToTree(legacy);
        json.remove("ownerAgent");
        Path editsDir = lockFile(shared.tryAcquireEditLock(abs("probe.txt"), "edit").getLockId()).getParent();
        timeAware.writeValue(editsDir.resolve(lockId + ".lock.json").toFile(), json);

        EditLockEntry read = shared.queryEditsForFile(path).get(0);
        assertNull(read.getOwnerAgent());
        assertTrue(shared.tryAcquireEditLock(path, "edit", "probe-a").isAcquired(),
                "a session-level legacy lock does not block an agent of the same session");
        assertTrue(other.tryAcquireEditLock(path, "edit", "probe-a").hasConflict(),
                "another session still conflicts with it");
        assertEquals(ReleaseStatus.RELEASED, shared.releaseEditLock(lockId, "probe-b").status(),
                "any caller in the owning session may release a session-level lock");
    }

    // ── Another session: conflicts as before ─────────────────────────────────

    @Test
    void differentSessionStillConflicts() {
        String path = abs("cross.txt");
        assertTrue(other.tryAcquireEditLock(path, "edit", "probe-a").isAcquired());

        EditLockResult sameName = shared.tryAcquireEditLock(path, "edit", "probe-a");
        EditLockResult agentless = shared.tryAcquireEditLock(path, "edit");
        BatchAcquireResult batch = shared.tryAcquireEditLocks(List.of(path), "edit", "probe-a", false);

        assertTrue(sameName.hasConflict(), "the same agent name in another session is another owner");
        assertEquals(path + " is actively being edited by probe-a (session " + OTHER_SESSION + ")",
                sameName.getConflictMessage());
        assertTrue(agentless.hasConflict());
        assertTrue(batch.aborted());
        assertNotNull(shared.findConflictingLock(path));
        assertNotNull(shared.findConflictingLock(path, "probe-a"));
    }

    // ── Release ownership ────────────────────────────────────────────────────

    @Test
    void ownerReleasesThroughTheTool() throws Exception {
        ToolResult single = tool.execute(registerEdit("one.txt", "probe-a"), mcpContext);
        String singleId = (String) single.getMetadata().get("lockId");
        ToolResult released = tool.execute(releaseEdit(singleId, "probe-a"), mcpContext);
        assertFalse(released.isError(), released::getOutput);
        assertEquals("released", released.getMetadata().get("status"));
        assertFalse(lockExists(singleId));

        ToolResult batch = tool.execute(registerEdits("probe-a", false, "x.txt", "y.txt"), mcpContext);
        @SuppressWarnings("unchecked")
        Map<String, Object> ids = (Map<String, Object>) batch.getMetadata().get("lockIds");
        String[] lockIds = ids.values().stream().map(String::valueOf).toArray(String[]::new);
        ToolResult batchRelease = tool.execute(releaseEdits("probe-a", lockIds), mcpContext);
        assertFalse(batchRelease.isError(), batchRelease::getOutput);
        assertTrue(batchRelease.getOutput().contains("2/2 locks released"), batchRelease::getOutput);
        assertEquals(List.of(), batchRelease.getMetadata().get("retainedLockIds"));
        assertTrue(shared.queryEdits().isEmpty());
    }

    @Test
    void crossAgentReleaseIsRejected() throws Exception {
        ToolResult held = tool.execute(registerEdit("guarded.txt", "probe-a"), mcpContext);
        String lockId = (String) held.getMetadata().get("lockId");

        ToolResult bySibling = tool.execute(releaseEdit(lockId, "probe-b"), mcpContext);
        assertTrue(bySibling.isError(), "a sibling agent must not release another agent's lock");
        assertTrue(bySibling.getOutput().contains("owned by agent 'probe-a'"), bySibling::getOutput);
        assertTrue(lockExists(lockId));

        ToolResult agentless = tool.execute(releaseEdit(lockId, null), mcpContext);
        assertTrue(agentless.isError(), "a caller without agent_name cannot release an agent lock");
        assertTrue(lockExists(lockId));

        ToolResult batch = tool.execute(releaseEdits("probe-b", lockId), mcpContext);
        assertFalse(batch.isError());
        assertTrue(batch.getOutput().contains("NOT_OWNER " + lockId), batch::getOutput);
        assertTrue(batch.getOutput().contains("0/1 locks released"), batch::getOutput);
        assertEquals(List.of(lockId), batch.getMetadata().get("retainedLockIds"));
        assertTrue(lockExists(lockId));
        assertTrue(shared.tryAcquireEditLock(abs("guarded.txt"), "edit", "probe-b").hasConflict(),
                "the rejected release must leave the lock enforced");

        ReleaseResult direct = shared.releaseEditLock(lockId, "probe-b");
        assertEquals(ReleaseStatus.NOT_OWNER, direct.status());
        assertEquals("probe-a", direct.holder().getOwnerAgent());

        assertEquals("released", tool.execute(releaseEdit(lockId, "probe-a"), mcpContext)
                .getMetadata().get("status"));
        assertFalse(lockExists(lockId));
    }

    @Test
    void crossSessionReleaseIsRejectedButSessionLevelLockIsSharedWithinTheSession() throws Exception {
        String foreignPath = abs("foreign.txt");
        String foreignId = other.tryAcquireEditLock(foreignPath, "edit", "probe-a").getLockId();
        ReleaseResult foreign = shared.releaseEditLock(foreignId, "probe-a");
        assertEquals(ReleaseStatus.NOT_OWNER, foreign.status());
        assertTrue(foreign.message().contains("not by this session"), foreign.message());
        assertTrue(lockExists(foreignId));

        String sessionLevel = shared.tryAcquireEditLock(abs("common.txt"), "edit").getLockId();
        assertEquals(ReleaseStatus.RELEASED, shared.releaseEditLock(sessionLevel, "probe-b").status());

        assertEquals(ReleaseStatus.NOT_FOUND, shared.releaseEditLock(sessionLevel, "probe-b").status());
        assertEquals(ReleaseStatus.NOT_FOUND, shared.releaseEditLock("../escape", "probe-b").status());
    }

    @Test
    void sessionShutdownReleasesEveryAgentsLocks() {
        shared.tryAcquireEditLock(abs("a.txt"), "edit", "probe-a");
        shared.tryAcquireEditLock(abs("b.txt"), "edit", "probe-b");
        shared.tryAcquireEditLock(abs("c.txt"), "edit");

        shared.shutdown();

        assertTrue(other.queryEdits().isEmpty());
    }

    // ── Conflict probe used by edit/write/edit_batch/edit_patch ──────────────

    @Test
    void conflictProbeIsScopedToTheCallersAgent() {
        String path = abs("probe.txt");
        assertTrue(shared.tryAcquireEditLock(path, "edit", "probe-a").isAcquired());

        assertNull(shared.findConflictingLock(path), "no identity: session semantics");
        assertNull(shared.findConflictingLock(path, "probe-a"), "the owner is not in conflict");
        EditLockEntry sibling = shared.findConflictingLock(path, "probe-b");
        assertNotNull(sibling);
        assertEquals("probe-a", sibling.getAgentName());
        assertNotNull(other.findConflictingLock(path), "another session always sees it");
    }

    @Test
    void nativeSubagentIdentityArbitratesToolCalls() throws Exception {
        ToolContext first = newContext("build-1111");
        ToolContext second = newContext("build-2222");
        Path file = project.resolve("native.txt");
        Files.writeString(file, "content\n");

        ToolResult held = tool.execute(registerEdit("native.txt", null), first);
        assertEquals("acquired", held.getMetadata().get("status"), held::getOutput);
        assertEquals(SESSION + "-build-1111-" + fileHash(file.toAbsolutePath().toString()),
                held.getMetadata().get("lockId"));

        ToolResult blocked = tool.execute(registerEdit("native.txt", null), second);
        assertEquals("conflict", blocked.getMetadata().get("status"), blocked::getOutput);
        // A known identity wins over agent_name, so a subagent cannot impersonate its sibling.
        ToolResult impersonating = tool.execute(registerEdit("native.txt", "build-1111"), second);
        assertEquals("conflict", impersonating.getMetadata().get("status"), impersonating::getOutput);
        ToolResult release = tool.execute(
                releaseEdit((String) held.getMetadata().get("lockId"), "build-1111"), second);
        assertTrue(release.isError(), release::getOutput);

        // edit_batch fails fast for the sibling and leaves the file untouched.
        second.recordFileRead(file);
        EditBatchTool editBatch = new EditBatchTool(shared);
        ObjectNode batchParams = om.createObjectNode();
        ObjectNode edit = batchParams.putArray("edits").addObject();
        edit.put("file_path", "native.txt");
        edit.put("old_string", "content");
        edit.put("new_string", "sibling");
        ToolResult siblingBatch = editBatch.execute(batchParams, second);
        assertTrue(siblingBatch.isError(), siblingBatch::getOutput);
        assertTrue(siblingBatch.getOutput().contains("locked by build-1111"), siblingBatch::getOutput);
        assertEquals("content\n", Files.readString(file));

        // The owner edits without a warning.
        first.recordFileRead(file);
        ObjectNode ownerEdit = om.createObjectNode();
        ownerEdit.put("file_path", "native.txt");
        ownerEdit.put("old_string", "content");
        ownerEdit.put("new_string", "owner");
        ToolResult owned = new EditTool(shared).execute(ownerEdit, first);
        assertFalse(owned.isError(), owned::getOutput);
        assertFalse(owned.getOutput().contains("WARNING"), owned::getOutput);
        assertEquals("owner\n", Files.readString(file));

        // An MCP call (no identity) in the same session keeps session semantics: no warning.
        mcpContext.recordFileRead(file);
        ObjectNode mcpEdit = om.createObjectNode();
        mcpEdit.put("file_path", "native.txt");
        mcpEdit.put("old_string", "owner");
        mcpEdit.put("new_string", "mcp");
        ToolResult viaMcp = new EditTool(shared).execute(mcpEdit, mcpContext);
        assertFalse(viaMcp.isError(), viaMcp::getOutput);
        assertFalse(viaMcp.getOutput().contains("WARNING"), viaMcp::getOutput);
        assertEquals("mcp\n", Files.readString(file));
    }

    @Test
    void identityPropagatesToForkedToolContexts() {
        ToolContext parent = newContext("build-1111");
        assertEquals("build-1111", parent.forkForToolExecution().getCoordinationAgent());
        assertNull(mcpContext.forkForToolExecution().getCoordinationAgent());
        parent.setCoordinationAgent("   ");
        assertNull(parent.getCoordinationAgent());
    }

    // ── Visibility: query_edits and awareness ────────────────────────────────

    @Test
    void queryEditsAndAwarenessShowAgentAndFullLockId() throws Exception {
        ToolResult held = tool.execute(registerEdit("visible.txt", "probe-a"), mcpContext);
        String lockId = (String) held.getMetadata().get("lockId");

        ObjectNode query = om.createObjectNode();
        query.put("action", "query_edits");
        query.put("file_path", "visible.txt");
        ToolResult edits = tool.execute(query, mcpContext);
        assertTrue(edits.getOutput().contains("probe-a"), edits::getOutput);
        assertTrue(edits.getOutput().contains(lockId), edits::getOutput);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> locks = (List<Map<String, Object>>) edits.getMetadata().get("locks");
        assertEquals(1, locks.size());
        assertEquals(lockId, locks.get(0).get("lockId"));
        assertEquals("probe-a", locks.get(0).get("ownerAgent"));
        assertEquals(SESSION, locks.get(0).get("sessionId"));

        ObjectNode awareness = om.createObjectNode();
        awareness.put("action", "awareness");
        ToolResult aware = tool.execute(awareness, mcpContext);
        assertTrue(aware.getOutput().contains("by probe-a"), aware::getOutput);
        assertTrue(aware.getOutput().contains("lock_id=" + lockId), aware::getOutput);

        assertTrue(shared.statusDashboard().contains("lock_id=" + lockId));
    }

    // ── Stale and dead-holder cleanup ────────────────────────────────────────

    @Test
    void staleSiblingLockIsReleasableAndEvicted() throws Exception {
        String path = abs("stale.txt");
        String staleId = shared.tryAcquireEditLock(path, "edit", "probe-a").getLockId();
        expireLock(staleId);

        assertNull(shared.findConflictingLock(path, "probe-b"), "a stale lock never conflicts");
        assertEquals(ReleaseStatus.RELEASED, shared.releaseEditLock(staleId, "probe-b").status(),
                "a stale lock is releasable by anyone");

        String again = shared.tryAcquireEditLock(path, "edit", "probe-a").getLockId();
        expireLock(again);
        EditLockResult taken = shared.tryAcquireEditLock(path, "edit", "probe-b");
        assertTrue(taken.isAcquired(), "acquire evicts a stale sibling lock");
        assertFalse(lockExists(again));

        expireLock(taken.getLockId());
        BatchAcquireResult batch = shared.tryAcquireEditLocks(List.of(path), "edit", "probe-c", false);
        assertTrue(batch.results().get(path).isAcquired(), "batch acquire evicts it too");
        assertFalse(lockExists(taken.getLockId()));
    }

    @Test
    void deadHolderLockIsEvictedAndReleasable() throws Exception {
        // Beyond every kernel pid_max bound, so the holder is provably dead.
        other.registerAgent("finished", null, "probe-a", 0, Integer.MAX_VALUE);
        String path = abs("orphan.txt");
        String orphanId = other.tryAcquireEditLock(path, "edit", "probe-a").getLockId();

        assertNull(shared.findConflictingLock(path, "probe-a"));
        assertFalse(lockExists(orphanId), "the conflict probe evicts the dead holder's lock");

        orphanId = other.tryAcquireEditLock(path, "edit", "probe-a").getLockId();
        assertEquals(ReleaseStatus.RELEASED, shared.releaseEditLock(orphanId, "probe-b").status(),
                "a dead holder's lock is releasable by anyone");

        orphanId = other.tryAcquireEditLock(path, "edit", "probe-a").getLockId();
        assertTrue(shared.tryAcquireEditLock(path, "edit", "probe-a").isAcquired());
        assertFalse(lockExists(orphanId));

        shared.queryEdits().forEach(lock -> shared.releaseEditLock(lock.getLockId()));
        orphanId = other.tryAcquireEditLock(path, "edit", "probe-a").getLockId();
        BatchAcquireResult batch = shared.tryAcquireEditLocks(List.of(path), "edit", "probe-b", false);
        assertTrue(batch.results().get(path).isAcquired());
        assertFalse(lockExists(orphanId));
    }

    // ── Owner key sanitization ───────────────────────────────────────────────

    @Test
    void ownerAgentKeySanitizesWithoutCollisions() {
        assertNull(CoordinationStateManager.ownerAgentKey(null));
        assertNull(CoordinationStateManager.ownerAgentKey("   "));
        assertNull(CoordinationStateManager.ownerAgentKey("unknown"));
        assertNull(CoordinationStateManager.ownerAgentKey("UNKNOWN"));
        assertEquals("probe-a", CoordinationStateManager.ownerAgentKey(" probe-a "));
        assertEquals("agent_one", CoordinationStateManager.ownerAgentKey("agent_one"));

        String spaced = CoordinationStateManager.ownerAgentKey("agent one");
        assertNotEquals("agent_one", spaced, "sanitized names must not collide with real ones");
        assertTrue(spaced.matches("[A-Za-z0-9._-]+"), spaced);
        String traversal = CoordinationStateManager.ownerAgentKey("../../etc/passwd");
        assertTrue(traversal.matches("[A-Za-z0-9._-]+"), traversal);
        assertFalse(traversal.contains("/"));

        String longName = CoordinationStateManager.ownerAgentKey("x".repeat(500));
        assertTrue(longName.length() <= 64, longName);
        assertNotEquals(longName, CoordinationStateManager.ownerAgentKey("x".repeat(501)));

        // Odd names still round-trip through acquire/conflict/release.
        String path = abs("odd.txt");
        EditLockResult odd = shared.tryAcquireEditLock(path, "edit", "agent one");
        assertTrue(odd.isAcquired());
        assertTrue(shared.tryAcquireEditLock(path, "edit", "agent_one").hasConflict());
        assertEquals("agent one", shared.queryEdits().get(0).getAgentName());
        assertEquals(ReleaseStatus.RELEASED, shared.releaseEditLock(odd.getLockId(), "agent one").status());
    }

    @Test
    void longSessionAndAgentIdsStayWithinTheSafeComponentBound() throws Exception {
        String longSession = "s".repeat(160);
        CoordinationStateManager longManager = new CoordinationStateManager(
                project, longSession, om, tempDir.resolve("system-activity"));
        try {
            String path = abs("long.txt");
            EditLockResult agentLock = longManager.tryAcquireEditLock(path, "edit", "y".repeat(64));
            EditLockResult sessionLock = longManager.tryAcquireEditLock(abs("long2.txt"), "edit");
            assertTrue(agentLock.getLockId().length() <= 160, agentLock.getLockId());
            assertTrue(sessionLock.getLockId().length() <= 160, sessionLock.getLockId());
            // Still readable (not quarantined) and still arbitrating.
            assertEquals(2, longManager.queryEdits().size());
            assertTrue(longManager.tryAcquireEditLock(path, "edit", "z").hasConflict());
            assertTrue(shared.tryAcquireEditLock(path, "edit", "y".repeat(64)).hasConflict());
        } finally {
            longManager.shutdown();
        }
    }
}
