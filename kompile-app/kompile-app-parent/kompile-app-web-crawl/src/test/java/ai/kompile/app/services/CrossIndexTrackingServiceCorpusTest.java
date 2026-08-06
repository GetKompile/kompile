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
import ai.kompile.app.ingest.repository.IndexedDocumentRepository;
import ai.kompile.app.ingest.repository.IndexedPassageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CrossIndexTrackingServiceCorpusTest {

    @Mock
    private IndexedDocumentRepository documents;
    @Mock
    private IndexedPassageRepository passages;

    private CrossIndexTrackingService tracking;
    private IndexedDocument document;

    @BeforeEach
    void setUp() {
        tracking = new CrossIndexTrackingService(documents, passages);
        document = IndexedDocument.builder()
                .sourceId("source-a")
                .factSheetId(42L)
                .build();
        when(passages.save(any(IndexedPassage.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void ordinaryPassageRegistrationRetainsExactTextAlongsideTheBrowserPreview() {
        String exact = "A long source passage with model evidence. ".repeat(30);
        when(passages.findByChunkId("chunk-new")).thenReturn(Optional.empty());

        IndexedPassage registered = tracking.registerPassage(
                document, "chunk-new", 3, exact, Map.of("content_type", "text"));

        assertEquals(exact, registered.getFullContent());
        assertEquals(exact.substring(0, 500), registered.getContentPreview());
        assertNotNull(registered.getContentHash());
        assertEquals(64, registered.getContentHash().length());
    }

    @Test
    void registeringAnExistingLegacyRowBackfillsItsMissingExactText() {
        String exact = "Recovered exact passage text. ".repeat(30);
        IndexedPassage legacy = IndexedPassage.builder()
                .document(document)
                .factSheetId(42L)
                .chunkId("chunk-legacy")
                .chunkIndex(1)
                .contentHash("old-hash")
                .contentPreview("old preview")
                .fullContent(null)
                .build();
        when(passages.findByChunkId("chunk-legacy")).thenReturn(Optional.of(legacy));

        IndexedPassage registered = tracking.registerPassage(
                document, "chunk-legacy", 1, exact, Map.of("content_type", "text"));

        assertSame(legacy, registered);
        assertEquals(exact, registered.getFullContent());
        assertEquals(exact.substring(0, 500), registered.getContentPreview());
        verify(passages).save(legacy);
    }

    @Test
    void aMetadataOnlyUpdateCannotErasePreviouslyKnownHashOrPreview() {
        IndexedPassage existing = IndexedPassage.builder()
                .document(document)
                .factSheetId(42L)
                .chunkId("chunk-known")
                .chunkIndex(2)
                .contentHash("known-hash")
                .contentPreview("known preview")
                .contentType("text")
                .build();
        when(passages.findByChunkId("chunk-known")).thenReturn(Optional.of(existing));

        tracking.registerPassage(
                document, "chunk-known", 2, null, Map.of("content_type", "code"));

        assertEquals("known-hash", existing.getContentHash());
        assertEquals("known preview", existing.getContentPreview());
        assertEquals("code", existing.getContentType());
        verify(passages).save(existing);
    }
}
