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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A crash can leave the JSON index state empty: the rename reaches the disk before the
 * data. The loaders name such a file, and the next index pass rebuilds it from source
 * instead of failing every index, reindex and repair call.
 */
@TemporaryUserHome
class IndexStateSelfHealTest {

    @TempDir Path temp;
    private final String project = "index-self-heal-test-" + UUID.randomUUID();

    @AfterEach
    void cleanup() throws Exception {
        Path dir = LocalCodeIndexer.getIndexDir(project);
        IndexMaintenance.invalidate(dir);
        if (Files.exists(dir)) {
            try (var paths = Files.walk(dir)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static IndexFileStore store(Path dir) {
        return new IndexFileStore(dir, JsonUtils.standardMapper());
    }

    private String index(LocalCodeIndexer indexer) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            indexer.index(temp, project, null, null, false, out);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static void assertUnreadable(Path file, Executable load, String content) {
        IndexFileStore.UnreadableIndexStateException error = assertThrows(
                IndexFileStore.UnreadableIndexStateException.class, load, file.getFileName() + " '" + content + "'");
        assertEquals(file, error.file());
        assertTrue(error.getMessage().contains("action=index"), error.getMessage());
    }

    @Test
    void emptyZeroedOrNonObjectStateIsReportedWithItsPath() throws Exception {
        Path dir = temp.resolve("state");
        Files.createDirectories(dir);
        IndexFileStore store = store(dir);
        assertEquals(Map.of(), store.loadMetadata(), "an absent file is an empty index, not an error");
        assertEquals(Map.of(), store.loadFingerprints());

        Path metadata = dir.resolve(IndexFileStore.METADATA_FILE);
        Path fingerprints = dir.resolve(IndexFileStore.FINGERPRINTS_FILE);
        for (String content : List.of("", "\0\0\0\0\0\0", "{\"rootPath\":", "null", "[]")) {
            Files.writeString(metadata, content);
            Files.writeString(fingerprints, content);
            assertUnreadable(metadata, store::loadMetadata, content);
            assertUnreadable(fingerprints, store::loadFingerprints, content);
        }

        Files.writeString(metadata, "{}");
        Files.writeString(fingerprints, "{}");
        assertEquals(Map.of(), store.loadMetadata());
        assertEquals(Map.of(), store.loadFingerprints());
    }

    @Test
    void stateWritesRoundTripAndLeaveNoTemporaryFile() throws Exception {
        Path dir = temp.resolve("not-yet-created");
        IndexFileStore store = store(dir);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("projectId", "p");
        metadata.put("filesProcessed", 2);
        Map<String, IndexFileStore.FileFingerprint> fingerprints = new LinkedHashMap<>();
        fingerprints.put("Alpha.java", new IndexFileStore.FileFingerprint(1L, 2L, "sha"));

        store.saveMetadata(metadata);
        store.saveFingerprints(fingerprints);
        store.markUpdatePending("generation-1");

        assertEquals(metadata, store.loadMetadata());
        assertEquals(fingerprints, store.loadFingerprints());
        assertTrue(store.hasPendingUpdate());
        assertEquals("generation-1", Files.readString(dir.resolve(IndexFileStore.UPDATE_PENDING_FILE)));
        try (var files = Files.list(dir)) {
            assertEquals(List.of(), files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".tmp")).toList());
        }
        store.clearUpdatePending();
        assertFalse(store.hasPendingUpdate());
    }

    @Test
    void emptiedStateFilesAreRebuiltByTheNextPass() throws Exception {
        Files.writeString(temp.resolve("Alpha.java"), "public class Alpha {}\n");
        Files.writeString(temp.resolve("Beta.java"), "public class Beta {}\n");
        var indexer = new LocalCodeIndexer();
        index(indexer);
        Path dir = LocalCodeIndexer.getIndexDir(project);
        // What a crash right after a pass leaves behind: both files present and empty.
        Files.write(dir.resolve(IndexFileStore.METADATA_FILE), new byte[0]);
        Files.write(dir.resolve(IndexFileStore.FINGERPRINTS_FILE), new byte[0]);

        IndexMaintenance.Result check = IndexMaintenance.repair(project);
        assertTrue(check.reindexRequired(), check.summary());
        assertTrue(check.summary().contains("index metadata missing or unreadable"), check.summary());

        List<String> alerts = new ArrayList<>();
        Runnable uninstall = CodeIndexDiagnostics.installAlertSink(alerts::add);
        String output;
        try {
            output = index(indexer);
        } finally {
            uninstall.run();
        }

        assertTrue(output.contains("(full re-index)"), output);
        assertTrue(output.contains("Rebuilding: metadata.json and fingerprints.json empty or unreadable"), output);
        assertEquals(1, alerts.stream().filter(alert -> alert.startsWith("[code-index] ")
                && alert.contains("metadata.json and fingerprints.json empty or unreadable")).count(), alerts.toString());
        IndexFileStore store = store(dir);
        assertEquals(temp.toAbsolutePath().normalize().toString(), store.loadMetadata().get("rootPath"));
        assertEquals(Set.of("Alpha.java", "Beta.java"), store.loadFingerprints().keySet());
        assertFalse(indexer.search(project, "Alpha", "CLASS", 10).isEmpty());
        assertFalse(store.hasPendingUpdate());
        assertFalse(IndexMaintenance.repair(project).reindexRequired());
    }

    @Test
    void emptiedFingerprintsAloneStillDropDeletedFiles() throws Exception {
        Files.writeString(temp.resolve("Alpha.java"), "public class Alpha {}\n");
        Files.writeString(temp.resolve("Beta.java"), "public class Beta {}\n");
        var indexer = new LocalCodeIndexer();
        index(indexer);
        Path dir = LocalCodeIndexer.getIndexDir(project);
        Files.write(dir.resolve(IndexFileStore.FINGERPRINTS_FILE), new byte[0]);
        Files.delete(temp.resolve("Beta.java"));

        String output = index(indexer);

        // The metadata, DB generation and parser version are all intact: only the torn
        // fingerprints force the full pass, the one that drops files deleted meanwhile.
        assertTrue(output.contains("(full re-index)"), output);
        assertTrue(output.contains("Rebuilding: fingerprints.json empty or unreadable"), output);
        assertTrue(indexer.search(project, "Beta", "CLASS", 10).stream()
                .noneMatch(row -> "Beta".equals(row.get("name"))), "a deleted file survived the rebuild");
        assertFalse(indexer.search(project, "Alpha", "CLASS", 10).isEmpty());
        assertEquals(Set.of("Alpha.java"), store(dir).loadFingerprints().keySet());
    }
}
