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

package ai.kompile.core.crawl.graph.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip tests for {@link DocumentArchiveStore} — the disk persistence used to archive a crawl
 * step's chunked documents so the step can run later. The most fidelity-sensitive concern is that a
 * Spring AI {@link Document} survives a JSONL round-trip with id/text/metadata intact.
 */
class DocumentArchiveStoreTest {

    private final DocumentArchiveStore store = new DocumentArchiveStore(new ObjectMapper());

    @Test
    void roundTripsTextAndMetadata(@TempDir Path dir) throws IOException {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("source", "doc-a.txt");
        meta.put("pageNum", 42);
        meta.put("score", 0.95);
        meta.put("flag", true);
        Document doc = new Document("id-1", "hello world", meta);

        int written = store.writeChunks(dir, List.of(doc));
        assertEquals(1, written);

        List<Document> back = store.readChunks(dir);
        assertEquals(1, back.size());
        Document r = back.get(0);
        assertEquals("id-1", r.getId());
        assertEquals("hello world", r.getText());
        assertEquals("doc-a.txt", r.getMetadata().get("source"));
        assertEquals(42, ((Number) r.getMetadata().get("pageNum")).intValue());
        assertEquals(0.95, ((Number) r.getMetadata().get("score")).doubleValue(), 1e-9);
        assertEquals(true, r.getMetadata().get("flag"));
    }

    @Test
    void preservesOrderForManyDocuments(@TempDir Path dir) throws IOException {
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            docs.add(new Document("id-" + i, "chunk text " + i, Map.of("idx", i)));
        }
        store.writeChunks(dir, docs);
        List<Document> back = store.readChunks(dir);
        assertEquals(1000, back.size());
        for (int i = 0; i < 1000; i++) {
            assertEquals("id-" + i, back.get(i).getId());
            assertEquals("chunk text " + i, back.get(i).getText());
        }
    }

    @Test
    void skipsMediaAndEmptyDocuments(@TempDir Path dir) throws IOException {
        Document withText = new Document("keep", "real text", Map.of());
        Document empty = new Document("drop", "", Map.of());
        int written = store.writeChunks(dir, List.of(withText, empty));
        assertEquals(1, written, "empty-text documents are skipped");
        List<Document> back = store.readChunks(dir);
        assertEquals(1, back.size());
        assertEquals("keep", back.get(0).getId());
    }

    @Test
    void writeIsAtomicWithNoLingeringTempFile(@TempDir Path dir) throws IOException {
        store.writeChunks(dir, List.of(new Document("a", "x", Map.of())));
        assertTrue(Files.exists(dir.resolve(DocumentArchiveStore.CHUNKS_FILE)));
        assertFalse(Files.exists(dir.resolve(DocumentArchiveStore.CHUNKS_FILE + ".tmp")),
                "temp file must be moved into place, not left behind");
    }

    @Test
    void emptyListProducesEmptyReadback(@TempDir Path dir) throws IOException {
        int written = store.writeChunks(dir, List.of());
        assertEquals(0, written);
        assertTrue(store.readChunks(dir).isEmpty());
    }

    @Test
    void missingDirectoryReadsAsEmpty(@TempDir Path dir) throws IOException {
        assertTrue(store.readChunks(dir.resolve("does-not-exist")).isEmpty());
    }

    @Test
    void skipsCorruptLinesOnRead(@TempDir Path dir) throws IOException {
        store.writeChunks(dir, List.of(new Document("ok", "good", Map.of())));
        // Append a corrupt line that is not valid JSON.
        Files.writeString(dir.resolve(DocumentArchiveStore.CHUNKS_FILE),
                "\n{ this is not json", java.nio.file.StandardOpenOption.APPEND);
        List<Document> back = store.readChunks(dir);
        assertEquals(1, back.size(), "valid documents still load; corrupt line is skipped");
        assertEquals("ok", back.get(0).getId());
    }

    @Test
    void roundTripsStepConfig(@TempDir Path dir) throws IOException {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("enabled", true);
        cfg.put("batchSize", 16);
        store.writeStepConfig(dir, cfg);

        String json = store.readStepConfigJson(dir);
        assertNotNull(json);
        assertTrue(json.contains("batchSize"));

        @SuppressWarnings("unchecked")
        Map<String, Object> read = store.readStepConfig(dir, Map.class);
        assertNotNull(read);
        assertEquals(true, read.get("enabled"));
        assertEquals(16, ((Number) read.get("batchSize")).intValue());
    }

    @Test
    void readStepConfigReturnsNullWhenAbsent(@TempDir Path dir) throws IOException {
        assertEquals(null, store.readStepConfig(dir, Map.class));
        assertEquals(null, store.readStepConfigJson(dir));
    }
}
