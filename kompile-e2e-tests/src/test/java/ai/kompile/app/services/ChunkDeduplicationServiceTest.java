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

package ai.kompile.app.services;

import ai.kompile.app.web.dto.ChunkManagerDtos.*;
import ai.kompile.core.embeddings.VectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ChunkDeduplicationService} — content hash dedup,
 * source+index dedup, keep policies, dry run, null vectorStore.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class ChunkDeduplicationServiceTest {

    @Mock
    private VectorStore vectorStore;

    private ChunkDeduplicationService service;

    @BeforeEach
    void setUp() {
        service = new ChunkDeduplicationService(vectorStore);
    }

    private Map<String, Object> doc(String id, String content, String sourceId, int chunkIndex) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source_id", sourceId);
        metadata.put("chunk_index", chunkIndex);
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", id);
        doc.put("content", content);
        doc.put("metadata", metadata);
        return doc;
    }

    private Map<String, Object> doc(String id, String content) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", id);
        doc.put("content", content);
        doc.put("metadata", Map.of());
        return doc;
    }

    // ─── analyzeDuplicates: null vectorStore ──────────────────────────

    @Test
    void analyzeDuplicates_nullVectorStore_returnsEmpty() {
        ChunkDeduplicationService nullService = new ChunkDeduplicationService(null);
        DuplicateAnalysisResponse resp = nullService.analyzeDuplicates("content_hash");

        assertEquals(0, resp.totalDuplicateGroups());
        assertEquals(0, resp.totalDuplicateChunks());
        assertEquals(0, resp.chunksToRemove());
        assertTrue(resp.groups().isEmpty());
    }

    // ─── analyzeDuplicates: content_hash strategy ─────────────────────

    @Test
    void analyzeDuplicates_noDuplicates_emptyGroups() {
        when(vectorStore.getAllVectorDocuments()).thenReturn(List.of(
                doc("c1", "Hello world"),
                doc("c2", "Goodbye world")));

        DuplicateAnalysisResponse resp = service.analyzeDuplicates("content_hash");

        assertEquals(0, resp.totalDuplicateGroups());
        assertEquals(0, resp.totalDuplicateChunks());
    }

    @Test
    void analyzeDuplicates_contentHash_findsDuplicates() {
        when(vectorStore.getAllVectorDocuments()).thenReturn(List.of(
                doc("c1", "Same content"),
                doc("c2", "Same content"),
                doc("c3", "Different content")));

        DuplicateAnalysisResponse resp = service.analyzeDuplicates("content_hash");

        assertEquals(1, resp.totalDuplicateGroups());
        assertEquals(2, resp.totalDuplicateChunks());
        assertEquals(1, resp.chunksToRemove());
        assertEquals("content_hash", resp.strategy());
    }

    @Test
    void analyzeDuplicates_contentHash_multipleGroups() {
        when(vectorStore.getAllVectorDocuments()).thenReturn(List.of(
                doc("c1", "AAA"), doc("c2", "AAA"),
                doc("c3", "BBB"), doc("c4", "BBB"), doc("c5", "BBB"),
                doc("c6", "CCC")));

        DuplicateAnalysisResponse resp = service.analyzeDuplicates("content_hash");

        assertEquals(2, resp.totalDuplicateGroups());
        assertEquals(5, resp.totalDuplicateChunks()); // 2 + 3
        assertEquals(3, resp.chunksToRemove()); // 1 + 2
    }

    @Test
    void analyzeDuplicates_contentHash_skipsEmptyContent() {
        when(vectorStore.getAllVectorDocuments()).thenReturn(List.of(
                doc("c1", ""),
                doc("c2", "")));

        DuplicateAnalysisResponse resp = service.analyzeDuplicates("content_hash");
        assertEquals(0, resp.totalDuplicateGroups());
    }

    @Test
    void analyzeDuplicates_contentHash_skipsNullContent() {
        Map<String, Object> d1 = new HashMap<>();
        d1.put("id", "c1");
        d1.put("content", null);
        Map<String, Object> d2 = new HashMap<>();
        d2.put("id", "c2");
        d2.put("content", null);
        when(vectorStore.getAllVectorDocuments()).thenReturn(List.of(d1, d2));

        DuplicateAnalysisResponse resp = service.analyzeDuplicates("content_hash");
        assertEquals(0, resp.totalDuplicateGroups());
    }

    // ─── analyzeDuplicates: source_and_index strategy ─────────────────

    @Test
    void analyzeDuplicates_sourceAndIndex_findsDuplicates() {
        when(vectorStore.getAllVectorDocuments()).thenReturn(List.of(
                doc("c1", "First version", "doc1.pdf", 0),
                doc("c2", "Updated version", "doc1.pdf", 0),
                doc("c3", "Other chunk", "doc2.pdf", 0)));

        DuplicateAnalysisResponse resp = service.analyzeDuplicates("source_and_index");

        assertEquals(1, resp.totalDuplicateGroups());
        assertEquals(2, resp.totalDuplicateChunks());
        assertEquals(1, resp.chunksToRemove());
    }

    @Test
    void analyzeDuplicates_sourceAndIndex_differentIndex_notDuplicate() {
        when(vectorStore.getAllVectorDocuments()).thenReturn(List.of(
                doc("c1", "Chunk 0", "doc1.pdf", 0),
                doc("c2", "Chunk 1", "doc1.pdf", 1)));

        DuplicateAnalysisResponse resp = service.analyzeDuplicates("source_and_index");
        assertEquals(0, resp.totalDuplicateGroups());
    }

    @Test
    void analyzeDuplicates_sourceAndIndex_nullSourceId_skipped() {
        Map<String, Object> d1 = doc("c1", "text");
        Map<String, Object> d2 = doc("c2", "text");
        when(vectorStore.getAllVectorDocuments()).thenReturn(List.of(d1, d2));

        DuplicateAnalysisResponse resp = service.analyzeDuplicates("source_and_index");
        assertEquals(0, resp.totalDuplicateGroups());
    }

    // ─── deduplicate ──────────────────────────────────────────────────

    @Test
    void deduplicate_nullVectorStore_returnsUnavailable() {
        ChunkDeduplicationService nullService = new ChunkDeduplicationService(null);
        DeduplicationResult result = nullService.deduplicate("content_hash", "first", false);

        assertEquals(0, result.chunksRemoved());
        assertFalse(result.success());
    }

    @Test
    void deduplicate_noDuplicates_returnsZero() {
        when(vectorStore.getAllVectorDocuments()).thenReturn(List.of(
                doc("c1", "unique1"),
                doc("c2", "unique2")));

        DeduplicationResult result = service.deduplicate("content_hash", "first", false);

        assertEquals(0, result.duplicateGroupsFound());
        assertEquals(0, result.chunksRemoved());
        assertTrue(result.success());
    }

    @Test
    void deduplicate_dryRun_doesNotRemove() {
        when(vectorStore.getAllVectorDocuments()).thenReturn(List.of(
                doc("c1", "Same"), doc("c2", "Same")));

        DeduplicationResult result = service.deduplicate("content_hash", "first", true);

        assertEquals(1, result.duplicateGroupsFound());
        assertEquals(0, result.chunksRemoved()); // dry run
        assertTrue(result.success());
        verify(vectorStore, never()).delete(anyList());
    }

    @Test
    void deduplicate_keepFirst_removesLater() {
        when(vectorStore.getAllVectorDocuments()).thenReturn(List.of(
                doc("c1", "Same"), doc("c2", "Same"), doc("c3", "Same")));
        when(vectorStore.delete(anyList())).thenReturn(true);

        DeduplicationResult result = service.deduplicate("content_hash", "first", false);

        assertEquals(1, result.duplicateGroupsFound());
        assertEquals(2, result.chunksRemoved());
        assertTrue(result.success());
        verify(vectorStore).delete(anyList());
    }

    @Test
    void deduplicate_keepLatest_removesEarlier() {
        when(vectorStore.getAllVectorDocuments()).thenReturn(List.of(
                doc("c1", "Same"), doc("c2", "Same")));
        when(vectorStore.delete(anyList())).thenReturn(true);

        DeduplicationResult result = service.deduplicate("content_hash", "latest", false);

        assertEquals(1, result.duplicateGroupsFound());
        assertEquals(1, result.chunksRemoved());
        assertTrue(result.success());
    }

    @Test
    void deduplicate_deleteFails_reportsFailure() {
        when(vectorStore.getAllVectorDocuments()).thenReturn(List.of(
                doc("c1", "Same"), doc("c2", "Same")));
        when(vectorStore.delete(anyList())).thenReturn(false);

        DeduplicationResult result = service.deduplicate("content_hash", "first", false);

        assertEquals(0, result.chunksRemoved());
        assertFalse(result.success());
    }

    // ─── Empty document list ──────────────────────────────────────────

    @Test
    void analyzeDuplicates_emptyDocs_returnsEmpty() {
        when(vectorStore.getAllVectorDocuments()).thenReturn(List.of());

        DuplicateAnalysisResponse resp = service.analyzeDuplicates("content_hash");
        assertEquals(0, resp.totalDuplicateGroups());
    }

    @Test
    void deduplicate_emptyDocs_noDuplicates() {
        when(vectorStore.getAllVectorDocuments()).thenReturn(List.of());

        DeduplicationResult result = service.deduplicate("content_hash", "first", false);
        assertEquals(0, result.duplicateGroupsFound());
        assertTrue(result.success());
    }
}
