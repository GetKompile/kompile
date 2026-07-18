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
 *  limitations under the License.
 */

package ai.kompile.crawl.graph;

import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphExtractionCheckpointStoreTest {

    @TempDir
    Path tempDir;

    private GraphExtractionCheckpointStore store;

    @BeforeEach
    void setUp() {
        System.setProperty("kompile.data.dir", tempDir.toString());
        store = new GraphExtractionCheckpointStore();
    }

    @AfterEach
    void clearProjectProperty() {
        System.clearProperty("kompile.data.dir");
    }

    @Test
    void recordCompletedBatchCreatesProjectScopedCheckpointFile() {
        GraphExtractionConfig config = GraphExtractionConfig.builder()
                .llmProvider("opencode")
                .modelName("deepseek-v4-flash-free")
                .schemaPresetId("fpna")
                .build();
        RetrievedDoc doc = doc("doc-random-1", "/fpna/a.xlsx", 0, "Revenue forecast text");

        store.recordCompletedBatch(42L, config, List.of(doc), "crawl-1", 12, 8);

        Path expected = tempDir.resolve("data/graph/42/graph-extraction-checkpoints.json");
        assertTrue(Files.exists(expected));
        assertTrue(store.completedChunkKeys(42L, config).contains(store.chunkKey(doc)));
    }

    @Test
    void clearFactSheetDeletesProjectScopedCheckpointFile() {
        GraphExtractionConfig config = GraphExtractionConfig.builder()
                .llmProvider("local")
                .modelName("lfm2.5-1.2b-instruct")
                .schemaPresetId("fpna")
                .build();
        RetrievedDoc doc = doc("doc-random-1", "/fpna/a.xlsx", 0, "Revenue forecast text");

        store.recordCompletedBatch(42L, config, List.of(doc), "crawl-1", 12, 8);
        Path expected = tempDir.resolve("data/graph/42/graph-extraction-checkpoints.json");
        assertTrue(Files.exists(expected));

        store.clearFactSheet(42L);

        assertFalse(Files.exists(expected));
        assertTrue(store.completedChunkKeys(42L, config).isEmpty());
    }

    @Test
    void checkpointsFromOneProjectAreInvisibleToAnotherProject() {
        GraphExtractionConfig config = GraphExtractionConfig.builder()
                .llmProvider("local")
                .modelName("lfm2.5-1.2b-instruct")
                .schemaPresetId("fpna")
                .build();
        RetrievedDoc doc = doc("doc-a", "/fpna/a.xlsx", 0, "same text");
        store.recordCompletedBatch(42L, config, List.of(doc), "crawl-1", 1, 1);

        Path secondProject = tempDir.resolve("second-project");
        System.setProperty("kompile.data.dir", secondProject.toString());
        GraphExtractionCheckpointStore secondStore = new GraphExtractionCheckpointStore();

        assertTrue(secondStore.completedChunkKeys(42L, config).isEmpty());
        assertFalse(Files.exists(secondProject.resolve("data/graph/42/graph-extraction-checkpoints.json")));
    }

    @Test
    void chunkKeyIgnoresRegeneratedDocumentIdWhenStableMetadataExists() {
        RetrievedDoc firstRun = doc("random-id-run-1", "/fpna/a.xlsx", 3, "same text");
        RetrievedDoc secondRun = doc("random-id-run-2", "/fpna/a.xlsx", 3, "same text");

        assertEquals(store.chunkKey(firstRun), store.chunkKey(secondRun));
    }

    @Test
    void completedKeysAreScopedByExtractionConfigFingerprint() {
        GraphExtractionConfig original = GraphExtractionConfig.builder()
                .llmProvider("opencode")
                .modelName("deepseek-v4-flash-free")
                .schemaPresetId("fpna")
                .build();
        GraphExtractionConfig changedModel = GraphExtractionConfig.builder()
                .llmProvider("opencode")
                .modelName("another-model")
                .schemaPresetId("fpna")
                .build();
        RetrievedDoc doc = doc("doc-a", "/fpna/a.xlsx", 1, "same text");

        store.recordCompletedBatch(null, original, List.of(doc), "crawl-1", 1, 2);

        assertTrue(store.completedChunkKeys(null, original).contains(store.chunkKey(doc)));
        assertFalse(store.completedChunkKeys(null, changedModel).contains(store.chunkKey(doc)));
    }

    private static RetrievedDoc doc(String id, String sourcePath, int chunkIndex, String text) {
        return new RetrievedDoc(id, text, Map.of(
                GraphConstants.META_SOURCE_PATH, sourcePath,
                "chunk_index", chunkIndex));
    }
}
