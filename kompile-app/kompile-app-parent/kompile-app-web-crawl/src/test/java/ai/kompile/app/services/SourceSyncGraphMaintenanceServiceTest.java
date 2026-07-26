/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.services;

import ai.kompile.app.facts.domain.Note;
import ai.kompile.app.facts.repository.NoteRepository;
import ai.kompile.app.sync.domain.NoteSyncRecord;
import ai.kompile.app.sync.domain.SyncProvider;
import ai.kompile.app.sync.repository.NoteSyncRecordRepository;
import ai.kompile.app.sync.service.NoteSyncProgressTracker;
import ai.kompile.app.sync.service.NoteSyncPulledEvent;
import ai.kompile.core.crawl.graph.UnifiedCrawlSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SourceSyncGraphMaintenanceServiceTest {

    @Test
    void pulledProviderNotesStartObservableFactSheetCrawl() throws Exception {
        NoteRepository notes = mock(NoteRepository.class);
        NoteSyncRecordRepository records = mock(NoteSyncRecordRepository.class);
        NoteSyncProgressTracker progress = mock(NoteSyncProgressTracker.class);
        SingleSourceCrawlStarter crawlStarter = mock(SingleSourceCrawlStarter.class);
        when(crawlStarter.isAvailable()).thenReturn(true);
        when(notes.findByFactSheetIdOrderByCreatedAtDesc(42L)).thenReturn(List.of(
                Note.builder()
                        .id(100L)
                        .factSheetId(42L)
                        .title("Quarterly plan")
                        .content("Revenue grew by 12 percent.")
                        .syncProvider(SyncProvider.NOTION)
                        .build(),
                Note.builder()
                        .id(101L)
                        .factSheetId(42L)
                        .title("Local draft")
                        .content("Must not enter the Notion snapshot.")
                        .syncProvider(SyncProvider.LOCAL_FOLDER)
                        .build()));
        when(records.findByConnectionId(7L)).thenReturn(List.of(
                NoteSyncRecord.builder()
                        .noteId(100L)
                        .connectionId(7L)
                        .externalId("quarterly-plan")
                        .status("SYNCED")
                        .build()));
        when(crawlStarter.start(
                anyString(),
                any(UnifiedCrawlSource.class),
                any(SingleSourceCrawlStarter.SingleSourceCrawlOptions.class)))
                .thenReturn(new SingleSourceCrawlStarter.SingleSourceCrawlResult(
                        "crawl-1", "QUEUED", 42L, 1, true, true,
                        null, null, Map.of(), List.of(), null, null,
                        Map.of(), Map.of(), null, null, null, List.of(), List.of(), null));

        SourceSyncGraphMaintenanceService service =
                new SourceSyncGraphMaintenanceService(notes, records, progress, crawlStarter);
        service.updateGraph(new NoteSyncPulledEvent(
                "sync-1", 7L, 42L, SyncProvider.NOTION, 1, 0));

        ArgumentCaptor<UnifiedCrawlSource> source =
                ArgumentCaptor.forClass(UnifiedCrawlSource.class);
        ArgumentCaptor<SingleSourceCrawlStarter.SingleSourceCrawlOptions> options =
                ArgumentCaptor.forClass(SingleSourceCrawlStarter.SingleSourceCrawlOptions.class);
        verify(crawlStarter).start(
                org.mockito.ArgumentMatchers.contains("NOTION"),
                source.capture(),
                options.capture());

        assertEquals("markdown", source.getValue().getLoaderName());
        assertEquals(42L, options.getValue().factSheetId());
        Path snapshot = Path.of(source.getValue().getPathOrUrl());
        String markdown = Files.readString(snapshot);
        assertTrue(markdown.contains("Quarterly plan"));
        assertTrue(markdown.contains("Revenue grew by 12 percent."));
        assertTrue(!markdown.contains("Local draft"));
        verify(progress).graphQueued("sync-1", "crawl-1");

        Files.deleteIfExists(snapshot);
        Files.deleteIfExists(snapshot.getParent());
    }

    @Test
    void unchangedPullDoesNotStartGraphMaintenance() {
        NoteRepository notes = mock(NoteRepository.class);
        NoteSyncRecordRepository records = mock(NoteSyncRecordRepository.class);
        NoteSyncProgressTracker progress = mock(NoteSyncProgressTracker.class);
        SingleSourceCrawlStarter crawlStarter = mock(SingleSourceCrawlStarter.class);
        when(crawlStarter.isAvailable()).thenReturn(true);

        SourceSyncGraphMaintenanceService service =
                new SourceSyncGraphMaintenanceService(notes, records, progress, crawlStarter);
        service.updateGraph(new NoteSyncPulledEvent(
                "sync-2", 8L, 43L, SyncProvider.OBSIDIAN, 0, 0));

        verify(crawlStarter, never()).start(
                anyString(),
                any(UnifiedCrawlSource.class),
                any(SingleSourceCrawlStarter.SingleSourceCrawlOptions.class));
    }

    @Test
    void deletionOnlyRunQueuesAnEmptySnapshotToRemoveStaleGraphContent() throws Exception {
        NoteRepository notes = mock(NoteRepository.class);
        NoteSyncRecordRepository records = mock(NoteSyncRecordRepository.class);
        NoteSyncProgressTracker progress = mock(NoteSyncProgressTracker.class);
        SingleSourceCrawlStarter crawlStarter = mock(SingleSourceCrawlStarter.class);
        when(crawlStarter.isAvailable()).thenReturn(true);
        when(notes.findByFactSheetIdOrderByCreatedAtDesc(44L)).thenReturn(List.of());
        when(records.findByConnectionId(9L)).thenReturn(List.of(
                NoteSyncRecord.builder()
                        .noteId(200L)
                        .connectionId(9L)
                        .externalId("removed.md")
                        .status("EXTERNAL_DELETED")
                        .build()));
        when(crawlStarter.start(
                anyString(),
                any(UnifiedCrawlSource.class),
                any(SingleSourceCrawlStarter.SingleSourceCrawlOptions.class)))
                .thenReturn(new SingleSourceCrawlStarter.SingleSourceCrawlResult(
                        "crawl-delete", "QUEUED", 44L, 1, true, true,
                        null, null, Map.of(), List.of(), null, null,
                        Map.of(), Map.of(), null, null, null, List.of(), List.of(), null));

        SourceSyncGraphMaintenanceService service =
                new SourceSyncGraphMaintenanceService(notes, records, progress, crawlStarter);
        service.updateGraph(new NoteSyncPulledEvent(
                "sync-delete", 9L, 44L, SyncProvider.OBSIDIAN, 0, 1));

        ArgumentCaptor<UnifiedCrawlSource> source =
                ArgumentCaptor.forClass(UnifiedCrawlSource.class);
        verify(crawlStarter).start(
                anyString(),
                source.capture(),
                any(SingleSourceCrawlStarter.SingleSourceCrawlOptions.class));
        Path snapshot = Path.of(source.getValue().getPathOrUrl());
        assertEquals("# Synced source notes\n\n", Files.readString(snapshot));
        verify(progress).graphQueued("sync-delete", "crawl-delete");

        Files.deleteIfExists(snapshot);
        Files.deleteIfExists(snapshot.getParent());
    }
}
