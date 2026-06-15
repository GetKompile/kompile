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

import ai.kompile.core.evaluation.EvaluationType;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of running an evaluation suite.
 * Aggregates results from all test cases in the suite.
 */
@Data
@Builder
public class EvalSuiteResult {

    /**
     * Unique identifier for this suite run.
     */
    private String id;

    /**
     * The suite ID that was executed.
     */
    private String suiteId;

    /**
     * The suite name.
     */
    private String suiteName;

    /**
     * The fact sheet ID if suite was scoped to one.
     */
    private Long factSheetId;

    /**
     * Whether the suite passed overall.
     */
    private boolean passed;

    /**
     * Overall pass rate (passed tests / total tests).
     */
    private double passRate;

    /**
     * Average score across all tests.
     */
    private double averageScore;

    /**
     * Number of tests that passed.
     */
    private int passedCount;

    /**
     * Number of tests that failed.
     */
    private int failedCount;

    /**
     * Number of tests skipped.
     */
    private int skippedCount;

    /**
     * Total number of tests.
     */
    private int totalCount;

    /**
     * Individual test results.
     */
    @Builder.Default
    private List<EvalTestResult> testResults = new ArrayList<>();

    /**
     * Average scores per evaluation type.
     */
    @Builder.Default
    private Map<EvaluationType, Double> averageScoresByType = new HashMap<>();

    /**
     * Pass rates per evaluation type.
     */
    @Builder.Default
    private Map<EvaluationType, Double> passRatesByType = new HashMap<>();

    /**
     * Test cases that failed by ID and failure reasons.
     */
    @Builder.Default
    private Map<String, List<String>> failedTests = new HashMap<>();

    /**
     * When the suite run started.
     */
    private Instant startedAt;

    /**
     * When the suite run completed.
     */
    private Instant completedAt;

    /**
     * Total execution time in milliseconds.
     */
    private long executionTimeMs;

    /**
     * Total tokens used across all tests.
     */
    private long totalTokens;

    /**
     * Additional metadata.
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    /**
     * Add a test result to the suite.
     */
    public void addTestResult(EvalTestResult result) {
        if (testResults == null) {
            testResults = new ArrayList<>();
        }
        testResults.add(result);

        // Update counts
        totalCount = testResults.size();
        passedCount = (int) testResults.stream().filter(EvalTestResult::isPassed).count();
        failedCount = totalCount - passedCount;

        // Update pass rate
        passRate = totalCount > 0 ? (double) passedCount / totalCount : 0.0;

        // Update average score
        averageScore = testResults.stream()
                .mapToDouble(EvalTestResult::getScore)
                .average()
                .orElse(0.0);

        // Track failed tests
        if (!result.isPassed()) {
            failedTests.put(result.getTestCaseId(), result.getFailureReasons());
        }
    }

    /**
     * Calculate whether the suite passed based on required pass rate.
     */
    public boolean calculatePassed(double requiredPassRate) {
        this.passed = passRate >= requiredPassRate;
        return this.passed;
    }

    /**
     * Generate a summary of the suite run.
     */
    public String generateSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Suite: ").append(suiteName).append("\n");
        sb.append("Status: ").append(passed ? "PASSED" : "FAILED").append("\n");
        sb.append("Pass Rate: ").append(String.format("%.1f%%", passRate * 100)).append("\n");
        sb.append("Average Score: ").append(String.format("%.2f", averageScore)).append("\n");
        sb.append("Tests: ").append(passedCount).append("/").append(totalCount).append(" passed\n");

        if (!failedTests.isEmpty()) {
            sb.append("\nFailed Tests:\n");
            for (Map.Entry<String, List<String>> entry : failedTests.entrySet()) {
                sb.append("  - ").append(entry.getKey()).append(": ");
                sb.append(String.join(", ", entry.getValue())).append("\n");
            }
        }

        sb.append("Execution Time: ").append(executionTimeMs).append("ms\n");
        return sb.toString();
    }

    /**
     * Create an empty result for a suite.
     */
    public static EvalSuiteResult forSuite(EvalSuite suite) {
        return EvalSuiteResult.builder()
                .id(java.util.UUID.randomUUID().toString())
                .suiteId(suite.getId())
                .suiteName(suite.getName())
                .factSheetId(suite.getFactSheetId())
                .startedAt(Instant.now())
                .build();
    }
}
