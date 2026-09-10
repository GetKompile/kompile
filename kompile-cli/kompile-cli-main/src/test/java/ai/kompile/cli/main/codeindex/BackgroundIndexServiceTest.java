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

package ai.kompile.cli.main.codeindex;

import ai.kompile.cli.main.chat.tools.grounding.CodeGraphLearningRunner;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link BackgroundIndexService}: background index jobs,
 * write-notification-driven incremental refresh with read-your-writes joins,
 * and watcher auto-start.
 *
 * <p>Runs against an isolated temporary {@code user.home}; production root
 * discovery is also exercised with one deliberately malformed sibling index.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class BackgroundIndexServiceTest {

    private static final String PROJECT_ID = "background-index-test-" + System.nanoTime();
    private static final String DISABLED_PROJECT_ID = PROJECT_ID + "-disabled";
    private static final String ALIAS_PROJECT_ID = PROJECT_ID + "-alias";
    private static final String CORRUPT_PROJECT_ID = PROJECT_ID + "-corrupt";
    @TempDir
    static Path testHome;
    private static String previousUserHome;
    private static Path projectDir;
    private static Path disabledProjectDir;

    @BeforeAll
    static void setUp() throws Exception {
        previousUserHome = System.getProperty("user.home");
        System.setProperty("user.home", testHome.toString());
        // Generous join budget so slow CI boxes don't flake read-your-writes,
        // and no watchers by default — individual tests opt in.
        System.setProperty("KOMPILE_CODE_INDEX_READ_WAIT_MS", "15000");
        System.setProperty("KOMPILE_CODE_INDEX_WATCH", "false");
        BackgroundIndexService.resetForTests();

        projectDir = Files.createTempDirectory("background-index-project");
        Files.writeString(projectDir.resolve("Alpha.java"), """
                package com.example;
                public class Alpha {
                    public void greet() {}
                }
                """);
        disabledProjectDir = Files.createTempDirectory("background-index-disabled");
        Files.writeString(disabledProjectDir.resolve("Delta.java"), """
                package com.example;
                public class Delta {}
                """);
        Path corruptIndex = LocalCodeIndexer.getIndexDir(CORRUPT_PROJECT_ID);
        Files.createDirectories(corruptIndex);
        Files.writeString(corruptIndex.resolve("metadata.json"), "{not valid json");
    }

    @AfterAll
    static void tearDown() throws IOException {
        try {
            BackgroundIndexService.resetForTests();
            System.clearProperty("KOMPILE_CODE_INDEX_READ_WAIT_MS");
            System.clearProperty("KOMPILE_CODE_INDEX_WATCH");
            System.clearProperty("KOMPILE_CODE_INDEX_BACKGROUND");
            System.clearProperty("KOMPILE_CODE_INDEX_LEARNING_DEBOUNCE_MS");
            System.clearProperty("KOMPILE_CODE_INDEX_LEARNING_MAX_WAIT_MS");
            deleteRecursively(LocalCodeIndexer.getIndexDir(PROJECT_ID));
            deleteRecursively(LocalCodeIndexer.getIndexDir(DISABLED_PROJECT_ID));
            deleteRecursively(LocalCodeIndexer.getIndexDir(ALIAS_PROJECT_ID));
            deleteRecursively(LocalCodeIndexer.getIndexDir(CORRUPT_PROJECT_ID));
            deleteRecursively(projectDir);
            deleteRecursively(disabledProjectDir);
        } finally {
            if (previousUserHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousUserHome);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                }
            });
        }
    }

    private static void awaitTrue(BooleanSupplier condition, long timeoutMs, String what) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for: " + what);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted waiting for: " + what);
            }
        }
    }

    @Test
    @Order(1)
    void backgroundJobIndexesAndCompletes() throws Exception {
        BackgroundIndexService service = BackgroundIndexService.getInstance();
        BackgroundIndexService.IndexJob job =
                service.submitIndexJob(projectDir, PROJECT_ID, null, null, false);

        assertTrue(service.awaitJob(job, 30_000), "background index job should finish");
        assertEquals(BackgroundIndexService.JobStatus.COMPLETED, job.status());
        assertNotNull(job.result());
        assertTrue(job.result().entitiesFound() > 0, "fixture class should be indexed");
        awaitTrue(() -> job.projection() != null && job.learning() != null, 30_000,
                "background KGraph projection and configured-learning decision");
        assertEquals("DISABLED", job.learning().status(),
                "default config must make an explicit, non-heavy learning decision");
        UnifiedGraph projected = UnifiedGraph.load(job.projection().graphPath());
        assertEquals("STALE", projected.meta().get("phase.codeLearning." + PROJECT_ID));
        assertEquals(PROJECT_ID, service.projectForPath(projectDir.resolve("Alpha.java")),
                "a completed index must immediately populate write-notification root routing");

        List<Map<String, Object>> hits =
                new LocalCodeIndexer().search(PROJECT_ID, "Alpha", null, 10);
        assertFalse(hits.isEmpty(), "search should hit the background-built index");

        List<BackgroundIndexService.IndexJob> jobs = service.jobs(PROJECT_ID);
        assertFalse(jobs.isEmpty(), "completed job should be listed");
        assertEquals(job.id(), jobs.get(0).id());
        assertNotNull(service.statusLine(PROJECT_ID));
    }

    @Test
    @Order(2)
    void concurrentSubmitDeduplicatesPerProject() throws Exception {
        BackgroundIndexService service = BackgroundIndexService.getInstance();
        Path indexDir = LocalCodeIndexer.getIndexDir(PROJECT_ID);

        // Hold the project write lock so the first job blocks mid-flight.
        try (IndexLockManager.LockToken ignored =
                     IndexLockManager.acquireWriteLock(PROJECT_ID, indexDir)) {
            BackgroundIndexService.IndexJob first =
                    service.submitIndexJob(projectDir, PROJECT_ID, null, null, false);
            BackgroundIndexService.IndexJob second =
                    service.submitIndexJob(projectDir, PROJECT_ID, null, null, false);
            assertSame(first, second, "active job should be reused, not duplicated");
            assertSame(first, service.activeJob(PROJECT_ID));
        }

        awaitTrue(() -> service.activeJob(PROJECT_ID) == null, 30_000,
                "deduped job to finish after lock release");
    }

    @Test
    @Order(3)
    void writeNotificationTriggersRefreshAndReadJoinsIt() throws Exception {
        BackgroundIndexService service = BackgroundIndexService.getInstance();
        LocalCodeIndexer indexer = new LocalCodeIndexer();

        Files.writeString(projectDir.resolve("Zebra.java"), """
                package com.example;
                public class Zebra {
                    public void stripe() {}
                }
                """);
        service.noteFileWritten(projectDir.resolve("Zebra.java"));

        // The read must join the debounced background refresh and then see
        // the entity — the read-your-writes contract.
        service.prepareForRead(indexer, PROJECT_ID);
        List<Map<String, Object>> hits = indexer.search(PROJECT_ID, "Zebra", null, 10);
        assertFalse(hits.isEmpty(),
                "search right after a write notification should see the new class");
    }

    @Test
    @Order(4)
    void writeOutsideAnyIndexedRootIsIgnored() {
        BackgroundIndexService service = BackgroundIndexService.getInstance();
        // Must not throw or schedule anything for an unrelated path.
        service.noteFileWritten(Path.of("/definitely/not/an/indexed/root/File.java"));
        assertNull(service.activeJob("not-an-indexed-project"));
    }

    @Test
    @Order(5)
    void unknownProjectPrepareForReadIsSilentNoOp() {
        BackgroundIndexService service = BackgroundIndexService.getInstance();
        String note = service.prepareForRead(new LocalCodeIndexer(),
                "no-such-project-" + System.nanoTime());
        assertNull(note);
    }

    @Test
    @Order(6)
    void watcherAutoStartsAndIndexesExternalChanges() throws Exception {
        System.setProperty("KOMPILE_CODE_INDEX_WATCH", "true");
        try {
            BackgroundIndexService service = BackgroundIndexService.getInstance();
            LocalCodeIndexer indexer = new LocalCodeIndexer();

            // First read queues watcher startup in the background.
            service.prepareForRead(indexer, PROJECT_ID);
            awaitTrue(() -> service.isWatching(PROJECT_ID), 15_000,
                    "watcher to auto-start for a read project");

            // An external write (no notification) must land via the watcher.
            Files.writeString(projectDir.resolve("Hawk.java"), """
                    package com.example;
                    public class Hawk {
                        public void soar() {}
                    }
                    """);
            awaitTrue(() -> {
                try {
                    return !indexer.search(PROJECT_ID, "Hawk", null, 10).isEmpty();
                } catch (IOException e) {
                    return false;
                }
            }, 30_000, "watcher-driven incremental index of an external write");
            Path graphPath = projectDir.resolve("data/crawls")
                    .resolve(PROJECT_ID + "-knowledge").resolve("graph.kgraph");
            awaitTrue(() -> {
                try {
                    return Files.isRegularFile(graphPath)
                            && UnifiedGraph.load(graphPath).entities().stream()
                            .anyMatch(entity -> "Hawk".equals(entity.label()));
                } catch (Exception e) {
                    return false;
                }
            }, 30_000, "watcher-driven KGraph publication of an external write");
        } finally {
            System.setProperty("KOMPILE_CODE_INDEX_WATCH", "false");
        }
    }

    @Test
    @Order(7)
    void disabledServiceFallsBackToLegacyInlineRefresh() throws Exception {
        LocalCodeIndexer indexer = new LocalCodeIndexer();
        indexer.index(disabledProjectDir, DISABLED_PROJECT_ID, null, null,
                new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));

        System.setProperty("KOMPILE_CODE_INDEX_BACKGROUND", "false");
        try {
            Files.writeString(disabledProjectDir.resolve("Echo.java"), """
                    package com.example;
                    public class Echo {}
                    """);
            String note = BackgroundIndexService.getInstance()
                    .prepareForRead(indexer, DISABLED_PROJECT_ID);
            assertNotNull(note, "legacy inline refresh should report the change");
            assertTrue(note.contains("re-indexed"), note);
            assertFalse(indexer.search(DISABLED_PROJECT_ID, "Echo", null, 10).isEmpty());
        } finally {
            System.clearProperty("KOMPILE_CODE_INDEX_BACKGROUND");
        }
    }

    @Test
    @Order(8)
    void duplicateRootUsesManifestCanonicalProjectForWriteNotifications() throws Exception {
        BackgroundIndexService.resetForTests();
        Path manifest = projectDir.resolve("kompile.project.json");
        String root = projectDir.toAbsolutePath().normalize().toString().replace("\\", "\\\\");
        Files.writeString(manifest, """
                {
                  "codingProjects": [ {
                    "id": "%s",
                    "codeProjectId": "%s",
                    "rootPath": "%s",
                    "lifecycle": "ACTIVE"
                  } ]
                }
                """.formatted(PROJECT_ID, PROJECT_ID, root));
        try {
            new LocalCodeIndexer().index(projectDir, ALIAS_PROJECT_ID, null, null,
                    new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));

            BackgroundIndexService service = BackgroundIndexService.getInstance();
            assertEquals(PROJECT_ID, service.projectForPath(
                    projectDir.resolve("Alpha.java").toAbsolutePath().normalize()));
        } finally {
            BackgroundIndexService.resetForTests();
            deleteRecursively(LocalCodeIndexer.getIndexDir(ALIAS_PROJECT_ID));
            Files.deleteIfExists(manifest);
        }
    }

    @Test
    @Order(9)
    void blockedProjectionDoesNotStarveLocalIndexJobs() throws Exception {
        BackgroundIndexService.resetForTests();
        System.setProperty("KOMPILE_CODE_INDEX_WATCH", "false");
        BackgroundIndexService service = BackgroundIndexService.getInstance();
        CountDownLatch projectionStarted = new CountDownLatch(1);
        CountDownLatch releaseProjection = new CountDownLatch(1);
        Path firstRoot = Files.createTempDirectory("projection-lane-first");
        Path secondRoot = Files.createTempDirectory("projection-lane-second");
        String firstProject = PROJECT_ID + "-projection-first";
        String secondProject = PROJECT_ID + "-projection-second";
        Files.writeString(firstRoot.resolve("First.java"), "public class First {}\n");
        Files.writeString(secondRoot.resolve("Second.java"), "public class Second {}\n");

        service.setProjectionPublisherForTests((root, projectId, includes, excludes) -> {
            projectionStarted.countDown();
            if (!releaseProjection.await(30, TimeUnit.SECONDS)) {
                throw new IOException("test projection was not released");
            }
            return null;
        });
        try {
            BackgroundIndexService.IndexJob first =
                    service.submitIndexJob(firstRoot, firstProject, null, null, false);
            assertTrue(service.awaitJob(first, 30_000),
                    "SQLite index job must complete before graph projection");
            assertTrue(projectionStarted.await(30, TimeUnit.SECONDS),
                    "projection should start on its separate local lane");

            BackgroundIndexService.IndexJob second =
                    service.submitIndexJob(secondRoot, secondProject, null, null, false);
            assertTrue(service.awaitJob(second, 30_000),
                    "a blocked projection must not starve a later index job");
            assertEquals(BackgroundIndexService.JobStatus.COMPLETED, second.status());
        } finally {
            releaseProjection.countDown();
            service.setProjectionPublisherForTests(null);
            BackgroundIndexService.resetForTests();
            deleteRecursively(LocalCodeIndexer.getIndexDir(firstProject));
            deleteRecursively(LocalCodeIndexer.getIndexDir(secondProject));
            deleteRecursively(firstRoot);
            deleteRecursively(secondRoot);
        }
    }

    @Test
    @Order(10)
    void blockedLearningDoesNotStarveLaterProjectionOrIndexJob() throws Exception {
        BackgroundIndexService.resetForTests();
        System.setProperty("KOMPILE_CODE_INDEX_WATCH", "false");
        System.setProperty("KOMPILE_CODE_INDEX_LEARNING_DEBOUNCE_MS", "0");
        BackgroundIndexService service = BackgroundIndexService.getInstance();
        CountDownLatch learningStarted = new CountDownLatch(1);
        CountDownLatch releaseLearning = new CountDownLatch(1);
        Path graph = Files.createTempDirectory("learning-shared-graph")
                .resolve("graph.kgraph");
        Path firstRoot = Files.createTempDirectory("learning-lane-first");
        Path secondRoot = Files.createTempDirectory("learning-lane-second");
        String firstProject = PROJECT_ID + "-learning-first";
        String secondProject = PROJECT_ID + "-learning-second";
        Files.writeString(firstRoot.resolve("First.java"), "public class First {}\n");
        Files.writeString(secondRoot.resolve("Second.java"), "public class Second {}\n");

        service.setProjectionPublisherForTests((root, projectId, includes, excludes) ->
                new LocalCodeKGraphPublisher.ProjectionResult(graph, null, null, 0, 0, 0));
        service.setConfiguredLearningForTests((root, graphPath, trigger) -> {
            learningStarted.countDown();
            if (!releaseLearning.await(30, TimeUnit.SECONDS)) {
                throw new IOException("test learning was not released");
            }
            return completedLearning();
        });
        try {
            BackgroundIndexService.IndexJob first = service.submitIndexJob(
                    firstRoot, firstProject, null, null, false);
            assertTrue(service.awaitJob(first, 30_000));
            assertTrue(learningStarted.await(30, TimeUnit.SECONDS),
                    "the first graph learning pass should start");

            BackgroundIndexService.IndexJob second = service.submitIndexJob(
                    secondRoot, secondProject, null, null, false);
            assertTrue(service.awaitJob(second, 30_000),
                    "a blocked learning pass must not starve another index job");
            awaitTrue(() -> second.projection() != null, 30_000,
                    "the later projection to publish while learning is blocked");

            releaseLearning.countDown();
            awaitTrue(() -> first.learning() != null && second.learning() != null,
                    30_000, "both coalesced jobs to receive learning results");
            assertEquals(BackgroundIndexService.JobStatus.COMPLETED, second.status());
        } finally {
            releaseLearning.countDown();
            service.setConfiguredLearningForTests(null);
            service.setProjectionPublisherForTests(null);
            BackgroundIndexService.resetForTests();
            deleteRecursively(LocalCodeIndexer.getIndexDir(firstProject));
            deleteRecursively(LocalCodeIndexer.getIndexDir(secondProject));
            deleteRecursively(firstRoot);
            deleteRecursively(secondRoot);
            deleteRecursively(graph.getParent());
            System.clearProperty("KOMPILE_CODE_INDEX_LEARNING_DEBOUNCE_MS");
        }
    }

    @Test
    @Order(11)
    void sameGraphBurstCoalescesAndUsesLatestRoot() throws Exception {
        BackgroundIndexService.resetForTests();
        System.setProperty("KOMPILE_CODE_INDEX_LEARNING_DEBOUNCE_MS", "100");
        System.setProperty("KOMPILE_CODE_INDEX_LEARNING_MAX_WAIT_MS", "500");
        BackgroundIndexService service = BackgroundIndexService.getInstance();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Path> lastRoot = new AtomicReference<>();
        Path graph = Files.createTempDirectory("learning-burst-graph")
                .resolve("graph.kgraph");
        service.setConfiguredLearningForTests((root, graphPath, trigger) -> {
            lastRoot.set(root);
            calls.incrementAndGet();
            return completedLearning();
        });
        try {
            Path latestRoot = null;
            for (int i = 0; i < 50; i++) {
                latestRoot = Path.of("burst-root-" + i);
                service.scheduleLearningForTests(latestRoot, PROJECT_ID + "-burst", graph);
            }
            Path expectedRoot = latestRoot;
            awaitTrue(() -> calls.get() == 1, 5_000,
                    "one learning pass for a same-graph burst");
            assertEquals(expectedRoot, lastRoot.get(),
                    "the coalesced pass should use the latest projection root");
        } finally {
            service.setConfiguredLearningForTests(null);
            BackgroundIndexService.resetForTests();
            deleteRecursively(graph.getParent());
            System.clearProperty("KOMPILE_CODE_INDEX_LEARNING_DEBOUNCE_MS");
            System.clearProperty("KOMPILE_CODE_INDEX_LEARNING_MAX_WAIT_MS");
        }
    }

    @Test
    @Order(12)
    void updatesDuringLearningProduceOneFollowup() throws Exception {
        BackgroundIndexService.resetForTests();
        System.setProperty("KOMPILE_CODE_INDEX_LEARNING_DEBOUNCE_MS", "0");
        BackgroundIndexService service = BackgroundIndexService.getInstance();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        Path graph = Files.createTempDirectory("learning-followup-graph")
                .resolve("graph.kgraph");
        service.setConfiguredLearningForTests((root, graphPath, trigger) -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                firstStarted.countDown();
                if (!releaseFirst.await(30, TimeUnit.SECONDS)) {
                    throw new IOException("test learning was not released");
                }
            } else if (call == 2) {
                secondStarted.countDown();
            }
            return completedLearning();
        });
        try {
            service.scheduleLearningForTests(Path.of("followup-root"), PROJECT_ID + "-followup", graph);
            assertTrue(firstStarted.await(30, TimeUnit.SECONDS));
            for (int i = 0; i < 20; i++) {
                service.scheduleLearningForTests(Path.of("followup-root-" + i),
                        PROJECT_ID + "-followup", graph);
            }
            releaseFirst.countDown();
            assertTrue(secondStarted.await(30, TimeUnit.SECONDS),
                    "updates during learning should schedule one follow-up");
            awaitTrue(() -> calls.get() == 2, 5_000,
                    "the follow-up learning pass to finish without duplication");
            assertEquals(2, calls.get());
        } finally {
            releaseFirst.countDown();
            service.setConfiguredLearningForTests(null);
            BackgroundIndexService.resetForTests();
            deleteRecursively(graph.getParent());
            System.clearProperty("KOMPILE_CODE_INDEX_LEARNING_DEBOUNCE_MS");
        }
    }

    @Test
    @Order(13)
    void distinctProjectsSharingGraphCoalesceAndCompleteEachJob() throws Exception {
        BackgroundIndexService.resetForTests();
        System.setProperty("KOMPILE_CODE_INDEX_LEARNING_DEBOUNCE_MS", "100");
        BackgroundIndexService service = BackgroundIndexService.getInstance();
        AtomicInteger calls = new AtomicInteger();
        Path graph = Files.createTempDirectory("learning-shared-project-graph")
                .resolve("graph.kgraph");
        BackgroundIndexService.IndexJob first = new BackgroundIndexService.IndexJob(
                "test-job-1", "shared-project-1", "root-1", false);
        BackgroundIndexService.IndexJob second = new BackgroundIndexService.IndexJob(
                "test-job-2", "shared-project-2", "root-2", false);
        service.setConfiguredLearningForTests((root, graphPath, trigger) -> {
            calls.incrementAndGet();
            return completedLearning();
        });
        try {
            service.scheduleLearningForTests(Path.of("root-1"), "shared-project-1", graph, first);
            service.scheduleLearningForTests(Path.of("root-2"), "shared-project-2", graph, second);
            awaitTrue(() -> first.learning() != null && second.learning() != null,
                    5_000, "both jobs to receive the shared graph result");
            assertEquals(1, calls.get(), "graph path, not project id, is the batch key");
            assertNotNull(first.projection());
            assertNotNull(second.projection());
        } finally {
            service.setConfiguredLearningForTests(null);
            BackgroundIndexService.resetForTests();
            deleteRecursively(graph.getParent());
            System.clearProperty("KOMPILE_CODE_INDEX_LEARNING_DEBOUNCE_MS");
        }
    }

    @Test
    @Order(14)
    void watcherOnlyProjectionRunsConfiguredLearning() throws Exception {
        BackgroundIndexService.resetForTests();
        System.setProperty("KOMPILE_CODE_INDEX_LEARNING_DEBOUNCE_MS", "0");
        BackgroundIndexService service = BackgroundIndexService.getInstance();
        CountDownLatch learned = new CountDownLatch(1);
        Path graph = Files.createTempDirectory("learning-watcher-graph")
                .resolve("graph.kgraph");
        service.setConfiguredLearningForTests((root, graphPath, trigger) -> {
            learned.countDown();
            return completedLearning();
        });
        try {
            service.scheduleLearningForTests(Path.of("watcher-root"), PROJECT_ID + "-watcher", graph);
            assertTrue(learned.await(30, TimeUnit.SECONDS),
                    "a watcher/null-target-job projection should learn");
        } finally {
            service.setConfiguredLearningForTests(null);
            BackgroundIndexService.resetForTests();
            deleteRecursively(graph.getParent());
            System.clearProperty("KOMPILE_CODE_INDEX_LEARNING_DEBOUNCE_MS");
        }
    }

    @Test
    @Order(15)
    void resetInterruptsRunningLearningAndCancelsPendingWork() throws Exception {
        BackgroundIndexService.resetForTests();
        System.setProperty("KOMPILE_CODE_INDEX_LEARNING_DEBOUNCE_MS", "0");
        BackgroundIndexService service = BackgroundIndexService.getInstance();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        Path graph = Files.createTempDirectory("learning-shutdown-graph")
                .resolve("graph.kgraph");
        BackgroundIndexService.IndexJob pending = new BackgroundIndexService.IndexJob(
                "pending-learning-job", PROJECT_ID + "-shutdown", "shutdown-root", false);
        service.setConfiguredLearningForTests((root, graphPath, trigger) -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException expected) {
                interrupted.countDown();
                throw expected;
            }
            return completedLearning();
        });
        try {
            service.scheduleLearningForTests(Path.of("shutdown-root"),
                    PROJECT_ID + "-shutdown", graph);
            assertTrue(started.await(30, TimeUnit.SECONDS));
            service.scheduleLearningForTests(Path.of("shutdown-latest"),
                    PROJECT_ID + "-shutdown", graph, pending);
            assertNull(pending.learning(), "the follow-up job should still be pending");
        } finally {
            BackgroundIndexService.resetForTests();
            assertTrue(interrupted.await(5, TimeUnit.SECONDS),
                    "reset should interrupt the learning lane");
            assertNotNull(pending.learning(), "reset should complete pending jobs");
            assertEquals("learning cancelled", pending.learning().error());
            deleteRecursively(graph.getParent());
            System.clearProperty("KOMPILE_CODE_INDEX_LEARNING_DEBOUNCE_MS");
        }
    }

    @Test
    @Order(16)
    void quietDebounceUsesLastArrival() throws Exception {
        BackgroundIndexService.resetForTests();
        System.setProperty("KOMPILE_CODE_INDEX_LEARNING_DEBOUNCE_MS", "1000");
        System.setProperty("KOMPILE_CODE_INDEX_LEARNING_MAX_WAIT_MS", "4000");
        BackgroundIndexService service = BackgroundIndexService.getInstance();
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Path> lastRoot = new AtomicReference<>();
        Path graph = Files.createTempDirectory("learning-last-arrival-graph")
                .resolve("graph.kgraph");
        service.setConfiguredLearningForTests((root, graphPath, trigger) -> {
            lastRoot.set(root);
            calls.incrementAndGet();
            started.countDown();
            return completedLearning();
        });
        try {
            service.scheduleLearningForTests(Path.of("quiet-first"),
                    PROJECT_ID + "-last-arrival", graph);
            Thread.sleep(400);
            service.scheduleLearningForTests(Path.of("quiet-latest"),
                    PROJECT_ID + "-last-arrival", graph);
            assertFalse(started.await(750, TimeUnit.MILLISECONDS),
                    "a second arrival must postpone learning past the first quiet deadline");
            assertTrue(started.await(5, TimeUnit.SECONDS),
                    "the debounced learning pass should eventually start");
            assertEquals(1, calls.get());
            assertEquals(Path.of("quiet-latest"), lastRoot.get());
        } finally {
            service.setConfiguredLearningForTests(null);
            BackgroundIndexService.resetForTests();
            deleteRecursively(graph.getParent());
            System.clearProperty("KOMPILE_CODE_INDEX_LEARNING_DEBOUNCE_MS");
            System.clearProperty("KOMPILE_CODE_INDEX_LEARNING_MAX_WAIT_MS");
        }
    }

    @Test
    @Order(17)
    void sustainedLearningUpdatesHonorMaximumWait() throws Exception {
        BackgroundIndexService.resetForTests();
        System.setProperty("KOMPILE_CODE_INDEX_LEARNING_DEBOUNCE_MS", "500");
        System.setProperty("KOMPILE_CODE_INDEX_LEARNING_MAX_WAIT_MS", "1200");
        BackgroundIndexService service = BackgroundIndexService.getInstance();
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicLong firstCallAt = new AtomicLong();
        Path graph = Files.createTempDirectory("learning-max-wait-graph")
                .resolve("graph.kgraph");
        service.setConfiguredLearningForTests((root, graphPath, trigger) -> {
            firstCallAt.compareAndSet(0, System.currentTimeMillis());
            calls.incrementAndGet();
            started.countDown();
            return completedLearning();
        });
        try {
            long firstDirtyAt = System.currentTimeMillis();
            service.scheduleLearningForTests(Path.of("max-root-0"),
                    PROJECT_ID + "-max-wait", graph);
            long deadline = firstDirtyAt + 4_000;
            int updatesWhileWaiting = 0;
            boolean startedWithinDeadline = false;
            while (!startedWithinDeadline) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                startedWithinDeadline = started.await(
                        Math.min(150, remaining), TimeUnit.MILLISECONDS);
                if (!startedWithinDeadline) {
                    updatesWhileWaiting++;
                    service.scheduleLearningForTests(
                            Path.of("max-root-" + updatesWhileWaiting),
                            PROJECT_ID + "-max-wait", graph);
                }
            }
            assertTrue(startedWithinDeadline,
                    "continuous updates must still reach the bounded maximum wait");
            assertTrue(updatesWhileWaiting >= 4,
                    "learning must start during continued dirty arrivals, not after a quiet period");
            assertEquals(1, calls.get());
            assertTrue(firstCallAt.get() - firstDirtyAt >= 900,
                    "learning must not run at the first quiet deadline under sustained updates");
        } finally {
            service.setConfiguredLearningForTests(null);
            BackgroundIndexService.resetForTests();
            deleteRecursively(graph.getParent());
            System.clearProperty("KOMPILE_CODE_INDEX_LEARNING_DEBOUNCE_MS");
            System.clearProperty("KOMPILE_CODE_INDEX_LEARNING_MAX_WAIT_MS");
        }
    }

    @Test
    @Order(18)
    void supersededLearningSchedulesLatestRetry() throws Exception {
        BackgroundIndexService.resetForTests();
        System.setProperty("KOMPILE_CODE_INDEX_LEARNING_DEBOUNCE_MS", "100");
        System.setProperty("KOMPILE_CODE_INDEX_LEARNING_MAX_WAIT_MS", "1000");
        BackgroundIndexService service = BackgroundIndexService.getInstance();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Path> lastRoot = new AtomicReference<>();
        Path graph = Files.createTempDirectory("learning-superseded-graph")
                .resolve("graph.kgraph");
        service.setConfiguredLearningForTests((root, graphPath, trigger) -> {
            lastRoot.set(root);
            if (calls.incrementAndGet() == 1) {
                firstStarted.countDown();
                if (!releaseFirst.await(30, TimeUnit.SECONDS)) {
                    throw new IOException("test learning was not released");
                }
                return supersededLearning();
            }
            secondStarted.countDown();
            return completedLearning();
        });
        try {
            service.scheduleLearningForTests(Path.of("superseded-first"),
                    PROJECT_ID + "-superseded", graph);
            assertTrue(firstStarted.await(30, TimeUnit.SECONDS));
            service.scheduleLearningForTests(Path.of("superseded-latest"),
                    PROJECT_ID + "-superseded", graph);
            releaseFirst.countDown();
            assertTrue(secondStarted.await(30, TimeUnit.SECONDS),
                    "SUPERSEDED learning should schedule one latest retry");
            assertEquals(2, calls.get());
            assertEquals(Path.of("superseded-latest"), lastRoot.get());
        } finally {
            releaseFirst.countDown();
            service.setConfiguredLearningForTests(null);
            BackgroundIndexService.resetForTests();
            deleteRecursively(graph.getParent());
            System.clearProperty("KOMPILE_CODE_INDEX_LEARNING_DEBOUNCE_MS");
            System.clearProperty("KOMPILE_CODE_INDEX_LEARNING_MAX_WAIT_MS");
        }
    }

    private static CodeGraphLearningRunner.ConfiguredResult supersededLearning() {
        return new CodeGraphLearningRunner.ConfiguredResult(
                new CodeGraphLearningRunner.LearningSummary(
                        "SUPERSEDED", 0, 0, false, false, false, 0, 0, 0, 0), null);
    }

    private static CodeGraphLearningRunner.ConfiguredResult completedLearning() {
        return new CodeGraphLearningRunner.ConfiguredResult(
                new CodeGraphLearningRunner.LearningSummary(
                        "COMPLETED", 0, 0, false, false, false, 0, 0, 0, 0), null);
    }
}
