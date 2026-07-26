/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.sync.service;

import ai.kompile.app.facts.repository.NoteRepository;
import ai.kompile.app.facts.service.NoteService;
import ai.kompile.app.sync.adapter.SyncAdapter;
import ai.kompile.app.sync.domain.NoteSyncConnection;
import ai.kompile.app.sync.domain.NoteSyncRecord;
import ai.kompile.app.sync.domain.SyncDirection;
import ai.kompile.app.sync.domain.SyncProvider;
import ai.kompile.app.sync.dto.SyncRunResult;
import ai.kompile.app.sync.repository.NoteSyncConnectionRepository;
import ai.kompile.app.sync.repository.NoteSyncRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NoteSyncEngineTest {

    private NoteSyncEngine engine;
    private NoteSyncConnectionRepository connectionRepository;
    private NoteSyncRecordRepository recordRepository;
    private NoteRepository noteRepository;
    private NoteSyncProgressTracker progressTracker;
    private ApplicationEventPublisher eventPublisher;
    private SyncAdapter adapter;
    private NoteSyncConnection connection;

    @BeforeEach
    void setUp() {
        connectionRepository = mock(NoteSyncConnectionRepository.class);
        recordRepository = mock(NoteSyncRecordRepository.class);
        noteRepository = mock(NoteRepository.class);
        progressTracker = mock(NoteSyncProgressTracker.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        adapter = mock(SyncAdapter.class);

        engine = new NoteSyncEngine();
        setField(engine, "connectionRepository", connectionRepository);
        setField(engine, "syncRecordRepository", recordRepository);
        setField(engine, "noteRepository", noteRepository);
        setField(engine, "noteService", mock(NoteService.class));
        setField(engine, "progressTracker", progressTracker);
        setField(engine, "eventPublisher", eventPublisher);
        setField(engine, "adapters", List.of(adapter));

        connection = NoteSyncConnection.builder()
                .id(42L)
                .factSheetId(7L)
                .provider(SyncProvider.LOCAL_FOLDER)
                .externalScope("/vault")
                .direction(SyncDirection.EXTERNAL_TO_KOMPILE)
                .enabled(true)
                .lastSyncStatus("OK")
                .build();
        when(connectionRepository.findById(42L)).thenReturn(Optional.of(connection));
        when(connectionRepository.save(any(NoteSyncConnection.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(adapter.adapterId()).thenReturn(SyncProvider.LOCAL_FOLDER.name());
    }

    @Test
    void partialPullFailureRetainsCheckpointAndSchedulesRetry() {
        Instant previousCheckpoint = Instant.parse("2025-01-02T03:04:05Z");
        connection.setLastSyncAt(previousCheckpoint);
        when(adapter.fetchChangedSince(connection, previousCheckpoint))
                .thenThrow(new IllegalStateException("provider unavailable"));

        SyncRunResult result = engine.syncConnection(42L, "sync-failure", false);

        assertEquals(1, result.getErrors());
        assertEquals(previousCheckpoint, connection.getLastSyncAt());
        assertEquals("ERROR", connection.getLastSyncStatus());
        assertEquals(1, connection.getConsecutiveSyncFailures());
        assertNotNull(connection.getNextSyncAttemptAt());
        verify(progressTracker).complete("sync-failure", result, previousCheckpoint);
    }

    @Test
    void bidirectionalRunNeverPushesAfterAnUnsafePullFailure() {
        Instant previousCheckpoint = Instant.parse("2025-01-02T03:04:05Z");
        connection.setDirection(SyncDirection.BIDIRECTIONAL);
        connection.setLastSyncAt(previousCheckpoint);
        when(adapter.fetchChangedSince(connection, previousCheckpoint))
                .thenThrow(new IllegalStateException("source mount unavailable"));

        SyncRunResult result = engine.syncConnection(42L, "sync-safe-order", false);

        assertEquals(1, result.getErrors());
        verify(noteRepository, never())
                .findByFactSheetIdAndUpdatedAtAfter(7L, previousCheckpoint);
        verify(progressTracker).progress(
                "sync-safe-order", 42L, "PUSH_SKIPPED",
                "Push skipped because the source pull did not complete safely");
    }

    @Test
    void staleWorkerAbortsWithoutCommittingAConnectionOutcome() {
        Instant previousCheckpoint = Instant.parse("2025-01-02T03:04:05Z");
        connection.setLastSyncAt(previousCheckpoint);
        doThrow(new NoteSyncConnectionService.SyncLeaseLostException(42L, "sync-stale"))
                .when(progressTracker)
                .progress("sync-stale", 42L, "PULL", "Checking the source for updates");

        assertThrows(NoteSyncConnectionService.SyncLeaseLostException.class,
                () -> engine.syncConnection(42L, "sync-stale", false));

        assertEquals(previousCheckpoint, connection.getLastSyncAt());
        verify(connectionRepository, never()).save(any(NoteSyncConnection.class));
    }

    @Test
    void successfulEnumerationAdvancesCheckpoint() {
        Instant previousCheckpoint = Instant.parse("2025-01-02T03:04:05Z");
        connection.setLastSyncAt(previousCheckpoint);
        when(adapter.fetchChangedSince(connection, previousCheckpoint)).thenReturn(List.of());
        when(adapter.listExternalIds(connection)).thenReturn(Optional.of(Set.of()));
        when(recordRepository.findByConnectionId(42L)).thenReturn(List.of());

        SyncRunResult result = engine.syncConnection(42L, "sync-success", false);

        assertEquals(0, result.getErrors());
        assertTrue(connection.getLastSyncAt().isAfter(previousCheckpoint));
        assertEquals("OK", connection.getLastSyncStatus());
        verify(progressTracker).complete(eq("sync-success"), same(result), eq(connection.getLastSyncAt()));
    }

    @Test
    void firstMissingSnapshotIsProvisionalAndDoesNotQueueGraphMaintenance() {
        Instant previousCheckpoint = Instant.parse("2025-01-02T03:04:05Z");
        connection.setLastSyncAt(previousCheckpoint);
        NoteSyncRecord missing = NoteSyncRecord.builder()
                .id(9L)
                .noteId(100L)
                .connectionId(42L)
                .externalId("missing-once.md")
                .status("SYNCED")
                .build();
        when(adapter.fetchChangedSince(connection, previousCheckpoint)).thenReturn(List.of());
        when(adapter.listExternalIds(connection)).thenReturn(Optional.of(Set.of("present.md")));
        when(recordRepository.findByConnectionId(42L)).thenReturn(List.of(missing));

        SyncRunResult result = engine.syncConnection(42L, "sync-missing-once", false);

        assertEquals(0, result.getDeleted());
        assertEquals("EXTERNAL_MISSING", missing.getStatus());
        assertTrue(missing.getErrorMessage().contains("confirmed"));
        verify(recordRepository).save(missing);
        verify(progressTracker, never()).graphPending("sync-missing-once");
        verify(eventPublisher, never()).publishEvent(any(NoteSyncPulledEvent.class));
    }

    @Test
    void secondMissingSnapshotCreatesTombstoneAndQueuesGraphMaintenance() {
        Instant previousCheckpoint = Instant.parse("2025-01-02T03:04:05Z");
        connection.setLastSyncAt(previousCheckpoint);
        NoteSyncRecord missing = NoteSyncRecord.builder()
                .id(9L)
                .noteId(100L)
                .connectionId(42L)
                .externalId("deleted.md")
                .status("EXTERNAL_MISSING")
                .build();
        when(adapter.fetchChangedSince(connection, previousCheckpoint)).thenReturn(List.of());
        when(adapter.listExternalIds(connection)).thenReturn(Optional.of(Set.of("present.md")));
        when(recordRepository.findByConnectionId(42L)).thenReturn(List.of(missing));

        SyncRunResult result = engine.syncConnection(42L, "sync-delete", false);

        assertEquals(1, result.getDeleted());
        assertEquals("EXTERNAL_DELETED", missing.getStatus());
        assertTrue(missing.getErrorMessage().contains("retained"));
        verify(recordRepository).save(missing);
        verify(progressTracker).graphPending("sync-delete");

        ArgumentCaptor<NoteSyncPulledEvent> event =
                ArgumentCaptor.forClass(NoteSyncPulledEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertEquals("sync-delete", event.getValue().sessionId());
        assertEquals(1, event.getValue().deletedCount());
        assertEquals(0, event.getValue().pulledCount());
    }

    @Test
    void reappearingExternalItemClearsProvisionalMissingState() {
        Instant previousCheckpoint = Instant.parse("2025-01-02T03:04:05Z");
        connection.setLastSyncAt(previousCheckpoint);
        NoteSyncRecord reappeared = NoteSyncRecord.builder()
                .id(9L)
                .noteId(100L)
                .connectionId(42L)
                .externalId("reappeared.md")
                .status("EXTERNAL_MISSING")
                .errorMessage("provisional absence")
                .build();
        when(adapter.fetchChangedSince(connection, previousCheckpoint)).thenReturn(List.of());
        when(adapter.listExternalIds(connection)).thenReturn(Optional.of(Set.of("reappeared.md")));
        when(recordRepository.findByConnectionId(42L)).thenReturn(List.of(reappeared));

        SyncRunResult result = engine.syncConnection(42L, "sync-reappeared", false);

        assertEquals(0, result.getDeleted());
        assertEquals("SYNCED", reappeared.getStatus());
        assertEquals(null, reappeared.getErrorMessage());
        verify(recordRepository).save(reappeared);
        verify(progressTracker, never()).graphPending("sync-reappeared");
        verify(eventPublisher, never()).publishEvent(any(NoteSyncPulledEvent.class));
    }

    @Test
    void unresolvedConflictIsNotReclassifiedByDeletionReconciliation() {
        Instant previousCheckpoint = Instant.parse("2025-01-02T03:04:05Z");
        connection.setLastSyncAt(previousCheckpoint);
        NoteSyncRecord conflict = NoteSyncRecord.builder()
                .id(9L)
                .noteId(100L)
                .connectionId(42L)
                .externalId("conflict.md")
                .status("CONFLICT")
                .errorMessage("manual resolution required")
                .build();
        when(adapter.fetchChangedSince(connection, previousCheckpoint)).thenReturn(List.of());
        when(adapter.listExternalIds(connection)).thenReturn(Optional.of(Set.of()));
        when(recordRepository.findByConnectionId(42L)).thenReturn(List.of(conflict));

        SyncRunResult result = engine.syncConnection(42L, "sync-conflict", false);

        assertEquals(0, result.getDeleted());
        assertEquals("CONFLICT", conflict.getStatus());
        assertEquals("manual resolution required", conflict.getErrorMessage());
        verify(recordRepository, never()).save(conflict);
        verify(progressTracker, never()).graphPending("sync-conflict");
        verify(eventPublisher, never()).publishEvent(any(NoteSyncPulledEvent.class));
    }

    @Test
    void checksumIsStableAcrossPlatformLineEndingsAndUnicodeForms() {
        String unix = NoteSyncEngine.noteChecksum(
                "Café", "beta,alpha", "first line\nsecond line");
        String windows = NoteSyncEngine.noteChecksum(
                "Cafe\u0301", "alpha,beta", "first line\r\nsecond line");

        assertEquals(unix, windows);
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
