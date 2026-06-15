/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.builder.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Represents a knowledge graph extraction job.
 * Tracks the progress and results of building a knowledge graph from document chunks.
 */
@Entity
@Table(name = "extraction_jobs", indexes = {
    @Index(name = "idx_ej_status", columnList = "status"),
    @Index(name = "idx_ej_fact_sheet", columnList = "fact_sheet_id"),
    @Index(name = "idx_ej_builder_type", columnList = "builder_type"),
    @Index(name = "idx_ej_created", columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtractionJob {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    /**
     * External UUID for API references.
     */
    @Column(name = "job_id", nullable = false, unique = true, length = 36)
    private String jobId;

    /**
     * The fact sheet this job is associated with.
     */
    @Column(name = "fact_sheet_id")
    private Long factSheetId;

    /**
     * Type of builder used: "llm", "manual", "pattern".
     */
    @Column(name = "builder_type", nullable = false, length = 32)
    private String builderType;

    /**
     * JSON-serialized BuilderConfig.
     */
    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson;

    /**
     * Current job status.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private JobStatus status = JobStatus.PENDING;

    /**
     * Total number of chunks to process.
     */
    @Column(name = "total_chunks")
    @Builder.Default
    private Integer totalChunks = 0;

    /**
     * Number of chunks processed so far.
     */
    @Column(name = "processed_chunks")
    @Builder.Default
    private Integer processedChunks = 0;

    /**
     * Number of proposals created.
     */
    @Column(name = "proposals_created")
    @Builder.Default
    private Integer proposalsCreated = 0;

    /**
     * Number of proposals accepted.
     */
    @Column(name = "proposals_accepted")
    @Builder.Default
    private Integer proposalsAccepted = 0;

    /**
     * Number of proposals rejected.
     */
    @Column(name = "proposals_rejected")
    @Builder.Default
    private Integer proposalsRejected = 0;

    /**
     * When this job was created.
     */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * When this job started processing.
     */
    @Column(name = "started_at")
    private LocalDateTime startedAt;

    /**
     * When this job completed (success or failure).
     */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /**
     * Error message if the job failed.
     */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * Optional name/description for this job.
     */
    @Column(name = "job_name", length = 255)
    private String jobName;

    /**
     * Proposals created by this job.
     */
    @OneToMany(mappedBy = "job", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<TripleProposal> proposals = new ArrayList<>();

    /**
     * Extraction logs for this job.
     */
    @OneToMany(mappedBy = "job", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<ExtractionLogRecord> logs = new ArrayList<>();

    /**
     * Job status enumeration.
     */
    public enum JobStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (jobId == null) {
            jobId = UUID.randomUUID().toString();
        }
    }

    /**
     * Mark the job as started.
     */
    public void start() {
        this.status = JobStatus.RUNNING;
        this.startedAt = LocalDateTime.now();
    }

    /**
     * Mark the job as completed successfully.
     */
    public void complete() {
        this.status = JobStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * Mark the job as failed.
     */
    public void fail(String errorMessage) {
        this.status = JobStatus.FAILED;
        this.errorMessage = errorMessage;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * Mark the job as cancelled.
     */
    public void cancel() {
        this.status = JobStatus.CANCELLED;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * Increment processed chunks counter.
     */
    public void incrementProcessedChunks() {
        if (processedChunks == null) processedChunks = 0;
        processedChunks++;
    }

    /**
     * Increment proposals created counter.
     */
    public void incrementProposalsCreated(int count) {
        if (proposalsCreated == null) proposalsCreated = 0;
        proposalsCreated += count;
    }

    /**
     * Get progress percentage.
     */
    public int getProgressPercent() {
        if (totalChunks == null || totalChunks == 0) return 0;
        return (int) ((processedChunks * 100.0) / totalChunks);
    }

    /**
     * Check if the job is terminal (completed, failed, or cancelled).
     */
    public boolean isTerminal() {
        return status == JobStatus.COMPLETED ||
               status == JobStatus.FAILED ||
               status == JobStatus.CANCELLED;
    }
}
