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
package ai.kompile.react.eval.model;

import ai.kompile.core.evaluation.EvaluationReport;
import ai.kompile.core.evaluation.EvaluationResult;
import ai.kompile.core.evaluation.EvaluationType;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Result of running an individual evaluation test case.
 * Captures the full execution details and evaluation metrics.
 */
@Data
@Builder
public class EvalTestResult {

    /**
     * Unique identifier for this result.
     */
    private String id;

    /**
     * The test case ID that was executed.
     */
    private String testCaseId;

    /**
     * The test case name.
     */
    private String testCaseName;

    /**
     * The suite ID if part of a suite run.
     */
    private String suiteId;

    /**
     * The fact sheet ID the test was run against.
     */
    private Long factSheetId;

    /**
     * The execution ID of the agent run.
     */
    private String executionId;

    /**
     * Whether the test passed overall.
     */
    private boolean passed;

    /**
     * Overall score (0.0 to 1.0).
     */
    private double score;

    /**
     * The input query that was tested.
     */
    private String query;

    /**
     * The expected answer (if provided).
     */
    private String expectedAnswer;

    /**
     * The actual answer produced by the agent.
     */
    private String actualAnswer;

    /**
     * Retrieved documents used as context.
     */
    @Builder.Default
    private List<String> retrievedDocuments = new ArrayList<>();

    /**
     * Tool calls made during execution.
     */
    @Builder.Default
    private List<String> toolCalls = new ArrayList<>();

    /**
     * Number of reasoning steps taken.
     */
    private int stepsExecuted;

    /**
     * Individual evaluation results by type.
     */
    @Builder.Default
    private Map<EvaluationType, EvaluationResult> evaluationResults = Map.of();

    /**
     * The full evaluation report.
     */
    private EvaluationReport evaluationReport;

    /**
     * Scores per evaluation type.
     */
    @Builder.Default
    private Map<EvaluationType, Double> scores = Map.of();

    /**
     * Whether each evaluation type passed.
     */
    @Builder.Default
    private Map<EvaluationType, Boolean> passedByType = Map.of();

    /**
     * Failure reasons if the test failed.
     */
    @Builder.Default
    private List<String> failureReasons = new ArrayList<>();

    /**
     * When the test started.
     */
    private Instant startedAt;

    /**
     * When the test completed.
     */
    private Instant completedAt;

    /**
     * Total execution time in milliseconds.
     */
    private long executionTimeMs;

    /**
     * Total tokens used (input + output).
     */
    private long totalTokens;

    /**
     * Additional metadata.
     */
    @Builder.Default
    private Map<String, Object> metadata = Map.of();

    /**
     * Create a passing result.
     */
    public static EvalTestResult pass(String testCaseId, String query, String actualAnswer, double score) {
        return EvalTestResult.builder()
                .id(java.util.UUID.randomUUID().toString())
                .testCaseId(testCaseId)
                .query(query)
                .actualAnswer(actualAnswer)
                .passed(true)
                .score(score)
                .completedAt(Instant.now())
                .build();
    }

    /**
     * Create a failing result.
     */
    public static EvalTestResult fail(String testCaseId, String query, String actualAnswer,
                                       double score, List<String> failureReasons) {
        return EvalTestResult.builder()
                .id(java.util.UUID.randomUUID().toString())
                .testCaseId(testCaseId)
                .query(query)
                .actualAnswer(actualAnswer)
                .passed(false)
                .score(score)
                .failureReasons(failureReasons)
                .completedAt(Instant.now())
                .build();
    }
}
