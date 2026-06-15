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
package ai.kompile.app.eval.service;

import ai.kompile.app.eval.domain.EvalCaseEntity;
import ai.kompile.app.eval.domain.EvalSuiteEntity;
import ai.kompile.app.eval.domain.EvalSuiteResultEntity;
import ai.kompile.app.eval.domain.EvalTestResultEntity;
import ai.kompile.app.eval.repository.EvalCaseRepository;
import ai.kompile.app.eval.repository.EvalSuiteRepository;
import ai.kompile.app.eval.repository.EvalSuiteResultRepository;
import ai.kompile.app.eval.repository.EvalTestResultRepository;
import ai.kompile.react.eval.EvalTracker;
import ai.kompile.react.eval.model.EvalCase;
import ai.kompile.react.eval.model.EvalSuite;
import ai.kompile.react.eval.model.EvalSuiteResult;
import ai.kompile.react.eval.model.EvalTestResult;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * JPA-backed implementation of EvalTracker for database persistence.
 * This is marked as @Primary to override the in-memory implementation.
 */
@Service
@Primary
@RequiredArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED, force = true)
@Slf4j
public class JpaEvalTracker implements EvalTracker {


    @Autowired
    private final EvalCaseRepository caseRepository;
    @Autowired
    private final EvalSuiteRepository suiteRepository;
    @Autowired
    private final EvalTestResultRepository testResultRepository;
    @Autowired
    private final EvalSuiteResultRepository suiteResultRepository;
    @Autowired
    private final EvalEntityConverter converter;

    // ==================== Test Case Management ====================

    @Override
    @Transactional
    public void registerTestCase(EvalCase testCase) {
        if (testCase.getId() == null) {
            testCase = EvalCase.builder()
                    .id(UUID.randomUUID().toString())
                    .name(testCase.getName())
                    .description(testCase.getDescription())
                    .factSheetId(testCase.getFactSheetId())
                    .factSheetName(testCase.getFactSheetName())
                    .query(testCase.getQuery())
                    .expectedAnswer(testCase.getExpectedAnswer())
                    .expectedFacts(testCase.getExpectedFacts())
                    .forbiddenFacts(testCase.getForbiddenFacts())
                    .expectedEntities(testCase.getExpectedEntities())
                    .expectedToolCalls(testCase.getExpectedToolCalls())
                    .evaluationTypes(testCase.getEvaluationTypes())
                    .thresholds(testCase.getThresholds())
                    .tags(testCase.getTags())
                    .priority(testCase.getPriority())
                    .enabled(testCase.isEnabled())
                    .timeoutMs(testCase.getTimeoutMs())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .metadata(testCase.getMetadata())
                    .build();
        }
        EvalCaseEntity entity = converter.toEntity(testCase);
        caseRepository.save(entity);
        log.debug("Registered test case: {}", testCase.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EvalCase> getTestCase(String testCaseId) {
        return caseRepository.findById(testCaseId)
                .map(converter::toModel);
    }

    @Override
    @Transactional(readOnly = true)
    public List<EvalCase> getAllTestCases() {
        return caseRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(converter::toModel)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<EvalCase> getTestCasesForFactSheet(Long factSheetId) {
        return caseRepository.findByFactSheetIdOrderByPriorityDesc(factSheetId).stream()
                .map(converter::toModel)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<EvalCase> getTestCasesByTag(String tag) {
        return caseRepository.findByTagsContaining(tag).stream()
                .map(converter::toModel)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void updateTestCase(EvalCase testCase) {
        Optional<EvalCaseEntity> existing = caseRepository.findById(testCase.getId());
        if (existing.isEmpty()) {
            log.warn("Test case not found for update: {}", testCase.getId());
            return;
        }

        EvalCaseEntity entity = converter.toEntity(testCase);
        // Preserve the suite relationship
        entity.setSuite(existing.get().getSuite());
        caseRepository.save(entity);
        log.debug("Updated test case: {}", testCase.getId());
    }

    @Override
    @Transactional
    public boolean deleteTestCase(String testCaseId) {
        if (caseRepository.existsById(testCaseId)) {
            caseRepository.deleteById(testCaseId);
            log.debug("Deleted test case: {}", testCaseId);
            return true;
        }
        return false;
    }

    // ==================== Suite Management ====================

    @Override
    @Transactional
    public void registerSuite(EvalSuite suite) {
        if (suite.getId() == null) {
            suite = EvalSuite.builder()
                    .id(UUID.randomUUID().toString())
                    .name(suite.getName())
                    .description(suite.getDescription())
                    .factSheetId(suite.getFactSheetId())
                    .tags(suite.getTags())
                    .enabled(suite.isEnabled())
                    .requiredPassRate(suite.getRequiredPassRate())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .metadata(suite.getMetadata())
                    .build();
        }

        EvalSuiteEntity entity = converter.toEntity(suite);
        suiteRepository.save(entity);

        // Handle test cases if provided
        if (suite.getTestCases() != null && !suite.getTestCases().isEmpty()) {
            for (EvalCase testCase : suite.getTestCases()) {
                EvalCaseEntity caseEntity = converter.toEntity(testCase);
                caseEntity.setSuite(entity);
                caseRepository.save(caseEntity);
            }
        }

        log.debug("Registered suite: {}", suite.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EvalSuite> getSuite(String suiteId) {
        return suiteRepository.findByIdWithTestCases(suiteId)
                .map(entity -> converter.toModel(entity, true));
    }

    @Override
    @Transactional(readOnly = true)
    public List<EvalSuite> getAllSuites() {
        return suiteRepository.findAllByOrderByNameAsc().stream()
                .map(entity -> converter.toModel(entity, false))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<EvalSuite> getSuitesForFactSheet(Long factSheetId) {
        return suiteRepository.findByFactSheetIdOrderByNameAsc(factSheetId).stream()
                .map(entity -> converter.toModel(entity, false))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void updateSuite(EvalSuite suite) {
        Optional<EvalSuiteEntity> existing = suiteRepository.findById(suite.getId());
        if (existing.isEmpty()) {
            log.warn("Suite not found for update: {}", suite.getId());
            return;
        }

        EvalSuiteEntity entity = converter.toEntity(suite);
        // Preserve the test cases relationship - don't overwrite
        entity.setTestCases(existing.get().getTestCases());
        suiteRepository.save(entity);
        log.debug("Updated suite: {}", suite.getId());
    }

    @Override
    @Transactional
    public boolean deleteSuite(String suiteId) {
        if (suiteRepository.existsById(suiteId)) {
            // Delete associated results first
            suiteResultRepository.deleteBySuiteId(suiteId);
            suiteRepository.deleteById(suiteId);
            log.debug("Deleted suite: {}", suiteId);
            return true;
        }
        return false;
    }

    // ==================== Result Tracking ====================

    @Override
    @Transactional
    public void recordTestResult(EvalTestResult result) {
        if (result.getId() == null) {
            result = EvalTestResult.builder()
                    .id(UUID.randomUUID().toString())
                    .testCaseId(result.getTestCaseId())
                    .testCaseName(result.getTestCaseName())
                    .suiteId(result.getSuiteId())
                    .factSheetId(result.getFactSheetId())
                    .executionId(result.getExecutionId())
                    .passed(result.isPassed())
                    .score(result.getScore())
                    .query(result.getQuery())
                    .expectedAnswer(result.getExpectedAnswer())
                    .actualAnswer(result.getActualAnswer())
                    .retrievedDocuments(result.getRetrievedDocuments())
                    .toolCalls(result.getToolCalls())
                    .stepsExecuted(result.getStepsExecuted())
                    .evaluationResults(result.getEvaluationResults())
                    .scores(result.getScores())
                    .passedByType(result.getPassedByType())
                    .failureReasons(result.getFailureReasons())
                    .startedAt(result.getStartedAt())
                    .completedAt(result.getCompletedAt() != null ? result.getCompletedAt() : Instant.now())
                    .executionTimeMs(result.getExecutionTimeMs())
                    .totalTokens(result.getTotalTokens())
                    .metadata(result.getMetadata())
                    .build();
        }

        EvalTestResultEntity entity = converter.toEntity(result);
        testResultRepository.save(entity);
        log.debug("Recorded test result: {} for test case: {}", result.getId(), result.getTestCaseId());
    }

    @Override
    @Transactional
    public void recordSuiteResult(EvalSuiteResult result) {
        if (result.getId() == null) {
            result = EvalSuiteResult.builder()
                    .id(UUID.randomUUID().toString())
                    .suiteId(result.getSuiteId())
                    .suiteName(result.getSuiteName())
                    .factSheetId(result.getFactSheetId())
                    .passed(result.isPassed())
                    .passRate(result.getPassRate())
                    .averageScore(result.getAverageScore())
                    .passedCount(result.getPassedCount())
                    .failedCount(result.getFailedCount())
                    .skippedCount(result.getSkippedCount())
                    .totalCount(result.getTotalCount())
                    .averageScoresByType(result.getAverageScoresByType())
                    .passRatesByType(result.getPassRatesByType())
                    .failedTests(result.getFailedTests())
                    .startedAt(result.getStartedAt())
                    .completedAt(result.getCompletedAt() != null ? result.getCompletedAt() : Instant.now())
                    .executionTimeMs(result.getExecutionTimeMs())
                    .totalTokens(result.getTotalTokens())
                    .metadata(result.getMetadata())
                    .build();
        }

        EvalSuiteResultEntity entity = converter.toEntity(result);
        entity = suiteResultRepository.save(entity);

        // Save test results with suite result reference
        if (result.getTestResults() != null && !result.getTestResults().isEmpty()) {
            for (EvalTestResult testResult : result.getTestResults()) {
                EvalTestResultEntity testEntity = converter.toEntity(testResult);
                testEntity.setSuiteResult(entity);
                testResultRepository.save(testEntity);
            }
        }

        log.debug("Recorded suite result: {} for suite: {}", result.getId(), result.getSuiteId());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EvalTestResult> getLatestTestResult(String testCaseId) {
        return testResultRepository.findFirstByTestCaseIdOrderByCompletedAtDesc(testCaseId)
                .map(converter::toModel);
    }

    @Override
    @Transactional(readOnly = true)
    public List<EvalTestResult> getTestResultHistory(String testCaseId, int limit) {
        return testResultRepository.findByTestCaseIdOrderByCompletedAtDesc(testCaseId).stream()
                .limit(limit)
                .map(converter::toModel)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<EvalTestResult> getTestResultsInRange(String testCaseId, Instant from, Instant to) {
        return testResultRepository.findByTestCaseIdAndCompletedAtBetweenOrderByCompletedAtDesc(
                testCaseId, from, to).stream()
                .map(converter::toModel)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EvalSuiteResult> getLatestSuiteResult(String suiteId) {
        return suiteResultRepository.findFirstBySuiteIdOrderByCompletedAtDesc(suiteId)
                .map(entity -> converter.toModel(entity, false));
    }

    @Override
    @Transactional(readOnly = true)
    public List<EvalSuiteResult> getSuiteResultHistory(String suiteId, int limit) {
        return suiteResultRepository.findBySuiteIdOrderByCompletedAtDesc(suiteId).stream()
                .limit(limit)
                .map(entity -> converter.toModel(entity, false))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<EvalTestResult> getResultsForFactSheet(Long factSheetId, int limit) {
        return testResultRepository.findByFactSheetIdOrderByCompletedAtDesc(
                factSheetId, PageRequest.of(0, limit)).stream()
                .map(converter::toModel)
                .collect(Collectors.toList());
    }

    // ==================== Metrics and Analysis ====================

    @Override
    @Transactional(readOnly = true)
    public double getTestCasePassRate(String testCaseId, int windowDays) {
        Instant since = Instant.now().minus(windowDays, ChronoUnit.DAYS);
        long passed = testResultRepository.countPassedSince(testCaseId, since);
        long total = testResultRepository.countSince(testCaseId, since);
        return total > 0 ? (double) passed / total : 0.0;
    }

    @Override
    @Transactional(readOnly = true)
    public double getSuitePassRate(String suiteId, int windowDays) {
        Instant since = Instant.now().minus(windowDays, ChronoUnit.DAYS);
        long passed = suiteResultRepository.countPassedSince(suiteId, since);
        long total = suiteResultRepository.countSince(suiteId, since);
        return total > 0 ? (double) passed / total : 0.0;
    }

    @Override
    @Transactional(readOnly = true)
    public double getTestCaseAverageScore(String testCaseId, int windowDays) {
        Instant since = Instant.now().minus(windowDays, ChronoUnit.DAYS);
        Double avg = testResultRepository.getAverageScoreSince(testCaseId, since);
        return avg != null ? avg : 0.0;
    }

    @Override
    @Transactional(readOnly = true)
    public double getTestCaseScoreTrend(String testCaseId, int windowDays) {
        Instant since = Instant.now().minus(windowDays, ChronoUnit.DAYS);
        List<Object[]> scores = testResultRepository.getScoresSince(testCaseId, since);

        if (scores.size() < 2) {
            return 0.0;
        }

        // Simple linear regression for trend
        int n = scores.size();
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;

        for (int i = 0; i < n; i++) {
            double x = i;
            double y = (Double) scores.get(i)[0];
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumX2 += x * x;
        }

        double denominator = n * sumX2 - sumX * sumX;
        if (denominator == 0) {
            return 0.0;
        }

        return (n * sumXY - sumX * sumY) / denominator;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Double> getFactSheetMetrics(Long factSheetId) {
        Map<String, Double> metrics = new HashMap<>();
        Instant since = Instant.now().minus(30, ChronoUnit.DAYS);

        // Test result metrics
        Object[] testMetrics = extractMetricsArray(testResultRepository.getFactSheetMetrics(factSheetId, since));
        if (testMetrics != null && testMetrics.length > 0 && testMetrics[0] != null) {
            long totalTests = extractNumber(testMetrics[0]).longValue();
            long passedTests = testMetrics.length > 1 && testMetrics[1] != null ? extractNumber(testMetrics[1]).longValue() : 0;
            Double avgScore = testMetrics.length > 2 && testMetrics[2] != null ? extractDouble(testMetrics[2]) : 0.0;

            metrics.put("totalTestRuns", (double) totalTests);
            metrics.put("passedTestRuns", (double) passedTests);
            metrics.put("testPassRate", totalTests > 0 ? (double) passedTests / totalTests : 0.0);
            metrics.put("averageTestScore", avgScore);
        }

        // Suite result metrics
        Object[] suiteMetrics = extractMetricsArray(suiteResultRepository.getFactSheetSuiteMetrics(factSheetId, since));
        if (suiteMetrics != null && suiteMetrics.length > 0 && suiteMetrics[0] != null) {
            long totalSuites = extractNumber(suiteMetrics[0]).longValue();
            long passedSuites = suiteMetrics.length > 1 && suiteMetrics[1] != null ? extractNumber(suiteMetrics[1]).longValue() : 0;
            Double avgPassRate = suiteMetrics.length > 2 && suiteMetrics[2] != null ? extractDouble(suiteMetrics[2]) : 0.0;
            Double avgScore = suiteMetrics.length > 3 && suiteMetrics[3] != null ? extractDouble(suiteMetrics[3]) : 0.0;

            metrics.put("totalSuiteRuns", (double) totalSuites);
            metrics.put("passedSuiteRuns", (double) passedSuites);
            metrics.put("suitePassRate", totalSuites > 0 ? (double) passedSuites / totalSuites : 0.0);
            metrics.put("averageSuitePassRate", avgPassRate);
            metrics.put("averageSuiteScore", avgScore);
        }

        // Count test cases and suites
        metrics.put("totalTestCases", (double) caseRepository.countByFactSheetId(factSheetId));
        metrics.put("totalSuites", (double) suiteRepository.countByFactSheetId(factSheetId));

        return metrics;
    }

    /**
     * Safely extracts the metrics array from JPA query result.
     * JPA/Hibernate can return results in different formats depending on configuration:
     * - Object[] directly with values
     * - Object[] where first element is itself an Object[] (nested)
     */
    private Object[] extractMetricsArray(Object result) {
        if (result == null) {
            return null;
        }
        if (!(result instanceof Object[])) {
            return null;
        }
        Object[] arr = (Object[]) result;
        if (arr.length == 0) {
            return null;
        }
        // Check if the result is nested (first element is itself an Object[])
        if (arr[0] instanceof Object[]) {
            return (Object[]) arr[0];
        }
        return arr;
    }

    /**
     * Safely extracts a Number from an object that might be Number or other numeric type.
     */
    private Number extractNumber(Object value) {
        if (value instanceof Number) {
            return (Number) value;
        }
        // Fallback for unexpected types
        return 0L;
    }

    /**
     * Safely extracts a Double from an object.
     */
    private Double extractDouble(Object value) {
        if (value instanceof Double) {
            return (Double) value;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return 0.0;
    }

    @Override
    @Transactional(readOnly = true)
    public List<EvalCase> getFailingTestCases(Long factSheetId) {
        Instant since = Instant.now().minus(30, ChronoUnit.DAYS);
        List<String> failingIds = testResultRepository.findConsistentlyFailingTestCases(
                factSheetId, since, 3);

        return failingIds.stream()
                .map(caseRepository::findById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(converter::toModel)
                .collect(Collectors.toList());
    }

    // ==================== Cleanup ====================

    @Override
    @Transactional
    public int cleanupOldResults(int retentionDays) {
        Instant before = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int deletedTestResults = testResultRepository.deleteOlderThan(before);
        int deletedSuiteResults = suiteResultRepository.deleteOlderThan(before);
        log.info("Cleaned up {} test results and {} suite results older than {} days",
                deletedTestResults, deletedSuiteResults, retentionDays);
        return deletedTestResults + deletedSuiteResults;
    }

    // ==================== Additional Helper Methods ====================

    /**
     * Add a test case to a suite.
     */
    @Transactional
    public void addTestCaseToSuite(String testCaseId, String suiteId) {
        Optional<EvalCaseEntity> testCase = caseRepository.findById(testCaseId);
        Optional<EvalSuiteEntity> suite = suiteRepository.findById(suiteId);

        if (testCase.isPresent() && suite.isPresent()) {
            testCase.get().setSuite(suite.get());
            caseRepository.save(testCase.get());
            log.debug("Added test case {} to suite {}", testCaseId, suiteId);
        }
    }

    /**
     * Remove a test case from its suite.
     */
    @Transactional
    public void removeTestCaseFromSuite(String testCaseId) {
        Optional<EvalCaseEntity> testCase = caseRepository.findById(testCaseId);
        if (testCase.isPresent()) {
            testCase.get().setSuite(null);
            caseRepository.save(testCase.get());
            log.debug("Removed test case {} from suite", testCaseId);
        }
    }

    /**
     * Get test cases in a suite.
     */
    @Transactional(readOnly = true)
    public List<EvalCase> getTestCasesInSuite(String suiteId) {
        return caseRepository.findBySuiteId(suiteId).stream()
                .map(converter::toModel)
                .collect(Collectors.toList());
    }

    /**
     * Get unassigned test cases for a fact sheet.
     */
    @Transactional(readOnly = true)
    public List<EvalCase> getUnassignedTestCasesForFactSheet(Long factSheetId) {
        return caseRepository.findUnassignedByFactSheet(factSheetId).stream()
                .map(converter::toModel)
                .collect(Collectors.toList());
    }
}
