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
package ai.kompile.app.web.controllers;

import ai.kompile.app.eval.service.EvalSetService;
import ai.kompile.core.evaluation.EvaluationType;
import ai.kompile.react.eval.model.EvalCase;
import ai.kompile.react.eval.model.EvalSuite;
import ai.kompile.react.eval.model.EvalSuiteResult;
import ai.kompile.react.eval.model.EvalTestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * REST Controller for managing evaluation sets (suites and test cases).
 * Provides CRUD operations for evaluation management.
 */
@RestController
@RequestMapping("/api/eval-sets")
public class ManagedEvalController {

    private static final Logger logger = LoggerFactory.getLogger(ManagedEvalController.class);

    private final EvalSetService evalSetService;

    public ManagedEvalController(EvalSetService evalSetService) {
        this.evalSetService = evalSetService;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SUITE OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * List all evaluation suites.
     */
    @GetMapping
    public ResponseEntity<List<EvalSuiteDto>> getAllSuites() {
        List<EvalSuite> suites = evalSetService.getSuitesForActiveFactSheet();
        List<EvalSuiteDto> dtos = suites.stream().map(this::toSuiteDto).toList();
        return ResponseEntity.ok(dtos);
    }

    /**
     * Get suites for a specific fact sheet.
     */
    @GetMapping("/fact-sheet/{factSheetId}")
    public ResponseEntity<List<EvalSuiteDto>> getSuitesForFactSheet(
            @PathVariable("factSheetId") Long factSheetId) {
        List<EvalSuite> suites = evalSetService.getSuitesForFactSheet(factSheetId);
        List<EvalSuiteDto> dtos = suites.stream().map(this::toSuiteDto).toList();
        return ResponseEntity.ok(dtos);
    }

    /**
     * Get a suite by ID with full details.
     */
    @GetMapping("/{suiteId}")
    public ResponseEntity<EvalSuiteDto> getSuite(@PathVariable("suiteId") String suiteId) {
        return evalSetService.getSuiteById(suiteId)
                .map(suite -> ResponseEntity.ok(toSuiteDto(suite)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Create a new evaluation suite.
     */
    @PostMapping
    public ResponseEntity<EvalSuiteDto> createSuite(@RequestBody CreateSuiteRequest request) {
        try {
            EvalSuite suite = evalSetService.createSuiteForFactSheet(
                    request.factSheetId(),
                    request.name(),
                    request.description()
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(toSuiteDto(suite));
        } catch (Exception e) {
            logger.error("Failed to create suite", e);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Update an existing suite.
     */
    @PutMapping("/{suiteId}")
    public ResponseEntity<Void> updateSuite(
            @PathVariable("suiteId") String suiteId,
            @RequestBody UpdateSuiteRequest request) {
        try {
            EvalSuite existing = evalSetService.getSuiteById(suiteId).orElse(null);
            if (existing == null) {
                return ResponseEntity.notFound().build();
            }

            EvalSuite updated = EvalSuite.builder()
                    .id(suiteId)
                    .name(request.name() != null ? request.name() : existing.getName())
                    .description(request.description() != null ? request.description() : existing.getDescription())
                    .factSheetId(existing.getFactSheetId())
                    .enabled(request.enabled() != null ? request.enabled() : existing.isEnabled())
                    .requiredPassRate(request.requiredPassRate() != null ? request.requiredPassRate() : existing.getRequiredPassRate())
                    .tags(request.tags() != null ? request.tags() : existing.getTags())
                    .createdAt(existing.getCreatedAt())
                    .metadata(existing.getMetadata())
                    .build();

            evalSetService.updateSuite(updated);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            logger.error("Failed to update suite", e);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Delete a suite.
     */
    @DeleteMapping("/{suiteId}")
    public ResponseEntity<Void> deleteSuite(@PathVariable("suiteId") String suiteId) {
        boolean deleted = evalSetService.deleteSuite(suiteId);
        return deleted ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // TEST CASE OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Get all test cases in a suite.
     */
    @GetMapping("/{suiteId}/cases")
    public ResponseEntity<List<EvalCaseDto>> getTestCasesInSuite(
            @PathVariable("suiteId") String suiteId) {
        List<EvalCase> cases = evalSetService.getTestCasesInSuite(suiteId);
        List<EvalCaseDto> dtos = cases.stream().map(this::toCaseDto).toList();
        return ResponseEntity.ok(dtos);
    }

    /**
     * Get a test case by ID.
     */
    @GetMapping("/cases/{caseId}")
    public ResponseEntity<EvalCaseDto> getTestCase(@PathVariable("caseId") String caseId) {
        return evalSetService.getTestCaseById(caseId)
                .map(tc -> ResponseEntity.ok(toCaseDto(tc)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Create a new test case in a suite.
     */
    @PostMapping("/{suiteId}/cases")
    public ResponseEntity<EvalCaseDto> createTestCase(
            @PathVariable("suiteId") String suiteId,
            @RequestBody CreateTestCaseRequest request) {
        try {
            EvalCase testCase = EvalCase.builder()
                    .name(request.name())
                    .description(request.description())
                    .factSheetId(request.factSheetId())
                    .query(request.query())
                    .expectedAnswer(request.expectedAnswer())
                    .expectedFacts(request.expectedFacts())
                    .forbiddenFacts(request.forbiddenFacts())
                    .expectedEntities(request.expectedEntities())
                    .expectedToolCalls(request.expectedToolCalls())
                    .evaluationTypes(request.evaluationTypes())
                    .thresholds(request.thresholds())
                    .tags(request.tags())
                    .priority(request.priority() != null ? request.priority() : 3)
                    .enabled(request.enabled() != null ? request.enabled() : true)
                    .timeoutMs(request.timeoutMs() != null ? request.timeoutMs() : 30000L)
                    .build();

            EvalCase created = evalSetService.createTestCaseInSuite(suiteId, testCase);
            return ResponseEntity.status(HttpStatus.CREATED).body(toCaseDto(created));
        } catch (Exception e) {
            logger.error("Failed to create test case", e);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Update an existing test case.
     */
    @PutMapping("/cases/{caseId}")
    public ResponseEntity<Void> updateTestCase(
            @PathVariable("caseId") String caseId,
            @RequestBody UpdateTestCaseRequest request) {
        try {
            EvalCase existing = evalSetService.getTestCaseById(caseId).orElse(null);
            if (existing == null) {
                return ResponseEntity.notFound().build();
            }

            EvalCase updated = EvalCase.builder()
                    .id(caseId)
                    .name(request.name() != null ? request.name() : existing.getName())
                    .description(request.description() != null ? request.description() : existing.getDescription())
                    .factSheetId(existing.getFactSheetId())
                    .factSheetName(existing.getFactSheetName())
                    .query(request.query() != null ? request.query() : existing.getQuery())
                    .expectedAnswer(request.expectedAnswer() != null ? request.expectedAnswer() : existing.getExpectedAnswer())
                    .expectedFacts(request.expectedFacts() != null ? request.expectedFacts() : existing.getExpectedFacts())
                    .forbiddenFacts(request.forbiddenFacts() != null ? request.forbiddenFacts() : existing.getForbiddenFacts())
                    .expectedEntities(request.expectedEntities() != null ? request.expectedEntities() : existing.getExpectedEntities())
                    .expectedToolCalls(request.expectedToolCalls() != null ? request.expectedToolCalls() : existing.getExpectedToolCalls())
                    .evaluationTypes(request.evaluationTypes() != null ? request.evaluationTypes() : existing.getEvaluationTypes())
                    .thresholds(request.thresholds() != null ? request.thresholds() : existing.getThresholds())
                    .tags(request.tags() != null ? request.tags() : existing.getTags())
                    .priority(request.priority() != null ? request.priority() : existing.getPriority())
                    .enabled(request.enabled() != null ? request.enabled() : existing.isEnabled())
                    .timeoutMs(request.timeoutMs() != null ? request.timeoutMs() : existing.getTimeoutMs())
                    .createdAt(existing.getCreatedAt())
                    .metadata(existing.getMetadata())
                    .build();

            evalSetService.updateTestCase(updated);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            logger.error("Failed to update test case", e);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Delete a test case.
     */
    @DeleteMapping("/cases/{caseId}")
    public ResponseEntity<Void> deleteTestCase(@PathVariable("caseId") String caseId) {
        boolean deleted = evalSetService.deleteTestCase(caseId);
        return deleted ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    /**
     * Move a test case to a different suite.
     */
    @PostMapping("/cases/{caseId}/move")
    public ResponseEntity<Void> moveTestCase(
            @PathVariable("caseId") String caseId,
            @RequestBody MoveTestCaseRequest request) {
        try {
            evalSetService.moveTestCaseToSuite(caseId, request.targetSuiteId());
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            logger.error("Failed to move test case", e);
            return ResponseEntity.badRequest().build();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // RESULTS OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Get result history for a suite.
     */
    @GetMapping("/{suiteId}/results")
    public ResponseEntity<List<EvalSuiteResultDto>> getSuiteResultHistory(
            @PathVariable("suiteId") String suiteId,
            @RequestParam(defaultValue = "10") int limit) {
        List<EvalSuiteResult> results = evalSetService.getSuiteResultHistory(suiteId, limit);
        List<EvalSuiteResultDto> dtos = results.stream().map(this::toSuiteResultDto).toList();
        return ResponseEntity.ok(dtos);
    }

    /**
     * Get result history for a test case.
     */
    @GetMapping("/cases/{caseId}/results")
    public ResponseEntity<List<EvalTestResultDto>> getTestCaseResultHistory(
            @PathVariable("caseId") String caseId,
            @RequestParam(defaultValue = "10") int limit) {
        List<EvalTestResult> results = evalSetService.getTestCaseResultHistory(caseId, limit);
        List<EvalTestResultDto> dtos = results.stream().map(this::toTestResultDto).toList();
        return ResponseEntity.ok(dtos);
    }

    /**
     * Get the latest result for a suite.
     */
    @GetMapping("/{suiteId}/results/latest")
    public ResponseEntity<EvalSuiteResultDto> getLatestSuiteResult(
            @PathVariable("suiteId") String suiteId) {
        return evalSetService.getLatestSuiteResult(suiteId)
                .map(result -> ResponseEntity.ok(toSuiteResultDto(result)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get the latest result for a test case.
     */
    @GetMapping("/cases/{caseId}/results/latest")
    public ResponseEntity<EvalTestResultDto> getLatestTestCaseResult(
            @PathVariable("caseId") String caseId) {
        return evalSetService.getLatestTestCaseResult(caseId)
                .map(result -> ResponseEntity.ok(toTestResultDto(result)))
                .orElse(ResponseEntity.notFound().build());
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // METRICS OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Get evaluation metrics for a fact sheet.
     */
    @GetMapping("/fact-sheet/{factSheetId}/metrics")
    public ResponseEntity<Map<String, Double>> getFactSheetMetrics(
            @PathVariable("factSheetId") Long factSheetId) {
        Map<String, Double> metrics = evalSetService.getFactSheetMetrics(factSheetId);
        return ResponseEntity.ok(metrics);
    }

    /**
     * Get consistently failing test cases for a fact sheet.
     */
    @GetMapping("/fact-sheet/{factSheetId}/failing")
    public ResponseEntity<List<EvalCaseDto>> getFailingTestCases(
            @PathVariable("factSheetId") Long factSheetId) {
        List<EvalCase> cases = evalSetService.getFailingTestCases(factSheetId);
        List<EvalCaseDto> dtos = cases.stream().map(this::toCaseDto).toList();
        return ResponseEntity.ok(dtos);
    }

    /**
     * Get pass rate for a test case.
     */
    @GetMapping("/cases/{caseId}/pass-rate")
    public ResponseEntity<Map<String, Object>> getTestCasePassRate(
            @PathVariable("caseId") String caseId,
            @RequestParam(defaultValue = "30") int windowDays) {
        double passRate = evalSetService.getTestCasePassRate(caseId, windowDays);
        double trend = evalSetService.getTestCaseScoreTrend(caseId, windowDays);
        return ResponseEntity.ok(Map.of(
                "passRate", passRate,
                "trend", trend,
                "windowDays", windowDays
        ));
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // IMPORT/EXPORT OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Export a suite as JSON.
     */
    @GetMapping("/{suiteId}/export")
    public ResponseEntity<EvalSuite> exportSuite(@PathVariable("suiteId") String suiteId) {
        EvalSuite suite = evalSetService.exportSuite(suiteId);
        return suite != null ? ResponseEntity.ok(suite) : ResponseEntity.notFound().build();
    }

    /**
     * Import a suite from JSON.
     */
    @PostMapping("/import")
    public ResponseEntity<EvalSuiteDto> importSuite(@RequestBody ImportSuiteRequest request) {
        try {
            EvalSuite imported = evalSetService.importSuite(request.suite(), request.targetFactSheetId());
            return ResponseEntity.status(HttpStatus.CREATED).body(toSuiteDto(imported));
        } catch (Exception e) {
            logger.error("Failed to import suite", e);
            return ResponseEntity.badRequest().build();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // DTO CLASSES
    // ═══════════════════════════════════════════════════════════════════════════════

    public record EvalSuiteDto(
            String id,
            String name,
            String description,
            Long factSheetId,
            boolean enabled,
            double requiredPassRate,
            int testCaseCount,
            List<String> tags,
            Instant createdAt,
            Instant updatedAt,
            List<EvalCaseDto> testCases
    ) {}

    public record EvalCaseDto(
            String id,
            String name,
            String description,
            Long factSheetId,
            String factSheetName,
            String query,
            String expectedAnswer,
            List<String> expectedFacts,
            List<String> forbiddenFacts,
            List<String> expectedEntities,
            List<String> expectedToolCalls,
            List<String> evaluationTypes,
            Map<String, Double> thresholds,
            List<String> tags,
            int priority,
            boolean enabled,
            long timeoutMs,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record EvalTestResultDto(
            String id,
            String testCaseId,
            String testCaseName,
            String suiteId,
            Long factSheetId,
            boolean passed,
            double score,
            String query,
            String expectedAnswer,
            String actualAnswer,
            List<String> retrievedDocuments,
            List<String> toolCalls,
            int stepsExecuted,
            Map<String, Double> scores,
            Map<String, Boolean> passedByType,
            List<String> failureReasons,
            Instant startedAt,
            Instant completedAt,
            long executionTimeMs,
            long totalTokens
    ) {}

    public record EvalSuiteResultDto(
            String id,
            String suiteId,
            String suiteName,
            Long factSheetId,
            boolean passed,
            double passRate,
            double averageScore,
            int passedCount,
            int failedCount,
            int skippedCount,
            int totalCount,
            Map<String, Double> averageScoresByType,
            Map<String, Double> passRatesByType,
            Map<String, List<String>> failedTests,
            Instant startedAt,
            Instant completedAt,
            long executionTimeMs,
            long totalTokens
    ) {}

    // Request DTOs
    public record CreateSuiteRequest(Long factSheetId, String name, String description) {}
    public record UpdateSuiteRequest(String name, String description, Boolean enabled, Double requiredPassRate, List<String> tags) {}
    public record CreateTestCaseRequest(
            String name,
            String description,
            Long factSheetId,
            String query,
            String expectedAnswer,
            List<String> expectedFacts,
            List<String> forbiddenFacts,
            List<String> expectedEntities,
            List<String> expectedToolCalls,
            List<EvaluationType> evaluationTypes,
            Map<EvaluationType, Double> thresholds,
            List<String> tags,
            Integer priority,
            Boolean enabled,
            Long timeoutMs
    ) {}
    public record UpdateTestCaseRequest(
            String name,
            String description,
            String query,
            String expectedAnswer,
            List<String> expectedFacts,
            List<String> forbiddenFacts,
            List<String> expectedEntities,
            List<String> expectedToolCalls,
            List<EvaluationType> evaluationTypes,
            Map<EvaluationType, Double> thresholds,
            List<String> tags,
            Integer priority,
            Boolean enabled,
            Long timeoutMs
    ) {}
    public record MoveTestCaseRequest(String targetSuiteId) {}
    public record ImportSuiteRequest(EvalSuite suite, Long targetFactSheetId) {}

    // ═══════════════════════════════════════════════════════════════════════════════
    // CONVERSION METHODS
    // ═══════════════════════════════════════════════════════════════════════════════

    private EvalSuiteDto toSuiteDto(EvalSuite suite) {
        List<EvalCaseDto> testCaseDtos = null;
        if (suite.getTestCases() != null && !suite.getTestCases().isEmpty()) {
            testCaseDtos = suite.getTestCases().stream().map(this::toCaseDto).toList();
        }
        return new EvalSuiteDto(
                suite.getId(),
                suite.getName(),
                suite.getDescription(),
                suite.getFactSheetId(),
                suite.isEnabled(),
                suite.getRequiredPassRate(),
                suite.getTestCaseCount(),
                suite.getTags(),
                suite.getCreatedAt(),
                suite.getUpdatedAt(),
                testCaseDtos
        );
    }

    private EvalCaseDto toCaseDto(EvalCase testCase) {
        List<String> evalTypes = testCase.getEvaluationTypes() != null
                ? testCase.getEvaluationTypes().stream().map(Enum::name).toList()
                : List.of();

        Map<String, Double> thresholds = testCase.getThresholds() != null
                ? testCase.getThresholds().entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(e -> e.getKey().name(), Map.Entry::getValue))
                : Map.of();

        return new EvalCaseDto(
                testCase.getId(),
                testCase.getName(),
                testCase.getDescription(),
                testCase.getFactSheetId(),
                testCase.getFactSheetName(),
                testCase.getQuery(),
                testCase.getExpectedAnswer(),
                testCase.getExpectedFacts(),
                testCase.getForbiddenFacts(),
                testCase.getExpectedEntities(),
                testCase.getExpectedToolCalls(),
                evalTypes,
                thresholds,
                testCase.getTags(),
                testCase.getPriority(),
                testCase.isEnabled(),
                testCase.getTimeoutMs(),
                testCase.getCreatedAt(),
                testCase.getUpdatedAt()
        );
    }

    private EvalTestResultDto toTestResultDto(EvalTestResult result) {
        Map<String, Double> scores = result.getScores() != null
                ? result.getScores().entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(e -> e.getKey().name(), Map.Entry::getValue))
                : Map.of();

        Map<String, Boolean> passedByType = result.getPassedByType() != null
                ? result.getPassedByType().entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(e -> e.getKey().name(), Map.Entry::getValue))
                : Map.of();

        return new EvalTestResultDto(
                result.getId(),
                result.getTestCaseId(),
                result.getTestCaseName(),
                result.getSuiteId(),
                result.getFactSheetId(),
                result.isPassed(),
                result.getScore(),
                result.getQuery(),
                result.getExpectedAnswer(),
                result.getActualAnswer(),
                result.getRetrievedDocuments(),
                result.getToolCalls(),
                result.getStepsExecuted(),
                scores,
                passedByType,
                result.getFailureReasons(),
                result.getStartedAt(),
                result.getCompletedAt(),
                result.getExecutionTimeMs(),
                result.getTotalTokens()
        );
    }

    private EvalSuiteResultDto toSuiteResultDto(EvalSuiteResult result) {
        Map<String, Double> scoresByType = result.getAverageScoresByType() != null
                ? result.getAverageScoresByType().entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(e -> e.getKey().name(), Map.Entry::getValue))
                : Map.of();

        Map<String, Double> ratesByType = result.getPassRatesByType() != null
                ? result.getPassRatesByType().entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(e -> e.getKey().name(), Map.Entry::getValue))
                : Map.of();

        return new EvalSuiteResultDto(
                result.getId(),
                result.getSuiteId(),
                result.getSuiteName(),
                result.getFactSheetId(),
                result.isPassed(),
                result.getPassRate(),
                result.getAverageScore(),
                result.getPassedCount(),
                result.getFailedCount(),
                result.getSkippedCount(),
                result.getTotalCount(),
                scoresByType,
                ratesByType,
                result.getFailedTests(),
                result.getStartedAt(),
                result.getCompletedAt(),
                result.getExecutionTimeMs(),
                result.getTotalTokens()
        );
    }
}
