package ai.kompile.graphchangetracking.service;

import ai.kompile.graphchangetracking.domain.GraphMutationRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GraphMutationStore startup rehydration and JSONL compaction.
 *
 * <p>Uses a TempDir to avoid touching the real ~/.kompile/graph-mutations.jsonl.</p>
 */
class GraphMutationStoreRehydrationTest {

    @TempDir
    Path tempDir;

    private Path jsonlPath;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        jsonlPath = tempDir.resolve("graph-mutations.jsonl");
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        // Disable date-as-timestamps so LocalDateTime round-trips correctly
        objectMapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /** Build a store that uses our tempDir path instead of ~/.kompile. */
    private GraphMutationStore makeStore() {
        return new GraphMutationStore(objectMapper, jsonlPath);
    }

    // ─── Rehydration ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Fresh store with no JSONL file starts empty")
    void freshStore_noFile_startsEmpty() {
        GraphMutationStore store = makeStore();
        assertEquals(0, store.findAll(org.springframework.data.domain.Pageable.unpaged()).getTotalElements());
    }

    @Test
    @DisplayName("Store rehydrates records from existing JSONL on construction")
    void rehydration_loadsExistingRecords() throws Exception {
        // Pre-populate the JSONL file
        GraphMutationRecord rec1 = GraphMutationRecord.builder()
                .id(1L).mutationId("m1").mutationType("NODE_CREATED")
                .entityKind("NODE").entityId("n1").factSheetId(42L)
                .occurredAt(LocalDateTime.now().minusDays(1))
                .build();
        GraphMutationRecord rec2 = GraphMutationRecord.builder()
                .id(2L).mutationId("m2").mutationType("EDGE_CREATED")
                .entityKind("EDGE").entityId("e1").factSheetId(42L)
                .occurredAt(LocalDateTime.now().minusHours(1))
                .build();
        writeJsonl(List.of(rec1, rec2));

        GraphMutationStore store = makeStore();

        var page = store.findAll(org.springframework.data.domain.Pageable.unpaged());
        assertEquals(2, page.getTotalElements());

        List<GraphMutationRecord> forFactSheet =
                store.findByEntityKindAndEntityIdOrderByOccurredAtDesc("NODE", "n1");
        assertEquals(1, forFactSheet.size());
        assertEquals("NODE_CREATED", forFactSheet.get(0).getMutationType());
    }

    @Test
    @DisplayName("Rehydration skips malformed lines and loads the rest")
    void rehydration_skipsMalformedLines() throws Exception {
        GraphMutationRecord rec = GraphMutationRecord.builder()
                .id(1L).mutationId("m1").mutationType("NODE_CREATED")
                .entityKind("NODE").entityId("n1").factSheetId(7L)
                .occurredAt(LocalDateTime.now())
                .build();
        try (BufferedWriter writer = Files.newBufferedWriter(jsonlPath,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.write(objectMapper.writeValueAsString(rec));
            writer.newLine();
            writer.write("{ this is not json }}}");
            writer.newLine();
        }

        GraphMutationStore store = makeStore();
        // Only the valid line should be loaded
        assertEquals(1, store.findAll(org.springframework.data.domain.Pageable.unpaged()).getTotalElements());
    }

    @Test
    @DisplayName("New saves after rehydration append correctly and do not collide with rehydrated IDs")
    void rehydration_newSavesDoNotCollideWithLoadedIds() throws Exception {
        GraphMutationRecord existing = GraphMutationRecord.builder()
                .id(50L).mutationId("m50").mutationType("NODE_CREATED")
                .entityKind("NODE").entityId("n50").factSheetId(1L)
                .occurredAt(LocalDateTime.now().minusMinutes(10))
                .build();
        writeJsonl(List.of(existing));

        GraphMutationStore store = makeStore();

        GraphMutationRecord newRec = GraphMutationRecord.builder()
                .mutationType("EDGE_CREATED").entityKind("EDGE").entityId("e99")
                .factSheetId(1L).occurredAt(LocalDateTime.now())
                .build();
        GraphMutationRecord saved = store.save(newRec);

        assertNotNull(saved.getId());
        // ID must be > 50 (rehydration bumps idSeq past the highest loaded ID)
        assertTrue(saved.getId() > 50L,
                "Expected new ID > 50 but was " + saved.getId());
    }

    // ─── Compaction ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("deleteOlderThan removes in-memory records and compacts the JSONL file")
    void deleteOlderThan_compactsJsonl() throws Exception {
        GraphMutationStore store = makeStore();

        LocalDateTime old = LocalDateTime.now().minusDays(2);
        LocalDateTime recent = LocalDateTime.now().minusHours(1);

        GraphMutationRecord r1 = GraphMutationRecord.builder()
                .mutationType("NODE_CREATED").entityKind("NODE").entityId("n1")
                .factSheetId(1L).occurredAt(old).build();
        GraphMutationRecord r2 = GraphMutationRecord.builder()
                .mutationType("NODE_CREATED").entityKind("NODE").entityId("n2")
                .factSheetId(1L).occurredAt(recent).build();
        store.save(r1);
        store.save(r2);

        // File should have 2 lines
        long linesBefore = Files.lines(jsonlPath).count();
        assertEquals(2, linesBefore);

        int removed = store.deleteOlderThan(LocalDateTime.now().minusHours(2));
        assertEquals(1, removed);

        // In-memory should have 1 record
        assertEquals(1, store.findAll(org.springframework.data.domain.Pageable.unpaged()).getTotalElements());

        // JSONL file should now have 1 line (compacted)
        long linesAfter = Files.lines(jsonlPath).count();
        assertEquals(1, linesAfter);
    }

    @Test
    @DisplayName("deleteOlderThan with no deletions does not compact the file")
    void deleteOlderThan_noOp_doesNotCompact() throws Exception {
        GraphMutationStore store = makeStore();

        GraphMutationRecord r = GraphMutationRecord.builder()
                .mutationType("NODE_CREATED").entityKind("NODE").entityId("n1")
                .factSheetId(1L).occurredAt(LocalDateTime.now()).build();
        store.save(r);

        long beforeModified = Files.getLastModifiedTime(jsonlPath).toMillis();
        // Small sleep to ensure file mod time would change if file were rewritten
        Thread.sleep(50);

        int removed = store.deleteOlderThan(LocalDateTime.now().minusDays(10));
        assertEquals(0, removed);

        // File modification time should NOT have changed (no compaction needed)
        long afterModified = Files.getLastModifiedTime(jsonlPath).toMillis();
        assertEquals(beforeModified, afterModified, "File should not have been rewritten when no records deleted");
    }

    @Test
    @DisplayName("Compaction preserves newest records in correct content")
    void compaction_preservesNewestRecordsContent() throws Exception {
        GraphMutationStore store = makeStore();

        LocalDateTime t1 = LocalDateTime.now().minusDays(5);
        LocalDateTime t2 = LocalDateTime.now().minusHours(2);
        LocalDateTime t3 = LocalDateTime.now().minusMinutes(10);

        store.save(GraphMutationRecord.builder().mutationType("N").entityKind("NODE")
                .entityId("old1").factSheetId(1L).occurredAt(t1).build());
        store.save(GraphMutationRecord.builder().mutationType("N").entityKind("NODE")
                .entityId("keep1").factSheetId(1L).occurredAt(t2).build());
        store.save(GraphMutationRecord.builder().mutationType("N").entityKind("NODE")
                .entityId("keep2").factSheetId(1L).occurredAt(t3).build());

        // Delete records older than 3 hours ago
        int removed = store.deleteOlderThan(LocalDateTime.now().minusHours(3));
        assertEquals(1, removed);

        // Re-load the JSONL file into a fresh store to verify compacted content
        GraphMutationStore freshStore = makeStore();
        var all = freshStore.findAll(org.springframework.data.domain.Pageable.unpaged()).getContent();
        assertEquals(2, all.size());
        assertTrue(all.stream().anyMatch(r -> "keep1".equals(r.getEntityId())));
        assertTrue(all.stream().anyMatch(r -> "keep2".equals(r.getEntityId())));
        assertFalse(all.stream().anyMatch(r -> "old1".equals(r.getEntityId())));
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private void writeJsonl(List<GraphMutationRecord> records) throws Exception {
        try (BufferedWriter writer = Files.newBufferedWriter(jsonlPath,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            for (GraphMutationRecord rec : records) {
                writer.write(objectMapper.writeValueAsString(rec));
                writer.newLine();
            }
        }
    }

}
