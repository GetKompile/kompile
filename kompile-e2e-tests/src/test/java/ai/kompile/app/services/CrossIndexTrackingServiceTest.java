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

import ai.kompile.app.ingest.domain.IndexedDocument;
import ai.kompile.app.ingest.domain.IndexedDocument.IndexStatus;
import ai.kompile.app.ingest.domain.IndexedDocument.OverallIndexStatus;
import ai.kompile.app.ingest.domain.IndexedPassage;
import ai.kompile.app.ingest.repository.IndexedDocumentRepository;
import ai.kompile.app.ingest.repository.IndexedPassageRepository;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CrossIndexTrackingService}.
 * Verifies document registration, passage tracking, cross-index resolution,
 * statistics, and cleanup behavior using mocked repositories.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CrossIndexTrackingServiceTest {

    @Mock
    private IndexedDocumentRepository documentRepository;

    @Mock
    private IndexedPassageRepository passageRepository;

    private CrossIndexTrackingService service;

    private static final Long FACT_SHEET_ID = 42L;
    private static final Long FACT_ID = 7L;
    private static final Long DOC_ID = 100L;
    private static final String SOURCE_ID = "file:///data/test.pdf";
    private static final String FILE_NAME = "test.pdf";
    private static final String CHECKSUM = "abc123def456";
    private static final String CHUNK_ID = "chunk-uuid-001";

    @BeforeEach
    void setUp() {
        service = new CrossIndexTrackingService(documentRepository, passageRepository);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // registerDocument
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void registerDocument_returnsExistingDocWhenChecksumUnchanged() {
        IndexedDocument existing = mock(IndexedDocument.class);
        when(existing.getChecksum()).thenReturn(CHECKSUM);
        when(documentRepository.findBySourceIdAndFactSheetId(SOURCE_ID, FACT_SHEET_ID))
                .thenReturn(Optional.of(existing));

        IndexedDocument result = service.registerDocument(SOURCE_ID, FILE_NAME, CHECKSUM, FACT_ID, FACT_SHEET_ID);

        assertSame(existing, result);
        verify(documentRepository, never()).save(any());
    }

    @Test
    void registerDocument_marksStaleAndSavesWhenChecksumChanged() {
        IndexedDocument existing = mock(IndexedDocument.class);
        when(existing.getChecksum()).thenReturn("old-checksum");
        when(documentRepository.findBySourceIdAndFactSheetId(SOURCE_ID, FACT_SHEET_ID))
                .thenReturn(Optional.of(existing));
        when(documentRepository.save(existing)).thenReturn(existing);

        IndexedDocument result = service.registerDocument(SOURCE_ID, FILE_NAME, CHECKSUM, FACT_ID, FACT_SHEET_ID);

        verify(existing).setChecksum(CHECKSUM);
        verify(existing).markAsStale();
        verify(documentRepository).save(existing);
        assertSame(existing, result);
    }

    @Test
    void registerDocument_createsNewDocWhenNotFound() {
        when(documentRepository.findBySourceIdAndFactSheetId(SOURCE_ID, FACT_SHEET_ID))
                .thenReturn(Optional.empty());
        ArgumentCaptor<IndexedDocument> captor = ArgumentCaptor.forClass(IndexedDocument.class);
        IndexedDocument saved = mock(IndexedDocument.class);
        when(documentRepository.save(captor.capture())).thenReturn(saved);

        IndexedDocument result = service.registerDocument(SOURCE_ID, FILE_NAME, CHECKSUM, FACT_ID, FACT_SHEET_ID);

        IndexedDocument created = captor.getValue();
        assertNotNull(created);
        assertEquals(SOURCE_ID, created.getSourceId());
        assertEquals(FILE_NAME, created.getFileName());
        assertEquals(CHECKSUM, created.getChecksum());
        assertEquals(FACT_ID, created.getFactId());
        assertEquals(FACT_SHEET_ID, created.getFactSheetId());
        assertSame(saved, result);
    }

    @Test
    void registerDocument_nullChecksumDoesNotTriggerStale() {
        IndexedDocument existing = mock(IndexedDocument.class);
        when(existing.getChecksum()).thenReturn(CHECKSUM);
        when(documentRepository.findBySourceIdAndFactSheetId(SOURCE_ID, FACT_SHEET_ID))
                .thenReturn(Optional.of(existing));

        IndexedDocument result = service.registerDocument(SOURCE_ID, FILE_NAME, null, FACT_ID, FACT_SHEET_ID);

        verify(existing, never()).markAsStale();
        verify(documentRepository, never()).save(any());
        assertSame(existing, result);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getOrCreateDocument
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void getOrCreateDocument_returnsExistingIfPresent() {
        IndexedDocument existing = mock(IndexedDocument.class);
        when(documentRepository.findBySourceIdAndFactSheetId(SOURCE_ID, FACT_SHEET_ID))
                .thenReturn(Optional.of(existing));

        IndexedDocument result = service.getOrCreateDocument(SOURCE_ID, FACT_SHEET_ID);

        assertSame(existing, result);
        verify(documentRepository, never()).save(any());
    }

    @Test
    void getOrCreateDocument_createsWhenNotFound() {
        // First call (getOrCreate check), second call (registerDocument check)
        when(documentRepository.findBySourceIdAndFactSheetId(SOURCE_ID, FACT_SHEET_ID))
                .thenReturn(Optional.empty());
        IndexedDocument saved = mock(IndexedDocument.class);
        when(documentRepository.save(any(IndexedDocument.class))).thenReturn(saved);

        IndexedDocument result = service.getOrCreateDocument(SOURCE_ID, FACT_SHEET_ID);

        verify(documentRepository).save(any(IndexedDocument.class));
        assertSame(saved, result);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // findDocument / findDocumentBySourceId
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void findDocument_delegatesToRepositoryFindById() {
        IndexedDocument doc = mock(IndexedDocument.class);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(doc));

        Optional<IndexedDocument> result = service.findDocument(DOC_ID);

        assertTrue(result.isPresent());
        assertSame(doc, result.get());
    }

    @Test
    void findDocument_returnsEmptyWhenNotFound() {
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.empty());

        Optional<IndexedDocument> result = service.findDocument(DOC_ID);

        assertFalse(result.isPresent());
    }

    @Test
    void findDocumentBySourceId_delegatesToRepository() {
        IndexedDocument doc = mock(IndexedDocument.class);
        when(documentRepository.findBySourceIdAndFactSheetId(SOURCE_ID, FACT_SHEET_ID))
                .thenReturn(Optional.of(doc));

        Optional<IndexedDocument> result = service.findDocumentBySourceId(SOURCE_ID, FACT_SHEET_ID);

        assertTrue(result.isPresent());
        assertSame(doc, result.get());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // updateKeywordIndexStatus / updateVectorStoreStatus / updateGraphStatus
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void updateKeywordIndexStatus_callsDocMethodAndSaves() {
        IndexedDocument doc = mock(IndexedDocument.class);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(doc));
        when(documentRepository.save(doc)).thenReturn(doc);

        service.updateKeywordIndexStatus(DOC_ID, IndexStatus.INDEXED, "/index/path", 50);

        verify(doc).updateKeywordStatus(IndexStatus.INDEXED, "/index/path", 50);
        verify(documentRepository).save(doc);
    }

    @Test
    void updateKeywordIndexStatus_doesNothingWhenDocNotFound() {
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.empty());

        service.updateKeywordIndexStatus(DOC_ID, IndexStatus.INDEXED, "/index/path", 50);

        verify(documentRepository, never()).save(any());
    }

    @Test
    void updateVectorStoreStatus_callsDocMethodAndSaves() {
        IndexedDocument doc = mock(IndexedDocument.class);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(doc));
        when(documentRepository.save(doc)).thenReturn(doc);

        service.updateVectorStoreStatus(DOC_ID, IndexStatus.INDEXED, "/vector/path", 50);

        verify(doc).updateVectorStatus(IndexStatus.INDEXED, "/vector/path", 50);
        verify(documentRepository).save(doc);
    }

    @Test
    void updateGraphStatus_callsDocMethodAndSaves() {
        IndexedDocument doc = mock(IndexedDocument.class);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(doc));
        when(documentRepository.save(doc)).thenReturn(doc);

        service.updateGraphStatus(DOC_ID, IndexStatus.INDEXED, 10);

        verify(doc).updateGraphStatus(IndexStatus.INDEXED, 10);
        verify(documentRepository).save(doc);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // registerPassage
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void registerPassage_returnsExistingWhenChunkIdFound() {
        IndexedDocument doc = mock(IndexedDocument.class);
        IndexedPassage existing = mock(IndexedPassage.class);
        when(passageRepository.findByChunkId(CHUNK_ID)).thenReturn(Optional.of(existing));

        IndexedPassage result = service.registerPassage(doc, CHUNK_ID, 0, "content");

        assertSame(existing, result);
        verify(passageRepository, never()).save(any());
    }

    @Test
    void registerPassage_createsAndSavesWhenNotFound() {
        IndexedDocument doc = IndexedDocument.create(SOURCE_ID, FILE_NAME, CHECKSUM, FACT_ID, FACT_SHEET_ID);
        when(passageRepository.findByChunkId(CHUNK_ID)).thenReturn(Optional.empty());
        IndexedPassage saved = mock(IndexedPassage.class);
        when(passageRepository.save(any(IndexedPassage.class))).thenReturn(saved);

        IndexedPassage result = service.registerPassage(doc, CHUNK_ID, 0, "some content text");

        verify(passageRepository).save(any(IndexedPassage.class));
        assertSame(saved, result);
    }

    @Test
    void registerPassage_truncatesLongContent() {
        IndexedDocument doc = IndexedDocument.create(SOURCE_ID, FILE_NAME, CHECKSUM, FACT_ID, FACT_SHEET_ID);
        String longContent = "x".repeat(1000);
        when(passageRepository.findByChunkId(CHUNK_ID)).thenReturn(Optional.empty());
        ArgumentCaptor<IndexedPassage> captor = ArgumentCaptor.forClass(IndexedPassage.class);
        when(passageRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.registerPassage(doc, CHUNK_ID, 0, longContent);

        IndexedPassage created = captor.getValue();
        assertNotNull(created.getContentPreview());
        assertThat(created.getContentPreview().length()).isLessThanOrEqualTo(500);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // registerPassages
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void registerPassages_registersAllChunksAndUpdatesPassageCount() {
        IndexedDocument doc = mock(IndexedDocument.class);
        when(doc.getFactSheetId()).thenReturn(FACT_SHEET_ID);

        RetrievedDoc chunk1 = new RetrievedDoc("chunk-1", "text one", new HashMap<>());
        RetrievedDoc chunk2 = new RetrievedDoc("chunk-2", "text two", new HashMap<>());

        when(passageRepository.findByChunkId("chunk-1")).thenReturn(Optional.empty());
        when(passageRepository.findByChunkId("chunk-2")).thenReturn(Optional.empty());
        when(passageRepository.save(any(IndexedPassage.class))).thenAnswer(inv -> inv.getArgument(0));
        when(documentRepository.save(doc)).thenReturn(doc);

        List<IndexedPassage> results = service.registerPassages(doc, List.of(chunk1, chunk2));

        assertThat(results).hasSize(2);
        verify(doc).setKeywordPassageCount(2);
        verify(documentRepository).save(doc);
    }

    @Test
    void registerPassages_usesChunkIndexFromMetadataWhenAvailable() {
        IndexedDocument doc = mock(IndexedDocument.class);
        when(doc.getFactSheetId()).thenReturn(FACT_SHEET_ID);

        Map<String, Object> meta = new HashMap<>();
        meta.put("chunk_index", 5);
        RetrievedDoc chunk = new RetrievedDoc("chunk-meta", "content", meta);

        when(passageRepository.findByChunkId("chunk-meta")).thenReturn(Optional.empty());
        ArgumentCaptor<IndexedPassage> captor = ArgumentCaptor.forClass(IndexedPassage.class);
        when(passageRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));
        when(documentRepository.save(doc)).thenReturn(doc);

        service.registerPassages(doc, List.of(chunk));

        IndexedPassage created = captor.getValue();
        assertEquals(5, created.getChunkIndex());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // findPassage / mark*Indexed
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void findPassage_delegatesToPassageRepository() {
        IndexedPassage passage = mock(IndexedPassage.class);
        when(passageRepository.findByChunkId(CHUNK_ID)).thenReturn(Optional.of(passage));

        Optional<IndexedPassage> result = service.findPassage(CHUNK_ID);

        assertTrue(result.isPresent());
        assertSame(passage, result.get());
    }

    @Test
    void markPassageKeywordIndexed_callsRepositoryWithInstant() {
        service.markPassageKeywordIndexed(CHUNK_ID, 42);

        verify(passageRepository).markKeywordIndexed(eq(CHUNK_ID), eq(42), any());
    }

    @Test
    void markPassageVectorIndexed_callsRepositoryWithInstant() {
        service.markPassageVectorIndexed(CHUNK_ID, "vec-id-123");

        verify(passageRepository).markVectorIndexed(eq(CHUNK_ID), eq("vec-id-123"), any());
    }

    @Test
    void markPassageGraphIndexed_callsRepositoryWithInstant() {
        service.markPassageGraphIndexed(CHUNK_ID, "graph-node-001");

        verify(passageRepository).markGraphIndexed(eq(CHUNK_ID), eq("graph-node-001"), any());
    }

    @Test
    void markPassagesVectorIndexed_callsBatchUpdateWithIndexedStatus() {
        List<String> chunkIds = List.of("c1", "c2", "c3");

        service.markPassagesVectorIndexed(chunkIds);

        verify(passageRepository).updateVectorStatusBatch(eq(chunkIds), eq(IndexStatus.INDEXED), any());
    }

    @Test
    void markPassagesGraphIndexed_callsBatchUpdateWithIndexedStatus() {
        List<String> chunkIds = List.of("c1", "c2");

        service.markPassagesGraphIndexed(chunkIds);

        verify(passageRepository).updateGraphStatusBatch(eq(chunkIds), eq(IndexStatus.INDEXED), any());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // findDocumentsNeedingSync / findPassagesMissing*
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void findDocumentsNeedingSync_delegatesToRepository() {
        IndexedDocument doc = mock(IndexedDocument.class);
        when(documentRepository.findNeedingAnyIndexing(FACT_SHEET_ID)).thenReturn(List.of(doc));

        List<IndexedDocument> result = service.findDocumentsNeedingSync(FACT_SHEET_ID);

        assertThat(result).containsExactly(doc);
    }

    @Test
    void findPassagesMissingFromVector_delegatesToRepository() {
        IndexedPassage passage = mock(IndexedPassage.class);
        when(passageRepository.findInKeywordNotInVector(FACT_SHEET_ID)).thenReturn(List.of(passage));

        List<IndexedPassage> result = service.findPassagesMissingFromVector(FACT_SHEET_ID);

        assertThat(result).containsExactly(passage);
    }

    @Test
    void findPassagesMissingFromGraph_delegatesToRepository() {
        IndexedPassage passage = mock(IndexedPassage.class);
        when(passageRepository.findInVectorNotInGraph(FACT_SHEET_ID)).thenReturn(List.of(passage));

        List<IndexedPassage> result = service.findPassagesMissingFromGraph(FACT_SHEET_ID);

        assertThat(result).containsExactly(passage);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // checkVectorIndexStatus
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void checkVectorIndexStatus_initializesAllFalse_thenMarksIndexedOnes() {
        IndexedPassage indexedPassage = mock(IndexedPassage.class);
        when(indexedPassage.getChunkId()).thenReturn("c1");
        when(indexedPassage.isInVectorStore()).thenReturn(true);

        IndexedPassage notIndexedPassage = mock(IndexedPassage.class);
        when(notIndexedPassage.getChunkId()).thenReturn("c2");
        when(notIndexedPassage.isInVectorStore()).thenReturn(false);

        List<String> chunkIds = List.of("c1", "c2", "c3");
        when(passageRepository.findByChunkIds(chunkIds))
                .thenReturn(List.of(indexedPassage, notIndexedPassage));

        Map<String, Boolean> result = service.checkVectorIndexStatus(chunkIds);

        assertThat(result).containsEntry("c1", true)
                          .containsEntry("c2", false)
                          .containsEntry("c3", false);
    }

    @Test
    void checkVectorIndexStatus_allFalseWhenNoPassagesFound() {
        List<String> chunkIds = List.of("x", "y");
        when(passageRepository.findByChunkIds(chunkIds)).thenReturn(Collections.emptyList());

        Map<String, Boolean> result = service.checkVectorIndexStatus(chunkIds);

        assertThat(result).containsEntry("x", false).containsEntry("y", false);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // resolveQueryResults
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void resolveQueryResults_emptyInputReturnsEmptyResultWithNoSync() {
        CrossIndexTrackingService.CrossIndexResolutionResult result =
                service.resolveQueryResults(Collections.emptyList(), FACT_SHEET_ID);

        assertThat(result.allIndexedChunkIds()).isEmpty();
        assertThat(result.missingFromVectorChunkIds()).isEmpty();
        assertThat(result.missingFromGraphChunkIds()).isEmpty();
        assertFalse(result.needsSync());
    }

    @Test
    void resolveQueryResults_nullInputReturnsEmptyResultWithNoSync() {
        CrossIndexTrackingService.CrossIndexResolutionResult result =
                service.resolveQueryResults(null, FACT_SHEET_ID);

        assertFalse(result.needsSync());
        assertThat(result.allIndexedChunkIds()).isEmpty();
    }

    @Test
    void resolveQueryResults_setsNeedsSyncWhenPassagesMissingFromVector() {
        List<String> chunkIds = List.of("c1", "c2");
        when(passageRepository.findChunkIdsNotInVectorStore(any())).thenReturn(Set.of("c1"));
        when(passageRepository.findChunkIdsNotInGraph(any())).thenReturn(Collections.emptySet());

        CrossIndexTrackingService.CrossIndexResolutionResult result =
                service.resolveQueryResults(chunkIds, FACT_SHEET_ID);

        assertTrue(result.needsSync());
        assertThat(result.missingFromVectorChunkIds()).containsExactly("c1");
        assertThat(result.allIndexedChunkIds()).containsExactly("c2");
    }

    @Test
    void resolveQueryResults_allIndexedWhenNoMissingPassages() {
        List<String> chunkIds = List.of("c1", "c2");
        when(passageRepository.findChunkIdsNotInVectorStore(any())).thenReturn(Collections.emptySet());
        when(passageRepository.findChunkIdsNotInGraph(any())).thenReturn(Collections.emptySet());

        CrossIndexTrackingService.CrossIndexResolutionResult result =
                service.resolveQueryResults(chunkIds, FACT_SHEET_ID);

        assertFalse(result.needsSync());
        assertThat(result.allIndexedChunkIds()).containsExactlyInAnyOrder("c1", "c2");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // listDocuments / listPassages / search*
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void listDocuments_delegatesToRepositoryWithPageable() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<IndexedDocument> page = new PageImpl<>(List.of(mock(IndexedDocument.class)));
        when(documentRepository.findByFactSheetId(FACT_SHEET_ID, pageable)).thenReturn(page);

        Page<IndexedDocument> result = service.listDocuments(FACT_SHEET_ID, pageable);

        assertSame(page, result);
    }

    @Test
    void listDocumentsByStatus_delegatesToRepositoryWithStatus() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<IndexedDocument> page = new PageImpl<>(Collections.emptyList());
        when(documentRepository.findByFactSheetIdAndOverallStatus(
                FACT_SHEET_ID, OverallIndexStatus.FULLY_INDEXED, pageable)).thenReturn(page);

        Page<IndexedDocument> result = service.listDocumentsByStatus(
                FACT_SHEET_ID, OverallIndexStatus.FULLY_INDEXED, pageable);

        assertSame(page, result);
    }

    @Test
    void searchDocuments_delegatesToSearchByFileName() {
        Pageable pageable = PageRequest.of(0, 5);
        Page<IndexedDocument> page = new PageImpl<>(Collections.emptyList());
        when(documentRepository.searchByFileName(FACT_SHEET_ID, "report", pageable)).thenReturn(page);

        Page<IndexedDocument> result = service.searchDocuments(FACT_SHEET_ID, "report", pageable);

        assertSame(page, result);
    }

    @Test
    void searchDocumentsByStatus_delegatesToSearchByFileNameAndStatus() {
        Pageable pageable = PageRequest.of(0, 5);
        Page<IndexedDocument> page = new PageImpl<>(Collections.emptyList());
        when(documentRepository.searchByFileNameAndStatus(
                FACT_SHEET_ID, "doc", OverallIndexStatus.PARTIAL, pageable)).thenReturn(page);

        Page<IndexedDocument> result = service.searchDocumentsByStatus(
                FACT_SHEET_ID, "doc", OverallIndexStatus.PARTIAL, pageable);

        assertSame(page, result);
    }

    @Test
    void listPassages_delegatesToRepositoryWithDocumentIdAndPageable() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<IndexedPassage> page = new PageImpl<>(Collections.emptyList());
        when(passageRepository.findByDocumentId(DOC_ID, pageable)).thenReturn(page);

        Page<IndexedPassage> result = service.listPassages(DOC_ID, pageable);

        assertSame(page, result);
    }

    @Test
    void listPassagesOrdered_delegatesToRepositoryOrderByChunkIndex() {
        IndexedPassage p1 = mock(IndexedPassage.class);
        IndexedPassage p2 = mock(IndexedPassage.class);
        when(passageRepository.findByDocumentIdOrderByChunkIndex(DOC_ID)).thenReturn(List.of(p1, p2));

        List<IndexedPassage> result = service.listPassagesOrdered(DOC_ID);

        assertThat(result).containsExactly(p1, p2);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getStatistics
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void getStatistics_returnsPopulatedRecord() {
        when(documentRepository.countByFactSheetId(FACT_SHEET_ID)).thenReturn(10L);
        when(documentRepository.countByFactSheetIdAndOverallStatus(FACT_SHEET_ID, OverallIndexStatus.FULLY_INDEXED)).thenReturn(5L);
        when(documentRepository.countByFactSheetIdAndOverallStatus(FACT_SHEET_ID, OverallIndexStatus.PARTIAL)).thenReturn(2L);
        when(documentRepository.countByFactSheetIdAndOverallStatus(FACT_SHEET_ID, OverallIndexStatus.NOT_INDEXED)).thenReturn(2L);
        when(documentRepository.countByFactSheetIdAndOverallStatus(FACT_SHEET_ID, OverallIndexStatus.FAILED)).thenReturn(1L);
        when(passageRepository.countByFactSheetId(FACT_SHEET_ID)).thenReturn(100L);
        when(passageRepository.countByFactSheetIdAndVectorStoreStatus(FACT_SHEET_ID, IndexStatus.INDEXED)).thenReturn(80L);
        when(passageRepository.countByFactSheetIdAndKeywordIndexStatus(FACT_SHEET_ID, IndexStatus.INDEXED)).thenReturn(90L);
        when(passageRepository.countByFactSheetIdAndGraphStatus(FACT_SHEET_ID, IndexStatus.INDEXED)).thenReturn(60L);

        CrossIndexTrackingService.CrossIndexStatistics stats = service.getStatistics(FACT_SHEET_ID);

        assertEquals(10L, stats.totalDocuments());
        assertEquals(5L, stats.fullyIndexedDocuments());
        assertEquals(2L, stats.partiallyIndexedDocuments());
        assertEquals(2L, stats.notIndexedDocuments());
        assertEquals(1L, stats.failedDocuments());
        assertEquals(100L, stats.totalPassages());
        assertEquals(80L, stats.vectorIndexedPassages());
        assertEquals(90L, stats.keywordIndexedPassages());
        assertEquals(60L, stats.graphIndexedPassages());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getSummary
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void getSummary_returnsPopulatedRecord() {
        IndexedDocument docNeedingSync = mock(IndexedDocument.class);
        IndexedPassage passageMissingVector = mock(IndexedPassage.class);
        IndexedPassage passageMissingGraph = mock(IndexedPassage.class);

        when(documentRepository.findNeedingAnyIndexing(FACT_SHEET_ID)).thenReturn(List.of(docNeedingSync));
        when(passageRepository.findInKeywordNotInVector(FACT_SHEET_ID)).thenReturn(List.of(passageMissingVector));
        when(passageRepository.findInVectorNotInGraph(FACT_SHEET_ID)).thenReturn(List.of(passageMissingGraph));

        CrossIndexTrackingService.CrossIndexSummary summary = service.getSummary(FACT_SHEET_ID, "MySheet");

        assertEquals(FACT_SHEET_ID, summary.factSheetId());
        assertEquals("MySheet", summary.factSheetName());
        assertEquals(1, summary.documentsNeedingSync());
        assertEquals(1, summary.passagesMissingFromVector());
        assertEquals(1, summary.passagesMissingFromGraph());
        assertNotNull(summary.lastSyncCheck());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getStatusDistribution
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void getStatusDistribution_returnsEnumMapWithAllStatuses() {
        List<Object[]> dbRows = List.of(
                new Object[]{OverallIndexStatus.FULLY_INDEXED, 4L},
                new Object[]{OverallIndexStatus.PARTIAL, 2L}
        );
        when(documentRepository.getStatusCountsByFactSheet(FACT_SHEET_ID)).thenReturn(dbRows);

        Map<OverallIndexStatus, Long> distribution = service.getStatusDistribution(FACT_SHEET_ID);

        // All statuses initialized to 0
        assertThat(distribution).containsKeys(OverallIndexStatus.values());
        assertEquals(4L, distribution.get(OverallIndexStatus.FULLY_INDEXED));
        assertEquals(2L, distribution.get(OverallIndexStatus.PARTIAL));
        assertEquals(0L, distribution.get(OverallIndexStatus.NOT_INDEXED));
        assertEquals(0L, distribution.get(OverallIndexStatus.FAILED));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // deleteByFactSheet / deleteDocument
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void deleteByFactSheet_deletesPassagesBeforeDocuments() {
        service.deleteByFactSheet(FACT_SHEET_ID);

        // Verify ordering: passages first, then documents
        var inOrder = inOrder(passageRepository, documentRepository);
        inOrder.verify(passageRepository).deleteByFactSheetId(FACT_SHEET_ID);
        inOrder.verify(documentRepository).deleteByFactSheetId(FACT_SHEET_ID);
    }

    @Test
    void deleteDocument_deletesPassagesBeforeDocument() {
        service.deleteDocument(DOC_ID);

        var inOrder = inOrder(passageRepository, documentRepository);
        inOrder.verify(passageRepository).deleteByDocumentId(DOC_ID);
        inOrder.verify(documentRepository).deleteById(DOC_ID);
    }
}
