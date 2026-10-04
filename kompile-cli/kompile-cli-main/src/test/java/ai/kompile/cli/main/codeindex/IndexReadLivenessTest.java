package ai.kompile.cli.main.codeindex;

import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Same-JVM qualification: background writers must not make foreground reads uncancellable. */
@TemporaryUserHome
@Timeout(15)
class IndexReadLivenessTest {
    @TempDir Path root;
    private final String project = "read-liveness-" + UUID.randomUUID();

    @Test
    void foregroundReadersSeeCommittedWalSnapshotWhileWriterHoldsProjectLock() throws Exception {
        Path source = root.resolve("Alpha.java");
        Files.writeString(source, "package example;\npublic class Alpha {}\n");
        LocalCodeIndexer indexer = new LocalCodeIndexer();
        try (PrintStream quiet = new PrintStream(OutputStream.nullOutputStream())) {
            indexer.index(root, project, "*.java", null, false, quiet);
        }
        var readers = Executors.newSingleThreadExecutor();
        try (var lock = IndexLockManager.acquireWriteLock(project, LocalCodeIndexer.getIndexDir(project));
             var writer = IndexDatabase.open(LocalCodeIndexer.getIndexDir(project))) {
            String generation = writer.getIndexGeneration();
            writer.beginTransaction();
            writer.clearIndex();
            writer.setIndexGeneration("not-yet-committed");
            try {
                // Each future must finish BEFORE the writer releases either lock or transaction.
                assertFalse(readers.submit(() -> indexer.search(project, "Alpha", "CLASS", 10))
                        .get(2, TimeUnit.SECONDS).isEmpty());
                assertFalse(readers.submit(() -> indexer.entitiesForFile(project, "Alpha.java", 10))
                        .get(2, TimeUnit.SECONDS).isEmpty());
                assertFalse(readers.submit(() -> new SpathResolver(project).resolve("example.Alpha", 10))
                        .get(2, TimeUnit.SECONDS).matches().isEmpty());
                FileContextService.ContextSnapshot context = readers.submit(
                        () -> new FileContextService().lookup(source, root)).get(2, TimeUnit.SECONDS);
                assertTrue(context.available(), context.toString());
                assertEquals("AVAILABLE", context.indexStatus());
                assertFalse(context.symbols().isEmpty(), context.toString());
                try (var snapshot = IndexDatabase.openReadOnly(LocalCodeIndexer.getIndexDir(project))) {
                    assertEquals(generation, snapshot.getIndexGeneration());
                }
            } finally {
                writer.rollback();
            }
        } finally {
            readers.shutdownNow();
            assertTrue(readers.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void foregroundStatsDoNotMigrateAnOlderReadableIndex() throws Exception {
        Files.writeString(root.resolve("Alpha.java"), "package example;\npublic class Alpha {}\n");
        LocalCodeIndexer indexer = new LocalCodeIndexer();
        try (PrintStream quiet = new PrintStream(OutputStream.nullOutputStream())) {
            indexer.index(root, project, "*.java", null, false, quiet);
        }
        Path directory = LocalCodeIndexer.getIndexDir(project);
        try (var writer = IndexDatabase.open(directory);
             var sql = writer.getConnection().createStatement()) {
            sql.execute("PRAGMA user_version=6");
        }
        assertEquals(root.toAbsolutePath().toString(), indexer.getMetadata(project).get("rootPath"));
        assertTrue(((Number) indexer.getStats(project).get("entitiesFound")).intValue() > 0);
        try (var reader = IndexDatabase.openReadOnly(directory);
             var sql = reader.getConnection().createStatement();
             var version = sql.executeQuery("PRAGMA user_version")) {
            assertTrue(version.next());
            assertEquals(6, version.getInt(1), "stats must not run schema migration");
        }
    }

    @Test
    void slowWatcherRegistrationCannotHoldForegroundStateMonitor() throws Exception {
        Files.writeString(root.resolve("Alpha.java"), "package example;\npublic class Alpha {}\n");
        String oldBackground = System.getProperty("KOMPILE_CODE_INDEX_BACKGROUND");
        String oldWatch = System.getProperty("KOMPILE_CODE_INDEX_WATCH");
        CountDownLatch registering = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<IndexFileWatcher> registeredWatcher = new AtomicReference<>();
        var readers = Executors.newSingleThreadExecutor();
        BackgroundIndexService.resetForTests();
        System.setProperty("KOMPILE_CODE_INDEX_BACKGROUND", "true");
        System.setProperty("KOMPILE_CODE_INDEX_WATCH", "true");
        try {
            BackgroundIndexService service = BackgroundIndexService.getInstance();
            service.setProjectionPublisherForTests((path, id, includes, excludes) ->
                    LocalCodeKGraphPublisher.ProjectionResult.skipped("test: no projection"));
            service.setWatcherStarterForTests(watcher -> {
                registering.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                    watcher.start();
                    registeredWatcher.set(watcher);
                } finally {
                    finished.countDown();
                }
            });
            BackgroundIndexService.IndexJob job = service.submitIndexJob(root, project, "*.java", null, false);
            assertTrue(registering.await(3, TimeUnit.SECONDS));
            assertTrue(readers.submit(() -> service.awaitJob(job, 50)).get(1, TimeUnit.SECONDS));
            readers.submit(() -> service.prepareForRead(new LocalCodeIndexer(), project, root))
                    .get(1, TimeUnit.SECONDS);
            // Both calls finished while registration is still deliberately blocked.
            assertEquals(1, release.getCount());
            // Removal while registration is in flight must not publish a resurrected watcher.
            Files.writeString(LocalCodeIndexer.getIndexDir(project).resolve(LocalCodeIndexer.REMOVAL_MARKER),
                    root.toRealPath().toString());
            service.retireRemovedProject(project);
            release.countDown();
            assertTrue(finished.await(3, TimeUnit.SECONDS));
            assertNotNull(registeredWatcher.get());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (registeredWatcher.get().isRunning() && System.nanoTime() < deadline) Thread.sleep(1);
            assertFalse(registeredWatcher.get().isRunning(), "removed project must dispose the new watcher");
            assertFalse(service.isWatching(project));
        } finally {
            release.countDown();
            finished.await(3, TimeUnit.SECONDS);
            readers.shutdownNow();
            BackgroundIndexService.resetForTests();
            if (oldBackground == null) System.clearProperty("KOMPILE_CODE_INDEX_BACKGROUND");
            else System.setProperty("KOMPILE_CODE_INDEX_BACKGROUND", oldBackground);
            if (oldWatch == null) System.clearProperty("KOMPILE_CODE_INDEX_WATCH");
            else System.setProperty("KOMPILE_CODE_INDEX_WATCH", oldWatch);
            assertTrue(readers.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void contendedReadAndWriteAcquisitionHaveBoundedWaitsAndDoNotLeakLocks() throws Exception {
        String previous = System.getProperty("kompile.codeIndex.lockWaitMs");
        System.setProperty("kompile.codeIndex.lockWaitMs", "25");
        var contenders = Executors.newSingleThreadExecutor();
        try {
            Path directory = LocalCodeIndexer.getIndexDir(project);
            try (var owner = IndexLockManager.acquireWriteLock(project, directory)) {
                IOException readFailure = contenders.submit(() -> assertThrows(IOException.class,
                        () -> IndexLockManager.acquireReadLock(project))).get(2, TimeUnit.SECONDS);
                assertTrue(readFailure.getMessage().contains("lock wait exceeded"));
                IOException writeFailure = contenders.submit(() -> assertThrows(IOException.class,
                        () -> IndexLockManager.acquireWriteLock(project, directory))).get(2, TimeUnit.SECONDS);
                assertTrue(writeFailure.getMessage().contains("lock wait exceeded"));
            }
            assertTrue(contenders.submit(() -> {
                try (var retry = IndexLockManager.acquireWriteLock(project, directory)) { return true; }
            }).get(2, TimeUnit.SECONDS));
        } finally {
            contenders.shutdownNow();
            assertTrue(contenders.awaitTermination(2, TimeUnit.SECONDS));
            restoreProperty(previous);
        }
    }

    @Test
    void cancellationInterruptsContendedReadAndWriteWaits() throws Exception {
        String previous = System.getProperty("kompile.codeIndex.lockWaitMs");
        System.setProperty("kompile.codeIndex.lockWaitMs", "30000");
        try {
            Path directory = LocalCodeIndexer.getIndexDir(project);
            for (boolean write : List.of(false, true)) {
                CountDownLatch entered = new CountDownLatch(1);
                AtomicReference<Throwable> outcome = new AtomicReference<>();
                Thread waiter = new Thread(() -> {
                    entered.countDown();
                    try (var ignored = write ? IndexLockManager.acquireWriteLock(project, directory)
                            : IndexLockManager.acquireReadLock(project)) {
                        outcome.set(new AssertionError("contended lock unexpectedly acquired"));
                    } catch (Throwable failure) {
                        outcome.set(Thread.currentThread().isInterrupted() ? failure
                                : new AssertionError("interrupt flag lost", failure));
                    }
                }, "index-lock-cancellation-test");
                // Daemon is a safety net on regressions; releasing owner in finally unblocks old lock().
                waiter.setDaemon(true);
                try (var owner = IndexLockManager.acquireWriteLock(project, directory)) {
                    waiter.start();
                    assertTrue(entered.await(2, TimeUnit.SECONDS));
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    while (!IndexLockManager.lockFor(project).hasQueuedThread(waiter)
                            && waiter.isAlive() && System.nanoTime() < deadline) {
                        Thread.sleep(1);
                    }
                    assertTrue(IndexLockManager.lockFor(project).hasQueuedThread(waiter),
                            "interrupt must arrive after lock acquisition is queued");
                    waiter.interrupt();
                    waiter.join(2000);
                    assertFalse(waiter.isAlive(), "cancel must finish while owner still holds lock");
                    assertInstanceOf(InterruptedIOException.class, outcome.get());
                } finally {
                    waiter.interrupt();
                    waiter.join(2000);
                }
            }
        } finally {
            restoreProperty(previous);
        }
    }

    @Test
    void readToWriteUpgradeFailsRatherThanSelfDeadlocking() throws Exception {
        try (var read = IndexLockManager.acquireReadLock(project)) {
            IOException failure = assertThrows(IOException.class,
                    () -> IndexLockManager.acquireWriteLock(project, LocalCodeIndexer.getIndexDir(project)));
            assertTrue(failure.getMessage().contains("Cannot upgrade"));
        }
        try (var retry = IndexLockManager.acquireWriteLock(project, LocalCodeIndexer.getIndexDir(project))) {
            assertNotNull(retry);
        }
    }

    private static void restoreProperty(String previous) {
        if (previous == null) System.clearProperty("kompile.codeIndex.lockWaitMs");
        else System.setProperty("kompile.codeIndex.lockWaitMs", previous);
    }
}
