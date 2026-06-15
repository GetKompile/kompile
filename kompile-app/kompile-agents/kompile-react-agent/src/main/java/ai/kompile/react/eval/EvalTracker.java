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
package ai.kompile.react.eval;

import ai.kompile.react.eval.model.EvalCase;
import ai.kompile.react.eval.model.EvalSuite;
import ai.kompile.react.eval.model.EvalSuiteResult;
import ai.kompile.react.eval.model.EvalTestResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tracks evaluation test cases, suites, and their results over time.
 * Provides history and metrics for evaluating agent performance.
 */
public interface EvalTracker {

    // ==================== Test Case Management ====================

    /**
     * Register a test case.
     *
     * @param testCase The test case to register
     */
    void registerTestCase(EvalCase testCase);

    /**
     * Get a test case by ID.
     *
     * @param testCaseId The test case ID
     * @return The test case if found
     */
    Optional<EvalCase> getTestCase(String testCaseId);

    /**
     * Get all test cases.
     *
     * @return List of all test cases
     */
    List<EvalCase> getAllTestCases();

    /**
     * Get test cases for a specific fact sheet.
     *
     * @param factSheetId The fact sheet ID
     * @return List of test cases for that fact sheet
     */
    List<EvalCase> getTestCasesForFactSheet(Long factSheetId);

    /**
     * Get test cases by tag.
     *
     * @param tag The tag to filter by
     * @return List of test cases with that tag
     */
    List<EvalCase> getTestCasesByTag(String tag);

    /**
     * Update a test case.
     *
     * @param testCase The updated test case
     */
    void updateTestCase(EvalCase testCase);

    /**
     * Delete a test case.
     *
     * @param testCaseId The test case ID
     * @return true if deleted
     */
    boolean deleteTestCase(String testCaseId);

    // ==================== Suite Management ====================

    /**
     * Register an evaluation suite.
     *
     * @param suite The suite to register
     */
    void registerSuite(EvalSuite suite);

    /**
     * Get a suite by ID.
     *
     * @param suiteId The suite ID
     * @return The suite if found
     */
    Optional<EvalSuite> getSuite(String suiteId);

    /**
     * Get all suites.
     *
     * @return List of all suites
     */
    List<EvalSuite> getAllSuites();

    /**
     * Get suites for a specific fact sheet.
     *
     * @param factSheetId The fact sheet ID
     * @return List of suites for that fact sheet
     */
    List<EvalSuite> getSuitesForFactSheet(Long factSheetId);

    /**
     * Update a suite.
     *
     * @param suite The updated suite
     */
    void updateSuite(EvalSuite suite);

    /**
     * Delete a suite.
     *
     * @param suiteId The suite ID
     * @return true if deleted
     */
    boolean deleteSuite(String suiteId);

    // ==================== Result Tracking ====================

    /**
     * Record a test result.
     *
     * @param result The test result to record
     */
    void recordTestResult(EvalTestResult result);

    /**
     * Record a suite result.
     *
     * @param result The suite result to record
     */
    void recordSuiteResult(EvalSuiteResult result);

    /**
     * Get the latest result for a test case.
     *
     * @param testCaseId The test case ID
     * @return The latest result if available
     */
    Optional<EvalTestResult> getLatestTestResult(String testCaseId);

    /**
     * Get result history for a test case.
     *
     * @param testCaseId The test case ID
     * @param limit Maximum number of results to return
     * @return List of results in reverse chronological order
     */
    List<EvalTestResult> getTestResultHistory(String testCaseId, int limit);

    /**
     * Get results for a test case within a time range.
     *
     * @param testCaseId The test case ID
     * @param from Start time (inclusive)
     * @param to End time (exclusive)
     * @return List of results within the range
     */
    List<EvalTestResult> getTestResultsInRange(String testCaseId, Instant from, Instant to);

    /**
     * Get the latest result for a suite.
     *
     * @param suiteId The suite ID
     * @return The latest result if available
     */
    Optional<EvalSuiteResult> getLatestSuiteResult(String suiteId);

    /**
     * Get result history for a suite.
     *
     * @param suiteId The suite ID
     * @param limit Maximum number of results to return
     * @return List of results in reverse chronological order
     */
    List<EvalSuiteResult> getSuiteResultHistory(String suiteId, int limit);

    /**
     * Get all results for a fact sheet.
     *
     * @param factSheetId The fact sheet ID
     * @param limit Maximum number of results to return
     * @return List of results in reverse chronological order
     */
    List<EvalTestResult> getResultsForFactSheet(Long factSheetId, int limit);

    // ==================== Metrics and Analysis ====================

    /**
     * Get pass rate for a test case over time.
     *
     * @param testCaseId The test case ID
     * @param windowDays Number of days to look back
     * @return Pass rate (0.0 to 1.0)
     */
    double getTestCasePassRate(String testCaseId, int windowDays);

    /**
     * Get pass rate for a suite over time.
     *
     * @param suiteId The suite ID
     * @param windowDays Number of days to look back
     * @return Pass rate (0.0 to 1.0)
     */
    double getSuitePassRate(String suiteId, int windowDays);

    /**
     * Get average score for a test case over time.
     *
     * @param testCaseId The test case ID
     * @param windowDays Number of days to look back
     * @return Average score (0.0 to 1.0)
     */
    double getTestCaseAverageScore(String testCaseId, int windowDays);

    /**
     * Get score trend for a test case (is it improving or degrading).
     *
     * @param testCaseId The test case ID
     * @param windowDays Number of days to analyze
     * @return Trend value (positive = improving, negative = degrading)
     */
    double getTestCaseScoreTrend(String testCaseId, int windowDays);

    /**
     * Get overall metrics for a fact sheet.
     *
     * @param factSheetId The fact sheet ID
     * @return Map of metric name to value
     */
    Map<String, Double> getFactSheetMetrics(Long factSheetId);

    /**
     * Get failing test cases for a fact sheet.
     *
     * @param factSheetId The fact sheet ID
     * @return List of consistently failing test cases
     */
    List<EvalCase> getFailingTestCases(Long factSheetId);

    // ==================== Cleanup ====================

    /**
     * Clean up old results.
     *
     * @param retentionDays Number of days to retain results
     * @return Number of results deleted
     */
    int cleanupOldResults(int retentionDays);
}
