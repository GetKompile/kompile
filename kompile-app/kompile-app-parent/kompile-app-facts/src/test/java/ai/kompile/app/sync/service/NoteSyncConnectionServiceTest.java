/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.sync.service;

import ai.kompile.app.sync.domain.NoteSyncConnection;
import ai.kompile.app.sync.domain.NoteSyncRun;
import ai.kompile.app.sync.domain.SyncAuthMode;
import ai.kompile.app.sync.domain.SyncProvider;
import ai.kompile.app.sync.dto.SyncConnectionRequest;
import ai.kompile.app.sync.dto.SyncRunResponse;
import ai.kompile.app.sync.repository.NoteSyncConnectionRepository;
import ai.kompile.app.sync.repository.NoteSyncRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NoteSyncConnectionServiceTest {

    private NoteSyncConnectionService service;
    private NoteSyncConnectionRepository repository;
    private NoteSyncRunRepository runRepository;
    private ApplicationEventPublisher eventPublisher;
    private NoteSyncConnection connection;

    @BeforeEach
    void setUp() {
        repository = mock(NoteSyncConnectionRepository.class);
        runRepository = mock(NoteSyncRunRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new NoteSyncConnectionService();
        setField(service, "connectionRepository", repository);
        setField(service, "syncRunRepository", runRepository);
        setField(service, "eventPublisher", eventPublisher);
        connection = NoteSyncConnection.builder()
                .id(42L)
                .factSheetId(7L)
                .provider(SyncProvider.OBSIDIAN)
                .externalScope("/vault")
                .authMode(SyncAuthMode.OBSIDIAN_REST_TOKEN)
                .authStatus("CONFIGURED")
                .enabled(false)
                .build();
        when(repository.findById(42L)).thenReturn(Optional.of(connection));
        when(repository.save(any(NoteSyncConnection.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(runRepository.save(any(NoteSyncRun.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void credentialedSourceCannotBeEnabledUntilAuthTestPasses() {
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> service.enableConnection(42L));

        assertTrue(failure.getMessage().contains("Test Auth"));
        assertFalse(connection.getEnabled());
        verify(repository, never()).save(any());
    }

    @Test
    void validAuthenticationAllowsEnableAndAutoSync() {
        connection.setAuthStatus("VALID");

        assertTrue(service.enableConnection(42L).getEnabled());
        assertEquals("0 */15 * * * *",
                service.updateAutoSync(42L, true, "0 */15 * * * *").getPollCron());
    }

    @Test
    void runValidationRequiresBothEnabledAndValidAuthentication() {
        connection.setAuthStatus("VALID");
        assertThrows(IllegalArgumentException.class, () -> service.validateCanRun(42L));

        connection.setEnabled(true);
        assertDoesNotThrow(() -> service.validateCanRun(42L));

        connection.setAuthStatus("INVALID");
        assertThrows(IllegalArgumentException.class, () -> service.validateCanRun(42L));
    }

    @Test
    void localCredentialFreeSourceDoesNotRequireAuthTest() {
        connection.setProvider(SyncProvider.LOCAL_FOLDER);
        connection.setAuthMode(SyncAuthMode.NONE);
        connection.setAuthStatus("NOT_REQUIRED");

        assertTrue(service.enableConnection(42L).getEnabled());
    }

    @Test
    void acceptedPullUsesOneDurableSessionIdForLeaseRunAndWorkerEvent() {
        connection.setProvider(SyncProvider.LOCAL_FOLDER);
        connection.setAuthMode(SyncAuthMode.NONE);
        connection.setAuthStatus("NOT_REQUIRED");
        connection.setEnabled(true);
        when(repository.acquireRunLease(
                eq(42L), anyString(), any(Instant.class), any(Instant.class))).thenReturn(1);

        SyncRunResponse response = service.triggerPull(42L);

        ArgumentCaptor<String> leaseRunId = ArgumentCaptor.forClass(String.class);
        verify(repository).acquireRunLease(
                eq(42L), leaseRunId.capture(), any(Instant.class), any(Instant.class));
        ArgumentCaptor<NoteSyncRun> persisted = ArgumentCaptor.forClass(NoteSyncRun.class);
        verify(runRepository).save(persisted.capture());
        ArgumentCaptor<NoteSyncRunQueuedEvent> queued =
                ArgumentCaptor.forClass(NoteSyncRunQueuedEvent.class);
        verify(eventPublisher).publishEvent(queued.capture());

        assertEquals(leaseRunId.getValue(), response.sessionId());
        assertEquals(response.sessionId(), persisted.getValue().getId());
        assertEquals(response.sessionId(), queued.getValue().sessionId());
        assertEquals("PULL", response.mode());
        assertEquals("QUEUED", response.status());
        assertTrue(response.sessionId().startsWith("pull-"));
    }

    @Test
    void overlappingRunIsRejectedWithTheActiveDurableRunId() {
        connection.setProvider(SyncProvider.LOCAL_FOLDER);
        connection.setAuthMode(SyncAuthMode.NONE);
        connection.setAuthStatus("NOT_REQUIRED");
        connection.setEnabled(true);
        connection.setActiveSyncRunId("sync-active");
        connection.setSyncLeaseExpiresAt(Instant.now().plusSeconds(120));
        when(repository.acquireRunLease(
                eq(42L), anyString(), any(Instant.class), any(Instant.class))).thenReturn(0);

        NoteSyncConnectionService.SyncAlreadyRunningException failure = assertThrows(
                NoteSyncConnectionService.SyncAlreadyRunningException.class,
                () -> service.triggerSync(42L));

        assertEquals("sync-active", failure.getActiveRunId());
        verify(runRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void staleWorkerIsFencedWhenItNoLongerOwnsTheLease() {
        when(repository.renewRunLease(
                eq(42L), eq("sync-stale"), any(Instant.class), any(Instant.class)))
                .thenReturn(0);

        assertThrows(NoteSyncConnectionService.SyncLeaseLostException.class,
                () -> service.renewRunLeaseOrThrow(42L, "sync-stale"));
    }

    @Test
    void repositoryCredentialsAreRemovedBeforePersistenceAndResponse() {
        SyncConnectionRequest request = SyncConnectionRequest.builder()
                .factSheetId(7L)
                .provider(SyncProvider.GIT_REPOSITORY)
                .externalScope("/workspace/repository")
                .repositoryUrl("https://user:token@github.example/org/repo.git?access_token=secret#ref")
                .remoteSyncEnabled(true)
                .build();

        var response = service.createConnection(request);

        ArgumentCaptor<NoteSyncConnection> saved = ArgumentCaptor.forClass(NoteSyncConnection.class);
        verify(repository).save(saved.capture());
        assertFalse(saved.getValue().getEnabled());
        assertEquals("https://github.example/org/repo.git", saved.getValue().getRepositoryUrl());
        assertEquals("https://github.example/org/repo.git", response.getRepositoryUrl());
    }

    @Test
    void refusesToStorePlaintextTokenWhenEncryptionIsUnavailable() {
        SyncConnectionRequest request = SyncConnectionRequest.builder()
                .factSheetId(7L)
                .provider(SyncProvider.OBSIDIAN)
                .externalScope("/vault")
                .obsidianApiUrl("https://localhost:27124")
                .obsidianToken("never-store-plaintext")
                .authMode(SyncAuthMode.OBSIDIAN_REST_TOKEN)
                .build();

        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> service.createConnection(request));

        assertTrue(failure.getMessage().contains("encryption is unavailable"));
        verify(repository, never()).save(any());
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
