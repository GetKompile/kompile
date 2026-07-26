/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.sync.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class NoteSyncRunWorkerTest {

    private NoteSyncEngine engine;
    private NoteSyncConnectionService connections;
    private NoteSyncProgressTracker progressTracker;
    private NoteSyncRunWorker worker;

    @BeforeEach
    void setUp() {
        engine = mock(NoteSyncEngine.class);
        connections = mock(NoteSyncConnectionService.class);
        progressTracker = mock(NoteSyncProgressTracker.class);
        worker = new NoteSyncRunWorker(engine, connections, progressTracker);
    }

    @AfterEach
    void tearDown() {
        worker.stopLeaseHeartbeat();
    }

    @Test
    void unexpectedWorkerFailureTerminatesTheDurableRun() {
        NoteSyncRunQueuedEvent event =
                new NoteSyncRunQueuedEvent("sync-failed", 42L, false);
        doThrow(new IllegalStateException("connection removed"))
                .when(engine).syncConnection(42L, "sync-failed", false);

        worker.execute(event);

        verify(progressTracker).error("sync-failed", 42L, "connection removed");
        verify(connections).releaseRunLease(42L, "sync-failed");
    }

    @Test
    void leaseLossDoesNotOverwriteAnInterruptedRunWithError() {
        NoteSyncRunQueuedEvent event =
                new NoteSyncRunQueuedEvent("sync-stale", 42L, true);
        doThrow(new NoteSyncConnectionService.SyncLeaseLostException(42L, "sync-stale"))
                .when(engine).syncConnection(42L, "sync-stale", true);

        worker.execute(event);

        verify(progressTracker, never()).error(
                "sync-stale", 42L,
                "Source sync lease is no longer owned by run sync-stale for connection 42");
        verify(connections).releaseRunLease(42L, "sync-stale");
    }
}
