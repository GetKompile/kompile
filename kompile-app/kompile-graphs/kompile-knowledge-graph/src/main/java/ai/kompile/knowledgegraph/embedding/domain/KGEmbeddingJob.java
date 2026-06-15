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

package ai.kompile.knowledgegraph.embedding.domain;

import ai.kompile.core.kgembedding.KGEmbeddingAlgorithm;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity tracking KG embedding training jobs.
 */
@Entity
@Table(name = "kg_embedding_jobs", indexes = {
    @Index(name = "idx_kg_jobs_fact_sheet", columnList = "fact_sheet_id"),
    @Index(name = "idx_kg_jobs_status", columnList = "status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KGEmbeddingJob {

    @Id
    @Column(name = "job_id", length = 36)
    private String jobId;

    @Column(name = "fact_sheet_id", nullable = false)
    private Long factSheetId;

    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private KGEmbeddingAlgorithm algorithm;

    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private JobStatus status;

    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════

    @Column(name = "embedding_dim")
    private Integer embeddingDim;

    @Column
    private Integer epochs;

    @Column(name = "learning_rate")
    private Double learningRate;

    @Column(name = "batch_size")
    private Integer batchSize;

    @Column
    private Double margin;

    @Column(name = "negative_samples")
    private Integer negativeSamples;

    // ═══════════════════════════════════════════════════════════════════════════
    // PROGRESS
    // ═══════════════════════════════════════════════════════════════════════════

    @Column(name = "current_epoch")
    private Integer currentEpoch;

    @Column(name = "current_loss")
    private Double currentLoss;

    @Column(name = "total_triples")
    private Integer totalTriples;

    // ═══════════════════════════════════════════════════════════════════════════
    // RESULTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Column(name = "embedding_version")
    private Long embeddingVersion;

    @Column(name = "entities_embedded")
    private Integer entitiesEmbedded;

    @Column(name = "relations_embedded")
    private Integer relationsEmbedded;

    // ═══════════════════════════════════════════════════════════════════════════
    // TIMING
    // ═══════════════════════════════════════════════════════════════════════════

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    // ═══════════════════════════════════════════════════════════════════════════
    // ERROR HANDLING
    // ═══════════════════════════════════════════════════════════════════════════

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @PrePersist
    protected void onCreate() {
        if (jobId == null) {
            jobId = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == null) {
            status = JobStatus.PENDING;
        }
    }

    /**
     * Returns progress as a percentage (0-100).
     */
    public double getProgressPercent() {
        if (epochs == null || epochs == 0 || currentEpoch == null) {
            return 0;
        }
        return (double) currentEpoch / epochs * 100;
    }

    /**
     * Returns training duration in milliseconds.
     */
    public Long getDurationMs() {
        if (startedAt == null) return null;
        Instant end = completedAt != null ? completedAt : Instant.now();
        return end.toEpochMilli() - startedAt.toEpochMilli();
    }

    /**
     * Job status enum.
     */
    public enum JobStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }
}
