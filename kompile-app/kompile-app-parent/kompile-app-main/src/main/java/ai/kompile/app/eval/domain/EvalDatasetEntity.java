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
 * JPA entity for eval dataset metadata.
 * Links an uploaded dataset (CSV/JSONL) to the eval suite holding its test cases.
 */
@Entity
@Table(name = "eval_datasets", indexes = {
    @Index(name = "idx_eval_dataset_name", columnList = "name"),
    @Index(name = "idx_eval_dataset_suite", columnList = "suite_id"),
    @Index(name = "idx_eval_dataset_created", columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalDatasetEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 1000)
    private String description;

    /**
     * The eval suite holding the test cases parsed from this dataset.
     */
    @Column(name = "suite_id", nullable = false, length = 36)
    private String suiteId;

    /**
     * Format of the original file: csv, jsonl, manual.
     */
    @Column(nullable = false, length = 20)
    private String format;

    /**
     * Number of samples/rows in the dataset.
     */
    @Column(name = "sample_count")
    @Builder.Default
    private Integer sampleCount = 0;

    /**
     * Dataset version string.
     */
    @Column(length = 50)
    private String version;

    /**
     * Tags for categorization (JSON array).
     */
    @Column(name = "tags_json", columnDefinition = "TEXT")
    private String tagsJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
