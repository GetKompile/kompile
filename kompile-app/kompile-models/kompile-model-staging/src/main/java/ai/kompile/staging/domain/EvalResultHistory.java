/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.staging.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Persisted evaluation result. Stores benchmark and custom eval results
 * so they survive application restarts.
 */
@Entity
@Table(name = "eval_result_history", indexes = {
        @Index(name = "idx_eval_result_eval_id", columnList = "evaluationId", unique = true),
        @Index(name = "idx_eval_result_model_id", columnList = "modelId"),
        @Index(name = "idx_eval_result_benchmark", columnList = "benchmarkName"),
        @Index(name = "idx_eval_result_created", columnList = "createdAt")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalResultHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @Column(nullable = false, length = 128, unique = true)
    private String evaluationId;

    @Column(nullable = false, length = 256)
    private String modelId;

    @Column(length = 256)
    private String datasetId;

    @Column(length = 128)
    private String benchmarkName;

    /** JSON-serialized Map<String, Double> of metric scores */
    @Column(columnDefinition = "TEXT")
    private String metricsJson;

    /** JSON-serialized Map<String, Double> of category-level scores (e.g. MMLU per-subject) */
    @Column(columnDefinition = "TEXT")
    private String categoryScoresJson;

    @Column
    private Long evaluationTimeMs;

    @Column
    private Integer samplesEvaluated;

    @Column
    private Integer correctSamples;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(columnDefinition = "TEXT")
    private String error;

    /** JSON-serialized List<SampleResultDto> — only stored if logSamples was enabled */
    @Column(columnDefinition = "TEXT")
    private String sampleResultsJson;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(length = 64)
    private String completedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
