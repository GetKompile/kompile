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
import java.util.ArrayList;
import java.util.List;

/**
 * JPA entity for tracking experiments that compare eval results across models.
 */
@Entity
@Table(name = "experiments", indexes = {
    @Index(name = "idx_experiment_status", columnList = "status"),
    @Index(name = "idx_experiment_suite", columnList = "suite_id"),
    @Index(name = "idx_experiment_dataset", columnList = "dataset_id"),
    @Index(name = "idx_experiment_created", columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExperimentEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 1000)
    private String description;

    /**
     * The eval suite used for this experiment.
     */
    @Column(name = "suite_id", nullable = false, length = 36)
    private String suiteId;

    /**
     * Optional dataset ID used for this experiment.
     */
    @Column(name = "dataset_id", length = 36)
    private String datasetId;

    /**
     * Experiment status: PENDING, RUNNING, COMPLETED, FAILED.
     */
    @Column(nullable = false, length = 20)
    private String status;

    /**
     * Tags for categorization (JSON array).
     */
    @Column(name = "tags_json", columnDefinition = "TEXT")
    private String tagsJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Additional metadata (JSON object).
     */
    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @OneToMany(mappedBy = "experiment", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<ExperimentRunEntity> runs = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (status == null) {
            status = "PENDING";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public void addRun(ExperimentRunEntity run) {
        if (runs == null) {
            runs = new ArrayList<>();
        }
        runs.add(run);
        run.setExperiment(this);
    }
}
