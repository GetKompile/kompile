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
 * JPA entity for persisting individual test results.
 * Maps to the EvalTestResult model class from kompile-react-agent.
 */
@Entity
@Table(name = "eval_test_results", indexes = {
    @Index(name = "idx_eval_result_test_case", columnList = "test_case_id"),
    @Index(name = "idx_eval_result_suite", columnList = "suite_id"),
    @Index(name = "idx_eval_result_fact_sheet", columnList = "fact_sheet_id"),
    @Index(name = "idx_eval_result_completed", columnList = "completed_at"),
    @Index(name = "idx_eval_result_passed", columnList = "passed"),
    @Index(name = "idx_eval_result_suite_result", columnList = "suite_result_id"),
    @Index(name = "idx_eval_result_model", columnList = "model_id"),
    @Index(name = "idx_eval_result_experiment_run", columnList = "experiment_run_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalTestResultEntity {

    @Id
    @Column(length = 36)
    private String id;

    /**
     * The test case ID that was executed.
     */
    @Column(name = "test_case_id", nullable = false, length = 36)
    private String testCaseId;

    /**
     * The test case name (denormalized for display).
     */
    @Column(name = "test_case_name", length = 255)
    private String testCaseName;

    /**
     * The suite ID if part of a suite run.
     */
    @Column(name = "suite_id", length = 36)
    private String suiteId;

    /**
     * Reference to suite result (if part of a suite run).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "suite_result_id")
    private EvalSuiteResultEntity suiteResult;

    /**
     * The fact sheet ID the test was run against.
     */
    @Column(name = "fact_sheet_id")
    private Long factSheetId;

    /**
     * The execution ID of the agent run.
     */
    @Column(name = "execution_id", length = 255)
    private String executionId;

    /**
     * Whether the test passed overall.
     */
    @Column(nullable = false)
    private Boolean passed;

    /**
     * Overall score (0.0 to 1.0).
     */
    @Column(nullable = false)
    @Builder.Default
    private Double score = 0.0;

    /**
     * The input query that was tested.
     */
    @Column(columnDefinition = "TEXT")
    private String query;

    /**
     * The expected answer (if provided).
     */
    @Column(name = "expected_answer", columnDefinition = "TEXT")
    private String expectedAnswer;

    /**
     * The actual answer produced by the agent.
     */
    @Column(name = "actual_answer", columnDefinition = "TEXT")
    private String actualAnswer;

    /**
     * Retrieved documents used as context (JSON array).
     */
    @Column(name = "retrieved_documents_json", columnDefinition = "TEXT")
    private String retrievedDocumentsJson;

    /**
     * Tool calls made during execution (JSON array).
     */
    @Column(name = "tool_calls_json", columnDefinition = "TEXT")
    private String toolCallsJson;

    /**
     * Number of reasoning steps taken.
     */
    @Column(name = "steps_executed")
    private Integer stepsExecuted;

    /**
     * Individual evaluation results by type (JSON object).
     */
    @Column(name = "evaluation_results_json", columnDefinition = "TEXT")
    private String evaluationResultsJson;

    /**
     * Scores per evaluation type (JSON map).
     */
    @Column(name = "scores_json", columnDefinition = "TEXT")
    private String scoresJson;

    /**
     * Whether each evaluation type passed (JSON map).
     */
    @Column(name = "passed_by_type_json", columnDefinition = "TEXT")
    private String passedByTypeJson;

    /**
     * Failure reasons if the test failed (JSON array).
     */
    @Column(name = "failure_reasons_json", columnDefinition = "TEXT")
    private String failureReasonsJson;

    /**
     * When the test started.
     */
    @Column(name = "started_at")
    private Instant startedAt;

    /**
     * When the test completed.
     */
    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    /**
     * Total execution time in milliseconds.
     */
    @Column(name = "execution_time_ms")
    private Long executionTimeMs;

    /**
     * Total tokens used (input + output).
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
}
