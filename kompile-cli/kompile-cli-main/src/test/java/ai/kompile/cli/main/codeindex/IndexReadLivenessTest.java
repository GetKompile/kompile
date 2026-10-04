package ai.kompile.cli.main.codeindex;

import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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
    void rankedSearchReturnsResultsWithoutMigratingBehindAWalWriter() throws Exception {
        Files.writeString(root.resolve("Alpha.java"), "package example;\npublic class Alpha {}\n");
        LocalCodeIndexer indexer = new LocalCodeIndexer();
        try (PrintStream quiet = new PrintStream(OutputStream.nullOutputStream())) {
            indexer.index(root, project, "*.java", null, false, quiet);
        }
        Path directory = LocalCodeIndexer.getIndexDir(project);
        var readers = Executors.newSingleThreadExecutor();
        try (var writer = IndexDatabase.open(directory)) {
            try (var sql = writer.getConnection().createStatement()) {
                sql.execute("PRAGMA user_version=6");
            }
            writer.beginTransaction();
            writer.clearIndex();
            try {
                assertFalse(readers.submit(() -> CodeRelevanceRanker.rankedSearch(
                        project, "Alpha", directory, root, 10)).get(2, TimeUnit.SECONDS)
                        .results().isEmpty());
                assertFalse(readers.submit(() -> BlendedCodeSearch.search(
                        project, "Alpha", directory, root, 10)).get(2, TimeUnit.SECONDS)
                        .results().isEmpty());
                assertFalse(readers.submit(() -> SignatureExtractor.extractFile(
                        project, "Alpha.java", directory, root)).get(2, TimeUnit.SECONDS)
                        .signatures().isEmpty());
                ToolContext context = mock(ToolContext.class);
                when(context.getWorkingDirectory()).thenReturn(root);
                ObjectMapper mapper = new ObjectMapper();
                for (String action : List.of("callers", "implementors", "trace", "spring_resolve", "debug_trace")) {
                    var params = mapper.createObjectNode().put("action", action).put("project_id", project)
                            .put("query", "example.Alpha").put("auto_refresh", false);
                    ToolResult result = readers.submit(() -> new LocalCodeIndexTool().execute(params, context))
                            .get(2, TimeUnit.SECONDS);
                    assertFalse(result.isError(), action + ": " + result.getOutput());
                }
                for (String action : List.of("symbol", "file")) {
                    var params = mapper.createObjectNode().put("action", action).put("project_id", project)
                            .put("fqn", "example.Alpha").put("file_path", "Alpha.java").put("auto_refresh", false);
                    ToolResult result = readers.submit(() -> new CodeGraphTool(null, mapper).execute(params, context))
                            .get(2, TimeUnit.SECONDS);
                    assertFalse(result.isError(), result.getOutput());
                    assertTrue(result.getOutput().contains("Alpha"), result.getOutput());
                }
            } finally {
                writer.rollback();
            }
            try (var reader = IndexDatabase.openReadOnly(directory);
                 var sql = reader.getConnection().createStatement();
                 var version = sql.executeQuery("PRAGMA user_version")) {
                assertTrue(version.next());
                assertEquals(6, version.getInt(1), "foreground queries must not migrate");
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

    @Test
    void optionalAnalysisQueriesDoNotCreateTablesOrWaitForWriter() throws Exception {
        Path directory = LocalCodeIndexer.getIndexDir(project);
        Files.createDirectories(directory);
        try (var writer = IndexDatabase.open(directory)) {
            writer.beginTransaction();
            writer.setIndexGeneration("uncommitted");
            var readers = Executors.newSingleThreadExecutor();
            try {
                readers.submit(() -> {
                    assertTrue(PageRankComputer.getTopFiles(directory, 10).isEmpty());
                    assertEquals(0.0, PageRankComputer.getFileRank(directory, "Alpha.java"));
                    assertFalse(PageRankComputer.isComputed(directory));
                    assertTrue(CoChangeAnalyzer.getCoChanges(directory, "Alpha.java").isEmpty());
                    assertTrue(CoChangeAnalyzer.getTopCoChanges(directory, 10).isEmpty());
                    assertFalse(CoChangeAnalyzer.hasData(directory));
                    assertTrue(CloneDetector.getClones(directory, 10).isEmpty());
                    assertTrue(CloneDetector.getClonesForFile(directory, "Alpha.java").isEmpty());
                    assertTrue(CloneDetector.getFragments(directory, 10).isEmpty());
                    assertFalse(CloneDetector.hasData(directory));
                    return true;
                }).get(2, TimeUnit.SECONDS);
                try (var snapshot = IndexDatabase.openReadOnly(directory)) {
                    for (String table : List.of("pagerank", "cochanges", "clones", "fragments"))
                        assertFalse(snapshot.hasTable(table), table);
                }
            } finally {
                writer.rollback();
                readers.shutdownNow();
                assertTrue(readers.awaitTermination(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void cloneLookupForACloneFreeFileDoesNotRerunProjectDetection() throws Exception {
        Files.writeString(root.resolve("Alpha.java"), "package example;\npublic class Alpha {}\n");
        try (PrintStream quiet = new PrintStream(OutputStream.nullOutputStream())) {
            new LocalCodeIndexer().index(root, project, "*.java", null, false, quiet);
        }
        Path directory = LocalCodeIndexer.getIndexDir(project);
        try (var writer = IndexDatabase.open(directory)) {
            CloneDetector.ensureTables(writer);
            try (var sql = writer.getConnection().createStatement()) {
                sql.execute("INSERT INTO clones (file_a,name_a,line_a,end_line_a,file_b,name_b,line_b,end_line_b," +
                        "similarity,computed_at) VALUES ('Beta.java','b',1,2,'Gamma.java','g',1,2,0.9,'earlier')");
            }
        }
        assertTrue(CloneDetector.hasData(directory));
        ToolContext context = mock(ToolContext.class);
        when(context.getWorkingDirectory()).thenReturn(root);
        var params = new ObjectMapper().createObjectNode().put("action", "clones").put("project_id", project)
                .put("file_paths", "Alpha.java").put("auto_refresh", false);
        ToolResult result = new LocalCodeIndexTool().execute(params, context);
        assertFalse(result.isError(), result.getOutput());
        assertTrue(result.getOutput().contains("No clones found for: Alpha.java"), result.getOutput());
        // Detection clears and recomputes the table; the stored pair must survive a lookup.
        assertEquals(1, CloneDetector.getClones(directory, 10).size());
    }

    @Test
    void slowGitCollectionCannotBlockRankingOrOtherProjects() throws Exception {
        Files.writeString(root.resolve("Alpha.java"), "package example;\npublic class Alpha {}\n");
        try (PrintStream quiet = new PrintStream(OutputStream.nullOutputStream())) {
            new LocalCodeIndexer().index(root, project, "*.java", null, false, quiet);
        }
        CountDownLatch collecting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        Path otherRoot = root.resolve("another-project");
        GitSignals.setSignalLoaderForTests(path -> {
            if (!path.equals(root.toAbsolutePath().normalize()))
                return Map.of("Other.java", new GitSignals.FileSignals(1.0, 3, 2));
            calls.incrementAndGet();
            collecting.countDown();
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS));
                return Map.of("Alpha.java", new GitSignals.FileSignals(1.0, 5, 2));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Map.of();
            } finally { finished.countDown(); }
        });
        var readers = Executors.newSingleThreadExecutor();
        try {
            assertTrue(GitSignals.snapshot(root).isEmpty());
            assertTrue(collecting.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 3; i++) {
                assertFalse(readers.submit(() -> CodeRelevanceRanker.rankedSearch(project, "Alpha",
                        LocalCodeIndexer.getIndexDir(project), root, 10)).get(2, TimeUnit.SECONDS).results().isEmpty());
                assertFalse(readers.submit(() -> BlendedCodeSearch.search(project, "Alpha",
                        LocalCodeIndexer.getIndexDir(project), root, 10)).get(2, TimeUnit.SECONDS).results().isEmpty());
            }
            assertEquals(1, release.getCount(), "search must finish while Git is still held");
            assertEquals(1, calls.get(), "one collector per project");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (GitSignals.snapshot(otherRoot).isEmpty() && System.nanoTime() < deadline) Thread.sleep(1);
            assertTrue(GitSignals.snapshot(otherRoot).containsKey("Other.java"), "different projects must not share a monitor");
            release.countDown();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (GitSignals.snapshot(root).isEmpty() && System.nanoTime() < deadline) Thread.sleep(1);
            assertTrue(GitSignals.snapshot(root).containsKey("Alpha.java"));
            assertFalse(GitSignals.snapshot(root).containsKey("Other.java"));
        } finally {
            release.countDown();
            finished.await(2, TimeUnit.SECONDS);
            GitSignals.setSignalLoaderForTests(null);
            readers.shutdownNow();
            assertTrue(readers.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void gitLifetimeBoundStartsBeforeReadingAnOpenPipe() throws Exception {
        var readers = Executors.newSingleThreadExecutor();
        try (PipedInputStream input = new PipedInputStream();
             PipedOutputStream output = new PipedOutputStream(input)) {
            Process process = mock(Process.class);
            AtomicBoolean alive = new AtomicBoolean(true);
            when(process.getInputStream()).thenReturn(input);
            when(process.isAlive()).thenAnswer(invocation -> alive.get());
            when(process.waitFor(25, TimeUnit.MILLISECONDS)).thenReturn(false);
            Path captured = root.resolve("git-output.log");
            Files.writeString(captured, "");
            when(process.destroyForcibly()).thenAnswer(invocation -> {
                alive.set(false);
                // Simulate a descendant retaining stdout even after parent termination.
                return process;
            });
            IOException failure = readers.submit(() -> {
                try { GitCommandOutput.awaitOutput(process, captured, 25); }
                catch (IOException e) { return e; }
                throw new AssertionError("collection must stop at its deadline");
            }).get(2, TimeUnit.SECONDS);
            assertTrue(failure.getMessage().contains("lifetime"));
            verify(process, never()).getInputStream();
            verify(process, atLeastOnce()).destroyForcibly();
            assertFalse(alive.get());
        } finally {
            readers.shutdownNow();
            assertTrue(readers.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void gitOutputSnapshotDoesNotReadInheritedStdoutAndCancellationCleansUp() throws Exception {
        Path captured = root.resolve("completed-git.log");
        Files.writeString(captured, "author@example.com\nAlpha.java\n");
        Process completed = mock(Process.class);
        when(completed.waitFor(25, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(completed.exitValue()).thenReturn(0);
        var signals = GitSignals.parseSignals(GitCommandOutput.awaitOutput(completed, captured, 25));
        assertEquals(1, signals.get("Alpha.java").commitCount());
        verify(completed, never()).getInputStream();

        Process interrupted = mock(Process.class);
        when(interrupted.waitFor(25, TimeUnit.MILLISECONDS)).thenThrow(new InterruptedException());
        when(interrupted.isAlive()).thenReturn(true);
        ProcessHandle child = mock(ProcessHandle.class);
        when(interrupted.descendants()).thenReturn(java.util.stream.Stream.of(child));
        assertThrows(InterruptedException.class, () -> GitCommandOutput.awaitOutput(interrupted, captured, 25));
        verify(interrupted).destroyForcibly();
        verify(child).destroyForcibly();
        verify(interrupted, never()).getInputStream();
    }

    @Test
    void rankingUsesSelectedIndexForPageRankAndCoChanges() throws Exception {
        Files.writeString(root.resolve("Alpha.java"), "package example;\npublic class Alpha {}\n");
        Files.writeString(root.resolve("AlphaPeer.java"), "package other;\npublic class Alpha {}\n");
        try (PrintStream quiet = new PrintStream(OutputStream.nullOutputStream())) {
            new LocalCodeIndexer().index(root, project, "*.java", null, false, quiet);
        }
        Path directory = LocalCodeIndexer.getIndexDir(project);
        GitSignals.setSignalLoaderForTests(path -> Map.of());
        try {
            double original = CodeRelevanceRanker.rankedSearch(project, "Alpha", directory, root, 10)
                    .results().stream().filter(result -> result.filePath().equals("Alpha.java")).findFirst().orElseThrow().score();
            try (var writer = IndexDatabase.open(directory)) {
                PageRankComputer.ensureTable(writer);
                CoChangeAnalyzer.ensureTable(writer);
                try (var sql = writer.getConnection().createStatement()) {
                    sql.execute("INSERT INTO pagerank VALUES ('Alpha.java', 1.0, 'test')");
                    sql.execute("INSERT INTO cochanges(file_a,file_b,count,confidence,computed_at) " +
                            "VALUES ('Alpha.java','AlphaPeer.java',5,1.0,'test')");
                }
            }
            assertEquals(1.025, GitSignals.coChangeBoost("Alpha.java", Set.of("AlphaPeer.java"), directory), 1e-6);
            var ranked = CodeRelevanceRanker.rankedSearch(project, "Alpha", directory, root, 10);
            assertEquals(2, ranked.results().size(), ranked.toString());
            var result = ranked.results().stream().filter(row -> row.filePath().equals("Alpha.java")).findFirst().orElseThrow();
            assertEquals(original * 1.3 * 1.025, result.score(), 1e-6);
            assertEquals(1.025, result.scoreBreakdown().get("coChangeBoost"), 1e-6);

            // The same project id and root with a different selected database must
            // not reuse canonical/global analysis signals from the first request.
            Path alternative = root.resolve("alternative-index");
            Files.createDirectories(alternative);
            try (var originalDb = IndexDatabase.openReadOnly(directory);
                 var otherDb = IndexDatabase.open(alternative)) {
                for (var entity : originalDb.search("Alpha", null, 10))
                    otherDb.insertEntities(entity.get("filePath").toString(), List.of(entity));
                PageRankComputer.ensureTable(otherDb);
                CoChangeAnalyzer.ensureTable(otherDb);
                try (var sql = otherDb.getConnection().createStatement()) {
                    sql.execute("INSERT INTO pagerank VALUES ('AlphaPeer.java', 1.0, 'test')");
                }
            }
            for (int i = 0; i < 3; i++) {
                var isolated = CodeRelevanceRanker.rankedSearch(project, "Alpha", alternative, root, 10)
                        .results().stream().filter(row -> row.filePath().equals("Alpha.java")).findFirst().orElseThrow();
                assertEquals(original, isolated.score(), 1e-6);
                assertFalse(isolated.scoreBreakdown().containsKey("coChangeBoost"));
                var canonical = CodeRelevanceRanker.rankedSearch(project, "Alpha", directory, root, 10)
                        .results().stream().filter(row -> row.filePath().equals("Alpha.java")).findFirst().orElseThrow();
                assertEquals(result.score(), canonical.score(), 1e-6);
            }
        } finally { GitSignals.setSignalLoaderForTests(null); }
    }

    private static void restoreProperty(String previous) {
        if (previous == null) System.clearProperty("kompile.codeIndex.lockWaitMs");
        else System.setProperty("kompile.codeIndex.lockWaitMs", previous);
    }
}
