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

import ai.kompile.app.config.DocumentFreshnessProperties;
import ai.kompile.app.ingest.domain.IndexedDocument;
import ai.kompile.app.ingest.repository.IndexedDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentFreshnessServiceTest {

    @Mock
    private IndexedDocumentRepository documentRepository;

    private DocumentFreshnessProperties properties;
    private DocumentFreshnessService service;

    @BeforeEach
    void setUp() {
        properties = new DocumentFreshnessProperties();
        properties.setEnabled(true);
        properties.setFreshnessHalfLifeDays(30);
        properties.setDefaultTtlDays(90);
        service = new DocumentFreshnessService(documentRepository, properties);
    }

    @Test
    void testFreshnessScoreRecentDocument() {
        IndexedDocument doc = IndexedDocument.builder()
                .keywordIndexedAt(Instant.now().minus(1, ChronoUnit.DAYS))
                .build();

        double score = service.calculateFreshnessScore(doc);
        assertTrue(score > 0.95, "Recently indexed document should have high freshness score");
    }

    @Test
    void testFreshnessScoreOldDocument() {
        IndexedDocument doc = IndexedDocument.builder()
                .keywordIndexedAt(Instant.now().minus(60, ChronoUnit.DAYS))
                .build();

        double score = service.calculateFreshnessScore(doc);
        assertTrue(score < 0.3, "60-day old document should have low freshness score");
    }

    @Test
    void testFreshnessScoreExactHalfLife() {
        IndexedDocument doc = IndexedDocument.builder()
                .keywordIndexedAt(Instant.now().minus(30, ChronoUnit.DAYS))
                .build();

        double score = service.calculateFreshnessScore(doc);
        assertEquals(0.5, score, 0.05, "Document at half-life age should have ~0.5 freshness");
    }

    @Test
    void testFreshnessScoreNullTimestamp() {
        IndexedDocument doc = IndexedDocument.builder().build();

        double score = service.calculateFreshnessScore(doc);
        assertEquals(0.5, score, "Unknown age should return neutral score");
    }

    @Test
    void testMarkStaleByTtl() {
        IndexedDocument oldDoc = IndexedDocument.builder()
                .id(1L)
                .fileName("report.pdf")
                .keywordIndexedAt(Instant.now().minus(100, ChronoUnit.DAYS))
                .keywordIndexStatus(IndexedDocument.IndexStatus.INDEXED)
                .vectorStoreStatus(IndexedDocument.IndexStatus.INDEXED)
                .graphStatus(IndexedDocument.IndexStatus.NOT_INDEXED)
                .overallStatus(IndexedDocument.OverallIndexStatus.FULLY_INDEXED)
                .createdAt(Instant.now().minus(100, ChronoUnit.DAYS))
                .build();

        IndexedDocument recentDoc = IndexedDocument.builder()
                .id(2L)
                .fileName("notes.txt")
                .keywordIndexedAt(Instant.now().minus(10, ChronoUnit.DAYS))
                .keywordIndexStatus(IndexedDocument.IndexStatus.INDEXED)
                .vectorStoreStatus(IndexedDocument.IndexStatus.INDEXED)
                .graphStatus(IndexedDocument.IndexStatus.NOT_INDEXED)
                .overallStatus(IndexedDocument.OverallIndexStatus.FULLY_INDEXED)
                .createdAt(Instant.now().minus(10, ChronoUnit.DAYS))
                .build();

        when(documentRepository.findByFactSheetId(1L)).thenReturn(List.of(oldDoc, recentDoc));

        int result = service.markStaleByTtl(1L);

        assertEquals(1, result, "Only the old document should be marked stale");
        verify(documentRepository, times(1)).save(any());
    }
}
