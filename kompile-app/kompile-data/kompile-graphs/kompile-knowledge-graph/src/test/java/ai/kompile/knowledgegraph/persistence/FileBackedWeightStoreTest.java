/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.persistence;

import ai.kompile.graph.reasoning.learning.FileWeightStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain JUnit 5 (no Spring) tests for {@link FileBackedWeightStore}.
 */
class FileBackedWeightStoreTest {

    @TempDir
    Path tempDir;

    /**
     * When constructed with an explicit data dir, files must land in that dir,
     * NOT in {@code ~/.kompile}.
     */
    @Test
    void dataDirResolvesProjectScoped() throws Exception {
        String dataDirPath = tempDir.toString();
        FileBackedWeightStore store = new FileBackedWeightStore(dataDirPath);

        Map<String, Double> weights = Map.of("rule1", 0.8, "rule2", 0.5);
        store.save("prog-1", weights);

        // Files must be under tempDir, not under user.home/.kompile
        Path expectedBase = tempDir.resolve("data").resolve("graph").resolve("reasoning");
        assertTrue(Files.isDirectory(expectedBase),
                "Reasoning base directory should exist under tempDir");

        // At least one .json file must exist under expectedBase
        long fileCount = Files.walk(expectedBase)
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".json"))
                .count();
        assertTrue(fileCount > 0, "At least one weight JSON file must exist in the project-scoped dir");

        // Verify home dir was NOT used (best-effort: check no reasoning dir under ~/.kompile was created)
        Path homeReasoning = Path.of(System.getProperty("user.home"), ".kompile",
                "data", "graph", "reasoning");
        // We can't assert this is absent (user may have pre-existing files), but we can assert
        // that the store's saved file IS under tempDir.
        Optional<Map<String, Double>> loaded = store.latest("prog-1");
        assertTrue(loaded.isPresent(), "Saved weights must be retrievable");
        assertEquals(0.8, loaded.get().get("rule1"), 1e-9);
    }

    /**
     * Verify that weights written in one store instance are readable by a NEW instance
     * over the same directory (proves file durability).
     */
    @Test
    void persistAndReload() throws Exception {
        String dataDirPath = tempDir.toString();

        // First store instance — write weights
        FileBackedWeightStore store1 = new FileBackedWeightStore(dataDirPath);
        Map<String, Double> weights = Map.of("ruleA", 0.75, "ruleB", 0.25);
        store1.save("my-program", weights);
        Optional<Map<String, Double>> latestFromStore1 = store1.latest("my-program");
        assertTrue(latestFromStore1.isPresent(), "store1 must return saved weights");
        assertEquals(0.75, latestFromStore1.get().get("ruleA"), 1e-9);

        // Second store instance over same dir — must reload from files
        FileBackedWeightStore store2 = new FileBackedWeightStore(dataDirPath);
        Optional<Map<String, Double>> latestFromStore2 = store2.latest("my-program");
        assertTrue(latestFromStore2.isPresent(), "store2 must load weights persisted by store1");
        assertEquals(0.75, latestFromStore2.get().get("ruleA"), 1e-9);
        assertEquals(0.25, latestFromStore2.get().get("ruleB"), 1e-9);
    }

    /**
     * Multiple save calls must produce distinct versions, both retrievable by version number.
     */
    @Test
    void multiVersioning() throws Exception {
        FileBackedWeightStore store = new FileBackedWeightStore(tempDir.toString());

        Map<String, Double> v1Weights = Map.of("rule1", 0.3);
        Map<String, Double> v2Weights = Map.of("rule1", 0.9);

        int v1 = store.save("prog-multi", v1Weights);
        int v2 = store.save("prog-multi", v2Weights);

        assertEquals(1, v1, "First save must be version 1");
        assertEquals(2, v2, "Second save must be version 2");

        Optional<Map<String, Double>> retrieved1 = store.get("prog-multi", 1);
        Optional<Map<String, Double>> retrieved2 = store.get("prog-multi", 2);

        assertTrue(retrieved1.isPresent(), "Version 1 must be retrievable");
        assertTrue(retrieved2.isPresent(), "Version 2 must be retrievable");
        assertEquals(0.3, retrieved1.get().get("rule1"), 1e-9, "Version 1 value must match");
        assertEquals(0.9, retrieved2.get().get("rule1"), 1e-9, "Version 2 value must match");
    }

    /**
     * Fact-sheet-scoped sub-stores must not share files.
     */
    @Test
    void factSheetScopedStores() throws Exception {
        FileBackedWeightStore store = new FileBackedWeightStore(tempDir.toString());

        FileWeightStore fs1 = store.fileWeightStoreFor("fs-1");
        FileWeightStore fs2 = store.fileWeightStoreFor("fs-2");

        Map<String, Double> weightsFs1 = Map.of("x", 0.11);
        Map<String, Double> weightsFs2 = Map.of("x", 0.99);
        fs1.save("shared-program", weightsFs1);
        fs2.save("shared-program", weightsFs2);

        Optional<Map<String, Double>> loadedFromFs1 = fs1.latest("shared-program");
        Optional<Map<String, Double>> loadedFromFs2 = fs2.latest("shared-program");

        assertTrue(loadedFromFs1.isPresent(), "fs-1 store must return its own weights");
        assertTrue(loadedFromFs2.isPresent(), "fs-2 store must return its own weights");
        assertEquals(0.11, loadedFromFs1.get().get("x"), 1e-9, "fs-1 value must be 0.11");
        assertEquals(0.99, loadedFromFs2.get().get("x"), 1e-9, "fs-2 value must be 0.99");
    }
}
