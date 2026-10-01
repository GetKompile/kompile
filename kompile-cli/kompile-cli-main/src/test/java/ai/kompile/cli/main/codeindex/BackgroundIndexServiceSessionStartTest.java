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

import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BackgroundIndexService#refreshOnSessionStart}: one incremental pass over an
 * existing index that starts no watcher and projects the graph only when the pass
 * changed the index.
 */
@TemporaryUserHome
class BackgroundIndexServiceSessionStartTest {

    private static final String BACKGROUND = "KOMPILE_CODE_INDEX_BACKGROUND";
    private static final String WATCH = "KOMPILE_CODE_INDEX_WATCH";

    @TempDir Path project;

    private final AtomicInteger projections = new AtomicInteger();
    private String previousBackground;
    private String previousWatch;
    private BackgroundIndexService service;
    private String projectId;

    @BeforeEach
    void setUp() throws Exception {
        previousBackground = System.getProperty(BACKGROUND);
        previousWatch = System.getProperty(WATCH);
        System.setProperty(BACKGROUND, "true");
        // Watchers are allowed, so a watcher-free pass is the session-start path's own choice.
        System.setProperty(WATCH, "true");
        BackgroundIndexService.resetForTests();
        service = BackgroundIndexService.getInstance();
        service.setProjectionPublisherForTests((root, id, includes, excludes) -> {
            projections.incrementAndGet();
            return null;
        });
        projectId = "session-start-test-" + System.nanoTime();
        Files.writeString(project.resolve("Alpha.java"), "public class Alpha {}\n");
        new LocalCodeIndexer().index(project, projectId, null, null,
                new PrintStream(OutputStream.nullOutputStream()));
    }

    @AfterEach
    void tearDown() {
        BackgroundIndexService.resetForTests();
        restore(BACKGROUND, previousBackground);
        restore(WATCH, previousWatch);
    }

    private static void restore(String key, String previous) {
        if (previous == null) System.clearProperty(key);
        else System.setProperty(key, previous);
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
    void anUnchangedIndexIsCheckedWithoutAProjectionOrAWatcher() {
        service.refreshOnSessionStart(projectId);

        awaitTrue(() -> {
            String status = service.statusLine(projectId);
            return status != null && status.contains("last background pass");
        }, 15_000, "the session-start pass to complete");

        // The pass schedules any projection before it records completion.
        String status = service.statusLine(projectId);
        assertEquals(0, projections.get(), status);
        assertFalse(status.contains("graph projection"), status);
        assertFalse(service.isWatching(projectId), "a session-start pass starts no watcher");
    }

    @Test
    void aChangedTreeIsIndexedAndProjectedWithoutAWatcher() throws Exception {
        LocalCodeIndexer indexer = new LocalCodeIndexer();
        Files.writeString(project.resolve("Beta.java"), "public class Beta {}\n");
        assertTrue(indexer.search(projectId, "Beta", null, 10).isEmpty(), "Beta is not indexed yet");

        service.refreshOnSessionStart(projectId);

        // Indexing finishes before the projection is scheduled.
        awaitTrue(() -> projections.get() >= 1, 15_000, "the graph projection after a changed pass");
        assertFalse(indexer.search(projectId, "Beta", null, 10).isEmpty(), "the pass indexed Beta");
        assertFalse(service.isWatching(projectId), "a session-start pass starts no watcher");
    }
}
