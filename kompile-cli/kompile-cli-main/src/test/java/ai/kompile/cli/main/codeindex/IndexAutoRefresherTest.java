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
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link IndexAutoRefresher}: a throttled incremental re-index pass
 * that keeps read actions in sync with the working tree.
 *
 * <p>Follows the existing codeindex test convention: indexes into the real
 * {@code ~/.kompile/code-index} under a unique throwaway project id, cleaned
 * up in {@code @AfterAll}.</p>
 */
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
}
