/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.sync.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Durable lifecycle record for a source-maintenance run.
 *
 * <p>The run identifier is created before dispatch and is reused by the HTTP response,
 * WebSocket progress, provider work, and graph-maintenance crawl linkage.</p>
 */
@Entity
@Table(name = "note_sync_runs", indexes = {
        @Index(name = "idx_nsr_run_connection", columnList = "connectionId"),
        @Index(name = "idx_nsr_run_status", columnList = "status"),
        @Index(name = "idx_nsr_run_queued", columnList = "queuedAt")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NoteSyncRun {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false)
    private Long connectionId;

    @Column(nullable = false)
    private Long factSheetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SyncProvider provider;

    /** FULL or PULL. */
    @Column(nullable = false, length = 16)
    private String mode;

    /** QUEUED, RUNNING, COMPLETED, PARTIAL, ERROR, or INTERRUPTED. */
    @Column(nullable = false, length = 32)
    private String status;

    @Column(nullable = false, length = 48)
    private String stage;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Builder.Default
    private int pushed = 0;

    @Builder.Default
    private int pulled = 0;

    @Builder.Default
    private int deleted = 0;

    @Builder.Default
    private int conflicts = 0;

    @Builder.Default
    private int skipped = 0;

    @Builder.Default
    private int errors = 0;

    @Column(length = 128)
    private String crawlJobId;

    /** NOT_REQUIRED, PENDING, QUEUED, or ERROR. */
    @Column(length = 32)
    private String graphStatus;

    @Column(columnDefinition = "TEXT")
    private String graphError;

    private Instant checkpointBefore;
    private Instant checkpointAfter;

    @Column(nullable = false)
    private Instant queuedAt;

    private Instant startedAt;
    private Instant finishedAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (queuedAt == null) queuedAt = now;
        if (updatedAt == null) updatedAt = now;
        if (status == null) status = "QUEUED";
        if (stage == null) stage = "QUEUED";
        if (graphStatus == null) graphStatus = "NOT_REQUIRED";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
