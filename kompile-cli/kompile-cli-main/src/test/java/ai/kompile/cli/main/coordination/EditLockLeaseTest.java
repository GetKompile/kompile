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
import ai.kompile.cli.main.chat.harness.HarnessConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.EditBatchTool;
import ai.kompile.cli.main.chat.tools.EditCoordinatorTool;
import ai.kompile.cli.main.chat.tools.EditPatchTool;
import ai.kompile.cli.main.chat.tools.EditTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.tools.WriteTool;
import ai.kompile.cli.main.coordination.CoordinationStateManager.BatchAcquireResult;
import ai.kompile.cli.main.coordination.CoordinationStateManager.ReleaseStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Per-agent liveness of edit locks: every agent-owned lock carries a lease that only that
 * agent's own activity renews (edit_coordinator calls, edit-tool calls, registers made
 * under its agent name). A lock whose lease runs out is stale and reclaimable through the
 * same stale-cleanup path as a dead holder's lock, even while its session heartbeats; a
 * lock renewed within its lease is never taken. Session-level locks have no lease.
 *
 * <p>Renewal is synchronous: an edit-tool call under an agent identity renews the lease and
 * re-verifies lock ownership in one coordinator critical section before it writes. A busy
 * coordinator is waited for (bounded) and the edit fails without writing on timeout; a lock
 * lost to another owner fails the edit with CONFLICT naming the new holder.
 *
 * <p>The lease runs on an injected clock, so these tests move time without sleeping; the
 * session heartbeat TTL stays on wall time and therefore stays fresh throughout. Blocked
 * callers are observed through the coordinator's busy-wait seam with a latch, not by timing.
 */
@TemporaryUserHome
class EditLockLeaseTest {

    private static final String SESSION = "session-lease";
    private static final String OTHER_SESSION = "session-lease-other";
    private static final Duration LEASE = Duration.ofSeconds(600);
    /** Lease plus the largest renewal slack (15 s): past this, a lease surely expired. */
    private static final Duration PAST_LEASE = LEASE.plusSeconds(16);
    /** The coordinator's production bounded wait for edit-lock operations. */
    private static final long EDIT_LOCK_WAIT_MS = 500L;

    @TempDir
    Path tempDir;

    private final ObjectMapper om = new ObjectMapper();
    private final ObjectMapper timeAware = JsonUtils.standardMapper();
    private Path project;
    private Path systemRoot;
    private MutableClock clock;
    private CoordinationStateManager shared;
    private CoordinationStateManager other;
    private EditCoordinatorTool tool;
    /** An MCP call: shared session, no native agent identity. */
    private ToolContext mcpContext;

    /** A lease clock the test moves by hand. */
    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration step) {
            now = now.plus(step);
        }

        void set(Instant instant) {
            now = instant;
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
        PermissionService permissions = new PermissionService();
        permissions.setAutoApproveAll(true);
        ToolContext context = new ToolContext(SESSION, null, permissions, project, new ToolRegistry(om));
        context.setCoordinationAgent(coordinationAgent);
        return context;
    }

    private String abs(String relative) {
        return project.resolve(relative).toAbsolutePath().toString();
    }

    private ObjectNode registerEdit(String file, String agentName) {
        ObjectNode params = om.createObjectNode();
        params.put("action", "register_edit");
        params.put("file_path", file);
        if (agentName != null) params.put("agent_name", agentName);
        return params;
    }

    private ObjectNode queryEdits(String agentName) {
        ObjectNode params = om.createObjectNode();
        params.put("action", "query_edits");
        if (agentName != null) params.put("agent_name", agentName);
        return params;
    }

    private ObjectNode releaseEdit(String lockId, String agentName) {
        ObjectNode params = om.createObjectNode();
        params.put("action", "release_edit");
        params.put("lock_id", lockId);
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

    private ObjectNode editParams(String file, String oldString, String newString, String agentName) {
        ObjectNode params = om.createObjectNode();
        params.put("file_path", file);
        params.put("old_string", oldString);
        params.put("new_string", newString);
        if (agentName != null) params.put("agent_name", agentName);
        return params;
    }

    private ObjectNode batchParams(String file, String oldString, String newString, String agentName) {
        ObjectNode params = om.createObjectNode();
        ArrayNode edits = params.putArray("edits");
        ObjectNode edit = edits.addObject();
        edit.put("file_path", file);
        edit.put("old_string", oldString);
        edit.put("new_string", newString);
        if (agentName != null) params.put("agent_name", agentName);
        return params;
    }

    private ObjectNode patchParams(String file, String oldLine, String newLine, String agentName) {
        ObjectNode params = om.createObjectNode();
        ArrayNode patches = params.putArray("patches");
        ObjectNode patch = patches.addObject();
        patch.put("file_path", file);
        patch.put("patch", "@@\n-" + oldLine + "\n+" + newLine + "\n");
        if (agentName != null) params.put("agent_name", agentName);
        return params;
    }

    /** The project's coordinator lock file, which a test holds to make the coordinator busy. */
    private Path coordinatorLockFile() throws Exception {
        Path dir = Files.createDirectories(project.resolve(".kompile").resolve("coordination"));
        return dir.resolve(".coordinator.lock");
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

    private EditLockEntry readLock(String lockId) throws Exception {
        return timeAware.readValue(lockFile(lockId).toFile(), EditLockEntry.class);
    }

    private Instant leaseOf(String lockId) throws Exception {
        return readLock(lockId).getLeaseExpiresAt();
    }

    private Set<String> lockIds() throws Exception {
        try (Stream<Path> files = Files.walk(project)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".lock.json"))
                    .map(name -> name.substring(0, name.length() - ".lock.json".length()))
                    .collect(Collectors.toSet());
        }
    }

    /** agent-b (same session) and another session's agent both still conflict on {@code path}. */
    private void assertStillHeld(String path, String lockId) throws Exception {
        assertNotNull(shared.findConflictingLock(path, "agent-b"), "a renewed lock still conflicts");
        assertTrue(shared.tryAcquireEditLock(path, "edit", "agent-b").hasConflict(),
                "a lock renewed within its lease is never taken");
        assertTrue(other.tryAcquireEditLock(path, "edit", "agent-c").hasConflict(),
                "nor taken by another session");
        assertTrue(lockExists(lockId));
    }

    // ── Lease expiry: reclaim ────────────────────────────────────────────────

    @Test
    void agentLockCarriesALeaseAndSessionLockDoesNot() throws Exception {
        Instant start = clock.instant();
        String agentLock = shared.tryAcquireEditLock(abs("agent.txt"), "edit", "agent-a").getLockId();
        String sessionLock = shared.tryAcquireEditLock(abs("session.txt"), "edit").getLockId();

        assertEquals(start.plus(LEASE).plusSeconds(15), leaseOf(agentLock),
                "a fresh agent lock gets the lease plus the renewal slack");
        assertNull(leaseOf(sessionLock), "a session-level lock has no lease");
        assertEquals(LEASE, shared.getAgentLease());
    }

    @Test
    void leaseExpiryLetsAnotherAgentReclaimTheLock() throws Exception {
        String path = abs("expiring.txt");
        EditLockResult held = shared.tryAcquireEditLock(path, "edit", "agent-a");
        assertTrue(held.isAcquired());
        assertTrue(shared.tryAcquireEditLock(path, "edit", "agent-b").hasConflict());

        // A sibling's write is refused while the lock is live, and writes nothing.
        ToolResult refused = new WriteTool(shared).execute(writeParams("expiring.txt", "b\n", "agent-b"), mcpContext);
        assertTrue(refused.isError(), refused::getOutput);
        assertTrue(refused.getOutput().contains("CONFLICT"), refused::getOutput);
        assertTrue(refused.getOutput().contains("locked by agent-a"), refused::getOutput);
        assertFalse(Files.exists(project.resolve("expiring.txt")), "nothing was written");

        // agent-a goes quiet. Its session keeps running (the heartbeat TTL is wall time and
        // fresh), so only the lease can free the lock.
        clock.advance(PAST_LEASE);
        EditLockEntry expired = readLock(held.getLockId());
        assertFalse(expired.isHeartbeatExpired(Instant.now()), "the session is alive");
        assertTrue(expired.isLeaseExpired(clock.instant()), "the agent's lease ran out");

        ToolResult allowed = new WriteTool(shared).execute(writeParams("expiring.txt", "c\n", "agent-b"), mcpContext);
        assertFalse(allowed.isError(), allowed::getOutput);
        assertFalse(allowed.getOutput().contains("WARNING"), allowed::getOutput);
        assertEquals("c\n", Files.readString(project.resolve("expiring.txt")));
        assertNull(shared.findConflictingLock(path, "agent-b"), "an expired lease never conflicts");

        EditLockResult taken = shared.tryAcquireEditLock(path, "edit", "agent-b");
        assertTrue(taken.isAcquired(), "acquire reclaims the lease-expired lock");
        assertFalse(lockExists(held.getLockId()), "through the stale-cleanup path");
    }

    @Test
    void leaseExpiryIsReclaimedByBatchToolReleaseQueryAndOtherSessions() throws Exception {
        // Batch acquire.
        String batchPath = abs("batch.txt");
        String batchLock = shared.tryAcquireEditLock(batchPath, "edit", "agent-a").getLockId();
        clock.advance(PAST_LEASE);
        BatchAcquireResult batch = shared.tryAcquireEditLocks(List.of(batchPath), "edit", "agent-b", false);
        assertTrue(batch.results().get(batchPath).isAcquired(), "batch acquire reclaims it");
        assertFalse(lockExists(batchLock));

        // The edit_coordinator tool (MCP agents pass agent_name).
        ToolResult held = tool.execute(registerEdit("tool.txt", "agent-a"), mcpContext);
        assertEquals("acquired", held.getMetadata().get("status"), held::getOutput);
        ToolResult blocked = tool.execute(registerEdit("tool.txt", "agent-b"), mcpContext);
        assertEquals("conflict", blocked.getMetadata().get("status"), blocked::getOutput);
        clock.advance(PAST_LEASE);
        ToolResult reclaimed = tool.execute(registerEdit("tool.txt", "agent-b"), mcpContext);
        assertEquals("acquired", reclaimed.getMetadata().get("status"), reclaimed::getOutput);
        assertFalse(lockExists((String) held.getMetadata().get("lockId")));

        // Owner-checked release: a live sibling lock is refused, an expired one is not.
        String releasable = shared.tryAcquireEditLock(abs("release.txt"), "edit", "agent-a").getLockId();
        assertEquals(ReleaseStatus.NOT_OWNER, shared.releaseEditLock(releasable, "agent-b").status());
        clock.advance(PAST_LEASE);
        ToolResult released = tool.execute(releaseEdit(releasable, "agent-b"), mcpContext);
        assertFalse(released.isError(), released::getOutput);
        assertFalse(lockExists(releasable));

        // queryEdits evicts it.
        String queried = shared.tryAcquireEditLock(abs("query.txt"), "edit", "agent-a").getLockId();
        clock.advance(PAST_LEASE);
        assertTrue(shared.queryEdits().stream().noneMatch(lock -> lock.getLockId().equals(queried)));
        assertFalse(lockExists(queried));

        // Another session reclaims it too: its session is alive, but the agent is not.
        String crossPath = abs("cross.txt");
        String crossLock = shared.tryAcquireEditLock(crossPath, "edit", "agent-a").getLockId();
        assertTrue(other.tryAcquireEditLock(crossPath, "edit", "agent-c").hasConflict());
        clock.advance(PAST_LEASE);
        assertNull(other.findConflictingLock(crossPath, "agent-c"));
        assertTrue(other.tryAcquireEditLock(crossPath, "edit", "agent-c").isAcquired());
        assertFalse(lockExists(crossLock));
    }

    // ── Renewal within the lease: never stolen ───────────────────────────────

    @Test
    void everyKindOfAgentActivityRenewsTheLeaseAndBlocksReclaim() throws Exception {
        String path = abs("renewed.txt");
        String lockId = shared.tryAcquireEditLock(path, "edit", "agent-a").getLockId();
        Instant original = leaseOf(lockId);
        Duration step = Duration.ofSeconds(400);

        // 1. Any edit_coordinator call under the agent name (here a read-only query).
        clock.advance(step);
        ToolResult query = tool.execute(queryEdits("agent-a"), mcpContext);
        assertFalse(query.isError(), query::getOutput);
        clock.advance(step);
        assertTrue(clock.instant().isAfter(original), "past the original lease");
        assertStillHeld(path, lockId);

        // 2. An edit-tool call carrying agent_name (write of an unrelated file).
        ToolResult write = new WriteTool(shared).execute(writeParams("notes.txt", "a\n", "agent-a"), mcpContext);
        assertFalse(write.isError(), write::getOutput);
        clock.advance(step);
        assertStillHeld(path, lockId);

        // 3. A register of another file under the agent name.
        ToolResult second = tool.execute(registerEdit("second.txt", "agent-a"), mcpContext);
        assertEquals("acquired", second.getMetadata().get("status"), second::getOutput);
        clock.advance(step);
        assertStillHeld(path, lockId);
        assertStillHeld(abs("second.txt"), (String) second.getMetadata().get("lockId"));

        // 4. A native subagent's identity (no agent_name parameter at all).
        ToolResult nativeCall = tool.execute(queryEdits(null), newContext("agent-a"));
        assertFalse(nativeCall.isError(), nativeCall::getOutput);
        clock.advance(step);
        assertStillHeld(path, lockId);

        // 5. An edit call carrying agent_name.
        Path edited = project.resolve("edited.txt");
        Files.writeString(edited, "before\n");
        mcpContext.recordFileRead(edited);
        ObjectNode edit = om.createObjectNode();
        edit.put("file_path", "edited.txt");
        edit.put("old_string", "before");
        edit.put("new_string", "after");
        edit.put("agent_name", "agent-a");
        ToolResult editResult = new EditTool(shared).execute(edit, mcpContext);
        assertFalse(editResult.isError(), editResult::getOutput);
        clock.advance(step);
        assertStillHeld(path, lockId);

        // Silence for a full lease: now it goes.
        clock.advance(PAST_LEASE);
        assertTrue(shared.tryAcquireEditLock(path, "edit", "agent-b").isAcquired());
        assertFalse(lockExists(lockId));
    }

    @Test
    void throttledRenewalsNeverLetALeaseEndBeforeLastActivityPlusLease() throws Exception {
        String path = abs("throttled.txt");
        String lockId = shared.tryAcquireEditLock(path, "edit", "agent-a").getLockId();
        // Gaps around the 15 s write throttle and up to one second short of a full lease.
        long[] gaps = {1, 5, 14, 15, 16, 1, 300, 599, 2, 599, 30, 1, 599};
        for (long gap : gaps) {
            clock.advance(Duration.ofSeconds(gap));
            assertStillHeld(path, lockId);
            assertTrue(shared.renewAgentLease("agent-a"));
            Instant activity = clock.instant();
            assertFalse(leaseOf(lockId).isBefore(activity.plus(LEASE)),
                    "persisted lease " + leaseOf(lockId) + " ends before activity " + activity + " + lease");
        }
        clock.advance(LEASE.minusSeconds(1));
        assertStillHeld(path, lockId);
    }

    // ── Busy coordinator: renewal is synchronous with the edit ───────────────

    @Test
    void lastInstantActivityWaitsForABusyCoordinatorCommitsAndBeatsAConcurrentReclaim() throws Exception {
        String path = abs("last-instant.txt");
        String lockId = shared.tryAcquireEditLock(path, "edit", "agent-a").getLockId();
        Instant leaseEnd = leaseOf(lockId);
        // Run both code paths once: a write elsewhere and a refused reclaim renew nothing yet.
        assertFalse(new WriteTool(shared).execute(
                writeParams("warm-up.txt", "w\n", "agent-a"), mcpContext).isError());
        assertTrue(other.tryAcquireEditLock(path, "edit", "agent-c").hasConflict());
        assertEquals(leaseEnd, leaseOf(lockId), "nothing renewed it yet (throttle)");

        // The very last instant of the lease: still valid (expiry is strictly after the end).
        clock.set(leaseEnd);
        CountDownLatch bothWaiting = new CountDownLatch(2);
        shared.onCoordinatorBusyWait(bothWaiting::countDown);
        other.onCoordinatorBusyWait(bothWaiting::countDown);
        // The waiters must outlast the hold however slowly this test thread runs; the hold
        // ends only when the test releases the coordinator, never by a timeout.
        shared.setEditLockWaitMillis(TimeUnit.MINUTES.toMillis(1));
        other.setEditLockWaitMillis(TimeUnit.MINUTES.toMillis(1));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ToolResult> edit;
            Future<EditLockResult> reclaim;
            // Another holder of the project's coordinator lock (a same-JVM holder counts).
            try (FileChannel channel = FileChannel.open(coordinatorLockFile(),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock busy = channel.lock()) {
                assertTrue(busy.isValid());
                edit = pool.submit(() -> new WriteTool(shared).execute(
                        writeParams("last-instant.txt", "a\n", "agent-a"), mcpContext));
                reclaim = pool.submit(() -> other.tryAcquireEditLock(path, "edit", "agent-c"));
                assertTrue(bothWaiting.await(10, TimeUnit.SECONDS),
                        "the edit and the reclaim both wait for the coordinator");
                assertFalse(Files.exists(project.resolve("last-instant.txt")),
                        "nothing is written before the renewal commits");
                assertEquals(leaseEnd, leaseOf(lockId), "nothing renewed while the coordinator is held");
            }

            ToolResult written = edit.get(10, TimeUnit.SECONDS);
            assertFalse(written.isError(), written::getOutput);
            assertEquals("a\n", Files.readString(project.resolve("last-instant.txt")));
            EditLockResult reclaimed = reclaim.get(10, TimeUnit.SECONDS);
            assertTrue(reclaimed.hasConflict(), "the concurrent reclaim fails");
            assertEquals("agent-a", reclaimed.getConflictEntry().getAgentName());
        } finally {
            shared.onCoordinatorBusyWait(null);
            other.onCoordinatorBusyWait(null);
            shared.setEditLockWaitMillis(EDIT_LOCK_WAIT_MS);
            other.setEditLockWaitMillis(EDIT_LOCK_WAIT_MS);
            pool.shutdownNow();
        }

        // The renewal committed: the lease now runs a full lease past the activity, so the
        // lock survives the original end and everything up to one lease after the edit.
        assertFalse(leaseOf(lockId).isBefore(leaseEnd.plus(LEASE)), "renewed by the edit");
        clock.advance(Duration.ofSeconds(1));
        assertTrue(clock.instant().isAfter(leaseEnd), "past the lease the edit found");
        assertStillHeld(path, lockId);
        clock.set(leaseEnd.plus(LEASE));
        assertStillHeld(path, lockId);
    }

    @Test
    void anEditAfterTheLeaseExpiredAndWasReclaimedIsRejectedWithConflictAndWritesNothing() throws Exception {
        Path file = project.resolve("reclaimed.txt");
        Files.writeString(file, "original\n");
        String path = file.toAbsolutePath().toString();
        String lostLock = shared.tryAcquireEditLock(path, "edit", "agent-a").getLockId();

        // agent-a is silent for a full lease; agent-b reclaims the file.
        clock.advance(PAST_LEASE);
        EditLockResult taken = shared.tryAcquireEditLock(path, "edit", "agent-b");
        assertTrue(taken.isAcquired(), "the lease-expired lock is reclaimed");
        assertFalse(lockExists(lostLock));

        // agent-a comes back and edits the file it believes it still holds — through every
        // edit tool, by agent_name and by native subagent identity.
        ToolContext nativeA = newContext("agent-a");
        mcpContext.recordFileRead(file);
        nativeA.recordFileRead(file);

        List<ToolResult> attempts = List.of(
                new EditTool(shared).execute(editParams("reclaimed.txt", "original", "edit", "agent-a"), mcpContext),
                new EditTool(shared).execute(editParams("reclaimed.txt", "original", "native", null), nativeA),
                new WriteTool(shared).execute(writeParams("reclaimed.txt", "write\n", "agent-a"), mcpContext),
                new EditBatchTool(shared).execute(batchParams("reclaimed.txt", "original", "batch", "agent-a"), mcpContext),
                new EditPatchTool(shared).execute(patchParams("reclaimed.txt", "original", "patch", "agent-a"), mcpContext),
                new EditPatchTool(shared).execute(patchParams("reclaimed.txt", "original", "patch", null), nativeA));
        for (ToolResult attempt : attempts) {
            assertTrue(attempt.isError(), attempt::getOutput);
            assertTrue(attempt.getOutput().contains("CONFLICT"), attempt::getOutput);
            assertTrue(attempt.getOutput().contains("locked by agent-b"), attempt::getOutput);
            assertTrue(attempt.getOutput().contains("expired"), attempt::getOutput);
            assertEquals("original\n", Files.readString(file), "the file is untouched");
        }
        assertTrue(lockExists(taken.getLockId()), "the new holder keeps the file");
        assertFalse(lockExists(lostLock), "the lost lock is not resurrected");

        // The new holder edits normally.
        ToolContext nativeB = newContext("agent-b");
        nativeB.recordFileRead(file);
        ToolResult owner = new EditTool(shared).execute(editParams("reclaimed.txt", "original", "b", null), nativeB);
        assertFalse(owner.isError(), owner::getOutput);
        assertEquals("b\n", Files.readString(file));
    }

    @Test
    void anEditThatCannotGetTheCoordinatorWithinTheBoundedWaitFailsAndWritesNothing() throws Exception {
        Path file = project.resolve("busy.txt");
        Files.writeString(file, "original\n");
        String lockId = shared.tryAcquireEditLock(file.toAbsolutePath().toString(), "edit", "agent-a").getLockId();
        mcpContext.recordFileRead(file);
        Instant before = leaseOf(lockId);
        clock.advance(Duration.ofSeconds(500));
        Set<String> locksBefore = lockIds();
        AtomicInteger waits = new AtomicInteger();
        shared.onCoordinatorBusyWait(waits::incrementAndGet);
        try {
            try (FileChannel channel = FileChannel.open(coordinatorLockFile(),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock busy = channel.lock()) {
                assertTrue(busy.isValid());
                ToolResult edit = new EditTool(shared).execute(
                        editParams("busy.txt", "original", "changed", "agent-a"), mcpContext);
                assertTrue(edit.isError(), edit::getOutput);
                assertTrue(edit.getOutput().contains("Coordinator busy"), edit::getOutput);
                assertTrue(edit.getOutput().contains("nothing was written"), edit::getOutput);
                assertEquals(1, waits.get(), "the edit waited for the coordinator before giving up");
                assertEquals("original\n", Files.readString(file));

                ToolResult query = tool.execute(queryEdits("agent-a"), mcpContext);
                assertTrue(query.isError(), query::getOutput);
                assertTrue(query.getOutput().contains("Coordinator busy"), query::getOutput);
                ToolResult register = tool.execute(registerEdit("other-file.txt", "agent-a"), mcpContext);
                assertTrue(register.isError(), "an agent-scoped register never fakes success");
                assertTrue(register.getOutput().contains("Coordinator busy"), register::getOutput);
                assertEquals(locksBefore, lockIds(), "nothing was acquired");
                assertFalse(shared.renewAgentLease("agent-a"));
                assertEquals(4, waits.get(), "every call waited before giving up");
                assertEquals(before, leaseOf(lockId), "nothing was renewed");
            }

            // The coordinator is free again: the same edit renews and lands.
            ToolResult retried = new EditTool(shared).execute(
                    editParams("busy.txt", "original", "changed", "agent-a"), mcpContext);
            assertFalse(retried.isError(), retried::getOutput);
            assertEquals("changed\n", Files.readString(file));
            assertFalse(leaseOf(lockId).isBefore(clock.instant().plus(LEASE)));
        } finally {
            shared.onCoordinatorBusyWait(null);
        }
    }

    @Test
    void anEvictedButUnclaimedLockIsReestablishedByTheAgentsNextEdit() throws Exception {
        Path file = project.resolve("evicted.txt");
        Files.writeString(file, "original\n");
        String path = file.toAbsolutePath().toString();
        String lockId = shared.tryAcquireEditLock(path, "edit", "agent-a").getLockId();
        clock.advance(PAST_LEASE);
        assertTrue(shared.queryEdits().stream().noneMatch(lock -> lock.getLockId().equals(lockId)));
        assertFalse(lockExists(lockId), "evicted, but nobody reclaimed the file");

        mcpContext.recordFileRead(file);
        ToolResult edit = new EditTool(shared).execute(
                editParams("evicted.txt", "original", "changed", "agent-a"), mcpContext);
        assertFalse(edit.isError(), edit::getOutput);
        assertEquals("changed\n", Files.readString(file));
        assertTrue(lockExists(lockId), "the active agent holds its file again");
        assertFalse(leaseOf(lockId).isBefore(clock.instant().plus(LEASE)));
        assertStillHeld(path, lockId);
    }

    // ── What does NOT renew a lease ──────────────────────────────────────────

    @Test
    void callsWithoutTheAgentsIdentityDoNotRenewItsLease() throws Exception {
        String path = abs("quiet.txt");
        String lockId = shared.tryAcquireEditLock(path, "edit", "agent-a").getLockId();
        Instant original = leaseOf(lockId);

        clock.advance(Duration.ofSeconds(300));
        // Session-level (no identity) coordinator and edit-tool calls.
        assertFalse(tool.execute(queryEdits(null), mcpContext).isError());
        assertFalse(new WriteTool(shared).execute(writeParams("anon.txt", "x\n", null), mcpContext).isError());
        assertTrue(shared.tryAcquireEditLock(abs("session-level.txt"), "edit").isAcquired());
        assertTrue(shared.renewAgentLease(null));
        assertTrue(shared.renewAgentLease("  "));
        // A sibling agent's activity renews only the sibling.
        assertFalse(tool.execute(queryEdits("agent-b"), mcpContext).isError());
        assertTrue(shared.tryAcquireEditLock(abs("b-only.txt"), "edit", "agent-b").isAcquired());
        // Another session's agent of the same name is a different owner.
        assertTrue(other.renewAgentLease("agent-a"));
        assertTrue(other.tryAcquireEditLock(abs("other-a.txt"), "edit", "agent-a").isAcquired());

        assertEquals(original, leaseOf(lockId), "only agent-a's own activity in its session moves its lease");
        clock.advance(PAST_LEASE.minusSeconds(300));
        assertTrue(shared.tryAcquireEditLock(path, "edit", "agent-b").isAcquired());
        assertFalse(lockExists(lockId));
    }

    @Test
    void sessionLevelLocksHaveNoLeaseAndOutliveAnyClockAdvance() throws Exception {
        String path = abs("session-only.txt");
        String lockId = shared.tryAcquireEditLock(path, "edit").getLockId();
        clock.advance(LEASE.multipliedBy(10));

        assertNull(leaseOf(lockId));
        assertNotNull(other.findConflictingLock(path), "only the session heartbeat ages it out");
        assertTrue(other.tryAcquireEditLock(path, "edit", "agent-c").hasConflict());
        assertTrue(shared.queryEdits().stream().anyMatch(lock -> lock.getLockId().equals(lockId)));
    }

    // ── Lease configuration (harness-config.json, editLockLeaseSeconds) ──────

    @Test
    void leaseLengthComesFromTheCoordinatorsJsonConfig() throws Exception {
        assertEquals(600, new HarnessConfig().getEditLockLeaseSeconds(), "default lease");
        Path config = HarnessConfig.getConfigFilePath();
        assertTrue(config.startsWith(System.getProperty("user.home")), "the temporary user home");
        Files.createDirectories(config.getParent());
        try {
            CoordinationStateManager defaults = new CoordinationStateManager(project, "session-default", om, systemRoot);
            try {
                assertEquals(Duration.ofSeconds(600), defaults.getAgentLease());
            } finally {
                defaults.shutdown();
            }

            Files.writeString(config, "{\"editLockLeaseSeconds\":900}");
            CoordinationStateManager configured = new CoordinationStateManager(project, "session-config", om, systemRoot);
            try {
                assertEquals(Duration.ofSeconds(900), configured.getAgentLease());
            } finally {
                configured.shutdown();
            }

            // A null lease on the explicit constructor also means "the configured lease".
            CoordinationStateManager nullLease = new CoordinationStateManager(
                    project, "session-null", om, systemRoot, null, clock);
            try {
                assertEquals(Duration.ofSeconds(900), nullLease.getAgentLease());
                Instant start = clock.instant();
                String lockId = nullLease.tryAcquireEditLock(abs("configured.txt"), "edit", "agent-a").getLockId();
                assertEquals(start.plusSeconds(900).plusSeconds(15), leaseOf(lockId));
            } finally {
                nullLease.shutdown();
            }

            // Below two heartbeat intervals a live agent could lose its lock between heartbeats.
            Files.writeString(config, "{\"editLockLeaseSeconds\":10}");
            CoordinationStateManager clamped = new CoordinationStateManager(project, "session-clamped", om, systemRoot);
            try {
                assertEquals(Duration.ofSeconds(60), clamped.getAgentLease());
            } finally {
                clamped.shutdown();
            }
            CoordinationStateManager explicitShort = new CoordinationStateManager(
                    project, "session-short", om, systemRoot, Duration.ofSeconds(5), clock);
            try {
                assertEquals(Duration.ofSeconds(60), explicitShort.getAgentLease());
                Instant start = clock.instant();
                String lockId = explicitShort.tryAcquireEditLock(abs("short.txt"), "edit", "agent-a").getLockId();
                // Slack is a quarter of a short lease.
                assertEquals(start.plusSeconds(60).plusSeconds(15), leaseOf(lockId));
            } finally {
                explicitShort.shutdown();
            }
        } finally {
            Files.deleteIfExists(config);
        }
    }

    // ── Releasing one agent's locks ──────────────────────────────────────────

    @Test
    void releaseAgentLocksRemovesOnlyThatAgentsLocksInThisSession() throws Exception {
        String a1 = shared.tryAcquireEditLock(abs("a1.txt"), "edit", "agent-a").getLockId();
        String a2 = shared.tryAcquireEditLock(abs("a2.txt"), "edit", "agent-a").getLockId();
        String b1 = shared.tryAcquireEditLock(abs("b1.txt"), "edit", "agent-b").getLockId();
        String sessionLevel = shared.tryAcquireEditLock(abs("s1.txt"), "edit").getLockId();
        String otherA = other.tryAcquireEditLock(abs("o1.txt"), "edit", "agent-a").getLockId();

        assertEquals(2, shared.releaseAgentLocks("agent-a"));
        assertEquals(Set.of(b1, sessionLevel, otherA), lockIds());
        assertEquals(0, shared.releaseAgentLocks("agent-a"), "idempotent");
        assertEquals(0, shared.releaseAgentLocks(null), "session-level locks are untouched");
        assertEquals(0, shared.releaseAgentLocks(" "));
        assertFalse(lockExists(a1) || lockExists(a2));

        // The runners' entry point goes through the registry's edit_coordinator tool.
        ToolRegistry registry = new ToolRegistry(om);
        assertEquals(0, EditCoordinatorTool.releaseAgentLocks(registry, "agent-b"), "no coordinator");
        assertEquals(0, EditCoordinatorTool.releaseAgentLocks(null, "agent-b"));
        registry.register(tool);
        assertEquals(0, EditCoordinatorTool.releaseAgentLocks(registry, null));
        assertEquals(1, EditCoordinatorTool.releaseAgentLocks(registry, "agent-b"));
        assertEquals(Set.of(sessionLevel, otherA), lockIds());

        // The released agent can lock again later, with a fresh lease.
        Instant now = clock.instant();
        String again = shared.tryAcquireEditLock(abs("a1.txt"), "edit", "agent-a").getLockId();
        assertEquals(now.plus(LEASE).plusSeconds(15), leaseOf(again));
    }
}
