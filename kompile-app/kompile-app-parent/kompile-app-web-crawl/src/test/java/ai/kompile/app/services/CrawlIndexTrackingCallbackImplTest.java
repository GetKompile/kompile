/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.app.services;

import ai.kompile.app.ingest.domain.IndexedDocument;
import ai.kompile.app.ingest.domain.IndexedPassage;
import ai.kompile.crawl.graph.CrawlIndexTrackingCallback.CrawlCorpusPassage;
import ai.kompile.crawl.graph.CrawlIndexTrackingCallback.CrawlCorpusSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CrawlIndexTrackingCallbackImplTest {

    private static final long FACT_SHEET = 42L;

    @Mock
    private CrossIndexTrackingService tracking;

    private CrawlIndexTrackingCallbackImpl callback;

    @BeforeEach
    void setUp() {
        callback = new CrawlIndexTrackingCallbackImpl(tracking);
    }

    @Test
    void pooledSnapshotIsStableAndNeverLabelsALegacyPreviewAsExactSourceText() {
        IndexedDocument sourceA = IndexedDocument.builder()
                .sourceId("source-a")
                .factSheetId(FACT_SHEET)
                .build();
        IndexedDocument sourceB = IndexedDocument.builder()
                .sourceId("source-b")
                .factSheetId(FACT_SHEET)
                .build();
        String exactLongText = "Exact retained source text ".repeat(40);
        IndexedPassage exact = IndexedPassage.builder()
                .document(sourceB)
                .factSheetId(FACT_SHEET)
                .chunkId("chunk-exact")
                .chunkIndex(2)
                .contentHash("hash-exact")
                .contentPreview(exactLongText.substring(0, 500))
                .fullContent(exactLongText)
                .contentType("text")
                .build();
        IndexedPassage legacyPreview = IndexedPassage.builder()
                .document(sourceA)
                .factSheetId(FACT_SHEET)
                .chunkId("chunk-preview")
                .chunkIndex(4)
                .contentHash("hash-of-the-original-longer-content")
                .contentPreview("Only the first 500 characters survived")
                .fullContent(null)
                .contentType("text")
                .build();
        when(tracking.findPassagesByFactSheetId(FACT_SHEET))
                .thenReturn(List.of(exact, legacyPreview), List.of(legacyPreview, exact));

        CrawlCorpusSnapshot first = callback.loadCorpusSnapshot(FACT_SHEET).orElseThrow();
        CrawlCorpusSnapshot reordered = callback.loadCorpusSnapshot(FACT_SHEET).orElseThrow();

        assertEquals(first.snapshotId(), reordered.snapshotId(),
                "repository iteration order must not redefine the corpus revision");
        assertTrue(first.snapshotId().startsWith("sha256:"));
        assertEquals(List.of("chunk-preview", "chunk-exact"), first.passages().stream()
                .map(CrawlCorpusPassage::chunkId).toList());

        CrawlCorpusPassage preview = first.passages().get(0);
        assertFalse(preview.completeText(),
                "a hash that does not match the preview proves the source was truncated");
        CrawlCorpusPassage retained = first.passages().get(1);
        assertTrue(retained.completeText());
        assertEquals(exactLongText, retained.content());
        assertEquals("source-b", retained.metadata().get("source_document_id"));
    }
}
