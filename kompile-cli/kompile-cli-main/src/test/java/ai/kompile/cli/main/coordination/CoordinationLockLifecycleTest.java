package ai.kompile.cli.main.coordination;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** The ledger must not retain edit locks after their session ends. */
@TemporaryUserHome
class CoordinationLockLifecycleTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = JsonUtils.standardMapper();
    private final List<CoordinationStateManager> managers = new ArrayList<>();

    private CoordinationStateManager manager(String session) {
        var manager = new CoordinationStateManager(directory, session, mapper, directory.resolve("system"));
        managers.add(manager);
        return manager;
    }

    private String acquire(CoordinationStateManager manager, String file, String agent) {
        var result = manager.tryAcquireEditLock(directory.resolve(file).toString(), "edit", agent);
        assertTrue(result.isAcquired(), result::getConflictMessage);
        assertTrue(Files.exists(lockFile(result.getLockId())));
        return result.getLockId();
    }

    private Path lockFile(String id) {
        return directory.resolve(".kompile/coordination/edits").resolve(id + ".lock.json");
    }

    private Path agentFile(String session) {
        return directory.resolve(".kompile/coordination/agents").resolve(session + ".agent.json");
    }

    @AfterEach
    void closeManagers() {
        managers.forEach(CoordinationStateManager::shutdown);
    }

    @Test
    void shutdownReleasesPersistedSessionAndAgentLocksButNotAnotherSession() {
        var owner = manager("ending");
        var peer = manager("ending"); // A second connection contributed locks for this session.
        var other = manager("ending-sibling"); // Prefix match must not count as ownership.
        owner.registerAgent("work", null, "owner", 0, ProcessHandle.current().pid());
        String own = acquire(owner, "own.txt", null);
        String child = acquire(peer, "child.txt", "agent-a");
        String survivor = acquire(other, "survivor.txt", "agent-a");

        owner.shutdown();
        owner.shutdown();

        assertFalse(Files.exists(lockFile(own)));
        assertFalse(Files.exists(lockFile(child)), "cleanup must scan the ledger, not just local IDs");
        assertFalse(Files.exists(agentFile("ending")));
        assertTrue(Files.exists(lockFile(survivor)), "another session's locks must survive");
    }

    @Test
    void deregisterChildReleasesOnlyTheEndedSessionEvenWithoutPresence() {
        var parent = manager("parent");
        var child = manager("child");
        String own = acquire(parent, "parent.txt", null);
        String childLock = acquire(child, "child.txt", "worker");
        // Missing presence is deliberately inconclusive during normal lock probes, but
        // an explicit terminal callback is authoritative even without registration.
        parent.deregisterAgent("child");
        parent.deregisterAgent("child");
        assertFalse(Files.exists(lockFile(childLock)));
        assertTrue(Files.exists(lockFile(own)));
    }

    @Test
    void queryingAgentsReleasesLocksBeforeDiscardingStalePresence() throws Exception {
        var observer = manager("observer");
        var stale = manager("stale");
        stale.registerAgent("work", null, "worker", 0, ProcessHandle.current().pid());
        String id = acquire(stale, "stale.txt", null);
        AgentEntry entry = mapper.readValue(agentFile("stale").toFile(), AgentEntry.class);
        entry.setLastHeartbeat(Instant.now().minusSeconds(3600));
        mapper.writeValue(agentFile("stale").toFile(), entry);

        assertTrue(observer.queryAgents(true).stream().anyMatch(a -> a.getSessionId().equals("stale")));
        assertTrue(Files.exists(lockFile(id)), "include-stale is an observational query");
        assertTrue(observer.queryAgents().isEmpty());
        assertFalse(Files.exists(agentFile("stale")));
        assertFalse(Files.exists(lockFile(id)), "fresh lock TTL must not hide an ended holder");
    }

    @Test
    void queryingAgentsReleasesLocksOfProvablyDeadPid() {
        var observer = manager("observer");
        var dead = manager("dead");
        dead.registerAgent("work", null, "worker", 0, Long.MAX_VALUE);
        String id = acquire(dead, "dead.txt", "worker");
        assertTrue(observer.queryAgents().isEmpty());
        assertFalse(Files.exists(lockFile(id)));
        assertFalse(Files.exists(agentFile("dead")));
    }

    @Test
    void lateToolCallsCannotResurrectLocksOrPresenceAfterShutdown() {
        var owner = manager("closed");
        String id = acquire(owner, "old.txt", "worker");
        owner.shutdown();

        assertFalse(owner.tryAcquireEditLock(directory.resolve("late.txt").toString(), "edit").isAcquired());
        assertFalse(owner.tryAcquireEditLock(directory.resolve("late-agent.txt").toString(), "edit", "worker").isAcquired());
        for (String agent : new String[]{null, "worker"}) {
            var batch = owner.tryAcquireEditLocks(List.of(directory.resolve("batch.txt").toString()), "edit", agent, false);
            assertTrue(batch.aborted());
            assertTrue(batch.results().values().stream().noneMatch(EditLockResult::isAcquired));
        }
        assertFalse(owner.renewAgentLease("worker"));
        assertTrue(owner.guardAgentEdit("worker", List.of(directory.resolve("old.txt").toString())).isBusy());
        assertTrue(owner.guardSessionEdit(List.of(directory.resolve("auto.txt").toString())).isBusy());
        owner.registerAgent("late", null, "worker", 0, ProcessHandle.current().pid());
        assertFalse(Files.exists(agentFile("closed")));
        assertFalse(Files.exists(lockFile(id)));
        assertTrue(owner.queryEdits().isEmpty());
    }

    @Test
    @Timeout(15)
    void queuedAcquisitionCannotCommitAfterShutdownBegins() throws Exception {
        var owner = manager("queued");
        acquire(owner, "before.txt", "worker");
        owner.setEditLockWaitMillis(5000);
        CountDownLatch waiting = new CountDownLatch(1);
        owner.onCoordinatorBusyWait(waiting::countDown);
        var executor = Executors.newFixedThreadPool(2);
        try (FileChannel channel = FileChannel.open(directory.resolve(".kompile/coordination/.coordinator.lock"),
                StandardOpenOption.WRITE)) {
            var held = channel.lock();
            try {
                var acquire = executor.submit(() -> owner.tryAcquireEditLock(
                        directory.resolve("queued.txt").toString(), "edit", "worker"));
                assertTrue(waiting.await(5, TimeUnit.SECONDS));
                var shutdown = executor.submit(owner::shutdown);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (owner.renewAgentLease(null) && System.nanoTime() < deadline) Thread.sleep(1);
                assertFalse(owner.renewAgentLease(null), "shutdown must have started before the queue drains");
                held.release();
                assertFalse(acquire.get(5, TimeUnit.SECONDS).isAcquired());
                shutdown.get(5, TimeUnit.SECONDS);
                assertTrue(owner.queryEdits().isEmpty());
            } finally {
                if (held.isValid()) held.release();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @Timeout(15)
    void writeAlreadyInProgressCannotOutliveInterruptedShutdownCleanup() throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch finishWrite = new CountDownLatch(1);
        ObjectMapper blockedWriter = new ObjectMapper() {
            @Override public ObjectMapper copy() { return this; }
            @Override public void writeValue(File file, Object value) throws IOException {
                if (value instanceof EditLockEntry) {
                    writing.countDown();
                    try {
                        if (!finishWrite.await(10, TimeUnit.SECONDS)) throw new IOException("test write timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException(interrupted);
                    }
                }
                super.writeValue(file, value);
            }
        };
        var owner = new CoordinationStateManager(directory, "in-flight", blockedWriter, directory.resolve("system"));
        managers.add(owner);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var acquiring = executor.submit(() -> owner.tryAcquireEditLock(
                    directory.resolve("in-flight.txt").toString(), "edit", "worker"));
            assertTrue(writing.await(5, TimeUnit.SECONDS), "acquire must be inside its critical section");
            Thread.currentThread().interrupt();
            try {
                owner.shutdown(); // Interrupted coordinator wait forces the direct cleanup fallback.
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
            finishWrite.countDown();
            assertFalse(acquiring.get(5, TimeUnit.SECONDS).isAcquired(), "an ended session cannot finish acquiring");
            assertTrue(owner.queryEdits().isEmpty(), "the write must not land after fallback cleanup");
        } finally {
            finishWrite.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @Timeout(15)
    void busyCoordinatorCannotPreventTerminalLockRelease() throws Exception {
        var owner = manager("busy");
        String id = acquire(owner, "busy.txt", "worker");
        try (FileChannel channel = FileChannel.open(directory.resolve(".kompile/coordination/.coordinator.lock"),
                StandardOpenOption.WRITE); var ignored = channel.lock()) {
            owner.shutdown();
            assertFalse(Files.exists(lockFile(id)), "terminal cleanup has an owner-scoped fallback");
        }
    }

    @Test
    void interruptedShutdownStillReleasesLocksAndPreservesInterruption() {
        var owner = manager("interrupted");
        String id = acquire(owner, "interrupted.txt", "worker");
        Thread.currentThread().interrupt();
        try {
            owner.shutdown();
            assertFalse(Files.exists(lockFile(id)));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted(); // Do not leak the test's interruption into JUnit.
        }
    }

    @Test
    @Timeout(30)
    void jvmExitAutomaticallyReleasesLocksWithoutExplicitClose() throws Exception {
        String survivor = acquire(manager("survivor"), "keep.txt", "worker");
        Process child = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx128m",
                "-Duser.home=" + System.getProperty("user.home"), "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                ExitFixture.class.getName(), directory.toString())
                .redirectErrorStream(true).start();
        try {
            assertTrue(child.waitFor(20, TimeUnit.SECONDS), "fixture must not hang on JVM exit");
            String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, child.exitValue(), output);
            assertTrue(output.contains("locked-before-exit"), output);
            assertFalse(Files.exists(agentFile("exiting")));
            assertEquals(List.of(survivor), manager("observer").queryEdits().stream()
                    .map(EditLockEntry::getLockId).toList());
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
    }

    public static class ExitFixture {
        public static void main(String[] args) {
            Path root = Path.of(args[0]);
            var coordinator = new CoordinationStateManager(root, "exiting", JsonUtils.standardMapper(), root.resolve("system"));
            coordinator.registerAgent("fixture", null, "worker", 0, ProcessHandle.current().pid());
            var lock = coordinator.tryAcquireEditLock(root.resolve("exiting.txt").toString(), "edit", "worker");
            if (!lock.isAcquired()) throw new AssertionError("fixture failed to acquire its lock");
            System.out.println("locked-before-exit");
            System.exit(0); // Deliberately no finally/close: the manager owns JVM-exit cleanup.
        }
    }
}
