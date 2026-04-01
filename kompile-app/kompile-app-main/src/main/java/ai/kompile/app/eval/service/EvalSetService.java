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

import ai.kompile.app.facts.domain.FactSheet;
import ai.kompile.app.facts.service.FactSheetService;
import ai.kompile.react.eval.model.EvalCase;
import ai.kompile.react.eval.model.EvalSuite;
import ai.kompile.react.eval.model.EvalSuiteResult;
import ai.kompile.react.eval.model.EvalTestResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Higher-level service for managed evaluation sets.
 * Provides UI-focused operations for evaluation management.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EvalSetService {

    private final JpaEvalTracker evalTracker;
    private final FactSheetService factSheetService;

    // ==================== Suite Operations ====================

    /**
     * Get all suites for the currently active fact sheet.
     */
    @Transactional(readOnly = true)
    public List<EvalSuite> getSuitesForActiveFactSheet() {
        FactSheet active = factSheetService.getActiveSheet();
        if (active == null) {
            return Collections.emptyList();
        }
        return evalTracker.getSuitesForFactSheet(active.getId());
    }

    /**
     * Get all suites for a specific fact sheet.
     */
    @Transactional(readOnly = true)
    public List<EvalSuite> getSuitesForFactSheet(Long factSheetId) {
        return evalTracker.getSuitesForFactSheet(factSheetId);
    }

    /**
     * Get a suite by ID with full details including test cases.
     */
    @Transactional(readOnly = true)
    public Optional<EvalSuite> getSuiteById(String suiteId) {
        return evalTracker.getSuite(suiteId);
    }

    /**
     * Create a new suite for a fact sheet.
     */
    @Transactional
    public EvalSuite createSuiteForFactSheet(Long factSheetId, String name, String description) {
        EvalSuite suite = EvalSuite.builder()
                .id(UUID.randomUUID().toString())
                .name(name)
                .description(description)
                .factSheetId(factSheetId)
                .enabled(true)
                .requiredPassRate(0.8)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .testCases(new ArrayList<>())
                .tags(new ArrayList<>())
                .metadata(new HashMap<>())
                .build();

        evalTracker.registerSuite(suite);
        log.info("Created evaluation suite '{}' for fact sheet {}", name, factSheetId);
        return suite;
    }

    /**
     * Update an existing suite.
     */
    @Transactional
    public void updateSuite(EvalSuite suite) {
        suite = EvalSuite.builder()
                .id(suite.getId())
                .name(suite.getName())
                .description(suite.getDescription())
                .factSheetId(suite.getFactSheetId())
                .enabled(suite.isEnabled())
                .requiredPassRate(suite.getRequiredPassRate())
                .createdAt(suite.getCreatedAt())
                .updatedAt(Instant.now())
                .tags(suite.getTags())
                .metadata(suite.getMetadata())
                .build();
        evalTracker.updateSuite(suite);
        log.info("Updated evaluation suite '{}'", suite.getId());
    }

    /**
     * Delete a suite and all its test cases.
     */
    @Transactional
    public boolean deleteSuite(String suiteId) {
        boolean deleted = evalTracker.deleteSuite(suiteId);
        if (deleted) {
            log.info("Deleted evaluation suite '{}'", suiteId);
        }
        return deleted;
    }

    // ==================== Test Case Operations ====================

    /**
     * Get all test cases in a suite.
     */
    @Transactional(readOnly = true)
    public List<EvalCase> getTestCasesInSuite(String suiteId) {
        return evalTracker.getTestCasesInSuite(suiteId);
    }

    /**
     * Get a test case by ID.
     */
    @Transactional(readOnly = true)
    public Optional<EvalCase> getTestCaseById(String testCaseId) {
        return evalTracker.getTestCase(testCaseId);
    }

    /**
     * Create a new test case and add it to a suite.
     */
    @Transactional
    public EvalCase createTestCaseInSuite(String suiteId, EvalCase testCase) {
        // Ensure ID is set
        if (testCase.getId() == null) {
            testCase = EvalCase.builder()
                    .id(UUID.randomUUID().toString())
                    .name(testCase.getName())
                    .description(testCase.getDescription())
                    .factSheetId(testCase.getFactSheetId())
                    .factSheetName(testCase.getFactSheetName())
                    .query(testCase.getQuery())
                    .expectedAnswer(testCase.getExpectedAnswer())
                    .expectedFacts(testCase.getExpectedFacts() != null ? testCase.getExpectedFacts() : new ArrayList<>())
                    .forbiddenFacts(testCase.getForbiddenFacts() != null ? testCase.getForbiddenFacts() : new ArrayList<>())
                    .expectedEntities(testCase.getExpectedEntities() != null ? testCase.getExpectedEntities() : new ArrayList<>())
                    .expectedToolCalls(testCase.getExpectedToolCalls() != null ? testCase.getExpectedToolCalls() : new ArrayList<>())
                    .evaluationTypes(testCase.getEvaluationTypes())
                    .thresholds(testCase.getThresholds() != null ? testCase.getThresholds() : new HashMap<>())
                    .tags(testCase.getTags() != null ? testCase.getTags() : new ArrayList<>())
                    .priority(testCase.getPriority())
                    .enabled(testCase.isEnabled())
                    .timeoutMs(testCase.getTimeoutMs())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .metadata(testCase.getMetadata() != null ? testCase.getMetadata() : new HashMap<>())
                    .build();
        }

        evalTracker.registerTestCase(testCase);
        evalTracker.addTestCaseToSuite(testCase.getId(), suiteId);
        log.info("Created test case '{}' in suite '{}'", testCase.getName(), suiteId);
        return testCase;
    }

    /**
     * Create a standalone test case (not in a suite).
     */
    @Transactional
    public EvalCase createTestCase(EvalCase testCase) {
        if (testCase.getId() == null) {
            testCase = EvalCase.builder()
                    .id(UUID.randomUUID().toString())
                    .name(testCase.getName())
                    .description(testCase.getDescription())
                    .factSheetId(testCase.getFactSheetId())
                    .factSheetName(testCase.getFactSheetName())
                    .query(testCase.getQuery())
                    .expectedAnswer(testCase.getExpectedAnswer())
                    .expectedFacts(testCase.getExpectedFacts() != null ? testCase.getExpectedFacts() : new ArrayList<>())
                    .forbiddenFacts(testCase.getForbiddenFacts() != null ? testCase.getForbiddenFacts() : new ArrayList<>())
                    .expectedEntities(testCase.getExpectedEntities() != null ? testCase.getExpectedEntities() : new ArrayList<>())
                    .expectedToolCalls(testCase.getExpectedToolCalls() != null ? testCase.getExpectedToolCalls() : new ArrayList<>())
                    .evaluationTypes(testCase.getEvaluationTypes())
                    .thresholds(testCase.getThresholds() != null ? testCase.getThresholds() : new HashMap<>())
                    .tags(testCase.getTags() != null ? testCase.getTags() : new ArrayList<>())
                    .priority(testCase.getPriority())
                    .enabled(testCase.isEnabled())
                    .timeoutMs(testCase.getTimeoutMs())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .metadata(testCase.getMetadata() != null ? testCase.getMetadata() : new HashMap<>())
                    .build();
        }

        evalTracker.registerTestCase(testCase);
        log.info("Created test case '{}'", testCase.getName());
        return testCase;
    }

    /**
     * Update an existing test case.
     */
    @Transactional
    public void updateTestCase(EvalCase testCase) {
        testCase = EvalCase.builder()
                .id(testCase.getId())
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
                .createdAt(testCase.getCreatedAt())
                .updatedAt(Instant.now())
                .metadata(testCase.getMetadata())
                .build();
        evalTracker.updateTestCase(testCase);
        log.info("Updated test case '{}'", testCase.getId());
    }

    /**
     * Delete a test case.
     */
    @Transactional
    public boolean deleteTestCase(String testCaseId) {
        boolean deleted = evalTracker.deleteTestCase(testCaseId);
        if (deleted) {
            log.info("Deleted test case '{}'", testCaseId);
        }
        return deleted;
    }

    /**
     * Move a test case to a different suite.
     */
    @Transactional
    public void moveTestCaseToSuite(String testCaseId, String newSuiteId) {
        evalTracker.removeTestCaseFromSuite(testCaseId);
        if (newSuiteId != null) {
            evalTracker.addTestCaseToSuite(testCaseId, newSuiteId);
        }
        log.info("Moved test case '{}' to suite '{}'", testCaseId, newSuiteId);
    }

    // ==================== Results Operations ====================

    /**
     * Get result history for a test case.
     */
    @Transactional(readOnly = true)
    public List<EvalTestResult> getTestCaseResultHistory(String testCaseId, int limit) {
        return evalTracker.getTestResultHistory(testCaseId, limit);
    }

    /**
     * Get result history for a suite.
     */
    @Transactional(readOnly = true)
    public List<EvalSuiteResult> getSuiteResultHistory(String suiteId, int limit) {
        return evalTracker.getSuiteResultHistory(suiteId, limit);
    }

    /**
     * Get the latest result for a test case.
     */
    @Transactional(readOnly = true)
    public Optional<EvalTestResult> getLatestTestCaseResult(String testCaseId) {
        return evalTracker.getLatestTestResult(testCaseId);
    }

    /**
     * Get the latest result for a suite.
     */
    @Transactional(readOnly = true)
    public Optional<EvalSuiteResult> getLatestSuiteResult(String suiteId) {
        return evalTracker.getLatestSuiteResult(suiteId);
    }

    // ==================== Metrics Operations ====================

    /**
     * Get metrics for a fact sheet.
     */
    @Transactional(readOnly = true)
    public Map<String, Double> getFactSheetMetrics(Long factSheetId) {
        return evalTracker.getFactSheetMetrics(factSheetId);
    }

    /**
     * Get consistently failing test cases for a fact sheet.
     */
    @Transactional(readOnly = true)
    public List<EvalCase> getFailingTestCases(Long factSheetId) {
        return evalTracker.getFailingTestCases(factSheetId);
    }

    /**
     * Get pass rate for a test case over a time window.
     */
    @Transactional(readOnly = true)
    public double getTestCasePassRate(String testCaseId, int windowDays) {
        return evalTracker.getTestCasePassRate(testCaseId, windowDays);
    }

    /**
     * Get score trend for a test case.
     */
    @Transactional(readOnly = true)
    public double getTestCaseScoreTrend(String testCaseId, int windowDays) {
        return evalTracker.getTestCaseScoreTrend(testCaseId, windowDays);
    }

    // ==================== Import/Export Operations ====================

    /**
     * Export a suite as a portable format.
     */
    @Transactional(readOnly = true)
    public EvalSuite exportSuite(String suiteId) {
        return evalTracker.getSuite(suiteId).orElse(null);
    }

    /**
     * Import test cases from a suite definition.
     */
    @Transactional
    public EvalSuite importSuite(EvalSuite importedSuite, Long targetFactSheetId) {
        // Create new suite with new ID
        EvalSuite newSuite = EvalSuite.builder()
                .id(UUID.randomUUID().toString())
                .name(importedSuite.getName())
                .description(importedSuite.getDescription())
                .factSheetId(targetFactSheetId)
                .enabled(importedSuite.isEnabled())
                .requiredPassRate(importedSuite.getRequiredPassRate())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .tags(importedSuite.getTags() != null ? new ArrayList<>(importedSuite.getTags()) : new ArrayList<>())
                .metadata(importedSuite.getMetadata() != null ? new HashMap<>(importedSuite.getMetadata()) : new HashMap<>())
                .build();

        evalTracker.registerSuite(newSuite);

        // Import test cases
        if (importedSuite.getTestCases() != null) {
            for (EvalCase testCase : importedSuite.getTestCases()) {
                EvalCase newTestCase = EvalCase.builder()
                        .id(UUID.randomUUID().toString())
                        .name(testCase.getName())
                        .description(testCase.getDescription())
                        .factSheetId(targetFactSheetId)
                        .query(testCase.getQuery())
                        .expectedAnswer(testCase.getExpectedAnswer())
                        .expectedFacts(testCase.getExpectedFacts() != null ? new ArrayList<>(testCase.getExpectedFacts()) : new ArrayList<>())
                        .forbiddenFacts(testCase.getForbiddenFacts() != null ? new ArrayList<>(testCase.getForbiddenFacts()) : new ArrayList<>())
                        .expectedEntities(testCase.getExpectedEntities() != null ? new ArrayList<>(testCase.getExpectedEntities()) : new ArrayList<>())
                        .expectedToolCalls(testCase.getExpectedToolCalls() != null ? new ArrayList<>(testCase.getExpectedToolCalls()) : new ArrayList<>())
                        .evaluationTypes(testCase.getEvaluationTypes())
                        .thresholds(testCase.getThresholds() != null ? new HashMap<>(testCase.getThresholds()) : new HashMap<>())
                        .tags(testCase.getTags() != null ? new ArrayList<>(testCase.getTags()) : new ArrayList<>())
                        .priority(testCase.getPriority())
                        .enabled(testCase.isEnabled())
                        .timeoutMs(testCase.getTimeoutMs())
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .metadata(testCase.getMetadata() != null ? new HashMap<>(testCase.getMetadata()) : new HashMap<>())
                        .build();

                evalTracker.registerTestCase(newTestCase);
                evalTracker.addTestCaseToSuite(newTestCase.getId(), newSuite.getId());
            }
        }

        log.info("Imported suite '{}' with {} test cases to fact sheet {}",
                newSuite.getName(),
                importedSuite.getTestCases() != null ? importedSuite.getTestCases().size() : 0,
                targetFactSheetId);

        return evalTracker.getSuite(newSuite.getId()).orElse(newSuite);
    }

    // ==================== Cleanup Operations ====================

    /**
     * Clean up old results.
     */
    @Transactional
    public int cleanupOldResults(int retentionDays) {
        return evalTracker.cleanupOldResults(retentionDays);
    }
}
