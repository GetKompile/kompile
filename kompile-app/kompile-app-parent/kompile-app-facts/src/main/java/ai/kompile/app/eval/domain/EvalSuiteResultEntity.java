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
 * JPA entity for persisting suite execution results.
 * Maps to the EvalSuiteResult model class from kompile-react-agent.
 */
@Entity
@Table(name = "eval_suite_results", indexes = {
    @Index(name = "idx_eval_suite_result_suite", columnList = "suite_id"),
    @Index(name = "idx_eval_suite_result_fact_sheet", columnList = "fact_sheet_id"),
    @Index(name = "idx_eval_suite_result_completed", columnList = "completed_at"),
    @Index(name = "idx_eval_suite_result_passed", columnList = "passed"),
    @Index(name = "idx_eval_suite_result_model", columnList = "model_id"),
    @Index(name = "idx_eval_suite_result_experiment_run", columnList = "experiment_run_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalSuiteResultEntity {

    @Id
    @Column(length = 36)
    private String id;

    /**
     * The suite ID that was executed.
     */
    @Column(name = "suite_id", nullable = false, length = 36)
    private String suiteId;

    /**
     * The suite name (denormalized for display).
     */
    @Column(name = "suite_name", length = 255)
    private String suiteName;

    /**
     * The fact sheet ID if suite was scoped to one.
     */
    @Column(name = "fact_sheet_id")
    private Long factSheetId;

    /**
     * Whether the suite passed overall.
     */
    @Column(nullable = false)
    private Boolean passed;

    /**
     * Overall pass rate (passed tests / total tests).
     */
    @Column(name = "pass_rate", nullable = false)
    @Builder.Default
    private Double passRate = 0.0;

    /**
     * Average score across all tests.
     */
    @Column(name = "average_score", nullable = false)
    @Builder.Default
    private Double averageScore = 0.0;

    /**
     * Number of tests that passed.
     */
    @Column(name = "passed_count")
    @Builder.Default
    private Integer passedCount = 0;

    /**
     * Number of tests that failed.
     */
    @Column(name = "failed_count")
    @Builder.Default
    private Integer failedCount = 0;

    /**
     * Number of tests skipped.
     */
    @Column(name = "skipped_count")
    @Builder.Default
    private Integer skippedCount = 0;

    /**
     * Total number of tests.
     */
    @Column(name = "total_count")
    @Builder.Default
    private Integer totalCount = 0;

    /**
     * Individual test results.
     */
    @OneToMany(mappedBy = "suiteResult", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<EvalTestResultEntity> testResults = new ArrayList<>();

    /**
     * Average scores per evaluation type (JSON map).
     */
    @Column(name = "average_scores_by_type_json", columnDefinition = "TEXT")
    private String averageScoresByTypeJson;

    /**
     * Pass rates per evaluation type (JSON map).
     */
    @Column(name = "pass_rates_by_type_json", columnDefinition = "TEXT")
    private String passRatesByTypeJson;

    /**
     * Test cases that failed with their reasons (JSON map).
     */
    @Column(name = "failed_tests_json", columnDefinition = "TEXT")
    private String failedTestsJson;

    /**
     * When the suite run started.
     */
    @Column(name = "started_at")
    private Instant startedAt;

    /**
     * When the suite run completed.
     */
    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    /**
     * Total execution time in milliseconds.
     */
    @Column(name = "execution_time_ms")
    private Long executionTimeMs;

    /**
     * Total tokens used across all tests.
     */
    @Column(name = "total_tokens")
    private Long totalTokens;

    /**
     * The model ID that produced this result (for experiment tracking).
     */
    @Column(name = "model_id", length = 255)
    private String modelId;

    /**
     * The experiment run ID this result belongs to.
     */
    @Column(name = "experiment_run_id", length = 36)
    private String experimentRunId;

    /**
     * Additional metadata (JSON object).
     */
    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @PrePersist
    protected void onCreate() {
        if (completedAt == null) {
            completedAt = Instant.now();
        }
        if (startedAt == null) {
            startedAt = completedAt;
        }
    }

    /**
     * Add a test result and update aggregates.
     */
    public void addTestResult(EvalTestResultEntity result) {
        if (testResults == null) {
            testResults = new ArrayList<>();
        }
        testResults.add(result);
        result.setSuiteResult(this);
        updateAggregates();
    }

    /**
     * Update aggregate counts and scores.
     */
    public void updateAggregates() {
        if (testResults == null || testResults.isEmpty()) {
            totalCount = 0;
            passedCount = 0;
            failedCount = 0;
            passRate = 0.0;
            averageScore = 0.0;
            return;
        }

        totalCount = testResults.size();
        passedCount = (int) testResults.stream()
                .filter(r -> Boolean.TRUE.equals(r.getPassed()))
                .count();
        failedCount = totalCount - passedCount;
        passRate = totalCount > 0 ? (double) passedCount / totalCount : 0.0;
        averageScore = testResults.stream()
                .mapToDouble(r -> r.getScore() != null ? r.getScore() : 0.0)
                .average()
                .orElse(0.0);
    }

    /**
     * Calculate whether the suite passed based on required pass rate.
     */
    public boolean calculatePassed(double requiredPassRate) {
        this.passed = passRate >= requiredPassRate;
        return this.passed;
    }
}
