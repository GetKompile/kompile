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

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link IndexAutoRefresher}: a throttled incremental re-index pass
 * that keeps read actions in sync with the working tree.
 *
 * <p>Indexes into a temporary home's {@code ~/.kompile/code-index} under a
 * unique throwaway project id, cleaned up in {@code @AfterAll}.</p>
 */
@TemporaryUserHome
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IndexAutoRefresherTest {

    private static final String PROJECT_ID = "auto-refresher-test-" + System.nanoTime();
    private static Path projectDir;
    private static LocalCodeIndexer indexer;

    private static PrintStream silent() {
        return new PrintStream(OutputStream.nullOutputStream(), false, StandardCharsets.UTF_8);
    }

    @BeforeAll
    static void setUp() throws Exception {
        projectDir = Files.createTempDirectory("auto-refresher-project");
        Files.writeString(projectDir.resolve("Alpha.java"), """
                package com.example;
                public class Alpha {
                    public void greet() {}
                }
                """);
        Path virtualEnv = projectDir.resolve(".venv/lib");
        Files.createDirectories(virtualEnv);
        Files.writeString(virtualEnv.resolve("Ignored.py"), "class Ignored:\n    pass\n");
        indexer = new LocalCodeIndexer();
        indexer.index(projectDir, PROJECT_ID, null, null, silent());
    }

    @AfterAll
    static void tearDown() throws IOException {
        deleteRecursively(LocalCodeIndexer.getIndexDir(PROJECT_ID));
        deleteRecursively(projectDir);
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

    @Test
    @Order(1)
    void refreshPicksUpNewFile() throws Exception {
        Files.writeString(projectDir.resolve("Zebra.java"), """
                package com.example;
                public class Zebra {
                    public void stripe() {}
                }
                """);

        String note = IndexAutoRefresher.maybeRefresh(indexer, PROJECT_ID, 0);
        assertNotNull(note, "adding a file should produce a refresh note");
        assertTrue(note.contains("re-indexed"), "note should describe the refresh: " + note);

        List<Map<String, Object>> hits = indexer.search(PROJECT_ID, "Zebra", null, 10);
        assertFalse(hits.isEmpty(), "search after refresh should find the new class");
    }

    @Test
    @Order(2)
    void refreshIsThrottledWithinInterval() throws Exception {
        Files.writeString(projectDir.resolve("Gamma.java"), """
                package com.example;
                public class Gamma {}
                """);

        // The previous test just refreshed; a long interval must suppress this one.
        String note = IndexAutoRefresher.maybeRefresh(indexer, PROJECT_ID, 600_000);
        assertNull(note, "refresh within the throttle window should be a no-op");
    }

    @Test
    @Order(3)
    void noOpWhenNothingChanged() {
        // Force past the throttle; the tree already matches the index (Gamma was
        // picked up by a forced refresh here — run twice to land on a clean pass).
        IndexAutoRefresher.maybeRefresh(indexer, PROJECT_ID, 0);
        String note = IndexAutoRefresher.maybeRefresh(indexer, PROJECT_ID, 0);
        assertNull(note, "a clean tree should produce no refresh note");
    }

    @Test
    @Order(4)
    void missingProjectIsSilentlySkipped() {
        String note = IndexAutoRefresher.maybeRefresh(indexer,
                "no-such-project-" + System.nanoTime(), 0);
        assertNull(note);
    }

    @Test
    @Order(5)
    void virtualEnvironmentTreesAreIgnored() throws Exception {
        assertTrue(indexer.search(PROJECT_ID, "Ignored", null, 10).isEmpty(),
                "virtual-environment dependencies must not pollute the project index");
    }

    @Test
    @Order(6)
    void invalidUtf8ReportsPathAndFailedRefreshCount() throws Exception {
        Path broken = projectDir.resolve("Broken.java");
        Files.write(broken, new byte[] {(byte) 0xc3, (byte) 0x28});
        try {
            String generationBefore = String.valueOf(indexer.getStats(PROJECT_ID).get("indexedAt"));
            ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
            LocalCodeIndexer.IndexResult result = indexer.index(
                    projectDir, PROJECT_ID, null, null,
                    new PrintStream(diagnostics, true, StandardCharsets.UTF_8));

            assertEquals(1, result.errors());
            assertEquals(generationBefore,
                    String.valueOf(indexer.getStats(PROJECT_ID).get("indexedAt")),
                    "a failed-only pass must not mint a committed index generation");
            String rendered = diagnostics.toString(StandardCharsets.UTF_8);
            assertTrue(rendered.contains("Broken.java"), rendered);
            assertTrue(rendered.contains("invalid UTF-8"), rendered);

            String note = IndexAutoRefresher.maybeRefresh(indexer, PROJECT_ID, 0);
            assertNotNull(note);
            assertTrue(note.contains("1 failed"), note);
            assertTrue(note.contains("0 files re-indexed"), note);
        } finally {
            Files.deleteIfExists(broken);
            IndexAutoRefresher.maybeRefresh(indexer, PROJECT_ID, 0);
        }
    }

    @Test
    @Order(7)
    void implicitRefreshPreservesStoredScope() throws Exception {
        String scopedProjectId = PROJECT_ID + "-scoped";
        Path scopedRoot = Files.createTempDirectory("auto-refresher-scoped");
        try {
            Files.writeString(scopedRoot.resolve("Alpha.java"), "final class Alpha {}\n");
            Files.writeString(scopedRoot.resolve("ignored.json"), "{\"ignored\":true}\n");
            indexer.index(scopedRoot, scopedProjectId, "*.java", null, silent());

            Files.writeString(scopedRoot.resolve("Beta.java"), "final class Beta {}\n");
            String note = IndexAutoRefresher.maybeRefresh(indexer, scopedProjectId, 0);

            assertNotNull(note);
            assertEquals("*.java", indexer.getStats(scopedProjectId).get("includePatterns"));
            assertFalse(indexer.search(scopedProjectId, "Beta", null, 10).isEmpty());
            assertTrue(indexer.search(scopedProjectId, "ignored.json", null, 10).isEmpty());
        } finally {
            deleteRecursively(LocalCodeIndexer.getIndexDir(scopedProjectId));
            deleteRecursively(scopedRoot);
        }
    }

    @Test
    @Order(8)
    void expectedIndexContentionDoesNotBecomeATuiAlert() throws Exception {
        List<String> alerts = new ArrayList<>();
        Runnable cleanup = CodeIndexDiagnostics.installAlertSink(alerts::add);
        try (IndexLockManager.LockToken ignored = IndexLockManager.acquireWriteLock(
                PROJECT_ID, LocalCodeIndexer.getIndexDir(PROJECT_ID))) {
            IndexAutoRefresher.RefreshOutcome outcome =
                    IndexAutoRefresher.refresh(indexer, PROJECT_ID, 0);
            assertFalse(outcome.successful());
            assertTrue(alerts.isEmpty(), "routine lock contention must stay out of the alert lane");
        } finally {
            cleanup.run();
        }
    }

    @Test
    @Order(9)
    void tornMetadataIsRebuiltFromTheRootTheCallerKnows() throws Exception {
        String tornProjectId = PROJECT_ID + "-torn";
        Path tornRoot = Files.createTempDirectory("auto-refresher-torn").toAbsolutePath().normalize();
        List<String> alerts = new ArrayList<>();
        Runnable cleanup = CodeIndexDiagnostics.installAlertSink(alerts::add);
        try {
            Files.writeString(tornRoot.resolve("Alpha.java"), "final class Alpha {}\n");
            indexer.index(tornRoot, tornProjectId, null, null, silent());
            // What a crash between the write and its fsync leaves behind.
            Files.write(LocalCodeIndexer.getIndexDir(tornProjectId).resolve("metadata.json"), new byte[0]);
            Files.writeString(tornRoot.resolve("Beta.java"), "final class Beta {}\n");

            IndexAutoRefresher.RefreshOutcome blind =
                    IndexAutoRefresher.refresh(indexer, tornProjectId, 0);
            assertFalse(blind.successful(), "only the torn file recorded the root");
            assertTrue(alerts.stream().anyMatch(alert -> alert.contains("auto-refresh skipped")), alerts.toString());

            IndexAutoRefresher.RefreshOutcome outcome =
                    IndexAutoRefresher.refresh(indexer, tornProjectId, 0, tornRoot);
            assertTrue(outcome.successful(), alerts.toString());
            assertTrue(outcome.changed());
            assertEquals(tornRoot.toString(), indexer.getStats(tornProjectId).get("rootPath"));
            assertFalse(indexer.search(tornProjectId, "Alpha", null, 10).isEmpty());
            assertFalse(indexer.search(tornProjectId, "Beta", null, 10).isEmpty());
        } finally {
            cleanup.run();
            deleteRecursively(LocalCodeIndexer.getIndexDir(tornProjectId));
            deleteRecursively(tornRoot);
        }
    }

    @Test
    @Order(10)
    void knownRootDoesNotOverrideReadableMetadata() throws Exception {
        Path elsewhere = Files.createTempDirectory("auto-refresher-elsewhere").toAbsolutePath().normalize();
        try {
            Files.writeString(elsewhere.resolve("Stranger.java"), "final class Stranger {}\n");
            Files.writeString(projectDir.resolve("Delta.java"), "final class Delta {}\n");

            IndexAutoRefresher.RefreshOutcome outcome =
                    IndexAutoRefresher.refresh(indexer, PROJECT_ID, 0, elsewhere);

            assertTrue(outcome.successful());
            assertEquals(projectDir.toAbsolutePath().normalize().toString(),
                    indexer.getStats(PROJECT_ID).get("rootPath"));
            assertFalse(indexer.search(PROJECT_ID, "Delta", null, 10).isEmpty());
            assertTrue(indexer.search(PROJECT_ID, "Stranger", null, 10).isEmpty());
        } finally {
            deleteRecursively(elsewhere);
        }
    }

    @Test
    @Order(11)
    void refreshWaitingOnTheIndexLockKeepsTheScopeCommittedMeanwhile() throws Exception {
        String raceProjectId = PROJECT_ID + "-race";
        Path raceRoot = Files.createTempDirectory("auto-refresher-race").toAbsolutePath().normalize();
        Path indexDir = LocalCodeIndexer.getIndexDir(raceProjectId);
        try {
            Files.writeString(raceRoot.resolve("Alpha.java"), "final class Alpha {}\n");
            Files.writeString(raceRoot.resolve("kestrel.py"), "class Kestrel:\n    pass\n");
            indexer.index(raceRoot, raceProjectId, "*.java", null, silent());
            assertTrue(indexer.search(raceProjectId, "Kestrel", null, 10).isEmpty());

            AtomicReference<IndexAutoRefresher.RefreshOutcome> outcome = new AtomicReference<>();
            Thread refresher = new Thread(() -> outcome.set(IndexAutoRefresher.refresh(indexer, raceProjectId, 0)));
            try (IndexLockManager.LockToken ignored = IndexLockManager.acquireWriteLock(raceProjectId, indexDir)) {
                refresher.start();
                // getStats takes no index lock, so the only wait the refresh can be queued in
                // is the index write lock, after it has read the metadata the old code trusted.
                long deadline = System.currentTimeMillis() + 10_000;
                while (!IndexLockManager.lockFor(raceProjectId).hasQueuedThread(refresher)) {
                    assertTrue(System.currentTimeMillis() < deadline, "the refresh never reached the index lock");
                    Thread.sleep(10);
                }
                // While it waits, another process commits an explicit widening to the default scope.
                IndexFileStore store = new IndexFileStore(indexDir, JsonUtils.standardMapper());
                Map<String, Object> metadata = store.loadMetadata();
                metadata.remove("includePatterns");
                store.saveMetadata(metadata);
            }
            refresher.join(30_000);

            assertNotNull(outcome.get(), "the refresh did not finish");
            assertTrue(outcome.get().successful());
            assertNull(indexer.getStats(raceProjectId).get("includePatterns"));
            assertFalse(indexer.search(raceProjectId, "Kestrel", null, 10).isEmpty());
        } finally {
            deleteRecursively(indexDir);
            deleteRecursively(raceRoot);
        }
    }

    @Test
    @Order(12)
    void tornMetadataRebuildsANarrowedIndexWithTheDefaultScope() throws Exception {
        String tornProjectId = PROJECT_ID + "-torn-scope";
        Path tornRoot = Files.createTempDirectory("auto-refresher-torn-scope").toAbsolutePath().normalize();
        try {
            Files.writeString(tornRoot.resolve("Alpha.java"), "final class Alpha {}\n");
            Files.writeString(tornRoot.resolve("kestrel.py"), "class Kestrel:\n    pass\n");
            indexer.index(tornRoot, tornProjectId, "*.java", null, silent());
            assertTrue(indexer.search(tornProjectId, "Kestrel", null, 10).isEmpty());
            // The torn file was the only record of the narrowed scope.
            Files.write(LocalCodeIndexer.getIndexDir(tornProjectId).resolve("metadata.json"), new byte[0]);

            indexer.refreshRecordedScope(tornRoot, tornProjectId, silent());

            assertNull(indexer.getStats(tornProjectId).get("includePatterns"));
            assertFalse(indexer.search(tornProjectId, "Alpha", null, 10).isEmpty());
            assertFalse(indexer.search(tornProjectId, "Kestrel", null, 10).isEmpty());
        } finally {
            deleteRecursively(LocalCodeIndexer.getIndexDir(tornProjectId));
            deleteRecursively(tornRoot);
        }
    }
}
