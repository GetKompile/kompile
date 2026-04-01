/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.eval.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * JPA entity for a single experiment run against a specific model.
 */
@Entity
@Table(name = "experiment_runs", indexes = {
    @Index(name = "idx_exp_run_experiment", columnList = "experiment_id"),
    @Index(name = "idx_exp_run_model", columnList = "model_id"),
    @Index(name = "idx_exp_run_status", columnList = "status"),
    @Index(name = "idx_exp_run_suite_result", columnList = "suite_result_id"),
    @Index(name = "idx_exp_run_started", columnList = "started_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExperimentRunEntity {

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "experiment_id", nullable = false)
    private ExperimentEntity experiment;

    /**
     * The model ID from the registry.
     */
    @Column(name = "model_id", nullable = false, length = 255)
    private String modelId;

    /**
     * Optional variant label (e.g., "base", "lora-v2", "quantized").
     */
    @Column(name = "model_variant", length = 255)
    private String modelVariant;

    /**
     * Model type (dense_encoder, sparse_encoder, cross_encoder).
     */
    @Column(name = "model_type", length = 50)
    private String modelType;

    /**
     * Reference to the eval suite result produced by this run.
     */
    @Column(name = "suite_result_id", length = 36)
    private String suiteResultId;

    /**
     * Run status: PENDING, RUNNING, COMPLETED, FAILED.
     */
    @Column(nullable = false, length = 20)
    private String status;

    // Denormalized aggregate scores for fast comparison queries
    @Column(name = "pass_rate")
    private Double passRate;

    @Column(name = "average_score")
    private Double averageScore;

    @Column(name = "passed_count")
    private Integer passedCount;

    @Column(name = "failed_count")
    private Integer failedCount;

    @Column(name = "total_count")
    private Integer totalCount;

    /**
     * Scores broken down by evaluator type (JSON map).
     */
    @Column(name = "scores_by_type_json", columnDefinition = "TEXT")
    private String scoresByTypeJson;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "execution_time_ms")
    private Long executionTimeMs;

    /**
     * Error message if the run failed.
     */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * Run configuration (JSON object).
     */
    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson;

    /**
     * Additional metadata (JSON object).
     */
    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @PrePersist
    protected void onCreate() {
        if (status == null) {
            status = "PENDING";
        }
    }
}
