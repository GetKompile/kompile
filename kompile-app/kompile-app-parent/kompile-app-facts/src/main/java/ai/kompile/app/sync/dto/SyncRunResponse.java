/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.sync.dto;

import ai.kompile.app.sync.domain.NoteSyncRun;
import ai.kompile.app.sync.domain.SyncProvider;

import java.time.Instant;

/** Stable API representation of a durable source-maintenance run. */
public record SyncRunResponse(
        String sessionId,
        Long connectionId,
        Long factSheetId,
        SyncProvider provider,
        String mode,
        String status,
        String stage,
        String message,
        String errorMessage,
        int pushed,
        int pulled,
        int deleted,
        int conflicts,
        int skipped,
        int errors,
        String crawlJobId,
        String graphStatus,
        String graphError,
        Instant checkpointBefore,
        Instant checkpointAfter,
        Instant queuedAt,
        Instant startedAt,
        Instant finishedAt,
        Instant updatedAt) {

    public static SyncRunResponse from(NoteSyncRun run) {
        return new SyncRunResponse(
                run.getId(),
                run.getConnectionId(),
                run.getFactSheetId(),
                run.getProvider(),
                run.getMode(),
                run.getStatus(),
                run.getStage(),
                run.getMessage(),
                run.getErrorMessage(),
                run.getPushed(),
                run.getPulled(),
                run.getDeleted(),
                run.getConflicts(),
                run.getSkipped(),
                run.getErrors(),
                run.getCrawlJobId(),
                run.getGraphStatus(),
                run.getGraphError(),
                run.getCheckpointBefore(),
                run.getCheckpointAfter(),
                run.getQueuedAt(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getUpdatedAt());
    }
}
