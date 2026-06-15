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
package ai.kompile.react.tool;

import ai.kompile.react.eval.EvalTracker;
import ai.kompile.react.eval.model.EvalCase;
import ai.kompile.react.eval.model.EvalSuite;
import ai.kompile.react.eval.model.EvalTestResult;
import ai.kompile.react.model.ToolDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tool that allows the ReAct agent to query evaluation test cases
 * and results tied to fact sheets.
 *
 * <p>Provides capabilities to:
 * <ul>
 *   <li>Look up test cases for a fact sheet</li>
 *   <li>Query test result history</li>
 *   <li>Get performance metrics</li>
 *   <li>Find similar queries and their expected answers</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class FactSheetEvalTool {

    private final EvalTracker evalTracker;

    /**
     * Create a tool for querying test cases.
     */
    public ToolDefinition createTestCaseLookupTool() {
        return ToolDefinition.builder()
                .name("lookup_test_cases")
                .description("Look up evaluation test cases for a fact sheet. " +
                        "Returns test cases with expected answers and evaluation criteria. " +
                        "Use this to understand what responses are expected for similar queries.")
                .parameters(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "fact_sheet_id", Map.of(
                                        "type", "integer",
                                        "description", "The fact sheet ID to look up test cases for"
                                ),
                                "query", Map.of(
                                        "type", "string",
                                        "description", "Optional query to find similar test cases"
                                ),
                                "tag", Map.of(
                                        "type", "string",
                                        "description", "Optional tag to filter test cases"
                                ),
                                "limit", Map.of(
                                        "type", "integer",
                                        "description", "Maximum number of test cases to return (default: 5)"
                                )
                        ),
                        "required", List.of()
                ))
                .executor(this::executeTestCaseLookup)
                .parallelizable(true)
                .build();
    }

    /**
     * Create a tool for querying test results.
     */
    public ToolDefinition createResultsQueryTool() {
        return ToolDefinition.builder()
                .name("query_test_results")
                .description("Query evaluation test results for a specific test case or fact sheet. " +
                        "Returns recent test executions and their scores.")
                .parameters(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "test_case_id", Map.of(
                                        "type", "string",
                                        "description", "The test case ID to get results for"
                                ),
                                "fact_sheet_id", Map.of(
                                        "type", "integer",
                                        "description", "The fact sheet ID to get all results for"
                                ),
                                "limit", Map.of(
                                        "type", "integer",
                                        "description", "Maximum number of results to return (default: 10)"
                                )
                        ),
                        "required", List.of()
                ))
                .executor(this::executeResultsQuery)
                .parallelizable(true)
                .build();
    }

    /**
     * Create a tool for getting performance metrics.
     */
    public ToolDefinition createMetricsTool() {
        return ToolDefinition.builder()
                .name("get_eval_metrics")
                .description("Get evaluation performance metrics for a fact sheet. " +
                        "Returns pass rates, average scores, and identifies failing test cases.")
                .parameters(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "fact_sheet_id", Map.of(
                                        "type", "integer",
                                        "description", "The fact sheet ID to get metrics for"
                                ),
                                "window_days", Map.of(
                                        "type", "integer",
                                        "description", "Number of days to look back (default: 7)"
                                )
                        ),
                        "required", List.of("fact_sheet_id")
                ))
                .executor(this::executeMetricsQuery)
                .parallelizable(true)
                .build();
    }

    /**
     * Create a tool for finding expected answers.
     */
    public ToolDefinition createExpectedAnswerTool() {
        return ToolDefinition.builder()
                .name("find_expected_answer")
                .description("Find the expected answer for a query based on registered test cases. " +
                        "Use this when you want to verify your answer against known good answers.")
                .parameters(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of(
                                        "type", "string",
                                        "description", "The query to find expected answer for"
                                ),
                                "fact_sheet_id", Map.of(
                                        "type", "integer",
                                        "description", "Optional fact sheet ID to scope the search"
                                )
                        ),
                        "required", List.of("query")
                ))
                .executor(this::executeFindExpectedAnswer)
                .parallelizable(true)
                .build();
    }

    private String executeTestCaseLookup(Map<String, Object> args) {
        Long factSheetId = extractLong(args, "fact_sheet_id");
        String query = (String) args.get("query");
        String tag = (String) args.get("tag");
        int limit = extractInt(args, "limit", 5);

        try {
            List<EvalCase> testCases;

            if (tag != null && !tag.isBlank()) {
                testCases = evalTracker.getTestCasesByTag(tag);
            } else if (factSheetId != null) {
                testCases = evalTracker.getTestCasesForFactSheet(factSheetId);
            } else {
                testCases = evalTracker.getAllTestCases();
            }

            // Filter by query similarity if provided
            if (query != null && !query.isBlank()) {
                String lowerQuery = query.toLowerCase();
                testCases = testCases.stream()
                        .filter(tc -> tc.getQuery() != null &&
                                tc.getQuery().toLowerCase().contains(lowerQuery))
                        .toList();
            }

            // Limit results
            testCases = testCases.stream().limit(limit).toList();

            return formatTestCases(testCases);

        } catch (Exception e) {
            log.error("Test case lookup failed: {}", e.getMessage(), e);
            return "Error looking up test cases: " + e.getMessage();
        }
    }

    private String executeResultsQuery(Map<String, Object> args) {
        String testCaseId = (String) args.get("test_case_id");
        Long factSheetId = extractLong(args, "fact_sheet_id");
        int limit = extractInt(args, "limit", 10);

        try {
            List<EvalTestResult> results;

            if (testCaseId != null && !testCaseId.isBlank()) {
                results = evalTracker.getTestResultHistory(testCaseId, limit);
            } else if (factSheetId != null) {
                results = evalTracker.getResultsForFactSheet(factSheetId, limit);
            } else {
                return "Error: Either test_case_id or fact_sheet_id is required";
            }

            return formatTestResults(results);

        } catch (Exception e) {
            log.error("Results query failed: {}", e.getMessage(), e);
            return "Error querying results: " + e.getMessage();
        }
    }

    private String executeMetricsQuery(Map<String, Object> args) {
        Long factSheetId = extractLong(args, "fact_sheet_id");
        int windowDays = extractInt(args, "window_days", 7);

        if (factSheetId == null) {
            return "Error: fact_sheet_id is required";
        }

        try {
            Map<String, Double> metrics = evalTracker.getFactSheetMetrics(factSheetId);
            List<EvalCase> failingCases = evalTracker.getFailingTestCases(factSheetId);

            StringBuilder sb = new StringBuilder();
            sb.append("## Evaluation Metrics for Fact Sheet ").append(factSheetId).append("\n\n");

            sb.append("**Summary**\n");
            sb.append("- Total Test Cases: ").append(metrics.getOrDefault("total_test_cases", 0.0).intValue()).append("\n");
            sb.append("- Enabled Test Cases: ").append(metrics.getOrDefault("enabled_test_cases", 0.0).intValue()).append("\n");
            sb.append("- Total Results: ").append(metrics.getOrDefault("total_results", 0.0).intValue()).append("\n");
            sb.append("- Pass Rate: ").append(String.format("%.1f%%", metrics.getOrDefault("pass_rate", 0.0) * 100)).append("\n");
            sb.append("- Average Score: ").append(String.format("%.2f", metrics.getOrDefault("average_score", 0.0))).append("\n\n");

            if (!failingCases.isEmpty()) {
                sb.append("**Failing Test Cases**\n");
                for (EvalCase tc : failingCases.stream().limit(5).toList()) {
                    sb.append("- ").append(tc.getName()).append(": ").append(tc.getQuery()).append("\n");
                    double passRate = evalTracker.getTestCasePassRate(tc.getId(), windowDays);
                    sb.append("  Pass Rate: ").append(String.format("%.1f%%", passRate * 100)).append("\n");
                }
            } else {
                sb.append("**No consistently failing test cases.**\n");
            }

            return sb.toString();

        } catch (Exception e) {
            log.error("Metrics query failed: {}", e.getMessage(), e);
            return "Error querying metrics: " + e.getMessage();
        }
    }

    private String executeFindExpectedAnswer(Map<String, Object> args) {
        String query = (String) args.get("query");
        Long factSheetId = extractLong(args, "fact_sheet_id");

        if (query == null || query.isBlank()) {
            return "Error: query is required";
        }

        try {
            List<EvalCase> testCases;
            if (factSheetId != null) {
                testCases = evalTracker.getTestCasesForFactSheet(factSheetId);
            } else {
                testCases = evalTracker.getAllTestCases();
            }

            // Find best matching test case
            String lowerQuery = query.toLowerCase();
            Optional<EvalCase> bestMatch = testCases.stream()
                    .filter(tc -> tc.getQuery() != null && tc.getExpectedAnswer() != null)
                    .filter(tc -> calculateSimilarity(lowerQuery, tc.getQuery().toLowerCase()) > 0.5)
                    .max((a, b) -> Double.compare(
                            calculateSimilarity(lowerQuery, a.getQuery().toLowerCase()),
                            calculateSimilarity(lowerQuery, b.getQuery().toLowerCase())
                    ));

            if (bestMatch.isPresent()) {
                EvalCase tc = bestMatch.get();
                StringBuilder sb = new StringBuilder();
                sb.append("## Expected Answer Found\n\n");
                sb.append("**Test Case**: ").append(tc.getName()).append("\n");
                sb.append("**Query**: ").append(tc.getQuery()).append("\n");
                sb.append("**Expected Answer**: ").append(tc.getExpectedAnswer()).append("\n");

                if (!tc.getExpectedFacts().isEmpty()) {
                    sb.append("**Key Facts**: ").append(String.join(", ", tc.getExpectedFacts())).append("\n");
                }

                if (!tc.getForbiddenFacts().isEmpty()) {
                    sb.append("**Avoid These**: ").append(String.join(", ", tc.getForbiddenFacts())).append("\n");
                }

                return sb.toString();
            } else {
                return "No matching test case found for the query.";
            }

        } catch (Exception e) {
            log.error("Expected answer lookup failed: {}", e.getMessage(), e);
            return "Error finding expected answer: " + e.getMessage();
        }
    }

    private double calculateSimilarity(String s1, String s2) {
        // Simple Jaccard similarity on words
        String[] words1 = s1.split("\\W+");
        String[] words2 = s2.split("\\W+");

        java.util.Set<String> set1 = new java.util.HashSet<>(java.util.Arrays.asList(words1));
        java.util.Set<String> set2 = new java.util.HashSet<>(java.util.Arrays.asList(words2));

        java.util.Set<String> intersection = new java.util.HashSet<>(set1);
        intersection.retainAll(set2);

        java.util.Set<String> union = new java.util.HashSet<>(set1);
        union.addAll(set2);

        if (union.isEmpty()) {
            return 0.0;
        }

        return (double) intersection.size() / union.size();
    }

    private String formatTestCases(List<EvalCase> testCases) {
        if (testCases.isEmpty()) {
            return "No test cases found.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## Test Cases\n\n");

        for (EvalCase tc : testCases) {
            sb.append("### ").append(tc.getName()).append("\n");
            sb.append("**ID**: ").append(tc.getId()).append("\n");
            sb.append("**Query**: ").append(tc.getQuery()).append("\n");

            if (tc.getExpectedAnswer() != null) {
                sb.append("**Expected**: ").append(tc.getExpectedAnswer()).append("\n");
            }

            if (!tc.getExpectedFacts().isEmpty()) {
                sb.append("**Key Facts**: ").append(String.join(", ", tc.getExpectedFacts())).append("\n");
            }

            if (!tc.getTags().isEmpty()) {
                sb.append("**Tags**: ").append(String.join(", ", tc.getTags())).append("\n");
            }

            sb.append("**Priority**: ").append(tc.getPriority()).append("\n");
            sb.append("**Enabled**: ").append(tc.isEnabled()).append("\n\n");
        }

        return sb.toString();
    }

    private String formatTestResults(List<EvalTestResult> results) {
        if (results.isEmpty()) {
            return "No test results found.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## Test Results\n\n");

        for (EvalTestResult r : results) {
            sb.append("### ").append(r.getTestCaseId() != null ? r.getTestCaseId() : "Unknown").append("\n");
            sb.append("**Status**: ").append(r.isPassed() ? "PASSED" : "FAILED").append("\n");
            sb.append("**Score**: ").append(String.format("%.2f", r.getScore())).append("\n");
            sb.append("**Query**: ").append(r.getQuery()).append("\n");

            if (r.getActualAnswer() != null) {
                String answer = r.getActualAnswer();
                if (answer.length() > 200) {
                    answer = answer.substring(0, 200) + "...";
                }
                sb.append("**Answer**: ").append(answer).append("\n");
            }

            if (!r.getFailureReasons().isEmpty()) {
                sb.append("**Failures**: ").append(String.join(", ", r.getFailureReasons())).append("\n");
            }

            sb.append("**Time**: ").append(r.getExecutionTimeMs()).append("ms\n");
            sb.append("**Completed**: ").append(r.getCompletedAt()).append("\n\n");
        }

        return sb.toString();
    }

    private Long extractLong(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Long) {
            return (Long) value;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private int extractInt(Map<String, Object> args, String key, int defaultValue) {
        Object value = args.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
