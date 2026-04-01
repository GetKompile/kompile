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
package ai.kompile.react.eval.impl;

import ai.kompile.react.eval.EvalTracker;
import ai.kompile.react.eval.model.EvalCase;
import ai.kompile.react.eval.model.EvalSuite;
import ai.kompile.react.eval.model.EvalSuiteResult;
import ai.kompile.react.eval.model.EvalTestResult;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory implementation of EvalTracker.
 * Suitable for development and testing. For production, use a database-backed implementation.
 */
@Slf4j
public class InMemoryEvalTracker implements EvalTracker {

    private final Map<String, EvalCase> testCases = new ConcurrentHashMap<>();
    private final Map<String, EvalSuite> suites = new ConcurrentHashMap<>();
    private final Map<String, List<EvalTestResult>> testResults = new ConcurrentHashMap<>();
    private final Map<String, List<EvalSuiteResult>> suiteResults = new ConcurrentHashMap<>();

    // ==================== Test Case Management ====================

    @Override
    public void registerTestCase(EvalCase testCase) {
        testCases.put(testCase.getId(), testCase);
        log.debug("Registered test case: {}", testCase.getId());
    }

    @Override
    public Optional<EvalCase> getTestCase(String testCaseId) {
        return Optional.ofNullable(testCases.get(testCaseId));
    }

    @Override
    public List<EvalCase> getAllTestCases() {
        return new ArrayList<>(testCases.values());
    }

    @Override
    public List<EvalCase> getTestCasesForFactSheet(Long factSheetId) {
        return testCases.values().stream()
                .filter(tc -> Objects.equals(tc.getFactSheetId(), factSheetId))
                .collect(Collectors.toList());
    }

    @Override
    public List<EvalCase> getTestCasesByTag(String tag) {
        return testCases.values().stream()
                .filter(tc -> tc.getTags() != null && tc.getTags().contains(tag))
                .collect(Collectors.toList());
    }

    @Override
    public void updateTestCase(EvalCase testCase) {
        testCase.setUpdatedAt(Instant.now());
        testCases.put(testCase.getId(), testCase);
    }

    @Override
    public boolean deleteTestCase(String testCaseId) {
        return testCases.remove(testCaseId) != null;
    }

    // ==================== Suite Management ====================

    @Override
    public void registerSuite(EvalSuite suite) {
        suites.put(suite.getId(), suite);
        log.debug("Registered suite: {}", suite.getId());
    }

    @Override
    public Optional<EvalSuite> getSuite(String suiteId) {
        return Optional.ofNullable(suites.get(suiteId));
    }

    @Override
    public List<EvalSuite> getAllSuites() {
        return new ArrayList<>(suites.values());
    }

    @Override
    public List<EvalSuite> getSuitesForFactSheet(Long factSheetId) {
        return suites.values().stream()
                .filter(s -> Objects.equals(s.getFactSheetId(), factSheetId))
                .collect(Collectors.toList());
    }

    @Override
    public void updateSuite(EvalSuite suite) {
        suite.setUpdatedAt(Instant.now());
        suites.put(suite.getId(), suite);
    }

    @Override
    public boolean deleteSuite(String suiteId) {
        return suites.remove(suiteId) != null;
    }

    // ==================== Result Tracking ====================

    @Override
    public void recordTestResult(EvalTestResult result) {
        testResults.computeIfAbsent(result.getTestCaseId(), k -> new ArrayList<>())
                .add(result);
        log.debug("Recorded test result for case: {}, passed: {}",
                result.getTestCaseId(), result.isPassed());
    }

    @Override
    public void recordSuiteResult(EvalSuiteResult result) {
        suiteResults.computeIfAbsent(result.getSuiteId(), k -> new ArrayList<>())
                .add(result);
        log.debug("Recorded suite result for suite: {}, passed: {}",
                result.getSuiteId(), result.isPassed());
    }

    @Override
    public Optional<EvalTestResult> getLatestTestResult(String testCaseId) {
        List<EvalTestResult> results = testResults.get(testCaseId);
        if (results == null || results.isEmpty()) {
            return Optional.empty();
        }
        return results.stream()
                .max(Comparator.comparing(EvalTestResult::getCompletedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())));
    }

    @Override
    public List<EvalTestResult> getTestResultHistory(String testCaseId, int limit) {
        List<EvalTestResult> results = testResults.get(testCaseId);
        if (results == null) {
            return List.of();
        }
        return results.stream()
                .sorted(Comparator.comparing(EvalTestResult::getCompletedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public List<EvalTestResult> getTestResultsInRange(String testCaseId, Instant from, Instant to) {
        List<EvalTestResult> results = testResults.get(testCaseId);
        if (results == null) {
            return List.of();
        }
        return results.stream()
                .filter(r -> r.getCompletedAt() != null)
                .filter(r -> !r.getCompletedAt().isBefore(from))
                .filter(r -> r.getCompletedAt().isBefore(to))
                .sorted(Comparator.comparing(EvalTestResult::getCompletedAt).reversed())
                .collect(Collectors.toList());
    }

    @Override
    public Optional<EvalSuiteResult> getLatestSuiteResult(String suiteId) {
        List<EvalSuiteResult> results = suiteResults.get(suiteId);
        if (results == null || results.isEmpty()) {
            return Optional.empty();
        }
        return results.stream()
                .max(Comparator.comparing(EvalSuiteResult::getCompletedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())));
    }

    @Override
    public List<EvalSuiteResult> getSuiteResultHistory(String suiteId, int limit) {
        List<EvalSuiteResult> results = suiteResults.get(suiteId);
        if (results == null) {
            return List.of();
        }
        return results.stream()
                .sorted(Comparator.comparing(EvalSuiteResult::getCompletedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public List<EvalTestResult> getResultsForFactSheet(Long factSheetId, int limit) {
        return testResults.values().stream()
                .flatMap(List::stream)
                .filter(r -> Objects.equals(r.getFactSheetId(), factSheetId))
                .sorted(Comparator.comparing(EvalTestResult::getCompletedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit)
                .collect(Collectors.toList());
    }

    // ==================== Metrics and Analysis ====================

    @Override
    public double getTestCasePassRate(String testCaseId, int windowDays) {
        Instant cutoff = Instant.now().minus(windowDays, ChronoUnit.DAYS);
        List<EvalTestResult> results = testResults.get(testCaseId);
        if (results == null) {
            return 0.0;
        }

        List<EvalTestResult> recentResults = results.stream()
                .filter(r -> r.getCompletedAt() != null && r.getCompletedAt().isAfter(cutoff))
                .toList();

        if (recentResults.isEmpty()) {
            return 0.0;
        }

        long passed = recentResults.stream().filter(EvalTestResult::isPassed).count();
        return (double) passed / recentResults.size();
    }

    @Override
    public double getSuitePassRate(String suiteId, int windowDays) {
        Instant cutoff = Instant.now().minus(windowDays, ChronoUnit.DAYS);
        List<EvalSuiteResult> results = suiteResults.get(suiteId);
        if (results == null) {
            return 0.0;
        }

        List<EvalSuiteResult> recentResults = results.stream()
                .filter(r -> r.getCompletedAt() != null && r.getCompletedAt().isAfter(cutoff))
                .toList();

        if (recentResults.isEmpty()) {
            return 0.0;
        }

        long passed = recentResults.stream().filter(EvalSuiteResult::isPassed).count();
        return (double) passed / recentResults.size();
    }

    @Override
    public double getTestCaseAverageScore(String testCaseId, int windowDays) {
        Instant cutoff = Instant.now().minus(windowDays, ChronoUnit.DAYS);
        List<EvalTestResult> results = testResults.get(testCaseId);
        if (results == null) {
            return 0.0;
        }

        return results.stream()
                .filter(r -> r.getCompletedAt() != null && r.getCompletedAt().isAfter(cutoff))
                .mapToDouble(EvalTestResult::getScore)
                .average()
                .orElse(0.0);
    }

    @Override
    public double getTestCaseScoreTrend(String testCaseId, int windowDays) {
        List<EvalTestResult> history = getTestResultHistory(testCaseId, 10);
        if (history.size() < 2) {
            return 0.0;
        }

        // Simple linear regression for trend
        int n = history.size();
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;

        for (int i = 0; i < n; i++) {
            double x = i;
            double y = history.get(n - 1 - i).getScore(); // Oldest first
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumX2 += x * x;
        }

        double slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX);
        return slope;
    }

    @Override
    public Map<String, Double> getFactSheetMetrics(Long factSheetId) {
        Map<String, Double> metrics = new HashMap<>();

        List<EvalCase> cases = getTestCasesForFactSheet(factSheetId);
        metrics.put("total_test_cases", (double) cases.size());
        metrics.put("enabled_test_cases", (double) cases.stream()
                .filter(EvalCase::isEnabled).count());

        List<EvalTestResult> results = getResultsForFactSheet(factSheetId, 100);
        metrics.put("total_results", (double) results.size());

        if (!results.isEmpty()) {
            long passed = results.stream().filter(EvalTestResult::isPassed).count();
            metrics.put("pass_rate", (double) passed / results.size());
            metrics.put("average_score", results.stream()
                    .mapToDouble(EvalTestResult::getScore).average().orElse(0.0));
        } else {
            metrics.put("pass_rate", 0.0);
            metrics.put("average_score", 0.0);
        }

        return metrics;
    }

    @Override
    public List<EvalCase> getFailingTestCases(Long factSheetId) {
        return getTestCasesForFactSheet(factSheetId).stream()
                .filter(tc -> {
                    double passRate = getTestCasePassRate(tc.getId(), 7);
                    return passRate < 0.5; // Consider failing if < 50% pass rate
                })
                .collect(Collectors.toList());
    }

    // ==================== Cleanup ====================

    @Override
    public int cleanupOldResults(int retentionDays) {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int deleted = 0;

        for (List<EvalTestResult> results : testResults.values()) {
            int before = results.size();
            results.removeIf(r -> r.getCompletedAt() != null && r.getCompletedAt().isBefore(cutoff));
            deleted += before - results.size();
        }

        for (List<EvalSuiteResult> results : suiteResults.values()) {
            int before = results.size();
            results.removeIf(r -> r.getCompletedAt() != null && r.getCompletedAt().isBefore(cutoff));
            deleted += before - results.size();
        }

        log.info("Cleaned up {} old eval results", deleted);
        return deleted;
    }
}
