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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
 * <p>Follows the existing codeindex test convention: indexes into the real
 * {@code ~/.kompile/code-index} under unique throwaway project ids, cleaned
 * up in {@code @AfterAll}.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BackgroundIndexServiceTest {

    private static final String PROJECT_ID = "background-index-test-" + System.nanoTime();
    private static final String DISABLED_PROJECT_ID = PROJECT_ID + "-disabled";
    private static Path projectDir;
    private static Path disabledProjectDir;

    @BeforeAll
    static void setUp() throws Exception {
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
    }

    @AfterAll
    static void tearDown() throws IOException {
        BackgroundIndexService.resetForTests();
        System.clearProperty("KOMPILE_CODE_INDEX_READ_WAIT_MS");
        System.clearProperty("KOMPILE_CODE_INDEX_WATCH");
        System.clearProperty("KOMPILE_CODE_INDEX_BACKGROUND");
        deleteRecursively(LocalCodeIndexer.getIndexDir(PROJECT_ID));
        deleteRecursively(LocalCodeIndexer.getIndexDir(DISABLED_PROJECT_ID));
        deleteRecursively(projectDir);
        deleteRecursively(disabledProjectDir);
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
}
