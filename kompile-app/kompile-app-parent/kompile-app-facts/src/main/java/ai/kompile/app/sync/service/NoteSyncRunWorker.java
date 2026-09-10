/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.sync.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Executes a committed source-sync run outside the request and scheduling transactions. */
@Service
@ConditionalOnExpression("'${spring.application.name:}' == 'kompile-app-crawl-manager'")
public class NoteSyncRunWorker {

    private static final Logger log = LoggerFactory.getLogger(NoteSyncRunWorker.class);

    private final NoteSyncEngine engine;
    private final NoteSyncConnectionService connections;
    private final NoteSyncProgressTracker progressTracker;
    private final ScheduledExecutorService leaseHeartbeat =
            Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "note-sync-lease-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    public NoteSyncRunWorker(
            NoteSyncEngine engine,
            NoteSyncConnectionService connections,
            NoteSyncProgressTracker progressTracker) {
        this.engine = engine;
        this.connections = connections;
        this.progressTracker = progressTracker;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void execute(NoteSyncRunQueuedEvent event) {
        ScheduledFuture<?> heartbeat = leaseHeartbeat.scheduleAtFixedRate(
                () -> renewLease(event), 1, 1, TimeUnit.MINUTES);
        try {
            engine.syncConnection(event.connectionId(), event.sessionId(), event.pullOnly());
        } catch (NoteSyncConnectionService.SyncLeaseLostException leaseLost) {
            log.warn("Source sync run {} stopped after losing its lease: {}",
                    event.sessionId(), leaseLost.getMessage());
        } catch (RuntimeException failure) {
            log.error("Source sync run {} failed: {}", event.sessionId(), failure.getMessage(), failure);
            String message = failure.getMessage() == null
                    ? failure.getClass().getSimpleName()
                    : failure.getMessage();
            try {
                progressTracker.error(event.sessionId(), event.connectionId(), message);
            } catch (RuntimeException trackingFailure) {
                log.warn("Could not persist failure for source sync run {}: {}",
                        event.sessionId(), trackingFailure.getMessage());
            }
        } finally {
            heartbeat.cancel(false);
            connections.releaseRunLease(event.connectionId(), event.sessionId());
        }
    }

    private void renewLease(NoteSyncRunQueuedEvent event) {
        try {
            if (!connections.renewRunLease(event.connectionId(), event.sessionId())) {
                log.warn("Could not renew source sync lease for run {}", event.sessionId());
            }
        } catch (RuntimeException failure) {
            log.warn("Source sync lease heartbeat failed for run {}: {}",
                    event.sessionId(), failure.getMessage());
        }
    }

    @PreDestroy
    void stopLeaseHeartbeat() {
        leaseHeartbeat.shutdownNow();
    }
}
